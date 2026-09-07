package nt.ddeoid.accountbook

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import nt.ddeoid.accountbook.data.seed.SeedDataInitializer
import javax.inject.Inject

/**
 * 应用入口。
 *
 * - [HiltAndroidApp] 触发 Hilt 组件图生成。
 * - sqlcipher-android 4.6.x 通过 ReLinker 自动加载 native 库,无需显式调用 loadLibs。
 * - 种子数据(6 个标签 + 精简平台目录)在 IO 协程里写入,不阻塞主线程。
 */
@HiltAndroidApp
class AccountBookApp : Application() {

    @Inject
    lateinit var seedDataInitializer: SeedDataInitializer

    override fun onCreate() {
        super.onCreate()
        seedDataInitializer.initialize()
    }
}