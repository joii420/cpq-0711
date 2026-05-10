import React, { useEffect, useState } from 'react';
import type { FieldItem } from './types';
import { FIELD_TYPE_OPTIONS } from './types';
import { globalVariableService, type GlobalVariableDefinition } from '../../services/globalVariableService';
import './styles.css';

export interface GlobalVariablePick {
  def: GlobalVariableDefinition;
  /** 静态 key (列名→字面值) — 选择具体某行时用 */
  key_values?: Record<string, any>;
  /** 动态 key (列名→同行字段名) — 选择"按当前行字段查表"时用 */
  key_field_refs?: Record<string, string>;
  label: string;
}

interface FieldPanelProps {
  fields: FieldItem[];
  otherComponentSubtotals: { name: string; componentCode: string; componentName: string }[];
  onFieldClick: (fieldName: string) => void;
  onSubtotalClick: (subtotal: { name: string; componentCode: string }) => void;
  onQuotationFieldClick?: (field: { value: string; label: string }) => void;
  /** V104: 选中"全局变量+key" 后回调 */
  onGlobalVariableClick?: (pick: GlobalVariablePick) => void;
  hasSelection?: boolean;
}

const FieldPanel: React.FC<FieldPanelProps> = ({
  fields,
  otherComponentSubtotals,
  onFieldClick,
  onSubtotalClick,
  onQuotationFieldClick,
  onGlobalVariableClick,
  hasSelection,
}) => {
  const [activeTab, setActiveTab] = useState<'fields' | 'subtotals' | 'globals'>('fields');
  const [globalDefs, setGlobalDefs] = useState<GlobalVariableDefinition[]>([]);
  const [expandedCode, setExpandedCode] = useState<string | null>(null);
  const [keyOptions, setKeyOptions] = useState<Record<string, Array<{ key_values: Record<string, any>; label: string }>>>({});

  const fieldTypeLabel = (type: string) =>
    FIELD_TYPE_OPTIONS.find((o) => o.value === type)?.label ?? type;

  // 全局变量 tab 首次激活时拉清单 (惰性加载, 不影响其他场景)
  useEffect(() => {
    if (activeTab !== 'globals' || globalDefs.length > 0) return;
    globalVariableService.list()
      .then((res) => {
        const list: GlobalVariableDefinition[] = (res?.data?.data || res?.data || []) as any;
        setGlobalDefs(Array.isArray(list) ? list : []);
      })
      .catch(() => setGlobalDefs([]));
  }, [activeTab, globalDefs.length]);

  // 展开某变量时拉它的可选 key 列表
  const handleExpandGlobal = async (def: GlobalVariableDefinition) => {
    if (expandedCode === def.code) {
      setExpandedCode(null);
      return;
    }
    setExpandedCode(def.code);
    if (keyOptions[def.code]) return;
    try {
      const res = await globalVariableService.listKeys(def.code, 500);
      const opts: any[] = (res?.data?.data || res?.data || []) as any;
      setKeyOptions((prev) => ({ ...prev, [def.code]: Array.isArray(opts) ? opts : [] }));
    } catch {
      setKeyOptions((prev) => ({ ...prev, [def.code]: [] }));
    }
  };

  const handlePickStatic = (def: GlobalVariableDefinition, opt: { key_values: Record<string, any>; label: string }) => {
    onGlobalVariableClick?.({
      def,
      key_values: opt.key_values,
      label: `${def.name}[${opt.label}]`,
    });
  };

  const handlePickDynamic = (def: GlobalVariableDefinition, fieldName: string) => {
    // 单键场景下让用户选一个本行字段当 key 值; 复合键暂走静态选择
    if (def.keyColumns.length !== 1) return;
    const col = def.keyColumns[0];
    onGlobalVariableClick?.({
      def,
      key_field_refs: { [col]: fieldName },
      label: `${def.name}[${fieldName}]`,
    });
  };

  return (
    <div className="cm-right-panel">
      {/* Tab headers */}
      <div className="cm-right-tabs">
        <div
          className={`cm-right-tab${activeTab === 'fields' ? ' active' : ''}`}
          onClick={() => setActiveTab('fields')}
        >
          字段列表
        </div>
        <div
          className={`cm-right-tab${activeTab === 'subtotals' ? ' active' : ''}`}
          onClick={() => setActiveTab('subtotals')}
        >
          其他数据源
        </div>
        <div
          className={`cm-right-tab${activeTab === 'globals' ? ' active' : ''}`}
          onClick={() => setActiveTab('globals')}
        >
          全局变量
        </div>
      </div>

      {/* Tab content */}
      <div className="cm-right-content">
        {activeTab === 'fields' && (
          <>
            {!hasSelection || fields.length === 0 ? (
              <div className="cm-right-empty">
                {hasSelection ? '暂无字段，请在左侧添加' : '选择组件后可查看字段'}
              </div>
            ) : (
              fields.map((f) => {
                const canRef = f.field_type === 'FORMULA'
                  || f.field_type === 'INPUT_NUMBER'
                  || f.field_type === 'DATA_SOURCE'
                  || f.field_type === 'BASIC_DATA';
                return (
                <div
                  key={f.key}
                  className="cm-field-card"
                  onClick={() => canRef && f.name && onFieldClick(f.name)}
                  style={{ cursor: canRef && f.name ? 'grab' : 'default', opacity: canRef ? 1 : 0.6 }}
                >
                  <div className="cm-field-card-info">
                    <div className="cm-field-card-name">{f.name || '(未命名)'}</div>
                    <div className="cm-field-card-type">{fieldTypeLabel(f.field_type)}</div>
                  </div>
                  {f.global_variable_code && (
                    <span style={{
                      fontSize: 10.5,
                      padding: '1px 6px',
                      borderRadius: 10,
                      background: '#fff7e6',
                      color: '#d46b08',
                      border: '1px solid #ffd591',
                      flexShrink: 0,
                    }}
                    title={`取自全局变量 ${f.global_variable_code}`}>
                      🌐 {f.global_variable_code}
                    </span>
                  )}
                  {f.is_subtotal && (
                    <span style={{
                      fontSize: 10.5,
                      padding: '1px 6px',
                      borderRadius: 10,
                      background: '#fff8e6',
                      color: '#e6a23c',
                      border: '1px solid #ffe4a0',
                      flexShrink: 0,
                    }}>
                      小计
                    </span>
                  )}
                  {f.is_amount && (
                    <span style={{
                      fontSize: 10.5,
                      padding: '1px 6px',
                      borderRadius: 10,
                      background: '#fef0f0',
                      color: '#f56c6c',
                      border: '1px solid #ffcfcf',
                      flexShrink: 0,
                    }}>
                      金额
                    </span>
                  )}
                </div>
                );
              })
            )}
          </>
        )}

        {activeTab === 'subtotals' && (
          <>
            {/* Quotation fields */}
            <div className="cm-section-divider">报价单字段</div>
            {[{ value: 'tax_rate', label: '报价单·税率(%)' }].map((qf) => (
              <div
                key={qf.value}
                className="cm-subtotal-card"
                onClick={() => onQuotationFieldClick?.(qf)}
                style={{ cursor: 'pointer' }}
              >
                <span style={{ fontSize: 14, color: '#cf1322' }}>%</span>
                <div className="cm-subtotal-card-info">
                  <div className="cm-subtotal-card-name">{qf.label}</div>
                  <div className="cm-subtotal-card-comp">来自当前报价单</div>
                </div>
              </div>
            ))}

            {/* Other component subtotals */}
            {otherComponentSubtotals.length > 0 && (
              <>
                <div className="cm-section-divider">其他组件小计</div>
                {otherComponentSubtotals.map((item, i) => (
                  <div
                    key={i}
                    className="cm-subtotal-card"
                    onClick={() =>
                      onSubtotalClick({ name: item.name, componentCode: item.componentCode })
                    }
                  >
                    <span style={{ fontSize: 14 }}>Σ</span>
                    <div className="cm-subtotal-card-info">
                      <div className="cm-subtotal-card-name">{item.name}</div>
                      <div className="cm-subtotal-card-comp">{item.componentName}</div>
                    </div>
                  </div>
                ))}
              </>
            )}

            {otherComponentSubtotals.length === 0 && (
              <div style={{ color: '#ccc', fontSize: 12, textAlign: 'center', padding: '8px 0' }}>
                暂无跨组件小计
              </div>
            )}
          </>
        )}

        {activeTab === 'globals' && (
          <>
            {globalDefs.length === 0 ? (
              <div className="cm-right-empty">
                暂无可用全局变量
              </div>
            ) : (
              globalDefs.map((def) => {
                const expanded = expandedCode === def.code;
                const opts = keyOptions[def.code] || [];
                const dynKeyCol = def.keyColumns.length === 1 ? def.keyColumns[0] : null;
                return (
                  <div key={def.code} style={{ marginBottom: 6 }}>
                    <div
                      className="cm-subtotal-card"
                      onClick={() => handleExpandGlobal(def)}
                      style={{ cursor: 'pointer', borderColor: expanded ? '#ffd591' : undefined }}
                    >
                      <span style={{ fontSize: 14, color: '#d46b08' }}>🌐</span>
                      <div className="cm-subtotal-card-info">
                        <div className="cm-subtotal-card-name">{def.name}</div>
                        <div className="cm-subtotal-card-comp">
                          {def.unit ? `单位 ${def.unit} · ` : ''}{def.keyColumns.join(' + ')}
                        </div>
                      </div>
                      <span style={{ fontSize: 11, color: '#999' }}>{expanded ? '▼' : '▶'}</span>
                    </div>
                    {expanded && (
                      <div style={{ marginLeft: 8, marginTop: 4, paddingLeft: 8, borderLeft: '2px solid #ffe7ba' }}>
                        {/* 动态 key: 只在单键 + 当前组件存在可引字段时显示 */}
                        {dynKeyCol && fields.length > 0 && (
                          <>
                            <div className="cm-section-divider" style={{ fontSize: 11 }}>
                              按行字段查表 (动态 key: {dynKeyCol})
                            </div>
                            {fields
                              .filter((f) =>
                                f.name &&
                                (f.field_type === 'BASIC_DATA' ||
                                 f.field_type === 'INPUT_TEXT' ||
                                 f.field_type === 'FIXED_VALUE'))
                              .map((f) => (
                                <div
                                  key={`dyn-${f.key}`}
                                  className="cm-subtotal-card"
                                  onClick={() => handlePickDynamic(def, f.name)}
                                  style={{ cursor: 'pointer', marginBottom: 2 }}
                                >
                                  <span style={{ fontSize: 12 }}>↳</span>
                                  <div className="cm-subtotal-card-info">
                                    <div className="cm-subtotal-card-name">{f.name}</div>
                                    <div className="cm-subtotal-card-comp">取本行 {f.name} 的值作为 key</div>
                                  </div>
                                </div>
                              ))}
                          </>
                        )}

                        {/* 静态 key: 列出可选 key 行 */}
                        <div className="cm-section-divider" style={{ fontSize: 11 }}>
                          静态选择
                        </div>
                        {opts.length === 0 ? (
                          <div style={{ color: '#ccc', fontSize: 11, padding: '4px 6px' }}>暂无可选 key</div>
                        ) : (
                          opts.slice(0, 100).map((opt, i) => (
                            <div
                              key={`static-${i}`}
                              className="cm-subtotal-card"
                              onClick={() => handlePickStatic(def, opt)}
                              style={{ cursor: 'pointer', marginBottom: 2 }}
                            >
                              <span style={{ fontSize: 12 }}>=</span>
                              <div className="cm-subtotal-card-info">
                                <div className="cm-subtotal-card-name">{opt.label}</div>
                              </div>
                            </div>
                          ))
                        )}
                      </div>
                    )}
                  </div>
                );
              })
            )}
          </>
        )}
      </div>
    </div>
  );
};

export default FieldPanel;
