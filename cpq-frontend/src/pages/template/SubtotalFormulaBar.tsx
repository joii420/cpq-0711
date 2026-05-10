import React from 'react';
import FormulaZone from '../../components/formula/FormulaZone';
import type { FormulaToken } from '../component/types';
import './styles.css';

interface SubtotalFormulaBarProps {
  tokens: FormulaToken[];
  onChange: (tokens: FormulaToken[]) => void;
  availableSubtotals: { code: string; name: string }[];
  availableAttributes: { name: string }[];
  disabled?: boolean;
}

const SubtotalFormulaBar: React.FC<SubtotalFormulaBarProps> = ({
  tokens,
  onChange,
  availableSubtotals,
  availableAttributes,
  disabled = false,
}) => {
  const addSubtotalToken = (sub: { code: string; name: string }) => {
    if (disabled) return;
    const token: FormulaToken = {
      type: 'component_subtotal',
      value: sub.code,
      label: `${sub.name}.小计`,
      tab_name: sub.name,
      component_code: sub.code,
    };
    onChange([...tokens, token]);
  };

  const addAttributeToken = (attr: { name: string }) => {
    if (disabled) return;
    const token: FormulaToken = {
      type: 'product_attribute',
      value: attr.name,
      label: attr.name,
      attribute_name: attr.name,
    };
    onChange([...tokens, token]);
  };

  const addOperatorToken = (op: string) => {
    if (disabled) return;
    const token: FormulaToken = { type: 'operator', value: op, label: op };
    onChange([...tokens, token]);
  };

  return (
    <div id="subtotal-formula-bar" className="tm-subtotal-bar" style={{ flexDirection: 'column', alignItems: 'stretch' }}>
      {/* Top row: label + formula zone */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
        <span className="tm-subtotal-label">💰 产品小计</span>
        <div className="tm-subtotal-formula-zone">
          <FormulaZone tokens={tokens} onChange={disabled ? () => {} : onChange} />
        </div>
      </div>

      {/* Chip palette */}
      {!disabled && (
        <div className="tm-subtotal-chips">
          {/* Operators */}
          {['+', '-', '*', '/'].map((op) => (
            <span
              key={op}
              className="tm-chip-op"
              onClick={() => addOperatorToken(op)}
            >
              {op}
            </span>
          ))}

          {availableSubtotals.length > 0 && (
            <span className="tm-chip-divider">|</span>
          )}

          {/* Component subtotals */}
          {availableSubtotals.map((sub) => (
            <span
              key={sub.code}
              className="tm-chip-sub"
              onClick={() => addSubtotalToken(sub)}
            >
              {sub.name}.小计
            </span>
          ))}

          {availableAttributes.length > 0 && (
            <span className="tm-chip-divider">|</span>
          )}

          {/* NUMBER product attributes */}
          {availableAttributes.map((attr) => (
            <span
              key={attr.name}
              className="tm-chip-attr"
              onClick={() => addAttributeToken(attr)}
            >
              {attr.name}
            </span>
          ))}
        </div>
      )}
    </div>
  );
};

export default SubtotalFormulaBar;
