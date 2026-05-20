/**
 * useConfigTemplates — Phase B5
 *
 * 给定 lineItems, 扫描所有 LIST_FORMULA 字段, 收集涉及的 config_template_id,
 * 批量拉取详情 (含 categories + items), 给 ProductCard 渲染时用作组件级 driver.
 *
 * 返回: { [config_template_id]: { template: ConfigTemplate | null, loading: boolean, error?: string } }
 */
import { useEffect, useMemo, useState } from 'react';
import { configTemplateService, type ConfigTemplate } from '../../services/configTemplateService';
import type { LineItem, ComponentDataItem } from './QuotationStep2';

export interface ConfigTemplateState {
  template: ConfigTemplate | null;
  loading: boolean;
  error?: string;
}

export type ConfigTemplateMap = Record<string, ConfigTemplateState>;

export function useConfigTemplates(lineItems: LineItem[]): ConfigTemplateMap {
  const tplIdSet = useMemo(() => {
    const set = new Set<string>();
    for (const li of lineItems || []) {
      for (const comp of (li.componentData || []) as ComponentDataItem[]) {
        for (const f of (comp.fields || [])) {
          if ((f as any).field_type === 'LIST_FORMULA') {
            const id = (f as any).list_formula_config?.config_template_id;
            if (id) set.add(id);
          }
        }
      }
    }
    return set;
  }, [lineItems]);

  const [cache, setCache] = useState<ConfigTemplateMap>({});

  useEffect(() => {
    const wanted = Array.from(tplIdSet);
    const missing = wanted.filter(id => !(id in cache));
    if (missing.length === 0) return;

    setCache(prev => {
      const next = { ...prev };
      for (const id of missing) next[id] = { template: null, loading: true };
      return next;
    });

    for (const id of missing) {
      configTemplateService.getById(id)
        .then(r => {
          setCache(prev => ({ ...prev, [id]: { template: r.data, loading: false } }));
        })
        .catch(err => {
          setCache(prev => ({ ...prev, [id]: { template: null, loading: false, error: err?.message || '加载模板失败' } }));
        });
    }
  }, [tplIdSet]);  // eslint-disable-line react-hooks/exhaustive-deps

  return cache;
}
