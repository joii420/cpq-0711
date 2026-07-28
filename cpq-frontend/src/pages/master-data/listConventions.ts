/**
 * 主数据维护 4 个页签的统一版式约定（task-0728 · F0-1）。
 * 新增页签一律引用本文件，不要各自写死。
 *
 * 覆盖页签：料号核价 / 材质 / 元素 / 工序
 * 其中「材质」「元素」两个页签的组件在 `pages/config/` 下，跨目录 import 本文件即可（不必移动文件）：
 *   import { SEARCH_WIDTH, commonPagination } from '../master-data/listConventions';
 */
import type { CSSProperties } from 'react';

/** 搜索框统一宽度（`Input.Search`，px） */
export const SEARCH_WIDTH = 280;

/** 过滤下拉最小宽度（`Select`，px） */
export const FILTER_MIN_WIDTH = 150;

/** 搜索输入防抖（ms） */
export const SEARCH_DEBOUNCE_MS = 300;

/** 默认页大小 */
export const DEFAULT_PAGE_SIZE = 20;

/** 页大小候选项 */
export const PAGE_SIZE_OPTIONS = ['10', '20', '50', '100'];

/**
 * 四个页签共用的分页配置。
 *
 * 服务端分页页签（料号核价 / 工序）把 current / pageSize / total / onChange 覆盖掉即可：
 *   pagination={{ ...commonPagination, current: page, pageSize: size, total, onChange }}
 *
 * 前端分页页签（材质 / 元素）直接展开即可，Table 自带前端分页：
 *   pagination={{ ...commonPagination }}
 *
 * 注意：展开（`{...commonPagination}`）后得到的是可变对象，可直接赋给 antd 的 `pagination` prop；
 * 不要把 `commonPagination` 本身（readonly）直接传进去。
 */
export const commonPagination = {
  showSizeChanger: true,
  pageSizeOptions: PAGE_SIZE_OPTIONS,
  defaultPageSize: DEFAULT_PAGE_SIZE,
  showTotal: (t: number) => `共 ${t} 条`,
} as const;

/**
 * F0-3 统一工具栏行样式：一行两组 —— 左＝查询（搜索框 → 过滤下拉），右＝动作（次级下拉 → 刷新 → 导入 → 新建）。
 *
 * 用法分两种，注意不要套两层 flex，否则右组会被挤到左边：
 *
 * 1）不走 SelectableTable 的页签（料号核价，用裸 `<Table>`）—— 自己套这一层：
 *      <div style={TOOLBAR_ROW_STYLE}>
 *        <Space wrap>搜索框 + 过滤下拉</Space>
 *        <Space wrap>刷新 + 导入</Space>
 *      </div>
 *
 * 2）走 SelectableTable 的页签（材质 / 元素 / 工序）—— 不要再包 div：
 *    SelectableTable 内部已用同一套样式包住 toolbar（见 components/SelectableTable.tsx 的 toolbar 容器），
 *    直接把左右两组作为并列子节点传给 toolbar：
 *      toolbar={
 *        <>
 *          <Space wrap>搜索框 + 过滤下拉</Space>
 *          <Space wrap>次级下拉 + 刷新 + 导入 + 新建</Space>
 *        </>
 *      }
 */
export const TOOLBAR_ROW_STYLE: CSSProperties = {
  display: 'flex',
  justifyContent: 'space-between',
  alignItems: 'center',
  gap: 8,
  flexWrap: 'wrap',
  marginBottom: 12,
};
