# test — task-0725 修复报价单页签无法显示数据

> 基准样本：报价单 **`QT-20260725-0001`**（id `c670e9e7-5f7c-4b72-9a27-965447fcf75b`）
> 客户 罗克韦尔 `CUST-0001`（id `32aea5b1-…`）／主件 `S-3120014539`（主料1）／`composite_type=SIMPLE`
> 明细行 id `6ad49abc-7b9f-4de2-a993-5c7d22e30aba`／客户料号 `PN0507945`
> 导入源 `docs/table/报价测试数据/v2/报价系统模板0723.xlsx`
>
> **下表所有期望值均已于 2026-07-25 直连 SQL 实测确认，非估算。**

---

## 0. 前置条件（不满足则整批用例无效）

| # | 条件 | 校验方式 | 期望 |
|---|------|---------|------|
| P-1 | 后端在跑 | `curl -s --noproxy '*' -o /dev/null -w '%{http_code}' http://localhost:8081/api/cpq/components` | `401` |
| P-2 | 前端在跑 | `curl -s --noproxy '*' -o /dev/null -w '%{http_code}' http://localhost:5174/` | `200` |
| P-3 | **报价树配置在位** | `GET /api/cpq/costing-bom-tree-config?usage=QUOTE` | 1 条 `isActive=true` |
| P-4 | **核价树配置在位** | `GET /api/cpq/costing-bom-tree-config?usage=COSTING` | 1 条 `isActive=true` |
| P-5 | 组件 tab_type 已配 | 见下方 SQL | BOM/主件/零件/外购件/材质元素 各 1 |
| P-6 | 本单 pending 数据在库 | 见下方 SQL | mbi 10 / ebi 6 / up 20 行，全 `is_current=f` |

```sql
-- P-5
SELECT name, component_type, tab_type, bom_recursive_expand, data_driver_path
FROM component ORDER BY name;

-- P-6
SELECT 'material_bom_item' t, count(*) FROM material_bom_item
  WHERE system_type='QUOTE' AND pending_quotation_id='c670e9e7-5f7c-4b72-9a27-965447fcf75b'
UNION ALL SELECT 'element_bom_item', count(*) FROM element_bom_item
  WHERE system_type='QUOTE' AND pending_quotation_id='c670e9e7-5f7c-4b72-9a27-965447fcf75b'
UNION ALL SELECT 'unit_price', count(*) FROM unit_price
  WHERE system_type='QUOTE' AND pending_quotation_id='c670e9e7-5f7c-4b72-9a27-965447fcf75b';
```

> ⚠️ **P-3 / P-4 是环境级前置**：`costing_bom_tree_config` **全库无 INSERT 迁移**，配置只活在 DB，环境重建即丢。丢了会导致 **BOM 页签落失败哨兵**（`__renderError`）。⚠️ 注意：材料成本的行数**不受**树配置影响（报价侧无 BOM 闭包，种子恒为 `productPartNo`），恒为 2 行。

### 🔴 §1 全部核心用例的前置操作：必须走「从基础刷新」重新物化

```bash
curl -s --noproxy '*' -b jar.txt -X POST \
  http://localhost:8081/api/cpq/configure-product/quotations/c670e9e7-5f7c-4b72-9a27-965447fcf75b/refresh-snapshot
```

对应前端 DRAFT 态常驻的「刷新基础数据」按钮（`QuotationStep2.tsx:3401`），**一次点击即可，无需重新编辑字段**。

⚠️ **不要用「保存草稿」验收**：`QuotationResource:132` 调 `snapshotQuotation(id, **true**)` 走**增量**，而 `ConfigureSnapshotService.lineNeedsExpand:148-156` **只判 `sr == null`** —— 上次失败物化写下的 `snapshot_rows = []` 是非 null → 整行判「已完整」跳过 → `anyNeedsExpand=false` → 连树都不渲染。**页签仍空，会被误判为「修复无效」。**
⚠️ 也不要期待「打开页面等一会自愈」：2026-06-01 已取消 10 秒定时自动保存（`QuotationWizard.tsx:232-237`），必须显式点按钮。

### 环境范围（本期已确认）

数据库固定 **`cpq_db`**（jh profile）。已实测该库**只有 1 张报价单且为 DRAFT，零冻结单** → 「bug 窗口期内冻结、页签永久空白」的问题在本环境**不存在**，本期不处理。将来上真实环境前须先跑：

