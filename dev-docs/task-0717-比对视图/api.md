# 报价单比对视图 · 接口文档（api.md）

> 版本：v1（2026-07-18 技术总监定稿）
> 关联：`需求说明.md §11`、`fronttask.md`、`backtask.md`
> 基线原型：`prototype-比对视图.html`（连线配置版，前端 1:1 还原基准）

---

## 0. 设计总纲

### 0.1 职责划分
- **后端**：① 提供两侧「页签 → 可比对值」目录（meta）；② 提供逐销售料号、两侧、逐页签的**取值矩阵**（data，复用渲染 报价单/核价单 Tab 的同一套口径，禁止另起取值路径 → 防 AP-50 双源漂移）；③ 持久化「比对列配置」（config，按 报价单×桶 存）。
- **前端**：组装列、算差异、着色、排序、过滤、分页；连线配置抽屉；配置读写。**前端不新增任何取值 SQL/公式**，只消费 data 矩阵。

### 0.2 桶（bucket）
- `SALES`：报价单编辑页 / 报价单详情页入口（销售）
- `FINANCE`：核价单页面 / 核价单详情页入口（财务）
- 桶由**入口页面**决定并作为查询参数显式传入，**不看**登录用户真实角色（见 §11.E）。

### 0.3 通用约定
- Base path：`/api/cpq/quotations/{id}`（`id` = 报价单 quotationId，UUID）。
- 统一响应信封 `ApiResponse<T>`：`{ code, message, data }`（沿用现有约定；本文 `data` 列出 T 的结构）。
- 鉴权：`@RoleAllowed({"SALES_REP","SALES_MANAGER","PRICING_MANAGER","SYSTEM_ADMIN"})`（与现有 `CostingSheetResource` 一致）。config 的 PUT 亦同一组角色（桶按入口区分，不按角色鉴权）。
- 数值精度（对齐 `docs/小数显示口径`）：`字段小计 / 页签合计` = 4 位；`产品卡片总计` = 2 位；差异值按其所在列的操作数口径（产品总计列 2 位、页签列 4 位）。后端 data 返回**原始数值**（不预格式化），精度由前端按上述口径展示。

### 0.4 与旧比对端点的关系
- 旧端点 `GET /{id}/comparison`（`buildComparison` → tag 分组模型）与 `POST /{id}/comparison/export` **保留不动、不再被新视图调用**（见 §11.F：本期不做导出）。新功能一律走本文 `/{id}/comparison-view/*` 新路径，互不影响。

---

## 1. GET `/{id}/comparison-view/meta` — 页签·可比对值目录（连线抽屉数据源）

供「新增比对（连线配置）」抽屉左右两列渲染。与 bucket 无关（两侧目录对所有桶一致）。

### 请求
```
GET /api/cpq/quotations/{id}/comparison-view/meta
```

### 响应 `data: ComparisonMetaDTO`
```jsonc
{
  "quoteTabs": [
    {
      "componentId": "uuid",          // 页签(组件)ID，作为列配置里的稳定引用键
      "tabName": "投料",              // 页签显示名
      "sortOrder": 0,
      "metrics": [                     // 该页签下的可比对值节点
        { "key": "material_subtotal", "label": "材料小计", "type": "SUBTOTAL_FIELD" },
        { "key": "aux_subtotal",      "label": "辅料小计", "type": "SUBTOTAL_FIELD" },
        { "key": "__TAB_TOTAL__",     "label": "页签合计", "type": "TAB_TOTAL" }
      ]
    }
    // …报价侧其余页签
  ],
  "costingTabs": [ /* 结构同 quoteTabs，核价侧页签 */ ]
}
```

### 语义
- `metrics` 中 `SUBTOTAL_FIELD` = 该页签内 `is_subtotal=true` 的字段（`key`=字段 `name`，`label`=字段 `label`）；末尾固定追加一条 `__TAB_TOTAL__`（页签合计）。
- 目录取自该报价单两侧卡片结构快照（`quoteCardStructure` / `costingCardStructure`）。**一张单一套模板 → 页签集统一**（§11.B），故与具体料号无关。
- 若某侧无模板/无页签 → 该侧数组为空（前端连线抽屉对应列显示空态）。

---

## 2. GET `/{id}/comparison-view/data` — 取值矩阵（逐料号 × 两侧 × 逐页签）

一次性返回**所有页签所有小计**的值；前端按已配置列从中取数即可，**新增/删除列无需重新请求 data**（除非底层业务数据变化）。

