package com.charles.owefolk.data

import com.charles.owefolk.domain.*
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.charles.owefolk.observability.Telemetry
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.tasks.await
import java.time.Instant
import java.util.Date
import java.util.UUID

class FirebaseOwefolkRepository : OwefolkRepository {
    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy {
        FirebaseFirestore.getInstance().also { firestore ->
            val settings = com.google.firebase.firestore.FirebaseFirestoreSettings.Builder()
                .setPersistenceEnabled(true)
                .setCacheSizeBytes(com.google.firebase.firestore.FirebaseFirestoreSettings.CACHE_SIZE_UNLIMITED)
                .build()
            firestore.firestoreSettings = settings
        }
    }

    override val dashboard: Flow<Dashboard> = callbackFlow {
        val registrations = mutableListOf<ListenerRegistration>()
        var groupIds = emptyList<String>()
        var version = 0

        fun clearListeners() {
            registrations.forEach(ListenerRegistration::remove)
            registrations.clear()
        }

        fun refresh(uid: String) {
            val expectedVersion = version
            launch {
                runCatching { loadDashboard(uid, groupIds) }
                    .onSuccess { if (version == expectedVersion) trySend(it) }
                    .onFailure { if (version == expectedVersion) Telemetry.record(it, "dashboard_refresh") }
            }
        }

        fun observe(uid: String) {
            clearListeners()
            version++
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
            "color" to (user.getLong("color") ?: 0xFF5B4BD8),
            "preferredProvider" to (user.getString("preferredProvider") ?: PaymentProvider.VENMO.name),
            "paymentHandle" to (user.getString("paymentHandle") ?: ""),
            "role" to "admin", "joinedAt" to FieldValue.serverTimestamp(),
        ))
        batch.set(db.collection("userGroups").document(uid).collection("groups").document(group.id),
            mapOf("groupId" to group.id, "joinedAt" to FieldValue.serverTimestamp()))
        batch.commit().await()
        return group.id
    }

    override suspend fun createInvite(groupId: String): String {
        val token = UUID.randomUUID().toString() + UUID.randomUUID().toString()
        db.collection("groups").document(groupId).collection("invites").document(token).set(mapOf(
            "groupId" to groupId, "createdBy" to uid(), "status" to "open", "createdAt" to FieldValue.serverTimestamp(),
            "expiresAt" to Timestamp(Date(System.currentTimeMillis() + 7 * 24 * 60 * 60 * 1000)),
        )).await()
        return "https://chartmann1590.github.io/owefolk/invite.html?token=$token&group=$groupId"
    }

    override suspend fun acceptInvite(groupId: String, token: String) {
        require(token.length in 64..96 && token.all { it.isLetterOrDigit() || it == '-' }) { "Invalid invite" }
        val uid = uid()
        val group = db.collection("groups").document(groupId)
        val inviteRef = group.collection("invites").document(token)
        val invite = inviteRef.get().await()
        require(invite.exists()) { "Invite is no longer available" }
        if (invite.getString("status") == "accepted" && invite.getString("acceptedBy") == uid) return
        require(invite.getString("status") == "open") { "Invite is no longer available" }
        require(invite.getTimestamp("expiresAt")?.toDate()?.after(Date()) == true) { "Invite has expired" }
        val user = db.collection("users").document(uid).get().await()
        val batch = db.batch()
        batch.update(inviteRef, mapOf(
            "status" to "accepted", "acceptedBy" to uid, "acceptedAt" to FieldValue.serverTimestamp(),
        ))
        batch.set(group.collection("members").document(uid), mapOf(
            "uid" to uid, "name" to (user.getString("name") ?: "Friend"),
            "initials" to (user.getString("initials") ?: "OF"), "color" to (user.getLong("color") ?: 0xFF5B4BD8),
            "preferredProvider" to (user.getString("preferredProvider") ?: PaymentProvider.VENMO.name),
            "paymentHandle" to (user.getString("paymentHandle") ?: ""),
            "role" to "member", "inviteId" to token, "joinedAt" to FieldValue.serverTimestamp(),
        ))
        batch.set(db.collection("userGroups").document(uid).collection("groups").document(groupId),
            mapOf("groupId" to groupId, "joinedAt" to FieldValue.serverTimestamp()))
        batch.commit().await()
        group.collection("activity").add(mapOf(
            "kind" to "member", "title" to "${user.getString("name") ?: "A friend"} joined the group",
            "detail" to "Invite accepted", "timestamp" to FieldValue.serverTimestamp(),
        )).await()
        group.update("updatedAt", FieldValue.serverTimestamp()).await()
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

    override suspend fun updateRepaymentMode(groupId: String, simplifyDebts: Boolean) {
        db.collection("groups").document(groupId).update(mapOf(
            "simplifyDebts" to simplifyDebts,
            "updatedAt" to FieldValue.serverTimestamp(),
        )).await()
    }

    override suspend fun updatePaymentPreference(provider: PaymentProvider, paymentHandle: String?) {
        val handle = paymentHandle?.trim()?.takeUnless(String::isBlank)
        if (provider != PaymentProvider.CASH) {
            require(handle != null) { "Add the handle or link people should use to repay you" }
            require(handle.length <= 160) { "Payment details are too long" }
            if (provider == PaymentProvider.OTHER) require(handle.startsWith("https://")) { "Use a secure https:// payment link" }
        }
        val uid = uid()
        val links = db.collection("userGroups").document(uid).collection("groups").get().await()
        val userRef = db.collection("users").document(uid)
        val existing = runCatching { userRef.get().await() }.getOrNull()
        val base = mutableMapOf<String, Any>("preferredProvider" to provider.name)
        base["paymentHandle"] = handle ?: FieldValue.delete()
        if (existing?.exists() != true) {
            val fallback = auth.currentUser?.displayName ?: "Friend"
            base["name"] = fallback
            base["initials"] = fallback.split(' ').filter(String::isNotBlank).take(2)
                .joinToString("") { it.first().uppercase() }.ifBlank { "OF" }
            base["color"] = 0xFF5B4BD8
        }
        val batch = db.batch()
        batch.set(userRef, base, SetOptions.merge())
        val memberFields = mapOf<String, Any>("preferredProvider" to provider.name, "paymentHandle" to (handle ?: FieldValue.delete()))
        links.documents.forEach { link ->
            batch.update(db.collection("groups").document(link.id).collection("members").document(uid), memberFields)
        }
        batch.commit().await()
    }

    override suspend fun saveProfileName(name: String, color: Long) {
        val uid = uid()
        val nameClean = name.trim()
        require(nameClean.isNotEmpty()) { "Add a name friends will recognize" }
        val initials = nameClean.split(' ').filter(String::isNotBlank).take(2)
            .joinToString("") { it.first().uppercase() }.ifBlank { "OF" }
        val userRef = db.collection("users").document(uid)
        val existing = runCatching { userRef.get().await() }.getOrNull()
        userRef.set(mapOf<String, Any>(
            "name" to nameClean, "initials" to initials, "color" to color,
            "preferredProvider" to (existing?.getString("preferredProvider") ?: PaymentProvider.VENMO.name),
        ), SetOptions.merge()).await()
    }

    override suspend fun completeOnboarding() {
        val uid = uid()
        val userRef = db.collection("users").document(uid)
        val existing = runCatching { userRef.get().await() }.getOrNull()
        val fields = mutableMapOf<String, Any>(
            "preferredProvider" to (existing?.getString("preferredProvider") ?: PaymentProvider.VENMO.name),
            "onboarded" to true,
        )
        if (existing?.exists() != true) {
            val fallback = auth.currentUser?.displayName ?: "Friend"
            fields["name"] = fallback
            fields["initials"] = fallback.split(' ').filter(String::isNotBlank).take(2)
                .joinToString("") { it.first().uppercase() }.ifBlank { "OF" }
            fields["color"] = 0xFF5B4BD8
        }
        userRef.set(fields, SetOptions.merge()).await()
    }

    override suspend fun deleteAccount() {
        val user = requireNotNull(auth.currentUser) { "Sign in required" }
        val links = db.collection("userGroups").document(user.uid).collection("groups").get().await()
        val batch = db.batch()
        links.documents.forEach { link ->
            batch.update(db.collection("groups").document(link.id).collection("members").document(user.uid),
                mapOf("name" to "Deleted member", "initials" to "—", "color" to 0xFF928D9A,
                    "preferredProvider" to FieldValue.delete(), "paymentHandle" to FieldValue.delete(), "deleted" to true))
            batch.delete(link.reference)
        }
        batch.delete(db.collection("users").document(user.uid))
        batch.commit().await()
        user.delete().await()
    }

    private suspend fun loadDashboard(uid: String, groupIds: List<String>): Dashboard {
        val userDoc = db.collection("users").document(uid).get().await()
        val user = userDoc.toPerson(uid)
        val needsOnboarding = userDoc.getBoolean("onboarded") != true
        val groupResults = supervisorScope {
            groupIds.map { groupId ->
                async { runCatching { loadGroup(uid, groupId, user) }.getOrNull() }
            }.awaitAll()
        }
        val groups = groupResults.mapNotNull { it?.first }.toMutableList()
        val allActivities = groupResults.flatMap { it?.second.orEmpty() }.toMutableList()
        val allSettlements = groupResults.flatMap { it?.third.orEmpty() }.toMutableList()
        return Dashboard(user, groups.sortedBy { it.name }, allActivities.sortedByDescending { it.timestamp }.take(30), allSettlements, needsOnboarding)
    }

    private suspend fun loadGroup(uid: String, groupId: String, currentUser: Person): Triple<Group?, List<ActivityItem>, List<Settlement>> = coroutineScope {
        val groupRef = db.collection("groups").document(groupId)
        val groupDocDeferred = async { groupRef.get().await() }
        val membersDeferred = async { groupRef.collection("members").get().await() }
        val expensesDeferred = async { groupRef.collection("expenses").whereEqualTo("deleted", false).orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING).limit(50).get().await() }
        val settlementsDeferred = async { groupRef.collection("settlements").orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING).limit(50).get().await() }
        val activityDeferred = async { groupRef.collection("activity").orderBy("timestamp", com.google.firebase.firestore.Query.Direction.DESCENDING).limit(30).get().await() }

        val groupDoc = groupDocDeferred.await()
        if (!groupDoc.exists()) return@coroutineScope Triple(null, emptyList(), emptyList())

        val members = membersDeferred.await().documents.map { document ->
            document.toPerson(document.id).let {
                if (it.id == uid) it.copy(preferredProvider = currentUser.preferredProvider, paymentHandle = currentUser.paymentHandle) else it
            }
        }
        val expenseDocs = expensesDeferred.await().documents
        val charges = expenseDocs.map { expense ->
            val allocations = expense.get("allocations") as? List<Map<String, Any>> ?: emptyList()
            LedgerCharge(
                expense.getString("paidById") ?: "",
                allocations.mapNotNull { allocation ->
                    val personId = allocation["personId"] as? String ?: return@mapNotNull null
                    val amount = (allocation["minorUnits"] as? Number)?.toLong() ?: return@mapNotNull null
                    personId to amount
                }.toMap(),
            )
        }
        val settlementDocs = settlementsDeferred.await().documents
        val confirmedPayments = settlementDocs.filter { it.getString("status") == "confirmed" }.mapNotNull { settlement ->
            val payerId = settlement.getString("payerId") ?: return@mapNotNull null
            val recipientId = settlement.getString("recipientId") ?: return@mapNotNull null
            LedgerPayment(payerId, recipientId, settlement.getLong("amountMinorUnits") ?: 0L)
        }
        val directTransfers = LedgerMath.directTransfers(charges, confirmedPayments)
        val netByPerson = LedgerMath.netByPerson(members.map(Person::id), directTransfers)
        val simplified = groupDoc.getBoolean("simplifyDebts") ?: true
        val transfers = if (simplified) DebtSimplifier.simplify(netByPerson) else directTransfers
        val currency = groupDoc.getString("currencyCode") ?: "USD"
        val peopleById = members.associateBy(Person::id)
        val repayments = transfers.mapNotNull { transfer ->
            val from = peopleById[transfer.fromId]
            if (from == null) {
                Telemetry.record(IllegalStateException("Missing member ${transfer.fromId} in group $groupId"), "missing_member")
                return@mapNotNull null
            }
            val to = peopleById[transfer.toId]
            if (to == null) {
                Telemetry.record(IllegalStateException("Missing member ${transfer.toId} in group $groupId"), "missing_member")
                return@mapNotNull null
            }
            Repayment(from, to, Money(transfer.minorUnits, currency))
        }
        val group = Group(groupId, groupDoc.getString("name") ?: "Group", groupDoc.getString("emoji") ?: "👥",
            currency, members, netByPerson[uid] ?: 0L, simplified, repayments)
        val groupSettlements = mutableListOf<Settlement>()
        settlementDocs.filter { it.getString("payerId") == uid || it.getString("recipientId") == uid }.forEach { doc ->
            val payer = members.firstOrNull { it.id == doc.getString("payerId") } ?: return@forEach
            val recipient = members.firstOrNull { it.id == doc.getString("recipientId") } ?: return@forEach
            groupSettlements += Settlement("$groupId|${doc.id}", payer, recipient,
                Money(doc.getLong("amountMinorUnits") ?: 0, doc.getString("currencyCode") ?: group.currencyCode),
                PaymentProvider.valueOf((doc.getString("provider") ?: "other").uppercase()),
                SettlementStatus.valueOf((doc.getString("status") ?: "sent").uppercase()), doc.instant("createdAt"))
        }
        val groupActivities = mutableListOf<ActivityItem>()
        activityDeferred.await().documents.forEach { doc ->
            groupActivities += ActivityItem(doc.id, ActivityKind.valueOf((doc.getString("kind") ?: "member").uppercase()),
                doc.getString("title") ?: "Group update", doc.getString("detail") ?: group.name, doc.instant("timestamp"),
                doc.getLong("amountMinorUnits")?.let { Money(it, doc.getString("currencyCode") ?: group.currencyCode) })
        }
        Triple(group, groupActivities, groupSettlements)
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
        getBoolean("premiumActive") == true,
    )

    private fun DocumentSnapshot.instant(field: String): Instant = getTimestamp(field)?.toDate()?.toInstant()
        ?: Instant.ofEpochMilli(getLong(field) ?: System.currentTimeMillis())
    private fun uid(): String = requireNotNull(auth.currentUser?.uid) { "Sign in required" }
    private fun emptyDashboard() = Dashboard(Person("signed-out", "Friend", "OF", 0xFF5B4BD8), emptyList(), emptyList(), emptyList())
}