```sql
-- 查 bug 窗口期（2026-07-21 起）内已冻结且页签为空的单子
SELECT q.quotation_number, q.status, q.created_at
FROM quotation q JOIN quotation_line_item li ON li.quotation_id = q.id
WHERE q.status IN ('SUBMITTED','APPROVED','PUBLISHED')
  AND q.created_at >= '2026-07-21'
  AND (li.quote_card_values IS NULL
       OR li.quote_card_values::text LIKE '%"baseRows": []%');
```

返非空 = 存在需单独决策的数据完整性缺口（救它们要破例重算，会打破「冻结不漂移」规则）。

---

## 1. 核心功能用例（根因 1 主验收）

| ID | 用例 | 步骤 | 修复前 | ⭐ 期望 |
|----|------|------|--------|--------|
| TC-CORE-1 | 产品页签出数 | 点「刷新基础数据」重新物化后查 `quote_card_values` | 0（查询报错） | **1 行**：`S-3120014539`／客户产品编号 `PN0507945`／汇率 `6.9755` |
| TC-CORE-2 | BOM 页签出树 | 同上 | 失败哨兵 `__renderError` | **6 节点**（结构见 §2） |
| TC-CORE-3 | 材料成本出数 | 同上 | 0 | **2 行**（均为销售料号 `S-3120014539`）⚠️ 期望已由 3 更正为 2，见下方说明 |
| TC-CORE-4 | 外购件出数 | 同上 | 0 | **1 行**：`W-1001` 组成件1（`characteristic='OUTSOURCED'`） |
| TC-CORE-5 | 加工费出数 | 同上 | 0 | **1 行**：仅自制加工费（`price_type='PROCESS'`，`code=S-80011`）。**已确认为有意口径**，不含 `INCOMING_MATERIAL_PROCESS` 的 2 行来料固定加工费 |
| TC-CORE-6 | 小计非 0 | 同上 | `subtotal=0.0` | **非 0** 汇总值 |
| TC-CORE-7 | 无遗留报错 | 查后端日志 | 见下 | 不出现 `executeAllRows failed path=$cp_view` / `column index is out of range` / `pending 改写失败` / `报价树整单渲染失败` |
| TC-CORE-8 | 无「加载中」 | 前端 8 Tab 计数 | — | **全部 = 0** |

### TC-CORE-3 的依赖说明（易误判为 bug）

**材料成本为什么是 2 行不是 3 行**（2026-07-25 更正，原文错误）：

`element_bom_item` 里本单共 3 行，但第 3 行的销售料号是**子件 `S-80011`**。而报价侧普通页签的展开种子**恒为本行产品料号**（`ConfigureSnapshotService` javadoc 原文：「报价侧无 BOM 闭包，分桶键就是产品料号本身」，代码见 `:371`/`:581`），**不纳入 BOM 树发现的子件**。按 `material_no='S-3120014539'` 收窄 → 恰好 **2 行**。

原文把归档文档「核价SQL配置手册」里的**核价侧**闭包契约错误外推到了报价侧。**2 行是当前设计下的正确结果，不是缺陷。** 若测出 3 行，反而说明引入了非预期的闭包行为，须报告。

---

## 2. BOM 树结构用例

期望树（与 Excel「物料BOM」5 行严格对应，共 1 根 + 5 边 = 6 节点）：

```
S-3120014539          bom_version=2001   node_path=S-3120014539
├── 00137   (H65)                        node_path=S-3120014539/00137
├── 00006   (AgNi10)                     node_path=S-3120014539/00006
├── S-80011 (投入零件1)  bom_version=2001  node_path=S-3120014539/S-80011
│   └── 00006 (AgNi10)                   node_path=S-3120014539/S-80011/00006
└── W-1001  (组成件1)                     node_path=S-3120014539/W-1001
```

| ID | 用例 | 期望 |
|----|------|------|
| TC-TREE-1 | 节点数 | **6** |
| TC-TREE-2 | `node_path` 前缀性 | 每个子孙的 `node_path` 以其父的 `node_path` 为前缀 |
| TC-TREE-3 | **同料号多 occurrence 不挂错枝** | `00006` 出现 **2 次**（根下 + `S-80011` 下），两者 `node_path` **不同**，各自挂在正确父节点 |
| TC-TREE-4 | `parent_no` 正确 | 根 `parent_no=NULL`；`S-80011/00006` 的 `parent_no=S-80011` |
| TC-TREE-5 | `bom_version` | 有自身 BOM 的节点带版本（根与 `S-80011` = `2001`），叶子为 `NULL` |
| TC-TREE-6 | 行键含 `__nodeId` | 树上行键必须含 `__nodeId`，否则同料号跨节点撞键（design §4.4） |
| TC-TREE-7 | 树页签业务行挂载 | BOM 页签的业务行（走 `$bom_view`）按 `(parent_no, material_no)` 边键正确挂到对应节点 |

