package nt.ddeoid.accountbook.data.repository

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import nt.ddeoid.accountbook.data.local.DatabaseProvider
import nt.ddeoid.accountbook.data.local.dao.AccountDao
import nt.ddeoid.accountbook.data.local.dao.AccountWithTags
import nt.ddeoid.accountbook.data.local.dao.PlatformCatalogDao
import nt.ddeoid.accountbook.data.local.entity.AccountEntity
import nt.ddeoid.accountbook.data.local.entity.AccountType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [AccountRepository] 单测 —— 验证 v0.5.0 新增 `password` 字段在 upsert 链路里
 * 完整透传。
 *
 * 不真跑 Room(SQLCipher / Room 都靠 instrumented test 覆盖);这里只测
 * "AccountEntity(password=X) → accountDao.insert / update → column = X" 这条
 * 编排链的契约。Room 自己的列映射通过 [androidx.room.testing.MigrationTestHelper]
 * 之类的工具覆盖,这里不重复。
 */
class AccountRepositoryTest {

    private val databaseProvider = mockk<DatabaseProvider>(relaxed = true)
    private val accountDao = mockk<AccountDao>(relaxed = true)
    private val platformCatalogDao = mockk<PlatformCatalogDao>(relaxed = true)

    private val repository = AccountRepository(databaseProvider)

    init {
        coEvery { databaseProvider.accountDao() } returns accountDao
        coEvery { databaseProvider.platformCatalogDao() } returns platformCatalogDao
        coEvery { accountDao.findById(any()) } returns null
        coEvery { accountDao.insert(any()) } returns Unit
        coEvery { accountDao.update(any()) } returns Unit
        coEvery { accountDao.replaceTagsFor(any(), any()) } returns Unit
        coEvery { platformCatalogDao.upsertCustom(any(), any(), any()) } returns Unit
        coEvery { platformCatalogDao.bumpUsage(any(), any()) } returns Unit
    }

    @Test
    fun `upsert writes password column when entity has password`() = runTest {
        val entity = sampleEntity().copy(password = "Test1234")

        val captured = slot<AccountEntity>()
        coEvery { accountDao.insert(capture(captured)) } returns Unit

        repository.upsert(entity, tagIds = emptyList())

        coVerify { accountDao.insert(any()) }
        assertEquals("Test1234", captured.captured.password)
    }

    @Test
    fun `upsert preserves null password`() = runTest {
        val entity = sampleEntity().copy(password = null)

        val captured = slot<AccountEntity>()
        coEvery { accountDao.insert(capture(captured)) } returns Unit

        repository.upsert(entity, tagIds = emptyList())

        coVerify { accountDao.insert(any()) }
        assertNull(captured.captured.password)
    }

    @Test
    fun `upsert with existing account calls update not insert`() = runTest {
        val entity = sampleEntity().copy(password = "UpdatedPass!")
        coEvery { accountDao.findById(entity.id) } returns entity.copy(password = "oldPass")

        val inserted = slot<AccountEntity>()
        val updated = slot<AccountEntity>()
        coEvery { accountDao.insert(capture(inserted)) } returns Unit
        coEvery { accountDao.update(capture(updated)) } returns Unit

        repository.upsert(entity, tagIds = emptyList())

        coVerify(exactly = 0) { accountDao.insert(any()) }
        coVerify(exactly = 1) { accountDao.update(any()) }
        assertEquals("UpdatedPass!", updated.captured.password)
    }

    private fun sampleEntity(): AccountEntity = AccountEntity(
        id = "test-id",
        platform = "GitHub",
        account = "alice@example.com",
        accountType = AccountType.EMAIL,
        registeredAt = "2024-01-01",
        notes = "",
        password = null,
        isActive = true,
        createdAt = 1_700_000_000_000L,
        updatedAt = 1_700_000_000_000L,
    )
}