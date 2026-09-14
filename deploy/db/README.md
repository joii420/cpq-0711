# `deploy/db/` · v2 数据库交付目录

> **这个目录管什么**：内网项目的数据库**初始化**与**增量升级** SQL。
> **为什么单独立目录**：`deploy/` 根目录下的历史脚本（`cpq-init*.sql` / `0804-dbupdate.sql` …）是 v1，
> 命名无规律、基线号散落在文件头注释里、增量与全量混放。v2 起一律走本目录，规则见下。
>
> 🚫 **不要改 `deploy/` 根目录下的 v1 脚本** —— 它们已停用，仅作历史追溯。

---

## ① 当前节点（每次发布必须更新这一节）

| 项 | 值 |
|---|---|
| **全量初始化脚本** | `cpq-init-empty.sql` |
| **Flyway 基线** | **V439**（全量脚本自身） |
| **生成时间** | 2026-09-09 |
| **源库** | `cpq_db_0724`（schema-only + 系统配置种子） |
| **内容** | **251 表 · 29 视图 · 106 序列 · 6 函数 · 703 行种子 · 1 个 admin 用户** |
| **文件 md5** | `cd311bdf54d87dd9c93ba5464c94c7fd` |
| **已应用的增量** | `update-260913-v440-v443-record-batch-and-price.sql`（V440~V443） |
| 🎯 **全部执行后的基线** | **V443** |

### 增量清单（按顺序执行）

| 顺序 | 文件 | 源迁移 | 改了什么 | md5 |
|---|---|---|---|---|
| 1 | `update-260913-v440-v443-record-batch-and-price.sql` | V440 / V441 / V442 / V443 | 核价取价改客户级单键 · 语义图加 `ROW_SCOPE` 角色 · 13 张 `ds_quote_*_record` 加 `import_batch_id` 且 `quotation_id` 放开可空 · 取价函数补 `_record` 分支 | `5339712c91327ba92bfb2aae6035610a` |

### 这份全量脚本刻意**不含**什么

| 排除对象 | 数量 | 理由 |
|---|---|---|
| `v_compat_material_master` / `_material_bom_item` / `_element_bom_item` | 3 视图 | V439 已将其退役（用户 2026-09-09 裁定「退役后的干净态」） |
| 人工备份表 | 10 表 | 一次性数据修复前的快照，不是业务对象。清单见 §④ |
| `COMMENT ON` | 全部 | 用 `--no-comments`，与源库的一处已知差异，无运行时语义 |

⚠️ **`V9ZeroRegressionTest` 在本脚本建出的库上会红，属预期不是回归** ——
它断言 `component_sql_view_backup_260903` 非空，而该表由 `V410` 从当时的存量数据现建；
基线 439 ⇒ V410 不重放 ⇒ 全空库里无从产生备份行。**建与不建该表对这条测试是同一个结果。**

---

## ② 升级文件的命名与生成规则

### 命名

```
update-<YYMMDD>-<简短英文描述>.sql        例：update-260915-add-quote-tax-column.sql
```
- 日期用 `date +%y%m%d` **实取**，不许凭记忆写
- 同一天多个用 `-a` / `-b` 后缀区分

### 什么时候要生成

> **凡是改变数据库结构或系统配置种子的 Flyway 迁移进入 master，就要在本目录同步生成一份 `update-*.sql`。**

因为内网项目**不跑 Flyway**（它用全量脚本建库 + 增量脚本升级），所以：
- 只改代码不改 schema → 不需要
- 新增/修改表、列、索引、约束、视图、函数 → **需要**
- 改系统配置种子（`semantic_*` / `costing_bom_tree_config` / `sel_param_type` 等） → **需要**
- 只改业务数据 → 不需要

### 每个 `update-*.sql` 必须包含

