# AccountBook · 账号簿

> 用手机号 / 邮箱注册了哪些互联网账号?本应用帮你记住这件事。

**100% 本地存储 · 端到端加密 · 完全离线 · 单人使用**

[![Build](https://github.com/hzbcdut/accountbook/actions/workflows/build.yml/badge.svg)](https://github.com/hzbcdut/accountbook/actions/workflows/build.yml)
[![Release](https://github.com/hzbcdut/accountbook/actions/workflows/release.yml/badge.svg)](https://github.com/hzbcdut/accountbook/actions/workflows/release.yml)
[![Latest](https://img.shields.io/github/v/release/hzbcdut/accountbook)](https://github.com/hzbcdut/accountbook/releases/latest)

> ⚠️ Phase 1 (v0.1.1) — 项目骨架已就绪,SQLCipher 加密的 Room 数据库已通,
> 平台目录 43 个 + 标签 6 个种子已写入,**模拟器实测启动 + 种子数据 OK**。
> 真正的 UI 录入/编辑/搜索/导入导出 在 Phase 2+。

## 为什么做这个

- 密码管理器(1Password/Bitwarden)适合存密码,**不适合回答"我在哪些平台用 13800138000 注册过"**
- 平台太多,时不时会忘记"这个账号是邮箱还是手机" / "哪年注册的" / "绑定的哪个邮箱"
- 不想把这些信息交给云端,也不希望被某个商业密码管理器厂商看到

## 设计目标

- 字段极简:`平台 · 账号 · 注册时间` (+ 标签 + 备注)
- 一行一个 (平台, 账号):同一平台多个账号分开记
- 加密 Room DB + 生物识别/PIN 解锁 + 12 词助记词备份
- 标签分组(`社交/购物/金融/工作/工具/其他`),便于搜索筛选
- 平台名自动补全(内置 43 个常见平台,可继续输入新名)
- 导入导出 CSV / JSON
- 周自动备份 + 通知栏一键锁定

## 技术栈

- Android 13+,minSdk 26,targetSdk 36
- Kotlin 2.0.21,Jetpack Compose,Material 3
- Room + SQLCipher(全库加密)
- Hilt + Coroutines + Flow + Compose Navigation
- DataStore Preferences + EncryptedSharedPreferences
- 严格离线:`<uses-permission android:name="android.permission.INTERNET" />` **不申请**

## 路线图

- [x] **Phase 1** — 项目骨架 / Room + SQLCipher / 种子 / 主题 / Home 占位
- [ ] **Phase 2** — 分组列表 / 搜索筛选排序 / 详情页 / BottomSheet 编辑
- [ ] **Phase 3** — 导入导出 CSV/JSON / 冲突合并 UI / 自动备份
- [ ] **Phase 4** — 解锁 / 生物识别 / PIN / 助记词 / 紧急锁 Tile & Shortcut
- [ ] **Phase 5** — 首次启动引导 / 设置页 / 多语言 / 关于页
- [ ] **Phase 6** — 单元测试 + Compose UI Test + 打包 / 文档

## 构建

```bash
git clone https://github.com/hzbcdut/accountbook.git
cd accountbook
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

> ⚠️ debug APK 用的是 Android Studio 默认 debug keystore 签名,生产环境使用请配置 `signingConfigs` 替换为正式 keystore。

## CI/CD

GitHub Actions 在以下时机自动触发:

| Workflow | 触发 | 行为 |
|---|---|---|
| [`.github/workflows/build.yml`](.github/workflows/build.yml) | push / PR 到 main | assembleDebug + unit test + 上传 APK artifact |
| [`.github/workflows/release.yml`](.github/workflows/release.yml) | push tag `v*` | assembleDebug + 创建 GitHub Release + 上传 APK + SHA256 |

发布新版本只需:

```bash
git tag v0.2.0
git push origin v0.2.0
```

CI 会自动构建 + 创建 release + 上传 APK,无需手动跑 `gh release create`。

## 隐私

- 所有数据只存在设备本地的加密数据库(`/data/data/nt.ddeoid.accountbook/databases/accountbook.db`)
- 不申请 INTERNET 权限,无任何网络通信
- 备份导出由用户主动触发,文件落到 `Documents/AccountBook/backups/`
- 不接入任何第三方 SDK、不含任何埋点

## License

TBD (Phase 6 选定)