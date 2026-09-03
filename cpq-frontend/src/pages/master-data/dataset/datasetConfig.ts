// ─────────────────────────────────────────────────────────────────────────────
// 三个数据集的静态配置（task-260902）
// 数据集中文名 / 轴列标题 / 导入按钮文案 / 模板名，集中一处，避免各页面各写一遍漂移。
// ⚠️ 「带版本 sheet 数」不写死在前端 —— tab 数由 `GET /dataset/{ds}/sheets` 决定（F-3）。
//    下表的 sheetCountHint 仅用于文案提示，不参与渲染逻辑。
// ─────────────────────────────────────────────────────────────────────────────
import type { DatasetKey } from './types';

export interface DatasetConfig {
  key: DatasetKey;
  /** 数据集中文名（页签 label / 抽屉标题后缀 / 导入抽屉标题后缀） */
  label: string;
  /** 轴列标题（列表首列 + 搜索占位符） */
  axisLabel: string;
  /** 导入按钮与导入抽屉的动作文案 */
  importActionLabel: string;
  /** 该数据集对应的 Excel 模板俗称（导入抽屉说明文案用） */
  templateAlias: string;
  /** 仅文案提示用，不参与渲染 */
  sheetCountHint: number;
}

export const DATASETS: Record<DatasetKey, DatasetConfig> = {
  quote: {
    key: 'quote',
    label: '报价数据',
    axisLabel: '销售料号',
    importActionLabel: '导入报价数据',
    templateAlias: '报价',
    sheetCountHint: 13,
  },
  'cost-basic': {
    key: 'cost-basic',
    label: '基础核价',
    axisLabel: '生产料号',
    importActionLabel: '导入核价数据',
    templateAlias: '核价2',
    sheetCountHint: 9,
  },
  'cost-detail': {
    key: 'cost-detail',
    label: '详细核价',
    axisLabel: '生产料号',
    importActionLabel: '导入核价数据',
    templateAlias: '核价1',
    sheetCountHint: 17,
  },
};

/** 写权限角色（api.md §0：PUT rows / POST import 仅这两个角色，AC-31） */
export const DATASET_EDIT_ROLES = ['PRICING_MANAGER', 'SYSTEM_ADMIN'];

/** AC-31 禁用态 hover 文案 */
export const NO_PERMISSION_TIP = '需要核价管理员权限';

/** AC-29 历史版本禁用态 hover 文案 */
export const HISTORY_READONLY_TIP = '历史版本只读，请切回最新版本后编辑';

/** 原型「核价数据-抽屉-空态」：无行时保存按钮的禁用原因 */
export const NO_ROWS_TIP = '请先新增至少一行数据';

/**
 * api.md §7「整组清空 422」的原文。
 * 前端在**删除按钮**上就挡住「删到 0 行」，不让用户点了才收到 422（2026-09-03 契约补定义）。
 */
export const KEEP_ONE_ROW_TIP = '至少保留一行数据；整组清空不在本期范围';

/** AC-32 空态文案 */
export const EMPTY_ROWS_TEXT = '暂无数据，可点「新增行」录入或从 Excel 导入';
