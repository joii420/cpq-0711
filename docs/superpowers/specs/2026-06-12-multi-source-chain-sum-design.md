# 多 source 链式 SUM —— 一个 SUM 内引用多个关联页签（行键包含链 LEFT JOIN）+ KSUM 嵌套预聚合（降维投影）设计

> 日期：2026-06-12 ｜ 状态：**v3.1（在 v3 基础上采纳第 2 轮评审 I-1/I-2 精修：纠正决策 K 空集语义的真实代码落点 + 补 KAVG 空集 null 的污染范围口径；转 plan 就绪）** ｜ 基线 commit：`beb1d00`（细项多命中 ⚠ 错误态旁路已合并）
>
> **【v3.1 精修摘要】**：
> - **I-1**：纠正决策 K 落点——真正"空集吞 0"在 `evalCrossTab` 的 `hits.isEmpty()→return ZERO`（后端 `:233`、前端 `formulaEngine.ts:287` `hits.length===0→out=0`），**不是** `.average().orElse(0)`（后端 `:243`，hits 非空时根本不触发 orElse 的死分支）。改法 = 在空集提前返回点之前，对 `projectToHostKey && agg∈{AVG,MAX,MIN} && 空集` 返 null（而非统一 ZERO）。见 §4.2 / §4.4 / §10.1-K。
> - **I-2**：补 KAVG/KMAX/KMIN 空集 null 的污染范围——注入外层 `(null.x)` 使**整个外层 SUM 表达式塌成 0 + crossTabError ⚠**（不是只扣该项；与 `beb1d00` "细项多命中→整公式 0+⚠" 一致）。§7.3 对拍用例 8 断言口径 = **值 0 且 errors 非空**。见 §4.2 / §7.3。
> 承接：`specs/2026-06-11-tabjoin-rowkey-host-grouping-design.md`（批3 宿主行键分组 + FN 聚合 + 试算=渲染）
>      + RECORD `[2026-06-12] 页签连表公式 行级聚合 SUMPRODUCT（批4）`（打通单 source `targetExpr` 入口）
>      + master `beb1d00`（细项多命中渲染 ⚠ crossTabError 旁路，求值数值零改）
> 本特性 = 批4 的**直接延伸 + 升级**：把"一个 `SUM(targetExpr)` 只能引用单个细 source 页签"放宽为"**多个呈行键包含链的 source 页签**同进一个 SUM"，并**新增内层聚合函数族 `KSUM/KAVG/KMAX/KMIN/KCOUNT`（按宿主键降维投影预聚合）**绕开"不可比 → 笛卡尔"的硬限制。

---

## 【v2 变更摘要】（先读这一节）

v1（多 source 链式 SUM）的核心结论**全部保留**；v2 在其之上叠加 **KSUM 嵌套预聚合**，并据用户最新拍板**固化** v1 §10 中的若干开放决策。逐项对照：

| v1 设计点 | v2 处置 |
|---|---|
| 多 source 链式 SUM（行键包含链 LEFT JOIN，语义 A） | ✅ **保留**（裸引用更细/可比 source 的合法路径不变） |
| §10-D 更粗 source `>1` 命中如何处理 | ✅ **拍定为「报错 ⚠」**（复用 `beb1d00` crossTabError 旁路）；提示"该页签按宿主键有多行,需加区分行键或改用 KSUM 聚合" |
| §10-E 链中间层缺失是否合法 | ✅ **拍定为「合法，允许跳层」**（两两可比即合法，不要求层级连续） |
| §10-H AVG/MAX/MIN/COUNT 是否本期都做 | ✅ **拍定为「全做」**：外层 `SUM/AVG/MAX/MIN/COUNT` 五种 + 内层 `KSUM/KAVG/KMAX/KMIN/KCOUNT` 五种**本期一起** |
| 不可比维度（料×元素 vs 料×外购件）同进 SUM | 🆕 **v2 新增 KSUM 解法**：不再一律拒——**被 KSUM 包裹的页签**先按宿主键塌缩成宿主粒度标量，绕开笛卡尔；**裸引用**不可比 source 仍拒（§10-D 报错）。 |
| 行键定义 | ✅ **保持不变**（仍是行唯一标识）；KSUM 按"宿主结果行键"（宿主 rowKeyFields，是被聚合页签行键的子集）分组。**这是用户不改外购件行键还能聚合的关键。** |

**v2 新增的一句话能力**：用户在外层 `SUM(...)` 内可以写 `KSUM([外购件.单价] * [外购件.数量])` —— 它把"外购件"页签**按宿主结果行键分组、逐行算内层表达式、塌缩成每宿主键一个标量**，该标量作为常量广播进外层 SUM 的每个驱动行。**优先级：先内层 KSUM 塌缩，再外层 SUM 聚合。**

用户终态公式（料8 真机验收点）：

```
SUM( [元素.重量(g)]/1000 * [元素.单价] * (1 + [元素.损耗率]/100)
     + KSUM([外购件.单价] * [外购件.数量]) ) * [组成用量]
```

---

## 【v3 变更摘要】（先读这一节 —— 采纳一轮独立评审）

v2 的核心能力（多 source 链式 SUM + KSUM 嵌套预聚合）**全部保留**；v3 在其上采纳一轮独立代码评审的 Critical / 决策修订 / Important 结论，并**以实地核对的真实行号**重写涉及的设计点。逐条对照（详设计落在对应章节，本表仅索引）：

| 评审项 | v3 处置 | 落点章节 |
|---|---|---|
| **C1 前后端求值上下文对称 + KSUM inner token 白名单** | ① 后端 `targetRowValue` 的 `sub`（实地核对 `FormulaCalculator.java:255-262` 仅透传 fieldValues/currentRowRaw/basicDataValues/crossTabRows）**补齐** componentSubtotals/quotationFields/productAttributes/previousRowSubtotal，与前端 `evalRow`（`formulaEngine.ts:275-278` 已透传 componentSubtotals/productAttributes/quotationFields）**对齐**；删去 v2 "无需改透传契约" 措辞。② **KSUM inner token 白名单**：内层 targetExpr 仅允许 `field`(限被聚合页签)/`operator`/`number`/`bracket_open\|close`/`global_variable`，**显式拒绝** component_subtotal / quotation_field / product_attribute / previous_row_subtotal / b_field(宿主列) / 跨页签 field / 嵌套 KSUM，序列化期 + 后端 validator 双端镜像拒绝 | §2.4 / §2.5 / §3.3 / §4.4 / §6 |
| **C2 KSUM 折叠不复用单列 shortcut** | KSUM 折叠（inner 单列或多列）**一律走 targetExpr 构造 + 强制 `projectToHostKey:true`**，**绝不复用** `formulaSerialize.ts:244-257` 单列 shortcut（该 shortcut 走 `makeCrossTabRef` 产单 `target`、无 targetExpr、无 projectToHostKey）；补单列 KSUM 单测断言产物含 projectToHostKey + targetExpr | §3.3 / §8.1 |
| **C3 lexer `K SUM` 误拆文案** | lexer（`formulaSerialize.ts:126-135` 贪婪吃 `[A-Za-z]+`）遇单字母 `K` + 空白 + 聚合词（`K SUM` 等）给专门错误文案"KSUM/KAVG… 不能拆写，请连写"，不落到 line 134 的通用"无法识别的标识符" | §3.1 / §5 |
| **决策 K — KSUM 空集按 agg 分流** | KSUM/KCOUNT 空集(0 命中)→ **0**；KAVG/KMAX/KMIN 空集 → **null**（**【v3.1 I-1】落点 = 空集提前返回分支：后端 `evalCrossTab` `:233` `hits.isEmpty()→ZERO` / 前端 `formulaEngine.ts:287` `hits.length===0→out=0` 之前判分流，不动 `:243` `.average().orElse(0)` 死分支**）；下游：外层算术遇此 null → **走既有 crossTabError 旁路渲染 ⚠**（前后端一致，选定此实现）。料8 终态 KSUM=SUM→0 → 25.41 不受影响 | §4.2 / §10.1(K) |
| **决策 M — 本期只允许 KSUM 在外层 FN 内** | 顶层裸 KSUM（不在外层 SUM/AVG… 内）**本期不支持**，序列化期报错；列为 fast-follow。降低改动面 + 规避与 C2 单列 shortcut 冲突 | §1 / §3.3 / §10.1(M) |
| **决策 J — 前后端都拒 K 套 K** | KSUM inner 再出现 K*（嵌套投影）→ **序列化期 + 后端 validator 双端拒绝**（不只前端），消息明确 | §3.3 / §6 / §10.1(J) |
| **I1 顺序依赖 token 在 inner 被拒** | previous_row_subtotal 等并入 C1 白名单（inner 拒绝） | §2.4 / §3.3 |
| **I2 同页签冲突** | 某页签**已被 KSUM 包裹**则禁止其在同一外层 FN 内**再被裸引用**（反之亦然）→ 序列化期报错 | §3.3 |
| **M2 E2E seed 前置(T0)** | RECORD 记 2026-06-11 选配/组合数据已清；新增 §8.5 "T0 测试数据 seed"，作为 E2E 强制前置任务 | §8.5 / Task T0 |
| **Minor 挂入** | ① aggregateRows 抽取时保留 NONE 分支 `hits>1→ERR` 旁路（checklist）；② 配色 `insideKsum` 从"可选"提为 **P2 必做**；③ Excel 模型 B(TabJoinPlanEvaluator) 遇 KSUM token 运行期降级行为写明；④ 补"同一 targetExpr 内 2 个独立 KSUM 引不同页签"对拍用例；⑤ 核价单视图 crossTabRows 装配单独验收(AP-50) | §4.3 / §5.1 / §1 / §7.3 / §7.2 |

> **v3 一句话**：v2 把能力定齐，v3 把**前后端求值上下文对称**和**KSUM inner 边界（白名单 + 空集语义 + 拒嵌套/拒裸引冲突）**这两块"魔鬼细节"锁死，并把 K/M/J 从开放决策转为已定，使 spec 可直接转 plan。

---

## 0. 一句话目标

让用户在报价单页签连表公式里写：

```
SUM( [元素.重量(g)]/1000 * [元素.单价] * (1 + [元素.损耗率]/100)
     + KSUM([外购件.单价] * [外购件.数量]) ) * [组成用量]
```

其中宿主=「来料」（行键=料件）、`元素`按 **料件×元素**（料8 → Ag/Ni 两行）、`外购件`按 **料件×采购行**（多行，行键比宿主细，且与元素**互不包含**）、`[组成用量]` 是宿主本行字段。期望求值：

```
对料8：
  内层 KSUM 先塌缩：把 外购件 按「料件」(= 宿主结果键) 分组,组内逐行算 单价×数量,组内求和
                   → 得到 料8 一个标量 Kp（= Σ_采购行 单价×数量）
  外层 SUM 逐元素驱动行：Σ_{元素∈{Ag,Ni}} ( 元素行表达式 + Kp )
                       （Kp 作为常量,在每个元素驱动行被加一次 → 进 SUM 被加 N=2 次）
  再 × 宿主.组成用量
若料8 外购件 0 行 → Kp = 0（或 null→0）→ 外购件项不计 → 结果 = 25.41（用户真机验收点）
```

