# 后端任务分解 · task-260907 报价侧加客户维度

> 后端只按本文做。🚫 不 `git commit`（由主线提交）· 🚫 遇 `CLAUDE.md` §3.2 不可逆操作停下报告。
> **本任务的完整背景与四条夹击约束见 `交接说明-customer_no.md`，动手前必读。**

---

## 🚨 开工前必读的三条（顺序不能反）

### ① 🚨 本任务一律连**隔离库**起服务，🚫 绝不连共享库 `cpq_db_0724`

**隔离库已就绪（主线 2026-09-07 建）**：`cpq_t260907_custdim`
```
10.177.152.12:5432/cpq_t260907_custdim   凭据同 dev：postgres / joii5231
由 cpq_db_0724 全量 pg_dump 克隆，restore 错误 0 条
292 张表 / flyway 顶版 422 / ds_quote_material 49 行  ← 与共享库当时状态一致
```

起服务 / 跑测试时覆盖数据源（**三个参数都要给，只给 url 不生效**）：
```bash
cd cpq-backend && ./mvnw quarkus:dev \
  -Dquarkus.http.port=<你自己的端口> \
  -Dquarkus.datasource.jdbc.url=jdbc:postgresql://10.177.152.12:5432/cpq_t260907_custdim \
  -Dquarkus.datasource.username=postgres \
  -Dquarkus.datasource.password=joii5231
```
✅ **起完先自证连对了库**：查 `flyway_schema_history` 顶版应为 **423 或更高**。
**若仍是 422 ⇒ 你没连过去，参数没生效，立刻停下** —— 这一步不做，后面所有「已验证」都可能是在共享库上得出的。

🚨 **为什么非隔离库不可**（主线 2026-09-07 实证，不是保守起见）：
`V423` 给 28 张 `ds_quote_*` 加 `customer_no` 并**收紧 `NOT NULL`**。而 master 的代码里：
```
DatasetSchemaSelfCheck.java:116   if (!exp.contains(col)) problems.add(table + " 多出未声明的列: " + col);
DatasetSchemaSelfCheck.java:73    throw new IllegalStateException(...)
```
是**双向**比对 —— **DB 多一列同样抛**。共享库当时有 **12 条活连接 / 6 个并发会话**。
⇒ `V423` 一旦落共享库，**所有会话的后端下次启动全部失败**，不是本分支挂，是所有人挂。
⇒ 这也是为什么 **🚫 不许把 V423/V424 单独推 master 来「解阻塞」** —— 那正是下面 ② 禁止的中间态。

### ①b 兜底守卫：起任何**连共享库**的服务前先比对（不能替代 ①）

```bash
# 仓库根目录执行。检查不过【根本不会】走到启动那一支。
M=cpq-backend/src/main/resources/db/migration
DIFF=$(comm -23 \
  <(ls "$M" | grep -oE '^V[0-9]+' | sort -u) \
  <(git ls-tree master --name-only "$M" | grep -oE 'V[0-9]+' | sort -u))

if [ -n "$DIFF" ]; then
  echo "🚨 工作区有 master 上没有的迁移：$DIFF"
  echo "   起服务会把它们自动落进所连的库（migrate-at-start=true）"
  echo "   ⇒ 停下报主线，不要连共享库起服务"
else
  (cd cpq-backend && ./mvnw quarkus:dev)
fi
```

🔑 **左边必须是 `ls`（文件系统），🚫 不是 `git ls-tree HEAD`** ——
本条 2026-09-07 由主线实证修正：**Flyway 读的是文件系统，不是 git**。
原版守卫用 `git ls-tree HEAD` 作左边，而 `V423`/`V424` 当时是**未跟踪文件**
（从未 commit、从未落库）⇒ **它们会从守卫底下整个溜过去，守卫报「无差异」然后照常启动**。
**一个只看已提交内容的守卫，防不住「还没提交」这个最常见的形态。**

