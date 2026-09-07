// task-260904 F-5（AC-6 / AC-7）：报价侧 BOM 树「加叶子」两个新拒绝态的错误信封解析。
//
// 单独成文件的两个理由：① `BomTreeAddLeafDrawer.tsx` 只导出组件，混入普通导出会破坏 fast refresh；
// ② 抽屉本体走 Portal（SSR 渲染为空，见 __tests__/treeRefUI.test.tsx 顶部注释），
//    纯函数拆出来才验得到 —— 与本项目 `checkTreeRefTabTypeGate` 的做法同源。
import type { ApiError } from '../../services/api';

/** api.md §3.3 声明的两个新错误码。 */
export type LeafRejectionCode = 'LEAF_PART_NOT_IN_MASTER' | 'LEAF_CYCLE_DETECTED';

/**
 * 从错误信封里取业务错误码。
 *
 * 本仓错误码有两种既有落点（`GlobalExceptionMapper` 实测）：`data.code`（→ `ApiError.payload.code`，
 * 多数模块用这个）与顶层 `detail.bizCode`（task-260902 起的新写法）。两个都认，避免后端选了另一种
 * 时前端静默退化成通用 message。
 * ⚠️ `ApiError.code` 取的是信封顶层 `code`，那一层在本仓是 int(HTTP 状态码)，所以只在它确实是
 * 非空字符串时才采信。
 */
export function readBizCode(err: ApiError): string | undefined {
  const payload = err.payload as { code?: unknown } | null;
  const detail = err.detail as { bizCode?: unknown } | null | undefined;
  const candidates = [payload?.code, detail?.bizCode, err.code];
  for (const c of candidates) {
    if (typeof c === 'string' && c.length > 0) return c;
  }
  return undefined;
}

/**
 * 结构化环路径（若后端给了）。
 * 🚨 api.md §3.3 **只强制「文案里给出环路径」，没有声明结构化字段** —— 所以这里是「有就用」，
 * 取不到返回 undefined，由调用方回退展示后端原文，绝不能因此降级成一句「会成环」。
 * 容忍 `string[]` 与 `'A → B → A'` 两种形态。
 */
export function readCyclePath(err: ApiError): string[] | undefined {
  const payload = err.payload as { cyclePath?: unknown } | null;
  const raw = payload?.cyclePath;
  if (Array.isArray(raw)) {
    const path = raw.filter((x): x is string => typeof x === 'string' && x.length > 0);
    return path.length > 0 ? path : undefined;
  }
  if (typeof raw === 'string' && raw.trim()) {
    const path = raw.split(/\s*(?:\u2192|->)\s*/).filter(Boolean);
    return path.length > 0 ? path : undefined;
  }
  return undefined;
}
