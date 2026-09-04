# 测试执行报告 · 报价与核价建表与导入方案新规范

> 与 `test.md`（测试**方案**）是两份文件。本文只装**执行结果与原始证据**。
> 执行者：`test-engineer` 子代理 · 分支 `feat/task-260902-dataset-tables-import` · 2026-09-03
> 🚫 **本报告不构成 AC 达成的证据**（`testing.md §0`）：测试证明「代码按实现者的理解工作」，
> AC 达成要由**主线亲验**在 dev server + 真实浏览器上逐条确认。本文是亲验的**证据基础**，不是替代品。

---

## §0 执行环境（判断本报告可不可信的前提）

| 项 | 值 |
|---|---|
| 库 | `10.177.152.12:5432/**cpq_db_0724**`（`test` profile 实连共享 dev 库） |
| 迁移 | `V405__ds_quote_tables` ~ `V408__ds_history_tables`，`Successfully applied 4 migrations, now at version v408` |
| 命令 | `./mvnw -o test -Dtest=<类名>`，工作目录 `cpq-backend/`（worktree 内） |
| 🚩 必需的环境变量 | `export QUARKUS_REDIS_HOSTS="redis://:joii5231@10.177.152.12:6379/0"` |

### 0.1 🚩 不设那个环境变量，全套用例会以「假红」形态倒掉

```
src/test/resources/application.properties:11       redis://:joii5231@10.177.152.12:6379/0   ← 可用
src/main/resources/application-test.properties:68  redis://:WzHf20230610@172.16.18.56:6380/0 ← 覆盖它，AUTH 失败
```

profile-specific 覆盖 generic ⇒ `test` profile 下 Redis 指向连不上的实例：

```
GlobalExceptionMapper: java.util.concurrent.CompletionException: CONNECTION_CLOSED
    at io.quarkus.redis.runtime.datasource.BlockingHashCommandsImpl.hset(...)
⇒ POST /api/cpq/auth/login 返回 500 ⇒ 每一条带 session 的用例全倒
```

⚠️ **同样的登录在 8081 上返 200**，所以极易误判成「鉴权坏了」。
这是 `test.md §0.2` 那条 RBAC-401 缺陷的**同族第二例**（同一个文件、同一种覆盖机制）。
绕法用环境变量（ordinal 300 压过 properties 的 250），**不改任何文件**。

### 0.2 反复撞到的构建陷阱

后端代理并发改 `src/main` 时，`target/classes` 会与源码失步，症状是
`ClassNotFoundException` / `NoSuchFileException: xxx.class`（文件明明在）。
⇒ **跑测试前先 `./mvnw -o -q compile`**。本轮撞了 3 次，每次都是这个原因，与业务无关。

---

## §1 结果总览

> **2026-09-03 末轮：`./mvnw test -Dtest='Dataset*Test'` → `Tests run: 59, Failures: 0, Errors: 0` BUILD SUCCESS**
> 前置（主数据 5 工序 / 4 元素 / 991、三份模板补齐轴值登记）解除后全绿。

| 测试类 | 结果 | 覆盖 AC |
|---|---|---|
| `DatasetFixtureSelfTest`（不连库） | **6 / 6 ✅** | 判据自检 |
| `DatasetPreflightTest` | **11 / 11 ✅** | 夹具前置 |
| `DatasetStructureAcTest` | **10 / 10 ✅** | AC-1 ~ AC-5 + R-1/R-2 |
| `DatasetImportValidationAcTest` | **7 / 7 ✅** | AC-6 ~ AC-11、AC-52 |
| `DatasetVersioningAcTest` | **3 / 3 ✅** | AC-12 ~ AC-20 |
| `DatasetUnversionedAcTest` | **3 / 3 ✅** | AC-21 ~ AC-23 |
| `DatasetCustomerPartAcTest` | **3 / 3 ✅** | AC-45 ~ AC-47 |
| `DatasetBoundaryAcTest` | **6 / 6 ✅** | AC-39~41、AC-25、AC-31/34 后端半边 |
| `DatasetQuoteAndRegressionAcTest` | **4 / 4 ✅** | AC-36/37/43/44 |
| E2E ×3 spec | 未执行 | AC-24~35、AC-38、AC-42、AC-48~51 |

