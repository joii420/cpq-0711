# 交接 · `unit_price` / `capacity` 与选配流程的关系

> **给谁**：接手「V6 单价类表 → `ds_quote_*` 语义映射」的新会话
> **来源**：`task-260909-V6老表引用面审计`（已合 master `82be1dd9`）+ 2026-09-09 本会话追加实测
> 📌 **全部数字为 2026-09-09 实测**，每条可原样重跑。共享库并发写入频繁，**执行前请重采**。

---

## ① 一句话结论

> **选配下单的「工序单价」被拆在两张 V6 老表里** —— 自制工序在 `unit_price`、组装工序在 `capacity`。
> 两张表的**活读点是同一个类**：`ConfigureProductService`。
> 新体系已有对应表（`ds_quote_self_process_fee` / `ds_quote_assembly_fee`），但**读侧一行没改**。

---

## ② 精确的引用面（已做 T-1 消歧，可直接采信）

### `unit_price` —— 自制工序

| 位置 | SQL | 过滤条件 | 用途 |
|---|---|---|---|
| `ConfigureProductService:287` | `SELECT DISTINCT ON (seq_no) operation_no, seq_no FROM unit_price` | `finished_material_no=:p AND cost_type='自制加工费' AND is_current` | 取该料号的工序号 + 项次 |
| `:1047` | `SELECT 1 FROM unit_price` | `finished_material_no=:p AND customer_no=:c AND cost_type='自制加工费' AND is_current` | **存在性判定**：该料号在该客户下报过价没有 |
| `:1055` / `:1057` | `SELECT operation_no, seq_no, currency, unit FROM unit_price` | 同上 + 子查询按 `version_no DESC` 取最新客户 | 取工序/项次/币种/计价单位 |

**四处全部带 `cost_type='自制加工费'`** —— 所以选配只用这一种，不碰其余 27 种。

### `capacity` —— 组装工序

| 位置 | SQL | 过滤条件 |
|---|---|---|
| `ConfigureProductService:299` | `SELECT process_no, seq_no FROM capacity` | `material_no=:p AND resource_group_no='QUOTE_ASSEMBLY' AND is_current` |

🔑 **组装的标记在 `resource_group_no='QUOTE_ASSEMBLY'`，不在 `cost_type`**（该列 22 行全为空）。

---

## ③ 新体系的对应表（列级已核，语义 7/7 对齐）

### 自制：`unit_price` → `ds_quote_self_process_fee`（10 行 / 8 个客户×料号组合）

| 语义 | 老 | 新 |
|---|---|---|
| 成品料号 | `finished_material_no` | `material_no` |
| 客户 | `customer_no` | `customer_no` |
| 工序号 | `operation_no` | `operation_no` |
| 项次 | `seq_no` | `operation_item_seq` / `item_seq` |
| 币种 | `currency` | `currency` |
| 计价单位 | `unit` | `pricing_unit` |
| 单价 | `pricing_price` | `value` |

### 组装：`capacity` → `ds_quote_assembly_fee`（14 行）

新表列：`material_no · customer_no · item_seq · assembly_operation · assembly_fee · currency · pricing_unit · defect_rate · version_no · row_fingerprint`

⚠️ **新表列名是「组装专用」的**（`assembly_operation` / `assembly_fee` / `defect_rate`），**不是老表 `process_no`/`fixed_cost` 的照搬** ⇒ 映射不是改表名，要逐列对。
📌 另有 `ds_quote_assembly_fee_annual`（8 行），老侧对应物待查。

---

## ④ 🚨 五个已知的坑（都是本会话踩过或差点踩的）

### 坑 1 · 从任一张表单独出发都会漏

**`组装加工费` 在 V6 侧写的是 `capacity` 表**（`Q14AssemblyProcessFeeHandler:103`，字段名 `fixed_cost`），**不在 `unit_price` 里**。
全库 28 种 `cost_type` 里**没有「组装加工费」**这一项 —— 不是漏了，是它压根不在那张表。
⇒ **必须两张一起查，且双向映射**（老→新、新→老各走一遍）。

### 坑 2 · T-1：`unit_price` 是同名列，裸 grep 虚高 30 倍

```
/usr/bin/grep -rlaE "\bunit_price\b" main                                → 59 个文件
/usr/bin/grep -rlaE "(FROM|JOIN|INTO|UPDATE)[[:space:]]+unit_price\b" main → 2 个文件
```
全库 `unit_price` 作**列**存在于：`production_energy` 表 + `f_customer_element_price` / `f_material_element_price` **两个函数的返回列**。

🔴 **本会话在这里判错过一次**：看到 `BuilderService:921` / `FieldTreeBuilder:440` / `ElementBindingDerivation:80` 出现 `"unit_price"`，判定「取数配置器（新体系）在用这张表」。
**错了** —— 那三处全是 `col.sourceColumn` / `col.dbColumn` / `findAliasedOutputColumn(...)`，是**列名**。
同理 `component_sql_view` 有 **12 段**提及 `unit_price`，作表名 **0** 段，全是 `cep.unit_price AS "元素单价"`（`cep` 是价格函数的固定别名）。
⇒ **取数配置器与 `unit_price` 表零关系。**

📌 `capacity` 无此问题：全库无同名列（`pg_attribute` 实测）。

