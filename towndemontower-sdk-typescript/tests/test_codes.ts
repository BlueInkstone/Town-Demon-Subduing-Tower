/**
 * 方向 D 跨语言错误码口径验收（TypeScript 侧）。
 *
 * 权威数据直接读仓库根 `contract/error-codes.json`，逐条跑 golden，
 * 保证 TS 绑定与已定稿口径同源；跨语言一致性另见 `contract/verify_codes.py`。
 */

import { readFileSync } from "fs";
import { resolve } from "path";

import {
  CANONICAL_CODES,
  ERROR_CODE_ALIASES,
  UNMAPPED_REQUIRES_REVIEW,
  canonicalCode,
  countsAsDenied,
  isCanonicalCode,
} from "../src/codes";
import { canonicalCode as canonicalCodeFromIndex } from "../src/index";

interface Spec {
  status: string;
  canonical: string[];
  aliases: Record<string, string>;
  unmappedRequiresReview: Record<string, string>;
  golden: Array<{ input: string; expected: string }>;
  statistics: { deniedExcludes: string[] };
}

const spec: Spec = JSON.parse(
  readFileSync(resolve(__dirname, "../../contract/error-codes.json"), "utf8"),
);

describe("方向 D · 规范码口径", () => {
  test("权威文件已定稿", () => {
    expect(spec.status).toBe("ratified");
  });

  test("规范码集合与权威文件一致", () => {
    expect(new Set(CANONICAL_CODES)).toEqual(new Set(spec.canonical));
    expect(CANONICAL_CODES.size).toBe(30);
  });

  test("别名映射与权威文件一致", () => {
    expect({ ...ERROR_CODE_ALIASES }).toEqual(spec.aliases);
    Object.values(ERROR_CODE_ALIASES).forEach((target) => {
      expect(CANONICAL_CODES.has(target)).toBe(true);
    });
  });

  test("待评审码与权威文件一致，且未被归一", () => {
    expect(new Set(UNMAPPED_REQUIRES_REVIEW)).toEqual(
      new Set(Object.keys(spec.unmappedRequiresReview)),
    );
    UNMAPPED_REQUIRES_REVIEW.forEach((code) => {
      expect(canonicalCode(code)).toBe(code);
    });
  });

  test("golden 全量归一结果一致", () => {
    spec.golden.forEach(({ input, expected }) => {
      expect(canonicalCode(input)).toBe(expected);
    });
  });

  test("包入口导出的归一函数可用", () => {
    expect(canonicalCodeFromIndex("APPROVAL_REQUIRED")).toBe("PENDING_APPROVAL");
  });

  test("空值与规范码判定", () => {
    expect(canonicalCode(null)).toBeNull();
    expect(canonicalCode("")).toBe("");
    expect(canonicalCode("  RISK_REJECTED  ")).toBe("R3_DENIED");
    expect(isCanonicalCode("OK")).toBe(true);
    expect(isCanonicalCode("APPROVAL_REQUIRED")).toBe(false);
  });
});

describe("方向 D · 统一统计口径", () => {
  test("与 AuditDecorator 一致：PENDING_APPROVAL 单独计，不算拒绝", () => {
    expect(countsAsDenied("OK")).toBe(false);
    expect(countsAsDenied("PENDING_APPROVAL")).toBe(false);
    expect(countsAsDenied("R3_DENIED")).toBe(true);
    expect(countsAsDenied("PERMISSION_DENIED")).toBe(true);
    expect(countsAsDenied(null)).toBe(false);
  });

  test("归一后再统计", () => {
    expect(countsAsDenied(canonicalCode("APPROVAL_REQUIRED"))).toBe(false);
    expect(countsAsDenied(canonicalCode("RISK_REJECTED"))).toBe(true);
    expect(countsAsDenied(canonicalCode("EXECUTION_ERROR"))).toBe(true);
  });

  test("deniedExcludes 与 countsAsDenied 逐项一致", () => {
    const excludes = new Set(spec.statistics.deniedExcludes);
    CANONICAL_CODES.forEach((code) => {
      expect(countsAsDenied(code)).toBe(!excludes.has(code));
    });
  });
});