1. **文件头**：源迁移版本号（如 `V440`）· 生成日期 · 一句话说明改了什么
2. **幂等**：一律 `IF NOT EXISTS` / `IF EXISTS` / `CREATE OR REPLACE`，**必须可重复执行**
3. **末尾更新 flyway 基线**：
   ```sql
   UPDATE flyway_schema_history SET version = '<新版本号>', script = '<< Flyway Baseline >>'
    WHERE installed_rank = 1;
   ```
   🚨 **基线号不上调的后果**：内网库若将来接上 Quarkus，会重放中间的迁移，
   而其中**有非幂等的**（`CREATE TABLE` 无 `IF NOT EXISTS`、`RENAME COLUMN`），会因
   「表已存在 / 列不存在」**启动失败**。
4. **自检段**：文件末尾附可粘贴执行的验证 SQL（期望值写在注释里）

### 生成后必须做的两件事

1. **更新本文件 §① 的「当前节点」**（基线号 + 已应用增量列表）
2. **在临时库跑一遍**：全量脚本 + 全部增量按顺序执行，`ON_ERROR_STOP=1`，
   与源库做六维比对（表 / 列 / 索引 / 约束 / 函数 / 视图）

---

## ③ 🚨 Navicat 兼容规则（7 条，踩坑换来的，逐条必守）

内网用 **Navicat** 执行，不是 psql。以下每条都出过事故：

| # | 规则 | 违反的后果 |
|---|---|---|
| 1 | 🚫 不许 `COPY … FROM stdin` + `\.` | psql 客户端协议，GUI 跑不了。数据用 `INSERT`（`pg_dump --column-inserts`） |
| 2 | 🚫 不许任何 psql 元命令（`\echo` / `\restrict` / `\connect`） | GUI 不认。**`pg_dump` 18.x 会自动吐 `\restrict`，必须剔除** |
| 3 | 🚨 函数体用**单引号** `AS '…'`，不许 `$$` | 朴素切分器会被 `$$` 切碎（本项目函数体内含分号）。单引号是所有客户端的最低共识 |
| 4 | 🚨 函数体内**0 个非 ASCII 字符**，无 TAB | 实测：含中文注释的函数**必失败**，且 Navicat 只报 `Process terminated` **无 PG 错误码**，极难排查 |
| 5 | 🚨 函数区**整体移到文件末尾** | 把唯一风险点隔离在最后，前面全部执行完才可能出问题 |
| 6 | ⚠️ 全文扫**孤立的 `$$`** | 曾在 `component_sql_view.scope` 的 `COMMENT` 文案里发现一个，对按 `$$` 配对的解析器是永不闭合的块 |
| 7 | 🚨 剔除 **PG17+ GUC** | 本机 `pg_dump` 18.x，目标库 PG 16.13。`SET transaction_timeout` 在 PG16 上直接报错 |

### 附加两条（同样出过事）

- **`CREATE SCHEMA public;`** → 改 `CREATE SCHEMA IF NOT EXISTS public;`（Navicat 新建的空库里 `public` 已存在）
- **`search_path=''`** → 改 `SET search_path = public;`（否则 `"user"` 这类无 schema 限定的引用解析失败）

### 兼容性自验手法（可复用）

写一个「**引号感知但不认美元引用**」的切分器模拟 GUI，切完断言**每个片段都以合法 SQL 关键字开头**。

当前 `cpq-init-empty.sql`：**2167 片段 / 非法开头 0 / 引号全闭合**，且片段数与 psql 实跑输出行数 **1:1 对上**。
（对照：v1 的 `deploy/cpq-init-empty-navicat.sql` 有 **9 个非法片段** —— 它的 12 处 `$$` 从未改过，**函数区在 Navicat 里本来就是会炸的**。）

---

## ④ 被排除的 10 张人工备份表

判据（**改这条判据的人务必看 §⑤ 的陷阱**）：
```sql
relname ~ '^(_bak|bak_|zz_|tmp_|temp_)'
  OR relname ~ '(_backup|_bak)(_[0-9]+)?$'
  OR relname ~ '[0-9]{6,8}$'
```

