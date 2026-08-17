# 前端任务 —— repair-0727 报价侧树页签删除行 BUG

> 上游：`需求说明.md`、`api.md`（契约）、`backtask.md`（后端对端实现）。
> 分支：`fix/repair-0727-tree-delete-row`；工作区：`.claude/worktrees/repair-0727-tree-delete`。
> **共享约束**：5174 dev server 与 `node_modules` 是**主工作区共享**的 —— **不要**在 worktree 里 `npm install` 或另起 dev server。
> 自检需要跑 vite 时：软链主工作区 `node_modules` + 临时端口起一个（见 §F4），跑完关掉。

---

## 0. 开工前必读

- `docs/反模式.md` **AP-54**（过滤子集下标 / 单一口径不变量）、**AP-31/AP-37**（"加载中…"族）、**AP-51**（行数纪律）
- `需求说明.md` §11.1 实测：树删除端点响应**没有** `componentData`，前端只回灌了 `quoteCardValues`
  → 过滤所需的 `deletedRowKeys` 永远是旧值 → 行不消失（这就是症状 ①）

---

## F0（前置）—— 与后端 B0 配套：`buildUniqueRowKeys` 的树行口径

文件：`cpq-frontend/src/pages/quotation/useCardSnapshots.ts`（约 `:154-163`）

**背景（实测）**：后端 `FormulaCalculator` 对**树行**算的 effKey 带 `__nodeId::` 前缀
（实测 `formulaResults[0].rowKey = "S-3120014539::主料1"`），前端 `buildUniqueRowKeys` **不带** →
前端按无前缀键去查 `formulaResults` / `editRows` 会 miss。

**任务**：
1. 先与后端工程师确认 B0 的核查结论（**以后端对齐后的口径为准**，不要各改各的）。
2. 若后端确认统一为"树行带前缀"，则 `buildUniqueRowKeys` 增加可选入参（每行的 `__nodeId`），
   树行按 `nodeId + "::" + base` 算 raw key，再 `uniquifyRowKeys`。
   - 生效条件必须与后端一致：**仅报价侧 + 行有 `__nodeId`**；核价侧、无 `__nodeId` 的行**逐字节不变**。
3. 查表侧（`findKeyedValues` 之类按 rowKey 找 editRows/formulaResults 的地方）加**旧键回退**：
   新键未命中 → 用不带前缀的旧键再查一次（存量单据的 editRows 是旧键存的）。

**验收**
- [ ] 非树行 / 核价侧 effKey 逐字节不变（单测断言）
- [ ] 树行 effKey 与后端产出一致（可用 `quote_card_values.tabs[].formulaResults[].rowKey` 对拍）
- [ ] 旧键回退有单测

> ⚠️ 若后端 B0 核查结论是"证伪"（口径其实一致），本任务作废，**不要凭空改**。

---

## F1 —— `deletedRows.ts` 墓碑匹配加 `nodeId` 维度（后端镜像）

文件：`cpq-frontend/src/pages/quotation/deletedRows.ts`

1. `Tombstone` 类型增加可选 `nodeId?: string`。
2. `keepRow(effKey, fp, deleted)` → 增加 `nodeId` 参数，按 `api.md §2.2` 规则匹配：

```
删除 ⟺ t.fp === fp 且 ( !t.nodeId || !nodeId || t.nodeId === nodeId )
```

3. **与后端 `DeletedRowKeys.keepMask` 逐字节对齐**（这是硬契约，两侧成对改）。
4. 保留旧调用签名（不传 nodeId → 退化 fp 单键），避免其它调用点被动改。

**验收**
- [ ] `deletedRows.test.ts` 补：新墓碑区分同 fp 不同 nodeId / 旧墓碑退化 / 非树行不受影响
- [ ] 现有用例**不改**且通过

---

## F2 —— `buildSnapshotExpansions` 过滤时传 `__nodeId`

文件：`cpq-frontend/src/pages/quotation/QuotationStep2.tsx`（约 `:1483-1496`）

现状：
```ts
kept = kept.filter(({ br, i }) =>
  keepRow(uniqFull[i], rowFingerprint(rkfForSide, br?.driverRow ?? {}), tombs));
```

改为把该行的 `br.__nodeId`（**row 顶层，不在 driverRow 里**）一并传入。

> ⚠️ **AP-54 头号不变量不得动**：`uniqFull` 仍在**完整** baseRows 上算；过滤后子集**绝不**重算 key；
> `deletedRowKeys` **绝不**进 `driverExpansionKey`；`rowCount` 仍取 `kept.length`。

**验收**
- [ ] `buildSnapshotExpansions.deletedRows.test.ts` 补一条：两行同 fp 不同 `__nodeId` + 带 nodeId 的墓碑 → 只过滤 1 行
- [ ] 该文件现有 5 条不变量用例全部通过

---

## F3 —— 树删除回灌改走 `applyQuoteProjection`（**解症状 ①**）