> **KSUM 的价值 = 降维投影**：外购件与元素**互不包含**（料×采购行 ⊄/⊅ 料×元素），裸引用进同一 SUM 会笛卡尔积无唯一解（v1 §1 非目标拒绝）。**KSUM 先把外购件沿宿主键塌缩成料件粒度标量**，使它对外层 SUM 而言只是"宿主本行的一个常量"，从而**绕开"不可比 → 笛卡尔"**——用户因此**不必修改外购件行键定义**（行键仍唯一）即可聚合。

---

## 1. 目标与非目标

### 目标（本期做）
- **【v1 保留】** 支持**单个 `SUM(targetExpr)` 内引用 N≥2 个 source 页签**，前提是这些 source（连同宿主行键）两两呈**行键包含链**（⊆/⊇，顺序无关）。求值语义 = **A**（最细 source 驱动、更粗 source 广播、按宿主键 SUM）。
- **【v1 保留 + v2 固化 §10-E】** 链上两两可比即合法，**允许跳层**（不要求层级连续）。
- **【v2 新增】** 内层聚合函数族 **`KSUM/KAVG/KMAX/KMIN/KCOUNT`（降维投影预聚合）**：在外层 `SUM(...)` 的 targetExpr 内出现，对**单个被聚合页签**按宿主结果行键分组、逐行算 inner targetExpr、按 K-agg 塌缩成**每宿主键一个标量**，作为常量广播进外层逐行表达式。**优先级高于外层 SUM**（先内层塌缩，再外层聚合）。
- **【v2 固化 §10-H】** 外层 `SUM/AVG/MAX/MIN/COUNT` 五种 + 内层 `KSUM/KAVG/KMAX/KMIN/KCOUNT` 五种**本期一起做**。
- **【v1 保留】 既有单 source `targetExpr` token 行为零变**（单 source = N=1 退化），库内存量 token 不迁移即继续有效。

### 非目标（本期不做，明确拒绝/留后）
- ❌ **裸引用（非 KSUM）互不包含维度的多 source 进同一 SUM**（如裸 `[元素.x] + [外购件.y]`，料×元素 vs 料×采购行互不⊆）→ 笛卡尔无唯一解 → **序列化期报错拒绝**（消息指出哪两页签不可比，并提示"改用 KSUM 聚合"）。**注**：被 KSUM 包裹后即合法（v2 新增解法）。
- ❌ **裸引用更粗 source 按宿主键有 >1 命中**（§10-D 拍定）→ **报错 ⚠**（复用 `beb1d00` crossTabError 旁路）；提示"该页签按宿主键有多行,需加区分行键或改用 KSUM 聚合"。视为配置错误。
- ❌ **EXCEL 列模型 B**（`TabJoinPlanEvaluator`）：本期**不接通**多 source 链式 / KSUM；配置方法论指南继续标注差异。**【v3 Minor ③ 运行期降级行为写明】**：`TabJoinPlanEvaluator` 遇 `projectToHostKey===true` 的嵌套 KSUM token（或 `sources.length≥2`）时——**选定行为：报错（不静默忽略）**：返回结构化错误 "Excel 列模型暂不支持 KSUM 降维聚合 / 多 source 链式 SUM，请改用页签连表渲染（模型 A）"，**不静默吞 token 产出错误数值**（静默忽略会让 Excel 导出列得到"少算了 KSUM 项"的错值，比报错更危险）。落点：`TabJoinPlanEvaluator` 求值入口判 token 形态，命中即抛 → Excel 导出该列标错/留空 + 提示。**理由**：宁可让用户在 Excel 模板配置时显式看到"该公式 Excel 不支持"，也不要导出一个静默少算的数。
- ❌ **【v3 决策 J 定】K 套 K 嵌套**（`KSUM(... KSUM(...) ...)`）：本期**不支持**，**前端序列化期 + 后端 validator 双端拒绝**（不只前端，防直接 POST 绕过）。KSUM inner targetExpr 仅允许白名单 token（§2.4），**不得再含 K\*、不得跨页签**。fast-follow 再论证。
- ❌ **【v3 决策 M 定】顶层裸 KSUM**（不在任何外层 FN 内的 `KSUM(...)`，如整条 `= KSUM([外购件.单价]*[外购件.数量]) * [组成用量]`）：**本期不支持**，序列化期报错（消息："KSUM 等降维聚合本期只能写在外层 SUM/AVG/MAX/MIN/COUNT 内"）。理由：降低"顶层 token 收集也要识别 KSUM 折叠"的改动面 + 规避与 C2 单列 shortcut 路径冲突。**列为 fast-follow**（§10.1-M）。
- ❌ **【v3 C1/I1/I2 边界】KSUM inner 引用宿主上下文 token**：inner targetExpr 不得含 `component_subtotal` / `quotation_field` / `product_attribute` / `previous_row_subtotal` / `b_field`（宿主列）/ 跨被聚合页签的 `field`（详 §2.4 白名单），序列化期 + 后端 validator 双端拒绝。
- ❌ **【v3 I2】同页签既被 KSUM 包裹又被裸引用**：某页签在同一外层 FN 内若已出现在 KSUM 内，禁止再被裸引用（反之亦然）→ 序列化期报错（§3.3）。
- ❌ 嵌套 BNF `{path}` 引用进 SUM/KSUM（沿用批4 既有禁止）。

---

## 2. Token 模型设计

### 2.1 现状（单 source `targetExpr` token，批4 / `beb1d00` 后）

`FormulaToken`（`cpq-frontend/src/pages/component/types.ts:134` 起）cross_tab_ref 专用字段（**已核实真实 schema**）：

```ts
type: 'cross_tab_ref'
source?: string          // 单个 source componentId（UUID, AP-37 稳定 ID）
sourceLabel?: string     // 显示名
target?: string          // 单列名；targetExpr 存在时为 ''；COUNT 聚合时为空
match?: Array<{a:string; b:string}>   // a=source 行键字段, b=宿主行键字段
agg?: string             // NONE/SUM/AVG/COUNT/MAX/MIN
targetExpr?: FormulaToken[]   // 行级表达式：field / b_field / operator / number / bracket / global_variable
```

求值真实点位（**已核实**）：
- 前端 `formulaEngine.ts:257-308`（`cross_tab_ref` case，内含 `evalRow` `:272-279` + agg switch `:282-298` + crossTabError 旁路 `:300-307`）。
- 后端 `FormulaCalculator.evalCrossTab` `:206-249` + `targetRowValue` `:252-266` + `appendToken` 的 `field` case `:86-88` / `cross_tab_ref` case `:159-167`。

以 `source` 单页签的行按 `match` 过滤 `hits`，`hasTE` 时对每个 hit 在 `aFieldValues`（A 行列）+ `currentRowRaw`（B 行）上下文算 `targetExpr` → 按 `agg` 聚合。**这是「1 个 source ⋈ 宿主」两路 join**。

### 2.2 【v1 保留】多 source 链 token schema（有序 source 链）

新增可选字段 `sources`（**有序数组，最细 → 更粗**），`source`（单数）**镜像为最细 source**（=驱动），向后兼容。

```ts
// cross_tab_ref 专用字段（新增；其余字段不变）
sources?: Array<{
  source: string;                       // source 页签 componentId（UUID）
  sourceLabel?: string;                 // 显示名（chip 显示用）
  match: Array<{ a: string; b: string }>; // 该 source ⋈ 宿主 的行键配对（a=source 字段, b=宿主字段）
}>;
// 链有序约定：sources[0] = 最细（驱动行集，= 顶层 source/sourceLabel/match 冗余镜像）；
//             sources[1..] = 逐渐更粗（行键 ⊆ sources[0] 的行键）。
```

`targetExpr` 内 `field` token 带 `source` 限定 componentId 消歧义（§2.4，v1 方案 B 保留）。

### 2.3 【v2 新增】KSUM 嵌套聚合 token —— 递归结构

**核心洞察**：KSUM 本质就是一个**嵌套的、按宿主键塌缩的 cross_tab_ref 聚合 token**——它有自己的 source（被聚合页签）、自己的 agg（KSUM 对应 SUM、KAVG→AVG…）、自己的 inner targetExpr（对被聚合页签逐行算）、自己的 match（被聚合页签 ⋈ 宿主），且 **`projectToHostKey: true` 标记它"塌缩成每宿主键一个标量"**（区别于外层 cross_tab_ref 的"按驱动 join 行集聚合"）。

因此 KSUM token **复用 `cross_tab_ref` 的整套 schema**，只多一个 `projectToHostKey` 标记 + 把 K-agg 字面映射回 SUM/AVG/…。它**作为一个 token 嵌入外层 cross_tab_ref 的 `targetExpr` 数组**（递归结构）：

```ts
// 外层 SUM token（含一个嵌套 KSUM token 在 targetExpr 内）
{
  type: 'cross_tab_ref',
  source: '<元素componentId>',         // 外层驱动 = 元素（最细可见 source）
  sourceLabel: '元素',
  target: '',
  agg: 'SUM',                           // 外层聚合
  match: [{ a:'料件', b:'料件' }],      // 元素 ⋈ 宿主
  // sources 省略 = 外层只有元素一个可比 source（KSUM 包裹的外购件不进 sources 链）
  targetExpr: [
    // [元素.重量(g)]/1000 * [元素.单价] * (1 + [元素.损耗率]/100)
    { type:'field', value:'重量(g)', source:'<元素Id>' },
    { type:'operator', value:'/' }, { type:'number', value:'1000' },
    { type:'operator', value:'*' },
    { type:'field', value:'单价', source:'<元素Id>' },
    { type:'operator', value:'*' },
    { type:'bracket_open' },
    { type:'number', value:'1' }, { type:'operator', value:'+' },
    { type:'field', value:'损耗率', source:'<元素Id>' },
    { type:'operator', value:'/' }, { type:'number', value:'100' },
    { type:'bracket_close' },
    { type:'operator', value:'+' },
    // ★ v2 嵌套 KSUM token —— 一个完整的 cross_tab_ref，projectToHostKey:true
    {
      type: 'cross_tab_ref',
      projectToHostKey: true,            // ★ v2 标记：按宿主键塌缩成宿主粒度标量（区别外层 join-set 聚合）
      source: '<外购件componentId>',     // 被聚合页签
      sourceLabel: '外购件',
      target: '',
      agg: 'SUM',                        // KSUM → SUM（K* 字面映射，见下表）
      match: [{ a:'料件', b:'料件' }],   // 外购件 ⋈ 宿主结果行键（= 分组键）
      targetExpr: [                      // ★ inner targetExpr：[外购件.单价] * [外购件.数量]
        { type:'field', value:'单价', source:'<外购件Id>' },
        { type:'operator', value:'*' },
        { type:'field', value:'数量', source:'<外购件Id>' },
      ],
    },
  ],
}
```

**K-agg 字面映射（token 上的 `agg` 存映射后的标准 agg，`projectToHostKey` 区分内/外层）**：

