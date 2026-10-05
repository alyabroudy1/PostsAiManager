package com.postsaimanager.core.designsystem.component

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownPadding

/**
 * Renders [text] as GitHub-flavoured markdown — bold, italics, headings, lists, code fences,
 * links — using Material 3 styling, so assistant replies don't show raw `**bold**` syntax.
 *
 * Safe to call with partial/unbalanced markdown (an in-progress stream cut mid-`**` or
 * mid-fence): the underlying parser recovers rather than throwing, at worst rendering the
 * trailing marker literally until the next chunk closes it.
 *
 * @param color base text color; other markdown colors (headings, code, quotes) derive from
 * it so the block reads consistently against whatever surface it's drawn on (a colored chat
 * bubble, for instance, rather than always assuming the screen background).
 * @param style base text style; markdown block styles (paragraph, list items) derive their
 * size/line-height from it.
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
) {
    val resolvedColor = if (color.isSpecified) color
    else if (style.color.isSpecified) style.color
    else MaterialTheme.colorScheme.onSurface
    val resolvedStyle = style.copy(color = resolvedColor)

    Markdown(
        content = text,
        colors = markdownColor(
            text = resolvedColor,
            codeText = resolvedColor,
            linkText = resolvedColor,
        ),
        typography = markdownTypography(
            text = resolvedStyle,
            paragraph = resolvedStyle,
            ordered = resolvedStyle,
            bullet = resolvedStyle,
            list = resolvedStyle,
            code = resolvedStyle.copy(fontFamily = FontFamily.Monospace),
            inlineCode = resolvedStyle.copy(fontFamily = FontFamily.Monospace),
        ),
        padding = markdownPadding(block = 0.dp),
        modifier = modifier,
    )
}
