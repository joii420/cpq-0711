# 比对视图重构（料号双行对比 + 单元格高亮）Implementation Plan

> 📌 **状态**：实现计划/设计（交付状态未标注——若已落地请补 commit/E2E 链接；本文按历史设计快照保留）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把报价单编辑里的「比对视图」重构为：按料号横向对比报价单 Excel 视图与核价单 Excel 视图、相同 `comparison_tag` 字段一个料号两行（报价/核价）、值不同时两格高亮，并支持导出 Excel。

**Architecture:** 方案 A —— 从 `LinkedExcelView` 抽出单元格计算逻辑为共享 hook `useLinkedExcelRows`，报价/核价两个 Excel 视图与新比对视图都消费同一 hook，保证比对值与 Excel 视图逐格严格一致。比对模型在前端构建（纯函数，可单测）。导出走后端 Apache POI，但**只按前端传来的已算好模型写值+填色，不重算**。

**Tech Stack:** React + Ant Design + TypeScript（前端）、Vitest（前端单测）、Playwright（E2E）、Quarkus + Apache POI（后端导出）、JUnit 5（后端单测）。

**关键设计文档:** `docs/superpowers/specs/2026-05-29-comparison-view-by-partno-design.md`

**强制纪律（CLAUDE.md）:** `LinkedExcelView.tsx` 与 `QuotationStep2.tsx` 是协议关键文件，改动后**必须**跑 Playwright E2E `quotation-flow.spec.ts` 并附 `'加载中' final count = 0` 证据；每个"完成"必须带一行"已自检"声明。

---

## 文件结构

**前端（cpq-frontend/src）**
- 新建 `pages/quotation/useLinkedExcelRows.ts` —— 从 `LinkedExcelView.tsx` 抽出的共享 hook（模板配置加载 + BNF 路径批量求值缓存 + 每行 col_key→value 计算）。职责：把 `{linkedTemplateId, lineItems, customerId, templateId}` 算成 `{rows, parsedColumns, loading, error, excelTemplate}`。
- 改 `pages/quotation/LinkedExcelView.tsx` —— 删掉内部计算逻辑，改为消费 `useLinkedExcelRows`，只保留渲染（行为零变化）。
- 新建 `pages/quotation/comparisonModel.ts` —— 纯函数 + 类型：数值容差/字符严格 diff、由两侧 `{parsedColumns, rows}` + tag 元数据构建 `ComparisonModel`。
- 新建 `pages/quotation/comparisonModel.test.ts` —— Vitest 单测。
- 新建 `services/comparisonExportService.ts` —— POST 已算好的模型 → 下载 xlsx blob。
- 改 `pages/quotation/ComparisonView.tsx` —— 重写：两次调用 hook、构建模型、渲染双行高亮表格、导出按钮、"仅看差异"过滤。
- 改 `pages/quotation/QuotationStep2.tsx` —— 给 `<ComparisonView>` 透传新 props。

**后端（cpq-backend/src）**
- 新建 `main/.../costing/dto/ComparisonExportRequest.java` —— 导出请求体（columns + rows + cells）。
- 新建 `main/.../costing/service/ComparisonExportService.java` —— POI 写值+填色，不重算。
- 改 `main/.../costing/resource/CostingSheetResource.java` —— 新增 `POST /{id}/comparison/export`。
- 新建 `test/.../costing/ComparisonExportServiceTest.java` —— 读回 workbook 验证值与高亮填充。

**保留不动（决策）:** 旧 `CostingSheetService.buildComparison` / `GET /{id}/comparison` / `ComparisonDTO` / `CostingComparisonResourceTest` 全部**保留**——它们承载 TDD §10 COST-COMPARE 覆盖，删除会破坏测试矩阵。新比对视图不再调用 `costingSheetService.getComparison`，该方法标记保留但不再被视图引用（见 Task 9）。

---

## Task 1: 抽取 `useLinkedExcelRows` 共享 hook，`LinkedExcelView` 改为消费它

**Files:**
- Create: `cpq-frontend/src/pages/quotation/useLinkedExcelRows.ts`
- Modify: `cpq-frontend/src/pages/quotation/LinkedExcelView.tsx`

这是 refactor 任务（无新行为）。验证手段 = tsc + Vite 200 + E2E 零回归（不是单测）。

- [ ] **Step 1: 新建 hook 文件，迁入计算逻辑**

把 `LinkedExcelView.tsx` 现有的以下内容**原样**搬进 hook：`pathCacheKey`、`isLegacyVarCode`、`resolveVariable`、`evaluateFormula`、`formatPathValue`、`excelTemplate` 加载 effect、`parsedColumns`、`pathTasks`、batch-evaluate effect、`rows` useMemo。

创建 `cpq-frontend/src/pages/quotation/useLinkedExcelRows.ts`：

```typescript
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
const pathCacheKey = (partNo: string, path: string) => `${partNo}::${path}`;

export interface LinkedExcelRow {
  __key: string;
  __label: string;
  __hfPartNo?: string;
  __noData: boolean;
  [colKey: string]: any;
}

export interface UseLinkedExcelRowsParams {
  linkedTemplateId?: string;
  lineItems: LineItem[];
  customerId?: string;
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
}

function isLegacyVarCode(s: string | undefined): boolean {
  return !!s && /^\{[^}]+\}$/.test(s.trim());
}

function resolveVariable(
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

function evaluateFormula(
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

function formatPathValue(v: any): any {
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
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [pathCache, setPathCache] = useState<Record<string, any>>({});

  useEffect(() => {
    if (!linkedTemplateId) {
      setExcelTemplate(null);
      return;
    }
    setLoading(true);
    setError(null);
    templateService
      .getExcelViewConfig(linkedTemplateId)
      .then((r: any) => {
        const raw = r?.data ?? r;
        const cols = Array.isArray(raw)
          ? raw
          : Array.isArray(raw?.columns) ? raw.columns : [];
        if (cols.length === 0) {
          setExcelTemplate(null);
          return;
        }
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

    batchEvaluate(tasks)
      .then((items) => {
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
      .catch(() => {
        setPathCache((prev) => {
          const next = { ...prev };
          for (const t of missing) {
            const cacheK = pathCacheKey(t.partNo, t.path);
            if (!(cacheK in next)) next[cacheK] = null;
          }
          return next;
        });
      });
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
  }, [parsedColumns, lineItems, quotationContext, pathCache]);

  return { rows, parsedColumns, excelTemplate, loading, error };
}
```