| 公式关键词 | token `agg` | token `projectToHostKey` | 语义 |
|---|---|---|---|
| `SUM/AVG/MAX/MIN/COUNT`（外层） | `SUM`/`AVG`/… | **缺省 / false** | 按外层驱动 join 行集聚合（v1） |
| `KSUM` | `SUM` | **true** | 按宿主结果键分组塌缩成标量 |
| `KAVG` | `AVG` | **true** | 同上，取平均 |
| `KMAX` | `MAX` | **true** | 同上，取最大 |
| `KMIN` | `MIN` | **true** | 同上，取最小 |
| `KCOUNT` | `COUNT` | **true** | 同上，组内命中行数 |

> **为何用 `projectToHostKey` 标记而非独立 `type`**：KSUM token 与 cross_tab_ref **结构完全同构**（source/agg/match/targetExpr 字段一一对应），求值也复用同一"行集聚合"原语（仅分组键不同）。独立 type 会迫使前后端 `appendToken` switch 多一个分支 + 求值器再写一套近乎重复的逻辑（DRY 违背）；`projectToHostKey` 布尔标记 = 同分支内一个 if，**复用度最高、改动面最小**。备选 A（独立 `type:'cross_tab_proj'`）取舍：语义更显式但代码重复；备选 B（在 `agg` 存 `'KSUM'` 字面、求值期 strip 前缀）取舍：token 字面更贴公式，但散落各处需反复判前缀，**否决**——`agg` 应存标准聚合字面，`projectToHostKey` 单独承载"是否降维投影"维度。**采用 `projectToHostKey` 标记。**

### 2.4 【v1 保留 + v3 收紧】`targetExpr` 内 token 的 source 归属 + KSUM inner 白名单

多 source / KSUM 下同一 targetExpr 内 `[元素.单价]` 与 KSUM 内 `[外购件.单价]` 来自不同 source，`field` token 必须带 `source` 限定 componentId（方案 B）。`value` 仍存裸列名；`field.source` 缺省 = 该 cross_tab_ref 的 `source`（单 source 退化兼容）。

- 外层 `field` 的 `source` 必须 ∈ 外层 `sources` 链（或 = 外层 `source`）。
- KSUM 内层 `field` 的 `source` 必须 === KSUM token 自己的 `source`（被聚合页签）——序列化期强制校验（§3.3）。

#### 【v3 C1/I1/J 新增】KSUM inner targetExpr token 白名单（强制，前后端双端镜像）

KSUM 是"对**单个被聚合页签**逐行算 inner 表达式再塌缩"的算子，其 inner targetExpr **只允许引用被聚合页签自己的列 + 纯算术 + 全局变量常量**。明确白名单 / 黑名单：

| token type | inner 是否允许 | 理由 |
|---|---|---|
| `field`（**限被聚合页签**，`field.source === KSUM.source`） | ✅ 允许 | KSUM 对被聚合页签逐行算的本体 |
| `operator` / `number` / `bracket_open` / `bracket_close` | ✅ 允许 | 纯算术结构 |
| `global_variable` | ✅ 允许 | 与行无关的常量，逐行广播安全 |
| `field`（**跨被聚合页签**，source ≠ KSUM.source） | ❌ 拒 | 跨页签请放外层（§3.3-a） |
| `b_field`（宿主自身列） | ❌ 拒 | 宿主列归外层 SUM（§3.3-b）；KSUM 按宿主键分组，宿主列在组内是常量但语义易误用 |
| `component_subtotal` / `quotation_field` / `product_attribute` | ❌ 拒（**v3 I1/C1**） | 宿主级上下文标量，混入被聚合页签逐行算无明确语义；如需请放外层 |
| `previous_row_subtotal` | ❌ 拒（**v3 I1**） | 顺序依赖 token，KSUM 内"组内行序"无定义 → 语义不良 |
| `cross_tab_ref`（嵌套 KSUM / K 套 K） | ❌ 拒（**v3 J**） | K 套 K 本期不支持 |
| `path`(`{...}` BNF) | ❌ 拒 | 沿用批4 既有禁止 |

- **校验落点**：前端 `expressionToTokens` 折叠 KSUM 时即逐 token 比对白名单，命中黑名单 → 抛错，**消息指明该 token 类型为何不允许**（如"KSUM() 内不支持引用上一行小计(previous_row_subtotal)，请放到外层"）。
- **后端镜像**：`TokenMappabilityValidator` 对 `projectToHostKey` 子 token 的 targetExpr 同样逐 token 比对白名单（防前端绕过直接 POST），违者拒绝 publish / dry-run（§6.2）。
- **注意**：外层 cross_tab_ref 的 targetExpr **不受**此白名单约束（外层允许 b_field 等，见 §2.1 / batch4），白名单**仅作用于 `projectToHostKey:true` 的 KSUM inner**。

### 2.5 【v2 扩展】向后兼容判定（强制，库内存量不迁移）

求值引擎按以下判定分派，**存量单 source / 无嵌套 token 一行不改即继续有效**：

```
isMultiSourceChain(token) := Array.isArray(token.sources) && token.sources.length >= 2     // v1
isProjectKsum(token)      := token.projectToHostKey === true                               // v2
hasNestedKsum(targetExpr) := targetExpr?.some(t => t.type==='cross_tab_ref' && t.projectToHostKey)  // v2
```

- 既有单 source token：无 `sources`、targetExpr 内无嵌套 cross_tab_ref → 三判定全 false → **走批4 / `beb1d00` 既有路径，逐字不变**。
- `isMultiSourceChain` → v1 N 路 join 分支。
- targetExpr 内含 `isProjectKsum` 子 token → v2 求值期递归先塌缩该子 token（§4.3）。

> **不新增 DB 列、不改 jsonb 存储结构**（`sources` / `projectToHostKey` / 嵌套 cross_tab_ref 均是 `formula_tokens` jsonb 内嵌字段），**不触发 snapshot 数据迁移**；存量 PUBLISHED 模板 snapshot 内的旧 token 天然继续有效（AP-39 类残留不适用）。**`projectToHostKey` 缺省即 false → 旧 token 不受影响。**

---

## 3. 序列化设计（`formulaSerialize.ts`：lexer + `expressionToTokens` + 回显）

### 3.1 【v2 新增 + v3-C3 修订】lexer 加 K\* func token 识别 + `K SUM` 误拆专门文案（`lex` `:84-150`，已实地核对）

当前 `lex` 贪婪吃完连续字母 `[A-Za-z]+`（`:126-128`），在 `:130` 识别 func：`['SUM','AVG','MAX','MIN','COUNT'].includes(upper)`，未命中则 `:134` 抛通用"无法识别的标识符"。**v3 扩为**：

```ts
const OUTER_FNS  = ['SUM','AVG','MAX','MIN','COUNT'];
const INNER_FNS  = ['KSUM','KAVG','KMAX','KMIN','KCOUNT'];
// word 已贪婪吃完连续字母, upper = word.toUpperCase()
if ([...OUTER_FNS, ...INNER_FNS].includes(upper)) {
  tokens.push({ kind: 'func', name: upper });   // RawToken.func 复用,name 区分
  continue;
}
// 【v3-C3】单字母 K + 空白 + 聚合词的误拆专门文案:
//   写 `K SUM(...)` 时 lexer 在 :91 跳过空白 → 'K' 单独成一个 word(未命中 func) →
//   原会落 :134 通用"无法识别的标识符 'K'", 用户难定位.
//   v3: 检测 word === 'K'(或单字母 K) 且下一个非空白 word ∈ OUTER_FNS → 抛专门文案.
if (upper === 'K') {
  // 向前预览(跳过空白)下一个连续字母 word
  let j = i; while (j < expr.length && /\s/.test(expr[j])) j++;
  let peek = ''; let p = j; while (p < expr.length && /[A-Za-z]/.test(expr[p])) peek += expr[p++];
  if (OUTER_FNS.includes(peek.toUpperCase())) {
    throw new Error(`KSUM/KAVG/KMAX/KMIN/KCOUNT 不能拆写，请连写（如 K${peek.toUpperCase()} 写成 "K${peek.toUpperCase()}"，不要写成 "K ${peek.toUpperCase()}"）`);
  }
}
// 未命中 → 既有 :134 通用"无法识别的标识符"
```

- `RawToken` 的 `func` 注释（`:79`）更新为 `SUM/AVG/MAX/MIN/COUNT/KSUM/KAVG/KMAX/KMIN/KCOUNT`。
- **整词匹配保障**：`KSUM` 连写时 lexer `:126-128` 贪婪吃完 `[A-Za-z]+` → 整体得到 `KSUM` 一个 word，命中 INNER_FNS，**不会**被切成 `K`+`SUM`（已实地核对 `:128` 贪婪循环）。仅当用户**显式写空白** `K SUM` 时才触发 C3 误拆文案分支。
- §5 配色器同步：`K SUM` 这类误拆写法在编辑器轻量试解析时即抛 C3 文案到 FN 块下方红字（§5.2）。

### 3.2 【v1 保留】多 source 链：收集 / 成链校验 / 排序 / 建 token

替换当前 `:307-316` 的单 source 抛错（"只允许引用同一个细页签"）为**有序多 source 收集 + 成链校验**（v1 §3.1-3.4 逻辑保留）：

- 遍历 FN body 收集所有 `[别名.列]`（componentId ≠ selfComponentId）为 source 引用，`field` token 写 `source` 限定。
- 成链校验（N≥2 时）：`collect rowKeySets = [H, s1.rowKeyFields, ...]`，两两 `comparable`（复用 `formulaSerialize.ts` 已 export 的 `isSubset`/`comparable`）；**任一对不可比 → 抛错**，消息指出哪两页签 + 提示"改用 KSUM 聚合"。
- **【v2 固化 §10-E 跳层合法】**：校验只要求**两两可比**，不要求层级连续——链 `料 ⊆ 料×元素×子级` 跳过 `料×元素` 中间层合法。
- 最细 = rowKeyFields 集合最大者 = `sources[0]` = 驱动。

### 3.3 【v2 新增 + v3-C2/J/M/I2 修订】KSUM 折叠（`expressionToTokens` FN body 解析）

当前 FN body 行级路径在 `:288`（实地核对）对 `case 'func'` 直接抛 `"暂不支持嵌套聚合函数"`。**v3 改为**：识别 inner K\* func → 折叠成嵌套 cross_tab_ref token（**强制 `projectToHostKey:true`**）压进外层 `targetExpr`。

#### 【v3-C2】KSUM 折叠**一律走 targetExpr 构造，绝不复用单列 shortcut**

实地核对：`expressionToTokens` 对 FN body 有两条路径——
- **单列 shortcut**（`:244-257`）：body 恰好一个 `[alias.field]` → 走 `makeCrossTabRef`，产**单 `target`、无 `targetExpr`、无 `projectToHostKey`**。
- **行级 targetExpr 路径**（`:259-337`）：解析成 `targetExpr` 数组。

