# 报价模板配置参考手册 — Excel基础结构 v1.5

> 本文档记录「报价标准模板-Excel基础结构」截至 V141 迁移后的完整结构，供后续配置同类 QUOTATION 模板时参考。
> 数据来源：SQL 迁移文件 V128 ~ V141，不依赖运行时 API 调用。

---

## 1. 模板概览

| 属性 | 值 |
|---|---|
| 模板名 | 报价标准模板-Excel基础结构 v1.0（SQL 中原始名；文档化版本称 v1.5，对应第 141 次迁移后的状态） |
| Template ID | gen_random_uuid() 动态生成（V128 创建时）；实际 ID 查询：`SELECT id FROM template WHERE name='报价标准模板-Excel基础结构 v1.0' AND template_kind='QUOTATION'` |
| 用户任务中的参考 ID | `820f319a-b92e-46f0-a788-bc72cad46c37`（运行时实例 ID，仅供参考） |
| status | PUBLISHED（V117 模式发布；V140 后 snapshot 已手动重建） |
| version | 1.0（数据库 version 字段） |
| template_kind | QUOTATION |
| customer_id | NULL（通用兜底模板，不绑定特定客户） |
| 组件目录 | 报价模板组件V3-Excel结构（id=`c1d2e3f4-0003-4003-8003-000000000003`） |
| 总 tab 数 | 9 个（V128 建 7 个 + V139/V140 补第 8 个组件费用；snapshot 含 9 条包括历史 SUBTOTAL） |

### 1.1 Tab 一览表

| sort_order | tab 名称 | 组件 code | 数据驱动视图 | component_type |
|---|---|---|---|---|
| 0 | 料件 | COMP-QX-PART-INFO | v_q_part_info_merged | NORMAL |
| 1 | 来料 | COMP-QX-INCOMING | v_q_incoming_merged | NORMAL |
| 2 | 元素 | COMP-QX-ELEMENT | v_q_element_merged | NORMAL |
| 3 | 成品 | COMP-QX-FINISHED | v_q_finished_merged | NORMAL |
| 4 | 组成件 | COMP-QX-COMPONENT | v_q_component_merged | NORMAL |
| 5 | 组装加工 | COMP-QX-ASSEMBLY | v_q_assembly_merged | NORMAL |
| 6 | 电镀 | COMP-QX-PLATING | v_q_plating_merged | NORMAL |
| 7 或 8 | 组件费用 | COMP-QX-COMPONENT-FEE | v_q_component_fee_merged | NORMAL |
| 末位 | 小计1（历史遗留）| COMP-Q-TOTAL 或同类 | 无驱动视图 | SUBTOTAL |

> 注：sort_order=7/8 的「组件费用」因 V139/V140 两次 INSERT 逻辑差异，实际 sort_order 以 `template_component` 表数据为准（V140 期望 9 个组件，末尾是历史 SUBTOTAL）。

---

## 2. 组件目录与组件配置

**目录**：报价模板组件V3-Excel结构（id=`c1d2e3f4-0003-4003-8003-000000000003`，sort_order=82，parent_id=NULL）

---

### 2.1 COMP-QX-PART-INFO 料件

| 字段 | 值 |
|---|---|
| code | COMP-QX-PART-INFO |
| name | 料件 |
| component_type | NORMAL |
| data_driver_path | v_q_part_info_merged |
| 字段数 | 10 |

**字段列表：**

| # | 字段名（中文） | basic_data_path 列 | is_amount | is_subtotal |
|---|---|---|---|---|
| 1 | 宏丰料号 | v_q_part_info_merged.hf_part_no | 否 | 否 |
| 2 | 客户料号名称 | v_q_part_info_merged.customer_part_name | 否 | 否 |
| 3 | 客户产品编号 | v_q_part_info_merged.customer_product_no | 否 | 否 |
| 4 | 客户图号 | v_q_part_info_merged.customer_drawing_no | 否 | 否 |
| 5 | 付款方式 | v_q_part_info_merged.payment_method | 否 | 否 |
| 6 | 基础货币 | v_q_part_info_merged.base_currency | 否 | 否 |
| 7 | 报价货币 | v_q_part_info_merged.quote_currency | 否 | 否 |
| 8 | 汇率 | v_q_part_info_merged.exchange_rate | 否 | 否 |
| 9 | 单重(g/pcs) | v_q_part_info_merged.unit_weight | 否 | 否 |
| 10 | 重量单位 | v_q_part_info_merged.weight_unit | 否 | 否 |

---

### 2.2 COMP-QX-INCOMING 来料

| 字段 | 值 |
|---|---|
| code | COMP-QX-INCOMING |
| name | 来料 |
| component_type | NORMAL |
| data_driver_path | v_q_incoming_merged |
| 字段数 | 23 |

**字段列表：**

| # | 字段名（中文） | basic_data_path 列 | is_amount | is_subtotal |
|---|---|---|---|---|
| 1 | 来源 | v_q_incoming_merged.source_type | 否 | 否 |
| 2 | 宏丰料号 | v_q_incoming_merged.hf_part_no | 否 | 否 |
| 3 | 项次 | v_q_incoming_merged.seq_no | 否 | 否 |
| 4 | 投入料号 | v_q_incoming_merged.input_material_no | 否 | 否 |
| 5 | 投入料号名称 | v_q_incoming_merged.input_material_name | 否 | 否 |
| 6 | 产出料号类型 | v_q_incoming_merged.output_material_type | 否 | 否 |
| 7 | 材料毛重 | v_q_incoming_merged.gross_qty | 否 | 否 |
| 8 | 材料净重 | v_q_incoming_merged.net_qty | 否 | 否 |
| 9 | 重量单位 | v_q_incoming_merged.weight_unit | 否 | 否 |
| 10 | 损耗率(%) | v_q_incoming_merged.loss_rate | 否 | 否 |
| 11 | 不良率(%) | v_q_incoming_merged.defect_rate | 否 | 否 |
| 12 | 项次2 | v_q_incoming_merged.sub_seq_no | 否 | 否 |
| 13 | 要素名称 | v_q_incoming_merged.element_name | 否 | 否 |
| 14 | 值 | v_q_incoming_merged.fee_value | 是 | 否 |
| 15 | 比例(%) | v_q_incoming_merged.fee_ratio | 否 | 否 |
| 16 | 货币 | v_q_incoming_merged.currency | 否 | 否 |
| 17 | 计价单位 | v_q_incoming_merged.price_unit | 否 | 否 |
| 18 | 是否随材料价格波动 | v_q_incoming_merged.price_floating | 否 | 否 |
| 19 | 材料结算涨幅比例(%) | v_q_incoming_merged.settlement_rise_ratio | 否 | 否 |
| 20 | 材料固定涨幅值 | v_q_incoming_merged.fixed_rise_value | 是 | 否 |
| 21 | 涨幅货币 | v_q_incoming_merged.rise_currency | 否 | 否 |
| 22 | 涨幅单位 | v_q_incoming_merged.rise_unit | 否 | 否 |
| 23 | 回收折扣(%) | v_q_incoming_merged.recycle_pct | 否 | 否 |

