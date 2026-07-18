package com.example.aihot.ui.more

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CurrencyYuan
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aihot.data.AiConfig
import com.example.aihot.data.AiConfigStore
import com.example.aihot.data.AiProvider
import com.example.aihot.data.AiUsageStore
import com.example.aihot.ui.components.AppTopBar
import com.example.aihot.ui.components.AppTopBarDefaults
import com.example.aihot.ui.components.SectionHeader
import com.example.aihot.ui.components.SettingsRow
import kotlinx.coroutines.launch

/**
 * AI 服务独立二级页 —— 全局 AI 服务配置 + token 用量统计。
 *
 * 从设置页拆出,聚合所有 AI 相关内容:服务商/API Key/模型/翻译开关 + 用量与费用。
 * 所有端侧 AI 功能(翻译、WebView 整页翻译、系统选中翻译)共用这里的同一套配置。
 *
 * 配置经 [AiConfigStore] 持久化(DataStore `ai_prefs`),用量经 [AiUsageStore](同一 DataStore)。
 */
@Composable
fun AiServiceScreen(
    configStore: AiConfigStore,
    usageStore: AiUsageStore,
    onBack: () -> Unit
) {
    val config by configStore.configFlow.collectAsStateWithLifecycle(initialValue = AiConfig())

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            AppTopBar(
                title = "AI 服务",
                titleFontSize = AppTopBarDefaults.secondaryTitleFontSize,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            // 服务商 section —— 翻译开关 + 服务商/API 地址/API Key/模型(自定义时另有单价两行)
            item { SectionHeader("服务商") }
            item {
                AiServiceSection(config = config, configStore = configStore)
            }

            // 用量与费用 section —— token 消耗统计 + 官方刊例价估算
            item { SectionHeader("用量与费用") }
            item {
                AiUsageSection(usageStore = usageStore, config = config)
            }
        }
    }
}

/**
 * AI 服务配置区块:翻译开关 + 服务商/API 地址/API Key/模型(自定义时另有单价两行)。
 *
 * - 服务商预设(DeepSeek/智谱 GLM)选中即回填 baseUrl 与默认模型,API 地址行只读展示;
 * - 预设下模型弹内置列表单选对话框,也可选「自定义模型名…」手填;
 * - API Key 对话框默认密码态输入,带显隐切换(避免肩窥);
 * - 自定义服务商需自填 baseUrl(含版本段)与模型,可另填单价用于费用估算。
 */
