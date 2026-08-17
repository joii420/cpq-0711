# test.md · 报价编辑链路优化与前后端对账（阶段⓪ + 阶段① + 阶段③a）

> 口径以同目录 `需求文档.md` 为准，接口以 `api.md` 为准，前后端落地细节以 `fronttask.md` / `backtask.md` 为准。
> **本文件覆盖阶段⓪（白名单收窄）+ 阶段①（分流 + 对账 + 提交闸门）+ 阶段③a（物化批量写，D17 裁定后的实际范围）**。阶段②④⑤（及裁定不做的 ③b）的用例待各自阶段开工时另行编写（`需求文档.md §2.1` 顺序约束：① 必须最先，是后续所有阶段的安全网）。
> 阶段⓪① 60 条用例（§0~§6，TC-101~TC-217/TC-401~TC-402）已于 2026-08-07 执行完毕，见文末各表「实际结果」列 + `test-report.md`。
> **阶段③a 部分（§7，TC-3A-xxx）是本轮新增，编写时（2026-08-07 二次会话）开发仍在并行进行，未执行任何用例，「实际结果」列全部留空待填**，执行等技术总监审核通过后另行通知。

---

## 0. 测试范围声明

| 阶段 | 本批是否覆盖 | 说明 |
|---|---|---|
| ⓪ 白名单收窄 | ✅ | AC-18 |
| ① 分流+对账+提交闸门 | ✅ | AC-1 / AC-2 / AC-3 / AC-4 |
| **③a 物化批量写**（本轮新增，见 §7） | ✅ | AC-8 / AC-8b / AC-8c |
| ②④⑤（及 ③b，裁定不做） | ❌ | 未开工，AC-5~7 / AC-9~12 / AC-14 / AC-16 / AC-17 / AC-19 不在本批 |
| 跨阶段 | ✅（部分） | AC-13 值中性（⓪① 部分见 §3.10，③a 部分见 §7.3.4；②④ 留待各自阶段追加）；AC-15 回归基线（每阶段都要跑，⓪① 见 §3.11，③a 见 §7.3.10） |

---

## 1. 测试环境与夹具

| 项 | 值 |
|---|---|
| 分支 | `feat/task-0806-edit-reconcile`（worktree `/home/joii/project/cpq/.claude/worktrees/task-0806-edit-reconcile`） |
| 后端 dev | `http://localhost:8081`（共享，8081 跑的是**主工作区**代码，worktree 后端改动需在 worktree `cpq-backend/` 单独跑 `./mvnw test` 验证，不能只看 8081 curl） |
| 前端 dev | `http://localhost:5174`（同上共享约束） |
| 数据库 | `10.177.152.12:5432/cpq_db_0724`（`PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_0724`） |
| 登录账号 | `admin / Admin@2026`（SYSTEM_ADMIN，E2E 推荐）；`alice / Admin@2026`（SALES_REP，权限用例用）|

### 1.1 DRAFT 夹具（AC-1 / AC-3 / AC-4 / AC-13 用）

> ⚠️ **2026-08-07 执行前夹具变更登记**：原定夹具 `QT-20260805-0080` 在技术总监验证 AC-4「清空 pending 后闸门放行」时被误操作放行提交，已变为 `SUBMITTED`（非本组测试造成，用户已确认测试库数据无妨、不回滚）。技术总监提供替代夹具如下，测试工程师执行前已用 SQL 核实两者当前状态均与描述一致：

- 报价单 `QT-20260806-0120`，`quotationId = 08312d5d-99c7-4502-80dd-1eff41c4f345`，`status = DRAFT`（执行前核实：DRAFT ✅）
- 行项 `lineItemId = 5aae535e-6b2c-421c-9036-dc1d83dec852`，`productPartNo = 3120011203`，8 个页签
- 「物料」页签 `componentId = 2db185d6-2b5f-4617-bbc5-6957d6b735e2`
- ⚠️ **执行期二次漂移**：技术总监为取生产态性能基线，在本组用例执行过程中又对该夹具「Ag粉」行发了 6 次 `quote-card-edit`（`材料占比` 定格为 `"0.25"`），`quote_values_at` 最终定格 `2026-08-08 01:01:02`。本组所有断言均按**动态取基线 + 自洽性断言**执行（如 TC-210 用「手算各行之和=响应 subtotalByColumn」而非任何写死数值），未受此漂移影响
- 未使用 `QT-20260807-0127`（执行期 `updated_at` 持续变化，确认为另一并发会话在改，本组测试全程未使用该夹具）

### 1.2 非 DRAFT 夹具（AC-2 用）

- 实际使用 `QT-20260805-0080`（`quotationId = fe75eb4d-ebd2-4997-85ae-3322b7c09471`，`lineItemId = 6caffef0-47f8-447c-8b19-d1165b53270f`，`status = SUBMITTED`）——即 §1.1 原定夹具变为 SUBMITTED 后的状态，技术总监确认「正好可用」于 AC-2 非DRAFT场景
- 原 `QT-20260806-0087`（`quotationId = d8d2943a-2952-4011-9212-38916a8e7aba`，`lineItemId = a30ad9d8-bf5e-4128-b04d-4aea870f910b`）执行前核实同样为 `SUBMITTED`，作为 TC-112 的实际测试对象

### 1.3 回归基线

- 后端全量测试基线：**159 failures + 393 errors**（master，2026-08-06 实测，全部指向共享测试库 `element_price_version` 脏数据毒化事务，见 `需求文档.md §8 K1`）。**判回归一律 A/B 对比失败测试的具体清单，不能只看总数**（见 TC-402）
- E2E `quotation-flow.spec.ts` 在干净 master 上本身有 3 个夹具漂移导致的失败（`BL-0078`），不是本次引入的基线
- `formulaGolden.test.ts`（`cpq-frontend/src/utils/formulaGolden.test.ts`）`amt-002` / `amt-003` 常年红（master 存量），阶段⓪① 不应改变其红/绿状态或错误内容

---

## 2. AC 覆盖对照表（本批范围）

| AC | 用例编号 |
|---|---|
| AC-1 | TC-101, TC-102, TC-103, TC-104 |
| AC-2 | TC-110, TC-111, TC-112 |
| AC-3 | TC-120, TC-121, TC-122, TC-123 |
| AC-4 | TC-130, TC-131, TC-132, TC-133, TC-134, TC-135 |
| AC-13（本批部分） | TC-140, TC-141 |
| AC-15 | TC-150, TC-151, TC-152, TC-401, TC-402 |
| AC-18 | TC-160～TC-166 |
| AC-18b | TC-167 |

---

## 3. 用例列表

### 3.1 阶段⓪ · 白名单收窄（AC-18）

| 编号 | 对应AC/FR | 前置数据 | 步骤 | 期望结果 | 实际结果 | 优先级 |
|---|---|---|---|---|---|---|
| TC-160 | AC-18 / FR-0 | 已登录 `admin`；任一组件（新建或已有草稿态） | 1) 调 `POST /api/cpq/components`（或组件编辑保存接口），字段列表含 1 个 `field_type: "DATA_SOURCE"` 的字段 2) 提交 | `400`，响应体 `message` 含 `DATA_SOURCE`（说明是哪个字段类型非法）且列出的合法值集合**恰好**为 `{BASIC_DATA, INPUT_TEXT, INPUT_NUMBER, FORMULA, FIXED_VALUE, LIST_FORMULA}`（用集合比较，`VALID_FIELD_TYPES` 是 `Set.of()` 遍历顺序不保证，**不得断言顺序**） | PASS：400，message含「Invalid field_type: DATA_SOURCE. Must be one of: [FIXED_VALUE, BASIC_DATA, INPUT_TEXT, LIST_FORMULA, INPUT_NUMBER, FORMULA]」，集合恰6项 | P0 |
| TC-161 | AC-18 / FR-0 | 同上 | 同上，字段类型改为 `"INPUT"`（裸，非 `INPUT_TEXT`/`INPUT_NUMBER`） | `400`，同上断言（合法值集合同 TC-160） | PASS：400，同上集合，字段值INPUT | P0 |
| TC-162 | AC-18 / FR-0 | 同上 | 字段类型分别用合法 6 种各建一次（`BASIC_DATA`/`INPUT_TEXT`/`INPUT_NUMBER`/`FORMULA`/`FIXED_VALUE`/`LIST_FORMULA`） | 6 次均 `200`/`201` 成功创建，无一被拒绝 | PASS：6种合法类型均200创建成功；FORMULA类型需搭配formula_name显式绑定（BL-0098既有规则，非本次引入，非缺陷） | P0 |
| TC-163 | AC-18 | 同上 | 字段类型给不存在的枚举值如 `"FOO_BAR"` | `400`，同 TC-160 断言口径（回归：白名单机制本身未被本次改动破坏） | PASS：400，同一集合，FOO_BAR | P1 |
| TC-164 | AC-18（存量语义，D16 裁定） | 需先人为构造：直接 `INSERT`/`UPDATE` 一条测试组件，`fields` JSON 里塞入 `field_type: "DATA_SOURCE"` 的字段（**因为实测全库该类型存量 = 0，无法用真实存量验证，必须人工构造**，测试后必须清理该测试组件，避免污染共享库） | 1) 直接编辑该组件的**其它**字段（如 `name`），`field_type` 不变，走保存接口 2) 观察保存是否成功 | **保存被 `400` 拒绝**（`D16` 裁定：`validateFields` 遍历整份 `fields` 数组逐个校验，不是只校验本次改动的字段——这是**预期行为，不是缺陷**）。`需求文档 §4 FR-0`「已有数据零影响」的准确含义是「实测三载体 0 命中所以对真实数据无影响」，不是设计上豁免存量含非法值的组件 | PASS：SQL构造DATA_SOURCE字段后改name保存→400，符合D16裁定 | P0 |
| TC-165 | AC-18 | — | 全库三载体复查：`SELECT field_type, count(*) FROM component/frozen structure/template snapshot GROUP BY 1`（SQL 见 `backtask.md B0-4`） | 阶段⓪ 上线前后 `DATA_SOURCE`/`INPUT` 计数均为 **0**（TC-164 构造的测试数据清理后应归零；若非 0 则按 `backtask.md B0-4` 要求**立即回滚**） | PASS：清理测试数据后三载体DATA_SOURCE/INPUT计数=0（清理前一度=1，证明该查询确能探测非零） | P0 |
| TC-166 | AC-18（代码分支保留） | 后端 diff | `git diff` 对比阶段⓪ 提交前后 `ComponentService.java` 及所有含 `DATA_SOURCE`/`INPUT`（裸）字符串字面量的文件 | 除 `VALID_FIELD_TYPES` 定义那一行外，**其余涉及 `DATA_SOURCE`/`INPUT` 的解析/渲染/序列化分支一处未删**（对照 `backtask.md B0-2`） | PASS：git diff 286def1c^..286def1c 仅ComponentService.java改3行（剔除2个字符串字面量），其余13个含DATA_SOURCE/INPUT分支的文件(3后端+10前端)零改动 | P0 |
| TC-167 | AC-18b / FR-0b | 已登录 `admin`；组件管理页面 | 打开「新建组件」抽屉（或编辑已有组件），展开字段类型下拉选项 | 下拉**只展示 6 项**：`BASIC_DATA`/`INPUT_TEXT`/`INPUT_NUMBER`/`FORMULA`/`FIXED_VALUE`/`LIST_FORMULA`，选项列表中**不出现** `DATA_SOURCE` / `INPUT`（裸），因而用户无法选中一个必然导致后端 400 的值（FR-0b 前后端一致性收窄） | PASS：cpq-frontend/src/pages/component/types.ts FIELD_TYPE_OPTIONS 恰6项且不含DATA_SOURCE（diff直接证据）；浏览器实拍下拉因新建组件抽屉需二次进入字段编辑视图未能取到截图证据，以代码证据定PASS | P0 |

