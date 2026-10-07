package com.postsaimanager.core.designsystem.component

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection

/**
 * This style with each paragraph laid out by its own script: an Arabic answer is right-to-left and right-aligned, a German one
 * left-to-right, whatever language the app's UI is in, and a line that mixes Arabic with numbers or Latin words orders its runs by
 * the Unicode bidirectional rules of the line's dominant direction.
 *
 * [TextDirection.Content] reads the first strong character of the paragraph (the app's locale only decides a paragraph with none,
 * such as a line of digits), and [TextAlign.Start] is the start of THAT direction. Use it on every text that shows a letter's or a
 * model's words, never on the app's own labels, which follow the locale.
 */
fun TextStyle.byContentDirection(): TextStyle = copy(textDirection = TextDirection.Content, textAlign = TextAlign.Start)
