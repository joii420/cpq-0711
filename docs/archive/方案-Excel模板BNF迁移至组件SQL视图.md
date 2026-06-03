# 方案：Excel 模板独立 SQL 视图（与组件视图同构 + 完全隔离）

> 立项日期：2026-05-26
> 设计版本：**v2（独立 SQL 视图）— 取代 v1 GLOBAL 借用方案**
> 触发来源：
> 1. AP-53 严格 BNF 通道规则（BNF path 必须走"组件配置的 SQL 视图"，禁止 PG 视图直引）
> 2. 用户要求："模板的 Excel 视图中可以与组件一样配置 SQL 视图进行引用，与组件的视图互相隔离"
>
> 前置必读：`docs/方案制定前必读.md`、`docs/反模式.md` AP-44 / AP-53、`docs/组件级数据源SQL方案.md`、`docs/Excel模板配置指南.md`、`docs/三大核心模块基线.md`

---

## 一、设计核心（一句话）

> **组件 → 组件 SQL 视图（component_sql_view）；Excel 模板 → 模板 SQL 视图（costing_template_sql_view）。两张表完全独立，引用语法都是 `$<view>.<col>`，靠请求线程的 owner 上下文决定查哪张表。**

### 1.1 与 v1 GLOBAL 借用方案的对比

| 维度 | v1（已废弃） | **v2（本方案）** |
|------|-------------|-----------------|
| Excel 模板用 SQL 视图的方式 | 借组件的 GLOBAL 视图（`$$code.view.col`） | 自己拥有的视图（`$view.col`） |
| 视图归宿 | 收纳在 `COMP-CFG-EXCEL-SHARED` 虚拟组件 | 直接挂在 `costing_template` 下 |
| 命名空间隔离 | 与组件共用一个 `component_sql_view` 表 | 独立表 `costing_template_sql_view` |
| 跨 Excel 模板复用 | 通过 GLOBAL 视图天然支持 | 不支持（隔离 = 不能跨）；如需复用则各自复制一份 |
| 与组件 SQL 视图互引 | 可（通过 `$$`）| **完全禁止** |
| 配置体验 | Excel 模板 → PathPicker → 跨组件浏览 | Excel 模板编辑页加"SQL 视图" Tab，与组件管理同构 |

### 1.2 隔离边界的强约束

1. Excel 模板的 `$<view>` 路径**只能**解析到本模板拥有的 `costing_template_sql_view` 行
2. 组件的 `$<view>` 路径**只能**解析到本组件拥有的 `component_sql_view` 行
3. **不允许**任何 `$$<...>` 跨 owner 引用形态在 Excel 模板路径中出现
4. **不允许**组件视图引用 Excel 模板视图（反向也不可）
5. 实现机制：`SqlViewRuntimeContext` 同时刻只能有一个 owner（`componentId` 或 `costingTemplateId`），二者互斥；`SqlViewExecutor` 根据 owner 选查表

---

## 二、当前现状再确认（v1 调查复用）

### 2.1 三种 BNF 路径形态今天的支持

| # | 形态 | 走的链路 | Excel 模板能用否 | 备注 |
|---|------|---------|-----------------|------|
| ① | `v_xxx.col` / `mat_xxx.col`（PG 直引）| `ImplicitJoinRewriter` | ✅ 但属于负债 | AP-53 治理目标，逐步退役 |
| ② | `$view.col`（本 owner）| `SqlViewExecutor.execute` → `lookupForResolver(ownerId, viewName)` | ❌ 今天 owner=componentId，Excel 没"当前组件" | **本方案要打通：扩展 owner 概念** |
| ③ | `$$code.view.col`（跨组件 GLOBAL）| `SqlViewExecutor.execute` → `lookupForResolver(isCross=true, componentCode)` | ⚠️ 链路通但破坏隔离 | **本方案禁用于 Excel 上下文** |
| ④ | `$view`（driver 整行）| `SqlViewExecutor.executeAllRows` | ❌ Excel 列是单值，不需要 | 维持现状 |

### 2.2 现有 5 个关键文件状态

| 文件 | 当前职责 | 本方案是否要改 |
|------|---------|---------------|
| `cpq-backend/.../entity/ComponentSqlView.java` | 组件 SQL 视图实体 | ✘ 不动（沿用） |
| `cpq-backend/.../service/ComponentSqlViewService.java` | `lookupForResolver` 三层 fallback | ✓ 改造为 owner-aware（按 ownerType 路由）|
| `cpq-backend/.../datasource/sqlview/SqlViewExecutor.java` | 解析 `$/$$` 路径并执行 | ✓ 改造 `execute / executeAllRows` 按 owner 选查表 |
| `cpq-backend/.../datasource/sqlview/SqlViewRuntimeContext.java` | ThreadLocal 上下文 | ✓ 加 `costingTemplateId` + owner 类型字段 |
| `cpq-backend/.../formula/resource/FormulaEvaluateResource.java` | `/formulas/(batch-)?evaluate` 端点 | ✓ 接 ThreadLocal（接收 `costingTemplateId`）|
| `cpq-frontend/.../pages/component/PathPickerDrawer.tsx` | BNF 路径选择 Drawer | ✓ 增加 owner 隔离行为 |
| `cpq-frontend/.../pages/costing/CostingTemplateConfig.tsx` | Excel 模板列编辑页 | ✓ 增 "SQL 视图" Tab + PathPicker owner=costingTemplate |
| `cpq-frontend/.../pages/quotation/LinkedExcelView.tsx` | 报价单 / 核价单 Excel 视图渲染 | ✓ batchEvaluate 携带 `costingTemplateId` |
| `cpq-backend/.../costing/entity/CostingTemplate.java` | Excel 模板实体 | ✓ 增 `sql_views_snapshot` JSONB |
| **新表** `costing_template_sql_view` | 模板 SQL 视图（与 component_sql_view 同构）| 新建 |

