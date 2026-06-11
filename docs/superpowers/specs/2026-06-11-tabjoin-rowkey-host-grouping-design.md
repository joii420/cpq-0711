# 页签连表公式 — 行键宿主分组（包含关系）重设计 + 样本卡修复 + 抽屉显示/添加流程

> 日期：2026-06-11 ｜ 状态：**v3（经两轮独立架构评审 + 实地代码核查）** ｜ 前提：**存量数据不管（无迁移、无双语义共存）**
> v1 已废：锚错引擎 `TabJoinPlanEvaluator`。v2 锚对 `cross_tab_ref` 引擎但有 5 处"必改"被乐观地说成"不动"。
> **v3 增量（第二轮评审补正）**：①后端+前端 validator 增「空 match 聚合 → 拒绝」；②补 `(总计)` 双语义表；③`selfRowKeyFields` prop 链；④需求3 后端过滤；⑤需求2 IT 测业务契约；⑥双份 mappability 同步 + 置灰用例重写；⑦存量收紧为"同序同集"。
>
> **v4 增量（第三轮评审补正 + 用户范围决定）**：
> - 🔴 **A. 需求3 锚错文件纠正**：组件抽屉页签来自 **`ComponentTabDefService.componentsToTabDefs`（component-scoped，`alias=c.code` 即"误显示编号"真源）**，**不是** `ExcelViewService.parseTabDefs`（template/Excel-scoped）。需求3 全部改动落 `componentsToTabDefs`。
> - 🔴 **B. 聚合范围 = 全函数（用户定）**：SUM/AVG/MAX/MIN/COUNT 全做，按函数字面语义。**现状序列化只支持 `(总计)`→SUM**，AVG/MAX/MIN/COUNT 需新建"UI 聚合选择 + `expressionToTokens` 解析 + `tokensToDrawerExpression` 回显（round-trip）"三处通道，纳入本期。
> - 🔴 **C. validator 规则收敛**：拒绝"**任何 cross_tab_ref 且 match 为空**"（含 `agg=NONE`——否则 1 源行静默广播错值 / 多源行 ERR 吞 0）。
> - D. `parseActiveRowKeySig` 废"首个明细令牌锁签名"、改**纯按宿主可比**判定（否则多个不同细 source 无法共存）。
> - E. 空行键 source（SUBTOTAL）：只允许整页签总计、明细一律置灰。
> - F. 补"漏匹配缺补 0"边界 + 声明"存量 token 不重跑 `buildMatch`（仅新建/编辑走新配对；除非 `refreshSnapshotsByComponent` 重序列化）"。
> - G. `(总计)` 双语义表补第三条（`[alias.subtotalCol]`→component_subtotal）。

## 现状认知（实地核查结论，纠正 v1）

页签连表公式有**两条独立链路**：

| 链路 | 引擎 | 服务对象 | 求值粒度 | 本方案 |
|---|---|---|---|---|
| **A. `cross_tab_ref` token** | `FormulaCalculator.evalCrossTab`（后端，:206）+ `formulaEngine.ts`（前端，:251，对等镜像）| **NORMAL / SUBTOTAL 组件公式** | **宿主(self)行驱动、逐行** | **改这条** |
| B. `TAB_JOIN_FORMULA` string column | `TabJoinPlanEvaluator.evaluateColumn`（:243）| **EXCEL 列** + 试算 | 单卡片单值 | 不动 |

**关键事实（v1 全错）**：
1. NORMAL 组件公式走 `cross_tab_ref`，**不是** `TabJoinPlanEvaluator`。后者仅 `ExcelViewService` 调用，只服务 EXCEL 列。
2. **"宿主行驱动 + 逐行 + 按行键对齐 + 聚合" 早已实现**（2026-06-05），不是新能力。`TabJoinFormulaDrawer.save()` 按 `componentType` 分流：NORMAL/SUBTOTAL → `expressionToTokens()` 落 `cross_tab_ref` token；EXCEL → string column。
3. 前后端两个引擎**对等镜像**，都消费 `token.match`（`{a: source字段, b: host字段}` 数组）+ `token.agg`。两者由 `cross-tab-cases.json` 13 用例逐例锁一致（2026-06-05）。
4. 宿主行键已传入抽屉（`TabJoinFormulaDrawer` 的 `selfRowKeyFields` prop，`ComponentManagement.tsx:1119`）。

