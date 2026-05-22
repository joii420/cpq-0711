# ADR-002 模板绑定全局变量 + 报价单引用数据 Tab

| 字段 | 内容 |
|---|---|
| **状态** | Accepted |
| **决策日期** | 2026-05-21 |
| **决策者** | 架构师（编排器协议批准） |
| **影响** | 模板管理 + 报价单渲染 两个核心模块的"配置端 → 消费端"新增数据通道 |
| **关联文档** | PRD-v3.md §3.7, V104__global_variable_registry.sql, V212 SQL, docs/三大核心模块基线.md |

---

## 1. 背景

报价单依赖若干**全系统共享的基础数据**（元素单价 `ELEM_PRICE`、材料单价 `MAT_PRICE`、汇率 `EXCHANGE_RATE` 等），这些数据已注册于 `global_variable_definition` 表（V104），通过 `GlobalVariableService` 暴露给公式引擎做按 key 求值。

**业务诉求**（来自 PRD-v3 §3.7 业务目标）：

1. **配置端**：模板管理员在编辑模板时，能声明"本模板出的报价单需要参考哪几张价格表"，并排序。
2. **消费端**：销售/审批人在报价单详情页，开一个独立的「引用数据」Tab 直接看完整价格表，无需切回配置中心。
3. **审计端**：报价单提交（DRAFT → SUBMITTED）瞬间，把当时的价格表全量行**快照固化**，确保事后能复盘"这笔报价当时引用的价格表长什么样"。

**业务边界**：
- 这不是公式引擎扩展（公式引擎已能按 `{type:'global_variable', code:'ELEM_PRICE', ...}` token 求单值）。
- 这是新增"**全表展示通道**"：把价格表整张表当成一个可视化数据卡片放到报价单详情。

---

## 2. 决策（5 条核心架构选择）

### 决策点 1：关联表 vs JSONB 字段存绑定关系

**选择**：新增独立关联表 `template_global_variable_binding`（M:N），**不**用 `template.componentsSnapshot` 风格的 JSONB 数组。

**取舍**：
| 方案 | 优点 | 缺点 |
|---|---|---|
| ✅ 关联表 | FK 完整性 / 外键 RESTRICT 防止误删 GV / 索引可控 / 列展开易跑 SQL | 多一张表，要写 Repository |
| ❌ JSONB 内嵌 `template.globalVariableBindings` | 一次查模板就拿到 | FK 弱、UNIQUE 难做、JSONB 操作语法笨重；createNewDraft 拷贝时容易遗漏 |

**理由**：绑定关系是**强结构化数据**（每行只有 `template_id + code + display_order`，无业务可变性），符合 PRD 规范"JSONB 慎用"原则。

### 决策点 2：FK 字段类型 — `global_variable_code` VARCHAR(64) 而非 UUID

**选择**：`template_global_variable_binding.global_variable_code VARCHAR(64) REFERENCES global_variable_definition(code)`。

**强制依据**：V104 schema 中 `global_variable_definition` 主键就是 `code VARCHAR(64) PRIMARY KEY`（业务编码），**不存在 UUID 主键列**。PM 在 PRD 早期草稿写的 `gv_id UUID FK → global_variable_definition.id` 是事实错误，本 ADR 修正为 `global_variable_code` 与真实 schema 对齐。

**额外收益**：JSONB 快照内放 `code='ELEM_PRICE'` 而非 UUID，人类可读、跨环境对账无需 join。

### 决策点 3：快照存储位置 — `quotation.bound_global_variables_snapshot` 独立 JSONB 列

**选择**：在 `quotation` 表新增 `bound_global_variables_snapshot JSONB NOT NULL DEFAULT '[]'`，**不**并入 V54 的 `submission_snapshot` JSONB 内。

**取舍**：
| 方案 | 优点 | 缺点 |
|---|---|---|
| ✅ 独立列 | 列粒度可控 / 回滚 DROP COLUMN 不污染 V54 / 前端 payload 精准 | quotation 表多一列 |
| ❌ 合并进 submission_snapshot.boundGlobalVariables 子键 | 列数不增 | 改 V54 既有结构 / SnapshotCollector 要同时维护两类内容 / 回滚困难 |
| ❌ 新建 `quotation_submission_snapshot` 表 | 表语义清晰 | **该表实际不存在**（PRD 早期草稿误写）；新建会与 V54 的列式快照冲突 |

