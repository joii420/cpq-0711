import React, { createContext, useContext, useMemo } from 'react';
import { Table } from 'antd';
import type { TableProps } from 'antd';
import { HolderOutlined } from '@ant-design/icons';
import {
  DndContext, PointerSensor, KeyboardSensor, useSensor, useSensors,
  closestCenter, type DragEndEvent,
} from '@dnd-kit/core';
import {
  SortableContext, useSortable, verticalListSortingStrategy, arrayMove,
  sortableKeyboardCoordinates,
} from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';

interface RowContextProps {
  setActivatorNodeRef?: (el: HTMLElement | null) => void;
  listeners?: Record<string, unknown>;
}
const RowContext = createContext<RowContextProps>({});

/** 放进某一列的 render 里作为拖拽手柄。 */
export const DragHandle: React.FC = () => {
  const { setActivatorNodeRef, listeners } = useContext(RowContext);
  return (
    <HolderOutlined
      ref={setActivatorNodeRef as unknown as React.Ref<HTMLSpanElement>}
      {...listeners}
      style={{ cursor: 'move', color: '#999', touchAction: 'none' }}
    />
  );
};

const SortableRow: React.FC<React.HTMLAttributes<HTMLTableRowElement> & { 'data-row-key': string }> = (props) => {
  const { attributes, listeners, setNodeRef, setActivatorNodeRef, transform, transition, isDragging } =
    useSortable({ id: props['data-row-key'] });
  const style: React.CSSProperties = {
    ...props.style,
    transform: CSS.Translate.toString(transform),
    transition,
    ...(isDragging ? { position: 'relative', zIndex: 999, background: '#fafafa' } : {}),
  };
  const ctx = useMemo<RowContextProps>(() => ({ setActivatorNodeRef, listeners }), [setActivatorNodeRef, listeners]);
  return (
    <RowContext.Provider value={ctx}>
      <tr {...props} ref={setNodeRef} style={style} {...attributes} />
    </RowContext.Provider>
  );
};

export interface SortableTableProps<T> extends Omit<TableProps<T>, 'components'> {
  /** 稳定且唯一的行 id 字段名（如字段行用 'key'，EXCEL 列用 'col_key'）。 */
  rowKey: string;
  /** 拖拽结束后的新数组顺序。 */
  onReorder: (next: T[]) => void;
}

/** AntD Table + dnd-kit 垂直拖拽排序。消费方需在 columns 里放一列 render={() => <DragHandle />}。 */
export function SortableTable<T extends object>(
  { rowKey, onReorder, dataSource, ...rest }: SortableTableProps<T>,
) {
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  );
  const list = (dataSource ?? []) as T[];
  const keyOf = (d: T) => String((d as Record<string, unknown>)[rowKey]);
  const items = list.map(keyOf);
  const onDragEnd = ({ active, over }: DragEndEvent) => {
    if (!over || active.id === over.id) return;
    const from = list.findIndex((d) => keyOf(d) === active.id);
    const to = list.findIndex((d) => keyOf(d) === over.id);
    if (from < 0 || to < 0) return;
    onReorder(arrayMove(list, from, to));
  };
  return (
    <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={onDragEnd}>
      <SortableContext items={items} strategy={verticalListSortingStrategy}>
        <Table<T>
          {...rest}
          rowKey={rowKey}
          dataSource={dataSource}
          components={{ body: { row: SortableRow as never } }}
        />
      </SortableContext>
    </DndContext>
  );
}
