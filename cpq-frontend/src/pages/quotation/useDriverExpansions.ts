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
import { useEffect, useState, useMemo, useRef } from 'react';
import { batchExpandDriver, buildBatchKey } from '../../services/componentService';
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

export const driverExpansionKey = (partNo: string, componentId: string, customerId?: string) =>
  `${partNo}::${componentId}::${customerId ?? ''}`;

/** 空展开兜底值（避免反复重试同一失败 key） */
const EMPTY_EXPANSION: DriverExpansion = { rowCount: 0, rows: [] };

export function useDriverExpansions(
  lineItems: LineItem[],
  customerId?: string,
): DriverExpansionMap {
  const [cache, setCache] = useState<DriverExpansionMap>({});
  // cacheRef 与 cache state 保持同步，effect 内读 ref 避免闭包过期引发二次触发
  const cacheRef = useRef<DriverExpansionMap>({});

  // ── 方案1: 内容指纹替代引用依赖 ──────────────────────────────────
  // 仅含影响 expand-driver 的字段: productPartNo + 各 componentId
  // lineItems 引用变化但内容不变 → fingerprint 字符串相等 → tasks useMemo 不重建 → effect 不重跑
  const fingerprint = useMemo(() => {
    return JSON.stringify(
      (lineItems || []).map((li) => ({
        pn: li.productPartNo,
        // partVersionLocked 变化（版本切换）必须触发重查，加入指纹
        pv: li.partVersionLocked ?? null,
        cids: (li.componentData || [])
          .filter((cd) => cd.componentId && cd.componentType !== 'SUBTOTAL')
          .map((cd) => cd.componentId)
          .sort(),
      })),
    );
  }, [lineItems]);

  // 收集需要查的 (componentId, partNo) 对
  // 注意:不依赖 comp.dataDriverPath(老报价单的 components_snapshot 可能没有该字段),
  //       直接对所有 componentId 探测;后端按组件实时读 data_driver_path,
  //       未配 driver 的组件返回 rowCount=0,前端按单行兜底渲染。
  const tasks = useMemo(() => {
    const out: Array<{ key: string; componentId: string; partNo: string; partVersion: number | null }> = [];
    const seen = new Set<string>();
    for (const item of lineItems || []) {
      if (!item.productPartNo) continue;
      const partVersion = item.partVersionLocked ?? null;
      for (const comp of item.componentData || []) {
        if (!comp.componentId) continue;
        if (comp.componentType === 'SUBTOTAL') continue;
        const key = driverExpansionKey(item.productPartNo, comp.componentId, customerId);
        if (seen.has(key)) continue;
        seen.add(key);
        out.push({ key, componentId: comp.componentId, partNo: item.productPartNo, partVersion });
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
    }));

    // 建立 batchKey(后端格式，含 partVersion) → 本地 driverExpansionKey 的映射
    const batchKeyToLocalKey: Record<string, string> = {};
    for (const t of missing) {
      const bk = buildBatchKey(t.componentId, customerId, t.partNo, t.partVersion);
      batchKeyToLocalKey[bk] = t.key;
    }

    batchExpandDriver(batchTasks)
      .then((results) => {
        const updates: DriverExpansionMap = {};

        for (const r of results) {
          const localKey = batchKeyToLocalKey[r.key];
          if (!localKey) continue;

          if (r.status === 'OK' && r.data) {
            // eslint-disable-next-line no-console
            console.log('[Y1.5 expand-driver] OK', {
              key: r.key,
              rowCount: r.data.rowCount,
              driverPath: r.data.driverPath,
            });
            updates[localKey] = {
              rowCount: r.data.rowCount,
              driverPath: r.data.driverPath,
              rows: r.data.rows ?? [],
            };
          } else {
            // status=ERROR 或 data 为 null：写入空展开，避免反复重试
            // eslint-disable-next-line no-console
            console.warn('[Y1.5 expand-driver] ERROR', { key: r.key, error: r.error });
            updates[localKey] = EMPTY_EXPANSION;
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

  return cache;
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
