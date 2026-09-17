/**
 * repair-260916 · AC-6 判定（用户裁决 D-4，问题说明.md ⑥ AC-6）。
 * 纯函数、无副作用：spec 的 E-7 与一次性自证脚本共用这一份。
 * 全部用 decimal.js 按原文精度计算，不经过 JS number。
 *
 *  - |col_1 − 物料 subtotalByColumn.材料成本| ≤ 0.0000001，且小计 = 1.97800612
 *  - |col_3 − quotation_line_item.subtotal| ≤ 0.0000001
 *  - 导出 col_1 / col_3 与库快照同名两格舍到 9 位后相同
 */
import Decimal from 'decimal.js';

export const AC6_TOL = '0.0000001';
export const AC6_SUBTOTAL_EXPECTED = '1.97800612';

export interface Ac6Input {
  col1: string; col3: string;          // 库 quote_excel_values.rows[0] 原文
  materialSubtotal: string;            // 库 物料 subtotalByColumn.材料成本 原文
  lineSubtotal: string;                // 库 quotation_line_item.subtotal 原文
  exportCol1: string; exportCol3: string; // 导出文件原文
}
export interface Ac6Item { name: string; detail: string; pass: boolean }
export interface Ac6Result { pass: boolean; diff1: string; diff3: string; items: Ac6Item[] }

const D = (s: string) => new Decimal(String(s).trim());
const r9 = (s: string) => D(s).toDecimalPlaces(9, Decimal.ROUND_HALF_UP);

export function judgeAc6(i: Ac6Input): Ac6Result {
  const tol = D(AC6_TOL);
  const d1 = D(i.col1).minus(D(i.materialSubtotal)).abs();
  const d3 = D(i.col3).minus(D(i.lineSubtotal)).abs();
  const items: Ac6Item[] = [
    { name: '|col_1 − 材料成本小计| ≤ 0.0000001', detail: `|${i.col1} − ${i.materialSubtotal}| = ${d1.toFixed()}`, pass: d1.lte(tol) },
    { name: '材料成本小计 = 1.97800612', detail: `${i.materialSubtotal}`, pass: D(i.materialSubtotal).eq(D(AC6_SUBTOTAL_EXPECTED)) },
    { name: '|col_3 − subtotal| ≤ 0.0000001', detail: `|${i.col3} − ${i.lineSubtotal}| = ${d3.toFixed()}`, pass: d3.lte(tol) },
    { name: '导出 col_1 = 快照 col_1（9 位）', detail: `${r9(i.exportCol1).toFixed(9)} vs ${r9(i.col1).toFixed(9)}`, pass: r9(i.exportCol1).eq(r9(i.col1)) },
    { name: '导出 col_3 = 快照 col_3（9 位）', detail: `${r9(i.exportCol3).toFixed(9)} vs ${r9(i.col3).toFixed(9)}`, pass: r9(i.exportCol3).eq(r9(i.col3)) },
  ];
  return { pass: items.every((x) => x.pass), diff1: d1.toFixed(), diff3: d3.toFixed(), items };
}
