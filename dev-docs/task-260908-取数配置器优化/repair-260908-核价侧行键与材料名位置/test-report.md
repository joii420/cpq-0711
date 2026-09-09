# 测试执行报告 · repair-260908（材料名位置 + 核价侧行键）

> 本文是**执行结果**，方案在 `test.md`（两份不要混）。
> 执行人：test-engineer（单片 S，认领全部 8 条 AC）· 执行日期：2026-09-08
> 分支 `feat/task-260908-sqlvb-lookup` · 被测提交 `c8f7944b`（V433）+ `23cfa5c6`（B-1 + 修法A）

**本次无契约变更，无需回写 `main-api.md`。**（与 `api.md` 结论一致：`field-tree` 结构/字段数不变，只有数组顺序与 `roles` 内容变化 —— 已实测确认，见 AC-R1 的「字段集合不变」证据。）

---

## 0. 执行环境（判自己的实例用 `readlink /proc/<pid>/cwd`，不用「端口有响应」）

| 项 | 值 |
|---|---|
| 后端 | `http://localhost:8097` · pid 341363 · `cwd=/…/worktrees/task-260908-sqlvb-lookup/cpq-backend` |
| 前端 | `http://localhost:5199` · pid 116387 · `cwd=/…/worktrees/task-260908-sqlvb-lookup/cpq-frontend`，`VITE_API_TARGET=http://localhost:8097`（已核实代理确实指向 8097，不是 8081） |
| 库 | `10.177.152.12:5432/cpq_db_0724`（共享） |
| 造数前缀 | `T260908R-` · 已 `finally` 按 id 精确回收，无残留 |

🚫 全程未使用 5174 / 8081（留给用户闸门 B 验收）。
✅ 全片零全局计数断言；未改任何全局状态（唯一一次写入是「量具灵敏度实验」，在 `BEGIN … ROLLBACK` 内，见 §2）。

---

## 1. 结论总表（三态）

| AC | 结论 | 一句话依据 |
|---|---|---|
| **AC-R1** 材料名紧邻 `PART_NO` 之后 | ✅ **通过** | 同一份数据下 A/B：改动前 **0/42** 锚点相邻，改动后 **42/42** |
| **AC-R2** 仍在 MAIN 组 + 无独立查名分组 | ✅ **通过** | 改动前后均 **42/42**（`AC-7` 原断言未被破坏） |
| **AC-R3** 锚点无 `PART_NO` 时仍出现 | ⚠️ **无样本 · 未验证** | 全部 42 个锚点**都有** `PART_NO` 列，该分支不可达（🚫 未写 `test.skip`） |
| **AC-R4** 核价七类行键与目标一致 | ✅ **通过** | 12 个断言（7 源 × 方言）逐项 PASS + 两方言一致 10/10 PASS |
| **AC-R5** 报价方言零变化 | ✅ **通过** | 迁移前后 md5 均 `a87fc3dc…`（14 行，量具已证伪） |
| **AC-R6** `PART_NO`/`SORT` 未误伤 | ✅ **通过** | 迁移前后 md5 均 `dcd8959b…`（72 行，量具已证伪） |
| **AC-R7** `component.row_key_fields` 零变化 | ✅ **通过** | 迁移前后 md5 均 `c62355ba…`（49 行，量具已证伪） |
| **AC-R8** 新建组件自动带出恰好 2 列 | ✅ **通过** | E2E 绿；UI 实读 `["production_no","operation_no"]`，集合相等 |

**7 通过 / 0 失败 / 1 未验证（无样本）。**

> 📌 与主线亲验结果**逐条一致**（AC-R1 的 `COST_DETAIL/BOM` 下标 2→3、AC-R2 单 MAIN 组、AC-R4 十二行、AC-R8 集合相等）。无不一致信号。
> 📌 主线提到的 `AC-R10` 不在本片认领的 8 条内；顺带实测到的 `COST_DETAIL/BOM` 改动后字段序为
> `['生产料号','项次','组成料号','材料名','工序编号','使用特性',…]`，与主线所述一致。

---

## 2. 🚨 三条量具纪律的执行情况（`test.md §3`）

