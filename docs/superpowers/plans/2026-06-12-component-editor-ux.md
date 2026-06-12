# 组件管理编辑体验三项优化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给组件管理加本地草稿自动保存、修复行键资格不实时刷新、把取数路径配置统一为「选视图→点列」点选交互。

**Architecture:** 纯前端为主（请求1/3 零后端；请求2 仅当根因=跨组件 fallback 串号时改后端一处）。请求1 用 localStorage 草稿 hook 隔离持久化逻辑；请求3 复用并扩展现有 `PathPickerDrawer`、抽共享展示主体 `ViewColumnPickerBody`；请求2 先 systematic-debugging 复现再修。按隐藏依赖分三阶段：①请求1 独立先行 → ②铺 `dataDriverPath` 子组件数据通路 → ③请求2+请求3 同批。

**Tech Stack:** React + TypeScript + Ant Design + Vitest（单测 `npx vitest run`）+ Playwright（E2E）。后端 Quarkus（仅请求2 可能涉及）。

**Spec:** `docs/superpowers/specs/2026-06-12-component-editor-ux-design.md`

**前置（每个 worktree 会话开工前）：** 复用主工作区已运行的 dev server（前端 5174 / 后端 8081），**不在 worktree 另起 server / 重装依赖**（CLAUDE.md worktree 约束）。单测在 worktree 内 `cd cpq-frontend && npx vitest run <file>` 即可（vitest 不依赖运行中的 server）。

---

## 阶段一 · 请求1：组件编辑自动保存（localStorage 草稿 + 自动恢复）

### Task 1: 草稿数据结构与序列化纯函数（`componentDraft.ts`）

**Files:**
- Create: `cpq-frontend/src/pages/component/componentDraft.ts`
- Test: `cpq-frontend/src/pages/component/componentDraft.test.ts`

抽出**不依赖 React / localStorage** 的纯逻辑：草稿快照的构造、临时 `key` 剥离/重建、陈旧判定。便于单测。

- [ ] **Step 1: 写失败测试**

```typescript
// componentDraft.test.ts
import { describe, it, expect } from 'vitest';
import {
  buildDraftSnapshot,
  stripFieldKeys,
  rebuildFieldKeys,
  isDraftStale,
  type DraftSnapshot,
} from './componentDraft';
import type { ComponentItem } from './types';

const baseComp = (over: Partial<ComponentItem> = {}): ComponentItem => ({
  id: 'c1', name: 'N', code: 'C', columnCount: 0, status: 'ACTIVE',
  componentType: 'NORMAL', fields: [], formulas: [],
  ...over,
});

describe('componentDraft', () => {
  it('buildDraftSnapshot 收齐所有可编辑态字段', () => {
    const snap = buildDraftSnapshot({
      fields: [{ key: 'field-0-123', name: 'a', field_type: 'INPUT_TEXT' }] as any,
      formulas: [{ key: 'formula-0-9', name: 'f', expression: [] }] as any,
      dataDriverPath: '$v',
      rowKeyFields: ['a'],
      excelColumns: [{ col_key: 'x' }] as any,
      bomRecursiveExpand: true,
    });
    expect(snap.fields[0]).not.toHaveProperty('key'); // 临时 key 已剥离
    expect(snap.fields[0].name).toBe('a');
    expect(snap.dataDriverPath).toBe('$v');
    expect(snap.rowKeyFields).toEqual(['a']);
    expect(snap.bomRecursiveExpand).toBe(true);
  });

  it('stripFieldKeys / rebuildFieldKeys round-trip 恢复后每个字段有唯一 key', () => {
    const stripped = stripFieldKeys([{ key: 'k1', name: 'a' } as any, { key: 'k2', name: 'b' } as any]);
    expect(stripped[0]).not.toHaveProperty('key');
    const rebuilt = rebuildFieldKeys(stripped);
    expect(rebuilt[0].key).toBeTruthy();
    expect(rebuilt[1].key).toBeTruthy();
    expect(rebuilt[0].key).not.toBe(rebuilt[1].key);
  });

  it('isDraftStale：服务端基线快照与当前服务端态不同 → 陈旧', () => {
    const draft: DraftSnapshot = buildDraftSnapshot({
      fields: [], formulas: [], dataDriverPath: '$v', rowKeyFields: [],
      excelColumns: [], bomRecursiveExpand: false,
    });
    const baselineServer = baseComp({ updatedAt: '2026-06-12T00:00:00Z' });
    const freshSame = baseComp({ updatedAt: '2026-06-12T00:00:00Z' });
    const freshChanged = baseComp({ updatedAt: '2026-06-12T09:00:00Z' });
    expect(isDraftStale(baselineServer.updatedAt, freshSame.updatedAt)).toBe(false);
    expect(isDraftStale(baselineServer.updatedAt, freshChanged.updatedAt)).toBe(true);
    void draft;
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-frontend && npx vitest run src/pages/component/componentDraft.test.ts`
Expected: FAIL（模块/导出不存在）

- [ ] **Step 3: 实现**

