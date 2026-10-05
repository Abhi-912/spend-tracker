package com.spendtracker.notifications

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.spendtracker.data.AppDatabase
import com.spendtracker.data.Transaction
import com.spendtracker.parser.TransactionParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Captures payment confirmations from GPay / PhonePe / Paytm / BHIM etc.,
 * plus Splitwise expense additions (stored as OWED/LENT, separate from spend).
 * Enable via Settings > Notification access. All processing is on-device.
 */
class PaymentNotificationService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString().orEmpty()
        val text = extras.getCharSequence("android.text")?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return

        if (pkg == TransactionParser.SPLITWISE_PACKAGE) {
            val parsed = TransactionParser.parseSplitwiseNotification(pkg, title, text) ?: return
            store(parsed, pkg, "SPLITWISE", "$title — $text")
            return
        }

        if (pkg !in TransactionParser.PAYMENT_PACKAGES) return
        val parsed = TransactionParser.parseNotification(pkg, title, text) ?: return
        store(parsed, pkg, "NOTIFICATION", "$title — $text")
    }

    private fun store(
        parsed: TransactionParser.Parsed,
        pkg: String,
        source: String,
        raw: String
    ) {
        val now = System.currentTimeMillis()
        scope.launch {
            val dao = AppDatabase.get(this@PaymentNotificationService).transactionDao()
            val dups = dao.findDuplicates(parsed.amount, now - 2 * 60 * 1000, now + 60 * 1000)
            val isDup = dups.any {
                TransactionParser.isDuplicate(
                    parsed.amount, parsed.upiRef, parsed.merchant, now,
                    it.amount, it.upiRef, it.merchant, it.timestamp
                )
            }
            if (!isDup) {
                dao.insert(
                    Transaction(
                        amount = parsed.amount,
                        type = parsed.type,
                        merchant = parsed.merchant,
                        category = parsed.category,
                        source = source,
                        senderOrApp = pkg,
                        upiRef = parsed.upiRef,
                        bank = parsed.bank,
                        rawMessage = raw.take(500),
                        timestamp = now
                    )
                )
            }
        }
    }
}
