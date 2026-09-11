# 子代理回报 · 测试片 S-C（方案② 与 `_record`）

> 📌 **先落盘、后分析**（`subagents.md §1⑥`）。逐字留档。
> **落盘时间**：2026-09-10（agent 用时约 33.7 分钟 / 85 次工具调用）
> **认领**：AC-10、11、13、14、16、18、19、20　**造数前缀**：`T260910C-`

## 结论

**5 通过 / 4 未验证 / 1 阻塞**，`T260910C-` 残留 **0**（两库均已核对）。

> 被测代码 = 本 worktree 的**在途实现**（HEAD 仅文档提交，实现全在未提交改动里；后端/前端代理正在并行编辑）。库 = `cpq_db_test`（`\conninfo` 实测 `test` profile 连它，非 `cpq_db_0724` —— BL-0232 确认）。

---

## 1. 逐条 AC（断言 → 实际值，逐字）

| AC | 结果 | 用例:行号 | 断言原文 → 实际值 |
|---|---|---|---|
| **AC-10** | ✅ | `RecordOnlyWriteAcTest.java:56` | ③ `ds_quote_material=1`/`ds_quote_customer_part=1` → **1/1**；② `_record` 有行且 `quotation_id`=本单 → **mbom_rec=1 / ebom_rec=2**；① 主表零新增 → **`ds_quote_material_bom=0` / `ds_quote_element_bom=0`**。料号 `1022-2609000001`，单 `3e8eca03-…` |
| **AC-14①** | ✅ | `RecordRatioAcTest.java:53` | `material_ratio` 分别 `70.000000000000`/`30.000000000000` 且不许 NULL → 实际 **`[70.000000000000, 30.000000000000]`**（2 行）。AC-14② 属核价通过 ⇒ **S-全局 片，本片不声称验过** |
| **AC-16②** | ✅ | `RecordDedupAcTest.java:112` | 两次触发（选配提交 + 报价单提交 200）后本单重复分组 → **mbom_rec 1 行/0 组、ebom_rec 2 行/0 组**，行数 1→1 未累加 |
| **AC-16①** | 🔴 **阻塞** | `RecordDedupAcTest.java:76` | 第 2 次同输入提交（触发指纹复用以造出「同料号 2 个 line item」）**返 500** ⇒ **夹具前置造不出来，去重断言未执行**。根因见 §3 缺陷① |
| **AC-18 ①~⑤** | ✅ | `BindExistingMaterialAcTest.java:47` | 响应 `fingerprintMatched=false`/`reusedHfPartNos=[]`/`productType=SIMPLE`；② `quotation_line_item` 1 行且 `product_part_no_snapshot=T260910C-BIND546f2`；① `customer_part` +1、`source='MANUAL'`；③ `ds_quote_material` 1→1；④ 主表 2→2 / 2→2、`_record` 0→0 / 0→0；⑤ `sel_part_signature` 0→0 |
| **AC-19** | ✅ | `BindExistingMaterialAcTest.java:229` | 409 + `CUSTOMER_PRODUCT_NO_TAKEN` → 实际 `409 {"code":409,…,"data":{"code":"CUSTOMER_PRODUCT_NO_TAKEN"},"detail":{"bizCode":"CUSTOMER_PRODUCT_NO_TAKEN"}}`；该编号仍 **1 行** |
| **AC-18⑥** | ⛔ **未验证** | `BindExistingMaterialAcTest.java:161` | 卡片渲染既有 BOM 非空 → `quotation_line_component_data` **0 条**，三条服务端入口全 200 但都不物化。见 §5 限制A |
| **AC-11** | ⛔ **未验证** | `CardRenderFromRecordAcTest.java:81` | 同上。前提已成立（主表 0 行 / `_record` 1 行 / `template_id` 已补 `875a5c9f…`），但物化 0 条 ⇒ **判不出红绿** |
| **AC-13** | ⛔ **未验证** | `RecordOnlyWriteAcTest.java:173` | 日志时序 → 提交期间全部 **5 条**日志里只有 `ds-record` 侧标记，`snapshot` 侧**一个标记都不存在**。见 §5 限制B |
| **AC-20** | ⛔ **未执行** | `cpq-frontend/e2e/t260910c-sc.spec.ts` | 纯 UI 断言，需临时 vite + 临时后端；前端 `BindExistingPartPanel.tsx` 仍在编辑中 |

