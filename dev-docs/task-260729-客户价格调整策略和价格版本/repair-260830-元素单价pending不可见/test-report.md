# test-report · repair-260830 元素单价 pending 不可见

**执行人**：test-engineer（子代理）
**执行日期**：2026-08-30
**worktree**：`/home/joii/project/cpq/.claude/worktrees/repair-260830-element-price-pending`
**分支**：`fix/repair-260830-element-price-pending`
**方案**：`test.md`（AC 原文在 `问题说明.md` ⑥，冲突以 AC 原文为准）

> **本次无契约变更，无需回写 `main-api.md`**（依据 `api.md`：端点/参数/响应/错误码全未变）。

---

## 0. 一句话结论

**修复有效，且已通过证伪实验证明用例不是白测。**
库层与改写器层的 AC 全部达成；**UI 层 4 条（AC-1 / AC-3 / AC-12 UI 侧 / AC-13 整单渲染）我无法验证**，因为 V397 尚未应用到 dev 库（未合并），必须由主线合并后亲验 —— 见 §5。

另有 **2 项立项文档缺陷**必须回报主线（AC-8 在 dev 数据上不可执行 / AC-4·AC-5 的证据形式在 dev 上不存在）—— 见 §4。

---

## 1. 环境与红线遵守

| 项 | 实际 |
|---|---|
| dev 库 `cpq_db_0724` | **全程只读**。零 INSERT / UPDATE / DELETE / DDL。V397 **未**被我应用（实测 `flyway_schema_history` 无 397 行） |
| 测试库 `cpq_db` | 造数 / 转正 / 探针在此进行，**已全部清理，残留 0**（§6） |
| 端口 | 用 **8123**（临时）。`8081` / `5174` 全程未占用、未重启，验后仍 401 / 存活 |
| 用户在用的单 `QT-20260830-0211` | **未被任何写操作触碰** |
| 红线 | 未触发。测试库内的 DELETE 均**先量化再执行**（§6），对象全部为本次自建 |

⚠️ **一个我主动规避的坑**：启动 worktree 后端时若用**默认 profile**，Flyway `migrate-at-start` 会把 **V397 自动写进 dev 库**。我显式加了 `-Dquarkus.profile=test` 指向 `cpq_db`，并从启动日志核验了 `Database: jdbc:postgresql://…/cpq_db` 才继续。

---

## 2. 逐条执行结果

### 2.1 我已验证的（10 条）

#### T-2′ → **AC-2**（阴性 · 单点 · 库层）✅

测试库夹具，三参传本单 `pq`：

```
 material_no  | element_code | unit_price
--------------+--------------+------------
 ZZT-OFFICIAL | ZZT260830-AG | 12345.0000
 ZZT-ONLY-Q1  | ZZT260830-AG | 12345.0000
 ZZT-SHARED   | ZZT260830-AG | 12345.0000
```

**改动前同一夹具**（V397 未装时，只有两参版）只返回 `ZZT-OFFICIAL` 1 行 —— pending 料号全部缺失，逐字复现原 BUG。

**dev 真实数据佐证**（B-4 脚本 P-0 节，把三参函数体内联成只读查询，dev 库只读）：

```
 material_no  | element_code | unit_price | currency | price_unit
--------------+--------------+------------+----------+------------
 202601010001 | 白银         | 12345.0000 | CNY      | kg
```

⇒ **AC-2 断言的 `unit_price = 12345.0000` 在 dev 真实数据上成立**（改动前为 0 行）。

#### T-7 → **AC-7**（边界 · `p_pq=NULL` 退化）✅

```
 two_arg_rows | three_arg_null_rows | only_in_two_arg_expect0 | only_in_three_arg_expect0
--------------+---------------------+-------------------------+---------------------------
           10 |                  10 |                       0 |                         0
```

⚠️ 上式有重言式风险（两参版已改为委托三参 NULL）。**补了一条非重言式断言**：三参 NULL 的候选集 ≡ `is_current`-only 候选集（口径取自 `问题说明` §4.2，不取自实现）：

```
 fn_materials | old_candidates | fn_extra_expect0
--------------+----------------+------------------
           10 |             10 |                0
```

dev 侧同口径（B-4 P-0）：`③NULL(核价/冻结) → 16`、`④陌生 uuid → 16`，与改动前 `16` 一致。

