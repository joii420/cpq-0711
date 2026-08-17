# 测试报告 · task-0708 导入报价单/核价单落库料号语义纠偏

> 测试员:QA｜测试日期:2026-07-08｜依据:`test.md`（42 项用例）
> 被测提交(初测):`4ce28a3`(feat) + `8d61cc0`(P24单重) + `1f47c9c`(record)
> **复验新增**:`257b8cd`(修 TC-B1 物料BOM 组件列回退材质料号) + `8767d87`(文档纠正合入 master)，均已在 master
> 环境:后端 8081(SYSTEM_ADMIN 会话)｜DB `10.177.152.12/cpq_db`｜官方验收文件 报价V3.xlsx / 核价6.0.xlsx
> 测试方式:清空 QUOTE+PRICING → 真实上传两文件 → DB 逐项断言 → 重导验升版 → 建报价单回归

---

## 一、总体结论

## ✅ 全项 PASS — 达到交付水平

料号语义纠偏的全部指标通过:schema 终态正确、报价/核价 material_no 正确落销售料号、**element_bom 撞键(一票否决)通过**、is_current 唯一且重导不累加、接口契约零变更、前端零改动;初测暴露的 2 项问题(TC-B1、TC-G)**开发方已修复并落 master,本次复验通过**。

**复验结果(2026-07-08 二轮)**:

| 项 | 初测 | 复验 | 修复提交 |
|----|:---:|:---:|------|
| TC-B1 报价导入 failedRows=0 | ⚠️ PARTIAL(1 失败) | ✅ **SUCCESS / 20 成 / 0 失败** | `257b8cd` 物料BOM 组件列 投入料号→材质料号回退 |
| TC-G 文档纠正落 master | 🔴 待合并 | ✅ **HEAD 已纠正**,sales_part_no 仅 2 处废弃注记 | `8767d87` 行级 patch 合入避开并发 WIP |

**剩余 2 项为数据未覆盖遗留(不影响达标判定,建议后续补样例二次验证)**:

| 级别 | 项 | 说明 |
|------|------|------|
| 🟡 遗留 R1 | 核价 `production_no` **取值映射** | 官方核价6.0「生产料号」列**全空**(实测 18 sheet 0 非空)→ 落库全 NULL,取值正确性未被官方数据证伪。开发方已用补充文件 `-增加销售料号` 自测 `capacity.production_no=PN-3120018220` 吻合;建议纳入官方验收集正式复验 |
| 🟡 遗留 R4 | 报价铸号正向路径 | 报价 V3「投入料号」列全空 → 缺失投入料号铸号逻辑未触发/未回归(反向 Q04 不再错误铸号已 TC-B5b 覆盖)。建议补含未匹配投入料号样例验证发号 |

**最终判定**:**全项 PASS,交付达标**。R1/R4 记为数据遗留项,择期补样例二次验证即可闭环。

---

## 二、逐组结果

### TC-A 迁移与 DB 结构 — ✅ 全部 PASS(7/7)

| 用例 | 结果 | 证据 |
|------|:---:|------|
| A1 迁移成功 | ✅ | flyway V308/V311/V315 success=t |
| A2 sales_part_no 全删 | ✅ | 11 表查询返 **0 行** |
| A3 production_no 覆盖 11 表 | ✅ | 11 表全含(含 material_customer_map) |
| A4 material_part_no 恰两表 | ✅ | 仅 element_bom + element_bom_item |
| A5 element_bom 唯一键含 material_part_no | ✅ | `uq_element_bom_v6 (…, material_no, COALESCE(material_part_no,''), characteristic)`，无 sales |
| A5b element_bom_item 唯一键 | ✅ | `uq_element_bom_item` 含 material_part_no、无 sales |
| A6 其余 8 表去 sales 后缀 | ✅ | 8 索引 has_sales 全 = f |
| A7 后端存活非 500 | ✅ | 导入探针 401 |

### TC-B 报价导入(罗克韦尔 CUST-1269) — ✅ 全部 PASS(复验后)

| 用例 | 结果 | 证据 |
|------|:---:|------|
| B0 客户核实 | ✅ | id=3027d83b… code=CUST-1269 未变 |
| **B1 导入无失败** | ✅ **PASS**(复验) | 初测 PARTIAL(19 成/1 失败);修复 `257b8cd` 后干净重导 = status=**SUCCESS**,total=20 **success=20 failed=0**;「物料BOM+组成件BOM(合并)」sheet 4/4/0(原失败行经材质料号名称回退成功,material_master 写入 3→4)。根因分析见 §三 |
| B2 material_no=销售料号 | ✅ | 3120012530 / CUST-1269 / customer_product_no=10772736 |
| B3 报价 production_no 恒 NULL | ✅ | material_bom/capacity/unit_price 非空计数均 0 |
| B4 物料BOM 落库 | ✅ | material_bom 有 3120012530 |
| B5 element_bom material_part_no=NULL | ✅ | material_no=3120012530，material_part_no 空(符合 V3 材质料号列空) |
| B5b Q04 不再铸号 | ✅ | 铸号格式 `XXXX-YYMMNNNNNN` 命中 **0 行** |
| B6 单重落库(P24 修复) | ✅ | 3120012530 落 material_customer_map + material_master(8d61cc0 去贪婪「料号」key 生效) |
| B7 无历史累加 | ✅ | QUOTE mcm 清后=3(清前 241)，无累加 |
| B8 铸号正向路径 | ➖ N/A | V3 投入料号列全空,未触发(R4) |

