# 测试用例 — task-0812 材质元素改下拉选择

- 依据：`需求文档.md`（AC-1~AC-12 为验收唯一标准）、`fronttask.md`、`backtask.md` §6、`api.md`
- 执行环境：前端 `http://localhost:5174`（复用主工作区 dev server），后端 `http://localhost:8081`，库 `10.177.152.12:5432/cpq_db_0724`
- 入口路径：主数据维护 → 「材质」Tab →「新建材质」按钮 / 点行进入编辑 → 抽屉「材质详情」Tab →「元素组成」表
- 抽屉标题：新建 = `新建材质`；编辑 = `编辑材质: <code>`
- 列头（改造后应为）：`元素 | 默认 % | 最小 % | 最大 % | 锁定 | 操作`
- SQL 连接：`PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_0724`
- 本文档执行者请勿改动生产数据的最终状态：所有对 992 / 00262 / 00158 / element 表的临时改动，用例末尾均写明还原步骤，执行后必须还原
- 本仓 `grep` 为 ugrep，检索中文源码用 `/usr/bin/grep -a`

## 固定测试数据（已实测，直接引用）

| 用途 | 数据 |
|---|---|
| 正例材质（AC-7） | `00005`（symbol `AgNi25C2`，recipeType=`locked`，3 行：`C/碳/1.0`、`Ni/镍/23.0`、`Ag/银/76.0`，sort_order 1/2/3），recipe_id=`d6e44a1e-8cae-4fba-8582-17d2a28408ca` |
| 脏数据材质①（AC-8） | `992`（symbol `AgNi11#-Ⅰ`），1 行 `element_code='10001'`（应为 Ag），recipe_id=`38218ebe-cc36-493e-83cc-42508e18734e` |
| 脏数据材质②（回归） | `00262`（symbol `Sn02`），1 行 `element_code='10004'`（应为 Sn），recipe_id=`c7dba513-5419-4269-9d2c-981258564bdd` |
| 元素字典 | `element` 表 37 条，**全部 ACTIVE**；`10001/Ag/银`、`10005/Ni/镍`、`10012/C/碳`、`10004/Sn/锡` |
| 停用测试用元素 | `10009/Be/铍`，referencedCount=1（现网 37 条元素**没有一条 referencedCount=0**，AC-4「未被引用的元素」用引用数最小的这条替代，语义等价：停用/恢复的下拉可见性行为与是否被引用无关，主线已确认接受，不算偏离 AC）；被材质 `00158`（symbol `C17200`）唯一引用 |
| flyway 基线 | `SELECT max(version::int) FROM flyway_schema_history WHERE version ~ '^[0-9]+$'` = `385`（用例执行前后必须一致） |
| 非管理员账号 | `test_finance_c87a27ab`（`PRICING_MANAGER`，ACTIVE）用于权限用例；若密码不可得，改用新建一个 ACTIVE 的 `SALES_REP`/`PRICING_MANAGER` 测试账号 |
| 元素停用/启用的唯一入口 | `DELETE /api/cpq/elements/{elementNo}` 只能**停用**（软删，204）；**恢复启用没有对应的"反 DELETE"接口**，必须走「主数据维护 → 元素」Tab 找到该行点「编辑」，在编辑抽屉里把状态改回「启用」提交（`PUT /elements/{elementNo}` body 带 `status:'ACTIVE'`）。TC-12/TC-13 的还原步骤按此执行，不要以为再 DELETE 一次能切回来 |

---

## 一、正常流

### TC-01　两入口列头结构与元素列类型
- 对应：AC-1、AC-9
- 前置数据：无
- 步骤：
  1. 打开「主数据维护 → 材质」，点「新建材质」，展开「元素组成」表
  2. 关闭抽屉，改为点开材质 `00005` 行进入「编辑材质」，展开「元素组成」表
- 期望结果：
  1. 两个入口的表格列头均依次为 `元素 | 默认 % | 最小 % | 最大 % | 锁定 | 操作`，共 6 列
  2. 不存在「元素 code」「元素名」两列
  3. 「元素」列渲染为下拉框（antd `Select`，点击后出现候选浮层），不是文本输入框
  4. 全表任意行都不存在可编辑「元素名称」的 `Input` 输入框（AC-9）
- 实际结果：**PASS**（Playwright 自动化，5199）。新建入口：headers=["元素","默认 %","最小 %","最大 %","锁定","操作"]，isSelect=1，plainInputs=0。编辑入口（材质 00005）：headers 一致，rows=3，全部为 Select，无 plain input。证据：`tc01-new-headers.png` / `tc01-edit-headers.png`
- 优先级：P0

### TC-02　选中元素后收起态文本
- 对应：AC-3
- 前置数据：新建材质抽屉，任一空行
- 步骤：
  1. 点击「元素」下拉，输入 `10001`
  2. 点击唯一候选项
- 期望结果：下拉收起，框内文本精确为 `10001 / Ag / 银`（无多余空格/换行）
- 实际结果：**PASS**。收起态文本="10001 / Ag / 银"，与期望逐字符一致。证据：`tc02-collapsed.png`
- 优先级：P0

### TC-03　编辑正例材质原样保存（幂等性）
- 对应：AC-7、backtask §6 第 2 条
- 前置数据：材质 `00005`
- 步骤：
  1. 执行前置 SQL 留痕：
     `SELECT element_code, element_name, default_pct, min_pct, max_pct, is_locked, sort_order FROM material_recipe_element WHERE recipe_id='d6e44a1e-8cae-4fba-8582-17d2a28408ca' ORDER BY sort_order;`
  2. 打开材质 `00005` 编辑抽屉，确认 3 行元素自动回显为 `10012 / C / 碳`、`10005 / Ni / 镍`、`10001 / Ag / 银`（顺序与 sort_order 一致）
  3. 不做任何字段改动，直接点「保存」
  4. 重跑步骤 1 的 SQL
