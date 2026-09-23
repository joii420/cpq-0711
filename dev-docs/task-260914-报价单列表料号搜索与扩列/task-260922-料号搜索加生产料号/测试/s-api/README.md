# task-260922 · 分片 S-API（接口层 + AC-6 集成）测试用例

> 负责 AC：**AC-2 / 3 / 4 / 5 / 6 / 7 / 8**（任务.md §⑦ 分片计划）
> 用例全部从 `任务.md` §④ AC 原文 + §⑥ 接口契约 + §⑦ 基准 SQL 派生，**未读实现代码**。
> 状态：**已执行**（2026-09-22 18:06~18:11，主线审过解锁）。结果见 `../../test-report-S-API.md`。
> AC-6 测试类已移入 `cpq-backend/src/test/java/com/cpq/quotation/Task260922ProductionNoSearchTest.java`（包 `com.cpq.quotation`），本目录不再保留 `.draft`。

## 文件

| 文件 | 作用 | 写入面 |
|---|---|---|
| `cpq-backend/src/test/java/com/cpq/quotation/Task260922ProductionNoSearchTest.java`（原 `.draft`） | AC-6 集成测试（`@QuarkusTest` + `@TestTransaction`） | `cpq_db_test`，**单事务造数、结束回滚**，不写 DELETE |
| `run.sh` | AC-2 / 3 / 4 / 5 / 7 / 8 的接口层用例 | **纯只读**：GET + SELECT，唯一非 GET 是 `POST /auth/login`（admin） |
| `lib.sh` | 公共库：基准 SQL 生成、psql 包装、登录、GET、结果登记、证据落盘 | — |

## AC → 用例 对照

| AC | 用例 | 断言（期望值现场算，不硬编码） | 区分力从哪来 |
|---|---|---|---|
| AC-2 | `run.sh` TC-AC2 | `partNo=300001&page=0&size=20`：`totalElements == Q1′(300001)`；同分钟 `Q1(300001) == 0`；`content` 非空、条数 = min(20, Q1′)、逐条 ∈ Q1′ 单号集合 | 旧口径 Q1=0 ⇒ 没接生产料号的实现返 0，与 Q1′ 分叉 |
| AC-3 | `run.sh` TC-AC3 | `partNo=3000`：`totalElements == Q1′(3000)`；且**严格大于**本用例内紧接着打的 `partNo=300001` 的 `totalElements`；content ∈ Q1′(3000) 集合 | 精确/前缀匹配时 `3000` 命中不会多于 `300001` |
| AC-4 | `run.sh` TC-AC4 | `partNo=3120011203`：`totalElements == Q1′`（size=20 与 size=500 两次）；`≠ S+P`；两页 content 单号**无重复**、条数对、∈ Q1′ | 守卫：两路交集 I 必须 > 0，否则去重没被触发，判「未验证」 |
| AC-5 | `run.sh` TC-AC5 | `partNo=300021&page=0&size=100`：① `== Q1′(300021)`；② 返回单号不含 `QT-20260907-0564` / `QT-20260908-0620` / `QT-20260910-0806`（且 size=100 装得下全部结果）；③ 同分钟 `Q-漏客户(300021) ≠ ①` | ③ 相等 ⇒ **样本失效，①② 一律不计 PASS**（AC 原文）；漏客户过滤的实现会得到 Q-漏客户 值，① 会命中专门的失败文案 |
| AC-6 | `Task260922ProductionNoSearchTest#ac6_productionNo_caseInsensitive_partial_and_isolatedByCustomer` | ① `t260922p-prod-xy` ② `T260922P-PROD-XY` ③ `PrOd-X` 三次都 `totalElements==1` 且 ids == [QA]、不含 QB；④ `T260922P-S1` → `totalElements==2` 且 id 集合 == {QA, QB}；每次打印 total + id 列表 + 单号 | 关键字只存在于生产料号；QA/QB 只差客户；存的是混合大小写 `PrOd-Xy` |
| AC-6（对照） | `…#ac6_control_whenCustomerBAlsoBound_qbBecomesVisible` | **非 AC 断言**：给 B 也绑同一条后，① 必须返回 {QA, QB} | 证明主用例里 QB 消失确实是客户维度挡的（§4.4 阳性对照） |
| AC-7 | `run.sh` TC-AC7 | `s0004` / `a002` / `s000` / `zzz9999` 各一次：HTTP 200；`totalElements == Q1 == Q1′`（同分钟）；非零的三个 content ∈ Q1 集合；`zzz9999` → 200 且 0 | 回归样本：某关键字现已出现在生产料号里（Q1≠Q1′）⇒ 判「样本失效」 |
| AC-8 | `run.sh` TC-AC8 | 预热三种请求各 2 次 → ① `partNo=3000&size=20` ② `partNo=3000&size=1` ③ `size=20`：① 与 ② SQL 条数相等；① − ③ ≤ 1；引用 `ds_quote_material` 的语句 ①=② 且无同文本重复；三窗口 SQL 原文落盘 | 守卫：任一窗口 0 条 / 没查 quotation / ① 里没有 `ds_quote_material` / ①② 行数没拉开 ⇒ 判「未验证」 |

