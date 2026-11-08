package com.yuvaraj.smsrelay

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
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
import androidx.compose.runtime.Composable
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
import androidx.fragment.app.FragmentActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.yuvaraj.smsrelay.data.PairingPolicy
import com.yuvaraj.smsrelay.data.RelayCrypto

class MainActivity : FragmentActivity() {
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

    fun authenticateForDisconnect(onAuthenticated: () -> Unit, onError: (String) -> Unit) {
        val biometricManager = BiometricManager.from(this)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG
        if (biometricManager.canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            onError("A registered strong biometric (such as fingerprint) is required to disconnect.")
            return
        }

        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onAuthenticated()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onError(errString.toString())
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Disconnect receiver")
            .setSubtitle("Verify your fingerprint to disconnect this device")
            .setNegativeButtonText("Cancel")
            .setAllowedAuthenticators(authenticators)
            .build()
        prompt.authenticate(info)
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

@Composable
private fun RelayApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context as? MainActivity
    val prefs = remember { context.getSharedPreferences("sms_relay", Context.MODE_PRIVATE) }
    val db = remember { FirebaseFirestore.getInstance() }
    var mode by remember { mutableStateOf(RelayMode.SENDER) }
    var pairingCode by remember { mutableStateOf(prefs.getString("sender_pairing_code", "") ?: "") }
    var receiverCode by remember { mutableStateOf(prefs.getString("receiver_pairing_code", "") ?: "") }
    var receiverName by remember {
        mutableStateOf(prefs.getString("receiver_display_name", defaultDeviceName()) ?: defaultDeviceName())
    }
    var messages by remember { mutableStateOf<List<String>>(emptyList()) }
    var connectedName by remember { mutableStateOf("") }
    var connectedModel by remember { mutableStateOf("") }
    var receiverConnected by remember { mutableStateOf(false) }
    var authUid by remember { mutableStateOf(FirebaseAuth.getInstance().currentUser?.uid) }
    var status by remember { mutableStateOf("Connecting to Firebase…") }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser != null) {
            authUid = auth.currentUser?.uid
            status = "Connected to Firebase"
        } else {
            auth.signInAnonymously()
                .addOnSuccessListener {
                    authUid = it.user?.uid
                    status = "Connected to Firebase"
                }
                .addOnFailureListener { status = "Firebase sign-in failed: ${it.localizedMessage}" }
        }
    }

    // Restore/verify the sender's room without overwriting an existing connection.
    LaunchedEffect(authUid, pairingCode) {
        val uid = authUid
        if (uid != null && pairingCode.length >= 16) {
            val ref = db.collection("rooms").document(RelayCrypto.roomId(pairingCode))
            ref.get()
                .addOnSuccessListener { doc ->
                    if (!doc.exists()) {
                        ref.set(
                            mapOf(
                                "ownerUid" to uid,
                                "receiverUid" to null,
                                "receiverName" to "",
                                "receiverModel" to "",
                                "receiverConnectedAt" to null,
                                "status" to "WAITING",
                                "createdAt" to FieldValue.serverTimestamp()
                            )
                        ).addOnFailureListener { status = "Could not create pairing room: ${it.localizedMessage}" }
                    } else if (doc.getString("ownerUid") != uid) {
                        status = "This pairing code belongs to a different app installation. Generate a new code."
                    }
                }
                .addOnFailureListener { status = "Could not load pairing room: ${it.localizedMessage}" }
        }
    }

    // Sender sees the currently claimed receiver and its chosen device label.
    DisposableEffect(mode, pairingCode, authUid) {
        if (mode != RelayMode.SENDER || pairingCode.length < 16 || authUid == null) {
            receiverConnected = false
            connectedName = ""
            connectedModel = ""
            onDispose { }
        } else {
            val ref = db.collection("rooms").document(RelayCrypto.roomId(pairingCode))
            val registration = ref.addSnapshotListener { doc, error ->
                if (error != null) {
                    status = "Pairing status error: ${error.localizedMessage}"
                } else if (doc != null && doc.exists()) {
                    if (doc.getString("ownerUid") != authUid) {
                        status = "Pairing room owner mismatch"
                        receiverConnected = false
                    } else {
                        receiverConnected = !doc.getString("receiverUid").isNullOrBlank()
                        connectedName = doc.getString("receiverName").orEmpty()
                        connectedModel = doc.getString("receiverModel").orEmpty()
                        status = if (receiverConnected) "Receiver connected" else "Waiting for a receiver"
                    }
                }
            }
            onDispose { registration.remove() }
        }
    }

    // Receiver must successfully claim the room before its message listener is started.
    DisposableEffect(mode, receiverCode, authUid) {
        if (mode != RelayMode.RECEIVER || receiverCode.length < 16 || authUid == null) {
            messages = emptyList()
            onDispose { }
        } else {
            val room = db.collection("rooms").document(RelayCrypto.roomId(receiverCode))
            var messageRegistration: com.google.firebase.firestore.ListenerRegistration? = null
            val roomRegistration = room.addSnapshotListener { doc, error ->
                messageRegistration?.remove()
                messageRegistration = null
                if (error != null) {
                    status = "Pairing status error: ${error.localizedMessage}"
                } else if (doc == null || !doc.exists()) {
                    status = "Pairing code not found"
                    messages = emptyList()
                } else if (doc.getString("receiverUid") != authUid) {
                    status = "This phone is not the connected receiver"
                    messages = emptyList()
                } else {
                    status = "Connected to ${doc.getString("receiverName").orEmpty()}"
                    messageRegistration = room.collection("messages")
                        .orderBy("createdAt", Query.Direction.DESCENDING)
                        .limit(50)
                        .addSnapshotListener { snapshot, messageError ->
                            if (messageError != null) {
                                status = "Message sync error: ${messageError.localizedMessage}"
                            } else if (snapshot != null) {
                                messages = snapshot.documents.mapNotNull { message ->
                                    runCatching {
                                        val sender = message.getString("sender").orEmpty()
                                        val text = RelayCrypto.decrypt(
                                            receiverCode,
                                            message.getString("nonce") ?: return@mapNotNull null,
                                            message.getString("ciphertext") ?: return@mapNotNull null
                                        )
                                        "${sender.ifBlank { "Unknown sender" }}: $text"
                                    }.getOrNull()
                                }
                            }
                        }
                }
            }
            onDispose {
                roomRegistration.remove()
                messageRegistration?.remove()
            }
        }
    }

    fun createPairingRoom() {
        val uid = authUid
        if (uid == null) {
            status = "Wait for Firebase authentication"
            return
        }
        busy = true
        val newCode = RelayCrypto.newPairingCode()
        val ref = db.collection("rooms").document(RelayCrypto.roomId(newCode))
        ref.set(
            mapOf(
                "ownerUid" to uid,
                "receiverUid" to null,
                "receiverName" to "",
                "receiverModel" to "",
                "receiverConnectedAt" to null,
                "status" to "WAITING",
                "createdAt" to FieldValue.serverTimestamp()
            )
        ).addOnSuccessListener {
            pairingCode = newCode
            prefs.edit().putString("sender_pairing_code", newCode).apply()
            status = "Waiting for one receiver"
            busy = false
        }.addOnFailureListener {
            status = "Could not create pairing code: ${it.localizedMessage}"
            busy = false
        }
    }

    fun claimPairing() {
        val uid = authUid
        if (uid == null) {
            status = "Wait for Firebase authentication"
            return
        }
        if (receiverName.isBlank()) {
            status = "Enter a name for this phone"
            return
        }
        busy = true
        val ref = db.collection("rooms").document(RelayCrypto.roomId(receiverCode))
        val model = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        db.runTransaction { transaction ->
            val doc = transaction.get(ref)
            if (!doc.exists()) throw IllegalStateException("Pairing code not found")
            val ownerUid = doc.getString("ownerUid") ?: throw IllegalStateException("Invalid pairing room")
            val existingReceiver = doc.getString("receiverUid")
            if (!PairingPolicy.canClaim(existingReceiver, uid, ownerUid)) {
                throw IllegalStateException("This sender is already paired with another phone")
            }
            transaction.update(
                ref,
                mapOf(
                    "receiverUid" to uid,
                    "receiverName" to receiverName.trim(),
                    "receiverModel" to model,
                    "receiverConnectedAt" to FieldValue.serverTimestamp(),
                    "status" to "CONNECTED"
                )
            )
            null
        }.addOnSuccessListener {
            prefs.edit()
                .putString("receiver_pairing_code", receiverCode)
                .putString("receiver_display_name", receiverName.trim())
                .apply()
            status = "Successfully paired with sender"
            busy = false
        }.addOnFailureListener {
            status = it.cause?.localizedMessage ?: it.localizedMessage ?: "Pairing failed"
            busy = false
        }
    }

    fun disconnectReceiverAfterBiometric() {
        val uid = authUid
        if (uid == null) {
            status = "Firebase authentication is not ready"
            return
        }
        val currentActivity = activity
        if (currentActivity == null) {
            status = "Biometric authentication is unavailable"
            return
        }
        currentActivity.authenticateForDisconnect(
            onAuthenticated = {
                busy = true
                val ref = db.collection("rooms").document(RelayCrypto.roomId(pairingCode))
                db.runTransaction { transaction ->
                    val doc = transaction.get(ref)
                    if (!doc.exists()) throw IllegalStateException("Pairing room no longer exists")
                    val ownerUid = doc.getString("ownerUid").orEmpty()
                    val receiverUid = doc.getString("receiverUid")
                    if (!PairingPolicy.canDisconnect(ownerUid, uid, receiverUid)) {
                        throw IllegalStateException("Only the sender can disconnect an active receiver")
                    }
                    transaction.update(
                        ref,
                        mapOf(
                            "receiverUid" to null,
                            "receiverName" to "",
                            "receiverModel" to "",
                            "receiverConnectedAt" to null,
                            "status" to "WAITING"
                        )
                    )
                    null
                }.addOnSuccessListener {
                    status = "Receiver disconnected successfully"
                    busy = false
                }.addOnFailureListener {
                    status = "Disconnect failed: ${it.localizedMessage}"
                    busy = false
                }
            },
            onError = { status = it }
        )
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
            Text("Only one receiver can be connected to this sender at a time.")
            if (pairingCode.isBlank()) {
                Button(onClick = { createPairingRoom() }, enabled = !busy && authUid != null) {
                    Text("Generate pairing code")
                }
            } else {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Your pairing code", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(pairingCode, fontSize = 22.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("Enter this code on the receiver phone. Keep it private.")
                    }
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Connected device", style = MaterialTheme.typography.titleMedium)
                        if (receiverConnected) {
                            Text(connectedName.ifBlank { "Unnamed receiver" }, style = MaterialTheme.typography.titleLarge)
                            if (connectedModel.isNotBlank()) Text(connectedModel, style = MaterialTheme.typography.bodySmall)
                            Text("One receiver is paired with this sender.")
                            Button(
                                onClick = { disconnectReceiverAfterBiometric() },
                                enabled = !busy
                            ) { Text("Disconnect (biometric required)") }
                        } else {
                            Text("No device connected")
                            Text("Waiting for a receiver to pair.")
                        }
                    }
                }
                if (!receiverConnected) {
                    Button(onClick = { createPairingRoom() }, enabled = !busy) {
                        Text("Generate new pairing code")
                    }
                }
            }
            Text("Only forward messages from a SIM and phone you own or are authorized to manage.")
        } else {
            Text("Receiver phone", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = receiverName,
                onValueChange = {
                    receiverName = it
                    prefs.edit().putString("receiver_display_name", it).apply()
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Name shown on sender phone") },
                singleLine = true
            )
            OutlinedTextField(
                value = receiverCode,
                onValueChange = { receiverCode = it.uppercase().filterNot(Char::isWhitespace) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Enter sender pairing code") },
                singleLine = true
            )
            Button(
                onClick = { claimPairing() },
                enabled = receiverCode.length >= 16 && receiverName.isNotBlank() && !busy && authUid != null
            ) { Text("Connect") }
            Text("Received messages")
            if (messages.isEmpty()) Text("No messages received yet.")
            messages.forEach { message ->
                Card(Modifier.fillMaxWidth()) { Text(message, Modifier.padding(14.dp)) }
            }
        }
    }
}

private fun defaultDeviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
