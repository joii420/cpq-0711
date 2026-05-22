import React, { useCallback, useEffect, useState } from 'react';
import type { LineItem, ComponentDataItem, ComponentField, ComponentFormula } from './QuotationStep2';
import { productService } from '../../services/productService';
import { processService } from '../../services/processService';
import { bindingService } from '../../services/bindingService';
import { templateService } from '../../services/templateService';
import './quotation.css';

export interface AddProductModalProps {
  open: boolean;
  onCancel: () => void;
  onConfirm: (lineItem: LineItem) => void;
}

// ─── helpers ────────────────────────────────────────────────────────────────

function parseJson<T>(value: T | string | null | undefined, fallback: T): T {
  if (value == null) return fallback;
  if (typeof value === 'string') {
    try {
      return JSON.parse(value) as T;
    } catch {
      return fallback;
    }
  }
  return value;
}

function extractArray<T>(data: any): T[] {
  if (Array.isArray(data)) return data as T[];
  if (data?.content && Array.isArray(data.content)) return data.content as T[];
  return [];
}

function buildEmptyRow(fields: ComponentField[]): Record<string, any> {
  const row: Record<string, any> = { row_index: 0 };
  for (const f of fields) {
    if (f.field_type === 'FIXED_VALUE') {
      row[f.name] = f.content ?? '';
    } else if (f.field_type === 'FORMULA') {
      // Formula results are computed on-the-fly — don't pre-store
    } else if (f.field_type === 'DATA_SOURCE') {
      row[f.name] = null; // Will be filled by datasource query
    } else {
      // INPUT
      row[f.name] = '';
    }
  }
  return row;
}

// ─── Step-indicator ─────────────────────────────────────────────────────────

const STEP_LABELS = ['选择产品', '选择工序', '选择模板'];

const StepIndicator: React.FC<{ current: number }> = ({ current }) => (
  <div className="qt-step-indicator">
    {STEP_LABELS.map((label, i) => {
      const isDone = i < current;
      const isActive = i === current;
      return (
        <React.Fragment key={i}>
          {i > 0 && (
            <div className={`qt-step-connector${isDone ? ' done' : ''}`} />
          )}
          <div className="qt-step-item">
            <div className={`qt-step-dot${isActive ? ' active' : isDone ? ' done' : ''}`}>
              {isDone ? '✓' : i + 1}
            </div>
            <span className={`qt-step-label${isActive ? ' active' : isDone ? ' done' : ''}`}>
              {label}
            </span>
          </div>
        </React.Fragment>
      );
    })}
  </div>
);

// ─── Step 1: Select Product ──────────────────────────────────────────────────

const CATEGORIES = [
  { label: '全部', value: '' },
  { label: '标准件', value: '标准件' },
  { label: '定制件', value: '定制件' },
  { label: '原材料', value: '原材料' },
];

interface Step1Props {
  selectedProduct: any | null;
  onSelect: (product: any) => void;
}

