package com.postsaimanager.feature.parser

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
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
                    label = { Text("أدخل النص الجملة...") },
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
                        text = "Paste an Arabic sentence to test the ONNX HRM-Grid model.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
                is ParserUiState.Loading -> {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Executing ONNX Operations...")
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
                    AnimatedVisibility(visible = true) {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            item {
                                Text(
                                    text = "Syntax Grid Hierarchy",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                            }
                            
                            val nodes = state.tree.nodes.filter { !it.isRoot } // Hide dummy root
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

@Composable
fun SyntaxNodeCard(node: SyntaxNode) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                // Word Display (Right to Left naturally mapped via composition or simply string handling)
                Text(
                    text = node.word,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                
                Row {
                    Badge(containerColor = MaterialTheme.colorScheme.primary) { 
                        Text("Head: ${node.headId}") 
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Badge(containerColor = MaterialTheme.colorScheme.tertiary) { 
                        Text(node.getRelationName()) 
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Case: ${node.getCaseName()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
