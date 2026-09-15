# task-260914 · 分片 S-API（后端接口层）测试用例

> 负责 AC：**2 / 3 / 4 / 6 / 7 / 13 / 14 / 15 / 18 / 19 / 21**
> 🚨 **纯只读片**：全部是 `GET` + `SELECT`。不建单、不改数据、不删数据、不 DDL。
> 唯一的非 GET 请求是 `POST /api/cpq/auth/login`（取会话，接口测试绕不开）。
> 🚫 不读、不改 S-UI 片的数据；🚫 无任何全局计数硬编码。

## 文件

| 文件 | 作用 |
|---|---|
| `lib.sh` | 公共库：登录、GET、psql 单值查询、断言器、结果汇总 |
| `run.sh` | 11 条用例本体 |

## 跑法

```bash
# 第二阶段（后端实现完成后）在 worktree 内起 8195，默认 profile（= cpq_db_0724，与基准 SQL 同库）
cd /home/joii/project/cpq/.claude/worktrees/task-260914-quote-list/cpq-backend
./mvnw quarkus:dev -Dquarkus.http.port=8195 \
    -Dquarkus.hibernate-orm.log.sql=true -Dquarkus.hibernate-orm.log.format-sql=false \
    2>&1 | tee /tmp/cpq-8195.log
# ⚠️ 不要加 -Dquarkus.profile=test，那会连到 cpq_db_test，与基准 SQL 不是同一个库

# 跑全部
cd ../dev-docs/task-260914-报价单列表料号搜索与扩列/测试/s-api
S_API_SQLLOG=/tmp/cpq-8195.log bash run.sh

# 只跑某几条
bash run.sh TC-API-04 TC-API-11
```

可用环境变量：`S_API_BACKEND`（默认 `http://localhost:8195`）、`S_API_SQLLOG`、
`S_API_DB_HOST/PORT/USER/NAME/PASS`、`S_API_ADMIN_USER/PASS`、`S_API_SALES_USER/PASS`。

## 用例 ↔ AC 对照

| 用例 | AC | 断言要点 | 区分力从哪来（万一实现没做，这条会怎么红） |
|---|---|---|---|
| TC-API-01 | AC-2 | `partNo=S0004` 命中数 == Q1 现场值；抽样单回库确认真含该料号 | 参数被忽略 → 返全量 187，被「严格小于 Q0」这条挡下 |
| TC-API-02 | AC-3 | `partNo=A002` 命中数 == Q1；抽样单的 `customer_part_no` 确实含 A002 | 本库 `a002` **在销售料号里 0 命中、只在客户料号里命中** ⇒ 只搜销售料号会返 0，与期望 35 分叉 |
| TC-API-03 | AC-4 | 小写 `s000` == Q1，且**严格大于** `S0004` 的命中数；再验 `s000` == `S000` | 精确匹配 → 命中数不会大于 S0004；大小写敏感 → 两向不等 |
| TC-API-04 | AC-6 | `partNo` ∧ `keyword` 严格交集；关键字**现场从库里挑**（S0004 单的真子集） | keyword 被吞 → 等于单料号值，被上界挡下；做成 OR → 超过单条件值，被 AND 语义检查挡下 |
| TC-API-05 | AC-7 | `partNo` ∧ `status=DRAFT` == Q3；去掉 status 后**回到** Q1 | 料号条件被状态切换清空 → 回到全量 187，被显式比对挡下 |
| TC-API-06 | AC-13 | `categoryId=<UUID>` == 该分类名下单数；**拉全量逐行**校验 `categoryId`/`categoryName` | 只过滤当前页 → 全量拉取时出现不符行；`categoryName` 没实现 → 逐行判 BAD |
| TC-API-07 | AC-14 | `categoryId=NONE` == `IS NULL` 单数；逐行 `categoryId`/`categoryName` 均为 null | `NONE` 当成非法 UUID 或当成不过滤 → 数字对不上 |
| TC-API-08 | AC-15 | **`templateSeriesId=<系列UUID>`** == 该系列**全部版本之和**；逐行 `templateName` 一致 + 不带版本号（D-8）+ 跨版本聚合正向证据 | 退回按单个模板 ID 过滤 → 返回 32 而不是 59，被「必须严格大于单版本最大值」挡下 |
| TC-API-09 | AC-18 | 20 行请求 vs 1 行请求，**SQL 条数必须相等**；字典查询各 ≤2 条 | 逐行查字典 → 20 行那次条数明显更多 |
| TC-API-10 | AC-19 | SALES_REP（alice）**现场测定可见口径** → 不加料号 == 名下单数 → 加料号 == Q10 且严格更小 → admin 阳性对照远大于它 | 料号被忽略 → == 名下单数；角色被冲掉 → == 全库 43；两侧都有守卫 |
| TC-API-11 | AC-21 | `categoryId=not-a-uuid` → HTTP 400 + 可读消息；对照既有 `status` 非法值口径 | 500 / 200 静默返全量 → 分别判 FAIL 并打印实际 `totalElements` |

