package com.arva.shining.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.arva.shining.R

/** Brand radii: 6 tags, 8 buttons and inputs, 12 cards and images. */
val RadiusTag = RoundedCornerShape(6.dp)
val RadiusControl = RoundedCornerShape(8.dp)
val RadiusCard = RoundedCornerShape(12.dp)

/** Header: leaf + product name on the left, icon actions on the right, 1px line below. */
@Composable
fun ArvaHeader(
    title: String,
    navigation: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val c = Arva.colors
    Column(Modifier.background(c.bg).windowInsetsPadding(WindowInsets.statusBars)) {
        Row(
            Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (navigation != null) {
                navigation()
            } else {
                Spacer(Modifier.width(12.dp))
                // ARVA leaf (mark-small, Moss) — swaps to the washi/mint file in dark theme via drawable-night.
                Image(painterResource(R.drawable.arva_mark_small), contentDescription = "ARVA", modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
            }
            Text(title, style = MaterialTheme.typography.titleLarge, color = c.text, modifier = Modifier.weight(1f))
            actions()
        }
        HorizontalDivider(thickness = 1.dp, color = c.line)
    }
}

@Composable
fun HeaderIcon(@DrawableRes icon: Int, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Icon(painterResource(icon), contentDescription = label, tint = Arva.colors.text2, modifier = Modifier.size(24.dp))
    }
}

/** Mono uppercase eyebrow in Moss: names what you see. Never for actions. */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = Arva.colors.moss, modifier = modifier)
}

/** Small grey group label (Plex Sans, --text-2). */
@Composable
fun GroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = Arva.colors.text2, modifier = modifier)
}

/** One per view: accent fill, on-accent label, 8 dp radius. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = Arva.colors
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        shape = RadiusControl,
        colors = ButtonDefaults.buttonColors(
            containerColor = c.accent, contentColor = c.onAccent,
            disabledContainerColor = c.sunken, disabledContentColor = c.text2,
        ),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 14.dp),
        elevation = null,
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

/** Transparent with a 1.5 dp --text border. */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = Arva.colors
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        shape = RadiusControl,
        border = BorderStroke(1.5.dp, if (enabled) c.text else c.line),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = c.text, disabledContentColor = c.text2),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 14.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

/** Accent text with a trailing arrow. */
@Composable
fun TextLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp), shape = RadiusControl) {
        Text("$text →", style = MaterialTheme.typography.bodyMedium, color = Arva.colors.accent)
    }
}

/** Surface fill, 1 dp line border, 12 dp radius, 24 dp padding, no shadow. */
@Composable
fun ArvaCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: PaddingValues = PaddingValues(24.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Arva.colors
    Column(
        modifier
            .fillMaxWidth()
            .clip(RadiusCard)
            .background(c.surface)
            .border(1.dp, c.line, RadiusCard)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

/** Segmented choice. Selected uses the brand "on" state: --accent-tint fill with --accent text. */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val c = Arva.colors
    Row(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RadiusControl)
            .background(c.sunken)
            .border(1.dp, c.line, RadiusControl)
            .padding(4.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (value, label) ->
            val on = value == selected
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (on) c.accentTint else androidx.compose.ui.graphics.Color.Transparent)
                    .selectable(selected = on, role = Role.RadioButton, onClick = { onSelect(value) })
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
                    color = if (on) c.accent else c.text2,
                )
            }
        }
    }
}

/** Brand input: surface fill, line border, 8 dp radius, accent on focus. */
@Composable
fun ArvaField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    isError: Boolean = false,
    supporting: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val c = Arva.colors
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        shape = RadiusControl,
        textStyle = if (mono) MaterialTheme.typography.labelSmall.copy(fontSize = MaterialTheme.typography.bodyMedium.fontSize)
        else MaterialTheme.typography.bodyMedium,
        supportingText = supporting?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = c.surface, unfocusedContainerColor = c.surface, errorContainerColor = c.surface,
            focusedBorderColor = c.accent, unfocusedBorderColor = c.line, errorBorderColor = c.error,
            focusedLabelColor = c.accent, unfocusedLabelColor = c.text2, errorLabelColor = c.error,
            focusedTextColor = c.text, unfocusedTextColor = c.text, cursorColor = c.accent,
            focusedSupportingTextColor = c.text2, unfocusedSupportingTextColor = c.text2, errorSupportingTextColor = c.error,
        ),
    )
}
