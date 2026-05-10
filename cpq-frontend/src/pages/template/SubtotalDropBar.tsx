import React from 'react';
import { Button } from 'antd';
import { DeleteOutlined } from '@ant-design/icons';
import { useDroppable } from '@dnd-kit/core';
import type { TemplateComponentItem } from './types';
import './styles.css';

interface SubtotalDropBarProps {
  tcs: TemplateComponentItem[];
  availableComponents: any[];
  isDraft: boolean;
  onRemove: (tcId: string) => void;
}

const SubtotalDropBar: React.FC<SubtotalDropBarProps> = ({
  tcs,
  availableComponents,
  isDraft,
  onRemove,
}) => {
  const { isOver, setNodeRef } = useDroppable({ id: 'subtotal-dropzone' });

  // Find subtotal components in current template
  const subtotalTcs = tcs.filter(tc => {
    const comp = availableComponents.find((c: any) => c.id === tc.componentId);
    return comp?.componentType === 'SUBTOTAL';
  });

  const hasSubtotal = subtotalTcs.length > 0;

  // Get formula display for the subtotal component
  const getFormulaDisplay = (tc: TemplateComponentItem) => {
    const comp = availableComponents.find((c: any) => c.id === tc.componentId);
    if (!comp?.formulas) return '暂无公式';
    const formulas = typeof comp.formulas === 'string' ? JSON.parse(comp.formulas) : comp.formulas;
    if (!Array.isArray(formulas) || formulas.length === 0) return '暂无公式';
    return formulas.map((f: any) => {
      const expr = (f.expression || [])
        .map((t: any) => t.label || t.value || '')
        .join(' ');
      return `${f.name}: ${expr || '(空)'}`;
    }).join('; ');
  };

  return (
    <div
      ref={setNodeRef}
      className={`tm-subtotal-drop-bar${isOver ? ' drag-over' : ''}${hasSubtotal ? ' has-content' : ''}`}
    >
      {hasSubtotal ? (
        subtotalTcs.map(tc => (
          <div key={tc.id} className="tm-subtotal-drop-content">
            <div>
              <div style={{ fontWeight: 600, fontSize: 14 }}>💰 {tc.tabName}</div>
              <div style={{ fontSize: 12, opacity: 0.85, marginTop: 4 }}>{getFormulaDisplay(tc)}</div>
            </div>
            {isDraft && (
              <Button
                type="text"
                size="small"
                icon={<DeleteOutlined />}
                onClick={() => onRemove(tc.id)}
                style={{ color: 'rgba(255,255,255,0.8)' }}
              />
            )}
          </div>
        ))
      ) : (
        <div className="tm-subtotal-drop-placeholder">
          {isOver ? '松开以设置小计组件' : '将小计组件拖拽到此处设置产品小计'}
        </div>
      )}
    </div>
  );
};

export default SubtotalDropBar;
