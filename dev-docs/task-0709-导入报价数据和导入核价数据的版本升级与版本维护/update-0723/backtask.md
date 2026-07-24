# 后端任务文档 · update-0723 报价导入模板 0723 适配

> 关联需求：`update-0723/需求说明.md`（§11 澄清记录 U0~U14 为唯一口径）
> 关联接口：`update-0723/api.md`
> 依赖前置：`task-0721-报价升版逻辑`（pending 机制已在主干，本次不重做）

---

## 0. 背景与总纲

新导入模板 `报价系统模板0723.xlsx` 相对旧 `V3.xlsx` 有 5 处结构差异（U0）。核心变化是：**删除 `组成件BOM` sheet，物料BOM 单表承载材质/零件/外购件三态**；**删除 `元素单价` sheet**；并要求**全量校验 + 整单回滚 + 端到端 < 2s**。

**只改「从基础数据导入 → 创建报价单」链路**；不动核价侧、不动升版逻辑、前端零改动。

**现役导入入口**：
- 端点 `POST /api/cpq/basic-data-import/v6/quote`（`BasicDataImportV6Resource#importQuote`，异步 + 轮询）
- 调度 `QuoteImportService#processImport`（`ByteArrayInputStream` → 逐 sheet handler）
- 现役 handler：`quote/` 目录 Q01/Q02/Q04~Q11/Q13~Q19 + `MaterialBomMergeHandler`

**⚠️ 本次改动触及导入链路事务模型与 `MaterialBomMergeHandler` 核心逻辑，属高风险区。必读**：
`docs/方案制定前必读.md`、`docs/反模式.md` AP-53（V6 表使用规则）、记忆 `v6-child-multiversion-iscurrent-audit-scope`、`v6-handler-registry-mirror-trap`、`cpq-shared-flyway-history-churn`（迁移号取实际 max+1、勿改已应用号）。

---

## 1. 任务拆分总览

| Task | 标题 | 规模 | 依赖 |
|------|------|------|------|
| B1 | Q01 元素单价 handler 下线 | S | — |
| B2 | 全局料号类型推断服务 `PartTypeInferenceService`（Phase 1 预扫 + 冲突 + 库内兜底） | M | — |
| B3 | 物料BOM 单表三态改造（`MaterialBomMergeHandler` 重构为单 sheet + 类型推断 + issue_unit 兜底 + composition_qty） | M | B2 |
| B4 | 自制加工费 → `material_bom_item.operation_no` 工序反填 | S | B3 |
| B5 | 来料四表 + 组成件其他费用 删列/口径适配（Q06/Q07/Q09/Q13） | M | B2 |
| B6 | `material_master.material_type` 值域改造（零件/外购件） | S | B2/B3 |
| B7 | **两阶段校验 + 单一事务重构**（Phase1 校验器 + 外层事务 + handler 传播改 join） | L | B1~B6 |
| B8 | 全量必填/冲突/材质缺库/引用 校验汇总（并入 B7 Phase 1） | M | B7 |
| B9 | Flyway 迁移（如需，material_type 兼容 / 无结构变更则免） | S | B6 |
| B10 | 回归 + 自检 + 性能验证 | M | 全部 |

> 落地顺序建议：B2 → B3 → B4 → B5 → B6 → B1 → B7/B8（合并推进）→ B9 → B10。

---

## 2. Task B1 · Q01 元素单价 handler 下线

**目标**：新模板无 `元素单价` sheet，Q01 不再导入。

**做法**：
- `QuoteImportService#orderedHandlers()` 移除 `q01`；`@Inject Q01ElementPriceHandler q01` 一并移除。
- `Q01ElementPriceHandler.java` 删除（或保留类但不注册——**推荐直接删**，避免 CDI `Instance<SheetHandler>` 误收集；确认无别处引用）。
- 校验无残留引用：`codegraph_callers Q01ElementPriceHandler` / grep `Q01` 全工程。

