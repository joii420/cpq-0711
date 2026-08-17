# 规则分册 · 后端

> **何时必读**：改后端源码、写 SQL、加数据迁移、动 schema DDL。
> **效力**：与 `CLAUDE.md` 同等，不是参考资料。冲突时以 `CLAUDE.md` 为准。

---

## 1. 🚫 严禁 N+1 查库（强制，无商量余地）

**定义**：查询次数随数据量线性增长 —— 「先查一批 N 条，再对每条各查一次」。

> 下列示例用 Java + JPA 写法，**规则适用于任何语言的任何 ORM / 查询层**（ActiveRecord、Sequelize、GORM、SQLAlchemy…），形态一致：**循环体里出现了查询**。

```java
// ❌ 循环里查库
for (Id id : ids)      { repo.findById(id); }
// ❌ 懒加载在循环里触发（隐式 N+1，不出现 SQL 字样，最难发现）
for (Order o : orders) { o.getItems().size(); }
// ❌ Stream 里藏循环查库
ids.stream().map(repo::findById).toList();
```

**必须改成批量**，四选一：

| 手法 | 用法 |
|---|---|
| `IN` 批量 | `repo.list("id in ?1", ids)` → 回内存建 Map 分发 |
| 元组 `IN` | 复合键用 `(col1, col2) IN ((?,?),(?,?)…)` |
| `JOIN FETCH` | 关联对象一次带出，杜绝懒加载触发 |
| 数组参数 | 如 PG `ANY(:arr)` |

**硬指标**：**单个业务操作的 SQL 条数必须是常数**，与 N 无关。推荐口径：每张表最多 2 条 SQL。

**唯一例外路径**（不是自己说了算）：必须**同时**满足三条 —— ① 代码处写明 `// N+1 例外：具体原因` ② 在 `docs/BACKLOG.md` 登记并标 P1 以上 ③ **向用户报备并获批**。三条缺一即视为违规。

**自检**（后端改动结束前必跑，写进汇报）：
1. 人工过一遍新增/改动代码里所有 `for` / `forEach` / `stream()` 循环体，确认**没有 repository 调用、没有触发懒加载的 getter**
2. 有条件就开 SQL 计数断言单测
3. 关键链路补日志证据：`[perf] 操作名 N=数据量 sql=条数` —— **N 翻倍而 sql 不变**才算过

声明格式示例：
> `N+1 自检：本次改动 3 处循环，均为纯内存运算，无查库 ✅`
> `批量化验证：数据量 5→20 条，SQL 条数恒为 4 ✅`

---

## 2. 后端改动强制自检

1. **强制服务重启**（如 `touch` 一个源文件触发热重载）→ 等重启完成
2. `curl` 具体 endpoint → 期望 **200 / 401**（**不要 500**）
   > ⚠️ 判后端健康看**业务端点返 401**（应用在跑、鉴权正常）。未装健康检查扩展时 `/health` 返 404，**它不是健康探针**
3. 有数据迁移时：查迁移历史表确认 `success = true`
4. **不要手工执行迁移 SQL**！让框架启动时自动跑。手工跑会导致重启时 checksum 对账不符报 `Migration checksum mismatch`，或碰到"对象已存在"导致启动失败
5. **N+1 自检**（见 §1）

### 2.1 cpq 实际命令

**后端改动**（含 `.java` `.sql` 修改）：
1. `touch` 一个 java 文件强制 Quarkus 重启 → 等 5-7 秒
2. `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8081/q/health` 或具体 endpoint → 期望 200/401（不要 500）
3. 如果是 Flyway 迁移：`PGPASSWORD=... psql ... -c "SELECT version, success FROM flyway_schema_history WHERE version = 'NN'"` 必须 success=t
4. 不要手动 `psql -f V_xx.sql`！让 Quarkus dev mode 自己跑 Flyway，否则会在重启时碰到"已存在"导致启动失败
5. 🚫 **N+1 自检（强制，见「开发规范 §🚫 后端严禁 N+1 查库」）**：逐个检查本次新增/改动的 `for` / `forEach` / `stream()` 循环体，确认里面**没有 repository 调用 / `SqlViewExecutor.execute` / 触发懒加载的关联 getter**；SQL 条数必须与 N 无关。声明格式示例：
   > `N+1 自检：本次改动 3 处循环，均为纯内存运算，无查库 ✅` 或 `批量化验证：料号 5→20 条，SQL 条数恒为 4 ✅`

