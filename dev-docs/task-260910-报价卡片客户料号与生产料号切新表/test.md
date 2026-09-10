# test.md · 报价卡片客户料号与生产料号切 ds_* 新体系

> **派生来源**：`任务.md §③ 验收标准` AC-1~AC-14 原文 + `原型图/报价单产品卡片.html` 6 个状态 + `dev-docs/main-api.md` 契约。
> 🚫 **本文件与全部用例代码均未读过** `cpq-backend/src/main/java/**`、`cpq-frontend/src/**`（`testing.md §1`）。
> 选择器词汇取自**既有 e2e 测试代码**（`t260909ft.helpers.ts` / `tsprobe260909-step2.spec.ts` / `docs/E2E测试方法.md §2`）与原型图 —— 两者都在允许范围内。

---

## 0. 🚩 先报三处 AC 问题（写用例时发现，需主线裁决）

### 0.1 AC-8 与 AC-6 在同一场景下互相矛盾

| | 原文 | 在「`customer_part_no` 为 NULL 但 `ds_quote_customer_part` 按 `material_no` 有匹配」时要求什么 |
|---|---|---|
| **AC-6** | 「仅当该列为空时才**回退按 `material_no` 取一条**」 | 取到值 ⇒ 左侧**要**渲染 |
| **AC-8** | 「`customer_part_no` 为空的存量行……头部左侧客户料号区**整块不渲染**」 | 左侧**不**渲染 |

**这不是假设，是现网实存的行**：`QT-20260909-0794` 第 5 行 `mat=0028-2609000014` / `customer_part_no IS NULL`，
而 `ds_quote_customer_part (CUST-0004, 0028-2609000014)` **有 1 行** → `customer_product_no='43543'` / `customer_part_name='11'`。

**原型图消解了矛盾、AC 原文没有**：`原型图` 状态 5 的说明写的是「`customer_part_no` 为 NULL **且客户料号表无匹配**」。

**本方案的处置（按原型图口径，请主线确认）**：
- **AC-8 前置收窄**为「`customer_part_no IS NULL` **且** `ds_quote_customer_part` 无匹配」→ 断言整块不渲染
- **新增 T6.2** 覆盖 AC-6 的回退分支正向态（NULL 但表有匹配 → 左侧显示 `11` / `客户产品编号: 43543`）
- 🚫 不自行把 AC-8 解释成别的意思 —— 若主线裁决相反，本方案要改。

### 0.2 AC-13 的 UI 入口与它断言的 DTO 指向两个不同抽屉

- AC-13 前置写的是「点**「从已有产品添加」**打开抽屉」→ 按 `main-api.md §4.3`，该抽屉的数据源是
  `GET /api/cpq/quotations/{quotationId}/existing-products`，其 `ExistingProductDTO` **没有 `hfPartInfo` 字段**。
- AC-13 断言的 `CustomerPartCandidateDTO.hfPartInfo` 属于 `GET /api/cpq/quotations/customer-part-candidates`
  （`main-api.md §4.1.4`，功能标注「Step2 **批量从基础数据导入产品**候选料号」）= **「批量导入料号」抽屉**，
  与 **AC-14** 的前置是同一个入口。
- `任务.md §⑥ B-4` 改的也是 `CustomerPartCandidateService` ⇒ **契约侧为准，UI 入口按「批量导入料号」抽屉验**。
- 另加 **T13.3 回归**：`/existing-products` 仍 200 且响应形状未因 DTO 字段变化而崩（`任务.md §④ 需回归 3`）。

### 0.3 AC-12 / AC-14 被归在 `S-只读` 片，但它们的前置动作会写库

| AC | 前置动作 | 实际写入面 |
|---|---|---|
| AC-12 | 「改一个输入框的值 → **保存**」 | 写 `quotation_line_item`（含快照重算） |
| AC-14 | 「走批量导入料号抽屉**添加一个产品**」 | 新增 `quotation_line_item`（编辑页 autosave 亦会落库） |

⇒ **本方案把 AC-12 / AC-14 移入 `S-造数` 片**，且**不在基准单 `QT-20260909-0799` 上做写操作**
（它是主线亲验的基准，来回保存会触发快照重算，风险不对称）。改在**自建单**上跑完整序列；
另在 `0799` 上跑 AC-12 的**不写库子序列**（切走切回 + F5），两份证据都留。

---

## 1. 分片计划（对 `任务.md §⑦` 的修订，修订处已标 🔧）

