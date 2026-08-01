import {createHash, randomBytes, randomUUID} from "node:crypto";
import {initializeApp} from "firebase-admin/app";
import {getAuth} from "firebase-admin/auth";
import {FieldValue, Timestamp, getFirestore} from "firebase-admin/firestore";
import {getMessaging} from "firebase-admin/messaging";
import {HttpsError, onCall} from "firebase-functions/v2/https";
import {onSchedule} from "firebase-functions/v2/scheduler";
import {Allocation, equalSplit, exactSplit, percentSplit} from "./money.js";

initializeApp();
const db = getFirestore();
const REGION = "us-east1";
const callableOptions = {region: REGION, enforceAppCheck: true};

type Input = Record<string, unknown>;
type Member = {uid: string; name: string; initials: string; color: number; role: string; deleted?: boolean};

function uidOf(request: {auth?: {uid: string}}): string {
  if (!request.auth) throw new HttpsError("unauthenticated", "Sign in required");
  return request.auth.uid;
}

function text(data: Input, key: string, max = 120): string {
  const value = data[key];
  if (typeof value !== "string" || !value.trim() || value.length > max) throw new HttpsError("invalid-argument", `Invalid ${key}`);
  return value.trim();
}

function integer(data: Input, key: string, min = 0): number {
  const value = data[key];
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value < min) throw new HttpsError("invalid-argument", `Invalid ${key}`);
  return value;
}

function operationRef(uid: string, data: Input) {
  const key = text(data, "idempotencyKey", 80);
  if (!/^[A-Za-z0-9_-]{8,80}$/.test(key)) throw new HttpsError("invalid-argument", "Invalid idempotency key");
  return db.collection("operationKeys").doc(`${uid}_${key}`);
}

async function requireMember(groupId: string, uid: string): Promise<Member> {
  const snapshot = await db.doc(`groups/${groupId}/members/${uid}`).get();
  if (!snapshot.exists) throw new HttpsError("permission-denied", "Group membership required");
  return snapshot.data() as Member;
}

async function groupMembers(groupId: string): Promise<Member[]> {
  const snapshot = await db.collection(`groups/${groupId}/members`).get();
  return snapshot.docs.map((doc) => doc.data() as Member);
}

function cleanCurrency(value: unknown): string {
  const currency = typeof value === "string" ? value.toUpperCase() : "USD";
  if (!/^[A-Z]{3}$/.test(currency)) throw new HttpsError("invalid-argument", "Invalid currency");
  return currency;
}

export const createGroup = onCall(callableOptions, async (request) => {
  const uid = uidOf(request);
  const data = request.data as Input;
  const name = text(data, "name", 60);
  const emoji = typeof data.emoji === "string" ? data.emoji.slice(0, 8) : "👥";
  const currencyCode = cleanCurrency(data.currencyCode);
  const userSnapshot = await db.doc(`users/${uid}`).get();
  const user = userSnapshot.data() ?? {name: "Friend", initials: "OF", color: 0xFF5B4BD8};
  const groupRef = db.collection("groups").doc();
  await db.runTransaction(async (transaction) => {
    const op = operationRef(uid, data);
    if ((await transaction.get(op)).exists) return;
    transaction.create(op, {uid, type: "createGroup", createdAt: FieldValue.serverTimestamp()});
    transaction.create(groupRef, {name, emoji, currencyCode, simplifyDebts: true, createdBy: uid, createdAt: FieldValue.serverTimestamp()});
    transaction.create(groupRef.collection("members").doc(uid), {
      uid, name: user.name, initials: user.initials, color: user.color, role: "admin", joinedAt: FieldValue.serverTimestamp(),
    });
    transaction.create(db.doc(`userGroups/${uid}/groups/${groupRef.id}`), {groupId: groupRef.id, joinedAt: FieldValue.serverTimestamp()});
  });
  await refreshDashboard(uid);
  return {groupId: groupRef.id};
});

