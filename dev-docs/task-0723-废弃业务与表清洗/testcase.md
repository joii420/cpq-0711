# 测试用例 · task-0723 废弃业务与表清洗

> 依据：同目录 `需求说明.md`（§4 审计事实 / §8 验收标准 / §9.5 决策）+ `api.md` + `backtask.md` + `fronttask.md`
> 性质：**本波只设计用例，不执行**。执行留待前后端 7 阶段开发完成后，由测试工程师按本文档逐条跑并回填「实际结果」列。
> 环境：后端 `localhost:8081`（curl 一律加 `--noproxy '*'`）；前端 `localhost:5174`；DB `PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db`；grep 一律用 `/usr/bin/grep -a`（ugrep 坑，见 `docs/RECORD.md` 记忆 `cpq-grep-ugrep-binary-pitfall`）。
> 编号规则：A=退役验证，B=改读V6正确性，C=保留功能无回归，D=三重验证配套。每条用例含：编号 / 对应需求点 / 前置条件 / 步骤 / 预期结果 / 验证方式。「实际结果」「通过/失败」两列留空，执行时填。

---

## 0. 用例 ↔ 需求 §8 验收标准 对照表

| §8 验收标准 | 原文 | 覆盖用例 | 备注 |
|---|---|---|---|
| 1 | 【止血】前端「V5 增强导入」按钮摘除后，全站无 UI 入口触达 import-session/staging 写 mat_* | A-01, A-02, A-04, A-06 | — |
| 2 | 【漂移】修复后 `referenced_versions` 从 V6 采集非空；能真报 `hasDrift=true` | **B-06, B-07（改口径）** | ⚠️ **与 §9.5 Q4 决策矛盾，详见下方「文档内部矛盾」** |
| 3 | 【客户料号】现役 V6 料号客户料号名/图号/产品编号非空，导出 Excel 有值 | B-01, B-02, B-03 | 已由 §9.5 官方改口径为「取数正确」，本文档按新口径设计 |
| 4 | 【版本锁】`part_version_locked` 反映真实版本，不再恒 2000 | **B-08（改口径）** | ⚠️ **与 §9.5 Q6 决策矛盾，详见下方「文档内部矛盾」** |
| 5 | 【模板/视图清理】DROP 7 模板 + 4 视图后，所有 PUBLISHED 活模板渲染无破坏；旧视图无残留引用报错 | C-07, D-01, D-03, D-04 | — |
| 6 | 【财务单据】254 张历史核价单打开回看逐字节不变 | C-05, C-06 | — |
| 7 | 【回归】`quotation-flow.spec.ts` E2E 全绿，`'加载中' final count=0`；报价/核价单主流程无回归 | C-04, D-05 | 判定口径＝A/B 同型新增失败数 0，非字面「全绿」，详见 D-05 |
| 8 | 【最终】mat_* 各表无任何活代码引用，可安全 DROP | D-01, D-02 | 本期只改名不真 DROP，用例验证"改名后无报错"等价于"可安全 DROP" |

### ⚠️ 文档内部矛盾（需 PM/技术总监明确口径，测试工程师职责范围内提出，不擅自裁决）

1. **验收标准 2（漂移检测）与 §9.5 Q4 决策直接矛盾**：原验收标准要求"漂移检测生效、能报 hasDrift=true"，但 Q4 官方决策是"漂移检测整体下线"（删 service + 横幅 + 端点）。下线后没有任何代码路径能产出 `hasDrift=true`，此验收标准**在决策生效后必然不可达**。§9.5 只写了"验收标准修正（§8 第 3 条…）"，**没有同步修正第 2 条**，属于文档遗漏。本文档按 Q4 决策改口径为 B-06/B-07（验证"漂移功能已下线、无残留副作用"），但建议 PM 在需求说明 §8 补一行同款修正说明，与第 3 条对齐格式。
2. **验收标准 4（版本锁）与 §9.5 Q6 决策同样矛盾**：原验收标准要求"不再恒 2000"，但 Q6 决策是"整族下线，`part_version_locked` 列保留（历史兼容）"。下线后不再有任何代码写这一列，它会**永远维持决策生效前的历史值**（138/138=2000），不存在"变成非 2000"的路径。本文档按 Q6 改口径为 B-08（验证"列被保留、值维持不变、版本切换功能整体不可达"）。同样建议 PM 在 §8 补修正说明。

> 上述两条不是本文档"发明"的新要求，而是把 §9.5 已经拍板的决策（Q4/Q6）如实映射成可执行验收动作；只是指出 §8 原文与 §9.5 决策之间缺一次文字同步，避免执行阶段有人拿着 §8 原文误判"漂移检测应该修复"而返工。

---

## A 组 · 退役验证（下线的东西真的没了）

### A-01 止血复核：全站无 UI 入口触达 import-session/staging 写 mat_*

- **对应需求点**：§8 验收 1，api.md §1
- **前置条件**：F1 已完成（`ImportHistoryList.tsx` 摘除按钮）
- **步骤**：
  1. 浏览器打开报价/导入历史相关页面，全站巡查一遍菜单、Tab、按钮（导入历史列表页、报价单创建页、报价单编辑页 Step1~5）
  2. 逐个记录是否存在任何可点击入口指向「V5 增强导入」「基础资料导入向导（旧版）」
  3. 对 `pages/importconfig/ImportHistoryList.tsx` 全文 Read，确认 `:146` 按钮、`:241` 向导挂载、`:34` import 均已删除
