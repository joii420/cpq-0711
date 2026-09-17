/**
 * repair-260916 · 改了上游页签的值，本页签公式仍按旧值算（S-U 片：AC-11、AC-13）
 *
 * 用例书：dev-docs/repair-260803-公式SUM内引用宿主页签字段/repair-260916-改上游页签后公式按旧值算/test.md §2.1~§2.3
 * AC 原文：同目录 问题说明.md ⑥
 *
 *   U-0  夹具可信（对照组：夹具原样 → 6 行 × 8 公式列 == 后端 formulaResults）
 *   U-1  AC-13（AC-1 离线等价，树入口）：本行存的「来料加工费」是旧值 150.8，上游已是 170.404 ⇒ 按新值算
 *   U-2  AC-13（非树入口 computeAllFormulas）：同 U-1
 *   U-3  AC-13（期望值表 E0~E3）：行数据保持 T0 原样，只改上游页签
 *   U-4  AC-11a 条件公式的条件引用本页签公式列 ⇒ 按本轮算出值选分支
 *   U-5  AC-11b b_field 引用本页签输入列：用户值优先、显式清空按 0（修复前后相同）
 *   U-6  AC-11c 入参行对象不被改写
 *
 * 夹具：__fixtures__/qt20260916-0879/wuliao.json（= 证据/离线判决/fixture_0879_wuliao.json 原样复制）
 * ⚠️ 必须用 tryParseSnapshotJsonLossless 读（JSON.parse 读出 JS number 会被引擎丢弃 ⇒ 材料成本整列 0，是夹具读法错误不是缺陷）。
 * 数值一律字符串；比较口径 = 9 位小数（ROUND_HALF_UP）；列合计 = 每格先舍 9 位再相加。
 */
import { describe, it, expect } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import Decimal from 'decimal.js';
import { computeAllFormulas, computeTabFormulasTree } from './QuotationStep2';
import { tryParseSnapshotJsonLossless } from '../../utils/losslessJson';

// ─── 夹具 ───────────────────────────────────────────────────────────────────

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const FIXTURE_PATH = path.join(__dirname, '__fixtures__', 'qt20260916-0879', 'wuliao.json');

if (!fs.existsSync(FIXTURE_PATH)) {
  throw new Error(`夹具文件不存在: ${FIXTURE_PATH}`);
}
const FX: any = tryParseSnapshotJsonLossless<any>(fs.readFileSync(FIXTURE_PATH, 'utf-8'));
if (!FX) throw new Error(`夹具解析结果为空: ${FIXTURE_PATH}`);

/** 上游「来料其他费用」页签在 crossTabRows 里的两个键（componentId 与 componentCode 各一份，两处都要改）。 */
const UPSTREAM_KEYS = ['57554055-0896-4cc8-be98-65e45b0a5985', 'COMP-0005'] as const;
const SUBTOTALS: Record<string, string> = { 'COMP-0001#税率': '1.13', 'COMP-0004#加工费': '127.5' };
const PART_NO = 'S3120011203';
const FORMULA_COLS: string[] = FX.comp.fields
  .filter((f: any) => f.field_type === 'FORMULA')
  .map((f: any) => f.name);

// 行下标：0 根 S3120011203 · 1 S3110520422 · 2 00144 · 3 00255 · 4 00256 · 5 00257
const IDX = { root: 0, parent: 1, p00144: 2, p00255: 3, p00256: 4, p00257: 5 } as const;

/** 9 位定点字符串；null/undefined/非数字直接判失败（防「—」「空」被当成 0 蒙混过关）。 */
function r9(v: unknown, label: string): string {
  expect(v === null || v === undefined || v === '', `${label} 没算出值（实际 ${JSON.stringify(v)}）`).toBe(false);
  let d: Decimal;
  try {
    d = new Decimal(String(v));
  } catch {
    throw new Error(`${label} 不是数字：${JSON.stringify(v)}`);
  }
  return d.toDecimalPlaces(9, Decimal.ROUND_HALF_UP).toFixed(9);
}

