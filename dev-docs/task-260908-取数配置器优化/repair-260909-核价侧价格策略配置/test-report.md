# 测试报告 · repair-260909 核价侧价格策略配置

> 执行人：后端工程师（子代理） ｜ 日期：2026-09-09
> 分支 `fix/repair-260909-costing-price` ｜ 库 `10.177.152.12:5432/cpq_db_0724`（共享开发库）
> 迁移 **V434 已落共享库，`success=t` @ 02:34:26**
> AC 原文出处：`问题说明.md ⑥`（含 2026-09-09 的 AC-B1 拆分与 AC-B2 样本订正）

---

## 0. 契约结论

> **本次无接口契约变更，无需回写 `main-api.md`。**

没有新增/删除端点，没有任何端点的 HTTP 方法 / 路径 / 请求体字段 / 响应体字段 / 错误码发生变化。
两处**值**的变化（非结构变化）：
- `field-tree` 核价两方言的 `groups` 多返回一个 `groupKind='PRICE'` 的组；
- `compile` 核价侧 `requiredVariables` 由 `[total_material_no]` 变为 `[total_material_no, customerCode, priceBaseDate]`（该字段本就是动态集合）。

---

## 1. 逐条 AC 状态

| AC | 状态 | 证据摘要 |
|---|---|---|
| **AC-P1** 核价两方言出「价格策略」组 | ✅ **通过** | 前后两点测量。改动前 `COST_BASIC/COST_DETAIL` 各 1 组、PRICE 组 0 个；改动后各 2 组、**PRICE 组恰好 1 个**、`groupName='价格策略'`。字段 = `unit_price(元素单价, isCore=true)` + `currency(货币)` |
| **AC-P2** 编译产物 JOIN 形状与报价侧同构 | ✅ **通过** | 产出含 `ON cep.element_code = vdcbeba.element_code AND cep.material_no = vdcbeba.sales_material_no`；**产物中搜不到** `cep.material_no = <锚点>.production_no`。`COST_DETAIL` 同构（别名 `vdcdeba`） |
| **AC-P3** 两侧取价逐位相同 | ✅ **通过** | 先各自断言非空（4 个目标各 1 行），再逐字节比对：三侧 Ag 单价均为字符串 `'43358.75'`，`QUOTE==COST_BASIC==COST_DETAIL` 为 True |
| **AC-N1** 非材质元素页签仍无 PRICE 组 | ✅ **通过** | 6 个有效读数（`BOM`/`主件`/`费用类+variantKey` × 两方言）全部 HTTP 200 且 PRICE 组 = 0 |
| **AC-N2** `COMPILE_PRICE_EDGE_NOT_FOUND` 守卫未被削弱 | ✅ **通过** | 守卫三处（`:1387/:1397/:1407`）代码未动；`QUOTE/BOM` 改动前后均返 400 `COMPILE_PRICE_EDGE_NOT_FOUND` |
| **AC-R1** 报价侧编译产物逐字节不变 | ✅ **通过** | 8/8 样本；三步灵敏度实验（基准 → 故意写错列名得 40 行 diff → 改回 diff 为空） |
| **AC-R2** 报价侧仍只出一个价格策略组 | ✅ **通过** | 改动前后 `QUOTE` 均为 2 组 / PRICE 组 1 个，字段与顺序逐字不变 |
| **AC-R3** 既有列名字/顺序/行数不变，新列在末尾 | ✅ **通过** | 列清单 diff 只多两行，均 `ordinal_position=16`（末尾）；既有 15 列逐字不变 |
| **AC-R4** 存量 `sql_template` md5 零变化 | ✅ **通过** | 35 行逐行 md5 `diff` 退出码 0 |
| **AC-R5** `Sec31CompileCorrectnessTest` 墓碑哨兵仍绿 | ✅ **通过** | 3/3，**mtime 02:57:28**（V434 落库 02:34:26 之后的新鲜读数） |
| **AC-B1a** 桥不到 → 单价空、不报错 | ⚠️ **未验证（全库无有效样本）** | 见 §2 |
| **AC-B1b** 渲染层不显示「加载中…」 | ➖ **不归本报告** | 由主线亲验覆盖，🚫 不以 AC-B1a 顶替 |
| **AC-B2** 1→N 时元素BOM行数不翻倍 | ✅ **通过（含构造性证明）** | 聚合行数 15/7 与基线一致；且用纯 `SELECT` 构造性证明 LATERAL 1 行 vs 裸 `LEFT JOIN` 2 行 |
| **AC-S1** 自检证据 | ✅ **通过** | 迁移 `success=t`；后端起得来（业务端点 401）；两张视图可查；前端 `tsc --noEmit` **0 错误** |
| **决策 2** `PriceGroupDuplicationAcTest` 改期望值 + 留碑 | ✅ **通过** | 1/1，**mtime 02:57:36** |
| **B-3 生效独立判据** 错误码翻转 | ✅ **通过** | 核价两方言 `COMPILE_COLUMN_SOURCE_UNKNOWN` → `COMPILE_PRICE_EDGE_NOT_FOUND`；`QUOTE` 不变 |

