# 报价通用组件（10 个）

> 输入：`客户组件模板/组件模板-报价通用模板.xlsx`
> 规则：`dev-docs/rule-260724-组件模板配置/AGENT-配置入口.md`（+ `报价侧.md` / `2-组件与字段.md` / `3-SQL视图.md` / `4-页签属性与树.md`）
> 生成：2026-08-02 · 报价侧（`system_type='QUOTE'`）
> **规则复核：2026-08-09**（见文末「规则复核记录」）—— 材料成本已按 `报价侧.md §5.2.2` 切到料号级取价函数，其余 9 个组件复核无需改动。

## 交付物

每个组件两个文件（sheet 名同名）：

| 文件 | 是什么 | 怎么用 |
|---|---|---|
| `<组件>.json` | **单组件 bundle**（服务器导出，带 checksum） | 在「组件管理」建好目录 → 导入该 JSON（`POST /component-directories/{新目录id}/import/commit?conflictPolicy=RENAME`） |
| `<组件>.sql` | 该组件 SQL 视图的**可编辑副本**（带配置头注释） | 只作阅读/改写底稿；**改它不生效**，要粘回 UI 或走 `PUT /components/{cid}/sql-views/{viewId}`，并把 `.json` 同步重出 |

10 个组件：产品 / 物料 / 材料成本 / 外购件成本 / 加工费 / 组装加工费 / 来料固定加工费 / 其他费用 / 来料其他费用 / 电镀费用。

## 组件配置总表

| 组件 | tabType | partNoField | partNameField | rowKeyFields | sortField | 视图 | requiredVariables | 字段数 | BOM 闭包 |
|---|---|---|---|---|---|---|---|---|---|
| 产品 | 主件 | 销售料号 | — | 销售料号 | — | `$cp_view` | customerCode | 5 | 否（产品自身属性） |
| 物料 | **BOM** | — | 料件 | 料件 | — | `$bom_view` | **total_material_no** | 11 | 树契约（Pass2） |
| 材料成本 | 材质元素 | 料号 | 料件 | 销售料号,料号,元素 | 项次 | `$mc_view` | customerCode, priceBaseDate | 13 | 是 |
| 外购件成本 | 外购件 | — | 料件 | 销售料号,料件,要素 | — | `$wg_view` | customerCode | 7 | 是 |
| 加工费 | 零件 | 料号 | 料件 | 销售料号,料号,项次,工序 | 项次 | `$jg_view` | customerCode | 8 | 是 |
| 组装加工费 | 主件 | 销售料号 | 料件 | 项次,工序 | 项次 | `$zz_view` | *(无)* | 7 | 否（成品自身工序） |
| 来料固定加工费 | (空) | — | — | 销售料号,料件,项次,工序 | 项次 | `$ll_view` | customerCode | 7 | 是 |
| 其他费用 | 主件 | 销售料号 | — | 项次,要素 | 项次 | `$qt_view` | customerCode | 6 | 否（成品自身费用） |
| 来料其他费用 | (空) | — | — | 销售料号,料件,要素 | 项次 | `$lqt_view` | customerCode | 8 | 是 |
| 电镀费用 | **(空)** | — | — | 销售料号,料件,项次,要素 | 项次 | `$dp_view` | customerCode | 7 | 是（闭包键=`finished_material_no`）|

- **料号列留空的 5 个页签**（物料/外购件成本/来料两个/电镀费用）：模板没有料号列 → 按料号铁律 `partNoField` 留空，名称列担标识，料号只在 SQL 里做 JOIN / 归属 / ORDER BY 键，**不新增模板没有的可见列**。其中**电镀费用连 `partNameField` 也留空**（费用页签不参与树，无需标识列，见下方 2026-08-03 拍板）。
- `sortField`：模板勾了「排序列」的照配（材料成本/加工费/组装加工费/来料固定加工费/其他费用）；**来料其他费用、电镀费用模板未勾，按 §4.2「多行项次页签建议配」推导补的**，不想要就把 `sortField` 置空。
- 字段与模板列 **10/10 严格 1:1（含顺序）**，无凭空新增列。

## 数据来源（每列绑到哪张 V6 表）

