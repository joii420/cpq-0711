# 测试用例文档 · task-0709 元素主表管理（BL-0040）

> 测试负责人:开发需求测试员(QA)｜优先级:P2｜编写日期:2026-07-09
> 依据:`backtask.md`(6 锁定决策 + B1~B6)+ `api.md`(接口契约)+ `fronttask.md`(F1~F5 + §七验收)。
> 承接 task-0708 材质库规范化(element 表由 V317 建 + 材质导入已 populate material_recipe_element)。
> 判定原则:**B 模型正确落地**——`element_no`=不可改业务主键、`element_code`=被引用即锁、`element_name`随时可改;`material_recipe_element` 加 `element_no` 权威链且回填;导入按 `element_no` upsert 不覆盖人工符号/中文;CRUD + 符号锁 409 + 软删;材质/选配/定价读 element_code 快照零回归。
> 🩸 铁律:计数/断言以**实测锚点**为准,不照抄示例;NULL/未回填不得因「缺数据」蒙混判 PASS。

---

## 0. 一句话验收目标

主数据维护新增「元素」页签:元素列表(编号/符号/中文/被引用材质数/状态/时间 + 搜索 + 排序)、新建(编号+符号唯一)、编辑(**元素编号只读**、**被引用元素符号锁定 + tooltip**、中文/状态随时改)、停用(软删二次确认);后端 element_no 升为业务主键、material_recipe_element 加 element_no 回填、导入按编号 upsert 不覆盖人工维护;材质导入 253/1 基线 + 材质页/选配/定价渲染零回归。

---

## 1. 测试环境与前置

| 项 | 值 |
|----|----|
| DB | **`cpq_db_elemtest`**(task-0709 隔离库;**严禁**对共享 `cpq_db` 跑 B1/B2 迁移污染主干) |
| 后端 | worktree 隔离实例(B 方案端口;测试前向技术经理确认——task-0708 曾用 8082,本任务预计另起一套;探活 `GET /api/cpq/elements` 期望 200/401 非 500) |
| 前端 | worktree 隔离前端(`/api` 代理指隔离后端;主数据维护→元素) |
| 鉴权 | 读=SALES_REP/SALES_MANAGER/PRICING_MANAGER/SYSTEM_ADMIN;**写(create/update/delete)=SYSTEM_ADMIN**(`admin/Admin@2026`) |
| 材质文件 | `docs/table/核价测试数据/...` 无关;本任务用 task-0708 的 `dev-docs/task-0708-材质库规范澄清/材质库.xlsx` 做 B5 导入回归(253/1) |
| SQL 前缀 | `PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_elemtest -A -F'|' -c "..."` |

**执行前置**:
1. 后端合并 B1~B6 + 迁移就位:`SELECT version,success FROM flyway_schema_history WHERE version >= '<B1版本号>';` 全 success=t。
2. 前端合并 F1~F5 + Vite 200。
3. **先跑 §TC-PRE** 确认隔离库锚点(元素/引用数)与本基线一致,再据以判定。

---

## 2. ⚠️ 测试数据事实基线(实测 cpq_db 克隆源,cpq_db_elemtest 应镜像;决定断言预期值)