### TC-C 核价导入 — ✅ PASS(取值遗留 R1)

| 用例 | 结果 | 证据 |
|------|:---:|------|
| **C1 逐 Sheet 全成功** | ✅ | status=**SUCCESS**，success=**96**，**failed=0**，24 Sheet 全 0 失败 |
| C2 material_no=销售料号 | ✅ | capacity 落 3120018220(销售料号) |
| C3 核价版本表不整表失败 | ✅ | material_version_mgmt 有 2 行(P04 读销售料号正常) |
| C4 production_no | ✅(分支①) | capacity/material_bom/mcm 全 NULL(生产料号列全空)。**取值映射→R1 未覆盖** |
| C5 物料BOM 落库 | ✅ | material_bom 有 3120018220 |
| C6 汇总/价格表未误导 | ✅ | 「汇总」无 handler 不导入;材料/元素价格表照旧 |

### TC-D element_bom 撞键(核心·一票否决) — ✅ 全部 PASS(5/5)

| 用例 | 结果 | 证据 |
|------|:---:|------|
| **D1 多材质料号均存在** | ✅✅ | 销售料号 3110520789 精确 **2 行**:material_part_no=`2101110225` + `2111410069`，**未撞键覆盖** |
| D2 其余料号材质料号 | ✅ | 2120011658→3112230066；2120011659→3112230067 |
| D3 material_no=销售料号 且 production_no=NULL | ✅ | 3110520789 / production_no 空 |
| D4 element_bom_item 按 material_part_no 拆分 | ✅ | 2101110225→2 明细，2111410069→1 明细，均在 |
| D5 无唯一键冲突 | ✅ | 物料与元素BOM sheet failed=0 |

### TC-E 升版 / is_current — ✅ 全部 PASS

| 用例 | 结果 | 证据 |
|------|:---:|------|
| E1 每销售料号 n_current=1 | ✅ | 4 个销售料号 versions=1 n_current=1 |
| E1b 违规行=0 | ✅ | 0 |
| **E2 重导不累加** | ✅ | 二次导入 SUCCESS/96/0；各表**未翻倍**(capacity4/material_bom4/element_bom4/mcm14)；`unit_price` 版本 2000→**2001** 确有升版；is_current 唯一;3110520789 仍 2 行(未累加成 4) |
| E3 QUOTE 侧违规=0 | ✅ | 0(material_bom + element_bom 两侧均 0) |

### TC-F 接口契约 / 前端 — ✅ 全部 PASS

| 用例 | 结果 | 证据 |
|------|:---:|------|
| F1/F2 入参不变 | ✅ | quote=customerId+file；pricing=file，均成功 |
| F3 响应字段集不变 | ✅ | importRecordId/systemType/status/totalRows/successRows/failedRows/originalFileName/createdAt/metadata |
| F4 前端零改动 | ✅ | 本次 3 提交无 cpq-frontend 改动 |

### TC-G 文档纠正 — ✅ PASS(复验后)

- 初测:master 文档未纠正(报价 0 命中、核价仍是废弃 sales_part_no 设计),纠正在 feat 分支待并。
- 复验:`8767d87`「报价V3.4/核价V6.3落库方案纠正合入 master(行级 patch 避开并发 WIP)」已落 master。
- **HEAD 提交版核对(避开工作区并发 WIP)**:
  - `git show HEAD:核价系统Excel导入落库方案.md | grep -c sales_part_no` = **2**,均为废弃注记(第5行「废弃 V6.2 的 sales_part_no 反向设计」、第9行「V6.2 的 sales_part_no 维度整体废弃 + spec 作废」);
  - 核价 material_no 已改述「**承载销售料号(主料号)**」;物料与元素BOM 例外标注 material_part_no 进唯一键、production_no NULL;
  - 报价文档 HEAD 新语义命中:销售料号 2 / material_part_no 2 / production_no 1。
- **结论**:两份文档已按新语义纠正并落 master,无 sales_part_no 现行描述残留(仅废弃说明)。✅

### TC-H 回归 — ✅ 全部 PASS