| 组件 | 主表 / price_type | 关键绑定 |
|---|---|---|
| 产品 | `material_master` + `material_customer_map` | 销售料号=`mm.material_no`、客户料号名称=`mcm.customer_material_name`、客户产品编号=`mcm.customer_product_no`、报价货币=`mcm.quote_currency`；**税率=无源手填** |
| 物料 | `material_bom_item`（树） | 料件=`COALESCE(mm.material_name, mr.name)`、产出类型=`component_usage_type`、组成数量=`composition_qty`、毛/净重=`rough_weight`/`net_weight`、单位=`weight_unit`、材料占比=`material_ratio`、损耗率=`scrap_rate`、不良率=`defect_rate`；回收价格/回收比例=`unit_price(INCOMING_MATERIAL_RECYCLE)` 的 `pricing_price`/`cost_ratio`（相关标量子查询，按「投入料号+直接父件」两维） |
| 材料成本 | `element_bom_item` | 料号=`material_part_no`、料件=材质名（逐行经 `material_part_no` 取 `material_recipe.name`）、元素=`component_no`、组成含量=`content`、损耗率=`scrap_rate`、毛重=`composition_qty`、**净用量=`base_qty`**、毛/净用量单位=`issue_unit`（同一列，见下）、**元素回收折扣=`recovery_discount`**、元素单价=**`f_material_element_price` 表函数（2026-08-09 由 `f_customer_element_price` 切换，见文末复核记录）** |
| 外购件成本 | `material_bom_item`（`characteristic='OUTSOURCED'`） | 组成数量=`composition_qty`、组成单位=`issue_unit`；要素/费用/单位=`unit_price(COMPONENT_OTHER)`，按【闭包根成品 × 组成件】匹配 |
| 加工费 | `unit_price(PROCESS)` | 料号=`code`（零件料号）、工序=`process_master.process_name` 兜底 `operation_no`、加工费=`pricing_price`、**比例=`cost_ratio`**、单位=`unit` |
| 组装加工费 | `capacity` | 工序=`COALESCE(NULLIF(process_name,''), process_no)`、加工费=`fixed_cost`、单位=`capacity_unit`、**不良率=`default_defect_rate`**。该表无 `customer_no`，故不加客户过滤 |
| 来料固定加工费 | `unit_price(INCOMING_MATERIAL_PROCESS)` | 加工费=`COALESCE(pricing_price, base_value)`（该类型金额常落 `base_value`）、**比例=`cost_ratio`** |
| 其他费用 | `unit_price(FINISHED_MATERIAL_OTHER)` | 料号在 `code`；要素=`cost_type`、费用=`pricing_price`、**比例=`cost_ratio`**（按比例登记的项值在这列、`费用`为空，两列并存不互相兜底） |
| 来料其他费用 | `unit_price(INCOMING_MATERIAL_OTHER)` | 料号=`code`（投入料号）、销售料号=`finished_material_no`、要素=`cost_type`、费用=`pricing_price`、比例=`cost_ratio` |
| 电镀费用 | `unit_price(PLATING)` | **repair-0802 新口径（2026-08-03 重写）**：销售料号=`finished_material_no`（成品，必有值）、`code`=投入料号（被电镀的零件料号；Excel 两列皆空时回退=销售料号）；`hf_part_no` 与闭包 JOIN 键**都绑 `finished_material_no`**；料件=`COALESCE(mm.material_name, mr.name)` via `code`（=投入料号名称，**≠电镀元素名称**）；要素=`cost_type`（电镀加工费/电镀材料费，**保持行式不做 PIVOT**，因模板把「要素」做成了一列）、费用=`pricing_price`、**不良率=`defect_rate`** |

### 三处用户拍板（2026-08-02）

1. **产品·税率 = 无源手填**。V6 客户主数据没有税率列（唯一的 `customer_tax` 是空表且在 AP-53 禁表黑名单内，视图引用会被 dry-run 拒）。库里「成品其他费用」有 `cost_type='税率'` 的行（比例 13%），它在【其他费用】页签照常显示。
2. **材料成本·毛用量单位与净用量单位都绑 `issue_unit`**。`element_bom_item` 只有这一个单位列（Q04 口径：净用量单位非空则存它、否则存毛用量单位），故两列**显示同值**，属已知取舍。
3. ~~**电镀费用 tabType 保持「零件」**。实测其料号确是零件料号（非成品），故零件页签成立、不会让 BOM 树把成品误判成零件。~~ **⚠️ 已于 2026-08-03 推翻，见下节。**

### 电镀费用按 repair-0802 口径重写（2026-08-03）