**v3 强制规则**：KSUM 折叠（无论 inner 是单列 `KSUM([外购件.费用])` 还是多列 `KSUM([外购件.单价]*[外购件.数量])`）**一律构造 targetExpr 并打 `projectToHostKey:true`**，**绝不复用单列 shortcut 的 `makeCrossTabRef`**。原因：单列 shortcut 不产 targetExpr / 不带 projectToHostKey，若复用则单列 KSUM 会退化成普通 cross_tab_ref（按外层 join 行集聚合而非按宿主键塌缩）→ 语义错。
- **落点**：KSUM 折叠在外层 FN body 解析里**先于** `:244` 单列 shortcut 判定捕获（遇 `raw.kind==='func' && name ∈ INNER_FNS` 即进 KSUM 分支），单列 shortcut 只服务"外层 FN 直接包单列"的批4 旧形态。
- **单测断言**（§8.1）：`KSUM([外购件.费用])`（inner 单列）→ 产物必须含 `projectToHostKey:true` + `targetExpr:[{field,source:外购件}]`，**不得**是 `target:'费用'` 的单列 shortcut 形态。

#### 折叠算法（外层 FN body 逐 raw token 时）

```
遇 raw.kind==='func' 且 name ∈ INNER_FNS（KSUM/KAVG/KMAX/KMIN/KCOUNT）：
  1. 扫描其匹配 ')'（同外层 closeIdx 逻辑,支持嵌套括号）
  2. innerBody = 括号内 raw tokens
  3. 递归解析 innerBody → innerTargetExpr（行级解析 + §2.4 白名单收紧）：
     约束（校验，违者抛错，消息指明原因）：
       a. innerBody 内所有 [别名.列] 必须解析到**同一个**被聚合页签 kSrc
          （跨页签 → 抛 "KSUM() 内只能引用同一个页签的列,跨页签请放到外层"）
       b. innerBody 内**不得**出现裸宿主字段 b_field / 宿主自身列
          （检测无点裸字段 或 alias.componentId===selfComponentId → 抛
           "KSUM() 内不能引用宿主自身列,宿主列请放到外层 SUM"）
       c.【v3-J】innerBody 内**不得**再含 K* func（K 套 K 本期不支持）→ 抛
          "KSUM() 内暂不支持再嵌套 K 聚合"
       d.【v3-C1/I1】innerBody 解析出的每个 token 必须 ∈ §2.4 白名单
          （field[限kSrc]/operator/number/bracket/global_variable）；
          命中黑名单 component_subtotal / quotation_field / product_attribute /
          previous_row_subtotal / 跨页签 field → 抛,消息指明该 token 类型为何不允许
       e. innerBody 内 field token 一律带 source = kSrc.componentId
  4. push 嵌套 token 进外层 targetExpr（强制 projectToHostKey:true，C2）：
     {
       type:'cross_tab_ref', projectToHostKey:true,
       source: kSrc.componentId, sourceLabel: kSrc.componentName, target:'',
       agg: INNER_TO_AGG[name],                       // KSUM→SUM, KAVG→AVG, ...
       match: buildMatch(kSrc.rowKeyFields, selfRowKeyFields),  // 被聚合页签 ⋈ 宿主结果键 = 分组键
       targetExpr: innerTargetExpr,
     }
  5. 该嵌套 token 视作外层表达式里的"一个值项"（相邻值项缺运算符校验照常,:266-272）
  6. 被 KSUM 包裹的页签**不进外层 sources 链**（它已塌缩成宿主标量,对外层是常量）
```

#### 【v3-I2】同页签"既被 KSUM 包裹又被裸引用"冲突校验

外层 FN body 解析完后追加一遍冲突校验：收集**被 KSUM 包裹的页签集 `K`**（各 KSUM token 的 source）与**外层裸引用的细页签集 `B`**（外层 targetExpr 里 `field.source`）。若 `K ∩ B ≠ ∅`（某页签既在 KSUM 内又被外层裸引用）→ 抛错：
> "页签 X 已被 KSUM 聚合，不能在同一 SUM 内再被裸引用（语义二义：塌缩标量 vs join 行集）；请二选一"。

反之同理（先裸引用再 KSUM 同一页签）。理由：同一外层 FN 内对同一页签同时按"宿主键塌缩标量"和"驱动 join 行集"两种粒度引用，求值语义二义。

#### 【v3-M】顶层裸 KSUM 拒绝

`expressionToTokens` 顶层（非 FN body 上下文）遇 `raw.kind==='func' && name ∈ INNER_FNS` → 抛错：
> "KSUM/KAVG/KMAX/KMIN/KCOUNT 本期只能写在外层 SUM/AVG/MAX/MIN/COUNT 内，不支持顶层直接使用"。

实现要点：仅在 FN body 解析上下文（§3.3 折叠算法）里接受 INNER_FNS；主 `expressionToTokens` 顶层 raw-token 扫描遇 INNER_FNS 一律拒。**这样顶层 token 收集无需识别 KSUM 折叠**（改动面缩小，且规避与 `:244` 单列 shortcut 路径冲突）。列为 fast-follow（§10.1-M）。

#### 优先级与 match

- **优先级落地**：嵌套 token 在外层 targetExpr 中作为一个值项 → 外层逐驱动行求值时，该值项先被求值器塌缩成标量再参与外层算术（§4.3）= "先内层塌缩，再外层聚合"。
- **`KSUM` 的 match 按"宿主结果行键"**：`buildMatch(kSrc.rowKeyFields, selfRowKeyFields)` 取被聚合页签行键 ∩ 宿主行键 = 宿主结果键（宿主 rowKeyFields 是被聚合页签行键的子集，§5 用户确认）→ 即"按宿主键分组"的分组键。**外购件行键 = 料件×采购行，宿主 = 料件，match = {料件}** → 按料件分组塌缩 ✅。

### 3.4 【v1 保留 + v2 微调】成链校验 / match 非空校验

- 外层裸引用的多 source 成链校验同 v1 §3.2（不可比 → 抛错 + 提示 KSUM）。
- KSUM 内层 match 非空校验：`buildMatch(kSrc.rowKeyFields, selfRowKeyFields)` 为空（被聚合页签与宿主无公共行键）→ 抛 `"KSUM() 引用的页签与宿主无公共行键,无法按宿主键分组"`。

### 3.5 【v2 扩展】回显 `tokensToDrawerExpression`（`:480` 起，cross_tab_ref 分支）

- 外层 cross_tab_ref → `SUM(<targetExpr 回显>)`（批4 已有；agg 字面回显）。
- **targetExpr 内遇 `type==='cross_tab_ref' && projectToHostKey` 子 token** → 递归回显为 `K<AGG>(<inner targetExpr 回显>)`：`agg='SUM'+projectToHostKey` → `KSUM(...)`，`AVG`→`KAVG` 等（反查映射）。
- `field` token 回显按 `te.source ?? token.source` 反查页签名（v1 §3.5 保留），KSUM 内 field 反查 KSUM token 的 source。
- **幂等**：`KSUM([外购件.单价]*[外购件.数量])` → token → drawer string round-trip 两次稳定（新增单测）。

---

## 4. 求值引擎设计（前后端对称，🔒 基线敏感）

> 🔒 触三大核心基线（求值器）。纪律：**N=1 无嵌套退化路径 = 既有逻辑逐字不变**；多 source（v1）+ KSUM（v2）是**纯新增分支**，论证见 §4.5。

### 4.1 【v2 核心】两层都用同一"行集聚合"原语，只是分组键不同

抽出统一原语 `aggregateRows(rows, match, hostRow, innerTargetExpr, agg, evalCtx) → 标量`：对 `rows` 按 `match ⋈ hostRow` 过滤命中行 → 逐行算 `innerTargetExpr` → 按 `agg` 聚合（SUM/AVG/MAX/MIN/COUNT）。**外层与内层 KSUM 都调它，区别仅在"分组键 / hostRow"**：

| | 分组（hostRow / 命中过滤键） | rows 来源 | agg |
|---|---|---|---|
| **外层 SUM/AVG/…**（v1 单/多 source） | 宿主当前行 → 驱动 join 行集（最细 source 命中行，更粗广播） | 驱动 source 行 | 外层 agg |
| **内层 KSUM/KAVG/…**（v2） | 宿主当前行 → 被聚合页签命中行集（按宿主结果键分组） | KSUM token 的 source 行 | KSUM 映射后 agg |

> 两者结构同构（都是"按某键过滤命中行 → 逐行算表达式 → 聚合"），KSUM 的"分组键"恰是"宿主当前行 ⋈ 被聚合页签 match"——与外层"宿主当前行 ⋈ 驱动 match"同一形态。**这就是为何 KSUM token 复用 cross_tab_ref schema、求值复用同一原语。**

> **【v3 Minor ① 抽取纪律】**：把单 source 路径收敛进 `aggregateRows` 时，**必须原样保留 NONE 分支的 `hits>1 → ERR` crossTabError 旁路**（前端 `formulaEngine.ts:285` / 后端 `FormulaCalculator.java:230`）——`aggregateRows` 对 `agg='NONE'` 命中 >1 行仍返 ERR 哨兵（非静默取首值），由调用方写 `outDiag.crossTabError`。重构后跑既有 `cross-tab-cases.json` NONE-多命中用例回归证明零行为变化（列入实施 checklist）。

### 4.2 【v2 核心】KSUM 在外层逐行求值中的塌缩时机

外层 cross_tab_ref 逐驱动行算 targetExpr 时（前端 `evalRow` `:272`，后端 `targetRowValue` `:252`），targetExpr 内的嵌套 KSUM 子 token **在每个驱动行求值时**被递归求值：

```
外层 evalRow(driverRow):                         // 对每个外层驱动行
  对 targetExpr 内每个 token:
    if token.type==='cross_tab_ref' && token.projectToHostKey:   // ★ v2 嵌套 KSUM
       标量 = aggregateRows(
                rows   = crossTabRows[token.source],
                match  = token.match,
                hostRow= 宿主当前行(currentRow/currentRowRaw),   // ★ 按宿主键,非驱动行!
                innerTargetExpr = token.targetExpr,
                agg    = token.agg)
       该 token 求值 = 标量（对每个外层驱动行都相同 → 等价"按宿主键的常量广播"）
    else: 既有逐 token 求值（field 按 source 取驱动行/广播值, b_field 取宿主, ...）
```

- **关键**：KSUM 的 `hostRow` 是**宿主当前行**（不是外层驱动行）→ 对同一宿主下所有外层驱动行，KSUM 塌缩出的标量**恒定相同** → 等价"宿主粒度常量广播进每个驱动行"。料8 → Kp 在 Ag 行、Ni 行都等于同一个 Σ_采购行(单价×数量)。
- **优先级 = 先内层塌缩再外层聚合**：自然落地——KSUM 子 token 在外层 evalRow 内**先**被求值成标量，**再**参与外层逐行算术，外层 SUM **最后**对各驱动行结果聚合。

#### 【v3 决策 K】KSUM 空集（0 命中）按 agg 分流 + 下游 null 处理

v2 默认"0 命中一律返 0"。**v3 收紧为按 agg 分流**：