### 3.2 阶段① · DRAFT 分流取值（AC-1）

| 编号 | 对应AC/FR | 前置数据 | 步骤 | 期望结果 | 实际结果 | 优先级 |
|---|---|---|---|---|---|---|
| TC-101 | AC-1 / FR-1,2,3 | §1.1 DRAFT 夹具；打开 F12 Network 面板，`Preserve log` 勾选 | 1) 打开 `QT-20260805-0080` 编辑页，切到「物料」页签 2) 记录 Ag粉 行「材料成本」当前显示值 `V0` 与页签「材料成本」列小计当前值 `S0` 3) 修改 Ag粉 行「材料占比」为不同于当前值的新数（如当前 `0.25` 改为 `0.30`），失焦 | ① 失焦后 **DOM 立即更新**（用 Playwright 断言：编辑后 100ms 内 `材料成本` 单元格文本变化，早于对应的 `PUT quote-card-edit` 响应到达时间戳，即 DOM 变化时间戳 < network response 收到时间戳）；② 新显示值 `V1 ≠ V0`；③ 新列小计 `S1` = 该列 6 行当前显示值之和（保留 6 位小数，逐值验证，不依赖历史基准数字）；④ Network 面板中该次编辑触发的 `PUT .../quote-card-edit` 请求**存在但不阻塞渲染**——即请求发出，但页面在收到响应前已完成渲染更新（不是"无请求"，是"无等待"，需求文档写的是「无阻塞请求」非「无请求」） | PASS：人为延迟PUT响应2500ms，DOM在10ms内完成更新（早于PUT完成），V0=0.005093→V1=0.006111 | P0 |
| TC-102 | AC-1 / FR-1 | 同上 | 用 Chrome DevTools Network 节流至 `Slow 3G`，重复 TC-101 步骤 3 | 即使后端响应延迟 >2s，行内值仍在编辑失焦后**立即**（<100ms）更新，不等待网络（验证「取值来源是前端引擎」而非"网络快所以看起来快"） | PASS：同TC-101一次验证（10ms << 人为2500ms慢网延迟） | P0 |
| TC-103 | AC-1 / FR-2 | 报价侧 / 核价侧 / 详情页三处入口 | `grep -an "<ProductCard" src/pages/quotation/*.tsx` 逐个核对 `quotationStatus` 是否透传（对照 `fronttask.md §2.1` AP-41 验收方式） | 三处调用点（报价侧 `QuotationStep2.tsx` / 核价侧 / `ReadonlyProductCard.tsx`）**均**传入 `quotationStatus`，命中行号需贴进 `test-report.md`；**任一处漏传即判定 AP-41 复发，P0 缺陷** | PASS：3处调用点均透传quotationStatus——QuotationStep2.tsx:4553(COSTING)/4610(QUOTE)，ProductDetailViews.tsx:239(详情页ReadonlyProductCard) | P0 |
| TC-104 | AC-1 / FR-3 | DRAFT 夹具新增一行（如「物料」页签手动添加一条新料件行，此时后端快照尚未覆盖到该 rowKey） | 1) 新增行后立即编辑该行任一 FORMULA 依赖字段 2) 观察该行公式列取值 | 该行走本地引擎兜底正常算出值（不因"快照没有这个 rowKey"而报错或显示为空），且**不计入对账差异**（§3.5 边界表第 2 行，FR-3 兜底不可删） | N/A：该DRAFT夹具「物料」组件为driver-bound，无「添加行」入口按钮命中，属该组件类型既有约束非缺陷；本地引擎兜底逻辑已由TC-174代码验证 | P0 |

### 3.3 阶段① · 非 DRAFT 只读一致性（AC-2）

| 编号 | 对应AC/FR | 前置数据 | 步骤 | 期望结果 | 实际结果 | 优先级 |
|---|---|---|---|---|---|---|
| TC-110 | AC-2 / FR-1 | §1.2 非 DRAFT 夹具 `QT-20260806-0087` | 1) `SELECT quote_card_values FROM quotation_line_item WHERE id='a30ad9d8-...'` 取出 `formulaResults` 中「物料」页签全部字段值（记为快照基准 J） 2) 打开该单编辑/查看页「物料」页签 3) 逐字段截图/取 DOM 文本 | 页面显示的每一个公式列值与 J 中对应 `rowKey+fieldName` 的值**逐字节相同**（字符串级比较，非四舍五入后近似），**不允许**因为前端引擎重算产生哪怕最后一位的偏差 | PASS：编辑页显示值取自同一份quote_card_values快照JSON（非独立重算），与DB SELECT基准一致 | P0 |
| TC-111 | AC-2 / FR-1（AP-50 回归） | 同上 | 同一料号同一页签，分别在「报价侧编辑页」「核价侧」「详情页只读 `ReadonlyProductCard`」三处打开并读取同一字段值 | 三视图数值**完全一致**（AP-50 三视图核对，`fronttask.md §7` 自检项） | 值一致性PASS；但发现缺陷D-01（详见缺陷清单）：非DRAFT物料页签<input>元素DOM未disabled/readOnly，用户可打字但PUT被400拒绝后前端未回滚显示值/未提示只读，与api.md「显示只读提示」承诺不符（经查为既有代码路径，非本次回归） | P0 |
| TC-112 | AC-2（回归） | 同上，尝试编辑 | 对非 DRAFT 单据发起 `PUT quote-card-edit` 请求 | `400`「非 DRAFT 不可编辑」（`api.md` 既有错误码，回归验证阶段①改动未放开非 DRAFT 编辑限制）；前端不应把该次失败误判为对账差异（该单据本无编辑动作） | PASS：400「编辑失败：非草稿态或数据缺失」 | P0 |

### 3.4 阶段① · 对账报警（AC-3）

> 制造分歧的方法：**不直接改共享 DB 数据**（避免污染夹具/影响并发会话），改用 Playwright `page.route()` 拦截 `PUT .../quote-card-edit` 的响应，篡改 `data.quoteCardValues` 里目标字段的值，模拟"后端算出的值与前端不同"的场景。此法可重复、无副作用、不依赖数据漂移。

| 编号 | 对应AC/FR | 前置数据 | 步骤 | 期望结果 | 实际结果 | 优先级 |
|---|---|---|---|---|---|---|
| TC-120 | AC-3 / FR-4 | DRAFT 夹具；Playwright route 拦截脚本已就绪 | 1) 拦截 `PUT .../quote-card-edit`，把响应体里 Ag粉 行「材料成本」字段的值改写为**前端当前值 × 100**（模拟历史故障"材料占比 0.25 vs 25"的量级） 2) 编辑该行任意可编辑字段触发防抖对账 | ① 对账触发后该单元格出现 ⚠ 角标；② hover/点击 tooltip 展示内容**同时含**：前端值、后端值（改写后的值）、双方输入摘要（D2 要求，至少含行键字段 + 该公式引用到的字段，如「材料占比」「组成数量」「元素单价」） | PASS：篡改材料成本×100后⚠图标出现（命中1），tooltip=「前后端算值不一致\n前端…输入：…\n后端…输入：…」含双方值+输入摘要 | P0 |
| TC-121 | AC-3 / FR-4（D4 阈值） | 同上，改为篡改成**仅最后一位小数不同**（如原值 0.526759 改为 0.526760，差值 0.000001，小于 `DISPLAY_SCALE=6` 阈值） | 同上触发对账 | **不报警**（D4：按 `DISPLAY_SCALE=6` 阈值判定，差值 < 阈值视为一致，避免浮点末位噪音）——这是**负例**，验证阈值机制生效而非逢差必报 | 未执行：时间预算内未做“仅末位小数差”的精确阈值边界构造；已用TC-122(无篡改0误报)+TC-120(显著差异必报)间接验证阈值机制两端，DISPLAY_SCALE=6精确边界未专项验证 | P1 |
| TC-122 | AC-3（负例） | 不做任何篡改，正常编辑 | 编辑任一字段，等待对账完成 | 无 ⚠ 角标出现（真实无分歧场景下不误报，避免"逢编辑必报警"的噪音污染） | PASS：无篡改正常编辑后⚠命中数=0，无误报 | P0 |
| TC-123 | AC-3 / FR-5 | 同 TC-120 场景 | 触发差异后，用 Network 面板观察 `POST .../reconcile-report` | 该请求被发出（fire-and-forget，不阻塞 UI），请求体 `diffs[]` 含 `componentId/tabName/rowKey/fieldName/frontendValue/backendValue/frontendInputs/backendInputs`，其中 `frontendInputs`/`backendInputs` **只含行键字段+该公式引用到的字段**，不应包含该行全部字段（D2 强制要求，`api.md` API-5） | PASS：reconcile-report请求体含全部8个必需字段（componentId/tabName/rowKey/fieldName/frontendValue/backendValue/frontendInputs/backendInputs），frontendInputs/backendInputs仅含行键字段+公式引用字段（未整行倾倒） | P1 |

### 3.5 阶段① · 提交闸门（AC-4）

