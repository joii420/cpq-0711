# 后端任务分解 · 材质管理模块定义规则更新

> 后端只按本文件做。AC 原文在 `需求文档.md` §③，**本文件只标编号不复制原文**（复制会双写漂移）。
> 数据模型与六条派生规则 M-1~M-6 在 `需求文档.md` §4.5，**不得自行改动**。接口契约见 `api.md`。

## 派工须知（进场先读）

| 项 | 内容 |
|---|---|
| **分支 / worktree** | 闸门 A 放行后由主线建，路径与分支名在派工 prompt 里给 |
| **库** | 开发连默认 profile → `10.177.152.12:5432/cpq_db_0724`；`mvnw test` 走 `test` profile → `10.177.152.12:5432/cpq_db`。**两个库不同，写集成测试时注意** |
| 🚨 **红线** | `B-3` 含 `DROP CONSTRAINT` + `DROP COLUMN`。**子代理不得自行执行，写完迁移文件后停下报主线**，由主线按 `CLAUDE.md §3.2` 三步走报请用户批准 |
| 🚨 **N+1** | 导入与配置读取是批量场景，`backend.md` 的硬指标适用：**单个业务操作的 SQL 条数必须与行数无关**。既有导入已用 tuple-IN 批量 upsert，重写后不得退化成逐行查库 |
| ⚠️ **Flyway 版本号** | 共享 dev 库的迁移历史是并发移动靶（master 当前最高 `V398`）。**建文件时先 `ls db/migration | tail -5` 取实际最大值**，合并前主线会再核一次 |
| ⚠️ **精度** | 含量列是 `numeric(16,12)`。Java 侧一律 `BigDecimal`，`@Column(precision=16, scale=12)` 必须显式声明（`task-260813` T6 反射一致性测试会扫）。**API 出参一律 `String`**（见 `api.md` §1） |

---

## A · 数据层

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **B-1** | AC-13, AC-14, AC-31 | Flyway 迁移①：建 **`material_recipe_composition`**（材质的元素组成）+ 建 `material_recipe_config`（两张表 DDL 见 `需求文档.md` §4.5）；`material_recipe` 加 `allow_custom_content boolean NOT NULL DEFAULT false`；`material_recipe_element` 加**可空** `config_id uuid` |
| **B-2** | AC-7, AC-13, AC-17 | Flyway 迁移②（与 B-1 同文件、同事务）：**双向存量迁移** —— ① 每条 `material_recipe` INSERT 一条 `config_no = code \|\| '-01'`、`seq = 1` 的配置（**258 行**）；② `UPDATE material_recipe_element` 回填 `config_id`（**621 行**）；③ **从这 621 行推导每条材质的元素组成写入 `material_recipe_composition`**（`sort_order` 沿用元素行原有的 `sort_order`，**621 行**）。迁移末尾加**三条**断言，任一不成立就 `RAISE EXCEPTION` 让迁移失败而不是静默放过：`config_id IS NULL` 计数 = 0 · `material_recipe_composition` 行数 = 621 · **不存在「元素行的 element_code 不在本材质元素组成里」的行** |
| **B-3** | AC-14 | Flyway 迁移③：`config_id SET NOT NULL` + 建 FK + `DROP CONSTRAINT uq_recipe_element` + 建 `uq_config_element (config_id, element_code)` + `DROP COLUMN recipe_id`。🚨 **写完停下报主线，不要自行 apply**。报告里给出：① `SELECT count(*) FROM material_recipe_element WHERE config_id IS NULL`（须 = 0）② 可恢复性说明（`recipe_id` 可由 `config_id → material_recipe_config.recipe_id` 完整重建） |
| **B-4** | AC-13 | 实体层：新增 `MaterialRecipeConfig`（Panache）；`MaterialRecipeElement` 的 `recipeId` 换成 `configId`；`MaterialRecipe` 加 `allowCustomContent`。**同步改 `DemoMaterialRecipeFixture`**（测试夹具，不改则全量测试红） |

