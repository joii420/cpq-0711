# 页签连表公式 — 嵌套聚合设计方案（v3 · 卡片优先 · 通用配置驱动）

- 日期：2026-06-16（v3：纳入两轮独立评审，收敛为 `外层聚合(内层 KSUM(子页签)) + 内联 SUMIF`）
- 状态：设计待评审（brainstorming 产出，未进入实现计划）
- 作者：Claude（与用户协同 brainstorming）

## 范围决策（一句话）
**本期只在产品卡片（模型 A）落地"≤2 层、内层聚合子页签、SUMIF 全局广播"的嵌套聚合**；语法**沿用现役 `KSUM` 族**（不发明新语法）；**砍掉"组内聚合宿主自身列"**（无数据底座，见 §0.3）；Excel 视图列含嵌套**显式报错引导用卡片**（Excel 引擎统一留作后续独立 spec）。

> ⚠️ **通用性铁律**：本方案全程**配置驱动、页签无关**。下文 `宿主页签 / 子页签 / 页签A / 费率页签` 均为**占位角色**，不是具体页签名。实现中**禁止**对任何具体页签名 / 字段名写死分支逻辑——一切由组件配置（`tabKey` / `rowKeyFields` / `field_type` / token 结构）驱动。

## 0. 背景与现状（实测）

页签连表公式（cross_tab / TabJoin）已具备"宿主行键分组（KSUM/`projectToHostKey`）+ 条件聚合（SUMIF 族 `predicate`）+ 多源链式（`sources[]`）"能力。本方案在其上叠加**有限层（≤2）嵌套聚合**。

### 0.1 关键现状事实（均经代码核验，决定边界）

| 事实 | 证据 |
|---|---|
| 后端求值器是**递归**骨架（`targetExpr` 子树回调递归求值），理论深度不限 | `cpq-backend/.../quotation/service/FormulaCalculator.java` `evalCrossTab:267`→`targetRowValue:353`→`evaluateExpression:76`（:447 递归回调）|
| 现役**嵌套唯一可写形态 = `SUM(KSUM(...))`**（K 前缀 = 组内聚合子页签）；plain `SUM(SUM(...))` 被直接抛错 | `cpq-frontend/src/pages/component/formulaSerialize.ts` `:610-612`（FN 内 plain 聚合抛错）、`:508-510`（K 套 K 抛错）、`:334 INNER_FNS_SET` |
| KSUM **禁止聚合宿主自身列**（强制宿主列放外层）| `formulaSerialize.ts:521-524, 538-542` |
| 外层 FN body 循环**无 `sumif_call` 分支**，内联 SUMIF 被 `default:break` 静默丢弃 | `formulaSerialize.ts:597-645`（:643 default）|
| `checkMappable` 只扫顶层一层，不递归 `targetExpr` | `formulaSerialize.ts:1180-1182` |
| `tokensToDrawerExpression` 的 `renderTargetExprParts` 内嵌 SUMIF **回显残缺**（不渲染 predicate）| `formulaSerialize.ts:1010-1022` |
| 求值器 `evaluateExpression` 顶层 `catch(Exception)→ZERO4`：一切异常**静默塌 0** | `FormulaCalculator.java:84-89`（:86 `setScale(4,HALF_UP)`）|
| 求值**逐宿主行独立**（`for baseRow`），后端**无嵌套深度 guard** | `FormulaCalculator.java:601` |

### 0.2 评审结论对范围的影响（为什么"卡片优先"）
- "Excel 复用统一引擎"有两个致命前置：**后端无 expression→token 解析器**（仅前端 `expressionToTokens`）、**无宿主全局归约模式**（求值天然以宿主行过滤为前提）。二者**只阻碍 Excel 路径**，不阻碍卡片（卡片本走 token + 递归求值器）。
- 故本期把嵌套限定在卡片，主改动落在**序列化器递归化 + 内联 SUMIF + 诊断**，规避两前置。