| 片 | 认领 AC | 写入面 | 造数前缀 | 何时可跑 |
|---|---|---|---|---|
| **S-只读** | AC-1 / AC-2 / AC-3 / AC-4 / AC-8 / AC-10 | **零写库**（读现网 `QT-20260909-0799` / `QT-20260909-0794` / `QT-20260909-0652`） | — | 前后端都完成后 |
| **S-造数** | AC-5 / AC-6 / AC-9 / 🔧AC-12 / 🔧AC-13 / 🔧AC-14 | 私有写：`ds_quote_material` · `ds_quote_customer_part` · `ds_cost_basic_material` · 自建 `quotation` | `T260910-` | 后端完成即可开跑（UI 部分需前端） |
| **S-后端单测** | AC-7 / AC-11 | — | — | **不归我**，由 `cpq-backend` 交付 |

### 本片会写什么（逐项登记，`testing.md §4.3`）

1. `ds_quote_material` —— 只插 `material_no LIKE 'T260910-%'` **或** `customer_no LIKE 'T260910-%'` 的行
2. `ds_quote_customer_part` —— 同上
3. `ds_cost_basic_material` —— 只插 `production_no LIKE 'T260910-%'` 的行
4. `quotation` / `quotation_line_item` —— 自建单（`name LIKE 'T260910-%'`），走 `POST /api/cpq/quotations` 正常业务路径

🚫 **本片零 `S-全局`**：不动任何用户启停用 / 角色权限 / 模板发布态 / 系统开关 / 公共基础数据；
🚫 不改任何**既有**行（既有行一律只读）；🚫 不动 `material_customer_map`（在途任务 `task-260909-V6老表退役` 的 `AC-10` 断言它 61 行不变）。

### 断言纪律（共库片，`testing.md §4.5`）

🚫 **禁用全局计数断言**（「`ds_quote_material` 共 2727 行」这类）。
✅ 全部写成**针对性断言**：「`(CUST-0004, S0001)` 这一行的 `production_no` = `300001`」「浮层第 1 行文本 = `300001`」。
唯一的两个数字对照是**一致性不变式**（两边同时变才成立）：`API totalElements == SELECT count(*) FROM quotation`，用于验明后端连的确实是 `cpq_db_0724`。

---

## 2. 🔬 基准数据（2026-09-10 本人实查，非转抄；执行时会**再查一遍**再断言）

```
QT-20260909-0799  DRAFT  客户 正泰 CUST-0004 (id=1f5818d8-b934-44be-b1c3-47df7053cff4)
  唯一行 li=b01b5379-5a18-4cea-ac2c-5f89ffd00777  mat=S0001  customer_part_no=A002  pname=铆钉
ds_quote_customer_part(CUST-0004,S0001) → customer_part_name=示例客户料号 / customer_product_no=A002 / drawing=4332243
ds_quote_material   (CUST-0004,S0001) → production_no=300001
                                        （该表自己的 material_name=铆钉 / spec=Φ50 / dim=1×3 / old_material_no=12133）
ds_cost_basic_material(300001)        → material_name=零件1 / specification=<空> / dimension=3.5×3.5×0.6 / old_material_no=8DLX.550.653
ds_cost_detail_material(300001)       → 0 行
```

### 🔑 这组数据自带一个「取错源就一定露馅」的判别器

`ds_quote_material` 与 `ds_cost_basic_material` 对同一条记录**四列全不同**：

| 列 | 取错（销售侧 `ds_quote_material`） | 取对（核价侧 `ds_cost_basic_material`）= AC-3 期望 |
|---|---|---|
| 名称 | `铆钉` | **`零件1`** |
| 规格 | `Φ50` | **`—`**（空） |
| 尺寸 | `1×3` | **`3.5×3.5×0.6`** |
| 旧料号 | `12133` | **`8DLX.550.653`** |

⇒ AC-3 的五行逐字断言**同时也是数据源正确性的证据**，不需要另写一条「验它读的是哪张表」。
（对照 `testing.md §5.5 ②`：避免「验了一个恒真的东西」。）

### 现网样本（全部实查，执行时动态重取，🚫 不写死）