## 🚫 本脚本刻意规避的四类假绿

1. **硬编码期望值** —— 共享库 `cpq_db_0724` 随时有别的会话建单。每条用例都是「现场跑基准 SQL → 立刻打接口 → 再跑一次基准 SQL」。两次 SQL 采样不相等 ⇒ 判 **🌀不稳定**、要求重跑，**不判 FAIL 也不判 PASS**（避免把别人的建单动作误报成业务回归）。
2. **断言从未执行** —— 基准值为 0 时，「接口也返 0」没有区分力（全查不到也是 0），一律判 **⚠️未验证**，不计 PASS。逐行校验拿到 0 行时同样判 FAIL 而不是「没有不符的行 ⇒ 通过」。
3. **过滤条件被忽略也能过** —— 每条过滤类用例都带一个**上界**（全量 `Q0` 或单条件命中数）。改动前基线恰好就是「返回全量」，所以参数没接上时这些用例会**硬失败**，不会蒙混过关。
4. **脚本自身造假绿** —— 不吞 stderr；不用 `cmd | head || echo OK`（`head` 恒返 0，`||` 永不触发）；`psql` 带 `ON_ERROR_STOP=1` 且**校验返回值是数字**（空串不当 0）；开 `pipefail`；`grep` 一律用 `/usr/bin/grep -a`（本环境 `grep` 是 `ugrep -I`，含中文的内容会被静默判为二进制返空）。

## 🧪 证伪实验（脚本自检，必须做）

> 「首次 PASS 证明不了断言接上了」。本片的证伪不需要改代码 —— **改动前的后端就是天然的 not-X 对照组**：
> 那边三个新参数还不存在，会被静默忽略并返回全量。

```bash
S_API_BACKEND=http://localhost:8081 bash run.sh     # 打主工作区（改动前代码）
```
**期望结果：TC-API-01 ~ 08、10、11 全部 FAIL。** 若这里也全绿，说明断言根本没接上，用例作废。

### 已跑结果（2026-09-14 21:01，后端 = 8081 主工作区改动前代码）

```
PASS=0  FAIL=18  未验证=2  不稳定=0
```
归档在 `../证据/S-API-改动前证伪基线-8081.txt`，**不要覆盖它** —— 合并后 8081 就变成改动后代码，这份基线再也取不回来。

🔴 **这次实验当场抓到了 3 条我自己写的假绿，已修**（这正是必须做证伪的理由）：

| 原写法 | 为什么在改动前也 PASS | 改成 |
|---|---|---|
| TC-API-01 只抽 **1 单**回库核实料号 | 参数被忽略返全量时，**首行恰好**含 S0004 → PASS | 整页 20 单**逐单**核实（改后：6/20 单不含 → FAIL） |
| TC-API-02 同上 | 同上 | 逐单核实（改后：11/20 单不含 → FAIL） |
| TC-API-03 断言 `s000 == S000` | 参数被忽略时**两向都返 187**，相等**平凡成立** → PASS | 先守卫「两向都等于全量 ⇒ 参数没生效」，再比较 |

**改口径后（`templateSeriesId` / 造数守卫）重跑 TC-API-08 + TC-API-10：`PASS=0 FAIL=3 未验证=2`**，
归档在 `../证据/S-API-改动前证伪基线-8081-TC08-TC10-改口径后.txt`。这一轮**又抓到 3 个缺陷**（已修）：

| 缺陷 | 表现 | 改成 |
|---|---|---|
| 选系列按 `quotes DESC` 排 | 挑中「报价模板·ds原生v1.0」（3 版本但 **63 单全在一个版本**，`63-63=0`）⇒ 按系列与按单 ID 返回**同一个数**，断言恒真 | 按**判别力** `quotes - max_one DESC` 排，挑中正泰系列（`59-32=27`） |
| 逐行校验 `sorted(set(...))` 遇 `None` 抛 TypeError | 崩溃 ⇒ 无 `ROWCHECK` 输出 ⇒ 落兜底分支判 FAIL（方向碰巧对，但判据其实没产出结论） | `str()` 包一层，None-safe |
| `TC-API-08-跨版本` 断言 `DV > 1` | 过滤没生效返全量时，21 个模板全在里面，`DV>1` **平凡成立** → PASS | 挂在 `ROWCHECK=OK` 之下，且要求 `DV ≤ 本系列版本数` |

