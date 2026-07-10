package zone.ave.aicelite

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import zone.ave.aicelite.ui.AiceApp
import zone.ave.aicelite.ui.theme.AiceTheme

/** Scanning needs different permissions depending on the platform version. */
val blePermissions: Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        // Pre-12, a BLE scan is a location capability as far as Android is concerned.
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AiceTheme {
                var granted by remember { mutableStateOf(hasBlePermissions()) }
                val launcher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) { result -> granted = result.values.all { it } }

                AiceApp(
                    hasPermissions = granted,
                    onRequestPermissions = { launcher.launch(blePermissions) },
                )
            }
        }
    }

    private fun hasBlePermissions(): Boolean = blePermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }
}
