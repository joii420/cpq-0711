# 配置入口 · Agent 必读（组件模板配置 Runbook）

> **这是任意 Agent 做「组件模板配置」的唯一起点。** 读完本文 + 拿到填好的 `组件模板.xlsx`，即可据规则**准确配出一套组件**，无需再逐项问用户（模板留白处才问）。
> 深度规则见同目录 `1`~`5` / `报价侧.md` / `核价侧.md` / `附录-速查.md`（完整正文迁移前以 `docs/rule/报价模板生成规则.md` 为准）。

---

## 0. 任务与产物

- **输入**：① 填好的 `组件模板.xlsx`（格式见 §2）② 客户名 / 目录名 ③ 报价侧 or 核价侧。
- **产物**：在「组件管理」建好一套组件（目录 → 每组件：字段 + `component_sql_view` + 页签属性），并自检渲染正确。
- **纪律**：模板已填的**照配**；模板留白且规则推不出的**才问用户**（用业务白话问，别问 DB 术语）。

## 1. 环境（配置前自检）

- DB `10.177.152.12:5432/cpq_db_0724`（`postgres/joii5231`；2026-07-24 起的迁移目标库，psql 一律 `-d cpq_db_0724`，别连旧 `cpq_db`）；API base `http://localhost:8081/api/cpq`；账号 `admin/Admin@2026`。
- dev server 存活：`curl -s --noproxy '*' -o /dev/null -w '%{http_code}' http://localhost:8081/api/cpq/components` → 期望 `401`。
- **禁在 DB 建物理视图**；一律组件 SQL 视图 `$name`。**FROM 必须 V6 表**（禁 `mat_*`/`plating_plan`/`element_price*`/`costing_part_*`）。

## 2. 读模板（`组件模板.xlsx` 格式）

- `0-填写说明`：图例（用户视角）。
- **每个组件一个 Sheet**（Sheet 名 = 组件名）：
  - **B1** = 页签类型 `tabType`（主件/材质元素/零件/外购件/BOM/(空)）。
  - **第 3 行** = 表头；第 4 行起 = 字段表。
  - 列：`字段名` | `料号列` | `名称列` | `行键` | `排序列` | `接价格策略` | **`元素列`** | `备注(大白话)`（后 6 列勾 `✓` 或留空）。
  - 颜色：🟡黄=用户填(字段名/tabType)；⬜灰=可选/你推导；🟩绿=接价格策略勾选 + 元素列勾选。
  - 🆕 **`元素列`**（2026-07-29 task-0729 新增）：只在**接了价格策略的组件**上勾，标出「拿哪一列的值去匹配元素编码」的那个字段（材质元素页签通常是「元素」）。详见 §3.6。

## 3. 逐组件推导（模板 → 配置）

### 3.1 组件级属性（直接从模板取）

| 配置项 | 来源 |
|---|---|
| `name` | Sheet 名 |
| `componentType` | 默认 `NORMAL`；名字/语义是小计→`SUBTOTAL`；Excel 卡→`EXCEL` |
| `tabType` | B1（`(空)` → 不设） |
| `partNoField` | 勾「料号列」的字段。**模板没有料号列 → 留空，禁凭空造字段**（客户模板不显示料号是真实场景），料号改为只在 SQL 里做 JOIN 键，见 §3.4 |
| `partNameField` | 勾「名称列」的字段。`材质元素/零件/外购件/主件` 4 类页签：**料号列与名称列至少配一个**（两者皆空才 400），`partNoField` 留空时由它承担标识 |
| `rowKeyFields` | 勾「行键」的字段集合（多行组件必配；**一号多材质须含「材质」字段**——模板没有材质料号列时靠材质名区分多材质链）。**只能用模板实有字段**，别为做行键造列 |
| `sort_field` | 勾「排序列」的字段（多行 项次/序号 页签建议配，数字正序） |
| 🆕 `element_code_field` | 勾「元素列」的字段（**只在接价格策略的组件上配**）。升版时拿这一列的值去匹配元素编码。**必问用户，禁自己猜**，见 §3.6 |
| 🆕 `element_price_field` | 勾「接价格策略」的**单价**字段（如「元素单价」）。升版时**只改这一个键**，其余手改字段不动 |
| 🆕 `element_currency_field` | 勾「接价格策略」的**货币**字段；视图没输出货币列就**留空**（升版只改单价） |
| `dataDriverPath` | `$<拼音缩写>_view`（如 产品=`$cp_view`、材料成本=`$mc_view`、加工费=`$jg_view`） |
| `required_variables` | 报价：`["customerCode"]`（有「接价格策略」再+`"priceBaseDate"`）；核价：`["priceBaseDate"]`（不含 customerCode） |