@Composable
private fun AiServiceSection(
    config: AiConfig,
    configStore: AiConfigStore
) {
    val scope = rememberCoroutineScope()
    // 当前正在编辑的字段:null=未弹窗;非空=对应字段对话框打开
    var editingField by rememberSaveable { mutableStateOf<String?>(null) }
    var showProviderDialog by rememberSaveable { mutableStateOf(false) }
    var showModelDialog by rememberSaveable { mutableStateOf(false) }

    val cs = MaterialTheme.colorScheme

    SettingsRow(
        icon = Icons.Filled.Translate,
        iconAccent = cs.primary,
        title = "启用翻译",
        subtitle = "翻译 HackerNews 标题/评论、GitHub Trending、HuggingFace 论文等",
        showDivider = true,
        trailing = {
            Switch(
                checked = config.translateEnabled,
                onCheckedChange = { enabled ->
                    scope.launch { configStore.update(config.copy(translateEnabled = enabled)) }
                }
            )
        },
        showChevron = false
    )

    SettingsRow(
        icon = Icons.Filled.Hub,
        iconAccent = cs.secondary,
        title = "服务商",
        subtitle = config.provider.label,
        onClick = { showProviderDialog = true }
    )

    // API 地址:预设只读展示(回填值),自定义可编辑
    if (config.provider == AiProvider.CUSTOM) {
        SettingsRow(
            icon = Icons.Filled.Language,
            iconAccent = cs.tertiary,
            title = "API 地址",
            subtitle = config.baseUrl.ifBlank { "未设置" },
            onClick = { editingField = "base" }
        )
    } else {
        SettingsRow(
            icon = Icons.Filled.Language,
            iconAccent = cs.tertiary,
            title = "API 地址",
            subtitle = config.effectiveBaseUrl,
            showChevron = false
        )
    }

    SettingsRow(
        icon = Icons.Filled.Key,
        iconAccent = cs.primary,
        title = "API Key",
        subtitle = if (config.apiKey.isBlank()) "未设置" else "已设置",
        onClick = { editingField = "key" }
    )

    SettingsRow(
        icon = Icons.Filled.Memory,
        iconAccent = cs.secondary,
        title = "模型",
        subtitle = config.model.ifBlank { "未设置" },
        showDivider = config.provider == AiProvider.CUSTOM,
        onClick = {
            if (config.provider == AiProvider.CUSTOM) editingField = "model"
            else showModelDialog = true
        }
    )

    // 自定义模型的估算单价(仅 CUSTOM;留空则只统计 token 不估算费用)
    if (config.provider == AiProvider.CUSTOM) {
        SettingsRow(
            icon = Icons.Filled.CurrencyYuan,
            iconAccent = cs.tertiary,
            title = "输入单价",
            subtitle = config.customInputPrice.ifBlank { "未设置(不估算费用)" },
            onClick = { editingField = "inputPrice" }
        )
        SettingsRow(
            icon = Icons.Filled.CurrencyYuan,
            iconAccent = cs.tertiary,
            title = "输出单价",
            subtitle = config.customOutputPrice.ifBlank { "未设置(不估算费用)" },
            showDivider = false,
            onClick = { editingField = "outputPrice" }
        )
    }

    // 服务商单选对话框:选预设回填 baseUrl 与默认模型(key 保留);选自定义保留现有值供编辑
    if (showProviderDialog) {
        ProviderDialog(
            selected = config.provider,
            onSelect = { p ->
                scope.launch {
                    val updated = if (p == AiProvider.CUSTOM) {
                        config.copy(provider = p)
                    } else {
                        config.copy(
                            provider = p,
                            baseUrl = p.baseUrl,
                            model = config.model.takeIf { m -> p.models.any { it.id == m } }
                                ?: p.models.first().id
                        )
                    }
                    configStore.update(updated)
                }
                showProviderDialog = false
            },
            onDismiss = { showProviderDialog = false }
        )
    }

    // 内置模型单选对话框(预设服务商):带估算单价;底部入口可手填模型名
    if (showModelDialog) {
        ModelDialog(
            provider = config.provider,
            selected = config.model,
            onSelect = { id ->
                scope.launch { configStore.update(config.copy(model = id)) }
                showModelDialog = false
            },
            onCustom = {
                showModelDialog = false
                editingField = "model"
            },
            onDismiss = { showModelDialog = false }
        )
    }

    editingField?.let { field ->
        when (field) {
            "base" -> EditDialog(
                title = "API 地址",
                initial = config.baseUrl,
                placeholder = "含版本段,如 https://api.example.com/v1",
                onDismiss = { editingField = null },
                onConfirm = { v ->
                    scope.launch { configStore.update(config.copy(baseUrl = v)) }
                    editingField = null
                }
            )
            "key" -> EditDialog(
                title = "API Key",
                initial = config.apiKey,
                isSecret = true,
                onDismiss = { editingField = null },
                onConfirm = { v ->
                    scope.launch { configStore.update(config.copy(apiKey = v)) }
                    editingField = null
                }
            )
            "model" -> EditDialog(
                title = "模型",
                initial = config.model,
                placeholder = "模型 ID,如 deepseek-v4-flash",
                keyboardType = KeyboardType.Text,
                onDismiss = { editingField = null },
                onConfirm = { v ->
                    scope.launch { configStore.update(config.copy(model = v)) }
                    editingField = null
                }
            )
            "inputPrice" -> EditDialog(
                title = "输入单价",
                initial = config.customInputPrice,
                placeholder = "元 / 百万 token,留空不估算",
                keyboardType = KeyboardType.Decimal,
                onDismiss = { editingField = null },
                onConfirm = { v ->
                    scope.launch { configStore.update(config.copy(customInputPrice = v)) }
                    editingField = null
                }
            )
            "outputPrice" -> EditDialog(
                title = "输出单价",
                initial = config.customOutputPrice,
                placeholder = "元 / 百万 token,留空不估算",
                keyboardType = KeyboardType.Decimal,
                onDismiss = { editingField = null },
                onConfirm = { v ->
                    scope.launch { configStore.update(config.copy(customOutputPrice = v)) }
                    editingField = null
                }
            )
        }
    }
}