---

## 3. 根因 2 用例（注释屏蔽）

| ID | 用例 | 步骤 | 期望 |
|----|------|------|------|
| TC-MASK-1 | `cp_view` 注释原样可执行 | 保持 `-- 产品(主件, 平铺契约: hf_part_no + :customerCode)` 不动，触发展开 | 查询成功返 1 行；日志无 `column index is out of range` |
| TC-MASK-2 | `bom_view` 注释原样可执行 | 保持 `-- BOM 树页签(树契约: … + :total_material_no; …)` 不动 | Java 绑定数 == pgjdbc 占位符数；查询成功 |
| TC-MASK-3 | 行注释内 token 不识别 | 单测：`-- :foo` + 正文 `:foo` | 只产生 **1** 个占位符 |
| TC-MASK-4 | 块注释内 token 不识别 | 单测：`/* :foo */` + 正文 `:foo` | 只产生 **1** 个占位符 |
| TC-MASK-5 | 字符串字面量内 token 不识别 | 单测：`WHERE x = ':foo'` | **0** 个占位符 |
| TC-MASK-6 | `::` cast 不误识 | 单测：`p::text` / `id::uuid` | **0** 个占位符（既有 `(?<!:)` 行为不回退） |
| TC-MASK-7 | 正文 token 仍正常替换 | 单测：正文多处同名 token | 全部替换，绑定顺序正确 |
| TC-MASK-8 | 偏移量对齐 | 单测：注释在 SQL 中间、含多个换行 | 替换位置正确，行号不错位 |
| TC-MASK-9 | **双链路一致** ⚠️ | 同一 SQL 分别过 `SqlViewExecutor.extractNamedParams` 与 `SqlViewValidator` | 占位符清单**完全一致**（防「dry-run 报错但实际能跑」，这是 V236 同源坑第二次） |
| TC-MASK-10 | 树 SQL 注释加固 | 树递归 SQL 注释里写 `:production_part_nos` | `TREE_PARAM` 不误识，递归 SQL 正常执行 |

---

## 4. AC-17 核价侧零回归用例（最高优先级门禁）

> 误改写**不会崩、只会静默漂移**，人眼看不出来 —— 本节全部靠断言，不接受"看起来正常"。

> 🔴 **本节已于 2026-07-25 按需求方决策整体重写。** 原 TC-AC17-4「4 个等价性测试全绿」**作废** ——
> `GoldenCardValuesEquivTest` / `NonRecursiveCostingBucketEquivTest` / `CardValuesBatchPersistEquivTest` / `FirstSaveQuoteBucketEquivTest`
> 全部把锚单硬编码为 `8f0c37a4-8186-4f5e-a9ca-358bd2d9662d` / `a8f17a74-5a32-40fc-9e3d-bd5e81181248` 并用 `Assumptions.assumeTrue` 保护。
> **已实测这两张单不在 `cpq_db`**（库中只有 `QT-20260725-0001`，该库是重建过的空 baseline 库）→ 四个测试**全部 skip，Maven 报绿**。
> 叠加 BL-0021（golden 常量已过期，干净 HEAD 上同样 FAIL）。**用「什么都不检查的检查」当唯一门禁 = 自欺。**
> BL-0021 本期不做，继续挂 P2。