---

### 2.3 COMP-QX-ELEMENT 元素

| 字段 | 值 |
|---|---|
| code | COMP-QX-ELEMENT |
| name | 元素 |
| component_type | NORMAL |
| data_driver_path | v_q_element_merged |
| 字段数 | 13 |

**字段列表：**

| # | 字段名（中文） | basic_data_path 列 | is_amount | is_subtotal |
|---|---|---|---|---|
| 1 | 来源 | v_q_element_merged.source_type | 否 | 否 |
| 2 | 宏丰料号 | v_q_element_merged.hf_part_no | 否 | 否 |
| 3 | 投入料号 | v_q_element_merged.input_material_no | 否 | 否 |
| 4 | 投入料号名称 | v_q_element_merged.input_material_name | 否 | 否 |
| 5 | 项次 | v_q_element_merged.seq_no | 否 | 否 |
| 6 | 元素 | v_q_element_merged.element_name | 否 | 否 |
| 7 | 组成含量(%) | v_q_element_merged.composition_pct | 否 | 否 |
| 8 | 损耗率(%) | v_q_element_merged.loss_rate | 否 | 否 |
| 9 | 毛用量 | v_q_element_merged.gross_qty | 否 | 否 |
| 10 | 毛用量单位 | v_q_element_merged.gross_unit | 否 | 否 |
| 11 | 净用量 | v_q_element_merged.net_qty | 否 | 否 |
| 12 | 净用量单位 | v_q_element_merged.net_unit | 否 | 否 |
| 13 | 回收折扣(%) | v_q_element_merged.recycle_pct | 否 | 否 |

---

### 2.4 COMP-QX-FINISHED 成品

| 字段 | 值 |
|---|---|
| code | COMP-QX-FINISHED |
| name | 成品 |
| component_type | NORMAL |
| data_driver_path | v_q_finished_merged |
| 字段数 | 8 |

**字段列表：**

| # | 字段名（中文） | basic_data_path 列 | is_amount | is_subtotal |
|---|---|---|---|---|
| 1 | 来源 | v_q_finished_merged.source_type | 否 | 否 |
| 2 | 宏丰料号 | v_q_finished_merged.hf_part_no | 否 | 否 |
| 3 | 项次 | v_q_finished_merged.seq_no | 否 | 否 |
| 4 | 要素名称 | v_q_finished_merged.element_name | 否 | 否 |
| 5 | 值 | v_q_finished_merged.fee_value | 是 | 否 |
| 6 | 比例(%) | v_q_finished_merged.fee_ratio | 否 | 否 |
| 7 | 货币 | v_q_finished_merged.currency | 否 | 否 |
| 8 | 计价单位 | v_q_finished_merged.price_unit | 否 | 否 |

---

### 2.5 COMP-QX-COMPONENT 组成件

| 字段 | 值 |
|---|---|
| code | COMP-QX-COMPONENT |
| name | 组成件 |
| component_type | NORMAL |
| data_driver_path | v_q_component_merged |
| 字段数 | 15 |

**字段列表：**

| # | 字段名（中文） | basic_data_path 列 | is_amount | is_subtotal |
|---|---|---|---|---|
| 1 | 宏丰料号 | v_q_component_merged.hf_part_no | 否 | 否 |
| 2 | 项次 | v_q_component_merged.seq_no | 否 | 否 |
| 3 | 工序编号 | v_q_component_merged.process_code | 否 | 否 |
| 4 | 组装工序 | v_q_component_merged.assembly_process | 否 | 否 |
| 5 | 项次2 | v_q_component_merged.sub_seq_no | 否 | 否 |
| 6 | 组成件料号 | v_q_component_merged.component_part_no | 否 | 否 |
| 7 | 组成件名称 | v_q_component_merged.component_name | 否 | 否 |
| 8 | 供应商编号 | v_q_component_merged.supplier_code | 否 | 否 |
| 9 | 供应商名称 | v_q_component_merged.supplier_name | 否 | 否 |
| 10 | 组成数量 | v_q_component_merged.quantity | 否 | 否 |
| 11 | 组成单位 | v_q_component_merged.quantity_unit | 否 | 否 |
| 12 | 单价 | v_q_component_merged.unit_price | 是 | 否 |
| 13 | 运费 | v_q_component_merged.freight | 是 | 否 |
| 14 | 货币 | v_q_component_merged.currency | 否 | 否 |
| 15 | 计价单位 | v_q_component_merged.price_unit | 否 | 否 |

---

### 2.6 COMP-QX-ASSEMBLY 组装加工

| 字段 | 值 |
|---|---|
| code | COMP-QX-ASSEMBLY |
| name | 组装加工 |
| component_type | NORMAL |
| data_driver_path | v_q_assembly_merged |
| 字段数 | 7 |

**字段列表：**

| # | 字段名（中文） | basic_data_path 列 | is_amount | is_subtotal |
|---|---|---|---|---|
| 1 | 宏丰料号 | v_q_assembly_merged.hf_part_no | 否 | 否 |
| 2 | 项次 | v_q_assembly_merged.seq_no | 否 | 否 |
| 3 | 组装工序 | v_q_assembly_merged.assembly_process | 否 | 否 |
| 4 | 组装加工费 | v_q_assembly_merged.fee_value | 是 | 否 |
| 5 | 货币 | v_q_assembly_merged.currency | 否 | 否 |
| 6 | 计价单位 | v_q_assembly_merged.price_unit | 否 | 否 |
| 7 | 拒收率/不良率(%) | v_q_assembly_merged.reject_rate | 否 | 否 |

---

### 2.7 COMP-QX-PLATING 电镀

| 字段 | 值 |
|---|---|
| code | COMP-QX-PLATING |
| name | 电镀 |
| component_type | NORMAL |
| data_driver_path | v_q_plating_merged |
| 字段数 | 14 |

**字段列表：**

