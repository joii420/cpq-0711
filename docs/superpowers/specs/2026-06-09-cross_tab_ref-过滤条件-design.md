# cross_tab_ref 过滤条件（filter / SUMIF）设计方案

- 日期：2026-06-09
- 状态：设计（待用户拍板 → 出实施计划）
- 关联：
  - cross_tab_ref token：`docs/superpowers/specs/2026-06-05-跨页签引用公式-design.md`
  - cross_tab_ref 目标公式：`docs/superpowers/specs/2026-06-05-跨页签目标公式-design.md`
  - 公式可视化构建器路线图：`docs/superpowers/specs/2026-06-09-公式可视化构建器-design.md`（本项 = 路线图「P2·条件过滤」的 cross_tab_ref 侧）
  - 协议：`docs/反模式.md` AP-55（cross_tab_ref token 字段扩展 = 本设计命中 AP-55，非 AP-44 全 17 点）

## 1. 背景与需求

cross_tab_ref 现按等值 `match:[{a,b}]` 把源组件 A 的行关联到当前 B 行，对匹配到的 A 行计算 target/targetExpr 并聚合（NONE/SUM/AVG/COUNT/MAX/MIN）。**缺一道"过滤"**：用户无法只对满足某条件的 A 行聚合（如「**只对非银点类来料**求和」）。本设计加 **filter（WHERE）**，实现组件字段侧的 SUMIF/COUNTIF。

直接动机：用户最初需求 `SUM(非银点来料.组成用量 × 元素.含量 × 元素.单价)` 里的「**非银点**」过滤，正落在 cross_tab_ref 侧（Excel 列 SUM_OVER 侧的条件 P1.2 已可用，cross_tab_ref 侧此前无过滤）。

## 2. 现状事实基线（来自端到端测绘）

- **Token**（前端 `types.ts:124-170` `FormulaToken` / `CrossTabRefDrawer.tsx:22-30` `CrossTabToken` / `crossTabText.ts:28-36`）：`{type:'cross_tab_ref', source, sourceLabel, target, targetExpr?, match:[{a,b}], agg}`。后端无专门 class，`FormulaCalculator.evalCrossTab(JsonNode,...)` 按字段名直读。
- **前端求值** `formulaEngine.ts:251-295`：`rows = crossTabRows[source]` → `hits = rows.filter(match)` → per-hit 算 targetExpr/target → 聚合。**filter 插入点 = `hits` 产生后、聚合前。**
- **后端求值** `FormulaCalculator.java:206-244` `evalCrossTab`：同结构（match 循环填 `hits` → targetRowValue → 聚合）。**filter 插入点 = `hits` 填充后、聚合分支前。**
- **双引擎对等**靠**共享夹具** `cross-tab-cases.json`（两份：`cpq-frontend/src/utils/__fixtures__/` + `cpq-backend/src/test/resources/`，现 16 例），前端 `formulaEngine.test.ts` + 后端 `FormulaCalculatorCrossTabFixtureTest.java` 同时消费、断言一致。
- **可复用条件模型** `cardFormula.ts:1-9` `CondRowSpec{left,op,logic,rhs:{type,value}}` + `CondOp('eq'|'ne'|'gt'|'gte'|'lt'|'lte'|'in')`；后端 `CardRef.CondRow`。卡片引擎走 JEXL（`buildDynamicCond`）。**cross_tab_ref filter 不走 JEXL**——在 evalCrossTab 内联轻量谓词，镜像前端，避免 JEXL 依赖、更易锁对等。
- **校验**：组件级 `ComponentService.validateFormulas`（:470-503）；模板级 `TemplateService.validateCrossTabRefs`（:1029，只查 source 存在 + 无环，filter 无需在此校验）。
- **UI** `CrossTabRefDrawer.tsx`：区块顺序 = 源(:268) / 匹配列对(:301) / 要算什么(:357) / 目标列(:380) + 高级原始文本(P1)。

## 3. 方案：token 加 `filter`，match 后聚合前过滤（双引擎镜像）

### 3.1 Token 字段（向后兼容，缺省 = 现状）

