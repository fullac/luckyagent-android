package com.luckyagent.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.luckyagent.android.data.api.InstalledPlugin
import com.luckyagent.android.ui.AppUiState
import com.luckyagent.android.ui.AppViewModel
import com.luckyagent.android.ui.components.CloverCard
import com.luckyagent.android.ui.components.DetailDisclosure
import com.luckyagent.android.ui.components.EmptyState
import com.luckyagent.android.ui.components.MetaChip
import com.luckyagent.android.ui.components.ScreenHeader

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PluginsScreen(state: AppUiState, vm: AppViewModel) {
    val manager = state.pluginManager
    var query by rememberSaveable(state.settings.apiBase) { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("全部") }
    var dialog by rememberSaveable(state.settings.apiBase) { mutableStateOf<String?>(null) }
    var selectedName by rememberSaveable { mutableStateOf("") }
    var dialogChangeCount by rememberSaveable { mutableStateOf(0) }
    val busy = manager.operation != null
    val canManage = manager.loaded && manager.loadError == null && !manager.loading && !busy
    val selected = manager.plugins.firstOrNull { it.name == selectedName }
    val filtered = manager.plugins.filter { plugin ->
        val matchesFilter = when (filter) {
            "已启用" -> plugin.enabled
            "已停用" -> !plugin.enabled
            "异常" -> !plugin.error.isNullOrBlank()
            else -> true
        }
        matchesFilter && listOf(plugin.name, plugin.description, plugin.source, plugin.version)
            .any { it.contains(query.trim(), ignoreCase = true) }
    }
    fun openDialog(kind: String, name: String = "") {
        vm.clearPluginOperationError()
        selectedName = name
        dialogChangeCount = manager.changeCount
        dialog = kind
    }
    LaunchedEffect(manager.changeCount) {
        if (manager.changeCount > dialogChangeCount) dialog = null
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            eyebrow = "Plugins",
            title = "插件",
            subtitle = "管理当前 LuckyAgent 服务的扩展能力",
            actions = {
                IconButton(onClick = vm::refreshPlugins, enabled = !manager.loading && !busy) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "刷新插件")
                }
                IconButton(onClick = { openDialog("install") }, enabled = canManage) {
                    Icon(Icons.Outlined.Add, contentDescription = "安装插件")
                }
            },
        )
        if (manager.loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyVerticalGrid(
            columns = GridCells.Adaptive(360.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                CloverCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Widgets, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("已安装插件", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                when {
                                    !manager.loaded -> "正在读取服务端安装列表…"
                                    manager.loadError != null && manager.plugins.isEmpty() -> "未能获取安装列表"
                                    else -> "${manager.plugins.size} 个插件 · ${manager.plugins.count { it.enabled }} 个设为启用"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { openDialog("install") }, enabled = canManage) { Text("安装") }
                    }
                    Text("插件可提供技能、命令、子 Agent 和 MCP 服务。", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "这里显示已保存的启用设置。安装、更新、启停或卸载后，需要重启 LuckyAgent 服务才能生效。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (busy) item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    when (manager.operation?.substringBefore(':')) {
                        "install" -> "正在下载并安装插件，请稍候…"
                        "update" -> "正在从已记录来源更新插件，请稍候…"
                        else -> "正在保存插件变更…"
                    },
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            manager.message?.let { message ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
            manager.loadError?.let { error ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    CloverCard {
                        Text(if (manager.plugins.isEmpty()) "暂时无法读取插件" else "刷新失败，下面保留上次读取的列表", fontWeight = FontWeight.SemiBold)
                        Text(error, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = vm::refreshPlugins, enabled = !manager.loading && !busy) { Text("重试") }
                    }
                }
            }
            if (dialog == null) manager.operationError?.let { error ->
                item(span = { GridItemSpan(maxLineSpan) }) { Text(error, color = MaterialTheme.colorScheme.error) }
            }
            if (manager.plugins.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        label = { Text("搜索名称或说明") }, modifier = Modifier.fillMaxWidth(),
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("全部", "已启用", "已停用", "异常").forEach { label ->
                            FilterChip(selected = filter == label, onClick = { filter = label }, label = { Text(label) })
                        }
                    }
                }
            }
            if (manager.loaded && !manager.loading && manager.loadError == null && manager.plugins.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyState(
                        title = "还没有安装插件", body = "从 Git 仓库或服务端已有的插件目录安装。",
                        actionLabel = "安装插件", onAction = { openDialog("install") },
                    )
                }
            } else if (manager.plugins.isNotEmpty() && filtered.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyState(title = "没有匹配的插件", body = "换个关键词或筛选条件试试。")
                }
            }
            items(filtered, key = { it.name }) { plugin ->
                InstalledPluginCard(
                    plugin = plugin, canManage = canManage,
                    onToggle = { vm.setPluginEnabled(plugin.name, it) },
                    onUpdate = { openDialog("update", plugin.name) },
                    onRemove = { openDialog("remove", plugin.name) },
                )
            }
        }
    }
    dialog?.let { kind ->
        PluginChangeDialog(
            kind = kind, plugin = selected, busy = busy, error = manager.operationError,
            onDismiss = { dialog = null; vm.clearPluginOperationError() },
            onInstall = vm::installPlugin,
            onUpdate = { trust -> selected?.let { vm.updatePlugin(it.name, trust) } },
            onRemove = { selected?.let { vm.removePlugin(it.name) } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InstalledPluginCard(
    plugin: InstalledPlugin,
    canManage: Boolean,
    onToggle: (Boolean) -> Unit,
    onUpdate: () -> Unit,
    onRemove: () -> Unit,
) {
    var details by rememberSaveable(plugin.name) { mutableStateOf(false) }
    CloverCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Widgets, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(plugin.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("v${plugin.version}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = plugin.enabled,
                onCheckedChange = onToggle,
                enabled = canManage && (plugin.enabled || (plugin.error.isNullOrBlank() && (!plugin.requiresTrust || plugin.trusted))),
                modifier = Modifier.semantics { contentDescription = "${plugin.name} 的启用设置" },
            )
        }
        if (plugin.description.isNotBlank()) Text(plugin.description, style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MetaChip(if (plugin.enabled) "设为启用" else "设为停用")
            MetaChip(if (plugin.trusted) "已信任执行" else if (plugin.requiresTrust) "待授权执行" else "提示词组件")
            if (plugin.skills.isNotEmpty()) MetaChip("${plugin.skills.size} 个技能")
            if (plugin.commands.isNotEmpty()) MetaChip("${plugin.commands.size} 个命令")
            if (plugin.agents.isNotEmpty()) MetaChip("${plugin.agents.size} 个 Agent")
            if (plugin.mcpServers.isNotEmpty()) MetaChip("${plugin.mcpServers.size} 个 MCP")
            if (plugin.hookCount > 0) MetaChip("${plugin.hookCount} 个钩子")
        }
        plugin.error?.takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (plugin.requiresTrust && !plugin.trusted) Text(
            "包含执行组件。请在更新时明确授予执行信任，再启用插件。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { details = !details }) {
                Icon(if (details) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(if (details) "收起" else "详情")
            }
            TextButton(onClick = onUpdate, enabled = canManage) {
                Icon(Icons.Outlined.Update, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp)); Text("更新")
            }
            TextButton(onClick = onRemove, enabled = canManage) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp)); Text("卸载")
            }
        }
        if (details) {
            val metadata = buildString {
                appendLine("来源：${plugin.source}")
                if (plugin.ref.isNotBlank()) appendLine("版本引用：${plugin.ref}")
                if (plugin.subdir.isNotBlank()) appendLine("子目录：${plugin.subdir}")
                if (plugin.installedAt.isNotBlank()) appendLine("安装时间：${plugin.installedAt}")
                if (plugin.revision.isNotBlank()) appendLine("提交：${plugin.revision}")
            }.trim()
            SelectionContainer { Text(metadata, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (plugin.skills.isNotEmpty()) DetailDisclosure("技能", plugin.skills.joinToString("\n"))
            if (plugin.commands.isNotEmpty()) DetailDisclosure("命令", plugin.commands.joinToString("\n") { "/${plugin.name}:$it" })
            if (plugin.agents.isNotEmpty()) DetailDisclosure("Agent", plugin.agents.joinToString("\n"))
            if (plugin.mcpServers.isNotEmpty()) DetailDisclosure("MCP 服务", plugin.mcpServers.joinToString("\n"))
        }
    }
}