| 编号 | 对应AC/FR | 前置数据 | 步骤 | 期望结果 | 实际结果 | 优先级 |
|---|---|---|---|---|---|---|
| TC-130 | AC-4 / FR-6 | DRAFT 夹具，先用 TC-120 手法制造一处未消解差异并确认已上报 `reconcile-report` | ⚠️ **执行期间不得触发后端热重载/重启**（进程内 Map 存差异状态，重启会静默清空，见 §5.2 附注）。立即调用 `POST .../{quotationId}/submit` | `409`，响应体 `data.reason = "RECONCILE_PENDING"`，`data.conflicts[]` 至少含 1 条，字段包含 `lineItemId/productPartNo/tabName/rowKey/fieldName/frontendValue/backendValue`（对照 `api.md` API-3 示例结构） | PASS：409，data.reason=RECONCILE_PENDING，conflicts[]含lineItemId/productPartNo/tabName/rowKey/fieldName/frontendValue/backendValue，结构与api.md API-3示例一致 | P0 |
| TC-131 | AC-4 | 同上，提交被拒后 | 前端捕获 409 后的表现：应弹出 **Drawer**（不是 Modal，`CLAUDE.md` UI 规范）列出差异清单，提供"定位到该格"跳转 | Drawer 弹出且内容与响应 `conflicts[]` 一致；点击"定位"能跳转/高亮到对应页签+行+列 | PASS：409后弹出Drawer（非Modal，class=.ant-drawer非.ant-modal-content），标题「提交校验未通过：前后端算值不一致」，含conflicts表格(料号/页签/行/列/前端值/后端值)+「定位到该格」操作列 | P0 |
| TC-132 | AC-4 | 差异已消解（下一轮对账上报 `diffs: []`，或该 lineItem 的 `quote_card_values` 被整份重建） | 消解后再次调用 `submit` | 正常提交成功（不再返回 `RECONCILE_PENDING`）——验证"消解条件生效"（`backtask.md §4.1` 消解条件①） | PASS：上报diffs:[]消解后再次submit返回200 | P0 |
| TC-133 | AC-4（正例回归） | 全新 DRAFT 夹具或复制一份，未做任何编辑 | 直接提交 | 正常提交成功（`200`），不应因为"从未对账过"而被误判为 `RECONCILE_PENDING`（无编辑=无差异状态=闸门应放行，回归既有提交流程不受阶段① 影响） | PASS：全新DRAFT克隆（POST copy产出，从未编辑）直接submit返回200，未被误判RECONCILE_PENDING | P0 |
| TC-134 | AC-4 / D15（`WRITE_IN_FLIGHT` 阶段①恒 false） | DRAFT 夹具；用 Playwright route 延迟 `PUT .../quote-card-edit` 响应（如延迟 5s），模拟"编辑请求仍在网络传输中" | 1) 编辑一格，**不等待**该请求返回 2) 在编辑请求仍处于 pending 状态时立即调用 `submit` | 阶段① 前端仍 `await` 编辑请求（异步化是阶段②），**不存在**真正的"在飞写"物理状态；`assertLineSettled` 的 `WRITE_IN_FLIGHT` 条件**恒为 `false`**——即便模拟了网络延迟，提交请求本身要等前端 `await` 完成才会发出，所以**不会**、也**不应该**因为 `WRITE_IN_FLIGHT` 被拒（D15）。若观察到因 `WRITE_IN_FLIGHT` 被拒，判定为**过度实现**（阶段①提前引入了阶段②才该有的队列状态），记入缺陷 | PASS（代码验证）：SubmitGateService.isWriteInFlight()源码硬编码return false（注释明确「阶段①恒false，是留给阶段②接线的占位点」），不存在因WRITE_IN_FLIGHT被拒的可能，未观察到过度实现 | P1 |
| TC-135 | AC-4 / D15（上报未完成就提交） | DRAFT 夹具 | 1) 用 Playwright route 拦截并**永久挂起**（不返回）`POST .../reconcile-report` 请求 2) 编辑一格触发防抖对账（对账在前端算完后会尝试上报，但上报请求被挂起、无法完成） 3) 在上报请求仍挂起、未收到 `202` 的情况下，手动调用 `submit`（**违反 D15 定义的"前端提交前必须先完成一次对账上报"的前端串行保证**，模拟用户手速快 / 前端该处保证有 bug 的场景） | 后端 `assertLineSettled` 只能看到**它 Map 里已经落地的最后一次成功上报**——若这是编辑前的旧一轮上报（不含本次编辑产生的新差异，或本身为空），提交会被**放行**（不会因为"有一次上报正卡在路上"而拒绝，因为阶段① 无法感知"正在发生但未完成"的上报）。**这是 D15 明确的已知限制**：真正的一致性保证依赖前端"先完成上报再提交"的串行纪律，不是后端强制；测试目的是**验证并记录**这个边界行为，而非断言"应该被拒绝"。若前端串行保证被绕过（如本用例的人为挂起），提交可能带着未被后端感知的新差异通过——需在 `test-report.md` 中登记为**已知限制**，不按缺陷处理 | 已知限制确认（非缺陷）：等价于TC-201变体b场景——submit在reconcile-report未落地前读取Map（此时为空/旧值）即放行，与D15裁定的边界行为一致，已记入test-report.md | P1 |

### 3.6 边界与空态（`fronttask.md §6`，6 类逐一覆盖）

| 编号 | 对应 | 前置数据 | 步骤 | 期望结果 | 实际结果 | 优先级 |
|---|---|---|---|---|---|---|
| TC-170 | 边界①（非DRAFT） | 同 TC-110 | 已在 TC-110/TC-112 覆盖：非 DRAFT 打开显示快照且逐字节一致，对账仍跑但不弹提交闸门（无编辑动作、无提交按钮） | 参见 TC-110/TC-112；额外验证：非 DRAFT 页面**没有可提交的入口**，因此即使对账有差异也不会触发 TC-131 的 Drawer（没有触发提交这个动作） | PASS：值一致性+编辑拒绝已由TC-110/TC-112验证；「非DRAFT无提交入口」未做UI层专项验证（低风险，逻辑上SUBMITTED单据渲染层本就不显示提交按钮，isDraft门控已在代码中确认，如TC-131脚本证实isDraft变量存在且被消费） | P1 |
| TC-171 | 边界②（新行快照缺失） | 同 TC-104 | 已在 TC-104 覆盖 | 参见 TC-104 | 参见TC-104（N/A，同一夹具物料组件为driver-bound无新增行入口，未能触发「快照缺失新行」真实场景；本地引擎兜底逻辑已由代码路径验证，见TC-174） | P1 |
| TC-172 | 边界③（`__cardValueFailed` 哨兵） | 构造该料号卡片数据待重算场景（如临时使某 componentId 对应的快照缺失/损坏，具体触发方式待与后端确认："该料号卡片数据待重算"提示当前由哪个条件触发） | 打开该卡片 | 保持现有"该料号卡片数据待重算"提示文案不变；**跳过对账**（Network 面板不应出现因该卡片触发的差异上报请求，或上报但后端/前端逻辑视为无可比对象不生成 ⚠） | 未执行：需专项构造__cardValueFailed哨兵触发条件（卡片数据待重算态），时间预算未覆盖，且触发条件本身待与后端确认（test.md原文亦标注「具体触发方式待确认」） | P2 |
| TC-173 | 边界④（driver 返 0 行，AP-38 回归） | 找一个 `driver=mat_xx` 类且已知返回 0 行的组件/单据（或临时构造） | 打开该页签 | `BASIC_DATA` 字段显示 `—`，**不降级读 `globalPathCache`**（回归 AP-38 既有口径，验证阶段① 改动未破坏它） | 未执行：未找到/未构造已知返回0行的driver=mat_xx夹具，时间预算未覆盖；AP-38既有口径本次阶段①代码零接触（BASIC_DATA渲染分支未在286def1c diff中出现） | P1 |
| TC-174 | 边界⑤（编辑请求全部失败/离线） | DRAFT 夹具；用 Chrome DevTools 切 `Offline` 或用 Playwright route 让 `quote-card-edit` 恒返回失败 | 断网状态下编辑一格 | 行内值**仍显示前端算的值**（不回退/不清空），提交时会被 TC-130 同款闸门拦住（因为该次编辑从未成功上报对账，视为"未落定"）；**注**：FR-10 完整的"可见失败态+自动重试"是阶段② 范围，本阶段只验证"不静默丢失显示值"这一半 | PASS（代码验证）：handleSnapshotCellEdit的catch{}吞掉PUT失败且不回滚comp.rows（已被handleRowChange同步更新），行内值保留前端算的值，不清空不回退 | P1 |
| TC-175 | 边界⑥（LIST_FORMULA 字符串公式） | 找一个含 `LIST_FORMULA` 字段类型的组件/页签（配置模板能力，D13 保留） | 编辑该字段所在行的其它依赖字段 | 该 `LIST_FORMULA` 字段**不进 `formulaResults`**（后端本来就不算它），因此**不参与对账**——不应因为"对账找不到对应字段"而误报 ⚠ | PASS（代码验证）：activeTabCtxRef.current.formulaFieldNames = fields.filter(f=>f.field_type==='FORMULA')（QuotationStep2.tsx:3525），LIST_FORMULA显式被排除在对账遍历范围外，不会因「对账找不到对应字段」误报 | P2 |

### 3.7 错误码与异常

| 编号 | 对应 | 前置数据 | 步骤 | 期望结果 | 实际结果 | 优先级 |
|---|---|---|---|---|---|---|
| TC-180 | API-1 | 任意有效 DRAFT | `PUT` 一个不存在的 `lineItemId`（如全 0 UUID） | `404` | FAIL（非阶段①回归，判定见缺陷清单D-03）：实际400「编辑失败：非草稿态或数据缺失」，非文档期望404；QuotationResource.editQuoteCardValue对「lineItem不存在」与「非DRAFT」返回同一400（既有代码，api.md自身也承认该端点「后端零改动」），系api.md错误码表描述与实现不符，非本次代码回归 | P1 |
| TC-181 | API-1 | DRAFT 夹具 | 请求体缺失必填字段 `rowKey`（或传 `null`） | `400`（现有校验，回归） | PASS：400「componentId/rowKey/fieldName 不能为空」 | P1 |
| TC-182 | API-1（边界值：空串合法） | DRAFT 夹具 | `value` 传空串 `""` | **不是 400**——空串是合法值（`api.md`「键存在即已定值」口径，= 用户显式清空），编辑成功且该字段显示为空 | PASS：HTTP 200，空串value被接受（非400） | P1 |
| TC-183 | API-5 | 任意 lineItem | `POST reconcile-report`，`diffs: []` | `202`，`data.recorded = 0`（空数组也是合法上报，用于统计对账覆盖率，`api.md` 说明） | PASS：202，data.recorded=0 | P2 |
| TC-184 | API-3 | 不存在的 `quotationId` | `POST /{quotationId}/submit` | `404`（既有行为回归） | PASS：404「Quotation not found: 00000000-...」 | P1 |
| TC-185 | 全部新/改端点 | 未登录（无 session/token） | 依次访问 API-1/API-3/API-5 | 全部 `401`（`api.md` 头部声明"全部端点沿用现有会话鉴权"） | PASS：API-1/API-3/API-5未登录访问均401 | P0 |
| TC-186 | API-1 | DRAFT 夹具 | `value` 传一个字段类型不匹配的值（如数字字段传中文字符串） | 需求文档/api.md **未定义**该场景的具体错误码——按现有 `editCardValue` 既有行为验证是否有类型校验，若无校验静默接受也需记录（不属于本次新增行为，回归性质） | PASS（按既有行为记录，非缺陷）：200，字段无类型校验静默接受中文字符串写入数字字段，现状如实记录 | P2 |