- **预期结果**：全站 0 处可点击入口能触达 V5/import-session 写路径；文件里对应代码块不存在
- **验证方式**：UI 手动巡查 + Read 源码

### A-02 下线端点全返 404（逐条 curl）

- **对应需求点**：需求说明 §5「退役（死端点）」+ api.md §8「下线端点汇总」
- **前置条件**：阶段 2~4 后端改动已完成，Quarkus 已重启
- **步骤**：对下表每一行执行 curl，记录实际状态码

| # | 方法 | 端点 | curl 命令 | 期望 |
|---|---|---|---|---|
| 1 | POST | `/quotations/{id}/refresh-versions` | `curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8081/api/cpq/quotations/00000000-0000-0000-0000-000000000000/refresh-versions` | 404 |
| 2 | GET | `/part-version/{cpn}/{hf}` | `curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:8081/api/cpq/part-version/CPN001/HF001` | 404 |
| 3 | GET | `/part-version/{cpn}/{hf}/fingerprint` | `curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:8081/api/cpq/part-version/CPN001/HF001/fingerprint` | 404 |
| 4 | POST | `/part-version/propose` | `curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' -X POST -H 'Content-Type: application/json' -d '{}' http://localhost:8081/api/cpq/part-version/propose` | 404 |
| 5 | POST | `/part-version/{cpn}/{hf}/apply` | `curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' -X POST -H 'Content-Type: application/json' -d '{}' http://localhost:8081/api/cpq/part-version/CPN001/HF001/apply` | 404 |
| 6 | PUT | `/part-version/{cpn}/{hf}/switch/{version}` | `curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' -X PUT http://localhost:8081/api/cpq/part-version/CPN001/HF001/switch/2001` | 404 |
| 7 | POST | `/part-version/admin/wipe-basic-data` | `curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8081/api/cpq/part-version/admin/wipe-basic-data` | 404（⚠️见下方专项提醒） |
| 8 | POST | `/import/basic-data/v5/preview` | `curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8081/api/cpq/import/basic-data/v5/preview` | 404 |
| 9 | POST | `/import/basic-data/v5/confirm` | `curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8081/api/cpq/import/basic-data/v5/confirm` | 404 |
| 10 | POST | `/quotations/{id}/reimport-basic-data` | `curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8081/api/cpq/quotations/00000000-0000-0000-0000-000000000000/reimport-basic-data` | 404 |
| 11 | POST | `/import-session/upload` | `curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8081/api/cpq/import-session/upload` | 404 |
| 12 | PUT | `/import-session/{id}/decisions` | `curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' -X PUT http://localhost:8081/api/cpq/import-session/00000000-0000-0000-0000-000000000000/decisions` | 404 |
| 13 | POST | `/import-session/{id}/commit` | `curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8081/api/cpq/import-session/00000000-0000-0000-0000-000000000000/commit` | 404 |
| 14 | DELETE | `/import-session/{id}`（cancel） | `curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' -X DELETE http://localhost:8081/api/cpq/import-session/00000000-0000-0000-0000-000000000000` | 404 |
| 15 | GET | `/costing-part/process-cost` | `curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:8081/api/cpq/costing-part/process-cost` | 404 |
| 16 | GET | `/costing-basic/versions` | `curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:8081/api/cpq/costing-basic/versions` | 404 |
| 17 | GET | `/costing-summary/{id}` | `curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:8081/api/cpq/costing-summary/00000000-0000-0000-0000-000000000000` | 404 |
| 18 | GET | `/costing-templates/{id}` | `curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:8081/api/cpq/costing-templates/00000000-0000-0000-0000-000000000000` | 404 |
| 19 | GET | `/quotations/{id}/costing-sheet` | `curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:8081/api/cpq/quotations/00000000-0000-0000-0000-000000000000/costing-sheet` | 404 |
| 20 | GET | `/element-prices/available-elements` | `curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:8081/api/cpq/element-prices/available-elements` | 404（条件项，见下） |

- **⚠️ #7 专项提醒**：`PartVersionResource.wipeBasicData()`（源码 `:128-136`）是一个**无角色门、无二次确认的破坏性管理端点**——一次调用会清空报价基础数据+报价单。api.md/backtask 只笼统写了 `part-version/**` 通配删除，**没有单独点名这个端点**，属于最容易被"删了 Resource 主体但漏查还有没有别的地方复用同一套清空逻辑"的风险点。必须确认：① 该方法本身随 `PartVersionResource` 整体删除；② `service.wipeBasicData()`（`PartVersionService` 内）没有被其他任何活代码（比如测试夹具清理脚本、CI seed 脚本）单独复用——若被复用，删 `PartVersionService` 会连带炸掉那条链路，需要测试工程师在阶段 3 结束后额外跑一次全仓 grep `wipeBasicData` 确认调用方清单。
- **#20 专项提醒**：`listAvailableElements()` 已在 task-0722 确认**改读 element 主表**（非 mat_bom），需求说明 §4.2 把它归为"死代码"是指**页面孤儿**（`ElementPriceCenterPage` 0 菜单命中），不是"读错表"。执行前先确认 task-0722 update-0724 是否已经删除该端点/页面（`git log` 查该分支合并记录），若已删则本条标记「已在前置任务完成，本任务复核即可」，不要重复计入本任务工作量。
- **验证方式**：curl，逐行记录实际状态码

