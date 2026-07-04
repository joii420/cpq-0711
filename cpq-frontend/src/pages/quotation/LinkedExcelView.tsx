import React from 'react';
import { Table, Card, Spin, Alert, Tag } from 'antd';
import type { CostingTemplateColumn } from '../../services/costingTemplateService';
import type { LineItem } from './QuotationStep2';
import { useLinkedExcelRows } from './useLinkedExcelRows';
import { useExcelSnapshotRows } from './useExcelSnapshotRows';
import { useBackendExcelRows } from './useBackendExcelRows';
import { buildExcelSnapshot } from './buildExcelSnapshot';
import { renderCellValue } from './excelCellFormat';
import type { DriverExpansionMap } from './useDriverExpansions';
import type { PathCache } from './usePathFormulaCache';
import type { GlobalVariableDefinition } from '../../utils/formulaEngine';

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
  /**
   * Phase 2：报价侧 Excel 视图改走前端 buildExcelSnapshot 即时求值，与卡片共用同一引擎。
   * 由 QuotationStep2.tsx 报价侧 LinkedExcelView 调用处传入（side=QUOTE 时生效）。
   */
  driverExpansions?: DriverExpansionMap;
  /** BNF 路径求值缓存（来自 usePathFormulaCache，即 quotationPathCache），key=`${partNo}::${path}` */
  pathCache?: PathCache;
  /** 全局变量定义字典（code → def），供动态 key GV 路径重写 */
  globalVariableDefs?: Record<string, GlobalVariableDefinition>;
}

/**
 * 判断是否为新模型：模板列配置中存在 source_type==='CARD_FORMULA' 的列。
 * 新模型下使用 useBackendExcelRows（后端已算好所有列值）；旧模型继续用 useLinkedExcelRows。
 */
function isNewModel(parsedColumns: CostingTemplateColumn[]): boolean {
  return parsedColumns.some((col) => col.source_type === 'CARD_FORMULA');
}