### 关键证据原文

**AC-P1 / AC-R2 / 错误码翻转（前后两点）**
```
【改动前 · V434 未应用，服务起在 -Dquarkus.flyway.migrate-at-start=false，库仍 433】
QUOTE        HTTP=200 组数=2 PRICE组数=1 字段=[('unit_price','元素单价',True,'元素单价'), ('currency','货币',False,'货币')]
COST_BASIC   HTTP=200 组数=1 PRICE组数=0
COST_DETAIL  HTTP=200 组数=1 PRICE组数=0
QUOTE/BOM        400 COMPILE_PRICE_EDGE_NOT_FOUND
COST_BASIC/BOM   400 COMPILE_COLUMN_SOURCE_UNKNOWN
COST_DETAIL/BOM  400 COMPILE_COLUMN_SOURCE_UNKNOWN

【改动后 · V434 已应用】
QUOTE        HTTP=200 组数=2 PRICE组数=1 字段=[('unit_price','元素单价',True,'元素单价'), ('currency','货币',False,'货币')]   ← 逐字未变
COST_BASIC   HTTP=200 组数=2 PRICE组数=1 groupName='价格策略'
COST_DETAIL  HTTP=200 组数=2 PRICE组数=1 groupName='价格策略'
QUOTE/BOM        400 COMPILE_PRICE_EDGE_NOT_FOUND   ← 未变
COST_BASIC/BOM   400 COMPILE_PRICE_EDGE_NOT_FOUND   ← 翻转
COST_DETAIL/BOM  400 COMPILE_PRICE_EDGE_NOT_FOUND   ← 翻转
```
> 🔑 量具是**双侧**的：报价侧给 True、核价侧给 False。脚本对每次请求**先断言 HTTP 200 再解析**，非 200 打印「本行不算读数」。

**AC-P2**
```
FROM v_ds_cost_basic_element_bom_all vdcbeba
  LEFT JOIN f_material_element_price(:customerCode, :priceBaseDate) cep
         ON cep.element_code = vdcbeba.element_code
        AND cep.material_no  = vdcbeba.sales_material_no
requiredVariables = ['total_material_no', 'customerCode', 'priceBaseDate']
```

**AC-P3（先数样本，再断言）**
```
前置 · 样本在各自断言目标上的行数
  报价侧 ds_quote_element_bom / S-3120014539 / Ag            = 1
  核价侧 v_ds_cost_basic_element_bom_all / 3120014539 / Ag   = 1
  核价侧 v_ds_cost_detail_element_bom_all / 3120014539 / Ag  = 1
  桥 ds_quote_material: S-3120014539 -> 3120014539           = 1

preview 实得（原始字符串，未做任何格式化）
  报价侧   QUOTE       = ['43358.75']
  核价基础 COST_BASIC  = ['43358.75']
  核价明细 COST_DETAIL = ['43358.75']
  ✅ 三侧逐字节相同 = '43358.75'
```
> 该值来自**冻结版本**（`element_price_version_item`），不是实时策略算出来的 —— `CUST-0001` 的策略（`AVG`/1 周窗口）对 `Ag` 算不出值。核价侧走**同一函数**才拿得到同一个数，这正是 `A0-1` 不变量的意义所在。

