package com.rainbowcockroach.table.tableandroidclient.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.rainbowcockroach.table.tableandroidclient.ui.theme.Metrics
import com.rainbowcockroach.table.tableandroidclient.ui.theme.tableButtonElevation
import com.rainbowcockroach.table.tableandroidclient.ui.theme.tableColors

/** §11.2: the drawn circle is inset inside the square a thumb actually hits. */
private val DrawnCircle = 40.dp

/** §11.2: past this a capsule reads as a lozenge. */
private val PillMaxWidth = 260.dp

/**
 * `../UI.md` §4's trailing slot: at most two, and never closer than §11.2's 8.
 */
@Composable
fun TrailingSlot(content: @Composable () -> Unit) = Row(
    horizontalArrangement = Arrangement.spacedBy(Metrics.PairedControlGap),
    verticalAlignment = Alignment.CenterVertically,
    content = { content() },
)

/**
 * A row-level action: icon-only (§6), circular (§11.2), and raised on the 2px wall that
 * collapses under the press while the button travels the same distance (§11.3).
 */
@Composable
fun RaisedIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = tableColors.roseTint,
    content: Color = tableColors.roseText,
) = IconControl(icon, description, onClick, modifier, container, content, raised = true)

/**
 * The way out of a row. §11.3: the ghost button gets no cap — keeping it flush is the
 * hierarchy, and it is what makes the raised control beside it read as the thing to do.
 */
@Composable
fun GhostIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: Color = tableColors.ink2,
) = IconControl(icon, description, onClick, modifier, Color.Transparent, content, raised = false)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IconControl(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier,
    container: Color,
    content: Color,
    raised: Boolean,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val wall = if (raised && !pressed) Metrics.Wall else 0.dp
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(description) } },
        state = rememberTooltipState(),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .size(Metrics.ControlMin)
                .clickable(
                    interactionSource = interaction,
                    indication = ripple(bounded = false, radius = Metrics.ControlMin / 2),
                    role = Role.Button,
                    onClickLabel = description,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .offset(y = if (raised && pressed) Metrics.Wall else 0.dp)
                    .shadow(wall, CircleShape)
                    .background(container, CircleShape)
                    .size(DrawnCircle),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, description, Modifier.size(Metrics.Glyph), tint = content)
            }
        }
    }
}

/**
 * A control that appears once: §11.2's pill, on the same wall as every other button because
 * §11.3 forbids depth as a hierarchy channel among them.
 */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = tableColors.roseTint,
    content: Color = tableColors.roseText,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) = Button(
    onClick = onClick,
    modifier = modifier.widthIn(max = PillMaxWidth),
    enabled = enabled,
    shape = CircleShape,
    colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
    elevation = tableButtonElevation(),
    contentPadding = ButtonDefaults.ContentPadding,
) {
    if (icon != null) {
        Icon(icon, contentDescription = null, Modifier.size(Metrics.Glyph))
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(start = if (icon != null) 8.dp else 0.dp),
    )
}