/** A2: 稳定空数组引用 —— QUOTE 侧 useLinkedExcelRows 不消费 legacy rows 时传入，断开其 batch-evaluate。 */
const EMPTY_LINE_ITEMS: LineItem[] = [];

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
  driverExpansions,
  pathCache,
  globalVariableDefs,
}) => {
  // ---- 旧模型 hook（始终调用，用 enabled 控制是否真正运行）----
  // useLinkedExcelRows 内部用 linkedTemplateId 有无控制；直接传完整参数，
  // 新模型下它拿到 excelTemplate 后 parsedColumns 会有值但 rows 不被 LinkedExcelView 消费。
  //
  // A2: QUOTE 侧渲染恒走 frontendRows(buildExcelSnapshot)/v2/快照，**从不消费 legacyResult.rows**
  // (见下方 rows 选择：side!=COSTING && frontendRows!=null 时用 frontendRows)，故 QUOTE 侧传空 lineItems
  // 断开 useLinkedExcelRows 的 batch-evaluate(纯浪费)。parsedColumns/configShape/excelTemplate 由
  // linkedTemplateId 的 config effect 决定，与 lineItems 无关 → 不受影响。
  // COSTING 侧 legacy 非新模型仍消费 legacyResult.rows → 保持原 lineItems(不 gate)。
  const legacyLineItems = side === 'COSTING' ? lineItems : EMPTY_LINE_ITEMS;
  const legacyResult = useLinkedExcelRows({
    linkedTemplateId,
    lineItems: legacyLineItems,
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
  // v2「引用 EXCEL 组件」配置：客户端无法解析(需后端 getEffectiveColumns)，且列多为 TAB_JOIN/CARD_FORMULA(须后端求值)。
  const isV2 = legacyResult.configShape === 'v2';
  const useSnapshot = isNewModel(legacyColumns);

  const snapshotResult = useExcelSnapshotRows({
    lineItems,
    side: side ?? 'QUOTE',
    parsedColumns: legacyColumns,
  });

  // v2 走后端 getExcelView：返回已解析列 + 已算值。
  // 报价侧只取其「已解析列定义」(列结构非分叉源)，行值改由前端 buildExcelSnapshot 算(恒等卡片)；
  // 核价侧仍取其已算值(后续计划再统一)。enabled=isV2 拉一次解析列。
  const backendResult = useBackendExcelRows({
    quotationId,
    lineItems,
    enabled: isV2,
    templateId: templateId ?? linkedTemplateId ?? null,
  });

  // 报价侧 Excel 列定义来源：v2 取后端解析列(getEffectiveColumns)，legacy 取客户端解析列。
  const reportColumns = isV2 ? backendResult.parsedColumns : legacyColumns;

  // ---- Phase 2/2.5：报价侧(side=QUOTE) Excel 用前端 buildExcelSnapshot 即时算值 ----
  // 列定义来自后端解析(reportColumns)，值与卡片同引擎(恒等卡片)；核价侧(COSTING)不动，走原 v2/snapshot/legacy。
  // __key 仅作 React key，不参与 driverExpansionKey 计算；报价行恒有产品结构，__noData 恒 false。
  const frontendRows = React.useMemo(() => {
    if (side === 'COSTING' || !reportColumns?.length) return null;
    return lineItems.flatMap((li, i) => {
      const snap = buildExcelSnapshot(
        li,
        reportColumns,
        driverExpansions,
        customerId,
        { pathCache: pathCache as Record<string, any> | undefined, globalVariableDefs },
      );
      return snap.rows.map((r, ri) => ({
        __key: `fe-${(li as any).id ?? (li as any).tempId ?? i}-${ri}`,
        __label: r.__hfPartNo ?? `产品 ${i + 1}`,
        __hfPartNo: r.__hfPartNo,
        __noData: false,
        ...r,
      }));
    });
  }, [side, lineItems, reportColumns, driverExpansions, customerId, pathCache, globalVariableDefs]);

  // ---- 按模型选用最终结果：报价侧前端算值优先(列后端解析+值前端)；核价侧 v2(后端) > 快照 > 老内联 ----
  const {
    rows,
    parsedColumns,
    loading: resolvedLoading,
    error: resolvedError,
  } = (side !== 'COSTING' && frontendRows != null)
    ? {
        rows: frontendRows,
        parsedColumns: reportColumns,
        loading: legacyResult.loading || (isV2 && backendResult.loading),
        error: legacyResult.error || (isV2 ? backendResult.error : null),
      }
    : isV2
    ? {
        rows: backendResult.rows,
        parsedColumns: backendResult.parsedColumns,
        loading: backendResult.loading,
        error: backendResult.error,
      }
    : useSnapshot
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
  // 标题栏"后端算值"标记：v2 与新模型快照都走后端。
  const backendComputed = isV2 || useSnapshot;

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
  // v2 引用配置无 excelTemplate（列由后端解析），不走"未找到关联"分支。
  if (!excelTemplate && !isV2) {
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
        message={isV2 ? 'Excel 视图暂无可显示的列' : '关联的 Excel 模板未配置任何列'}
        description={isV2
          ? '请确认模板已「引用 EXCEL 组件」并配置列，且本报价单已添加产品行后重试。'
          : `Excel 模板「${excelTemplate?.name}」没有列定义。请前往「Excel 模板配置」打开它，添加列后重试。`}
        style={{ margin: 16 }} />
    );
  }

  const visibleColumns = parsedColumns.filter((col: CostingTemplateColumn) => !col.hidden);
  // P2-B 核价 Excel 树：核价侧料号列按 __lvl 缩进 + 前置「版本」列；报价侧保持原样（隔离）。
  // 按约定「父料号」不单独成列(仅用于建层级),2026-07-03 隐藏——与产品卡片树页签一致。
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
            {viewLabel || 'Excel 视图'}
            {/* v2 引用配置无 excelTemplate 元信息（列由后端解析），仅 legacy/snapshot 模型显示模板 Tag。 */}
            {excelTemplate && (
              <>
                {' · '}<Tag color="blue">{excelTemplate.name}</Tag>
                <Tag>{excelTemplate.version}</Tag>
                {excelTemplate.isDefault && <Tag color="gold">默认</Tag>}
                <Tag color={excelTemplate.status === 'PUBLISHED' ? 'green' : 'default'}>
                  {excelTemplate.status === 'PUBLISHED' ? '已发布' : excelTemplate.status}
                </Tag>
              </>
            )}
            {side !== 'COSTING' && frontendRows != null
              ? <Tag color="green" style={{ marginLeft: 6 }}>前端算值</Tag>
              : backendComputed && <Tag color="cyan" style={{ marginLeft: 6 }}>后端算值</Tag>
            }
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