**注意**：元素单价对应 `unit_price(price_type=ELEMENT, cost_type=元素价格)`。下线后 QUOTE 侧该维度不再由导入写入 —— 已确认元素价格由另立项功能维护，本次**不补偿、不迁移**。B10 回归需确认报价渲染取元素价格的链路不因此 500（若有读 QUOTE ELEMENT 单价的渲染点，记录为观察项，不在本次修复范围）。

**验收**：新模板导入 0 报错涉及元素单价；grep 无 Q01 残留注册。

---

## 3. Task B2 · 全局料号类型推断服务

**目标**：新建 `PartTypeInferenceService`（`basicdata/v6/quote/` 或 `service/`），在 Phase 1 预扫阶段构建**料号 → 类型**索引，供物料BOM / 来料四表定型。

### 3.1 输入：三个权威 sheet 的料号集 + 名称集

| 类型 | 来源 sheet | 料号列 | 名称列 |
|------|-----------|--------|--------|
| 材质 `RECIPE` | 物料与元素BOM | 材质料号 | 材质料号名称 |
| 零件 `ASSEMBLY` | 自制加工费 | 投入料号 | 投入料号名称 |
| 外购件 `OUTSOURCED` | 组成件其他费用 | 组成件料号 | 组成件名称 |
| 主件（=零件） | 客户料号与宏丰料号的关系 / 成品其他费用 / 组装加工费 | 销售料号 | — |

- 每个类型建两个集合：`Set<String> 料号集`、`Set<String> 名称集`（strip 后，空跳过）。
- 用 `SheetRow.exact("材质料号")` 等精确键读料号列，避开 contains 命中「…名称」列。

### 3.2 冲突检测（U6 洞①）

- 对同一 token（料号值 or 名称值），若同时出现在 ≥2 个类型集 → **收集为校验错误**（Phase 1 报错，不在此处抛，交 B7 汇总回滚）。
- 错误信息格式：`料号/名称 「X」同时命中 材质(物料与元素BOM) 与 外购件(组成件其他费用)，类型冲突`。
- 主件（零件）与「零件（自制加工费）」同属零件，不算冲突。

### 3.3 定型 API

```
InferResult infer(String rawPartNo, String rawName)
  // 返回 {characteristic: RECIPE|ASSEMBLY|OUTSOURCED, source: SHEET|RECIPE_DB|MASTER_DB|DEFAULT}
```

判定顺序（U1）：
1. **权威 sheet 匹配**：`rawPartNo` 命中任一类型料号集 **或** `rawName` 命中任一类型名称集 → 该类型（命中多类型已在 3.2 拦截）。
2. **material_recipe**：`rawPartNo` = `material_recipe.code` **或** `rawName` = `material_recipe.name`（`status='ACTIVE'`）→ 材质 `RECIPE`。
3. **material_master**：`rawPartNo` = `material_master.material_no` **或** `rawName` = `material_master.material_name` → 取其 `material_type` 映射（零件→ASSEMBLY / 外购件→OUTSOURCED；若旧值「组成件」→ 视为 ASSEMBLY 兜底）。
4. **兜底** → 零件 `ASSEMBLY`。

### 3.4 无 N+1 纪律（U8）

- material_recipe / material_master 的库内兜底**必须批量**：Phase 1 先收集所有「三 sheet 未命中」的料号 + 名称候选，**一次** `SELECT code,name FROM material_recipe WHERE status='ACTIVE'`（表小，全量拉进内存 Set）+ **一次** `SELECT material_no,material_name,material_type FROM material_master WHERE material_no IN (...) OR material_name IN (...)`（或全量，视表规模，工程师实测后定；本地表约数百行可全量）。
- 严禁在 per-row 循环里查库。

### 3.5 ⚠️ R2 · 名称→料号解析必须跨 handler 统一（防同件重号）

**测试复核发现的正确性风险**：`MaterialNoResolver.resolve()` 按名称查的是 `material_master` **正表**（`findFirstByMaterialName`），而导入期料号写进 `pending_material_master_staging`（未 promote）。若同一物理件在 sheet A 只填名称、在 sheet B 以「有料号」形式出现（写 staging 未入正表），两处解析互不可见、各 handler 的 `BatchState.nameToNo` 缓存又不共享 → **同一件被分配两个不同料号（重号）**。

