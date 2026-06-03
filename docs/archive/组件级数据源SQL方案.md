# 组件级数据源 SQL 方案

> **2026-05-25 立项**。本方案让组件管理新增"用户自写 SQL 视图"能力，作为 **BNF path 数据源的扩展层**，**不引入新渲染通路、不打破单一来源、不撞 §10.1.2 禁双轨红线**。
>
> **核心定位**：组件 SQL 视图 = 用户自定义的"行内虚拟视图"，物理上不发 DDL，但语义上与 PG view 等价；字段渲染依旧通过 BNF path 引用，渲染层 / cache key / ComponentDriverService 三分支 / ComponentCell 全部不动。
>
> **关联文档**：
> - `docs/三大核心模块基线.md` §2.3 driver path 语义（含 `$` 前缀）/ §3.2 snapshot 机制
> - `docs/组件管理字段配置指南.md` §十一 联动矩阵（17 → 18 处）
> - `docs/反模式.md` AP-44（更新）
> - `docs/方案制定前必读.md` 决策树改动 6（ImplicitJoinRewriter / BNF path / 视图 schema）

---

## 一、方案立项背景

### 1.1 原始痛点

「基础数据配置」当前承担三重职责：
1. Excel sheet → 物理表导入路由（最初设计意图）
2. PathPicker / BNF path 可选根节点库
3. ImplicitJoinRewriter 运行时 schema 上下文

每加一张视图，DBA 在 Flyway 加完之后还要在「基础数据配置」UI 补登记一行 sheet + N 行 attributes 才能被组件公式引用。维护成本高 + 元数据散落两处。

### 1.2 用户的核心诉求（三条澄清）

经 4 轮架构 critique 收敛，用户确定方向：

1. **用户自己在 SQL 中使用 UNION**，拼出想要使用的视图，然后**用 BNF path 配置路径来引用**
2. **组件 SQL 并不是结果渲染到组件上**，使用原理依然是 BNF path 引用 —— 组件 SQL 只是像 PG view 一样**提供一个数据源**
3. **绝对禁止双轨渲染**

这把方案从"渲染层双轨改造（高风险）"重定位成"BNF path 数据源扩展（最小侵入）"。

### 1.3 与"基础数据配置"职责拆分的关系

| 模块 | 拆分前职责 | 拆分后职责 |
|---|---|---|
| 基础数据配置 | Excel 入库 + BNF 根节点库 + schema 上下文 | **仅 Excel sheet → 物理表导入路由**（回归最初设计） |
| BNF 元数据 | 散在 basic_data_config 手工登记 | **自动同步 information_schema**（启动时扫物理视图/表 → bnf_table_meta） |
| 组件管理 | 字段配置 + 公式 | **+ 组件 SQL 视图配置**（用户自写 inline view，命名后被 BNF path 引用） |

---

## 二、心智模型

### 2.1 一句话定义

> **组件 SQL 视图 = 用户在组件管理 UI 写的一条 SELECT 语句，给它取个名字，BNF path 用 `$名字` 引用它，后端解析时把它当作 inline subquery 拼进最终查询。**

### 2.2 数据流（与基线 §1.2 对齐）

```
组件管理                                  渲染期
─────────                                ─────
component                                BNF path: $my_view[谓词].column
  fields[]                                       │
  formulas[]                                     ▼
  ┌────────────────────────────────┐    ┌─────────────────────────────────────┐
  │ component_sql_view (新增)       │    │ BnfPathResolver                     │
  │   sql_view_name = "my_view"    │    │   检测 path 开头是否为 "$"          │
  │   sql_template  = SELECT ...   │    │   是 → 查 component_sql_view → 拼成 │
  │   declared_columns = [...]     │ →  │   "(<sql>) my_view" 作为 FROM       │
  │   scope = COMPONENT / GLOBAL   │    │   否 → 走原物理视图路径             │
  └────────────────────────────────┘    └─────────────────────────────────────┘
                                                   │
                                                   ▼
                                         ImplicitJoinRewriter (不变)
                                         ComponentDriverService 三分支 (不变)
                                         useDriverExpansions 6 维 cache key (不变)
                                         ComponentCell 渲染 (不变)
```

### 2.3 引用语法对照

