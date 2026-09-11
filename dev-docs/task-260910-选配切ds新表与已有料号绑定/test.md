# task-260910 · 测试方案 + AC 可追溯矩阵

> 🚨 **本文件是闸门 A 的前置产物**（`task-docs.md §2`），开工前写完，🚫 不是开发期补的。
> 🚫 **测试不得从实现代码派生**（`testing.md §1`）：测试工程师**禁止读** `cpq-backend/src/main/java/com/cpq/configure/`、`.../quotation/service/dsrecord/`、`.../component/service/`、`cpq-frontend/src/pages/quotation/`。信息不足就停下报缺什么，🚫 不许翻实现补齐。
> 可读：`需求文档.md` / `api.md` / 本文件 / `原型图/`。

---

## 1. 分片计划（按**写入面**切，不按 AC 编号 —— `testing.md §4.5`）

| 片 | 写入面档位 | 共库 | 造数前缀 | 认领 AC | 会写什么 |
|---|---|---|---|---|---|
| **S-A** 写入侧落表 | 私有写 | ✅ | **`T260910A-`** | AC-1, AC-2, AC-3, AC-4 | 自己造的新铸料号在 `ds_quote_self_process_fee` / `ds_quote_assembly_fee` 的行；AC-3 需自造外购件料号 `T260910A-OUT1`（`ds_quote_material` 一行，`material_type='外购件'`） |
| **S-B** 读取侧与判据 | 只读 + 少量私有造数 | ✅ | **`T260910B-`** | AC-5, AC-6, AC-7, AC-8, AC-9, AC-21, AC-22 | 仅造对照料号（`T260910B-C1ONLY` 只挂 `CUST-0001`；`T260910B-MULTI` 挂 2 个材质）。其余全只读 |
| **S-C** 直写主表与 `_record` | 私有写 | ✅ | **`T260910C-`** | AC-10, AC-11, AC-13, AC-14, AC-16, AC-18, AC-19, AC-20 | 自己建的报价单及其 `_record` 行、`ds_quote_customer_part` 行 |
| **S-全局** 核价通过与导入回归 | 🚫 **全局写** | ❌ **串行殿后** | **`T260910G-`** | AC-12, AC-15, AC-17, AC-23 | ⚠️ **改报价单状态机**（`SUBMITTED`→`APPROVED`）· **写 `ds_quote_*` 主表 + `_history`**（回填）· **跑导入建单**（写入面极大） |
| **S-D** 树页签重影 🆕 | 私有写（自造边行） | ✅ | **`T260910D-`** | AC-24 | 自造「同边 2 行」的 `ds_quote_material_bom` 数据 + 自建报价单卡片。⚠️ **造的是边行不是料号** ⇒ 必须自造 `P`/`C` 两个料号，🚫 不许在现网已有料号上加重复边（那会污染别人的卡片渲染）|

### 为什么 AC-12 / AC-17 必须归 S-全局

它们要走**核价通过**，那会：① 改报价单状态（`quotation.status`）；② 由 `DsQuoteBackfillService` **写 `ds_quote_*` 主表并归档进 `_history`** —— 主表是**公共基础数据**，不是本片私有。
按 `testing.md §4.5`「拿不准一律归 `S-全局`」+「公共基础数据 ⇒ 不共库、串行殿后」。

### 为什么 AC-15 / AC-23 也归 S-全局

它们要跑**导入建单流程**，写入面覆盖十几张 `ds_quote_*` 表 + `_record` + `quotation_line_*`，无法用前缀圈住。

### S-全局 片会动的全局状态（`testing.md §4.3` 要求登记）

| 全局状态 | 怎么还原 |
|---|---|
| `quotation.status`（`DRAFT`→`SUBMITTED`→`APPROVED`） | 用例自己建的单，`finally` 里不需要还原（本片自造单） |
| `ds_quote_material_bom` / `_element_bom` **主表 + `_history`** | 🚨 **只允许对本片自造料号（`T260910G-` 前缀）产生行**。🚫 **不许 DELETE 别的行**（`CLAUDE.md §3.2` 红线），本片自造行留在库里可接受 |
| 登录态 / RBAC | 用财务或管理员账号做核价通过；🚫 不许改用户状态或角色（那会打红别片） |

🚫 **本片不许清库、不许 TRUNCATE、不许无 WHERE 的 DELETE** —— §3.2 红线，换独立库也不豁免。

---

## 2. 共库并行的两条断言纪律（`testing.md §4.5`）

