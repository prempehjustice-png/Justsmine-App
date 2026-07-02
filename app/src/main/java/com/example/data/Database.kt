package com.example.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

// --- ENTITIES ---

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val email: String,
    val passwordHash: String,
    val username: String,
    val isEmailConfirmed: Boolean = false,
    val btcBalance: Double = 0.0,
    val hasFreeContractClaimed: Boolean = false,
    val twoFactorSecret: String = "",
    val twoFactorEnabled: Boolean = false,
    val biometricEnabled: Boolean = false,
    val kycStatus: String = "UNVERIFIED", // UNVERIFIED, PENDING, VERIFIED
    val kycDocUri: String? = null,
    val referralCode: String = "",
    val referredBy: String? = null,
    val referralEarnings: Double = 0.0,
    val totalHashrate: Double = 0.0,
    val isDarkMode: Boolean = true,
    val pushNotificationsEnabled: Boolean = true,
    val lastActiveTime: Long = System.currentTimeMillis()
)

@Entity(tableName = "contracts")
data class ContractEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val userEmail: String,
    val planName: String,
    val hashrate: Double,
    val purchaseTime: Long,
    val expiryTime: Long,
    val isActive: Boolean = true
)

@Entity(tableName = "transactions")
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val userEmail: String,
    val type: String, // DEPOSIT, WITHDRAWAL
    val amount: Double,
    val currency: String = "BTC",
    val walletAddress: String,
    val status: String, // PENDING, APPROVED, COMPLETED, FAILED
    val timestamp: Long,
    val txHash: String
)

@Entity(tableName = "reward_claims")
data class RewardClaimEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val userEmail: String,
    val timestamp: Long
)

@Entity(tableName = "activity_logs")
data class ActivityLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val userEmail: String,
    val activityType: String,
    val details: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "support_tickets")
data class SupportTicketEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val userEmail: String,
    val subject: String,
    val message: String,
    val status: String, // OPEN, RESOLVED
    val timestamp: Long = System.currentTimeMillis(),
    val reply: String? = null
)

// --- DAOS ---

@Dao
interface UserDao {
    @Query("SELECT * FROM users WHERE email = :email LIMIT 1")
    suspend fun getUserByEmail(email: String): UserEntity?

    @Query("SELECT * FROM users WHERE email = :email LIMIT 1")
    fun observeUserByEmail(email: String): Flow<UserEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUser(user: UserEntity)

    @Update
    suspend fun updateUser(user: UserEntity)
}

@Dao
interface ContractDao {
    @Query("SELECT * FROM contracts WHERE userEmail = :email")
    fun observeContractsByEmail(email: String): Flow<List<ContractEntity>>

    @Query("SELECT * FROM contracts WHERE userEmail = :email AND isActive = 1")
    suspend fun getActiveContracts(email: String): List<ContractEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContract(contract: ContractEntity)
}

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions WHERE userEmail = :email ORDER BY timestamp DESC")
    fun observeTransactionsByEmail(email: String): Flow<List<TransactionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransaction(transaction: TransactionEntity)

    @Update
    suspend fun updateTransaction(transaction: TransactionEntity)
}

@Dao
interface RewardClaimDao {
    @Query("SELECT * FROM reward_claims WHERE userEmail = :email ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastClaim(email: String): RewardClaimEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertClaim(claim: RewardClaimEntity)
}

@Dao
interface ActivityLogDao {
    @Query("SELECT * FROM activity_logs WHERE userEmail = :email ORDER BY timestamp DESC")
    fun observeActivityLogs(email: String): Flow<List<ActivityLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertActivityLog(log: ActivityLogEntity)
}

@Dao
interface SupportTicketDao {
    @Query("SELECT * FROM support_tickets WHERE userEmail = :email ORDER BY timestamp DESC")
    fun observeSupportTickets(email: String): Flow<List<SupportTicketEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSupportTicket(ticket: SupportTicketEntity)
}

// --- DATABASE ---

@Database(
    entities = [
        UserEntity::class,
        ContractEntity::class,
        TransactionEntity::class,
        RewardClaimEntity::class,
        ActivityLogEntity::class,
        SupportTicketEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun contractDao(): ContractDao
    abstract fun transactionDao(): TransactionDao
    abstract fun rewardClaimDao(): RewardClaimDao
    abstract fun activityLogDao(): ActivityLogDao
    abstract fun supportTicketDao(): SupportTicketDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "justsmine_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
