# 后端任务 · task-0708 导入报价单/核价单落库料号语义纠偏

> 负责人:cpq-backend｜优先级:P0｜前端:无改动(见 fronttask.md)｜接口契约:不变(见 api.md)
> 权威规则来源:本次澄清 5 个决策点(已与需求方逐条确认),覆盖并纠正 `docs/superpowers/specs/2026-07-07-核价销售料号维度落库-design.md`(该 spec 的 `sales_part_no` 反向设计**作废**)。

---

## 0. 背景与目标(一句话)

系统引入"销售料号"概念(=宏丰料号改名)后,本周两条并行改造(报价 V308 / 核价 V311)对料号语义处理不一致。本次**统一**:让 `material_no` 承载**销售料号**(主料号),新增 `production_no` 承载**生产料号**;核价侧废弃 V311 的反向 `sales_part_no` 设计。目标 = 两个测试文件清空重导后**数据正确落库**。

---

## 1. 统一料号落库规则(权威 · 实现必须逐条对齐)

适用范围:QUOTE / PRICING 两条导入路径**共享**的 V6 基础表(靠 `system_type` 区分)。

### 1.1 三个料号字段的统一语义

| DB 字段 | 语义 | 取值来源 Excel 列 | 是否进唯一键 |
|---------|------|------------------|:---:|
| `material_no` | **销售料号**(系统主料号) | `销售料号`(报价旧文件兼容回退 `报价料号`/`宏丰料号`) | ✅ 所有表主维度 |
| `production_no` | 生产料号 | `生产料号`(核价大部分表有;报价 Excel 无→NULL;`element_bom` 无→NULL) | ❌ 描述列,不进键 |
| `material_part_no` | 材质料号(原料料号,原名"物料料号") | `材质料号`(原 `物料料号`) | ✅ 仅含此列的表(当前=`element_bom`/`element_bom_item`) |

### 1.2 唯一键 & 升版总原则

- **恢复到 V311 之前的键结构**(去掉 V311 追加的 `COALESCE(sales_part_no,'')` 后缀),`material_no` 的**值**由生产料号改为**销售料号**(键结构不变、只换值语义)。
- 含材质料号的表(`element_bom`/`element_bom_item`)额外把 `material_part_no` **纳入唯一键**。
- `production_no` **不进键**(生产:销售=1:N,销售料号更细,已能唯一决定生产料号)。
- **升版分组键(VersionedV6Writer groupKey)按 `material_no`(销售料号)** → 每个销售料号一份成本、独立升版。

### 1.3 例外表:element_bom / element_bom_item

| 字段 | 值 |
|------|-----|
| `material_no` | 销售料号(与其余表统一) |
| `material_part_no`(**本次新增列**) | 材质料号 |
| `production_no` | NULL(该 Sheet 无生产料号列) |
| 唯一键 | `(system_type, customer_no, material_no, material_part_no, characteristic)` |

### 1.4 不受本次影响(不在共享 11 表内,禁止顺手改)

- `材料核价价格表`→材料价格主表(键=材料料号)、`元素核价价格表`→元素价格主表(键=元素代码)——照旧。

### 1.5 保留不动(明确不回退)

- V308 的**发号服务** `XXXX-YYMMNNNNNN`(组件缺失投入料号时铸号)、**`system_type`** 主线隔离、**清空重导**机制,一律保留。

---

## 2. DB 变更

> ⚠️ **迁移落地方式(先判 DB 状态再选)**:V311 目前 git 未提交。执行前先查 `SELECT version,checksum FROM flyway_schema_history WHERE version='311'`:
> - **V311 尚未应用**(共享 dev DB 无 311 记录):直接**在原地改写 V311**(最干净),内容改成下方目标态。
> - **V311 已应用**(dev DB 有 311 记录):**新增 V315**(下一个空号)做正向转换 + 反做 V311 的 `sales_part_no`(shared dev DB 上安全,不触 checksum mismatch)。**默认走此路**,除非确认全体会话/worktree 的 dev DB 都没跑过 V311。
> 禁止手工 `psql -f`;放进 `db/migration/` 后 touch 一个 java 文件让 Quarkus 自动 `migrate-at-start`(见 CLAUDE.md「修改后强制自检」)。