### 2.1 「零变化」断言先证明量具会动（服务 AC-R5/R6/R7）

**第一次采样就撞上了 `test.md` 预警的那个坑**：脚本用了不存在的列名 `c.column_name`，
psql 报错、`\o` 产出**两个空文件**，而汇总只打行数才暴露 —— 若直接 md5 对比，会比出「完全一致」并打勾。

```
ERROR:  column c.column_name does not exist
S1_quote_rowkeys   rows=0   md5=d41d8cd98f00b204e9800998ecf8427e   ← 空文件的 md5
```
改用真实列名 `db_column` 后，四份快照分别 14 / 72 / 49 / 28 行，**均非空**。

随后做**证伪实验**：在**一个事务内**扰动四个维度各一行，当场重采，再 `ROLLBACK`。
（选事务而非「改完再改回来」，是为了不让共享库出现哪怕一瞬的错误状态。）

| 量具 | 扰动 | diff 是否有输出 |
|---|---|---|
| S1 报价行键 | `ANNUAL_DISCOUNT.销售料号` 去 `ROW_KEY` | ✅ 4 行 |
| S2 `PART_NO`/`SORT` | `ELEMENT_BOM.材质料号` 去 `PART_NO` | ✅ 2 行 |
| S3 `component.row_key_fields` | 一个组件置 `["__PERTURB__"]` | ✅ 4 行 |
| S4 核价行键 | `AUXILIARY_ENERGY.生产料号` 去 `ROW_KEY` | ✅ 4 行 |

`ROLLBACK` 后四份快照与基线 **IDENTICAL**，共享库零残留。
**四个量具都被证明会动之后**，才开始正式的迁移前后对照。

⏱ **时序运气说明（重要）**：基线采于 **18:31**，V433 由 worktree dev server 于 **18:34:04** 落库（`flyway_schema_history` 实录）。
⇒ 本报告的 before/after 是**真实的迁移前后对**，不是事后重构的。

### 2.2 AC-R1 断言「相邻」不是「存在」

判据写成下标关系：`idx(首个查名字段) === idx(最后一个 PART_NO) + 1`，且多个查名字段必须**连续**。
**反证**：同一份 V433 后数据、只换代码（8081 master 代码 vs 8097 worktree 代码）——
改动前 **42/42 全部 FAIL**，改动后 **42/42 全部 PASS**。若按「材料名存在」写，两侧都会绿。

### 2.3 AC-R8 断言「恰好 2 列」不是「包含」

用**集合相等** + 显式反向（整份已选列不得含 `模具台账/工装编号` / `tooling_no`）。
另加 `T0` 量具自检用例（已通过）：三列集合必须被判**不等于**两列目标。

**追加的量具校准（否则会误判成产品缺陷）**：已选列行上显示的是**视图列名**（`production_no`），不是显示名（`生产料号`）。
第一版按显示名匹配，读到 `<未识别:…>` —— 那看起来像「工序编号没带出来」。改为用服务端 `field-tree` 现算的 `viewColumn` 比对，🚫 不写死。

---

## 3. 逐条证据

### AC-R1 · 材料名紧邻带 `料号` 徽标的那一列之后 —— ✅ 通过

量具：全量枚举 **42 个数据源锚点**（3 方言 × 全部 `availableSources`）的 `field-tree`，比对 `groups[].fields[]` 下标。

```
===== 改动前（master 代码 · 同一份 V433 后数据） =====
AC-R1（材料名紧邻最后一个 PART_NO 之后）: PASS=0  FAIL=42 / 锚点=42
===== 改动后（worktree 代码 · 8097） =====
AC-R1（材料名紧邻最后一个 PART_NO 之后）: PASS=42 FAIL=0  / 锚点=42
```

AC 点名的报价侧锚点（`04375490-…` 来料其他费用，`PART_NO` = 投入料号）：

```
[改动前] lastPART_NO下标=2(投入料号)  材料名下标=[9]
   ['销售料号','项次','投入料号','要素项次','要素名称','值','比例（%）','货币','计价单位','材料名']
[改动后] lastPART_NO下标=2(投入料号)  材料名下标=[3]   ← 2+1，中间不夹任何字段
   ['销售料号','项次','投入料号','材料名','要素项次','要素名称','值','比例（%）','货币','计价单位']
```

