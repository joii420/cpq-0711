/**
 * ExcelView — displays quotation line items in an Excel-like spreadsheet view.
 *
 * Uses a plain HTML <table> with <input> cells.
 * - PRODUCT_ATTRIBUTE / COMPONENT_FIELD cells: editable
 * - EXCEL_FORMULA cells: read-only, gray background, value computed from other cells
 * - FIXED_VALUE cells: read-only, light-blue background
 */
import React, { useEffect, useState, useCallback } from 'react';
import { Spin, message, Tag } from 'antd';
import { templateService } from '../../services/templateService';
import type { LineItem } from './QuotationStep2';

interface ViewColumn {
  col_key: string;
  title: string;
  source_type: 'PRODUCT_ATTRIBUTE' | 'COMPONENT_FIELD' | 'EXCEL_FORMULA' | 'FIXED_VALUE';
  value: any;
}

interface ExcelViewConfig {
  columns: ViewColumn[];
}

interface Props {
  quotationId: string;
  customerId: string;
  lineItems: LineItem[];
  onUpdate: (lineItemIndex: number, data: Partial<LineItem>) => void;
}

// ─── Formula computation ──────────────────────────────────────────────────────

/**
 * Compute an Excel formula for a specific row.
 * Formula syntax: =B{row}*C{row}  or  =B2+C2
 * {row} is replaced with the actual 1-based row number.
 */
function computeExcelFormula(
  formula: string,
  rowValues: Record<string, string | number>,
  rowNum: number,
): string | number {
  if (!formula || !formula.startsWith('=')) return '';

  // Replace {row} with actual row number
  let expr = formula.replace(/\{row\}/g, String(rowNum));

  // Strip leading '='
  expr = expr.slice(1);

  // Replace absolute cell references like B2, C3 (same-row values accessible by col key)
  // We only support same-row computations
  for (const [colKey, val] of Object.entries(rowValues)) {
    // Replace col+rowNum references: e.g. B2 where rowNum=2
    const cellRef = new RegExp(`\\b${colKey}${rowNum}\\b`, 'g');
    expr = expr.replace(cellRef, String(val ?? 0));
    // Replace bare column references like B (without a row number following)
    const bareRef = new RegExp(`\\b${colKey}(?![0-9])`, 'g');
    expr = expr.replace(bareRef, String(val ?? 0));
  }

  try {
    // eslint-disable-next-line no-new-func
    const result = new Function('return ' + expr)();
    if (typeof result === 'number' && isFinite(result)) {
      return Math.round(result * 10000) / 10000;
    }
    return result ?? '#ERR';
  } catch {
    return '#ERR';
  }
}

// ─── Cell value resolution ────────────────────────────────────────────────────

function resolveProductAttribute(lineItem: LineItem, attrName: string): string {
  return String(lineItem.productAttributeValues?.[attrName] ?? '');
}

function resolveComponentField(
  lineItem: LineItem,
  value: { component_code: string; field_name: string; row_index: number },
): string {
  if (!value || typeof value !== 'object') return '';
  const { component_code, field_name, row_index } = value;
  const comp = lineItem.componentData?.find(
    c => c.componentId === component_code || c.componentCode === component_code
  );
  if (!comp) return '';
  const row = comp.rows?.[row_index];
  if (!row) return '';
  return String(row[field_name] ?? '');
}

function getCellValue(col: ViewColumn, lineItem: LineItem): string {
  switch (col.source_type) {
    case 'PRODUCT_ATTRIBUTE':
      return resolveProductAttribute(lineItem, col.value as string);
    case 'COMPONENT_FIELD':
      return resolveComponentField(lineItem, col.value);
    case 'FIXED_VALUE':
      return String(col.value ?? '');
    default:
      return '';
  }
}

// ─── ExcelView component ──────────────────────────────────────────────────────

