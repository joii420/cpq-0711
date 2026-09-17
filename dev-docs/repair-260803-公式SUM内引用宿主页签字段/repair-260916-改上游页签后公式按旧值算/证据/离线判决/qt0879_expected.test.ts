import { it } from 'vitest';
import { readFileSync } from 'node:fs';
import Decimal from '/home/joii/project/cpq/cpq-frontend/node_modules/decimal.js/decimal.mjs';
import { computeTabFormulasTree } from '/home/joii/project/cpq/cpq-frontend/src/pages/quotation/QuotationStep2';
import { tryParseSnapshotJsonLossless } from '/home/joii/project/cpq/cpq-frontend/src/utils/losslessJson';
const FX: any = tryParseSnapshotJsonLossless(readFileSync('/home/joii/project/cpq/dev-docs/repair-260803-公式SUM内引用宿主页签字段/repair-260916-改上游页签后公式按旧值算/证据/离线判决/fixture_0879_wuliao.json', 'utf8'));
const SUB = { 'COMP-0001#税率': '1.13', 'COMP-0004#加工费': '127.5' };
const FCOLS: string[] = FX.comp.fields.filter((f: any) => f.field_type === 'FORMULA').map((f: any) => f.name);
const r9 = (v: any) => new Decimal(v ?? '0').toDecimalPlaces(9, Decimal.ROUND_HALF_UP);
function run(label: string, fee00257: string, ratio00256: string, withFormulaKeys: boolean) {
  const xt = JSON.parse(JSON.stringify(FX.crossTabRows));
  for (const k of ['57554055-0896-4cc8-be98-65e45b0a5985', 'COMP-0005']) {
    for (const r of xt[k]) {
      if (r['料号'] === '00257' && r['要素'] === '来料加工费') r['费用'] = fee00257;
      if (r['料号'] === '00256' && r['要素'] === '来料损耗率') r['比例'] = ratio00256;
    }
  }
  const rows = FX.rows.map((r: any, i: number) => {
    const row = { ...r.row };
    if (withFormulaKeys) { if (i === 5) row['来料加工费'] = fee00257; if (i === 4) row['来料损耗率'] = ratio00256; }
    else for (const c of FCOLS) delete row[c];
    return { ...r, row };
  });
  const out = computeTabFormulasTree(FX.comp, rows, SUB, undefined, undefined, 'S3120011203', undefined, xt);
  let sum = new Decimal(0);
  for (let i = 0; i < 6; i++) sum = sum.plus(r9(out[i]['材料成本']));
  console.log(`${label} | 00257 来料加工费=${out[5]['来料加工费']} 材料成本=${r9(out[5]['材料成本'])} | 00256 来料损耗率=${out[4]['来料损耗率']} 材料成本=${r9(out[4]['材料成本'])} | 父行=${r9(out[1]['材料成本'])} | 00255=${r9(out[3]['材料成本'])} 00144=${r9(out[2]['材料成本'])} | 材料成本列合计=${sum.toFixed(9)}`);
}
it('expected', () => {
  run('E0 现状 170.404/5 ', '170.404', '5', false);
  run('E1 改200/5       ', '200', '5', false);
  run('E1b 同上(新鲜键) ', '200', '5', true);
  run('E2 改200/10      ', '200', '10', false);
  run('E3 改0/10        ', '0', '10', false);
  run('E4 旧值bug:行存170.404 表改200', '200', '5', false);
});
