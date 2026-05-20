/**
 * usePathFormulaCache
 *
 * 报价单含 BNF 路径公式的运行时求值 hook(Phase A4)。
 *
 * 工作原理:
 *   1. 扫描所有 LineItem 的公式 expression,提取 path token
 *   2. 按 (partNo, path) 去重,批量调后端 /formulas/batch-evaluate（单次 POST）
 *   3. 求值结果存到 cache,key = `${partNo}::${path}`
 *   4. evaluateExpression(tokens, ..., partNo, pathCache) 检测 path token 时按 key 查 cache
 *
 * 性能优化 (2026-05-08):
 *  方案1: fingerprint 稳定化 —— lineItems 引用变化但内容不变时 tasks useMemo 不重建
 *  方案3: batch endpoint  —— 所有 missing tasks 一次 POST 完成，而非 N 个并发请求
 *  cacheRef: effect 内读 ref 避免 setState 触发 effect 二次执行
 *
 * 重新求值触发:lineItems 中的产品数 / partNo / 组件列表 / 客户 ID 变化时
 */
import { useEffect, useState, useMemo, useRef } from 'react';
import { batchEvaluate, buildEvalKey } from '../../services/formulaService';
import { setGlobalPathCache } from '../../utils/formulaEngine';
import type { LineItem } from './QuotationStep2';

/** path 求值结果可以是 number / string / boolean / null,UI 直接显示;公式引用按 number 解析 */
export type PathCache = Record<string, number | string | boolean | null>;

/** 计算 cache key */
export const pathCacheKey = (partNo: string, path: string) => `${partNo}::${path}`;

