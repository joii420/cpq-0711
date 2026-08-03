import React from 'react';
import type { FormulaToken } from '../../pages/component/types';
import { treeAttrChipLabel, treeRefChipLabel } from '../../pages/component/crossTabText';

export interface FormulaZoneProps {
  tokens: FormulaToken[];
  onChange: (tokens: FormulaToken[]) => void;
  /**
   * task-0803 Task 8 (F-5)：点击 chip 本体（非删除的 "×"）时触发，供宿主重新打开配置抽屉编辑。
   * 目前仅 `tree_ref` 类型的 chip 会触发（点 chip 可重新打开「父子取值」抽屉编辑）；
   * 可选，缺省不传 = 维持原有"仅可删除、不可点击编辑"行为，向后兼容既有调用点。
   */
  onTokenClick?: (token: FormulaToken, index: number) => void;
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
    case 'tree_ref':
      // task-0803: BOM 树父子取值(PGET/C* 族) —— 绿色系,与 cross_tab_ref 的紫色系区分"树内取值" vs "跨页签取值"
      return {
        background: '#f6ffed',
        border: '1px solid #b7eb8f',
        color: '#389e0d',
      };
    case 'tree_attr':
      // task-0803: BOM 树属性([层级]/[是否叶子]/[是否根]) —— 同色系浅一档,标记"只读标量"
      return {
        background: '#e6fffb',
        border: '1px solid #87e8de',
        color: '#08979c',
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
    // 2026-06-30 WYSIWYG: 整页签总计标记优先 → 「组件名(总计)」，不读 value/tab_name（仍存列名供求值）。
    // 注：is_tab_total token 仅由 expressionToTokens 产出（TabJoinFormulaDrawer / buildExcelSnapshot），
    // 不经 FormulaZone（仅 FormulaBuilder / SubtotalFormulaBar chip 编辑器使用）；此分支为冗余兜底，保显示一致。
    if (token.is_tab_total) {
      return `${token.label || token.component_code || '组件'}(总计)`;
    }
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
  if (token.type === 'tree_ref') {
    // task-0803 F-5: 「父行(累计用量)」/「子行合计(用量 × 单价)」等，逐字文案见 crossTabText.ts
    return treeRefChipLabel(token.dir, token.agg, token.targetExpr);
  }
  if (token.type === 'tree_attr') {
    // task-0803 F-6: 「[层级]」/「[是否叶子]」/「[是否根]」
    return treeAttrChipLabel(token.attr);
  }
  if (token.label) return token.label;
  if (token.type === 'product_attribute') {
    return token.attribute_name || '产品属性';
  }
  return token.value || '';
}

const FormulaZone: React.FC<FormulaZoneProps> = ({ tokens, onChange, onTokenClick }) => {
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
          // task-0803 F-5：仅 tree_ref chip 可点击重新打开配置抽屉；其余 token 类型维持原有的
          // "纯展示 + 可删除"行为不变（onTokenClick 未传或 token 类型不支持时不挂 onClick）。
          const clickable = !!onTokenClick && token.type === 'tree_ref';
          return (
            <span
              key={index}
              data-token-type={token.type}
              onClick={clickable ? () => onTokenClick!(token, index) : undefined}
              style={{
                display: 'inline-flex',
                alignItems: 'center',
                borderRadius: 3,
                padding: '1px 6px',
                fontSize: 12,
                lineHeight: '20px',
                cursor: clickable ? 'pointer' : undefined,
                ...chipStyle,
              }}
            >
              {getTokenLabel(token)}
              <span
                role="button"
                aria-label="remove"
                onClick={(e) => {
                  // 阻止冒泡到外层 chip 的 onTokenClick（否则点"×"删除会连带误触发编辑抽屉）
                  e.stopPropagation();
                  removeToken(index);
                }}
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
