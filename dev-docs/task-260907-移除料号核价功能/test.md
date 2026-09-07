# test · 移除主数据维护「料号核价」

> 闸门 A 的**前置产物**。测试代理按本文件执行，产出写进 `test-report.md`。
> AC 原文在 `需求文档.md §③`，本文件只标编号。

---

## 0. 🚨 删除类任务的测试特殊性（先读这段，否则整轮测试都是白测）

删除类任务的所有断言都是「**某某不存在了**」，而**"不存在"是最容易被环境问题伪装出来的结果**：

| 假绿形态 | 怎么骗过你 | 防法 |
|---|---|---|
| **进程级** | 后端没起来 → **所有**路径都返 404 → 「删除成功」全绿 | 每条 404 断言**必须配一条同轮次的 200/400 反向对照**（AC-4 / AC-5 已内建） |
| **别名级** | 本环境 `grep` = `ugrep -I`，把中文注释多的源文件**静默判为二进制返空** → 「零残留引用」全绿 | AC-7 一律用 `/usr/bin/grep -a`；**先做还原实验**：故意在某文件插一行 `PartCostingTab`，扫描必须变红，不变红 = 扫描本身没生效 |
| **空跑级** | 挑了个没数据的料号 → 表格空 → 「渲染正常」全过 | AC-8a/8b 指定料号 `3120014539` 并写明各 sheet 实测行数；**执行时重查一遍**，为空则停下报主线 |
| **范围级** | 用例本身就没覆盖到被删的东西 | §3 的双向追溯矩阵 |
| **陈旧产物级** | 删了 java 源文件但 `target/classes` 留着旧 class → 「删了还能跑」或反过来报 CDI 错 | B-6 冷启动前 `./mvnw -q clean` |

**每一轮"绿"上报前，先回答一句：这个绿是"功能对了"，还是"断言压根没执行"？**

---

## 1. 测试环境与前置

| 项 | 值 |
|---|---|
| 前端 | `http://localhost:5174` |
| 后端 | `http://localhost:8081`（默认 profile → `10.177.152.12:5432/cpq_db_0724`） |
| 探活 | `curl -s --noproxy '*' -o /dev/null -w '%{http_code}'` —— ⚠️ **必须带 `--noproxy '*'`**（本机 `http_proxy=127.0.0.1:7890`，不带会走代理返 502）；⚠️ `/q/health` 返 404 不是健康探针 |
| 角色 | `SYSTEM_ADMIN`（默认）、`PRICING_MANAGER`（AC-11） |
| E2E 夹具料号 | **`3120014539`** —— 基础核价与详细核价**唯一**数据充分的料号（见 §2） |

### 🚨 共享库纪律

- `mvnw test` **直接写共享开发库** `cpq_db_0724`。🚫 **不许跑任何清库型测试**（`CLAUDE.md §3.2`）。
- **AC-12 的 A/B 两次采样之间，不许跑任何写库测试** —— 否则自己毁掉自己的判据。
- ⚠️ 本任务与 **3 条并发任务线**同库：`task-260907-报价导入建单切ds新表`（其 S-7 计划全库清空）、`task-260907-取数配置器补齐`、以及 `BasicDataImportV6Resource.java` 上的其他任务。**执行前先看 `dev-docs/INDEX.md §0.0 活跃主线`。**

---

## 2. 夹具数据（2026-09-07 实查，执行时必须重查）

### 基础核价 · 料号 `3120014539`（`ds_cost_basic_*`）

| sheet | 实测行数 |
|---|---|
| 物料BOM | 8 |
| 元素BOM | 7 |
| 装配工序费 | 4 |
| 来料工序费 | 2 |
| 成品固定费 | 2 |
| 外协工序 | 1 |

### 详细核价 · 料号 `3120014539`（`ds_cost_detail_*`）

| sheet | 实测行数 |
|---|---|
| 物料BOM | 8 |
| 元素BOM | 7 |
| 产能 | 4 |
| 设备折旧 | 4 |
| 生产耗材 | 3 |
| 模具工装 | 2 |

### 🚫 为什么不能换别的料号

