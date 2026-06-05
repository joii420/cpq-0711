import React from 'react';
import type { FormulaToken } from '../../pages/component/types';

export interface FormulaZoneProps {
  tokens: FormulaToken[];
  onChange: (tokens: FormulaToken[]) => void;
}

function getChipStyle(type: FormulaToken['type']): React.CSSProperties {
  switch (type) {
    case 'field':
    case 'b_field':
      return {
        background: '#e1f0ff',
        border: '1px solid #c6e0ff',
        color: '#1677ff',
      };
    case 'operator':
    case 'bracket_open':
    case 'bracket_close':
      return {
        background: '#f0f9eb',
        border: '1px solid #d5f0c2',
        color: '#52c41a',
      };
    case 'component_subtotal':
      return {
        background: '#fff8e6',
        border: '1px solid #ffe4a0',
        color: '#d48806',
      };
    case 'cross_tab_ref':
      return {
        background: '#f6f0ff',
        border: '1px solid #d9b3ff',
        color: '#531dab',
      };
    case 'product_attribute':
      return {
        background: '#f3e8ff',
        border: '1px solid #d9b3ff',
        color: '#722ed1',
      };
    case 'number':
      return {
        background: '#f5f5f5',
        border: '1px solid #d9d9d9',
        color: '#595959',
      };
    case 'quotation_field':
      return {
        background: '#fff0f0',
        border: '1px solid #ffccc7',
        color: '#cf1322',
      };
    case 'path':
      return {
        background: '#e6fffb',
        border: '1px solid #87e8de',
        color: '#08979c',
      };
    case 'global_variable':
      return {
        background: '#fff7e6',
        border: '1px solid #ffd591',
        color: '#d46b08',
      };
    default:
      return {
        background: '#f5f5f5',
        border: '1px solid #d9d9d9',
        color: '#595959',
      };
  }
}

function exprTokenToText(tok: FormulaToken): string {
  switch (tok.type) {
    case 'field': return `A.${tok.label || tok.value}`;
    case 'b_field': return `本.${tok.label || tok.value}`;
    case 'operator': {
      if (tok.value === '*') return '×';
      if (tok.value === '/') return '÷';
      return tok.value || '';
    }
    case 'bracket_open': return '(';
    case 'bracket_close': return ')';
    case 'number': return tok.value || '';
    case 'global_variable': return tok.code || tok.label || '全局变量';
    default: return tok.value || '';
  }
}

function getTokenLabel(token: FormulaToken): string {
  if (token.type === 'b_field') {
    return `本.${token.label || token.value}`;
  }
  if (token.type === 'component_subtotal') {
    // Show "组件名·字段名" format for cross-component references
    if (token.label && token.label.includes('·')) return token.label;
    // Fallback for old tokens: use component_code as component identifier
    const compLabel = token.component_code || '组件';
    const fieldLabel = token.tab_name || token.value || '小计';
    return `${compLabel}·${fieldLabel}`;
  }
  if (token.type === 'cross_tab_ref') {
    const aggLabel = token.agg && token.agg !== 'NONE' ? `${token.agg}.` : '';
    const cond = (token.match ?? []).map((p: { a: string; b: string }) => `${p.a}=本.${p.b}`).join(' 且 ');
    // If targetExpr is present, render the expression instead of a single target name
    let tgt: string;
    if (token.targetExpr && token.targetExpr.length > 0) {
      tgt = token.targetExpr.map(exprTokenToText).join(' ');
    } else {
      tgt = token.target || '行数';
    }
    return `跨页签[${token.sourceLabel ?? token.source}].${aggLabel}${tgt} 当[${cond}]`;
  }
  if (token.type === 'quotation_field') {
    return token.label || token.value || '报价单字段';
  }
  if (token.type === 'path') {
    // 显示 "🔗 字段标签" 区分本组件 field;tooltip 看完整路径
    const label = token.label || token.path || '路径';
    return `🔗 ${label}`;
  }
  if (token.type === 'global_variable') {
    // V104: 「🌐 元素核价[Cu]」/ 「🌐 核价汇率[CNY:USD]」
    const label = token.label || `${token.code ?? '全局变量'}`;
    return `🌐 ${label}`;
  }
  if (token.label) return token.label;
  if (token.type === 'product_attribute') {
    return token.attribute_name || '产品属性';
  }
  return token.value || '';
}

const FormulaZone: React.FC<FormulaZoneProps> = ({ tokens, onChange }) => {
  const removeToken = (index: number) => {
    const next = [...tokens];
    next.splice(index, 1);
    onChange(next);
  };

  return (
    <div
      style={{
        border: '1px dashed #c0c4cc',
        borderRadius: 4,
        minHeight: 36,
        padding: '4px 6px',
        display: 'flex',
        flexWrap: 'wrap',
        gap: 4,
        alignItems: 'center',
        background: '#f9f9f9',
      }}
    >
      {tokens.length === 0 ? (
        <span style={{ color: '#c0c4cc', fontSize: 12, userSelect: 'none' }}>
          从右侧拖拽字段到此处构建公式
        </span>
      ) : (
        tokens.map((token, index) => {
          const chipStyle = getChipStyle(token.type);
          return (
            <span
              key={index}
              style={{
                display: 'inline-flex',
                alignItems: 'center',
                borderRadius: 3,
                padding: '1px 6px',
                fontSize: 12,
                lineHeight: '20px',
                ...chipStyle,
              }}
            >
              {getTokenLabel(token)}
              <span
                role="button"
                aria-label="remove"
                onClick={() => removeToken(index)}
                style={{
                  marginLeft: 4,
                  cursor: 'pointer',
                  fontSize: 10,
                  lineHeight: 1,
                  opacity: 0.7,
                  userSelect: 'none',
                }}
              >
                ×
              </span>
            </span>
          );
        })
      )}
    </div>
  );
};

export default FormulaZone;
