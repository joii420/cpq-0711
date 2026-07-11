# 后端修复任务 · repair-1:生产料号(production_no)落库补全 + 组成料号 calc_type 语义

> 负责人:cpq-backend｜优先级:P0｜前端:无改动(见 fronttask.md)｜接口契约:不变(见 api.md)
> 来源:task-0708 结案后,用户用**含生产料号值的新文件**复验,暴露 production_no 落库残缺(被原验收 R1「官方数据生产料号全空」掩盖)。
> 权威依据:本修复文档 = task-0708 澄清结论 + 本次 2 个补充决策(已与需求方确认)。

---

## 0. 一句话目标

让"生产料号"在导入后**落到它该落的每一张表的主行 + 部件主档**(当前只落到了明细表 bom_item/capacity,主表/映射表/主档全空),并让"组成料号"按 `计算类型` 正确区分语义、不污染主档。用**新文件**复验通过 = 达标(顺带彻底闭掉 task-0708 的 R1 遗留)。

---

## 1. 触发原因与实测证据(修复依据)

**新文件**:`docs/table/核价测试数据/核价系统功能基础数据功能结构所需字段（导入版本)-新版.xlsx`(生产料号 `3120018220`、销售料号 `S-3120018220`,两者不同值)。

导入后实测 production_no 落库全景(证据):

| 表 | 性质 | production_no 列 | 实际落值 | 问题 |
|----|------|:---:|:---:|------|
| `material_master` | 部件主档 | ❌ **无此列** | — | V315 漏加 |
| `material_customer_map` | 生产↔销售映射 | ✅有(V308) | **0 / 14** | P05 读了没写 |
| `material_bom`(主表) | BOM 主 | ✅有(V315) | **0 / 8** | P06 只写子表、主表留 NULL |
| `material_bom_item`(子表) | BOM 明细 | ✅有(V315) | 14 / 28 | ✅ 正常 |
| `capacity` 等成本表 | 成本 | ✅有(V315) | 4 / 8 | 部分 ✅(需审计主行是否全覆盖) |

**结论**:production_no **只落到明细/子表,没落到主表、映射表、部件主档** → 生产料号落库残缺。

---

## 2. 已定决策(实现必须对齐)

| # | 决策 |
|---|------|
| **决策 A**:production_no 归属 | `material_master` 作**权威归属**;`material_customer_map` / `material_bom` 主表 / 各成本表**主行**都写生产料号(与销售料号 `material_no` 1:1)。 |
| **决策 B**:组成料号 calc_type 分流 | **不加列**。`component_no` 存 `组成料号` 原值;语义靠已有 `calc_type` 列区分(材料=销售料号 / 元素=材质编号)。**元素行(材质编号)不得登记进 `material_master`**(防把材质编号当销售料号污染主档)。 |

---

## 3. 修复项

### 3.1 [DB] 新迁移 `V316`(创建前先 `ls db/migration` 确认最大号仍为 V315)

- `ALTER TABLE material_master ADD COLUMN IF NOT EXISTS production_no VARCHAR(32);`
- 是否进 material_master 唯一键:**不进**(material_master 键仍是销售料号维度;production_no 为 1:1 描述列)。
- 放进 `db/migration/` 后 `touch` 一个 java 文件让 Quarkus 自动 migrate(禁手工 psql,见 CLAUDE.md)。

### 3.2 [Code] P05CustomerMapHandler(宏丰-客户料号对应关系)

- 现状:line 35 已 `productionNo = row.getStr("生产料号")` 读到值,但**没写进任何表**。
- 修复:把 `productionNo` 写入
  - `material_master.production_no`(part 主档,决策 A 权威归属);
  - `material_customer_map.production_no`(该表天然是生产↔销售映射;列已存在 V308,补写值)。
- ⚠️ 若走原生 SQL upsert(`MaterialCustomerMapRepository.upsertPricing/...`),补形参 + SET production_no;若走实体,先确认实体已映射 production_no(参见 task-0708 backtask §2.4 的实体缺字段坑)。