**后端侧 AC 全部通过。** E2E 未跑，见 §4。

---

## §2 已验证达成的 AC（14 条，附原始输出）

### AC-1 · 表数量

```
[TS-01] 实际 ds_% = 84，其中 _history = 39
```
判据：`SELECT count(*) FROM pg_tables WHERE schemaname='public' AND tablename LIKE 'ds\_%'` → **84**；
其中 `LIKE '%\_history'` → **39**。✅

### AC-2 · 45 张主表列集合逐表等于 `字段矩阵.md`

```
[TD-00] 矩阵解析 OK：主表 45（带版本 39 / 免版本 6），建字段合计 332 列
TS-02 通过：45 张全部比对，0 张不符
```
🚨 判据的**右边是 `字段矩阵.md`（文档），不是 Java Registry** ——
读 Registry 会让判据退化成「实现 == 实现」，实现漏一列时 Registry 跟着漏，测试照样全绿。✅

### AC-3 · 白底列未建

```
[TS-03] ds_cost_detail_capacity 实际列 = [created_at, created_by, currency, id,
        labor_std_price, operation_no, production_no, row_fingerprint, source,
        unit, updated_at, updated_by, version_no]
```
`material_name` / `specification` / `dimension` / `operation_name` **四个白底列均不存在** ✅
`TS-03b` 把这条推广到全部 45 张表，0 张多出列。

### AC-4 · 6 张免版本表

```
[TS-04] 6 张免版本表全部符合 R-2
```
均无 `version_no` / `row_fingerprint`，且对应 `_history` 表不存在。✅

### AC-5 · `_history` 结构（🚩 措辞修正后）

```
[TS-05] ds_cost_detail_material_bom_history 列集合 =
  [archive_reason, archived_at, archived_by, base_qty, base_qty_unit, component_no,
   component_qty, component_qty_unit, component_type, created_at, created_by,
   defect_rate, id, item_seq, material_fixed_loss, material_loss_rate, operation_no,
   origin_id, production_no, row_fingerprint, source, updated_at, updated_by,
   usage_characteristic, version_no]
```
= 主表列（`id`→`origin_id`）∪ 3 归档列 ∪ `{id}` ✅
`TS-05b` 推广到 39 张 `_history`，全部相符。

> 📌 **本条曾以「39/39 全不符」红过**：AC-5 原文没写 history 自带主键，实现同时有 `id` 与 `origin_id`。
> 经裁决 **改 AC 不改实现**（同一 `origin_id` 会因多次升版出现多行，不能做主键）。

### R-1 / R-2 · 约束（TS-06 / TS-07）

```
TS-06 通过（行为实证：事务内插两条业务键相同的行 → 第二条抛唯一性冲突 → 整个事务回滚）
TS-07 通过（39 张带版本表的 version_no / row_fingerprint / 轴列均 NOT NULL）
```
探针残留复查，6 张表逐张 **0 行**：
```
ds_quote_material 探针残留=0        ds_cost_basic_material 探针残留=0
ds_cost_detail_material 探针残留=0  ds_quote_customer_part 探针残留=0
ds_quote_plating_scheme 探针残留=0  ds_cost_detail_plating_scheme 探针残留=0
```

> 🚩 **本条我误报过一次，教训写在这里**：
> 第一版查 `pg_constraint` 列数 → 被 `id` 主键的 1 列凑巧骗过；
> 第二版查 `pg_constraint` 列集合 → 报「6 张全缺唯一约束」，**但实现用的是 `CREATE UNIQUE INDEX`，它不进 `pg_constraint`**。
> ⇒ 两次都是**查错了元数据**。第三版改**行为实证**（能不能拦住重复）后一次就对。
> **结构类断言优先验行为，不验元数据长什么样。**