| KSUM agg | 0 命中返回 | 理由 |
|---|---|---|
| **KSUM / KCOUNT** | **0** | "空集求和 = 0"、"空集计数 = 0" 数学良定义；料8 终态依赖此 |
| **KAVG / KMAX / KMIN** | **null** | 空集"平均/最大/最小"无定义；返 0 会污染外层算术（如 `+ KMIN(...)` 把不存在当 0） |

##### 【v3.1 I-1 纠正】空集语义的真实落点 = "空集提前返回分支"（不是 `.average().orElse(0)`）

> **第 2 轮评审实地核对纠正 v3 措辞**：v3 此前写"禁 `.average().orElse(0)` 静默吞 0 / 改 `:243`"是**误判**。实地核对（2026-06-12）后端 `FormulaCalculator.evalCrossTab`：空集**不会**走到 `:243` 的 `.average().orElse(0)`——因为在它之前的 **`:233` `if (hits.isEmpty()) return BigDecimal.ZERO;`** 已经对空集提前返回（`hits` 非空时才进 `nums` 构造与 `:243` 的 stream，`orElse(0)` 是 stream 永不为空时的死分支）。前端同理：`formulaEngine.ts:287` 的 **`else if (hits.length === 0) out = 0;`** 才是真正"空集吞 0"的提前返回点（`:294` 的 `/ arr.length` 同样在 hits 非空时才执行）。

**真实空集提前返回点（已实地核对，行号按 master `beb1d00`）**：

| 端 | 文件:行 | 现状代码 | 空集语义改法 |
|---|---|---|---|
| 后端（SUM/AVG/MAX/MIN 公共路径） | `FormulaCalculator.java:233` | `if (hits.isEmpty()) return BigDecimal.ZERO;` | 改为：`projectToHostKey && agg∈{AVG,MAX,MIN} && hits.isEmpty()` → 返 **null**（Java `Double null` / 哨兵）；否则（KSUM=SUM / KCOUNT / 非 KSUM）仍返 `ZERO` |
| 后端（NONE 分支空集，旁路保留） | `FormulaCalculator.java:229` | `if (hits.isEmpty()) return BigDecimal.ZERO;` | **不动**（NONE 非 K-agg，空集仍 0；Minor ① 旁路语义保留） |
| 后端（COUNT 空集） | `FormulaCalculator.java:227` | `if ("COUNT".equals(agg)) return BigDecimal.valueOf(hits.size());` | **不动**（KCOUNT 空集 = size 0 = 0，天然良定义） |
| 前端（SUM/AVG/MAX/MIN 公共路径） | `formulaEngine.ts:287` | `else if (hits.length === 0) out = 0;` | 改为：`projectToHostKey && agg∈{AVG,MAX,MIN} && hits.length===0` → 标记 null 哨兵 → crossTabError 旁路；否则仍 `out = 0` |
| 前端（NONE 空集 / COUNT 空集） | `formulaEngine.ts:284` / `:282` | `if (hits.length===0) out=0` / `out = hits.length` | **不动**（非 K-agg / KCOUNT 空集天然 0） |

- **改法一句话**：**在空集提前返回点之前，对 `projectToHostKey && agg∈{AVG,MAX,MIN} && 空集` 返 null**（而非统一 ZERO）；其余形态（KSUM=SUM、KCOUNT、外层非 KSUM 的 SUM/AVG/MAX/MIN）维持既有 ZERO/size 不变。**不涉及 `:243` 的 `.average().orElse(0)`**——那是 hits 非空路径的安全兜底，与空集语义无关，保留原样。
- **下游 null 处理（前后端一致，v3 选定）**：外层逐行算术遇 KSUM 返 null（即 KAVG/KMAX/KMIN 空集）→ **走既有 crossTabError 旁路**：前端把该 token 求值注入非法表达式 `(null.x)` 触发外层 try/catch → 返 0，**同时**写 `outDiag.crossTabError`（文案如 "[外购件] KAVG 命中 0 行，平均值无定义"）→ 渲染层显示 ⚠。后端镜像：`evalCrossTab` 在 `:233` 之前对 KAVG/KMAX/KMIN 空集返 `null`，`appendToken` 的 `cross_tab_ref` case（`:159-167`）见 null → 写非法表达式 / 返 `ERR` → 外层表达式 → 0（对齐前端 error→0）。
- **为何选 crossTabError 旁路而非 null→0 降级**：null→0 会**静默**把"无定义"当 0 参与算术，用户看不出错；crossTabError 旁路保留数值零改（仍 →0）但**显式 ⚠ 提示**，符合 `beb1d00` 既有"细项多命中 ⚠"的一致体验。
- **料8 终态不受影响**：料8 用的是 `KSUM`（=SUM），外购件 0 行 → KSUM 空集 → **0** → Kp=0 → 结果 25.41 ✅。仅当用户改用 KAVG/KMAX/KMIN 且命中 0 行时才进 null→⚠ 旁路。

##### 【v3.1 I-2 补】KAVG/KMAX/KMIN 空集 null 的"污染范围" = 塌掉整个外层 SUM 表达式

> **重要语义口径（与 `beb1d00` 既有"细项多命中 ⚠"一致）**：KAVG/KMAX/KMIN 空集 → null 后，该 null 标量被注入外层逐行表达式（前端 `expr += '(null.x)'`），使**该驱动行的整条外层 targetExpr 求值抛错 → 外层 try/catch 兜成 0**。因此污染**不是只扣掉 KAVG 这一项**，而是**整个外层 SUM 表达式塌成 0 + 写 `crossTabError` ⚠**（与 master `beb1d00` "细项多命中 → 整公式 0 + ⚠" 语义完全一致——错误态是"整表达式 0"而非"扣项"）。
>
> 即：`SUM([元素.x] + KAVG([外购件.y]))`，若某宿主 KAVG 命中 0 行 → 该宿主下**每个元素驱动行的 `([元素.x] + (null.x))` 都抛错 → 各驱动行 0 → 外层 SUM = 0** + `outDiag.crossTabError` 非空 ⚠。**元素项 `[元素.x]` 不被保留**（它和 null 在同一表达式内被一起抛错）。这是设计选择：宁可整列显式标错 ⚠ 让用户修，也不静默给一个"漏算了 KAVG 项"的错值。

### 4.3 前端 `formulaEngine.ts` 改法（`:257-308` cross_tab_ref 分支 + `evalRow` `:272-279`）

- **抽 `aggregateRows` 工具**（把现有 `hits.filter` `:259-268` + `evalRow` `:272-279` + agg switch `:282-298` 收敛成可复用函数；单 source 路径改调它 = 零行为纯重构；**保留 NONE 多命中 `:285` crossTabError 旁路**，见 §4.1 Minor ①）。
- **外层多 source（v1）**：`isMultiSourceChain` → 驱动 = `sources[0]`，更粗 source 按 match 广播，`>1` 命中 → crossTabError（§10-D 报错）。
- **【C1 基准】前端 `evalRow`（`:275-278`）已是对称基准**：递归调 `evaluateExpression` 时透传 `componentSubtotals` / `productAttributes` / `quotationFields` / `pathCache` / `basicDataValues` / `globalVariableDefs` / `currentRow` / `crossTabRows`（第 9 位 `previousRowSubtotal` 形参传 `undefined`——KSUM/外层 inner 白名单已禁 previous_row_subtotal token，故不影响）。**C1 修正方向 = 后端向此基准对齐**（§4.4），前端无需动透传。
- **内层 KSUM（v2）**：`evalRow` `:272` 内构造 `aFieldValues` 后，对 `token.targetExpr` 逐 token 求值时——当前 `evalRow` 直接把整个 `token.targetExpr` 丢给 `evaluateExpression`（`:275`）。**改造**：`evaluateExpression` 的 `cross_tab_ref` case（`:257`）天然会**递归处理**嵌套 KSUM 子 token——因为嵌套 KSUM 也是 `type:'cross_tab_ref'`，会再次进入同一 case；新增 `if (token.projectToHostKey)` 分支：用**宿主当前行 `currentRow`** 作为 hostRow（而非外层 hits 的驱动行）调 `aggregateRows` 塌缩成标量，`expr += 标量`；**KAVG/KMAX/KMIN 空集 → null → crossTabError 旁路 ⚠**（决策 K，§4.2）。
  - **关键实现点**：嵌套 KSUM 求值需要 `crossTabRows`[KSUM.source] + `currentRow`（宿主行）。这两者在 `evalRow` `:275-277` 调 `evaluateExpression` 时**已透传**（`currentRow`、`crossTabRows` 都在参数列表里）→ 嵌套 KSUM 递归进入 `evaluateExpression` 时上下文齐备，**无需新增参数**。
- `outDiag.crossTabError` 文案扩展：KSUM 内层非数值 / KAVG/KMAX/KMIN 空集无定义 / inner 跨页签（理论被序列化拦截）等。

### 4.4 后端 `FormulaCalculator.java` 改法（`evalCrossTab` `:206` / `targetRowValue` `:252-266` / `appendToken` `:159` / `RowContext` `:44-61`）

#### 【v3-C1 必做】`targetRowValue` 的 `sub` 透传补齐，与前端 `evalRow` 对齐（删 v2 "无需改契约" 措辞）

实地核对 `targetRowValue`（`:252-266`）构造的 `sub` RowContext **只透传了 4 个字段**（`:255-262`）：
```java
RowContext sub = new RowContext();
// sub.fieldValues   ← 从 arow 逐项 toNumber 重建
sub.currentRowRaw    = ctx.currentRowRaw;   // :260
sub.basicDataValues  = ctx.basicDataValues; // :261
sub.crossTabRows     = ctx.crossTabRows;    // :262
```
而 `RowContext`（`:44-61`）共 8 个字段——**漏透传** `componentSubtotals`（`:48`）/ `quotationFields`（`:50`）/ `productAttributes`（`:52`）/ `previousRowSubtotal`（`:56`）。

对照前端 `evalRow`（`formulaEngine.ts:275-278`）递归调 `evaluateExpression` 时**透传了** `componentSubtotals` / `productAttributes` / `quotationFields`。→ **前后端求值子上下文不对称**：同一含 `[元素.单价] + 报价字段X` 的外层 targetExpr，前端能取到 `quotationFields[X]`、后端 `sub.quotationFields` 为空 → 取 0 → 前后端对拍数值分叉。

**v3 修正（必做）**：`targetRowValue` 的 `sub` **补齐透传**至与前端对齐：
```java
sub.componentSubtotals = ctx.componentSubtotals;  // ← 新增
sub.quotationFields     = ctx.quotationFields;    // ← 新增
sub.productAttributes   = ctx.productAttributes;  // ← 新增
sub.previousRowSubtotal = ctx.previousRowSubtotal;// ← 新增（前端 evalRow 第9参传 undefined,但后端补齐以保 RowContext 完整透传, inner 白名单已禁 previous_row_subtotal token → 实际不被读, 仅契约对齐）
```
> **spec 措辞更正**：v2 §4.4 末句"已核实 :260,262 透传到位，**无需改透传契约**"是**错误结论**——`:260/:262` 仅保证 currentRowRaw/crossTabRows 在位（KSUM 按宿主键分组所需），但**宿主级上下文标量（componentSubtotals 等）确实漏传**，v3 改为"**需后端补齐 sub 透传至与前端 evalRow 对齐**"。

