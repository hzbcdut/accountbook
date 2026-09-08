package nt.ddeoid.accountbook.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nt.ddeoid.accountbook.data.local.DatabaseProvider
import nt.ddeoid.accountbook.data.local.dao.AccountWithTags
import nt.ddeoid.accountbook.data.local.entity.AccountEntity
import nt.ddeoid.accountbook.data.local.entity.AccountType
import nt.ddeoid.accountbook.data.local.entity.PlatformCatalogEntity
import nt.ddeoid.accountbook.data.local.entity.TagEntity
import nt.ddeoid.accountbook.data.repository.AccountRepository
import javax.inject.Inject

/**
 * Phase 2 主界面 ViewModel。
 *
 * 持有筛选 / 排序 / 搜索状态,合并成 [HomeUiState] 给 UI。
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val databaseProvider: DatabaseProvider,
) : ViewModel() {

    private val filterState = MutableStateFlow(HomeFilterState())

    val state: StateFlow<HomeUiState> = combine(
        accountRepository.observeActiveAccountsWithTags(),
        databaseProvider.deferred { it.tagDao().observeAll() },
        filterState,
    ) { accounts, tags, filter ->
        buildUiState(accounts, tags, filter)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(isLoading = true),
    )

    // 一次性计数,Phase 1 占位屏用过,Phase 2 还会在第一次冷启动时用到。
    val counts: StateFlow<HomeCounts> = combine(
        accountRepository.observeActiveAccountsWithTags(),
        databaseProvider.deferred { it.platformCatalogDao().observeCount() },
        databaseProvider.deferred { it.tagDao().observeCount() },
    ) { accounts, platforms, tags ->
        HomeCounts(
            activeAccountCount = accounts.size,
            platformCatalogCount = platforms,
            tagCount = tags,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeCounts(),
    )

    // 平台目录(BottomSheet 自动补全)
    val platformSuggestions: StateFlow<List<PlatformCatalogEntity>> =
        databaseProvider.deferred { it.platformCatalogDao().observeAll() }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList(),
            )

    // --- 筛选动作 ---------------------------------------------------------

    fun onSearchChange(query: String) {
        filterState.update { it.copy(searchQuery = query) }
    }

    fun toggleTag(tagId: String) {
        filterState.update {
            val newSet = it.selectedTagIds.toMutableSet()
            if (!newSet.add(tagId)) newSet.remove(tagId)
            it.copy(selectedTagIds = newSet)
        }
    }

    fun clearTagFilter() {
        filterState.update { it.copy(selectedTagIds = emptySet()) }
    }

    fun setSortOrder(order: SortOrder) {
        filterState.update { it.copy(sortOrder = order) }
    }

    // --- 写动作 -----------------------------------------------------------

    /** 新增或编辑一条账号。Phase 2 简化版:全字段一次性提交,不处理并发冲突。 */
    fun submitAccount(
        id: String?,
        platform: String,
        account: String,
        accountType: AccountType,
        registeredAt: String?,
        notes: String,
        tagIds: List<String>,
    ) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val entity = AccountEntity(
                id = id ?: AccountRepository.newId(),
                platform = platform.trim(),
                account = account.trim(),
                accountType = accountType,
                registeredAt = registeredAt?.takeIf { it.isNotBlank() },
                notes = notes.trim(),
                isActive = true,
                createdAt = now,
                updatedAt = now,
            )
            accountRepository.upsert(entity, tagIds)
        }
    }

    fun deleteAccount(id: String) {
        viewModelScope.launch { accountRepository.delete(id) }
    }

    fun setActive(id: String, active: Boolean) {
        viewModelScope.launch { accountRepository.setActive(id, active) }
    }

    // --- 纯函数 -----------------------------------------------------------

    private fun buildUiState(
        accounts: List<AccountWithTags>,
        tags: List<TagEntity>,
        filter: HomeFilterState,
    ): HomeUiState {
        val filtered = accounts.filter { row ->
            val matchesSearch = filter.searchQuery.isBlank() || run {
                val q = filter.searchQuery.trim()
                row.account.platform.contains(q, ignoreCase = true) ||
                    row.account.account.contains(q, ignoreCase = true) ||
                    row.account.notes.contains(q, ignoreCase = true)
            }
            val matchesTags = filter.selectedTagIds.isEmpty() ||
                row.tags.any { it.id in filter.selectedTagIds }
            matchesSearch && matchesTags
        }

        val sorted = when (filter.sortOrder) {
            SortOrder.UPDATED_DESC -> filtered.sortedByDescending { it.account.updatedAt }
            SortOrder.UPDATED_ASC -> filtered.sortedBy { it.account.updatedAt }
            SortOrder.PLATFORM_ASC -> filtered.sortedWith(platformThenUpdated)
            SortOrder.REGISTERED_DESC -> filtered.sortedWith(registeredDescThenPlatform)
        }

        val groups = sorted
            .groupBy { it.account.platform }
            .toList()
            .sortedBy { it.first }  // 组之间按平台名字典序
            .map { (platform, items) -> PlatformGroup(platform, items) }

        return HomeUiState(
            groups = groups,
            allTags = tags,
            filter = filter,
            isLoading = false,
            totalAfterFilter = filtered.size,
        )
    }
}

// --- UI 状态 --------------------------------------------------------------

data class HomeUiState(
    val groups: List<PlatformGroup> = emptyList(),
    val allTags: List<TagEntity> = emptyList(),
    val filter: HomeFilterState = HomeFilterState(),
    val isLoading: Boolean = true,
    val totalAfterFilter: Int = 0,
)

data class HomeFilterState(
    val searchQuery: String = "",
    val selectedTagIds: Set<String> = emptySet(),
    val sortOrder: SortOrder = SortOrder.UPDATED_DESC,
)

data class HomeCounts(
    val activeAccountCount: Int = 0,
    val platformCatalogCount: Int = 0,
    val tagCount: Int = 0,
)

data class PlatformGroup(
    val platform: String,
    val items: List<AccountWithTags>,
) {
    val count: Int get() = items.size
}

enum class SortOrder(val label: String) {
    UPDATED_DESC("按更新时间 倒序"),
    UPDATED_ASC("按更新时间 正序"),
    PLATFORM_ASC("按平台 A-Z"),
    REGISTERED_DESC("按注册时间 倒序"),
}

// --- 排序 Comparators ----------------------------------------------------

private val platformThenUpdated: Comparator<AccountWithTags> =
    Comparator { a, b ->
        val platformCmp = String.CASE_INSENSITIVE_ORDER.compare(a.account.platform, b.account.platform)
        if (platformCmp != 0) {
            platformCmp
        } else {
            b.account.updatedAt.compareTo(a.account.updatedAt)
        }
    }

private val registeredDescThenPlatform: Comparator<AccountWithTags> =
    Comparator { a, b ->
        // 注册时间为空的排到末尾,非空的按 yyyy-MM-dd 字符串倒序
        val aReg = a.account.registeredAt
        val bReg = b.account.registeredAt
        val cmp = when {
            aReg == null && bReg == null -> 0
            aReg == null -> 1
            bReg == null -> -1
            else -> bReg.compareTo(aReg)
        }
        if (cmp != 0) {
            cmp
        } else {
            String.CASE_INSENSITIVE_ORDER.compare(a.account.platform, b.account.platform)
        }
    }