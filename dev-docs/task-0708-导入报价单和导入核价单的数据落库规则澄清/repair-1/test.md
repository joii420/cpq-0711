# 测试用例文档 · repair-1:生产料号(production_no)落库补全 + 组成料号 calc_type 语义

> 测试员:QA｜优先级:P0｜依据:`repair-1/backtask.md §4 验收标准(AC-1~AC-10)` + §2 决策 A/B
> 被测范围:核价(PRICING)导入 production_no 落到主表/映射表/material_master 主档;组成料号按 calc_type 区分、元素行不污染主档。**不破 task-0708 已交付部分(AC-9 回归)**。
> 测试文件:`docs/table/核价测试数据/核价系统功能基础数据功能结构所需字段（导入版本)-新版.xlsx`(生产料号有值)

---

## 0. 一句话验收目标

生产料号(`production_no`)落到**它该落的每张表主行 + 部件主档(material_master)**(当前只落明细/子表);组成料号靠 `calc_type` 区分语义,**元素行的材质编号不得登记进 material_master**;task-0708 撞键/is_current/material_no=销售料号 全部不破。

---

## 1. 测试环境与前置

| 项 | 值 |
|----|----|
| 后端 | `http://localhost:8081`（探针 401 存活;改动后 touch java 触发热重载,**会话失效需重登**) |
| 登录 | `POST /api/cpq/auth/login` `{admin/Admin@2026}`(SYSTEM_ADMIN);核价导入需 SALES_MANAGER/SYSTEM_ADMIN |
| DB | `10.177.152.12/cpq_db`（postgres/joii5231） |
| 测试文件 | 核价 `（导入版本)-新版.xlsx`（25 Sheet,生产料号有值） |
| 导入端点 | `POST /api/cpq/basic-data-import/v6/pricing`（同步,multipart `file`） |
| 前端 | 零改动（见 fronttask.md） |

**执行前置(全部就绪方可复验)**：
1. **repair-1 代码已合并** + 后端已热重载(touch 一个 pricing handler java,等 6-8s,重登会话)。
2. **需求方修正版测试文件已到位**（RR-1 前置,见 §3）——补齐 P05 生产料号 + 统一销售料号为 `S-` 格式,使映射/主档与成本表料号可 join。**未拿到修正版文件前不启动复验**(当前 `-新版.xlsx` 会卡在 AC-2 且主链 join 断裂)。
3. 迁移就位:`SELECT version,success FROM flyway_schema_history WHERE version='316'` → success=t(见 AC-10)。
4. **清空 PRICING**(7 张有 system_type 的表 + material_master 里本文件料号)后重导,保证计数干净(清场 SQL 见附录 A)。

---

## 2. ⚠️ 测试数据事实基线(判定口径,已实测新文件,务必先读)

| # | 事实 | 对断言影响 |
|---|------|-----------|
| D1 | **销售料号带 `S-` 前缀、生产料号不带**:如 物料BOM 销售=`S-3120018220`、生产=`3120018220`(不同值) | AC-4 核心:production_no≠material_no 用此双轨证。material_no=`S-3120018220`、production_no=`3120018220` |
| D2 | 各成本/BOM sheet **生产料号有值**:物料BOM 14/14、产能 4/4、设备折旧 4/4、生产/辅助能耗 4/4、模具 2/2、耗材 3/3、包装 1/1、来料加工费 2/2、加工费&组装 4/4、成品比例/固定 2/2、电镀成本 1/1、单重 8/8 | AC-3/AC-5 主行 production_no 应有值,可正向证伪 |
| D3 | **物料BOM 计算类型:材料 8 行 / 元素 6 行**;材料行组成料号=`S-xxxx`(销售料号)、元素行组成料号=材质编号(`2101110225`/`2111410069`/`3112230066`/`3112230067`/`3110520789`) | AC-6 两类都在;AC-7 元素材质编号不进 material_master |
| D4 | **纯材质编号**(只作 材质料号/元素组成料号出现、从不作销售料号):`2101110225`、`2111410069`、`3112230066`、`3112230067` | AC-7 探针用这些(查 material_master 应 0);⚠️ `3110520789` 既作材质编号(裸)又作销售料号(带 S-=`S-3110520789`),**不用它做 AC-7 探针**(用裸 2101110225 更干净) |
| D5 | **撞键点**:销售料号 `S-3110520789` → 2 个材质料号 `2101110225`+`2111410069` | AC-9 回归 |
| D6 | material_master 归属来源:单重(销售`S-3120018220`+生产`3120018220`)、物料BOM 材料行组成料号、P05 | AC-1 material_master.production_no 主要由单重/物料BOM 提供 |

---

## 3. 测试风险(报告须专门结论)

