# 测试用例文档 · task-0708 材质库规范化与导入

> 测试负责人:开发需求测试员(QA)｜优先级:P0｜编写日期:2026-07-09
> 依据:`需求说明.md §8` + `backtask.md`(8 锁定决策 + B1~B6 + 校验/×100/Upsert/性能)+ `api.md`(接口契约)+ `fronttask.md`(F1~F5)+ `进度与验收.md`(12 项终验收 + 5 雷区)。
> 测试文件:`dev-docs/task-0708-材质库规范澄清/材质库.xlsx`(12 Sheet,只用 `材质编号` + `材质对应元素`)。
> 判定原则:导入按 8 决策正确落库(×100 / 增量 Upsert 非清空 / 脏行跳过报告 / 元素主表同步)+ 列表搜索排序时间字段 + 编辑抽屉改造 + 性能 1000 行 <3s + 无下游回归。
> 🩸 **铁律**:计数类断言一律以**实测文件推演的真实预期值**为准,**不照抄文档里的示例数字**(示例 254/11/620 为占位;真实验收基线=**253 落库 + 1 跳**,见 R1 裁决)。

---

## 0. 一句话验收目标

主数据维护→材质:能导入 `材质库.xlsx`(只读两 sheet、含量×100、按材质编号增量 Upsert、脏行跳过并报告)、能新建/编辑/停用、能按材质编号/材质名称/元素搜索、列表有创建/修改时间列并按「启用优先→改时倒序→建时倒序」排序;新增元素主表 `element`;编辑抽屉标签/字段/隐藏项按锁定决策改造;下游选配/搜索不回归。

---

## 1. 测试环境与前置

> ⚠️ **联调 B 方案(隔离,主干零污染)**:本任务在 worktree 起**独立后端 8082 + 独立前端 5175 + 隔离库 `cpq_db_mattest`**,**严禁 worktree 后端连共享 `cpq_db`**——会落 V317/V318(element 建表/删 demo)致 master-8081 重启 Flyway 校验失败。测试前须先拉起这套隔离环境(当前 8082/5175/`cpq_db_mattest` 均未就绪,需 dev/我在测前启动)。

| 项 | 值 |
|----|----|
| 后端 | `http://localhost:8082`(worktree 隔离实例;探活 `GET /api/cpq/material-recipes` 期望 200/401,**非 500**) |
| 前端 | `http://localhost:5175`(隔离,`/api` 代理指 8082;主数据维护→材质页签) |
| DB | **`10.177.152.12:5432/cpq_db_mattest`**(隔离库,与主干 `cpq_db` 分离;postgres / joii5231) |
| 鉴权 | 读:SALES_REP/SALES_MANAGER/PRICING_MANAGER/SYSTEM_ADMIN;**写(import/create/update/delete):SYSTEM_ADMIN**(`admin/Admin@2026`) |
| 测试文件 | `dev-docs/task-0708-材质库规范澄清/材质库.xlsx` |
| SQL 前缀 | `PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_mattest -A -F'|' -c "..."` |

**执行前置**:
1. 后端已合并 B1~B6 代码 + `touch` java 触发热重载(会话可能失效需重登)。
2. 迁移就位:`SELECT version,success FROM flyway_schema_history WHERE version >= '<B1版本号>';` 全 `success=t`(勿手工 psql)。
3. 前端已合并 F1~F5 + Vite 200。
4. **先读 §3 R1(已裁决)**——`materialsUpserted` 判定基线钉死 **253 落库 + 1 跳(WZHF26-25)**。

---

## 2. ⚠️ 测试数据事实基线(实测 `材质库.xlsx`,决定断言预期值)