`FormulaToken`（cross_tab_ref）新增可选：
```jsonc
"filter": [
  { "field": "类型", "op": "ne", "value": "银点", "logic": "and" }
]
```
- `field`：**A 组件字段名**（中文，做 map key 直取 `arow[field]`，不涉及 SQL → 无 AP-53 别名问题）。
- `op`：复用 `CondOp` 枚举 `eq|ne|gt|gte|lt|lte|in`。
- `value`：**字面量**（P1 只做 literal RHS；`b_col`（A 字段 vs B 字段）推后，见 §6）。
- `logic`：`'and'|'or'`，本行与**下一行**的连接符（末行忽略）。
- `filter` 缺省 / 空数组 → 不过滤，行为与现状逐字节一致（无 token 迁移、无 snapshot 重建）。

> 命名：P1 用轻量结构 `{field,op,value,logic}`（非完整 `CondRowSpec`，因只需 literal RHS）。`CondOp` 类型从 `cardFormula.ts` import 复用。

### 3.2 求值语义（前后端**必须逐字镜像**，夹具锁定）

在 `hits`（match 通过的 A 行）产生后、进入聚合前：
```
hits = hits.filter(arow => passFilter(arow, filter))
```
`passFilter(arow, filter)`：
- filter 空 → true。
- 否则**左到右折叠**（等优先级，无 AND-over-OR 优先级；两引擎实现同一折叠）：
  ```
  result = test(arow, filter[0])
  for i in 1..n-1:
    result = (filter[i-1].logic === 'and') ? (result && test(arow, filter[i]))
                                            : (result || test(arow, filter[i]))
  ```
- `test(arow, cond)`：取 `av = arow[cond.field]`；按 `op` 比较 `av` 与 `cond.value`：
  - 数值比较 iff `av` 与 `value` 都能 parse 成数字（与现有 match 的数字/字符串双模规则一致）；否则字符串 `String(av).trim()` 比较。
  - `eq/ne/gt/gte/lt/lte` 按对应运算。
  - `in`：`value` 按逗号拆成列表，`av` ∈ 列表（trim 后字符串比较）成立。
  - `av` 为空（null/''）：除 `ne`（空 ≠ 非空值 → true）外，比较一律 false（与 match 空键排除同精神；§7 夹具固化此约定）。
- COUNT 语义自然成为 COUNTIF：`COUNT(filter(match(A)))`。

### 3.3 前端实现 `formulaEngine.ts`
cross_tab_ref case 内 `hits` 计算后插入 `if (token.filter?.length) hits = hits.filter(ar => passCrossTabFilter(ar, token.filter))`。`passCrossTabFilter` 为本文件内纯函数（同 §3.2）。

### 3.4 后端实现 `FormulaCalculator.java`
`evalCrossTab` 内 `hits` 填充后插入同逻辑（内联 Java 谓词，读 `token.path("filter")`）。不引入 `CardRef`/JEXL。`passCrossTabFilter(Map<String,Object> arow, JsonNode filter)` 私有方法，逐字镜像前端折叠 + 比较规则。

## 4. 校验（`ComponentService.validateFormulas`）
对每个 cross_tab_ref token 的 `filter` 条目：`field` 必须是 source 组件存在字段名；`op` 在枚举内；`value` 非空（`in` 至少一项）。不合法 → 校验错误（与现有 targetExpr 校验同款）。模板级 `validateCrossTabRefs` 不变（filter 不新增跨组件依赖）。