- 期望结果：
  1. 保存请求返回 200
  2. 步骤 4 的查询结果与步骤 1 逐字段（`element_code`/`element_name`/`default_pct`/`min_pct`/`max_pct`/`is_locked`/`sort_order`）完全一致，3 行行序不变
  3. ⚠️ **`element_no` 列不在上述断言字段清单内，允许从有值变为 NULL，不得据此判本用例失败**。原因：`MaterialRecipeService.update()` 是「全删旧行再插新行」，`insertElement()` 从不写 `element_no`（`backtask.md` §4 既有缺口，`element_no` 是否变化与本次改造无关，AC-7 的字段清单本来就不含它）。执行前可先 `SELECT element_no FROM material_recipe_element WHERE recipe_id='d6e44a1e-8cae-4fba-8582-17d2a28408ca';` 留痕（预期保存前 3 行均非空、保存后 3 行均为 NULL），仅作观察记录，不计入通过/失败判定
- 实际结果：**PASS**。回显 row0="10012 / C / 碳" row1="10005 / Ni / 镍" row2="10001 / Ag / 银"（与 sort_order 一致）；保存返回 message="材质已更新"；SQL 逐字段比对 `element_code`/`element_name`/`default_pct`/`min_pct`/`max_pct`/`is_locked`/`sort_order` 保存前后完全一致（C/碳/1.0000、Ni/镍/23.0000、Ag/银/76.0000，均 is_locked=t）；`element_no` 按预期从有值变 NULL（不计入判定）。证据：`tc01-edit-headers.png` / `tc03-saved.png` + SQL 输出
- 优先级：P0

### TC-04　编辑脏数据材质 992：阻断→重选→保存成功
- 对应：AC-8、FR-7 第三分支
- 前置数据：材质 `992`（`element_code='10001'`）
- 步骤：
  1. 打开材质 `992` 编辑抽屉
  2. 观察该行「元素」列
  3. 不改任何值，直接点「保存」
  4. 在该行下拉中输入 `10001`，选中 `10001 / Ag / 银`
  5. 再次点「保存」
  6. `SELECT element_code, element_name FROM material_recipe_element WHERE recipe_id='38218ebe-cc36-493e-83cc-42508e18734e';`
- 期望结果：
  1. 步骤 2：元素框为空（无选中态），下方出现红色（`#ff4d4f`）文案 `原值「10001」不在元素字典中，请重新选择`
  2. 步骤 3：保存被拦截，未发出 `PUT` 请求（F12 Network 确认），`message.error` 提示文案含「不在元素字典中，请重新选择」字样
  3. 步骤 5：保存成功，返回 200
  4. 步骤 6：`element_code='Ag'`，`element_name='银'`
- 实际结果：**部分 FAIL（缺陷 BUG-0812-01，见 test-report.md）**。
  - 步骤 2（打开阻断态）PASS：元素框显示占位符（未选中，非 " / " 格式），红字精确为 `原值「10001」不在元素字典中，请重新选择`
  - 步骤 3（保存被拦截）PASS：确实未放行保存（无 "已更新" 消息）
  - **但实际拦截文案是 `请为第 1 行选择元素`（FR-9），不是期望的 `不在元素字典中，请重新选择`（FR-7/D4）—— FAIL**。根因：`unmatched` 行的 `elementNo` 恒为 `null`（回显逻辑决定），`handleSubmit` 里 FR-9「未选元素」检查（`!e.elementNo`）排在 FR-7「字典外脏值」检查之前，永远先命中，导致 FR-7 专属提示成为不可达代码
  - 步骤 5（重选后保存成功）PASS：message="材质已更新"；SQL 验证 `element_code='Ag'`、`element_name='银'`
  - 证据：`tc04-open-blocked.png` / `tc04-save-blocked-msg.png` / `tc04-save-success.png`
- 优先级：P0

### TC-05　编辑脏数据材质 00262：同类场景交叉验证（仅验阻断态，不消耗样本）
- 对应：AC-8（回归覆盖第二条脏数据）
- ⚠️ **数据风险提示**：现网仅有 `992`/`00262` 两条脏数据样本。TC-04 已完整跑通「阻断→重选→保存」并会真实纠正 `992` 那 1 行，此后 `992` 不再是脏数据。为保留至少一条脏数据样本供复测/回归使用，**本用例只验证打开时的阻断表现，不执行重选保存**，把 `00262` 保留为脏数据活样本
- 前置数据：材质 `00262`（`element_code='10004'`）
- 步骤：
  1. 打开材质 `00262` 编辑抽屉
  2. 观察该行「元素」列
  3. 不做任何修改，直接点「保存」
  4. 关闭抽屉（不提交任何改动）
- 期望结果：
  1. 步骤 2：元素框为空 + 红字 `原值「10004」不在元素字典中，请重新选择`
  2. 步骤 3：保存被拦截，未发出 `PUT` 请求（F12 Network 确认），`message.error` 含「不在元素字典中，请重新选择」
  3. 步骤 4 后 `SELECT element_code FROM material_recipe_element WHERE recipe_id='c7dba513-5419-4269-9d2c-981258564bdd';` 仍为 `10004`（样本未被消耗，可供后续复测复用）
- 实际结果：**部分 FAIL（同 BUG-0812-01）**。打开阻断态 PASS（占位符 + 红字 `原值「10004」不在元素字典中，请重新选择`）；保存未被放行 PASS；但拦截 toast 文案同样是 `请为第 1 行选择元素` 而非期望的「不在元素字典中」文案 —— FAIL，与 TC-04 同一缺陷。样本未消耗：保存后 SQL 复查 `element_code` 仍为 `10004` PASS。证据：`tc05-open-blocked.png` / `tc05-save-blocked-msg.png`
- 优先级：P1
- 如需重建脏数据样本（例如 `992` 已被 TC-04 纠正，需要新造一条脏数据用于其他轮复测）：
  ```sql
  UPDATE material_recipe_element
  SET element_code='10001', element_name='Ag', element_no=NULL
  WHERE recipe_id='38218ebe-cc36-493e-83cc-42508e18734e';
  ```
  （把 992 的行改回脏值 `10001`/`Ag` 且清空 `element_no`，还原成 TC-04 执行前的状态；执行前确认没有其他会话正在用这条数据）

---

## 二、过滤（FR-3 / AC-2）

### TC-06　按元素编号过滤
- 对应：AC-2
- 前置数据：新建材质，任一空行
- 步骤：打开元素下拉，输入 `10001`
- 期望结果：候选恰筛出 1 项，文本为 `10001 / Ag / 银`
- 实际结果：**PASS**。opts=["10001 / Ag / 银"]，恰 1 项。证据：`tc06-filter-10001.png`
- 优先级：P0