#### T-8 → **AC-8**（边界 · 跨单隔离）✅ *（在测试库构造数据上）*

```
Q1 视角: has_own_pending=1  has_shared=1  has_official=1  has_other_order=0
Q2 视角: has_own_pending=1  has_shared=1  has_official=1  has_other_order=0
```

**非重言**：own=1 证明断言真的取到了数据，other=0 才有意义。
🚨 **但这条在 dev 数据上不可执行 —— 见 §4.1，必须回报主线。**

#### T-9 → **AC-9**（**序列 AC**，本次最关键）✅

全程测试库。中间态与最终态**都断言了**：

| 步骤 | 状态 | `unit_price` |
|---|---|---|
| ① 中间态：`is_current=f, pending=Q1`，以 `p_pq=Q1` 调用 | pending | **12345.0000** ✅ |
| ② 转正 `SET is_current=true, pending_quotation_id=NULL`（命中 1+1 行） | — | `UPDATE 1` / `UPDATE 1` |
| ③ 最终态 A：转正后仍以 `p_pq=Q1` 调用 | 正式 | **12345.0000** ✅ |
| ④ 最终态 B：以 `p_pq=NULL` 调用（模拟核价侧） | 正式 | **12345.0000** ✅ |
| ⑤ 夹具复位回 pending | pending | 已还原（已核验） |

⇒ `is_current` 半边未被破坏，E-8 成立。

#### T-4 / T-5 → **AC-4 / AC-5**（零回归）⚠️ 部分验证

**第一次横扫是假绿，我把它推翻了**：按客户比对 md5 时，6 个客户里 5 个的 md5 都是 `d41d8cd98f00b204e9800998ecf8427e` —— 那是**空字符串的 md5**。它们根本没有取价结果，「前后一致」是拿空集比空集，属 `testing.md` §3.3 的断言空跑。

改为**非重言式断言**（基数必须 > 0）：

```
 基数_必须大于0 | NULL_vs_随机pq_差异_期望0 | NULL_vs_本单pq去掉pending_差异_期望0
----------------+---------------------------+--------------------------------------
             10 |                         0 |                                    0
```

⇒ **正式行部分的取价结果不随 `p_pq` 变化**，这是 E-4/E-5 的核心不变量，已在 10 行非空数据上成立。

dev 侧核价口径（B-4 S-1）：候选料号 **16**、`row_cnt 80`，与 AC-4 要求的 16 一致。

🚨 **但 AC-4 / AC-5 原文要求的证据形式在 dev 上不存在 —— 见 §4.2。**

#### T-10 → **AC-10**（改写器单测）✅

`./mvnw test -Dtest=QuotePendingRewriterTest` → **Tests run: 15, Failures: 0, Errors: 0**（11 条既有 + 5 条新增）。

新增用例与 AC-10 四个分项逐条对应：

| 用例 | AC-10 分项 |
|---|---|
| `fnCall_twoArg_rewrittenToThreeArg` | ① 补为三参且第三参 `:pq` |
| `fnCall_insideComment_notRewritten` | ② 注释里的文本不被改写 |
| `fnCall_alreadyThreeArg_idempotent` | ③ 幂等跳过 |
| `fnCall_customerElementPrice_untouched` | ④ 不碰 `f_customer_element_price` |
| `fnCall_noQuotationContext_rewriterNotInvoked` | ④ 补充：非报价单上下文不补参 |

① 还在真库做了 `LIMIT 0` 执行探测，**顺带证明 V397 三参重载存在且重载无歧义**，质量高于 AC 要求。

⚠️ **但 `fnCall_noQuotationContext_rewriterNotInvoked` 是弱断言 —— 见 §4.3。**

#### T-11 → **AC-11**（启动期校验）✅

worktree 后端，`-Dquarkus.profile=test -Dquarkus.http.port=8123`：

```
Database: jdbc:postgresql://10.177.152.12:5432/cpq_db   ← 验明正身，非探到别人的实例
[QuoteViewValidationService] 校验通过 total=170 ok=170
Listening on: http://localhost:8123 / Profile test activated

flyway: version=397  success=t  checksum=-526805303
GET /api/cpq/components -> 401     ← 应用在跑、鉴权正常
GET /q/health           -> 404     ← 符合"它不是健康探针"的已知口径
```

**阳性对照**：`total=170 > 0`，校验非空跑；测试库中引用 `f_material_element_price` 的视图 **24 个**全部通过。

