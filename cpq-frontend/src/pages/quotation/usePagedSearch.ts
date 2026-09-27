/**
 * usePagedSearch —— task-260825 报价单大单量前端分页与料号查询。
 *
 * 服务端零改动，全量数据仍一次性拉取并常驻前端内存（`lineItems` 数组本身不被本 hook 改变）。
 * 本 hook 只产出「渲染窗口」相关的**索引**（不是数据副本），供调用方按位置索引回原数组渲染 / 写回，
 * 与 AP-54（过滤后下标当原数组下标）同一纪律：**下标永远指向调用方传入的 `items` 数组本身**，
 * 不指向本 hook 内部任何过滤/切片后的子集。
 *
 * 复用范围：QuotationStep2（编辑页报价侧/核价侧/Excel 视图共享同一实例）、
 * QuotationDetail/ProductDetailViews（详情页，独立实例）、CostingReviewPage（核价工作台，独立实例）。
 *
 * task-260923（F-1）：查询改为「按回车提交」——
 *   - 框内文字（searchInput）只是草稿，打字过程中生效查询词、计数、分页都不变；
 *   - `submitSearch()`（回车）把草稿去首尾空格后作为生效查询词；
 *   - 草稿去首尾空格后为空（✕ / 退格删光 / 只剩空格）时**立即**清空生效查询词，不需要回车；
 *   - `submittedSearchText` 暴露最近一次提交的原文（保留大小写、去首尾空格），供空态副文案引用。
 * 原先的「停止输入 200ms 自动生效」防抖已移除。
 */
import { useEffect, useMemo, useState } from 'react';

// 2026-08-28 用户参数变更（合并后跟进）：默认页大小 100→10，档位新增 10/30/50。
// 分页栏隐藏阈值规则不变——始终跟着"最小可选页大小"走，改档位后阈值自动同步为 10。
export const PAGE_SIZE_OPTIONS = [10, 30, 50, 100, 200, 500] as const;
export const DEFAULT_PAGE_SIZE = 10;
/** AC-2b：总行数低于该阈值（即最小可选页大小）时，分页栏整体不渲染。 */
export const MIN_PAGE_SIZE_FOR_BAR = 10;

function norm(v: unknown): string {
  return v == null ? '' : String(v).toLowerCase();
}

// ─── task-260923：纯函数（hook 与单测共用，避免测试手抄副本） ───────────────────────

/** 搜索框状态：`input` = 框内草稿原文；`submitted` = 最近一次提交的原文（已去首尾空格，保留大小写），空串 = 无查询。 */
export interface PagedSearchState {
  input: string;
  submitted: string;
}

export const INITIAL_PAGED_SEARCH_STATE: PagedSearchState = { input: '', submitted: '' };

/** 框内文字变化：只改草稿；草稿去首尾空格后为空时同时清空生效查询（task-260923 D-2「变空即恢复」）。 */
export function applySearchInput(prev: PagedSearchState, next: string): PagedSearchState {
  if (next.trim() === '') return { input: next, submitted: '' };
  return { input: next, submitted: prev.submitted };
}

/** 回车提交：以 `raw`（缺省取当前草稿）去首尾空格后作为生效查询。 */
export function applySearchSubmit(prev: PagedSearchState, raw?: string): PagedSearchState {
  const input = raw ?? prev.input;
  return { input, submitted: input.trim() };
}

/** 按生效查询词（已小写）在 items 中找命中下标；空词 = 全部。口径：大小写不敏感子串，任一字段命中即算。 */
export function matchSearchPositions<T>(
  items: T[],
  getSearchFields: (item: T) => Array<string | undefined | null>,
  term: string,
): number[] {
  if (!term) return items.map((_, i) => i);
  const out: number[] = [];
  items.forEach((item, i) => {
    const fields = getSearchFields(item);
    if (fields.some(f => f != null && norm(f).includes(term))) out.push(i);
  });
  return out;
}

/**
 * 这次 keydown 是否应当提交搜索（task-260923 D-6）：只认 Enter，且输入法组字中的回车一律不算。
 * - `isComposing=true`：Chrome/Firefox 组字中的回车（AC-8 的合成事件同此形态）；
 * - `keyCode===229`：Safari 在 compositionend 之后才派发确认上屏的那次 keydown，isComposing 已是 false，
 *   但 keyCode 仍为 229（IME 处理中），不排除就会把「上屏」误当成「搜索」。
 */
export function isSubmitEnterKey(e: { key: string; isComposing?: boolean; keyCode?: number }): boolean {
  return e.key === 'Enter' && !e.isComposing && e.keyCode !== 229;
}

export interface UsePagedSearchOptions<T> {
  /** 全量（或已按业务规则过滤过 PART 等不可渲染行的）有序集合，索引即本 hook 全部输出索引的基准。 */
  items: T[];
  /** 取出该条目用于料号匹配的候选字段（大小写不敏感子串匹配，任一命中即算）。 */
  getSearchFields: (item: T) => Array<string | undefined | null>;
  pageSizeOptions?: readonly number[];
  defaultPageSize?: number;
}