### 0.3 为什么砍掉"组内聚合宿主自身列"（关键决策依据）
三重代码事实证明它**无数据底座**：
1. 宿主组件的行在"算它自己"那一轮**不在 `crossTabRows`**（拓扑序逐组件计算，`CardSnapshotService.java:765-824`，:788 才回填）→ 内层聚合宿主列拿空集 → 静默 0；
2. 求值**逐宿主行独立**（`:601`）+ 宿主行键经唯一化已**一键一行**（见记忆 `cpq-rowkey-uniqueness-disambiguation`）→ "一键多行的宿主行集"不存在，内层对宿主列求和=恒等且无对象；
3. **推论**：卡片单键下，"内层聚合宿主自身列"要么取不到值、要么恒等。真正非恒等、有数据底座的嵌套**只有**"内层聚合比宿主更细的子页签"= 现役 `KSUM`。
⇒ 砍掉它不损失业务表达力：宿主自身列的逐行运算放外层即可（`SUM([宿主.列] * KSUM([子页签.列]))` 这类），无需把宿主列塞进内层聚合。

## 1. 统一语义模型（规范核心 · 唯一权威定义）

### 1.1 三件套：层级 / 取数 / 计算
嵌套聚合 = **自底向上递归 reduce**（"循环计算"：最内层先在其行集上聚合 → 标量回代上层 → 上层再聚合）。每个聚合节点由两件事确定，其余是纯算术：

1. **层级 = 作用域（scope）= 该聚合循环的行集**，只有两档：
   - **组内（within-key）**：当前宿主行键分区内、来自**子页签**的行（现役 `KSUM` 族：`KSUM/KAVG/KMAX/KMIN/KCOUNT`）；
   - **跨组 / 投影（across-key）**：外层 `SUM/AVG/MAX/MIN/COUNT`，跨所有宿主行键归约/投影。
   - ⇒ **有意义嵌套深度 ≤2 层**（唯一分组键=宿主行键，无第三行集）。**第 3 层及以上的聚合嵌套 → 序列化期 + 求值期双重显式报错**（聚合函数嵌套层数，纯算术括号不计层）。
2. **取数来源 = 是否参与宿主行键对齐 + 字段**：
   - **参与对齐 · 子页签列**（粒度 = 一键多条子行）→ 被组内 `KSUM` 聚合；
   - **不参与对齐 · SUMIF 族**（`SUMIF/COUNTIF/AVGIF/MINIF/MAXIF`）→ **永远全局**：只按 `predicate` 过滤、与行键无关、广播到每组（来源对不上键 → 退化全局单值，已确认）；
   - **宿主自身列**：只能出现在**外层**（逐宿主行广播，`b_field`），**不可进内层聚合**（§0.3）。
3. **计算** = `+ - * /` 与函数组合，对回代后的标量求值。

### 1.2 投影规则（消除"谁是最外层"歧义）
投影 = 求值器外层循环，**不给任何 token 特殊地位**：
- **有宿主（卡片 / 模型 A）** → 对每个宿主行键 g，把**整条表达式**在 g 的作用域求值一遍 → 输出向量 `{ g → 值 }`，按行键拼回宿主各行。
- **无宿主（Excel）** → 整条表达式全局求值一遍 → 单值；**本期：Excel 列表达式含嵌套聚合则配置/求值期显式报错引导用卡片**。
- 组内聚合在当前组的子页签行上 reduce；裸明细引用沿用"默认按组内行求和"；SUMIF 永远全局广播。

### 1.3 空值 / 异常语义（逐层一致，fail-soft + 必出诊断）
- `SUM/COUNT`（含 K 系）空集 → `0`；`AVG/MAX/MIN`（含 K 系）空集 → `null` → 整外层项塌 `0`（沿用现役 I-1/I-2）；
- **除零** → 该项塌 `0` + **试算诊断**（不抛异常、不出 NaN/Infinity）；
- **NULL 叶子** → `0`（与"全外连缺补 0"一致）；
- **文本叶子进数值聚合 / 算术** → **在序列化 / 配置期判定并报错**（字段类型此时已知），**运行期不依赖、不改动现有全局 catch**（见 §3.1 决策）；
- 多命中 / 类型错误沿用 `ERR` 哨兵 → 整外层塌 0 + 诊断。

