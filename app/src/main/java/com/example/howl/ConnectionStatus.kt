package com.example.howl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.howl.ui.theme.HowlTheme

@Composable
fun ConnectionStatusBar(
    status: ConnectionStatus,
    batteryLevel: Int,
    connectFunction: () -> Unit,
    modifier: Modifier = Modifier
) {
    // suppress the connection bar when audio output is selected
    val outputType = DataRepository.outputState.collectAsStateWithLifecycle().value.outputType
    if (outputType == OutputType.AUDIO_WAVELET || outputType == OutputType.AUDIO_CONTINUOUS)
        return
    Card (
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row {
                Icon(
                    painter = painterResource(R.drawable.coyote),
                    contentDescription = null,
                )
                Spacer(modifier = Modifier.width(8.dp))
                val connectionText = when (status) {
                    ConnectionStatus.Disconnected -> "Disconnected"
                    ConnectionStatus.Connecting -> "Connecting"
                    ConnectionStatus.Connected -> "Connected"
                }

                Text(
                    text = connectionText,
                    style = MaterialTheme.typography.bodyLarge
                )
            }

            when (status) {
                ConnectionStatus.Disconnected -> {
                    Button(
                        onClick = connectFunction,
                        modifier = Modifier.align(Alignment.CenterVertically)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.bluetooth),
                            contentDescription = "Connect",
                        )
                        Text(text = "Connect", modifier = Modifier.padding(start = 8.dp))
                    }
                }
                ConnectionStatus.Connecting -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                    )
                }
                ConnectionStatus.Connected -> {
                    Row {
                        Text(
                            text = "$batteryLevel%",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.align(Alignment.CenterVertically)
                        )
                        Icon(
                            painter = painterResource(R.drawable.battery),
                            contentDescription = "Battery level",
                        )
                    }
                }
            }
        }
    }
}

@Preview
@Composable
fun ConnectionStatusBarPreview() {
    HowlTheme {
        ConnectionStatusBar (
            status = ConnectionStatus.Disconnected,
            batteryLevel = 75,
            connectFunction = {},
            modifier = Modifier.fillMaxHeight()
        )
    }
}