```typescript
// componentDraft.ts
import type { FieldItem, FormulaItem, ComponentType } from './types';

export interface DraftSnapshot {
  fields: Omit<FieldItem, 'key'>[];
  formulas: Omit<FormulaItem, 'key'>[];
  dataDriverPath: string;
  rowKeyFields: string[];
  excelColumns: any[];
  bomRecursiveExpand: boolean;
}

export interface DraftEnvelope {
  savedAt: number;
  /** 草稿创建时服务端组件的 updatedAt，用于陈旧检测 */
  baselineUpdatedAt?: string;
  snapshot: DraftSnapshot;
}

let keySeq = 0;
function freshKey(prefix: string): string {
  keySeq += 1;
  // 不用 Date.now()，避免同毫秒撞 key；进程内自增足够唯一
  return `${prefix}-${keySeq}-${Math.floor(performance.now())}`;
}

export function stripFieldKeys<T extends { key?: string }>(arr: T[]): Omit<T, 'key'>[] {
  return (arr ?? []).map(({ key: _k, ...rest }) => rest);
}

export function rebuildFieldKeys<T extends object>(arr: T[]): (T & { key: string })[] {
  return (arr ?? []).map((f) => ({ ...f, key: freshKey('field') }));
}

export function rebuildFormulaKeys<T extends object>(arr: T[]): (T & { key: string })[] {
  return (arr ?? []).map((f) => ({ ...f, key: freshKey('formula') }));
}

export function buildDraftSnapshot(input: {
  fields: FieldItem[];
  formulas: FormulaItem[];
  dataDriverPath: string;
  rowKeyFields: string[];
  excelColumns: any[];
  bomRecursiveExpand: boolean;
}): DraftSnapshot {
  return {
    fields: stripFieldKeys(input.fields),
    formulas: stripFieldKeys(input.formulas),
    dataDriverPath: input.dataDriverPath ?? '',
    rowKeyFields: input.rowKeyFields ?? [],
    excelColumns: input.excelColumns ?? [],
    bomRecursiveExpand: !!input.bomRecursiveExpand,
  };
}

/** 服务端基线 updatedAt 与当前服务端 updatedAt 不同 → 草稿陈旧。任一缺失视为不陈旧（保守不打扰）。 */
export function isDraftStale(baselineUpdatedAt?: string, currentUpdatedAt?: string): boolean {
  if (!baselineUpdatedAt || !currentUpdatedAt) return false;
  return baselineUpdatedAt !== currentUpdatedAt;
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd cpq-frontend && npx vitest run src/pages/component/componentDraft.test.ts`
Expected: PASS（3 个 test）

- [ ] **Step 5: 补 `ComponentItem.updatedAt` 类型 + 提交**

修改 `cpq-frontend/src/pages/component/types.ts`，在 `ComponentItem` 接口末尾 `excelColumns?: string;` 之后加：

```typescript
  /** 后端 ComponentDTO.updatedAt（ISO 字符串）；草稿陈旧检测用。getById 返回。 */
  updatedAt?: string;
```

```bash
git add cpq-frontend/src/pages/component/componentDraft.ts cpq-frontend/src/pages/component/componentDraft.test.ts cpq-frontend/src/pages/component/types.ts
git commit -m "feat(component): 草稿快照纯函数 componentDraft + ComponentItem.updatedAt 类型"
```

---

### Task 2: localStorage 草稿读写 hook（`useComponentDraft.ts`）

**Files:**
- Create: `cpq-frontend/src/pages/component/useComponentDraft.ts`
- Test: `cpq-frontend/src/pages/component/useComponentDraft.test.ts`

封装 localStorage key 规约、读/写/删/枚举全部草稿。测试用 jsdom 的 `localStorage`（vitest 默认 `environment: 'jsdom'`，已被现有组件测试使用；若 vite.config 未设，测试文件顶部加 `// @vitest-environment jsdom`）。

- [ ] **Step 1: 写失败测试**

```typescript
// useComponentDraft.test.ts
// @vitest-environment jsdom
import { describe, it, expect, beforeEach } from 'vitest';
import {
  draftKey, writeDraft, readDraft, clearDraft, listAllDrafts,
} from './useComponentDraft';
import { buildDraftSnapshot } from './componentDraft';

const snap = () => buildDraftSnapshot({
  fields: [], formulas: [], dataDriverPath: '$v', rowKeyFields: [],
  excelColumns: [], bomRecursiveExpand: false,
});

describe('useComponentDraft storage', () => {
  beforeEach(() => localStorage.clear());

  it('draftKey 规约', () => {
    expect(draftKey('abc')).toBe('cpq:component-draft:abc');
  });

  it('write → read round-trip', () => {
    writeDraft('c1', snap(), '2026-06-12T00:00:00Z');
    const env = readDraft('c1');
    expect(env?.snapshot.dataDriverPath).toBe('$v');
    expect(env?.baselineUpdatedAt).toBe('2026-06-12T00:00:00Z');
    expect(typeof env?.savedAt).toBe('number');
  });

  it('clearDraft 删除', () => {
    writeDraft('c1', snap(), undefined);
    clearDraft('c1');
    expect(readDraft('c1')).toBeNull();
  });

  it('listAllDrafts 枚举所有草稿（含 componentId）', () => {
    writeDraft('c1', snap(), undefined);
    writeDraft('c2', snap(), undefined);
    localStorage.setItem('unrelated:key', 'x'); // 不应被枚举
    const all = listAllDrafts();
    expect(all.map((d) => d.componentId).sort()).toEqual(['c1', 'c2']);
  });

  it('readDraft 容错：损坏 JSON 返 null 不抛', () => {
    localStorage.setItem(draftKey('bad'), '{not json');
    expect(readDraft('bad')).toBeNull();
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-frontend && npx vitest run src/pages/component/useComponentDraft.test.ts`
Expected: FAIL（导出不存在）

- [ ] **Step 3: 实现存储层 + hook**

```typescript
// useComponentDraft.ts
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

  // 组件切换/卸载时 flush 上一个组件的待写草稿
  useEffect(() => () => flush(), [componentId, flush]);

  return { scheduleSave, flush };
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd cpq-frontend && npx vitest run src/pages/component/useComponentDraft.test.ts`
Expected: PASS（5 个 test）

- [ ] **Step 5: 提交**

```bash
git add cpq-frontend/src/pages/component/useComponentDraft.ts cpq-frontend/src/pages/component/useComponentDraft.test.ts
git commit -m "feat(component): localStorage 草稿读写+枚举+防抖自动保存 hook"
```

---

### Task 3: ComponentManagement 接入草稿自动写入 + 自动恢复

**Files:**
- Modify: `cpq-frontend/src/pages/component/ComponentManagement.tsx`（`handleSelectComponent` ~898、`handleSave` ~935、新增 effect、详情头 ~1240）

- [ ] **Step 1: 引入 hook 与防抖写入**

在 `ComponentManagement` 组件体内（state 声明区附近）加：

