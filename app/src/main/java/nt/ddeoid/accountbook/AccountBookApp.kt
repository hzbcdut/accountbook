package nt.ddeoid.accountbook

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import nt.ddeoid.accountbook.data.seed.SeedDataInitializer
import javax.inject.Inject

/**
 * 应用入口。
 *
 * - [HiltAndroidApp] 触发 Hilt 组件图生成。
 * - **必须显式 `System.loadLibrary("sqlcipher")`** —— sqlcipher-android 4.6.1 移除了
 *   `SQLiteDatabase.loadLibs(Context)` 和 ReLinker 自动加载,需要应用自己 load `libsqlcipher.so`。
 *   不显式 load 会导致 Room 的 InvalidationTracker 在抢在前面触发 nativeOpen → UnsatisfiedLinkError。
 * - 种子数据(6 个标签 + 精简平台目录)在 IO 协程里写入,不阻塞主线程。
 */
@HiltAndroidApp
class AccountBookApp : Application() {

    @Inject
    lateinit var seedDataInitializer: SeedDataInitializer

    override fun onCreate() {
        super.onCreate()
        try {
            System.loadLibrary("sqlcipher")
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.e("AccountBookApp", "loadLibrary('sqlcipher') failed", e)
            throw e
        }
        seedDataInitializer.initialize()
    }
}