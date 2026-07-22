/**
 * useDriverExpansions  ──  Y1.5 行驱动展开 hook
 *
 * 目标:对 lineItems 中每个含 dataDriverPath 的组件,调后端
 *      POST /api/cpq/components/batch-expand (批量)
 *  → 返回 { rowCount, rows: [{driverRow, basicDataValues}] }
 *
 * key = `${productPartNo}::${componentId}::${customerId}`
 *  - partNo 变化(料号切换) → 重查
 *  - customer 变化(报价单换客户) → 重查
 *  - dataDriverPath 在模板快照中,变化场景小;一次性按 key 缓存即可
 *
 * 字段路径在 Step2 渲染时按 cacheKey + rowIndex 直接查 expansions[key].rows[i].basicDataValues[normalizedPath]
 *
 * 性能优化 (2026-05-08):
 *  方案1: fingerprint 稳定化 —— lineItems 引用变化但内容不变时 tasks useMemo 不重建
 *  方案3: batch endpoint  —— 所有 missing tasks 一次 POST 完成，而非 N 个并发请求
 */
import { useEffect, useState, useMemo, useRef, useCallback } from 'react';
import { batchExpandDriver } from '../../services/componentService';
import type { LineItem } from './QuotationStep2';

/** BOM spine 系统列；核价侧 + 报价侧（task-0721）快照行均可携带，普通快照行 undefined。 */
export interface BomSysCols {
  nodeId?: string;
  parentId?: string | null;
  lvl?: number;
  hfPartNo?: string | null;
  parentNo?: string | null;
  bomVersion?: string | null;
  isCycle?: boolean;
  /**
   * task-0721 F1/F3：节点业务类型（后端物化时按判定链算好写入）。
   * '材质' | '零件' | '外购件' | '主件' | null(未判定)。前端仅用于「+」按钮置灰判断，
   * 不在前端重新实现类型判定逻辑（架构红线）。
   */
  nodeType?: string | null;
}

export interface DriverRow {
  driverRow: Record<string, any>;
  basicDataValues: Record<string, any>;
  /** 核价 BOM spine 系统列（P1）；仅核价侧快照行有值。 */
  __sys?: BomSysCols;
  /**
   * AP-54 C3：完整集 effKey（由 buildSnapshotExpansions 在完整 baseRows 上算后盖入）。
   * 渲染层用此值作 driver 行 rowKey，与过滤口径、后端 resolvedRows/formulaResults 键
   * 保持单一口径。COSTING 侧 undefined（spec §3.7 隔离）；手动行不经此路径。
   * 不变量：过滤后子集绝不重算 key，__effKey 始终是完整集下标对应的键。
   */
  __effKey?: string;
}

export interface DriverExpansion {
  rowCount: number;
  driverPath?: string;
  rows: DriverRow[];
}

/** key = `${partNo}::${componentId}::${customerId}` */
export type DriverExpansionMap = Record<string, DriverExpansion>;

/**
 * V203/Phase B: cache key 加 dataDriverPath 后缀.
 * 同 lineItem 里同 componentId 但不同 dataDriverPath 的两个组件实例 (如模板里出现两次
 * COMP-CFG-PROCESS 一次绑 v_composite_child_processes 一次绑 mat_process) 必须分开缓存
 * — 否则后到的请求被去重不发, basicDataValues 永远只含先到那个的 keys, 第二个组件
 * 的字段查不到 → 永久"加载中". 旧调用未传 path 时退化为旧 key 行为.
 */
/** 简单稳定 hash, 用于把 fields override 数组压成 key 维度的一段. */
function shortHash(s: string): string {
  if (!s) return '';
  let h = 0;
  for (let i = 0; i < s.length; i++) h = ((h << 5) - h + s.charCodeAt(i)) | 0;
  return Math.abs(h).toString(36);
}

