# task-0806 模板发布全量冻结 —— 后端任务文档

> 执行依据：`需求文档.md`（FR / AC 为准）｜接口契约：`api.md`｜分支：`feat/task-0806-template-freeze`
> **开工前必读**：`需求文档.md` §8 已知坑位、`docs/三大核心模块基线.md` §3.2/§3.4/§10、`docs/方案制定前必读.md` §改动 5、`docs/反模式.md` AP-39 / AP-40 / AP-44 / AP-51

---

## 0. 一句话

让已发布模板的渲染配置**只有一个来源**：`template_component_snapshot`。写入只发生在 `publish()`，读取只经 `PublishedTemplateReader`，读不到就报错，不许回落活表。

---

## 1. 数据模型变更

### 1.1 新表 `template_component_snapshot`（B1）—— FR-1 / **FR-4**（6 个新补字段在本表内，已标注 `② 新补`）

**Flyway 版本号**：当前库内最高 `V381`（`success=t`），本次取 `V382`。
⚠️ **版本号是移动靶** —— 多会话并发，合并前必须复查一次；**已应用的迁移禁止改名改号**。

```sql
CREATE TABLE template_component_snapshot (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    template_id             uuid NOT NULL REFERENCES template(id) ON DELETE CASCADE,
    template_component_id   uuid NOT NULL,          -- 溯源，刻意不建 FK
    component_id            uuid NOT NULL,          -- 溯源，刻意不建 FK
    sort_order              integer NOT NULL,

    -- 来自 template_component（模板级）
    tab_name                varchar(200),
    preset_rows             jsonb NOT NULL DEFAULT '[]',
    formula_assignments     jsonb NOT NULL DEFAULT '{}',

    -- 来自 component（内容层，18 个渲染配置字段中的 component 侧）
    component_name          varchar(200),
    component_code          varchar(100),
    component_type          varchar(20)  NOT NULL DEFAULT 'NORMAL',
    column_count            integer      NOT NULL DEFAULT 0,
    fields                  jsonb        NOT NULL DEFAULT '[]',
    formulas                jsonb        NOT NULL DEFAULT '[]',
    excel_columns           jsonb        NOT NULL DEFAULT '[]',
    data_driver_path        text,
    tree_config             jsonb,
    bom_recursive_expand    boolean      NOT NULL DEFAULT false,
    tab_type                varchar(30),
    part_no_field           varchar(100),
    part_name_field         varchar(100),
    row_key_fields          jsonb,                  -- ② 新补
    sort_field              varchar(120),           -- ② 新补
    element_code_field      varchar(100),           -- ② 新补
    element_price_field     varchar(100),           -- ② 新补
    element_currency_field  varchar(100),           -- ② 新补

    frozen_at               timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT uq_tcs_template_sort UNIQUE (template_id, sort_order)
);

CREATE INDEX idx_tcs_template            ON template_component_snapshot(template_id);
CREATE INDEX idx_tcs_component           ON template_component_snapshot(component_id);
CREATE INDEX idx_tcs_template_tabtype    ON template_component_snapshot(template_id, tab_type);
CREATE INDEX idx_tcs_template_driver     ON template_component_snapshot(template_id)
                                          WHERE data_driver_path IS NOT NULL AND data_driver_path <> '';
```

**设计要点（不许改）**

| 决定 | 原因 |
|---|---|
| `template_component_id` / `component_id` **不建 FK** | 快照的意义就是与活表脱钩。组件被停用/删除不该动摇已发布模板 |
| `template_id` 建 FK + `ON DELETE CASCADE` | 模板没了快照无意义；且 PUBLISHED 模板本就不可删 |
| `UNIQUE (template_id, sort_order)` | 从 schema 层面消灭 **AP-40**（同 cid 多 tc 实例 `firstResult()` 反向污染）。迁移必须**按 tc 逐行插入，不得按 componentId 聚合** |
| **不含** `status` / `directory_id` / `created_at` / `updated_at` | D5：status 不进快照也不进渲染路径；后两者非渲染配置 |

**字段账对照**（全文口径，见 `需求文档.md` §1.4）：`Component` 实体 23 字段 = `id` + `createdAt` / `updatedAt`（3 非配置）+ **18 渲染配置字段** + `status` / `directoryId`（2 刻意不冻）。18 个中旧 snapshot 只冻 12 个，本次补 6 个。

### 1.2 `operation_log` 加列（B2）

```sql
ALTER TABLE operation_log ADD COLUMN details jsonb;
```

加法式，可空，不影响 `CustomerService` 等既有写入方。