- **🔴 RR-1(测试数据缺陷 · 技术总监已实测确认 · 已定性)**:新文件 `宏丰-客户料号对应关系`(P05 源)**生产料号列全空(0/14)**,且其销售料号是**裸 `3120018220`(无 S-)**,与成本表 `S-3120018220` **零交集**。影响比"AC-2 落 NULL"更严重——**主档/映射表 与 成本表整条链 join 断裂**。
  - **定性(技术总监拍板)**:此为**测试数据缺陷**,数据口径归**需求方**,**不归开发**(开发按 spec 实现,代码无责)。已作为**复验前置条件**写入 `repair-1/backtask.md §4`。
  - **处理**:**需求方先修正文件**(补 P05 生产料号 + 统一销售料号为 `S-` 格式),QA 等修正版文件到位再复验。**不得用当前 `-新版.xlsx` 卡 AC-2**。用修正版文件复验后:AC-2 若仍 NULL = 代码"读了没写"FAIL;正确写入 = PASS。
- **RR-2(裸/前缀料号并存)**:因 RR-1,同一逻辑料号在 P05(裸)与其它表(S-)以两种字符串存在,可能导致 material_master 双行、跨表 join 断链。审计 AC-1/AC-5 时注意 material_no 一致性。
- **RR-3(handler "读了没写")**:backtask 核心 bug 模式=读了生产料号但没写主行。AC-3/AC-5 即探针;凡源有生产料号(D2)而主行 production_no 为 NULL 即命中该 bug。

---

## 4. 测试用例(AC-1 ~ AC-10)

> SQL 前缀:`PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db -A -F'|' -c "..."`
> 执行:清空 PRICING → 上传新文件 → 同步响应确认 SUCCESS/0 失败 → 逐条断言。

| 编号 | 断言 SQL / 步骤 | 预期 | 判定 |
|------|----------------|------|------|
| **前置-导入** | 上传新文件,响应 `status`/`totalFailedRows` | status=SUCCESS,failedRows=0(料号列改名/S-前缀未导致失败) | |
| **AC-1** material_master 有 production_no 列且有值 | `SELECT material_no,production_no FROM material_master WHERE material_no='S-3120018220';` 另 `SELECT count(*) FILTER(WHERE production_no IS NOT NULL) n, count(*) t FROM material_master WHERE material_no LIKE 'S-%';` | 列存在;`S-3120018220` 行 production_no=`3120018220`;S- 料号主档 production_no 非空率符合来源(单重 8 料号都应有) | |
| **AC-2** material_customer_map 主行写生产料号 | `SELECT material_no,production_no FROM material_customer_map WHERE system_type='PRICING' AND production_no IS NOT NULL;` | ≥1 行,production_no=生产料号、material_no=销售料号,二者不同值。**⚠️ 若为 0 行→先按 RR-1 归因(P05 源生产料号空),不直接判代码失败** | |
| **AC-3** material_bom 主表写生产料号 | `SELECT count(*) FILTER(WHERE production_no IS NOT NULL) n, count(*) t FROM material_bom WHERE system_type='PRICING';` 且 `SELECT material_no,production_no FROM material_bom WHERE system_type='PRICING' AND material_no='S-3120018220';` | n>0(不再全 NULL);`S-3120018220` 主行 production_no=`3120018220` | |
| **AC-4** production_no 值正确(≠销售料号) | `SELECT DISTINCT material_no,production_no FROM material_bom WHERE system_type='PRICING' AND production_no IS NOT NULL ORDER BY material_no LIMIT 5;` | material_no=`S-xxxx`、production_no=裸`xxxx`,逐值对得上新文件(如 S-3120018220↔3120018220);**production_no 不等于 material_no** | |
| **AC-5** 各成本表主行覆盖(无"读了没写") | 逐表:`SELECT 'capacity' t, count(*) FILTER(WHERE production_no IS NOT NULL) n, count(*) tot FROM capacity WHERE system_type='PRICING' UNION ALL SELECT 'tooling_cost',count(*) FILTER(WHERE production_no IS NOT NULL),count(*) FROM tooling_cost WHERE system_type='PRICING' UNION ALL SELECT 'production_energy',...auxiliary_energy...labor_rate...unit_price...;`(labor_rate/tooling_cost/energy 无 system_type 则去掉过滤) | 凡源 sheet 有生产料号(D2)的表,主行 production_no 非空(n=tot 或 n=源有值行数);无 NULL 残留 | |
| **AC-6** 组成料号 calc_type 区分 | `SELECT calc_type,count(*) FROM material_bom_item WHERE system_type='PRICING' GROUP BY calc_type ORDER BY calc_type;` | 材料/元素两类都在(材料≈8、元素≈6);component_no 存组成料号原值(材料=S-xxxx、元素=材质编号) | |
| **AC-7** 元素行未污染 material_master(核心) | `SELECT material_no FROM material_master WHERE material_no IN ('2101110225','2111410069','3112230066','3112230067');` | **返回 0 行**——材质编号(calc_type=元素 的组成料号)未被当销售料号登记进主档(决策 B) | |
| **AC-8** element_bom 合理留空 | `SELECT count(*) FILTER(WHERE production_no IS NOT NULL) FROM element_bom WHERE system_type='PRICING';` | 0(物料与元素BOM 无生产料号列,留 NULL 合理) | |
| **AC-9a** 回归·撞键不破 | `SELECT material_no,material_part_no FROM element_bom WHERE system_type='PRICING' AND material_no='S-3110520789' ORDER BY material_part_no;` | 2 行,material_part_no=`2101110225`+`2111410069`(未撞键覆盖) | |
| **AC-9b** 回归·material_no=销售料号 | `SELECT DISTINCT material_no FROM material_bom WHERE system_type='PRICING' AND material_no LIKE 'S-%' LIMIT 3;` | material_no 为 S- 销售料号(非生产料号) | |
| **AC-9c** 回归·is_current 唯一 | `SELECT count(*) FROM (SELECT material_no FROM material_bom WHERE system_type='PRICING' GROUP BY material_no HAVING count(*) FILTER(WHERE is_current)<>1) x;` 及 element_bom 同法 | 0(无多 current) | |
| **AC-9d** 回归·重导不累加 | 同文件再导一次,`SELECT count(*) FROM material_bom WHERE system_type='PRICING';` 前后对比 | 不翻倍;is_current 仍唯一;element_bom S-3110520789 仍 2 行 | |
| **AC-10a** 迁移 V316 | `SELECT version,success FROM flyway_schema_history WHERE version='316';` + `SELECT column_name FROM information_schema.columns WHERE table_name='material_master' AND column_name='production_no';` | success=t;material_master 有 production_no 列 | |
| **AC-10b** 后端存活 | `curl -s -o /dev/null -w '%{http_code}' --noproxy '*' http://localhost:8081/api/cpq/basic-data-import/v6/000...000` | 401/404(非 500) | |