---

## 三、目标与非目标

### 3.1 目标（必达）

| # | 目标 | 验收 |
|---|------|------|
| G1 | Excel 模板可独立配置 SQL 视图（CRUD + dry-run 校验），与组件 SQL 视图存储 / 命名 / 引用三层隔离 | 进入 Excel 模板详情页能看到 "SQL 视图" Tab；新建 + 保存 + dry-run 流程与组件管理一致 |
| G2 | Excel 列 BNF 路径用 `$<view>.<col>` 引用本模板拥有的 SQL 视图 | `LinkedExcelView` 渲染时能正确求值 `$<view>.<col>`，结果与 dry-run 一致 |
| G3 | 跨 owner 引用 100% 被拒（Excel 列写 `$$code.view.col` → 报错；组件字段 `$<excel_view_name>` → 报错）| 单元测试覆盖 4 个跨向场景（C→E、E→C、E→E_other、C→C_other） |
| G4 | Excel 模板发布时把所拥有的 SQL 视图全部快照到 `costing_template.sql_views_snapshot` | 已发布模板的渲染对源 `costing_template_sql_view` 修改完全冻结 |
| G5 | 老 PG 直引路径（`v_xxx.col`）保留兼容但加迁移告警 | `/api/cpq/costing-templates/legacy-paths` 端点 + 控制台 WARN |

### 3.2 非目标（本次不做）

- 不动组件 SQL 视图协议（`component_sql_view` / `$$code.view.col` GLOBAL 跨组件引用仍保留，只在组件之间使用）
- 不动 Excel 模板 `columns` JSON 的列结构（col_key / source_type / variable_path 等字段保持兼容）
- 不动 ImplicitJoinRewriter / CachedSqlCompiler（老 PG 路径兼容保留到 AP-53 治理收尾）
- 不动公式语法（`[X]+[Y]` / `{code}` 现有语义保持）
- 不引入新的字段类型；不触发 AP-44 17 处协议矩阵

---

## 四、最终架构

```
┌─────────────────────────────────────────────────────────────────────────┐
│                  请求线程 ThreadLocal                                    │
│  SqlViewRuntimeContext {                                                 │
│    ownerType: COMPONENT | COSTING_TEMPLATE | null,   ← 新增字段          │
│    componentId: UUID | null,                                             │
│    costingTemplateId: UUID | null,           ← 新增字段                  │
│    templateId: UUID | null,                                              │
│    quotationId: UUID | null,                                             │
│    quotationStatus: String | null                                        │
│  }                                                                        │
│  约束：ownerType=COMPONENT 时 componentId 必非空；                       │
│        ownerType=COSTING_TEMPLATE 时 costingTemplateId 必非空            │
└─────────────────────────────────────────────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ SqlViewExecutor.execute(path, ctx, partNos)                              │
│   1. 用 PATH_PATTERN 解出 (isCross, prefix, viewName, predicate, column) │
│   2. 若 isCross=true（$$形态）：                                          │
│        - ownerType=COSTING_TEMPLATE → 抛 BusinessException（拒绝跨引用） │
│        - ownerType=COMPONENT → 按 GLOBAL 组件视图查（沿用现状）          │
│   3. 若 isCross=false（$形态）：                                          │
│        - ownerType=COMPONENT → lookup component_sql_view                 │
│        - ownerType=COSTING_TEMPLATE → lookup costing_template_sql_view   │
│        - ownerType=null → 抛 BusinessException                            │
└─────────────────────────────────────────────────────────────────────────┘
                       │
              ┌────────┴────────┐
              ▼                 ▼
┌─────────────────────────┐  ┌─────────────────────────┐
│ ComponentSqlViewService │  │ CostingTemplateSqlView- │
│  .lookupForResolver()   │  │ Service.lookupForResolver│
│                         │  │                          │
│ fallback 顺序：         │  │ fallback 顺序：          │
│ 1. 报价单冻结 snapshot  │  │ 1. costing_template     │
│ 2. template snapshot    │  │    .sql_views_snapshot  │
│ 3. component_sql_view   │  │    (PUBLISHED 时优先)   │
│   (实时读)              │  │ 2. costing_template_    │
│                         │  │    sql_view (实时读)    │
└─────────────────────────┘  └─────────────────────────┘
```

### 4.1 设计决策表

| 决策 | 备选 | **选定** | 理由 |
|------|------|---------|------|
| Excel 模板 SQL 视图存储方式 | (a) 复用 `component_sql_view` 加 owner_type 字段；(b) 新建 `costing_template_sql_view` 表 | **(b)** | 隔离更彻底；FK 干净（owner=costingTemplate id 直接 FK）；snapshot 反序列化逻辑独立 |
| `$<view>.col` 路径形态对 Excel 是否扩展前缀（例如 `$@view`）| (a) 共用 `$`；(b) 加新前缀 `$@view` 区分 owner | **(a)** | 用户期望"与组件一样配置"，前缀一致最少认知负担；owner 由请求上下文决定，符合"配置位置 = 引用语义"直觉 |
| ThreadLocal 是否互斥？ | (a) componentId / costingTemplateId 可同时设；(b) 强互斥（同时只能有一个 owner）| **(b)** | 隔离约束的根本保障；避免歧义路径 |
| Excel 模板里写 `$$code.view.col`（误用）| (a) 静默回退到组件视图；(b) 强抛 BusinessException | **(b)** | 帮助用户立即发现隔离边界；提示文案明确 |
| `costing_template_sql_view` 是否支持 GLOBAL scope | (a) 不支持（隔离=不能跨）；(b) 支持但只在 Excel 模板之间跨 | **(a)** | 用户明确"互相隔离"；KISS；如有跨模板复用诉求，鼓励复制粘贴或后续单开"模板视图库"特性 |
| Snapshot fallback 优先级（Excel 模板）| (a) 报价单冻结 → costing_template snapshot → 实时；(b) costing_template snapshot → 实时 | **(b)** | Excel 模板不进 quotation_component_sql_snapshot（那是组件视图专用快照）；Excel 模板自身的 snapshot 已经够 |
| 列编辑页 PathPicker 的 SQL 视图 Tab 内容 | (a) 显示本模板视图 + 组件 GLOBAL 视图（混合）；(b) 仅显示本模板视图 | **(b)** | 隔离 → UI 也隔离；避免用户错选组件视图 |

