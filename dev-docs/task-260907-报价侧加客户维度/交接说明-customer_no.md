# 交接说明 · 报价侧 `ds_quote_*` 加 `customer_no`

> **给谁看**：接手「基础资料查询（维护端）」单独立项的新会话。
> **本文自足** —— 不需要读 `task-260907-取数配置器补齐` 或本任务的其它文档就能开工。
> **采集时点**：2026-09-07，dev 库 `10.177.152.12:5432/cpq_db_0724`。⚠️ 共享库并发写，行数类数字会漂；**结构类事实（表名/列定义/约束）稳定**。

---

## ① 一句话背景

BOM 树的递归 SQL（`costing_bom_tree_config`，**全系统共用一份**）要从 V6 老表 `material_bom_item` 改读新表 `ds_quote_material_bom`。
那份递归**靠 `customer_no` 做客户隔离**（`ON ch.material_no = b.material_no AND ch.customer_no = b._cust`），而新表体系没有这一列。

**用户裁决**（2026-09-07）：不丢这个维度，而是**把它补进整个报价侧新表体系**。

> 🚨 **不做的后果不是「少个字段」**：本项目**出过跨客户串号故障**（森萨塔，占号表 `material_customer_map` 全局唯一）。
> 实测当前数据下不会串（V6 侧跨多客户的料号 0 个），但那是**「现网恰好没有」而非「设计上安全」**。

---

## ② 要加 `customer_no` 的表：**28 张**

### 列定义（🚫 不要自己定，照抄既有那张）

```
customer_no   character varying(20)   NOT NULL   无默认值
```
出处：`ds_quote_customer_part.customer_no` 实查（`information_schema.columns`）。取值口径 = `customer.code`。

### 存量回填（用户裁决）

> **用户原话**：「统一给罗克韦尔的客户号，测试数据不计较数据真实，重点是业务逻辑准确」

**统一 `CUST-0001`（罗克韦尔）**。实测 `customer` 表：`CUST-0001`=罗克韦尔 · `CUST-0004`=正泰 · `CUST-0027`=Import Record Test Customer。

🔑 **裁决的重点在后半句**：回填值**不承担业务正确性**，它只是让**隔离逻辑能被真正验证**的载体。
⇒ 🚨 **验收判据不能是「回填值对不对」，而是「隔离机制在有值的前提下是否真的生效」。**
统一值反而更利于证伪：**所有存量都是 `CUST-0001` ⇒ 任何其它客户的查询/导入都不该碰到它们。**

### 清单（2026-09-07 实查）

**免版本主表 · 2 张**
```
ds_quote_material
ds_quote_plating_scheme
```

**带版本主表 · 13 张**
```
ds_quote_annual_discount        ds_quote_assembly_fee          ds_quote_assembly_fee_annual
ds_quote_element_bom            ds_quote_finished_other_fee    ds_quote_incoming_annual
ds_quote_incoming_fixed_fee     ds_quote_incoming_other_fee    ds_quote_incoming_recovery
ds_quote_material_bom           ds_quote_plating_fee           ds_quote_self_process_fee
ds_quote_sub_component_fee
```

**`_history` 镜像 · 13 张**（与上面 13 张带版本主表一一对应，列定义必须**逐字一致**）
```
ds_quote_annual_discount_history        ds_quote_assembly_fee_history
ds_quote_assembly_fee_annual_history    ds_quote_element_bom_history
ds_quote_finished_other_fee_history     ds_quote_incoming_annual_history
ds_quote_incoming_fixed_fee_history     ds_quote_incoming_other_fee_history
ds_quote_incoming_recovery_history      ds_quote_material_bom_history
ds_quote_plating_fee_history            ds_quote_self_process_fee_history
ds_quote_sub_component_fee_history
```

### 🚫 不要动的（已有或不适用）

| 表 | 情况 |
|---|---|
| `ds_quote_customer_part` | **本来就有** `customer_no`，且唯一索引 `uq(customer_no, customer_product_no)` 已含它 |
| `ds_quote_*_record` **13 张** | **V420 已建且已带** `customer_no varchar(20) NOT NULL`（`核价回填` 会话建的）⇒ **迁移里必须排除，否则撞「列已存在」** |

> ⚠️ **判据用动态扫，不要写静态 `ALTER TABLE` 清单** —— 后续还会有新表加入。推荐：
> ```sql
> SELECT table_name FROM information_schema.tables t
> WHERE t.table_name LIKE 'ds_quote_%'
>   AND NOT EXISTS(SELECT 1 FROM information_schema.columns c
>                  WHERE c.table_name=t.table_name AND c.column_name='customer_no')
> -- 验收断言：返回 0 行
> ```
> 🚫 **不要把「28」写进 AC** —— 本项目实证 `task-260819` 的 `AC-113`/`AC-122` 因判据写死数字，**同一条 AC 上栽了三次**。

