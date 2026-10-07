package dev.dzsun.bookkeeping.feature.ask

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 问账：「这个月花了多少」「星巴克花了几次」。
 *
 * 界面只负责**收一句话、把答案摆出来**。判定、算术、措辞分别在
 * `LedgerQueryClient` / `LedgerQueryRunner` / `AskAnswerFormatter` 三处，
 * 这一层一行判断逻辑都没有——那正是它不该有的东西。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AskScreen(
    onBack: () -> Unit,
    viewModel: AskViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("问账") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "用一句话问账本。判定交给 AI 服务，算术在本机做——账目不出设备。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = state.question,
                onValueChange = viewModel::onQuestionChange,
                label = { Text("问什么") },
                placeholder = { Text("这个月花了多少") },
                minLines = 2,
                maxLines = 4,
                trailingIcon = {
                    IconButton(onClick = viewModel::onAsk, enabled = state.canAsk) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "提问")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            // 不知道该问什么的时候，给几句现成的。它们是界面文案，不是关键词表——
            // 点一下只是把字填进输入框，判定仍然全部走调度层。
            if (state.stage == AskStage.Idle) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "可以这样问",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    EXAMPLE_QUESTIONS.forEach { example ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                example,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.onQuestionChange(example) }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                            )
                        }
                    }
                }
            }

            Button(
                onClick = viewModel::onAsk,
                enabled = state.canAsk,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                if (state.isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("问一问")
                }
            }

            when (val stage = state.stage) {
                is AskStage.Answered -> AnswerCard(stage.answer)
                is AskStage.Unavailable -> UnavailableCard(stage)
                AskStage.Idle, AskStage.Interpreting -> Unit
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AnswerCard(answer: AskAnswer) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                answer.question,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                answer.headline,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            if (answer.detail.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                answer.detail.forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
            answer.footnote?.let { note ->
                Spacer(Modifier.height(12.dp))
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                )
            }
        }
    }
}

/**
 * 答不了的时候说清楚**为什么**。
 *
 * 这里的三种原因（服务端没起、契约缺能力、科目表没建好）用户一件也改不了，
 * 所以既不显示「重试」也不显示「失败」，只把白名单文案摆出来——
 * 服务端的内部原因（工具名、账本归属）不会走到界面。
 */
@Composable
private fun UnavailableCard(stage: AskStage.Unavailable) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    "这次答不了",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stage.reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val EXAMPLE_QUESTIONS = listOf(
    "这个月花了多少",
    "星巴克花了几次",
    "这个月的餐饮花了多少",
)
