# 测试执行报告 · task-260909 已有产品抽屉数据源收敛

- 执行方：`cpq-tester`（单片 S1）
- 执行日期：2026-09-09
- 库：`10.177.152.12:5432/cpq_db_0724`（共享开发库）
- 用例总数：**28 条**（接口档 13 / UI 档 15）
- **最终结果：28 / 28 全部通过**（两档各做过一次完整整跑，非逐条补跑拼接）

> 🚫 本报告的断言全部从 `需求文档.md §③` AC 原文派生。写用例期间未读
> `cpq-backend/src/main/java/com/cpq/existingproduct/`、`AddProductModal.tsx`、`existingProduct.ts`。
> 🚫 **测试全绿不构成 AC 达成的证据** —— 测试证明「代码按实现者的理解工作」，
> AC 证明「功能符合需求文档」。本报告供主线亲验时**复核**用，不替代亲验。

---

## 0. 环境与实例正身

| 角色 | 地址 | 正身判据（字段集） | 结果 |
|---|---|---|---|
| 被测前端 | `http://localhost:5203` | `AddProductModal.tsx` 含「客户图号」×2；proxy → 8123 | ✅ |
| 被测后端（改动后） | `http://localhost:8123` | 含 `customerDrawingNo`，无 `has3d`/`thumbnailUrl` | ✅ |
| 对照后端（改动前） | `http://localhost:8081` | 含 `has3d`/`thumbnailUrl`，无 `customerDrawingNo` | ✅ |

**同库确认**（A/B 成立的前提）：`GET /quotations?page=1&size=1` 的 `totalElements` 三方一致 = **130**，
与 `SELECT count(*) FROM quotation` = 130 相符。

**链路正身实测**（同一正泰报价单、同一请求，三个入口）：

```
5203(前端proxy)  total=  2662   customerDrawingNo=True    has3d=False
8123(直连)       total=  2662   customerDrawingNo=True    has3d=False
8081(master)     total=     1   customerDrawingNo=False   has3d=True
```

---

## 1. 接口档：**13 / 13 PASS**（本轮有效结果）

命令：
```bash
cd cpq-frontend
PW_BASE_URL=http://localhost:5203 \
PW_BACKEND_URL=http://localhost:8123 \
PW_MASTER_BACKEND_URL=http://localhost:8081 \
npx playwright test task260909-api-invariants --reporter=list
```

| 用例 | AC | 结果 | 可复核证据 |
|---|---|---|---|
| T0 | 夹具自检 | ✅ | 5 条非只读语句全部被拒（`DELETE`/`UPDATE`/`INSERT`/非法语句/带前导空白的 `MERGE`）；阳性对照 `SELECT 1` → 1 |
| T0b | 守卫证伪 | ✅ | 拿 master 冒充 `changed` → 抛错；同实例按 `unchanged` 断言 → 通过（证明守卫非恒真、非全拒） |
| **T1** | **AC-1（核心）** | ✅ | **`beforeTotal=1` → `afterTotal=2662`（×2662）**；SQL 不变量 = 2662；`before ≤ 5` ✅；第一页 20 行且销售料号逐行非空 |
| T2 | AC-1 | ✅ | `totalElements` = 2662 == `count(DISTINCT material_no)`；`totalPages` = 134 == ceil(2662/20) |
| T3 | AC-2 | ✅ | 罗克韦尔 `total=10`、苏州西门子 `total=0`，各自等于当场实测不变量；西门子 `content=[]` |
| T7 | AC-4 全表对账 | ✅ | 全量取回 2662 行，`customerDrawingNo` 非空 **2651** == SQL `count(DISTINCT material_no) ... IS NOT NULL` = 2651 |
| T9 | AC-5 | ✅ | 见 §1.1 原始响应；三个料号两列均不同且与库逐字段一致 |
| T9b | AC-5b | ✅ | `0028-2609000001` 在 `v_compat_material_master` 命中 0 行（兜底路径确被触发）→ `productName` = `0028-2609000001` ≠ `customerMaterialName` |
| T14 | AC-7③ | ✅ | 20 行**逐行**断言字段集无 `has3d`/`thumbnailUrl`；阳性对照：样本行含 `materialNo`（非空壳） |
| T16 | AC-9 | ✅ | 见 §1.2 |
| T21 | AC-14 | ✅ | `customerProductNo=T260907R-SEL-D40B` → `total=1`，`materialNo=0526-2609000006` |
| T22 | AC-15 | ✅ | 预热 3 次后 5 次耗时 `[169,173,174,197,166]` ms，**中位数 173ms** < 500ms |
| T23 | AC-16 | ✅ | 见 §1.3 |

