# HF 主导身份重设计 — 客户料号降为可选属性

> **日期**: 2026-05-14
> **状态**: 设计中,待评审
> **触发**: Rockwell Excel 导入撞 `uq_mat_cust_part(customer_id, customer_product_no)` 1:1 约束 — Excel 实际填法是同一客户料号映射到多个宏丰料号(PN-509102 → 3 hf 等共 10 个 cpn 各对应 ≥2 hf)
> **设计目标**: 把"宏丰料号(hf_part_no)"确立为产品卡片唯一身份,"客户料号(customer_product_no)"降为可空附属属性;消除多张表里"cpn 必填 / cpn 唯一"等内嵌假设;让选配/导入/版本/报价/核价五条主线统一以 hf 为锚

> **配套**:
> - 现状架构: `docs/superpowers/specs/2026-04-26-cpq-design-v5.1.md` §6.1
> - 历史选配设计(已上线): `docs/superpowers/specs/2026-05-13-add-product-configure-design.md`
> - 反模式速查: `docs/反模式.md` AP-13 / AP-15(客户级版本表三方对齐律)
> - V6 staging 导入: `docs/superpowers/specs/2026-05-12-import-v6-staging-design.md`

---

## 目录

1. [背景与动机](#1-背景与动机)
2. [核心决策清单(Q1–Q8)](#2-核心决策清单q1q8)
3. [新数据模型 — DDL 差异](#3-新数据模型--ddl-差异)
4. [服务层改造](#4-服务层改造)
5. [前端适配](#5-前端适配)
6. [存量数据迁移策略](#6-存量数据迁移策略)
7. [API 兼容性 / 行为差异](#7-api-兼容性--行为差异)
8. [验收清单](#8-验收清单)
9. [实施清单 — Phase 拆分](#9-实施清单--phase-拆分)
10. [已知风险与回滚预案](#10-已知风险与回滚预案)
11. [不在本次范围](#11-不在本次范围)

---

## 1. 背景与动机

### 1.1 现状失配

| 表 / 字段 | 当前设计 | 与业务规则冲突点 |
|---|---|---|
| `mat_customer_part_mapping(customer_id, customer_product_no)` UNIQUE(V44 line 223) | "客户内,1 客户料号 → 唯一 hf" | Rockwell Excel 10 个 cpn 各对应 ≥2 hf;PN-509102 → 3 hf |
| `mat_customer_part_mapping.customer_product_no VARCHAR(64)` 列(V44 line 211) | 允许 NULL | 但 `fillMappingRow` 显式 skip 空 cpn 行 — 数据丢弃 |
| `mat_part_version_log` PK(三元含 `customer_product_no NOT NULL`) | cpn 强制非空 | 选配 feature 产生的 hf **无 cpn**,version_log baseline 直接写不进去(2026-05-13 已记入"未来 follow-up") |
| `uq_mat_cust_part_global(customer_product_no, hf_part_no) WHERE NOT NULL`(V151) | 限制 (cpn, hf) 对全局不重复 | 当前以 cpn 为主轴的全局唯一,与"hf 主导"语义反向 |
| `BasicDataImportServiceV5.fillMappingRow` | `if (cpNo.isBlank()) return;` | 业务上"cpn 可空"的合法数据被丢 |
| V6 staging `decisionKey="{cpn}|{hf}"` + UI-1 冲突预览 | 以 cpn 为版本决策维度 | 与 hf 主导矛盾 |

### 1.2 设计目标(Done 定义)

1. **mat_customer_part_mapping 语义重定义为"客户对 hf 的附属属性表"**:`(customer_id, hf_part_no)` 唯一,`customer_product_no` 是其中一个可空字段(与 `customer_part_name / customer_drawing_no / payment_method / base_currency / quote_currency` 同列)
2. **mat_part_version_log PK 不含 cpn**:改成 `(customer_id, hf_part_no, version)`
3. **导入 / staging / 版本服务**全部按 hf 维度决策,允许 cpn 空
4. **选配 feature 修复历史遗留**:version_log baseline 可写入
5. **核价 / Excel 视图 / 公式引擎 / 选配主流程 / 报价单 line_item snapshot 字段 0 改动**(已 hf 主导)
6. **不破坏已发布报价单 / 核价单的数据可读性**:历史 cpn 字段保留,前端仍可显示

### 1.3 不动的边界

- `quotation` / `quotation_line_item` schema 不动(已 hf 主导)
- `costing_summary` / `costing_summary_result` / 7 项 metric 计算 — 0 改动
- `Excel 模板配置` / `costing_template` / 公式引擎 / `ImplicitJoinRewriter` — 0 改动
- 选配主链路(P0-P5)— 0 改动,仅修复 version_log baseline
- 报价单审批 / 撤回 / 比对视图 — 0 改动

---

## 2. 核心决策清单(Q1–Q8)

| # | 主题 | 决策 | 关键依据 |
|---|---|---|---|
| Q1 | 业务唯一键应该是? | **A:`(customer_id, hf_part_no)`** | hf 是全局料号,客户的"对此 hf 的特有属性"是 1:1 |
| Q2 | 同 (cust, hf) 历史多行 cpn 不同怎么 fold? | **F2:取 `created_at` 最早行的 cpn 为 current,其余 cpn 写日志 `mat_mapping_cpn_history` 归档表(可选,仅审计)** | 数据可追溯 + 99% 单 cpn 场景零干扰 |
| Q3 | cpn 是否保留 schema 列? | **C1:保留,允许 NULL,作为"该客户对此 hf 的本地编号"展示字段** | 与 customer_part_name / customer_drawing_no 同级 |
| Q4 | `uq_mat_cust_part_global` 怎么处理? | **G1:DROP(per-customer 已唯一即可,无需全局唯一)** | 简化约束栈;同一 cpn 不同客户使用是正常业务 |
| Q5 | `mat_part_version_log` PK 重设计 | **L1:`(customer_id, hf_part_no, version)`** | 与版本管理"客户级 mat_*"系列对齐 |
| Q6 | V6 staging decisionKey 形态 | **D1:`hf_part_no`(单段)** | 简化;cpn 不再参与版本决策维度 |
| Q7 | UI-1 客户数据冲突预览维度 | **U1:hf 卡片为主,cpn 作为 hf 卡片内的一个属性** | 与新 schema 一致;cpn 多/空场景 UI 自然处理 |
| Q8 | PartVersionService API 签名 | **P1:`applyVersionBump(customerId, hfPartNo, userId, sourceExcel, ...)`** — cpn 移除 | 与新 PK 对齐 |

---

## 3. 新数据模型 — DDL 差异

### 3.1 `mat_customer_part_mapping` 约束改造

```sql
-- Vxxx__mapping_hf_as_primary.sql

-- 1. 删旧的 1:1 唯一索引
DROP INDEX IF EXISTS uq_mat_cust_part;

-- 2. 删 V151 加的全局 cpn-hf 唯一索引(语义反向)
DROP INDEX IF EXISTS uq_mat_cust_part_global;

-- 3. 新建 (customer_id, hf_part_no) 唯一索引(新业务唯一键)
CREATE UNIQUE INDEX uq_mat_cust_part_per_hf
    ON mat_customer_part_mapping (customer_id, hf_part_no);

-- 4. cpn 列继续保留,允许 NULL(V44 line 211 已是,无需 ALTER)

COMMENT ON COLUMN mat_customer_part_mapping.customer_product_no
    IS 'v6.2: 客户对此 hf 的本地编号,可空;不再是业务唯一键';
```

**前置数据清理(同迁移内做)**:同 (customer_id, hf_part_no) 历史多行的归一化 — 见 §6。

### 3.2 `mat_part_version_log` PK 重设计

当前 PK = `(customer_product_no NOT NULL, hf_part_no, version)`,改成 `(customer_id, hf_part_no, version)`。

```sql
-- Vxxx__part_version_log_pk_reshape.sql

-- 1. 新加 customer_id 列(若未有)
ALTER TABLE mat_part_version_log
    ADD COLUMN IF NOT EXISTS customer_id UUID;

-- 2. 历史数据回填:按 customer_product_no 反查 mat_customer_part_mapping 取 customer_id
UPDATE mat_part_version_log L
SET customer_id = M.customer_id
FROM mat_customer_part_mapping M
WHERE L.customer_id IS NULL
  AND L.customer_product_no = M.customer_product_no
  AND L.hf_part_no = M.hf_part_no;

-- 3. customer_id 未能回填的孤儿行处置(理论上选配产生的 cpn 空行不会有 log,此处兜底)
DELETE FROM mat_part_version_log WHERE customer_id IS NULL;

-- 4. 旧 PK 解除 + 列改可空
ALTER TABLE mat_part_version_log
    DROP CONSTRAINT mat_part_version_log_pkey;
ALTER TABLE mat_part_version_log
    ALTER COLUMN customer_product_no DROP NOT NULL;

-- 5. customer_id 设 NOT NULL + 新 PK
ALTER TABLE mat_part_version_log
    ALTER COLUMN customer_id SET NOT NULL,
    ADD CONSTRAINT mat_part_version_log_pkey
        PRIMARY KEY (customer_id, hf_part_no, version);

-- 6. cpn 保留为附属(审计用),customer_id 列设 FK
ALTER TABLE mat_part_version_log
    ADD CONSTRAINT mat_part_version_log_customer_fkey
        FOREIGN KEY (customer_id) REFERENCES customer(id);

COMMENT ON COLUMN mat_part_version_log.customer_id
    IS 'v6.2: 客户级版本日志的客户维度(新 PK 成员)';
COMMENT ON COLUMN mat_part_version_log.customer_product_no
    IS 'v6.2: 可空;仅作历史 cpn 记录,不参与 PK';
```

### 3.3 其他表 — 0 DDL 变更

- `mat_part`:hf 主键不变,新增的 `material_recipe_id / product_type / config_fingerprint` 不动
- `mat_bom / mat_process / mat_fee / mat_plating_fee / mat_composite_process`:`hf_part_no` FK + 客户级 UNIQUE 已是 hf 主导,不动
- `quotation_line_item`:已用 `product_part_no_snapshot`(hf),不动
- `costing_summary*`:不动
- 视图(`v_q_* / v_c_* / v_costing_*`):不动

---

## 4. 服务层改造

### 4.1 `BasicDataImportServiceV5` — 解析阶段

| 文件 | 方法 | 改动 |
|---|---|---|
| `BasicDataImportServiceV5.java` | `fillMappingRow` | **删除** `if (cpNo == null || cpNo.isBlank()) return;`;改为 `if (hfPartNo == null || hfPartNo.isBlank()) return;`,cpn 留 NULL 允许 |
| `BasicDataImportServiceV5.java` | `fillMatPartRow` | 不动 |

### 4.2 `StagingWriter` — staging 写入阶段

无改动。`writeMappingStaging` 已经把空 cpn 直接 `setString` 写 NULL,只是上游 fillMappingRow 把空 cpn 行丢了。上游放开后这里自然吃到。

### 4.3 `StagingMerger` — commit 合并阶段

| 方法 | 改动 |
|---|---|
| `applyPartVersionDecisions` | decisionKey 从 `"{cpn}|{hf}"` 改为 `"{hf}"`;split/解析逻辑相应改 |
| `mergeMapping` | 1. WHERE 子句从 `hf_part_no = ? AND customer_product_no = ?` 改成 `hf_part_no = ? AND import_session_id = ?`(cpn 作为 SELECT 出来的字段,不再用作 WHERE)<br>2. `ON CONFLICT` 目标改成 `(customer_id, hf_part_no)` 匹配新 `uq_mat_cust_part_per_hf`<br>3. cpn 在 ON CONFLICT DO UPDATE 中**仅当原值 NULL 时覆盖**(避免存量 cpn 被空覆盖):`customer_product_no = COALESCE(mat_customer_part_mapping.customer_product_no, EXCLUDED.customer_product_no)` |
| `backfillOrphanParts` | 不动(本次新加的逻辑,无 cpn 依赖) |
| `mergePart / mergeBom / mergeProcess / mergeFee / mergePlatingFee / mergePlatingPlan` | 不动(已 hf 主导) |

### 4.4 `PartVersionService`

| 方法 | 改动 |
|---|---|
| `applyVersionBump(cpn, hf, userId, sourceExcel, ...)` | 签名改为 `applyVersionBump(customerId, hf, userId, sourceExcel, ...)` |
| 内部 `INSERT INTO mat_part_version_log` | PK 列从 `(cpn, hf, version)` 改成 `(customer_id, hf, version)`;cpn 作为附属字段写入(从 mapping 反查) |
| `getCurrentVersion(...)` | 查 mapping 改用 `(customer_id, hf)` |

### 4.5 `ImportSessionService.commit`

| 改动 |
|---|
| 调用 `stagingMerger.applyPartVersionDecisions` 传入 decisionKey 形态从 `cpn|hf` → `hf`,内部 split 取 hf 段 |
| `metadata.hfPairs` 列表(写入 import_record):改成 `metadata.hfs` 单值列表(或保留 `hfPairs` 兼容,cpn 字段允许 null) |
| `quotation.create` 时报价单与 hf 关联的部分不变 |

### 4.6 `CustomerPartCandidateService.listCandidates`

| 改动 |
|---|
| 列表项 key 从 cpn 改 hf;返回每个 hf 时同时带回该客户 mapping 中的 cpn(可空) + customer_part_name / customer_drawing_no |
| 排序 / 筛选默认按 hf 升序 |

### 4.7 差异检测器 / Conflict UI 后端

| 文件 | 改动 |
|---|---|
| `BasicDataImportServiceV5.detectCustomerDataConflicts` 等 | dbMap key 主键从含 cpn 改为含 hf 维度;cpn 作为可空属性比较 |
| `import_session_decision` 的 `decision_key` 写入格式 | `hf` 单段 |

---

## 5. 前端适配

### 5.1 `BasicDataImportV5ToQuotation` 系列 wizard 抽屉

| 组件 | 改动 |
|---|---|
| `Step1 上传 + 解析预览` | 列表/表格列首改 hf,cpn 作为副列;cpn 空显示「—」 |
| `Step2 客户数据冲突预览(UI-1)` | 每个 hf 作为一行卡片,cpn 显示在 hf 卡片内属性区;冲突逐字段展示不变 |
| `Step3 版本决策(UI-2)` | per-hf 一行 BUMP/NEW/NO_BUMP 选择,cpn 文字辅助显示 |

### 5.2 `CreateQuotationDrawer` / `AddProductModal`

| 控件 | 改动 |
|---|---|
| 产品选择下拉 | 主显示 hf,副显示「客户料号: PN-509100」(空则不显示) |
| 搜索 | 同时按 hf 和 cpn 搜(LIKE OR) |

### 5.3 `quotation/QuotationStep2` / `LinkedExcelView`

不变。已 hf 主导。

### 5.4 文案

| 旧 | 新 |
|---|---|
| 「客户料号」列首/必填星号 | 「宏丰料号」必填,「客户料号」可选 |
| 错误提示「客户料号已存在」 | 「该客户已绑定此宏丰料号 X(原客户料号: Y)」 |

---

## 6. 存量数据迁移策略

### 6.1 mat_customer_part_mapping 归一化

```sql
-- 同 (customer_id, hf_part_no) 历史多行(场景罕见)处置:
-- 1. 取 created_at 最早行保留 + 它的 cpn 作为 current cpn
-- 2. 其余行的 cpn 移入归档表(可选)

CREATE TABLE IF NOT EXISTS mat_mapping_cpn_history (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id     UUID NOT NULL,
    hf_part_no      VARCHAR(64) NOT NULL,
    cpn_archived    VARCHAR(64) NOT NULL,
    archived_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    archived_reason VARCHAR(64) NOT NULL DEFAULT 'V6.2_dedupe'
);

-- 归档非 current 行的 cpn(若有)
INSERT INTO mat_mapping_cpn_history (customer_id, hf_part_no, cpn_archived)
SELECT customer_id, hf_part_no, customer_product_no
FROM (
    SELECT id, customer_id, hf_part_no, customer_product_no,
           ROW_NUMBER() OVER (PARTITION BY customer_id, hf_part_no ORDER BY created_at, id) AS rn
    FROM mat_customer_part_mapping
    WHERE customer_product_no IS NOT NULL
) t
WHERE rn > 1;

-- 删除非首行(其属性已归档,但若有 line_item 引用其 id 需慎处理,先扫)
DELETE FROM mat_customer_part_mapping
WHERE id IN (
    SELECT id FROM (
        SELECT id, ROW_NUMBER() OVER (PARTITION BY customer_id, hf_part_no ORDER BY created_at, id) AS rn
        FROM mat_customer_part_mapping
    ) t WHERE rn > 1
);
```

**前置扫描**(在迁移前由人工/脚本跑一次):

```sql
-- 扫出会被归档的行数 + 涉及客户/料号
SELECT customer_id, hf_part_no, COUNT(*) AS dup_rows, ARRAY_AGG(customer_product_no) AS cpns
FROM mat_customer_part_mapping
GROUP BY customer_id, hf_part_no
HAVING COUNT(*) > 1;
```

### 6.2 mat_part_version_log 回填(见 §3.2)

### 6.3 已上线选配料号(V164+ 之后产生的 hf,cpn 空)

- 直接受益:mat_part_version_log baseline 可补写(可选,作为单独的小迁移)
- 不破坏现有 mapping(未写入 mapping)

---

## 7. API 兼容性 / 行为差异

| API | 旧行为 | 新行为 | 客户端兼容 |
|---|---|---|---|
| `POST /api/cpq/import-sessions/{id}/commit` | decision 内部用 cpn | 内部用 hf;前端传参可保留 cpn 但被 ignore | 透明,前端无感 |
| `GET /api/cpq/customer-part-candidates` | 按 cpn 列表 | 按 hf 列表 + cpn 附属 | 前端需调整列字段(同 §5.1) |
| `POST /api/cpq/quotations` 创建报价单 | line_item 选 cpn 或 hf 均可 | 推荐传 hf;传 cpn 仍兼容(后端查 mapping 反推 hf) | 兼容 |
| `GET /api/cpq/quotations/{id}` 报价单详情 | line_item 含 product_part_no_snapshot | 不变 | 完全兼容 |

---

## 8. 验收清单

### 8.1 数据层

- [ ] `\d mat_customer_part_mapping` 显示 PK=id,UNIQUE INDEX `uq_mat_cust_part_per_hf(customer_id, hf_part_no)`,无 `uq_mat_cust_part` 和 `uq_mat_cust_part_global`
- [ ] `\d mat_part_version_log` PK=`(customer_id, hf_part_no, version)`,`customer_product_no` 可空
- [ ] `flyway_schema_history` 显示新 V_xx migration success=t

### 8.2 后端服务

- [ ] Rockwell Excel(`报价系统功能基础数据功能结构所需字段——rockwell.xlsx`)导入端到端不撞约束 — 88 行 mapping 全部入库
- [ ] PN-509102 在 mapping 表为 3 行(`customer_id, hf=4170010173/3120014695/4140010115, cpn=PN-509102`)
- [ ] 创建一份**选配产品**报价单(SIMPLE),mat_part_version_log 写入 baseline 行(`customer_id, hf, version=2000`)— 验证选配 baseline 遗留问题修复
- [ ] cpn 空行的 Excel(构造测试):导入后 mat_customer_part_mapping 有对应 NULL cpn 行,不报错
- [ ] 一份 Excel 多 sheet 引用 hf=X,但单重 sheet 漏列 X — `backfillOrphanParts` 仍正常工作
- [ ] `tsc --noEmit` 0 错误,Vite 200,后端 401

### 8.3 前端 UI

- [ ] V6 上传抽屉 Step2 客户数据冲突预览:hf 作为主行,cpn 在 hf 卡片内显示;cpn 空时不显示该字段
- [ ] V6 上传抽屉 Step3 版本决策:per-hf 一行;cpn 副显示
- [ ] 报价单创建抽屉的产品选择控件:主显 hf,副显「客户料号: X」(空时不显示)
- [ ] 强刷 Ctrl+Shift+R 后所有控件文案无残留旧 cpn-必填的提示

### 8.4 反模式回归

- [ ] AP-13 / AP-15(客户级版本表三方对齐)未被破坏
- [ ] AP-22(多行数据 X (共N项))无新增触发场景
- [ ] 已发布报价单 / 核价单**完全可读**(线下抽样 3-5 张确认 line_item / costing_summary / Excel 视图全部正常)

### 8.5 文档更新

- [ ] `docs/PRD-v3.md` §6.1 / §9 演进史 — 同步新 hf-主导设计
- [ ] `docs/反模式.md` — 加 AP-XX「客户料号 1:1 假设导致组合产品/无 cpn 场景失败」
- [ ] 本 spec(`docs/superpowers/specs/2026-05-14-hf-as-primary-identity.md`)状态 → 已实施
- [ ] `docs/RECORD.md` 追加实施记录

---

## 9. 实施清单 — Phase 拆分

### Phase 1 — DB Schema 迁移(2 个 Flyway)

- T01 `Vxxx__mapping_hf_as_primary.sql`:DROP `uq_mat_cust_part` + DROP `uq_mat_cust_part_global` + 数据归一化(同 §6.1) + CREATE `uq_mat_cust_part_per_hf`
- T02 `Vxxx__part_version_log_pk_reshape.sql`:加 `customer_id` 列 + 回填 + DROP 旧 PK + 加新 PK(同 §3.2)
- T03 前置扫描脚本(可选):`scripts/scan_v62_migration_risks.py` 输出当前 mapping 重复 / version_log 孤儿统计,迁移前人工确认
- T04 验证:Flyway success=t + `\d` 输出符合

### Phase 2 — 后端服务改造

- T05 `BasicDataImportServiceV5.fillMappingRow`:移除空 cpn skip,hf 改为必填校验
- T06 `StagingMerger.applyPartVersionDecisions`:decisionKey 改用 `hf`(单段)+ 兼容旧 `cpn|hf` 解析(读 split[1])
- T07 `StagingMerger.mergeMapping`:WHERE / ON CONFLICT 改 `(customer_id, hf_part_no)`,cpn COALESCE 处理
- T08 `PartVersionService.applyVersionBump`:签名 cpn → customer_id;内部 INSERT log 改新 PK 列
- T09 `ImportSessionService.commit`:decision metadata 写入兼容;`hfPairs` 字段 cpn 允许 null
- T10 `CustomerPartCandidateService.listCandidates`:返回结构以 hf 为 key
- T11 后端 test:重跑现有 4 套 import test + 加 1 套"cpn 空 / 1 cpn 多 hf"集成测试

### Phase 3 — 前端适配

- T12 `BasicDataImportV5ToQuotation/Step1Preview`:列字段 / 排序按 hf
- T13 `BasicDataImportV5ToQuotation/Step2ConflictPreview`:hf 卡片为主结构
- T14 `BasicDataImportV5ToQuotation/Step3Decisions`:per-hf 决策
- T15 `AddProductModal / 产品下拉 / 搜索`:主显 hf + 副显 cpn
- T16 `CreateQuotationDrawer`:产品选择控件文案
- T17 文案 sweep:全局 grep「客户料号必填」类提示

### Phase 4 — 文档与验收

- T18 PRD-v3 / 反模式 / RECORD 三处文档同步
- T19 端到端验收(同 §8)
- T20 Spec 状态 → 已实施 + 加入 docs/RECORD.md

---

## 10. 已知风险与回滚预案

| 风险 | 影响 | 处置 |
|---|---|---|
| Phase 1 迁移前未做 §6 归一化,直接 CREATE UNIQUE 会失败 | Flyway 停在迁移失败 → Quarkus 启动失败 | 迁移内嵌归一化 SQL + 前置扫描脚本;失败时手动 rollback Flyway 行 |
| mat_part_version_log 历史 cpn 反查回填失败(cpn 不在 mapping 表) | 那一行 customer_id 留 NULL → 被 DELETE | 迁移前先 SELECT 数 + 留备份表 `mat_part_version_log_pre_v62` |
| 前端 / 旧报价单依赖 cpn 作为 unique key 的 useEffect 等 | 二次加载/显示空白 | grep `customerProductNo.*===` / `cpn.*===` 全前端排查 |
| 已有 `import_session_decision` 行用旧 `cpn|hf` key | 重启后 commit 走读取分支兼容旧 key | T06 兼容 split[1] 取 hf 段 |
| Rockwell 之外某客户业务上确实 1 客户料号 → 1 hf,前端按 cpn 唯一展示 | 旧逻辑 OK | 0 改动,因为新 schema 是 1 hf → 0/1 cpn,不破坏 1:1 子集 |

**回滚预案**:DDL 迁移都是 DROP/ADD,可逆;若 Phase 1-2 上线后发现严重问题:
1. 写一个 `Vxxx__rollback_v62.sql`:恢复 `uq_mat_cust_part` + `uq_mat_cust_part_global` + 旧 PK
2. service 层用 git revert 回滚
3. 前端用 git revert 回滚

---

## 11. 不在本次范围

- **选配 feature 主链路改动** — 0 改动,本次仅顺手修复 baseline 遗留问题
- **核价单 / Excel 视图 / 公式引擎** — 0 改动
- **报价单审批 / 撤回 / 比对视图** — 0 改动
- **多 cpn → 1 hf 场景(同客户对一个 hf 起多个本地编号)** — 当前归档进 `mat_mapping_cpn_history` 仅作审计,UI 不展示;若未来业务需要,再扩展 mapping 表为 1:N cpn(主属性 + 别名列表)