🔑 **为什么把检查和启动写成一条命令，而不是写一条纪律**（由 `产品管理客户过滤` 会话指出，主线采纳）：
**写下来不等于会被执行** —— 尤其当这个失败模式的特征就是**当事人不知道自己触发了它**
（起服务是你知道的动作，落库是你看不见的副作用）。纪律要求你**记得**，而你连自己踩了都不知道。
⇒ 把检查与启动做成**同一个不可分离的动作**，跳过检查需要额外动作才做得到。

⚠️ **必须用 `if/else`，🚫 不要写成 `[ -n "$DIFF" ] && echo "停手"`** ——
本项目实证教训（`RECORD.md` 2026-09-06 提议④）：曾写过 `[ -e "$DST" ] && echo "已存在，停手"`，
**打印了「停手」却继续执行**，覆盖了未读过的文件。**红线守卫必须是控制流，不是打印。**

> 📌 该守卫已由对方做过证伪实验 **4/4**：基线换成 `HEAD~40` 时拦下的差集正好是
> **`V418 V419 V420 V421`** —— 等于用今天真实的事故迁移验证了它抓得住（该实验用的是已提交的迁移，
> 所以**没能暴露上面那个「未跟踪文件」的洞** —— 证伪实验只能证明它抓得住你想到的那一类）。

🚨 **背景**：
```
application.properties:67  quarkus.flyway.migrate-at-start=true
                     :80  %prod.…=false        ← 只有 prod 关；dtz/jh/test/test2 全是 true
```
⇒ **起一次 Quarkus 服务 = 把工作区里目标库没有的迁移自动落进那个库。**

2026-09-07 当天已同型事故 **5 次**（V416~V421）。其中一次是子代理起临时服务自检时无意落的 ——
**它回报「未手工执行任何迁移 SQL」属实，它不知道自己落了库。**

⇒ 🚫 **「落库就推 master」不可执行**（人不知道自己落了库）。**可执行的是「连隔离库」+「起服务之前先比对」**——
因为「起服务」是你**知道自己在做**的动作。

### ② 🔴 DDL 与轴模型**必须同一批合并**，不许中间态

#### 合并日操作（主线 2026-09-07 预先实证，合并那天照做即可，🚫 不要重新推导）

**✅ 已证实：启动顺序是安全的，合并后不存在「代码要列、库还没列」的窗口。**
判据不是 `DatasetSchemaSelfCheck` 那句自称「早于 StartupEvent，顺序是安全的」的 javadoc
（设计期注释不能当判据），而是**实证**：隔离库克隆自 V422（当时**没有** `customer_no`），
用**要求该列**的分支代码起服务 —— 结果 `flyway_schema_history` 423/424 `success=true`，
且 `pg_stat_activity` 上出现 **5 idle + 1 active** 的连接池
⇒ **Flyway 先跑完，自检才跑，并且通过**。若顺序反了，应用会在自检处抛 `IllegalStateException` 起不来。

**⚠️ 真正的协调成本在别处**：合并 + 有人重启使共享库迁移之后，
**其它会话仍在跑旧代码的 worktree，下次重启会撞 `多出未声明的列: customer_no`**
（`DatasetSchemaSelfCheck:116` 的双向比对，方向反过来）。
⇒ 合并前必须**广播**给全部并发会话：「本次合并后，重启后端之前先把 master 合进你的 worktree」。
合并时点的活跃会话可用 `ListAgents` 取；当天已知 6 个。


**不允许「先加列、轴模型下一轮再改」。** 中间那个状态最危险：列已经在、值也在填，而删除仍按单列轴走 ——
**看起来一切正常，实际每次导入都在删别的客户的数据。**

⇒ 这是**结构上不让那个状态出现**，强于「发现轴还是单列就停下」那种事后检测。

### ③ 夹具前缀用 `T260907C-`

多线并发，前缀撞了的危害不是脏数据是**互删**（两边都按 `LIKE 'T260907%'` 清理，谁先跑谁把对方删了，
**症状是随机挂且极像业务回归**）。已占用：`T260907B-` / `T260907Q-` / `T260907T-` / `T260907M-`。
🚫 清理一律用主键或完整名精确删。

---

## B-1 · 所有 `ds_quote_*` 业务表及其镜像表加 `customer_no`

