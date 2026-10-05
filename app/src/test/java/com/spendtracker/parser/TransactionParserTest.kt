package com.spendtracker.parser

import org.junit.Assert.*
import org.junit.Test

class TransactionParserTest {

    @Test fun parsesHdfcUpiDebitSms() {
        val sms = "Rs.500.00 debited from A/c XX1234 via UPI Ref 123456789012 to SWIGGY on 05-10-26"
        val p = TransactionParser.parseSms(sms, "HDFCBK")!!
        assertEquals(500.0, p.amount, 0.001)
        assertEquals("DEBIT", p.type)
        assertEquals("SWIGGY", p.merchant)
        assertEquals("123456789012", p.upiRef)
        assertEquals("HDFC", p.bank)
        assertEquals("Food", p.category)
    }

    @Test fun parsesCreditSms() {
        val sms = "INR 1,250.00 credited to your SBI A/c XX5678 by UPI Ref 999888777666"
        val p = TransactionParser.parseSms(sms, "SBINBK")!!
        assertEquals(1250.0, p.amount, 0.001)
        assertEquals("CREDIT", p.type)
    }

    @Test fun parsesIciciMerchantBeforeKeyword() {
        val sms = "ICICI Bank Acct XX824 debited for Rs 30.00 on 05-Oct-26; Hari Om General credited. UPI:863947395128. Call 18002662 for dispute. SMS BLOCK 824 to 9215676766."
        val p = TransactionParser.parseSms(sms, "ICICIB")!!
        assertEquals(30.0, p.amount, 0.001)
        assertEquals("DEBIT", p.type)
        assertEquals("Hari Om General", p.merchant)
        assertEquals("863947395128", p.upiRef)
        assertEquals("ICICI", p.bank)
    }

    @Test fun parsesCreditWithMerchantBeforeKeyword() {
        val sms = "Acct XX123 credited with Rs 1,000.00 on 06-Oct-26; Salary ABC Corp credited. UPI:111222333444"
        val p = TransactionParser.parseSms(sms, "HDFCBK")!!
        assertEquals(1000.0, p.amount, 0.001)
        assertEquals("CREDIT", p.type)
        assertEquals("Salary ABC Corp", p.merchant)
    }

    @Test fun parsesIciciSanveerSingh() {
        val sms = "ICICI Bank Acct XX824 debited for Rs 20.00 on 02-Oct-26; SANVEER SINGH credited. UPI:588951127890. Call 18002662 for dispute. SMS BLOCK 824 to 9215676766."
        val p = TransactionParser.parseSms(sms, "ICICIB")!!
        assertEquals(20.0, p.amount, 0.001)
        assertEquals("DEBIT", p.type)
        assertEquals("SANVEER SINGH", p.merchant)
        assertEquals("588951127890", p.upiRef)
        assertEquals("ICICI", p.bank)
    }

    @Test fun parsesIciciLargeAmountNoComma() {
        val sms = "ICICI Bank Acct XX824 debited for Rs 10000.00 on 05-Oct-26; Abhishek Pandey credited. UPI:275465847896. Call 18002662 for dispute. SMS BLOCK 824 to 9215676766."
        val p = TransactionParser.parseSms(sms, "ICICIB")!!
        assertEquals(10000.0, p.amount, 0.001)
        assertEquals("DEBIT", p.type)
        assertEquals("Abhishek Pandey", p.merchant)
        assertEquals("275465847896", p.upiRef)
    }

    @Test fun ignoresAutopayMandateCreation() {
        // Mandate setup moves no money — must not be recorded.
        val sms = "Your AutoPay mandate with ASPRESENTED is successfully created towards APPLE MEDIA SER from 05-Oct-26 to 22-Sep-36 for Rs 8199.00, RRN 104182967682-ICICI Bank."
        assertNull(TransactionParser.parseSms(sms, "ICICIB"))
    }

    @Test fun parsesNetflixAutopayDebit() {
        val sms = "Rs 199.00 debited from ICICI Bank Savings Account XX824 on 05-Oct-26 towards NETFLIX for Autopay AutoPay Retrieval Ref No.104188749274"
        val p = TransactionParser.parseSms(sms, "ICICIB")!!
        assertEquals(199.0, p.amount, 0.001)
        assertEquals("DEBIT", p.type)
        assertEquals("NETFLIX", p.merchant)
        assertEquals("ICICI", p.bank)
    }

    @Test fun ignoresOtpSms() {
        assertNull(TransactionParser.parseSms("Your OTP is 452310 for login. Do not share.", "HDFCBK"))
    }

    @Test fun parsesGpayNotification() {
        val p = TransactionParser.parseNotification(
            "com.google.android.apps.nbu.paisa.user",
            "Payment successful",
            "Payment of ₹450 to Sharma Tea Stall successful via Google Pay"
        )!!
        assertEquals(450.0, p.amount, 0.001)
        assertEquals("DEBIT", p.type)
    }

    @Test fun ignoresNonPaymentNotification() {
        assertNull(
            TransactionParser.parseNotification(
                "com.google.android.apps.nbu.paisa.user",
                "New offer",
                "Get 10% cashback this weekend"
            )
        )
    }

    @Test fun parsesSplitwiseYouOwe() {
        val p = TransactionParser.parseSplitwiseNotification(
            "com.Splitwise.SplitwiseMobile",
            "Goa Trip",
            "Amit added \"Dinner\" in \"Goa Trip\": You owe ₹500"
        )!!
        assertEquals(500.0, p.amount, 0.001)
        assertEquals("OWED", p.type)
        assertEquals("Dinner", p.merchant)
    }

    @Test fun parsesSplitwiseOwesYou() {
        val p = TransactionParser.parseSplitwiseNotification(
            "com.Splitwise.SplitwiseMobile",
            "Friends",
            "You added \"Cab\" in \"Friends\": Rahul owes you ₹250"
        )!!
        assertEquals(250.0, p.amount, 0.001)
        assertEquals("LENT", p.type)
        assertEquals("Cab", p.merchant)
    }

    @Test fun ignoresSplitwiseSettlement() {
        // Settlements are real money movement -> counted via UPI, not here.
        assertNull(
            TransactionParser.parseSplitwiseNotification(
                "com.Splitwise.SplitwiseMobile",
                "Goa Trip",
                "Amit settled ₹500 with you in \"Goa Trip\""
            )
        )
    }

    @Test fun ignoresSplitwisePromo() {
        assertNull(
            TransactionParser.parseSplitwiseNotification(
                "com.Splitwise.SplitwiseMobile",
                "Simplify your debts",
                "Check out the new charts view"
            )
        )
    }

    @Test fun ignoresWrongPackage() {
        assertNull(
            TransactionParser.parseSplitwiseNotification(
                "com.whatsapp",
                "Goa Trip",
                "Amit added \"Dinner\": You owe ₹500"
            )
        )
    }
    @Test fun dedupsSamePayment() {
        assertTrue(
            TransactionParser.isDuplicate(
                500.0, "123", "SWIGGY", 1_000_000L,
                500.0, "123", "SWIGGY", 1_060_000L
            )
        )
        assertFalse(
            TransactionParser.isDuplicate(
                500.0, "123", "SWIGGY", 1_000_000L,
                600.0, "123", "SWIGGY", 1_060_000L
            )
        )
    }
}
