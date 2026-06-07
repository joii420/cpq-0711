# spineKeys 复合键跨页签过滤 — 设计文档

- **日期**: 2026-06-06
- **状态**: 设计已确认，待实现
- **范围**: 核价模板（COSTING）SQL 视图配置
- **相关基线**: 🔒 `docs/三大核心模块基线.md`（核价单渲染 / 版本显示口径变动）、`docs/反模式.md` AP-37 / AP-51、`docs/Excel模板配置指南.md`、`docs/配置中心架构.md`
- **关联前序**: `2026-06-04-核价BOM递归展开-design.md`、`2026-06-06-核价BOM递归展开-组件级开关-design.md`、`2026-06-04-material_bom_item-版本化-design.md`

---

## 1. 背景与目标

核价模板里一张产品卡片以"生产料号"为根，经 `BomClosureService` 算出整棵 BOM 闭包：
`partSet`（根 + 全部子孙料号，去重）+ `spine`（树骨架，每个 occurrence 一行，含 `hfPartNo / parentNo / bomVersion`）。

**现状能力**：SQL 视图通过 `:hfPartNos`（= `partSet`，单列料号数组）做 `hf_part_no = ANY(:hfPartNos)` 过滤，把结果分配到对应卡片。这是**单列料号**匹配。

**新增目标**：让核价模板里**消费方页签**的 SQL 视图能用 BOM 树的
**`(子件料号, 父件料号, 子件自身当前版本)` 三元组集合**做过滤，即
`WHERE (该视图的三列) ∈ {树上所有节点的三元组}`。
单列料号无法区分"同一料号在不同父件/版本下的多 occurrence"，复合三元组才能精确对号。

---

## 2. 关键设计决策（Q&A 收敛结论）

| # | 决策点 | 结论 | 备注 |
|---|--------|------|------|
| 1 | 匹配语义 | **三列元组匹配（非单列拼接）** | 作者不必自己拼字符串 |
| 2 | 超长 IN 规避 | **数组参数 + `unnest`**，无论几千行只占 3 个绑定参数 | 绕开 SQL 文本膨胀 + PG 65535 参数上限 |
| 3 | 书写体验 | **宏占位符 `:spineKeys(料号列, 父料号列, 版本列)`**，框架展开 | 作者一行；unnest/NULL 细节由框架封装 |
| 4 | 三列取值规则 | **作者按位手写三个列引用/表达式**，框架原样透传 | 不做固定列名约定（拒绝 B/混合） |
| 5 | NULL 命中 | **NULL-safe 全保留**（`IS NOT DISTINCT FROM`） | 根/无版本节点也参与匹配 |
| 6 | "版本"语义 | **子件自身当前生效 BOM 版本（语义 B）** | 非"边版本/父 BOM 版本（语义 A）" |
| 7 | 显示 vs 匹配版本 | **统一用子件自身版本**；边版本弃用 | 改现有树"版本"列显示口径 |
| 8 | 三元组源头 | **当前版从卡片 BOM 闭包 spine 派生**（环境注入，类比 `:hfPartNos`） | 不读源页签持久化行（因切换版本推迟，本版三元组无用户可变因素） |
| 9 | 源页签指定 | 模板配置加"指定源页签"设置项，**本版只存不用**（前向兼容） | 等"切换版本"立项再接实时行 |
| 10 | 交付边界 | **最小版** | 不做实时联动 / 跨页签指纹 / override 表 / 删加行 |

### 被推翻/收敛的中途结论（保留追溯）
- 一度选"取指定树状页签实时行（B）" + "用户可删行/加行/改版本（1,2,3）" + "实时联动重算（A）" + "三类 override（B）"。
- 最终用户澄清：**核价单不允许编辑/新增，只能切换版本**；且**切换版本功能本期推迟**。
- 因此本期源页签无任何用户可变因素 → 三元组 ≡ 闭包 spine → 实时联动 / 跨页签指纹 / 读持久化行 / override 表全部成为"切换版本"的配套基建，**按 YAGNI 全部推迟**。

---

## 3. 宏与 SQL 展开（核心机制）

### 3.1 作者书写
```sql
SELECT ...
FROM v_某视图 t
WHERE :spineKeys(t.子件料号列, t.父料号列, t.版本列)
```

### 3.2 框架展开目标
```sql
WHERE EXISTS (
  SELECT 1
  FROM unnest(:__skP::text[], :__skPP::text[], :__skV::text[]) AS k(p, pp, v)
  WHERE (t.子件料号列) IS NOT DISTINCT FROM k.p
    AND (t.父料号列)   IS NOT DISTINCT FROM k.pp
    AND (t.版本列)     IS NOT DISTINCT FROM k.v
)
```

