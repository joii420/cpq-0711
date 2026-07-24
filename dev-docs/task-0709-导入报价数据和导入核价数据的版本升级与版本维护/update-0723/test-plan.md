# 测试用例设计文档 · update-0723 报价导入模板0723适配

> 状态：**仅用例设计，不写测试代码、不执行**（后端开发中）。
> 依据：`需求说明.md` §11 澄清记录 U0~U14（唯一口径） · `backtask.md` B1~B10 · `api.md` · `报价系统模板0723.xlsx`（已用 openpyxl 逐 sheet 读取真实数据，见 §3）。
> 端点：`POST /api/cpq/basic-data-import/v6/quote`（异步）+ `GET /api/cpq/basic-data-import/v6/{recordId}`（轮询）+ `POST /api/cpq/basic-data-import/v6/quote/create-quotation`。
> 前端**零改动**（已有独立 `frontend-regression.md` 覆盖 UI 回归，本文档只覆盖后端 API/DB 判定，两者互补不重复）。

---

## 1. 测试范围

**覆盖**：从「基础数据导入」到「创建报价单」全链路的后端行为 —— 料号三态推断（U1/U4）、落库动作矩阵（U2）、`material_type` 值域（U3）、工艺路线反填（U5）、两阶段全量校验 + 整单回滚（U6/U7/U8）、4 个忽略 sheet 纳入回滚（U9）、来料三表口径统一（U10）、模板配色语义（U11）、必填校验口径（U12）、pending/升版语义回归（U13）、范围边界（U14）。

**不覆盖**（明确边界，见 U14 / §10 依赖与风险）：
- 前端 UI/交互（`frontend-regression.md` 专项覆盖）
- 核价侧导入（`/basic-data-import/v6/pricing`，本次未改动，仅抽查不回归）
- `task-0721` 报价升版逻辑本体（回填升版机制已在主干，本次只验证「导入阶段不触发」这一半）
- 元素价格表新功能（Q01 下线后的替代方案，另案）

---

## 2. 风险点清单（测试设计过程中读码发现，非需求文档字面覆盖，需重点验证或与工程师对齐）

| # | 风险点 | 说明 | 关联用例 |
|---|--------|------|----------|
| R1 | **`material_master` 验收 SQL 目标表存疑** | 现役 `MaterialMasterRepository.upsertBatchNameType(rows, updatedBy, true, pendingQuotationId)` 在 `pendingQuotationId != null` 时（导入全程恒非空，见 `QuoteImportService.processImport` `ctx.pendingQuotationId = recordId`）**不写 `material_master`，改写 `pending_material_master_staging`**（键 = `quotation_id + material_no`）。backtask B6 验收原文「`W-1001 → material_master.material_type=外购件`」若在 create-quotation 之前（甚至之后、核价审批之前）直查 `material_master`，会查到空/旧值——这是**查询目标误判**，不是功能缺陷。本文档 U2/U3/U6 相关用例统一改查 `pending_material_master_staging`，并保留一条对 `material_master` 的显式反向确认（应仍为空/未变）。 | TC-U2-04~07、TC-U3-01~03、TC-U6-01 |
| R2 | **跨 sheet 仅凭名称互相引用同一物理件，可能各自发号产生重号** | `MaterialNoResolver.resolve()` 按名称查询时读的是 `material_master` **正式表**（`repo.findFirstByMaterialName`），而导入期写入进的是 `pending_material_master_staging`（尚未 promote）。若「物料BOM」某行只填名称、且该名称对应的料号只在同批次「组成件其他费用」等其它 sheet 里以「有料号」形式出现（尚未写入正式表），两处解析互不可见，各自的 `BatchState.nameToNo` 缓存也不跨 handler 共享 → **有产生同一物理件被分配两个不同料号的风险**。B2 `PartTypeInferenceService` 若只做「类型」推断、不做「名称→料号」的跨 handler 统一，此风险不会被自动规避。 | TC-U1-16、TC-U1-17 |
| R3 | **`clearPreviousPending` 未覆盖 `pending_material_master_staging`** | `QuoteImportService.PENDING_TABLES`（8 张：`unit_price/material_bom/material_bom_item/element_bom/element_bom_item/capacity/plating_scheme/material_customer_map`）不含 `pending_material_master_staging`。重导覆盖场景下，若新文件相比旧文件「减少」了某个只凭名称发号的料号，旧 staging 行会成孤儿残留（不影响正确性，但需确认是否符合预期）。 | TC-U13-05 |
| R4 | **发号「号段空洞」的真实来源** | U6 原文称 `quote_material_no_seq`「非事务性」，但该表是普通事务性 UPSERT 计数表（会随事务回滚）；PG 原生 `nextval('quote_customer_code_seq')`（`getOrAllocateCustomerCode` 内，仅客户首次发号触发）才是真正非事务性的组件。测试按「黑盒可观察现象」设计（回滚后重试是否跳号），不对内部实现方式做强断言，出现空洞按可接受处理，不判失败。 | TC-U6-09 |
| R5 | **`quotation_line_process` 反填 SQL 未按 `pending_quotation_id` 过滤** | `QuotationService:590` 反填 SQL 只按 `system_type/customer_no/material_no/characteristic/operation_no/is_current` 过滤，不看 `pending_quotation_id`。需确认 pending 阶段写入的 `material_bom_item` 是否 `is_current=true` 立即可见（若是，属于 V6「customer×material 全局共享，非按报价单隔离」的既有设计，见团队记忆；若否，反填会读不到刚导入的数据，需要工程师说明触发时机）。 | TC-U5-02 |

---

## 3. 测试环境与前置数据

### 3.1 测试客户与账号
- 使用**独立测试客户**（如 `客户名=测试客户-0723`，避免与共享 dev DB 上其它会话/E2E 常驻客户数据串扰；`customer.code` 需存在，导入取其作 `customer_no`）。
- 账号角色：`SALES_REP` / `SALES_MANAGER` / `SYSTEM_ADMIN` 任一（导入端点角色要求）。
- 每个用例组执行前**换新客户**或**清理该客户全部 QUOTE 系统数据**（`material_customer_map/material_bom*/unit_price/pending_material_master_staging` 等），避免用例间发号序列 / `is_current` 状态互相污染。

### 3.2 `报价系统模板0723.xlsx` 真实数据摘录（17 个 sheet，用例直接引用）

