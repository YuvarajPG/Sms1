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
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isEmpty()) return

        val sender = messages.firstOrNull()?.originatingAddress.orEmpty()
        val body = messages.joinToString(separator = "") { it.messageBody.orEmpty() }
        if (body.isBlank()) return

        val prefs = context.getSharedPreferences("sms_relay", Context.MODE_PRIVATE)
        val code = prefs.getString("sender_pairing_code", null) ?: return
        val pending = goAsync()

        fun upload() {
            try {
                val encrypted = RelayCrypto.encrypt(code, body)
                val data = hashMapOf(
                    "nonce" to encrypted.nonce,
                    "ciphertext" to encrypted.ciphertext,
                    "sender" to sender,
                    "createdAt" to FieldValue.serverTimestamp()
                )
                FirebaseFirestore.getInstance()
                    .collection("rooms")
                    .document(RelayCrypto.roomId(code))
                    .collection("messages")
                    .add(data)
                    .addOnCompleteListener { pending.finish() }
            } catch (error: Exception) {
                Log.e("SmsRelay", "Could not encrypt or queue SMS", error)
                pending.finish()
            }
        }

        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser != null) {
            upload()
        } else {
            auth.signInAnonymously()
                .addOnSuccessListener { upload() }
                .addOnFailureListener {
                    Log.e("SmsRelay", "Firebase anonymous sign-in failed", it)
                    pending.finish()
                }
        }
    }
}