| 🚫 会被别片打红（假红） | ✅ 分片安全 |
|---|---|
| 「`ds_quote_self_process_fee` 共 N 行」 | 「`WHERE material_no LIKE 'T260910A-%'` 恰好 1 行，且 `operation_no='Z008'`」 |
| 「外购件候选共 5 条」 | 「候选里能找到 `S0011`，且**不含** `T260910B-C1ONLY`」 |
| 「`_record` 共 M 行」 | 「`WHERE quotation_id=<本片自造单>` 恰好 2 行」 |

⚠️ **AC-6 的 `total` 断言是唯一例外**：它必须验「每客户 5 条而不是 10 条」，这是本任务的核心判据。
⇒ **该用例必须自造两个客户下的同名料号对照**，并把断言收窄成「`WHERE material_no LIKE 'T260910B-%'` 在客户 A 下 1 条、客户 B 下 1 条」，🚫 不许直接断言现网的 5。

🔑 **同时守另一条反向纪律**（`testing.md §3`）：**断言前先断言结果非空**。
空列表 / 0 行 / 「—」/「加载中…」**一律不算通过** —— 这是本项目 AP-31 族的既有教训。

---

## 3. AC 可追溯矩阵

| AC | 类型 | 用例 | 分片 | 认领的实现 | 关键断言（可观测） |
|---|---|---|---|---|---|
| AC-1 | 单点 | `TC-A1` | **S-A** | B-1, B-4, B-5 | `ds_quote_self_process_fee` 1 行 + `unit_price` 0 新增 + 卡片「自制加工费」非空 |
| AC-2 | 单点 | `TC-A2` | **S-A** | B-2, B-4 | `ds_quote_assembly_fee` 1 行 `assembly_fee=0` `source='MANUAL'` + `capacity` 0 新增 |
| AC-3 | 单点 | `TC-A3` | **S-A** | B-3, B-4 | 外购件工序进 `_assembly_fee`、**不进** `_self_process_fee`（费用类别变更 D-6） |
| AC-4 | 边界 | `TC-A4` | **S-A** | B-1 | 🔴 **D-15**：**父轴恰好 2 行**（`material_no=P`，`input`=`C1`/`C2`）**+ 子轴各 1 行**（自指），合计 **4 行**。🚫 不许写「整表 2 行」 |
| AC-5 | 单点 | `TC-B1` | **S-B** | B-7, B-10, B-11 | 🔴 **D-16**：以 `CUST-0004` 搜 **`S0013`** 命中 ≥1。可证伪：该料号在 `material_master` 里 `count(*)=0` ⇒ 老 SQL 必然 0 命中 |
| AC-6 | 边界 | `TC-B2` | **S-B** | B-9 | 同料号挂 2 客户 ⇒ 每客户各 1 条、不出双份；阴性：A 客户查不到 B 客户独有料号 |
| AC-7 | 边界 | `TC-B3` | **S-B** | B-7, B-10 | 以 `CUST-0004` 搜 `T260910B-C1ONLY` → **0 条** |
| AC-8 | 单点 | `TC-B4` | **S-B** | B-7, B-8 | 🔴 **D-17**：自造多材质料号返回 N 个材质（N≥2），🚫 不是 1 个。现网交叉验证锚点 `(CUST-0004, S0013)` = 2 材质。🚫 **不许断言「4 材质」**（该粒度下 0 个）|
| AC-9 | 边界 | `TC-B5` | **S-B** | B-7 | 判据是 JOIN 命中：`output_material_type='成品'` 但 JOIN 落空 ⇒ 材质空；声明非 RECIPE 但 JOIN 命中 ⇒ 材质必须带出 |
| AC-10 | 单点 | `TC-C1` | **S-C** | B-12R | 🔴 **断言按 D-14 反转**：`ds_quote_material_bom` 主表 **1 行**（`material_ratio=100.000000000000` · `version_no=1` · `source='MANUAL'`）+ `_element_bom` 主表 2 行 + `_record` 有对应行 + 与导入建单逐字同型 |
| AC-11 | 单点 | `TC-C2` | **S-C** | B-14R, B-22, F-5 | 两页签非空 + 「物料BOM」渲染**树形两层**（根 `<新料号>` + 子 `AgCu90`）且「材料占比（%）」显示 `100` + 元素两行。🔑 **达成路径改为「主表有边 ⇒ 树能递归」**，🚫 不再依赖 `_record` patch |
| AC-12 | **序列** | `TC-G1` | **S-全局** | B-15R | 🔴 **终态断言按 D-14 改**：核价通过后主表**仍 1 行 / `version_no` 仍 1 / `source` 仍 `'MANUAL'`**（回填判 `UNCHANGED`、一行不写）+ `_history` **零新增**。反面实证：`0526-2609000006` 曾升到 `v2 source=QUOTE_BACKFILL`，根因是当时 `_record.material_ratio` 为空 |
| AC-13 | 边界 | `TC-C3` | **S-C** | **B-13R** 🔴 | 🔴 **D-21 方向已反转**：`syncRecordsForFlow` **晚于** 两段物化（= `master` D-40 挂点），且**本次请求结束后**新料号在 `_record` 里就有行（`origin_id` 非空）。🚨 原断言写「早于」是错的。<br>🧪 **阳性对照已由后端做过**：临时改回上移 ⇒ `record_rows=0` 必红 |
| AC-14 | 单点 | `TC-C4` | **S-C** | B-16 | 🟡 **B-16 降为优化项**（本期不做，登记 BACKLOG）⇒ 本条改验**主表侧**：两材质 70/30 ⇒ `ds_quote_material_bom.material_ratio` = `70.000000000000` / `30.000000000000`（直写本来就带）|
| AC-15 | 边界 | `TC-G2` | **S-全局** | （零改动，纯回归）| 🔴 **D-18**：导入建单产生的 `_record` 行（`source='QUOTE_DRAFT'`）行为与改动前**逐字一致**。🚫 **不要去找 `source='IMPORT'`**（实测一行都没有，只有 `QUOTE_DRAFT` 一种）|
| AC-16 | 单点 | `TC-C5` | **S-C** | B-17 | 同单同料号 2 个 line item ⇒ `_record` 无同内容重复；**阳性对照**：改动前必须能复现重复 |
| AC-17 | **序列** | `TC-G3` | **S-全局** | B-17 | 核价通过后主表行数 = `_record` 去重后行数，🚫 不翻倍 |
| AC-18 | 单点 | `TC-C6` | **S-C** | B-18, B-19, B-20, F-3 | 绑定路径六项落库断言（写 2 表 / 4 表零新增） |
| AC-19 | 边界 | `TC-C7` | **S-C** | B-20, F-4 | 重复编号 → 409 `CUSTOMER_PRODUCT_NO_TAKEN`，不新增第 2 行 |
| AC-20 | 边界 | `TC-C8` | **S-C** | B-18, F-3 | 只选绑定不加配件 ⇒ 「下一步」「添加到报价单」均可点 |
| AC-21 | 回归 | `TC-B6` | **S-B** | （零改动） | 同输入再选配 ⇒ `fingerprintMatched=true`、复用原料号 |
| AC-22 | 回归 | `TC-B7` | **S-B** | B-6 | 换序仍命中 + 确认页显示 `（Z100 → Z101）` |
| AC-23 | 回归 | `TC-G4` | **S-全局** | （零改动） | 导入建单的 `_self_process_fee` / `_assembly_fee` / `_record` 行为逐字一致 |
| **AC-24** 🆕 | 单点 | `TC-D1` | **S-D** 🆕 | **B-23** | 🔴 **D-28 新增**：造「同边 2 行」数据 ⇒ `BOM` 页签 `snapshot_rows` **2 行**（修复前 4）+ 逐字节重复组 **0** + 行数 == 该边真实行数（A3 防过度折叠）。<br>🧪 阳性：去重改回去 ⇒ 必须变 4；阴性：`S0004` 单边场景 6 行逐位不变 |

