# 子代理回报 · 测试片 S-B **重跑**（读取侧与判据）

> **落盘时间**：2026-09-10 18:20（本地）
> **认领**：AC-5 / AC-6 / AC-7 / AC-8 / AC-9 / AC-21 / AC-22　**造数前缀**：`T260910B-`
> **上一轮读数已作废**（锚定在途未提交代码 + AC-5/AC-8/AC-22 前置被 D-16/D-17/B-6 改过）。本文件是在**当前代码**上重取的读数。

## 0. 结论

**12/12 全绿，两次独立运行同结果。**

| 片内用例 | 认领 AC | 判定 | 与上一轮相比 |
|---|---|---|---|
| `TC-B1`（+ 内置证伪实验） | AC-5 | ✅ | 🔴 **锚点换成 `S0013`@`CUST-0004`（D-16）**，不再需要上一轮的「自洽读法让步」 |
| `TC-B2` / `TC-B2b` | AC-6 | ✅ | 读数一致 |
| `TC-B3` / `TC-B3b` / 🆕 `TC-B3c` | AC-7 | ✅ | 🆕 增加**现网只读**反向样本 `T260907M-AC17ANCHOR` |
| `TC-B4` | AC-8 | ✅ | 🔴 **按 D-17 去掉「4 材质」场景**，补粒度事实实测 |
| `TC-B5a/b/c` | AC-9 | ✅ | 读数一致 |
| `TC-B6`（+ 🆕 反方向） | AC-21 | ✅ | 🔴 **上一轮是红（HTTP 500）⇒ 本轮绿**；🆕 补「不该复用时不误复用」 |
| `TC-B7` | AC-22 | ✅ | 🔴 上一轮 500 导致**断言一条都没跑**；本轮全部执行，且契约裁剪改成**按键存在判**（见 §5） |

**上一轮的阻塞缺陷已消失**：`buildReusedProductInfo` 查 `v_compat_material_master`（该视图在 `cpq_db_test` 不存在 ⇒ 复用路径必现 500）。本轮同一路径 **HTTP 200**，且响应带出 `materials`/`firstCreatedAt`（见 §5-3）。

---

## 1. 代码与环境锚定（读数只对这份状态有效）

| 项 | 值（实取） |
|---|---|
| worktree | `/home/joii/project/cpq/.claude/worktrees/task-260910-sel-ds-tables`，分支 `feat/task-260910-sel-ds-tables`，`HEAD=8ef9eba8` |
| 在途实现改动 | `git diff --stat` = **10 文件 / +898 −285**（含 `ConfigureSearchResource` +163、`ConfigureProductService` +692/−…、`CostingTreeGrouping` ±55、新增 `DsRecordCardDeduper`）。🚫 未读内容，仅取 stat + md5 锚定 |
| 实现文件 md5 | `ConfigureSearchResource.java` `f5c015e4…` · `LookupFingerprintResponse.java` `60ec4fcf…` · `ConfigureProductService.java` `2d0b798d…` |
| **实连库** | **`cpq_db_test`** —— 双重实确认：① Flyway 启动日志 `Database: jdbc:postgresql://10.177.152.12:5432/cpq_db_test (PostgreSQL 16.13)`、`Current version of schema "public": 439`；② `psql \conninfo` → `Database\|cpq_db_test`；③ 交叉核对 `SELECT count(*) FROM quotation`：**`cpq_db_test`=134**（与派工单给的基线逐字一致）、`cpq_db_0724`=**161**（派工单写 156 ⇒ 期间别处新建了 5 单，仅记录漂移，不影响本片） |
| 端口 | 临时 `8141`（🚫 未占 8081/5174/8091/8099） |
| 隔离方式 | 见 §6 |

---

## 2. 逐条 AC —— 实际请求与实际响应/SQL 返回

### AC-5（单点 · 已有零件能搜到新表料号）🔴 D-16 换锚点

**请求**：`GET /api/cpq/quotations/configure/search-parts?customerNo=CUST-0004&q=S0013`

