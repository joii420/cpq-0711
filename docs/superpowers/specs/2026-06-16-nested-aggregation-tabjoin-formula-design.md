# 页签连表公式 — 嵌套聚合 `SUM(SUM()+SUM())` 设计方案（v2 · 卡片优先）

- 日期：2026-06-16（v2 重写，纳入独立评审 R1~R7 + 二轮语义探讨结论）
- 状态：设计待评审（brainstorming 产出，未进入实现计划）
- 作者：Claude（与用户协同 brainstorming）
- 范围决策：**本期只在产品卡片（模型 A）落地嵌套聚合**；Excel 视图列**含嵌套时显式报错引导用卡片渲染**，"Excel 引擎统一 / 退役 `TabJoinPlanEvaluator`"留作**独立后续 spec**（规避评审 R1/R2 两个致命前置依赖）。

## 0. 背景与现状（实测）

页签连表公式（cross_tab / TabJoin）已具备"宿主行键分组（KSUM/`projectToHostKey`）+ 条件聚合（SUMIF 族 `predicate`）+ 多源链式（`sources[]`）"能力。本方案在其上叠加**有限层嵌套聚合（≤2 层有意义）**。

### 0.1 关键现状事实（决定可行性与边界，均经代码核验）

| 事实 | 证据 |
|---|---|
| 模型 A 后端求值器是**递归**骨架（`targetExpr` 子树经回调递归求值），理论深度不限 | `FormulaCalculator.java` `evalCrossTab:267` → `targetRowValue:353` → `evaluateExpression:76`（:447 递归回调） |
| 前端序列化器把**可写形态钉死在 2 层**：FN 内嵌聚合 / K 套 K 直接抛错 | `formulaSerialize.ts:612`（FN 内不支持嵌套聚合）、`:508-510`（K 套 K）|
| KSUM **禁止聚合宿主自身列**（"宿主列请放到外层 SUM"）| `formulaSerialize.ts:521-524, 538-542` |
| 外层 FN body 循环**没有 `sumif_call` 分支**，内联 SUMIF 被 `default:break` 静默丢弃 | `formulaSerialize.ts:597-645` |
| `checkMappable` 只扫顶层一层（非整树）| `formulaSerialize.ts:1180-1182` |
| Excel 路径（`TabJoinPlanEvaluator`）单层非递归、`AGG_CALL` 不含 SUMIF、显式拒绝 `projectToHostKey`/多源 | `TabJoinPlanEvaluator.java:72-73, 148-200, 242-257` |
| **后端无 expression→token 序列化器**（仅前端有）；Excel 列存 `expression` 字符串 | `evaluateColumn:259` 吃字符串；`expressionToTokens` 仅 `formulaSerialize.ts:319` |
| Excel 单值的"无宿主全局归约"模式在 `FormulaCalculator` **不存在**（聚合天然以宿主行过滤为前提）| `FormulaCalculator.java:287-302` |

### 0.2 评审结论对范围的影响（为什么"卡片优先"）
- 评审 R1（后端无 string→token 解析器）、R2（无宿主全局归约模式不存在）是"Excel 复用统一引擎"的两个致命前置。两者都**只阻碍 Excel 路径**，不阻碍卡片路径（卡片本就走 token + 递归求值器）。
- 故本期**把嵌套聚合限定在卡片**：求值器已递归，主要工作落在**序列化器递归化 + 拆 KSUM 宿主列限制 + SUMIF 内联**。Excel 含嵌套先报错（沿用其对 KSUM/多源的现有做法），等独立 spec 再统一。

## 1. 统一语义模型（规范核心 · 唯一权威定义）

### 1.1 三件套：层级 / 取数 / 计算
嵌套聚合 = **自底向上递归 reduce**（"循环计算"：最内层先在其行集上做一次聚合 → 标量回代上层 → 上层再聚合）。每个聚合节点的语义由两件事确定，其余是纯算术：

1. **层级 = 作用域（scope）= 该聚合循环的行集**。作用域**只有两档**（由"唯一分组键 = 宿主行键"推出）：
   - **组内（within-key）**：当前宿主行键分区内的行；
   - **跨组 / 投影（across-key）**：跨所有宿主行键。
   - ⇒ **有意义嵌套深度 ≤2 层**（无第三个行集可绑）。第 3 层及以上的聚合嵌套在**校验期显式报错**（不静默算成恒等）。
