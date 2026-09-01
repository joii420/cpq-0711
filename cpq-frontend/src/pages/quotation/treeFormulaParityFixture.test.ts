import { describe, it, expect } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  computeTabFormulasTree,
  type ComponentDataItem, type TreeFormulaRowInput,
} from './QuotationStep2';

/**
 * task-0803 Task 9-A — 前后端求值引擎（BOM 父子取值 tree_ref / tree_attr）逐位比对。
 *
 * 为什么需要这份测试（而不是复用 treeFormula.test.ts）：treeFormula.test.ts 只单测前端
 * computeTabFormulasTree 自己的一套硬编码期望值，后端 TreeFormulaEvalTest 同理只单测后端
 * evaluateExpression 层（还绕开了 calculate() 的整页签单元格拓扑/环检测）。两边从未真正对比过
 * "同一份输入，两套独立实现算出的数是否逐位相同"。
 *
 * 本测试与后端 TreeFormulaParityFixtureTest 读取**同一个物理文件**（不复制）：
 *   dev-docs/task-260803-BOM页签增加父子取值公式/fixtures/tree-formula-parity-cases.json
 * 改一处夹具，两端测试同步生效；找不到文件直接抛错（fail-fast，不静默跳过）。
 */

interface FixtureRow {
  nodeId: string;
  parentId: string | null;
  lvl: number;
  values: Record<string, any>;
  /** 可选：建模 BASIC_DATA 字段（键形如 "{$view.col}"）。见后端 harness 同名注释。 */
  basicDataValues?: Record<string, any>;
}

interface FixtureCase {
  name: string;
  _doc?: string;
  fields: { name: string; field_type: string; basic_data_path?: string }[];
  formulas: { name: string; expression: any[] }[];
  rows: FixtureRow[];
  expected: Record<string, Record<string, number>>;
}

/** 从当前测试文件所在目录向上找仓库根（同时含 cpq-backend 与 cpq-frontend 子目录的那一层）。 */
function findRepoRoot(startDir: string): string {
  let dir = startDir;
  for (let i = 0; i < 8; i++) {
    if (fs.existsSync(path.join(dir, 'cpq-backend')) && fs.existsSync(path.join(dir, 'cpq-frontend'))) {
      return dir;
    }
    const parent = path.dirname(dir);
    if (parent === dir) break;
    dir = parent;
  }
  throw new Error(`repo root (containing cpq-backend & cpq-frontend) not found from ${startDir}`);
}

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const repoRoot = findRepoRoot(__dirname);
const fixturePath = path.join(
  repoRoot, 'dev-docs', 'task-260803-BOM页签增加父子取值公式', 'fixtures', 'tree-formula-parity-cases.json',
);

if (!fs.existsSync(fixturePath)) {
  throw new Error(`共享夹具文件不存在: ${fixturePath}`);
}
const cases: FixtureCase[] = JSON.parse(fs.readFileSync(fixturePath, 'utf-8'));

function buildComp(c: FixtureCase): ComponentDataItem {
  return {
    componentId: 'fixture', componentCode: 'FIXTURE', tabName: 'BOM页签',
    fields: c.fields as any,
    formulas: c.formulas as any,
    rows: [],
    subtotal: 0,
  } as any;
}

function buildRows(c: FixtureCase): TreeFormulaRowInput[] {
  const decimalize = (values?: Record<string, any>) => values && Object.fromEntries(
    Object.entries(values).map(([key, value]) => [key, typeof value === 'number' ? String(value) : value]),
  );
  return c.rows.map(r => ({
    row: decimalize(r.values) ?? {},
    basicDataValues: decimalize(r.basicDataValues),
    nodeId: r.nodeId,
    parentId: r.parentId ?? null,
    lvl: r.lvl,
  }));
}

// fail-fast：夹具必须非空，否则下面 for 循环会静默产出 0 个 it()，测试报告显示"0 passed"而非报错，
// 容易被误读成"全绿"。用顶层断言在 describe 收集阶段就炸，不藏进某个 it 里。
if (cases.length === 0) {
  throw new Error(`共享夹具为空数组: ${fixturePath}`);
}

describe('computeTabFormulasTree — 前后端共享夹具逐位比对（task-0803 Task 9-A）', () => {
  for (const c of cases) {
    it(c.name, () => {
      const comp = buildComp(c);
      const rows = buildRows(c);
      const out = computeTabFormulasTree(comp, rows);

      for (const [rowIdxStr, expectedRow] of Object.entries(c.expected)) {
        const rowIdx = Number(rowIdxStr);
        const actualRow = out[rowIdx];
        expect(actualRow, `[${c.name}] 行 ${rowIdx} 在前端结果中缺失`).toBeDefined();
        for (const [field, expectedVal] of Object.entries(expectedRow)) {
          expect(actualRow[field], `[${c.name}] 行 ${rowIdx} 列 [${field}]`).toBe(String(expectedVal));
        }
      }
    });
  }
});