#### 多 source / KSUM 求值分支（v1/v2 保留）

- **抽 `aggregateRows`**（把 `:216-248` 的 hits 过滤 + agg switch 收敛；单 source 调它；**保留 NONE 多命中 ERR 旁路** `:230`，见 §4.1 Minor ①）。
- **外层多 source（v1）**：`evalCrossTab` 读 `token.path("sources")`，size≥2 → 驱动 + 广播分支；`>1` 命中更粗 source → 返 `ERR`。
- **内层 KSUM（v2）**：`appendToken` 的 `cross_tab_ref` case（`:159-167`）已调 `evalCrossTab`。`targetRowValue`（`:252`）构造 `sub`（**已含 C1 补齐字段**）后调 `evaluateExpression(te, sub)`（`:263`）→ te 内嵌套 KSUM 子 token 再次进 `appendToken` 的 `cross_tab_ref` case → `evalCrossTab`。**新增**：`evalCrossTab` 开头判 `token.path("projectToHostKey").asBoolean(false)`：
  - true → **hostRow 用 `ctx.currentRowRaw`（宿主当前行）**（而非 `sub` 内 A 行）；rows = `ctx.crossTabRows[KSUM.source]`；按 match 分组过滤 → `aggregateRows` 塌缩 → 返标量；**KAVG/KMAX/KMIN 空集 → null → ERR**（决策 K，§4.2；【v3.1 I-1】落点 = `evalCrossTab` 在 `:233` `hits.isEmpty()→ZERO` 之前判 `projectToHostKey && agg∈{AVG,MAX,MIN} && 空集` 返 null，**不动** `:243` `.average().orElse(0)`；抽 `aggregateRows` 时把该空集分流逻辑收进原语，由 `projectToHostKey` 入参驱动）。
  - **hostRow 正确性**：`sub.currentRowRaw = ctx.currentRowRaw`（`:260`）引用同一宿主行对象，嵌套 KSUM 在 `sub` 上下文求值时 `currentRowRaw` 仍是宿主当前行 → 按宿主键分组正确（此项 v2 已核实，保留）。
- **KSUM inner 白名单后端镜像**：`evalCrossTab` 对 `projectToHostKey` 子 token 求值前，其 targetExpr 已在 publish/dry-run 期过 validator 白名单（§6.2）；运行期信任 token（但 `appendToken` 对 component_subtotal/quotation_field 等仍按既有 case 取值，inner 不应出现这些 → 已被 validator 拦）。
- `field` case（`:86-88`）多 source 下需按 `field.source` 取对应 source 行值（v1）；KSUM 内层 field 的值由 `aggregateRows` 逐行构造的 sub.fieldValues 提供（A 行列），source 限定一致。
- `RowKeyCompare.comparable`（后端镜像 isSubset/comparable）用于 validator（§6）。

### 4.5 为何是"新增分支"而非"改既有语义"（基线纪律论证）

- 既有单 source token **不含 `sources`、targetExpr 内无 `projectToHostKey` 子 token** → 三判定全 false → 走**完全未改动的原代码路径**。批4 / `beb1d00` / 06-05 既有 cross-tab-cases.json 全部用例 token 形态不变 → 求值结果逐字不变。
- 多 source（v1）+ KSUM（v2）是**新增条件分支**，仅当 token 显式带 `sources(≥2)` 或 `projectToHostKey` 时进入——该形态此前不可能存在（序列化 `:288` 此前对嵌套 func 直接抛错，库内无此 token）。
- 抽 `aggregateRows` 是**纯重构**（单 source 路径改调它，逻辑等价，由既有夹具回归证明零变化）。
- 结论：退化 = 既有基线；多 source + KSUM = 全新增量。**不改既有 1 路 LEFT JOIN 语义。**

---

## 5. 配色 / 编辑器联动（`classifyRefSegment` / `parseFormulaSegments`）

现状（`formulaSerialize.ts:716-777`，已核实）：`classifyRefSegment` 带 `insideFn`；FN 内细 source 明细 → 蓝（`:772`），FN 外细 source 裸引用 → 红 `needs-agg`（`:765-769`）。

### 5.1 【v2 新增】KSUM 块判色规则

- **合法 KSUM 块（蓝）**：`insideFn===true` 且 KSUM 内每个 `[被聚合页签.列]` 单段判色——被聚合页签与宿主可比（行键 ⊇ 宿主，KSUM 要求宿主键是子集）→ 单段走"明细 → 蓝"（`:772`）。`KSUM` 关键字本身不是 segment（`parseFormulaSegments` 只对 `[...]`/`{...}` 块判色，func 关键字是普通文本）。**逐段配色器无需改即对合法 KSUM 内列判蓝。**
- **inner 跨页签（红）**：KSUM 内出现第二个不同被聚合页签 → 跨段非法。**逐段配色器看不到跨段**（每段单看都可能合法）→ 必须由**序列化校验（§3.3-a）在保存/试算期报错**兜底 + 编辑器 FN 块整体标错（同 v1 §5.2）。
- **inner 引用宿主列（红）**：KSUM 内 `[宿主.列]` → §3.3-b 序列化抛错。逐段层：宿主自身列在 `classifyRefSegment` 判紫（`:748-751`）——但在 KSUM 内紫=非法。**跨段语义（"紫块出现在 KSUM 内"）逐段器不知 insideKsum**→ 需序列化校验兜底。
- **【v3 P2 必做（从 v2"可选"提升）】`insideKsum` 上下文配色**：`parseFormulaSegments` 增 `insideKsum` 上下文标记（类似 `insideFn`），对 KSUM 区间内的**宿主紫块**（`:748-751` 判紫的宿主自身列）/ **第二被聚合页签块** / inner 引用的上下文标量块**直接标红**（`insideKsum-illegal`），并 hover 提示原因（"KSUM 内不能引用宿主列/跨页签/上一行小计"）——避免必须保存才暴露。理由：KSUM inner 白名单（§2.4）较严，纯靠序列化校验则用户每次写错都要等保存/试算才报错，编辑体验差；`insideKsum` 让违规块**即时变红**，与 `insideFn` 红 `needs-agg` 体验一致。**实现：`parseFormulaSegments` 扫到 `K*(` 起、匹配 `)` 止区间内的 segment 打 `insideKsum=true`，`classifyRefSegment` 据此对紫块/第二页签块返红。**

### 5.2 【v1 保留】FN 块级错误提示

输入变更时跑轻量 `expressionToTokens` 试解析，捕获 §3.1（C3 `K SUM` 误拆）/ §3.2（成链不可比）/ §3.3（KSUM inner 白名单违规 / I2 同页签冲突 / M 顶层裸 KSUM / J K 套 K）抛错 → 状态条/FN 块下方红色文案（原样透出抛错 message，已指明原因）。**特别地 C3 `K SUM` 误拆文案在此即时暴露**，无需保存才报错。

---

## 6. 校验联动（前端序列化 + 后端 validator 镜像）

### 6.1 前端
- 成链校验（v1 §3.2）+ KSUM 约束校验（§3.3 a/b/c/d/e + I2 同页签冲突 + M 顶层裸 KSUM）在 `expressionToTokens` 抛错。
- `checkMappable`（`:683`）扩展：cross_tab_ref（含嵌套 KSUM 子 token）的 `match` 非空；`sources` 内每个 match 非空。

### 6.2 后端 `TokenMappabilityValidator`（镜像，**v3 扩为全白名单 + J 双端拒**）
- 拒空 match（既有）+ `sources` 逐项非空 + `RowKeyCompare.comparable` 校验外层链成链。
- **【v3 必做】嵌套 KSUM 子 token 全量校验**（防前端绕过直接 POST 非法 token）：对每个 `projectToHostKey===true` 子 token：
  1. `match` 非空；
  2. targetExpr 内每个 `field.source` 一律 === KSUM.source（**跨页签拒**，镜像 §3.3-a）；
  3. **白名单**（镜像 §2.4 / §3.3-d）：targetExpr 内 token type 只允许 `field`/`operator`/`number`/`bracket_open`/`bracket_close`/`global_variable`；出现 `b_field`(宿主列) / `component_subtotal` / `quotation_field` / `product_attribute` / `previous_row_subtotal`(I1) → 拒；
  4. **【J 双端拒】** targetExpr 内出现任何 `cross_tab_ref`（嵌套 KSUM / K 套 K）→ 拒，消息"不支持 K 套 K 嵌套"；
  5. **【I2】** 同一外层 cross_tab_ref 内：KSUM 包裹页签集 ∩ 外层裸引用页签集 ≠ ∅ → 拒（同页签既塌缩又裸引）。
- **【M】** 顶层（非嵌入 targetExpr）出现 `projectToHostKey===true` 的裸 cross_tab_ref → 拒（顶层裸 KSUM 本期不支持）。
- 模板 publish 期 / dry-run-token 期均过此 validator。违者返结构化错误（含违规 token 类型 + 原因），前端 Drawer 展示。

---

## 7. 协议检查点

### 7.1 AP-44（字段类型联动 17 检查点）—— 是否触发？
- **token schema 变更 ≠ field_type 变更**：不新增 `field_type` 枚举、不改 `VALID_FIELD_TYPES`。`sources` / `projectToHostKey` / 嵌套 cross_tab_ref 是 cross_tab_ref token 内嵌字段。
- **不触发完整 17 点矩阵**，但触及求值传播链子集，必须同步：

  | 传播点 | 改动 |
  |---|---|
  | 前端 `evaluateExpression`（cross_tab_ref 分支 + evalRow + aggregateRows + KSUM 递归） | v1 多 source join + v2 KSUM 塌缩 |
  | 后端 `evalCrossTab` / `targetRowValue` / `appendToken` field+cross_tab_ref case | 镜像 |
  | `computeAllFormulas` 字段值循环 | **无需改**（cross_tab_ref 走同一 evaluateExpression 入口，crossTabRows 装配不变） |
  | `tokensToDrawerExpression` 回显 | field.source + KSUM 递归回显（§3.5） |
  | `classifyRefSegment` / `parseFormulaSegments` | §5（合法块逐段判蓝不改；**insideKsum P2 必做**——KSUM 内违规块即时标红；跨段非法仍由序列化校验兜底） |
  | `lex` func 识别 | §3.1 加 K* func token + C3 `K SUM` 误拆文案 |
  | **后端 `TokenMappabilityValidator`** | §6.2 全白名单 + J 双端拒 + I2 + M |

- **结论**：按"lexer + 序列化器 + 求值器 + 配色器 + validator"五处同步，**不需要跑完整 17 点矩阵**，但**必须跑求值前后端对拍 + E2E**（触三大核心基线求值器）。