const ExcelView: React.FC<Props> = ({ quotationId: _quotationId, lineItems, onUpdate }) => {
  const [viewConfig, setViewConfig] = useState<ExcelViewConfig | null>(null);
  const [loading, setLoading] = useState(false);
  // rowData[lineItemIndex][col_key] = current cell value (strings for inputs)
  const [rowData, setRowData] = useState<Record<string, string>[]>([]);

  // Derive templateId from the first lineItem
  const templateId = lineItems[0]?.templateId;

  const loadViewConfig = useCallback(async (tid: string) => {
    setLoading(true);
    try {
      const res = await templateService.getExcelViewConfig(tid);
      const raw = res.data || res;
      let cfg: ExcelViewConfig;
      if (typeof raw === 'string') {
        cfg = JSON.parse(raw);
      } else if (raw && raw.columns) {
        cfg = raw as ExcelViewConfig;
      } else {
        cfg = { columns: [] };
      }
      setViewConfig(cfg);
    } catch {
      // Fallback: try reading from template directly
      try {
        const res2 = await templateService.getById(tid);
        const tpl = res2.data || res2;
        let cfg2: ExcelViewConfig = { columns: [] };
        if (tpl.excelViewConfig) {
          cfg2 = typeof tpl.excelViewConfig === 'string'
            ? JSON.parse(tpl.excelViewConfig)
            : tpl.excelViewConfig;
        }
        setViewConfig(cfg2);
      } catch (e2: any) {
        message.error('加载Excel视图配置失败: ' + (e2.message || ''));
        setViewConfig({ columns: [] });
      }
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (templateId) {
      loadViewConfig(templateId);
    }
  }, [templateId, loadViewConfig]);

  // Initialize rowData from lineItems
  useEffect(() => {
    if (!viewConfig || !lineItems.length) return;
    const initial = lineItems.map(item => {
      const row: Record<string, string> = {};
      for (const col of viewConfig.columns) {
        if (col.source_type !== 'EXCEL_FORMULA') {
          row[col.col_key] = getCellValue(col, item);
        }
      }
      return row;
    });
    setRowData(initial);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [viewConfig, lineItems.length]);

  const handleCellChange = (rowIndex: number, colKey: string, value: string, col: ViewColumn) => {
    setRowData(prev => {
      const next = [...prev];
      next[rowIndex] = { ...(next[rowIndex] || {}), [colKey]: value };
      return next;
    });

    // Propagate change back to lineItem
    const item = lineItems[rowIndex];
    if (!item) return;

    if (col.source_type === 'PRODUCT_ATTRIBUTE') {
      const attrName = col.value as string;
      onUpdate(rowIndex, {
        productAttributeValues: {
          ...item.productAttributeValues,
          [attrName]: value,
        },
      });
    } else if (col.source_type === 'COMPONENT_FIELD' && typeof col.value === 'object') {
      const { component_code, field_name, row_index } = col.value;
      const newComponentData = item.componentData.map(comp => {
        if (comp.componentId !== component_code && comp.componentCode !== component_code) return comp;
        const newRows = comp.rows.map((row, ri) => {
          if (ri !== row_index) return row;
          return { ...row, [field_name]: value };
        });
        return { ...comp, rows: newRows };
      });
      onUpdate(rowIndex, { componentData: newComponentData });
    }
  };

  if (loading) {
    return (
      <div style={{ textAlign: 'center', padding: 48 }}>
        <Spin tip="加载Excel视图..." />
      </div>
    );
  }

  if (!viewConfig || viewConfig.columns.length === 0) {
    return (
      <div style={{
        padding: 32, textAlign: 'center', background: '#fafafa',
        border: '1px dashed #d9d9d9', borderRadius: 8, color: '#999',
      }}>
        该模板尚未配置Excel视图列，请先在模板配置页的"Excel视图"标签页中设置。
      </div>
    );
  }

  const cols = viewConfig.columns;

  return (
    <div style={{ overflowX: 'auto' }}>
      <div style={{ marginBottom: 8, display: 'flex', gap: 8, alignItems: 'center' }}>
        <span style={{ fontSize: 12, color: '#666' }}>图例：</span>
        <Tag color="default">可编辑</Tag>
        <span style={{ fontSize: 12, padding: '0 6px', background: '#f5f5f5', border: '1px solid #d9d9d9', borderRadius: 4, color: '#999' }}>公式(只读)</span>
        <span style={{ fontSize: 12, padding: '0 6px', background: '#e6f7ff', border: '1px solid #91d5ff', borderRadius: 4, color: '#1890ff' }}>固定值</span>
      </div>

      <table style={{
        borderCollapse: 'collapse',
        fontSize: 13,
        width: '100%',
        tableLayout: 'auto',
      }}>
        <thead>
          <tr style={{ background: '#f0f5ff' }}>
            {/* Row number column */}
            <th style={headerCellStyle({ width: 40 })}>#</th>
            {cols.map(col => (
              <th key={col.col_key} style={headerCellStyle({})}>
                <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
                  <span style={{ fontFamily: 'monospace', color: '#8c8c8c', fontSize: 11 }}>{col.col_key}</span>
                  <span>{col.title || col.col_key}</span>
                  <Tag
                    color={SOURCE_COLORS[col.source_type] || 'default'}
                    style={{ fontSize: 10, lineHeight: '16px', padding: '0 4px', alignSelf: 'flex-start' }}
                  >
                    {SOURCE_LABELS[col.source_type] || col.source_type}
                  </Tag>
                </div>
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {lineItems.map((item, rowIndex) => {
            const currentRowData = rowData[rowIndex] || {};
            // Build all non-formula values for formula evaluation
            const rowValues: Record<string, string | number> = {};
            for (const col of cols) {
              if (col.source_type !== 'EXCEL_FORMULA') {
                const v = currentRowData[col.col_key] ?? getCellValue(col, item);
                rowValues[col.col_key] = isNaN(Number(v)) || v === '' ? v : Number(v);
              }
            }

            return (
              <tr key={rowIndex} style={{ borderBottom: '1px solid #f0f0f0' }}>
                <td style={rowNumCellStyle}>{rowIndex + 1}</td>
                {cols.map(col => {
                  if (col.source_type === 'EXCEL_FORMULA') {
                    const computed = computeExcelFormula(
                      String(col.value || ''),
                      rowValues,
                      rowIndex + 1,
                    );
                    return (
                      <td key={col.col_key} style={formulaCellStyle}>
                        {computed === '#ERR' ? (
                          <span style={{ color: '#ff4d4f' }}>#ERR</span>
                        ) : String(computed)}
                      </td>
                    );
                  }

                  if (col.source_type === 'FIXED_VALUE') {
                    return (
                      <td key={col.col_key} style={fixedValueCellStyle}>
                        {getCellValue(col, item)}
                      </td>
                    );
                  }

                  // PRODUCT_ATTRIBUTE or COMPONENT_FIELD — editable
                  const cellVal = currentRowData[col.col_key] ?? getCellValue(col, item);
                  return (
                    <td key={col.col_key} style={editableCellStyle}>
                      <input
                        type="text"
                        value={cellVal}
                        onChange={e => handleCellChange(rowIndex, col.col_key, e.target.value, col)}
                        style={inputStyle}
                        title={`${item.productName} - ${col.title || col.col_key}`}
                      />
                    </td>
                  );
                })}
              </tr>
            );
          })}
        </tbody>
      </table>

      {lineItems.length === 0 && (
        <div style={{ textAlign: 'center', padding: '24px 0', color: '#999' }}>
          暂无产品行，请先添加产品
        </div>
      )}
    </div>
  );
};

// ─── Styles ───────────────────────────────────────────────────────────────────

const SOURCE_LABELS: Record<string, string> = {
  PRODUCT_ATTRIBUTE: '产品属性',
  COMPONENT_FIELD: '组件字段',
  EXCEL_FORMULA: 'Excel公式',
  FIXED_VALUE: '固定值',
};

const SOURCE_COLORS: Record<string, string> = {
  PRODUCT_ATTRIBUTE: 'blue',
  COMPONENT_FIELD: 'green',
  EXCEL_FORMULA: 'orange',
  FIXED_VALUE: 'cyan',
};

function headerCellStyle(extra: React.CSSProperties): React.CSSProperties {
  return {
    padding: '8px 10px',
    border: '1px solid #d9d9d9',
    textAlign: 'left',
    fontWeight: 600,
    whiteSpace: 'nowrap',
    ...extra,
  };
}

const rowNumCellStyle: React.CSSProperties = {
  padding: '4px 8px',
  border: '1px solid #e8e8e8',
  textAlign: 'center',
  color: '#bfbfbf',
  fontSize: 12,
  background: '#fafafa',
  userSelect: 'none',
};

const formulaCellStyle: React.CSSProperties = {
  padding: '4px 8px',
  border: '1px solid #e8e8e8',
  background: '#f5f5f5',
  color: '#595959',
  fontSize: 13,
  whiteSpace: 'nowrap',
};

const fixedValueCellStyle: React.CSSProperties = {
  padding: '4px 8px',
  border: '1px solid #e8e8e8',
  background: '#e6f7ff',
  color: '#1890ff',
  fontSize: 13,
  whiteSpace: 'nowrap',
};

const editableCellStyle: React.CSSProperties = {
  padding: '2px',
  border: '1px solid #e8e8e8',
};

const inputStyle: React.CSSProperties = {
  width: '100%',
  minWidth: 80,
  padding: '3px 6px',
  border: 'none',
  outline: 'none',
  fontSize: 13,
  background: 'transparent',
};

export default ExcelView;
