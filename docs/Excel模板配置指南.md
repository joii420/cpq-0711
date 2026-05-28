# Excel 模板配置指南

> 用途：报价单 / 核价单的「Excel 视图」按预定义列结构展示数据。本文以「核价-汇总演示模板」(23 列对应导入 Excel 的「汇总」页签) 为例，说明列、变量路径、公式的完整配置流程。
>
> 适用读者：业务管理员、PRICING_MANAGER、SYSTEM_ADMIN。
>
> 前置阅读：`docs/PRD.md`「Excel 模板配置」「核价系统」相关章节。

---

## 一、整体架构

```
报价单 (quotation)
  ├─ customer_template_id     ─► template (报价模板)  ─┐
  └─ costing_card_template_id ─► template (核价模板)  ─┤  linked_template_id
                                                       ▼
                                       costing_template (Excel 模板)
                                          └─ columns: JSON [ ...23 列 ]
```

### 渲染入口

| 视图 | 触发条件 | 反查源 |
|---|---|---|
| 报价单 Excel 视图 | mainTab=`quote` + viewType=`excel` | `quotation.customer_template_id` |
| **核价单 Excel 视图** | mainTab=`costing` + viewType=`excel` | `quotation.costing_card_template_id` |

反查规则（同时满足）：
1. `costing_template.linked_template_id = <反查源>`
2. `costing_template.status = 'PUBLISHED'`
3. 优先 `is_default = true`；否则取最新一份

→ 然后按 `columns` JSON 渲染。**每个 `lineItem`（产品行）渲染一行**。

---

## 二、入口

### 菜单路径

`Excel 模板配置` → 列表页 → 点击模板名 → 列配置详情页（路由 `/costing-templates/:id`）

### 编辑约束

| 状态 | 是否可编辑 |
|---|---|
| `DRAFT` | ✅ 全部字段 |
| `PUBLISHED` | ❌ 只读（要改 → 列表页「派生新草稿」） |
| `ARCHIVED` | ❌ 只读 |

### 同时只能一份「默认」

同一 `linked_template_id` 内 `is_default=true` + `status='PUBLISHED'` 唯一（DB 约束）。要换默认 → 先把旧的 `is_default` 置 false 或归档。

---

## 三、列的 5 个字段

| 字段 | 含义 | 示例 |
|---|---|---|
| **列Key** | 列在公式中的引用名（建议 A/B/C…）| `L` |
| **列标题** | 表头显示文字 | `材料成本` |
| **数据来源** | `VARIABLE`（取数）/ `FORMULA`（计算）| VARIABLE |
| **变量路径 / 公式** | 取数路径 或 公式表达式 | `v_costing_summary_full.material_cost` |
| **业务标签** | 比对视图分组用，可选 | （留空） |

> 列Key 是公式 `[X]` 引用的字符。若改名，所有引用它的 FORMULA 列都要同步改。

---

## 四、两种数据来源

### A. VARIABLE — 取数列

点「选择」按钮打开 **PathPickerDrawer**（与组件管理共用一个）。三种填法：

#### A-1.「从基础数据选」（推荐）

走 BNF 路径：

| 步骤 | 操作 |
|---|---|
| 1 | 顶部 **Segmented**：报价模板 / **核价模板** / 全部（V79）— 按当前模板用途过滤 |
| 2 | **Sheet 下拉**：选物理表/视图（如「核价汇总」对应 `v_costing_summary_full`） |
| 3 | **字段下拉**：选具体列（如「材料成本」对应 `material_cost`） |
| 4 | **谓词区**（可选）：加 `[field='value' AND ...]` 过滤 |
| 5 | 确认 → 自动写回 `variable_path = v_costing_summary_full.material_cost` |

##### 隐式 JOIN（自动按料号/客户过滤）

后端 `ImplicitJoinRewriter` 渲染时自动注入：
- `hf_part_no = '<lineItem.productPartNo>'` —— 当目标表/视图含 `hf_part_no` 列
- `customer_id = '<报价单.customerId>'` —— 当目标表/视图含 `customer_id` 列
- `part_no = '<lineItem.productPartNo>'` —— 当目标表/视图含 `part_no` 列（如 `mat_part`）

> 因此**不用手填**这些谓词；视图里没有这些列时不会注入（举例：`v_costing_*_price` 全局价格视图不带 `hf_part_no` → 所有料号共享同一价格，正确）。

##### 系统列黑名单（不会被自动注入）

`id / created_at / updated_at / created_by / updated_by / deleted_at / is_deleted / version / status / is_current / import_record_id / imported_by` —— 这些是审计列、状态列，不算业务键。