**AC-R1 三步灵敏度实验**
```
样本数断言：8 个组件（全部 dialect=QUOTE），8/8 采样成功、全部非空、全部含 f_material_element_price
步骤1 基准：… ON cep.element_code = dqeb.element_code AND cep.material_no = dqeb.material_no  （8/8 逐字相同）
步骤2 故意写错列名 __bogus_sensitivity_probe__ → diff 退出码=1，diff 行数=40，8 个文件全部不同 ✅ 量具灵敏
步骤3 改回正确实现 → diff 退出码=0（逐字节一致）
```
> 🚫 采样器对空文件与 `NO_SQL:` 都会显式报 `!! SAMPLE FAILED`，本次未触发 —— 避免了「比较两个空文件说相同」的假绿。

**AC-R3 / AC-B2**
```
列清单 diff（改前 30 行 → 改后 32 行）
  15a16  > v_ds_cost_basic_element_bom_all|16|sales_material_no
  30a32  > v_ds_cost_detail_element_bom_all|16|sales_material_no

行数（基线 basic=15 / detail=7）
   basic  | 改后 15 行 | 桥到 7 | 桥不到 8
   detail | 改后  7 行 | 桥到 7 | 桥不到 0
```

**AC-B2 构造性证明（纯 `SELECT`，不写任何数据）**
```
0·前置：material_master 上 TEST0813-P01-PROD 的销售料号数 = 2   （必须 >1，否则本证明空跑）
1·LEFT JOIN LATERAL … LIMIT 1（V434 采用）                     = 1 行   ← 不翻倍
2·裸 LEFT JOIN（被否决的写法）                                  = 2 行   ← 翻倍 = 数据损坏
```
> 📌 **为什么必须补这个**：`TEST0813-P01-PROD` 在两张元素BOM视图里各 **0 行**，两视图里最大桥接基数 = 1
> ⇒ `15/7 不变` 只证明「本次没发生翻倍」，不证明「LATERAL 挡住了翻倍」。构造性证明把一条**恒真**的断言变成了**能红**的断言。

**焦点测试（mtime 已核，均晚于 V434 落库的 02:34:26）**
```
[mtime 2026-09-09 02:57:28] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- Sec31CompileCorrectnessTest
[mtime 2026-09-09 02:57:30] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 -- Sec34PriceStrategyTest
[mtime 2026-09-09 02:57:36] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- PriceGroupDuplicationAcTest
[mtime 2026-09-09 02:57:37] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0 -- FieldTreeAndDialectParseSelfCheckTest
EXIT=0（隔离运行，无其它进程并发）
```
> 🚨 **这一步差点出假绿**：首次读到的四份报告数字与本轮**逐字节相同**，`stat` 一看 mtime 是 **02:13** ——
> 那是 V434 落库前、我改测试前的**陈旧报告**。已删旧报告后重跑，才得到上面这组新鲜读数。

**Sec31 墓碑哨兵不误触的逐条核对（AC-R5）**

| 断言 | 为什么本次不触发 |
|---|---|
| `lookupOrAuxEdgeIds` | 只筛 `edge_kind IN ('LOOKUP','AUX')`；新边是 `PRICE` |
| `auxSheetIds` | 只筛 `role='AUX' AND node_kind='SHEET'`；新节点是 `FUNCTION` 且**不挂** `semantic_tab_view_node` |
| `v6SourceNodes = 0` | 新节点 `physical_table` 为 NULL |
| `withDiscriminator = 0` | 新节点无 discriminator |
| `twoHopPaths = 0` | 实测 `ELEMENT_BOM` 入边 0 条、`FUNC_ELEMENT_PRICE` 出边 0 条 ⇒ 构不成二跳 |

⇒ **未放宽任何白名单。**

---

## 2. AC-B1a：全库无有效样本 · 未验证

**结论：`COST_BASIC` 与 `COST_DETAIL` 两侧均记「无有效样本 · 未验证」。** 🚫 不打勾。

**为什么样本无效** —— 有效样本需同时满足三条：
① 经 `ds_quote_material` 桥够得到（否则 `preview` 恒 0 行）；② 在元素BOM视图里有行（否则「单价为空」恒真）；③ 桥不到 `material_master`（否则取得到价，不是本 AC 的场景）。