核价侧（`COST_BASIC/主件/物料`）：`材料名` 由下标 7 → **1**（`lastPART_NO=0`）。

**多查名字段的连续性**：`QUOTE/BOM/物料BOM` 有两个合成查名字段（`材料名`、`生产料号`），
改动前在 `[12,13]`，改动后在 `[3,4]` —— 整体搬到 `PART_NO`(2) 之后且保持连续。

**`api.md` 的两条不变量也实测确认**（A=改动前代码 / B=改动后代码，同一份数据）：
```
锚点数 A=42 B=42  键一致=True
字段集合 / 分组结构 / 字段总数 —— 差异项 = 0
```
⇒ 只有顺序变，字段不增不减，分组结构不变。

### AC-R2（反向）· 仍在 MAIN 组内 + 无独立查名分组 —— ✅ 通过

```
[改动前] AC-R2: PASS=42 FAIL=0 / 锚点=42
[改动后] AC-R2: PASS=42 FAIL=0 / 锚点=42
```
判据：① 材料名宿主分组 `groupKind === 'MAIN'`；② 整棵树里不存在标题为
`物料（查名）`/`材质（查名）`/`物料（生产料号）` 或 `nodeKey ∈ {MAT_NAME_LK, RECIPE_NAME_LK, MAT_PROD_LK}` 的独立分组。
42 个锚点的 `独立查名分组` 一律为 `[]`。⇒ 父任务 `AC-7` 原断言未被本次改动破坏。

### AC-R3（边界）· 锚点无 `PART_NO` 列 —— ⚠️ 无样本 · 未验证

直查语义图 + 全量枚举双向确认：

```sql
-- 无 PART_NO 列的 ACTIVE 节点，只有 LOOKUP / FUNCTION / 非锚点 SHEET：
COST_BASIC|MAT_NAME_LK(LOOKUP) · QUOTE_MATERIAL_BRIDGE(LOOKUP) · RECIPE_NAME_LK(LOOKUP)
COST_DETAIL|同上三个   QUOTE|CUSTOMER_PART(SHEET,非可选锚点) · FUNC_ELEMENT_PRICE(FUNCTION) · 三个 LOOKUP
```
```
枚举 42 个可选锚点：无 PART_NO 锚点 = 0
```
⇒ **该分支在现网数据下不可达，本条如实记「未验证」**，🚫 未写 `test.skip`（跳过在报告里和通过长得一样）。

📌 **可选的补验路径（需主线裁决，我没有批准权）**：临时给某个锚点的 `PART_NO` 角色做一次「提交→立即还原」，
在窗口内打一次 `field-tree`。但那是**改共享库全局状态**，按派工纪律我停下来报告，未自行执行。

### AC-R4 · 核价七类行键与目标一致 —— ✅ 通过

12 个断言（7 个数据源 × 存在的方言）全部 PASS：

```
[PASS] COST_BASIC  MATERIAL_BOM              期望/实际='生产料号 | 组成料号'
[PASS] COST_DETAIL MATERIAL_BOM              期望/实际='生产料号 | 组成料号'
[PASS] COST_BASIC  FINISHED_RATIO_FEE        期望/实际='生产料号 | 要素名称'
[PASS] COST_DETAIL FINISHED_RATIO_FEE        期望/实际='生产料号 | 要素名称'
[PASS] COST_BASIC  FINISHED_FIXED_FEE        期望/实际='生产料号 | 要素名称'
[PASS] COST_DETAIL FINISHED_FIXED_FEE        期望/实际='生产料号 | 要素名称'
[PASS] COST_BASIC  INCOMING_OTHER_FEE        期望/实际='生产料号 | 来料料号 | 要素名称'
[PASS] COST_DETAIL INCOMING_OTHER_FEE        期望/实际='生产料号 | 来料料号 | 要素名称'
[PASS] COST_BASIC  INCOMING_OTHER_FIXED_FEE  期望/实际='生产料号 | 来料料号 | 要素名称'
[PASS] COST_DETAIL INCOMING_OTHER_FIXED_FEE  期望/实际='生产料号 | 来料料号 | 要素名称'
[PASS] COST_DETAIL PLATING_COST              期望/实际='生产料号'
[PASS] COST_DETAIL TOOLING                   期望/实际='生产料号 | 工序编号'
```