/**
 * 用量与费用区块:本月/累计合计 + 按模型明细 + 清空统计。
 *
 * 数据来自 [AiUsageStore.statsFlow](模型 × 月聚合);费用按内置模型刊例价或
 * 自定义单价估算([pricingOf]),存在不可定价条目时合计加「≥」前缀。
 */
@Composable
private fun AiUsageSection(
    usageStore: AiUsageStore,
    config: AiConfig
) {
    val entries by usageStore.statsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var confirmClear by rememberSaveable { mutableStateOf(false) }

    if (entries.isEmpty()) {
        Text(
            text = "暂无用量记录,AI 调用(如翻译)成功后自动统计",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)
        )
        return
    }

    val monthEntries = entries.filter { it.month == AiUsageStore.monthNow() }
    UsageSummaryRow(title = "本月", entries = monthEntries, config = config, showDivider = true)
    UsageSummaryRow(title = "累计", entries = entries, config = config, showDivider = true)

    // 按模型明细(跨月合计)
    entries.groupBy { it.model }.forEach { (model, list) ->
        val prompt = list.sumOf { it.promptTokens }
        val completion = list.sumOf { it.completionTokens }
        val calls = list.sumOf { it.calls }
        val pricing = pricingOf(model, config)
        val costText = pricing?.let { (inP, outP) ->
            "估算 ${formatCost(prompt / 1e6 * inP + completion / 1e6 * outP)}"
        } ?: "费用未定价"
        SettingsRow(
            title = model,
            subtitle = "输入 ${formatTokens(prompt)} · 输出 ${formatTokens(completion)} · $calls 次 · $costText",
            showDivider = true,
            showChevron = false
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "费用按官方刊例价估算,仅供参考",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = { confirmClear = true }) {
            Text("清空统计", color = MaterialTheme.colorScheme.error)
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空统计") },
            text = { Text("将删除全部 token 用量与费用记录,不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { usageStore.clear() }
                    confirmClear = false
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            }
        )
    }
}

/** 本月/累计合计行:输入/输出 token、调用次数与估算费用(含未定价条目时加「≥」)。 */
@Composable
private fun UsageSummaryRow(
    title: String,
    entries: List<AiUsageStore.Entry>,
    config: AiConfig,
    showDivider: Boolean
) {
    if (entries.isEmpty()) {
        SettingsRow(title = title, subtitle = "暂无记录", showDivider = showDivider, showChevron = false)
        return
    }
    val prompt = entries.sumOf { it.promptTokens }
    val completion = entries.sumOf { it.completionTokens }
    val calls = entries.sumOf { it.calls }
    var cost = 0.0
    var hasUnpriced = false
    entries.forEach { e ->
        val pricing = pricingOf(e.model, config)
        if (pricing == null) hasUnpriced = true
        else cost += e.promptTokens / 1e6 * pricing.first + e.completionTokens / 1e6 * pricing.second
    }
    val costText = (if (hasUnpriced) "≥" else "") + formatCost(cost)
    SettingsRow(
        title = title,
        subtitle = "输入 ${formatTokens(prompt)} · 输出 ${formatTokens(completion)} · $calls 次 · $costText",
        showDivider = showDivider,
        showChevron = false
    )
}

