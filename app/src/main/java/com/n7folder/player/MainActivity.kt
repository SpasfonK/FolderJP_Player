package com.n7folder.player

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.n7folder.player.playback.PlayerViewModel
import com.n7folder.player.ui.N7App
import com.n7folder.player.ui.nav.NavViewModel
import com.n7folder.player.ui.scan.ScanViewModel
import com.n7folder.player.ui.theme.N7Theme

class MainActivity : ComponentActivity() {

    private val scanViewModel: ScanViewModel by viewModels()
    private val playerViewModel: PlayerViewModel by viewModels()
    private val navViewModel: NavViewModel by viewModels()

    // Sélecteur de dossier système (SAF) : aucun droit de stockage à demander dans le manifeste.
    private val addFolderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            scanViewModel.onFolderPicked(uri)
        }

    // Même sélecteur, mais pour rattacher une source existante à un stockage reconnecté.
    private val relinkFolderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            scanViewModel.onRelinkPicked(uri)
        }

    // Android 13+ : sans cette autorisation, la lecture fonctionne mais la notification n'apparaît pas.
    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _: Boolean -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Thème toujours sombre : icônes de barres système claires quel que soit le mode du système.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        requestNotificationPermissionIfNeeded()
        setContent {
            N7Theme {
                N7App(
                    scanVm = scanViewModel,
                    playerVm = playerViewModel,
                    navVm = navViewModel,
                    onAddFolder = { addFolderLauncher.launch(null) },
                    onRelink = { sourceId ->
                        scanViewModel.prepareRelink(sourceId)
                        relinkFolderLauncher.launch(null)
                    }
                )
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