**理由**：保持与 V54 同模式（"快照=quotation 表上的 JSONB 列"），最小破坏 + 最易回滚。

### 决策点 4：渲染路径完全隔离，不复用 driver / enrich

**选择**：新增 `GlobalVariableDataLoader` 服务做"全表读取 → 序列化为 rows[]"，**不**复用：
- ❌ `ComponentDriverService.expand`（涉及 lineItemId / hf_part_no / RuntimeContext，污染 GV 全表语义）
- ❌ `useDriverExpansions` hook（涉及 fingerprint / driverExpansionKey，全表无 lineItem 维度）
- ❌ `enrichComponentData`（属于 template snapshot 渲染链路，与 GV 无关）

**理由**：
1. GV 全表渲染**没有任何"按 lineItem 过滤""按谓词约束""按 key 取单值"的需求** — 它就是 `SELECT * FROM v_costing_element_price LIMIT 100` 这种最朴素的查询。
2. 三大核心基线明确禁止任何破坏组件/模板/报价渲染基线的改动。复用 driver 链路 = 把 GV 数据强塞进现有 enrich-pipeline，必然触发 AP-37 / AP-44 字段类型协议传播灾难。

### 决策点 5：懒加载 + 状态分流

**选择**：
- 用户切到「引用数据」Tab 才触发请求（懒加载）
- DRAFT 报价单 → `GET /quotations/{qid}/ref-data` 实时拉 `source_view`
- 非 DRAFT 报价单（SUBMITTED / APPROVED / REJECTED）→ `GET /quotations/{qid}/ref-data/snapshot` 读 JSONB

**理由**：
- DRAFT 阶段数据可变，必须实时；提交后必须冻结（审计要求）
- 懒加载避免给报价单详情页加预拉取负担
- 状态分流由后端校验（防越权：DRAFT 不许走 snapshot 端点，否则拿不到数据；SUBMITTED 不许走实时端点，否则审计失效）

---

## 3. 数据模型（完整 SQL DDL）

### 3.1 `template_global_variable_binding`

```sql
CREATE TABLE template_global_variable_binding (
    id                     UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    template_id            UUID         NOT NULL
        REFERENCES template(id) ON DELETE CASCADE,
    global_variable_code   VARCHAR(64)  NOT NULL
        REFERENCES global_variable_definition(code) ON DELETE RESTRICT,
    display_order          INT          NOT NULL DEFAULT 0,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_tgvb_template_code UNIQUE (template_id, global_variable_code)
);

CREATE INDEX idx_tgvb_template_order
    ON template_global_variable_binding (template_id, display_order);

CREATE INDEX idx_tgvb_global_variable_code
    ON template_global_variable_binding (global_variable_code);
```

**FK 行为**：
- `template_id → template.id ON DELETE CASCADE` — 模板被删，绑定自动清
- `global_variable_code → global_variable_definition.code ON DELETE RESTRICT` — 想删 GV 必须先解绑（保护既有模板）

**唯一约束**：`(template_id, global_variable_code)` — 一个模板不能重复绑定同一个 GV

### 3.2 `quotation.bound_global_variables_snapshot`

```sql
ALTER TABLE quotation
    ADD COLUMN bound_global_variables_snapshot JSONB NOT NULL DEFAULT '[]'::jsonb;
```

**JSONB 结构**：

```json
[
  {
    "code": "ELEM_PRICE",
    "name": "元素核价价格",
    "varType": "LOOKUP_TABLE",
    "unit": "CNY/KG",
    "displayOrder": 0,
    "snapshotAt": "2026-05-21T10:30:00Z",
    "rows": [
      { "element_code": "Cu", "costing_price": 75000 },
      { "element_code": "Ag", "costing_price": 8500 }
    ]
  }
]
```

**索引策略**：不加 GIN 索引（不存在跨行按 JSONB 字段过滤的查询路径，只按 `quotation_id` 直读整个 JSONB，加索引纯写放大）。

---

## 4. API 契约

### 4.1 端点总表

