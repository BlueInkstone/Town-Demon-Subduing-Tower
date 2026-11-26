#!/usr/bin/env bash
# 镇妖塔网关一键烟测：编译 -> 起服务 -> 三个断言 -> 关服务
# 用法（仓库根目录）：bash scripts/quickstart.sh
# 依赖：JDK、Maven、curl、python3（协议门禁，缺失时跳过）
# 退出码：0=全过，1=任一项失败

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

TOKEN='quickstart-token'
PORT=18090
BASE="http://127.0.0.1:${PORT}"
JAR="$ROOT/towndemontower-gateway/target/towndemontower-gateway-0.1.0.jar"
PID=''
FAILURES=()

check() {
  local name="$1" ok="$2" detail="${3:-}"
  if [ "$ok" = "1" ]; then
    printf '  [PASS] %s\n' "$name"
  else
    printf '  [FAIL] %s -- %s\n' "$name" "$detail"
    FAILURES+=("$name")
  fi
}

cleanup() {
  if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; then
    kill "$PID" 2>/dev/null || true
    wait "$PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT

echo '== 1/4 编译 core 到本地仓库 =='
mvn -B -q -f towndemontower-core/pom.xml clean install -DskipTests || {
  echo '  [FAIL] core install'; FAILURES+=('core install'); }

echo '== 2/4 打包网关 =='
mvn -B -q -f towndemontower-gateway/pom.xml clean package || {
  echo '  [FAIL] gateway package'; FAILURES+=('gateway package'); }
[ -f "$JAR" ] || { echo "  [FAIL] jar 不存在: $JAR"; FAILURES+=('jar 不存在'); }

echo '== 3/4 协议门禁 =='
if command -v python3 >/dev/null 2>&1; then
  python3 contract/verify_codes.py 2>&1 | tail -2
  check '协议门禁 69/69' "$([ ${PIPESTATUS[0]} -eq 0 ] && echo 1 || echo 0)" 'verify_codes.py 失败'
else
  echo '  [SKIP] 未找到 python3，跳过协议门禁'
fi

echo '== 4/4 起服务 + 三个断言 =='
INKARMOR_API_TOKEN="$TOKEN" PORT="$PORT" java -jar "$JAR" >/tmp/tower-quickstart.log 2>&1 &
PID=$!

HEALTH=''
for _ in $(seq 1 20); do
  sleep 0.5
  HEALTH="$(curl -s --max-time 2 "$BASE/api/health" 2>/dev/null || true)"
  [ -n "$HEALTH" ] && break
done
case "$HEALTH" in *'"status":"up"'*) check 'health 探活' 1 ;; *) check 'health 探活' 0 "health=$HEALTH" ;; esac

BAD_BODY='{"tool":"deleteData","args":{"rows":"1"},"confidence":0.9,"reason":"缺表参数","expectedEffect":"无","rollbackHint":"无"}'
R1="$(curl -s --max-time 5 -X POST "$BASE/api/evaluate" -H "X-Inkarmor-Token: $TOKEN" -H 'Content-Type: application/json' -d "$BAD_BODY" || true)"
case "$R1" in *'INTENT_FORMAT_INVALID'*) check '缺参数意图被拒' 1 ;; *) check '缺参数意图被拒' 0 "$R1" ;; esac

R3_BODY='{"tool":"deleteData","args":{"table":"test_table","rows":"1"},"confidence":0.9,"reason":"清理测试数据","expectedEffect":"删除一行","rollbackHint":"无"}'
R2="$(curl -s --max-time 5 -X POST "$BASE/api/evaluate" -H "X-Inkarmor-Token: $TOKEN" -H 'Content-Type: application/json' -d "$R3_BODY" || true)"
case "$R2" in *'R3_DENIED'*) check '不可逆工具 R3 拒绝' 1 ;; *) check '不可逆工具 R3 拒绝' 0 "$R2" ;; esac

if [ "${#FAILURES[@]}" -gt 0 ]; then
  printf 'FAILED: %s\n' "${FAILURES[*]}"
  exit 1
fi
echo 'ALL CHECKS PASSED'
exit 0
