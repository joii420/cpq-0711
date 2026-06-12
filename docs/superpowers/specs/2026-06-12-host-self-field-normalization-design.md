# 宿主页签自引用归一(self → field)+ 紫色区分 设计

- 日期:2026-06-12
- 范围:页签连表公式 —— 序列化器 + 配色分类器 + 矩阵 + 富文本块 + 一次性数据迁移
- 状态:设计已与用户确认,待写 spec 评审 + 用户复核

## 1. 背景与问题

发布「报价模板0608 草稿」时报「页签公式存在循环引用」。根因定位(已查实):组件 **来料** 的两条公式末尾都写了 `[来料.组成用量]` —— 引用**宿主自己**的明细字段。但 `[别名.字段]`(带页签名前缀)会被序列化成 `cross_tab_ref`(跨组件引用 token),`source` 指向来料自身,在组件级依赖图里形成**自环**,被发布前的环检测(`CrossTabComponentOrder.topoOrder`)拦下。

更深的问题(已用代码证实):即使不拦,这个自引用也**算不出正确值**。`cross_tab_ref` 求值时从 `crossTabRows`(其它已算完组件的结果行)按 source 取数(`FormulaCalculator:214`);而组件按拓扑序逐个算,算来料时它自己尚未进 `crossTabRows`(`CardSnapshotService:778` 才放入)→ 取空 → agg=NONE 空命中**返回 0**(`FormulaCalculator:229`)→ 整条公式 ×0=0。

正确表达"本行自己的列"应是 `{type:'field'}` token(裸 `[字段]`),求值时直接读本行 `ctx.fieldValues`(`FormulaCalculator:87`),无跨组件依赖、不成环、算得对。

**目标**:根治"宿主自引用明细字段"这一类问题——无论用户在矩阵点击还是手敲,引用宿主自身明细字段都归一为 `field` token;并用**紫色**在 UI 上把"宿主自身字段"与"跨页签引用"区分开。

非目标(YAGNI):
- `SUM([宿主.列])` 自聚合(对自己整列求和)本期不处理 —— 语义=小计,应走小计机制;仍由环检测拦截。
- 宿主**小计列**自引用(`[宿主.某小计列]`)、`[宿主(总计)]`:本就走 `component_subtotal`(标量、PASS1 先算),不是 cross_tab_ref、不成环 → **不改**,配色保持黄/绿。

## 2. 现状关键事实(已查实)

- `tab-defs` 返回**同目录全部 ACTIVE 组件,含宿主自己**;宿主那条 tabDef **后端已标 `self: true`**(`ComponentTabDefService.java:100`)。前端矩阵当前未使用该标记。
- `expressionToTokens` 已有 `selfComponentId` 参数,且在 `SUM(...)` 内部已做"是不是宿主自己 → b_field"判断;但**顶层 `[别名.字段]` 明细分支未做自判**,一律 makeCrossTabRef。
- `selfComponentId` 在抽屉 save 与 dryRun 两处都已传入(`expressionToTokens(expr, tabDefs, selfRowKeyFields, componentId)`)。
- 序列化器现有判定(顶层 `[别名.字段]`,isAgg=false):字段 ∈ subtotalCols → `component_subtotal`;否则(明细)→ `cross_tab_ref` ← **唯一需要加自判的分支**。

## 3. 配色体系(新增紫色:宿主自身字段)

在既有四色(蓝/黄/绿/红)上新增"宿主自身字段 = 紫":

| 类型 | 判定 | 矩阵 chip | 输入框元素块 |
|---|---|---|---|
| **宿主自身字段(新)** | 裸 `[字段]`,或 `[别名.字段]` 且 tabDef.self=true 且字段 ∈ detailFields(非小计) | 🟣 紫边 | 🟣 紫底块 |
| 跨页签明细 | 别名≠self 的明细字段 | 🔵 蓝 | 🔵 蓝 |
| 小计列 | 字段 ∈ subtotalCols(含宿主自己的小计列) | 🟡 黄 | 🟡 黄 |
| 页签总计 | `[别名(总计)]`(含 `[宿主(总计)]`) | 🟢 绿 | 🟢 绿 |
| 无效 | 解析不到 / 不可比保存必失败 | 🔴 红 | 🔴 红 |