**服务的 AC**：AC-2 · AC-3

### 列定义（🚫 不要自己定，照抄既有那张）

```
customer_no   character varying(20)   NOT NULL   无默认值
```
出处：`ds_quote_customer_part.customer_no` 实查。取值口径 = `customer.code`。

### 存量回填（用户裁决）

> 原话：「统一给罗克韦尔的客户号，测试数据不计较数据真实，重点是业务逻辑准确」

**统一 `CUST-0001`**（罗克韦尔）。

🔑 **裁决的重点在后半句**：回填值**不承担业务正确性**，它只是让隔离逻辑**能被验证**的载体。
⇒ 🚫 **不要写任何「校验 `customer_no` 值是否业务合理」的逻辑**（如反查料号该属于哪个客户）。要保证的是**机制**。

### 🚫 用动态扫，不要写静态 `ALTER TABLE` 清单

```sql
SELECT table_name FROM information_schema.tables t
WHERE t.table_name LIKE 'ds_quote_%'
  AND NOT EXISTS(SELECT 1 FROM information_schema.columns c
                 WHERE c.table_name=t.table_name AND c.column_name='customer_no')
```
⚠️ **13 张 `ds_quote_*_record` 已带该列**（`核价回填` 会话的 V420 建的）⇒ 静态清单会撞「列已存在」。
📌 参考量级：2026-09-07 实测缺该列 **28 张**（15 主表 + 13 `_history`）。**🚫 不要把这个数字写进代码或 AC。**

### 🔴 加列不是跑个 ALTER 就完了 —— 四条夹击约束

| # | 约束 | 源码 | 不做的后果 |
|---|---|---|---|
| ① | 列集**双向**比对 | `DatasetSchemaSelfCheck.java:112-118` | Registry 不同步声明 ⇒ **后端起不来** |
| ② | 🚨 **但不能声明成 `ColumnDef`** | `DatasetSheetParser.java:72-77` | `persistedColumns()` 每列必须在 Excel 表头出现，而 `customer_no` 来自**选客户下拉不来自 Excel** ⇒ **15 张 sheet 整份拒收** |
| ③ | 出路：**静态系统列** | `SheetDef.java:20-27` + `expectedTableColumns():106-113` | 进自检、不进 `persistedColumns()` ⇒ 正是所需形状 |
| ④ | 写入点要显式加 | `VersionedGroupWriter.java:264-267`（`insertAll`）· `:244-245`（`archive`） | 否则**列建了、值永远 NULL** |

**实现形状**：
1. `SheetDef` 加第四组静态常量（如 `CUSTOMER_COLUMNS`）
2. 🚨 **必须只对报价侧条件生效** —— `SheetDef` **三套数据集共用**，无条件追加会让核价两套自检也要求这列 ⇒ **核价侧当场起不来**
3. `expectedTableColumns()` / `expectedHistoryColumns()` 按报价侧条件追加
4. `insertAll` 与 `archive` 两处显式带上它

> ⚠️ **④ 的失败形态**：列建好了、自检过了、导入也不报错，**只是值恒为 NULL**。
> 届时 AC-2① 会红 —— **红得对，但排查方向极易跑偏到 DDL 上**，真因在两个写入点的列清单里。

---

## B-2 · `SheetDef.axisColumn` 由单列改复合

**服务的 AC**：AC-2 · AC-4

```java
SheetDef.java:30   public final String axisColumn;   // ← 单列 String，要改
```

🚨 **判据要落在签名层，不要落在点名清单上**：让**新增调用方编译期就必须提供** `customer_no`。

> 📌 **为什么**：主线「找调用方」这件事**连错三次，错法各不相同** ——
> ① 按类名 grep 漏查使用引用（DI 项目里定义引用 ≠ 使用引用）
> ② 漏查未来调用方（穷举当下 ≠ 穷举未来）
> ③ 没查「引用」是不是「调用」（把锁键工具当成写入方）
> **⇒ 清单这个形式本身不可靠。**

---

## B-3 · `VersionedGroupWriter` 的整组删除按复合轴执行

