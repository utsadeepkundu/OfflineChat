package com.offlinechat.app.ui.components

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.min

/**
 * A rounded chat bubble. The outer [tailWidth] on the speaker's side is always reserved, so bubbles in a run line up;
 * only the last bubble of a run draws the little tail.
 */
class BubbleShape(
    private val incoming: Boolean,
    private val tail: Boolean,
    private val tailWidth: Dp = TAIL_WIDTH,
    private val radius: Dp = 18.dp,
) : Shape {

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val tw = with(density) { tailWidth.toPx() }
        val r = min(with(density) { radius.toPx() }, size.height / 2f)
        val small = with(density) { 4.dp.toPx() }
        val left = if (incoming) tw else 0f
        val right = if (incoming) size.width else size.width - tw

        val body = Path().apply {
            addRoundRect(
                RoundRect(
                    left, 0f, right, size.height,
                    topLeftCornerRadius = CornerRadius(r),
                    topRightCornerRadius = CornerRadius(r),
                    bottomRightCornerRadius = CornerRadius(if (!incoming && tail) small else r),
                    bottomLeftCornerRadius = CornerRadius(if (incoming && tail) small else r),
                ),
            )
        }
        if (!tail) return Outline.Generic(body)

        val tailPath = Path().apply {
            val rise = tw * 1.7f
            if (incoming) {
                moveTo(left + 1f, size.height - rise)
                lineTo(0f, size.height)
                lineTo(left + small + 1f, size.height)
            } else {
                moveTo(right - 1f, size.height - rise)
                lineTo(size.width, size.height)
                lineTo(right - small - 1f, size.height)
            }
            close()
        }
        return Outline.Generic(Path.combine(PathOperation.Union, body, tailPath))
    }

    companion object {
        val TAIL_WIDTH = 8.dp
    }
}