## B · 编号发号器（三个都是纯函数 + 一次查询，务必单测覆盖）

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **B-5** | AC-14, AC-15, AC-26 | **配置编号发号**（M-1 / M-2）：`config_no = recipe.code + "-" + String.format("%02d", seq)`，`seq = max(该材质全部配置的 seq，含 INACTIVE) + 1`。🚫 **禁止用 PG `lpad(x,2,'0')`** —— 它把 `'100'` 截成 `'10'`。单测必须含 `seq=99 → "-99"`、`seq=100 → "-100"`、`删除中间一条后不回收`三例 |
| **B-6** | AC-3, AC-4 | **材质编号自增**：只统计 `code ~ '^[0-9]{5}$'` 的最大值 + 1，格式化为 5 位补零。脏值 `'992'` 必须被排除（它不是 5 位）。**只对校验通过的材质发号** —— 发号动作必须在 Σ 校验、元素集合校验之后，否则会出现空号（AC-4） |
| **B-7** | AC-6 | **元素编号自增 + 自动建档**：符号在 `element` 主表查无 → 新建，`element_no = max(element_no::bigint WHERE element_no ~ '^[0-9]+$') + 1`。⚠️ 主表存在脏行 `element_no='白银'`，**正则过滤不可省**，否则 `::bigint` 直接抛异常。中文名取现有 `DICT` 字典，无对应则回退为符号。新建的元素必须进 `report.createdElements`（X-2） |

## C · 导入重写（`MaterialRecipeImportService` 整体重写）

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **B-8** | AC-5, AC-27 | 元素符号 → `element_no` 匹配：按 `element.element_code` 等值查。⚠️ **纯数字符号 `301/304/316/430/191/206/223/258/721` 是合法钢/合金牌号，不得因「纯数字」判非法**（`task-260708` R1 已推翻过一次该规则，别改回去） |
| **B-9** | AC-1, AC-11, AC-23 | 解析层：**读工作簿的第一个 sheet，按表头识别，不依赖 sheet 名**（模板生成的叫「材质含量」，但业务改名不应导致导入失败）；表头须恰为 `材质 / 组号 / 元素符号 / 含量` 四列，否则 `IMPORT_HEADER_INVALID`；命中旧两 sheet 结构（存在名为「材质编号」或「材质对应元素」的工作表）→ 抛 `IMPORT_TEMPLATE_OUTDATED`（400）；表头对但零数据行 → 返 200 全 0 报告，**不抛异常**。⚠️ 含量单元格可能是**文本格式**（夹具就是，为保 12 位小数），`cellStr` 的 STRING / NUMERIC / FORMULA 三分支都要能取到值 |
| **B-10** | AC-9, AC-10, AC-24, AC-25, AC-28, AC-32 | 校验层，**五级顺序不可颠倒**：① 行级（含量非数字/≤0/>1 → 跳过该行）→ ② 组级 Σ≈1（容差 0.02，**沿用既有值**，`WZHF26-25` 的 Σ≈1.41 仍须被跳过）→ ③ 材质名长度 ≤ 32 / 同名材质唯一（M-6）→ ④ **元素组成一致性，按材质是否已存在分两条路**：<br>&nbsp;&nbsp;• **已存在的材质** → 每组与 `material_recipe_composition` **逐组比对**，不相等的**那一组**跳过（`元素组合与该材质的元素组成不一致`）；<br>&nbsp;&nbsp;• **新材质** → 先把该材质在**本文件内的所有有效组互相比对**，全一致才放行并以该集合作为元素组成；**任一组不一致则整个材质跳过**（`同一材质内各组元素组成不一致(...)`），不建材质、不发号（M-5b / D11）。🚨 **这一条是顺序无关性的唯一保证** —— 实现必须是「先收齐该材质的全部组再判」，🚫 **不许写成「拿第一组当基准、后面逐组比」**（那又退回行序依赖）。<br>**每级失败都写 `report.skipped` 并带 Excel 行号** |
| **B-11** | AC-2, AC-4, AC-7, AC-8, AC-20, AC-21, AC-32 | 落库层，语义由「整体重灌覆盖」改为**只增不改**：按材质名匹配已有材质（匹配不到才建，B-6 发号，**并同时写入 `material_recipe_composition`**，元素顺序按其在文件中首次出现的次序）→ 按组号把行分组 → 每组与该材质**ACTIVE** 配置逐值比对（M-4：元素集合相同 + 每元素 `BigDecimal.compareTo == 0`）→ 已存在则 `configsSkippedAsDuplicate++`，不存在则建配置（B-5 发号）+ 灌元素行。🚫 **不得再出现 `DELETE FROM material_recipe_element WHERE recipe_id IN (...)`**（那是旧覆盖语义的实现）。🚫 **不复活 INACTIVE 配置**（M-3）。含量 `×100` 归一保留，12 位小数无损 |
| **B-12** | AC-6, AC-9, AC-10, AC-23 | 报告结构扩展：`MaterialImportReportDTO` 按 `api.md` §2.3 加 `recipesCreated` / `configsCreated` / `configsSkippedAsDuplicate` / `createdElements[]` / `createdConfigs[]`，`skipped[].reason` 补两种新值 |
| **B-13** | AC-12 | `generateTemplate()` 改：单 sheet、表头 `材质 / 组号 / 元素符号 / 含量`、2 行示例（`AgCu10 / 1 / Ag / 0.9` 与 `AgCu10 / 1 / Cu / 0.1`）、含量列表头批注改新文案。**删掉「材质编号」sheet 与「元素编号」列** |