function expect9(actual: unknown, expected: string, label: string) {
  const a = r9(actual, label);
  const e = new Decimal(expected).toFixed(9);
  expect(a, `${label}：实际 ${String(actual)}（9 位 ${a}），期望 ${e}`).toBe(e);
}

/** 复制上游行并改值；断言两个键下各恰好命中 1 行（防「改了个寂寞」）。 */
function upstreamWith(fee00257?: string, ratio00256?: string): Record<string, Array<Record<string, any>>> {
  const out: Record<string, Array<Record<string, any>>> = {};
  for (const [k, rows] of Object.entries(FX.crossTabRows as Record<string, Array<Record<string, any>>>)) {
    out[k] = rows.map((r) => ({ ...r }));
  }
  for (const k of UPSTREAM_KEYS) {
    const list = out[k];
    expect(Array.isArray(list) && list.length > 0, `crossTabRows[${k}] 为空`).toBe(true);
    if (fee00257 !== undefined) {
      const hit = list.filter((r) => String(r['料号']) === '00257' && r['要素'] === '来料加工费');
      expect(hit.length, `crossTabRows[${k}] 00257/来料加工费 命中行数`).toBe(1);
      hit[0]['费用'] = fee00257;
    }
    if (ratio00256 !== undefined) {
      const hit = list.filter((r) => String(r['料号']) === '00256' && r['要素'] === '来料损耗率');
      expect(hit.length, `crossTabRows[${k}] 00256/来料损耗率 命中行数`).toBe(1);
      hit[0]['比例'] = ratio00256;
    }
  }
  return out;
}

/** 每行复制一份再交给 patch（不改动 FX 本身）。 */
function treeRows(patch?: (row: Record<string, any>, i: number) => Record<string, any>) {
  return FX.rows.map((r: any, i: number) => {
    const row = { ...r.row };
    return { ...r, row: patch ? patch(row, i) : row };
  });
}

function runTree(
  rows: any[],
  cross: Record<string, Array<Record<string, any>>> = FX.crossTabRows,
): Record<number, Record<string, any>> {
  const out = computeTabFormulasTree(FX.comp, rows, SUBTOTALS, undefined, undefined, PART_NO, undefined, cross);
  expect(out, 'computeTabFormulasTree 返回空').toBeTruthy();
  for (let i = 0; i < FX.rows.length; i++) {
    expect(out[i], `树入口第 ${i} 行无结果`).toBeTruthy();
  }
  return out as Record<number, Record<string, any>>;
}

function dump(label: string, out: Record<number, Record<string, any>>) {
  const lines = FX.rows.map((r: any, i: number) =>
    `  [${i}] ${r.row['料号'] ?? '(root)'}  来料加工费=${out[i]?.['来料加工费']}  来料损耗率=${out[i]?.['来料损耗率']}  材料成本=${out[i]?.['材料成本']}`);
  // eslint-disable-next-line no-console
  console.log(`\n== ${label}\n${lines.join('\n')}`);
}

// ─── 夹具自检（断言前先断言非空）──────────────────────────────────────────────

describe('repair-260916 夹具形状', () => {
  it('6 行、8 个公式列、6 份后端结果、00257 行存着公式列旧键', () => {
    expect(FX.rows).toHaveLength(6);
    expect(FORMULA_COLS).toHaveLength(8);
    expect(FX.backendFormulaResults).toHaveLength(6);
    expect(FX.rows[IDX.p00257].row['料号']).toBe('00257');
    expect(FX.rows[IDX.p00256].row['料号']).toBe('00256');
    expect(FX.rows[IDX.parent].row['料号']).toBe('S3110520422');
    // 缺陷的前提：本行原始数据里存着公式列的值
    expect(String(FX.rows[IDX.p00257].row['来料加工费'])).toBe('170.404');
    // 上游 T0 值
    for (const k of UPSTREAM_KEYS) {
      const fee = FX.crossTabRows[k].find((r: any) => String(r['料号']) === '00257' && r['要素'] === '来料加工费');
      const ratio = FX.crossTabRows[k].find((r: any) => String(r['料号']) === '00256' && r['要素'] === '来料损耗率');
      expect(String(fee?.['费用'])).toBe('170.404');
      expect(String(ratio?.['比例'])).toBe('5');
    }
  });
});