---

## 五、数据模型变更

### 5.1 新表 `costing_template_sql_view`（V237）

```sql
-- V237__create_costing_template_sql_view.sql
CREATE TABLE costing_template_sql_view (
    id                  UUID         PRIMARY KEY,
    costing_template_id UUID         NOT NULL REFERENCES costing_template(id) ON DELETE CASCADE,
    sql_view_name       VARCHAR(80)  NOT NULL,
    sql_template        TEXT         NOT NULL,
    declared_columns    JSONB        NOT NULL DEFAULT '[]'::jsonb,
    required_variables  TEXT[]       NOT NULL DEFAULT '{}'::text[],
    -- scope 字段保留但只有 LOCAL 一个有效值，留为后续扩展空间
    scope               VARCHAR(20)  NOT NULL DEFAULT 'LOCAL' CHECK (scope IN ('LOCAL')),
    status              VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    description         TEXT,
    created_by          UUID,
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_ctsv_template_view_name UNIQUE (costing_template_id, sql_view_name)
);

CREATE INDEX idx_ctsv_template ON costing_template_sql_view(costing_template_id);

COMMENT ON TABLE costing_template_sql_view IS
  'Excel 模板拥有的 SQL 视图（与 component_sql_view 同构 + 隔离）。引用语法 $view.col，作用域为本模板内部。';

COMMENT ON COLUMN costing_template_sql_view.scope IS
  '保留字段，当前只允许 LOCAL；后续若需要"模板视图库"特性可扩 GLOBAL';
```

### 5.2 `costing_template` 增字段（V238）

```sql
-- V238__costing_template_add_sql_views_snapshot.sql
ALTER TABLE costing_template
  ADD COLUMN sql_views_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb;

COMMENT ON COLUMN costing_template.sql_views_snapshot IS
  '发布时把本模板拥有的所有 costing_template_sql_view 快照到此 JSONB。
   结构: {"<sql_view_name>": {sqlTemplate, declaredColumns, requiredVariables}}';
```

> 注：不需要 `referenced_components` 字段（v1 的设想 — v2 隔离后没有外部引用）。

### 5.3 名称冲突约束

`costing_template_sql_view.sql_view_name` 与 `component_sql_view.sql_view_name` **允许同名**，因为隔离查表 — 不会冲突。但前端需在 PathPicker 上明确提示来源（badge "模板视图" vs "组件视图"）避免用户错觉。

---

## 六、后端代码变更

### 6.1 `SqlViewRuntimeContext.java` — 扩展 owner 字段

```java
public final class SqlViewRuntimeContext {

    public enum OwnerType { COMPONENT, COSTING_TEMPLATE }

    public static final class Snapshot {
        public static final Snapshot EMPTY = new Snapshot(null, null, null, null, null, null);

        public final OwnerType ownerType;       // 新增
        public final UUID componentId;
        public final UUID costingTemplateId;    // 新增
        public final UUID templateId;
        public final UUID quotationId;
        public final String quotationStatus;
        ...
    }

    /** 组件上下文。沿用旧 set，内部转调新 6-arg。 */
    public static void set(UUID componentId, UUID templateId, UUID quotationId, String quotationStatus) {
        CURRENT.set(new Snapshot(OwnerType.COMPONENT, componentId, null,
                templateId, quotationId, quotationStatus));
    }

    /** Excel 模板上下文（新增）。 */
    public static void setCostingTemplate(UUID costingTemplateId, UUID quotationId, String quotationStatus) {
        CURRENT.set(new Snapshot(OwnerType.COSTING_TEMPLATE, null, costingTemplateId,
                null, quotationId, quotationStatus));
    }

    public static Snapshot setNested(...) { ... }  // 同步加 6-arg 形态
    public static void restore(Snapshot prev) { ... }
}
```

**互斥约束**：构造 `Snapshot` 时校验 `ownerType=COMPONENT ⇒ componentId≠null ∧ costingTemplateId=null`，反之亦然；违反抛 `IllegalArgumentException`。

### 6.2 新实体 `CostingTemplateSqlView.java`

与 `ComponentSqlView.java` 结构对齐，只换 owner FK：

```java
@Entity
@Table(name = "costing_template_sql_view",
       uniqueConstraints = @UniqueConstraint(
           name = "uq_ctsv_template_view_name",
           columnNames = {"costing_template_id", "sql_view_name"}))
public class CostingTemplateSqlView extends PanacheEntityBase {
    @Id @GeneratedValue public UUID id;
    @Column(name = "costing_template_id", nullable = false) public UUID costingTemplateId;
    @Column(name = "sql_view_name", nullable = false, length = 80) public String sqlViewName;
    @Column(name = "sql_template", nullable = false, columnDefinition = "TEXT") public String sqlTemplate;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "declared_columns", nullable = false, columnDefinition = "jsonb")
    public String declaredColumns = "[]";
    @Column(name = "required_variables", nullable = false, columnDefinition = "text[]")
    public String[] requiredVariables = new String[0];
    @Column(nullable = false, length = 20) public String scope = "LOCAL";
    @Column(nullable = false, length = 20) public String status = "ACTIVE";
    @Column(columnDefinition = "TEXT") public String description;
    @Column(name = "created_by") public UUID createdBy;
    @Column(name = "created_at", nullable = false) public LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false) public LocalDateTime updatedAt;
    ...
}
```

