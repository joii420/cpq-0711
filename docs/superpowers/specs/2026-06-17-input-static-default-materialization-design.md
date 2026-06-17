# 设计方案：INPUT 字段默认值解析统一化（default_source 实时 + 静态 content 兜底）

> 日期：2026-06-17 | 状态：待评审（v3：B 方向 + 全链补齐；含独立评审 S1~S4 修订） | 类型：报价/核价 渲染+计算+落库+导出 全链一致性修复（字段类型协议级，AP-44）

---

## 1. 背景与问题

组件管理给**输入类字段**（`INPUT_TEXT` / `INPUT_NUMBER`）配了默认值后，报价单填数向导第 2 步渲染时**不带出**。以 `元素` 组件 COMP-0029 为例（DB `component.fields` 实测）：

| 字段 | field_type | content | default_source | 现象 | 期望 |
|---|---|---|---|---|---|
| 含量 | INPUT_NUMBER | `''` | `{BASIC_DATA, $ys_view.重量}` | 正常 | （维持） |
| 单价 | INPUT_NUMBER | `''` | 无 | 空 | （维持，无默认） |
| **货币** | INPUT_TEXT | `'RMB'` | 无 | **空（bug）** | 带出 RMB，可改，保存/计算/导出 |
| **计价单位** | INPUT_TEXT | `'KG'` | `{BASIC_DATA, $ys_view.单位}` | **空（bug）** | **优先带出 `$ys_view.单位`（实时）**，取空时兜底 KG；可改，参与计算/导出/核价 |

用户已确认目标（A）：默认值可编辑 + 未改动也作为真实值参与公式/小计/导出/核价；并确认（B）：**`计价单位` 要数据源 `$ys_view.单位` 优先于静态 KG**。

### 1.1 根因：INPUT 默认值解析在全工程"各写各的、覆盖不一致"（AP-44 协议漂移）

同一套"INPUT 默认值解析"逻辑在 ~8 处各拷一份，覆盖参差（行号按当前代码核对，实现时复核）：

| 消费点 | 管 INPUT_TEXT? | default_source.type 分支 | 静态 content 兜底? |
|---|---|---|---|
| `ComponentCell.tsx` **编辑态** `:644` | ❌ 仅 INPUT_NUMBER | GLOBAL_VARIABLE + BNF_PATH（**无 BASIC_DATA**） | ✅(`:673`，但因 :644 gate，TEXT 到不了) |
| `ComponentCell.tsx` **只读态** `:600` | ✅ TEXT+NUMBER | GLOBAL_VARIABLE + BNF_PATH（需复核 BASIC_DATA） | ✅(`:632`) |
| `QuotationStep2.tsx#computeAllFormulas` `:577` | ❌ 仅 INPUT_NUMBER | GLOBAL_VARIABLE + BNF_PATH + **BASIC_DATA(`:598`)** | ✅ |
| `QuotationWizard.tsx#snapshotRows` `:810-902` | ❌ **完全不解析 INPUT 默认值** | — | ❌（仅 BASIC_DATA/FIXED_VALUE/FORMULA 回填） |
| `useCardSnapshots.ts` / `rowDedup.ts`（行键用） | （行键场景） | BNF_PATH + BASIC_DATA | — |
| `usePathFormulaCache.ts` `:103` | — | **仅 BNF_PATH，故意排除 BASIC_DATA**（`$view.中文列` 单列路径撞键，有注释 :99/:172） | — |
| 后端 `CardSnapshotService#buildCardValues/buildCostingCardValues` + Excel/提交快照 | 待核 | 待核 | 待核 |

**净结果**：
- `货币`（静态、无 default_source）—— **全程无人兜底**（snapshotRows 不写、编辑态 :644 gate 排除 TEXT），故全空。
- `计价单位`（INPUT_TEXT + BASIC_DATA 源）—— 编辑态被 `:644` 的 `INPUT_NUMBER` gate + 缺 BASIC_DATA 分支**双重漏掉**；计算态被 `:577` 的 `INPUT_NUMBER` gate 漏掉。只读态本应能显示（:600 含 TEXT），印证"详情有、编辑空"的割裂。

> 这是典型 AP-44：默认值解析逻辑没有单一真源，散落多处、各自演进 → 新字段类型/新 type 分支必然漏某几处，静默失败。

---

## 2. 目标