// ─── U-0 对照 ──────────────────────────────────────────────────────────────

describe('U-0 对照：夹具原样喂树入口 ⇒ 与后端 formulaResults 9 位逐格相等', () => {
  it('6 行 × 8 公式列', () => {
    const out = runTree(treeRows());
    dump('U-0 对照', out);
    let checked = 0;
    FX.backendFormulaResults.forEach((fr: any, i: number) => {
      for (const col of FORMULA_COLS) {
        const exp = fr.values[col];
        expect(exp, `后端结果 row ${i} ${col} 缺失`).not.toBeUndefined();
        expect9(out[i][col], String(exp), `row ${i} ${FX.rows[i].row['料号'] ?? '(root)'} ${col}`);
        checked++;
      }
    });
    expect(checked).toBe(48);
  });
});

// ─── U-1 / U-2：本行存旧值、上游已是新值 ────────────────────────────────────

describe('U-1（AC-13 / AC-1 离线等价）树入口：本行存的来料加工费是旧值，按上游新值算', () => {
  it('00257 材料成本 0.002418226、S3110520422 0.059189199', () => {
    const out = runTree(treeRows((row, i) => (i === IDX.p00257 ? { ...row, 来料加工费: '150.8' } : row)));
    dump('U-1', out);
    expect9(out[IDX.p00257]['来料加工费'], '170.404', '00257 来料加工费');
    expect9(out[IDX.p00257]['材料成本'], '0.002418226', '00257 材料成本');
    expect9(out[IDX.parent]['材料成本'], '0.059189199', 'S3110520422 材料成本');
    // 其余行不受影响
    expect9(out[IDX.p00255]['材料成本'], '1.437983994', '00255 材料成本');
    expect9(out[IDX.p00144]['材料成本'], '0.463735546', '00144 材料成本');
    expect9(out[IDX.p00256]['材料成本'], '0.015491845', '00256 材料成本');
  });
});

describe('U-2（AC-13）非树入口 computeAllFormulas：同 U-1', () => {
  it('00257 材料成本 0.002418226，且无计算错误', () => {
    const src = FX.rows[IDX.p00257];
    const row = { ...src.row, 来料加工费: '150.8' };
    const errors: Record<string, string> = {};
    const out = computeAllFormulas(
      FX.comp, row, SUBTOTALS, undefined, undefined, PART_NO, src.basicDataValues,
      undefined, undefined, FX.crossTabRows, undefined, { errors },
    );
    // eslint-disable-next-line no-console
    console.log(`\n== U-2 00257 来料加工费=${out?.['来料加工费']} 材料成本=${out?.['材料成本']} errors=${JSON.stringify(errors)}`);
    expect(out, 'computeAllFormulas 返回空').toBeTruthy();
    expect9(out['来料加工费'], '170.404', '00257 来料加工费');
    expect9(out['材料成本'], '0.002418226', '00257 材料成本');
    expect(errors, '不应有计算错误').toEqual({});
  });
});

// ─── U-3：期望值表（行数据保持 T0 原样，只改上游）─────────────────────────────

type Scenario = {
  id: string; fee: string; ratio: string;
  m00257: string; m00256: string; parent: string; sum: string;
};
const SCENARIOS: Scenario[] = [
  { id: 'E0 现状 170.404/5', fee: '170.404', ratio: '5', m00257: '0.002418226', m00256: '0.015491845', parent: '0.059189199', sum: '1.978818810' },
  { id: 'E1 费用 200/比例 5', fee: '200', ratio: '5', m00257: '0.002678099', m00256: '0.015491845', parent: '0.059194397', sum: '1.979083881' },
  { id: 'E2 费用 200/比例 10', fee: '200', ratio: '10', m00257: '0.002678099', m00256: '0.016191348', parent: '0.059208387', sum: '1.979797374' },
  { id: 'E3 费用 0/比例 10', fee: '0', ratio: '10', m00257: '0.000921968', m00256: '0.016191348', parent: '0.059173264', sum: '1.978006120' },
];

