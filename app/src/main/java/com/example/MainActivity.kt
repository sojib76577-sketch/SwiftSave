package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.data.database.AppDatabase
import com.example.data.repository.DownloadRepository
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.VideoPlayerScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.DownloadViewModel
import com.example.ui.viewmodel.DownloadViewModelFactory
import java.net.URLDecoder
import java.net.URLEncoder

class MainActivity : ComponentActivity() {
    private val TAG = "MainActivity"
    private lateinit var viewModel: DownloadViewModel

    // Handle runtime request for POST_NOTIFICATIONS on Android 13+
    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.d(TAG, "Notification permission granted.")
        } else {
            Log.w(TAG, "Notification permission denied.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize Room Database, DAO and Repository
        val database = AppDatabase.getDatabase(applicationContext)
        val repository = DownloadRepository(database.downloadDao())
        
        // Create ViewModel
        viewModel = ViewModelProvider(
            this,
            DownloadViewModelFactory(repository)
        )[DownloadViewModel::class.java]

        // Handle incoming intent (Share Target URL extraction)
        handleShareIntent(intent)

        setContent {
            MyApplicationTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()

                    NavHost(
                        navController = navController,
                        startDestination = "home"
                    ) {
                        // Home Download Manager Screen
                        composable("home") {
                            HomeScreen(
                                viewModel = viewModel,
                                onNavigateToPlayer = { videoFilePath ->
                                    try {
                                        val encodedUri = URLEncoder.encode(videoFilePath, "UTF-8")
                                        navController.navigate("player/$encodedUri")
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error encoding navigation argument: ${e.message}")
                                        Toast.makeText(this@MainActivity, "Could not launch video player", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                        }

                        // Built-in Video Player Screen
                        composable(
                            route = "player/{videoUri}",
                            arguments = listOf(
                                navArgument("videoUri") { type = NavType.StringType }
                            )
                        ) { backStackEntry ->
                            val encodedUri = backStackEntry.arguments?.getString("videoUri") ?: ""
                            val decodedUri = try {
                                URLDecoder.decode(encodedUri, "UTF-8")
                            } catch (e: Exception) {
                                Log.e(TAG, "Error decoding navigation argument: ${e.message}")
                                encodedUri
                            }
                            
                            VideoPlayerScreen(
                                videoUriString = decodedUri,
                                onBackClick = {
                                    navController.popBackStack()
                                }
                            )
                        }
                    }

                    // Trigger request for notification permissions on startup (Android 13+)
                    LaunchedEffect(Unit) {
                        checkNotificationPermission()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    /**
     * Check if the intent was triggered by system Share Target SEND action,
     * extract the shared URL and automatically prompt validation/confirmation.
     */
    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        val type = intent.type

        if (Intent.ACTION_SEND == action && type != null) {
            if ("text/plain" == type) {
                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!sharedText.isNullOrBlank()) {
                    Log.d(TAG, "Captured shared URL: $sharedText")
                    // Paste shared URL into view model
                    viewModel.urlInput.value = sharedText.trim()
                    
                    // Immediately trigger URL validation and show confirmation popup
                    val isValid = viewModel.validateAndPrepareDownload(sharedText.trim())
                    if (isValid) {
                        Toast.makeText(this, "Shared link detected! Preparing video...", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Invalid shared video link.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    /**
     * Prompt for POST_NOTIFICATIONS permission on Android 13 (Tiramisu) or above.
     */
    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = Manifest.permission.POST_NOTIFICATIONS
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermissionLauncher.launch(permission)
            }
        }
    }
}
