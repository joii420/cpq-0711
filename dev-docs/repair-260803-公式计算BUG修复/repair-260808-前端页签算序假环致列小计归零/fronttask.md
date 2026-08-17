# fronttask · repair-0808 前端页签算序假环致列小计归零

> 输入文档：同目录 `需求文档.md`（FR/AC 编号以它为准）。**接口零改动**，无需读 `api.md` 的请求响应部分。
> 分支：`fix/repair-0808-crosstab-order-column-granularity`（worktree 内开发，勿在主工作区改）

---

## 1. 改动清单（只有两个文件）

| # | 文件 | 动作 |
|---|---|---|
| F-1 | `cpq-frontend/src/pages/quotation/crossTabOrder.ts` | **新增** `buildComponentDeps()` + 内部辅助（列粒度建图），保留现有 `extractSourceRefs` / `topoOrderComponents` 不动 |
| F-2 | `cpq-frontend/src/pages/quotation/QuotationStep2.tsx:1452-1478` | `buildCrossTabRows` 里内联的 `deps` 组装改为调用 F-1；`catch` 分支加 `console.error` 留痕 + 订正注释 |

**不改**：渲染层、行身份（`__effKey`/`__nodeId`/rowKey）、`useDriverExpansions`、`computeAllFormulas`、
`computeTabFormulasTree`、`ReadonlyProductCard`、后端任何文件。

---

## 2. F-1：`crossTabOrder.ts` 新增列粒度建图

### 2.1 权威参照（逐条镜像，不要自由发挥）

`cpq-backend/src/main/java/com/cpq/quotation/service/CrossTabComponentOrder.java`
- `buildComponentDeps(List<TabDep>)` :182
- `isOrderSensitiveColumn(...)` :211
- `isFormulaType(...)` :224
- `extractSubtotalRefDetails(...)` :240

### 2.2 契约

```ts
/** 参与拓扑的一个页签（仅 NORMAL；调用方自行过滤 SUBTOTAL/EXCEL）。 */
export interface TabDepInput {
  cid: string;                 // 图中的节点键 = componentId || componentCode || tabName（与调用方 ids 同源）
  code?: string;               // componentCode，解析 component_subtotal.component_code
  tabName?: string;            // 页签名，component_code 缺失时的回退解析键
  formulas?: FormulaLike[];    // 该页签 formulas
  fields?: FieldLike[];        // 该页签 fields（判被引用列是否顺序敏感）
}
interface FieldLike { name?: string; key?: string; field_type?: string; fieldType?: string }

/** cid → 依赖的 cid[]（可直接喂 topoOrderComponents）。 */
export function buildComponentDeps(tabs: TabDepInput[]): Record<string, string[]>
```

### 2.3 规则（缺一不可）

1. **建 `refToCid` 映射**：对每个 tab，把 `cid` / `code` / `tabName` 三个键都指向该 tab 的 `cid`（与后端 `refToCid` 同）。
2. **`cross_tab_ref`**：复用现有 `extractSourceRefs(formulas)`，**全量建边**，不做任何列粒度豁免。
3. **`component_subtotal`**：扫 `formulas[].expression[]` 中 `type === 'component_subtotal'` 的 token，取
   - `ref = component_code || tab_name`（都空 → 跳过）
   - `column = token.value`
   - `tabTotal = token.is_tab_total === true || column === '__amount_total__'`

   然后 `tcid = refToCid[ref]`；`tcid` 不存在（卡片外引用）或 `tcid === 本 tab.cid`（自引用，由 B2 两阶段处理）→ **不建边**。
4. **顺序敏感判定 `isOrderSensitiveColumn(targetFields, {column, tabTotal})`**（保守优先）：
   - `tabTotal` → `true`
   - `column` 为空 → `true`
   - `targetFields` 不是数组 → `true`
   - 在 `targetFields` 里找 `name || key === column` 的字段 → 返回 `isFormulaType(field_type ?? fieldType)`
   - 查无此列 → `true`
5. **`isFormulaType(ft)`** = `ft === 'FORMULA' || ft.endsWith('_FORMULA')`（勿硬编码枚举，见 D-4）。
6. 去重、保留首次出现序（与 `extractSourceRefs` 一致，`topoOrderComponents` 依赖稳定序）。

> ⚠️ 字段名兼容：前端 `ComponentDataItem.fields` 走 enrich 后是 **snake `field_type`**；夹具/结构快照里可能是 camel `fieldType`。两者都认（后端 `fieldTypeOf` 同样两认）。

---

## 3. F-2：`QuotationStep2.tsx` 接线

### 3.1 现状（要被替换的那段，1452-1478）