### 3.3 展开规则
- **位置绑定**：第 1 实参 → `k.p`（= spine `hfPartNo`）；第 2 → `k.pp`（= `parentNo`）；第 3 → `k.v`（= 子件自身版本）。
- **三实参原样透传**为 SQL 片段（外加一层 `()` 防优先级问题），框架不解释其语义，作者负责其在自身查询上下文里有效；可写表达式（`nullif(trim(t.x),'')`、`t.col::text` 等）。
- **NULL-safe**：`IS NOT DISTINCT FROM`，使 `NULL = NULL` 为 true。
- **绑定**：`__skP/__skPP/__skV` 三个 `List<String>`，经 `rewriteNamedParams` 自动 `createArrayOf("text")` 绑成 `text[]`（复用现有 `:hfPartNos` 同一条绑定通路）。
- **去重**：三元组绑定前去重（减小数组；EXISTS 语义不变）。
- **空集**：spine 为空 → unnest 0 行 → EXISTS 恒 false → 匹配 0 行（既不 `IN ()` 语法报错，也不误匹配全表）。
- **多次调用**：同一 `sql_template` 可多处 `:spineKeys(...)`，共享同一组 `__sk*` 参数。

### 3.4 解析鲁棒性（实现注意）
- `:spineKeys` 会被现有 `NAMED_PARAM` 正则 `(?<!:):([A-Za-z_]\w*)` 当普通占位符 → **宏展开必须是 `rewriteNamedParams` 之前的文本预处理**，把整个 `:spineKeys( ... )` 调用（含实参）替换掉。
- 找右括号要**配平括号**（实参里可能含 `nullif(...)`、嵌套函数）。
- 实参里可能含 `::cast`，预处理不要破坏；切分三个实参按**顶层逗号**（括号深度为 0 的逗号）分割，恰好 3 个，否则报配置错。

---

## 4. 版本语义修正（本期必做，独立价值）

### 4.1 现状
`BomClosureService.CLOSURE_SQL`：
```sql
COALESCE(t.edge_version, v.bom_version) AS bom_version
```
- 非根节点 → `t.edge_version` = `child.bom_version` = **父件 BOM 版本（边版本，语义 A）**（注释："用户口径：非根节点显示被父件带入时的边版本"）。
- 根节点 → 回退 LATERAL `v.bom_version`（= 自身当前 BOM 版本）。

### 4.2 改为
```sql
v.bom_version AS bom_version   -- 全节点统一取 LATERAL：子件自身 is_current BOM 版本（语义 B）
```
- LATERAL 子查询已存在（`material_no = t.node_no AND customer_no='_GLOBAL_' AND system_type='PRICING' AND is_current=true LIMIT 1`），只是过去仅根节点用。
- **显示 + spineKeys 匹配统一用此值**；边版本（`edge_version` / `t.edge_version`）彻底弃用（CTE 里可一并精简）。
- 叶子节点（自身无 BOM）→ `v.bom_version` = NULL → 树"版本"列显示空白；匹配时按 NULL-safe 只命中下游"版本列也为 NULL"的行。

### 4.3 影响面（实现前必查）
- ⚠️ 改的是核价单树"版本"列**显示口径**，属「三大核心模块基线·核价单渲染」之一。
- 用 `codegraph_impact` / `codegraph_callers` 查 `BomClosureResult.bomVersion` / `SpineNode.bomVersion` / `__bomVersion` 所有引用方（前端树渲染、`CardSnapshotService.spineRowNode` 注入的 `__bomVersion` 系统列、Excel 树化导出等），逐一确认改后无回归。
- 在 `docs/三大核心模块基线.md` 登记此次显示口径变动。

---

## 5. 数据流（最小版）

```
卡片根料号
  └─ BomClosureService.compute(根料号)
        ├─ partSet           → :hfPartNos（现状）
        └─ spine + 自身版本   → 去重三元组 (hfPartNo, parentNo, bomVersion)
                                  → 注入 RuntimeContext / SqlViewRuntimeContext
                                      → SqlViewExecutor.namedParams.{__skP,__skPP,__skV}
                                          → 任何消费方页签的 :spineKeys 展开后用上
```
- 三元组在**算闭包时一并产出**，整张卡片共享。
- 注入位置：随 `:hfPartNos` 的现有通路（`CardSnapshotService` / `ComponentDriverService.expandForPartSet` → `SqlViewRuntimeContext` / `RuntimeContext`）携带到 `SqlViewExecutor`。
- 不依赖前端渲染，不读源页签持久化行。

---

## 6. 协议传播点（保存期 + 执行期都要识别宏）

| 阶段 | 位置 | 改动 |
|------|------|------|
| 执行期·组件视图 | `SqlViewExecutor.executeViaComponentSqlView` / `executeAllRows` | `rewriteNamedParams` 前插入 spineKeys 宏展开 + 注入三数组 |
| 执行期·模板视图 | `SqlViewExecutor.executeViaTemplateSqlView` | 同上（两条 owner 路径都要） |
| 保存期·组件 dry-run | `ComponentSqlViewService.dryRun` / `extractNamedParams` / `required_variables` 提取 | 先剥离/展开宏，不把 `spineKeys` / `__sk*` 当缺失变量 |
| 保存期·模板 dry-run | `TemplateSqlView` 保存路径同款提取 | 同上 |