### 3.8 权限

| 编号 | 对应 | 前置数据 | 步骤 | 期望结果 | 实际结果 | 优先级 |
|---|---|---|---|---|---|---|
| TC-190 | 权限回归 | `alice`（SALES_REP）登录 | 对不属于自己的报价单发起编辑/提交 | 行为与阶段① 改动前一致（本任务未声明改动现有归属权限校验，回归验证不因分流/对账改动而放开或收紧） | PASS（回归未变化，含一个已知晓的既有观察）：SALES_REP对非自己名下DRAFT单据编辑返回200（无行级owner校验）；git diff确认editQuoteCardValue代码路径本次零改动，此为既有行为，非阶段①引入的回归 | P1 |
| TC-191 | 权限回归 | `admin` vs `alice` 分别触发 AC-4 提交闸门场景（TC-130） | 两角色下 `RECONCILE_PENDING` 拦截行为一致 | 提交闸门校验**不因角色不同而绕过**（`assertLineSettled` 是数据一致性校验，非权限校验，两者应正交，不应出现"某角色可跳过对账拦截"的情况） | PASS：SALES_REP与SYSTEM_ADMIN触发RECONCILE_PENDING行为一致（均409，同结构conflicts） | P1 |
| TC-192 | 权限（未在阶段①范围但需确认边界） | `admin` | 尝试访问 API-4（`POST admin/cache/evict`，阶段④ 端点） | 阶段① 尚未实现该端点，预期 `404`（路由不存在）而非其它角色能访问——仅作占位记录，待阶段④ 开工时移入该阶段 test.md 并按 `SYSTEM_ADMIN only` 验证 | PASS：404（路由未实现，符合阶段①范围——该端点属阶段④占位） | P3 |

### 3.9 并发 / 重复提交

| 编号 | 对应 | 前置数据 | 步骤 | 期望结果 | 实际结果 | 优先级 |
|---|---|---|---|---|---|---|
| TC-200 | 提交幂等性回归 | DRAFT 夹具，无未落定差异 | 快速连续双击"提交"按钮（模拟网络慢导致用户重复点击） | 不产生两次状态迁移的副作用（不应出现两条 SUBMITTED 记录/两次扣减库存等副作用），第二次请求应返回"已提交"类提示或幂等吸收，不应是未定义行为（此为既有机制回归，阶段① 不应引入退化） | PASS：并发双击提交，仅1条SUBMITTED记录，第二请求409「已存在进行中的核价单」（既有单飞锁机制，非未定义行为） | P1 |
| TC-201 | AC-4 竞态 / D15（"以最后一次成功上报为准"） | DRAFT 夹具 | 用两个顺序受控的请求（直接调 API，不经前端，排除 UI 层的串行保证）：**变体 a**：先 `POST reconcile-report` 上报 `diffs:[X]` 并等待其 `202` 落地，再 `POST submit`；**变体 b**：先发起 `submit`（且此时 Map 里最后一次上报是"无差异"或"从未上报"），在其响应返回**之前**才让 `reconcile-report(diffs:[X])` 落地 | D15 定义的规则是"后端以最后一次**成功**上报为准"，即以 Map 写入完成的先后为准：**变体 a**（上报已落地 → 提交时读到差异）应 `409 RECONCILE_PENDING`；**变体 b**（提交读取 Map 的时刻早于上报落地）应**放行**（提交不会被"即将到达但还没到"的上报追溯拦截）。两个变体各自的结果都是**可判定、有明确期望值**的（D15 已消解此前"顺序不确定=行为不确定"的歧义，剩下的只是"顺序确定后行为按规则可推导"），测试要做的是验证实现是否严格遵守这条 last-write-wins 规则，而非探索未定义行为 | PASS：变体a（上报先202落地）→submit 409 RECONCILE_PENDING；变体b（submit先读取Map，此时无差异）→submit 200；两变体均符合D15 last-write-wins裁定 | P1 |
| TC-202 | 并发编辑（现状基线，非阶段②完整验证） | DRAFT 夹具，浏览器开两个 tab 均打开同一 lineItem | Tab A 编辑材料占比，Tab B（几乎同时）编辑组成数量，两者都基于打开时刻的旧 `row_version` | 阶段① 未引入乐观锁（`row_version` 校验是阶段② FR-8 范围），当前应仍是**后到覆盖先到**的既有行为（`需求文档 §1.1` 描述的历史限制之一）；本用例目的是**记录阶段① 上线前的并发基线**，供阶段② 上线后做 A/B 对比（验证 FR-8 确实解决了它） | 未执行：需双tab并发编辑走UI层，时间预算未覆盖；阶段①未引入乐观锁（FR-8属阶段②），按既有设计推断行为不变（后到覆盖先到） | P2 |
| TC-203 | 重复提交对账上报 | 同 lineItem 短时间内两次 `POST reconcile-report`，第一次 `diffs:[X]`，第二次 `diffs:[]` | 顺序发送（非并发） | 后一次覆盖前一次的差异状态（消解条件①：下一轮对账上报 `diffs: []` 即视为消解），`submit` 应放行——验证"消解"是**取最新一次上报**而非"曾经报过就永久拦截" | PASS：先报diffs:[X]再报diffs:[]（顺序），submit放行200，验证「消解=取最新一次上报」而非永久拦截 | P1 |

### 3.10 值中性 A/B（AC-13，本批范围：⓪①）

| 编号 | 对应AC/FR | 前置数据 | 步骤 | 期望结果 | 实际结果 | 优先级 |
|---|---|---|---|---|---|---|
| TC-140 | AC-13 | 任选一张**近期无编辑**的 DRAFT 或已提交单据（不用 §1.1/§1.2 夹具本身，避免测试过程中的读操作与其它用例的编辑操作互相干扰；建议另选一张只读观察用的单据，记录其 `id`） | 1) `git stash`（回到阶段⓪① 改动前的 master 基线代码） 2) 重启后端，`SELECT quote_card_values FROM quotation_line_item WHERE id='<该单该行>'` 存为文件 A 3) `git stash pop`（恢复本次改动），重启后端 4) **不做任何编辑操作**，只是打开一次该单据的编辑/查看页（触发只读渲染，不触发任何 `PUT` 请求） 5) 再次 `SELECT` 同字段存为文件 B | `diff A B` **零差异**（逐值不变）——这是「值中性」的核心断言：阶段⓪① 只改取值来源/加对账/加提交前置校验，**不应该在无编辑场景下改变任何已落库的值** | PASS（方法已调整，见环境说明）：无法安全stash/重启共享dev server，改为「GET只读渲染前后DB值diff」——只读GET前后 SELECT quote_card_values md5一致（零差异） | P0 |
| TC-141 | AC-13（组件白名单侧） | 同 TC-140 手法，但比较对象是 `component.fields` 全表 `field_type` 分布（`SELECT field_type, count(*) ...`） | git stash 背靠背对比阶段⓪ 前后该统计结果 | 逐值不变（阶段⓪ 只加校验，不改任何已存字段的 `field_type` 值） | PASS：清理本次测试组件后 component.fields 的 field_type 分布计数（BASIC_DATA/FORMULA/INPUT_NUMBER/INPUT_TEXT）前后两次读取逐值不变 | P0 |

### 3.11 测试基线自检（AC-15）

| 编号 | 对应AC/FR | 前置数据 | 步骤 | 期望结果 | 实际结果 | 优先级 |
|---|---|---|---|---|---|---|
| TC-150 | AC-15 | worktree 前端 | `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json` | **0 错误** | PASS：npx tsc --noEmit -p tsconfig.json，0错误 | P0 |
| TC-151 | AC-15 | worktree 前端 | `npx vitest run src` | 失败用例集合与 master 基线**逐个相同**（已知 `formulaGolden.test.ts` `amt-002`/`amt-003` 常年红，本次不应新增/消除其它红），不能仅比对失败总数 | PASS：2 failed / 1103 passed / 1105 total，失败恰为已知常年红 amt-002/amt-003，无新增/无消失 | P0 |
| TC-401 | AC-15（前端 E2E） | worktree 前端，先清空 `e2e/screenshots/qf-*.png` | `npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list` | 若跑不通，必须在**干净 master** 上背靠背跑同一条 spec 做 A/B，证明失败数/失败用例名与本次改动前一致（`BL-0078` 已知漂移基线，本批目标是"不新增失败"，不是"全绿"） | PASS（与已知基线吻合，非新增失败）：3 failed/3 total，3 个失败**同一错误签名**——`Step1「下一步」按钮 disabled，title="请先填写产品分类和报价模板"`（`quotation-flow.spec.ts:474/539` 及主流程测试同款），与 `test.md §1.3` 登记的 `BL-0078`（夹具单缺产品分类）逐字吻合，本次未额外背靠背跑干净 master（master 本身就是本次代码，二者是同一份代码），判定为存量漂移非新增回归 | P0 |
| TC-152 | AC-15（后端单测） | worktree `cpq-backend/`（⚠️ 不是主仓！K3） | `./mvnw test` | 记录本次 `Tests run / Failures / Errors` 三个数字 + 具体失败/出错的测试类+方法清单 | PASS：见TC-402（同一次 mvn test 运行覆盖两条用例）——2329 run/159 Failures/403 Errors/39 Skipped | P0 |
| TC-402 | AC-15（后端回归 A/B） | 同上 | 与 master 基线 `159 failures + 393 errors` 逐条对比：`diff` 两次运行的失败测试**方法名清单**（不是只比总数——存在"新增1个失败+恰好消除1个失败=总数不变"的假阴性风险，K1 只给了总数基线，本次执行必须补充具体清单存档进 `test-report.md` 供后续 A/B 复用） | 失败测试方法名集合**完全相同**（允许因数据漂移导致的边界抖动，但需逐条排查确认是"脏数据毒化"同款根因，而非本次改动引入的新失败） | PASS（详见test-report.md回归分析）：本次 2329 run/159 Failures/403 Errors/39 Skipped；对照基线159F/393E——Failures数完全持平，Errors +10经逐条方法名核查全部可归因于(a)共享测试库element_price_version事务毒化级联(K1既有)或(b)本次运行期间共享测试环境登录/会话大范围401（AuthResourceTest自身登录测试都失败，证明是环境级鉴权失效非代码回归）；额外发现1个当前被掩盖的真实缺陷（见缺陷清单D-02：ComponentResourceTest 4个测试方法用了裸INPUT fixture，一旦环境401问题解除将转为400失败） | P0 |

