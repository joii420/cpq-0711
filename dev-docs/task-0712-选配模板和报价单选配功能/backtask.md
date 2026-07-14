# 后端任务文档 — 选配模板和报价单选配功能（task-0712）

> 依据 `需求说明.md`（D1–D16 / §4.5 / §4.6）、`api.md`、《报价系统Excel导入落库方案 V3.4》(`docs/table/`)。
> 技术栈 Quarkus + Hibernate Panache + PostgreSQL + Flyway。**在隔离 worktree 分支开发**。

## 0. 范围 / 前置 / 纪律
- **涉及**：选配模板（复用）、选配落库改造（核心）、已有产品列表（新）、3D 模型配置（新表）、组合工艺候选收敛。
- **禁改**：报价单其他功能、三大核心模块架构基线、`field_type`（不触发 AP-44）、渲染管线。
- **N+1 硬禁**（验收项）：列表/候选/3D/指纹全部批量或 JOIN。
- **Flyway**：新迁移用**当前最大号之后的新号**（现网 ≥ V327，起 **V330+** 避让并发分支）；**不改已应用迁移的号/名**；不手工 `psql -f`，靠 dev 启动 `migrate-at-start`。
- **落库方案为准**：任何列名/固定值以《报价系统Excel导入落库方案 V3.4》对应 Sheet 章节为准，代码里已有 `Q03/Q04/Q10` 等 handler 可参考对齐。

---

## B1. 选配模板管理后端 🟢（现有，核对+补齐）
现有 `com.cpq.seltemplate`（`SelTemplate/Item/ItemValue` + `SelTemplateService` + `EffectiveTemplateService` + `SelTemplateResource` + `SelParamTypeResource`）已可 CRUD + effective + candidates。
- **任务**：按 `api.md §1` 核对；补"停用/启用"若列表工具栏需要独立轻端点（否则 upsert status 即可）。
- **验收**：`GET /sel-templates`、`/effective?customerNo=` 返 §1 结构；无模板客户 `hasTemplate=false`；`__DEFAULT__` 兜底命中 `usedDefault=true`。

---

## B2. 选配落库改造 🟡🔴（**本任务核心**，D16 / §4.6）

**目标**：`ConfigureProductService.configure/resolvePart` 由"渲染取巧落库"改造为**等价导入的完整落库**——写头表 + 完整 BOM/元素/工序/组合工艺 + material_master，使选配料号与导入料号等价、投标可用。

### B2.1 落库逐表逐列（严格照落库方案）

**① 料号身份 → `material_master`**（§落库方案 §2/§3 同步）
| 列 | 值 |
|---|---|
| material_no | 发号得到的报价料号 |
| material_type | 材质符号 / `COMPOSITE`（组合父件）|
| material_recipe_id | 材质配方 id（SIMPLE 子件）|
| unit_weight | 单重 |
| config_fingerprint | **NULL**（客户维度发号，避免撞生产侧全局唯一索引 `uq_material_master_fingerprint`）|

**② 材质/物料BOM → `material_bom`(头) + `material_bom_item`(子)**（§3）
- 头 `material_bom`：`system_type=QUOTE, bom_type=MATERIAL, customer_no, material_no`（**本次新增头表写入**，原缺）。
- 子 `material_bom_item`：`system_type=QUOTE, customer_no, material_no, seq_no, component_no, component_usage_type, rough_weight, net_weight, weight_unit, scrap_rate, defect_rate`。
- ⚠️ 原实现只写"自指行 + characteristic NULL"取巧 → 改为按方案 §3 语义写主件+组件行。SIMPLE 单材质料号的 BOM 结构由 architect 与业务确认（自制成品的物料构成）。

**③ 元素 → `element_bom`(头) + `element_bom_item`(子)**（§4）
- 头 `element_bom`：`system_type=QUOTE, bom_type=MATERIAL, material_no, characteristic`（**本次新增头表写入**）。
- 子 `element_bom_item`：`material_no, characteristic(默认2000,同主件不同组成则+1), seq_no, component_no(元素码), content(含量), scrap_rate, composition_qty(毛用量), issue_unit(净用量单位非空则替换毛用量单位), base_qty(净用量)`。
- 保持"元素镜像视图可渲染"的同时补齐头表 + 完整列（原只写 system_type/customer_no/hf_part_no/material_no/characteristic/seq_no/component_no/content）。
- ⚠️ **characteristic 区分（架构风险4，B2 详设必列）**：现役 `insertElementBomV6` 固定 `'2000'`；D11 支持"一成品多材质料号"后，元素需**按组成真正区分 characteristic**（不同材质料号 → 不同 characteristic 桶），否则多材质料号元素挤同桶 → 元素 Tab 错。本次每个"材质料号"(子件)是独立 material_no，其元素挂各自 material_no 的 characteristic，天然分桶；若同一 material_no 出现多组成才需 +1，实现时确认口径。