### TC-07　按元素符号过滤（小写，验证大小写不敏感）
- 对应：AC-2
- 前置数据：同上
- 步骤：打开元素下拉，输入 `ag`（全小写）
- 期望结果：候选筛出的项中包含 `10001 / Ag / 银`（大小写不敏感命中 `elementCode='Ag'`）
- 实际结果：**PASS**。输入 `ag`（小写）候选包含 "10001 / Ag / 银"。证据：`tc07-filter-ag-lowercase.png`
- 优先级：P0

### TC-08　按中文名过滤
- 对应：AC-2
- 前置数据：同上
- 步骤：打开元素下拉，输入 `银`
- 期望结果：候选筛出的项中包含 `10001 / Ag / 银`
- 实际结果：**PASS**。输入 `银` 候选包含 "10001 / Ag / 银"。证据：`tc08-filter-cn.png`
- 优先级：P0

### TC-09　输入前后带空格
- 对应：fronttask.md §5「输入含前后空格」
- 前置数据：同上
- 步骤：打开元素下拉，输入 `" ag "`（前后各一个空格）
- 期望结果：候选中仍包含 `10001 / Ag / 银`（`filterOption` 内部 `trim()` 后匹配，不因空格导致 0 命中）
- 实际结果：**PASS**。输入 " ag "（前后空格）候选仍包含 "10001 / Ag / 银"。证据：`tc09-filter-spaces.png`
- 优先级：P1

### TC-10　过滤无命中空态
- 对应：AC-6、FR-6
- 前置数据：同上
- 步骤：打开元素下拉，输入 `zzz`
- 期望结果：
  1. 下拉候选区文案精确为 `未找到该元素，请先到「主数据维护 → 元素」维护后再选择`
  2. 该文案区域内**不存在**任何可点击的跳转链接或按钮（无「刷新」按钮、无「去新建」链接）
- 实际结果：**PASS**。输入 `zzz` 空态文案精确匹配 `未找到该元素，请先到「主数据维护 → 元素」维护后再选择`；linkCount=0（下拉区域内无 a/button）。证据：`tc10-no-match.png`
- 优先级：P0

---

## 三、边界

### TC-11　同材质跨行去重置灰
- 对应：AC-5、FR-5、D7
- 前置数据：新建材质，2 行元素
- 步骤：
  1. A 行（第 1 行）下拉选中 `10001 / Ag / 银`
  2. 展开 B 行（第 2 行）下拉
  3. 展开 A 行下拉（不切换选中值）
- 期望结果：
  1. B 行下拉中 `10001 / Ag / 银` 呈禁用态（灰色不可点，`aria-disabled="true"` 或 antd `disabled` class）
  2. A 行下拉中 `10001 / Ag / 银` 仍可点击且显示为当前选中项（不置灰）
- 实际结果：**PASS**。B 行(row1)展开搜 Ag，选项 class 含 `ant-select-item-option-disabled`（置灰）；A 行(row0)展开，选项 class 含 `ant-select-item-option-selected` 无 `disabled`（可点/已选中）。证据：`tc11-row1-ag-disabled.png` / `tc11-row0-ag-not-disabled.png`
- 优先级：P0

> ⚠️ **TC-12 与 TC-13 共用同一个停用目标 `10009/Be/铍`，必须按固定顺序连续执行、只停用一次、最后统一还原一次**，避免两条用例各自还原互相打架（例如 TC-12 先还原成 ACTIVE，TC-13 又要求已停用，导致状态对不上）。执行顺序：**先做 TC-12 步骤 1~2 → 紧接着做 TC-13 步骤 2~3（不要在中间恢复启用）→ 都做完后再统一执行一次「元素」Tab 编辑抽屉把 `10009` 改回 ACTIVE → 最后做 TC-12 步骤 4 验证恢复后重新出现**。

### TC-12　停用元素从下拉候选消失，恢复后重新出现
- 对应：AC-4、FR-4、D2
- 前置数据：元素 `10009/Be/铍`（当前 ACTIVE）
- 步骤：
  1. 打开「主数据维护 → 元素」Tab，把 `Be`（`10009`）状态改为「停用」（`DELETE /api/cpq/elements/10009`，需 SYSTEM_ADMIN；该接口只能停用，见「固定测试数据」表最后一行）
  2. 打开任一材质（新建或编辑）抽屉，元素组成表任一行展开下拉，输入 `Be`
  3. **不要在此处恢复启用**，直接接续执行 TC-13（见上方顺序说明）
  4. TC-13 全部做完后，回到「主数据维护 → 元素」Tab，找到 `Be`（`10009`）行点「编辑」，在编辑抽屉里把状态改回「启用」并保存（`PUT /elements/10009` body 含 `status:'ACTIVE'`）——**不是再 DELETE 一次**
  5. 重新打开材质抽屉，元素下拉再次输入 `Be`
- 期望结果：
  1. 步骤 2：候选中**不出现** `10009 / Be / 铍`（`notFoundContent` 空态或候选为空，视是否有其他匹配项）
  2. 步骤 5：候选中**重新出现** `10009 / Be / 铍`
- 实际结果：**PASS**。停用后（走「元素」Tab 编辑抽屉 status→INACTIVE）新建材质抽屉搜 Be：候选 opts=[]（不出现）；恢复启用（编辑抽屉 status→ACTIVE）后再次搜 Be：候选 opts=["10009 / Be / 铍"]（重新出现）。证据：`tc12-be-stopped.png` / `tc12-be-not-in-dropdown.png` / `tc12-be-restored.png` / `tc12-be-back-in-dropdown.png`
- 优先级：P0
- 还原：**已验证**。`SELECT status FROM element WHERE element_no='10009';` → `ACTIVE`（测试结束时复核）

### TC-13　已引用但被停用的元素：只读回显 + 已停用标记，允许保存
- 对应：D3、FR-7 第二分支
- 前置数据：材质 `00158`（symbol `C17200`，唯一引用 `Be/10009`）；**紧接 TC-12 步骤 2 之后执行，`10009` 此时应已处于停用态，不要重复停用**
- 步骤：
  1. 确认 `10009` 当前为 INACTIVE（`SELECT status FROM element WHERE element_no='10009';`），若 TC-12 未先执行则先做 TC-12 步骤 1
  2. 打开材质 `00158` 编辑抽屉
  3. 不改任何字段，直接点「保存」
  4. 保存完成后，**不要在这里单独还原**，按上方顺序说明统一在 TC-12 步骤 4 一次性把 `10009` 改回 ACTIVE
