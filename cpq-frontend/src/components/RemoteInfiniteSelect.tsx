import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Select } from 'antd';
import type { SelectProps } from 'antd';

/**
 * 统一的动态下拉数据源：关键词由后端模糊查询，结果按页加载，滚动到底部继续取下一页。
 *
 * 约定 fetchPage 的 page 从 0 开始；返回值可以是 axios api 信封的 data，
 * 也可以直接是 PageResult/数组，组件会兼容两种形态。
 */
export interface RemotePageRequest {
  page: number;
  size: number;
  keyword: string;
}

export interface RemotePageResult<T> {
  content?: T[];
  items?: T[];
  totalElements?: number;
  total?: number;
}

export interface RemoteOption {
  value: string;
  label: React.ReactNode;
  item?: unknown;
}

export interface RemoteInfiniteSelectProps<T> extends Omit<SelectProps, 'options' | 'onSearch' | 'onPopupScroll' | 'filterOption' | 'onSelect'> {
  fetchPage: (request: RemotePageRequest) => Promise<unknown>;
  mapOption: (item: T) => RemoteOption;
  pageSize?: number;
  initialOptions?: RemoteOption[];
  emptySearchText?: string;
  onSelect?: SelectProps['onSelect'];
  onPage?: (items: T[], replace: boolean) => void;
}

function unwrapPage<T>(raw: unknown): RemotePageResult<T> | T[] {
  const response = raw as { data?: unknown } | undefined;
  const payload = response && response.data !== undefined ? response.data : raw;
  if (Array.isArray(payload)) return payload as T[];
  const page = (payload ?? {}) as RemotePageResult<T> & { data?: unknown };
  if (page.data && typeof page.data === 'object') return page.data as RemotePageResult<T>;
  return page;
}

function mergeOptions(...groups: RemoteOption[][]): RemoteOption[] {
  const seen = new Set<string>();
  const result: RemoteOption[] = [];
  for (const group of groups) {
    for (const option of group) {
      const key = String(option.value);
      if (seen.has(key)) continue;
      seen.add(key);
      result.push(option);
    }
  }
  return result;
}

export function RemoteInfiniteSelect<T>({
  fetchPage,
  mapOption,
  pageSize = 50,
  initialOptions = [],
  emptySearchText = '请输入关键词搜索',
  onSelect,
  onPage,
  ...selectProps
}: RemoteInfiniteSelectProps<T>) {
  const [options, setOptions] = useState<RemoteOption[]>(initialOptions);
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(-1);
  const [hasMore, setHasMore] = useState(true);
  const [loading, setLoading] = useState(false);
  const loadingRef = useRef(false);
  const requestSerial = useRef(0);
  const debounceTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const selectedOptions = useRef<RemoteOption[]>(initialOptions);
  const fetchPageRef = useRef(fetchPage);
  const mapOptionRef = useRef(mapOption);
  const onPageRef = useRef(onPage);
  fetchPageRef.current = fetchPage;
  mapOptionRef.current = mapOption;
  onPageRef.current = onPage;

  const loadPage = useCallback(async (nextPage: number, nextKeyword: string, append: boolean) => {
    const serial = ++requestSerial.current;
    loadingRef.current = true;
    setLoading(true);
    try {
      const raw = await fetchPageRef.current({ page: nextPage, size: pageSize, keyword: nextKeyword });
      if (serial !== requestSerial.current) return;

      const pageResult = unwrapPage<T>(raw);
      const rows = Array.isArray(pageResult)
        ? pageResult
        : (pageResult.content ?? pageResult.items ?? []);
      const nextOptions = rows.map(mapOptionRef.current);
      onPageRef.current?.(rows, !append);
      const total = Array.isArray(pageResult) ? undefined : (pageResult.totalElements ?? pageResult.total);
      setOptions((previous) => append
        ? mergeOptions(previous, nextOptions)
        : mergeOptions(selectedOptions.current, nextOptions));
      setPage(nextPage);
      setHasMore(total !== undefined ? (nextPage + 1) * pageSize < total : rows.length >= pageSize);
    } catch {
      if (serial === requestSerial.current) {
        setHasMore(false);
      }
    } finally {
      if (serial === requestSerial.current) {
        loadingRef.current = false;
        setLoading(false);
      }
    }
  }, [pageSize]);

  useEffect(() => {
    void loadPage(0, '', false);
    return () => {
      if (debounceTimer.current) clearTimeout(debounceTimer.current);
      requestSerial.current += 1;
    };
  }, [loadPage]);

  // 编辑态的已选项可能在详情请求完成后才到达，需同步并入，保证远程首屏中没有该项时仍能回显。
  selectedOptions.current = mergeOptions(selectedOptions.current, initialOptions);
  const renderedOptions = mergeOptions(selectedOptions.current, options);

  const handleSearch = (nextKeyword: string) => {
    setKeyword(nextKeyword);
    // 输入变化后立即废弃上一关键词的在途响应，避免防抖窗口内短暂回显旧结果。
    requestSerial.current += 1;
    loadingRef.current = false;
    setLoading(false);
    if (debounceTimer.current) clearTimeout(debounceTimer.current);
    debounceTimer.current = setTimeout(() => {
      void loadPage(0, nextKeyword.trim(), false);
    }, 250);
  };

  const handlePopupScroll: React.UIEventHandler<HTMLDivElement> = (event) => {
    const target = event.currentTarget;
    if (loadingRef.current || !hasMore || page < 0) return;
    if (target.scrollTop + target.clientHeight < target.scrollHeight - 24) return;
    void loadPage(page + 1, keyword.trim(), true);
  };

  const handleSelect: SelectProps['onSelect'] = (value, option) => {
    const selected = option as RemoteOption;
    selectedOptions.current = mergeOptions(selectedOptions.current, [
      { value: String(value), label: selected?.label ?? String(value), item: selected?.item },
    ]);
    onSelect?.(value, option);
  };

  return (
    <Select
      {...selectProps}
      showSearch
      filterOption={false}
      options={renderedOptions}
      loading={loading}
      onSearch={handleSearch}
      onPopupScroll={handlePopupScroll}
      onSelect={handleSelect}
      notFoundContent={loading ? '搜索中…' : (keyword ? '无匹配结果' : emptySearchText)}
    />
  );
}

export default RemoteInfiniteSelect;