紫色取值(复用矩阵既有宿主主题色 `#722ed1` 系):
- 矩阵 chip:边 `#d3adf7`、字 `#722ed1`、底 `#fff`(实线)。
- 输入框块:底 `#f9f0ff`、边 `#d3adf7`、字 `#722ed1`。

`SegmentColor` 类型新增 `'purple'`。

## 4. Part 1 — 序列化器归一(`formulaSerialize.ts`,核心)

`expressionToTokens` 顶层 `[别名.字段]` 处理中,在**明细分支**(含点、isAgg=false、字段 ∉ tabDef.subtotalCols、即当前调用 makeCrossTabRef 的那条 else)前加自判:

```
const tabDef = findTabByRef(tabDefs, alias);
// …已有 unknown/缺 componentId 校验…
if (!isAgg && (tabDef.subtotalCols ?? []).includes(fieldPart)) {
  → component_subtotal（不变）
} else if (selfComponentId && tabDef.componentId === selfComponentId && !isAgg) {
  // 新增:宿主自身明细字段 → 同行裸字段 token,不成环、读本行
  result.push({ type: 'field', value: fieldPart });
} else {
  → makeCrossTabRef（跨页签引用，不变）
}
```

> 守卫只需 `selfComponentId && tabDef.componentId === selfComponentId && !isAgg`;**无需**再写 `&& !subtotalCols.includes(fieldPart)` —— 小计列已被外层 `if (!isAgg && subtotalCols.includes…)` 先行截走(实现处加一行注释说明即可)。(评审点7)

覆盖范围:
- **点击**(矩阵宿主行插裸 `[字段]`,见 Part 3)→ 走"无点"分支,本就 field;
- **手敲** `[来料.组成用量]`(带前缀)→ 命中上面自判 → 归一为 field;
- **旧坏数据自愈**:重开公式点保存,显示串 `[来料.组成用量]` 经新代码重解析 → field。

求值正确性(评审点5):field token 在 `FormulaCalculator:87` 读 `ctx.fieldValues`。宿主明细若是 **INPUT_NUMBER**(如 组成用量),`collectFieldValues` 阶段已就绪、直接可读;若宿主该字段本身是 **FORMULA** 字段,则字段级 `topoOrder(formulaFields)` 保证被依赖者先算并回填 `fieldValues`,而真实的"组件内公式字段互引环"由**保存期** `ComponentService.validateFormulas`→`cyclicFormulaNodes`(`ComponentService:510-513`)拦截(发布不再重跑,保存时已兜)。故归一 field 不引入新的求值排序风险。

不影响:
- `selfComponentId` 未传时(其它 caller)→ 不自判,沿用旧行为(保守);
- isAgg 的 `[宿主.字段(总计)]`(自聚合)→ 不归一(本期非目标),仍 cross_tab_ref;
- 小计列/总计自引用 → 仍 component_subtotal;
- **EXCEL 组件路径**(评审点遗漏B):save 走 `buildColumn`(字符串扫描、不调 `expressionToTokens`、不传 selfComponentId),Excel 列公式由独立的 `CardFormulaEvaluator` 求值。本期 **EXCEL 路径不处理宿主自引用、沿用原行为**,明确 out of scope。
- **FN 行级路径**(评审点遗漏D):`SUM(...)` 内宿主列已判 `b_field`(读 `currentRowRaw`,SUMPRODUCT 逐 join 行广播宿主本行值),与本次顶层 `field`(读 `fieldValues`)语义不同、各司其职,**本次不动 FN 内逻辑**。

round-trip:`tokensToDrawerExpression` 对 field token 已回显裸 `[字段]`(掉前缀,符合预期)。

## 5. Part 2 — 配色分类器(`classifyRefSegment`)

> **前置步骤(评审点2,必做)**:前端 `TabDef` 接口(`tabJoinFormulaService.ts`)**必须新增 `self?: boolean`**。后端 `ComponentTabDefService:100` 已在宿主那条返 `self:true`,但前端类型未声明 → `tabDef.self` 永远 `undefined`、**紫色分支静默失效且无编译错**。此接口变更是分类器与矩阵紫色生效的硬前提,列为本特性第一步。

新增"宿主自身字段 → 紫",复用 tabDefs 上现成的 `self` 标记(分类器无 selfComponentId 参数,用 `tabDef.self`):