| # | 字段名（中文） | basic_data_path 列 | is_amount | is_subtotal |
|---|---|---|---|---|
| 1 | 来源 | v_q_plating_merged.source_type | 否 | 否 |
| 2 | 宏丰料号 | v_q_plating_merged.hf_part_no | 否 | 否 |
| 3 | 方案编号 | v_q_plating_merged.plan_code | 否 | 否 |
| 4 | 版本 | v_q_plating_merged.plan_version | 否 | 否 |
| 5 | 项次 | v_q_plating_merged.seq_no | 否 | 否 |
| 6 | 电镀元素名称 | v_q_plating_merged.plating_element | 否 | 否 |
| 7 | 电镀面积(cm2) | v_q_plating_merged.plating_area | 否 | 否 |
| 8 | 镀层厚度(um) | v_q_plating_merged.coating_thickness | 否 | 否 |
| 9 | 电镀要求 | v_q_plating_merged.plating_requirement | 否 | 否 |
| 10 | 电镀加工费 | v_q_plating_merged.plating_process_fee | 是 | 否 |
| 11 | 电镀材料费 | v_q_plating_merged.plating_material_fee | 是 | 否 |
| 12 | 货币 | v_q_plating_merged.currency | 否 | 否 |
| 13 | 计价单位 | v_q_plating_merged.price_unit | 否 | 否 |
| 14 | 不良率(%) | v_q_plating_merged.defect_rate | 否 | 否 |

---

### 2.8 COMP-QX-COMPONENT-FEE 组件费用

| 字段 | 值 |
|---|---|
| code | COMP-QX-COMPONENT-FEE |
| name | 组件费用 |
| component_type | NORMAL |
| data_driver_path | v_q_component_fee_merged |
| 字段数 | 10 |

**字段列表：**

| # | 字段名（中文） | basic_data_path 列 | is_amount | is_subtotal |
|---|---|---|---|---|
| 1 | 宏丰料号 | v_q_component_fee_merged.hf_part_no | 否 | 否 |
| 2 | 项次 | v_q_component_fee_merged.seq_no | 否 | 否 |
| 3 | 组装工序 | v_q_component_fee_merged.assembly_process | 否 | 否 |
| 4 | 组件项次 | v_q_component_fee_merged.sub_seq_no | 否 | 否 |
| 5 | 组成件料号 | v_q_component_fee_merged.component_part_no | 否 | 否 |
| 6 | 组成件名称 | v_q_component_fee_merged.component_name | 否 | 否 |
| 7 | 要素名称 | v_q_component_fee_merged.element_name | 否 | 否 |
| 8 | 值 | v_q_component_fee_merged.fee_value | 是 | 否 |
| 9 | 货币 | v_q_component_fee_merged.currency | 否 | 否 |
| 10 | 计价单位 | v_q_component_fee_merged.price_unit | 否 | 否 |

---

## 3. 视图设计（8 个合并视图）

### 3.1 v_q_part_info_merged — 料件视图

| 属性 | 说明 |
|---|---|
| 来源物理表 | mat_customer_part_mapping、mat_part、exchange_rate |
| 拼接方式 | LEFT JOIN（单行，不 UNION ALL） |
| source_type 取值 | `'PART'`（固定字符串） |
| 关键过滤条件 | exchange_rate.is_current = true |
| 百分比 ×100 列 | 无 |
| 特殊说明 | 按 (customer_id, base_currency, quote_currency) 关联汇率；无汇率配置时 exchange_rate 列为 NULL 不报错 |

**SQL 结构摘要：**

```sql
SELECT 'PART' AS source_type,
       m.hf_part_no, m.customer_part_name, m.customer_product_no,
       m.customer_drawing_no, m.payment_method, m.base_currency, m.quote_currency,
       er.rate AS exchange_rate,
       p.unit_weight, p.weight_unit
FROM mat_customer_part_mapping m
LEFT JOIN mat_part p ON p.part_no = m.hf_part_no
LEFT JOIN exchange_rate er
    ON  er.customer_id   = m.customer_id
    AND er.from_currency = m.base_currency
    AND er.to_currency   = m.quote_currency
    AND er.is_current    = true
```

---

### 3.2 v_q_incoming_merged — 来料视图

| 属性 | 说明 |
|---|---|
| 来源物理表 | mat_bom、mat_fee |
| 拼接方式 | UNION ALL（4 来源） |
| source_type 取值 | `'BOM'` / `'INCOMING_FIXED'` / `'INCOMING_OTHER'` / `'MATERIAL_RECYCLE'` |
| 关键过滤条件 | mat_bom: bom_type='INCOMING'；mat_fee 各支: is_current=true |
| 百分比 ×100 列（V133修复） | loss_rate × 100、defect_rate × 100（来自 mat_bom）；fee_ratio × 100、settlement_rise_ratio × 100（来自 mat_fee INCOMING_FIXED/INCOMING_OTHER）；recycle_pct = fee_ratio × 100（来自 MATERIAL_RECYCLE） |
| 不 ×100 列 | fixed_rise_value（金额）、gross_qty、net_qty（用量绝对值） |

**4 个 UNION ALL 来源：**

| 来源 | source_type | 物理表 | 主要字段 |
|---|---|---|---|
| 来料BOM | BOM | mat_bom[bom_type='INCOMING'] | seq_no / input_material_no / gross_qty / net_qty / loss_rate / defect_rate |
| 来料固定加工费 | INCOMING_FIXED | mat_fee[fee_type='INCOMING_FIXED', is_current=true] | fee_value / fee_ratio / settlement_rise_ratio / price_floating / fixed_rise_value |
| 来料其他费用 | INCOMING_OTHER | mat_fee[fee_type='INCOMING_OTHER', is_current=true] | fee_value / fee_ratio / dim_sub_seq_no / dim_element_name |
| 材料回收折扣 | MATERIAL_RECYCLE | mat_fee[fee_type='MATERIAL_RECYCLE', is_current=true] | fee_ratio → recycle_pct |

---

### 3.3 v_q_element_merged — 元素视图

| 属性 | 说明 |
|---|---|
| 来源物理表 | mat_bom、mat_fee |
| 拼接方式 | UNION ALL（2 来源） |
| source_type 取值 | `'BOM'` / `'ELEMENT_RECYCLE'` |
| 关键过滤条件 | mat_bom: bom_type='ELEMENT'；mat_fee: fee_type='ELEMENT_RECYCLE' AND is_current=true |
| 百分比 ×100 列（V133+V135）| loss_rate × 100；recycle_pct = fee_ratio × 100 |
| **不** ×100 列（V135 修复误伤）| composition_pct 整数百分比直存（75 = 75%），**禁止再乘 100** |

