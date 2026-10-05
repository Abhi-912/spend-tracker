package com.spendtracker.parser

/**
 * Pure-Kotlin parser for Indian transactional SMS + payment notifications.
 * No Android dependencies so it is unit-testable on JVM.
 */
object TransactionParser {

    data class Parsed(
        val amount: Double,
        val type: String, // DEBIT | CREDIT | OWED | LENT
        val merchant: String,
        val upiRef: String?,
        val bank: String?,
        val category: String
    )

    // Rs. 500 / Rs 500 / INR 1,250.00 / ₹450
    private val amountRegex =
        Regex("""(?:Rs\.?|INR|₹)\s?([\d,]+\.?\d*)""", RegexOption.IGNORE_CASE)

    private val upiRefRegex = Regex(
        """(?:UPI(?:\s?(?:Ref(?:erence)?|Txn))?|UTR|Ref(?:erence)?(?:\s?No)?)\s?(?:\:|#|is)?\s?(\d{6,})""",
        RegexOption.IGNORE_CASE
    )

    // A/c XX1234, Ac 1234, Card XX4321, Wallet
    private val accountRegex = Regex(
        """(?:A/?c(?:count)?\.?\s?(?:No\.?|XX|X+)?\s?(\d{4,})|Card\s?(?:XX|X+)?\s?(\d{4,}))""",
        RegexOption.IGNORE_CASE
    )

    // Word boundaries matter: "sent" must not match "preSENTed"
    // (AutoPay mandate *creation* messages move no money and must be ignored).
    private val debitPattern = Regex(
        """\b(debited|debit|spent|paid|sent|purchase)\b|payment of|transferred to""",
        RegexOption.IGNORE_CASE
    )
    private val creditPattern = Regex(
        """\b(credited|credit|received|refund|cashback)\b""",
        RegexOption.IGNORE_CASE
    )

    // Package names of payment apps we care about for notifications.
    val PAYMENT_PACKAGES = setOf(
        "com.google.android.apps.nbu.paisa.user", // GPay
        "com.phonepe.app",                        // PhonePe
        "net.one97.paytm",                        // Paytm
        "in.org.npci.upiapp",                     // BHIM
        "com.amazon.pay",                         // Amazon Pay
        "com.cred.club",                          // CRED
        "com.freecharge.android"                  // Freecharge
    )

    /** Splitwise Android package — tracked separately as OWED/LENT, never as spend. */
    const val SPLITWISE_PACKAGE = "com.Splitwise.SplitwiseMobile"

    /** Returns null if message is not a financial transaction. */
    fun parseSms(body: String, sender: String): Parsed? {
        val type = when {
            debitPattern.containsMatchIn(body) -> "DEBIT"
            creditPattern.containsMatchIn(body) -> "CREDIT"
            else -> return null
        }

        val amountMatch = amountRegex.find(body) ?: return null
        val amount = amountMatch.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
        if (amount <= 0) return null

        val upiRef = upiRefRegex.find(body)?.groupValues?.getOrNull(1)
        val bank = detectBank(body, sender)
        val merchant = detectMerchantSms(body)
        return Parsed(
            amount = amount,
            type = type,
            merchant = merchant,
            upiRef = upiRef,
            bank = bank,
            category = Categorizer.category(merchant, body)
        )
    }

    /** Returns null if notification is not a payment confirmation. */
    fun parseNotification(packageName: String, title: String, text: String): Parsed? {
        if (packageName !in PAYMENT_PACKAGES) return null
        val combined = "$title $text"
        val lower = combined.lowercase()
        val isPayment = lower.contains("paid") || lower.contains("payment") ||
            lower.contains("sent") || lower.contains("received") ||
            lower.contains("successful") || lower.contains("debited") ||
            lower.contains("credited")
        if (!isPayment) return null

        val amountMatch = amountRegex.find(combined) ?: return null
        val amount = amountMatch.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null

        val type = if (lower.contains("received") || lower.contains("credited") || lower.contains("cashback")) "CREDIT" else "DEBIT"
        val merchant = detectMerchantNotification(title, text)
        return Parsed(
            amount = amount,
            type = type,
            merchant = merchant,
            upiRef = upiRefRegex.find(combined)?.groupValues?.getOrNull(1),
            bank = null,
            category = Categorizer.category(merchant, combined)
        )
    }

    /** Returns null if notification is not a Splitwise expense addition. */
    fun parseSplitwiseNotification(packageName: String, title: String, text: String): Parsed? {
        if (packageName != SPLITWISE_PACKAGE) return null
        val combined = "$title $text"
        val lower = combined.lowercase()
        // Only expense additions ("A added 'Dinner' ... You owe ₹500").
        // Settlements ("settled up") are ignored: the actual money movement
        // shows up via UPI SMS/notification, and counting both would double-count.
        val isExpense = lower.contains("added") || lower.contains("you owe") ||
            lower.contains("owes you") || lower.contains("owe you") ||
            lower.contains("your share")
        if (!isExpense) return null

        val amountMatch = amountRegex.find(combined) ?: return null
        val amount = amountMatch.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
        if (amount <= 0) return null

        val type = if (lower.contains("owes you") || lower.contains("owe you")) "LENT" else "OWED"
        val merchant = detectSplitwiseExpense(title, text)
        return Parsed(
            amount = amount,
            type = type,
            merchant = merchant,
            upiRef = null,
            bank = null,
            category = Categorizer.category(merchant, combined)
        )
    }