**反向期望③（两方言一致）**：两方言都存在的 10 个节点逐个 PASS（含未被本次改动的
`ELEMENT_BOM` / `INCOMING_PROCESS_FEE` / `MATERIAL` / `OUTSOURCED_PROCESS` / `PROCESS_ASSEMBLY_FEE`）。
📌 `PLATING_COST` / `TOOLING` **只存在于 `COST_DETAIL`**（已查 `semantic_node` 确认），故两方言一致对它们 N/A。

**迁移前后 S4 diff 命中面**：28 行中恰好 **12 行**变化，位置与目标表逐行吻合；
其余 16 行（`AUXILIARY_ENERGY` / `CAPACITY` / `ELEMENT_BOM` / `MATERIAL` 等）逐字节未变。

### AC-R5（反向）· 报价方言行键零变化 —— ✅ 通过

```
S1_quote_rowkeys  迁移前 rows=14 md5=a87fc3dc076459da3a1e9f873223190b
S1_quote_rowkeys  迁移后 rows=14 md5=a87fc3dc076459da3a1e9f873223190b   → IDENTICAL
```
量具灵敏度见 §2.1（扰动一个报价行键 → diff 立即有 4 行输出）。
14 行内容含 `MATERIAL_BOM|销售料号|投入料号`、`PLATING_FEE|销售料号|电镀方案编号|版本编号` 等 ——
**核价侧同名节点被改的那几个，报价侧一行都没动**。

### AC-R6（反向）· `PART_NO` / `SORT` 未误伤 —— ✅ 通过

```
S2_partno_sort  迁移前 rows=72 md5=dcd8959b9615348bd80a0100cf04456b
S2_partno_sort  迁移后 rows=72 md5=dcd8959b9615348bd80a0100cf04456b   → IDENTICAL
```
快照口径是「**所有带 `PART_NO` 或 `SORT` 的行**（含方言/节点/列/两个角色位）」⇒ 新增与删除都会体现，不只是修改。

⚠️ **本条的覆盖边界（如实交代，不夸大）**：
系统里 role 取值共 4 种 —— `ROW_KEY`(92) / `PART_NO`(44) / `SORT`(28) / `PART_NAME`(8)。
`ROW_KEY` 由 S1+S4 全覆盖，`PART_NO`/`SORT` 由 S2 全覆盖。
**`PART_NAME` 的 8 行没有 before 快照**（AC-R6 原文只点名 `PART_NO`/`SORT`）。
旁证：这 8 行全部落在 `MAT_NAME_LK` / `RECIPE_NAME_LK` / `QUOTE_MATERIAL_BRIDGE` 三类 **LOOKUP 节点**上，
不在 V433 的七类 `node_key` 范围内；且改动后 42 个锚点的 `材料名` 合成字段全部照常出现（AC-R1 的 42/42 即其功能旁证）。

### AC-R7（反向）· 存量组件 `row_key_fields` 零变化 —— ✅ 通过

```
S3_component_rkf  迁移前 rows=49 md5=c62355babb00bfaa4b6b41204a9f03ab
S3_component_rkf  迁移后 rows=49 md5=c62355babb00bfaa4b6b41204a9f03ab   → IDENTICAL
```
= 裁决 `A0-3`「只改语义图，存量组件不动」的直接断言。
📌 该 md5 在我 E2E 造数（新建组件）**之后**重采仍相同 ⇒ 本片造的数据也已干净回收。

### AC-R8（序列）· 新建组件 → 明细核价 → 模具工装成本 —— ✅ 通过

E2E：`cpq-frontend/e2e/repair260908-r8-tooling-rowkey.spec.ts`（config：`repair260908-r8.config.ts`）

