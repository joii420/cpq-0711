# 后端任务分解 · task-260909 已有产品抽屉数据源收敛

> 执行方：`cpq-backend`。契约以 `api.md` 为准，AC 原文在 `需求文档.md §③`（本文件**只标编号不复制原文**）。
> 🚫 遇 `CLAUDE.md §3.2` 不可逆操作红线（DROP / TRUNCATE / 无 WHERE 的 DELETE / 清库）**立即停下报主线**，你没有批准权。

---

## B-1 · `ExistingProductService.list()` 三支 UNION 收敛为单表

**服务的 AC**：AC-1, AC-2, AC-4b, AC-9, AC-10, AC-13, AC-14, AC-15

**文件**：`cpq-backend/src/main/java/com/cpq/existingproduct/service/ExistingProductService.java`

**改法**：

1. **删除** mcm 分支（`mcmSelect` 及其 `where` 构造）、spn 分支（`spnSelect` / `spnWhere`）、`unionSql` 拼接
2. **保留并改造** dqcp 分支为唯一数据源，**去掉 `AND dqcp.source <> 'IMPORT'` 这个谓词**（这是本次 bug 的直接病灶）
3. **删除**两条 `NOT EXISTS` 挡板（让位给 spn / dqcp 的那两条）与 `sel_part_signature` 的 `OR EXISTS` 兜底谓词
4. **删除** `model_config` 的三处 LEFT JOIN 与 `has3d` / `thumbnail_url` 选列（配合 B-3）
5. `source` 改为按来源列判定：`CASE WHEN d.source = 'IMPORT' THEN 'EXISTING' ELSE 'CONFIGURED' END`
6. 四个过滤谓词改为只作用于单表 + JOIN 出来的视图列，见 `api.md §1.1`

**必须保留（改错会引发已治理过的旧病）**：

| 保留项 | 理由 |
|---|---|
| `DISTINCT ON (material_no)` + `ORDER BY material_no, created_at, customer_product_no` | AC-13 去重；代表编号取最早 |
| `array_agg` 聚合子查询（`aggSql`） | AC-13 的「等 N 个」。🚫 **不许改成逐行查** —— 那是 backtask B-19 治过的 N+1 |
| `sel_part_signature` 取 `config_product_type` 的**标量子查询** | AC-6 的「选配·单件/组合」标签。注意：删的是 `OR EXISTS` **兜底谓词**，不是这个取值子查询 |
| 服务端分页 `setFirstResult` / `setMaxResults` | AC-10, AC-15 |
| `resolveCustomerNo()` 及其 404 / 400 分支 | 契约不变 |
| `toStringList()` 的 `SQLException` 降级返空 List | 🚫 不返 null，前端直接 `.map()` |

**聚合子查询同步收窄**：`aggSql` 现在 UNION 了 spn + dqcp + mcm 三处编号，改为**只查 `ds_quote_customer_part`**，与主查询同源。

### 🚨 `v_compat_material_master`：JOIN 原样保留，🚫 不许直连 `ds_quote_material`，🚫 也不许改视图定义


#### 🚫 第二个夹带姿势：不许「修」视图定义

你读 `v_compat_material_master` 的定义时会看到新表侧有一句：

```sql
DISTINCT ON (material_no) ... ORDER BY material_no, customer_no
```

看起来像 bug（同一料号跨客户被"随便"挑了一个）。**不要动它。**
视图定义归并发会话「老表退役」的退役任务，不归本任务。改它同样是**夹带一个没人验收的数据变更** ——
本次 18 条 AC 里没有任何一条在验跨客户折叠行为，改了没人兜底。

发现它确实有问题 → **报告我**，我转给那个任务，🚫 不要自己修。

```sql
LEFT JOIN v_compat_material_master v ON v.material_no = d.material_no   -- ← 保持不动
```

看到「兼容视图」四个字会很想顺手削掉它 —— **不行，这是 D-3 明确裁决过的**。
该视图不是普通 `UNION ALL`，是**老表遮蔽新表**（第二支带 `NOT EXISTS`）。实测直连新表的后果：
**42 个料号**走的是老表值，其中 **3 个单元格显示值会变**，另有 **6 个料号整个消失**
（`0526-2609000001/2/3`、`0028-2609000012`、`3110520422`、`3120011203`）。
这些变化落在品名/规格列上，**全部在本次 AC 覆盖范围之外** —— 改了就是夹带一个没人验收的数据变更。

⚠️ 并发会话「**老表退役**」正在做 `v_compat_*` 的退役审计。**那是它的范围，不是你的。**
你这一刀删掉 mcm 分支，反而是帮它减少一个引用点。

⚠️ **N+1 硬指标**：单次请求的 SQL 条数必须是常数（预期 2 条：一条 COUNT、一条 LIMIT/OFFSET），与返回行数无关。🚫 循环体里出现查询即违规。

---

## B-2 · `productName` / `customerMaterialName` 语义拆分

**服务的 AC**：AC-5, AC-5b

**文件**：同 B-1

**改法**：两个字段不再同取一列（改动前 `dto.productName` 和 `dto.customerMaterialName` 都取 `r[2]`）：

```
customerMaterialName ← d.customer_part_name
productName          ← COALESCE(NULLIF(v.material_name,''), d.material_no)
```

🚫 **`productName` 的兜底链里不许再出现 `customer_part_name`** —— 那会让两列在客户名有值时又变回相同，AC-5 直接失败。

---

## B-3 · 删除 DTO 的 3D 字段

