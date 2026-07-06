package com.datacall.ccp

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.datacall.ccp.ui.ModeSelectScreen
import com.datacall.ccp.ui.diagnostics.DiagnosticsScreen
import com.datacall.ccp.ui.navigation.Routes
import com.datacall.ccp.ui.receiver.EndpointListScreen
import com.datacall.ccp.ui.receiver.ReceiverScreen
import com.datacall.ccp.ui.sender.SenderScreen
import com.datacall.ccp.ui.theme.DataCallTheme
import com.datacall.ccp.viewmodel.DiagnosticsViewModel
import com.datacall.ccp.viewmodel.EndpointViewModel
import com.datacall.ccp.viewmodel.ReceiverViewModel
import com.datacall.ccp.viewmodel.SenderViewModel
import com.datacall.ccp.viewmodel.ViewModelFactory

class MainActivity : ComponentActivity() {
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.CAMERA,
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.POST_NOTIFICATIONS
            )
        )
        enableEdgeToEdge()
        val app = application as DataCallApp
        val factory = ViewModelFactory(app)
        setContent {
            DataCallTheme {
                DataCallNavHost(factory, app.preferences)
            }
        }
    }
}

@Composable
private fun DataCallNavHost(factory: ViewModelFactory, preferences: com.datacall.ccp.data.AppPreferences) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.MODE_SELECT) {
        composable(Routes.MODE_SELECT) {
            ModeSelectScreen(
                preferences = preferences,
                onSender = { navController.navigate(Routes.SENDER) },
                onReceiver = { navController.navigate(Routes.RECEIVER) },
                onDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) }
            )
        }
        composable(Routes.SENDER) {
            val vm: SenderViewModel = viewModel(factory = factory)
            SenderScreen(viewModel = vm, onBack = { navController.popBackStack() })
        }
        composable(Routes.RECEIVER) {
            val vm: ReceiverViewModel = viewModel(factory = factory)
            ReceiverScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onManageEndpoints = { navController.navigate(Routes.ENDPOINTS) }
            )
        }
        composable(Routes.ENDPOINTS) {
            val vm: EndpointViewModel = viewModel(factory = factory)
            EndpointListScreen(viewModel = vm, onBack = { navController.popBackStack() })
        }
        composable(Routes.DIAGNOSTICS) {
            val vm: DiagnosticsViewModel = viewModel(factory = factory)
            DiagnosticsScreen(viewModel = vm, onBack = { navController.popBackStack() })
        }
    }
}
