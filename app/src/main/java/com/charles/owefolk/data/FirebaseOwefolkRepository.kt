package com.charles.owefolk.data

import com.charles.owefolk.domain.*
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.charles.owefolk.observability.Telemetry
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.security.MessageDigest
import java.time.Instant
import java.util.Date
import java.util.UUID

class FirebaseOwefolkRepository : OwefolkRepository {
    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseFirestore.getInstance() }

    override val dashboard: Flow<Dashboard> = callbackFlow {
        val registrations = mutableListOf<ListenerRegistration>()
        var groupIds = emptyList<String>()

        fun clearListeners() {
            registrations.forEach(ListenerRegistration::remove)
            registrations.clear()
        }

        fun refresh(uid: String) {
            launch { runCatching { loadDashboard(uid, groupIds) }.onSuccess(::trySend).onFailure(::close) }
        }

        fun observe(uid: String) {
            clearListeners()
            registrations += db.collection("users").document(uid).addSnapshotListener { _, error ->
                if (error != null) Telemetry.record(error, "user_listener") else refresh(uid)
            }
            registrations += db.collection("userGroups").document(uid).collection("groups").addSnapshotListener { links, error ->
                if (error != null) {
                    Telemetry.record(error, "group_links_listener")
                    return@addSnapshotListener
                }
                groupIds = links?.documents?.map(DocumentSnapshot::getId).orEmpty()
                val existing = registrations.take(2).toList()
                registrations.drop(2).forEach(ListenerRegistration::remove)
                registrations.clear()
                registrations.addAll(existing)
                groupIds.forEach { groupId ->
                    val group = db.collection("groups").document(groupId)
                    registrations += group.addSnapshotListener { _, e -> if (e != null) Telemetry.record(e, "group_listener") else refresh(uid) }
                    listOf("members", "expenses", "settlements", "activity").forEach { collection ->
                        registrations += group.collection(collection).addSnapshotListener { _, e -> if (e != null) Telemetry.record(e, "group_collection_listener") else refresh(uid) }
                    }
                }
                refresh(uid)
            }
        }

        val authListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            val uid = firebaseAuth.currentUser?.uid
            if (uid == null) {
                clearListeners()
                trySend(emptyDashboard())
            } else observe(uid)
        }
        auth.addAuthStateListener(authListener)
        awaitClose { clearListeners(); auth.removeAuthStateListener(authListener) }
    }

    override suspend fun createGroup(name: String, emoji: String, currencyCode: String): String {
        val uid = uid()
        val user = db.collection("users").document(uid).get().await()
        val group = db.collection("groups").document()
        val batch = db.batch()
        batch.set(group, mapOf(
            "name" to name.trim(), "emoji" to emoji, "currencyCode" to currencyCode.uppercase(),
            "simplifyDebts" to true, "createdBy" to uid, "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
        ))
        batch.set(group.collection("members").document(uid), mapOf(
            "uid" to uid, "name" to (user.getString("name") ?: "Friend"), "initials" to (user.getString("initials") ?: "OF"),
            "color" to (user.getLong("color") ?: 0xFF5B4BD8), "role" to "admin", "joinedAt" to FieldValue.serverTimestamp(),
        ))
        batch.set(db.collection("userGroups").document(uid).collection("groups").document(group.id),
            mapOf("groupId" to group.id, "joinedAt" to FieldValue.serverTimestamp()))
        batch.commit().await()
        return group.id
    }

    override suspend fun createInvite(groupId: String): String {
        val token = UUID.randomUUID().toString() + UUID.randomUUID().toString()
        val hash = MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
        db.collection("groups").document(groupId).collection("invites").document(hash).set(mapOf(
            "groupId" to groupId, "createdBy" to uid(), "status" to "open", "createdAt" to FieldValue.serverTimestamp(),
            "expiresAt" to Timestamp(Date(System.currentTimeMillis() + 7 * 24 * 60 * 60 * 1000)),
        )).await()
        return "https://owefolk-20260801.web.app/invite?token=$token&group=$groupId"
    }

    override suspend fun addExpense(expense: NewExpense) {
        val uid = uid()
        val group = db.collection("groups").document(expense.groupId)
        val groupData = group.get().await()
        val member = group.collection("members").document(uid).get().await()
        val shares = when (expense.splitMode) {
            SplitMode.EQUAL -> MoneyMath.splitEqual(expense.totalMinorUnits, expense.participantIds)
            SplitMode.EXACT -> expense.exactSharesMinorUnits.also { require(MoneyMath.validateExact(expense.totalMinorUnits, it)) }
            SplitMode.PERCENT -> MoneyMath.splitPercent(expense.totalMinorUnits, expense.percentageBasisPoints)
        }
        val batch = db.batch()
        val expenseRef = group.collection("expenses").document()
        batch.set(expenseRef, mapOf(
            "title" to expense.title.trim(), "totalMinorUnits" to expense.totalMinorUnits,
            "currencyCode" to (groupData.getString("currencyCode") ?: "USD"), "paidById" to uid,
            "allocations" to shares.map { mapOf("personId" to it.key, "minorUnits" to it.value) },
            "splitMode" to expense.splitMode.name.lowercase(), "deleted" to false, "createdAt" to FieldValue.serverTimestamp(),
        ))
        batch.set(group.collection("activity").document(), mapOf(
            "kind" to "expense", "title" to "${member.getString("name") ?: "A friend"} added ${expense.title.trim()}",
            "detail" to (groupData.getString("name") ?: "Group"), "amountMinorUnits" to expense.totalMinorUnits,
            "currencyCode" to (groupData.getString("currencyCode") ?: "USD"), "timestamp" to FieldValue.serverTimestamp(),
        ))
        batch.update(group, "updatedAt", FieldValue.serverTimestamp())
        batch.commit().await()
    }

    override suspend fun startSettlement(groupId: String, recipientId: String, amountMinorUnits: Long, provider: PaymentProvider) {
        val group = db.collection("groups").document(groupId)
        val currency = group.get().await().getString("currencyCode") ?: "USD"
        val ref = group.collection("settlements").document()
        val batch = db.batch()
        batch.set(ref, mapOf(
            "payerId" to uid(), "recipientId" to recipientId, "amountMinorUnits" to amountMinorUnits,
            "currencyCode" to currency, "provider" to provider.name.lowercase(), "status" to "sent",
            "createdAt" to FieldValue.serverTimestamp(), "updatedAt" to FieldValue.serverTimestamp(),
        ))
        batch.update(group, "updatedAt", FieldValue.serverTimestamp())
        batch.commit().await()
    }

    override suspend fun confirmSettlement(settlementId: String) = transition(settlementId, "confirmed")
    override suspend fun rejectSettlement(settlementId: String) = transition(settlementId, "rejected")

    private suspend fun transition(compoundId: String, status: String) {
        val (groupId, id) = compoundId.split('|', limit = 2).also { require(it.size == 2) { "Invalid settlement" } }
        val group = db.collection("groups").document(groupId)
        val batch = db.batch()
        batch.update(group.collection("settlements").document(id), mapOf("status" to status, "updatedAt" to FieldValue.serverTimestamp()))
        batch.update(group, "updatedAt", FieldValue.serverTimestamp())
        batch.commit().await()
    }

    override suspend fun sendReminder(groupId: String) {
        val uid = uid()
        val group = db.collection("groups").document(groupId)
        val member = group.collection("members").document(uid).get().await()
        group.collection("activity").add(mapOf(
            "kind" to "reminder", "title" to "${member.getString("name") ?: "A friend"} sent a friendly reminder",
            "detail" to "No amounts were shared", "timestamp" to FieldValue.serverTimestamp(),
        )).await()
        group.update("updatedAt", FieldValue.serverTimestamp()).await()
    }

    override suspend fun updatePreferredProvider(provider: PaymentProvider) {
        db.collection("users").document(uid()).update("preferredProvider", provider.name).await()
    }

    override suspend fun deleteAccount() {
        val user = requireNotNull(auth.currentUser) { "Sign in required" }
        val links = db.collection("userGroups").document(user.uid).collection("groups").get().await()
        val batch = db.batch()
        links.documents.forEach { link ->
            batch.update(db.collection("groups").document(link.id).collection("members").document(user.uid),
                mapOf("name" to "Deleted member", "initials" to "—", "color" to 0xFF928D9A, "deleted" to true))
            batch.delete(link.reference)
        }
        batch.delete(db.collection("users").document(user.uid))
        batch.commit().await()
        user.delete().await()
    }

    private suspend fun loadDashboard(uid: String, groupIds: List<String>): Dashboard {
        val userDoc = db.collection("users").document(uid).get().await()
        val user = userDoc.toPerson(uid)
        val groups = mutableListOf<Group>()
        val allActivities = mutableListOf<ActivityItem>()
        val allSettlements = mutableListOf<Settlement>()
        for (groupId in groupIds) {
            val groupRef = db.collection("groups").document(groupId)
            val groupDoc = groupRef.get().await()
            if (!groupDoc.exists()) continue
            val members = groupRef.collection("members").get().await().documents.map { it.toPerson(it.id) }
            var net = 0L
            groupRef.collection("expenses").whereEqualTo("deleted", false).get().await().documents.forEach { expense ->
                if (expense.getString("paidById") == uid) net += expense.getLong("totalMinorUnits") ?: 0
                val allocations = expense.get("allocations") as? List<Map<String, Any>> ?: emptyList()
                net -= (allocations.firstOrNull { it["personId"] == uid }?.get("minorUnits") as? Number)?.toLong() ?: 0
            }
            val settlementDocs = groupRef.collection("settlements").get().await().documents
            settlementDocs.filter { it.getString("status") == "confirmed" }.forEach { settlement ->
                val amount = settlement.getLong("amountMinorUnits") ?: 0
                if (settlement.getString("payerId") == uid) net += amount
                if (settlement.getString("recipientId") == uid) net -= amount
            }
            val group = Group(groupId, groupDoc.getString("name") ?: "Group", groupDoc.getString("emoji") ?: "👥",
                groupDoc.getString("currencyCode") ?: "USD", members, net, groupDoc.getBoolean("simplifyDebts") ?: true)
            groups += group
            settlementDocs.filter { it.getString("payerId") == uid || it.getString("recipientId") == uid }.forEach { doc ->
                val payer = members.firstOrNull { it.id == doc.getString("payerId") } ?: return@forEach
                val recipient = members.firstOrNull { it.id == doc.getString("recipientId") } ?: return@forEach
                allSettlements += Settlement("$groupId|${doc.id}", payer, recipient,
                    Money(doc.getLong("amountMinorUnits") ?: 0, doc.getString("currencyCode") ?: group.currencyCode),
                    PaymentProvider.valueOf((doc.getString("provider") ?: "other").uppercase()),
                    SettlementStatus.valueOf((doc.getString("status") ?: "sent").uppercase()), doc.instant("createdAt"))
            }
            groupRef.collection("activity").orderBy("timestamp", com.google.firebase.firestore.Query.Direction.DESCENDING).limit(30).get().await().documents.forEach { doc ->
                allActivities += ActivityItem(doc.id, ActivityKind.valueOf((doc.getString("kind") ?: "member").uppercase()),
                    doc.getString("title") ?: "Group update", doc.getString("detail") ?: group.name, doc.instant("timestamp"),
                    doc.getLong("amountMinorUnits")?.let { Money(it, doc.getString("currencyCode") ?: group.currencyCode) })
            }
        }
        return Dashboard(user, groups.sortedBy { it.name }, allActivities.sortedByDescending { it.timestamp }.take(30), allSettlements)
    }

    private fun DocumentSnapshot.toPerson(fallbackId: String) = Person(
        getString("uid") ?: getString("id") ?: fallbackId,
        getString("name")?.takeUnless(String::isBlank)
            ?: auth.currentUser?.displayName?.takeUnless(String::isBlank)
            ?: "Friend",
        getString("initials") ?: "OF",
        getLong("color") ?: 0xFF5B4BD8,
        runCatching { PaymentProvider.valueOf(getString("preferredProvider") ?: "VENMO") }.getOrDefault(PaymentProvider.VENMO),
        getString("paymentHandle")?.takeUnless(String::isBlank),
    )

    private fun DocumentSnapshot.instant(field: String): Instant = getTimestamp(field)?.toDate()?.toInstant() ?: Instant.now()
    private fun uid(): String = requireNotNull(auth.currentUser?.uid) { "Sign in required" }
    private fun emptyDashboard() = Dashboard(Person("signed-out", "Friend", "OF", 0xFF5B4BD8), emptyList(), emptyList(), emptyList())
}
