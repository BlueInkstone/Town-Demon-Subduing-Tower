#!/usr/bin/env bash
# 墨铠网关 curl 最小示例（docs/38 改动⑩）。网关是「按约定前置询问」，调用方须自行决定是否执行。
set -euo pipefail

URL="${INKARMOR_GATEWAY_URL:-http://127.0.0.1:8080}/api/evaluate"
TOKEN="${INKARMOR_API_TOKEN:-}"

RESP=$(curl -s -w '\n%{http_code}' -X POST "$URL" \
  -H "Content-Type: application/json" \
  -H "X-Inkarmor-Token: $TOKEN" \
  -d '{"tool":"deleteData","args":{"table":"tmp"},"confidence":0.9,"reason":"清理过期表","expectedEffect":"表被删除","rollbackHint":"无"}')

BODY=$(printf '%s\n' "$RESP" | sed '$d')
CODE=$(printf '%s\n' "$RESP" | tail -n 1)

if [ "$CODE" != "200" ] || ! printf '%s' "$BODY" | grep -q '"success":true'; then
  echo "裁决拒绝 / 鉴权失败（HTTP $CODE），不执行："
  echo "$BODY"
  exit 1
fi
echo "裁决允许，执行原操作："
echo "$BODY"