### 3.3 [Code] P06MaterialBomHandler(物料BOM)

- **主表补写**(决策 A):现状 line 82 只把 production_no 放进子表 content,master header 留 NULL。改为 **master group 也写 production_no**(同一 material_no 下生产料号唯一,取该组任一非空值)。
- **calc_type gate material_master 登记**(决策 B):line 142-156 的组成料号自动登记 material_master,**必须跳过 `calc_type=元素` 的行**(那是材质编号不是料号)。仅 `calc_type=材料`(即组成料号=销售料号)的组成料号才 upsert 进 material_master。
- 子表 component_no 仍存组成料号原值、calc_type 仍照存(决策 B,不加列)。

### 3.4 [Code] 其余各表 handler 主行统一补写 production_no(决策 A)

对**其源 Sheet 含「生产料号」列**的所有 P* handler,审计并确保 production_no 写到该表**主行/权威行**(不只明细行):

| 表 | handler(示例) | 源 Sheet 有生产料号 | 待确认 |
|----|------|:---:|------|
| capacity | P08 类 | ✅ | 主行是否全覆盖(现 4/8) |
| production_energy / auxiliary_energy | P09/P10 类 | ✅ | 主行补写 |
| tooling_cost | 模具工装 | ✅ | 主行补写 |
| labor_rate | — | ✅ | 主行补写 |
| unit_price | P13~P23 费用类 | ✅(来料*/加工/成品/电镀/单重) | 主行补写 |
| element_bom / element_bom_item | P07 | ❌ 无生产料号列 | **保持 NULL**(无源,合理) |

> 交付前用 `grep -rn 'getStr("生产料号")' cpq-backend/.../pricing/` 列全所有读生产料号的 handler,逐个确认"读了 → 写进主行 production_no"。**"读了没写"是本次的核心 bug 模式**。

### 3.5 [Doc] 修正逐 Sheet 明细映射表

- `docs/table/核价系统Excel导入落库方案.md`(及报价对应文档)**§ 正文逐 Sheet 明细表**目前仍写 `生产料号/宏丰料号 → material_no`,与顶部 V6.3 摘要及代码矛盾。逐表改为:
  - `销售料号 → material_no`(主料号);
  - `生产料号 → production_no`;
  - `组成料号 → component_no`(备注:语义随 `计算类型` 变——材料=销售料号 / 元素=材质编号,由 calc_type 区分,不单列);
  - material_master 段补 `production_no`。
- ⚠️ 走**行级 patch**,避开主工作区那份并发 WIP(同 task-0708 8767d87 做法)。

---

## 4. 验收标准(供 QA 出新测试用例)

> ⚠️ **复验前置条件(RR-1,测试数据必须先修)**:当前新文件 `…（导入版本)-新版.xlsx` **内部不一致**,直接复验会卡在 AC-2 且整条链断:
> - `宏丰-客户料号对应关系`(P05 源)生产料号列 **0/14 全空** → P05 无生产料号可写;
> - 该 sheet 销售料号是**裸号** `3120018220`,而成本表(物料BOM 等)是 **`S-3120018220`(带 S-)** → 两表销售料号**零交集**,主档/映射与成本表 join 不上。
> - **须由需求方/数据 owner 先修文件**:① 全文件统一销售料号格式(带不带 S- 由需求方定);② P05 映射表补齐生产料号列 + 销售料号改成与成本表一致。修好(生产料号有值 + 销售料号全文件一致)后再复验。**此为测试数据缺陷,非 repair-1 代码问题。**

> QA 用**修正后的新文件**(生产料号有值 + 销售料号全文件一致)清空重导后逐条断言。SQL 前缀:`PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db -A -F'|' -c "..."`

