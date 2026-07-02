package com.example.data

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class FirebaseSyncService(private val context: Context, private val repository: MinerRepository) {

    private val tag = "FirebaseSyncService"

    // Helper to check if Firebase and Auth is fully configured & initialized
    fun isFirebaseReady(): Boolean {
        return try {
            FirebaseApp.getApps(context).isNotEmpty() && FirebaseAuth.getInstance() != null && FirebaseFirestore.getInstance() != null
        } catch (e: Exception) {
            false
        }
    }

    // Helper to check if user is signed in to Firebase
    fun isUserSignedIn(): Boolean {
        if (!isFirebaseReady()) return false
        return try {
            FirebaseAuth.getInstance().currentUser != null
        } catch (e: Exception) {
            false
        }
    }

    // Retrieve active authenticated user's email from Firebase
    fun getFirebaseUserEmail(): String? {
        if (!isFirebaseReady()) return null
        return try {
            FirebaseAuth.getInstance().currentUser?.email
        } catch (e: Exception) {
            null
        }
    }

    // Programmatic user sign-up via Firebase Auth & Firestore
    suspend fun signUpFirebase(email: String, passwordRaw: String, username: String, referredBy: String?): Result<UserEntity> {
        if (!isFirebaseReady()) {
            return Result.failure(Exception("Firebase is not initialized or configured on this node"))
        }

        return try {
            val auth = FirebaseAuth.getInstance()
            val firestore = FirebaseFirestore.getInstance()

            // 1. Create User in Firebase Authentication
            val authResult = auth.createUserWithEmailAndPassword(email.trim(), passwordRaw).await()
            val firebaseUser = authResult.user ?: throw Exception("Auth user creation failed")

            // Send Verification Email
            firebaseUser.sendEmailVerification().await()

            // 2. Prepare user state
            val localResult = repository.signUp(email, passwordRaw, username, referredBy)
            if (localResult.isFailure) {
                // If local room signup failed (e.g. exists locally), clean auth if possible or return exception
                throw localResult.exceptionOrNull() ?: Exception("Local database write failed")
            }
            val userEntity = localResult.getOrThrow()

            // 3. Write user profile to Firebase Firestore (Secure remote storage)
            val firestoreMap = hashMapOf(
                "email" to userEntity.email,
                "username" to userEntity.username,
                "isEmailConfirmed" to false,
                "btcBalance" to userEntity.btcBalance,
                "hasFreeContractClaimed" to userEntity.hasFreeContractClaimed,
                "twoFactorSecret" to userEntity.twoFactorSecret,
                "twoFactorEnabled" to userEntity.twoFactorEnabled,
                "biometricEnabled" to userEntity.biometricEnabled,
                "kycStatus" to userEntity.kycStatus,
                "referralCode" to userEntity.referralCode,
                "referredBy" to userEntity.referredBy,
                "totalHashrate" to userEntity.totalHashrate,
                "uid" to firebaseUser.uid
            )

            firestore.collection("users").document(firebaseUser.uid)
                .set(firestoreMap, SetOptions.merge())
                .await()

            Log.d(tag, "Successfully initialized user profile in Firebase Firestore")
            Result.success(userEntity)
        } catch (e: Exception) {
            Log.e(tag, "Firebase signup failed: ${e.message}")
            Result.failure(e)
        }
    }

    // Programmatic user sign-in via Firebase Auth & sync data down to local Room DB
    suspend fun loginFirebase(email: String, passwordRaw: String): Result<UserEntity> {
        if (!isFirebaseReady()) {
            return Result.failure(Exception("Firebase is not initialized on this node"))
        }

        return try {
            val auth = FirebaseAuth.getInstance()
            val firestore = FirebaseFirestore.getInstance()

            // 1. Authenticate with Firebase Auth
            val authResult = auth.signInWithEmailAndPassword(email.trim(), passwordRaw).await()
            val firebaseUser = authResult.user ?: throw Exception("Auth sign-in returned empty user instance")

            // Check if email verification is completed
            val isEmailVerified = firebaseUser.isEmailVerified

            // 2. Fetch User Profile from Firestore
            val doc = firestore.collection("users").document(firebaseUser.uid).get().await()
            if (!doc.exists()) {
                throw Exception("Profile data not found on secure Firebase cloud cluster")
            }

            // Sync down fields to compile UserEntity
            val remoteEmail = doc.getString("email") ?: email.trim().lowercase()
            val remoteUsername = doc.getString("username") ?: "MinerNode"
            val remoteBtcBalance = doc.getDouble("btcBalance") ?: 0.0
            val remoteTotalHashrate = doc.getDouble("totalHashrate") ?: 0.0
            val remoteKycStatus = doc.getString("kycStatus") ?: "UNVERIFIED"
            val remoteTwoFactorEnabled = doc.getBoolean("twoFactorEnabled") ?: false
            val remoteTwoFactorSecret = doc.getString("twoFactorSecret") ?: ""
            val remoteBiometricEnabled = doc.getBoolean("biometricEnabled") ?: false
            val remoteHasFreeClaimed = doc.getBoolean("hasFreeContractClaimed") ?: false
            val remoteReferralCode = doc.getString("referralCode") ?: "REFERRAL"
            val remoteReferredBy = doc.getString("referredBy")

            // Sync state with local Room Database
            val existingLocalUser = repository.getUserByEmail(remoteEmail)
            val syncUser = UserEntity(
                email = remoteEmail,
                passwordHash = existingLocalUser?.passwordHash ?: "", // Local shadow credentials
                username = remoteUsername,
                isEmailConfirmed = isEmailVerified || (existingLocalUser?.isEmailConfirmed ?: false),
                btcBalance = remoteBtcBalance,
                hasFreeContractClaimed = remoteHasFreeClaimed,
                twoFactorSecret = remoteTwoFactorSecret,
                twoFactorEnabled = remoteTwoFactorEnabled,
                biometricEnabled = remoteBiometricEnabled,
                kycStatus = remoteKycStatus,
                referralCode = remoteReferralCode,
                referredBy = remoteReferredBy,
                totalHashrate = remoteTotalHashrate
            )

            // Trigger local DB persistence
            // Insert user details locally to keep session live
            val localResult = repository.signUp(remoteEmail, passwordRaw, remoteUsername, remoteReferredBy)
            if (localResult.isFailure) {
                // User already exists, update local fields
                repository.updateProfile(remoteEmail, remoteUsername)
                if (isEmailVerified) {
                    repository.confirmEmail(remoteEmail)
                }
            }

            Log.d(tag, "Successfully authenticated and synced user session from Firebase cloud")
            Result.success(syncUser)
        } catch (e: Exception) {
            Log.e(tag, "Firebase sign-in failed: ${e.message}")
            Result.failure(e)
        }
    }

    // Dynamic backup/sync from Room to Firestore database for secure data persistence
    suspend fun backupLocalDataToFirebase(): Result<Boolean> {
        if (!isFirebaseReady() || !isUserSignedIn()) {
            return Result.failure(Exception("Cannot perform cloud sync: Firebase node offline or signed out"))
        }

        return try {
            val email = getFirebaseUserEmail() ?: throw Exception("Unresolved session email mapping")
            val firestore = FirebaseFirestore.getInstance()
            val uid = FirebaseAuth.getInstance().currentUser?.uid ?: throw Exception("Session UID null")

            val localUser = repository.getUserByEmail(email) ?: throw Exception("Local profile mapping unresolved")

            // Backup user state
            val firestoreMap = hashMapOf(
                "email" to localUser.email,
                "username" to localUser.username,
                "isEmailConfirmed" to localUser.isEmailConfirmed,
                "btcBalance" to localUser.btcBalance,
                "hasFreeContractClaimed" to localUser.hasFreeContractClaimed,
                "twoFactorSecret" to localUser.twoFactorSecret,
                "twoFactorEnabled" to localUser.twoFactorEnabled,
                "biometricEnabled" to localUser.biometricEnabled,
                "kycStatus" to localUser.kycStatus,
                "referralCode" to localUser.referralCode,
                "referredBy" to localUser.referredBy,
                "totalHashrate" to localUser.totalHashrate,
                "uid" to uid
            )

            firestore.collection("users").document(uid)
                .set(firestoreMap, SetOptions.merge())
                .await()

            // Backup contracts
            val activeContracts = repository.getActiveContracts(email)
            for (contract in activeContracts) {
                val contractMap = hashMapOf(
                    "userEmail" to contract.userEmail,
                    "planName" to contract.planName,
                    "hashrate" to contract.hashrate,
                    "purchaseTime" to contract.purchaseTime,
                    "expiryTime" to contract.expiryTime
                )
                firestore.collection("contracts").document("${email}_${contract.purchaseTime}")
                    .set(contractMap, SetOptions.merge())
                    .await()
            }

            Log.d(tag, "Cloud Sync: Safely mirrored user profile & active miners to Firebase")
            Result.success(true)
        } catch (e: Exception) {
            Log.e(tag, "Cloud Sync failed: ${e.message}")
            Result.failure(e)
        }
    }

    // Submit/update a transaction log in Firestore
    suspend fun submitTransactionToFirestore(tx: TransactionEntity): Result<Boolean> {
        if (!isFirebaseReady()) {
            return Result.failure(Exception("Firebase is not initialized or offline"))
        }
        return try {
            val firestore = FirebaseFirestore.getInstance()
            val txMap = hashMapOf(
                "userEmail" to tx.userEmail,
                "type" to tx.type,
                "amount" to tx.amount,
                "currency" to tx.currency,
                "walletAddress" to tx.walletAddress,
                "status" to tx.status,
                "timestamp" to tx.timestamp,
                "txHash" to tx.txHash
            )
            // Save transaction under 'transactions' collection using userEmail_timestamp as a predictable unique key
            val docId = "${tx.userEmail}_${tx.timestamp}"
            firestore.collection("transactions").document(docId)
                .set(txMap, SetOptions.merge())
                .await()
            Log.d(tag, "Successfully synced transaction log $docId to Firestore")

            // Specifically sync withdrawals to the dedicated 'withdrawals' collection
            if (tx.type == "WITHDRAWAL") {
                firestore.collection("withdrawals").document(docId)
                    .set(txMap, SetOptions.merge())
                    .await()
                Log.d(tag, "Successfully synced withdrawal log $docId to 'withdrawals' collection")
            }

            Result.success(true)
        } catch (e: Exception) {
            Log.e(tag, "Failed to sync transaction to Firestore: ${e.message}")
            Result.failure(e)
        }
    }

    private var userSnapshotListener: ListenerRegistration? = null

    // Register a real-time Firestore listener on the user's document to keep balance and state synced automatically
    fun listenToUserData(email: String) {
        if (!isFirebaseReady()) return
        
        try {
            // Cancel existing listener if any
            userSnapshotListener?.remove()
            
            val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
            val docRef = FirebaseFirestore.getInstance().collection("users").document(uid)
            
            userSnapshotListener = docRef.addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(tag, "Listen failed: ", error)
                    return@addSnapshotListener
                }
                
                if (snapshot != null && snapshot.exists()) {
                    val remoteEmail = snapshot.getString("email") ?: email
                    val remoteBtcBalance = snapshot.getDouble("btcBalance") ?: 0.0
                    val remoteTotalHashrate = snapshot.getDouble("totalHashrate") ?: 0.0
                    val remoteKycStatus = snapshot.getString("kycStatus") ?: "UNVERIFIED"
                    val remoteHasFreeClaimed = snapshot.getBoolean("hasFreeContractClaimed") ?: false
                    val remoteTwoFactorEnabled = snapshot.getBoolean("twoFactorEnabled") ?: false
                    val remoteTwoFactorSecret = snapshot.getString("twoFactorSecret") ?: ""
                    
                    Log.d(tag, "Real-time Update from Firestore: balance=$remoteBtcBalance, hashrate=$remoteTotalHashrate")
                    
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            repository.syncUserFieldsFromRemote(
                                email = remoteEmail,
                                btcBalance = remoteBtcBalance,
                                totalHashrate = remoteTotalHashrate,
                                kycStatus = remoteKycStatus,
                                hasFreeContractClaimed = remoteHasFreeClaimed,
                                twoFactorEnabled = remoteTwoFactorEnabled,
                                twoFactorSecret = remoteTwoFactorSecret
                            )
                        } catch (e: Exception) {
                            Log.e(tag, "Error saving snapshot update to local db: ${e.message}")
                        }
                    }
                }
            }
            Log.d(tag, "Successfully attached onSnapshot listener to user $uid ($email)")
        } catch (e: Exception) {
            Log.e(tag, "Error setting up onSnapshot listener: ${e.message}")
        }
    }
    
    fun stopListeningToUserData() {
        try {
            userSnapshotListener?.remove()
            userSnapshotListener = null
            Log.d(tag, "Removed onSnapshot listener.")
        } catch (e: Exception) {
            Log.e(tag, "Error stopping onSnapshot listener: ${e.message}")
        }
    }

    private var withdrawalsSnapshotListener: ListenerRegistration? = null

    // Register a real-time Firestore listener on the 'withdrawals' collection
    fun listenToWithdrawals(email: String, onUpdate: (List<FirestoreWithdrawal>) -> Unit) {
        if (!isFirebaseReady()) return
        
        try {
            withdrawalsSnapshotListener?.remove()
            
            val firestore = FirebaseFirestore.getInstance()
            withdrawalsSnapshotListener = firestore.collection("withdrawals")
                .whereEqualTo("userEmail", email)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.e(tag, "Withdrawals listen failed: ", error)
                        return@addSnapshotListener
                    }
                    
                    if (snapshot != null) {
                        val list = snapshot.documents.mapNotNull { doc ->
                            try {
                                FirestoreWithdrawal(
                                    docId = doc.id,
                                    userEmail = doc.getString("userEmail") ?: "",
                                    amount = doc.getDouble("amount") ?: 0.0,
                                    currency = doc.getString("currency") ?: "BTC",
                                    walletAddress = doc.getString("walletAddress") ?: "",
                                    status = doc.getString("status") ?: "",
                                    timestamp = doc.getLong("timestamp") ?: 0L,
                                    txHash = doc.getString("txHash") ?: ""
                                )
                            } catch (e: Exception) {
                                Log.e(tag, "Error parsing withdrawal doc: ${e.message}")
                                null
                            }
                        }.sortedByDescending { it.timestamp }
                        
                        onUpdate(list)
                    }
                }
            Log.d(tag, "Successfully attached onSnapshot listener to 'withdrawals' for $email")
        } catch (e: Exception) {
            Log.e(tag, "Error setting up withdrawals onSnapshot listener: ${e.message}")
        }
    }

    fun stopListeningToWithdrawals() {
        try {
            withdrawalsSnapshotListener?.remove()
            withdrawalsSnapshotListener = null
            Log.d(tag, "Removed withdrawals onSnapshot listener.")
        } catch (e: Exception) {
            Log.e(tag, "Error stopping withdrawals onSnapshot listener: ${e.message}")
        }
    }
}

// Data model representing a withdrawal from Firestore
data class FirestoreWithdrawal(
    val docId: String = "",
    val userEmail: String = "",
    val amount: Double = 0.0,
    val currency: String = "BTC",
    val walletAddress: String = "",
    val status: String = "",
    val timestamp: Long = 0,
    val txHash: String = ""
)