describe('U-3（AC-13 期望值表）行数据保持 T0 原样，只改上游页签', () => {
  it.each(SCENARIOS)('$id', (s) => {
    const cross = upstreamWith(s.fee, s.ratio);
    const out = runTree(treeRows(), cross);
    dump(`U-3 ${s.id}`, out);
    expect9(out[IDX.p00257]['来料加工费'], s.fee, '00257 来料加工费');
    expect9(out[IDX.p00256]['来料损耗率'], s.ratio, '00256 来料损耗率');
    expect9(out[IDX.p00257]['材料成本'], s.m00257, '00257 材料成本');
    expect9(out[IDX.p00256]['材料成本'], s.m00256, '00256 材料成本');
    expect9(out[IDX.parent]['材料成本'], s.parent, 'S3110520422 材料成本');
    expect9(out[IDX.p00255]['材料成本'], '1.437983994', '00255 材料成本');
    expect9(out[IDX.p00144]['材料成本'], '0.463735546', '00144 材料成本');
    // 列合计 = 6 格先舍 9 位再相加
    let sum = new Decimal(0);
    for (let i = 0; i < FX.rows.length; i++) sum = sum.plus(r9(out[i]['材料成本'], `row ${i} 材料成本`));
    // eslint-disable-next-line no-console
    console.log(`   材料成本列合计(9位累加)=${sum.toFixed(9)} 期望 ${s.sum}`);
    expect(sum.toFixed(9), `${s.id} 材料成本列合计`).toBe(s.sum);
  });
});

// ─── U-4 / U-5 / U-6：构造组件（test.md §2.3 原样）──────────────────────────

function buildHostComp(): any {
  return {
    componentId: 'H', componentCode: 'H', tabName: 'H',
    fields: [
      { name: 'A', field_type: 'INPUT_NUMBER' },
      { name: 'N', field_type: 'INPUT_NUMBER' },
      { name: 'X', field_type: 'FORMULA', formula_id: 'fx' },
      {
        name: 'Y', field_type: 'FORMULA', formula_id: 'fq',
        conditional_formula: {
          rules: [{
            when: {
              kind: 'group', logic: 'and', children: [
                { kind: 'leaf', left: 'X', op: 'gt', rhs: { type: 'literal', value: '10' } },
              ],
            },
            formula_id: 'fp', formula: 'P',
          }],
          default_formula_id: 'fq', default: 'Q',
        },
      },
      { name: 'Z', field_type: 'FORMULA', formula_id: 'fz' },
    ],
    formulas: [
      { id: 'fx', name: 'X', expression: [{ type: 'field', value: 'A' }, { type: 'operator', value: '*' }, { type: 'number', value: '2' }] },
      { id: 'fp', name: 'P', expression: [{ type: 'number', value: '1' }] },
      { id: 'fq', name: 'Q', expression: [{ type: 'number', value: '2' }] },
      {
        id: 'fz', name: 'Z', expression: [{
          type: 'cross_tab_ref', agg: 'SUM', match: [], source: 'SRC', target: '',
          targetExpr: [
            { type: 'field', value: 'v', source: 'SRC' },
            { type: 'operator', value: '*' },
            { type: 'b_field', value: 'N' },
          ],
        }],
      },
    ],
    rows: [],
    subtotal: 0,
  };
}
const HOST_CROSS = { SRC: [{ v: '5' }] };

type EntryName = 'computeAllFormulas' | 'computeTabFormulasTree';
function runEntry(entry: EntryName, row: Record<string, any>): Record<string, any> {
  const comp = buildHostComp();
  if (entry === 'computeAllFormulas') {
    const out = computeAllFormulas(
      comp, row, {}, undefined, undefined, undefined, undefined,
      undefined, undefined, HOST_CROSS, undefined, undefined,
    );
    expect(out, `${entry} 返回空`).toBeTruthy();
    return out as Record<string, any>;
  }
  const out = computeTabFormulasTree(comp, [{ row }], {}, undefined, undefined, undefined, undefined, HOST_CROSS);
  expect(out?.[0], `${entry} 第 0 行无结果`).toBeTruthy();
  return out[0] as Record<string, any>;
}