⚠️ **TC-API-09（AC-18）的日志计数机制尚未实跑过** —— 它需要一个开了 SQL 日志的后端，本阶段没起。
第二阶段首次运行时要先确认它**数得出非 0 条 SQL**（脚本已内置「数到 0 条 ⇒ 判未验证」的守卫，不会把「没数到」读成「没有 N+1」）。

## ✅ 原报的三处冲突 —— 主线已裁决（2026-09-14），用例已按裁决改口径

| # | 原冲突 | 裁决与处置 |
|---|---|---|
| **X-1** | AC-15 的 Q7 按模板*名*，契约按 `templateId`(UUID)，同名模板 3 个 id ⇒ 单 id 到不了 59 | 🚦 **用户裁决：改按「模板系列」聚合**。契约 `templateId` → **`templateSeriesId`**。TC-API-08 已改：请求 `templateSeriesId`，基准 SQL 改成 `JOIN template ON ... WHERE t.template_series_id=:tsid`，期望 **59**（三版本之和）。原登记的 `TC-API-08b 未验证` **已撤销**，冲突消解 |
| **X-2** | AC-19 假定既有角色隔离，但 SALES_REP 直接打接口返全量；alice 名下 0 单 ⇒ 断言退化 | 🚦 **用户裁决：造测试数据再验**（主线造，见 `任务.md` §⑧）。TC-API-10 已改：加了三重造数守卫（名下 0 单 / 无一含 S0004 / 全都含 S0004 → 一律判**未验证**不计 PASS），并补了 admin 阳性对照 |
| **X-3** | AC-21 没写 `templateSeriesId` 非法值的期望 | 🚦 **主线裁决：不扩范围**。保持「打印实际 HTTP 码但不判定」 |

### AC-19 的接口层机制（黑盒实测，改动前基线）

后端 `list` **不自动注入**角色过滤 —— `salesRepId` 是**显式 query param**，由前端在角色为 SALES_REP 时主动传。
实证（值互异，证明真的跑了）：`salesRepId=<alice>` → **0**、`salesRepId=<admin>` → **156**，与 SQL 逐个对上；
而 alice 的会话不带该参数直接打 → **187**。
⇒ TC-API-10 先**现场测定口径**：默认可见 == 名下单数 ⇒ `AUTO`；== 全量 ⇒ `EXPLICIT`，此时**补传 `salesRepId`** 复现前端的真实调用形态。走了哪条路径会打印出来，🚫 不写死。

## ⚠️ 环境事实（第二阶段执行时别再踩）

- `curl` 打本机服务**必须加 `--noproxy '*'`**（本机 shell 设了 `http_proxy`，不加会返 502）。
- 判后端起来了看业务端点返 **401**；`/q/health` 返 404 是正常的，**它不是健康探针**。
- 列表接口响应是**带包裹的**：`{code,message,data:{content,page,size,totalElements,totalPages}}`，`page` 从 **0** 起。
- `size` 实测无上限（`size=500` 一次返回全部 187 行），逐行校验因此可以一次拉全量，不必翻页。
- 既有关键字参数名是 **`keyword`**（`search`/`q`/`name`/`query` 都不生效，会被当成无过滤返全量）。
- 既有 `status=<非法值>` 改动前实测就返 **400** —— 这是 AC-21 要对齐的口径参照。
- 🚨 **但既有 UUID 型参数的非法值口径不是 400**：`salesRepId=not-a-uuid` 改动前实测返 **404**。
  ⇒ 库里同时存在两种「非法值」口径（枚举 400 / UUID 404），而 `AC-21` 明确要求 `categoryId` 走 **400**。
  实现若照 UUID 那一套写成 404，TC-API-11 会判 FAIL —— 这是**有意的**，不是误报。
- 既有销售员过滤参数名是 **`salesRepId`**（显式传参，后端不自动注入，见上）。
- SALES_REP 可登录账号：**`alice / Admin@2026`**（ACTIVE，`is_first_login=false`）。
  库里另外 4 个名下有单的 SALES_REP 要么 `password_hash='x'`（造不出登录）、要么 INACTIVE。
  🚫 **不新建用户、不改用户状态** —— 那是写操作，也是全局状态。
