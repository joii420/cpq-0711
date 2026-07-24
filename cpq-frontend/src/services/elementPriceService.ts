import api from './api';
import type { ElementLatestPriceDTO } from '../types/element-price-strategy';

/**
 * 元素价格 · 「各源最新价格」只读查询服务
 *
 * ⚠️ update-0724 · F5：v1「元素价格中心」整体下线 —— 本文件原有的
 * getReference / listHistory / upsertManual / listAvailableElements 四个方法
 * （对接已下线的 /api/cpq/element-prices/** 复数端点）已删除，不留休眠代码。
 * 仅保留 listLatestBySource（对接活跃端点 /api/cpq/element-price/latest-by-source，单数命名空间）。
 */
export const elementPriceService = {
  // ────────────────────────────────────────────────────────────────
  // task-0722 新增 · 元素抽屉「各源最新价格」区块用
  // GET /api/cpq/element-price/latest-by-source?elementCode=Cu（api.md §4.1）
  // 每个有过价格记录的源返回一行；已停用的源照常返回，前端按 sourceStatus 置灰标注。
  // ────────────────────────────────────────────────────────────────

  /** 某元素在各价格源下的最新价（元素编辑抽屉·仅编辑态调用） */
  listLatestBySource: async (elementCode: string): Promise<ElementLatestPriceDTO[]> => {
    const res = await api.get('/element-price/latest-by-source', { params: { elementCode } });
    return (res as unknown as ElementLatestPriceDTO[]) ?? [];
  },
};