2. **取数来源 = 是否参与宿主行键对齐 + 字段**：
   - **参与对齐 · 宿主自身列**（粒度 = 宿主行，可一键多行）→ **允许被组内聚合**（放开现有 KSUM 禁宿主列限制）；
   - **参与对齐 · 子页签列**（粒度 = 一键多条子行）→ 组内聚合（现有 KSUM 语义）；
   - **不参与对齐 · SUMIF 族**（`SUMIF/COUNTIF/AVGIF/MINIF/MAXIF`）→ **永远全局**：只按 `predicate` 过滤、与行键无关、广播到每组（来源对不上键 → 退化全局单值，已确认）。
3. **计算** = `+ - * /` 及函数组合，对回代后的标量求值。

### 1.2 投影规则（消除"谁是最外层"歧义）
**不给任何 token 特殊地位**，投影 = 求值器的外层循环：
- **有宿主（产品卡片 / 模型 A）** → 对每个宿主行键 g，把**整条表达式**在 g 的作用域里求值一遍 → 输出向量 `{ g → 值 }`，按行键拼接回宿主各行。
- **无宿主（Excel）** → 整条表达式在全局作用域求值一遍 → 单值（**本期：若 Excel 列表达式含嵌套聚合，配置/求值期显式报错引导用卡片**）。
- 其中：组内聚合在当前组行上 reduce；裸明细引用沿用"默认按组内行求和"；SUMIF 永远全局广播。

### 1.3 空值 / 异常语义（逐层一致，fail-soft + 必出诊断）
- `SUM/COUNT`（含 KSUM/KCOUNT）空集 → `0`；
- `AVG/MAX/MIN`（含 K 系）空集 → `null` → 整外层项塌 `0`（沿用现有 I-1/I-2）；
- **除零** → 该项塌 `0` + **试算诊断**（不抛异常、不出 NaN/Infinity）；
- **NULL 叶子** → `0`（与"全外连缺补 0"约定一致）；
- **文本叶子进数值聚合 / 算术** → **报错并在试算面板暴露**（配置错误必须吵闹，禁止静默塌 0 掩盖）；
- 多命中 / 类型错误沿用 `ERR` 哨兵 → 整外层塌 0 + 诊断。

### 1.4 样例走查
`SUM( SUM([来料.来料材料费用]+[来料.外购件材料费用]) * SUMIF([其他费用.要素]='材料管理费',[其他费用.比例]) )`，宿主 = 来料：
- 内层 `SUM([来料.…]+[来料.…])`：参与对齐 · 宿主自身列 → **组内聚合**（一键多行则求和，单行则恒等）→ 标量 `S_g`；
- `SUMIF([其他费用.…])`：SUMIF 族 → **全局单值** `R`（其他费用对不上键 → 全局），广播到每组；
- 内层式 = `S_g * R`；
- 外层 `SUM(...)` = 投影：卡片 → 向量 `{ g → S_g * R }`；Excel → 含嵌套 → 本期报错引导用卡片。

## 2. 范围

### 2.1 本期目标（In Scope）
1. **产品卡片（模型 A）** 页签连表公式支持**≤2 层嵌套聚合**（任意 `SUM/AVG/MIN/MAX/COUNT` 作内/外层 + 同层多 SUMIF/算术混合）。
2. 放开"组内聚合宿主自身列"（拆 KSUM 禁宿主列限制）。
3. 作者体验（方案 C）：文本表达式为主体，SUMIF 构造器产物**内联插入光标处**（可嵌进函数参数），与手敲产出同一套语法。
4. §1.3 空值/异常语义统一落地（含文本叶子报错暴露）。
5. **不破坏现有公式计算能力**（硬约束）：存量公式逐位等价（除法/AVG 类按 4 位小数舍入相等，见 §5.1）。

### 2.2 非目标（Out of Scope，留后续 spec）
- **Excel 视图列的嵌套聚合统一**（需后端 string→token 解析器 R1 + 无宿主全局归约 R2）→ 独立 spec；本期 Excel 含嵌套**显式报错**。
- **退役 `TabJoinPlanEvaluator`** → 随 Excel 统一一并处理。
- **第 3 层及以上嵌套**（无第三作用域）→ 校验期报错，永不支持（除非未来引入第二分组键，属另案）。
- 不改 `field_type` 枚举。

