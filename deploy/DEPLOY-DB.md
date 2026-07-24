# 服务器部署 — 数据库初始化

> 一次性建成终态数据库，替代重放 345 条 Flyway 迁移。
> 生成日期 2026-07-19，源库快照 `cpq_db @ PG 16.13`，Flyway 基线 **V343**。

## 先选对文件 ⚠️

提供两个版本，**内容等价、产出的数据库逐字节相同**，区别只在客户端兼容性：

| 文件 | 用什么导入 | 大小 |
|---|---|---|
| `cpq-init.sql` | **只能用 psql 命令行** | 698K |
| `cpq-init-navicat.sql` | **Navicat / DBeaver / pgAdmin 等 GUI 客户端** | 1.1M |
| `cpq-functions.sql` | 兜底文件，仅在主脚本末尾函数区失败时单独补跑 | 2K |

**用 GUI 客户端导入 `cpq-init.sql` 一定会失败**，报错停在建函数处。原因：

- 它含 161 个 `COPY ... FROM stdin` 块 + `\.` 终止符 —— 这是 psql 的客户端协议，GUI 客户端无法执行；
- 它含 `\echo` 等 psql 元命令；
- 3 个函数体用 `$$ ... $$` 包裹、内部有分号，GUI 客户端的语句切分器会把它们**从中间切碎**
  （实测：用引号感知但不认 `$$` 的切分器解析，会产生 **190 个非法片段**，
  开头分别是 `BEGIN` / `END IF` / `RETURN COALESCE(v_ver, 2000)` / `$$`）。

`cpq-init-navicat.sql` 已消除以上全部构造：数据段改为 1701 条标准 `INSERT`，
无任何 psql 元命令，3 个函数体改写为单引号字面量（内部单引号加倍转义）。
同一切分器解析该版本产生 **2814 条语句、0 个非法片段**。

### 函数区的两轮修复（Navicat 实测）

第一轮改成单引号后 Navicat **仍在同一个函数处中断**。排查发现完美相关性：

| 函数 | 函数体内非 ASCII 字符 | Navicat |
|---|---|---|
| `current_part_version` | 0 个 | ✅ 通过 |
| `get_bom_components(text)` | 8 个（`-- 锚点：第一层数据` 等中文注释） | ❌ 中断 |
| `get_bom_components(text,text)` | 8 个（同上） | ❌ 中断 |

唯一通过的，恰好是函数体内一个中文字符都没有的那个。第二轮修复：

- **函数体改为纯 ASCII** —— 去掉体内的中文 `--` 注释和 TAB 字符
  （用真实 109 行 `material_bom_item` 数据遍历全部输入验证，
  去注释前后逐行一致：`400/400`、`88/88`，差异 0）
- **整个函数区移到文件最末尾** —— `pg_depend` 实测**无任何数据库对象依赖这 3 个函数**，
  放最后可把「函数体解析」这个唯一风险点隔离出去：万一你的客户端仍在此处失败，
  前面的表 / 视图 / 索引 / 数据都已建好，只需再单独跑一次 `cpq-functions.sql`。

> 注：中文注释导致 Navicat 中断的**确切机制未能坐实**（Navicat 只报
> `[SQL] Process terminated`，没给出 PostgreSQL 错误码）。以上是按相关性消除触发条件。
> 如果仍然失败，请检查 Navicat 的**文件编码设置是否为 UTF-8**（菜单：运行 SQL 文件 → 编码）。

### Navicat 操作步骤

1. 右键连接 → **新建数据库** → 名称 `cpq_db`，编码 `UTF8`
2. 右键该库 → **运行 SQL 文件** → 选择 `cpq-init-navicat.sql` → 开始
3. **勾选「遇到错误时停止」**，不要选「忽略错误继续」——
   否则出错也会跑到底，你会得到一个残缺的库却看不出来
4. 跑完执行文件末尾的 5 条自检 SQL，逐条核对期望值

---

## 1. 为什么不重放 Flyway 迁移

不是"迁移会建很多无用的表"——实测 162 张表里 **158 张有活跃代码引用**，真正的死表只有 1 张
（`_bak_component_formulas_20260612`，一张日期备份表，已剔除）。

不重放的真正理由有两条：

1. **迁移重放得不到完整系统**。部分关键配置是当初用 UI 建的，从未进入迁移文件。
   最典型的是 `costing_bom_tree_config` —— 缺这一行，核价树渲染直接抛
   `400 未配置生效的核价树递归 SQL`。光跑 Flyway 得不到它。
2. **345 条迁移串行重放慢且脆**，中间任何一条的历史包袱（改名、乱序、checksum）都可能中断部署。

本脚本是从运行中的库直接导出的**终态快照**，与现网 schema 逐表、逐列、逐约束比对一致。

---

## 2. 执行（psql 路线）

```bash
# 1) 建空库（必须是全新空库，脚本不含 DROP）
createdb -h <db-host> -U <user> cpq_db

# 2) 执行初始化
psql -h <db-host> -U <user> -d cpq_db -v ON_ERROR_STOP=1 -f cpq-init.sql
```

GUI 客户端路线见文件开头「先选对文件」一节。

脚本末尾会自动打印 5 项自检，全部对上才算成功：

| 检查项 | 期望值 |
|---|---|
| 表 / 视图 / 函数 | `161 / 10 / 3` |
| 非空表数 | `20`（19 张种子表 + 1 行 Flyway 基线） |
| 管理员 | `admin / SYSTEM_ADMIN / ACTIVE` |
| 核价树 active 配置 | `1` |
| Flyway 基线 | `343 / BASELINE / t` |

**要求 PostgreSQL 16。** 脚本已剔除 pg_dump 18 生成的 `\restrict` 指令和
`SET transaction_timeout`（PG17+ 才有的 GUC），确保在 PG16 上可执行。