### 7.2 AP-50（三视图 single-source）+ 【v3 Minor ⑤】核价单 crossTabRows 装配单独验收
- 报价单 / 核价单 / 详情页（`ReadonlyProductCard`）三处都走同一 `evaluateExpression` + `beb1d00` 引入的 `ComponentCell` 共享 FORMULA 分支 → **求值器逻辑**改动天然三视图一致。
- **【v3 Minor ⑤】不假设三视图 `crossTabRows` 同源**：求值器复用 ≠ `crossTabRows` 装配复用。**核价单（costing 侧）的 `crossTabRows` 是独立装配链路**（costing BOM 树 spine 渲染 + costing 行数据源），与报价单不同源（参 MEMORY `costing-bom-tree-full-spine-render`）。→ KSUM 依赖被聚合页签（如外购件）的行进 `crossTabRows[外购件Id]`，**核价单侧必须单独确认该装配把外购件行喂进了 crossTabRows**，否则核价视图 KSUM 命中 0 行（≠ 报价单）。
- **验收必须三视图都看** KSUM 列渲染正确（AP-50 纪律）；**核价单 KSUM 作为独立验收点**，不靠"报价单过了核价单必过"假设。

### 7.3 `cross-tab-cases.json` 前后端对拍夹具新增用例
前后端逐字同步（`formulaEngine` vitest + 后端 `FormulaCalculatorCrossTabFixtureTest`）。**新增用例**：
1. **【v1】2 source 链 SUM**（驱动细 + 1 粗广播）。
2. **【v1】粗 source 0 命中** → 项 = 0。
3. **【v1/v2-D】裸引用粗 source >1 命中 → ERR**（报错，提示加行键 / 改 KSUM）。
4. **【v1】3 source 链跳层**（料×元素×子级 + 料，跳过料×元素 → 合法，§10-E）。
5. **【v2 KSUM 核心】料8 终态**：`SUM([元素.重量(g)]/1000*[元素.单价]*(1+[元素.损耗率]/100) + KSUM([外购件.单价]*[外购件.数量])) * [组成用量]`，外购件多行（料件×采购行 2 行）→ KSUM 按料件汇总成 Kp，外层 Σ_元素(... + Kp)。
6. **【v2 KSUM 0 命中=0】料8 外购件 0 行 → KSUM 空集 → Kp=0 → 结果 = 25.41**（决策 K：KSUM/KCOUNT 空集 → 0）。
7. **【v2 KSUM 各 agg 有命中】** KSUM/KAVG/KMAX/KMIN/KCOUNT 逐一（外购件 2~3 行验证塌缩值）。
8. **【v3 决策 K + v3.1 I-2 断言口径 — KAVG/KMAX/KMIN 空集 → 整外层表达式塌 0 + ⚠】** 外购件 0 行 + `SUM([元素.x] + KAVG([外购件.y]))`：KAVG 空集 → null → 注入 `(null.x)` → **该宿主下每个元素驱动行整条 targetExpr 抛错 → 各行 0 → 外层 SUM = 0**（**不是只扣 KAVG 项保留 `[元素.x]`**，与 `beb1d00` "细项多命中→整公式 0+⚠" 语义一致）。**前后端一致断言口径 = 值 0（不是元素项和）且 `errors`/`crossTabError` 非空**（夹具断言 `result === 0 && diag.crossTabError != null`）。区别于用例 6（KSUM 空集静默 0、无 error）。
9. **【v2 KSUM 与外层 AVG/MAX 组合】**：`AVG([元素.x] + KSUM(...))`、`MAX(...)` 验证内层先塌缩、外层后聚合的优先级。
10. **【v3 Minor ④ — 同 targetExpr 内 2 个独立 KSUM 引不同页签】**：`SUM([元素.x] + KSUM([外购件.单价]*[外购件.数量]) + KSUM([其他源.费用]))` → 两个 `projectToHostKey` 子 token 各按各自 source 的宿主键塌缩，互不串号（验证 §2.4 白名单"同一 inner 单页签"约束只作用于单个 KSUM 内，多个独立 KSUM 引不同页签合法）。
11. **【v3 C1 对称】** targetExpr 含宿主级上下文 `SUM([元素.x] + [报价字段Y])`（外层 b_field/quotation_field，非 KSUM inner）→ 前后端均取到 quotationFields[Y]（验证 §4.4 C1 `sub` 补齐后前后端数值一致，**修复前后端会分叉**）。
12. **【v2 校验拒绝（前端单测，非对拍）】** KSUM 跨页签 / KSUM 含宿主列 / KSUM 含 previous_row_subtotal/component_subtotal(I1) / K 套 K(J) / 同页签既 KSUM 又裸引(I2) / 顶层裸 KSUM(M) → 序列化抛错，消息含原因。
13. **【v3 后端绕过拒绝（后端单测）】** 直接构造非法嵌套 token POST（白名单违规 / K 套 K / I2） → `TokenMappabilityValidator` 拒（§6.2，镜像前端）。
14. **【回归】单 source 退化**：批4 4 个 SUMPRODUCT 用例 token 不含 sources/projectToHostKey → 结果逐字不变。
15. **【v3 Minor ① 回归】NONE 多命中 ERR 旁路**：`aggregateRows` 抽取后，agg=NONE + 命中 >1 行 → 仍 ERR/⚠（证明重构零行为变化）。

### 7.4 强制 E2E 双 spec
- 触 `formulaEngine.ts` / `FormulaCalculator.java`（协议级清单）→ 强制 Playwright E2E。
- `quotation-flow.spec.ts`（SIMPLE）+ `composite-product-flow.spec.ts`（COMPOSITE）双 spec 三步走。
- 必须 `'加载中' final count = 0`、全 8 Tab 截图。
- **注意**：E2E 测试数据（MEMORY `cpq-e2e-quotation-flow-test-data`）组合产品/选配数据 2026-06-11 已清理，`composite-product-flow` 选模板步可能卡（非本改动回归）；需主工作区合并后跑。
- **【v3 强制】KSUM E2E 断言依赖 §8.5 T0 seed**：现役数据无外购件多行采购数据 → KSUM 用例落不了地。**T0 seed（外购件料8 ≥2 行 + 元素料8 Ag/Ni 两行）是 E2E 前置任务**，未 seed 即跳过 KSUM 断言 = 自检不完整（详 §8.5）。

---

## 8. 测试策略

### 8.1 单测 — 序列化（`formulaSerialize.test.ts`，TDD 先红后绿）
- **【v1】成链解析 / 拒不可比 / 最细判定 / N=1 不写 sources / 回显幂等 / match 非空**（保留）。
- **【v2】KSUM 折叠**：`SUM([元素.单价] + KSUM([外购件.单价]*[外购件.数量]))` → 外层 cross_tab_ref，targetExpr 内含 `projectToHostKey:true` 嵌套 token，agg=SUM，inner field 带 source=外购件。
- **【v2】K-agg 映射**：KAVG→agg:AVG+proj、KMAX→MAX、KCOUNT→COUNT 各一例。
- **【v2】KSUM 校验拒绝**：跨页签（`KSUM([外购件.x]+[元素.y])`）/ 含宿主列（`KSUM([宿主.x])` 或裸 `KSUM([组成用量])`）/ K 套 K → 抛错，消息含原因。
- **【v2】lexer**：`__lexForTest('KSUM(...)')` → func token name='KSUM'，不被切成 K+SUM。
- **【v2】回显幂等**：KSUM token → drawer string → token round-trip 两次稳定，回显 `KSUM([外购件.单价] * [外购件.数量])`。
- **【v3-C2 单列 KSUM 不走 shortcut】**：`SUM([元素.x] + KSUM([外购件.费用]))`（inner 单列）→ 断言嵌套 token **含 `projectToHostKey:true` + `targetExpr:[{type:'field',value:'费用',source:外购件Id}]`**，**断言不是** `target:'费用'` 的单列 shortcut 形态（防 C2 退化）。
- **【v3-C3 lexer 误拆】**：`__lexForTest('K SUM(...)')` → 抛 "KSUM/…不能拆写,请连写" 专门文案（非通用"无法识别的标识符"）；对照 `__lexForTest('KSUM(...)')` → func name='KSUM' 正常。
- **【v3-I2/M 拒绝】**：`SUM(KSUM([外购件.x]) + [外购件.y])`（同页签既 KSUM 又裸引,I2）/ 顶层 `KSUM([外购件.x]) * [组成用量]`（M）→ 抛错,消息含原因。
- **【v3-I1 拒绝】**：`SUM([元素.x] + KSUM([外购件.单价] + {上一行小计}))` 类含 previous_row_subtotal/component_subtotal → 抛错（白名单）。

### 8.2 单测 — 引擎前后端对拍（cross-tab-cases.json，§7.3 共 15 类）
- 前端 vitest + 后端 `FormulaCalculatorCrossTabFixtureTest` 同夹具锁一致。
- 重点：KSUM 按宿主键塌缩 + **决策 K 空集分流（KSUM/KCOUNT→0 静默；KAVG/KMAX/KMIN→null→⚠）** + 优先级（先内后外）+ **C1 前后端 sub 对称（quotation_field 等取值一致）** + 单 source 退化零变 + Minor ① NONE 多命中 ERR 旁路保留。

### 8.3 E2E（§7.4）
- 双 spec、加载中=0、8 Tab 截图。

### 8.4 真机验收点（交用户）—— 【v2 更新为终态公式】
- 料8 配 `SUM([元素.重量(g)]/1000 * [元素.单价] * (1+[元素.损耗率]/100) + KSUM([外购件.单价] * [外购件.数量])) * [组成用量]`：
  - **能存**（lexer 识别 KSUM、序列化折叠成嵌套 token、不报错）。
  - **能算**：结果 = `(Σ_{元素∈{Ag,Ni}} (元素行表达式 + Kp)) × 组成用量`，其中 `Kp = Σ_采购行(外购件.单价 × 外购件.数量)`。
  - **外购件 0 行 → Kp=0 → 25.41**。
  - **外购件多行（料件×采购行 N>1）** → KSUM 按料件汇总不报错（绕开笛卡尔，验证 KSUM 价值）。
- 三视图（报价单 / 核价单 / 详情）渲染一致；**核价单 KSUM 单独验收（§7.2 Minor ⑤）**。

### 8.5 【v3 Minor M2 — T0 测试数据 seed（E2E 强制前置）】

**背景**：RECORD / MEMORY `cpq-e2e-quotation-flow-test-data` 记 2026-06-11 选配/组合数据已清理。当前 E2E 现役数据（苏州西门子 + 报价模板0608 v1.9 + 10110002）**没有外购件页签的多行采购数据**，KSUM 无可聚合行 → KSUM 用例（料8 终态、各 agg、空集分流）**无法在 E2E 落地**。因此 **T0 seed 是 E2E 的强制前置任务**（实施 Task 列表 T0），不 seed 则 §7.4 双 spec 的 KSUM 断言形同虚设。

