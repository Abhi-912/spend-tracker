package com.spendtracker.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.spendtracker.data.AppDatabase
import com.spendtracker.data.Transaction
import com.spendtracker.parser.TransactionParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Captures incoming transactional SMS, parses on-device, stores in Room. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val body = messages.joinToString("") { it.messageBody ?: "" }
        if (body.isBlank()) return
        val sender = messages.firstOrNull()?.originatingAddress.orEmpty()

        val parsed = TransactionParser.parseSms(body, sender) ?: return
        val now = System.currentTimeMillis()
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.get(context)
                val dao = db.transactionDao()
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
                            source = "SMS",
                            senderOrApp = sender,
                            upiRef = parsed.upiRef,
                            bank = parsed.bank,
                            rawMessage = body.take(500),
                            timestamp = now
                        )
                    )
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