```typescript
import { buildDraftSnapshot } from './componentDraft';
import { readDraft, clearDraft, useDraftAutosave, listAllDrafts } from './useComponentDraft';

// ...组件内：
const baselineUpdatedAt = selectedComponent?.updatedAt;
const { scheduleSave, flush: flushDraft } = useDraftAutosave(selectedComponent?.id, baselineUpdatedAt);
const restoringRef = useRef(false); // 恢复期间不要把恢复动作当成用户编辑回写

// 编辑态任一变化 → 防抖写草稿（恢复中跳过，避免刚加载就写一次无意义草稿）
useEffect(() => {
  if (!selectedComponent?.id) return;
  if (restoringRef.current) { restoringRef.current = false; return; }
  scheduleSave(buildDraftSnapshot({
    fields, formulas, dataDriverPath, rowKeyFields, excelColumns, bomRecursiveExpand,
  }));
  // eslint-disable-next-line react-hooks/exhaustive-deps
}, [fields, formulas, dataDriverPath, rowKeyFields, excelColumns, bomRecursiveExpand]);
```

> 注：`excelColumns` 在 buildDraftSnapshot 里直接放数组；`handleSave` 仅 EXCEL 类型用它、NORMAL 用 rowKey 等，草稿全存无妨（恢复时按类型用对应字段）。

- [ ] **Step 2: handleSelectComponent 中做自动恢复**

**关键：** 在 `handleSelectComponent` 的 `try {` 之后、任何 setState 之前，第一行加 `restoringRef.current = true;`。原因：`handleSelectComponent` 里所有 setState 在同一事件处理函数中批处理成一次 re-render → 触发一次自动写草稿 effect；不跳过的话**每个刚加载的组件都会被误写一份草稿、误判为脏**。该标志让紧随的那一次 effect 跳过写入（effect 内首run 即把它复位为 false，见 Step 1）。正常加载与草稿恢复都靠这一个标志覆盖。

```typescript
const handleSelectComponent = async (comp: ComponentItem) => {
  try {
    restoringRef.current = true; // 跳过本次程序化加载触发的自动写草稿
    const res = await componentService.getById(comp.id);
    // ...（其余不变）
```

把 setState 段改为「先 setState 服务端值，再检测草稿决定是否覆盖」。在 `setBomRecursiveExpand(...)` 之后、`refreshRowKeyCandidates` 之前插入恢复逻辑：

```typescript
// ——草稿自动恢复——
const draft = readDraft(loaded.id);
if (draft) {
  const stale = draft.baselineUpdatedAt && loaded.updatedAt
    && draft.baselineUpdatedAt !== loaded.updatedAt;
  if (!stale) {
    restoringRef.current = true;
    setFields(rebuildFieldKeys(draft.snapshot.fields));
    setFormulas(rebuildFormulaKeys(draft.snapshot.formulas));
    setExcelColumns(draft.snapshot.excelColumns ?? []);
    setDataDriverPath(draft.snapshot.dataDriverPath ?? '');
    setRowKeyFields(draft.snapshot.rowKeyFields ?? []);
    setBomRecursiveExpand(!!draft.snapshot.bomRecursiveExpand);
    setDraftBanner({ kind: 'restored', componentId: loaded.id });
  } else {
    setDraftBanner({ kind: 'stale', componentId: loaded.id });
  }
} else {
  setDraftBanner(null);
}
```

在文件顶部 import 补 `rebuildFieldKeys, rebuildFormulaKeys`（来自 `./componentDraft`）。新增 state：

```typescript
const [draftBanner, setDraftBanner] = useState<{ kind: 'restored' | 'stale'; componentId: string } | null>(null);
```

- [ ] **Step 3: handleSave 成功后清草稿 + 刷新 banner**

在 `handleSave` 的 `message.success('保存成功');` 之后加：

```typescript
clearDraft(selectedComponent.id);
flushDraft(); // 丢弃任何在途的防抖写
setDraftBanner(null);
```

- [ ] **Step 4: 详情头渲染 banner + 脏标记**

在详情区（`:1239` `cmm-detail-wrap` 内、`cmm-detail-head` 之后）加提示条：

```tsx
{draftBanner?.componentId === selectedComponent.id && (
  <Alert
    style={{ margin: '0 0 8px' }}
    type={draftBanner.kind === 'restored' ? 'warning' : 'info'}
    showIcon
    message={draftBanner.kind === 'restored'
      ? '检测到未保存的修改，已自动恢复'
      : '该组件在别处已更新，本地草稿可能过期'}
    action={
      <Space>
        {draftBanner.kind === 'stale' && (
          <Button size="small" onClick={() => {
            const d = readDraft(selectedComponent.id);
            if (d) {
              restoringRef.current = true;
              setFields(rebuildFieldKeys(d.snapshot.fields));
              setFormulas(rebuildFormulaKeys(d.snapshot.formulas));
              setExcelColumns(d.snapshot.excelColumns ?? []);
              setDataDriverPath(d.snapshot.dataDriverPath ?? '');
              setRowKeyFields(d.snapshot.rowKeyFields ?? []);
              setBomRecursiveExpand(!!d.snapshot.bomRecursiveExpand);
              setDraftBanner({ kind: 'restored', componentId: selectedComponent.id });
            }
          }}>仍恢复草稿</Button>
        )}
        <Button size="small" danger onClick={() => {
          clearDraft(selectedComponent.id);
          flushDraft();
          setDraftBanner(null);
          handleSelectComponent(selectedComponent); // 重载服务端版本
        }}>放弃草稿</Button>
      </Space>
    }
  />
)}
```

确保 `Alert` 已在 antd import 中（`ComponentManagement.tsx` 顶部）。

- [ ] **Step 5: 自检 + 提交**

Run（worktree 内，复用主工作区 5174 server）：
```bash
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/component/ComponentManagement.tsx
```
Expected: tsc 0 错误；Vite 200。

手动验证：编辑某字段名 → 不点保存 → 切到别的组件 → 切回 → 顶部出现「已自动恢复」且改动还在。刷新页面后再选该组件 → 同样恢复。

```bash
git add cpq-frontend/src/pages/component/ComponentManagement.tsx
git commit -m "feat(component): 详情接入草稿自动写入+切组件/刷新自动恢复+脏banner"
```

---