### 6.3 新服务 `CostingTemplateSqlViewService.java`

与 `ComponentSqlViewService` 同 5 个核心方法（list / get / create / update / dryRun），**但**：
- 没有 `lookupForResolver` 跨 owner 查询逻辑（隔离）
- 简单 fallback：snapshot 优先 → 实时读

```java
@ApplicationScoped
public class CostingTemplateSqlViewService {

    public List<CostingTemplateSqlView> list(UUID costingTemplateId) { ... }
    public Optional<CostingTemplateSqlView> get(UUID id) { ... }
    public CostingTemplateSqlView create(UUID costingTemplateId, CreateCostingTemplateSqlViewRequest req) { ... }
    public CostingTemplateSqlView update(UUID id, UpdateCostingTemplateSqlViewRequest req) { ... }
    public void delete(UUID id) { ... }
    public DryRunResponse dryRun(DryRunRequest req) { ... }

    /**
     * BNF $view 路径查找入口。仅在 SqlViewRuntimeContext.ownerType=COSTING_TEMPLATE 时被 SqlViewExecutor 调用。
     */
    public Optional<CostingTemplateSqlView> lookupForResolver(UUID costingTemplateId, String sqlViewName) {
        // 1. snapshot 优先（PUBLISHED 模板冻结视图定义）
        Optional<CostingTemplateSqlView> fromSnapshot = lookupFromCostingTemplateSnapshot(
            costingTemplateId, sqlViewName);
        if (fromSnapshot.isPresent()) return fromSnapshot;

        // 2. 兜底实时读
        return CostingTemplateSqlView.find(
            "costingTemplateId = ?1 AND sqlViewName = ?2 AND status = 'ACTIVE'",
            costingTemplateId, sqlViewName).firstResultOptional();
    }

    private Optional<CostingTemplateSqlView> lookupFromCostingTemplateSnapshot(...) { ... }
}
```

### 6.4 改造 `SqlViewExecutor.java` — owner-aware 路由

```java
public List<Map<String, Object>> execute(String path, RuntimeContext ctx, List<String> partNos) {
    Matcher m = PATH_PATTERN.matcher(path.trim());
    if (!m.matches()) throw new IllegalArgumentException("非法的 SQL 视图路径语法：" + path);

    boolean isCross = m.group(1) != null;
    String componentCode = isCross ? m.group(1) : null;
    String viewName = isCross ? m.group(2) : m.group(3);
    String predicate = m.group(4);
    String column = m.group(5);

    SqlViewRuntimeContext.Snapshot owner = SqlViewRuntimeContext.get();

    // === 隔离边界强制 ===
    if (isCross) {
        // $$code.view.col 形态：仅组件上下文可用
        if (owner.ownerType != SqlViewRuntimeContext.OwnerType.COMPONENT) {
            throw new BusinessException(400,
                "Excel 模板路径不允许跨组件引用（$$ 形态）。" +
                "请改用本模板自有的 SQL 视图 $" + viewName + "。path=" + path);
        }
        return executeCrossComponent(componentCode, viewName, predicate, column, ctx, partNos);
    }

    // $view.col 形态：按 owner 路由
    if (owner.ownerType == SqlViewRuntimeContext.OwnerType.COMPONENT) {
        return executeViaComponentSqlView(owner.componentId, viewName, predicate, column, ctx, partNos);
    }
    if (owner.ownerType == SqlViewRuntimeContext.OwnerType.COSTING_TEMPLATE) {
        return executeViaCostingTemplateSqlView(owner.costingTemplateId, viewName, predicate, column, ctx, partNos);
    }

    throw new BusinessException(400,
        "SQL 视图路径解析失败：未设置 owner 上下文。path=" + path);
}
```

并新增私有方法 `executeViaCostingTemplateSqlView` — 走 `costingTemplateSqlViewService.lookupForResolver` + 同 SQL 拼装流程。

`executeAllRows` 同样改造（driver path 形态）— 但 Excel 模板列是单值，本方案的 driver path 仍是组件专用，理论上不会从 Excel 上下文调用。仍写防御性断言：

```java
if (owner.ownerType != SqlViewRuntimeContext.OwnerType.COMPONENT) {
    throw new BusinessException(400, "driver 形态 $view 路径仅组件上下文可用");
}
```

### 6.5 新 REST 资源 `CostingTemplateSqlViewResource.java`

与 `ComponentSqlViewResource` 路由对齐：

```
GET    /api/cpq/costing-templates/{templateId}/sql-views
GET    /api/cpq/costing-templates/{templateId}/sql-views/{id}
POST   /api/cpq/costing-templates/{templateId}/sql-views
PUT    /api/cpq/costing-templates/sql-views/{id}
DELETE /api/cpq/costing-templates/sql-views/{id}
POST   /api/cpq/costing-templates/sql-views/dry-run
```

> dry-run 端点接受 sql_template + 占位符值，返回列签名 + 样本数据。

### 6.6 改造 `FormulaEvaluateResource.java`

`EvaluateRequest` 新增字段：

```java
public UUID costingTemplateId;       // 新增
public UUID quotationId;             // 新增
public String quotationStatus;       // 新增
```

`evaluate` 入口包裹 ThreadLocal：

```java
public ApiResponse<EvaluateResponse> evaluate(EvaluateRequest req, ...) {
    SqlViewRuntimeContext.Snapshot prev = null;
    try {
        if (req.costingTemplateId != null) {
            prev = SqlViewRuntimeContext.setNestedCostingTemplate(
                req.costingTemplateId, req.quotationId, req.quotationStatus);
        }
        // 缓存命中检查 + doEvaluate
        return ApiResponse.success(...);
    } finally {
        if (prev != null) SqlViewRuntimeContext.restore(prev);
    }
}
```

