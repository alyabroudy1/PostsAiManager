package com.postsaimanager.feature.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.postsaimanager.core.designsystem.component.PamEmptyState
import com.postsaimanager.core.designsystem.component.PamTopAppBar
import com.postsaimanager.core.designsystem.icon.PamIcons

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    documentId: String?,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        topBar = {
            PamTopAppBar(
                title = "AI Chat",
                onNavigateBack = onNavigateBack,
            )
        },
        modifier = modifier,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            PamEmptyState(
                icon = PamIcons.AiChat,
                title = "AI Assistant",
                subtitle = "Download an AI model from Settings to start chatting.",
                actionLabel = "Go to AI Models",
                onAction = { /* TODO: Navigate to AI models */ },
            )
        }
    }
}