#### A-2.「输入物理表路径(BNF)」— 自由编写

格式：`<sheet_name>[谓词].<field>`

```
v_costing_summary_full.material_cost                    # 隐式按料号过滤
costing_summary[summary_no='CS-DEMO-0001'].quote_currency   # 显式谓词
mat_part[hf_part_no='3100080003'].unit_weight           # 显式谓词
mat_bom[hf_part_no AND bom_type='INCOMING'].input_qty   # 多条谓词
```

#### A-3.「lineItem 字段简写」— 老格式 `{code}`

不走后端求值，直接从前端 `lineItem` 对象取字段（前端 `LinkedExcelView.resolveVariable` 实现）：

| 简写 | 取自 |
|---|---|
| `{hf_part_no}` | `lineItem.productPartNo` |
| `{product_name}` | `lineItem.productName` |
| `{customer_drawing_no}` | `lineItem.customerDrawingNo` |
| `{customer_part_name}` | `lineItem.customerPartName` |
| `{customer_part_no}` | `lineItem.customerPartNo` |
| `{customer_product_no}` | `lineItem.customerProductNo` |
| `{product_part_no}` | `lineItem.productPartNo` |
| `{specification}` | `lineItem.hfPartInfo.specification` |
| `{size_info}` | `lineItem.hfPartInfo.sizeInfo` |
| `{status_code}` | `lineItem.hfPartInfo.statusCode` |
| `{subtotal}` | `lineItem.subtotal` |
| `{base_currency}` | 硬编码 `'USD'` |
| `{system_date}` | 当前日期 ISO `YYYY-MM-DD` |
| 其他 `{xxx}` | `lineItem.productAttributeValues['xxx']` 兜底 |

> **何时用 `{code}` 而不是 BNF**：当字段已经存在于 lineItem 内存对象、不需要查 DB 时（性能更好，无网络请求）。

---

### C. SQL 视图引用（推荐新配方式）

**背景**：V249/V250 起，Excel 模板层拥有独立的 `template_sql_view` 表，可在模板内维护专属 SQL 视图，再用 `$<name>.<col>` 路径引用。此方式相比直引物理 PG 视图有以下优势：

| 对比维度 | `$<name>.<col>` SQL 视图引用 | `v_xxx.col` 物理视图直引 |
|---|---|---|
| **快照冻结** | 发布时冻结到 `template_sql_views_snapshot`，历史报价可复现 | 物理视图改动会影响旧报价 |
| **隔离性** | 本模板私有，不影响其他模板/组件 | 全局共享，修改有副作用 |
| **V44 安全** | 干运行（dry-run）校验时检测并拒绝老表引用（AP-53） | 无校验，可能悄悄查废弃表 |
| **可维护性** | 在模板配置页直接维护，UI 可 dry-run 预览结果 | 需 Flyway 迁移 + DBA 操作 |

#### 操作入口

1. 进入「模板配置」→ 找到目标 Excel 模板 → 派生草稿（如已发布）。
2. 编辑页顶部 Tabs 切换到「**SQL 视图**」Tab（`TemplateSqlViewsTab`）。
3. 点「新建视图」→ 填写视图名称（如 `summary_full`）、描述、SQL 语句 → 「dry-run 预览」校验 → 保存。
4. 切换到「**列配置**」Tab → 编辑某 VARIABLE 列 → 点「**🗄 SQL 视图**」按钮 → 弹出 `PathPickerDrawer`（ownerContext=TEMPLATE）→ 在"SQL 视图" Tab 选视图名和字段列 → 确认 → `variable_path` 自动写回为 `$summary_full.<col>` 格式。

#### 引用路径格式

```
$<sql_view_name>.<column_name>

示例：
$summary_full.material_cost        # 引用本模板 summary_full 视图的 material_cost 列
$costing_index.processing_cost     # 引用本模板 costing_index 视图的 processing_cost 列
```

#### 与基础数据直引（A/B 类）的区别

- **`$name.col`**（本文 C 类）：从 `template_sql_view` 执行，逻辑由模板管理员自己维护，发布时冻结，隔离性最强。
- **`v_xxx.col`**（A-1 类 BNF）：查物理 PG 视图，需 DBA 管理，历史报价可能受视图修改影响。
- **`{code}` 简写**（A-3 类）：不查 DB，直接取 lineItem 内存字段，性能最好但限制于 lineItem 已有字段。

#### 注意事项