### 1.4 通用样例走查（角色占位，非具体页签）
设宿主 = `宿主页签H`，其下更细子页签 = `子页签C`，另有全局 `费率页签F`。目标列：
```
SUM( KSUM([子页签C.金额A] + [子页签C.金额B]) * SUMIF([费率页签F.要素]='某费率项', [费率页签F.比例]) )
```
- 内层 `KSUM([子页签C.金额A]+[子页签C.金额B])`：组内聚合（当前宿主行键分区内、子页签C 的多条子行求和）→ 标量 `S_g`；
- `SUMIF([费率页签F.…])`：SUMIF 族 → 全局单值 `R`（费率页签F 对不上宿主键 → 全局），广播到每组；
- 内层式 = `S_g * R`；
- 外层 `SUM(...)` = 投影：卡片 → 向量 `{ g → S_g * R }`；Excel → 含嵌套 → 本期报错引导用卡片。

> 若用户原始诉求里"内层聚合的对象"其实是宿主自身列且其下无更细子页签，则该公式在卡片单键下无需嵌套，应写成单层 `([宿主.列A]+[宿主.列B]) * SUMIF(...)` 逐宿主行求值；"跨全部宿主行求和"语义属 Excel，随 Excel 独立 spec。

## 2. 范围

### 2.1 本期目标（In Scope）
1. **产品卡片（模型 A）** 支持 **≤2 层嵌套聚合**：外层 `SUM/AVG/MIN/MAX/COUNT` + 内层 `KSUM` 族（聚合子页签）+ 同层多 SUMIF / 算术混合。
2. **内联 SUMIF**：SUMIF 族可写进外层 FN body（拆 `formulaSerialize.ts:597-645` 静默丢弃）。
3. 作者体验（方案 C）：文本表达式为主体，SUMIF 构造器产物**内联插入光标处**（可嵌进函数参数），与手敲产出同一套语法。
4. §1.3 空值/异常语义统一落地（文本叶子配置期拦截）。
5. **不破坏现有公式**（硬约束）：存量公式**计算结果**（4 位小数舍入相等）+ **校验 pass/fail** 双不变（§5.1）。
6. **通用配置驱动，零页签名硬编码**（铁律）。

### 2.2 非目标（Out of Scope）
- **组内聚合宿主自身列**（§0.3，无数据底座，永不做）。
- **Excel 视图列嵌套聚合统一 / 退役 `TabJoinPlanEvaluator`**（需后端解析器 + 全局归约）→ 独立 spec；本期 Excel 含嵌套报错。
- **第 3 层及以上嵌套**（无第三作用域）→ 校验期报错。
- 不发明 plain `SUM(SUM())` 语法（沿用 `KSUM`）。
- 不改 `field_type` 枚举。

## 3. 架构设计

### 3.1 求值（后端，模型 A）
- 复用 `FormulaCalculator` 递归 cross_tab 通路。需补/验证：
  1. **内联 SUMIF 进嵌套**：确保 `targetExpr` 子树内的 `cross_tab_ref(predicate)` token 在 sub-RowContext 下经 `predicateEval` 正确**全局**求值（补一个最小后端单测验证 `:299 predicateEval.test` 链对内嵌 SUMIF 齐备）。
  2. **≤2 层求值期校验**：在 token 树遍历期数"聚合函数嵌套深度"（纯算术括号不计层），超 2 层返回明确错误；常量与前端**同源**（§4.3）。
  3. **§1.3 异常语义**：除零塌 0+诊断、空集 I-1/I-2 沿用；**文本叶子不在运行期处理**（见决策）。
- **文本叶子决策（关键，规避动基线热路径）**：`evaluateExpression:84-89` 的全局 `catch→ZERO4` 是所有公式共用 fail-soft 兜底，**本期不动它**；文本字段进数值算术一律在**序列化 / 配置期**判定报错（字段类型已知）。运行期保持现状 → 退化纪律零破坏。
- **退化纪律（硬约束）**：不含本期新形态（内联 SUMIF / 子页签嵌套）的 token，代码路径与现状完全一致（N=1 退化路径零变化）。

### 3.2 序列化（前端，本期最大改动面）
- `expressionToTokens` 改**递归下降解析**支持 **≤2 层**：
  - 外层 FN body 内允许 **`KSUM` 族**（已有）+ **`sumif_call` 分支**（新增，拆 `:597-645` 静默丢弃）；
  - **第 3 层聚合嵌套 → 抛明确错误**；纯算术括号 `SUM(a*(b+c))` 不计层（走 paren 分支，不增聚合深度）。
