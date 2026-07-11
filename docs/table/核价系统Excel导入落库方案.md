# 核价系统基础数据 Excel 导入落库方案

> 版本：V6.3 | 日期：2026-07-08
>
> **V6.3（2026-07-08）· 料号语义纠偏（废弃 V6.2 的 sales_part_no 反向设计）**：统一报价/核价两条路径的料号口径——
> - `material_no` **承载销售料号（主料号）**：核价各 P* handler 由读「宏丰料号」改读 **`销售料号`**（旧名回退兼容）落 `material_no`；`P04PricingVersionHandler`（核价版本→`material_version_mgmt`，不在 11 表）同样必须改读列，否则列改名后整表导入失败。
> - 新增 `production_no` **承载生产料号**（描述列，**不进唯一键**）：`宏丰-客户料号对应关系/物料BOM/产能/设备折旧/生产能耗/辅助能耗/模具工装/生产耗材BOM/包装材料BOM/来料*/加工费&组装费/成品其他*/电镀成本/其他外加工` 等 Sheet 读 `生产料号` 落对应表 `production_no`（unit_price/capacity/labor_rate/production_energy/auxiliary_energy/tooling_cost/material_customer_map/material_bom_item）。
> - **`物料与元素BOM` 例外**：材质料号源列名仍为「物料料号」→ 落 `element_bom.material_part_no`（材质料号，新增列并纳入唯一键 `(system_type, customer_no, material_no, material_part_no, characteristic)`）；`production_no` NULL（该 Sheet 无生产料号列）。同一销售料号下多材质料号各自独立成 BOM/版本，`P07` 分组键改为 `(material_no, material_part_no)`。
> - **唯一键 & 升版**：恢复到引入 sales_part_no 之前的键结构（迁移 `V315` `DROP COLUMN sales_part_no` + 去唯一索引 `COALESCE(sales_part_no,'')` 后缀），升版 groupKey 按 `material_no`（销售料号）；element_bom 两表额外把 `material_part_no` 纳入唯一键。**V6.2 的 `sales_part_no` 维度整体废弃**（该 spec `docs/superpowers/specs/2026-07-07-核价销售料号维度落库-design.md` 作废）。
> - `汇总` Sheet **不导入**（无对应 handler，`costing_summary` 由 `CostingSummaryService.compute()` 算出，键 `hf_part_no`，本次不动）。
>
> **V6.1（2026-06-30）**：补齐 `unit_price.price_type` 细分化（2026-06-30 代码已生效，本文档此前漏更）——原超载大类 `MATERIAL` 在核价写入端废弃，各费用 Sheet 直接写 7 个细分值（材料核价价格=`MATERIAL_PRICE`、包装=`PACKAGING`、来料加工费=`INCOMING_PROCESS`、来料其他费用比例+固定合并=`INCOMING_OTHER`、自制加工费=`SELF_PROCESS`、成品其他比例+固定合并=`FINISHED_OTHER`、其他外加工=`OUTSOURCE_PROCESS`、电镀两条=`PLATING`）；`ELEMENT`（元素核价价格表）/`CONSUMABLE`（生产耗材BOM）已与 Sheet 1:1 **保留不动**；`cost_type` 全程不变。比例/固定不进 price_type，靠 `cost_ratio` vs `pricing_price` 哪列有值区分。规则出处 `docs/superpowers/specs/2026-06-30-pricing-unit-price-source-enum-design.md`（DDL=`V306`，常量类=`PricingPriceType`）。
>
> **V6.2（2026-07-07）**：**销售料号（报价料号）贯穿维度并入**——核价 Excel 新增必填列「销售料号」（= 报价料号 `XXXX-YYMMNNNNNN`），原「宏丰料号」列**改名「生产料号」**（落库列 `material_no`/`finished_material_no`/`code` 口径不变，仅表头改名 + handler 兼容读）。19 个"按料号" Sheet 新增落 `sales_part_no` 列并进唯一键 + 升版 groupKey；5 个全局/元素级 Sheet（元素价格/材料价格/汇率/电镀方案/核价版本）不加。**并"一步到位"把费用类 `unit_price`（P13/P14/P15–P20/P22/P23，其中 P15–P20/P23 原为 `V_DEFAULT` 无版本）纳入统一升版（2000 起版 + `is_current` 翻转，忽略 Excel 版本列）**。详见 **§四（销售料号维度）+ §五（统一升版机制）**；术语与三码模型对齐 `docs/superpowers/specs/2026-07-06-报价料号统一-design.md`。

---

## 目录