| 用途 | 样本 | 实查事实 |
|---|---|---|
| AC-1/2/3/4/12 | `QT-20260909-0799` | 见上 |
| AC-9 场景 2（核价侧无明细，现网 2688/2696） | `QT-20260909-0794` 第 2~4 行 `S0004→300021` / `S0008→300031` / `S0012→300041` | 三者在 basic(11 行) 与 detail(5 行) **均 0 命中** |
| AC-9 场景 1（未绑生产料号，现网 31/2727） | `QT-20260909-0794` 第 5 行 `0028-2609000014` | `ds_quote_material.production_no` 为空 |
| AC-6 回退分支（§0.1 新增 T6.2） | 同上第 5 行 | `customer_part_no IS NULL`，但按 `material_no` 命中 `43543` / `11` |
| AC-8（收窄后） | `QT-20260909-0652`（CUST-0058）两行 `CMPV-BOTH-1` / `CMPV-QUOTE-ONLY-1` | `customer_part_no IS NULL` **且** `ds_quote_customer_part` / `ds_quote_material` 均 0 命中 |
| AC-6 精确消歧 | `(CUST-0004, T260907-M1)` → `T260907-CP1` / `T260907-CP2` 两条；`(CUST-0001, 0526-2609000006)` → `T260907R-SEL-D40` / `-D40B` | 一料号对多客户产品编号确实存在 |

### 🚨 AC-5 **必须造数**，用现网数据验它必然通过（`testing.md §5.5` 的「恒为 1 的维度」）

实查：`S0001` 在 `CUST-0001` 与 `CUST-0004` 下**两行的 `production_no` 完全相同（都是 300001）**，
`ds_quote_customer_part` 两行也**逐列相同**。
⇒ 拿现网数据验「客户维度隔离」，**无论后端有没有 `customer_no` 过滤都会绿**。这是零证据判据，必须造出差异。

---

## 3. 用例清单

### 环境正身（每次开跑第一件事，`testing.md §4.2`「探活必须验明正身」）

- **T0.1** 记录被测后端 `pid / cwd / 启动时刻`，`cwd` 必须落在 **worktree 路径内**
  ⇒ 防「测了主工作区旧代码」（共享 5174/8081 服务的是主工作区，拿它验 = 假绿）
- **T0.2** `GET /api/cpq/quotations?page=1&size=1` 的 `totalElements` == `SELECT count(*) FROM quotation`
  ⇒ 证明被测后端连的确实是 `cpq_db_0724`
- **T0.3** 前端页面 `GET /` == 200；后端业务端点未登录 == 401（🚫 `/q/health` 返 404，不是健康探针）

### S-只读片

| 用例 | AC | 步骤 | 断言（逐字，🚫 无形容词） |
|---|---|---|---|
| **T1.1** | AC-1 | 打开 `/quotations/{0799}/edit` Step2 | `.qt-card-header` **左半区**文本序列 = [`示例客户料号`, `客户产品编号: A002`]；且这两个节点在 DOM 中**先于**产品名节点出现 |
| **T2.1** | AC-2 | 同上 | 右半区文本序列 = [`铆钉`, `销售料号: S0001`, `删除`]；🚫 全卡片内 `料号: S0001`（不带「销售」二字前缀）的徽标计数 = **0** |
| **T3.1** | AC-3 | 点 `销售料号: S0001` 徽标 | 浮层标题 == `生产料号`；五行 label 序列 == [`料号`,`名称`,`规格`,`尺寸`,`旧料号`]；值 == [`300001`,`零件1`,`—`,`3.5×3.5×0.6`,`8DLX.550.653`] |
| **T3.2** | AC-3 | 同上 | 浮层内 `生产状态` 出现次数 = **0**；第 1 行值 ≠ `S0001`（核心 bug 反向断言） |
| **T4.1** | AC-4 | 打开页面 | `.qt-product-card` 元素 `getAttribute('style')` 中**不含** `border`；`getComputedStyle().borderTopColor` == `rgb(224, 224, 224)`（= `#e0e0e0`） |
| **T4.2** | AC-4 | 同上 | 卡片头部内文本 `料号信息` 计数 = **0** |
| **T4.3** | AC-4 | `page.on('request')` 全程录 | URL 含 `/material-mappings/match` 的请求条数 = **0**。**配阳性对照 T4.4**，否则这条是零证据 |
| **T4.4** | AC-4 | **阳性对照**：同一监听器下故意 `page.evaluate(fetch('/api/cpq/customers/{id}/material-mappings/match?partNo=A002'))` | 计数器必须变成 **1** ⇒ 证明观察手段抓得到该事件（`testing.md §4.4`：断言「某事没发生」必须配阳性对照） |
| **T8.1** | AC-8 | 打开 `QT-20260909-0652` | 每张卡片头部左半区 `childElementCount` == **0**；整卡片内 `客户产品编号:` 计数 = 0；`—` 不出现在左半区；页面 `text=加载中` 计数 = 0；控制台无 uncaught error |
| **T8.2** | AC-8 | 同上 | 右半区仍有 `销售料号: CMPV-BOTH-1` 徽标 + `删除` 按钮 ⇒ 证明**只塌了左边、卡片没整体不渲染**（防「断言从未执行」：先断言卡片数 ≥ 1） |
| **T10.1** | AC-10 | 打开 `/quotations/{0799}`（详情页） | 头部出现 `示例客户料号` 与 `客户产品编号: A002` |
| **T10.2** | AC-10 | 同上 | 详情页 `.qt-product-card` 无内联 border；无 `生产料号` 浮层触发点；无 `料号信息` |
| **T9.2** | AC-9 场景2 | 打开 `QT-20260909-0794`，点第 2 行卡片 `销售料号: S0004` | 浮层 5 行值 == [`300021`,`—`,`—`,`—`,`—`]（第 1 行有值、其余 4 行 `—`）。🚨 **这是正确行为不是缺陷**（现网 2688/2696） |
| **T9.1** | AC-9 场景1 | 同单，点第 5 行卡片 `销售料号: 0028-2609000014` | 徽标**可点**（`disabled` 不存在）；浮层内容 == 单行 `未绑定生产料号`；浮层内 `料号`/`名称`/`规格`/`尺寸`/`旧料号` label 计数均 = 0（🚫 不摆空骨架） |
| **T6.2** | AC-6 回退分支（§0.1） | 同单第 5 行卡片 | 左半区 == [`11`, `客户产品编号: 43543`] ⇒ `customer_part_no` 为 NULL 时按 `material_no` 回退命中 |
| **T14.2** | AC-14 第2条 | 打开详情页 `/quotations/{0799}` | 「产品分类」徽标仍在（`.qt-template-badge` 至少 1 个且文本不以 `模板:` 开头）；CSS 规则 `.qt-template-badge` 仍存在于 `quotation.css` |