| Sheet | 关键样例行 |
|-------|-----------|
| 客户料号与宏丰料号的关系 | 客户产品编号=`PN0507945`，客户图号=`22-211-514-01`，**销售料号=`S-3120014539`** |
| 单重 | `S-3120014539`=42.9167 g/pcs；`S-80011`=12.55 g/pcs |
| **物料BOM**（新单表三态） | r2: `S-3120014539`/项次1/投入料号`991`/名称`H65带`；r3: 项次2/`992`/`AgNi11#-Ⅰ`；r4: 项次3/`S-80011`/`投入零件1`/组成数量`1`；r5: 销售料号=`S-80011`（嵌套自身BOM）/项次4/`992`/`AgNi11#-Ⅰ`；r6: 项次5/`W-1001`/`组成件1`/组成数量`2` |
| **物料与元素BOM**（材质权威sheet） | r2: `S-3120014539`/材质料号`991`/名称`H65带`/项次1/元素`Cu`/组成含量(空)/损耗率1.05/净用量0.62461/单位g/PCS；r3: 材质料号`992`/名称`AgNi11#-Ⅰ`/元素`Ag`/组成含量(空)/净用量0.05691/单位g/KPCS；r4: `S-80011`/材质料号`991`/名称`H65带`/元素`Cu`（其余全空） |
| 元素回收折扣 | `S-3120014539`/材质料号`992`/名称`AgNi11#-Ⅰ`/元素`Ag`/回收折扣20 |
| 来料固定加工费 | r2: `S-3120014539`/项次1/投入料号`991`/名称`H65带`/基准值0.04326/货币RMB/计价单位pcs；r3: 项次3/`992`/`AgNi11#-Ⅰ`/基准值0.03414 |
| 来料其他费用 | 样例文件为空（仅表头），需自行构造数据行 |
| 来料回收折扣 | 样例文件为空（仅表头），需自行构造数据行 |
| **自制加工费**（零件权威sheet） | r2: `S-3120014539`/项次1/投入料号`S-80011`/投入料号名称(空)/项次(第2次)1/工序编号`Z380`/工序名称(空)/值14 |
| 成品其他费用 | `S-3120014539` 4 行：材料管理费4.5%/外购件管理费4%/利润4%/税率13% |
| **组成件其他费用**（外购件权威sheet，已删3列） | r2: `S-3120014539`/项次1/组成件料号`W-1001`/组成件名称`组成件1`/供应商编号1/供应商名称`供应商1`/项次(第2次)1/要素名称`材料费`/值55/货币RMB/计价单位PCS |
| 组装加工费 | r2: `S-3120014539`/项次1/组装工序`焊接`/0.08 RMB/PCS；r3: 项次2/`铆接`/12 |
| 电镀费用 | `S-80011`/电镀加工费1.396/电镀材料费1.55/RMB/PCS |
| 电镀方案（忽略） | `A0001`方案2条：Ni（元素单价来源长江有色网）、Au |
| 来料年降/组装加工费年降/年降系数（忽略） | 样例文件均为空（仅表头） |

**类型推断黄金三角**（本文档反复引用）：`991`/`992` = 材质(RECIPE)；`S-80011` = 零件(ASSEMBLY，命中自制加工费)；`W-1001` = 外购件(OUTSOURCED，命中组成件其他费用)；`S-3120014539` = 主件(=零件)。

### 3.3 判定用数据库表速查

| 表 | 关键列 | 备注 |
|----|--------|------|
| `import_record` | `import_status`(PROCESSING/SUCCESS/FAILED)、`total_rows`、`success_rows`、`metadata`(jsonb)、`quotation_id` | API JSON 字段名 `status`，DB 列名 `import_status` |
| `material_bom` | `system_type,customer_no,material_no,bom_type,characteristic,bom_version,pending_quotation_id,is_current` | |
| `material_bom_item` | `system_type,customer_no,material_no,component_no,seq_no,item_seq,component_usage_type,composition_qty,issue_unit,scrap_rate,defect_rate,operation_no,rough_weight,net_weight,weight_unit,characteristic,bom_version,pending_quotation_id,is_current` | 新增列：`composition_qty`（U0#3） |
| `element_bom` / `element_bom_item` | （本次不改，仅纳入回滚校验） | |
| `material_recipe` | `code,symbol,name,status` | 材质主档，只读查询，导入不写 |
| `material_master` | `material_no,material_name,material_type,production_no,...` | **导入期不直接写**（见 R1），核价审批通过后由 `promoteStaging` 落地 |
| `pending_material_master_staging` | `quotation_id,material_no,material_name,material_type,...` | 导入期零件/外购件的真实落点（键=quotation_id+material_no，此刻 quotation_id=pendingQuotationId） |
| `unit_price` | `system_type,customer_no,price_type,cost_type,code,finished_material_no,operation_no,supplier_no,item_seq,pricing_price,currency,unit,version_no,pending_quotation_id,is_current` | |
| `material_customer_map` | `system_type,material_no,customer_no,customer_product_no,production_no,pending_quotation_id` | 占号表 |
| `quote_customer_code` / `quote_material_no_seq` | `code` / `customer_code,year_month,last_serial` | 发号基础设施 |
| `quotation_line_process` | `id,line_item_id,process_no` | |
| `capacity` / `plating_scheme` | （本次不改，仅纳入回滚校验） | Q14/Q16 对应表 |

### 3.4 判定 SQL 模板

**SQL-A（整单回滚 · 零写库校验集）**：导入前后各查一次，两次结果须完全相同（0 变化）：
```sql
SELECT
  (SELECT count(*) FROM unit_price WHERE customer_no=:cc) AS unit_price,
  (SELECT count(*) FROM material_bom WHERE customer_no=:cc) AS material_bom,
  (SELECT count(*) FROM material_bom_item WHERE customer_no=:cc) AS material_bom_item,
  (SELECT count(*) FROM element_bom WHERE customer_no=:cc) AS element_bom,
  (SELECT count(*) FROM element_bom_item WHERE customer_no=:cc) AS element_bom_item,
  (SELECT count(*) FROM capacity WHERE customer_no=:cc) AS capacity,
  (SELECT count(*) FROM plating_scheme WHERE customer_no=:cc) AS plating_scheme,
  (SELECT count(*) FROM material_customer_map WHERE customer_no=:cc) AS mcm,
  (SELECT count(*) FROM pending_material_master_staging) AS staging_all;
```

**SQL-B（API 响应判定）**：`GET /api/cpq/basic-data-import/v6/{recordId}` → `data.status` ∈ `{SUCCESS, FAILED}`（**不再出现 `PARTIAL`**）；`FAILED` 时 `data.metadata.sheetResults[].errors[]` 汇总全量错误。

---

## 4. 测试用例

### G0 · U0 模板结构差异全清单

| 用例ID | 关联需求点 | 前置条件 | 输入(sheet/料号/值) | 操作步骤 | 预期结果 | 判定方式 |
|---|---|---|---|---|---|---|
| TC-U0-01 | U0#1 / B1 | 测试客户已就绪 | 原始模板（无「元素单价」sheet） | 上传原始模板导入 | 导入无任何报错提及「元素单价」/Q01；`sheetResults` 不含 `sheetName="元素单价"` | API：`sheetResults` 遍历无该 sheetName；SQL：`SELECT count(*) FROM unit_price WHERE customer_no=:cc AND price_type='ELEMENT'` = 0（本次导入未产生该维度数据） |
| TC-U0-02 | U0#2 / B3 | 同上 | 原始模板（无「组成件BOM」sheet，物料BOM 单表承载三态） | 上传原始模板导入 | 导入 `SUCCESS`，无「缺组成件BOM sheet」类报错 | API：`status=SUCCESS`；`sheetResults` 不含 `sheetName="组成件BOM"` |
| TC-U0-03 | U0#3 / B3 | 同上 | 物料BOM r4(`S-80011`,组成数量=1)、r6(`W-1001`,组成数量=2) | 导入后查子行 | `composition_qty` 分别落值 1、2 | SQL：`SELECT component_no,composition_qty FROM material_bom_item WHERE customer_no=:cc AND material_no='S-3120014539' ORDER BY seq_no` → `S-80011`→1，`W-1001`→2 |
| TC-U0-04 | U0#4 / B5.2 | 同上 | 组成件其他费用 r2（第2个「项次」=1，无工序编号/组装工序/要素编号列） | 导入后查 unit_price | `item_seq=1`（非 null，**验证 `getIntNth("项次",2)` 而非旧 `,3`**）；`operation_no` 恒 NULL；无撞键错误 | SQL：`SELECT item_seq,operation_no FROM unit_price WHERE customer_no=:cc AND price_type='COMPONENT_OTHER' AND code='W-1001' AND finished_material_no='S-3120014539'` → item_seq=1, operation_no IS NULL |
| TC-U0-05 | U0#5 / B3.4 | 同上 | 物料BOM 无「组成单位」列 | 导入后查子行 issue_unit | RECIPE 行(`991`/`992`)取「重量单位」列值；ASSEMBLY/OUTSOURCED 行(`S-80011`/`W-1001`)兜底 `PCS` | SQL：`SELECT component_no,characteristic,issue_unit FROM material_bom_item WHERE customer_no=:cc AND material_no='S-3120014539'` → `S-80011`/`W-1001` issue_unit='PCS' |
| TC-U0-06 | U0#3 下游消费 / B3 | 已创建报价单（见 TC-U0-01 后走 create-quotation） | 同 TC-U0-03 | 打开报价单编辑页「物料BOM」Tab | 「数量」列（`$view.input_qty`）渲染 1（`S-80011`行）、2（`W-1001`行），非空白/非「加载中」 | UI 截图 + Network：编辑页 driver-expand 接口返回值含对应 composition_qty |