- 期望结果：
  1. 步骤 2：该行元素正常回显选中态 `10009 / Be / 铍`（不清空、不标红），且选中态右侧出现灰色 `已停用` Tag
  2. 步骤 3：保存不被拦截，返回 200（D3：停用元素不阻断已引用行的保存）
- 实际结果：**PASS**（附执行修正：材质 `00158` 实际含 2 行元素 `Cu/铜`(row0,sort_order1) + `Be/铍`(row1,sort_order2)，Be 在 **row1** 非 row0，本条按 row1 验证，与 AC/需求语义不冲突）。row1 回显 "10009 / Be / 铍"（未清空未标红）+ 右侧灰色 `已停用` Tag（count=1）；点保存返回 message="材质已更新"（200，未被拦截）。证据：`tc13-inactive-tag.png` / `tc13-save-allowed.png`
- 优先级：P0
- 还原：本用例不单独还原，统一见 TC-12 步骤 4 及其「还原」行（已确认 `10009` 最终 ACTIVE；`00158` 两行内容保存前后一致，SQL 复核 Cu/98%/1、Be/2%/2 未变）

### TC-14　字典为空（0 条）
- 对应：FR-6、fronttask.md §5「字典为空」
- 前置数据：Chrome DevTools
- 步骤：
  1. F12 打开 Network 面板，找到 `GET /api/cpq/elements` 请求，右键 → `Override content`（或等效的响应体覆盖手段），把响应体替换为 `[]`
  2. 打开材质抽屉（新建或编辑均可），展开任一行元素下拉
- 期望结果：下拉候选区文案为 `未找到该元素，请先到「主数据维护 → 元素」维护后再选择`（与 TC-10 同一空态文案，FR-6 未区分「过滤无果」与「字典本身为空」两种情况）
- 实际结果：**PASS**（用 Playwright `page.route()` 拦截 `GET /api/cpq/elements` 返回 `[]`，等效 DevTools Override）。展开元素下拉，空态文案精确匹配期望文案。证据：`tc14-empty-dict.png`
- 优先级：P1
- 还原：**已验证**。测试脚本内 `page.unroute()` 解除拦截，之后请求恢复真实响应（后续用例正常拿到 37 条）

### TC-15　字典接口 5xx / 网络失败（含「不得误判正常行为脏数据」回归断言）
- 对应：AC-10（错误处理）、FR-10、api.md 错误码表
- ⚠️ **本用例针对一个已发现并已让前端返修的缺陷设计，是该缺陷的验收用例，缺一不可**：字典加载失败时若 `dictLoading` 仍被置为 `false`，回显逻辑会把「字典未就绪」误判成「字典里真的没有这个 elementCode」，导致**所有正常行**被误标为字典外脏值（每行飘红「原值「X」不在元素字典中」）且阻断保存。因此本用例**必须打开有正常数据的材质 `00005`**，不能用新建抽屉（新建抽屉的行本来就是空的，测不出这个误判）
- 前置数据：Chrome DevTools；材质 `00005`（3 行正常数据：`C/碳`、`Ni/镍`、`Ag/银`）
- 步骤：
  1. F12 Network 面板，对 `GET /api/cpq/elements` 设置「Block request URL」（或 Override 为 500 状态码空响应）
  2. 打开材质 `00005` 编辑抽屉
  3. 逐行检查 3 行「元素」列的表现
  4. 尝试点「保存」
- 期望结果：
  1. 页面顶部出现 `message.error`，文案含「元素字典加载失败」字样（`元素字典加载失败，请刷新重试`）
  2. 元素下拉的 `notFoundContent` 显示 `元素字典加载失败`，**不得**表现为「未找到该元素，请先到…」（区分「本来没数据」与「加载失败」两种空态，FR-10 明确要求）
  3. **（核心断言，缺陷验收点）** 3 行**均不得**出现红色「原值「C」/「Ni」/「Ag」不在元素字典中」提示 —— 字典加载失败 ≠ 数据本身是脏数据，不得对正常行发起「诬告」
  4. 步骤 4 若被拦截，`message.error` 的拦截文案**不得**使用「不在元素字典中，请重新选择」这套 FR-7/D4 脏值文案（那套文案专属「字典已就绪但查无此码」的场景，与「字典压根没加载成功」是两种不同错误，用户看到的提示必须能区分成因；具体应表现为「字典未加载完成，无法保存」一类提示，或禁用保存按钮，以实际返修实现为准，但**红字「原值…」误报与「不在元素字典中」误导文案这两条硬性不得出现**）
- 实际结果：**PASS（全部 4 项，缺陷 5534e47f 已修好）**。
  1. 用 `page.route()` 拦截 `GET /api/cpq/elements` 返回 500，打开材质 00005：顶部 toast="元素字典加载失败，请刷新重试" PASS
  2. 展开元素下拉，`notFoundContent`="元素字典加载失败"（不含"未找到该元素"字样）PASS
  3. **核心断言**：逐行检查 3 行（C/Ni/Ag），均**未出现**红色「原值「X」不在元素字典中」文案，`anyRedOriginal=false` PASS —— 确认代码评审必修项（"字典加载失败不再误判为字典外脏值"）已生效
  4. 点保存，拦截 toast="元素字典加载失败，无法校验元素，请关闭抽屉重新打开后再保存"；不含"不在元素字典中，请重新选择"字样 PASS
  - 证据：`tc15-load-failed-msg.png` / `tc15-notfound-content.png` / `tc15-rows-no-red.png` / `tc15-save-blocked.png`
  - 备注：已知文案瑕疵（新建场景下若字典失败，行内提示误写"暂无法显示**原选择**"，新建场景本无原选择）不影响本用例判定，主线已裁定不返工
- 优先级：P0
- 还原：**已验证**。测试脚本内 `page.unroute()` 解除拦截