**服务的 AC**：AC-2（本任务**最重要**的一条）

```java
VersionedGroupWriter.java:206
  "DELETE FROM " + table + " WHERE " + axisCol + " IN (:axes)"
```

🚨 **失败形态是静默删数据，不是撞键报错**（主线曾判反，已实测更正）：
```
ds_quote_* 的 UNIQUE 约束：pg_indexes UNIQUE 非 pkey → 3 条，全在【免版本表】
                          （🚫 别用 pg_constraint contype='u' 查，它返 0——那 3 条是 CREATE UNIQUE INDEX 建的）
13 张带版本表：只有 PRIMARY KEY (id)，无任何业务唯一索引
```
⇒ 隔离**根本不靠 DB 约束**，靠整组删除 + 重插。
**只加列不扩轴 ⇒ 客户 A 导入料号 X 会把客户 B 的料号 X 整组删掉，不报错、不撞键、不留痕。**

---

## B-4 · 每一条写入路径都要能提供 `customer_no`

**服务的 AC**：AC-4

| 调用方 | `customerNo` 引用数 | 客户来源 |
|---|---|---|
| `DatasetImportService` | 0 | 导入时选客户（`报价导入切ds新表` 的 `QuotationImportService` 提供） |
| `DatasetMaintenanceService` | 0 | 🚚 **前端选择器已移出本任务**（`task-260907-产品管理客户过滤` 承接）。本任务只需**让它能接收并透传** |
| `DatasetCustomerPartService` | 22 | 自带 |
| `SelDsQuoteWriter` | 5 | 选配链路 |
| `ConfigureProductService` | 55 | 选配主链路。⚠️ `:1205-1265` 有**明确标注的豁免点**至今仍写 V6 表，**不是纯 ds_ 路径**，算影响面时别按「已全切新表」这个前提 |
| （未来）`核价回填` 的 S-4 | — | 报价单客户（`quotation.customer_id → customer_no`） |

> 🚫 **`DatasetGroupLock` 不在此列** —— 36 行锁键工具，**不调用** writer；反过来是 `VersionedGroupWriter:140` 调它。

---

## B-6 · `uq_ds_quote_material` 扩成复合

**服务的 AC**：AC-2

```
现状：uq_ds_quote_material  USING btree (material_no)          ← 单列
目标：                       (customer_no, material_no)
```
⚠️ 另两条唯一索引：`uq_ds_quote_customer_part(customer_no, customer_product_no)` **已含客户，不动**；
`uq_ds_quote_plating_scheme(scheme_no, scheme_version, item_seq)` 与客户无关，**不动**。

---

## B-7 · `costing_bom_tree_config` 的 QUOTE 递归换读新表 + 接客户隔离

**服务的 AC**：AC-1

现状（实测）：
```
usage=QUOTE    读 V6 裸表 material_bom_item=t  读兼容视图=f  读新表=f  带 versionFilter=f
usage=COSTING  同上                                                    带 versionFilter=t
```

### 🚦 B-7a · 修 `_cust`（2026-09-07 用户裁决：**修，只改 QUOTE 那条**）

⚠️ **本节推翻了本文件先前写的「`_cust` 是非本任务的架构问题，🚫 不要顺手改它，那是扩范围」。**
那条边界是主线立项时写的，**写错了**：`_cust` 恰恰就是递归的客户隔离本身，
不改则 AC-1 的断言①②③**在任何实现下都不可能成立**，B-7 的「接客户隔离」也是空话。
子代理已用自造夹具实证：同一料号挂两客户时，两张不同客户的报价单**都**拿到 `CUST-0001` 的子树
（没有串客户，但也没有按报价单的客户选）。

模板现状（`usage='QUOTE' AND is_active`，实查）：
```sql
WITH RECURSIVE bom AS (
  SELECT ...,
    (SELECT bc.customer_no FROM material_bom_item bc
      WHERE bc.material_no=p AND bc.system_type='QUOTE' AND bc.is_current
      ORDER BY bc.customer_no LIMIT 1) AS _cust      -- ← 取 customer_no 最小的，与本单客户无关
  FROM unnest(:production_part_nos) AS p
  UNION ALL
  SELECT ..., b._cust
  FROM material_bom_item ch JOIN bom b ON ch.material_no=b.material_no AND ch.customer_no=b._cust
) CYCLE material_no SET is_cyc USING cyc_path
```