- [一、总览](#一总览)
- [二、各 Sheet 落库详细说明](#二各-sheet-落库详细说明)
  - [1. 元素核价价格表](#1-元素核价价格表)
  - [2. 材料核价价格表](#2-材料核价价格表)
  - [3. 汇率管理表](#3-汇率管理表)
  - [4. 核价版本](#4-核价版本)
  - [5. 宏丰-客户料号对应关系](#5-宏丰-客户料号对应关系)
  - [6. 物料BOM](#6-物料bom)
  - [7. 物料与元素BOM](#7-物料与元素bom)
  - [8. 产能](#8-产能)
  - [9. 设备折旧成本](#9-设备折旧成本)
  - [10. 生产设备能耗](#10-生产设备能耗)
  - [11. 辅助设备能耗](#11-辅助设备能耗)
  - [12. 模具工装成本](#12-模具工装成本)
  - [13. 生产耗材BOM](#13-生产耗材bom)
  - [14. 包装材料BOM](#14-包装材料bom)
  - [15. 来料加工费](#15-来料加工费)
  - [16. 来料其他费用（比例）](#16-来料其他费用比例)
  - [17. 来料其他固定费用](#17-来料其他固定费用)
  - [18. 加工费&组装费](#18-加工费组装费)
  - [19. 成品其他比例费用](#19-成品其他比例费用)
  - [20. 成品其他固定费用](#20-成品其他固定费用)
  - [21. 电镀方案](#21-电镀方案)
  - [22. 电镀成本](#22-电镀成本)
  - [23. 其他外加工成本](#23-其他外加工成本)
  - [24. 单重](#24-单重)
- [三、通用落库规则](#三通用落库规则)
- [四、销售料号（报价料号）贯穿维度](#四销售料号报价料号贯穿维度)
- [五、统一升版机制（一步到位）](#五统一升版机制一步到位)

---

## 一、总览

共 24 个有效 Sheet（忽略「汇总」Sheet），均落入 `unit_price` 或其他目标表。

> **字段说明**
> - `price_type`（价格类型）：标识该条记录的价格来源/Sheet 分类。2026-06-30 起原超载大类 `MATERIAL` 已按 Sheet 细分为 `MATERIAL_PRICE`/`PACKAGING`/`INCOMING_PROCESS`/`INCOMING_OTHER`/`SELF_PROCESS`/`FINISHED_OTHER`/`OUTSOURCE_PROCESS`/`PLATING`；`ELEMENT`/`CONSUMABLE` 保留
> - `cost_type`（费用类型）：标识该条价格记录的费用用途分类，如 `元素核价价格`、`自制加工费`、`电镀加工费` 等
> - 两个字段相互独立，均写入 `unit_price` 表，共同描述一条价格记录的分类维度

| # | Excel Sheet | 目标数据库表 | price_type（价格类型） | cost_type（费用类型） |
|:-:|-------------|-------------|----------------------|---------------------|
| 1 | 元素核价价格表 | `unit_price` | `ELEMENT 元素` | `元素核价价格` |
| 2 | 材料核价价格表 | `unit_price` | `MATERIAL_PRICE` | `材料核价价格` |
| 3 | 汇率管理表 | `exchange_rate` | — | — |
| 4 | 核价版本 | `material_version_mgmt` | — | — |
| 5 | 宏丰-客户料号对应关系 | `material_customer_map` + `material_master` | — | — |
| 6 | 物料BOM | `material_bom` + `material_bom_item` + `material_master`（20260705 起同步登记父件/组成料号） | — | — |
| 7 | 物料与元素BOM | `element_bom` + `element_bom_item` | — | — |
| 8 | 产能 | `capacity` + `labor_rate` | — | — |
| 9 | 设备折旧成本 | `production_energy` | — | — |
| 10 | 生产设备能耗 | `production_energy` | — | — |
| 11 | 辅助设备能耗 | `auxiliary_energy` | — | — |
| 12 | 模具工装成本 | `tooling_cost` | — | — |
| 13 | 生产耗材BOM | `unit_price` | `CONSUMABLE 耗材` | `耗材` |
| 14 | 包装材料BOM | `unit_price` | `PACKAGING` | `包装` |
| 15 | 来料加工费 | `unit_price` | `INCOMING_PROCESS` | `来料加工费` |
| 16 | 来料其他费用（比例） | `unit_price` | `INCOMING_OTHER` | `要素名称（动态）` |
| 17 | 来料其他固定费用 | `unit_price` | `INCOMING_OTHER` | `要素名称（动态）` |
| 18 | 加工费&组装费 | `unit_price` | `SELF_PROCESS` | `自制加工费` |
| 19 | 成品其他比例费用 | `unit_price` | `FINISHED_OTHER` | `要素名称（动态）` |
| 20 | 成品其他固定费用 | `unit_price` | `FINISHED_OTHER` | `要素名称（动态）` |
| 21 | 电镀方案 | `plating_scheme` | — | — |
| 22 | 电镀成本（加工费） | `unit_price` | `PLATING` | `电镀加工费` |
| 22 | 电镀成本（材料费） | `unit_price` | `PLATING` | `电镀材料费` |
| 23 | 其他外加工成本 | `unit_price` | `OUTSOURCE_PROCESS` | `其他加工费` |
| 24 | 单重 | `material_master` | — | — |

> **V6.2 列变更（适用于下方全部明细表）**：
> - Excel 原「宏丰料号」列 → 统一改名「**生产料号**」；落库列（`material_no`/`finished_material_no`/`code`）**口径不变**，handler 读列改「生产料号」优先、回退「宏丰料号」（**例外**：Sheet 7「物料与元素BOM」料号列真实表头是「物料料号」，未参与改名，不适用回退）。
> - 上表第 5、6、7、8、9、10、11、12、13、14、15、16、17、18、19、20、22、23、24 号（19 个"按料号" Sheet）新增必填列「**销售料号**」→ 落 `sales_part_no`，进唯一键 + 升版 groupKey（详见 §四/§五）。第 1、2、3、4、21 号为全局/元素级，**不加**销售料号。

---

## 二、各 Sheet 落库详细说明

---

### 1. 元素核价价格表

**目标表：** `unit_price`

| 固定写入字段 | 固定值 |
|------------|--------|
| `system_type` | `PRICING` |
| `price_type` | `ELEMENT`（元素） |
| `cost_type` | `元素核价价格` |

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 元素代码 | `code` | ✅ | 元素代码/材料料号/零件号/耗材料号 |
| 核价单价 | `pricing_price` | ✅ | 费用(固定) |
| 市场参考价 | `market_ref_price` | ✅ | |
| 参考价来源网址 | `source_url` | ✅ | 抓取网址 |
| 网站名称 | `source_name` | ✅ | |
| 参考价取用规则 | `fetch_rule` | ✅ | 取用规则 |
| 币种 | `currency` | ✅ | |
| 计量单位 | `unit` | ✅ | |
| 回收折扣（%） | `recovery_discount` | ✅ | 回收折扣(%) |
| 元素价格版本 | `version_no` | ✅ | 价格版本 |

> 📌 每行生成一条 `unit_price` 记录；`system_type=PRICING`，`price_type=ELEMENT`，`cost_type=元素核价价格` 由系统固定写入。

---

### 2. 材料核价价格表

**目标表：** `unit_price`

| 固定写入字段 | 固定值 |
|------------|--------|
| `system_type` | `PRICING` |
| `price_type` | `MATERIAL_PRICE` |
| `cost_type` | `材料核价价格` |

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 材料料号 | `code` | ✅ | 元素代码/材料料号/零件号/耗材料号 |
| 品名 | — | ❌ | 不需要导入 |
| 规格 | — | ❌ | 不需要导入 |
| 尺寸 | — | ❌ | 不需要导入 |
| 核价单价 | `pricing_price` | ✅ | 费用(固定) |
| 市场参考价 | `market_ref_price` | ✅ | |
| 参考价来源网址 | `source_url` | ✅ | 抓取网址 |
| 网站名称 | `source_name` | ✅ | |
| 参考价取用规则 | `fetch_rule` | ✅ | 取用规则 |
| 币种 | `currency` | ✅ | |
| 计量单位 | `unit` | ✅ | |
| 回收折扣（%） | `recovery_discount` | ✅ | 回收折扣(%) |
| 材料价格版本 | `version_no` | ✅ | 价格版本 |

> 📌 品名、规格、尺寸列不导入。`system_type=PRICING`，`price_type=MATERIAL_PRICE`，`cost_type=材料核价价格` 由系统写入。

---

### 3. 汇率管理表

**目标表：** `exchange_rate`

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 基础货币 | `base_currency` | ✅ | |
| 核价货币 | `target_currency` | ✅ | 目标货币 |
| 核价汇率 | `rate` | ✅ | 汇率 |
| 参考汇率 | `ref_rate` | ✅ | |
| 参考汇率数据抓取规则 | `ref_fetch_rule` | ✅ | 参考汇率抓取规则 |
| 抓取网址 | `ref_source_url` | ✅ | |
| 汇率版本 | `version_no` | ✅ | |

> 📌 每行生成一条 `exchange_rate` 记录，全部字段均导入。

---

### 4. 核价版本

**目标表：** `material_version_mgmt`

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 生产料号 | `material_no` | ✅ | 料号 |
| 品名 | — | ❌ | 不需要导入 |
| 规格 | — | ❌ | 不需要导入 |
| 尺寸 | — | ❌ | 不需要导入 |
| 项次 | `seq_no` | ✅ | |
| 核价版本编号 | `pricing_version_no` | ✅ | |
| 核价版本名称 | `pricing_version_name` | ✅ | |
| 元素价格版本 | `element_price_version` | ✅ | |
| 材料价格版本 | `material_price_version` | ✅ | |
| 汇率价格版本 | `exchange_rate_version` | ✅ | |
| 是否生效 | `is_effective` | ✅ | 是→1，否→0 |

> 📌 品名、规格、尺寸不导入；是否生效转换为 TINYINT：是=1，否=0。

---

### 5. 宏丰-客户料号对应关系

**目标表：** `material_customer_map`（主） + `material_master`（同时写入）

#### → 料号关系表（material_customer_map）

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 销售料号 | `sales_part_no` | ✅ | 报价料号（必填）；三码之一（见 §4.4） |
| 生产料号 | `material_no` | ✅ | 料号 |
| 品名 | — | ❌ | 不需要导入 |
| 规格 | — | ❌ | 不需要导入 |
| 尺寸 | — | ❌ | 不需要导入 |
| 旧料号 | — | ❌ | 不需要导入（旧料号由料号表维护） |
| 项次 | `seq_no` | ✅ | |
| 客户编号 | `customer_no` | ✅ | |
| 客户名称 | `customer_name` | ✅ | |
| 客户产品编号 | `customer_product_no` | ✅ | |

#### → 料号表（material_master）

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 生产料号 | `material_no` | ✅ | 料号（业务唯一） |
| 品名 | `material_name` | ✅ | |
| 规格 | `specification` | ✅ | |
| 尺寸 | `dimension` | ✅ | |
| 旧料号 | `old_material_no` | ✅ | |
| 项次 | — | ❌ | 不需要导入 |
| 客户编号 | — | ❌ | 不需要导入 |
| 客户名称 | — | ❌ | 不需要导入 |
| 客户产品编号 | — | ❌ | 不需要导入 |

> 📌 同一 Sheet 同时向两张表落库，生产料号为关联键；料号表按 material_no upsert。

---

### 6. 物料BOM

**目标表：** `material_bom`（主表） + `material_bom_item`（子表）

| 固定写入字段 | 固定值 |
|------------|--------|
| `system_type` | `PRICING` |

#### → 物料BOM主表（material_bom）

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 销售料号 | `sales_part_no` | ✅ | 报价料号（必填）；进唯一键 + 升版 groupKey（见 §四/§五） |
| 生产料号 | `material_no` | ✅ | 主件料号 |

> 其余头表字段（`bom_type`、`bom_version` 等）由系统写入默认值或另行配置。

#### → 物料BOM子表（material_bom_item）

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 生产料号 | `material_no` | ✅ | 主件料号 |
| 项次 | `seq_no` | ✅ | |
| 组成料号 | `component_no` | ✅ | 组件料号 |
| 品名 | — | ❌ | 不导入 |
| 规格 | — | ❌ | 不导入 |
| 尺寸 | — | ❌ | 不导入 |
| 工序编号 | `operation_no` | ✅ | 作业编号 |
| 工序名称 | — | ❌ | 不导入 |
| 使用特性 | `component_usage_type` | ✅ | 元件使用特性 |
| 组成用量 | `composition_qty` | ✅ | |
| 组成用量单位 | `issue_unit` | ✅ | 发料单位 |
| 底数 | `base_qty` | ✅ | 主件底数 |
| 底数单位 | — | ❌ | 不导入 |
| 材料损耗率（%） | `scrap_rate` | ✅ | 损耗率 |
| 材料固定损耗量 | `fixed_scrap` | ✅ | 固定损耗 |
| 不良率（%） | `defect_rate` | ✅ | 不良率(%) |
| 计算类型 | `calc_type` | ✅ | |

> 📌 `system_type=PRICING`、`bom_type=MATERIAL` 固定写入主表；子表与主表保持一致。BOM 数据来源于 ERP，导入时需抓取成品料号往下所有级组成料号。

#### 20260705 更新：物料BOM 导入时同步登记料号表（material_master）

**背景 / 动机**：组成料号（`component_no`）常常只作为子件出现在 BOM 里，从未在「宏丰-客户料号对应关系」（Sheet 5）单独登记过 → 料号表 `material_master` 无此料号行 → 核价树递归展开出该子件节点时，下游页签 `$view` join 出的 品名/规格/尺寸/单重全为空。本次优化：**导入 物料BOM Sheet 时，额外把父件与所有组成料号 upsert 进 `material_master`，补齐显示用主数据**。

> ⚠️ 本同步**只影响 `material_master`**，不改变 BOM 树结构——树结构来自 `material_bom_item` 的递归 SQL，与 `material_master` 无关（见 `docs/核价树页签组件配置指南.md`）。收益点正是让树上子件节点的名称/规格/尺寸不再为空。

##### 同步 1 —— 父件（生产料号）→ material_master

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 生产料号 | `material_no` | ✅ | 仅裸登记 `material_no`（本 Sheet 无父件名称列）；不写名称/规格/尺寸 |

> 📌 父件通常是成品/半成品料号，其 品名/规格/尺寸 由 Sheet 5（客户料号对应关系）或它自身作为别处子件出现时补齐；此处只保证料号行存在，名称为空可接受。

##### 同步 2 —— 组成料号 → material_master

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 组成料号 | `material_no` | ✅ | upsert 主键 |
| 品名 | `material_name` | ✅ | **仅回填空白**（见下方覆盖语义）|
| 规格 | `specification` | ✅ | **仅回填空白** |
| 尺寸 | `dimension` | ✅ | **仅回填空白** |
| 使用特性 | — | ❌ | 不回填 `material_type`/`usage_property`（使用特性是 BOM 边级语义，≠ 料号级材料类型），仍按 §6 子表规则写入 `material_bom_item.component_usage_type` |

> ⚠️ **组成料号的 品名/规格/尺寸 只进 `material_master`，不进 `material_bom_item`**——子表这三列仍按 §6 保持 ❌ 不导入。

**覆盖语义（关键，务必按此实现）**：
- 组成料号的 品名/规格/尺寸 采用 **「仅回填空白」**（`preserveDescriptive=true`：料号表已有值就保留，只填补原本为 NULL/空 的列），**不得覆盖** Sheet 5 写入的权威名称。
  - 原因：导入执行顺序为 Sheet 5（客户料号对应关系，权威名称，非空覆盖）先跑 → 物料BOM 后跑；若 BOM 用「非空覆盖」，会用 BOM 里可能简写/粗糙的名称盖掉 Sheet 5 的权威名称。
- 其余列（如别的 Sheet 负责的 `unit_weight` 等）走 `material_master` 既有的**列级 COALESCE** 合并：各 Sheet 只更新自己那几列、传空的列不清空他表已写的值，三处（Sheet 5 名称 / Sheet 24 单重 / 本次 BOM）天然共存。

**落库前去重**：同一组成料号会在多父件、多 occurrence 下重复出现，upsert 前**必须按 `material_no` 去重**（PG 同一条 INSERT 不能命中同一冲突键两次）；多次出现名称不一致时取「首个非空」归并。



---

### 7. 物料与元素BOM

**目标表：** `element_bom`（主表） + `element_bom_item`（子表）

| 固定写入字段 | 固定值 |
|------------|--------|
| `system_type` | `PRICING` |

#### → 元素BOM主表（element_bom）

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 销售料号 | `sales_part_no` | ✅ | 报价料号（必填）；进唯一键 + 升版 groupKey（见 §四/§五） |
| 物料料号 | `material_no` | ✅ | 主件料号 |

#### → 元素BOM子表（element_bom_item）

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 物料料号 | `material_no` | ✅ | 主件料号 |
| 品名 | — | ❌ | 不导入 |
| 规格 | — | ❌ | 不导入 |
| 尺寸 | — | ❌ | 不导入 |
| 项次 | `seq_no` | ✅ | |
| 元素代码 | `component_no` | ✅ | 组件料号（元素代码归此字段） |
| 组成含量（%） | `content` | ✅ | 含量 |
| 损耗率（%） | `scrap_rate` | ✅ | 损耗率 |

> 📌 只维护「计算类型=按元素」的来料及边角料的元素BOM；`system_type=PRICING` 固定写入。

---

### 8. 产能

**目标表：** `capacity` + `labor_rate`（同时写入两张表）

#### → 产能表（capacity）

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 销售料号 | `sales_part_no` | ✅ | 报价料号（必填）；进唯一键 + 升版 groupKey（见 §四/§五） |
| 生产料号 | `material_no` | ✅ | 料号 |
| 品名 | — | ❌ | 不导入 |
| 规格 | — | ❌ | 不导入 |
| 尺寸 | — | ❌ | 不导入 |
| 工序编号 | `process_no` | ✅ | |
| 工序名称 | — | ❌ | 不导入 |
| 人工标准单价 | — | ❌ | 不导入（由工时单价表维护） |
| 币种 | — | ❌ | 不导入 |
| 计量单位 | — | ❌ | 不导入 |
| 取用的计算版本 | `calc_version` | ✅ | 计算版本 |
| 是否有效 | `is_effective` | ✅ | 是→1，否→0 |

#### → 工时单价表（labor_rate）

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 生产料号 | `material_no` | ✅ | 料号 |
| 工序编号 | `process_no` | ✅ | |
| 人工标准单价 | `standard_labor_rate` | ✅ | 标准工时单价 |
| 币种 | `currency` | ✅ | |
| 计量单位 | `unit` | ✅ | 计价单位 |
| 取用的计算版本 | `version_no` | ✅ | 工时单价版本 |

> 📌 因不同月计算单价可能不同，需手动选取版本；是否有效转换为 TINYINT(1)。

---

### 9. 设备折旧成本

**目标表：** `production_energy`

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 销售料号 | `sales_part_no` | ✅ | 报价料号（必填）；进唯一键（本表不升版，见 §五 B 类） |
| 生产料号 | `material_no` | ✅ | 料号 |
| 品名 | — | ❌ | 不导入 |
| 规格 | — | ❌ | 不导入 |
| 尺寸 | — | ❌ | 不导入 |
| 工序编号 | `process_no` | ✅ | |
| 工序名称 | — | ❌ | 不导入 |
| 折旧单价 | `depreciation_unit_price` | ✅ | 设备折旧单价 |
| 币种 | `currency` | ✅ | |
| 计量单位 | `unit` | ✅ | |
| 取用的计算版本 | `calc_version` | ✅ | 计算版本 |
| 是否有效 | — | ❌ | 不导入（由产能表 `is_effective` 控制） |

> 📌 折旧单价写入 `production_energy.depreciation_unit_price`；与「生产设备能耗」通过 `(material_no, process_no, calc_version)` 合并写入同一张表。

---

### 10. 生产设备能耗

**目标表：** `production_energy`

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 销售料号 | `sales_part_no` | ✅ | 报价料号（必填）；进唯一键（本表不升版，见 §五 B 类） |
| 生产料号 | `material_no` | ✅ | 料号 |
| 品名 | — | ❌ | 不导入 |
| 规格 | — | ❌ | 不导入 |
| 尺寸 | — | ❌ | 不导入 |
| 工序编号 | `process_no` | ✅ | |
| 工序名称 | — | ❌ | 不导入 |
| 生产能耗单价 | `energy_unit_price` | ✅ | |
| 币种 | `currency` | ✅ | |
| 计量单位 | `unit` | ✅ | |
| 取用的计算版本 | `calc_version` | ✅ | 计算版本 |
| 是否有效 | — | ❌ | 不导入 |

> 📌 与「设备折旧成本」Sheet 落入同一张表 `production_energy`，通过 `(material_no, process_no, calc_version)` 关联合并写入。

---

### 11. 辅助设备能耗

**目标表：** `auxiliary_energy`

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 销售料号 | `sales_part_no` | ✅ | 报价料号（必填）；进唯一键（本表不升版，见 §五 B 类） |
| 生产料号 | `material_no` | ✅ | 料号 |
| 品名 | — | ❌ | 不导入 |
| 规格 | — | ❌ | 不导入 |
| 尺寸 | — | ❌ | 不导入 |
| 工序编号 | `process_no` | ✅ | |
| 工序名称 | — | ❌ | 不导入 |
| 非生产能耗单价 | `non_production_energy_price` | ✅ | |
| 币种 | `currency` | ✅ | |
| 计量单位 | `unit` | ✅ | |
| 取用的计算版本 | `calc_version` | ✅ | 计算版本 |
| 是否有效 | — | ❌ | 不导入 |

> 📌 非生产能耗（照明/空调等）因每月使用频率不同需手动选版本，摊销逻辑由系统基于工时计算。

---

### 12. 模具工装成本

**目标表：** `tooling_cost`

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 销售料号 | `sales_part_no` | ✅ | 报价料号（必填）；进唯一键（本表不升版，见 §五 B 类） |
| 生产料号 | `material_no` | ✅ | 料号 |
| 品名 | — | ❌ | 不导入 |
| 规格 | — | ❌ | 不导入 |
| 尺寸 | — | ❌ | 不导入 |
| 工序编号 | `process_no` | ✅ | |
| 工序名称 | — | ❌ | 不导入 |
| 项次 | `seq_no` | ✅ | |
| 模具台账/工装编号 | `tooling_no` | ✅ | 模具/工装编号 |
| 单个模具/工装成本 | `tooling_unit_cost` | ✅ | |
| 寿命（次） | `tool_life` | ✅ | 寿命(次) |
| 单循环产量 | `cycle_output` | ✅ | |
| 模具工装成本单价 | `tooling_unit_price` | ✅ | 摊销后单价=模具工装成本单价 |
| 币种 | `currency` | ✅ | |
| 计量单位 | `unit` | ✅ | |
| 是否有效 | `is_effective` | ✅ | 有效否；是→1，否→0 |

> 📌 每个料号+工序下可有多条模具/工装记录（`seq_no` 区分）；`is_effective` 转为 TINYINT(1)。

---

### 13. 生产耗材BOM

**目标表：** `unit_price`

| 固定写入字段 | 固定值 |
|------------|--------|
| `system_type` | `PRICING` |
| `price_type` | `CONSUMABLE`（耗材） |
| `cost_type` | `耗材` |

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 销售料号 | `sales_part_no` | ✅ | 报价料号（必填）；进唯一键 + 升版 groupKey（见 §四/§五） |
| 生产料号（成品料号） | `finished_material_no` | ✅ | 成品料号 |
| 品名 | — | ❌ | 不导入 |
| 规格 | — | ❌ | 不导入 |
| 尺寸 | — | ❌ | 不导入 |
| 工序编号 | `operation_no` | ✅ | 作业编号 |
| 工序名称 | — | ❌ | 不导入 |
| 耗材成本单价 | `pricing_price` | ✅ | 费用(固定) |
| 币种 | `currency` | ✅ | |
| 计量单位 | `unit` | ✅ | |
| 取用的耗材版本 | `version_no` | ✅ | 价格版本 |
| 是否有效 | — | ❌ | 不导入 |

> 📌 不包含包装工序耗材（包装耗材在「包装材料BOM」Sheet 维护）。`price_type=CONSUMABLE` 与「包装材料BOM」的 `price_type=PACKAGING` 不同，通过 `price_type`/`cost_type` 区分耗材与包装用途。

---

### 14. 包装材料BOM

**目标表：** `unit_price`

| 固定写入字段 | 固定值 |
|------------|--------|
| `system_type` | `PRICING` |
| `price_type` | `PACKAGING` |
| `cost_type` | `包装` |

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 销售料号 | `sales_part_no` | ✅ | 报价料号（必填）；进唯一键 + 升版 groupKey（见 §四/§五） |
| 生产料号（成品料号） | `finished_material_no` | ✅ | 成品料号 |
| 品名 | — | ❌ | 不导入 |
| 规格 | — | ❌ | 不导入 |
| 尺寸 | — | ❌ | 不导入 |
| 工序编号 | `operation_no` | ✅ | 作业编号 |
| 工序名称 | — | ❌ | 不导入 |
| 包装成本单价 | `pricing_price` | ✅ | 费用(固定) |
| 币种 | `currency` | ✅ | |
| 计量单位 | `unit` | ✅ | |
| 取用的耗材版本 | `version_no` | ✅ | 价格版本 |
| 是否有效 | — | ❌ | 不导入 |

> 📌 包装材料写入独立细分值 `price_type=PACKAGING`（原归大类 MATERIAL），`cost_type=包装`。

---

### 15. 来料加工费

**目标表：** `unit_price`

| 固定写入字段 | 固定值 |
|------------|--------|
| `system_type` | `PRICING` |
| `price_type` | `INCOMING_PROCESS` |
| `cost_type` | `来料加工费` |

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 销售料号 | `sales_part_no` | ✅ | 报价料号（必填）；进唯一键 + 升版 groupKey（见 §四/§五） |
| 生产料号（成品料号） | `finished_material_no` | ✅ | 成品料号 |
| 项次 | — | ❌ | 不导入 |
| 来料料号 | `code` | ✅ | 元素代码/材料料号/零件号/耗材料号 |
| 品名 | — | ❌ | 不导入 |
| 规格 | — | ❌ | 不导入 |
| 尺寸 | — | ❌ | 不导入 |
| 加工费 | `pricing_price` | ✅ | 费用(固定) |
| 币种 | `currency` | ✅ | |
| 计量单位 | `unit` | ✅ | |
| 损耗（%） | `defect_rate` | ✅ | 不良率%（损耗字段） |

> 📌 只能维护外购来料的加工费（料号只能是初始来料）。损耗（%）映射至 `defect_rate` 字段。

---

### 16. 来料其他费用（比例）

**目标表：** `unit_price`

| 固定写入字段 | 固定值 |
|------------|--------|
| `system_type` | `PRICING` |
| `price_type` | `INCOMING_OTHER` |
| `cost_type` | 取自 Excel「要素编号」列（动态写入） |

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 销售料号 | `sales_part_no` | ✅ | 报价料号（必填）；进唯一键 + 升版 groupKey（见 §四/§五） |
| 生产料号（成品料号） | `finished_material_no` | ✅ | 成品料号 |
| 一级项次 | — | ❌ | 不导入 |
| 来料料号 | `code` | ✅ | 元素代码/材料料号/零件号/耗材料号 |
| 品名 | — | ❌ | 不导入 |
| 规格 | — | ❌ | 不导入 |
| 尺寸 | — | ❌ | 不导入 |
| 二级项次 | `seq_no` | ✅ | 序号 |
| 要素编号 | `cost_type` | ✅ | 费用类型（动态写入，存要素编号） |
| 要素名称 | — | ❌ | 不导入（通过要素编号关联费用配置表获取） |
| 比例（%） | `cost_ratio` | ✅ | 比例 |

> 📌 比例费用只能维护外购来料；`cost_type` 动态取自要素编号列，非固定值。要素名称不导入，通过要素编号关联查询。

---

### 17. 来料其他固定费用

**目标表：** `unit_price`

| 固定写入字段 | 固定值 |
|------------|--------|
| `system_type` | `PRICING` |
| `price_type` | `INCOMING_OTHER` |
| `cost_type` | 取自 Excel「要素名称」列（动态写入） |

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 销售料号 | `sales_part_no` | ✅ | 报价料号（必填）；进唯一键 + 升版 groupKey（见 §四/§五） |
| 生产料号（成品料号） | `finished_material_no` | ✅ | 成品料号 |
| 一级项次 | — | ❌ | 不导入 |
| 来料料号 | `code` | ✅ | 元素代码/材料料号/零件号/耗材料号 |
| 品名 | — | ❌ | 不导入 |
| 规格 | — | ❌ | 不导入 |
| 尺寸 | — | ❌ | 不导入 |
| 二级项次 | `seq_no` | ✅ | 序号 |
| 要素名称 | `cost_type` | ✅ | 费用类型（动态写入） |
| 费用 | `pricing_price` | ✅ | 费用(固定) |
| 币种 | `currency` | ✅ | |
| 计价单位 | `unit` | ✅ | 计量单位 |

> 📌 固定费用通过 `pricing_price` 存储，比例费用通过 `cost_ratio` 存储，两者以对应字段是否为空区分；只能维护外购来料。

---

### 18. 加工费&组装费

**目标表：** `unit_price`

| 固定写入字段 | 固定值 |
|------------|--------|
| `system_type` | `PRICING` |
| `price_type` | `SELF_PROCESS` |
| `cost_type` | `自制加工费` |

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 销售料号 | `sales_part_no` | ✅ | 报价料号（必填）；进唯一键 + 升版 groupKey（见 §四/§五） |
| 生产料号 | `code` | ✅ | 元素代码/材料料号/零件号/耗材料号（成品/半成品料号） |
| 品名 | — | ❌ | 不导入 |
| 规格 | — | ❌ | 不导入 |
| 尺寸 | — | ❌ | 不导入 |
| 工序编号 | `operation_no` | ✅ | 作业编号 |
| 工序名称 | — | ❌ | 不导入 |
| 加工费 | `pricing_price` | ✅ | 费用(固定) |
| 币种 | `currency` | ✅ | |
| 计量单位 | `unit` | ✅ | |
| 不良率/拒收率（%） | `defect_rate` | ✅ | 不良率% |

> 📌 加工费由人工+折旧+能耗+模具工装+耗材包装汇总得出，也可直接录入；适用于成品、内部半成品及原材料料号。`price_type=SELF_PROCESS`（原归大类 MATERIAL）标识自制加工费维度。

---

### 19. 成品其他比例费用

**目标表：** `unit_price`

| 固定写入字段 | 固定值 |
|------------|--------|
| `system_type` | `PRICING` |
| `price_type` | `FINISHED_OTHER` |
| `cost_type` | 取自 Excel「要素名称」列（动态写入） |

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 销售料号 | `sales_part_no` | ✅ | 报价料号（必填）；进唯一键 + 升版 groupKey（见 §四/§五） |
| 生产料号 | `code` | ✅ | 元素代码/材料料号/零件号/耗材料号 |
| 品名 | — | ❌ | 不导入 |
| 规格 | — | ❌ | 不导入 |
| 尺寸 | — | ❌ | 不导入 |
| 项次 | `seq_no` | ✅ | 序号 |
| 要素编号 | — | ❌ | 不导入 |
| 要素名称 | `cost_type` | ✅ | 费用类型（动态写入） |
| 比例（%） | `cost_ratio` | ✅ | 比例 |

> 📌 成品维度的比例附加费用，按要素名称动态写入 `cost_type`。

---

### 20. 成品其他固定费用

**目标表：** `unit_price`

| 固定写入字段 | 固定值 |
|------------|--------|
| `system_type` | `PRICING` |
| `price_type` | `FINISHED_OTHER` |
| `cost_type` | 取自 Excel「要素名称」列（动态写入） |

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 销售料号 | `sales_part_no` | ✅ | 报价料号（必填）；进唯一键 + 升版 groupKey（见 §四/§五） |
| 生产料号 | `code` | ✅ | 元素代码/材料料号/零件号/耗材料号 |
| 品名 | — | ❌ | 不导入 |
| 规格 | — | ❌ | 不导入 |
| 尺寸 | — | ❌ | 不导入 |
| 项次 | `seq_no` | ✅ | 序号 |
| 要素名称 | `cost_type` | ✅ | 费用类型（动态写入，如运费、清关费） |
| 费用 | `pricing_price` | ✅ | 费用(固定) |
| 币种 | `currency` | ✅ | |
| 计价单位 | `unit` | ✅ | 计量单位 |

> 📌 成品维度固定金额附加费用，通过 `cost_type` 动态区分类别（如运费、清关费等）。

---

### 21. 电镀方案

**目标表：** `plating_scheme`

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 方案编号 | `scheme_no` | ✅ | 电镀方案编号 |
| 版本 | `scheme_version` | ✅ | 电镀方案版本 |
| 项次 | `seq_no` | ✅ | |
| 电镀元素名称 | `plating_element` | ✅ | 电镀元素（如 Ag/Au/Ni/Sn/Cu） |
| 电镀面积（cm²） | `plating_area` | ✅ | 电镀面积 (cm²) |
| 镀层厚度（μm） | `plating_thickness` | ✅ | |
| 电镀要求 | `plating_requirement` | ✅ | 电镀要求/规格描述 |
| 密度（g/cm³） | `density` | ✅ | 密度 (g/cm³) |

> 📌 全量字段均导入；`element_usage`（理论元素用量）由系统根据 `plating_area × plating_thickness × density` 自动计算，不从 Excel 导入。

---

### 22. 电镀成本

**目标表：** `unit_price`（每行拆分为两条记录）

> ⚠️ **特殊规则：** 当电镀方案编号不为空时，该行整体跳过不导入，由系统根据电镀方案表自动计算。

#### → 第 1 条：电镀加工费

| 固定写入字段 | 固定值 |
|------------|--------|
| `system_type` | `PRICING` |
| `price_type` | `PLATING` |
| `cost_type` | `电镀加工费` |

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 销售料号 | `sales_part_no` | ✅ | 报价料号（必填）；进唯一键 + 升版 groupKey（见 §四/§五） |
| 生产料号 | `code` | ✅ | 元素代码/材料料号/零件号/耗材料号 |
| 电镀方案编号 | `plating_scheme_no` | ✅ | 不为空时整行跳过不导入 |
| 版本编号 | `version_no` | ✅ | 价格版本 |
| 电镀加工费 | `pricing_price` | ✅ | 费用(固定) |
| 电镀材料费 | — | ❌ | 不导入（归第 2 条记录） |
| 货币 | `currency` | ✅ | 币种 |
| 计价单位 | `unit` | ✅ | 计量单位 |
| 不良率（%） | `defect_rate` | ✅ | 不良率% |

#### → 第 2 条：电镀材料费

| 固定写入字段 | 固定值 |
|------------|--------|
| `system_type` | `PRICING` |
| `price_type` | `PLATING` |
| `cost_type` | `电镀材料费` |

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 销售料号 | `sales_part_no` | ✅ | 报价料号（必填）；进唯一键 + 升版 groupKey（见 §四/§五） |
| 生产料号 | `code` | ✅ | 元素代码/材料料号/零件号/耗材料号 |
| 电镀方案编号 | `plating_scheme_no` | ✅ | 不为空时整行跳过不导入 |
| 版本编号 | `version_no` | ✅ | 价格版本 |
| 电镀加工费 | — | ❌ | 不导入（归第 1 条记录） |
| 电镀材料费 | `pricing_price` | ✅ | 费用(固定) |
| 货币 | `currency` | ✅ | 币种 |
| 计价单位 | `unit` | ✅ | 计量单位 |
| 不良率（%） | `defect_rate` | ✅ | 不良率% |

> 📌 每行 Excel 数据拆分为两条 `unit_price` 记录，仅 `cost_type` 与 `pricing_price` 取值不同，其余字段相同。当电镀方案编号不为空时整行跳过。

---

### 23. 其他外加工成本

**目标表：** `unit_price`

| 固定写入字段 | 固定值 |
|------------|--------|
| `system_type` | `PRICING` |
| `price_type` | `OUTSOURCE_PROCESS` |
| `cost_type` | `其他加工费` |

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 销售料号 | `sales_part_no` | ✅ | 报价料号（必填）；进唯一键 + 升版 groupKey（见 §四/§五） |
| 生产料号 | `code` | ✅ | 元素代码/材料料号/零件号/耗材料号 |
| 工序编号 | `operation_no` | ✅ | 作业编号 |
| 工序名称 | — | ❌ | 不导入 |
| 外加工费用 | `pricing_price` | ✅ | 费用(固定) |
| 币种 | `currency` | ✅ | |
| 单位 | `unit` | ✅ | 计量单位 |

> 📌 适用于成品或其 BOM 下半成品/原材料料号的外协加工费。

---

### 24. 单重

**目标表：** `material_master`

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 销售料号 | `sales_part_no` | ✅ | 报价料号（必填）；不落 material_master，登记进 map（见 §4.4） |
| 生产料号 | `material_no` | ✅ | 料号（业务唯一） |
| 单重（g/pcs） | `unit_weight` | ✅ | 单重 (g/pcs) |

> 📌 按 `material_no` upsert（存在则更新 `unit_weight`，不存在则插入）。

---

## 三、通用落库规则

| 规则项 | 说明 |
|--------|------|
| **price_type 与 cost_type 的区别** | `price_type`（价格类型）标识价格来源/Sheet 分类。**2026-06-30 起原超载大类 `MATERIAL` 已在核价写入端废弃并按 Sheet 细分**，各费用 Sheet 直接写 `MATERIAL_PRICE`/`PACKAGING`/`INCOMING_PROCESS`/`INCOMING_OTHER`/`SELF_PROCESS`/`FINISHED_OTHER`/`OUTSOURCE_PROCESS`/`PLATING`；`ELEMENT`/`CONSUMABLE` 保留。`cost_type`（费用类型）标识费用用途分类（如自制加工费、电镀材料费等），保持不变。比例/固定不进 price_type，靠 `cost_ratio` vs `pricing_price` 哪列有值区分。两个字段均写入 `unit_price` 表。旧大类 `MATERIAL` 仅保留在 CHECK 白名单供存量行/报价侧使用。详见 `docs/superpowers/specs/2026-06-30-pricing-unit-price-source-enum-design.md`。 |
| **数据清洗** | 导入前过滤空行（所有关键字段均为空的行）；注释行、说明行不导入。 |
| **upsert 策略** | 料号表（`material_master`）按 `material_no` 做 upsert；其余表按各自唯一约束做 INSERT OR UPDATE。 |
| **布尔字段转换** | 「是否有效」、「是否生效」等文字值统一转换：是→1，否→0，以 TINYINT(1) 存储。 |
| **固定字段写入** | `system_type`、`price_type`、`cost_type` 等在 Excel 未作为数据列出现的字段，由程序根据 Sheet 来源固定写入，不依赖用户填写。 |
| **动态 cost_type** | 来料其他费用、来料其他固定费用、成品其他比例/固定费用等 Sheet 中，`cost_type` 取自 Excel 的「要素编号」或「要素名称」列，动态写入，不固定。 |
| **版本字段（V6.2 更新）** | 分两类，详见 §五：① **系统升版**（`material_bom`/`element_bom`/`capacity`/`plating_scheme` + 一步到位新增的费用类 `unit_price` P13/P14/P15–P20/P22/P23）——版本号由 `VersionedV6Writer` 内容指纹比对生成（首版 `2000`、变化 `max+1`、旧组 `is_current=false` 翻转），**忽略 Excel 版本列**。② **Excel 业务版本直存**（价格版本 P01/P02、汇率版本 P03、核价版本 P04、取用的计算版本 P09/P10/P11、模具 P12）——Excel「版本」列直接写 `version_no`/`calc_version`，不系统升版。 |
| **销售料号维度（V6.2 新增）** | 19 个"按料号" Sheet 的必填「销售料号」→ 落新列 `sales_part_no`，进各表唯一键（末尾追加 `COALESCE(sales_part_no,'')`）+ 系统升版表的 groupKey；核价侧只写文件既有值、**不铸号**。全局 Sheet（P01/P02）落 `unit_price` 时 `sales_part_no=NULL`，靠 `COALESCE` 中性化与费用行不冲突。详见 §四。 |
| **生产料号改名（V6.2）** | Excel「宏丰料号」列改名「生产料号」；落库列 `material_no`/`finished_material_no`/`code` **不变**；handler 读列「生产料号」优先、回退「宏丰料号」（Sheet 7 用「物料料号」例外）。 |
| **电镀条件判断** | 「电镀成本」Sheet：当电镀方案编号不为空时，该行整体跳过不导入，由系统根据电镀方案表自动计算结果。 |
| **一行拆多条** | 「电镀成本」Sheet 每行拆分为两条 `unit_price` 记录（电镀加工费、电镀材料费）；其余 Sheet 一行对应一条记录（或主+子各一条）。 |
| **多表写入顺序** | 建议写入顺序：料号表 → 料号关系表 → 汇率表 → BOM主表 → BOM子表 → 单价表 → 其余关联表，以保证外键约束。 |
| **忽略 Sheet** | 「汇总」Sheet 不导入。 |

---

## 四、销售料号（报价料号）贯穿维度

> 2026-07-07 并入；术语与三码模型对齐 `docs/superpowers/specs/2026-07-06-报价料号统一-design.md`（Spec 1）。

### 4.1 术语与列改名对照

| Excel 列 | 语义 | 落库列 |
|---|---|---|
| **销售料号**（新增·必填） | = **报价料号** `XXXX-YYMMNNNNNN`，全系统唯一料号，贯穿报价/核价 | 各"按料号"表新增 `sales_part_no` 列 |
| **生产料号**（原「宏丰料号」改名） | 对接 ERP 的料号（原核价料号主键口径） | 仍落原列 `material_no`/`finished_material_no`/`code`（**列不动，仅表头改名**） |
| 客户产品编号 | 客户方料号 | `customer_product_no` |

**三码关系**：销售料号(报价料号) ↔ 客户料号 = 1:1；生产料号 ↔ 销售料号 = 1:N。

**四项决策（2026-07-07 确认）**：① 销售料号 = 复合唯一键 + 升版 groupKey 维度；② 生产料号原落库列不动、旁加 `sales_part_no`；③ P05 在 `material_customer_map`（PRICING 行）登记三码；④ 5 个全局/元素级 Sheet 不加。

### 4.2 需新增 `sales_part_no` 列的表（DDL 概要）

```sql
-- 统一 VARCHAR(32) NULL（报价料号定长 15，留余量；列级可空以兼容共享表全局行）
ALTER TABLE unit_price        ADD COLUMN sales_part_no VARCHAR(32);   -- 费用行有值；P01/P02 全局行 NULL
ALTER TABLE material_bom      ADD COLUMN sales_part_no VARCHAR(32);
ALTER TABLE material_bom_item ADD COLUMN sales_part_no VARCHAR(32);   -- 随主表镜像
ALTER TABLE element_bom       ADD COLUMN sales_part_no VARCHAR(32);
ALTER TABLE element_bom_item  ADD COLUMN sales_part_no VARCHAR(32);
ALTER TABLE capacity          ADD COLUMN sales_part_no VARCHAR(32);
ALTER TABLE labor_rate        ADD COLUMN sales_part_no VARCHAR(32);
ALTER TABLE production_energy ADD COLUMN sales_part_no VARCHAR(32);
ALTER TABLE auxiliary_energy  ADD COLUMN sales_part_no VARCHAR(32);
ALTER TABLE tooling_cost      ADD COLUMN sales_part_no VARCHAR(32);
ALTER TABLE material_customer_map ADD COLUMN sales_part_no VARCHAR(32);  -- PRICING 行=销售料号
```

> `material_master` **不加列**：生产料号↔销售料号=1:N，加列会破坏 1:N；销售↔生产映射由 `material_customer_map` 承载（§4.4）。

### 4.3 逐 Sheet 销售料号落库约定

| Sheet | 目标表 | 销售料号落库列 | 进 uq | 进升版 groupKey | 必填 |
|---|---|---|:-:|:-:|:-:|
| P05 宏丰-客户料号 | material_customer_map(PRICING) | `sales_part_no` | 是（三码键，§4.4） | — | ✅ |
| P06 物料BOM | material_bom(+item) | `sales_part_no`（主表；子表镜像） | 是 | 是 | ✅ |
| P07 物料与元素BOM | element_bom(+item) | `sales_part_no` | 是 | 是 | ✅ |
| P08 产能 | capacity(+labor_rate) | `sales_part_no` | 是 | 是 | ✅ |
| P09 设备折旧成本 | production_energy | `sales_part_no` | 是（合并冲突键） | —（Excel calc_version） | ✅ |
| P10 生产设备能耗 | production_energy | `sales_part_no` | 是（须与 P09 一致） | — | ✅ |
| P11 辅助设备能耗 | auxiliary_energy | `sales_part_no` | 是 | — | ✅ |
| P12 模具工装成本 | tooling_cost | `sales_part_no` | 是 | — | ✅ |
| P13 生产耗材BOM | unit_price | `sales_part_no` | 是 | **是**（本次系统升版） | ✅ |
| P14 包装材料BOM | unit_price | `sales_part_no` | 是 | **是**（本次系统升版） | ✅ |
| P15 来料加工费 | unit_price | `sales_part_no` | 是 | **是**（本次系统升版） | ✅ |
| P16 来料其他费用(比例) | unit_price | `sales_part_no` | 是 | **是** | ✅ |
| P17 来料其他固定费用 | unit_price | `sales_part_no` | 是 | **是** | ✅ |
| P18 加工费&组装费 | unit_price | `sales_part_no` | 是 | **是** | ✅ |
| P19 成品其他比例费用 | unit_price | `sales_part_no` | 是 | **是** | ✅ |
| P20 成品其他固定费用 | unit_price | `sales_part_no` | 是 | **是** | ✅ |
| P22 电镀成本 | unit_price（两条都写） | `sales_part_no` | 是 | **是**（本次系统升版） | ✅ |
| P23 其他外加工成本 | unit_price | `sales_part_no` | 是 | **是** | ✅ |
| P24 单重 | material_master + map | material_master **不加列**；销售↔生产登记进 map（幂等） | —/map | — | ✅ |

**不加销售料号的 5 个全局/元素级 Sheet**：P01 元素价格、P02 材料价格、P03 汇率、P21 电镀方案、P04 核价版本（版本登记按生产料号；其料号列同样由「宏丰料号」改名「生产料号」，按 §三 兼容读，落库列不变）。

> 说明：来料系（P15–P17）「来料料号」仍落 `code`（组件），成品生产料号落 `finished_material_no`，销售料号落 `sales_part_no` 并列成品维度；P18–P20/P22/P23 中 `code`=生产料号、`sales_part_no`=销售料号并列。

### 4.4 `material_customer_map` 三码登记（P05 + P24）

P05（`system_type='PRICING'`）一行落三码：销售料号→`sales_part_no`、生产料号→`material_no`、客户产品编号→`customer_product_no`；upsert 冲突键沿用 `(system_type, material_no, customer_no, COALESCE(customer_product_no,''))`，`sales_part_no` 进 `DO UPDATE SET`（COALESCE 回填）。**守卫**：同一 `sales_part_no` 命中不同 `customer_no` → handler `recordError`（跨客户串号，对齐 Spec §7）；同一销售料号对多个生产料号按 1:N 允许。P24 单重仍按生产料号 upsert `material_master.unit_weight`，其销售↔生产 pair 缺失则幂等补登 map。

### 4.5 校验与兼容

- **销售料号必填**：19 个 Sheet 空 → 该行 `recordError("销售料号","必填")`，不落库；5 个全局 Sheet 无此列不校验。
- **只写不 mint**：核价侧销售料号取自 Excel 文件既有值（报价侧已发号），文件值即权威；参照存在性（是否已在 map 登记）本期**告警不阻断**（数据 Sheet 可能先于 P05 导入）。
- **生产料号兼容读**：见 §三「生产料号改名」规则。

---

## 五、统一升版机制（一步到位）

> 版本化算法权威在 `com.cpq.basicdata.v6.versioning.VersionedV6Writer` + `docs/table/报价系统版本号统一升版规则-设计方案.md`。本节声明**核价侧各表落哪一类版本口径**及本次"一步到位"变更。

### 5.1 三类版本口径

| 类别 | 机制 | 适用 Sheet/表 |
|---|---|---|
| **A · 系统升版** | `VersionedV6Writer` 内容指纹比对 → 复用 / `max+1`(首版 `2000`) / 旧组 `is_current=false` 翻转；**忽略 Excel 版本列**；groupKey **含 `sales_part_no`** | P06 material_bom、P07 element_bom、P08 capacity、P21 plating_scheme（现有）＋ **本次一步到位新增 P13/P14/P15–P20/P22/P23 的 `unit_price` 费用类** |
| **B · Excel 业务版本直存** | Excel「版本/计算版本」列直接写 `version_no`/`calc_version`，普通 upsert，不系统升版；加 `sales_part_no` 进 uq | P09/P10/P11 能耗折旧（取用的计算版本＝手动选版）、P12 模具 |
| **C · 全局业务版本（无销售料号）** | 同 B，但无销售料号维度 | P01/P02 价格（价格版本被 P04 核价版本表引用，**必须保留业务版本号**）、P03 汇率、P04 核价版本登记 |

### 5.2 "一步到位"变更（本次落地）

- **P15–P20、P23**（原走 `UnitPriceWriter` 覆盖式 upsert、`version_no` 恒 `V_DEFAULT`、无 `is_current`、无版本历史）→ 改走 `VersionedV6Writer.writeVersionedGroups`，**系统 2000 起版 + `is_current` 翻转**，`V_DEFAULT` 兜底移除。
- **P13 生产耗材 / P14 包装 / P22 电镀成本**（原读 Excel「取用的耗材版本」/「版本编号」直存）→ 同样改走系统升版，**忽略该 Excel 版本列**（与 P21 电镀方案一致），版本号系统 2000 起版生成。
- **groupKey**：`(system_type='PRICING', price_type, cost_type, code, COALESCE(finished_material_no,''), COALESCE(operation_no,''), sales_part_no)`——即在《升版规则》§四各 Sheet groupKey 基础上**追加 `sales_part_no`**，实现"同生产料号、不同销售料号各自独立升版、互不翻转 `is_current`"。
- 系统升版表（A 类）的 groupKey 一律追加 `sales_part_no`：material_bom/element_bom/capacity 亦然。

### 5.3 已知不一致（本次不改，留档）

- **P08 产能 vs P09/P10/P11 能耗**的「取用的计算版本」口径不一致：产能走 A 类系统升版（忽略 Excel），能耗走 B 类直存 Excel。此为历史分叉，本次不改；若要统一须单独立项。

> 决策留档（2026-07-07）：P13 生产耗材 / P14 包装 / P22 电镀成本 已确认**归 A 类系统升版**（忽略 Excel「取用的耗材版本」/「版本编号」列），与费用类一致。

---

*文档完*
