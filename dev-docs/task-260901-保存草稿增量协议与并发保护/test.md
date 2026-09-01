# 测试方案 · task-260901

> 🚫 **本方案从 `需求文档.md` §③ 的 AC 原文派生，不读实现代码**（`testing.md` §1）。
> 测试子代理**禁止读** `cpq-backend/src/main/java/com/cpq/quotation/service/`、`cpq-frontend/src/pages/quotation/` 两个实现目录。
> 例外：复现失败、定位原因时可读，但**必须在用例写完之后**，且不得据此修改断言。

---

## 0. 测试环境与前置

| 项 | 值 |
|---|---|
| 后端 | dev server `8081`，库 `cpq_db_0724`（**注意**：`mvnw test` 默认走 `test` profile 的 `cpq_db`，与 dev 库不同） |
| 前端 | Vite `5174`（`/api` 代理到 8081） |
| 账号 | `admin` / `Admin@2026` |
| 基准单 | 1845 行 DRAFT 单（如 `QT-20260901-0218`）。**用例执行前须确认其 `status='DRAFT'`、行数 1845** |

🚨 **性能类用例（T-18）的执行前提**：确认**无其他大请求在跑**。实测证据（`证据/E4`）：4 个并发大请求期间，同一个 0 行空单的响应从 0.18 s 劣化到 9.8 s，`ping` 从 32 ms 涨到 878 ms。同码同数据曾测出 16 s 与 43 s 两种结果。**不满足该前提时测得的数字一律作废**。

---

## 1. AC 可追溯矩阵

| AC | 覆盖它的测试 | 层级 | 验收证据形式 |
|---|---|---|---|
| AC-1 | T-1 | E2E | Network 请求体 JSON（存档到 `证据/`） |
| AC-2 | T-2 | E2E | 请求体 JSON + 库查询结果 |
| AC-3 | T-3 | E2E + SQL | 四张表 count=0 的 SQL 输出 |
| AC-4 | T-4 | E2E + SQL | 请求体三数组为空 + `n_tup_upd` 差值为 0 |
| AC-5 | T-5 | E2E | 无 PUT 请求 + toast 文案截图 |
| AC-6 | T-6 | SQL | 保存前后整表 md5 指纹对比 |
| AC-7 | T-7 | SQL | `count(*) WHERE quote_card_values IS NULL` = 1 |
| AC-8 | T-8 | 日志 | `[ensure-cardvalues] 补算 1 行` 日志原文 |
| AC-9 | T-9 | 单元 | 断言输出 |
| AC-10 | T-10 | 单元 | 断言输出 |
| AC-11 | T-11 | 接口 | 两次响应的 `userDataVersion` |
| AC-12 | T-12 | E2E + 接口 | 409 响应体 + 弹窗截图（对照原型） |
| AC-13 | T-13 | 集成 | 调用前后 `user_data_version` 相等 |
| AC-14 | T-14 | 接口 | `quote-card-edit` 响应含 `userDataVersion` |
| AC-15 | T-15 | E2E | Network 响应体大小 |
| AC-16 | T-16 | 接口 | 响应 JSON 键集合 |
| AC-17 | T-17 | E2E + SQL | 第二次请求体的 id + 库中行数=1 |
| AC-18 | T-18 | E2E | 三次计时，取最大值 |
| AC-19 | T-19 | SQL + E2E | 核价空值行数对比 + 详情页截图 |
| AC-20 | T-20 | SQL | 两张子表 count + 内容 md5 |
| AC-21 | T-21 | E2E + SQL | `annual_volume=100` |
| AC-22 | T-22 | E2E | 状态变更 + 总价一致 |
| AC-23 | T-23 | E2E | 刷新后两处值截图 |
| AC-24 | T-24 | E2E | 无异常 |

**三类覆盖**：单点 T-1~T-4、T-6~T-11、T-14~T-16、T-19~T-21；**序列** T-17、T-23、T-22；**边界** T-5、T-12、T-13、T-24。

---

## 2. 关键用例设计要点

### T-6 / T-19 / T-20 —— 「未变的不动」怎么证

统一手法：**保存前后取整表指纹对比**，而非抽样。

```sql
-- 保存前后各跑一次，比对
SELECT md5(string_agg(cd.id::text || coalesce(cd.row_data::text,''), ',' ORDER BY cd.id))
FROM quotation_line_component_data cd
JOIN quotation_line_item li ON li.id = cd.line_item_id
WHERE li.quotation_id = :qid;
```

断言：**只有目标行的分组指纹变化**，其余 9224 条逐字节相同。

