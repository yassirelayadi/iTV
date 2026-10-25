package fr.poptv.player.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.request.ImageRequest
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
    shape: Shape = RoundedCornerShape(20.dp),
    focusScale: Float = 1.06f,
    idleColor: Color = Surface1,
    focusedColor: Color = Surface3,
    focusRing: Boolean = false,
    ringColor: Color = Color(0xE6FFFFFF),
    onLongClick: (() -> Unit)? = null,
    idleBrush: Brush? = null,
    glow: Boolean = false,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        if (focused) focusScale else 1f,
        spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
        label = "focusScale",
    )
    val color by animateColorAsState(if (focused) focusedColor else idleColor, tween(220), label = "focusColor")
    val elevation by animateDpAsState(if (focused) 18.dp else if (glow) 12.dp else 0.dp, tween(220), label = "focusShadow")

    Box(
        modifier = modifier
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(elevation, shape, clip = false, ambientColor = Accent, spotColor = Accent)
            .clip(shape)
            .then(if (idleBrush != null && !focused) Modifier.background(idleBrush) else Modifier.background(color))
            .then(if (focusRing && focused) Modifier.border(2.dp, ringColor, shape) else Modifier)
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
    icon: ImageVector? = null,
) {
    FocusItem(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(50),
        focusScale = 1.08f,
        idleColor = if (selected) Accent else Color.Transparent,
        focusedColor = Color.White,
    ) { focused ->
        val color = when {
            focused -> Color.Black
            selected -> Color.White
            else -> TextSecondary
        }
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (icon != null) Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            Text(
                text,
                color = color,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Bouton rond avec une icône seule (roue crantée des réglages, par exemple). */
@Composable
fun RoundIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FocusItem(
        onClick = onClick,
        modifier = modifier.size(44.dp),
        shape = RoundedCornerShape(50),
        focusScale = 1.12f,
        idleColor = Color.Transparent,
        focusedColor = Color.White,
    ) { focused ->
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (focused) Color.Black else TextSecondary,
            modifier = Modifier.align(Alignment.Center).size(26.dp),
        )
    }
}

/** Bouton principal : pastille blanche, texte noir. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    FocusItem(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(50),
        focusScale = 1.08f,
        idleColor = Color(0xFFE8EEF5),
        focusedColor = Color.White,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (icon != null) Icon(icon, contentDescription = null, tint = Color.Black, modifier = Modifier.size(24.dp))
            Text(text, color = Color.Black, fontWeight = FontWeight.Bold)
        }
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
    leadingIcon: ImageVector? = null,
) {
    TextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text(placeholder) },
        leadingIcon = if (leadingIcon != null) {
            { Icon(leadingIcon, contentDescription = null, tint = TextSecondary) }
        } else {
            null
        },
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

/**
 * Requête d'image décodée à la taille d'affichage (et non en pleine résolution) : beaucoup moins
 * de mémoire et un défilement plus fluide sur les grands catalogues.
 */
@Composable
fun rememberImage(url: String?, width: Int, height: Int): ImageRequest {
    val context = LocalContext.current
    return remember(url, width, height) {
        ImageRequest.Builder(context)
            .data(url)
            .size(width, height)
            .crossfade(200)
            .build()
    }
}

/** Pastille rouge « EN DIRECT » des chaînes. */
@Composable
fun LiveBadge(modifier: Modifier = Modifier, fontSize: androidx.compose.ui.unit.TextUnit = 10.sp) {
    Row(
        modifier = modifier
            .background(Color(0xFFFF3B30), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(modifier = Modifier.size(5.dp).background(Color.White, CircleShape))
        Text(
            "EN DIRECT",
            color = Color.White,
            fontSize = fontSize,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.4.sp,
            maxLines = 1,
        )
    }
}
