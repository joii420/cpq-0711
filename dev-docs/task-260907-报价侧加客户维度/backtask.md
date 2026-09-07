# 后端任务分解 · task-260907 报价侧加客户维度

> 后端只按本文做。🚫 不 `git commit`（由主线提交）· 🚫 遇 `CLAUDE.md` §3.2 不可逆操作停下报告。
> **本任务的完整背景与四条夹击约束见 `交接说明-customer_no.md`，动手前必读。**

---

## 🚨 开工前必读的三条（顺序不能反）

### ① 🚨 用下面这条命令**代替** `./mvnw quarkus:dev`，不要直接起服务

```bash
# 仓库根目录执行。检查不过【根本不会】走到启动那一支。
M=cpq-backend/src/main/resources/db/migration/
DIFF=$(comm -23 \
  <(git ls-tree HEAD   --name-only "$M" | grep -o 'V[0-9]*' | sort -u) \
  <(git ls-tree master --name-only "$M" | grep -o 'V[0-9]*' | sort -u))

if [ -n "$DIFF" ]; then
  echo "🚨 本分支有 master 上没有的迁移：$DIFF"
  echo "   起服务会把它们自动落进共享库（migrate-at-start=true）"
  echo "   ⇒ 停下报主线，先把迁移文件推 master，不要起服务"
else
  (cd cpq-backend && ./mvnw quarkus:dev)
fi
```

🔑 **为什么是一条命令而不是一条纪律**（由 `产品管理客户过滤` 会话指出，主线采纳）：
**写下来不等于会被执行** —— 尤其当这个失败模式的特征就是**当事人不知道自己触发了它**
（起服务是你知道的动作，落库是你看不见的副作用）。纪律要求你**记得**，而你连自己踩了都不知道。
⇒ 把检查与启动做成**同一个不可分离的动作**，跳过检查需要额外动作才做得到。

⚠️ **必须用 `if/else`，🚫 不要写成 `[ -n "$DIFF" ] && echo "停手"`** ——
本项目实证教训（`RECORD.md` 2026-09-06 提议④）：曾写过 `[ -e "$DST" ] && echo "已存在，停手"`，
**打印了「停手」却继续执行**，覆盖了未读过的文件。**红线守卫必须是控制流，不是打印。**

> 📌 该守卫已由对方做过证伪实验 **4/4**：基线换成 `HEAD~40` 时拦下的差集正好是
> **`V418 V419 V420 V421`** —— 等于用今天真实的事故迁移验证了它抓得住。

🚨 **为什么这条排在最前面**：
```
application.properties:67  quarkus.flyway.migrate-at-start=true
                     :80  %prod.…=false        ← 只有 prod 关；dtz/jh/test/test2 全是 true
```
⇒ **在 worktree 里起一次 Quarkus 服务 = 把该 worktree 里 master 上没有的迁移自动落进共享库。**

2026-09-07 当天已同型事故 **5 次**（V416~V421）。其中一次是子代理起临时服务自检时无意落的 ——
**它回报「未手工执行任何迁移 SQL」属实，它不知道自己落了库。**

⇒ 🚫 **「落库就推 master」不可执行**（人不知道自己落了库）。**可执行的是「起服务之前先比对」**——
因为「起服务」是你**知道自己在做**的动作。

### ② 🔴 DDL 与轴模型**必须同一批合并**，不许中间态

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
原递归靠 `ON ch.material_no = b.material_no AND ch.customer_no = b._cust` 做客户隔离。

### 💡 一条待验证的近路（🚫 不是结论，立项时先做三条排除）

`v_compat_material_bom_item` 对 `ds_` 独有的 **23 个** BOM 料号 **23/23 全覆盖**（主线实测）。
`task-260903` 已为 **135 段组件 SQL** 做过同一替换并验过等价性。

🚨 **三条必须先排除的风险**：
1. 该配置**报价 + 核价共用**，核价那条带 `versionFilter` 而兼容视图新表侧**无版本列** ⇒ 大概率不能照搬
2. `task-260903` 那次替换的**已知行为变更是行序会变**，而**递归对行序更敏感**（树的兄弟节点顺序）⇒ 必须单独验
3. 兼容视图新表侧 `pending_quotation_id` 恒 NULL、`is_current` 恒 true ⇒ 若递归里有 pending/版本谓词，**语义会变**

⚠️ **`AC-12②` 是个已知的、非本任务的架构问题**：该递归用
`_cust = (根料号那批边里 ORDER BY bc.customer_no LIMIT 1)` 选客户，**不是按报价单的客户**。
同一根料号挂两客户时，两张不同客户的报价单会拿到同一个（`customer_no` 最小的）客户的子树。
**master 与本分支逐字相同 ⇒ 不归本任务。** 🚫 不要顺手改它，那是扩范围。

---

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