`Q17PlatingCostHandler` 在 repair-0802 改了两列语义（`code` 销售料号→**投入料号**；`finished_material_no` 恒 NULL→**销售料号**），`dev-docs/rule-260724-组件模板配置/报价侧.md §5.4/§7.4` 随之重写，本目录的 `电镀费用.json` / `电镀费用.sql` 按新口径重出：

| | 旧版（2026-08-02） | 现行（2026-08-03） |
|---|---|---|
| `hf_part_no` | `COALESCE(cl.root_no, up.code)` | `COALESCE(cl.root_no, up.finished_material_no)` |
| 闭包 JOIN 键 | `cl.node_no = up.code`（零件） | `cl.node_no = up.finished_material_no`（成品）—— 对齐 `3-SQL视图.md §3.8.2` 的「零件/unit_price」行、与 `加工费.sql` 一致 |
| `_销售料号` | `COALESCE(cl.root_no, up.code)`（靠闭包反推） | `up.finished_material_no`（数据直接给出） |
| `tabType` / `partNoField` / `partNameField` | 零件 / (空) / 料件 | **(空) / (空) / (空)**（用户 2026-08-03 拍板：退回费用页签，不参与树） |

**为什么必须改（A/B 实证，单事务造数 + ROLLBACK）**：旧写法按**零件号**做闭包，只要该零件被多个成品共用，就会把 A 成品的电镀费串到 B 成品的报价行上 —— 造一条 `P-TEST9 → S-80011` 的 BOM 边后，旧 SQL 让与 P-TEST9 毫无关系的 1.396/1.550 两行出现在它的报价行上（2 行），新 SQL 0 行 ✅。当前库只有单成品数据，两者结果碰巧相同，故这是**静默的错**。

**tabType 拍板理由**：模板电镀 sheet 没有【投入料号】可见列，`partNoField` 只能留空、由名称列担标识；而 repair-0802 的回退规则（Excel 两列皆空 → `code` = 销售料号）会让「料件」显示成品名 → 树把成品误判成零件节点。按 `报价侧.md §5.4 随动规则 4`「模板没有投入料号列则退回费用页签」处理。**⚠️ 模板 `组件模板-报价通用模板.xlsx` 电镀费用 sheet 的 B1 仍写着「零件」，与本次交付不一致，建议改为留空。**

## 导入步骤

1. 「组件管理」新建目录（建议名 = 客户名）。
2. 逐个导入 10 个 `.json`（`conflictPolicy=RENAME`）。导入端会一并还原字段 / `rowKeyFields` / `tabType` / `partNoField` / `partNameField` / `sortField` / SQL 视图 / `requiredVariables`。
3. **导入后必须手工补 1 项**（bundle 带不走）：**材料成本**接了价格策略，组件级三项角色字段不在导出格式里 —— 在「组件管理」编辑该组件时补：

   | 角色字段 | 值 |
   |---|---|
   | `element_code_field` | `元素` |
   | `element_price_field` | `元素单价` |
   | `element_currency_field` | *(留空，本模板无货币列)* |

   直接改库也可以：
   ```sql
   UPDATE component SET element_code_field='元素', element_price_field='元素单价',
          element_currency_field=NULL, updated_at=now()
    WHERE id='<导入后的材料成本组件id>';
   ```
   ⚠️ 不补的话，后端 `assertElementBindingRequirement` 会在**下次保存该组件时 400**（视图里有取价函数却没配齐角色字段）。
4. BOM 树页签（物料）依赖 `costing_bom_tree_config`（`usage=QUOTE`, active）—— **本库已有**「报价BOM树-QUOTE口径v1」，无需另建；换库部署时要确认它在（V363 已纳入迁移）。
5. 🚨 **模板要发布/发新版本，配置才会生效**（2026-08-06 task-0806 起）：组件导入完只是有了组件，**改组件不会推给任何已发布模板**。把组件挂进模板后必须 `publish`；对已发布的老模板则是 `createNewDraft → publish` 出**新版本**（没有「重新发布」这个操作）。若打开报价单返 `409 TEMPLATE_NOT_FROZEN`，是该模板从未按新语义冻结过 → 管理员调 `POST /templates/{id}/freeze` 补冻。规则见 `dev-docs/rule-260724-组件模板配置/1-总则与工作流.md §1.5`。

## 验证记录（`cpq_db_0724` 真数据，客户 `CUST-0001`，料号 `S-3120014539`）

