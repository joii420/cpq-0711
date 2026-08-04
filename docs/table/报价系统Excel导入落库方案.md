# 报价系统基础数据 Excel 导入落库方案

> 版本：V3.7 | 日期：2026-08-03
>
> **V3.7（2026-08-03，repair-0804）· 年降三 Sheet 落库统一**：§8「来料年降」、§15「组装加工费年降」原落 `unit_price`（`price_type=INCOMING_MATERIAL_REDUCTION` / `COMPONENT_REDUCTION`），§19「年降系数」原落 `annual_discount` 且无版本化/无 pending 隔离/无客户维度。本次三者收敛到单表 `annual_discount`，用 `discount_type`（`INCOMING_MATERIAL` / `ASSEMBLY_PROCESS` / `FINISHED`）区分，`target_no` 为泛化挂载目标（材质料号 / 工序编号 / NULL），统一走组级版本化 + pending 隔离，并补齐 `customer_no`、补回 `seq_no`「项次」与 `discount_times`「降价次数」。`unit_price` 两个退役 `price_type` 的存量已清除，CHECK 约束保留不动。**三个 Sheet 内同一 `(销售料号, 年降顺序[, 挂载目标])` 的重复行走「组内归并、逐字段末值非空胜」**（典型：一行填「年降系数（%）」、另一行填「单次固定年降值」），与 V3.6 给「来料回收折扣」定的口径一致，不撞唯一键报错。详见 `dev-docs/task-0708-导入报价单和导入核价单的数据落库规则澄清/repair-0804-年降三sheet的入库规则/需求文档.md`。
>
> **V3.6（2026-07-30，task-0730）· §9 来料回收折扣新增 项次/值/货币/计价单位 四列**：`项次→seq_no`（不必填不补号，空即 NULL）、`值→pricing_price`、`货币→currency`、`计价单位→unit`；「值」与「回收折扣（%）」**并存但必填其一**（Phase 1 拦截）；同一 `(成品料号, 投入料号, COALESCE(项次,0))` 的重复行走**组内 upsert 末值胜**，不再撞唯一键报错。`CONTENT` 同步扩为 `[seq_no, cost_ratio, pricing_price, currency, unit]`（漏加会导致「只改值不改折扣%」被静默吞掉）。**顺带修正本节两处历史漂移**：主料号列名早已由「宏丰料号」改为「销售料号」（代码有回退兼容）；投入料号的名称反查规则已被 update-0723 U10 覆盖（材质走 `material_recipe`、查无即报错，只有零件/外购件才发号）。零 DDL、零 Flyway 迁移。
>
> **V3.5（2026-07-27，repair-0727）· 组装工序编号/名称解析**：§14「组装加工费」与 §15「组装加工费年降」的「组装工序」列此前**未做工序解析**，业务填的工序名称被原样写入编号列（`capacity.process_no`/`unit_price.operation_no`），`process_name` 恒 NULL；本次改为 Phase 1（`QuoteImportValidator`）经新增 `ProcessNoResolver` 反查 `process_master`（先按编号精确匹配、再按名称精确匹配，同名多条取 `process_no` 升序第一条并记日志），解析成功才落真编号 + 规范名称，解析不到则整单导入失败（fail-fast，不写库）。详见下方 §14/§15 与需求文档 `dev-docs/task-0708-导入报价单和导入核价单的数据落库规则澄清/repair-0727-工序编号与工序名称落库优化/需求文档.md`。
>
> **V3.4（2026-07-08）· 销售料号主料号口径 + 材质料号**：报价 V3 各 Sheet 主料号列统一为 **`销售料号`**（已无「宏丰料号」/「报价料号」列）——各 Q* handler 主料号/成品料号读列改为 `row.getStr("销售料号", <旧名回退>)` 落 `material_no`/`finished_material_no`/`code`。
> - **材质料号**：`物料BOM`/`物料与元素BOM`/`元素回收折扣` 含 `材质料号` 列 → 落 `element_bom.material_part_no`（新增列并纳入唯一键，与核价侧同口径）；`物料BOM`（MaterialBomMergeHandler）组件列由 `投入料号` 回退 `材质料号`（名称 `投入料号名称` 回退 `材质料号名称`），仍走 §1.5 按名发号。
> - **`物料与元素BOM`（Q04）**：该 Sheet **无投入料号列**（只有 销售料号+材质料号+元素）→ `material_no←销售料号`、`material_part_no←材质料号`、`component_no←元素`，master/版本分组键改为 `(material_no, material_part_no)`，**本 Sheet 不再铸号**；`元素回收折扣`（Q05）同样按 `(销售料号, 材质料号, 元素)` 3 键匹配更新 `element_bom_item.recovery_discount`。
> - **发号（铸号）边界**：仅在**仍含 `投入料号`/`材质料号` 组件列**的 Sheet 保留组件缺料号时按名发号（组件维度）。
> - **`production_no` 恒 NULL**：报价 Excel 无生产料号列。
>
> **V3.3（2026-06-18）**：补齐 `unit_price.price_type` 细分化（2026-06-08 代码已生效，本文档此前漏更）——大类 `MATERIAL`/`COMPONENT` 废弃，各 Sheet 直接写 9 个细分值（来料固定加工费=`INCOMING_MATERIAL_PROCESS`、来料其他费用=`INCOMING_MATERIAL_OTHER`、来料年降=`INCOMING_MATERIAL_REDUCTION`、来料回收折扣=`INCOMING_MATERIAL_RECYCLE`、自制加工费=`PROCESS`、成品其他费用=`FINISHED_MATERIAL_OTHER`、组成件其他费用=`COMPONENT_OTHER`、组装加工费年降=`COMPONENT_REDUCTION`、电镀费用=`PLATING`）；`cost_type` 不变。规则出处 `docs/superpowers/specs/2026-06-08-quote-price-type-subdivide-design.md`。

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
| 6 | 来料固定加工费 | `unit_price` | `INCOMING_MATERIAL_PROCESS` | `来料加工费` |
| 7 | 来料其他费用 | `unit_price` | `INCOMING_MATERIAL_OTHER` | `要素名称（动态）` |
| 8 | 来料年降 | `annual_discount`（`discount_type=INCOMING_MATERIAL`） | — | — |
| 9 | 来料回收折扣 | `unit_price` | `INCOMING_MATERIAL_RECYCLE` | `回收折扣` |
| 10 | 自制加工费 | `unit_price` | `PROCESS` | `自制加工费` |
| 11 | 成品其他费用 | `unit_price` | `FINISHED_MATERIAL_OTHER` | `要素名称（动态）` |
| 12 | 组成件BOM | `material_bom`（主表） + `material_bom_item`（子表） | — | — |
| 13 | 组成件其他费用 | `unit_price` | `COMPONENT_OTHER` | `要素名称（动态）` |
| 14 | 组装加工费 | `capacity` | — | — |
| 15 | 组装加工费年降 | `annual_discount`（`discount_type=ASSEMBLY_PROCESS`） | — | — |
| 16 | 电镀方案 | `plating_scheme` | — | — |
| 17 | 电镀费用（加工费） | `unit_price` | `PLATING` | `电镀加工费` |
| 17 | 电镀费用（材料费） | `unit_price` | `PLATING` | `电镀材料费` |
| 18 | 单重 | `material_master` | — | — |
| 19 | 年降系数 | `annual_discount`（`discount_type=FINISHED`） | — | — |