---

## ③ 🔴 加列**不是**「跑个 ALTER 就完了」—— 四条夹击的约束

**四条均为逐字复核过的源码事实**（由 `核价回填` 会话交底，主线复核）：

| # | 约束 | 源码 | 不做的后果 |
|---|---|---|---|
| ① | 列集**双向**比对，多一列即启动失败 | `DatasetSchemaSelfCheck.java:112-118`<br>`for (String col : act.keySet()) if (!exp.contains(col)) problems.add(table + " 多出未声明的列: " + col);` | `ALTER TABLE` 加了列、Registry 不同步声明 ⇒ **后端 `onStartup` 抛 `IllegalStateException`，起不来** |
| ② | 🚨 **但不能声明成 `ColumnDef`** | `DatasetSheetParser.java:72-77`<br>`for (ColumnDef c : spec.persistedColumns()) { … if (idx == null) out.missingHeaders.add(c.label); }`<br>`if (!out.missingHeaders.isEmpty()) return out;` | `persistedColumns()` 每列**必须在 Excel 表头出现**。而 `customer_no` 来自**导入时选客户的下拉，不来自 Excel**（16 个 sheet 里只有「客户料号」有该列）⇒ 其余 15 张 sheet 表头校验全报「缺列」，**整份 Excel 拒收** |
| ③ | 出路：走**静态系统列** | `SheetDef.java:20-27` 的 `SYSTEM_COLUMNS` / `VERSION_COLUMNS` / `ARCHIVE_COLUMNS`；`expectedTableColumns():106-113` 把它们并入期望列集 | 静态系统列**进自检、不进 `persistedColumns()`** ⇒ 自检认、表头校验不认，**正是所需形状** |
| ④ | 写入点要显式加 | `VersionedGroupWriter.java:264-267`（`insertAll` 只写 `persistedColumns + INSERT_SYS_COLUMNS`）· `:244-245`（`archive` 只复制 `persistedColumns + ARCHIVE_SYS_COLUMNS`） | 走系统列这条路 ⇒ **这两处必须显式加上 `customer_no`**，否则**列建了、值永远 NULL** |

### 实现形状

1. `SheetDef` 加第四组静态常量（如 `CUSTOMER_COLUMNS`）
2. 🚨 **必须只对报价侧生效** —— `SheetDef` 是**三套数据集共用**的，无条件追加会让**核价两套的自检也要求这一列 ⇒ 核价侧当场起不来**
3. `expectedTableColumns()` / `expectedHistoryColumns()` 按报价侧条件追加
4. `insertAll` 与 `archive` 两处显式带上它

> ⚠️ **④ 的失败形态特别值得记**：列建好了、自检过了、导入也不报错，**只是值恒为 NULL**。
> 届时「`NULL` 行数 = 0」的断言会红 —— **红得对，但排查方向极易跑偏到 DDL 上**（「列不是加了吗？」），而真因在两个写入点的列清单里。

---

## ④ 🔴 最危险的一条：**只加列不扩轴 = 静默删别人的数据**

```
🚨 口径说明（2026-09-07 由 cpq-46 会话指出，主线复核）：两种查法结果不同，统一用 pg_indexes
   pg_constraint WHERE contype='u'        → 0 条   ← 单独引用会误导！
   pg_indexes UNIQUE 非 _pkey             → 3 条   ← 以此为准
   差异原因：那 3 条是 CREATE UNIQUE INDEX 建的，不是表约束，pg_constraint 查不到
   ⚠️ 「UNIQUE 约束 = 0」这句单独被引用时，会让人以为 uq_ds_quote_material 不存在
      —— 而本任务的 B-6 正是要扩它。

13 张带版本表：只有 PRIMARY KEY (id)，无任何业务唯一索引（这条两种口径都成立）
业务唯一索引 3 条，全在免版本表上：
  uq_ds_quote_customer_part(customer_no, customer_product_no)   ← 已含客户
  uq_ds_quote_material(material_no)                             ← 需扩成 (customer_no, material_no)
  uq_ds_quote_plating_scheme(scheme_no, scheme_version, item_seq)
```

带版本表的隔离**根本不靠 DB 约束**，靠 `VersionedGroupWriter` 的**整组删除 + 重插**：
```java
SheetDef.java:30               public final String axisColumn;   // 单列 String，不是 List
VersionedGroupWriter.java:206  "DELETE FROM " + table + " WHERE " + axisCol + " IN (:axes)"
```

⇒ **只加列、不把轴扩成 `(customer_no, material_no)`，客户 A 导入料号 X 会把客户 B 的料号 X 整组删掉 —— 不报错、不撞键、不留痕。**

