# backtask · 移除主数据维护「料号核价」

> 后端只按本文件做。AC 原文在 `需求文档.md §③`，**本文件只标编号不复制原文**。
> 🚨 **本任务不含任何 DDL、不含任何写库语句、不新增迁移文件。** 见 B-0。

---

## B-0 · 红线（先读，违反即停）

| 🚫 禁止 | 说明 |
|---|---|
| **任何 `DROP` / `TRUNCATE` / `DELETE` / `UPDATE`** | `CLAUDE.md §3.2` 不可逆红线。V6 表**仍在给核价单渲染供数**（`task-260819` 裁决 `N-16`），删表 = 打掉在用数据 |
| **新增 Flyway 迁移** | 本任务不改 schema。迁移号是三方争抢的移动靶（已同型撞车 6 次），无谓新增只会再制造一次 |
| **跑任何清库型测试** | `mvnw test` 直接写共享开发库 `cpq_db_0724`（`CLAUDE.md` profile 表）。本任务的 AC-12 要靠改动前后 md5 相等来守数据零变化，**A/B 两次采样之间跑写库测试会自己毁掉自己的判据** |
| **删 `com/cpq/basicdata/v6/pricing/` 下任何文件** | 用户 2026-09-07 明确裁决保留 P01~P24 handlers 及 `PricingImportService` / `PricingTemplateService` / `PricingHandlerCatalog`。它们是 V6 表**唯一写入实现** |

遇到任何看起来需要越过上面四条的情况：**停下来报主线，不要自行判断。子代理没有红线批准权。**

---