**四个触点（主线已数清，实现时逐条勾）**：

| # | 触点 | 做什么 |
|---|---|---|
| ① | `costing_bom_tree_config` 的 **QUOTE** 那条 `sql_template` | 把那个相关子查询整体换成绑定参数 `:customerCode`（迁移改数据，不是改代码） |
| ② | `BomTreeRenderService.queryRecursive`（`:610`） | 它自己做具名参数→`?` 的 mask+有序替换（现支持 `:production_part_nos` / `:pq` / `:__vfPart` / `:__vfVer`），**要把 `:customerCode` 加进这套协议并绑值** |
| ③ | 客户码从哪来 | **不必新查** —— `BomTreeRenderService:286~293` 已解出 `ctxCustomerId`（task-0729 真根因修复时加的，整单只查一次）。UUID→code 的解析有现成先例：`SqlViewExecutor:418~438` 的 `customerCodeCache` / `queryCustomerCode` |
| ④ | `CostingTreeSqlValidator`（`:46` / `:55`） | 探针只把 `:production_part_nos` 替成 `ARRAY[]::text[]`，**新模板里的 `:customerCode` 不加桩会让校验直接失败**（保存树配置时报错）。加桩即可 |

🚫 **`usage='COSTING'` 那条一个字不动** —— 它的客户语义是 `customer_no IN (客户, '_GLOBAL_')`，
与报价侧不同轴，带 `versionFilter`，动它需要另验核价树渲染无回归。**本期不碰**。

✅ **AC-1 原文保留不动**，本期真正达成。

### 💡 一条待验证的近路（🚫 不是结论，先做三条排除）

`v_compat_material_bom_item` 对 `ds_` 独有的 **23 个** BOM 料号 **23/23 全覆盖**（主线实测）。
`task-260903` 已为 **135 段组件 SQL** 做过同一替换并验过等价性。

🚨 **三条必须先排除的风险**：
1. 该配置**报价 + 核价共用**，核价那条带 `versionFilter` 而兼容视图新表侧**无版本列** ⇒ 大概率不能照搬
   （⚠️ 有了 B-7a 后这条更要留意：**只改 QUOTE 那条**，两条已经分家，别再顺手对齐）
2. `task-260903` 那次替换的**已知行为变更是行序会变**，而**递归对行序更敏感**（树的兄弟节点顺序）⇒ 必须单独验
3. 兼容视图新表侧 `pending_quotation_id` 恒 NULL、`is_current` 恒 true ⇒ 若递归里有 pending/版本谓词，**语义会变**

🚨 **另一个已发现的风险（子代理报，未处置）**：`QuotePendingRewriter.WHITELIST_TABLES`（`:53`，8 张版本化表）
**不含 `ds_quote_material_bom`** ⇒ 递归若直接改读新表，本单 pending 影子行将不可见（实测 11070 行）。
换表前先说清怎么办，🚫 不许默默换过去。

## 自检（回报里贴命令原始输出）

- 起服务前跑**迁移号差集**（见开工前必读①），非空先回报
- `mvnw -o clean test-compile` 通过
- 冷启动无 ERROR；`DatasetSchemaSelfCheck` 启动期自检通过（**报价侧要求该列、核价两套不要求**）
- 业务端点 200/401（**不要 500**）；迁移 `success = true`
- **N+1 自检**：说清每个改动路径的 SQL 条数是否与 N 无关
- 🚨 **核价两套回归**：同一份夹具在导入与维护端保存两条路上，改动前后**逐行 md5 相同**（AC-6）

## 🚫 红线