**现状对齐语义**（`buildMatch`，`formulaSerialize.ts:39`）：`match[i] = {a: source.rowKeyFields[i], b: self.rowKeyFields[i]}` —— **按 rowKeyFields 下标位置 zip**（截断到较短长度）。求值时 host 每行用 `match` 在 source 行里找命中：`agg=NONE` 命中 0→0、命中 1→取值、命中 >1→`ERR`（FormulaCalculator.java:226 / formulaEngine.ts:279）；`agg=SUM/AVG/MAX/MIN/COUNT` 对命中行聚合。

## 与目标的差距（=本方案真实改动面）

现状已是"宿主行驱动 + 聚合"，离"宿主行键分组 + 包含关系"只差三点：

1. **`buildMatch` 位置配对 → 公共字段名配对**（核心）：改成按 source.rowKeyFields ∩ self.rowKeyFields **的公共字段名**生成 `{a:f, b:f}` 对。这样 host`[子件]` × source`[工序,子件]` 会正确匹配 `子件`（位置 zip 会错配 `工序↔子件`）。**`token.match` 一改，前后端两个引擎自动一致**（都只读 match），无需改引擎逻辑。
2. **细 source 必须显式聚合**：source 比 host 细（source 键 ⊋ host 键）时，host 一行命中 source 多行，`agg=NONE`→`ERR`。所以 UI 对"引用细 source 字段"**强制选聚合函数**（默认 SUM=现有"(总计)"语义、可选 AVG/MAX/MIN/COUNT——引擎已全支持），裸明细置灰。
3. **置灰基准换成宿主行键 + 集合包含**：`TabFieldMatrix` 从"与第一个明细令牌同行键签名"改为"**与宿主 `selfRowKeyFields` 可比（⊆ 或 ⊇，按列集合、顺序无关）**"。

---

## 需求 1 — 行键宿主分组（包含关系）重设计

### 模型（落到 `cross_tab_ref`）
- **结果粒度 = 宿主组件行键**（每个 host 行算一个值，现状本就如此）。
- 每个被引用 source 页签对齐到宿主行键：
  | source vs host | 处理 | 落地 |
  |---|---|---|
  | 宿主自身字段 | 取本行值 | `field` token（不变）|
  | **粗 / 同级**（source 键 ⊆ host 键）| 广播 / 1:1（host 行命中 ≤1 source 行）| `cross_tab_ref` agg=NONE（裸明细可用）|
  | **细**（source 键 ⊋ host 键）| 按宿主键**聚合**（host 行命中多 source 行）| `FN([alias.field])`，FN∈SUM/AVG/MAX/MIN/COUNT，**强制显式选函数**（裸明细置灰）|
  | **不可比** | 置灰（仅 source 整页签总计可用）| —— |
- **不笛卡尔**：多个细 source 各自独立按宿主键聚合，互不相乘。

### ⚠️ 聚合 / 总计 语义表（命门，UI 改造必须区分三条路径）
"聚合"与"总计"序列化时走**三条不同 token、不同聚合范围**，不可混淆：

| 抽屉写法 | 序列化 token | 聚合范围 | 用途 |
|---|---|---|---|
| `[加工.工时]`（裸明细）| `cross_tab_ref` agg=NONE | 单行匹配（>1→ERR）| 粗/同级 source 1:1/广播 |
| **`FN([加工.工时])`** FN∈{SUM,AVG,MAX,MIN,COUNT}（**v4 新增函数语法**）| `cross_tab_ref` agg=FN（match 按公共行键）| **按宿主行分组**聚合 | 细 source 字段聚合贴回宿主行（模型 A）。兼容旧 `[加工.工时(总计)]`=SUM |
| `[加工(总计)]`（裸页签+总计）| `component_subtotal`（标量）| **整页签全表**小计 | 跨页签拿一个全局小计标量 |
| `[加工.小计列]`（field∈subtotalCols）| `component_subtotal`（标量，**v4 补列**）| 整页签该列小计 | 组件小计列引用 |

**v4 序列化改造**（B 范围）：新增函数语法 `FN([alias.field])`：
- `expressionToTokens` 解析 `SUM/AVG/MAX/MIN/COUNT(...)` → `cross_tab_ref agg=FN`（保留旧 `(总计)`→SUM 兼容）；
- `tokensToDrawerExpression` 反向回显 `agg=FN` → `FN([alias.field])`（现状只会回显成 `(总计)`，会丢 AVG/MAX 语义，必须改）；
- lexer 识别函数名 token（现遇字母即抛错）。

