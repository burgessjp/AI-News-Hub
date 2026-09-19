package com.peng.ainewshub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.peng.ainewshub.R

/**
 * 通用空状态 —— 场景化参数版。
 *
 * 视觉(沿用"精致低对比"规格):
 *  - 图标置于 primary 半透明圆形背景里(72dp,更友好)
 *  - 标题用 titleLarge,副标题 bodySmall + onSurfaceVariant + 居中
 *  - 可选动作按钮(actionLabel + onAction 同时非空才显示,primary 色)
 *
 * 场景化用法(图标语义约定):
 *  全 App 去图标改版:空/错误态统一「※」排版符号替代 Material 图标
 */
@Composable
fun EmptyState(
    title: String,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            StateIconBadge()
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
 * 默认即「加载/网络错误」场景:「※」+ 「加载失败」标题;
 * 各调用点按场景覆盖 [title](如「日报加载失败」),[message] 展示底层错误详情。
 */
@Composable
fun ErrorState(
    message: String,
    onRetry: () -> Unit,
    title: String = stringResource(R.string.common_load_failed),
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            StateIconBadge()
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
 * 区块内嵌提示 —— 单页多区块(「今天」的分源区块 / 「热词」的关注段)里某一区块的
 * 空态/错误态:与整页 [EmptyState]/[ErrorState] 同语言但体量收一档(小图标无底衬、
 * 标题 bodySmall),嵌入列表 item 不撑满整屏、不打断其余区块的渲染。
 * [actionLabel] 与 [onAction] 同时非空才渲染动作(重试/查看类文字按钮)。
 */
@Composable
fun SectionNotice(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // 「※」排版符号替代图标:报刊「注意/附注」记号,与纸墨语言同源
        Text(
            text = "※",
            style = MaterialTheme.typography.titleMedium,
            color = cs.onSurfaceVariant
        )
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = cs.onSurface
        )
        if (message != null) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) {
                Text(actionLabel, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * 状态图标徽章 —— primary 半透明圆形背景,72dp。
 */
@Composable
private fun StateIconBadge() {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(cs.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        // 「※」大字:报刊附注记号,空/错态的排版语言
        Text(
            text = "※",
            fontSize = 26.sp,
            color = cs.onSurfaceVariant
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
