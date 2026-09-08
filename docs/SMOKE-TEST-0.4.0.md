# AccountBook v0.4.0 — Emulator 端到端验收清单

本清单覆盖 Phase 4 全部新功能,**人工**在 emulator 上走一遍即可。
每条都标了"通过的标准",勾完即代表 v0.4.0 可以发版。

> **为什么是清单而不是自动化脚本?**
> Phase 4 引入的核心特性都是 UI/用户驱动(SetupWizard 输入 PIN + 抄 12 词、
> LockScreen 解锁、生物识别弹窗、Tile 长按启动 app、剪贴板离开 app 即清)——
> 这些都需要人眼判断。FLAG_SECURE 又把自动化截图这条路堵了,本就该人走。

---

## 0. 前置

```bash
# 全新 emulator (Pixel 4 API 34 即可),不需要 wipe data
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm dump nt.ddeoid.accountbook.debug | grep "requested permissions"
# 期望只能看到:USE_BIOMETRIC / USE_FINGERPRINT / POST_NOTIFICATIONS
# 看到 INTERNET = 安全约束破了,立刻停
```

## 1. Fresh Install 路径

| # | 步骤 | 通过标准 |
|---|---|---|
| 1.1 | 启动 app | 直接进入 **SetupWizard Step 1**(welcome 页,不是 LockScreen) |
| 1.2 | 阅读 welcome 页 | 标题"保护你的账号簿",有"开始设置"+"暂时跳过"两个按钮 |
| 1.3 | 点击"暂时跳过" | 直接进 Home 空状态(底部按钮新增账号) |
| 1.4 | 退到桌面 → 重新启动 app | 仍然是 Home,**不**回到 SetupWizard(因为"跳过"已经存了 Disabled 标记) |
| 1.5 | 卸载重装(清掉 SharedPreferences)→ 启动 | 又是 SetupWizard Step 1 |
| 1.6 | 点击"开始设置" | 进入 Step 2 (PIN 输入) |
| 1.7 | 输入 PIN "1234" → 下一步 | 进入 Step 3 (再次输入 PIN) |
| 1.8 | 输入 "5678"(故意错)→ 下一步 | 提示"两次输入不一致" |
| 1.9 | 改输入 "1234" → 下一步 | 进入 Step 4 (生物识别开关) |
| 1.10 | 勾选"启用生物识别"(emulator 上其实无法用,不要紧)→ 下一步 | 进入 Step 5 (12 个助记词展示) |
| 1.11 | 抄下 12 词(任何纸/笔记 app 都行)→ 勾选"我已抄好" | 进入 Step 6 (验证第 N 个词) |
| 1.12 | 输入错的词 → 校验 | 提示"词不对,检查一下抄写" |
| 1.13 | 输入对的词 → 校验 | 提示"Finishing setup…",自动跳到 Home |
| 1.14 | Home 空状态检查 | FAB + 搜索框 + 设置按钮,无账号记录 |

## 2. LockScreen 解锁路径

| # | 步骤 | 通过标准 |
|---|---|---|
| 2.1 | 按 HOME 退到桌面,等 5 秒 | 不应该立刻锁(默认 IMMEDIATE 锁屏策略,但 NeedsSetup 不会锁) |
| 2.2 | (如果已设 PIN)按 HOME → 等 10 秒 → 重新启动 app | LockScreen 出现,要求 PIN 或生物识别 |
| 2.3 | 输入错的 PIN | "PIN 错误,请重试"toast + 30 秒倒计时(快速错 5 次) |
| 2.4 | 等 30 秒后输入对的 PIN | 解锁成功,进 Home |
| 2.5 | (如果生物识别可用)LockScreen 上点"使用生物识别" | 系统弹窗出现 |
| 2.6 | (如果生物识别可用)完成系统弹窗 | 解锁成功 |

## 3. 助记词恢复路径