### 1.1 AC-4 / AC-4b / AC-5 / AC-5b 原始响应

```json
// AC-4 有值态（罗克韦尔 S0004）
{"materialNo":"S0004","customerProductNo":"RW-A004","customerProductNos":["RW-A004"],
 "customerDrawingNo":"DWG-A004","customerMaterialName":"罗克韦尔触桥组件A",
 "productName":"触桥组件A","spec":"Φ12","source":"EXISTING","configProductType":null}

// AC-4 空值态 + AC-5b 品名兜底（正泰 0028-2609000001）
{"materialNo":"0028-2609000001","customerProductNo":"CP-0028-2609000001",
 "customerDrawingNo":null,"customerMaterialName":null,
 "productName":"0028-2609000001","spec":null,"source":"EXISTING"}

// AC-4b 一料号多编号取代表行（正泰 T260907-M1）
{"materialNo":"T260907-M1","customerProductNo":"T260907-CP1",
 "customerProductNos":["T260907-CP1","T260907-CP2"],
 "customerDrawingNo":"DWG-1","customerMaterialName":"客户件A-1",
 "productName":"测试主件A","spec":"SPEC-A","source":"EXISTING"}

// AC-6 来源标签（罗克韦尔，按客户产品编号过滤）
A002              → [{"materialNo":"S0001","source":"EXISTING","configProductType":null}]
T260907R-SEL-D40  → [{"materialNo":"0526-2609000006","source":"CONFIGURED","configProductType":"SIMPLE"}, ...]
2222222           → [{"materialNo":"0526-2609000005","source":"CONFIGURED","configProductType":"COMPOSITE"}]
```

**AC-4b 底层数据**（证明该判据有区分力，非恒真）：
```
customer_product_no | customer_drawing_no | customer_part_name | created_at
T260907-CP1         | DWG-1               | 客户件A-1          | 2026-09-07 09:35:41.32649+00
T260907-CP2         | DWG-2               | 客户件A-2          | 2026-09-07 09:35:41.32649+00
```
两行 `created_at` 完全相同 ⇒ 由第三排序键 `customer_product_no` 决胜，选中 CP1 那一行；
返回的编号 / 图号 / 物料名三者**同源于代表行，无错配**。

### 1.2 AC-9 消失（正）+ 保留（反）

```sql
-- 应消失：sel_product_no 独有（不在 dqcp）的料号
SELECT s.customer_no, s.quote_part_no FROM sel_product_no s
 WHERE NOT EXISTS (SELECT 1 FROM ds_quote_customer_part d
                    WHERE d.customer_no=s.customer_no AND d.material_no=s.quote_part_no);
-- CUST-0001 / 0526-2609000001
-- CUST-0001 / 0526-2609000003
-- CUST-0004 / 0028-2609000012   ← 改动前正泰抽屉里唯一可见的那 1 行
```
接口按 `salesPartNo=<各料号>` 逐个查询 → **全部 0 命中**（= 预期消失，用户已裁决存量不迁）。

反向：`ds_quote_customer_part` 中 `source='MANUAL'` 的 `0526-2609000005 / 06 / 07 / 08`
→ **全部仍可查到**（防"修过头把选配产品也弄丢"）。

### 1.3 AC-16 索引

```
$ SELECT indexname FROM pg_indexes WHERE tablename='ds_quote_customer_part';
 ds_quote_customer_part_pkey
 idx_ds_quote_customer_part_customer_no      ← 本次 V436 新增
 uq_ds_quote_customer_part

$ SELECT version, description, success FROM flyway_schema_history WHERE version='436';
 436 | task260909 idx ds quote customer part customer no | t
```
证伪对照：同一查法打一个不存在的索引名 → 返空（证明该判据非恒真）。

> 📌 AC-16 原文的第二半「查询计划不再是全表 Seq Scan」已于用例编写阶段被证明**双向皆假**
> （既有唯一索引前导列就是 `customer_no` ⇒ 低选择性客户改动前就走 Index Scan = 恒真假绿；
> 正泰 99.4% 选择性 ⇒ 建了索引 PG 也必然继续 Seq Scan = 恒定假红），经用户裁决从 AC 删除。

---

## 2. UI 档：**15 / 15 PASS**

