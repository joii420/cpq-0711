# CPQ 基础数据表设计 — 合并方案（10 张表）

> **日期**: 2026-04-24
> **方案**: 按业务维度合并 + 类型字段区分
> **表数量**: 10 张
> **定位**: 减少表数量，通过类型字段扩展

---

## 目录

1. [设计原则](#1-设计原则)
2. [表清单总览](#2-表清单总览)
3. [通用字段](#3-通用字段)
4. [表定义](#4-表定义)
5. [表间关系](#5-表间关系)
6. [Excel Sheet → 物理表映射](#6-excel-sheet--物理表映射)
7. [优缺点分析](#7-优缺点分析)

---

## 1. 设计原则

- **业务域合并**：结构相似的 Sheet 合并到同一张表
- **类型字段区分**：用 `bom_type` / `fee_type` / `target_type` 枚举标识具体业务
- **公共字段提取**：所有同类表的公共字段作为正式列，特有字段作为"维度字段"（dim_*）或类型专用字段
- **统一料号主键**：`mat_part.part_no` 作为 VARCHAR 主键
- **忽略年降**：用户指定，暂不纳入设计

---

## 2. 表清单总览

| # | 表名 | 合并来源 | 类型字段 | 来源 |
|---|------|---------|---------|------|
| 1 | `mat_customer_part_mapping` | 客户料号与宏丰料号的关系 | — | Excel |
| 2 | `mat_part` | 料号基础资料 + 单重 | — | Excel + 非 Excel |
| 3 | `mat_bom` | 来料BOM + 元素BOM | `bom_type` | Excel (2→1) |
| 4 | `mat_process` | 组成件BOM及单价 | — | Excel |
| 5 | `mat_price` | 料号单价 + 工序单价 + 元素单价 | `target_type` | 非 Excel + Excel(元素单价) |
| 6 | `plating_plan` | 电镀方案（移除 source 字段） | — | Excel |
| 7 | `exchange_rate` | 币种汇率 | — | 非 Excel |
| 8 | `tax_rate` | 客户税率 | — | 非 Excel |
| 9 | `mat_fee` | 来料固定/成品固定/成品其他/组装加工费 | `fee_type` | Excel (4→1) |
| 10 | `plating_fee` | 电镀费用 | — | Excel |

**合并统计：** Excel 13 Sheet → 合并为 8 张；加 2 张非 Excel（exchange_rate, tax_rate）= **10 张**

---

## 3. 通用字段

所有表都包含：

```
id              UUID        PK (mat_part 除外，part_no 做 PK)
import_batch_id UUID        关联导入批次（Excel 来源才有）
created_at      TIMESTAMP
updated_at      TIMESTAMP
```

---

## 4. 表定义

### 4.1 `mat_customer_part_mapping` — 客户料号对照

**来源：** excel(客户料号与宏丰料号的关系)

| 列名 | 类型 | 说明 |
|------|------|------|
| customer_id | UUID | FK → Customer |
| customer_part_name | VARCHAR(128) | 客户料号名称 |
| customer_product_no | VARCHAR(64) | 客户产品编号 |
| customer_drawing_no | VARCHAR(64) | 客户图号 |
| hf_part_no | VARCHAR(64) | 生产料号 (FK → mat_part.part_no) |
| payment_method | VARCHAR(64) | 付款方式 |
| base_currency | VARCHAR(8) | 基础货币 |
| quote_currency | VARCHAR(8) | 报价货币 |

唯一约束：`(customer_id, customer_product_no)`

> 该表替代原 Product 表职责

### 4.2 `mat_part` — 生产料号（含单重）

**来源：** 料号基础资料 + excel(单重)

| 列名 | 类型 | 说明 |
|------|------|------|
| **part_no** | VARCHAR(64) | **PK** 生产料号 |
| part_name | VARCHAR(128) | 品名 |
| specification | VARCHAR(128) | 规格 |
| size_info | VARCHAR(128) | 尺寸 |
| category_id | UUID | FK → ProductCategory |
| unit_weight | DECIMAL(18,4) | 单重(g/pcs) |
| weight_unit | VARCHAR(16) | 重量单位 |
| status_code | ENUM [Y, N] | Y 可生产 / N 停产 |

索引：`(category_id, status_code)`

### 4.3 `mat_bom` — 统一 BOM 表

**来源合并：** excel(来料BOM) + excel(元素BOM)

| 列名 | 类型 | 说明 |
|------|------|------|
| `bom_type` | ENUM | **类型**: `INCOMING` 来料BOM / `ELEMENT` 元素BOM |
| hf_part_no | VARCHAR(64) | 生产料号 (公共) |
| seq_no | INT | 项次 (公共) |
| input_material_no | VARCHAR(64) | 投入料号 (公共) |
| input_material_name | VARCHAR(128) | 投入料号名称 (公共) |
| loss_rate | DECIMAL(10,4) | 损耗率(%) (公共) |
| **-- 数量字段（合并后公共）--** | | |
| gross_qty | DECIMAL(18,4) | 毛用量 / 材料毛重 |
| net_qty | DECIMAL(18,4) | 净用量 / 材料净重 |
| gross_unit | VARCHAR(16) | 毛用量单位 / 重量单位 |
| net_unit | VARCHAR(16) | 净用量单位（ELEMENT 可能不同，INCOMING 留空） |
| **-- INCOMING 专用 --** | | |
| output_material_type | VARCHAR(64) | 产出料号类型 |
| defect_rate | DECIMAL(10,4) | 不良率(%) |
| **-- ELEMENT 专用 --** | | |
| element_name | VARCHAR(64) | 元素 |
| composition_pct | DECIMAL(10,4) | 组成含量(%) |

索引：`(bom_type, hf_part_no, seq_no)` + `(bom_type, input_material_no)`

### 4.4 `mat_process` — 组成件BOM及单价（工序）

**来源：** excel(组成件BOM及单价)

| 列名 | 类型 | 说明 |
|------|------|------|
| hf_part_no | VARCHAR(64) | 生产料号 |
| seq_no | INT | 项次 |
| process_code | VARCHAR(32) | 工序编号 |
| assembly_process | VARCHAR(64) | 组装工序 |
| sub_seq_no | INT | 子项项次 |
| component_part_no | VARCHAR(64) | 组成件料号 |
| component_name | VARCHAR(128) | 组成件名称 |
| supplier_code | VARCHAR(32) | 供应商编号 |
| supplier_name | VARCHAR(128) | 供应商名称 |
| quantity | DECIMAL(18,4) | 组成数量 |
| quantity_unit | VARCHAR(16) | 组成单位 |
| unit_price | DECIMAL(18,4) | 单价 |
| freight | DECIMAL(18,4) | 运费 |
| currency | VARCHAR(8) | 货币 |
| price_unit | VARCHAR(16) | 计价单位 |

索引：`(hf_part_no, seq_no)` + `(process_code)` + `(assembly_process)`

### 4.5 `mat_price` — 统一价格表

**来源合并：** 料号单价 + 工序单价 + excel(元素单价)

| 列名 | 类型 | 说明 |
|------|------|------|
| `target_type` | ENUM | **类型**: `PART` 料号单价 / `PROCESS` 工序单价 / `ELEMENT` 元素单价 |
| target_no | VARCHAR(64) | 料号 / 工序编号 / 元素名称 |
| target_name | VARCHAR(128) | 名称（冗余展示） |
| customer_id | UUID | FK → Customer (ELEMENT 必填，其他可选) |
| **-- 金额字段 --** | | |
| unit_price | DECIMAL(18,4) | 单价（PART/PROCESS 用） |
| premium_price | DECIMAL(18,4) | 升水价（ELEMENT 用） |
| currency | VARCHAR(8) | 货币 |
| price_unit | VARCHAR(16) | 计价单位 |
| **-- 时效字段 --** | | |
| effective_date | DATE | 生效日期 |
| expiry_date | DATE | 失效日期 (nullable) |
| status | ENUM [ACTIVE, EXPIRED] | |
| **-- PART/PROCESS 专用 --** | | |
| source_quotation_id | UUID | 来源报价单ID |
| **-- ELEMENT 专用 --** | | |
| seq_no | INT | 项次 |
| source_url | VARCHAR(256) | 来源网址 |
| source_site | VARCHAR(128) | 来源网站名称 |
| fetch_rule | VARCHAR(256) | 取用规则 |

索引：`(target_type, target_no, customer_id, effective_date)` + `(customer_id, target_type)` + `(source_quotation_id)`

**各 target_type 字段使用情况：**

| 字段 | PART | PROCESS | ELEMENT |
|------|------|---------|---------|
| target_no | 料号 | 工序编号 | 元素名称 |
| customer_id | 可选 | 可选 | 必填 |
| unit_price | ✓ | ✓ | — |
| premium_price | — | — | ✓ |
| source_quotation_id | ✓ | ✓ | — |
| source_url/site/rule | — | — | ✓ |
| seq_no | — | — | ✓ |

### 4.6 `plating_plan` — 电镀方案

**来源：** excel(电镀方案)（移除 source_url/source_site/fetch_rule，迁移到 mat_price）

| 列名 | 类型 | 说明 |
|------|------|------|
| plan_code | VARCHAR(32) | 方案编号 |
| version | VARCHAR(16) | 版本 |
| seq_no | INT | 项次 |
| plating_element | VARCHAR(64) | 电镀元素名称 |
| plating_area | DECIMAL(18,4) | 电镀面积(cm²) |
| coating_thickness | DECIMAL(10,4) | 镀层厚度(μm) |
| plating_requirement | VARCHAR(256) | 电镀要求 |

唯一约束：`(plan_code, version, seq_no)`

### 4.7 `exchange_rate` — 币种汇率

**来源：** 非 Excel

| 列名 | 类型 | 说明 |
|------|------|------|
| from_currency | VARCHAR(8) | 源币种 |
| to_currency | VARCHAR(8) | 目标币种（默认 CNY） |
| rate | DECIMAL(10,6) | 汇率 |
| effective_date | DATE | 生效日期 |
| expiry_date | DATE | 失效日期 (nullable) |
| status | ENUM [ACTIVE, EXPIRED] | |

索引：`(from_currency, to_currency, effective_date)`

### 4.8 `tax_rate` — 客户税率

**来源：** 非 Excel

| 列名 | 类型 | 说明 |
|------|------|------|
| customer_id | UUID | FK → Customer (nullable) |
| tax_type | ENUM [VAT, INCOME_TAX, CUSTOMS, OTHER] | 税种 |
| tax_rate | DECIMAL(10,4) | 税率(%) |
| effective_date | DATE | 生效日期 |
| expiry_date | DATE | 失效日期 (nullable) |
| description | VARCHAR(256) | 说明 |
| status | ENUM [ACTIVE, EXPIRED] | |

索引：`(customer_id, tax_type, effective_date)`

### 4.9 `mat_fee` — 统一费用表

**来源合并：** excel(来料固定加工费) + excel(来料其他费用) + excel(成品固定加工费) + excel(成品其他费用) + excel(组装加工费)

| 列名 | 类型 | 说明 |
|------|------|------|
| `fee_type` | ENUM | **类型**: `INCOMING_FIXED` / `INCOMING_OTHER` / `FINISHED_FIXED` / `FINISHED_OTHER` / `ASSEMBLY_PROCESS` |
| hf_part_no | VARCHAR(64) | 生产料号 (公共) |
| seq_no | INT | 项次 (公共) |
| fee_value | DECIMAL(18,4) | 值（组装时存 assembly_fee） |
| fee_ratio | DECIMAL(10,4) | 比例(%) |
| currency | VARCHAR(8) | 货币 |
| price_unit | VARCHAR(16) | 计价单位 |
| **-- 维度字段 --** | | |
| dim_input_material_no | VARCHAR(64) | 投入料号（来料类用） |
| dim_input_material_name | VARCHAR(128) | 投入料号名称（来料类用） |
| dim_element_name | VARCHAR(128) | 要素名称（其他费用类用） |
| dim_assembly_process | VARCHAR(64) | 组装工序（ASSEMBLY 用） |
| dim_sub_seq_no | INT | 子项项次（来料其他费用用） |
| **-- INCOMING_FIXED 专用 --** | | |
| price_floating | BOOLEAN | 是否随材料价格波动 |
| settlement_rise_ratio | DECIMAL(10,4) | 材料结算涨幅比例(%) |
| fixed_rise_value | DECIMAL(18,4) | 材料固定的涨幅值 |
| rise_currency | VARCHAR(8) | 涨幅货币 |
| rise_unit | VARCHAR(16) | 涨幅单位 |
| **-- ASSEMBLY_PROCESS 专用 --** | | |
| reject_rate | DECIMAL(10,4) | 拒收率/不良率(%) |

索引：`(fee_type, hf_part_no, seq_no)` + `(fee_type, dim_input_material_no)` + `(fee_type, dim_assembly_process)`

**各 fee_type 字段使用情况：**

| 字段 | INCOMING_FIXED | INCOMING_OTHER | FINISHED_FIXED | FINISHED_OTHER | ASSEMBLY_PROCESS |
|------|:---:|:---:|:---:|:---:|:---:|
| fee_value | ✓ | ✓ | ✓ | ✓ | ✓ |
| fee_ratio | ✓ | ✓ | ✓ | ✓ | — |
| dim_input_material_no | ✓ | ✓ | — | — | — |
| dim_element_name | — | ✓ | — | ✓ | — |
| dim_assembly_process | — | — | — | — | ✓ |
| price_floating | ✓ | — | — | — | — |
| rise_* | ✓ | — | — | — | — |
| reject_rate | — | — | — | — | ✓ |

### 4.10 `plating_fee` — 电镀费用

**来源：** excel(电镀费用)

| 列名 | 类型 | 说明 |
|------|------|------|
| hf_part_no | VARCHAR(64) | 生产料号 |
| plating_plan_code | VARCHAR(32) | 电镀方案编号 |
| plan_version | VARCHAR(16) | 版本编号 |
| plating_process_fee | DECIMAL(18,4) | 电镀加工费 |
| plating_material_fee | DECIMAL(18,4) | 电镀材料费 |
| currency | VARCHAR(8) | 货币 |
| price_unit | VARCHAR(16) | 计价单位 |
| defect_rate | DECIMAL(10,4) | 不良率(%) |

索引：`(hf_part_no)` + `(plating_plan_code, plan_version)`

---

## 5. 表间关系

```
Customer
  ├── mat_customer_part_mapping.customer_id (FK)
  ├── mat_price[target_type=ELEMENT].customer_id (FK，客户元素单价)
  └── tax_rate.customer_id (FK)

mat_part (PK=part_no)
  ├── mat_customer_part_mapping.hf_part_no (FK)
  ├── mat_bom.hf_part_no (FK)
  ├── mat_process.hf_part_no (FK)
  ├── mat_fee.hf_part_no (FK)
  └── plating_fee.hf_part_no (FK)

ProductCategory
  └── mat_part.category_id (FK，核价模板匹配依据)

mat_price
  ├── target_type=PART:    报价生成的料号单价
  ├── target_type=PROCESS: 报价生成的工序单价
  └── target_type=ELEMENT: 客户元素单价（含抓取配置）

plating_plan (方案定义库)
  └── plating_fee.plating_plan_code (软引用)

exchange_rate (独立，按币种+日期查询)

类型字段体系:
  mat_bom.bom_type      [INCOMING / ELEMENT]
  mat_price.target_type [PART / PROCESS / ELEMENT]
  mat_fee.fee_type      [INCOMING_FIXED / INCOMING_OTHER / FINISHED_FIXED / FINISHED_OTHER / ASSEMBLY_PROCESS]
```

---

## 6. Excel Sheet → 物理表映射

| Excel Sheet | 物理表 | 类型字段值 |
|-----------|-------|----------|
| 客户料号与宏丰料号的关系 | `mat_customer_part_mapping` | — |
| 单重 | `mat_part.unit_weight` | — |
| 来料BOM | `mat_bom` | `bom_type = INCOMING` |
| 元素BOM | `mat_bom` | `bom_type = ELEMENT` |
| 组成件BOM及单价 | `mat_process` | — |
| 元素单价 | `mat_price` | `target_type = ELEMENT` |
| 电镀方案 | `plating_plan` + `mat_price`(source 部分) | plating_plan 本体 + mat_price[ELEMENT] |
| 电镀费用 | `plating_fee` | — |
| 来料固定加工费 | `mat_fee` | `fee_type = INCOMING_FIXED` |
| 来料其他费用 | `mat_fee` | `fee_type = INCOMING_OTHER` |
| 成品固定加工费 | `mat_fee` | `fee_type = FINISHED_FIXED` |
| 成品其他费用 | `mat_fee` | `fee_type = FINISHED_OTHER` |
| 组装加工费 | `mat_fee` | `fee_type = ASSEMBLY_PROCESS` |
| 来料年降 | (忽略) | |
| 组装加工费年降 | (忽略) | |
| 年降系数 | (忽略) | |

---

## 7. 优缺点分析

### 优点

1. **表数量少** — 10 张，整体 schema 简洁
2. **扩展便利** — 新增费用/BOM 类型只需加枚举值，无需建表
3. **Flyway 脚本少** — 建表和维护脚本约为独立方案的 60%
4. **同类扩列统一** — 费用共用字段一次扩展
5. **跨类型查询方便** — 如"某产品所有费用"一个 SQL 搞定
6. **初期实施快** — 建表工作量小
7. **代码重用性高** — 费用类的 CRUD 可以复用

### 缺点

1. **字段稀疏** — mat_fee 每行平均只用 40%-60% 字段
2. **业务语义不直观** — 表名需结合 type 字段才能完整理解
3. **ORM 映射复杂** — 单实体多态或继承映射
4. **查询必带 type 过滤** — `WHERE fee_type = X` 不可省
5. **索引依赖复合列** — 需要 (type, ...) 的复合索引
6. **变更影响范围大** — 改 mat_fee 结构影响所有 5 种费用类型
7. **违反部分规范化** — 稀疏字段接近 OTLT 反模式
8. **未来业务独立化成本高** — 若某类费用需独立字段/约束，需要从合并表拆出

### 适用场景

- 追求实施效率和简洁 schema
- 业务类型稳定，不会频繁独立演化
- 团队规模小，开发维护资源有限
- 能接受稀疏字段和类型判断代码
- 扩列频率低或可接受 Flyway 集中管理

---

## 附录：支持性实体

- **ProductDataPool** — 产品数据池（按批次存储导入的树形快照）
- **ImportRecord** — 导入记录
- **BasicDataConfig / BasicDataAttribute** — 基础数据元数据（Sheet 层和列层配置）

---

## 附录：与独立方案的对比

| 对比维度 | 独立方案（16 张） | 合并方案（10 张） |
|---------|----------------|----------------|
| 表数量 | 16 | 10 |
| Schema 简洁度 | 低 | 高 |
| 业务语义 | 高 | 中 |
| 字段密度 | 高 | 低 |
| ORM 复杂度 | 低 | 中 |
| 扩展便利性 | 中（需建表） | 高（加枚举） |
| 初期工作量 | 较大 | 较小 |
| 长期演化弹性 | 高 | 中 |