### TC-16　全部 ACTIVE 元素已被选完，剩余行全部置灰
- 对应：FR-5 边界、fronttask.md §5「全部元素都已被其他行选完」
- 前置数据：Chrome DevTools（缩小字典规模便于验证）
- 步骤：
  1. 用 TC-14 同样手段 Override `GET /api/cpq/elements` 响应体为仅 2 条：
     `[{"id":"x1","elementNo":"10009","elementCode":"Be","elementName":"铍","status":"ACTIVE"},{"id":"x2","elementNo":"10014","elementCode":"Cr","elementName":"铬","status":"ACTIVE"}]`
  2. 打开新建材质抽屉（默认 1 行），添加 1 行使总行数为 2
  3. 第 1 行选 `10009 / Be / 铍`，第 2 行选 `10014 / Cr / 铬`
  4. 点「+ 添加元素」新增第 3 行，展开第 3 行下拉
- 期望结果：第 3 行下拉两个候选项 `10009 / Be / 铍`、`10014 / Cr / 铬` 均呈禁用置灰态，无可选项
- 实际结果：**PASS**（`page.route()` 拦截字典为仅 2 条）。第 1/2 行分别选中 Be / Cr 后，展开第 3 行下拉：候选 optCount=2，两项 class 均含 `ant-select-item-option-disabled`。证据：`tc16-all-selected-disabled.png`
- 优先级：P1
- 还原：**已验证**。测试脚本内 `page.unroute()` 解除拦截

### TC-17　编辑入口回显：老数据 element_code 为空串
- 对应：fronttask.md §5「老数据 `element_code` 为空串」
- 前置数据：需先构造脏测试行（执行后必须清理）
- 步骤：
  1. 先建一条一次性测试材质用于本用例（避免污染正式数据）：
     ```sql
     INSERT INTO material_recipe (id, code, symbol, recipe_type, sort_order, status, created_at, updated_at)
     VALUES (gen_random_uuid(), 'TC17-TEMP', 'TC17-TEMP', 'locked', 999, 'ACTIVE', now(), now())
     RETURNING id;
     -- 记录返回的 id 为 <tc17_id>
     INSERT INTO material_recipe_element (id, recipe_id, element_code, element_name, default_pct, is_locked, sort_order)
     VALUES (gen_random_uuid(), '<tc17_id>', '', '', 100, true, 1);
     ```
  2. 打开材质 `TC17-TEMP` 编辑抽屉
  3. 测试结束后清理：
     ```sql
     DELETE FROM material_recipe_element WHERE recipe_id='<tc17_id>';
     DELETE FROM material_recipe WHERE id='<tc17_id>';
     ```
- 期望结果：该行「元素」列表现为**未选择态**（空下拉，无红色 `unmatched` 提示），点「保存」被 FR-9 校验拦截，提示 `请为第 1 行选择元素`（不是 FR-7 的「不在字典中」提示，因为空串不进入 `unmatched` 分支，走「未选择」分支，见需求文档 §5 边界表最后一行）
- 实际结果：**PASS**。打开 TC17-TEMP：元素框显示占位符（未选中态），无红色提示（hasRedCount=0）；点保存 toast="请为第 1 行选择元素"，精确匹配期望（FR-9 分支，非 FR-7）。证据：`tc17-empty-code-row.png` / `tc17-save-blocked.png`
- 优先级：P2
- 还原：**已验证**。测试结束后 `DELETE FROM material_recipe_element/material_recipe WHERE ...`，复查 `SELECT count(*) FROM material_recipe WHERE code='TC17-TEMP';` = 0

---

## 四、异常

### TC-18　未选元素点保存被拦截
- 对应：AC-9（间接）、FR-9
- 前置数据：新建材质抽屉（默认第 1 行元素为空）
- 步骤：不选任何元素，直接点「保存」
- 期望结果：`message.error` 提示精确为 `请为第 1 行选择元素`，保存请求未发出（F12 Network 确认无新 `POST`）
- 实际结果：**PASS**（执行注记：需先填「材质编号/化学式」两个必填项，否则会被 antd `Form.validateFields()` 通用校验拦在更前面、直接静默 return，走不到元素校验分支——本条按此前置条件执行）。toast="请为第 1 行选择元素"，精确匹配。证据：`tc18-empty-row1.png`
- 优先级：P0

### TC-19　多行时未选元素定位到具体行号
- 对应：FR-9
- 前置数据：新建材质抽屉
- 步骤：
  1. 第 1 行选 `10001 / Ag / 银`
  2. 点「+ 添加元素」新增第 2 行，不选元素
  3. 点「保存」
- 期望结果：提示精确为 `请为第 2 行选择元素`（行号从 1 开始且定位到真实空行，不是恒定第 1 行）
- 实际结果：**PASS**。第 1 行选 Ag 后，第 2 行留空点保存，toast="请为第 2 行选择元素"，行号正确定位到第 2 行（非恒定第 1 行）。证据：`tc19-empty-row2.png`
- 优先级：P1

### TC-20　字典外脏值点保存被拦截（见 TC-04 步骤 3，此处补充多行场景）
- 对应：FR-7、D4
- 前置数据：材质 `992`
- 步骤：
  1. 打开材质 `992`（唯一 1 行为 unmatched 态）
  2. 添加第 2 行，选中 `10005 / Ni / 镍`
  3. 点「保存」
- 期望结果：保存仍被拦截（第 1 行 unmatched 未处理），`message.error` 文案含「不在元素字典中，请重新选择」；不会因为第 2 行合法就放行
- 实际结果：**部分 FAIL（同 BUG-0812-01）**。"不会因为第 2 行合法就放行" PASS：保存确实未放行（用 `00262` 添加第 2 行选 Ni 后保存，无"已更新"消息）。但拦截文案是 `请为第 1 行选择元素`，不含「不在元素字典中，请重新选择」—— FAIL，与 TC-04/05 同一缺陷（unmatched 行 elementNo 恒为 null，FR-9 检查抢在 FR-7 之前命中）。证据：`tc20-multirow-still-blocked.png`
- 优先级：P0

---

## 五、回归

### TC-21　后端既有校验仍生效：含量和 ≠ 100
- 对应：backtask.md §6 第 3 条、api.md 校验表
- 前置数据：新建材质抽屉
- 步骤：
  1. 第 1 行选 `10001 / Ag / 银`，默认 % 改为 `50`
  2. 添加第 2 行，选 `10005 / Ni / 镍`，默认 % 改为 `40`
  3. 点「保存」