命令（`PW_BASE_URL` 换成 proxy 已指向 8123 的 5203）：
```bash
PW_BASE_URL=http://localhost:5203 PW_BACKEND_URL=http://localhost:8123 \
PW_MASTER_BACKEND_URL=http://localhost:8081 \
npx playwright test task260909-drawer-ui --reporter=list
# → 15 passed (4.7m)
```
> 这是**修完夹具后的一次完整重跑**，不是逐条补跑的拼接结果 ——
> 分片重跑全绿不等于整套全绿，故以本次整跑为准。

| 用例 | AC | 结果 | 证据 |
|---|---|---|---|
| T4 | AC-3 | ✅ | 表头严格等于 `[来源,客户产品编号,客户图号,客户物料名,销售料号,品名,规格]`；`证据/AC-3-列构成7列.png` |
| T5 | AC-4 有值态 | ✅ | S0004 图号列 = `DWG-A004`（与库当场对账）；`证据/AC-4-图号有值态-S0004.png` |
| T6 | AC-4 空值态 | ✅ | `0028-2609000001` 图号列 = `—`（≠ 空/undefined/null）；前置自检确认库中该值为空；`证据/AC-4-图号空值态-*.png` |
| T20b | AC-4b | ✅ | 编号 `T260907-CP1` + 图号 `DWG-1` + 物料名 `客户件A-1` 三者同源；前置自检确认两行图号不同（判据有区分力）；`证据/AC-4b-*.png` |
| T8 | AC-5 | ✅ | S0004/S0001/S0012 三个定点料号两列均不同；`证据/AC-5-*.png` |
| T10 | AC-5b | ✅ | 品名回退为销售料号本身，且 ≠ 客户物料名；`证据/AC-5b-*.png` |
| T11 | AC-6 | ✅ | `A002`→「已有」、`T260907R-SEL-D40`→「选配·单件」、`2222222`→「选配·组合」；`证据/AC-6-来源标签.png` |
| T12 | AC-7① | ✅ | 「⤢ 交互查看」0 个、`img` 0 个、`canvas` 0 个；`证据/AC-7-无3D预览面板.png` |
| T13 | AC-7② | ✅ | 连点 5 行：捕获 793 条请求，`existing-products` **3 条**（阳性对照成立）、`model-configs` **0 条** |
| T15 | AC-8 | ✅ | AC 已订正为结构性证据：8 文件 diff 全 0 + 阳性对照 578 行 + 页面可打开 + 接口 200；`证据/AC-8-3D模型管理无回归.png`（见 §2.1） |
| T17 | AC-10 序列 | ✅ | 第 3 页 20 行；过滤 `S000` → 3 行（== SQL 实测且 >0）逐行含 S000；清空后 total 复原；再翻第 2 页与序列前逐元素相等、无重复；`证据/AC-10-*.png` |
| T18 | AC-11 序列 | ✅ | 加入后 Step2 出现 2 张卡；存草稿后落库 `0028-2609000001,0028-2609000002`，`customer_part_no` 回填 `CP-*`；再次加入 2 旧 + 1 新 → 卡片 3 张、明细 3 行、去重生效；`证据/AC-11-*.png` |
| T19 | AC-12 | ✅ | 打开时本就 0 行 → **点查询后**仍 0 行 → `.ant-empty` 1 个、无「加载中…」、无红色遮罩、分页器 0 个；`证据/AC-12-*.png` |
| T19b | AC-12 阳性对照 | ✅ | 同一判据在有数据客户下：行数 >0、空态 0 个、分页器 >0 —— 证明空态判据非恒真 |
| T20 | AC-13 | ✅ | 料号 `0526-2609000006` 只出现 1 行、带「等 2 个」Tag、hover tooltip 含 `T260907R-SEL-D40` 与 `…D40B`；`证据/AC-13-*.png` |

### 2.1 ✅ T15 / AC-8 —— AC 已订正，改用结构性证据后通过

**原 AC-8** 要求「页面可打开、**列表有数据**、上传与设为当前版本功能正常」。
首轮 T15 红，归因为**数据前提缺失**：

```
model_config      = 0 行        model_config_file = 0 行
GET /api/cpq/model-configs?page=0&size=5
  → 200 {"content":[],"page":0,"size":5,"totalElements":0,"totalPages":0}
```
接口**健康**（200，不是 500/404），但本库里 3D 模型配置**从来没有过数据**。

