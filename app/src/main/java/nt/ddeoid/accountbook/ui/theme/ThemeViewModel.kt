package nt.ddeoid.accountbook.ui.theme

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * 全局主题偏好。Phase 1 用内存版默认值;Phase 5 接 DataStore 后会读真实用户选择。
 *
 * 暴露为 [StateFlow] 是为了让 [androidx.compose.runtime.collectAsState] 能在 Composition 中订阅。
 */
@HiltViewModel
class ThemeViewModel @Inject constructor() : ViewModel() {

    private val _preference = MutableStateFlow(ThemePreferences.Default)
    val preference: StateFlow<ThemePreferences> = _preference.asStateFlow()
}