#### T-13 → **AC-13**（性能）⚠️ 仅函数层

dev 库只读代理（候选集 CROSS JOIN 元素价，即 `realtime` 分支主成本项），各 3 次：

| 口径 | 行数 | 耗时中位数 |
|---|---:|---:|
| A 现状（`is_current` only） | 80 | **20.06 ms** |
| B 修复后（`OR pending = 本单`） | **9305** | **21.50 ms** |

⇒ 函数层劣化 **约 +7%**，远低于 20% 阈值；行数 80 → 9305 与立项预估逐字吻合。
🚨 **AC-13 原文要的是「整单渲染耗时」，那需要 dev server + dev 数据 —— 我做不到，见 §5。**

#### AC-12（未建档元素仍为空）✅ 库层 / ⚠️ UI 层未验

dev 只读实测：正泰 12 个元素中**仅「白银」**在 `element` 主表建档且有行情；
`f_customer_element_price('CUST-0004', …)` 只返回 5 个元素（`Ni/Cu/Zn/Ag/白银`）。
⇒ 修复后 `LEFT JOIN` 对其余 11 个元素仍匹配不上 → 值为 `NULL` → **空**，且**不报错**（P-0 查询正常返回，无异常）。这与 AC-12 的预期一致。

UI 侧「不得出现『加载中…』占位」我无法验证（需渲染），列为主线亲验项。

#### AC-14（自检证据）✅

> **已自检**：worktree `cpq-backend` 内 `./mvnw test -Dtest=QuotePendingRewriterTest` → 15/15 全绿 ✅；
> `com.cpq.datasource.sqlview.*Test` 全包 84 条 → 仅 2 条失败且已 A/B 归因为 **pre-existing** ✅；
> Flyway `V397 success=t` ✅；后端 `/api/cpq/components` → **401** ✅；
> 测试库夹具与探针**残留 0** ✅；dev 库**零写操作** ✅。

---

## 3. 证伪实验（R-1 / R-2 / R-3）—— 全部达成

> `testing.md` §4.4：首次 PASS 证明不了守卫接上了。每条都**先证明干预真的生效**，再看是否变红。

### R-1｜把 `OR pending_quotation_id = p_pq` 改回 `AND is_current = true` → **变红** ✅

**干预生效证明**：用 `pg_get_functiondef` 取 V397 落库后的三参函数体，替换过滤条件生成探针 `zz_probe_r1_fmep`，并打印替换后的过滤行确认命中：`['AND is_current = true', 'AND is_current = true']`（2 处都改到）。

| | 真实修复 | R-1 探针 |
|---|---|---|
| `ZZT-ONLY-Q1` | 12345.0000 | **消失** |
| `ZZT-SHARED` | 12345.0000 | **消失** |
| `ZZT-OFFICIAL` | 12345.0000 | 12345.0000 |
| T-2′ / T-8 断言值 | own=1, shared=1 | **own=0, shared=0 → 用例会失败** |

⇒ T-2′ / T-8 **不是白测**。

> 补充：R-1 还有一次**天然对照** —— 我在 V397 落库**之前**（18:22，函数签名列表实测只有两参版）就在同一夹具上跑过一次，结果同样只有 `ZZT-OFFICIAL`。这是未经改造的真实旧代码，比人造探针更强。

### R-2｜关掉改写器补参规则 → **变红** ✅（用反向断言等价达成）

我不能改后端工程师的 `src/main` 产出（越界纪律），改用**反向断言脚手架**证明观察手段是活的：写一条断言「两参调用**不会**被补成三参」，跑出来必须 FAIL。

```
[R-2 SCRATCH] rewritten sql =
  LEFT JOIN f_material_element_price(:customerCode, :priceBaseDate, :pq) cep
[ERROR] ZzScratchR2FalsifyTest.inverse_expectFail_twoArgStaysTwoArg
        [R-2] 若本条 FAIL => 补参确实发生、断言是活的 ==> expected: <false> but was: <true>
```

⇒ 补参**确实发生**（改写后 SQL 里 `:pq` 是第三参），且断言能抓到它。
配套 DB 侧：若不补参，SQL 走两参版 → 实测两参版对 pending 料号**取不到价**（T-7 的 10 个料号里不含任何 pending 料号）。两半合起来即 R-2 的完整链条。
**脚手架已删除**（`ZzScratchR2FalsifyTest.java`，验后即删，git status 已确认无残留）。

