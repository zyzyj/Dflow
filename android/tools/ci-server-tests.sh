#!/bin/sh
# 模拟 CI 里的服务端验证步骤，用独立端口避免与开发中的服务冲突。
set -eu

# 从脚本位置回到项目根（本脚本在 <root>/android/tools/ 下）。
cd "$(dirname "$0")/../.."
echo "工作目录: $(pwd)"

DATA="$(mktemp -d)"
echo "数据目录: $DATA"

# 模拟 CI 写入的构建信息，否则版本检查在无 .git 环境里无法确定本地版本。
if [ -n "${GITHUB_SHA:-}" ]; then
  mkdir -p "${TARGET_APP_DIR:-.}"
  printf '{"commit":"%s","version":"%s","builtAt":"%s"}\n' \
    "$GITHUB_SHA" \
    "$(node -p "require('./package.json').version")" \
    "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    > "${TARGET_APP_DIR:-.}/BUILD_INFO.json"
fi

PORT=4199 DFLOW_DATA_DIR="$DATA" HOST=127.0.0.1 node server.js &
SERVER_PID=$!
trap 'kill $SERVER_PID 2>/dev/null || true' EXIT

for i in $(seq 1 60); do
  if curl -sf http://127.0.0.1:4199/api/state >/dev/null 2>&1; then
    echo "服务已就绪（第 $i 秒）"
    break
  fi
  sleep 1
done

echo "--- 反推链路 ---"
DFLOW_E2E_PORT=4199 DFLOW_DATA_DIR="$DATA" python3 android/tools/e2e-reverse.py

echo "--- 内置 AI 反推 ---"
DFLOW_E2E_PORT=4199 DFLOW_DATA_DIR="$DATA" python3 android/tools/e2e-ai-reverse.py

echo "--- 批量删除 ---"
DFLOW_E2E_PORT=4199 DFLOW_DATA_DIR="$DATA" python3 android/tools/e2e-bulk.py

echo "--- MCP over HTTP（本机认证与工具）---"
DFLOW_E2E_PORT=4199 DFLOW_DATA_DIR="$DATA" python3 android/tools/e2e-api.py

echo "--- reverse_image 两种模式 ---"
DFLOW_E2E_PORT=4199 DFLOW_DATA_DIR="$DATA" python3 android/tools/e2e-reverse-image.py

echo "--- 版本比较（纯函数）---"
node android/tools/test-version.mjs

echo "===== CI 服务端验证全部通过 ====="
