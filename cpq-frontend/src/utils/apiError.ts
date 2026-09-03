/**
 * 从 axios 拦截器抛出的 `ApiError` 里取出**后端的字符串错误码**（task-260901）。
 *
 * 背景（实证，不是推断）：`services/api.ts` 的 `buildApiError` 只把信封的 `message` 提成
 * `Error.message`，但**把信封的 `data` 整个挂在 `err.payload` 上**：
 * ```ts
 * err.payload = error?.response?.data?.data ?? null;
 * ```
 * 后端按 `ComponentElementBindingRequiredException` 的既有惯例，把字符串码放进 `data`：
 * ```json
 * { "code": 400, "message": "…", "data": { "code": "COMPOSITION_LOCKED" } }
 * ```
 * ⇒ 顶层 `code` 是 int（HTTP 语义），**判分支要用 `payload.code`**。
 *
 * 🚫 不要拿 `message` 做文案匹配来判分支 —— 文案是给人看的，改文案不该改行为。
 * ⚠️ 导入报告的 `skipped[].reason` 只有文案没有码，那里仍按原文展示（见 `MaterialImportDrawer`）。
 */
export function apiErrorCode(e: unknown): string | null {
  const payload = (e as { payload?: unknown } | null)?.payload;
  if (payload && typeof payload === 'object') {
    const code = (payload as { code?: unknown }).code;
    if (typeof code === 'string' && code) return code;
  }
  return null;
}

/** HTTP 状态码（`buildApiError` 挂在 `httpStatus` 上） */
export function apiErrorStatus(e: unknown): number | null {
  const s = (e as { httpStatus?: unknown } | null)?.httpStatus;
  return typeof s === 'number' ? s : null;
}

/** 后端给的可读文案；缺省回退到 `fallback` */
export function apiErrorMessage(e: unknown, fallback = '操作失败'): string {
  const m = (e as { message?: unknown } | null)?.message;
  return typeof m === 'string' && m ? m : fallback;
}
