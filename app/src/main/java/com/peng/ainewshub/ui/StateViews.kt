package com.peng.ainewshub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.peng.ainewshub.R
import com.peng.ainewshub.ui.theme.AppAlpha

/**
 * 通用空状态 —— 场景化参数版。
 *
 * 视觉(沿用"精致低对比"规格):
 *  - 图标置于 primary 半透明圆形背景里(72dp,更友好)
 *  - 标题用 titleLarge,副标题 bodySmall + onSurfaceVariant + 居中
 *  - 可选动作按钮(actionLabel + onAction 同时非空才显示,primary 色)
 *
 * 场景化用法(图标语义约定):
 *  - 搜索无结果 → Icons.Outlined.SearchOff
 *  - 浏览历史空 → Icons.Outlined.History
 *  - 归档/数据缺失 → Icons.Outlined.Inventory2
 *  - 通用空态 → 默认 Icons.Outlined.Inbox
 */
@Composable
fun EmptyState(
    title: String,
    subtitle: String? = null,
    icon: ImageVector = Icons.Outlined.Inbox,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            StateIconBadge(icon = icon)
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            if (actionLabel != null && onAction != null) {
                StateActionButton(label = actionLabel, onClick = onAction)
            }
        }
    }
}

/**
 * 通用错误状态 —— 带重试按钮,场景化参数版。
 *
 * 默认即「加载/网络错误」场景:Icons.Outlined.CloudOff + 「加载失败」标题;
 * 各调用点按场景覆盖 [title](如「日报加载失败」),[message] 展示底层错误详情。
 */
@Composable
fun ErrorState(
    message: String,
    onRetry: () -> Unit,
    title: String = stringResource(R.string.common_load_failed),
    icon: ImageVector = Icons.Outlined.CloudOff,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            StateIconBadge(icon = icon)
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            StateActionButton(label = stringResource(R.string.common_retry), onClick = onRetry)
        }
    }
}

/** 通用加载中。 */
@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        androidx.compose.material3.CircularProgressIndicator(
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/**
 * 状态图标徽章 —— primary 半透明圆形背景,72dp。
 */
@Composable
private fun StateIconBadge(icon: ImageVector) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = Modifier.size(72.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(cs.primary.copy(alpha = AppAlpha.badgeOverlay))
        )
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = cs.primary,
            modifier = Modifier.size(34.dp)
        )
    }
}

/** 状态页动作按钮 —— primary 色填充(品牌一致),空态动作与错误态重试共用。 */
@Composable
private fun StateActionButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
    ) {
        Text(label, fontWeight = FontWeight.SemiBold)
    }
}