- 共享 dev 库**不许跑清库型测试**；夹具用完按主键删净，给「残留=0」证据
- 🚫 不对共享库跑 `flyway repair`；🚫 不用 `-Dquarkus.flyway.validate-on-migrate=false` 长期绕过
- 🚫 不 `pkill -f "quarkus:dev"`（会误伤他人进程），按端口精确定位 PID
- 🚫 绝不 cd 回主仓跑 `mvnw`；worktree 里跑 `quarkus:dev` 与 `mvnw` **共用 `target/`** ⇒ 用隔离副本，拷完 `diff -rq`

---

## B-8 · 🆕 元素价格策略闭环：给 `f_material_element_price` 的候选料号集补 `ds_` 支

**服务的 AC**：AC-8（用户 2026-09-07 裁决「加上，让元素价格策略闭环」）

### 🚦 2026-09-07 方案改判：**不换 PRICE 边，改补候选集**（用户裁决）

⚠️ **本节整体推翻了先前的「加 `FUNC_CUSTOMER_ELEMENT_PRICE` 节点 + 改指 PRICE 边」方案。**

🚦 **落地方式 = 新增 `V425`，🚫 不要改写 `V424`**（主线 2026-09-07 定，理由如下）：
- `V424` 已应用到隔离库 `cpq_t260907_custdim`（`flyway_schema_history` 423/424 均 `success=true`，
  实测已建出 `FUNC_CUSTOMER_ELEMENT_PRICE` 节点并把 `PRICE` 边改指过去）
- 改写它 = checksum 失配，隔离库下次启动直接挂；且违反项目规则
  「**schema 变更一律新建迁移脚本，不改历史脚本**」（`backend.md §4`，改名/移动也被 hook 拦）
- 走追加式的**决定性好处**：全新库（master）跑 `V424→V425`、已应用库（隔离库）只跑 `V425`，
  **两条路径收敛到同一状态** —— 这是改写做不到的

**`V425` 要做三件事**（缺一不可，前两件是撤销 `V424`）：
1. 把 `PRICE` 边**改回**指向 `FUNC_ELEMENT_PRICE`，并把 `QUOTE/材质元素` 上的 AUX 挂载一并改回
2. 删除 `FUNC_CUSTOMER_ELEMENT_PRICE` 节点及其 3 个 `semantic_node_column`
   （⚠️ `DELETE` 必须带精确 `WHERE node_key=...`，并在回报里给出**删除前后行数**）
3. 给 `f_material_element_price` 的 `candidate_materials` 补 `ds_` 支（下面「做什么」一节）

✅ **收敛自证**：`V425` 跑完后，隔离库里
`SELECT count(*) FROM semantic_node WHERE node_key='FUNC_CUSTOMER_ELEMENT_PRICE'` 必须 = **0**，
且 `QUOTE/材质元素` 的 `groupKind='PRICE'` 组数必须 = **1**、`groupKey` 回到 `FUNC_ELEMENT_PRICE`。

原因是主线实测发现原方案有**真回归**：

```
老函数 f_material_element_price(customer, date) 是 UNION 的两支：
  versioned : material_price_version_ref → element_price_version_item   ← 按【料号】钉的版本价
  realtime  : candidate_materials CROSS JOIN f_customer_element_price   ← 客户级实时价，纯扇出
```
⇒ 改指 `f_customer_element_price` 等于**丢掉 versioned 分支**。

🚨 **先前那句「实测 `material_price_version_ref` 全库仅 4 行，`CUST-0004` 为 0 ⇒ 现网零损失」是错的** ——
它**只抽了 CUST-0004 一个客户，就下了全网的全称结论**。主线改用全客户扫后实测：

```sql
SELECT c.code, (SELECT count(*) FROM (
    SELECT element_code FROM f_material_element_price(c.code, CURRENT_DATE)
    GROUP BY element_code HAVING count(DISTINCT unit_price) > 1) z) AS 逐料号有价差的元素数
FROM customer c;
→ CUST-0002 = 1 · CUST-0729-QA = 1 · 其余全 0
```
`CUST-0002` 有 1 个料号钉了 `Ag = 3500`，客户级实时价是 `3000`
⇒ 换边会让它**静默从 3500 掉到 3000**，且 **21 个存量视图**（实查 `component_sql_view` 引用
`FUNC_ELEMENT_PRICE`，全部是 QUOTE/材质元素）**全部**走这条路。