| 编号 | 断言 | 预期 |
|------|------|------|
| **AC-1** material_master 有 production_no 列且有值 | `SELECT count(*) FILTER(WHERE production_no IS NOT NULL) n, count(*) t FROM material_master;` | 列存在;导入的销售料号对应行 production_no=对应生产料号值(如 material_no 对应 `3120018220`) |
| **AC-2** material_customer_map 主行写了生产料号 | `SELECT material_no,production_no FROM material_customer_map WHERE system_type='PRICING' AND production_no IS NOT NULL;` | ≥1 行;production_no=生产料号、material_no=销售料号,二者不同值 |
| **AC-3** material_bom 主表写了生产料号 | `SELECT count(*) FILTER(WHERE production_no IS NOT NULL) FROM material_bom WHERE system_type='PRICING';` | > 0(不再全 NULL);抽查某销售料号主行 production_no 正确 |
| **AC-4** production_no 值正确(≠销售料号) | 抽查任一表:`SELECT material_no,production_no FROM material_bom WHERE system_type='PRICING' AND production_no IS NOT NULL LIMIT 5;` | material_no=`S-xxxx`(销售)、production_no=`xxxx`(生产),逐值对得上新文件 |
| **AC-5** 各成本表主行覆盖 | 对 capacity/tooling_cost/unit_price 等逐表:主行 production_no 非空率符合源数据(源有生产料号的行都写上) | 无"读了没写"残留 |
| **AC-6** 组成料号 calc_type 区分 | `SELECT calc_type,count(*) FROM material_bom_item WHERE system_type='PRICING' GROUP BY calc_type;` | 材料/元素两类都在;component_no 存组成料号原值 |
| **AC-7** 元素行未污染 material_master | 取一条 `calc_type=元素` 的组成料号(材质编号),查 `SELECT count(*) FROM material_master WHERE material_no=该材质编号;` | **0**(材质编号未被当料号登记进主档) |
| **AC-8** element_bom 合理留空 | `SELECT count(*) FILTER(WHERE production_no IS NOT NULL) FROM element_bom WHERE system_type='PRICING';` | 0(该 Sheet 无生产料号列,留 NULL 合理) |
| **AC-9** 回归:task-0708 硬指标不破 | 撞键(3110520789 两材质料号)、is_current 唯一、material_no=销售料号 | 全部保持 PASS |
| **AC-10** 迁移/存活 | V316 success=t;后端探针非 500 | ✅ |

---

## 5. 自检(交付前必跑,附证据)

1. V316 `success=t`;`\d material_master` 含 production_no。
2. `grep -rn 'getStr("生产料号")'` 列出的 handler 逐个"读→写主行"确认(截图 grep 结果 + 对应写入代码行)。
3. 新文件清空重导 → AC-1~AC-8 逐条 SQL 通过;贴关键计数。
4. AC-7 元素污染专项:找一条元素行材质编号,证明 material_master 里没有它。
5. task-0708 回归(AC-9)不破。
6. 两份 `docs/table` 明细表已改、与代码同源。

> "完成"必须附一行自检声明(V316 success=t ✅ / material_master.production_no 有值 ✅ / material_bom 主表 production_no 非空 ✅ / 元素未污染主档 ✅ / task-0708 回归不破 ✅)。

---

## 6. 交付物清单

- [ ] 迁移 V316:material_master 加 production_no
- [ ] P05:生产料号写入 material_master + material_customer_map
- [ ] P06:主表补写 production_no + material_master 登记按 calc_type 跳过元素行
- [ ] 其余含生产料号 Sheet 的 handler:主行统一补写 production_no
- [ ] 实体/Repository 字段与形参同步(material_master / mcm)
- [ ] 两份 `docs/table` 逐 Sheet 明细表纠正(行级 patch)
- [ ] 自检证据(§5)齐全

---

## 7. 边界(本次不做)

- 组成料号**不加**材质编号新列(决策 B,靠 calc_type)。
- 不动 task-0708 已交付且正确的部分(销售料号落 material_no、element_bom material_part_no、V311 废弃、升版/撞键逻辑)。
- mat_part 退役 / 生产料号 BNF 重定向 / V6 查询页标签仍在 BACKLOG(BL-0035/0036/0037),不在本修复内。
