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

export interface DriverRow {
  driverRow: Record<string, any>;
  basicDataValues: Record<string, any>;
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

/** 把 fields 数组中影响 expand 解析结果的元数据(name + field_type + 路径绑定)序列化成 hash. */
export function fieldsOverrideHash(fields: any[] | undefined): string {
  if (!fields || fields.length === 0) return '';
  const parts = fields.map((f: any) => {
    const ds = f.datasource_binding || {};
    const def = f.default_source || {};
    return [
      f.name || '',
      f.field_type || '',
      f.basic_data_path || '',
      ds.type || '',
      ds.bnf_path || '',
      ds.global_variable_code || '',
      def.type || '',
      def.code || '',
      def.path || '',
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
 */
export const driverExpansionKey = (
  partNo: string,
  componentId: string,
  customerId?: string,
  dataDriverPath?: string,
  fieldsHash?: string,
) => `${partNo}::${componentId}::${customerId ?? ''}::${dataDriverPath ?? ''}::${fieldsHash ?? ''}`;

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
        const partNoOfKey = k.split('::')[0];
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
    return JSON.stringify(
      (lineItems || []).map((li) => ({
        pn: li.productPartNo,
        pv: li.partVersionLocked ?? null,
        comps: (li.componentData || [])
          .filter((cd) => cd.componentId && cd.componentType !== 'SUBTOTAL')
          .map((cd) => `${cd.componentId}::${cd.dataDriverPath || ''}::${fieldsOverrideHash((cd.fields || []) as any[])}`)
          .sort(),
      })),
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
    const out: Array<{
      key: string; componentId: string; partNo: string; partVersion: number | null;
      overrideDataDriverPath?: string;
      overrideFieldsJson?: string;
    }> = [];
    const seen = new Set<string>();
    for (const item of lineItems || []) {
      if (!item.productPartNo) continue;
      const partVersion = item.partVersionLocked ?? null;
      for (const comp of item.componentData || []) {
        if (!comp.componentId) continue;
        if (comp.componentType === 'SUBTOTAL') continue;
        // 跳过 enrich 前的 raw 数据: fields/driverPath 都没 → 没法做有意义的 expand
        const hasDriver = !!(comp.dataDriverPath && comp.dataDriverPath.length > 0);
        const hasFields = !!(comp.fields && comp.fields.length > 0);
        if (!hasDriver && !hasFields) continue;
        const fieldsHash = fieldsOverrideHash(comp.fields as any[]);
        const key = driverExpansionKey(item.productPartNo, comp.componentId, customerId, comp.dataDriverPath, fieldsHash);
        if (seen.has(key)) continue;
        seen.add(key);
        out.push({
          key, componentId: comp.componentId, partNo: item.productPartNo, partVersion,
          overrideDataDriverPath: comp.dataDriverPath || undefined,
          overrideFieldsJson: hasFields ? JSON.stringify(comp.fields) : undefined,
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
      // V195 hotfix: 让 snapshot driver_path / fields 作为 backend expand 真理源
      overrideDataDriverPath: t.overrideDataDriverPath,
      overrideFieldsJson: t.overrideFieldsJson,
    }));

    // 2026-05-19 fix: 用 task index 直接对应 result index, 不再用 batchKey map.
    // 原因: backend r.key = cacheKey(cid, cust, partNo, partVer) 不含 override tag,
    // 同 (cid,cust,part,ver) 不同 override 的多 task → r.key 相同 → map 后写覆盖前写
    // → cache 只剩一个 driver 的数据, 另一个永远丢失 → 用户看到"加载中"/tab 隐藏.
    // backend 保证 results.length == tasks.length 且按 task 顺序返 (含 ERROR 兜底),
    // 直接按 index 配对最稳.

    batchExpandDriver(batchTasks)
      .then((results) => {
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

    // tasks 已 dedupe；cache 读 ref 不入依赖；customerId 是原始依赖
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tasks, customerId]);

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
