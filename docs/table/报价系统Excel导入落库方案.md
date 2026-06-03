# 报价系统基础数据 Excel 导入落库方案

> 版本：V3.2 | 日期：2026-06-03

> **V3.2 变更（2026-06-03）**：补全去重合并的**实现细则（通俗版）**（见 §一 总览末尾【去重合并实现细则】），修正 V3.1 一处关键疏漏——"每料号只调一次 writeMasterDetail" **不足以**保证"唯一当前行"：当某料号上次写的是 `characteristic=NULL`、这次判成 `'ASSEMBLY'`（或反向）时，通用写入器 `VersionedV6Writer` 的 flip 只认相同 groupKey、**翻不到相反 characteristic 的旧当前行** → 双当前行复现（现网已存在该脏数据，如料号 `3120018220`）。新增**第 4 步「先下线相反 characteristic 旧行」**为强制步骤，并补 seq_no 合并口径、存量清洗、编排改造、material_master 副作用归属。**本次仍仅更新方案文档，未改代码。**
>
> **V3.1 变更（2026-06-03）**：新增「物料BOM ⇄ 组成件BOM 同料号去重合并规则」（见 §一 总览末尾的专项规则块），解决"组成件料号被重复填入物料BOM 导致 `material_bom` 出现两条记录"的问题。已经 architect 评审：实现**收敛在导入器层**（新增 `MaterialBomMergeHandler` 内存合并 + 单次写入），**零 DDL、不改通用写入器 `VersionedV6Writer`、不动 `characteristic` 隔离**（因选配运行时 `ConfigureProductService` 依赖 characteristic 双行契约喂 4 个 mirror 视图）；另有一项 PM 待决（导入 / 选配料号命名空间是否共享）。

---

## 目录