**2 个 UNION ALL 来源：**

| 来源 | source_type | 物理表 | 主要字段 |
|---|---|---|---|
| 元素BOM | BOM | mat_bom[bom_type='ELEMENT'] | element_name / composition_pct（直接读，不×100）/ loss_rate（×100）/ gross_qty / net_qty |
| 元素回收折扣 | ELEMENT_RECYCLE | mat_fee[fee_type='ELEMENT_RECYCLE', is_current=true] | fee_ratio → recycle_pct（×100） |

---

### 3.4 v_q_finished_merged — 成品视图

| 属性 | 说明 |
|---|---|
| 来源物理表 | mat_fee |
| 拼接方式 | UNION ALL（2 来源） |
| source_type 取值 | `'FINISHED_FIXED'` / `'FINISHED_OTHER'` |
| 关键过滤条件 | is_current=true |
| 百分比 ×100 列（V133修复） | fee_ratio × 100 |
| 不 ×100 列 | fee_value（金额值） |

**2 个 UNION ALL 来源：**

| 来源 | source_type | 物理表 | 主要字段 |
|---|---|---|---|
| 成品固定加工费 | FINISHED_FIXED | mat_fee[fee_type='FINISHED_FIXED', is_current=true] | seq_no / fee_value / fee_ratio / currency / price_unit |
| 成品其他费用 | FINISHED_OTHER | mat_fee[fee_type='FINISHED_OTHER', is_current=true] | seq_no / dim_element_name → element_name / fee_value / fee_ratio |

---

### 3.5 v_q_component_merged — 组成件视图

| 属性 | 说明 |
|---|---|
| 来源物理表 | mat_process |
| 拼接方式 | 单源（不 UNION ALL） |
| source_type 取值 | `'COMPONENT_BOM'`（固定字符串） |
| 关键过滤条件 | is_current = true |
| 百分比 ×100 列 | 无 |
| 不 ×100 列 | unit_price / freight / quantity（金额/数量绝对值） |

---

### 3.6 v_q_assembly_merged — 组装加工视图

| 属性 | 说明 |
|---|---|
| 来源物理表 | mat_fee |
| 拼接方式 | 单源（不 UNION ALL） |
| source_type 取值 | `'ASSEMBLY_PROCESS'`（固定字符串） |
| 关键过滤条件 | fee_type='ASSEMBLY_PROCESS' AND is_current=true |
| 百分比 ×100 列（V136修复）| reject_rate × 100（V133 漏修，V136 补修） |
| 不 ×100 列 | fee_value（组装加工费金额） |

---

### 3.7 v_q_plating_merged — 电镀视图

**迭代历史（V128 → V141 经历最多改动）：**
- V128：UNION ALL（plating_plan PLAN 行 + plating_fee FEE 行，旧表）
- V133：defect_rate × 100
- V137：数据源从旧表（plating_plan/plating_fee）切到 V125 新表（mat_plating_plan/mat_plating_fee）
- V141：架构从 UNION ALL 改为 LEFT JOIN（FEE 表 LEFT JOIN PLAN 表，彻底解决全局表 hf_part_no=NULL 被过滤问题）

| 属性 | 值 |
|---|---|
| 来源物理表 | mat_plating_fee、mat_plating_plan（V125 新表，非旧表 plating_fee/plating_plan） |
| 拼接方式 | LEFT JOIN（V141：mat_plating_fee LEFT JOIN mat_plating_plan ON plan_code + version） |
| source_type 取值 | `'FEE'`（固定字符串，V141 后仅一行类型） |
| 关键过滤条件 | mat_plating_fee.is_current = true；LEFT JOIN 按 (plan_code, version) 匹配 mat_plating_plan |
| 百分比 ×100 列 | defect_rate × 100（mat_plating_fee.defect_rate） |
| 不 ×100 列 | plating_area（cm2，绝对数值）、coating_thickness（um，绝对数值）、plating_process_fee、plating_material_fee |

**V141 LEFT JOIN 原因**：UNION ALL 时 PLAN 行 hf_part_no=NULL，ImplicitJoinRewriter 按当前报价单 hf_part_no 注入谓词后过滤掉 PLAN 行，前端只能看到 FEE 行（缺电镀元素名称/面积/厚度等方案字段）。改为 LEFT JOIN 后，FEE × N 个 PLAN 元素 = N 行，方案信息完整展示。

```sql
-- V141 最终结构
SELECT 'FEE' AS source_type,
       f.hf_part_no,
       f.plating_plan_code AS plan_code, f.plan_version,
       p.seq_no, p.plating_element, p.plating_area, p.coating_thickness, p.plating_requirement,
       f.plating_process_fee, f.plating_material_fee, f.currency, f.price_unit,
       CAST(f.defect_rate * 100 AS NUMERIC(10,4)) AS defect_rate
FROM mat_plating_fee f
LEFT JOIN mat_plating_plan p
       ON p.plan_code = f.plating_plan_code
      AND p.version   = f.plan_version
WHERE f.is_current = true
```

---

### 3.8 v_q_component_fee_merged — 组件费用视图

| 属性 | 说明 |
|---|---|
| 来源物理表 | mat_fee |
| 拼接方式 | 单源（不 UNION ALL） |
| source_type 取值 | `'COMPONENT_OTHER'`（固定字符串） |
| 关键过滤条件 | fee_type='COMPONENT_OTHER' AND is_current=true |
| 百分比 ×100 列 | 无（fee_value 是金额，不是比例） |
| 不 ×100 列 | fee_value（金额值） |

---

## 4. basic_data_config 中 Excel sheet 与物理表映射

> 仅列 template_kind = 'QUOTATION' 或 'BOTH' 的 ACTIVE 配置。sheet_index 是业务排序号，非 Excel tab 物理顺序。