---

## 2. 交付物（绝对路径，未提交）

- `.../cpq-backend/src/test/java/com/cpq/task260910c/`（**6 文件 1454 行**：`Task260910CBase` + 5 个 AC 用例类）
- `.../cpq-frontend/e2e/t260910c-sc.helpers.ts` 与 `t260910c-sc.spec.ts`（AC-11 UI + AC-20，`tsc` 通过）
- 隔离构建副本（可直接复跑）：`/tmp/claude-1000/-home-joii-project-cpq/54527263-…/scratchpad/iso-sc-050648`，日志 `run-final2.log`

复跑：`cd <ISO>/cpq-backend && ./mvnw -o test -Dtest='com.cpq.task260910c.*Test' -Dquarkus.http.test-port=8113`

---

## 3. 发现的缺陷与环境事实（只报根因 + 证据，未动任何实现文件）

### 缺陷① 🔴 阻塞 AC-16，且会打红 AC-21/AC-22（S-B 片）
```
【现象】选配第 2 次同输入提交（指纹复用路径）→ 500 Internal server error
【预期】AC-16 前置需要「同料号 2 个 line item」；AC-21 要求命中复用
【证据】ConfigureProductService.buildReusedProductInfo:1808 → configureBySelection:2101
        SQL: SELECT mm.material_name, mm.specification, mm.dimension, mm.unit_weight,
               (SELECT min(sps.created_at) FROM sel_part_signature sps WHERE sps.quote_part_no = mm.material_no)
             FROM v_compat_material_master mm WHERE mm.material_no = ?
        ERROR: relation "v_compat_material_master" does not exist（SQLState 42P01）
【根因方向】S-2「选配侧 4 个引用点归零」漏了 buildReusedProductInfo 这个点；
           且 api.md §2.4 已裁定删除的 unitWeightGrams 就源自这里的 mm.unit_weight
【影响】严重 —— 但主线在 8081/cpq_db_0724 上亲验【看不到它】（见环境事实②）
```
📌 **与 S-B 片、后端① 三方独立报出同一处**。

### 缺陷②（需主线裁决：AC 与实现偏离）
> `_record` 的写入改由**新增文件 `DsRecordDirectWriter`** 直写，日志原文：
> `[ds-record][direct] ds_quote_material_bom_record 单=… 整组覆盖 —— 删 0 行、写 1 行（主表零改动，方案② / D-7；origin_id 一律 NULL = 回填时按新增追加）`
> 同时**原挂点仍在打** `[ds-record] quotation=… 命中 1 个轴值但无组件数据，跳过`。
>
> ⇒ 看起来 **B-13「调换 `syncRecordsForFlow` / `snapshotLines` 顺序」并未实施**，而是换了一条直写路径绕过组件数据依赖。两点要主线裁决：
> ① AC-13 是否已被别的方式满足（若是，AC 文本要改）；
> ② **`origin_id` 被硬编码 NULL** —— 对新铸料号正确（D-7「本来就没有这组 BOM」），但 **D-9「按 `origin_id` + 按列 COALESCE」与 AC-17「不许整组翻倍」都依赖主表有行时能认领 `origin_id`**。

### 缺陷③（AC 不可执行，要求澄清）
> AC-16② 原文「`ds_quote_element_bom_record` **同上**」字面不可执行 —— 只读查 `information_schema.columns`：该表**没有 `input_material_no` 列**（粒度列是 `element_code`，另有 `material_part_no`）。按该表**实际行粒度**取 `element_code` 并在代码里显式登记，但请主线确认这就是本意。