| ID | 用例 | 期望 |
|----|------|------|
| TC-AC17-1 | **`open()` 白名单写成单测**（不是人肉 grep） | 遍历 `src/main/java`，断言含 `QuotePendingScope.open(` 的**文件集合 == 白名单集合**（`backtask.md` T3 的 P1/P2/P3/P4）。**不得出现**在 `CardSnapshotService#precomputeCostingDriverUnion(:767)` / `#buildCostingCardValues(:1152)` / `#snapshotNewLinesCardValues(:483)` / `CostingVersionService(:354)` / 核价侧 render 调用点（`CardSnapshotService:501`、`:841`、`CostingVersionService:209`）。这是 D' 全部安全性的支点，靠人工审查会腐化 |
| TC-AC17-2 | **负向断言：核价侧 SQL 不含 pending 痕迹** | 用 `SqlDebugContext`（`begin()`/`record()`/`drainJoined()`）录下核价侧渲染（`precomputeCostingDriverUnion` + `buildCostingCardValues`）实际发出的**每一条** SQL，断言逐条都**不含** `pending_quotation_id`、**不含** `AS __v6_id`，且参数表里没有本单 quotationId 被当 `:pq` 绑入 |
| TC-AC17-3 | ⭐ **正向对照：报价侧 SQL 必须含** | 同一单跑报价侧，断言捕获的 SQL **确实含** `(t.is_current OR t.pending_quotation_id = ?)` 且含 `AS __v6_id`。**没有正向对照，TC-AC17-2 就是「因为什么都没跑所以通过」** |
| TC-AC17-4 | ⭐ **前置非空断言（防空转）** | 任何 `assertFalse(contains(...))` 之前先 `assertFalse(capturedSql.isEmpty())`；涉及 rows 的断言先断言 rows 非空。⚠️ `SqlViewExecutorPendingHookTest` 的断言全在 `for (Map row : rows)` 里（其 `:45-46` 注释自己承认「数据可能为空」），空结果集 = 空转通过 —— **不要复制这个缺陷** |
| TC-AC17-5 | ⭐ **证明测试真的跑了** | `assumeTrue` 的 skip 在 Maven 里与 pass 视觉无差别。PR 须附 surefire 报告中相关测试类的 `tests run / skipped` 计数，要求 **`skipped == 0`**。**只贴「BUILD SUCCESS」不算证据** |
| TC-AC17-6 | 核价卡产物不含 `__v6_id` | DRAFT 下跑 `ensureCardValues(qid)`，断言 `costing_card_values` 的 `driverRow` **不含 `__v6_id`** 键（与 TC-AC17-2 互为「SQL 层 / 产物层」双重确认） |
| TC-AC17-7 | 核价卡不含 pending 行 | 核价渲染结果中不出现只存在于 pending 的行 |
| TC-AC17-8 | **AP-37 同单跨侧缓存交叉（两层）** | 同一报价单、同一 lineItem、同一组件：先跑报价侧（开域）再跑核价侧（关域），**30s TTL 内 + 同一请求内**（前者覆盖 `expandCache`，后者覆盖 `@RequestScoped` 的 `DataLoader.resultCache`）。断言核价侧结果不含 pending 行、不含 `__v6_id`。反向顺序同测 |
| TC-AC17-9 | 核价侧 cacheKey 逐字不变 | 单测：scope 关闭态下 `ComponentDriverService.cacheKey` **与改动前逐字相同**（`cacheTag()` 返 `""`） |
| TC-AC17-10 | 报价 vs 核价 cacheKey 必须不同 | 单测：同参数下 scope 开 vs 关，`cacheKey` **必须不同** |
| TC-AC17-11 | ⭐ **`DataLoader` 三个重载的 key 同款** | 单测：`loadByPath` 的 `:90` / `:104` / `:189` 三个进缓存重载，在 scope 开/关下 key **必须不同**；关闭态**逐字不变**。⚠️ `:90` 那个重载的 key **只有 `normalizedPath` 一项**，粒度最粗，重点覆盖 |
| TC-AC17-12 | 核价树基线（**烟雾测试，非硬门禁**） | 种子 `S-3120014539` → **15 节点 / 4 层**（`00006`/`00168` 各出现 2 次且 node_path 不同）。⚠️ 该值**数据依赖**（jh 库实测），环境重建即失效且 `costing_bom_tree_config` 全库无 INSERT 迁移 → **前置 P-4 不满足时本项作废而非 FAIL** |

> **为什么必须靠断言而非人眼**：误改写**不会崩、只会静默漂移**。官方数据不会丢（不变式 `is_current=true ⟹ pending_quotation_id IS NULL` 由 `QuoteBackfillService:106-107` 与 V349 默认 NULL 共同保证），症状只是「多出 `__v6_id` 列 + 多出 pending 行」—— 而 `__v6_id` 一旦进 `costing_card_values`，等价性就破了。

### TC-AC17-6 的现实可触发性确认（可选）