- `template_sql_view.sql_template` 内部禁止查 V44 废弃表（`mat_part` / `mat_bom` / `mat_process` 等），详见 AP-53。
- 禁止使用 `$$comp.view.col` 跨组件引用语法（模板上下文强阻断）。
- PUBLISHED 模板的 SQL 视图只读；需修改时先「派生草稿」。

---

### B. FORMULA — 公式列

点「编辑」按钮打开公式抽屉，含两个区：

- **公式输入框**（TextArea）
- **快速插入列引用**（Tag 列表）— 点击自动追加 `[A] [B] [L]` 到光标处

---

## 五、公式语法

前端 `LinkedExcelView.evaluateFormula` 实现，规则：

| 语法 | 含义 |
|---|---|
| `[X]` | 引用本行 X 列已求值后的数值 |
| `{code}` | 引用变量（VARIABLE 列已 resolved 的值） |
| `+ - * / ( )` | 四则运算 + 括号 |
| `< > <= >= == != && \|\| ? :` | 比较 / 逻辑 / 三元 |
| 数字 / 小数点 | 直接写 |
| `=` 前缀 | 兼容 Excel 习惯（如 `=[A]+[B]`），自动剥离 |

### ❌ 不支持

- 字符串字面量：写 `"KG"` 会触发安全闸 → 返回 `—`
- Excel 函数：`SUM`、`IF`、`VLOOKUP` 等
- 跨行引用：只能引用**本行**的列

> **要显示固定文本**（如计量单位 "KG"）→ 改用 VARIABLE 列指向视图里的硬编码字段（汇总演示模板的 W 列就是这种做法 —— `v_costing_summary_full.weight_unit` 视图里写死 `'KG'::varchar`）。

### 安全闸

表达式只允许字符 `[\d+\-*/().,\s%<>=!&|?:]`，含其他字符立即返回 `—`（防注入 + 容错）。

### 公式示例

```
=[L]+[M]+[N]+[O]+[P]+[Q]+[R]+[S]+[T]    # 总成本（汇总演示模板的 U 列）
=[C]*[D]                                  # 数量 × 单价
=[B]*0.93                                 # 折扣 7%
=([A]+[B])*1.13                           # 税前合计 × 含税系数
=[A]>1000 ? [A]*0.9 : [A]                # 满 1000 打 9 折
={subtotal}                               # 直接拿变量（数值才有意义）
```

---

## 六、实操示例 — 按截图模板配 23 列

> 对应表：导入 Excel `核价系统功能基础数据功能结构所需字段（3.0版）.xlsx` 的「汇总」页签 1:1。

### 步骤 1 — 创建/打开模板

`Excel 模板配置` → 找「核价-汇总演示模板」→ 若已发布 → 点「派生新草稿」→ 进入详情页。

### 步骤 2 — 关联到核价模板

「基本信息」卡 → 右上角「关联」/「更换」按钮 → 抽屉内选「核价模板」组下的目标模板 → 保存。

### 步骤 3 — 配 23 列（完整对照表）