### S-造数片（前缀 `T260910-`，两阶段设计自带阳性对照）

**造数清单**（阶段 1 先只造 decoy，阶段 2 再补本客户行）：

```
阶段1（只造别的客户的行）：
  ds_quote_material        (customer_no='T260910-C2', material_no='T260910-M1', production_no='T260910-P-BBB')
  ds_quote_customer_part   (customer_no='T260910-C2', material_no='T260910-M1', customer_product_no='T260910-CP-B',
                            customer_part_name='T260910-客户料号-B')
  ds_cost_basic_material   (production_no='T260910-P-BBB', material_name='T260910-零件-BBB',
                            specification='T260910-规格-B', dimension='T260910-尺寸-B', old_material_no='T260910-旧-B')
阶段2（补本客户 CUST-0004 的行）：
  ds_quote_material        (customer_no='CUST-0004', material_no='T260910-M1', production_no='T260910-P-AAA')
  ds_quote_customer_part   (customer_no='CUST-0004', material_no='T260910-M1', customer_product_no='T260910-CP-A',
                            customer_part_name='T260910-客户料号-A')
  ds_cost_basic_material   (production_no='T260910-P-AAA', material_name='T260910-零件-AAA',
                            specification='T260910-规格-A', dimension='T260910-尺寸-A', old_material_no='T260910-旧-A')
自建单：POST /api/cpq/quotations  客户=CUST-0004（正泰），name='T260910-<runid>'，
        再经「批量导入料号」抽屉把 T260910-M1 加为产品行（该动作同时服务 AC-13 / AC-14）
```