| Excel sheet 名 | target_table | target_discriminator | sheet_index | attribute 数量 | 备注 |
|---|---|---|---|---|---|
| 客户料号与宏丰料号的关系 | mat_customer_part_mapping | — | 约 100 | 若干 | 早期 V46~V59 seed |
| 来料BOM | mat_bom | bom_type=INCOMING | 约 200 | 若干 | 早期注册 |
| 元素BOM | mat_bom | bom_type=ELEMENT | 约 201 | 若干 | 早期注册 |
| 来料固定加工费 | mat_fee | fee_type=INCOMING_FIXED | 约 202 | 若干 | 早期注册，kind=BOTH |
| 来料其他费用 | mat_fee | fee_type=INCOMING_OTHER | 约 203 | 若干 | 早期注册，kind=QUOTATION |
| 材料固定加工费 | mat_fee | fee_type=INCOMING_FIXED | 约 204 | 来料固定加工费副本 | V118 注册，QUOTATION 别名 |
| 材料其他费用 | mat_fee | fee_type=INCOMING_OTHER | 约 205 | 来料其他费用副本 | V118 注册，QUOTATION 别名 |
| 材料回收折扣 | mat_fee | fee_type=MATERIAL_RECYCLE | 约 100 | 5 列（A=hf_part_no/B=seq_no/C=投料号/D=投料号名称/E=fee_ratio） | V118 新建 |
| 元素回收折扣 | mat_fee | fee_type=ELEMENT_RECYCLE | 405 | 6 列（A/B/C/D/E/F） | V134 新建 |
| 成品固定加工费 | mat_fee | fee_type=FINISHED_FIXED | 约 300 | 若干 | 早期注册 |
| 成品其他费用 | mat_fee | fee_type=FINISHED_OTHER | 约 301 | 若干 | 早期注册 |
| 组成件BOM | mat_process | — | 约 302 | 9 列（A-I，V138 补全） | V138 补 9 条 attribute（原 0 条导致静默跳过）|
| 组装加工费 | mat_fee | fee_type=ASSEMBLY_PROCESS | 约 303 | 若干 | 早期注册 |
| 电镀方案 | mat_plating_plan | — | 约 304 | 若干 | V125 后改指向新表 |
| 电镀费用 | mat_plating_fee | — | 约 305 | 若干 | V125 后改指向新表，含 customer_id |
| 单重 | mat_part | — | 约 306 | 若干 | 早期注册 |
| 组成件其他费用 | mat_fee | fee_type=COMPONENT_OTHER | 406 | 10 列（A/B/D/E/F/G/L/M/N/O） | V139 新建，2.0 版 Excel 专属 |

**特别标注：**

- **V138 关键修复**：「组成件BOM」sheet 在 V138 之前 attribute 表 count=0，V58 的 import 逻辑 `if(attrs.isEmpty()) skip` 导致整个 sheet 静默跳过，mat_process 零写入，前端「组成件」tab 空白。V138 补充了 9 条 attribute 才解决。
- **V134 新增**：「元素回收折扣」sheet 在 V128 时已经建了组件和视图，但 V118 注释自承"不在范围"留下了缺 basic_data_config 注册的半成品，V134 补齐。
- **V139 新增**：「组成件其他费用」是 2.0 版 Excel 新增的 sheet，1.0 版无此 sheet，是否需要导入取决于 Excel 版本。
- **sheet_index 准确值**：本表中约 200~306 的 sheet_index 是早期 V46/V59/V122 期间的配置，精确值须查 `SELECT * FROM basic_data_config WHERE template_kind IN ('QUOTATION','BOTH') AND status='ACTIVE' ORDER BY sort_order`。

---

## 5. fee_type 取值表（mat_fee CHECK 约束）

| fee_type | 引入版本 | 业务含义 | 对应 Excel sheet |
|---|---|---|---|
| INCOMING_FIXED | V44（原始建表） | 来料固定加工费 | 来料固定加工费 / 材料固定加工费 |
| INCOMING_OTHER | V44 | 来料其他费用 | 来料其他费用 / 材料其他费用 |
| FINISHED_FIXED | V44 | 成品固定加工费 | 成品固定加工费 |
| FINISHED_OTHER | V44 | 成品其他费用 | 成品其他费用 |
| ASSEMBLY_PROCESS | V44 | 组装加工费 | 组装加工费 |
| INCOMING_ANNUAL_DOWN | V116/V117 | 来料年降（已从现用模板移除） | 来料年降 |
| ASSEMBLY_ANNUAL_DOWN | V116/V117 | 组装年降（已从现用模板移除） | 组装年降 |
| ANNUAL_REDUCTION_FACTOR | V116/V117 | 年降系数（已从现用模板移除） | — |
| MATERIAL_RECYCLE | V118 | 材料回收折扣 | 材料回收折扣 |
| ELEMENT_RECYCLE | V128 | 元素回收折扣 | 元素回收折扣 |
| COMPONENT_OTHER | V139 | 组成件其他费用（2.0版 Excel 新增）| 组成件其他费用 |

> INCOMING_ANNUAL_DOWN / ASSEMBLY_ANNUAL_DOWN / ANNUAL_REDUCTION_FACTOR 三类在 V119 被从报价模板组件中移除，但 CHECK 约束保留以兼容历史数据。

---

## 6. 物理表结构速查

### 6.1 mat_part — 生产料号主档

| 字段 | 类型 | 说明 |
|---|---|---|
| part_no | VARCHAR(64) PRIMARY KEY | 宏丰内部料号（业务主键） |
| part_name | VARCHAR(128) | 料号名称 |
| unit_weight | DECIMAL(18,4) | 单重 |
| weight_unit | VARCHAR(16) | 重量单位 |
| status_code | VARCHAR(4) NOT NULL DEFAULT 'Y' | Y / N |

- 业务键：part_no（PRIMARY KEY）
- 是否 versioned：否（全局表，无 is_current）

---

### 6.2 mat_customer_part_mapping — 客户料号映射

| 字段 | 类型 | 说明 |
|---|---|---|
| id | UUID PRIMARY KEY | |
| customer_id | UUID NOT NULL | 客户 ID |
| customer_part_name | VARCHAR(128) | 客户料号名称 |
| customer_product_no | VARCHAR(64) | 客户产品编号（业务唯一键） |
| customer_drawing_no | VARCHAR(64) | 客户图号 |
| hf_part_no | VARCHAR(64) NOT NULL | 宏丰料号（FK mat_part） |
| payment_method | VARCHAR(64) | 付款方式 |
| base_currency | VARCHAR(8) | 基础货币 |
| quote_currency | VARCHAR(8) | 报价货币 |

- 业务键：UNIQUE (customer_id, customer_product_no)（BV-06 唯一约束仍生效）
- 是否 versioned：否

---

### 6.3 mat_bom — 统一 BOM 表

