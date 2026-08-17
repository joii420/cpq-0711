# 测试用例与验收标准 — 核价管理·核价单版本选择（task-0713）

> 权威依据：`需求说明.md` §「需求澄清纪要与设计定稿」+「架构评审落定」+ `api.md`/`backtask.md`/`fronttask.md`。
> **本文是交付验收闸**：每一项都有严格、可验证的通过标准（SQL/API/UI/E2E 证据）。技术总监按本文逐项验收，**任一项 FAIL = 不予合并**。
> 图例：🔴 = 阻断级（必过）；🟡 = 重要；🟢 = 加分。验证方式：`SQL`(直连库) / `API`(curl 端点) / `UI`(页面手测截图) / `E2E`(Playwright) / `CODE`(代码核查)。

---

## 0. 测试数据前置（P0，一切测试的地基）

> 当前库多版本数据 = 0（`material_bom_item` distinct bom_version=1、is_current=false 行=0）。**没有多版本数据 = 版本切换无从测试**。

**T0.1 🔴 造出多版本 BOM 数据**
- 步骤：改 `docs/table/报价测试数据/…V3-罗克韦尔.xlsx`（+核价侧导入文件）某料号的子件构成后重导，触发升版。
- 严格通过标准（`SQL`）：
  ```sql
  -- 至少一个料号有 ≥2 个 bom_version，且旧版 is_current=false 保留、新版 is_current=true
  SELECT material_no, count(DISTINCT bom_version) v, bool_or(is_current) has_cur, bool_or(NOT is_current) has_old
  FROM material_bom_item WHERE system_type='PRICING' GROUP BY material_no HAVING count(DISTINCT bom_version)>1;
  ```
  必须返回 ≥1 行且 `has_cur=t AND has_old=t`。
- **关键**：两个版本的**子件集合必须不同**（低版本子件少、高版本多），否则切换不可观测：
  ```sql
  -- 同一父料号，两个 bom_version 下的 component_no 集合应不同
  SELECT bom_version, array_agg(DISTINCT component_no ORDER BY component_no)
  FROM material_bom_item WHERE system_type='PRICING' AND material_no='<父料号>' GROUP BY bom_version;
  ```
- 记录锚点：`<父料号>`、两个版本号（如 2000/2001）、各自子件集合，写入验收记录供后续用例引用。

**T0.2 🔴 元素多版本数据**：同一料号 `element_bom_item.characteristic` 有 ≥2 个值（版本），子集合不同。

---

## 1. 打开空白修复（P0）

**T1.1 🔴 空白根因证据（修复前）** — `SQL`
- 取一张打开空白的核价单，查其报价行核价值是否 NULL：
  ```sql
  SELECT li.id, (li.costing_card_values IS NULL) AS is_null
  FROM quotation_line_item li WHERE li.quotation_id = '<空白单的quotationId>';
  ```
- 预期：修复前存在 `is_null=t`（证明根因=冻结前 lazy NULL 未物化）。**若不是 NULL，根因另查、不得照本方案往下走**。

**T1.2 🔴 修复：冻结前物化** — `CODE`+`SQL`
- 核查 `CostingFreezeService.createForSubmission` 在 `buildFrozenDto` **之前**调用了 `ensureCardValues(quotationId)`。
- 新提交的核价单：`costing_order.costing_render` 非空且各 lineItem 的 `costingCardValues` 非 NULL。

**T1.3 🔴 打开核价单不再空白** — `UI`（截图）
- 核价管理 → 打开一张 PENDING 核价单 → **报价侧（报价单视图）卡片/Excel 有数据；核价侧（核价单视图）卡片/Excel 也有数据、核价树正常出树**。
- 严格：8 个页签均无"加载中…"残留；核价树带"料号/父料号/版本"系统列。

**T1.4 🔴 历史单（APPROVED/REJECTED）打开不空白且只读缓存** — `UI`+`CODE`
- 打开一张历史核价单：核价侧从 `costing_render` 缓存渲染，**不触发任何 live 重算/V6 查询**（核查无 on-open 重算路径）。即使其 line item 已被重提删除，也能正常展示缓存。

---

## 2. 报价侧完全隔离（P0，最易被破坏，逐字节校验）