    private fun detectSplitwiseExpense(title: String, text: String): String {
        val combined = "$title $text"
        // Expense name is usually quoted: added "Dinner" in "Goa Trip" -> Dinner
        val quoted = Regex("\"([^\"]{1,40})\"").findAll(combined)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .toList()
        if (quoted.isNotEmpty()) return quoted.first().take(40)
        val m = Regex(
            """added\s+([A-Za-z0-9 .&_-]{2,40})(?:\s+in|\s+to|$)""",
            RegexOption.IGNORE_CASE
        ).find(combined)
        return m?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
            ?: title.take(40).ifBlank { "Splitwise" }
    }

    private fun detectBank(body: String, sender: String): String? {
        // Sender IDs like HDFCBK, SBINBK, ICICIB, AXISBK
        val senderBank = when {
            sender.contains("HDFC", true) -> "HDFC"
            sender.contains("SBI", true) -> "SBI"
            sender.contains("ICICI", true) -> "ICICI"
            sender.contains("AXIS", true) -> "Axis"
            sender.contains("KOTAK", true) -> "Kotak"
            sender.contains("PNB", true) -> "PNB"
            sender.contains("BOB", true) -> "BoB"
            else -> null
        }
        if (senderBank != null) return senderBank
        val m = Regex("""(HDFC|SBI|ICICI|Axis|Kotak|PNB|BOB|Yes Bank|IDFC)\s?Bank""", RegexOption.IGNORE_CASE).find(body)
        return m?.groupValues?.getOrNull(1)?.uppercase()
    }

    private fun detectMerchantSms(body: String): String {
        // 1. VPA is the most specific: "VPA name@upi"
        Regex("""(?:VPA|UPI:?\s?)\s?([a-zA-Z0-9._-]{2,}@[a-zA-Z]{2,})""", RegexOption.IGNORE_CASE)
            .find(body)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { return it.take(40) }

        // 2. Merchant BEFORE the keyword (ICICI style):
        //    "... on 05-Oct-26; Hari Om General credited. UPI:..."
        val beforeKeyword =
            Regex("""(?:^|;\s*)([A-Za-z][A-Za-z0-9 .&'\-]{1,39}?)\s+(?:credited|debited)""", RegexOption.IGNORE_CASE)
        for (m in beforeKeyword.findAll(body)) {
            val v = m.groupValues[1].trim().trim('.', ' ')
            if (v.length >= 2 && looksLikeMerchant(v)) return v.take(40)
        }

        // 3. Merchant AFTER to/at/towards (HDFC style: "... to SWIGGY on ...",
        //    autopay style: "... towards NETFLIX for Autopay ...").
        //    Skips numeric captures (phone numbers like "to 9215676766").
        val afterTo =
            Regex("""(?:to|at|towards)\s+([A-Za-z0-9 .&_-]{2,30}?)(?:\s+on|\s+via|\s+for|\s+UPI|\.|$)""", RegexOption.IGNORE_CASE)
        for (m in afterTo.findAll(body)) {
            val v = m.groupValues[1].trim().trim('.', ' ')
            if (v.length >= 2 && looksLikeMerchant(v) && !v.matches(Regex("""[\d\s.,\-+]+"""))) return v.take(40)
        }
        return "Unknown"
    }

    /** Rejects account fragments ("ICICI Bank Acct XX824") and refs, keeps real names. */
    private fun looksLikeMerchant(v: String): Boolean {
        if (v.contains("Ref", ignoreCase = true)) return false
        if (v.any { it.isDigit() }) return false
        val lower = v.lowercase()
        if (listOf("bank", "acct", "a/c", "upi", "utr", "xx").any { lower.contains(it) }) return false
        return true
    }

    private fun detectMerchantNotification(title: String, text: String): String {
        // "Payment of ₹450 to Sharma Tea Stall successful" -> merchant after "to"
        val m = Regex("""to\s+([A-Za-z0-9 .&_-]{2,40})(?:\s+successful|\s+via|\s+is|$)""", RegexOption.IGNORE_CASE)
            .find("$title $text")
        return m?.groupValues?.getOrNull(1)?.trim() ?: title.take(40).ifBlank { "Unknown" }
    }

    /** True if [existing] likely refers to the same payment (dedup window ±2 min). */
    fun isDuplicate(
        amount: Double,
        newRef: String?,
        newMerchant: String,
        newTs: Long,
        existingAmount: Double,
        existingRef: String?,
        existingMerchant: String,
        existingTs: Long,
        windowMs: Long = 2 * 60 * 1000L
    ): Boolean {
        if (amount != existingAmount) return false
        if (kotlin.math.abs(newTs - existingTs) > windowMs) return false
        if (newRef != null && newRef == existingRef) return true
        return newMerchant.equals(existingMerchant, ignoreCase = true) ||
            newMerchant == "Unknown" || existingMerchant == "Unknown"
    }
}

object Categorizer {
    fun category(merchant: String, raw: String): String {
        val s = "$merchant $raw".lowercase()
        return when {
            listOf("swiggy", "zomato", "restaurant", "cafe", "tea", "food", "dominos", "dhaba").any { s.contains(it) } -> "Food"
            listOf("uber", "ola", "rapido", "metro", "irctc", "flight", "makemytrip", "petrol", "fuel").any { s.contains(it) } -> "Travel"
            listOf("amazon", "flipkart", "myntra", "shopping", "mart", "store", "bigbasket", "blinkit", "zepto").any { s.contains(it) } -> "Shopping"
            listOf("electricity", "water", "gas", "recharge", "bill", "rent", "broadband", "jio", "airtel").any { s.contains(it) } -> "Bills"
            listOf("pharmacy", "hospital", "clinic", "doctor").any { s.contains(it) } -> "Health"
            else -> "Other"
        }
    }
}