## 5. UI（CrossTabRefDrawer）
在「匹配列对」(§2 区块) 与「要算什么」之间插入 **「过滤条件（可选）」** 区块：
- 条件行列表，每行：`A 字段 Select`（源组件字段）+ `op Select`（等于/不等/大于/…/包含）+ `值 Input`（字面量）+（多行时）`且/或 Select` + 删除按钮；底部「+ 添加条件」。
- 状态 `filterRows: Array<{field, op, value, logic}>`；`handleConfirm`/`buildTokenLike` 把非空 `filterRows`（field+value 都填的行）写入 token `filter`；空则不写（兼容）。
- 简单/高级：简单模式可只露常用 op（等于/不等/包含），高级露全集；两模式都可加过滤（这是用户核心需求，不藏）。
- **预览**：底部预览串拼一句「且仅当 类型 不等于 银点 …」中文摘要。
- **原始文本双向同步**（P1 已有）：`crossTabText.ts` 的 `serializeCrossTab`/`parseCrossTab` 加 `筛选:<field><op符><value>[ 且/或 …]` 段，保证 filter 也能 round-trip（否则"应用文本"会丢 filter）。解析失败行内报错（沿用 P1）。
- **FormulaZone chip 回显**（`FormulaZone.tsx getTokenLabel`）：cross_tab_ref 分支摘要追加 filter 简述。

## 6. 范围裁剪（P1 不含，留后续）
- **`b_col` RHS**（`A.类型 == B.材质`，A 字段对 B 行字段比较）：需新增 RHS 类型 + B 行取值；P1 只做 literal，覆盖 SUMIF 95% 场景。
- filter 条件 RHS 引用全局变量 / 已算列：推后。
- AND/OR 优先级括号分组：P1 左到右等优先级折叠，不做括号。

## 7. 测试（双引擎对等 + 夹具锁定，AP-55 纪律）
- **共享夹具** `cross-tab-cases.json`（两份同步）新增 ≥5 例：
  - filter `ne` 单条件（非银点）SUM；filter `eq`；filter `gt`/数值；filter `in`；
  - filter 多条件 AND；filter 多条件 OR；
  - **filter 空/absent = 与无 filter 同结果**（向后兼容锁）；
  - filter + targetExpr 组合（逐行先过滤再乘再求和）；
  - COUNT + filter = COUNTIF。
- 前端 `formulaEngine.test.ts` + 后端 `FormulaCalculatorCrossTabFixtureTest.java` 同时跑，断言一致（容差 1e-4）。
- 前端单测：`passCrossTabFilter` 折叠/比较/空值规则；`serializeCrossTab`/`parseCrossTab` filter round-trip。
- **E2E**：`cross-tab-ref.spec.ts` / `cross-tab-builder.spec.ts` 加 filter 配置路径（只读）；`quotation-flow.spec.ts` 回归加载中=0。
- tsc 0 + 改动 tsx Vite 200 + 后端 touch 重启 + endpoint 200/401。

## 8. 向后兼容 / 已知风险
- `filter` absent/空 → 100% 兼容，无迁移、无 snapshot 重建。
- **双引擎对等是头号风险**：折叠规则 + 比较规则（数值/字符串/空值/in）必须逐字一致，夹具是唯一锁。任何一侧改动必须同步两份夹具并双跑。
- 中文 `field` 做 map key 安全（非 SQL，无 AP-53）。
- match 与 filter 语义分离：match=等值 JOIN（右值取 B 行），filter=A 行谓词（右值字面量）；先 match 再 filter，**不可合并进 match**。

## 9. AP-55 传播点（约 8 处，写代码前对照）
| 文件 | 变更 |
|---|---|
| `cpq-frontend/src/pages/component/types.ts` | `FormulaToken` += `filter?` |
| `cpq-frontend/src/utils/formulaEngine.ts` | `hits` 后插 `passCrossTabFilter` |
| `cpq-backend/.../FormulaCalculator.java` | `evalCrossTab` `hits` 后插内联 filter |
| `cpq-frontend/src/pages/component/CrossTabRefDrawer.tsx` | 过滤条件 UI + 写入 token + 预览 |
| `cpq-frontend/src/pages/component/crossTabText.ts` | serialize/parse `筛选:` 段（round-trip） |
| `cpq-frontend/src/components/formula/FormulaZone.tsx` | getTokenLabel filter 摘要 |
| `cpq-backend/.../ComponentService.java` | `validateFormulas` filter 校验 |
| `cross-tab-cases.json`（两份）+ E2E | 夹具用例 + 配置路径测试 |
| `docs/反模式.md` AP-55 | 追加「filter condRows 扩展」小节 |
