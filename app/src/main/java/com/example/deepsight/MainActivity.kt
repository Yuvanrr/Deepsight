package com.example.deepsight

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.deepsight.ui.theme.DeepsightTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        setContent {
            DeepsightTheme {
                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = "main") {
                    composable("main") {
                        MainScreen(
                            navController = navController,
                            onStartDetection = { startProjection() },
                            onStopDetection = { stopDetection() }
                        )
                    }
                    composable("app_selection") {
                        AppSelectionScreen(navController = navController)
                    }
                }
            }
        }

        requestNotificationPermission()
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            Log.d("DeepSight", "Projection permission granted")
            val serviceIntent = Intent(this, DetectionService::class.java).apply {
                putExtra(DetectionService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(DetectionService.EXTRA_DATA, result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } else {
            Log.d("DeepSight", "Projection permission denied or cancelled")
            Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startProjection() {
        if (!isAccessibilityServiceEnabled(this)) {
            Toast.makeText(this, "Please enable DeepSight Accessibility Service", Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (e: Exception) {
                Log.e("DeepSight", "Could not open accessibility settings", e)
            }
            return
        }
        // ALWAYS launch fresh MediaProjection intent first
        projectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun stopDetection() {
        stopService(Intent(this, DetectionService::class.java))
        OverlayService.instance?.updateStatus(OverlayService.DetectionStatus.IDLE)
        Log.d("DeepSight", "Detection stopped by user")
        Toast.makeText(this, "Detection Stopped", Toast.LENGTH_SHORT).show()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val service = "${context.packageName}/com.example.deepsight.OverlayService"
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return enabled.contains(service)
}

data class AppInfo(val packageName: String, val appName: String, val icon: Drawable)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(navController: NavController, onStartDetection: () -> Unit, onStopDetection: () -> Unit) {
    val context = LocalContext.current
    var isRunning by remember { mutableStateOf(DetectionService.isRunning) }
    var isAccessibilityEnabled by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isAccessibilityEnabled = isAccessibilityServiceEnabled(context)
                isRunning = DetectionService.isRunning
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("DeepSight") },
                actions = {
                    IconButton(onClick = { navController.navigate("app_selection") }) {
                        Icon(Icons.Default.Settings, contentDescription = "Select Apps")
                    }
                }
            )
        }
    ) { padding ->
        Surface(modifier = Modifier.fillMaxSize().padding(padding), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = "DeepSight Prototype", style = MaterialTheme.typography.headlineMedium)
                Spacer(modifier = Modifier.height(16.dp))
                
                Card(
                    modifier = Modifier.padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Text(
                        text = "Privacy Safeguard: This app processes all visual data on-device. No frames are stored, logged, or transmitted over the network.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(16.dp)
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))

                if (!isAccessibilityEnabled) {
                    Button(
                        onClick = {
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            context.startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color(0xFFFFA500)) // Orange
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Enable Accessibility Service")
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 16.dp)
                    ) {
                        Icon(
                            Icons.Default.CheckCircle, 
                            contentDescription = null, 
                            tint = androidx.compose.ui.graphics.Color(0xFF4CAF50) // Green
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Accessibility Service Active", color = androidx.compose.ui.graphics.Color(0xFF4CAF50))
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        if (isRunning) onStopDetection() else onStartDetection()
                        isRunning = !isRunning
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    enabled = isAccessibilityEnabled
                ) {
                    Text(if (isRunning) "Stop Detection" else "Start Detection")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSelectionScreen(navController: NavController) {
    val context = LocalContext.current
    val appPreferences = remember { AppPreferences(context) }
    var selectedApps by remember { mutableStateOf(appPreferences.getSelectedApps()) }
    var installedApps by remember { mutableStateOf(emptyList<AppInfo>()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 || pm.getLaunchIntentForPackage(it.packageName) != null }
                .map { AppInfo(it.packageName, pm.getApplicationLabel(it).toString(), pm.getApplicationIcon(it)) }
                .sortedBy { it.appName }
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Select Apps") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(installedApps) { app ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            bitmap = app.icon.toBitmap().asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = app.appName, style = MaterialTheme.typography.bodyLarge)
                            Text(text = app.packageName, style = MaterialTheme.typography.bodySmall)
                        }
                        Checkbox(
                            checked = selectedApps.contains(app.packageName),
                            onCheckedChange = { checked ->
                                val newSelected = if (checked) {
                                    selectedApps + app.packageName
                                } else {
                                    selectedApps - app.packageName
                                }
                                selectedApps = newSelected
                                appPreferences.setSelectedApps(newSelected)
                            }
                        )
                    }
                }
            }
        }
    }
}