| # | 事实 | 对断言影响 |
|---|------|-----------|
| **D1** | `element` 现 **39 个**,其中 **element_no 为 NULL 恰 2 个:`Au(金)`、`CdO(氧化镉)`**(V317 seed 未带编号),均 ACTIVE | B1 补号:按 `element_code` 排序 → **`Au→90001`、`CdO→90002`**(90000+ 保留段);补后无 NULL |
| **D2** 🔒 | **符号锁锚点**:`Ag`(element_no=**10001**)被 **142** 个材质引用;`Cu`(10002)132、`Ni`(10005)67、`Ni36`(10032)37、`Zn`(10003)29、`C`(10012)20、`Sn`(10004)18、`WC`(10024)17 | 对 Ag(10001)PUT 改符号 → **409「符号已被 142 个材质引用,不可修改」**;referencedCount(10001)=142、codeLocked=true |
| **D3** | **未引用元素**现仅 `Au`/`CdO`(补号后 referencedCount=0);其余 seed 元素(Ag/Cu/…)+9 数字牌号均被引用 | 「可改符号」锚点:补号后的 Au/CdO,或**新建一个元素**(referencedCount=0)测改符号 |
| **D4** | `material_recipe_element` **628 行**;**element_no 列尚未加**(B2 待落) | B2 加列 + 回填:按 element_code 反查 element.element_no;628 行应尽量全回填(未匹配记 NULL 供排查) |
| **D5** | element_code↔element_no 映射:`Ag=10001/Cu=10002/C=10012/Sn=10004/Ni=10005/Ni36=10032/WC=10024/304=10042/206=10034` | B2 回填 + 导入 material_recipe_element.element_no 的预期值对照 |
| **D6** | 元素中文名(快照)在材质编辑抽屉渲染(task-0708 已验:AgC3 元素 Ag→银/C→碳) | TC-R 回归:符号锁保证 element_code 快照恒一致,材质页元素明细渲染不得变 |

> ⚠️ 锚点取自 cpq_db(克隆源)。`cpq_db_elemtest` 若数据不同(如引用数≠142),以 §TC-PRE 实测值替换本组断言预期后再执行。

---

## 3. 已识别测试风险(报告须专门结论)

- **R1(符号锁边界)**:符号锁判据 = `referencedCount>0`(按 **element_no** 引用计)。B2 未回填时 referencedCount 恒 0 → 符号锁形同虚设。**必须先验 B2 回填生效**(§TC-M2),再验符号锁(§TC-C1),否则 409 测不出真伪。
- **R2(导入不覆盖人工维护)**:决策#5「编号已存在→不回写符号/中文」。真 `材质库.xlsx` 里 10001 恒=Ag/银,无法自然证伪「不覆盖」。§TC-E3 用**人工先改** element 10001 的 element_name/element_code 再导入,断言**不被还原**。
- **R3(协议链 AP-53 类)**:B5 改 import 落 material_recipe_element 属渲染协议链。改完必须真文件复跑,断言 **253/1 不变** + material_recipe_element.element_no 全回填 + 材质页元素明细渲染零回归(§TC-E/§TC-R)。
- **R4(不动边界回归)**:决策#6 明确不改选配/定价/element_bom/材质页/材质导入元素明细渲染(继续读 element_code 快照)。符号锁保证快照恒一致,但仍须回归验证材质页元素中文名照常显示。
- **R5(隔离纪律)**:B1/B2 迁移只能落 `cpq_db_elemtest`;若误落共享 `cpq_db` → 主干 8081 重启 Flyway 校验冲突(参 task-0708 B 方案教训)。

---

## 4. 测试用例

### TC-PRE 隔离库锚点校准(执行前必跑)

| 用例 | 断言 | 预期 | 判定 |
|------|------|------|------|
| **TC-PRE1** 元素/引用锚点一致 | `SELECT count(*) FROM element;` + `SELECT count(*) FROM material_recipe_element mre JOIN element e ON e.element_code=mre.element_code WHERE e.element_code='Ag';` | element≈39;Ag 引用≈142(若不同,以实测替换 §2 锚点) | |

### TC-M 迁移(B1 / B2)