**T2.1 🔴 版本切换不改报价侧** — `API`（背靠背对比）
- 步骤：打开核价单记录报价侧数据 → 主树切一个版本 → 再取报价侧数据。
- 严格通过标准：切换前后**报价侧（frozen_dto 派生的报价单视图）逐字节相同**（报价卡片值、报价 Excel、原价合计、报价总金额、折扣率全不变）。
- 验证：对比 `getById` 返回的 `frozenDto` 及报价侧渲染值 hash 一致。

**T2.2 🔴 frozen_dto 写一次永不改** — `SQL`
- 切换版本前后：`costing_order.frozen_dto` 列内容**完全不变**（同一 md5）。版本切换只动 `costing_render`/`costing_total_amount`/override 表。

**T2.3 🔴 不回写报价行** — `SQL`
- 切换版本后：`quotation_line_item.costing_card_values` **不被回写**（保持切换前值/或保持提交时值）。切换只落 `costing_order.costing_render`。

---

## 3. 版本下拉（view_version 约定）（P0）

**T3.1 🔴 仅"有 view_version 列"的页签出下拉** — `UI`+`API`
- 有版本列的页签（子配件/主树、工序、材质、组合工艺、元素）：每个带版本料号出版本下拉。
- 无版本列的页签/全局费率类：**不出下拉**（纯文本或无）。
- `version-options` 端点对无 view_version 组件返回空 `options`。

**T3.2 🔴 下拉版本倒序** — `API`
- `GET /version-options` 返回 `options` 严格**降序**（如 `["2002","2001","2000"]`），`currentVersion` 正确高亮。

**T3.3 🔴 下拉数据源=列出模式、非 expandUncached** — `CODE`
- 核查列出模式走独立轻查（`:versionFilter`→TRUE + partNo 限定取 distinct view_version），**不复用带 30s 缓存的 `expandUncached`**（守 AP-37 串号）。

**T3.4 🟡 元素页签 characteristic 即版本** — `UI`+`SQL`
- 元素页签 `ys_view` 输出 `element_bom_item.characteristic AS view_version`；下拉列出的版本 = 该料号 characteristic 全值集合，倒序。零新增列。

---

## 4. 主树页签切换（P0，含 D2 拓扑变最高风险）

**T4.1 🔴 父节点切版 → 子件集合按新版本变（拓扑变）** — `UI`+`SQL`
- 用 T0.1 的 `<父料号>`，从版本 A 切到版本 B。
- 严格：核价树中该父节点下的子件节点集合**变为版本 B 的子件集**（成员增/减与 T0.1 的 SQL 结果一致）。这是 D2 spike 的核心验证点。

**T4.2 🔴 主树切 → 该产品卡片所有页签重查** — `UI`
- 主树切版本后，**当前产品卡片的每个页签**都刷新（因料号数组可能变）；其余页签数据随新料号集合正确重查。

**T4.3 🔴 父节点不重建/子孙重查** — `UI`
- 切某中间节点版本：其**子树重查/重建**；其**父节点结构不变**（父的其他子节点不动）。

**T4.4 🔴 成本 rollup 到根 + 单据总价更新** — `UI`+`API`
- 切版本后：被切节点成本变 → 一路 rollup 到根 → `costingTotalAmount` 更新（`version-switch` 返回值与 `getById` 一致）。

**T4.5 🔴 其他产品卡片不受影响** — `API`
- 主树切 A 卡片版本后：**B/C… 其他卡片**的核价渲染值逐字节不变。

---

## 5. 非主树页签切换（P0，范围最易越界）

**T5.1 🔴 只重查"本页签该销售料号的整组数据"（非单行）** — `UI`
- 某料号在该页签有多行 → 切该料号版本 → **该料号在本页签的整组行**按新版本重查；**本页签其他料号的行不动**。

**T5.2 🔴 其他页签原始数据不重查** — `UI`+`API`
- 非树页签切某料号版本后：**其他页签的原始数据行不发新查询、不变**（仅因 rollup 使成本/小计/总价重算）。
- 严格区分：**重查(发新SQL取原始数据行) vs 重算(公式/rollup)** —— 其他页签"数据行"不变，但依赖它的"成本数字"可随 rollup 变。

**T5.3 🔴 本页签其他销售料号不动** — `UI`
- 切料号 X 版本，同页签料号 Y/Z 的行与值逐字节不变。