### 1.3 存量对齐（B3，与 B1/B2 同一个 V382）

```sql
DELETE FROM template_component_snapshot
 WHERE template_id IN (SELECT id FROM template WHERE status IN ('PUBLISHED','ARCHIVED'));

INSERT INTO template_component_snapshot (
    template_id, template_component_id, component_id, sort_order,
    tab_name, preset_rows, formula_assignments,
    component_name, component_code, component_type, column_count,
    fields, formulas, excel_columns, data_driver_path,
    tree_config, bom_recursive_expand, tab_type, part_no_field, part_name_field,
    row_key_fields, sort_field,
    element_code_field, element_price_field, element_currency_field)
SELECT tc.template_id, tc.id, tc.component_id, tc.sort_order,
       tc.tab_name,
       COALESCE(tc.preset_rows, '[]'::jsonb),
       COALESCE(tc.formula_assignments, '{}'::jsonb),
       c.name, c.code, c.component_type, c.column_count,
       COALESCE(tc.fields_override, c.fields),                       -- override 优先
       c.formulas, c.excel_columns,
       COALESCE(tc.data_driver_path_override, c.data_driver_path),   -- override 优先
       c.tree_config, c.bom_recursive_expand, c.tab_type, c.part_no_field, c.part_name_field,
       c.row_key_fields, c.sort_field,
       c.element_code_field, c.element_price_field, c.element_currency_field
  FROM template_component tc
  JOIN component c ON c.id = tc.component_id
  JOIN template  t ON t.id = tc.template_id
 WHERE t.status IN ('PUBLISHED', 'ARCHIVED');
```

随后**在同一迁移里**按新表重生成各模板的 `components_snapshot` jsonb（保证两份从第一天起同源）。

| 要点 | 说明 |
|---|---|
| `COALESCE` 保留 override 优先 | 两列当前全 NULL 用不上，但语义必须对，否则将来谁用后门写了一个就静默丢 |
| 覆盖 `ARCHIVED` | 库里现在 0 个，规则先立住（历史报价单在读） |
| **幂等** | 先 `DELETE` 后 `INSERT`，重复执行结果不变（AC-9） |
| 预期行数 | **149**（以迁移当时 `template_component` 实际值为准） |

---

## 2. 服务与端点清单

### 2.1 新增 `PublishedTemplateReader`（B4）—— FR-5

渲染期一切「这个模板有哪些页签、每个页签什么配置」的问题都问它。10 处调用点不再自己拼 SQL。

**接口形状必须是批量的** —— 把 CLAUDE.md「单个业务操作 SQL 条数与 N 无关」的铁律焊死在签名里：

```java
public interface PublishedTemplateReader {
    /** 该模板全部页签快照，按 sortOrder 升序。一次查询。 */
    List<TemplateComponentSnapshot> allTabsOf(UUID templateId);

    /** driver 组件（data_driver_path 非空）。 */
    List<TemplateComponentSnapshot> driverCompsOf(UUID templateId);

    /** 树页签（tab_type = 'BOM'）。 */
    List<TemplateComponentSnapshot> treeTabsOf(UUID templateId);

    /** 是否存在 bom_recursive_expand = true 的页签。 */
    boolean hasRecursiveExpand(UUID templateId);

    /** 多模板批量（供整单场景，避免逐模板查）。 */
    Map<UUID, List<TemplateComponentSnapshot>> allTabsOfMany(Collection<UUID> templateIds);
}
```

🚫 **禁止**提供 `tabOf(templateId, sortOrder)` 这类单条查询后被调用方放进循环 —— 那就是把 N+1 重新引回来。若某调用点确实只要一条，让它拿 `allTabsOf` 的结果在内存里挑。

### 2.2 `TemplateService.publish()` 改造（B5）—— FR-1 / FR-2

同事务内：

1. 现有逻辑照旧（`cross_tab_ref` 校验、`sql_views_snapshot`、`template_sql_views_snapshot`、版本号、status）
2. **新增**：清掉该模板旧快照行（重发布场景）→ 按 tc 逐行插入 `template_component_snapshot`
3. **改造**：`components_snapshot` 由新表派生，不再各自拼装

> ⚠️ **AC-2 是硬门槛**：派生出的 jsonb 必须与改造前**逐字段一致**（键集合、键顺序、值）。建议实现时先跑一遍改造前的 `publish()` 存下 jsonb，再对比派生结果。

### 2.3 `refreshSnapshotsByComponent` 整体退役（B6）—— FR-3