| # | 方法 | 路径 | Request | Response | 错误码 |
|---|---|---|---|---|---|
| 1 | GET | `/api/cpq/templates/{tid}/global-variable-bindings` | - | `List<TemplateGvBindingDTO>` | 404 模板不存在 |
| 2 | PUT | `/api/cpq/templates/{tid}/global-variable-bindings` | `UpdateBindingsRequest` | `List<TemplateGvBindingDTO>` | 403 非 DRAFT / 400 code 不存在或已停用 / 404 模板不存在 |
| 3 | GET | `/api/cpq/quotations/{qid}/ref-data` | - | `List<BoundGvViewDTO>` | 404 报价单不存在 / 400 报价单非 DRAFT |
| 4 | GET | `/api/cpq/quotations/{qid}/ref-data/snapshot` | - | `List<BoundGvSnapshotItem>`（空 = `[]`） | 404 报价单不存在 / 400 报价单为 DRAFT |
| 5 | GET | `/api/cpq/global-variable-definitions?activeOnly=true` | - | `List<GlobalVariableDefinitionDTO>` | — （复用 `GlobalVariableService.listAll()`） |

### 4.2 DTO JSON Shape

#### `TemplateGvBindingDTO`（端点 1、2 响应元素）
```json
{
  "id": "uuid",
  "templateId": "uuid",
  "globalVariableCode": "ELEM_PRICE",
  "globalVariableName": "元素核价价格",
  "varType": "LOOKUP_TABLE",
  "unit": "CNY/KG",
  "isActive": true,
  "displayOrder": 0,
  "createdAt": "2026-05-21T08:00:00Z"
}
```
> `globalVariableName / varType / unit / isActive` 是 join `global_variable_definition` 的回填字段，方便前端绑定列表直接渲染徽章。

#### `UpdateBindingsRequest`（端点 2 请求）
```json
{
  "bindings": [
    { "globalVariableCode": "ELEM_PRICE", "displayOrder": 0 },
    { "globalVariableCode": "MAT_PRICE",  "displayOrder": 1 }
  ]
}
```
> 全量替换语义（PUT）。后端做 diff：新增、删除、order 变更分别处理；事务保证原子性。

#### `BoundGvViewDTO`（端点 3 响应元素 — DRAFT 实时数据）
```json
{
  "code": "ELEM_PRICE",
  "name": "元素核价价格",
  "varType": "LOOKUP_TABLE",
  "unit": "CNY/KG",
  "displayOrder": 0,
  "fetchedAt": "2026-05-21T10:30:00Z",
  "columns": ["element_code", "costing_price"],
  "rows": [
    { "element_code": "Cu", "costing_price": 75000 },
    { "element_code": "Ag", "costing_price": 8500 }
  ]
}
```
> `columns` 显式给出列顺序（取 `key_columns + [value_column]`），前端不依赖 JSON key 顺序。

#### `BoundGvSnapshotItem`（端点 4 响应元素 — 非 DRAFT 快照）
JSON shape 与 `BoundGvViewDTO` **完全一致**，仅来源不同：直接从 `quotation.bound_global_variables_snapshot` 反序列化返回。`fetchedAt` 字段对应 JSONB 中的 `snapshotAt`。

> 前端可用同一 React 组件渲染两种来源（仅 fetch URL 不同），降低维护成本。

### 4.3 错误码规范

| HTTP | businessCode | 场景 |
|---|---|---|
| 400 | `INVALID_GV_CODE` | PUT 时 `globalVariableCode` 在 `global_variable_definition` 中不存在 |
| 400 | `INACTIVE_GV_CODE` | PUT 时绑定到 `is_active = false` 的 GV |
| 400 | `QUOTATION_NOT_DRAFT` | GET `/ref-data` 时报价单已非 DRAFT |
| 400 | `QUOTATION_IS_DRAFT` | GET `/ref-data/snapshot` 时报价单为 DRAFT（无快照） |
| 403 | `TEMPLATE_NOT_DRAFT` | PUT 时模板状态 ≠ DRAFT |
| 404 | `TEMPLATE_NOT_FOUND` | 模板 tid 不存在 |
| 404 | `QUOTATION_NOT_FOUND` | 报价单 qid 不存在 |
| 422 | `TOO_MANY_BINDINGS` | PUT 时 bindings.length > 20（软上限，纯警告，仍可接受） |