const Step1: React.FC<Step1Props> = ({ selectedProduct, onSelect }) => {
  const [category, setCategory] = useState('');
  const [keyword, setKeyword] = useState('');
  const [products, setProducts] = useState<any[]>([]);
  const [loading, setLoading] = useState(false);

  const loadProducts = useCallback(async (cat: string, kw: string) => {
    setLoading(true);
    try {
      const params: any = { size: 50 };
      if (cat) params.category = cat;
      if (kw.trim()) params.keyword = kw.trim();
      const res = await productService.list(params);
      setProducts(extractArray(res.data));
    } catch {
      setProducts([]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadProducts(category, keyword);
  }, [category, keyword, loadProducts]);

  return (
    <div className="qt-step-layout">
      {/* Category sidebar */}
      <div className="qt-category-sidebar">
        {CATEGORIES.map(c => (
          <button
            key={c.value}
            type="button"
            className={`qt-category-btn${category === c.value ? ' active' : ''}`}
            onClick={() => setCategory(c.value)}
          >
            {c.label}
          </button>
        ))}
      </div>

      {/* Product grid */}
      <div className="qt-step-main">
        <input
          className="qt-search-bar"
          type="text"
          placeholder="搜索产品名称或料号…"
          value={keyword}
          onChange={e => setKeyword(e.target.value)}
        />

        {loading ? (
          <div className="qt-modal-loading">
            <div className="qt-spinner" />
            加载中…
          </div>
        ) : products.length === 0 ? (
          <div className="qt-no-templates">
            <p style={{ fontSize: 32 }}>📦</p>
            <p>暂无匹配的产品</p>
          </div>
        ) : (
          <div className="qt-product-grid">
            {products.map(p => (
              <div
                key={p.id}
                className={`qt-product-option${selectedProduct?.id === p.id ? ' selected' : ''}`}
                onClick={() => onSelect(p)}
              >
                <div className="qt-product-option-name">{p.name || '—'}</div>
                {p.partNo && <div className="qt-product-option-sku">料号: {p.partNo}</div>}
                {p.category && (
                  <span className="qt-category-tag">{p.category}</span>
                )}
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
};

// ─── Step 2: Select Processes ────────────────────────────────────────────────

interface Step2Props {
  productId: string;
  selectedProcessIds: string[];
  onToggle: (id: string) => void;
}

const Step2: React.FC<Step2Props> = ({ productId, selectedProcessIds, onToggle }) => {
  const [boundProcessIds, setBoundProcessIds] = useState<string[]>([]);
  const [allProcesses, setAllProcesses] = useState<any[]>([]);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    const fetch = async () => {
      setLoading(true);
      try {
        const [boundRes, allRes] = await Promise.all([
          processService.getProductProcesses(productId),
          processService.listAll(),
        ]);

        // Extract bound process IDs — may come as array of IDs or array of objects
        const rawBound: any[] = extractArray(boundRes.data);
        const ids = rawBound.map((item: any) => {
          if (typeof item === 'string') return item;
          return item.processId || item.id || '';
        }).filter(Boolean);
        setBoundProcessIds(ids);

        setAllProcesses(extractArray(allRes.data));
      } catch {
        setBoundProcessIds([]);
        setAllProcesses([]);
      } finally {
        setLoading(false);
      }
    };
    fetch();
  }, [productId]);

  if (loading) {
    return (
      <div className="qt-modal-loading">
        <div className="qt-spinner" />
        加载工序数据…
      </div>
    );
  }

  // Filter to bound processes only
  const boundProcesses = allProcesses.filter(p => boundProcessIds.includes(p.id));

  if (boundProcesses.length === 0) {
    return (
      <div className="qt-skip-notice">
        <p style={{ fontSize: 32 }}>⚙️</p>
        <p>该产品未配置任何工序</p>
        <p style={{ fontSize: 13 }}>可直接跳过此步骤，继续选择模板</p>
      </div>
    );
  }

  // Group by category
  const grouped: Record<string, any[]> = {};
  for (const p of boundProcesses) {
    const cat = p.category || '其他';
    if (!grouped[cat]) grouped[cat] = [];
    grouped[cat].push(p);
  }

  return (
    <div>
      {Object.entries(grouped).map(([cat, procs]) => (
        <div key={cat} className="qt-process-group">
          <div className="qt-process-group-title">{cat}</div>
          <div className="qt-process-grid">
            {procs.map(proc => {
              const isSelected = selectedProcessIds.includes(proc.id);
              return (
                <div
                  key={proc.id}
                  className={`qt-process-option${isSelected ? ' selected' : ''}`}
                  onClick={() => onToggle(proc.id)}
                >
                  <div className="qt-process-checkbox">
                    {isSelected && '✓'}
                  </div>
                  <div className="qt-process-info">
                    <div className="qt-process-name">{proc.name || proc.processName || '—'}</div>
                    {proc.description && (
                      <div className="qt-process-desc">{proc.description}</div>
                    )}
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      ))}
    </div>
  );
};

// ─── Step 3: Select Template ─────────────────────────────────────────────────

const GRADIENT_ICONS = [
  { bg: 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)', icon: '📋' },
  { bg: 'linear-gradient(135deg, #f6d365 0%, #fda085 100%)', icon: '📄' },
  { bg: 'linear-gradient(135deg, #84fab0 0%, #8fd3f4 100%)', icon: '📝' },
  { bg: 'linear-gradient(135deg, #a18cd1 0%, #fbc2eb 100%)', icon: '🗂' },
];

interface Step3Props {
  productId: string;
  processIds: string[];
  selectedTemplateId: string | null;
  onSelect: (template: any) => void;
}

const Step3: React.FC<Step3Props> = ({ productId, processIds, selectedTemplateId, onSelect }) => {
  const [templates, setTemplates] = useState<any[]>([]);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    const fetch = async () => {
      setLoading(true);
      try {
        const res = await bindingService.matchTemplates(productId, processIds);
        const bindings: any[] = extractArray(res.data);

        if (bindings.length === 0) {
          setTemplates([]);
          return;
        }

        // Load template details for each binding
        const details = await Promise.all(
          bindings.map(async (b: any) => {
            const tId = b.templateId || b.template_id || b.id;
            if (!tId) return null;
            try {
              const tRes = await templateService.getById(tId);
              return tRes.data || null;
            } catch {
              return null;
            }
          })
        );

        setTemplates(details.filter(Boolean));
      } catch {
        setTemplates([]);
      } finally {
        setLoading(false);
      }
    };
    fetch();
  }, [productId, processIds]);

  if (loading) {
    return (
      <div className="qt-modal-loading">
        <div className="qt-spinner" />
        加载模板数据…
      </div>
    );
  }

  if (templates.length === 0) {
    return (
      <div className="qt-no-templates">
        <p style={{ fontSize: 32 }}>📭</p>
        <p>当前工序组合暂未配置报价模板</p>
        <p style={{ fontSize: 13, color: '#a0aec0' }}>请联系管理员配置对应的模板绑定</p>
      </div>
    );
  }

  return (
    <div className="qt-template-grid">
      {templates.map((tmpl, idx) => {
        const iconStyle = GRADIENT_ICONS[idx % GRADIENT_ICONS.length];
        const isSelected = selectedTemplateId === tmpl.id;
        return (
          <div
            key={tmpl.id}
            className={`qt-template-option${isSelected ? ' selected' : ''}`}
            onClick={() => onSelect(tmpl)}
          >
            <div
              className="qt-template-icon"
              style={{ background: iconStyle.bg }}
            >
              {iconStyle.icon}
            </div>
            <div className="qt-template-info">
              <div className="qt-template-name">{tmpl.name || '未命名模板'}</div>
              {tmpl.version && (
                <div className="qt-template-version">版本: {tmpl.version}</div>
              )}
              {tmpl.description && (
                <div className="qt-template-desc">{tmpl.description}</div>
              )}
            </div>
          </div>
        );
      })}
    </div>
  );
};

// ─── Main Component ──────────────────────────────────────────────────────────

const AddProductModal: React.FC<AddProductModalProps> = ({ open, onCancel, onConfirm }) => {
  const [step, setStep] = useState(0);
  const [selectedProduct, setSelectedProduct] = useState<any | null>(null);
  const [selectedProcessIds, setSelectedProcessIds] = useState<string[]>([]);
  const [selectedTemplate, setSelectedTemplate] = useState<any | null>(null);
  const [confirming, setConfirming] = useState(false);

  // Reset state when modal opens
  useEffect(() => {
    if (open) {
      setStep(0);
      setSelectedProduct(null);
      setSelectedProcessIds([]);
      setSelectedTemplate(null);
      setConfirming(false);
    }
  }, [open]);

  const toggleProcess = (id: string) => {
    setSelectedProcessIds(prev =>
      prev.includes(id) ? prev.filter(x => x !== id) : [...prev, id]
    );
  };

  const handleNext = () => {
    if (step < 2) setStep(s => s + 1);
  };

  const handlePrev = () => {
    if (step > 0) setStep(s => s - 1);
  };

  const handleConfirm = async () => {
    if (!selectedProduct || !selectedTemplate) return;
    setConfirming(true);
    try {
      // Load full template detail
      const res = await templateService.getById(selectedTemplate.id);
      const tmpl = res.data;

      // Parse componentsSnapshot (may be string or array)
      const componentsSnapshot: any[] = parseJson(tmpl.componentsSnapshot, []);

      // Parse productAttributes (may be string or array)
      const productAttrs: any[] = parseJson(tmpl.productAttributes, []);
      const productAttributes: LineItem['productAttributes'] = productAttrs.map((attr: any) => ({
        name: attr.name || attr.key || attr.fieldKey || '',
        field_type: attr.field_type || attr.fieldType || 'TEXT',
        required: attr.required ?? false,
        default_value: attr.default_value ?? attr.defaultValue,
        source: attr.source,
      }));

      // Build initial productAttributeValues from productAttributes
      // Auto-fill product built-in attributes from selected product data
      const productAttributeValues: Record<string, any> = {};
      for (const attr of productAttributes) {
        if (!attr.name) continue;
        const source = (attr as any).source;
        if (source === 'PRODUCT_SPEC') {
          productAttributeValues[attr.name] = selectedProduct.specification || '';
        } else if (source === 'PRODUCT_CATEGORY') {
          const categoryMap: Record<string, string> = { STANDARD: '标准件', CUSTOM: '定制件', RAW_MATERIAL: '原材料' };
          productAttributeValues[attr.name] = categoryMap[selectedProduct.category] || selectedProduct.category || '';
        } else if (source === 'PRODUCT_TAGS') {
          const tags = Array.isArray(selectedProduct.tags) ? selectedProduct.tags : [];
          productAttributeValues[attr.name] = tags.join(', ');
        } else if (source === 'PRODUCT_DRAWING_NO') {
          productAttributeValues[attr.name] = selectedProduct.drawingNo || '';
        } else if (source === 'PRODUCT_DIMENSION') {
          productAttributeValues[attr.name] = selectedProduct.dimension || '';
        } else if (source === 'PRODUCT_MATERIAL') {
          productAttributeValues[attr.name] = selectedProduct.material || '';
        } else {
          productAttributeValues[attr.name] = attr.default_value ?? '';
        }
      }

      // Build componentData — preserve full field structure
      const componentData: ComponentDataItem[] = componentsSnapshot.map((comp: any) => {
        const fields: ComponentField[] = (comp.fields || []).map((f: any) => ({
          name: f.name || f.key || f.fieldKey || '',
          field_type: normalizeFieldType(f.field_type || f.type || f.fieldType || ''),
          content: f.content,
          is_amount: f.is_amount,
          is_subtotal: f.is_subtotal,
          formula_name: f.formula_name,
          datasource_binding: f.datasource_binding,
          sort_order: f.sort_order,
          label: f.label || f.fieldLabel || f.name || '',
          key: f.name || f.key || f.fieldKey || '',
        }));

        const formulas: ComponentFormula[] = (comp.formulas || []).map((fm: any) => ({
          name: fm.name || fm.fieldKey || fm.key || '',
          expression: Array.isArray(fm.expression) ? fm.expression : [],
          result_type: fm.result_type,
        }));

        // Build initial rows: preset rows (marked as fixed) + one empty row if no presets
        const presetRows: any[] = comp.preset_rows || comp.presetRows || [];
        const initialRows = presetRows.length > 0
          ? presetRows.map((pr: any, ri: number) => ({
              ...buildEmptyRow(fields),
              ...pr,
              _preset: true,
              row_index: ri,
            }))
          : [buildEmptyRow(fields)];

        // Load formula assignments from snapshot (template-level binding)
        const rawFa = comp.formula_assignments || comp.formulaAssignments || {};
        const formulaAssignments: Record<string, string> = typeof rawFa === 'string'
          ? JSON.parse(rawFa) : rawFa;

        const compType = comp.component_type || comp.componentType || 'NORMAL';

        return {
          componentId: comp.component_id || comp.componentId || '',
          componentCode: comp.component_code || comp.componentCode || '',
          componentType: compType,
          tabName: comp.tab_name || comp.tabName || comp.name || 'Tab',
          fields,
          formulas,
          formulaAssignments,
          rows: compType === 'SUBTOTAL' ? [] : initialRows,
          subtotal: 0,
        };
      });

      // Parse subtotal formula (token array) from template
      const subtotalFormula: any[] = parseJson(
        tmpl.subtotalFormula || tmpl.subtotal_formula,
        []
      );

      const lineItem: LineItem = {
        // Bug B (2026-05-20): 新建 lineItem 时生成 tempId，用于 driverExpansionKey lineItemId 维度
        tempId: typeof crypto !== 'undefined' ? crypto.randomUUID() : `tmp-${Date.now()}`,
        productId: selectedProduct.id,
        productName: selectedProduct.name || '',
        productPartNo: selectedProduct.partNo || '',
        templateId: tmpl.id,
        templateName: tmpl.name + (tmpl.version ? ` ${tmpl.version}` : ''),
        productAttributeValues,
        productAttributes,
        componentData,
        subtotal: 0,
        subtotalFormula,
      };

      onConfirm(lineItem);
    } catch (e) {
      // eslint-disable-next-line no-console
      console.error('AddProductModal: failed to build line item', e);
    } finally {
      setConfirming(false);
    }
  };

  const canNext =
    (step === 0 && selectedProduct != null) ||
    step === 1 || // can always advance from step 2 (skip processes)
    (step === 2 && selectedTemplate != null);

  const isStep2NextDisabled = step === 2 && !selectedTemplate;

  if (!open) return null;

  return (
    <div className="qt-modal-overlay" onClick={e => { if (e.target === e.currentTarget) onCancel(); }}>
      <div className="qt-modal-container">
        {/* Header */}
        <div className="qt-modal-header">
          <span className="qt-modal-title">添加产品</span>
          <button type="button" className="qt-modal-close" onClick={onCancel}>✕</button>
        </div>

        {/* Step indicator */}
        <StepIndicator current={step} />

        {/* Body */}
        <div className="qt-modal-body">
          {step === 0 && (
            <Step1
              selectedProduct={selectedProduct}
              onSelect={setSelectedProduct}
            />
          )}
          {step === 1 && selectedProduct && (
            <Step2
              productId={selectedProduct.id}
              selectedProcessIds={selectedProcessIds}
              onToggle={toggleProcess}
            />
          )}
          {step === 2 && selectedProduct && (
            <Step3
              productId={selectedProduct.id}
              processIds={selectedProcessIds}
              selectedTemplateId={selectedTemplate?.id ?? null}
              onSelect={setSelectedTemplate}
            />
          )}
        </div>

        {/* Footer */}
        <div className="qt-modal-footer">
          <button type="button" className="qt-btn qt-btn-default" onClick={onCancel}>
            取消
          </button>
          {step > 0 && (
            <button type="button" className="qt-btn qt-btn-default" onClick={handlePrev}>
              上一步
            </button>
          )}
          {step < 2 && (
            <button
              type="button"
              className="qt-btn qt-btn-primary"
              onClick={handleNext}
              disabled={!canNext}
            >
              下一步
            </button>
          )}
          {step === 2 && (
            <button
              type="button"
              className={`qt-btn qt-btn-success`}
              onClick={handleConfirm}
              disabled={isStep2NextDisabled || confirming}
            >
              {confirming ? '处理中…' : '确认添加'}
            </button>
          )}
        </div>
      </div>
    </div>
  );
};

// ─── Utility ─────────────────────────────────────────────────────────────────

function normalizeFieldType(raw: string): 'FIXED_VALUE' | 'DATA_SOURCE' | 'INPUT' | 'INPUT_TEXT' | 'INPUT_NUMBER' | 'FORMULA' {
  const t = (raw || '').toUpperCase();
  if (t === 'FORMULA') return 'FORMULA';
  if (t === 'FIXED_VALUE' || t === 'FIXED') return 'FIXED_VALUE';
  if (t === 'DATA_SOURCE') return 'DATA_SOURCE';
  if (t === 'INPUT_TEXT') return 'INPUT_TEXT';
  if (t === 'INPUT_NUMBER') return 'INPUT_NUMBER';
  return 'INPUT_TEXT';
}

export default AddProductModal;