### 3.2 字段级属性（推导）

| 配置项 | 推导规则 |
|---|---|
| `field_type` | 见 §3.3 推导表（用户不填，你推） |
| `is_amount` | 金额/费用/单价 列 = `true`（`content="0"`；汇率 `content="1"`） |
| `is_subtotal` | **R7 联动（2026-07-27）**：`is_amount=true` 且**语义可沿行累加**（费用/金额/成本/加工费）→ **一并 `true`**；**单价/汇率/费率等单位量纲的金额 → 保持 `false`**（累加无业务含义，会污染页签合计与 `component_subtotal`）。判据：*该列各行相加是不是有含义的钱数？* 详见 `2-组件与字段.md` R7 |
| 别名（视图 SELECT）| 业务列 `_字段名`（如 `_销售料号`）；**唯 `hf_part_no` 驱动键不加 `_`**；**唯 接价格策略的 单价/货币 别名 = 字段名逐字、不加 `_`**（硬约束） |
| `default_source.path` | 报价：`$view._字段名`（价格策略列 `$view.字段名`）；核价：改用 `basic_data_path` |
| 数据来源（写进 SQL）| 见 §3.5 tabType→V6 映射；「备注」「接价格策略」覆盖 |
| `formula` | `FORMULA` 字段才有；用户在「备注」用白话说，你转公式 |

### 3.3 field_type 推导表

| 字段名特征 | field_type |
|---|---|
| 料号/编号/名称/规格/单位/货币/工序/要素/类型 | `INPUT_TEXT` |
| 量/率/%/重/单价/费用/金额/厚度/汇率/数量/项次/序号 | `INPUT_NUMBER`（金额性 `is_amount=true`，再按 R7 判 `is_subtotal`） |
| 备注写「手填/无源」 | `INPUT_*` 无 `default_source`（`content` 给默认） |
| 备注写「公式/合计/小计」 | `FORMULA` |

### 3.4 🔑 料号列铁律（先判「模板有没有料号列」，再谈绑哪列）

**第一步 · 模板有没有料号列**（2026-07-24 用户定，优先于下面的绑定表）：

| 情况 | 怎么配 |
|---|---|
| 模板**有**料号列（勾了「料号列」） | `partNoField` = 该字段，绑值按下表语义层级 |
| 模板**没有**料号列 | **`partNoField` 留空**；`partNameField`（名称列）承担标识；料号**只在 SQL 里做 JOIN 键**（`hf_part_no` / 取名 JOIN / ORDER BY），**不出可见列** |

> 🚫 **禁止为凑 `partNoField` 而新增模板里没有的可见字段**（如给材料成本加「材质料号」、给外购件加「组成件料号」）。客户模板不显示料号是**真实业务场景**，凭空加列会让报价单多出用户看不懂的空列。
> ✅ 后端 2026-07-23 已放宽（`ComponentService.assertPartNoFieldRequirement`）：`{材质元素,零件,外购件,主件}` 只要求**料号列或名称列至少一个**，两者皆空才 400。
> ✅ 类型判定同样支持（`QuotationTreeService`）：**优先 `partNoField` 取料号值，为空则回落 `partNameField` 取名称值**做匹配标识。

**第二步 · 模板确有料号列时，绑哪列**（绑错语义层级仍是硬错）：

| tabType | partNoField 应绑的 V6 列 |
|---|---|
| 主件 | `material_master.material_no`（产品） |
| 材质元素 | `element_bom_item.material_part_no`（材质料号） |
| 零件 | `material_bom_item.component_no`；**`unit_price` 源取 `code`（零件料号），非 `finished_material_no`（成品）** |
| 外购件 | `material_bom_item.component_no`（`characteristic='OUTSOURCED'`） |

> 树类型判定按 partNoField 取值收候选料号；零件页签绑成成品料号 → 成品被误判零件。

### 3.5 数据来源映射（tabType → V6 表.列，标准字段）

