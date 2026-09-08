package nt.ddeoid.accountbook.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import nt.ddeoid.accountbook.data.local.DatabasePassphraseProvider
import nt.ddeoid.accountbook.data.local.MigrationMarker
import nt.ddeoid.accountbook.security.crypto.Bip39WordListProvider
import nt.ddeoid.accountbook.security.crypto.MnemonicCodec
import nt.ddeoid.accountbook.security.crypto.PinKdf
import nt.ddeoid.accountbook.security.lock.EncryptedPrefsFactory
import nt.ddeoid.accountbook.security.lock.KeystoreAccess
import javax.inject.Singleton

/**
 * 数据库 + 安全层相关的 Hilt 模块。
 *
 * Phase 4 之前这里提供 `AppDatabase` 和三个 DAO 的 `@Singleton` 绑定。**那些绑定已经
 * 全部删掉**,原因是 Q4=C:锁定必须真的关库,而直接注入的 DAO 生命周期比库长,一旦
 * 库被关掉,攥在手里的 DAO 就成了指向已关闭连接的野指针。
 *
 * 现在的结构:
 * - [nt.ddeoid.accountbook.data.local.DatabaseProvider] 自己带 `@Inject` 构造器 +
 *   `@Singleton`,不需要在这里声明;它负责 open / close,并按需解析 DAO。
 * - 所有消费方注入 `DatabaseProvider`,调用 `provider.accountDao()` 之类,每次重新解析。
 * - [DatabasePassphraseProvider] 保留,是"未启用应用锁"时的口令来源(v0.3.0 行为)。
 * - [MnemonicCodec] 由 [Bip39WordListProvider] 从 assets 加载、校验后提供。
 * - [KeystoreAccess] / [EncryptedPrefsFactory] 都是抽象接口的 Default 实现 —— 测试可以
 *   在 Hilt 里替换。
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun providePassphraseProvider(
        @ApplicationContext context: Context,
    ): DatabasePassphraseProvider = DatabasePassphraseProvider(context)

    @Provides
    @Singleton
    fun provideMnemonicCodec(provider: Bip39WordListProvider): MnemonicCodec = provider.codec()

    @Provides
    @Singleton
    fun provideEncryptedPrefsFactory(
        @ApplicationContext context: Context,
    ): EncryptedPrefsFactory = EncryptedPrefsFactory.Default(context)

    @Provides
    @Singleton
    fun provideKeystoreAccess(): KeystoreAccess = KeystoreAccess.Default()

    /**
     * [MigrationMarker] 需要一个生产用的 [android.content.SharedPreferences](在
     * [MigrationMarker.Factory] 里开)。测试可以直接构造 `MigrationMarker(inMemoryPrefs)`。
     */
    @Provides
    @Singleton
    fun provideMigrationMarker(factory: MigrationMarker.Factory): MigrationMarker =
        MigrationMarker(factory.create())

    /**
     * PBKDF2 迭代次数 —— 跟 [PinKdf.PRODUCTION_ITERATIONS] 绑死。
     *
     * 走 Hilt 而不是默认值,是因为 [KeyVault] 的构造器参数 `pinKdfIterations: Int`
     * 有默认但 Hilt 不会读 Kotlin 默认值。
     */
    @Provides
    @Singleton
    fun providePinKdfIterations(): Int = PinKdf.PRODUCTION_ITERATIONS

    /**
     * 给 [nt.ddeoid.accountbook.security.lock.LockController] 用的应用级 scope。
     *
     * `LockController` 自己的字段里需要 `CoroutineScope` 来跑 `bootstrap()` /
     * `lock()` / `unlockWith*()` 这些 IO 操作。原本 Kotlin 默认值是
     * `CoroutineScope(Dispatchers.IO)`,但 Hilt 不读默认值,所以这里显式提供一份。
     *
     * 用 [SupervisorJob] + [Dispatchers.IO]:子任务失败不互相取消,IO 线程池适合
     * SQLCipher / Keystore 这种会阻塞的活。
     */
    @Provides
    @Singleton
    fun provideLockControllerScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