export const createInvite = onCall(callableOptions, async (request) => {
  const uid = uidOf(request);
  const data = request.data as Input;
  const groupId = text(data, "groupId", 80);
  await requireMember(groupId, uid);
  const token = randomBytes(24).toString("base64url");
  const tokenHash = createHash("sha256").update(token).digest("hex");
  const inviteRef = db.doc(`groups/${groupId}/invites/${tokenHash}`);
  await db.runTransaction(async (transaction) => {
    const op = operationRef(uid, data);
    if ((await transaction.get(op)).exists) return;
    transaction.create(op, {uid, type: "createInvite", createdAt: FieldValue.serverTimestamp()});
    transaction.create(inviteRef, {
      groupId, createdBy: uid, status: "open", createdAt: FieldValue.serverTimestamp(),
      expiresAt: Timestamp.fromMillis(Date.now() + 7 * 24 * 60 * 60 * 1000),
    });
  });
  return {url: `https://owefolk-20260801.web.app/invite?token=${token}&group=${groupId}`};
});

export const acceptInvite = onCall(callableOptions, async (request) => {
  const uid = uidOf(request);
  const data = request.data as Input;
  const groupId = text(data, "groupId", 80);
  const tokenHash = createHash("sha256").update(text(data, "token", 200)).digest("hex");
  const user = (await db.doc(`users/${uid}`).get()).data() ?? {name: "Friend", initials: "OF", color: 0xFF5B4BD8};
  const membersRef = db.doc(`groups/${groupId}/members/${uid}`);
  await db.runTransaction(async (transaction) => {
    const inviteRef = db.doc(`groups/${groupId}/invites/${tokenHash}`);
    const invite = await transaction.get(inviteRef);
    if (!invite.exists || invite.get("status") !== "open" || invite.get("expiresAt").toMillis() < Date.now()) {
      throw new HttpsError("failed-precondition", "Invite is invalid or expired");
    }
    transaction.set(membersRef, {uid, name: user.name, initials: user.initials, color: user.color, role: "member", joinedAt: FieldValue.serverTimestamp()});
    transaction.set(db.doc(`userGroups/${uid}/groups/${groupId}`), {groupId, joinedAt: FieldValue.serverTimestamp()});
    transaction.update(inviteRef, {status: "accepted", acceptedBy: uid, acceptedAt: FieldValue.serverTimestamp()});
  });
  await refreshGroupDashboards(groupId);
  return {groupId};
});

export const createExpense = onCall(callableOptions, async (request) => {
  const uid = uidOf(request);
  const data = request.data as Input;
  const groupId = text(data, "groupId", 80);
  const member = await requireMember(groupId, uid);
  const group = await db.doc(`groups/${groupId}`).get();
  const totalMinorUnits = integer(data, "totalMinorUnits", 1);
  const participantIds = Array.isArray(data.participantIds) ? data.participantIds.filter((value): value is string => typeof value === "string") : [];
  const knownMembers = new Set((await groupMembers(groupId)).map((item) => item.uid));
  if (participantIds.some((id) => !knownMembers.has(id))) throw new HttpsError("invalid-argument", "Unknown participant");
  let allocations: Allocation[];
  try {
    const mode = text(data, "splitMode", 20);
    if (mode === "equal") allocations = equalSplit(totalMinorUnits, participantIds);
    else if (mode === "exact") allocations = exactSplit(totalMinorUnits, data.exactSharesMinorUnits as Record<string, number>);
    else if (mode === "percent") allocations = percentSplit(totalMinorUnits, data.percentageBasisPoints as Record<string, number>);
    else throw new Error("invalid-split-mode");
  } catch (error) {
    throw new HttpsError("invalid-argument", error instanceof Error ? error.message : "Invalid split");
  }
  if (allocations.some((item) => !knownMembers.has(item.personId))) throw new HttpsError("invalid-argument", "Unknown participant");
  const expenseRef = db.collection(`groups/${groupId}/expenses`).doc();
  await db.runTransaction(async (transaction) => {
    const op = operationRef(uid, data);
    if ((await transaction.get(op)).exists) return;
    transaction.create(op, {uid, type: "createExpense", createdAt: FieldValue.serverTimestamp()});
    transaction.create(expenseRef, {
      title: text(data, "title", 80), totalMinorUnits, currencyCode: group.get("currencyCode"), paidById: uid,
      allocations, splitMode: data.splitMode, revision: 1, deleted: false, createdAt: FieldValue.serverTimestamp(), updatedAt: FieldValue.serverTimestamp(),
    });
    transaction.create(db.collection(`groups/${groupId}/activity`).doc(), {
      kind: "expense", title: `${member.name} added ${text(data, "title", 80)}`, detail: group.get("name"),
      amountMinorUnits: totalMinorUnits, currencyCode: group.get("currencyCode"), timestamp: FieldValue.serverTimestamp(),
    });
  });
  await refreshGroupDashboards(groupId);
  return {expenseId: expenseRef.id};
});

