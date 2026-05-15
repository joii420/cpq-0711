import React, { useState, useEffect } from 'react';
import { evaluateExpression } from '../../utils/formulaEngine';
import { templateService } from '../../services/templateService';
import { useAuthStore } from '../../stores/authStore';
import type { ComponentDataItem, ComponentField, ComponentFormula } from './QuotationStep2';
import FieldTraceIcon from './components/FieldTraceIcon';
import './quotation.css';

/** Readonly product card for quotation detail page */

// 关键字段：显示 FieldTraceIcon 的字段名集合
const TRACE_FIELD_NAMES = new Set([
  'unit_price',
  'process_cost',
  'material_cost',
  'total_price',
  'element_actual_unit_price',
]);

/** 判断是否为需要追溯的关键字段（单价/费用/公式衍生字段） */
function isTraceField(field: ComponentField): boolean {
  const key = field.name || '';
  return (
    TRACE_FIELD_NAMES.has(key) ||
    field.is_amount === true ||
    field.is_subtotal === true ||
    field.field_type === 'FORMULA'
  );
}

interface ReadonlyProductCardProps {
  lineItem: any;  // QuotationDTO.LineItemDTO from backend
  index: number;
  /** 报价单 ID（从父组件传入，用于 FieldTraceIcon） */
  quotationId?: string;
  /** 报价单状态（用于 FieldTraceIcon isDraft 判断） */
  quotationStatus?: string;
}

function parseJson<T>(value: T | string | null | undefined, fallback: T): T {
  if (value == null) return fallback;
  if (typeof value === 'string') {
    try { return JSON.parse(value) as T; } catch { return fallback; }
  }
  return value;
}

function normalizeFieldType(raw: string): ComponentField['field_type'] {
  const t = (raw || '').toUpperCase();
  if (t === 'FORMULA') return 'FORMULA';
  if (t === 'FIXED_VALUE' || t === 'FIXED') return 'FIXED_VALUE';
  if (t === 'DATA_SOURCE') return 'DATA_SOURCE';
  // 与 BulkImportPartsDrawer / QuotationWizard 完全对齐——少一个就会让 BASIC_DATA 字段
  // 在明细页落到 input 默认分支显示空白。
  if (t === 'BASIC_DATA') return 'BASIC_DATA';
  if (t === 'INPUT_TEXT') return 'INPUT_TEXT';
  if (t === 'INPUT_NUMBER') return 'INPUT_NUMBER';
  return 'INPUT';
}

function computeFormula(
  comp: ComponentDataItem,
  formulaFieldName: string,
  row: Record<string, any>,
): number | null {
  if (!comp?.fields || !comp?.formulas) return null;

  // 1. Explicit binding via field.formula_name
  const field = comp.fields.find(f => f.name === formulaFieldName && f.field_type === 'FORMULA');
  const boundName = field?.formula_name;
  let formula = boundName ? comp.formulas.find(f => f.name === boundName) : undefined;

  // 2. Fallback: exact name match
  if (!formula) formula = comp.formulas.find(f => f.name === formulaFieldName);

  // 3. Fallback: positional
  if (!formula) {
    const formulaFields = comp.fields.filter(f => f.field_type === 'FORMULA');
    const idx = formulaFields.findIndex(f => f.name === formulaFieldName);
    if (idx >= 0 && idx < comp.formulas.length) formula = comp.formulas[idx];
  }
  if (!formula?.expression?.length) return null;

  const fieldValues: Record<string, number> = {};
  for (const f of comp.fields) {
    if (f.field_type !== 'FORMULA') {
      // FIXED_VALUE 字段如果当前行没存值（driver 展开行 / 旧报价单回读），
      // 用 field.content 兜底——和编辑态/单元格渲染保持一致，避免公式按 0 算。
      let raw: any = row[f.name];
      if ((raw === undefined || raw === null || raw === '')
          && f.field_type === 'FIXED_VALUE'
          && f.content != null && f.content !== '') {
        raw = f.content;
      }
      const val = parseFloat(raw);
      if (!isNaN(val)) fieldValues[f.name] = val;
    }
  }
  try {
    return evaluateExpression(formula.expression, fieldValues, {});
  } catch {
    return null;
  }
}

