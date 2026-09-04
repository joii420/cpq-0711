# task-260903 · 测试方案

## 0. 环境纪律（先读，否则结论不可信）

| 事实 | 后果 |
|---|---|
| `application-test.properties:24` 的库名默认值就是 **`cpq_db_0724`**（共享开发库，`f2f4cc4b` 用户裁决，`cpq_db` 已废弃） | **`mvnw test` 直接写开发库**。🚫 共享库上不许跑清库型测试 |
| Redis 已于 2026-09-03 改指 `10.177.152.12:6379`（原 `172.16.18.56:6380` 本机连上即被 reset） | 不用再带 `-Dquarkus.redis.hosts` 覆盖 |
| `com.cpq.task260902` 包名被**三条任务线共用** | 🚫 不许用 `-Dtest='com.cpq.task260902.**'` 通配符，会拖进别人的类。**一律用显式类名** |
| 选配端点类级有 `@RoleAllowed` | 测试基类**必须真实登录**。`task-260902` 的 `SelConfigAcTestBase` 已有该机制（`867f0282`），本任务复用 |

---

## 1. 阶段 B 的核心手法：改造前后快照 diff

**135 段 SQL 靠人眼比对不出来。** B 阶段唯一可信的验收手段是机械 diff：

```
改造前：对每个 component_sql_view 跑一次，把结果集落盘成基线（含列名、行序、每个单元格值）
改造后：同样跑一次，逐字节 diff
判据：任何一段 SQL 的结果有差异 → 红，且报出是哪一段、哪一行、哪一列
```

🚨 **基线必须在改造前采集**，改造后补采的基线毫无意义。

⚠️ **采基线时共享库不能有并发写入**，否则 diff 出来的差异是别人写的。采集前后各记一次 `max(created_at)`，两次相同才算这轮基线有效。

---

## 2. 用例清单

### 2.1 阶段 B

| 用例 | 对应 AC | 手法 |
|---|---|---|
| `CompatViewEquivalenceTest` | B-AC-1 | 兼容视图 vs V6 表，同条件查询结果**逐字相同**（此时新表侧为空） |
| `ComponentSqlSnapshotDiffTest` | B-AC-2 | §1 的快照 diff。**这是 B 阶段的主验收** |
| `PgViewEquivalenceTest` | B-AC-3 | 3 个 PG 视图改造前后对同一料号返回相同 |
| `CompatColumnMappingTest` | B-AC-4/5/6 | 事务内往新表插测试行 → 查兼容视图 → 断言 `characteristic` / `component_usage_type` / `is_current` 三列映射正确 → **回滚** |
| `CompatPerfGuardTest` | B-AC-7/8 | ≥100 行报价单渲染耗时与 SQL 条数，与基线比 |

### 2.2 阶段 A

| 用例 | 对应 AC | 手法 |
|---|---|---|
| `SelConfigWritesNewTablesTest` | A-AC-1/6/7 | 提交选配 → 查 `ds_quote_*` 四表，断言落行、`version_no=1`、`output_material_type` 双投影、`material_type` |
| `V6ZeroWriteGuardTest` | A-AC-2 | 🚨 **守卫**：提交前后 V6 五表行数**不变** |
| `CustomerPartMappingTest` | A-AC-3/10 | 落 `ds_quote_customer_part`；`sel_product_no` 零新增；并发同编号恰好 1×200 + 1×409 |
| `FingerprintReuseNoWriteTest` | A-AC-4/9 | 命中复用 → `ds_quote_*` 一行不写；`VersionedGroupWriter` 零调用 |
| `NoVersionBumpInSelConfigTest` | A-AC-5 | 连配三个不同产品 → `version_no` 全 1、`_history` 零新增 |
| `EndToEndRenderTest` (E2E) | A-AC-8 | **Playwright**：提交选配 → 打开报价单 → 断言卡片渲染出材质/占比/外购件 |

---

## 3. 假绿陷阱（本任务专属）

| 陷阱 | 为什么会假绿 | 怎么防 |
|---|---|---|
| 🚨 **兼容视图的 UNION 新表侧为空时，diff 必然全绿** | B 阶段采基线时新表还没有选配数据，`UNION ALL` 的新表侧是空集 —— 这时候「结果相同」只证明了 V6 侧没被改坏，**没有证明新表侧映射正确** | B-AC-4/5/6 必须**事务内造新表数据**再验，🚫 不能只靠 B-AC-2 的 diff |
| 🚨 **A-AC-2「V6 五表行数不变」可能因为压根没提交成功而通过** | 提交失败 → 什么都没写 → 行数当然不变 | 必须先断言**提交返回 200 且 `ds_quote_*` 真落了行**，再断言 V6 不变。对齐 `task-260902` 的 `assertReachedBusinessLayer` 手法 |
| 🚨 **A-AC-5「version_no 全 1」在没写入时也成立** | 同上 | 先断言行数 > 0 |
| 🚨 **性能测试受并发干扰** | 共享库上别的会话在跑测试 | 采样前后各查一次 `pg_stat_activity` 活跃连接数，波动大则本轮作废重测 |
| **INNER JOIN 吞掉外购件行** | 外购件的 `input_material_no` 不在 `material_recipe` 里 | B-AC-5 的用例必须**同时包含一个外购件行**，否则漏检 |
| **表名替换把 `material_bom` 和 `material_bom_item` 搞混** | 前者是后者的前缀 | 替换后逐条回读 `sql_template`，断言不含裸的 V6 表名 |

---

## 4. 回归

| 项 | 判据 |
|---|---|
| `task-260902` 的 33 条 | 全绿（显式类名跑，带真实鉴权，401 次数 = 0） |
| V6 报价数据导入 | 跑一次导入，结果与改造前一致 |
| 核价侧渲染 | 不受影响（本任务只动报价侧） |

---

## 5. 收尾污染核对

本任务会在共享库造测试数据，收尾必须核对并清理（DELETE 属 §3.2 红线，须单独批准）：

🚨 **前缀是 `T260902-` 不是 `T260903-`**（2026-09-03 裁决更正）：本任务复用 `task-260902` 的 `SelConfigAcTestBase`
（它已有真实登录、`assertReachedBusinessLayer`、`@AfterEach` 精确还原，重写一套只会漂移），
而它的 `PREFIX` 是 `static final "T260902-"`、`customer_no` 是 `"T2609"+uuid`，**子类无法覆盖**。

⚠️ **查 `T260903%` 会返回 0 条 —— 那是查错了前缀，不是「很干净」。** 这个陷阱本身值得留痕。

```sql
SELECT 'ds_quote_material',      count(*) FROM ds_quote_material      WHERE material_no LIKE 'T260902%'
UNION ALL SELECT 'ds_quote_material_bom',  count(*) FROM ds_quote_material_bom  WHERE material_no LIKE 'T260902%'
UNION ALL SELECT 'ds_quote_element_bom',   count(*) FROM ds_quote_element_bom   WHERE material_no LIKE 'T260902%'
UNION ALL SELECT 'ds_quote_customer_part', count(*) FROM ds_quote_customer_part WHERE customer_no LIKE 'T2609%'
UNION ALL SELECT 'customer',               count(*) FROM customer               WHERE code       LIKE 'T2609%';
```

⚠️ 已知的既有污染（**不是本任务造成的**，见 `BL-0204`）：`DemoMaterialRecipeFixture` 的 4 条 demo 材质、
`PricingMaintenanceServiceTest` 的 `TP10`/`TP20`、3 行 mcm 孤儿（`Q13CUST0617` ×2 / `C1`）。按 `created_at` 区分。