**覆盖自检**：
- ✅ **每条 AC 恰好属于一片，不重不漏**（S-A 4 条 + S-B 7 条 + S-C 8 条 + S-全局 4 条 + **S-D 1 条** = **24 条**）
- ✅ 三类覆盖齐全：**单点** AC-1/2/3/5/8/10/11/14/16/18 · **序列** AC-12/AC-17 · **边界** AC-4/6/7/9/13/15/19/20 · **回归** AC-21/22/23
- ✅ 每条 AC 都有实现认领（AC-21 / AC-23 / **AC-15（D-18 后）** 是「不该变的没变」，无实现任务，由本片回归覆盖 —— 见 `backtask.md` 覆盖表说明）

---

## 4. 必做的证伪实验（`testing.md §4.4`）

新加的守卫/断言**必须做一次证伪**，否则可能是空验证：

| # | 实验 | 怎么证伪 | 不做的后果 |
|---|---|---|---|
| **FT-1** | AC-16 的去重断言 | **把 S-6 的修复改回去**重跑 ⇒ 必须**变红**（复现重复行） | 不变红 = 用例根本没触发重复场景，白测 |
| **FT-2** | AC-6 的客户隔离 | 把 `WHERE customer_no = :customerNo` 去掉重跑 ⇒ 必须**变红**（出双份） | 现网若恰好每料号只挂 1 客户，断言会恒绿 |
| ~~FT-3~~ | ~~AC-12 的 `material_ratio` 非 NULL~~ | 🔴 **随 D-14 + B-16 降级一并作废** —— `material_ratio` 由直写主表天然带上，不再依赖 `_record` 投影补全，没有可回退的干预点 | — |
| **FT-4** | AC-1 的 `unit_price` 零新增 | 把 B-1 的切表改回去重跑 ⇒ 必须**变红** | 否则分不清「没写老表」和「压根没执行到写入」 |
| ~~FT-5~~ | ~~AC-11 的页签非空~~ | 🔴 **随 D-14 作废** —— B-13 保留现状不调换，AC-11 改由主表数据达成，无干预点 | — |
| **FT-6** 🆕 | AC-10 的「与导入逐字同型」 | 同一材质组成分别走**导入**与**选配**各建一单，逐列 diff `ds_quote_material_bom` / `_element_bom`（除 `id`/`quotation_id`/`created_at`/`source`）⇒ 必须**零差异**。<br>反向：把 B-12R 改回写 `_record` ⇒ 选配侧主表 0 行、diff 必红 | 这是 D-14「两侧功能保持一致」的唯一最终判据 |