**要求**：Phase 1 预扫时**统一建立「名称→料号」映射**（把三权威 sheet + 各 sheet 的 (料号,名称) 对收敛成一张 batch 级 `nameToNo`），Phase 2 所有 handler 解析名称→料号时**共享这一张表**，不各查各的正表。`PartTypeInferenceService` 除类型外，一并承担「名称→权威料号」解析（命中权威 sheet 的名称直接返回其料号）。B10 用 TC-U1-16/17（同名跨 sheet 一致性）验证不重号。

**验收**：单测覆盖 4 条判定路径 + 冲突拦截；`infer` 无逐行查库（日志/断点确认批量）；同名跨 sheet 解析出**同一** component_no。

---

## 4. Task B3 · 物料BOM 单表三态改造（核心）

**现状**：`MaterialBomMergeHandler#merge(materialRows, assemblyRows, ...)` 把 `物料BOM` 组件**恒写 `characteristic=RECIPE`**，靠 `组成件BOM`（assemblyRows）提供 ASSEMBLY/OUTSOURCED。

**新模板无 `组成件BOM`** → 重构为**单 sheet `物料BOM`**，`characteristic` 由 B2 `PartTypeInferenceService` 按投入料号定型。

### 4.1 改动点

1. **入参**：`merge` 改为只接 `物料BOM` 行（`assemblyRows` 分支删除或传空）。`QuoteImportService` 里 `wb.getSheet("组成件BOM")` 相关预扫全部删除。
2. **组件定型**：每行投入料号（`exact("投入料号")`，空回退 `exact("投入料号名称")` 走名称）→ `infer()` 得 `characteristic`：
   - `RECIPE` → 走材质分支：原始码作 `component_no`，**不 resolve / 不铸号 / 不登记 material_master**（沿用现状材质处理）。
   - `ASSEMBLY` / `OUTSOURCED` → 走零件/外购件分支：
     - 有料号 → 直接用该料号（U2：库内无也直接落库，不发号）；`MaterialNoResolver.resolveMatchOnly` 或直接用原始码 + `ensureRegistered` 占号。
     - 只有名称 → `MaterialNoResolver.resolve`（按名查 material_master，无则发号 U2）。
     - 登记 `material_master`：`material_type` 写汉字「零件」/「外购件」（B6）。
3. **新列 `组成数量`** → `composition_qty`（`row.getDecimal("组成数量")`）。加入 `CHILD_CONTENT`。
4. **`issue_unit` 兜底（U5）**：RECIPE 行用「重量单位」（现状 `weight_unit`）；ASSEMBLY/OUTSOURCED 行 `issue_unit` 兜底 `"PCS"`。
5. **主表 bom_type/characteristic**：保留现有判定 —— 有任一非 RECIPE 子行 → `bom_type=ASSEMBLY, characteristic=ASSEMBLY`；纯材质 → `bom_type=MATERIAL, characteristic=NULL`。
6. **CFG- 前缀拒导**、pending 归属、版本化写入（`writeVersionedMasterDetail`）等现有逻辑保留。

### 4.2 material_bom_item 子行字段映射（新模板）

| Excel 列 | 子行字段 | 备注 |
|----------|---------|------|
| 投入料号 | component_no | RECIPE 存原始码；ASSEMBLY/OUTSOURCED 存 resolve 后料号 |
| 项次 | seq_no | |
| 产出料号类型 | component_usage_type | `labelOnly()` 剥前导序号 |
| 组成数量（新） | composition_qty | 有下游 $view 用量 |
| 材料毛重 | rough_weight | |
| 材料净重 | net_weight | |
| 重量单位 | weight_unit | RECIPE 行 issue_unit 亦取此 |
| 损耗率（%） | scrap_rate | |
| 不良率（%） | defect_rate | |
| （推断） | characteristic | RECIPE/ASSEMBLY/OUTSOURCED |
| （来自自制加工费，B4） | operation_no | 反填 |
| （兜底 PCS 或 weight_unit） | issue_unit | U5 |

