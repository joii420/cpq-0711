import React, { useState } from 'react';
import { Tabs, Tooltip, Tag, Button, Space } from 'antd';
import { DeleteOutlined, ArrowUpOutlined, ArrowDownOutlined, SettingOutlined } from '@ant-design/icons';
import { useDroppable } from '@dnd-kit/core';
import type { TemplateComponentItem } from './types';
import ComponentTablePreview from './ComponentTablePreview';
import OverridesDrawer from './OverridesDrawer';
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
  /** V204: 模板 id, OverridesDrawer 调 PATCH 用 */
  templateId?: string;
  /** V204: 保存后回调, 父组件 reload tcs */
  onOverridesSaved?: () => void;
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
  templateId,
  onOverridesSaved,
}) => {
  // V204: 当前打开 OverridesDrawer 的 tcId; null = 关闭
  const [overridesTcId, setOverridesTcId] = useState<string | null>(null);

  // Filter out SUBTOTAL components — they render in SubtotalDropBar, not as tabs
  const normalTcs = tabComponents.filter(tc => {
    const comp = componentsSnapshot.find((c: any) => c.id === tc.componentId);
    return !comp || comp.componentType !== 'SUBTOTAL';
  });

  // V204: tc 的有效字段 / driver = override 优先, 否则 component 默认 (与后端 publish 规则一致).
  // 编辑视图据此渲染 Preview, 让用户所见即所得.
  const resolveEffective = (tc: TemplateComponentItem, comp: any) => {
    let effectiveFields: any[];
    if (tc.fieldsOverride && tc.fieldsOverride.trim().length > 0) {
      try {
        const parsed = JSON.parse(tc.fieldsOverride);
        effectiveFields = Array.isArray(parsed) ? parsed : [];
      } catch {
        effectiveFields = comp && Array.isArray(comp.fields) ? comp.fields : (tc.fields || []);
      }
    } else {
      effectiveFields = comp && Array.isArray(comp.fields) ? comp.fields : (tc.fields || []);
    }
    const effectiveDriver = tc.dataDriverPathOverride || comp?.dataDriverPath || '';
    return { effectiveFields, effectiveDriver };
  };

  const tabItems = normalTcs.map((tc, idx) => {
    const comp = componentsSnapshot.find((c) => c.id === tc.componentId);
    const { effectiveFields, effectiveDriver } = resolveEffective(tc, comp);
    const formulas: any[] = comp && Array.isArray(comp.formulas) ? comp.formulas : [];
    const hasOverride = !!tc.fieldsOverride || !!tc.dataDriverPathOverride;

    return {
      key: tc.id,
      label: (
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
          <span>{tc.tabName || `组件${idx + 1}`}</span>
          {hasOverride && (
            <Tooltip title="此 Tab 已覆盖组件默认配置">
              <Tag color="orange" style={{ marginLeft: 2, marginRight: 0, fontSize: 10, lineHeight: '14px', padding: '0 4px' }}>
                覆盖
              </Tag>
            </Tooltip>
          )}
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
        <>
          {/* V204: Tab 内顶部工具栏 — 显示当前 effective driver + override 入口 */}
          {templateId && (
            <Space size={8} style={{ marginBottom: 8, fontSize: 12 }}>
              <span style={{ color: '#666' }}>当前 Driver:</span>
              <code style={{ fontSize: 11 }}>{effectiveDriver || '(无)'}</code>
              {tc.dataDriverPathOverride && <Tag color="orange" style={{ fontSize: 10 }}>已覆盖</Tag>}
              {tc.fieldsOverride && <Tag color="orange" style={{ fontSize: 10 }}>字段已覆盖</Tag>}
              <Button
                size="small"
                type="link"
                icon={<SettingOutlined />}
                onClick={() => setOverridesTcId(tc.id)}
              >
                {isDraft ? '编辑字段 / Driver 覆盖' : '查看覆盖配置'}
              </Button>
            </Space>
          )}
          <ComponentTablePreview
            fields={effectiveFields}
            formulas={formulas}
            tabName={tc.tabName}
            tcId={tc.id}
            presetRows={presetRowsMap[tc.id] || []}
            onPresetRowsChange={onPresetRowsChange ? (rows) => onPresetRowsChange(tc.id, rows) : undefined}
            isDraft={isDraft}
          />
        </>
      ),
    };
  });

  // V204: 当前打开的 tc + 对应 component
  const editingTc = overridesTcId ? normalTcs.find(t => t.id === overridesTcId) : null;
  const editingComp = editingTc ? componentsSnapshot.find(c => c.id === editingTc.componentId) : null;

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

      {/* V204: Overrides Drawer (fields_override + dataDriverPathOverride) */}
      {templateId && editingTc && (
        <OverridesDrawer
          open={overridesTcId !== null}
          templateId={templateId}
          tcId={editingTc.id}
          tabName={editingTc.tabName || ''}
          componentName={editingComp?.name}
          componentCode={editingComp?.code}
          componentDefaultFields={Array.isArray(editingComp?.fields) ? editingComp!.fields : []}
          componentDefaultFormulas={Array.isArray(editingComp?.formulas) ? editingComp!.formulas : []}
          componentDefaultDriverPath={editingComp?.dataDriverPath || null}
          currentFieldsOverride={editingTc.fieldsOverride}
          currentDataDriverPathOverride={editingTc.dataDriverPathOverride}
          isDraft={isDraft}
          onClose={() => setOverridesTcId(null)}
          onSaved={() => {
            if (onOverridesSaved) onOverridesSaved();
          }}
        />
      )}
    </div>
  );
};

export default TabComponentArea;