**最小 seed 设计**（落成可重放 SQL / fixture，挂在 worktree 合并后、跑 E2E 前执行）：
1. **外购件页签 + 组件**：确保模板含一个"外购件"页签（componentId 稳定），其 `rowKeyFields` = `[料件, 采购行]`（或等价细于宿主的行键），宿主"来料"`rowKeyFields` = `[料件]`（保证宿主键 ⊆ 外购件键 → KSUM 可按料件分组）。
2. **料8 采购数据 ≥2 行**：在外购件页签插 ≥2 行，`料件 = 料8`，各行带 `单价` / `数量`（如行1 单价10×数量2，行2 单价5×数量3 → Kp=20+15=35）→ 使 `KSUM([外购件.单价]*[外购件.数量])` 有多行可聚合，验证"按料件汇总"绕开笛卡尔。
3. **元素页签料8 仍 Ag/Ni 两行**：确认元素页签料8 保留 Ag、Ni 两行（外层 SUM 驱动行 N=2），与 §0 验收口径一致（外层 Σ_{元素∈{Ag,Ni}}(... + Kp)）。
4. **（可选）KAVG/KMAX/KMIN 空集行**：留一个料件外购件 0 行的料项，验证决策 K 空集 → null → ⚠ 旁路（E2E 看 ⚠ 渲染）。
5. **可重放**：seed 写成幂等 SQL（先 delete 该料项相关行再 insert）或 E2E `beforeAll` fixture，**不污染其他 E2E 数据**（仅动料8 + 外购件页签）。

> **验收口径**：seed 后跑 §7.4 双 spec，料8 行的 KSUM 列渲染 = 35（或对应 Kp），外购件 0 行料项的 KAVG 列渲染 ⚠；`'加载中' final count = 0`。

---

## 9. 基线 / PRD 影响

### 9.1 触碰的 🔒 基线文件
- `cpq-frontend/src/utils/formulaEngine.ts`（求值器，三大核心基线·渲染）
- `cpq-backend/.../quotation/service/FormulaCalculator.java`（求值器，镜像）
- `cpq-frontend/src/pages/component/formulaSerialize.ts`（lexer + 序列化器 + 配色器）
- `cpq-frontend/src/pages/component/types.ts`（FormulaToken schema：`sources` / `projectToHostKey`）
- `cpq-backend/.../TokenMappabilityValidator.java`（校验镜像）
- `docs/三大核心模块基线.md`：求值器扩 N 路 join + KSUM 嵌套塌缩属基线敏感，**改前评估 + 走 architect**（本 spec 即架构评审产物）。

### 9.2 文档同步（实施时必做）—— 【v3 确认：同步章节清单不变】

> v3 评审未新增/删减需同步的基线文档，**章节清单与 v2 一致**（仅内容上补 C1/K/白名单等细节）：

- `docs/PRD-v3.md`：连表公式章节补"多 source 链式 SUM + KSUM 嵌套预聚合"能力 + 演进史（第 9 章）。**（清单不变）**
- `docs/配置方法论-合并版.md §2.6`（连表公式 SUMPRODUCT 章节）+ **§11**（字段类型联动协议）：补 KSUM 用法 + 成链约束 + 降维投影语义 + **inner 白名单（§2.4）** + **KSUM 空集分流（决策 K）** + **C3 `K SUM` 不可拆写** + 与 EXCEL 模型 B 差异（含 Minor ③ 降级行为）。**（§2.6 / §11 两节，不变）**
- `docs/RECORD.md`：`[2026-06-12] 多 source 链式 SUM + KSUM 嵌套预聚合 | 涉及文件 | 关键决策（C1 前后端 sub 对称 / KSUM inner 白名单 / 决策 K 空集分流 / J K套K 双端拒 / M 顶层裸 KSUM 不支持）`。
- `docs/反模式.md`：v3 引入的边界（KSUM 空集 KSUM/KCOUNT→0 vs KAVG/KMAX/KMIN→null→⚠ / 前后端 sub 上下文对称 / 核价单 crossTabRows 不同源 / 单列 KSUM 不复用 shortcut）酌情补 AP 条目（建议 KSUM 专项 AP，集中"inner 白名单 + 空集分流 + sub 对称"三坑）。

### 9.3 实施纪律
- 隔离 worktree 分支开发（CLAUDE.md 强制）；基于 master `beb1d00` 起 worktree。
- TDD 先红后绿；前后端对拍夹具锁一致；E2E 双 spec；三视图验收；用户确认后再合并。

---

## 10. 开放决策（v3 收敛后）

> v1 A~H 在 v2 固化；**v3 又把 J / K / M 拍定**（采纳评审）。残留开放项仅剩 I（命名）/ L（分组键）/ N（K* inner 语义对象），且均有强默认。

### 10.1 已固化（v2 + v3 拍定，不再讨论）

| # | 决策点 | 拍定 | 拍定版本 |
|---|---|---|---|
| A | `sources` 链字段命名 | `sources: [{source,sourceLabel,match}]` | v2 |
| B | targetExpr 内 field 的 source 归属 | `field.source` 限定 componentId | v2 |
| C | N=1 是否写 sources | 不写（字节级兼容批4） | v2 |
| **D** | 裸引用更粗 source >1 命中 | **报错 ⚠**（crossTabError 旁路），提示"该页签按宿主键有多行,需加区分行键或改用 KSUM 聚合" | v2 |
| **E** | 链中间层缺失 / 跳层 | **合法**（两两可比即可，不要求连续） | v2 |
| G | 宿主 b_field 进 SUM 内 vs 外 | 都允许（**注**：KSUM **inner** 不允许宿主 b_field，§2.4 白名单；此处指外层 targetExpr） | v2 |
| **H** | 聚合函数覆盖 | **全做**：外层 SUM/AVG/MAX/MIN/COUNT + 内层 KSUM/KAVG/KMAX/KMIN/KCOUNT | v2 |
| **J** | **K 套 K 嵌套** | **本期不支持**，**前端序列化期 + 后端 validator 双端拒**（§3.3-c / §6.2，v3 升级为双端）。fast-follow 再论证 | **v3** |
| **K** | **KSUM 空集（0 命中）返回** | **按 agg 分流**：KSUM/KCOUNT → **0**；KAVG/KMAX/KMIN → **null → 外层 crossTabError 旁路 ⚠**。**【v3.1 I-1 纠正落点】**：改在**空集提前返回分支**（后端 `evalCrossTab` `:233` `hits.isEmpty()→ZERO`、前端 `formulaEngine.ts:287` `hits.length===0→out=0`）之前，对 `projectToHostKey && agg∈{AVG,MAX,MIN} && 空集` 返 null（而非统一 ZERO）；**不涉及** `:243` 的 `.average().orElse(0)`（hits 非空路径死分支，保留）。料8 用 KSUM→0→25.41 不受影响（§4.2） | **v3 / v3.1** |
| **M** | **顶层裸 KSUM**（不在外层 FN 内） | **本期不支持**，序列化期报错；列为 fast-follow（§3.3-M / §1）。理由：缩小改动面 + 规避与单列 shortcut 冲突 | **v3** |
| **C1** | 前后端求值子上下文对称 | 后端 `targetRowValue` 的 `sub` **补齐**透传 componentSubtotals/quotationFields/productAttributes/previousRowSubtotal，与前端 `evalRow` 对齐（§4.4，删 v2 "无需改契约"误判） | **v3** |
| **白名单** | KSUM inner targetExpr token 范围 | 仅 field(限被聚合页签)/operator/number/bracket/global_variable；拒 b_field/component_subtotal/quotation_field/product_attribute/previous_row_subtotal/跨页签 field/嵌套 KSUM（§2.4，前后端双端镜像） | **v3** |
| **I2** | 同页签既 KSUM 又裸引 | 序列化期 + validator 拒（§3.3-I2 / §6.2） | **v3** |

### 10.2 v3 残留（仍可用户拍板，均有强默认，不阻塞转 plan）

| # | 决策点 | 选项 | 本 spec 默认建议 | 影响 |
|---|---|---|---|---|
| **I** | KSUM 最终关键词命名 | (1) `KSUM/KAVG/KMAX/KMIN/KCOUNT`（K=按宿主 Key 分组，推荐）；(2) `HSUM…`（H=Host）；(3) `GSUM…`（G=Group）；(4) `SUM_BY_HOST(...)` 长名 | (1) `K*` | lexer 关键词表、回显、文档、用户书写习惯 |
| **L** | **KSUM 的分组键** | (1) **固定 = 宿主结果行键**（宿主 rowKeyFields ∩ 被聚合页签 rowKeyFields，推荐——语义简单、覆盖用户场景）；(2) 允许公式内显式 `KSUM(... BY [料件])`（更灵活、语法更重） | (1) 固定宿主键 | 语法、token schema（是否加 groupBy 字段）、求值分组 |
| **N** | KAVG/KMAX/KMIN inner targetExpr 语义对象 | (1) 对**塌缩前每行 inner 表达式值**求 AVG/MAX/MIN（推荐，与 SUM 同口径逐行算再聚合，已与决策 K 空集语义协同）；(2) 仅对单列 | (1) 逐行 inner 值 | 求值原语、夹具用例 7/8 |

> **残留项均不阻塞转 plan**：I 命名默认 `K*` 已贯穿全 spec；L 默认固定宿主键、N 默认逐行 inner 值——若用户无异议即按默认实施，仅 I（若改名）需回改 lexer 关键词表。

---

> **下一步**：I/L/N 残留项按默认或用户确认后，转 `superpowers:writing-plans` 拆 Task：
> - **T0（v3 新增）seed 测试数据**：外购件料8 ≥2 行 + 元素料8 Ag/Ni 两行（§8.5，E2E 强制前置）。
> - **T1** token schema + types（sources/projectToHostKey）。
> - **T2** lexer K\* + C3 `K SUM` 误拆文案 + 序列化多 source 成链。
> - **T3** 序列化 KSUM 折叠（**C2 强制 targetExpr+projectToHostKey、不复用单列 shortcut**）+ 约束校验（白名单/I1/I2/J/M）TDD。
> - **T4** 回显幂等（含 KSUM 递归回显）。
> - **T5** 前端引擎 aggregateRows 抽取（保留 NONE 多命中 ERR 旁路）+ N 路 join + KSUM 塌缩 + 决策 K 空集分流。
> - **T6（v3 显式 3 子项）后端引擎镜像**：① **`targetRowValue` 的 `sub` 全字段透传**（C1）；② **`evalCrossTab` KSUM inner 白名单**（validator 镜像 §6.2）；③ **KAVG/KMAX/KMIN 空集 null 语义**（决策 K；【v3.1 I-1】落点 = `evalCrossTab` `:233` `hits.isEmpty()→ZERO` 之前判分流，**不动** `:243` `.average().orElse(0)`）。+ validator（白名单/J/I2/M）。
> - **T7** cross-tab-cases 对拍夹具（15 类，含料8 终态 + KSUM 各 agg + C1 对称 + 决策 K 空集 + Minor ④ 双独立 KSUM）。
> - **T8** 配色/编辑器：**insideKsum P2 必做**（KSUM 内违规块即时标红）+ FN 块级错误（C3/I2/M 文案透出）。
> - **T9** E2E 双 spec（依赖 T0 seed）+ 三视图验收（**核价单 KSUM 单独验收，AP-50 Minor ⑤**）+ Excel 模型 B 降级行为确认（Minor ③）+ 文档同步。
>
> 基于 master `beb1d00` 起隔离 worktree，全程 TDD 先红后绿。