| 用例 | 断言(SQL) | 预期 | 判定 |
|------|----------|------|------|
| **TC-M1** 迁移成功 | `SELECT version,success FROM flyway_schema_history WHERE version IN ('<B1>','<B2>');` | 均 success=t;后端启动无 checksum mismatch |  |
| **TC-M2** element_no 升业务主键 | `SELECT count(*) FILTER(WHERE element_no IS NULL) n_null FROM element;` + `SELECT indexdef FROM pg_indexes WHERE indexname='uq_element_no';` + `SELECT column_name,is_nullable FROM information_schema.columns WHERE table_name='element' AND column_name='element_no';` | n_null=**0**;`uq_element_no` UNIQUE 存在;element_no `is_nullable=NO` | |
| **TC-M3** Au/CdO 补号 | `SELECT element_code,element_no FROM element WHERE element_code IN ('Au','CdO') ORDER BY element_code;` | `Au→90001`、`CdO→90002`(90000+ 段,按 code 排序) | |
| **TC-M4** mre 加 element_no + 回填 | `SELECT count(*) total, count(*) FILTER(WHERE element_no IS NULL) n_null FROM material_recipe_element;` + `SELECT mre.element_code,mre.element_no FROM material_recipe_element mre WHERE mre.element_code='Ag' LIMIT 1;` | 列存在;回填后 `n_null` 尽量小(理想 0);Ag 行 element_no=`10001` | |
| **TC-M5** 后端存活非 500 | `curl -s -o /dev/null -w '%{http_code}\n' --noproxy '*' <隔离后端>/api/cpq/elements` | 200/401 | |

### TC-A 列表 GET /elements（B4 / api §二）

| 用例 | 断言 | 预期 | 判定 |
|------|------|------|------|
| **TC-A1** referencedCount 准确 | `GET /elements` → 找 elementNo=10001 | Ag:`referencedCount=142`、`codeLocked=true`、`elementName=银`、时间字段非空 | |
| **TC-A2** codeLocked 逻辑 | 同上,取一个 referencedCount=0 的(如补号后 Au/CdO 或新建) | referencedCount=0 → `codeLocked=false` | |
| **TC-A3** 全状态 + 排序 | 停用某元素后 `GET /elements` | 停用项仍在列表且排在 ACTIVE 之后;顺序=启用优先→updated_at 倒序→created_at 倒序 | |
| **TC-A4** 搜索·编号 | `GET /elements?keyword=10001` | 命中 Ag | |
| **TC-A5** 搜索·符号 | `GET /elements?keyword=Ag` | 命中 Ag | |
| **TC-A6** 搜索·中文名 | `GET /elements?keyword=银` | 命中 Ag(中文名可搜) | |
| **TC-A7** 无 N+1 | 观察后端日志 | list 单查询聚合 referencedCount,无逐元素查引用 | |

### TC-B 新建 POST /elements（api §三）

| 用例 | 断言 | 预期 | 判定 |
|------|------|------|------|
| **TC-B1** 新建成功 | `POST /elements {elementNo:90100,elementCode:Mo,elementName:钼}` | 200;status=ACTIVE、referencedCount=0、codeLocked=false | |
| **TC-B2** 编号撞号 409 | `POST {elementNo:10001,elementCode:XX,elementName:x}` | **409**「元素编号已存在: 10001」 | |
| **TC-B3** 符号撞号 409 | `POST {elementNo:90200,elementCode:Ag,elementName:x}` | **409**「符号已存在: Ag」 | |
| **TC-B4** 必填缺失 400 | `POST {elementNo:90300}`(缺 code/name) | 400 | |
| **TC-B5** 鉴权 | 非 SYSTEM_ADMIN POST | 401/403 | |

### TC-C 编辑 PUT /elements/{elementNo}（★符号锁核心,api §四）

| 用例 | 断言 | 预期 | 判定 |
|------|------|------|------|
| **TC-C1** ★被引用改符号 409 | `PUT /elements/10001 {elementNo:10001,elementCode:AgX,elementName:银}` | **409「符号已被 142 个材质引用,不可修改」**;DB 符号仍=Ag | |
| **TC-C2** 被引用改中文名 200 | `PUT /elements/10001 {...,elementCode:Ag,elementName:银QA}` | 200;element_name 改为「银QA」(中文随时可改) | |
| **TC-C3** 被引用改状态 200 | `PUT /elements/10001 {...,status:INACTIVE→ACTIVE}` 或走停用 | status 可改 | |
| **TC-C4** element_no 不可改 | `PUT /elements/90100 {elementNo:99999,elementCode:Mo,elementName:钼}` | element_no 仍=90100(路径为准,入参忽略/拒绝);DB 无 99999 | |
| **TC-C5** 未引用可改符号 | `PUT /elements/90100 {...,elementCode:Mo2,...}`(90100 未被引用) | 200;符号改为 Mo2 | |
| **TC-C6** 未引用改符号撞他人 409 | `PUT /elements/90100 {...,elementCode:Ag,...}` | **409「符号已存在: Ag」** | |
| **TC-C7** 404 | `PUT /elements/00000 {...}` | 404 | |