### T-9 / T-10 —— 语义比对的分辨力

这两条**必须成对**，缺任一条都测不出真问题：

| | 输入 | 期望 |
|---|---|---|
| T-9 | 同一份 `row_data`，键顺序打乱（模拟 PG jsonb 规范化差异） | 判「未变」，不产生 UPDATE |
| T-10 | 任一数值差最后一位小数（如 `3.3` → `3.30000000001`） | 判「已变」，写入 + 卡片值清空 |

> T-9 单独绿没有意义 —— 一个「永远判未变」的错误实现也能让它绿。T-10 是它的对照组。

### T-13 —— 最容易被漏掉的一条（守 B-3e）

```
1. 打开报价单，记下 user_data_version = N
2. 不做任何编辑
3. 触发 POST /quotations/{id}/ensure-card-values，等日志出现补算完成
4. 断言：库中 user_data_version 仍为 N
5. 随后正常编辑一格并保存 → 断言不出现 409
```

**为什么必须测**：若派生数据写入递增了版本号，用户什么都不做也会被反复要求刷新，且这个 bug 在单人测试时可能被误认为「偶发」。

### T-12 —— 并发冲突的构造

不依赖两个真实浏览器。手法：
1. 用 API 取得当前版本 N
2. 用 API 直接发一次保存（版本 →N+1），模拟「他人修改」
3. 浏览器端此时仍持有 N，点保存
4. 断言 409 + `reason=STALE_VERSION` + 弹窗形态对照 `原型图/冲突提示.html`

### T-18 —— 性能用例的纪律

- 测 **3 次取最大值**，不是取最好那次
- 每次测量前确认无并发大请求（见 §0）
- 记录端到端两段：`PUT /draft` 耗时 + 其触发的 `[ensure-cardvalues]` 耗时，**两段之和** ≤ 10 s
- 同时记录后端 `[draft-profile]` 的 S1/S2/S3 分解，便于超标时定位

---

## 3. 🚫 假绿防范（`testing.md` §3.3）

| 风险 | 对策 |
|---|---|
| **断言从未执行** | 每条涉及"数量"的断言前，先断言"结果非空"。如 T-6 先断言指纹字符串非空、行数=9225，再比对 |
| **空夹具** | 基准单必须是**真实 1845 行单**，不许用 3 行的小夹具跑性能与增量用例 |
| **测了旧代码** | 每轮测试前确认 dev server 已重启且加载的是被测分支代码（worktree 场景见 `git-worktree.md`） |
| **证据被下轮清掉** | Playwright 默认在开跑前清空 `test-results/`。**要留证的截图必须复制到 `证据/` 目录并随任务提交** |

## 4. 证伪实验（强制，`testing.md` 清单）

新加的守卫必须做还原实验，**首次 PASS 不算数**：

| 守卫 | 破坏方式 | 期望 |
|---|---|---|
| B-1a 语义比对 | 改回字符串比对 | T-9 变红 |
| B-1c 有条件置 NULL | 改回无条件置 NULL | T-7 变红（空值行数变 1845） |
| B-3b 版本校验 | 去掉校验 | T-12 变红（不再返 409） |
| B-3e 派生不递增 | 让 `ensureCardValues` 也递增 | T-13 变红 |
| B-4b 轻量响应 | 改回 `loadLineItems` | T-15 变红（响应体 > 500 KB） |

**每一项都要记录"破坏后哪条用例变红"，写进 `test-report.md`。** 破坏后仍全绿 = 该用例无分辨力，必须重写。

## 5. 回归基线

| 套件 | 已知既有失败（**非本次引入，判回归须 A/B 同型对比**） |
|---|---|
| `quotation-flow.spec.ts` | **恒 4 条失败**：`:144` LEGACY smoke、`:463` TC-F1、`:522` TC-F2、`:624` TC-075（缺 `PW_PRECISION_SEED_QUOTATION_NO` 环境变量）。2026-08-31 在干净 master 上 `git stash` 后实测同为这 4 条 |
| `vitest src/pages/quotation/` | `treeFormulaParityFixture.test.ts` **文件级失败**：夹具 `dev-docs/task-0803-BOM页签增加父子取值公式/fixtures/tree-formula-parity-cases.json` 从未提交进 git（`git ls-files` 为空、目录不存在），任何干净检出同此 |

🚫 **不许把这些既有失败当成本次引入**，也不许因为"反正本来就红"而跳过对比 —— 每次都要跑 A/B 确认失败**条目与数量**都没变化。