> `__sk*` 为框架保留前缀；可在保存校验里禁止作者自定义 `:__sk*` 占位符，避免冲突。

---

## 7. 源页签配置（前向兼容占位）

- 模板配置新增"指定源页签"设置项（落 `template` 或 `template_component`，存被指定组件 id）。
- 本期**只存不用**：三元组实际 = 闭包 spine，与指定页签无关（因当前源页签 ≡ 闭包）。
- 用途：将来"切换版本"立项时，把源切到该页签的实时行 + override，spineKeys 自动跟随。

---

## 8. 本期不做（推迟到"切换版本"立项）

- 切换版本（含子树按所选 BOM 版本逐层重展开，对应 `BomClosureService` P2 `versionOverrides`）。
- 源页签实时联动重算（用户改源 → 消费方页签即时重算）。
- 跨页签展开指纹维度（AP-37：`driverExpansionKey` 含源三元组 hash）。
- 读源页签持久化行 / override 表（删除集 / 版本覆盖表 / 手工新增行）。
- 源页签系统列 `__hfPartNo/__parentNo/__bomVersion` 可编辑、手工加行。
- 注：核价单**不允许删行/加行/编辑**，用户唯一能动的是"切换版本"——而切换版本本身推迟，故上列基建当前无触发源。

---

## 9. 测试

### 9.1 单元测试
- 宏展开文本正确性：单次 / 多次 `:spineKeys` / 实参含嵌套括号 + `::cast` + `nullif`。
- 实参数量校验：非 3 个顶层逗号分隔 → 报配置错。
- 超长（>10 万三元组）→ 仅绑 3 个参数、SQL 文本不膨胀、可执行。
- NULL 命中：父料号 / 版本为 NULL 的节点正确命中下游 NULL 行。
- 去重、空集（0 行而非报错 / 全表）。
- dry-run / required_variables 不把 `spineKeys` / `__sk*` 报成缺失变量。

### 9.2 E2E（CLAUDE.md 强制：改 `BomClosureService` + SQL 视图执行属协议级）
- 核价单消费方页签按 spineKeys 过滤渲染正确（含 DAG 重复子件多 occurrence 对号）。
- 版本列显示改为子件自身版本：**改前 vs 改后**树截图。
- 按 `docs/E2E测试方法.md` 跑 + 附 `'加载中' final count = 0` 证据。

### 9.3 自检
- 后端：`touch` java 重启 → endpoint 200/401（非 500）；若加 Flyway 迁移 → `flyway_schema_history.success = t`。
- 前端（若涉及源页签配置 UI / 版本列显示）：`tsc --noEmit` 0 错误 + 改动 `.tsx` 经 Vite 200。
- 完成宣告含"已自检"声明。

---

## 10. 风险

| 风险 | 缓解 |
|------|------|
| 版本口径改动触碰锁定基线（核价单渲染） | §4.3 走 `codegraph_impact` 全量排查 + 基线文档登记 + architect 评估 |
| 宏预处理与 `::cast` / 嵌套括号解析鲁棒性 | 配平括号找右括号；顶层逗号切实参；充分单测 |
| `:spineKeys` 被 NAMED_PARAM 误当占位符 | 宏展开严格前置于 `rewriteNamedParams`；保留 `__sk*` 前缀 |
| `__bomVersion` 系统列下游消费方（Excel 树化导出等）受版本口径变动影响 | §4.3 impact 排查覆盖到导出链路 |

---

## 11. 实现范围清单（供 plan 阶段拆解）

1. `BomClosureService.CLOSURE_SQL`：`bom_version` 改为 `v.bom_version`（语义 B），精简 `edge_version`。
2. 闭包结果产出去重三元组（可在 `BomClosureResult` 加便捷方法或在调用方算）。
3. 三元组注入通路：`RuntimeContext` / `SqlViewRuntimeContext` 携带 → `SqlViewExecutor.namedParams`。
4. `SqlViewExecutor`：新增 spineKeys 宏展开预处理（前置于 `rewriteNamedParams`），组件 + 模板两条路径。
5. 保存期：`ComponentSqlViewService` / 模板视图 dry-run + `required_variables` 提取先处理宏；保留 `__sk*` 前缀校验。
6. 模板配置"指定源页签"设置项（存储 + 配置 UI，本版只存）。
7. 文档：`docs/三大核心模块基线.md` 登记版本口径变动；`docs/Excel模板配置指南.md` / `docs/配置方法论.md` 增 `:spineKeys` 用法；`docs/RECORD.md` 追加。
8. 单测 + E2E。
