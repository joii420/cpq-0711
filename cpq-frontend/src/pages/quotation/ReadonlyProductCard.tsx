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
  if (t === 'LIST_FORMULA') return 'LIST_FORMULA';  // V203/Phase B
  return 'INPUT';
}

function computeFormula(
  comp: ComponentDataItem,
  formulaFieldName: string,
  row: Record<string, any>,
): number | null {
  if (!comp?.fields || !comp?.formulas) return null;

  // 与 QuotationStep2.resolveFormula 同源 (AP-37 协议: 详情页/编辑页 resolveFormula 必须同源)
  const field = comp.fields.find(f => f.name === formulaFieldName && f.field_type === 'FORMULA');
  const boundName = field?.formula_name;

  // 0. (2026-05-20) field.formula_name 显式绑定 — 找不到不 fallback (配置漂移时显示 "—" 避免误导)
  let formula: typeof comp.formulas[number] | undefined;
  if (boundName) {
    formula = comp.formulas.find(f => f.name === boundName);
    if (!formula) return null;  // 显式绑定但漂移 → 不 fallback
  }

  // 1. Template-level binding (formulaAssignments)
  if (!formula && (comp as any).formulaAssignments && field) {
    const fieldIndex = comp.fields.indexOf(field);
    const assignedName = (comp as any).formulaAssignments[String(fieldIndex)];
    if (assignedName) formula = comp.formulas.find(f => f.name === assignedName);
  }

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

/** 单元格值格式化 — V197 同 QuotationStep2.formatPathValue 同款逻辑, 支持 JSONB 包装对象 */
const formatCellValue = (v: any): string => {
  if (v == null || v === '') return '—';
  if (typeof v === 'boolean') return v ? '是' : '否';
  if (typeof v === 'number' || typeof v === 'string') return String(v);
  // V197: PG JDBC jsonb 列读成 PGobject {type:'jsonb', value:'<json>'}, 单 cell 完整展开
  if (typeof v === 'object') {
    if (v.type === 'jsonb' && typeof v.value === 'string') {
      try {
        const parsed = JSON.parse(v.value);
        if (Array.isArray(parsed)) {
          if (parsed.length === 0) return '—';
          return parsed.map(it => formatCellValue(it)).filter(s => s && s !== '—').join(', ') || '—';
        }
        if (parsed && typeof parsed === 'object') {
          const keys = Object.keys(parsed);
          if (keys.length === 0) return '—';
          return keys.map(k => {
            const sub = formatCellValue(parsed[k]);
            return sub && sub !== '—' ? `${k}=${sub}` : null;
          }).filter(Boolean).join(', ') || '—';
        }
        return String(parsed);
      } catch { return v.value; }
    }
    if (Array.isArray(v)) {
      if (v.length === 0) return '—';
      return v.map(it => formatCellValue(it)).filter(s => s && s !== '—').join(', ') || '—';
    }
    // 普通 object 取首个非空字段
    for (const k of Object.keys(v)) {
      if (v[k] != null && v[k] !== '') return formatCellValue(v[k]);
    }
    return '—';
  }
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

  // Enrich componentData with fields/formulas from template snapshot.
  // 2026-05-19 AP-37 续: 按 snapshot 遍历 (而非 saved), 用 Map<cid, Queue<saved>> 配对,
  //   保证同 cid 多实例 (典型: 标准 Tab + 选配-* Tab 同 componentId) 不会被反查塌缩.
  //   tabName / componentType / dataDriverPath / componentId 全部 snapshot 优先 (snapshot
  //   是 publish 定格的结构权威), saved 只贡献行/小计. 与 QuotationWizard.enrichComponentData
  //   保持完全一致——任何一方走老逻辑都会让详情/编辑页 Tab 列表对不上.
  useEffect(() => {
    const enrich = async () => {
      const rawCompData: any[] = lineItem.componentData || [];
      if (!lineItem.templateId) {
        setComponents([]);
        setLoading(false);
        return;
      }
      try {
        const res = await templateService.getByIdCached(lineItem.templateId);
        const snapshot: any[] = parseJson(res.data.componentsSnapshot, []);

        // saved 预处理: 把 rowData 解析成 rows
        const withRows = rawCompData.map((s: any) => ({
          ...s,
          rows: Array.isArray(s.rows) ? s.rows : parseJson(s.rowData, [{}]),
        }));

        const savedQueueByCid: Map<string, any[]> = new Map();
        const savedByTab: Record<string, any> = {};
        for (const s of withRows) {
          if (s.componentId) {
            if (!savedQueueByCid.has(s.componentId)) savedQueueByCid.set(s.componentId, []);
            savedQueueByCid.get(s.componentId)!.push(s);
          }
          if (s.tabName) savedByTab[s.tabName] = s;
        }

        const enriched: ComponentDataItem[] = snapshot.map((snapshotComp: any) => {
          const snapId = snapshotComp.componentId || snapshotComp.component_id || '';
          const snapTab = snapshotComp.tabName || snapshotComp.tab_name || '';
          let saved: any = {};
          const queue = savedQueueByCid.get(snapId);
          if (queue && queue.length > 0) {
            let idx = queue.findIndex(s => (s.tabName || '') === snapTab);
            if (idx < 0) idx = 0;
            saved = queue.splice(idx, 1)[0] || {};
          } else if (savedByTab[snapTab]) {
            saved = savedByTab[snapTab];
          }

          const fields: ComponentField[] = (snapshotComp.fields || []).map((f: any) => ({
            name: f.name || f.key || '',
            field_type: normalizeFieldType(f.field_type || f.type || ''),
            content: f.content,
            is_amount: f.is_amount,
            is_subtotal: f.is_subtotal,
            formula_name: f.formula_name,
            basic_data_path: f.basic_data_path,
            list_formula_config: f.list_formula_config,
            label: f.label || f.name || '',
            key: f.name || f.key || '',
          }));
          const formulas: ComponentFormula[] = (snapshotComp.formulas || []).map((fm: any) => ({
            name: fm.name || '',
            expression: Array.isArray(fm.expression) ? fm.expression : [],
          }));
          const rows = Array.isArray(saved.rows) && saved.rows.length > 0
            ? saved.rows
            : [];

          const componentType = snapshotComp.component_type
            || snapshotComp.componentType
            || saved.componentType
            || 'NORMAL';
          const dataDriverPath = snapshotComp.data_driver_path
            || snapshotComp.dataDriverPath
            || saved.dataDriverPath
            || undefined;

          return {
            componentId: snapshotComp.componentId || saved.componentId || '',
            componentCode: snapshotComp.componentCode || saved.componentCode || '',
            componentType,
            tabName: snapshotComp.tabName || snapshotComp.tab_name || saved.tabName || '',
            fields,
            formulas,
            rows,
            subtotal: saved.subtotal || 0,
            dataDriverPath,
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

  // 2026-05-19 用户决议 (方案 A): 严格按模板, 不再隐藏空数据 Tab.
  //   模板 publish 时配置的全部 NORMAL 组件都展示, SUBTOTAL 仍单独走"产品小计".
  //   空 Tab 内部表格显示"暂无数据"占位 (走 Ant Design Table 默认 emptyText).
  //   这一改动同时修复 ReadonlyProductCard 与 QuotationStep2 Tab 数量不一致的问题.
  const normalComponents = components
    .filter(c => (c as any)?.componentType !== 'SUBTOTAL');
  const activeComp = normalComponents[activeTab];

  // activeTab 越界钳位
  useEffect(() => {
    if (normalComponents.length > 0 && activeTab >= normalComponents.length) {
      setActiveTab(0);
    }
  }, [normalComponents.length, activeTab]);

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
                  {/* 2026-05-19 (方案 A): 模板配了组件但当前料号未匹配数据 → 显示 "暂无数据" 占位行, 列数 = fields.length */}
                  {activeComp.rows.length === 0 && (
                    <tr>
                      <td
                        colSpan={activeComp.fields.length || 1}
                        style={{ textAlign: 'center', color: '#999', padding: '16px 0' }}
                      >
                        暂无数据
                      </td>
                    </tr>
                  )}
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