> ⚠️ **2026-08-07 更正**：本表原列「4 个调用点」，**漏了第 5 个** —— `TemplateService.promoteOverrideToComponent:923` 的**方法内部自调用**。测试工程师审用例时 grep 源码发现（主线立项时的 grep 输出里其实有这一行，写文档时漏抄）。不处理会在删除方法体后**直接编译不过**。已补入下表。

| 位置 | 动作 |
|---|---|
| `TemplateService:340-414` | **删除方法本体** |
| `TemplateService.promoteOverrideToComponent:923`（**补漏**） | 内部自调用 → 改走新的批量化重冻实现，不得残留对已删方法的引用 |
| `ComponentService.update:729-740` | **删除**整个 try 块（含 `[H1 auto-sync]` 日志）。⚠️ 别误删紧随其后的 `syncExcelColumnsToImportedCopies`（Bug3 源→副本同步，与本任务无关） |
| `ComponentService.setDriverView:790-796` | **删除**整个 try 块 |
| `ComponentResource:143-147` | **删除路由**（D11，不做 410 过渡） |
| `TemplateResource:143-156` `migrate-to-unified-view` | **删除路由**（一次性历史迁移，基线 §3.5 标注已跑过） |
| `TemplateService.migrateToUnifiedView` | 随 A9 一并删除 |
| `ConfigCenterResource:108-135` `refresh-all-snapshots` | **保留但实现重写**，见 B7 |

### 2.4 三个 admin 后门改造（B7）—— FR-7

统一口径：`confirm` 参数（缺省 `false` = 仅预览零写入）+ 影响面预览 + `operation_log` 审计 + `LOG.warn`。契约见 `api.md` §6/§7。

**`refresh-all-snapshots` 实现重写要点**：原实现循环调 `refreshSnapshotsByComponent`（`O(N_template × N_component)`，本身违反 N+1 铁律）。新实现 = 「B3 那段 SQL 的参数化可重复版本」，按 `templateIds` 过滤，**SQL 条数与模板数/组件数无关**。

**`delete-tcs` 补充**：删 tc 后必须**同步删对应快照行 + 重生成该模板 jsonb**，否则快照与 tc 不一致。

### 2.5 10 处读取点改造（B8）—— FR-5

| 文件 | 行 | 现状 | 改法 |
|---|---|---|---|
| `CardSnapshotService` | 1072 | `JOIN component c ... c.data_driver_path IS NOT NULL` | `reader.driverCompsOf(tid)` |
| `CardSnapshotService` | 1299 | `c.id, c.bom_recursive_expand`（prefetch） | `reader.driverCompsOf(tid)` 取字段 |
| `CardSnapshotService` | 1640 | `count(*) ... c.bom_recursive_expand = true` | `reader.hasRecursiveExpand(tid)` |
| `CardSnapshotService` | 2858 | `c.id, c.bom_recursive_expand, c.tab_type` | `reader.driverCompsOf(tid)` |
| `CardSnapshotService` | 2929 | `c.tab_type = 'BOM'` | `reader.treeTabsOf(tid)` |
| `ConfigureSnapshotService` | 856 | `JOIN ... c.data_driver_path IS NOT NULL`（经 quotation 关联模板） | 先取 `q.customer_template_id` → `reader.driverCompsOf` |
| `ExcelViewService` | 519 / 565 / 1094 / 1119 | `Component.findById` / `TemplateComponent.list` | 按 templateId 一次预载 Map；注意保留 `:435` 的性能守卫语义（无 TAB_JOIN 列时不做无谓加载） |

**改造后 grep 零命中验收（AC-5）**：这三个文件内不得再有对 `component` / `template_component` 表的直接查询。
⚠️ 验收 grep **必须** `/usr/bin/grep -a` —— 本环境 `grep` 是 ugrep，中文注释多的大文件会被静默判为二进制返空，据空结果下「零命中」结论会假绿。

### 2.6 快照 miss 显式报错（B9）—— FR-6

`PublishedTemplateReader` 内部：模板 status ∈ (PUBLISHED, ARCHIVED) 且查不到快照行 → 抛 `BusinessException(500, "模板快照缺失：templateId=…, sortOrder=…")`。

🚫 **绝不允许**写成「没有就查活表兜底」。渲染只发生在报价单上，而报价单绑定已被 task-0729 强制要求 PUBLISHED，故渲染期不存在 DRAFT 分支，**不需要**兜底路径。

### 2.7 SQL 视图 fallback 收口（B10）—— FR-6 / D13

`ComponentSqlViewService.lookupForResolver:400` 第 3 步「兜底实时读」在已发布模板上下文下改为抛错。