| 数据源类型 | BNF path 写法 | 解析后 |
|---|---|---|
| 物理表 | `mat_part[hf_part_no={lineItem.partNo}].unit_weight` | 不变 |
| 物理视图 | `v_q_element_merged[bom_type='ELEMENT'].composition_pct` | 不变 |
| **本组件 SQL（新）** | `$element_view[bom_type='ELEMENT'].composition_pct` | `(SELECT ...) element_view WHERE bom_type='ELEMENT'` 取列 |
| **跨组件 GLOBAL SQL（新）** | `$$<componentCode>.element_view[...].col` | 跨组件查找 + inline subquery |

`$` = 本组件命名空间；`$$` = 跨组件全局命名空间（要求目标 SQL `scope=GLOBAL`）。

---

## 三、数据模型

### 3.1 component_sql_view 表（新增）

```sql
CREATE TABLE component_sql_view (
  id UUID PRIMARY KEY,
  component_id UUID NOT NULL REFERENCES component(id) ON DELETE CASCADE,
  sql_view_name VARCHAR(80) NOT NULL,          -- BNF 引用名（如 element_view），全表内同 component 唯一
  sql_template TEXT NOT NULL,                  -- 含 :customerId / :partVersion 等占位符
  declared_columns JSONB NOT NULL,             -- [{name, dataType, nullable}]，保存时 dry-run 自动填
  required_variables TEXT[] NOT NULL DEFAULT '{}', -- 启动时由后端解析 :xxx 占位符
  scope VARCHAR(20) NOT NULL DEFAULT 'COMPONENT',  -- COMPONENT (本组件用) / GLOBAL (可跨组件 BNF 引用)
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  description TEXT,
  created_by UUID,
  created_at TIMESTAMP NOT NULL DEFAULT now(),
  updated_at TIMESTAMP NOT NULL DEFAULT now(),
  UNIQUE (component_id, sql_view_name)
);

CREATE INDEX idx_csv_scope_global ON component_sql_view(scope) WHERE scope = 'GLOBAL';
```

### 3.2 报价单冻结表（新增）

```sql
CREATE TABLE quotation_component_sql_snapshot (
  quotation_id UUID NOT NULL REFERENCES quotation(id) ON DELETE CASCADE,
  sql_view_key VARCHAR(200) NOT NULL,        -- "componentId::sql_view_name"
  sql_template TEXT NOT NULL,
  declared_columns JSONB NOT NULL,
  required_variables TEXT[] NOT NULL,
  frozen_at TIMESTAMP NOT NULL DEFAULT now(),
  PRIMARY KEY (quotation_id, sql_view_key)
);
```

### 3.3 模板 snapshot 扩展（既有表）

```sql
ALTER TABLE template
  ADD COLUMN sql_views_snapshot JSONB DEFAULT NULL;
-- 模板 PUBLISHED 时由 TemplateService.refreshSnapshotsByComponent 一同冻结
-- 结构: { "componentId::sql_view_name": { sql_template, declared_columns, required_variables } }
```

### 3.4 BNF 元数据自动同步表（新增）

```sql
CREATE TABLE bnf_table_meta (
  table_name VARCHAR(120) PRIMARY KEY,
  is_view BOOLEAN NOT NULL,
  template_kind VARCHAR(20) DEFAULT 'ALL',   -- QUOTATION / COSTING / ALL（运营按需调）
  display_name VARCHAR(200),
  picker_visible BOOLEAN DEFAULT true,
  last_synced TIMESTAMP NOT NULL DEFAULT now()
);
-- 后端启动时扫 information_schema.tables/views → upsert 本表
-- PathPicker 第二个 Tab 读这张表（替代 basic_data_config 提供根节点）
```

---

## 四、占位符约定

### 4.1 占位符白名单（系统级 RuntimeContext 暴露）

| 占位符 | 类型 | 来源 | 是否允许在用户 SQL 中使用 |
|---|---|---|---|
| `:customerId` | UUID | RuntimeContext.quotation.customerId | ✅ |
| `:partVersion` | INT | RuntimeContext.lineItem.partVersion | ✅ |
| `:templateKind` | TEXT | RuntimeContext.templateKind ('QUOTATION'/'COSTING') | ✅ |
| `:userId` | UUID | RuntimeContext.user.id | ✅ |
| `:quotationId` | UUID | RuntimeContext.quotation.id | ✅ |
| `:costingSummaryId` | UUID | RuntimeContext.costingSummary.id | ✅ |
| `:hfPartNo` | TEXT | 驱动行的宏丰料号 | ❌ **禁用** —— 由 batchExpand 外层 `ANY(:hfPartNos)` 注入 |
| `:hfPartNos` | TEXT[] | 批量料号数组 | ❌ **保留** —— 用户 SQL 不应直接使用，由外层 wrapper 注入 |

