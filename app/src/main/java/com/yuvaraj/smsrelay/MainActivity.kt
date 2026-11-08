package com.yuvaraj.smsrelay

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = colorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = colorScheme.background,
                    contentColor = colorScheme.onBackground
                ) {
                    AppScreen()
                }
            }
        }
    }
}

private enum class Mode {
    NONE,
    SENDER,
    RECEIVER
}

@Composable
private fun AppScreen() {
    val mode = remember { mutableStateOf(Mode.NONE) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "SMS Relay",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )

        ModePicker(mode)

        when (mode.value) {
            Mode.NONE -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFEEF2FF), RoundedCornerShape(12.dp))
                    .padding(16.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text("Choose a mode to begin.")
            }
            Mode.SENDER -> SenderScreen()
            Mode.RECEIVER -> ReceiverScreen()
        }
    }
}

@Composable
private fun ModePicker(selected: MutableState<Mode>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = { selected.value = Mode.SENDER },
            modifier = Modifier.weight(1f)
        ) {
            Text("Sender")
        }
        Button(
            onClick = { selected.value = Mode.RECEIVER },
            modifier = Modifier.weight(1f)
        ) {
            Text("Receiver")
        }
    }
}

@Composable
private fun SenderScreen() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFECFDF5), RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        Text("Sender mode is ready. Add your SMS forwarding logic here.")
    }
}

@Composable
private fun ReceiverScreen() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFF5F3FF), RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        Text("Receiver mode is ready. Add your message receiving flow here.")
    }
}
