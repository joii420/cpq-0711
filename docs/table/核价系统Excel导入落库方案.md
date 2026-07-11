# 核价系统基础数据 Excel 导入落库方案

> 版本：V7.0 | 日期：2026-07-11
>
> **V7.0（2026-07-11）· tesk-0709 落库方案纠正（本次）**：通读全文，将 §二/§三/§四/§五 中残留的 V6.2 独立销售料号列设计（该设计已被 `V315__unify_partno_semantics.sql` 反做删除，**作废**，详见下方 ~~V6.2~~ 条目）与旧版 `production_energy` 双列结构等**过时口径**，统一改写为当前实际落库口径（以代码为准逐一核对：`P01~P24Handler` / `IncomingOtherMergeHandler` / `FinishedOtherMergeHandler` / `VersionedV6Writer` / `V323~V326` 迁移）。核心变化：
> 1. **料号语义收敛**（承接下方 V6.3 已声明的方向，本次把 §二 24 个 Sheet 的逐列映射表补齐到位，不再有任何遗留的旧独立销售料号列描述）：`material_no` / `code` / `finished_material_no` 统一承载**销售料号**（= 报价料号，主键 + 升版轴）；新增描述列 `production_no` 承载**生产料号**（写入库但**不进唯一键、不参与升版内容比对**，绝大多数单表版本化 Sheet 通过 `VersionedV6Writer` 的 `descriptorColumns` 参数落实这一点——唯一例外见 §6/§7 的脚注）；`element_bom(_item)` 额外的 `material_part_no` 承载**材质料号**（进轴）。**不存在独立的销售料号列**，销售料号与既有主键列（`material_no`/`code`/`finished_material_no`）共用同一列。
> 2. **`production_energy` 表结构重构**（`V323`/`V324`/`V325`）：原 `depreciation_unit_price` + `energy_unit_price` 两列已删除，合并为单列 `unit_price`；新增 `price_type`（`DEPRECIATION`=折旧 / `ENERGY`=能耗）区分来源 Sheet；P09 折旧写 `price_type=DEPRECIATION`、P10 能耗写 `price_type=ENERGY`，**各写各行、不再合并进同一行**，两 Sheet 各自独立按 `(system_type, material_no, price_type)` 版本化（详见 §9/§10）。
> 3. **P09–P12 升版口径统一为"甲·系统自增"**：`VersionedV6Writer` 内容指纹比对（首版 `2000`，内容变 `max+1`，旧组 `is_current=false` 翻转），**忽略 Excel 版本/计算版本列**；`labor_rate` 不再借用 `capacity` 的版本号，自身独立按 `(system_type, material_no)` 版本化；`tooling_cost` 新增 `calc_version` 列（`V323`）接入版本化。
> 4. §四（原"销售料号贯穿维度"）与 §五（统一升版机制）整节重写。**唯一权威出处改为** `dev-docs/tesk-0709-导入报价数据和导入核价数据的版本升级与版本维护/版本升级规则文档.md §五`；本文档 §四/§五 只保留面向"Excel 列 → 落库字段"细节的配套说明，两者若有出入以《版本升级规则文档》为准。
>
> ~~**V6.2（2026-07-07）：销售料号（报价料号）贯穿维度并入**~~ —— **已作废**。该版本引入了一个与 `material_no`/`code`/`finished_material_no` 并列的**独立销售料号列**设计（19 个 Sheet 新增该独立列进唯一键 + 升版 groupKey），已被 `V315__unify_partno_semantics.sql`（`DROP COLUMN` 该独立列 + 去对应唯一索引后缀）撤销，改为 V6.3 的"复用既有主键列"方案；原设计文档 `docs/superpowers/specs/2026-07-07-核价销售料号维度落库-design.md` 随之作废，仅作历史追溯，不再指导实现。
>
> **V6.3（2026-07-08）· 料号语义纠偏**（方向仍然现行有效，V7.0 在此基础上把 §二 24 个 Sheet 逐条落实）：
> - `material_no` **承载销售料号（主料号）**：核价各 P* handler 读列优先「销售料号」、回退历史模板列名「宏丰料号」，落 `material_no`（`P04PricingVersionHandler` → `material_version_mgmt`，不进 `VersionedV6Writer` 版本化范围，但料号列同样按此口径读）。
> - 新增 `production_no` **承载生产料号**（描述列，**不进唯一键、不进升版比对**）：`宏丰-客户料号对应关系/物料BOM/产能/工时单价/设备折旧/生产能耗/辅助能耗/模具工装/生产耗材BOM/包装材料BOM/来料*/加工费&组装费/成品其他*/电镀成本/其他外加工/单重` 等 Sheet 读「生产料号」列落对应表 `production_no`（`unit_price` / `capacity` / `labor_rate` / `production_energy` / `auxiliary_energy` / `tooling_cost` / `material_customer_map` / `material_master` / `material_bom` / `material_bom_item`）。
> - **`物料与元素BOM`（P07）例外**：材质料号源列名仍为「材质料号」（历史模板兼容读「物料料号」）→ 落 `element_bom.material_part_no`（材质料号，进唯一键 `(system_type, customer_no, material_no, material_part_no, characteristic)`）；`production_no` 该 Sheet 无对应列，恒 NULL。同一销售料号下多材质料号各自独立成 BOM/版本，P07 分组键 = `(material_no, material_part_no)`。
> - `汇总` Sheet **不导入**（无对应 handler，`costing_summary` 由 `CostingSummaryService.compute()` 算出，键 `hf_part_no`，本次不动）。
>
> **V6.1（2026-06-30）**：补齐 `unit_price.price_type` 细分化——原超载大类 `MATERIAL` 在核价写入端废弃，各费用 Sheet 直接写 7 个细分值（材料核价价格=`MATERIAL_PRICE`、包装=`PACKAGING`、来料加工费=`INCOMING_PROCESS`、来料其他费用比例+固定合并=`INCOMING_OTHER`、自制加工费=`SELF_PROCESS`、成品其他比例+固定合并=`FINISHED_OTHER`、其他外加工=`OUTSOURCE_PROCESS`、电镀两条=`PLATING`）；`ELEMENT`（元素核价价格表）/`CONSUMABLE`（生产耗材BOM）已与 Sheet 1:1 **保留不动**；`cost_type` 全程不变。比例/固定不进 price_type，靠 `cost_ratio` vs `pricing_price` 哪列有值区分。规则出处 `docs/superpowers/specs/2026-06-30-pricing-unit-price-source-enum-design.md`（DDL=`V306`，常量类=`PricingPriceType`）。

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
- [四、料号语义与销售料号（报价料号）主轴](#四料号语义与销售料号报价料号主轴)
- [五、统一升版机制](#五统一升版机制)

---

## 一、总览

共 24 个有效 Sheet（忽略「汇总」Sheet），均落入 `unit_price` 或其他目标表。

> **字段说明**
> - `price_type`（价格类型）：标识该条记录的价格来源/Sheet 分类。原超载大类 `MATERIAL` 已按 Sheet 细分为 `MATERIAL_PRICE`/`PACKAGING`/`INCOMING_PROCESS`/`INCOMING_OTHER`/`SELF_PROCESS`/`FINISHED_OTHER`/`OUTSOURCE_PROCESS`/`PLATING`；`ELEMENT`/`CONSUMABLE` 保留
> - `cost_type`（费用类型）：标识该条价格记录的费用用途分类，如 `元素核价价格`、`自制加工费`、`电镀加工费` 等
> - 两个字段相互独立，均写入 `unit_price` 表，共同描述一条价格记录的分类维度
> - `material_no`（含各表同义列 `code`/`finished_material_no`）统一语义 = **销售料号**（报价料号）；各表新增的 `production_no` 是**生产料号描述列**（不进轴），详见 §四

| # | Excel Sheet | 目标数据库表 | price_type（价格类型） | cost_type（费用类型） |
|:-:|-------------|-------------|----------------------|---------------------|
| 1 | 元素核价价格表 | `unit_price` | `ELEMENT` | `元素核价价格` |
| 2 | 材料核价价格表 | `unit_price` | `MATERIAL_PRICE` | `材料核价价格` |
| 3 | 汇率管理表 | `exchange_rate_v6` | — | — |
| 4 | 核价版本 | `material_version_mgmt` | — | — |
| 5 | 宏丰-客户料号对应关系 | `material_customer_map` + `material_master` | — | — |
| 6 | 物料BOM | `material_bom` + `material_bom_item` + `material_master`（同步登记父件/组成料号） | — | — |
| 7 | 物料与元素BOM | `element_bom` + `element_bom_item` | — | — |
| 8 | 产能 | `capacity` + `labor_rate`（两表各自独立版本化） | — | — |
| 9 | 设备折旧成本 | `production_energy`（`price_type=DEPRECIATION`） | — | — |
| 10 | 生产设备能耗 | `production_energy`（`price_type=ENERGY`） | — | — |
| 11 | 辅助设备能耗 | `auxiliary_energy` | — | — |
| 12 | 模具工装成本 | `tooling_cost` | — | — |
| 13 | 生产耗材BOM | `unit_price` | `CONSUMABLE` | `耗材` |
| 14 | 包装材料BOM | `unit_price` | `PACKAGING` | `包装` |
| 15 | 来料加工费 | `unit_price` | `INCOMING_PROCESS` | `来料加工费` |
| 16 | 来料其他费用（比例） | `unit_price`（与 P17 合并单版本组，见 §16/§17） | `INCOMING_OTHER` | `要素编号（动态）` |
| 17 | 来料其他固定费用 | `unit_price`（与 P16 合并单版本组，见 §16/§17） | `INCOMING_OTHER` | `要素名称（动态）` |
| 18 | 加工费&组装费 | `unit_price` | `SELF_PROCESS` | `自制加工费` |
| 19 | 成品其他比例费用 | `unit_price`（与 P20 合并单版本组，见 §19/§20） | `FINISHED_OTHER` | `要素名称（动态）` |
| 20 | 成品其他固定费用 | `unit_price`（与 P19 合并单版本组，见 §19/§20） | `FINISHED_OTHER` | `要素名称（动态）` |
| 21 | 电镀方案 | `plating_scheme` | — | — |
| 22 | 电镀成本（加工费） | `unit_price` | `PLATING` | `电镀加工费` |
| 22 | 电镀成本（材料费） | `unit_price` | `PLATING` | `电镀材料费` |
| 23 | 其他外加工成本 | `unit_price` | `OUTSOURCE_PROCESS` | `其他加工费` |
| 24 | 单重 | `material_master` | — | — |

> **料号列变更（适用于下方全部明细表）**：
> - Excel 主料号列表头统一为「**销售料号**」（= 报价料号，主键 + 升版轴）；历史模板列名「宏丰料号」仍向后兼容读取（handler 读列优先「销售料号」、回退「宏丰料号」）；落库列沿用各表既有列名 `material_no`/`code`/`finished_material_no`，**不新增列**。
> - 除 P01/P02/P03/P04/P21（全局/元素级，无逐料号维度）外，其余 Sheet 新增「**生产料号**」列 → 落各表新增描述列 `production_no`（写入但不进唯一键、原则上不参与升版内容比对）。
> - 「物料与元素BOM」（Sheet 7）例外：其料号列表头是「材质料号」（历史兼容读「物料料号」），落 `element_bom.material_part_no`（材质料号，进轴），该 Sheet 无「生产料号」列。

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
| 元素代码 | `code` | ✅ | 元素代码/材料料号/零件号/耗材料号；全局轴（本 Sheet 无逐料号维度） |
| 核价单价 | `pricing_price` | ✅ | 费用(固定) |
| 市场参考价 | `market_ref_price` | ✅ | |
| 参考价来源网址 | `source_url` | ✅ | 抓取网址 |
| 网站名称 | `source_name` | ✅ | |
| 参考价取用规则 | `fetch_rule` | ✅ | 取用规则 |
| 币种 | `currency` | ✅ | |
| 计量单位 | `unit` | ✅ | |
| 回收折扣（%） | `recovery_discount` | ✅ | 回收折扣(%) |
| 元素价格版本 | — | ❌ | **不导入**：`version_no` 由 `VersionedV6Writer` 系统自增生成（首版 `2000`，任一内容列变化即整组升版），Excel 该列被忽略，见 §五 A 组 |

> 📌 每行生成一条 `unit_price` 记录；`system_type=PRICING`，`price_type=ELEMENT`，`cost_type=元素核价价格` 由系统固定写入。版本按 `元素代码` 分组。

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
| 材料料号 | `code` | ✅ | 元素代码/材料料号/零件号/耗材料号；全局轴 |
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
| 材料价格版本 | — | ❌ | **不导入**：`version_no` 系统自增生成，忽略 Excel 该列，见 §五 A 组 |

> 📌 品名、规格、尺寸列不导入。`system_type=PRICING`，`price_type=MATERIAL_PRICE`，`cost_type=材料核价价格` 由系统写入。版本按 `材料料号` 分组。

---

### 3. 汇率管理表

**目标表：** `exchange_rate_v6`（V218 起新表；旧 `exchange_rate` 表已不是本 Sheet 落库目标）

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 基础货币 | `base_currency` | ✅ | 分组轴之一 |
| 核价货币 | `target_currency` | ✅ | 目标货币；分组轴之一 |
| 核价汇率 | `rate` | ✅ | 汇率 |
| 参考汇率 | `ref_rate` | ✅ | |
| 参考汇率数据抓取规则 | `ref_fetch_rule` | ✅ | 参考汇率抓取规则 |
| 抓取网址 | `ref_source_url` | ✅ | |
| 汇率版本 | — | ❌ | **不导入**：`version_no` 由 `VersionedV6Writer` 系统自增生成，忽略 Excel 该列，见 §五 A 组 |

> 📌 每行生成一条 `exchange_rate_v6` 记录；版本按 `(base_currency, target_currency)` 分组。`exchange_rate_v6` 无 `system_type` 列（全局共享，报价/核价合用），批量写入按组逐个调用单组入口（表数据量小，不强求跨组恒定前缀）。

---

### 4. 核价版本

**目标表：** `material_version_mgmt`（**不接入 `VersionedV6Writer`**，普通 upsert；本期出范围，"版本包"概念退化，见 §五）

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 销售料号（历史模板兼容读「宏丰料号」） | `material_no` | ✅ | 报价料号 |
| 品名 | — | ❌ | 不需要导入 |
| 规格 | — | ❌ | 不需要导入 |
| 尺寸 | — | ❌ | 不需要导入 |
| 项次 | `seq_no` | ✅ | |
| 核价版本编号 | `pricing_version_no` | ✅ | 必填 |
| 核价版本名称 | `pricing_version_name` | ✅ | |
| 元素价格版本 | `element_price_version` | ✅ | 业务版本号直存（非本表升版轴，仅登记引用） |
| 材料价格版本 | `material_price_version` | ✅ | 同上 |
| 汇率价格版本 | `exchange_rate_version` | ✅ | 同上 |
| 是否生效 | `is_effective` | ✅ | 是→true，否→false；默认 true |

> 📌 品名、规格、尺寸不导入；冲突键 `(material_no, COALESCE(customer_no,''), seq_no, pricing_version_no)` upsert，`is_effective` 末行覆盖、其余列 COALESCE 回填。**本表登记的是元素/材料/汇率的业务版本号引用（Excel 原生版本编号），不经 `VersionedV6Writer` 系统自增。**

---

### 5. 宏丰-客户料号对应关系

**目标表：** `material_customer_map`（主） + `material_master`（同时写入）

#### → 料号表（material_master）

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 销售料号（历史模板兼容读「宏丰料号」） | `material_no` | ✅ | **业务主键**（= 销售料号，upsert 依据） |
| 品名 | `material_name` | ✅ | |
| 规格 | `specification` | ✅ | |
| 尺寸 | `dimension` | ✅ | |
| 旧料号 | `old_material_no` | ✅ | |
| 生产料号 | `production_no` | ✅ | 描述列；本 Sheet 该列常为空，由物料BOM/产能等成本类 Sheet 侧回填（见 §6） |
| 项次 | — | ❌ | 不需要导入 |
| 客户编号 | — | ❌ | 不需要导入（落 `material_customer_map`） |
| 客户名称 | — | ❌ | 不需要导入（落 `material_customer_map`） |
| 客户产品编号 | — | ❌ | 不需要导入（落 `material_customer_map`） |

#### → 料号关系表（material_customer_map）

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 销售料号（历史模板兼容读「宏丰料号」） | `material_no` | ✅ | 关联键之一（必填） |
| 生产料号 | `production_no` | ✅ | 描述列 |
| 品名 | — | ❌ | 不需要导入（落 `material_master`） |
| 规格 | — | ❌ | 不需要导入 |
| 尺寸 | — | ❌ | 不需要导入 |
| 旧料号 | — | ❌ | 不需要导入 |
| 项次 | `seq_no` | ✅ | |
| 客户编号 | `customer_no` | ✅ | 必填 |
| 客户名称 | `customer_name` | ✅ | |
| 客户产品编号 | `customer_product_no` | ✅ | 必填 |

> 📌 同一 Sheet 同时向两张表落库，`material_no`（销售料号）为关联键；料号表按 `material_no` upsert。三个必填字段（销售料号/客户编号/客户产品编号）任一为空则该行整体报错跳过。

---

### 6. 物料BOM

**目标表：** `material_bom`（主表） + `material_bom_item`（子表）

| 固定写入字段 | 固定值 |
|------------|--------|
| `system_type` | `PRICING` |
| `customer_no` | `_GLOBAL_`（核价 BOM 全局共享哨兵，与报价侧按客户隔离物理区分） |

#### → 物料BOM主表（material_bom）

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 销售料号（历史模板兼容读「宏丰料号」） | `material_no` | ✅ | 主件料号；**升版轴**（`system_type, customer_no, material_no, bom_type`） |
| 生产料号 | `production_no` | ✅ | 描述列（masterContent，不进版本比较），同批多行取首个非空归并 |

> 其余头表字段（`bom_type=MATERIAL`、`bom_version` 等）由系统写入默认值或系统生成。

#### → 物料BOM子表（material_bom_item）

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| （分组键：与主表同一「销售料号」列） | `material_no` | — | 子表分组键来自主表分组，非单独导入列 |
| 项次 | `seq_no` | ✅ | |
| 组成料号 | `component_no` | ✅ | 组件料号（列名「组成料号」/「组件料号」兼容读） |
| 品名 | — | ❌ | 不导入（子表不落；组成料号的品名/规格/尺寸另同步进 `material_master`，见下方 20260705 说明） |
| 规格 | — | ❌ | 不导入 |
| 尺寸 | — | ❌ | 不导入 |
| 工序编号 | `operation_no` | ✅ | 作业编号 |
| 工序名称 | — | ❌ | 不导入 |
| 使用特性 | `component_usage_type` | ✅ | 元件使用特性 |
| 组成用量 | `composition_qty` | ✅ | |
| 组成用量单位 | `issue_unit` | ✅ | 发料单位 |
| 底数 | `base_qty` | ✅ | 主件底数 |
| 底数单位 | — | ❌ | 不导入 |
| 材料损耗率（%） | `scrap_rate` | ✅ | 损耗率（列名「材料损耗率」/「损耗率」兼容读） |
| 材料固定损耗量 | `fixed_scrap` | ✅ | 固定损耗（列名「材料固定损耗量」/「固定损耗」兼容读） |
| 不良率（%） | `defect_rate` | ✅ | 不良率(%) |
| 计算类型 | `calc_type` | ✅ | 区分 `component_no` 归属语义：`材料`=组成料号是销售料号、`元素`=组成料号是材质编号（决策B，2026-07 repair-1） |
| 生产料号 | `production_no` | ✅ | 描述列，随子表每行写入 |

> 📌 `system_type=PRICING`、`bom_type=MATERIAL` 固定写入主表；子表与主表保持一致。BOM 数据来源于 ERP，导入时需抓取成品料号往下所有级组成料号。
>
> ⚠️ **已知实现细节**：`VersionedV6Writer.writeVersionedMasterDetail` 当前不支持主从表的独立"描述列豁免"机制（该机制仅存在于单表 `writeVersionedGroups` 的 `descriptorColumns` 参数），子表 `production_no` 是随其余值列一并放进 `childContentColumns` 传入的——技术上会参与子表 multiset 内容比较，与"描述列不参与升版比对"的产品语义原则不完全一致。此为当前代码现状，非本次文档纠正范围，如需彻底对齐语义需另立项改造 `writeVersionedMasterDetail` 签名。

#### 20260705 更新：物料BOM 导入时同步登记料号表（material_master）

**背景 / 动机**：组成料号（`component_no`）常常只作为子件出现在 BOM 里，从未在「宏丰-客户料号对应关系」（Sheet 5）单独登记过 → 料号表 `material_master` 无此料号行 → 核价树递归展开出该子件节点时，下游页签 `$view` join 出的 品名/规格/尺寸/单重全为空。本次优化：**导入 物料BOM Sheet 时，额外把父件（销售料号）与所有组成料号 upsert 进 `material_master`，补齐显示用主数据**。

> ⚠️ 本同步**只影响 `material_master`**，不改变 BOM 树结构——树结构来自 `material_bom_item` 的递归 SQL，与 `material_master` 无关（见 `docs/核价树页签组件配置指南.md`）。收益点正是让树上子件节点的名称/规格/尺寸不再为空。

##### 同步 1 —— 父件（销售料号）→ material_master

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 销售料号 | `material_no` | ✅ | 主件料号（= 主表分组轴），upsert 主键 |
| 生产料号 | `production_no` | ✅ | 首个非空归并后随主料号一并登记（决策A，2026-07 repair-1） |

> 📌 父件通常是成品/半成品料号，其 品名/规格/尺寸 由 Sheet 5（客户料号对应关系）或它自身作为别处子件出现时补齐；此处只保证料号行存在（+ `production_no`），名称为空可接受。

##### 同步 2 —— 组成料号 → material_master

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 组成料号 | `material_no` | ✅ | upsert 主键 |
| 品名 | `material_name` | ✅ | **仅回填空白**（见下方覆盖语义）|
| 规格 | `specification` | ✅ | **仅回填空白** |
| 尺寸 | `dimension` | ✅ | **仅回填空白** |
| 使用特性 | — | ❌ | 不回填 `material_type`/`usage_property`（使用特性是 BOM 边级语义，≠ 料号级材料类型），仍按 §6 子表规则写入 `material_bom_item.component_usage_type` |

> ⚠️ **仅「材料」行（组成料号=销售料号）才登记进 `material_master`**（决策B，2026-07 repair-1）；`计算类型=元素` 行的「组成料号」实为材质编号，不得当销售料号污染主档（AC-7）。
>
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
| `customer_no` | `_GLOBAL_`（核价全局共享哨兵） |
| `bom_type` | `MATERIAL` |

#### → 元素BOM主表（element_bom）

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 销售料号（列名兼容读「物料料号」「宏丰料号」） | `material_no` | ✅ | 主件料号；**升版轴之一** |
| 材质料号（列名兼容读「物料料号」，历史遗留，未随报价侧改名） | `material_part_no` | ✅ | 材质料号；**升版轴之一**，同一销售料号下多材质料号各自独立成 BOM/版本 |

> ⚠️ 本 Sheet **无「生产料号」列**（`production_no` 恒 NULL），是 §四 中唯一一个"料号列不是「销售料号 / 生产料号」二分"的例外，改用「销售料号 / 材质料号」二分。

#### → 元素BOM子表（element_bom_item）

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| （分组键：与主表同一「销售料号」「材质料号」列） | `material_no`/`material_part_no` | — | 子表分组键来自主表分组，非单独导入列 |
| 品名 | — | ❌ | 不导入 |
| 规格 | — | ❌ | 不导入 |
| 尺寸 | — | ❌ | 不导入 |
| 项次 | `seq_no` | ✅ | |
| 元素代码 | `component_no` | ✅ | 组件料号（元素代码归此字段） |
| 组成含量（%） | `content` | ✅ | 含量 |
| 损耗率（%） | `scrap_rate` | ✅ | 损耗率 |

> 📌 只维护「计算类型=按元素」的来料及边角料的元素BOM；`system_type=PRICING` 固定写入。版本列 `characteristic`：子表行集相同复用旧版本号，不同则整组升版（首版 `2000`）。

---

### 8. 产能

**目标表：** `capacity` + `labor_rate`（两表**各自独立版本化**，`labor_rate` 不再借用 `capacity` 的版本号——2026-07-11 tesk-0709 变更）

#### → 产能表（capacity）

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 销售料号（历史模板兼容读「宏丰料号」） | `material_no` | ✅ | **升版轴**（`system_type, material_no, resource_group_no='PRICING_DEFAULT'`） |
| 生产料号 | `production_no` | ✅ | 描述列（写入不参与升版比对） |
| 品名 | — | ❌ | 不导入 |
| 规格 | — | ❌ | 不导入 |
| 尺寸 | — | ❌ | 不导入 |
| 工序编号 | `process_no` | ✅ | |
| 工序名称 | — | ❌ | 不导入 |
| 人工标准单价 | — | ❌ | 不导入（由工时单价表 `labor_rate` 独立维护） |
| 币种 | — | ❌ | 不导入（`capacity` 表无此列） |
| 计量单位 | — | ❌ | 不导入（`capacity` 表无此列） |
| 取用的计算版本 | — | ❌ | **不导入**：`calc_version` 系统自增生成，忽略 Excel 该列，见 §五 |
| 是否有效 | `is_effective` | ✅ | 是→true，否→false；空默认 true |

#### → 工时单价表（labor_rate）

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| （分组键：与 capacity 同一「销售料号」列） | `material_no` | — | **独立升版轴**（`system_type, material_no`），不借用 `capacity` 的 `calc_version` |
| 生产料号 | `production_no` | ✅ | 描述列 |
| 工序编号 | `process_no` | ✅ | |
| 人工标准单价 | `standard_labor_rate` | ✅ | 标准工时单价；仅当该值非空才写入本表行 |
| 币种 | `currency` | ✅ | |
| 计量单位 | `unit` | ✅ | 计价单位 |
| 取用的计算版本 | — | ❌ | **不导入**：`version_no` 系统自增生成，忽略 Excel 该列 |

> 📌 因不同月计算单价可能不同，历史需求是"手动选取版本"；本次改为系统按内容指纹自动升版，Excel 版本列不再生效。`capacity` 与 `labor_rate` 各自独立按 `(system_type, material_no)` 系轴比对内容、各自升版，互不影响。

---

### 9. 设备折旧成本

**目标表：** `production_energy`（`price_type=DEPRECIATION`）

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 销售料号（历史模板兼容读「宏丰料号」） | `material_no` | ✅ | **升版轴**（`system_type, material_no, price_type=DEPRECIATION`） |
| 生产料号 | `production_no` | ✅ | 描述列 |
| 品名 | — | ❌ | 不导入 |
| 规格 | — | ❌ | 不导入 |
| 尺寸 | — | ❌ | 不导入 |
| 工序编号 | `process_no` | ✅ | |
| 工序名称 | — | ❌ | 不导入 |
| 折旧单价 | `unit_price` | ✅ | 设备折旧单价；**原 `depreciation_unit_price` 列已删除，合并进统一列 `unit_price`**（V323，见 §五） |
| 币种 | `currency` | ✅ | |
| 计量单位 | `unit` | ✅ | |
| 取用的计算版本 | — | ❌ | **不导入**：`calc_version` 系统自增生成，忽略 Excel 该列 |
| 是否有效 | — | ❌ | 不导入（由产能表 `is_effective` 控制） |

> 📌 系统固定写入 `price_type=DEPRECIATION`、`system_type=PRICING`。**与「生产设备能耗」（P10）不再合并进同一行**，各自按 `(system_type, material_no, price_type)` 独立整批版本化（工序整批为一组，任一内容列变化即整组升版）。数值精度：`unit_price` 落库列 `numeric(18,6)`，Excel 若含超精度浮点字面量（POI double 全精度解析），解析时同步 `setScale(6, HALF_UP)` 舍入，避免同文件重导因精度截断误判"内容变化"而误升版（tesk-0709 Task 11 E2E 修复）。

---

### 10. 生产设备能耗

**目标表：** `production_energy`（`price_type=ENERGY`）

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 销售料号（历史模板兼容读「宏丰料号」） | `material_no` | ✅ | **升版轴**（`system_type, material_no, price_type=ENERGY`） |
| 生产料号 | `production_no` | ✅ | 描述列 |
| 品名 | — | ❌ | 不导入 |
| 规格 | — | ❌ | 不导入 |
| 尺寸 | — | ❌ | 不导入 |
| 工序编号 | `process_no` | ✅ | |
| 工序名称 | — | ❌ | 不导入 |
| 生产能耗单价 | `unit_price` | ✅ | **原 `energy_unit_price` 列已删除，合并进统一列 `unit_price`**（V323，见 §五） |
| 币种 | `currency` | ✅ | |
| 计量单位 | `unit` | ✅ | |
| 取用的计算版本 | — | ❌ | **不导入**：`calc_version` 系统自增生成，忽略 Excel 该列 |
| 是否有效 | — | ❌ | 不导入 |

> 📌 系统固定写入 `price_type=ENERGY`。与「设备折旧成本」（P09）**同表不同行**（各按 `price_type` 独立整批版本化，互不影响 `is_current` 翻转）。精度处理同 P09（`unit_price` 解析时同步舍入至 6 位小数）。

---

### 11. 辅助设备能耗

**目标表：** `auxiliary_energy`

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 销售料号（历史模板兼容读「宏丰料号」） | `material_no` | ✅ | **升版轴**（`system_type, material_no`） |
| 生产料号 | `production_no` | ✅ | 描述列 |
| 品名 | — | ❌ | 不导入 |
| 规格 | — | ❌ | 不导入 |
| 尺寸 | — | ❌ | 不导入 |
| 工序编号 | `process_no` | ✅ | |
| 工序名称 | — | ❌ | 不导入 |
| 非生产能耗单价 | `non_production_energy_price` | ✅ | |
| 币种 | `currency` | ✅ | |
| 计量单位 | `unit` | ✅ | |
| 取用的计算版本 | — | ❌ | **不导入**：`calc_version` 系统自增生成（2026-07-11 tesk-0709 新增版本化），忽略 Excel 该列 |
| 是否有效 | — | ❌ | 不导入 |

> 📌 非生产能耗（照明/空调等）摊销逻辑由系统基于工时计算；工序整批为一组，按内容指纹整组版本化（首版 `2000`）。

---

### 12. 模具工装成本

**目标表：** `tooling_cost`

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 销售料号（历史模板兼容读「宏丰料号」） | `material_no` | ✅ | **升版轴**（`system_type, material_no`） |
| 生产料号 | `production_no` | ✅ | 描述列 |
| 品名 | — | ❌ | 不导入 |
| 规格 | — | ❌ | 不导入 |
| 尺寸 | — | ❌ | 不导入 |
| 工序编号 | `process_no` | ✅ | |
| 工序名称 | — | ❌ | 不导入 |
| 项次 | `seq_no` | ✅ | |
| 模具台账/工装编号 | `tooling_no` | ✅ | 列名「模具台账」/「工装编号」/「模具编号」兼容读 |
| 单个模具/工装成本 | `tooling_unit_cost` | ✅ | 列名「单个模具」/「工装成本」兼容读 |
| 寿命（次） | `tool_life` | ✅ | 寿命(次) |
| 单循环产量 | `cycle_output` | ✅ | |
| 模具工装成本单价 | `tooling_unit_price` | ✅ | 摊销后单价；NOT NULL 列，解析不到兜底 `ZERO`；数值经 `setScale(8, HALF_UP)` 同步舍入（对齐 `numeric(18,8)` 列精度，避免重导误升版） |
| 币种 | `currency` | ✅ | |
| 计量单位 | `unit` | ✅ | |
| 是否有效 | `is_effective` | ✅ | 有效否；是→true，否→false |

> 📌 每个料号+工序下可有多条模具/工装记录，同批同 `(工序编号,项次,模具编号)` 先按业务键折叠去重（末值覆盖）再整批比对。**本次新增 `calc_version` 列**（V323；此前 `tooling_cost` 只有 `is_current`、无版本列，无法系统升版）并接入 `VersionedV6Writer`，轴 = `(system_type, material_no)`，模具明细整批为一组，任一值变化即整组升版（首版 `2000`）。

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
| 销售料号（历史模板兼容读「宏丰料号」） | `code` | ✅ | **升版轴**（`system_type, price_type, cost_type, code`） |
| 生产料号 | `production_no` | ✅ | 描述列 |
| 品名 | — | ❌ | 不导入 |
| 规格 | — | ❌ | 不导入 |
| 尺寸 | — | ❌ | 不导入 |
| 工序编号 | `operation_no` | ✅ | 作业编号；组内去重键（同工序编号取末值覆盖） |
| 工序名称 | — | ❌ | 不导入 |
| 耗材成本单价 | `pricing_price` | ✅ | 费用(固定)；空值兜底 `ZERO` |
| 币种 | `currency` | ✅ | |
| 计量单位 | `unit` | ✅ | |
| 取用的耗材版本 | — | ❌ | **不导入**：`version_no` 系统自增生成，忽略 Excel 该列，见 §五 |
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
| 销售料号（历史模板兼容读「宏丰料号」） | `code` | ✅ | **升版轴** |
| 生产料号 | `production_no` | ✅ | 描述列 |
| 品名 | — | ❌ | 不导入 |
| 规格 | — | ❌ | 不导入 |
| 尺寸 | — | ❌ | 不导入 |
| 工序编号 | `operation_no` | ✅ | 作业编号；组内去重键 |
| 工序名称 | — | ❌ | 不导入 |
| 包装成本单价 | `pricing_price` | ✅ | 费用(固定)；空值兜底 `ZERO` |
| 币种 | `currency` | ✅ | |
| 计量单位 | `unit` | ✅ | |
| 取用的耗材版本 | — | ❌ | **不导入**：系统自增生成，忽略 Excel 该列 |
| 是否有效 | — | ❌ | 不导入 |

> 📌 包装材料写入独立细分值 `price_type=PACKAGING`，`cost_type=包装`。

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
| 销售料号（历史模板兼容读「宏丰料号」「成品料号」） | `finished_material_no` | ✅ | **升版轴锚点**（成品/销售料号维度；字段名沿用历史 `finished_material_no`，语义=销售料号） |
| 生产料号 | `production_no` | ✅ | 描述列 |
| 项次 | — | ❌ | 不导入 |
| 来料料号 | `code` | ✅ | 组内明细维度（进 content，不进轴），组内去重键（同来料料号取末值覆盖） |
| 品名 | — | ❌ | 不导入 |
| 规格 | — | ❌ | 不导入 |
| 尺寸 | — | ❌ | 不导入 |
| 加工费 | `pricing_price` | ✅ | 费用(固定)；空值兜底 `ZERO` |
| 币种 | `currency` | ✅ | |
| 计量单位 | `unit` | ✅ | |
| 损耗（%） | `defect_rate` | ✅ | 不良率%（损耗字段） |

> 📌 只能维护外购来料的加工费（料号只能是初始来料）。损耗（%）映射至 `defect_rate` 字段。系统按内容指纹整批版本化（`version_no`），忽略 Excel 无版本列的历史行为不变。

---

### 16. 来料其他费用（比例）

**目标表：** `unit_price`（**与 §17「来料其他固定费用」合并为单一版本组**，见下方合并说明）

| 固定写入字段 | 固定值 |
|------------|--------|
| `system_type` | `PRICING` |
| `price_type` | `INCOMING_OTHER` |
| `cost_type` | 取自 Excel「要素编号」列（动态写入） |

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 销售料号（历史模板兼容读「宏丰料号」「成品料号」） | `finished_material_no` | ✅ | **升版轴锚点**（与 P17 共享同一版本组，见下方合并说明） |
| 生产料号 | `production_no` | ✅ | 描述列 |
| 一级项次 | — | ❌ | 不导入 |
| 来料料号 | `code` | ✅ | 组内明细维度 |
| 品名 | — | ❌ | 不导入 |
| 规格 | — | ❌ | 不导入 |
| 尺寸 | — | ❌ | 不导入 |
| 二级项次 | `seq_no` | ✅ | 列名「二级项次」/「项次」兼容读 |
| 要素编号 | `cost_type` | ✅ | 费用类型（动态写入，存要素编号） |
| 要素名称 | — | ❌ | 不导入（通过要素编号关联费用配置表获取） |
| 比例（%） | `cost_ratio` | ✅ | 比例；`pricing_price` 固定写 `ZERO`（核价比例费用保持原行为） |

---

### 17. 来料其他固定费用

**目标表：** `unit_price`（**与 §16「来料其他费用（比例）」合并为单一版本组**）

| 固定写入字段 | 固定值 |
|------------|--------|
| `system_type` | `PRICING` |
| `price_type` | `INCOMING_OTHER` |
| `cost_type` | 取自 Excel「要素名称」列（动态写入） |

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 销售料号（历史模板兼容读「宏丰料号」「成品料号」） | `finished_material_no` | ✅ | **升版轴锚点**（与 P16 共享同一版本组） |
| 生产料号 | `production_no` | ✅ | 描述列 |
| 一级项次 | — | ❌ | 不导入 |
| 来料料号 | `code` | ✅ | 组内明细维度 |
| 品名 | — | ❌ | 不导入 |
| 规格 | — | ❌ | 不导入 |
| 尺寸 | — | ❌ | 不导入 |
| 二级项次 | `seq_no` | ✅ | |
| 要素名称 | `cost_type` | ✅ | 费用类型（动态写入） |
| 费用 | `pricing_price` | ✅ | 费用(固定)；空值兜底 `ZERO` |
| 币种 | `currency` | ✅ | |
| 计价单位 | `unit` | ✅ | 计量单位 |

> ⚠️ **P16 + P17 合并单版本组**（由独立 bean `IncomingOtherMergeHandler` 实现，**不是**两个各自独立的 `SheetHandler`）：同一 `finished_material_no`（销售料号）下比例费用（P16）与固定费用（P17）共享**同一个** `unit_price` 版本组，`groupKey = (system_type, price_type=INCOMING_OTHER, finished_material_no)`（**不含 `cost_type`**——`cost_type` 是动态"要素"值，比例/固定两类费用任一变化都会让**两个 Sheet 的全部行一起整组升版**）。两个 Sheet 由 `PricingImportService` 在编排循环外一次性解析、合并、单次调用 `writeVersionedGroups`；组内去重键 = `(code, cost_type, seq_no)`，同键取最后一行（末值覆盖）。若各自独立调用会互相把对方刚写入的 current 行当"旧组"重复升版。固定费用通过 `pricing_price` 存储，比例费用通过 `cost_ratio` 存储，两者以对应字段是否为空区分。

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
| 销售料号（历史模板兼容读「宏丰料号」） | `code` | ✅ | **升版轴**（成品/半成品/原材料料号均可） |
| 生产料号 | `production_no` | ✅ | 描述列 |
| 品名 | — | ❌ | 不导入 |
| 规格 | — | ❌ | 不导入 |
| 尺寸 | — | ❌ | 不导入 |
| 工序编号 | `operation_no` | ✅ | 作业编号；组内去重键 |
| 工序名称 | — | ❌ | 不导入 |
| 加工费 | `pricing_price` | ✅ | 费用(固定)；空值兜底 `ZERO` |
| 币种 | `currency` | ✅ | |
| 计量单位 | `unit` | ✅ | |
| 不良率/拒收率（%） | `defect_rate` | ✅ | 列名「不良率」/「拒收率」兼容读 |

> 📌 加工费由人工+折旧+能耗+模具工装+耗材包装汇总得出，也可直接录入；适用于成品、内部半成品及原材料料号。系统按内容指纹整批版本化。

---

### 19. 成品其他比例费用

**目标表：** `unit_price`（**与 §20「成品其他固定费用」合并为单一版本组**）

| 固定写入字段 | 固定值 |
|------------|--------|
| `system_type` | `PRICING` |
| `price_type` | `FINISHED_OTHER` |
| `cost_type` | 取自 Excel「要素名称」列（动态写入） |

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 销售料号（历史模板兼容读「宏丰料号」） | `code` | ✅ | **升版轴锚点**（与 P20 共享同一版本组） |
| 生产料号 | `production_no` | ✅ | 描述列 |
| 品名 | — | ❌ | 不导入 |
| 规格 | — | ❌ | 不导入 |
| 尺寸 | — | ❌ | 不导入 |
| 项次 | `seq_no` | ✅ | 序号 |
| 要素编号 | — | ❌ | 不导入（本 Sheet 只按「要素名称」写 `cost_type`） |
| 要素名称 | `cost_type` | ✅ | 费用类型（动态写入） |
| 比例（%） | `cost_ratio` | ✅ | 比例；`pricing_price` 固定写 `ZERO` |

---

### 20. 成品其他固定费用

**目标表：** `unit_price`（**与 §19「成品其他比例费用」合并为单一版本组**）

| 固定写入字段 | 固定值 |
|------------|--------|
| `system_type` | `PRICING` |
| `price_type` | `FINISHED_OTHER` |
| `cost_type` | 取自 Excel「要素名称」列（动态写入） |

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 销售料号（历史模板兼容读「宏丰料号」） | `code` | ✅ | **升版轴锚点**（与 P19 共享同一版本组） |
| 生产料号 | `production_no` | ✅ | 描述列 |
| 品名 | — | ❌ | 不导入 |
| 规格 | — | ❌ | 不导入 |
| 尺寸 | — | ❌ | 不导入 |
| 项次 | `seq_no` | ✅ | 序号 |
| 要素名称 | `cost_type` | ✅ | 费用类型（动态写入，如运费、清关费） |
| 费用 | `pricing_price` | ✅ | 费用(固定)；空值兜底 `ZERO` |
| 币种 | `currency` | ✅ | |
| 计价单位 | `unit` | ✅ | 计量单位 |

> ⚠️ **P19 + P20 合并单版本组**（由独立 bean `FinishedOtherMergeHandler` 实现，机制与 §16/§17 完全同构）：`groupKey = (system_type, price_type=FINISHED_OTHER, code)`（**不含 `cost_type`**），比例（P19）+ 固定（P20）任一变化整组一起升版；组内去重键 = `(cost_type, seq_no)`，同键取最后一行。

---

### 21. 电镀方案

**目标表：** `plating_scheme`（全局共享，无逐料号维度，`scheme_no` 本身即轴）

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 方案编号 | `scheme_no` | ✅ | **升版轴**（`system_type, scheme_no`） |
| 版本 | — | ❌ | **不导入**：`scheme_version` 系统自增生成，忽略 Excel 该列，见 §五 |
| 项次 | `seq_no` | ✅ | |
| 电镀元素名称 | `plating_element` | ✅ | 电镀元素（如 Ag/Au/Ni/Sn/Cu） |
| 电镀面积（cm²） | `plating_area` | ✅ | 电镀面积 (cm²) |
| 镀层厚度（μm） | `plating_thickness` | ✅ | |
| 电镀要求 | `plating_requirement` | ✅ | 电镀要求/规格描述 |
| 密度（g/cm³） | `density` | ✅ | 密度 (g/cm³) |

> 📌 `plating_method` 系统固定写 `电镀`；`surface_area` 取 `plating_area`（空则 `ZERO`）；`element_usage`（理论元素用量）**当前实现固定写入 `ZERO`**（不同于报价侧 Q16 按 `plating_area × plating_thickness × density` 公式计算——如核价侧后续也需要该公式请单独核实立项，本文档仅如实记录当前代码行为）。组内多行按 `seq_no` 区分。

---

### 22. 电镀成本

**目标表：** `unit_price`（每行拆分为两条记录，共享同一版本组）

> ⚠️ **特殊规则：** 当电镀方案编号不为空时，该行整体跳过不导入，由系统根据电镀方案表自动计算。

#### → 第 1 条：电镀加工费

| 固定写入字段 | 固定值 |
|------------|--------|
| `system_type` | `PRICING` |
| `price_type` | `PLATING` |
| `cost_type` | `电镀加工费` |

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 销售料号（历史模板兼容读「宏丰料号」） | `code` | ✅ | **升版轴锚点**（加工费+材料费共享同一版本组） |
| 生产料号 | `production_no` | ✅ | 描述列 |
| 电镀方案编号 | — | — | 不落库，仅判断用：不为空时整行跳过不导入 |
| 版本编号 | — | ❌ | **不导入**：`version_no` 系统自增生成，忽略 Excel 该列 |
| 电镀加工费 | `pricing_price` | ✅ | 费用(固定)；空值兜底 `ZERO` |
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
| 销售料号（历史模板兼容读「宏丰料号」） | `code` | ✅ | **升版轴锚点** |
| 生产料号 | `production_no` | ✅ | 描述列 |
| 电镀方案编号 | — | — | 同上，不为空则整行跳过 |
| 版本编号 | — | ❌ | 不导入 |
| 电镀加工费 | — | ❌ | 不导入（归第 1 条记录） |
| 电镀材料费 | `pricing_price` | ✅ | 费用(固定)；空值兜底 `ZERO` |
| 货币 | `currency` | ✅ | 币种 |
| 计价单位 | `unit` | ✅ | 计量单位 |
| 不良率（%） | `defect_rate` | ✅ | 不良率% |

> 📌 每行 Excel 数据拆分为两条 `unit_price` 记录，仅 `cost_type` 与 `pricing_price` 取值不同，其余字段相同。`groupKey = (system_type, price_type=PLATING, code)`，加工费/材料费两条 `cost_type` 记录共享一个版本组，任一变化整组一起升版（`groupKey` 层面为整批版本化，非 §16/17、§19/20 那种跨 Sheet 合并——本 Sheet 内两条记录本就来自同一 Sheet 同一行）。当电镀方案编号不为空时整行跳过。

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
| 销售料号（历史模板兼容读「宏丰料号」） | `code` | ✅ | **升版轴** |
| 生产料号 | `production_no` | ✅ | 描述列 |
| 工序编号 | `operation_no` | ✅ | 作业编号；组内去重键 |
| 工序名称 | — | ❌ | 不导入 |
| 外加工费用 | `pricing_price` | ✅ | 费用(固定)；空值兜底 `ZERO` |
| 币种 | `currency` | ✅ | |
| 单位 | `unit` | ✅ | 计量单位 |

> 📌 适用于成品或其 BOM 下半成品/原材料料号的外协加工费。系统按内容指纹整批版本化。

---

### 24. 单重

**目标表：** `material_master`

| Excel 列名 | 目标表字段 | 是否导入 | 备注说明 |
|-----------|-----------|:-------:|---------|
| 销售料号（历史模板兼容读「宏丰料号」） | `material_no` | ✅ | **upsert 主键**（直接落 `material_master`，非登记进 `material_customer_map`） |
| 生产料号 | `production_no` | ✅ | 描述列（2026-07-11 repair-1 新增写入） |
| 单重（g/pcs） | `unit_weight` | ✅ | 单重 (g/pcs) |

> 📌 按 `material_no`（= 销售料号）upsert：存在则更新 `unit_weight`/`production_no`，不存在则插入。**注意**：`getStr` 读取「销售料号」时不能用裸「料号」子串匹配，会误命中同 Sheet 的「生产料号」列导致读空；本 Sheet 单重的落库主列口径是销售料号。

---

## 三、通用落库规则

| 规则项 | 说明 |
|--------|------|
| **price_type 与 cost_type 的区别** | `price_type`（价格类型）标识价格来源/Sheet 分类，各费用 Sheet 直接写 `MATERIAL_PRICE`/`PACKAGING`/`INCOMING_PROCESS`/`INCOMING_OTHER`/`SELF_PROCESS`/`FINISHED_OTHER`/`OUTSOURCE_PROCESS`/`PLATING`；`ELEMENT`/`CONSUMABLE` 保留。`cost_type`（费用类型）标识费用用途分类（如自制加工费、电镀材料费等），保持不变。比例/固定不进 `price_type`，靠 `cost_ratio` vs `pricing_price` 哪列有值区分。两个字段均写入 `unit_price` 表。详见 `docs/superpowers/specs/2026-06-30-pricing-unit-price-source-enum-design.md`。 |
| **数据清洗** | 导入前过滤空行（所有关键字段均为空的行）；注释行、说明行不导入。 |
| **upsert 策略** | 料号表（`material_master`）按 `material_no` 做 upsert；已版本化的表（见下方"版本字段"）由 `VersionedV6Writer` 统一处理整组比对/插入/翻转；其余非版本化表按各自唯一约束做 INSERT OR UPDATE。 |
| **布尔字段转换** | 「是否有效」、「是否生效」等文字值统一转换：是→`true`，否→`false`。 |
| **固定字段写入** | `system_type`、`price_type`、`cost_type` 等在 Excel 未作为数据列出现的字段，由程序根据 Sheet 来源固定写入，不依赖用户填写。 |
| **动态 cost_type** | 来料其他费用、来料其他固定费用、成品其他比例/固定费用等 Sheet 中，`cost_type` 取自 Excel 的「要素编号」或「要素名称」列，动态写入，不固定。 |
| **版本字段** | 分两类，详见 §五：① **A/C 类·系统自增**（`VersionedV6Writer` 内容指纹比对：首版 `2000`、内容变 `max+1`、旧组 `is_current=false` 翻转，**忽略 Excel 版本列**）——覆盖本文档 §二 中除 P04/P05/P24 外的**全部 20 张已版本化表**（P01/P02/P03/P06/P07/P08(capacity+labor_rate)/P09/P10/P11/P12/P13/P14/P15/P16+P17/P18/P19+P20/P21/P22/P23）。② **B 类·Excel 业务版本直存**（无系统升版，Excel「版本」列直接写入并按业务键 upsert）——仅 P04 核价版本（`pricing_version_no`/`element_price_version`/`material_price_version`/`exchange_rate_version` 均为业务版本号登记，非升版轴）。P05/P24 是纯 upsert（无版本概念）。 |
| **料号语义（V6.3 起）** | `material_no`/`code`/`finished_material_no` 统一承载**销售料号**（= 报价料号，主键 + 大多数已版本化表的升版轴）；新增描述列 `production_no` 承载**生产料号**（写入不进唯一键、原则上不参与升版比对，唯一已知例外见 §6 脚注）；handler 读列优先「销售料号」、回退历史模板列名「宏丰料号」。`element_bom(_item)`（P07）例外，用「材质料号」（`material_part_no`，进轴）替代「生产料号」二分，详见 §四。 |
| **电镀条件判断** | 「电镀成本」Sheet：当电镀方案编号不为空时，该行整体跳过不导入，由系统根据电镀方案表自动计算结果。 |
| **一行拆多条** | 「电镀成本」Sheet 每行拆分为两条 `unit_price` 记录（电镀加工费、电镀材料费）；其余 Sheet 一行对应一条记录（或主+子各一条）。 |
| **跨 Sheet 合并单版本组** | P16(比例)+P17(固定)、P19(比例)+P20(固定) 两对 Sheet 因共享同一 `price_type` + 销售料号锚点，各自不能独立调用 `writeVersionedGroups`（会互相当"旧组"重升版），由专门的 `IncomingOtherMergeHandler`/`FinishedOtherMergeHandler` 在编排循环外合并两个源 Sheet 后一次性写入，详见 §16/17、§19/20。 |
| **多表写入顺序** | 建议写入顺序：料号表 → 料号关系表 → 汇率表 → BOM主表 → BOM子表 → 单价表 → 其余关联表，以保证外键约束。`PricingImportService` 实际编排顺序：P24 → P05 → P03 → P04 → P06 → P07 → P01 → P02 → P08 → P09 → P10 → P11 → P12 → P13 → P14 → P15 → P18 → P21 → P22 → P23；P16+P17、P19+P20 合并对在循环外优先解析写入。 |
| **忽略 Sheet** | 「汇总」Sheet 不导入。 |

---

## 四、料号语义与销售料号（报价料号）主轴

> 2026-07-11 V7.0 重写；**唯一权威出处** = `dev-docs/tesk-0709-导入报价数据和导入核价数据的版本升级与版本维护/版本升级规则文档.md §五`，本节仅摘要 + 落库层配套说明。原 V6.2「销售料号贯穿维度」的独立销售料号列设计（`docs/superpowers/specs/2026-07-07-核价销售料号维度落库-design.md`）已作废，不再指导实现。

### 4.1 三码术语

| 术语 | 落库列 | 含义 |
|---|---|---|
| **销售料号**（= 报价料号，`XXXX-YYMMNNNNNN`） | `material_no` / `code` / `finished_material_no`（各表沿用既有列名，语义已统一） | 全系统唯一料号，贯穿报价/核价；**已版本化表的主键 + 升版轴** |
| **生产料号** | `production_no`（各表新增描述列） | 对接 ERP 的历史料号口径；**只写入、不进轴、不参与升版内容比对**（唯一已知例外：`material_bom_item` 子表，见 §6 脚注） |
| **材质料号**（仅 `element_bom(_item)`） | `material_part_no` | 材质编号；**进轴**，与销售料号共同构成 `element_bom` 的分组键 |

**料号关系**：销售料号(报价料号) ↔ 客户料号 = 1:1；生产料号 ↔ 销售料号 = 1:N（故 `material_master` 主键是销售料号，不能反过来用生产料号做主键）。

### 4.2 哪些 Sheet 有「生产料号」描述列

除 P01/P02/P03/P04/P21（全局/元素级，无逐料号维度）与 P07（用材质料号二分，无生产料号列）外，其余 19 个 Sheet（P05/P06/P08/P09/P10/P11/P12/P13/P14/P15/P16/P17/P18/P19/P20/P22/P23/P24 + `material_bom_item`/`material_customer_map`/`material_master`/`labor_rate` 等派生写入点）均新增「生产料号」列 → 落对应表新增 `production_no` 列。详见 §二 各 Sheet 表格。

### 4.3 兼容读取规则

各 handler 读取销售料号列时优先匹配 Excel 表头「销售料号」，找不到则回退历史模板表头「宏丰料号」（部分 Sheet 额外兼容「成品料号」/「物料料号」，见各 Sheet 章节备注），以兼容尚未切换到新模板的历史 Excel 文件。**不要求文件同时提供两个列**——「销售料号」与「宏丰料号」是同一份数据的新旧表头名，不是两个不同料号。

### 4.4 校验

- 各 Sheet 必填字段（如 P05 的「销售料号/客户编号/客户产品编号」、P06/P07/P08 等的「销售料号/工序编号」等）任一为空 → 该行 `recordError`，不落库，详见各 Sheet 章节。
- **只写不铸号**：核价侧销售料号取自 Excel 文件既有值（报价侧已发号），文件值即权威，不在核价侧生成新料号。

---

## 五、统一升版机制

> 版本化算法权威在 `com.cpq.basicdata.v6.versioning.VersionedV6Writer` + `docs/table/报价系统版本号统一升版规则-设计方案.md` + `dev-docs/tesk-0709-导入报价数据和导入核价数据的版本升级与版本维护/版本升级规则文档.md`（§五「核价侧版本升级规则」为核价专属定稿，本节与其保持一致，出入以该文档为准）。本节摘要**核价侧各表落哪一类版本口径**及关键实现约束。

### 5.1 核价升版总原则

| 维度 | 口径 |
|---|---|
| 版本来源 | **甲·系统自增**：忽略 Excel 自带版本列（元素价格版本/计算版本/汇率版本/耗材版本/方案版本/版本编号…），由 `VersionedV6Writer` 内容指纹比对生成（首版 `2000`、内容变 `max+1`、旧组 `is_current=false` 翻转） |
| 取数 | **永远取 `is_current=true` 最新版**；不按版本号复现历史 |
| 版本总轴 | **销售料号（`material_no`/`code`/`finished_material_no`）**；`production_no` 仅描述、不进轴、原则上不参与升版比对（§6 脚注例外） |
| 分类 | **每个 Sheet（或合并对）独立管版本**；版本组 = `(system_type, price_type/表专属轴, 销售料号)` |
| 粒度 | **粗·多行一组**：一个版本组的整批行为一个版本；子维度（来料料号/要素/工序/项次/模具编号…）当作**值**，不作轴 |
| 比对 | **顺序无关** multiset（数字 `stripTrailingZeros` 归一、NULL 安全）；**不因导入行先后顺序不同而升版** |
| 触发 | 无触发列：任一值变化即整组升版（`capacity`/`labor_rate` 已去掉历史触发列，见 5.4） |

### 5.2 三类版本口径与逐表归类

| 类别 | 机制 | 适用 Sheet/表（本文档 §二 章节号） |
|---|---|---|
| **A · 系统自增（全局，无逐料号维度）** | `VersionedV6Writer`，groupKey 不含销售料号 | §1 P01 元素价格（轴=元素代码）、§2 P02 材料价格（轴=材料料号）、§3 P03 汇率（轴=`base_currency,target_currency`）、§21 P21 电镀方案（轴=`scheme_no`） |
| **A' · 系统自增（含销售料号轴）** | 同上，groupKey 含 `material_no`/`code`/`finished_material_no` | §6 P06 物料BOM、§7 P07 物料与元素BOM（轴含 `material_part_no`）、§8 P08 capacity + labor_rate（各自独立）、§9 P09 折旧、§10 P10 能耗、§11 P11 辅助能耗、§12 P12 模具工装、§13 P13 生产耗材、§14 P14 包装材料、§15 P15 来料加工费、§16/17 P16+P17 合并组、§18 P18 自制加工费、§19/20 P19+P20 合并组、§22 P22 电镀成本 |
| **B · Excel 业务版本直存（无系统升版）** | 普通 upsert，Excel「版本」列直接写入 | §4 P04 核价版本（`pricing_version_no` 等均为业务版本号登记） |
| **— · 纯 upsert（无版本概念）** | 无版本列，按业务键覆盖式 upsert | §5 P05 客户料号关系、§24 P24 单重 |

### 5.3 `production_energy` 表结构重构（V323/V324/V325，根因修复）

- **根因（旧结构）**：原表未加类型区分，折旧、能耗塞 `depreciation_unit_price` / `energy_unit_price` 两列、按 `(material_no,process_no)` 合并进同一行 → 两 Sheet 共用行，无法独立版本。
- **改法**：
  1. 新增列 `price_type`（枚举 `ENERGY`=能耗 / `DEPRECIATION`=折旧）；
  2. 把 `depreciation_unit_price` + `energy_unit_price` **合并为单列 `unit_price`**（原两列已 `DROP COLUMN`）；
  3. P09 折旧写 `price_type=DEPRECIATION`、P10 能耗写 `price_type=ENERGY`，**各写各行、不再共用行**。
- **收益**：来源 Sheet 明确 + 两 Sheet **可各自独立版本管理**（轴 = `system_type, material_no, price_type`，工序整批一组），与 `unit_price` 费用类模型同构。
- **迁移**：`V323` 加列 + 合并列（测试环境采「清空重导」，不做存量拆分）；`V325` 补唯一索引维度（`system_type, material_no, process_no, price_type, equipment_no, calc_version`，含 `price_type` 防 P09/P10 首版撞键）。
- **命名**：`production_energy.unit_price` 列与同名 `unit_price` 表仅名字相近、不同命名空间，语义="单价（按 `price_type` 区分类型）"。

### 5.4 `tooling_cost` 加版本列（V323/V326）

- 现状：原有 `is_current` 但**无版本列** → 无法系统升版。
- 本次：新增版本列 `calc_version`（VARCHAR，与能耗对齐），接入 `VersionedV6Writer`；轴 = `system_type, material_no`，模具明细整批为一组；`V326` 补唯一索引维度（`system_type, material_no, process_no, seq_no, tooling_no, calc_version`）。

### 5.5 `capacity` + `labor_rate` 去触发列、独立版本化

- 甲下任一值变即升版；`capacity` 无触发列（金额/币种/单位变化也升版）。
- `labor_rate` **不再借用 `capacity` 版本号**——原逻辑是人工单价借 `capacity` 返回的版本号做 upsert（非独立升版，若 `capacity` 未升版但人工单价变了会原地覆盖、无历史）；本次改为按 `material_no` 独立聚合、独立按 `(system_type, material_no)` 版本化写入自身 `version_no`。
- 报价侧 `Q14 组装加工费` 的触发列差异（金额原地更新不升版）**不在本期范围**，留档暂不动。

### 5.6 已知不一致 / 已知实现细节（留档）

- **P08 产能/工时单价 vs P09/P10/P11 能耗**：历史上"取用的计算版本"曾有直存 Excel 与系统生成两种口径分叉；本次 tesk-0709 统一为**全部系统自增**，此项分叉已消除。
- **`material_bom_item` 子表 `production_no` 参与内容比较**：见 §6 脚注，`writeVersionedMasterDetail` 当前无独立描述列豁免机制，`production_no` 随其余值列一并进 `childContentColumns`，技术上会参与版本比对，与"描述列不参与比对"的设计原则存在偏差；非本次改动范围。
- **P21 电镀方案 `element_usage` 恒写 `ZERO`**：不同于报价侧 Q16 按公式计算，见 §21 脚注。
- `material_version_mgmt`（P04 版本包）、`fee_config`、`electricity_price`、`production_consumable`/`packaging_consumable`（未见对应 Sheet 落库，实际落 `unit_price`）本期不纳入系统升版范围。
- V44 遗留表（`mat_*` / `plating_fee`）走旧 `VersionedWriter`，本期不动。

---

*文档完*