/** 条目模型定价:内置模型查刊例价表;否则若正是当前自定义配置的模型,用用户手填单价。 */
private fun pricingOf(model: String, config: AiConfig): Pair<Double, Double>? =
    AiConfig.builtinPricingOf(model)
        ?: config.currentPricing()?.takeIf { config.provider == AiProvider.CUSTOM && config.model == model }

private fun formatTokens(n: Long): String = "%,d".format(n)

/** 费用格式化:小额保留 4 位小数(翻译单次费用极低),其余 2 位。 */
private fun formatCost(cost: Double): String =
    if (cost in 0.0..0.01) "¥%.4f".format(cost) else "¥%.2f".format(cost)

/** 单价展示:整数不带小数点(1 而非 1.0)。 */
private fun formatPrice(d: Double): String =
    if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

/** 服务商单选对话框。 */
@Composable
private fun ProviderDialog(
    selected: AiProvider,
    onSelect: (AiProvider) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("服务商") },
        text = {
            Column {
                AiProvider.entries.forEach { p ->
                    DialogRadioRow(
                        selected = p == selected,
                        title = p.label,
                        subtitle = if (p == AiProvider.CUSTOM) "OpenAI 兼容服务,自填地址与模型" else p.baseUrl,
                        onClick = { onSelect(p) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

/** 内置模型单选对话框(预设服务商):每项带估算单价;底部「自定义模型名…」转手填。 */
@Composable
private fun ModelDialog(
    provider: AiProvider,
    selected: String,
    onSelect: (String) -> Unit,
    onCustom: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("模型") },
        text = {
            Column {
                provider.models.forEach { m ->
                    DialogRadioRow(
                        selected = m.id == selected,
                        title = m.id,
                        subtitle = "输入 ¥${formatPrice(m.inputPricePerMillion)} / 输出 ¥${formatPrice(m.outputPricePerMillion)} 每百万 token",
                        onClick = { onSelect(m.id) }
                    )
                }
                val isCustomName = selected.isNotBlank() && provider.models.none { it.id == selected }
                DialogRadioRow(
                    selected = isCustomName,
                    title = "自定义模型名…",
                    subtitle = if (isCustomName) selected else null,
                    onClick = onCustom
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

/** 对话框内的单选行:RadioButton + 标题/副标题,整行可点。 */
@Composable
private fun DialogRadioRow(
    selected: Boolean,
    title: String,
    subtitle: String?,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 单行文本输入对话框。保存时回写配置 Store。
 *
 * @param isSecret 密码态输入(API Key),带显隐切换
 * @param placeholder 输入框占位提示(为空时显示)
 * @param keyboardType 键盘类型;null 时按 isSecret 取 Password/Uri(旧行为)
 */
@Composable
private fun EditDialog(
    title: String,
    initial: String,
    isSecret: Boolean = false,
    placeholder: String? = null,
    keyboardType: KeyboardType? = null,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    var visible by rememberSaveable { mutableStateOf(!isSecret) }
    // 每次重新打开时把内容重置为当前已保存值(rememberSaveable 会跨重组保留,
    // 故用 LaunchedEffect(title, initial) 在初值变化时同步)
    LaunchedEffect(initial) { text = initial }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = placeholder?.let { { Text(it) } },
                visualTransformation = if (isSecret && !visible) PasswordVisualTransformation()
                else androidx.compose.ui.text.input.VisualTransformation.None,
                trailingIcon = if (isSecret) {
                    {
                        TextButton(onClick = { visible = !visible }) {
                            Text(if (visible) "隐藏" else "显示")
                        }
                    }
                } else null,
                keyboardOptions = KeyboardOptions(
                    keyboardType = keyboardType
                        ?: if (isSecret) KeyboardType.Password else KeyboardType.Uri
                ),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
