import React from 'react';
import { Select, Tag, Empty, Typography } from 'antd';
import type { ComponentSqlView } from '../../services/componentSqlViewService';

const { Text } = Typography;

export function buildColumnPath(viewName: string, col: string): string {
  return `$${viewName}.${col}`;
}

interface Props {
  views: ComponentSqlView[];
  selectedViewName?: string;
  onSelectView?: (viewName: string) => void;
  /** driverOnly：锁定到 driver 视图，视图选择只读 */
  driverOnly?: boolean;
  onPick: (path: string, label: string) => void;
  /** 当前已选路径（高亮用） */
  currentPath?: string;
}

export const ViewColumnPickerBody: React.FC<Props> = ({
  views, selectedViewName, onSelectView, driverOnly, onPick, currentPath,
}) => {
  const view = views.find((v) => v.sqlViewName === selectedViewName) ?? views[0];
  const cols = view?.declaredColumns ?? [];
  return (
    <div>
      <div style={{ marginBottom: 8 }}>
        <Text type="secondary" style={{ fontSize: 12 }}>选择视图</Text>
        <Select
          style={{ width: '100%', marginTop: 4 }}
          value={view?.sqlViewName}
          disabled={driverOnly}
          onChange={(v) => onSelectView?.(v)}
          options={views.map((v) => ({ label: v.sqlViewName, value: v.sqlViewName }))}
          placeholder="选择 SQL 视图"
        />
        {driverOnly && <Text type="secondary" style={{ fontSize: 11 }}>（字段只能取 driver 视图的列）</Text>}
      </div>
      <div>
        <Text type="secondary" style={{ fontSize: 12 }}>点击字段列即选中</Text>
        <div style={{ marginTop: 6, display: 'flex', flexWrap: 'wrap', gap: 6 }}>
          {cols.length === 0
            ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="该视图无列 / 请先把数据驱动路径配为 SQL 视图" />
            : cols.map((c) => {
                const path = buildColumnPath(view!.sqlViewName, c.name);
                const active = currentPath === path;
                return (
                  <Tag.CheckableTag key={c.name} checked={active} onChange={() => onPick(path, c.name)}>
                    {c.name}
                  </Tag.CheckableTag>
                );
              })}
        </div>
      </div>
    </div>
  );
};