| 用例 | AC | 步骤 | 断言 |
|---|---|---|---|
| **T5.1** | AC-5 **阳性对照** | 阶段 1 后（只有 `T260910-C2` 有绑定），打开自建单该行 | 浮层 == 单行 `未绑定生产料号`。🚨 若显示 `T260910-P-BBB` ⇒ **后端漏了 `customer_no` 过滤，跨客户串号**（缺陷）。本步同时证明「量具能区分两种结果」 |
| **T5.2** | AC-5 正向 | 阶段 2 后刷新 | 浮层 5 行 == [`T260910-P-AAA`,`T260910-零件-AAA`,`T260910-规格-A`,`T260910-尺寸-A`,`T260910-旧-A`]，🚫 一个 `-B` 值都不许出现 |
| **T5.3** | AC-5（客户料号侧） | 阶段 1 后 → 阶段 2 后各看一次左半区 | 阶段1：左半区**空**（`T260910-C2` 的客户料号不许泄漏到 CUST-0004）；阶段2：左半区 == [`T260910-客户料号-A`, `客户产品编号: T260910-CP-A`] |
| **T6.1** | AC-6 精确消歧 | 给 `T260910-M1` 在 `CUST-0004` 下**再插一条** `customer_product_no='T260910-CP-A2'`/`name='T260910-客户料号-A2'`；把自建单该行的 `customer_part_no` 经 UI/接口置为 `T260910-CP-A2` | 左半区 == [`T260910-客户料号-A2`, `客户产品编号: T260910-CP-A2`] ⇒ **按 `line_item.customer_part_no` 精确命中**，不是随便取一条 |
| **T6.3** | AC-6 确定性排序 | 把 `customer_part_no` 置空后，**连续 3 次**刷新整页 | 三次左半区文本**完全相同** ⇒ 回退路径有确定性排序，不依赖堆表顺序。（弱判据，已知局限见 §5） |
| **T13.1** | AC-13 | `GET /api/cpq/quotations/customer-part-candidates?customerId={CUST-0004}` | 该响应中 `partNo=='S0001'` 的候选：`hfPartInfo.partNo=='300001'` / `partName=='零件1'` / `specification` 为 null 或空 / `sizeInfo=='3.5×3.5×0.6'` / `oldMaterialNo=='8DLX.550.653'`；🚫 `'statusCode' in hfPartInfo` == **false**。**断言前先断言候选列表非空且找得到 S0001**（防空跑） |
| **T13.2** | AC-13 | UI：自建单 → 「批量导入料号」抽屉 → 搜 `S0001` | 该行展示的生产料号详情与 T13.1 逐字一致（截图归档） |
| **T13.3** | 回归（§0.2） | `GET /api/cpq/quotations/{自建单}/existing-products?salesPartNo=S0001` | 200，且响应含 `content` / `totalElements` 包络；不 500 |
| **T14.1** | AC-14 第1条 | 自建单经「批量导入料号」抽屉添加 `T260910-M1` | 添加**当场**（未刷新）卡片头部内以 `模板:` 开头的文本节点计数 = **0**；刷新后仍 = 0 |
| **T12.1** | AC-12 序列 | 自建单：打开 → 「产品」页签任一输入框改值 → 保存 → 切 Step1 → 切回 Step2 → F5 → 重开浮层 | AC-1/2/3 的值在**每个中间态**与最终态逐字不变（每态各断言一次，共 4 次采样）；AC-4 三条在 F5 后仍成立 |
| **T12.2** | AC-12（基准单只读子序列） | `0799`：打开 → 切 Step1 → 切回 Step2 → F5 → 重开浮层（**不改值、不保存**） | 同上断言集；本条**零写库**，用于隔离「是不是保存动作把它弄坏的」 |

### 清理（`finally`，`testing.md §4.5` 私有写片第 ③ 条）

1. 先 `SELECT count(*)` **量化命中面**，再按前缀 `DELETE`：
   `ds_cost_basic_material WHERE production_no LIKE 'T260910-%'`
   `ds_quote_material WHERE material_no LIKE 'T260910-%' OR customer_no LIKE 'T260910-%'`
   `ds_quote_customer_part WHERE material_no LIKE 'T260910-%' OR customer_no LIKE 'T260910-%'`
   删后回读 count == 0。
2. 🚦 **自建报价单不删** —— 删单会级联 `quotation_line_item` 等多张表，命中面说不清；
   按 `testing.md §4.5` 末节登记进 `test-report.md` 的**待回收清单**，交主线在闸门 B 一并报用户批准。
3. 🚨 **若清理 DELETE 被 hook 拒**：**停下报主线**，🚫 不换写法重试（换工具 / 包脚本 / 改 ORM 都算绕路），
   把这批行原样登记进待回收清单。

---

## 4. AC 可追溯矩阵

