import React from 'react';
import { Table, Card, Spin, Alert, Tag } from 'antd';
import type { CostingTemplateColumn } from '../../services/costingTemplateService';
import type { LineItem } from './QuotationStep2';
import { useLinkedExcelRows } from './useLinkedExcelRows';
import { useExcelSnapshotRows } from './useExcelSnapshotRows';

interface Props {
  linkedTemplateId?: string;
  lineItems: LineItem[];
  quotationContext?: Record<string, any>;
  viewLabel?: string;
  customerId?: string;
  quotationId?: string | null;
  quotationStatus?: string | null;
  templateId?: string | null;
  /** @deprecated 旧字段，已由 templateId 替代。 */
  costingTemplateId?: string | null;
  /** 本视图侧：QUOTE=报价 Excel、COSTING=核价 Excel；新模型下据此读对应 Excel 值快照 */
  side?: 'QUOTE' | 'COSTING';
}

/**
 * 判断是否为新模型：模板列配置中存在 source_type==='CARD_FORMULA' 的列。
 * 新模型下使用 useBackendExcelRows（后端已算好所有列值）；旧模型继续用 useLinkedExcelRows。
 */
function isNewModel(parsedColumns: CostingTemplateColumn[]): boolean {
  return parsedColumns.some((col) => col.source_type === 'CARD_FORMULA');
}

/**
 * 格式化单元格值：
 * - null/undefined/''/''—'' → 显示 "—"
 * - PERCENT 格式：数值 × 100，按 decimals 保留小数，加 % 后缀
 * - 其余数值原样显示（toLocaleString）
 */
function renderCellValue(val: any, col: CostingTemplateColumn): React.ReactNode {
  if (val === null || val === undefined || val === '' || val === '—') {
    return <span style={{ color: '#bbb' }}>—</span>;
  }

  const fmt = col.display_format;
  if (fmt?.type === 'PERCENT') {
    const n = typeof val === 'number' ? val : parseFloat(String(val));
    if (isNaN(n)) return <span style={{ color: '#bbb' }}>—</span>;
    const decimals = fmt.decimals ?? 2;
    return `${(n * 100).toFixed(decimals)}%`;
  }

  if (typeof val === 'number') return val.toLocaleString();
  return String(val);
}