export interface UsePagedSearchResult<T> {
  page: number;
  setPage: (p: number) => void;
  pageSize: number;
  setPageSize: (s: number) => void;
  /** 输入框草稿原文，用于受控 Input（task-260923：打字不触发查询）。 */
  searchInput: string;
  /** 改草稿；草稿去首尾空格后为空时**立即**清空生效查询（✕ / 删光 / 只剩空格）。 */
  setSearchInput: (s: string) => void;
  /** 回车提交：以 `raw`（缺省取当前草稿）去首尾空格后作为生效查询。 */
  submitSearch: (raw?: string) => void;
  /** 最近一次提交的原文（去首尾空格、保留大小写），空串表示无查询。供空态副文案引用。 */
  submittedSearchText: string;
  /** 生效查询词（最近一次提交的原文转小写），空串表示无查询。匹配与黄底高亮都跟随它。 */
  searchTerm: string;
  clearSearch: () => void;
  /** 总行数（未按查询过滤）。 */
  total: number;
  /** 查询命中数（无查询时等于 total）。 */
  matchedTotal: number;
  /** 是否处于「有查询」状态。 */
  isSearching: boolean;
  /** 当前页窗口内的条目（对象引用取自 items，未克隆）。 */
  pagedItems: T[];
  /** 当前页窗口内条目在 items 中的原始下标，与 pagedItems 一一对应（AP-54 写路径纪律）。 */
  pagedPositions: number[];
  /** 分页栏是否应渲染（AC-2b：总行数 < 最小页大小时不渲染，由调用方再叠加 mainTab===comparison 等条件）。 */
  showPager: boolean;
  /** 定位到 items 中第 pos 个元素所在的页（供 AC-15 冲突跨页跳转复用），并清空查询以确保目标可见。 */
  locateToPosition: (pos: number) => void;
  pageSizeOptions: readonly number[];
}

export function usePagedSearch<T>(opts: UsePagedSearchOptions<T>): UsePagedSearchResult<T> {
  const {
    items,
    getSearchFields,
    pageSizeOptions = PAGE_SIZE_OPTIONS,
    defaultPageSize = DEFAULT_PAGE_SIZE,
  } = opts;

  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(defaultPageSize);
  // task-260923（F-1）：草稿与生效查询放在同一个 state 里，保证「删光即恢复」与草稿更新同一次提交、无中间态
  const [searchState, setSearchState] = useState<PagedSearchState>(INITIAL_PAGED_SEARCH_STATE);
  const searchInput = searchState.input;
  const submittedSearchText = searchState.submitted;
  const searchTerm = useMemo(() => submittedSearchText.toLowerCase(), [submittedSearchText]);

  const setSearchInput = (next: string) => setSearchState(prev => applySearchInput(prev, next));
  const submitSearch = (raw?: string) => setSearchState(prev => applySearchSubmit(prev, raw));

  const matchedPositions = useMemo(
    () => matchSearchPositions(items, getSearchFields, searchTerm),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [items, searchTerm],
  );

  const total = items.length;
  const matchedTotal = matchedPositions.length;
  const isSearching = searchTerm.length > 0;

  // 页大小变化 / 查询词变化 → 回第 1 页
  useEffect(() => { setPage(1); }, [pageSize, searchTerm]);

  // items 长度变化（加/删产品）→ 钳制页码到合法区间
  const pageCount = Math.max(1, Math.ceil(matchedTotal / pageSize));
  useEffect(() => {
    setPage(p => (p > pageCount ? pageCount : p < 1 ? 1 : p));
  }, [pageCount]);

  const pagedPositions = useMemo(
    () => matchedPositions.slice((page - 1) * pageSize, page * pageSize),
    [matchedPositions, page, pageSize],
  );
  const pagedItems = useMemo(() => pagedPositions.map(pos => items[pos]), [pagedPositions, items]);

  const showPager = total >= MIN_PAGE_SIZE_FOR_BAR;

  const clearSearch = () => {
    setSearchState(INITIAL_PAGED_SEARCH_STATE);
  };

  const locateToPosition = (pos: number) => {
    // 目标可能被当前查询过滤在外 —— 先清空查询保证目标一定在命中集合里
    setSearchState(INITIAL_PAGED_SEARCH_STATE);
    const target = Math.max(1, Math.ceil((pos + 1) / pageSize));
    setPage(target);
  };

  return {
    page, setPage, pageSize, setPageSize,
    searchInput, setSearchInput, submitSearch, submittedSearchText, searchTerm, clearSearch,
    total, matchedTotal, isSearching,
    pagedItems, pagedPositions,
    showPager,
    locateToPosition,
    pageSizeOptions: pageSizeOptions as readonly number[],
  };
}
