#!/usr/bin/env bash
# 本机首次 setup:生成项目稳定 debug keystore。
# 运行后:
#   - keystore/debug.keystore 文件存在
#   - 可以 `git status` 确认在 .gitignore
#   - 本机 ./gradlew assembleDebug / assembleRelease 都用这个 keystore 签
#
# 如果你之前装过 v0.5.0 但因为 keystore 轮换要卸装重装,先跑这个脚本,
# 再 `adb install -r accountbook-0.5.0.apk` (用本机编出来的 + GitHub release 上
# 的 SHA-1 一致,就能直接覆盖,数据不丢)。

set -euo pipefail

KEYSTORE_DIR="$(cd "$(dirname "$0")" && pwd)"
KEYSTORE_FILE="$KEYSTORE_DIR/debug.keystore"

if [ -f "$KEYSTORE_FILE" ]; then
  echo "已经存在 keystore/debug.keystore,不覆盖。"
  echo "当前 SHA-256:"
  keytool -list -v -keystore "$KEYSTORE_FILE" -storepass android -alias androiddebugkey 2>/dev/null \
    | grep -E "SHA1|SHA256" | sed 's/^/  /'
  echo
  echo "如果想重新生成,先删 keystore/debug.keystore 再跑。"
  exit 0
fi

mkdir -p "$KEYSTORE_DIR"

keytool -genkeypair -v \
  -keystore "$KEYSTORE_FILE" \
  -alias androiddebugkey \
  -keyalg RSA -keysize 2048 \
  -validity 36500 \
  -storepass android -keypass android \
  -dname "C=US, O=Android, CN=Android Debug" 2>&1 | grep -v "^$"

echo
echo "✓ 已生成 keystore/debug.keystore"
echo
echo "SHA-256:"
keytool -list -v -keystore "$KEYSTORE_FILE" -storepass android -alias androiddebugkey 2>/dev/null \
  | grep -E "SHA1|SHA256" | sed 's/^/  /'
echo
echo "下一步:"
echo "  1. 上传到 GitHub Actions secret(让 CI 用同一个 keystore):"
echo "       gh secret set KEYSTORE_BASE64 < keystore/debug.keystore"
echo "  2. 本机编译:"
echo "       ./gradlew :app:assembleDebug"
echo "  3. 装到设备:"
echo "       adb install -r app/build/outputs/apk/debug/app-debug.apk"