| col_key | title | source_type | variable_path / formula | 数据来源说明 |
|---|---|---|---|---|
| A | 宏丰料号 | VARIABLE | `{hf_part_no}` | lineItem.productPartNo |
| B | 品名 | VARIABLE | `{product_name}` | lineItem.productName |
| C | 规格 | VARIABLE | `{specification}` | lineItem.hfPartInfo.specification |
| D | 尺寸 | VARIABLE | `{size_info}` | lineItem.hfPartInfo.sizeInfo |
| E | 项次 | VARIABLE | `v_costing_summary_full.line_seq` | 视图按料号 ROW_NUMBER |
| F | 核价版本编号 | VARIABLE | `v_costing_summary_full.summary_no` | costing_summary.summary_no |
| G | 核价版本名称 | VARIABLE | `v_costing_summary_full.element_version_number` | （占位 — 等业务字段确认）|
| H | 元素价格版本 | VARIABLE | `v_costing_summary_full.element_version_number` | costing_price_version (ELEMENT) |
| I | 材料价格版本 | VARIABLE | `v_costing_summary_full.material_version_number` | costing_price_version (MATERIAL) |
| J | 汇率价格版本 | VARIABLE | `v_costing_summary_full.exchange_version_number` | costing_price_version (EXCHANGE) |
| K | 是否生效 | VARIABLE | `v_costing_summary_full.is_published_label` | CASE status WHEN PUBLISHED THEN '是' ELSE '否' END |
| L | 材料成本 | VARIABLE | `v_costing_summary_full.material_cost` | costing_summary_result[MATERIAL_COST]。**建议新形态**：改用模板 SQL 视图，`variable_path=$summary_full.material_cost`（需先建视图 `summary_full`） |
| M | 材料损耗成本 | VARIABLE | `v_costing_summary_full.material_loss_cost` | NULL 占位（compute 未实现）。**建议新形态**：`$summary_full.material_loss_cost` |
| N | 加工费 | VARIABLE | `v_costing_summary_full.processing_cost` | costing_summary_result[PROCESS_FEE]。**建议新形态**：`$summary_full.processing_cost` |
| O | 管理费 | VARIABLE | `v_costing_summary_full.management_cost` | NULL 占位。**建议新形态**：`$summary_full.management_cost` |
| P | 财务费 | VARIABLE | `v_costing_summary_full.finance_cost` | NULL 占位。**建议新形态**：`$summary_full.finance_cost` |
| Q | 利润 | VARIABLE | `v_costing_summary_full.profit` | NULL 占位。**建议新形态**：`$summary_full.profit` |
| R | 税费 | VARIABLE | `v_costing_summary_full.tax` | NULL 占位。**建议新形态**：`$summary_full.tax` |
| S | 电镀成本 | VARIABLE | `v_costing_summary_full.plating_cost` | NULL 占位。**建议新形态**：`$summary_full.plating_cost` |
| T | 其他外加工成本 | VARIABLE | `v_costing_summary_full.other_outsource_cost` | NULL 占位。**建议新形态**：`$summary_full.other_outsource_cost` |
| **U** | **总成本** | **FORMULA** | `=[L]+[M]+[N]+[O]+[P]+[Q]+[R]+[S]+[T]` | 9 列求和 |
| V | 币种 | VARIABLE | `v_costing_summary_full.quote_currency` | costing_summary.quote_currency |
| W | 计量单位 | VARIABLE | `v_costing_summary_full.weight_unit` | 视图硬编码 `'KG'::varchar` |

### 步骤 4 — 保存 + 发布

1. 顶部「保存」按钮 → 写入 `costing_template.columns`
2. 返回列表页 → 选中行 → 工具栏「发布」→ 自动把同一关联组的旧默认置 `is_default=false`，本份置 `true`

---

## 七、求值顺序（重要！）

`LinkedExcelView` 渲染每行时**两遍扫描**（见 `LinkedExcelView.tsx:238-285`）：

```
第一遍：处理所有 VARIABLE 列
  ├─ {code} 简写 → 前端 resolveVariable 取 lineItem 字段（同步）
  └─ BNF path → 推 pathTasks 队列 → 异步调后端 /formulas/evaluate
                  → 结果进 pathCache（带 partNo:path 缓存键）

第二遍：处理所有 FORMULA 列
  ├─ 替换 [X] 为第一遍 cellValues 里的数值
  ├─ 替换 {code} 为变量值
  └─ Function('"use strict"; return (...)') 运行表达式
```

**含义**：
- FORMULA 只能引用 VARIABLE 列，**不能引用其他 FORMULA 列**（没有第三遍扫描）
- 嵌套公式 → 展开成单行公式（U 列 `[L]+...+[T]` 就是平铺所有取数列）

---

## 八、调试技巧

| 现象 | 排查 |
|---|---|
| 「未找到关联的 Excel 模板」| 报价单的 `costing_card_template_id` 没有反查到 `is_default=true + PUBLISHED` 的 Excel 模板 → 检查关联与默认设置 |
| 列显示「加载中…」永不消失 | 后端 `/formulas/evaluate` 报错 → 看浏览器 DevTools Network 标签是不是 500 / 超时 |
| 列显示「—」 | BNF 路径求值返回 null：① 视图里没该料号的行；② 字段为空；③ 隐式 JOIN 没注入到 hf_part_no |
| FORMULA 列永远「—」| 公式含非法字符（如 `"KG"`、`SUM`、`IF`）→ 改用 VARIABLE 取数 |
| 隐式 JOIN 没生效 | 目标表/视图必须有 `hf_part_no` / `customer_id` / `part_no` 列才能自动注入 → `\d v_xxx` 看列结构 |
| 改了模板但页面没更新 | 浏览器强刷 `Ctrl+Shift+R` 清 HMR 缓存；检查模板状态是否 `PUBLISHED` |
| 报价单料号不在 demo 数据 | `v_costing_summary_full` 里没该料号 → 走核价单流程跑 compute() 产生数据，或临时插入 demo summary |
| 同一 linked_template 多份默认报错 | DB 唯一约束阻止；先把旧的 is_default 置 false |

### 验证 BNF 路径是否正确（命令行）