| 字段 | 类型 | 说明 |
|---|---|---|
| id | UUID PRIMARY KEY | |
| bom_type | VARCHAR(16) NOT NULL | 'INCOMING' / 'ELEMENT' |
| hf_part_no | VARCHAR(64) NOT NULL | FK mat_part |
| seq_no | INT NOT NULL | 项次 |
| input_material_no | VARCHAR(64) | 投入料号 |
| input_material_name | VARCHAR(128) | 投入料号名称 |
| loss_rate | DECIMAL(10,4) | 损耗率（÷100 存，视图 ×100 显示） |
| gross_qty / net_qty | DECIMAL(18,4) | 毛重/净重用量 |
| gross_unit / net_unit | VARCHAR(16) | 单位 |
| output_material_type | VARCHAR(64) | INCOMING 专用：产出类型 |
| defect_rate | DECIMAL(10,4) | 不良率（÷100 存，视图 ×100 显示） |
| element_name | VARCHAR(64) | ELEMENT 专用：元素名称 |
| composition_pct | DECIMAL(10,4) | ELEMENT 专用：组成含量（整数百分比直存，**不** ÷100） |

- 业务键：UNIQUE (bom_type, hf_part_no, seq_no, COALESCE(input_material_no,''), COALESCE(element_name,''))
- 是否 versioned：否（全局表，无 customer_id，无 is_current）

---

### 6.4 mat_fee — 统一费用表

| 字段 | 类型 | 说明 |
|---|---|---|
| id | UUID PRIMARY KEY | |
| customer_id | UUID NOT NULL | 客户 ID |
| hf_part_no | VARCHAR(64) NOT NULL | FK mat_part |
| version | INT NOT NULL DEFAULT 1 | 版本号 |
| is_current | BOOLEAN NOT NULL DEFAULT true | 当前有效标记 |
| fee_type | VARCHAR(32) NOT NULL | 见第 5 章 CHECK 约束 |
| seq_no | INT NOT NULL | 项次 |
| fee_value | DECIMAL(18,4) | 金额值（不 ÷100） |
| fee_ratio | DECIMAL(10,4) | 比例（÷100 存：0.03 = 3%，视图 ×100 还原） |
| currency / price_unit | VARCHAR | 货币/计价单位 |
| dim_input_material_no | VARCHAR(64) | 维度字段：投入料号 |
| dim_input_material_name | VARCHAR(128) | 维度字段：投入料号名称 |
| dim_element_name | VARCHAR(128) | 维度字段：元素名称 |
| dim_assembly_process | VARCHAR(64) | 维度字段：组装工序 |
| dim_sub_seq_no | INT | 维度字段：子项次 |
| price_floating | BOOLEAN | INCOMING_FIXED 专用：是否随材料价格波动 |
| settlement_rise_ratio | DECIMAL(10,4) | INCOMING_FIXED 专用（÷100 存，视图 ×100 显示） |
| fixed_rise_value | DECIMAL(18,4) | INCOMING_FIXED 专用（金额，不 ÷100） |
| reject_rate | DECIMAL(10,4) | ASSEMBLY_PROCESS 专用（÷100 存，视图 ×100 显示） |

- 业务键：无显式 UNIQUE 约束（依赖 customer_id + fee_type + hf_part_no + version + seq_no + dim_* 组合）
- 是否 versioned：是（version + is_current）
- BV-18 已移除（component_part_no 不再做外键校验）；BV-06 仍生效

---

### 6.5 mat_process — 工艺基础（组成件 BOM）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | UUID PRIMARY KEY | |
| customer_id | UUID NOT NULL | 客户 ID（BIZ-2 跨客户差异化）|
| hf_part_no | VARCHAR(64) NOT NULL | FK mat_part |
| version | INT NOT NULL DEFAULT 1 | 版本号 |
| is_current | BOOLEAN NOT NULL DEFAULT true | 当前有效标记 |
| seq_no / sub_seq_no | INT | 项次/子项次 |
| process_code | VARCHAR(32) | 工序编号 |
| assembly_process | VARCHAR(64) | 组装工序名 |
| component_part_no / component_name | VARCHAR | 组成件料号/名称 |
| supplier_code / supplier_name | VARCHAR | 供应商 |
| quantity / quantity_unit | DECIMAL / VARCHAR | 组成数量/单位 |
| unit_price / freight | DECIMAL(18,4) | 单价/运费 |
| currency / price_unit | VARCHAR | 货币/计价单位 |

- 业务键：UNIQUE (customer_id, hf_part_no, version, seq_no, sub_seq_no)；当前版本唯一 UNIQUE (customer_id, hf_part_no, seq_no, sub_seq_no) WHERE is_current=true
- 是否 versioned：是

---

### 6.6 mat_plating_plan — 报价侧电镀方案库

| 字段 | 类型 | 说明 |
|---|---|---|
| id | UUID PRIMARY KEY | |
| plan_code | VARCHAR(32) NOT NULL | 方案编号 |
| version | VARCHAR(16) NOT NULL | 方案版本 |
| seq_no | INT NOT NULL | 项次 |
| plating_element | VARCHAR(64) | 电镀元素名称 |
| plating_area | DECIMAL(18,4) | 电镀面积（cm2，绝对值，不 ÷100） |
| coating_thickness | DECIMAL(10,4) | 镀层厚度（um，绝对值，不 ÷100） |
| plating_requirement | VARCHAR(256) | 电镀要求 |

- 业务键：UNIQUE (plan_code, version, seq_no)
- 是否 versioned：否（全局表，无 customer_id，无 is_current）
- 注：V125 新建（与核价侧 costing_part_plating 独立），V137 起 v_q_plating_merged 指向此表

---

### 6.7 mat_plating_fee — 报价侧电镀费用

| 字段 | 类型 | 说明 |
|---|---|---|
| id | UUID PRIMARY KEY | |
| customer_id | UUID NOT NULL | 客户 ID |
| hf_part_no | VARCHAR(64) NOT NULL | FK mat_part |
| version | INT NOT NULL DEFAULT 1 | 版本号 |
| is_current | BOOLEAN NOT NULL DEFAULT true | 当前有效标记 |
| plating_plan_code | VARCHAR(32) | 关联方案编号（JOIN 用） |
| plan_version | VARCHAR(16) | 关联方案版本（JOIN 用） |
| plating_process_fee / plating_material_fee | DECIMAL(18,4) | 加工费/材料费 |
| currency / price_unit | VARCHAR | 货币/计价单位 |
| defect_rate | DECIMAL(10,4) | 不良率（÷100 存，视图 ×100 显示） |

- 业务键：无显式 UNIQUE（依赖 customer_id + hf_part_no + plating_plan_code + plan_version）
- 是否 versioned：是（version + is_current）
- 注：V125 新建，V141 起视图通过 (plan_code, version) LEFT JOIN mat_plating_plan 展示方案详情

---

## 7. 关键约束与陷阱