**UI 规则**：不可比页签**只留 `[alias(总计)]`（整页签小计）入口**，**禁止**给它宿主分组入口——不可比 → 无公共行键 → match 为空 → 被 validator 拒。

### 求值示例（宿主 投料`[子件]` 金额=10；细 source 加工`[子件,工序]` 工时 3、5）
- `[投料.金额] * SUM([加工.工时])` → 加工按子件 SUM=8 → 螺丝行 `10*8=80`
- `[投料.金额] / AVG([加工.工时])` → `10 / 4 = 2.5`
- `MAX([加工.工时])` → 5；`COUNT([加工.工时])` → 2
- 裸 `[加工.工时]`（细 source、未聚合）→ UI 置灰，不允许（否则运行时多命中 ERR）
- **缺补 0 边界（v4 补）**：宿主某行（如 子件=垫片）在 source 无匹配行 → hits 为空 → 聚合/广播均返 **0**（与 RECORD 2026-06-10「全外连·缺补 0」一致，非报错）

### 改动清单
**前端**
- `formulaSerialize.ts` `buildMatch`：位置 zip → **公共字段名交集配对**（顺序无关）。无公共字段 → 返回 `[]`（交给 validator 拒绝）。
- **🔴 聚合函数序列化（v4，B 范围）**：
  - `expressionToTokens`：新增 `FN([alias.field])`（FN∈SUM/AVG/MAX/MIN/COUNT）解析 → `cross_tab_ref agg=FN`；保留旧 `[alias.field(总计)]`→SUM 兼容；lexer 识别函数名（现遇字母抛错）。
  - `tokensToDrawerExpression`：`agg=FN` 反向回显 `FN([alias.field])`（现状把任意 agg 回显成 `(总计)`，会丢 AVG/MAX 语义 → round-trip 破坏，必须改）。
- `TabFieldMatrix.tsx`：
  - 置灰基准改为宿主 `selfRowKeyFields`；按集合 ⊆/⊇ 判可比；细 source 裸明细置灰、仅留聚合入口（chip 上加 SUM/AVG/MAX/MIN/COUNT 选择）；**空行键 source（SUBTOTAL，rowKeyFields=[]）→ 明细一律置灰、只留 `[alias(总计)]` 整页签小计**（v4-E）。
  - **🔴 `parseActiveRowKeySig` 重构（v4-D）**：废除"取表达式首个明细令牌锁签名"机制，改为**纯按宿主可比**——每个 tab 独立判 `comparable(def.rowKeyFields, selfRowKeyFields)`，与表达式已有内容无关（否则首令牌锁死、多个不同细 source 无法共存）。
  - **prop 链（v3）**：`ComponentManagement`(持 `rowKeyFields`) → `TabJoinFormulaDrawer`(已持 `selfRowKeyFields`，但**未转发**) → `TabFieldMatrix`(新增 prop) → `parseActiveRowKeySig`(新增入参)。
- `formulaEngine.ts`：**求值逻辑不改**（消费 `token.match`+`agg`，已证实按宿主行 filter→聚合）；随夹具回归。
- `formulaSerialize.ts` `checkMappable`（前端 gate）：与后端 validator **同步**改新规则（见下）。

**后端**
- `FormulaCalculator.evalCrossTab`：**求值逻辑不改**；建议加防御性断言"空 match 不得进聚合/广播"（validator 漏网也返 ERR 而非全表）。
- **🔴 mappability 新规则（前端 `checkMappable` + 后端 `TokenMappabilityValidator` 两份同改、同夹具锁）**：
  1. 作废旧规则"≥2 个 agg=NONE 即拒"。
  2. **拒绝 `agg=NONE` 且 source 比 host 细**（运行时多命中 ERR）。
  3. **🔴 拒绝任何 cross_tab_ref 且 `match` 为空（含 agg=NONE）**（v4-C 收敛）：空 match → 全源行命中 → 聚合退化全表 / NONE 静默广播或吞 0。`[alias(总计)]` 走 component_subtotal、本无 match，不受影响。
  4. 同级/粗 source 的多个 NONE 允许（各自命中 ≤1、互不相乘）。
- `comparable(a,b)` / `isSubset`（视行键为 Set，顺序无关）工具：前后端各一份，共享用例锁一致。