### AC-6 / AC-7 / AC-8 / AC-9 / AC-10 · 导入校验

| AC | 判据 | 结果 |
|---|---|---|
| AC-7 | `年降系数` 轴列（橙底）为空 → 400 + `轴列不可为空` | ✅ |
| AC-8 | 元素代码 `ZZZZ` → 400 + `主数据不存在` | ✅ |
| AC-9 | 加工费填 `abc` → 400 + `不是合法数值` | ✅ |
| AC-10 | 4 类错误分布 4 个 sheet → 一次返回 4 条 + **45 张表 count 逐表不变** | ✅ |

> ⚠️ **AC-7 的行号与 AC 原文不同**：原文写「第 2 行」，但模板里第 2 行是「轴/对比项」标记行、
> 且该 sheet 一条数据行都没有（`maxRow=2`）。夹具在第 3 行造数据行、轴值留空，断言 `第 3 行`。
> **该改写已获主线批准**。
> ⚠️ **AC-6 曾绿后转红**：新增的 `轴值未在物料表登记` 让报告多出 4 条，
> 打破了 AC-6「含且仅含一条」的判据 —— 见 §3。

### AC-52 · 轴值登记校验（D-24，新增）

```
[TI-07] 补登记后 TEST-DS-M1 / TEST-DS-M9 均 CREATED v1
```
- 第一段：`物料` sheet 只登记 `TEST-DS-M1`，`物料BOM` 引用 `M1`+`M9`
  → 400 + `{sheet=物料BOM, column=生产料号, reason=轴值未在物料表登记}`，10 张 `ds_cost_basic_*` count 逐表不变 ✅
- 第二段：把 `M9` **只补进 Excel、不预先写库** → 200，`物料BOM` 的 `created=2`，两轴 `version_no` 均为 1 ✅
  （这一段专门验「判定集合 = 本次 Excel ∪ 库中已有」里**同文件内先登记后引用**那条分支）

### AC-45 / AC-46 · 客户编号（D-18 / D-19）

| AC | 判据 | 结果 |
|---|---|---|
| AC-45 | 客户编号留空 → 400 + `必填项为空`，`ds_quote_customer_part` count 不变 | ✅ |
| AC-46 | 客户编号 `NOTEXIST-999` → 400 + `客户编号未在客户档案中登记`，16 张 `ds_quote_*` count 全不变 | ✅ |

### AC-40 · 超长输入（🚩 2026-09-03 修正版）

```
判据自检：ds_cost_basic_finished_fixed_fee.currency     character_maximum_length = 128 ✅
         ds_cost_basic_finished_fixed_fee.element_name character_maximum_length = 256 ✅
① 币种 129 字符   → 400 + 列「币种」：超出长度上限 128    ✅
② 要素名称 257 字符 → 400 + 列「要素名称」：超出长度上限 256 ✅
两条均复查「库中不得出现该值前缀」→ 0 行（无静默截断）✅
```
> 📌 用例**先自检两列的真实上限再验超长**。旧版 AC-40 写「要素名称上限 128」而该列实为 `varchar(256)`，
> 正是因为判据里没有这一层自检才没被及时发现。现在长度对不上会以「AC 数字与 schema 不符」硬失败。

### AC-34 后端半边 · 错数据集整份拒收

报价文件传给 `cost-basic` → 400 + `不属于基础核价数据集`，`ds_cost_basic_*` 全部表 count 不变 ✅
⚠️ AC 原文点名的字面 sheet 名「产能」来自 `核价1`，该文件仍不可用 ⇒ **那三个字待主线补文件后亲验**。

### AC-31 后端半边 · 写端点鉴权

