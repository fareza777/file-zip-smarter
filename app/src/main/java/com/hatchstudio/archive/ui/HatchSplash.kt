package com.hatchstudio.archive.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hatchstudio.archive.R
import com.hatchstudio.archive.ui.theme.Bone
import com.hatchstudio.archive.ui.theme.Ink
import com.hatchstudio.archive.ui.theme.Lime
import com.hatchstudio.archive.ui.theme.Sea
import com.hatchstudio.archive.ui.theme.SpaceGrotesk
import com.hatchstudio.archive.ui.theme.SpaceMono
import kotlinx.coroutines.delay

@Composable
fun HatchLaunchSplash(onDone: () -> Unit) {
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        entered = true
        delay(1550)
        onDone()
    }
    val reveal by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(850, easing = FastOutSlowInEasing),
        label = "splash-reveal",
    )
    val line by animateFloatAsState(
        targetValue = if (entered) 1f else 0.05f,
        animationSpec = tween(1250, easing = FastOutSlowInEasing),
        label = "splash-line",
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(Bone),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Lime.copy(alpha = 0.34f), Color.Transparent),
                    center = Offset(size.width * 0.82f, size.height * 0.18f),
                    radius = size.width * 0.7f,
                ),
                radius = size.width * 0.7f,
                center = Offset(size.width * 0.82f, size.height * 0.18f),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Sea.copy(alpha = 0.22f), Color.Transparent),
                    center = Offset(size.width * 0.1f, size.height * 0.9f),
                    radius = size.width * 0.65f,
                ),
                radius = size.width * 0.65f,
                center = Offset(size.width * 0.1f, size.height * 0.9f),
            )
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 34.dp)
                .padding(top = 52.dp, bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "LOCAL · PRIVATE · FAST",
                modifier = Modifier.fillMaxWidth().alpha(reveal * 0.75f),
                fontFamily = SpaceMono,
                fontWeight = FontWeight.Bold,
                fontSize = 10.5.sp,
                letterSpacing = 2.2.sp,
                color = Ink.copy(alpha = 0.55f),
                textAlign = TextAlign.Start,
            )
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .size(142.dp)
                    .scale(0.82f + reveal * 0.18f)
                    .alpha(reveal)
                    .clip(CircleShape)
                    .background(Lime.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_splash_hatch),
                    contentDescription = "Hatch",
                    modifier = Modifier.size(112.dp),
                    contentScale = ContentScale.Fit,
                )
            }
            Spacer(Modifier.height(28.dp))
            Text(
                "HATCH",
                modifier = Modifier.alpha(reveal),
                fontFamily = SpaceMono,
                fontWeight = FontWeight.Bold,
                fontSize = 29.sp,
                letterSpacing = 8.sp,
                color = Ink,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Make room for what's inside.",
                modifier = Modifier.alpha(reveal * 0.8f),
                style = androidx.compose.material3.MaterialTheme.typography.bodyLarge.copy(
                    fontFamily = SpaceGrotesk,
                    fontSize = 16.sp,
                ),
                color = Ink.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .width(148.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Ink.copy(alpha = 0.08f)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(line)
                        .height(3.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Ink),
                )
            }
            Spacer(Modifier.height(13.dp))
            Text(
                "EXTRACT · RECLAIM · CREATE",
                modifier = Modifier.alpha(reveal * 0.55f),
                fontFamily = SpaceMono,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                letterSpacing = 1.8.sp,
                color = Ink.copy(alpha = 0.5f),
            )
        }
    }
}