- 10 个组件 `POST /components` + `POST /sql-views` 全部 200（**保存即 dry-run**，SQL 语法/禁表/参数全过）。
- 9 个平铺组件走真管道 `expand-driver` 无报错；树页签（物料）按 Pass2 契约不适用平铺展开，另行直连验证。
- 按 `QuotePendingRewriter` 逐字复刻（表替换 + `pending_supersedes` 遮蔽）模拟 DRAFT 渲染，直连 PG 实跑：

| 组件 | 行数 | 出数实证 |
|---|---|---|
| 产品 | 1 | 客户产品编号 `PN0507945`、报价货币 `USD` |
| 物料（树） | 6 | 5 条边 + 根分支；二层挂接 `00002 ← S-80011 ← S-3120014539` 正确 |
| 材料成本 | 4 | 主件 `H65/Cu`(单价 1850)、`AgNi11#-Ⅰ/Ag`(回收折扣 20)、**闭包带出子件 `S-80011` 的 `AgC3` 的 C/Ag 两行**；无价元素留 `NULL` 不掉行；净用量 0.624610/0.056910 |
| 外购件成本 | 1 | 组成件1 / 材料费 55 |
| 加工费 | 1 | 零件料号 `S-80011`、工序 `Z380`、14.00 |
| 组装加工费 | 2 | 焊接 0.08 / 铆接 12.00 |
| 来料固定加工费 | 2 | 0.043260 / 0.034140（走 `base_value` 兜底取到） |
| 其他费用 | 4 | 材料管理费 4.5 / 外购件管理费 4 / 利润 4 / 税率 13（值在「比例」列） |
| 来料其他费用 | 0 | **数据缺口**：本库 `INCOMING_MATERIAL_OTHER` 无数据（导入文件该 sheet 0 行） |
| 电镀费用 | 2 | 电镀加工费 1.396 / 电镀材料费 1.550、料件=`投入零件1`、单位 `PCS`（**2026-08-03 按 repair-0802 新口径重跑**：`hf_part_no`/闭包键改绑 `finished_material_no` 后行数与值不变，另经单事务造数 A/B 证明旧写法会串号，见上节）|

- 字段↔模板列 1:1 脚本比对 **10/10 完全一致（含顺序）**。
- 金额/小计（R7）逐条核过：`费用`/`加工费` 类 `is_amount=is_subtotal=true`；`元素单价`/`回收价格` 是单位量纲 → 只 `is_amount`、不进小计；比率类（比例/损耗率/不良率/材料占比/组成含量/税率/元素回收折扣）两者皆 false。
- 暂存目录与暂存组件建完即删，共享库无残留。

## 已知数据缺口（配置正确、当前无数据，导入数据后自动出值）

| 列 | 落点 | 现状 |
|---|---|---|
| 物料·产出类型 / 材料占比 / 不良率 / 回收价格 / 回收比例 | `material_bom_item` 各列 / `unit_price(INCOMING_MATERIAL_RECYCLE)` | 本库导入文件这些列空白 |
| 加工费·比例、来料固定加工费·比例 | `unit_price.cost_ratio` | 写入路径在（Q10/Q06），当前无值 |
| 组装加工费·不良率 | `capacity.default_defect_rate` | 写入路径在（Q14 读「拒收率/不良率」），当前无值 |
| 电镀费用·不良率 | `unit_price.defect_rate` | 写入路径在（Q17），当前无值 |
| 电镀费用·项次 | `unit_price.seq_no` | **Q17 不写 seq_no** → 该列恒空，可手填；不想要就删掉这个字段 |
| 材料成本·组成含量(%) | `element_bom_item.content` | 导入文件该列空白 |
| 来料其他费用（整页） | `unit_price(INCOMING_MATERIAL_OTHER)` | 该 sheet 无数据 |
| 外购件成本 | `material_bom_item.characteristic='OUTSOURCED'` | 有 1 条，出数正常 |

## 已知取舍

- **回收价格/回收比例的匹配维度**是【投入料号 + 直接父件】：三层以上 BOM 中，孙件那行取不到值（它的父不是成品）。
- **闭包性能**：CTE 对该客户所有根料号做全展开再靠 `hf_part_no` 筛，BOM 量大的生产库需实测（§3.8.5）。
- **同名多行撞键**：料号列留空的页签靠名称列做行键，真出现同名多行需回头确认是否把料号列显出来。

---

## 规则复核记录（2026-08-09）

本目录 2026-08-02/03 生成，此后 rule-0724 规则集有更新。逐条复核结论：