**EXCEL（模型 B）不动**：`TabJoinPlanEvaluator` + `validateTabJoinConfig`（"裸明细须同一行键类"）保持，EXCEL 列单值语义不回归。

### 向后兼容（存量不管，但实际低风险）
- 旧 `cross_tab_ref` token 的 `match[]` 是位置配对生成的。旧模型只允许"行键完全相同"（其它置灰），**同序同集**时位置配对 == 公共字段名配对 → 旧 token 仍正确。
- **收紧（v3）**：仅对**同序同集**等价；**乱序同集**（如两组件行键 `[A,B]` vs `[B,A]`）的存量 token，旧位置配对生成 `{A,B}{B,A}`（错配）、新公共名配对生成 `{A,A}{B,B}`（正确）→ 新旧结果可能不同。此类**极少且本就语义可疑**，按"存量不管"**不回溯**。
- 细/粗组合的旧 token 本就无法被创建（曾被置灰），不存在。**无需迁移脚本。**
- **存量渲染不漂移（v4-F）**：引擎只读落库的 `token.match`、**不重跑 `buildMatch`**，故已发布 snapshot 的存量公式求值期不变；**仅新建/编辑公式才走新配对**。唯一例外：`refreshSnapshotsByComponent` 重序列化会把乱序同集存量从旧错配翻成新配对（值变）——属"存量不管"范畴，不专门处理。

---

## 需求 2 — 修复「样本卡片加载失败」

**根因（实地堆栈确认，非推断）**：`ComponentSampleCardService.java:85/88` 用 Panache 方法引用 `QuotationLineItem::findById` / `Quotation::findById`。后端日志（2026-06-11 15:43 仍在抛）：
```
IllegalStateException: This method is normally automatically overridden in subclasses:
did you forget to annotate your entity with @Entity?
  at ComponentSampleCardService.sampleCardsForComponent(...:85)
```
方法引用编译成 `invokedynamic`，绑定到未增强的 `PanacheEntityBase` 占位方法 → 抛此异常；lambda 体内 `Entity.findById(id)` 是正常增强调用点（同仓 `ExcelViewService.sampleCardsOfTemplate:897` 即用 lambda 正常）。

> 注：架构评审 S3 质疑此诊断（称方法引用≡静态调用）。以**真实堆栈为证驳回**该质疑；但采纳其"先写能复现 500 的 IT 再改"的做法。

**修复**：`computeIfAbsent(liId, id -> QuotationLineItem.findById(id))` / `computeIfAbsent(li.quotationId, id -> Quotation.findById(id))`。
**回归 IT（v3 松绑）**：造 quotation+line_item+componentData → 调 `sampleCardsForComponent`，断言**业务契约**——返回正确样本卡列表、无引用时返空、**不抛 500**。
> ⚠️ **不强求"先复现红"**：该 `@Entity` 异常依赖字节码增强时序（dev-mode 热部署易触发，干净 `@QuarkusTest` 构建下方法引用可能反而正常 → IT 复现不出红）。若复现不出红，**直接改 lambda（与同仓 `ExcelViewService.java:897` 一致、零风险）+ IT 验行为绿**即可，不把交付卡在"必须先红"。

**独立先交付**（急、隔离）。

---

## 需求 3 — 配置抽屉：显示组件名[编号] + 过滤文本字段

> 🔴 **改对文件（v4 纠正）**：组件抽屉页签来自 **`ComponentTabDefService.componentsToTabDefs`**（`GET /components/{id}/tab-defs`，ComponentTabJoinResource），**不是** `ExcelViewService.parseTabDefs`（那是 Excel 模板视图）。需求 3 全部落 `componentsToTabDefs`。该 service **已有** `alias=c.code`（:65，即"误显示编号"真源）+ `componentName=c.name`（:89）。

- **左栏标签 → `组件名称[组件编号]`**（`componentName` 加粗为主、`alias`(=code) `COMP-00xx` 小字）。
  - 后端 `componentsToTabDefs` 已下发 `componentName`+`alias`，**只需前端 `TabFieldMatrix` 改显示**（`componentName` 主、`alias` 括号辅）。若 `componentCode` 与 `alias` 同义即直接用 `alias`。