### 4.3 CHILD_CONTENT 更新

现状含 `characteristic`（三态统一必须参与内容比对，见现有注释）。新增 `composition_qty`（若未在列表内）。确认 `issue_unit` 在列表内（当前已在）。

**验收**：
- 用 `报价系统模板0723.xlsx` 导入，`material_bom_item` 中 `S-3120014539` 的子行：`991/992`=RECIPE、`S-80011`=ASSEMBLY（命中自制加工费投入料号）、`W-1001`=OUTSOURCED（命中组成件其他费用）；`composition_qty` 分别落值；`issue_unit` 组成件行=PCS。
- 主表 `S-3120014539` bom_type=ASSEMBLY（含非 RECIPE 子行）。

---

## 5. Task B4 · 自制加工费工序反填

**目标**：修复 `组成件BOM` 删除后 `material_bom_item.operation_no` 断供（U5）。

**做法**：
1. Phase 1 预扫 `自制加工费`：建 map `(销售料号, 投入料号) → 工序编号`（`row.getStr("工序编号")`；同键多工序取项次最小行，或第一条——工程师按数据实况定，默认取首条并在日志标注多工序情况）。
2. B3 写 `material_bom_item` 子行时，若子行 `characteristic=ASSEMBLY` 且命中 map → 写 `operation_no`。
3. 下游 `QuotationService` 的 seed SQL（`:590` / `:2437`，`WHERE characteristic='ASSEMBLY' AND operation_no IS NOT NULL`）**不动**。

**验收**：导入后 `SELECT operation_no FROM material_bom_item WHERE material_no='S-3120014539' AND component_no='S-80011'` = 自制加工费里该料号的工序编号（样例 `Z380`）；「从基础数据导入」加入报价单后 `quotation_line_process` 有对应工序行。

---

## 6. Task B5 · 来料四表 + 组成件其他费用 适配

### 6.1 来料三表口径统一（U10）—— Q06 / Q07 / Q09

现状 Q06/Q07/Q09 均 `exact("投入料号")` 恒按材质原始码处理（task-0717 扩围）。新需求下投入料号可能是三态之一，但**这三张表只写 `unit_price`（费用），不写 characteristic** —— 类型对它们的落库值无直接影响，**只需保证料号解析正确**：
- 有料号 → 用料号（现状即如此）。
- 只有名称（蓝色必填其一）→ 需**补名称反查**：`infer()` 定型后按类型反查（材质→material_recipe.name、零件/外购件→material_master.material_name 或发号）取得料号作 `code`。
- 材质定型 + 名称查不到 → 报错「未找到材质」（U2）。

> 若需求方本期只要求「料号列有值即可、名称行暂不强制」，可将名称反查降级为 Phase 1 校验拦截。**默认按 U10 全口径实现名称反查**，B10 用样例验证。

### 6.2 Q13 组成件其他费用删列适配（U0 #4）

新模板列：`销售料号 | 项次 | 组成件料号 | 组成件名称 | 供应商编号 | 供应商名称 | 项次 | 要素名称 | 值 | 货币 | 计价单位`（「项次」仅 2 次）。

改动：
- **`item_seq` 错位修正**：`row.getIntNth("项次", 3)` → **`row.getIntNth("项次", 2)`**（要素项次现为第 2 个「项次」）。**这是必修 bug**，否则 item_seq 恒 null。
- **`operation_no`**：`row.getStr("工序编号")` 现无此列 → 恒返 null。groupKey 含 operation_no（允许 null），确认 unit_price 唯一键在 operation_no=null 下不塌缩撞键（同 (要素,组成件料号,成品,供应商) 下多行会合并到一组 multiset —— 组成件其他费用一般一件一要素一行，实测样例无撞键；若真撞键 Phase 1 报错）。
- `costType = row.getStr("要素名称")`（现状，列仍在，不变）。
- 外购件类型：Q13 的组成件料号命中外购件权威集，B2 冲突检测已保证不与材质/零件冲突。resolve/登记逻辑保留，`material_type` 写「外购件」（B6）。

