# AccountBook · 账号簿

> 用手机号 / 邮箱注册了哪些互联网账号?本应用帮你记住这件事。

**100% 本地存储 · 端到端加密 · 完全离线 · 单人使用**

[![Build](https://github.com/hzbcdut/accountbook/actions/workflows/build.yml/badge.svg)](https://github.com/hzbcdut/accountbook/actions/workflows/build.yml)
[![Release](https://github.com/hzbcdut/accountbook/actions/workflows/release.yml/badge.svg)](https://github.com/hzbcdut/accountbook/actions/workflows/release.yml)
[![Latest](https://img.shields.io/github/v/release/hzbcdut/accountbook)](https://github.com/hzbcdut/accountbook/releases/latest)

> ✨ **v0.4.0** — 应用锁 + 助记词恢复 + 加密整库备份 + 紧急锁
> 全部 Phase 1-4 功能齐备,**202 单测全绿,端到端 smoke-test 已通过**。
> 详见 [路线图](#路线图) 与 [`docs/SMOKE-TEST-0.4.0.md`](docs/SMOKE-TEST-0.4.0.md)。

## 为什么做这个

- 密码管理器(1Password / Bitwarden)适合存密码,**不适合**回答
  "我在哪些平台用 13800138000 注册过"
- 平台太多,时不时会忘记"这个账号是邮箱还是手机" / "哪年注册的" /
  "绑定的哪个邮箱"
- 不想把这些信息交给云端,也不希望被某个商业密码管理器厂商看到
- 不想依赖 Google / Apple / 任何第三方账号体系

## 设计目标

- **字段极简**:`平台 · 账号 · 注册时间`(+ 标签 + 备注)
- **一行一条**:同一平台多个账号分开记
- **加密 Room DB**:SQLCipher 4.6.1 整库加密,密钥由用户 PIN + BIP39 熵派生
- **生物识别 / PIN 解锁**:`BIOMETRIC_STRONG | DEVICE_CREDENTIAL` 双重门
- **12 词助记词恢复**:BIP39 英文词表,离线生成,App 不存
- **进程级超时锁**:APP 进后台 4 档时间(tier) 后自动锁
- **FLAG_SECURE**:窗口防截屏 / 任务快照
- **整库加密导出**:除了明文 JSON / CSV,还支持把 SQLCipher `.db` 文件
  整库备份(适用于换机)
- **紧急锁定**:下拉控制中心 tile + 长按 launcher 图标,一键锁屏
- **剪贴板加固**:复制账号 60s 自动清空 + 离开 App 即清空
- **标签分组**(社交 / 购物 / 金融 / 工作 / 工具 / 其他),便于搜索筛选
- **平台名自动补全**:内置常见平台,可继续输入新名
- **导入导出 CSV / JSON**

## 技术栈

- **平台**:Android 8.0+ (minSdk 26),targetSdk 36,compileSdk 36
- **语言 / UI**:Kotlin 2.0.21,Jetpack Compose,Material 3
- **存储**:Room 2.7.0 + SQLCipher 4.6.1 + DataStore Preferences + EncryptedSharedPreferences
- **架构**:Hilt 2.57 + Coroutines + Flow + Compose Navigation
- **密码学**:Tink 风格分层 — BIP39 熵 → HKDF → AES-GCM wrapping,PBKDF2 600k 迭代
- **离线**:`<uses-permission android:name="android.permission.INTERNET" />` **不申请**
- **测试**:JUnit4 + mockk + Turbine + kotlinx-coroutines-test,**202 单测**

## 路线图

| Phase | 版本 | 状态 | 内容 |
|---|---|---|---|
| 1 | v0.1.1 | ✅ | 骨架 / Room + SQLCipher / 种子 / 主题 / Home 占位 |
| 2 | v0.2.0 | ✅ | 分组列表 / 搜索筛选排序 / 详情页 / BottomSheet 编辑 |
| 3 | v0.3.0 | ✅ | 导入导出 CSV/JSON / 冲突合并 / 自动备份 |
| 4 | v0.4.0 | ✅ | 应用锁 / 生物识别 / PIN / 助记词 / 迁移 / Tile / 整库备份 / 进程锁 / 剪贴板加固 / FLAG_SECURE |
| 5 | _未来_ | ⬜ | 首启引导 / 关于页 / 多语言扩展 / 平台目录云同步(纯本地,可选) |
| 6 | _未来_ | ⬜ | Compose UI Test / 性能 / 文档站 |

## v0.4.0 新功能一览

### 应用锁(4 状态机)
`NeedsSetup` → `Migrating` → `Locked` ↔ `Unlocked` + `Disabled`(跳过设置)

- **SetupWizard**(6 步):Welcome → PIN → Confirm PIN → 生物识别开关 → 12 词展示 → 验证
- **MigrationWizard**(升级用户):v0.3.0 老库 `PRAGMA rekey` 到新主密钥,**就地重加密**
- **LockScreen**:PIN 数字键盘 + 生物识别按钮 + "忘记 PIN"入口
- **Recovery**:12 词助记词输入,带词表 / 数量 / checksum 错误路径提示
- **LockController**:状态机 + 30s 错误冷却 + 4 档 timeout tier(IMMEDIATE / 30s / 5min / NEVER)

### 加密层
- **BIP39 熵**(16 字节)→ **HKDF** → **AES-GCM** 包装的 SQLCipher 主密钥
- **PBKDF2-SHA256 600k** 迭代派生 PIN → wrap key
- **Tink 风格分层**:密钥永远不进 SharedPreferences,只存密文 + IV
- **Android Keystore**:MasterKey 仅在 TEE / StrongBox 里,普通设备 fallback 到软件 Keystore

### 进程生命周期
- **ProcessLifecycleOwner**:`onStart` / `onStop` 钩子驱动"前台 → 解锁 / 后台 → 锁"
- **FLAG_SECURE**:全 Activity 窗口防截屏 / 任务快照
- **SensitiveClipboard**:剪贴板 60s 自动清空 + `MainActivity.onStop` 立即清空
  - Android 13+ `ClipDescription.EXTRA_IS_SENSITIVE` 标记,输入法不预览

### 立即锁定 — 两条路径
- **Long-press shortcut**:长按 launcher 图标 → 弹菜单 → "锁定 AccountBook"
- **Quick Settings Tile**:下拉控制中心 → "立即锁定"tile
- 两条都走 `MainActivity` intent action `LOCK_NOW` → `LockController.lock()`(幂等)

### 加密整库备份(#33)
- "导出加密数据库(整库备份)" 走 SAF,把整个 SQLCipher `.db` 文件复制到用户选的位置
- `PRAGMA wal_checkpoint(TRUNCATE)` 后再复制,确保 WAL 数据落盘
- 换机场景:在旧机导出 → 新机装同版本 App → 用同 PIN 的 KeyVault 解密

### v0.3.0 → v0.4.0 迁移(#30)
- `MigrationMarker` 进程中断保护:`rekey` 前写、`vault-write` 后清
- `LegacyKeyMigrator` 编排:`generate(熵)` → `rekeyer.rekey(old, new)` → `keyVault.initializeWithExistingEntropy(entropy, pin)` → `passphraseProvider.wipe()`
- 中断场景:DB 已重 key 但 vault 未写 → 引导用户从备份恢复(不静默吞)

## 截图

> ⚠️ 因为 `FLAG_SECURE`,emulator 上 `adb screencap` 返回 0 字节——这是设计,
> 不是 bug。截图请用真机 + 系统截图工具,或临时关闭 `FLAG_SECURE` 后截。

## 构建

```bash
git clone https://github.com/hzbcdut/accountbook.git
cd accountbook
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

> ⚠️ debug APK 用 Android Studio 默认 debug keystore 签名。
> 生产环境请配置 `signingConfigs` 替换为正式 keystore。

## 测试

```bash
./gradlew :app:testDebugUnitTest
# 202 个测试,应全绿
```

端到端 smoke-test 清单见 [`docs/SMOKE-TEST-0.4.0.md`](docs/SMOKE-TEST-0.4.0.md)。

## CI/CD

GitHub Actions 在以下时机自动触发:

| Workflow | 触发 | 行为 |
|---|---|---|
| [`.github/workflows/build.yml`](.github/workflows/build.yml) | push / PR 到 main | assembleDebug + unit test + 上传 APK artifact |
| [`.github/workflows/release.yml`](.github/workflows/release.yml) | push tag `v*` | assembleDebug + 创建 GitHub Release + 上传 APK + SHA256 |

发布新版本:

```bash
git tag v0.4.0
git push origin v0.4.0
```

CI 自动构建 + 创建 release + 上传 APK + SHA256。

## 隐私

- 所有数据只存在设备本地的加密数据库
  (`/data/data/nt.ddeoid.accountbook/databases/accountbook.db`)
- **不申请 INTERNET 权限**,无任何网络通信
- 备份导出由用户主动触发,文件落到 SAF 用户选择的位置
- **不接入任何第三方 SDK**,无任何埋点 / 统计 / 崩溃上报
- 助记词:**App 不持久化**,只展示一次让用户抄,丢了找不回来

## 安全约束(机器可验证)

```bash
# 1. 无 INTERNET 权限
grep -E "<uses-permission[^>]*INTERNET" app/src/main/AndroidManifest.xml
# 期望:空输出

# 2. 无任何第三方追踪 / 崩溃 SDK
grep -rE "firebase|crashlytics|google-services|analytics" app/build.gradle.kts
# 期望:空输出

# 3. 无 Google Play Services 依赖
./gradlew :app:dependencies --configuration debugRuntimeClasspath | grep "com.google.android.gms"
# 期望:空输出

# 4. SQLCipher ≥ 4.6.1
grep sqlcipher gradle/libs.versions.toml
# 期望:sqlcipher = "4.6.1"
```

## License

TBD — Phase 6 选定(候选:AGPL-3.0)
