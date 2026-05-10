# 核价 Excel 视图模板「完整公式版」配置说明

> **来源**：`data/template/核价系统计算公式和取值（示例）.xlsx`
> **落地**：`db/migration/V83__costing_excel_full_formula_template.sql`
> **模板名**：`核价Excel视图模板（完整公式版）`
> **状态**：DRAFT v1.0（待审核 + 关联模板 + 发布）
> **配套指南**：`docs/Excel模板配置指南.md`（通用 Excel 模板配置流程）

---

## 一、设计目标

用户提供的 Excel 文件包含核价单的**完整计算链路**：

- **15 个输入模块**（来料 BOM、来料与元素 BOM、人工/折旧/能耗/模具/耗材成本、来料加工费、来料其他费用、成品加工费&组装费、成品其他费用、电镀方案、电镀成本、其他外加工成本、单重）
- **17 条计算公式**（行 78–100）：纯材料成本、来料加工费、来料其他费用、回收成本、材料成本、材料损耗成本、各工序加工费、加工费、各元素电镀重量、电镀材料费、电镀成本、管理费、财务费、利润、税费、总成本（CNY/KG, CNY/PCS, USD/KG, USD/PCS）

V80 已经创建了 23 列的「核价-汇总演示模板」，但**该模板把 6 个商务加价/总成本列声明为 `VARIABLE` 读 `v_costing_summary_full` 视图，而视图里这些列目前是 `NULL` 占位**（等后端 `compute()` 升级）。

本模板的取舍：**不等 compute() 升级，直接在前端 Excel 视图层把加价链算出来**——把这 6 列改为 `FORMULA`，引用前面的 VARIABLE 列做加减乘除。这样即使后端只产出基础成本（材料/加工/电镀/外加工），前端也能立刻展示完整的成本拆解。

---

## 二、列定义（共 23 列；对外可见 16，隐藏 7）

> **隐藏列说明（V86 起）**：列定义里 `hidden: true` 的列**仍参与 FORMULA 求值链路**，但不会出现在「核价单 Excel 视图 / 报价单 Excel 视图」的表头里。本模板共 23 列，其中 7 列设为 hidden（**G** 加价基数、**H/J/L/N** 4 个加价比例、**Q** 单重、**S** 核价汇率）→ 对外只展示 16 列，与 Excel「汇总」的 16 列严格一一对应。

