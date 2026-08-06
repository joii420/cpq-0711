import React, { useCallback, useEffect, useState } from 'react';
import { Select, Table, Button, InputNumber, Tag, message, Alert, Popconfirm } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { priceAdjustService } from '../../../services/priceAdjustService';
import { LinkConfigDrawer } from '../../quotation/LinkConfigDrawer';
import { buildTabPairColumns, nextSortOrder } from '../../quotation/comparisonMapping';
import type { LinkPairInput } from '../../quotation/comparisonMapping';
import type { ComparisonMetaDTO } from '../../../services/comparisonViewService';
import type { PriceAdjustColumnDef, TemplateSeriesDTO } from '../../../types/price-adjust';

export interface ComparisonColumnPanelProps {
  customerNo: string;
}

const makeDefaultColumn = (): PriceAdjustColumnDef => ({
  id: 'col-default', kind: 'PRODUCT_TOTAL', sortOrder: 0, threshold: 0,
  quoteLabel: '产品总价', costingLabel: '产品总价', removable: false,
});

/**
 * 屏 1 · 比对列配置区（fronttask §1.4 / api.md §1.8-1.10）。
 * 🔒 配置维度 =「客户 × 模板系列」（§11.5.4 改判裁决 43），顶部模板系列选择器选中后才连线。
 * 🔒 这是比对列的唯一写入口（屏 4 只读）；默认「产品总价」列 removable=false 不可删。
 * 复用 task-0717 的 LinkConfigDrawer（连线交互零改动）与 comparisonMapping 的纯函数
 * （buildTabPairColumns/nextSortOrder），本文件只负责把连线抽屉产出的 pairs 转成
 * 带 removable 的 PriceAdjustColumnDef 并走 price-adjust 专属的 PUT 端点。
 *
 * 📌 meta 数据源（页签/可比对值目录）：端点已于 2026-08-06 由后端补交（api.md §1.10a）
 * GET /price-adjust/template-series/{id}/comparison-view-meta，「配置比对列」已正常打开。
 * 该端点与 task-0717 的 GET /quotations/{id}/comparison-view/meta **URL 不同、DTO 形状相同**
 * （前者按 quotationId、本屏按 templateSeriesId，语义不同不能共用 URL；但页签→可比对值的
 * 目录结构一致，故照搬 ComparisonMetaDTO，LinkConfigDrawer 零改动复用）——
 * 这解释了为什么这里的类型是从 task-0717 引来的，别当成误用。
 * 此后再打不开就是**真故障**（网络/权限/数据），不是"接口没做"。
 */