### 🚨 环境事实②（最需要主线知道的一条）—— 两库的组件/视图基线不同

| | `cpq_db_0724`（默认 profile，**主线亲验**） | `cpq_db_test`（`mvnw test`） |
|---|---|---|
| `v_compat_*` 视图 | **3 张全在** | **0 张** |
| `component_sql_view` 段数 | **99** | **42** |
| `template_component` 行数 | **146** | **78** |

> ⇒ **缺陷① 只在测试库炸、在主线亲验的库上静默通过**。判「测试红 vs 亲验绿」的分歧时必须先看这张表，🚫 不要归因成「测试环境问题所以忽略」—— **这一次测试库才是对的**（V439 已把兼容视图判为应退役）。

### 环境事实③（共享基座清理缺口，S-全局 片必撞）
> 任何调 `POST /{id}/submit` 的用例会产生 `costing_order` 行，而 `SelConfigAcTestBase.restoreFixtures` **不清它** ⇒ `DELETE FROM quotation` 撞 `costing_order_quotation_id_fkey` ⇒ 整个清理事务回滚 ⇒ 报出来的却是「**还原自检：sel_part_signature 仍有 … 的残留**」。
> 已在**自己的**基座里补 `clearSubmitArtifacts()`（命中面只限本片 `quotation_id`），🚫 没有改共享基座。**S-全局 片要 submit + 核价通过，请把这条转告它。**

### 环境事实④（harness 危险，第三次独立确认）
> 另一个代理（跑 `Ac2122FingerprintRegressionTest`，PID 58855）在**同一个 worktree** 跑 maven，共享 `target/` ⇒ 第一次 `mvnw test` 得到一整屏 `cannot find symbol: ImportContext / SheetRow / …` 的**假故障**。建议每个测试代理都用隔离副本。

### 文档口径偏差
> 需求文档 AC-14 写「该列填充率仅 12.2%」—— 两库实查 `_record.material_ratio` 是 **0.5%（49/9636）**，主表 `ds_quote_material_bom` 是 **96.9%**。对 AC 结论无影响（目标行改动前确实 NULL），但数字请更正。
>
> 📌 **主线注**：12.2% 是「真实边行」口径（49/401），0.5% 是「全表含合成树根行」口径（49/9636）—— 两个数都对，但 AC 里没写清是哪个口径，属我的表述缺陷。

---

## 4. 三个证伪实验（FT-1 / FT-3 / FT-5）—— 均未执行，设计如下

> **不能改实现代码** ⇒ 下面是设计与所需干预，请主线执行或授权。**每次实验前必须先确认干预真的生效**（读到 `Restarting quarkus` 或重跑 `mvnw test` 让改动进 classpath，否则读数作废）。

**FT-1（AC-16 去重）** —— 阳性对照**已在改动前的只读数据上成立**：
> 两库逐字一致，复现单 `08c99680-1359-4b1b-be98-7b93aabc71e4` 的 `S0001`：`id=9645 origin_id=12013` 与 `id=9646 origin_id=NULL`，同 `(item_seq=1, input_material_no=S0002)`，`created_at` 逐字相同；`item_seq=2` 同形。
> **但这只证明缺陷存在过，不证明我的夹具能触发它。**
>
> 步骤：① **先修缺陷①**（否则夹具造不出前置）；② revert S-6 去重修复；③ 跑 `RecordDedupAcTest`；④ **必须变红**且明细里出现「一份认领 `origin_id`、一份 NULL」。不变红 = 用例白测。

**FT-3（AC-14 `material_ratio`）** —— 已有**改动前基线**（该列填充率 0.5%，本片目标行改动前必为 NULL；改动后实测 `70/30`）。
> 步骤：revert S-5 投影补全 → 跑 `RecordRatioAcTest` → **必须变红**（`(NULL)`）。当前 PASS 已是**有基线支撑的 PASS**，但仍需这一步把「S-5 真的在起作用」钉死。