```
✓ T0 量具自检: 集合相等判据对「多一列」必须硬失败（改动前那一态） (4.6s)
[S2][服务端行键] 费用类/TOOLING/COST_DETAIL = ["生产料号(production_no)","工序编号(operation_no)"]
[R8] 造出组件 COMP-2305 T260908R-R8-1788920956726 (f1de8aa3-…)
[R8] 确认弹层：点「确认切换」        ← 切数据集
[R8] 确认弹层：点「继续切换」        ← 切数据源（两个弹层文案不同！）
[AC-R8] 期望行键视图列=["production_no","operation_no"] 禁止列=tooling_no
[S2][已选列] AC-R8 选完数据源 → 2 行
    字段名="生产料号" 行键=true ✕=禁用 | ⋮⋮ production_no 文本 料号 行键 金额 小计 ✕
    字段名="工序编号" 行键=true ✕=禁用 | ⋮⋮ operation_no 文本 行键 金额 小计 ✕
[AC-R8] UI 自动带出的行键列 = ["production_no","operation_no"]
[R8][cleanup] 删组件 COMP-2305（component_sql_view 0 行）
✓ AC-R8: … 自动带出的行键列恰好 [生产料号, 工序编号] (26.9s)
  2 passed (32.8s)
```

断言构成：① 服务端 `ROW_KEY` 集合相等 `{生产料号,工序编号}` ② UI 已选行键列**集合相等**
`{production_no, operation_no}` ③ 整份已选列**不含** `tooling_no` ④ 前置守卫：数据源文案必须真的是「模具工装成本」。
截图归档：`证据/AC-R8-tooling-rowkey.png`（🚫 未留在 `test-results/`，那里每轮开跑会清空）。

---

## 4. 回归清单（`test.md §4`）

| 回归项 | 结果 |
|---|---|
| 报价侧字段面板材料名位置也跟着变 | ✅ 符合**预期**（12 个 QUOTE 锚点全部由末尾移到 `PART_NO` 之后），不是回归 |
| 材质元素源的价格策略组仍只出现一次 | ✅ `QUOTE/材质元素/物料与元素BOM` 分组结构与改动前逐项相同（差异项 0） |
| 父任务 `AC-7`（不另起查名分组） | ✅ = AC-R2，42/42 |
| 存量组件打开取数配置正常渲染 | ✅ 42 个锚点 `field-tree` 全部 HTTP 200、无空组、字段总数与改动前一致 |

---

## 5. 冷启动（`test.md §5`）—— ⚠️ **部分未验证**，请主线裁决

- ✅ **V433 可应用**：`flyway_schema_history` 实录 `433 | repair260908 costing row key roles | success=t | 2026-09-08 18:34:04`，失败迁移 0。
- ⚠️ **「可重复应用（幂等）」未验证**。原因如实交代：

  我计划在 `BEGIN … ROLLBACK` 内重放一次 V433 来验幂等；执行前先做**只读安全扫描**（grep 该 SQL 有无 `DROP`/`TRUNCATE`/无 `WHERE` 的 `DELETE`）。
  **该 grep 命令被 §3.2 红线 hook 拦下** —— 因为「TRUNCATE」这个词出现在我的**匹配模式串**里（并非要执行它）：
  ```
  🚨 CLAUDE.md §3.2 红线【数据销毁】：TRUNCATE 被 hook 拦截。
  ```
  按派工纪律「**被 hook 拒了不要换写法重试**」，我停在这里上报，未绕路。
  ⇒ 幂等这一项**未验证**，🚫 不写成「应该没问题」。

- 全量重放冷启动：仍卡 `V87`（`BL-0220`，非本任务可解），本次未做。

---

## 6. 失败项归因（`task-docs.md §8` 六类）

**产品缺陷 0 项。** 过程中出现的 5 次红全部归入**前五类**，逐条如下：