| col | 列名 | 来源 | path/formula | tag | 隐藏 | Excel 出处 |
|---|---|---|---|---|---|---|
| A | 宏丰料号 | VARIABLE | `{hf_part_no}` | — | | 由报价单 `lineItem.productPartNo` 自动注入 |
| B | 材料成本 | VARIABLE | `v_costing_summary_full.material_cost` | `MATERIAL_COST` | | 行 73 列 B |
| C | 材料损耗成本 | VARIABLE | `v_costing_summary_full.material_loss_cost` | `MATERIAL_LOSS` | | 行 73 列 C（视图占位，待 compute()） |
| D | 加工费 | VARIABLE | `v_costing_summary_full.processing_cost` | `PROCESS_FEE` | | 行 73 列 D |
| E | 电镀成本 | VARIABLE | `v_costing_summary_full.plating_cost` | `PLATING_COST` | | 行 73 列 I（视图占位） |
| F | 其他外加工成本 | VARIABLE | `v_costing_summary_full.other_outsource_cost` | `OUTSOURCE_COST` | | 行 73 列 J（视图占位） |
| G | 加价基数 | FORMULA | `=[B]+[C]+[D]+[E]+[F]` | — | ✅ | 中间列；Excel 公式中重复出现的「(材料+加工+材料损耗+电镀+其他外加工)」 |
| H | 管理费比例 | VARIABLE | `mat_fee[fee_type='FINISHED_OTHER',dim_element_name='管理费'].fee_ratio` | — | ✅ | 行 48；DB 中 fee_ratio 以小数存储(0.006 = 0.6%) |
| I | 管理费 | FORMULA | `=[G]*[H]` | `MGMT_FEE` | | 行 92 |
| J | 财务费比例 | VARIABLE | `mat_fee[fee_type='FINISHED_OTHER',dim_element_name='财务费'].fee_ratio` | — | ✅ | 行 49 |
| K | 财务费 | FORMULA | `=[G]*[J]` | `FINANCE_FEE` | | 行 93 |
| L | 利润比例 | VARIABLE | `mat_fee[fee_type='FINISHED_OTHER',dim_element_name='利润'].fee_ratio` | — | ✅ | 行 50 |
| M | 利润 | FORMULA | `=[G]*[L]` | `PROFIT` | | 行 94 |
| N | 税费比例 | VARIABLE | `mat_fee[fee_type='FINISHED_OTHER',dim_element_name='税费'].fee_ratio` | — | ✅ | 行 51 |
| O | 税费 | FORMULA | `=[G]*[N]` | `TAX` | | 行 95 |
| P | 总成本(CNY/KG) | FORMULA | `=[G]+[I]+[K]+[M]+[O]` | `TOTAL_CNY_KG` | | 行 97 |
| Q | 单重(g/pcs) | VARIABLE | `mat_part.unit_weight` | — | ✅ | 行 68/69（基础数据） |
| R | 总成本(CNY/PCS) | FORMULA | `=[P]/1000/[Q]` | `TOTAL_CNY_PCS` | | 行 98 |
| S | 核价汇率 | VARIABLE | `v_costing_exchange_rate[from_currency='CNY',to_currency='USD'].costing_rate` | — | ✅ | 行 3；DB 已有数据 = 0.138 |
| T | 总成本(USD/KG) | FORMULA | `=[P]*[S]` | `TOTAL_USD_KG` | | 行 99 |
| U | 总成本(USD/PCS) | FORMULA | `=[T]/1000/[Q]` | `TOTAL_USD_PCS` | | 行 100 |
| V | 报价币种 | VARIABLE | `v_costing_summary_full.quote_currency` | — | | 行 73 列 L/O |
| W | 计量单位 | VARIABLE | `v_costing_summary_full.weight_unit` | — | | 行 73 列 M/P；固定 `KG` |

> **设计原则**：所有数值均通过 BNF 路径引用基础数据，**无任何硬编码字面量**。这与 Excel 模板里 `F74=*L49/100` 引用 `L49` 单元格的语义一致——基础数据维护变更时模板自动联动，不需要修改公式。
>
> **当前 DB 数据现状**：
> - `mat_fee[fee_type='FINISHED_OTHER']` 仅有 3 条 demo 数据（dim_element_name = `财务管理费/回收费/材料管理费`），与 Excel 设计的 `管理费/财务费/利润/税费` 不一致 → H/J/L/N 这 4 列暂解析为 null，加价 FORMULA 列(I/K/M/O)显示 0，总成本只算基础部分
> - `v_costing_exchange_rate` 已有 CNY→USD = 0.138 → S 列与 USD 总成本立即可用
> - 待 admin 在「成品其他费用」基础数据中按 Excel 命名补全 4 行（管理费 0.006 / 财务费 0.005 / 利润 0.05 / 税费 0.13），整链路自动联动

---

## 三、Excel 公式 → 模板列对照