### 4.2 用户 SQL 的必要约束（保存时校验）

1. ❌ **禁止使用 `:hfPartNo`**（标量）—— 保存时 AST 扫描，命中立即报错
2. ⚠️ **强烈建议 SELECT 出 `hf_part_no` 列** —— 否则外层 batch filter `WHERE inner_q.hf_part_no = ANY(:hfPartNos)` 失败；不强制（某些全局常量场景可以不要 hf_part_no）
3. ❌ **禁止 DDL / DML（INSERT/UPDATE/DELETE/CREATE/DROP/ALTER/TRUNCATE）** —— EXPLAIN 时检测语句类型
4. ⚠️ **保存时 dry-run** —— 用空 RuntimeContext 跑一次 `EXPLAIN`，确认语法合法 + 列签名提取

---

## 五、运行时执行流程

### 5.1 BNF path 解析扩展（唯一新增逻辑点）

```java
class BnfPathResolver {

  String resolveSheetExpression(String sheetName, RuntimeContext ctx) {
    if (sheetName.startsWith("$$")) {
      // 跨组件 GLOBAL SQL: $$<componentCode>.<sql_view_name>
      String[] parts = sheetName.substring(2).split("\\.", 2);
      ComponentSqlView view = lookupGlobalSqlView(parts[0], parts[1], ctx);
      String boundSql = bindNamedParams(view.sqlTemplate, ctx.toNamedParams());
      return "(" + boundSql + ")";
    }
    if (sheetName.startsWith("$")) {
      // 本组件 SQL: $<sql_view_name>
      ComponentSqlView view = lookupComponentSqlView(
          ctx.currentComponentId, sheetName.substring(1), ctx
      );
      String boundSql = bindNamedParams(view.sqlTemplate, ctx.toNamedParams());
      return "(" + boundSql + ")";
    }
    return sheetName;  // 物理视图/表 - 走原路径
  }
}
```

### 5.2 batch 优化（N+1 融合）

组件 SQL 模式与 BNF path 现有 batch 机制**自动融合** —— 因为 inline subquery 包装后仍走 ImplicitJoinRewriter 的 `ANY(:hfPartNos)` batch filter：

```sql
-- 用户 SQL（含 UNION 处理 SIMPLE/COMPOSITE）
SELECT hf_part_no, fee_value
FROM mat_fee
WHERE fee_type='ASSEMBLY_PROCESS' AND customer_id = :customerId
UNION ALL
SELECT hf_part_no, fee_value
FROM mat_fee_legacy
WHERE customer_id = :customerId

-- BNF path 引用: $assembly_fee[hf_part_no={lineItem.partNo}].fee_value
-- 后端解析改写后的实际执行 SQL（batch 形式）
SELECT inner_q.fee_value, inner_q.hf_part_no
FROM (
  <user_sql with :customerId bound>
) inner_q
WHERE inner_q.hf_part_no = ANY(:hfPartNos)
```

→ **一次 batch query 拿全部 partNo 数据，N+1 自然消解**。

### 5.3 lookupSqlView 的 snapshot 优先级（双层冻结）

```
渲染期请求一条 $sql_view_name 时:
  ┌────────────────────────────────────────────────────────┐
  │ 1. 报价单状态 = APPROVED / PUBLISHED ?                  │
  │    → 是: 查 quotation_component_sql_snapshot           │
  │         命中: 用冻结副本 (BREAK)                        │
  │         未命中: 报错或 fallback (要 alarm)              │
  ├────────────────────────────────────────────────────────┤
  │ 2. 模板状态 = PUBLISHED ?                               │
  │    → 是: 查 template.sql_views_snapshot JSONB          │
  │         命中: 用模板冻结副本 (BREAK)                    │
  ├────────────────────────────────────────────────────────┤
  │ 3. 兜底: 实时读 component_sql_view 表 (DRAFT 期使用)    │
  └────────────────────────────────────────────────────────┘
```

