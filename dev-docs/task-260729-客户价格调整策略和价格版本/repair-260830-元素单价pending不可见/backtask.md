# backtask · repair-260830 元素单价 pending 不可见

**只按本文件做。** AC 原文在 `问题说明.md` ⑥，本文件只标编号、不复制原文（避免双写漂移）。
**遇到 `CLAUDE.md` §3.2 不可逆操作红线 → 停下报告主线，你没有批准权。**

---

## 分工总览

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| B-1 | AC-1, AC-2, AC-3, AC-7, AC-9, AC-11, AC-12, AC-13 | 迁移：`f_material_element_price` 三参重载 + 两参委托 |
| B-2 | AC-1, AC-2, AC-3, AC-10 | `QuotePendingRewriter` 新增函数调用改写规则 |
| B-3 | AC-10 | 改写器单元测试（4 条） |
| B-4 | AC-4, AC-5, AC-6, AC-7, AC-8 | 零回归验证脚本（可复跑的 A/B 比对） |

---

## B-1 迁移：三参重载 + 两参委托

**服务的 AC**：AC-1, AC-2, AC-3, AC-7, AC-9, AC-11, AC-12, AC-13

**文件**：`cpq-backend/src/main/resources/db/migration/V397__repair260830_material_element_price_pending_visibility.sql`

> ⚠️ **版本号 V397 是立项时（2026-08-30）实取的**：主仓最大 V396、dev 库 flyway 最大 396、
> 全部 worktree 最大 V396。**共享 Flyway 历史是移动靶**——动手前**必须重新核对一次**，
> 撞号就顺延，并回来告诉主线你改用了哪个号。

**做什么**：

① 新增三参重载 `f_material_element_price(p_customer_no text, p_base_date date, p_pending_quotation_id uuid)`。
   函数体**逐字照抄**现两参版（`V369` 定义，可用 `pg_get_functiondef` 取当前生产定义为准），
   **只改 `candidate_materials` 的两处过滤**：

```sql
-- 改前
WHERE customer_no IN (p_customer_no, '_GLOBAL_') AND is_current = true
-- 改后
WHERE customer_no IN (p_customer_no, '_GLOBAL_')
  AND (is_current = true OR pending_quotation_id = p_pending_quotation_id)
```

   两处：`material_bom_item` 与 `element_bom_item` 各一处。**其余 CTE（`pointers`/`versioned`/`realtime`）一字不改。**

② 用 `CREATE OR REPLACE` 把两参版改为委托：

```sql
CREATE OR REPLACE FUNCTION f_material_element_price(p_customer_no text, p_base_date date)
RETURNS TABLE(material_no varchar, element_code varchar, unit_price numeric, currency varchar, price_unit varchar)
LANGUAGE sql STABLE AS $$
  SELECT * FROM f_material_element_price(p_customer_no, p_base_date, NULL::uuid);
$$;
```

**硬约束**：

- 🚫 **禁止 `DROP FUNCTION`**。用重载，签名不变的那个用 `CREATE OR REPLACE`。
  已实测重载无歧义（参数个数不同）：两参调用解析到两参版、三参调用解析到三参版。
  这是本方案**完全避开 §3.2 契约销毁红线**的关键，不要"顺手清理一下"。
- 🚫 **禁止改 `f_customer_element_price`**（8 个组件 + `PriceReconciler` +
  `PriceAdjustVersionGenerationService` 在用，且它不读 BOM 表、与本 BUG 无关）。
- 🚫 **禁止改任何组件的 `component_sql_view.sql_template`**（15 个是客户生产配置）。
- ⚠️ `RETURNS TABLE` 的列名/类型必须与现版本**逐字一致**，否则调用方列序错位且不报错。
- ⚠️ 三参版内部 `realtime` 分支仍 `CROSS JOIN f_customer_element_price(p_customer_no, p_base_date)` —— **不要**给它也加参数。

**B-1 额外承担的三条 AC（实现即保证，不是测试专属）**：

| AC | B-1 的实现责任 |
|---|---|
| AC-9（转正后仍成立） | `is_current = true OR …` 的**前半边**就是这条 AC 的实现。🚫 删掉前半边 = AC-9 直接失败（已实测：核价侧候选 16→0） |
| AC-12（未建档元素仍为空） | 候选集扩大后，未建档元素**仍应取不到价**且**不得报错**。`realtime` 分支 `CROSS JOIN f_customer_element_price` 天然只产出有价元素，无需额外处理 —— 但要**验证**，不能假设 |
| AC-13（性能不劣化 <20%） | 候选集从 16 → 1845 料号（正泰），函数返回 80 → 9305 行。已预估 22.9ms → 37.5ms。若实测劣化超阈值，**回流主线**，不要自行加缓存或改索引（那是范围外） |

**为什么不需要写 `p_pq IS NULL` 分支**：SQL 三值逻辑下 `pending_quotation_id = NULL` 恒为 NULL，
在 `WHERE` 里等价于 false，函数自动退化为纯 `is_current`。已实测。