### 2.1 反做 V311 的 sales_part_no(11 表)

对 V311 列出的 11 表 `unit_price / material_bom / material_bom_item / element_bom / element_bom_item / capacity / labor_rate / production_energy / auxiliary_energy / tooling_cost / material_customer_map`:
- `DROP COLUMN IF EXISTS sales_part_no`;
- 重建各表唯一索引,**去掉末尾 `COALESCE(sales_part_no,'')`**,恢复到 V311 之前的键列(逐字对照 V311 里 `DROP INDEX ... CREATE UNIQUE INDEX ...` 段,砍掉 sales 后缀)。

### 2.2 新增 production_no(生产料号)

对下列表 `ADD COLUMN IF NOT EXISTS production_no VARCHAR(32)`(描述列,不进唯一键):
`unit_price / material_bom / material_bom_item / element_bom(留空) / element_bom_item(留空) / capacity / labor_rate / production_energy / auxiliary_energy / tooling_cost`。
- `material_customer_map` 的 `production_no` **已由 V308 建**,复用,不重复加。

### 2.3 element_bom / element_bom_item 加 material_part_no + 改唯一键

- 两表 `ADD COLUMN IF NOT EXISTS material_part_no VARCHAR(32)`;
- 重建唯一键为 `(system_type, customer_no, material_no, material_part_no, characteristic)`(item 表按其现有键相应插入 material_part_no)。

### 2.4 实体同步(Panache)

- ⚠️ 已知坑:`MaterialCustomerMap` 实体**未映射** `system_type/production_no`(V308 起仅 Repository 原生 SQL 读写)。凡本次要用**实体**读写 `production_no`/`material_part_no` 的表,先在实体补字段;若走原生 SQL upsert(如 `MaterialCustomerMapRepository.upsertQuote`)则补形参。逐表确认走实体还是原生 SQL,不要漏。

---

## 3. 导入 handler 改造

> 原则:handler 按 §1 规则读列落库。**先 grep 全量 handler 列清单再逐个改**,不要只改下方点名的。
> 报价:`cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/`(Q* handler)
> 核价:`cpq-backend/src/main/java/com/cpq/basicdata/v6/pricing/`(P* handler)

### 3.1 报价侧(Q* handler)关键改动

- **主料号列别名**:现有 handler 读 `row.getStr("报价料号","宏丰料号")`,报价 V3 新列名是 **`销售料号`** → 改为 `row.getStr("销售料号","报价料号","宏丰料号")`(新列优先,旧名回退兼容)。落 `material_no`。
- **material_part_no**:报价 `物料BOM`/`物料与元素BOM`/`元素回收折扣` 含 `材质料号` 列 → 落 `element_bom.material_part_no`(与核价侧同口径)。
- **production_no**:报价 Excel **无**生产料号列 → 报价侧 `production_no` 恒 NULL(不必读)。
- **发号(铸号)边界**:仅在**实际含 `投入料号` 列**的 sheet(`来料*`/`自制加工费`/`组成件*` 等)保留投入料号缺失铸号逻辑。
  - ⚠️ **`Q04ElementBomHandler`(物料与元素BOM)例外**:该 sheet **无投入料号**(只有 销售料号+材质料号+元素),现状错误地 `materialNoResolver.resolve(投入料号)` 铸号 → 必改为:`material_no←销售料号`、`material_part_no←材质料号`、`component_no←元素`,master/版本分组键改为 `(material_no=销售料号, material_part_no=材质料号)`,**不再铸号**。
- **核价对侧 `P07ElementBomHandler`** 做同口径改造(见 §3.2),保证 element_bom 两条路径落列一致。

### 3.2 核价侧(P* handler)关键改动

