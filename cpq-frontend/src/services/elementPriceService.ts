import api from './api';
import type { ElementLatestPriceDTO } from '../types/element-price-strategy';

// task-0723: 旧「元素价格中心」(ElementPriceCenterPage / ManualPriceEntryDrawer / ElementPriceHint)
// 已随其页面一并下线 —— getReference / listHistory / upsertManual / listAvailableElements 及其
// mock 数据、依赖的 ../types/element-price 类型全部随之移除（均已 0 调用方）。
// 只保留 task-0722 新增的 listLatestBySource，供 ElementEditDrawer（活跃·元素管理）调用。

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
