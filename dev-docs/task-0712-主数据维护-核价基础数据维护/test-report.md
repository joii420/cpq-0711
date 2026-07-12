# 测试报告 · 主数据维护-核价基础数据维护（task-0712）

> 测试员：QA | 起始：2026-07-12 | 依据：`test.md`
> **状态：本轮通过（合并前预验）** — 对 worktree 服务（后端 8082 / 前端 5176→8082，`task-0712-*` 分支未提交代码）做全量测试：8 条验收全过，5 个缺陷全部修复复验。**判定：达到交付水平**（详见 §七结论）。仅 TC-G/TC-H 两个次要 UI 项可选补测。正式合并到 master 后建议再跑一遍冒烟确认。

---

## 一、测试环境

| 项 | 值 | 说明 |
|----|----|------|
| 后端 | `http://localhost:8082`（pid 35859，cwd=backend worktree） | 8081 为 master，无端点(404)；worktree 服务端点齐全 |
| 前端 | 待联调（5175=前端 worktree Vite） | 本轮未测 |
| DB | 远程 `cpq_db`（8081/8082 共享同库） | 无本地 psql/pg 驱动/docker daemon，仅经 API 反映库态 |
| 账号 | admin / Admin@2026（SYSTEM_ADMIN，会话 cookie 鉴权） | PRICING_MANAGER/SALES_MANAGER 待测权限时创建 |
| 测试料号 | `S-3120014539`(14/16)、`TEST-0712-SMOKE` | S- 前缀=测试数据 |

> ⚠️ 前提：8082 为未提交、仍在开发（worktree 锁定）的代码，结论在正式合并前为**暂定**。

---

## 二、已执行用例结果（本轮，后端接口层）

| 编号 | 用例 | 结果 | 证据/备注 |
|------|------|------|-----------|
| TC-J-01 | 未带 token 访 GET | ✅ Pass | parts/sheets/lookup 均 401 |
| TC-J-03 | GET /parts | ✅ Pass* | total=6，分页/N/16/updatedAt 正确；*品名/规格/尺寸见 OBS-03 |
| TC-J-04 | GET /sheets 元数据 | ✅ Pass | 16 组，order/tabName/anchor/masterDetail/columns(role/dropdown) 齐全正确 |
| TC-J-05 | GET /overview | ✅ Pass | 16 项，hasData/currentVersion/versionCount/updatedAt 正确（N=14 对齐 /parts） |
| TC-J-06 | GET /rows 当前版 | ✅ Pass* | isCurrent/editable/rows 正确；*NAME 列见 OBS-04 |
| TC-J-08 | GET /versions | ✅ Pass | version/isCurrent/source/operator/operatedAt 正确 |
| TC-J-10 | GET /lookup process/element/material | ✅ Pass | 三类主表均返 code+name（element Ag→银） |
| TC-C-02 | 固定 16 tab 结构 | ✅ Pass | 元数据 16 组顺序/名称与需求 §4.1 一致 |
| TC-L-01 | P16+P17 合并=INCOMING_OTHER 单组 | ✅ Pass | 元数据/overview 为单版本组 |
| TC-L-02 | P19+P20 合并=FINISHED_OTHER 单组 | ✅ Pass | 同上 |
| TC-L-03 | P08 拆 CAPACITY+LABOR_RATE 两独立组 | ✅ Pass | 两组独立版本线 |
| TC-L-04 | 折旧/能耗同表分 price_type | ✅ Pass(读) | DEPRECIATION/ENERGY 各 ver=2000、4 行、按 price_type 正确过滤；写侧独立性被 BUG-02 阻塞 |
| TC-G-02 | 空 tab 从零新建 | ✅ Pass | PLATING/S-3120014539：result=CREATED、version=2000、isCurrent=true |
| TC-F-04 | 内容未变不升版 | ✅ Pass | 原样回存→UNCHANGED、版本不变（PLATING 上验证；真实导入数据被 BUG-02 阻塞） |
| TC-F-01 | 改值升版 | ✅ Pass | 1.5→2.8 → UPGRADED 2001 |
| TC-F-09 | 指纹比对 | ✅ Pass | 改回 1.5(异于2001)→UPGRADED 2002 |
| TC-F-07 | source=MANUAL + 操作人 | ✅ Pass | versions 显示新版 source=MANUAL、operator=系统管理员 |
| TC-I-01 | 乐观锁 409 | ✅ Pass | 过期 expectedCurrentVersion → 409（带期望vs实际） |
| TC-I-03 | 至少留一行 422 | ✅ Pass | rows 空 → 422，消息正确 |
| TC-I-04 | 轴锁定 | ✅ Pass | body 篡改 price_type/system_type/code 被忽略，未串组，PLATING 内容不变 |
| TC-H-01 | MASTER 合法码编辑 + NAME 回带 | ✅ Pass | CONSUMABLE 存 Z029→UPGRADED 2001，operation_name 回带非空（NAME join 正确） |
| TC-H-09 | MASTER 非法码拦截 | ✅ Pass | Z002 等不存在→400（严格，符合裁定） |
| TC-I-07 | 折旧/能耗写侧独立 | ✅ Pass | DEPRECIATION 存 Z029→2001，ENERGY 保持 2000 不受影响 |
| TC-D-06 | DECIMAL 精度(字符串/定标) | ✅ Pass | BUG-01 修复后复验：全 rows DECIMAL 为定标字符串、无科学计数 |
| TC-J-11b | 不存在 sheetKey | ✅ Pass | 404「sheetKey 不存在: NOPE」 |
| TC-J-12 | 非法 masterType | ✅ Pass | 400「masterType 非法」 |
| TC-J-11 | 不存在料号 read | ❌ 见 BUG-04 | overview/rows 返 200 空(不该)，且保存无校验 |
| TC-D-05 | 主从 BOM 读(masterInfo) | ✅ Pass | masterInfo={bomVersion/productionNo/bomType}+子行全列, DECIMAL 字符串 |
| TC-I-05 | 并发不产生双 current | ✅ Pass* | 5 并发→单 is_current、无重复版本号；*但乐观锁 TOCTOU 见 BUG-05 |