## 🚫 刻意规避的假绿

1. **硬编码期望值** —— 每个数字都是「基准 SQL 首采 → 打接口 → 复采」；首采 ≠ 复采 ⇒ 判 **🌀不稳定**，不判 PASS/FAIL。
2. **断言从未执行** —— 基准值为 0 时一律「未验证」（`zzz9999` 除外，它的 0 是 AC 明写的期望，且另外断言了 HTTP 200）；逐条核对前先断言 content 非空、条数符合预期（`testing.md §5.7⑥`：少返回不是「验过了」）。
3. **样本失效当通过** —— AC-2 要求 Q1=0、AC-3 要求 Q1′(3000) > Q1′(300001)、AC-4 要求交集 > 0、AC-5 要求 Q-漏客户 ≠ Q1′、AC-7 要求 Q1 == Q1′：前提不成立时判「未验证 · 样本失效」，不计 PASS。
4. **AC-8 的「日志外查询」** —— 若生产料号那一路没走进 Hibernate 日志，「至多多 1 条」会少数它而假绿；脚本要求 ① 窗口必须看得到 `ds_quote_material`，否则「未验证」。
5. **脚本自身** —— `pipefail`；psql 带 `ON_ERROR_STOP=1` 且校验返回值是数字；`grep` 一律 `/usr/bin/grep -a`；关键字白名单 `[a-z0-9-]`（拒绝 `%` `_` `'`，不做转义）；curl 一律 `--noproxy '*'`。
6. **AC-6 空跑** —— 造数前四个关键字各查一次必须全 0（防别的数据混入计数）；造数后按前缀回读四张表行数；④ 阳性对照 + B 也绑定的对照组。

## 跑法（解锁后，按 ⓐ → ⓑ 顺序；同一 worktree 同一时刻只许一个 maven 进程）

```bash
W=/home/joii/project/cpq/.claude/worktrees/task-260922-partno-production-search
T="$W/dev-docs/task-260914-报价单列表料号搜索与扩列/task-260922-料号搜索加生产料号"

# 0. 确认没有别的 maven 在写这棵树的 target/（testing.md §4.2.5）
pgrep -af "[m]vnw|[p]lexus-classworlds" | /usr/bin/grep -a "$W" || echo "无"
/usr/bin/grep -n DB_NAME "$W/cpq-backend/src/main/resources/application-test.properties"   # 确认 test profile 默认 cpq_db_test
```

### ⓐ AC-6（`cpq_db_test`，事务回滚）

```bash
# 测试类已在 cpq-backend/src/test/java/com/cpq/quotation/ 下
E="$T/证据/s-api/AC-6-$(date +%Y%m%d-%H%M%S)"; mkdir -p "$E"
cd "$W/cpq-backend" && ./mvnw test -Dtest=Task260922ProductionNoSearchTest 2>&1 | tee "$E/mvn-test.txt"
echo "mvn exit=${PIPESTATUS[0]}" | tee -a "$E/mvn-test.txt"          # 不许被 tee 吞掉退出码
ls -l --time-style=full-iso target/surefire-reports/*Task260922* && cp target/surefire-reports/*Task260922* "$E/"
/usr/bin/grep -a '\[AC-6' "$E/mvn-test.txt"                          # 四次 + 对照组的 total/ids 打印
# 零残留复核（只读）：
PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_test -Atc \
 "SELECT (SELECT count(*) FROM customer WHERE code LIKE 'T260922P%'),(SELECT count(*) FROM quotation WHERE quotation_number LIKE 'T260922P%'),(SELECT count(*) FROM quotation_line_item WHERE product_part_no_snapshot LIKE 'T260922P%'),(SELECT count(*) FROM ds_quote_material WHERE customer_no LIKE 'T260922P%')" | tee "$E/零残留复核.txt"   # 期望 0|0|0|0
```
判定：surefire 报告 `tests=2 failures=0 errors=0 skipped=0`，**点名的两个方法都在报告里**（缺一个即判失败，不看总数），报告 mtime 晚于本次开跑时刻。

