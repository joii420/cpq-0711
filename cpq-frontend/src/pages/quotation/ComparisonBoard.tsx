/**
 * ComparisonBoard —— 报价单比对视图容器（task-0717，替代旧 ComparisonView / ReadonlyComparison）。
 *
 * 三处共用挂载：
 *   - 报价单编辑页（QuotationStep2）  bucket=SALES   readonly=false frozen=false
 *   - 报价单详情页 / 核价单页面（ProductDetailViews，经 coid 区分 bucket）readonly/frozen 见调用方
 *
 * 数据装配（api.md）：并发拉 meta/data/config 三端点；config.columns=null 时前端种入默认列
 * （产品卡片总计），不立即落库。新增/删除/改阈值/改顺序只改本地 columns + PUT config，不重拉 data。
 * 前端不新增任何取值逻辑，只消费 data 矩阵做组装/差异/着色/排序/过滤/分页（fronttask.md §0.4）。
 */
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { Spin, Alert, Pagination, message } from 'antd';
import { comparisonViewService } from '../../services/comparisonViewService';
import type {
  ComparisonBucket,
  ComparisonMetaDTO,
  ComparisonRowDTO,
} from '../../services/comparisonViewService';
import {
  ensureColumns,
  filterRowsByPartNo,
  sortRowsDiffFirst,
  paginateRows,
  nextSortOrder,
  buildTabPairColumns,
} from './comparisonMapping';
import type { ColumnDef, LinkPairInput } from './comparisonMapping';
import { ComparisonTable } from './ComparisonTable';
import { ComparisonToolbar } from './ComparisonToolbar';
import { LinkConfigDrawer } from './LinkConfigDrawer';

export interface ComparisonBoardProps {
  quotationId: string;
  bucket: ComparisonBucket;
  /** 详情页=true：隐藏「新增比对」、列删除✕、阈值⚙，不可打开抽屉，不调 PUT。 */
  readonly?: boolean;
  /** 详情页/已提交核价单=true → data 端点带 frozen=true（冻结快照口径）。 */
  frozen?: boolean;
}

const PAGE_SIZE_OPTIONS = [10, 20, 50];

export const ComparisonBoard: React.FC<ComparisonBoardProps> = ({
  quotationId, bucket, readonly = false, frozen = false,
}) => {
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [meta, setMeta] = useState<ComparisonMetaDTO | null>(null);
  const [rows, setRows] = useState<ComparisonRowDTO[]>([]);
  const [columns, setColumns] = useState<ColumnDef[]>([]);

  const [onlyDiff, setOnlyDiff] = useState(false);
  const [filterText, setFilterText] = useState('');
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [drawerOpen, setDrawerOpen] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [metaRes, dataRes, configRes] = await Promise.all([
        comparisonViewService.getMeta(quotationId),
        comparisonViewService.getData(quotationId, frozen),
        comparisonViewService.getConfig(quotationId, bucket),
      ]);
      setMeta(metaRes.data);
      setRows(dataRes.data?.rows || []);
      setColumns(ensureColumns(configRes.data?.columns));
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : '加载比对视图失败');
    } finally {
      setLoading(false);
    }
  }, [quotationId, bucket, frozen]);

  useEffect(() => {
    load();
  }, [load]);

  // 过滤 → 排序（差异料号前置）→ 分页；三者均以「料号」为粒度（一个料号 3 行不切断）。
  const filteredRows = useMemo(() => filterRowsByPartNo(rows, filterText), [rows, filterText]);
  const sortedRows = useMemo(
    () => sortRowsDiffFirst(filteredRows, columns, onlyDiff),
    [filteredRows, columns, onlyDiff],
  );
  const totalParts = sortedRows.length;
  const totalPages = Math.max(1, Math.ceil(totalParts / pageSize));
  const safePage = Math.min(page, totalPages);
  const pageRows = useMemo(
    () => paginateRows(sortedRows, safePage, pageSize),
    [sortedRows, safePage, pageSize],
  );

  const persistColumns = useCallback(async (next: ColumnDef[]) => {
    if (readonly) return;
    setColumns(next);
    try {
      await comparisonViewService.putConfig(quotationId, bucket, next);
    } catch (e: any) {
      message.error(e?.message || '保存比对配置失败');
    }
  }, [readonly, quotationId, bucket]);

  const handleRemoveColumn = useCallback((id: string) => {
    if (readonly) return;
    const target = columns.find((c) => c.id === id);
    if (!target || target.kind === 'PRODUCT_TOTAL') return; // 默认列不可删（双保险）
    const next = columns.filter((c) => c.id !== id);
    persistColumns(next);
    message.success('已删除比对列');
  }, [readonly, columns, persistColumns]);

  const handleUpdateThreshold = useCallback((id: string, threshold: number) => {
    if (readonly) return;
    const next = columns.map((c) => (c.id === id ? { ...c, threshold } : c));
    persistColumns(next);
    message.success('阈值已更新');
  }, [readonly, columns, persistColumns]);

  const handleConfirmLink = useCallback((pairs: LinkPairInput[]) => {
    const startSortOrder = nextSortOrder(columns);
    const newCols = buildTabPairColumns(pairs, startSortOrder);
    const next = [...columns, ...newCols];
    setDrawerOpen(false);
    persistColumns(next).then(() => {
      message.success(`已添加 ${newCols.length} 个比对列`);
    });
  }, [columns, persistColumns]);

  const handleToggleOnlyDiff = (checked: boolean) => {
    setOnlyDiff(checked);
    setPage(1);
  };
  const handleFilterChange = (text: string) => {
    setFilterText(text);
    setPage(1);
  };
  const handlePageChange = (p: number, size: number) => {
    if (size !== pageSize) {
      setPageSize(size);
      setPage(1);
    } else {
      setPage(p);
    }
  };

  if (loading) {
    return <div style={{ textAlign: 'center', padding: 48 }}><Spin tip="加载比对视图…" /></div>;
  }
  if (error) {
    return <Alert type="error" showIcon message={error} style={{ margin: 16 }} />;
  }

  return (
    <div style={{ padding: '4px 0 24px' }}>
      <ComparisonToolbar
        readonly={readonly}
        onlyDiff={onlyDiff}
        onToggleOnlyDiff={handleToggleOnlyDiff}
        filterText={filterText}
        onFilterChange={handleFilterChange}
        onAddCompare={() => setDrawerOpen(true)}
        totalParts={totalParts}
        totalColumns={columns.length}
      />

      <ComparisonTable
        columns={columns}
        rows={pageRows}
        readonly={readonly}
        onRemoveColumn={handleRemoveColumn}
        onUpdateThreshold={handleUpdateThreshold}
      />

      <div style={{ display: 'flex', justifyContent: 'flex-end', margin: '14px 24px 0' }}>
        <Pagination
          current={safePage}
          pageSize={pageSize}
          total={totalParts}
          pageSizeOptions={PAGE_SIZE_OPTIONS}
          showSizeChanger
          showTotal={(total) => `共 ${total} 个料号`}
          onChange={handlePageChange}
          onShowSizeChange={handlePageChange}
        />
      </div>

      {!readonly && (
        <LinkConfigDrawer
          open={drawerOpen}
          meta={meta}
          onClose={() => setDrawerOpen(false)}
          onConfirm={handleConfirmLink}
        />
      )}
    </div>
  );
};

export default ComparisonBoard;
