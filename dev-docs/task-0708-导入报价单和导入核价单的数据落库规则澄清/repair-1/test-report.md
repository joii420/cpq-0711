# 测试报告 · repair-1:生产料号(production_no)落库补全 + 组成料号 calc_type 语义

> 测试员:QA（独立复验,不采信后端自判）｜报告接收:技术总监｜测试日期:2026-07-09
> 依据:`repair-1/backtask.md §4(AC-1~AC-10)` + §2 决策 A/B｜用例:`repair-1/test.md`
> 被测代码:dev server commit `9433742`(feat/task-0708-repair-1-production-no;V316 已应用,P05/P06/P24 handler 生效)
> 测试数据:**自洽文件** `docs/table/核价测试数据/…（导入版本)-新版-自洽.xlsx`
> 方式:**净化 material_master 遗留污染 + 清空 PRICING**(不复用后端遗留状态)→ 自洽文件重导 → 逐条 DB 断言 → 二次重导验回归

---

## 一、总体结论:✅ 全项 PASS —— repair-1 达到交付水平

生产料号(`production_no`)已落到**每张主表/映射表/部件主档(material_master)主行**,值=生产料号且 ≠ 销售料号;组成料号按 calc_type 区分、**元素材质编号不再污染 material_master**;task-0708 撞键/is_current/material_no=销售料号 回归不破。task-0708 遗留 R1(生产料号取值未覆盖)**一并闭合**。

| AC | 结果 | 关键证据 |
|----|:---:|------|
| AC-1 material_master.production_no 有值 | ✅ | `S-3120018220→3120018220`;S- 主档 **10/10** 非空 |
| AC-2 mcm 主行写生产料号 | ✅ | **10/10** 非空;material_no=`S-xxx`、production_no=裸`xxx`,不同值(RR-1 闭合) |
| AC-3 material_bom 主表 production_no | ✅ | **4/4** 非空(不再全 NULL) |
| AC-4 production_no 值正确≠销售料号 | ✅ | S-2120011658↔2120011658 等逐值对得上,production_no≠material_no |
| AC-5 各成本表主行覆盖 | ✅ | capacity/能耗/tooling/labor_rate/bom_item 主行全覆盖;无"读了没写" |
| AC-6 calc_type 区分 | ✅ | 元素 6/材料 8;component_no 存原值(元素=材质编号、材料=S-销售料号) |
| **AC-7 元素码未污染 material_master** | ✅ | 元素码 `3112230066/2101110225/2111410069/3112230067` 在 material_master **0 行** |
| AC-8 element_bom 合理留空 | ✅ | production_no **0/4** 全 NULL |
| AC-9 回归(撞键/is_current/material_no) | ✅ | 见 §三 |
| AC-10 迁移/存活 | ✅ | V316 success=t;后端探针 401(非 500) |
| 去重(裸号+S-号) | ✅ | 同一产品仅 S- 号,无裸号重复行 |

**判定:PASS,建议技术总监据此复核结案。**

---

## 二、逐条证据

### 前置-导入
- 净化:material_master 删本测试涉及 24 个料号(裸号+S-号+元素码)→ 验证归 0;PRICING 7 表清空。
- 自洽文件重导:`status=SUCCESS,successRows=86,failedRows=0`(S- 前缀 + 列改名未致任何失败)。
- **自洽文件核对(造对确认)**:P05 映射表 生产料号 **10/10 有值**、销售料号 **全 S- 对齐**、**元素码已剔**(14→10 行)。

### AC-1 material_master.production_no
```
S-3120018220 | 3120018220          ← 权威主档带生产料号(决策 A)
S- 主档 production_no 非空: 10/10
```

### AC-2 material_customer_map.production_no（RR-1 闭合的关键）
```
material_no    | production_no
S-3120018220   | 3120018220
S-2120011658   | 2120011658
S-3110520789   | 3110520789
... (共 10 行, 全非空)          ← production_no=生产料号、material_no=销售料号, 不同值
非空: 10/10
```
> 初测 R1/RR-1:旧文件 P05 生产料号全空 → mcm.production_no 落 NULL。自洽文件补齐后,P05 正确写入 mcm.production_no。**RR-1 属测试数据缺陷(技术总监已定性),自洽文件修正后本项转 PASS。**

### AC-3 / AC-4 material_bom 主表 production_no
```
material_bom 主表 production_no 非空: 4/4
S-2120011658 | 2120011658
S-2120011659 | 2120011659
S-3110520789 | 3110520789
S-3120018220 | 3120018220        ← material_no(S-销售) ≠ production_no(裸生产)
```