export const reviseExpense = onCall(callableOptions, async (request) => {
  const uid = uidOf(request); const data = request.data as Input;
  const groupId = text(data, "groupId", 80); const expenseId = text(data, "expenseId", 80);
  const member = await requireMember(groupId, uid);
  const expenseRef = db.doc(`groups/${groupId}/expenses/${expenseId}`);
  await db.runTransaction(async (transaction) => {
    const expense = await transaction.get(expenseRef);
    if (!expense.exists) throw new HttpsError("not-found", "Expense not found");
    if (expense.get("paidById") !== uid && member.role !== "admin") throw new HttpsError("permission-denied", "Only its creator or an admin can edit this expense");
    transaction.create(expenseRef.collection("revisions").doc(String(expense.get("revision"))), {...expense.data()!, archivedAt: FieldValue.serverTimestamp()});
    transaction.update(expenseRef, {title: text(data, "title", 80), revision: FieldValue.increment(1), updatedAt: FieldValue.serverTimestamp()});
  });
  await refreshGroupDashboards(groupId); return {expenseId};
});

export const deleteExpense = onCall(callableOptions, async (request) => {
  const uid = uidOf(request); const data = request.data as Input;
  const groupId = text(data, "groupId", 80); const expenseId = text(data, "expenseId", 80);
  const member = await requireMember(groupId, uid); const ref = db.doc(`groups/${groupId}/expenses/${expenseId}`);
  await db.runTransaction(async (transaction) => {
    const snapshot = await transaction.get(ref);
    if (!snapshot.exists) throw new HttpsError("not-found", "Expense not found");
    if (snapshot.get("paidById") !== uid && member.role !== "admin") throw new HttpsError("permission-denied", "Not allowed");
    transaction.update(ref, {deleted: true, deletedBy: uid, updatedAt: FieldValue.serverTimestamp()});
  });
  await refreshGroupDashboards(groupId); return {expenseId};
});

export const startSettlement = onCall(callableOptions, async (request) => {
  const uid = uidOf(request); const data = request.data as Input;
  const groupId = text(data, "groupId", 80); await requireMember(groupId, uid);
  const recipientId = text(data, "recipientId", 128); await requireMember(groupId, recipientId);
  if (recipientId === uid) throw new HttpsError("invalid-argument", "Cannot pay yourself");
  const provider = text(data, "provider", 30).toLowerCase();
  if (!new Set(["cash_app", "venmo", "paypal", "zelle", "other", "cash"]).has(provider)) throw new HttpsError("invalid-argument", "Unsupported provider");
  const group = await db.doc(`groups/${groupId}`).get(); const ref = db.collection(`groups/${groupId}/settlements`).doc();
  await db.runTransaction(async (transaction) => {
    const op = operationRef(uid, data); if ((await transaction.get(op)).exists) return;
    transaction.create(op, {uid, type: "startSettlement", createdAt: FieldValue.serverTimestamp()});
    transaction.create(ref, {settlementId: ref.id, payerId: uid, recipientId, amountMinorUnits: integer(data, "amountMinorUnits", 1),
      currencyCode: group.get("currencyCode"), provider, status: "sent", createdAt: FieldValue.serverTimestamp(), updatedAt: FieldValue.serverTimestamp()});
  });
  await notifyUser(recipientId, "Payment confirmation", "A friend marked a payment sent. Confirm it in Owefolk.", groupId);
  await refreshGroupDashboards(groupId); return {settlementId: ref.id};
});