### A-03 保留端点仍存活（A-02 配对验证，防误伤）

- **对应需求点**：api.md §8「保留清单」
- **前置条件**：同 A-02
- **步骤**：

| # | 端点 | curl 命令 | 期望（非 404） |
|---|---|---|---|
| 1 | `GET /quotations/{id}` | `curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:8081/api/cpq/quotations/00000000-0000-0000-0000-000000000000` | 401 或 404（业务级 not-found，非路由级 404——需人工核对响应体是 JSON 业务错误而非 Quarkus 默认 404 页） |
| 2 | `GET /quotations/{id}/comparison` | 同上加 `/comparison` | 401（鉴权）非路由 404 |
| 3 | `POST /quotations/{id}/comparison/export` | 同上 POST | 401 非路由 404 |
| 4 | `POST /element-price/import` | `curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8081/api/cpq/element-price/import` | 401/400，非 404 |
| 5 | `basicdata.v6` 任一端点（如 Q01 handler） | 具体路径以 `basicdata.v6` 包路由为准，联调时现取 | 401，非 404 |
| 6 | 前端菜单 `/costing-summary` | 浏览器打开 `http://localhost:5174/costing-summary` | 正常渲染 `CostingOrderListPage`，非空白/非 404 |

- **⚠️ 关键点**：本条要区分"路由级 404"（Quarkus 找不到该 `@Path`，说明端点是真的没了）和"业务级 404/401"（路由存在但业务逻辑判定资源不存在或未鉴权）。用假 UUID 会命中"业务上不存在"，需要看响应体格式（有 `code`/`message` 结构化 JSON = 端点还活着；纯文本/HTML "RESTEASY003210" 之类 = 路由真的不存在）区分，不能只看状态码数字。
- **验证方式**：curl + 响应体人工核对

### A-04 前端下线路由不可达

- **对应需求点**：§8 验收 1，fronttask F6
- **前置条件**：F3/F5 已完成
- **步骤**：浏览器直接访问以下 URL

| URL | 期望 |
|---|---|
| `http://localhost:5174/part-versions` | 404 / 空白 / 重定向到首页（不渲染 `PartVersionPage`） |
| `http://localhost:5174/costing-templates` | 同上（不渲染 `CostingTemplateList`） |
| `http://localhost:5174/costing-templates/00000000-0000-0000-0000-000000000000` | 同上（不渲染 `CostingTemplateConfig`） |
| `http://localhost:5174/costing-part-data` | 同上（不渲染 `CostingPartDataPage`） |
| `http://localhost:5174/element-price-center` | 同上（若已被 task-0722 update-0724 先删，标记「前置任务已覆盖」） |

- **验证方式**：浏览器手测 + `router/index.tsx` Read 确认路由项已删

### A-05 后端废弃符号全工程 0 残留（分类 grep 清单）

- **对应需求点**：§8 验收 8，backtask 各阶段"验证"小节 + B9 交付说明必含行
- **前置条件**：阶段 2~6 全部完成
- **步骤**：按下表逐类执行 grep，记录命中数与命中内容

| 类别 | grep 命令 | 期望命中 |
|---|---|---|
| 漂移检测 | `/usr/bin/grep -a -rn "DriftDetection\|collectReferencedVersions\|referencedVersions" cpq-backend/src/main/java/` | 0（若 `referenced_versions` 列的实体字段声明保留，允许出现 1 处纯字段声明，非 service 调用） |
| 版本族 | `/usr/bin/grep -a -rn "PartVersionService\|PartVersionResource\|PartVersionPredicateBuilder" cpq-backend/src/main/java/` | 0 |
| V5/import-session | `/usr/bin/grep -a -rn "ImportSession\|BasicDataImportServiceV5\|FieldMetaCache\|import-session\|basic-data/v5" cpq-backend/src/main/java/` | 0（`ParsedBasicData` 若被 V6 复用需单独确认保留原因） |
| 旧核价引擎 | `/usr/bin/grep -a -rn "CostingPartData\|CostingSummaryResource\|CostingBasicData\|CostingTemplateService\|CostingTemplateResource\|CostingSheetService" cpq-backend/src/main/java/` | 0（`CostingSheetResource` 文件本身应仍存在，但类内不应再有 `getCostingSheet` 方法——单独核查这一条，见下） |
| CostingSheetResource 方法级摘除核查 | `/usr/bin/grep -a -n "getCostingSheet\|getComparison\|exportComparison" cpq-backend/src/main/java/com/cpq/costing/resource/CostingSheetResource.java` | 只剩 `getComparison`/`exportComparison` 两个方法，`getCostingSheet` 0 命中 |
| ComponentService 护栏摘除核查 | `/usr/bin/grep -a -n "assertNotReferencedByCostingTemplate" cpq-backend/src/main/java/com/cpq/component/service/ComponentService.java` | 0 |

- **验证方式**：grep + 人工核对每类命中内容是否为"注释残留"（可接受）还是"活代码调用"（不可接受，视为 bug）

### A-06 前端废弃符号全工程 0 残留（分类 grep 清单）

- **对应需求点**：fronttask F6 交付说明必含行
- **前置条件**：F1~F5 全部完成
- **步骤**：