@Composable
private fun PluginChangeDialog(
    kind: String,
    plugin: InstalledPlugin?,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onInstall: (String, String, String, Boolean) -> Unit,
    onUpdate: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    var source by rememberSaveable(kind) { mutableStateOf("") }
    var ref by rememberSaveable(kind) { mutableStateOf("") }
    var subdir by rememberSaveable(kind) { mutableStateOf("") }
    var trust by rememberSaveable(kind, plugin?.name) { mutableStateOf(false) }
    var advanced by rememberSaveable(kind) { mutableStateOf(false) }
    val installing = kind == "install"
    val removing = kind == "remove"
    val title = when (kind) { "install" -> "安装插件"; "update" -> "更新 ${plugin?.name.orEmpty()}"; else -> "卸载 ${plugin?.name.orEmpty()}" }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when {
                    installing -> {
                        OutlinedTextField(
                            value = source, onValueChange = { source = it }, enabled = !busy,
                            label = { Text("Git 仓库或服务端目录") },
                            placeholder = { Text("https://github.com/team/plugins.git") },
                            modifier = Modifier.fillMaxWidth(), maxLines = 3,
                        )
                        Text("本地目录指运行 LuckyAgent 的电脑上的路径。", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { advanced = !advanced }, enabled = !busy) { Text(if (advanced) "收起安装选项" else "指定版本和子目录") }
                        if (advanced) {
                            OutlinedTextField(value = ref, onValueChange = { ref = it }, enabled = !busy, label = { Text("版本 / 分支（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(value = subdir, onValueChange = { subdir = it }, enabled = !busy, label = { Text("仓库内插件目录（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    removing -> Text("将 ${plugin?.name.orEmpty()} 从安装列表移除，重启服务后停止加载。已有会话保留。")
                    else -> {
                        Text("从已记录的来源重新安装。新版本需要重新授予执行信任。")
                        plugin?.let { SelectionContainer { Text(it.source, style = MaterialTheme.typography.bodySmall) } }
                    }
                }
                if (!removing) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = trust, onCheckedChange = { trust = it }, enabled = !busy)
                        Text("信任本次安装版本的执行组件", style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        "勾选后允许插件声明的 MCP、钩子及脚本按服务端权限运行。未勾选时，含 MCP 或钩子的插件将保存为停用。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!installing && plugin?.enabled == false) Text("更新保留当前停用设置，完成后可手动启用。", style = MaterialTheme.typography.bodySmall)
                }
                Text("变更保存后，重启 LuckyAgent 服务生效。", style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (busy) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp)); Text("正在处理，请稍候…")
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !busy && if (installing) source.isNotBlank() else plugin != null,
                onClick = { when { installing -> onInstall(source, ref, subdir, trust); removing -> onRemove(); else -> onUpdate(trust) } },
            ) { Text(when { installing -> "安装"; removing -> "卸载"; else -> "更新" }) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } },
    )
}
