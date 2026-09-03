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
 */
import { useEffect, useMemo, useRef, useState } from 'react';

// 2026-08-28 用户参数变更（合并后跟进）：默认页大小 100→10，档位新增 10/30/50。
// 分页栏隐藏阈值规则不变——始终跟着"最小可选页大小"走，改档位后阈值自动同步为 10。
export const PAGE_SIZE_OPTIONS = [10, 30, 50, 100, 200, 500] as const;
export const DEFAULT_PAGE_SIZE = 10;
/** AC-2b：总行数低于该阈值（即最小可选页大小）时，分页栏整体不渲染。 */
export const MIN_PAGE_SIZE_FOR_BAR = 10;
/** 料号查询防抖：1845 条纯内存过滤很快，防抖只为避免逐字符重渲染卡片。 */
const SEARCH_DEBOUNCE_MS = 200;

function norm(v: unknown): string {
  return v == null ? '' : String(v).toLowerCase();
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
  /** 输入框实时值（未防抖），用于受控 Input。 */
  searchInput: string;
  setSearchInput: (s: string) => void;
  /** 防抖后的查询词（trim 后小写），空串表示无查询。 */
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
  const [searchInput, setSearchInput] = useState('');
  const [searchTerm, setSearchTerm] = useState('');

  // 200ms 防抖：searchInput → searchTerm
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => {
    if (debounceRef.current) clearTimeout(debounceRef.current);
    debounceRef.current = setTimeout(() => {
      setSearchTerm(searchInput.trim().toLowerCase());
    }, SEARCH_DEBOUNCE_MS);
    return () => {
      if (debounceRef.current) clearTimeout(debounceRef.current);
    };
  }, [searchInput]);

  const matchedPositions = useMemo(() => {
    if (!searchTerm) return items.map((_, i) => i);
    const out: number[] = [];
    items.forEach((item, i) => {
      const fields = getSearchFields(item);
      if (fields.some(f => f != null && norm(f).includes(searchTerm))) out.push(i);
    });
    return out;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [items, searchTerm]);

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
    setSearchInput('');
    setSearchTerm('');
  };

  const locateToPosition = (pos: number) => {
    // 目标可能被当前查询过滤在外 —— 先清空查询保证目标一定在命中集合里
    setSearchInput('');
    setSearchTerm('');
    const target = Math.max(1, Math.ceil((pos + 1) / pageSize));
    setPage(target);
  };

  return {
    page, setPage, pageSize, setPageSize,
    searchInput, setSearchInput, searchTerm, clearSearch,
    total, matchedTotal, isSearching,
    pagedItems, pagedPositions,
    showPager,
    locateToPosition,
    pageSizeOptions: pageSizeOptions as readonly number[],
  };
}
