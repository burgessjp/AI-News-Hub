package com.example.aihot.ui.daily

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
import com.example.aihot.ui.ErrorState
import com.example.aihot.ui.LoadingState
import com.example.aihot.ui.UiState
import com.example.aihot.ui.DailyViewModel
import com.example.aihot.ui.components.AppTopBar

/** 指定日期的日报屏幕。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyDateScreen(
    date: String,
    onBack: () -> Unit,
    onOpenUrl: (String, String) -> Unit = { _, _ -> },
    vm: DailyViewModel = viewModel()
) {
    val state by vm.selected.collectAsStateWithLifecycle()

    LaunchedEffect(date) { vm.loadDate(date) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            AppTopBar(
                title = "$date 日报",
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
                is UiState.Loading -> com.example.aihot.ui.components.NewsCardSkeletonList(count = 4)
                is UiState.Error -> ErrorState(message = s.message, onRetry = { vm.loadDate(date) })
                is UiState.Success -> DailyContent(
                    report = s.data,
                    onOpen = { url -> onOpenUrl(url, "AI HOT") }
                )
            }
        }
    }
}
