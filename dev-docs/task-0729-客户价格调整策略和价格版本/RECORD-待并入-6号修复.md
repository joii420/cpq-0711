# 待并入 `docs/RECORD.md` —— 验收 `#6` 修复（裁决 39）

> **为什么单独成文**：修复完成时 `docs/RECORD.md` 有他会话未提交改动（前端 tsconfig 修复 / 建库脚本同步），
> 直接追加会让我的内容混进他人的待提交变更、被 `git add docs/RECORD.md` 顺手带走或反被覆盖。
> 故把条目backing out 出来单独存放，**由 task-0729 结案人统一并入**。
>
> **并入方式**：把下面 §1 的整段（一个单行条目 + 前置 `---` 分隔）追加到 `docs/RECORD.md` 末尾即可，
> 格式已对齐现有单行条目风格，无需再改写。
>
> 并入后本文件可删。
>
> - 关联提交：`9a164f99`（fix + 4 项回归测试，主仓 master）
> - 关联验收项：`testcases.md #6`（已判 PASS）
> - 关联裁决：需求说明 §11.5 裁决 39 + §11.5.5 补丁 1

---

## 1. 待并入条目（原样复制）

```

---

[2026-08-05] task-0729 价格调整 / 验收 `#6` 返修 - **修复：价格 0 变动的料号仍然进待办池（裁决 39 从未实现）** | 涉及文件：`priceadjust/service/PriceAdjustBudgetService.java`（`processMaterial` 加准入判定 + 新增 `hasRelevantPriceChange` / `loadVersionEffectivePrices`）、`priceadjust/service/MaterialVersionUpgradeService.java`（抽出 `resolveDriverRowElementCode` / `resolveDataRowElementCode`，新增 `collectMaterialElementCodes`）、新建测试 `PriceAdjustBudgetServiceDecision39Test.java`（提交 `9a164f99`） | **根因**：`processMaterial` 的准入判定**只有两条**（① 指针已指向本版本 → 跳过；② 无活单且未曾驳回 → 推指针不进池），**完全没有任何价格比较**。只要料号在策略范围内且有活单，相关元素价一个都没变也会建 review 进池 —— 裁决 39「相关元素价与上一版逐个相同的料号不进待办池」自始至终没有落地。 | 🚨 **注释说谎（本轮最值得记的发现）**：`PriceAdjustStrategyService.java:270` 的注释白纸黑字写着「本期有价格变动则纳入待办池，无变动不进（同裁决39，**复用 B4 processMaterial 的 pool-entry 判定，不重复实现**）」—— **那个判定从来就不存在**，注释以"已经有了、别重复造"的口吻描述了一个想象中的功能。任何人读到这句都会停止追查。**教训：注释声称"复用了某处既有能力"时，必须点进去确认那个能力真的存在**（与 `#4` 的"类注释声称的补跑能力不存在"同类，已并入交付报告方法论章节）。 | 🔑 **难点在「相关元素」怎么定义** —— `element_price_version_item` 是**元素级**的，准入判定是**料号级**的，而全工程**没有**现成的「料号 → 元素」映射：`computeBudget` 走 `upgrade(dryRun=true)` 整单重算、元素是隐含的；`PriceAdjustReviewService:166` 列的是版本全部元素（非料号相关）；`findBasisLine` 只返回 quotationId/lineItemId/templateSeriesId，零元素信息。**裁定：「相关元素」= 升版真正会改到该料号的那批行上扫出来的元素编码全集**（S3a 的 `snapshot_rows.driverRow` + S3b/S4b 的 `row_data`），因为那正是"价格变了会影响这个料号"的充要范围。 | 🔒 **实现纪律：不新写第二套口径**。把 `upgradeComponentRows` 里两处内联的元素编码解析抽成 `resolveDriverRowElementCode` / `resolveDataRowElementCode`，新增的 `collectMaterialElementCodes` 与升版执行**共用同一份**（冻结结构 → `locatePriceBearingComponents` → 同两个 resolver）。若各写一套，会出现「预算算的元素」与「准入判定的元素」不是同一批，比"全都进池"更难查。抽取是等价变换，回归网 = `MaterialVersionUpgradeServiceS3Test`（断言 rowsChanged=5 / 双 Ag 行逐行改 / Ni 不动 / 非价格键逐字不变 / manual 分流）。 | 🚨 **`_元素` 下划线前缀坑（不查真实数据必踩）**：真实 driverRow 的键是 SQL 原始列名 **`_元素`（带下划线前缀）而不是 `元素`** —— 实测 `SELECT r->'driverRow'->>'元素'` 全返 NULL。若图省事读裸键，相关元素集合**恒为空** → 保守兜底恒触发 → **判定永远不生效、且不报任何错**，`#6` 照样 FAIL 而看不出哪里错（典型"改了等于没改"的隐形失败）。必须走 `FormulaCalculator.resolveRowByFieldName` 按字段定义解析（BASIC_DATA / INPUT_TEXT+default_source 两种来源统一）。 | **三个边界（漏一个就出事）**：① `previousVersionId == null`（料号从无指针）→ 没有可比基准，**必须进池**，不得把"没得比"当成"无变化"；② `basis == null`（无活单但曾被驳回，§11.5.5 补丁 2 反例外）→ 无依据单可扫，且该分支存在意义正是"驳回决定不得被静默撤销"，此处跳过+推指针会直接违反补丁 2；③ **`review != null`（本版已在池中）→ 不判定**，否则 `PriceAdjustStrategyService#dispatchRecompute` / `PriceAdjustComparisonColumnService#putColumns` 两个重算入口刚把行标成 `BUDGET_QUEUED`，就会被踢出池 + 推进指针 → 行**永久悬停在 QUEUED**（既不 READY 也不消失）。**曾被驳回的料号不需要额外判定**：指针没推进 → 与新版比必有差异 → 自然进池（验收 #50①）。 | **比较口径**：`current_price` + `currency`（正是 S1 读出、S3a/S3b 写进行里的两个值）；`price_unit` 全链路不参与写回故不比，`change_rate`/`previous_price` 是派生展示列更不比；两版都 NULL（彻底无价）判相同；金额用 `compareTo` 不用 `equals`（5450 与 5450.000000 必须同价）。**保守方向**：任何"证明不了没变"的情形（相关元素集合为空 / 元素只在一版有 item）一律判为有变动 → 照常进池 —— 宁可多进池让财务点一下，也不能静默跳过+推指针（那等于未经审核就接受了本期价）。 | **验证**：新增 4 项回归测试全绿（正向 0 变动不进池+版本照常生成+指针照常推进 / 反向对照价格确有变动照常进池 / 相关性精度—变动元素该料号没用到仍不进池 / 无指针必须进池），`priceadjust` 全模块 **52 测试 0 失败**（含 `80a091a8` 补入的 #48 两条）；真实数据（`cpq_db_0724`，共享 8081）经 `POST /api/cpq/admin/quotations/task0729-b0-upgrade-preview` 验证重构后升版链路无回归，3 个真实 line item SUCCESS 改写 8/8/6 行，其中 `431a9baf` 的 8 行 = row_data 4 行 + **driver 侧 4 行**（driverRow 键为 `_元素`，证明按字段名解析这一步真实生效）。 | ⚠️ **开发期事故（共享 8081 被我打挂一次）**：先落了两处 resolver **调用点**、后落 resolver **方法定义**，共享 dev server 热重载恰好扫到这个中间态 → `cannot find symbol` 编译失败，全会话被挡。**两条教训**：① 改共享 dev server 的代码，**改完先 `./mvnw -o compile` 自检**，别等热重载报错（快得多，也不会连累别人）；② **顺序必须是「先加新方法、再改调用点」，让任一中间态都可编译** —— 反过来（先改调用点）必然留下一段"引用了不存在的符号"的时间窗，在共享 dev server 上就是事故。
```

---

## 2. 四条教训速查（并入后可作为 `反模式.md` 候选素材）

| # | 教训 | 适用面 |
|---|------|--------|
| **1** | **改共享 dev server 的代码，改完先 `./mvnw -o compile` 自检**，别等热重载报错 | 所有在主仓 master 上直接改后端的场景 |
| **2** | **顺序必须是「先加新方法 → 再改调用点」**，让任一中间态都可编译。反过来必然留下"引用不存在符号"的时间窗，在共享 8081 上就是事故 | ⭐ 最实用，任何跨方法重构/抽取 |
| **3** | **`driverRow` 的键是 SQL 原始列名（带 `_` 前缀）**，读裸键在真实数据上恒空 → 判定恒不生效且不报错。必须走 `resolveRowByFieldName` | 任何读 `snapshot_rows.driverRow` 取字段值的新代码 |
| **4** | **注释声称"复用了某处既有能力"时，必须点进去确认那个能力真的存在** | 全局；本 task 已出现两次（`#4` 的补跑能力、`#6` 的 pool-entry 判定） |