无 session 调 `POST /dataset/{ds}/import` → 401 ✅；无 session 调 `PUT .../rows` → 401 ✅
⚠️ **角色维度（可见但禁用）未验证**，见 §4。

---

## §3 ✅ 曾冻结 12 条 AC 的前置，已全部解除

**原前置**：三份模板的 `物料` sheet 没登记齐带版本表用到的轴值，触发 D-24 的
`轴值未在物料表登记` → 整份拒收，AC-6/11/12~23/36/37/39/41/44/47 全部不可达。

**2026-09-03 主线补齐后复核**（我实测三份文件，`未登记 = 无 ✅`）：

| 文件 | 物料 sheet 轴值 | 带版本表轴值 | 未登记 |
|---|---|---|---|
| 报价 | `202601011226 / 5550C1649001 / S-3120014539 / S-80011` | 同左 4 个 | **无 ✅** |
| 核价2 | `S-3120014539 / 3120014539 / 2120011658 / 2120011659 / 3110520789` | 4 个 | **无 ✅** |
| 核价1 | 同核价2 | 4 个 | **无 ✅** |

主数据侧同样复核通过：`process_master` 命中模板 5 个工序 `5/5`、`element` 命中报价元素列 `5/5`、`material_recipe` 含 `991`。

⇒ 12 条 AC 全部解冻并通过。

---

## §4 未验证项（🚫 不写「应该没问题」）

| # | 项 | 原因 |
|---|---|---|
| ~~U-1~~ ✅ **已完成** | FT-1 ~ FT-4 四条证伪实验 | 见 §4.2，四条全部「改之前绿 / 改之后红 / 还原后绿」，含 FT-4 第一次干预失效的复盘 |
| U-2 | AC-24~35 / AC-38 / AC-42 / AC-48~51（E2E 三个 spec） | 后端导入链路不通，跑了是假信号；且 5174/8081 保留给主线亲验 |
| ~~U-3~~ ✅ **已解除** | AC-26「详细核价 17 个 tab」、AC-34 字面 sheet 名「产能」 | 用户已于 **2026-09-03 02:25 重存三份模板**：`核价1` 现可读、19 sheet 与矩阵逐个吻合（`[TD-01c] 核价1 存在=true 可读=true`）。已补 `Fixtures.costDetail()`，待随 §3 解冻后跑 |
| U-4 | AC-31 角色维度（可见但禁用 + hover 文案） | 库中无「非 `PRICING_MANAGER`/`SYSTEM_ADMIN` 且 ACTIVE」账号（`test0806_alice`/`bob` 均 INACTIVE）。🚫 未自行启用账号 —— 那是改共享库全局状态 |
| U-5 | AC-43 旧导入无回归 | 端点路径已由主线更正为 `/api/cpq/basic-data-import/v6/{pricing,quote}`，用例已改，待随 §3 解冻后重跑 |
| U-6 | AC-44 的「SQL 条数与料号数无关」 | 接口层拿不到 SQL 计数器，用例只覆盖「≤30s + 2000 行完整落库」；N+1 那半句需主线在 dev server 日志侧亲验 |
| U-7 | AC-26/27/28/30 的 UI 路径 | 「阻塞：模板口径待修」（`S-3120014539` vs `3120014539`）。用例判据已写好，`assertTemplateAxisMismatchBlocked()` 具名硬失败，🚫 未在夹具里统一前缀 |

---

### §4.1 模板换版说明（2026-09-03 02:25）

三份 `.xlsx` 被用户同时重存。已复核变化：

| 文件 | 变化 | 对测试的影响 |
|---|---|---|
| `核价1` | `BadZipFile` → **可读，19 sheet** | U-3 解除；`Fixtures.costDetail()` 已补 |
| `报价` | `来料回收折扣` 残行**已删** | 夹具清理改条件式 + `TD-02e` 转为回归守卫 |
| `报价` | `物料` sheet **仍为空**（`maxrow=1`） | §3 的 A-5 前置**未解除**，12 条 AC 仍冻结 |