---

## 六、冻结策略（双层）

### 6.1 第一层：模板发布冻结

| 触发 | 模板 DRAFT → PUBLISHED |
|---|---|
| 触发位置 | `TemplateService.publish()` 内调 `refreshSnapshotsByComponent` 扩展 |
| 冻结对象 | 该模板所有挂载组件的 component_sql_view 行（含 GLOBAL scope 跨组件引用闭包） |
| 落地 | `template.sql_views_snapshot` JSONB |
| 与既有 fields snapshot 同步触发 | ✅ 与 component.fields → components_snapshot 同一事务 |

### 6.2 第二层：报价单提交冻结

| 触发 | 报价单 DRAFT → SUBMITTED |
|---|---|
| 触发位置 | `QuotationService.submit()` 内新增 `snapshotComponentSqlViews()` |
| 冻结对象 | 该报价单 line_items 引用的所有 component_sql_view（含跨组件依赖闭包） |
| 落地 | `quotation_component_sql_snapshot` 表 |
| 与既有 `*_snapshot` 列同步触发 | ✅ 与 formula_used_snapshot / data_source_snapshot 同事务 |

### 6.3 状态机扩展点

| 状态过渡 | 组件 SQL 处理 |
|---|---|
| 报价单 DRAFT → SUBMITTED | 写 quotation_component_sql_snapshot |
| 报价单 SUBMITTED → DRAFT（驳回） | 冻结副本**保留**（让销售看到上次提交时的状态），下次提交覆盖 |
| 模板 DRAFT → PUBLISHED | 写 template.sql_views_snapshot |
| 模板 createNewDraft | 拷贝 component_sql_view 关联引用即可（活引用） |

---

## 七、组件管理 UI 改动

### 7.1 新增 Tab "SQL 视图"

组件编辑抽屉新增一个 Tab，与"字段配置"/"公式"并列：

```
┌── 组件编辑抽屉 ────────────────────────────────────────────────┐
│ [基本信息] [字段配置] [公式] [SQL 视图 ★ 新]                  │
│                                                                │
│ SQL 视图列表（SelectableTable）:                              │
│ ┌──────────────────────────────────────────────────────────┐  │
│ │ ☐ name             scope      列签名         状态         │  │
│ │ ☐ element_view     COMPONENT  6 列          ACTIVE       │  │
│ │ ☐ assembly_fee     GLOBAL     2 列          ACTIVE       │  │
│ └──────────────────────────────────────────────────────────┘  │
│                                                                │
│ 工具栏: [新建] [编辑] [删除] [Dry-Run 校验]                   │
└────────────────────────────────────────────────────────────────┘
```

### 7.2 SQL 编辑 Drawer

```
┌── 编辑 SQL 视图 ─────────────────────────────────────────────┐
│ 名称: element_view                  Scope: ◉ COMPONENT ○ GLOBAL│
│                                                                │
│ SQL 模板（含命名占位符）:                                      │
│ ┌──────────────────────────────────────────────────────────┐  │
│ │ SELECT hf_part_no, element_name, composition_pct          │  │
│ │ FROM mat_bom                                              │  │
│ │ WHERE bom_type = 'ELEMENT'                                │  │
│ │   AND customer_id = :customerId                           │  │
│ │ UNION ALL                                                 │  │
│ │ SELECT hf_part_no, element_name, composition_pct          │  │
│ │ FROM mat_bom_legacy                                       │  │
│ │ WHERE customer_id = :customerId                           │  │
│ └──────────────────────────────────────────────────────────┘  │
│ 占位符提示: :customerId :partVersion :templateKind 等         │
│ ⚠️ 禁用 :hfPartNo（由外层 batch 注入）                        │
│                                                                │
│ [Dry-Run 测试]  → 显示自动提取的列签名:                       │
│   ✅ hf_part_no (TEXT) / element_name (TEXT) / composition_pct (NUMERIC) │
│                                                                │
│ [保存]  [取消]                                                │
└────────────────────────────────────────────────────────────────┘
```

### 7.3 PathPicker 新增第三个 Tab