**FT-5（AC-11 冻结时序）** —— ⚠️ **目前跑不了**：
> AC-11 本身尚未验证（限制A），**红绿基线不存在，FT-5 无从「必须变红」**。
> 前置：先让 AC-11 能判红绿（补一条编辑页卡片渲染的可观测入口，或跑 E2E）。
> 另外：按缺陷②，**B-13 可能压根没实施**，那 FT-5 的干预对象需要主线先确认是哪一段代码。

---

## 5. 「未验证」项与子代理需要什么（逐字）

- **限制A（AC-11 / AC-18⑥）**：三条服务端入口（`GET /quotations/{id}`、`PUT /{id}/draft`、`POST /{id}/ensure-card-values`）**全返 200 但 `quotation_line_component_data` 恒 0 条**（已排除 BL-0202：`template_id` 补成了含两个目标页签的 PUBLISHED 模板 `875a5c9f…`）。既有测试 `CardValuesRecomputeStableTest` 类注释也登记过同型未解现象。
  **需要**：① 后端告知「编辑页卡片实时渲染」走的接口（不读实现，`api.md` 未列）；或 ② 授权起临时后端(8117)+临时 vite(5187) 跑 `t260910c-sc.spec.ts`。
- **限制B（AC-13）**：`snapshotLines` **没有任何日志标记**（提交期间全部 5 条日志已在失败信息里全量打印）。**需要**：该处日志标记原文，或改用断点/调用序号断言；否则 AC-13 只能靠 FT-5，而 FT-5 又被限制A 卡住。
- **AC-20**：未执行（前端在途 + 需临时端口授权）。
- **不属本片、未声称验过**：AC-12 / AC-15 / AC-17 / AC-23（S-全局）；AC-14②（核价通过后主表）；AC-1~AC-9 / AC-21 / AC-22（S-A / S-B）。

---

## 6. 子代理规避掉的坑（逐字要点）

1. **不用 `CUST-0004` / `S0004` 造数** —— 那是共享客户，往它名下增删等于动别片和真人的作用域；改用自造客户 + `T260910C-` 前缀的等价料号（顺带补上 `S0004` 元素侧 0 行的短板：现网 `S0004` 只有 3 行 `material_bom`、**0 行 `element_bom`**，用它 AC-18⑥ 的元素侧无从验起）。
2. **`_record` 无 `quotation_id` 外键**（只读查证：`referential_constraints` 对两张 `_record` 表零行）⇒ 删报价单**不级联**；子类 `@AfterEach` 先于父类执行，在报价单被删前先清 `_record`。
3. **所有 `_record` 断言按 `quotation_id` 收窄** —— 全库同一条判据 SQL 有 **3763 个重复分组 / 5610 条多余行**（存量），拿它当判据会**永远红且红得像本次回归**。
4. **断言前先断言非空**：AC-10 按 ③→②→① 排序（「主表零新增」在提交失败时同样成立）；AC-18 全部用 before/after 基线；AC-16 先硬断言「两行是同一料号」，否则退化成空验证。
5. **三处失败先分清是不是自己的用例缺陷**（都是，已改）：响应载荷在**根层**不在 `data` 下（`data.fingerprintMatched` 取到 null 曾误报红）、两个夹具漏挂产品分类、`element_bom_record` 无 `input_material_no` 列。
6. **端口**：全程 8113（`ss` 实测 8081/8097/5174 均被占，8097 还被另一 worktree 的 dev server 和另一代理的 test-port 同时用着）。
7. 未执行 `git commit`；未做核价通过；无 DROP / TRUNCATE / 无 WHERE 的 DELETE；**未读任何被点名禁止的实现目录**（缺陷诊断只用了测试运行日志的栈帧与 SQL 原文）。