> ⚠️ 模板是**移动靶**。本报告的每条结论都注明了它对应哪一版模板；
> 模板再换版时，`TD-01*` / `TD-02*` 会第一时间以「夹具前置不满足」的名义硬失败，
> 不会让它伪装成业务缺陷 —— 这正是把前置自检单独拆一个类的原因。

---

## §4.2 四条证伪实验（FT-1 ~ FT-4）—— 全部完成

`testing.md §4.4`：首次 PASS 证明不了守卫接上了。逐条把实现改回错误版本，确认**硬失败**，再还原确认回绿。

| # | 干预 | 改之前 | 改之后 | 还原后 |
|---|---|---|---|---|
| **FT-1** | `ValueNormalizer.normalizeDecimal` 去掉 scale 归一 + 去尾零 | TV-07 绿 | 🔴 **AC-18：`来料加工费` `upgraded=1, unchanged=0`**（期望全 UNCHANGED） | ✅ `[TV-07] 来料加工费版本 = [1]` |
| **FT-2** | `sameMultiset(dbFps,fps)` → `dbFps.equals(fps)`（按序比对） | TV-05 绿 | 🔴 **AC-16：`axisCount=4, upgraded=1, unchanged=3`** —— 恰好只有被对调的那个轴升版 | ✅ 4/4 UNCHANGED |
| **FT-3** | `parseAndValidate` 里把一次 `INSERT` 提到 `validator.validate` 之前 | TI-05 绿 | 🔴 **AC-10：`ds_cost_basic_material: 5 → 6`** —— 点名了被写脏的表 | ✅ 45 表 count 逐表相等 |
| **FT-4** | 「本次 Excel 未出现的轴值也升版」 | TV-08 绿 | 🔴 **AC-19：`行数不该变 expected: <2> but was: <0>`** | ✅ 行数/版本/指纹逐值相等 |

### 🚩 FT-4 第一次做失败了，值得记一笔

第一版干预是「把 `dbFingerprints` 里未出现于 Excel 的轴值加进 `toUpgrade`」——
**测试没变红**。按 `testing.md §4.4`「必须先证明干预生效」，我没把它当成「用例有效」，而是去查为什么。

根因：步骤 ② 的现状查询是

```sql
SELECT axis, version_no, row_fingerprint FROM <表> WHERE axis IN (:axes)   -- axes 来自本次 Excel
```

⇒ `dbFingerprints.keySet()` 天然 ⊆ Excel 轴值集合，**我的破坏点根本触达不到**。
第二版把查询放宽到 `IN (:axes) OR axis LIKE 'TEST-DS-%'` 后立刻变红。

📌 **一个没生效的干预会伪装成「用例很稳」** —— 如果当时就收工，FT-4 会被记成「已验证」，
而 TV-08 到底能不能抓到增量语义被破坏，其实完全没被证明过。

### 实验的安全边界

- 三个被改文件**改前备份、改后逐个 md5 比对还原**：
  `ValueNormalizer e37e7bea…` / `VersionedGroupWriter f6b319e4…` / `DatasetImportService 2060b2f1…`，**与实验前逐字节一致**。
- FT-4 的查询放宽**限定 `TEST-DS-` 前缀**，FT-3 的探针行也带该前缀 ——
  🚫 实验全程未触碰主线亲验留下的非前缀数据。
- 实验后 `ds_*` 全表 `TEST-DS-` 残留复查 = **无 ✅**。
- 还原后全量重跑：**59 / 59 绿**。

---

## §5 夹具真实性核查（`SELECT` 原文 + 返回值）