### ⓑ AC-2/3/4/5/7/8（临时后端 8322，默认 profile = `cpq_db_0724`，纯只读）

```bash
LOG=/tmp/claude-1000/cpq-8322-task260922.log     # 或任一临时路径；SQL 原文会被截取到证据目录
cd "$W/cpq-backend" && nohup ./mvnw quarkus:dev -Ddebug=false -Dquarkus.http.port=8322 \
   -Dquarkus.hibernate-orm.log.sql=true -Dquarkus.hibernate-orm.log.format-sql=false \
   -Dquarkus.scheduler.enabled=false -Dcpq.price-adjust.startup-recovery.enabled=false \
   > "$LOG" 2>&1 &
# 🚫 不加 -Dquarkus.profile=test（那会连 cpq_db_test，与基准 SQL 不同库）
# 等到业务端点返 401：
curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:8322/api/cpq/quotations
cd "$T/测试/s-api" && S_API_BACKEND=http://localhost:8322 S_API_SQLLOG="$LOG" bash run.sh
```
脚本开头会**验明正身**：接口全量 == `cpq_db_0724.count(*)` 且 ≠ `cpq_db_test.count(*)`，对不上直接中止；并打印日志里的 JDBC URL 与监听端口作辅证。
跑完停掉 8322（按 PID 停，🚫 不许 `pkill -f` 模式匹配），S-UI 才能起它的后端。

### 证据落在哪

| 内容 | 位置 |
|---|---|
| `run.sh` 每轮一个目录（**不覆盖旧轮**） | `任务目录/证据/s-api/run-<时间戳>-<端口>/`：`console.txt`（全量控制台）、`TC-*.response.txt`（接口原始响应）、`TC-*.sql.txt`（基准 SQL 原文 + 首采/复采值）、`*-set.txt` / `*-numbers.txt`（集合与返回单号）、`AC-8-{1,2,3}-*.sql.txt`（三次请求各自的 SQL 原文 + 条数统计） |
| 复跑 / 证伪要换目录 | `S_API_EVIDENCE_DIR=<任务目录>/证据/s-api/证伪-<栈>-<时间戳> bash run.sh` |
| AC-6 | `任务目录/证据/s-api/AC-6-<时间戳>/`：`mvn-test.txt`、surefire 报告、`零残留复核.txt` |

## 🧪 证伪（本轮未做，执行轮要做）

- **`run.sh` 对旧代码的 not-X 对照**：改动前的后端不查生产料号 ⇒ 预期 **TC-AC2 / TC-AC3 / TC-AC5-① FAIL**（total 为 0 或 Q1 值）；
  TC-AC4、TC-AC7 在旧代码上**也会 PASS —— 这是设计如此**（AC-4 防的是「两路重复计数」、AC-7 防的是「另两路被清零」，都只对错误的新实现有区分力，对「没实现」没有）。
  ⚠️ 现成的旧代码实例只有 8081（主工作区），而派工写明「执行时不要 curl 8081」⇒ **是否用 8081 做这一步须主线批准**；
  若批准：`S_API_BACKEND=http://localhost:8081 S_API_EVIDENCE_DIR=<任务目录>/证据/s-api/证伪-8081-<时间戳> bash run.sh TC-AC2 TC-AC3 TC-AC4 TC-AC5 TC-AC7`（AC-8 在 8081 没有 SQL 日志，会判未验证）。
- **AC-6**：worktree 里实现落地后无法在本树跑「旧代码」（回滚实现不属于测试片的权限）⇒ 用两道替代：
  造数前 0 命中守卫 + ④ 阳性对照（证明「只有 QA」不是空跑）、B 也绑定的对照组（证明「没有 QB」是客户维度挡的）。
- **AC-8 守卫自检**：首次执行时先确认三个窗口都数得出非 0 条且含 `from quotation`；若 0 条，按上一期教训先查日志前缀（Quarkus 为 `[Hibernate] `）。

## ⚠️ 环境事实

- 列表响应：`{code, message, data:{content, page, size, totalElements, totalPages}}`，`page` 从 **0** 起（上一期实测）；`size=500` 一次返全量（上一期实测，TC-AC4 依赖这一点）。
- `curl` 打本机必须 `--noproxy '*'`；后端健康看业务端点返 **401**，`/q/health` 404 是正常的。
- Quarkus SQL 日志前缀是 `[Hibernate] `（不是裸 Hibernate 的 `Hibernate: `），脚本两种都认。
- `ds_quote_material_id_seq` 在 AC-6 每跑一次 +1 且**不随回滚撤销**（PG 序列非事务性）—— 不是数据行残留。