| 表 | 源库行数 | 被谁引用 |
|---|---|---|
| `_bak_bl0098_20260803` | 98 | 无 |
| `_bak_component_formulas_20260612` | 0 | 无（`RECORD.md` 认定的全库唯一真死表） |
| `bak_task260901_b0` | 1845 | 无 |
| `bl0092_orphan_backup_20260802` | 241 | 无 |
| **`component_sql_view_backup_260903`** | 135 | ⚠️ `V9ZeroRegressionTest` + `V410` 迁移（见 §① 说明） |
| `flyway_dup_backup_20260803` | 4 | 无 |
| `mcm_pending_backup_20260801` | 3 | 无 |
| `zz_d3_bk_cd` / `_li` / `_q` | 8 / 1 / 1 | 无 |

---

## ⑤ 🚨 两个判据陷阱（改排除规则前必读）

### 陷阱 1 · `temp` 必须写成 `temp_`

判据里的 `temp_` **必须带下划线锚在开头**。若写成 `~ 'temp'`，
**16 张 `*template*` 业务表会被整片误删**（`template` / `template_component` / `sel_template` /
`product_config_template` / `import_mapping_template` / `costing_template` …）。

### 陷阱 2 · `[0-9]{6,8}$` 的位数限制不能松

`exchange_rate_v6` 以数字结尾，宽网 `[0-9]+$` 能捞到它 —— 它是**活的 V6 汇率主数据表，必须保留**。
是判据的 **6~8 位**长度限制挡住了（`v6` 只有 1 位）。**把 `{6,8}` 改成 `+` 会误删它。**

⇒ **改判据后必须做反向核对**：用更宽的网扫一遍，逐个确认「被宽网捞到但判据放过的」都是该保留的业务表。

---

## ⑥ 生成脚本时的两条方法纪律

这两条都是**假绿事故**换来的，比规则本身更值得记：

1. 🚫 **比对类脚本禁止吞 stderr，且必须打印每一维的基数。**
   实证：某轮比对脚本里 `CONSTRAINT` 和 `FUNC` 两条查询报错，stderr 被重定向进 `/dev/null`，
   两维在两边的签名文件里**都是 0 行** ⇒ **拿空集对空集比出了「全等」**，据此报了「六维零差异」。
   ⇒ 加 `ON_ERROR_STOP=1` + 断言 stderr 字节数为 0 + **逐维度打印总数**（`0/0` 一眼可见）。

2. 🚫 **「空输出 = 通过」是最常见的假绿形态。**
   实证：验证约束等价性的脚本正则没匹配上、直接抛异常、输出 0 行，
   而判据写的是「无输出即一致」⇒ 假绿。
   ⇒ 任何「无输出即通过」的断言，必须同时核对 **exit 码 + 输出行数 + 用例数** 三项。

3. ⚠️ **行数用 `query_to_xml` 取真实 `count(*)`，不许用 `pg_stat_user_tables.n_live_tup`** ——
   那是延迟统计，刚建完库读到的是假值。

4. ⚠️ **本环境 `grep` 是 `ugrep -I`**，中文多的大文件会被**静默判为二进制返空** ——
   而本目录的产物正是 800+ KB 的中文大文件。**一律 `/usr/bin/grep -a`。**

---

---

## ⑧ 三层库隔离与 `cpq_db_test` 的定期同步

| 库 | 用途 | 谁指向它 | 纪律 |
|---|---|---|---|
| **`cpq_db_0910`** | 用户的**真机验证库** | `application-uat.properties`（合于 `5a294c35`）+ Navicat 手工连 | ✅ 可读写排查 · 🚫 **不做批量/自动化测试、不造数** |
| **`cpq_db_0724`** | 开发共享库 | `application.properties` | dev server(8081) + 各会话调试 |
| **`cpq_db_test`** | 自动化测试库 | `application-test.properties`（合于 `b40d725a`） | `mvnw test` 打这里 |

