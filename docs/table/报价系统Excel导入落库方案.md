# 报价系统基础数据 Excel 导入落库方案

> 版本：V3.4 | 日期：2026-07-08
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
| 8 | 来料年降 | `unit_price` | `INCOMING_MATERIAL_REDUCTION` | `年降系数` |
| 9 | 来料回收折扣 | `unit_price` | `INCOMING_MATERIAL_RECYCLE` | `回收折扣` |
| 10 | 自制加工费 | `unit_price` | `PROCESS` | `自制加工费` |
| 11 | 成品其他费用 | `unit_price` | `FINISHED_MATERIAL_OTHER` | `要素名称（动态）` |
| 12 | 组成件BOM | `material_bom`（主表） + `material_bom_item`（子表） | — | — |
| 13 | 组成件其他费用 | `unit_price` | `COMPONENT_OTHER` | `要素名称（动态）` |
| 14 | 组装加工费 | `capacity` | — | — |
| 15 | 组装加工费年降 | `unit_price` | `COMPONENT_REDUCTION` | `年降系数` |
| 16 | 电镀方案 | `plating_scheme` | — | — |
| 17 | 电镀费用（加工费） | `unit_price` | `PLATING` | `电镀加工费` |
| 17 | 电镀费用（材料费） | `unit_price` | `PLATING` | `电镀材料费` |
| 18 | 单重 | `material_master` | — | — |
| 19 | 年降系数 | `annual_discount` | — | — |

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
| 产出料号类型 | `component_usage_type` | ✅ | 只存汉字（剥离"N."编号）：银点类 / 非银点类 / 组成件 / 边角料 |
| 材料毛重 | `rough_weight` | ✅ | 毛重                                    |
| 材料净重 | `net_weight` | ✅ | 净重 |
| 重量单位 | `weight_unit` | ✅ | 重量单位 |
| 损耗率（%） | `scrap_rate` | ✅ | |
| 不良率（%） | `defect_rate` | ✅ | |

#### → 料号表（material_master）同步

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 投入料号 | `material_no` | ✅ | 按 upsert 写入料号表 |
| 产出料号类型 | `material_type` | ✅ | 只写汉字（剥离"N."编号）：银点类 / 非银点类 / 组成件 / 边角料 |
| 投入料号名称 | material_name | ✅ | 料件名称 |

> 📌 `system_type=QUOTE`，`bom_type=MATERIAL` 固定写入主表；客户编号由系统自动提供。投入料号同步 upsert 至料号表，写入 `material_type`，**只写汉字**（剥离 N. 编号）；组成件BOM 侧固定写「组成件」。
>
> 20260615更新:
>
> 如果导入时的料号名称为空,需要先查询料号表是否有名称相同的料件名称,如果有的话可以根据料件名称进行upsert,如果料件名称也不存在则根据规则自动生成一个料号进行存储.
>
> 自动生成料号的规则暂时约定为: 十位数 9000000000进行递增, 作标记以后可能进行规则修改.

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

> ✅ **2026-06-17 实现**：本 Sheet「投入料号/组成件料号」为空+名称有值时，按名称匹配料号表 / 匹配不到自动生成 9 字头料号并登记料号表(material_type=组成件)，再回填键列继续落库；§5 为更新型仅匹配不生成（详见 `docs/superpowers/plans/2026-06-17-quote-import-materialno-autogen-extend.md`）。

**目标表：** `unit_price`

| 固定写入字段 | 固定值 / 来源 |
|------------|--------------|
| `system_type` | `QUOTE` |
| `price_type` | `INCOMING_MATERIAL_REDUCTION` |
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

> ✅ **2026-06-17 实现**：本 Sheet「投入料号/组成件料号」为空+名称有值时，按名称匹配料号表 / 匹配不到自动生成 9 字头料号并登记料号表(material_type=组成件)，再回填键列继续落库；§5 为更新型仅匹配不生成（详见 `docs/superpowers/plans/2026-06-17-quote-import-materialno-autogen-extend.md`）。

**目标表：** `unit_price`

| 固定写入字段 | 固定值 / 来源 |
|------------|--------------|
| `system_type` | `QUOTE` |
| `price_type` | `INCOMING_MATERIAL_RECYCLE` |
| `cost_type` | `回收折扣` |
| `customer_no` | **由系统导入时提供** |

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 宏丰料号（成品料号） | `finished_material_no` | ✅ | 成品料号 |
| 项次 | `—` | ❌ | 不导入 |
| 投入料号 | `code` | ✅ | 元素代码/材料料号/零件号/耗材料号 |
| 投入料号名称 | — | ❌ | 不导入 |
| 回收折扣（%） | `cost_ratio` | ✅ | 比例 |

> 📌 `cost_type=回收折扣`，`price_type=INCOMING_MATERIAL_RECYCLE` 固定写入；客户编号由系统自动提供。

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
| `price_type` | `COMPONENT_REDUCTION` |
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

> 📌 `price_type=COMPONENT_REDUCTION`，`cost_type=年降系数` 固定写入；年降系数（%）与单次固定年降值二选一填写。客户编号由系统自动提供；降价次数不导入。

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
| `price_type` | `PLATING`（电镀） |
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
| **price_type 与 cost_type 区别** | `price_type` 标识价格来源分类，`cost_type` 标识费用用途分类，两字段独立写入 `unit_price`。**2026-06-08 起 price_type 大类 `MATERIAL`/`COMPONENT` 已细分化并彻底废弃**，列直接存 9 个细分值：元素=`ELEMENT`（不在本次细分范围）；来料类=`INCOMING_MATERIAL_PROCESS`(固定加工费)/`INCOMING_MATERIAL_OTHER`(其他费用)/`INCOMING_MATERIAL_REDUCTION`(年降)/`INCOMING_MATERIAL_RECYCLE`(回收折扣)；`PROCESS`(自制加工费)；`FINISHED_MATERIAL_OTHER`(成品其他费用)；`COMPONENT_OTHER`(组成件其他费用)/`COMPONENT_REDUCTION`(组装加工费年降)；`PLATING`(电镀费用，两条靠 cost_type 区分)。`cost_type` 保持原样与细分 price_type 并存。详见 `docs/superpowers/specs/2026-06-08-quote-price-type-subdivide-design.md`。 |
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
