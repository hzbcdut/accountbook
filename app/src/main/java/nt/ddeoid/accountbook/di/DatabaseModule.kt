package nt.ddeoid.accountbook.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import nt.ddeoid.accountbook.data.local.AppDatabase
import nt.ddeoid.accountbook.data.local.DatabasePassphraseProvider
import nt.ddeoid.accountbook.data.local.dao.AccountDao
import nt.ddeoid.accountbook.data.local.dao.PlatformCatalogDao
import nt.ddeoid.accountbook.data.local.dao.TagDao
import javax.inject.Singleton

/**
 * 数据库 Hilt 模块。
 *
 * - DB 文件名 [AppDatabase.DATABASE_NAME],落在 `getDatabasesDir()` 默认目录。
 * - 加密口令由 [DatabasePassphraseProvider] 提供。Phase 1 仅占位实现,Phase 4 会改成
 *   从 EncryptedSharedPreferences 取真实口令(由用户 PIN + 设备 Keystore 派生)。
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
    fun provideDatabase(
        @ApplicationContext context: Context,
        passphraseProvider: DatabasePassphraseProvider,
    ): AppDatabase {
        val passphrase: ByteArray = passphraseProvider.getOrCreate()
        val factory = SupportOpenHelperFactory(passphrase)
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME,
        )
            .openHelperFactory(factory)
            .build()
    }

    @Provides
    fun provideAccountDao(db: AppDatabase): AccountDao = db.accountDao()

    @Provides
    fun providePlatformCatalogDao(db: AppDatabase): PlatformCatalogDao = db.platformCatalogDao()

    @Provides
    fun provideTagDao(db: AppDatabase): TagDao = db.tagDao()
}