### G1 · U1/U4 料号类型推断（权威sheet命中 · 料号列 vs 名称列 · 冲突 · 库内兜底）

| 用例ID | 关联需求点 | 前置条件 | 输入(sheet/料号/值) | 操作步骤 | 预期结果 | 判定方式 |
|---|---|---|---|---|---|---|
| TC-U1-01 | U1步骤1(材质·料号列) | 客户就绪 | 物料BOM r2 投入料号=`991` | 导入 | `991` 定型 RECIPE | SQL：`material_bom_item.characteristic='RECIPE' WHERE component_no='991'` |
| TC-U1-02 | U1步骤1(材质·名称列) | 客户就绪 | 变体：物料BOM 新增 r7 `S-3120014539`/项次6/投入料号(留空)/投入料号名称=`H65带` | 导入 | 判定 RECIPE（命中「物料与元素BOM」材质料号名称列 `H65带`） | SQL：新行 `characteristic='RECIPE'`，`component_no`=按名反查得到的材质码`991` |
| TC-U1-03 | U1步骤1(零件·料号列) | 客户就绪 | 物料BOM r4 投入料号=`S-80011` | 导入 | 定型 ASSEMBLY（命中自制加工费投入料号列） | SQL：`material_bom_item.characteristic='ASSEMBLY' WHERE component_no='S-80011'` |
| TC-U1-04 | U1步骤1(零件·名称列) | 客户就绪 | 变体：①自制加工费 r2 投入料号名称补填=`投入零件1`；②物料BOM 新增 r7 `S-3120014539`/项次6/投入料号(留空)/投入料号名称=`投入零件1` | 导入 | 新行定型 ASSEMBLY（命中自制加工费投入料号名称列）；`component_no` 应解析为 `S-80011`（见 R2 风险，若解析出另一个新发号料号则判失败） | SQL：新行 `characteristic='ASSEMBLY'` 且 `component_no='S-80011'`（与已有 r4 相同料号，不重号） |
| TC-U1-05 | U1步骤1(外购件·料号列) | 客户就绪 | 物料BOM r6 投入料号=`W-1001` | 导入 | 定型 OUTSOURCED（命中组成件其他费用组成件料号列） | SQL：`material_bom_item.characteristic='OUTSOURCED' WHERE component_no='W-1001'` |
| TC-U1-06 | U1步骤1(外购件·名称列) | 客户就绪 | 变体：物料BOM 新增 r7 `S-3120014539`/项次6/投入料号(留空)/投入料号名称=`组成件1` | 导入 | 新行定型 OUTSOURCED（命中组成件其他费用组成件名称列）；`component_no` 应解析为 `W-1001`（同 R2 风险） | SQL：新行 `characteristic='OUTSOURCED'` 且 `component_no='W-1001'`（与 r6 同料号） |
| TC-U1-07 | U1主件=零件 | 客户就绪 | 客户料号与宏丰料号的关系/成品其他费用/组装加工费 的销售料号均=`S-3120014539` | 导入 | `S-3120014539` 作为主件按零件类型登记（`pending_material_master_staging.material_type`='零件'） | SQL：`SELECT material_type FROM pending_material_master_staging WHERE quotation_id=:pending AND material_no='S-3120014539'` = '零件' |
| TC-U1-08 | U1嵌套BOM | 客户就绪 | 物料BOM r5：销售料号=`S-80011`（自身也是主件），投入料号=`992` | 导入 | `S-80011` 的自身子BOM里 `992` 仍正确定型 RECIPE（不受"S-80011自身是ASSEMBLY类型主件"影响） | SQL：`SELECT characteristic FROM material_bom_item WHERE customer_no=:cc AND material_no='S-80011' AND component_no='992'` = 'RECIPE' |
| TC-U1-09 | U6洞①类型冲突(料号) / B2§3.2 | 客户就绪 | 变体：组成件其他费用新增一行组成件料号=`992`（与物料与元素BOM材质料号`992`撞） | 导入 | 报错「料号「992」类型冲突：同时命中 材质(物料与元素BOM) 与 外购件(组成件其他费用)」（api.md §3.2 示例原句），`status=FAILED`，整单回滚 | API：`sheetResults[].errors[].message` 含上述文案；SQL-A 全表 count 导入前后不变 |
| TC-U1-10 | U6洞①类型冲突(名称) | 客户就绪 | 变体：组成件其他费用新增一行组成件名称=`H65带`（与材质料号名称`H65带`撞，料号留空走名称匹配） | 导入 | 同样报错类型冲突，整单回滚 | 同上 SQL-A |
| TC-U1-11 | U1步骤2 material_recipe(按code) | 库内预置：`material_recipe` 存在 `code='993'`(不在本次三个权威sheet任何一处出现) | 变体：物料BOM 新增一行投入料号=`993`（名称留空/或填一个三处都未命中的名称） | 导入 | 库内兜底命中 `material_recipe.code`，判定 RECIPE | SQL：新行 `characteristic='RECIPE'`，`component_no='993'` |
| TC-U1-12 | U1步骤3 material_master(按material_no) | 库内预置：`material_master` 存在 `material_no='M-9001'`，`material_type='零件'`，且 `M-9001` 不出现在三个权威sheet | 变体：物料BOM 新增一行投入料号=`M-9001` | 导入 | 库内兜底命中 `material_master.material_type='零件'`→ASSEMBLY | SQL：新行 `characteristic='ASSEMBLY'` |
| TC-U1-13 | U1步骤4 兜底零件 | 库内预置：`material_recipe`/`material_master` 均不存在 `M-9999` | 变体：物料BOM 新增一行投入料号=`M-9999`，三权威sheet均未命中 | 导入 | 三 sheet 未命中 + 两库未命中 → 兜底 ASSEMBLY(零件) | SQL：新行 `characteristic='ASSEMBLY'` |
| TC-U1-14 | U1步骤2 material_recipe(按name) | 库内预置：`material_recipe` 存在 `name='测试材质甲'`（code 与之无关，不出现在权威sheet） | 变体：物料BOM 新增一行投入料号(空)/名称=`测试材质甲` | 导入 | 按 `material_recipe.name` 命中，判定 RECIPE | SQL：新行 `characteristic='RECIPE'` |
| TC-U1-15 | U1步骤3 material_master(按material_name) | 库内预置：`material_master` 存在 `material_name='测试外购件甲'`，`material_type='外购件'` | 变体：物料BOM 新增一行投入料号(空)/名称=`测试外购件甲` | 导入 | 按 `material_master.material_name` 命中，判定 OUTSOURCED | SQL：新行 `characteristic='OUTSOURCED'` |
| TC-U1-16 | R2风险·跨sheet同名一致性(零件) | 客户就绪，清库 | 同 TC-U1-04 变体 | 导入 | **专项断言**：新行 `component_no` 必须等于已有 r4 的 `component_no='S-80011'`，不产生第二个料号 | SQL：`SELECT count(DISTINCT component_no) FROM material_bom_item WHERE customer_no=:cc AND material_no='S-3120014539' AND seq_no IN (3,6)` = 1（若=2 视为缺陷，按 R2 记录 Bug） |
| TC-U1-17 | R2风险·跨sheet同名一致性(外购件) | 客户就绪，清库 | 同 TC-U1-06 变体 | 导入 | 新行 `component_no` 必须等于 r6 的 `component_no='W-1001'` | 同上模式；若不一致按 R2 记录 Bug |

