# 提示词 · 在 SQL 视图中配置客户元素价格单价

> **用途**：直接粘贴给「报表模板 / 组件配置 agent」的即用型任务提示词。
> **来源**：由 `单价字段配置规则.md`（已过实施阶段两次纠错）凝练；完整规则 + 可抄 SQL 全示例 + 10 项自检清单见该文件。
> **状态**：2026-07-23 定稿，已吸收实战两个坑（计价单位不绑 / 核价传 `_GLOBAL_`）。

---

## 提示词正文（整段复制）

```
【任务】给某个元素类组件的「单价」列接入客户价格策略，让报价单/核价单渲染时按客户
自动取到元素单价，而不是让用户手填。改动只动组件的 SQL 视图(component_sql_view.
sql_template) + 字段取数配置，不改字段类型。

【核心机制】价格逻辑已封装在 PG 表函数：
  f_customer_element_price(客户编号 TEXT, 基准日 DATE)
  返回列：element_code(元素符号) / unit_price(最终单价,已含系数加价) /
          currency(货币) / price_unit(价格计价单位)
在驱动视图里 LEFT JOIN 它，用元素符号关联即可。表函数一次返回该客户全部有价元素，
不是逐行调用，无 N+1。

【三步配置】
1) 驱动视图 FROM 子句加 JOIN（报价侧 vs 核价侧客户参数不同）：
   -- 报价侧(system_type='QUOTE')：传运行时真实客户
   LEFT JOIN f_customer_element_price(:customerCode, :priceBaseDate) cep
          ON cep.element_code = ebi.component_no
   -- 核价侧(system_type='PRICING')：传字面量 '_GLOBAL_'
   LEFT JOIN f_customer_element_price('_GLOBAL_', :priceBaseDate) cep
          ON cep.element_code = ebi.component_no

2) SELECT 列表加输出列，中文别名必须与组件字段名【逐字一致】：
   cep.unit_price  AS 单价,
   cep.currency    AS 货币,
   -- ⛔ 不要输出「计价单位」（见硬约束4）

3) component_sql_view.required_variables 声明：
   报价侧：["customerCode","priceBaseDate"]
   核价侧：["priceBaseDate"]   ← 不含 customerCode(传的是字面量)
   字段取数：报价侧字段用 default_source 指向 $view.单价 / $view.货币(类型仍 INPUT_*)，
            核价侧用 basic_data_path 指向 $view.单价(类型 BASIC_DATA)。字段类型一个字都不要改。

【硬约束(违反即出 bug,逐条自检)】
1. 必须 LEFT JOIN，禁止 INNER JOIN —— 否则无价元素整行消失、行数变少、页签小计错。
2. JOIN 键用【元素符号列】(element_bom_item.component_no，值如 Ag/Cu/301)，
   不是元素编号 —— 用错恒不命中，全表无价。
3. 禁止 COALESCE(cep.unit_price, 0) 之类兜底 —— 无价必须留 NULL 触发"留空手填"；
   填 0 会静默算出 0 元成本、不报错。
4. ⛔ 不要绑「计价单位」字段。报价单的「计价单位」列是【BOM 发料单位】
   (ebi.issue_unit，值如 g/KG)，被「毛用量/净用量」以 unit_source_field 引用做真实
   g→KG 换算；改指价格的 price_unit(元/kg) 会让换算读到非法 token、静默算错。
   本期只绑「单价」+「货币」两个字段。
5. 核价侧传字面量 '_GLOBAL_'，且视图 WHERE 里【不要】加 customer_no=:customerCode。
   核价基础数据的 customer_no 恒为 '_GLOBAL_'，加客户过滤会一行都查不到。
6. 别名与组件字段名逐字一致(含全半角/空格) —— 否则路径解析不到、渲染空白。
7. 不要为"源名/命中日期/取值方式"等加额外输出列 —— 无消费方，徒增视图复杂度。

【前提与边界】
- 客户没配价格策略 → 表函数返 0 行 → 元素行照常显示、单价列留空手填(设计如此,不报错)。
- 基准日 :priceBaseDate 由后端自动注入(=报价单创建日期)，视图里直接写占位符即可，不用自己算。
- 因字段类型不变，本改动不触发字段类型联动协议(AP-44)。

【自检】用 expand-driver 端点或直接 SQL 实测：有价元素出数、无价元素返 NULL(不是 0、
不是整行消失)；换一个没配策略的客户，元素行数不变、单价全空。
```

---

## 使用说明（给人看的，不用粘给 agent）

1. **两个坑已内置**：约束 4（计价单位不绑）、约束 5（核价传 `_GLOBAL_` 不加客户过滤）是开发阶段真出过问题、改文档后才定的。只看初版规则的 agent 会配错，务必用这份带这两条的版本。

2. **换组件时只改驱动表别名**：提示词里用 `element_bom_item ebi` / `ebi.component_no` / `ebi.issue_unit` 是现役 V6 元素 BOM 结构。给别的元素组件配时，把 `ebi` 换成该组件驱动视图自己的元素 BOM 表别名，JOIN 逻辑与约束不变。

3. **表函数签名不可改**：`f_customer_element_price(客户编号 TEXT, 基准日 DATE)` 已发给所有配置方，agent 只调用不改签名。

4. **验证客户能否查到价**（人工快速核对，navicat 可跑）：
   ```sql
   SELECT * FROM f_customer_element_price('CUST-1269', CURRENT_DATE);
   -- 返 0 行 = 该客户没配策略或窗口内无价；有行 = 配置生效
   ```

5. **完整版规则**：`dev-docs/task-0722-元素价格策略/单价字段配置规则.md`（含报价/核价两段完整可抄 SQL + 10 项自检清单 + 表函数返回列定义）。本文件是它的即用凝练版。