- **基础核价** 11 个料号中，除 `3120014539`（6 个非空 sheet）外，只有 `300001` 与 `300012` 达到 3 个非空；其余最多 2 个。
- **详细核价** 5 个料号中，`2120011658` / `2120011659` / `3110520789` **各只有 1 个非空 sheet**（物料BOM 2 行），`S-3120014539` **全空**。
  ⇒ 用它们跑 AC-8b 必然空跑，且**用例照样绿**。

### 电镀方案（`ds_cost_detail_plating_scheme`）

实测 **2 行**，方案号 `A0001`、料号 `2000`（Ni 0.4μm / Au 0.1μm）。数据集下拉里**没有「基础核价」**（该数据集无电镀方案表，后端返 404）。

### 材质（`material_recipe`）

实测 **264 行**；最长材质名 26 字符（`AgNi(0.2)/C10500/AgNi(0.2)`，编号 `00193`）。

### 导入记录（`import_record`）

实测 **173 行**。AC-5 反向对照取任一现存 `id`（如 `417b8558-b8dd-4618-a60f-dd5f9fe19503`）—— **执行时实查，不写死**。

### 核价单（AC-13 / T-13 用，🔒 主线亲验，不归测试代理）

🚨 **2026-09-07 实查推翻了 AC-13 的初稿假设**（已修正需求文档）：

| 我原以为 | 实测 |
|---|---|
| 有 `PENDING` 状态的报价单 | **`quotation.status` 全库无此取值** —— 只有 `DRAFT`(160) / `SUBMITTED`(70) / `COSTING_REJECTED`(9) / `APPROVED`(9) / `SENT`(3) |
| 随便挑张 PENDING 单验核价渲染 | **核价侧已物化的单共 17 张，100% 是 `DRAFT`** |

⇒ 原判据字面上永远无法变绿。现判据：按 `需求文档.md AC-13` 里的 SQL 实查选单，优先真实业务单。
2026-09-07 候选：`正泰 报价单`（`6441b4d2-96da-493e-9085-0daca297b244`，`DRAFT`，`COSTING_CARD` 结构 10911 字节）。

📌 **这条记在这里是给后续任务看的**：`quotation` 的状态机取值与「核价侧物化单全是 DRAFT」这两个事实，
容易被想当然写成 `PENDING`（我就写错了）。写涉及报价单状态的 AC 前先跑一句 `GROUP BY status`。

---

## 3. AC 可追溯矩阵（双向）

| AC | 类型 | 测试项 | 执行方式 | 实现方 |
|---|---|---|---|---|
| AC-1 页签集合 | 单点 | T-1 | E2E（改造后的 `dataset-maintenance.spec.ts`） | F-1 |
| AC-2 默认落点 | 单点 | T-2 | E2E | F-1 |
| AC-3 导入按钮消失 | 单点 | T-3 | E2E | F-2, F-3 |
| AC-4 维护端点 404 + 反向对照 | 单点 | T-4 | curl / RestAssured | B-1 |
| AC-5 导入端点 404 + 反向对照 | 单点 | T-5 | curl / RestAssured | B-2 |
| AC-6 代码物理删除 + 反向对照 | 单点 | T-6 | shell `test -e` | F-2,F-3,F-4,B-1,B-3,B-4 |
| AC-7 零残留引用 | 单点 | T-7 | `/usr/bin/grep -a` + **还原实验** | F-2,F-3,F-6,B-1,B-2,B-4,B-5 |
| AC-8a 基础核价读写序列 | **序列** | T-8a | E2E | F-4, F-5 |
| AC-8b 详细核价读写序列 | **序列** | T-8b | E2E | F-4, F-5 |
| AC-8c 电镀方案只读形态 | **序列** | T-8c | E2E | F-4, F-5 |
| AC-9 切换与刷新 | **序列** | T-9 | E2E | F-1 |
| AC-10 后端冷启动 | 单点 | T-10 | shell | B-6 |
| AC-11 角色边界 | **边界** | T-11 | E2E（迁自 `product-hub-readonly.spec.ts:381`） | F-1, F-4 |
| AC-12 V6 数据零变化 | **边界** | T-12 | SQL A/B | 🔒 **主线亲验** |
| AC-13 核价渲染零回归 | **边界** | T-13 | UI A/B | 🔒 **主线亲验** |
| AC-14 构建与类型 | 单点 | T-14 | shell | F-5, F-7, B-4, B-6 |
| AC-15 E2E 套件不新增失败 | **边界** | T-15 | Playwright A/B | 测试代理 |
| AC-16 公共件内容零变化 | 单点 | T-16 | `git diff` 审阅 | F-4, F-5 |
| AC-17 文档回写 | 单点 | T-17 | `/usr/bin/grep -a` + 人工 | B-7 |