| 用例 | 结果 | 证据 |
|------|:---:|------|
| H1 导入后建报价单 | ✅ | create-quotation 200，quotationId=729863ee…，hfPairsCount=1(autoPopulate 拿到料号) |
| H2 后端无 500 / 无 checksum | ✅ | 全程 401/200，flyway 无 mismatch |
| H3 自检声明齐全 | ✅ | RECORD.md:3932 含 flyway/failedRows/material_part_no/is_current 自检行 |

---

## 三、TC-B1 根因分析(初测暴露 → 已修复 `257b8cd` → 复验 PASS)

> **复验结论**:开发方按建议 2(handler 适配)修复——物料BOM 分支组件料号回退读「材质料号/材质料号名称」(exact 避 contains)。修复后干净重导 failedRows=0,`material_master` 写入 3→4(原失败行成功登记)。以下为初测根因存档。

**初测现象**:官方报价 V3 导入 = `PARTIAL`(20 总 / 19 成 / **1 失败**)。唯一失败行:
```
sheet=物料BOM+组成件BOM(合并)  rowNo=2  column=投入料号  message=料号与名称均为空
```

**根因**(已定位到 `MaterialBomMergeHandler.java:79-81`):
- 该 handler 在物料BOM 循环里,组件料号读 `row.exact("投入料号")`、名称读 `row.getStr("投入料号名称")`;
- 但官方 V3「物料BOM」sheet 的组件列是「**材质料号 / 材质料号名称**」,**无「投入料号」列**;
- 该唯一数据行「材质料号」值为空、「材质料号名称」='Material data tip' 有值 → handler 读投入料号取到空、投入料号名称也取不到 → resolve 抛异常 → 记失败。

**性质判定**:
- **不是**料号语义纠偏错误(material_no=销售料号 已正确落库,B2/B4 通过);
- 是「列名/数据不匹配」——正是 test.md 风险 **R3** 预警族;
- 开发方交付时用的是**自填材质料号(MP-3120012530)的改造文件**自测通过,官方 V3(材质料号空)未覆盖此路径。

**影响**:该失败行仅丢失「产出料号类型=1.银点类」这一描述属性;3120012530 的 material_bom 父行 + 3 条组成件明细已正常落库,料号主数据无缺失。

**建议(二选一,需求方定)**:
1. 修官方 V3 测试数据:为物料BOM 行补真实「材质料号」值;或
2. 改 handler:物料BOM 分支组件料号回退读「材质料号/材质料号名称」(与 backtask §3.3「物料BOM 材质料号」口径对齐)。

> 按 §8.3 / api.md §4.1 验收口径「failedRows=0」,当前官方文件为 failedRows=1,严格论**未满足该条**,故列为待澄清而非直接 PASS。

---

## 四、未覆盖遗留项(建议补测,不阻断代码达标)

- **R1 核价 production_no 取值**:官方核价6.0「生产料号」列全空,production_no 落库全 NULL,取值映射(生产料号值→production_no 且 ≠ 销售料号)未被官方数据证伪。开发方已用补充文件 `核价…-增加销售料号.xlsx` 自测 `capacity.production_no=PN-3120018220` 逐值吻合。**建议**:将含生产料号值的样例纳入官方验收集,走 test.md TC-C4 分支②正式复验后消除 R1。
- **R4 报价铸号正向路径**:报价 V3 投入料号列全空,缺失投入料号铸号逻辑未被触发/回归。**建议**:补一份含"未匹配投入料号"的样例验证 `XXXX-YYMMNNNNNN` 发号。反向(Q04 不再错误铸号)已由 TC-B5b 正向覆盖通过。

---

## 五、测试留痕 / 数据清理

- 导入产生:报价 importRecord `8310f5d8…`、核价 importRecord `ca21431c…`/`ca21…`(二次)、测试报价单 `729863ee…`(名"task0708测试报价单")。均为测试副产物,如需纯净环境可清理。
- 测试前已清空 QUOTE+PRICING 存量(清前 mcm QUOTE=241、element_bom=2229 等历史脏数据,系 R2 遗留)。

---

## 六、达标清单对照(test.md §5)

| 达标条件 | 状态 |
|---------|:---:|
| TC-A 全 PASS(schema 终态) | ✅ |
| TC-B/TC-C failedRows=0 且 material_no 正确 | ✅ 核价 SUCCESS/0 失败、报价复验 SUCCESS/0 失败,material_no 均正确 |
| **TC-D 撞键不覆盖(一票否决)** | ✅ |
| **TC-E is_current 唯一、不累加(一票否决)** | ✅ |
| TC-F 契约零变更 + 前端零改动 | ✅ |
| TC-G 两份文档已纠正、无 sales_part_no 残留 | ✅ 已合入 master(`8767d87`),仅 2 处废弃注记 |

**最终判定**:**全项 PASS,交付达标**。R1(核价 production_no 取值)、R4(报价铸号正向路径)为官方测试数据未覆盖项,建议择期补含生产料号值 / 未匹配投入料号的样例做二次验证闭环,不阻断本次达标结论。