const ComparisonColumnPanel: React.FC<ComparisonColumnPanelProps> = ({ customerNo }) => {
  const [seriesList, setSeriesList] = useState<TemplateSeriesDTO[]>([]);
  const [seriesLoading, setSeriesLoading] = useState(false);
  const [selectedSeriesId, setSelectedSeriesId] = useState<string | undefined>(undefined);

  const [columns, setColumns] = useState<PriceAdjustColumnDef[]>([makeDefaultColumn()]);
  const [configured, setConfigured] = useState(false);
  const [columnsLoading, setColumnsLoading] = useState(false);

  const [drawerOpen, setDrawerOpen] = useState(false);
  const [meta, setMeta] = useState<ComparisonMetaDTO | null>(null);
  const [metaError, setMetaError] = useState<string | null>(null);

  const loadSeries = useCallback(async () => {
    setSeriesLoading(true);
    try {
      const list = await priceAdjustService.getTemplateSeries(customerNo);
      setSeriesList(list || []);
      if (list && list.length) {
        const def = list.find((s) => s.isDefault) || list[0];
        setSelectedSeriesId(def.templateSeriesId);
      } else {
        setSelectedSeriesId(undefined);
      }
    } catch (e: any) {
      message.error(e?.message || '加载模板系列失败');
    } finally {
      setSeriesLoading(false);
    }
  }, [customerNo]);

  useEffect(() => { loadSeries(); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [customerNo]);

  const loadColumns = useCallback(async (seriesId: string) => {
    setColumnsLoading(true);
    try {
      const res = await priceAdjustService.getComparisonColumns(customerNo, seriesId);
      setConfigured(!!res.configured);
      setColumns(res.columns && res.columns.length ? res.columns : [makeDefaultColumn()]);
    } catch (e: any) {
      message.error(e?.message || '加载比对列配置失败');
      setColumns([makeDefaultColumn()]);
    } finally {
      setColumnsLoading(false);
    }
  }, [customerNo]);

  useEffect(() => {
    if (selectedSeriesId) loadColumns(selectedSeriesId);
  }, [selectedSeriesId, loadColumns]);

  const persist = useCallback(async (next: PriceAdjustColumnDef[]) => {
    if (!selectedSeriesId) return;
    setColumns(next);
    try {
      const res = await priceAdjustService.saveComparisonColumns(customerNo, selectedSeriesId, next);
      if (res.affectedReviewCount != null) {
        message.success(`已保存，触发 ${res.affectedReviewCount} 个待处理料号的预算重算`);
      } else {
        message.success('已保存比对列配置');
      }
    } catch (e: any) {
      message.error(e?.message || '保存比对列配置失败');
    }
  }, [customerNo, selectedSeriesId]);

  const handleRemove = (id: string) => {
    const target = columns.find((c) => c.id === id);
    if (!target || !target.removable) return; // 默认列不可删（双保险）
    persist(columns.filter((c) => c.id !== id));
  };

  const handleThresholdChange = (id: string, threshold: number) => {
    persist(columns.map((c) => (c.id === id ? { ...c, threshold } : c)));
  };

  const openDrawer = async () => {
    if (!selectedSeriesId) return;
    setMetaError(null);
    try {
      const m = await priceAdjustService.getComparisonMeta(selectedSeriesId);
      setMeta(m as unknown as ComparisonMetaDTO);
      setDrawerOpen(true);
    } catch (e: any) {
      // 🔒 文案不得再暗示"功能尚未上线"：该端点已于 2026-08-06 补交（api.md §1.10a），
      // 此后再报错就是**真故障**（网络/权限/该模板系列数据异常）。说成没上线会把用户和
      // 排查方向都带反 —— 一个是等开发，一个是查环境。带上后端原因便于定位。
      // （措辞刻意避开旧文案原词，好让"改文案先 grep"的审计不出现噪音命中）
      const reason = e?.message ? `：${e.message}` : '';
      setMetaError(`加载页签/可比对值目录失败${reason}，无法打开连线配置。请重试；若持续失败，请联系管理员排查网络/权限或该模板系列的数据。`);
      message.error('加载失败，暂无法打开连线配置');
    }
  };

  const handleConfirmLink = (pairs: LinkPairInput[]) => {
    const startSortOrder = nextSortOrder(columns as any);
    const newCols = buildTabPairColumns(pairs, startSortOrder).map((c) => ({ ...c, removable: true } as PriceAdjustColumnDef));
    const next = [...columns, ...newCols];
    setDrawerOpen(false);
    persist(next);
  };

  const columnDefs: ColumnsType<PriceAdjustColumnDef> = [
    { title: '#', width: 44, render: (_: unknown, __: PriceAdjustColumnDef, i: number) => i + 1 },
    {
      title: '比对列', render: (_: unknown, r: PriceAdjustColumnDef) => (
        <span>
          <b>{r.kind === 'PRODUCT_TOTAL' ? '产品总价' : (r.quoteLabel || r.costingLabel || '—')}</b>
          {!r.removable && <Tag color="blue" style={{ fontSize: 10, marginLeft: 6 }}>默认列</Tag>}
        </span>
      ),
    },
    { title: '报价侧', dataIndex: 'quoteLabel', render: (v: string | undefined, r) => r.kind === 'PRODUCT_TOTAL' ? '产品卡片总计' : (v || '—') },
    { title: '核价侧', dataIndex: 'costingLabel', render: (v: string | undefined, r) => r.kind === 'PRODUCT_TOTAL' ? '产品卡片总计' : (v || '—') },
    {
      title: '阈值', width: 100, align: 'right' as const,
      render: (_: unknown, r: PriceAdjustColumnDef) => (
        <InputNumber size="small" min={0} precision={2} value={r.threshold} style={{ width: 80 }}
          onChange={(v) => handleThresholdChange(r.id, v ?? 0)} />
      ),
    },
    {
      title: '', width: 60,
      render: (_: unknown, r: PriceAdjustColumnDef) => r.removable
        ? <Popconfirm title="删除该比对列？" onConfirm={() => handleRemove(r.id)}><Button size="small" type="link" danger>删除</Button></Popconfirm>
        : null,
    },
  ];

  const selectedSeries = seriesList.find((s) => s.templateSeriesId === selectedSeriesId);
  const totalTemplates = seriesList.reduce((sum, s) => sum + (s.templateCount || 0), 0);

  return (
    <div style={{ marginTop: 8 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 10, flexWrap: 'wrap' }}>
        <span style={{ fontSize: 12.5, color: 'rgba(0,0,0,.45)' }}>模板系列</span>
        <Select
          style={{ width: 260 }}
          loading={seriesLoading}
          value={selectedSeriesId}
          placeholder="请选择模板系列"
          onChange={setSelectedSeriesId}
          options={seriesList.map((s) => ({
            value: s.templateSeriesId,
            label: `${s.seriesName}（最新 ${s.latestVersion}）`,
          }))}
        />
        {seriesList.length > 0 && (
          <Tag>该客户共 {seriesList.length} 个系列 / {totalTemplates} 个模板</Tag>
        )}
        <span style={{ flex: 1 }} />
        <span style={{ fontSize: 12, color: 'rgba(0,0,0,.35)' }}>切换系列 = 切换另一份配置</span>
      </div>

      {!selectedSeriesId ? (
        <Alert type="info" showIcon message="该客户暂无可用模板系列，无法配置比对列" />
      ) : (
        <div style={{ border: '1px solid #f0f0f0', borderRadius: 6 }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '10px 14px', borderBottom: '1px solid #f0f0f0' }}>
            <span style={{ fontWeight: 600, fontSize: 13.5 }}>比对列 · {selectedSeries?.seriesName || ''}</span>
            <span style={{ fontSize: 12.5, color: 'rgba(0,0,0,.45)' }}>
              已配 {columns.length} 列 · 复用报价单比对视图的连线配置 ·{' '}
              <a onClick={openDrawer}>⚙ 配置比对列</a>
            </span>
          </div>
          {metaError && <Alert type="warning" showIcon closable message={metaError} style={{ margin: '10px 14px 0' }} onClose={() => setMetaError(null)} />}
          <Table<PriceAdjustColumnDef>
            size="small"
            rowKey="id"
            loading={columnsLoading}
            dataSource={columns}
            columns={columnDefs}
            pagination={false}
          />
          <div style={{ padding: '10px 14px', fontSize: 12, color: 'rgba(0,0,0,.45)', borderTop: '1px solid #f5f5f5' }}>
            配置按「客户 × 模板系列」各一份，改一次对该系列下全部料号的审核生效；{configured ? '' : '该系列尚未配置过，当前展示默认列。'}
            未配过的组合默认只有「产品总价」一列（不依赖 componentId，跨模板天然通用）。
            保存后立即重算「该客户 × 该系列」下「待处理」料号的预算，其他系列的料号不受影响；已通过/已驳回的料号不重算。
          </div>
        </div>
      )}

      <LinkConfigDrawer
        open={drawerOpen}
        meta={meta}
        onClose={() => setDrawerOpen(false)}
        onConfirm={handleConfirmLink}
      />
    </div>
  );
};

export default ComparisonColumnPanel;
