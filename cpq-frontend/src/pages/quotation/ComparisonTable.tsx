/**
 * ComparisonTable —— 比对视图主表（task-0717，纯展示 + 列头交互，零取数）。
 * 每销售料号 3 行块：报价(数据) / 核价(数据) / 差异，料号列 rowSpan=3 合并。
 * 对齐 dev-docs/task-0717-比对视图/prototype-比对视图.html「比对主表」+ fronttask.md §3。
 */
import React, { useMemo, useState } from 'react';
import { Table, Tag, Popover, InputNumber, Button } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { SettingOutlined, CloseOutlined, LockOutlined } from '@ant-design/icons';
import {
  columnDecimals,
  computeDiff,
  classifyDiff,
  formatComparisonNumber,
  formatDiffNumber,
  getColumnValue,
} from './comparisonMapping';
import type { ColumnDef } from './comparisonMapping';
import type { ComparisonRowDTO } from '../../services/comparisonViewService';

const RED_BG = '#ff4d4f';
const ORANGE_BG = '#fa8c16';
const MUTED_BG = '#fafafa';
const MUTED_FG = '#bbb';
const DIFF_ROW_BG = '#fbfbfb';

type RowType = 'quote' | 'costing' | 'diff';
const ROW_LABEL: Record<RowType, string> = { quote: '报价', costing: '核价', diff: '差异' };

interface RecordRow {
  key: string;
  row: ComparisonRowDTO;
  rowType: RowType;
  /** rowSpan：料号列合并用，块内首行=3，其余=0（antd 约定 0 = 不渲染该单元格）。 */
  span: number;
}

/** 列头一格：默认列显示锁图标 + "默认" 徽标；用户列显示两行「报价：.../核价：...」+ ✕。两者都带 ⚙ 阈值气泡（readonly 时隐藏）。 */
const ColumnHeaderCell: React.FC<{
  col: ColumnDef;
  readonly: boolean;
  onRemove: (id: string) => void;
  onUpdateThreshold: (id: string, threshold: number) => void;
}> = ({ col, readonly, onRemove, onUpdateThreshold }) => {
  const [open, setOpen] = useState(false);
  const [draft, setDraft] = useState<number | null>(col.threshold);
  const isDefault = col.kind === 'PRODUCT_TOTAL';

  const handleOpenChange = (v: boolean) => {
    setOpen(v);
    if (v) setDraft(col.threshold);
  };
  const handleConfirm = () => {
    if (draft != null && !Number.isNaN(draft)) onUpdateThreshold(col.id, draft);
    setOpen(false);
  };

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'flex-start', gap: 6 }}>
        <span style={{ flex: 1, whiteSpace: 'normal', lineHeight: 1.6, fontSize: 12.5, fontWeight: 500, minWidth: 150 }}>
          {isDefault ? (
            <>
              <LockOutlined style={{ marginRight: 4 }} />
              产品卡片总计
              <span
                style={{
                  display: 'inline-block', background: '#f0f0f0', color: '#666', fontSize: 10,
                  padding: '0 5px', borderRadius: 3, marginLeft: 4, fontWeight: 400,
                }}
              >
                默认
              </span>
            </>
          ) : (
            <>
              报价：{col.quoteLabel}
              <br />
              核价：{col.costingLabel}
            </>
          )}
        </span>
        <span style={{ display: 'flex', alignItems: 'center', gap: 6, flexShrink: 0 }}>
          {!readonly && (
            <Popover
              open={open}
              onOpenChange={handleOpenChange}
              trigger="click"
              placement="bottom"
              content={
                <div style={{ width: 180 }}>
                  <div style={{ fontSize: 12, color: 'rgba(0,0,0,.65)', marginBottom: 6 }}>设置差异阈值</div>
                  <InputNumber
                    autoFocus
                    size="small"
                    style={{ width: '100%', marginBottom: 8 }}
                    value={draft}
                    onChange={setDraft}
                    onPressEnter={handleConfirm}
                  />
                  <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 6 }}>
                    <Button size="small" onClick={() => setOpen(false)}>取消</Button>
                    <Button size="small" type="primary" onClick={handleConfirm}>确定</Button>
                  </div>
                </div>
              }
            >
              <SettingOutlined
                style={{ cursor: 'pointer', color: 'rgba(0,0,0,.35)', fontSize: 13 }}
                title="设置差异阈值"
                onClick={(e) => e.stopPropagation()}
              />
            </Popover>
          )}
          {!readonly && !isDefault && (
            <CloseOutlined
              style={{ cursor: 'pointer', color: 'rgba(0,0,0,.35)', fontSize: 13 }}
              title="删除该列"
              onClick={(e) => {
                e.stopPropagation();
                onRemove(col.id);
              }}
            />
          )}
        </span>
      </div>
      <div style={{ marginTop: 6, fontSize: 11, color: '#8c8c8c', fontWeight: 400 }}>阈值 {col.threshold}</div>
    </div>
  );
};