| AC | 覆盖它的测试 | 层级 | 分片 | 验收证据形式（**归档到任务目录**，🚫 不留 `test-results/`） |
|---|---|---|---|---|
| AC-1 | T1.1 | E2E | S-只读 | `证据/ac1-头部左侧.png` + DOM 文本序列 txt |
| AC-2 | T2.1 | E2E | S-只读 | `证据/ac2-头部右侧.png` + 旧徽标计数 txt |
| AC-3 | T3.1 / T3.2 | E2E | S-只读 | `证据/ac3-浮层五行.png` + 五行逐字 txt |
| AC-4 | T4.1 / T4.2 / T4.3 + **阳性对照 T4.4** | E2E | S-只读 | `证据/ac4-边框与网络.txt`（含 computedStyle、请求计数、对照后计数） |
| AC-5 | T5.1（阳性对照）/ T5.2 / T5.3 | E2E + SQL | S-造数 | `证据/ac5-跨客户隔离.txt` + 两阶段截图 |
| AC-6 | T6.1 / T6.2 / T6.3 | E2E | S-造数 + S-只读 | `证据/ac6-消歧.txt` + 截图 |
| AC-7 | —（`backend-engineer` 交付） | 单测 | S-后端单测 | 不归本片，闸门 B 需其回报 |
| AC-8 | T8.1 / T8.2 | E2E | S-只读 | `证据/ac8-空客户料号.png` + childElementCount txt |
| AC-9 | T9.1 / T9.2 | E2E | S-只读 | `证据/ac9-两种降级.png` ×2 |
| AC-10 | T10.1 / T10.2 | E2E | S-只读 | `证据/ac10-详情页.png` |
| AC-11 | —（`backend-engineer` 交付） | 单测 | S-后端单测 | 不归本片 |
| AC-12 | T12.1 / T12.2 | E2E 序列 | S-造数 + S-只读 | `证据/ac12-序列四态.txt` + 4 张截图 |
| AC-13 | T13.1 / T13.2 (+T13.3 回归) | 接口 + E2E | S-造数 | `证据/ac13-候选契约.json` + 抽屉截图 |
| AC-14 | T14.1 / T14.2 | E2E | S-造数 + S-只读 | `证据/ac14-模板徽标.txt` + 详情页截图 |

**无覆盖的 AC**：无（AC-7 / AC-11 由 `backend-engineer` 认领，本片不重复验，避免「两个人验同一条、结论冲突时没人裁决」）。

⚠️ 依 `testing.md §2` 第 2 条：AC-7 / AC-11 **只有单测覆盖 ⇒ 不算已验收**，闸门 B 汇报须显式标注这一点。

---

## 5. 已知局限（先写下来，🚫 事后不许当成「验过了」）

1. **T6.3 是弱判据**：三次刷新结果相同，可能只是「碰巧每次堆序一致」，不能证明 SQL 里真有 `ORDER BY`。
   真正的强证据是 `AC-7` 的 SQL 日志（后端片）—— 闸门 B 汇报时须由后端片补上 `ORDER BY` 的日志原文。
2. **T5.1 的阳性对照能证明「有客户过滤」，不能证明「过滤写在 SQL 的 WHERE 里」**（也可能是 Java 侧过滤）。
   AC-5 原文断言的是 SQL `WHERE` 含 `customer_no` ⇒ 该字面断言只能由后端片的 SQL 日志闭合，本片标注为**部分覆盖**。
3. **`ds_cost_detail_material` 侧的 union 分支本片验不到**：现网 detail 的 5 行**全部**被 basic 覆盖且逐列相同
   ⇒ 拿现网数据验 union 是 `testing.md §5.5 ②`「恒真的东西」。AC-11 由后端片造冲突数据覆盖，本片不重复。
4. **`quotation-flow.spec.ts` 的 3 条恒定失败**（干净 master 上即失败，夹具单缺产品分类）：
   判是否回归**必须做 A/B 同型对比**（同一命令在 master 上打一次），🚫 不许直接归因本次改动。

---

## 6. 自检口径

- 🚫 共享 `5174 / 8081 / 8091 / 8099` 服务的是**主工作区**代码，拿它验 = 假绿 ⇒ 本片用 **worktree 临时端口**
  （后端 `8211`、前端 `5211`），并在 T0.1 用 `/proc/<pid>/cwd` 证明 cwd 落在 worktree 内
- `curl` 一律 `--noproxy '*'`；`grep` 一律 `/usr/bin/grep -a`（本机 `grep` 是 `ugrep -I`，会把中文源文件静默判为二进制）
- 中文断言注意 UTF-8（`docs/E2E测试方法.md §三`）；两字按钮实际文案可能是「保 存」，用容忍空白的正则
- antd 选择器坑（类名易变 / 下拉虚拟滚动）**都表现为 timeout** ⇒ timeout 一律先判**入口问题（未验证）**，🚫 不默认判产品缺陷