### uat profile 怎么起（连 `cpq_db_0910`）

```bash
cd cpq-backend && ./mvnw quarkus:dev -Dquarkus.profile=uat      # 端口 8091
VITE_API_TARGET=http://localhost:8091 npm run dev               # 前端指过来
```

⚠️ **端口是 8091，不是 8081** —— 项目里 5 个既有 profile **全写 `quarkus.http.port=8081`**，
端口实际不由 profile 决定（平时靠 `-Dquarkus.http.port=` 覆盖）。uat 直接把默认值写成
`${UAT_HTTP_PORT:8091}`，这样它能与 dev server(8081) **同时在跑**而不抢端口。

📌 启动日志里的 `missing table [mat_composite_process]` 是**既有问题不是本 profile 的**：
`cpq_db_0910` 与 `cpq_db_0724` 的 `mat_*` 表数**均为 0**（记忆 `mat-tables-frozen-since-0602`），不阻止启动。

⚠️ **隔离依赖环境变量未被覆盖**：配置写的是 `${DB_NAME:cpq_db_test}`，**只是默认值**。
谁 `export DB_NAME=cpq_db_0724`，测试照样打开发库。临时要打回共享库用
`DB_NAME=cpq_db_0724 ./mvnw test`，🚫 **不要改回配置文件**。

### `cpq_db_test` 会漂移，需要定期重克隆

它是某个时点从 `cpq_db_0724` 克隆的快照 —— **schema 由 Flyway `migrate-at-start` 自动跟进，
但数据不会同步**。时间一长，测试跑在越来越陈旧的数据上。

```bash
bash deploy/db/refresh-test-db.sh          # 交互确认后执行
bash deploy/db/refresh-test-db.sh --yes    # 跳过确认（明确授权时）
```

**脚本内置的护栏（已实测生效，不是写了就算）**：
- 目标库**白名单**：只允许 `cpq_db_test`，写成别的名字直接拒
- 源库 ≠ 目标库；`cpq_db_0910` **不允许作为克隆源**
- 执行前查 `pg_stat_activity`，**目标库有连接就拒**（防止有人正在跑测试）
- 源库表数 < 100 视为连错库，中止
- 按 §3.2 打印影响面数字与可恢复路径，非 `--yes` 时要求输入 `yes`
- 克隆后**六项验证**（表/视图/序列/函数/flyway 最高版本/用户数），任一不一致即失败退出

🚨 **本脚本会销毁 `cpq_db_test` 全部内容**（§3.2【数据销毁】）。它按定义是一次性测试库、无独有数据，
**脚本本身即恢复手段**（重跑一次即可）。

## ⑦ 变更记录

| 日期 | 动作 | 内容 |
|---|---|---|
| 2026-09-09 | 建立目录 + 首版全量 | `cpq-init-empty.sql`，基线 V439，251 表 / 29 视图。排除 3 个 `v_compat_*` 与 10 张人工备份表 |
| 2026-09-09 | 三层库隔离落地 | `application-test.properties` 改指 `cpq_db_test`（合 `b40d725a`）；新增 `refresh-test-db.sh` 定期同步脚本；§⑧ 记录隔离口径 |
| 2026-09-09 | uat profile 落地 | 新增 `application-uat.properties`（合 `5a294c35`）连 `cpq_db_0910`，端口 8091；已实测启动（Flyway `up to date`、业务端点 401）|
| 2026-09-13 | 首份增量脚本 | `update-260913-v440-v443-record-batch-and-price.sql`，基线 V439 → **V443**。覆盖 V440~V443 四个迁移。**V442 的 `DO` 块已按 §③ 展开为 13 组静态 DDL，V443 的函数体已去中文 + 改单引号并移至文件末尾**（见 §⑨ 验证足迹）|

---