**自检**：
- 迁移在 **worktree 里**验证（共享 dev server 看不到 worktree 的迁移，见 `cpq-worktree-flyway-migration-verify`）
- `SELECT version, success FROM flyway_schema_history WHERE version='397';` → `t`
- 🚫 **不要手工 `psql -f` 执行迁移**，让 Flyway `migrate-at-start` 跑

---

## B-2 `QuotePendingRewriter` 新增函数调用改写规则

**服务的 AC**：AC-1, AC-2, AC-3, AC-10
**文件**：`cpq-backend/src/main/java/com/cpq/datasource/sqlview/QuotePendingRewriter.java`

**做什么**：在既有「白名单表 token 替换」之外，新增一条**函数调用补参**规则：
把 SQL 中的 `f_material_element_price(<argA>, <argB>)` 改写为 `f_material_element_price(<argA>, <argB>, :pq)`。

**硬约束**：

| # | 约束 | 理由 |
|---|---|---|
| 1 | **复用既有注释屏蔽机制**（`masked`，`:146` 一带，`task-0725 T1` 引入）—— 注释里写的函数名**不得被改写** | `mc_view` 的注释里就出现了 `f_material_element_price` 和 `f_customer_element_price` 的文本 |
| 2 | **幂等**：已是三参的调用**跳过**，不重复补参 | 防止重入 |
| 3 | **只改 `f_material_element_price`**，不碰 `f_customer_element_price` | 后者不读 BOM 表 |
| 4 | 参数里可能含**嵌套括号**与命名占位符（如 `:customerCode`/`:priceBaseDate`），括号匹配要**配平计数**，不能用贪婪正则一把梭 | 现网调用形态是 `f_material_element_price(:customerCode, :priceBaseDate)`，但不能假设永远如此 |
| 5 | 改写失败沿用既有**安全降级**语义（返回原模板 + `LOG.warnf`），不抛异常 | 与 `SqlViewExecutor:573` 的 catch 语义一致 |
| 6 | **不要动** `applyPendingRewrite` / `injectPendingParam` 的触发条件（`SqlViewExecutor:569`/`:583`） | 核价侧与冻结态的零回归**完全依赖**这两处现状：不改写 → 不补参 → 走两参版 → 行为逐字不变 |

**注意**：`:pq` 的绑定由 `SqlViewExecutor.injectPendingParam` 负责，**已经存在**，不需要新增绑定逻辑；
但要确认补参后的 SQL 里 `:pq` 能被既有占位符解析逻辑（`:618` 一带）正确处理。

---

## B-3 改写器单元测试

**服务的 AC**：AC-10
**文件**：`cpq-backend/src/test/java/com/cpq/datasource/sqlview/QuotePendingRewriterTest.java`（追加）

4 条用例，逐条对应 AC-10 的四个分项：

| 用例 | 断言 |
|---|---|
| `fnCall_twoArg_rewrittenToThreeArg` | 两参调用被补为三参，第三参为 `:pq` |
| `fnCall_insideComment_notRewritten` | **注释里**的 `f_material_element_price(...)` 文本原样保留 |
| `fnCall_alreadyThreeArg_idempotent` | 已三参的调用不被二次改写 |
| `fnCall_customerElementPrice_untouched` | `f_customer_element_price` 不受影响 |

> ⚠️ 现有 11 条用例**全部是表 token 改写**（这正是本 BUG 漏网的原因，见 `问题说明.md` §4.7）。
> 新增用例必须真正针对**函数调用**，不要写成又一条表改写用例。

**测试库注意**：`mvnw test` 走 `test` profile → `10.177.152.12:5432/cpq_db`，**与 dev 库不同**。
🚫 共享库上不许跑会清库的测试（§3.2）。

---

## B-4 零回归验证脚本

**服务的 AC**：AC-4, AC-5, AC-6, AC-7, AC-8
**产出**：`验证脚本.sql` + 执行结果，放本任务目录

要求**可复跑、A/B 可比**（改动前跑一次存基线，改动后跑一次比对）：

| 脚本项 | 断言 |
|---|---|
| S-1 | 核价侧口径（`_GLOBAL_`）候选料号 = 16（AC-4） |
| S-2 | `CUST-0001` / `CUST-0002` 取价结果集 md5 前后一致（AC-5） |
| S-3 | 两参版调用结果 = 改动前两参版结果（80 行 / 16 料号，逐字）（AC-7） |
| S-4 | 三参传本单 `pq` → 不含另一张单（`1288120e-…`）独有的 pending 料号（AC-8） |
| S-5 | 三参传 `NULL` → 结果与两参版逐字相同（AC-7） |

🚫 **全部只读**。不许 UPDATE / DELETE / 转正操作（AC-9 的转正验证在**测试库**做，见 test.md）。

---

## 交付要求

- **必须在 worktree 的 `cpq-backend/` 里跑 `./mvnw test`**（`mvnw` 不在仓库根；在主仓跑会测错树报假绿）
- 交付时给出「已自检」一行（AC-14）：迁移 `success=t` / 相关测试全绿 / 后端 `/api/cpq/components` → 401
- 测试失败要做 **A/B 归因**：与干净基线的失败集逐字比对，区分 pre-existing 与本次引入
- ⚠️ 提交只用 `git commit -- <本次改的文件>`，**不要 `git add -A`**（并发会话会被夹带）