const LinkedExcelView: React.FC<Props> = ({
  linkedTemplateId,
  lineItems,
  quotationContext,
  viewLabel,
  customerId,
  quotationId,
  quotationStatus,
  templateId,
  costingTemplateId: _costingTemplateId, // @deprecated 已由 templateId 替代, 接收但不使用
  side,
}) => {
  // ---- 旧模型 hook（始终调用，用 enabled 控制是否真正运行）----
  // useLinkedExcelRows 内部用 linkedTemplateId 有无控制；直接传完整参数，
  // 新模型下它拿到 excelTemplate 后 parsedColumns 会有值但 rows 不被 LinkedExcelView 消费。
  const legacyResult = useLinkedExcelRows({
    linkedTemplateId,
    lineItems,
    customerId,
    templateId,
    quotationContext,
    quotationId,
    quotationStatus,
  });

  // ---- 新模型 hook：先用旧 hook 拿到 parsedColumns 判断模型，再决定 enabled ----
  // 注：两个 hook 无条件调用（React rules of hooks）。
  // 旧 hook 已加载好 parsedColumns → 用它判断模型；
  // 新 hook 的 enabled 由判断结果控制。
  const legacyColumns = legacyResult.parsedColumns;
  const useBackend = isNewModel(legacyColumns);

  const snapshotResult = useExcelSnapshotRows({
    lineItems,
    side: side ?? 'QUOTE',
    parsedColumns: legacyColumns,
  });

  // ---- 按模型选用最终结果 ----
  const {
    rows,
    parsedColumns,
    loading: resolvedLoading,
    error: resolvedError,
  } = useBackend
    ? {
        rows: snapshotResult.rows,
        parsedColumns: legacyColumns, // 列配置结构仍用旧 hook 已解析的（含 display_format 等）
        loading: legacyResult.loading,
        error: legacyResult.error,
      }
    : {
        rows: legacyResult.rows,
        parsedColumns: legacyColumns,
        loading: legacyResult.loading,
        error: legacyResult.error,
      };

  const excelTemplate = legacyResult.excelTemplate;

  // ---- 渲染 ----
  if (!linkedTemplateId) {
    return (
      <Alert type="info" showIcon
        message={`本报价单未绑定${viewLabel || ''}模板`}
        description="无法定位关联 Excel 模板。请先确认报价单已选择模板。"
        style={{ margin: 16 }} />
    );
  }
  if (resolvedLoading) return <div style={{ textAlign: 'center', padding: 48 }}><Spin /></div>;
  if (resolvedError) return <Alert type="error" showIcon message={resolvedError} style={{ margin: 16 }} />;
  if (!excelTemplate) {
    return (
      <Alert type="warning" showIcon
        message="未找到关联的 Excel 模板"
        description="请前往「Excel 模板配置」菜单：在某个 Excel 模板上把「关联模板」设为本报价单使用的模板，并发布；同一关联模板内可设一份默认。"
        style={{ margin: 16 }} />
    );
  }
  if (parsedColumns.length === 0) {
    return (
      <Alert type="warning" showIcon
        message="关联的 Excel 模板未配置任何列"
        description={`Excel 模板「${excelTemplate.name}」没有列定义。请前往「Excel 模板配置」打开它，添加列后重试。`}
        style={{ margin: 16 }} />
    );
  }

  const visibleColumns = parsedColumns.filter((col: CostingTemplateColumn) => !col.hidden);
  // P2-B 核价 Excel 树：核价侧料号列按 __lvl 缩进 + 前置 父料号/版本 两列；报价侧保持原样（隔离）
  const isCosting = side === 'COSTING';
  const tableColumns = [
    {
      title: '料号', dataIndex: '__hfPartNo', key: '__hfPartNo',
      fixed: 'left' as const, width: 220,
      render: (v: string, rec: any) => (
        <span style={{ fontFamily: 'monospace', paddingLeft: isCosting ? ((rec.__lvl ?? 1) - 1) * 16 : 0 }}>{v}</span>
      ),
    },
    ...(isCosting ? [
      {
        title: '父料号', dataIndex: '__parentNo', key: '__parentNo', width: 140,
        render: (v: string) => <span style={{ fontFamily: 'monospace', color: '#888' }}>{v ?? '—'}</span>,
      },
      {
        title: '版本', dataIndex: '__bomVersion', key: '__bomVersion', width: 90,
        render: (v: string) => <span>{v ?? '—'}</span>,
      },
    ] : []),
    ...visibleColumns.map((col) => ({
      title: (
        <span>
          <span style={{ color: '#999', marginRight: 4 }}>[{col.col_key}]</span>
          {col.title}
        </span>
      ),
      dataIndex: col.col_key, key: col.col_key, width: 140,
      render: (val: any) => {
        // 旧模型的 '__loading__' 哨兵
        if (val === '__loading__') return <span style={{ color: '#bbb' }}>加载中…</span>;
        return renderCellValue(val, col);
      },
    })),
  ];

  return (
    <div>
      <Card size="small" style={{ marginBottom: 12 }}
        title={
          <span>
            {viewLabel || 'Excel 视图'} · <Tag color="blue">{excelTemplate.name}</Tag>
            <Tag>{excelTemplate.version}</Tag>
            {excelTemplate.isDefault && <Tag color="gold">默认</Tag>}
            <Tag color={excelTemplate.status === 'PUBLISHED' ? 'green' : 'default'}>
              {excelTemplate.status === 'PUBLISHED' ? '已发布' : excelTemplate.status}
            </Tag>
            {useBackend && <Tag color="cyan">后端算值</Tag>}
          </span>
        }
        extra={<span style={{ fontSize: 12, color: '#999' }}>按本报价单产品行渲染，共 {rows.length} 行</span>}
      />
      <Table rowKey="__key" size="small" columns={tableColumns}
        dataSource={rows} pagination={false} scroll={{ x: 'max-content' }} />
    </div>
  );
};

export default LinkedExcelView;