**视图 DROP CASCADE / 重建（schema DDL）后必须重启 Quarkus**：
1. 触发场景：任何 `DROP VIEW ... CASCADE`、`DROP TABLE ... CASCADE`、视图列结构变更、视图重建（V109/V110/V111 是典型案例）
2. **必须** `touch` 一个 java 文件强制 Quarkus 重启（不是只跑 Flyway 就够）
3. 原因：`ImplicitJoinRewriter.tableColumnsCache` / `CachedSqlCompiler` / `CachedPathParser` 都是 ApplicationScoped 进程级缓存。视图被 CASCADE 临时删除的瞬间若有请求触发 `getColumns` → 缓存了**空集** → V112 之前永久残留 → 后续所有 BNF 路径求值不再注入 `hf_part_no` 谓词 → 视图返全表 N 行 → UI 出现「首值（共N项）」错乱
4. 自检：用一个含 BNF 路径的 endpoint 验证，期望返单值（不是数组）
5. 失败征兆：`v_costing_summary_full.xxx` 在 Excel 模板列里显示成 "—（共4项）" / "0.138（共3项）"
6. V112 后已修：空集不再缓存，下次自愈；但已残留旧 JVM 进程缓存仍需重启清空。**任何 schema DDL 操作完都重启一次以防万一**

**禁止手工 `psql -f V_xx.sql` 后不重启 Quarkus**：
- 即使 SQL 是幂等的，Quarkus dev 重启时 Flyway 仍按 file → history checksum 对账，可能因为本地 git 改动跟 history 记录不符而报 `Migration checksum mismatch`
- 正确做法：让 Quarkus 启动时 `migrate-at-start=true` 自动跑（已配置）。文件放进 `db/migration/` 后 touch 一个 java 文件即可触发

**当出现"看到了 500/红色 overlay"反馈时**：
- 不要立刻假设"是用户的浏览器缓存"——先 `curl` 拉一次原始响应，看错误信息是不是已经修了；
- 如果文件确实是好的而 overlay 仍存在，告诉用户 **强刷（Ctrl+Shift+R）** 清 HMR overlay；
- 如果文件本身有问题，立即修，再次跑完整自检后通报。

**任何"完成"宣告必须包含一行"已自检"声明**，例如：
> "TS 0 错误 ✅；CostingPartDataPage.tsx → Vite 200 ✅；后端 /api/cpq/.. → 401（auth 正常）✅；V77 success=t ✅"

没有这行声明的"完成"=未完成。

⚠️ **在 worktree 里改动时**：共享 dev server 服务的是主工作区代码，`curl` 它得到的是主仓结果 = 假绿。替代手段见 `git-worktree.md`。
⚠️ **worktree 里新增的迁移文件，主仓 dev server 不会执行** —— 不要假设它已经跑过。

---

## 3. schema DDL 后必须重启服务

1. **触发场景**：任何 `DROP VIEW/TABLE ... CASCADE`、视图列结构变更、视图重建
2. **必须**强制重启（**不是只跑完迁移就够**）
3. **原因**：进程级缓存（表结构缓存 / 编译后 SQL 缓存 / 解析器缓存）会在对象被 CASCADE 临时删除的瞬间**缓存空集**并永久残留，导致后续查询行为错乱
   典型症状：**本该返单值的地方返全表**（UI 上显示成「首值（共 N 项）」）
4. **自检**：用一个依赖该视图的 endpoint 验证，期望返单值而非数组

🚨 **注意**：本节讲的是 DDL 的**次要后果**（缓存错乱）。它的**主要后果是删数据** —— 执行任何 `DROP ... CASCADE` 之前，必须先走 `CLAUDE.md` §3.2 不可逆操作红线的三步前置。**只记住"要重启"而忘了"要先批准"，是本节最危险的读法。**

---

## 4. 迁移文件纪律

- **schema 变更一律新建迁移脚本，不改历史脚本**
- 🚫 **已应用到共享库的迁移文件，禁止改名、改版本号、删除** —— 迁移工具按 checksum 对账，改了会让**所有人的服务启动失败**（属 §3.2 红线「契约销毁」）
- ⚠️ **迁移版本号在多会话下是移动靶** —— 建号前先看最新已用版本，不要按记忆里的号往下顺

---

## 5. 本册自检清单（后端改动收工前逐条勾）

- [ ] 服务已强制重启，且启动无错误日志
- [ ] 改动涉及的 endpoint 返 200/401，**没有 500**
- [ ] 有迁移的话，迁移历史表 `success = true`
- [ ] **N+1 自检声明已写**（循环体逐个检查过）
- [ ] 有 DDL 的话：① 已走过红线三步前置 ② 已强制重启 ③ 用依赖该对象的端点验证过
- [ ] 没有手工执行过迁移 SQL
- [ ] 「完成」宣告里带了 `CLAUDE.md` §6.1 要求的自检声明行