**三类覆盖**：单点 11 条（AC-1~7,10,14,16,17）· 序列 4 条（AC-8a/8b/8c, AC-9）· 边界 4 条（AC-11,12,13,15）。✅ 齐。
**反向**：每个 T-x 都指回一条 AC，无孤儿测试项。

> 🔒 **AC-12 / AC-13 不派给测试代理**：它们要求跨「改动前」「改动后」两次采样，横跨合并动作，不属于任一子代理的工作区生命周期。由主线在亲验环节执行。

---

## 4. E2E spec 处置清单

### 4.1 整份删除（4 个）

| spec | 理由 |
|---|---|
| `tc0712-part-costing-smoke.spec.ts` | 料号核价冒烟专测 |
| `tc0712-edit-flow.spec.ts` | 料号核价编辑流专测 |
| `tc0712-roles.spec.ts` | 料号核价角色专测 |
| `verify260902-ac42.spec.ts` | 整份是「AC-42 料号核价页签结构取证」 |

⚠️ **删除前先跑一遍并记录它们的通过用例名** —— 这是 AC-15 差集判据里「被有意删除的用例」那一项的来源，不记就说不清差集里的用例是被删的还是被弄坏的。

### 4.2 改造（5 个）

| spec | 改哪里 | 改成什么 |
|---|---|---|
| `dataset-maintenance.spec.ts` | `:77` `HUB_TABS` 常量 | 去掉 `'料号核价'`，6 项 |
| | `:164` TE-01 / AC-24 | 断言改为 6 页签集合（AC-1） |
| | `:514~523` TR-01 / AC-42 整块 | **整块删除**（该块专测料号核价零回归，对象已不存在） |
| `dataset-plating-scheme.spec.ts` | `:39` `HUB_TABS_7` 常量 | 改名 `HUB_TABS_6` 并去掉 `'料号核价'` |
| `product-hub-edit-fs.spec.ts` | `:186~196` RG-5 块 | 该块原验「核价侧料号核价仍可编辑」，改为验**基础核价**仍可编辑（AC-8a 的轻量版）。⚠️ `:191` 的注释记着一个实测坑：**不要用 `getByText('料号核价',{exact:true}).first()`**（300s 超时），要用 `getByRole('tab', {name, exact:true})` —— **改造后沿用 `getByRole` 写法** |
| `product-hub-fs.spec.ts` | `:22` | `getByText('料号核价')` → `getByRole('tab', {name:'基础核价', exact:true})` |
| `product-hub-readonly.spec.ts` | `:349` 点击 | 同上 |
| | `:381` PRICING_MANAGER 保存按钮断言 | 载体换成「基础核价」抽屉，语义不变（＝ AC-11） |

### 4.3 不改（1 个）

`repair0805-three-views.spec.ts:263` —— 仅注释里出现「料号核价侧」措辞，非断言、非选择器。

### 4.4 已知的 Playwright 坑（实测，`cpq-playwright-selector-pitfalls`）

四个坑**都表现为 timeout**，极易误判成产品 bug：antd 类名不稳 · 两字按钮渲染成「保 存」（带空格）· 下拉虚拟滚动 · 列表勾选入口。选页签一律 `getByRole('tab', {name, exact:true})`。

---

## 5. 关键测试项的执行细则

### T-7 · 零残留引用（必须先做还原实验）

```bash
PAT='PartCostingTab|PartCostingDrawer|PricingBasicDataImportDrawer|PricingBasicDataMaintenanceResource|PricingMaintenanceService|PricingSheetRegistry|PricingSheetDef|pricing-basic-data|part-costing|importPricing|downloadPricingTemplate|料号核价'
# ① 还原实验：先证明这条扫描真的能抓到东西
echo '// PartCostingTab' >> cpq-frontend/src/main.tsx
/usr/bin/grep -raE "$PAT" cpq-backend/src cpq-frontend/src cpq-frontend/e2e | wc -l   # 必须 ≥1，否则扫描本身没生效
git checkout cpq-frontend/src/main.tsx
# ② 正式扫描
/usr/bin/grep -raE "$PAT" cpq-backend/src cpq-frontend/src cpq-frontend/e2e | wc -l   # 期望 0
```

