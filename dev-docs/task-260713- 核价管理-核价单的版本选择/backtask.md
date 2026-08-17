# 后端任务文档 — 核价管理·核价单版本选择（task-0713）

> 权威依据：`需求说明.md` §「需求澄清纪要与设计定稿」+ `api.md`。
> 🔶 = 具体落点依赖 cpq-architect 对 D1/D2/D3 评审结论，评审后按结论施工。
> 技术栈：Quarkus 3 + Hibernate Panache + PostgreSQL 16；DB 配置驱动 SQL（`component_sql_view` / `costing_bom_tree_config`）。

---

## 0. 必读约束（开工前）

- `docs/方案制定前必读.md`、`docs/三大核心模块基线.md`（**核价单渲染基线**）、`docs/反模式.md` AP-31/37/51/53、`docs/配置方法论-合并版.md`。
- **禁 N+1、<3s**；守 `cpq-expand-layer-not-threadsafe`（expand/公式/快照层**禁并行**，返回缓存可变对象）。
- Flyway 交由 dev 启动自动 migrate；**勿手工 `psql -f`**。视图/DDL 变更后 `touch` java 强制重启。
- 版本列名各表不同，**统一约定输出列名 `view_version`**；真实版本列：`material_bom_item.bom_version` / `unit_price.version_no` / `element_bom_item.characteristic` / `capacity.version_no` / `plating_scheme.scheme_version`。

---

## B1 · 版本 override 表 + 实体（Flyway）

- 迁移 `V3xx__costing_order_version_override.sql`：
  ```sql
  CREATE TABLE costing_order_version_override (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    costing_order_id uuid NOT NULL REFERENCES costing_order(id),
    component_id uuid NOT NULL,
    part_no varchar(40) NOT NULL,
    view_version varchar(40) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_covo UNIQUE (costing_order_id, component_id, part_no)
  );
  CREATE INDEX idx_covo_order ON costing_order_version_override(costing_order_id);
  ```
- 实体 `CostingOrderVersionOverride`（Panache）。
- 生命周期 **B1**：新核价单不继承旧 override（`CostingFreezeService.createForSubmission` 不复制）。

## B2 · `:versionFilter` 宏（新，仿 `SpineKeysMacro` 但真正启用）

- 新类 `com.cpq.datasource.sqlview.VersionFilterMacro`：`:versionFilter(is_current列, 版本列, 料号键列)`。
  - `expandForExecution`（**渲染模式**）：展开为"该料号有 override → `版本列 = 注入的选定版本`；否则 → `is_current`"。按位绑定注入数组（料号→版本对），仿 `SpineKeysMacro` 的 `:__vfPart / :__vfVer` 占位符 + `SqlViewExecutor` 注入。**注意** `String.valueOf(null)` 陷阱（BL-0028）：绑定保留 SQL NULL。
  - `expandForListing`（**列出模式**，供下拉）：展开为 `TRUE`（放开版本过滤）。
  - `expandForValidation`（保存期 dry-run）：展开为对应 `is_current = true`（或 `TRUE`），不污染 required_variables。
- 接线：
  - 注入点 = `SqlViewExecutor.injectCostingTreeVars`（既有，`executeAllRows`+`executeJdbc` 两处都调）加兄弟注入 `:__vfPart/:__vfVer`。
  - **⚠️ 宏展开需新接进 `executeAllRows`/`queryRecursive`**：`SpineKeysMacro.expandForExecution` 现在**从未被调**（死的），别照抄"已接"的假设——versionFilter 必须真正在 SQL 构建期调 `expandForExecution|Listing`。
  - RENDER/LIST 模式经 `CostingTreeVarsContext.Vars`（新增 `mode` + `overrides` map + `listPartNo`）在**构建期**读（宏是静态改写，跑前就得决定展开成占位符还是 TRUE）。
  - `SqlViewValidator`（`:131/:138`）：镜像 spineKeys 3.5/3.6——保留 `:__vf` 前缀禁自定义 + `expandForValidation` 后 EXPLAIN dry-run（自动校验三个参数列在 $view 内存在）。
- 渲染展开形态（单表达式，无 N+1，override 与 is_current 二选一）：
  `EXISTS(unnest(:__vfPart,:__vfVer) k(p,v) WHERE 料号键 IS NOT DISTINCT FROM k.p AND 版本列 IS NOT DISTINCT FROM k.v) OR (料号键 <> ALL(:__vfPart) AND is_current列)`。数组绑定复用既有 null 保真通路（守 BL-0028）。

## B3 · 扩展 `CostingTreeVarsContext` 携带 override + 渲染贯通 view_version

- `CostingTreeVarsContext.Vars` 增加 `Map<(componentId,partNo), viewVersion> overrides` 与 `mode`（RENDER / LIST + listPartNo）。
- `CostingTreeRenderService.render` / `CardSnapshotService` 核价渲染：
  - 组装 override map 传入上下文；
  - 输出行透传 `view_version`（各 $view 已 `AS view_version`）到 `costingCardValues`，供前端识别可切换行；
  - **主树递归 SQL 版本感知（已定 = R1）**：`costing_bom_tree_config.sql_template` 把 `material_bom_item.is_current` 换成 `:versionFilter(...)`；`queryRecursive`（`CostingTreeRenderService:259`）按 `:__vfPart/:__vfVer` **占位符出现次数绑定**（照抄现有 `:production_part_nos` 出现次数绑定范式）。**⚠️ 最高风险 = 父节点选版→子件成员集变（拓扑变）；必须先用多版本数据(B9)做最小 spike 验证"父切版→子件集合变"跑通，再展开本项**。

## B4 · $view 配置改造（component_sql_view / 核价模板组件）