1. `INPUT_TEXT` / `INPUT_NUMBER` 的默认值在**所有消费点**按统一优先级解析：`已有用户值 > default_source(GLOBAL_VARIABLE | BNF_PATH | BASIC_DATA，实时) > 静态 content > 空`。
2. 默认值可编辑；用户未改动时也作为真实值参与**公式/小计/Excel 导出/核价/提交快照**。
3. 编辑态、只读/详情态、计算、导出、核价五处**完全一致**（消除割裂）。
4. 根治"解析逻辑多处拷贝、覆盖不一致"的漂移：收敛为**单一共享解析器**。

---

## 3. 方案：抽统一解析器 + 全链路由 + 静态 content 持久化

### 3.1 单一共享解析器（核心）

新增纯函数到**独立 util**（建议 `cpq-frontend/src/pages/quotation/inputDefaults.ts`，不放进 `ComponentCell.tsx`，避免渲染组件被 Wizard/Step2 反向依赖）：

```ts
export interface InputDefaultCtx {
  basicDataValues?: Record<string, any>;   // driver 行级 KV（@gvar:CODE / bnfDriverLookupKey(path)）
  partNo?: string;
  pathCache?: Record<string, any>;          // partNo::path 兜底
}

/**
 * 解析 INPUT_TEXT / INPUT_NUMBER 的有效默认值（不含"已有用户值"判断，调用方先判 row[key] 非空则不调）。
 * 优先级：default_source(GLOBAL_VARIABLE | BNF_PATH | BASIC_DATA，实时) > 静态 content > undefined。
 * 返回原始值（字符串/数值），数值归一交由数值消费点处理。
 */
export function resolveInputDefault(field: ComponentField, ctx: InputDefaultCtx): string | number | undefined
```

解析顺序（与现有各处分支合并去重，覆盖**三种 type**）：
1. `default_source.type === 'GLOBAL_VARIABLE'` → `basicDataValues['@gvar:'+code]`
2. `default_source.type === 'BNF_PATH'` → `basicDataValues[bnfDriverLookupKey(path)]`，缺则 `pathCache[partNo::path]`
3. `default_source.type === 'BASIC_DATA'` → 同 BNF_PATH 的 `bnfDriverLookupKey(path)` 查法（与 `useCardSnapshots.ts:82`、`rowDedup.ts:40` 既有的 `(BNF_PATH || BASIC_DATA)` 合并写法一致）
4. 以上空 → `field.content`（非空）
5. 仍空 → `undefined`

**INPUT_TEXT 与 INPUT_NUMBER 一视同仁**（消除 `:644`/`:577` 的 `INPUT_NUMBER` gate）。数值归一（仅 INPUT_NUMBER）在数值消费点用与 `ComponentCell.onChange`（`:691` `/^-?\d*\.?\d*$/`）**同源**的校验做，非法返回不污染数值列。

### 3.2 全链路由：所有消费点改调统一解析器（AP-44 检查点）

| # | 消费点 | 现状 | 改法 |
|---|---|---|---|
| 1 | `ComponentCell.tsx` 编辑态 `:644-676` | 仅 NUMBER、无 BASIC_DATA | 改调 `resolveInputDefault`，INPUT_TEXT 也解析（默认值进 `placeholder`/初值，行为见 §3.4） |
| 2 | `ComponentCell.tsx` 只读态 `:599-635` | TEXT+NUMBER、缺 BASIC_DATA | 改调同一解析器，补齐 BASIC_DATA |
| 3 | `QuotationStep2.tsx#computeAllFormulas` `:563-640` | 仅 NUMBER | INPUT 字段值收集改调解析器（TEXT+NUMBER）；数值列归一参与公式，文本列写入 `out.fieldValues`/供 EXCHANGE/单位换算引用 |
| 4 | 🔴 `QuotationWizard.tsx#snapshotRows` `:810-902` | 不解析 INPUT 默认值 | 新增「INPUT 默认值兜底」块（BASIC_DATA 回填后、FORMULA 之前），调解析器；**持久化策略见 §3.4** |
| 5 | `QuotationStep2.tsx#buildCrossTabRows` + 渲染回填 | 已有 INPUT default_source 解析（见 `crossTabInputDefaultSource.test.ts`） | 收敛到统一解析器，确保跨 Tab 公式取数含 TEXT + content 兜底 |
| 6 | 建行函数 `buildEmptyRow` ×3（`BulkImportPartsDrawer.tsx:94` / `AddProductModal.tsx:36` / `enrichComponentData.ts:94`） | INPUT 写 `''` | 维持写 `''`（默认值由解析器在渲染/保存动态给出，不在建行写死，避免与 driver 行 baseRow 不一致）；**仅静态 content 的持久化在 snapshotRows 落，见 §3.4** |
| 7 | 🟠 后端 `CardSnapshotService#buildCardValues/buildCostingCardValues` + 核价 Excel + 提交快照 `SnapshotCollectorService` | 待核 | 核实/补齐 INPUT_TEXT + default_source(三 type) + content 兜底的解析，保证核价/导出/提交带出（见 §5.5） |
| — | ⚠️ `usePathFormulaCache.ts:103` BASIC_DATA 排除 | 故意排除 | **保持不动**：路径采集层排除 `$view.中文列` 是既有防撞键约束（:99/:172 注释），与本次"取值解析"是两件事，不可顺手改 |