- **主件（$cp_view，`material_master`+`material_customer_map`）**：销售料号=`mm.material_no`(=hf_part_no)、客户料号名称=`mcm.customer_material_name`、客户产品编号=`mcm.customer_product_no`、报价货币=`mcm.quote_currency`、单位=`mm.standard_unit`、汇率=`mcm.exchange_rate`。
- **材质元素（$mc_view，`element_bom_item`）**：销售料号=`ebi.material_no`(=hf_part_no)、材质=`COALESCE(mr.name,mm2.material_name)` via `ebi.material_part_no`（**一号多材质：逐行取，禁子查询聚合；材质进行键**）、〔材质料号=`ebi.material_part_no`——**模板有这列才出成 `_材质料号` 可见列**；没有就只当 JOIN 键 + `ORDER BY` 键用〕、项次=`ebi.seq_no`、元素=`ebi.component_no`、组成含量=`ebi.content`、损耗率=`ebi.scrap_rate`、毛重=`ebi.composition_qty`、毛用量单位=`ebi.issue_unit`（⛔BOM发料单位，被毛重 `unit_source_field` 引用做 g→KG，勿改指价格单位）、**元素单价/货币 见 §3.6**、〔产出类型=**`material_bom_item.component_usage_type`** 经相关标量子查询按 `(material_no, component_no=ebi.material_part_no)` 取——🚨**禁绑 `ebi.component_usage_type`**，`element_bom_item` 上的同名列无写入路径、恒为空；详见 `报价侧.md §7.2.1`〕、〔材质占比=**`material_bom_item.material_ratio`** 同款跨表相关标量子查询，但**再加 `AND t.characteristic='RECIPE'`**（该列只有材质边写值）——`element_bom_item` 上**没有**这个列，绑 `ebi.*` 直接 SQL 报错；详见 `报价侧.md §7.2.2`〕。
- **零件·加工费（$jg_view，`unit_price` price_type='PROCESS'）**：hf_part_no=`up.finished_material_no`(按成品收窄本行)、料号=`up.code`(零件料号)、工序=`COALESCE(pm.process_name, up.operation_no)`（`process_master` via `process_no=operation_no`）、单价=`up.pricing_price`、单位=`up.unit`、项次=`up.seq_no`。
- **外购件（$wg_view，`material_bom_item` characteristic='OUTSOURCED'）**：销售料号=`mbi.material_no`(=hf_part_no)、〔组成件料号=`mbi.component_no`——**模板有这列才出可见列**，没有就只当取名 JOIN 键〕、组成件名称=`COALESCE(mm.material_name, mr.name)` via component_no、组成数量=`mbi.composition_qty`、组成单位=`mbi.issue_unit`。
- **BOM 树（树契约，见 §4.2）**：视图输出 `material_no`(子)/`parent_no`(父)，走 `costing_bom_tree_config`。
- **费用类（tabType 空，`unit_price` OTHER/PLATING）**：hf_part_no=`code`/`finished_material_no`、要素=`cost_type`、费用=`pricing_price`。

> 未在表内的字段 = 非标准 → 看「备注」；仍不明 → 问用户（业务白话）。

> 🚨 **同名列陷阱（2026-07-28 施耐德-1 实证，绑错一次）**：**列名对得上 ≠ 该列有数据**。同一个列名常在多张 V6 表上并存，但**只有一张有写入路径**——典型：`component_usage_type` 同时长在 `material_bom_item`（有 3 个写点）和 `element_bom_item`（**0 个写点，恒 NULL**）上。绑错**不报错、dry-run 过、expand-driver 也正常**，只是这列永远空。
> **落笔前两步必查**（见 §6 自检第 2 项）：① `grep -rn "<列名>" cpq-backend/src/main/java --include=*.java` 找到写它的 handler，**确认它写的目标表就是你要绑的那张** ② 查库非空率。



### 3.6 元素单价接价格策略（字段勾「接价格策略」时）

驱动视图加：
```sql
-- 报价侧：LEFT JOIN f_customer_element_price(:customerCode, :priceBaseDate) cep ON cep.element_code = ebi.component_no
-- 核价侧：LEFT JOIN f_customer_element_price('_GLOBAL_', :priceBaseDate) cep ON cep.element_code = ebi.component_no
```
输出（别名 = 字段名逐字、不加 `_`）：`cep.unit_price AS 单价, cep.currency AS 货币`（字段实际名叫「元素单价」就 `AS 元素单价`）。
硬约束：**必 LEFT JOIN**（禁 INNER，否则无价元素掉行）；**JOIN 键=元素符号 `component_no`**（非元素编号）；**禁 `COALESCE(...,0)`**（无价留 NULL 手填）；**不绑「计价单位」**（那是 BOM 发料单位）；`:priceBaseDate` 后端自动注入（=报价单创建日）。表函数 `f_customer_element_price(客户,基准日)` 已落库。

