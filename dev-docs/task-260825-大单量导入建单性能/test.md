# test.md —— 测试方案 + AC 可追溯矩阵

> 闸门 A 的**前置产物**，开工前定稿。
> 🚫 按 `docs/rules/testing.md §1`：用例从 `问题说明.md §⑥` 的 **AC 原文**派生，**不从实现代码派生**。
> 派 `cpq-tester` 时**只给 AC 章节，并显式禁止其读 `cpq-backend/src/main/`**。
>
> 🔴 **2026-08-25 因独立评审扩范围（D-1 → D-1+D-3）重写。** 旧版只覆盖 D-1，丢掉重读。

---

## 0. 两个必须先说清的环境约束

### 0.1 测试库 ≠ dev 库

| profile | 连的库 | 用途 |
|---|---|---|
| 默认（dev server 8081） | `10.177.152.12:5432/**cpq_db_0724**` | 日常开发 / **复现数据在这里** |
| `test`（`./mvnw test`） | `10.177.152.12:5432/**cpq_db**` | 后端自动化测试 |

🚨 复现本缺陷的 `quotation 1ed36b62-...`（1845 行）**只存在于 `cpq_db_0724`**，`mvnw test` 里**取不到**。

**因此：AC-1 / AC-2 / AC-9（1845 行规模场景）无法用 `mvnw test` 断言**，只能由**主线亲验**在 dev 库执行。
矩阵中如实标注为「真机」，🚫 不许用小数据量单测冒充。
自动化测试负责**等价性、边界、SQL 条数**这三类与规模无关的断言。

### 0.2 ⚠️ 测试环境的事务上下文与生产不同（影响 T-1 的可信度）

`LazyQuoteBucketEquivTest` 等集成测试的方法带 `@Transactional` —— 即**测试里 `snapshotLines` 跑在事务内、
持久化上下文非空**，而生产的 `snapshotLines` **无事务**（`ConfigureSnapshotService` 类 javadoc）。

**后果**：T-1 的 SQL 条数断言仍然有效（数的是条数，不是事务行为），
但 🚫 **不许拿 T-1 的绿当「生产 flush 行为已验证」** —— 汇报时必须区分这两件事。

> ⚠️ `CLAUDE.md §3.2`：**共享库上不许跑会清库的测试**，哪怕写在 `beforeAll` 里。本任务全部测试**只读或自建自清**。

---

## 1. AC 可追溯矩阵

| AC | 覆盖它的测试 | 层级 | 验收证据形式 |
|---|---|---|---|
| **AC-1**（无 reaper / `cardValuesReady:true` / **实测墙钟先测后定**） | 亲验-1 | **真机** | ① `curl -w '%{time_total}'` 耗时；② 日志 grep `ARJUNA` **空结果 + 同窗口非空日志**（证明日志确在写）；③ 响应体 JSON 原文。归档 `证据/` |
| **AC-2**（报价空 0 / 核价空 0 / 总 1845） | 亲验-2 | **真机** | psql 输出原文，含改动前后对照（前：1845/1845/1845）。归档 `证据/` |
| **AC-3**（**两处** N+1 计数与 N 无关） | **T-1** | 集成 | 两组 N（5/50）× 两个计数器（`quotation_line_component_data` / `quotation_view_structure`）的对照表 + 改动前基线 |
| **AC-4**（INPUT 键盖回不回退；非 INPUT 列确实刷新） | **T-2** + 亲验-3 | 单元 + **真机序列** | T-2：`OverlayExistingInputKeysTest` 7 例通过数；亲验-3：UI 前后截图 + 库内 `row_data` 前后对照。归档 `证据/` |
| **AC-5**（失败仍报 `cardValuesReady:false` + `warnings`） | **T-3** | 集成 | 构造失败后的响应体 JSON 原文 |
| **AC-6**（四类字段逐位等价） | **T-4** + 亲验-4 | 集成 + **真机背靠背** | T-4：等价断言通过数；亲验-4：改动前后 dump 的 `diff`（除时间戳外为空）。归档 `证据/` |
| **AC-7**（小单量 / 非批量分支不变） | **T-5** + 亲验-5 | 集成 + 真机 | 测试通过数 + UI 截图 |
| **AC-8**（空集 / 单行 / 全 NULL 不 NPE） | **T-6** | 单元 + 集成 | 测试通过数 |
| **AC-9**（④ `ensureExcelValues` 不成为新墙 + **四步耗时逐一记录**） | 亲验-6 | **真机** | B-10 埋点输出的四步耗时表，**写入 `test-report.md`** |
| **AC-10**（仍按冻结结构算值，不退回同卡双值） | **T-7** + 亲验-3 | 集成 + 真机 | 卡片小计与报价总额一致的实测值 |

**反向覆盖（每个测试都指得回 AC）**：
T-1→AC-3 ｜ T-2→AC-4 ｜ T-3→AC-5 ｜ T-4→AC-6 ｜ T-5→AC-7 ｜ T-6→AC-8 ｜ T-7→AC-10 ｜
亲验-1→AC-1 ｜ 亲验-2→AC-2 ｜ 亲验-3→AC-4,AC-10 ｜ 亲验-4→AC-6 ｜ 亲验-5→AC-7 ｜ 亲验-6→AC-9。
**无孤儿测试，无未覆盖 AC。**

---

## 2. 测试用例设计

### T-1 · SQL 条数断言（AC-3 · 本任务唯一能防止缺陷复发的测试）

**必须写。** 两处 N+1 都因为「全工程没有任何 SQL 条数断言」而溜过去，T-1 就是来补这个洞的。

