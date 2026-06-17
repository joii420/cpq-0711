/**
 * ComponentCell.tsx — 共享单元格渲染组件
 *
 * 把 QuotationStep2 (编辑态) 与 ReadonlyProductCard (只读态) 中重复的 6 类字段渲染逻辑
 * 收敛到一处，消除二者之间的渲染分支不一致（AP-45 / 双轨问题）。
 *
 * 6 类字段渲染策略：
 *   FORMULA      → formulaCache[name] ?? '—'
 *   LIST_FORMULA → evaluateListFormulaString（含 configTemplates 解析）
 *   BASIC_DATA   → 统一 fallback 链（@gvar:CODE → BNF → pathCache → content → row[key]）
 *   DATA_SOURCE  → binding.type 分发；DATABASE_QUERY 退化 row[key] ?? '—'
 *   FIXED_VALUE  → row[key] ?? field.content ?? '—'
 *   INPUT_*      → readonly=true: 只读文本; readonly=false: <input>（含 V190 default_source placeholder）
 *
 * 统一 fallback 链（BASIC_DATA / DATA_SOURCE.GLOBAL_VARIABLE / INPUT_*.default_source.GLOBAL_VARIABLE 共用）：
 *   1. basicDataValues['@gvar:CODE']           非空 → 显示（badge: 🌐 CODE）
 *   2. basicDataValues[bnfDriverLookupKey(path)] 非空 → 显示（badge: BNF）
 *   3. pathCacheState[`${partNo}::${path}`]    非空 → 显示
 *   4. field.content                           非空 → 显示（badge: 默认）
 *   5. row[key]                                非空 → 显示（兼容历史持久化，readonly=true 加 title="历史值"）
 *   6. '—'
 */
import React from 'react';
import { evaluateListFormulaString } from '../../../utils/formulaEngine';
import { bnfDriverLookupKey } from '../useDriverExpansions';
import { evaluateCondition } from '../../../utils/conditionEngine';
import type { ComponentField, ComponentDataItem } from '../QuotationStep2';
import type { PathCache } from '../usePathFormulaCache';
import type { GlobalVariableDefinition } from '../../../services/globalVariableService';
import type { ConfigTemplateMap } from '../useConfigTemplates';
import { formatPathValue } from './formatPathValue';
import { resolveInputDefault } from '../inputDefaults';

// re-export 供已有 `import { formatPathValue } from '.../ComponentCell'` 的调用方使用
export { formatPathValue } from './formatPathValue';

// ─── 上下文接口 ──────────────────────────────────────────────────────────────

export interface CellContext {
  /** driver 展开后的行级 basicDataValues — key=`{path}` 或 `@gvar:CODE` */
  basicDataValues?: Record<string, any>;
  /** 模块级 globalPathCache 引用 (`pathCacheKey(partNo, path)` 格式) */
  pathCacheState: PathCache;
  /** 按行预计算的 FORMULA 值 map（computeAllFormulas 输出） */
  formulaCache: Record<string, number | null>;
  /**
   * 按行预计算的 FORMULA 错误旁路 map（computeAllFormulas out.errors 输出）。
   * 不传 = 旧行为。某字段有此条目 = cross_tab_ref 细项多命中等错误,数值已静默归 0,
   * 渲染层据此显示 ⚠ 错误态 + tooltip(替代误导的 0)。
   */
  formulaErrors?: Record<string, string>;
  /** 当前行所属产品的料号 */
  partNo: string | undefined;
  /** 当前激活的组件 */
  activeComponent: ComponentDataItem;
  /** driver 展开结果摘要（rowCount 用于 AP-38 鬼魂行判断） */
  activeDriverExpansion?: { rowCount: number };
  /** 当前行是否由 LIST_FORMULA 驱动（用于跳过 globalPathCache 回退） */
  isListFormulaBound?: boolean;
  /** 当前行是否由 dataDriver 展开（BASIC_DATA 行级 lookup 已准备好） */
  isDriverBound?: boolean;
  /**
   * 当前行是否为用户手动新增的空白行（Phase 1 手动新增行）。
   * 为 true 时，FIXED_VALUE 渲染成可填文本框，DATA_SOURCE 渲染成可填文本框（Phase 1.1 接入下拉）。
   * 只读态（readonly=true）不受影响。
   */
  isManualRow?: boolean;
  /**
   * 核价 BOM 递归展开（P1）：当前行是否为 BOM 树 spine 行。
   * 为 true 时，BASIC_DATA/DATA_SOURCE 的权威数据 = 本行 basicDataValues（按本子料号取）；
   * 缺值 → 直接 "—"，<b>不</b>回退到按根料号键的 globalPathCache（那是单料号/新行逻辑，对子料号行语义错误，会误显示根料号值或永久"加载中"）。
   */
  isBomTreeRow?: boolean;
  /** LIST_FORMULA 模式下本行对应的 config_item（包含 code / name / defaultValue 等） */
  listFormulaItem?: {
    code: string;
    name: string;
    sortOrder?: number;
    defaultValue?: string | number | null;
  };
  /** 当前组件内的 LIST_FORMULA 字段（用于共存模式判断） */
  listFormulaField?: ComponentField;
  /** 全局变量定义字典（CODE → def），供 FORMULA/INPUT_*.default_source.GLOBAL_VARIABLE 使用 */
  globalVariableDefs?: Record<string, GlobalVariableDefinition>;
  /** config 模板数据（key = config_template_id） */
  configTemplates?: ConfigTemplateMap;

