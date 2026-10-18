package com.luckyagent.android.ui

import com.luckyagent.android.data.api.InstalledPlugin

data class PluginManagerUiState(
    val plugins: List<InstalledPlugin> = emptyList(),
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val loadError: String? = null,
    val operation: String? = null,
    val operationError: String? = null,
    val message: String? = null,
    val restartRequired: Boolean = false,
    val changeCount: Int = 0,
)