### 🔴 已改：材料成本 —— 切到料号级取价函数 `f_material_element_price`

依据 `报价侧.md §5.2.2`（V369 起生效，🔴「新配报价侧材料成本类组件一律直接配这一版，不要再照抄 §5.2.1 的旧写法」）。本目录生成于该节写入之前，故用的是旧函数。

```diff
- LEFT JOIN f_customer_element_price(:customerCode, :priceBaseDate) cep
-        ON cep.element_code = ebi.component_no
+ LEFT JOIN f_material_element_price(:customerCode, :priceBaseDate) cep
+        ON cep.element_code = ebi.component_no
+       AND cep.material_no = COALESCE(cl.root_no, ebi.material_no)   -- JOIN 键铁律：与 hf_part_no 表达式逐字一致
```

**不是无害清理 —— 旧写法在本库就已取错价**。A/B 实跑（`CUST-0001`，直连 PG）：

| hf_part_no | 元素 | 旧（`f_customer_...`） | 新（`f_material_...`） |
|---|---|---|---|
| S-3120014539 | Cu | 171.3680 | 171.368000（同值，仅 numeric scale 差异）|
| S-3120014539 | **Ag** | **14042.1875** | **43358.750000** ← 该客户+料号有价格版本指针，旧写法**静默按实时价** |
| S-80011（子件自成闭包根）| Ag | 14042.1875 | 14042.1875（该料号无指针 → fallback 实时价，符合设计）|
| S-80011 | C | NULL | NULL（无价元素留 NULL、不掉行）|

行数 4 = 4，无价元素不掉行；JOIN 键按各行自己的 `hf_part_no` 取价，行为与 §5.2.2 一致。
（`material_price_version_ref` 里 `CUST-0001 / S-3120014539` 的指针建于 2026-08-05。）

**验证方式**：建暂存目录 → 导入本 bundle → `PUT sql-views`（**保存即 dry-run，200**）→ `PUT /components` 补角色字段 → 重新导出 → `POST /import` preview 校验 `checksumValid=True / canCommit=True / warnings=[]` → **删组件 + 删目录，共享库零残留**（`component_directory`/`component`/`component_sql_view` 三张表实测均 0 行）。

> 顺带实证：不补角色字段直接保存组件 → `400 COMPONENT_ELEMENT_BINDING_REQUIRED`，`missingFields=["elementCodeField","elementPriceField"]`。**下方「导入步骤 3」仍然必要**——角色字段确认**仍不在导出格式里**（本次重新导出的 bundle 组件键里没有这三项）。

### ✅ 复核无需改动的其余 9 个

| 新规则 | 是否影响本目录 |
|---|---|
| `DATA_SOURCE` / 裸 `INPUT` 字段类型退役（`2-组件与字段 §2.2 C1`） | **不影响**：10 个组件共 78 个字段全是 `INPUT_TEXT`/`INPUT_NUMBER`，本就符合 R1 |
| 公式改绑稳定 `formula_id`（BL-0098） | **不影响**：10 个组件 `formulas` 全为空、无 `FORMULA` 字段；重新导出的 `bindingReport` 为 `unboundCount=0 / totalFormulaRefs=0` |
| 树属性保留字 `层级`/`是否叶子`/`是否根`（task-0803） | **不影响**：逐字段扫过，无任何组件用这三个名字（BOM 页签「物料」的 11 个字段也不撞）|
| `component_subtotal` 依赖边改列粒度（repair-0808） | **不影响**：无跨页签公式 |
| V44 禁表 AP-53 | **不影响**：SQL 只引用 `material_bom_item` / `element_bom_item` / `material_master` / `material_customer_map` / `material_recipe` / `unit_price` / `capacity` / `process_master` 等 V6 表 |

### ⚠️ 新增注意：改完组件不会自动进已发布模板

2026-08-06 起 H1 自动同步已退役（`1-总则与工作流.md §1.5`）。**导入本目录组件后，引用它们的模板必须重新发布新版本才生效**；已发布的老模板不受影响。详见下方「导入步骤」第 5 条。

### 📌 原文件 checksum 全部有效

复核期一度怀疑 6 个 JSON 的 checksum 失效，经后端 `POST /import` preview 逐个实测 **`checksumValid=True`** —— 是复核脚本的 JSON 序列化与后端 Jackson 不逐字节一致导致的误报，**文件本身没问题**。
