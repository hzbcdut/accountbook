package nt.ddeoid.accountbook.ui.screens.home

import androidx.lifecycle.viewModelScope
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import nt.ddeoid.accountbook.data.local.DatabaseProvider
import nt.ddeoid.accountbook.data.local.entity.AccountEntity
import nt.ddeoid.accountbook.data.local.entity.AccountType
import nt.ddeoid.accountbook.data.repository.AccountRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * [HomeViewModel] 单测 —— 验证 v0.5.0 新增 `password` 字段在 submitAccount 链路
 * 里完整透传到 AccountEntity。
 *
 * 配套的"密码空串 → null"规范化也在这条用例里覆盖,避免 SQL 写出 `password = ''`
 * 而不是 `password IS NULL` 这种边界差异。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private lateinit var accountRepository: AccountRepository
    private lateinit var databaseProvider: DatabaseProvider

    private var viewModelsToCleanup: MutableList<HomeViewModel> = mutableListOf()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        accountRepository = mockk(relaxed = true)
        databaseProvider = mockk(relaxed = true)
        // observeActiveAccountsWithTags / deferred 提供空 Flow,HomeUiState 直接是 emptyList
        coEvery { accountRepository.observeActiveAccountsWithTags() } returns flowOf(emptyList())
        coEvery { databaseProvider.deferred<Any>(any()) } returns flowOf(emptyList<Any>())
        coEvery { accountRepository.upsert(any(), any()) } returns Unit
        viewModelsToCleanup = mutableListOf()
    }

    @After
    fun tearDown() {
        viewModelsToCleanup.forEach { it.viewModelScope.cancel() }
        viewModelsToCleanup.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `submitAccount passes password through to AccountEntity`() = runTest {
        val vm = newVm()
        val captured = slot<AccountEntity>()

        vm.submitAccount(
            id = null,
            platform = "GitHub",
            account = "alice@example.com",
            accountType = AccountType.EMAIL,
            registeredAt = null,
            notes = "",
            password = "Test1234",
            tagIds = emptyList(),
        )

        coVerify { accountRepository.upsert(capture(captured), any()) }
        assertEquals("Test1234", captured.captured.password)
    }

    @Test
    fun `submitAccount with null password writes null password`() = runTest {
        val vm = newVm()
        val captured = slot<AccountEntity>()

        vm.submitAccount(
            id = null,
            platform = "GitHub",
            account = "alice@example.com",
            accountType = AccountType.EMAIL,
            registeredAt = null,
            notes = "",
            password = null,
            tagIds = emptyList(),
        )

        coVerify { accountRepository.upsert(capture(captured), any()) }
        assertNull(captured.captured.password)
    }

    @Test
    fun `submitAccount with empty password normalizes to null`() = runTest {
        // BottomSheet 那边 `password.takeIf { it.isNotBlank() }`,但这里再断言一次
        // ViewModel 也守同样的契约 —— 万一以后有人从别的地方调 submitAccount,直接
        // 传空串,这条测试会红。
        val vm = newVm()
        val captured = slot<AccountEntity>()

        vm.submitAccount(
            id = null,
            platform = "GitHub",
            account = "alice@example.com",
            accountType = AccountType.EMAIL,
            registeredAt = null,
            notes = "",
            password = "",
            tagIds = emptyList(),
        )

        coVerify { accountRepository.upsert(capture(captured), any()) }
        assertNull(captured.captured.password)
    }

    private fun newVm(): HomeViewModel = HomeViewModel(accountRepository, databaseProvider)
        .also { viewModelsToCleanup += it }
}