#### 3.6.1 🆕 三项显式绑定（2026-07-29 task-0729 · 强制）

勾了「接价格策略」的组件，**必须同时配齐组件级三项**，否则**保存会被后端拒绝**：

| 组件属性 | 配什么 | 罗克韦尔·材料成本 |
|---|---|---|
| `element_code_field` | 拿哪一列的值匹配元素编码 | `元素`（对应 `ebi.component_no AS _元素`） |
| `element_price_field` | 升版改哪一列 | `元素单价`（对应 `cep.unit_price AS 元素单价`） |
| `element_currency_field` | 货币列，可空 | 留空（该视图未输出货币列） |

**为什么要显式配、不靠解析 SQL**：升版要精确改「元素单价」这一个键、并靠「元素列」的值定位到 Ag/Cu 那些行。原方案是运行期正则解析视图 SQL 反推，**2026-07-29 推翻** —— `COALESCE(cep.unit_price,0) AS 单价`、`ON ebi.component_no = cep.element_code`（左右写反）、多条件 JOIN、带引号列名都会让正则抓空或抓错，**且失败全是静默的**（价格纹丝不动 / 改到别的字段，都不报错）。详见 `dev-docs/task-0729-客户价格调整策略和价格版本/需求说明.md §11.15.3`。

**三条纪律**：

1. 🔒 **别名固定 `cep`**（2026-07-29 定死）。取价函数别名一律写 `cep`，不得自创。
2. 🔑 **元素列的值必须是 `element_code`（`Ag`/`Cu`），不是 `element_name`（银/铜）、也不是 `element_no`（10001）**。元素主表 `element` 三个标识并存，而 `element_bom_item.component_no` / `element_price_strategy.element_code` / JOIN 条件用的都是中间那个。
3. ❓ **「元素列」必问用户，禁自己猜** —— 与既有必问 4 项（页签类型 / 料号列 / 料号名称列 / 行键）同一纪律。**配组件必问项由 4 项扩为 6 项**（+元素列 +元素单价列/货币列）。

## 4. 写 SQL 视图（按数据源 + 契约）

**通用**：`SELECT <料号列> AS hf_part_no, <业务列> AS _别名, ... FROM <V6表> [LEFT JOIN...] WHERE system_type=... AND is_current AND ...`。

- **报价·平铺**（多数页签）：`hf_part_no` + `customer_no=:customerCode`；多行序靠 `sort_field`（视图 ORDER BY 对 DRAFT 无效）。
- **报价·BOM 树**（tabType=BOM）：视图输出 `material_no`(子)/`parent_no`(父)，用 `:total_material_no`；建一条 `costing_bom_tree_config`(usage=QUOTE)。数据页签仍走平铺 `:customerCode`（禁用 `:total_material_no`）。
- **核价**：`:versionFilter` 宏 + `_GLOBAL_`（禁 `customer_no=:customerCode` 过滤）；树主轴 spine。

**硬约束（逐条自检）**：① `_` 前缀别名（hf_part_no/价格策略列除外）② 版本化表（`unit_price` 等）禁写进带 `is_current` 的 `LEFT JOIN ON` → 用相关标量子查询 ③ 中文列名需 ASCII 别名/预解析 ④ dry-run 禁表 ⑤ 缓存 key 含 componentId。

## 5. 建组件（交付）

> **标准交付 = JSON 文件，用户自行导入**（2026-07-24 定）。Agent 产出 `组件导入-<客户>.json`，不直接落用户目录；用户建目录后自己导。API 直接落库仅用于**配置/调试**（在已有目录上改/测）。

- **JSON 交付（标准）**：在**暂存目录**用 API 建好整套组件 → `GET /component-directories/{暂存id}/export` 得 bundle JSON → **删暂存目录** → 交付 JSON 文件；用户建自己的目录 → `POST /component-directories/{新id}/import/commit?conflictPolicy=RENAME`。
  - **bundle 已无损承载**：字段/公式/Excel 列 + `tabType`/`partNoField`/`partNameField`/`rowKeyFields`/`sortField`（行排序，2026-07-24 补齐）+ 每组件 SQL 视图（`sqlTemplate`/`requiredVariables`——**元素价格策略靠这两样带**）+ 依赖清单 + checksum。导入端 `ComponentImportService` 一并还原（含公式引用跨组件重映射）。
