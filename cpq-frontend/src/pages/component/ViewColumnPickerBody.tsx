import React from 'react';
import { Select, Tag, Empty, Typography } from 'antd';
import type { ComponentSqlView } from '../../services/componentSqlViewService';

const { Text } = Typography;

export function buildColumnPath(viewName: string, col: string): string {
  return `$${viewName}.${col}`;
}

/**
 * 按视图 scope 生成正确的 BNF 路径（与 PathPickerDrawer.generatedSqlPath 同源规则）：
 * 跨组件 GLOBAL 视图（scope=GLOBAL 且不属于当前 effectiveComponentId）→ `$$<componentCode>.<view>.<col>`；
 * 否则 → `$<view>.<col>`。漏掉 `$$` 前缀会让核价模板列等跨组件取数落库格式错（后端 SqlViewPathRewriter 找不到组件维度）。
 */
export function buildViewColumnPath(
  view: ComponentSqlView,
  col: string,
  effectiveComponentId?: string,
): string {
  const isGlobal = view.scope === 'GLOBAL' && view.componentId !== effectiveComponentId;
  const prefix = isGlobal
    ? `$$${view.componentCode ?? view.componentId}.${view.sqlViewName}`
    : `$${view.sqlViewName}`;
  return `${prefix}.${col}`;
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
  /** 当前 owner 组件 id；用于判定某视图是否"跨组件 GLOBAL"以决定 $$ 前缀 */
  effectiveComponentId?: string;
}

export const ViewColumnPickerBody: React.FC<Props> = ({
  views, selectedViewName, onSelectView, driverOnly, onPick, currentPath, effectiveComponentId,
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
                const path = buildViewColumnPath(view!, c.name, effectiveComponentId);
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