---

## 5. 模块边界

### 5.1 新增（本 ADR 范围）

| 路径 | 内容 |
|---|---|
| `cpq-backend/src/main/resources/db/migration/V212__template_global_variable_binding.sql` | 关联表 + quotation 加列 |
| `cpq-backend/src/main/java/com/cpq/template/entity/TemplateGlobalVariableBinding.java` | Panache entity |
| `cpq-backend/src/main/java/com/cpq/template/service/TemplateGlobalVariableBindingService.java` | 绑定 CRUD 服务 |
| `cpq-backend/src/main/java/com/cpq/template/resource/TemplateGlobalVariableBindingResource.java` | 端点 1、2 |
| `cpq-backend/src/main/java/com/cpq/template/dto/TemplateGvBindingDTO.java` | 响应 DTO |
| `cpq-backend/src/main/java/com/cpq/template/dto/UpdateBindingsRequest.java` | 请求 DTO |
| `cpq-backend/src/main/java/com/cpq/quotation/refdata/GlobalVariableDataLoader.java` | 全表读取服务（独立） |
| `cpq-backend/src/main/java/com/cpq/quotation/refdata/QuotationRefDataResource.java` | 端点 3、4 |
| `cpq-backend/src/main/java/com/cpq/quotation/refdata/BoundGvViewDTO.java` | 响应 DTO |
| `cpq-backend/src/main/java/com/cpq/quotation/refdata/BoundGvSnapshotItem.java` | 响应 + JSONB POJO |
| `cpq-frontend/src/pages/template/GvBindingPanel.tsx` | 模板编辑抽屉新增区块 |
| `cpq-frontend/src/pages/quotation/RefDataTab.tsx` | 报价单详情新增 Tab |
| `cpq-frontend/src/services/templateGvBindingService.ts` | 前端 API client |

### 5.2 修改（最小侵入）

| 文件 | 修改点 |
|---|---|
| `cpq-backend/src/main/java/com/cpq/template/service/TemplateService.java` | `createNewDraft()` 末尾追加：复制 `template_global_variable_binding` 到新草稿（保持 `display_order`），其他逻辑不动 |
| `cpq-backend/src/main/java/com/cpq/quotation/snapshot/SnapshotCollectorService.java` | `collect()` 末尾追加：调用 `GlobalVariableDataLoader` 序列化到 `quotation.bound_global_variables_snapshot`，不动其他快照内容 |
| `cpq-frontend/src/pages/template/TemplateEditDrawer.tsx` | 注入 `<GvBindingPanel/>` 区块（在「基础信息」和「组件区」之间） |
| `cpq-frontend/src/pages/quotation/QuotationDetailTabs.tsx` | 注入 `<RefDataTab/>`（条件渲染：模板有绑定 ≥1 才显示 Tab） |

### 5.3 不许碰（红线，后端/前端 agent 绝对禁止修改）

| 文件 / 字段 | 禁止理由 |
|---|---|
| `cpq-backend/src/main/java/com/cpq/component/service/ComponentDriverService.java`（除 `evictAll` 外） | 三大基线核心，AP-31/AP-37/AP-44 教训源 |
| `cpq-frontend/src/pages/quotation/useDriverExpansions.ts` | 6 维 cache key 协议，改一处全链路重测 E2E |
| `cpq-frontend/src/pages/quotation/usePathFormulaCache.ts` | 同上 |
| `cpq-frontend/src/pages/quotation/QuotationStep2.tsx` 已有的字段类型渲染分支 | AP-44 17 处协议传播点 |
| `cpq-frontend/src/pages/quotation/ReadonlyProductCard.tsx` | 详情 vs 编辑同源 (AP-41) |
| `cpq-backend/src/main/java/com/cpq/formula/dataloader/ImplicitJoinRewriter.java` | 红线 2，禁止新增隐式硬规则 |
| `cpq-backend/src/main/java/com/cpq/template/entity/Template.java` 的 `componentsSnapshot` 字段及 schema | 三大基线红线 4，单一来源 |
| V104 `global_variable_definition` 表 schema 的任何列 | 跨模块共享，本 ADR 仅消费不改写 |
| `cpq-backend/src/main/resources/db/migration/V202__expand_composite_child_views_union_simple.sql` 及任何 `v_composite_child_*` 视图 | 三大基线红线 1，禁止改视图自适应分支 |