> 📌 **repair-0804**：#8「来料年降」、#15「组装加工费年降」原落 `unit_price`（`price_type=INCOMING_MATERIAL_REDUCTION` / `COMPONENT_REDUCTION`），本次统一迁至 `annual_discount`，改用 `discount_type` 判别；`unit_price` 的这两个 `price_type` 已随迁移退役（存量清除，`chk_unit_price_type` CHECK 约束保留枚举值不动）。详见下方 §8 / §15 / §19 与「三、通用落库规则」的 `price_type 与 cost_type 区别`。

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
| 宏丰料号   | `material_no` |    ✅     | 按 upsert 写入料号表 |
|            |               |          |                      |
|            |               |          |                      |

> ✅ **2026-06-18 实现**：`Q02CustomerMapHandler` 在 upsert `material_customer_map` 后，对 `宏丰料号` 同步 `materialMasterRepo.upsertByMaterialNo(...)`（仅 `material_no`，`preserveDescriptive=true`）。修复前成品只进客户映射表、不进料号主数据表，报价候选查询 `FROM material_master WHERE material_no IN hfPairs` 命中 0 行 → 报价单提示「该客户暂无基础数据料号」。

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
| 产出料号类型<br>*（或写「产出类型」）* | `component_usage_type` | ✅ | 只存汉字（剥离"N."编号）：银点类 / 非银点类 / 组成件 / 边角料。表头两种写法都认，见本节末「表头别名」 |
| 材料毛重 | `rough_weight` | ✅ | 毛重                                    |
| 材料净重 | `net_weight` | ✅ | 净重 |
| 重量单位 | `weight_unit` | ✅ | 重量单位 |
| 损耗率（%） | `scrap_rate` | ✅ | |
| 不良率（%） | `defect_rate` | ✅ | |
| 材质占比<br>*（或写「材料占比」）* | `material_ratio` | ✅ | **非必填**；小数口径（`0.3` = 30%），与 `element_bom_item.content`（组成含量）同口径同精度 `numeric(18,6)`。**仅材质行有效**：`characteristic='RECIPE'` 时取 Excel 值，零件/外购件行由 handler 显式置 NULL（防非材质行误填污染）。列由 V365 新增。表头两种写法都认，见下 |

> 🔤 **表头别名（2026-08-01）**：上表这两列各接受两种写法，是**两个互相独立的字段**，不要互相代替：
>
> | 导入 sheet 表头（任一） | 落库列 | 组件卡片列名 | 取值规则 |
> |---|---|---|---|
> | `材质占比` / `材料占比` | `material_ratio` | 材料占比 | 仅材质行（RECIPE）取值 |
> | `产出料号类型` / `产出类型` | `component_usage_type` | 产出类型 | 三态都取，剥离前导"N." |
>
> 别名常量在 `MaterialBomMergeHandler.MATERIAL_RATIO_HEADERS` / `USAGE_TYPE_HEADERS`。
> **为什么要兼容**：同一概念在导入模板/文档里叫「材质占比」，在卡片列名与 SQL 视图别名 `_材料占比`
> 里叫「材料占比」。客户按后者加列时 `SheetRow.getStr` 的 contains 匹配不上 →
> **静默按"没填"处理**（不报错、不计失败行、整列丢数，2026-08-01 A/B 实测）。属 AP-52 族。
> 匹配是 contains 且按**列序**取首个命中，故一份 sheet 里两种写法都出现时以左边那列为准。

