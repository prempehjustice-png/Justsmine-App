package com.example

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.screens.MainUi
import com.example.ui.theme.JustsMineTheme
import com.example.ui.viewmodel.MinerViewModel
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Safely enable and initialize Firebase
        try {
            if (FirebaseApp.getApps(this).isEmpty()) {
                try {
                    // Try default initialization (requires google-services.json or compiled resources)
                    FirebaseApp.initializeApp(this)
                    Log.d("MainActivity", "Firebase initialized successfully using google-services.json")
                } catch (e: Exception) {
                    Log.w("MainActivity", "Default Firebase initialization failed, falling back to dynamic initialization: ${e.message}")
                    // Fallback configuration to ensure Firebase is enabled/initialized
                    val options = FirebaseOptions.Builder()
                        .setApiKey("AIzaSyAB98XNuBZrUERYmUd4IH3470sm16SnfSg")
                        .setApplicationId("1:815382022369:android:0da65b7f47ea03a08bcfa0")
                        .setProjectId("justsmine-eeedc")
                        .setGcmSenderId("815382022369")
                        .setStorageBucket("justsmine-eeedc.firebasestorage.app")
                        .build()
                    FirebaseApp.initializeApp(this, options)
                    Log.d("MainActivity", "Firebase initialized successfully with placeholder fallback options")
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Fatal error during Firebase initialization helper: ${e.message}")
        }

        enableEdgeToEdge()
        setContent {
            val viewModel: MinerViewModel = viewModel()
            val currentLoggedUser by viewModel.currentUser.collectAsStateWithLifecycle()
            val isDark = currentLoggedUser?.isDarkMode ?: true

            JustsMineTheme(darkTheme = isDark) {
                MainUi(viewModel = viewModel)
            }
        }
    }
}