- 期望结果：请求返回 HTTP 400，响应体错误信息精确为 `元素 default_pct 之和必须 = 100, 当前: 90`（`MaterialRecipeService.validateUpsert` 未被前端改动绕过）
- 实际结果：**PASS（分两层验证，执行方式需补充说明）**。
  - **UI 层观察**：填 Ag=50/Ni=40 点保存，实际弹出的是**前端自身**既有校验 `默认含量之和必须 = 100，当前 90`（`handleSubmit` 里 `sumOk` 检查，pre-existing、非本次改造）——请求根本**没有发到后端**，因此 UI 路径看不到题目描述的后端原始错误文案。这不是缺陷（前端提前拦截优于等后端报错，属既有行为），但说明"仅凭 UI 操作"无法验证"后端既有校验仍生效"这条 backtask.md §6 的诉求
  - **直接 API 层验证（真正验证的是这条）**：用 admin session `curl -X POST /api/cpq/material-recipes` 直接发送 `elements:[{Ag,50},{Ni,40}]`（绕开前端），返回 **HTTP 400**，响应体精确为 `{"code":400,"message":"元素 default_pct 之和必须 = 100, 当前: 90"}`，与期望文案逐字符一致。确认 `MaterialRecipeService.validateUpsert` 未被本次改动影响，且该请求**未在库中留下残留记录**（`SELECT count(*) FROM material_recipe WHERE code='TC21API'` = 0）
- 优先级：P0

### TC-22　`recipeType` 切换（locked→editable→partial）后元素选中态不丢
- 对应：fronttask.md §2.6「已知坑」、T9
- 前置数据：编辑材质 `00005`（3 行均已回显 `碳/镍/银`）
- 步骤：
  1. 打开材质 `00005` 编辑抽屉，确认 3 行元素回显正常
  2. 「类型」切换为 `editable`
  3. 「类型」再切换为 `partial`
  4. 「类型」切回 `locked`
  5. 每次切换后检查 3 行「元素」列
- 期望结果：每次切换后 3 行元素下拉选中态始终保持 `10012 / C / 碳`、`10005 / Ni / 镍`、`10001 / Ag / 银`，不出现清空/错位/`unmatched` 误报（`onRecipeTypeChange` 必须用展开语法透传 `elementNo`/`elementCode`/`elementName`/`unmatched`）
- 实际结果：**PASS**。切换前及 locked→editable→partial→locked 每一步后读取 3 行文本，均恒为 `["10012 / C / 碳","10005 / Ni / 镍","10001 / Ag / 银"]`，无丢失/错位。未点保存。证据：`tc22-after-cycle.png`
- 优先级：P0
- 提醒：本用例**不要点保存**，仅验证前端内存态；若误保存需用 TC-03 的 SQL 校验并按需修复数据（本次执行未保存）

### TC-23　材质库 Excel 导入路径不受影响
- 对应：backtask.md §6 第 5 条
- 前置数据：`POST /api/cpq/material-recipes/import/template` 下载的空白模板，或任一历史已验证过的导入 xlsx
- 步骤：
  1. 下载导入模板：`GET /api/cpq/material-recipes/import/template`
  2. 在模板「材质对应元素」sheet 填入 1 条全新测试材质编号（如 `TC23-TEMP`）+ 元素符号 `Ag`/含量 `100`
  3. 通过「材质管理」页面的导入入口上传该文件（`POST /api/cpq/material-recipes/import`）
  4. `SELECT element_no, element_code FROM material_recipe_element mre JOIN material_recipe r ON r.id=mre.recipe_id WHERE r.code='TC23-TEMP';`
- 期望结果：
  1. 导入返回 200，报告显示成功
  2. 步骤 4 查得该行 `element_no` **正常写入非空值**（导入路径走 `MaterialRecipeImportService`，不经过本次改造的 UI 保存路径，`element_no` 应仍按原逻辑回填，不受「UI 保存不写 element_no」的影响）
- 实际结果：**PASS**（用 xlsx 库构造模板同结构文件，`curl -F file=@... /material-recipes/import` 上传，因浏览器手工操作构造 xlsx 较繁琐改走接口层等效验证，导入逻辑与页面入口一致）。响应 `{"totalRows":1,"materialsUpserted":1,"elementRowsInserted":1,...}` HTTP 200；SQL 复查 `TC23-TEMP`：`element_code='Ag'`、`element_name='银'`、`default_pct=100.0000`、**`element_no='10001'`（非空）**，与期望一致。证据：curl 响应 JSON + SQL 输出（详见 test-report.md）
- 优先级：P1
- 还原：**已执行**。`DELETE FROM material_recipe_element/material_recipe WHERE code='TC23-TEMP'`，复查 count=0

### TC-24　`flyway_schema_history` 无新增行
- 对应：backtask.md §6 第 4 条
- 前置数据：无
- 步骤：
  1. 用例执行前：`SELECT max(version::int) FROM flyway_schema_history WHERE version ~ '^[0-9]+$';`（基线 = `385`）
  2. 完成本文档全部用例后再次执行同一 SQL
- 期望结果：两次结果一致（= `385`），本任务全程无 Flyway 迁移产生
- 实际结果：**PASS**。执行前 `max(version::int)=385`；全部 32 条用例跑完后再次查询仍为 `385`，一致。
- 优先级：P0

### TC-25　`GET /api/cpq/elements` 返回条数与库一致
- 对应：backtask.md §6 第 1 条
- 前置数据：无
- 步骤：
  1. 登录后 `curl` 调 `GET /api/cpq/elements`（带 session cookie）或直接在已登录浏览器 F12 Network 里看响应体
  2. `SELECT count(*) FROM element;`
- 期望结果：接口返回 200，响应数组长度 = SQL 查询数（当前 = 37）
- 实际结果：**PASS**。Playwright 拦截打开抽屉时的响应：status=200，`body.length=37`，与 `SELECT count(*) FROM element` = 37 一致。
- 优先级：P1

---

## 六、权限

### TC-26　非 SYSTEM_ADMIN 角色能读元素字典（下拉正常有值）
- 对应：D12、api.md API-1 鉴权说明
- 前置数据：`test_finance_c87a27ab`（`PRICING_MANAGER`，ACTIVE）或临时新建的 ACTIVE `SALES_REP` 账号
- 步骤：
  1. 用该账号登录系统
  2. 打开「主数据维护 → 材质」→ 新建或编辑材质抽屉
  3. 展开任一行元素下拉