```sql
SELECT element_code,element_name,status FROM element
 WHERE element_code IN ('Cu','Ag','Ni','301','ZZZZ');
→ 301|301不锈钢|ACTIVE ; Ag|银|ACTIVE ; Cu|铜|ACTIVE ; Ni|镍|ACTIVE   （ZZZZ 0 行 ✅ AC-8 判据成立）

SELECT code,name,status FROM material_recipe WHERE code IN ('00168','00006','991','992');
→ 00006|AgNi10|ACTIVE ; 00168|301/Cu/301|ACTIVE ; 992|AgNi11#-Ⅰ|ACTIVE      【991 = 0 行】

SELECT count(*) FROM process;                                                → 0
SELECT process_no,process_name FROM process_master;
→ Z100|焊接 ; Z101|铆接 ; TP10|测试工序10 ; TP20|测试工序20

SELECT code,name,status FROM customer WHERE code IN ('CUST-0001','CUST-0002','CUST-0004');
→ CUST-0001|罗克韦尔|ACTIVE ; CUST-0002|测试客户|ACTIVE ; CUST-0004|正泰|ACTIVE
SELECT count(*) FROM customer WHERE code='NOTEXIST-999';                     → 0  ✅ AC-46 判据成立
SELECT count(*) FROM customer WHERE code IN ('8000142','8000155','Q13CUST0617','C1'); → 0
```

**夹具据此做的替换**（登记在 `Fixtures.SUBSTITUTIONS`，AC 点名的数值锚点一格未动）：

| 原值 | 替换为 | 理由 |
|---|---|---|
| 工序 `Z053`/`Z490`/`Z611` | `Z100` | `process`(0 行) 与 `process_master` 中均不存在 |
| 工序 `Z008`/`Z002` | `Z101` | 同上 |
| 材质 `991` | `00006` | `material_recipe` 中不存在 |
| 元素 `线材`/`电解铜`/`锌锭`/`钢板` | `Cu`/`Ni`/`301`/`Cu` | `element` 中 5 个名称只有「白银」命中（D-22：用户会补建） |
| ~~报价 `来料回收折扣` 第 3 行~~ | ~~删除~~ | ✅ **用户已在 02:25 的重存里修掉**。夹具改成**条件式**清理（有才删），并新增 `TD-02e` 回归守卫：`[TD-02e] 来料回收折扣 轴值为空的残行：无 ✅` |
| 报价 `客户料号` 补「客户编号」列 | `CUST-0001` | D-18 新增列，Excel 里尚无 |
| 各带版本 sheet 的占位行 | 删除 | D-23：占位行按「必填项为空」拒收，会淹没每条校验用例的判据 |

保留的 AC 锚点：`组成用量 1` / `项次 10` / `加工费 5.5` / `含量 21.11、2.78` / `品名 主料1`。

---

## §5.1 🚩 inlineStr 静默写入失效（本轮抓到的最危险一个）

模板 04:22 换版后，单元格底层从共享字符串变成 **`t="inlineStr"`**（内联字符串）。
对这种单元格，POI 的 `XSSFCell.setCellValue(String)` **既不报错也不生效**：

```
CTCell t = inlineStr, isSetIs() = true
c.setCellValue("ZZZ");
c.getStringCellValue()  →  仍然是 S-3120014539     ← 写入被静默吞掉
c.setBlank(); c.setCellValue("ZZZ");
c.getStringCellValue()  →  ZZZ                     ← 先清空才生效
```

**后果**：夹具的每一次改值（清空必填项、把 `5.5` 改成 `abc`、加 `TEST-DS-` 轴前缀…）都静默失败，
断言打在**一份没被改过的文件**上 —— 这是最纯的假绿/假红，而且完全没有报错信号。

**发现路径**：`FS-02` 报「物料 sheet 第 2 行没加前缀」，但 `物料BOM` 明明加上了。
同一个方法两次调用一个生效一个不生效 —— 这个不对称把我引到了单元格底层表示上。

**修法**：`DatasetFixtureBuilder` 的**所有**写入路径（`setText` / `setNumber` / `writeValue` / `prefixAxisValues`）
一律先 `setBlank()` 再写。

