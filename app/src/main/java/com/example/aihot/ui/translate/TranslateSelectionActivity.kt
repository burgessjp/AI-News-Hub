package com.example.aihot.ui.translate

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.aihot.data.ShortContentException
import com.example.aihot.data.TranslationConfigStore
import com.example.aihot.data.TranslationRepository
import com.example.aihot.ui.theme.AIHotTheme
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 系统选中菜单「译」的落地 Activity。
 *
 * 原理:在 manifest 声明 [Intent.ACTION_PROCESS_TEXT] 的 intent-filter,
 * 系统长按选中文字弹出的 ActionMode 会自动把本 Activity 的 label("译")作为菜单项。
 * 点击后系统通过 [Intent.EXTRA_PROCESS_TEXT] 把选中文本传进来。
 *
 * 职责单一:收文本 → 复用 [TranslationRepository] 翻译 → 底部 Sheet 展示 → 关闭。
 * 不持有任何业务状态,配置缺失用 Toast 提示,零侵入主流程导航。
 */
class TranslateSelectionActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 防御:无 EXTRA_PROCESS_TEXT 直接关(理论上不会被无参拉起)
        val text = intent.getStringExtra(Intent.EXTRA_PROCESS_TEXT)
        if (text.isNullOrBlank()) { finish(); return }

        val configStore = TranslationConfigStore(applicationContext)

        // 开关关闭时根本不构建 UI:系统菜单项无法运行时动态隐藏,
        // 这里在 setContent 前拦截——Toast 提示后直接关闭,连 Sheet 都不渲染。
        lifecycleScope.launch {
            val enabled = configStore.configFlow.first().enabled
            if (!enabled) {
                Toast.makeText(
                    this@TranslateSelectionActivity,
                    "翻译功能未开启",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
                return@launch
            }
            setContent {
                AIHotTheme {
                    TranslateSheet(
                        text = text,
                        onDismiss = { finish() }
                    )
                }
            }
        }
    }
}

/** UI 状态机:加载中 / 成功 / 错误(细分三类,语义对齐 HackerNewsViewModel.TranslationState)。 */
private sealed interface TranslateState {
    data object Loading : TranslateState
    data class Success(val translated: String) : TranslateState
    data class Error(val kind: ErrorKind, val message: String? = null) : TranslateState
}

private enum class ErrorKind {
    CONFIG_MISSING,   // 未配置翻译服务
    TOO_SHORT,        // 选中文本过短/无字母
    GENERIC           // 网络/HTTP/解析失败
}

/**
 * 翻译结果底部 Sheet。
 *
 * - [TranslationRepository] 与 [TranslationConfigStore] 进程内 new,
 *   与 [com.example.aihot.ui.HackerNewsViewModel] 同范式,直接复用缓存与并发锁。
 * - [LaunchedEffect] 以 text 为 key:同一段文本只翻译一次。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TranslateSheet(
    text: String,
    onDismiss: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // repository 进程级缓存依赖 cacheDir,remember 复用同一实例(缓存/锁才有效)。
    val repo = remember { TranslationRepository(context.applicationContext.cacheDir) }
    val configStore = remember { TranslationConfigStore(context.applicationContext) }

    var state by remember { mutableStateOf<TranslateState>(TranslateState.Loading) }
    var attempt by remember { mutableStateOf(0) }

    LaunchedEffect(text, attempt) {
        state = TranslateState.Loading
        val config = configStore.configFlow.first()
        if (!config.isReady) {
            state = TranslateState.Error(ErrorKind.CONFIG_MISSING)
            return@LaunchedEffect
        }
        runCatching { repo.translate(text, config).getOrThrow() }
            .onSuccess { state = TranslateState.Success(it) }
            .onFailure { t ->
                state = TranslateState.Error(
                    if (t is ShortContentException) ErrorKind.TOO_SHORT else ErrorKind.GENERIC,
                    t.message
                )
            }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            when (val s = state) {
                is TranslateState.Loading -> LoadingContent()
                is TranslateState.Success -> SuccessContent(original = text, translated = s.translated)
                is TranslateState.Error -> ErrorContent(
                    state = s,
                    onRetry = { attempt++ },
                    onGoSettings = {
                        Toast.makeText(
                            context,
                            "请在「更多 → 设置」中配置翻译服务",
                            Toast.LENGTH_LONG
                        ).show()
                        onDismiss()
                    }
                )
            }
        }
    }
}

@Composable
private fun LoadingContent() {
    Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Text("翻译中…", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SuccessContent(original: String, translated: String) {
    Text(
        text = "原文",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Text(
        text = original,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
    )
    Text(
        text = "译文",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold
    )
    Text(
        text = translated,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
private fun ErrorContent(
    state: TranslateState.Error,
    onRetry: () -> Unit,
    onGoSettings: () -> Unit
) {
    val (msg, primary) = when (state.kind) {
        ErrorKind.CONFIG_MISSING -> "翻译服务未配置" to "去设置"
        ErrorKind.TOO_SHORT -> "内容太短，无需翻译" to null
        ErrorKind.GENERIC -> "翻译失败${state.message?.let { "：$it" }.orEmpty()}" to "重试"
    }
    Text(
        text = msg,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(vertical = 8.dp)
    )
    if (primary != null) {
        OutlinedButton(
            onClick = if (state.kind == ErrorKind.GENERIC) onRetry else onGoSettings,
            modifier = Modifier.padding(top = 4.dp)
        ) { Text(primary) }
    }
}