**④ 工序 → `unit_price`（自制加工费）**（§10）
- 分组键：`system_type=QUOTE, price_type=PROCESS, cost_type=自制加工费, customer_no, code, finished_material_no`。
- 行列：`operation_no(工序码), seq_no, pricing_price(固定值), cost_ratio(比例), currency, unit`；`code` 取值按 §10「投入料号取值规则」（有投入料号取之；否则成品料号兜底 + 去重 fail-fast）。
- 版本化 `version_no`（`VersionedWriter`，覆盖当前客户为新版本）。现状方向对，补齐 pricing_price/cost_ratio 列语义。

**⑤ 组合工艺 → `capacity`（组装加工费）**（§14）
- 列：`material_no(父COMBO料号), seq_no(项次), process_no(=工序编号=`process_master.process_no`，非 def code), fixed_cost(固定费,可留NULL), currency(空→CNY兜底), **`capacity_unit`(计量单位，⚠️ 实际列名非 `unit`)**, default_defect_rate(拒收率/不良率)`。
- `process_name` 读 `process_master.process_name`（不再读 `composite_process_def`，见 B6）。ASSEMBLY 4 行 currency/unit/defectRate 均空 → 落库兜底。

### B2.2 指纹匹配 + 发号（选配专属，D16-B）
- `SalesFingerprintCalculator`：token **按 paramTypeCode 字母序**（ELEMENT/MATERIAL/PROCESS）→ SIMPLE `v1|CUST=..|ELE=码:含量(排序,去尾零)|MAT=配方码|PRC=工序码(排序)`；COMPOSITE `v1|CUST=..|COMBO=子件:数量(排序)|CPROC=组合工艺defCode(排序)`。码值禁含 `| = , : ∅`（fail-fast）。⚠️ 顺序以现役代码 `computeSimple` 的 `sorted(Comparator.comparing(paramTypeCode))` 为准（非 MAT 在前）。
- 查 `sel_part_signature`(`UNIQUE(customer_no,structure_version,config_fingerprint)`)：命中 → 复用 `quote_part_no`、**在任何落库前 return**（幂等）；未命中 → `quoteAllocator.mintAndRegister(customerNo,yyMm)` 铸号 + `insertOrReadExisting` 登记（ON CONFLICT DO NOTHING 处理并发败者回读先赢号）。

### B2.3 SIMPLE / COMPOSITE 判定（D11/D12，✅ 架构决策1-A 定稿）
- 数量合计 `Σqty = Σ parts[].quantity`。**判定后端按 Σqty 兜底裁决**（不盲信前端 productType，前后端同口径）：`Σqty==1 → SIMPLE`；`Σqty≥2 → COMPOSITE`。
- **单行 qty≥2 = 父 COMPOSITE + 1 个去重子件 `composition_qty=qty`**（不展开成多子件）：`childHfPartNos=[该行 resolvePart 料号]`、`childQtys=[qty]` → `COMBO=P:qty` → 父发 COMPOSITE 料号 + `capacity` 组装 + ASSEMBLY BOM `composition_qty=qty`。与 `computeComposite` 现役口径 + 导入 §3 ASSEMBLY 同形（选展开多子件会产 `P:1,P:1` 不一致 → 错价）。
- **放开 `validateRequest` 两个闸门（本决策唯一新增改点，务必一并改）**：① COMPOSITE 下限从 `parts.size()>=2` 改为 `Σqty>=2`（`parts.size()` 上限 ≤8 保留，指去重子件行数）；SIMPLE 改为 `Σqty==1`；② 组合工艺 `participatingPartIndexes.size()>=2` 硬校验放开，允许"单去重子件 qty≥2"。否则单行 qty2 选组合工艺会 400。

### B2.4 事务不变量
指纹登记 + ①~⑤ 完整落库 + line item 必须**同一事务（REQUIRED，禁 REQUIRES_NEW）**；保证"签名可见 ⇔ 数据可见"，防并发败者复用先赢号时数据未提交 → Tab 空。