### 3.3 静态 content 与 default_source 的持久化区别（B 的落地）

- **default_source 值（如 计价单位 ← `$ys_view.单位`）**：**实时解析、不冻结**进 `row_data`（与现有 `含量` 一致）。在渲染/计算/导出/核价各点由解析器实时给出。这样 driver 数据变化能反映，符合 B（源优先）。
- **静态 content（如 货币 = RMB）—— 仅对"无 default_source"的字段**：在 `snapshotRows` 兜底块里**冻结进 `row_data`**（它是常量，冻结安全），保证后端核价/Excel/提交读 `row_data` 时也带出。
- **同时配 content + default_source 的字段（计价单位）**：**不冻结 content**；其值统一由解析器给出（源优先、源空则 content 兜底，均实时）。后端各链同样走解析器逻辑（§3.2 #7），保证一致。

> 一句话：**有源的实时、无源的静态常量冻结**。优先级 `已有用户值 > default_source(实时) > 静态 content` 在所有消费点一致。

### 3.4 编辑态如何"可改且未改也算数"

编辑态 `<input>`：
- `row[key]` 非空（用户已改 / driver BASIC_DATA 回填）→ `value=row[key]`（铁律：不被默认值覆盖）。
- `row[key]` 空 → `value = resolveInputDefault(field, ctx)`（**作为初值显示，非仅 placeholder**），用户可直接改。
- 用户未改动时该默认值的"算数"通过两条保证：(a) 静态 content 在 `snapshotRows` 冻结落库；(b) default_source 值由各消费点解析器实时给出（计算/导出/核价/重开均一致）。

---

## 4. 数据流

```
field 货币(content=RMB, 无源)            field 计价单位(content=KG, 源=$ys_view.单位)
        │                                          │
   resolveInputDefault → "RMB"            resolveInputDefault → 源值(实时) || "KG"
        │                                          │
  ┌─────┴───────────────┐               ┌──────────┴───────────────┐
  编辑态 value=RMB(可改)                  编辑态 value=源值/KG(可改)
  computeAllFormulas 读 RMB              computeAllFormulas 读 源值/KG
  snapshotRows 冻结 RMB → row_data       snapshotRows 不冻结(源实时)
  后端 buildCostingCardValues 读 row_data 后端解析器实时给 源值/KG(§3.2#7)
  Excel/提交 读 row_data                  Excel/提交 走解析器
        └──────────── 五处一致 ────────────┘
```

---

## 5. 边界与风险

1. **不覆盖已有值**（铁律）：`row[key]` 非空（用户值 / BASIC_DATA driver 值）→ 解析器不参与。
2. **数值列非法 content**：`INPUT_NUMBER` content 非数值 → 解析器对数值消费点返回 undefined（与 onChange 校验同源），不污染。
3. **改动面大（核心风险）**：本质是统一 ~8 个前端消费点 + 后端取数链的 INPUT 默认值解析，触及"渲染/计算/导出"一致性层。
   - 缓解：单一解析器 + 既有 `INPUT_NUMBER + default_source` 行为（`含量`）必须**零回归**（保留 `unitConversion.*`、`crossTabInputDefaultSource`、`rowDedup`、`useCardSnapshots` 等现有测试全绿）。