```
穷举 ds_quote_material 全部 4535 个 (销售料号, 生产料号) 候选：
  有效 AC-B1a 样本数 = 0 / 候选总数 = 4535

指名样本实测：
  partNo=300013        HTTP=200 rowCount=0    ← 端点够不到
  partNo=300015        HTTP=200 rowCount=0    ← 端点够不到
  partNo=S-3120014539  HTTP=200 rowCount=7    ← 对照，证明端点本身是通的
```
`300013`/`300015` 在**视图**里确有 6 行 / 2 行，但 `preview` 的 `WHERE` 是
`production_no IN (SELECT dqm.production_no FROM ds_quote_material WHERE dqm.material_no = ANY(:total_material_no))`，
而 `ds_quote_material` 里**没有任何销售料号映射到这两个生产料号** ⇒ 端点层根本够不到。

🚫 **没有为它造夹具** —— 要让端点够到 `300013`，得往 `ds_quote_material` 塞一条假映射，那是**编造业务数据**，比留一条未验证更糟。

**能拿到的最强证据（SQL 层，纯 `SELECT`）** —— 把编译产物原样跑，只把 WHERE 的桥换成直接按 `production_no` 过滤：
```
步骤1_行数必须大于0 = 8      ← 先证明有行
步骤2_单价为空的行数 = 8
步骤2_单价非空的行数 = 0
逐行：300013×6 + 300015×2，sales_material_no 全为空，元素单价全为 NULL，无报错，8 行全部保留（LEFT JOIN 没丢行）
```
⇒ 「桥不到 → 单价为空、不报错、不丢行」在 **SQL 层**成立；**端点层与渲染层未验证**。

---

## 3. 冷启动验证（按 `test.md §5` 退化执行）

**退化理由（不是跳过）**：全量重放会卡在 `V87`（`BL-0220` 已登记），本库无法从零重建 ⇒ 冷启动退化为
「**当前库基线上验证迁移可应用 + 可重复应用**」。

| 检查 | 结果 |
|---|---|
| 迁移可应用 | `version=434 · success=t · checksum=1961540833 · execution_time=193ms` |
| 应用时应用起得来 | 同一次启动内 `全版本视图自检通过：26 张 v_<主表>_all` + `Semantic graph reloaded: nodes=57 edges=78` + 业务端点 401 |
| 幂等 · 语义图段 | 事务内重放 `INSERT … ON CONFLICT DO NOTHING` → `INSERT 0 0`，节点数仍 3，`ROLLBACK` |
| 幂等 · 视图段 | 事务内重放 `CREATE OR REPLACE VIEW` → 成功，行数仍 7，`ROLLBACK` |
| `DO $$` 落库自检 | 未抛异常（nodes=2/edges=2/keys=4/funcCols=4/bridgeCols=2 全部命中） |

⚠️ **未验证**：从空库全量重放 V1→V434 的可行性（受 `BL-0220` 阻断）。

---

## 4. 本次在共享开发库留下的残留（交闸门 B 由用户裁决是否回收）

**成因**：主线指令跑了一次全量 `mvnw test`，而 `CLAUDE.md` 明写 test profile 指向的**就是共享开发库 `cpq_db_0724`**（非独立库）。跑到 413 个测试类时由我主动 `kill`。

| 对象 | 变化 | 当前值 |
|---|---|---|
| 报价单 `quotation` | **+37** | 122 |
| 客户 `customer` | **+8** | 73 |
| 组件 `component` | **+9** | 104 |

**性质：叠加型残留，不是破坏。** 未发生删除；两张核价视图仍 15/7；组件/模板基线仍在。

🚨 **回收命令属 `CLAUDE.md §3.2` 红线（无 `WHERE` 或命中面不明的 `DELETE`）—— 本报告只呈报，不执行、不建议自动执行。**
若要回收，需先用只读手段量化命中面（按 `created_at` 窗口 + 测试夹具命名前缀圈定），再交用户逐条批准。