### ✅ 真正的缺口在别处（实查）

```sql
-- f_material_element_price 三参重载里的 candidate_materials：只读 V6 两张表
SELECT material_no FROM material_bom_item  WHERE customer_no IN (p_customer_no,'_GLOBAL_') AND (...)
UNION
SELECT material_no FROM element_bom_item   WHERE customer_no IN (p_customer_no,'_GLOBAL_') AND (...)
```
⇒ `ds_` 独有的料号不在候选集里，`realtime` 分支的 `CROSS JOIN` 就带不出它们，**恒 0 行**。

**ds_ 独有料号实测 = 8 个**（⚠️ 只作记账不进判据；且注意 `UNION`/`EXCEPT` 同级左结合，必须加括号，
主线首次算成 1872 就是漏了括号）：
```sql
SELECT count(*) FROM (
  (SELECT material_no FROM ds_quote_material_bom UNION SELECT material_no FROM ds_quote_element_bom)
  EXCEPT
  (SELECT material_no FROM material_bom_item UNION SELECT material_no FROM element_bom_item)) z;
```

### 做什么

给三参重载 `f_material_element_price(text, date, uuid)` 的 `candidate_materials` **补两条 UNION 支**，
读 `ds_quote_material_bom` / `ds_quote_element_bom`，按 **B-1 新加的 `customer_no`** 过滤。

**这个方案的好处（逐条都要在回报里自证）**：
- ✅ versioned（钉版本价）分支**原样保留** —— `CUST-0002` 的 `Ag=3500` 不变
- ✅ 语义图**一条边都不动** ⇒ 21 个存量视图零影响；`FUNC_ELEMENT_PRICE` 节点、PRICE 边、AUX 挂载全不动
- ✅ QUOTE/材质元素 的 **PRICE 组数恒为 1**，不重现 `取数配置器补齐 AC-30`
- ✅ `Sec34PriceStrategyTest` 那 4 条断言**不会红**（它们钉的正是现有边与节点）
- ✅ 是**纯加法**：只往候选集里加料号 ⇒ 已有料号的返回值逐行不变

### 自证要求（🚫 不许只说「加了」）

1. **A/B 值中性证据**：改动前后，对**每个** `customer.code` 跑
   `SELECT md5(string_agg(material_no||'|'||element_code||'|'||unit_price, ',' ORDER BY 1,2))
    FROM f_material_element_price(code, CURRENT_DATE)`，
   **原有客户的 md5 必须逐一相同**（新增料号只会让 ds_ 侧客户多出行 ⇒ 说清哪几个客户的 md5 变了、为什么该变）
2. **闭环证据**：那 8 个 ds_ 独有料号中，**至少 1 个**在改动后能取到非空单价，贴出料号 + 元素 + 值
3. ⚠️ **数据依赖三层**（缺任一层该元素整行不出现，不是返 0）：
   `element` 表 ACTIVE + 该客户有 `element_price_strategy` + `element_daily_price` 有行情。
   `CUST-0004` 下实际能算出价的只有 5 个元素：`Ag 3000 / Cu 101.14 / Ni 105 / Zn 24.17 / 白银 12345`（全 CNY/kg）
   ⇒ **验收挑这 5 个里的**，否则会拿到「空但不是 bug」的结果

### 🚫 红线

- 🚫 **不新增 `FUNC_CUSTOMER_ELEMENT_PRICE` 语义节点**，🚫 不动任何 `semantic_edge`
- 🚫 不改 `f_material_element_price` 的**签名**与**返回列**（21 个存量视图按现有列名取数）
- 🚫 不改 `f_customer_element_price` 本身（它是被 `realtime` 分支调用的下游）
- ⚠️ `candidate_materials` 现有两支带 `is_current = true OR pending_quotation_id = :pq` 的**双半边**语义
  （`repair-260830`：一半管正式行、一半管本单 pending 影子行）。
  新加的 ds_ 支要说清**它对应的是哪半边** —— ds_ 表若无 `pending_quotation_id`，就直说没有、并说明后果