**陷阱 1：ImplicitJoinRewriter 全局表问题**

ImplicitJoinRewriter 在执行 BNF path 查询时，会按当前报价单的 `hf_part_no` / `customer_id` 自动注入 WHERE 谓词。对于全局表（如 mat_plating_plan、exchange_rate），如果视图中这些表直接暴露且 hf_part_no=NULL，全部行会被过滤掉。

解决方式：
- 电镀视图（V141）：改为 LEFT JOIN，主驱动是含 hf_part_no 的 mat_plating_fee，方案信息附在其上。
- 汇率：在 v_q_part_info_merged 中通过 LEFT JOIN exchange_rate 在视图层完成，视图输出 `exchange_rate` 列而非暴露 exchange_rate 表。

**陷阱 2：视图列名变更必须 DROP CASCADE + 重启 Quarkus**

任何 `DROP VIEW ... CASCADE` 或视图列结构（类型/增删）变更后，**必须 touch 一个 java 文件强制 Quarkus dev reload**，清空 `ImplicitJoinRewriter.tableColumnsCache` / `CachedSqlCompiler` 进程级缓存。否则旧缓存（列空集）残留，BNF path 查询不再注入 hf_part_no 谓词，视图返回全表 N 行，前端出现「首值（共N项）」错乱。

**陷阱 3：basic_data_attribute 为空导致 import 静默跳过整个 sheet**

V58 import 逻辑：`if (attrs.isEmpty()) skip`。如果某个 sheet 的 basic_data_config 已注册但 basic_data_attribute 表 count=0，整个 sheet 会静默跳过，不报错，不入库。

组成件BOM 就是典型案例（V138 之前 attribute=0 → sheet 静默跳过 → 组成件 tab 空）。配完 basic_data_config 后**必须同步插入 basic_data_attribute**。

**陷阱 4：V139 步骤 6 的 IF EXISTS DO 块在某些场景跳过 INSERT**

V139 DO 块加了幂等保护（IF EXISTS → RETURN），但实际执行时 Flyway 事务边界导致某些条件下 INSERT 被跳过。V140 专门重做了强制绑定 + snapshot 重建。**新增模板组件绑定后必须验证 template_component 表确实有记录**，不能仅依赖 Flyway 日志。

**陷阱 5：百分比存储双重语义**

| 字段类型 | 存储方式 | 视图处理 | 示例 |
|---|---|---|---|
| fee_ratio / loss_rate / defect_rate / settlement_rise_ratio / reject_rate / recycle_pct | ÷100 后存（toDecimalPercent） | 视图 ×100 还原 | 3% → DB 0.03 → 视图 3 |
| mat_bom.composition_pct | 整数百分比直存（toDecimal） | 视图不 ×100 | 75% → DB 75 → 视图 75 |
| 金额类：fee_value / unit_price / freight / plating_*_fee | 原值存 | 视图直接读 | — |
| 尺寸类：plating_area / coating_thickness | 原值存 | 视图直接读 | — |

V133 批量修复时曾将 composition_pct 也做了 ×100（误伤），V135 立即修正。**新增视图列时按上表严格区分**。

**陷阱 6：PUBLISHED 模板 snapshot 冻结**

模板发布（publish）时，所有组件信息被序列化为 `components_snapshot` JSONB 字段，此后 template_component 表即使新增绑定，前端仍读 snapshot 旧数据。修改模板组件结构后必须：
1. 通过 API 重新 publish（createNewDraft → addComponent → publish），或
2. 手动重建 snapshot（V140 模式：`UPDATE template SET components_snapshot = jsonb_agg(...)`）

**陷阱 7：import 上传限制**

- multipart 文件上传上限：100M
- form-attribute-size：10M（V5 增强导入向导的 resolutions JSON 较大时注意）

---

## 8. 配置流程速查（如何创建新 QUOTATION 模板）

### 步骤 1：创建组件目录

```sql
INSERT INTO component_directory (id, name, parent_id, sort_order, created_at)
SELECT gen_random_uuid(), '新目录名称', NULL, 90, now()
WHERE NOT EXISTS (SELECT 1 FROM component_directory WHERE name = '新目录名称');
```

### 步骤 2：为每类 sheet 创建或复用组件

```sql
INSERT INTO component (
    id, directory_id, name, code, component_type, status,
    data_driver_path, fields, formulas, column_count, created_at, updated_at
) VALUES (
    gen_random_uuid(), <目录ID>, '组件名', 'COMP-NEW-XXX', 'NORMAL', 'ACTIVE',
    'v_your_view_name',
    '[{"name":"字段名","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_your_view_name.column_name"}]'::jsonb,
    '[]'::jsonb, 1, now(), now()
) ON CONFLICT (code) DO UPDATE SET
    fields = EXCLUDED.fields,
    data_driver_path = EXCLUDED.data_driver_path,
    column_count = EXCLUDED.column_count,
    updated_at = now();
```

注意：多行组件（BOM / fee 等）必须配 `data_driver_path`，否则多行数据被压缩成「首值（共N项）」。

### 步骤 3：创建或扩展物理视图

```sql
DROP VIEW IF EXISTS v_your_view_name CASCADE;  -- 修改现有视图须 CASCADE
CREATE VIEW v_your_view_name AS
SELECT 'TYPE' AS source_type, hf_part_no, ...
FROM mat_fee WHERE fee_type = 'YOUR_TYPE' AND is_current = true;
-- DROP CASCADE 后必须 touch java 文件重启 Quarkus
```

### 步骤 4：注册 basic_data_config + basic_data_attribute

```sql
-- 先注册 config
INSERT INTO basic_data_config (
    id, sheet_name, sheet_index, target_table, target_discriminator,
    template_kind, header_row_index, data_start_row_index,
    sort_order, status, description, created_at, updated_at
) VALUES (...);

-- 必须同步插入 attribute（每列一条，不能为空！）
INSERT INTO basic_data_attribute (
    id, config_id, column_letter, column_title,
    variable_code, variable_label, data_type, status, sort_order,
    importance_level, affects_calculation, is_required, created_at, updated_at
) VALUES ...;
```

### 步骤 5：创建模板（DRAFT 状态）

```sql
INSERT INTO template (
    id, template_series_id, name, version, status, template_kind,
    customer_id, description, created_at, updated_at
) VALUES (
    gen_random_uuid(), gen_random_uuid(), '新模板名', '1.0', 'DRAFT', 'QUOTATION',
    NULL, '模板描述', now(), now()
);
```

### 步骤 6：绑定组件

