package io.github.tieo.arbay

import android.Manifest
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
actual fun rememberCityDetector(onCity: (String?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            scope.launch { onCity(resolveCity(context)) }
        } else {
            onCity(null)
        }
    }

    return {
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            scope.launch { onCity(resolveCity(context)) }
        } else {
            permissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }
}

@Composable
actual fun rememberCoordDetector(onCoords: (Double?, Double?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) scope.launch { resolveCoords(context).let { onCoords(it?.first, it?.second) } }
        else onCoords(null, null)
    }

    return {
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) scope.launch { resolveCoords(context).let { onCoords(it?.first, it?.second) } }
        else permissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
    }
}

private suspend fun resolveCoords(context: android.content.Context): Pair<Double, Double>? =
    withContext(Dispatchers.IO) {
        try {
            val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE) as LocationManager
            listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
                .firstNotNullOfOrNull { provider ->
                    try { lm.getLastKnownLocation(provider) } catch (_: SecurityException) { null }
                }
                ?.let { it.latitude to it.longitude }
        } catch (_: Exception) {
            null
        }
    }

private suspend fun resolveCity(context: android.content.Context): String? =
    withContext(Dispatchers.IO) {
        try {
            val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE) as LocationManager
            val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            val location = providers.firstNotNullOfOrNull { provider ->
                try { lm.getLastKnownLocation(provider) } catch (_: SecurityException) { null }
            } ?: return@withContext null

            @Suppress("DEPRECATION")
            val addresses = Geocoder(context).getFromLocation(location.latitude, location.longitude, 1)
            addresses?.firstOrNull()?.locality
                ?: addresses?.firstOrNull()?.subAdminArea
                ?: addresses?.firstOrNull()?.adminArea
        } catch (_: Exception) {
            null
        }
    }