| 类别 | grep 命令 | 期望命中 |
|---|---|---|
| 版本族 UI | `/usr/bin/grep -a -rn "PartVersion\|partVersion\|part-version" cpq-frontend/src/` | 0（`part_version_locked` 若在类型定义里作字段名允许保留） |
| V5/import-session UI | `/usr/bin/grep -a -rn "ImportSession\|BasicDataImportV5\|OrphanRowsSection\|CustomerConflictSection\|importSessionService" cpq-frontend/src/` | 0（`QuotationCreateForm` 若确认是活的创建入口，只删其对 import-session 的依赖，文件本身保留——单独核查，见下） |
| 漂移 UI | `/usr/bin/grep -a -rn "quotationDriftService\|MOCK_DRIFT_RESULT\|VITE_USE_MOCK_DRIFT\|driftDetection\|hasDrift" cpq-frontend/src/` | 0 |
| 旧核价孤儿页 | `/usr/bin/grep -a -rn "CostingTemplateList\|CostingTemplateConfig\|CostingPartDataPage\|costing-templates\|costing-part-data" cpq-frontend/src/` | 0 |
| QuotationCreateForm 边界核查 | `/usr/bin/grep -a -n "BasicDataImportV5ToQuotation\|import-session" cpq-frontend/src/pages/quotation/QuotationCreateForm.tsx` | 0（若该文件仍存在且是活的报价创建入口，只要求它不再依赖 import-session，文件本身不删） |

- **验证方式**：grep + 人工核对 `QuotationCreateForm` 这类"名字像 V5 但实际是活功能"的边界情况（PM/需求说明 §10 明确警告过这类误判）

### A-07 主仓合并后编译验证（.class 残留坑）

- **对应需求点**：backtask B6 末尾"删 Java 源文件后的坑"
- **前置条件**：worktree 分支已合并回主仓 master
- **步骤**：
  1. 切回主仓 `cd cpq-backend`
  2. `./mvnw clean test`（**必须 `clean`**，否则 `target/` 残留已删类的 `.class` 会导致 CDI `UnsatisfiedResolutionException`——worktree 里绿、主仓合并后炸）
  3. 观察启动日志无 `UnsatisfiedResolutionException`/`DeploymentException`
- **预期结果**：`clean test` 全绿，无 CDI 解析异常
- **验证方式**：命令行 + 日志核查（记忆 `task0709-update0723-quote-import-template` 里记录过同类坑）

### A-08 前端 TS/Vite 自检

- **对应需求点**：fronttask F6
- **前置条件**：F1~F5 完成
- **步骤**：
  1. `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json` → 必须 0 错误
  2. 对每个改动的 `.tsx`（`ImportHistoryList.tsx`/`QuotationStep2.tsx`/`QuotationWizard.tsx`/`router/index.tsx`）逐个 `curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:5174/src/<相对路径>` → 必须 200
- **预期结果**：TS 0 错误，全部改动文件 Vite 200
- **验证方式**：命令行

### A-09（技术总监审核补充）· basic_data_config 废弃配置置 INACTIVE 验证

- **对应需求点**：api.md §5.3、backtask B7.1（原用例集遗漏，审核时补）
- **前置条件**：阶段 5 迁移已应用
- **步骤**：
  1. 迁移前后各查一次：`SELECT status, count(*) FROM basic_data_config WHERE target_table LIKE 'mat\_%' OR target_table LIKE 'costing\_%' GROUP BY status;`
  2. 确认迁移后这批（约 58 条 ACTIVE）status 全部变为 `INACTIVE`，且**总行数不变**（是置停用不是删行，保留追溯）
  3. 置 INACTIVE 后，走报价单主流程 + PUBLISHED「核价通用模板」渲染，确认无因这批配置失效导致的报错/渲染异常
- **预期结果**：58 条全 `INACTIVE`、行保留、无功能依赖它们报错
- **验证方式**：SQL 前后对比 + UI 回归（补充映射到 §8 验收 5）

---

## B 组 · 改读 V6 正确性

### B-01 客户料号命中场景取数正确性（正例）

- **对应需求点**：§8 验收 3（改口径「取数正确」）、api.md §2.2、backtask B2
- **前置条件**：B2 改造完成
- **步骤**：
  1. SQL 直查基准值：
     ```sql
     SELECT c.code, v.material_no, v.customer_material_name, v.customer_product_no, v.customer_drawing_no
     FROM material_customer_map v JOIN customer c ON c.code = v.customer_no
     WHERE c.code = 'CUST-1292' AND v.material_no = '6666677';
     -- 已知基准行：customer_product_no='8059407475', customer_drawing_no='1', customer_material_name 为空
     ```
  2. 找一张客户为 CUST-1292、含料号 `6666677` 的报价单（或临时构造一张）
  3. 打开该报价单，查看产品卡片客户料号名/图号/产品编号三字段
  4. 调用 `GET /api/cpq/quotations/{id}` 核对返回 JSON 对应字段
- **预期结果**：卡片显示值与步骤 1 SQL 基准值逐字段一致（`customer_material_name` 为空则显示空，不是「加载中」或旧值残留）
- **验证方式**：SQL 直查 + UI 截图 + API 响应对照

### B-02 客户料号未命中场景显示为空（反例，防兜底降级）

- **对应需求点**：backtask B2「全键严格匹配，不兜底」
- **前置条件**：同 B-01
- **步骤**：
  1. 构造/找一张报价单，客户 A 下含料号 X，且 `material_customer_map` 中料号 X 只在客户 B 名下有记录（即 `(customer_no=A, material_no=X)` 无命中，但 `(customer_no=B, material_no=X)` 有命中）
  2. 打开客户 A 的这张报价单