async function transitionSettlement(request: {auth?: {uid: string}; data: unknown}, next: "confirmed" | "rejected" | "cancelled") {
  const uid = uidOf(request); const data = request.data as Input; const settlementId = text(data, "settlementId", 80);
  const matches = await db.collectionGroup("settlements").where("settlementId", "==", settlementId).limit(1).get();
  if (matches.empty) throw new HttpsError("not-found", "Settlement not found");
  const ref = matches.docs[0].ref; const snapshot = matches.docs[0]; const groupId = ref.parent.parent?.id;
  if (!groupId) throw new HttpsError("internal", "Settlement group missing");
  if (next === "cancelled" ? snapshot.get("payerId") !== uid : snapshot.get("recipientId") !== uid) throw new HttpsError("permission-denied", "Not allowed");
  if (snapshot.get("status") !== "sent") throw new HttpsError("failed-precondition", "Settlement is no longer pending");
  await ref.update({status: next, updatedAt: FieldValue.serverTimestamp(), [`${next}At`]: FieldValue.serverTimestamp(), [`${next}By`]: uid});
  await refreshGroupDashboards(groupId); return {settlementId, status: next};
}

export const confirmSettlement = onCall(callableOptions, (request) => transitionSettlement(request, "confirmed"));
export const rejectSettlement = onCall(callableOptions, (request) => transitionSettlement(request, "rejected"));
export const cancelSettlement = onCall(callableOptions, (request) => transitionSettlement(request, "cancelled"));

export const sendReminder = onCall(callableOptions, async (request) => {
  const uid = uidOf(request); const data = request.data as Input; const groupId = text(data, "groupId", 80);
  const member = await requireMember(groupId, uid); const cooldownRef = db.doc(`groups/${groupId}/reminders/${uid}`);
  await db.runTransaction(async (transaction) => {
    const cooldown = await transaction.get(cooldownRef);
    if (cooldown.exists && Date.now() - cooldown.get("lastSentAt").toMillis() < 24 * 60 * 60 * 1000) {
      throw new HttpsError("resource-exhausted", "A reminder was already sent in the last 24 hours");
    }
    transaction.set(cooldownRef, {lastSentAt: FieldValue.serverTimestamp()});
    transaction.create(db.collection(`groups/${groupId}/activity`).doc(), {kind: "reminder", title: `${member.name} sent a friendly reminder`, detail: "No amounts were shared", timestamp: FieldValue.serverTimestamp()});
  });
  for (const memberItem of await groupMembers(groupId)) if (memberItem.uid !== uid) {
    await notifyUser(memberItem.uid, "A friendly Owefolk reminder", "Open the group to check your balance.", groupId);
  }
  await refreshGroupDashboards(groupId); return {sent: true};
});

export const deleteAccount = onCall(callableOptions, async (request) => {
  const uid = uidOf(request); const memberships = await db.collectionGroup("members").where("uid", "==", uid).get();
  const batch = db.batch(); const touchedGroups: string[] = [];
  for (const membership of memberships.docs) {
    batch.update(membership.ref, {name: "Deleted member", initials: "—", color: 0xFF928D9A, deleted: true});
    const groupId = membership.ref.parent.parent?.id; if (groupId) touchedGroups.push(groupId);
  }
  const tokens = await db.collection(`users/${uid}/tokens`).get(); tokens.docs.forEach((doc) => batch.delete(doc.ref));
  batch.delete(db.doc(`users/${uid}`)); batch.delete(db.doc(`userDashboards/${uid}`));
  const links = await db.collection(`userGroups/${uid}/groups`).get(); links.docs.forEach((doc) => batch.delete(doc.ref));
  await batch.commit(); await getAuth().deleteUser(uid);
  await Promise.all(touchedGroups.map(refreshGroupDashboards)); return {deleted: true};
});

