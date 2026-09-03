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
import { Select, AutoComplete, InputNumber, Input, Button, Spin, Typography } from 'antd';
import { DeleteOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { Table } from 'antd';
import type { ColumnDef, ColumnType, MasterType, SheetRow } from './types';
import { lookup } from './api';
import type { LookupFn } from './api';
import { normalizeDecimalString, isDecimalString } from '../../../utils/precision';

const { Text } = Typography;

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

/**
 * 只读单元格的展示值。
 *
 * 🚨 task-260902 · F-11（AC-53/54/55）修复「按值的形状猜类型」：
 *    本函数原来是 `if (typeof v === 'string' && isDecimalString(v))` —— **不看列类型，只看值长什么样**。
 *    于是 `type=STRING` 的编码列只要**长得像数字**就被当数值处理，前导零被抹掉：
 *      `00168` → `168`　`00006` → `6`　`00001` → `1`　`1.10` → `1.1`（实算复现，见任务回报）
 *    实测 `material_recipe` 260 个 code 里 **258 个带前导零**，且这些值已落在
 *    `ds_*_element_bom.material_part_no` 里 ⇒ 「物料与元素BOM」的材质料号必然显示错。
 *
 *    ⇒ 改为**由列类型决定**：只有 `DECIMAL` / `NUMBER` 才走去尾零，其余（含 STRING、以及
 *      调用方没给类型的场景）一律 `String(v)` 原样透传。
 *
 * 🚫 不能改 `precision.ts` —— `isDecimalString` / `normalizeDecimalString` 本身没错，
 *    错的是这里的**判据**（拿它当类型探测器用）。
 * 🚫 数值列的去尾零行为**必须原样保留**（AC-54 专门验这条，防止修过头）。
 */
function displayText(v: unknown, type?: ColumnType): string {
  if (v === null || v === undefined || v === '') return '—';
  if (typeof v === 'boolean') return v ? '是' : '否';
  const isNumericColumn = type === 'DECIMAL' || type === 'NUMBER';
  if (!isNumericColumn) return String(v);
  // 以下仅对**数值列**成立：后端按 DB 列 scale 定标序列化（如 "1.230000000000"）。
  //
  // ⚠️ 用 normalizeDecimalString（纯去尾零、**不截位**）而非 formatDisplayDecimal（截 9 位）：
  //    task-0813 把基础资料列扩到 12 位小数，本页是这些值的**编辑界面**。截到 9 位会让用户在
  //    格内做局部编辑时把刚扩容的 12 位精度退回 9 位 —— 为观感牺牲本任务的核心目标。
  //    用户真正要消除的是「补零到 12 位」的噪声（1.230000000000 → 1.23），去尾零已完全解决；
  //    真实有效数字（如 91.768628123457）必须完整可见可编辑。
  //    报价单/核价单/导出侧仍走 DISPLAY_SCALE=9 的全局口径，本处例外仅限编辑型维护页。
  if (typeof v === 'string' && isDecimalString(v)) return normalizeDecimalString(v);
  return String(v);
}

// ── NAME 列灰字兜底（childtask-1 · F2.2）────────────────────────────────────
// 四码名称列（工序名/元素名/料号名/材质名）统一走此渲染：有值显示灰字原值，
// 空值显示灰字兜底提示。纯展示、不阻断保存/浏览。
/** 材质名（未绑 material_recipe_id）提示「未绑定」；其余名称列（未维护对应主表编码）提示「未维护」 */
export function nameColumnHint(colName: string): string {
  return colName === 'material_recipe_name' ? '未绑定' : '未维护';
}

/** 名称列渲染：有值显示灰字原值，空值显示灰字 hint 兜底 */
export function renderNameOrHint(value: unknown, hint: string): React.ReactNode {
  const hasValue = value !== null && value !== undefined && value !== '';
  return <Text type="secondary">{hasValue ? String(value) : hint}</Text>;
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
  /** task-260902 · F-1：下拉数据源可注入（dataset 侧走 /dataset/{ds}/lookup），不传＝现有 /pricing-basic-data/lookup */
  lookupFn?: LookupFn;
}) {
  const { value, currentLabel, master, onPick, lookupFn = lookup } = props;
  const [options, setOptions] = useState<MasterOption[]>([]);
  const [fetching, setFetching] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const doSearch = (kw: string) => {
    if (timer.current) clearTimeout(timer.current);
    timer.current = setTimeout(async () => {
      setFetching(true);
      try {
        const r = await lookupFn(master, kw);
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
  /**
   * task-260902 · F-1（三个都是**可选**，不传时渲染与改造前逐像素一致）：
   * - `showComparedBadge`：列头按 `ColumnDef.compared` 打 🔗 角标（比对项，原型「核价数据-抽屉」）。
   *   现有页签不传 ⇒ 恒 false ⇒ 列头只出 label，与改造前一致。
   * - `lookupFn`：MASTER 下拉的数据源，dataset 侧注入 `/dataset/{ds}/lookup`。
   * - `rowClassName`：行样式钩子，保存冲突态给「本地未保存改动行」上浅红底（原型「核价数据-保存冲突」）。
   */
  showComparedBadge?: boolean;
  lookupFn?: LookupFn;
  rowClassName?: (row: SheetRow) => string | undefined;
  /**
   * 「只剩一行时删除按钮禁用」的 hover 文案。
   * 默认 `至少保留一行`＝现有「料号核价」页签改造前的原文案（AC-42 零变化）；
   * dataset 侧传 api.md §7 的 422 原文，让用户在**点之前**就知道为什么不能删。
   */
  deleteDisabledTip?: string;
}

const EditableSheetTable: React.FC<EditableSheetTableProps> = ({
  columns,
  rows,
  editable,
  onChange,
  loading,
  showComparedBadge = false,
  lookupFn,
  rowClassName,
  deleteDisabledTip = '至少保留一行',
}) => {
  const visibleColumns = columns.filter((c) => c.role !== 'AXIS');

  const updateCell = (rid: string, changes: Record<string, unknown>) => {
    onChange(rows.map((r) => (ridOf(r) === rid ? { ...r, ...changes } : r)));
  };

  const deleteRow = (rid: string) => {
    onChange(rows.filter((r) => ridOf(r) !== rid));
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
          ? `${displayText(value, col.type) === '—' ? '' : String(value)} · ${String(row[nameCol])}`
          : undefined;
      return (
        <MasterSelectCell
          value={value}
          currentLabel={currentLabel}
          master={dd.master}
          lookupFn={lookupFn}
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
      // 展示值：仅用于 InputNumber 的 value 回显，**只去尾零、不截位**
      //（1.230000000000 → 1.23，但 91.768628123457 原样保留）。
      // 关键一：格式化结果只喂给受控 value prop，绝不写回 row state。用户未编辑该格 → onChange 不触发
      //         → row[col.name] 保持后端下发的原始定标字符串 → 保存时回存值精度不丢。
      // 关键二：**不能用 formatDisplayDecimal 截 9 位**。这是受控输入框，截断后的文本就是用户下次
      //         编辑的起点；一旦在格内做局部修改，onChange 拿到的就是基于 9 位的值，
      //         task-0813 刚扩到 12 位的精度会被静默改回 9 位（见本文件 displayText 的同款说明）。
      const rawStr = value === null || value === undefined ? '' : String(value);
      const displayValue = rawStr === ''
        ? null
        : ((isDecimalString(rawStr) ? normalizeDecimalString(rawStr) : rawStr) as any);
      return (
        <InputNumber
          size="small"
          controls={false}
          stringMode
          style={{ width: '100%', minWidth: 100 }}
          value={displayValue}
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
      // 🔗 = 比对项（参与行指纹）。仅在调用方显式开启且后端下发 compared=true 时渲染。
      title: showComparedBadge && col.compared ? `${col.label} 🔗` : col.label,
      dataIndex: col.name,
      key: col.name,
      width: cellEditable ? 180 : 140,
      render: (_: unknown, row: SheetRow) => {
        if (!cellEditable) {
          if (col.role === 'NAME') {
            return renderNameOrHint(row[col.name], nameColumnHint(col.name));
          }
          return <span>{displayText(row[col.name], col.type)}</span>;
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
          title={rows.length <= 1 ? deleteDisabledTip : '删除该行'}
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
        rowClassName={rowClassName ? (r) => rowClassName(r) ?? '' : undefined}
      />
    </div>
  );
};

export default EditableSheetTable;
