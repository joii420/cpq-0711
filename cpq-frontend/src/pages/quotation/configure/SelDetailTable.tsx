/**
 * SelDetailTable — 选配添加·明细表主体（task-0712 F5，D11）。
 *
 * 1:1 对齐 `原型-报价单-选配添加.html` 的 `.detail-table`：
 * 顶部【+ 新增材质料号】+ 表格(#/材质/元素含量/工序/数量/操作) + 底部"数量合计: N"。
 * 一行 = 一个材质料号（`SelDetailRow`），数量行内可编辑（默认 1）。
 */
import React from 'react';
import { Table, Button, InputNumber, Empty, Tag } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import type { SelDetailRow } from '../../../types/configure';
import { formatPctText, normalizeDecimalString, type DecimalString } from '../../../utils/precision';
import { normalizeQuantityInput, sumQuantity } from './configureRequest';

interface Props {
  rows: SelDetailRow[];
  onAdd: () => void;
  onEdit: (rowId: string) => void;
  onDelete: (rowId: string) => void;
  onQuantityChange: (rowId: string, qty: DecimalString) => void;
}

// 材质色块 — 后端无配色数据，按 code 做确定性哈希取色，纯展示用（对齐原型 `.mo-swatch`/`.mat-swatch-sm` 视觉语义，非逐值还原）。
const SWATCH_COLORS = [
  '#b9c4d1', '#a9b6c8', '#e0c68a', '#e6cf94', '#d5dbe3',
  '#c7ced9', '#9aa5b1', '#f0b7b7', '#b7e0c9', '#c9b7e0',
];
function swatchColor(code: string | null): string {
  if (!code) return '#e4e7ed';
  let h = 0;
  for (let i = 0; i < code.length; i++) h = (h * 31 + code.charCodeAt(i)) >>> 0;
  return SWATCH_COLORS[h % SWATCH_COLORS.length];
}

// task-260901 · AC-30：给人看的含量一律**去掉小数点后多余的 0**（字符串处理，不过 JS number）
function summarizeElements(overrides: Record<string, DecimalString>): string {
  const entries = Object.entries(overrides);
  if (entries.length === 0) return '—';
  return entries.map(([code, val]) => `${code} ${formatPctText(val)}%`).join(' / ');
}

const SelDetailTable: React.FC<Props> = ({ rows, onAdd, onEdit, onDelete, onQuantityChange }) => {
  const qtySum = normalizeDecimalString(sumQuantity(rows));

  const columns: ColumnsType<SelDetailRow> = [
    {
      title: '#',
      key: 'seq',
      width: 36,
      render: (_v, _r, idx) => idx + 1,
    },
    {
      title: '材质',
      key: 'material',
      render: (_v, r) => (
        <span>
          <span
            style={{
              display: 'inline-block',
              width: 14,
              height: 14,
              borderRadius: 3,
              marginRight: 6,
              verticalAlign: -2,
              background: swatchColor(r.recipeCode),
            }}
          />
          {r.recipeLabel || r.recipeCode || '—'}
          {r.recipeCode && (
            <span style={{ color: '#909399', fontSize: 11, marginLeft: 4 }}>{r.recipeCode}</span>
          )}
        </span>
      ),
    },
    {
      // task-260901 · F-8/F-9：含量来源二选一 —— 标准配置（configNo）或自定义含量（elementOverrides）
      title: '含量',
      key: 'content',
      render: (_v, r) => {
        const mode = r.contentMode ?? (Object.keys(r.elementOverrides).length > 0 ? 'custom' : undefined);
        if (mode === 'config') {
          return (
            <span>
              <Tag color="blue">标准配置</Tag>
              <span style={{ fontFamily: 'Consolas, monospace' }}>{r.configNo ?? '—'}</span>
              {r.configLabel && (
                <span style={{ color: '#909399', fontSize: 11, marginLeft: 6 }}>
                  {r.configLabel.replace(/^[^（(]*/, '')}
                </span>
              )}
            </span>
          );
        }
        if (mode === 'custom') {
          return (
            <span>
              <Tag color="gold">自定义</Tag>
              {summarizeElements(r.elementOverrides)}
            </span>
          );
        }
        return '—';
      },
    },
    {
      title: '工序',
      key: 'process',
      render: (_v, r) => (r.processLabels.length > 0 ? r.processLabels.join('·') : '—'),
    },
    {
      title: '数量',
      key: 'quantity',
      width: 90,
      render: (_v, r) => (
        <InputNumber<string>
          size="small"
          stringMode
          min="1"
          step="1"
          precision={0}
          value={r.quantity}
          style={{ width: 68 }}
          onChange={(v) => onQuantityChange(r.rowId, normalizeQuantityInput(v))}
        />
      ),
    },
    {
      title: '操作',
      key: 'action',
      width: 96,
      render: (_v, r) => (
        <span>
          <a style={{ marginRight: 12, fontSize: 12.5 }} onClick={() => onEdit(r.rowId)}>
            编辑
          </a>
          <a style={{ color: '#f56c6c', fontSize: 12.5 }} onClick={() => onDelete(r.rowId)}>
            删除
          </a>
        </span>
      ),
    },
  ];

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
        <Button type="primary" icon={<PlusOutlined />} onClick={onAdd}>
          新增材质料号
        </Button>
        <div style={{ fontSize: 13, color: '#606266' }}>
          数量合计：<b style={{ color: '#1890ff', fontSize: 16, marginLeft: 2 }}>{qtySum}</b>
        </div>
      </div>

      {rows.length === 0 ? (
        <div
          style={{
            textAlign: 'center',
            padding: '34px 0',
            color: '#c0c4cc',
            fontSize: 12.5,
            border: '1px dashed #e4e7ed',
            borderRadius: 6,
          }}
        >
          点击「+ 新增材质料号」开始选配
        </div>
      ) : (
        <Table<SelDetailRow>
          rowKey="rowId"
          size="small"
          columns={columns}
          dataSource={rows}
          pagination={false}
          bordered
          locale={{ emptyText: <Empty description="暂无选配材质" /> }}
        />
      )}
    </div>
  );
};

export default SelDetailTable;