```sql
SELECT c.code, c.name, c.tab_type
FROM quotation q
JOIN template t ON t.id IN (q.customer_template_id, q.costing_card_template_id)
JOIN template_component tc ON tc.template_id = t.id
JOIN component c ON c.id = tc.component_id
WHERE c.data_driver_path IS NOT NULL AND c.data_driver_path <> ''
GROUP BY c.code, c.name, c.tab_type
HAVING bool_or(t.id = q.customer_template_id) AND bool_or(t.id = q.costing_card_template_id);
```

返非空 = 隐患现实可触发（必须过用例）；返空 = 仍须过用例（防御未来配置）。

---

## 5. AC-10 冻结态用例

| ID | 用例 | 期望 |
|----|------|------|
| TC-AC10-1 | `DRAFT` → 开域 | `pendingOwner()` 非 null，pending 可见 |
| TC-AC10-2 | **`SUBMITTED` → 不开域** | `pendingOwner()` == **null**。⚠️ 关键边界：此刻 B5 尚未升版（`QuoteBackfillService` 在核价通过才跑），pending 行仍带 `pending_quotation_id=本单`。**不可用「反正 B5 已升版所以改写等价于不改写」的推理省掉冻结判定** |
| TC-AC10-3 | `APPROVED` → 不开域 | 同上 |
| TC-AC10-4 | `PUBLISHED` → 不开域 | 同上 |
| TC-AC10-5 | `quotationId=null` → 不开域 | 同上 |
| TC-AC10-6 | 已冻结单快照不漂移 | ⚠️ 本库**零冻结单**（已实测：仅 1 张 DRAFT 单），故本项**以单测覆盖**（构造 SUBMITTED 上下文断言 `pendingOwner()==null`），不造真实冻结单 |
| TC-AC10-7 | **休眠分支保持休眠** | 断言 `ComponentSqlViewService:379`（`quotationId != null && isQuotationFrozen()`）在 driver 展开链路上**仍不可达** —— 即传入 `SqlViewRuntimeContext` 的第 4 参恒为 `null`。可用日志/断点或单测断言 `isQuotationFrozen()==false` |
| TC-AC10-8 | 嵌套 open/restore | 嵌套调用后正确还原到外层值；异常路径下 finally 仍还原（无 ThreadLocal 泄漏） |

---

## 6. 入口覆盖用例（漏一个该场景仍失效）

| ID | 入口 | 覆盖点 | 期望 |
|----|------|--------|------|
| TC-ENTRY-1 | 建单（Excel 导入） | P1 | 各页签有数据 |
| TC-ENTRY-2 | 加产品（选配） | P1 | 新增行的页签有数据 |
| TC-ENTRY-3 | saveDraft | P1 | ⚠️ saveDraft 走**增量**，对已有非 null 空数组是 no-op（见 §0 注）；此项验的是「**新行**能出数」 |
| TC-ENTRY-3b | **从基础刷新**（存量空快照重算入口） | P1 | `POST /api/cpq/configure-product/quotations/{id}/refresh-snapshot` → 各页签有数据。**这是 §1 全部核心用例的前置操作** |
| TC-ENTRY-3c | **报价 Excel 值 / 导出** | P4 | 与页签数据一致，不得「页面有数据、导出空白」 |
| TC-ENTRY-4 | 报价树渲染 | P1 | 6 节点树 |
| TC-ENTRY-5 | 「刷新基础数据」按钮 | P2 | 刷新后仍有数据 |
| TC-ENTRY-6 | 公式 dry-run 预览 | P2 | token 行非空 |
| TC-ENTRY-7 | 前端实时 batch-expand | P3 | F12 请求体报价侧带 `"usage":"QUOTE"`、核价侧带 `"usage":"COSTING"`；页签渲染正确 |
| TC-ENTRY-8 | **老前端兼容** | P3 缺省值 | 不传 `usage` 时按 `COSTING` 兜底，行为与修复前逐字相同 |
| TC-ENTRY-9 | **非法 usage 兜底** | P3 | 传 `"XXX"` 按 `COSTING` 处理，**不抛错**、不导致整批 expand 失败 |
| TC-ENTRY-10 | 同批混合 usage | P3 | 一个请求内 QUOTE + COSTING task 各自独立求值，按 **task index** 配对（AP-37：不得用 backend `r.key` 配对） |

---

## 7. 数据隔离与幂等用例