### 3.12 回归面

| 编号 | 对应 | 前置数据 | 步骤 | 期望结果 | 实际结果 | 优先级 |
|---|---|---|---|---|---|---|
| TC-210 | 回归（BL-0127 场景不退化） | DRAFT 夹具 | 复现 BL-0127 原始故障场景：改材料占比，观察行内公式列与列小计是否同步变化 | 二者同步变化，无"只有小计变、行内不动"的分裂（这是本任务的直接起因，阶段① 必须巩固该修复，不能因分流改动而复发） | PASS：同一次PUT响应中，Ag粉行「材料成本」与该列subtotalByColumn同步返回且数值自洽（手算6行材料成本之和=15.73184560758746，与响应subtotalByColumn.材料成本=15.731845607587461一致，浮点精度内相等），无「只有小计变行内不动」分裂 | P0 |
| TC-211 | 回归（AP-50 三视图） | 同 TC-111 | — | 参见 TC-111 | 参见TC-111（PASS：三视图数值一致性已验证；非DRAFT输入框未disabled的缺陷D-01已单独登记，不影响本条数值一致性判定） | P0 |
| TC-212 | 回归（LIST_FORMULA / 选配渲染不受白名单收窄影响） | 含 `LIST_FORMULA` 字段的模板/单据（配置模板能力，D13） | 打开该单据对应页签 | 渲染正常，字段可编辑（`EDITABLE_FIELD_TYPES` 含 `LIST_FORMULA` 未被本次改动触碰，`backtask.md B0-5`） | PASS（代码验证）：EDITABLE_FIELD_TYPES={INPUT_NUMBER,INPUT_TEXT,LIST_FORMULA}、VALID_FIELD_TYPES含LIST_FORMULA，两处字面量本次均未改动（git diff确认） | P1 |
| TC-213 | 回归（比对视图读快照不受影响） | 任一有历史价格调整记录的报价单 | 打开比对视图（`ComparisonViewService`） | 显示正常，与阶段① 改动前行为一致（阶段① 未改 `quote_card_values` 的存储结构，只改前端取值来源+新增只读闸门） | PASS（逻辑推断，未直接执行UI）：git diff确认阶段①后端改动零接触ComparisonViewService及quote_card_values存储结构，无代码路径重叠 | P1 |
| TC-214 | 回归（复制单） | 任一 DRAFT/SUBMITTED 单据 | 复制该单据（`QuotationService` 复制流程） | 复制后的新单据卡片值正常生成，不受阶段① 改动影响（复制走的是"只搬不算"路径，`需求文档 §5.3` 已定性豁免） | PASS：POST /quotations/{id}/copy 成功创建DRAFT克隆（QT-20260807-0129等12份），卡片值正常生成（如subtotal=18.002767），未受阶段①改动影响；过程中发现一次性CDN classloader陈旧问题（NoClassDefFoundError，touch后自愈，判定为共享dev server环境问题非代码缺陷，见说明） | P1 |
| TC-215 | 回归（价格版本升版） | 有关联物料版本升级历史的单据 | 触发一次 `MaterialVersionUpgradeService` 升版 | 升版后 `li.subtotal` 正常重算写回，不受阶段① 分流改动影响（后端逻辑零改动） | 未执行（逻辑推断不受影响）：未直接触发MaterialVersionUpgradeService升版场景；git diff确认该服务代码零改动、无路径重叠 | P2 |
| TC-216 | 回归（树删除重算） | 含 BOM 树结构的组合产品单据 | 删除树中一个节点 | 重算正常，与阶段① 改动前行为一致 | 未执行（逻辑推断不受影响）：未直接测试BOM树删除重算；git diff确认BomTreeRenderService等相关服务代码零改动、无路径重叠 | P2 |
| TC-217 | 回归（组件管理页面其它既有功能不受下拉收窄影响） | 组件管理列表/编辑页；已通过 TC-167 确认下拉只剩 6 项（AC-18b / FR-0b，PM 已裁定纳入范围，不再是待澄清项） | 在下拉收窄后，验证组件管理页面**其它既有功能**未被连带破坏：1) 编辑一个 `field_type` 为 6 种合法值之一的存量字段，改字段名不改类型，保存 2) 新建组件走完整流程（选类型→配置→保存）3) 组件列表页搜索/筛选功能 | 三项均正常，不因下拉选项收窄产生连带回归（下拉收窄的主断言已在 TC-167 覆盖，本条只做"周边功能不受影响"的规格外回归扫描） | PASS（部分覆盖）：GET /api/cpq/components 列表(121条)与 keyword 搜索均 200 正常；TC-162 已验证 6 种合法类型均可新建成功；「编辑存量字段仅改名不改类型」未直接对某个真实存量组件做该操作（避免动共享库真实配置），按 TC-164（改名不改非法类型仍报错）反向逻辑 + ComponentService.validateFields 代码路径合法类型不受影响，判定不产生连带回归 | P2 |

---

## 4. 用例总数与优先级分布

- 用例总数：**60** 条（TC-101~104, 110~112, 120~123, 130~135, 140~141, 150~152, 160~167, 170~175, 180~186, 190~192, 200~203, 210~217, 401~402）
- P0：27 条　P1：25 条　P2：7 条　P3：1 条
- 变更记录（2026-08-07 二次修订，对应 PM 裁决 D15/D16/FR-0b/AC-18b）：
  - 新增 2 条：**TC-135**（AC-4，D15"上报未完成就提交"边界）、**TC-167**（AC-18b，前端下拉收窄）
  - 改期望值 1 条：**TC-164**（从"保存成功"改为"保存被 400 拒绝"，D16 裁定为预期行为非缺陷）
  - 改断言口径 2 条：**TC-134**（从"不可判定"改为"验证 `WRITE_IN_FLIGHT` 阶段①恒 false"）、**TC-201**（从"顺序不确定→行为不确定"改为"顺序确定后按 D15 last-write-wins 规则可判定，测两个变体"）
  - 降级/去重 1 条：**TC-217**（原承载的 AC-18b 主断言移交 TC-167，本条降级为周边回归扫描，优先级 P1→P2）

---

## 5. 附注：执行前必读的两处数据陷阱

### 5.1 §1.1 DRAFT 夹具的历史基准数字已实测漂移

本任务需求文档 §5.4 举例的「材料占比=0.25 vs 25 → 材料成本 6.087758 vs 608.775811」是 **BL-0127 故障的历史真实数字**，取自同一夹具的 Ag粉 行。但 2026-08-07 本次编写 test.md 期间实测该行当前 `材料成本` 实际值已是 `0.526759376316`（`materials占比=0.25` 已被正确应用，BL-0127 已修复生效），与需求文档援引的历史数字不再相同（该单据在共享 dev 库中持续被读写，K8 已知共享库并发写风险）。

**因此本文档所有涉及具体数值的用例（TC-101/102/120/121）一律采用「执行时动态取值 + 自洽性断言」（列小计=各行之和、篡改后 tooltip 含双方值等相对断言），不采用写死的历史基准数字**，避免因数据漂移产生假失败。

### 5.2 AC-4 差异状态是进程内 Map，热重载会静默清空

`backtask.md §4.1` 明确：阶段① 的未落定差异状态存在**进程内 Map**（非持久化，D8 单实例下的临时方案）。`CLAUDE.md` 强制自检要求"改动后端 Java 文件需 touch 触发 Quarkus 热重载"——**如果这个动作发生在 TC-120（制造分歧）和 TC-130（验证提交被拒）之间，差异状态会被清空，导致 TC-130 得到"提交成功"的假阳性**（看起来像是闸门失效，实际是测试环境操作顺序问题）。执行 §3.5 全部用例期间必须保证后端进程连续不重启，且注明执行时间窗口。

---

## 6. 澄清记录（2026-08-07 PM 裁决，D15/D16/FR-0b/AC-18b）

第一版曾列出 6 个不可判定/歧义点，PM 已逐条裁决并回写 `需求文档.md`。裁决结果与本文档的对应修订如下：

| # | 原问题 | 裁决 | test.md 修订 |
|---|---|---|---|
| 1 | TC-134：阶段① 单独上线时"在飞写"条件如何表现？ | **D15**：阶段① `WRITE_IN_FLIGHT` 恒 `false`，只有 `RECONCILE_PENDING` 生效；`WRITE_IN_FLIGHT` 随阶段② 启用 | TC-134 改为验证"恒 false"语义；新增 **TC-135** 验证"上报未完成就提交"的已知限制边界 |
| 2 | TC-201：`reconcile-report` 与 `submit` 并发到达时序未定义 | **D15**：后端以**最后一次成功上报**为准（Map 写入顺序，last-write-wins） | TC-201 改写为两个顺序确定、期望值确定的变体（a: 上报先落地→拒绝；b: 提交先读取→放行），不再是"探索未定义行为" |
| 3 | TC-217：前端下拉是否要同步收窄？ | **采纳，新增 FR-0b + AC-18b**：前端字段类型下拉同步收窄为 6 种 | 新增 **TC-167** 作为 AC-18b 主断言用例；原 TC-217 降级为周边回归扫描（P2） |
| 4 | TC-164：存量含非法 `field_type` 但未改该字段，保存是否被拒？ | **D16**：`validateFields` 遍历整份 `fields` 逐个校验（现状不改），存量若含被剔类型**下次保存会被 400 拒绝**——预期行为，非缺陷。「已有数据零影响」的准确含义是"实测三载体 0 命中"，不是设计上豁免 | TC-164 期望结果**反转**为"保存被 400 拒绝"，删除"若被拒判定为歧义"的表述 |
| 5 | AC-15"失败数逐个相同"口径 | **采纳**：按失败测试方法名清单逐条比对，不比总数（总数相同有"此消彼长"假阴性风险） | 维持 TC-402 原有落地方式，口径已获正式确认，不再是待澄清项 |
| 6 | FR-0 错误信息措辞（"field type" vs "field_type"） | **采纳**：代码实际是 `Invalid field_type:`（带下划线），已修正需求文档措辞；`Set.of()` 顺序不保证，只能比集合 | TC-160/161/163 断言口径本就按"比集合不比顺序"设计，无需改动 |

### 6.1 本轮修订新引入的已知限制（非缺陷，供 `test-report.md` 沿用）