🚫 **每个实验都要先确认干预真的生效**（`INDEX.md` 记过的教训：改了代码但 `Restarting quarkus` 一条都没有 ⇒ 读数作废）。

---

## 5. 执行环境与自检口径

| 项 | 要求 |
|---|---|
| **库** | ⚠️ `test` profile 现已连 `cpq_db_test`（`BL-0232`），但**默认 profile 仍连 `cpq_db_0724`**。跑测试前用 `\conninfo` 或比对 `totalElements` 确认实际连的哪个库，🚫 不凭配置文件记载 |
| **端口** | 起服务一律用**临时端口**；🚫 **不要占 8081 / 5174**（主线亲验用）。探本机服务一律加 `--noproxy '*'`（本机 `http_proxy=127.0.0.1:7890` 会让 curl 走代理返 502） |
| **后端健康判据** | `/q/health` 返 404 —— **它不是健康探针**（未装 smallrye-health）。判健康看业务端点返 **401** |
| **worktree** | 所有命令在 worktree 路径内执行，🚫 不 cd 出去。`mvnw` 在 `cpq-backend/` 不在根 |
| **grep** | 本环境 `grep` 是 `ugrep -I`，中文多的大文件被静默判为二进制返空 ⇒ **一律 `/usr/bin/grep -a`** |
| **E2E** | 前端协议级改动 + 字段类型变动 ⇒ 按 `docs/E2E测试方法.md` 跑 `quotation-flow.spec.ts` + `composite-product-flow.spec.ts`。⚠️ **干净 master 上 `quotation-flow` 恒 3 失败**（夹具单缺产品分类 ⇒ Step1 下一步禁用），判回归必须 **A/B 同型对比**，🚫 不许误归因 |

---

## 6. 已知会干扰读数的环境缺陷（提前登记，避免误判）

| 缺陷 | 症状 | 规避 |
|---|---|---|
| 测试 profile 的 RBAC 开关自相矛盾 | `@QuarkusTest` 里不带 session 的请求一律 **401 假红** | 在基座 `given()` 里统一带 admin session（参照 `task-260902-主数据与用户导入导出` 的做法） |
| `application-test.properties` 的 Redis 指向不可用地址 | `SessionHelper.createSession` 的 `hset` 抛 `CONNECTION_CLOSED` ⇒ **登录直接 500** | 跑测试时加 `QUARKUS_REDIS_HOSTS='redis://:joii5231@10.177.152.12:6379/0'` |
| `mat_*` 表在本库**从未创建** | 全量 `mvnw test` **永远不可能全绿**（夹具 25 文件 235 处引用它们） | 🚫 **不把「全量绿」当验收门槛**；只跑本任务相关测试类 + 指定回归类 |