> 📌 **「材质占比」为什么是新增列而不是复用既有列**（2026-07-31，V365）：`material_bom_item` 50+ 列中，`composition_qty`（组成数量）/`base_qty`（底数）/`scrap_rate`/`defect_rate` 均已被 handler 占用；`upper_limit_pct`/`lower_limit_pct` 虽全表无值，但语义是 ERP 标准「用量上下限 %」，复用即语义错配（AP-52），故新增独立列。
>
> ⚠️ **改这列必须同步 3 处**（漏一处即静默失效）：
> 1. `MaterialBomMergeHandler.CHILD_CONTENT` —— 不含该列时，「只改材质占比」会被 `multisetEqual` 判成无变化 → 整组不写库、不升版（静默丢数据）；
> 2. `QuoteTableAxis.MATERIAL_BOM_ITEM.contentColumns` —— 不含该列时，报价单回填 / pending→正式投影会把该列抹成 NULL；
> 3. `BackfillLabelResolver.COLUMN_LABELS` —— 不含该列时，回填预览显示英文列名。
>
> 库层**不加** CHECK 约束：「同一销售料号下多材质占比合计 = 1」在非必填前提下无法强制（部分行为空时约束必然误伤），该校验属业务/公式层。核价侧 `P06MaterialBomHandler` 不写该列，PRICING 行恒 NULL。

---

### 4. 物料与元素BOM

> ✅ **2026-06-17 实现**：本 Sheet「投入料号/组成件料号」为空+名称有值时，按名称匹配料号表 / 匹配不到自动生成 9 字头料号并登记料号表(material_type=组成件)，再回填键列继续落库；§5 为更新型仅匹配不生成（详见 `docs/superpowers/plans/2026-06-17-quote-import-materialno-autogen-extend.md`）。

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
| 毛用量单位 | `issue_unit` | ✅ | 发料单位（净用量单位为空时的回退来源） |
| 净用量 | `base_qty` | ✅ | 主件底数 |
| 净用量单位 | `issue_unit` | ✅ | 发料单位（**净用量单位非空时替换毛用量单位**；trim 后为空白视同空，回退毛用量单位） |

> 📌 `characteristic`（特性）默认写入 `2000`；当同一主件料号（即投入料号）出现不同元素组成或用量时，特性版本号自动递增（+1）。
> 📌 Excel 行示例：`宏丰料号=3120012574, 投入料号=9996, 项次=1, 元素=Ag, 毛用量单位=PCS, 净用量单位=KG` → 写入 `element_bom_item(material_no='9996', characteristic='2000', seq_no=1, component_no='Ag', content=75, issue_unit='KG')`（净用量单位非空 → `issue_unit` 取 KG；若净用量单位留空则回退取毛用量单位 PCS）。

---

### 5. 元素回收折扣

> ✅ **2026-06-17 实现**：本 Sheet 投入料号为空+名称有值时，按名称匹配料号表取 material_no 后 UPDATE；更新型仅匹配不生成、不登记料号表（详见 plan 2026-06-17）。

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

> ✅ **2026-06-17 实现**：本 Sheet「投入料号/组成件料号」为空+名称有值时，按名称匹配料号表 / 匹配不到自动生成 9 字头料号并登记料号表(material_type=组成件)，再回填键列继续落库；§5 为更新型仅匹配不生成（详见 `docs/superpowers/plans/2026-06-17-quote-import-materialno-autogen-extend.md`）。

**目标表：** `unit_price`

| 固定写入字段 | 固定值 / 来源 |
|------------|--------------|
| `system_type` | `QUOTE` |
| `price_type` | `INCOMING_MATERIAL_PROCESS` |
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

> ✅ **2026-06-17 实现**：本 Sheet「投入料号/组成件料号」为空+名称有值时，按名称匹配料号表 / 匹配不到自动生成 9 字头料号并登记料号表(material_type=组成件)，再回填键列继续落库；§5 为更新型仅匹配不生成（详见 `docs/superpowers/plans/2026-06-17-quote-import-materialno-autogen-extend.md`）。

**目标表：** `unit_price`

| 固定写入字段 | 固定值 / 来源 |
|------------|--------------|
| `system_type` | `QUOTE` |
| `price_type` | `INCOMING_MATERIAL_OTHER` |
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

> 📌 **投入料号恒按材质料号处理**（task-0717）：原始码直接作 `target_no`，**不 resolve、不铸号、不登记 `material_customer_map` / `material_master`**；名称由视图 JOIN `material_recipe` 取，年降表不冗余存名称。

**目标表：** `annual_discount`（repair-0804 起；改造前落 `unit_price`，`price_type=INCOMING_MATERIAL_REDUCTION`，已随本次迁移退役）