判色表调整(行序即优先级):
1. `[别名(总计)]`(无点+总计)→ tab-total 绿 / invalid 红(不变)
2. 含点 + 字段 ∈ subtotalCols(非 isAgg)→ subtotal 黄(不变,**含宿主自己的小计列**;subtotalCols 判定先于 self 判定,故宿主小计列稳定为黄)
3. **含点 + `tabDef.self===true` + 字段 ∈ detailFields + 非 isAgg → self-field 紫(新增)**
4. **含点 + `tabDef.self===true` + isAgg(`[宿主.字段(总计)]` 自聚合)→ invalid 红(评审点6)** —— 本期不归一、保存仍 cross_tab_ref 会被环检测拦,故显红与"保存会失败"一致,不误导用户
5. 含点 + 字段 ∈ detailFields + 别名≠self → detail:enforceMappable 下 match 空判红,否则蓝(不变)
6. 含点 + 字段查不到 → invalid 红(不变)
7. 无点无总计 → self-field **紫**(原 blue 改 purple)

> 降级(评审点遗漏C):tabDefs 未加载/加载失败为 `[]` 时,宿主明细块判不出 `self` → 落到"含点→detail→蓝"(与现状一致的安全降级),不影响保存(归一在序列化器侧用 selfComponentId,独立于显示)。

## 6. Part 3 — 矩阵(`TabFieldMatrix.tsx`)

宿主那一行(`def.self === true`):
- **明细字段 chip**:① 点击插**裸 `[字段]`**(去掉 `ref.` 前缀);② **紫色边框**(`#d3adf7`/`#722ed1`);③ 该组"明细"小标题旁加"(本页签·同行)"提示。
- **小计列 chip / 页签总计 chip**:保持现状(插 `[ref.字段]` / `[ref(总计)]` → component_subtotal,黄/绿,不成环)。
- 不可比判定对宿主自身行不适用(自己与自己行键相同,恒可比)。

> 实现位置(评审点4):现矩阵明细 chip(`TabFieldMatrix.tsx:142` 起 `def.detailFields.map`)三个分支(不可比/sourceFiner/普通)**统一插 `[${ref}.${f}]`**。本次须在 `detailFields.map` 内**优先判 `def.self === true`**:命中则走"插 `[${f}]` + 紫边"专用分支,**先于**现有 `isComparable`/`sourceFiner` 判断(宿主对自己恒可比,不应落到那些分支)。仅此一行分支改动,兄弟行逻辑不碰。

兄弟行(非 self)一律不变(蓝/黄/绿 + `[ref.字段]` 插入)。

## 7. Part 4 — 富文本块(`FormulaRichInput.tsx`)

`BLOCK_STYLE` 新增 `purple` 项(底 `#f9f0ff`、边 `#d3adf7`、字 `#722ed1`);`SegmentColor` union 加 `'purple'`;渲染时 `s.color==='purple'` 命中紫块。

## 8. Part 5 — 现存坏数据一次性迁移

库里已存在"宿主明细自引用 cross_tab_ref"(至少 来料 两条)。需一次性把它们转成 field,使 草稿 能发布、并清扫全库同类。

**迁移规则(已简化,评审点3)**:token 满足 **`type='cross_tab_ref'` 且 `source = 该组件自身 id` 且 `(agg='NONE' OR agg 缺省/null)`** → 改写为 `{type:'field', value:target, label:target}`。
- **无需**再过滤 `target ∈ detailFields`:self cross_tab_ref 的 target **天然就是明细字段**——小计列/总计自引用走的是 `component_subtotal`(根本不是 cross_tab_ref),所以"source=自身 + cross_tab_ref + agg=NONE"已等价锁定明细自引用。省掉解析 fields JSONB 的复杂度。
- 自聚合(`agg ∈ SUM/AVG/MAX/MIN/COUNT` 的自引用)**不迁移**(本期非目标,继续由环检测提示用户改小计)。

