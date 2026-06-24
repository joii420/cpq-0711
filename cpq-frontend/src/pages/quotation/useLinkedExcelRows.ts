/**
 * useLinkedExcelRows —— 从 LinkedExcelView 抽出的共享计算 hook。
 * 输入 (linkedTemplateId, lineItems, customerId, templateId, ...) → 输出每行 col_key→value。
 * LinkedExcelView 与 ComparisonView 共用此 hook，保证「比对值」与「Excel 视图渲染值」逐格一致。
 */
import { useEffect, useMemo, useState } from 'react';
import { templateService } from '../../services/templateService';
import type { CostingTemplate, CostingTemplateColumn } from '../../services/costingTemplateService';
import { batchEvaluate, buildEvalKey } from '../../services/formulaService';
import type { LineItem } from './QuotationStep2';

/** BNF path cache key: `${partNo}::${path}` */
export const pathCacheKey = (partNo: string, path: string) => `${partNo}::${path}`;

/**
 * M1（2026-06-21）：共享列解析 helper，消除 QuotationWizard 与 useLinkedExcelRows 的重复逻辑。
 * getExcelViewConfig 响应可能返回数组或含 columns 属性的对象，此 helper 统一处理两种格式。
 * 行为与原两处实现 1:1 等价。
 */
export function parseExcelViewColumns(raw: any): CostingTemplateColumn[] {
  if (Array.isArray(raw)) return raw as CostingTemplateColumn[];
  if (Array.isArray(raw?.columns)) return raw.columns as CostingTemplateColumn[];
  return [];
}

/**
 * 单行数据：__key/__label/__hfPartNo/__noData 为元数据，其余 key 为 col_key → 求值后的值。
 * 注意：BNF 路径仍在异步求值中的 cell 值为哨兵字符串 '__loading__'，
 * 消费方（LinkedExcelView 渲染、ComparisonView 比对）必须识别并处理该哨兵。
 */
export interface LinkedExcelRow {
  __key: string;
  __label: string;
  __hfPartNo?: string;
  __noData: boolean;
  [colKey: string]: any;
}

export interface UseLinkedExcelRowsParams {
  /** 反查 Excel 视图配置的目标模板 ID（lookup key） */
  linkedTemplateId?: string;
  lineItems: LineItem[];
  customerId?: string;
  /** template 实体 ID，供后端 SqlViewRuntimeContext 定位 template_sql_view；与 linkedTemplateId 区分，传错会导致 BNF 路径求值错误 */
  templateId?: string | null;
  quotationContext?: Record<string, any>;
  quotationId?: string | null;
  quotationStatus?: string | null;
}

export interface UseLinkedExcelRowsResult {
  rows: LinkedExcelRow[];
  parsedColumns: CostingTemplateColumn[];
  excelTemplate: CostingTemplate | null;
  loading: boolean;
  error: string | null;
  /**
   * 配置模型：
   *  - 'v2'    = 模板「引用 EXCEL 组件」配置 {excel_component_id}，客户端无法解析，
   *              须走后端 getExcelView(getEffectiveColumns + TabJoinPlanEvaluator)。
   *  - 'legacy'= 老内联列数组（VARIABLE/FORMULA 客户端可算）。
   *  - 'empty' = 未配置 / 无 linkedTemplateId。
   */
  configShape: 'v2' | 'legacy' | 'empty';
}

/** 是否为老 `{CODE}` 简写格式（V73 过渡格式）；非此格式即按 BNF 路径走后端求值。 */
export function isLegacyVarCode(s: string | undefined): boolean {
  return !!s && /^\{[^}]+\}$/.test(s.trim());
}