| # | 步骤 | 通过标准 |
|---|---|---|
| 3.1 | LockScreen 上点"忘记 PIN?用助记词恢复" | 进入 Recovery 页 |
| 3.2 | 输入第 1 个词 "abc"(故意错)→ 提交 | 提示"第 1 个词 abc 不在词表里" |
| 3.3 | 输入 11 个词 + 1 个垃圾词 → 提交 | 提示"应为 12 个词,实际 11 个" |
| 3.4 | 输入顺序错位的 12 词 → 提交 | 提示"助记词无效:检查是否漏词、顺序错、或抄错了某个词" |
| 3.5 | 输入正确的 12 词 → 提交 | 直接解锁进 Home |

## 4. 进程生命周期 (FLAG_SECURE + 锁屏 + 剪贴板)

| # | 步骤 | 通过标准 |
|---|---|---|
| 4.1 | adb shell screencap -p > /sdcard/x.png && adb pull /sdcard/x.png | **0 字节文件**(FLAG_SECURE 生效) |
| 4.2 | Home 上点某条账号 → 进 Detail → 点"复制账号" | snackbar 提示"账号已复制到剪贴板" |
| 4.3 | `adb shell cmd clipboard get-primary --user 0` (API 33+) | 看到刚复制的账号 |
| 4.4 | 按 HOME 退到桌面,等 5 秒 | (剪贴板立即被 SensitiveClipboard.clearNow 清掉) |
| 4.5 | 再 `cmd clipboard get-primary` | 提示空 / 异常("No clipboard content"),**不再**能看到账号 |
| 4.6 | 重新启动 app → 等待 10 秒 → 按 HOME → 等 1 分钟 | LockScreen 出现 |
| 4.7 | (如果用 PROCESS_TIMEOUT_FIVE_MINUTES)在 Phase 4 #32 加的测试里已覆盖 4 个 timeout tier,不需要这里手动跑 |

## 5. 立即锁定 — Shortcut + Tile 两条路径

| # | 步骤 | 通过标准 |
|---|---|---|
| 5.1 | 长按 launcher 图标 → 弹出菜单 → 点"锁定 AccountBook" | 立刻 LockScreen,要求 PIN |
| 5.2 | 解锁 → Home → 下拉控制中心 → 找到 AccountBook tile | tile 显示"立即锁定",label 正确 |
| 5.3 | 点击 tile | 立刻 LockScreen(跟 long-press shortcut 行为一致) |
| 5.4 | tile 长按 | 启动 app(到 Home / LockScreen 取决于当前状态) |
| 5.5 | (可选) adb 模拟点击:`adb shell am start -a nt.ddeoid.accountbook.action.LOCK -n nt.ddeoid.accountbook.debug/nt.ddeoid.accountbook.MainActivity` | logcat 出现 `MainActivity: Lock shortcut triggered → lock()` |

## 6. 数据迁移路径(v0.3.0 → v0.4.0)

需要先在 v0.3.0 装一遍、写几条数据,然后升级 v0.4.0:

```bash
# 1. 装 v0.3.0,写 5 条测试账号(任何平台都行)
git checkout v0.3.0 && ./gradlew :app:installDebug
# 手动建 5 条

# 2. 切回 v0.4.0
git checkout main && ./gradlew :app:installDebug
```

| # | 步骤 | 通过标准 |
|---|---|---|
| 6.1 | 启动 v0.4.0 (over install) | 进入 **Migration Wizard**(只输入 PIN,没有 12 词) |
| 6.2 | 输入新 PIN → 再次输入 → 确认 | "正在重新加密数据库,请稍候…" |
| 6.3 | 等待完成 | 直接进 Home,5 条老账号全部保留 |
| 6.4 | `adb shell run-as nt.ddeoid.accountbook.debug cat shared_prefs/accountbook_secure_prefs.xml` | 没有 `db_passphrase_b64` 字段(已 wipe) |
| 6.5 | `adb shell run-as nt.ddeoid.accountbook.debug cat shared_prefs/accountbook_migration_marker.xml` | `<boolean name="in_progress" value="false" />` |
| 6.6 | 重启 app | LockScreen,输入新 PIN → 解锁,5 条老数据仍在 |
| 6.7 | 卸载重装 v0.4.0(fresh install)→ 启动 | 走标准 SetupWizard,**不**走 Migration(因为没 legacy 数据) |