- `tokensToDrawerExpression` / `renderTargetExprParts`：补**内嵌 SUMIF `predicate` 回显**（拆 `:1010-1022` 残缺），保证 round-trip。
- `checkMappable`：**遍历整棵 token 树**（拆 `:1180` 单层）；但**校验收紧只对本期新增嵌套形态生效，旧顶层结构维持原判定**（防 P1-2 存量"可存→不可存"，见 §5.1）。
- `parseFormulaSegments`（配色）/ `formulaBracketCheck`：括号深度栈 + SUMIF 深度（`:1100 sumifOpenDepths`）在嵌套层正确维护。
- **同名列消歧**：序列化期对"既是宿主列又是子页签列"的歧义字段**强制带 source**，运行期靠 `targetRowValue` 的 D3 `bySource` 桶精确取值（`FormulaCalculator.java:419`）。
- **round-trip 不变式**：任意合法 ≤2 层 expr → token → expr 幂等（专项测试，§5.2）。

### 3.3 作者 UI（`TabJoinFormulaDrawer` + `FormulaRichInput`）
- SUMIF 构造器：从"末尾 side-token"改为**生成内联文本 `SUMIF(条件, 取值)` → `insertAtCursor` 插入光标**（NORMAL/SUBTOTAL 统一）。
- 旧 side-token 公式重开：`tokensToDrawerExpression` 把带 `predicate` 的 token 还原成内联 SUMIF 文本展示（兼容回显，不丢 predicate）。
- 富文本提示：明确"外层聚合 + 内层 `KSUM`（子页签）+ SUMIF 全局 + ≤2 层"；`KSUM` 在 UI 可给友好标签（如"组内聚合"），由构造器生成、用户不必手记函数名。

### 3.4 试算
- `dry-run-token`（卡片逐行向量）走统一求值器，errors 暴露 §1.3 诊断。
- Excel `dry-run`：表达式含嵌套聚合 → 明确错误"Excel 列暂不支持嵌套聚合，请用卡片渲染"。

### 3.5 模块边界（路径以仓库实际为准）
- 求值核心：`cpq-backend/src/main/java/com/cpq/quotation/service/FormulaCalculator.java`（基线锁定，仅"加内联 SUMIF 求值验证 + 加深度校验 + 加诊断"，不动热路径 / 不动全局 catch）。
- 序列化：`cpq-frontend/src/pages/component/formulaSerialize.ts`（递归化，最大改动面）。
- 作者 UI：`cpq-frontend/src/pages/template/TabJoinFormulaDrawer.tsx` / `FormulaRichInput` / SUMIF 构造器。
- 试算端点：`cpq-backend/.../component/resource/ComponentTabJoinResource.java`。

## 4. 数据 / 边界 / 防御

### 4.1 持久化
- **不改 token schema**：嵌套靠现有 `targetExpr` 子树；内联 SUMIF 复用现有 `cross_tab_ref(predicate)` token。
- **不迁移存量数据**：旧 token 原样读取。

### 4.2 协议联动（AP-44 类）
- "内联 SUMIF 进 body + 序列化递归化"是跨前后端协议变换，须对照 `docs/配置方法论-合并版.md §11` 矩阵逐点核验（enrich / normalize / cache key / 渲染分支 / 序列化双向 / 后端校验 / 路径采集 / refreshSnapshots / useDriverExpansions / QuotationStep2），写码前 grep 全工程 + `codegraph_impact`。
- **零页签名硬编码**复核：所有新增分支按 token 结构 / 字段类型判定，禁止 `if (tabName == "…")`。

### 4.3 深度上限同源
- "≤2 层"常量前后端**同源**（共享常量；前端序列化校验 + 后端 token 树遍历校验各一道）。后端校验落点在 token 树遍历期（`appendToken` 拼串后看不出层级，须在树结构上数聚合嵌套深度）。

## 5. 测试与验收（基线锁定模块，从严）