| Excel 公式（行号） | 在本模板的实现 |
|---|---|
| 行 82 `材料成本=纯材料成本+来料加工费+来料其他费用-回收成本` | 已由后端 `compute()` 写入 `costing_summary_result.MATERIAL_COST`，模板列 B 读取 |
| 行 83 `材料损耗成本=...` | 后端 `compute()` 待实现，列 C 当前为 NULL |
| 行 86 `加工费=∑[各工序加工费×(1+不良率)]` | 已由后端 `compute()` 写入 `PROCESS_FEE`，模板列 D 读取 |
| 行 90 `电镀成本=(电镀加工费+电镀材料费)×(1+不良率)` | 后端 `compute()` 待实现，列 E 当前为 NULL |
| 行 92 `管理费=(材料+加工+材料损耗+电镀+其他外加工)×管理费比例` | **列 H 取比例 + 列 I：`=[G]*[H]`**（比例从 mat_fee BNF 引用） |
| 行 93 `财务费=...×财务费比例` | **列 J 取比例 + 列 K：`=[G]*[J]`** |
| 行 94 `利润=...×利润比例` | **列 L 取比例 + 列 M：`=[G]*[L]`** |
| 行 95 `税费=...×税费比例` | **列 N 取比例 + 列 O：`=[G]*[N]`** |
| 行 97 `总成本(CNY/KG)=材料+材料损耗+加工+电镀+其他外加工+管理费+财务费+利润+税费` | **列 P：`=[G]+[I]+[K]+[M]+[O]`** |
| 行 98 `总成本(CNY/PCS)=总成本(CNY/KG)/1000/单重` | **列 R：`=[P]/1000/[Q]`** |
| 行 99 `总成本(USD/KG)=总成本(CNY/KG)×核价汇率` | **列 S 取汇率 + 列 T：`=[P]*[S]`** |
| 行 100 `总成本(USD/PCS)=总成本(USD/KG)/1000/单重` | **列 U：`=[T]/1000/[Q]`** |

> **未在本模板出现的公式**（仍在后端 `compute()` 链路里）：纯材料成本（行 78）、来料加工费（行 79）、来料其他费用（行 80）、回收成本（行 81）、各工序加工费（行 85）、各元素电镀重量（行 88）、电镀材料费（行 89）。这些是中间值，本模板只展示**最终的成本聚合视图**，不重新算 BOM 级中间值。

---

## 四、BNF 引用 vs 字面量（V85 起所有数值均为引用）

### 4.1 加价比例（H/J/L/N 列）

V85 起 4 个加价比例全部走 BNF 引用：
```text
列 H variable_path: mat_fee[fee_type='FINISHED_OTHER',dim_element_name='管理费'].fee_ratio
列 J variable_path: mat_fee[fee_type='FINISHED_OTHER',dim_element_name='财务费'].fee_ratio
列 L variable_path: mat_fee[fee_type='FINISHED_OTHER',dim_element_name='利润'].fee_ratio
列 N variable_path: mat_fee[fee_type='FINISHED_OTHER',dim_element_name='税费'].fee_ratio
```

加价 FORMULA 列(I/K/M/O) 引用 `[H]/[J]/[L]/[N]` 这些 VARIABLE 列：
```text
列 I formula: =[G]*[H]    管理费
列 K formula: =[G]*[J]    财务费
列 M formula: =[G]*[L]    利润
列 O formula: =[G]*[N]    税费
```

**fee_ratio 单位约定**：DB 中以**小数**存储（0.006 = 0.6%），所以 FORMULA 中直接 `[G]*[H]`，不需要再除以 100。这与 Excel 单元格 `*L49/100`（Excel 里 L49=0.5 是百分比展示值）不同——但 Excel 中显示值与本系统存储值在数学上等价。

**Excel 来源对照**：
- Excel 行 43–44（来料其他费用，fee_type='INCOMING_OTHER'）：管理费 0.8% / 财务费 1.2%（**未使用**）
- Excel 行 48–51（成品其他费用，fee_type='FINISHED_OTHER'）：管理费 0.6% / 财务费 0.5% / 利润 5% / 税费 13% ← 本模板引用

> Excel 行 74 的 F74 公式 `=SUM(I74:J74,B74:D74)*L49/100` 引用的就是 L49=0.5（成品级），与本模板设计一致。

### 4.2 核价汇率（S 列）

V85 起核价汇率走 BNF 引用：
```text
列 S variable_path: v_costing_exchange_rate[from_currency='CNY',to_currency='USD'].costing_rate
```
DB 已有数据 = 0.138，列 T `=[P]*[S]` 立即有值。

