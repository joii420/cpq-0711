/**
 * PagingBar —— task-260825 报价单大单量前端分页与料号查询。
 *
 * 视觉基准：`dev-docs/task-260825-报价单大单量分页与料号查询/原型图/01-...默认.html` 的 `.pgbar`
 * 与 `03-...空态与禁用态.html` 的禁用态/空态。三处调用方（编辑页 QuotationStep2、详情页、核价工作台）
 * 共用同一份视觉，AC-1/AC-2/AC-2b 的「两级 Segmented 之下、独立一行」「顶部+底部各一个」由调用方负责摆位，
 * 本组件只负责单条分页栏本身的内容与状态。
 */
import React from 'react';
import { Pagination, Input } from 'antd';
import { SearchOutlined } from '@ant-design/icons';

/**
 * 实测发现：本项目 antd 版本下，`<Pagination>` 独立使用（不经 `<Table>`）时未能从
 * `ConfigProvider locale={zhCN}` 上下文拿到 `items_per_page` 等文案，页大小选项会回落成
 * 英文 "100 / page"（AC-2 要求「条/页」）。显式传 `locale` 兜底，不依赖上下文链路是否生效。
 * （该现象疑似项目级 antd 6.x 定位问题，不在本任务范围内，已在回报中登记。）
 */
export const ZH_PAGINATION_LOCALE = {
  items_per_page: '条/页',
  jump_to: '跳至',
  jump_to_confirm: '确定',
  page: '页',
  prev_page: '上一页',
  next_page: '下一页',
  prev_5: '向前 5 页',
  next_5: '向后 5 页',
  prev_3: '向前 3 页',
  next_3: '向后 3 页',
  page_size: '页码',
};

export interface PagingBarProps {
  total: number;
  matchedTotal: number;
  isSearching: boolean;
  page: number;
  pageSize: number;
  pageSizeOptions: readonly number[];
  onPageChange: (page: number, pageSize: number) => void;
  onPageSizeChange?: (size: number) => void;
  searchValue: string;
  onSearchChange: (v: string) => void;
  searchPlaceholder?: string;
  /** 翻页/搜索前先把当前受控输入 blur 落值，避免未提交编辑随卡片卸载丢失（AP-54 家族相关纪律）。 */
  onBeforeChange?: () => void;
}

const PagingBar: React.FC<PagingBarProps> = ({
  total,
  matchedTotal,
  isSearching,
  page,
  pageSize,
  pageSizeOptions,
  onPageChange,
  onSearchChange,
  searchValue,
  searchPlaceholder = '料号 / 客户产品编号，支持模糊匹配',
  onBeforeChange,
}) => {
  const totalText = !isSearching
    ? <span>共 <b>{total}</b> 条</span>
    : matchedTotal === 0
      ? <span>未匹配到料号（共 <b>{total}</b> 条）</span>
      : <span>匹配 <b>{matchedTotal}</b> 条 / 共 {total} 条</span>;

  return (
    <div
      className="qt-pgbar"
      data-testid="task260825-paging-bar"
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: 12,
        flexWrap: 'wrap',
        padding: '8px 10px',
        background: '#fafafa',
        border: '1px solid #f0f0f0',
        borderRadius: 6,
        marginBottom: 12,
      }}
    >
      <Input
        allowClear
        prefix={<SearchOutlined style={{ color: 'rgba(0,0,0,.45)' }} />}
        placeholder={searchPlaceholder}
        value={searchValue}
        onChange={e => onSearchChange(e.target.value)}
        style={{ maxWidth: 320 }}
        data-testid="paging-search-input"
      />
      <span style={{ color: 'rgba(0,0,0,.65)', fontSize: 13, whiteSpace: 'nowrap' }}>{totalText}</span>
      <div style={{ flex: 1 }} />
      {matchedTotal > 0 && (
        <Pagination
          size="small"
          current={page}
          pageSize={pageSize}
          total={matchedTotal}
          pageSizeOptions={pageSizeOptions as number[]}
          showSizeChanger
          showQuickJumper
          showTotal={() => ''}
          locale={ZH_PAGINATION_LOCALE}
          onChange={(p, ps) => {
            onBeforeChange?.();
            onPageChange(p, ps);
          }}
        />
      )}
    </div>
  );
};

export default PagingBar;
