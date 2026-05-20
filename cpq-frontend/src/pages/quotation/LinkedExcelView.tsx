/**
 * LinkedExcelView —— 按 (linkedTemplateId → costing_template) 渲染的 Excel 视图
 *
 * 流程：
 *   1. 根据 linkedTemplateId 反查 costing_template 列表（status='PUBLISHED'）
 *   2. 优先用 is_default=true 的那一份；否则 createdAt 倒序第一份
 *   3. 按 costing_template.columns 构造表头：每列两类
 *      - VARIABLE：在 lineItem / 报价单上下文里按变量名取值
 *      - FORMULA：引用本行其他列 [X] 或变量 {CODE}
 *   4. 每个 lineItem 一行
 *
 * 用途：替换旧的 <CostingSheetView>（按 costing_sheet 表）—— V72 起新建报价单不再自动建 costing_sheet 行；
 * 改为按"模板配置 → 关联 Excel 模板"双层 lookup 渲染（V73 新增字段，V74 移除产品分类后是默认调用方式）。
 */
import React, { useEffect, useMemo, useState } from 'react';
import { Table, Card, Spin, Alert, Tag } from 'antd';
import { templateService } from '../../services/templateService';
import type { CostingTemplate, CostingTemplateColumn } from '../../services/costingTemplateService';
import { batchEvaluate, buildEvalKey } from '../../services/formulaService';
import type { LineItem } from './QuotationStep2';

/** BNF path cache key: `${partNo}::${path}` */
const pathCacheKey = (partNo: string, path: string) => `${partNo}::${path}`;

interface Props {
  /** 反查 linked_template_id 的目标模板 ID */
  linkedTemplateId?: string;
  /** 当前报价单的产品行 —— 每个 lineItem 渲染一行 */
  lineItems: LineItem[];
  /** 报价单上下文（提供 quotation 级变量取值，比如 base_currency / customer_name） */
  quotationContext?: Record<string, any>;
  /** 视图标识：用于错误文案区分"报价单 Excel 视图"还是"核价单 Excel 视图" */
  viewLabel?: string;
  /** 当前报价单的客户 ID —— 求 BNF 路径（含 customer_id 谓词的客户级表）时透传 */
  customerId?: string;
}

/**
 * 判断 variable_path 是不是"老 `{CODE}` 简写格式"（V73 落地时的过渡格式）。
 * V75 起改为复用 PathPickerDrawer，存入 BNF 路径字符串（如 `mat_part.unit_weight`）；
 * 老数据仍按 `{CODE}` 格式按 lineItem 字段映射处理。
 */
function isLegacyVarCode(s: string | undefined): boolean {
  return !!s && /^\{[^}]+\}$/.test(s.trim());
}

/**
 * 解析单个 VARIABLE 列的值。
 * variable_path 形如 `{customer_drawing_no}` 或 `{HF_PART_NO}`，去括号 + 转小写后按 lineItem / quotationContext 字段查找。
 *
 * 暂时硬编码常用映射；后续可以改为从 basic_data_attribute 实际字段拉，或后端按变量 code 求值的接口。
 */
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
  // lineItem 上的常见字段（snake_case → camelCase 映射）
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
  // productAttributeValues 也允许引用（用户在产品属性里填的值，按 attr.name 取）
  if (li.productAttributeValues && Object.prototype.hasOwnProperty.call(li.productAttributeValues, code)) {
    return li.productAttributeValues[code];
  }
  // 报价单上下文（caller 透传）
  if (Object.prototype.hasOwnProperty.call(quotationContext, code)) return quotationContext[code];
  // 系统级常量兜底
  const sysMap: Record<string, any> = {
    'base_currency': 'USD',
    'system_date': new Date().toISOString().slice(0, 10),
  };
  if (Object.prototype.hasOwnProperty.call(sysMap, code)) return sysMap[code];
  return null;
}

