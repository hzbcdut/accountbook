package nt.ddeoid.accountbook.data.seed

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import nt.ddeoid.accountbook.data.local.AppDatabase
import nt.ddeoid.accountbook.data.local.entity.PlatformCatalogEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 首次启动时把内置标签 + 平台目录灌进 DB。
 *
 * - 标签:`INSERT ... ON CONFLICT(name) DO NOTHING`,可重复触发。
 * - 平台目录:`INSERT OR IGNORE`,用户后续新建走 [nt.ddeoid.accountbook.data.local.dao.PlatformCatalogDao.upsertCustom]。
 *
 * 由 [nt.ddeoid.accountbook.AccountBookApp] 在 `super.onCreate()` 之后同步启动一个 IO 协程。
 *
 * Phase 1 是粗暴版:每次启动都读 assets JSON。Phase 4 之后会改成"写入后保存一个标记位,
 * 只有标记缺失时才走这条路径",减少启动 IO。
 */
@Singleton
class SeedDataInitializer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    fun initialize() {
        scope.launch {
            runCatching {
                seedTags()
                seedPlatforms()
            }.onFailure { error ->
                // 启动期静默:种子写失败不要让 App 崩,下次启动再试。
                android.util.Log.w("SeedDataInitializer", "seed failed", error)
            }
        }
    }

    private suspend fun seedTags() {
        val raw = context.assets.open(ASSET_TAGS).bufferedReader().use { it.readText() }
        val parsed = json.decodeFromString(SeedTagsFile.serializer(), raw)
        parsed.tags.forEach { t ->
            db.tagDao().upsert(
                id = t.id,
                name = t.name,
                color = t.color,
                isBuiltin = true,
                sortOrder = t.sortOrder,
            )
        }
    }

    private suspend fun seedPlatforms() {
        val raw = context.assets.open(ASSET_PLATFORMS).bufferedReader().use { it.readText() }
        val parsed = json.decodeFromString(SeedPlatformsFile.serializer(), raw)
        val now = System.currentTimeMillis()
        parsed.platforms.forEach { p ->
            db.platformCatalogDao().insertIgnore(
                PlatformCatalogEntity(
                    id = p.id,
                    name = p.name,
                    usageCount = 0,
                    isCustom = false,
                    lastUsedAt = now,
                ),
            )
        }
    }

    companion object {
        private const val ASSET_TAGS = "seed/tags.json"
        private const val ASSET_PLATFORMS = "seed/platforms.json"
    }
}