| ID | 用例 | 期望 |
|----|------|------|
| TC-ISO-1 | **他单 pending 不可见** | 渲染结果中**不得出现** `pending_quotation_id='978479fd-fbad-4426-bcf0-d39603a67f3c'` 那批行（另一次导入残留，现成的隔离样本） |
| TC-ISO-2 | 官方 current 行仍可见 | `pending_quotation_id IS NULL` 且 `is_current=true` 的正式行可见性不变 |
| TC-ISO-3 | 遮蔽正确不翻倍 | 被 pending 行 `pending_supersedes` 点名的旧 current 行被屏蔽，同组不出现「official + pending」两行并存 |
| TC-IDEM-1 | **重复物化行数稳定** | 连续走 `refresh-snapshot` 重新物化 **3 次**，各页签行数**稳定不累加**（AP-51：driver 行数权威优先，**禁** `Math.max(expansion.rowCount, baseRows.length)`） |
| TC-IDEM-2 | 刷新 3 次树节点稳定 | BOM 树恒 6 节点，不重复长枝 |

---

## 8. 三视图一致性用例（AP-50 / AP-41）

| ID | 视图 | 期望 |
|----|------|------|
| TC-VIEW-1 | 报价单编辑页（Step2） | 各页签数据正确 |
| TC-VIEW-2 | 报价单详情页（`ReadonlyProductCard`） | 与编辑页**一致**，无僵尸数据、无缺失的 `DATA_SOURCE` / `LIST_FORMULA` 渲染分支 |
| TC-VIEW-3 | 核价单视图 | 与修复前**逐位相同**（AC-17 前端观测面） |
| TC-VIEW-4 | 视图间切换不串数据 | 报价↔核价来回切，数据不互相污染（前端 fingerprint 是否补 `usage` 维度的直接验证） |

---

## 9. E2E（强制，PR 门禁）

```powershell
cd cpq-frontend
Remove-Item e2e\screenshots\qf-*.png -ErrorAction SilentlyContinue
npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list
npx playwright test --config=e2e/playwright.config.ts e2e/composite-product-flow.spec.ts --reporter=list
```

| ID | 期望 |
|----|------|
| **TC-E2E-0** | ⭐ **开工前先跑基线**（不改任何代码），把结果填进下表。这是 T0，**必须最先做** |
| TC-E2E-1 | `quotation-flow.spec.ts`（SIMPLE）：基线全绿 → 要求全绿；基线带已知无关失败 → **相对基线无新增失败**且失败签名逐字一致 |
| TC-E2E-2 | `composite-product-flow.spec.ts`（COMPOSITE）：同上口径（注意它曾是 `1 skipped`，task-0712 遗留 `test.skip(true,...)`） |

### 基线实测记录（T0 填写）

> **实测环境说明**：本机（`.claude/worktrees/task-0725-quote-pending-fix`）无 Playwright 内置 chromium、无真实 `google-chrome-stable` 二进制（仅残留 bash-completion 脚本）、无 `psql` CLI。项目默认 `e2e/playwright.config.ts`（`channel:'chrome'` + `globalSetup` 调 `psql`）**在本机跑不起来**：`globalSetup` 内 `unlockAccounts()` 因缺 `psql` 报错但被内部 `try/catch` 吞掉（非致命），随后 `saveStorageState` 用 `chromium.launch({channel:'chrome'})` 因找不到 `/opt/google/chrome/chrome` 直接抛致命错误，测试 0 条执行。改用一个**临时**（跑完即删、未提交、未落入仓库）Playwright config：去掉 `globalSetup`（`fixtures/auth.ts#loginAs` 在无 storageState 文件时会自动回退到真实 UI 登录，已用 `docker exec cpq-jh-postgres psql` 手工确认 admin 账号 `locked_until` 为空可正常登录）、`launchOptions.executablePath` 指向系统 `/usr/bin/chromium-browser`（snap，`--no-sandbox --disable-dev-shm-usage`），其余项（baseURL/viewport/locale/timeout）与仓库默认 config 逐项一致。**未改动仓库内任何文件**，本次 `git status` 除 `test.md` 外无 diff。
>
> `quotation-flow.spec.ts` 跑了 **2 遍**（确认是否是本机偶发抖动而非真实基线），两遍失败数、失败用例、错误消息**逐字节完全一致**——判定为稳定可复现的基线，非 flaky。

