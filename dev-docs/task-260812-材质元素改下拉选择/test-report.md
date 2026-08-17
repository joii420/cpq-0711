# 测试报告 — task-0812 材质元素改下拉选择

- 执行日期：2026-08-12
- 执行人：cpq-tester（本会话）
- 依据：`test.md`（32 条用例，主线已复审通过）

## 1. 执行环境

| 项 | 值 |
|---|---|
| 分支 | `feat/task-260812-材质元素改下拉选择`（worktree `/home/joii/project/cpq/.claude/worktrees/task-0812-material-element-select`） |
| 被测前端代码 | 该分支最新两次提交：`92d1d1d0`（初版实现）+ `5534e47f`（字典加载失败不再误判为字典外脏值） |
| 前端运行方式 | **worktree 内临时 Vite**，端口 `5199`，`--host 0.0.0.0`；`node_modules` 软链自主工作区（未 `npm install`）。**未使用共享 5174**（5174 服务主工作区未合并代码，会测出假绿，见 test.md TC-31 方案 A 说明） |
| 后端 | 共享 dev server `8081`，**零改动**，本次直接复用；`curl /api/cpq/components` → 401（鉴权正常，服务健康） |
| 数据库 | `10.177.152.12:5432/cpq_db_0724`（与前端共享后端所连库一致） |
| 浏览器自动化 | Playwright + `channel:'chrome'`（系统 `google-chrome`），headless；未使用项目自带 `playwright.config.ts`/`global-setup.ts`（其硬编码指向 `cpq_db` 与 5174，与本任务库/端口不符），改写独立脚本直接以 `chromium.launch` 驱动，脚本执行完毕后已从 worktree 删除（未提交，不在 git 状态里） |
| 账号 | `admin`/`Admin@2026`（SYSTEM_ADMIN，执行前确认 `status=ACTIVE`）；`tc0812_sales`（临时新建，SALES_REP，测完已 `PATCH status=INACTIVE`，用于 TC-26/27） |
| 测试期间产生的临时数据 | `TC17-TEMP`／`TC21TEMP`(未落库,校验被前端拦截)／`TC23-TEMP`／`TC27TEMP`(未落库,403拒绝)／`TC30TEMP`／`TC32-TEMP`／临时用户 `tc0812_sales`／临时改停用元素 `10009`（Be）——**全部已清理/还原，见 §6** |

## 2. 用例执行汇总

| 状态 | 数量 | 用例编号 |
|---|---|---|
| **PASS** | 29 | TC-01,02,03,06~19,22,23,24,25,26,27,28,29,30,31,32（除下列 3 条外全部） |
| **部分 FAIL** | 3 | TC-04、TC-05、TC-20（同一根因，见缺陷 BUG-0812-01） |
| **BLOCKED** | 0 | 无 |
| **合计** | 32 | —— |

三条"部分 FAIL"用例中，各用例的**其余断言均 PASS**（阻断保存、红字提示、SQL 落库正确性等），**只有"保存被拦截时弹出的具体文案"这一条断言 FAIL**，详见 §4。

## 3. 逐条结果

详见 `test.md` 各用例「实际结果」字段（本轮已逐条填写，含截图文件名索引）。截图证据存放于测试执行期临时目录（未随代码提交，见 §7 证据清单的文件名列表，如需复核请重新按 test.md 步骤执行）。

摘要表：