---

## 6. 反模式隔离自检

### 6.1 不会触发 AP-31「加载中永久占位族」
**理由**：本功能渲染路径**完全不走** `useDriverExpansions` / fingerprint / driverExpansionKey / pre-enrich 缓存。「引用数据」Tab 是独立 React 组件，自管 loading 状态（`useState<'loading'|'success'|'error'>`），fetch 直接调 `/api/cpq/quotations/{qid}/ref-data`，没有任何"先 enrich 再渲染"的两阶段链路，因此不存在 fingerprint 漏维度 / EMPTY_EXPANSION 缓存污染 / invalidate 漏调的可能性。

### 6.2 不会触发 AP-44「字段类型协议传播 17 处」
**理由**：本功能**不引入任何新的 `field_type` 枚举**，不修改 `VALID_FIELD_TYPES`，不动 `normalizeFieldType` / `computeAllFormulas` / `parseBasicDataPaths`。全局变量值在「引用数据」Tab 内是**整行裸数据展示**（直接渲染 `row.element_code`、`row.costing_price`），不需要走组件字段渲染分发器。

### 6.3 不会触发 AP-37「同 componentId 多实例 cache 冲突」
**理由**：本功能**不进入** ProductCard / 组件渲染链路。引用数据 Tab 是与 ProductCard 平级的独立 Tab（兄弟节点而非子节点），没有 componentId 概念、没有 cache key 协议、没有 driverExpansionKey 维度计算。同一报价单的所有引用数据由 `qid` 单一维度索引。

---

## 7. 变更影响评估

| 子系统 | 影响等级 | 说明 |
|---|---|---|
| 组件管理 | **零影响** | 不动 component 表、不改字段类型矩阵、不动 `component.fields` 渲染 |
| 模板管理 | **低** | 新增关联表 + `TemplateService.createNewDraft()` 末尾追加 10 行拷贝逻辑；`componentsSnapshot` 完全不动 |
| 报价单编辑（Step1-Step5 wizard） | **零影响** | wizard 不展示引用数据，本功能只挂在详情页 |
| 报价单详情 Tab 体系 | **低** | 新增条件渲染 Tab；不动「报价单信息」「数据来源」「核价单」「审批记录」 |
| 报价单提交（SnapshotCollector） | **低** | `collect()` 末尾追加一段；不动 V54 既有 `submission_snapshot` 字段或其内容 |
| 公式引擎 / driver 链路 | **零影响** | 不调用 `ComponentDriverService` / `useDriverExpansions`，不注入 RuntimeContext |
| 全局变量服务 | **零影响** | 只读复用 `GlobalVariableService.listAll() / getByCode()`；不改写、不扩展 |
| ImplicitJoinRewriter / 路径求值 | **零影响** | 不引入新 BNF path，不动 hf_part_no 谓词注入 |
| Excel 模板 / 核价单 | **零影响** | 本功能限定 `template_kind ∈ {QUOTATION, COSTING}`，Excel 模板隐藏入口 |
| E2E 测试 | **低** | 不影响 `quotation-flow.spec.ts` / `composite-product-flow.spec.ts` / `multi-product-flow.spec.ts`；建议为本功能新增独立 spec `gv-binding-flow.spec.ts`（不强制） |

---

## 8. 回滚策略

### 8.1 Flyway 回滚 SQL（手动执行）

```sql
-- 1. 删 quotation 列（数据丢失：所有 SUBMITTED+ 报价的引用数据快照）
ALTER TABLE quotation DROP COLUMN IF EXISTS bound_global_variables_snapshot;

-- 2. 删关联表（FK CASCADE 自动清行）
DROP TABLE IF EXISTS template_global_variable_binding;

-- 3. flyway_schema_history 标记回滚
DELETE FROM flyway_schema_history WHERE version = '212';
```

### 8.2 JSONB 列回填 NULL 策略（如不回滚 DDL 仅停用功能）

