/**
 * useBackendExcelRows —— 调用后端 getExcelView 获取已算好的行数据（含 CARD_FORMULA 列）。
 *
 * 适用于新模型（模板列配置中存在 source_type==='CARD_FORMULA' 的列）。
 * 输出形态与 useLinkedExcelRows 对齐：{ rows, parsedColumns, excelTemplate, loading, error }。
 *
 * 注意：excelTemplate 在新模型下不携带完整 CostingTemplate 元信息（无 name/version/status 等），
 * 因此 LinkedExcelView 在新模型下不显示模板 Tag 标题栏——仅用 viewLabel。
 */
import { useEffect, useMemo, useState } from 'react';
import { quotationService } from '../../services/quotationService';
import type { CostingTemplateColumn } from '../../services/costingTemplateService';
import type { LinkedExcelRow } from './useLinkedExcelRows';
import type { LineItem } from './QuotationStep2';

export interface UseBackendExcelRowsParams {
  quotationId?: string | null;
  lineItems: LineItem[];
  /** 控制是否真正发请求（false 时立即返回初始空状态，不调 API） */
  enabled: boolean;
  /** 按指定模板取数（报价视图传报价模板id，核价视图传核价模板id） */
  templateId?: string | null;
}

export interface UseBackendExcelRowsResult {
  rows: LinkedExcelRow[];
  parsedColumns: CostingTemplateColumn[];
  loading: boolean;
  error: string | null;
}

/**
 * 将后端返回的原始值格式化为可显示值。
 * null/undefined/空字符串/"—" → null（让渲染层统一显示"—"）。
 */
function normalizeBackendValue(v: any): any {
  if (v === null || v === undefined || v === '' || v === '—') return null;
  return v;
}

/**
 * Excel 视图取数刷新信号（取值时机修复）：把每个 lineItem 的「编辑落库时间戳 quoteValuesAt」
 * （editCardValue 每次重算 + 物化 row_data 后都会更新并由前端就地回灌）拼成一个稳定字符串。
 *
 * <p>约定：用户在产品卡片改任意数据触发公式计算 → 后端重算并物化该料号 row_data → 前端 patch
 * quoteValuesAt → 本信号变化 → useBackendExcelRows useEffect 重取最新 row_data。
 *
 * <p>无 quoteValuesAt 时回退用 quoteCardValues 长度兜底（仍能在编辑改变卡片值时变化）；
 * 二者皆缺则该 lineItem 贡献空段（首次加载阶段，mount 本身已触发取数）。
 */
export function excelRefreshSignal(lineItems: LineItem[] | undefined): string {
  if (!lineItems || lineItems.length === 0) return '';
  return lineItems
    .map(li => `${li.id ?? li.tempId ?? ''}@${li.quoteValuesAt ?? (li.quoteCardValues ? li.quoteCardValues.length : '')}`)
    .join('|');
}

export function useBackendExcelRows(params: UseBackendExcelRowsParams): UseBackendExcelRowsResult {
  const { quotationId, lineItems, enabled, templateId } = params;

  const [rawColumns, setRawColumns] = useState<CostingTemplateColumn[]>([]);
  const [rawRows, setRawRows] = useState<Array<Record<string, any>>>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // 取值时机修复：编辑落库后 lineItems[].quoteValuesAt 变化 → 信号变化 → 重取最新 row_data。
  // 约定：卡片改任意数据触发公式计算 → 后端一并重算+物化该料号 row_data → Excel 视图随之刷新。
  const refreshSignal = useMemo(() => excelRefreshSignal(lineItems), [lineItems]);

  useEffect(() => {
    if (!enabled || !quotationId) {
      setRawColumns([]);
      setRawRows([]);
      setLoading(false);
      setError(null);
      return;
    }

    setLoading(true);
    setError(null);

    quotationService
      .getExcelView(quotationId, templateId || undefined)
      .then((resp: any) => {
        // api 拦截器可能把数据包在 resp.data 或直接是对象
        const body = resp?.data ?? resp;
        const cols: CostingTemplateColumn[] = Array.isArray(body?.columns) ? body.columns : [];
        const rows: Array<Record<string, any>> = Array.isArray(body?.rows) ? body.rows : [];
        setRawColumns(cols);
        setRawRows(rows);
      })
      .catch((e: any) => {
        setError(e?.message ?? '加载 Excel 视图数据失败');
        setRawColumns([]);
        setRawRows([]);
      })
      .finally(() => setLoading(false));
    // refreshSignal 入依赖：编辑落库(quoteValuesAt 变)→ 重取 Excel 视图最新行数据。
  }, [enabled, quotationId, templateId, refreshSignal]);

  // 按 lineItemId 建索引，用于 __hfPartNo 关联
  const lineItemById = useMemo(() => {
    const map = new Map<string, LineItem>();
    for (const li of lineItems || []) {
      if (li.id) map.set(li.id, li);
    }
    return map;
  }, [lineItems]);

  const rows = useMemo<LinkedExcelRow[]>(() => {
    return rawRows.map((rawRow, i) => {
      const lineItemId: string | undefined = rawRow._lineItemId;
      const matchedLi = lineItemId ? lineItemById.get(lineItemId) : undefined;

      // __hfPartNo: 优先按 _lineItemId 匹配，取不到则按顺序用 lineItems[i]
      const hfPartNo =
        matchedLi?.productPartNo ??
        (lineItems[i]?.productPartNo) ??
        undefined;

      // __key: 优先 lineItemId，其次顺序 index
      const rowKey = lineItemId ? `backend-${lineItemId}` : `backend-row-${i}`;

      const cellValues: Record<string, any> = {};
      for (const col of rawColumns) {
        const raw = rawRow[col.col_key];
        cellValues[col.col_key] = normalizeBackendValue(raw);
      }

      return {
        __key: rowKey,
        __label: hfPartNo ?? `产品 ${i + 1}`,
        __hfPartNo: hfPartNo,
        __noData: false,
        ...cellValues,
      };
    });
  }, [rawRows, rawColumns, lineItemById, lineItems]);

  return {
    rows,
    parsedColumns: rawColumns,
    loading,
    error,
  };
}