> 教训 3 与 4 有一个共同点：**失败是静默的**。前者让判定永不触发、后者让人停止追查，两者都不会抛异常、不会红字，
> 只会让功能"看起来实现了"。这类问题只能靠**真实数据验证**兜住 —— 纯代码走查和单元夹具（用简单
> `INPUT_TEXT` 字段、键就是字段名）都测不出教训 3。

---

## 3. 本次未处理、已报交付报告的遗留

| 问题 | 说明 | 处置 |
|------|------|------|
| `resolveScopeMaterials` 的 `ALL` 模式无过滤 | 返回该客户所有报过价的销售料号，无状态/时间窗限制；每个料号走一遍 `collectMaterialElementCodes` + 2 次版本明细查询，未批量化 | 已记交付报告，不在 `#6` 范围 |
| 3 个真实单被 L3 守卫 `SUBTOTAL_MISMATCH` 拦下 | 差异 12 / 214 / 541.9 / 0.37 元，当前走不完升版。属既有守卫行为，与本次无关 | 已记交付报告 |
| `quotation_price_revision` 清理依赖 dryRun 回滚 | 测试里 `materializeAndSealInitialRevision` 在 dryRun 事务内建 R 版本行、靠回滚消失。已验 0 残留，但若 dryRun 语义变化，这类测试会留脏数据 | 已记交付报告 |

---

## 4. 造数与清理留痕

| 数据 | 位置 | 状态 |
|------|------|------|
| `ZZ6-D39-<uuid8>` 客户 + `TEST-D39-*` 组件/报价单 + 冻结结构 + line_item + component_data + 2 个 `element_price_version` + items + `version_ref` | 测试库 `cpq_db`，`@AfterEach` 清理 | ✅ 7 张表逐张 count 验证 0 残留（含 `quotation_price_revision` 孤儿检查） |
| dev 库 `cpq_db_0724` | 只读 + `dryRun=true`（`setRollbackOnly`） | ✅ 零写入，`ZZ6%` 客户 0 条 |
| `CUST-0001` / `CUST-0729-QA` | 未创建、未修改（仅只读引用 CUST-0001 的 PENDING 版本做 dryRun preview） | ✅ 测试工程师数据域未受影响 |