## D · 配置 CRUD 与材质接口

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **B-14** | AC-14, AC-15, AC-17 | 配置四个端点（`api.md` §2.2）。`POST`/`PUT` 的 `elements` 元素集合必须与材质 `composition` **逐个相等**（多了少了都 400）—— 前端已按组成预填只读，这条防的是绕过前端。`DELETE` 是**软删且幂等**；`POST`/`PUT` 走与导入同一套校验（Σ / 逐值范围 / 元素集合一致 / 与 ACTIVE 配置重复），**校验逻辑抽成共享方法，不许导入与 CRUD 各写一份** |
| **B-15** | AC-13, AC-16, AC-17 | 列表与详情响应：加 `allowCustomContent` / `elementCodes` / `configCount`；详情加 `composition[]` 与 `compositionEditable`，`elements` 换成 `configs[]`（BC-1）。⚠️ **列表页 N+1 高风险** —— `configCount` 与 `elementCodes` 必须一条聚合 SQL 取全，不许逐材质查。⚠️ **`elementCodes` 必须查 `material_recipe_composition`，不许从配置推导**（BC-2b）：0 配置的材质也要有值，否则 AC-17 的列表 tag 会空 |
| **B-16** | AC-16, AC-24, AC-28, AC-31 | **`PUT` 材质（编辑态）**：① 加 `allowCustomContent`；② `elements` 换成 `composition[]`（BC-2），落 `material_recipe_composition`，`elementCode`/`elementName` 由服务端从 `element` 主表回填；③ **元素组成只读守卫（M-0b）**：该材质存在 ACTIVE 配置且提交的 `composition` 与现值不同 → 409 `COMPOSITION_LOCKED`；**传相同值视为未改、放行**（否则前端每次保存材质名都会被拒）。比较按 `(elementNo, sortOrder)` 的有序列表判等；④ 材质名长度与重名校验 |
| **B-20** | AC-33, AC-34 | **`POST` 材质（新建态）—— 建材质 + 推导元素组成 + 建配置，一个事务**：请求体是 `configs: [{remark?, elements:[{elementNo, pct}]}]`（`api.md` §2.1）。步骤：① 逐组校验 Σ≈1 与单值范围；② **各组元素种类集合互相比对**，不全相同 → 400 `COMPOSITION_INCONSISTENT_ACROSS_CONFIGS`，报文指名是哪两组、各是什么集合；③ 组间内容逐值判重（M-4）→ 409 `CONFIG_DUPLICATED_IN_REQUEST`；④ 全过才发材质编号（B-6）、写 `composition`（取第 1 组的元素与顺序）、按 B-5 逐组发配置编号。<br>🚨 **②③ 的判据必须与导入侧 B-10 第④级复用同一份代码**（M-0a：UI 与导入是同一条规则的两个入口）—— 🚫 不许两边各写一套，那是下一个「两处口径分叉」的种子。<br>🚨 **失败必须整体回滚且不消耗编号** —— 发号动作要排在全部校验之后 |