### R-3｜砍掉 `is_current = true OR` 半边（A0 已否决的备选）→ **变红** ✅

**干预生效证明**：探针 `zz_probe_r3_fmep` 的过滤行实测为 `['AND pending_quotation_id = p_pending_quotation_id', …]`（`is_current` 半边确已删除）。

| 断言 | 真实修复 | R-3 探针 |
|---|---|---|
| `ZZT-OFFICIAL`（正式行）取价 | 12345.0000 | **消失** → T-9 步骤③④ 失败 |
| 核价侧口径（`p_pq = NULL`）行数 / 候选 | **10 / 10** | **0 / 0** → T-4 失败 |

⇒ 与 `问题说明` 证据 5 预言的「核价侧 16 → 0」**同型吻合**。这条被否决的备选如果被误实施，测试**会**抓住。

---

## 4. 🚨 必须回报主线的问题（立项文档缺陷 + 用例质量）

### 4.1 **AC-8 在 dev 数据上不可执行 —— 断言会空跑（假绿）**

AC-8 原文要求「结果中**不含另一张单独有的** pending 料号」。实测 dev 库：

```
 a_total | b_total | a_exclusive | b_exclusive
---------+---------+-------------+-------------
    1845 |    1845 |           0 |           0
```

**两张单的料号集合完全相同，谁都没有「独有料号」。** 全库也只有这两张 pending 单（均为 CUST-0004）。
⇒ 在 dev 上跑 AC-8，断言的是**空集不相交**，**恒真且永远发现不了隔离被破坏** —— 正是 `testing.md` §3.3 的假绿。

**我的处理**：在测试库构造了带独有料号的数据（`ZZT-ONLY-Q1` / `ZZT-ONLY-Q2` / `ZZT-SHARED`）才使断言非重言（own=1 且 other=0）。
**建议**：AC-8 的验收口径应明确写成「在构造数据上验证」，或主线亲验时接受测试库证据。**不要在 dev 上跑它然后打勾。**

### 4.2 **AC-4 / AC-5 要求的证据形式在 dev 库不存在**

| AC | 原文要求的证据 | dev 库实际 |
|---|---|---|
| AC-4 | 「打开任一**核价单**」+ `costing_card_values` md5 比对 | `costing_order` **0 行**，一张核价单都没有 |
| AC-5 | 「打开 `CUST-0001` 与 `CUST-0002` 各一张**报价单**」+ `snapshot_rows` md5 | 全库**只有 2 张报价单**，都是 CUST-0004；这两个客户**一张单都没有** |
| AC-6 | 「打开一张**已提交冻结**的报价单」 | 两张单都是 **DRAFT**，无冻结单（B-4 S-6 实测：`DRAFT | 2`） |

⇒ **AC-4 / AC-5 / AC-6 按原文的 UI 路径无法执行。**
可替代的证据是**函数层**：核价侧候选恒 16（S-1 已验）、正式行取价不随 `p_pq` 变化（§2.1 已验）、冻结态不改写因而走两参版（结构性论证 + S-3 指纹）。
**请主线裁决**：是接受函数层证据，还是先在 dev 造一张核价单/冻结单再验。**我没有替主线降低验收标准的权限，故如实列出。**

### 4.3 `fnCall_noQuotationContext_rewriterNotInvoked` 是弱断言（建议后端加强）

该用例体内只断言了输入常量 `FN_CALL_VIEW` 不含 `:pq`：

```java
assertFalse(FN_CALL_VIEW.contains(":pq"), "原模板不含 :pq ……");
```

这是**对测试数据自身的断言**，`applyPendingRewrite` 的门槛逻辑**一次都没被执行**。它永远不会因为门槛被改坏而变红 —— 无法履行注释里声称的「锁死那条门槛的语义边界」。
**建议**：改为真正调用带 `quotationId=null` 上下文的执行路径并断言 SQL 逐字不变。**这是我的建议，不是我改的**（`src/test` 的 B-3 是后端产出）。

### 4.4 AC-5 的 md5 基线口径必须前后同脚本

我的基线（`BL-5`）与 B-4 脚本（`S-2`）对 `CUST-0001` 算出的 md5 不同（`6c748768…` vs `29a224fa…`），因为两者拼接的列不同。**都有效，但比对时必须用同一个脚本跑前后两次**，不能交叉比。