形态与安全步骤:
- 独立 SQL 脚本,合并后**手动执行**(非 Flyway db/migration,避免 checksum 纠葛)。
- SQL 骨架(按 token 重写 jsonb,保序;条件 `source = c.id::text AND agg IN ('NONE') OR agg IS NULL`,在 component 级 `id::text` 比较):
  ```sql
  -- 备份(执行前):
  -- CREATE TABLE _bak_component_formulas_20260612 AS
  --   SELECT id, formulas FROM component
  --   WHERE formulas::text LIKE '%cross_tab_ref%';
  UPDATE component c SET formulas = (
    SELECT jsonb_agg(
      CASE WHEN f ? 'expression' THEN jsonb_set(f, '{expression}', (
        SELECT jsonb_agg(
          CASE WHEN tk->>'type'='cross_tab_ref'
                AND tk->>'source'=c.id::text
                AND (tk->>'agg'='NONE' OR tk->>'agg' IS NULL)
               THEN jsonb_build_object('type','field','value',tk->>'target','label',tk->>'target')
               ELSE tk END ORDER BY tk_ord)
        FROM jsonb_array_elements(f->'expression') WITH ORDINALITY e(tk, tk_ord)))
      ELSE f END ORDER BY f_ord)
    FROM jsonb_array_elements(c.formulas) WITH ORDINALITY ff(f, f_ord))
  WHERE c.formulas::text LIKE '%cross_tab_ref%';
  ```
- 执行后**复查全库 0 自环**:扫所有 component,`cross_tab_ref.source = 本组件 id` 应为 0 条。

## 9. 测试

**测试 fixture 前置(评审点1/7)**:`formulaSerialize.test.ts` 的 `allTabs` fixture 需给宿主那条 tabDef 加 `self: true`(否则分类器紫色用例无法命中)。

**新增 TDD 用例**:
- 序列化:`[来料.组成用量]` + selfComponentId=来料 → `{type:'field',value:'组成用量'}`;selfComponentId=别的组件 → cross_tab_ref(回归保留)。
- 序列化:`[来料.金额小计]`(小计列)+ self → 仍 component_subtotal(不被误转 field)。
- 序列化:`[来料.组成用量(总计)]`(isAgg 自聚合)+ self → 仍 cross_tab_ref(本期不归一)。
- 序列化:不传 selfComponentId → 旧行为(cross_tab_ref)。
- round-trip:field token 回显裸 `[组成用量]`。
- 分类器:宿主明细(tabDef.self)→ purple;兄弟明细 → blue;宿主小计列 → yellow;`[宿主.字段(总计)]` 自聚合 → red;裸 `[字段]` → purple。

**须改期望值的现有用例(评审点1,不是"全绿不动")**:
- `classifyRefSegment('单重', allTabs, self, true)` 现期望 `{kind:'self-field', color:'blue'}` → 改为 `color:'purple'`。
- 排查 `parseFormulaSegments` 里凡断言裸 `[字段]` 块 `color:'blue'` 的用例(如 self-field 相关),同步改 `'purple'`。
- 其余(兄弟跨页签引用、黄/绿/红判色、round-trip)期望值不变。
- 改后 vitest 应全绿(数量随新增用例增加),**但不可声称"现有用例零改动"**。

**自检**:tsc 0;esbuild transform(TabFieldMatrix/FormulaRichInput/TabJoinFormulaDrawer)OK;vitest 全绿;合并后跑 E2E `quotation-flow`(+ composite,若数据可用)兜底。真机:矩阵宿主行紫 chip 插裸字段、手敲 `[宿主.列]` 显示紫、保存后重开为裸 `[列]`、来料发布通过。

## 10. 影响面

- 改:`formulaSerialize.ts`(序列化自判 + 分类器紫 + 类型 `SegmentColor`)、`formulaSerialize.test.ts`、`TabFieldMatrix.tsx`(宿主行紫+裸插)、`FormulaRichInput.tsx`(紫块)、`tabJoinFormulaService.ts`(TabDef 加 `self`)。
- 增:一次性迁移 SQL 脚本。
- 不改:后端求值/环检测逻辑、`expression` 字符串契约、cross_tab_ref/component_subtotal 既有语义。

## 11. 流程纪律

- 隔离 worktree + TDD;序列化器属核心模块(AP-44 类协议)需严谨。
- **每进入下一阶段前必须由用户确认**(用户要求):本 spec → 用户复核 → writing-plans → 用户确认 → worktree+实现 → 用户验收 → 合并。