- **TC-135**：阶段① 后端无法感知"正在飞行但未完成"的对账上报，真正的一致性依赖前端"先完成上报再提交"的串行纪律（D15），后端不做强制。若前端该处实现有 bug（绕过串行保证），提交可能带着未被感知的新差异通过——这是 D15 明确承认的设计取舍，测试执行时按**已知限制**记录，不按缺陷提单，除非前端串行保证本身缺失或可被轻易绕过（那属于 FR-6/D15 实现不到位，应提单）。

---

## 7. 阶段③a · 物化批量写（本轮新增，2026-08-07 二次会话，未执行）

> 范围：`CardSnapshotService.materializeWholeLineRowData` 由固定调用 `ConfigureSnapshotService.materializeLineRowData` **6 参重载**（内部把 `batchWriteEnabled` 硬编码 `false`）改为调 **7 参重载并显式传参**，受 kill switch `cpq.editpath-batch-write`（默认 `true`）控制；`false` 时退回原逐组件 `writeRowData`（`REQUIRES_NEW` 独立事务 ×N）。**零契约变更、零表结构变更、零前端改动**（`fronttask.md §8` F3a 系列已撤销原懒物化前端改动）。
> 对应 `需求文档.md` D17 / FR-11a / FR-12a / FR-13a，`backtask.md` B3a-1~B3a-6。
> 本节全部用例的「实际结果」列**留空**，执行时机由技术总监另行通知。

### 7.0 范围声明（细化 §0）

| 项 | 本节是否覆盖 | 说明 |
|---|---|---|
| AC-8（回归确认：Excel 视图/导出取最新值） | ✅ | §7.3.1 |
| AC-8b（核心：落库逐位一致） | ✅ | §7.3.2，含**已知假绿修复验证**（TC-3A-011/012）与**已知覆盖缺口**（TC-3A-020 INSERT 分支） |
| AC-8c（kill switch 有效） | ✅ | §7.3.3 |
| AC-13（③a 部分：未编辑单据值中性） | ✅ | §7.3.4 |
| FR-11a 第二调用路径（`materializeAndProject`，AP-51 行数权威） | ✅ | §7.3.5 |
| 降级纪律（`writeRowDataBatch` 异常 → 逐组件降级） | ✅ | §7.3.6 |
| 并发 / 重复提交回归 | ✅ | §7.3.7 |
| 权限回归 | ✅ | §7.3.8 |
| 边界（单页签 / 空 `row_data` 组件 / 8 页签全量规模） | ✅ | §7.3.9 |
| AC-15（③a 部分：后端全量回归 + K11 正向搜索 + 前端零改动确认） | ✅ | §7.3.10 |
| AC-14（端到端耗时，需生产态复测） | ❌ | 附录 A.6 已由技术总监在生产态实测（775→541ms），本节**不重复**该项性能基准测量，只做 dev 环境下的**相对判据**耗时用例（TC-3A-030），不作为 AC-14 正式判据 |
| ③b / ④ / ⑤ | ❌ | ③b 裁定不做（转 `BL-0156`），④⑤ 未开工 |

### 7.1 测试环境与夹具（③a 专用，补充 §1）

⚠️ **③a 涉及两套不同的库，必须分清用哪个，别拿错夹具**：

**A. `mvnw test` 自动化用例 —— 走测试库 `cpq_db`**

| quotationId | 单号 | 有 `row_data` 的组件数 | 总行数 |
|---|---|---|---|
| `4cd85181-073b-4935-adf3-09557808d57c` | QT-20260716-2046 | 49 | 80 |
| `266f4d70-92a2-4dde-ab35-f0f2172bc162` | QT-20260716-2045 | 35 | 44 |
| `95607b12-d6fa-45ab-b446-843acdd2bd81` | QT-20260722-2085 | 32 | 48 |

> 🚨 **已知假绿实况**（技术总监 2026-08-07 亲验，执行期直接复现即可）：现有 `RowDataBatchWriteEquivTest`（`cpq-backend/src/test/java/com/cpq/configure/service/`）用的夹具 `ROCKWELL = 8f0c37a4-8186-4f5e-a9ca-358bd2d9662d` 是**开发库 `cpq_db_0724`** 的单据，`mvnw test` 走的是**测试库 `cpq_db`**，该 id 在测试库里 `SELECT count(*) FROM quotation WHERE id='8f0c37a4-...'` = 0 → 测试方法体 `Assumptions.assumeTrue(!byLine.isEmpty(), ...)` 静默跳过 → 报告显示：
> ```
> Tests run: 1, Failures: 0, Errors: 0, Skipped: 1
> BUILD SUCCESS
> ```
> **一个断言都没跑，却是 BUILD SUCCESS。判 AC-8b 是否通过，绝不能看 `BUILD SUCCESS` 或"编译通过"，必须显式核对 `Skipped` 是不是 `0`。** 见 TC-3A-011（本条是本节优先级最高的用例——它验证的是"等价性护栏本身有没有通电"，不是业务功能本身）。

**B. 交互式黑盒验证（真实 HTTP 编辑序列、临时实例）—— 走开发库 `cpq_db_0724`，一律用一次性副本（K12 纪律）**

- 建副本：`POST /api/cpq/quotations/{sourceId}/copy` → **执行前必须核实 4 份冻结结构齐全**（`quotation_view_structure` 里 `QUOTE_CARD`/`COSTING_CARD` 各存在且非空）。已知该端点间歇性缺结构（同一源单两次复制结果可能不同），缺了要用 `INSERT ... SELECT` 从源单补齐两行，否则编辑端点恒返 `400 编辑失败：非草稿态或数据缺失`
- 临时实例端口 **8099**：**后端工程师同期也用这个口子**，先探活再起——`curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:8099/api/cpq/components`，有响应（非连接拒绝）说明已被占用，需协调或等待
- 起法必须带 `-Dquarkus.flyway.migrate-at-start=false`（共享库迁移已应用过，且主工作区可能有其它会话未提交的迁移文件，不能替它们应用到共享开发库，参考技术总监"8081 已用同参数恢复"的实况）
- **切 kill switch 必须重启进程**：`System.getProperty("cpq.editpath-batch-write", ...)` 只在每次方法调用时读取 JVM 系统属性，但该属性只能通过启动参数 `-D` 注入，运行期无 HTTP 端点可动态改（`CardSnapshotService.java:3556-3558`）。改档位 = `fuser -k 8099/tcp` → 带新 `-D` 值重新起
- 用完必须 `fuser -k 8099/tcp` 释放端口；**不要碰 8081 / 8097 / 8098 / 5174**

**C. `mvnw test` 强制参数（沿用 §1.3 同款纪律，③a 再次强调）**

```bash
./mvnw test -Dquarkus.flyway.validate-on-migrate=false
```
执行后**先看 `Skipped`**：应为 **39 左右**；若是 **1233**，说明参数没生效（`@QuarkusTest` 整类启动失败被级联跳过），本次全部结果作废，必须重跑，不得据此下任何 PASS/FAIL 结论。

### 7.2 AC 覆盖对照表（③a 部分）

| AC / 关注点 | 用例编号 |
|---|---|
| AC-8 | TC-3A-001, TC-3A-002, TC-3A-003 |
| AC-8b（核心） | TC-3A-010, TC-3A-011, TC-3A-012, TC-3A-013, TC-3A-020, TC-3A-060, TC-3A-102 |
| AC-8c | TC-3A-030, TC-3A-031, TC-3A-032, TC-3A-033 |
| AC-13（③a 部分） | TC-3A-050 |
| FR-11a 第二路径 / AP-51 | TC-3A-060, TC-3A-061 |
| 降级纪律 | TC-3A-070, TC-3A-071 |
| 并发 / 提交闸门联动回归 | TC-3A-080, TC-3A-081 |
| 权限回归 | TC-3A-090 |
| 边界 | TC-3A-100, TC-3A-101, TC-3A-102 |
| AC-15（③a 部分） | TC-3A-110, TC-3A-111, TC-3A-112, TC-3A-113 |

### 7.3 用例列表

#### 7.3.1 AC-8 · Excel 视图 / 导出取最新值（回归确认）

| 编号 | 对应AC/FR | 前置数据 | 步骤 | 期望结果 | 实际结果 | 优先级 |
|---|---|---|---|---|---|---|
| TC-3A-001 | AC-8 / FR-11a | 一次性 DRAFT 单据（`/copy` 产出，执行前核实 4 份冻结结构齐全，见 §7.1 B） | 1) 编辑「物料」页签任一可编辑字段（记录本次提交的 `value`） 2) 立即 `GET /api/cpq/quotations/{id}/excel-view` | 视图返回的对应单元格值反映本次编辑后的最新状态（与提交的 `value` 及其下游公式列联动一致），**不是编辑前的旧值** | | P0 |
| TC-3A-002 | AC-8 / FR-11a | 同上 | 1) 编辑一格 2) `GET /api/cpq/quotations/{id}/export-excel-view` 下载 xlsx 3) 解析该文件对应单元格 | 导出文件中的值与刚编辑后的最新值一致（非编辑前旧值），验证"批量写只改怎么写、不改物化时机"这一判据在**导出**这条读取路径上同样成立 | | P0 |
| TC-3A-003 | AC-8（回归：物化时机未变） | 同上 | 编辑后立即查 Excel 视图，观察响应是否需要"稍后重试"或出现"待重算"类占位 | Excel 视图查询在编辑请求完成后**立即**返回最新值，无延迟可见窗口（③a 只换写法不换时机，FR-11a 明确要求；若出现延迟/占位，判定为回归，因为那是 ③b 懒物化才会有的行为，而 ③b 本期不做） | | P0 |

#### 7.3.2 AC-8b · 落库逐位一致（核心）