`batchEvaluate` 同样在每个 task 入口分别 set / restore（task 之间 partNo / quotationId 可能不同）。

**缓存 key 必须含 costingTemplateId**（否则两个模板引用同名 `$view` 会串混）：

```java
String key = FormulaEvalCache.buildKey(expr, customerId, partNo, costingTemplateId);
```

### 6.7 改造 `CostingTemplateService` — publish 流程

```java
public void publish(UUID costingTemplateId) {
    CostingTemplate ct = CostingTemplate.findById(costingTemplateId);
    // 1. 状态机校验
    if (!"DRAFT".equals(ct.status)) throw new BusinessException(400, "只有 DRAFT 状态可发布");

    // 2. 解析 columns，校验所有 $view.col 路径
    List<PathRef> refs = parseColumnsForSqlViewRefs(ct.columns);
    for (PathRef ref : refs) {
        if (ref.isCross) {
            throw new BusinessException(400,
                "Excel 模板列 " + ref.colKey + " 含跨组件引用 $$，请改为本模板自有视图：" + ref.original);
        }
        Optional<CostingTemplateSqlView> v = sqlViewService.lookupForResolver(costingTemplateId, ref.viewName);
        if (v.isEmpty()) {
            throw new BusinessException(400,
                "Excel 模板列 " + ref.colKey + " 引用的视图 $" + ref.viewName + " 不存在。");
        }
    }

    // 3. 拉本模板所有 ACTIVE 视图，构造 snapshot
    List<CostingTemplateSqlView> views = CostingTemplateSqlView.find(
        "costingTemplateId = ?1 AND status = 'ACTIVE'", costingTemplateId).list();
    Map<String, Map<String, Object>> snapshot = new LinkedHashMap<>();
    for (CostingTemplateSqlView v : views) {
        snapshot.put(v.sqlViewName, Map.of(
            "sqlTemplate", v.sqlTemplate,
            "declaredColumns", v.declaredColumns,
            "requiredVariables", v.requiredVariables));
    }
    ct.sqlViewsSnapshot = MAPPER.writeValueAsString(snapshot);

    // 4. 标 PUBLISHED
    ct.status = "PUBLISHED";
    ct.publishedAt = OffsetDateTime.now();
    ct.persist();
}
```

> **派生新草稿**时（已存在的 `deriveDraft` 流程）也要把 SQL 视图行 + columns 一起 deep-copy 到新草稿，否则用户改了视图后老 PUBLISHED 副本还活着但新 DRAFT 没视图。

### 6.8 新工具 `BnfPathLinter.java`

```java
@ApplicationScoped
public class BnfPathLinter {

    public LintResult lint(String variablePath, SqlViewRuntimeContext.OwnerType ownerType, String status) {
        String p = variablePath.trim();

        // {code} 简写 — OK
        if (LEGACY_VAR_CODE.matcher(p).matches()) return LintResult.ok();

        // $$ 形态 — Excel 上下文禁
        if (p.startsWith("$$")) {
            if (ownerType == SqlViewRuntimeContext.OwnerType.COSTING_TEMPLATE) {
                return LintResult.error("Excel 模板不允许跨组件引用 $$");
            }
            return LintResult.ok();
        }

        // $ 形态 — OK
        if (p.startsWith("$")) return LintResult.ok();

        // PG 直引 — 检测 V44 老表黑名单
        for (String dep : DEPRECATED_TABLES) {
            if (p.startsWith(dep + ".") || p.startsWith(dep + "[")) {
                return LintResult.error("路径含 V44 老表 " + dep + "（AP-53 禁用）");
            }
        }
        // 兜底 warn — 建议迁移到 $ 形态
        return LintResult.warn("PG 视图直引建议迁移到本模板自有 SQL 视图（AP-53）");
    }
}
```

启动期或保存模板时调用，PUBLISHED 状态强阻断 error。

### 6.9 新端点 `LegacyPathsResource`

```
GET /api/cpq/costing-templates/legacy-paths
返回：[{ templateId, templateName, status, colKey, variablePath, lintLevel, suggestion }]
```

供运维 / 管理员主动盘点存量负债。

---

## 七、前端代码变更

### 7.1 新 service `costingTemplateSqlViewService.ts`

与 `componentSqlViewService.ts` 同接口名（list / get / create / update / delete / dryRun），底层路由换。

### 7.2 Excel 模板详情页加 "SQL 视图" Tab

`CostingTemplateConfig.tsx` 当前是单层布局（基本信息 + 列编辑表）。改为顶层 Tabs：

```tsx
<Tabs activeKey={mainTab} onChange={setMainTab} items={[
  { key: 'columns', label: '列配置', children: <ColumnsEditor ... /> },
  { key: 'sql-views', label: 'SQL 视图', children:
      <CostingTemplateSqlViewsTab costingTemplateId={id} readonly={template.status !== 'DRAFT'} /> },
]} />
```

新组件 `CostingTemplateSqlViewsTab` 复用组件管理的 SQL 视图 Tab UI 模式（列表 + 新建抽屉 + 编辑抽屉 + dry-run 预览），但底层 service 换成 `costingTemplateSqlViewService`。

### 7.3 `PathPickerDrawer.tsx` — owner 上下文扩展

新增 prop：

```tsx
interface Props {
  ...
  /** 路径选择器的 owner 上下文 — 决定 SQL 视图 Tab 显示哪张表的视图 + 允许的路径形态 */
  ownerContext?:
    | { type: 'COMPONENT'; componentId: string }
    | { type: 'COSTING_TEMPLATE'; costingTemplateId: string };
}
```

