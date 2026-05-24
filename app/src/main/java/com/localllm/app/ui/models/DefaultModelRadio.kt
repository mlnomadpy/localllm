package com.localllm.app.ui.models

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Tap-to-select "default model" affordance used inside Models-tab rows. Renders
 * either a filled badge (when this row is already the default) or a
 * radio-button + label that triggers [onSelect] when tapped. Disabled when the
 * model isn't usable on this device (e.g. .litertlm not yet downloaded).
 */
@Composable
fun DefaultModelRadio(
    isDefault: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    if (isDefault) {
        // Filled "Default" pill — matches the AICore card's pill style so the
        // visual language stays consistent across the tab.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .background(
                    MaterialTheme.colorScheme.tertiaryContainer,
                    RoundedCornerShape(6.dp),
                )
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Star,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = "Default",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                fontWeight = FontWeight.SemiBold,
            )
        }
    } else {
        TextButton(
            onClick = onSelect,
            enabled = enabled,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 8.dp,
                vertical = 0.dp,
            ),
        ) {
            RadioButton(
                selected = false,
                onClick = null,
                enabled = enabled,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "Set as default",
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}