**实际响应（逐字）**：
```json
[{"materials":[{"recipeName":"AgNi11#-Ⅰ","recipeSymbol":"AgNi11#-Ⅰ","recipeType":"locked","recipeSpec":null,"recipeCode":"992","ratio":"55.000000000000"},
               {"recipeName":"AgCu70","recipeSymbol":"AgCu70","recipeType":"locked","recipeSpec":null,"recipeCode":"00017","ratio":"45.000000000000"}],
  "hfPartNo":"S0013","specification":"Φ20","sizeInfo":"20×15×5","partName":"导电排C","statusCode":"Y"}]
```
⇒ **1 条、含 `S0013`**，满足「返回 ≥1 条且含 `S0013`」。
📌 顺带可见 `api.md §2.1` 的 `materials[]` 多值形态成立；`unitWeight` 这条数据为空未出现在 JSON 里（本片不断言它，它属「D-2 裁定保留、不在变更面内」）。

**前置 SQL 返回**：
`S0013@CUST-0004` 在 `ds_quote_material` = **1 行**（`material_name=导电排C`）。

### AC-5 的可证伪证据（派工单点名要）

| 证据 | 实际值 |
|---|---|
| `SELECT count(*) FROM material_master WHERE material_no='S0013'` | **0**（`cpq_db_test`），**0**（`cpq_db_0724`，独立复核） |
| `CUST-0004` 下料号在老表的命中率 | `ds_quote_material` 有 **2663** 个 distinct 料号，其中在 `material_master` 也存在的 = **0**（D-16 写 2672，是 dev 库口径；test 库 2663，**结论一致：全体 0 命中**） |
| 🧪 **同次运行内的证伪实验**（新加） | 换一个库里不存在的关键词 `T260910B-GHOST-<run>` ⇒ 实际 **0 条 `[]`**；并用 `assertThrows` 证明非空守卫**确实硬失败**：`证伪实验·空结果守卫：结果为空列表（0 行）… ==> expected: <false> but was: <true>` |

⇒ 三条合起来：**老 SQL 必然 0 命中**（老表没这个料号）+ **端点不是对任意关键词都返数据**（幽灵关键词 0 条）+ **非空守卫真的会红**。AC-5 不是恒真断言。

### AC-6（边界 · 客户维度隔离，阳性可证伪）

**请求**：`GET …/outsourced-parts?customerNo=CUST-0001&page=1&size=200` 与 `customerNo=CUST-0004`

**实际返回**：
```
CUST-0001 total=7 items=[S0003, S0007, S0011, S0014, T260907-M2, T260910B-OUTC1, T260910B-OUTDUAL]
CUST-0004 total=6 items=[S0003, S0007, S0011, S0014, T260907-M2, T260910B-OUTDUAL]
total 自洽：CUST-0001 api=7 db=7 ; CUST-0004 api=6 db=6
```
- ① **同料号不出双份**：自造的 `T260910B-OUTDUAL`（同时挂两客户）在两侧**各出现 1 次**；本片全部料号两侧均无重复。
- ② `total` 与「该客户在 `ds_quote_material` 的外购件行数」逐数字相等。
  🚫 **没有断言现网的「5」**（`test.md §2` 唯一例外条也要求收窄）：**实测就是被自己的 2 个对照料号顶到了 7 / 6** —— 直接断言 5 会被自己打红。
- ③ **阴性对照**：`CUST-0004` 侧**不含** `T260910B-OUTC1`；阳性反证：它在 `CUST-0001` 侧**在**。
- 🧪 **FT-2 反事实**（实现不可改，故在同一数据源上做）：`T260910B-OUTDUAL 不带客户谓词=2 行；带 CUST-0001=1 行；带 CUST-0004=1 行` ⇒「每客户各 1 条」**不是恒真**。
- 契约守卫：不带 `customerNo` → **HTTP 400** `{"message":"外购件候选必须携带客户编号(customerNo)","data":{"code":"CUSTOMER_NO_REQUIRED"}}`。

### AC-7（单点 · 已有零件搜索也按客户过滤）

