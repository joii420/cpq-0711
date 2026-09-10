# backtask · 后端任务分解

> 只按本文件做。AC 原文在 `需求文档.md §③`，**这里只标编号，不复制原文**。契约以 `api.md` 为准。
> 🚨 本任务触及 `field_type`（`AP-44` 核心对象）。§0 检查点清单**必须逐项勾掉**才算写完。

**改动面预估：后端很小** —— `BuilderConfig.fieldType` 已存在、`BuilderService:881` 已消费、`:890` 的 `BASIC_DATA` 分支已存在。本次只补**校验**与**方言默认**。

---

## §0 检查点清单（`AP-44` + `change-protocol.md §4`）

grep 一律用 `/usr/bin/grep -a`（本环境 `grep` 是 `ugrep -I` 别名，会把中文注释多的源文件**静默判为二进制返空**）；**空结果 ≠ 不存在**。优先 `codegraph_impact`。

| # | 检查点 | 关注什么 | 勾 |
|---|---|---|---|
| 1 | 后端 `field_type` 白名单 | 现为**零校验**，`col.fieldType` 原样写进 `field_type` | ☐ |
| 2 | 绑定键分派（`BuilderService:890`） | `BASIC_DATA` → 顶层 `basic_data_path`；`INPUT_*` → `default_source.path`。🚫 **不动这条不变量**（`D-73/B-30`） | ☐ |
| 3 | 前端类型定义 | `SelColumn.fieldType` 已存在（`SqlViewBuilderTab.tsx:164`）；跨端值域须一致 | ☐ |
| 4 | 数据规范化 / 映射 | 保存与回读两条路径上 `fieldType` 是否被静默丢弃 | ☐ |
| 5 | **缓存 key 维度**（`AP-37`） | 改 `field_type` 会不会命中旧缓存（`expandCache` 的 fieldsHash 维度） | ☐ |
| 6 | 渲染分支（列表 / 编辑 / 详情） | `ComponentCell` 的 `BASIC_DATA`(:394) 与 `INPUT_*` 两条分支；**三处视图分别验** | ☐ |
| 7 | 公式引擎的字段值循环 | `BASIC_DATA` 字段能否被公式引用（`FORMULA` 字段引用它时取不取得到值） | ☐ |
| 8 | 路径采集 / 依赖预取 | `parseBasicDataPaths` 是否覆盖顶层 `basic_data_path`（`INPUT_*` 走 `default_source.path`，两个键不同） | ☐ |
| 9 | 导出（Excel / PDF） | `BASIC_DATA` 字段在导出分支是否有值 | ☐ |
| 10 | **已存快照兼容**（`AP-39`） | 已发布模板的 `components_snapshot` 冻的是旧 `field_type`；改配后须重新对齐才生效（`task-260909-核价树骨架` 刚踩过） | ☐ |
| 11 | 行身份推导 | `part_no_field` / `part_name_field` / `row_key_fields` 按**字段名**推，与 `field_type` 无关 —— **本次要实证该假设**（AC-10） | ☐ |
| 12 | `basicDataValues` 键格式 | `basic_data_path` 存 `$view.col`，`basicDataValues` 键是 `{$view.col}`，中间隔 `bnfDriverLookupKey()`。**AC-6 的风险就在这** | ☐ |

---

## B-1 · `fieldType` 白名单校验

**服务的 AC**：AC-8, AC-9

- 位置：`BuilderService`（`:880~882` 一带）或请求校验层
- 合法值**恰好 3 个**：`BASIC_DATA` / `INPUT_TEXT` / `INPUT_NUMBER`
- 非法值（含 `FORMULA` / `DATA_SOURCE` / `FIXED_VALUE` / 垃圾串）→ **400**，消息列出 3 个合法值
- **不传 / null → 走默认推导**（B-2），🚫 不得报错（AC-9 向后兼容门禁）
- 🚫 校验失败时**不得有任何落库**（AC-8 的「库中不发生变更」）

## B-2 · 默认值按方言

**服务的 AC**：AC-3, AC-4, AC-5

- 位置：`BuilderService:880` 的默认推导
- 规则：
  - `dialect` 为 `COST_BASIC` / `COST_DETAIL` → 默认 **`BASIC_DATA`**
  - `dialect` 为 `QUOTE` → 默认按 `col.resolvedDataType` 推 `INPUT_TEXT` / `INPUT_NUMBER`（**现状逐位不变**）
- ⚠️ **只改默认值**：`col.fieldType` 非空时**恒以它为准**（用户显式选择优先）
- 🚫 **不动绑定键分派**（`:890`）：`BASIC_DATA` 仍写顶层 `basic_data_path`、`INPUT_*` 仍写 `default_source.path`。
  `D-73/B-30` 的不变量「绑定键跟 `field_type` 走，不跟侧走」**原样保留** —— 本次改的是 `field_type` 的**初值**，不是绑定键的**规则**
- 用 `CompileDialect.isCosting()` 判定，🚫 不要逐个枚举值写 `==`（漏一个就是静默走错分支，该方法的 javadoc 明写了这条）

## B-3 · 测试

**服务的 AC**：AC-8, AC-9, AC-3~AC-5 的落库层

- 白名单：3 个合法值各一例通过 + `FORMULA` / 垃圾值各一例 400 + 不传一例走默认
- 方言默认：三个方言各一例，断言 `field_type` **与绑定键写在哪个位置**（顶层 vs 嵌套）
- 🚫 **不许写清库型测试**（`mvnw test` 直接写共享开发库 `cpq_db_0724`）
- 🚫 **不许跑全量 `mvnw test`**（`mat_*` 夹具恒红，非本任务引入）
- 🔬 **还原实验**：把 B-2 的方言分支改回「恒按数据类型推」，AC-3 / AC-4 必须变红

## B-4 · 存量零影响与行身份的实证

**服务的 AC**：AC-10, AC-13

- **AC-13 存量零影响**：本次只改**默认值**与**校验**，🚫 不得有任何迁移/回填/批量更新触及存量组件。
  交付时给出交付前后的分布对照（`需求文档 §④` 的基线表），**逐位相同**。
  🔬 判据是「**没变**」，所以必须**先采后验**：改代码前先跑一次分布查询存档，别到最后才回头找基线。
- **AC-10 行身份**：`part_no_field` / `part_name_field` / `row_key_fields` 是按**字段名**推导的，
  与 `field_type` 无关。**本条要实证该假设成立** —— 把料号列配成 `BASIC_DATA` 后：
  - `component.part_no_field` 仍指向该字段名
  - 渲染时该页签的行仍能正确归属到卡片（行数与对照组相同）
  ⚠️ **若实证不成立**（比如推导链某处顺带看了 `field_type`）→ **停下报主线**，那是需求缺口，
  🚫 不许自行加"料号列不许配成 BASIC_DATA"这类约束（那是新增业务规则，属用户裁决）

---

## 越界纪律

- 只做 `B-1`~`B-3`。🚫 **不许改渲染层**（`readonly` 护栏是用户 `D-1` 明确排除的）
- 🚫 **不许迁移存量组件的 `field_type`**（`需求文档 §②` 明确不做；AC-13 是门禁）
- 发现契约问题 → **停下报告**，不许自行改 `api.md`
- 🚨 遇到 `CLAUDE.md §3.2` 红线 → **停下报告**，你没有批准权