### TC-D 停用 DELETE /elements/{elementNo}（api §五）

| 用例 | 断言 | 预期 | 判定 |
|------|------|------|------|
| **TC-D1** 软删 | `DELETE /elements/90100` | 204;`SELECT status FROM element WHERE element_no='90100'`=INACTIVE(非物理删) | |
| **TC-D2** 幂等 | 再 `DELETE /elements/90100` | 仍 204;status 仍 INACTIVE | |
| **TC-D3** 被引用可停用不断链 | `DELETE /elements/10001`(Ag,被 142 引用)→ 查 material_recipe_element | 204;status=INACTIVE;**material_recipe_element 中 Ag 行不受影响**(历史材质靠 element_no join 照常);随后恢复 status=ACTIVE 便于后续用例 | |

### TC-E 导入联动回归（★B5 / AP-53,api §六决策#5）

用 task-0708 `材质库.xlsx` 重导(隔离后端 `POST /api/cpq/material-recipes/import`)。

| 用例 | 断言 | 预期 | 判定 |
|------|------|------|------|
| **TC-E1** 253/1 基线不变 | 导入报告 `materialsUpserted` / `skipped` | 253 / 1(WZHF26-25),与 task-0708 一致(不回归) | |
| **TC-E2** mre.element_no 全回填 | `SELECT count(*) FILTER(WHERE element_no IS NULL) FROM material_recipe_element WHERE ...本次导入;` | 导入写入的元素明细 element_no 无 NULL(权威链落库);抽查 AgC3(00002)的 Ag 行 element_no=10001 | |
| **TC-E3** ★按编号 upsert 不覆盖人工维护 | 先 `UPDATE element SET element_name='银-QA手改', element_code=... WHERE element_no='10001'`(仅改中文,符号被锁不改)→ 重导 → 查 element 10001 | element_name 仍=「银-QA手改」**未被还原成「银」**(编号已存在 DO NOTHING,尊重人工) | |
| **TC-E4** 新编号新建 | 若 Excel 含主表没有的 element_no → 导入后主表新增该元素 | 新编号入 element(element_name=符号或字典中文) | |

### TC-U 前端（F2/F3/F4;Playwright/人工走查,测试时执行）

| 用例 | 断言 | 预期 | 判定 |
|------|------|------|------|
| **TC-U1** 元素页签 | 主数据维护 Tabs | 「材质」旁有「元素」页签,挂 ElementManagement | |
| **TC-U2** 列表列 | 元素页列 | 元素编号/符号/中文名/被引用材质数(Tag)/状态/创建时间/修改时间;行内无动作按钮(工具栏 编辑/停用) | |
| **TC-U3** 搜索 | 搜索框输入 `10001`/`Ag`/`银` | 均过滤命中 | |
| **TC-U4** 新建 | 新建元素抽屉 | 编号+符号+中文输入;撞号 message.error 后端提示 | |
| **TC-U5** ★编辑符号锁 | 编辑 Ag(10001) | 元素编号只读;**符号输入禁用 + tooltip「已被 142 个材质引用,符号不可修改」**;中文名可改 | |
| **TC-U6** 编辑未引用可改符号 | 编辑新建的 90100 | 符号输入可编辑 | |
| **TC-U7** 停用二次确认 | 工具栏「停用」 | SelectableTable Modal 列出所选项二次确认;确认后软删 | |
| **TC-U8** 自检 | `tsc --noEmit` + 4 改动 tsx Vite 200 | 0 错误;均 200 | |