export interface ComparisonTableProps {
  columns: ColumnDef[];
  /** 已过滤/排序/分页后的当前页料号（每料号渲染 3 行）。 */
  rows: ComparisonRowDTO[];
  readonly: boolean;
  onRemoveColumn: (id: string) => void;
  onUpdateThreshold: (id: string, threshold: number) => void;
}

export const ComparisonTable: React.FC<ComparisonTableProps> = ({
  columns, rows, readonly, onRemoveColumn, onUpdateThreshold,
}) => {
  const dataSource = useMemo<RecordRow[]>(() => {
    const out: RecordRow[] = [];
    for (const row of rows) {
      out.push({ key: `${row.partNo}__quote`, row, rowType: 'quote', span: 3 });
      out.push({ key: `${row.partNo}__costing`, row, rowType: 'costing', span: 0 });
      out.push({ key: `${row.partNo}__diff`, row, rowType: 'diff', span: 0 });
    }
    return out;
  }, [rows]);

  const tableColumns = useMemo(() => {
    const base: ColumnsType<RecordRow> = [
      {
        title: '销售料号',
        key: 'partNo',
        fixed: 'left',
        width: 180,
        onCell: (rec: RecordRow) => ({
          rowSpan: rec.span,
          style: rec.rowType === 'quote' ? { borderTop: '2px solid #e4e4e4' } : undefined,
        }),
        render: (_: unknown, rec: RecordRow) => (
          <span style={{ fontFamily: 'SFMono-Regular,Consolas,"Liberation Mono",monospace', fontWeight: 500 }}>
            {rec.row.partNo}
            {rec.row.presence !== 'BOTH' && (
              <Tag
                style={{
                  marginLeft: 6, fontSize: 11, lineHeight: '18px', padding: '0 6px',
                  background: '#fff7e6', color: '#d46b08', border: '1px solid #ffd591',
                }}
              >
                {rec.row.presence === 'QUOTE_ONLY' ? '仅报价' : '仅核价'}
              </Tag>
            )}
          </span>
        ),
      },
      {
        title: '口径',
        key: 'side',
        fixed: 'left',
        width: 76,
        onCell: (rec: RecordRow) => ({
          style: {
            ...(rec.rowType === 'quote' ? { borderTop: '2px solid #e4e4e4' } : {}),
            ...(rec.rowType === 'diff' ? { background: DIFF_ROW_BG, fontWeight: 600, color: 'rgba(0,0,0,.88)' } : { color: 'rgba(0,0,0,.65)' }),
          },
        }),
        render: (_: unknown, rec: RecordRow) => ROW_LABEL[rec.rowType],
      },
    ];

    for (const col of columns) {
      const decimals = columnDecimals(col);
      base.push({
        title: (
          <ColumnHeaderCell col={col} readonly={readonly} onRemove={onRemoveColumn} onUpdateThreshold={onUpdateThreshold} />
        ),
        key: col.id,
        width: 220,
        onCell: (rec: RecordRow) => {
          const style: React.CSSProperties = {};
          if (rec.rowType === 'quote') {
            if (rec.row.presence === 'COSTING_ONLY') { style.background = MUTED_BG; style.color = MUTED_FG; }
            style.borderTop = '2px solid #e4e4e4';
          } else if (rec.rowType === 'costing') {
            if (rec.row.presence === 'QUOTE_ONLY') { style.background = MUTED_BG; style.color = MUTED_FG; }
          } else {
            style.background = DIFF_ROW_BG;
            if (rec.row.presence === 'BOTH') {
              const diff = computeDiff(getColumnValue(rec.row, col, 'quote'), getColumnValue(rec.row, col, 'costing'));
              const color = classifyDiff(diff, col.threshold);
              if (color === 'red') { style.background = RED_BG; style.color = '#fff'; style.fontWeight = 600; }
              else if (color === 'orange') { style.background = ORANGE_BG; style.color = '#fff'; style.fontWeight = 600; }
            }
          }
          return { style };
        },
        render: (_: unknown, rec: RecordRow) => {
          if (rec.rowType === 'diff') {
            if (rec.row.presence !== 'BOTH') return <span style={{ color: MUTED_FG }}>—</span>;
            const diff = computeDiff(getColumnValue(rec.row, col, 'quote'), getColumnValue(rec.row, col, 'costing'));
            return formatDiffNumber(diff, decimals);
          }
          const v = getColumnValue(rec.row, col, rec.rowType);
          const text = formatComparisonNumber(v, decimals);
          return text === '—' ? <span style={{ color: MUTED_FG }}>—</span> : text;
        },
      });
    }
    return base;
  }, [columns, readonly, onRemoveColumn, onUpdateThreshold]);

  return (
    <div style={{ margin: '0 24px', border: '1px solid #f0f0f0', borderRadius: 6, overflowX: 'auto' }}>
      <Table
        rowKey="key"
        size="small"
        bordered
        columns={tableColumns}
        dataSource={dataSource}
        pagination={false}
        scroll={{ x: 'max-content' }}
        locale={{ emptyText: '暂无匹配的销售料号' }}
      />
    </div>
  );
};

export default ComparisonTable;