🚦 **前置门槛**：先实现 A4 体检端点并跑出结果，**把数据交用户拍板**（D13 三档），不得自行决断。

### 2.8 体检与差异端点（B11）—— FR-8 / FR-9

A2 / A3 / A4，契约见 `api.md` §3/§4/§5。差异比对逻辑（快照 vs 活配置）三者共用一份，抽成 `TemplateFreezeDriftService`。

---

## 3. 业务规则与算法

### 3.1 冻结边界（三条，不许破）

| 边界 | 规则 |
|---|---|
| **DRAFT 模板不写快照** | 沿用现状语义（`create:130` 不设、`createNewDraft:468` 显式 NULL、`publish:256` 才写）。草稿期走活表是草稿该有的行为 |
| **ARCHIVED 模板同样冻死** | `archive()` 只改 status 不动快照，新表同理不动 |
| **`sql_views_snapshot` / `template_sql_views_snapshot` 不动** | 本就在 `publish()` 冻结且退役的方法不碰它们，仅收口 fallback（B10） |

### 3.2 `component.status` 不变量（D5，本期不改代码只固化）

- 后端渲染链路**零处**读 `component.status`（已全量 grep 确认）
- 组件选择器已过滤（`ComponentPalette.tsx:106` / `TemplateConfiguration.tsx:129`）
- 实测：121 个组件中 51 个 `DISABLED`，其中 12 个 tc 行在 2 个已发布模板上**仍正常渲染**
- 值域 `ACTIVE` / `DISABLED`（`ComponentService.toggleStatus:846-850`），**不是** `INACTIVE`

🚫 **本任务及后续都不得**在渲染层加 `status='ACTIVE'` 过滤 —— 那会让停用影响已发布模板，违背 D5。

### 3.3 override 优先语义

`fields` 取 `COALESCE(tc.fields_override, c.fields)`；`data_driver_path` 取 `COALESCE(tc.data_driver_path_override, c.data_driver_path)`。两列当前全 NULL，但语义必须对。

---

## 4. 事务边界

| 操作 | 边界 |
|---|---|
| `publish()` | 单事务：cross_tab_ref 校验 + 三份快照 + 新表 N 行 + jsonb 派生 + 版本号 + status。任一失败全回滚 |
| Flyway V382 | Flyway 自身事务；建表 + 加列 + 存量对齐 + jsonb 重生成同批 |
| admin 后门 `confirm=true` | 单事务：快照重写 + `operation_log`。审计与写入**必须同生共死**，不许审计失败而写入成功 |
| admin 后门 `confirm=false` | **只读**，零写入（可加 `@Transactional(REQUIRES_NEW)` 只读或干脆不加事务注解） |
| 渲染期读取 | 只读，无事务要求 |

---

## 5. 幂等与并发

| 项 | 要求 |
|---|---|
| Flyway V382 | 幂等（先 DELETE 后 INSERT），重复执行结果不变 |
| `publish()` 重发布 | 先清该模板旧快照行再插，不留脏行 |
| `refresh-all-snapshots` | 幂等，可反复跑 |
| 并发发布同一模板 | 现有 `@Transactional` + `UNIQUE (template_id, sort_order)` 兜底；并发时后者撞唯一约束回滚，属预期 |
| 渲染期并发读 | 纯读，无竞态 |

---

## 6. 性能要求（CLAUDE.md 铁律）

- **单个业务操作 SQL 条数与 N（页签数 / 组件数 / 模板数 / 行数）无关**
- 新表读取一律走 `PublishedTemplateReader` 的批量方法；🚫 禁止在 `for` / `forEach` / `stream()` 里查库
- 关键链路补日志：`[perf] renderTemplate N=<页签数> sql=<条数>` —— **N 翻倍而 sql 不变才算过**（AC-11）
- `refresh-all-snapshots` 重写后必须比原实现（`O(N_template × N_component)`）显著更少 SQL

**N+1 自检（PR 必含）**：逐个检查本次新增/改动的循环体，确认无 repository 调用 / `SqlViewExecutor.execute` / 触发懒加载的关联 getter。声明格式：
> `N+1 自检：本次改动 N 处循环，均为纯内存运算，无查库 ✅` ／ `批量化验证：页签 5→20，SQL 条数恒为 M ✅`

---

## 7. 后端自检项（每项都要有证据，不能只声明）