- **预期结果**：三字段显示为空，**不得**因为"仅按 material_no 找到了客户 B 的记录"就错误带出客户 B 的数据（这正是森萨塔跨客户串号的复现路径，记忆 `sensata-crosscustomer-is-repair2`）
- **验证方式**：SQL 构造 + UI 核对；这是**回归防护类反例，优先级最高**，务必执行

### B-03 客户料号导出 Excel 单元格值一致性

- **对应需求点**：§8 验收 3「导出 Excel 对应单元格有值」
- **前置条件**：同 B-01
- **步骤**：
  1. 用 B-01 的报价单，调用导出 Excel 端点（`GET /quotations/{id}/export-excel-view` 或等效端点，具体以联调时实际路径为准）
  2. 打开导出的 Excel，定位客户料号名/图号/产品编号对应单元格
- **预期结果**：单元格值与 B-01 SQL 基准值一致，非空白/非公式错误
- **验证方式**：文件下载 + 人工打开核对

### B-04 提交快照客户料号块取数正确性

- **对应需求点**：backtask B4，`SnapshotCollectorService`
- **前置条件**：B4 改造完成
- **步骤**：
  1. 用 B-01 场景报价单执行 `submit` 提交
  2. 查提交后生成的冻结快照（`submission_snapshot` 或等效字段）中"客户料号映射"块
- **预期结果**：快照里客户料号名/图号/产品编号与 `material_customer_map` 提交当下的值一致（快照冻结特性——之后 `material_customer_map` 再变，此快照不应跟着变，需另开一个后续变更场景验证冻结隔离性，若时间充裕可加做）
- **验证方式**：SQL 查快照字段 + 对照

### B-05 森萨塔跨客户串号回归防护（专项边界用例）

- **对应需求点**：记忆 `sensata-crosscustomer-is-repair2`（历史真实事故）+ backtask B2 纪律
- **前置条件**：同 B-01
- **步骤**：
  1. 复用历史森萨塔场景的数据模式：同一 `material_no` 被两个不同客户共享占用（`material_customer_map` 里同料号跨 `customer_no` 出现多行）
  2. 分别打开这两个客户各自名下含该料号的报价单
- **预期结果**：两张单各自显示**各自客户**名下的客户料号信息，互不串号（即使 material_no 相同）
- **验证方式**：SQL 构造双客户同料号数据 + 双单分别打开核对；**这是本任务复杂度最高的回归风险点，B-02 是简化版，本条是完整版，两条都要跑**

### B-06 漂移检测下线 - 前端 UI 层面

- **对应需求点**：§9.5 Q4 决策（整体下线），api.md §2.1，fronttask F2
- **前置条件**：F2 完成
- **步骤**：
  1. 打开任意报价单编辑页 Step2
  2. 检查页面是否存在漂移横幅（原位置约 `QuotationStep2.tsx:3508` 附近）
  3. 检查是否存在"刷新版本"按钮（原位置约 `:3438`）
  4. 检查浏览器 Network 面板，确认打开报价单/存草稿/提交过程中**不再有** `refresh-versions` 请求
- **预期结果**：无横幅、无刷新按钮、Network 面板 0 次 `refresh-versions` 调用
- **验证方式**：UI 手测 + 浏览器 F12 Network 面板

### B-07 漂移检测下线 - 后端行为与 DTO 契约

- **对应需求点**：backtask B1
- **前置条件**：B1 完成
- **步骤**：
  1. 调用 `GET /quotations/{id}` 检查返回 JSON 是否还含 `driftDetection` 字段（若保留需恒为 `{hasDrift:false, driftedRecords:[]}`，若删除则前端不应有任何读取残留——与 A-06「漂移 UI」grep 结果对照）
  2. 存草稿、提交一张报价单，观察 Quarkus 日志无 `DriftDetectionService` 相关调用痕迹/异常
  3. 确认 `quotation.referenced_versions` 列仍存在于表结构中（`\d quotation`），但不再被写入新值
- **预期结果**：DTO 字段处理与前端 A-06 结果一致（不留悬空契约）；无异常日志；列结构保留
- **验证方式**：curl + psql `\d quotation` + 日志

### B-08 part_version_locked 列保留值不变 + 版本族功能整体不可达

- **对应需求点**：§9.5 Q6 决策 + §8 验收 4（改口径）
- **前置条件**：B3 完成
- **步骤**：
  1. `SELECT count(*), count(*) FILTER (WHERE part_version_locked <> 2000) FROM quotation_line_item;`（改名/下线前后各跑一次）
  2. 打开报价单编辑页，确认版本抽屉（`PartVersionDrawer`）、版本决策列表（`PartVersionDecisionList`）、报价内切版本入口（约 6 处）均已不可见/不可点击
  3. 结合 A-02 #2~#7 确认对应端点 404
- **预期结果**：`part_version_locked` 列存在且总数/分布与下线前完全一致（因为不再有代码写它，值被"冻结"在历史状态，**不是**"变成了真实版本号"）；UI 层版本切换入口全部消失
- **验证方式**：SQL 前后对比 + UI 巡查 + 端点 curl（复用 A-02）

---

## C 组 · 保留功能无回归（命名撞名保护，最易误伤）

### C-01 财务核价工作台 `/costing-summary` 正常