🚦 **2026-09-09 用户裁决：不删断言，换一条更强的。**
理由：AC-8 要验的是**无回归**，不是**有数据**。「列表有数据」即使通过，也只证明
「这一刻能查出东西」，证明不了「本次没碰它」—— **它一直是条比目标弱的断言**。

**新 AC-8 断言（已实现并通过）**：

| # | 断言 | 实测 |
|---|---|---|
| ① | 3D 管理源码相对 master **一行未改** | **8 个文件 diff 全为 0 行** |
| ② | **阳性对照**：本次确实改过的文件 diff 必须非空 | `AddProductModal.tsx` = **578 行** |
| ③ | 页面可打开，无红色遮罩 / 无「加载中…」滞留 | ✅ |
| ④ | `GET /model-configs` 返回 200（**空包络也算通过**） | ✅ `totalElements=0` |

覆盖的 8 个文件（**比 AC 说的「三个」更全**，超集只会更严）：
```
cpq-backend/.../modelconfig/dto/ModelConfigDTO.java
cpq-backend/.../modelconfig/entity/ModelConfigFile.java
cpq-backend/.../modelconfig/entity/ModelConfig.java
cpq-backend/.../modelconfig/resource/ModelConfigResource.java
cpq-backend/.../modelconfig/service/ModelConfigService.java
cpq-frontend/src/pages/config/ModelConfigManagement.tsx
cpq-frontend/src/services/modelConfigService.ts
cpq-frontend/src/types/modelConfig.ts
```

🚨 **两道防恒真的守卫，缺一条这个断言就不算证据**：

1. **先断言 `git ls-files` 认得这个路径，再断言 diff 为空** ——
   `git diff -- <不存在的路径>` **同样返回空**。路径写错会得到一条恒真的假绿
   （与 `CLAUDE.md §5`「grep 空结果 ≠ 不存在」同族）。实测反向对照：
   `git diff master...HEAD -- cpq-frontend/src/pages/config/NoSuchFile.tsx` → **0 行**。
2. **阳性对照** —— 没有它，「diff 为空」可能只是 git 命令根本没生效（分支名错 / cwd 错 / 参数错）。

🚫 **未真跑上传与「设为当前版本」**（会写公共配置状态，`testing.md §4.3`），
按主线批准降级；该降级已在本报告显式登记，不作为「已验证」。

### 2.2 五条红的归因（第一轮 proxy 事故之后的那轮）

修完后全部转绿，**没有一条是产品缺陷**：

| 用例 | 归因 | 判据 |
|---|---|---|
| T12 | **夹具问题**（我超出 AC 自行加严） | `getByText(/3D\|预览/)` 对 `<tr>` 匹配的是**所有单元格拼接后**的文本，相邻两格 `ZTPERF-00003` + `DWG-BULK-00003` 在接缝处凑出字面量 "3D"。探针实证：「交互查看」0、`img` 0、`canvas` 0 ⇒ AC-7① 本身是满足的 |
| T15 | **数据前提缺失 → AC 已订正** | 见 §2.1；订正后通过 |
| T17 | **夹具问题** | 过滤命中 3 行 ≤ 每页 20 ⇒ antd **整体隐藏分页器**，`readPagerCurrent` 返回 null。这与 AC-12「total ≤ PAGE_SIZE 时分页器不显示」是同一行为。改为「分页器隐藏 或 current===1」+ 用内容证明停在第一页 |
| T18 | **夹具问题**（两处） | ① 列名猜错：`quotation_line_item` 实际是 `product_part_no_snapshot` / `customer_part_no`；② **断言时机错**：加入报价单只改前端状态，实证「加入后 line_item=0，存草稿后=2」⇒ 改为先验 Step2 卡片再存草稿验库 |
| T20 | **夹具问题**（两处） | ① 过滤是模糊匹配，`T260907R-SEL-D40` 连带命中 D40B/D40C ⇒ 返回 2 个不同料号；AC-13 要的是「**该料号**只出现一行」不是「过滤只有一行」；② tooltip 容器实测**不是** `.ant-tooltip-inner`（命中 0），`[role=tooltip]` 命中 1 |

> 📌 五条里 4 条是「我自己写的断言比 AC 更严 / 猜了实现细节」。
> 这正是 §1「不读实现」的代价面：读不到实现就得猜列名、猜路由、猜 DOM 结构。
> **对策不是去读实现，而是让每条猜测在失败时打印实际值**（本套所有定位失败都会打印候选集），
> 使「猜错」与「产品坏」在报告里可区分 —— 本次 5 条红全部在一次探针内定性完毕。

---