| 固定写入字段 | 固定值 / 来源 |
|------------|--------------|
| `system_type` | `QUOTE` |
| `discount_type` | `INCOMING_MATERIAL` |
| `customer_no` | **由系统导入时提供** |

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 销售料号（成品料号） | `material_no` | ✅ | 销售料号（旧列名 `宏丰料号` 仍兼容回退） |
| 项次 | `seq_no` | ✅ | 序号（**repair-0804 新增导入**，改造前未落库） |
| 投入料号 | `target_no` | ✅ | 材质料号，原样落库（见上方 📌，不 resolve、不铸号） |
| 投入料号名称 | — | ❌ | 不导入；名称由视图 JOIN `material_recipe` 取 |
| 年降顺序 | `discount_order` | ✅ | **必填**（Phase 1 `QuoteImportValidator` 拦截空值；组内行集维度） |
| 年降系数（%） | `discount_ratio` | ✅ | 比例，与「单次固定年降值」二选一填写 |
| 单次固定年降值 | `fixed_discount_value` | ✅ | 固定金额，与「年降系数（%）」二选一填写 |
| 货币 | `currency` | ✅ | 币种 |
| 计价单位 | `unit` | ✅ | 计量单位 |
| 降价次数 | `discount_times` | ✅ | **repair-0804 新增导入**，改造前不导入 |

> 📌 `discount_type=INCOMING_MATERIAL` 固定写入；年降系数（%）与单次固定年降值二选一填写，另一个为空；客户编号由系统自动提供。

---

### 9. 来料回收折扣

> ✅ **投入料号取值（update-0723 U10 现行口径，已取代 2026-06-17 老规则）**：
> ① 投入料号**有值** → 原样沿用（不 resolve、不铸号）；
> ② 料号空 + 名称有值 → 先按 `TypeIndex` 推断类型：**材质**走 `material_recipe` 按名查码，**查无即报错「未找到材质」**（不发号）；**零件/外购件**走 `MaterialNoResolver` 按名匹配 `material_master`，匹配不到才自动生成 9 字头料号，并 upsert `material_master(material_type=零件/外购件)`；
> ③ 料号与名称**都空** → 拒绝该行。

**目标表：** `unit_price`

| 固定写入字段 | 固定值 / 来源 |
|------------|--------------|
| `system_type` | `QUOTE` |
| `price_type` | `INCOMING_MATERIAL_RECYCLE` |
| `cost_type` | `回收折扣` |
| `customer_no` | **由系统导入时提供** |

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 销售料号（成品料号） | `finished_material_no` | ✅ | 成品料号（旧列名 `宏丰料号`/`成品料号` 仍兼容回退） |
| 项次 | `seq_no` | ✅ | **不必填、不补号**：有值即存，空即 NULL |
| 投入料号 | `code` | ✅ | 元素代码/材料料号/零件号/耗材料号；为空时按「投入料号名称」反查/发号（见下） |
| 投入料号名称 | — | ❌ | 不落 `unit_price`；仅在投入料号为空时作反查依据，并 upsert `material_master` |
| 回收折扣（%） | `cost_ratio` | ✅ | 比例。与「值」**并存**，但二者**必填其一** |
| 值 | `pricing_price` | ✅ | 费用(固定)。与「回收折扣（%）」**并存**，但二者**必填其一** |
| 货币 | `currency` | ✅ | 币种 |
| 计价单位 | `unit` | ✅ | 计量单位（表头写 `单位` 亦可匹配） |

> 📌 `cost_type=回收折扣`，`price_type=INCOMING_MATERIAL_RECYCLE` 固定写入；客户编号由系统自动提供。

#### 值 / 回收折扣（%）的并存与必填其一（task-0730）

两列**可以同时有值**（不是二选一互斥），但**不得同时为空** —— Phase 1 `QuoteImportValidator.validateIncoming(..., requireValueOrRatio=true)` 零写库拦截，报错 `值/回收折扣（%）: 必填其一，不能同时为空`；`Q09IncomingRecoveryHandler` 内同款兜底，保证 handler 被直接调用（单测/其它编排）时语义一致。另两张来料表（来料固定加工费 / 来料其他费用）**不受此规则约束**（金额列语义不同）。

#### 项次与组内 upsert（末值胜，task-0730）

`seq_no` 已在 `uq_unit_price` 13 维内（`COALESCE(seq_no,0)`），故它决定「同一 (成品料号, 投入料号) 下能否存多行」：

- **项次不同** → 各自成行，同组多条并存。
- **项次相同（含都留空）** → **组内 upsert：后行覆盖前行，只落最后一条，不报唯一键冲突**。
  去重键 = `COALESCE(seq_no, 0)`，精确镜像 uq 表达式 —— **NULL 与 0 视为同一键**（否则「项次留空的多行」仍会撞 uq）。
  该手法对齐核价侧 `IncomingOtherMergeHandler` 的 EXCLUDED 覆盖语义；注意 `VersionedV6Writer.insertRowsBatched` 是**裸 INSERT 无 ON CONFLICT**，故去重必须在 handler 侧完成。
- 被覆盖的行仍计入 `successRows`（行本身合法，只是被合并），写入计数按去重后行数统计。

> ⚠️ 跨次导入不需要额外处理：版本化写入器本身就是「整组比对 → 复用版本 / 原地更新 / 升版」，天然具备 upsert 语义。需要 handler 侧去重的只有**同一批次组内**的重复行。

---

### 10. 自制加工费

> ✅ **2026-06-17 实现**：本 Sheet「投入料号/组成件料号」为空+名称有值时，按名称匹配料号表 / 匹配不到自动生成 9 字头料号并登记料号表(material_type=组成件)，再回填键列继续落库；§5 为更新型仅匹配不生成（详见 `docs/superpowers/plans/2026-06-17-quote-import-materialno-autogen-extend.md`）。