### G2 · U2 定型后落库动作矩阵

| 用例ID | 关联需求点 | 前置条件 | 输入(sheet/料号/值) | 操作步骤 | 预期结果 | 判定方式 |
|---|---|---|---|---|---|---|
| TC-U2-01 | U2材质·有料号·库无 | `material_recipe` 中不存在 `code='999'` | 变体：物料BOM 新增一行投入料号=`999`（三sheet不含，走类型推断落到"材质"分支的前提是：**注意** — 若三sheet+两库均未命中，兜底是"零件"而非材质，故本用例需构造"三sheet命中材质权威sheet"但 `material_recipe` 查无的场景：物料与元素BOM 新增一行 材质料号=`999`,名称=`不存在材质`，物料BOM 对应行也用`999`） | 导入 | 报错「未找到材质」，`status=FAILED`，整单回滚 | API：错误信息含「未找到材质」；SQL-A 零写库 |
| TC-U2-02 | U2材质·只填名称·库无 | 同上 | 变体：物料与元素BOM 新增一行材质料号(空)/名称=`不存在材质乙`；物料BOM 对应投入料号名称=`不存在材质乙` | 导入 | 按名查 `material_recipe` 无 → 报错「未找到材质」 | 同上 |
| TC-U2-03 | U2材质·有料号·库有(正例) | 同 TC-U1-01 | 物料BOM r2 投入料号=`991` | 导入 | 正常落库：`component_no='991'`（原始码），**不发号、不登记 `material_master`/`pending_material_master_staging`** | SQL：`SELECT count(*) FROM pending_material_master_staging WHERE quotation_id=:pending AND material_no='991'` = 0 |
| TC-U2-04 | U2零件·有料号·库内无 | `pending_material_master_staging`/`material_master` 均不存在 `S-80011` | 物料BOM r4 投入料号=`S-80011` | 导入 | 直接用该料号落库（新建登记），**不另发号**（不生成 `XXXX-YYMMNNNNNN`） | SQL：`component_no='S-80011'`（原样，非新号）；`SELECT material_type FROM pending_material_master_staging WHERE quotation_id=:pending AND material_no='S-80011'` = '零件'（R1：不要查 `material_master`） |
| TC-U2-05 | U2外购件·有料号·库内无 | 同上，`W-1001` 不存在 | 物料BOM r6 投入料号=`W-1001` | 导入 | 同上，`component_no='W-1001'`，不发号 | SQL：`pending_material_master_staging.material_type`='外购件' WHERE material_no='W-1001' |
| TC-U2-06 | U2零件·只填名称·库无→发号 | `material_master` 中不存在名称`全新零件甲` | 变体：物料BOM 新增一行投入料号(空)/名称=`全新零件甲`，且该名称不出现在自制加工费(即三sheet也未命中→兜底ASSEMBLY，且需求语境是"只填名称"分支专测；若三sheet命中零件权威sheet更贴合U2字面，建议改为：自制加工费也新增同名行只填名称`全新零件甲`使其命中零件权威sheet按名称) | 导入 | 按名查 `material_master`(正式表)无 → **发号** `XXXX-YYMMNNNNNN`格式（4位客户码-YYMM-6位流水）+ 登记 | SQL：新行 `component_no` 正则匹配 `^\d{4}-\d{10}$`；`pending_material_master_staging.material_type`='零件' |
| TC-U2-07 | U2外购件·只填名称·库无→发号 | 同上，名称=`全新外购件甲` | 变体：组成件其他费用+物料BOM 均新增只填名称=`全新外购件甲`的对应行 | 导入 | 发号 + 登记 `material_type`='外购件' | 同上格式校验 + type='外购件' |
| TC-U2-08 | U2零件·有料号·库内已存在(同客户) | 先导入一次使 `S-80011` 归属客户C，占号表已有记录 | 二次导入（重导覆盖或新一轮草稿）仍用 `S-80011` | 导入 | 复用已有料号，不重复登记，不报跨客户错 | SQL：`material_customer_map` 中 `S-80011` 仍唯一一行，`customer_no`=C |
| TC-U2-09 | U2跨客户串号(边界，关联U14) | `S-80011` 已属客户C(占号表已存在 customer_no=C 且非 pending 状态) | 客户D 导入同料号`S-80011` | 导入 | 抛 `CrossCustomerQuoteNoException`，Phase1 或占号预检拦截 → 报错「报价料号跨客户串号」，整单回滚 | API：错误信息含「跨客户串号」；SQL-A 零写库（客户D） |

### G3 · U3 material_type 值域改造

| 用例ID | 关联需求点 | 前置条件 | 输入 | 操作步骤 | 预期结果 | 判定方式 |
|---|---|---|---|---|---|---|
| TC-U3-01 | U3值域 | 客户就绪，`S-80011` 库内无 | 物料BOM r4(`S-80011`=ASSEMBLY) | 导入 | `material_type`='零件'（**不是**旧值"组成件"） | SQL：`pending_material_master_staging.material_type` WHERE material_no='S-80011' = '零件'（R1：不查 material_master） |
| TC-U3-02 | U3值域 | 客户就绪，`W-1001` 库内无 | 物料BOM r6(`W-1001`=OUTSOURCED) | 导入 | `material_type`='外购件' | 同上，值='外购件' |
| TC-U3-03 | U3材质不进material_master | 客户就绪 | 物料BOM r2/r3(`991`/`992`=RECIPE) | 导入 | `991`/`992` 不出现在 `material_master` 也不出现在 `pending_material_master_staging` | SQL：`SELECT count(*) FROM pending_material_master_staging WHERE material_no IN ('991','992')` = 0；`SELECT count(*) FROM material_master WHERE material_no IN ('991','992')` = 0 |
| TC-U3-04 | U3存量不迁移(回归) | 库内预置：`material_master` 已有一行 `material_no='OLD-001'`，`material_type='组成件'`（旧值，本次导入不涉及该料号） | 正常导入(不含`OLD-001`) | 导入 | `OLD-001` 的 `material_type` 仍为「组成件」，未被本次导入触碰/覆盖 | SQL：导入前后 `SELECT material_type FROM material_master WHERE material_no='OLD-001'` 值不变 |

### G4 · U5 工艺路线反填 + issue_unit（与 U0#5 呼应）

