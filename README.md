# SpendTracker (internal, on-device only)

Tracks every payment on the phone via:
- **Transactional SMS** (`SmsReceiver`) — bank/UPI debit+credit alerts. Best coverage in India.
- **Payment notifications** (`PaymentNotificationService`) — GPay / PhonePe / Paytm / BHIM / Amazon Pay / CRED confirmations.
- **Splitwise additions** — same service captures Splitwise expense notifications as `OWED` (you owe) / `LENT` (owed to you), kept separate from actual spend. Settlements are ignored to avoid double-counting the UPI transfer.
- Monthly **per-category breakdown** (Food/Travel/Shopping/Bills/Health/Other, DEBIT only).
- All parsing + storage **on-device** (Room). No `INTERNET` permission.

## Coverage (realistic)
~90-95% of UPI + bank transfers that generate SMS or a payment notification.
Won't catch: cash, silent web checkouts, cards with alerts disabled.

Dedup: same amount within ±2 min + same UPI ref / merchant = one row.

## Managing entries
- Pencil icon on any transaction corrects its name (e.g. `Unknown` → real merchant).
- Trash icon on any transaction deletes it immediately.
- **Clear all** (next to Transactions) wipes everything after confirmation.

## Build (sideload APK for personal use)

Requires: JDK 17/21, Android SDK 34.

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew :app:assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

No Gradle wrapper jar is checked in (to keep repo lean). If `./gradlew` is missing, generate once on a machine with Gradle:

```sh
gradle wrapper --gradle-version 8.7
```

Or build directly with system Gradle:

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
gradle :app:assembleDebug
```

## First-run setup (on phone)
1. Open app → **Grant SMS** (READ_SMS + RECEIVE_SMS).
2. **Enable access** → turn on SpendTracker under Notification access.
3. Make a test UPI payment → it appears in the list.

## Tests (parser is pure Kotlin, runs on JVM without emulator)
```sh
gradle :app:testDebugUnitTest --tests "com.spendtracker.parser.*"
```

## Privacy
- No network calls, no analytics, `allowBackup=false`.
- Uninstall wipes DB. Raw SMS text stored truncated to 500 chars for merchant debugging.