---

## 3. 默认账号

```
admin / Admin@2026
```

`is_first_login = false`，登录后不强制改密。**上线后请立即修改** —— 这是开发环境同款口令，
bcrypt hash 直接来自开发库。

---

## 4. 脚本包含什么

**结构**：161 张表 / 10 视图 / 3 函数 / 8 序列 / 全部索引与约束。

**系统种子（19 张表）**：

| 类别 | 表 | 行数 | 关键性 |
|---|---|---|---|
| 系统配置 | `system_config` | 25 | 🔴 硬依赖，缺 key 时 `SystemConfigService` 直接抛 404 |
| | `costing_bom_tree_config` | 1 | 🔴 硬依赖，缺失则核价树抛 400；**不在任何迁移文件里** |
| | `basic_data_config` | 73 | Excel 导入 sheet 配置，空表则导入静默跳过 |
| | `basic_data_attribute` | 504 | 跟随上表的字段级配置 |
| | `comparison_tag` | 24 | 内置比对标签 |
| | `variable_label` | 22 | 视图列中文标签 |
| | `global_variable_definition` | 6 | 下游 FK 父表 |
| | `sel_param_type` | 3 | 与 Java handler key 强耦合 |
| | `composite_process_def` | 6 | 下游 FK 父表 |
| | `part_no_sequence` | 9 | 料号段（代码有懒创建，预置更稳） |
| 组织架构 | `region` / `department` / `product_category` | 4 / 3 / 1 | 报价 Step1 无产品分类会禁用「下一步」 |
| | `user` | 1 | admin |
| 主数据 | `element` / `process` / `process_master` | 39 / 41 / 43 | |
| | `material_recipe` / `material_recipe_element` | 263 / 632 | task-0708 材质库成果 |

---

## 5. 脚本【不】包含什么

按"真·空库"要求，业务数据一律不带：

- **组件 / 模板骨架**（`component` 73、`component_sql_view` 54、`template` 97、
  `template_component` 641、`costing_template` 17 等）
  > ⚠️ 这意味着新环境**没有任何报价/核价模板**，上线后需要人工重建客户模板
  > （罗克韦尔 / 施耐德 / 森萨塔 / 西安中熔 / 核价通用 v1）。
  > 如果后续想改为带走，从源库按目录白名单导出即可，注意排除
  > `T2 Costing Tpl` / `CTPL-LIST-01` / `asd` / `Lifecycle Dir` 等测试残留。
- **业务单据**：客户、报价单、核价单、产品
- **V6 基础资料**：`material_master`、`material_bom*`、`element_bom*`、`unit_price` 等

有两张表是**刻意不预置**的，不是遗漏：

- **`bnf_table_meta`** —— 应用启动时由 `BnfTableMetaSyncer`（全工程唯一的
  `@Observes StartupEvent`）扫 `information_schema` 自动重建。预置反而会把旧库的陈旧登记
  带进新环境：现网 97 行里有 **44 行指向已不存在的对象**（4 张幽灵表 + 40 个幽灵视图）。
- **`datasource`** —— 现网该表 3 行全是测试数据，其中 `TEST_BAD_SQL_001` 的 SQL 内容是
  `DELETE FROM customer WHERE 1=1`。**必须排除**。

---

## 6. Flyway 后续迁移

脚本插入了一行基线：`version=343, type=BASELINE`。

配合 `application.properties` 里已有的 `%prod.quarkus.flyway.migrate-at-start=false`，
生产启动**不会**自动跑迁移。将来要应用 V344+ 的增量迁移：

```bash
# 临时开启，一次性应用
QUARKUS_FLYWAY_MIGRATE_AT_START=true java -jar quarkus-run.jar
```

Flyway 会跳过所有 ≤343 的迁移，只应用 344 及以后。

> ⚠️ 基线定在 343 之后，**仓库里 V343 及以前的迁移文件不能再改名改号**
> （项目有"共享 Flyway 历史被并发篡改"的既往教训）。

---

## 7. 部署前必须确认的环境变量

`application.properties` 里 DB 连接的**默认值指向开发库**：

```properties
quarkus.datasource.jdbc.url=jdbc:postgresql://${DB_HOST:10.177.152.12}:${DB_PORT:5432}/${DB_NAME:cpq_db}
quarkus.datasource.username=${DB_USERNAME:postgres}
quarkus.datasource.password=${DB_PASSWORD:joii5231}
```

- 走 `docker-compose`：compose 文件**会**从 `.env` 传 `DB_*`，按 `.env.example` 填好即可，
  漏填会因空 host 连接失败而**显式报错**，不会连错库。
- 绕过 compose 直接 `java -jar quarkus-run.jar`：**若不设 `DB_*` 环境变量，会静默连回
  开发库 `10.177.152.12/cpq_db`**。这是本次部署最需要提防的一点。

另需设置（见 `.env.example`）：

- `CPQ_ENCRYPTION_KEY` —— 必须恰好 32 个 ASCII 字符，**上线前务必轮换**；
  一旦用某个 key 加密了数据，不重新加密就无法更换。
- `REDIS_*` —— 会话存储依赖 Redis，不可省略。
- `CPQ_MODEL_CONFIG_STORAGE_DIR` —— `ModelFileStorageService` 的 `@PostConstruct` 在该目录
  不可写时会**抛 `IllegalStateException` 导致启动失败**。默认值是 `java.io.tmpdir`，
  容器里非持久化，生产建议显式指向持久盘。

---

## 8. 注意：健康检查不能作为部署成功的判据

`/api/cpq/health` 返回的是**硬编码 `UP`**，完全不碰数据库。空库也会返回 UP。
判断部署是否成功，请以脚本末尾的 5 项自检 + 实际登录为准。