### TC-R 不动边界回归（决策#6 / R4）

| 用例 | 断言 | 预期 | 判定 |
|------|------|------|------|
| **TC-R1** 材质页元素明细渲染不变 | 材质编辑抽屉打开 AgC3(00002) | 元素仍显示 Ag→银 97.0 / C→碳 3.0(读 element_code/element_name 快照,符号锁保证一致) | |
| **TC-R2** 选配/定价 element_code 读取不变 | `GET /quotations/configure/search-parts?q=Ag` 等 | 200,不受 element_no 改造影响(继续按符号读) | |
| **TC-R3** material_recipe_element 快照列仍写 | 导入后 `SELECT element_code,element_name,element_no FROM material_recipe_element WHERE ... LIMIT 5;` | element_code/element_name 快照 + element_no 权威链**并存**(未删快照列) | |
| **TC-R4** costing_element_price 接得上 | `SELECT count(*) FROM element e JOIN costing_element_price p ON p.element_code=e.element_code;` | Ag/Cu/Ni 等符号仍能 join 定价(符号未变) | |

---

## 5. 达标判定标准

**达到交付水平(PASS)** 需同时满足:
1. **TC-M**:element_no 升 NOT NULL+UNIQUE 业务主键、Au/CdO 补号;material_recipe_element 加 element_no 且回填(理想 0 NULL)。
2. **TC-C ★符号锁(一票否决)**:被引用元素(Ag/142)改符号 → 409;未引用可改;element_no 不可改;中文/状态随时改。
3. **TC-B**:新建编号+符号唯一校验(撞→409)。
4. **TC-D**:软删 INACTIVE + 幂等;被引用可停用不断历史链。
5. **TC-E ★导入回归(一票否决)**:253/1 不变 + mre.element_no 全回填 + **按编号 upsert 不覆盖人工符号/中文**。
6. **TC-A/TC-U**:列表 referencedCount/codeLocked/搜索/排序/时间;前端元素页签 + 符号锁 tooltip + 停用二次确认。
7. **TC-R 不动边界(一票否决)**:材质页/选配/定价/材质导入元素明细渲染读 element_code 快照零回归。

**不达标(FAIL)** 任一:
- 被引用元素能改符号(符号锁失效)/ element_no 可被改(业务主键破);
- material_recipe_element.element_no 大面积 NULL(权威链未回填)/ 导入覆盖了人工维护的符号/中文;
- 导入 253/1 基线被破 / 材质页元素明细渲染回归(读快照断链)/ 选配定价 element_code join 断链;
- element 被物理删(应只软删)。

**BLOCKED**:B1/B2 误落共享 `cpq_db` 污染主干(R5);或隔离库锚点与基线严重不符且未校准(TC-PRE 未过)。

---

## 附录 A:实测锚点速查

- element 39;NULL element_no = **Au/CdO** → 补号 **90001/90002**。
- 符号锁锚点 **Ag=element_no 10001,被引用 142**;Cu(10002)132/Ni(10005)67/Ni36(10032)37/Zn(10003)29/C(10012)20/Sn(10004)18/WC(10024)17。
- material_recipe_element 628 行,element_no 待 B2 回填。
- 导入回归文件:`dev-docs/task-0708-材质库规范澄清/材质库.xlsx`(253/1)。

## 附录 B:测试执行记录（测试时填)

| 用例组 | 判定 | 实测值/证据 | 备注 |
|--------|------|------------|------|
| TC-PRE1 | | | 锚点校准 |
| TC-M1~M5 | | | |
| TC-A1~A7 | | | |
| TC-B1~B5 | | | |
| TC-C1~C7 | | | ★符号锁 |
| TC-D1~D3 | | | |
| TC-E1~E4 | | | ★导入回归 |
| TC-U1~U8 | | | Playwright/人工 |
| TC-R1~R4 | | | 不动边界 |
