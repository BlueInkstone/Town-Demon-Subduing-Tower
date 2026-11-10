import { HashChainAuditStore, FileAuditStore, computeHash } from "../src/audit";
import { existsSync, readFileSync, rmSync } from "fs";

describe("HashChainAuditStore", () => {
  test("追加记录", () => {
    const store = new HashChainAuditStore();
    const id = store.append("RISK_EVALUATED", { tool: "test" });
    expect(Number(id)).toBe(0);
    expect(store.size()).toBe(1);
  });

  test("链验证", () => {
    const store = new HashChainAuditStore();
    store.append("A", { n: 1 });
    store.append("B", { n: 2 });
    expect(store.verify()).toBe(true);
  });

  test("哈希长度 64", () => {
    const store = new HashChainAuditStore();
    store.append("A", { n: 1 });
    store.append("B", { n: 2 });
    const records = store.all();
    expect(records[0].hash.length).toBe(64); // SHA-256 hex
    expect(store.verify()).toBe(true);
  });

  test("prevHash 链接", () => {
    const store = new HashChainAuditStore();
    store.append("A", { n: 1 });
    store.append("B", { n: 2 });
    const records = store.all();
    expect(records[1].prevHash).toBe(records[0].hash);
  });
});

describe("FileAuditStore", () => {
  const tmp = `${process.cwd()}/tmp_file_audit.jsonl`;

  afterEach(() => {
    if (existsSync(tmp)) rmSync(tmp);
  });

  test("追加并落盘", () => {
    const store = new FileAuditStore(tmp);
    const id = store.append("SKILL_INVOKED", { tool: "x" });
    expect(Number(id)).toBe(0);
    expect(existsSync(tmp)).toBe(true);
  });

  test("链验证", () => {
    const store = new FileAuditStore(tmp);
    store.append("A", { n: 1 });
    store.append("B", { n: 2 });
    expect(store.verify()).toBe(true);
  });

  test("重载已有记录", () => {
    const s1 = new FileAuditStore(tmp);
    s1.append("A", { n: 1 });
    s1.append("B", { n: 2 });

    const s2 = new FileAuditStore(tmp);
    expect(s2.size()).toBe(2);
    expect(s2.verify()).toBe(true);
  });
});

describe("computeHash", () => {
  test("确定性", () => {
    const h1 = computeHash(0, "A", { k: 1 }, "2024-01-01T00:00:00Z", "0".repeat(64));
    const h2 = computeHash(0, "A", { k: 1 }, "2024-01-01T00:00:00Z", "0".repeat(64));
    expect(h1).toBe(h2);
  });

  test("不同输入不同哈希", () => {
    const h1 = computeHash(0, "A", {}, "t", "0".repeat(64));
    const h2 = computeHash(1, "A", {}, "t", "0".repeat(64));
    expect(h1).not.toBe(h2);
  });
});