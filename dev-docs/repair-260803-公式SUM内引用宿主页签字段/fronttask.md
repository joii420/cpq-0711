# 前端任务 —— repair-0803 公式 SUM 内引用宿主页签字段

- 关联：`需求文档.md`（FR-1~FR-12 / AC-1~AC-17）、`api.md`
- 分支：与后端同分支 `fix/repair-0803-sum-host-field`（前后端必须同批次提交，见 §3 风险）

---

## 1. 任务清单

> ⚠️ **后端已完成（commit `7ebfa7b9` / `0f1f8603`），实现中有 4 处订正了原方案，前端必须照订正后的做法对齐。下面 F1/F2 已按实际落地重写。**

### F1. `b_field` 取值链补回退（FR-1 / FR-2）

- 文件：`src/utils/formulaEngine.ts`，`evalRowExpr`（`:377`）与 `b_field` 求值分支
- 规则**必须与后端逐字一致**：
  - `currentRow` 中**键存在**（含空串 `''`）→ 用之，**不回落**（尊重用户显式置空）
  - **键缺失** → 回落**宿主行已算字段值**
  - 都没有 → 0

- 🔴 **订正 1（关键，后端踩过坑）**：**不能回落到当前上下文的 `fieldValues`**。
  在 `targetExpr` 子上下文里 `fieldValues` 装的是**被聚合源页签当前行的列**（前端 `evalRowExpr` 里的
  `aFieldValues` 就是从 `ar` 灌的），宿主的已算字段根本不在里面；直接回落会让 `b_field` 串到源页签同名列上。
  后端为此新增了 `RowContext.hostFieldValues` 专用通道（顶层与 `fieldValues` 同引用，
  `targetRowValue` 原样透传）。**前端需要等价机制**：给 `evaluateExpression` 增一个「宿主已算字段值」
  参数，顶层传 `computeAllFormulas` 里那个随算随更新的 `fieldValues`，`evalRowExpr` 递归时原样透传。
- ⚠️ 不得改 `mergedRow` 的构造（`{ ...hostRow, ...ar }`）——它同时供内层 KSUM 的 match 键取值

- [ ] F1 完成

### F2. 依赖收集递归 targetExpr（FR-3）

- 文件：`src/pages/quotation/QuotationStep2.tsx`，`getFormulaDeps`（`:407-413`）
- 现状只 `filter(t => t.type === 'field')`，改为递归进 `cross_tab_ref.targetExpr`

- 🔴 **订正 2（关键）**：收集规则不是"b_field + field"，而是：
  - **任意层级**的 `b_field` → 收（其语义恒为「宿主行的列」）
  - **仅顶层**的 `field` → 收；**`targetExpr` 内的 `field` 一律不收** —— 它指的是**被聚合源页签**的列，
    与宿主字段同名纯属巧合，收了会凭空建边甚至误报环
- **递归终止**：遇 `projectToHostKey === true`（KSUM 子 token）停止下探
- 后端实现见 `FormulaCalculator.addExprFieldDeps`（带 `inTargetExpr` 标志的五参重载），**照抄其判定**

- [ ] F2 完成

### F3. 环链路抽屉（FR-10 / FR-11，**只读展示**）

- 新建：`src/pages/component/FormulaCycleDrawer.tsx`
- 组件规范：Ant Design `Drawer`，`placement="right"`，宽度 **720**（链路可能较长，需横向空间）
- Props：`open` / `onClose` / `cycles: FormulaCycle[]`（类型按 `api.md` §1）
- 展示结构：

```
标题：公式存在循环引用（N 处）

[环 1]  组件「物料」内
   「原材料成本」→「来料加工费」→「原材料成本」
     · 「原材料成本」的 公式「v2-原材料成本公式(银点类)」 中引用了 [来料加工费]
     · 「来料加工费」的 公式「来料加工费取值公式」 中引用了 [原材料成本]

[环 2]  跨页签
   页签「产品」→ 页签「物料」→ 页签「产品」
     · 页签「产品」的 公式「管理费」 中 跨页签引用 [材料成本]
     · 页签「物料」的 公式「v2-原材料成本公式(银点类)」 中 跨页签引用 [税率]
```

- 多个环用 `Collapse` 分组（默认全展开；≥3 个时默认只展开第 1 个）
- 链路渲染：`nodes` 按序渲染并**自行闭合回首节点**（后端不重复下发首尾）
- `scope === 'FIELD'` 显示 `组件「X」内`；`scope === 'TAB'` 显示 `跨页签`
- **本期只读**：节点不可点击、不做跳转（跳转增强已登记 BACKLOG）
- 底部按钮：仅「知道了」关闭

- [ ] F3 完成