### 请求
```
GET /api/cpq/quotations/{id}/comparison-view/data[?frozen=true]
```
- `frozen`（可选，默认 false）：`true` = 读冻结快照口径（详情页 / 已提交核价单用）；`false` = 读当前有效值口径（编辑态用）。后端据此选择取值来源，但**两种口径都必须与对应场景下 报价单/核价单 Tab 展示的值逐值一致**。

### 响应 `data: ComparisonDataDTO`
```jsonc
{
  "rows": [
    {
      "partNo": "3120018220",         // 销售料号（比对连接键）
      "productName": "…",             // 可选，展示用
      "presence": "BOTH",             // BOTH | QUOTE_ONLY | COSTING_ONLY
      "quote": {                       // 报价侧取值；presence=COSTING_ONLY 时为 null
        "productTotal": 15500.00,      // 产品卡片总计（独立公式值，2 位口径）
        "tabs": {
          "<componentId>": {
            "tabTotal": 8000.0000,     // 页签合计（4 位口径）
            "subtotals": { "material_subtotal": 8000.0000, "aux_subtotal": 0.0000 }
          }
          // …该侧其余页签
        }
      },
      "costing": { /* 结构同 quote；presence=QUOTE_ONLY 时为 null */ }
    }
    // …其余销售料号
  ]
}
```

### 语义 / 计算口径（后端务必遵守）
- **料号并集**：`rows` = 报价侧 line item 料号 ∪ 核价侧 line item 料号，`presence` 标记单双边；`quote`/`costing` 缺侧为 `null`（前端据此整行变灰、差异"—"）。
- **productTotal** = 该料号产品卡片总计（复用现有产品卡片总计/折扣基数取值路径，如 `LineDiscountService.discountBaseOf` / SUBTOTAL 组件求值），**不是**各页签小计加总。
- **tabs[componentId].subtotals[fieldName]** = 该页签内该 `is_subtotal` 字段的列小计；**tabTotal** = 该页签所有 `is_subtotal` 列之和（口径同 `computeTabSubtotalsByColumn` / `ComponentDataEffectiveRows.compute`）。
- **单一数据源**：以上三类值必须复用**渲染报价单/核价单 Tab 的同一后端服务**（`CardSnapshotService` / `ComponentDataEffectiveRows` / `FormulaCalculator` / `LineDiscountService`），严禁新写 SQL/公式旁路（AP-50）。
- 缺失值（某页签无数据/某字段不存在）→ 该项省略或返回 `null`，前端显示"—"、不参与红/橙判定。

---

## 3. GET `/{id}/comparison-view/config` — 读取比对列配置（按桶）

### 请求
```
GET /api/cpq/quotations/{id}/comparison-view/config?bucket=SALES|FINANCE
```

### 响应 `data: ComparisonConfigDTO`
```jsonc
{
  "quotationId": "uuid",
  "bucket": "SALES",
  "columns": [ /* ColumnDef[]，见 §5；从未保存过 → 返回 null */ ]
}
```
- `columns=null` 表示该（报价单×桶）从未保存过配置 → 前端自行**种入默认列**（产品卡片总计，见 §5.1），不落库直到用户首次保存。

---

## 4. PUT `/{id}/comparison-view/config` — 保存比对列配置（按桶，全量覆盖 upsert）

一次提交**整个 columns 数组**（新增/删除/改阈值/改顺序都通过整体保存；后端不做增量语义）。

### 请求
```
PUT /api/cpq/quotations/{id}/comparison-view/config?bucket=SALES|FINANCE
Content-Type: application/json

{ "columns": [ /* ColumnDef[]，见 §5 */ ] }
```

### 行为
- 按 `(quotationId, bucket)` 唯一键 **upsert**：存在则覆盖 `columns` + 刷新 `updatedAt`，不存在则插入。
- 后端**只做存储**，不解释/校验列内容（componentId/metric 是否仍有效由前端在读取时对照 meta 处理）。可选做轻量结构校验（columns 为数组、每项含必填键）。

### 响应 `data: ComparisonConfigDTO`（同 §3，回显已保存的 columns）

### 详情页只读约束
- 详情页入口**不得**调用本 PUT（前端不渲染新增/删除/改阈值控件）；后端不额外拦截（按角色已放行），只读靠前端保证。

---

## 5. 数据结构：ColumnDef（比对列定义，存于 config.columns JSONB）

