package com.yuvaraj.smsrelay

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.yuvaraj.smsrelay.data.RelayCrypto

class MainActivity : ComponentActivity() {
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNeededPermissions()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) { RelayApp() }
            }
        }
    }

    private fun requestNeededPermissions() {
        val permissions = buildList {
            add(Manifest.permission.RECEIVE_SMS)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }
}

private enum class RelayMode { SENDER, RECEIVER }

@androidx.compose.runtime.Composable
private fun RelayApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("sms_relay", Context.MODE_PRIVATE) }
    var mode by remember { mutableStateOf(RelayMode.SENDER) }
    var pairingCode by remember { mutableStateOf(prefs.getString("sender_pairing_code", "") ?: "") }
    var receiverCode by remember { mutableStateOf(prefs.getString("receiver_pairing_code", "") ?: "") }
    var messages by remember { mutableStateOf<List<String>>(emptyList()) }
    var status by remember { mutableStateOf("Connecting to Firebase…") }
    var authReady by remember { mutableStateOf(FirebaseAuth.getInstance().currentUser != null) }

    LaunchedEffect(Unit) {
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser != null) status = "Connected to Firebase"
        else auth.signInAnonymously()
            .addOnSuccessListener { authReady = true; status = "Connected to Firebase" }
            .addOnFailureListener { status = "Firebase sign-in failed: ${it.localizedMessage}" }
    }

    DisposableEffect(mode, receiverCode, authReady) {
        if (!authReady || mode != RelayMode.RECEIVER || receiverCode.length < 16) {
            messages = emptyList()
            onDispose { }
        } else {
            val registration = FirebaseFirestore.getInstance()
                .collection("rooms")
                .document(runCatching { RelayCrypto.roomId(receiverCode) }.getOrElse { "" })
                .collection("messages")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(50)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        status = "Firestore error: ${error.localizedMessage}"
                    } else if (snapshot != null) {
                        messages = snapshot.documents.mapNotNull { doc ->
                            runCatching {
                                val sender = doc.getString("sender").orEmpty()
                                val text = RelayCrypto.decrypt(
                                    receiverCode,
                                    doc.getString("nonce") ?: return@mapNotNull null,
                                    doc.getString("ciphertext") ?: return@mapNotNull null
                                )
                                "${sender.ifBlank { "Unknown sender" }}: $text"
                            }.getOrNull()
                        }
                        status = "Listening for forwarded SMS"
                    }
                }
            onDispose { registration.remove() }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("SMS Relay", fontSize = 30.sp)
        Text(status, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { mode = RelayMode.SENDER }) { Text("Sender") }
            Button(onClick = { mode = RelayMode.RECEIVER }) { Text("Receiver") }
        }

        if (mode == RelayMode.SENDER) {
            Text("Sender phone", style = MaterialTheme.typography.titleLarge)
            Text("Keep this phone's SIM active. Incoming SMS messages are encrypted before upload.")
            if (pairingCode.isBlank()) {
                Button(onClick = {
                    pairingCode = RelayCrypto.newPairingCode()
                    prefs.edit().putString("sender_pairing_code", pairingCode).apply()
                }) { Text("Generate pairing code") }
            } else {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Your pairing code", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(pairingCode, fontSize = 22.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("Enter this code on your receiver phone. Keep it private.")
                    }
                }
                Button(onClick = {
                    pairingCode = RelayCrypto.newPairingCode()
                    prefs.edit().putString("sender_pairing_code", pairingCode).apply()
                }) { Text("Generate new code") }
            }
            Text("SMS permission is required. Only use this app to forward messages from a phone and SIM you control.")
        } else {
            Text("Receiver phone", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = receiverCode,
                onValueChange = { receiverCode = it.uppercase().filterNot(Char::isWhitespace) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Enter sender pairing code") },
                singleLine = true
            )
            Button(
                onClick = {
                    prefs.edit().putString("receiver_pairing_code", receiverCode).apply()
                    status = "Pairing code saved"
                },
                enabled = receiverCode.length >= 16
            ) { Text("Connect") }
            Text("Received messages")
            if (messages.isEmpty()) Text("No messages received yet.")
            messages.forEach { message ->
                Card(Modifier.fillMaxWidth()) {
                    Text(message, Modifier.padding(14.dp))
                }
            }
        }
    }
}