- [一、总览](#一总览)
- [二、各 Sheet 落库详细说明](#二各-sheet-落库详细说明)
  - [1. 元素单价](#1-元素单价)
  - [2. 客户料号与宏丰料号的关系](#2-客户料号与宏丰料号的关系)
  - [3. 物料BOM](#3-物料bom)
  - [4. 物料与元素BOM](#4-物料与元素bom)
  - [5. 元素回收折扣](#5-元素回收折扣)
  - [6. 来料固定加工费](#6-来料固定加工费)
  - [7. 来料其他费用](#7-来料其他费用)
  - [8. 来料年降](#8-来料年降)
  - [9. 来料回收折扣](#9-来料回收折扣)
  - [10. 自制加工费](#10-自制加工费)
  - [11. 成品其他费用](#11-成品其他费用)
  - [12. 组成件BOM](#12-组成件bom)
  - [13. 组成件其他费用](#13-组成件其他费用)
  - [14. 组装加工费](#14-组装加工费)
  - [15. 组装加工费年降](#15-组装加工费年降)
  - [16. 电镀方案](#16-电镀方案)
  - [17. 电镀费用](#17-电镀费用)
  - [18. 单重](#18-单重)
  - [19. 年降系数](#19-年降系数)
- [三、通用落库规则](#三通用落库规则)

---

## 一、总览

共 19 个 Sheet，涉及多张目标数据库表。

> **关于客户编号**：凡目标表中存在 `customer_no` 字段的 Sheet，客户编号均**由系统在导入时自动提供**，Excel 中不维护此字段，无需用户填写。

| # | Excel Sheet | 目标数据库表 | price_type（价格类型） | cost_type（费用类型） |
|:-:|-------------|-------------|----------------------|---------------------|
| 1 | 元素单价 | `unit_price` | — | `元素价格` |
| 2 | 客户料号与宏丰料号的关系 | `material_customer_map` | — | — |
| 3 | 物料BOM | `material_bom` + `material_bom_item` + `material_master` | — | — |
| 4 | 物料与元素BOM | `element_bom` + `element_bom_item` | — | — |
| 5 | 元素回收折扣 | `element_bom_item` | — | — |
| 6 | 来料固定加工费 | `unit_price` | `MATERIAL 材料` | `来料加工费` |
| 7 | 来料其他费用 | `unit_price` | `MATERIAL 材料` | `要素名称（动态）` |
| 8 | 来料年降 | `unit_price` | `MATERIAL 材料` | `年降系数` |
| 9 | 来料回收折扣 | `unit_price` | `MATERIAL 材料` | `回收折扣` |
| 10 | 自制加工费 | `unit_price` | `MATERIAL 材料` | `自制加工费` |
| 11 | 成品其他费用 | `unit_price` | `MATERIAL 材料` | `要素名称（动态）` |
| 12 | 组成件BOM | `material_bom`（主表） + `material_bom_item`（子表） | — | — |
| 13 | 组成件其他费用 | `unit_price` | `COMPONENT 组成件` | `要素名称（动态）` |
| 14 | 组装加工费 | `capacity` | — | — |
| 15 | 组装加工费年降 | `unit_price` | `COMPONENT 组成件` | `年降系数` |
| 16 | 电镀方案 | `plating_scheme` | — | — |
| 17 | 电镀费用（加工费） | `unit_price` | `MATERIAL 材料` | `电镀加工费` |
| 17 | 电镀费用（材料费） | `unit_price` | `MATERIAL 材料` | `电镀材料费` |
| 18 | 单重 | `material_master` | — | — |
| 19 | 年降系数 | `annual_discount` | — | — |

---

### 物料BOM ⇄ 组成件BOM 同料号去重合并规则（2026-06-03 新增）

> **背景**：「物料BOM」(§3, `bom_type=MATERIAL`) 与「组成件BOM」(§12, `bom_type=ASSEMBLY`) 都向 `material_bom` + `material_bom_item` 落数据。用户常把本应放在「组成件BOM」的组成件料号**重复**填进「物料BOM」，导致同一料号在 `material_bom` 出现两条记录（一条 `characteristic=NULL`、一条 `characteristic='ASSEMBLY'`）。本规则做去重合并，确保同一料号只有一条当前 BOM 记录。

| 规则项 | 说明 |
|--------|------|
| **唯一当前行** | 同一料号（`system_type+customer_no+material_no`）在 `material_bom` 中**只保留一条当前行**（`is_current=true`）。 |
| **类型判定（组成件优先）** | 本次导入该料号**只要出现在「组成件BOM」** → `bom_type=ASSEMBLY`、`characteristic='ASSEMBLY'`；否则仅在「物料BOM」出现 → `bom_type=MATERIAL`、`characteristic=NULL`。 |
| **子行合并键** | `(system_type, customer_no, material_no, component_no)`，**不含 `characteristic`**；同一 `component_no` 两边都填 → 合并为一行（而非按 characteristic 拆两行）。 |
| **字段冲突取并集 + 组成件优先** | 两边都有但取值冲突的字段（如 `composition_qty`、`issue_unit`）取**组成件BOM** 的值；物料BOM 独有字段（`base_qty`/`scrap_rate`/`defect_rate`/`component_usage_type`）与组成件BOM 独有字段（`operation_no`/`item_seq`）均保留（取并集）。 |
| **版本血缘按料号** | 同一料号由导入器**单次写入**当前 characteristic 的版本（**同一 characteristic 内**的升版/翻转由 writer 负责：合并后子行集与上一当前版本不同 → 主表 `bom_version+1`、旧行 `is_current=false`、新行 `is_current=true`；完全相同则不升版）。⚠️ **但"换了 characteristic"的旧行 writer 翻不到**——它的 flip 只认与本次完全相同的 groupKey（含 characteristic），上次 NULL、这次 ASSEMBLY 时那条 NULL 旧行不在作用域内会留成第二条当前行。故导入器**必须在写入前手动下线"相反 characteristic"的旧当前行**（详见下方【去重合并实现细则】**第 4 步**，这是 V3.1 漏掉、本次补回的强制步骤）。不改通用写入器 `VersionedV6Writer` 的 groupKey。 |
| **文件即权威** | 每次导入文件 = 该料号 BOM 的最新全貌。只填一边重导时，另一边上次的明细**不保留**（旧版本降为 `is_current=false`，当前版本仅含本次内容）；`material_bom_item` 无版本列，旧明细按 `deleteNonCurrent` 物理删除。 |
| **导入器合并时机** | 同一文件中若某料号同时出现在两张表，**必须先按料号汇总两表子行 → 算出 characteristic/bom_type → 单次写入一个版本**（不能 Q03、Q12 各写各的，否则后写者会 flip + 删掉先写者的子行）。 |

> ✅ **实现方式（2026-06-03 经 architect 评审确定）**：本规则**全部在导入器层用内存合并 + 单次写入实现 —— 零 DDL、不改通用写入器、不动任何索引**。
> 1. 新增 `MaterialBomMergeHandler`：解析「物料BOM」+「组成件BOM」两 sheet 后，按 `material_no` 在单一事务内汇总两表子行（合并键去 characteristic，见上表）→ 算出 characteristic / bom_type（组成件优先）→ 每料号**单次** `writeVersionedMasterDetail`；`Q03MaterialBomHandler` / `Q12AssemblyBomHandler` 退化为纯 parser（material_master upsert 副作用保留）。
> 2. `VersionedV6Writer` **不改**（通用工具，选配链路 `ConfigureProductService` 也依赖；改 groupKey 会误翻选配写的行）。
> 3. **不新增** `(system_type,customer_no,material_no) WHERE is_current=true` 唯一约束、**不移除** `uq_material_bom_v6` / `uq_material_bom_item` 里的 `characteristic`。"唯一当前行"由导入器单次写入保证，不靠 DB 兜底。
>
> ⚠️ **为何不能动 characteristic 隔离 / 不能加全局唯一约束**：`material_bom` 有**第二个写入方**——选配运行时 `ConfigureProductService.writeCombomaterialBomV6` 对同一 COMBO 料号**故意写两条主行**：`(bom_type=MATERIAL, characteristic=NULL)` 与 `(bom_type=ASSEMBLY, characteristic='ASSEMBLY')`，分别喂 `composite_child_materials_mirror`（`characteristic IS NULL`）、`zcj_bom`（`='ASSEMBLY'`）、`composite_child_elements_mirror`、`composite_child_processes_mirror` 四个 mirror 视图。一旦加全局唯一约束或去掉 characteristic 隔离 → **选配保存 409 + 这 4 个 Tab 全空（AP-53 类断链）**。故 characteristic 的"同料号双行并存"是选配契约，导入去重只能收敛在导入器内、不得外溢到 DB 约束 / writer / 视图。
>
> ✅ **PM 已澄清（2026-06-03）**：导入侧 `material_no` = Excel 人工填的 ERP 物料主数据料号（如 `9996`、`3120012574`）；选配侧 `material_no`（parentHfPartNo）= 系统 `AutoAllocatePartNoProvider` 自动生成、**固定 `CFG-` 前缀**（如 `CFG-COMBO-000001`）。两个料号集合**靠 `CFG-` 前缀天然不相交**（共享 `customer_no` 维度但料号不撞），故导入去重与选配双行**不会互相覆盖**——`MaterialBomMergeHandler` 导入器层合并可安全实现，**无需新增来源标记列 / system_type 隔离**（两链路均写 `system_type=QUOTE`）。
> 🔒 **加固建议（实现时落地）**：导入校验层增加前置——`material_no` 以 `CFG-` 开头的行**拒绝导入并报错**，把"导入 vs 选配料号隔离"从操作规范升级为系统强制，封死"系统生成料号被手工回填 Excel"这条破坏路径。
>
> **代码落地走上述 architect 方案 + E2E 双 spec（`quotation-flow` + `composite-product-flow`）回归选配 4 个 Tab（`'加载中' final count = 0`）。**

---

### 去重合并实现细则（通俗版，2026-06-03 V3.2 补）

> 给实现同学的"照着做"清单，配合上方规则表一起看。所有动作都在**导入器层 + 单一事务**内完成，不碰 DB 约束 / 通用写入器 / 视图。

#### 一句话原理

把「物料BOM」和「组成件BOM」两张表，**先在内存里按料号拼成一张完整 BOM，再一次性写库**。谁也别单独写，避免后写的把先写的覆盖掉。

#### 第 1 步：两张表一起读，不再各写各的

- 现在 `Q03MaterialBomHandler`（物料BOM）和 `Q12AssemblyBomHandler`（组成件BOM）**各自独立写库、各自一个事务**。改成：两张表都先解析成内存对象，交给新的 `MaterialBomMergeHandler`，在**同一个事务**里统一处理。
- Q03 / Q12 **退化成纯 parser**（只负责把 Excel 行读成内存结构，不再写 `material_bom` / `material_bom_item`）。
- ⚠️ **唯一不能丢的副作用**：Q03 原来会把"投入料号"upsert 进料号表 `material_master`（带 `material_type` 数字，见 `Q03MaterialBomHandler.java:52-55`）。这段保留——搬进合并流程，对**每个物料BOM 的投入料号**都执行。组成件BOM 的料号**不写** `material_master`（维持现状）。

#### 第 2 步：按料号判类型（组成件优先）

对每个料号，看它这次出现在哪：

- 出现在「组成件BOM」（不管有没有同时出现在物料BOM）→ 目标 `bom_type=ASSEMBLY`、`characteristic='ASSEMBLY'`。
- **只**在「物料BOM」出现 → 目标 `bom_type=MATERIAL`、`characteristic=NULL`。

这个结果记为该料号的**「本次目标 characteristic」**，后面第 4、5 步都要用。

#### 第 3 步：拼子行（合并键 = 料号 + 组件，不含 characteristic / 不含 seq_no）

- 合并键用 `(material_no 料号, component_no 组件)`。
- 同一个 component 两张表都填了 → 合并成**一行**：
  - **冲突字段取组成件BOM 的值（组成件优先）**：`composition_qty`（组成数量）、`issue_unit`（单位）、**`seq_no`（项次）**。
  - **物料BOM 独有字段保留**：`base_qty` / `scrap_rate` / `defect_rate` / `component_usage_type`。
  - **组成件BOM 独有字段保留**：`operation_no` / `item_seq`。
- ⚠️ **`seq_no` 必须明确取组成件值**：子表唯一索引 `uq_material_bom_item` 含 `COALESCE(seq_no,0)`（`V219:99-106`）。若两表同一 component 的 seq_no 不同又不统一，写库时会被当成两行 → 去重失败。现网料号 `3120018220` 就有这种不一致（物料BOM 侧 seq=1/2/3/4，组成件BOM 侧 seq=1/2 重排），必须统一到组成件侧。

#### 第 4 步（关键、最容易漏 = 下午没修完的根因）：先把"相反 characteristic"的旧当前行下线

> ⚠️ **保留模型更新（2026-06-04 material_bom_item 版本化后）**：material_bom_item 已加 `bom_version` 多版本保留（见 `docs/superpowers/specs/2026-06-04-material_bom_item-版本化-design.md`）。因此第 4 步"下线反向 characteristic 旧行"由"物理 DELETE 子行"改为"**FLIP `is_current=false` 保留为历史**"（主表 + 子表都翻），与子表多版本保留一致；仍只按单料号、不碰 `CFG-`。

> 光"写一次"**不够**。写之前必须先清掉旧的相反行。

对这个料号，**先把和「本次目标 characteristic」相反的那一侧的旧当前行清掉**：

- 本次目标是 **ASSEMBLY** → 把该料号 `characteristic IS NULL` 的旧行：
  - 主表 `material_bom`：`UPDATE ... SET is_current=false WHERE system_type='QUOTE' AND customer_no=? AND material_no=? AND characteristic IS NULL AND is_current`；
  - 子表 `material_bom_item`：`DELETE ... WHERE 同上 AND characteristic IS NULL`（子表无版本列，直接删）。
- 本次目标是 **NULL（MATERIAL）** → 反过来，把 `characteristic='ASSEMBLY'` 的旧主行下线 + 删 ASSEMBLY 旧子行。

**为什么必须手动做**：通用写入器 `VersionedV6Writer` 翻旧版本（`flip`）时，只翻与本次 groupKey **完全相同**（含 characteristic）的行（见 `VersionedV6Writer.java:198,205,208-209`）。上次写 NULL、这次写 ASSEMBLY，writer 根本"看不见"那条 NULL 旧行 → 留成第二条当前行。**这正是现网 `3120018220` 同时存在 NULL-current + ASSEMBLY-current 两条主行的原因。**

✅ **对选配安全**：下线范围**严格按单个 `material_no`**，而导入料号永远不是 `CFG-` 开头（第 1 步加固已拒绝 `CFG-` 行导入），所以绝不会碰到选配 COMBO 故意写的双行。

#### 第 5 步：再调一次写入器，写本次内容

下线完"相反行"后，按「本次目标 characteristic」调**一次** `writer.writeVersionedMasterDetail(...)` 写主 + 子（groupKey 带本次目标 characteristic）。此时**同 characteristic 内**的版本血缘由 writer 正常负责：子行集没变 → 不升版、复用旧版本号；变了 → `bom_version+1` 并翻旧版。

> 调用顺序小结：**判类型(2) → 拼子行(3) → 下线相反行(4) → writeVersionedMasterDetail(5)**，每料号一轮。

#### 存量脏数据怎么办（二选一）

现网已有并存双行（已知至少料号 `3120018220` / 客户 `8000137`）。第 4 步只在料号**被重导时**触发，重导前旧脏数据仍在。二选一：

- **A) 配一次性清洗迁移（推荐，若不能等重导）**：把"同料号同时存在 NULL-current 和 ASSEMBLY-current"的，按组成件优先**保留 ASSEMBLY、下线 NULL 主行 + 删 NULL 子行**。注意只洗 `system_type='QUOTE'` 且**非 `CFG-`** 料号（别碰选配 COMBO）。
- **B) 不写迁移，靠第 4 步自愈**：等这些料号下次重导时自动清掉。代价：重导前仍是脏的。

#### 编排改造落点

- `QuoteImportService` 现在是"一 sheet 一 handler、各自 `REQUIRES_NEW` 事务"（`QuoteImportService.java:63-66,92-102`）。需调整：把 q03 / q12 从 per-sheet 循环中**摘出**，改为先各自解析两 sheet，再在**一个事务**里调 `MaterialBomMergeHandler.merge(物料BOM行, 组成件BOM行, ctx)`。
- 写入顺序仍排在 BOM 主/子位置（料号表 → 关系表 → **BOM(合并) → ...**），`material_master` upsert 因不依赖 BOM 主表外键，放合并流程内即可。

#### 一个真实例子（料号 `3120018220` / 客户 `8000137`）

- **现状**：物料BOM 写了 5 行（`characteristic=NULL`），组成件BOM 写了 6 行（`ASSEMBLY`），两条主行都 `is_current=true`（脏）。
- **修复后重导**：
  1. 判类型：它在组成件BOM 里 → 目标 = `ASSEMBLY`。
  2. 拼子行：两表按 `component_no` 合并，`seq_no` 取组成件侧。
  3. **先下线 `characteristic=NULL` 的旧主行 + 删 NULL 旧子行**（第 4 步）。
  4. 调 writer 写 `ASSEMBLY` 这一版。
  5. 结果：只剩一条 `ASSEMBLY` 当前行 ✅。

#### 实现后自检 checklist

- [ ] **同文件两表都填同一料号** → DB 只有 1 条 current 主行（`characteristic='ASSEMBLY'`）。
- [ ] **先只填物料BOM 导一次，再两表都填导一次** → 旧 `NULL` 行被下线，只剩 `ASSEMBLY`（验证第 4 步生效）。
- [ ] **反向：先 ASSEMBLY，再只填物料BOM 导一次** → 旧 `ASSEMBLY` 行被下线，只剩 `NULL`。
- [ ] **seq_no 不一致用例**（如 `3120018220`）→ 合并后子行不重复、seq_no = 组成件侧。
- [ ] **存量**：选 A 则跑完迁移后查该料号只剩 1 条 current；选 B 则记录在案。
- [ ] **material_master**：物料BOM 投入料号仍正确 upsert（`material_type` 写数字）。
- [ ] **选配回归**：E2E 双 spec（`quotation-flow` + `composite-product-flow`）4 个 Tab `'加载中' final count = 0`，COMBO 料号双行不受影响。
- [ ] **`CFG-` 守卫**：`material_no` 以 `CFG-` 开头的行被导入校验拒绝并报错。

---

## 二、各 Sheet 落库详细说明

---

### 1. 元素单价

**目标表：** `unit_price`

| 固定写入字段 | 固定值 / 来源 |
|------------|--------------|
| `system_type` | `QUOTE` |
| `customer_no` | **由系统导入时提供** |
| `cost_type` | `元素价格` |

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 客户编号 | `customer_no` | ⚙️ | 系统自动提供，不依赖 Excel 列 |
| 客户名称 | `customer_name` | ✅ | |
| 项次 | `seq_no` | ✅ | 序号 |
| 单个元素名称/所有元素 | `code` | ✅ | 元素代码/材料料号/零件号/耗材料号 |
| 网址 | `source_url` | ✅ | 抓取网址 |
| 网站名称 | `source_name` | ✅ | |
| 取用规则 | `fetch_rule` | ✅ | 取用规则 |
| 升水价/手续费 | `premium_fee` | ✅ | 升水价 / 手续费 |
| 货币 | `currency` | ✅ | 币种 |
| 计价单位 | `unit` | ✅ | 计量单位 |

> 📌 客户编号由系统导入时自动提供；`cost_type=元素价格` 固定写入；`system_type=QUOTE` 固定写入。

---

### 2. 客户料号与宏丰料号的关系

**目标表：** `material_customer_map`

| 固定写入字段 | 固定值 / 来源 |
|------------|--------------|
| `customer_no` | **由系统导入时提供** |

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 客户编号 | `customer_no` | ⚙️ | 系统自动提供，不依赖 Excel 列 |
| 客户料号名称 | `customer_material_name` | ✅ | |
| 客户产品编号 | `customer_product_no` | ✅ | |
| 客户图号 | `customer_drawing_no` | ✅ | |
| 宏丰料号 | `material_no` | ✅ | 料号 |
| 付款方式 | `payment_method` | ✅ | |
| 基础货币 | `base_currency` | ✅ | |
| 报价货币 | `quote_currency` | ✅ | |
| 汇率 | `exchange_rate` | ✅ | 报价汇率快照 |

> 📌 客户编号由系统自动提供，不从 Excel 读取。每行生成一条 `material_customer_map` 记录。

#### → 料号表（material_master）同步

| Excel 列名 | 目标表字段    | 是否导入 | 备注说明             |
| ---------- | ------------- | :------: | -------------------- |
| 投入料号   | `material_no` |    ✅     | 按 upsert 写入料号表 |
|            |               |          |                      |
|            |               |          |                      |

---

### 3. 物料BOM

**目标表：** `material_bom`（主表） + `material_bom_item`（子表） + `material_master`（料号表同步）

| 固定写入字段 | 固定值 / 来源 |
|------------|--------------|
| `system_type` | `QUOTE` |
| `bom_type` | `MATERIAL` |
| `customer_no` | **由系统导入时提供**（主表与子表均写入） |

#### → 物料BOM主表（material_bom）

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 宏丰料号 | `material_no` | ✅ | 主件料号 |
| 客户编号 | `customer_no` | ⚙️ | 系统自动提供 |

#### → 物料BOM子表（material_bom_item）

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 宏丰料号 | `material_no` | ✅ | 主件料号 |
| 客户编号 | `customer_no` | ⚙️ | 系统自动提供 |
| 项次 | `seq_no` | ✅ | |
| 投入料号 | `component_no` | ✅ | 组件料号 |
| 投入料号名称 | — | ❌ | 不导入 |
| 产出料号类型 | `component_usage_type` | ✅ | 1.银点类 / 2.非银点类 / 组成件 / 边角料 |
| 材料毛重 | `composition_qty` | ✅ | 组成用量（毛重） |
| 材料净重 | `base_qty` | ✅ | 主件底数（净重） |
| 重量单位 | `issue_unit` | ✅ | 发料单位 |
| 损耗率（%） | `scrap_rate` | ✅ | |
| 不良率（%） | `defect_rate` | ✅ | |

#### → 料号表（material_master）同步

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 投入料号 | `material_no` | ✅ | 按 upsert 写入料号表 |
| 产出料号类型 | `material_type` | ✅ | 1.银点类 / 2.非银点类 / 组成件 / 边角料  ,只写入数字 |
| 投入料号名称 | material_name | ✅ | 料件名称 |

> 📌 `system_type=QUOTE`，`bom_type=MATERIAL` 固定写入主表；客户编号由系统自动提供。投入料号同步 upsert 至料号表，写入 `material_type` ,只写入数字。
> 🔀 **去重合并**：若同一料号本次也出现在「组成件BOM」(§12)，则按「组成件优先」合并为**一条 `characteristic='ASSEMBLY'` 当前行**，不再单独插入 `characteristic=NULL` 行；子行按 `(system_type,customer_no,material_no,component_no)` 合并，冲突字段取组成件值——详见 §一 总览末尾【物料BOM ⇄ 组成件BOM 同料号去重合并规则】。

---

### 4. 物料与元素BOM

**目标表：** `element_bom`（主表） + `element_bom_item`（子表）

| 固定写入字段 | 固定值 / 来源 |
|------------|--------------|
| `system_type` | `QUOTE` |
| `bom_type` | `MATERIAL`（受 `chk_element_bom_type` 约束限定，bom_type 仅区分 MATERIAL/ASSEMBLY；`element_bom` 表名本身已标识"元素 BOM"维度） |
| `characteristic` | 默认 `2000`；当同一主件料号的元素组成或用量不同时，版本号递增（版本+1） |

> ⚠️ **关键语义修正（2026-05-26）**：本 Sheet 的"投入料号"是 element_bom 主表的**主件料号 (material_no)**，"元素"是 element_bom_item 子表的**组件料号 (component_no)**；"宏丰料号"列**不导入**（它是上游成品料号，不在元素 BOM 维度内）。

#### → 元素BOM主表（element_bom）

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 宏丰料号 | — | ❌ | 不导入（成品料号，不在元素 BOM 维度） |
| 投入料号 | `material_no` | ✅ | **主件料号**（元素 BOM 维度的主体） |
| 投入料号名称 | — | ❌ | 不导入（界面展示用） |

#### → 元素BOM子表（element_bom_item）

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 宏丰料号 | — | ❌ | 不导入 |
| 投入料号 | `material_no` | ✅ | **主件料号**（与主表对齐） |
| 投入料号名称 | — | ❌ | 不导入 |
| 项次 | `seq_no` | ✅ | |
| 元素 | `component_no` | ✅ | **组件料号**（存元素代码 Ag/Ni/Cu/Zn 等） |
| 组成含量（%） | `content` | ✅ | 含量 |
| 损耗率% | `scrap_rate` | ✅ | 损耗率 |
| 毛用量 | `composition_qty` | ✅ | 组成用量 |
| 毛用量单位 | `issue_unit` | ✅ | 发料单位 |
| 净用量 | `base_qty` | ✅ | 主件底数 |
| 净用量单位 | — | ❌ | 不导入（单位与毛用量单位一致） |

> 📌 `characteristic`（特性）默认写入 `2000`；当同一主件料号（即投入料号）出现不同元素组成或用量时，特性版本号自动递增（+1）。
> 📌 Excel 行示例：`宏丰料号=3120012574, 投入料号=9996, 项次=1, 元素=Ag` → 写入 `element_bom_item(material_no='9996', characteristic='2000', seq_no=1, component_no='Ag', content=75)`。

---

### 5. 元素回收折扣

**目标表：** `element_bom_item`（更新已有记录的 `recovery_discount` 字段）

> ⚠️ **关键语义修正（2026-05-26）**：与 §4 字段语义保持一致 — "投入料号"是 `material_no`（主件），"元素"是 `component_no`（组件）；"宏丰料号"不导入。

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 宏丰料号 | — | ❌ | 不导入（不在元素 BOM 维度） |
| 投入料号 | `material_no` | ✅ | **主件料号**（匹配键） |
| 投入料号名称 | — | ❌ | 不导入 |
| 项次 | — | ❌ | 不导入 |
| 元素 | `component_no` | ✅ | **组件料号**（匹配键，元素代码） |
| 回收折扣（%） | `recovery_discount` | ✅ | 元素回收折扣(%) |

> 📌 本 Sheet 为**更新操作**，按 `(material_no=投入料号, component_no=元素)` 匹配 element_bom_item 中**最新 characteristic** 的记录，更新其 `recovery_discount` 字段。
> 📌 Excel 行示例：`宏丰料号=3120012574, 投入料号=9996, 元素=Ag, 回收折扣=70%` → UPDATE element_bom_item SET recovery_discount=70 WHERE material_no='9996' AND component_no='Ag' 。

---

### 6. 来料固定加工费

**目标表：** `unit_price`

| 固定写入字段 | 固定值 / 来源 |
|------------|--------------|
| `system_type` | `QUOTE` |
| `price_type` | `MATERIAL`（材料） |
| `cost_type` | `来料加工费` |
| `customer_no` | **由系统导入时提供** |

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 宏丰料号（成品料号） | `finished_material_no` | ✅ | 成品料号 |
| 项次 | `seq_no` | ✅ | 序号 |
| 投入料号 | `code` | ✅ | 元素代码/材料料号/零件号/耗材料号 |
| 投入料号名称 | — | ❌ | 不导入 |
| 基准值 | `base_value` | ✅ | 基准值（费用类型=加工费时启用） |
| 比例（%） | `cost_ratio` | ✅ | 比例 |
| 货币 | `currency` | ✅ | 币种 |
| 计价单位 | `unit` | ✅ | 计量单位 |
| 是否随材料价格波动 | `is_fluctuate_with_material` | ✅ | 是→1，否→0 |
| 材料结算涨幅比例（%） | `material_increase_ratio` | ✅ | 材料结算涨幅比例（%） |
| 材料固定的涨幅值 | `material_fixed_increase` | ✅ | 材料固定的涨幅值 |
| 货币（涨幅） | — | ❌ | 不导入 |
| 涨幅单位 | — | ❌ | 不导入 |

> 📌 客户编号由系统自动提供；涨幅货币与涨幅单位列不导入。

---

### 7. 来料其他费用

**目标表：** `unit_price`

| 固定写入字段 | 固定值 / 来源 |
|------------|--------------|
| `system_type` | `QUOTE` |
| `price_type` | `MATERIAL`（材料） |
| `cost_type` | 取自 Excel「要素名称」列（动态写入） |
| `customer_no` | **由系统导入时提供** |

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 宏丰料号（成品料号） | `finished_material_no` | ✅ | 成品料号 |
| 项次（一级） | `seq_no` | ✅ | 序号（对应来料项次） |
| 投入料号 | `code` | ✅ | 元素代码/材料料号/零件号/耗材料号 |
| 投入料号名称 | — | ❌ | 不导入 |
| 项次（二级） | — | ❌ | 不导入（由 seq_no 一级项次区分） |
| 要素名称 | `cost_type` | ✅ | 费用类型（动态写入） |
| 值 | `pricing_price` | ✅ | 费用(固定)（固定金额时填） |
| 比例（%） | `cost_ratio` | ✅ | 比例（比例费用时填） |
| 货币 | `currency` | ✅ | 币种 |
| 计价单位 | `unit` | ✅ | 计量单位 |

> 📌 `cost_type` 动态取自「要素名称」列；固定金额费用写 `pricing_price`，比例费用写 `cost_ratio`，两者以对应字段是否为空区分。客户编号由系统自动提供。

---

### 8. 来料年降

**目标表：** `unit_price`

| 固定写入字段 | 固定值 / 来源 |
|------------|--------------|
| `system_type` | `QUOTE` |
| `price_type` | `MATERIAL`（材料） |
| `cost_type` | `年降系数` |
| `customer_no` | **由系统导入时提供** |

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 宏丰料号（成品料号） | `finished_material_no` | ✅ | 成品料号 |
| 项次 | `seq_no` | ✅ | 序号 |
| 投入料号 | `code` | ✅ | 元素代码/材料料号/零件号/耗材料号 |
| 投入料号名称 | — | ❌ | 不导入 |
| 年降顺序 | `discount_order` | ✅ | 序号（年降顺序） |
| 年降系数（%） | `cost_ratio` | ✅ | 比例 |
| 单次固定年降值 | `pricing_price` | ✅ | 费用(固定) |
| 货币 | `currency` | ✅ | 币种 |
| 计价单位 | `unit` | ✅ | 计量单位 |
| 降价次数 | — | ❌ | 不导入 |

> 📌 `cost_type=年降系数` 固定写入；年降系数（%）与单次固定年降值二选一填写，另一个为空。降价次数列不导入。

---

### 9. 来料回收折扣

**目标表：** `unit_price`

| 固定写入字段 | 固定值 / 来源 |
|------------|--------------|
| `system_type` | `QUOTE` |
| `price_type` | `MATERIAL`（材料） |
| `cost_type` | `回收折扣` |
| `customer_no` | **由系统导入时提供** |

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 宏丰料号（成品料号） | `finished_material_no` | ✅ | 成品料号 |
| 项次 | `—` | ❌ | 不导入 |
| 投入料号 | `code` | ✅ | 元素代码/材料料号/零件号/耗材料号 |
| 投入料号名称 | — | ❌ | 不导入 |
| 回收折扣（%） | `cost_ratio` | ✅ | 比例 |

> 📌 `cost_type=回收折扣`，`price_type=MATERIAL` 固定写入；客户编号由系统自动提供。

---

### 10. 自制加工费

**目标表：** `unit_price`

| 固定写入字段 | 固定值 / 来源 |
|------------|--------------|
| `system_type` | `QUOTE` |
| `price_type` | `MATERIAL`（材料） |
| `cost_type` | `自制加工费` |
| `customer_no` | **由系统导入时提供** |

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 宏丰料号（成品料号） | `finished_material_no` | ✅ | 成品料号 |
| 项次（一级） | `seq_no` | ✅ | 序号 |
| 投入料号 | `code` | ✅ | 元素代码/材料料号/零件号/耗材料号 |
| 投入料号名称 | — | ❌ | 不导入 |
| 项次（二级） | — | ❌ | 不导入 |
| 工序编号 | `operation_no` | ✅ | 作业编号 |
| 工序名称 | — | ❌ | 不导入 |
| 值 | `pricing_price` | ✅ | 费用(固定)（固定金额时填） |
| 比例（%） | `cost_ratio` | ✅ | 比例（比例费用时填） |
| 货币 | `currency` | ✅ | 币种 |
| 计价单位 | `unit` | ✅ | 计量单位 |

> 📌 `price_type=MATERIAL`，`cost_type=自制加工费` 固定写入；客户编号由系统自动提供。工序名称不导入。

---

### 11. 成品其他费用

**目标表：** `unit_price`

| 固定写入字段 | 固定值 / 来源 |
|------------|--------------|
| `system_type` | `QUOTE` |
| `price_type` | `MATERIAL`（材料） |
| `cost_type` | 取自 Excel「要素名称」列（动态写入） |
| `customer_no` | **由系统导入时提供** |

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 宏丰料号 | `code` | ✅ | 元素代码/材料料号/零件号/耗材料号（成品料号） |
| 项次 | `seq_no` | ✅ | 序号 |
| 要素名称 | `cost_type` | ✅ | 费用类型（动态写入） |
| 值 | `pricing_price` | ✅ | 费用(固定)（固定金额时填） |
| 比例（%） | `cost_ratio` | ✅ | 比例（比例费用时填） |
| 货币 | `currency` | ✅ | 币种 |
| 计价单位 | `unit` | ✅ | 计量单位 |

> 📌 `cost_type` 动态取自「要素名称」列；固定金额写 `pricing_price`，比例费用写 `cost_ratio`。客户编号由系统自动提供。

---

### 12. 组成件BOM

**目标表：** `material_bom`（主表） + `material_bom_item`（子表）

| 固定写入字段 | 固定值 / 来源 |
|------------|--------------|
| `system_type` | `QUOTE` |
| `bom_type` | `ASSEMBLY` |
| `customer_no` | **由系统导入时提供** |

#### → 物料BOM主表（material_bom，bom_type=ASSEMBLY）

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 宏丰料号 | `material_no` | ✅ | 主件料号 |
| 客户编号 | `customer_no` | ⚙️ | 系统自动提供 |

#### → 物料BOM子表（material_bom_item，bom_type=ASSEMBLY）

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 宏丰料号 | `material_no` | ✅ | 主件料号 |
| 客户编号 | `customer_no` | ⚙️ | 系统自动提供 |
| 项次（一级） | `seq_no` | ✅ | |
| 工序编号 | `operation_no` | ✅ | 作业编号 |
| 组装工序 | — | ❌ | 不导入（工序名称，仅展示） |
| 项次（二级） | `item_seq` | ✅ | 作业序 |
| 组成件料号 | `component_no` | ✅ | 组件料号 |
| 组成件名称 | — | ❌ | 不导入 |
| 组成数量 | `composition_qty` | ✅ | 组成用量 |
| 组成单位 | `issue_unit` | ✅ | 发料单位 |

> 📌 `bom_type=ASSEMBLY` 固定写入，与物料BOM（`bom_type=MATERIAL`）共用同一主/子表。客户编号由系统自动提供。
> 🔀 **去重合并（组成件优先）**：同一料号只要出现在本表，最终即以 `bom_type=ASSEMBLY`、`characteristic='ASSEMBLY'` 保存为**唯一当前行**；若该料号本次也出现在「物料BOM」(§3)，两表子行按 `(system_type,customer_no,material_no,component_no)` 合并、冲突字段取本表（组成件）值，物料BOM 不再单独留 `characteristic=NULL` 行——详见 §一 总览末尾【物料BOM ⇄ 组成件BOM 同料号去重合并规则】。

---

### 13. 组成件其他费用

**目标表：** `unit_price`

| 固定写入字段 | 固定值 / 来源 |
|------------|--------------|
| `system_type` | `QUOTE` |
| `price_type` | `COMPONENT`（组成件） |
| `cost_type` | 取自 Excel「要素名称」列（动态写入） |
| `customer_no` | **由系统导入时提供** |

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 宏丰料号（成品料号） | `finished_material_no` | ✅ | 成品料号 |
| 项次（一级） | `seq_no` | ✅ | 序号 |
| 工序编号 | `operation_no` | ✅ | 作业编号 |
| 组装工序 | — | ❌ | 不导入 |
| 项次（二级，组成件） | — | ❌ | 不导入 |
| 组成件料号 | `code` | ✅ | 元素代码/材料料号/零件号/耗材料号 |
| 组成件名称 | — | ❌ | 不导入 |
| 供应商编号 | `supplier_no` | ✅ | |
| 供应商名称 | — | ❌ | 不导入 |
| 项次（要素） | `item_seq` | ✅ | 序号（要素项次） |
| 要素编号 | — | ❌ | 不导入 |
| 要素名称 | `cost_type` | ✅ | 费用类型（动态写入） |
| 值 | `pricing_price` | ✅ | 费用(固定) |
| 货币 | `currency` | ✅ | 币种 |
| 计价单位 | `unit` | ✅ | 计量单位 |

> 📌 `price_type=COMPONENT` 固定写入；`cost_type` 动态取自「要素名称」列。组装工序名称、组成件名称、供应商名称、要素编号不导入。客户编号由系统自动提供。

---

### 14. 组装加工费

**目标表：** `capacity`

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 宏丰料号 | `material_no` | ✅ | 料号 |
| 项次 | `seq_no` | ✅ | |
| 组装工序 | `process_no` | ✅ | 工序编号（取工序编号对应值） |
| 组装加工费 | `fixed_cost` | ✅ | 费用(固定) |
| 货币 | `currency` | ✅ | 币种 |
| 计价单位 | `unit` | ✅ | 计量单位 |
| 拒收率/不良率（%） | `default_defect_rate` | ✅ | 默认不良率(%) |

> 📌 组装加工费落入 `capacity` 表（产能表），通过 `process_no` 关联工序；拒收率/不良率写入 `default_defect_rate`。

---

### 15. 组装加工费年降

**目标表：** `unit_price`

| 固定写入字段 | 固定值 / 来源 |
|------------|--------------|
| `system_type` | `QUOTE` |
| `price_type` | `COMPONENT`（组成件） |
| `cost_type` | `年降系数` |
| `customer_no` | **由系统导入时提供** |

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 宏丰料号（成品料号） | `finished_material_no` | ✅ | 成品料号 |
| 项次 | `seq_no` | ✅ | 序号 |
| 组装工序 | `operation_no` | ✅ | 作业编号（工序编号） |
| 年降顺序 | `discount_order` | ✅ | 序号（年降顺序） |
| 年降系数（%） | `cost_ratio` | ✅ | 比例 |
| 单次固定年降值 | `pricing_price` | ✅ | 费用(固定) |
| 货币 | `currency` | ✅ | 币种 |
| 计价单位 | `unit` | ✅ | 计量单位 |
| 降价次数 | — | ❌ | 不导入 |

> 📌 `price_type=COMPONENT`，`cost_type=年降系数` 固定写入；年降系数（%）与单次固定年降值二选一填写。客户编号由系统自动提供；降价次数不导入。

---

### 16. 电镀方案

**目标表：** `plating_scheme`

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 方案编号 | `scheme_no` | ✅ | 电镀方案编号 |
| 版本 | `scheme_version` | ✅ | 电镀方案版本 |
| 项次 | `seq_no` | ✅ | |
| 电镀元素名称 | `plating_element` | ✅ | 电镀元素（如 Ag/Au/Ni/Sn/Cu） |
| 元素单价来源网站网址 | `source_url` | ✅ | 抓取网址（报价端新增，来源于元素单价配置） |
| 元素单价来源网站名称 | `source_name` | ✅ | 网站名称 |
| 元素单价抓取规则 | `fetch_rule` | ✅ | 取用规则 |
| 电镀面积（cm²） | `plating_area` | ✅ | 电镀面积 (cm²) |
| 镀层厚度（μm） | `plating_thickness` | ✅ | |
| 电镀要求 | `plating_requirement` | ✅ | 电镀要求/规格描述 |

> 📌 报价系统电镀方案比核价系统多出元素单价来源网站、抓取规则三个字段（`source_url`、`source_name`、`fetch_rule`），均需导入。`element_usage` 由系统根据 `plating_area × plating_thickness × density` 自动计算。

---

### 17. 电镀费用

**目标表：** `unit_price`（每行拆分为两条记录）

> ⚠️ **特殊规则：** 当电镀方案编号不为空时，该行整体跳过不导入，由系统根据电镀方案表自动计算。

#### → 第 1 条：电镀加工费

| 固定写入字段 | 固定值 / 来源 |
|------------|--------------|
| `system_type` | `QUOTE` |
| `price_type` | `MATERIAL`（材料） |
| `cost_type` | `电镀加工费` |
| `customer_no` | **由系统导入时提供** |

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 宏丰料号 | `code` | ✅ | 元素代码/材料料号/零件号/耗材料号 |
| 电镀方案编号 | `plating_scheme_no` | ✅ | 不为空时整行跳过不导入 |
| 版本编号 | `version_no` | ✅ | 价格版本 |
| 电镀加工费 | `pricing_price` | ✅ | 费用(固定) |
| 电镀材料费 | — | ❌ | 不导入（归第 2 条记录） |
| 货币 | `currency` | ✅ | 币种 |
| 计价单位 | `unit` | ✅ | 计量单位 |
| 不良率（%） | `defect_rate` | ✅ | 不良率% |

#### → 第 2 条：电镀材料费

| 固定写入字段 | 固定值 / 来源 |
|------------|--------------|
| `system_type` | `QUOTE` |
| `price_type` | `MATERIAL`（材料） |
| `cost_type` | `电镀材料费` |
| `customer_no` | **由系统导入时提供** |

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 宏丰料号 | `code` | ✅ | 元素代码/材料料号/零件号/耗材料号 |
| 电镀方案编号 | `plating_scheme_no` | ✅ | 不为空时整行跳过不导入 |
| 版本编号 | `version_no` | ✅ | 价格版本 |
| 电镀加工费 | — | ❌ | 不导入（归第 1 条记录） |
| 电镀材料费 | `pricing_price` | ✅ | 费用(固定) |
| 货币 | `currency` | ✅ | 币种 |
| 计价单位 | `unit` | ✅ | 计量单位 |
| 不良率（%） | `defect_rate` | ✅ | 不良率% |

> 📌 每行 Excel 拆分为两条 `unit_price` 记录，仅 `cost_type` 与 `pricing_price` 取值不同。当电镀方案编号不为空时整行跳过。客户编号由系统自动提供。

---

### 18. 单重

**目标表：** `material_master`

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 料号 | `material_no` | ✅ | 料号（业务唯一） |
| 单重（g/pcs） | `unit_weight` | ✅ | 单重 (g/pcs) |

> 📌 按 `material_no` upsert（存在则更新 `unit_weight`，不存在则插入）。

---

### 19. 年降系数

**目标表：** `annual_discount`

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 宏丰料号 | `material_no` | ✅ | 料号 |
| 年降顺序 | `discount_order` | ✅ | 年降顺序 |
| 年降系数（%/年） | `discount_ratio` | ✅ | 年降系数(%) |
| 单次固定年降金额 | `fixed_discount_value` | ✅ | 单次年降值 |
| 货币 | `currency` | ✅ | 货币 |
| 计价单位 | `unit` | ✅ | 计价单位 |
| 降价次数 | `discount_times` | ✅ | 降价次数 |

> 📌 年降系数（%）与单次固定年降金额二选一填写；全字段导入，每行生成一条 `annual_discount` 记录。

---

## 三、通用落库规则

| 规则项 | 说明 |
|--------|------|
| **客户编号自动提供** | 凡目标表中存在 `customer_no` 字段的 Sheet，客户编号均由系统在导入时自动提供，Excel 文件中不维护此字段，程序不从 Excel 读取。 |
| **system_type 固定值** | 所有报价系统导入数据的 `system_type` 固定写入 `QUOTE`。 |
| **price_type 与 cost_type 区别** | `price_type` 标识价格来源分类（ELEMENT/MATERIAL/COMPONENT），`cost_type` 标识费用用途分类，两字段独立写入 `unit_price`。 |
| **动态 cost_type** | 来料其他费用、成品其他费用、组成件其他费用等 Sheet 中，`cost_type` 取自 Excel「要素名称」列动态写入，非固定值。 |
| **固定金额与比例区分** | `pricing_price` 存储固定金额，`cost_ratio` 存储比例（%），同一条记录两者互斥填写，以对应字段是否为空判断费用类型。 |
| **一行拆多条** | 「电镀费用」Sheet 每行拆分为两条 `unit_price` 记录（电镀加工费 + 电镀材料费）；其余 Sheet 一行对应一条记录（或主+子各一条）。 |
| **电镀条件判断** | 「电镀费用」Sheet：当电镀方案编号不为空时，该行整体跳过不导入，由系统根据电镀方案表自动计算结果。 |
| **元素BOM版本规则** | `element_bom` 的 `characteristic`（特性）默认写入 `2000`；当同一主件料号出现不同组件组成或用量时，特性版本号自动递增（+1）。 |
| **元素回收折扣为更新操作** | 「元素回收折扣」Sheet 落库时为更新操作，按 `(material_no, component_no, seq_no)` 匹配 `element_bom_item` 中特性最新版本的记录，更新 `recovery_discount` 字段。 |
| **bom_type 区分** | 物料BOM（来料）写入 `bom_type=MATERIAL`；组成件BOM（组装）写入 `bom_type=ASSEMBLY`；两者共用 `material_bom` 和 `material_bom_item` 表。**同一料号两表都填时，按下行去重合并规则塌缩为一条当前行（组成件优先）。** |
| **物料BOM ⇄ 组成件BOM 同料号去重合并**（2026-06-03 新增） | 同一料号在 `material_bom` 只保留一条当前行（`is_current=true`）：本次出现在组成件BOM → `bom_type=ASSEMBLY`/`characteristic='ASSEMBLY'`，否则 → `bom_type=MATERIAL`/`characteristic=NULL`；子行按 `(system_type,customer_no,material_no,component_no)`（不含 characteristic）合并，冲突字段组成件优先、独有字段取并集；版本血缘**同 characteristic 内**由 writer 升版翻转，**换 characteristic 的旧行须由导入器手动下线**（V3.2 第 4 步，否则双当前行复现）；文件即权威（只填一边重导时另一边明细不保留）。完整说明见 §一 总览末尾专项规则块 +【去重合并实现细则】。实现收敛在导入器层（新增 `MaterialBomMergeHandler` 内存合并 + 先下线相反 characteristic 旧行 + 单次写入，**零 DDL、不动 characteristic 隔离**，因选配链路依赖双行契约喂 mirror 视图），已过 architect 评审；PM 已澄清料号命名空间靠 `CFG-` 前缀天然不相交（导入侧拒收 `CFG-` 行加固）。 |
| **upsert 策略** | 料号表（`material_master`）按 `material_no` 做 upsert；其余表按各自唯一约束做 INSERT OR UPDATE。 |
| **布尔字段转换** | 「是否随材料价格波动」等文字值统一转换：是→1，否→0，以 TINYINT(1) 存储。 |
| **数据清洗** | 导入前过滤空行（所有关键字段均为空的行）；标题行、注释行、说明行不导入。 |
| **多表写入顺序** | 建议写入顺序：料号表 → 料号关系表 → BOM主表 → BOM子表 → 单价表 → 年降系数表，以保证外键约束。 |

---

*文档完*