- 给核价模板"有版本列"的组件 $view 加两处（`需求说明.md` §C 示例）：① `本表版本列 AS view_version`；② `is_current` → `:versionFilter(...)`。
  - 子配件/主树：`material_bom_item.bom_version`
  - 工序 `gx_view`：`unit_price.version_no`
  - 材质 `cz_view`：`material_bom_item.bom_version`
  - 元素 `ys_view`：`element_bom_item.characteristic`（**零新增列**）
  - 组合工艺 `zh_view`：`capacity.version_no`
- 落地方式：改 DB `component_sql_view.sql_template`（config 驱动，可用 admin 端点或迁移种子）。改后走 `SqlViewValidator` dry-run 通过。

## B5 · 核价侧"live 重算 + 缓存"改造（D1 已落定）

- 目标：核价单打开时**报价侧读 frozen_dto 不变**；**核价侧改为按 override 从 V6 渲染并缓存**。
- **缓存落点（已定死）**：`costing_order` **新增两列** `costing_render jsonb`（`{lineItemId: {costingCardValues, costingExcelValues}}`）+ `costing_total_amount numeric(18,4)`。**绝不复用 `total_amount`**（那是含 Step3 折扣的报价总额）。
- **修空白（正手）**：`CostingFreezeService.createForSubmission` 在 `buildFrozenDto` 之前**先 `ensureCardValues(quotationId)` 物化 lazy NULL**（守 task-0712 materialize+flush 纪律），再把已算好的核价值组装进 `costing_render`。空白根因 = 冻结前 `costing_card_values` 还是 NULL（见需求说明「架构评审落定」）。
- **打开永远读缓存、绝不 on-open 重算**（守 BL-0010）：PENDING 打开读缓存、切版本时 live 重算回写；APPROVED/历史单只读冻结缓存（历史单 line item 已被重提删除、物理上无法 live 重算）。
- `getById`（`api.md §1`）：返回 `costingRender` + `costingTotalAmount` + `versionOverrides` + `editable`。
- 前端守 AP-50：按 lineItemId 并回 `costingCardValues` 字段，`ReadonlyProductCard` COSTING 分支一行不改。**不破坏** `CostingReviewPage` 现有 frozen 报价侧渲染。

## B6 · 版本下拉端点

- `GET /costing-orders/{coid}/version-options`（`api.md §2`）：列出模式跑该组件 $view + 料号限定，`distinct view_version` 倒序。无 view_version 列 → 空。

## B7 · 版本切换端点（D3 已落定）

- `POST /costing-orders/{coid}/version-switch`（`api.md §3`）：门禁(PENDING+财务/管理员) → upsert override → 按范围重查重算 → rollup → 更新单据总价 + 缓存 → 返回受影响卡片重算结果。
- 新服务 `CostingVersionService.switchVersion(...)`：**单 `@Transactional` + `SELECT ... FOR UPDATE` 锁 costing_order** + upsert override + **`em.flush()`** + 重算 + 写缓存。（不用 REQUIRES_NEW；若遇 self-invocation 致 @Transactional 失效再把重算下沉独立 bean。）
- **重查 scope（最小远程 SQL）vs 重装 scope（整卡内存）分离**：
  - 主树切 → 该 line `render(templateId, [thisLine])`、各 driver 组件跑 $view；
  - 非主树切 → **仅该组件 $view 跑一次**（partNo 组限定）；
  - **重装一律整卡**（未重查页签用缓存值 + 刚重查页签），保跨页签公式正确。两种情形远程查询次数**都与料号数无关**（禁 N+1）。
- **rollup 落后端**（`buildCostingCardValues`/`assembleTabsWithFormulaResults`）；`costing_total_amount` = **Σ 核价成本 subtotal，不含 Step3 折扣**（核价=成本，折扣属报价轴）。
- **不回写** `quotation_line_item.costing_card_values`（只动 costing_order.costing_render）。
- 守 AP-51（受影响 line/页签整体 REPLACE、禁 Math.max 累加）、`cpq-expand-layer-not-threadsafe`（串行）、AP-31/37。
- **开工前置**：确认核价 FORMULA 有无跨页签依赖（决定非树重装粒度；默认整卡重装=正确优先）。

## B8 · 死代码清理（可选、低风险）

- 清 `:spineKeys` / `SpineKeysMacro` / `SpineKeysContext` 相关死代码（`CardSnapshotService:761` 已确认 no-op）。**与 B2 版本宏落地解耦**，可单独收尾。

## B9 · 多版本测试数据

- 改 `docs/table/报价测试数据/…V3-罗克韦尔.xlsx`（+核价侧导入文件）料号数据后重导，触发 `bom_version`/`characteristic`/`version_no` 升版，造出同料号 ≥2 版本。产出验收锚点（如 `S-3110520789` 有 2000/2001）。

---

## 自检（完成前必跑，写入"已自检"声明）

1. `curl -s -o /dev/null -w '%{http_code}' --noproxy '*' http://localhost:8081/api/cpq/components` → 401。
2. Flyway `V3xx` `success=t`。
3. 新端点：`version-options` 返回倒序版本；`version-switch` PENDING 可切、非 PENDING 403；切后 `costingTotalAmount` 变化、其他卡片不变。
4. **禁 N+1**：切一次的 SQL 次数与料号数无关（打印 SQL 计数）；单次 <3s。
5. **协议级 E2E**（改了 `CardSnapshotService`/`CostingTreeRenderService`/`SqlViewExecutor` 必跑）：`quotation-flow.spec.ts` 全绿、`'加载中' final=0`；核价树切版本前后行数/值稳定（守 AP-51）。
6. 后端测试在 **worktree 的 `cpq-backend/`** 里 `./mvnw test` 亲跑（守 `cpq-worktree-maven-test-tree`）。