| # | 事实(实测) | 对断言影响 |
|---|------|-----------|
| **D1** | `材质编号` sheet:**254 材质 / 254 编号**,一一对应无冲突。`Ag→00001`、`AgC3→00002`、`AgSnO2→00075` | materialsUpserted 上限=254;code=材质编号(00001 形式) |
| **D2** | `材质对应元素` sheet:654 数据行,254 材质,**37 种元素名称**(28 化学元素/合金 + 9 数字牌号,裁决后均合法) | B1 字典 seed 覆盖约 28 种;数字牌号由导入 upsert 补入 element |
| **D3** | ×100 锚点:`Ag(00001)`=Ag 1.0→`default_pct=100`;`AgC3(00002)`=Ag 0.97 + C 0.03→`97/3`;`AgSnO2(00075)`=Ag 0.9045 + Sn 0.0955→`90.45/9.55` | AC「含量×100」抽查锚点;同材质 Σdefault_pct=100 |
| **D4** | 元素→元素编号:`Ag=10001`、`Cu=10002`、`C=10012`、`Sn=10004`、`Ni=10005` | element_code 存**符号**(Ag),不是编号(10001);元素编号入 `element.element_no` 留存 |
| **D5** ✅裁决 | **9 个数字牌号 `{191,206,223,258,301,304,316,430,721}` 是合法组成项(不锈钢/合金牌号),导入不跳**(2026-07-09 裁决推翻原决策 #6);它们在 `Cu/304`、`Cu/301/Cu`、`304/Cu/304` 等复合材质里承载主含量 | 保留为合法元素 → 进 `material_recipe_element` + `element` 主表;开发须**移除**纯数字元素特判(§5.2) |
| **D6** 🔴 | 34 **重复行**((材质编号,元素)重复,如 `AgC3(00002)` 成对出现);去重后 654→620 行 | 导入**必须按(材质编号,元素)去重**,否则 `material_recipe_element` 灌成 Ag×2+C×2、Σdefault_pct=200 违反下游「和=100」;见 R2 |
| **D7** ✅裁决基线 | **按裁决规则(不剔数字牌号 + 去重(材质编号,元素名称)首次胜 + 材质级 Σ≈1 容差 0.02)实测推演**:**materialsUpserted=253、skipped=1**。唯一跳过=`WZHF26-25`(code=00242,元素 `Fe 0.406 + 206 0.296 + Ni36 0.298 + DC04 0.4412`,**Σ=1.4412** 超容差)。253+1=254 | 验收基线=**253 落库 + 1 跳**(已实测坐实) |
| **D8** | B2 待删 12 条 demo seed 材质(code=符号):`AgCu85/AgCu90/AgNi90/AgNi95/AgSnO2/AgSnO2b/AgCdO/AgW60/AgW72/CuCr/AgPd/AuAg` | 注意:demo `AgSnO2`(code=符号) ≠ 真实导入 `AgSnO2`(code=00075、symbol=AgSnO2),B2 按 code 删只删 demo,不误删真实 |

---

## 3. 已识别测试风险(报告须专门结论)

- **✅ R1(已裁决 · 2026-07-09 · 原决策 #6 作废)**:数字牌号(`304/316/301/430/191/206/223/258/721`)**是合法组成项,导入不跳**;开发同步**移除纯数字元素特判**(§5.2 行级校验删掉「元素名称为纯数字→跳过」分支)。**验收基线钉死:materialsUpserted=253、skipped=1(WZHF26-25 Σ=1.4412)**,非 189 非 254(已实测坐实)。
  - **PASS 条件**:实测 `materialsUpserted=253` 且 `skipped[]` 恰含 `WZHF26-25`(reason=`含量合计≠1(实际1.44)`)。
  - **FAIL 条件**:① `materialsUpserted<253`(数字牌号仍被特判跳过 → 特判没删干净);② `WZHF26-25` 未被跳过(材质级 Σ 校验失效 / 容差被放大到吞掉 1.44)。
- **🔴 R2(重复行去重)**:D6 有 34 重复行。若导入不按(材质编号,元素)去重 → 元素明细重灌翻倍、`Σdefault_pct=200` 触发下游「和=100」约束报错或错价。§TC-I5 专项:抽查 `AgC3(00002)` 元素明细**恰 2 行**、Σ=100,不得 4 行/200。
- **R3(element_code 必须存符号非编号)**:决策 #5 + 雷区。`element.element_code` 必须=`Ag/Cu/Sn`(接 `costing_element_price.element_code`),不得=`10001`。否则定价 join 断链。
- **R4(name/spec_label DB 列保留)**:决策 #2。导入置 NULL、UI 隐藏,但 **DB 列不得删**(下游 `ConfigureSearchResource`/`ConfigureProductService` `COALESCE(mr.name…)` 引用)。删列 → 下游 500。§TC-R 回归专项。
- **R5(增量 Upsert 非清空)**:决策 #8。文件外的手工材质**不得**被删。§TC-I4 造手工材质 ZZ999 → 导入后仍在。

---

## 4. 测试用例

> 判定列 PASS/FAIL/BLOCKED 测试时填。B* 关联后端任务,F* 关联前端任务。

---

### TC-M 迁移与元素主表(B1 / B2)

| 用例 | 断言(SQL) | 预期 | 判定 |
|------|----------|------|------|
| **TC-M1** 迁移成功 | `SELECT version,success FROM flyway_schema_history WHERE version IN ('<B1>','<B2>');` | 均 `success=t`;后端启动无 checksum mismatch | |
| **TC-M2** element 表建成 + seed | `SELECT count(*) FROM element;` 且 `SELECT element_name FROM element WHERE element_code='Ag';` | count ≥ 28;`Ag→银`(中文字典 seed 生效) | |
| **TC-M3** element_code 存符号/牌号非元素编号 | `SELECT count(*) FROM element WHERE element_code ~ '^100[0-9][0-9]$';` 且 `SELECT element_code FROM element WHERE element_code='Ag';` | 元素编号(`10001` 类)**0 行**(element_code 存 `元素名称` 值,非 `元素编号`,R3);`Ag` 在;⚠️ 数字牌号 `304/206` 等**允许存在**(裁决后为合法元素,不再判 0) | |
| **TC-M4** 删 12 条 demo 材质 | `SELECT count(*) FROM material_recipe WHERE code IN ('AgCu85','AgCu90','AgNi90','AgNi95','AgSnO2','AgSnO2b','AgCdO','AgW60','AgW72','CuCr','AgPd','AuAg');` | **=0**(demo 已清,D8) | |
| **TC-M5** 后端存活非 500 | `curl -s -o /dev/null -w '%{http_code}\n' --noproxy '*' http://localhost:8082/api/cpq/material-recipes` | 200/401(非 500) | |

---

### TC-L 列表查询改造(B3 / api §二)

| 用例 | 断言 | 预期 | 判定 |
|------|------|------|------|
| **TC-L1** DTO 补时间字段 | `GET /material-recipes` 响应 | 每项含非空 `createdAt`/`updatedAt`(ISO8601) | |
| **TC-L2** 返回全状态 | 停用某材质后 `GET /material-recipes` | 停用项(status=INACTIVE)**仍在**列表(非过滤消失) | |
| **TC-L3** 排序 | 同上 | 顺序=`启用优先 → updated_at 倒序 → created_at 倒序`;停用项排在启用项之后 | |
| **TC-L4** 搜索·材质编号 | `GET /material-recipes?keyword=00002` | 命中 code=00002(AgC3) | |
| **TC-L5** 搜索·材质名称(symbol) | `GET /material-recipes?keyword=AgC3` | 命中 symbol=AgC3 | |
| **TC-L6** 搜索·元素符号 | `GET /material-recipes?keyword=Sn` | 返回**任一含 Sn 元素**的材质(子查询命中 material_recipe_element,非仅 symbol) | |
| **TC-L7** 搜索·元素中文名 | `GET /material-recipes?keyword=银` | 返回含 Ag(中文「银」)的材质(中文元素名可搜) | |
| **TC-L8** 单条 SQL 无 N+1 | 观察后端日志 | 列表+搜索单查询完成,无逐材质查元素的 N+1 | |

---

### TC-I 材质库导入(B4 / B5 / api §三 · §四)— 核心

**执行**:清空重导前先造 §TC-I4 的手工材质 → `POST /api/cpq/material-recipes/import`(multipart `file=材质库.xlsx`,SYSTEM_ADMIN)→ 得 `MaterialImportReportDTO`。

| 用例 | 断言 | 预期 | 判定 |
|------|------|------|------|
| **TC-I1** 只读两 sheet | 导入后无「材质对应料号」「材质统一版」等脏 sheet 的材质混入;`SELECT count(*) FROM material_recipe WHERE status='ACTIVE';` | 材质来源仅 `材质编号`+`材质对应元素`;不出现草稿表脏材质 | |
| **TC-I2** 材质落库数(★R1 已裁决) | 报告 `materialsUpserted` + `SELECT count(DISTINCT code) FROM material_recipe WHERE code ~ '^[0-9]{5}$';` | **=253**(裁决基线);`skipped[]` 恰含 `WZHF26-25`(1 条,Σ=1.44)。`materialsUpserted<253`=数字牌号特判没删干净 FAIL;`WZHF26-25` 未跳=Σ 校验失效 FAIL | |
| **TC-I3** ×100 归一 | `SELECT element_code,default_pct FROM material_recipe_element e JOIN material_recipe r ON e.recipe_id=r.id WHERE r.code='00002' ORDER BY element_code;` | AgC3:`Ag=97.00`、`C=3.00`(0.97/0.03 ×100);**不是 0.97**(R 雷区) | |
| **TC-I4** 增量 Upsert·文件外不动(R5) | 导入**前**手工建材质 `ZZ999`(`POST /material-recipes`)→ 导入后 `SELECT count(*) FROM material_recipe WHERE code='ZZ999';` | **=1**(文件外材质保留,非 truncate 清空,决策 #8) | |
| **TC-I5** 去重·Σpct=100(★R2) | `SELECT count(*) n, sum(default_pct) s FROM material_recipe_element e JOIN material_recipe r ON e.recipe_id=r.id WHERE r.code='00002';` | AgC3 **n=2**(Ag+C)、**s=100.00**;**不得 n=4 / s=200**(重复行已去重) | |
| **TC-I6** 脏材质跳过 + 报告(裁决后) | 报告 `skipped[]` | **恰 1 条**:`WZHF26-25`(reason=`含量合计≠1(实际1.44)`);**不得**再出现 reason=`元素名称为纯数字`(特判已删,数字牌号不跳);脏材质不影响整单(HTTP 200,非 400) | |
| **TC-I7** 元素主表同步(R3) | `SELECT count(*) FROM element;` 导入前后 + `SELECT element_code,element_name,element_no FROM element WHERE element_code IN ('Ag','304');` | 出现的元素/牌号均 upsert(**含数字牌号 `304/206`**);`Ag→银/10001`;数字牌号以 `element_name=符号` 入库(`304→304`);已有中文名不被符号覆盖;导入后 `count(element)≥37`(28 字典 + 9 牌号) | |
| **TC-I8** 元素编号留存 | `SELECT element_no FROM element WHERE element_code='C';` | `10012`(Excel 元素编号入 element_no,当前无消费方仅留存) | |
| **TC-I9** 二次导入幂等(Upsert 覆盖不累加) | 同文件**再导一次** → 重跑 TC-I5 | AgC3 仍 n=2/s=100(元素明细全量重灌覆盖,不累加成 4/6);材质数不翻倍;文件外 ZZ999 仍在 | |
| **TC-I10** 性能 <3s | 报告 `durationMs`(真实 654 行);另 B6 用 1000 行 workbook 单测 | 真文件 `durationMs<3000`;1000 行单测 assert <3000;代码无 per-row DB 调用(批量) | |
| **TC-I11** 模板下载干净 | `GET /material-recipes/import/template` → 存 xlsx 打开 | **恰两 sheet**(`材质编号`表头`材质|材质编号`;`材质对应元素`表头`材质|材质编号|元素名称|含量|元素编号`);**不是** 12-sheet 脏原文件 | |
| **TC-I12** 导入鉴权 | 非 SYSTEM_ADMIN 调 `POST /import` | 403(仅 SYSTEM_ADMIN 可导) | |
| **TC-I13** 缺 sheet 报 400 | 上传缺 `材质对应元素` 的 xlsx | 400 `模板缺少必需 sheet: 材质对应元素`(区别于脏数据走 200+skipped) | |

---

### TC-E 材质 CRUD 与编辑抽屉规则(F4 / api §五)

| 用例 | 断言 | 预期 | 判定 |
|------|------|------|------|
| **TC-E1** 新建材质 | `POST /material-recipes`(code=00300,symbol=AgNi10,recipeType=locked,元素 Ag90+Ni10) | 201/200;落库 code=00300、recipeType=locked | |
| **TC-E2** 新建默认 locked | 前端新建抽屉初始值 | `recipeType` 默认 `locked`(标准锁定);初始元素 `isLocked=true` 无 min/max | |
| **TC-E3** 编辑材质编号只读 | 编辑抽屉 code 字段 + `PUT /material-recipes/{id}` 改 code | 前端 code `disabled`;后端 update **不改 code** | |
| **TC-E4** Σpct=100 校验 | 提交元素 Σ≠100 的材质 | 被拒(沿用现有「默认含量之和=100」校验) | |
| **TC-E5** 停用软删 | `DELETE /material-recipes/{id}` | 204;`status→INACTIVE`;仍在列表(TC-L2/L3) | |
| **TC-E6** name/specLabel 传 null 不报错 | 新建/编辑不传 name/specLabel | 落库 name/specLabel=NULL,无 500(DB 列仍在,R4) | |

---

### TC-U 前端页面(F2 / F3 / F4)

| 用例 | 断言(UI/curl) | 预期 | 判定 |
|------|----------|------|------|
| **TC-U1** 列表列改造 | 主数据维护→材质 列表 | 「代号」→**材质编号**、「化学式」→**材质名称**;**新增**创建时间、修改时间两列;**移除**「绑定料号数」列 | |
| **TC-U2** 搜索框 | 工具栏搜索框输入「银」/「AgC3」/「00002」 | 防抖后按 keyword 拉取,结果过滤生效 | |
| **TC-U3** 导入按钮 + 抽屉 | 点「导入材质库」 | 右侧 Drawer 开;含模板下载 + 上传区 + 导入后结果报告(成功数/跳过明细表) | |
| **TC-U4** 导入报告脏数据不弹错 | 上传真文件 | 成功走 200 展示报告(跳过明细 Table 列 sheet/行号/原因/原值),**不弹 error 弹窗**(脏数据非错误) | |
| **TC-U5** 编辑抽屉标签/隐藏 | 打开编辑抽屉 | 标签「材质编号」「材质名称」;**无** name/配比字段;**无**关联料号 tab;编辑时材质编号只读 | |
| **TC-U6** 统一 Drawer 非 Modal | 导入/编辑交互 | 均为右侧 Drawer(符合 UI 规范),无 Modal 表单 | |
| **TC-U7** 前端自检 | `tsc --noEmit` + 改动 tsx Vite 200 | 0 错误;`MaterialImportDrawer.tsx`/`MaterialRecipeManagement.tsx`/`MaterialRecipeEditDrawer.tsx`/`materialRecipeService.ts` 各 HTTP 200 | |

---

### TC-R 下游回归(雷区 R4 / 决策 #4 #7)

| 用例 | 断言 | 预期 | 判定 |
|------|------|------|------|
| **TC-R1** name/spec_label 列未删 | `SELECT column_name FROM information_schema.columns WHERE table_name='material_recipe' AND column_name IN ('name','spec_label');` | 两列都在(R4) | |
| **TC-R2** 选配材质 Tab / 搜索不报错 | 调 `ConfigureSearchResource` 相关端点 / 选配材质选择 | 正常返回,`COALESCE(mr.name…)` 不因 name=NULL 报 500 | |
| **TC-R3** 料号绑定接口保留 | `GET /material-recipes/{id}/parts` 等 | 端点仍存在(前端不调,后端不删,决策 #4) | |
| **TC-R4** 选配后端零改动 | 三类型枚举 + 选配逻辑 | recipeType 三枚举保留;编辑抽屉仍可切类型/填 min/max(决策 #7) | |

---

## 5. 达标判定标准

**达到交付水平(PASS)** 需同时满足:
1. **TC-M**:element 主表建成 + seed≥28 + Ag→银 + element_code 为符号;12 demo 材质已删。
2. **TC-I(核心)**:只读两 sheet;×100 正确(AgC3=97/3);**去重生效(AgC3 n=2/Σ=100,R2 一票否决)**;增量 Upsert 文件外材质不动(R5 一票否决);脏行跳过+报告(200 非 400);元素主表同步且 element_code=符号;性能<3s;模板下载干净两 sheet。
3. **TC-L**:全状态 + 三类搜索(编号/名称/元素含中文)+ 时间字段 + 排序。
4. **TC-E/TC-U**:编辑抽屉标签/只读编号/默认 locked/隐藏字段与料号 tab;列表列改造;导入抽屉报告。
5. **TC-R**:无下游回归(name 列保留、选配/搜索不报错)。

**R1 已裁决(基线锁定,硬性 PASS 判据)**:
- ✅ materialsUpserted **必须=253**、skipped **必须=1(WZHF26-25 Σ=1.44)**。数字牌号为合法元素、导入不跳;开发须移除纯数字元素特判。此项由「BLOCKED」转为**硬性达标判据**(不再是待澄清项)。

**不达标(FAIL)** 任一:
- 含量未 ×100(default_pct=0.97 而非 97)→ 流入定价 100 倍错价;
- 重复行未去重(AgC3 Σdefault_pct=200)→ 违反「和=100」;
- 导入 truncate 清空致文件外/手工材质丢失(违反增量 Upsert);
- element_code 存了数字编号(10001)→ 定价 join 断链;
- 删了 name/spec_label 列 → 下游 COALESCE 500;
- `materialsUpserted`≠253(<253=数字牌号特判没删/被误跳;>253=WZHF26-25 未按 Σ≠1 跳过);
- 脏数据报 400 中断整单(应 200+skipped);
- 1000 行导入 >3s 或代码 per-row 连库。

---

## 附录 A:实测数据速查(供报告引用)

- `材质库.xlsx`:12 sheet,只用 `材质编号`(254 材质/编号)+ `材质对应元素`(654 行,去重后 620)。
- ×100 锚点:`Ag(00001)=100`、`AgC3(00002)=Ag97+C3`、`AgSnO2(00075)=Ag90.45+Sn9.55`。
- 元素→编号:`Ag=10001/Cu=10002/C=10012/Sn=10004/Ni=10005`。
- 9 数字牌号(裁决=合法元素,**不跳**):`191/206/223/258/301/304/316/430/721`。
- 元素/牌号符号 37 种(28 化学元素/合金 `Ag Al Be C Cd Ce Cr Cu DC04 Fe H70 In Ir Mn Ni Ni36 Ni42 P Pd Pt Si Sn SnO2 W WC Zn ZnO 不锈钢` + 9 数字牌号);element_code 存符号,元素编号入 element_no。
- **裁决基线(实测坐实)**:materialsUpserted=**253**、skipped=**1**=`WZHF26-25`(code=00242,`Fe0.406+206_0.296+Ni36_0.298+DC04_0.4412`,Σ=1.4412 超容差)。
- 12 demo 待删 code:`AgCu85 AgCu90 AgNi90 AgNi95 AgSnO2 AgSnO2b AgCdO AgW60 AgW72 CuCr AgPd AuAg`。

## 附录 B:裁决基线复算脚本(测试时复现 253 / 1)

```python
# 裁决口径:不剔数字牌号 + 去重(材质编号,元素名称)首次胜 + 材质级 Σ≈1 容差 0.02
import openpyxl
from collections import defaultdict
F="dev-docs/task-0708-材质库规范澄清/材质库.xlsx"
wb=openpyxl.load_workbook(F,data_only=True,read_only=True)
def n(v): return "" if v is None else str(v).strip()
ws=wb["材质对应元素"]; rows=list(ws.iter_rows(values_only=True)); h=[n(c) for c in rows[0]]
jc,jm,je,jp=h.index("材质编号"),h.index("材质"),h.index("元素名称"),h.index("含量")
dedup={}; matOf={}
for r in rows[1:]:
    c,m,e,p=n(r[jc]),n(r[jm]),n(r[je]),n(r[jp])
    if not c: continue
    matOf[c]=m
    if (c,e) not in dedup: dedup[(c,e)]=p          # 首次胜
grp=defaultdict(list)
for (c,e),p in dedup.items(): grp[c].append((e,p))
up=0; skipped=[]
for c,els in grp.items():
    s=sum(float(p) for _,p in els if p!="")        # 不剔数字牌号
    if abs(s-1)>0.02: skipped.append((c,matOf[c],round(s,4)))
    else: up+=1
print("materialsUpserted预期=",up,"skipped=",len(skipped),skipped)
# 期望: materialsUpserted=253  skipped=[('00242','WZHF26-25',1.4412)]
```

## 附录 C:测试执行记录(测试时填)

| 用例组 | 判定 | 实测值/证据 | 备注 |
|--------|------|------------|------|
| TC-M1~M5 | | | |
| TC-L1~L8 | | | |
| TC-I1~I13 | | | I2 关联 R1;I5 关联 R2 |
| TC-E1~E6 | | | |
| TC-U1~U7 | | | |
| TC-R1~R4 | | | |
