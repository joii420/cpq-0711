# CPQ 基础数据表设计 — 独立表方案（合并前）


> **日期**: 2026-04-24
> **方案**: 按 Excel Sheet 独立建表 + 支撑表
> **表数量**: 16 张（不含年降 3 张）
> **定位**: 每个 Sheet 一张物理表，业务语义最清晰

---

## 目录

1. [设计原则](#1-设计原则)
2. [表清单总览](#2-表清单总览)
3. [通用字段](#3-通用字段)
4. [表定义](#4-表定义)
5. [表间关系](#5-表间关系)
6. [优缺点分析](#6-优缺点分析)

---

## 1. 设计原则

- **一 Sheet 一表**：每个 Excel Sheet 映射到一张独立物理表
- **字段 1:1 映射**：Excel 列直接对应物理表列
- **命名前缀**：Excel 来源用 `bd_`（basic_data），非 Excel 来源用业务前缀
- **统一料号主键**：`mat_part.part_no` 作为 VARCHAR 主键，其他表通过 part_no 关联
- **忽略年降**：用户指定，暂不纳入设计

---

## 2. 表清单总览

| # | 表名 | 来源 | 说明 |
|---|------|------|------|
| 1 | `mat_part` | 料号基础资料 | 生产料号主表（含单重） |
| 2 | `mat_customer_part_mapping` | excel(客户料号与宏丰料号的关系) | 客户料号对照 |
| 3 | `bd_unit_weight` | excel(单重) | 料号单重 |
| 4 | `bd_incoming_bom` | excel(来料BOM) | 来料BOM |
| 5 | `bd_element_bom` | excel(元素BOM) | 元素BOM |
| 6 | `bd_component_bom` | excel(组成件BOM及单价) | 组成件BOM（即工序） |
| 7 | `bd_incoming_fixed_fee` | excel(来料固定加工费) | 来料固定加工费 |
| 8 | `bd_incoming_other_fee` | excel(来料其他费用) | 来料其他费用 |
| 9 | `bd_finished_fixed_fee` | excel(成品固定加工费) | 成品固定加工费 |
| 10 | `bd_finished_other_fee` | excel(成品其他费用) | 成品其他费用 |
| 11 | `bd_assembly_fee` | excel(组装加工费) | 组装加工费 |
| 12 | `bd_element_price` | excel(元素单价) | 客户元素单价 |
| 13 | `bd_plating_plan` | excel(电镀方案) | 电镀方案 |
| 14 | `bd_plating_fee` | excel(电镀费用) | 电镀费用 |
| 15 | `exchange_rate` | 非 Excel | 币种汇率 |
| 16 | `tax_rate` | 非 Excel | 客户税率 |

**附加：** `mat_price`（料号/工序单价，报价计算后生成）—— 如需拆分为 `mat_part_price` 和 `mat_process_price` 则共 17 张。

---

## 3. 通用字段

所有 bd_* 和 mat_* 表都包含：

```
id              UUID        PK (bd_unit_weight 除外，part_no 做 PK)
import_batch_id UUID        关联导入批次（Excel 来源表才有）
created_at      TIMESTAMP
updated_at      TIMESTAMP
```

---

## 4. 表定义

### 4.1 `mat_part` — 生产料号主表

| 列名 | 类型 | 说明 |
|------|------|------|
| **part_no** | VARCHAR(64) | **PK** 生产料号 |
| part_name | VARCHAR(128) | 品名 |
| specification | VARCHAR(128) | 规格 |
| size_info | VARCHAR(128) | 尺寸 |
| category_id | UUID | FK → ProductCategory |
| status_code | ENUM [Y, N] | Y 可生产 / N 停产 |

索引：`(category_id, status_code)`

### 4.2 `mat_customer_part_mapping` — 客户料号对照

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

### 4.3 `bd_unit_weight` — 单重

| 列名 | 类型 | 说明 |
|------|------|------|
| part_no | VARCHAR(64) | 料号 (FK → mat_part.part_no) |
| unit_weight | DECIMAL(18,4) | 单重 |
| weight_unit | VARCHAR(16) | 单位（g/pcs） |

唯一约束：`(part_no)`

### 4.4 `bd_incoming_bom` — 来料BOM

| 列名 | 类型 | 说明 |
|------|------|------|
| hf_part_no | VARCHAR(64) | 宏丰料号 |
| seq_no | INT | 项次 |
| input_material_no | VARCHAR(64) | 投入料号 |
| input_material_name | VARCHAR(128) | 投入料号名称 |
| output_material_type | VARCHAR(64) | 产出料号类型 |
| gross_weight | DECIMAL(18,4) | 材料毛重 |
| net_weight | DECIMAL(18,4) | 材料净重 |
| weight_unit | VARCHAR(16) | 重量单位 |
| loss_rate | DECIMAL(10,4) | 损耗率(%) |
| defect_rate | DECIMAL(10,4) | 不良率(%) |

索引：`(hf_part_no, seq_no)`

### 4.5 `bd_element_bom` — 元素BOM

| 列名 | 类型 | 说明 |
|------|------|------|
| hf_part_no | VARCHAR(64) | 宏丰料号 |
| input_material_no | VARCHAR(64) | 投入料号 |
| input_material_name | VARCHAR(128) | 投入料号名称 |
| seq_no | INT | 项次 |
| element_name | VARCHAR(64) | 元素 |
| composition_pct | DECIMAL(10,4) | 组成含量(%) |
| loss_rate | DECIMAL(10,4) | 损耗率% |
| gross_qty | DECIMAL(18,4) | 毛用量 |
| gross_qty_unit | VARCHAR(16) | 毛用量单位 |
| net_qty | DECIMAL(18,4) | 净用量 |
| net_qty_unit | VARCHAR(16) | 净用量单位 |

索引：`(hf_part_no, input_material_no, element_name)`

### 4.6 `bd_component_bom` — 组成件BOM及单价（工序）

| 列名 | 类型 | 说明 |
|------|------|------|
| hf_part_no | VARCHAR(64) | 宏丰料号 |
| seq_no | INT | 项次 |
| process_code | VARCHAR(32) | 工序编号 |
| assembly_process | VARCHAR(64) | 组装工序 |
| sub_seq_no | INT | 项次（子项） |
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

### 4.7 `bd_incoming_fixed_fee` — 来料固定加工费

| 列名 | 类型 | 说明 |
|------|------|------|
| hf_part_no | VARCHAR(64) | 宏丰料号 |
| seq_no | INT | 项次 |
| input_material_no | VARCHAR(64) | 投入料号 |
| input_material_name | VARCHAR(128) | 投入料号名称 |
| fee_value | DECIMAL(18,4) | 值 |
| fee_ratio | DECIMAL(10,4) | 比例(%) |
| currency | VARCHAR(8) | 货币 |
| price_unit | VARCHAR(16) | 计价单位 |
| price_floating | BOOLEAN | 是否随材料价格波动 |
| settlement_rise_ratio | DECIMAL(10,4) | 材料结算涨幅比例(%) |
| fixed_rise_value | DECIMAL(18,4) | 材料固定的涨幅值 |
| rise_currency | VARCHAR(8) | 涨幅货币 |
| rise_unit | VARCHAR(16) | 涨幅单位 |

索引：`(hf_part_no, seq_no)`

### 4.8 `bd_incoming_other_fee` — 来料其他费用

| 列名 | 类型 | 说明 |
|------|------|------|
| hf_part_no | VARCHAR(64) | 宏丰料号 |
| incoming_seq_no | INT | 来料项次 |
| input_material_no | VARCHAR(64) | 投入料号 |
| input_material_name | VARCHAR(128) | 投入料号名称 |
| sub_seq_no | INT | 子项项次 |
| element_name | VARCHAR(128) | 要素名称 |
| fee_value | DECIMAL(18,4) | 值 |
| fee_ratio | DECIMAL(10,4) | 比例(%) |
| currency | VARCHAR(8) | 货币 |
| price_unit | VARCHAR(16) | 计价单位 |

索引：`(hf_part_no, incoming_seq_no, sub_seq_no)`

### 4.9 `bd_finished_fixed_fee` — 成品固定加工费

| 列名 | 类型 | 说明 |
|------|------|------|
| hf_part_no | VARCHAR(64) | 宏丰料号 |
| seq_no | INT | 项次 |
| fee_value | DECIMAL(18,4) | 值 |
| fee_ratio | DECIMAL(10,4) | 比例(%) |
| currency | VARCHAR(8) | 货币 |
| price_unit | VARCHAR(16) | 计价单位 |

索引：`(hf_part_no)`

### 4.10 `bd_finished_other_fee` — 成品其他费用

| 列名 | 类型 | 说明 |
|------|------|------|
| hf_part_no | VARCHAR(64) | 宏丰料号 |
| seq_no | INT | 项次 |
| element_name | VARCHAR(128) | 要素名称 |
| fee_value | DECIMAL(18,4) | 值 |
| fee_ratio | DECIMAL(10,4) | 比例(%) |
| currency | VARCHAR(8) | 货币 |
| price_unit | VARCHAR(16) | 计价单位 |

索引：`(hf_part_no, seq_no)`

### 4.11 `bd_assembly_fee` — 组装加工费

| 列名 | 类型 | 说明 |
|------|------|------|
| hf_part_no | VARCHAR(64) | 宏丰料号 |
| seq_no | INT | 项次 |
| assembly_process | VARCHAR(64) | 组装工序 |
| assembly_fee | DECIMAL(18,4) | 组装加工费 |
| currency | VARCHAR(8) | 货币 |
| price_unit | VARCHAR(16) | 计价单位 |
| reject_rate | DECIMAL(10,4) | 拒收率/不良率(%) |

索引：`(hf_part_no, assembly_process)`

### 4.12 `bd_element_price` — 元素单价（客户级）

| 列名 | 类型 | 说明 |
|------|------|------|
| customer_id | UUID | FK → Customer |
| seq_no | INT | 项次 |
| element_name | VARCHAR(64) | 元素名称（或"所有元素"） |
| source_url | VARCHAR(256) | 网址 |
| source_site | VARCHAR(128) | 网站名称 |
| fetch_rule | VARCHAR(256) | 取用规则 |
| premium_price | DECIMAL(18,4) | 升水价/手续费 |
| currency | VARCHAR(8) | 货币 |
| price_unit | VARCHAR(16) | 计价单位 |

索引：`(customer_id, element_name)`

### 4.13 `bd_plating_plan` — 电镀方案

| 列名 | 类型 | 说明 |
|------|------|------|
| plan_code | VARCHAR(32) | 方案编号 |
| version | VARCHAR(16) | 版本 |
| seq_no | INT | 项次 |
| plating_element | VARCHAR(64) | 电镀元素名称 |
| source_url | VARCHAR(256) | 元素单价来源网址 |
| source_site | VARCHAR(128) | 元素单价来源网站名称 |
| fetch_rule | VARCHAR(256) | 元素单价抓取规则 |
| plating_area | DECIMAL(18,4) | 电镀面积(cm²) |
| coating_thickness | DECIMAL(10,4) | 镀层厚度(μm) |
| plating_requirement | VARCHAR(256) | 电镀要求 |

唯一约束：`(plan_code, version, seq_no)`

### 4.14 `bd_plating_fee` — 电镀费用

| 列名 | 类型 | 说明 |
|------|------|------|
| hf_part_no | VARCHAR(64) | 宏丰料号 |
| plating_plan_code | VARCHAR(32) | 电镀方案编号 |
| plan_version | VARCHAR(16) | 版本编号 |
| plating_process_fee | DECIMAL(18,4) | 电镀加工费 |
| plating_material_fee | DECIMAL(18,4) | 电镀材料费 |
| currency | VARCHAR(8) | 货币 |
| price_unit | VARCHAR(16) | 计价单位 |
| defect_rate | DECIMAL(10,4) | 不良率(%) |

索引：`(hf_part_no)` + `(plating_plan_code, plan_version)`

### 4.15 `exchange_rate` — 币种汇率

| 列名 | 类型 | 说明 |
|------|------|------|
| from_currency | VARCHAR(8) | 源币种 |
| to_currency | VARCHAR(8) | 目标币种（默认 CNY） |
| rate | DECIMAL(10,6) | 汇率 |
| effective_date | DATE | 生效日期 |
| expiry_date | DATE | 失效日期 (nullable) |
| status | ENUM [ACTIVE, EXPIRED] | |

索引：`(from_currency, to_currency, effective_date)`

### 4.16 `tax_rate` — 客户税率

| 列名 | 类型 | 说明 |
|------|------|------|
| customer_id | UUID | FK → Customer (nullable，NULL = 默认税率) |
| tax_type | ENUM [VAT, INCOME_TAX, CUSTOMS, OTHER] | 税种 |
| tax_rate | DECIMAL(10,4) | 税率(%) |
| effective_date | DATE | 生效日期 |
| expiry_date | DATE | 失效日期 (nullable) |
| description | VARCHAR(256) | 说明 |
| status | ENUM [ACTIVE, EXPIRED] | |

索引：`(customer_id, tax_type, effective_date)`

---

## 5. 表间关系

```
mat_part (PK=part_no)
  ├── mat_customer_part_mapping.hf_part_no (FK)
  ├── bd_unit_weight.part_no (FK)
  ├── bd_incoming_bom.hf_part_no (FK)
  ├── bd_element_bom.hf_part_no (FK)
  ├── bd_component_bom.hf_part_no (FK)
  ├── bd_incoming_fixed_fee.hf_part_no (FK)
  ├── bd_incoming_other_fee.hf_part_no (FK)
  ├── bd_finished_fixed_fee.hf_part_no (FK)
  ├── bd_finished_other_fee.hf_part_no (FK)
  ├── bd_assembly_fee.hf_part_no (FK)
  └── bd_plating_fee.hf_part_no (FK)

Customer
  ├── mat_customer_part_mapping.customer_id (FK)
  ├── bd_element_price.customer_id (FK)
  └── tax_rate.customer_id (FK)

bd_plating_plan (plan_code + version 作为业务键)
  └── bd_plating_fee.plating_plan_code (FK 软引用)

ProductCategory
  └── mat_part.category_id (FK，用于核价模板匹配)

exchange_rate (独立，按币种+日期查询)
```

---

## 6. 优缺点分析

### 优点

1. **业务语义清晰** — 看表名即知具体业务含义
2. **字段无稀疏** — 每张表的每个字段都是必要的
3. **ORM 映射简单** — 1 表 1 实体类，代码直观
4. **索引策略聚焦** — 不需要含类型判别的复合索引
5. **变更影响局部** — 修改一张表不影响其他类型
6. **符合规范化** — 符合 3NF
7. **DDD 契合** — 每表对应一个清晰的聚合根
8. **未来独立演化** — 单表可独立分片、归档、归纳

### 缺点

1. **表数量多** — 16 张（+支持表），整体 schema 较大
2. **Flyway 脚本多** — 建表和维护脚本多
3. **结构相似度高** — 费用类 5 张表字段高度相似
4. **同类扩列需多次** — 所有费用表都加某字段要改多张
5. **跨类型查询需 UNION** — 汇总类查询复杂

### 适用场景

- 追求业务语义清晰度，开发规范性
- 预期业务会独立演化，各类型独立扩展
- 团队有较多开发协作
- 对 ORM 代码生成和维护有较高要求
- 能接受较多的 Flyway 迁移脚本

---

## 附录：支持性实体（非 Excel 来源）

下列实体不是基础数据 Excel 映射，是系统运行时所需：

- **ProductDataPool** — 产品数据池（按批次存储导入的树形快照）
- **ImportRecord** — 导入记录
- **mat_price** — 报价计算产生的料号/工序单价（如需独立可拆为 mat_part_price 和 mat_process_price）
