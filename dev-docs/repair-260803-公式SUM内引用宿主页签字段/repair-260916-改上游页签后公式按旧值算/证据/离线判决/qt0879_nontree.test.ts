import { it } from 'vitest';
import { readFileSync } from 'node:fs';
import { computeAllFormulas } from '/home/joii/project/cpq/cpq-frontend/src/pages/quotation/QuotationStep2';
import { tryParseSnapshotJsonLossless } from '/home/joii/project/cpq/cpq-frontend/src/utils/losslessJson';
const FX: any = tryParseSnapshotJsonLossless(readFileSync('/home/joii/project/cpq/dev-docs/repair-260803-公式SUM内引用宿主页签字段/repair-260916-改上游页签后公式按旧值算/证据/离线判决/fixture_0879_wuliao.json', 'utf8'));
const SUB = { 'COMP-0001#税率': '1.13', 'COMP-0004#加工费': '127.5' };
function runNonTree(label: string, patch: (r: any, i: number) => any) {
  for (let i = 0; i < 6; i++) {
    const errors: Record<string, string> = {};
    const row = patch({ ...FX.rows[i].row }, i);
    const out = computeAllFormulas(FX.comp, row, SUB, undefined, undefined, 'S3120011203', FX.rows[i].basicDataValues,
      undefined, undefined, FX.crossTabRows, undefined, { errors });
    console.log(label, i, 'mat=', out['材料成本'], 'errs=', JSON.stringify(errors));
  }
}
it('non-tree', () => {
  runNonTree('NT-S0', (r) => r);
  runNonTree('NT-S1', (r, i) => (i === 5 ? { ...r, 来料加工费: '150.8' } : r));
});