### 5.1 双维度差异回归（兼容硬指标）
- **结果回归**：盘点**存量卡片 cross_tab 公式样本集**（来源：现有模板组件公式，清单写进实现计划），改造前后**数值 4 位小数舍入相等**（后端本就 `setScale(4)`）。
- **校验回归**：改造后 `checkMappable` 对存量公式的 **pass/fail 不得变化**（无 pass→fail）；收紧校验前必须有此回归背书。

### 5.2 新能力单测
- `SUM(KSUM())` 两层；**3 层 → 报错**；纯算术括号 `SUM(a*(b+c))` **不报错**（不计层）；
- 内联 SUMIF 在 FN body 内（全局广播 + 对不上键退化）；
- `KAVG/KMIN/KMAX/KCOUNT` 各作内层、`AVG/MIN/MAX/COUNT` 各作外层；
- 空集（SUM→0 / AVG→塌0）、除零→塌0+诊断、NULL→0；
- 文本叶子 → **序列化期报错**（运行期不塌默背）；
- 同名列带 source 消歧取值正确；
- 有宿主出向量；**round-trip 幂等**（expr↔token，≤2 层 + 内嵌 SUMIF 回显全覆盖）；
- 内嵌 SUMIF 后端最小求值单测（predicate 在 sub-RowContext 生效）。

### 5.3 强制 E2E（协议级 + AP-44）
- `quotation-flow.spec.ts`（SIMPLE）+ `composite-product-flow.spec.ts`（COMPOSITE）：全 `passed`，`'加载中' final count = 0`，8 Tab `'加载中'=0`；附 qf-19 + qf-21~28 截图。

### 5.4 自检
- 前端：`npx tsc --noEmit` 0 错误；改动 `.tsx`/`.ts` 逐个 Vite 200。
- 后端：`FormulaCalculator` 改动后强制 Quarkus 重启 + `/q/health` 200；纯逻辑单测在 **worktree 的 `cpq-backend`** 内亲跑 `mvnw` 全绿。

### 5.5 文档
- 同步 `docs/PRD-v3.md`（公式能力章 + 演进史）、`docs/RECORD.md`、`docs/配置方法论-合并版.md`（§11 + 附录）、`docs/反模式.md`（若新增协议点）。

## 6. 实施阶段（供 writing-plans 细化）
1. **P0 基线**：固化 §1 语义；盘点存量卡片公式样本集；搭**双维度**（结果 + 校验）差异回归框架。
2. **P1 求值器**：内嵌 SUMIF 全局求值验证 + ≤2 层 token 树校验 + 除零诊断；退化纪律回归；**不动全局 catch**。
3. **P2 序列化递归化**：`expressionToTokens` / `tokensToDrawerExpression`(含内嵌 SUMIF 回显) / `checkMappable`(整树但旧形态不收紧) / `parseFormulaSegments` 支持 ≤2 层 + 内联 SUMIF；同名列带 source；round-trip 测试；文本叶子序列化期拦截。
4. **P3 作者 UI**：SUMIF 构造器改内联插入；富文本/括号/配色适配；KSUM 友好标签。
5. **P4 回归验收**：双维度差异回归 + 新能力单测 + 双 E2E + 文档。

## 7. 风险与缓解
- **基线模块改动**：仅"加内联 SUMIF 求值 + 加深度校验 + 加诊断"，不动热路径 / 不动全局 catch；结果+校验双回归 + 退化纪律。
- **序列化递归化（P2 最大面）**：round-trip 不变式 + 前端单测；先解析、再回显、最后配色，分步验证。
- **AP-44 协议漏点 + 硬编码复核**：`codegraph_impact` + 矩阵逐点 + 双 E2E + grep 禁页签名分支。
- **内嵌 SUMIF predicate 求值**：先写后端最小单测验证链路，不纸面断言。

## 8. 实现期待确认项（开放问题）
1. 存量卡片 cross_tab 公式样本集清单（P0 盘点产出，含结果 + 校验基线）。
2. `expressionToTokens` 在"外层聚合 + 内层 KSUM 子页签 + 内联 SUMIF + 算术混合"下的精确折叠规则（P2 细化）。
3. 前后端"≤2 层"同源常量的承载位置（共享常量类 / 配置）。
4. 文本叶子报错的精确判定点（解析期可判的全部前移到配置期）。
5. 内嵌 SUMIF 的 predicate 在 sub-RowContext 求值的最小验证用例。