async function notifyUser(uid: string, title: string, body: string, groupId: string) {
  const tokens = await db.collection(`users/${uid}/tokens`).get();
  if (tokens.empty) return;
  const values = tokens.docs.map((doc) => doc.id);
  await getMessaging().sendEachForMulticast({tokens: values, notification: {title, body}, data: {link: `https://owefolk-20260801.web.app/group/${groupId}`}});
}

async function refreshGroupDashboards(groupId: string) {
  const members = await groupMembers(groupId);
  await Promise.all(members.filter((member) => !member.deleted).map((member) => refreshDashboard(member.uid)));
}

async function refreshDashboard(uid: string) {
  const user = (await db.doc(`users/${uid}`).get()).data() ?? {name: "Friend", initials: "OF", color: 0xFF5B4BD8};
  const groupLinks = await db.collection(`userGroups/${uid}/groups`).get();
  const groups: Record<string, unknown>[] = []; const activities: Record<string, unknown>[] = []; const settlements: Record<string, unknown>[] = [];
  for (const link of groupLinks.docs) {
    const groupId = link.id; const group = await db.doc(`groups/${groupId}`).get(); if (!group.exists) continue;
    const members = await groupMembers(groupId); const net = await netForUser(groupId, uid);
    groups.push({id: groupId, name: group.get("name"), emoji: group.get("emoji"), currencyCode: group.get("currencyCode"),
      simplifyDebts: group.get("simplifyDebts"), netMinorUnits: net, members: members.map(publicMember)});
    const recent = await db.collection(`groups/${groupId}/activity`).orderBy("timestamp", "desc").limit(8).get();
    recent.docs.forEach((doc) => activities.push({id: doc.id, ...doc.data(), timestamp: doc.get("timestamp")?.toMillis?.() ?? Date.now()}));
    const pending = await db.collection(`groups/${groupId}/settlements`).where("status", "==", "sent").get();
    for (const doc of pending.docs) {
      if (doc.get("payerId") !== uid && doc.get("recipientId") !== uid) continue;
      const payer = members.find((item) => item.uid === doc.get("payerId")); const recipient = members.find((item) => item.uid === doc.get("recipientId"));
      if (payer && recipient) settlements.push({id: doc.id, ...doc.data(), payer: publicMember(payer), recipient: publicMember(recipient), createdAt: doc.get("createdAt")?.toMillis?.() ?? Date.now()});
    }
  }
  activities.sort((a, b) => Number(b.timestamp) - Number(a.timestamp));
  await db.doc(`userDashboards/${uid}`).set({user: {id: uid, ...user}, groups, activities: activities.slice(0, 30), settlements, updatedAt: FieldValue.serverTimestamp()});
}

async function netForUser(groupId: string, uid: string): Promise<number> {
  let net = 0; const expenses = await db.collection(`groups/${groupId}/expenses`).where("deleted", "==", false).get();
  for (const doc of expenses.docs) {
    if (doc.get("paidById") === uid) net += doc.get("totalMinorUnits");
    const allocation = (doc.get("allocations") as Allocation[]).find((item) => item.personId === uid); if (allocation) net -= allocation.minorUnits;
  }
  const confirmed = await db.collection(`groups/${groupId}/settlements`).where("status", "==", "confirmed").get();
  for (const doc of confirmed.docs) {
    const amount = doc.get("amountMinorUnits") as number; if (doc.get("payerId") === uid) net += amount; if (doc.get("recipientId") === uid) net -= amount;
  }
  return net;
}

function publicMember(member: Member) {
  return {id: member.uid, name: member.name, initials: member.initials, color: member.color};
}

export const sendDigests = onSchedule({region: REGION, schedule: "every day 15:00", timeZone: "UTC"}, async () => {
  const users = await db.collection("users").where("digestEnabled", "==", true).get();
  await Promise.all(users.docs.map((user) => notifyUser(user.id, "Your Owefolk digest", "Open Owefolk for a calm summary of unsettled balances.", "home")));
});

export const health = onCall({region: REGION}, async () => ({ok: true, requestId: randomUUID(), time: Date.now()}));