## 7. 加密数据库整库导出(#33)

| # | 步骤 | 通过标准 |
|---|---|---|
| 7.1 | 进 Settings → "导出加密数据库(整库备份)" | SAF 文件选择器弹出 |
| 7.2 | 选个文件名,保存 | snackbar "加密数据库已导出:0.0X MB"(数据少时是 0.0X MB,正常) |
| 7.3 | 用 SQLCipher CLI 或别的方式尝试用错密码打开导出的 .db | 打不开(因为文件用的是 master key 加密) |
| 7.4 | 卸载 app 重装 → 装一份新装的 v0.4.0 → 进 Settings → "从备份导入"(Phase 5 加,这里只验导出) | 暂不验证 |

## 8. UI 主题切换(回归)

| # | 步骤 | 通过标准 |
|---|---|---|
| 8.1 | Settings → 外观 → 跟随系统 / 浅色 / 深色 / AMOLED 纯黑 | 切换后主题立即生效,无白屏闪烁 |
| 8.2 | 系统切换深色模式 | "跟随系统"模式下主题跟随变化 |

## 9. 主流程回归(防新代码破坏老功能)

| # | 步骤 | 通过标准 |
|---|---|---|
| 9.1 | 新建账号 → 填平台 / 账号 / 注册时间 / 备注 → 保存 | 列表里出现新账号 |
| 9.2 | 搜索框输入平台名 | 列表过滤 |
| 9.3 | 点账号进 Detail → 编辑 → 保存 | 改动保存 |
| 9.4 | Detail 上点"复制账号" | snackbar 提示(同时 SensitiveClipboard 启动 60s 清空 + onStop 立即清) |
| 9.5 | Detail 上"标记停用"→ 回到列表 | 账号角标变"已停用" |
| 9.6 | Detail 上"删除账号"→ 确认 | 列表里账号消失 |
| 9.7 | Settings → 导出 JSON → 保存 | snackbar "导出完成:写入 N 条账号" |
| 9.8 | 删几条账号 → 导出 JSON → 选之前导出的文件导入 | snackbar "导入完成:新增 X · 跳过 Y" |

## 10. 安全约束(机器验证,不需要人工)

```bash
# 10.1 没有 INTERNET 权限(已在本文件 §0 覆盖)
# 10.2 Manifest 不含任何 <uses-permission android:name="android.permission.INTERNET" />
grep -rn "INTERNET" app/src/main/AndroidManifest.xml
# 期望:无输出

# 10.3 build.gradle.kts 不带 google-services / firebase / analytics / crashlytics 等
grep -rE "firebase|crashlytics|google-services|analytics" app/build.gradle.kts
# 期望:无输出

# 10.4 第三方依赖不应带 com.google.android.gms (这是 Play Services,会拉 Google 服务)
./gradlew :app:dependencies | grep "com.google.android.gms"
# 期望:无输出

# 10.5 SQLCipher 版本必须是 ≥ 4.6.1
grep "sqlcipher" app/build.gradle.kts
# 期望:net.zetetic:sqlcipher-android:4.6.1 或更新
```

## 11. 跑过的自动化测试(必须全绿)

```bash
./gradlew :app:testDebugUnitTest
# 期望:202 测试全部通过(Phase 1-4 累计)

./gradlew :app:assembleDebug
# 期望:无警告;deprecated API 仅限 TileService.startActivityAndCollapse 一处(已 @Suppress)
```

---

## 通过标准总结

- §1-§9 全部勾完 = UI/UX 端到端合格
- §10 全部命令无输出 = 安全约束未被破坏
- §11 全绿 = 自动化测试覆盖到位

勾完即可发 v0.4.0 tag。