const ENTRIES: EntryName[] = ['computeAllFormulas', 'computeTabFormulasTree'];

describe('U-4（AC-11a）条件公式的条件引用本页签公式列 ⇒ 按本轮算出值选分支', () => {
  it.each(ENTRIES)('%s：A=8、X 存旧值 1 ⇒ X=16、Y=1', (entry) => {
    const out = runEntry(entry, { A: '8', X: '1', N: '3' });
    // eslint-disable-next-line no-console
    console.log(`\n== U-4 ${entry} out=${JSON.stringify(out)}`);
    expect9(out['X'], '16', `${entry} X`);
    expect9(out['Y'], '1', `${entry} Y`);
  });
});

describe('U-5（AC-11b）b_field 引用本页签输入列：用户值优先、显式清空按 0', () => {
  it.each(ENTRIES)('%s：N=3 ⇒ Z=15', (entry) => {
    const out = runEntry(entry, { A: '8', X: '1', N: '3' });
    // eslint-disable-next-line no-console
    console.log(`\n== U-5 ${entry} N=3 out=${JSON.stringify(out)}`);
    expect9(out['Z'], '15', `${entry} Z(N=3)`);
  });
  it.each(ENTRIES)("%s：N='' ⇒ Z=0", (entry) => {
    const out = runEntry(entry, { A: '8', X: '1', N: '' });
    // eslint-disable-next-line no-console
    console.log(`\n== U-5 ${entry} N='' out=${JSON.stringify(out)}`);
    expect9(out['Z'], '0', `${entry} Z(N='')`);
  });
});

describe('U-6（AC-11c）入参行对象不被改写（含公式列键仍在）', () => {
  it.each(ENTRIES)('%s：构造组件', (entry) => {
    const row: Record<string, any> = { A: '8', X: '1', Y: '9', Z: '7', N: '3' };
    const before = structuredClone(row);
    const out = runEntry(entry, row);
    expect(out['X'], '先确认确实算过').toBeTruthy();
    expect(Object.keys(row).sort()).toEqual(Object.keys(before).sort());
    expect(row).toEqual(before);
  });

  it('computeTabFormulasTree：0879 真实夹具 6 行', () => {
    const rows = treeRows((row, i) => (i === IDX.p00257 ? { ...row, 来料加工费: '150.8' } : row));
    const before = rows.map((r: any) => ({ ...r.row }));
    const beforeKeys = rows.map((r: any) => Object.keys(r.row).sort());
    runTree(rows);
    rows.forEach((r: any, i: number) => {
      expect(Object.keys(r.row).sort(), `row ${i} 键集合`).toEqual(beforeKeys[i]);
      for (const k of Object.keys(before[i])) {
        expect(r.row[k], `row ${i} ${k}`).toBe(before[i][k]);
      }
    });
    // 公式列键仍在
    expect(rows[IDX.p00257].row['来料加工费']).toBe('150.8');
    expect('材料成本' in rows[IDX.p00257].row).toBe(true);
  });

  it('computeAllFormulas：0879 真实夹具 00257 行', () => {
    const src = FX.rows[IDX.p00257];
    const row = { ...src.row, 来料加工费: '150.8' };
    const before = { ...row };
    const out = computeAllFormulas(
      FX.comp, row, SUBTOTALS, undefined, undefined, PART_NO, src.basicDataValues,
      undefined, undefined, FX.crossTabRows, undefined, { errors: {} },
    );
    expect(out['材料成本'], '先确认确实算过').toBeTruthy();
    expect(Object.keys(row).sort()).toEqual(Object.keys(before).sort());
    for (const k of Object.keys(before)) expect((row as any)[k], k).toBe((before as any)[k]);
  });
});