```ts
  const idKeyOf = new Map<string, string>();
  normals.forEach((c, i) => { /* componentId / componentCode / tabName → ids[i] */ });
  const deps: Record<string, string[]> = {};
  normals.forEach((c, i) => {
    const crossRefs = extractSourceRefs(c.formulas as any);
    const subRefs: string[] = [];
    for (const f of (c.formulas ?? [])) {
      for (const t of (((f as any)?.expression ?? []) as any[])) {
        if (t?.type === 'component_subtotal') {
          const refKey = (t.component_code && idKeyOf.get(t.component_code))
            || (t.tab_name && idKeyOf.get(t.tab_name));
          if (refKey && refKey !== ids[i]) subRefs.push(refKey);   // ← 页签粒度：假环成因
        }
      }
    }
    deps[ids[i]] = [...new Set([...crossRefs, ...subRefs])];
  });
  let order: string[];
  try { order = topoOrderComponents(ids, deps); }
  catch { order = ids; /* 环：退回原序，避免整卡渲染崩溃；模板保存层已拦截环 */ }
```

### 3.2 改后

```ts
  // repair-0808：建图下沉到 crossTabOrder.buildComponentDeps（列粒度，镜像后端 CrossTabComponentOrder）。
  // 页签粒度会把「引用别页签零依赖 INPUT 列」也算成顺序依赖 → 产品⇄物料 假环（QT-20260807-0146）。
  const deps = buildComponentDeps(normals.map((c, i) => ({
    cid: ids[i],
    code: c.componentCode,
    tabName: c.tabName,
    formulas: c.formulas as any,
    fields: c.fields as any,
  })));
  let order: string[];
  try { order = topoOrderComponents(ids, deps); }
  catch (e) {
    // 真环兜底：退回声明序保证整卡仍能渲染，但列小计可能不准 —— 必须留痕，不得静默。
    // （发布期后端 TemplateService.validateCrossTabRefs 已用同规则拦截真环，此处是最后一道防线。）
    order = ids;
    console.error('[crossTabOrder] 组件依赖成环，退回声明序，列小计可能不准：', e);
  }
```

- `idKeyOf` 若无其它使用点则一并删除（`buildComponentDeps` 内部自建 `refToCid`）。
- import 补 `buildComponentDeps`（与 `extractSourceRefs` / `topoOrderComponents` 同一 import 行）。
- **`ids` 的算法不动**（`c.componentId || c.componentCode || c.tabName`），保证 `deps` 的键与 `ids` 同源。

---

## 4. 边界与空态

| 场景 | 期望 |
|---|---|
| `formulas` 为空 / `fields` 为空的页签 | `deps[cid] = []`，不报错 |
| `component_subtotal` 引用卡片外页签（模板改过） | 不建边（与后端同），不报错 |
| 自引用（二阶列 `self#col`） | 不建边（由 `detectSecondOrderFields` 两阶段处理，行为不变） |
| 同 componentId 多实例（`__impN` 后缀） | `ids` 用 `componentCode`（含后缀）区分，`refToCid` 三键映射照旧，行为不变 |
| 真成环 | `order = ids` + `console.error`，卡片仍渲染 |

---

## 5. 自检项（工程师提交前必须自己跑，输出贴进交付说明）

- [ ] `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json` → **0 错误**
- [ ] `npx vitest run src/pages/quotation/crossTabOrder.test.ts src/pages/quotation/buildCrossTabRows.test.ts src/pages/quotation/columnSumsByComp.test.ts src/pages/quotation/subtotalColRefEndToEnd.test.ts src/pages/quotation/computeMultiSubtotal.test.ts src/pages/quotation/buildExcelSnapshot.test.ts src/pages/quotation/lineDiscount.test.ts src/pages/quotation/formulaParityQt0068.repair0805.test.ts` → 全绿
- [ ] `npx vitest run src/pages/quotation` → 全绿（无新增失败）
- [ ] 新增用例（见 `test.md` T-1~T-4）全绿
- [ ] **不做**「curl 5174 拿 200」这一项 —— 共享 dev server 服务的是主工作区，worktree 内改动 curl 不到（记忆 `cpq-worktree-frontend-selfcheck`）；页面验证由主线在合并后做
- [ ] `git show --stat` 自查：只含上述 2 个源文件 + 测试/夹具文件，**严禁 `git add -A`**

## 6. Task 列表

- [ ] T-F1 `crossTabOrder.ts` 新增 `buildComponentDeps` + `TabDepInput` 类型（含 §2.3 六条规则）
- [ ] T-F2 `QuotationStep2.tsx` 接线（§3.2），删除内联 `subRefs` 与不再使用的 `idKeyOf`
- [ ] T-F3 `catch` 留痕 + 注释订正
- [ ] T-F4 跑通 §5 全部自检并贴输出