| 用例ID | 关联需求点 | 前置条件 | 输入 | 操作步骤 | 预期结果 | 判定方式 |
|---|---|---|---|---|---|---|
| TC-U5-01 | U5反填 / B4 | 客户就绪 | 自制加工费 r2(`S-3120014539`,`S-80011`,工序编号`Z380`) | 导入 | `material_bom_item` 对应零件子行 `operation_no='Z380'` | SQL：`SELECT operation_no FROM material_bom_item WHERE customer_no=:cc AND material_no='S-3120014539' AND component_no='S-80011'` = 'Z380' |
| TC-U5-02 | U5下游消费(quotation_line_process) / B4验收 | 已走 create-quotation 建单，且报价单主件明细行 `product_part_no_snapshot='S-3120014539'` | 同上 | 「从基础数据导入」加入报价单后查工序表 | `quotation_line_process` 存在 `process_no='Z380'` 的行 | SQL：`SELECT process_no FROM quotation_line_process WHERE line_item_id=:lid` 含 'Z380'；若查无，按 R5 记录风险/追问触发时机 |
| TC-U5-03 | U5未命中不影响其他字段 | 客户就绪 | 物料BOM 新增一行零件类型料号，且该料号**不**出现在自制加工费投入料号列 | 导入 | 该子行 `operation_no` 为 NULL，其余字段（component_no/characteristic/composition_qty）正常落值，不报错 | SQL：`operation_no IS NULL`，其余列非空 |
| TC-U5-04 | U0#5 issue_unit(材质行) | 客户就绪 | 物料BOM r2/r3(RECIPE)，重量单位列有值(如'g') | 导入 | RECIPE 行 `issue_unit` = 「重量单位」列原值 | SQL：`issue_unit` = weight_unit 列值 |
| TC-U5-05 | U0#5 issue_unit(组成件/外购件行) | 客户就绪 | 物料BOM r4/r6(ASSEMBLY/OUTSOURCED) | 导入 | `issue_unit` 兜底 `'PCS'` | SQL：`issue_unit='PCS'` WHERE component_no IN ('S-80011','W-1001') |
| TC-U5-06 | U5多工序取值稳定性(边界) | 客户就绪 | 变体：自制加工费新增第二行，同 `(销售料号=S-3120014539, 投入料号=S-80011)` 但工序编号=`Z381` | 导入 | 不报错，`operation_no` 取稳定的一条（首条或项次最小，与文档日志标注一致），重复导入结果不漂移 | SQL：两次导入(相同输入)得到相同 `operation_no` 值 |

### G5 · U6/U7 全量回滚 + 两阶段事务

| 用例ID | 关联需求点 | 前置条件 | 输入 | 操作步骤 | 预期结果 | 判定方式 |
|---|---|---|---|---|---|---|
| TC-U6-01 | U6/U7零写库 | 客户就绪，SQL-A 先查一次基线 | 同 TC-U1-09（类型冲突文件） | 导入 | `status=FAILED`；**所有** SQL-A 表 count 导入前后完全一致（含 `pending_material_master_staging` 全局计数不因本次客户变化） | SQL-A 前后 diff = 0 |
| TC-U6-02 | U6材质缺库回滚 | 同上 | 同 TC-U2-01/02（材质缺库文件） | 导入 | `FAILED`，零写库 | 同上 SQL-A |
| TC-U6-03 | U6/U8全量收集(不遇错即停) / B8验收 | 客户就绪 | 变体：单文件同时含 1 处类型冲突（TC-U1-09手法）+ 1 处材质缺库（TC-U2-01手法） | 导入 | 返回 **2 条**错误（不是 1 条就中断），`status=FAILED` | API：`sheetResults[].errors` 汇总数=2，分别对应两处 sheet |
| TC-U6-04 | U6Phase2异常整体回滚 | 需可控注入 Phase2 阶段异常（如构造导致唯一键冲突的极端行，若无法从 Excel 层面构造，本用例标记为**白盒观察项**，需工程师配合单测覆盖，API 层面仅验证："万一 Phase2 抛错"→`FAILED`+零残留） | 待定(工程师提供注入点或按现有唯一键规则构造) | 导入 | `status=FAILED`，无部分写入残留，无 `PARTIAL` | SQL-A + API `status` 二态检查 |
| TC-U6-05 | U6/U7正常全过 | 客户就绪 | 原始模板 | 导入 | Phase1 全通过 → Phase2 全部 commit，`status=SUCCESS`，各表按预期行数写入 | API `status=SUCCESS`；`sheetResults[].writes` 计数 > 0 |
| TC-U6-06 | U6事务边界(handler join外层) / B7 | 客户就绪 | 同 TC-U6-03(混合错误文件) | 导入后检查 `material_customer_map` | 即便某些行在类型冲突前已被「预扫」访问过，占号表也**不残留**任何本次导入产生的行（因随外层事务整体回滚） | SQL：`SELECT count(*) FROM material_customer_map WHERE customer_no=:cc` 导入前后不变 |
| TC-U6-07 | U6触发路径·关键键列为空 | 客户就绪 | 变体：物料BOM 某行「销售料号」留空 | 导入 | Phase1 报错「销售料号为空」，整单回滚（验证 B8 必填校验同样走 U6 回滚路径，非独立豁免） | API 错误含"为空"；SQL-A 零写库 |
| TC-U6-08 | U7 status 枚举回归 | 客户就绪 | 分别跑 1 正常文件 + 1 错误文件 | 两次导入分别轮询到终态 | `status` 只出现 `SUCCESS` 或 `FAILED`，**任何情况下都不出现 `PARTIAL`** | API：两次终态断言 |
| TC-U6-09 | R4号段空洞(观察项，不判失败) | 客户就绪，为新客户(未曾发过号) | 先跑一次会失败回滚的文件(如 TC-U1-09)，紧接着跑一次正常文件(需要发号的只填名称行) | 对比两次发号结果 | 允许号段不连续(空洞)；**只要求**：不重号、不阻塞、后续导入功能正常 | SQL：两次生成的料号各自唯一、格式合法；不作为通过/失败判据的是"是否连续" |

### G6 · U8 性能

| 用例ID | 关联需求点 | 前置条件 | 输入 | 操作步骤 | 预期结果 | 判定方式 |
|---|---|---|---|---|---|---|
| TC-U8-01 | U8基线 | 客户就绪 | 原始模板(约30行有效数据) | 点击导入起计时，轮询到 `import_record.import_status='SUCCESS'` | 端到端 < 2s | 客户端计时(HTTP请求发出→轮询首次拿到SUCCESS的时间差)；后端日志 `[v6import] QUOTE TOTAL elapsed=...ms` 佐证 |
| TC-U8-02 | U8百行边界 | 客户就绪 | 构造放大文件：物料BOM/物料与元素BOM/自制加工费等按比例复制到累计约百行(不同料号避免撞键) | 同上 | 仍 < 2s | 同上 |
| TC-U8-03 | U8无N+1 | 客户就绪，DB查询日志/Hibernate statistics开启 | 同 TC-U8-02 | 导入过程中采集 SQL 执行次数 | `material_recipe`/`material_master` 库内兜底查询各 ≤ 1~2 次（批量 IN/全表），不随行数线性增长；`VersionedV6Writer.profile()` summary 无异常放大 | Hibernate SQL count 断言 + profile 日志核对 |
| TC-U8-04 | U8千行观察项 | 客户就绪 | 构造千行级文件(远超百行) | 导入 | 不强制 < 2s，但须最终 `SUCCESS`，不超时报错、不 OOM | 记录实际耗时，标注为观察项非阻断 |