- **隐藏"文本输入"类型字段**（明细 chip 区）：纯文本无法数值计算。
  - 在 **`componentsToTabDefs` 按 `field_type` 过滤掉文本型** `detailFields`（该方法 :74 已读 `fm.get("name")/("is_subtotal")`，同样能读 `fm.get("field_type")`），**保持 `detailFields:String[]` 协议不变**（仅元素变少）→ 不波及消费方。
  - **行键字段仍显示在左栏"行键"徽标**：走**独立来源** `Component.rowKeyFields`（:69，与 detailFields 不共享）→ 过滤明细不影响徽标，天然成立。INPUT_TEXT 可作行键（RECORD 2026-06-10）。
  - **不触发 AP-44**：仅"按已有类型做展示过滤"，不改 `field_type` 枚举/渲染分支/cache key。
  - ⚠️ 若模板级 `parseTabDefs` 也被复用（Excel 视图），按是否需要同款展示决定是否同步（本需求只要求组件抽屉）。

---

## 需求 4 — 添加公式与配置分离

- 「添加公式」→ `FormulaListPanel` **行内新增一行**，名称可编辑（默认「公式N」、聚焦），expression 空。
- 每行加「**配置**」按钮 → 才弹 `TabJoinFormulaDrawer`。
- 名称**随时点单元格改名**。
- 空表达式公式先存本地 `formulas` 状态，随组件「保存」入库；抽屉只管表达式。
- 改 `FormulaListPanel`（可编辑名称列 + 配置按钮列）+ `ComponentManagement`（`onAdd` / `openFormulaForComponent`）。

---

## 实施顺序与验证

1. **需求 2**：独立 worktree，先写复现 IT（红）→ lambda 修 → 绿，先交付。
2. **需求 1 + 3 + 4**：一个 worktree。
3. **测试（重点：三视图一致 + 双引擎对等；🔴 命门项先写失败测试 TDD）**
   - **🔴 TDD 命门 1**：前后端 mappability 各加「**任何 cross_tab_ref 且 match 为空 → 拒绝**」用例（含 NONE，先红）；`evalCrossTab`+`formulaEngine.ts` 加防御性断言「空 match 不得进聚合/广播」。
   - **🔴 TDD 命门 2**：聚合/总计三语义用例——`SUM([a.f])`=宿主分组、`[a(总计)]`=整页签小计、`[a.subtotalCol]`=组件小计列，断言聚合范围不同。
   - **🔴 聚合函数 round-trip 用例（v4-B）**：`AVG/MAX/MIN/COUNT([a.f])` → `expressionToTokens` → `agg=FN` → `tokensToDrawerExpression` 还原回同串（防 AVG 被回显成 SUM 丢语义）；lexer 识别函数名。
   - `cross-tab-cases.json` **新增宿主分组用例**：粗 host+细 source 聚合(SUM/AVG)、细 host+粗 source 广播、同级 1:1、不可比置灰、公共字段名**乱序对齐**。
   - 后端 `FormulaCalculator` 单测 + 前端 `formulaEngine.ts` 对等单测 + **`CardSnapshotCrossTabTest`**（三视图写回）**同跑同夹具**（防三视图漂移，AP-50/AP-41）。
   - `buildMatch` 公共字段名配对单测（顺序无关、长度不等、**无公共字段→返回 []**）。
   - 前后端 mappability **两份同夹具锁**（细 source NONE 拒、空 match 聚合拒、粗/同级多 NONE 放行）。
   - **`tabFieldMatrix.test.ts` 置灰用例按"集合包含"重写**（非"随夹具回归"——旧"同行键类"预期作废）。
   - `tsc`+esbuild+后端 `touch` 重启 `/q/health`；真机试算复测（样本卡修好后）。
   - 涉求值语义但不在 E2E 强制清单 → 单测+IT+真机+夹具覆盖。

## 验收标准
- 抽屉中：包含关系页签可引用、细 source 强制聚合、不可比置灰；`组件名称[编号]` 显示；文本字段从明细隐藏（行键徽标保留）。
- NORMAL 公式逐行按宿主键分组、细 source 聚合、不笛卡尔；前后端两引擎 + 三视图结果一致（夹具锁定）。
- EXCEL/小计单值语义不回归。
- 样本卡端点不再 500（复现 IT 红→绿）。
- 添加公式先命名、可改名、点配置才弹抽屉。

## 风险与遗留
- 需求 1 触及三大核心模块基线（求值/渲染），靠"双引擎同夹具 + 三视图回归"兜底；无迁移（存量不管）。
- 默认 SUM 对比率/单价类指标可能不合语义 → 由"**强制用户显式选聚合函数**"规避（不给隐式默认），呼应 AP-22。