> 🔴 **订正 3**：后端 `message` **保持原多行定位文案逐字不变**（被既有测试
> `ComponentServiceConditionalValidationTest.cycleMessage_containsLocation` 锁死，也是 api.md
> 「不消费 data 的路径仍可只读 message」的落地）。`data.errorType` / `data.cycles` 是**纯新增**。
> 故前端判定**只认 `errorType`**，且既有 `showSaveError` 的 notification 分支必须原样保留。
>
> 🔴 **订正 4**：页签级环的**发布期校验口径已与渲染期对齐**（后端 B7）。原实现只收
> `cross_tab_ref` 边，这正是「模板发布通过、一渲染就报环」的成因。现在发布期也会检出
> `scope=TAB` 的环并下发结构化链路 —— F5 因此确实有真实触发场景。

### F4. 组件保存接入抽屉（FR-10）

- 文件：`src/pages/component/ComponentManagement.tsx`，`showSaveError`（`:40-59`）
- 增加分支：**先判 `errorType`，再走既有路径**

```ts
const data = err?.response?.data?.data;
if (data?.errorType === 'FORMULA_CYCLE') {
  setCycles(data.cycles); setCycleDrawerOpen(true);
  return;
}
showSaveError(msg);   // 既有：多行走 notification、单行走 message
```

- ⚠️ **禁止**用 `msg.includes('循环引用')` 文本匹配判定（文案会变，`errorType` 才是契约）
- 既有 `showSaveError` 的 notification 分支**保留不动**（其它多行错误仍需要它）

- [ ] F4 完成

### F5. 模板发布接入抽屉（FR-11）

- 文件：模板管理页发布动作的错误处理处
- 同 F4 的判定逻辑，复用同一个 `FormulaCycleDrawer`
- 发布失败时 `scope` 为 `TAB`

- [ ] F5 完成

### F6. 配置期提示（FR-6）

- 文件：`src/pages/component/CrossTabRefDrawer.tsx`（`:485-499`「本组件字段」下拉）
- 现状 `currentFields.map(...)` **不做任何过滤**，FORMULA 字段照样可选
- 改为：option 上标注类型，选中 `field_type === 'FORMULA'` 的字段插入 `b_field` 后，在编辑区显示一行提示

> ⚠️ 「<字段名>」是公式列，写在 SUM 内时**将对每个匹配行各计入一次**；若只需整单计一次，请写在 SUM 外。

- **不禁用**该选项（决策 D-5：不堵，只提示）

- [ ] F6 完成

---

## 2. 状态管理与缓存

| 项 | 说明 |
|---|---|
| 抽屉状态 | `ComponentManagement` / 模板页各自局部 `useState`，不进全局 store |
| 缓存影响 | **无**。本次不改 `useDriverExpansions` / `usePathFormulaCache` 的 key 结构，不涉及 AP-31 / AP-37 的 fingerprint 维度 |
| 渲染影响 | F1/F2 改的是求值与排序，不改字段类型协议 → **AP-44 不触发**（无 `field_type` 新增/变更） |

---

## 3. 风险

| 风险 | 规避 |
|---|---|
| **前后端只改一侧 → 编辑页与存库值分叉** | F1/F2 与后端 B1/B2 **必须同分支同批次提交**；AC-2 对拍用例守门 |
| 取值/依赖口径两端不一致（判空、递归终止条件） | 以 `需求文档.md` §5.1 / §5.2 为唯一定义，两端照同一段文字实现；对拍用例覆盖三种形状 |
| 文本匹配判错误类型 | F4/F5 一律用 `errorType`，评审时重点看 |

---

## 4. 自检项

- [ ] `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json` → 0 错误
- [ ] 每个改动的 `.tsx` 走 Vite：`curl -s -o /dev/null -w '%{http_code}\n' --noproxy '*' http://localhost:5174/src/<相对路径>` → 200
  - [ ] `src/pages/component/FormulaCycleDrawer.tsx`
  - [ ] `src/pages/component/ComponentManagement.tsx`
  - [ ] `src/pages/component/CrossTabRefDrawer.tsx`
  - [ ] `src/pages/quotation/QuotationStep2.tsx`
- [ ] `curl -s -o /dev/null -w '%{http_code}\n' --noproxy '*' http://localhost:5174/` → 200
- [ ] **E2E 必跑**（改了 `QuotationStep2.tsx`，属协议级文件）：
  ```
  npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list
  ```
  必须 `passed` + `'加载中' final count = 0`（AC-17）
- [ ] 抽屉截图（字段级环 + 页签级环各一张）附进 `test-report.md`

---

## 5. UI 规范符合性

- [x] 用 `Drawer` 而非 `Modal`（`CLAUDE.md` UI 交互规范）
- [x] 宽度取规范档位（480/720/960/1200）中的 **720**
- [x] 不新增列表页 → 不涉及 `SelectableTable` + 工具栏动作规范
- [x] 既有 `message` / `notification` 轻量反馈路径保留（属规范明确的例外白名单）
