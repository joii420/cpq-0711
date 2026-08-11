/**
 * task-0729 B9 · 双端公式一致性黄金用例集 —— 前端侧（vitest）。
 *
 * 🔒 契约（见仓库根 `formula-golden/README.md`）：
 *   1. 前端与后端 `FormulaGoldenTest.java` 读**同一份** `formula-golden/*.json` 文件，
 *      本文件不内嵌任何用例数据，也不维护副本——各自维护副本会退化回"两边都绿但从未
 *      验证同一输入"的现状（本任务立项时的真实教训）。
 *   2. `expected` 由前端引擎（本文件驱动的 `evaluateExpression`）产出，后端必须命中——
 *      前端是当前真正在给客户报价的那套引擎，是基准，不是后端的镜子。
 *   3. `expectedSource in {manual-computed, frontend-engine}` 才断言；`pending` 显式 SKIP
 *      （`it.skip`），不静默通过也不误报失败，与后端 `assumeTrue` 语义对齐。
 *
 * 🚨 已知的真实分歧（不要"修复"到消失，除非拿到明确裁定）：`10-decimal-precision.json`
 * 的 `dec-001/002/004` 三条，`expected` 是后端工程师按后端当前实现（task-0801 并发把
 * `FormulaCalculator.evaluateExpression` 改成"计算精度与呈现精度分离"：内部不再
 * `setScale(4)`，除法中间精度 12 位 HALF_UP）手推出来的。前端 `evaluateExpression`
 * （本文件 226 行下方 `.toDecimalPlaces(4)`）**仍然**在返回前把结果就近似到 4 位小数——
 * 这是历史一直如此的行为，本次任务未改动。因此这 3 条用例在本文件里会**真实断言失败**，
 * 这不是本文件的 bug，是这套黄金用例机制存在的意义：如实暴露两端当前不一致，交给
 * coordinator 裁定以哪端为准（是前端补 12 位精度跟后端对齐，还是后端加一层展示层
 * 4 位近似）。详见 `formula-golden/README.md` "重要发现" 章节 + 本次开发记录（RECORD.md）。
 */
import { describe, expect, it } from 'vitest';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import Decimal from 'decimal.js';
import { evaluateExpression } from './formulaEngine';
import type { ExpressionToken } from './formulaEngine';
import type { DecimalString } from './precision';
import { parseSnapshotJsonLossless } from './losslessJson';

// ─── 定位仓库根 formula-golden/ 目录（不依赖 vitest 的 cwd，从本文件出发逐级向上找）───

function resolveGoldenDir(): string {
  let dir = dirname(fileURLToPath(import.meta.url));
  for (let i = 0; i < 8; i++) {
    const candidate = join(dir, 'formula-golden');
    if (existsSync(candidate)) return candidate;
    const parent = dirname(dir);
    if (parent === dir) break;
    dir = parent;
  }
  throw new Error(
    `找不到 formula-golden/ 目录（从 ${dirname(fileURLToPath(import.meta.url))} 向上找了 8 层）——` +
    '本测试要求仓库根存在该目录，且与前端 vitest 读同一份文件（不是私有副本）。',
  );
}

// ─── 用例 shape（与 formula-golden/README.md 定义的 JSON schema 对齐）───

interface GoldenCaseContext {
  componentSubtotals?: Record<string, DecimalString>;
  productAttributes?: Record<string, DecimalString>;
  basicDataValues?: Record<string, any>;
  quotationFields?: Record<string, DecimalString>;
  crossTabRows?: Record<string, Array<Record<string, any>>>;
  currentRowRaw?: Record<string, any>;
  previousRowSubtotal?: DecimalString | null;
  /** README 未文档化，但后端 FormulaGoldenTest.buildContext 有读取；本次 33 条用例均未使用，恒 {} */
  fieldValues?: Record<string, DecimalString>;
}

interface GoldenCase {
  id: string;
  category: string;
  description: string;
  tokens: ExpressionToken[];
  context: GoldenCaseContext;
  expected: string;
  expectedSource: 'manual-computed' | 'frontend-engine' | 'pending';
  notes?: string;
}

/** 按 evaluateExpression 位置参数顺序，从 JSON context 映射实参（与 FormulaGoldenTest.buildContext 对齐同一组字段）。 */
function runCase(c: GoldenCase): Decimal {
  const ctx = c.context ?? {};
  const actual = evaluateExpression(
    c.tokens,
    ctx.fieldValues ?? {},
    ctx.componentSubtotals ?? {},
    ctx.productAttributes ?? {},
    ctx.quotationFields ?? {},
    undefined, // pathCache — 黄金用例的 path token（若有）走 basicDataValues 命中，不依赖模块级缓存
    undefined, // partNo
    ctx.basicDataValues ?? {},
    ctx.previousRowSubtotal ?? undefined,
    undefined, // globalVariableDefs（33 条用例均为静态 code，不涉及动态 key 重写）
    ctx.currentRowRaw ?? {},
    ctx.crossTabRows ?? {},
  );
  return new Decimal(actual);
}

// ─── 动态加载全部 formula-golden/*.json，逐条生成 it()（镜像后端 @TestFactory 设计）───

const GOLDEN_DIR = resolveGoldenDir();
const files = readdirSync(GOLDEN_DIR)
  .filter((f) => f.endsWith('.json'))
  .sort();

describe('formula-golden · 双端公式一致性黄金用例（前端引擎侧）', () => {
  for (const file of files) {
    const raw = readFileSync(join(GOLDEN_DIR, file), 'utf-8');
    const cases = parseSnapshotJsonLossless<GoldenCase[]>(raw);

    describe(file, () => {
      for (const c of cases) {
        const title = `${c.id} — ${c.description}`;
        if (c.expectedSource === 'pending') {
          it.skip(`${title} (expectedSource=pending，等待权威来源)`, () => {});
          continue;
        }
        it(title, () => {
          const actual = runCase(c);
          const expected = new Decimal(c.expected);
          expect(
            actual.equals(expected),
            `case=${c.id} expected=${c.expected} actual=${actual.toString()} (context=${JSON.stringify(c.context)})`,
          ).toBe(true);
        });
      }
    });
  }
});