**目标表：** `unit_price`

| 固定写入字段 | 固定值 / 来源 |
|------------|--------------|
| `system_type` | `QUOTE` |
| `price_type` | `PROCESS` |
| `cost_type` | `自制加工费` |
| `customer_no` | **由系统导入时提供** |

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 宏丰料号（成品料号） | `finished_material_no` | ✅ | 成品料号 |
| 项次（一级） | `seq_no` | ✅ | 序号 |
| 投入料号 | `code` | ✅ | 元素代码/材料料号/零件号/耗材料号；**取值见下方「投入料号取值规则」** |
| 投入料号名称 | — | ❌ | 不直接落库；**投入料号为空时作为匹配/生成料号的依据（条件必填）** |
| 项次（二级） | — | ❌ | 不导入 |
| 工序编号 | `operation_no` | ✅ | 作业编号 |
| 工序名称 | — | ❌ | 不导入 |
| 值 | `pricing_price` | ✅ | 费用(固定)（固定金额时填） |
| 比例（%） | `cost_ratio` | ✅ | 比例（比例费用时填） |
| 货币 | `currency` | ✅ | 币种 |
| 计价单位 | `unit` | ✅ | 计量单位 |

> 📌 `price_type=PROCESS`，`cost_type=自制加工费` 固定写入；客户编号由系统自动提供。工序名称不导入。

#### 投入料号取值规则（`code` 兜底，2026-06-30 增补）

> ✅ **2026-06-30 已实现**：`Q10SelfProcessFeeHandler`（`cpq-backend/.../basicdata/v6/quote/`）—— 规则 1/2 复用 `MaterialNoResolver.resolve`；规则 3 在 catch `MaterialNoUnresolvableException` 分支兜底 `code=宏丰料号`，含「宏丰料号也空→拒绝」与「同成品重复→第二条拒绝」两道 fail-fast；测试 `Q10SelfProcessFeeResolveTest`（4 例）。

`unit_price.code` 为 `NOT NULL` 且是唯一键 `uq_unit_price` 的构成列（`system_type+price_type+version_no+code+COALESCE(customer_no,'')+COALESCE(supplier_no,'')+COALESCE(effective_date,'1900-01-01')`，**不含 `finished_material_no`/`operation_no`/`seq_no`**）。因此 `code` 必须能稳定标识一行，按以下优先级取值：

1. **投入料号有值** → `code = 投入料号`。
2. **投入料号为空、投入料号名称有值** → 走 2026-06-17 逻辑：按名称匹配料号表 / 匹配不到则自动生成 9 字头料号并登记料号表（`material_type=组成件`），回填 `code` 后落库。
3. **投入料号、投入料号名称都为空** → `code = 宏丰料号（成品料号 finished_material_no）`。语义为「针对该成品整体的自制加工费」，非针对具体投入件。

**配套强制校验（fail-fast，不得靠落库覆盖消化）**：

- 规则 3 命中时，对「投入料号 + 投入料号名称都为空」的行，按 `(version_no, finished_material_no, customer_no, COALESCE(effective_date,'1900-01-01'))` 去重；同组出现 ≥2 行即判为**非法数据**，**报错拒绝该行并列出明细**（如「成品 X 存在多条无投入料号的自制加工费，数据非法」），不得落库。
- 业务前提（已确认 2026-06-30）：「两个都空」的行为成品级加工费，**每个成品最多一条**，不按工序拆分多行。若后续业务调整为按工序拆多条，则规则 3 的 `code` 须改为 `成品料号 + 工序编号(+seq_no)` 派生唯一料号，并同步放宽上述去重维度。

> ⚠️ **禁止**用 `code='-'` 等占位值兜底：同客户同版本下所有 PROCESS 行除 `code` 外唯一键维度全同，占位会使多行塌缩成同一唯一键 → 互相撞键/静默覆盖丢数据。

---

### 11. 成品其他费用

**目标表：** `unit_price`

| 固定写入字段 | 固定值 / 来源 |
|------------|--------------|
| `system_type` | `QUOTE` |
| `price_type` | `FINISHED_MATERIAL_OTHER` |
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

> 📌 `bom_type=ASSEMBLY` 固定写入，与物料BOM（`bom_type=MATERIAL`）共用同一主/子表，通过 `bom_type` 区分。客户编号由系统自动提供。

20260615更新:

如果工序编号为空,工序名称存在值的话,则根据工序名称到process_master表中进行查询process_name=工序名称的数据取第一条,填入process_no的值到`operation_no`

#### → 料号表（material_master）同步

| Excel 列名   | 目标表字段      | 是否导入 | 备注说明                                             |
| ------------ | --------------- | :------: | ---------------------------------------------------- |
| 组成件料号   | `material_no`   |    ✅     | 按 upsert 写入料号表                                 |
| 组成件名称   | `material_name` |    ✅     | 按 upsert 写入料号表                                 |
| 产出料号类型 | `material_type` |    ✅     | 1.银点类 / 2.非银点类 / 组成件 / 边角料  ,只写入数字 |
| 投入料号名称 | material_name   |    ✅     | 料件名称                                             |