/** 公式求值：替换列引用 [X] 与变量 {CODE} 后，用 Function 构造器计算数值 */
function evaluateFormula(
  formula: string | undefined,
  rowCellValues: Record<string, any>,
  rowVariableValues: Record<string, any>,
): number | string {
  if (!formula) return '';
  let expr = formula.trim();
  if (expr.startsWith('=')) expr = expr.slice(1);
  // 列引用 [A] → 当前行 A 列已求值的数值
  expr = expr.replace(/\[([A-Za-z][A-Za-z0-9_]*)\]/g, (_, k) => {
    const v = rowCellValues[k];
    if (typeof v === 'number') return String(v);
    const n = parseFloat(v as any);
    return isNaN(n) ? '0' : String(n);
  });
  // 变量 {CODE} → 当前行已 resolved 的变量值
  expr = expr.replace(/\{([^}]+)\}/g, (_, k) => {
    const code = (k as string).trim().toLowerCase();
    const v = rowVariableValues[code];
    if (typeof v === 'number') return String(v);
    const n = parseFloat(v as any);
    return isNaN(n) ? '0' : String(n);
  });
  try {
    // 限制只允许数字 + 运算符 + 括号 + 空格 + 小数点 + 比较 / 简单数学
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

const LinkedExcelView: React.FC<Props> = ({ linkedTemplateId, lineItems, quotationContext, viewLabel, customerId }) => {
  const [excelTemplate, setExcelTemplate] = useState<CostingTemplate | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // BNF path 求值缓存：key = `${partNo}::${path}` → 后端 /formulas/evaluate 返回值
  const [pathCache, setPathCache] = useState<Record<string, any>>({});

  useEffect(() => {
    if (!linkedTemplateId) {
      setExcelTemplate(null);
      return;
    }
    setLoading(true);
    setError(null);
    // V149 Stage 3 / V150: Excel 视图配置直接从 template.excel_view_config 读
    // (老的"反查 costing_template 表"路径在 V150 合并后失效, 改为直接读模板自身)
    templateService
      .getExcelViewConfig(linkedTemplateId)
      .then((r: any) => {
        // ApiResponse 信封解包: r.data 可能是 array (V150 后) 或 {columns:[]} (V150 前兼容)
        const raw = r?.data ?? r;
        const cols = Array.isArray(raw)
          ? raw
          : Array.isArray(raw?.columns) ? raw.columns : [];
        if (cols.length === 0) {
          setExcelTemplate(null);
          return;
        }
        // 复用 parsedColumns 下游逻辑: 把 cols 包装成"伪 CostingTemplate", columns 序列化字符串
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

  // 收集所有需要后端求值的 (partNo, BNF path) 对（VARIABLE 列里非 `{CODE}` 的是 BNF）
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
        if (isLegacyVarCode(vp)) continue; // 老 `{CODE}` 走前端 lineItem 字段映射，不调后端
        const k = pathCacheKey(partNo, vp);
        if (seen.has(k)) continue;
        seen.add(k);
        out.push({ partNo, path: vp });
      }
    }
    return out;
  }, [lineItems, parsedColumns]);

  // 异步批量求值缺失的 path — 改成一次 batch-evaluate 调用(后端 PathBatchEvaluator
  // 按 path 聚合同 path 多 partNo 为 1 条 IN SQL,N×M 请求 → 1 个 HTTP)。
  useEffect(() => {
    const missing = pathTasks.filter((t) => !(pathCacheKey(t.partNo, t.path) in pathCache));
    if (missing.length === 0) return;

    const tasks = missing.map((t) => ({
      expression: `{${t.path}}`,
      customerId: customerId || null,
      partNo: t.partNo,
    }));

    batchEvaluate(tasks)
      .then((items) => {
        // 用后端返回的 key 反查 partNo+path,失败 task 也要回填 null 避免反复重试
        const itemByKey: Record<string, typeof items[number]> = {};
        for (const it of items) itemByKey[it.key] = it;

        setPathCache((prev) => {
          const next = { ...prev };
          for (const t of missing) {
            const expr = `{${t.path}}`;
            const reqKey = buildEvalKey(expr, customerId || null, t.partNo);
            const item = itemByKey[reqKey];
            const cacheK = pathCacheKey(t.partNo, t.path);
            if (item && item.status === 'OK' && item.data?.success) {
              next[cacheK] = item.data.result ?? null;
            } else {
              // 求值失败/未匹配 → 标记 null 避免反复重试,UI 显示 '—'
              next[cacheK] = null;
            }
          }
          return next;
        });
      })
      .catch(() => {
        // 网络层失败:全体标 null 防 retry 循环
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
  }, [pathTasks, customerId]);

  /** 取 BNF 路径求值结果 —— 数组取首值，对象取首字段，字符串/数字直接返回 */
  const formatPathValue = (v: any): any => {
    if (v == null) return null;
    if (typeof v === 'number' || typeof v === 'string' || typeof v === 'boolean') return v;
    if (Array.isArray(v)) {
      if (v.length === 0) return null;
      return v.length === 1 ? formatPathValue(v[0]) : `${formatPathValue(v[0])}（共${v.length}项）`;
    }
    if (typeof v === 'object') {
      // V197 hotfix: PG JDBC jsonb 列读成 PGobject {type:'jsonb', value:'<json>'}.
      // 单 cell 内 JSONB 数组/对象应完整展开 (而不是 driver-expand 通用 "首值+共N项")
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
  };

  // 每行数据：lineItem → { [col_key]: 求值后的值 }
  const rows = useMemo(() => {
    if (parsedColumns.length === 0) return [];
    const ctx = quotationContext || {};
    return (lineItems || []).map((li, i) => {
      const cellValues: Record<string, any> = {};
      const varValues: Record<string, any> = {};
      const partNo = li.productPartNo;
      // 第一遍：resolve VARIABLE 列
      for (const col of parsedColumns) {
        if (col.source_type !== 'VARIABLE') continue;
        const vp = (col.variable_path || '').trim();
        if (!vp) {
          cellValues[col.col_key] = null;
          continue;
        }
        if (isLegacyVarCode(vp)) {
          // 老 `{CODE}` 走前端 lineItem 字段映射
          const v = resolveVariable(vp, li, ctx);
          cellValues[col.col_key] = v;
          const m = vp.match(/^\{([^}]+)\}$/);
          if (m) varValues[m[1].trim().toLowerCase()] = v;
        } else {
          // BNF path → 后端求值缓存查
          if (!partNo) {
            cellValues[col.col_key] = null;
            continue;
          }
          const k = pathCacheKey(partNo, vp);
          if (!Object.prototype.hasOwnProperty.call(pathCache, k)) {
            // 还在异步求值中 → 用占位符；等 pathCache 更新后会重新 useMemo
            cellValues[col.col_key] = '__loading__';
          } else {
            cellValues[col.col_key] = formatPathValue(pathCache[k]);
          }
        }
      }
      // V111: 在算公式前判断该料号是否真有核价数据.
      // 检测标准: 所有"非 legacy `{CODE}`"VARIABLE 列都是 null/undefined → 无核价数据
      // (legacy `{HF_PART_NO}` 等只是元数据, 不算"核价数据")
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
        // 整行无数据 — 全部列填 null, FORMULA 列也不计算 (避免 0 + 0 + ... 显示成 0)
        for (const col of parsedColumns) {
          cellValues[col.col_key] = null;
        }
      } else {
        // 第二遍：FORMULA 列引用 cellValues / varValues
        for (const col of parsedColumns) {
          if (col.source_type === 'FORMULA') {
            cellValues[col.col_key] = evaluateFormula(col.formula, cellValues, varValues);
          }
        }
      }
      // 业务约定: "产品"列优先显示客户料号 (客户看报价单时使用的料号),
      // HF 内部料号作为 fallback. 两者都缺时再回落到产品名 / 序号.
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
        __hfPartNo: li.productPartNo, // 透传给 render 显示二级信息
        __noData: noCostingData,
        ...cellValues,
      };
    });
  }, [parsedColumns, lineItems, quotationContext, pathCache]);

  if (!linkedTemplateId) {
    return (
      <Alert
        type="info"
        showIcon
        message={`本报价单未绑定${viewLabel || ''}模板`}
        description="无法定位关联 Excel 模板。请先确认报价单已选择模板。"
        style={{ margin: 16 }}
      />
    );
  }

  if (loading) return <div style={{ textAlign: 'center', padding: 48 }}><Spin /></div>;
  if (error) return <Alert type="error" showIcon message={error} style={{ margin: 16 }} />;
  if (!excelTemplate) {
    return (
      <Alert
        type="warning"
        showIcon
        message="未找到关联的 Excel 模板"
        description="请前往「Excel 模板配置」菜单：在某个 Excel 模板上把「关联模板」设为本报价单使用的模板，并发布；同一关联模板内可设一份默认。"
        style={{ margin: 16 }}
      />
    );
  }
  if (parsedColumns.length === 0) {
    return (
      <Alert
        type="warning"
        showIcon
        message="关联的 Excel 模板未配置任何列"
        description={`Excel 模板「${excelTemplate.name}」没有列定义。请前往「Excel 模板配置」打开它，添加列后重试。`}
        style={{ margin: 16 }}
      />
    );
  }

  // V86: 隐藏列(hidden=true)仍参与 FORMULA 求值链路, 但不出现在 tableColumns 里 → 不在 UI 展示
  const visibleColumns = parsedColumns.filter((col) => !col.hidden);
  const tableColumns = [
    {
      title: '客户料号',
      dataIndex: '__label',
      key: '__label',
      fixed: 'left' as const,
      width: 200,
      render: (v: string) => (
        <span style={{ fontFamily: 'monospace' }}>{v}</span>
      ),
    },
    ...visibleColumns.map((col) => ({
      title: (
        <span>
          <span style={{ color: '#999', marginRight: 4 }}>[{col.col_key}]</span>
          {col.title}
        </span>
      ),
      dataIndex: col.col_key,
      key: col.col_key,
      width: 140,
      render: (val: any) => {
        if (val === '__loading__') return <span style={{ color: '#bbb' }}>加载中…</span>;
        if (val === null || val === undefined || val === '') {
          return <span style={{ color: '#bbb' }}>—</span>;
        }
        if (typeof val === 'number') return val.toLocaleString();
        return String(val);
      },
    })),
  ];

  return (
    <div>
      <Card
        size="small"
        style={{ marginBottom: 12 }}
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
        extra={
          <span style={{ fontSize: 12, color: '#999' }}>
            按本报价单产品行渲染，共 {rows.length} 行
          </span>
        }
      />
      <Table
        rowKey="__key"
        size="small"
        columns={tableColumns}
        dataSource={rows}
        pagination={false}
        scroll={{ x: 'max-content' }}
      />
    </div>
  );
};

export default LinkedExcelView;