### AC-5 各成本表主行覆盖（无"读了没写"）
```
capacity          4/4
production_energy  4/4
auxiliary_energy   4/4
tooling_cost       2/2
labor_rate         4/4
material_bom_item 14/14
unit_price        15/21   ← 6 个 NULL 已核实为无生产料号源的全局行:
                            ELEMENT(元素价格)3 + MATERIAL_PRICE(材料价格)2 + OUTSOURCE_PROCESS(其他外加工)1
```
> 凡源 Sheet 含生产料号的表,主行 production_no 全覆盖;NULL 仅出现在天然无生产料号列的全局/外加工行 → 合理,非 bug。

### AC-6 组成料号 calc_type
```
calc_type 分布: 元素 6 / 材料 8
component_no: 元素→2101110225/2111410069/3110520789/3112230066/3112230067(材质编号原值)
              材料→S-1630010773/S-2120011658...(销售料号原值)
```

### AC-7 元素码未污染 material_master（决策 B 核心 · 一票否决）
```
SELECT material_no FROM material_master WHERE material_no IN
  ('3112230066','2101110225','2111410069','3112230067');
→ 0 行
```
> 独立复验发现:旧畸形文件 P05 映射表把这 4 个元素码当**销售料号**登记(第 11–14 行,品名 料10–料13)→ material_master 被污染;**非 P06 代码问题**(P06 决策 B 已正确只登记材料行)。自洽文件剔除后,元素码不再进主档。**与技术总监判断一致。**

### AC-8 element_bom 合理留空
```
element_bom production_no 非空: 0/4    ← 物料与元素BOM 无生产料号列, 留 NULL 合理
```

---

## 三、AC-9 回归（task-0708 硬指标不破）

```
AC-9a 撞键:  element_bom S-3110520789 → 2 行 (material_part_no=2101110225 + 2111410069)   ✅ 未覆盖
AC-9b material_no: material_bom 全为 S- 销售料号(S-2120011658/…/S-3120018220), 无裸号/无生产号  ✅
AC-9c is_current: material_bom 违规 0 / element_bom 违规 0                                  ✅ 唯一
AC-9d 不累加:   二次导入 SUCCESS 86/0 → material_bom=4/element_bom=4/mcm=10/master(S-)=10 不翻倍;
               is_current 仍 0 违规;撞键仍 2 行;mcm.production_no 仍 10/10               ✅
```

---

## 四、去重（映射对齐后重复消除）

```
SELECT material_no FROM material_master WHERE material_no IN
 ('3120018220','S-3120018220','2120011658','S-2120011658','3110520789','S-3110520789');
→ 仅 S-2120011658 / S-3110520789 / S-3120018220     ← 无"裸号+S-号"两行, 重复已消除
```
> 旧文件因 P05 裸销售料号 vs 成本表 S- 销售料号并存,产生裸+S- 双主档行;自洽文件全文件统一 S- 后,主档唯一。

---

## 五、独立复验说明（不采信后端自判）

1. **全程自清状态**:每轮导入前净化 material_master 遗留污染 + 清空 PRICING,不复用后端遗留数据;导入用自洽文件、绝不用旧畸形文件跑正式断言。
2. **一次险些误判 → 独立查证纠正**:在畸形文件冒烟时 AC-7 元素码复现,初判疑似 P06 决策 B 失效;经**逐 sheet 反查 P05 源**,确认元素码是被 P05 映射表当销售料号登记(数据缺陷),P06 代码正确。此即独立复验价值——最终 PASS 基于自洽文件 + 亲验 SQL,非后端结论。
3. **AC-2/AC-7/去重** 三项此前受阻全部源于旧 P05 数据缺陷(生产料号空 + 裸销售料号 + 元素码当销售料号),自洽文件修正后全部转 PASS,印证"测试数据缺陷、非 repair-1 代码问题"的定性。

---

## 六、遗留 / 备注

- repair-1 代码在分支 `feat/task-0708-repair-1-production-no`(commit 9433742),dev server 已运行;**合并 master 为收尾动作**,建议连同该分支的 `docs/table` §3.5 明细表纠正一并并入(commit 02c97a6)。
- 测试副产物:PRICING 现存自洽文件导入数据(2 轮,未累加);material_master 含本测试 S- 料号 10 行。如需纯净可再清。
- task-0708 遗留 **R1(核价 production_no 取值未覆盖)** 经本次自洽文件正向验证(production_no=生产料号≠销售料号,逐值吻合)**正式闭合**。

**最终判定:repair-1 全项 PASS,达到交付水平。**