> 📌 `system_type=QUOTE`，`bom_type=MATERIAL` 固定写入主表；客户编号由系统自动提供。投入料号同步 upsert 至料号表，写入 `material_type` 默认为3。
>
> 如果导入时的料号名称为空,需要先查询料号表是否有名称相同的料件名称,如果有的话可以根据料件名称进行upsert,如果料件名称也不存在则根据规则自动生成一个料号进行存储.
>
> 自动生成料号的规则暂时约定为: 十位数 9000000000进行递增, 作标记以后可能进行规则修改.

---

### 13. 组成件其他费用

> ✅ **2026-06-17 实现**：本 Sheet「投入料号/组成件料号」为空+名称有值时，按名称匹配料号表 / 匹配不到自动生成 9 字头料号并登记料号表(material_type=组成件)，再回填键列继续落库；§5 为更新型仅匹配不生成（详见 `docs/superpowers/plans/2026-06-17-quote-import-materialno-autogen-extend.md`）。

**目标表：** `unit_price`

| 固定写入字段 | 固定值 / 来源 |
|------------|--------------|
| `system_type` | `QUOTE` |
| `price_type` | `COMPONENT_OTHER` |
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

> 📌 `price_type=COMPONENT_OTHER` 固定写入；`cost_type` 动态取自「要素名称」列。组装工序名称、组成件名称、供应商名称、要素编号不导入。客户编号由系统自动提供。

---

### 14. 组装加工费

**目标表：** `capacity`

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 宏丰料号 | `material_no` | ✅ | 料号 |
| 项次 | `seq_no` | ✅ | |
| 组装工序 | `process_no` | ✅ | **工序编号**（经解析，见下方 📌） |
| 组装工序 | `process_name` | ✅ | **工序名称**（repair-0727 新增落库，经解析取 `process_master` 规范名） |
| 组装加工费 | `fixed_cost` | ✅ | 费用(固定) |
| 货币 | `currency` | ✅ | 币种 |
| 计价单位 | `unit` | ✅ | 计量单位 |
| 拒收率/不良率（%） | `default_defect_rate` | ✅ | 默认不良率(%) |

> 📌 组装加工费落入 `capacity` 表（产能表）；拒收率/不良率写入 `default_defect_rate`。
>
> 📌 **组装工序解析规则（repair-0727，2026-07-27）**：Excel「组装工序」列业务既可能填工序名称、也可能填工序编号，导入 Phase 1（`QuoteImportValidator.validateAssemblyProcess`，经 `ProcessNoResolver`）按**两段匹配**反查 `process_master`：① 原始值先按 `process_no` 精确匹配；② 未命中再按 `process_name` 精确匹配（同名多条取 `process_no` 升序第一条，并记 `Log.warn` 留痕全部候选）。解析成功才落 `(process_no, process_name)`；**解析不到 → Phase 1 拦截，整份 Excel 导入失败**（不是"该行跳过"，也不是"该 sheet 部分失败"），错误按**销售料号聚合**上报（同料号多道工序未登记只报一条，文案含全部未登记工序名）。`process_name` 是内容列（`CONTENT`），不进 `VERSION_TRIGGER`（仍为 `process_no` + `seq_no`）——工序改名走原地更新，不触发 `capacity` 升版。旧版本（本次改动前）`process_no` 直接写 Excel 原文、`process_name` 恒 NULL，属已废止的错误落库语义，历史脏数据不迁移，重导即覆盖。

---

### 15. 组装加工费年降

**目标表：** `annual_discount`（repair-0804 起；改造前落 `unit_price`，`price_type=COMPONENT_REDUCTION`，已随本次迁移退役）

| 固定写入字段 | 固定值 / 来源 |
|------------|--------------|
| `system_type` | `QUOTE` |
| `discount_type` | `ASSEMBLY_PROCESS` |
| `customer_no` | **由系统导入时提供** |

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 销售料号（成品料号） | `material_no` | ✅ | 销售料号（旧列名 `宏丰料号` 仍兼容回退） |
| 项次 | `seq_no` | ✅ | 序号（**repair-0804 新增导入**，改造前未落库） |
| 组装工序 | `target_no` | ✅ | **真工序编号**（经解析，见下方 📌；允许为空） |
| 年降顺序 | `discount_order` | ✅ | **必填**（Phase 1 `QuoteImportValidator` 拦截空值；组内行集维度） |
| 年降系数（%） | `discount_ratio` | ✅ | 比例，与「单次固定年降值」二选一填写 |
| 单次固定年降值 | `fixed_discount_value` | ✅ | 固定金额，与「年降系数（%）」二选一填写 |
| 货币 | `currency` | ✅ | 币种 |
| 计价单位 | `unit` | ✅ | 计量单位 |
| 降价次数 | `discount_times` | ✅ | **repair-0804 新增导入**，改造前不导入 |

