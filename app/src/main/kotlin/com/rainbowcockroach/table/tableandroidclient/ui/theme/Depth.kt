package com.rainbowcockroach.table.tableandroidclient.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * `../UI.md` §11.3: a 2px wall that collapses under the press, which travels the same distance.
 *
 * Nothing else in this file draws a positive shadow — §11's rule is that exactly one thing
 * rises, and it is the button.
 */
@Composable
fun tableButtonElevation(): ButtonElevation = ButtonDefaults.buttonElevation(
    defaultElevation = Metrics.Wall,
    pressedElevation = 0.dp,
    focusedElevation = Metrics.Wall,
    hoveredElevation = Metrics.Wall,
)

/**
 * §11.4: a region ground carved into the card — the light stops at the top 2px, never behind
 * the first row's text, and 6% is the floor the palette's 4.74:1 leaves room for.
 */
fun Modifier.well(color: Color, shape: Shape = RoundedCornerShape(Metrics.Corner)): Modifier =
    background(color, shape).drawWithContent {
        drawContent()
        val depth = Metrics.WellDepth.toPx()
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Black.copy(alpha = Metrics.WELL_ALPHA), Color.Transparent),
                startY = 0f,
                endY = depth,
            ),
            size = Size(size.width, depth),
        )
    }

/**
 * §11.4: a region boundary — the platform hairline with one pixel of light beneath it, so the
 * edge is cut rather than drawn. Row separators keep the plain hairline and never take this.
 */
fun Modifier.seam(line: Color, light: Color): Modifier = drawWithContent {
    drawContent()
    val hairline = 1.dp.toPx()
    drawRect(line, topLeft = Offset(0f, size.height - hairline * 2), size = Size(size.width, hairline))
    drawRect(light, topLeft = Offset(0f, size.height - hairline), size = Size(size.width, hairline))
}

/** §11.4: the same cut turned on its side, between the table's column and the rail. */
fun Modifier.columnSeam(line: Color, light: Color): Modifier = drawWithContent {
    drawContent()
    val hairline = 1.dp.toPx()
    drawRect(line, topLeft = Offset(size.width - hairline * 2, 0f), size = Size(hairline, size.height))
    drawRect(light, topLeft = Offset(size.width - hairline, 0f), size = Size(hairline, size.height))
}