`sql-view` Tab 行为分支：

```tsx
// 组件上下文
if (ownerContext?.type === 'COMPONENT') {
  // 列出本组件 SQL 视图 + 跨组件 GLOBAL 视图（沿用现状）
}

// Excel 模板上下文
if (ownerContext?.type === 'COSTING_TEMPLATE') {
  // 仅列出本 Excel 模板的 SQL 视图（隔离）
  costingTemplateSqlViewService.list(ownerContext.costingTemplateId).then(...)
  // 不显示 GLOBAL 视图区域
  // 不显示"来自其他组件 / 模板"任何引用
}
```

生成路径形态：

| owner | 选项 | 生成 |
|-------|------|------|
| COMPONENT | 本组件视图 | `$view.col` |
| COMPONENT | 跨组件 GLOBAL | `$$code.view.col` |
| COSTING_TEMPLATE | 本模板视图 | `$view.col` |

`manual` Tab 输入校验：

```tsx
{ownerContext?.type === 'COSTING_TEMPLATE' && pathExpr.startsWith('$$') && (
  <Alert type="error" message="Excel 模板路径不允许 $$ 跨组件引用（隔离规则）" />
)}
```

`visual`（PG 直引）Tab 加 legacy lint 警告（AP-53）。

### 7.4 `CostingTemplateConfig.tsx` 调用 PathPicker 升级

```tsx
<PathPickerDrawer
  open={pathPickerOpen}
  onClose={...}
  initialPath={...}
  onConfirm={handlePathPickerConfirm}
  ownerContext={{ type: 'COSTING_TEMPLATE', costingTemplateId: id! }}
  defaultTab="sql-view"
  legacyPathPolicy={template.status === 'DRAFT' ? 'WARN_WITH_MIGRATION_SUGGEST' : 'BLOCK'}
/>
```

### 7.5 列表行内"路径源"标签

在 `CostingTemplateConfig` 表格的 `variable_path` 列加标签：

| 形态 | 标签 | 颜色 |
|------|------|------|
| `{code}` | lineItem 字段 | 灰 |
| `$view.col` | 本模板视图 | 绿 |
| `$$code.view.col` | ⚠️ 跨引用（违反隔离）| 红 |
| `v_xxx.col` / `mat_xxx.col` | ⚠️ 老 PG 直引 | 黄 |

### 7.6 `LinkedExcelView.tsx` — 求值任务携带 costingTemplateId

```ts
const tasks = missing.map((t) => ({
  expression: `{${t.path}}`,
  customerId: customerId || null,
  partNo: t.partNo,
  costingTemplateId,                  // 新增 — 整个 Excel 视图渲染共用一份 costing_template
  quotationId: quotation?.id || null, // 新增
  quotationStatus: quotation?.status || null, // 新增
}));
```

> `LinkedExcelView` 已经持有 `costingTemplateId`（从反查 `linked_template_id` 拿到的 Excel 模板 id）；只需 props drilling 接进 batchEvaluate。

### 7.7 `formulaService.ts` 类型扩展

```ts
export interface EvaluateRequest {
  expression: string;
  customerId?: string | null;
  partNo?: string | null;
  bindings?: Record<string, any>;
  driverRow?: Record<string, any>;
  costingTemplateId?: string | null;  // 新增
  quotationId?: string | null;        // 新增
  quotationStatus?: string | null;    // 新增
}
```

---

## 八、迁移路径（按 Phase）

### Phase 1 — 后端打地基
1. V237（建表 `costing_template_sql_view`）+ V238（加 `sql_views_snapshot` 字段）
2. `SqlViewRuntimeContext` 扩 owner + 互斥约束
3. `CostingTemplateSqlView` 实体 + service + resource
4. `SqlViewExecutor` owner-aware 路由 + `$$` 跨引用拒绝
5. `FormulaEvaluateResource` 接 ThreadLocal + 缓存 key 含 costingTemplateId
6. `CostingTemplateService.publish` 构造 snapshot + 跨引用强校验
7. `BnfPathLinter` + `LegacyPathsResource`
8. 单元测试（4 个隔离边界场景）+ 集成测试

### Phase 2 — 前端接入
1. `costingTemplateSqlViewService` ts 服务
2. Excel 模板详情页加"SQL 视图" Tab（CRUD + dry-run）
3. `PathPickerDrawer` ownerContext 隔离行为
4. `CostingTemplateConfig` 调用方 + 行内"路径源"标签 + lint 警告
5. `LinkedExcelView` batchEvaluate 携带新参数

### Phase 3 — 灰度迁移
1. `/api/cpq/costing-templates/legacy-paths` 盘点存量
2. 对每个 PUBLISHED Excel 模板：
   - 派生新草稿
   - 进 SQL 视图 Tab，按列引用需求新建本模板视图（如 `summary_full` → SQL 模板 `SELECT ... FROM v_costing_summary_full`，过渡期）
   - 列编辑改 `variable_path` 从 `v_xxx.col` → `$summary_full.col`
   - 重新发布
3. E2E 复测核价单 + 报价单 Excel 视图

### Phase 4 — 老路径退役
1. `BnfPathLinter` PUBLISHED 路径强阻断 PG 直引
2. 已迁移模板的过渡期 sql_template 改为 V6 表直查（弃用 v_xxx 中间层）
3. ImplicitJoinRewriter 加 V44 黑名单阻断

---

## 九、自检清单（按 CLAUDE.md §修改后强制自检）