| 请求 | 实际 |
|---|---|
| `search-parts?customerNo=CUST-0001&q=T260910B-C1ONLY`（阳性①） | `[T260910B-C1ONLY]` |
| `search-parts?customerNo=CUST-0004&q=T260910B-MULTI`（阳性②，证明该客户视角的搜索是活的） | `[T260910B-MULTI]` |
| **`search-parts?customerNo=CUST-0004&q=T260910B-C1ONLY`（主断言）** | **0 条 `[]`** |
| 🆕 `TC-B3c` 现网只读样本：该料号在 `ds_quote_material` 的客户 = `[CUST-0001]`；`CUST-0001` 搜 → `[T260907M-AC17ANCHOR]`；**`CUST-0004` 搜 → 0 条 `[]`** | ✅（D-16：该锚点逐字不动，实测仍只挂 `CUST-0001`） |
| 契约守卫：`search-parts` 不带 `customerNo` | **HTTP 400** `已有零件搜索必须携带客户编号(customerNo)` / `CUSTOMER_NO_REQUIRED` |

### AC-8（单点 · 多材质全部带出）🔴 D-17 删前置数字

- **自造被测对象** `T260910B-MULTI`@`CUST-0004`（N=2）：返回材质 code 集合 = **`[00006, 00168]`**，🚫 不是 1 个。
  （该料号刻意有第 3 行 BOM：投入料号 `T260910B-NOTARECIPE` 声明 `RECIPE` 但 JOIN 落空 ⇒ 它**没有**被带出，见 AC-9 加固）
- **现网交叉验证锚点**（D-17 点名）：`(CUST-0004, S0013)` 期望 = `[992, 00017]`，**实际 = `[992, 00017]`**；并加了前提硬断言「该锚点必须恰好 2 个材质」，🚫 不许顺着实际值改期望。
- 🔴 **「4 材质」场景已按 D-17 从用例里去掉**，并留下可核数字：
  `SELECT n, count(*) FROM (SELECT customer_no, material_no, count(*) n … JOIN material_recipe … GROUP BY 1,2) GROUP BY n` ⇒
  **`1 材质→19 个; 2 材质→6 个`**（6 = 现网 5 + 本片自造 1，与 D-17 的「2 材质→5 个」吻合）。
  ⇒ **`4 材质` 在 `(customer_no, material_no)` 粒度下确实为 0 个**，原前置数字若不删会写出永远造不出来的用例。

### AC-9（边界 · 判据是 JOIN 命中，不是 `output_material_type`）

| 方向 | 前提（SQL 实测） | 请求 → 实际 |
|---|---|---|
| ① 声明 `成品` 但 JOIN 落空 ⇒ 材质必须**空** | `PERF0909-B00001@CUST-0004`：BOM 1 行 / JOIN 命中 **0** / `omt=成品` / `input=T260907T-RM01` | 阳性对照「料号本身搜得到」→ `[PERF0909-B00001]`；材质集合 = **`[]`** |
| ② 声明 `NULL`/`零件` 但 JOIN 命中 ⇒ 材质必须**带出**（逐行验，不许只验一行） | 符合条件的行 **4 行**（现网 3 + 自造 1） | `S0002@CUST-0001 omt=(NULL) input=992 → [992]`；`S0002@CUST-0004 → [992]`；`T260907-M1@CUST-0004 omt=零件 input=00006 → [00006]`；`T260910B-MULTI@CUST-0004 → [00006, 00168]` ⇒ **4/4 全带出** |
| ③ 加固：声明 `RECIPE` 但 JOIN 落空 ⇒ 🚫 不许带出 | `S-2120011659@CUST-0001`：声明 `RECIPE` **4 行**，JOIN 命中 **2 行** | 期望 `[00006, 00168]`（2 个）实际 **`[00006, 00168]`（2 个）** ⇒ 两行僵尸（`3110520789`/`3112230067`）没被当材质 |

### AC-21（回归 · 指纹复用不受影响）—— 上一轮红，本轮绿

