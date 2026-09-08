package nt.ddeoid.accountbook.data.seed

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import nt.ddeoid.accountbook.data.local.DatabaseProvider
import nt.ddeoid.accountbook.data.local.entity.PlatformCatalogEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 首次启动时把内置标签 + 平台目录灌进 DB。
 *
 * - 标签:`INSERT ... ON CONFLICT(name) DO NOTHING`,可重复触发。
 * - 平台目录:`INSERT OR IGNORE`,用户后续新建走 [nt.ddeoid.accountbook.data.local.dao.PlatformCatalogDao.upsertCustom]。
 *
 * 由 [nt.ddeoid.accountbook.data.local.DatabaseBootstrap] 在**数据库打开之后**调用。
 *
 * ⚠️ 不能再挂在 `Application.onCreate` 上:那时库还没开,而播种需要写库。更重要的是,
 * 如果这里持有 [AppDatabase] 注入,Hilt 会在解锁之前就把 SQLCipher 连接打开 —— 直接
 * 击穿 Q4=C("锁定时真的关库")。所以本类改成通过 [DatabaseProvider] 按需解析 DAO。
 *
 * Phase 1 是粗暴版:每次都读 assets JSON。将来可以改成"写入后保存一个标记位,只有标记
 * 缺失时才走这条路径",减少重复 IO —— 启用应用锁后每次解锁都会触发一次,值得优化,
 * 但不属于 Phase 4 的范围。
 */
@Singleton
class SeedDataInitializer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val databaseProvider: DatabaseProvider,
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
        val tagDao = databaseProvider.tagDao()
        parsed.tags.forEach { t ->
            tagDao.upsert(
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
        val platformCatalogDao = databaseProvider.platformCatalogDao()
        parsed.platforms.forEach { p ->
            platformCatalogDao.insertIgnore(
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