### Task 4: 全局「保存全部草稿 (N)」按钮 + 确认 Modal + 逐个陈旧复检

**Files:**
- Modify: `cpq-frontend/src/pages/component/ComponentManagement.tsx`（MasterList props + 工具栏；顶层 onSaveAllDrafts）

- [ ] **Step 1: 顶层实现 saveAllDrafts 逻辑**

在 `ComponentManagement` 组件体内加（复用 `runBatch`，串行 `concurrent:false`）：

```typescript
import { runBatch } from '../../components/SelectableTable';

const [draftListVersion, setDraftListVersion] = useState(0); // 触发徽标重算
const allDrafts = useMemo(() => listAllDrafts(), [draftListVersion, selectedComponent?.id]);
const [saveAllOpen, setSaveAllOpen] = useState(false);
const [saveAllChecked, setSaveAllChecked] = useState<string[]>([]);

const openSaveAll = () => {
  const ids = listAllDrafts().map((d) => d.componentId);
  setSaveAllChecked(ids); // 默认全选
  setSaveAllOpen(true);
};

const doSaveAll = async () => {
  const targets = listAllDrafts().filter((d) => saveAllChecked.includes(d.componentId));
  const res = await runBatch(
    targets,
    async (d) => {
      // 逐个落库前复检陈旧：拉服务端最新 updatedAt 比对
      const fresh = (await componentService.getById(d.componentId)).data as ComponentItem;
      if (d.env.baselineUpdatedAt && fresh.updatedAt && d.env.baselineUpdatedAt !== fresh.updatedAt) {
        throw new Error('该组件已被他人更新（跳过，避免覆盖）');
      }
      const s = d.env.snapshot;
      const payload: any = { name: fresh.name, fields: s.fields, formulas: s.formulas };
      if (fresh.componentType === 'EXCEL') {
        payload.excelColumns = JSON.stringify(s.excelColumns ?? []);
      } else if (fresh.componentType === 'NORMAL') {
        payload.dataDriverPath = s.dataDriverPath ?? '';
        payload.rowKeyFields = (s.rowKeyFields ?? []).length > 0 ? s.rowKeyFields : undefined;
        payload.bomRecursiveExpand = s.bomRecursiveExpand;
      }
      await componentService.update(d.componentId, payload);
      clearDraft(d.componentId);
    },
    { concurrent: false, rowLabel: (d) => d.componentId },
  );
  setSaveAllOpen(false);
  setDraftListVersion((v) => v + 1);
  loadTree(searchKeyword || undefined);
  if (res.failed.length === 0) {
    message.success(`已保存 ${res.ok} 个组件草稿`);
  } else {
    message.warning(
      `成功 ${res.ok} · 失败/跳过 ${res.failed.length}：` +
      res.failed.map((f) => `${f.row.componentId}(${f.reason})`).join('；')
    );
  }
  // 当前选中组件若被保存，刷新其 banner
  if (selectedComponent && saveAllChecked.includes(selectedComponent.id)) {
    setDraftBanner(null);
  }
};
```

> 注：此处批量保存用草稿 snapshot 直接拼 payload，不依赖 `rowKeyCandidates`（草稿已存最终 rowKeyFields）。锚定列保留逻辑在单组件 `handleSave` 已处理；批量场景用草稿存量值即可。

- [ ] **Step 2: 给 MasterList 传 props 并在工具栏渲染按钮**

`MasterListProps` 接口加：
```typescript
  draftCount: number;
  onSaveAllDrafts: () => void;
```

`MasterList` 解构加 `draftCount, onSaveAllDrafts`。在其批量动作工具栏区域（`onCreate`/`onRefresh` 按钮附近）加：

```tsx
<Tooltip title={draftCount === 0 ? '没有未保存的本地草稿' : `保存 ${draftCount} 个组件的本地草稿`}>
  <Button size="small" type="primary" ghost disabled={draftCount === 0} onClick={onSaveAllDrafts}>
    保存全部草稿{draftCount > 0 ? ` (${draftCount})` : ''}
  </Button>
</Tooltip>
```

在 `<MasterList ... />` 调用处（`:1221`）传：
```tsx
draftCount={allDrafts.length}
onSaveAllDrafts={openSaveAll}
```

- [ ] **Step 3: 渲染确认 Modal**

在 return 的根 `<div className="cm-layout">` 内末尾加：

```tsx
<Modal
  title="保存全部草稿"
  open={saveAllOpen}
  onCancel={() => setSaveAllOpen(false)}
  onOk={doSaveAll}
  okText="确认保存"
  width={520}
>
  <Alert type="warning" showIcon style={{ marginBottom: 12 }}
    message="确认后将把以下组件的本地草稿逐个落库（会触发模板 snapshot 同步）。被他人改过的组件将自动跳过。" />
  <Checkbox.Group
    style={{ display: 'flex', flexDirection: 'column', gap: 6 }}
    value={saveAllChecked}
    onChange={(v) => setSaveAllChecked(v as string[])}
    options={listAllDrafts().map((d) => ({
      label: `${d.componentId} · 草稿于 ${new Date(d.env.savedAt).toLocaleString()}`,
      value: d.componentId,
    }))}
  />
</Modal>
```

确保 `Modal`、`Checkbox`、`Tooltip` 已在 antd import 中。

> 优化（可选）：label 用组件名而非 id——可在 `listAllDrafts` 结果上用 `directories` 里的组件名映射；本期先用 id + code 够用。

- [ ] **Step 4: 自检 + 提交**

Run:
```bash
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/component/ComponentManagement.tsx
```
Expected: 0 错误；200。

手动验证：改两个组件不保存 → 顶部「保存全部草稿 (2)」→ 点开 Modal 列出 2 项可勾选 → 确认 → message 汇总；草稿清空、徽标归零。

```bash
git add cpq-frontend/src/pages/component/ComponentManagement.tsx
git commit -m "feat(component): 全局保存全部草稿(N)+确认Modal可勾选+逐个陈旧复检串行落库"
```

---

### Task 5: 左侧列表项脏标记徽标

**Files:**
- Modify: `cpq-frontend/src/pages/component/ComponentManagement.tsx`（MasterList 组件项渲染）

