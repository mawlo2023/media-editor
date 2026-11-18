package de.mawlo.mediaeditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Accent = Color(0xFF9B6BFF)
val Accent2 = Color(0xFFFF4D8D)
val Bg = Color(0xFF0E0E12)
val Panel = Color(0xFF17171D)
val PanelLight = Color(0xFF24242C)
val TextDim = Color(0xFF9A9AA8)

@Composable
fun StudioTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            secondary = Accent2,
            background = Bg,
            surface = Panel,
            surfaceVariant = PanelLight,
            onPrimary = Color.White,
            onBackground = Color.White,
            onSurface = Color.White,
        ),
        content = content,
    )
}

/** Werkzeug-Schaltfläche in der unteren Leiste. */
@Composable
fun ToolButton(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = if (selected) Accent else Color.White, modifier = Modifier.size(24.dp))
        Text(label, fontSize = 11.sp, color = if (selected) Accent else TextDim)
    }
}

/** Auswahl-Chip. */
@Composable
fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Accent else PanelLight)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(label, fontSize = 13.sp, color = Color.White, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    onChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float> = -1f..1f,
    display: (Float) -> String = { "${(it * 100).toInt()}" },
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, fontSize = 13.sp, color = TextDim)
            Spacer(Modifier.weight(1f))
            Text(display(value), fontSize = 13.sp, color = Color.White)
        }
        Slider(
            value = value, onValueChange = onChange, valueRange = range,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Accent, inactiveTrackColor = PanelLight),
        )
    }
}

@Composable
fun ColorDot(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(color)
            .border(if (selected) 3.dp else 1.dp, if (selected) Accent else Color(0x55FFFFFF), CircleShape)
            .clickable(onClick = onClick),
    )
}

@Composable
fun BusyOverlay(message: String, progress: Float? = null) {
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)).clickable(enabled = true, onClick = {}), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            if (progress != null) CircularProgressIndicator(progress = { progress }, color = Accent, modifier = Modifier.size(56.dp))
            else CircularProgressIndicator(color = Accent, modifier = Modifier.size(56.dp))
            Spacer(Modifier.size(16.dp))
            Text(message, color = Color.White, fontSize = 16.sp)
            if (progress != null) Text("${(progress * 100).toInt()} %", color = TextDim, fontSize = 13.sp)
        }
    }
}

val SwatchColors = listOf(
    0xFFFFFFFF, 0xFF000000, 0xFFFF4D8D, 0xFF9B6BFF, 0xFF3DA9FC, 0xFF2EC4B6,
    0xFFFFD166, 0xFFFF8C42, 0xFFEF476F, 0xFF06D6A0, 0xFFF1E3D3, 0xFF264653,
).map { it.toInt() }

@Composable
fun HSpace(w: Int) = Spacer(Modifier.width(w.dp))
