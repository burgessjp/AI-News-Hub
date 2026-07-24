package com.peng.ainewshub.ui.daily

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.peng.ainewshub.ui.ErrorState
import com.peng.ainewshub.ui.LoadingState
import com.peng.ainewshub.ui.UiState
import com.peng.ainewshub.ui.DailyViewModel
import com.peng.ainewshub.ui.components.AppTopBar
import com.peng.ainewshub.ui.components.AppTopBarDefaults

/** 指定日期的日报屏幕。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyDateScreen(
    date: String,
    onBack: () -> Unit,
    onOpenUrl: (String, String) -> Unit = { _, _ -> },
    // 按日期独立持有 VM:避免与 DailyScreen/DailyArchiveScreen 共用同一 DailyViewModel
    // 导致换日期时复用上次 Success 的 _selected,首帧闪现上一日期内容。
    vm: DailyViewModel = viewModel(key = "daily-date-$date")
) {
    val state by vm.selected.collectAsStateWithLifecycle()

    LaunchedEffect(date) { vm.loadDate(date) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            AppTopBar(
                title = "$date 日报",
                titleFontSize = AppTopBarDefaults.secondaryTitleFontSize,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                is UiState.Loading -> com.peng.ainewshub.ui.components.NewsCardSkeletonList(count = 4)
                is UiState.Error -> ErrorState(
                    message = s.message,
                    title = "日报加载失败",
                    onRetry = { vm.loadDate(date) }
                )
                is UiState.Success -> DailyContent(
                    report = s.data,
                    onOpen = { url -> onOpenUrl(url, "AI HOT") }
                )
            }
        }
    }
}