### G7 · U9 4 个忽略 sheet 纳入整单回滚

| 用例ID | 关联需求点 | 前置条件 | 输入 | 操作步骤 | 预期结果 | 判定方式 |
|---|---|---|---|---|---|---|
| TC-U9-01 | U9规则不变(正例) | 客户就绪 | 电镀方案(A0001两行)/来料年降/组装加工费年降/年降系数(均为样例空表头或补1行合法数据) | 导入 | 4个sheet按现状规则正常写入(与本次改动前行为一致) | SQL：`plating_scheme` 等对应表按预期行数写入 |
| TC-U9-02 | U9纳入整单回滚 | 客户就绪 | 4个忽略sheet数据本身合法，但物料BOM等其它sheet触发 TC-U1-09 类型冲突 | 导入 | 整单回滚，4个忽略sheet对应表(`plating_scheme`等)也**零写入**(不因"规则不改"而被单独豁免事务) | SQL-A 含 `plating_scheme`/`capacity` 等表，导入前后不变 |
| TC-U9-03 | U9忽略sheet自身异常(观察项) | 客户就绪 | 电镀方案构造一行必填列为空(如"方案编号"留空，若现状Q16有必填校验) | 导入 | 若现状规则本就校验该列 → 同样报错纳入整单回滚；若现状无此校验 → 该行按现状规则处理(不强制新增校验)，标注为观察项 | API 错误清单 + SQL-A；结果依现状 Q16 实现而定，不预设 |

### G8 · U10 来料三表口径统一（Q06/Q07/Q09）

| 用例ID | 关联需求点 | 前置条件 | 输入 | 操作步骤 | 预期结果 | 判定方式 |
|---|---|---|---|---|---|---|
| TC-U10-01 | U10料号列(正例·固定加工费) | 客户就绪 | 来料固定加工费 r2(`991`,`H65带`,基准值0.04326) | 导入 | 直接用料号 `991`(现状回归) | SQL：`unit_price` WHERE price_type对应固定加工费 AND code='991' |
| TC-U10-02 | U10名称反查(固定加工费) | 客户就绪，样例来料固定加工费原表有料号，本用例用变体 | 变体：来料固定加工费新增一行投入料号(空)/名称=`H65带` | 导入 | 名称反查定型材质 → 查 `material_recipe.name='H65带'` → 取得 `code='991'` 落库 | SQL：新行 `code='991'` |
| TC-U10-03 | U10材质定型+名称查无→报错 | 客户就绪 | 变体：来料固定加工费新增一行投入料号(空)/名称=`不存在材质丙`(不在物料与元素BOM权威集，三sheet未命中则走库内兜底最终判材质需构造：先在物料与元素BOM添加材质料号(空)/名称=不存在材质丙，使其命中材质权威名称集，但material_recipe查无) | 导入 | 报错「未找到材质」，整单回滚 | API 错误含「未找到材质」；SQL-A 零写库 |
| TC-U10-04 | U10来料其他费用名称反查 | 客户就绪(样例文件该sheet为空，需自建数据) | 变体：来料其他费用新增一行(`S-3120014539`,项次1,投入料号=空,名称=`投入零件1`,要素名称=`加工费`,值=10) | 导入 | 名称反查定型零件 → material_master(正式表)/发号 | SQL：`unit_price` 对应行 `code` 与 TC-U1-04 得到的 `S-80011` 一致(跨用例一致性，若单独跑则验证发号格式合法) |
| TC-U10-05 | U10来料回收折扣名称反查 | 客户就绪(样例为空，需自建) | 变体：来料回收折扣新增一行(`S-3120014539`,项次1,投入料号=空,名称=`H65带`,回收折扣=5) | 导入 | 材质定型，按名反查 `991` | SQL：对应表 `code`/`material_no`='991' |
| TC-U10-06 | U10三表口径一致性 | 客户就绪 | 同一料号`991`分别出现在 来料固定加工费(有料号)/来料其他费用(只填名称`H65带`)/来料回收折扣(只填名称`H65带`) | 导入 | 三处解析结果一致，均落 `991`，不因sheet不同产生分歧 | SQL：三张表对应行 `code`/`material_no` 均='991' |

### G9 · U11 模板配色语义

| 用例ID | 关联需求点 | 前置条件 | 输入 | 操作步骤 | 预期结果 | 判定方式 |
|---|---|---|---|---|---|---|
| TC-U11-01 | U11红=关键键列(强制) | 客户就绪 | 物料与元素BOM「项次」「元素」列(红色)留空 | 导入 | 报错(关键键列必填)，整单回滚 | API 错误含"为空"；SQL-A 零写库 |
| TC-U11-02 | U11红但业务值列(不强制，推翻字面) | 客户就绪 | 物料与元素BOM r2/r3 原样(「组成含量（%）」标红但样例本身全空) | 导入 | 不报错，正常导入(U12口径) | API `status=SUCCESS`，无「组成含量」相关错误 |
| TC-U11-03 | U11蓝=必填其一 | 客户就绪 | 物料BOM 新增一行「投入料号」「投入料号名称」均留空 | 导入 | 报错「料号与名称均为空」，整单回滚 | API 错误含该文案 |
| TC-U11-04 | U11蓝色两者都填→优先料号 | 客户就绪 | 变体：物料BOM 某行投入料号=`991`，投入料号名称故意填一个不匹配的名称(如`张冠李戴`) | 导入 | 不因名称不匹配报错，直接采用料号`991`(resolve逻辑：料号非空直接返回，不比对名称一致性) | SQL：该行 `component_no='991'`，导入 `SUCCESS` |
| TC-U11-05 | U11绿=选填 | 客户就绪 | 物料BOM「材料毛重」「损耗率」等绿/黄色列全部留空 | 导入 | 不报错，对应列落 NULL | SQL：列值 IS NULL，`status=SUCCESS` |

### G10 · U12 必填校验口径（关键键列强制 / 业务值列不强制）

| 用例ID | 关联需求点 | 前置条件 | 输入 | 操作步骤 | 预期结果 | 判定方式 |
|---|---|---|---|---|---|---|
| TC-U12-01 | U12关键键列-销售料号 | 客户就绪 | 抽样3个sheet(物料BOM/自制加工费/组装加工费)分别留空「销售料号」 | 逐个导入(每次1个错误) | 均报错「销售料号为空」，整单回滚 | API 错误文案；SQL-A 零写库(逐次验证) |
| TC-U12-02 | U12关键键列-项次 | 客户就绪 | 物料BOM 某行「项次」留空 | 导入 | 报错「项次为空」 | 同上 |
| TC-U12-03 | U12关键键列-料号类(蓝色必填其一) | 客户就绪 | 同 TC-U11-03 | 导入 | 报错「料号与名称均为空」 | 同上(与U11-03同一用例，交叉引用) |
| TC-U12-04 | U12业务值列不强制(样例原状) | 客户就绪 | 物料与元素BOM r2/r3(「组成含量（%）」全空，仅填净用量) | 导入 | 不强制报错，正常导入；元素用量按净用量计算(组成含量与净用量二者能算出即可) | API `status=SUCCESS` |
| TC-U12-05 | U12业务值列不强制(其它黄色列) | 客户就绪 | 物料BOM「材料毛重」「材料净重」「损耗率」「不良率」全空 | 导入 | 不强制报错 | API `status=SUCCESS`，对应列 NULL |
| TC-U12-06 | U12边界-组成含量与净用量都空 | 客户就绪 | 变体：物料与元素BOM 新增一行「组成含量（%）」「净用量」均留空(其余关键键列齐全) | 导入 | **【技术总监已裁决 2026-07-23：不报错】** —— 组成含量与净用量均为业务值列，按 U12「业务值列不强制」口径，Phase1 只校验关键键列，两者都空**不报错**、正常导入(下游元素用量算不出只渲染为空，非导入校验职责)。若业务后续要求"两者必填其一"，另立**数据质量校验**，不塞入本次导入 Phase1。 | API：`status=SUCCESS`，无「组成含量/净用量」相关错误。若实现报错→视为**过度校验缺陷**，判 FAIL。 |