| # | 现象 | 归类 | 根因 | 处置 |
|---|---|---|---|---|
| 1 | 四份快照 `rows=0`，md5 全等于空文件 | **harness 不可信** | SQL 用了不存在的列 `c.column_name`（真实为 `db_column`） | 改列名；采样器加「行数>0 + 无错误关键字」硬断言 |
| 2 | `selectSource` 报「数据源下拉找不到」 | **harness 不可信** | 切数据集的确认弹层没关掉，`.ant-modal-wrap` 拦掉所有点击；共享 helper `confirmIfAsked` 的正则 `/^确定$\|^确认$/` **匹配不上「确认切换」** | 本 spec 自带确认器（不改共享 helper） |
| 3 | 同上，第二次 | **harness 不可信** | 数据源下拉 **antd 虚拟滚动**，「模具工装成本」在可视区外、DOM 里不存在 | 自带「搜索框过滤 + 滚动 holder」兜底 |
| 4 | 已选列读到 `<未识别:…>`，看起来像「工序编号没带出来」 | **测试与 AC 不对齐** | 已选列行显示的是**视图列名**（`production_no`），我按**显示名**匹配 | 改用服务端现算的 `viewColumn` 比对 |
| 5 | 两轮 `loginAs` 超时 / 数据源没切过去 | **环境数据不一致 + harness** | ① worktree 后端 8097 中途死掉（我重启后恢复）；② 我的确认器在弹层按钮未及时可见时按了 `Escape` = 点了「取消」，切换被**悄悄撤销** | 重启后端；确认器**永不按 Escape**；补「数据源文案必须真的是模具工装成本」前置守卫 |

📌 第 5 条的后半段是最危险的一次：**切换被撤销，页面看上去一切正常，断言失败信息长得像产品缺陷**。
若当时按「实现确实错了」结案，就会向主线报一个不存在的 bug。

---

## 7. 给主线的三件事

1. 🔧 **共享 helper 有个会误伤别片的缺口**：`task260908-s2.helpers.ts` 的 `confirmIfAsked`
   只匹配 `确定|确认`，而实际弹层是 **「确认切换」（切数据集）/「继续切换」（切数据源）**；
   `selectSource` 也没有虚拟滚动兜底。S2 片若跑到这两条路径会红，**且红在下一步**，很难归因。
   我**没有改共享文件**（那是别片的资产），仅在本 spec 内自带兜底 —— 是否回流给 S2 片，请主线定。
2. ⚠️ **AC-R3 无样本**，若要真验，需要一次「改共享库全局状态」的临时扰动（§3 AC-R3 已写清做法与代价），**等你批准，我没有批准权**。
3. ⚠️ **V433 幂等未验证**，原因是安全扫描被 hook 拦下且我未绕路（§5）。

**一次性库待回收清单：无**（全程未建任何一次性库；仅在共享库造了 1 个带 `T260908R-` 前缀的组件与 1 个同前缀目录，**已回收，`S3` md5 复核相同**）。

---

## 8. 我规避掉的坑（留给下一个人）

1. **空快照假绿**（`test.md §3.1` 点名的那个）—— 真撞上了，靠「先打行数再 md5」拦住。若先写 `diff` 后看行数，这次一定会打绿钩。
2. **恒真判据**：AC-R1 写成「材料名存在」、AC-R8 写成「包含两列」都会两侧全绿；改成**下标相邻**与**集合相等**后，改动前侧立刻全红（0/42、集合不等），证明判据落在会变的维度上。
3. **A/B 混淆变量**：第一次对照拿的是「改动前代码+改动前数据」vs「改动后代码+改动后数据」，两个变量一起变。
   改成**同一份 V433 后数据**、只换代码（8081 vs 8097），才把 AC-R1 的功劳干净地归给 B-1。
4. **量具单位错配**：显示名 vs 视图列名（`生产料号` vs `production_no`）—— 错配的表现是「像产品缺陷」，不是「像脚本坏了」。
5. **Escape 当确认用**：Escape = 取消，会让操作被静默撤销而页面毫无异常。
6. **端口误杀**：起服务前先 `ss -tlnp` + `readlink /proc/<pid>/cwd`，确认 8092/8099 属于别的 worktree，**没有动它们**；只重启了自己那个 8097。
7. **证据落在会被清空的地方**：`test-results/` 每轮开跑清空，截图与日志已复制到 `证据/` 归档。