- 期望结果：`GET /api/cpq/elements` 返回 200，下拉候选正常展示 37 条 ACTIVE 元素（与 SYSTEM_ADMIN 登录时看到的候选一致）
- 实际结果：**PASS**（账号替代说明：`test_finance_c87a27ab` 密码不可得，改用 admin API `POST /api/cpq/users` 临时新建 `tc0812_sales`/`SALES_REP`/ACTIVE，测完立即 `PATCH status=INACTIVE` 停用，见 test-report.md 权限验证方法）。`GET /elements` 返回 status=200，响应体 `body.length=37`；下拉展开可见候选（DOM 因 antd Select 虚拟滚动只渲染约 10 个可视节点，属正常表现，不作判定依据——判定以 API 响应长度为准，同 TC-25 口径）。证据：`tc26-nonadmin-dropdown.png`
- 优先级：P1

### TC-27　非 SYSTEM_ADMIN 角色保存材质被拒绝
- 对应：api.md API-2 鉴权说明（写操作仍为 SYSTEM_ADMIN）
- 前置数据：同 TC-26 账号
- 步骤：
  1. 同 TC-26 账号登录，打开新建材质抽屉，正常选好元素
  2. 点「保存」
- 期望结果：`POST /api/cpq/material-recipes` 返回 HTTP 403，响应体含 `无权限访问`；前端展示保存失败提示；本次改造**未放宽**写权限
- 实际结果：**PASS**（账号同 TC-26 说明，用 `tc0812_sales`/SALES_REP）。选好元素+填好编号后点保存，`POST /api/cpq/material-recipes` 返回 **403**，`message.error`="无权限访问"；未创建材质（该请求从未落库）。证据：`tc27-save-403.png`
- 优先级：P0

---

## 七、请求面（AC-10 / AC-11）

### TC-28　抽屉打开时字典请求恰好 1 次
- 对应：AC-10
- 前置数据：F12 Network 面板清空后打开材质抽屉（新建或编辑均可）
- 步骤：
  1. 清空 Network 面板记录
  2. 打开材质抽屉
  3. 统计 `GET /api/cpq/elements` 的请求条数
- 期望结果：恰好 1 条
- 实际结果：**PASS**。打开抽屉后统计 `GET /api/cpq/elements` 请求数=1，恰好 1 次。证据：`tc28-29-network.png`
- 优先级：P0

### TC-29　下拉连续过滤不产生新请求
- 对应：AC-10、D11
- 前置数据：接 TC-28，抽屉已打开
- 步骤：
  1. 展开任一行元素下拉，依次输入 `1`、`10`、`100`、`1000`、`10001`（5 次不同输入，每次间隔清空 Network 计数基线不变）
- 期望结果：全程 Network 面板中除 TC-28 打开时的那 1 条 `GET /api/cpq/elements` 外，**不产生任何新请求**（过滤在前端本地完成，不打 `keyword` 参数）
- 实际结果：**PASS**。连续输入 `1`/`10`/`100`/`1000`/`10001` 共 5 次后，`GET /api/cpq/elements` 请求计数仍为 1（afterOpen=1, afterFilter=1），未新增请求。证据：`tc28-29-network.png`
- 优先级：P0

### TC-30　保存只发 1 次请求，请求体字段集合与改造前一致
- 对应：AC-10、AC-11
- 前置数据：新建材质，选好合法元素
- 步骤：
  1. 清空 Network 面板
  2. 点「保存」
  3. 查看发出的 `POST /api/cpq/material-recipes` 请求体 `elements[]` 数组
- 期望结果：
  1. 恰好 1 次 `POST`（或编辑场景下 1 次 `PUT`），无重复提交
  2. `elements[]` 每项字段集合 **⊆** `elementCode`、`elementName`、`defaultPct`、`minPct`、`maxPct`、`isLocked`、`sortOrder` 这 7 个键（`isLocked=true` 时 `minPct`/`maxPct` 传 `undefined` 会被 `JSON.stringify` 丢键，此时该项只会有 5 个键，键数量不是断言点）；**硬断言只有一条：不包含 `elementNo` 键**（D9：本期不落 `elementNo` 到提交体，这条键缺席才是本用例的判定依据）
- 实际结果：**PASS**。postCount=1（恰 1 次 POST）；实际捕获请求体 `elements=[{"elementCode":"Ag","elementName":"银","defaultPct":"100","isLocked":true,"sortOrder":1}]`（`isLocked=true` 场景 minPct/maxPct 确实被 JSON.stringify 丢键，5 个键，属预期）；键集合 ⊆ 7 键白名单 PASS；**不含 `elementNo`** PASS。证据：`tc30-request-body.png`
- 优先级：P0

---

## 八、新建入口端到端落库（补）

### TC-32　新建材质 → 选元素 → 保存 → DB 落库正确（P0，主路径）
- 对应：需求文档主流程（新建入口此前只有 TC-30 验请求体，没有端到端落库断言，本条补齐）
- 前置数据：无（用例内建一次性测试材质，结束后清理）
- 步骤：
  1. 打开「主数据维护 → 材质」，点「新建材质」
  2. 材质编号填 `TC32-TEMP`，符号填 `TC32-TEMP`，类型选 `locked`
  3. 第 1 行元素下拉选 `10001 / Ag / 银`，默认 % 改为 `60`
  4. 点「+ 添加元素」新增第 2 行，选 `10005 / Ni / 镍`，默认 % 改为 `40`
  5. 点「保存」
  6. `SELECT id FROM material_recipe WHERE code='TC32-TEMP';`（记为 `<tc32_id>`）
  7. `SELECT element_code, element_name, default_pct, is_locked, sort_order FROM material_recipe_element WHERE recipe_id='<tc32_id>' ORDER BY sort_order;`
- 期望结果：
  1. 保存返回 200，抽屉关闭，材质列表出现 `TC32-TEMP`
  2. 步骤 6 查到唯一 1 条 `material_recipe` 记录
  3. 步骤 7 查到 2 行，**逐字段**：
     - 第 1 行：`element_code='Ag'`、`element_name='银'`（**是字典值，不是用户没输入过的编号 `10001`**）、`default_pct=60.0000`、`is_locked=true`、`sort_order=1`
     - 第 2 行：`element_code='Ni'`、`element_name='镍'`、`default_pct=40.0000`、`is_locked=true`、`sort_order=2`