### 9.1 后端
- [ ] Flyway V237 + V238 `success=t`
- [ ] `touch` Java 文件强制 Quarkus 重启
- [ ] `curl /q/health` → 200
- [ ] 单测：`SqlViewRuntimeContextTest` 验互斥约束（同时设 componentId+costingTemplateId 必抛）
- [ ] 单测：`SqlViewExecutorTest` 验 4 个跨向场景全部按预期：
   - COMPONENT 上下文 + `$view` → 查 component_sql_view ✓
   - COMPONENT 上下文 + `$$code.view` → 查跨组件 GLOBAL ✓
   - COSTING_TEMPLATE 上下文 + `$view` → 查 costing_template_sql_view ✓
   - COSTING_TEMPLATE 上下文 + `$$code.view` → 抛 BusinessException ✓
- [ ] 集成测试：发布 Excel 模板后改源 `costing_template_sql_view.sql_template`，渲染**不变**（snapshot 冻结生效）
- [ ] 集成测试：派生新草稿 → SQL 视图 + 列配置都 deep-copy
- [ ] schema DDL 后必须 `touch` java 文件再重启（清 ImplicitJoinRewriter.tableColumnsCache）

### 9.2 前端
- [ ] `npx tsc --noEmit -p tsconfig.json` → 0 错误
- [ ] `curl http://localhost:5174/src/pages/costing/CostingTemplateConfig.tsx` → 200
- [ ] `curl http://localhost:5174/src/pages/component/PathPickerDrawer.tsx` → 200
- [ ] `curl http://localhost:5174/src/pages/quotation/LinkedExcelView.tsx` → 200
- [ ] 新文件 `curl http://localhost:5174/src/pages/costing/CostingTemplateSqlViewsTab.tsx` → 200
- [ ] 新文件 `curl http://localhost:5174/src/services/costingTemplateSqlViewService.ts` → 200

### 9.3 E2E（不可跳）
- [ ] `e2e/quotation-flow.spec.ts` → `1 passed` + `'加载中' final count = 0`
- [ ] `e2e/composite-product-flow.spec.ts` → `1 passed`
- [ ] 新建 `e2e/costing-template-sql-view.spec.ts`：
  - 进 Excel 模板详情页 → SQL 视图 Tab → 新建一个 `$summary_full` 视图（SQL: `SELECT hf_part_no, material_cost FROM v_costing_summary_full`）→ 保存
  - 列编辑 → 列 L → PathPicker → SQL 视图 Tab → 选 `summary_full` → 选 `material_cost` → 生成 `$summary_full.material_cost`
  - 发布
  - 报价单 Excel 视图 → 列 L 渲染正确 ✅
  - 改源视图 SQL → 已发布模板渲染**不变** ✅
  - 误填 `$$code.view.col` → 保存时 / 渲染时报错 ✅

### 9.4 AP-44 协议矩阵
本方案不动 `field_type` / 字段类型枚举 / driver expansion / ComponentCell / formulaEngine 字段值循环 → AP-44 17 处协议传播点**不触发**。仍需自检：
- [ ] 不动 `component/types.ts` `VALID_FIELD_TYPES`
- [ ] 不动 `useDriverExpansions.ts` / `ComponentCell.tsx` / `ReadonlyProductCard.tsx`
- [ ] 不动 `parseBasicDataPaths` / `usePathFormulaCache` / `computeAllFormulas`

### 9.5 AP-53 协议自检
- [ ] `costing_template.columns` grep `v_*` / `mat_*` / `element_price*` → 仅在已迁移过的列出现，且都已替换为 `$<view>.<col>` 形态
- [ ] `costing_template_sql_view.sql_template` grep `mat_part|mat_bom|mat_process|mat_fee|plating_plan|element_price|mat_customer_part_mapping` → Phase 3 过渡期可短暂存在，Phase 4 完成后必须 0 命中（改 V6 表）
- [ ] `component_sql_view` 表不出现 Excel 模板专用视图（隔离）

---

## 十、风险与缓解

| 风险 | 影响 | 概率 | 缓解 |
|------|------|------|------|
| 用户复制粘贴 SQL 视图到多个 Excel 模板形成漂移 | 中 | 高 | 后续可单开"模板视图库" 特性（GLOBAL scope 复活）；当前接受"同模板编辑各自更新"语义 |
| ThreadLocal owner 互斥导致复杂场景（如 Excel 列里嵌套调组件公式）失败 | 中 | 中 | 当前 Excel 列不支持嵌套组件公式（公式语法不允许）；如未来需求扩展，加 nested 设置（push/pop） |
| `costing_template_sql_view` 跟 `component_sql_view` 同名视图引起用户认知混淆 | 低 | 中 | UI badge "模板视图" vs "组件视图" 明显标识；保存时校验提示 |
| 缓存 key 漏 costingTemplateId 导致跨模板串混 | 高 | 中 | 测试覆盖 + code review 强制项 |
| `deriveDraft` 派生时没拷贝 SQL 视图 → 新草稿编辑列报错"视图不存在" | 中 | 中 | 派生流程必须包含 SQL 视图 deep-copy；E2E 覆盖 |
| `publish` 时跨引用强校验把历史 `$$code.view.col` 卡死（如某模板老路径含 `$$`）| 中 | 低 | 启动期扫描 + 提前迁移；Phase 3 完成后才开 publish 强校验 |
| 新表 `costing_template_sql_view` 与组件视图同概念但代码重复（DRY 违反）| 低 | 高 | 抽公共基类 `AbstractSqlView` 共享字段；service 共享 dry-run 工具方法 |

---

## 十一、PRD 与文档同步

按 CLAUDE.md "开发规范"：

1. **`docs/PRD-v3.md`**：
   - 第 8 章「Excel 模板配置」加 §8.X「模板 SQL 视图（独立配置）」章节，说明语法 / 隔离边界 / 与组件视图的关系
   - 第 9 章「演进史」追加：`2026-05-26 V237/V238 — Excel 模板独立 SQL 视图，路径 $view.col 隔离查 costing_template_sql_view`