**验收**：样例 `W-1001` 组成件其他费用行落 `unit_price(price_type=COMPONENT_OTHER)`，`item_seq` 非 null；无撞键错误。

---

## 7. Task B6 · material_type 值域改造（U3）

**目标**：`material_master.material_type` 新导入写 `{零件, 外购件}`，与 characteristic 对应。

**改动点**（grep `"组成件"` 全 quote handler）：
- `MaterialBomMergeHandler#accMaterialMaster(..., "组成件")` → 按子行 characteristic 写「零件」(ASSEMBLY) / 「外购件」(OUTSOURCED)。
- `Q13ComponentOtherFeeHandler` 的 `accNameType(..., "组成件")` → 「外购件」。
- 其余登记 material_master 的 handler 同步（Q10 自制加工费投入料号若登记 → 「零件」；核对 Q10 现状是否登记 master）。

**存量**：`material_type='组成件'` 旧行**不迁移**（U3 / §6）。`upsertBatchNameType(preserve=true)` 语义下，已存在行保留旧值 —— 注意：这会导致存量「组成件」行不被覆盖为新值。若需求方要求新导入覆盖旧「组成件」为准确类型，需将该字段改 `preserve=false`（**默认保持 preserve=true，存量不动**，符合 §6；B10 验证新料号写对即可）。

> **⚠️ R1（技术总监复核 · 铁的事实，必读）**：导入期 `ctx.pendingQuotationId = importRecordId` **恒非空**，故 `MaterialMasterRepository.upsertBatchNameType(rows, updatedBy, preserve, pendingQuotationId)` 走 `stageOne()` 写 **`pending_material_master_staging`（键 = quotation_id + material_no）**，**不写 `material_master` 正表**（正表由核价审批通过后 `promoteStaging` 落地）。
> - **禁止**为了让 `material_master` 有值而绕过 pending 写正表 —— 那会破坏 task-0721 的 pending 隔离语义（AC-3/AC-4）。
> - 你要做的只是把 material_type 的**取值**从「组成件」改为「零件/外购件」，落点仍是 staging，写入通道不变。

**验收（改查 staging，勿查正表）**：
- `SELECT material_type FROM pending_material_master_staging WHERE quotation_id=:pendingQuotationId AND material_no='W-1001'` = 「外购件」；`'S-80011'` = 「零件」。
- 反向确认：`SELECT count(*) FROM material_master WHERE material_no IN ('W-1001','S-80011')` 仍为 0（导入期不落正表）。
- 材质料号 `991/992` 既不进 material_master 也不进 staging。

---

## 8. Task B7 · 两阶段校验 + 单一事务重构（核心 · 高风险）

> 需求方拍板口径（U6/U7）：**Phase 1 全量校验零写库 → 全通过才 Phase 2 写入；Phase 2 外层开一个事务，内层 13 个 handler join 共享，一起提交 / 一起回滚。**

### 8.1 现状与目标

- **现状**：`processImport` 逐 handler 调 `@Transactional(REQUIRES_NEW)`，各自独立 commit → 可能 PARTIAL。
- **目标**：
  - **Phase 1**：新增 `QuoteImportValidator`，解析所有 sheet（一次性）+ 跑 B2 类型推断 + 全量校验（B8），返回 `List<RowError>`。**不写任何库**。
  - **Phase 2**：`errors.isEmpty()` 时，外层方法 `@Transactional(REQUIRES_NEW)` 包住**所有** handler 调用；handler 事务传播由 `REQUIRES_NEW` 改为 **join 外层**。任一 handler 抛异常 → 外层整体 rollback。

### 8.2 事务传播改法（关键）