## 3. 🚨 两次环境事故（本次交付最有价值的过程证据）

两次的**共同形态**：*验的是「我以为的那个东西」，不是「实际被使用的那条路径」*。
第一次验的是**地址不是身份**，第二次验的是**一个端点不是一条链路**。

### 3.1 事故一：`PW_BACKEND_URL=8098` 被别的 worktree 占用

- **现象**：T1 报 `beforeTotal=1, afterTotal=1`，看起来像「修复没生效」的产品缺陷。
- **诊断路径**：响应字段集比对 → 进程 cwd。
  - 8098 与 8081 响应**逐字节相同**：都含 `has3d`/`thumbnailUrl`，都无 `customerDrawingNo`
  - `/proc/827696/cwd` → `.claude/worktrees/repair-260908-tab-dup-rows/cpq-backend`（03:46 起，另一会话）
- **根因**：主线起的实例没绑上端口（日志有 `Port 8098 seems to be in use`），探活只看 401 就放行 ——
  **401 只证明「有个 Quarkus 在跑、鉴权正常」，证明不了「它跑的是本次改动的代码」**。
- **附带结论**（省掉一轮排查）：worktree 自己的编译产物是**好的**
  （`ExistingProductDTO.class` 里 `customerDrawingNo=1 / has3d=0 / thumbnailUrl=0`，07:31 编译）
  ⇒ 纯粹是「没有进程在服务这份代码」，不是代码没写、也不是构建陈旧。
- **结果处置**：该轮结果**全部作废**，未写入本报告。

### 3.2 事故二：`PW_BASE_URL=5202` 的 Vite proxy 指向 master

- **现象**：UI 档 13 failed / 2 passed，红得像产品缺陷。
- **诊断路径**：先排除夹具嫌疑，再抓包三方对照。
  - 探针实测：填完过滤框未提交 = 8 行 → **回车后 0 行** ⇒ Enter **确实触发了查询**，不是「回车不生效」的夹具坑
  - placeholder 实测 `["客户产品编号","销售料号","品名","规格"]` ⇒ 选择器定位成功，不是选择器问题
  - 浏览器实际请求：`?customerProductNo=&salesPartNo=S0004&...&page=0&size=20` → `total=0`
  - 三方对照：`5202 → total=1 有 has3d`，`8123 → total=2662 有 customerDrawingNo`，`8081 → total=1 有 has3d`
    ⇒ **5202 的响应与 8081 同形态**
- **根因**：`vite.config.ts` 的 `API_TARGET = process.env.VITE_API_TARGET || 'http://localhost:8081'`，
  起 5202 时未设 `VITE_API_TARGET`，默认值把 proxy 指回了 master。
- **结果处置**：该轮 15 条**全部作废**。
  ⚠️ 其中 T4（7 列）、T12（无 3D 面板）"通过"**同样不计入有效结果** ——
  它们只验前端渲染、不依赖后端数据，**在错的后端上照样绿**，属典型假绿。

### 3.3 守卫演进（两次事故各补一层，均带证伪）

| 层 | 判据 | 防的是 |
|---|---|---|
| 原有 | `PW_MASTER_BACKEND_URL !== PW_BACKEND_URL` | 两侧指向同一实例 —— **不够**：两个不同端口可以都不是要测的实例 |
| 🆕 第一层 | 直连 `PW_BACKEND_URL`，按**字段集**验 `changed` / `unchanged` | 事故一（端口被别的会话占用） |
| 🆕 第二层 | 用 `apiContext(PW_BASE_URL)` 走**同一条 proxy 链路**再验一次 | 事故二（页面走的链路与我直连的不是一个后端） |

- 判据用**字段集**而不是端口号：字段集是代码形态的直接证据，端口只是地址。
- **T0b 是永久证伪用例**，不是一次性实验：反向拿 master 冒充 `changed` 必须抛错，
  阳性对照按 `unchanged` 必须通过 —— 两头都堵，防守卫恒真或全拒。

---

## 4. 造数与回收

- 造数前缀 **`T260909-`**，仅新建报价单，**`ds_quote_customer_part` 全程只读**。
- 每轮 `afterAll` 按登记的 id 逐条回收，删前复核前缀（不带前缀则跳过并告警），走应用 `DELETE` 端点而非裸 SQL。

| 口径 | 跑前基线 | 跑后复查 |
|---|---|---|
| `quotation` 总数 | 130 | （见 §5） |
| `T260909-` 前缀单据 | 1（`T260909-selfcheck-*`，前端代理的，非本代理所建） | （见 §5） |
| `ds_quote_customer_part` | 2678 行 / 正泰 2662 料号 | （见 §5） |

