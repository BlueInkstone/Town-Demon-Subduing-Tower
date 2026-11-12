#!/usr/bin/env bash
# GW-04 镜像冒烟：构建 → 启动 → curl 一次 → 校验落盘审计。
# 一条命令可复现（需 docker 与 curl）。
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
IMAGE="inkarmor-gateway:0.1.0"
TOKEN="${INKARMOR_API_TOKEN:-smoke-token}"
AUDIT_DIR="$(mktemp -d)"
CONTAINER="inkarmor-smoke"

cleanup() { docker rm -f "$CONTAINER" >/dev/null 2>&1 || true; }
trap cleanup EXIT

echo "==> [1/4] 构建镜像"
docker build -f towndemontower-gateway/Dockerfile -t "$IMAGE" "$REPO_ROOT"

echo "==> [2/4] 启动容器"
docker run -d --name "$CONTAINER" -p 8080:8080 \
  -e INKARMOR_API_TOKEN="$TOKEN" \
  -v "$AUDIT_DIR:/app/data/audit" \
  "$IMAGE"

# 等端口就绪
for i in $(seq 1 30); do
  if curl -s -o /dev/null "http://127.0.0.1:8080/api/evaluate" 2>/dev/null; then break; fi
  sleep 1
done

echo "==> [3/4] 发一次裁决请求（R3 工具 → 应被拒绝）"
RESP=$(curl -s -X POST "http://127.0.0.1:8080/api/evaluate" \
  -H "Content-Type: application/json" \
  -H "X-Inkarmor-Token: $TOKEN" \
  -d '{"tool":"deleteData","args":{"table":"tmp"},"confidence":0.9,"reason":"清理","expectedEffect":"表被删","rollbackHint":"无"}')
echo "响应：$RESP"

echo "==> [4/4] 校验审计已落盘"
if [ -s "$AUDIT_DIR/gateway.log" ]; then
  echo "审计文件非空，落盘成功："
  tail -n 3 "$AUDIT_DIR/gateway.log"
else
  echo "审计未落盘！" >&2
  exit 1
fi

echo "==> GW-04 冒烟通过"