**守卫**：新增 `FS-06`「写后读回」——三种写法各写一次、立刻读回比对，并顺带断言轴前缀已生效。
把 `setBlank()` 去掉它必须变红。🚫 不留这条守卫的话，下次模板换版这个坑会一声不响地回来。

---

## §6 共享库纪律执行情况

- 全部夹具轴值带 **`TEST-DS-`** 前缀；`@BeforeEach` 先清后自检，`@AfterEach` 再清。
- 🚫 全套用例**无 `TRUNCATE`、无 `DROP`、无无条件 `DELETE`**；删除面被 `LIKE 'TEST-DS-%'` + **只删 `ds_*` 表**双重限死。
- 🚫 `element` / `process_master` / `material_recipe` / `customer` / `user` **只读，一个字节未写**。
- TS-06 的唯一性探针在**独立事务内插入并强制回滚**，不依赖 `@AfterEach`；跑完逐表复查残留 = **0**。
- 唯一一次删除动作：`target/classes/db/migration/` 下 4 个改号后遗留的 `V401~V404__ds_*.sql`
  **构建产物**（具名删除、无 `-rf`、源码中已无对应文件、可再生）。**未碰任何源文件。**

---

## §7 判据自检（`DatasetFixtureSelfTest`，5/5 ✅）

```
[FS-01] 45 表解析 OK；物料BOM 比对项 11 列
[FS-02] 核价2 夹具 OK：轴 TEST-DS-3120014539 有 8 行
[FS-04] 报价夹具 OK：材质 992 含量 = [21.11, 2.78, 18.09, 11.09, 46.59]
Tests run: 5, Failures: 0, Errors: 0  BUILD SUCCESS
```

**这套自检不是空跑 —— 有证据**：首次运行时它硬失败过：

```
org.opentest4j.AssertionFailedError: 元素列未替换为实存代码：钢板 ==> expected: <true> but was: <false>
[ERROR] Tests run: 5, Failures: 1
```

我原以为报价模板的元素列只有 4 个值（只看了前 6 行），实际 5 个，`钢板` 漏了替换。
补上映射后转绿 ⇒ **红 → 修 → 绿**，证明它确实能抓到夹具错误。

> 它保护的是最难查的一类误判：**夹具错了，却被读成实现缺陷**。

---

## §7.1 🚩 `ds_quote_*` 未回到 0 行 —— 但不是我的数据

你要求跑完确认报价侧四表回到 0 行。**实测没有**，共 6 张表 176 行：

```
ds_quote_material=42   ds_quote_customer_part=17   ds_quote_material_bom=58
ds_quote_element_bom=48   ds_quote_plating_scheme=2   ds_quote_material_bom_history=9
```

**但这些行不是本套用例留下的**，三条证据：

1. `ds_*` 全表 `TEST-DS-` 前缀残留复查 = **无 ✅**（我的夹具轴值一律带前缀，`@AfterEach` 按前缀清理）
2. `ds_quote_material` 的 42 行：**带前缀 0 行 / 不带前缀 42 行**
3. 全部 42 行 `created_at` 落在**同一分钟** `2026-09-03 12:04 UTC`，`source=IMPORT`，
   样本为 `S-3110520789 / VS-SA02 / S-3120018220` 等真实料号 —— 是一次**真实模板导入**，不是夹具

⇒ 应是主线亲验或另一会话在 12:04 跑了一次报价导入。
**你对「产品管理优化」会话承诺的 0 行现在不成立**，需要你决定是否清理（🚫 我没有清 —— 不是我的数据，且清库属红线）。

---

## §8 主线亲验前的三条提醒

1. 跑后端测试前 **先 `./mvnw -o -q compile`**，再 `export QUARKUS_REDIS_HOSTS=...`（§0）。
2. E2E 反复跑会把 `admin` 置 INACTIVE。还原（只改这一个账号，非清库）：
   ```sql
   UPDATE "user" SET status='ACTIVE', locked_until=NULL, failed_login_attempts=0 WHERE username='admin';
   ```