**V434 本身不产生任何数据残留**：其语句动词普查为 `CREATE OR REPLACE VIEW`×2 / `INSERT INTO`×5（全部 `ON CONFLICT DO NOTHING`）/ `UPDATE`×1（带 `node_key AND dialect='QUOTE'` 精确守卫）/ `DO`×1，**无任何破坏性语句**。

---

## 5. 发现但未处理（不属本次范围，只报不动）

| # | 问题 | 证据 | 判定 |
|---|---|---|---|
| 1 | **`mat_*` 全表缺失** | `information_schema` 查 `mat\_%` → **一张都不存在**；日志中 `relation "mat_..." does not exist` 出现 **235 次** | **部署缺口**。`AP-53` 称其「已废弃」，但代码仍 LIVE 读写（`ChangeLogResourceTest` 等整类 10/10 error）。与 `mat-tables-frozen-since-0602` / `cpq-server-deploy-init-sql` 记录一致 |
| 2 | **广泛 401** | `Expected status code <200> but was <401>` 出现 **113 次**，跨 `CustomerResourceTest`/`ProductResourceTest`/`QuotationResourceTest` 等整类 | 环境性。已排除 admin 账号问题：`status=ACTIVE`、`failed_login_attempts=0`、`locked_until=NULL`、未被锁。根因未定位 |
| 3 | **`SemanticEdgeCardinalityReconcileTest` 基数漂移** | 失败边 = `ds_quote_customer_part.material_no`（**task-260907 的 QUOTE 侧 LOOKUP 边**，非本次新增的 PRICE 边）。`ds_quote_material.production_no` 亦有 15 组重复 | 数据漂移，**与本次改动无关** |
| 4 | **hook 误报** | 一次纯只读 `grep` 因**搜索模式字符串**里含红线关键字而被拦截 | 🚫 未换写法绕过，已上报。建议登记为规则改进 |
| 5 | **AC-B2/AC-B1 的选样问题第 4 次** | 见 §2 | 已由主线写进 `问题说明.md ⑥` 的强制动作 |

### 全量 `mvnw test` 的读数（仅作环境评估，🚫 不作为本次改动的判据）
```
已完成测试类 413 · 其中红 178 类
累计 用例 1778 · Failures 180 · Errors 542（合计红 722）
```
**归因**：`mat_*` 缺失（235 处）+ 广泛 401（113 处）两个环境性根因即可解释绝大部分；
失败横跨 `changelog`/`customer`/`product`/`system`/`importexcel` 等与本次改动**毫无交集**的包。
V434 无破坏性语句，且我全程未手工执行任何 DDL。
⇒ **本次改动不是这批失败的成因**；本任务的判据以 §1 的焦点测试与逐条 AC 证据为准。

---

## 6. N+1 自检

> **N+1 自检：本次 Java 改动 0 处新增循环。唯一被改的 `for (int i = 1; i < keys.size(); i++)`（`SemanticCompiler.java:1444`）是纯字符串拼接，循环体内无 repository 调用、无 `SqlViewExecutor.execute`、无触发懒加载的关联 getter ✅**
> 迁移中的 `LEFT JOIN LATERAL` 是**单条 SQL 内**的相关子查询，由 PG 一次执行计划完成，不是应用层循环查库。

---

## 7. 交付物

| 文件 | 状态 |
|---|---|
| `cpq-backend/src/main/java/com/cpq/builder/compiler/SemanticCompiler.java` | 已改，**未提交**（B-1） |
| `cpq-backend/src/test/java/com/cpq/task260904/PriceGroupDuplicationAcTest.java` | 已改，**未提交**（决策 2） |
| `cpq-backend/src/main/java/com/cpq/builder/selfcheck/CostAllVersionViewSelfCheck.java` | 已由主线提交 `0c3c4aa7` |
| `cpq-backend/src/main/resources/db/migration/V434__repair260909_costing_price_strategy.sql` | 已由主线提交 `424ff8d0`，**已落共享库** |

**环境收尾**：我起的临时服务（8103）与测试进程全部已停，残留进程 0；
`8081 / 8092 / 8097 / 8098 / 8099`（主线与其它会话）**全程未碰**，收尾时 5 个监听全部在位。