---

## 5. 我**没做到 / 未验证**的（如实列出）

| AC | 为什么没做到 | 该谁做 |
|---|---|---|
| **AC-1**（UI 白银行显示 `12345.0000` + F12 响应片段） | 需 dev server + dev 库，而 **V397 未应用到 dev**（未合并）；我不得写 dev 库 | **主线合并后亲验** |
| **AC-3**（该单 1713 行全部非空） | `snapshot_rows` 是**已持久化**的渲染结果，不重新渲染不会变。改动前基线我已存（`1713 | 0 | 1713`），改动后需重开单据触发渲染 | **主线亲验** |
| **AC-12 的 UI 半边**（不得出现「加载中…」） | 同上，需渲染 | **主线亲验** |
| **AC-13 的「整单渲染耗时 < 20% 劣化」** | 同上。我只给了函数层 +7% 的代理测量 | **主线亲验** |
| **AC-4 / AC-5 / AC-6 的 UI 路径** | dev 库无核价单、无这两个客户的报价单、无冻结单（§4.2） | **主线裁决** |
| **冷启动验证**（`testing.md` §5） | 未做。我复用了 worktree 既有依赖，没有从零 clone 跑一遍 | 闸门 B 前补 |

🚫 以上各项我**没有**写成「应该没问题」。**未验证就是未验证。**

**给主线的合并后复核命令**（dev 库，先合并使 V397 生效）：

```bash
# AC-2
psql -h 10.177.152.12 -U postgres -d cpq_db_0724 -c \
"SELECT * FROM f_material_element_price('CUST-0004','2026-08-30','bbcb566f-4600-4fb0-9f7c-1154cf566d66'::uuid)
 WHERE material_no='202601010001' AND element_code='白银';"   -- 期望 1 行 / 12345.0000

# AC-3（需先重开单据触发渲染）—— 期望 1713 | 1713 | 0
# AC-4/5/7 零回归：用同一份 验证脚本.sql 再跑一次，与 证据/B-4_验证脚本_dev库只读执行.txt 逐行比对
psql -h 10.177.152.12 -U postgres -d cpq_db_0724 -f 验证脚本.sql
```

---

## 6. 共享库全局状态：动了什么、还原了没有

`testing.md` §4.3 要求登记。**dev 库：零改动。** 测试库 `cpq_db`：

| 对象 | 动作 | 还原 |
|---|---|---|
| `element_price_source` `ZZT260830-SRC` | INSERT 1 | 已删 |
| `element` `ZZT260830-AG` | INSERT 1 | 已删 |
| `element_daily_price` `ZZT260830-AG` | INSERT 1 | 已删 |
| `element_price_strategy` `ZZT260830` | INSERT 1 | 已删 |
| `element_bom_item` / `material_bom_item` `ZZT260830` | INSERT 5 + 5 | 已删 |
| `ZZT-ONLY-Q1` 转正 UPDATE（AC-9 步骤②） | UPDATE 1+1 | 已在步骤⑤还原，最终随夹具删除 |
| 探针函数 `zz_probe_r1_fmep` / `zz_probe_r3_fmep` | CREATE 2 | 已 DROP |
| `V397` 迁移 | 由 Flyway `migrate-at-start` 应用（正常机制，非我手工 `psql -f`） | 保留（这是被测对象） |

**残留自检（全 0）**：

```
 probes | ebi | mbi | el | edp | eps | src
--------+-----+-----+----+-----+-----+-----
      0 |   0 |   0 |  0 |   0 |   0 |   0
```

**生产函数未受影响**：`f_customer_element_price(text,date)` / `f_material_element_price(text,date)` / `f_material_element_price(text,date,uuid)` 三个签名齐全。

🚫 **未跑任何会清库的测试。** 每次 DELETE 前均先 `SELECT count(*)` 量化（§3.2 三步前置）。

---

## 7. A/B 归因：2 条失败全部为 pre-existing

`./mvnw test -Dtest='com.cpq.datasource.sqlview.*Test'` → **84 条，2 失败**，均在 `QuotePendingScopeOpenWhitelistTest`：

- `openCallSites_fileLevelWhitelist_exactMatch` —— 实际命中多出 `QuotationService.java`
- `cardSnapshotService_quoteSideMethods_containOpenCall` —— `ensureExcelValues` 内未找到 `open(`