**待回收清单：无。** 本次未建任何一次性库，回收不需要 `DROP`。

---

## 5. 收尾核对

| 口径 | 跑前基线 | 跑后复查 | 结论 |
|---|---|---|---|
| `quotation` 总数 | 130 | **130** | ✅ 一致 |
| `T260909-` 前缀单据 | 1 | **1**（仅 `T260909-selfcheck-1788960689215`） | ✅ 我建的全部回收；该张是前端代理的自检单，**非本代理所建，未动** |
| `ds_quote_customer_part` | 2678 行 / 正泰 2662 料号 | **2678 行 / 2662 料号** | ✅ 一致 |
| `ds_quote_customer_part` 写入 | — | `max(created_at)` = 07:21 UTC；有 `updated_at` 的行最新 12:13 UTC | ✅ 均远早于本轮运行窗口（DB `now()` = **15:26 UTC**），**证明本轮未写该表** |

> 📌 最后一行不是「我声称只读」，是**用时间戳证明**：DB 当前时间 15:26 UTC，
> 而该表最后一次写入是 12:13 UTC（3 小时前，且 `updated_by` 是另一个用户 id）。

**全局状态**：本轮未改用户启停用 / 角色权限 / 模板发布态 / 系统开关 / 公共基础数据。
AC-8 的上传与「设为当前版本」按主线批准**只断言按钮可用性，未真跑**（那会写公共配置）。

⚠️ 已登记但不由本套控制的一处：既有 `e2e/global-setup.ts` 会 `UPDATE "user" SET locked_until=NULL`
解锁 admin/alice/bob —— 属既有基础设施的**恢复性**写入，未改动它。

**待回收清单：无。** 本次未建任何一次性库，回收不需要 `DROP`。

**证据归档**（12 张，全部为最终整跑产物，🚫 不在 `test-results/`）：
`dev-docs/task-260909-已有产品抽屉数据源收敛/证据/`
```
AC-3-列构成7列.png            AC-4-图号有值态-S0004.png       AC-4-图号空值态-0028-2609000001.png
AC-4b-多编号取代表行-T260907-M1.png                          AC-5-品名与客户物料名不同.png
AC-5b-品名兜底-0028-2609000001.png                          AC-6-来源标签.png
AC-7-无3D预览面板.png          AC-10-序列-翻页过滤清空翻页.png  AC-11-加入报价单后Step2.png
AC-12-空数据客户空态.png       AC-13-一料号多编号-等N个.png
```
> 🚨 归档纪律的一次实际命中：第一轮（proxy 指错、结果作废）留下的 `AC-7-无3D预览面板.png`
> 在本轮 T12 未通过时**没有被覆盖**，仍以旧 mtime 躺在证据目录里 —— 已按 mtime 逐张核对并删除，
> 现存 12 张 mtime 全部落在最终整跑窗口内。**证据不只要"留得下来"，还要能证明是哪一轮留的。**

---

## 6. 结论与待办

- **接口档 13/13 通过，UI 档 15/15 通过 —— 合计 28/28。**
- **AC-1（核心）已达成**：正泰抽屉可见料号数由 **1 → 2662**，同库 A/B 对照，改动前后各自验明正身。
- **AC-8 已由用户裁决订正**（「列表有数据」→「3D 管理源码 diff 为空」），订正后通过（见 §2.1）。
- 🚫 **本报告不构成 AC 达成的最终证据** —— 主线仍须按 `CLAUDE.md §4.5` 步骤 4 逐条亲验。

### 需要主线跟进的两件事

1. **AC-4b 建议补一句**：代表行**会随过滤条件变化**（实测不过滤时取 `T260907-CP1/DWG-1/客户件A-1`，
   过滤 `customerProductNo=T260907-CP2` 时取 `T260907-CP2/DWG-2/客户件A-2`）。
   两种情况**都是整行同源**（AC-4b 要防的错配未发生），且 `customerProductNos` 始终列全。
   这是「过滤谓词在去重之前生效」（AC-14 硬要求）的必然结果，**判定为符合预期**；
   但闸门 B 若按 AC-4b 原文去搜编号，可能看到 `DWG-2` 而误以为对不上。
2. **`T260909-selfcheck-1788960689215`** 是前端代理的自检单，仍在库里，主线已表示自行回收，本代理未动。