## 3. 架构设计

### 3.1 求值（后端，模型 A）
- 复用 `FormulaCalculator` 递归 cross_tab 通路（已具备）。需补/验证：
  1. **拆 KSUM 禁宿主列限制**：允许组内聚合 `projectToHostKey` 引用宿主自身列（求值侧确保宿主列进入 `targetRowValue` 的 sub-RowContext 可取值）。
  2. **内联 SUMIF 进嵌套**：确保 `targetExpr` 子树内的 `cross_tab_ref(predicate)` token 能在 sub-RowContext 下经 `predicateEval` 正确全局求值。
  3. **≤2 层校验**：求值侧对超 2 层聚合嵌套返回明确错误（与前端校验同源常量，§4.3）。
  4. **§1.3 异常语义**：除零塌 0+诊断、文本叶子报错（在 dry-run rows 的 errors 暴露）。
- **退化纪律（硬约束）**：不含新嵌套 / 不含宿主列组内聚合的 token，代码路径与现状完全一致（N=1 退化路径零变化）。

### 3.2 序列化（前端，核心改造）
- `expressionToTokens` 改为**递归下降解析**支持 **≤2 层**嵌套：
  - FN body 内允许再出现一层聚合（拆 `:612` 限制）+ `sumif_call` 分支（拆 `:597-645` 静默丢弃）；
  - 外层聚合包裹"参与对齐引用（含宿主自身列）"→ 映射为组内聚合 token（K* 或其等价），放开宿主列白名单；
  - **第 3 层聚合嵌套 → 抛明确错误**。
- `tokensToDrawerExpression`：嵌套 token 树（含内嵌 SUMIF `predicate` 回显）→ 文本（`renderTargetExprParts` 加 `cross_tab_ref(predicate)` 分支）。
- `checkMappable`：改为**遍历整棵 token 树**（非顶层 `some`，拆 `:1180` 单层扫描）。
- `parseFormulaSegments`（配色）/ `formulaBracketCheck`：括号深度栈 + SUMIF 深度在嵌套层正确维护。
- **round-trip 不变式**：任意合法 ≤2 层 expr → token → expr 幂等（加专项测试，§5.2）。

### 3.3 作者 UI（`TabJoinFormulaDrawer` + `FormulaRichInput`）
- SUMIF 构造器：从"末尾 side-token"改为**生成内联文本 `SUMIF(条件, 取值)` → `insertAtCursor` 插入光标**（NORMAL/SUBTOTAL 统一）。
- 旧 side-token 公式重开：`tokensToDrawerExpression` 仍能把带 `predicate` 的 token 还原成内联 SUMIF 文本展示（兼容回显，不丢 predicate）。
- 富文本提示更新：明确"≤2 层嵌套、宿主列可组内聚合、SUMIF 全局"。

### 3.4 试算
- `dry-run-token`（卡片逐行向量）走统一求值器，"配置即所见"，errors 暴露 §1.3 诊断。
- Excel `dry-run`：表达式含嵌套聚合 → 返回明确错误"Excel 列暂不支持嵌套聚合，请用卡片渲染"。

### 3.5 模块边界
- 求值核心：`FormulaCalculator`（基线锁定，改动从严，仅在递归骨架上"放开限制 + 加诊断 + 加深度校验"，不改热路径行为）。
- 序列化：`formulaSerialize.ts`（递归化，本期最大改动面）。
- 作者 UI：`TabJoinFormulaDrawer.tsx` / `FormulaRichInput` / SUMIF 构造器。
- 试算端点：`ComponentTabJoinResource`。

## 4. 数据 / 边界 / 防御

### 4.1 持久化
- **不改 token schema**：嵌套靠现有 `targetExpr` 子树表达；放开宿主列只是放开校验，不增字段。
- **不迁移存量数据**：旧 token / 旧 Excel `expression` 原样读取。