- [ ] **Step 1: 传入有草稿的组件 id 集合**

`MasterListProps` 加 `draftIds: Set<string>;`；调用处传 `draftIds={new Set(allDrafts.map(d => d.componentId))}`。

- [ ] **Step 2: 列表项渲染小圆点**

在 MasterList 渲染单个组件卡片名称处，加条件徽标：

```tsx
{draftIds.has(comp.id) && (
  <span title="有未保存的本地草稿" style={{
    display: 'inline-block', width: 8, height: 8, borderRadius: '50%',
    background: '#faad14', marginLeft: 6, verticalAlign: 'middle',
  }} />
)}
```

- [ ] **Step 3: 自检 + 提交**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json` → 0 错误
手动验证：改组件不保存 → 左侧该项出现橙点；保存后消失。

```bash
git add cpq-frontend/src/pages/component/ComponentManagement.tsx
git commit -m "feat(component): 左侧列表项未保存草稿橙点徽标"
```

---

### Task 6: 阶段一回归自检

- [ ] **Step 1: 全量 tsc + Vite 200**

Run:
```bash
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/component/ComponentManagement.tsx
```
Expected: 0 错误；两个 200。

- [ ] **Step 2: 单测全绿**

Run: `cd cpq-frontend && npx vitest run src/pages/component/componentDraft.test.ts src/pages/component/useComponentDraft.test.ts`
Expected: 全 PASS。

- [ ] **Step 3: 阶段一里程碑提交（如有未提交散项）**

```bash
git add -A cpq-frontend/src/pages/component
git commit -m "chore(component): 阶段一(自动保存)自检通过" --allow-empty
```

> ⚠️ 仅 add 本特性涉及的 component 目录，遵循 worktree 并发纪律不用全仓 `-A`。

---

## 阶段二 · 铺 `dataDriverPath` 子组件数据通路（请求2/3 共享前置）

### Task 7: 把 driver 视图列集合下传到 FieldConfigTable / 选择器

**Files:**
- Modify: `cpq-frontend/src/pages/component/ComponentManagement.tsx`（`<FieldConfigTable .../>` callsite ~1137）
- Modify: `cpq-frontend/src/pages/component/FieldConfigTable.tsx`（props 接口 + 透传）
- Create: `cpq-frontend/src/pages/component/sqlViewPath.ts`（`extractSqlViewName` 纯函数）
- Test: `cpq-frontend/src/pages/component/sqlViewPath.test.ts`

- [ ] **Step 1: 写 extractSqlViewName 失败测试**

```typescript
// sqlViewPath.test.ts
import { describe, it, expect } from 'vitest';
import { extractSqlViewName } from './sqlViewPath';