| spec | 基线结果 | 失败签名 | 判定口径 |
|------|---------|---------|---------|
| `quotation-flow.spec.ts` | **3 total / 0 passed / 3 failed / 0 skipped**（两次独立运行结果逐字一致） | 见下方三条 | 相对基线无新增失败 |
| `composite-product-flow.spec.ts` | **1 total / 0 passed / 0 failed / 1 skipped** | `test.skip(true, 'task-0712 F5 明细表重构后本 spec 选择器/Tab 命名全部过时，待按文件头注释重写(见 dev-docs/task-260712-选配模板和报价单选配功能/)')`（文件 `e2e/composite-product-flow.spec.ts:106`，静态跳过，与代码改动无关） | 与基线一致（仍 1 skipped）即为通过；本 spec 在当前 HEAD 上无法验证组合产品渲染路径 |

**`quotation-flow.spec.ts` 三条失败的完整签名原文**（两次运行逐字相同）：

1. **`报价单流程: 苏州西门子 + 报价模板0608 v1.10 + 10110002(渲染层无回归)`**（e2e/quotation-flow.spec.ts:132）
   ```
   TimeoutError: locator.click: Timeout 15000ms exceeded.
   Call log:
     - waiting for locator('.ant-select-item-option').filter({ hasText: '西门子' }).first()

     52 |   }
     53 |   const opt = optionText || search;
   > 54 |   await page.locator('.ant-select-item-option').filter({ hasText: opt }).first().click();
        |                                                                                  ^
     55 |   await page.waitForTimeout(400);
     56 | }
       at selectByLabel (e2e/quotation-flow.spec.ts:54:82)
       at e2e/quotation-flow.spec.ts:167:3
   ```
   失败于 Step1「客户」下拉搜索「西门子」，15s 内未出现匹配 option（未走到产品选配/报价单卡片渲染阶段，与本任务改动的 pending 改写 / 缓存维度路径无关）。

2. **`TC-F1: 打开 DRAFT 报价单不自动发 refresh-card-snapshot`**（e2e/quotation-flow.spec.ts:448）
   ```
   Error: 编辑态 Step1(客户/模板已锁定预填)下一步应可点

   expect(locator).toBeEnabled() failed

   Locator:  getByRole('button', { name: /下一步/ }).first()
   Expected: enabled
   Received: disabled
   Timeout:  15000ms

   Call log:
     - 编辑态 Step1(客户/模板已锁定预填)下一步应可点 with timeout 15000ms
     - waiting for getByRole('button', { name: /下一步/ }).first()
       19 × locator resolved to <button disabled type="button" title="请先填写产品分类和报价模板" class="ant-btn ...">…</button>
          - unexpected value "disabled"

       at e2e/quotation-flow.spec.ts:474:61
   ```

3. **`TC-F2: 显式刷新才触发 refresh-card-snapshot`**（e2e/quotation-flow.spec.ts:507）
   ```
   Error: 编辑态 Step1(客户/模板已锁定预填)下一步应可点

   expect(locator).toBeEnabled() failed

   Locator:  getByRole('button', { name: /下一步/ }).first()
   Expected: enabled
   Received: disabled
   Timeout:  15000ms

   Call log:
     - 编辑态 Step1(客户/模板已锁定预填)下一步应可点 with timeout 15000ms
     - waiting for getByRole('button', { name: /下一步/ }).first()
       19 × locator resolved to <button disabled type="button" title="请先填写产品分类和报价模板" class="ant-btn ...">…</button>
          - unexpected value "disabled"

       at e2e/quotation-flow.spec.ts:539:60
   ```

**与 `docs/RECORD.md` 历史记录的比对**：TC-F1 / TC-F2 的签名（`title="请先填写产品分类和报价模板"` 导致「下一步」disabled）与 RECORD.md 2026-06-30 / 2026-07-01 / 2026-07-15 / 2026-07-16 / 2026-07-23 多次独立会话记录的**同一根因逐字一致**——`QuotationCreateForm.tsx` 产品分类异步默认值回填的 stale closure（2026-07-15 记录定位）与编辑态 `categoryId` 反查 effect 时序问题的叠加，是**独立于本任务（pending 改写 / 缓存维度）之外的既有夹具缺口**，非本次基线新引入。用例 1（主流程）本次卡在更早的「客户下拉搜索」阶段（此前记录卡在「产品选择 10110002」或「Step1 下一步 disabled」阶段），失败点比历史记录更靠前，但**总数与总体判定不变**（3 failed / 3 total），且两次本机运行完全一致，怀疑与本机 chromium（非官方 google-chrome）渲染/网络时序差异有关，不构成新增回归判据。

