/**
 * spec 2026-08-03「行数据即快照 / 键存在即权威」的**唯一前端实现**。
 *
 * 不变式：`row_data[row][fieldName]` 键存在 = 已定值，任何路径不得写入；
 * 键不存在 = 从未定值，仅此时允许烘一次默认值。清空写空串 `''`，不是删键。
 *
 * 本模块存在的意义就是「只有一份判据」—— bake effect、保存回填、enrich 合并三处
 * 必须调这里，禁止各自内联判空。修复前正是因为三处各写各的（都把 `''` 和
 * 「键不存在」折叠成同一个「空」），用户清空的数字每次重开都被默认值填回。
 *
 * 后端同口径见 `FormulaCalculator.resolveRowByFieldName` 的 INPUT 分支与
 * `fillInputDefaultSourceByFieldName`（「仅键缺失才补 default_source」）。
 */

/**
 * 该格是否「从未定值」（= 允许烘一次默认值）。
 *
 * `null` 按键缺失处理：空值的物理表示只认空串，不引入第三种空语义
 * （后端 `mergeRowDataInputsIntoEdits` 会跳过 null，落成 null 等于键丢了）。
 */
export function isKeyUnset(row: Record<string, any> | undefined | null, key: string): boolean {
  if (!row) return true;
  if (!Object.prototype.hasOwnProperty.call(row, key)) return true;
  return row[key] === null;
}

/**
 * 这批 rows 里是否含用户数据。
 *
 * 判据是「有没有非 row_index 的业务键」，**不看值是否为空** —— 用户把一行清空
 * 也是用户数据，不能因此把整行退回 enriched 默认行。
 */
export function rowsHaveUserData(rows: Array<Record<string, any>> | null | undefined): boolean {
  if (!Array.isArray(rows) || rows.length === 0) return false;
  return rows.some((r) => r && Object.keys(r).some((k) => k !== 'row_index'));
}
