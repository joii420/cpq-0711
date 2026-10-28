import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Select, Spin, type SelectProps } from 'antd';
import { customerService } from '../services/customerService';

export interface CustomerOption {
  id: string;
  name: string;
  code?: string;
}

type CustomerSelectCommonProps = {
  initialOptions?: Array<{ value: string; label: string }>;
};

type CustomerSelectProps =
  | (Omit<SelectProps<string>, 'options' | 'onChange' | 'onSearch' | 'onPopupScroll' | 'filterOption' | 'showSearch' | 'loading'>
      & CustomerSelectCommonProps
      & { mode?: undefined; onChange?: (value: string | undefined, option?: any) => void })
  | (Omit<SelectProps<string[]>, 'options' | 'onChange' | 'onSearch' | 'onPopupScroll' | 'filterOption' | 'showSearch' | 'loading'>
      & CustomerSelectCommonProps
      & { mode: 'multiple' | 'tags'; onChange?: (value: string[] | undefined, option?: any) => void });

const PAGE_SIZE = 50;
const EMPTY_INITIAL_OPTIONS: Array<{ value: string; label: string }> = [];

/** Customer selector backed by the customer table, with server-side search and scroll pagination. */
const CustomerSelect: React.FC<CustomerSelectProps> = ({ onChange, value, initialOptions = EMPTY_INITIAL_OPTIONS, ...props }) => {
  const initialCustomerOptions = useMemo(
    () => initialOptions.map(option => ({ id: option.value, name: option.label })),
    [initialOptions],
  );
  const [options, setOptions] = useState<CustomerOption[]>(initialCustomerOptions);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [keyword, setKeyword] = useState('');
  const [loading, setLoading] = useState(false);
  const requestSeq = useRef(0);
  const searchTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const mergeOptions = useCallback((current: CustomerOption[], incoming: CustomerOption[]) => {
    const byId = new Map(current.map(item => [item.id, item]));
    incoming.forEach(item => byId.set(item.id, item));
    return Array.from(byId.values());
  }, []);

  const loadPage = useCallback(async (nextPage: number, nextKeyword: string, append: boolean) => {
    const seq = ++requestSeq.current;
    setLoading(true);
    try {
      const res = await customerService.list({
        page: nextPage,
        size: PAGE_SIZE,
        keyword: nextKeyword.trim() || undefined,
      });
      if (seq !== requestSeq.current) return;
      const result = res.data || {};
      const rows: CustomerOption[] = result.content || [];
      setOptions(prev => append ? mergeOptions(prev, rows) : mergeOptions(initialCustomerOptions, rows));
      setPage(nextPage);
      setTotalPages(result.totalPages ?? Math.ceil((result.totalElements || 0) / PAGE_SIZE));
    } catch {
      if (seq === requestSeq.current && !append) setOptions([]);
    } finally {
      if (seq === requestSeq.current) setLoading(false);
    }
  }, [initialCustomerOptions, mergeOptions]);

  const ensureSelectedOption = useCallback(async (selectedValue?: string | string[]) => {
    const selectedIds = (Array.isArray(selectedValue) ? selectedValue : [selectedValue]).filter(Boolean) as string[];
    const missingIds = selectedIds.filter(selectedId => !options.some(item => item.id === selectedId));
    for (const selectedId of missingIds) {
      try {
        const res = await customerService.getById(selectedId);
        const customer = res.data;
        if (customer?.id) setOptions(prev => mergeOptions(prev, [customer]));
      } catch {
        // Keep the selected value even if its display record cannot be refreshed.
      }
    }
  }, [mergeOptions, options]);

  useEffect(() => {
    loadPage(0, '', false);
  }, [loadPage]);

  useEffect(() => {
    ensureSelectedOption(value ?? undefined);
  }, [ensureSelectedOption, value]);

  useEffect(() => () => {
    if (searchTimer.current) clearTimeout(searchTimer.current);
  }, []);

  const handleSearch = (nextKeyword: string) => {
    setKeyword(nextKeyword);
    if (searchTimer.current) clearTimeout(searchTimer.current);
    searchTimer.current = setTimeout(() => loadPage(0, nextKeyword, false), 250);
  };

  const handlePopupScroll: React.UIEventHandler<HTMLDivElement> = (event) => {
    const target = event.currentTarget;
    const reachedBottom = target.scrollTop + target.clientHeight >= target.scrollHeight - 16;
    if (reachedBottom && !loading && page + 1 < totalPages) {
      loadPage(page + 1, keyword, true);
    }
  };

  const selectOptions = options.map(customer => ({
    ...customer,
    value: customer.id,
    label: customer.code ? `${customer.code} / ${customer.name}` : customer.name,
  }));

  return (
    <Select<string | string[]>
      {...(props as SelectProps<string | string[]>)}
      value={value || undefined}
      options={selectOptions}
      showSearch
      filterOption={false}
      onSearch={handleSearch}
      onPopupScroll={handlePopupScroll}
      onChange={(nextValue, option) => (onChange as ((value: string | string[] | undefined, option?: any) => void) | undefined)?.(nextValue, option)}
      loading={loading}
      notFoundContent={loading ? <Spin size="small" /> : '暂无匹配客户'}
    />
  );
};

export default CustomerSelect;