export function usePathFormulaCache(
  lineItems: LineItem[],
  customerId?: string,
): PathCache {
  const [cache, setCache] = useState<PathCache>({});
  // cacheRef 与 cache state 保持同步，effect 内读 ref 避免闭包过期引发二次触发
  const cacheRef = useRef<PathCache>({});

  // ── 内容指纹替代引用依赖 ──────────────────────────────────────────────────
  // 仅含影响路径求值的字段: productPartNo + 各 componentId + 每组件的 path 列表(排序后).
  //
  // 2026-05-17 修复(QT-1377 "加载中" bug):必须把 fields 的 basic_data_path 纳入指纹.
  // 原因: applyQuotationData 先 setLineItems(basicItems) (componentData 来自 DB 无 fields),
  // 然后 enrichComponentData 异步把 fields 补上后再 setLineItems. 旧指纹只看 componentId 集合,
  // enrich 前后字符串相同 → tasks useMemo 不重建 → batchEvaluate 永远不发请求 → cache 空 → 加载中.
  const fingerprint = useMemo(() => {
    return JSON.stringify(
      (lineItems || []).map((li) => ({
        pn: li.productPartNo,
        comps: (li.componentData || [])
          .filter((cd: any) => cd.componentId)
          .map((cd: any) => {
            // 收集本组件内所有 BASIC_DATA 字段的 basic_data_path + 公式 token 中的 path,
            // 排序去重后参与指纹 — 让 enrich 把 fields 从空填到 N 个时指纹变化.
            const paths: string[] = [];
            for (const f of (cd.fields || []) as any[]) {
              if (f.field_type === 'BASIC_DATA' && f.basic_data_path) {
                paths.push(f.basic_data_path);
              }
              // V184: INPUT_NUMBER 字段的默认值路径(全局变量回退)也要计入指纹,
              // 这样 enrich 把 default_basic_data_path 补上后 tasks useMemo 才会重建.
              if (f.default_basic_data_path) {
                paths.push(f.default_basic_data_path);
              }
              // V190+ default_source.BNF_PATH (INPUT_NUMBER 走 BNF 兜底)
              if (f.default_source?.type === 'BNF_PATH' && f.default_source?.path) {
                paths.push(f.default_source.path);
              }
              // Phase J: DATA_SOURCE.BNF_PATH 子类型 — 没扫这里 → tasks 不预热 →
              // 渲染 fallback 链最终落"加载中"(AP-31 协议传播补)
              if (
                f.field_type === 'DATA_SOURCE'
                && f.datasource_binding?.type === 'BNF_PATH'
                && f.datasource_binding?.bnf_path
              ) {
                paths.push(f.datasource_binding.bnf_path);
              }
            }
            for (const fr of (cd.formulas || []) as any[]) {
              for (const tok of (fr.expression || []) as any[]) {
                if ((tok.type === 'path' || tok.type === 'global_variable') && tok.path) {
                  paths.push(tok.path);
                }
              }
            }
            return {
              id: cd.componentId,
              ps: Array.from(new Set(paths)).sort(),
            };
          })
          .sort((a: any, b: any) => (a.id > b.id ? 1 : a.id < b.id ? -1 : 0)),
      })),
    );
  }, [lineItems]);

  // 收集所有需要求值的 (partNo, path) 对
  // - BASIC_DATA 字段的 basic_data_path
  // - 公式 token 中的 path / global_variable (V104; global_variable 编译产物存在 token.path)
  const tasks = useMemo(() => {
    const collected: Array<{ partNo: string; path: string }> = [];
    const seen = new Set<string>();
    const addTask = (partNo: string, path: string) => {
      if (!path) return;
      const key = pathCacheKey(partNo, path);
      if (seen.has(key)) return;
      seen.add(key);
      collected.push({ partNo, path });
    };
    for (const item of lineItems || []) {
      const partNo = item.productPartNo;
      if (!partNo) continue;
      for (const comp of (item.componentData as any[]) || []) {
        // BASIC_DATA 字段
        for (const f of comp.fields || []) {
          if (f.field_type === 'BASIC_DATA' && f.basic_data_path) {
            addTask(partNo, f.basic_data_path);
          }
          // V184: INPUT_NUMBER (或任意字段)的默认值来源路径 — 让 batchEvaluate 预热,
          // 保证用户行值为空时 formulaEngine / 渲染层能取到全局变量默认值.
          if (f.default_basic_data_path) {
            addTask(partNo, f.default_basic_data_path);
          }
          // V190+ default_source.BNF_PATH
          if (f.default_source?.type === 'BNF_PATH' && f.default_source?.path) {
            addTask(partNo, f.default_source.path);
          }
          // Phase J: DATA_SOURCE.BNF_PATH 子类型 — 不预热 → fallback 链查空 → "加载中"
          if (
            f.field_type === 'DATA_SOURCE'
            && f.datasource_binding?.type === 'BNF_PATH'
            && f.datasource_binding?.bnf_path
          ) {
            addTask(partNo, f.datasource_binding.bnf_path);
          }
        }
        // 公式 token 内的 path / global_variable
        for (const fr of comp.formulas || []) {
          for (const tok of fr.expression || []) {
            if ((tok.type === 'path' || tok.type === 'global_variable') && tok.path) {
              addTask(partNo, tok.path);
            }
          }
        }
      }
    }
    return collected;
    // fingerprint 字符串稳定后，lineItems 引用变化不会重建 tasks
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fingerprint, customerId]);

  useEffect(() => {
    // 用 ref 读当前 cache，避免 state 写入触发 effect 二次执行
    const missing = tasks.filter((t) => !(pathCacheKey(t.partNo, t.path) in cacheRef.current));
    if (missing.length === 0) return;

    // eslint-disable-next-line no-console
    console.log('[path-formula-cache] batch 触发', {
      missingCount: missing.length,
      taskCount: tasks.length,
    });

    // ── 单次 batch 请求替代 Promise.all(N 个单请求) ──────────────────────────
    const batchTasks = missing.map((t) => ({
      expression: `{${t.path}}`,
      partNo: t.partNo,
      customerId: customerId ?? null,
    }));

    batchEvaluate(batchTasks)
      .then((results) => {
        const updates: PathCache = {};

        for (const r of results) {
          // 通过 key 反查 task —— 用 buildEvalKey 重建 key 匹配
          const matchedTask = missing.find(
            (t) => buildEvalKey(`{${t.path}}`, customerId, t.partNo) === r.key,
          );
          if (!matchedTask) continue;
          const localKey = pathCacheKey(matchedTask.partNo, matchedTask.path);
          if (r.status === 'OK' && r.data?.success) {
            // 直接存原值(包括 null / array / object)— 让 UI 端 formatPathValue 自行格式化
            // 不要在这里 toString,否则数组会被序列化为 "[object Object],..." 失真
            updates[localKey] = (r.data as any).result ?? null;
          } else {
            // 求值失败/网络错误标记 null,避免反复重试
            updates[localKey] = null;
          }
        }

        // missing 中没有对应结果的条目（后端未返回）也用 null 兜底，避免反复重试
        for (const t of missing) {
          const localKey = pathCacheKey(t.partNo, t.path);
          if (!(localKey in updates)) {
            updates[localKey] = null;
          }
        }

        setCache((prev) => {
          const next = { ...prev, ...updates };
          // 同步维护 ref，下次 effect 读 ref 不会漏掉本次写入
          cacheRef.current = next;
          // 同步写入模块级 cache;evaluateExpression 在 path case 内部用 formatPathValue → parseFloat 取数值
          setGlobalPathCache(next as any);
          return next;
        });
      })
      .catch((err) => {
        // eslint-disable-next-line no-console
        console.error('[path-formula-cache] batch 整体失败', err);
        // 整个 batch 失败 → 所有 missing 写 null 兜底，避免 effect 反复重跑
        setCache((prev) => {
          const next = { ...prev };
          for (const t of missing) {
            next[pathCacheKey(t.partNo, t.path)] = null;
          }
          cacheRef.current = next;
          setGlobalPathCache(next as any);
          return next;
        });
      });

    // tasks 已 dedupe；cache 读 ref 不入依赖；customerId 是原始依赖
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tasks, customerId]);

  return cache;
}
