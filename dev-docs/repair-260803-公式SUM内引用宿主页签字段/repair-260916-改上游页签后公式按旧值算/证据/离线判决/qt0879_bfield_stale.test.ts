import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { computeTabFormulasTree, usesTreeTokensTab } from '/home/joii/project/cpq/cpq-frontend/src/pages/quotation/QuotationStep2';
import { tryParseSnapshotJsonLossless } from '/home/joii/project/cpq/cpq-frontend/src/utils/losslessJson';

const FX: any = tryParseSnapshotJsonLossless(readFileSync(
  '/home/joii/project/cpq/dev-docs/repair-260803-公式SUM内引用宿主页签字段/repair-260916-改上游页签后公式按旧值算/证据/离线判决/fixture_0879_wuliao.json', 'utf8'));

const SUBTOTALS: Record<string, string> = { 'COMP-0001#税率': '1.13', 'COMP-0004#加工费': '127.5' };
const FORMULA_COLS: string[] = FX.comp.fields.filter((f: any) => f.field_type === 'FORMULA').map((f: any) => f.name);

function run(rowPatch: (row: Record<string, any>, i: number) => Record<string, any>) {
  const rows = FX.rows.map((r: any, i: number) => ({ ...r, row: rowPatch({ ...r.row }, i) }));
  return computeTabFormulasTree(FX.comp, rows, SUBTOTALS, undefined, undefined, 'S3120011203', undefined, FX.crossTabRows);
}

function dump(label: string, out: Record<number, Record<string, any>>) {
  // eslint-disable-next-line no-console
  console.log(`\n== ${label}`);
  for (let i = 0; i < FX.rows.length; i++) {
    // eslint-disable-next-line no-console
    console.log(`  [${i}] ${FX.rows[i].row['料号'] ?? '(root)'}  来料加工费=${out[i]['来料加工费']}  材料成本=${out[i]['材料成本']}`);
  }
}

describe('QT-20260916-0879 物料页签 b_field 读 row_data 旧公式值', () => {
  it('路由：物料页签走树求值入口', () => {
    expect(usesTreeTokensTab(FX.comp)).toBe(true);
  });

  it('S0 对照组：row_data 为库里现值（来料加工费=170.404）→ 前端 6 行 × 全部公式列 == 后端 formulaResults', () => {
    const out = run((r) => r);
    dump('S0 对照组', out);
    FX.backendFormulaResults.forEach((fr: any, i: number) => {
      for (const col of FORMULA_COLS) {
        expect(Number(out[i][col]).toFixed(9), `row ${i} ${col}`).toBe(Number(fr.values[col]).toFixed(9));
      }
    });
  });

  it('S1 复现组：页面加载时 row_data 里 00257 的来料加工费还是 150.8，来料其他费用已改 170.404', () => {
    const out = run((r, i) => (i === 5 ? { ...r, 来料加工费: '150.8' } : r));
    dump('S1 复现组', out);
    expect(Number(out[5]['来料加工费']).toFixed(9)).toBe('170.404000000'); // 列值已是新值（截图1）
    expect(Number(out[5]['材料成本']).toFixed(9)).toBe('0.002246091');   // 材料成本仍按旧值算（截图1）
    expect(Number(out[1]['材料成本']).toFixed(9)).toBe('0.059185757');   // 父行跟着偏（截图1）
  });

  it('S2 方向验证：同 S1，但把原始行里的公式列键剔掉 → b_field 回落本轮算出的值 → 与后端一致', () => {
    const out = run((r, i) => {
      const base = i === 5 ? { ...r, 来料加工费: '150.8' } : r;
      for (const c of FORMULA_COLS) delete base[c];
      return base;
    });
    dump('S2 剔除公式列', out);
    expect(Number(out[5]['材料成本']).toFixed(9)).toBe('0.002418226');
    expect(Number(out[1]['材料成本']).toFixed(9)).toBe('0.059189199');
  });

  it('S3 反向：row_data 里公式列值若是物化器写错的 0（本单 材料成本 等列就是 0），b_field 引用它也会被带偏', () => {
    // 本单 b_field 只引用 来料加工费/来料损耗率；这里把 00256 的来料损耗率 row_data 改成物化器式的 "0"
    const out = run((r, i) => (i === 4 ? { ...r, 来料损耗率: '0' } : r));
    dump('S3 来料损耗率 row_data=0', out);
    expect(Number(out[4]['来料损耗率']).toFixed(9)).toBe('5.000000000');   // 列本身算对
    expect(Number(out[4]['材料成本']).toFixed(9)).not.toBe('0.015491845'); // 材料成本却没按 5% 算
  });
});