### G11 · U13 升版语义回归（pending 机制）

| 用例ID | 关联需求点 | 前置条件 | 输入 | 操作步骤 | 预期结果 | 判定方式 |
|---|---|---|---|---|---|---|
| TC-U13-01 | U13pending归属 | 客户就绪 | 原始模板 | 导入(不做create-quotation) | 8张PENDING_TABLES + `material_customer_map` 中新增行 `pending_quotation_id = importRecordId` | SQL：`SELECT count(*) FROM material_bom WHERE pending_quotation_id=:recordId` > 0 |
| TC-U13-02 | U13不触发真实升版 | 同上，未create-quotation | 同上 | 检查是否有"真实报价单"产生 | `import_record.quotation_id` 为 NULL；不存在对应 `quotation` 表记录；不触发任何版本号递增行为 | SQL：`SELECT quotation_id FROM import_record WHERE id=:recordId` IS NULL |
| TC-U13-03 | U13过户(repointPendingOwnership) | 承接 TC-U13-01 | 调用 create-quotation | 建单后检查 | 8张表中原 `pending_quotation_id=importRecordId` 的行全部改为 `=真实quotationId` | SQL：`SELECT count(*) FROM material_bom WHERE pending_quotation_id=:recordId` = 0；`WHERE pending_quotation_id=:realQuotationId` = 原值 |
| TC-U13-04 | U13重导覆盖(clearPreviousPending) | 承接 TC-U13-01(未create-quotation) | 同一 importRecordId 重新上传变体文件(减少一个组件) | 重新导入 | 先清空该 pending 归属旧数据，再写新数据；不重复/不残留旧行 | SQL：8张表中该pending归属下的行数 = 新文件应产生的行数(非新旧叠加) |
| TC-U13-05 | R3风险·staging孤儿行(观察项) | 承接 TC-U13-04场景，旧文件含一个只凭名称发号的料号，新文件删除了该行 | 重导覆盖 | 检查 `pending_material_master_staging` | 记录该表是否残留旧料号的孤儿行(`clearPreviousPending` 未覆盖此表)；若残留，评估是否影响后续 promote 正确性，作为**观察项**记录，不强制判失败 | SQL：`SELECT * FROM pending_material_master_staging WHERE quotation_id=:recordId` 对比新文件预期料号集 |

### G12 · U14 范围边界 + 综合边界/异常

| 用例ID | 关联需求点 | 前置条件 | 输入 | 操作步骤 | 预期结果 | 判定方式 |
|---|---|---|---|---|---|---|
| TC-U14-01 | U14范围边界·CFG-前缀拒导(回归) | 客户就绪 | 物料BOM「销售料号」改为 `CFG-TEST001` | 导入 | 报错「禁止导入系统生成料号(CFG- 前缀)」，整单回滚 | API 错误文案；SQL-A 零写库 |
| TC-U14-02 | U14范围边界·跨客户串号(同TC-U2-09交叉引用) | 见 TC-U2-09 | 同上 | 同上 | 同上 | 同上 |
| TC-U14-03 | U14前端零改动回归(接口层面确认) | 客户就绪 | 同 TC-U6-03(混合错误文件) | 轮询响应体结构 | `metadata.sheetResults` 结构与现状兼容(sheetName/totalRows/successRows/failedRows/writes/errors[])，前端现有渲染无需改动即可展示全量错误 | API 响应体字段逐一比对 api.md §3.2 结构 |
| TC-U14-04 | U14边界·空文件(仅表头) | 客户就绪 | 全部17个sheet仅表头无数据行 | 导入 | `totalRows=0`，`status=SUCCESS`(空批次不算错误)，或按现状约定处理(需与工程师确认空sheet是否报错) | API：`status` 结果 + `totalRows` |
| TC-U14-05 | U14边界·特殊字符(全角空格/序号前缀) | 客户就绪 | 物料BOM「产出料号类型」列值含前导序号如 `1.银点类` / `产出料号类型：　　银点类／非银点类`(全角空格+顿号，见样例r5备注列) | 导入 | `component_usage_type` 正确剥离前导序号只留标签(`labelOnly` 现状回归)，不因全角空格报错 | SQL：`component_usage_type` = '银点类'(不含"1." 前缀) |
| TC-U14-06 | U14边界·超长文本 | 客户就绪 | 「投入料号名称」列填入 300+ 字符字符串 | 导入 | 不阻断导入(若DB列有长度限制则报错并整单回滚，非部分截断静默保存) | SQL：写入值长度 = 原始值长度(未截断)，或报错回滚(二选一，取决于列定义，测试记录实际行为) |
| TC-U14-07 | U14边界·最大数量(单料号多子件) | 客户就绪 | 物料BOM 同一销售料号下投入料号扩至50+条(不同料号避免撞键) | 导入 | 正常导入且仍 < 2s，无N+1放大 | SQL：子行数=50+；计时 < 2s |
| TC-U14-08 | U14范围边界·核价侧不受影响(回归) | 核价侧已有正常导入基线 | 核价「从基础数据导入」端点 `/basic-data-import/v6/pricing` 上传核价现状合法文件 | 导入 | 正常 SUCCESS/FAILED 二态，不受本次报价侧改动影响 | API `status` 正常返回，无报价侧相关报错混入 |

---

## 5. 覆盖矩阵