4. ⚠️ **`usePathFormulaCache` BASIC_DATA 排除不可破坏**（§3.2 末行）：那是路径采集防撞键约束，与取值解析正交。改前 grep 确认未触碰。
5. 🟠 **后端取数链（独立验证项）**：核价侧前端无自己的 `snapshotRows`（`src/pages/costing/` 无命中），核价卡片值由后端 `buildCostingCardValues` 生成（记忆 `card-snapshot-project-progress`）；服务端按行读真实值走 `componentData` 两路（`snapshot_rows` + `row_data`，记忆 `quote-card-values-excludes-manual-input-rows`）。**必须端到端确认**：货币的冻结 content 进 `row_data` 后端读到；计价单位的 default_source 后端能实时解析（含 INPUT_TEXT + 三 type + content 兜底）。否则再现"报价带出、核价/Excel 空"的 AP-44 割裂。
6. 🟠 **存量爆炸面 + 时间边界**：让 INPUT 默认值真正参与计算/落库，会改变**已有单重算时的公式输入**。
   - 统计三处口径（AP-39：snapshot 是渲染权威）：`component.fields`、`template.components_snapshot`、`template_component.fields_override`，统计 `field_type IN (INPUT_TEXT,INPUT_NUMBER) AND (content<>'' OR default_source IS NOT NULL)`。
   - 时间边界：仅对 **DRAFT 重建/新建实化，已提交单冻结不动**（与 Phase4 快照冻结一致）。
7. **AP-44 协议级**：命中字段类型联动协议（约 17 检查点），写前 grep 全工程列清单，写完跑双 spec E2E + 五处复测 + admin snapshot 核对。

---

## 6. 范围外（YAGNI）

- **不**改组件管理「内容/配置」存储结构（仍存 `field.content` + `default_source`）。
- **不**改 SQL 视图 / Flyway。
- **不**改 `usePathFormulaCache` 的 BASIC_DATA 路径采集排除（约束，§4）。
- **不**处理 `FIXED_VALUE` / `BASIC_DATA` / `FORMULA` / `LIST_FORMULA` 等其它类型的取值链（仅统一 INPUT 默认值解析）。

---

## 7. 测试方案

**单元测试**：
- `resolveInputDefault` 真值表：三 type（GLOBAL_VARIABLE/BNF_PATH/BASIC_DATA）× (TEXT/NUMBER) × (源命中/源空→content/全空→undefined)；INPUT_NUMBER 数值归一与非法返回；与 onChange 校验同源。
- `snapshotRows` 兜底块：货币(无源)→冻结 RMB；计价单位(有源)→不冻结；已有值不覆盖；BASIC_DATA 已回填不覆盖；公式读得到。
- **回归**：`unitConversion.*` / `crossTabInputDefaultSource` / `rowDedup` / `useCardSnapshots` 现有测试全绿（`含量` 行为不变）。

**E2E（AP-44 强制，双 spec）**：
- `quotation-flow.spec.ts`（SIMPLE，含 `元素` 页签）：
  - **货币（无源，硬断言）**：driver 行 `货币` 输入框 value=`RMB`（非 placeholder）；**不点任何格直接保存→重开仍 RMB**，且值来自持久化 `row_data`（隔离默认源重算验证）；改一格后保存→重开=改后值。
  - **计价单位（有源）**：driver 行带出 `$ys_view.单位` 实时值；源数据存在时显示源值（非 KG），印证 B 的"源优先"。
  - `'加载中' final count = 0`。
- `composite-product-flow.spec.ts`（COMPOSITE）：组合产品路径同上。

**五处一致性复测**：报价填数 / 详情只读 / 核价单 / 核价 Excel / 提交快照 —— 货币=RMB、计价单位=源值 均带出且一致（重点验后端 §5.5）。

**自检三连**：`tsc --noEmit` 0 错 / 改动 `.tsx` Vite `curl` 200 / E2E `1 passed` + `加载中=0`；后端改动则 Quarkus 重启 + endpoint 200/401。

---

## 8. 实现顺序（交给 writing-plans 细化）

1. 隔离 worktree 分支（`superpowers:using-git-worktrees`）。
2. **存量爆炸面量化**（三处口径 SQL）+ 确认 DRAFT-only/已提交冻结边界。
3. 抽 `resolveInputDefault`（独立 util，合并现有所有 type 分支，数值校验与 onChange 同源）+ 单测真值表。
4. 前端全链路由（§3.2 #1~#5），守"不覆盖已有值"铁律；`snapshotRows` 加兜底块（货币冻结 / 计价单位不冻结）。
5. **后端取数链验证/补齐**（§5.5）：`buildCardValues`/`buildCostingCardValues` + 核价 Excel + 提交快照对 INPUT_TEXT + 三 type + content 兜底的解析。
6. 回归现有 INPUT default_source 测试全绿 + 双 spec E2E（货币硬断言 + "未改动也保存" DB 断言 + 计价单位源优先）+ 五处复测 + 自检三连。
7. 记 RECORD.md（含本次"统一解析器"决策 + AP-44 漂移教训）。
```