/** 解析老 `{CODE}` 格式 VARIABLE 列：去括号转小写后按 lineItem / quotationContext 字段映射取值。 */
export function resolveVariable(
  variablePath: string | undefined,
  lineItem: LineItem,
  quotationContext: Record<string, any>,
): any {
  if (!variablePath) return null;
  const m = variablePath.match(/^\{([^}]+)\}$/);
  if (!m) return null;
  const code = m[1].trim().toLowerCase();
  const li = lineItem as any;
  const liMap: Record<string, any> = {
    'customer_drawing_no': li.customerDrawingNo,
    'customer_part_name': li.customerPartName,
    'customer_part_no': li.customerPartNo,
    'customer_product_no': li.customerProductNo,
    'product_part_no': li.productPartNo,
    'hf_part_no': li.productPartNo,
    'product_name': li.productName,
    'product_id': li.productId,
    'specification': li.hfPartInfo?.specification,
    'size_info': li.hfPartInfo?.sizeInfo,
    'status_code': li.hfPartInfo?.statusCode,
    'subtotal': li.subtotal,
  };
  if (Object.prototype.hasOwnProperty.call(liMap, code)) return liMap[code];
  if (li.productAttributeValues && Object.prototype.hasOwnProperty.call(li.productAttributeValues, code)) {
    return li.productAttributeValues[code];
  }
  if (Object.prototype.hasOwnProperty.call(quotationContext, code)) return quotationContext[code];
  const sysMap: Record<string, any> = {
    'base_currency': 'USD',
    'system_date': new Date().toISOString().slice(0, 10),
  };
  if (Object.prototype.hasOwnProperty.call(sysMap, code)) return sysMap[code];
  return null;
}

/** FORMULA 列求值：替换列引用 [X] 与变量 {CODE} 后，受限字符白名单内用 Function 构造器计算。 */
export function evaluateFormula(
  formula: string | undefined,
  rowCellValues: Record<string, any>,
  rowVariableValues: Record<string, any>,
): number | string {
  if (!formula) return '';
  let expr = formula.trim();
  if (expr.startsWith('=')) expr = expr.slice(1);
  expr = expr.replace(/\[([A-Za-z][A-Za-z0-9_]*)\]/g, (_, k) => {
    const v = rowCellValues[k];
    if (typeof v === 'number') return String(v);
    const n = parseFloat(v as any);
    return isNaN(n) ? '0' : String(n);
  });
  expr = expr.replace(/\{([^}]+)\}/g, (_, k) => {
    const code = (k as string).trim().toLowerCase();
    const v = rowVariableValues[code];
    if (typeof v === 'number') return String(v);
    const n = parseFloat(v as any);
    return isNaN(n) ? '0' : String(n);
  });
  try {
    if (!/^[\d+\-*/().,\s%<>=!&|?:]*$/.test(expr)) {
      return '—';
    }
    // eslint-disable-next-line no-new-func
    const v = Function(`"use strict"; return (${expr});`)();
    return typeof v === 'number' && Number.isFinite(v) ? v : '—';
  } catch {
    return '—';
  }
}

export function formatPathValue(v: any): any {
  if (v == null) return null;
  if (typeof v === 'number' || typeof v === 'string' || typeof v === 'boolean') return v;
  if (Array.isArray(v)) {
    if (v.length === 0) return null;
    return v.length === 1 ? formatPathValue(v[0]) : `${formatPathValue(v[0])}（共${v.length}项）`;
  }
  if (typeof v === 'object') {
    if (v.type === 'jsonb' && typeof v.value === 'string') {
      try {
        const parsed = JSON.parse(v.value);
        if (Array.isArray(parsed)) {
          if (parsed.length === 0) return null;
          return parsed.map((it: any) => formatPathValue(it) ?? '').filter(Boolean).join(', ');
        }
        if (parsed && typeof parsed === 'object') {
          const keys = Object.keys(parsed);
          if (keys.length === 0) return null;
          return keys.map(k => {
            const sub = formatPathValue(parsed[k]);
            return sub != null ? `${k}=${sub}` : null;
          }).filter(Boolean).join(', ');
        }
        return formatPathValue(parsed);
      } catch { return v.value; }
    }
    for (const k of Object.keys(v)) {
      if (v[k] != null && v[k] !== '') return formatPathValue(v[k]);
    }
  }
  return null;
}