- **`material_no` 改存销售料号**:现在多数 P* 读 `宏丰料号` 落 `material_no`(旧=生产料号)→ 改读 **`销售料号`** 落 `material_no`。
- **新增读 `生产料号`** 落 `production_no`(该 Sheet 有此列时)。
- **P07ElementBomHandler(物料与元素BOM)**:当前读 `row.getStr("物料料号","宏丰料号")` 落 `material_no`。改为:
  - `material_no` ← `销售料号`;
  - `material_part_no` ← `材质料号`(原 `物料料号` 已改名);
  - `production_no` ← NULL(该 Sheet 无生产料号列);
  - master groupKey / 唯一键加入 `material_part_no`(见 §1.3)。
- **VersionedV6Writer groupKey** 从 material_no(旧生产料号)切到 material_no(销售料号)——语义随字段值自动切换,但需确认 groupKey 构造处未硬编码。

### 3.3 Excel 列 → DB 字段 映射(实测两测试文件列头,实现按此逐 Sheet 核对)

**报价 V3(→ QUOTE，system_type='QUOTE')**

| Sheet | 主料号列→`material_no` | `material_part_no` | `production_no` | 其他料号列 |
|-------|----|----|----|----|
| 客户料号与宏丰料号的关系 | 销售料号 | — | NULL | 客户产品编号→customer_product_no |
| 物料BOM | 销售料号 | 材质料号 | NULL | 产出料号类型 |
| 物料与元素BOM(Q04→element_bom) | 销售料号 | 材质料号 | NULL | 元素→component_no;⚠️**本 sheet 无投入料号,不走发号**(现 `Q04ElementBomHandler` 错误地按投入料号 resolve/铸号,须改为按 销售料号+材质料号 分组、元素→component_no) |
| 元素回收折扣 | 销售料号 | 材质料号 | NULL | — |
| 来料*/组成件*/自制加工费/成品*/组装* | 销售料号 | — | NULL | 投入料号/组成件料号→component_no;**发号仅在此类仍含投入料号的 sheet 保留**(§1.5) |
| 单重 | 料号 | — | NULL | — |
| 电镀方案/电镀费用 | 方案编号/销售料号 | — | NULL | — |

**核价 6.0(→ PRICING，system_type='PRICING'，customer_no 从行读)**

| Sheet | 主料号列→`material_no` | `material_part_no` | `production_no` | 其他料号列 |
|-------|----|----|----|----|
| 宏丰-客户料号对应关系 | 销售料号 | — | 生产料号 | 旧料号 / 客户产品编号 |
| 物料BOM | 销售料号 | — | 生产料号 | 组成料号→component_no |
| **物料与元素BOM** | 销售料号 | **材质料号** | NULL(无列) | — |
| 产能/设备折旧/生产能耗/辅助能耗/模具工装/生产耗材BOM/包装材料BOM | 销售料号 | — | 生产料号 | 工序编号 |
| 来料加工费/来料其他费用/来料其他固定费用 | 销售料号 | — | 生产料号 | 来料料号→component_no |
| 加工费&组装费/成品其他比例费用/成品其他固定费用 | 销售料号 | — | 生产料号 | 要素编号 |
| 电镀成本/其他外加工成本/单重 | 销售料号 | — | 生产料号 | — |
| 核价版本(→`material_version_mgmt`) | 销售料号 | — | NULL | ⚠️ handler `P04PricingVersionHandler` 现读`宏丰料号`→**必改读`销售料号`**(否则整表导入全失败);该 sheet 无生产料号列;表不在 11-DDL 集(不加 production_no),仅 handler 读列 + material_no 语义改 |
| 汇总 | — | — | — | **不导入**:无 sheetName="汇总" 的 handler;`costing_summary` 由 `CostingSummaryService.compute()` 计算生成(键 `hf_part_no`),本次不改 |
| 材料核价价格表/元素核价价格表 | 材料料号 / 元素代码 | — | — | **不在 11 表,不改** |

---

## 4. 文档纠正(交付物一部分)

