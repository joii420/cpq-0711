/**
 * 零件自由文本（品名 / 规格 / 尺寸）的前端判据（task-260902，服务 AC-3 / AC-23 与 api.md §4.3）。
 *
 * 两条限制都**不是样式问题，是静默错价的防线**：
 *
 * 1. **长度 ≤ 100**（`material_master.material_name / specification / dimension` 均为 `varchar(100)`）。
 *    🚫 绝不允许落库截断 —— 截断后指纹算的是截断值、实际内容是另一个，
 *    **两个不同产品会算出相同指纹 ⇒ 复用同一个销售料号 ⇒ 静默错价**（AC-23）。
 *    前端用 `maxLength` 在输入框层面拦住；后端仍会返 400 `PART_TEXT_TOO_LONG`（体验 vs 正确性两道）。
 *
 * 2. **不得含指纹分隔符 `| = , :`**（`SalesFingerprintCalculator.assertNoDelimiter` 守的五个字符）。
 *    含了会在后端抛 `IllegalArgumentException` → 用户拿到 500；契约已补 400 `PART_TEXT_INVALID_CHAR`。
 *    ⚠️ **`/` 不在禁用之列**：实查 `material_recipe.symbol` 含 `/` 的有 74 条，品名含 `/` 的料号也真实存在
 *    （`AgNi10/Cu触点`），本业务文本带 `/` 是常态 —— 所以 v2 指纹的 `PART=` 改用**长度前缀编码**
 *    而不是 `/` 分隔（api.md §4.3）。禁 `/` 会把正常业务数据挡在门外。
 */

export const PART_TEXT_MAX_LENGTH = 100;

/** 指纹分隔符：`|` `=` `,` `:`（另有一个空值哨兵，不是可输入字符）。 */
export const FINGERPRINT_DELIMITERS = ['|', '=', ',', ':'] as const;

const DELIMITER_RE = /[|=,:]/g;

/** 返回文本里出现过的分隔符（去重、保序）。 */
export function findDelimiters(text: string | null | undefined): string[] {
  const hits = (text ?? '').match(DELIMITER_RE) ?? [];
  return Array.from(new Set(hits));
}

export interface PartTextIssue {
  kind: 'TOO_LONG' | 'INVALID_CHAR';
  message: string;
}

/**
 * 校验一段零件文本。`label` 用于拼提示（「品名」「规格」「尺寸」）。
 * 返回 null = 合法。空串合法（必填与否由调用方另判）。
 */
export function validatePartText(label: string, text: string | null | undefined): PartTextIssue | null {
  const value = text ?? '';
  if (value.length > PART_TEXT_MAX_LENGTH) {
    return {
      kind: 'TOO_LONG',
      message: `${label}最多 ${PART_TEXT_MAX_LENGTH} 个字符，当前 ${value.length} 个`,
    };
  }
  const bad = findDelimiters(value);
  if (bad.length > 0) {
    return {
      kind: 'INVALID_CHAR',
      message: `${label}不能包含 ${bad.map((c) => `「${c}」`).join('')} —— 这几个字符是产品指纹的分隔符`,
    };
  }
  return null;
}