**T5.4 🔴 其他产品卡片不动 + 单据总价更新** — `API`
- 其他卡片不变；该料号成本变 → rollup 到根 → 单据总价更新。

**T5.5 🔴 "触发重查的页签才重算"** — `CODE`+`UI`
- 非树切只重查 1 个页签 → 只该页签重算原始行；未重查页签用缓存值参与整卡重装（保跨页签公式正确），但不重发原始数据查询。

---

## 6. 持久化与生命周期 B1（P0）

**T6.1 🔴 override 落库** — `SQL`
- 切版本后 `costing_order_version_override` 有对应行 `(coid, component_id, part_no, view_version)`，唯一键生效。

**T6.2 🔴 重开即切换后结果** — `UI`
- 切版本后关闭再打开同一核价单：核价侧**直接是切换后的版本/数据/总价**（读缓存，非默认 is_current）。

**T6.3 🔴 B1 新核价单从 is_current 起、不继承** — `UI`+`SQL`
- 场景：核价单 A 切了版本 → 报价单驳回 → beginEdit 改 → 重提生成新核价单 B。
- 严格：B 打开时核价侧**全部回到 is_current 最新版**；`costing_order_version_override` 中 B 的 coid **无任何继承自 A 的行**。A 作为历史单保留自身 override + 缓存可回看。

**T6.4 🟡 复位** — `API`
- 把某料号切回其 is_current 版本（或 DELETE override）→ 该料号该页签回到 is_current，总价随之回算。

---

## 7. 状态门与权限（P0）

**T7.1 🔴 仅 PENDING 可切** — `API`
- PENDING 核价单：财务/管理员切版本成功（200）。
- **APPROVED/REJECTED/WITHDRAWN**：`version-switch` 返回 **403**，override 不落库、缓存不变。

**T7.2 🔴 角色门** — `API`
- 非财务/管理员（如销售）调 `version-switch` → **403**；`editable=false`，前端不显示切换控件（禁用非隐藏，或纯文本版本号）。

**T7.3 🟡 editable 标志正确** — `API`
- `getById.editable === (status==='PENDING' && role∈{PRICING_MANAGER,SYSTEM_ADMIN})`。

---

## 8. 缓存与数据模型（P0）

**T8.1 🔴 新列、不复用 total_amount** — `SQL`+`CODE`
- `costing_order` 存在新列 `costing_render jsonb` + `costing_total_amount numeric`。
- 严格：`costing_total_amount`（核价成本）与 `total_amount`（报价总额，含折扣）**是两列、两值**；核查代码未把核价总价写入 `total_amount`。

**T8.2 🔴 单据总价口径 = Σ成本subtotal 不含 Step3 折扣** — `SQL`+`API`
- `costing_total_amount` = 各 line 核价成本 subtotal 之和；**不含** Step3/lineDiscount 折扣。
- 严格验证：造一张有 Step3 折扣的单，`costing_total_amount` ≠ `total_amount`，且 `costing_total_amount` = Σ核价subtotal（不打折）。

**T8.3 🔴 打开读缓存、不 on-open 重算** — `CODE`
- `getById` 路径**不触发** V6 重算/expand；只读 `costing_render`。（防 BL-0010 首开阻塞）

---

## 9. 性能（P0，禁 N+1、<3s）

**T9.1 🔴 切一次远程查询次数与料号数无关** — `CODE`/日志
- 打印切换端点的远程 SQL 次数：
  - 主树切 = O(driver 组件数)，与卡片料号数**无关**；
  - 非树切 = O(1)（仅该组件 $view 跑一次）。
- 严格：料号从 10 增到 100，切换 SQL 次数**不随之线性增长**。

**T9.2 🔴 单次切换 <3s** — 计时
- 大单（罗克韦尔类多层 BOM）单次 `version-switch` 端到端 <3s。

**T9.3 🔴 expand 层串行、无并行竞态** — `CODE`
- 重查/重算未为提速并行化 expand/公式/快照层（守 `cpq-expand-layer-not-threadsafe`）。

---

## 10. 行数纪律与无风暴（P0，历史雷区）

**T10.1 🔴 AP-51 切 3 次行数/值稳定** — `UI`+`SQL`
- 对同一料号连切 3 次（含切回）：核价树行数、各页签行数、卡片值**收敛稳定**，无累加膨胀（禁 `Math.max(rowCount, baseRows.length)`；受影响 line/页签整体 REPLACE）。