> 📌 `discount_type=ASSEMBLY_PROCESS` 固定写入；年降系数（%）与单次固定年降值二选一填写。客户编号由系统自动提供。
>
> 📌 **组装工序解析规则（repair-0727，2026-07-27，本次落库列变化但解析语义未变）**：与 §14 同一套 `ProcessNoResolver` 两段匹配（`QuoteImportValidator.validateAssemblyAnnualDiscount`），差异有二：① 本表**不写工序名称**（`annual_discount` 无名称列，名称由视图 JOIN `process_master` 取）；② 「组装工序」列**允许为空**（`target_no` 允许 NULL）——为空则跳过解析、不记错；只有「填了但解析不到」才按销售料号聚合报错、整单拦截。
>
> 📌 **repair-0804 迁表说明**：本 Sheet 已从 `unit_price`（`operation_no` 列）迁至 `annual_discount`（`target_no` 列），`target_no` 仍在 groupKey 内，语义不变（解析后的真工序编号）。因整表重建（V377）时 `COMPONENT_REDUCTION` 存量已清空、`annual_discount` 无同类历史数据，**不再有 §15 旧版记载的"升级时序约束/双 current"问题**——`unit_price` 时代那条 ⚠️ 已随迁表消失。

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
| `price_type` | `PLATING`（电镀） |
| `cost_type` | `电镀加工费` |
| `customer_no` | **由系统导入时提供** |

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 投入料号 | `code` | ✅ | **非必填**；零件料号。空则回退销售料号（语义=电镀针对成品自身） |
| 投入料号名称 | — | ❌ | **非必填**；仅当投入料号为空时用于按名反查/铸号并 upsert `material_master`，不落 `unit_price` |
| 销售料号（历史模板兼容读「宏丰料号」） | `finished_material_no` | ✅ | 成品料号（该费用上卷到的成品） |
| 电镀方案编号 | — | ❌ | **不落库**，仅判断用：不为空时整行跳过不导入 |
| 版本编号 | — | ❌ | **不落库**，忽略 Excel 值，`version_no` 由系统自增（2000 起） |
| 电镀加工费 | `pricing_price` | ✅ | 费用(固定) |
| 电镀材料费 | — | ❌ | 不导入（归第 2 条记录） |
| 货币 | `currency` | ✅ | 币种 |
| 计价单位 | `unit` | ✅ | 计量单位 |
| 不良率（%） | `defect_rate` | ✅ | 不良率% |

#### → 第 2 条：电镀材料费

| 固定写入字段 | 固定值 / 来源 |
|------------|--------------|
| `system_type` | `QUOTE` |
| `price_type` | `PLATING`（电镀） |
| `cost_type` | `电镀材料费` |
| `customer_no` | **由系统导入时提供** |

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 投入料号 | `code` | ✅ | **非必填**；零件料号。空则回退销售料号（语义=电镀针对成品自身） |
| 投入料号名称 | — | ❌ | **非必填**；仅当投入料号为空时用于按名反查/铸号并 upsert `material_master`，不落 `unit_price` |
| 销售料号（历史模板兼容读「宏丰料号」） | `finished_material_no` | ✅ | 成品料号（该费用上卷到的成品） |
| 电镀方案编号 | — | ❌ | **不落库**，仅判断用：不为空时整行跳过不导入 |
| 版本编号 | — | ❌ | **不落库**，忽略 Excel 值，`version_no` 由系统自增（2000 起） |
| 电镀加工费 | — | ❌ | 不导入（归第 1 条记录） |
| 电镀材料费 | `pricing_price` | ✅ | 费用(固定) |
| 货币 | `currency` | ✅ | 币种 |
| 计价单位 | `unit` | ✅ | 计量单位 |
| 不良率（%） | `defect_rate` | ✅ | 不良率% |

> 📌 每行 Excel 拆分为两条 `unit_price` 记录，仅 `cost_type` 与 `pricing_price` 取值不同。当电镀方案编号不为空时整行跳过。客户编号由系统自动提供。
> 📌 **repair-0802**：`code` = 投入料号（零件料号）、`finished_material_no` = 销售料号（成品），与 §6/§7/§13 及 `unit_price` 全表口径一致。groupKey = (system_type, customer_no, price_type, cost_type, code, finished_material_no)，同一销售料号下可有多个投入料号各自独立成行。「投入料号」「投入料号名称」均非必填：有码沿用原始码（不 resolve/不铸号）；只有名称则按类型推断反查/铸号；两者皆空回退为销售料号且**不报错**。名称反查失败已提前到 Phase 1（零写库）拦截，不再拖到 Phase 2 触发整单回滚。

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

**目标表：** `annual_discount`（结构已随 repair-0804 变化：新增 `discount_type`/`system_type`/`customer_no`，纳入组级版本化 + pending 隔离，见下方 📌）

| 固定写入字段 | 固定值 / 来源 |
|------------|--------------|
| `system_type` | `QUOTE` |
| `discount_type` | `FINISHED` |
| `customer_no` | **由系统导入时提供**（repair-0804 新增维度，改造前无客户维度，见下方 📌） |
| `target_no` | 恒 `NULL`（整单级年降，无挂载目标） |

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 宏丰料号 | `material_no` | ✅ | 销售料号 |
| 年降顺序 | `discount_order` | ✅ | **必填**（Phase 1 `QuoteImportValidator` 拦截空值；组内行集维度） |
| 年降系数（%/年） | `discount_ratio` | ✅ | 年降系数(%)，与「单次固定年降金额」二选一填写 |
| 单次固定年降金额 | `fixed_discount_value` | ✅ | 单次年降值，与「年降系数（%/年）」二选一填写 |
| 货币 | `currency` | ✅ | 货币 |
| 计价单位 | `unit` | ✅ | 计价单位 |
| 降价次数 | `discount_times` | ✅ | 降价次数 |