| 用例 | 结果 | 一句话结论 |
|---|---|---|
| TC-01 | PASS | 两入口列头/Select 类型均符合 AC-1/AC-9 |
| TC-02 | PASS | 收起态文本精确匹配 |
| TC-03 | PASS | 00005 回显+幂等保存，SQL 逐字段一致（element_no 变 NULL 属既有行为，未计入判定） |
| TC-04 | **部分 FAIL** | 阻断/红字/重选保存均 PASS；拦截 toast 文案 FAIL（见 BUG-0812-01） |
| TC-05 | **部分 FAIL** | 同上，00262 样本保留未消耗 |
| TC-06~09 | PASS | 编号/符号(大小写)/中文名/空格过滤全部命中 |
| TC-10 | PASS | 无命中空态文案精确匹配，无跳转链接 |
| TC-11 | PASS | 跨行置灰 + 本行不置灰 |
| TC-12 | PASS | 停用消失/恢复重现，10009 最终复位 ACTIVE |
| TC-13 | PASS（执行修正：Be 实际在 row1 非 row0，00158 含 Cu+Be 两行） | 已停用元素只读回显+Tag+保存不拦截 |
| TC-14 | PASS | 空字典空态文案正确 |
| TC-15 | PASS（全部 4 项） | 代码评审必修项验证通过：加载失败不再诬告正常行为脏数据 |
| TC-16 | PASS | 全选完两项均置灰 |
| TC-17 | PASS | 空串老行按"未选择"处理，FR-9 拦截 |
| TC-18/19 | PASS（执行注记：需先填材质编号/化学式） | FR-9 未选元素拦截+行号定位正确 |
| TC-20 | **部分 FAIL** | 多行时确实未放行，但拦截文案同 BUG-0812-01 |
| TC-21 | PASS（分两层验证） | UI 层先被前端既有 sum 校验拦截（非本任务范围）；直接 API 验证后端校验完整存在，文案/状态码精确匹配 |
| TC-22 | PASS | recipeType 三态切换选中态不丢 |
| TC-23 | PASS | Excel 导入路径不受影响，element_no 正常写入 |
| TC-24 | PASS | flyway 版本执行前后一致（385=385） |
| TC-25 | PASS | 字典条数 37=37 |
| TC-26 | PASS | 非 admin 读字典 200，37 条 |
| TC-27 | PASS | 非 admin 保存 403 |
| TC-28/29 | PASS | 打开恰 1 次请求，过滤 5 次无新请求 |
| TC-30 | PASS | 保存恰 1 次，请求体不含 elementNo |
| TC-31 | PASS | tsc 0 错误，5199 两个路径均 200 |
| TC-32 | PASS | 新建端到端落库，SQL 逐字段核对一致 |

## 4. 缺陷清单

### BUG-0812-01（唯一发现的缺陷）

【现象】
编辑「字典外脏值」材质（如 `992`/`00262`）时，元素框正确显示为**未选择态**并在下方显示红字 `原值「X」不在元素字典中，请重新选择`（FR-7 视觉提示正确）；但点击「保存」被拦截时，弹出的 `message.error` 文案是 `请为第 N 行选择元素`（FR-9 通用"未选择"文案），**不是**代码里为这个场景专门写的 `第 N 行的元素不在元素字典中，请重新选择`（FR-7 专属文案，见 `fronttask.md` §2.5 与源码 `MaterialRecipeEditDrawer.tsx` 的 `badIdx` 分支）。

【预期】
需求文档 AC-8：「直接点保存被拦截并给出提示」——虽未逐字规定文案，但 `fronttask.md` §2.5 明确设计了两条独立的校验消息（emptyIdx 用"请为第 N 行选择元素"，badIdx 用"第 N 行的元素不在元素字典中，请重新选择"），意图是让用户能区分"我没选"和"我选的值有问题（数据本身脏）"两种不同情况。

【复现】（≤5 步）
1. 打开材质 `992`（或任何字典外脏值材质）编辑抽屉
2. 观察第 1 行：显示占位符 + 红字「原值「10001」不在元素字典中，请重新选择」（正常）
3. 直接点「保存」
4. 观察顶部 toast：显示「请为第 1 行选择元素」（**预期应为「第 1 行的元素不在元素字典中，请重新选择」**）

【根因】
`MaterialRecipeEditDrawer.tsx` 的元素回显 reconciliation 逻辑（`useEffect([open, editingDetail, dictLoading, dictError, byCode])`）中，「未命中字典」分支同时把 `elementNo` 置为 `null` **且** `unmatched` 置为 `true`：
```js
return {
  elementNo: null,          // ← 未命中时恒为 null
  elementCode: e.elementCode,
  elementName: e.elementName,
  unmatched: !!e.elementCode,
  ...
};
```
而 `handleSubmit` 里两条校验的执行顺序是：
```js
const emptyIdx = elements.findIndex(e => !e.elementNo);      // ① 先查"未选择"
if (emptyIdx >= 0) { message.error(`请为第 ${emptyIdx + 1} 行选择元素`); return; }
const badIdx = elements.findIndex(e => e.unmatched);          // ② 再查"字典外脏值"——但永远查不到
if (badIdx >= 0) { message.error(`第 ${badIdx + 1} 行的元素不在元素字典中，请重新选择`); return; }
```
由于 `unmatched === true` 的行 `elementNo` 恒为 `null`，① 一定先命中并 `return`，② 分支**在当前数据结构下永远不可达**（dead code）。这不是"没实现"，是两条独立正确的校验各自都写对了，但顺序 + 数据结构组合出了一个逻辑死角。

