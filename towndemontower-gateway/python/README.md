# `@inkarmor_guard` —— 墨铠网关 Python 薄客户端

镇妖塔「网关形态」下唯一需要维护的 Python 代码：**一份 JSON 协议 + 一个 ≤200 行的薄包**，
只做打包 `IntentObject`、POST 到网关 `POST /api/evaluate`、按响应阻断。零第三方依赖（只用标准库 `urllib`）。

## 用法

```python
import os
os.environ["INKARMOR_GATEWAY_URL"] = "http://127.0.0.1:8080"
os.environ["INKARMOR_API_TOKEN"] = "你的令牌"   # 必须与网关 INKARMOR_API_TOKEN 一致

from inkarmor_guard import inkarmor_guard

@inkarmor_guard("deleteData", confidence=0.9, reason="清理过期表")
def drop_table(table: str):
    # 只有网关裁决为「允许」才会执行到这里
    ...
```

- 裁决为「允许」：函数正常执行；
- 裁决为「拒绝」/「待审批」：抛 `InkarmorDenied`；
- 网关不可达 / 超时 / 鉴权失败（401/403）：抛 `InkarmorUnavailable`（fail-closed，绝不静默放行）。

## 环境变量

| 变量 | 默认 | 含义 |
|---|---|---|
| `INKARMOR_GATEWAY_URL` | `http://127.0.0.1:8080` | 网关地址 |
| `INKARMOR_API_TOKEN` | 空 | 与网关 `INKARMOR_API_TOKEN` 一致的令牌 |
| `INKARMOR_TIMEOUT` | `3.0` | 请求超时（秒） |

## 重要边界

网关是「按约定前置询问」：它只给裁决，**挡不住「不调网关就直接执行」的调用方**。
不要把本包当成进程内强制——强制力上限 = 调用方遵守协议的程度（见 `docs/16`、`docs/38`）。

测试：`python -m unittest -v test_inkarmor_guard`（用标准库，无 pytest 依赖）。