```
┌── BNF 路径选择 ──────────────────────────────────────────────┐
│ [视觉模式: 物理视图]  [视觉模式: SQL 视图 ★ 新]  [手动模式]  │
│                                                                │
│ SQL 视图模式:                                                  │
│ ┌──────────────────────────────────────────────────────────┐  │
│ │ 本组件 SQL 视图:                                           │  │
│ │   ○ $element_view (6 列)                                  │  │
│ │   ○ $process_view (5 列)                                  │  │
│ │ 跨组件 GLOBAL SQL 视图:                                    │  │
│ │   ○ $$COMP-FEE.assembly_fee (2 列)                        │  │
│ └──────────────────────────────────────────────────────────┘  │
│                                                                │
│ 选 SQL → 选列 → 加谓词 → 生成 path:                          │
│   $element_view[bom_type='ELEMENT'].composition_pct           │
└────────────────────────────────────────────────────────────────┘
```

---

## 八、对核心业务流程的影响（最小侵入确认）

### 8.1 三大模块基线红线检查（全部 ✅）

| 红线 | 影响 |
|---|---|
| §6.1 字段渲染必须配置表达 | ✅ 仍是配置层 |
| §6.2 RuntimeContext 声明 | ✅ 占位符即 RuntimeContext 暴露 |
| §6.3 SIMPLE/COMPOSITE 在配置层统一 | ✅ 用户在 SQL 内自己写 UNION 处理双场景 |
| §6.4 模板字段单一来源 | ✅ 字段渲染单通路仍是 BNF path |
| §6.5 上下文变量字典系统级扩展 | ✅ 占位符按 RuntimeContext 统一管理 |
| §10.1.1 不改 V202 视图 | ✅ |
| §10.1.2 **禁双轨配置** | ✅ **不是双轨 —— 是 BNF path 数据源的层级扩展** |
| §10.1.3 前端不加 compositeType if | ✅ |
| §10.1.4 ImplicitJoinRewriter 不加隐式硬规则 | ✅ |
| §10.1.5 fields_override 永久 NULL | ✅ |
| §10.1.6 mat_process UNIQUE index 不改 | ✅ |

### 8.2 三大模块改动面

| 模块 | 改动 |
|---|---|
| 组件管理 | UI 新增"SQL 视图"Tab；管 component_sql_view CRUD；字段配置 UI 不动 |
| 模板管理 | snapshot 多冻结一个 sql_views_snapshot JSONB（与 fields snapshot 同源） |
| 报价单渲染 | **完全不动** —— 渲染链 / cache key / ComponentDriverService 三分支 / ComponentCell / autoSave 全不变 |

### 8.3 三个核心选配组件（§2.4 锁定）

- e42185ec / dae85db8 / 0a436b6c **完全不动**
- 仍走 BNF path `v_composite_child_*` + ComponentDriverService 三分支
- 组件 SQL 模式是"新组件可选的数据源类型"，**不回溯改造已锁定组件**

### 8.4 AP-44 矩阵 17 → 18 处

只多 BNF path 解析层 1 处（`$` 前缀识别）。详见 `docs/反模式.md` AP-44 更新 + `docs/组件管理字段配置指南.md §十一`。

---

## 九、剩余真问题（精简后 6 项）

| # | 问题 | 严重性 | 建议解 |
|---|---|---|---|
| 1 | SQL 静态校验（仅 SELECT 限制） | 中 | 后端拦截器 + EXPLAIN dry-run；前期接受"SELECT 关键字 + EXPLAIN 通过"两条最低门槛，AST 白名单 v2 补 |
| 2 | declared_columns 与 SQL 同步 | 中 | 保存时 dry-run 重算列签名 + 比对历史版本，删除引用列要警告 + 列出受影响字段引用 |
| 3 | 跨组件 GLOBAL scope SQL 的依赖闭包 | 中 | TemplateService.refreshSnapshotsByComponent 扩展：递归收集所有 GLOBAL 引用进 snapshot |
| 4 | `:hfPartNo` 禁用 + hf_part_no 列必填强校验 | 低 | 保存时 AST 扫描禁用 `:hfPartNo`；declared_columns 不含 `hf_part_no` 警告（不强制） |
| 5 | 修改 PUBLISHED 模板下的组件 SQL 的传播 | 中 | H1 端点 refreshSnapshotsByComponent 同步刷 sql_views_snapshot；与 fields 同源同步 |
| 6 | 比对视图历史报价单 SQL 版本展示 | 低 | 比对视图加 "SQL 视图版本一致 / 已演进" 标签，不阻塞使用 |