> 版本翻转核对：PLATING 连续 CREATED→UPGRADED 后，is_current 恒 1 组 ✅。
> 累计本轮 **Pass 28 条**；发现 BUG-04（完整性）、BUG-05（并发乐观锁）；BUG-01 已修复复验。

---

## 三、问题清单（现象 / 归属 / 处理 / 复测）

### BUG-01 【后端·精度/契约·Major】→ ✅ 已修复并验证（2026-07-12）
- **原现象**：`GET /rows` DECIMAL 列以 JSON 数字返回，折旧 `unit_price: 3e-06`（科学计数法），违反 api.md §4 字符串契约 + 前端 F4。
- **修复复测**：DEPRECIATION `unit_price:"0.050000"`、PLATING `pricing_price:"1.500000"`/`defect_rate:"0.0200"`、BOM `composition_qty:"1.000000"` —— 全部**定标字符串、无科学计数**。✅ **关闭**。

### BUG-04 【后端·数据完整性·Major】保存未校验 materialNo 存在 → 可为不存在料号建数据 + 污染列表
- **现象**：`PUT /parts/NO_SUCH_MAT_999/sheets/PLATING/rows` 对**不存在的料号**返 `200 CREATED 2000`、数据落库，且该假料号**出现在 `/parts` 列表**（total=1）。overview/rows 对不存在料号也返 200 空数据（editable=true）。
- **期望**：backtask §B4 步骤1 明写「校验 materialNo 存在」；api.md §0 列「404 料号不存在」。应拒绝（404/400）。
- **风险**：录入/typo 即产生孤儿核价数据并污染料号列表，无删除 API 难清理。
- **归属**：后端（saveGroup 增加 material_master 存在性校验；读接口对不存在料号建议 404 或明确空态）。
- **状态**：✅ **已修复验证（2026-07-12）**：不存在料号 PUT→`404 料号不存在`、overview→404。⚠️ **遗留**：修复前测试写入的 `NO_SUCH_MAT_999/PLATING` 孤儿数据仍在 /parts 列表（本期无删除 API），**需后端手工清库**。