### B2.5 验收（strict）
- 选配一个 SIMPLE 料号后，DB 中该 `material_no` 在 `material_master`✓ `material_bom`(头)✓ `material_bom_item`✓ `element_bom`(头)✓ `element_bom_item`✓ `unit_price(PROCESS/自制加工费)`✓ **六处齐全**（原实现头表为空 → 必须补上，这是核心验收点）。
- COMPOSITE：父 `material_master(COMPOSITE)` + `capacity(组装加工费)` 行 + 各子件完整落库。
- 幂等：同客户同配置重复提交，`sel_part_signature` 不新增行、`quote_part_no` 复用、各表不重复累加。
- 指纹分隔符/自制费两空重复 → 400 fail-fast。

---

## B3. 已有产品列表 🔴（新，`api.md §2.1`）
- 新端点 `GET /api/cpq/quotations/{quotationId}/existing-products`，服务端从 quotation 取 `customer_no`，查 `material_customer_map` 该客户产品 + 4 过滤（`customer_product_no`/`material_no`/`customer_material_name`/规格）分页。
- 🔴 **必须过滤选配发号占位行（P0，F005）**：`QuoteMaterialNoAllocator.mintAndRegister` 每次选配发号会往 `material_customer_map` 插 `customer_product_no=NULL, production_no=NULL` 的 QUOTE 占位组件行。已有产品列表**必须 `WHERE customer_product_no IS NOT NULL`**（只列真实客户产品），否则选配副作用污染"从已有产品添加"列表。
- **3D + 规格一次性 LEFT JOIN（✅ 架构决策3-A）**：`material_customer_map` LEFT JOIN `model_config`(`subject_type='SALES_PART' AND subject_key=material_no AND is_current`, 带 `has3d`/`thumbnailUrl`) LEFT JOIN `material_master`(`material_no`, 带规格)。两 JOIN 均命中唯一索引，**单条 SQL、禁逐行查**（N+1 验收）。
- **规格 `spec` 取值（定稿）**：`spec = COALESCE(NULLIF(material_master.specification,''), material_master.dimension)`（specification 语义优先，测试期 dimension 兜显示）；§2.1 的 `spec` 过滤对同一表达式模糊匹配。
- **验收**：F12 Network 一次列表请求，无逐行 3D/规格 请求；4 过滤命中正确；分页 total 正确；占位行（customer_product_no NULL）不出现。

---

## B4. 加入报价单链路核对 🟢（复用，D8）
- "从已有产品添加"选中料号 → 复用现有"从基础数据加入行"落行链路（`buildLineItemFromTemplate` + 现有导入行端点），不新建落库端点。
- **任务**：核对现有批量加入端点能接受"料号列表 + 报价单已绑模板"入参；若签名不符补薄适配层。
- **验收**：加入后报价单各视图按当前客户模板正常渲染（沿用渲染管线，不改）。

---

## B5. 3D 模型配置 🔴（新表 + 新端点，D4/D5/D15）

### B5.1 新迁移（V330+）
```sql
CREATE TABLE model_config (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  subject_type  VARCHAR(20) NOT NULL,          -- SALES_PART / MATERIAL
  subject_key   VARCHAR(64) NOT NULL,          -- 销售料号 / 材质配方码
  version       INTEGER     NOT NULL DEFAULT 1,
  is_current    BOOLEAN     NOT NULL DEFAULT TRUE,
  label         VARCHAR(255),
  glb_url       TEXT        NOT NULL,
  thumbnail_url TEXT,
  mesh_count    INTEGER, vertices INTEGER, size_kb INTEGER,
  metadata      JSONB DEFAULT '{}',
  uploaded_by   UUID, uploaded_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT chk_mc_subject CHECK (subject_type IN ('SALES_PART','MATERIAL')),
  UNIQUE (subject_type, subject_key, version)
);
CREATE UNIQUE INDEX uq_model_config_current ON model_config(subject_type, subject_key) WHERE is_current;
CREATE INDEX idx_model_config_lookup ON model_config(subject_type, subject_key, is_current);

CREATE TABLE model_config_file (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  model_config_id UUID NOT NULL REFERENCES model_config(id) ON DELETE CASCADE,
  file_role       VARCHAR(20) NOT NULL,        -- GLB / THUMBNAIL / OTHER
  file_url        TEXT NOT NULL, file_size_bytes BIGINT, md5_hash VARCHAR(64),
  uploaded_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT chk_mcf_role CHECK (file_role IN ('GLB','THUMBNAIL','OTHER'))
);
```
- **`uq_model_config_current` 部分唯一索引**保证同 subject 仅一条 is_current（并发安全）。