### 4.2 协议联动（AP-44 类）
- 拆 KSUM 禁宿主列 + SUMIF 内联进 body，是**跨前后端协议变换**，需对照 `docs/配置方法论-合并版.md §11` 矩阵逐点核验（enrich / normalize / cache key / 渲染分支 / 序列化双向 / 后端校验 / 路径采集 / refreshSnapshots / useDriverExpansions / QuotationStep2），写代码前 grep 全工程 + `codegraph_impact`。

### 4.3 深度上限同源
- "≤2 层"常量前后端**同源**（前端序列化校验 + 后端求值校验都引用同一约定值），避免前端放行后端栈溢出或反之。

## 5. 测试与验收（基线锁定模块，从严）

### 5.1 差异回归（兼容硬指标）
- 盘点**存量卡片 cross_tab 公式样本集**（来源：现有模板组件公式，列清单写进实现计划）；改造前后结果比对：**数值按 4 位小数舍入相等**（非逐位——新旧本就 double/setScale，逐位 0 差异在除法/AVG 上不成立，评审 R6）。
- 不含新特性的公式必须 100% 不变。

### 5.2 新能力单测
- `SUM(SUM())` 两层；**3 层 → 报错**；
- 组内聚合**宿主自身列**（一键多行求和 / 单行恒等）；
- 内联 SUMIF 在 FN body 内（全局广播 + 对不上键退化）；
- `AVG/MIN/MAX/COUNT` 各作内/外层；
- 空集（SUM→0 / AVG→塌0）、除零→塌0+诊断、NULL→0、文本叶子→报错；
- 有宿主出向量；
- **round-trip 幂等**（expr↔token，≤2 层全覆盖）。

### 5.3 强制 E2E（协议级 + AP-44）
- `quotation-flow.spec.ts`（SIMPLE）+ `composite-product-flow.spec.ts`（COMPOSITE）：全 `passed`，`'加载中' final count = 0`，8 Tab `'加载中'=0`；附 qf-19 + qf-21~28 截图。

### 5.4 自检
- 前端：`npx tsc --noEmit` 0 错误；改动 `.tsx`/`.ts` 逐个 Vite 200。
- 后端：`FormulaCalculator` 改动后强制 Quarkus 重启 + `/q/health` 200；纯逻辑单测在 **worktree 的 `cpq-backend`** 内亲跑 `mvnw` 全绿。

### 5.5 文档
- 同步 `docs/PRD-v3.md`（公式能力章 + 演进史）、`docs/RECORD.md`、`docs/配置方法论-合并版.md`（§11 + 附录）、`docs/反模式.md`（若新增协议点）。

## 6. 实施阶段（供 writing-plans 细化）
1. **P0 基线**：固化 §1 语义；盘点存量卡片公式样本集；搭差异回归框架（4 位舍入比对）。
2. **P1 求值器放开**：拆 KSUM 禁宿主列 + 内嵌 SUMIF 全局求值 + ≤2 层校验 + §1.3 诊断；退化纪律回归。
3. **P2 序列化递归化**：`expressionToTokens`/`tokensToDrawerExpression`/`checkMappable`/`parseFormulaSegments` 支持 ≤2 层 + 内联 SUMIF；round-trip 测试。
4. **P3 作者 UI**：SUMIF 构造器改内联插入；富文本/括号/配色适配嵌套。
5. **P4 回归验收**：差异回归 + 新能力单测 + 双 E2E + 文档。

## 7. 风险与缓解
- **基线模块改动（`FormulaCalculator`）**：仅"放开限制 + 加诊断 + 加校验"，不动热路径；差异回归 + 退化纪律双保险。
- **序列化递归化（P2 最大面）**：round-trip 不变式 + 前端单测重写兜底；先实现解析、再实现回显、最后配色，分步验证。
- **AP-44 协议漏点**：`codegraph_impact` + 矩阵逐点 + 双 E2E。
- **Excel 报错引导的体验**：报错文案明确（"请用卡片渲染"），与现有 KSUM/多源报错风格一致。

## 8. 实现期待确认项（开放问题）
1. 存量卡片 cross_tab 公式样本集的具体清单（P0 盘点产出）。
2. `expressionToTokens` 在"外层聚合 + 宿主自身列 + 子页签列 + 内联 SUMIF"混合时的精确折叠规则（P2 细化）。
3. 前后端"≤2 层"同源常量的承载位置。
4. 文本叶子报错的精确判定点（解析期可判的尽量前移到配置期）。
