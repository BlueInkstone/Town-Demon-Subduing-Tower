// 墨铠网关最小 Node 客户端示例（docs/38 改动⑩）：仅用内置 https，不发布 SDK 包。
// 运行：node example.js
const https = require("https");

const url = process.env.INKARMOR_GATEWAY_URL || "http://127.0.0.1:8080";
const token = process.env.INKARMOR_API_TOKEN || "";

const intent = {
  tool: "deleteData",
  args: { table: "tmp" },
  confidence: 0.9,
  reason: "清理过期表",
  expectedEffect: "表被删除",
  rollbackHint: "无",
};

const body = JSON.stringify(intent);
const u = new URL(url + "/api/evaluate");

const req = https.request(
  {
    hostname: u.hostname,
    port: u.port || 443,
    path: u.pathname,
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-Inkarmor-Token": token,
      "Content-Length": Buffer.byteLength(body),
    },
  },
  (res) => {
    let data = "";
    res.on("data", (c) => (data += c));
    res.on("end", () => {
      const env = JSON.parse(data);
      if (res.statusCode !== 200 || !env.data || !env.data.success) {
        // 拒绝 / 不可达：fail-closed
        console.log("裁决拒绝，不执行：", data);
        process.exit(1);
      }
      console.log("裁决允许，执行原操作。code=", env.data.code);
    });
  }
);

req.on("error", (e) => {
  // 网关不可达：fail-closed，绝不静默放行
  console.log("网关不可达，拒绝执行：", e.message);
  process.exit(1);
});

req.write(body);
req.end();
