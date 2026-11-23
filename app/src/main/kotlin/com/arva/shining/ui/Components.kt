package com.arva.shining.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.arva.shining.R

/** Brand radii: 6 tags, 8 buttons and inputs, 12 cards and images. */
val RadiusTag = RoundedCornerShape(6.dp)
val RadiusControl = RoundedCornerShape(8.dp)
val RadiusCard = RoundedCornerShape(12.dp)

/** The brand focus ring: 2 dp Kikyō, 2 dp off the element. Goes before `clip` and `clickable`. */
fun Modifier.arvaFocus(shape: Shape = RadiusControl): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val ring = Arva.colors.kikyo
    onFocusChanged { focused = it.hasFocus }.drawWithContent {
        drawContent()
        if (focused) {
            val stroke = 2.dp.toPx()
            val gap = 2.dp.toPx() + stroke / 2
            val outline = shape.createOutline(Size(size.width + gap * 2, size.height + gap * 2), layoutDirection, this)
            translate(-gap, -gap) { drawOutline(outline, ring, style = Stroke(stroke)) }
        }
    }
}

/**
 * Header. The home screen shows the Kashiwa leaf with the app's name beside it, as the ar-va.com
 * subsites do; inner screens show a back arrow and a Brygada title.
 */
@Composable
fun ArvaHeader(
    title: String,
    navigation: (@Composable () -> Unit)? = null,
    // On scrolling screens the bar sits on its own surface with a divider; on the non-scrolling main
    // screen it blends into the page (transparent, no line) so everything reads as one surface.
    divider: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val c = Arva.colors
    Column(
        Modifier
            .background(if (divider) c.bg else Color.Transparent)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(
            Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (navigation != null) {
                navigation()
                Spacer(Modifier.width(4.dp))
                Text(title, style = MaterialTheme.typography.titleLarge, color = c.text, modifier = Modifier.weight(1f))
            } else {
                Spacer(Modifier.width(12.dp))
                // ARVA leaf (mark-small, Kashiwa): the ink or washi file by theme, via drawable-night.
                Image(painterResource(R.drawable.arva_mark_small), contentDescription = "ARVA", modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = MaterialTheme.typography.bodyLarge.fontSize),
                    color = c.kashiwa,
                    modifier = Modifier.weight(1f),
                )
            }
            actions()
        }
        if (divider) HorizontalDivider(thickness = 1.dp, color = c.line)
    }
}

@Composable
fun HeaderIcon(@DrawableRes icon: Int, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.arvaFocus().size(48.dp)) {
        Icon(painterResource(icon), contentDescription = label, tint = Arva.colors.text2, modifier = Modifier.size(24.dp))
    }
}

/** Names a group of cards: Plex Sans 500, sentence case, grey. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = Arva.colors.text2, modifier = modifier)
}

/** Small grey group label (Plex Sans, --text-2). */
@Composable
fun GroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = Arva.colors.text2, modifier = modifier)
}

/** Bengara's one form: a small label on its tint. At most one per view. */
@Composable
fun NoticeTag(text: String, modifier: Modifier = Modifier) {
    val c = Arva.colors
    Text(
        text,
        style = MaterialTheme.typography.titleSmall.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
        color = c.bengara,
        modifier = modifier.clip(RadiusTag).background(c.bengaraTint).padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** One per view: Kikyō fill, on-Kikyō label, 8 dp radius; the deeper Kikyō while pressed. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = Arva.colors
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.arvaFocus().heightIn(min = 48.dp),
        shape = RadiusControl,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (pressed) c.kikyoHover else c.kikyo, contentColor = c.onKikyo,
            disabledContainerColor = c.sunken, disabledContentColor = c.text2,
        ),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 14.dp),
        elevation = null,
        interactionSource = source,
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

/** Transparent with a 1.5 dp --text border; inverts to a --text fill while pressed. */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = Arva.colors
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.arvaFocus().heightIn(min = 48.dp),
        shape = RadiusControl,
        border = BorderStroke(1.5.dp, if (enabled) c.text else c.line),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (pressed) c.text else Color.Transparent,
            contentColor = if (pressed) c.bg else c.text,
            disabledContentColor = c.text2,
        ),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 14.dp),
        interactionSource = source,
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

/** Kikyō text button. No trailing arrow: the brand keeps arrows for real directions. */
@Composable
fun TextLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onClick, modifier = modifier.arvaFocus().heightIn(min = 44.dp), shape = RadiusControl) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Arva.colors.kikyo)
    }
}

/** A link in running text or a footer: Kikyō, underlined. */
@Composable
fun BodyLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.arvaFocus(RadiusTag).clip(RadiusTag).heightIn(min = 44.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall.copy(textDecoration = TextDecoration.Underline),
            color = Arva.colors.kikyo,
        )
    }
}

/** Surface fill, 1 dp line border, 12 dp radius, 24 dp padding, no shadow. Pressed: --text-2 border. */
@Composable
fun ArvaCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: PaddingValues = PaddingValues(24.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Arva.colors
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Column(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.arvaFocus(RadiusCard) else Modifier)
            .clip(RadiusCard)
            .background(c.surface)
            .border(1.dp, if (pressed) c.text2 else c.line, RadiusCard)
            .then(
                if (onClick != null) Modifier.clickable(interactionSource = source, indication = LocalIndication.current, onClick = onClick)
                else Modifier
            )
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

/** Segmented choice. Selected uses the brand "on" state: Kikyō tint with its text colour. */
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
                    .arvaFocus(RadiusTag)
                    .clip(RadiusTag)
                    .background(if (on) c.kikyoTint else Color.Transparent)
                    .selectable(selected = on, role = Role.RadioButton, onClick = { onSelect(value) })
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.titleSmall, color = if (on) c.onTint else c.text2)
            }
        }
    }
}

/**
 * Brand input: a label above, surface fill, 1.5 dp line border, 8 dp radius. Focus turns the border
 * Kikyō; an invalid field gets an error border with the message underneath.
 */
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
    password: Boolean = false,
) {
    val c = Arva.colors
    var reveal by remember { mutableStateOf(false) }
    val masked = password && !reveal
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val border = when {
        isError -> c.error
        focused -> c.kikyo
        else -> c.line
    }
    val base = if (mono || masked) MaterialTheme.typography.labelSmall.copy(fontSize = MaterialTheme.typography.bodyMedium.fontSize)
    else MaterialTheme.typography.bodyMedium
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = if (isError) c.error else c.text2)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
            textStyle = base.copy(color = c.text),
            singleLine = true,
            cursorBrush = SolidColor(c.kikyo),
            interactionSource = source,
            visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboardType),
            decorationBox = { field ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clip(RadiusControl)
                        .background(c.surface)
                        .border(1.5.dp, border, RadiusControl)
                        .padding(start = 12.dp, end = if (password) 0.dp else 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) { field() }
                    if (password) {
                        IconButton(onClick = { reveal = !reveal }, modifier = Modifier.arvaFocus().size(48.dp)) {
                            Icon(
                                painterResource(if (reveal) R.drawable.ic_eye_off else R.drawable.ic_eye),
                                contentDescription = if (reveal) "Hide password" else "Show password",
                                tint = c.text2,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            },
        )
        supporting?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                color = if (isError) c.error else c.text2,
            )
        }
    }
}
