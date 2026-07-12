// ─────────────────────────────────────────────────────────────────────────────
// EditableSheetTable —— 元数据驱动的通用可编辑表格（F4，核心）
//
// 按 ColumnDef.role / dropdown.kind 渲染每列：
//   - AXIS：不渲染（轴由抽屉上下文锁定，前端不可改）
//   - NAME：只读文本（主表关联带出，随编码列联动刷新）
//   - SUBDIM / VALUE（editable）：按 dropdown.kind 渲染编辑控件
//       · MASTER → 远程搜索 Select（选中带出名称回填 nameColumn）
//       · ENUM   → AutoComplete（固定候选 + 未知可输入）
//       · FREE   → Input
//       · 无 dropdown 按 type：DECIMAL/NUMBER→InputNumber(stringMode)，BOOLEAN→Select，其余 Input
//   - editable=false：全列只读文本
//
// 行由稳定内部键 __rid 标识（父组件通过 withRowIds/newBlankRow 赋值），
// 保证增删行时受控输入不错位/不假死。
// ─────────────────────────────────────────────────────────────────────────────
import React, { useRef, useState } from 'react';
import { Select, AutoComplete, InputNumber, Input, Button, Spin, Space } from 'antd';
import { PlusOutlined, DeleteOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { Table } from 'antd';
import type { ColumnDef, MasterType, SheetRow } from './types';
import { lookup } from './api';

// 单调递增内部行键
let RID = 1;
export const newRid = (): string => `r${RID++}`;

/** 给一组行补 __rid（读取后端 rows 后调用一次） */
export function withRowIds(rows: SheetRow[]): SheetRow[] {
  return (rows ?? []).map((r) => ({ ...r, __rid: r.__rid ?? newRid() }));
}

/** 按可编辑列生成一行空行（含 __rid） */
export function newBlankRow(columns: ColumnDef[]): SheetRow {
  const blank: SheetRow = { __rid: newRid() };
  columns.filter((c) => c.role !== 'AXIS').forEach((c) => {
    blank[c.name] = undefined;
  });
  return blank;
}

function ridOf(r: SheetRow): string {
  return String(r.__rid);
}

function displayText(v: unknown): string {
  if (v === null || v === undefined || v === '') return '—';
  if (typeof v === 'boolean') return v ? '是' : '否';
  return String(v);
}

// ── MASTER 远程搜索下拉（工序/元素/来料料号）─────────────────────────────────
interface MasterOption {
  value: string;
  label: string;
  name: string;
}

function MasterSelectCell(props: {
  value: unknown;
  currentLabel?: string;
  master: MasterType;
  onPick: (code: string, name: string) => void;
}) {
  const { value, currentLabel, master, onPick } = props;
  const [options, setOptions] = useState<MasterOption[]>([]);
  const [fetching, setFetching] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const doSearch = (kw: string) => {
    if (timer.current) clearTimeout(timer.current);
    timer.current = setTimeout(async () => {
      setFetching(true);
      try {
        const r = await lookup(master, kw);
        setOptions(
          (r.items ?? []).map((i) => ({
            value: i.code,
            label: `${i.code} · ${i.name}`,
            name: i.name,
          })),
        );
      } catch {
        setOptions([]);
      } finally {
        setFetching(false);
      }
    }, 300);
  };

  const hasVal = value !== null && value !== undefined && value !== '';

  return (
    <Select
      showSearch
      labelInValue
      filterOption={false}
      allowClear
      size="small"
      style={{ minWidth: 170, width: '100%' }}
      placeholder="搜索选择"
      value={hasVal ? { value: String(value), label: currentLabel || String(value) } : undefined}
      notFoundContent={fetching ? <Spin size="small" /> : null}
      onFocus={() => { if (options.length === 0) doSearch(''); }}
      onSearch={doSearch}
      onClear={() => onPick('', '')}
      onChange={(opt: any) => {
        if (!opt) { onPick('', ''); return; }
        const picked = options.find((o) => o.value === opt.value);
        onPick(String(opt.value), picked?.name ?? '');
      }}
      options={options}
    />
  );
}

// ── 主组件 ───────────────────────────────────────────────────────────────────
export interface EditableSheetTableProps {
  columns: ColumnDef[];
  rows: SheetRow[];
  editable: boolean;
  onChange: (rows: SheetRow[]) => void;
  loading?: boolean;
}

const EditableSheetTable: React.FC<EditableSheetTableProps> = ({
  columns,
  rows,
  editable,
  onChange,
  loading,
}) => {
  const visibleColumns = columns.filter((c) => c.role !== 'AXIS');

  const updateCell = (rid: string, changes: Record<string, unknown>) => {
    onChange(rows.map((r) => (ridOf(r) === rid ? { ...r, ...changes } : r)));
  };

  const deleteRow = (rid: string) => {
    onChange(rows.filter((r) => ridOf(r) !== rid));
  };

  const addRow = () => {
    onChange([...rows, newBlankRow(columns)]);
  };

  const renderEditControl = (col: ColumnDef, row: SheetRow) => {
    const rid = ridOf(row);
    const value = row[col.name];
    const dd = col.dropdown;

    // MASTER 编码列 → 远程搜索下拉，选中回填 nameColumn
    if (dd?.kind === 'MASTER' && dd.master) {
      const nameCol = dd.nameColumn;
      const currentLabel =
        nameCol && row[nameCol]
          ? `${displayText(value) === '—' ? '' : String(value)} · ${String(row[nameCol])}`
          : undefined;
      return (
        <MasterSelectCell
          value={value}
          currentLabel={currentLabel}
          master={dd.master}
          onPick={(code, name) => {
            const changes: Record<string, unknown> = { [col.name]: code || undefined };
            if (nameCol) changes[nameCol] = name || undefined;
            updateCell(rid, changes);
          }}
        />
      );
    }

    // ENUM 固定枚举 → AutoComplete（未知可输入）
    if (dd?.kind === 'ENUM') {
      return (
        <AutoComplete
          size="small"
          style={{ minWidth: 120, width: '100%' }}
          value={value === null || value === undefined ? undefined : String(value)}
          options={(dd.options ?? []).map((o) => ({ value: o }))}
          filterOption={(input, opt) =>
            String(opt?.value ?? '').toLowerCase().includes(input.toLowerCase())
          }
          onChange={(v) => updateCell(rid, { [col.name]: v === '' ? undefined : v })}
          allowClear
          placeholder="选择/输入"
        />
      );
    }

    // FREE 自由文本
    if (dd?.kind === 'FREE') {
      return (
        <Input
          size="small"
          style={{ minWidth: 120 }}
          value={value === null || value === undefined ? '' : String(value)}
          onChange={(e) => updateCell(rid, { [col.name]: e.target.value === '' ? undefined : e.target.value })}
        />
      );
    }

    // 无 dropdown → 按 type
    if (col.type === 'DECIMAL' || col.type === 'NUMBER') {
      return (
        <InputNumber
          size="small"
          controls={false}
          stringMode
          style={{ width: '100%', minWidth: 100 }}
          value={value === null || value === undefined ? null : (String(value) as any)}
          onChange={(v) => updateCell(rid, { [col.name]: v ?? undefined })}
        />
      );
    }

    if (col.type === 'BOOLEAN') {
      return (
        <Select
          size="small"
          allowClear
          style={{ minWidth: 90 }}
          value={value === null || value === undefined ? undefined : Boolean(value)}
          options={[{ label: '是', value: true }, { label: '否', value: false }]}
          onChange={(v) => updateCell(rid, { [col.name]: v })}
        />
      );
    }

    // STRING 默认
    return (
      <Input
        size="small"
        style={{ minWidth: 120 }}
        value={value === null || value === undefined ? '' : String(value)}
        onChange={(e) => updateCell(rid, { [col.name]: e.target.value === '' ? undefined : e.target.value })}
      />
    );
  };

  const tableColumns: ColumnsType<SheetRow> = visibleColumns.map((col) => {
    const cellEditable = editable && col.editable && col.role !== 'NAME';
    return {
      title: col.label,
      dataIndex: col.name,
      key: col.name,
      width: cellEditable ? 180 : 140,
      render: (_: unknown, row: SheetRow) => {
        if (!cellEditable) {
          return <span style={{ color: col.role === 'NAME' ? '#8c8c8c' : undefined }}>{displayText(row[col.name])}</span>;
        }
        return renderEditControl(col, row);
      },
    };
  });

  if (editable) {
    tableColumns.push({
      title: '操作',
      key: '__ops',
      fixed: 'right',
      width: 72,
      render: (_: unknown, row: SheetRow) => (
        <Button
          type="link"
          size="small"
          danger
          icon={<DeleteOutlined />}
          disabled={rows.length <= 1}
          title={rows.length <= 1 ? '至少保留一行' : '删除该行'}
          onClick={() => deleteRow(ridOf(row))}
        >
          删除
        </Button>
      ),
    });
  }

  return (
    <div>
      <Table<SheetRow>
        rowKey={(r) => ridOf(r)}
        size="small"
        columns={tableColumns}
        dataSource={rows}
        loading={loading}
        pagination={false}
        scroll={{ x: 'max-content' }}
      />
      {editable && (
        <Space style={{ marginTop: 12 }}>
          <Button icon={<PlusOutlined />} onClick={addRow}>
            新增行
          </Button>
        </Space>
      )}
    </div>
  );
};

export default EditableSheetTable;