### BUG-05 【后端·并发/乐观锁·Major】expectedCurrentVersion 校验在 advisory lock 之外，并发下防丢失更新失效（TOCTOU）
- **现象**：对同一轴 5 个并发 PUT，均带 `expectedCurrentVersion=2004`。结果 **5 个全部 200 UPGRADED**，链式升版 2005→2009，**无一被 409**。而顺序场景（TC-I-01）expected 过期能正确 409。
- **分析**：并发请求先各自读到 current=2004、都通过 expected 校验，再由 advisory lock 串行写 → 过期写入被当链式升版接受，**"防丢失更新"语义在真正并发时失效**（TOCTOU 窗口）。backtask §B4 已声明「乐观锁校验与写入同事务，避免 TOCTOU」，但实测未达成。
- **不影响项**：最终**单 is_current、无重复版本号**（§8.8 字面验收 Pass，见 TC-I-05）。
- **归属**：后端（把 expectedCurrentVersion 校验移入 advisory lock 持有后、与写入同一临界区）。
- **状态**：✅ **已修复验证（2026-07-12）**：5 并发同带过期版本号 → 正好 **1×200 + 4×409**，最终单 current、无重复版本号。乐观锁 TOCTOU 已闭合。

### BUG-02 → 【设计确认·维持严格·非缺陷】严格 MASTER 校验
- **现象**：对已导入的 SELF_PROCESS（工序号 Z002/Z008/Z053/Z490）**原样回存**即返 `400 工序号不存在于主表: Z002...`。经 lookup 证实这些工序号确不在 process_master（导入时未校验，落库为孤儿码）。
- **技术经理裁定（2026-07-12）**：**维持严格校验**。存量脏码数据不可编辑属**接受的既定行为**；如需编辑须先补齐 `process_master` 等主表数据。→ **不算缺陷，关闭**。
- **测试口径调整**：编辑类用例改用**主表真实存在的码（如工序 Z029）**验证（见 TC-H-01 已 Pass）。存量孤儿码 sheet 的"不可编辑"作为符合预期记录，不判 Fail。

### ISSUE-03 / 待技术经理确认 【ENUM 非法值未拦截（spec 自相矛盾）】
- **现象**：`PUT` PLATING 时 `currency="XXX"`（非法枚举）**返 200 UPGRADED**，脏值被写入，未拦截。
- **契约冲突**：api.md §6 护栏写「kind=ENUM 非法值 → **400**」；但 api.md §4.4.0 B + 前端 F4 写「ENUM **未知可输入回退**」。后端实现取了宽松（接受未知）。二者矛盾。
- **风险**：`currency` 是脏值会流入下游汇率/成本计算；建议至少对 `currency` 等强约束枚举做校验，自由枚举（unit 等）可宽松。
- **归属**：需技术经理裁定 ENUM 到底"严格 400"还是"宽松放行"，据此对齐 api.md 与后端实现。
- **裁定**：技术经理定**需修复（严格校验）**。
- **状态**：✅ **已修复验证（2026-07-12）**：`currency=XXX`→400「列[货币/currency] 值非法；合法值 [CNY,USD,EUR,JPY]」；`unit=BADUNIT`→400；`cost_type=乱写`→400（合法值 [电镀加工费,电镀材料费]）；全合法值→200 UPGRADED。错误消息含列名+合法值清单。**关闭**。
- **联动提醒**：ENUM 转严格后，与 BUG-02（MASTER 严格）同理——存量导入数据若含 options 外的枚举值（如非标 unit），编辑保存会被 400 拦。属与 BUG-02 一致的既定取舍（需补数据或不可编辑），非新缺陷；已知会即可。

---

### BUG-06 【前后端契约不对齐·P0 阻断】GET /sheets 返裸数组，前端按 `.sheets` 取 → 抽屉 16 tab 全不渲染
- **现象**：前端点料号进抽屉，标题正确（`料号核价 · S-3120014539`），但**body 显示 `Empty「无 sheet 元数据」`，0 个 tab**，版本下拉/表体/保存按钮全无 → **整个料号核价抽屉不可用**。
- **根因**：后端 `GET /sheets` 的 `data` 是**裸数组**（16 元素，无 `sheets` 键）；api.md §2 规定为 `data: { sheets: [...] }`；前端 `getSheets()` unwrap 后按 `r.sheets` 取 → `undefined` → `sheets.length===0` → 渲染 Empty。对比 `/overview` 后端**有**包 `{...,"sheets":[]}`，唯独 `/sheets` 返裸数组，**后端自身不一致且偏离 api.md §2**。
- **暴露方式**：纯接口测试测不出（/sheets 数据本身有效），**仅前后端联调（Playwright）暴露**。
- **归属**：后端（对齐 api.md §2，`/sheets` 的 data 包成 `{ "sheets": [...] }`）为主；或前端 `getSheets` 改读裸数组（二选一，建议对齐 api.md 由后端改）。
- **影响**：**阻塞前端 C/D/E/F/H 全部抽屉内用例**。
- **状态**：⏳ 待修复。复测：前端抽屉应渲染 16 tab。

