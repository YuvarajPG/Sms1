# SMS Relay

Forwards SMS received on one Android phone (the **sender**) to another Android phone (the **receiver**), over the internet, end-to-end encrypted. Use it on phones you own, for example a backup phone or a second SIM.

```
 SMS arrives ──> Sender phone ──encrypt──> Firebase Firestore ──> Receiver phone ──decrypt──> notification
```

## Features

- Sender and Receiver modes in one app
- Pairing by QR code or by typing a 10-character code
- Optional sender filter (forward only senders you choose, e.g. `HDFCBK`)
- Notifications and message history on both phones
- Messages are encrypted with AES-256-GCM using a key derived from the pairing code, so Firestore only stores ciphertext
- Messages are deleted from the cloud once the receiver has them (and expire after 7 days if you enable the TTL policy)
- Retries automatically when the sender is offline, and restarts after a reboot

## 1. Firebase setup (about 5 minutes, free Spark plan)

1. Go to <https://console.firebase.google.com> and create a project.
2. Add an **Android app** with package name `com.yuvaraj.smsrelay`.
3. Download `google-services.json` and put it in `app/` (next to `app/build.gradle.kts`).
4. **Build > Authentication > Sign-in method:** enable **Anonymous**.
5. **Build > Firestore Database:** create a database (production mode).
6. **Firestore > Rules:** paste the contents of `firestore.rules` and publish.
7. Optional but recommended: **Firestore > Indexes > TTL > Create policy** with collection group `messages` and field `expireAt`. This auto-deletes messages that were never delivered.

## 2. Build and install

1. Open this folder in **Android Studio** (Koala or newer) and let Gradle sync.
2. If Android Studio offers to update the Android Gradle Plugin or Gradle, accept.
3. Run it on the phone, or **Build > Build APK(s)** and install the APK on both phones.

## 3. Pair the phones

1. On the phone with the SIM, open the app and choose **Sender phone**. Grant the SMS and notification permissions.
2. On the other phone, choose **Receiver phone** and scan the QR code (or type the code).
3. Send a test SMS to the sender phone. It should show up on the receiver within a few seconds.

## Redmi / Xiaomi / MIUI / HyperOS notes

These matter a lot, otherwise the system will kill the app in the background:

- **Settings > Apps > Manage apps > SMS Relay:** turn on **Autostart**, and set **Battery saver** to **No restrictions**.
- Lock the app in the recent-apps screen (long-press its card, tap the lock).
- If you installed from an APK on Android 13 or newer and the SMS permission can't be granted: **App info > ⋮ menu > Allow restricted settings**, then grant SMS under Permissions.
- MIUI may also require **Other permissions > Receive SMS / Notification SMS**.

## Project layout

| Path | What it does |
|---|---|
| `sender/SmsReceiver.kt` | Catches incoming SMS, applies the filter, queues an upload |
| `sender/UploadWorker.kt` | Encrypts and uploads (WorkManager: waits for network, retries) |
| `sender/SenderService.kt` | Foreground service that keeps the process alive |
| `receiver/ReceiverService.kt` | Foreground service with the live Firestore listener |
| `data/Crypto.kt` | Pairing code, room ID and AES-GCM encryption |
| `data/RelayRepository.kt` | All Firestore access |
| `data/HistoryStore.kt`, `data/Prefs.kt` | Local history and settings |
| `ui/` | Jetpack Compose screens |
| `firestore.rules` | Security rules to publish in Firebase |

## Privacy and security

- The pairing code is both the room address and the encryption key. Anyone who has it can read your forwarded messages, so don't share it. **Generate new code** on the sender invalidates the old one.
- The message text is end-to-end encrypted, but metadata such as message timing and size is visible to Firebase.
- Forwarded SMS can contain one-time passwords. Anyone who can see the receiver phone can see them too, so only pair phones you control, and use the sender filter if you only need certain messages.

## Limitations

- Android only on both phones (iOS doesn't let apps read incoming SMS, and this app isn't built for iOS).
- Only classic SMS. RCS chats, WhatsApp and other app messages aren't forwarded.
- Messages longer than 3000 characters are truncated.
- If the sender phone is switched off or has no internet, messages are queued and sent when it's back (WorkManager retries for several minutes with backoff, then gives up on that message).
- Both phones need internet. The sender needs a working SIM or eSIM that receives SMS.

## Troubleshooting

- **Build fails with "google-services.json is missing":** step 1.3 above.
- **Nothing arrives:** check Anonymous auth is enabled, the rules are published, both phones show the same code, and battery restrictions are off on both.
- **"Permission denied" in logcat:** the rules weren't published, or Anonymous sign-in is disabled.
