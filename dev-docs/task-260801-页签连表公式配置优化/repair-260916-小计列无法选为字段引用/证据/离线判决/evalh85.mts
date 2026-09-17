import { fileURLToPath } from 'node:url';
import { readFileSync } from 'node:fs';
import { evaluateExpression } from '/home/joii/project/cpq/cpq-frontend/src/utils/formulaEngine.ts';
import { expressionToTokens } from '/home/joii/project/cpq/cpq-frontend/src/pages/component/formulaSerialize.ts';
const S = fileURLToPath(new URL('.', import.meta.url)); // snap.jsonl 与本脚本同目录
const snap = readFileSync(S + '/snap.jsonl', 'utf8').trim().split('\n').map((l) => JSON.parse(l));
const wl = snap.find((d) => d.code === 'COMP-0002');
const orig = wl.formulas.find((f: any) => f.name === '非银点类材料成本公式').expression;
const WL = 'b3445979-e3d6-4cdb-ab2d-53758b5f2eb2', JG = '4db28822-85c6-4522-ac62-61ef2393a99c';
const tabDefs: any[] = [
  { alias: 'COMP-0002', componentId: WL, componentName: '物料', rowKeyFields: ['销售料号','料号'], subtotalCols: ['材料成本'], detailFields: [], self: true },
  { alias: 'COMP-0004', componentId: JG, componentName: '来料固定加工费', rowKeyFields: ['销售料号','料号'], subtotalCols: ['加工费'], detailFields: [] },
];
const sumTok = expressionToTokens('SUM([来料固定加工费.加工费])', tabDefs, ['销售料号','料号'], WL)[0];
const fixed = orig.map((t: any) => (t.type === 'component_subtotal' && t.value === '加工费') ? sumTok : t);
const MC = '5d5b34f8-a4ae-4704-a91b-aab7d9316e71';
const crossTabRows = {
  [MC]: [
    { 销售料号: 'S3110520422', 料号: '00255', '组成含量（%）': '100', 税后单价: '25568.584070796' },
    { 销售料号: 'S3120011203', 料号: '00144', '组成含量（%）': '85', 税后单价: '89.503716814' },
    { 销售料号: 'S3120011203', 料号: '00144', '组成含量（%）': '15', 税后单价: '21.388938053' },
    { 销售料号: 'S3110520422', 料号: '00256', '组成含量（%）': '100', 税后单价: '89.503716814' },
    { 销售料号: 'S3110520422', 料号: '00257', '组成含量（%）': '100', 税后单价: '105' },
  ],
  [JG]: [
    { 销售料号: 'S3120011203', 料号: 'S3110520422', 加工费: '120' },
    { 销售料号: 'S3120011203', 料号: '00144', 加工费: '7.5' },
  ],
};
const subtotals = { 'COMP-0001#税率': '1.13', 'COMP-0004#加工费': '127.5' };
const row = { 销售料号: 'S3120011203', 料号: '00144', 产出类型: '非银点类', 材料毛重: '0.002365', 来料损耗率: '5' };
for (const [name, toks] of [['原公式（整列小计）', orig], ['改为 SUM([来料固定加工费.加工费])', fixed]] as const) {
  const fv: any = { 材料毛重: '0.002365', 来料损耗率: '5' };
  const diag: any = {};
  const v = evaluateExpression(toks as any, fv, subtotals, {}, {}, undefined, undefined, undefined, undefined, undefined, row, crossTabRows, diag, undefined, fv, row);
  console.log(name, '=>', v, diag.crossTabError ?? '');
}