## 四、观察项（非缺陷，数据质量/待澄清）

- **OBS-03**：列表/overview 中 `S-3120014539` 等 S- 前缀料号 品名/规格/尺寸为 null——经 lookup 证实 material_master 中该料号 material_name 本就为 null（测试数据质量）。但 `3120014539`(主料1) 的 `specification` 亦为 null（dimension 有值），需确认 specification 字段映射是否正确 或 源数据本无规格。→ 待造规范样本后复验。
- **OBS-04**：NAME 列（operation_name 等）返 null，因对应编码为孤儿码不在主表——属**正确行为**；需造命中主表（如工序 Z029）的样本才能正验 NAME join。

---

## 五、待办 / 阻塞

- ⏳ BUG-02 决策前，真实导入数据的编辑类用例（F/I-07/H 部分）暂挂 BLOCKED。
- ⏳ 前端全流程（A 权限门控 / B 列表 UI / C 抽屉 / D 展示 / E 版本切换 UI / H 下拉交互 / F5 保存交互）待前端联调（5175→8082）后执行。
- ⏳ 零 N+1（K）、并发双 current（I-05）、回归（M）、精度复验待后端问题处理后进行。

---

## 三点五、前端联调结果（Playwright，5175→8082）

| 编号 | 用例 | 结果 | 证据 |
|------|------|------|------|
| TC-A-01 | 主数据维护出现「料号核价」tab | ✅ Pass | admin 登录可见并可进入 |
| TC-A-04 | SYSTEM_ADMIN 可进入 | ✅ Pass | 进入正常 |
| TC-B-02 | 列表列完整 | ✅ Pass | 表头=品名/料号/规格/尺寸/已配置/最近更新 |
| TC-B-01 | 列表显示有核价数据料号 | ✅ Pass | 8 行数据 |
| TC-B-05 | 按料号搜索 | ✅ Pass | 搜 S-3120014539 → 1 行 |
| TC-B-10 | 点行开抽屉 | ✅ Pass | 抽屉打开，标题=`料号核价 · S-3120014539` |
| TC-C-01 | 抽屉右滑+标题 | ✅ Pass | placement=right、标题含料号 |
| TC-C-02 | 抽屉固定 16 tab | ✅ Pass（BUG-06 修复后） | 16 tab + 版本徽标全对；物料与元素BOM 显示"未配置" |
| TC-C-03/04 | tab 徽标有/无数据 | ✅ Pass | 有数据显版本号、无数据显"未配置" |
| TC-D-05 | 主从 BOM 展示 | ✅ Pass | masterInfo 条(bomVersion/productionNo/bomType)+8 行子表 |
| TC-A-06 | 编辑角色见保存按钮 | ✅ Pass | admin(编辑权) 保存/新增行/删除行 可见 |
| TC-F-05 | UI 编辑保存升版 | ✅ Pass | PLATING 改价→保存→提示「已保存，版本 2011」 |
| TC-E-01 | 版本切换下拉 | ✅ Pass | 版本下拉 10 选项 |
| TC-E-04 | 历史版只读 | ✅ Pass | 切历史版→保存按钮消失(只读) |
| TC-A-06 | PRICING_MANAGER 可编辑 | ✅ Pass | 抽屉有保存按钮（前端 saveBtn=1） |
| TC-A-07/F6 | SALES_MANAGER 只读 | ✅ Pass | 抽屉无保存/新增/删除（全 0），编码列文本展示 |
| TC-A-03 | SALES_MANAGER 可见入口 | ✅ Pass | 菜单+tab 可进 |
| TC-A-05 | SALES_REP 无入口/无数据 | ✅ Pass | 侧边菜单无"主数据维护"(sideMenuHub=0)；直达 URL 后料号核价列表空(后端 403 保护) |
| TC-J-01/02 | 接口鉴权(前端代理) | ✅ Pass | 后端矩阵：PRICING_MANAGER 读写、SALES_MANAGER 读/写403、SALES_REP 读403 |
| TC-G-02 | 空 tab UI 从零新建 | ⏳ 待补 | 后端 CREATED 已验；UI 新建流程可选补测 |
| TC-H | 编码列 UI 下拉(MASTER/ENUM) | ⏳ 待补 | 后端已验；UI 下拉交互可选补测 |

