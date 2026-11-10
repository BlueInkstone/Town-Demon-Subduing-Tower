/**
 * 审计模块 - 对齐 core/audit/ 模块
 */

import { createHash } from "crypto";
import { appendFileSync, existsSync, readFileSync } from "fs";

/** 审计记录 */
export interface AuditRecord {
  readonly seq: number;
  readonly action: string;
  readonly payload: Readonly<Record<string, unknown>>;
  readonly timestamp: string;
  readonly prevHash: string;
  readonly hash: string;
  readonly mac: string;
}

/** 计算哈希 - 对齐 core HashChainAuditStore.java */
export function computeHash(
  seq: number,
  action: string,
  payload: Readonly<Record<string, unknown>>,
  timestamp: string,
  prevHash: string,
): string {
  const content = `${seq}:${action}:${JSON.stringify(payload, Object.keys(payload).sort())}:${timestamp}:${prevHash}`;
  return createHash("sha256").update(content, "utf8").digest("hex");
}

/** 审计存储接口 */
export interface IAuditStore {
  append(action: string, payload: Record<string, unknown>): string;
  all(): ReadonlyArray<AuditRecord>;
  size(): number;
  verify(): boolean;
}

/** 内存哈希链审计存储 - 对齐 core HashChainAuditStore.java */
export class HashChainAuditStore implements IAuditStore {
  private _records: AuditRecord[] = [];
  private _prevHash: string = "0".repeat(64);

  append(action: string, payload: Record<string, unknown>): string {
    const seq = this._records.length;
    const timestamp = new Date().toISOString();
    const hash = computeHash(seq, action, payload, timestamp, this._prevHash);

    const record: AuditRecord = Object.freeze({
      seq,
      action,
      payload: Object.freeze({ ...payload }),
      timestamp,
      prevHash: this._prevHash,
      hash,
      mac: "",
    });

    this._records.push(record);
    this._prevHash = hash;
    return String(seq);
  }

  all(): ReadonlyArray<AuditRecord> {
    return [...this._records];
  }

  size(): number {
    return this._records.length;
  }

  verify(): boolean {
    let prevHash = "0".repeat(64);
    for (const record of this._records) {
      if (record.prevHash !== prevHash) return false;
      const expectedHash = computeHash(
        record.seq,
        record.action,
        record.payload,
        record.timestamp,
        record.prevHash,
      );
      if (record.hash !== expectedHash) return false;
      prevHash = record.hash;
    }
    return true;
  }
}

/** 文件审计存储 - 对齐 core FileAuditStore.java */
export class FileAuditStore implements IAuditStore {
  private _filePath: string;
  private _records: AuditRecord[] = [];
  private _prevHash: string = "0".repeat(64);

  constructor(filePath: string) {
    this._filePath = filePath;
    this._loadExisting();
  }

  private _loadExisting(): void {
    if (!existsSync(this._filePath)) return;
    const lines = readFileSync(this._filePath, "utf8").split("\n");
    for (const line of lines) {
      const trimmed = line.trim();
      if (!trimmed || trimmed.startsWith("#")) continue;
      try {
        const data = JSON.parse(trimmed) as Record<string, unknown>;
        const record: AuditRecord = Object.freeze({
          seq: data.seq as number,
          action: data.action as string,
          payload: Object.freeze((data.payload as Record<string, unknown>) ?? {}),
          timestamp: (data.timestamp as string) ?? new Date().toISOString(),
          prevHash: (data.prevHash as string) ?? "0".repeat(64),
          hash: (data.hash as string) ?? "",
          mac: (data.mac as string) ?? "",
        });
        this._records.push(record);
        this._prevHash = record.hash;
      } catch {
        // 跳过损坏的行
      }
    }
  }

  append(action: string, payload: Record<string, unknown>): string {
    const seq = this._records.length;
    const timestamp = new Date().toISOString();
    const hash = computeHash(seq, action, payload, timestamp, this._prevHash);

    const record: AuditRecord = Object.freeze({
      seq,
      action,
      payload: Object.freeze({ ...payload }),
      timestamp,
      prevHash: this._prevHash,
      hash,
      mac: "",
    });

    this._records.push(record);
    this._prevHash = hash;

    appendFileSync(this._filePath, JSON.stringify(record) + "\n", "utf8");
    return String(seq);
  }

  all(): ReadonlyArray<AuditRecord> {
    return [...this._records];
  }

  size(): number {
    return this._records.length;
  }

  verify(): boolean {
    let prevHash = "0".repeat(64);
    for (const record of this._records) {
      if (record.prevHash !== prevHash) return false;
      const expectedHash = computeHash(
        record.seq,
        record.action,
        record.payload,
        record.timestamp,
        record.prevHash,
      );
      if (record.hash !== expectedHash) return false;
      prevHash = record.hash;
    }
    return true;
  }
}