【影响】
仅影响**用户看到的提示文案**，不影响功能正确性：
- 保存仍然被正确拦截（不会带着脏数据提交）
- 红字视觉提示（FR-7 的核心诉求：让用户看见"这行数据有问题"）完全正常
- 用户重选后依然能正常保存成功（TC-04 验证过）
- 只是 toast 文案让人以为"我忘了选"，而不是更准确的"这行原来的值查不到"——体验上的降级，非功能性缺陷

【严重级】**轻微（Minor）**。不阻断任何验收标准（AC-8 未逐字规定文案），不影响数据正确性，只是提示信息不够精确。

【建议方向】
`handleSubmit` 里调整两条检查的优先级，或改用更细的判断条件，例如：
```js
const badIdx = elements.findIndex(e => e.unmatched);          // 先查"字典外脏值"（更具体的错误）
if (badIdx >= 0) { message.error(`第 ${badIdx + 1} 行的元素不在元素字典中，请重新选择`); return; }
const emptyIdx = elements.findIndex(e => !e.elementNo);       // 再查"未选择"（更通用的错误）
if (emptyIdx >= 0) { message.error(`请为第 ${emptyIdx + 1} 行选择元素`); return; }
```
（把更具体的错误判断放在更通用的判断之前，是这类"多条件互斥优先级"场景的常见处理方式）

【是否回流】待主线裁定。鉴于严重级为 Minor 且不影响 AC 达成，本报告如实记录，不代主线做修复决策。

---

**无其他缺陷**。TC-13 的"Be 实际在 row1 非 row0"是测试执行前对 00158 数据结构的认知偏差（该材质本身含 2 个元素 Cu+Be，非本任务改动导致），不算产品缺陷，已在 test.md/本报告中注明并按正确行号验证通过。

## 5. 回归结论

- **含量和≠100 后端校验**：PASS（经直接 API 验证，见 TC-21）
- **recipeType 切换选中态**：PASS（TC-22）
- **材质库 Excel 导入路径**：PASS（TC-23，`element_no` 仍正常写入，与 UI 保存路径的已知缺口互不影响）
- **flyway_schema_history 无新增行**：PASS（TC-24，385=385）
- **权限边界未被放宽**：PASS（TC-27，非 admin 保存仍 403）
- **N+1 声明**：本任务前端零查库循环、后端零改动，不涉及 N+1（沿用 `backtask.md` §7 声明）

**本次无契约变更，无需回写 `dev-docs/main-api.md`**（`api.md` 已论证：`GET /api/cpq/elements` 与 `POST/PUT /api/cpq/material-recipes` 均为复用现状端点，方法/路径/参数/响应/错误码全未变）。

## 6. 数据还原验证（测试结束后，在报告落笔前重新查询）

```sql
-- 无残留临时材质
SELECT code FROM material_recipe WHERE code LIKE 'TC%';
-- → (0 rows)

-- 元素 10009(Be) 恢复 ACTIVE
SELECT status FROM element WHERE element_no='10009';
-- → ACTIVE

-- 临时权限测试账号已停用
SELECT username, status FROM "user" WHERE username='tc0812_sales';
-- → tc0812_sales | INACTIVE

-- flyway 无新增迁移
SELECT max(version::int) FROM flyway_schema_history WHERE version ~ '^[0-9]+$';
-- → 385（与执行前一致）

-- 00262 保持脏数据活样本（供后续复测），未被 TC-05 消耗
SELECT element_code FROM material_recipe_element WHERE recipe_id='c7dba513-5419-4269-9d2c-981258564bdd';
-- → 10004

-- 992 已被 TC-04 正确纠正为字典值（预期行为，非需还原项）
SELECT element_code FROM material_recipe_element WHERE recipe_id='38218ebe-cc36-493e-83cc-42508e18734e';
-- → Ag
```

**结论：数据现场干净，仅 `992` 因 TC-04 的正向流程（重选后保存成功）从脏数据变为正常数据——这是被测功能"生效"的证明，不是需要还原的副作用。**`00262` 按 test.md 设计保留为脏数据活样本供后续复测使用。