2. **`docs/Excel模板配置指南.md`** 第四章「两种数据来源」补 `C. SQL 视图引用（推荐）`，第六章 23 列示例改为 `$summary_full.material_cost` 形态
3. **`docs/反模式.md`** AP-53 「关联文件高危清单」补 `costing_template.columns` + `costing_template.sql_views_snapshot` + `costing_template_sql_view.sql_template`
4. **`docs/方案制定前必读.md`** 决策树「改动 6」补"Excel 模板 BNF 路径 owner-aware 解析"分支
5. **`docs/组件级数据源SQL方案.md`** 加附录 D「与 Excel 模板独立 SQL 视图的隔离边界」说明
6. **`docs/三大核心模块基线.md`** §3「模板管理」补"模板 SQL 视图"为模板组件的第 4 种内含资源（列 / 关联 / fields_override / sql_views）

---

## 十二、验收 Demo（一次性走通）

1. 进 Excel 模板列表 → 选「核价-汇总演示模板」→ 点详情 → 派生新草稿
2. 切到「SQL 视图」Tab → 点「新建」→ 命名 `summary_full` → 粘贴 SQL 模板：
   ```sql
   SELECT hf_part_no, material_cost, processing_cost, profit, tax,
          finance_cost, management_cost, plating_cost, other_outsource_cost,
          quote_currency
   FROM v_costing_summary_full   -- 过渡期；Phase 4 改 V6 表
   ```
3. 点「dry-run」→ 看到列签名 + 样本数据 → 保存
4. 切到「列配置」Tab → 列 L「材料成本」→ 点「选择」→ SQL 视图 Tab → 选 `summary_full` → 选 `material_cost` → 路径自动生成 `$summary_full.material_cost`
5. 同样把 L~T 9 列从 `v_costing_summary_full.xxx` 改为 `$summary_full.xxx`
6. 保存 → 发布 → snapshot 写入 `sql_views_snapshot`
7. 进报价单 → 主 Tab 切到「核价」→ 视图切到 Excel → 列 L~T 渲染 ✅
8. 改源 `costing_template_sql_view.sql_template`（加一个 CASE）→ 已发布模板渲染**不变** ✅
9. 派生新草稿 → 新草稿 SQL 视图 Tab 自动包含 `summary_full`（deep-copy 生效）✅
10. 误把列 V 改成 `$$COMP-PROC.v.col` → 保存时报错"Excel 模板不允许跨组件引用 $$" ✅

---

## 十三、变更影响范围一览

| 层 | 文件 / 表 | 改动类型 |
|----|----------|---------|
| DB | `costing_template_sql_view`（新表）| 新建（V237）|
| DB | `costing_template` | +1 列 `sql_views_snapshot`（V238）|
| 后端（实体）| `CostingTemplate.java` | +1 字段 |
| 后端（实体）| `CostingTemplateSqlView.java` | 新建 |
| 后端（service）| `CostingTemplateSqlViewService.java` | 新建 |
| 后端（service）| `CostingTemplateService.java` | publish 流程改造 + deriveDraft deep-copy |
| 后端（resource）| `CostingTemplateSqlViewResource.java` | 新建 |
| 后端（resource）| `FormulaEvaluateResource.java` | +3 字段 + ThreadLocal 包裹 + 缓存 key 含 costingTemplateId |
| 后端 | `SqlViewRuntimeContext.java` | +1 enum + 1 字段 + 互斥约束 |
| 后端 | `SqlViewExecutor.java` | execute / executeAllRows owner-aware 路由 + 隔离强校验 |
| 后端 | `ComponentSqlViewService.java` | 仅 `lookupForResolver` 注释更新（语义不变）|
| 后端（新）| `BnfPathLinter.java` | 新建 |
| 后端（新）| `LegacyPathsResource.java` | 新建 |
| 后端（DTO）| `EvaluateRequest.java` | +3 字段 |
| 前端（service）| `costingTemplateSqlViewService.ts` | 新建 |
| 前端（页面）| `CostingTemplateConfig.tsx` | 顶层 Tabs 化 + PathPicker ownerContext + 路径源标签 |
| 前端（新组件）| `CostingTemplateSqlViewsTab.tsx` | 新建 |
| 前端 | `PathPickerDrawer.tsx` | +1 prop `ownerContext` + 隔离行为 + lint 提示 |
| 前端 | `LinkedExcelView.tsx` | batchEvaluate 携带 `costingTemplateId / quotationId / quotationStatus` |
| 前端 | `formulaService.ts` | EvaluateRequest 类型 +3 字段 |
| 测试 | `e2e/costing-template-sql-view.spec.ts` | 新建 |
| 文档 | 6 个文档同步更新 | — |

---

## 十四、TL;DR

**一句话**：让 Excel 模板拥有 `costing_template_sql_view`（独立表），引用语法仍是 `$<view>.<col>`，靠 `SqlViewRuntimeContext.ownerType` 决定查组件视图还是模板视图，跨 owner 引用一律拒绝；发布时把视图定义快照到 `costing_template.sql_views_snapshot` 实现冻结。

**核心 3 个改动点**：
1. **新表 `costing_template_sql_view`**（与 `component_sql_view` 同构 + FK 到 `costing_template`，scope 只有 LOCAL）
2. **`SqlViewExecutor` owner-aware**：`$view` 按当前 owner 路由查表；`$$` 在 Excel 上下文直接拒绝
3. **Excel 模板详情页新增"SQL 视图" Tab** + PathPickerDrawer 走 `ownerContext={type:'COSTING_TEMPLATE'}` 隔离选项

**最小可发布单元**：Phase 1 + Phase 2，即可让新建 Excel 模板配置 SQL 视图并被引用；Phase 3 / Phase 4 按业务节奏分批治理老 PG 直引存量。