## ⑨ 验证足迹 · `update-260913`（首份增量，方法可复用）

### 做了什么验证

| # | 验证 | 结果 |
|---|---|---|
| 1 | Navicat 七条规则静态自检（切分器模拟 GUI） | **57 片段 / 非法开头 0 / 引号全闭合 / 全文 0 个 `$`** |
| 2 | 临时库实跑第 1 遍（`ON_ERROR_STOP=1`） | exit 0，**stderr 0 字节**，11 条自检值全部命中期望 |
| 3 | **幂等**：第 2 遍执行 | exit 0，两遍自检输出**逐字节相同**；stderr 全是 `IF NOT EXISTS` 的 `NOTICE ... skipping`，非错误 |
| 4 | **还原实验**（防空验证） | 删 1 张表的列 ⇒ 自检 `13→12` 变红；改 1 个节点函数签名 ⇒ `2 0` 变 `1 1`。**证明这套自检不是恒绿** |
| 5 | 六维比对 vs 源库 `cpq_db_0724` | 表 251/251 · 列 3852/3852 · 索引 743/743 · 约束 582/582 · 函数 6/6 · 视图 **29/32** |
| 6 | **A/B 增量归因**（见下） | update 后的库 vs 纯基线库，差异**恰好等于**声明的改动，误伤 0 |
| 7 | 函数行为等价 | 在同一库里用源库原文建改名参照函数，**执行计划 98 行逐行相同** |

### 🚨 三类差异的归因（下一个做增量的人必看）

六维比对**不会全等**，而且其中两类**与你的增量无关**。把它们误判成自己引入的，会浪费一整轮排查：

| 差异 | 数量 | 归因 | 怎么证的 |
|---|---|---|---|
| `CHECK` 约束的渲染形态<br>`ARRAY[('X'::varchar)::text,…]` vs `(ARRAY['X'::varchar,…])::text[]` | 14 对 | **基线自带** —— `pg_dump` 重建时 PG 重新规范化了表达式，语义等价 | 建**纯基线库**（只跑全量脚本、不跑任何增量）与源库比，这 28 行差异**已经存在** |
| `get_bom_components` 函数 md5 | 1 | **基线自带** —— 全量脚本对它做过 ASCII 化处理 | 同上，纯基线库里就已不同 |
| `v_compat_*` 三视图缺失 | 3 | **刻意排除**，见 §① | — |

⇒ **方法纪律：判「是不是我引入的」只能做 A/B 同型对比**，
拿「update 后的库」直接跟源库比得到的差异里，混着基线自带的噪音。
正确姿势是**再建一个纯基线库**做三方对比：

```
纯基线库  vs  源库        -> 基线自带的噪音（本次 87 行）
update后  vs  纯基线库    -> 你的增量（本次 54 行，应逐条对得上你的声明）
```

### 本次增量的预期差异（54 行，逐条可核）

- `COLUMN` **39 行** = 13 张 `ds_quote_*_record` ×（新增 `import_batch_id` 1 行 + `quotation_id` 的 `notnull` 由 `true`→`false` 各 1 行）
- `INDEX` **13 行** = 13 个 `idx_ds_quote_*_batch`
- `FUNC` **2 行** = `f_material_element_price` 三参版换体
- `TABLE` / `CONSTRAINT` / `VIEW` **各 0 行** ← **零误伤的硬证据**
- ⚠️ `semantic_*` 的配置数据变更（V440/V441）**不在 schema 签名里**，由自检 6.1~6.6 覆盖

### ⚠️ 一条自己踩的坑

还原实验第一轮，`run_check()` 里写了 `2>/dev/null` —— **正是 §⑥ 第 1 条禁止的吞 stderr**，
结果干预后输出全空，差点被读成「自检挂了」。去掉重定向重跑才看清：干预**确实被抓到**（`13→12`）。
⇒ **验证脚本自己也要守 §⑥**，不只是比对脚本。
