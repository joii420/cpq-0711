import { useCallback, useEffect, useRef } from 'react';
import type { DraftEnvelope, DraftSnapshot } from './componentDraft';

const PREFIX = 'cpq:component-draft:';

export function draftKey(componentId: string): string {
  return `${PREFIX}${componentId}`;
}

export function writeDraft(componentId: string, snapshot: DraftSnapshot, baselineUpdatedAt?: string): void {
  const env: DraftEnvelope = { savedAt: Date.now(), baselineUpdatedAt, snapshot };
  try {
    localStorage.setItem(draftKey(componentId), JSON.stringify(env));
  } catch {
    /* 配额满等异常静默忽略，草稿是尽力而为 */
  }
}

export function readDraft(componentId: string): DraftEnvelope | null {
  try {
    const raw = localStorage.getItem(draftKey(componentId));
    if (!raw) return null;
    return JSON.parse(raw) as DraftEnvelope;
  } catch {
    return null;
  }
}

export function clearDraft(componentId: string): void {
  try { localStorage.removeItem(draftKey(componentId)); } catch { /* ignore */ }
}

export interface DraftListItem {
  componentId: string;
  env: DraftEnvelope;
}

export function listAllDrafts(): DraftListItem[] {
  const out: DraftListItem[] = [];
  for (let i = 0; i < localStorage.length; i += 1) {
    const k = localStorage.key(i);
    if (!k || !k.startsWith(PREFIX)) continue;
    const componentId = k.slice(PREFIX.length);
    const env = readDraft(componentId);
    if (env) out.push({ componentId, env });
  }
  return out;
}

/**
 * 防抖写草稿 hook。调用方在每次编辑态变化时调 scheduleSave(snapshot)。
 * 800ms 防抖；componentId 切换时立即 flush 上一个组件的待写草稿，避免漏存。
 */
export function useDraftAutosave(
  componentId: string | undefined,
  baselineUpdatedAt: string | undefined,
  delay = 800,
) {
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const pending = useRef<{ id: string; snap: DraftSnapshot; baseline?: string } | null>(null);

  const flush = useCallback(() => {
    if (timer.current) { clearTimeout(timer.current); timer.current = null; }
    if (pending.current) {
      writeDraft(pending.current.id, pending.current.snap, pending.current.baseline);
      pending.current = null;
    }
  }, []);

  const scheduleSave = useCallback((snapshot: DraftSnapshot) => {
    if (!componentId) return;
    pending.current = { id: componentId, snap: snapshot, baseline: baselineUpdatedAt };
    if (timer.current) clearTimeout(timer.current);
    timer.current = setTimeout(() => {
      if (pending.current) {
        writeDraft(pending.current.id, pending.current.snap, pending.current.baseline);
        pending.current = null;
      }
      timer.current = null;
    }, delay);
  }, [componentId, baselineUpdatedAt, delay]);

  useEffect(() => () => flush(), [componentId, flush]);

  return { scheduleSave, flush };
}