- **对应需求点**：api.md §4「命名撞名陷阱」，需求说明 §4.3
- **前置条件**：阶段 4 完成
- **步骤**：
  1. 浏览器打开 `http://localhost:5174/costing-summary`
  2. 确认渲染的是 `CostingOrderListPage`（新引擎财务工作台），列表能展示（基准：286 单活跃，执行时实际数字以当时库为准，允许增长）
  3. 打开其中一张核价单详情，确认能正常展示、提交流程可走
- **预期结果**：财务工作台完全正常，与 `CostingSummaryResource`（旧引擎，已下线）无关联报错
- **验证方式**：UI 手测

### C-02 比对视图（task-0717）正常

- **对应需求点**：backtask B6「全保留」清单
- **前置条件**：阶段 4 完成（`CostingSheetResource` 只删 `getCostingSheet`）
- **步骤**：
  1. 打开一张报价单的比对 Tab
  2. 调用 `GET /quotations/{id}/comparison`，确认 200（非鉴权失败）
  3. 执行导出比对 `POST /quotations/{id}/comparison/export`，确认能正常下载
- **预期结果**：比对视图渲染正常，连线配置/3行块/双色阈值均无回归（task-0717 交付的核心特性）
- **验证方式**：UI 手测 + curl + 文件下载核对

### C-03 V6 正式导入 `basicdata.v6` 正常

- **对应需求点**：backtask B5「保留」清单，前置核查阻塞项
- **前置条件**：阶段 3 完成
- **步骤**：
  1. 在 `QuoteBasicDataImportV6Drawer` 里选一张基础资料 Excel（Q01~Q17/P01~P23 任一 sheet）正常导入
  2. 确认导入结果写入 V6 表（`material_master`/`material_bom_item`/`element_bom_item`/`unit_price` 等），`import_record` 有新记录
  3. 用 `codegraph_trace` 复核该 Drawer 的后端落点确实是 `VersionedV6Writer`，不经过已删的 `ImportSessionService`/`StagingWriter`/`BasicDataImportServiceV5`
- **预期结果**：导入成功，落点验证通过
- **验证方式**：UI 实际导入 + SQL 核对新记录 + codegraph_trace

### C-04 报价单主流程 Golden Path

- **对应需求点**：§8 验收 7，backtask B8 第 2/3 重探针的业务动作之一
- **前置条件**：全部 7 阶段完成
- **步骤**：新建 → 打开 → Step1~5 填写 → 存草稿 → 提交 → 打开详情页 → 导出 Excel，全程观察 8 个 Tab
- **预期结果**：全流程无报错，8 Tab 全部正常渲染，`'加载中'` 计数最终为 0（沿用 CLAUDE.md 强制 E2E 判定口径）
- **验证方式**：UI 手测（可复用 Playwright `quotation-flow.spec.ts` 的断言逻辑做人工核对参照）

### C-05 254 张历史核价单打开回看逐字节不变

- **对应需求点**：§8 验收 6
- **前置条件**：阶段 5（视图改名+全局变量停用）完成
- **步骤**：
  1. 改名/停用前，对 254 张有 `frozen_dto` 的历史核价单，抽样（建议 ≥20 张，覆盖不同金额区间/客户/时间跨度）导出或截图当前渲染结果，或直接对 `frozen_dto` 字段做 checksum（如 `md5(frozen_dto::text)`）建基线
  2. 阶段 5 迁移应用后，重新打开同一批单据
  3. 对比渲染结果 / `frozen_dto` checksum
- **预期结果**：逐字节不变——checksum 完全一致，UI 渲染无肉眼可见差异
- **验证方式**：SQL checksum 前后对比（`SELECT id, md5(frozen_dto::text) FROM costing_order WHERE frozen_dto IS NOT NULL` 改名前后各跑一次并 diff）+ 抽样 UI 核对

### C-06 32 张无 frozen_dto 核价单打开走实时计算不受影响

- **对应需求点**：§9.5「无缓存核价单风险面」新增发现（32 张，07-22/23 测试单）
- **前置条件**：阶段 5（第 2 层价格视图改名 + 死全局变量停用）完成
- **步骤**：
  1. `SELECT id FROM costing_order WHERE frozen_dto IS NULL;` 找出这批单（数量可能随时间变化，执行时实测）
  2. 逐张打开，观察是否因为 `v_costing_element_price`/`v_costing_material_price`/`v_costing_exchange_rate` 被改名、`ELEM_PRICE`/`MAT_PRICE`/`EXCHANGE_RATE` 被停用而报错或渲染异常
- **预期结果**：这批单是**第 2 层删变量前的真实风险点**（需求说明 §9.5 原文），必须逐张打开确认无报错、无空白，而不是只抽样——因为它们走实时计算，是唯一可能真正读到这 3 张视图/3 个变量的活代码路径
- **验证方式**：UI 逐张手测（数量不多，建议全量而非抽样）；**这是本组风险最高的一条，务必不要漏测**

### C-07 PUBLISHED 核价通用模板渲染无破坏

- **对应需求点**：§8 验收 5
- **前置条件**：阶段 5 完成（7 模板 + 4 视图改名）
- **步骤**：
  1. 打开当前 15 单在用的「核价通用模板 v1.1」（PUBLISHED 状态）
  2. 新建一张核价单使用该模板，走完整渲染流程
  3. 确认组件管理里引用该模板的字段/公式配置正常加载（无「未知逻辑名」类报错，除非是阶段 6 SchemaContext 删映射后**故意**产生的显式报错——那属于预期行为，需与"模板本身破坏"区分开）