```bash
# 查视图是否有该料号数据
PGPASSWORD=joii5231 psql -h localhost -p 5432 -U postgres -d cpq_db \
  -c "SELECT material_cost, processing_cost FROM v_costing_summary_full WHERE hf_part_no='3100080003'"

# 验证后端 BNF 求值（需带 token）
curl -s -X POST http://localhost:8081/api/cpq/formulas/evaluate \
  -H 'Authorization: Bearer <token>' \
  -H 'Content-Type: application/json' \
  -d '{"expression":"{v_costing_summary_full.material_cost}","partNo":"3100080003"}'
```

---

## 九、VARIABLE vs FORMULA 决策表

| 需求 | 选哪个 | 为什么 |
|---|---|---|
| 显示某料号在某基础表的字段值 | **VARIABLE** + BNF | 数据来源在 DB |
| 显示客户图号、品名等 lineItem 字段 | **VARIABLE** + `{code}` | lineItem 内存有，无需查 DB |
| 几个数值列加减乘除 | **FORMULA** | 用 `[X]` 引用 |
| 固定字符串文本（如 "KG"） | **VARIABLE** + 视图硬编码字段 | FORMULA 不支持字符串字面量 |
| 条件判断 / 三元 | **FORMULA** | 支持 `?:` |
| Excel 的 `SUM`/`IF`/`VLOOKUP` | **❌ 不支持** | 把逻辑下沉到后端视图（如 `v_costing_summary_full` 用 PIVOT） |
| 跨产品行汇总（小计） | **❌ 不支持** | LinkedExcelView 是按 lineItem 一行一行渲染；要小计走「报价单」视图的 subtotal_formula |

---

## 十、相关数据库对象（参考）

### 主表

| 表 | 用途 |
|---|---|
| `costing_template` | Excel 模板定义（columns JSON）|
| `template` | 报价/核价模板（被 linked_template_id 引用）|
| `quotation` | 报价单（含 customer_template_id、costing_card_template_id）|
| `quotation_line_item` | 报价单产品行（每行 → Excel 一行）|

### 核价数据

| 表/视图 | 用途 |
|---|---|
| `costing_summary` | 核价单实例（每料号一份）|
| `costing_summary_result` | 核价计算结果（key-value，每 metric 一行）|
| `costing_price_version` | 全局基础数据版本（ELEMENT/MATERIAL/EXCHANGE）|
| `v_costing_summary_full` | **PIVOT 视图**：每料号 × summary 一行，9 个 metric 横向（Excel 模板从此视图取数） |

### basic_data_config 注册

`v_costing_summary_full` 已注册为 sheet `核价汇总`（`template_kind='COSTING'`），22 个字段可在 PathPickerDrawer 里下拉选择。

---

## 十一、变更日志（与本文档相关）

| 版本 | 日期 | 变更 |
|---|---|---|
| V73 | 2026-05-05 | `costing_template.linked_template_id` 增加；Excel 模板按本字段反查 |
| V74 | 2026-05-05 | 移除 `costing_template.category_id`，纯按 linked_template 组织 |
| V77 | 2026-05-05 | 引入 `costing_summary` + `costing_summary_result` |
| V79 | 2026-05-05 | `basic_data_config.template_kind`（QUOTATION/COSTING/BOTH）；PathPickerDrawer 加 Segmented 切换 |
| V80 | 2026-05-05 | 创建 `v_costing_summary_full` 视图；注册「核价汇总」sheet（22 attribute）；写入 23 列「核价-汇总演示模板」 |
| V81 | 2026-05-05 | 修复 Excel 模板 `linked_template_id` 关联到错误模板的问题；补 3 个 demo 料号 summary |
| V249 | 2026-05-26 | DROP `costing_template_sql_view` CASCADE；新建 `template_sql_view`（FK → `template.id`，与组件 SQL 视图同构） |
| V250 | 2026-05-26 | `template` 加 `template_sql_views_snapshot JSONB`；模板发布时冻结所有 ACTIVE 视图定义；Excel 视图列支持 `$name.col` 引用语法（`ownerContext=TEMPLATE`） |

---

**维护说明**：本文档与代码同源，修改 `LinkedExcelView.tsx` / `CostingTemplateConfig.tsx` 的字段映射或公式语法时同步更新本文。

---

## 配套文档

- 本文专注「单 Excel 模板内部的列配置」流程
- **跨「组件 / Excel 模板 / 公式」三层的配置决策与避坑** → 见 [`docs/配置方法论.md`](./配置方法论.md)（V96~V115 实战沉淀，含 14 个常见坑速查表 + 快速配置清单）
