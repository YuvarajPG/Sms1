package com.yuvaraj.smsrelay.data

/** Pure rules shared by the UI flow and unit tests. Firestore rules/transactions enforce these remotely. */
object PairingPolicy {
    fun canClaim(receiverUid: String?, requestingUid: String, ownerUid: String): Boolean =
        requestingUid.isNotBlank() &&
            requestingUid != ownerUid &&
            (receiverUid == null || receiverUid == requestingUid)

    fun canDisconnect(ownerUid: String, requestingUid: String, receiverUid: String?): Boolean =
        ownerUid.isNotBlank() && ownerUid == requestingUid && receiverUid != null
}