### 坑 3 · T-5：表名以字符串常量拼进 SQL，正则找不到

`capacity` 的「常量通道」命中 13 个文件（`VersionedV6Writer` / `QuotePendingRewriter` / `QuoteTableAxis` / `QuoteBackfillService` …），而「表名位」只命中 1 个。
⇒ **「表名位 0 命中」不等于无人引用**。详见 `dev-docs/task-260909-V6老表引用面审计/` 的 `T-5` 四形态（含 `T-5d`：写入调用点完全无表名、表名只在旁边的记账语句里）。

### 坑 4 · `unit_price` 是 28 种语义的公共载体

```
全库 185 行 / 28 种 cost_type：
  自制加工费 55 · 来料加工费 27 · 耗材 21 · 包装 11 · 检验费 8 · 其他加工费 7
  利润 5 · 销售管理费/材料管理费/包装运输费/包装费/质保费 各 4 · 元素核价价格 3 …
按 system_type：QUOTE 48 行（自制加工费 29）· PRICING 137 行
```
⇒ 退役它**不是一次迁移，是 28 次**。选配那条链路只覆盖其中 1 种。

### 坑 5 · 还有一个视图在读

`v_composite_child_processes` **一阶依赖 `unit_price`**，过滤条件是
`cost_type = ANY (ARRAY['自制加工费','组装加工费',…])`
⇒ 改选配读侧时，**这个视图要一起改**（它同时覆盖自制与组装两种，正好是本交接的范围）。
📌 `capacity` **零视图依赖**（`pg_depend` 实测）。

---

## ⑤ 改造的真正难点（不是换表名）

`ConfigureProductService` 现在**没有「自制 vs 组装」的分支** —— 它是两段独立查询（`:287` 查 `unit_price`、`:299` 查 `capacity`），各自读各自的表。

改造后要变成读两张新表，但：
- `ds_quote_self_process_fee` 用 `operation_no` / `operation_item_seq`
- `ds_quote_assembly_fee` 用 `assembly_operation` / `item_seq`

**两张新表的「工序号」「项次」列名不同** ⇒ 不能用一个统一查询覆盖，判据要显式写出来。

---

## ⑥ 数据覆盖缺口（迁移前必须先答的问题）

| | 老表 | 新表 |
|---|---|---|
| 自制 | `unit_price` QUOTE 侧 **30 个客户×料号组合 / 48 行**（其中自制加工费 29 行） | `ds_quote_self_process_fee` **8 组合 / 10 行** |
| 组装 | `capacity` QUOTE 侧 **4 行**（全 `QUOTE_ASSEMBLY`） | `ds_quote_assembly_fee` **14 行** |

⚠️ **自制侧新表覆盖不到老表。** 两种可能，性质完全不同：
- (a) 老数据没迁 ⇒ 迁完即等价
- (b) 两边口径不同（新表只收新导入 / 老表含历史）⇒ 不是简单搬运

**这一条必须先查清**，否则改了读侧会静默丢数据。

---

## ⑦ 结论：两张表现在能不能删

**不能，但报价侧可以退干净。**

| 阶段 | `unit_price` | `capacity` |
|---|---|---|
| 本交接的改造完成后 | 报价侧读点归零（4 处 + 视图） | 报价侧读点归零（1 处） |
| **但表里还剩** | **137 行 PRICING + 19 行 QUOTE 其它 26 种 `cost_type`** | **18 行 PRICING** |

⇒ **整表删除的前置是核价侧的去向**（`ds_cost_basic_*` / `ds_cost_detail_*`），**本会话未查**。

---

## ⑧ 可直接重跑的验证命令

```bash
# 连库
PG="PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_0724"

# unit_price 的 28 种语义分布
$PG -c "SELECT system_type, COALESCE(cost_type,'(空)'), count(*) FROM unit_price GROUP BY 1,2 ORDER BY 3 DESC;"

# capacity 的组装行
$PG -c "SELECT material_no, process_no, resource_group_no, fixed_cost, currency FROM capacity WHERE system_type='QUOTE';"

# 覆盖对比
$PG -c "SELECT 'old' t, count(DISTINCT (customer_no, finished_material_no)), count(*) FROM unit_price WHERE system_type='QUOTE' AND cost_type='自制加工费'
        UNION ALL SELECT 'new', count(DISTINCT (customer_no, material_no)), count(*) FROM ds_quote_self_process_fee;"

# ⚠️ T-1 消歧（别用裸 grep 的数字）
/usr/bin/grep -rlaE "(FROM|JOIN|INTO|UPDATE)[[:space:]]+unit_price\b" --include=*.java cpq-backend/src/main
/usr/bin/grep -rlaE "(FROM|JOIN|INTO|UPDATE)[[:space:]]+capacity\b"   --include=*.java cpq-backend/src/main

# 视图依赖
$PG -c "SELECT src.relname, dep.relname FROM pg_depend d JOIN pg_rewrite r ON r.oid=d.objid
        JOIN pg_class dep ON dep.oid=r.ev_class JOIN pg_class src ON src.oid=d.refobjid
        WHERE src.relname IN ('unit_price','capacity') AND dep.relname<>src.relname;"
```

⚠️ 本环境 `grep` 是 `ugrep -I`，中文多的大文件会被**静默判为二进制返空** —— 一律用 `/usr/bin/grep -a`。