- 外层新增 `writeAll(...)` 方法，`@Transactional(REQUIRES_NEW)`（后台线程 + `@ActivateRequestContext` 已有，EM 绑定此事务）。
- 13 个 handler + `MaterialBomMergeHandler` 的 `@Transactional(REQUIRES_NEW)` → **`@Transactional(Transactional.TxType.MANDATORY)`**（强制 join 已存在的外层事务；若无外层事务直接抛 `TransactionRequiredException`，反而能暴露误用）。
- `QuoteMaterialNoAllocator` 的 `ensureRegistered`（`dontRollbackOn=CrossCustomerQuoteNoException`）与 `mintAndRegister` —— 现状 `@Transactional(REQUIRED)` join，**保持 REQUIRED**（跨串号异常仍需 `dontRollbackOn` 避免污染外层，逻辑不变）。**⚠️ 重点回归**：整单回滚时占号行随外层事务一起回滚（占号行是事务性写入，会回滚；仅 sequence 号不回退 = U6 号段空洞，可接受）。

### 8.3 handler 返回值语义调整

- handler 内 per-row `recordError` 现在会走到 Phase 2 —— 但 Phase 1 已全量校验通过，Phase 2 理论上不应再有业务错误。Phase 2 若仍 `recordError`（如 DB 唯一键冲突等意外）→ **抛异常触发整体回滚**（不能吞）。约定：Phase 2 handler 遇任何 `recordError` 级问题 → `throw`，由外层 catch 落 `FAILED` + 回滚。
- `SheetImportResult` 的错误在 Phase 1 收集；Phase 2 只统计成功写入数。

### 8.4 进度条与状态

- Phase 1 校验失败 → `import_record.status=FAILED`，`metadata.sheetResults` 写全量错误清单（前端复用现有渲染，api.md）。
- Phase 2 成功 → `SUCCESS`；Phase 2 意外异常 → `FAILED`（回滚，无残留）。
- **不再有 `PARTIAL` 状态**（U7）。进度条 `updateProgress` 保留（Phase 2 各步仍报进度）。

### 8.5 性能（U8）

- Phase 1 解析所有 sheet 一次（现状 handler 各自 parseSheet，重构后统一 parse 一遍缓存，避免重复解析）。
- 无 N+1（B2 批量兜底、handler 现有 setBased 批量路径）。
- 百行内端到端 < 2s：B10 实测。

**验收**：见 B10。

---

## 9. Task B8 · 全量校验汇总（并入 B7 Phase 1）

Phase 1 `QuoteImportValidator` 逐 sheet 校验，全部收集不中断：

| 校验 | 范围 | 错误 |
|------|------|------|
| 关键键列必填（U12） | 各 sheet 销售料号 / 项次 / 料号类列 | `X 为空` |
| 蓝色必填其一 | 料号列 + 名称列 | `料号与名称均为空` |
| 类型冲突（U6 洞①） | B2 冲突集 | `料号「X」类型冲突` |
| 材质缺库（U2） | 定型=材质但 material_recipe 查无 | `未找到材质「X」` |
| CFG- 前缀 | 销售料号 | `禁止导入系统生成料号` |
| 跨客户串号 | 占号预检（可选，或留 Phase 2 dontRollbackOn） | `报价料号跨客户串号` |

- **业务值列不强制**（U12）：金额 / 比例 / 含量等允许空。
- 校验产物：`{sheetName, rowNo, column, message}` 列表，去重后写 metadata。

**验收**：故意构造一份含 1 个类型冲突 + 1 个材质缺库的文件 → 返回 2 条错误、`status=FAILED`、**库内零写入**（校验前后 `SELECT count(*)` 各表不变）。

---

## 10. Task B9 · Flyway 迁移（按需）

- 本次**无强制表结构变更**（material_type 已是 VARCHAR(50)，characteristic 三态列已存在）。
- 若 B6 决定覆盖存量「组成件」→ 需一次性 `UPDATE`，但**默认不做**（§6 存量不动）。
- 若确需迁移：新号从 **实际 `MAX(version)+1`** 起（当前文件系统最大 V358，但**必须** `SELECT MAX` 实测、避开并发会话占号，见记忆 `cpq-shared-flyway-history-churn`），勿改已应用号。
- **默认本 Task 为空**（无迁移）。