- [ ] **Step 2: `LinkedExcelView.tsx` 改为消费 hook**

删除 `LinkedExcelView.tsx` 内已迁出的逻辑（`pathCacheKey`、`isLegacyVarCode`、`resolveVariable`、`evaluateFormula`、`formatPathValue`、`excelTemplate`/`pathCache` 两个 state、加载 effect、`parsedColumns`、`pathTasks`、batch effect、`rows` useMemo），改为：

```typescript
import React from 'react';
import { Table, Card, Spin, Alert, Tag } from 'antd';
import type { CostingTemplateColumn } from '../../services/costingTemplateService';
import type { LineItem } from './QuotationStep2';
import { useLinkedExcelRows } from './useLinkedExcelRows';

interface Props {
  linkedTemplateId?: string;
  lineItems: LineItem[];
  quotationContext?: Record<string, any>;
  viewLabel?: string;
  customerId?: string;
  quotationId?: string | null;
  quotationStatus?: string | null;
  templateId?: string | null;
  /** @deprecated 旧字段，已由 templateId 替代。 */
  costingTemplateId?: string | null;
}

const LinkedExcelView: React.FC<Props> = ({
  linkedTemplateId,
  lineItems,
  quotationContext,
  viewLabel,
  customerId,
  quotationId,
  quotationStatus,
  templateId,
}) => {
  const { rows, parsedColumns, excelTemplate, loading, error } = useLinkedExcelRows({
    linkedTemplateId, lineItems, customerId, templateId, quotationContext, quotationId, quotationStatus,
  });

  if (!linkedTemplateId) {
    return (
      <Alert type="info" showIcon
        message={`本报价单未绑定${viewLabel || ''}模板`}
        description="无法定位关联 Excel 模板。请先确认报价单已选择模板。"
        style={{ margin: 16 }} />
    );
  }
  if (loading) return <div style={{ textAlign: 'center', padding: 48 }}><Spin /></div>;
  if (error) return <Alert type="error" showIcon message={error} style={{ margin: 16 }} />;
  if (!excelTemplate) {
    return (
      <Alert type="warning" showIcon
        message="未找到关联的 Excel 模板"
        description="请前往「Excel 模板配置」菜单：在某个 Excel 模板上把「关联模板」设为本报价单使用的模板，并发布；同一关联模板内可设一份默认。"
        style={{ margin: 16 }} />
    );
  }
  if (parsedColumns.length === 0) {
    return (
      <Alert type="warning" showIcon
        message="关联的 Excel 模板未配置任何列"
        description={`Excel 模板「${excelTemplate.name}」没有列定义。请前往「Excel 模板配置」打开它，添加列后重试。`}
        style={{ margin: 16 }} />
    );
  }

  const visibleColumns = parsedColumns.filter((col: CostingTemplateColumn) => !col.hidden);
  const tableColumns = [
    {
      title: '客户料号', dataIndex: '__label', key: '__label',
      fixed: 'left' as const, width: 200,
      render: (v: string) => <span style={{ fontFamily: 'monospace' }}>{v}</span>,
    },
    ...visibleColumns.map((col) => ({
      title: (
        <span>
          <span style={{ color: '#999', marginRight: 4 }}>[{col.col_key}]</span>
          {col.title}
        </span>
      ),
      dataIndex: col.col_key, key: col.col_key, width: 140,
      render: (val: any) => {
        if (val === '__loading__') return <span style={{ color: '#bbb' }}>加载中…</span>;
        if (val === null || val === undefined || val === '') return <span style={{ color: '#bbb' }}>—</span>;
        if (typeof val === 'number') return val.toLocaleString();
        return String(val);
      },
    })),
  ];

  return (
    <div>
      <Card size="small" style={{ marginBottom: 12 }}
        title={
          <span>
            {viewLabel || 'Excel 视图'} · <Tag color="blue">{excelTemplate.name}</Tag>
            <Tag>{excelTemplate.version}</Tag>
            {excelTemplate.isDefault && <Tag color="gold">默认</Tag>}
            <Tag color={excelTemplate.status === 'PUBLISHED' ? 'green' : 'default'}>
              {excelTemplate.status === 'PUBLISHED' ? '已发布' : excelTemplate.status}
            </Tag>
          </span>
        }
        extra={<span style={{ fontSize: 12, color: '#999' }}>按本报价单产品行渲染，共 {rows.length} 行</span>}
      />
      <Table rowKey="__key" size="small" columns={tableColumns}
        dataSource={rows} pagination={false} scroll={{ x: 'max-content' }} />
    </div>
  );
};

export default LinkedExcelView;
```

- [ ] **Step 3: TS 编译验证**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json`
Expected: 0 错误。

- [ ] **Step 4: Vite transform 验证（两个改动文件）**

Run: `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/quotation/useLinkedExcelRows.ts && curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/quotation/LinkedExcelView.tsx`
Expected: 两行都 `200`。

- [ ] **Step 5: Playwright E2E 零回归（refactor 的关键证据）**

Run:
```bash
cd cpq-frontend
npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list
```
Expected: 所有 test `passed`，输出含 `'加载中' final count = 0`，8 个 Tab `'加载中'=0`。这是确认 hook 抽取未改变 Excel 视图渲染的硬证据。

- [ ] **Step 6: Commit**

```bash
git add cpq-frontend/src/pages/quotation/useLinkedExcelRows.ts cpq-frontend/src/pages/quotation/LinkedExcelView.tsx
git commit -m "refactor(quotation): 抽取 useLinkedExcelRows 共享 hook, LinkedExcelView 消费之"
```

---

## Task 2: 比对模型纯函数 + 单测（TDD）

**Files:**
- Create: `cpq-frontend/src/pages/quotation/comparisonModel.ts`
- Test: `cpq-frontend/src/pages/quotation/comparisonModel.test.ts`

- [ ] **Step 1: 先写失败的测试**

创建 `cpq-frontend/src/pages/quotation/comparisonModel.test.ts`：

```typescript
import { describe, it, expect } from 'vitest';
import { toNumber, valuesDiffer, buildComparisonModel } from './comparisonModel';
import type { CostingTemplateColumn } from '../../services/costingTemplateService';

const col = (col_key: string, comparison_tag?: string): CostingTemplateColumn =>
  ({ col_key, title: col_key, source_type: 'VARIABLE', comparison_tag } as CostingTemplateColumn);