```sql
-- 软停用：清空所有快照（保留列结构，方便后续重启用）
UPDATE quotation SET bound_global_variables_snapshot = '[]'::jsonb;

-- 前端隐藏 Tab：通过 feature flag 控制 RefDataTab 渲染
```

### 8.3 应用层回滚

- 删除新增的 Resource / Service / Entity 类（编译时即可发现孤儿依赖）
- 移除 `SnapshotCollectorService.collect()` 末尾追加段
- 移除 `TemplateService.createNewDraft()` 末尾追加段
- 前端移除 `<GvBindingPanel/>` 和 `<RefDataTab/>` 引用

### 8.4 数据一致性

- 回滚前若已有 SUBMITTED 报价单的 `bound_global_variables_snapshot` 有数据，需先导出（业务方可能要求保留审计快照），导出后再 DROP COLUMN
- 回滚后历史 PUBLISHED 模板的绑定关系丢失，需业务方接受（属于功能撤销，不属于数据损坏）

---

## 9. Java DTO / Entity 骨架（仅签名，不含实现）

### 9.1 `TemplateGlobalVariableBinding`（Panache entity）

```java
@Entity
@Table(name = "template_global_variable_binding")
public class TemplateGlobalVariableBinding extends PanacheEntityBase {
    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "template_id", nullable = false)
    public UUID templateId;

    @Column(name = "global_variable_code", nullable = false, length = 64)
    public String globalVariableCode;  // FK → global_variable_definition.code (VARCHAR(64))

    @Column(name = "display_order", nullable = false)
    public int displayOrder = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    public OffsetDateTime createdAt;
}
```

### 9.2 `TemplateGvBindingDTO`（响应）

```java
public class TemplateGvBindingDTO {
    public UUID id;
    public UUID templateId;
    public String globalVariableCode;     // V104 主键
    public String globalVariableName;     // join 回填: global_variable_definition.name
    public String varType;                // LOOKUP_TABLE / SCALAR
    public String unit;                   // join 回填
    public boolean isActive;              // join 回填: global_variable_definition.is_active
    public int displayOrder;
    public OffsetDateTime createdAt;
}
```

### 9.3 `UpdateBindingsRequest`（请求）

```java
public class UpdateBindingsRequest {
    public List<BindingItem> bindings;

    public static class BindingItem {
        public String globalVariableCode;  // 不是 gvId
        public int displayOrder;
    }
}
```

### 9.4 `BoundGvViewDTO`（DRAFT 实时数据）

```java
public class BoundGvViewDTO {
    public String code;
    public String name;
    public String varType;
    public String unit;
    public int displayOrder;
    public OffsetDateTime fetchedAt;
    public List<String> columns;           // 列顺序: key_columns + [value_column]
    public List<Map<String, Object>> rows; // 整表行
}
```

### 9.5 `BoundGvSnapshotItem`（JSONB 内单元素 + 端点 4 响应元素）

```java
public class BoundGvSnapshotItem {
    public String code;
    public String name;
    public String varType;
    public String unit;
    public int displayOrder;
    public OffsetDateTime snapshotAt;      // JSON shape 与 BoundGvViewDTO.fetchedAt 同义
    public List<String> columns;
    public List<Map<String, Object>> rows;
}
```

---

## 10. 决策点重申（与编排器锁定值对齐）

1. **关联表方案**：`template_global_variable_binding` 独立 M:N 关联表 + FK 引用 V104 真实主键 `code VARCHAR(64)`，非 UUID
2. **快照存储位置**：`quotation.bound_global_variables_snapshot` 独立 JSONB 列（不污染 V54 `submission_snapshot`）
3. **渲染路径隔离**：新建 `GlobalVariableDataLoader` 独立服务，**不复用** `ComponentDriverService` / `useDriverExpansions` / `enrichComponentData`
4. **DRAFT vs 非 DRAFT 状态分流**：DRAFT 实时拉源视图 / 非 DRAFT 读 JSONB 快照；后端按 quotation.status 校验防越权
5. **createNewDraft 拷贝**：复制绑定关系到新 DRAFT，`display_order` 保持原值

---

**最后修订**：2026-05-21  
**状态**：Accepted，等待 P2 后端 agent 按本 ADR + V212 SQL 实施
