package nt.ddeoid.accountbook.data.local

import android.content.SharedPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [MigrationMarker] 测试 —— 验证:
 *
 * - 默认 [MigrationMarker.inProgress] 是 false
 * - 写入 true 后读出是 true
 * - 写回 false 后读出是 false
 * - 同一 prefs 实例构造第二个 marker,状态保留
 * - reset() 把所有字段清掉
 *
 * 用 in-memory [SharedPreferences] 替 Android 真家伙 —— MigrationMarker 只关心
 * boolean 读写,这层抽象够用。
 */
class MigrationMarkerTest {

    private lateinit var prefs: InMemorySharedPreferences
    private lateinit var marker: MigrationMarker

    @Before
    fun setUp() {
        prefs = InMemorySharedPreferences()
        marker = MigrationMarker(prefs)
    }

    @Test
    fun `default inProgress is false`() {
        assertFalse(marker.inProgress)
    }

    @Test
    fun `setting true makes inProgress true`() {
        marker.inProgress = true
        assertTrue(marker.inProgress)
    }

    @Test
    fun `setting false after true makes inProgress false`() {
        marker.inProgress = true
        marker.inProgress = false
        assertFalse(marker.inProgress)
    }

    @Test
    fun `state persists across new MigrationMarker on same prefs`() {
        marker.inProgress = true

        // 用同一份 prefs 造一个新 marker —— 模拟"应用重启"
        val reborn = MigrationMarker(prefs)
        assertTrue("重启后 inProgress 应当仍为 true", reborn.inProgress)
    }

    @Test
    fun `reset clears everything`() {
        marker.inProgress = true
        marker.reset()
        assertFalse(marker.inProgress)
    }
}

/**
 * 最小的 in-memory [SharedPreferences] —— 只实现 [MigrationMarker] 用到的 [getBoolean] /
 * [edit] / [contains] 这几个方法,以及 Editor 的 [putBoolean] / [remove] / [clear] / [apply]。
 *
 * 这不是 KeyVault 那个 InMemoryPrefs 的复用版,因为那个对 [contains] / [remove] 的语义
 * 略有差异 —— 这个更直白,适合做单点状态测试。
 */
private class InMemorySharedPreferences : SharedPreferences {
    private val map: MutableMap<String, Any?> = mutableMapOf()

    override fun getAll(): MutableMap<String, *> = map

    override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        @Suppress("UNCHECKED_CAST") (map[key] as? MutableSet<String>) ?: defValues
    override fun getInt(key: String?, defValue: Int): Int = (map[key] as? Int) ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = (map[key] as? Long) ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = (map[key] as? Float) ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = (map[key] as? Boolean) ?: defValue
    override fun contains(key: String?): Boolean = map.containsKey(key)
    override fun edit(): SharedPreferences.Editor = EditorImpl(map)
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    private class EditorImpl(private val map: MutableMap<String, Any?>) : SharedPreferences.Editor {
        private val initialKeys: Set<String> = map.keys.toSet()
        private val pending: MutableMap<String, Any?> = map.toMutableMap()
        private val removes: MutableSet<String> = mutableSetOf()
        private var cleared = false

        override fun putString(key: String, value: String?) = also { pending[key] = value; cleared = false }
        override fun putStringSet(key: String, values: MutableSet<String>?) = also { pending[key] = values; cleared = false }
        override fun putInt(key: String, value: Int) = also { pending[key] = value; cleared = false }
        override fun putLong(key: String, value: Long) = also { pending[key] = value; cleared = false }
        override fun putFloat(key: String, value: Float) = also { pending[key] = value; cleared = false }
        override fun putBoolean(key: String, value: Boolean) = also { pending[key] = value; cleared = false }
        override fun remove(key: String) = also { pending.remove(key); removes += key; cleared = false }
        override fun clear() = also {
            pending.clear()
            removes += initialKeys
            cleared = true
        }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() {
            if (cleared) map.clear()
            removes.forEach { map.remove(it) }
            pending.forEach { (k, v) -> map[k] = v }
        }
    }
}