const formatCurrency = (val: number) =>
  `¥ ${(val || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;

/** 单元格值格式化: boolean → 是/否, 其他 → String(v) */
const formatCellValue = (v: any): string => {
  if (typeof v === 'boolean') return v ? '是' : '否';
  return String(v);
};

const ReadonlyProductCard: React.FC<ReadonlyProductCardProps> = ({
  lineItem,
  index,
  quotationId,
  quotationStatus,
}) => {
  const [activeTab, setActiveTab] = useState(0);
  const [components, setComponents] = useState<ComponentDataItem[]>([]);
  const [loading, setLoading] = useState(true);
  const { user } = useAuthStore();

  const attrValues: Record<string, any> = parseJson(lineItem.productAttributeValues, {});

  // 料号版本 Tag 显示规则 (新需求):
  // - 草稿态 (DRAFT): 不显示 (草稿走 QuotationStep2 编辑视图, 那里已显示并可切换)
  // - 已提交态 (非 DRAFT): 仅销售经理(SALES_MANAGER) + 系统管理员(SYSTEM_ADMIN) 可见, 不可修改
  // - 其他角色 (SALES_REP / PRICING_MANAGER 等普通用户): 不显示
  const canSeeVersionTag = quotationStatus !== 'DRAFT'
      && (user?.role === 'SALES_MANAGER' || user?.role === 'SYSTEM_ADMIN');

  // Enrich componentData with fields/formulas from template snapshot
  useEffect(() => {
    const enrich = async () => {
      const rawCompData: any[] = lineItem.componentData || [];
      if (rawCompData.length === 0 || !lineItem.templateId) {
        setComponents([]);
        setLoading(false);
        return;
      }
      // Check if already enriched
      if (rawCompData[0]?.fields?.length > 0) {
        setComponents(rawCompData.map((cd: any) => ({
          ...cd,
          rows: Array.isArray(cd.rows) ? cd.rows : parseJson(cd.rowData, [{}]),
        })));
        setLoading(false);
        return;
      }
      try {
        // 用 cached 版本: N 个 ReadonlyProductCard 共享 1 个 in-flight Promise → 1 次 HTTP
        const res = await templateService.getByIdCached(lineItem.templateId);
        const snapshot: any[] = parseJson(res.data.componentsSnapshot, []);

        const enriched: ComponentDataItem[] = rawCompData.map((saved: any) => {
          const sc = snapshot.find((s: any) => (s.componentId || s.component_id) === saved.componentId)
            || snapshot.find((s: any) => (s.tabName || s.tab_name) === saved.tabName);

          const fields: ComponentField[] = (sc?.fields || []).map((f: any) => ({
            name: f.name || f.key || '',
            field_type: normalizeFieldType(f.field_type || f.type || ''),
            content: f.content,
            is_amount: f.is_amount,
            is_subtotal: f.is_subtotal,
            formula_name: f.formula_name,
            // BASIC_DATA 字段需要 path 才能渲染值；之前漏掉导致明细页出现"未配置路径"
            basic_data_path: f.basic_data_path,
            label: f.label || f.name || '',
            key: f.name || f.key || '',
          }));
          const formulas: ComponentFormula[] = (sc?.formulas || []).map((fm: any) => ({
            name: fm.name || '',
            expression: Array.isArray(fm.expression) ? fm.expression : [],
          }));
          const rows = Array.isArray(saved.rows) && saved.rows.length > 0
            ? saved.rows
            : parseJson(saved.rowData, [{}]);

          // 从模板快照取 componentType / dataDriverPath；保证小计组件不会被当成普通 tab
          const componentType = saved.componentType
            || sc?.component_type
            || sc?.componentType
            || 'NORMAL';

          return {
            componentId: saved.componentId || sc?.componentId || '',
            componentCode: saved.componentCode || sc?.componentCode || '',
            componentType,
            tabName: saved.tabName || sc?.tabName || '',
            fields,
            formulas,
            rows,
            subtotal: saved.subtotal || 0,
          } as ComponentDataItem;
        });
        setComponents(enriched);
      } catch {
        setComponents([]);
      } finally {
        setLoading(false);
      }
    };
    enrich();
  }, [lineItem]);

  // 与编辑页 ProductCard 对齐：SUBTOTAL 组件不进 tab 列表，单独走底部"产品小计"展示
  const normalComponents = components.filter(c => (c as any)?.componentType !== 'SUBTOTAL');
  const activeComp = normalComponents[activeTab];

  // Compute subtotals
  const compSubtotals: Record<string, number> = {};
  for (const comp of components) {
    if (!comp.fields) continue;
    const stField = comp.fields.find(f => f.is_subtotal);
    const st = stField
      ? comp.rows.reduce((s, row) => s + (computeFormula(comp, stField.name, row) ?? 0), 0)
      : 0;
    compSubtotals[comp.tabName] = st;
    if (comp.componentCode) compSubtotals[comp.componentCode] = st;
  }
  const productSubtotal = Object.values(compSubtotals).reduce((a, b) => a + b, 0);

  return (
    <div className="qt-product-card">
      <div className="qt-card-header">
        <div className="qt-card-header-left">
          <span className="qt-product-name">
            {/* 与 QuotationStep2 编辑卡片对齐：客户视角优先（customerPartName）→ HF 名 → 快照 partNo → 产品 N */}
            {lineItem.customerPartName
              || attrValues['产品名称']
              || attrValues['名称']
              || lineItem.productName
              || lineItem.snapshot?.productPartNo
              || `产品 ${index + 1}`}
          </span>
          {(lineItem.customerProductNo || lineItem.productPartNo || lineItem.snapshot?.productPartNo) && (
            <span className="qt-sku-badge">
              {lineItem.customerProductNo
                ? `客户产品编号: ${lineItem.customerProductNo}`
                : `料号: ${lineItem.productPartNo || lineItem.snapshot?.productPartNo}`}
            </span>
          )}
          {lineItem.productPartNo && lineItem.customerProductNo && (
            // 同时存在客户产品编号与生产料号时，两者并列显示便于审阅人对照
            <span
              className="qt-sku-badge"
              style={{ background: '#e6f4ff', color: '#0958d9', border: '1px solid #91caff' }}
            >
              生产料号: {lineItem.productPartNo}
            </span>
          )}
          {/* 料号版本锁定 — 仅已提交报价单且角色为销售经理/系统管理员时显示, 不可修改 */}
          {canSeeVersionTag && lineItem.partVersionLocked != null && (
            <span
              className="qt-sku-badge"
              style={{ background: '#f6ffed', color: '#389e0d', border: '1px solid #b7eb8f' }}
              title="料号版本锁定 — 本行报价数据锁定版本 (不可修改)"
            >
              版本: v{lineItem.partVersionLocked}
            </span>
          )}
          {lineItem.snapshot?.productCategory && (
            <span className="qt-template-badge">{lineItem.snapshot.productCategory}</span>
          )}
        </div>
      </div>

      {/* Product attributes */}
      {Object.keys(attrValues).length > 0 && (
        <div className="qt-product-attrs">
          <div className="qt-attrs-title">产品属性</div>
          <div className="qt-attrs-grid">
            {Object.entries(attrValues).map(([k, v]) => (
              <div key={k} className="qt-attr-field">
                <label className="qt-attr-label">{k}</label>
                <span className="qt-attr-input" style={{ background: '#fafafa', border: '1px solid #e8e8e8', padding: '4px 8px', borderRadius: 4 }}>
                  {v != null ? formatCellValue(v) : '—'}
                </span>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Component Tabs — 排除 SUBTOTAL */}
      {loading ? (
        <div style={{ padding: 24, textAlign: 'center', color: '#999' }}>加载组件结构...</div>
      ) : normalComponents.length > 0 ? (
        <div className="qt-tab-section">
          <div className="qt-tab-header">
            {normalComponents.map((comp, ci) => (
              <button
                key={ci}
                className={`qt-tab-btn${activeTab === ci ? ' active' : ''}`}
                type="button"
                onClick={() => setActiveTab(ci)}
              >
                {comp.tabName}
              </button>
            ))}
          </div>

          {activeComp && activeComp.fields && (
            <div>
              <table className="qt-cost-table">
                <thead>
                  <tr>
                    {activeComp.fields.map(field => (
                      <th key={field.name}>{field.label || field.name}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {activeComp.rows.map((rawRow, ri) => {
                    // FIXED_VALUE 默认值回填（与 QuotationStep2 一致）：
                    // 报价单/核价单切换或 driver 展开生成的行，row[key] 可能为空，
                    // 此时显示 field.content 而不是"—"，与编辑态一致。
                    let row: Record<string, any> = rawRow;
                    let cloned: Record<string, any> | null = null;
                    for (const f of activeComp.fields) {
                      if (f.field_type !== 'FIXED_VALUE') continue;
                      if (f.content == null || f.content === '') continue;
                      const k = f.name || '';
                      if (rawRow[k] !== undefined && rawRow[k] !== null && rawRow[k] !== '') continue;
                      if (!cloned) cloned = { ...rawRow };
                      cloned[k] = f.content;
                    }
                    if (cloned) row = cloned;
                    return (
                    <tr key={ri}>
                      {activeComp.fields.map((field, fi) => {
                        const key = field.name || '';
                        const showTrace = !!(quotationId && isTraceField(field));
                        const isDraft = !quotationStatus || quotationStatus === 'DRAFT';
                        // lineItemIndex derived from `index` prop (product card index)
                        const compIndex = components.indexOf(activeComp);
                        const fieldPath = `lineItems[${index}].componentData[${compIndex}].rowData.${key}`;
                        return (
                          <td
                            key={key}
                            className={field.field_type === 'FORMULA' ? 'qt-formula-cell' : ''}
                          >
                            {field.field_type === 'FORMULA' ? (
                              <span className="qt-formula-cell-value" style={{ display: 'inline-flex', alignItems: 'center', gap: 2 }}>
                                {(() => {
                                  const val = computeFormula(activeComp, field.name, row);
                                  return val != null ? val : '—';
                                })()}
                                {showTrace && (
                                  <FieldTraceIcon
                                    quotationId={quotationId!}
                                    fieldPath={fieldPath}
                                    isDraft={isDraft}
                                  />
                                )}
                              </span>
                            ) : (
                              <span style={showTrace ? { display: 'inline-flex', alignItems: 'center', gap: 2 } : undefined}>
                                {row[key] != null ? formatCellValue(row[key]) : '—'}
                                {showTrace && (
                                  <FieldTraceIcon
                                    quotationId={quotationId!}
                                    fieldPath={fieldPath}
                                    isDraft={isDraft}
                                  />
                                )}
                              </span>
                            )}
                          </td>
                        );
                      })}
                    </tr>
                    );
                  })}
                </tbody>
                {activeComp.fields.some(f => f.is_subtotal) && (
                  <tfoot>
                    <tr className="qt-subtotal-row">
                      {activeComp.fields.map((field, fi) => {
                        if (field.is_subtotal) {
                          return (
                            <td key={fi} className="qt-subtotal-cell">
                              {formatCurrency(compSubtotals[activeComp.tabName] || 0)}
                            </td>
                          );
                        }
                        return fi === 0
                          ? <td key={fi} className="qt-subtotal-label-cell">小计</td>
                          : <td key={fi} />;
                      })}
                    </tr>
                  </tfoot>
                )}
              </table>
            </div>
          )}
        </div>
      ) : (
        <div className="qt-no-component-data">��无组件数据</div>
      )}

      <div className="qt-subtotal-bar">
        <span className="qt-subtotal-label">产品小计</span>
        <span className="qt-subtotal-value">
          {formatCurrency(productSubtotal || lineItem.subtotal || 0)}
        </span>
      </div>
    </div>
  );
};

export default ReadonlyProductCard;