私有客户 `T2610B…`（本片自建，🚫 不在 `CUST-0001/0004` 下提交）。

1. **第一次提交**（阳性对照：**不该**命中）→ HTTP 200
   `{"lineItems":[{"productPartNo":"1090-2609000001",…,"processNos":["Z100","Z101"]}],"fingerprintMatched":false,"reusedHfPartNos":[],…}`
   落库：`ds_quote_material` 1 行 / `ds_quote_customer_part` 1 行。
2. **第二次提交（输入逐字相同，只换客户产品编号）**→ HTTP 200（**上一轮此处 500**）
   `"fingerprintMatched":true`、`"reusedHfPartNos":["1090-2609000001"]`
   `reusedProductInfo` = `{"hfPartNo":"1090-2609000001","partName":"T260910B-零件A","specification":"A13","dimension":"2343","unitWeight":"11","materials":[{"recipeCode":"00006","name":"AgNi10","ratio":"100"}],"firstCreatedAt":"2026-09-11T01:16:54.455901Z","lastQuotedPrice":null}`
   落库：该料号仍 **1 行**（不重复铸号）；`ds_quote_customer_part` **2 行**且都指向同一销售料号 —— `[T260910B-PROD-A-… → 1090-2609000001, T260910B-PROD-B-… → 1090-2609000001]`（一料号多编号，与改动前一致）。
3. 🆕 **反方向（不该复用时不许误复用）**：只把总重 `11→12` ⇒ `matched=false`、铸出**新**料号 `1090-2609000002`，本客户 `ds_quote_material` **2 行**。
   🔑 这一枪是针对派工单点名的「指纹复用假绿」：`fingerprintMatched=true` 时一个新料号都没铸，只查「有行」会被存量骗过 ⇒ **两个方向都有正向证据**。

### AC-22（回归 · 换序仍命中 + 确认页提示已有顺序）🔴 B-6 契约裁剪已落地

- **前置落库对照（数据源切换的实证）**：`ds_quote_self_process_fee=[Z100, Z101]` / `unit_price(自制加工费)=[]`
  ⇒ 本轮把前置从「两表任一非空即可」**收紧成「新表必须非空」**：新表空而老表有行 = 数据源没切，那时「顺序正确」是走老表得出的，不算 AC-22 达成。
- **① 换序仍命中**：以 `[Z101, Z100]` 提交 → `matched=true`、`reused=[1091-2609000001]`。
- **② 确认页显示真实落库顺序**：`POST /configure-product/lookup-fingerprint` 实际响应（逐字）：
  ```json
  {"matched":true,"hfPartNo":"1091-2609000001","matchedPartNo":"1091-2609000001",
   "snapshot":{"processes":[{"seqNo":1,"processCode":"Z100"},{"seqNo":2,"processCode":"Z101"}]}}
  ```
  `snapshot.processes.processCode = [Z100, Z101]` = **已有产品的真实顺序**，不是本次输入的换序。

### AC-22 的字段裁剪确认（派工单第 4 项）

`snapshot` **键集合实测 = `[processes]`** ⇒
- **不含** `unitWeightGrams` ✅
- **不含** `compositeProcesses` ✅
- `processes` 存在且**非空**（2 个元素，见上） ✅

🔑 **判法已改成「键是否存在」，不是「值是否为 null」**：`jsonPath().get()` 对「字段没删但值是 null」和「字段删了」都返 null ⇒ 上一轮那种写法会把「没删」读成「删了」。上一轮这段断言压根没执行（500 抛在前面），所以这是**首次真实执行**。

---

## 3. 与上一轮读数的 diff（哪些变了、为什么）