- `docs/table/报价系统Excel导入落库方案.md`:补"销售料号(主料号,落 material_no)/材质料号(落 material_part_no)"章节;修正过时点(宏丰料号→销售料号列名、9 字头发号已废、system_type、customer_product_no 可空)。
- `docs/table/核价系统Excel导入落库方案.md`:把"material_no=生产料号 + sales_part_no 维度"整体改写为"material_no=销售料号 + production_no=生产料号";`物料与元素BOM` 标注 material_part_no 例外;物料料号→材质料号改名;删除 sales_part_no 相关描述。

---

## 5. 风险清单 & 必查项(实现/自检必读)

1. **版本表 `is_current` 翻转范围**(参考记忆 v6-child-multiversion-iscurrent-audit-scope):升版分组键从生产料号→销售料号后,`is_current` 的 FLIP 范围随之变化。必须**穷举**配置 SQL + Java 直接 SQL 两侧,确认按新 groupKey 翻转,不产生跨版本多 current。
2. **两条路径一致性**:报价/核价共享 V6 表,`material_no`/`material_part_no` 落列口径必须两侧一致(否则同表出现两套语义)。
3. **element_bom 撞键**:`material_part_no` 必须真正进唯一键,否则同一销售料号的多个材质料号行互相覆盖 → element BOM 丢数据。
4. **唯一键去 sales 后缀要逐表逐字对照 V311**,漏一张表 = 该表唯一性错误。
5. **实体 vs 原生 SQL**:§2.4,补字段/形参别漏。
6. **groupKey 未硬编码**:确认 VersionedV6Writer 的 material_no 分组不是写死取某列。
7. **视图 DDL 后重启 Quarkus**:若重建唯一索引/涉及 CASCADE,按 CLAUDE.md 必 touch java 重启,防进程级缓存脏。

---

## 6. 自检(交付前必须逐条跑,附证据)

1. 迁移落地:`SELECT version,success FROM flyway_schema_history WHERE version IN ('311','315')` → success=t;`\d element_bom` 确认 `material_part_no` 存在、唯一索引已含它、`sales_part_no` 已无。
2. 后端存活:`curl -s -o /dev/null -w '%{http_code}\n' --noproxy '*' http://localhost:8081/api/cpq/basic-data-import/v6/00000000-0000-0000-0000-000000000000` → 401/404(非 500)。
3. **报价重导**:清空 → `POST /v6/quote`(报价 V3)→ 轮询至 `SUCCESS`、`failedRows=0`;抽查 `material_customer_map` 的 `material_no`=销售料号值、`material_bom` 有对应行。
4. **核价重导**:清空 → `POST /v6/pricing`(核价 6.0)→ `sheets[]` 全成功;抽查:
   - 任一成本表 `material_no`=销售料号、`production_no`=生产料号;
   - `element_bom.material_no`=销售料号、`material_part_no`=材质料号、`production_no` IS NULL;
   - 同一销售料号下多材质料号行**均存在**(未撞键覆盖)。
5. 升版:同一销售料号重导两次,版本正确递增、`is_current` 唯一(不累加、不多 current)。
6. 文档两份已更新并与代码同源。

> 交付"完成"必须附一行自检声明(flyway success=t ✅ / 报价 failedRows=0 ✅ / 核价 element_bom material_part_no 正确 ✅ / is_current 唯一 ✅)。无此声明=未完成。

---

## 7. 交付物清单

- [ ] 迁移(改写 V311 或 新增 V315):反做 sales_part_no + 加 production_no + element_bom 加 material_part_no + 唯一键重建
- [ ] 报价 Q* handler:主料号读 `销售料号`、材质料号落 material_part_no
- [ ] 核价 P* handler:material_no 改存销售料号、新增读生产料号→production_no、P07 材质料号→material_part_no
- [ ] 实体/Repository 字段与形参同步
- [ ] 两份 `docs/table` 落库方案文档纠正
- [ ] 自检证据(§6)齐全
