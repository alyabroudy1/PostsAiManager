package com.postsaimanager.feature.parser

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.postsaimanager.core.designsystem.icon.PamIcons
import com.postsaimanager.core.model.SyntaxNode
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParserScreen(
    viewModel: ParserViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var inputText by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Arabic Syntax Parser") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Input Area
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    label = { Text("أدخل الجملة...") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() })
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        focusManager.clearFocus()
                        viewModel.parseArabicText(inputText)
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(imageVector = PamIcons.AiModel, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Analyze")
                }

                FilledTonalButton(
                    onClick = {
                        focusManager.clearFocus()
                        viewModel.playAudio(inputText)
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(imageVector = Icons.Default.PlayArrow, contentDescription = "Play Arabic TTS")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Play Audio")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // State Display
            when (val state = uiState) {
                is ParserUiState.Idle -> {
                    Text(
                        text = "Paste an Arabic sentence to analyze its iʻrāb (إعراب) and tashkeel (تشكيل).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
                is ParserUiState.Loading -> {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Analyzing with HRM-Grid model...")
                }
                is ParserUiState.Error -> {
                    Text(
                        text = state.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                }
                is ParserUiState.Success -> {
                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn() + slideInVertically()
                    ) {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            // Diacritized Sentence Banner
                            if (state.tree.diacritizedSentence.isNotBlank()) {
                                item {
                                    DiacritizedSentenceCard(state.tree.diacritizedSentence)
                                }
                            }

                            item {
                                Text(
                                    text = "تحليل نحوي — Syntax Analysis",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                                )
                            }

                            val nodes = state.tree.nodes.filter { !it.isRoot }
                            items(nodes) { node ->
                                SyntaxNodeCard(node)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Prominent card showing the fully diacritized (tashkeel) sentence.
 */
@Composable
fun DiacritizedSentenceCard(diacritizedSentence: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.Transparent
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.tertiaryContainer
                        )
                    )
                )
                .padding(20.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "التشكيل الكامل",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Text(
                        text = diacritizedSentence,
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 26.sp,
                            lineHeight = 42.sp,
                            textDirection = TextDirection.Rtl
                        ),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

/**
 * Card for a single syntax node with tashkeel, POS, case, and relation info.
 */
@Composable
fun SyntaxNodeCard(node: SyntaxNode) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Row 1: Diacritized word + bare word
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Diacritized form (primary display)
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Text(
                        text = node.getDisplayWord(),
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 22.sp,
                            lineHeight = 36.sp,
                            textDirection = TextDirection.Rtl
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Source indicator
                if (node.diacSource == "lexicon") {
                    Badge(
                        containerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f),
                        contentColor = MaterialTheme.colorScheme.tertiary
                    ) {
                        Text("✓ lexicon", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Row 2: POS + Case + Relation badges
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // POS badge
                if (node.posTag.isNotBlank()) {
                    Badge(containerColor = MaterialTheme.colorScheme.secondary) {
                        Text(
                            text = node.posTag,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondary
                        )
                    }
                }

                // Relation badge
                if (node.relation.isNotBlank() && node.relation != "_") {
                    Badge(containerColor = MaterialTheme.colorScheme.tertiary) {
                        Text(
                            text = node.getRelationName(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiary
                        )
                    }
                }

                // Head badge
                Badge(containerColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)) {
                    Text(
                        text = "Head: ${node.headId}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Row 3: Case display in Arabic
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "الإعراب: ${node.getCaseDisplayArabic()}",
                    style = MaterialTheme.typography.bodySmall.copy(
                        textDirection = TextDirection.Rtl
                    ),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )

                if (node.caseDiacritic.isNotBlank()) {
                    Text(
                        text = "| علامة: ـ${node.caseDiacritic}",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 14.sp,
                            textDirection = TextDirection.Rtl
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Row 4: Morphological features
            val featureChips = buildList {
                if (node.isDefinite) add("معرفة")
                else add("نكرة")
                if (node.gender == "fem") add("مؤنث") else add("مذكر")
                when (node.number) {
                    "dual" -> add("مثنى")
                    "plur" -> add("جمع")
                    else -> add("مفرد")
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                featureChips.forEach { chip ->
                    SuggestionChip(
                        onClick = {},
                        label = {
                            Text(
                                text = chip,
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        modifier = Modifier.height(24.dp)
                    )
                }
            }
        }
    }
}
