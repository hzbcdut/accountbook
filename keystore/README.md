# 稳定 Debug Keystore

> v0.5.0+ 起,所有 release / debug APK 都用本目录下的 `debug.keystore` 签名。
> 这保证相邻版本签名稳定,用户**不需要卸装**就能升级。

## 为什么不用 `~/.android/debug.keystore`

CI runner 是 `ubuntu-latest`,Gradle 在 Linux 上**找不到 debug.keystore 就自动生成一个**。
每次 CI run 的 keystore 不同 → 同 release 频道相邻版本签名不匹配 → Android 拒绝覆盖安装。

## 本机 setup

```bash
# 一次性(从项目根目录)
./keystore/setup.sh
```

脚本会:
1. 用 `keytool` 生成 100 年有效的 RSA 2048 自签名证书
2. alias=`androiddebugkey`,password=`android`(与 Android 默认 debug keystore 完全一致)
3. DN=`C=US, O=Android, CN=Android Debug`(与 Android 默认一致)
4. 写入 `keystore/debug.keystore`

## GitHub Actions 注入

CI 不读这个文件(在 .gitignore 里),从 secret `KEYSTORE_BASE64` 还原:

```bash
# 设置 / 更新 secret(从 keystore 文件生成 base64)
gh secret set KEYSTORE_BASE64 < keystore/debug.keystore
```

secret 名固定 = `KEYSTORE_BASE64`,值 = base64 编码的 keystore 二进制。release.yml 在 build 前 decode 到同一路径。

## 校验指纹

```bash
keytool -list -v -keystore keystore/debug.keystore -storepass android -alias androiddebugkey
```

期望 SHA-256:`08:9B:EB:36:7C:35:C4:D6:A4:FE:0E:3A:14:E0:73:16:21:9C:26:79:B2:A3:D6:49:5B:5E:BD:D3:F8:F3:82:98`

任何本机 / CI 编译出来的 APK 签名 SHA-1 必须等于:
`34:FD:D5:76:7C:A8:61:63:C4:85:30:C0:54:47:D0:64:61:C1:3E:6A`

校验 APK:
```bash
APKSIGNER=$ANDROID_HOME/build-tools/30.0.3/apksigner
$APKSIGNER verify --print-certs <apk> | grep "SHA-1"
```

## 安全约束

- `keystore/` 已在 `.gitignore`,不进 git
- 别名 / 密码公开(`androiddebugkey` / `android`)与 Android 默认 debug keystore 相同 —— 这就是 debug-keystore 该有的样子,不要换更强密码
- 真要换,记得同步更新 GitHub Actions secret + 所有本地开发机 + 已有用户全部要重装
- **100% local app** 不上 Google Play,签名只用于"覆盖升级"这一件事,不用于商业信任