**服务的 AC**：AC-7

**文件**：`cpq-backend/src/main/java/com/cpq/existingproduct/dto/ExistingProductDTO.java`

**改法**：删除 `public Boolean has3d;` 与 `public String thumbnailUrl;` 两个字段，同步删掉类 javadoc 里描述 `model_config` 数据来源的那一段。

**新增字段**：`public String customerDrawingNo;`（AC-4）

⚠️ 这是**跨端契约变更**。前端 `cpq-frontend/src/types/existingProduct.ts` 由 `F-3` 同步，两边必须一致——但你**不要去改前端文件**，那是 `cpq-frontend` 的活。

### 🚨 B-3b · 删字段后必须清理**后端侧**引用面（2026-09-09 补，原文漏写致硬阻塞）

删 DTO 字段不是删两行就完了 —— **谁删的字段，谁负责它在后端的全部引用点**。

实测遗漏后果：`ExistingProductServiceTest#has3dAndThumbnailFromModelConfigIsCurrent`
仍在断言这两个字段 ⇒ `mvnw test-compile` **4 处编译错误** ⇒ 该 worktree 里**任何测试都跑不起来**，
测试工程师直接撞墙。这不是下游的问题，是本次改动的收尾没做完。

必做：
1. 删已作废的测试方法 + 其专用辅助方法（先确认没有别的测试在用）
2. 清理相关 javadoc 里对该字段的描述
3. `./mvnw -q -o test-compile` → **0 错误**
4. 全量扫一遍残留引用：
   ```bash
   /usr/bin/grep -rn "has3d\|thumbnailUrl" cpq-backend/src/
   ```
   🚫 **必须用 `/usr/bin/grep -a`** —— 本环境 `grep` 是 ugrep，中文注释多的源文件会被**静默判为二进制返空**，
   得到「零命中」的假象（`cpq-grep-ugrep-binary-pitfall`）。

🚫 **只跑 `test-compile`，不要跑 `mvnw test`**（会打共享库）。

---

## B-4 · 新增索引迁移

**服务的 AC**：AC-15, AC-16

**改法**：新建 Flyway 迁移，内容：

```sql
CREATE INDEX IF NOT EXISTS idx_ds_quote_customer_part_customer_no
    ON ds_quote_customer_part (customer_no);
```

**迁移号纪律**（`cpq-shared-flyway-history-churn` 教训）：

1. 🚨 **建文件前先跑一次** `ls cpq-backend/src/main/resources/db/migration/ | sort -V | tail -5` 取当前最大号，**+1** 使用
2. 🚨 迁移号是**移动靶** —— 多会话并发时可能被抢占。发现撞号**改自己的号**，🚫 **绝不改已应用到共享库的他人迁移**
3. 🚫 **不要手工 `psql -f`** 执行迁移，靠 8081 启动时的 `migrate-at-start`

**为什么加**：`EXPLAIN` 实测该表按 `customer_no` 过滤走全表 `Seq Scan`（当前 2678 行，0.68ms 尚可）。本次改动后单客户可返回 2662 行，且未来每个客户都是几千行量级。

---

## B-5 · 后端自检

**服务的 AC**：全部后端 AC 的前置

按 `docs/rules/backend.md` 跑完整自检，**「完成」宣告必须带「已自检」一行**（`CLAUDE.md §6.1`），至少含：

- [ ] `./mvnw -q compile` 通过（在 **worktree 的 `cpq-backend/`** 下跑，🚫 不要 cd 主仓）
- [ ] 迁移在启动时 `success=t`（查 `flyway_schema_history`）
- [ ] `curl -s --noproxy '*' -o /dev/null -w '%{http_code}' http://localhost:<端口>/api/cpq/components` → **401**（应用在跑 + 鉴权正常；🚫 `/q/health` 返 404，它不是健康探针）
- [ ] 接口实测：正泰某报价单 `existing-products?page=0&size=20` 的 `totalElements`
      **等于**当场跑 `SELECT count(DISTINCT material_no) FROM ds_quote_customer_part WHERE customer_no='CUST-0004'` 的结果
      （2026-09-09 该值为 2662，但**共享库会漂移，别写死这个数**，以当场实测为准）
- [ ] 响应 JSON **不含** `has3d` / `thumbnailUrl`，**含** `customerDrawingNo`
- [ ] SQL 条数实测为常数（开 `quarkus.hibernate-orm.log.sql=true` 数一次请求打了几条）

⚠️ **`mvnw test` 直接写共享开发库 `cpq_db_0724`**（`CLAUDE.md` 已实证更正）。🚫 **不许跑任何会清库/重置全局状态的测试** —— 那是 §3.2「环境销毁」，会打掉正在用的开发数据。

⚠️ 全量 `mvnw test` **永远不可能全绿**（`mat_*` 表在本库从未创建，25 个测试夹具文件 235 处引用恒红），🚫 **别拿全量绿当验收门槛**，只跑与本任务相关的测试类。

---

## 反向覆盖检查

| 任务 | 指回的 AC |
|---|---|
| B-1 | AC-1, AC-2, AC-4b, AC-9, AC-10, AC-13, AC-14, AC-15 |
| B-2 | AC-5, AC-5b |
| B-3 | AC-7（删字段）, AC-4（加字段） |
| B-4 | AC-15（性能）, AC-16 |
| B-5 | 全部后端 AC 的证据来源 |

✅ 每项均可指回至少一条 AC，无超范围项。
