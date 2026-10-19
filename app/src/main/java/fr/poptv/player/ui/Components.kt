package fr.poptv.player.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * Élément focusable pour la télécommande, dans l'esprit Apple TV : il s'agrandit
 * légèrement et s'éclaircit quand il a le focus. OK = clic, OK long = onLongClick.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FocusItem(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    focusScale: Float = 1.06f,
    idleColor: Color = Surface1,
    focusedColor: Color = Surface3,
    focusRing: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) focusScale else 1f, tween(160), label = "focusScale")
    val color by animateColorAsState(if (focused) focusedColor else idleColor, tween(160), label = "focusColor")

    Box(
        modifier = modifier
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(color)
            .then(if (focusRing && focused) Modifier.border(2.dp, Color.White, shape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        content(focused)
    }
}

/** Onglet / bouton discret : texte gris au repos, pastille blanche au focus. */
@Composable
fun Pill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    FocusItem(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(50),
        focusScale = 1.08f,
        idleColor = if (selected) Surface2 else Color.Transparent,
        focusedColor = Color.White,
    ) { focused ->
        Text(
            text,
            color = when {
                focused -> Color.Black
                selected -> TextPrimary
                else -> TextSecondary
            },
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

/** Bouton principal : pastille blanche, texte noir. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusItem(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(50),
        focusScale = 1.08f,
        idleColor = Color(0xFFD1D1D6),
        focusedColor = Color.White,
    ) {
        Text(
            text,
            color = Color.Black,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 36.dp, vertical = 12.dp),
        )
    }
}

/** Champ de saisie sans bordure, avec simple texte d'indication. */
@Composable
fun MinimalField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    imeAction: ImeAction = ImeAction.Next,
    shape: Shape = RoundedCornerShape(14.dp),
) {
    TextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text(placeholder) },
        singleLine = true,
        shape = shape,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Surface3,
            unfocusedContainerColor = Surface1,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            errorIndicatorColor = Color.Transparent,
            cursorColor = Color.White,
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedPlaceholderColor = TextSecondary,
            unfocusedPlaceholderColor = TextSecondary,
        ),
        modifier = modifier,
    )
}
