package com.spendtracker.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transactions")
data class Transaction(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amount: Double,
    /** DEBIT, CREDIT, OWED (you owe via Splitwise), LENT (owed to you via Splitwise) */
    val type: String,
    val merchant: String,
    val category: String,
    /** SMS, NOTIFICATION, MANUAL */
    val source: String,
    val senderOrApp: String,
    val upiRef: String?,
    val bank: String?,
    val rawMessage: String,
    val timestamp: Long
)
