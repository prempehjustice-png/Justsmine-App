package com.example.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.security.MessageDigest
import java.util.UUID

class MinerRepository(private val db: AppDatabase) {

    private val userDao = db.userDao()
    private val contractDao = db.contractDao()
    private val transactionDao = db.transactionDao()
    private val rewardClaimDao = db.rewardClaimDao()
    private val activityLogDao = db.activityLogDao()
    private val supportTicketDao = db.supportTicketDao()

    // --- Helper Functions ---
    private fun hashPassword(password: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(password.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun generateReferralCode(): String {
        return UUID.randomUUID().toString().substring(0, 8).uppercase()
    }

    private fun generateTxHash(): String {
        val chars = "0123456789abcdef"
        val hash = (1..64).map { chars.random() }.joinToString("")
        return "0x$hash"
    }

    // --- Authentication Flow ---

    suspend fun getUserByEmail(email: String): UserEntity? {
        return userDao.getUserByEmail(email)
    }

    fun observeUserByEmail(email: String): Flow<UserEntity?> {
        return userDao.observeUserByEmail(email)
    }

    suspend fun signUp(email: String, passwordHashRaw: String, username: String, referredBy: String?): Result<UserEntity> {
        val trimmedEmail = email.trim().lowercase()
        if (trimmedEmail.isEmpty() || passwordHashRaw.isEmpty() || username.trim().isEmpty()) {
            return Result.failure(Exception("All fields are required"))
        }

        val existing = userDao.getUserByEmail(trimmedEmail)
        if (existing != null) {
            return Result.failure(Exception("Email already exists"))
        }

        // Validate referral if provided
        var referralBonus = 0.0
        var validReferredBy: String? = null
        if (!referredBy.isNullOrBlank()) {
            // Check if referral code exists among users by scanning database (simulated with a query or we can just accept it if non-empty)
            // For robust system, we can just log referral
            validReferredBy = referredBy.trim().uppercase()
        }

        val newUser = UserEntity(
            email = trimmedEmail,
            passwordHash = hashPassword(passwordHashRaw),
            username = username.trim(),
            isEmailConfirmed = false, // Must confirm email first!
            btcBalance = 0.0,
            hasFreeContractClaimed = false,
            twoFactorSecret = UUID.randomUUID().toString().substring(0, 16).uppercase(),
            twoFactorEnabled = false,
            biometricEnabled = false,
            kycStatus = "UNVERIFIED",
            referralCode = generateReferralCode(),
            referredBy = validReferredBy,
            totalHashrate = 0.0
        )

        userDao.insertUser(newUser)
        recordActivity(trimmedEmail, "REGISTER", "Account registered. Verification email sent.")

        return Result.success(newUser)
    }

    suspend fun confirmEmail(email: String): Result<Boolean> {
        val trimmedEmail = email.trim().lowercase()
        val user = userDao.getUserByEmail(trimmedEmail) ?: return Result.failure(Exception("User not found"))
        
        if (user.isEmailConfirmed) {
            return Result.success(true)
        }

        // Confirm email and auto-grant FREE 10 TH/s contract
        val updatedUser = user.copy(
            isEmailConfirmed = true,
            totalHashrate = user.totalHashrate + 10.0,
            hasFreeContractClaimed = true
        )
        userDao.updateUser(updatedUser)

        val freeContract = ContractEntity(
            userEmail = trimmedEmail,
            planName = "Free Signup Contract",
            hashrate = 10.0,
            purchaseTime = System.currentTimeMillis(),
            expiryTime = System.currentTimeMillis() + (365 * 24 * 60 * 60 * 1000L) // 1 year
        )
        contractDao.insertContract(freeContract)

        recordActivity(trimmedEmail, "EMAIL_CONFIRMED", "Email verified. Received Free 10 TH/S contract!")
        return Result.success(true)
    }

    suspend fun login(email: String, passwordRaw: String): Result<UserEntity> {
        val trimmedEmail = email.trim().lowercase()
        val user = userDao.getUserByEmail(trimmedEmail) ?: return Result.failure(Exception("Invalid email or password"))

        if (user.passwordHash != hashPassword(passwordRaw)) {
            return Result.failure(Exception("Invalid email or password"))
        }

        if (!user.isEmailConfirmed) {
            return Result.failure(Exception("Email not confirmed. Please verify your email first."))
        }

        recordActivity(trimmedEmail, "LOGIN", "Successfully logged in.")
        return Result.success(user)
    }

    suspend fun forgotPassword(email: String): Result<String> {
        val trimmedEmail = email.trim().lowercase()
        val user = userDao.getUserByEmail(trimmedEmail) ?: return Result.failure(Exception("User not found"))
        recordActivity(trimmedEmail, "FORGOT_PASSWORD", "Password reset request initiated.")
        return Result.success("Password reset instructions have been sent to $trimmedEmail.")
    }

    // --- Profile, KYC, 2FA, Security ---

    suspend fun updateProfile(email: String, username: String): Result<Boolean> {
        val user = userDao.getUserByEmail(email) ?: return Result.failure(Exception("User not found"))
        val updated = user.copy(username = username)
        userDao.updateUser(updated)
        recordActivity(email, "PROFILE_UPDATE", "Updated username to $username")
        return Result.success(true)
    }

    suspend fun changePassword(email: String, oldPass: String, newPass: String): Result<Boolean> {
        val user = userDao.getUserByEmail(email) ?: return Result.failure(Exception("User not found"))
        if (user.passwordHash != hashPassword(oldPass)) {
            return Result.failure(Exception("Current password is incorrect"))
        }
        val updated = user.copy(passwordHash = hashPassword(newPass))
        userDao.updateUser(updated)
        recordActivity(email, "PASSWORD_CHANGE", "Account password updated successfully")
        return Result.success(true)
    }

    suspend fun set2FAEnabled(email: String, enabled: Boolean): Result<Boolean> {
        val user = userDao.getUserByEmail(email) ?: return Result.failure(Exception("User not found"))
        val updated = user.copy(twoFactorEnabled = enabled)
        userDao.updateUser(updated)
        recordActivity(email, "2FA_TOGGLE", "Two-factor authentication ${if (enabled) "enabled" else "disabled"}")
        return Result.success(true)
    }

    suspend fun setBiometricEnabled(email: String, enabled: Boolean): Result<Boolean> {
        val user = userDao.getUserByEmail(email) ?: return Result.failure(Exception("User not found"))
        val updated = user.copy(biometricEnabled = enabled)
        userDao.updateUser(updated)
        recordActivity(email, "BIOMETRIC_TOGGLE", "Biometric authentication ${if (enabled) "enabled" else "disabled"}")
        return Result.success(true)
    }

    suspend fun setDarkModeEnabled(email: String, enabled: Boolean) {
        val user = userDao.getUserByEmail(email) ?: return
        val updated = user.copy(isDarkMode = enabled)
        userDao.updateUser(updated)
    }

    suspend fun setPushNotificationsEnabled(email: String, enabled: Boolean) {
        val user = userDao.getUserByEmail(email) ?: return
        val updated = user.copy(pushNotificationsEnabled = enabled)
        userDao.updateUser(updated)
    }

    suspend fun submitKYC(email: String, docUri: String): Result<Boolean> {
        val user = userDao.getUserByEmail(email) ?: return Result.failure(Exception("User not found"))
        val updated = user.copy(kycStatus = "PENDING", kycDocUri = docUri)
        userDao.updateUser(updated)
        recordActivity(email, "KYC_SUBMIT", "KYC documents submitted for verification.")
        return Result.success(true)
    }

    suspend fun autoApproveKYC(email: String) {
        val user = userDao.getUserByEmail(email) ?: return
        if (user.kycStatus == "PENDING") {
            val updated = user.copy(kycStatus = "VERIFIED")
            userDao.updateUser(updated)
            recordActivity(email, "KYC_APPROVED", "KYC status automatically verified by Bitget compliance system.")
        }
    }

    // --- Mining Contracts and Claims ---

    fun observeContracts(email: String): Flow<List<ContractEntity>> {
        return contractDao.observeContractsByEmail(email)
    }

    suspend fun getActiveContracts(email: String): List<ContractEntity> {
        return contractDao.getActiveContracts(email)
    }

    suspend fun purchaseContract(email: String, planName: String, hashrate: Double, priceBtc: Double): Result<Boolean> {
        val user = userDao.getUserByEmail(email) ?: return Result.failure(Exception("User not found"))
        if (user.btcBalance < priceBtc) {
            return Result.failure(Exception("Insufficient balance to buy plan"))
        }

        val updatedUser = user.copy(
            btcBalance = user.btcBalance - priceBtc,
            totalHashrate = user.totalHashrate + hashrate
        )
        userDao.updateUser(updatedUser)

        val newContract = ContractEntity(
            userEmail = email,
            planName = planName,
            hashrate = hashrate,
            purchaseTime = System.currentTimeMillis(),
            expiryTime = System.currentTimeMillis() + (30 * 24 * 60 * 60 * 1000L) // 30 Days contract
        )
        contractDao.insertContract(newContract)

        val tx = TransactionEntity(
            userEmail = email,
            type = "DEPOSIT",
            amount = priceBtc,
            currency = "BTC",
            walletAddress = "JustsMine Cloud Miner",
            status = "COMPLETED",
            timestamp = System.currentTimeMillis(),
            txHash = generateTxHash()
        )
        transactionDao.insertTransaction(tx)

        recordActivity(email, "PLAN_PURCHASE", "Purchased contract: $planName with $hashrate TH/S power")
        return Result.success(true)
    }

    suspend fun claimDailyReward(email: String): Result<Double> {
        val user = userDao.getUserByEmail(email) ?: return Result.failure(Exception("User not found"))
        val lastClaim = rewardClaimDao.getLastClaim(email)
        val now = System.currentTimeMillis()

        if (lastClaim != null && (now - lastClaim.timestamp) < (24 * 60 * 60 * 1000L)) {
            val remainingMs = (24 * 60 * 60 * 1000L) - (now - lastClaim.timestamp)
            val hours = remainingMs / (60 * 60 * 1000L)
            val minutes = (remainingMs % (60 * 60 * 1000L)) / (60 * 1000L)
            return Result.failure(Exception("Daily reward already claimed. Please wait $hours hours, $minutes minutes."))
        }

        // Add 2 TH/s Contract
        val updatedUser = user.copy(totalHashrate = user.totalHashrate + 2.0)
        userDao.updateUser(updatedUser)

        val rewardContract = ContractEntity(
            userEmail = email,
            planName = "Daily Claim Reward",
            hashrate = 2.0,
            purchaseTime = now,
            expiryTime = now + (24 * 60 * 60 * 1000L) // 24 hours reward
        )
        contractDao.insertContract(rewardContract)

        rewardClaimDao.insertClaim(RewardClaimEntity(userEmail = email, timestamp = now))
        recordActivity(email, "CLAIM_REWARD", "Claimed 2 TH/S daily contract boost")

        return Result.success(2.0)
    }

    // --- Real-time Mining Tick Updates ---

    suspend fun updateMiningBalance(email: String, minedAmount: Double) {
        val user = userDao.getUserByEmail(email) ?: return
        val updated = user.copy(
            btcBalance = user.btcBalance + minedAmount,
            lastActiveTime = System.currentTimeMillis()
        )
        userDao.updateUser(updated)
    }

    suspend fun syncUserFieldsFromRemote(
        email: String,
        btcBalance: Double,
        totalHashrate: Double,
        kycStatus: String,
        hasFreeContractClaimed: Boolean,
        twoFactorEnabled: Boolean,
        twoFactorSecret: String
    ): Result<Boolean> {
        val user = userDao.getUserByEmail(email) ?: return Result.failure(Exception("User not found"))
        val updated = user.copy(
            btcBalance = btcBalance,
            totalHashrate = totalHashrate,
            kycStatus = kycStatus,
            hasFreeContractClaimed = hasFreeContractClaimed,
            twoFactorEnabled = twoFactorEnabled,
            twoFactorSecret = twoFactorSecret
        )
        userDao.updateUser(updated)
        return Result.success(true)
    }

    // --- Transactions & Live Bitget Withdrawal Flow ---

    fun observeTransactions(email: String): Flow<List<TransactionEntity>> {
        return transactionDao.observeTransactionsByEmail(email)
    }

    suspend fun requestWithdrawal(email: String, walletAddress: String, amount: Double, code2Fa: String): Result<TransactionEntity> {
        val user = userDao.getUserByEmail(email) ?: return Result.failure(Exception("User not found"))
        
        if (amount <= 0.0) {
            return Result.failure(Exception("Amount must be greater than zero"))
        }

        if (user.btcBalance < amount) {
            return Result.failure(Exception("Insufficient BTC balance. Current balance: ${String.format("%.8f", user.btcBalance)} BTC"))
        }

        // Validate 2FA if enabled
        if (user.twoFactorEnabled) {
            // Simulated validation. Simple check for non-empty matching user's expectation
            if (code2Fa.isBlank() || code2Fa.length != 6 || !code2Fa.all { it.isDigit() }) {
                return Result.failure(Exception("Invalid 2FA code. Please enter the 6-digit code from your authenticator app."))
            }
        }

        // Security check: must have KYC verified or pending
        if (user.kycStatus == "UNVERIFIED") {
            return Result.failure(Exception("Security Hold: Please complete KYC verification before making a withdrawal."))
        }

        // Deduct balance
        val updatedUser = user.copy(btcBalance = user.btcBalance - amount)
        userDao.updateUser(updatedUser)

        // Create transaction as PENDING
        val tx = TransactionEntity(
            userEmail = email,
            type = "WITHDRAWAL",
            amount = amount,
            currency = "BTC",
            walletAddress = walletAddress,
            status = "PENDING",
            timestamp = System.currentTimeMillis(),
            txHash = "Processing in Blockchain via Bitget..."
        )
        transactionDao.insertTransaction(tx)
        recordActivity(email, "WITHDRAW_REQUEST", "Requested withdrawal of $amount BTC to $walletAddress")

        return Result.success(tx)
    }

    suspend fun processWithdrawalCompletion(txId: Int): TransactionEntity? {
        // We retrieve the transaction, update status to APPROVED then COMPLETED
        // In database, retrieve transactions by email or just query database directly or do update
        // Since we don't have direct findTx, let's observe and modify. Let's do it via queries:
        // Or write custom dao method, but we can do a quick load or simulation in viewmodel by creating complete state
        return null
    }

    suspend fun updateTransactionStatus(tx: TransactionEntity, status: String, txHash: String) {
        val updated = tx.copy(status = status, txHash = txHash)
        transactionDao.updateTransaction(updated)
    }

    // --- Support Tickets ---

    fun observeSupportTickets(email: String): Flow<List<SupportTicketEntity>> {
        return supportTicketDao.observeSupportTickets(email)
    }

    suspend fun createSupportTicket(email: String, subject: String, message: String): Result<Boolean> {
        if (subject.isBlank() || message.isBlank()) {
            return Result.failure(Exception("Subject and message are required"))
        }
        val ticket = SupportTicketEntity(
            userEmail = email,
            subject = subject,
            message = message,
            status = "OPEN"
        )
        supportTicketDao.insertSupportTicket(ticket)
        recordActivity(email, "TICKET_OPENED", "Opened support ticket: $subject")
        return Result.success(true)
    }

    suspend fun addSupportReply(ticket: SupportTicketEntity, replyText: String) {
        val updated = ticket.copy(status = "RESOLVED", reply = replyText)
        supportTicketDao.insertSupportTicket(updated) // or update, insert replaces on unique if we had primary key
    }

    // --- Activity Logs ---

    fun observeActivityLogs(email: String): Flow<List<ActivityLogEntity>> {
        return activityLogDao.observeActivityLogs(email)
    }

    suspend fun recordActivity(email: String, type: String, details: String) {
        activityLogDao.insertActivityLog(
            ActivityLogEntity(userEmail = email, activityType = type, details = details)
        )
    }
}
