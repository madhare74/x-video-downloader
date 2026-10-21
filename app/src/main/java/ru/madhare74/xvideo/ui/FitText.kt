package ru.madhare74.xvideo.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.isSpecified

/**
 * Подпись строго в одну строку: если при текущем (в том числе крупном системном) шрифте
 * она не помещается, размер шрифта уменьшается, пока текст не влезет целиком.
 * Буквы никогда не переносятся по одной в столбик.
 */
@Composable
fun FitText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    minScale: Float = 0.45f,
) {
    var scale by remember(text, style) { mutableFloatStateOf(1f) }
    var ready by remember(text, style) { mutableStateOf(false) }
    val fontSize = if (style.fontSize.isSpecified) style.fontSize * scale else style.fontSize
    val lineHeight = if (style.lineHeight.isSpecified) style.lineHeight * scale else style.lineHeight
    Text(
        text = text,
        modifier = modifier.drawWithContent { if (ready) drawContent() },
        color = color,
        fontWeight = fontWeight,
        style = style.copy(fontSize = fontSize, lineHeight = lineHeight),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        onTextLayout = { layout ->
            if (layout.didOverflowWidth && scale > minScale) {
                scale = (scale * 0.9f).coerceAtLeast(minScale)
            } else {
                ready = true
            }
        },
    )
}
