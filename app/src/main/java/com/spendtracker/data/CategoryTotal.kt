package com.spendtracker.data

/** One row of the monthly per-category spend breakdown (DEBIT only). */
data class CategoryTotal(
    val category: String,
    val total: Double
)