- [ ] `touch` 一个 java 触发 Quarkus 重启（等 5-7s）
- [ ] `curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:8081/api/cpq/components` → **401**（非 500）
- [ ] 新端点 A2/A3/A4 各 `curl` 一次 → 200/401（非 500）
- [ ] A8/A9 删除后 `curl` → **404**
- [ ] `SELECT version, success FROM flyway_schema_history WHERE version='382'` → **`success=t`**
- [ ] `SELECT count(*) FROM template_component_snapshot` → **149**（或迁移当时 tc 实际值）
- [ ] 值中性验证：迁移前后同批报价单纯读渲染逐位一致
- [ ] N+1 自检声明
- [ ] **worktree 内**跑 `cd cpq-backend && ./mvnw test`（`mvnw` 在 `cpq-backend/` **不在仓库根**；跑到主仓 = 测错树 = 假绿）
- [ ] `git branch --contains <commit>` 确认提交落在特性分支而非 master

⚠️ **Flyway 在 worktree 里的坑**：共享 dev server **看不到** worktree 的新迁移文件。验证时把 V382 copy 到主仓跑，**合并前删掉副本**；做 A/B 对比时别把迁移文件 stash 掉。
⚠️ **禁止**手工 `psql -f V382__*.sql` —— 让 Quarkus dev mode 自动跑 Flyway。

---

## 8. Task 列表（逐项勾选）

### 阶段一 · 地基（可并行 B1/B2）

- [ ] **B1** 建表 `template_component_snapshot` + 4 个索引 + 唯一约束（V382）｜FR-1 + **FR-4**（6 个新补字段）
- [ ] **B2** `operation_log` 加 `details jsonb` 列（V382 同批）
- [ ] **B3** 存量对齐 SQL + jsonb 重生成（V382 同批），幂等
- [ ] **B4** `TemplateComponentSnapshot` 实体 + `PublishedTemplateReader` 服务（批量接口，禁单条查询）

### 阶段二 · 写入侧（依赖 B1/B4）

- [ ] **B5** `publish()` 落新表 + jsonb 改派生（**AC-2 逐字段一致是硬门槛**）
- [ ] **B6** `refreshSnapshotsByComponent` 退役 + 4 处调用点处置 + 2 个路由删除
- [ ] **B7** 三个 admin 后门改造（confirm 预览 + `operation_log` 审计 + WARN）；`refresh-all-snapshots` 实现重写并批量化

### 阶段三 · 读取侧（依赖 B4）

- [ ] **B8** 10 处活表读改走 Reader（`CardSnapshotService` 5 / `ConfigureSnapshotService` 1 / `ExcelViewService` 4）
- [ ] **B9** 快照 miss 显式报错，禁止回落
- [ ] **B11** 体检与差异端点 A2/A3/A4 + `TemplateFreezeDriftService`

### 阶段四 · 门槛后置（依赖 B11 + 用户拍板）

- [ ] **B10-a** 跑 A4 体检，输出 `missCount` 与明细
- [ ] 🚦 **交用户按 D13 三档拍板**（实现者不得自决）
- [ ] **B10-b** 按裁决执行：切报错 / 补闭包后切 / 拆出立项并登记缺口

### 阶段五 · 文档（FR-11）

- [ ] **B12** `PRD-v3.md` 加「发布后不可变 + 版本演进」+ 演进史
- [ ] **B13** `三大核心模块基线.md`：§3.2 改写、**§3.4 纠错**（老 PUBLISHED 不自动归档，多版本共存是设计允许的）、§3.5 端点清单、§10 红线 +3 条
- [ ] **B14** `方案制定前必读.md` §改动 5 改写（老契约「必须同时改 3 个层」作废）
- [ ] **B15** `反模式.md` 新增 AP：「发布后活穿透」族（3 类形态 + 「绕开 AP-44 顺带绕开冻结」根因模式）
- [ ] **B16** 测试通过后回写 `dev-docs/main-api.md`（清单见 `api.md` §11），**合并前必须完成**

---

## 9. 红线（违反即打回）

1. 🚫 任何形式的「快照读不到就查活表」兜底
2. 🚫 在 `for` / `forEach` / `stream()` 里查库
3. 🚫 渲染层新增 `component.status` 过滤
4. 🚫 迁移里按 componentId 聚合插入（会重演 AP-40）
5. 🚫 手工 `psql -f` 跑迁移
6. 🚫 `git add -A`（并发会话互相夹带；只 add 本次明确改动的文件，提交后 `git show --stat` 自查）
7. 🚫 据 ugrep 的空结果下「零命中」结论（必须 `/usr/bin/grep -a` 复核）
8. 🚫 B10 自行决断（必须交用户按 D13 拍板）