### 4.3 历史变更轨迹

| 版本 | 状态 |
|---|---|
| V83 | 初版：4 加价比例字面量 0.008/0.012/0.05/0.13；汇率字面量 0.138 |
| V84 | 修正字面量值：0.008→0.006、0.012→0.005（来料级误填，应为成品级） |
| **V85** | **架构修正**：所有数值均走 BNF 引用，无字面量；列数 19→23 |

---

## 五、与 V80 模板的关系与切换建议

| 维度 | V80「核价-汇总演示模板」 | V85 本模板「完整公式版」 |
|---|---|---|
| 列数 | 23 | 23 |
| 加价/总成本列 | 全部 VARIABLE，读视图 NULL → 显示空白 | FORMULA 自洽，前端立刻有非空展示 |
| 比例 / 汇率 | 不涉及（依赖 compute()） | 全部 BNF 引用 mat_fee + v_costing_exchange_rate |
| 适用阶段 | 后端 `compute()` 完整产出后 | **当前 / compute() 升级前的过渡** |
| 默认状态 | PUBLISHED + 关联具体模板 | DRAFT + 不关联（待用户审核） |

**建议**：
1. 本模板上线后观察 1~2 个真实核价单是否能正确显示加价链
2. 与业务确认 4 个比例（按用户 Excel 行 50 vs 52–53 选哪组）
3. 当 `compute()` 写入了 `MATERIAL_LOSS`/`PLATING_COST`/`OUTSOURCE_COST` 三个 metric 后，列 C/E/F 自动开始有值——FORMULA 链不需要任何改动
4. 评估是否替换 V80 模板为默认（设 `is_default = true` + 解除 V80 的默认）

---

## 六、回归测试清单

发布此模板前请确认：

- [ ] 数据库迁移 `V83` 已成功执行（Flyway 启动日志看到 `V83 ... migrating`）
- [ ] 「核价模板配置」列表中可见「核价Excel视图模板（完整公式版）」(状态 DRAFT)
- [ ] 进入模板详情页，19 列定义无报错（FORMULA 列正确显示 `=[X]...` 表达式）
- [ ] 关联到一份`templateKind='COSTING'` 的核价模板（`linked_template_id`）
- [ ] 设置该模板 `is_default=true` 后发布到 PUBLISHED
- [ ] 创建 / 打开一张关联了对应核价模板的报价单，切到核价单 Excel 视图
- [ ] 至少能看到 hf_part_no = `3100090136` / `3120012574` / `3120012575`（V81 演示数据）的 19 列
- [ ] B + D 列有值（视图中的 material_cost / processing_cost），G/H/I/J/K/L 加价链按公式联动
- [ ] M 列读到 `mat_part.unit_weight`，N/Q 列正确按 PCS 分摊
- [ ] 切换报价单的核价模板后，Excel 视图自动重新匹配本模板（`linked_template_id` 反查链路）

---

## 七、变更日志

| 日期 | 变更 |
|---|---|
| 2026-05-06 | V83 初版：19 列；商务加价比例字面量；核价汇率字面量；DRAFT 状态待审核 |
| 2026-05-06 | V84 修正：管理费 0.008→0.006、财务费 0.012→0.005（误把"来料级"比例填进去了，应使用 Excel L48/L49 引用的"成品级"比例）|
| 2026-05-06 | V85 架构修正：去除全部字面量，4 个加价比例 + 核价汇率均改为 BNF 引用基础数据；列数 19→23；与 Excel 模板「`*L49/100`」式的引用语义对齐 |
| 2026-05-06 | V86 + 前端：CostingTemplateColumn 新增 `hidden` 字段；7 个中间值列(G/H/J/L/N/Q/S)打 `hidden=true`，仅参与公式计算不在 Excel 视图显示；对外可见 16 列与 Excel「汇总」严格对齐 |
