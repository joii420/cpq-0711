// 前端 Excel 连表列（buildExcelSnapshot 同口径：expressionToTokens 不传宿主行键）对「裸引用」的求值。
// 用 QT-20260916-0881 物料页签 6 行的「材料成本」实值。
import { evaluateExpression } from '../../../../../cpq-frontend/src/utils/formulaEngine.ts';
import { expressionToTokens } from '../../../../../cpq-frontend/src/pages/component/formulaSerialize.ts';
const WL = 'b3445979-e3d6-4cdb-ab2d-53758b5f2eb2';
const rows = ['0', '0.059191597', '0.463735546', '1.437983994', '0.015491845', '0.002538082']
  .map((v, i) => ({ 销售料号: 'S3120011203', 料号: `P${i}`, 材料成本: v }));
const subtotals = { 'b3445979-e3d6-4cdb-ab2d-53758b5f2eb2#材料成本': '1.978941064', '物料#材料成本': '1.978941064' };
for (const [label, sub] of [['材料成本 勾小计（现状）', ['材料成本']], ['假设材料成本未勾小计', []]] as const) {
  const tabDefs: any[] = [{ alias: '物料', tabKey: WL, componentId: WL, componentName: '物料', rowKeyFields: ['销售料号', '料号'],
    detailFields: ['材料成本'].filter((f) => !sub.includes(f)), allFields: ['材料成本'], subtotalCols: sub }];
  const toks = expressionToTokens('[物料.材料成本]', tabDefs);
  const diag: any = {};
  const v = evaluateExpression(toks as any, {}, subtotals, {}, {}, undefined, undefined, undefined, undefined, undefined, {}, { [WL]: rows }, diag);
  console.log(`${label}: token=${JSON.stringify(toks)} => ${v} ${diag.crossTabError ?? ''}`);
}