## E · 选配链路

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **B-17** | AC-18, AC-19 | `ConfigureProductService`：① `configNo` 与 `elements` 互斥校验；② 选标准配置时按 `configNo` 取元素；③ 自定义时先查 `allowCustomContent`（M-5：为 `false` 直接 403，**不进元素级 `is_locked` 判断**；为 `true` 时 `is_locked` 不再单独生效，只校验 `min/max`（若有）+ Σ=1）。⚠️ 现有 `:526-546` 的元素级校验分支**保留但降级为 `allowCustomContent=true` 下的子分支**，不要删 |
| **B-18** | AC-17 | 选配材质候选：返回 `configCount`，供前端灰显无配置的材质。**后端也要拦** —— 提交时材质无 ACTIVE 配置返 409 `RECIPE_HAS_NO_CONFIG`（前端灰显是体验，后端拦是正确性） |
| **B-19** | AC-22 | 回归保障（**不改功能，只加测试**）：① `ElementService` 的「被引用数」与「符号锁」按 `element_no` 聚合，元素行改挂配置后计数须不变 —— 写用例锁住；② 选配落到 `element_bom_item` 的含量在配置被软删后**不受影响** —— 写用例锁住 |

---

## 双向覆盖自查（闸门 A 用）

**正向**（每条 AC 至少一个 B-x 或 F-x 认领）：

| AC | 后端 | AC | 后端 |
|---|---|---|---|
| AC-1 | B-9 | AC-16 | B-15, B-16 |
| AC-2 | B-11 | AC-17 | B-14, B-18 |
| AC-3 | B-6 | AC-18 | B-17 |
| AC-4 | B-6, B-11 | AC-19 | B-17 |
| AC-5 | B-8 | AC-20 | B-11 |
| AC-6 | B-7, B-12 | AC-21 | B-11 |
| AC-7 | B-2, B-11 | AC-22 | B-19 |
| AC-8 | B-11 | AC-23 | B-9, B-12 |
| AC-9 | B-10, B-12 | AC-24 | B-10, B-16 |
| AC-10 | B-10, B-12 | AC-25 | B-10 |
| AC-11 | B-9 | AC-26 | B-5 |
| AC-12 | B-13 | AC-27 | B-8 |
| AC-13 | B-1, B-4, B-15 | AC-28 | B-10, B-16 |
| AC-14 | B-1, B-3, B-5, B-14 | AC-29 | 纯前端（F-2） |
| AC-15 | B-5, B-14 | AC-30 | 纯前端（F-12） |
| | | **AC-33** | **B-6, B-20** |
| | | **AC-34** | **B-20** |
| | | **AC-31** | **B-1, B-2, B-15, B-16** |
| | | **AC-32** | **B-10, B-11** |

**反向**：B-1~B-20 每项在上表都至少出现一次 ✅ —— 无「没人要的功能」。

> ⚠️ **AC-30（含量去尾随零）与 AC-29（工具栏禁用态）是纯前端断言**，后端不认领；但 AC-30 有一条**反向的后端断言**（库内仍是 `90.000000000000`），由 `B-19` 的回归用例一并锁住 —— 防止前端为了显示好看而把去零做到了存储层。

---

## 必须回归的既有测试

| 测试 | 为什么会受影响 | 要求 |
|---|---|---|
| `MaterialRecipeImportServiceTest`（11 例） | 旧两 sheet 语义整体作废 | **按新格式重写，不许直接删**。真实文件基线 `realFile_R1Import_253Materials_1Skip` 换成新格式的等价基线；含量越界、Σ≠1、×100 归一、元素主表同步四类用例的**断言意图必须保留** |
| `MaterialRecipeListSearchTest` | 列表响应加了三个字段 | 断言补新字段，原有搜索语义不得变 |
| `MaterialRecipeUpdateCodeReadonlyTest` | 材质 Upsert 改了请求体 | **材质编号只读**这条不得被破坏 |
| `ElementServiceTest` | 元素行归属变了 | 「被引用数」「符号锁」计数须与改动前一致（B-19） |
| `DemoMaterialRecipeFixture` | 夹具需补配置层 | 不改则全量测试红 |
| 后端全量 `./mvnw test` | — | 合并前主线会在**主仓**再跑一次（worktree 绿 ≠ 主仓绿，`RECORD` 有前科） |