## 任务分解

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **B-1** | AC-4, AC-6, AC-7 | **删维护端整包**：物理删除 `cpq-backend/src/main/java/com/cpq/basicdata/v6/maintenance/` 整个目录（`PricingBasicDataMaintenanceResource` + `PricingMaintenanceService` + `PricingSheetRegistry` + `PricingSheetDef` + `dto/` 下 10 个 DTO，合计 14 个文件 / 1765 行）。<br>✅ **删除前已由主线实查确认**：全工程对该包的外部引用**只有注释**，唯一真 `import` 在测试类 `MasterDataLayoutEndpointsTest`（由 B-4 处理）。若你发现第二个真 import → **停下来报主线**，说明主线的清点有漏 |
| **B-2** | AC-5, AC-7 | **删两个导入端点**：`com/cpq/basicdata/v6/resource/BasicDataImportV6Resource.java` —— 删 `importPricing()` 方法（`@Path("/pricing")`）、`pricingTemplate()` 方法（`@Path("/pricing/template")`）、`@Inject PricingImportService pricingService` 与 `@Inject PricingTemplateService pricingTemplateService` 两个字段、对应的两条 `import`、类 javadoc 里 `/pricing` 那一行路由说明。<br>🚫 **同类里的 `importQuote()`、`createQuotation()` 与 `@Path("/{recordId}")` 查询端点必须原样保留** —— AC-5 的反向对照验 `GET /{recordId}` 带实查现存 id 返 **200**，证明「删的是方法不是整个类」。<br>🚨 **并发撞车（2026-09-07）**：`task-260907-报价导入建单切ds新表` 的 B-10 要把**同一个类**的 `importQuote()` / `createQuotation()` 改为返 **410**。⇒ ① 你**一个字节都不许碰这两个方法**，它们的去留归对方；② 合并前先看 `dev-docs/INDEX.md §0.5` 里 `BasicDataImportV6Resource.java` 一行的撞车状态（实测该文件命中 **6 个任务**）；③ 若拉取到的版本里这两个方法已返 410，**那是对方的改动，照原样保留，不要"修复"它** |
| **B-3** | AC-6（反向对照） | **给保留下来的两个 service 加防误删标注**：在 `PricingImportService` 与 `PricingTemplateService` 的类 javadoc 顶部各加一段：<br>「⚠️ **2026-09-07 起无生产调用方**（`task-260907` 删除了 `/basic-data-import/v6/pricing` 端点），仅由 `PricingTemplateServiceTest` / `PricingVersioningImportE2ETest` / `Task0812DisabledSheetsTest` 覆盖。**但它是 V6 表唯一的写入实现，🚫 不要按死代码清理** —— V6 表仍在给核价单渲染供数。」<br>纯注释改动，不改任何行为 |
| **B-4** | AC-6, AC-7, AC-14 | **测试树处置**：① 物理删 `test/java/com/cpq/basicdata/v6/maintenance/` 下三个类（`PricingMaintenanceServiceTest` / `PricingMaintenanceServiceSortFilterTest` / `PricingMaintenanceServiceMaterialNameJoinTest`）；② 改 `test/java/com/cpq/basicdata/v6/resource/MasterDataLayoutEndpointsTest.java` —— 删 `:4,5` 两条 import、`:41` 的 `@Inject PricingBasicDataMaintenanceResource`、`:95` 对 `/api/cpq/pricing-basic-data/parts` 的断言、`:133` 对 `maintenanceResource.parts(...)` 的调用；**该类里其余用例（主数据其他端点的版式断言）保留**。<br>③ 🚫 **不删** `PricingTemplateServiceTest` / `PricingVersioningImportE2ETest` / `Task0812DisabledSheetsTest` —— 它们测的是 B-0 保留的 pricing 包 |
| **B-5** | AC-7 | **改写 12 处指向已删类的注释（A0 岜路 2 · 方案甲）**：`basicdata/v6/BomCharacteristic.java:7`、`dataset/dto/DsSheetMeta.java:14`、`dataset/fingerprint/ValueNormalizer.java:22`、`dataset/importer/MasterDataChecker.java:30`、`dataset/registry/ColumnDef.java:14,144`、`dataset/registry/DatasetRegistry.java:12`、`dataset/registry/DatasetSchemaSelfCheck.java:24`、`dataset/service/DatasetMaintenanceService.java:40,41,217`、`dataset/versioning/VersionedGroupWriter.java:29`、`priceadjust/dto/ComparisonColumnDef.java:7`、`dataset/resource/DatasetImportResource.java:44`、`dataset/resource/DatasetMaintenanceResource.java:24,30,67,132`、`test/.../PrecisionScaleConsistencyTest.java:28`、`test/.../EnsureCardValuesSkipInProgressGuardTest.java:35`。<br>**改写规则**：保留教训文字，把具名引用换成不指向已删类的描述。例：<br>「`PricingSheetRegistry` 类注释自陈的『双写漂移』就是前车之鉴」<br>→「历史上核价维护端的声明式镜像与导入器 handler 双写，曾发生过静默漂移（该实现已于 2026-09-07 随 `task-260907` 移除）」<br>🚫 **不整段删** —— `DatasetSchemaSelfCheck` 等类「为什么存在」的设计理由全在这些注释里 |
| **B-6** | AC-10, AC-14 | **后端自检**：① `cd cpq-backend && ./mvnw -q compile` exit 0；② 停掉已有 8081 进程后**冷启动** `./mvnw quarkus:dev`，日志出现 `migrations validated`，**无** `UnsatisfiedResolutionException` / `DeploymentException` / `ClassNotFoundException`；③ `curl -s --noproxy '*' -o /dev/null -w '%{http_code}' http://localhost:8081/api/cpq/components` → **401**。<br>⚠️ 探本机服务**必须加 `--noproxy '*'`**（本机 shell 常设 `http_proxy=127.0.0.1:7890`，不加会走代理返 502）；⚠️ `/q/health` 返 404 是因为没装 smallrye-health，**它不是健康探针**，别拿它判断死活 |
| **B-7** | AC-17 | **接口总账回写**：`dev-docs/main-api.md` —— 删除「#### 核价基础数据导入（同步）」整节（`POST /api/cpq/basic-data-import/v6/pricing`，约 `:4947~4960`）+ 更新文件头日期说明注明本次来源任务。<br>✅ **主线已实查**：总账**从未登记** `/pricing-basic-data/*` 七个端点与 `/pricing/template`，故本次**只删一节，无新增无覆盖**。🚫 不要"顺手补齐"那七个端点再删 —— 补了再删是无谓动作。<br>🚨 **相邻节误删风险（并发会话 2026-09-07 提供的行号，已复核）**：该文件里三节紧挨着 —— `:4911` 报价基础数据导入（`/v6/quote`）· **`:4948` 核价基础数据导入（`/v6/pricing`）← 只删这一节** · `:4963` 由导入记录创建报价单（`/v6/quote/create-quotation`）。**前后两节归 `task-260907-报价导入建单切ds新表`，它会把它们改成 410**。⇒ 删除时**按小节标题精确定位**，🚫 不许按行号范围整段切；删完 `/usr/bin/grep -an 'v6/quote'` 必须仍能命中 `:4911` 与 `:4963` 两处 |

