// ─────────────────────────────────────────────────────────────────────────────
// ReadonlySheetTable —— 元数据驱动的**只读**平铺表格（task-260903 · F-4）
//
// 与核价侧 `EditableSheetTable` 同构，差异只有一条但是根本性的：**没有可编辑分支**。
//   · 表格内不渲染任何 input / select / textarea（AC-8）
//   · 不渲染「删除」操作列（AC-8）
//   · 🚨 无论 rows 响应里 `readOnly` 为何值一律只读 —— 本组件根本不接受 editable prop，
//     从类型上就堵死「据 readOnly 推导出可编辑分支」这条路（api.md 硬约束 7）
//
// 🚧 过渡说明（2026-09-03 主线情报更正）：原计划复用的公共件 `SheetPartDrawer` 不会存在了，
//    task-260902 改为零触碰 legacy + 新建 `pages/master-data/dataset/DatasetSheetDrawer.tsx`
//    （该目录当前尚未合入 master）。本任务**不得 import 也不得修改 `part-costing/` 下任何文件**，
//    故此处按 `EditableSheetTable` 只读分支的等价语义新写一份平行实现。
//
// 🚫 列渲染一律按 `ColumnDef.type` 判断，**禁止按列名硬编码** ——
//    task-260902 刚修正 50 列类型（31 列方向错），典型如 `pricing_unit`（计价单位）
//    由 DECIMAL 改为 STRING，硬编码必然过时（api.md 硬约束 3）。
// ─────────────────────────────────────────────────────────────────────────────
import React, { useMemo } from 'react';
import { Table, Empty } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import type { ColumnDef, ColumnType, SheetRow } from './productHubTypes';
import { renderTextCell, renderDecimalCell, renderBooleanCell } from './productHubCells';

/** 数值型列（右对齐 + 走 Decimal 格式化）。ENUM/STRING/BOOLEAN 一律不算数值。 */
function isNumericType(t?: ColumnType): boolean {
  return t === 'NUMBER' || t === 'DECIMAL';
}

/**
 * 行内部键。行数据是动态列结构、没有业务主键，而 antd 6 已弃用 `rowKey` 函数的 `index` 形参
 * （弃用告警走 console.error，会违反 AC-11「console 无 error 级日志」）。
 * ⇒ 渲染前按下标注入一个稳定合成键，`rowKey` 直接读它。
 * 本表只读、无增删行、无受控输入，行下标在一次渲染内稳定，不存在 AP-54 那类错位风险。
 */
const ROW_KEY = '__rsk';

export interface ReadonlySheetTableProps {
  columns: ColumnDef[];
  rows: SheetRow[];
  loading?: boolean;
}

const ReadonlySheetTable: React.FC<ReadonlySheetTableProps> = ({ columns, rows, loading }) => {
  // role=AXIS 的列隐藏 —— 轴值已在抽屉标题上，与核价侧一致（AC-7 / api.md 硬约束 4）
  const visibleColumns = useMemo(
    () => (columns ?? []).filter((c) => c.role !== 'AXIS'),
    [columns],
  );

  const tableColumns: ColumnsType<SheetRow> = useMemo(
    () => visibleColumns.map((col) => {
      const numeric = isNumericType(col.type);
      return {
        title: col.label,
        dataIndex: col.name,
        key: col.name,
        width: numeric ? 130 : 150,
        align: numeric ? ('right' as const) : ('left' as const),
        render: (_: unknown, row: SheetRow): React.ReactNode => {
          const v = row?.[col.name];
          if (numeric) return renderDecimalCell(v);
          if (col.type === 'BOOLEAN') return renderBooleanCell(v);
          // STRING / ENUM / NAME（后端不下发 type）一律按文本原样显示
          return renderTextCell(v);
        },
      };
    }),
    [visibleColumns],
  );

  const dataSource = useMemo(
    () => (rows ?? []).map((r, i) => ({ ...r, [ROW_KEY]: String(i) })),
    [rows],
  );

  return (
    <Table<SheetRow>
      rowKey={ROW_KEY}
      size="small"
      columns={tableColumns}
      dataSource={dataSource}
      loading={loading}
      pagination={false}
      scroll={{ x: 'max-content' }}
      locale={{
        emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无数据" />,
      }}
    />
  );
};

export default ReadonlySheetTable;
