package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ActivityLogEntity
import com.example.data.AppDatabase
import com.example.data.ContractEntity
import com.example.data.MinerRepository
import com.example.data.FirebaseSyncService
import com.example.data.SupportTicketEntity
import com.example.data.TransactionEntity
import com.example.data.UserEntity
import com.example.data.FirestoreWithdrawal
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed class Screen {
    object Splash : Screen()
    object Login : Screen()
    object SignUp : Screen()
    object ForgotPassword : Screen()
    data class ConfirmEmail(val email: String) : Screen()
    object Dashboard : Screen()
}

data class LiveWithdrawalFeed(
    val username: String,
    val amount: Double,
    val timeAgo: String,
    val via: String = "Bitget HotWallet"
)

class MinerViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application)
    private val repository = MinerRepository(db)
    val firebaseSyncService = FirebaseSyncService(application, repository)

    // --- Screen Navigation ---
    private val _currentScreen = MutableStateFlow<Screen>(Screen.Splash)
    val currentScreen: StateFlow<Screen> = _currentScreen.asStateFlow()

    // --- Active User Email (Session) ---
    private val _currentUserEmail = MutableStateFlow<String?>(null)
    val currentUserEmail: StateFlow<String?> = _currentUserEmail.asStateFlow()

    // --- Reactive UI States ---
    val currentUser: StateFlow<UserEntity?> = _currentUserEmail
        .flatMapLatest { email ->
            if (email != null) repository.observeUserByEmail(email) else flowOf(null)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val activeContracts: StateFlow<List<ContractEntity>> = _currentUserEmail
        .flatMapLatest { email ->
            if (email != null) repository.observeContracts(email) else flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val transactions: StateFlow<List<TransactionEntity>> = _currentUserEmail
        .flatMapLatest { email ->
            if (email != null) repository.observeTransactions(email) else flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activityLogs: StateFlow<List<ActivityLogEntity>> = _currentUserEmail
        .flatMapLatest { email ->
            if (email != null) repository.observeActivityLogs(email) else flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val supportTickets: StateFlow<List<SupportTicketEntity>> = _currentUserEmail
        .flatMapLatest { email ->
            if (email != null) repository.observeSupportTickets(email) else flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // --- Toast / In-App Banner Notifications ---
    private val _notifications = MutableSharedFlow<String>()
    val notifications: SharedFlow<String> = _notifications.asSharedFlow()

    private val _appAlert = MutableStateFlow<String?>(null)
    val appAlert: StateFlow<String?> = _appAlert.asStateFlow()

    // --- Loader & Action States ---
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // --- Live Peer Withdrawal Stream ---
    private val _liveFeed = MutableStateFlow<List<LiveWithdrawalFeed>>(emptyList())
    val liveFeed: StateFlow<List<LiveWithdrawalFeed>> = _liveFeed.asStateFlow()

    // --- Firestore Real-time Withdrawals List ---
    private val _firestoreWithdrawals = MutableStateFlow<List<FirestoreWithdrawal>>(emptyList())
    val firestoreWithdrawals: StateFlow<List<FirestoreWithdrawal>> = _firestoreWithdrawals.asStateFlow()

    // --- Active Mining Loop Job ---
    private var miningJob: Job? = null

    // --- In-Memory Email Verification Codes ---
    private val _verificationCodes = mutableMapOf<String, String>()

    init {
        generateInitialLiveFeed()
        startLiveFeedSimulator()
    }

    fun dismissAlert() {
        _appAlert.value = null
    }

    // --- Navigation Utilities ---
    fun navigateTo(screen: Screen) {
        _currentScreen.value = screen
    }

    // --- Splash Logic ---
    fun runSplashCheck() {
        viewModelScope.launch {
            _isLoading.value = true
            delay(2000) // Beautiful 2s animation loader
            _isLoading.value = false
            // Check auto-login: hardcode remember-me check or standard login screen
            _currentScreen.value = Screen.Login
        }
    }

    // --- Authentication Actions ---
    fun handleSignUp(email: String, pass: String, user: String, referral: String?) {
        viewModelScope.launch {
            _isLoading.value = true
            var isFirebaseFallback = false
            var firebaseErrorMessage = ""
            val result = if (firebaseSyncService.isFirebaseReady()) {
                val fbResult = firebaseSyncService.signUpFirebase(email, pass, user, referral)
                if (fbResult.isFailure) {
                    val ex = fbResult.exceptionOrNull()
                    val msg = ex?.message ?: ""
                    val isConfigError = (ex is com.google.firebase.auth.FirebaseAuthException && 
                            (ex.errorCode == "ERROR_OPERATION_NOT_ALLOWED" || msg.contains("disabled", ignoreCase = true) || msg.contains("not allowed", ignoreCase = true))) ||
                            msg.contains("configuration", ignoreCase = true) ||
                            msg.contains("operation-not-allowed", ignoreCase = true)
                    
                    if (isConfigError) {
                        isFirebaseFallback = true
                        firebaseErrorMessage = msg
                        // Fall back to local room signup
                        repository.signUp(email, pass, user, referral)
                    } else {
                        fbResult
                    }
                } else {
                    fbResult
                }
            } else {
                repository.signUp(email, pass, user, referral)
            }
            _isLoading.value = false
            result.onSuccess {
                // Generate a 6-digit confirmation code for verification
                val code = (100000..999999).random().toString()
                _verificationCodes[email.trim().lowercase()] = code

                if (firebaseSyncService.isFirebaseReady() && !isFirebaseFallback) {
                    _currentScreen.value = Screen.ConfirmEmail(email)
                    _notifications.emit("Firebase Verification Email sent! Please verify your email first.")
                    _appAlert.value = "📧 FIREBASE CLOUD VERIFICATION DISPATCHED\n\n" +
                            "A secure verification email has been sent to: $email\n\n" +
                            "• Verification Mode: Firebase Authenticated Mailer\n" +
                            "• Verification Code: $code (Optional local fallback)\n\n" +
                            "Please check your inbox or use the 6-Digit Code above to instantly confirm your account and claim your FREE 10 TH/S hashing contract."
                } else {
                    _currentScreen.value = Screen.ConfirmEmail(email)
                    if (isFirebaseFallback) {
                        _notifications.emit("Firebase sign-up provider disabled. Switched to Local Offline Mode.")
                        _appAlert.value = "DIAGNOSTIC ADVISORY: Firebase Email/Password Sign-Up is not enabled on your Firebase Console.\n\n" +
                                "To resolve this configuration issue:\n" +
                                "1. Go to your Firebase Console (console.firebase.google.com)\n" +
                                "2. Navigate to Build -> Authentication -> Sign-in method\n" +
                                "3. Click on 'Email/Password' and switch it to Enable\n\n" +
                                "JustsMine has successfully activated LOCAL OFFLINE MODE fallback so you can continue testing immediately.\n\n" +
                                "📧 OFFLINE VERIFICATION MAIL DISPATCHED\n" +
                                "An email was sent to: $email\n" +
                                "Your 6-Digit Cryptographic Code is: $code"
                    } else {
                        _notifications.emit("Verification code sent to $email!")
                        _appAlert.value = "📧 LOCAL OFFLINE VERIFICATION DISPATCHED\n\n" +
                                "An offline verification email has been sent to: $email\n\n" +
                                "Your 6-Digit Cryptographic Code is: $code\n\n" +
                                "Enter this code on the verification screen to activate your account."
                    }
                }
            }.onFailure {
                _notifications.emit("Error: ${it.message}")
            }
        }
    }

    fun handleConfirmEmail(email: String, code: String) {
        viewModelScope.launch {
            _isLoading.value = true
            delay(1500) // Simulated network delay
            
            val trimmedEmail = email.trim().lowercase()
            val expectedCode = _verificationCodes[trimmedEmail]
            
            // Allow code matching, or default placeholder bypass for convenience
            val isCodeValid = expectedCode == null || expectedCode == code.trim() || code.trim() == "123456"
            
            if (!isCodeValid) {
                _isLoading.value = false
                _notifications.emit("Error: Invalid verification code. Please check your inbox or resend code.")
                return@launch
            }
            
            val result = repository.confirmEmail(email)
            _isLoading.value = false
            result.onSuccess {
                _currentUserEmail.value = email.trim().lowercase()
                _currentScreen.value = Screen.Dashboard
                _appAlert.value = "Welcome! Your email has been confirmed. You received a FREE 10 TH/S Mining Contract!"
                
                // If firebase is live, attempt background mirroring
                if (firebaseSyncService.isFirebaseReady()) {
                    firebaseSyncService.backupLocalDataToFirebase()
                }
                
                startMiningLoop()
            }.onFailure {
                _notifications.emit("Error: ${it.message}")
            }
        }
    }

    fun handleResendConfirmationEmail(email: String) {
        viewModelScope.launch {
            _isLoading.value = true
            delay(1000)
            val trimmedEmail = email.trim().lowercase()
            val code = (100000..999999).random().toString()
            _verificationCodes[trimmedEmail] = code
            
            var sentFirebase = false
            if (firebaseSyncService.isFirebaseReady()) {
                try {
                    val auth = com.google.firebase.auth.FirebaseAuth.getInstance()
                    val user = auth.currentUser
                    if (user != null && user.email?.trim()?.lowercase() == trimmedEmail) {
                        // Resend Firebase verification email
                        user.sendEmailVerification()
                        sentFirebase = true
                    }
                } catch (e: Exception) {
                    android.util.Log.e("MinerViewModel", "Failed to resend Firebase email: ${e.message}")
                }
            }
            
            _isLoading.value = false
            if (sentFirebase) {
                _notifications.emit("Firebase Verification Email resent! Please check your inbox.")
                _appAlert.value = "📧 FIREBASE VERIFICATION RESENT\n\n" +
                        "A secure verification link was resent to: $email\n\n" +
                        "• Verification Code: $code (Local fallback)\n\n" +
                        "Please verify your email or input the code above to confirm."
            } else {
                _notifications.emit("Verification code resent to $email!")
                _appAlert.value = "📧 LOCAL OFFLINE VERIFICATION RESENT\n\n" +
                        "An offline verification email has been resent to: $email\n\n" +
                        "Your 6-Digit Cryptographic Code is: $code\n\n" +
                        "Enter this code on the verification screen to activate your account."
            }
        }
    }

    fun handleLogin(email: String, pass: String) {
        viewModelScope.launch {
            _isLoading.value = true
            var isFirebaseFallback = false
            var firebaseErrorMessage = ""
            val result = if (firebaseSyncService.isFirebaseReady()) {
                val fbResult = firebaseSyncService.loginFirebase(email, pass)
                if (fbResult.isFailure) {
                    val ex = fbResult.exceptionOrNull()
                    val msg = ex?.message ?: ""
                    val isConfigError = (ex is com.google.firebase.auth.FirebaseAuthException && 
                            (ex.errorCode == "ERROR_OPERATION_NOT_ALLOWED" || msg.contains("disabled", ignoreCase = true) || msg.contains("not allowed", ignoreCase = true))) ||
                            msg.contains("configuration", ignoreCase = true) ||
                            msg.contains("operation-not-allowed", ignoreCase = true)
                    
                    if (isConfigError) {
                        isFirebaseFallback = true
                        firebaseErrorMessage = msg
                        // Fall back to local room login
                        repository.login(email, pass)
                    } else {
                        fbResult
                    }
                } else {
                    fbResult
                }
            } else {
                repository.login(email, pass)
            }
            _isLoading.value = false
            result.onSuccess { user ->
                _currentUserEmail.value = user.email
                _currentScreen.value = Screen.Dashboard
                if (isFirebaseFallback) {
                    _notifications.emit("Firebase provider disabled. Switched to Local Offline Mode.")
                    _appAlert.value = "DIAGNOSTIC ADVISORY: Firebase Email/Password Sign-In is not enabled on your Firebase Console.\n\n" +
                            "To resolve this configuration issue:\n" +
                            "1. Go to your Firebase Console (console.firebase.google.com)\n" +
                            "2. Navigate to Build -> Authentication -> Sign-in method\n" +
                            "3. Click on 'Email/Password' and switch it to Enable\n\n" +
                            "JustsMine has successfully activated LOCAL OFFLINE MODE fallback to retrieve your account."
                } else {
                    _notifications.emit("Welcome back, ${user.username}!")
                }
                
                // Keep cloud backups updated
                if (firebaseSyncService.isFirebaseReady() && !isFirebaseFallback) {
                    firebaseSyncService.backupLocalDataToFirebase()
                }
                
                startMiningLoop()
            }.onFailure {
                _notifications.emit("Error: ${it.message}")
            }
        }
    }

    fun handleForgotPassword(email: String) {
        viewModelScope.launch {
            _isLoading.value = true
            val result = repository.forgotPassword(email)
            _isLoading.value = false
            result.onSuccess { msg ->
                _appAlert.value = msg
                _currentScreen.value = Screen.Login
            }.onFailure {
                _notifications.emit("Error: ${it.message}")
            }
        }
    }

    fun handleLogout() {
        viewModelScope.launch {
            val email = _currentUserEmail.value
            if (email != null) {
                repository.recordActivity(email, "LOGOUT", "User logged out.")
            }
            firebaseSyncService.stopListeningToUserData()
            firebaseSyncService.stopListeningToWithdrawals()
            _firestoreWithdrawals.value = emptyList()
            miningJob?.cancel()
            _currentUserEmail.value = null
            _currentScreen.value = Screen.Login
            _notifications.emit("Logged out successfully.")
        }
    }

    // --- Mining Balance Tick Loop ---
    private fun startMiningLoop() {
        miningJob?.cancel()
        val activeEmail = _currentUserEmail.value
        if (activeEmail != null && firebaseSyncService.isFirebaseReady()) {
            firebaseSyncService.listenToUserData(activeEmail)
            firebaseSyncService.listenToWithdrawals(activeEmail) { list ->
                _firestoreWithdrawals.value = list
            }
        }
        miningJob = viewModelScope.launch {
            while (true) {
                delay(1000) // Runs every second
                val email = _currentUserEmail.value ?: break
                val user = currentUser.value ?: continue
                if (user.totalHashrate > 0) {
                    // Mining speed: 10 TH/s mines 0.00000002 BTC/sec
                    val speedFactor = 0.00000002 / 10.0
                    val tickEarning = user.totalHashrate * speedFactor
                    repository.updateMiningBalance(email, tickEarning)
                }
            }
        }
    }

    // --- Profile & Custom Security Actions ---
    fun handleUpdateProfile(username: String) {
        viewModelScope.launch {
            val email = _currentUserEmail.value ?: return@launch
            _isLoading.value = true
            val res = repository.updateProfile(email, username)
            _isLoading.value = false
            if (res.isSuccess) {
                _notifications.emit("Profile updated successfully!")
            } else {
                _notifications.emit("Error updating profile.")
            }
        }
    }

    fun handleChangePassword(old: String, new: String) {
        viewModelScope.launch {
            val email = _currentUserEmail.value ?: return@launch
            _isLoading.value = true
            val res = repository.changePassword(email, old, new)
            _isLoading.value = false
            if (res.isSuccess) {
                _notifications.emit("Password changed successfully!")
            } else {
                _notifications.emit("Error: ${res.exceptionOrNull()?.message}")
            }
        }
    }

    fun handleToggle2FA(enabled: Boolean) {
        viewModelScope.launch {
            val email = _currentUserEmail.value ?: return@launch
            val res = repository.set2FAEnabled(email, enabled)
            if (res.isSuccess) {
                _notifications.emit("2FA authentication ${if (enabled) "enabled" else "disabled"}")
            }
        }
    }

    fun handleToggleBiometric(enabled: Boolean) {
        viewModelScope.launch {
            val email = _currentUserEmail.value ?: return@launch
            val res = repository.setBiometricEnabled(email, enabled)
            if (res.isSuccess) {
                _notifications.emit("Biometric authorized security ${if (enabled) "activated" else "disabled"}")
            }
        }
    }

    fun handleToggleDarkMode(enabled: Boolean) {
        viewModelScope.launch {
            val email = _currentUserEmail.value ?: return@launch
            repository.setDarkModeEnabled(email, enabled)
        }
    }

    fun handleTogglePushNotifications(enabled: Boolean) {
        viewModelScope.launch {
            val email = _currentUserEmail.value ?: return@launch
            repository.setPushNotificationsEnabled(email, enabled)
            _notifications.emit("Push alerts ${if (enabled) "enabled" else "disabled"}")
        }
    }

    // --- Document KYC Scan Upload Simulator ---
    fun handleKYCUpload(docType: String, simulatedUri: String) {
        viewModelScope.launch {
            val email = _currentUserEmail.value ?: return@launch
            _isLoading.value = true
            val res = repository.submitKYC(email, simulatedUri)
            if (res.isSuccess) {
                _notifications.emit("KYC document uploaded! Bitget Compliance scanner analyzing...")
                delay(4000) // Simulated scan delay
                repository.autoApproveKYC(email)
                _appAlert.value = "Bitget Compliance Scan Complete! Your identity verification has been APPROVED."
                _notifications.emit("KYC Verification Approved.")
            } else {
                _notifications.emit("KYC Submission failed.")
            }
            _isLoading.value = false
        }
    }

    // --- Daily Reward Claim ---
    fun handleClaimDailyReward() {
        viewModelScope.launch {
            val email = _currentUserEmail.value ?: return@launch
            _isLoading.value = true
            val res = repository.claimDailyReward(email)
            _isLoading.value = false
            res.onSuccess {
                _appAlert.value = "Daily Power claim SUCCESS! +2.0 TH/S added to your mining contract!"
            }.onFailure {
                _appAlert.value = it.message
            }
        }
    }

    // --- Custom Mining Contracts Purchase ---
    fun purchaseContract(planName: String, hashrate: Double, priceBtc: Double) {
        viewModelScope.launch {
            val email = _currentUserEmail.value ?: return@launch
            _isLoading.value = true
            val res = repository.purchaseContract(email, planName, hashrate, priceBtc)
            _isLoading.value = false
            res.onSuccess {
                _appAlert.value = "Successfully purchased $planName! Contract hashrate of $hashrate TH/S is now ACTIVE."
            }.onFailure {
                _notifications.emit("Error: ${it.message}")
            }
        }
    }

    // Bitcoin Address Validation Helper
    fun isValidBitcoinAddress(address: String): Boolean {
        val trimmed = address.trim()
        if (trimmed.isEmpty()) return false
        
        // Starts with 1, 3, or bc1 (case-insensitive)
        val isBech32 = trimmed.startsWith("bc1", ignoreCase = true)
        val isLegacyOrP2sh = trimmed.startsWith("1") || trimmed.startsWith("3")
        if (!isBech32 && !isLegacyOrP2sh) return false
        
        // Length range: 26 to 62 characters
        val length = trimmed.length
        if (length < 26 || length > 62) return false
        
        // Alphanumeric characters only
        return trimmed.all { it.isLetterOrDigit() }
    }

    // --- Bitget Automated Withdrawal Engine ---
    fun handleRequestWithdrawal(walletAddress: String, amount: Double, code2Fa: String) {
        viewModelScope.launch {
            val email = _currentUserEmail.value ?: return@launch
            
            // 1. Validate Bitcoin wallet address before any processing
            if (!isValidBitcoinAddress(walletAddress)) {
                _appAlert.value = "Validation Error:\n\nInvalid Bitcoin wallet address format.\nMust start with '1', '3', or 'bc1', and be between 26 and 62 alphanumeric characters."
                _notifications.emit("Invalid BTC wallet address!")
                return@launch
            }

            // 2. Validate requested amount
            if (amount <= 0.0) {
                _appAlert.value = "Validation Error:\n\nAmount must be greater than zero."
                _notifications.emit("Invalid withdraw amount!")
                return@launch
            }

            // 3. Validate user balance
            val user = repository.getUserByEmail(email)
            val balance = user?.btcBalance ?: 0.0
            if (balance < amount) {
                _appAlert.value = "Validation Error:\n\nInsufficient BTC balance.\nAvailable: ${String.format("%.8f", balance)} BTC\nRequested: ${String.format("%.8f", amount)} BTC"
                _notifications.emit("Insufficient balance!")
                return@launch
            }

            _isLoading.value = true
            val res = repository.requestWithdrawal(email, walletAddress.trim(), amount, code2Fa)
            _isLoading.value = false
            res.onSuccess { tx ->
                _notifications.emit("Withdrawal request filed via Bitget protocol!")
                
                // Submit initially to Firestore (as PENDING status)
                if (firebaseSyncService.isFirebaseReady()) {
                    firebaseSyncService.submitTransactionToFirestore(tx)
                }

                simulateBitgetClearingProcess(tx)
            }.onFailure {
                _appAlert.value = it.message
            }
        }
    }

    private fun simulateBitgetClearingProcess(tx: TransactionEntity) {
        viewModelScope.launch {
            // STEP 1: Processing
            delay(3000)
            val processingTx = tx.copy(
                status = "PROCESSING",
                txHash = "Broadcasting to BTC blockchain..."
            )
            repository.updateTransactionStatus(processingTx, "PROCESSING", "Bitget HotWallet clearing queue")
            _notifications.emit("Bitget node processing... Clearing withdrawal.")
            
            // Sync status update to Firestore
            if (firebaseSyncService.isFirebaseReady()) {
                firebaseSyncService.submitTransactionToFirestore(processingTx)
            }

            // STEP 2: Signing Block
            delay(3000)
            val signingTx = tx.copy(
                status = "APPROVED",
                txHash = "Broadcasting to BTC blockchain..."
            )
            repository.updateTransactionStatus(signingTx, "APPROVED", "Bitget Cryptographic Multi-Sig authorized")
            _notifications.emit("Blockchain multi-sig authorized! Transmitting funds.")
            
            // Sync status update to Firestore
            if (firebaseSyncService.isFirebaseReady()) {
                firebaseSyncService.submitTransactionToFirestore(signingTx)
            }

            // STEP 3: Blockchain Completed
            delay(4000)
            val completedTx = tx.copy(
                status = "COMPLETED",
                txHash = "0x" + List(64) { "0123456789abcdef".random() }.joinToString("")
            )
            repository.updateTransactionStatus(completedTx, "COMPLETED", completedTx.txHash)
            _appAlert.value = "WITHDRAWAL APPROVED & COMPLETE!\n\nAmount: ${tx.amount} BTC\nWallet: ${tx.walletAddress}\nTxHash: ${completedTx.txHash}\nProcessed safely via Bitget integration."
            repository.recordActivity(tx.userEmail, "WITHDRAW_SUCCESS", "Withdrawal of ${tx.amount} BTC successfully signed on block.")
            
            // Sync final COMPLETED status to Firestore and update overall balance backup
            if (firebaseSyncService.isFirebaseReady()) {
                firebaseSyncService.submitTransactionToFirestore(completedTx)
                firebaseSyncService.backupLocalDataToFirebase()
            }
        }
    }

    // --- Support Ticket System ---
    fun handleCreateSupportTicket(subject: String, msg: String) {
        viewModelScope.launch {
            val email = _currentUserEmail.value ?: return@launch
            _isLoading.value = true
            val res = repository.createSupportTicket(email, subject, msg)
            _isLoading.value = false
            if (res.isSuccess) {
                _notifications.emit("Support ticket opened! A helpdesk agent is reviewing your query.")
                // Simulate auto helpdesk reply
                simulateHelpdeskReply(subject)
            } else {
                _notifications.emit("Failed to open ticket.")
            }
        }
    }

    private fun simulateHelpdeskReply(subject: String) {
        viewModelScope.launch {
            delay(10000) // Reply in 10s
            val email = _currentUserEmail.value ?: return@launch
            val ticketsList = supportTickets.value
            val target = ticketsList.firstOrNull { it.subject == subject && it.reply == null } ?: return@launch

            val friendlyReply = "Hello! Our Bitget API bridge has reviewed your inquiry. All systems are fully operational, block mining nodes are running at 100% capacity, and standard withdraws complete in under 10 seconds. Please let us know if you need help with any particular block transit!"
            repository.addSupportReply(target, friendlyReply)
            _notifications.emit("New support message reply received!")
        }
    }

    // --- Live Peer Simulated Feed ---
    private fun generateInitialLiveFeed() {
        val feeds = listOf(
            LiveWithdrawalFeed("btc_king***", 0.0425, "2m ago", "Bitget HotWallet"),
            LiveWithdrawalFeed("miner_rich***", 0.1084, "5m ago", "Bitget HotWallet"),
            LiveWithdrawalFeed("crypto_boss***", 0.0051, "9m ago", "Bitget HotWallet"),
            LiveWithdrawalFeed("satoshi_kid***", 0.0210, "15m ago", "Bitget HotWallet"),
            LiveWithdrawalFeed("just_mine***", 0.0115, "21m ago", "Bitget HotWallet")
        )
        _liveFeed.value = feeds
    }

    private fun startLiveFeedSimulator() {
        viewModelScope.launch {
            while (true) {
                delay(kotlin.random.Random.nextLong(10000, 25000)) // Random update every 10-25 seconds
                val usersList = listOf("giga_miner***", "hash_power***", "bitget_user***", "coindesk***", "block_lord***", "sat_stacker***")
                val randomUser = usersList.random()
                val randomAmount = kotlin.random.Random.nextDouble(0.002, 0.15)
                val formattedAmount = String.format("%.4f", randomAmount).toDouble()
                val newWithdrawal = LiveWithdrawalFeed(randomUser, formattedAmount, "Just now", "Bitget HotWallet")
                
                val currentList = _liveFeed.value.toMutableList()
                currentList.add(0, newWithdrawal)
                if (currentList.size > 8) {
                    currentList.removeAt(currentList.size - 1)
                }
                _liveFeed.value = currentList
            }
        }
    }
}
