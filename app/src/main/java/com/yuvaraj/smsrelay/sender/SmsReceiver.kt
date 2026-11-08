package com.yuvaraj.smsrelay.sender

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.yuvaraj.smsrelay.data.RelayCrypto

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (parts.isEmpty()) return

        val sender = parts.firstOrNull()?.originatingAddress.orEmpty()
        val body = parts.joinToString(separator = "") { it.messageBody.orEmpty() }
        if (body.isBlank()) return

        val prefs = context.getSharedPreferences("sms_relay", Context.MODE_PRIVATE)
        val code = prefs.getString("sender_pairing_code", null) ?: return
        val pending = goAsync()
        val auth = FirebaseAuth.getInstance()

        fun uploadFor(uid: String) {
            val db = FirebaseFirestore.getInstance()
            val room = db.collection("rooms").document(RelayCrypto.roomId(code))
            room.get()
                .addOnSuccessListener { roomDoc ->
                    // Do not upload SMS unless this app installation owns the room
                    // and exactly one receiver is currently paired.
                    if (roomDoc.getString("ownerUid") != uid ||
                        roomDoc.getString("receiverUid").isNullOrBlank()
                    ) {
                        pending.finish()
                        return@addOnSuccessListener
                    }
                    try {
                        val encrypted = RelayCrypto.encrypt(code, body)
                        val data = hashMapOf(
                            "nonce" to encrypted.nonce,
                            "ciphertext" to encrypted.ciphertext,
                            "sender" to sender,
                            "createdAt" to FieldValue.serverTimestamp()
                        )
                        room.collection("messages").add(data)
                            .addOnCompleteListener { pending.finish() }
                    } catch (error: Exception) {
                        Log.e("SmsRelay", "Could not encrypt or upload SMS", error)
                        pending.finish()
                    }
                }
                .addOnFailureListener {
                    Log.e("SmsRelay", "Could not check pairing status", it)
                    pending.finish()
                }
        }

        val currentUser = auth.currentUser
        if (currentUser != null) {
            uploadFor(currentUser.uid)
        } else {
            auth.signInAnonymously()
                .addOnSuccessListener { result -> uploadFor(result.user?.uid ?: run { pending.finish(); return@addOnSuccessListener }) }
                .addOnFailureListener {
                    Log.e("SmsRelay", "Firebase anonymous sign-in failed", it)
                    pending.finish()
                }
        }
    }
}