---

## 双向覆盖自检

**正向 —— 每条后端相关 AC 都有人认领：**

| AC | 认领 |
|---|---|
| AC-4 维护端点 404 + 反向对照 | B-1 |
| AC-5 导入端点 404 + 反向对照 | B-2 |
| AC-6 代码物理删除 + 反向对照 | B-1, B-3, B-4 |
| AC-7 零残留引用 | B-1, B-2, B-4, B-5 |
| AC-10 冷启动 | B-6 |
| AC-14 构建 | B-4, B-6 |
| AC-17 文档回写 | B-7 |

> AC-1/2/3/8/9/16 由前端认领（见 `fronttask.md`）；AC-11/15 由测试认领（见 `test.md`）；
> **AC-12（V6 数据零变化）与 AC-13（核价渲染零回归）由主线亲验认领** —— 它们是「证明没碰」，需要跨改动前后两次采样，不属于任一子代理的工作区。

**反向 —— 每个 B-x 都指回 AC：** B-1→AC-4/6/7 · B-2→AC-5/7 · B-3→AC-6 · B-4→AC-6/7/14 · B-5→AC-7 · B-6→AC-10/14 · B-7→AC-17。**无孤儿项。**
（B-0 是红线约束不是交付项，不参与覆盖统计。）

---

## 🚫 明确不做

1. 🚫 **不动 `com/cpq/basicdata/v6/pricing/` 下任何文件的逻辑**（B-3 只加注释）
2. 🚫 **不动 `com/cpq/dataset/` 下任何代码逻辑**（B-5 只改注释）
3. 🚫 **不删 `BasicDataImportV6Resource` 整个类**，只删两个方法
4. 🚫 **不重构、不"顺手清理"其他死代码** —— 本任务的删除边界由 AC 划定，超出的一律记 `BACKLOG.md`
5. 🚫 **不补 `main-api.md` 里 `/pricing-basic-data/*` 的缺失登记**（B-7 已说明）
6. 🚫 **不改 `application*.properties`、不改共享配置**

---

## 已知坑

| 坑 | 说明 |
|---|---|
| **`grep` 是 `ugrep -I` 别名** | 会把中文注释多的 Java 文件**静默判为二进制返空**。清点引用一律用 `/usr/bin/grep -a`，据别名 grep 的空结果下「无引用」结论是假绿 |
| **worktree 里 `mvnw` 在 `cpq-backend/` 不在根** | 在主仓跑会测错树，得到与 worktree 无关的绿 |
| **删 java 源文件后 `target/` 会留旧 class** | 会造成 CDI `UnsatisfiedResolutionException` 或反过来「删了还能跑」的假象。B-6 冷启动前先 `./mvnw -q clean` 或至少确认 `target/classes` 下对应 class 已消失 |
| **迁移号撞车（已同型 6 次）** | 若启动报 `Detected applied migration not resolved locally: NNN`，**第一步永远是** `SELECT script FROM flyway_schema_history WHERE version='NNN'` 问「这个号是谁的」。本任务不新增迁移，撞上的一定是并发任务线的 —— 把对方文件按**未跟踪副本**借入本 worktree（不 `git add`）。🚫 不伪造文件、🚫 不 `flyway repair`、🚫 不改共享 `application-test.properties` |