describe('toNumber', () => {
  it('parses numbers and numeric strings, NaN otherwise', () => {
    expect(toNumber(12)).toBe(12);
    expect(toNumber('12.5')).toBe(12.5);
    expect(toNumber(' 3 ')).toBe(3);
    expect(Number.isNaN(toNumber('abc'))).toBe(true);
    expect(Number.isNaN(toNumber(''))).toBe(true);
    expect(Number.isNaN(toNumber(null))).toBe(true);
  });
});

describe('valuesDiffer', () => {
  it('12 vs 12.0 are equal (no diff)', () => {
    expect(valuesDiffer(12, '12.0')).toBe(false);
  });
  it('within tolerance → no diff', () => {
    expect(valuesDiffer(1, 1.0000005)).toBe(false);
  });
  it('beyond tolerance → diff', () => {
    expect(valuesDiffer(1, 1.1)).toBe(true);
  });
  it('strings trimmed strict equal → no diff', () => {
    expect(valuesDiffer(' abc ', 'abc')).toBe(false);
  });
  it('different strings → diff', () => {
    expect(valuesDiffer('abc', 'abd')).toBe(true);
  });
});

describe('buildComparisonModel', () => {
  const quoteColumns = [col('A', 'MATERIAL'), col('B', 'PROCESS')];
  const costingColumns = [col('X', 'MATERIAL'), col('Y', 'FREIGHT')];
  const tagMetas = [
    { code: 'MATERIAL', label: '材料费', groupName: '成本', groupSortOrder: 1, tagSortOrder: 1 },
    { code: 'PROCESS', label: '加工费', groupName: '成本', groupSortOrder: 1, tagSortOrder: 2 },
    { code: 'FREIGHT', label: '运费', groupName: '其它', groupSortOrder: 2, tagSortOrder: 1 },
  ];

  it('columns = intersection of tags on both sides', () => {
    const m = buildComparisonModel(quoteColumns, [], costingColumns, [], tagMetas);
    expect(m.columns.map((c) => c.tag)).toEqual(['MATERIAL']);
    expect(m.columns[0].label).toBe('材料费');
  });

  it('rows = union of part numbers, with presence flags', () => {
    const quoteRows = [{ __hfPartNo: 'P1', A: 10 }, { __hfPartNo: 'P2', A: 20 }];
    const costingRows = [{ __hfPartNo: 'P1', X: 10 }, { __hfPartNo: 'P3', X: 30 }];
    const m = buildComparisonModel(quoteColumns, quoteRows as any, costingColumns, costingRows as any, tagMetas);
    expect(m.rows.map((r) => r.partNo)).toEqual(['P1', 'P2', 'P3']);
    expect(m.rows.find((r) => r.partNo === 'P1')!.presence).toBe('BOTH');
    expect(m.rows.find((r) => r.partNo === 'P2')!.presence).toBe('QUOTE_ONLY');
    expect(m.rows.find((r) => r.partNo === 'P3')!.presence).toBe('COSTING_ONLY');
  });

  it('highlights only when both present and differ', () => {
    const quoteRows = [{ __hfPartNo: 'P1', A: 10 }, { __hfPartNo: 'P2', A: 20 }];
    const costingRows = [{ __hfPartNo: 'P1', X: 11 }, { __hfPartNo: 'P3', X: 30 }];
    const m = buildComparisonModel(quoteColumns, quoteRows as any, costingColumns, costingRows as any, tagMetas);
    expect(m.rows.find((r) => r.partNo === 'P1')!.cells['MATERIAL'].highlighted).toBe(true);
    // P2 only on quote side → no highlight
    expect(m.rows.find((r) => r.partNo === 'P2')!.cells['MATERIAL'].highlighted).toBe(false);
  });

  it('equal values within tolerance not highlighted', () => {
    const quoteRows = [{ __hfPartNo: 'P1', A: 10 }];
    const costingRows = [{ __hfPartNo: 'P1', X: '10.0' }];
    const m = buildComparisonModel(quoteColumns, quoteRows as any, costingColumns, costingRows as any, tagMetas);
    expect(m.rows[0].cells['MATERIAL'].highlighted).toBe(false);
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-frontend && npx vitest run src/pages/quotation/comparisonModel.test.ts`
Expected: FAIL（`comparisonModel` 模块不存在 / 导出未定义）。

- [ ] **Step 3: 实现 `comparisonModel.ts`**

创建 `cpq-frontend/src/pages/quotation/comparisonModel.ts`：

```typescript
/**
 * comparisonModel —— 比对视图纯函数层。
 * 由报价侧/核价侧的 (parsedColumns, rows) + comparison_tag 元数据，
 * 构建「料号并集 × tag 交集」的双行对比模型，并按数值容差+字符严格判定差异。
 */
import type { CostingTemplateColumn } from '../../services/costingTemplateService';

/** 数值容差常量（集中定义，可调） */
export const ABS_EPS = 1e-6;
export const REL_EPS = 1e-6;

export interface TagMeta {
  code: string;
  label: string;
  groupName?: string;
  groupSortOrder?: number;
  tagSortOrder?: number;
}

export interface ComparisonColumnDef {
  tag: string;
  label: string;
  groupName?: string;
}

export interface ComparisonCellPair {
  quote: any;
  costing: any;
  highlighted: boolean;
}

export type RowPresence = 'BOTH' | 'QUOTE_ONLY' | 'COSTING_ONLY';

export interface ComparisonRowModel {
  partNo: string;
  presence: RowPresence;
  cells: Record<string, ComparisonCellPair>;
}

export interface ComparisonModel {
  columns: ComparisonColumnDef[];
  rows: ComparisonRowModel[];
}

interface ExcelRowLike {
  __hfPartNo?: string;
  [colKey: string]: any;
}

export function toNumber(v: any): number {
  if (typeof v === 'number') return Number.isFinite(v) ? v : NaN;
  if (typeof v === 'string') {
    const t = v.trim();
    if (t === '') return NaN;
    const n = Number(t);
    return Number.isFinite(n) ? n : NaN;
  }
  return NaN;
}

/** 数值容差 + 字符严格：返回「两值是否不同」 */
export function valuesDiffer(a: any, b: any): boolean {
  const na = toNumber(a);
  const nb = toNumber(b);
  if (!Number.isNaN(na) && !Number.isNaN(nb)) {
    const tol = Math.max(ABS_EPS, REL_EPS * Math.max(Math.abs(na), Math.abs(nb)));
    return Math.abs(na - nb) > tol;
  }
  return String(a ?? '').trim() !== String(b ?? '').trim();
}

function isBlank(v: any): boolean {
  return v === null || v === undefined || v === '' || v === '__loading__';
}

/** 该侧某 tag 取值列：列定义顺序第一个打了该 tag 的 col_key */
function tagToColKey(columns: CostingTemplateColumn[], tag: string): string | null {
  for (const c of columns) if (c.comparison_tag === tag) return c.col_key;
  return null;
}

function tagsOf(columns: CostingTemplateColumn[]): Set<string> {
  const s = new Set<string>();
  for (const c of columns) {
    const t = (c.comparison_tag || '').trim();
    if (t) s.add(t);
  }
  return s;
}

export function buildComparisonModel(
  quoteColumns: CostingTemplateColumn[],
  quoteRows: ExcelRowLike[],
  costingColumns: CostingTemplateColumn[],
  costingRows: ExcelRowLike[],
  tagMetas: TagMeta[],
): ComparisonModel {
  const metaByCode: Record<string, TagMeta> = {};
  for (const m of tagMetas) metaByCode[m.code] = m;

  const qTags = tagsOf(quoteColumns);
  const cTags = tagsOf(costingColumns);
  const tags = Array.from(qTags).filter((t) => cTags.has(t));

  tags.sort((a, b) => {
    const ma = metaByCode[a];
    const mb = metaByCode[b];
    const ga = ma?.groupSortOrder ?? 9999;
    const gb = mb?.groupSortOrder ?? 9999;
    if (ga !== gb) return ga - gb;
    const ta = ma?.tagSortOrder ?? 9999;
    const tb = mb?.tagSortOrder ?? 9999;
    if (ta !== tb) return ta - tb;
    return a.localeCompare(b);
  });

  const columns: ComparisonColumnDef[] = tags.map((t) => ({
    tag: t,
    label: metaByCode[t]?.label || t,
    groupName: metaByCode[t]?.groupName,
  }));

  const qColKey: Record<string, string | null> = {};
  const cColKey: Record<string, string | null> = {};
  for (const t of tags) {
    qColKey[t] = tagToColKey(quoteColumns, t);
    cColKey[t] = tagToColKey(costingColumns, t);
  }

  const quoteByPart: Record<string, ExcelRowLike> = {};
  for (const r of quoteRows) {
    const p = r.__hfPartNo;
    if (p && !(p in quoteByPart)) quoteByPart[p] = r;
  }
  const costingByPart: Record<string, ExcelRowLike> = {};
  for (const r of costingRows) {
    const p = r.__hfPartNo;
    if (p && !(p in costingByPart)) costingByPart[p] = r;
  }

  const partOrder: string[] = [];
  const seen = new Set<string>();
  for (const r of quoteRows) {
    const p = r.__hfPartNo;
    if (p && !seen.has(p)) { seen.add(p); partOrder.push(p); }
  }
  for (const r of costingRows) {
    const p = r.__hfPartNo;
    if (p && !seen.has(p)) { seen.add(p); partOrder.push(p); }
  }

  const rows: ComparisonRowModel[] = partOrder.map((partNo) => {
    const qrow = quoteByPart[partNo];
    const crow = costingByPart[partNo];
    const presence: RowPresence = qrow && crow ? 'BOTH' : qrow ? 'QUOTE_ONLY' : 'COSTING_ONLY';
    const cells: Record<string, ComparisonCellPair> = {};
    for (const t of tags) {
      const quote = qrow ? qrow[qColKey[t] as string] : undefined;
      const costing = crow ? crow[cColKey[t] as string] : undefined;
      const highlighted =
        presence === 'BOTH' && !isBlank(quote) && !isBlank(costing) && valuesDiffer(quote, costing);
      cells[t] = { quote, costing, highlighted };
    }
    return { partNo, presence, cells };
  });

  return { columns, rows };
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd cpq-frontend && npx vitest run src/pages/quotation/comparisonModel.test.ts`
Expected: 所有 test PASS。

- [ ] **Step 5: TS 编译验证**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json`
Expected: 0 错误。

- [ ] **Step 6: Commit**

```bash
git add cpq-frontend/src/pages/quotation/comparisonModel.ts cpq-frontend/src/pages/quotation/comparisonModel.test.ts
git commit -m "feat(comparison): 比对模型纯函数(数值容差+料号并集+tag交集)与单测"
```

---

## Task 3: 前端导出 service

**Files:**
- Create: `cpq-frontend/src/services/comparisonExportService.ts`

- [ ] **Step 1: 实现 service**

创建 `cpq-frontend/src/services/comparisonExportService.ts`：

```typescript
import api from './api';
import type { ComparisonModel } from '../pages/quotation/comparisonModel';

export const comparisonExportService = {
  /** POST 已算好的比对模型，后端 POI 只写值+填色，返回 xlsx blob */
  export: (quotationId: string, model: ComparisonModel) =>
    api.post(`/quotations/${quotationId}/comparison/export`, model, { responseType: 'blob' }) as Promise<any>,
};
```

- [ ] **Step 2: TS 编译验证**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json`
Expected: 0 错误。

- [ ] **Step 3: Commit**

```bash
git add cpq-frontend/src/services/comparisonExportService.ts
git commit -m "feat(comparison): 前端导出 service(POST 模型→xlsx blob)"
```

---

## Task 4: 重写 `ComparisonView.tsx`

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/ComparisonView.tsx`（整文件替换）

- [ ] **Step 1: 整文件替换为新实现**

```tsx
import React, { useEffect, useMemo, useState } from 'react';
import { Table, Spin, Alert, Tag, Button, Switch, Space, message } from 'antd';
import { DownloadOutlined } from '@ant-design/icons';
import { useLinkedExcelRows } from './useLinkedExcelRows';
import { buildComparisonModel } from './comparisonModel';
import type { TagMeta } from './comparisonModel';
import { comparisonTagService } from '../../services/comparisonTagService';
import { comparisonExportService } from '../../services/comparisonExportService';
import type { LineItem } from './QuotationStep2';

interface Props {
  quotationId: string;
  customerId?: string;
  quoteTemplateId?: string;
  costingTemplateId?: string;
  quoteLineItems: LineItem[];
  costingLineItems: LineItem[];
}

const HIGHLIGHT_BG = '#fff1f0';

const ComparisonView: React.FC<Props> = ({
  quotationId, customerId, quoteTemplateId, costingTemplateId, quoteLineItems, costingLineItems,
}) => {
  const quote = useLinkedExcelRows({
    linkedTemplateId: quoteTemplateId, lineItems: quoteLineItems, customerId, templateId: quoteTemplateId ?? null,
  });
  const costing = useLinkedExcelRows({
    linkedTemplateId: costingTemplateId, lineItems: costingLineItems, customerId, templateId: costingTemplateId ?? null,
  });

  const [tagMetas, setTagMetas] = useState<TagMeta[]>([]);
  const [onlyDiff, setOnlyDiff] = useState(false);
  const [exporting, setExporting] = useState(false);

  useEffect(() => {
    comparisonTagService.list('ACTIVE')
      .then((r) => setTagMetas((r.data || []).map((t) => ({
        code: t.code, label: t.label, groupName: t.groupName,
        groupSortOrder: t.groupSortOrder, tagSortOrder: t.tagSortOrder,
      }))))
      .catch(() => setTagMetas([]));
  }, []);

  const resolving =
    quote.rows.some((r) => Object.values(r).includes('__loading__')) ||
    costing.rows.some((r) => Object.values(r).includes('__loading__'));

  const model = useMemo(
    () => buildComparisonModel(quote.parsedColumns, quote.rows, costing.parsedColumns, costing.rows, tagMetas),
    [quote.parsedColumns, quote.rows, costing.parsedColumns, costing.rows, tagMetas],
  );

  const visibleRows = useMemo(
    () => onlyDiff
      ? model.rows.filter((r) => Object.values(r.cells).some((c) => c.highlighted))
      : model.rows,
    [model.rows, onlyDiff],
  );

  // 每个料号展开为两行（报价 / 核价）；料号列用 rowSpan 合并
  const dataSource = useMemo(() => {
    const out: any[] = [];
    for (const r of visibleRows) {
      out.push({ key: `${r.partNo}__q`, partNo: r.partNo, presence: r.presence, cells: r.cells, side: 'quote', _span: 2 });
      out.push({ key: `${r.partNo}__c`, partNo: r.partNo, presence: r.presence, cells: r.cells, side: 'costing', _span: 0 });
    }
    return out;
  }, [visibleRows]);

  const columns = useMemo(() => {
    const base: any[] = [
      {
        title: '料号', key: 'partNo', fixed: 'left', width: 200,
        onCell: (rec: any) => ({ rowSpan: rec._span }),
        render: (_: any, rec: any) => (
          <span style={{ fontFamily: 'monospace' }}>
            {rec.partNo}
            {rec.presence !== 'BOTH' && (
              <Tag color="orange" style={{ marginLeft: 6 }}>
                {rec.presence === 'QUOTE_ONLY' ? '仅报价' : '仅核价'}
              </Tag>
            )}
          </span>
        ),
      },
      {
        title: '口径', key: 'side', fixed: 'left', width: 80,
        render: (_: any, rec: any) => (rec.side === 'quote' ? '报价' : '核价'),
      },
    ];
    for (const col of model.columns) {
      base.push({
        title: (
          <span>
            {col.groupName && <span style={{ color: '#999', marginRight: 4 }}>[{col.groupName}]</span>}
            {col.label}
          </span>
        ),
        key: col.tag, width: 140,
        onCell: (rec: any) => (rec.cells[col.tag]?.highlighted ? { style: { background: HIGHLIGHT_BG } } : {}),
        render: (_: any, rec: any) => {
          const pair = rec.cells[col.tag];
          const v = rec.side === 'quote' ? pair?.quote : pair?.costing;
          if (v === null || v === undefined || v === '' || v === '__loading__') return <span style={{ color: '#bbb' }}>—</span>;
          return typeof v === 'number' ? v.toLocaleString() : String(v);
        },
      });
    }
    return base;
  }, [model.columns]);

  const handleExport = async () => {
    setExporting(true);
    try {
      const res = await comparisonExportService.export(quotationId, model);
      const blob = new Blob([res.data], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `comparison-${quotationId}.xlsx`;
      a.click();
      URL.revokeObjectURL(url);
    } catch (e: any) {
      message.error(e?.message || '导出失败');
    } finally {
      setExporting(false);
    }
  };

  if (quote.error || costing.error) {
    return <Alert type="error" showIcon message={quote.error || costing.error || '加载失败'} style={{ margin: 16 }} />;
  }
  if (resolving) return <div style={{ textAlign: 'center', padding: 48 }}><Spin tip="正在求值…" /></div>;

  if (model.columns.length === 0) {
    return (
      <Alert type="warning" showIcon style={{ margin: 16 }}
        message="没有可比对的字段"
        description="报价单 Excel 模板与核价单 Excel 模板没有共同的 comparison_tag。请在两侧 Excel 模板的列上配置相同的业务标签。" />
    );
  }

  return (
    <div>
      <Space style={{ marginBottom: 12 }}>
        <Button icon={<DownloadOutlined />} onClick={handleExport} loading={exporting}>导出比对 Excel</Button>
        <span>仅看有差异的料号 <Switch checked={onlyDiff} onChange={setOnlyDiff} size="small" /></span>
        <span style={{ color: '#999', fontSize: 12 }}>共 {model.rows.length} 个料号，{model.columns.length} 个比对字段</span>
      </Space>
      <Table
        rowKey="key" size="small" bordered
        columns={columns} dataSource={dataSource}
        pagination={false} scroll={{ x: 'max-content' }}
      />
    </div>
  );
};

export default ComparisonView;
```

- [ ] **Step 2: TS 编译验证**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json`
Expected: 0 错误。（注意：此步会因 Task 5 尚未改 `QuotationStep2` 的 `<ComparisonView>` 调用而报 props 缺失错误 —— 这是预期的，下一 Task 修。若希望本步即 0 错误，可与 Task 5 Step 1 合并提交后再跑 tsc。）

- [ ] **Step 3: Commit**

```bash
git add cpq-frontend/src/pages/quotation/ComparisonView.tsx
git commit -m "feat(comparison): 重写比对视图为料号双行对比+单元格高亮+导出按钮"
```

---

## Task 5: `QuotationStep2` 透传 props 给 `ComparisonView`

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/QuotationStep2.tsx:2038-2039`

- [ ] **Step 1: 替换 ComparisonView 挂载处**

把：

```tsx
      {mainTab === 'comparison' && quotationId ? (
        <ComparisonView quotationId={quotationId} />
```

改为：

```tsx
      {mainTab === 'comparison' && quotationId ? (
        <ComparisonView
          quotationId={quotationId}
          customerId={customerId}
          quoteTemplateId={customerTemplateId}
          costingTemplateId={costingCardTemplateId}
          quoteLineItems={quoteLineItems}
          costingLineItems={costingLineItems}
        />
```

（`quoteTemplateId`/`costingTemplateId` 与报价单/核价单 Excel 视图挂载用的 `linkedTemplateId`+`templateId` 完全一致 —— 保证比对值与 Excel 视图逐格相同；`quoteLineItems`/`costingLineItems`/`customerId` 均为本组件已有变量。）

- [ ] **Step 2: TS 编译验证**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json`
Expected: 0 错误。

- [ ] **Step 3: Vite transform 验证**

Run: `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/quotation/QuotationStep2.tsx && curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/quotation/ComparisonView.tsx`
Expected: 两行都 `200`。

- [ ] **Step 4: Playwright E2E（QuotationStep2 协议关键文件改动强制项）**

Run:
```bash
cd cpq-frontend
npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list
```
Expected: 所有 test `passed`，`'加载中' final count = 0`。

- [ ] **Step 5: Commit**

```bash
git add cpq-frontend/src/pages/quotation/QuotationStep2.tsx
git commit -m "feat(comparison): QuotationStep2 透传两侧模板/行/客户给比对视图"
```

---

## Task 6: 后端导出请求 DTO

**Files:**
- Create: `cpq-backend/src/main/java/com/cpq/costing/dto/ComparisonExportRequest.java`

- [ ] **Step 1: 实现 DTO**

字段名必须与前端 `ComparisonModel` JSON 键一致（`columns/rows/tag/label/groupName/partNo/presence/cells/quote/costing/highlighted`）。

```java
package com.cpq.costing.dto;

import java.util.List;
import java.util.Map;

/**
 * 比对视图导出请求体 —— 前端已算好的双行对比模型。
 * 后端 ComparisonExportService 只按此写值+填色，不做任何路径/公式重算（保证与 Excel 视图逐格一致）。
 */
public class ComparisonExportRequest {

    public List<Column> columns;
    public List<Row> rows;

    public static class Column {
        public String tag;
        public String label;
        public String groupName;
    }

    public static class Cell {
        public Object quote;
        public Object costing;
        public boolean highlighted;
    }

    public static class Row {
        public String partNo;
        public String presence;   // BOTH | QUOTE_ONLY | COSTING_ONLY
        public Map<String, Cell> cells;  // key = tag
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/costing/dto/ComparisonExportRequest.java
git commit -m "feat(comparison): 比对导出请求 DTO"
```

---

## Task 7: 后端 POI 导出 service（含单测）

**Files:**
- Create: `cpq-backend/src/main/java/com/cpq/costing/service/ComparisonExportService.java`
- Test: `cpq-backend/src/test/java/com/cpq/costing/ComparisonExportServiceTest.java`

- [ ] **Step 1: 先写失败的单测**

创建 `cpq-backend/src/test/java/com/cpq/costing/ComparisonExportServiceTest.java`（纯 JUnit，不需 Quarkus 容器）：

```java
package com.cpq.costing;

import com.cpq.costing.dto.ComparisonExportRequest;
import com.cpq.costing.service.ComparisonExportService;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ComparisonExportServiceTest {

    private ComparisonExportRequest sampleRequest() {
        ComparisonExportRequest req = new ComparisonExportRequest();

        ComparisonExportRequest.Column c = new ComparisonExportRequest.Column();
        c.tag = "MATERIAL"; c.label = "材料费"; c.groupName = "成本";
        req.columns = List.of(c);

        ComparisonExportRequest.Cell cell = new ComparisonExportRequest.Cell();
        cell.quote = 10; cell.costing = 11; cell.highlighted = true;

        ComparisonExportRequest.Row row = new ComparisonExportRequest.Row();
        row.partNo = "P1"; row.presence = "BOTH";
        row.cells = Map.of("MATERIAL", cell);

        req.rows = List.of(row);
        return req;
    }

    @Test
    void writesTwoRowsPerPartWithValues() throws Exception {
        byte[] bytes = new ComparisonExportService().export(sampleRequest());
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            // row0 = header; row1 = 报价行; row2 = 核价行
            Row reportRow = sheet.getRow(1);
            Row costingRow = sheet.getRow(2);
            assertEquals("报价", reportRow.getCell(1).getStringCellValue());
            assertEquals("核价", costingRow.getCell(1).getStringCellValue());
            assertEquals(10.0, reportRow.getCell(2).getNumericCellValue(), 1e-9);
            assertEquals(11.0, costingRow.getCell(2).getNumericCellValue(), 1e-9);
        }
    }

    @Test
    void highlightedCellsHaveFill() throws Exception {
        byte[] bytes = new ComparisonExportService().export(sampleRequest());
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            Cell quoteCell = sheet.getRow(1).getCell(2);
            assertEquals(FillPatternType.SOLID_FOREGROUND, quoteCell.getCellStyle().getFillPattern(),
                    "差异格应有实心填充");
        }
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-backend && ./mvnw -q test -Dtest=ComparisonExportServiceTest`
Expected: 编译失败 / 测试失败（`ComparisonExportService` 不存在）。

- [ ] **Step 3: 实现 service**

创建 `cpq-backend/src/main/java/com/cpq/costing/service/ComparisonExportService.java`：

```java
package com.cpq.costing.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.costing.dto.ComparisonExportRequest;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 比对视图 Excel 导出 —— 只按前端传来的已算好模型写值+填色，不重算任何路径/公式。
 * 布局：表头(料号 | 口径 | 各 tag 标签)，每个料号两行(报价/核价)，料号列纵向合并，差异格填充底色。
 */
@ApplicationScoped
public class ComparisonExportService {

    public byte[] export(ComparisonExportRequest req) {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("比对");
            CellStyle headerStyle = headerStyle(wb);
            CellStyle highlightStyle = highlightStyle(wb);

            List<ComparisonExportRequest.Column> cols = req.columns != null ? req.columns : List.of();
            List<ComparisonExportRequest.Row> rows = req.rows != null ? req.rows : List.of();

            // 列宽
            sheet.setColumnWidth(0, 6000);
            sheet.setColumnWidth(1, 2600);
            for (int i = 0; i < cols.size(); i++) sheet.setColumnWidth(2 + i, 4200);

            // 表头
            Row header = sheet.createRow(0);
            createCell(header, 0, "料号", headerStyle);
            createCell(header, 1, "口径", headerStyle);
            for (int i = 0; i < cols.size(); i++) {
                ComparisonExportRequest.Column col = cols.get(i);
                String label = col.label != null ? col.label : col.tag;
                if (col.groupName != null && !col.groupName.isEmpty()) label = "[" + col.groupName + "] " + label;
                createCell(header, 2 + i, label, headerStyle);
            }

            int r = 1;
            for (ComparisonExportRequest.Row row : rows) {
                Row reportRow = sheet.createRow(r);
                Row costingRow = sheet.createRow(r + 1);

                String partLabel = row.partNo != null ? row.partNo : "";
                if (!"BOTH".equals(row.presence)) {
                    partLabel += "QUOTE_ONLY".equals(row.presence) ? " (仅报价)" : " (仅核价)";
                }
                reportRow.createCell(0).setCellValue(partLabel);
                sheet.addMergedRegion(new CellRangeAddress(r, r + 1, 0, 0)); // 料号纵向合并

                reportRow.createCell(1).setCellValue("报价");
                costingRow.createCell(1).setCellValue("核价");

                Map<String, ComparisonExportRequest.Cell> cells = row.cells != null ? row.cells : Map.of();
                for (int i = 0; i < cols.size(); i++) {
                    ComparisonExportRequest.Cell cell = cells.get(cols.get(i).tag);
                    int c = 2 + i;
                    Object qv = cell != null ? cell.quote : null;
                    Object cv = cell != null ? cell.costing : null;
                    boolean hl = cell != null && cell.highlighted;
                    writeValue(reportRow.createCell(c), qv, hl ? highlightStyle : null);
                    writeValue(costingRow.createCell(c), cv, hl ? highlightStyle : null);
                }
                r += 2;
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BusinessException(500, "导出比对 Excel 失败: " + e.getMessage());
        }
    }

    private void writeValue(Cell cell, Object v, CellStyle style) {
        if (v instanceof Number n) {
            cell.setCellValue(n.doubleValue());
        } else if (v != null) {
            String s = String.valueOf(v);
            // 数值型字符串按数字写，其余按文本
            try {
                cell.setCellValue(Double.parseDouble(s.trim()));
            } catch (NumberFormatException ex) {
                cell.setCellValue(s);
            }
        }
        if (style != null) cell.setCellStyle(style);
    }

    private void createCell(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value);
        if (style != null) cell.setCellStyle(style);
    }

    private CellStyle headerStyle(XSSFWorkbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_50_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    private CellStyle highlightStyle(XSSFWorkbook wb) {
        CellStyle style = wb.createCellStyle();
        style.setFillForegroundColor(IndexedColors.ROSE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd cpq-backend && ./mvnw -q test -Dtest=ComparisonExportServiceTest`
Expected: 2 个 test PASS。

- [ ] **Step 5: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/costing/service/ComparisonExportService.java cpq-backend/src/test/java/com/cpq/costing/ComparisonExportServiceTest.java
git commit -m "feat(comparison): 后端 POI 导出 service(只格式化不重算)+单测"
```

---

## Task 8: 后端导出端点

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/costing/resource/CostingSheetResource.java`

- [ ] **Step 1: 加 import + 注入 + 端点**

在 `CostingSheetResource.java` 顶部 import 区加：

```java
import com.cpq.costing.dto.ComparisonExportRequest;
import com.cpq.costing.service.ComparisonExportService;
import jakarta.ws.rs.core.Response;
```

在类内 `@Inject CostingSheetService service;` 下面加：

```java
    @Inject
    ComparisonExportService comparisonExportService;
```

在 `getComparison` 方法后加：

```java
    @POST
    @Path("/{id}/comparison/export")
    @Produces("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
    public Response exportComparison(@PathParam("id") UUID quotationId, ComparisonExportRequest req) {
        byte[] xlsx = comparisonExportService.export(req);
        return Response.ok(xlsx)
                .header("Content-Disposition", "attachment; filename=\"comparison-" + quotationId + ".xlsx\"")
                .build();
    }
```

（注意：类级 `@Produces(MediaType.APPLICATION_JSON)` 被方法级 `@Produces` 覆盖；类级 `@Consumes(APPLICATION_JSON)` 正好用于反序列化请求体。）

- [ ] **Step 2: 触发 Quarkus 重启 + 健康检查**

Run:
```bash
cd cpq-backend && touch src/main/java/com/cpq/costing/resource/CostingSheetResource.java && sleep 7
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8081/q/health
```
Expected: `200`。

- [ ] **Step 3: 端点冒烟（含最小 payload）**

Run（需替换 `<TOKEN>` 与 `<QID>` 为本地可用值；若本地无鉴权可去掉 Authorization 头，期望 200 而非 500）：
```bash
curl -s -o /tmp/cmp.xlsx -w "%{http_code}\n" -X POST \
  -H "Content-Type: application/json" \
  -d '{"columns":[{"tag":"MATERIAL","label":"材料费"}],"rows":[{"partNo":"P1","presence":"BOTH","cells":{"MATERIAL":{"quote":10,"costing":11,"highlighted":true}}}]}' \
  "http://localhost:8081/api/cpq/quotations/<QID>/comparison/export"
file /tmp/cmp.xlsx
```
Expected: HTTP `200`（或鉴权 `401`，不能 `500`）；`file` 显示为 `Microsoft Excel 2007+` / Zip 档。

- [ ] **Step 4: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/costing/resource/CostingSheetResource.java
git commit -m "feat(comparison): 新增 POST /quotations/{id}/comparison/export 端点"
```

---

## Task 9: 旧 buildComparison 链路处置（确认保留）

**Files:**
- Modify: `cpq-frontend/src/services/costingSheetService.ts`（仅加注释，不删方法）

- [ ] **Step 1: grep 确认旧端点引用面**

Run: `cd /home/joii/project/cpq && grep -rn "getComparison\|buildComparison\|/comparison\"" cpq-backend/src cpq-frontend/src`
Expected 结论：`buildComparison`/`GET /comparison`/`ComparisonDTO` 仍被 `CostingComparisonResourceTest`（承载 TDD §10 COST-COMPARE 覆盖）引用 → **保留不删**；前端 `costingSheetService.getComparison` 重写后不再被 `ComparisonView` 调用。

- [ ] **Step 2: 给 `costingSheetService.getComparison` 加保留说明注释**

在 `cpq-frontend/src/services/costingSheetService.ts` 的 `getComparison` 定义上方加一行注释：

```typescript
  // 注：旧的 tag 分组比对端点。新「料号双行比对视图」(ComparisonView) 不再调用此方法，
  // 但后端 GET /comparison + buildComparison 仍被 CostingComparisonResourceTest 覆盖, 保留。
```

- [ ] **Step 3: TS 编译验证**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json`
Expected: 0 错误。

- [ ] **Step 4: Commit**

```bash
git add cpq-frontend/src/services/costingSheetService.ts
git commit -m "chore(comparison): 标注旧 getComparison 保留(供后端测试覆盖), 新视图不再调用"
```

---

## Task 10: 终验 + 文档回写 + 自检声明

**Files:**
- Modify: `docs/RECORD.md`

- [ ] **Step 1: 前端全量编译 + 关键页面 200**

Run:
```bash
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/quotation/ComparisonView.tsx
```
Expected: tsc 0 错误；两个 200。

- [ ] **Step 2: 前端单测**

Run: `cd cpq-frontend && npx vitest run src/pages/quotation/comparisonModel.test.ts`
Expected: 全 PASS。

- [ ] **Step 3: 后端单测**

Run: `cd cpq-backend && ./mvnw -q test -Dtest=ComparisonExportServiceTest`
Expected: 全 PASS。

- [ ] **Step 4: Playwright E2E（最终回归证据）**

Run:
```bash
cd cpq-frontend
npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list
```
Expected: 所有 test `passed`，`'加载中' final count = 0`。

- [ ] **Step 5: 人工验收（比对视图）**

打开一张含报价单+核价单且两侧 Excel 模板有共同 `comparison_tag` 的报价单 → 切到「比对视图」：
- 每个料号两行（报价/核价），料号列纵向合并。
- 同 tag 两侧值不同 → 报价行与核价行两格都高亮。
- 仅一侧存在的料号 → 料号处显示「仅报价/仅核价」标签、另一侧留空。
- 点「导出比对 Excel」→ 下载的 xlsx 值与高亮格 = 页面所见（抽样比对）。

- [ ] **Step 6: 回写 RECORD.md**

在 `docs/RECORD.md` 末尾追加一行：

```
[2026-05-29] 报价单/比对视图 - 重构比对视图为料号双行对比+单元格高亮+导出Excel(方案A:抽 useLinkedExcelRows 共享 hook 保证与 Excel 视图逐格一致, comparison_tag 交集成列, 料号并集双行, 数值容差+字符严格 diff, 后端 POI 只格式化不重算) | useLinkedExcelRows.ts/LinkedExcelView.tsx/comparisonModel.ts(+test)/ComparisonView.tsx/QuotationStep2.tsx/comparisonExportService.ts/ComparisonExportRequest.java/ComparisonExportService.java(+test)/CostingSheetResource.java | 旧 buildComparison 链路保留(供 CostingComparisonResourceTest 覆盖), 新视图不再调用 getComparison
```

- [ ] **Step 7: Commit + 自检声明**

```bash
git add docs/RECORD.md
git commit -m "docs(record): 记录比对视图重构(料号双行+高亮+导出)"
```

收尾报告必须带一行"已自检"声明，例如：
> TS 0 错误 ✅；ComparisonView.tsx/useLinkedExcelRows.ts/QuotationStep2.tsx → Vite 200 ✅；comparisonModel.test.ts 全 PASS ✅；ComparisonExportServiceTest 全 PASS ✅；quotation-flow.spec.ts 全 passed、'加载中'=0 ✅；POST /comparison/export → 200 + 合法 xlsx ✅

---

## Self-Review

**1. Spec coverage（逐条对照设计文档 §3 决策表 + 各节）**
- 替换旧比对视图 → Task 4/5（重写 ComparisonView + 透传）；旧链路保留决策 Task 9 ✅
- 按 comparison_tag 匹配 / 列=交集 → `comparisonModel.buildComparisonModel`（Task 2）✅
- 同侧 tag 多列取第一个 → `tagToColKey` 取列序第一个（Task 2）✅
- 料号并集双行 / 单边留空+标签 → `buildComparisonModel` partOrder 并集 + presence；ComparisonView rowSpan 双行 + 「仅报价/仅核价」标签（Task 2/4）✅
- 数值容差+字符严格 → `valuesDiffer` + ABS_EPS/REL_EPS（Task 2）✅
- 两格都高亮 → `cells[t].highlighted` + 列 `onCell` 不分 side 取 pair.highlighted（Task 2/4）✅
- 值严格一致（复用同一计算路径）→ `useLinkedExcelRows` 抽取 + ComparisonView 用与 Excel 视图相同入参调用（Task 1/4/5）✅
- 导出 Excel 含高亮、后端不重算 → Task 6/7/8（DTO/POI service/端点）✅
- 测试计划（E2E 回归 / 模型单测 / 渲染验收 / 导出验收）→ Task 1 Step5、Task 2、Task 10 ✅

**2. Placeholder scan:** 无 TBD/TODO；每个代码步均含完整代码与命令。✅

**3. Type consistency:**
- 前端 `ComparisonModel`/`ComparisonColumnDef`/`ComparisonCellPair`/`RowPresence` 在 Task 2 定义，Task 3/4 一致引用 ✅
- `useLinkedExcelRows` 返回 `{rows, parsedColumns, excelTemplate, loading, error}` 在 Task 1 定义，Task 1 Step2 / Task 4 一致消费 ✅
- 后端 `ComparisonExportRequest`(Column/Cell/Row 字段名)与前端 JSON 键一致；Task 7 service 与 Task 8 端点签名一致 ✅
- 函数命名一致：`buildComparisonModel`/`valuesDiffer`/`toNumber`/`export` 全程一致 ✅
- 已知预期编译态：Task 4 Step2 的 tsc 在 Task 5 前会报 `<ComparisonView>` props 缺失（已在该步注明），Task 5 后归零。