🚫 **跳过 ① 直接跑 ② 拿到 0 = 白测**（`cpq-agent-tests-stale-server-false-positive`：自己写的验证脚本首次 PASS 也可能是空验证）。

### T-12 · V6 数据零变化（主线执行）

```sql
-- 改动前、改动后各跑一次，逐表比对两个值
SELECT 'unit_price' t, count(*), md5(string_agg(x::text,'|' ORDER BY x::text)) FROM unit_price x
UNION ALL SELECT 'material_bom_item', count(*), md5(string_agg(x::text,'|' ORDER BY x::text)) FROM material_bom_item x
UNION ALL SELECT 'element_bom_item',  count(*), md5(string_agg(x::text,'|' ORDER BY x::text)) FROM element_bom_item x
UNION ALL SELECT 'production_energy', count(*), md5(string_agg(x::text,'|' ORDER BY x::text)) FROM production_energy x
UNION ALL SELECT 'capacity',          count(*), md5(string_agg(x::text,'|' ORDER BY x::text)) FROM capacity x
UNION ALL SELECT 'labor_rate',        count(*), md5(string_agg(x::text,'|' ORDER BY x::text)) FROM labor_rate x
UNION ALL SELECT 'tooling_cost',      count(*), md5(string_agg(x::text,'|' ORDER BY x::text)) FROM tooling_cost x;
```

⚠️ 判据是 **A/B 两次输出逐字相同**，🚫 不是「等于某个固定行数」（共享库并发写入会让固定数字必然过期）。
⚠️ 两次采样之间**不跑任何写库测试**。
⚠️ 若两次不同 → **先查是不是并发任务线写的**（`INDEX.md §0.0`），再下「本次改动动了数据」的结论。

### T-15 · E2E 差集（不是「全绿」）

```
P_before − P_after − {4.1 里被删的用例名} = ∅
```

🚫 **不要求全绿** —— 这 5 个 spec 在改动前就可能有既存失败（共享库夹具漂移，`quotation-flow` 在干净 master 上恒 3 失败是已知形态）。要求全绿会把既存失败误算成本次回归。

### T-16 · 公共件 diff 审阅（人工，不可自动化过关）

```bash
git diff master -- cpq-frontend/src/pages/master-data/shared/
```
逐个 hunk 判定属不属于允许的三类（import 路径 / 文件头注释 / legacy 段删除）。**出现第四类即不通过**，报主线。

---

## 6. 🚫 本轮不做的测试

| 不做 | 理由 |
|---|---|
| 料号核价功能本身的任何功能测试 | 对象已删除 |
| V6 导入链路（`PricingImportService` / P01~P24）的功能测试 | 本任务不动它们；`PricingTemplateServiceTest` 等 3 个既有测试类照常运行即可 |
| 性能测试 | 本任务是纯删除，不引入新的查询或渲染路径 |
| 数据库迁移测试 | 本任务不新增迁移（`backtask.md` B-0） |
| 报价侧 `/v6/quote` 的行为测试 | 归并发任务线 `task-260907-报价导入建单切ds新表`；本任务只用 `GET /{recordId}` 做反向对照 |

---

## 7. 上报格式要求

`test-report.md` 里每条 AC 必须给出**可复核证据**，🚫 禁止只写「✅ 通过」：

- 端点类 → 完整 curl 命令 + 实际 HTTP 状态码 + 响应体首行
- 扫描类 → 完整命令 + 命中数 + **还原实验的结果**
- E2E 类 → 用例名 + Playwright 输出 + 截图路径
- SQL 类 → 完整 SQL + 两次输出的原文
- 空结果（0 行 / 空列表 / 「—」）**一律不算通过**，除非该 AC 本身就是断言"应为空"，且已配反向对照

**每条上报附一句归因**：这条绿是"功能对了"还是"断言没执行"，你怎么排除的后者。
