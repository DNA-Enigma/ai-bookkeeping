package dev.dzsun.bookkeeping.feature.discover

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dzsun.bookkeeping.core.designsystem.BrandBlue

/**
 * 发现 · 投资问诊：对话流，文字与截图都能发。
 * 不做荐股，只做决策脚手架与账单/截图解读。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(viewModel: DiscoverViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val context = LocalContext.current

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()?.let(viewModel::onImageAttached)
        }
    }

    LaunchedEffect(state.items.size) {
        if (state.items.isNotEmpty()) listState.animateScrollToItem(state.items.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("发现", fontWeight = FontWeight.Bold)
                        Text(
                            "投资问诊 · 只给思路，不给荐股",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.items, key = { it.id }) { item ->
                    Bubble(item)
                }
            }

            if (state.attachedPreview != null) {
                AttachedStrip(onClear = viewModel::clearImage)
            }

            Composer(
                draft = state.draft,
                canSend = state.canSend,
                onDraftChange = viewModel::onDraftChange,
                onSend = viewModel::send,
                onPickImage = {
                    pickImage.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
            )
        }
    }
}

@Composable
private fun Bubble(item: ChatItem) {
    val isUser = item.role == ChatRole.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        if (!isUser) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(BrandBlue, Color(0xFF7C5CFF)))),
                contentAlignment = Alignment.Center,
            ) {
                Text("AI", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(8.dp))
        }
        Card(
            modifier = Modifier.widthIn(max = 280.dp),
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp,
            ),
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) BrandBlue else MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (item.hasImage) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(72.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFFD8DEE9)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "📷 已附截图",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }
                Text(
                    item.text,
                    color = if (isUser) Color.White else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun AttachedStrip(onClear: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFFD8DEE9)),
            contentAlignment = Alignment.Center,
        ) { Text("图", fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(8.dp))
        Text("已选一张图，发送后随消息一起发出", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onClear) {
            Icon(Icons.Default.Close, contentDescription = "移除图片")
        }
    }
}

@Composable
private fun Composer(
    draft: String,
    canSend: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onPickImage: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPickImage) {
            Icon(Icons.Default.Image, contentDescription = "添加截图")
        }
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("问一句，或丢一张截图…") },
            maxLines = 4,
            shape = RoundedCornerShape(18.dp),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                imeAction = androidx.compose.ui.text.input.ImeAction.Send,
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onSend = { if (canSend) onSend() },
            ),
        )
        Spacer(Modifier.width(8.dp))
        IconButton(
            onClick = onSend,
            enabled = canSend,
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(
                    if (canSend) BrandBlue else BrandBlue.copy(alpha = 0.25f),
                ),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = "发送",
                tint = Color.White,
            )
        }
    }
}