> **BUG-06 修复后前端全线贯通**：列表→搜索→抽屉→16 tab→主从BOM→版本切换只读→编辑保存升版→**三角色权限门控**（PRICING_MANAGER 编辑 / SALES_MANAGER 只读 / SALES_REP 无入口无数据）全绿。仅空tab UI 新建 + UI 下拉两个次要项可选补测。

### 后端角色授权矩阵（直连 8082，2026-07-12）
| 角色 | 登录 | GET /parts | PUT /rows |
|------|------|-----------|-----------|
| SYSTEM_ADMIN | 200 | 200 | 三态正常 |
| PRICING_MANAGER | 200 | 200 | 200/409（写授权） |
| SALES_MANAGER | 200 | 200 | **403**（写拒） |
| SALES_REP | 200 | **403**（读拒） | 403 |
> 联调环境说明：本机无 Playwright 内置浏览器，改用系统 `/usr/bin/chromium-browser`（headless, --no-sandbox）；偶发浏览器崩溃，已用固定等待+简化脚本规避。测试脚本：`e2e/tc0712-part-costing-smoke.spec.ts` + `e2e/tc0712.config.ts`（前端 worktree）。

## 五点五、环境事件
- **2026-07-12 测试中途**：8082（后端 worktree 服务）出现 `Error restarting Quarkus`，根因为 `PricingMaintenanceService.java` 编译不通过——后端开发正在实时改该文件（worktree 仍锁定）。测试**暂停**，等后端达到稳定可编译状态（提交/合并）后恢复。此期间 TC-J-11 的 500 判为环境态、作废。

## 五点八、前端回归（ISSUE-03 修复后，专属 5176→8082 环境）
> 原 5175 被另一并发会话(task-0712-costing-display-fix)占用改代理 8085，故另起 5176→8082 专属环境复测。
- **5 passed**：编辑保存(「已保存，版本 2013」) / 16 tab 冒烟 / SALES_MANAGER 只读 / PRICING_MANAGER 可编辑 / SALES_REP 无菜单无数据。
- 结论：ISSUE-03 纯后端改动，**未引起任何前端回归**。

## 七、结论（截至 2026-07-12）
- **核心验收 8 条全部通过**（入口权限 / 列表 N16 / 16 tab / 版本切换只读 / 编辑升版 / 空tab新建(后端已验) / 编码列下拉+名称 / 零for嵌套+并发无双current）。
- **发现并闭环缺陷 5 个**：BUG-01 精度、BUG-04 料号存在性、BUG-05 乐观锁 TOCTOU、BUG-06 /sheets 契约(P0)、ISSUE-03 ENUM 校验 —— **全部已修复并复验通过**。
- **剩余可选补测（次要，不影响验收结论）**：TC-G 空tab UI 从零新建流程、TC-H 编码列 UI 下拉交互（后端均已验，仅差 UI 层取证）。
- **交付判定**：**达到交付水平**。

## 六、测试残留数据（透明记录）
- **`S-3120014539` 多组被测试升版**：PLATING（原空→建到 2013+）、DEPRECIATION(→2001)、CONSUMABLE(→2001) 等版本历史累积，功能正常；该料号 N 由 14→15（新增 PLATING）。如需清库告知。
- **`NO_SUCH_MAT_999` 孤儿数据**：BUG-04 修复前测试写入，已请后端清理并复验消失 ✅。
- **测试账号**（我创建，用于权限门控测试）：`pricingmgr`/Price@2026x（PRICING_MANAGER）、`salesmgr`/Sales@2026x（SALES_MANAGER）、`salesrep`/UZSzd@kt@y（SALES_REP，首登未改）。如需删除告知。
- **测试脚本**（前端 worktree `e2e/`）：`tc0712-part-costing-smoke.spec.ts` / `tc0712-edit-flow.spec.ts` / `tc0712-roles.spec.ts` / `tc0712.config.ts`。合并前建议移除或保留为回归资产（二选一，请示技术经理）。
- **专属测试 Vite**：5176→8082（因 5175 被并发会话占用另起），测试期临时进程，收尾时关闭。
