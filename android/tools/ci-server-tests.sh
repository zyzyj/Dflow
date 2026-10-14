#!/bin/sh
# 模拟 CI 里的服务端验证步骤，用独立端口避免与开发中的服务冲突。
set -eu

# 从脚本位置回到项目根（本脚本在 <root>/android/tools/ 下）。
cd "$(dirname "$0")/../.."
pwd

DATA="$(mktemp -d)"
echo "数据目录: $DATA"

PORT=4199 DFLOW_DATA_DIR="$DATA" HOST=127.0.0.1 node server.js &
SERVER_PID=$!
trap 'kill $SERVER_PID 2>/dev/null || true' EXIT

for i in $(seq 1 40); do
  if curl -sf http://127.0.0.1:4199/api/state >/dev/null 2>&1; then
    echo "服务已就绪（第 $i 秒）"
    break
  fi
  sleep 1
done

DFLOW_E2E_PORT=4199 DFLOW_DATA_DIR="$DATA" python3 android/tools/e2e-reverse.py
DFLOW_E2E_PORT=4199 DFLOW_DATA_DIR="$DATA" python3 android/tools/e2e-ai-reverse.py

echo "===== CI 服务端验证全部通过 ====="
