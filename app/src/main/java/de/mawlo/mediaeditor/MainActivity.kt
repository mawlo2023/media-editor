package de.mawlo.mediaeditor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.mawlo.mediaeditor.photo.PhotoEditorScreen
import de.mawlo.mediaeditor.ui.Accent
import de.mawlo.mediaeditor.ui.Accent2
import de.mawlo.mediaeditor.ui.Bg
import de.mawlo.mediaeditor.ui.StudioTheme
import de.mawlo.mediaeditor.ui.TextDim
import de.mawlo.mediaeditor.video.VideoEditorScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StudioTheme {
                var screen by rememberSaveable { mutableStateOf("home") }
                when (screen) {
                    "photo" -> PhotoEditorScreen(onBack = { screen = "home" })
                    "video" -> VideoEditorScreen(onBack = { screen = "home" })
                    else -> HomeScreen(onPhoto = { screen = "photo" }, onVideo = { screen = "video" })
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(onPhoto: () -> Unit, onVideo: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Bg).statusBarsPadding().navigationBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Media Studio", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Text("Fotos & Videos professionell bearbeiten – komplett offline.", color = TextDim, fontSize = 15.sp)
        Spacer(Modifier.height(40.dp))
        BigCard(
            Icons.Default.PhotoCamera, "Foto bearbeiten",
            "Filter · Hintergrund entfernen · Objekte wegradieren · Text",
            Brush.linearGradient(listOf(Accent, Color(0xFF5B3DF5))), onPhoto,
        )
        Spacer(Modifier.height(16.dp))
        BigCard(
            Icons.Default.Movie, "Video bearbeiten",
            "Schneiden · Filter · Musik · Text · TikTok-Format",
            Brush.linearGradient(listOf(Accent2, Color(0xFFFF8C42))), onVideo,
        )
    }
}

@Composable
private fun BigCard(icon: ImageVector, title: String, subtitle: String, brush: Brush, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(brush).clickable(onClick = onClick).padding(24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(44.dp))
            Spacer(Modifier.size(16.dp))
            Column {
                Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, color = Color(0xDDFFFFFF), fontSize = 13.sp)
            }
        }
    }
}
