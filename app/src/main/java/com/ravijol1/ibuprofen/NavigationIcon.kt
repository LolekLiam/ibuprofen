package com.ravijol1.ibuprofen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
internal fun NavigationIcon(tab: AppTab) {
    val color = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.size(24.dp)) {
        val unit = size.width / 24f
        val stroke = Stroke(1.8f * unit)
        fun point(x: Float, y: Float) = Offset(x * unit, y * unit)
        fun line(x: Float, y: Float, endX: Float, endY: Float) =
            drawLine(color, point(x, y), point(endX, endY), 1.8f * unit)
        when (tab) {
            AppTab.Public -> {
                drawRoundRect(color, point(3f, 4f), Size(18f * unit, 17f * unit), CornerRadius(2f * unit), style = stroke)
                line(3f, 9f, 21f, 9f)
                line(8f, 2f, 8f, 6f); line(16f, 2f, 16f, 6f)
                for (x in listOf(8f, 16f)) for (y in listOf(13f, 17f)) drawCircle(color, unit, point(x, y))
            }
            AppTab.Child -> {
                drawCircle(color, 9f * unit, point(12f, 12f), style = stroke)
                line(12f, 6f, 12f, 12f); line(12f, 12f, 17f, 15f)
            }
            AppTab.Grades -> {
                drawRoundRect(color, point(5f, 3f), Size(14f * unit, 18f * unit), CornerRadius(2f * unit), style = stroke)
                line(8f, 8f, 16f, 8f); line(8f, 12f, 16f, 12f); line(8f, 16f, 13f, 16f)
            }
            AppTab.Login -> {
                drawCircle(color, 4f * unit, point(12f, 7f), style = stroke)
                drawArc(color, 180f, 180f, false, point(4f, 14f), Size(16f * unit, 14f * unit), style = stroke)
            }
            AppTab.Settings -> {
                for ((y, x) in listOf(6f to 8f, 12f to 16f, 18f to 8f)) {
                    line(3f, y, x - 3f, y); line(x + 3f, y, 21f, y)
                    drawCircle(color, 2.5f * unit, point(x, y), style = stroke)
                }
            }
        }
    }
}