**T10.2 🔴 AP-31/37 无 batch 风暴、无残留"加载中…"** — `UI`(F12 Network)+`E2E`
- 前端切换回调**只用返回值增量刷当前卡片**，**不重新 getById 整单、不触发全量 batch-expand**。
- 切换后 `'加载中' final count = 0`。

---

## 11. versionFilter 宏正确性（P0，D2 核心）

**T11.1 🔴 渲染模式：override 优先、否则 is_current** — `SQL`/单测
- 有 override 的料号 → 取选定版本行；无 override 的料号 → 取 is_current 行。同一 $view 一次查询内两种并存正确。

**T11.2 🔴 列出模式 = 全版本** — `SQL`
- `:versionFilter`→TRUE 时返回该料号全部版本行，供 distinct view_version。

**T11.3 🔴 保存期校验通过** — `API`
- 带 `:versionFilter` 的 $view 过 `SqlViewValidator` dry-run（EXPLAIN），三个参数列在 $view 内真实存在才通过；乱写参数列被拦。

**T11.4 🔴 BL-0028 null 保真** — 单测
- `:__vfPart/:__vfVer` 数组绑定保留 SQL NULL（非字符串 "null"），叶子/空版本 NULL-safe 命中。

**T11.5 🔴 递归 SQL 与页签 $view 同用一份宏语义** — `CODE`
- 主树递归 SQL 与非树 $view 的版本过滤都走 `:versionFilter`，**无两套版本逻辑**（防漂移）。

---

## 12. 回归与协议级 E2E（P0）

**T12.1 🔴 quotation-flow E2E 全绿** — `E2E`
- `npx playwright test e2e/quotation-flow.spec.ts` → 全 `passed`；`'加载中' final count = 0`；8 Tab `'加载中'=0`。附 qf-19 + qf-21~28 截图。

**T12.2 🔴 后端测试在 worktree 内亲跑全绿** — 后端
- 在 worktree 的 `cpq-backend/` 跑 `./mvnw test` 相关用例全绿（守 `cpq-worktree-maven-test-tree`，不得在主仓跑）。

**T12.3 🟡 既有核价/报价渲染无回归** — `E2E`+`UI`
- 未切版本的核价单、报价单详情、比对视图渲染与改动前一致（值中性；可用 golden/背靠背对比）。

**T12.4 🟢 spineKeys 清理无回归**（若做 B8）— `E2E`
- 清 spineKeys 死代码后核价树渲染无变化。

---

## 13. 自检声明（提交/验收必附）

后端：`8081 /api/cpq/components→401 ✅`；`Flyway V3xx success=t ✅`；`version-switch PENDING可切/非PENDING 403 ✅`；`切换SQL次数与料号数无关 ✅`；`mvnw test 绿 ✅`。
前端：`tsc 0错 ✅`；改动 .tsx `Vite 200 ✅`；`quotation-flow E2E 1 passed + 加载中=0 ✅`。

---

## 14. 验收闸总清单（技术总监逐项打勾，任一 🔴 FAIL 不合并）

- [ ] T0 多版本测试数据就绪（父/子集合差异可观测）
- [ ] T1 打开不空白（报价侧+核价侧+历史单）+ 根因证据
- [ ] T2 报价侧逐字节隔离（frozen_dto/报价视图/不回写报价行）
- [ ] T3 下拉：仅版本列页签出、倒序、列出模式非缓存
- [ ] T4 主树切：拓扑变/整卡重查/rollup到根/其他卡不动
- [ ] T5 非树切：整组重查/其他页签数据不动/其他料号不动/总价更新
- [ ] T6 持久化+B1（重开保持/新单从is_current/历史单只读缓存）
- [ ] T7 状态门+权限（仅PENDING+财务管理员，否则403）
- [ ] T8 新列不复用total_amount + 总价不含Step3折扣 + 读缓存不重算
- [ ] T9 <3s + 禁N+1（SQL次数与料号数无关）+ expand串行
- [ ] T10 AP-51行数稳定 + AP-31/37无风暴无加载中
- [ ] T11 versionFilter宏（override优先/列出/校验/null保真/单份语义）
- [ ] T12 E2E全绿 + 后端worktree内测绿 + 无回归