/** 把 fields 数组中影响 expand 解析结果的元数据(name + field_type + 路径绑定)序列化成 hash.
 *
 * 2026-05-22: 加入 BASIC_DATA 字段的 global_variable_code (AP-45 双轨修复)。
 * BASIC_DATA + global_variable_code 复合配置下，后端需要在 batchExpand 时同时返回
 * @gvar:CODE key，fieldsHash 加入此维度避免缓存命中错误（同 cid 不同 gvar_code 场景）。
 */
export function fieldsOverrideHash(fields: any[] | undefined): string {
  if (!fields || fields.length === 0) return '';
  const parts = fields.map((f: any) => {
    const ds = f.datasource_binding || {};
    const def = f.default_source || {};
    return [
      f.name || '',
      f.field_type || '',
      f.basic_data_path || '',
      // BASIC_DATA 字段的全局变量 code（AP-45 新增维度，避免同 cid 不同 gvar_code 缓存冲突）
      (f.field_type === 'BASIC_DATA' ? (f.global_variable_code || '') : ''),
      ds.type || '',
      ds.bnf_path || '',
      ds.global_variable_code || '',
      def.type || '',
      def.code || '',
      def.path || '',
      // AP-37 (2026-06-16): 单位换算字段纳入 hash — 改 unit_source_field 配置后立即失效 driver-expansion 缓存
      f.unit_source_field || '',
    ].join('|');
  });
  return shortHash(parts.join('::'));
}

/**
 * V196 (2026-05-19): 5 维 cache key (含 fieldsHash).
 *
 * 背景: 同 cid 同 driverPath 但 fields_override 不同的两个 Tab (典型: 模板里"材质"6字段 +
 * "选配-材质"5字段, cid=e42185ec, driver_path=''). 仅 4 维 cache key 会让两个 Tab 共用同一
 * cache slot, batchExpand 返不同 basicDataValues → 后写覆盖先写, 渲染层永久"加载中".
 *
 * 修法: 加 fieldsHash 维度 (基于 name + field_type + 各 path/code 绑定).
 *
 * Bug B (2026-05-20): 6 维 cache key (加 lineItemId).
 *
 * 背景: 同一报价单内两条相同 productPartNo 的 lineItem (例：同产品报两个数量),
 * 5 维 key 完全相同 → 共用同一 cache slot → 其中一条的 driver 展开结果被另一条覆盖
 * → 报价单修改后展开数据错乱。
 *
 * 修法: 加 lineItemId 作第一维 (item.id || item.tempId || '')。
 * - item.id: 后端持久化后的稳定 UUID
 * - item.tempId: 新建未保存时由前端 crypto.randomUUID() 生成的临时 id
 * - 两者均无时退化为空串 (SIMPLE 单产品常见，此时退化为旧 5 维行为)
 *
 * invalidate(partNos) 改为按 key 的第 2 维 (partNo) 匹配，保持业务语义不变。
 */
export const driverExpansionKey = (
  lineItemId: string,
  partNo: string,
  componentId: string,
  customerId?: string,
  dataDriverPath?: string,
  fieldsHash?: string,
) => `${lineItemId}::${partNo}::${componentId}::${customerId ?? ''}::${dataDriverPath ?? ''}::${fieldsHash ?? ''}`;

/** 空展开兜底值（避免反复重试同一失败 key） */
const EMPTY_EXPANSION: DriverExpansion = { rowCount: 0, rows: [] };

/**
 * V202+ (2026-05-19): 返回 { cache, invalidate } 替代单纯 cache.
 *
 * - cache: 同原 DriverExpansionMap (向后兼容; 调用方通过 .cache 取)
 * - invalidate(partNos): 把指定 partNos 相关的缓存 key 清掉; 不传则清全部.
 *   用途: 选配/批量导入完成后, 后端写过 mat_process / mat_bom 但前端 cache 还停留在
 *   "写之前"的 0 行/旧值, 导致 componentHasData 误判隐藏 Tab. 调 invalidate 清掉,
 *   fingerprint useMemo 下一轮自动重 fetch.
 */