```jsonc
{
  "id": "col-uuid-or-clientseq",       // 列唯一 id（前端生成，删除/定位用）
  "kind": "PRODUCT_TOTAL | TAB_PAIR",  // 列类型
  "sortOrder": 0,                       // 列顺序（追加到末尾 = 递增）
  "threshold": 0,                       // 差异阈值（每列可配，默认 0）

  // kind=TAB_PAIR 时必填（连线配置产物）：
  "quoteComponentId": "uuid",          // 报价侧页签
  "quoteMetric": "material_subtotal",  // 报价侧比对值：字段名 | "__TAB_TOTAL__"
  "quoteLabel": "投料·材料小计",        // 冗余存展示名（模板漂移时兜底渲染）
  "costingComponentId": "uuid",        // 核价侧页签
  "costingMetric": "__TAB_TOTAL__",    // 核价侧比对值：字段名 | "__TAB_TOTAL__"
  "costingLabel": "投料成本·页签合计"
  // kind=PRODUCT_TOTAL 时上述 quote*/costing* 省略，metric 语义为产品卡片总计
}
```

### 5.1 默认列（PRODUCT_TOTAL）
- 语义 = 产品卡片总计（报价 vs 核价），取 `data.rows[].quote.productTotal` / `costing.productTotal`。
- **永远存在、不可删**（§11.B）；`threshold` 可改（默认 0）。
- 约定：列表中恒为**第一列**，`sortOrder` 最小；若 config.columns 里没有 PRODUCT_TOTAL 项，前端渲染时在最前**自动补齐**一条（阈值 0）。保存时应包含它（携带其 threshold 以持久化阈值）。

### 5.2 用户列（TAB_PAIR）
- 由连线抽屉每条配对生成一条；按连线顺序 `sortOrder` 递增、追加到末尾。
- 允许「一对多」：同一 `quoteComponentId+quoteMetric` 可出现在多条不同 TAB_PAIR 中（各配不同核价侧），反之亦然。
- 取值：`quote` 值 = `data.rows[].quote.tabs[quoteComponentId].subtotals[quoteMetric]`（`quoteMetric==='__TAB_TOTAL__'` 时取 `tabTotal`）；`costing` 侧同理。

---

## 6. 前端着色 / 差异（客户端计算，非接口字段，列此备忘对齐）

- 差异值 `diff = quoteValue − costingValue`（两侧任一为 null → "—"，不判定颜色）。
- `diff < 0` → 🔴 红（全局固定红线，优先）；否则 `diff < column.threshold` → 🟠 橙；否则无色。整格填色，标在差异行差异格（§11.C/§11.D）。
- 单边料号（presence≠BOTH）：缺失侧数据行变灰、差异"—"，且计为差异料号（前置优先级最高）。

---

## 7. 持久化表：`quotation_comparison_config`（Flyway 新增迁移）

```sql
CREATE TABLE quotation_comparison_config (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    quotation_id  UUID        NOT NULL,
    bucket        VARCHAR(16) NOT NULL,          -- SALES | FINANCE
    columns       JSONB       NOT NULL DEFAULT '[]'::jsonb,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_qcc_quotation_bucket UNIQUE (quotation_id, bucket)
);
CREATE INDEX idx_qcc_quotation ON quotation_comparison_config (quotation_id);
```
- 迁移版本号在开发时取当前 `db/migration/` 最大号 +1（**勿手工 psql，交 Quarkus 启动 Flyway 自动跑**，见 CLAUDE.md「修改后强制自检」）。
- 存量数据不迁移（§6 需求：新增功能）。

---

## 8. 端点清单速览

| 方法 | 路径 | 用途 | 桶 | 详情页 |
|------|------|------|----|--------|
| GET | `/{id}/comparison-view/meta` | 连线抽屉页签·比对值目录 | 无关 | ✅ 可调（但抽屉不显示） |
| GET | `/{id}/comparison-view/data[?frozen=]` | 逐料号两侧取值矩阵 | 无关 | ✅ 只读用 frozen=true |
| GET | `/{id}/comparison-view/config?bucket=` | 读该桶比对列配置 | 必填 | ✅ |
| PUT | `/{id}/comparison-view/config?bucket=` | 全量保存该桶配置 | 必填 | ❌ 前端不调用 |

> 旧端点 `GET /{id}/comparison`、`POST /{id}/comparison/export` 保留不动、不在本功能调用范围内。
