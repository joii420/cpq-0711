import { expressionToTokens, tokensToDrawerExpression } from '/home/joii/project/cpq/cpq-frontend/src/pages/component/formulaSerialize.ts';
const WL = 'b3445979-e3d6-4cdb-ab2d-53758b5f2eb2', JG = '4db28822-85c6-4522-ac62-61ef2393a99c', CP = '9612f71d-a5e3-48ce-b1d7-7dfc456125a7';
const tabDefs: any[] = [
  { alias: 'COMP-0002', componentId: WL, componentName: '物料', rowKeyFields: ['销售料号','料号'], subtotalCols: ['材料成本','材料损耗成本','回收成本','铆钉额外费用'], detailFields: [], self: true },
  { alias: 'COMP-0004', componentId: JG, componentName: '来料固定加工费', rowKeyFields: ['销售料号','料号'], subtotalCols: ['加工费'], detailFields: ['销售料号','料号','材料名','项次','加工费'] },
  { alias: 'COMP-0001', componentId: CP, componentName: '产品', rowKeyFields: ['销售料号'], subtotalCols: ['税率','管理费'], detailFields: [] },
];
for (const e of ['[来料固定加工费.加工费]', 'SUM([来料固定加工费.加工费])', '[来料固定加工费.加工费(总计)]']) {
  const t = expressionToTokens(e, tabDefs, ['销售料号','料号'], WL);
  const back = tokensToDrawerExpression(t, tabDefs, WL);
  const again = expressionToTokens(back, tabDefs, ['销售料号','料号'], WL);
  console.log(`\n输入: ${e}\n  存成: ${JSON.stringify(t)}\n  回显: ${back}\n  回显再保存是否不变: ${JSON.stringify(again) === JSON.stringify(t)}`);
}