export function useLinkedExcelRows(params: UseLinkedExcelRowsParams): UseLinkedExcelRowsResult {
  const { linkedTemplateId, lineItems, customerId, templateId, quotationContext, quotationId, quotationStatus } = params;
  const [excelTemplate, setExcelTemplate] = useState<CostingTemplate | null>(null);
  const [configShape, setConfigShape] = useState<'v2' | 'legacy' | 'empty'>('empty');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [pathCache, setPathCache] = useState<Record<string, any>>({});

  useEffect(() => {
    if (!linkedTemplateId) {
      setExcelTemplate(null);
      setConfigShape('empty');
      return;
    }
    setLoading(true);
    setError(null);
    templateService
      .getExcelViewConfig(linkedTemplateId)
      .then((r: any) => {
        const raw = r?.data ?? r;
        // v2「引用 EXCEL 组件」配置 {version,excel_component_id,column_overrides}：客户端无法把组件引用
        // 解析成列（需后端 getEffectiveColumns），其列多为 TAB_JOIN/CARD_FORMULA。
        // 标记为 v2，由 LinkedExcelView 据 side 决定列定义来源（报价侧取后端解析列 + 前端 buildExcelSnapshot 算值）。
        if (raw && typeof raw === 'object' && !Array.isArray(raw) && raw.excel_component_id) {
          setConfigShape('v2');
          setExcelTemplate(null);
          return;
        }
        const cols = parseExcelViewColumns(raw);
        if (cols.length === 0) {
          setConfigShape('empty');
          setExcelTemplate(null);
          return;
        }
        setConfigShape('legacy');
        setExcelTemplate({
          id: linkedTemplateId,
          columns: JSON.stringify(cols),
        } as CostingTemplate);
      })
      .catch((e: any) => setError(e?.message ?? '加载 Excel 视图配置失败'))
      .finally(() => setLoading(false));
  }, [linkedTemplateId]);

  const parsedColumns: CostingTemplateColumn[] = useMemo(() => {
    if (!excelTemplate) return [];
    try {
      return JSON.parse(excelTemplate.columns || '[]') as CostingTemplateColumn[];
    } catch {
      return [];
    }
  }, [excelTemplate]);

  const pathTasks = useMemo(() => {
    const out: Array<{ partNo: string; path: string }> = [];
    const seen = new Set<string>();
    for (const li of lineItems || []) {
      const partNo = li.productPartNo;
      if (!partNo) continue;
      for (const col of parsedColumns) {
        if (col.source_type !== 'VARIABLE') continue;
        const vp = (col.variable_path || '').trim();
        if (!vp) continue;
        if (isLegacyVarCode(vp)) continue;
        const k = pathCacheKey(partNo, vp);
        if (seen.has(k)) continue;
        seen.add(k);
        out.push({ partNo, path: vp });
      }
    }
    return out;
  }, [lineItems, parsedColumns]);

  useEffect(() => {
    const missing = pathTasks.filter((t) => !(pathCacheKey(t.partNo, t.path) in pathCache));
    if (missing.length === 0) return;

    const tasks = missing.map((t) => ({
      expression: `{${t.path}}`,
      customerId: customerId || null,
      partNo: t.partNo,
      templateId: templateId ?? linkedTemplateId ?? null,
      quotationId: quotationId || null,
      quotationStatus: quotationStatus || null,
    }));

    const controller = new AbortController();
    batchEvaluate(tasks, controller.signal)
      .then((items) => {
        if (controller.signal.aborted) return;
        const itemByKey: Record<string, typeof items[number]> = {};
        for (const it of items) itemByKey[it.key] = it;
        setPathCache((prev) => {
          const next = { ...prev };
          for (const t of missing) {
            const expr = `{${t.path}}`;
            const reqKey = buildEvalKey(expr, customerId || null, t.partNo, templateId ?? linkedTemplateId ?? null);
            const item = itemByKey[reqKey];
            const cacheK = pathCacheKey(t.partNo, t.path);
            if (item && item.status === 'OK' && item.data?.success) {
              next[cacheK] = item.data.result ?? null;
            } else {
              next[cacheK] = null;
            }
          }
          return next;
        });
      })
      .catch((err) => {
        if (controller.signal.aborted || (err && (err as any).code === 'ERR_CANCELED')) return;
        setPathCache((prev) => {
          const next = { ...prev };
          for (const t of missing) {
            const cacheK = pathCacheKey(t.partNo, t.path);
            if (!(cacheK in next)) next[cacheK] = null;
          }
          return next;
        });
      });
    return () => controller.abort();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pathTasks, customerId, templateId, linkedTemplateId, quotationId, quotationStatus]);

  const rows = useMemo<LinkedExcelRow[]>(() => {
    if (parsedColumns.length === 0) return [];
    const ctx = quotationContext || {};
    return (lineItems || []).map((li, i) => {
      const cellValues: Record<string, any> = {};
      const varValues: Record<string, any> = {};
      const partNo = li.productPartNo;
      for (const col of parsedColumns) {
        if (col.source_type !== 'VARIABLE') continue;
        const vp = (col.variable_path || '').trim();
        if (!vp) {
          cellValues[col.col_key] = null;
          continue;
        }
        if (isLegacyVarCode(vp)) {
          const v = resolveVariable(vp, li, ctx);
          cellValues[col.col_key] = v;
          const m = vp.match(/^\{([^}]+)\}$/);
          if (m) varValues[m[1].trim().toLowerCase()] = v;
        } else {
          if (!partNo) {
            cellValues[col.col_key] = null;
            continue;
          }
          const k = pathCacheKey(partNo, vp);
          if (!Object.prototype.hasOwnProperty.call(pathCache, k)) {
            cellValues[col.col_key] = '__loading__';
          } else {
            cellValues[col.col_key] = formatPathValue(pathCache[k]);
          }
        }
      }
      const dataVariableCols = parsedColumns.filter((c) => {
        if (c.source_type !== 'VARIABLE') return false;
        const vp = (c.variable_path || '').trim();
        return vp && !isLegacyVarCode(vp);
      });
      const stillLoading = dataVariableCols.some((c) => cellValues[c.col_key] === '__loading__');
      const hasAnyData = dataVariableCols.some((c) => {
        const v = cellValues[c.col_key];
        return v !== null && v !== undefined && v !== '' && v !== '__loading__';
      });
      const noCostingData = !stillLoading && dataVariableCols.length > 0 && !hasAnyData;

      if (noCostingData) {
        for (const col of parsedColumns) {
          cellValues[col.col_key] = null;
        }
      } else {
        for (const col of parsedColumns) {
          if (col.source_type === 'FORMULA') {
            cellValues[col.col_key] = evaluateFormula(col.formula, cellValues, varValues);
          }
        }
      }
      const liAny = li as any;
      const productLabel =
        liAny.customerPartNo
        || liAny.customerProductNo
        || li.productPartNo
        || li.productName
        || `产品 ${i + 1}`;
      return {
        __key: li.productId ? `${li.productId}-${i}` : `row-${i}`,
        __label: productLabel,
        __hfPartNo: li.productPartNo,
        __noData: noCostingData,
        ...cellValues,
      };
    });
    // 注：formatPathValue/resolveVariable/evaluateFormula 均为模块级稳定引用，无需进 deps
  }, [parsedColumns, lineItems, quotationContext, pathCache]);

  return { rows, parsedColumns, excelTemplate, loading, error, configShape };
}