### F3.1 抽屉侧
文件：`cpq-frontend/src/pages/quotation/BomTreeDeleteConfirmDrawer.tsx`（约 `:88-90`）

现状只取 `quoteCardValues`：
```ts
if (data?.quoteCardValues) onApplied(data.quoteCardValues);
```

改为把**整个 `data`** 交给上层（`onApplied` 的签名相应改为接收整个响应对象）。

### F3.2 页面侧
文件：`cpq-frontend/src/pages/quotation/QuotationStep2.tsx`（约 `:1768-1771`）

`applyTreeQuoteCardValues` 改为：
```ts
const applyTreeDeleteResult = (data: any) => {
  if (!data) return;
  if (Array.isArray(data.componentData)) {
    applyQuoteProjection(data);            // 权威投影：原子重灌 rows + deletedRowKeys + quoteCardValues
  } else if (data.quoteCardValues) {
    onUpdate({ quoteCardValues: data.quoteCardValues } as Partial<LineItem>);  // 回落旧行为（非 DRAFT）
  }
};
```

> **加叶子（`BomTreeAddLeafDrawer`）保持原样**：它改的是 `snapshot_rows`（baseRows 直接变），
> 回灌 `quoteCardValues` 即可见。**除非**你实测发现它也不生效 —— 那属于新问题，先报告不要顺手改。

### F3.3 在途窗口保护（对齐既有删除路径）
`handleDeleteDriverRow` 用 `pendingDeleteRef` 在删除在途期间抑制 bake effect 按错位下标写 `comp.rows`
（`QuotationStep2.tsx:1714 / :1817`）。**树删除必须同样处理**：确认前把 `componentId` 加入
`pendingDeleteRef`，`finally` 里移除。否则在途重渲染会按 N vs N-1 错位污染。

**验收**
- [ ] 删除确认后**不刷新页面**，该行当帧消失（症状 ① 闭环）
- [ ] 同料号挂另一父的行**仍在**，各列取值逐字段不变（无串行/位移）
- [ ] 响应不带 `componentData` 时回落旧行为不报错
- [ ] 删除在途期间无闪烁、无错位

---

## F4 —— 自检（强制，缺一不可）

```bash
cd .claude/worktrees/repair-0727-tree-delete/cpq-frontend

# 1) 类型检查：必须 0 错误
npx tsc --noEmit -p tsconfig.json

# 2) 单测
npx vitest run src/pages/quotation/deletedRows.test.ts \
               src/pages/quotation/buildSnapshotExpansions.deletedRows.test.ts \
               src/pages/quotation/rowKeyUniquify.test.ts

# 3) Vite transform 自检（tsc 不覆盖解析期错误）
#    worktree 无 node_modules → 先软链主工作区的，再用临时端口起 vite，跑完关掉
ln -s /home/joii/project/cpq/cpq-frontend/node_modules node_modules 2>/dev/null
npx vite --port 5199 --host 0.0.0.0 &      # 临时实例，勿占用共享的 5174
sleep 6
for f in pages/quotation/QuotationStep2.tsx pages/quotation/BomTreeDeleteConfirmDrawer.tsx \
         pages/quotation/deletedRows.ts pages/quotation/useCardSnapshots.ts; do
  echo -n "$f -> "; curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' "http://localhost:5199/src/$f"
done
kill %1
```

**每个改动的 `.tsx`/`.ts` 必须 HTTP 200。**

### E2E（协议级改动，强制）
本任务改了 `QuotationStep2.tsx` + `useDriverExpansions` 相关协议 → 按 `docs/E2E测试方法.md` 跑：
```bash
npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list
```
- 必须 `passed`，`'加载中' final count = 0`
- **已知环境噪声**：干净 master 上 `quotation-flow` 可能因夹具单缺产品分类而失败（见记忆 task-0712）。
  若失败，**必须做 A/B 对照**：在主工作区（未改动）跑同一 spec，两边同型失败 = 环境噪声，可放行并在交付说明写明；
  只有 worktree 失败而主工作区通过 = 真回归，必须修。

### 交付说明必须包含
1. `tsc --noEmit` 输出（0 错误）
2. 三个单测文件的通过数
3. 每个改动文件的 Vite 200 输出
4. E2E 结果（含 A/B 对照结论，如适用）
5. **UI 实证**：在 QT-20260726-0006 上手工删一行的前后截图（或 Playwright 截图），证明"不刷新即消失、另一条同料号行仍在"

---

## 红线（违反即打回）

1. **不得**破坏 AP-54 头号不变量（完整集算 key / 过滤后不重排 / 墓碑不进 cache key）
2. **不得**改动核价侧渲染分支（`side === 'COSTING'` 路径逐字节不变）
3. **不得**在 worktree 里 `npm install` 或占用共享的 5174 端口
4. **不得**用 `git add -A`；只 add 本任务明确改动的文件
5. 自报"完成"必须附**实跑输出 + UI 实证**，不接受"应该可以"