---

## 5. 达标判定标准

**达到交付水平(PASS)** 需同时满足:
1. 导入 SUCCESS、failedRows=0。
2. **AC-1 / AC-3 / AC-4 / AC-5**:production_no 落到 material_master + material_bom 主表 + 各成本表主行,且值=生产料号 ≠ 销售料号,无"读了没写"残留。
3. **AC-7**:元素材质编号未污染 material_master(一票否决——决策 B 核心)。
4. **AC-6 / AC-8**:calc_type 两类都在;element_bom 合理留 NULL。
5. **AC-9(a/b/c/d)**:task-0708 撞键/material_no=销售料号/is_current/不累加 全部保持 PASS(一票否决——不得回归)。
6. AC-10:V316 成功、后端非 500。

**AC-2 判定(RR-1 已作复验前置解决)**:
- 用**需求方修正版文件**(已补 P05 生产料号 + 统一 S- 销售料号)复验:
  - mcm.production_no 正确写入(=生产料号,material_no=S- 销售料号)→ **PASS**;
  - 仍全 NULL → **FAIL**(命中"读了没写"bug,此时数据已修正,责任归代码)。
- 严禁再用旧 `-新版.xlsx` 执行 AC-2。

**不达标(FAIL)** 任一:
- 源有生产料号的主表/成本表主行仍全 NULL(AC-3/AC-5);
- material_master 出现材质编号行(AC-7 污染);
- 撞键覆盖 / is_current 多 current / material_no 落成生产料号(AC-9 回归破);
- V316 缺失或后端 500。

---

## 附录 A：清空 PRICING SQL(重导前)

```sql
-- 7 张有 system_type 的表
DELETE FROM element_bom_item WHERE system_type='PRICING';
DELETE FROM element_bom WHERE system_type='PRICING';
DELETE FROM material_bom_item WHERE system_type='PRICING';
DELETE FROM material_bom WHERE system_type='PRICING';
DELETE FROM material_customer_map WHERE system_type='PRICING';
DELETE FROM unit_price WHERE system_type='PRICING';
DELETE FROM capacity WHERE system_type='PRICING';
-- material_master 无 system_type:按本文件料号清(S- 销售料号 + 裸生产/材质编号),或整表评估
-- DELETE FROM material_master WHERE material_no LIKE 'S-%' OR material_no IN ('3120018220', ...);
```
> ⚠️ material_master 跨 QUOTE/PRICING 共享无 system_type,清理需谨慎;建议只清本文件涉及料号,或用背靠背前后计数差值判断而非清空。

## 附录 B：新文件事实速查

- 销售料号(material_no)=`S-3120018220` / 生产料号(production_no)=`3120018220`(不同值)。
- 撞键点 `S-3110520789` → 材质料号 {`2101110225`,`2111410069`}。
- 纯材质编号(AC-7 探针,查 material_master 应 0):`2101110225`/`2111410069`/`3112230066`/`3112230067`。
- 物料BOM calc_type:材料 8 / 元素 6。
- ⚠️ P05(宏丰-客户料号对应关系)生产料号全空 + 裸销售料号 → RR-1。

## 附录 C：执行记录(测试时填)

| 用例 | 判定 | 实测值/证据 | 备注 |
|------|------|------------|------|
| 前置-导入 | | | |
| AC-1 | | | |
| AC-2 | | | RR-1 归因 |
| AC-3 | | | |
| AC-4 | | | |
| AC-5 | | | |
| AC-6 | | | |
| AC-7 | | | 一票否决 |
| AC-8 | | | |
| AC-9a~d | | | 一票否决(回归) |
| AC-10a/b | | | |