- **预期结果**：模板渲染 0 破坏；若配置层有历史遗留指向已删 BNF 映射的路径，应看到**显式**报错（而非静默空值），这是符合预期的信号
- **验证方式**：UI 手测 + 日志核查

### C-08 命名撞名代码级/数据级双检

- **对应需求点**：api.md §4「命名撞名陷阱」，fronttask F5「命名撞名」，backtask B6
- **前置条件**：阶段 4 完成
- **步骤**：核对下表每一对，确认「删」的确实被删、「留」的确实被留

| 撞名对 | 应删 | 应留 |
|---|---|---|
| Java 类 | `CostingSummaryResource`/`CostingSummaryService`（旧引擎） | `CostingOrder*`（财务工作台） |
| 数据表 | `costing_summary`/`costing_summary_result`/`costing_summary_override`（改名 `_drop`） | `costing_order`（保留原名） |
| 前端页面 | `CostingTemplateList`/`CostingTemplateConfig` | `CostingOrderListPage` |
| 前端路由 | `/costing-templates` | `/costing-summary`（菜单"核价管理"） |
| SchemaContext 映射 | "电镀方案"→`plating_plan` | "电镀费用"→`plating_fee`（V6 活表，保留） |
| 表改名 | `plating_plan`（3 行冻结） | `plating_fee`（V6 活表，不改名） |

- **预期结果**：表内每一对都是"左删右留"，无一处连坐误删/漏删
- **验证方式**：codegraph_search 逐个符号核对 + `\dt` 核对表名 + 路由 grep

### C-09 组件删除功能不受护栏拆除影响

- **对应需求点**：backtask B6「ComponentService 护栏拆除」
- **前置条件**：`assertNotReferencedByCostingTemplate()` 已删（A-05 已核查代码层面）
- **步骤**：
  1. 尝试删除一个当前未被任何模板引用的组件，确认删除成功
  2. 尝试删除一个仍被 PUBLISHED 模板引用的组件，确认**其他**护栏逻辑（非核价模板相关的）仍然生效，正确阻止删除
- **预期结果**：拆掉核价模板护栏后，组件删除的正常业务规则（被模板引用不可删）不受影响——只是不再检查"是否被已废弃的 costing_template 引用"这一项
- **验证方式**：UI 手测组件管理页删除操作

### C-10 报价单/核价单/详情页三视图客户料号字段一致性

- **对应需求点**：AP-50 同类历史坑位（详情页/编辑页渲染层 single-source 反模式）+ 本任务 B-01 场景延伸
- **前置条件**：B-01 完成
- **步骤**：用 B-01 同一张报价单，分别打开「编辑页」「详情页（只读）」「导出 Excel」三个渲染入口，核对客户料号三字段
- **预期结果**：三处值完全一致，不出现"编辑页有值、详情页空白"这类历史上真实发生过的渲染层不同源问题
- **验证方式**：三处 UI 截图对照

---

## D 组 · 三重验证配套（技术总监执行，本组给可操作用例）

### D-01 第 1 重 · 静态审计（37 候选表逐张核实）

- **对应需求点**：§8 验收 8，backtask B8 第 1 重
- **前置条件**：阶段 2~6 全部完成（表改名前）
- **步骤**：对 backtask B7.3 列出的 37 张候选表逐张执行：
  1. `/usr/bin/grep -a -rn "\b<表名>\b" cpq-backend/src/main/java/`，记录命中行；命中若为"注释"或"已删模块残留字符串"（应已在阶段 2~6 清完）以外的内容，判定该表**不可**改名
  2. 对有 `@Table` 注解的实体（如 `CostingPartMaterialBom` 对应 `costing_part_material_bom`），额外跑 `codegraph_impact` 确认调用边为 0
- **预期结果**：37 张表逐张给出「可改名」/「不可改名+残留消费方清单」两态判定，产出一张完整核对表（交付材料的一部分）
- **验证方式**：grep + codegraph_impact，逐表记录（建议用表格产出，含"表名/grep命中数/codegraph_impact边数/判定"四列）

### D-02 第 2 重 · pg_stat 运行时探针（决定哪些表真正进改名迁移）

- **对应需求点**：backtask B8 第 2 重，这是"改名迁移表清单基于此结果编写"的硬约束
- **前置条件**：D-01 完成，阶段 7 迁移**尚未编写**
- **步骤**：
  ```sql
  -- 1. 清零
  SELECT pg_stat_reset();
  ```
  2. 跑一遍完整业务动作 checklist（缺一不可，覆盖面直接决定探针可信度）：
     - 报价单：新建 → 打开一张既有单 → 存草稿 → 提交 → 导出 Excel → 打开比对视图
     - 核价单：打开财务工作台列表 → 打开一张单 → 提交
     - V6 导入：完整导一张基础资料表
     - 模板渲染：打开 PUBLISHED「核价通用模板」新建一张核价单走完整渲染
     - **补充（原 checklist 未提及但应覆盖）**：打开 C-06 场景里那批无 `frozen_dto` 的核价单（这批最可能意外命中价格视图/死变量）
  3. 查扫描计数：
  ```sql
  SELECT relname, seq_scan, idx_scan
  FROM pg_stat_user_tables
  WHERE relname LIKE 'mat\_%' OR relname LIKE 'costing\_part%'
     OR relname LIKE 'costing\_summary%' OR relname IN
     ('costing_template','costing_sheet','plating_plan','costing_element_price',
      'costing_material_price','costing_exchange_rate','costing_price_version')
  ORDER BY seq_scan + COALESCE(idx_scan,0) DESC;
  ```