| 项 | 上一轮 | 本轮 | 变的原因 |
|---|---|---|---|
| **AC-5 锚点** | `T260907M-AC17ANCHOR`，且**以它所属的 `CUST-0001`** 发起搜索（AC 原文写 `CUST-0004`，按字面会与 AC-7 互斥，只能取自洽读法） | **`S0013`@`CUST-0004`**，前置客户与搜索客户一致 | **D-16 裁决换锚点**。互斥解除，不再需要让步读法 |
| **AC-5 可证伪证据** | `material_master=0 行` 一条 | 3 条：老表 0 行（两库复核）+ 该客户 2663 料号在老表 0 命中 + 同次运行内幽灵关键词 0 条且守卫硬失败 | 派工单点名要 `count(*)` 证据；顺带补上「端点不是对任意 q 都返数据」这一层 |
| **AC-7 样本** | 只有自造 `T260910B-C1ONLY` | 自造 + 🆕 现网 `T260907M-AC17ANCHOR` | D-16 明确该锚点转为 AC-7 方向；现网样本不依赖造数链路，造数静默失效时仍有证据 |
| **AC-8 前置** | 报告里逐条驳了 AC 原文的「2 材质→7 个 / 4 材质→2 个」 | 原文数字已删，用例改用自造 N=2 + 现网锚点 `(CUST-0004,S0013)`，并留下粒度分布实测 | **D-17 采纳了上一轮的驳正**。本轮补的分布数字 = `1材质→19 / 2材质→6`（含自造 1） |
| **AC-21** | 🔴 **红**：第二次提交 HTTP 500，`ERROR: relation "v_compat_material_master" does not exist`，栈顶 `ConfigureProductService.buildReusedProductInfo` | ✅ **绿**：200，`fingerprintMatched=true`，`reusedProductInfo` 完整 | 实现侧已换源（我未读实现，只能从行为判断）。**顺带坐实两件在途裁决**：C-2「复用时材质返空」缺口消失（`materials:[{00006,AgNi10,100}]` 非空）、**D-25 的 `firstCreatedAt` 恒 NULL 已修**（本轮为真实时间戳 `2026-09-11T01:16:54.455901Z`）—— D-25 要求的「修后真实时间戳」对照证据在此 |
| **AC-22** | 🔴 **红且未验证**：500 抛在第一条业务断言之前，顺序断言与两个删字段断言**一次都没执行** | ✅ 全部执行 | 同上 |
| **AC-21 覆盖面** | 只有「该复用时复用」 | 🆕 加「不该复用时不误复用」（改总重 ⇒ 不命中 + 铸新号 + 2 行） | 派工单点名的指纹复用假绿 |
| **AC-22 前置强度** | 「两表任一非空即可」，可能走老表得出正确顺序 | 「**新表**必须非空且顺序正确」 | `api.md §2.4` 的数据源切换本身就是被验对象 |
| AC-6 / AC-9 读数 | 同 | 同（`total` 7/6、FT-2 反事实 2/1/1、AC-9② 4 行全带出、S-2120011659 期望=实际=2 个） | 无变化，这几条与本轮 5 轮代码改动无交集 |

---

## 4. 🧪 证伪实验执行情况（`testing.md §4.4`）

| 实验 | 状态 | 证据 |
|---|---|---|
| **AC-5 新加断言的证伪**（本轮新增） | ✅ **已执行** | 幽灵关键词 → 0 条；`assertThrows` 捕到守卫的硬失败原文 |
| **FT-2（AC-6 客户隔离）可执行那半** | ✅ 已执行 | 不带客户谓词 2 行 / 带客户各 1 行 |
| **FT-2 改实现那半** | 🚫 **未执行（无权改实现）** | 步骤：注掉外购件候选 SQL 的 `AND customer_no = :customerNo` → 确认重编译真的发生 → 重跑 `Ac567ReadSideCustomerScopeTest` → `tcB2_ac6_outsourcedCustomerIsolation` **必须变红**（`OUT_DUAL` 出 2 行）。**不变红 = 断言没接上** |
| **AC-21 反方向** | ✅ 已执行 | 改总重 ⇒ `matched=false` + 新料号 |
| **AC-22 键存在判** | ✅ 已执行 | 同一 `containsKey` 谓词对存在的 `processes` 返 true、对两个被删键返 false ⇒ 谓词是活的 |

**重复次数**：本轮三个测试类**连续两次独立运行全绿（12/12 × 2）**，另有一次因端口被别的 worktree 抢占而 boot 失败（见 §6），该次不计入读数。

