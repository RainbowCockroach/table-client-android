package com.rainbowcockroach.table.tableandroidclient.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.rainbowcockroach.table.tableandroidclient.ui.theme.Metrics
import com.rainbowcockroach.table.tableandroidclient.ui.theme.tableColors

/**
 * `../UI.md` §3: tall enough to clear a 44 control *and* the 2px wall under it, because §11.2
 * makes *Take all* and *Clear* real buttons rather than text links.
 */
@Composable
fun SectionHeader(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    action: @Composable () -> Unit = {},
) = Row(
    modifier = modifier
        .fillMaxWidth()
        .heightIn(min = Metrics.SectionHeaderHeight)
        .padding(horizontal = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = color)
    Spacer(Modifier.weight(1f))
    action()
}

/** §8's centred states: what a region says when it has nothing to list. */
@Composable
fun CentredState(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    action: @Composable () -> Unit = {},
) = Column(
    modifier = modifier.fillMaxSize().padding(24.dp),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally,
) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = tableColors.ink)
    detail?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodyMedium,
            color = tableColors.ink2,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    Spacer(Modifier.height(16.dp))
    action()
}
