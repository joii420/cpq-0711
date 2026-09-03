/**
 * task-260901 ③：`quotation.user_data_version` 的**前端本地基线**。
 *
 * 谁写它（对齐 `api.md §4.1`）：
 *   · `GET /quotations/{id}` 响应根部的 `userDataVersion` —— 打开单据时初始化；
 *   · `PUT /draft` 响应的 `userDataVersion` —— 保存成功后前进；
 *   · `PUT /line-items/{id}/quote-card-edit` 响应的 `userDataVersion` —— 单元格编辑后前进。
 *     🚨 **漏掉这一条 = 改完一个格子再点保存必报 409**（AC-14）：该端点写 row_data 属用户数据，
 *     后端会递增版本号，前端不跟进就立刻持有过期基线。
 *
 * 为什么用模块级单例而不是 props：写点在 `QuotationStep2` 的 ProductCard 里
 * （`handleSnapshotCellEdit`），读点在 `QuotationWizard`。两者之间隔着
 * Step2 → ProductCard 两层，AP-41 记录过「prop 漏传一层 → 报价侧正常核价侧失效」的教训；
 * 这里改用带 quotationId 校验的单例，不新增穿透 prop。
 *
 * quotationId 维度是必需的：切换报价单时上一张单的版本号必须失效，否则会拿 A 单的版本去存 B 单。
 */

let currentQuotationId: string | null = null;
let currentVersion: number | null = null;

function isVersion(v: unknown): v is number {
  return typeof v === 'number' && Number.isSafeInteger(v) && v >= 0;
}

/** 打开/切换单据时初始化基线。version 缺失（后端尚未上线该字段）时置空，不猜。 */
export function initUserDataVersion(quotationId: string | null | undefined, version: unknown): void {
  const qid = quotationId ? String(quotationId) : null;
  if (qid !== currentQuotationId) {
    currentQuotationId = qid;
    currentVersion = null;
  }
  if (isVersion(version)) currentVersion = version;
}

/**
 * 服务端回传新版本号时前进基线。
 * 单调不回退：并发响应乱序到达时，旧值不得覆盖新值（否则下一次保存拿旧版本必 409）。
 */
export function noteUserDataVersion(quotationId: string | null | undefined, version: unknown): void {
  if (!isVersion(version)) return;
  const qid = quotationId ? String(quotationId) : null;
  if (qid == null || qid !== currentQuotationId) return;
  if (currentVersion == null || version > currentVersion) currentVersion = version;
}

/** 取当前基线；未知时返回 null（请求体按契约原样送 null，由后端 400 明示，不猜 0）。 */
export function getUserDataVersion(quotationId: string | null | undefined): number | null {
  const qid = quotationId ? String(quotationId) : null;
  if (qid == null || qid !== currentQuotationId) return null;
  return currentVersion;
}

/** 仅供测试/卸载复位。 */
export function resetUserDataVersion(): void {
  currentQuotationId = null;
  currentVersion = null;
}