---

## 5. 需要主线知道的三件事（非本片 AC，但是本轮实测顺带抓到的）

1. **上一轮的阻塞缺陷已闭合**，但我**没有读实现**，只能从行为断言：复用路径不再 500、`reusedProductInfo` 四列（品名/规格/尺寸/单重）齐全 ⇒ 看起来已从 `v_compat_material_master` 换源。**是否换到了 `ds_quote_material` 并带 `customer_no` 维度（D-2），本片无法证明** —— 标为**未验证**，建议主线亲验时点一下。
2. **D-25 的证据在这里**：`firstCreatedAt` 本轮返回 `2026-09-11T01:16:54.455901Z`（上一轮该字段按 D-25 描述恒 NULL）。D-25 要求「修前 null / 修后真实时间戳」的对照证据 —— **修后那半由本片提供**。
3. **两个 `materials[]` 形状不一致**（🚫 不构成任一 AC 违反，只是登记）：
   `search-parts` 的元素是 `{recipeCode, recipeSymbol, recipeName, recipeSpec, recipeType, ratio}`（`api.md §2.1`）；
   `reusedProductInfo.materials` 的元素是 `{recipeCode, name, ratio}`。前端若复用同一渲染组件会读不到 `recipeSymbol`。

---

## 6. 隔离方式与 target 假故障

- ✅ **用的是派工单指定的唯一有效办法**：`rsync -a --exclude target/ --exclude node_modules/ --exclude .git/` 把 `cpq-backend/` 整份拷到 scratchpad 副本
  `/tmp/claude-1000/…/scratchpad/iso-sb-rerun/cpq-backend`，**全部 maven 在副本里跑，worktree 里一次都没跑**。
  🚫 未使用 `-Dmaven.build.dir=`（派工单已实证在本项目被静默忽略）。
- **收尾核对**：副本与 worktree 原文的 4 个测试文件 **md5 逐字节一致**
  （`SbBase.java 12178b32…` / `Ac567… 0756fae0…` / `Ac89… cbb7a6ad…` / `Ac2122… ea46a97f…`）；
  worktree 的 `cpq-backend/target/` mtime 停在 **17:42**（我 18:10 才开工）⇒ **没碰过它**。
- **target 假故障：0 次。**
- ⚠️ **但撞到一次端口抢占**（形态不同、同样长得像代码坏了）：第二次运行 `Port already bound: 8123`，`Tests run: 2 … Errors: 1, Skipped: 11`、`BUILD FAILURE`。
  查明占用者是**另一个 worktree** 的 dev server（`pid 26841`，`/home/joii/project/cpq/.claude/worktrees/repair-260910-cust-product-no/…`）。
  🚫 **没有杀它**，换端口 `8141` 重跑即全绿。⇒ 登记给主线：**临时端口也要按 worktree 分段，8123 已被 repair-260910 占用**。

---

## 7. 待回收清单（🚫 本片未删，呈报主线）

**仅 `cpq_db_test` 一处，6 行孤儿发号计数器**（本片自建的临时客户已全部删净，但发号器计数行不带 `customer_no`，清理脚本圈不住）：

```sql
-- 影响面已量化（只读先跑过）：6 行，customer_code 均为本片临时客户的四位码
SELECT customer_code, year_month, last_serial
  FROM quote_material_no_seq
 WHERE customer_code IN ('1086','1087','1088','1089','1090','1091');
--  1086/2609/1 · 1087/2609/1 · 1088/2609/1 · 1089/2609/1 · 1090/2609/2 · 1091/2609/1
```

- **可恢复性**：删除后如需恢复，重新发号会自动重建同形态行（计数器语义，无业务数据）。
- **不删的后果**：这 6 个四位码不会再被分配给真实客户（`quote_customer_code` 侧已清净，四位码本身仍可被占用逻辑复用）⇒ 影响**轻微**，可以不删。
- 🚫 **本片不执行任何 DELETE**（`CLAUDE.md §3.2` + 派工单）。
- **一次性库**：无（本片全程用共享 `cpq_db_test`，未建库、未 DROP、未 TRUNCATE、无无 WHERE 删除）。