### 🚫 DDL 与轴模型必须同一批合并

**不允许「先加列、轴模型下一轮再改」。** 中间那个状态最危险：列已经在、值也在填，而删除仍按单列轴走 ——
**看起来一切正常，实际每次导入都在删别的客户的数据。**

> 这是**结构上不让那个状态出现**，强于「发现轴还是单列就停下」那种事后检测 —— 后者依赖有人去看，而该中间态恰恰"看起来正常"。

### 验收判据必须写成「两个客户的行并存」

```
以 CUST-0004 导入料号 X → 再以 CUST-0001 导入同一料号 X
① 写入的每张表 customer_no 全 = 本次所选客户，NULL 行数 = 0
② 🚨 该料号【两个客户的行并存，第一次导入的行未被删除】
```
🔑 **只断言 ① 抓不住静默删除** —— ① 在删除发生后**照样成立**。

---

## ⑤ 轴变复合后，**每一条**写入路径都要能提供 `customer_no`

实测（`grep` 类名 **+ 注入字段名 `versionedWriter`**，并逐个确认是否真的调用）：

| 调用方 | `customerNo` 引用数 | 客户来源 |
|---|---|---|
| `DatasetImportService` | 0 | 导入时选客户（`报价导入切ds新表` 会话的 `QuotationImportService` 提供） |
| **`DatasetMaintenanceService`** | 0 | 🚦 **维护端加客户选择器**（用户 2026-09-07 裁决）—— **就是你要立项的那块** |
| `DatasetCustomerPartService` | 22 | 自带 |
| `SelDsQuoteWriter` | 5 | 选配链路 |
| `ConfigureProductService` | 55 | 选配主链路（`:1077/:1333/:1379/:1441`）。⚠️ `:1205-1265` 有**明确标注的豁免点**至今仍写 V6 表，**不是纯 ds_ 路径** |
| （未来）`核价回填` 的 S-4 | — | 报价单客户（`quotation.customer_id → customer_no`） |

> 🚫 **`DatasetGroupLock` 不在此列** —— 它是 36 行的**锁键工具**（`key(tableName) = "ds:" + tableName`），
> **不调用** `VersionedGroupWriter`；反过来是 `VersionedGroupWriter:140` 调 `DatasetGroupLock.acquire(em, table)`。

### 📌 「找调用方」这件事主线连错三次，错法各不相同

| 次 | 错法 |
|---|---|
| 1 | 按类名 grep，**漏查使用引用** → 漏了 `ConfigureProductService`（DI 项目里「定义引用」与「使用引用」不重合） |
| 2 | **漏查未来调用方** → 漏了尚未写出的 S-4（穷举当下 ≠ 穷举未来） |
| 3 | **没查「引用」是不是「调用」** → 把锁键工具当成写入方 |

⇒ **判据不要落在「点名清单」上，要落在复合轴的签名层**：让新增调用方**编译期就必须提供** `customer_no`。
**清单这个形式本身不可靠。**

---

## ⑥ 你要立项的那块：维护端（基础资料查询）

### 已裁决的两条（用户 2026-09-07）

**A0-2 · URL 表达 → 客户走 query 参数**
```
GET /api/cpq/dataset/{dataset}/parts/{axisValue}/overview?customerNo=CUST-0001
```
受影响的 **5 个端点**（`DatasetMaintenanceResource`）—— 🔄 **2026-09-07 更正：原写 4 个，漏了列表端点本身**：
```
GET /{dataset}/parts                                      ← 🆕 补：列表本身，销售产品列表的数据源
GET /{dataset}/parts/{axisValue}/overview
GET /{dataset}/parts/{axisValue}/sheets/{sheetKey}/rows
GET /{dataset}/parts/{axisValue}/sheets/{sheetKey}/versions
PUT /{dataset}/parts/{axisValue}                          (updatePart)
```
> 由 `cpq-46` 会话指出，主线复核：`GET /{dataset}/parts` 只有 `page/size/keyword`，**没有任何客户输入**
> ⇒ 漏了它，**列表照样混行**。

🔴 **落点页面更正（同上来源，主线复核）**：本节原写「维护端（基础资料查询）」**指向不明**。实测：
```
MasterDataHubPage.tsx:37/38  →  DatasetPartListTab dataset="cost-basic" / "cost-detail"
```
⇒ **「主数据维护」页的两个 Dataset 页签跑的是核价两套** —— 按用户裁决**不加 `customer_no`，压根不受影响**。
全工程**没有** `DatasetPartListTab dataset="quote"` 实例 ⇒ **报价数据集的料号列表只有「产品管理页 → 销售产品」页签在用**。
⚠️ **照本节字面去改 `pages/master-data/dataset/` 会改到一个跑核价数据集的页面上。**

