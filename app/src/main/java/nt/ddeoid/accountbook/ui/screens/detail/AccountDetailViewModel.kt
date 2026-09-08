package nt.ddeoid.accountbook.ui.screens.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nt.ddeoid.accountbook.data.local.dao.AccountWithTags
import nt.ddeoid.accountbook.data.repository.AccountRepository
import javax.inject.Inject

/**
 * 详情页 ViewModel。
 *
 * - 从 SavedStateHandle 拿 accountId(NavHost 路由 `{id}` 的 arg)。
 * - 直接 observe [AccountRepository.observeAccountWithTags],Room 会推送最新数据,删除时自动 emit null。
 */
@HiltViewModel
class AccountDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val accountRepository: AccountRepository,
) : ViewModel() {

    val accountId: String =
        savedStateHandle.get<String>(ARG_ACCOUNT_ID)
            ?: error("AccountDetail requires '$ARG_ACCOUNT_ID' nav arg")

    val state: StateFlow<AccountDetailUiState> = accountRepository
        .observeAccountWithTags(accountId)
        .map { row ->
            if (row == null) AccountDetailUiState.Deleted
            else AccountDetailUiState.Loaded(row)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AccountDetailUiState.Loading,
        )

    fun setActive(active: Boolean) {
        viewModelScope.launch { accountRepository.setActive(accountId, active) }
    }

    fun delete() {
        viewModelScope.launch { accountRepository.delete(accountId) }
    }

    companion object {
        const val ARG_ACCOUNT_ID: String = "accountId"
    }
}

sealed interface AccountDetailUiState {
    data object Loading : AccountDetailUiState
    data object Deleted : AccountDetailUiState
    data class Loaded(val row: AccountWithTags) : AccountDetailUiState
}
