package com.example.android_cast

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.android_cast.cast.CastForegroundService
import com.example.android_cast.cast.CastState
import com.example.android_cast.cast.CastViewModel
import com.example.android_cast.cast.CastViewModelFactory
import com.example.android_cast.cast.FakeReceiverDiscovery
import com.example.android_cast.cast.FakeScreenCaptureEngine
import com.example.android_cast.cast.Receiver
import com.example.android_cast.cast.ReceiverDiscovery
import com.example.android_cast.cast.ScreenCaptureEngine
import com.example.android_cast.ui.theme.Android_castTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Android_castTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    CastScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

/**
 * The MediaProjection permission flow takes a snapshot of the result Intent and
 * launches [CastForegroundService]. Held in composition state so a permission
 * denial is recoverable without restart.
 */
@Composable
private fun rememberCastLaunchLauncher(
    onResult: (granted: Boolean, resultCode: Int, data: Intent?) -> Unit,
): androidx.activity.result.ActivityResultLauncher<Intent> {
    return rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val granted = result.resultCode == Activity.RESULT_OK && result.data != null
        onResult(granted, result.resultCode, result.data)
    }
}

@Composable
fun CastScreen(
    discovery: ReceiverDiscovery = FakeReceiverDiscovery(),
    engine: ScreenCaptureEngine = FakeScreenCaptureEngine(),
    modifier: Modifier = Modifier,
) {
    val viewModel: CastViewModel = viewModel(
        factory = CastViewModelFactory(discovery, engine)
    )
    val receivers by viewModel.receivers.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var pendingReceiver by remember { mutableStateOf<Receiver?>(null) }
    var showExplanation by remember { mutableStateOf(false) }
    // Auto-default: emulator -> 10.0.2.2 (loopback alias for host Mac),
    // real device -> last-known Wi-Fi IP (edit if network changes).
    val isEmulator = remember {
        (android.os.Build.FINGERPRINT.contains("generic") ||
            android.os.Build.MODEL.contains("Emulator") ||
            android.os.Build.PRODUCT.contains("sdk"))
    }
    var manualIp by remember { mutableStateOf(if (isEmulator) "10.0.2.2" else "192.168.0.101") }

    val projectionLauncher = rememberCastLaunchLauncher { granted, resultCode, data ->
        val target = pendingReceiver
        if (granted && data != null && target != null) {
            viewModel.castTo(target)  // drive fake engine state for now
            CastForegroundService.startWith(context, target, resultCode, data)
        } else {
            viewModel.stop()
        }
        pendingReceiver = null
    }

    if (showExplanation) {
        AlertDialog(
            onDismissRequest = { showExplanation = false },
            title = { Text("MirrorCast needs screen capture") },
            text = {
                Text(
                    "MirrorCast captures your Android screen and streams it to the " +
                        "selected Mac on your local network. Grant the next permission " +
                        "to start casting.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showExplanation = false
                    val target = pendingReceiver ?: return@TextButton
                    val mgr = context.getSystemService<MediaProjectionManager>()
                        ?: return@TextButton
                    projectionLauncher.launch(mgr.createScreenCaptureIntent())
                }) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showExplanation = false
                    pendingReceiver = null
                }) { Text("Cancel") }
            },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BigCastButton(state = state, onStop = {
            CastForegroundService.stop(context)
            viewModel.stop()
        })

        Spacer(modifier = Modifier.height(8.dp))

        when (state) {
            is CastState.Connecting -> StateIndicator(text = "Connecting…")
            is CastState.Streaming -> StateIndicator(
                text = "Streaming to ${(state as CastState.Streaming).receiver.name} " +
                    "at ${(state as CastState.Streaming).fps} fps",
            )
            is CastState.Paused -> StateIndicator(text = "Paused")
            is CastState.Failed -> StateIndicator(
                text = "Failed: ${(state as CastState.Failed).message}",
                isError = true,
            )
            else -> {}
        }

        Text(
            text = "Receivers",
            style = MaterialTheme.typography.titleMedium,
        )

        ManualIpRow(
            ip = manualIp,
            onIpChange = { manualIp = it },
            enabled = state is CastState.Idle,
            onAdd = { ip ->
                val cleaned = ip.trim()
                if (cleaned.isNotEmpty()) {
                    (discovery as? FakeReceiverDiscovery)?.emit(
                        Receiver(
                            id = "manual-$cleaned",
                            name = "Manual: $cleaned",
                            host = cleaned,
                            port = 7236,
                        )
                    )
                }
            },
        )

        ReceiverList(
            receivers = receivers,
            enabled = state is CastState.Idle,
            onPick = { receiver ->
                pendingReceiver = receiver
                showExplanation = true
            },
        )
    }
}

@Composable
private fun ManualIpRow(
    ip: String,
    onIpChange: (String) -> Unit,
    enabled: Boolean,
    onAdd: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedTextField(
                value = ip,
                onValueChange = onIpChange,
                label = { Text("Mac IP") },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { onAdd(ip) },
                enabled = enabled && ip.isNotBlank(),
            ) { Text("Add") }
        }
        Spacer(modifier = Modifier.height(4.dp))
        // Quick toggles for the two most common IPs.
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            TextButton(
                onClick = { onIpChange("10.0.2.2") },
                enabled = enabled,
                modifier = Modifier.weight(1f),
            ) { Text("Emulator (10.0.2.2)", style = MaterialTheme.typography.labelSmall) }
            TextButton(
                onClick = { onIpChange("192.168.0.101") },
                enabled = enabled,
                modifier = Modifier.weight(1f),
            ) { Text("Home Wi-Fi", style = MaterialTheme.typography.labelSmall) }
        }
    }
}

@Composable
private fun BigCastButton(state: CastState, onStop: () -> Unit) {
    val isActive = state !is CastState.Idle
    Button(
        onClick = { if (isActive) onStop() },
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp),
        enabled = isActive,
    ) {
        Text(
            text = if (isActive) "Stop casting" else "Cast",
            style = MaterialTheme.typography.headlineSmall,
        )
    }
}

@Composable
private fun StateIndicator(text: String, isError: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (!isError) {
            CircularProgressIndicator(modifier = Modifier.height(20.dp))
            Spacer(modifier = Modifier.padding(end = 8.dp))
        }
        Text(
            text = text,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun ReceiverList(
    receivers: List<Receiver>,
    enabled: Boolean,
    onPick: (Receiver) -> Unit,
) {
    if (receivers.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Searching for receivers on your network…",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(receivers, key = { it.id }) { receiver ->
            ReceiverRow(receiver = receiver, enabled = enabled, onClick = { onPick(receiver) })
        }
    }
}

@Composable
private fun ReceiverRow(receiver: Receiver, enabled: Boolean, onClick: () -> Unit) {
    Card {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                Text(
                    text = receiver.name,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = "${receiver.host}:${receiver.port}",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (receiver.paired) {
                    Text(
                        text = "Last connected",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun CastScreenPreview() {
    Android_castTheme {
        CastScreen()
    }
}