- **机制**：`em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics()`，
  `setStatisticsEnabled(true)` 后取查询执行计数 —— 范式见既有 `GetByIdBatchEquivTest`。
- **两个计数器都要断言**：
  - `quotation_line_component_data` 读取条数 ≤ ⌈N/200⌉（**D-1**）
  - `quotation_view_structure` 读取条数 ≤ 4（四类 `view_kind` 各一次，**与 N 无关**）（**D-3**）
- 🔑 **必须用两组不同的 N 对照**（N=5 与 N=50），断言两个计数**均不随 N 增长**。
  只测一个 N 无法区分「常数条」与「每行一条」。
- 🚨 **反向实验（`testing.md` 要求，必做）**：分别把两处批量化改回逐行，本测试**必须各变红一次**。
  不变红 = 测试是空的，须重写。**首次 PASS 不等于有效。**

### T-2 · INPUT 键盖回等价（AC-4）

- `OverlayExistingInputKeysTest` 现有 **7 例**，**不得修改其断言**，必须继续全绿。
- 若实现需要改动该测试才能通过 → **说明改坏了 E-4，立即停下报主线**，不许改测试迁就实现。

### T-3 · 降级路径仍报错（AC-5）

- 构造物化必失败场景（如令某 `$view` 解析失败），断言 `cardValuesReady=false` 且 `warnings` 含失败原文。
- 🚫 防「为了让 AC-2 变绿而吞掉失败」—— 这是本条存在的唯一理由。

### T-4 · 批量取数与逐行取数逐位等价（AC-6）

- **对标既有 `LoadSnapshotRowsByLinesEquivTest` 的结构**（同文件同构先例）：
  证明「整单批量查」与「逐行查的并集」**逐键逐值相同，含 null 值一致**。
- **D-3 侧同理**：整单查一次的冻结结构 == 逐行查 N 次拿到的那一份。
- 只读，不写库。
- ⚠️ 若实现采纳了 `loadSnapshotRowsByLines` 姊妹方法的可选改法，**本测试需相应扩展**，
  且原 `LoadSnapshotRowsByLinesEquivTest` 必须保持不动、继续全绿。

### T-5 · 小单量与非批量分支（AC-7）

- 1~3 行单据走加产品 / 编辑失焦同步；覆盖 `wholeBatchEnabled=false` 分支（本次不改，属回归确认）。

### T-6 · 边界（AC-8）

- 空集合入参 → 返回空 Map，不抛异常（对标 `LoadSnapshotRowsByLinesEquivTest` TC-2）
- 单行报价单退化（对标其 TC-3）
- 全部 `row_data` 为 NULL 的新建单 → 不 NPE
- **IN 列表含 `null` 元素**（约束 C-2）→ 不抛异常

### T-7 · 冻结结构优先级不变（AC-10 · 守 `b6e86a18`）

- 断言卡片值仍**优先按冻结结构**算，三级降级链（冻结结构 → prefetch 模板快照 → 模板表）
  的**优先级与语义**不变；卡片小计与报价总额一致，不退回「同卡双值」。

---

## 3. 必须保持全绿的既有测试（回归基线）

| 测试 | 守什么 | 备注 |
|---|---|---|
| `OverlayExistingInputKeysTest`（7 例） | E-4 INPUT 键盖回语义 | 断言不许改 |
| `GoldenCardValuesEquivTest` | 卡片值金额基线 | ⚠️ 见下方 `BL-0021` 提示 |
| `CardValuesBatchPersistEquivTest` | 批量落库与逐行落库等价 | |
| `LoadSnapshotRowsByLinesEquivTest` | 同文件的批量加载先例 | 签名不许改 |
| `RowDataBatchWriteEquivTest` / `RowDataWholeBatchEquivTest` / `PersistWholeBatchEquivTest` | 同一循环内既有的批量化护栏 | |
| **`LazyQuoteBucketEquivTest`**（评审补入） | `:52` 断言 `rt < 30`（往返数上限） | 🔑 **它正是约束 C-1 的探针** —— 改完盯着这个数**不要涨**，涨了说明给 saveDraft 增量路径加了无谓预载 |
| **`ConfigureSnapshotEmptyOverwriteGuardTest`**（评审补入） | `:266` 真调 `snapshotQuotation(id, true)` 打真库 | 会实际走新预载路径 |

⚠️ `GoldenCardValuesEquivTest` 的 rockwell 常量**已知在干净 master 上就可能失效**（`BL-0021`）。
若它红，**先用 stash 背靠背确认是否本次引入**，不要直接归因于本次改动。

---

## 4. 判绿纪律（`testing.md`）

1. **全绿 ≠ AC 达成**。等价性测试对 N+1 天然全绿 —— 本次两处缺陷正是被这类测试**漏掉**的
   （见 `问题说明.md §④「为什么测试没拦住」`），不许再用它们当达成证据。
2. **T-1 必须做还原实验**（两处分别改回逐行 → 必须各变红一次）。
3. 🚩 **别再用「日志里没看到」证明「不存在」** —— 初稿正是这样漏掉 D-3 的
   （裸 `em.createNativeQuery` 不打日志）。要证明「没有 N+1」，用 **SQL 条数断言**，不用日志计数。
4. **不采信子代理的「已完成」** —— 主线亲验不可省。
5. 证据必须**非空正向结果**；空列表 / 0 行 / `—` 一律不算通过。
6. 归档纪律：矩阵里写「归档 `证据/`」的产物必须真的**复制到本任务目录并随任务提交** ——
   留在测试输出目录的会被下一轮跑测试清掉，等于没有证据。
   ⚠️ 本次排查已吃过这个亏：ARJUNA 原始日志因未落盘归档，评审时已不可得。
