# SMS Relay

Android-to-Android SMS forwarding using Firebase Authentication and Cloud Firestore. SMS content is encrypted on the sender phone with AES-256-GCM before upload and decrypted locally on the receiver.

## Current implementation

- Sender and receiver modes
- Random 16-character pairing code
- Anonymous Firebase Authentication
- Firestore real-time receiver listener
- AES-256-GCM encryption/decryption
- Incoming SMS broadcast receiver on the sender
- JVM unit tests for encryption/decryption

This is an MVP, not production-ready. QR pairing, delivered-message deletion, notification display, durable offline queue, and foreground service are not implemented yet. Receiver app must remain open to display messages. Do not rely on it for critical OTP delivery.

## Firebase setup

The Android app is registered as com.yuvaraj.smsrelay. Its project config is app/google-services.json.

In Firebase Console for project project-a8795:
1. Authentication -> Sign-in method -> enable Anonymous.
2. Create a Cloud Firestore database.
3. Firestore -> Rules -> paste firestore.rules and publish.

The rules allow authenticated clients to read/delete documents under a room path. The path is derived from the random pairing code, which is never uploaded. Anyone with the pairing code can access that room. Keep it private and regenerate it if exposed.

## Build and test

1. Open the repository in Android Studio.
2. Use JDK 17 and Android SDK 35.
3. Allow Gradle sync to complete.
4. Install on two Android phones.
5. On the SIM phone, select Sender and generate a pairing code.
6. On the other phone, select Receiver, enter the code, and tap Connect.
7. Grant SMS permission on the sender. Send a test SMS to that phone while keeping the receiver app open.

## Permissions and security

The app requests RECEIVE_SMS and notification permission on Android 13+. SMS permissions are sensitive and subject to Google Play policy. This project is intended for personal sideloading/testing on devices you own or are authorized to manage.

Message bodies are encrypted locally with AES-GCM using a PBKDF2-HMAC-SHA256-derived key. Firestore stores ciphertext, nonce, sender address, and timestamp; sender address and timing are metadata. The pairing code is the decryption secret. Configure Firebase App Check and suitable API restrictions before wider distribution.