describe('extractSqlViewName', () => {
  it('$view.col → view', () => expect(extractSqlViewName('$cp_view.品名')).toBe('cp_view'));
  it('$view（仅视图） → view', () => expect(extractSqlViewName('$cp_view')).toBe('cp_view'));
  it('非 $ 形态 → null', () => expect(extractSqlViewName('mat_part.x')).toBeNull());
  it('空 → null', () => expect(extractSqlViewName('')).toBeNull());
  it('undefined → null', () => expect(extractSqlViewName(undefined)).toBeNull());
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-frontend && npx vitest run src/pages/component/sqlViewPath.test.ts`
Expected: FAIL。

- [ ] **Step 3: 实现**

```typescript
// sqlViewPath.ts
/** 从 driver 路径解析 $视图名。`$cp_view.品名`→`cp_view`；`$cp_view`→`cp_view`；非 $ 形态→null。 */
export function extractSqlViewName(path?: string): string | null {
  if (!path) return null;
  const t = path.trim();
  if (!t.startsWith('$')) return null;
  const body = t.slice(1);
  const dot = body.indexOf('.');
  const name = dot >= 0 ? body.slice(0, dot) : body;
  return name.length > 0 ? name : null;
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd cpq-frontend && npx vitest run src/pages/component/sqlViewPath.test.ts`
Expected: PASS（5 test）。

- [ ] **Step 5: FieldConfigTable 接收 dataDriverPath prop**

`FieldConfigTable` 的 props 接口加：
```typescript
  /** 组件 driver 路径（$视图…）；用于行键判定 + 字段路径选择器只列 driver 视图列。 */
  dataDriverPath?: string;
```
解构加 `dataDriverPath`。ComponentManagement 中 `<FieldConfigTable ... />`（`:1137`）传 `dataDriverPath={dataDriverPath}`。

- [ ] **Step 6: 自检 + 提交**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json` → 0 错误
```bash
git add cpq-frontend/src/pages/component/sqlViewPath.ts cpq-frontend/src/pages/component/sqlViewPath.test.ts cpq-frontend/src/pages/component/FieldConfigTable.tsx cpq-frontend/src/pages/component/ComponentManagement.tsx
git commit -m "feat(component): 铺 dataDriverPath→FieldConfigTable 数据通路 + extractSqlViewName"
```

---

## 阶段三 · 请求2（行键资格实时刷新）+ 请求3（统一点选取数）

### Task 8: 请求2 — 复现并定位行键资格不刷新根因（systematic-debugging）

> **REQUIRED SUB-SKILL:** superpowers:systematic-debugging。**先复现再改，不盲改。**

**Files:**
- 调查：`ComponentManagement.tsx`（`refreshRowKeyCandidates` `:850`、`rowKeySignature` `:872`、effect `:878`、`handleSelectComponent` `:898/:926`、catch `:867`）
- 调查：`ComponentDriverService.java`（`:1065` 撞名、`:1122` 空集、`:1127` 跨组件 fallback）

- [ ] **Step 1: 复现**

在主工作区前端打开组件管理，选一个 NORMAL 组件，造一个 INPUT_TEXT 字段名 == 某 driver 列名（行键复选框应禁用，tooltip「撞名」）。改字段名为不撞名 → 观察复选框是否在 ~1s 内自动放开。F12 Network 看 `row-key-candidates` 请求是否触发、返回什么。记录现象。

- [ ] **Step 2: 按嫌疑清单二分定位**

用 `codegraph_trace` 跑 `row-key-candidates` 端点 → `computeRowKeyCandidates` → `resolveRowKeyCandidates` 全链。逐一验证：
1. driver 是否 `$视图`？非视图 → `loadDriverColumnNames` 返空 → `haveColumns=false` → 撞名分支不进（`:1066`）。
2. 改名后 `row-key-candidates` 是否真的重新请求？响应里该字段 `eligible` 是否翻 true？若请求没发 → effect/防抖问题；若发了但仍 false → 后端列集问题。
3. 是否 `catch`/非 NORMAL 分支把 map 置空（`:867`/`:926`）。
4. 是否跨组件 fallback 取错列集（`:1127`，导入副本场景）。

- [ ] **Step 3: 记录根因结论**

把复现步骤 + 确认的根因写进 `docs/RECORD.md`（一行）和本任务备注。**根因决定 Step 4 改哪里**：
- 命中 1/2/3（前端）→ 改 `ComponentManagement.tsx`。
- 命中 4（后端串号）→ 改 `ComponentDriverService.java` 的 `loadDriverColumnNames` fallback 加 componentId 维度。

- [ ] **Step 4: 按根因修复（前端典型修法）**

> 若根因是前端 effect/map（最可能命中 2 或 3），典型修法：确保改名后 candidates 可靠刷新且失败不静默清空。示例（按实际根因调整）：

```typescript
// refreshRowKeyCandidates 的 catch 不要整体清空 map，保留上次结果避免全禁用闪烁
} catch {
  // 保留旧候选，仅记录；避免请求偶发失败导致全字段 disabled
  console.warn('[rowKeyCandidates] refresh failed, keep previous map');
}
```

> 若根因是后端串号（命中 4），在 `loadDriverColumnNames` 的 `find("sqlViewName", viewName)` 加 `and componentId =` 维度（参照记忆 `cpq-sqlview-cache-key-needs-component-dim`），并 `touch` 一个 java 文件触发 Quarkus 重启，`curl /q/health` 期望 200。

- [ ] **Step 5: 验证 + E2E + 提交**

手动：改名后 ≤1s 复选框自动放开，无需重进。
Run E2E（协议文件改动，双 spec）：
```bash
cd cpq-frontend
npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list
npx playwright test --config=e2e/playwright.config.ts e2e/composite-product-flow.spec.ts --reporter=list
```
Expected: 全 `passed`，`'加载中' final count = 0`。

```bash
git add -A cpq-frontend/src/pages/component cpq-backend/src/main/java/com/cpq/component 2>/dev/null
git commit -m "fix(component): 行键资格随改名实时刷新(根因:<填实际根因>)"
```

---

### Task 9: 请求3 — 共享展示主体 `ViewColumnPickerBody`

**Files:**
- Create: `cpq-frontend/src/pages/component/ViewColumnPickerBody.tsx`
- Test: `cpq-frontend/src/pages/component/ViewColumnPickerBody.test.ts`

纯展示组件：给定视图列表 + 列列表 + 当前选中，渲染「视图选择 + 列以可点标签铺开」，点列回调。数据获取由宿主负责（保持 body 纯净可测）。

> 注：仓库**未引入 `@testing-library/react`**（已确认 `package.json` 无此依赖），不做组件 render 测试，避免新增依赖；只对纯函数 `buildColumnPath` 做单测，组件交互走 Vite 200 + 手动验证。

- [ ] **Step 1: 写失败测试（纯函数）**

```typescript
// ViewColumnPickerBody.test.ts
import { describe, it, expect } from 'vitest';
import { buildColumnPath } from './ViewColumnPickerBody';

describe('buildColumnPath', () => {
  it('拼 $视图.列', () => expect(buildColumnPath('cp_view', '品名')).toBe('$cp_view.品名'));
  it('英文列名', () => expect(buildColumnPath('v_cost', 'unit_price')).toBe('$v_cost.unit_price'));
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-frontend && npx vitest run src/pages/component/ViewColumnPickerBody.test.ts`
Expected: FAIL（导出不存在）。

- [ ] **Step 3: 实现**

```tsx
// ViewColumnPickerBody.tsx
import React from 'react';
import { Select, Tag, Empty, Typography } from 'antd';
import type { ComponentSqlView } from '../../services/componentSqlViewService';

const { Text } = Typography;

export function buildColumnPath(viewName: string, col: string): string {
  return `$${viewName}.${col}`;
}

interface Props {
  views: ComponentSqlView[];
  selectedViewName?: string;
  onSelectView?: (viewName: string) => void;
  /** driverOnly：锁定到 driver 视图，视图选择只读 */
  driverOnly?: boolean;
  onPick: (path: string, label: string) => void;
  /** 当前已选路径（高亮用） */
  currentPath?: string;
}

export const ViewColumnPickerBody: React.FC<Props> = ({
  views, selectedViewName, onSelectView, driverOnly, onPick, currentPath,
}) => {
  const view = views.find((v) => v.sqlViewName === selectedViewName) ?? views[0];
  const cols = view?.declaredColumns ?? [];
  return (
    <div>
      <div style={{ marginBottom: 8 }}>
        <Text type="secondary" style={{ fontSize: 12 }}>选择视图</Text>
        <Select
          style={{ width: '100%', marginTop: 4 }}
          value={view?.sqlViewName}
          disabled={driverOnly}
          onChange={(v) => onSelectView?.(v)}
          options={views.map((v) => ({ label: v.sqlViewName, value: v.sqlViewName }))}
          placeholder="选择 SQL 视图"
        />
        {driverOnly && <Text type="secondary" style={{ fontSize: 11 }}>（字段只能取 driver 视图的列）</Text>}
      </div>
      <div>
        <Text type="secondary" style={{ fontSize: 12 }}>点击字段列即选中</Text>
        <div style={{ marginTop: 6, display: 'flex', flexWrap: 'wrap', gap: 6 }}>
          {cols.length === 0
            ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="该视图无列 / 请先把数据驱动路径配为 SQL 视图" />
            : cols.map((c) => {
                const path = buildColumnPath(view!.sqlViewName, c.name);
                const active = currentPath === path;
                return (
                  <Tag.CheckableTag key={c.name} checked={active} onChange={() => onPick(path, c.name)}>
                    {c.name}
                  </Tag.CheckableTag>
                );
              })}
        </div>
      </div>
    </div>
  );
};
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd cpq-frontend && npx vitest run src/pages/component/ViewColumnPickerBody.test.ts`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add cpq-frontend/src/pages/component/ViewColumnPickerBody.tsx cpq-frontend/src/pages/component/ViewColumnPickerBody.test.ts
git commit -m "feat(component): 共享展示主体 ViewColumnPickerBody(选视图+可点列标签)"
```

---

### Task 10: 默认值来源（①）BASIC_DATA 内联点选

**Files:**
- Modify: `cpq-frontend/src/pages/component/DefaultSourceEditor.tsx`（props 加 componentId；BASIC_DATA 分支 `:299` 换内联 body）
- Modify: `cpq-frontend/src/pages/component/FieldConfigTable.tsx`（`<DefaultSourceEditor>` callsite `:627` 传 componentId）

- [ ] **Step 1: DefaultSourceEditor 加 componentId prop + 拉视图**

props 接口加 `componentId?: string;`。组件内加：
```typescript
const [views, setViews] = useState<ComponentSqlView[]>([]);
const [pickedViewName, setPickedViewName] = useState<string | undefined>();
useEffect(() => {
  if (!open || !componentId) return;
  componentSqlViewService.list(componentId)
    .then((r) => setViews(r.data || []))
    .catch(() => setViews([]));
}, [open, componentId]);
```
import `componentSqlViewService` 与 `ViewColumnPickerBody`、`ComponentSqlView` 类型。

- [ ] **Step 2: 替换 BASIC_DATA 分支（`:299-312`）的 `<Input>` 为内联 body**

```tsx
{type === 'BASIC_DATA' && (
  <Form.Item label="基础数据来源（选视图 → 点字段）" required>
    <ViewColumnPickerBody
      views={views}
      selectedViewName={pickedViewName}
      onSelectView={setPickedViewName}
      currentPath={bnfPath}
      onPick={(path) => setBnfPath(path)}
    />
    {bnfPath && <div style={{ marginTop: 6, fontSize: 12 }}>已选：<code>{bnfPath}</code></div>}
  </Form.Item>
)}
```

> ① 不做 driver 过滤（`driverOnly` 不传）——默认值来源可取任意视图列（spec R3）。`submit()` 里 BASIC_DATA 分支已有 `onConfirm({ type:'BASIC_DATA', path: bnfPath.trim() })`（`:121-125`），无需改。

- [ ] **Step 3: FieldConfigTable 传 componentId**

`<DefaultSourceEditor>` callsite（`:627` 附近）加 `componentId={componentId}`（FieldConfigTable 已有 `componentId` prop）。

- [ ] **Step 4: 自检 + 提交**

Run:
```bash
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/component/DefaultSourceEditor.tsx
```
Expected: 0 错误；200。
手动：某 INPUT 字段 → 配默认值来源 → 选「基础数据」→ 选视图 → 点字段 → 已选路径出现 → 保存。

```bash
git add cpq-frontend/src/pages/component/DefaultSourceEditor.tsx cpq-frontend/src/pages/component/FieldConfigTable.tsx
git commit -m "feat(component): 默认值来源BASIC_DATA改内联选视图+点字段(任意视图列)"
```

---

### Task 11: 字段 `basic_data_path`（②）— 增强 PathPickerDrawer 列为可点 + driver 过滤

**Files:**
- Modify: `cpq-frontend/src/pages/component/PathPickerDrawer.tsx`（sql-view tab 列渲染改 body；新增 `driverViewPath?` / `disablePredicate?` 可选 prop）
- Modify: `cpq-frontend/src/pages/component/FieldConfigTable.tsx`（`<PathPickerDrawer>` callsite `:522` 传 `driverViewPath={dataDriverPath}`、`disablePredicate`）

- [ ] **Step 1: PathPickerDrawer 新增可选 props（默认不改变现有行为）**

props 接口加：
```typescript
  /** 传入则把可选视图过滤到该 driver 视图，并锁定（字段 basic_data_path 用） */
  driverViewPath?: string;
  /** 关闭谓词构造（C2 纯点选） */
  disablePredicate?: boolean;
```

- [ ] **Step 2: sql-view tab 内用 ViewColumnPickerBody 渲染列（替换列下拉）**

在 PathPickerDrawer 的 COMPONENT sql-view 区，把已加载的视图按 `driverViewPath` 过滤后交给 body：
```tsx
import { extractSqlViewName } from './sqlViewPath';
import { ViewColumnPickerBody } from './ViewColumnPickerBody';
// ...
const driverViewName = extractSqlViewName(driverViewPath);
const visibleViews = driverViewName
  ? sqlViews.filter((v) => v.sqlViewName === driverViewName)
  : sqlViews;
// 渲染：
<ViewColumnPickerBody
  views={visibleViews}
  selectedViewName={driverViewName ?? selectedSqlViewName}
  onSelectView={driverViewName ? undefined : setSelectedSqlViewName}
  driverOnly={!!driverViewName}
  currentPath={/* 当前 manual path 回填值 */ undefined}
  onPick={(path, label) => onConfirm(path, label)}
/>
```
> `disablePredicate` 为真时不渲染 `sqlExtraPredicate` 输入区（`:181/:190` 那块谓词构造跳过）。保留原 `selectedSqlViewName`/`selectedSqlColumn` 状态供未传 `driverViewPath` 的旧调用方（核价模板列在 Task 12 处理）。

- [ ] **Step 3: FieldConfigTable 调用处传 props**

`<PathPickerDrawer>`（`:522`）加：
```tsx
driverViewPath={dataDriverPath}
disablePredicate
```

- [ ] **Step 4: 自检 + 提交**

Run:
```bash
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/component/PathPickerDrawer.tsx
```
Expected: 0 错误；200。
手动：BASIC_DATA 字段 → 配物理表路径 → 抽屉里只列 driver 视图、列以可点标签呈现、点一下即选、无谓词输入框。

```bash
git add cpq-frontend/src/pages/component/PathPickerDrawer.tsx cpq-frontend/src/pages/component/FieldConfigTable.tsx
git commit -m "feat(component): 字段basic_data_path选择器列改可点+只列driver视图+无谓词"
```

---

### Task 12: 核价 Excel 模板列（④）— 列改可点（沿用跨视图，不做 driver 过滤）

**Files:**
- Modify: `cpq-frontend/src/pages/costing/CostingTemplateConfig.tsx`（`<PathPickerDrawer>` callsite `:548`）

- [ ] **Step 1: 确认 Task 11 的 body 渲染对"未传 driverViewPath"分支生效**

PathPickerDrawer 未传 `driverViewPath` 时，`visibleViews = sqlViews`（全部视图），`driverOnly=false`，视图下拉可切。④ 复用此默认分支即得"选视图 → 点列"。CostingTemplateConfig 调用处**无需传 driver 过滤**，但可显式不传 `disablePredicate`（模板列若需谓词保留能力）或传 `disablePredicate`（统一纯点选）——按 spec「全站点选」传 `disablePredicate`。

- [ ] **Step 2: 调用处可选传 disablePredicate**

`<PathPickerDrawer>`（`:548`）按需加 `disablePredicate`。其余 props 不动（保持 TEMPLATE ownerContext）。

- [ ] **Step 3: 自检 + 提交**

Run:
```bash
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/costing/CostingTemplateConfig.tsx
```
Expected: 0 错误；200。
手动：核价模板列配 VARIABLE → 选视图 → 点列。

```bash
git add cpq-frontend/src/pages/costing/CostingTemplateConfig.tsx
git commit -m "feat(costing): 模板列路径选择器列改可点(统一点选,沿用跨视图)"
```

---

### Task 13: 历史路径「非 driver 列」保留 + 标注提醒（②场景）

**Files:**
- Modify: `cpq-frontend/src/pages/component/FieldConfigTable.tsx`（BASIC_DATA 字段已配 `basic_data_path` 渲染处 `:210-238`）

- [ ] **Step 1: 判定并标注**

在 BASIC_DATA 字段已配路径的渲染块（`:228` 那个显示 `{record.basic_data_path}` 的 Button 附近）加：旧路径的视图名 != driver 视图名（或非 `$` 形态/含谓词 `[`）时显示黄色提醒。

```tsx
{(() => {
  const driverView = extractSqlViewName(dataDriverPath);
  const fieldView = extractSqlViewName(record.basic_data_path);
  const hasPredicate = (record.basic_data_path ?? '').includes('[');
  const nonDriver = hasPredicate || !fieldView || (driverView && fieldView !== driverView);
  return nonDriver ? (
    <Tooltip title="该路径非 driver 视图列（含谓词或指向别的表），建议重新配置为 driver 列">
      <Tag color="warning" style={{ marginLeft: 4 }}>⚠ 非driver列</Tag>
    </Tooltip>
  ) : null;
})()}
```
import `extractSqlViewName`、`Tag`、`Tooltip`（多数已 import）。

- [ ] **Step 2: 自检 + 提交**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json` → 0 错误
手动：找一个含谓词或非 driver 视图的旧字段 → 出现「⚠ 非driver列」Tag；合规字段无 Tag。

```bash
git add cpq-frontend/src/pages/component/FieldConfigTable.tsx
git commit -m "feat(component): 历史非driver列basic_data_path保留并标注提醒"
```

---

### Task 14: 阶段三回归 + 全量自检

- [ ] **Step 1: tsc + Vite 200（所有改动文件）**

Run:
```bash
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json
for f in pages/component/ComponentManagement pages/component/FieldConfigTable pages/component/DefaultSourceEditor pages/component/PathPickerDrawer pages/component/ViewColumnPickerBody pages/costing/CostingTemplateConfig; do
  curl -s -o /dev/null -w "$f %{http_code}\n" "http://localhost:5174/src/$f.tsx"
done
```
Expected: 0 错误；全部 200。

- [ ] **Step 2: 单测全绿**

Run: `cd cpq-frontend && npx vitest run src/pages/component`
Expected: 全 PASS。

- [ ] **Step 3: E2E 双 spec（请求2/3 协议级改动）**

Run:
```bash
cd cpq-frontend
npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list
npx playwright test --config=e2e/playwright.config.ts e2e/composite-product-flow.spec.ts --reporter=list
```
Expected: 全 `passed`，`'加载中' final count = 0`，8 Tab `'加载中'=0`。

- [ ] **Step 4: 三视图复测**

报价单视图 + 核价单视图 + 详情页关键 Tab 渲染正常（无新「(共N项)」/「加载中…」）。

- [ ] **Step 5: 更新 RECORD.md + 提交**

在 `docs/RECORD.md` 追加一行（格式见 CLAUDE.md）：
`[2026-06-12] 组件管理 - 自动保存草稿+行键资格实时刷新+统一点选取数 | ComponentManagement/FieldConfigTable/DefaultSourceEditor/PathPickerDrawer/ViewColumnPickerBody/CostingTemplateConfig | 草稿走localStorage不触发snapshot传播；请求2根因=<填>；请求3 C2只列driver列(字段)/任意视图列(默认值&模板列)`

```bash
git add docs/RECORD.md
git commit -m "docs(record): 组件编辑体验三项优化交付记录"
```

---

## 自检清单（合并前）

- [ ] `npx tsc --noEmit` 0 错误
- [ ] 所有改动 `.tsx` Vite 200
- [ ] `npx vitest run src/pages/component` 全绿
- [ ] E2E `quotation-flow` + `composite-product-flow` 双 spec passed，`'加载中'=0`
- [ ] 报价单 / 核价单 / 详情页三视图复测无回归
- [ ] 请求2 根因已写入 RECORD.md；若改后端则 `/q/health` 200
- [ ] 仅提交本特性涉及文件（不 `git add -A` 全仓）
- [ ] 由用户确认达标后，走 `superpowers:finishing-a-development-branch` 合并清理
