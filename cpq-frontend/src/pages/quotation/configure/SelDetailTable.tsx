/**
 * SelDetailTable — 选配添加·明细表主体（task-0712 F5，D11）。
 *
 * 1:1 对齐 `原型-报价单-选配添加.html` 的 `.detail-table`：
 * 顶部【+ 新增材质料号】+ 表格(#/材质/元素含量/工序/数量/操作) + 底部"数量合计: N"。
 * 一行 = 一个材质料号（`SelDetailRow`），数量行内可编辑（默认 1）。
 */
import React from 'react';
import { Table, Button, InputNumber, Empty } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import type { SelDetailRow } from '../../../types/configure';

interface Props {
  rows: SelDetailRow[];
  onAdd: () => void;
  onEdit: (rowId: string) => void;
  onDelete: (rowId: string) => void;
  onQuantityChange: (rowId: string, qty: number) => void;
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

function summarizeElements(overrides: Record<string, number>): string {
  const entries = Object.entries(overrides);
  if (entries.length === 0) return '—';
  return entries.map(([code, val]) => `${code}${Number(val).toFixed(2)}`).join('/');
}

const SelDetailTable: React.FC<Props> = ({ rows, onAdd, onEdit, onDelete, onQuantityChange }) => {
  const qtySum = rows.reduce((s, r) => s + (r.quantity || 0), 0);

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
      title: '元素含量',
      key: 'elements',
      render: (_v, r) => summarizeElements(r.elementOverrides),
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
        <InputNumber
          size="small"
          min={1}
          step={1}
          precision={0}
          value={r.quantity}
          style={{ width: 68 }}
          onChange={(v) => onQuantityChange(r.rowId, Math.max(1, Math.floor(Number(v) || 1)))}
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