- **API 直接落库（配置/调试）**：`POST /auth/login` → `POST /component-directories{name,parentId}` → 每组件 `POST /components{name,directoryId,componentType,fields[],rowKeyFields[],tabType,partNoField,partNameField,sortField,status:ACTIVE}` → `POST /components/{cid}/sql-views{sqlViewName,sqlTemplate,scope:COMPONENT,requiredVariables}` → `PUT /components/{cid}/driver-view{sqlViewName}`。
- 更新：`PUT /components/{cid}`（整体替换，先 GET 保 formulas）；`PUT /components/{cid}/sql-views/{viewId}`。

## 6. 自检（宣告完成前必跑）

0. **字段与模板列 1:1**：组件 `fields[]` 逐条对得上 sheet 的列，**没有凭空新增的列**（尤其别为 `partNoField` 造料号列，§3.4）；视图 SELECT 输出列与字段一一对应，无没人绑的死列。
1. **金额/小计成对自检（R7）**：列出所有 `is_amount=true` 的字段，逐个确认 `is_subtotal` —— 可累加的（费用/金额/成本/加工费）必须 `true`，单位量纲的（单价/汇率/费率）必须 `false`。**漏勾** = 页签合计少算该列；**错勾单价** = 页签合计被单价之和污染。两种都不报错、只体现为金额不对，必须逐条看。
2. **每个绑定列必须有写入路径（"列名对得上 ≠ 有数据"）**：对视图里每个非平凡的 V6 列，逐个确认它**真的会被写**——
   ```bash
   # ① 谁写它？确认目标表 = 你绑的那张表（同名列常跨表并存，只有一张有写点）
   /usr/bin/grep -rn "<列名>" cpq-backend/src/main/java --include=*.java
   # ② 非空率：全 0 要追问是「数据没填」还是「根本没人写」——后者是配置错，必须改绑
   psql ... -c "SELECT count(*) FILTER (WHERE <列> IS NOT NULL) AS 有值, count(*) AS 总行 FROM <表>;"
   ```
   两者**结论不同处理**：无写点 → **改绑到真正有写点的那张表**（配置错）；有写点但全空 → 数据缺口，配置照旧 + 在交付说明里写明。
   ⚠️ 这类错**全程静默**：不报编译错、`$view` dry-run 照过、`expand-driver` 照常返回，只是该列永远空白。反面教材见 `报价侧.md §7.2.1`（`ebi.component_usage_type`）。
3. 每个 `$view` PUT 返 200（dry-run 过）。
4. `refresh-snapshot`（`POST /configure-product/quotations/{id}/refresh-snapshot`）后查 `quotation_line_component_data.snapshot_rows`：各页签行数对、料号/单价出数、树页签父子挂接、多行页签按 sort_field 正序。
5. 元素单价接价格策略：有价出数 / 无价返 NULL（不是 0、不掉行）/ 换没配策略客户行数不变。
6. 字段类型未改 → 不触发 AP-44；改了 ConfigureSnapshotService/ComponentService 等协议文件才跑 E2E。

## 7. 详细规则索引

| 主题 | 文档 |
|---|---|
| 总则 / 4 问工作流 / 料号铁律 | `1-总则与工作流.md`（完整正文 `docs/rule/报价模板生成规则.md §5.5/§6.-1`） |
| 组件与字段 | `2-组件与字段.md` |
| SQL 视图 / pending 改写坑 + 生效时间线 | `3-SQL视图.md`（`§3.6`） |
| 注释里能否写 `:命名参数`（能，框架已屏蔽） | `3-SQL视图.md`（`§3.7`） |
| 子件数据要进平铺页签（BOM 闭包取数） | `3-SQL视图.md`（`§3.8`） |
| 页签属性 / sort_field / 树 | `4-页签属性与树.md`（`§4.7/§5.5/§5.6/§7.0`） |
| 公式 / Excel 列 / AP-44 | `5-公式与Excel列.md` |
| 报价侧 delta / V6 映射 / 配方大全 | `报价侧.md`（`§5/§7`） |
| 核价侧 delta | `核价侧.md` |
| 元素单价接价格策略（完整 7 硬约束） | `docs/rule/报价模板生成规则.md §5.2.1` |
| 6 维度对照 / 常见坑 / V6 表 | `附录-速查.md` |
