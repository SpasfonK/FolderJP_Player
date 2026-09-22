package com.n7folder.player.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Icônes de transport dessinées au Canvas : nettes à toutes les tailles, sans dépendre d'une police
// ni de la bibliothèque material-icons-extended.

/** Lecture (triangle) ou pause (deux barres). */
@Composable
fun PlayPauseIcon(playing: Boolean, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        if (playing) {
            val barWidth = w * 0.2f
            val radius = CornerRadius(barWidth * 0.25f, barWidth * 0.25f)
            drawRoundRect(
                color = tint,
                topLeft = Offset(w * 0.24f, h * 0.2f),
                size = Size(barWidth, h * 0.6f),
                cornerRadius = radius
            )
            drawRoundRect(
                color = tint,
                topLeft = Offset(w * 0.56f, h * 0.2f),
                size = Size(barWidth, h * 0.6f),
                cornerRadius = radius
            )
        } else {
            val triangle = Path()
            triangle.moveTo(w * 0.3f, h * 0.18f)
            triangle.lineTo(w * 0.3f, h * 0.82f)
            triangle.lineTo(w * 0.82f, h * 0.5f)
            triangle.close()
            drawPath(triangle, tint)
        }
    }
}

/** Piste suivante (triangle + barre à droite) ou précédente (barre à gauche + triangle inversé). */
@Composable
fun SkipIcon(next: Boolean, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val triangle = Path()
        if (next) {
            triangle.moveTo(w * 0.2f, h * 0.22f)
            triangle.lineTo(w * 0.2f, h * 0.78f)
            triangle.lineTo(w * 0.66f, h * 0.5f)
        } else {
            triangle.moveTo(w * 0.8f, h * 0.22f)
            triangle.lineTo(w * 0.8f, h * 0.78f)
            triangle.lineTo(w * 0.34f, h * 0.5f)
        }
        triangle.close()
        drawPath(triangle, tint)
        val barLeft = if (next) w * 0.72f else w * 0.18f
        drawRect(color = tint, topLeft = Offset(barLeft, h * 0.22f), size = Size(w * 0.1f, h * 0.56f))
    }
}

/** Zone tactile ronde (44 dp par défaut, la taille minimale recommandée pour un doigt). */
@Composable
fun IconTap(onClick: () -> Unit, modifier: Modifier = Modifier, touchSize: Dp = 44.dp, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .size(touchSize)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}