**归因证据（不是猜的）**：该用例是**纯源码文本扫描**，其输入文件在本分支与 master **逐字相同**：

```
与 master 相同   QuotePendingScopeOpenWhitelistTest.java
与 master 相同   QuotationService.java
与 master 相同   CardSnapshotService.java
git show master:…/QuotationService.java | grep -c "QuotePendingScope.open("  →  1   （master 上就有）
```

⇒ 输入相同 ⇒ 输出必然相同 ⇒ **在 master 上同样失败，与本次改动无关**。
本分支改动仅 3 个文件：`QuotePendingRewriter.java` / `QuotePendingRewriterTest.java` / `V397__*.sql`。

> 这 2 条 pre-existing 失败**本身是个信号**（有人在白名单外开了 pending 可见域，或 `ensureExcelValues` 接线丢了），但**不属本次范围**，建议主线另行登记。

---

## 8. 过程中规避 / 踩到的坑

| # | 坑 | 后果若不察觉 |
|---|---|---|
| 1 | **默认 profile 会把 V397 自动写进 dev 库**（`migrate-at-start`） | 违反「dev 只读」红线 + 污染用户在用的库。已用 `-Dquarkus.profile=test` + 日志核验规避 |
| 2 | **`psql -tAc` 下 `\timing` 不输出** | 我第一次性能测量**全程空输出**，形态与「全部通过」一模一样（`testing.md` §4.4 原话）。改用 `-c "\timing on" -c "<sql>"` |
| 3 | **`count(*) … AS notnull / isnull` 被 PG 解析成 `IS NOT NULL` 运算符**，返回 `t/f` 而非计数 | 会得到一张看起来正常、实则语义完全不同的结果表。已改别名为 `n_priced` / `n_empty` |
| 4 | **md5 `d41d8cd98f00b204e9800998ecf8427e` 是空串的 md5** | 5/6 个客户的「零回归 md5 一致」其实是空集比空集，是假绿。已推翻重做（§2.1 T-4/T-5） |
| 5 | **8097 端口已被别人的实例占用**（pid 2477625，非我起的） | 探活会探到别人的服务，把断言打在别人的库上（`testing.md` §4.2）。改用 8123 并从日志验明正身 |
| 6 | **`pkill -f "quarkus.http.port=8123"` 把我自己的 shell 也杀了**（命令行含同一字符串），命令返回 144 | 会被误读成「测试失败」。已确认 8081/5174 未受影响 |
| 7 | **杀掉 `quarkus:dev` 后整包测试爆 `NoClassDefFoundError`**（`AcquireLocksResult` 等无关类），一条 `ExceptionInInitializerError` 污染共享 Quarkus 上下文 → 后续 `@QuarkusTest` 全被 Skipped | 极易被误判为「本次改动引入大面积回归」。实为 live-coding 中断导致的类加载器索引失效，`clean test-compile` 后 84 条只剩 2 条 pre-existing 失败 |

> 坑 7 尤其值得记：**失败形态是「大面积、跨模块、与改动无关的类找不到」时，先查测试基础设施，不要查业务代码**（`testing.md` §4）。

---

## 9. 交付物

| 文件 | 内容 |
|---|---|
| `test-report.md` | 本文件 |
| `证据/BL-*.txt` | 改动前 dev 基线（AC-3 白银 1713/0/1713、AC-5 老客户 md5、AC-7 两参版 md5、AC-4 核价候选 16、AC-13 性能、元素建档情况） |
| `证据/PRE-FIX_测试库夹具_两参版行为.txt` | V397 落库前的夹具行为（R-1 天然对照） |
| `证据/POSTFIX_T2-T7-T8_测试库.txt` | AC-2 / AC-7 / AC-8 |
| `证据/T-9_AC9_转正序列.txt` | AC-9 中间态 + 最终态 |
| `证据/R-1_R-3_证伪实验.txt` | R-1 / R-3 变红实录 |
| `证据/T-4-T-5_零回归_非重言式.txt` | AC-4 / AC-5 非重言式断言 |
| `证据/T-11_AC11_启动校验.txt` | Flyway + 401 + 启动期校验 |
| `证据/T-13_AC13_性能代理.txt` | 性能 A/B |
| `证据/B-4_验证脚本_dev库只读执行.txt` | B-4 脚本在 dev 库的改动前基线输出 |