- **预期结果**：`seq_scan=0 AND idx_scan=0` 的表才写入阶段 7 迁移；`scan≠0` 的表列出残留消费方，暂不改名
- **验证方式**：SQL 探针，产出「37 表 scan 计数快照」作为交付材料附件

### D-03 视图改名"透明性"陷阱验证（阶段 5 必须先于阶段 7）

- **对应需求点**：需求说明 §9.5 Q2「PostgreSQL RENAME 不破坏视图」注意事项
- **前置条件**：阶段 5 视图改名迁移已应用
- **步骤**：
  1. 尝试查询旧视图名（如 `SELECT * FROM v_costing_summary_full LIMIT 1;`）
  2. 确认返回 `relation "v_costing_summary_full" does not exist`（而不是继续正常返回数据）
  3. 反向验证：在阶段 7 表改名**之前**，先查一次改了名的第 1 层视图（现在叫 `v_costing_summary_full_drop`）能否正常执行 `SELECT * FROM v_costing_summary_full_drop LIMIT 1;`——应该能，因为视图内部仍指向未改名的底表（此时底表还没到阶段 7）
- **预期结果**：① 旧视图名查询报错（验证信号生效）；② 改名后的视图仍能正常查询底表数据（证明 RENAME 是安全的纯改名，不丢数据）
- **验证方式**：psql 直接执行两条 SQL

### D-04 第 3 重 · 改名后回归

- **对应需求点**：backtask B8 第 3 重
- **前置条件**：阶段 7 迁移已应用
- **步骤**：
  1. 应用阶段 7 改名迁移
  2. `touch cpq-backend/src/main/java/com/cpq/CpqApplication.java` 强制 Quarkus 重启，等 5-7 秒（清 `ImplicitJoinRewriter`/`CachedSqlCompiler`/`CachedPathParser` 进程级缓存）
  3. 重跑 D-02 的同一套业务动作 checklist
  4. 观察 Quarkus 启动+运行期日志，`grep -i "does not exist"` 或 `relation.*does not exist`
- **预期结果**：日志 0 处 `relation "xxx" does not exist`；若出现，说明 D-01/D-02 漏判了某张表的活消费方，需要回退该表改名（`ALTER TABLE xxx_drop RENAME TO xxx`）并回到阶段 2~6 补漏
- **验证方式**：日志 grep + 业务动作复测

### D-05 E2E quotation-flow A/B 同型对比

- **对应需求点**：§8 验收 7，backtask B8 第 3 重步骤 4，fronttask F6
- **前置条件**：阶段 7 完成
- **步骤**：
  1. 在**干净 master**（不含本任务改动）跑一次 `npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list`，记录基线失败用例名单（已知恒 3 失败，夹具单缺产品分类，记忆 `task0712-update071501-category-axis`）
  2. 在本任务分支（阶段 7 完成后）跑同一 spec，记录失败用例名单
  3. 逐条比对两份失败用例名单
- **预期结果**：**新增失败数 = 0**（不是要求全绿，而是本分支失败用例名单 ⊆ 基线失败用例名单）；同时确认 `'加载中' final count = 0`
- **验证方式**：Playwright 输出对比，产出「基线 vs 本分支失败用例名单」对照表作为交付材料

### D-06 Flyway 迁移成功性 + 版本号纪律核验

- **对应需求点**：backtask B7 版本号纪律，B9 自检
- **前置条件**：阶段 5/7 迁移已应用
- **步骤**：
  1. `PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db -c "SELECT version, success, description FROM flyway_schema_history WHERE description LIKE '%task0723%';"`
  2. 确认阶段 5、阶段 7 两条迁移 `success=t`
  3. 确认版本号未与 task-0722 update-0724（`element_daily_price_log` 相关迁移）撞号——`SELECT version FROM flyway_schema_history WHERE version IN (<本任务用到的版本号>);` 应各只有 1 行
- **预期结果**：两条迁移均 `success=t`；无重复/冲突版本号记录
- **验证方式**：SQL 直查 `flyway_schema_history`

---

## 附录：回归测试清单（任一 bug 修复后必须重跑）

| 修复涉及的用例 | 必须联动重测 |
|---|---|
| B-01/B-02/B-05（客户料号 SQL） | B-03（导出）、B-04（快照）、C-10（三视图一致性） |
| B-06/B-07（漂移下线） | C-04（报价主流程）、D-05（E2E） |
| A-02（下线端点） | A-03（保留端点，防止误伤连坐） |
| A-05/A-06（符号 grep） | A-07/A-08（编译/TS 自检） |
| D-01/D-02（静态+探针判定的表清单） | D-03/D-04（改名后回归）——**任何表清单变动都必须重跑整个 D 组，不能只补测单表** |
| C-06（32 张无 frozen_dto 核价单） | C-05（254 张历史核价单 checksum）、D-02（探针，因为这批单是价格视图真实消费方） |

---

## 附录：Bug 报告格式提醒

发现问题按标准格式记录：
```
【现象】用户操作 + 实际结果
【预期】需求说明 §X / api.md §X + 期望结果
【复现】最小步骤（≤5 步）
【环境】接口/UI + 具体数据（表名/id/customer.code等）
【影响】阻塞/严重/一般/轻微
【建议】可能的根因方向（参照 A/B/C/D 组用例定位是退役不净/改读错误/回归破坏/验证疏漏 中的哪一类）
```