> 📌 `discount_type=FINISHED` 固定写入；年降系数（%）与单次固定年降金额二选一填写；本 Sheet 无「项次」列，`seq_no` 恒 NULL。
>
> ⚠️ **写入语义变更（repair-0804）**：改造前按 `material_no` **行级 upsert**（`ON CONFLICT DO UPDATE SET col = COALESCE(EXCLUDED.col, existing.col)`，空值不覆盖旧值）；现改为与 §8/§15 一致的**组级版本化整组替换**——同一 `(customer_no, material_no)` 组内，本次导入的行集即当前状态，Excel 留空的列**不再保留旧值**，重导前请确认整组数据齐全。
>
> ⚠️ **客户维度补齐（repair-0804）**：改造前 `annual_discount` 无 `customer_no` 列，同一销售料号导给不同客户会按 `material_no` 撞唯一键**静默互相覆盖**；现纳入 `customer_no` 维度，不同客户各自独立成组。
>
> ⚠️ **pending 隔离补齐（repair-0804）**：改造前 `annual_discount` 无 `pending_quotation_id` 列，导入在报价单 pending 期即直落正表、立即对所有报价单生效；现与其余 V6 版本化表一致纳入 pending 隔离（导入期 `is_current=false` + `pending_quotation_id`），待核价通过回填后才转正、对外可见。

---

## 三、通用落库规则

| 规则项 | 说明 |
|--------|------|
| **客户编号自动提供** | 凡目标表中存在 `customer_no` 字段的 Sheet，客户编号均由系统在导入时自动提供，Excel 文件中不维护此字段，程序不从 Excel 读取。 |
| **system_type 固定值** | 所有报价系统导入数据的 `system_type` 固定写入 `QUOTE`。 |
| **price_type 与 cost_type 区别** | `price_type` 标识价格来源分类，`cost_type` 标识费用用途分类，两字段独立写入 `unit_price`。**2026-06-08 起 price_type 大类 `MATERIAL`/`COMPONENT` 已细分化并彻底废弃**，列直接存 9 个细分值，其中 **7 个在用 + 2 个已退役**：元素=`ELEMENT`（不在本次细分范围，在用）；来料类=`INCOMING_MATERIAL_PROCESS`(固定加工费，在用)/`INCOMING_MATERIAL_OTHER`(其他费用，在用)/~~`INCOMING_MATERIAL_REDUCTION`(年降)~~（**已退役，repair-0804 迁至 `annual_discount.discount_type=INCOMING_MATERIAL`**）/`INCOMING_MATERIAL_RECYCLE`(回收折扣，在用)；`PROCESS`(自制加工费，在用)；`FINISHED_MATERIAL_OTHER`(成品其他费用，在用)；`COMPONENT_OTHER`(组成件其他费用，在用)/~~`COMPONENT_REDUCTION`(组装加工费年降)~~（**已退役，repair-0804 迁至 `annual_discount.discount_type=ASSEMBLY_PROCESS`**）；`PLATING`(电镀费用，两条靠 cost_type 区分，在用)。`cost_type` 保持原样与细分 price_type 并存。**两个已退役枚举值的存量已清除，`chk_unit_price_type` CHECK 约束里保留枚举值不动**（动 CHECK 收益为零）。细分规则出处 `docs/superpowers/specs/2026-06-08-quote-price-type-subdivide-design.md`；退役规则出处 `dev-docs/task-0708-导入报价单和导入核价单的数据落库规则澄清/repair-0804-年降三sheet的入库规则/需求文档.md`。 |
| **动态 cost_type** | 来料其他费用、成品其他费用、组成件其他费用等 Sheet 中，`cost_type` 取自 Excel「要素名称」列动态写入，非固定值。 |
| **固定金额与比例区分** | `pricing_price` 存储固定金额，`cost_ratio` 存储比例（%），同一条记录两者互斥填写，以对应字段是否为空判断费用类型。 |
| **一行拆多条** | 「电镀费用」Sheet 每行拆分为两条 `unit_price` 记录（电镀加工费 + 电镀材料费）；其余 Sheet 一行对应一条记录（或主+子各一条）。 |
| **电镀条件判断** | 「电镀费用」Sheet：当电镀方案编号不为空时，该行整体跳过不导入，由系统根据电镀方案表自动计算结果。 |
| **元素BOM版本规则** | `element_bom` 的 `characteristic`（特性）默认写入 `2000`；当同一主件料号出现不同组件组成或用量时，特性版本号自动递增（+1）。 |
| **元素回收折扣为更新操作** | 「元素回收折扣」Sheet 落库时为更新操作，按 `(material_no, component_no, seq_no)` 匹配 `element_bom_item` 中特性最新版本的记录，更新 `recovery_discount` 字段。 |
| **bom_type 区分** | 物料BOM（来料）写入 `bom_type=MATERIAL`；组成件BOM（组装）写入 `bom_type=ASSEMBLY`；两者共用 `material_bom` 和 `material_bom_item` 表。 |
| **upsert 策略** | 料号表（`material_master`）按 `material_no` 做 upsert；其余表按各自唯一约束做 INSERT OR UPDATE。 |
| **布尔字段转换** | 「是否随材料价格波动」等文字值统一转换：是→1，否→0，以 TINYINT(1) 存储。 |
| **数据清洗** | 导入前过滤空行（所有关键字段均为空的行）；标题行、注释行、说明行不导入。 |
| **多表写入顺序** | 建议写入顺序：料号表 → 料号关系表 → BOM主表 → BOM子表 → 单价表 → 年降系数表，以保证外键约束。 |

---

*文档完*