📌 **本块已由 `cpq-46` 承接**，任务号 `task-260907-产品管理客户过滤`（19 条 AC / 12 个任务项 / A0 六条已裁 / 六件套 + 6 份原型图已合 master）。
其开工时机**卡在本任务的 B-1 DDL + B-2/B-3 复合轴 + B-6 唯一索引扩展合并 master 之后**（用户裁决的时序）。
**选它的理由**：路径结构不变、**向后兼容**（不传则不过滤，维持现有行为）、前端只需在现有请求上加一个参数。

🚫 **已否决**：① 拆两段路径 `/parts/{customerNo}/{axisValue}` —— 语义最清楚但**破坏性变更**，4 个端点 URL 全变；
② 复合值编码进单段（`CUST-0001~S-80011`）—— 引入自制编码，**料号里出现分隔符就会静默解析错**。

> 🔑 **这条要解的真问题不是「URL 装不下」，而是**：同一料号在两个客户下都有数据时，
> `/parts/S-80011/overview` 会把两家的行**混在一起展示**，而用户看不出来。

**A0-3 · 客户来源 → 维护端加客户选择器**
与导入端「选客户」一致，客户号由前端显式传入。

🚫 **已否决「从料号反查」**：实测当前**1866/1866 唯一、技术可行**，但**一旦同料号跨多客户就反查不出唯一值，而那时才爆** ——
等于把一个必然会到来的失败推迟到数据变复杂之后。

### ⚠️ 一条时序依赖

`报价数据导入切ds新表` 会话有一条 **`AC-15`**：「【基础资料维护】页签读写行为**与改动前逐字一致**」。
它的 **A 侧基线已采**（`master = a81f2c40` 时点）。
⇒ **你改维护端 URL / 加客户选择器时，它的 `AC-15` 必然变红** —— 那是**预期的行为变更，不是回归**。
**请在立项时与那条会话对齐**，让它把 AC-15 的验收时点钉在你的改动之前。

---

## ⑦ 迁移纪律（本项目今天已连出四次同型事故）

🚨 **迁移一落共享库，文件必须当即推 master。**

Flyway **只在启动时校验** ⇒ **落库的人自己看不见问题**，而：
- 新检出 / 新 worktree 的后端**启动即挂**：`FlywayValidateException: Detected applied migration not resolved locally: <N>`
- 共享 8081 若启动早于该迁移，是**侥幸活着** —— 任何一次重启（谁的都算）都会挂，而**所有会话都连它**

🚫 撞到这个错**不要**用 `-Dquarkus.flyway.validate-on-migrate=false` 长期绕过 —— 那关掉的正是发现同型事故的唯一信号。
🚫 **更不要**对共享库跑 `flyway repair` —— 会打掉别人的记录，属 `CLAUDE.md` §3.2 红线。

**取号口径**：`max(目录, 共享库) + 1`，**落库那一刻实取**，不预取（多条会话在抢同一个移动靶）。

**自查命令**（建议绑在「每次收到子代理回报」这个会重复的动作上，而不是写进只读一次的派工文档）：
```bash
export PGPASSWORD=joii5231
psql -h 10.177.152.12 -U postgres -d cpq_db_0724 -tA \
  -c "SELECT version FROM flyway_schema_history WHERE success ORDER BY version::int DESC LIMIT 8;" | sort > /tmp/db_v.txt
git ls-tree master --name-only cpq-backend/src/main/resources/db/migration/ \
  | grep -o 'V[0-9]*' | tr -d 'V' | sort -n | tail -8 | sort > /tmp/master_v.txt
comm -23 /tmp/db_v.txt /tmp/master_v.txt   # 期望：空
```

---

## ⑧ 夹具前缀（多线并发，必须错开）

危害不是脏数据，是**互删**：两边都按 `LIKE 'T260907%'` 清理，**谁先跑谁把对方的删了**，**症状是随机挂且极像业务回归**。

| 线 | 前缀 |
|---|---|
| `取数配置器补齐` 后端 | `T260907B-` |
| `取数配置器补齐` 测试 | `T260907Q-` |
| `报价导入切ds新表` 测试 | `T260907T-` |
| **你（新会话）** | **请另取，建议 `T260907M-`（Maintenance）** |

🚫 清理一律用主键或完整名精确删。

---

## ⑨ 相关文档

| 文档 | 内容 |
|---|---|
| `dev-docs/task-260907-报价侧加客户维度/需求文档.md` | 本任务全文（7 条 AC + A0 三条裁决 + 影响面） |
| `dev-docs/task-260907-取数配置器补齐/` | 上游任务（F-3 树契约已落地，B-7 递归换表未做） |
| `docs/反模式.md` `AP-66` | `UNION ALL` 产物的 `ORDER BY` 只能用输出列名（本任务线新增） |
