import React from 'react';
import { Tabs, Tooltip } from 'antd';
import { DeleteOutlined, ArrowUpOutlined, ArrowDownOutlined } from '@ant-design/icons';
import { useDroppable } from '@dnd-kit/core';
import type { TemplateComponentItem } from './types';
import ComponentTablePreview from './ComponentTablePreview';
import './styles.css';

interface TabComponentAreaProps {
  tabComponents: TemplateComponentItem[];
  componentsSnapshot: any[];
  activeTabKey: string;
  onTabChange: (key: string) => void;
  onRemoveTab: (tcId: string) => void;
  onMoveUp: (idx: number) => void;
  onMoveDown: (idx: number) => void;
  isDraft: boolean;
  presetRowsMap?: Record<string, any[]>;
  onPresetRowsChange?: (tcId: string, rows: any[]) => void;
}

const CanvasDropzone: React.FC = () => {
  const { isOver, setNodeRef } = useDroppable({ id: 'canvas-dropzone' });

  return (
    <div
      ref={setNodeRef}
      className={`tm-tab-dropzone ${isOver ? 'drag-over' : ''}`}
    >
      {isOver ? '松开以添加组件' : '拖拽左侧组件到此处添加'}
    </div>
  );
};

const TabComponentArea: React.FC<TabComponentAreaProps> = ({
  tabComponents,
  componentsSnapshot,
  activeTabKey,
  onTabChange,
  onRemoveTab,
  onMoveUp,
  onMoveDown,
  isDraft,
  presetRowsMap = {},
  onPresetRowsChange,
}) => {
  // Filter out SUBTOTAL components — they render in SubtotalDropBar, not as tabs
  const normalTcs = tabComponents.filter(tc => {
    const comp = componentsSnapshot.find((c: any) => c.id === tc.componentId);
    return !comp || comp.componentType !== 'SUBTOTAL';
  });

  const tabItems = normalTcs.map((tc, idx) => {
    const comp = componentsSnapshot.find((c) => c.id === tc.componentId);
    const fields: any[] = comp && Array.isArray(comp.fields) ? comp.fields : (tc.fields || []);
    const formulas: any[] = comp && Array.isArray(comp.formulas) ? comp.formulas : [];

    return {
      key: tc.id,
      label: (
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
          <span>{tc.tabName || `组件${idx + 1}`}</span>
          {isDraft && (
            <span style={{ display: 'inline-flex', gap: 3, marginLeft: 4 }}>
              <Tooltip title="上移">
                <ArrowUpOutlined
                  style={{ fontSize: 10, cursor: 'pointer', opacity: idx === 0 ? 0.3 : 1 }}
                  onClick={(e) => {
                    e.stopPropagation();
                    onMoveUp(idx);
                  }}
                />
              </Tooltip>
              <Tooltip title="下移">
                <ArrowDownOutlined
                  style={{
                    fontSize: 10,
                    cursor: 'pointer',
                    opacity: idx === tabComponents.length - 1 ? 0.3 : 1,
                  }}
                  onClick={(e) => {
                    e.stopPropagation();
                    onMoveDown(idx);
                  }}
                />
              </Tooltip>
              <Tooltip title="移除">
                <DeleteOutlined
                  style={{ fontSize: 10, cursor: 'pointer', color: '#ff4d4f' }}
                  onClick={(e) => {
                    e.stopPropagation();
                    onRemoveTab(tc.id);
                  }}
                />
              </Tooltip>
            </span>
          )}
        </span>
      ),
      children: (
        <ComponentTablePreview
          fields={fields}
          formulas={formulas}
          tabName={tc.tabName}
          tcId={tc.id}
          presetRows={presetRowsMap[tc.id] || []}
          onPresetRowsChange={onPresetRowsChange ? (rows) => onPresetRowsChange(tc.id, rows) : undefined}
          isDraft={isDraft}
        />
      ),
    };
  });

  return (
    <div className="tm-tabs-section">
      {/* Drop zone */}
      <CanvasDropzone />

      {normalTcs.length === 0 ? (
        <div className="tm-empty-tabs">
          {isDraft ? '从左侧面板拖拽组件到此处添加' : '暂无组件'}
        </div>
      ) : (
        <div className="tm-tabs-container">
          <Tabs
            activeKey={activeTabKey}
            onChange={onTabChange}
            size="small"
            items={tabItems}
          />
        </div>
      )}
    </div>
  );
};

export default TabComponentArea;
