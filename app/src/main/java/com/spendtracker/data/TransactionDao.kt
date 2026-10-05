package com.spendtracker.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tx: Transaction): Long

    @Update
    suspend fun update(tx: Transaction)

    @Delete
    suspend fun delete(tx: Transaction)

    @Query("SELECT * FROM transactions ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<Transaction>>

    @Query("SELECT * FROM transactions ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recent(limit: Int = 200): List<Transaction>

    /** Dedup: same amount within time window, optionally same UPI ref. */
    @Query(
        """SELECT * FROM transactions
           WHERE amount = :amount
           AND timestamp BETWEEN :fromTs AND :toTs
           LIMIT 5"""
    )
    suspend fun findDuplicates(amount: Double, fromTs: Long, toTs: Long): List<Transaction>

    @Query("SELECT IFNULL(SUM(amount),0) FROM transactions WHERE type='DEBIT' AND timestamp >= :fromTs")
    fun observeSpendSince(fromTs: Long): Flow<Double>

    @Query("SELECT IFNULL(SUM(amount),0) FROM transactions WHERE type='OWED' AND timestamp >= :fromTs")
    fun observeOwedSince(fromTs: Long): Flow<Double>

    @Query("SELECT IFNULL(SUM(amount),0) FROM transactions WHERE type='LENT' AND timestamp >= :fromTs")
    fun observeLentSince(fromTs: Long): Flow<Double>

    @Query(
        """SELECT category AS category, IFNULL(SUM(amount),0) AS total
           FROM transactions WHERE type='DEBIT' AND timestamp >= :fromTs
           GROUP BY category ORDER BY total DESC"""
    )
    fun observeCategorySpendSince(fromTs: Long): Flow<List<CategoryTotal>>

    @Query("DELETE FROM transactions")
    suspend fun clearAll()
}