```sql
INSERT INTO template_component (id, template_id, component_id, tab_name, sort_order, created_at)
VALUES (gen_random_uuid(), <模板ID>, <组件ID>, 'Tab名', 0, now());
-- 每个组件一行，sort_order 从 0 递增
```

绑定后务必验证：`SELECT COUNT(*) FROM template_component WHERE template_id = <模板ID>`

### 步骤 7：发布模板（推荐 API 方式）

```
POST /api/cpq/templates/{id}/publish
```

或通过 SQL 手动重建 snapshot（V140 方式，适用于 PUBLISHED 状态下追加组件的场景）：

```sql
UPDATE template t
SET components_snapshot = (
    SELECT jsonb_agg(jsonb_build_object(
        'id',                  tc.id::text,
        'componentId',         c.id::text,
        'componentName',       c.name,
        'componentCode',       c.code,
        'componentType',       c.component_type,
        'tabName',             tc.tab_name,
        'sortOrder',           tc.sort_order,
        'fields',              c.fields,
        'formulas',            c.formulas,
        'preset_rows',         COALESCE(tc.preset_rows, '[]'::jsonb),
        'data_driver_path',    c.data_driver_path,
        'formula_assignments', COALESCE(tc.formula_assignments, '{}'::jsonb)
    ) ORDER BY tc.sort_order ASC)
    FROM template_component tc JOIN component c ON c.id = tc.component_id
    WHERE tc.template_id = t.id
)
WHERE t.id = <模板ID>;
```

---

## 9. 验证清单（配完后必跑）

### 9.1 视图行数自检

```sql
SELECT COUNT(*) FROM v_q_part_info_merged;     -- 按 customer+partNo 组合行数
SELECT COUNT(*) FROM v_q_incoming_merged;      -- 4 来源合并行数
SELECT COUNT(*) FROM v_q_element_merged;       -- 2 来源合并行数
SELECT COUNT(*) FROM v_q_finished_merged;      -- 2 来源合并行数
SELECT COUNT(*) FROM v_q_component_merged;     -- 单源 mat_process 行数
SELECT COUNT(*) FROM v_q_assembly_merged;      -- 单源 mat_fee[ASSEMBLY_PROCESS] 行数
SELECT COUNT(*) FROM v_q_plating_merged;       -- LEFT JOIN 结果行数
SELECT COUNT(*) FROM v_q_component_fee_merged; -- 单源 mat_fee[COMPONENT_OTHER] 行数
-- 期望：无报错（允许 0 行，无数据时正常）
```

### 9.2 expand-driver 验证（按实际料号）

```
POST /api/cpq/components/{componentId}/expand-driver
Body: {"customerId": "<实际客户ID>", "partNo": "<实际料号如3120012574>"}
期望：status=200，rowCount > 0
```

逐个验证 8 个组件 ID，确保每个 tab 有驱动行数据。

### 9.3 import 验证

1. `POST /api/cpq/basic-data/import/preview`（multipart 上传 Excel）：期望各 sheet status=SUCCESS，orphanRows 列出历史孤儿行供用户决策。
2. `POST /api/cpq/basic-data/import/confirm`（确认导入）：期望 matXxxCreated / matXxxUpdated 计数与 Excel 行数一致。
3. 重跑 expand-driver 验证新数据已可查到。

### 9.4 前端 Step2 验证

1. 新建/编辑报价单，Step2 选产品后进入产品卡片编辑页
2. 切换各 tab，确认数据完整（不出现「— (共N项)」乱码）
3. 百分比列（损耗率/不良率/比例等）显示整数百分比值（如 3、5），而非小数（0.03、0.05）
4. 电镀 tab 同时显示方案字段（plating_element / plating_area / coating_thickness）和费用字段（plating_process_fee / plating_material_fee）
5. 若已配置「组件费用」tab，确认组件级费用项（包装费/运费等）正常展示

---

## 附录：迁移版本演进索引

| 迁移版本 | 主要内容 |
|---|---|
| V44 | 建立 mat_fee / mat_bom / mat_process / plating_plan / plating_fee 等物理表；mat_fee 原始 fee_type 5 种 |
| V116 / V117 | 为施耐德创建报价模板（COMP-Q-* 系组件）；新增 fee_type：INCOMING_ANNUAL_DOWN/ASSEMBLY_ANNUAL_DOWN/ANNUAL_REDUCTION_FACTOR |
| V118 | 报价侧 3 个 sheet 别名（材料固定/其他/回收折扣）注册；新增 fee_type：MATERIAL_RECYCLE |
| V119 | 从模板移除年降组件；更名对齐报价 Excel |
| V120 | 补「成品固定加工费」组件；重整 tab sort_order |
| V122 | 报价组件 driver 从核价侧表切换到报价侧表（mat_bom/mat_process/mat_part） |
| V125 | 电镀双侧分流：新建 mat_plating_plan / mat_plating_fee（报价侧）；旧表 plating_plan/plating_fee 保留为核价侧 |
| V127 | 复制报价模板组件到「报价模板组件V2」目录（过渡用，code 加 -V2 后缀） |
| V128 | **核心**：7 个 UNION ALL 视图 + 7 个 COMP-QX-* 组件（V3 目录） + 通用 QUOTATION 模板；新增 fee_type：ELEMENT_RECYCLE |
| V133 | 修复视图百分比列 ×100（loss_rate/defect_rate/fee_ratio/settlement_rise_ratio/recycle_pct） |
| V134 | 注册 sheet「元素回收折扣」到 basic_data_config（fee_type=ELEMENT_RECYCLE，6 列 attribute） |
| V135 | 修复 V133 误伤：v_q_element_merged.composition_pct 恢复为不 ×100（整数百分比直存） |
| V136 | 补修 v_q_assembly_merged.reject_rate ×100（V133 漏修） |
| V137 | v_q_plating_merged 数据源从旧表切到 mat_plating_plan/mat_plating_fee |
| V138 | 给 sheet「组成件BOM」补 9 条 basic_data_attribute（解决 import 静默跳过问题） |
| V139 | 2.0 版 Excel 组件费用支持：fee_type=COMPONENT_OTHER + v_q_component_fee_merged + COMP-QX-COMPONENT-FEE + sheet 注册 |
| V140 | 强制绑定 COMP-QX-COMPONENT-FEE 到模板 + 手动重建 components_snapshot（期望 9 个组件） |
| V141 | v_q_plating_merged 架构从 UNION ALL 改为 LEFT JOIN，解决方案侧字段（电镀元素/面积/厚度）缺失问题 |