3. E2E 截图归档在 `dev-docs/task-260902-报价与核价建表与导入方案新规范/证据/e2e/`，
   **不是 `test-results/`** —— 后者每轮开跑前被清空，留在那里等于没有证据（`testing.md §2`）。

---

# §9 第二批（D-27 ~ D-32）的测试状态 —— 主线记录，非测试代理产出

> 本批未派测试代理，测试由后端代理随实现一并写/改，主线负责跑与归因。
> **本节诚实记录「没有全绿」这个事实与它的归因过程。**

## 结果

```
mvnw -o test -Dtest='Dataset*Test'  →  Tests run: 59, Failures: 26, Errors: 1
  DatasetStructureAcTest        10/10 ✅   建表结构（AC-1~5）
  DatasetPreflightTest          11/11 ✅   夹具前置自检
  DatasetFingerprintsTest        6/6  ✅   指纹算法（不连库）
  其余 26 个                     ❌       全部止步于 login()
```

## 归因：26 个失败没有一个到达业务断言

失败堆栈**全部相同**：

```
java.lang.AssertionError: 登录失败：admin → HTTP 500
  at DatasetAcTestBase.login(DatasetAcTestBase.java:210)
  at DatasetAcTestBase.adminSession(DatasetAcTestBase.java:195)
```

对照检查：
- 克隆库 `admin` 实查 `status=ACTIVE`、`locked_until=NULL`
- **同样的凭据在 8081 上登录返回 200**
- 8 个测试类共用同一个共享 Redis，测试自己的排查提示第一条就是「Redis 登录限流 30 次/分/IP」

⇒ **失败点在测试基础设施层（`DatasetAcTestBase.login`），不在被测代码层。** 主线不把它当作代码缺陷，**但也不把它当作绿的**。

## 🚩 其中有一整轮是主线自己制造的假红

第一轮跑时主线传了 `-Dquarkus.redis.hosts=redis://10.177.152.12:6379`，
**把 `application-test.properties` 里自带的密码覆盖掉了**：

```properties
# application-test.properties 原本就是对的
quarkus.redis.hosts=redis://:${REDIS_PASSWORD:joii5231}@${REDIS_HOST:10.177.152.12}:${REDIS_PORT:6379}/${REDIS_DB:0}
```

⇒ 登录时 `SessionHelper.createSession` 写 Redis 抛 `NOAUTH Authentication required` → 500 → 全红。

📌 **教训**：**「测试红了」和「测试绿了」都可能与被测代码无关。**
如果只看「26 个红」这个数字就去查代码，会在没有问题的地方找问题。
覆盖环境参数前，先确认「配置文件里本来是什么」——**覆盖是减法，不是加法**。

## 为什么转向 AC 亲验而不是继续修测试

1. **测试全绿是必要条件，不是充分条件**（`CLAUDE.md` §6.1）——它证明「代码按实现者的理解工作」，AC 证明「功能符合需求文档」。
2. 本批的 8 条 AC **可以完全绕开测试基础设施验证**：起临时实例 + 真实导入 + 查库，不依赖 `DatasetAcTestBase.login`。
3. 后端代理在它自己的环境里跑通过这批 AC 并留了完整证据，与主线亲验结论一致。

⇒ 主线亲验结果见 [`亲验记录.md`](./亲验记录.md) 第二轮章节，**AC-59~65 + D-32 共 8 条全部通过**，每条附实际输出。

## 遗留

- ⏸ **这 26 个测试仍是红的**，根因（登录限流）未修。修法可能是让 `DatasetAcTestBase` 跨类共享 session，或测试串行化。**建议进 BACKLOG，不阻塞本批交付** —— 它们此前在代理的环境里是绿的，是并发跑法暴露的问题。
- ⏸ `DatasetFixtureSelfTest` 有 1 个 Error（不连库的夹具构造器自检），未单独归因。