| 需求点 | 细节描述 | 是否覆盖 | 用例ID |
|---|---|:---:|---|
| U0#1 | 元素单价sheet删除，Q01下线 | ✅ | TC-U0-01 |
| U0#2 | 组成件BOM删除，合并进物料BOM | ✅ | TC-U0-02（+G1全组） |
| U0#3 | 物料BOM改列名+新增组成数量→composition_qty | ✅ | TC-U0-03、TC-U0-06(下游消费) |
| U0#4 | 组成件其他费用删3列，item_seq错位修正(getIntNth 3→2) | ✅ | TC-U0-04 |
| U0#5 | 组成单位列消失，issue_unit兜底 | ✅ | TC-U0-05、TC-U5-04、TC-U5-05 |
| U1 | 料号类型推断·判定顺序步骤1(权威sheet·料号列) | ✅ | TC-U1-01/03/05/07 |
| U1 | 料号类型推断·判定顺序步骤1(权威sheet·名称列) | ✅ | TC-U1-02/04/06 |
| U1 | 判定顺序步骤2(material_recipe·code/name) | ✅ | TC-U1-11、TC-U1-14 |
| U1 | 判定顺序步骤3(material_master·material_no/material_name) | ✅ | TC-U1-12、TC-U1-15 |
| U1 | 判定顺序步骤4(兜底零件) | ✅ | TC-U1-13 |
| U1 | 嵌套BOM(主件自身也是子件销售料号) | ✅ | TC-U1-08 |
| U1/U6洞① | 类型冲突(料号/名称) | ✅ | TC-U1-09/10 |
| U1(风险) | 跨sheet同名一致性(未在需求文档字面出现，属深度风险点) | ✅(风险专项) | TC-U1-16/17（R2） |
| U2 | 材质·有料号·库无→报错 | ✅ | TC-U2-01 |
| U2 | 材质·只填名称·库无→报错 | ✅ | TC-U2-02 |
| U2 | 材质·有料号·库有→正常(不发号不登记) | ✅ | TC-U2-03 |
| U2 | 零件/外购件·有料号·库内无→直接落库不发号 | ✅ | TC-U2-04/05 |
| U2 | 零件/外购件·只填名称·库无→发号XXXX-YYMMNNNNNN | ✅ | TC-U2-06/07 |
| U2 | 有料号·库内已存在(同客户复用/跨客户拦截) | ✅ | TC-U2-08/09 |
| U3 | material_type值域{零件,外购件} | ✅ | TC-U3-01/02 |
| U3 | 材质不进material_master | ✅ | TC-U3-03 |
| U3 | 存量"组成件"不迁移 | ✅ | TC-U3-04 |
| U4 | 只填名称行定型(名称参与权威sheet匹配+库内兜底) | ✅ | 并入G1全组(TC-U1-02/04/06/14/15) |
| U5 | 自制加工费工序反填operation_no | ✅ | TC-U5-01 |
| U5 | 下游quotation_line_process有对应工序 | ✅(含风险标注R5) | TC-U5-02 |
| U5 | 未命中不影响其他字段 | ✅ | TC-U5-03 |
| U5 | 多工序取值稳定性 | ✅ | TC-U5-06 |
| U6洞① | 类型冲突→整单回滚 | ✅ | TC-U1-09/10、TC-U6-01 |
| U6洞② | 库内兜底顺序 | ✅ | TC-U1-11~15 |
| U6 | 两阶段：Phase1零写库 | ✅ | TC-U6-01/02/07 |
| U6 | Phase2外层事务，13 handler join，全成功commit/失败rollback | ✅ | TC-U6-04/05/06 |
| U6 | 号段空洞副作用(可接受) | ✅(观察项) | TC-U6-09（R4） |
| U7 | 有错全部回滚，全部通过才导入 | ✅ | TC-U6-01~08 |
| U7 | 不再有PARTIAL状态 | ✅ | TC-U6-08 |
| U8 | 百行内端到端<2s | ✅ | TC-U8-01/02 |
| U8 | 无N+1 | ✅ | TC-U8-03 |
| U8 | 千行级不强制但不卡死 | ✅(观察项) | TC-U8-04 |
| U9 | 4个忽略sheet规则不变 | ✅ | TC-U9-01 |
| U9 | 4个忽略sheet纳入整单回滚 | ✅ | TC-U9-02 |
| U9 | 忽略sheet自身异常处理(观察项) | ✅(观察项) | TC-U9-03 |
| U10 | 来料三表(Q06/Q07/Q09)口径统一·料号列 | ✅ | TC-U10-01 |
| U10 | 来料三表·名称反查 | ✅ | TC-U10-02/04/05 |
| U10 | 材质定型+名称查无→报错 | ✅ | TC-U10-03 |
| U10 | 三表口径一致性 | ✅ | TC-U10-06 |
| U11 | 红=必填(关键键列) | ✅ | TC-U11-01 |
| U11 | 红但业务值列不强制(与U12交叉) | ✅ | TC-U11-02 |
| U11 | 蓝=必填其一 | ✅ | TC-U11-03、TC-U11-04(都填时优先料号) |
| U11 | 绿=选填 | ✅ | TC-U11-05 |
| U12 | 关键键列强制(销售料号/项次/料号类) | ✅ | TC-U12-01/02/03 |
| U12 | 业务值列不强制(含组成含量%标红可空典型例外) | ✅ | TC-U12-04/05 |
| U12 | 组成含量与净用量都空的边界(技术总监已裁决:不报错) | ✅ | TC-U12-06 |
| U13 | 导入走pending，不触发真实升版 | ✅ | TC-U13-01/02 |
| U13 | pending过户为真实quotationId | ✅ | TC-U13-03 |
| U13 | 重导覆盖语义 | ✅ | TC-U13-04 |
| U13(风险) | staging表未纳入clearPreviousPending(观察项) | ✅(风险专项) | TC-U13-05（R3） |
| U14 | 只改导入→创建报价单链路 | ✅ | TC-U14-08(核价侧不受影响回归) |
| U14 | 前端无代码改动(接口兼容性侧面验证) | ✅ | TC-U14-03 |
| U14 | 其余模块功能不变 | ✅ | TC-U14-08 |
| 边界 | CFG-前缀拒导 | ✅ | TC-U14-01 |
| 边界 | 跨客户串号 | ✅ | TC-U2-09、TC-U14-02 |
| 边界 | 空文件 | ✅ | TC-U14-04 |
| 边界 | 特殊字符 | ✅ | TC-U14-05 |
| 边界 | 超长文本 | ✅ | TC-U14-06 |
| 边界 | 最大数量 | ✅ | TC-U14-07 |

**遗留待澄清项**（非用例遗漏，是需求本身的模糊边界）：
1. ~~TC-U12-06：组成含量与净用量都为空是否报错~~ —— **已由技术总监 2026-07-23 裁决：不报错**（U12「业务值列不强制」直接推论；如需"必填其一"另立数据质量校验）。已闭合。
2. TC-U6-04：Phase2 阶段"意外异常"目前只能通过白盒注入/单测构造，黑盒 Excel 层面较难自然触发，建议开发阶段配单元测试直接覆盖 `writeAll` 的 rollback 分支。
3. R2/R5：跨 sheet 名称一致性、`quotation_line_process` 反填的 `is_current`/`pending_quotation_id` 可见性时机，backtask.md 未明确设计，建议工程师实现前对齐，避免验收时才发现契约空白。

---

## 6. 回归测试清单（本次改动后必须重测的关联场景）

| 场景 | 触发原因 | 关联用例 |
|---|---|---|
| 核价侧「从基础数据导入」 | 报价侧 handler/事务模型改动是否误伤共享代码路径(如 `MaterialMasterRepository`/`MaterialNoResolver` 若被核价侧复用) | TC-U14-08 |
| 报价单编辑页渲染(物料BOM/来料四表/组成件其他费用等Tab) | `MaterialBomMergeHandler` 重构 + `material_bom_item` 新增列，AP-53/AP-38/AP-31 已知反模式 | TC-U0-06、`frontend-regression.md` 回归4 |
| `task-0721` 报价升版逻辑(回填升版) | U13 pending 机制是本次导入的下游依赖，需衔接测试 | TC-U13-01~05 |
| 选配/3D配置器等复用 `QuoteMaterialNoAllocator`/`MaterialNoResolver` 的路径 | 事务传播 REQUIRES_NEW→MANDATORY 改动的影响面，需确认非导入路径(如3D配置器)未被误改传播类型 | 不在本次范围内，建议 B10 grep 确认 `mintAndRegister(customerNo, yyMm)` 无 pendingQuotationId 的旧签名调用点未受影响 |
| 发号序列相关的其它报价功能 | R4 号段空洞现象是否影响其它依赖 `quote_customer_code`/`quote_material_no_seq` 的功能 | TC-U6-09 |