  // ── 仅 readonly=false 使用 ──
  /** DATABASE_QUERY 类型字段的 loading 状态 map（key = `${tabIndex}-${rowIndex}-${fieldName}`） */
  dsLoading?: Record<string, boolean>;
  /** DATABASE_QUERY 类型字段的错误信息 map */
  dsErrors?: Record<string, string>;
  /** 编辑态：cell 值变化回调 */
  onCellChange?: (rowIndex: number, key: string, value: any) => void;
  /** 编辑态：cell 失焦回调（触发 autoSave） */
  onCellBlur?: (rowIndex: number, key: string) => void;
  /** 编辑态：dsLoading/dsErrors 的 key 前缀（`${tabIndex}-${rowIndex}`） */
  dsStateKey?: string;
}

export interface ComponentCellProps {
  field: ComponentField;
  row: Record<string, any>;
  rowIndex: number;
  fieldKey: string;
  /** true = 详情/只读页；false = 编辑页（渲染 <input>） */
  readonly: boolean;
  context: CellContext;
}

// ─── 内部辅助 ────────────────────────────────────────────────────────────────

/** 统一 fallback 链（架构师决议第 1~6 步） */
function resolveGvarFallback(
  code: string,
  path: string | undefined,
  field: ComponentField,
  key: string,
  row: Record<string, any>,
  ctx: CellContext,
  isReadonly: boolean,
): React.ReactElement {
  const { basicDataValues, pathCacheState, partNo } = ctx;

  // Step 1: basicDataValues['@gvar:CODE'] （全局变量直查）
  if (basicDataValues && code) {
    const gvKey = `@gvar:${code}`;
    if (Object.prototype.hasOwnProperty.call(basicDataValues, gvKey)) {
      const v = basicDataValues[gvKey];
      const formatted = formatPathValue(v);
      if (formatted != null) {
        return <span className="qt-ds-value" title={`🌐 ${code}`}>{formatted}</span>;
      }
      // value null/empty → 继续下沉
    }
  }

  // Step 2: basicDataValues[bnfDriverLookupKey(path)]（BNF driver 行级）
  if (basicDataValues && path) {
    const lk = bnfDriverLookupKey(path);
    if (Object.prototype.hasOwnProperty.call(basicDataValues, lk)) {
      const v = (basicDataValues as Record<string, any>)[lk];
      const formatted = formatPathValue(v);
      if (formatted != null) {
        return <span className="qt-ds-value">{formatted}</span>;
      }
    }
  }

  // Step 3: pathCacheState[`${partNo}::${path}`]（全局 path cache 兜底）
  if (path && partNo) {
    const cacheKey = `${partNo}::${path}`;
    if (Object.prototype.hasOwnProperty.call(pathCacheState, cacheKey)) {
      const v = (pathCacheState as Record<string, any>)[cacheKey];
      const formatted = formatPathValue(v);
      if (formatted != null) {
        return <span className="qt-ds-value">{formatted}</span>;
      }
    }
  }

  // Step 4: field.content（静态配置默认值）
  if (field.content != null && field.content !== '') {
    return <span className="qt-ds-value" title="默认值">{String(field.content)}</span>;
  }

  // Step 5: row[key]（历史持久化兜底，兼容 QT-20260522-1590）
  if (row[key] != null && row[key] !== '') {
    const formatted = formatPathValue(row[key]);
    if (formatted != null) {
      return (
        <span
          className="qt-ds-value"
          title={isReadonly ? '历史值' : '历史值（将在下次保存时更新）'}
        >
          {formatted}
        </span>
      );
    }
  }

  // Step 6: '—'
  return <span className="qt-ds-placeholder">—</span>;
}

