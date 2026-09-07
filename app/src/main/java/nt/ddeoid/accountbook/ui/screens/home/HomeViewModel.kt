package nt.ddeoid.accountbook.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import nt.ddeoid.accountbook.data.local.dao.AccountDao
import nt.ddeoid.accountbook.data.local.dao.PlatformCatalogDao
import nt.ddeoid.accountbook.data.local.dao.TagDao
import javax.inject.Inject

/**
 * Phase 1 的 Home 屏只要能从 DB 里读到 3 个计数就能证明:
 * 1) SQLCipher 加密的 Room 正常打开;
 * 2) 种子数据写入成功;
 * 3) Compose 端到端从 DB 拉到数据。
 *
 * 真正的列表展示在 Phase 2。
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    accountDao: AccountDao,
    platformCatalogDao: PlatformCatalogDao,
    tagDao: TagDao,
) : ViewModel() {

    val state: StateFlow<HomeUiState> = combine(
        accountDao.observeCount(),
        platformCatalogDao.observeCount(),
        tagDao.observeCount(),
    ) { accounts, platforms, tags ->
        HomeUiState(
            accountCount = accounts,
            platformCatalogCount = platforms,
            tagCount = tags,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(),
    )
}

data class HomeUiState(
    val accountCount: Int = 0,
    val platformCatalogCount: Int = 0,
    val tagCount: Int = 0,
)