> 背景：`docs/RECORD.md` 记载 2026-07-23（task-0722）时 `quotation-flow.spec.ts` 在**本分支与干净基线上各跑一次均 3 failed**，同一夹具签名（「请先填写产品分类和报价模板」），判定为与业务无关的已知环境缺口。把「全部 passed」当硬门禁在这种基线下**不可判定** —— 故需求方决策改为以基线实测为准（这是让标准可判定，不是降低标准）。「补 E2E 夹具缺口」登记为独立待办。
>
> **T0 结论**：本次实测**与历史记载口径一致**（3 failed / 3 total，同一「请先填写产品分类和报价模板」根因族），验证了「相对基线无新增失败」这套判定口径在当前 HEAD 上确实可执行、不是空判定。**验收口径建议**：`quotation-flow.spec.ts` 采用「相对基线（3 total/0 passed/3 failed，签名如上）无新增失败」；`composite-product-flow.spec.ts` 采用「维持 1 skipped（skip 原因不变）」。task-0725 修复完成后重跑，若新增第 4 个失败、或失败签名脱离上述三条已知根因族，才判定为回归。TC-CORE 系列 / TC-AC17 系列等业务断言仍应作为独立硬门禁（不受本 E2E 环境缺口影响）。
| TC-E2E-3 | `'加载中' final count = 0` |
| TC-E2E-4 | 全部 8 Tab `'加载中'=0` |
| TC-E2E-5 | 截图证据：qf-19 + qf-21~28 共 **9 张** |

> 跳过 E2E = 跳过自检。根因 1 本身就属 AP-31 / AP-37 族（不报编译错、不报运行时错、只是静默空表），**TS 检查与 API 探活看不到**。

---

## 10. 回归清单（不得破坏）

| # | 项 |
|---|---|
| R-1 | `SqlViewExecutorPendingHookTest` 三个既有用例**不改一行**仍全绿 |
| R-2 | `SqlViewIsolationBoundaryTest` 全绿（未动 `SqlViewRuntimeContext` 语义） |
| R-3 | `ComponentDriverServiceCacheKeyTest` 更新后全绿 |
| R-4 | `QuotePendingRewriter` 既有单测全绿（`mask` 改委派后行为不变） |
| R-5 | `BatchExpandSnapshotPrefetchEquivTest` / `ComponentDriverGvarBatchEquivTest` / `EligibleForQuoteBucketTest` / `S3SegmentProfileTest` 全绿 |
| R-6 | Excel 导出 / 报价单导出不受影响 |
| R-7 | `FormulaEvaluateResource:119` 的 Excel 公式路径语义不变（本期不碰） |
| R-8 | 无新增 Flyway 迁移；`flyway_schema_history` 无新记录 |

---

## 11. 已知不在本期修（测出来不算 Bug）

| 现象 | 说明 |
|------|------|
| 跨客户同成品的根客户按 `customer_no` 字母序取 | 树 SQL 无 `:customerCode` 占位符，task-0721 follow-up，已建议进 Backlog |
| BOM 页签不参与 B5 回填 | `bom_view` 含顶层 `UNION ALL` → `QuotePendingRewriter.hasTopLevelSetOp` 安全降级 `anchorInjected=false` → 不注入 `__v6_id`，仅只读展示。**设计内降级，非缺陷** |
| 加工费只 1 行（不含来料固定加工费 2 行） | `price_type='PROCESS'` 是**有意口径**，已与需求方确认 |
| 存量已物化的空 `quote_card_values` 不自动重算 | 需求 §6 决策：DRAFT 单由用户点「**刷新基础数据**」（`refresh-snapshot`）重建。⚠️ **不是** saveDraft（走增量、对空数组是 no-op） |
| 工序反填仍读正式数据、不看本单 pending | BL-0073，2026-07-25 已划清边界。修复后会出现「页签/Excel 显示本单待生效数据、工序反填填入正式数据」的**同单双来源**现象，**有意保留** |
| 联动 Excel 公式路径的 gating 与 driver 路径不同 | `FormulaEvaluateResource:119-120` 已传真实 quotationId+status，该路径现在就对 DRAFT 单开启 pending 改写。修复后系统有两套 gating 语义，本期**不统一**（已登记待办） |
| 启动日志 `missing table [mat_composite_process]` | V361 已重命名该表，实体 + `TableRegistry:40` 未同步，task-0723 退役收尾遗漏，已建议进 Backlog |
