/**
 * 导出下载统一入口（task-260902 · F-1 / F-2 / F-3 三个导出按钮共用）。
 *
 * 🚨 为什么不能照抄现有 `handleDownloadTemplate` 的写法：
 *   现有模板下载的 `catch` 是**静默**的（只 `message.error('模板下载失败')`），因为模板端点
 *   对所有登录角色开放，实际上不会失败。本次三个**导出**端点标了
 *   `@RoleAllowed({"SYSTEM_ADMIN"})`，非管理员直接调会返 **403 + JSON 体**。
 *
 *   而 `responseType: 'blob'` 时 axios 把**错误响应体也解析成 Blob**：
 *     · `services/api.ts` 的 `buildApiError` 读的是 `error.response.data.message` ——
 *       此刻 `data` 是个 Blob，`.message` 恒 `undefined` ⇒ 拿到的永远是 `'Network error'`；
 *     · `err.payload = error.response.data.data` 同理恒 `null`，错误码也丢了。
 *   ⇒ 照抄旧范式的结果是「点了按钮，界面上什么都不发生」。
 *
 * 本文件的解法（两步，缺一不可）：
 *   ① `validateStatus: (s) => s !== 401` —— 让 403/400/500 走 axios 的**成功分支**，
 *      这样 Blob 原件能到我们手上（401 仍留给全局拦截器跳登录页，不改既有行为）；
 *   ② 下载前先看 `blob.type`：是 `application/json` / `text/*` 就说明这不是 xlsx 而是错误信封，
 *      `await blob.text()` 解析出 `message` 抛出，**不触发下载**（否则用户会存下一个 300 字节的假 xlsx）。
 */
import dayjs from 'dayjs';
import api from '../services/api';
import { apiErrorMessage } from './apiError';

/**
 * 三个导出按钮在「筛选结果 0 条」时的禁用 tooltip。
 * ⚠️ 需求文档 AC-23 要求三页**逐字相同**，所以抽成常量，不许各页各写一句。
 */
export const EXPORT_EMPTY_TOOLTIP = '当前筛选结果为 0 条，无可导出数据';

const XLSX_MIME = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';

/**
 * 导出文件名由**前端**决定（后端 `Content-Disposition` 走 ASCII，浏览器最终用的是 `a.download`）。
 * 格式：`<前缀>_YYYYMMDD_HHmmss.xlsx`，如 `材质库_20260902_143005.xlsx`。
 */
export function exportFileName(prefix: string): string {
  return `${prefix}_${dayjs().format('YYYYMMDD_HHmmss')}.xlsx`;
}

function saveBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

/** 把错误信封的 Blob 读成 `message`；读不出来返回 null（交给调用方回退文案） */
async function readErrorMessage(blob: Blob): Promise<string | null> {
  try {
    const text = await blob.text();
    const parsed = JSON.parse(text) as { message?: unknown };
    return typeof parsed?.message === 'string' && parsed.message ? parsed.message : null;
  } catch {
    return null;
  }
}

/**
 * 拉取导出文件并触发浏览器下载。
 *
 * 失败时**抛出带可读文案的 Error**（调用方 `catch` 后 `message.error(e.message)`），
 * 且此时不会产生任何下载动作。
 */
export async function downloadExport(
  path: string,
  params: Record<string, unknown> | undefined,
  filename: string,
): Promise<void> {
  let body: unknown;
  try {
    body = (await api.get(path, {
      params,
      responseType: 'blob',
      // 401 仍走全局拦截器（跳 /login），其余状态码交给下面按 blob.type 判定
      validateStatus: (s: number) => s !== 401,
    })) as unknown;
  } catch (e: unknown) {
    const msg = apiErrorMessage(e, '');
    // 'Network error' 是 buildApiError 在 blob 场景下的兜底占位，不是给用户看的文案
    throw new Error(msg && msg !== 'Network error' ? msg : '导出失败，请稍后重试');
  }

  const blob = body instanceof Blob
    ? body
    : new Blob([body as BlobPart], { type: XLSX_MIME });

  const type = (blob.type || '').toLowerCase();
  if (type.includes('json') || type.startsWith('text/')) {
    throw new Error((await readErrorMessage(blob)) ?? '导出失败，请稍后重试');
  }

  saveBlob(blob, filename);
}