---

## 十、迁移路径

### 10.1 阶段 1（功能加法，0 破坏）

- 加 `bnf_table_meta` + 启动同步任务（PathPicker 第二个 Tab 数据源）
- 加 `component_sql_view` 表 + 组件管理 UI "SQL 视图" Tab
- 加 `quotation_component_sql_snapshot` 表 + 报价单 SUBMITTED 冻结 hook
- 扩 `template.sql_views_snapshot` 列 + 模板 PUBLISHED 冻结 hook
- BNF path 解析器加 `$` / `$$` 前缀识别
- PathPicker 新增第三个 Tab "SQL 视图"

### 10.2 阶段 2（基础数据配置职责回归）

- 加 `basic_data_config.deprecated_for_bnf` 标记位
- BNF path 解析时优先查 `bnf_table_meta`（fallback `basic_data_config`）
- 文档警示：新组件优先用 SQL 视图模式或 information_schema 视觉模式

### 10.3 阶段 3（可选，长期）

- 已发布模板的旧 BNF path 不动（snapshot 冻结，永久稳定）
- 新建组件强制走 SQL 视图模式或 information_schema 模式
- `basic_data_config` 表退化为单一 Excel 入库元数据

---

## 十一、E2E 自检清单

新增 spec `cpq-frontend/e2e/component-sql-view.spec.ts`，覆盖：

| # | 场景 | 验收 |
|---|---|---|
| 1 | 创建组件 SQL 视图（含 UNION + 占位符） | dry-run 通过 + 列签名提取 |
| 2 | BNF path 引用 `$sql_view_name[谓词].col` | 渲染单元格取值正确 |
| 3 | 跨组件 GLOBAL 引用 `$$<componentCode>.view[...]` | 渲染取值正确 |
| 4 | batchExpand 多 partNo 批量取数 | 1 次 SQL（非 N 次） |
| 5 | 报价单 SUBMITTED 冻结 SQL | quotation_component_sql_snapshot 写入 |
| 6 | SUBMITTED 后改组件 SQL → 报价单回放不变 | 冻结副本生效 |
| 7 | 模板 PUBLISHED 冻结 SQL | template.sql_views_snapshot 写入 |
| 8 | 删除 SQL 视图 / 字段仍引用 → 警告 | UI 显示受影响字段清单 |
| 9 | 用户 SQL 写 `:hfPartNo` → 保存报错 | 校验拦截 |
| 10 | 用户 SQL 写 DDL/DML → 保存报错 | EXPLAIN 校验拦截 |

并跑既有双 spec 保证渲染层无回归：
- `quotation-flow.spec.ts` 全 PASS（'加载中' final count = 0）
- `composite-product-flow.spec.ts` 全 PASS（'加载中' final count = 0）

---

## 十二、关联文档

| 文档 | 章节 | 关系 |
|---|---|---|
| `docs/三大核心模块基线.md` | §2.3 / §3.2 | $ 引用语法 + snapshot 扩展 |
| `docs/组件管理字段配置指南.md` | §二 BASIC_DATA / §十一 联动矩阵 | $ 引用语法 + 协议传播第 18 处 |
| `docs/反模式.md` | AP-44 | 矩阵 17 → 18 处更新 |
| `docs/方案制定前必读.md` | 决策树改动 6 | BNF path 解析层改动必读本方案 |
| `docs/PRD-v3.md` | §9 演进史 | 立项条目 |
| `docs/RECORD.md` | 时间线 | 立项条目（2026-05-25） |

---

## 十三、版本历史

| 日期 | 版本 | 关键演进 |
|---|---|---|
| 2026-05-23 ~ 25 | 立项 critique | 4 轮架构方向收敛（基础数据配置职责拆分 → 组件持 SQL → SQL 视图作为 BNF 数据源） |
| 2026-05-25 | **本文档定稿** | 心智模型 + 数据模型 + 双层冻结 + E2E 清单 |

---

**文档维护**：本文档随阶段 1/2/3 落地同步更新；任何对 component_sql_view / $ 引用语法的破坏性改动必须先评估、走 architect、更新本文档对应章节。

**最后修订**：2026-05-25
**状态**：📋 立项（待开发实施）
