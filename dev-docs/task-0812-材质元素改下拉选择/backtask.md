# 后端任务 — task-0812 材质元素改下拉选择

> **结论先行：本期后端零改动**。不新增端点、不改 DTO、不改校验、不加 Flyway。
> 本文件不是空槽，它记录**「为什么不改」的判定依据 + 回归确认清单 + 二期触发条件**（`任务平台规则.md` §3 要求）。
> 后端工程师**本期不进场**；测试期若发现必须动后端，先回主线改 `api.md`，不得直接改。

## 1. 为什么不需要改后端

| 需求点 | 已有能力 | 判定 |
|---|---|---|
| 下拉需要「编号 / 符号 / 中文名 + 状态」 | `GET /api/cpq/elements` → `ElementDTO` 已含 `elementNo` / `elementCode` / `elementName` / `status`，且**返回全状态**（`ElementService.list()` 无 status 过滤） | 直接复用，零改动 |
| 按编号/符号/中文名过滤 | 后端 `keyword` 已支持三字段 ILIKE；但本期**前端本地过滤**（需求文档 D11，现网仅 37 条） | 不调用 keyword，也不改 |
| 排序 | `ORDER BY (status='ACTIVE') DESC, GREATEST(updated_at, MAX(price.updated_at)) DESC` 已是「启用优先 + 最近使用靠前」 | 前端直接沿用，不重排 |
| 权限 | `ElementResource` 与 `MaterialRecipeResource` 的**读**权限完全一致：`SALES_REP` / `SALES_MANAGER` / `PRICING_MANAGER` / `SYSTEM_ADMIN` | 不会出现「能开材质抽屉却读不到元素」，无需放权 |
| 保存材质 | `MaterialRecipeUpsertRequest.ElementUpsert` 字段集合不变（`elementCode` / `elementName` / …），仅取值来源从手输改为字典 | 请求体一字不改，零改动 |
| 服务端校验 | `validateUpsert()` 既有校验（`elementCode` 必填 / 同材质重复 / 含量和=100 / recipeType 与 min-max 组合）全部保留 | 前端新增校验是**前置防线**，不替代后端 |

## 2. 数据模型变更

**无**。

| 项 | 状态 |
|---|---|
| Flyway 迁移 | **本期不新增任何版本**（需求文档 D10：2 行存量脏数据不迁移） |
| `element` 表 | 只读，不动。现网 37 行全 `ACTIVE` |
| `material_recipe_element` 表 | 结构不动、写入字段不动。`element_no` 列**继续由 `MaterialRecipeService.insertElement()` 不写入**（保持 NULL），见 §4 |
| 视图 | 实测无任何 PG 视图依赖 `material_recipe_element`，本改动无视图影响 |

## 3. 服务与端点清单

| 端点 | 本期动作 |
|---|---|
| `GET /api/cpq/elements` | 不改（新增一个调用方而已） |
| `POST /api/cpq/material-recipes` | 不改 |
| `PUT /api/cpq/material-recipes/{id}` | 不改 |

## 4. 明确保留的已知缺口（不是遗漏，是本期决策）

`MaterialRecipeService.insertElement()`（`:668-682`）**从不给 `el.elementNo` 赋值**，因此凡是**从管理 UI 新建/编辑**的材质元素行，`material_recipe_element.element_no` 恒为 NULL；只有 V320 迁移回填过的存量行和导入路径写入的行有值。

连带影响：`ElementService.list()` 的 `LEFT JOIN material_recipe_element mre ON mre.element_no = e.element_no` 会**少算**这部分引用 → 元素主表列表的「被引用数」偏低、「符号锁」（`codeLocked = referencedCount > 0`）可能该锁未锁。

- 现网实测：621 行中 619 行有 `element_no`，2 行为 NULL（即那 2 行脏数据），当前**影响面极小**。
- 本期按用户决策 **D9 不修**（只改 UI），登记 `BACKLOG.md` **BL-0163**（P2）。
- 注意：本次改造**会增加**该缺口的产生速率吗？**不会** —— 无论改造前后，UI 保存路径都不写 `element_no`，改造只是让 `element_code` 一定合法。

## 5. 二期触发条件（满足任一即应重开后端任务）

1. 元素字典条数 **超过约 500 条** → 前端全量拉取 + 本地过滤不再合适，改走后端 `keyword` 分页搜索（需求文档 §7 R2）。
2. 需要让元素主表的「被引用数 / 符号锁」准确 → 落实 BL-0163：`insertElement()` 补写 `element_no`（前端提交体加 `elementNo`，`api.md` API-2 需同步改）。
3. 需要在材质抽屉内内联新建元素 → 涉及 `POST /elements`（`SYSTEM_ADMIN`）与角色降级讨论，本期已否决（决策 D5）。

## 6. 回归确认清单（后端不改，但必须确认没被误伤）

由测试工程师在 `test.md` 覆盖，主线亲验时复核：

- [ ] `GET /api/cpq/elements` 返回 200，条数 = `SELECT count(*) FROM element`（现网 37）
- [ ] 保存正常材质后，`material_recipe_element` 的 `element_code` / `element_name` / `default_pct` / `min_pct` / `max_pct` / `is_locked` / `sort_order` 逐字段与保存前一致（对应 AC-7）
- [ ] 后端既有校验仍生效：构造含量和 ≠ 100 的请求 → 返回 `元素 default_pct 之和必须 = 100, 当前: …`
- [ ] `flyway_schema_history` 本次**无新增行**（`SELECT max(version) FROM flyway_schema_history` 改动前后一致）
- [ ] 材质库导入路径（`MaterialRecipeImportService`）不受影响：导入后 `element_no` 仍正常写入

## 7. N+1 自检

**不涉及后端代码改动，无新增循环、无新增查库**。前端为一次性全量拉取 + 本地过滤，请求数与元素数、材质行数均无关。

> 声明格式（合并前写进报告）：`N+1 自检：本次后端零改动，无新增循环与查库 ✅`

## 8. Task 列表

- [ ] B1 **无开发任务**。后端工程师本期不进场
- [ ] B2 测试期配合：按 §6 提供/执行 SQL 断言证据（可由测试工程师直接执行）