---

## 11. Task B10 · 回归 + 自检 + 性能

### 11.1 强制自检（CLAUDE.md「修改后强制自检」）

- 后端：`touch` java 触发 Quarkus 重启 → `curl -s -o /dev/null -w '%{http_code}' --noproxy '*' http://localhost:8081/api/cpq/components` 期望 401。
- 单测：`cd cpq-backend && ./mvnw test -Dtest='*Quote*,*PartType*,*MaterialBomMerge*'` 全绿（在 worktree 的 cpq-backend 里跑，见记忆 `cpq-worktree-maven-test-tree`）。

### 11.2 功能验收（用 `报价系统模板0723.xlsx`）

1. 正常导入 → `status=SUCCESS`，无 PARTIAL。
2. `material_bom_item`：三态正确（B3 验收）、`operation_no` 反填（B4）、`composition_qty`/`issue_unit` 正确（B3）。
3. **料号登记落 staging（R1）**：`pending_material_master_staging`（quotation_id=pendingQuotationId）新料号 material_type = 零件/外购件；`material_master` 正表**不**落新料号（B6）。
4. **整单回滚**：构造错误文件 → `FAILED` + 各表 `count(*)` 校验前后一致（B8/B9）。
5. 创建报价单 → 编辑页渲染子件/单位/工序正常（AP-53 视图链路 `$view`）。

### 11.3 性能（U8）

- 计时点击导入 → `import_record` 变 `SUCCESS` 的 wall-clock，百行内 **< 2s**。
- 记录 `[v6import] QUOTE TOTAL elapsed=...ms` 日志；确认无 N+1（`VersionedV6Writer.profile()` summary、Hibernate SQL 计数）。

### 11.4 升版语义回归（U13）

- 导入走 pending，`pending_quotation_id` 落 7 表 + 占号表；未 create-quotation 前不触发真实升版。
- 重导覆盖（`clearPreviousPending`）语义不破坏。

### 11.5 PR 附证

- 三态 SQL 查询结果截图/文本、回滚前后 count 对比、`< 2s` 计时、单测绿、`quotation-flow` E2E（若触发协议级改动，见 CLAUDE.md 清单——本次改 `MaterialBomMergeHandler` + 导入事务，**建议跑一遍** `quotation-flow.spec.ts` 确认渲染不回归）。

---

## 12. 影响面清单（工程师写代码前 grep + codegraph_impact 核对）

| 符号/文件 | 动作 |
|-----------|------|
| `Q01ElementPriceHandler` | 删除 + 去注册 (B1) |
| `MaterialBomMergeHandler` | 重构单 sheet 三态 (B3/B4/B6) |
| `QuoteImportService` | 去组成件BOM预扫、Phase1/2 编排、事务重构 (B3/B7) |
| `Q06/Q07/Q09` 来料 | 名称反查口径 (B5) |
| `Q13ComponentOtherFeeHandler` | item_seq 错位修正、operation_no 恒空 (B5) |
| 新增 `PartTypeInferenceService` | B2 |
| 新增 `QuoteImportValidator` | B7/B8 |
| 所有 quote handler `@Transactional` | REQUIRES_NEW → MANDATORY (B7) |
| `QuoteMaterialNoAllocator` | 保持 REQUIRED，回归号段空洞 (B7) |
| `PricingSheetRegistry` | **确认是否镜像报价侧**（记忆 `v6-handler-registry-mirror-trap`）——本次改报价 quote/ 侧，核价 pricing/ 侧不动，但若有共享声明需同步核对 |
| `material_bom_item.operation_no` 下游 | `QuotationService:590/2437` **不动**，仅回归 |

> **codegraph 优先**：`codegraph_impact MaterialBomMergeHandler`、`codegraph_callers Q01ElementPriceHandler`、`codegraph_trace 物料BOM导入→material_bom_item→报价渲染` 评估爆炸半径，勿只靠 grep。