- 实际结果：**PASS**。保存 message="材质已创建"（200）；`SELECT id FROM material_recipe WHERE code='TC32-TEMP'` 唯一 1 条；元素行逐字段核对：row1 `Ag/银/60.0000/is_locked=t/sort_order=1`，row2 `Ni/镍/40.0000/is_locked=t/sort_order=2`，与期望完全一致（落库值是字典的 `Ag`/`银`，不是用户输入过的编号 `10001`）。证据：`tc32-saved.png` + SQL 输出
- 优先级：P0
- 清理：**已执行**。测试完成后 `DELETE FROM material_recipe_element/material_recipe WHERE code='TC32-TEMP'`，复查 `SELECT count(*) FROM material_recipe WHERE code='TC32-TEMP'` = 0

---

## 九、工程自检（AC-12）

### TC-31　前端编译与静态自检
- 对应：AC-12
- 前置数据：worktree `/home/joii/project/cpq/.claude/worktrees/task-0812-material-element-select`
- 步骤：
  1. `tsc` 检查**必须在 worktree 内的 `cpq-frontend/` 下跑**（不是主仓 `/home/joii/project/cpq/cpq-frontend`）：
     `cd /home/joii/project/cpq/.claude/worktrees/task-0812-material-element-select/cpq-frontend && npx tsc --noEmit -p tsconfig.json`
     跑到主仓 = 测的是未包含本次改动的代码树 = 假绿，见 `任务平台规则.md` §6「后端测试」同款陷阱在前端同样适用
  2. Vite 200 验证：共享的 `5174` dev server 跑的是**主工作区已合并代码**，此刻特性分支尚未合并，直接 `curl localhost:5174` 验的是旧文件，验完也是假绿。**二选一**：
     - **方案 A（合并前验证，推荐）**：在 worktree 内起临时 vite（不装依赖，软链复用主仓 `node_modules`）：
       ```bash
       cd /home/joii/project/cpq/.claude/worktrees/task-0812-material-element-select/cpq-frontend
       ln -sfn /home/joii/project/cpq/cpq-frontend/node_modules node_modules
       npx vite --port 5199 &
       sleep 3
       curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:5199/src/pages/config/MaterialRecipeEditDrawer.tsx
       kill %1   # 验完关掉临时进程，不留后台残留
       ```
       期望 `200`
     - **方案 B（标注延后）**：本步骤标注「延后到合并 master 后于 5174 复验」，在 `test-report.md` 里注明执行时机是合并后而非本轮，不得在合并前用 5174 的 200 当证据
- 期望结果：步骤 1 输出 0 个 error；步骤 2（方案 A 或方案 B 复验时）输出 `200`
- 实际结果：**PASS（方案 A 执行）**。`cd .../worktrees/.../cpq-frontend && npx tsc --noEmit -p tsconfig.json` → 输出为空，**0 error**；worktree 内软链 `node_modules` 起临时 vite（`--port 5199 --host 0.0.0.0`）：`curl http://localhost:5199/` → **200**，`curl http://localhost:5199/src/pages/config/MaterialRecipeEditDrawer.tsx` → **200**。全部 32 条用例均在此 5199 临时前端 + 真实后端 8081 + 真实库 `cpq_db_0724` 环境下执行。测试结束后已 `pkill` 该临时 vite 进程，`ps aux | grep 5199` 确认无残留。
- 优先级：P0

---

## AC 覆盖对照表

| AC | 覆盖用例 |
|---|---|
| AC-1 | TC-01 |
| AC-2 | TC-02（部分）、TC-06、TC-07、TC-08 |
| AC-3 | TC-02 |
| AC-4 | TC-12 |
| AC-5 | TC-11 |
| AC-6 | TC-10 |
| AC-7 | TC-03 |
| AC-8 | TC-04、TC-05 |
| AC-9 | TC-01、TC-18（间接） |
| AC-10 | TC-15（错误提示 + 误报防护）、TC-28、TC-29、TC-30 |
| AC-11 | TC-30 |
| AC-12 | TC-31 |
| 主流程新建端到端落库（补） | TC-32 |

FR 补充覆盖（未被 AC 直接点名但需求文档 §4 列出）：FR-5/D7→TC-11、TC-16；FR-6→TC-10、TC-14；FR-7→TC-04/05/13/17/20；FR-9→TC-18/19；FR-10→TC-15；D3→TC-13；D12/权限→TC-26/27；backtask §6 回归→TC-21/22/23/24/25；新建端到端落库→TC-32。

共 **32 条**用例（TC-01 ~ TC-32）。

## 返修记录（主线首轮审核，2026-08-12）

| # | 问题 | 处理 |
|---|---|---|
| 硬错误 1 | TC-31 用共享 5174 验未合并代码，假绿 | 改为 worktree 内临时 vite（端口 5199）方案 A / 合并后复验方案 B 二选一；`tsc` 明确要求在 worktree `cpq-frontend/` 下跑 |
| 硬错误 2 | TC-15 未断言「加载失败不得误判正常行为脏数据」 | 补 3 条断言：00005 三行不得飘红「原值…」、拦截文案不得用「不在元素字典中」这套脏值文案；改用 `00005` 而非新建抽屉执行 |
| 缺失 3 | 新建入口缺端到端落库用例 | 新增 TC-32，`TC32-TEMP` 材质 2 行元素落库 SQL 断言 + 清理 |
| 数据风险 4 | TC-03 未声明 `element_no` 变 NULL 是既有行为；TC-04/05 会耗尽仅有的 2 条脏数据样本 | TC-03 加说明；TC-05 改为只验阻断态不重选保存，保留 `00262` 为活样本，并给 992 的脏数据重建 SQL |
| 裁决 5 | TC-17 空串行期望 | 维持不变（主线已裁决：空串按「未选择」处理） |
| 小改 6 | TC-12/13 停用元素的恢复方式与执行顺序不明确 | 明确恢复只能走「元素」Tab 编辑抽屉 PUT，不是再 DELETE 一次；固定「先 12 后 13、统一还原一次」的执行顺序 |
