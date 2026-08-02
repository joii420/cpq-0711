import type { ApiError } from '../../../services/api';

/**
 * 从 ApiError 里取后端错误信封携带的业务字段（code + 额外字段，如 removedMaterialNos）。
 *
 * ⚠️ 已知的信封形状不确定性（后端尚未实现，无法实测，先做双重兜底）：
 * - `services/api.ts` 的 `buildApiError` 把 `err.payload` 设为 `error.response.data.data`
 *   （既有惯例：错误体嵌一层 `data`，如 BomTreeDeleteConfirmDrawer 的用法）；
 * - 但 api.md §0.3/§1.4/§1.6/§1.11 给出的错误体示例是**扁平**结构
 *   （`{ "code":"REMOVAL_NEEDS_CONFIRM", "removedMaterialNos":[...], ... }` 直接在顶层，无嵌套）。
 * 两种形状都试一遍，取不到 code 字段则返回 null。等后端联调后按实测结果收敛。
 */
export function extractErrorPayload<T extends { code: string }>(e: unknown): T | null {
  const err = e as ApiError & { response?: { data?: any } };
  const fromPayload = err?.payload as any;
  if (fromPayload && typeof fromPayload === 'object' && 'code' in fromPayload) {
    return fromPayload as T;
  }
  const raw = (e as any)?.response?.data;
  if (raw && typeof raw === 'object' && 'code' in raw) {
    return raw as T;
  }
  return null;
}