export interface UseDriverExpansionsResult {
  cache: DriverExpansionMap;
  invalidate: (partNos?: string[]) => void;
}

export function useDriverExpansions(
  lineItems: LineItem[],
  customerId?: string,
  /**
   * 当前报价单 id(2026-05-30 统一渲染协议新增)。
   * 透传到 batchExpand task,后端绑成 :quotationId 让所有 mirror 视图统一使用。
   * 老调用者(详情页等)不传时,后端走老协议(:lineItemId 标量),向后兼容。
   */
  quotationId?: string,
): UseDriverExpansionsResult {
  const [cache, setCache] = useState<DriverExpansionMap>({});
  // cacheRef 与 cache state 保持同步，effect 内读 ref 避免闭包过期引发二次触发
  const cacheRef = useRef<DriverExpansionMap>({});

  const invalidate = useCallback((partNos?: string[]) => {
    setCache(prev => {
      if (!partNos || partNos.length === 0) {
        cacheRef.current = {};
        return {};
      }
      const setOfPartNos = new Set(partNos);
      const next: DriverExpansionMap = {};
      for (const [k, v] of Object.entries(prev)) {
        // Bug B: key 结构变为 lineItemId::partNo::componentId::..., partNo 在第 2 维 (index 1)
        const partNoOfKey = k.split('::')[1];
        if (!setOfPartNos.has(partNoOfKey)) next[k] = v;
      }
      cacheRef.current = next;
      return next;
    });
  }, []);

  // ── 方案1: 内容指纹替代引用依赖 ──────────────────────────────────
  // 含影响 expand-driver 的字段: productPartNo + 各 componentId + driver_path + fields 数量
  //
  // 2026-05-19 修: 之前 fingerprint 只看 cids, 导致 enrich 改了 comp.dataDriverPath /
  // comp.fields (从空到含 v_composite_*) 时 fingerprint 不变 → tasks useMemo 不重建 →
  // useEffect 不重跑 → 不重 fetch → 缓存卡在 enrich 前的"错"数据 (mat_process keys + 缺 @gvar).
  // 现把 driver_path + fields 数 也进 fingerprint, 任一变化都触发 tasks 重算.
  const fingerprint = useMemo(() => {
    // 预建 parentId -> childIds 映射用于 fingerprint（确保子件 ID 集变化时父级也重 fetch）
    const childIdMap: Record<string, string[]> = {};
    for (const li of lineItems || []) {
      const parentId = (li as any).parentLineItemId as string | undefined;
      const itemId = (li as any).id as string | undefined;
      if (parentId && itemId) {
        if (!childIdMap[parentId]) childIdMap[parentId] = [];
        childIdMap[parentId].push(itemId);
      }
    }
    return JSON.stringify(
      (lineItems || []).map((li) => {
        const liId = (li as any).id || (li as any).tempId || '';
        const compositeType = (li as any).compositeType as string | undefined;
        // COMPOSITE 父级: 把子件 ID 列表也纳入 fingerprint，子件 ID 变化 → 父级 tasks 重建 → 重 fetch
        const childIds = (compositeType === 'COMPOSITE' && liId)
          ? (childIdMap[liId] || []).sort().join(',')
          : '';
        return {
          // Bug B: 加 lineItemId 维度，使同 partNo 的两条行产生不同 fingerprint → 各自独立 fetch
          lid: liId,
          pn: li.productPartNo,
          pv: li.partVersionLocked ?? null,
          // COMPOSITE 父级子件 ID 集变化时 fingerprint 变 → tasks 重建 → 重 fetch (消除缓存旧子件集)
          cids: childIds,
          comps: (li.componentData || [])
            .filter((cd) => cd.componentId && cd.componentType === 'NORMAL')
            .map((cd: any) => {
              return `${cd.componentId}::${cd.dataDriverPath || ''}::${fieldsOverrideHash(cd.fields || [])}`;
            })
            .sort(),
        };
      }),
    );
  }, [lineItems]);

  // 收集需要查的 (componentId, partNo) 对
  //
  // 2026-05-19 修(关键): 跳过"comp 既无 dataDriverPath 又无 fields"的组件 — 这种是
  // raw saved componentData (后端 ComponentDataDTO 不持久化 fields/driverPath, enrich
  // 前是空的). 如果不跳过, batch 用 undefined override 触发 backend 走 component 表
  // 默认 dataDriverPath (例如 COMP-CFG-PROCESS 默认 'mat_process'). 对 COMPOSITE 父级
  // CFG-COMBO-* 查 mat_process WHERE hf_part_no=... → 0 行 → 缓存 EMPTY_EXPANSION →
  // 即便 enrich 后 fingerprint 变化触发 batch#2 with override, batch#1 的 0 行已被
  // 写入 cache, 而且 invalidate + batch#2 有时序竞态(batch#1 .then 可能晚于 batch#2 .then
  // 完成 → 用 prev spread 把"对的"覆盖回"错的"). 直接跳过 enrich 前的 task 才是干净.
  //
  // skip 后行为: 渲染层 driverExpansions[key]=undefined → 走"row[key] 兜底" / DATA_SOURCE
  // "加载中…"占位 ~100-500ms (enrich 期间). enrich 完成 → fingerprint 变 → tasks 重算 →
  // batch fire with override → 正确数据.
  const tasks = useMemo(() => {
    // 预建 parentLineItemId → [child lineItemId] 映射，用于 COMPOSITE 父级传子件 ID 列表
    const childIdsByParent: Record<string, string[]> = {};
    for (const item of lineItems || []) {
      const parentId = (item as any).parentLineItemId as string | undefined;
      const itemId = (item as any).id as string | undefined;
      if (parentId && itemId) {
        if (!childIdsByParent[parentId]) childIdsByParent[parentId] = [];
        childIdsByParent[parentId].push(itemId);
      }
    }

    const out: Array<{
      key: string; componentId: string; partNo: string; partVersion: number | null;
      /** Bug B: lineItemId 传入 batchExpand HTTP body，后端按此隔离同 partNo 多行展开结果 */
      lineItemId: string;
      /** compositeType 传入后端：COMPOSITE 父级跳过 lineItemId 注入，SIMPLE/null 必须注入 */
      compositeType?: string;
      /**
       * COMPOSITE 父级的子件 lineItemId 列表。
       * 后端用于向 v_composite_child_* 路径注入 quotation_line_item_id IN (...) 谓词，
       * 只返回当前报价单子件自己的工序行，消除历史累积（236 行 bug 根因）。
       */
      childLineItemIds?: string[];
      overrideDataDriverPath?: string;
      overrideFieldsJson?: string;
    }> = [];
    const seen = new Set<string>();
    for (const item of lineItems || []) {
      if (!item.productPartNo) continue;
      const partVersion = item.partVersionLocked ?? null;
      for (const comp of item.componentData || []) {
        if (!comp.componentId) continue;
        if (comp.componentType !== 'NORMAL') continue;
        const effectiveDriver = comp.dataDriverPath || undefined;
        const effectiveFields = comp.fields;
        // 跳过 enrich 前的 raw 数据: fields/driverPath 都没 → 没法做有意义的 expand
        const hasDriver = !!(effectiveDriver && effectiveDriver.length > 0);
        const hasFields = !!(effectiveFields && (effectiveFields as any[]).length > 0);
        if (!hasDriver && !hasFields) continue;
        const fieldsHash = fieldsOverrideHash(effectiveFields as any[]);
        // Bug B: lineItemId = item.id (持久化后) || item.tempId (新建时) || '' (退化为旧行为)
        const lineItemId = (item as any).id || (item as any).tempId || '';
        // compositeType: COMPOSITE 父级跳过 lineItemId 注入 (聚合子件工序); SIMPLE/undefined 必须注入
        const compositeType = (item as any).compositeType as string | undefined;
        // COMPOSITE 父级: 计算子件 lineItemId 列表，传给后端注入 IN 谓词（消除历史累积）
        const childLineItemIds = (compositeType === 'COMPOSITE' && lineItemId)
          ? (childIdsByParent[lineItemId] || [])
          : undefined;
        const key = driverExpansionKey(lineItemId, item.productPartNo, comp.componentId, customerId, effectiveDriver, fieldsHash);
        if (seen.has(key)) continue;
        seen.add(key);
        out.push({
          key, componentId: comp.componentId, partNo: item.productPartNo, partVersion,
          lineItemId,
          compositeType,
          childLineItemIds: childLineItemIds && childLineItemIds.length > 0 ? childLineItemIds : undefined,
          overrideDataDriverPath: effectiveDriver,
          overrideFieldsJson: hasFields ? JSON.stringify(effectiveFields) : undefined,
        });
      }
    }
    return out;
    // fingerprint 字符串稳定后，lineItems 引用变化不会重建 tasks
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fingerprint, customerId]);

  useEffect(() => {
    // 用 ref 读当前 cache，避免 state 写入触发 effect 二次执行
    const missing = tasks.filter((t) => !(t.key in cacheRef.current));
    if (missing.length === 0) return;

    // eslint-disable-next-line no-console
    console.log('[Y1.5 expand-driver] batch 触发', {
      missingCount: missing.length,
      taskCount: tasks.length,
    });

    // ── 方案3: 单次 batch 请求替代 Promise.all(N 个单请求) ──────────
    const batchTasks = missing.map((t) => ({
      componentId: t.componentId,
      customerId: customerId ?? null,
      partNo: t.partNo,
      // 传入版本号，后端注入 AND part_version=N 谓词，避免历史版本数据叠加重复
      partVersion: t.partVersion,
      // Bug B: 传入 lineItemId，后端 BatchExpandRequest 收到后可按此隔离同 partNo 多行
      // 老后端 Jackson @JsonIgnoreProperties 默认忽略未知字段，不会报 400
      lineItemId: t.lineItemId || null,
      // compositeType 修复: COMPOSITE 父级 + v_composite_child_* 路径时跳过 lineItemId 注入
      // SIMPLE/undefined 则注入 lineItemId 防止累积全量历史工序行 (171 行 bug 根因)
      compositeType: t.compositeType || null,
      // COMPOSITE 父级子件 lineItemId IN 谓词: 限定只返回当前报价单子件自己的工序行 (消除历史累积)
      // 无 childLineItemIds (SIMPLE/PART/新建未持久化) → 后端忽略，走原有逻辑
      childLineItemIds: t.childLineItemIds || null,
      // V195 hotfix: 让 snapshot driver_path / fields 作为 backend expand 真理源
      overrideDataDriverPath: t.overrideDataDriverPath,
      overrideFieldsJson: t.overrideFieldsJson,
      // V273 统一渲染协议(2026-05-30):透传报价单 id,后端绑成 :quotationId,
      // 让所有 mirror 视图统一靠 (:quotationId + :customerCode + :hfPartNos) 三参数跑,
      // 不再有视图单独用 :lineItemId 标量(空时后端走老兼容路径)
      quotationId: quotationId || null,
    }));

    // 2026-05-19 fix: 用 task index 直接对应 result index, 不再用 batchKey map.
    // 原因: backend r.key = cacheKey(cid, cust, partNo, partVer) 不含 override tag,
    // 同 (cid,cust,part,ver) 不同 override 的多 task → r.key 相同 → map 后写覆盖前写
    // → cache 只剩一个 driver 的数据, 另一个永远丢失 → 用户看到"加载中"/tab 隐藏.
    // backend 保证 results.length == tasks.length 且按 task 顺序返 (含 ERROR 兜底),
    // 直接按 index 配对最稳.

    // 调试开关: URL ?debugSql=1 或 localStorage.cpqDebugSql='1' 时,
    // 后端回传 driver 改写后的最终 SQL, 这里只对「第一个产品」的各 Tab 打到控制台。
    const debugSqlOn = (() => {
      try {
        return new URLSearchParams(window.location.search).get('debugSql') === '1'
          || window.localStorage.getItem('cpqDebugSql') === '1';
      } catch { return false; }
    })();
    const firstPartNo = (lineItems && lineItems.length > 0) ? lineItems[0].productPartNo : undefined;

    const controller = new AbortController();
    batchExpandDriver(batchTasks, debugSqlOn, controller.signal)
      .then((results) => {
        if (controller.signal.aborted) return;
        const updates: DriverExpansionMap = {};

        for (let i = 0; i < missing.length; i++) {
          const t = missing[i];
          const r = results[i];
          if (!r) {
            // backend 未返该 index (极端情况) → EMPTY_EXPANSION
            updates[t.key] = EMPTY_EXPANSION;
            continue;
          }
          if (r.status === 'OK' && r.data) {
            // eslint-disable-next-line no-console
            console.log('[Y1.5 expand-driver] OK', {
              localKey: t.key,
              backendKey: r.key,
              rowCount: r.data.rowCount,
              driverPath: r.data.driverPath,
            });
            updates[t.key] = {
              rowCount: r.data.rowCount,
              driverPath: r.data.driverPath,
              rows: r.data.rows ?? [],
            };
          } else {
            // status=ERROR 或 data 为 null：写入空展开，避免反复重试
            // eslint-disable-next-line no-console
            console.warn('[Y1.5 expand-driver] ERROR', { localKey: t.key, backendKey: r.key, error: r.error });
            updates[t.key] = EMPTY_EXPANSION;
          }
          // 调试: 仅第一个产品的各 Tab 打印 driver 实际执行 SQL(OK/ERROR 都打, 失败也能看到失败的那条 SQL)
          if (debugSqlOn && t.partNo === firstPartNo && (r.debugSql || r.error)) {
            // eslint-disable-next-line no-console
            console.log(
              `%c[报价单 driver SQL] 第一个产品 ${t.partNo} · 组件 ${t.componentId} · ${r.status}`,
              'color:#0a7;font-weight:bold',
            );
            // eslint-disable-next-line no-console
            if (r.debugSql) console.log(r.debugSql);
            // eslint-disable-next-line no-console
            if (r.error) console.warn('执行报错:', r.error);
          }
        }

        // missing 中没有对应结果的条目（后端未返回）也用空展开兜底
        for (const t of missing) {
          if (!(t.key in updates)) {
            updates[t.key] = EMPTY_EXPANSION;
          }
        }

        setCache((prev) => {
          const next = { ...prev, ...updates };
          cacheRef.current = next;
          return next;
        });
      })
      .catch((err) => {
        if (controller.signal.aborted || (err && (err as any).code === 'ERR_CANCELED')) return;
        // eslint-disable-next-line no-console
        console.error('[Y1.5 expand-driver] batch 整体失败', err);
        // 失败时把所有 missing 写空，避免 effect 反复重跑
        setCache((prev) => {
          const next = { ...prev };
          for (const t of missing) {
            next[t.key] = EMPTY_EXPANSION;
          }
          cacheRef.current = next;
          return next;
        });
      });
    return () => controller.abort();

    // tasks 已 dedupe；cache 读 ref 不入依赖；customerId 是原始依赖
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tasks, customerId, quotationId]);

  return { cache, invalidate };
}

/** 把任意 BNF 路径规范化(剥花括号 + trim),用于查 basicDataValues */
export function normalizeBnfKey(path: string): string {
  if (!path) return '';
  let p = path.trim();
  if (p.startsWith('{') && p.endsWith('}')) {
    p = p.substring(1, p.length - 1).trim();
  }
  return p;
}

/** 后端 ComponentDriverService 把 fields[].basic_data_path 自动加上花括号再返回作为 key */
export function bnfDriverLookupKey(path: string): string {
  const inner = normalizeBnfKey(path);
  return `{${inner}}`;
}