### B5.2 端点（`api.md §4`）
- `GET /model-configs`（分页 + subjectType + keyword；材质 Tab 关联材质库带 subjectLabel/材质名）。
- `POST /model-configs`（multipart：glbFile/thumbnailFile + 字段；version=max+1；setCurrent 时事务内降级旧 current + 置新）。文件落现有文件存储（复用 part-model 存储方式）。
- `GET /model-configs/versions`、`PUT /model-configs/{id}/set-current`（事务内 FLIP，非 DELETE）、`DELETE /model-configs/{id}`。
- `GET /model-configs/current?subjectType=&subjectKey=`（运行端带出；无 → data=null）。
- **缓存**：若加进程级缓存，`set-current`/`upload`/`delete` 后失效对应 key（D15 不得陈旧）。

### B5.3 验收
- 材质、料号各上传 2 版，`set-current` 切换后 `uq_model_config_current` 不冲突、旧版降级；`GET current` 取新版本。
- 缩略图缺失/自动截图占位（前端），后端接受 thumbnailFile 可空。

---

## B6. 组合工艺候选收敛 🟡（D13 / R4，✅ 架构决策2-2A 定稿）
- 口径：候选取 **`process_master WHERE process_category='ASSEMBLY'`**（现网实值，4 行：总装配/部件装配/螺栓连接/焊接装配；**无需补种子**）。标识锚点统一 **`process_master.process_no`**。
- **选配三处解绑 `composite_process_def`**（表保留给 v0.4，不删）：
  1. 候选端点 `GET /api/cpq/composite-processes` → 改读 `process_master WHERE process_category='ASSEMBLY'`；DTO `{ code=process_no, name=process_name, currency=standard_currency, unit=standard_unit, defectRate=default_defect_rate }`（去掉 icon/paramSchema）。
  2. `insertCompositeProcessCapacityV6` → `process_no=选中的 process_master.process_no`、`process_name` 读 `process_master`；补 §14 全列（fixed_cost 可 NULL / `capacity_unit`（非 unit）/ default_defect_rate / currency 空兜 CNY）。
  3. `insertCompositeProcessesPerQuote` 的 `CompositeProcessDef.findByCodeOrThrow` → 改校验 `process_no` ∈ `process_master`(ASSEMBLY)；`quotation_line_composite_process.def_code` 语义变"工序编号"。
- **放弃 param_schema 参数化**（业务已确认 2026-07-14）：现役 6 条带参数 def 退出候选，改用 ASSEMBLY 4 条通用工序（只选不录参）。
- **五处标识对齐清单（PR 自检硬项，AP-44 精神）**：候选端点 / 前端选择值 / 指纹 CPROC / `capacity.process_no` / `quotation_line_composite_process.def_code` —— 全部 = `process_master.process_no`。漏一处 = 组合工艺静默错配。
- 指纹：`computeComposite` 的 `compositeProcessCodes` 入参由 def code 改为 `process_no`（算法不变，值域变；存量全测试数据不兼容，backtask 注明"CPROC 值=工序编号"）。
- **验收**：组合工艺候选来自 `process_master`(ASSEMBLY)；COMPOSITE 落 `capacity.process_no` = 候选 process_no = 指纹 CPROC，五处一致。

---

## 7. 修改后强制自检（每次改动结束前）
1. `touch` 一个 java 触发 Quarkus 重启（5-7s）；`curl -s -o /dev/null -w '%{http_code}' --noproxy '*' http://localhost:8081/api/cpq/components` → 401（应用+鉴权正常，非 500）。
2. Flyway：`SELECT version,success FROM flyway_schema_history WHERE version='330'` → success=t。
3. 选配落库改造属渲染链路协议改动 → **跑 E2E**（`quotation-flow.spec.ts`；组合走 `composite-product-flow.spec.ts`），`'加载中' final=0`。
4. 落库完整性 SQL 验收（B2.5 六处齐全 + 幂等 + capacity）。
5. N+1：F12 / SQL 日志确认无逐行查询。
6. 完成宣告必须含"已自检"证据行（TS/编译/端点码/Flyway/E2E/落库 SQL）。

## 8. 交付物
- 迁移 `V330+__model_config.sql`（+ 如需 process_category 种子）。
- `ConfigureProductService` 落库改造（B2）+ 单测（落库六处齐全断言 / 幂等 / fail-fast）。
- `model_config` 实体/服务/资源 + `ExistingProductResource` + 单测。
- RECORD.md 追加开发记录。