// ─── 主组件 ──────────────────────────────────────────────────────────────────

export const ComponentCell: React.FC<ComponentCellProps> = ({
  field,
  row,
  rowIndex,
  fieldKey: key,
  readonly,
  context: ctx,
}) => {
  const {
    basicDataValues,
    pathCacheState,
    formulaCache,
    formulaErrors,
    partNo,
    activeComponent,
    activeDriverExpansion,
    isListFormulaBound,
    isDriverBound,
    listFormulaItem,
    listFormulaField,
    configTemplates,
    dsLoading,
    dsErrors,
    onCellChange,
    onCellBlur,
    dsStateKey,
  } = ctx;

  // 手动行可编辑标志：仅在非只读态 + isManualRow=true 时生效
  const isManual = !!ctx.isManualRow && !readonly;

  // ── 1. FORMULA ──────────────────────────────────────────────────────────────
  if (field.field_type === 'FORMULA') {
    // 错误旁路: cross_tab_ref 细项多命中等场景,数值已静默归 0/null。
    // 此时显示 ⚠ + tooltip 说明原因,避免误导(替代看似有效的 0)。
    const err = formulaErrors?.[field.name];
    if (err) {
      return (
        <span className="qt-formula-cell-error" title={err}>⚠</span>
      );
    }
    const val = formulaCache[field.name];
    return (
      <span className="qt-formula-cell-value">
        {val != null ? val : '—'}
      </span>
    );
  }

  // ── 2. LIST_FORMULA ─────────────────────────────────────────────────────────
  if (field.field_type === 'LIST_FORMULA') {
    const cfg = field.list_formula_config;
    if (!cfg || !listFormulaItem) {
      return <span className="qt-ds-placeholder">—</span>;
    }

    // configTemplates 是否已加载
    const tplState = configTemplates?.[cfg.config_template_id];
    if (!tplState) {
      return <span className="qt-ds-loading">加载中...</span>;
    }
    if (tplState.loading) {
      return <span className="qt-ds-loading">加载中...</span>;
    }

    const rule = cfg.per_item_rules[listFormulaItem.code];
    // 收集本行字段值给 condition & formula 求值
    const rowFieldValues: Record<string, any> = {};
    for (const f of activeComponent.fields) {
      if (f.field_type === 'LIST_FORMULA') continue;
      const k = f.name || (f as any).key || '';
      if (k) rowFieldValues[k] = row[k];
    }

    // branches 按顺序求 condition，第一个 true 取 formula
    let chosenFormula: string | null = null;
    if (rule) {
      for (const b of rule.branches) {
        if (evaluateCondition(b.condition, rowFieldValues)) {
          chosenFormula = b.formula;
          break;
        }
      }
      if (chosenFormula == null && rule.default_formula) {
        chosenFormula = rule.default_formula;
      }
    }
    // 仍无 → item.defaultValue
    if (chosenFormula == null) {
      chosenFormula = listFormulaItem.defaultValue != null ? String(listFormulaItem.defaultValue) : null;
    }

    if (!chosenFormula || !chosenFormula.trim()) {
      return <span className="qt-ds-placeholder">—</span>;
    }

    try {
      const v = evaluateListFormulaString(
        chosenFormula,
        rowFieldValues,
        {},
        '',
        {},
        basicDataValues,
        partNo,
        pathCacheState as any,
      );
      if (v == null) return <span className="qt-ds-placeholder">—</span>;
      return (
        <span
          className="qt-formula-cell-value"
          title={`📋 [${listFormulaItem.code}] ${chosenFormula}`}
        >
          {v}
        </span>
      );
    } catch {
      return <span className="qt-ds-placeholder">—</span>;
    }
  }

  // ── 3. BASIC_DATA ───────────────────────────────────────────────────────────
  if (field.field_type === 'BASIC_DATA') {
    const path = field.basic_data_path ?? '';

    // 优先识别 global_variable_code → 走统一 fallback 链 Step1
    if (field.global_variable_code) {
      return resolveGvarFallback(
        field.global_variable_code,
        path || undefined,
        field,
        key,
        row,
        ctx,
        readonly,
      );
    }

    if (!path) return <span className="qt-ds-placeholder">未配置路径</span>;

    // 优先从 driver 展开结果取（已隐式 JOIN driver 行）
    if (basicDataValues) {
      const lookupKey = bnfDriverLookupKey(path);
      if (Object.prototype.hasOwnProperty.call(basicDataValues, lookupKey)) {
        const v = (basicDataValues as Record<string, any>)[lookupKey];
        const formatted = formatPathValue(v);
        if (formatted === null) return <span className="qt-ds-placeholder">—</span>;
        const isMulti = Array.isArray(v) && v.length > 1;
        if (isMulti) {
          const tooltip = v.map((it: any) => formatPathValue(it) ?? '—').join(' / ');
          return <span className="qt-ds-value" title={tooltip}>{formatted}</span>;
        }
        return <span className="qt-ds-value">{formatted}</span>;
      }
      // basicDataValues 到位但缺 key → 回退到 row[key]（Step 5 兜底，不走 "加载中"）
    }

    // AP-38: driver=0 行鬼魂行 → 直接显示 "—"
    if (
      activeComponent.dataDriverPath &&
      activeDriverExpansion &&
      activeDriverExpansion.rowCount === 0
    ) {
      return <span className="qt-ds-placeholder">—</span>;
    }

    // 第二优先级：row[key] 持久化值（AP-38 修正，不再直接走 globalPathCache）
    if (!isListFormulaBound) {
      const fieldKey = key;
      if (fieldKey) {
        const rowVal = (row as Record<string, any>)[fieldKey];
        if (rowVal !== undefined && rowVal !== null && rowVal !== '') {
          const formatted = formatPathValue(rowVal);
          if (formatted !== null) {
            const isMulti = Array.isArray(rowVal) && rowVal.length > 1;
            if (isMulti) {
              const tooltip = rowVal.map((it: any) => formatPathValue(it) ?? '—').join(' / ');
              return <span className="qt-ds-value" title={tooltip}>{formatted}</span>;
            }
            return <span className="qt-ds-value">{formatted}</span>;
          }
        }
      }
    }

    // LIST_FORMULA 驱动行：不走 globalPathCache
    if (isListFormulaBound) {
      return <span className="qt-ds-placeholder">—</span>;
    }

    // 核价 BOM 树 spine 行：权威数据 = 本行 basicDataValues（按本子料号取）。
    // 上面 basicDataValues 缺 key + row[key] 也缺 → 该子料号确无此项数据 → "—"。
    // 不回退 globalPathCache（按根料号键，对子料号语义错误 → 会显示根值或永久"加载中"）。
    if (ctx.isBomTreeRow) {
      return <span className="qt-ds-placeholder">—</span>;
    }

    // 第三优先级：globalPathCache（新建未保存或 row_data 缺字段时兜底）
    // AP-38/AP-31: readonly=true（详情页）不依赖 pathCache 是否到位来显示"加载中…"
    // 详情页 pathCache 可能根本没发请求，永远缺 key → 应直接走"—"兜底
    const cacheKey = `${partNo ?? ''}::${path}`;
    if (!Object.prototype.hasOwnProperty.call(pathCacheState, cacheKey)) {
      if (readonly) return <span className="qt-ds-placeholder">—</span>;
      return <span className="qt-ds-loading">加载中…</span>;
    }
    const v = (pathCacheState as Record<string, any>)[cacheKey];
    const formatted = formatPathValue(v);
    if (formatted === null) return <span className="qt-ds-placeholder">—</span>;
    const isMulti = Array.isArray(v) && v.length > 1;
    if (isMulti) {
      const tooltip = v.map((it: any) => formatPathValue(it) ?? '—').join(' / ');
      return <span className="qt-ds-value" title={tooltip}>{formatted}</span>;
    }
    return <span className="qt-ds-value">{formatted}</span>;
  }

  // ── 4. DATA_SOURCE ──────────────────────────────────────────────────────────
  if (field.field_type === 'DATA_SOURCE') {
    // 手动新增行：DATA_SOURCE 降级为可填文本框，默认空，用户自填。
    // 注：DATA_SOURCE 手动行下拉选择器待 Phase 1.1 接入，暂用文本框。
    if (isManual) {
      return (
        <input
          type="text"
          value={row[key] ?? ''}
          onChange={(e) => onCellChange?.(rowIndex, key, e.target.value)}
          onBlur={() => onCellBlur?.(rowIndex, key)}
        />
      );
    }

    const dsBindingType = (field.datasource_binding as any)?.type;

    // GLOBAL_VARIABLE 子类型
    if (dsBindingType === 'GLOBAL_VARIABLE') {
      const code = (field.datasource_binding as any)?.global_variable_code ?? '';
      if (basicDataValues && code) {
        const gvKey = `@gvar:${code}`;
        if (Object.prototype.hasOwnProperty.call(basicDataValues, gvKey)) {
          const v = basicDataValues[gvKey];
          const formatted = formatPathValue(v);
          if (formatted != null) {
            return <span className="qt-ds-value" title={`🌐 ${code}`}>{formatted}</span>;
          }
          // value null → 下沉到兜底链
        }
        // 回退链: field.content → row[key] → '—'
        if (field.content != null && field.content !== '') {
          return <span className="qt-ds-value" title={`🌐 ${code} (默认)`}>{field.content}</span>;
        }
        if (row[key] != null && row[key] !== '') {
          return <span className="qt-ds-value">{String(row[key])}</span>;
        }
        return <span className="qt-ds-placeholder">—</span>;
      }
      // LIST_FORMULA 驱动行没有 driver expansion：content/row 兜底
      if (isListFormulaBound) {
        if (field.content != null && field.content !== '') {
          return <span className="qt-ds-value" title={`🌐 ${code} (默认)`}>{field.content}</span>;
        }
        if (row[key] != null && row[key] !== '') {
          return <span className="qt-ds-value">{String(row[key])}</span>;
        }
        return <span className="qt-ds-placeholder">—</span>;
      }
      // basicDataValues 未到位 → 真 loading（详情页不发请求，直接显示 —）
      if (readonly) return <span className="qt-ds-placeholder">—</span>;
      return <span className="qt-ds-loading">加载中…</span>;
    }

    // BNF_PATH 子类型
    if (dsBindingType === 'BNF_PATH') {
      const bnfPath = (field.datasource_binding as any)?.bnf_path;
      if (basicDataValues && bnfPath) {
        const lk = bnfDriverLookupKey(bnfPath);
        if (Object.prototype.hasOwnProperty.call(basicDataValues, lk)) {
          const v = (basicDataValues as Record<string, any>)[lk];
          const formatted = formatPathValue(v);
          if (formatted == null) return <span className="qt-ds-placeholder">—</span>;
          return <span className="qt-ds-value">{formatted}</span>;
        }
        // 已加载但缺键 → 回退 row[key]
        if (row[key] != null && row[key] !== '') {
          return <span className="qt-ds-value">{String(row[key])}</span>;
        }
        return <span className="qt-ds-placeholder">—</span>;
      }
      // basicDataValues 未到位（详情页不发请求，直接显示 —）
      if (readonly) return <span className="qt-ds-placeholder">—</span>;
      return <span className="qt-ds-loading">加载中…</span>;
    }

    // HTTP_API 子类型
    if (dsBindingType === 'HTTP_API') {
      return row[key] != null
        ? <span className="qt-ds-value">{String(row[key])}</span>
        : <span className="qt-ds-placeholder">— (待解析)</span>;
    }

    // DATABASE_QUERY（默认/老逻辑）
    if (!readonly) {
      // 编辑态：走 dsLoading 状态机
      const loadingKey = dsStateKey ? `${dsStateKey}-${field.name}` : undefined;
      if (loadingKey && dsLoading?.[loadingKey]) {
        return <span className="qt-ds-loading">查询中...</span>;
      }
      if (loadingKey && dsErrors?.[loadingKey]) {
        return <span className="qt-ds-error" title={dsErrors[loadingKey]}>查询失败</span>;
      }
    }
    if (row[key] != null) {
      return (
        <span className="qt-ds-value">
          {typeof row[key] === 'object' ? JSON.stringify(row[key]) : row[key]}
        </span>
      );
    }
    return <span className="qt-ds-placeholder">—</span>;
  }

  // ── 5. FIXED_VALUE ──────────────────────────────────────────────────────────
  if (field.field_type === 'FIXED_VALUE') {
    // 手动新增行：FIXED_VALUE 渲染为可填文本框（用户自填，忽略 field.content 模板值）
    if (isManual) {
      return (
        <input
          type="text"
          value={row[key] ?? ''}
          onChange={(e) => onCellChange?.(rowIndex, key, e.target.value)}
          onBlur={() => onCellBlur?.(rowIndex, key)}
        />
      );
    }
    const val = row[key] ?? field.content;
    if (val != null && val !== '') {
      const formatted = formatPathValue(val);
      return <span>{formatted ?? String(val)}</span>;
    }
    return <span className="qt-ds-placeholder">—</span>;
  }

  // ── 6. INPUT_TEXT / INPUT_NUMBER / INPUT（fallback） ──────────────────────
  const isNumber = field.field_type === 'INPUT_NUMBER' || (field as any).is_amount;
  const rawCell = row[key];
  const isEmpty = rawCell === undefined || rawCell === null || rawCell === '';

  // readonly=true: 只读文本渲染
  if (readonly) {
    if (!isEmpty) {
      const formatted = formatPathValue(rawCell);
      return <span>{formatted ?? String(rawCell)}</span>;
    }

    // default_source 解析（统一解析器；含 BASIC_DATA + content 兜底）
    if (isNumber || field.field_type === 'INPUT_TEXT' || field.field_type === 'INPUT') {
      const def = resolveInputDefault(field, {
        basicDataValues,
        partNo,
        pathCache: pathCacheState as Record<string, any>,
      });
      if (def !== undefined) {
        const formatted = formatPathValue(def) ?? String(def);
        return <span title="默认值">{formatted}</span>;
      }
    }

    return <span className="qt-ds-placeholder">—</span>;
  }

  // readonly=false: 渲染 <input>
  // row[key] 空 → 用统一解析器给默认初值（default_source 实时 > content）；非空不动（铁律）
  let effectiveValue: any = rawCell;
  if (isEmpty) {
    const def = resolveInputDefault(field, {
      basicDataValues,
      partNo,
      pathCache: pathCacheState as Record<string, any>,
    });
    if (def !== undefined) effectiveValue = isNumber ? def : String(def);
  }

  return (
    <input
      type={isNumber ? 'number' : 'text'}
      step={isNumber ? 'any' : undefined}
      value={effectiveValue ?? ''}
      onChange={e => {
        const val = e.target.value;
        if (isNumber && val !== '' && !/^-?\d*\.?\d*$/.test(val)) return;
        onCellChange?.(rowIndex, key, val);
      }}
      onBlur={() => onCellBlur?.(rowIndex, key)}
    />
  );
};

export default ComponentCell;