**残留自证（两库逐项 0 行）**：`ds_quote_material` / `ds_quote_material_bom` / `customer` / `quotation` / `ds_quote_customer_part` / `ds_quote_self_process_fee` / `material_customer_map` / `quote_customer_code` 中 `T260910B-%` 与 `T2610B%` 前缀 **全为 0**（`cpq_db_test` 与 `cpq_db_0724` 分别查过）。

---

## 8. 「未验证」标注（🚫 不写「应该没问题」）

1. **换源目标是否真是 `ds_quote_material` + `customer_no` 维度** —— **未验证**（只观测到「不再 500 且四列齐全」，没读实现，也没有 AC 覆盖「复用信息按客户隔离」这件事）。
2. **前端 UI** —— **未验证**。本片是接口层 + DB 层；`ExistingPartPanel` 的多材质展示形态（`api.md` R-2：`materials[]` vs `materialsLabel`）只在解析层做了双形态兼容 + 对旧单值形态点名报错，**没走过界面**。
3. **`cpq_db_0724`（默认 profile）上的同一组读数** —— **未验证**。本轮全部跑在 `cpq_db_test`。两库基线不同（派工单 §6 已登记：`v_compat_*` 3 张 vs 0 张、`component_sql_view` 99 vs 42 段、`template_component` 146 vs 78 行）⇒ **若主线亲验（默认 profile → `cpq_db_0724`）与本报告分歧，先查这张表，🚫 不要归因成「测试环境问题」** —— 本任务已有先例：上一轮那个 500 只在 test 库炸、在 dev 库被存活的兼容视图掩盖，**那次测试库才是对的**。
4. **`ds_quote_self_process_fee` / 外购件候选的分页行为** —— 未验证（`api.md §2.2` 明确本期不接分页，候选超 20 时选不到第 21 个，已登记 BACKLOG）。

---

## 9. 产出文件（绝对路径）

**测试代码**（worktree 内，🚫 未 `git commit`）：
- `/home/joii/project/cpq/.claude/worktrees/task-260910-sel-ds-tables/cpq-backend/src/test/java/com/cpq/task260910b/SbBase.java`
- `…/task260910b/Ac567ReadSideCustomerScopeTest.java`（AC-5 · AC-6 · AC-7 + 🆕 `TC-B3c` + 2 条契约守卫 + AC-5 证伪实验）
- `…/task260910b/Ac89MaterialPredicateTest.java`（AC-8 · AC-9 + AC-9 加固 + D-17 粒度事实）
- `…/task260910b/Ac2122FingerprintRegressionTest.java`（AC-21 + 🆕 反方向 · AC-22 + 键存在判）

**证据归档**（🚫 不留在 `target/`，已复制进任务目录）：
- `/home/joii/project/cpq/.claude/worktrees/task-260910-sel-ds-tables/dev-docs/task-260910-选配切ds新表与已有料号绑定/证据/S-B-重跑/`
  - `TEST-com.cpq.task260910b.*.xml`（3 份 surefire，含全部 stdout）
  - `mvn-run1.log`（第一次全绿）· `mvn-run4-final.log`（最终全绿）· `stdout-断言实际值-run4.txt`

**自检声明**：`./mvnw -o test`（scratchpad 隔离副本，`DB_NAME=cpq_db_test`，端口 8141）→ `Tests run: 12, Failures: 0, Errors: 0, Skipped: 0` / `BUILD SUCCESS` ✅（连续两次）；连库经 Flyway 日志 + `\conninfo` + `quotation` 计数三重实确认 = `cpq_db_test` ✅；未占 8081/5174 ✅；两库残留前缀 0 行 ✅；worktree `target/` 未被触碰（mtime 17:42）✅。🚫 未执行 `git commit`，🚫 未跑任何 DROP/TRUNCATE/清库，🚫 未 `pkill`。