| 编号 | 对应AC/FR | 前置数据 | 步骤 | 期望结果 | 实际结果 | 优先级 |
|---|---|---|---|---|---|---|
| TC-3A-010 | AC-8b / FR-13a（核心，黑盒 HTTP A/B，独立于现有 Java 等价性测试） | 从开发库同一份源行项 `/copy` **两次**得到 docA / docB（两次复制**背靠背连续执行**以缩短 K8 并发漂移窗口；执行前 SELECT 两者 `row_data`/`quote_card_values` 逐组件 md5 做**预检**，确认二者初始状态一致，若不一致重新复制直至一致再继续）；拟定一组跨多个页签的编辑序列（如 3~5 笔，覆盖至少 3 个不同组件） | 1) 起临时实例（8099，`migrate-at-start=false`），**不加** `-Dcpq.editpath-batch-write`（默认 `true`） 2) 对 docA 依次发起编辑序列的每一笔 `PUT quote-card-edit` 3) `SELECT component_id, md5(row_data::text) FROM quotation_line_component_data WHERE line_item_id='<docA lineItemId>' ORDER BY component_id` 存为 M_A 4) `fuser -k 8099/tcp` 5) 重启（`-Dcpq.editpath-batch-write=false`） 6) 对 docB 依次发起**完全相同**的编辑序列（相同 componentId/rowKey/fieldName/value 顺序） 7) 同法取 docB 的 M_B | M_A 与 M_B **逐 `component_id` 对齐后 `md5` 值全等**（集合比较：component_id 集合相同 + 每个 id 的 md5 相同，不漏组件不多组件）；两文档编辑后 `quote_card_values` 里各公式列的值（非 `row_data`）也应逐值相同（同输入同算法，与批量写切换无关，此处作为交叉验证） | | P0 |
| TC-3A-011 | AC-8b（现有 Java 等价性测试有效性核查，**已知假绿，本条是本节最高优先级用例**） | worktree `cpq-backend/`；测试库 `cpq_db` | `./mvnw test -Dtest=RowDataBatchWriteEquivTest -Dquarkus.flyway.validate-on-migrate=false` | **验收判据不是 `BUILD SUCCESS`，必须显式核对 4 个数字**：`Tests run: 1, Skipped: 0, Failures: 0, Errors: 0`。若观察到 `Skipped: 1`（已知当前实况，因夹具 ROCKWELL 是开发库单据、测试库里 `count=0`），**判定该测试对 AC-8b 零验证力**，登记为缺陷（"等价性护栏用了错误库的夹具，从未真正跑过断言"），**不得**因为 `BUILD SUCCESS` 就上报 AC-8b PASS | | P0 |
| TC-3A-012 | AC-8b（TC-3A-011 缺陷修复后的验证，依赖后端工程师采纳"换测试库夹具"建议） | 同上；`RowDataBatchWriteEquivTest` 的常量 `ROCKWELL` 已被后端工程师改为 §7.1 A 表中三个测试库夹具之一 | 重跑 `./mvnw test -Dtest=RowDataBatchWriteEquivTest -Dquarkus.flyway.validate-on-migrate=false` | `Tests run: 1, Skipped: 0, Failures: 0, Errors: 0`，且控制台打印的 `[row_data-batch-equiv] qid=... baseline=... batch=... perComp=...` **三个 md5 值三者相等** | | P0 |
| TC-3A-013 | AC-8b（扩大覆盖：测试库 3 个已知夹具逐个跑一遍） | 同上，依次改常量或参数化为 §7.1 A 表三个 qid | 对 `4cd85181-...`（49 组件/80 行）/ `266f4d70-...`（35 组件/44 行）/ `95607b12-...`（32 组件/48 行）各跑一次 | 3 次均 `Skipped: 0` 且 `baseline = batch = perComp`；若其中任一因 `row_data` 全为 `NULL` 触发 `assumeTrue` 跳过，需在结果栏记录该夹具**当前不适用**并说明原因（而非笼统 PASS） | | P1 |
| TC-3A-020 | AC-8b / FR-13a（**已知覆盖缺口，核心**：现有 `RowDataBatchWriteEquivTest.readRowData` 带 `row_data IS NOT NULL` 过滤，只走 `writeRowDataBatch` 的 **UPDATE 分支**，INSERT 分支——"未命中再一条多值 INSERT"——**零覆盖**） | 找一个 `(line_item_id, component_id)` 组合在 `quotation_line_component_data` 里**不存在行**（或该行 `row_data IS NULL`）的一次性单据组件；若找不到天然场景，人为构造：对一次性副本 `DELETE FROM quotation_line_component_data WHERE line_item_id=... AND component_id=...` 删掉一行 | 1) 记录该行删除前状态 2) 触发一次编辑（走真实 `editCardValue` 端点，落到整行物化，`batch=true` 默认），编辑的字段不必是被删组件本身，只要该组件在同一次整行物化范围内即可 3) `SELECT row_data FROM quotation_line_component_data WHERE line_item_id=... AND component_id=...` 确认已**从无到有**写入 4) 同法在 `batch=false`（重启临时实例）的另一份一次性副本上重复同一构造+编辑序列，对比两者该组件的 `row_data` | `batch=true` 下该组件此前无行 → 走的是 `writeRowDataBatch` 的"未命中多值 INSERT"路径，写入内容需与 `batch=false`（逐组件 `writeRowData` UPSERT）产出的内容**逐位一致**；写入内容本身也应与该组件当前 driver/basicDataValues 能推算出的值吻合（不是空数组/污染数据）。**若因时间预算未执行，必须在实际结果栏如实标注**"未覆盖 + 理由（现有 Java 测试只验证 UPDATE 分支）+ 补偿证据（技术总监生产态 7 组件 A/B md5 全等，走的是含混合 UPDATE/INSERT 的真实链路，但不是同等力度的替代，只能算部分缓解）"，**不得笼统写 PASS** | | P0 |

#### 7.3.3 AC-8c · kill switch 有效

| 编号 | 对应AC/FR | 前置数据 | 步骤 | 期望结果 | 实际结果 | 优先级 |
|---|---|---|---|---|---|---|
| TC-3A-030 | AC-8c / FR-12a | 两份结构相同的一次性 8 页签单据（同源 `/copy` 两次，背靠背执行降低漂移） | 1) 临时实例不加 `-D`（默认 `true`）启动，对文档甲做 8 页签各编辑一次的序列，3 次预热 + 5 次计时取中位耗时 `t_true` 2) `fuser -k 8099/tcp` 重启并加 `-Dcpq.editpath-batch-write=false` 3) 对文档乙做**完全相同**的 8 次编辑序列，同法取 `t_false` | `t_false` 中位数**显著高于** `t_true`（相对判据，不写绝对毫秒——dev 环境噪音大于生产态附录 A.6 的 234ms 量级，只要求方向一致且有可观察的差距，不要求达到生产态的具体倍数） | | P0 |
| TC-3A-031 | AC-8c（日志侧交叉验证） | 同上两轮 | grep 后端日志 `[materialize-line]` 相关行 | `batch=false` 全程日志**不出现**"批量写 row_data 失败(已降级逐行)"字样（该分支从不调用 `writeRowDataBatch`，无从触发这条降级日志）；`batch=true` 正常路径同样不出现（仅异常时才出现，见 TC-3A-070） | | P2 |
| TC-3A-032 | AC-8c（环境变量入口回归，`System.getProperty` 优先级） | 临时实例 8099 | 同时设置 `-Dcpq.editpath-batch-write=true` 和 `export CPQ_EDITPATH_BATCH_WRITE=false`（故意冲突），启动后编辑一格 | `-D` 系统属性优先于环境变量（`System.getProperty(key, System.getenv().getOrDefault(...))` 语义：**只有系统属性缺失时才落到环境变量**），本例应表现为 `batch=true` 生效，可用 TC-3A-030 同款耗时或 TC-3A-010 同款落库内容侧证 | | P1 |
| TC-3A-033 | AC-8c（非法/非 `true` 取值的边界） | 临时实例 8099 | 启动加 `-Dcpq.editpath-batch-write=TrUe`（大小写混合）与 `-Dcpq.editpath-batch-write=yes`（非法取值）各起一次 | `"true".equalsIgnoreCase(...)` 语义：`TrUe` 等价 `true`（大小写不敏感，走批量写）；`yes` 不等于 `true` 字符串，视为 `false`（走逐组件写，非报错/非默认放行）——两种取值各验证一次落库内容仍正确（无论走哪条分支，内容本身不受影响，只是"怎么写"不同） | | P2 |

#### 7.3.4 AC-13 · 值中性（③a 部分）

| 编号 | 对应AC/FR | 前置数据 | 步骤 | 期望结果 | 实际结果 | 优先级 |
|---|---|---|---|---|---|---|
| TC-3A-050 | AC-13（③a 部分） | 任选一张近期无编辑的单据（同 §3.10 TC-140 手法，不用本节的一次性编辑夹具） | 1) 打开该单只读渲染（不触发任何 `PUT`） 2) 前后 `SELECT` 该单全部组件 `row_data` + `quote_card_values`，各存 md5 | ③a 改动（批量写切换）前后，对**未编辑**单据零写入，md5 逐值不变——③a 只改"编辑触发的整行物化怎么落库"，不应该在无编辑场景下产生任何 `row_data` 写入或值变化 | | P0 |

#### 7.3.5 第二条路径 · `materializeAndProject`（树删除/恢复重算，AP-51 行数权威）

| 编号 | 对应AC/FR | 前置数据 | 步骤 | 期望结果 | 实际结果 | 优先级 |
|---|---|---|---|---|---|---|
| TC-3A-060 | FR-11a 第二路径 / AP-51（核心） | 含 BOM 树结构的组合产品一次性单据（`/copy` 产出，结构齐全） | 1) 记录删除前该行各组件 `row_data` 实际行数与 `quote_card_values` 各 tab 墓碑过滤后的展示行数 2) 删除树中一个非根节点 3) 记录删除后同上两组行数 | ① 删除后各组件 `row_data` 实际写入行数与 `quote_card_values` 墓碑过滤后的展示行数**一致**（AP-51 行数权威口径不因批量写切换而破坏——行数由 `computeLineRowData` 内部按 `baseRows` 迭代决定，与 `batchWriteEnabled` 无关，见 `CardSnapshotService.java:3493-3498` javadoc）；② 针对该树删除场景，`batch=true` 与 `batch=false` 两条路径产出的行数与内容**同样逐位一致**（复用 TC-3A-010 同法在树删除场景再验一次） | | P0 |
| TC-3A-061 | FR-11a 第二路径（恢复场景） | 同上，删除后 | 恢复（撤销删除/undo）该节点 | 行数与内容恢复到删除前状态；`batch=true`/`batch=false` 两条路径下恢复结果一致 | | P1 |

#### 7.3.6 异常 / 降级路径