worktree 内的临时测试脚本（`e2e/tc0812-manual-run.mjs`、`e2e/tc0812-part2.mjs`、`e2e/probe.mjs`）执行完毕后已删除，未提交、未污染 git 状态（`git status --short` 确认干净）。临时前端进程（5199）已 `pkill` 并确认无残留。

## 7. AC-1 ~ AC-12 逐条达成对照表

| AC | 内容摘要 | 覆盖用例 | 达成结论 |
|---|---|---|---|
| AC-1 | 列头 6 列，元素列为 Select | TC-01 | **达成** |
| AC-2 | 编号/符号(大小写)/中文名过滤均命中 `10001 / Ag / 银` | TC-02,06,07,08 | **达成** |
| AC-3 | 选中后收起态文本精确 | TC-02 | **达成** |
| AC-4 | 停用元素消失、恢复后重现（用引用数最小的 Be 替代"零引用"，主线已确认接受） | TC-12 | **达成** |
| AC-5 | 跨行选中置灰，本行不置灰 | TC-11 | **达成** |
| AC-6 | 无命中空态文案精确，无跳转 | TC-10 | **达成** |
| AC-7 | 正例材质 00005 幂等保存，SQL 逐字段一致 | TC-03 | **达成** |
| AC-8 | 脏数据材质阻断保存+重选后成功 | TC-04,05 | **达成，但伴随 Minor 缺陷 BUG-0812-01**（拦截逻辑正确，提示文案不够精确，AC 未逐字规定文案，不影响达成判定） |
| AC-9 | 无可编辑元素名称输入框 | TC-01 | **达成** |
| AC-10 | 恰 1 次 GET，过滤不发请求，保存恰 1 次；加载失败不误判 | TC-15,28,29,30 | **达成** |
| AC-11 | 请求体不含 `elementNo` | TC-30 | **达成** |
| AC-12 | tsc 0 错误 + Vite 200（worktree 环境） | TC-31 | **达成** |

**12/12 AC 全部达成**（其中 AC-8 附带一条 Minor 级别的文案缺陷记录，不影响达成判定，供主线参考决定是否回流修复）。

## 8. 证据清单（截图文件名索引，执行期截取，共 40 张）

`tc01-new-headers` / `tc01-edit-headers` / `tc02-collapsed` / `tc03-saved` / `tc04-open-blocked` / `tc04-save-blocked-msg` / `tc04-save-success` / `tc05-open-blocked` / `tc05-save-blocked-msg` / `tc06-filter-10001` / `tc07-filter-ag-lowercase` / `tc08-filter-cn` / `tc09-filter-spaces` / `tc10-no-match` / `tc11-row0-ag-not-disabled` / `tc11-row1-ag-disabled` / `tc12-be-back-in-dropdown` / `tc12-be-not-in-dropdown` / `tc12-be-restored` / `tc12-be-stopped` / `tc13-inactive-tag` / `tc13-save-allowed` / `tc14-empty-dict` / `tc15-load-failed-msg` / `tc15-notfound-content` / `tc15-rows-no-red` / `tc15-save-blocked` / `tc16-all-selected-disabled` / `tc17-empty-code-row` / `tc17-save-blocked` / `tc18-empty-row1` / `tc19-empty-row2` / `tc20-multirow-still-blocked` / `tc21-sum-not-100` / `tc22-after-cycle` / `tc26-nonadmin-dropdown` / `tc27-save-403` / `tc28-29-network` / `tc30-request-body` / `tc32-saved`

其余证据形式：curl HTTP 状态码与响应体（TC-21 直接 API 验证、TC-23 Excel 导入响应）、SQL 查询输出（贯穿 TC-03/04/05/12/13/17/23/24/25/32 及 §6 还原验证）、Playwright 拦截的 Network 请求计数/响应体（TC-25/28/29/30）。

## 9. 已自检声明

> tsc 0 错误 ✅；5199 首页 200 ✅；5199 目标 tsx 200 ✅；后端 `/api/cpq/components` → 401（鉴权正常）✅；flyway 385→385 无新增 ✅；数据现场还原验证 SQL 全部通过 ✅；32/32 用例执行完毕，29 PASS / 3 部分 FAIL（同一 Minor 缺陷）/ 0 BLOCKED ✅