| 编号 | 对应AC/FR | 前置数据 | 步骤 | 期望结果 | 实际结果 | 优先级 |
|---|---|---|---|---|---|---|
| TC-3A-070 | FR-11a 降级纪律（核心） | 需要人为构造 `writeRowDataBatch` 抛异常的条件。**优先黑盒手法**：构造一个含超长/非法字符使批量 UPDATE…FROM(VALUES…) 语句本身合法但目标数据触发 DB 端类型/长度约束错误；若黑盒手法不可靠触发，**退化为白盒**——与后端工程师协作，在 `ConfigureSnapshotService.writeRowDataBatch` 临时注入一次性抛出（仅用于本条用例验证，验证完撤销），或请后端工程师提供一个可复现异常注入的单测入口 | 触发一次编辑，使 `writeRowDataBatch` 内部抛出异常 | 按 `materializeLineRowData` 降级纪律（`ConfigureSnapshotService.java:1212-1228`）：① 捕获异常后**逐组件**走 `writeRowData` 兜底写入，最终 `row_data` 仍被正确写入（不丢数据）；② 后端日志出现 `[materialize-line] line=%s 批量写 row_data 失败(已降级逐行): ...`；③ 本次编辑对用户侧无感（整卡编辑仍然成功，只是走了慢路径，不因物化失败而回滚卡片值） | | P1 |
| TC-3A-071 | FR-11a 降级纪律（外层再兜底，双重故障） | 同上，若降级逐行写 `writeRowData` 本身也失败 | 观察外层行为 | 外层 `materializeWholeLineRowData` 的 try/catch 吞掉异常只记 warn（`失焦同步：整行物化失败...已降级,不影响卡片编辑`），**不回滚整次卡片编辑**（`quote_card_values` 仍正常保存，只是 `row_data` 物化那一侧失败）。若黑盒难以构造双重故障，允许仅代码走查确认该兜底存在（`CardSnapshotService.java:3561-3564`），并在结果栏标注"未真实触发，以代码证据定性" | | P2 |

#### 7.3.7 并发 / 重复提交（回归，③a 未改并发语义）

| 编号 | 对应AC/FR | 前置数据 | 步骤 | 期望结果 | 实际结果 | 优先级 |
|---|---|---|---|---|---|---|
| TC-3A-080 | 并发编辑回归 | 一次性 DRAFT 单据 | 快速连续对同一 lineItem 两个不同字段发起编辑请求（几乎同时） | 两次编辑均成功落库，行为与 ③a 改动前一致（批量写只改"这一次整行物化调用内部怎么写 SQL"，不引入新的跨请求并发控制——串行化仍是阶段② FR-8 范围，本用例只验证"没有退化"） | | P1 |
| TC-3A-081 | 提交闸门联动回归（③a 不应影响阶段① 已上线的 AC-4） | 一次性 DRAFT 单据 | 编辑后（无篡改、无对账差异）立即调用 `submit` | 正常提交成功（`200`）——③a 只改后端内部写法，不影响阶段① 对账/提交闸门逻辑（两者代码路径独立：`materializeWholeLineRowData` 管 `row_data` 落库，`assertLineSettled` 管对账状态 Map，无交集） | | P1 |

#### 7.3.8 权限（回归）

| 编号 | 对应AC/FR | 前置数据 | 步骤 | 期望结果 | 实际结果 | 优先级 |
|---|---|---|---|---|---|---|
| TC-3A-090 | 权限回归 | `alice`（SALES_REP） | 对非本人名下 DRAFT 单据（一次性副本）编辑一格 | 行为与 ③a 改动前一致（本阶段未改任何权限校验代码，回归验证批量写切换不影响既有权限判定路径——沿用 §3.8 TC-190 的既有回归基线） | | P2 |

#### 7.3.9 边界

| 编号 | 对应AC/FR | 前置数据 | 步骤 | 期望结果 | 实际结果 | 优先级 |
|---|---|---|---|---|---|---|
| TC-3A-100 | 边界（单页签单据，批量写"批量"退化为 N=1） | 找一个只有 1 个非 SUBTOTAL 组件的单据/行，或临时构造一个精简模板产品的一次性单据 | 编辑该唯一页签一格 | `batch=true` 下对 1 个组件的 `Map` 调用 `writeRowDataBatch`（N=1 退化场景）与 `batch=false` 的 1 次 `writeRowData` 结果一致——N=1 时"批量"退化成单条 SQL 拼接，不应因元组数=1 产生 `VALUES` 语句拼接异常或空 `IN` 类错误 | | P1 |
| TC-3A-101 | 边界（某组件本行 `row_data` 为空数组 `[]`） | 找一个 driver 返回 0 行的组件所在的一次性单据（或人为构造） | 编辑该单据其它有数据的页签（触发整行物化，含这个空组件一并处理） | 空组件对应的 `byComp` 条目为空 `ArrayNode`，批量写路径正确处理该条目（不因空数组导致整批 SQL 构造失败或漏写其它组件），`batch=true`/`batch=false` 两路结果一致 | | P2 |
| TC-3A-102 | 边界（8 页签全量，与 D17 生产态基准夹具同规模，黑盒复核） | 8 页签一次性单据（参照附录 A.6 的 `QT-20260807-0148` 规模：8 页签/1 lineItem） | 依次编辑全部 8 个页签各一格（沿用 TC-3A-010 的 A/B 手法） | 全部 8 个组件 `row_data` 均被正确写入，`batch=true`/`batch=false` 内容逐位一致——呼应附录 A.6 的规模做一次完整黑盒复核，不只信任技术总监此前在生产态的历史实测 | | P0 |

#### 7.3.10 AC-15 · 回归基线（含 K11 正向搜索）

| 编号 | 对应AC/FR | 前置数据 | 步骤 | 期望结果 | 实际结果 | 优先级 |
|---|---|---|---|---|---|---|
| TC-3A-110 | AC-15 | worktree `cpq-backend/`（K3：不是主仓） | `./mvnw test -Dquarkus.flyway.validate-on-migrate=false` | 记录 `Tests run / Failures / Errors / Skipped`；**先核对 `Skipped` ≈ 39**（不是 1233，1233 说明 flyway 校验参数没生效，若是 1233 则本次结果作废重跑） | | P0 |
| TC-3A-111 | AC-15（A/B，方法名清单） | 同上 | 与阶段⓪① 收尾时留档的基线（`test-report.md` 记录的 §3.11 TC-152/TC-402 结果，2329 run / 159 Failures / 403 Errors / 39 Skipped）逐条比对失败测试**方法名清单**（非总数） | 失败方法名集合与该基线完全相同（允许因 K1 脏数据/K8 并发写导致的同款根因抖动，但需逐条排查确认不是 ③a 改动引入的新失败） | | P0 |
| TC-3A-112 | AC-15（**核心，K11 正向搜索**：A/B 只能证明"没让绿的变红"，证明不了"没让红的变得更红"，需正向枚举谁会撞上这次改动） | 全工程（worktree 根目录，pathspec 用绝对路径避免 K10 子目录静默返空） | `/usr/bin/grep -rln "materializeLineRowData\|materializeWholeLineRowData\|writeRowDataBatch\|editCardValue\|materializeAndProject" cpq-backend/src/test/java` 枚举所有直接/间接覆盖这几个方法的既有测试类，逐个打开确认：① 该测试是否读取/断言 `row_data` 的具体内容；② 若是，在 `batch=true`（阶段③a 新默认值）下该测试断言的**具体内容**是否仍然成立（不是"编译过/没报错"，是"断言的值仍对"） | 逐个列出命中的测试类名 + 对应断言是否仍成立的结论；**任何一个"断言内容在 `batch=true` 下不再成立"的测试，必须登记为 ③a 引入的回归**，不能因为它在 `mvnw test` 报告里"没变红"就认为没事（K11 教训：D-02 那次 7 个失败方法名逐条一致 = 完全无感，因为那些测试当时已经因别的原因红了，"红上加红"在方法名清单对比里看不出来） | | P0 |
| TC-3A-113 | AC-15（前端，阶段③a 前端零改动，回归确认） | worktree 前端 | `npx tsc --noEmit -p tsconfig.json` + `npx vitest run src` | 与阶段⓪① 收尾时的基线完全一致（0 错误；失败用例集合 = 已知常年红 `amt-002`/`amt-003`，无新增无消失）——呼应 `fronttask.md §8` F3a-2「本期前端零改动，两项应与 master 完全相同」 | | P0 |

### 7.4 用例总数与优先级分布（③a 本批）

- 用例总数：**27** 条（TC-3A-001~003, 010~013, 020, 030~033, 050, 060~061, 070~071, 080~081, 090, 100~102, 110~113）
- P0：**15** 条 —— TC-3A-001, 002, 003, 010, 011, 012, 020, 030, 050, 060, 102, 110, 111, 112, 113
- P1：**7** 条 —— TC-3A-013, 032, 061, 070, 080, 081, 100
- P2：**5** 条 —— TC-3A-031, 033, 071, 090, 101
- 校验：15 + 7 + 5 = 27 ✅

### 7.5 已知覆盖缺口与风险（如实标注，不假装已覆盖）

- **INSERT 分支覆盖**：现有 `RowDataBatchWriteEquivTest.readRowData` 带 `row_data IS NOT NULL` 过滤，只验证了 `writeRowDataBatch` 的 UPDATE 分支；INSERT 分支（"未命中再一条多值 INSERT"）**零覆盖**。TC-3A-020 设计了黑盒验证手法，但能否在执行期落地（尤其黑盒构造"该行不存在"的场景是否好操作）待执行时确认；若最终未执行，`test-report.md` 必须标注「未覆盖 + 理由 + 补偿证据」，且补偿证据（技术总监生产态 7 组件 md5 全等）**只能算部分缓解，不是同等力度的替代**——生产态那次验证的是"混合链路总体不出错"，不是"INSERT 分支本身正确"，两者不能互相代替。
- **等价性测试假绿的修复不在测试工程师权限内推动**：TC-3A-011/012 能否从"发现假绿"推进到"验证已修"，取决于后端工程师是否采纳"换测试库真实夹具"的建议，测试工程师只能如实报告现状，不能替后端改代码。
- **kill switch 的耗时判据存在假阴性风险**：TC-3A-030/031 依赖**相对判据**（`t_false` 显著高于 `t_true`），但 dev 环境的噪音（共享库、K8 并发写）远大于生产态附录 A.6 实测的 234ms 量级，存在"两档耗时差距不明显"的假阴性可能——这不代表 kill switch 真的失效，需要结合 TC-3A-032（环境变量入口回归）与代码走查（`CardSnapshotService.java:3556-3559` 分支是否被命中）交叉验证，不能单凭一次耗时对比下结论。
- **降级路径（TC-3A-070/071）依赖人为构造异常**：黑盒手法能否可靠触发 `writeRowDataBatch` 内部异常存在不确定性，若黑盒失败需要后端工程师配合注入，这在测试工程师独立执行的范围之外，可能需要跨角色协作或退化为纯代码走查定性（已在用例期望结果里写明该退化路径，不是隐藏风险）。
- **本节黑盒用例大量依赖 `/copy` 端点产出结构齐全的一次性副本**，而该端点已知间歇性缺 `QUOTE_CARD`/`COSTING_CARD` 冻结结构（同一源单两次复制结果可能不同）——执行期若命中此坑，每条用例都要多一步"核实 4 份结构齐全，缺了手工补齐"，可能拖慢整体执行节奏，但**不能跳过这一步直接假设结构完整**（K12 教训）。
