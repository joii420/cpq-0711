# api · repair-260916 接口契约

> 本次**新增 1 个端点**，**既有端点零改动**。前端不调用新端点（见 `fronttask.md`），调用方是主线 / 运维脚本。
> 本文是 `backtask.md` 与 `test.md` 的唯一协调物；两边有出入以本文为准，本文与 `问题说明.md ⑥` 有出入以 AC 原文为准并报主线。

---

## 1. 新增：按组件重编译取数视图

### 1.1 基本信息

| 项 | 值 |
|---|---|
| 方法 / 路径 | `POST /api/cpq/config-center/recompile-components` |
| 所在类 | `ConfigCenterResource`（类级 `@Path("/api/cpq/config-center")`） |
| 鉴权 | **方法级** `@RoleAllowed({"SYSTEM_ADMIN"})`（覆盖类级的四角色，写法同 `refresh-all-snapshots`） |
| 用途 | 连表配置（语义图）变更后，**只**把指定组件的取数配置器视图按当前连表配置重新生成；不改字段表 / 公式 / 组件属性，不推任何模板快照 |
| 服务 AC | AC-6 / AC-7 / AC-8 / AC-13 |

### 1.2 请求体

```json
{
  "componentIds": ["4db28822-85c6-4522-ac62-61ef2393a99c", "57554055-0896-4cc8-be98-65e45b0a5985"],
  "confirm": false
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `componentIds` | `string[]`（UUID） | **是** | 要处理的组件 id。去重后处理，顺序无关。缺失 / `null` / 空数组 → 400 |
| `confirm` | `boolean` | 否 | 缺省 `false` = **仅预览，零写入**；`true` = 执行 |

### 1.3 处理规则（两条路径共用）

1. 按 `componentIds` **一次**查出组件；有不存在的 id → **整体** 404，零写入（存在的也不处理）。
2. 按这些组件 id **一次**查出其取数配置器视图（`component_sql_view` 中 `builder_config IS NOT NULL` 的行；判定口径与既有全量重编译 `listBuilderManaged` 相同）。某组件一个这样的视图都没有 → 放进 `skippedComponentIds`，不算错误。
3. 对每个视图：读 `builder_config` → 用**当前**连表配置编译（与全量重编译**同一编译调用、同一参数**，落库产物必须带收窄谓词）→ 与已落库 `sql_template` 比较。
   - `builder_config` 反序列化失败 → 500 `RECOMPILE_CONFIG_CORRUPT`，整体中止；
   - 编译失败 → 500 `RECOMPILE_FAILED`（结构化编译错误按原码原样上抛），整体中止。
   - 🚫 **不许跳过出错的视图继续处理其余视图**（与全量重编译同一纪律：混合态比整体失败更难查）。
4. **只有** `confirm=true` 时写库，且**全部写入在同一事务**内：
   - 文本有变化的视图：走与全量重编译**同一条写入路径**（`ComponentSqlViewService.update`，含保存期 dry-run，重算 `declared_columns` / `required_variables`）；
   - `builder_version` 对齐为当前编译器版本；`builder_config.axisScope` 缺失或不同则写入（与全量重编译相同判据）；
   - 每个**文本有变化**的视图写 **1 行** `operation_log`（见 1.6）；文本无变化的不写审计。
   - 任一步失败 → 整个事务回滚，返回错误，**已处理的视图也不落库**。
5. 🚫 **明确不做**：不改 `component` 表任何列（`fields` / `formulas` / `row_key_fields` / `part_no_field` / `part_name_field` / `sort_field` / `element_*_field` / `updated_at`）；不调 `forceRealignSnapshots` / `realignSqlViewsSnapshots`；不写 `template` / `template_component` / `template_component_snapshot`。
6. 🔑 **产物一致性**：同一时刻，本端点对某视图算出的新 `sql_template` 必须**逐字等于** `refresh-all-snapshots`（`recompile=true`）对该视图算出的文本（AC-7②）。实现上应复用同一段编译逻辑，不另写一份。

### 1.4 成功响应 · 预览（`confirm=false`）

HTTP 200，`ApiResponse` 信封（与 `refresh-all-snapshots` 一致）：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "preview": true,
    "componentCount": 3,
    "views": 3,
    "changed": 3,
    "changes": [
      {
        "componentId": "4db28822-85c6-4522-ac62-61ef2393a99c",
        "componentCode": "COMP-0004",
        "componentName": "来料固定加工费",
        "sqlViewName": "builder_c35c2bd590fe",
        "oldSqlTemplate": "SELECT … FROM ds_quote_incoming_fixed_fee dqiff LEFT JOIN ds_quote_material dqm …",
        "newSqlTemplate": "SELECT … COALESCE(dqm.material_name, mr.symbol) AS \"_物料_材料名\" … LEFT JOIN material_recipe mr …"
      }
    ],
    "unchangedViewNames": [],
    "skippedComponentIds": [],
    "warning": "只重编译所列组件的取数视图；不改组件字段表、公式、组件属性，不改任何模板快照。"
  }
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `preview` | boolean | 恒 `true` |
| `componentCount` | int | 去重后的组件数 |
| `views` | int | 这些组件下取数配置器视图总数 |
| `changed` | int | 其中新旧文本不同的视图数 |
| `changes` | array | **仅**文本有变化的视图，每项含 `componentId` / `componentCode` / `componentName` / `sqlViewName` / `oldSqlTemplate` / `newSqlTemplate`；按 `componentCode`、`sqlViewName` 升序 |
| `unchangedViewNames` | string[] | 文本无变化的视图名（升序） |
| `skippedComponentIds` | string[] | 没有取数配置器视图的组件 id（升序） |
| `warning` | string | 固定文案，见上 |

### 1.5 成功响应 · 执行（`confirm=true`）

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "preview": false,
    "componentCount": 3,
    "views": 3,
    "changed": 3,
    "changedViewNames": ["builder_4602c64a0c38", "builder_46f244df7ede", "builder_c35c2bd590fe"],
    "unchangedViewNames": [],
    "skippedComponentIds": [],
    "operationLogIds": ["…", "…", "…"]
  }
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `preview` | boolean | 恒 `false` |
| `componentCount` / `views` / `changed` / `unchangedViewNames` / `skippedComponentIds` | — | 同 1.4 |
| `changedViewNames` | string[] | 实际改写了 `sql_template` 的视图名（升序） |
| `operationLogIds` | string[] | 本次写入的审计行 id，与 `changedViewNames` **一一对应、同序**；`changed=0` 时为空数组 |

### 1.6 审计（`operation_log`）

每个文本有变化的视图 1 行：

| 列 | 值 |
|---|---|
| `operation_type` | `COMPONENT_VIEW_RECOMPILE` |
| `target_type` | `COMPONENT` |
| `target_id` | 组件 id |
| `operator_id` | 当前登录用户 id（取法同 `refresh-all-snapshots`：`sessionHelper.getCurrentUserIdOrFallback`） |
| `summary` | `按组件重编译取数视图 <sqlViewName>` |
| `details`（jsonb） | `{"sqlViewName": "...", "componentCode": "...", "oldSqlMd5": "...", "newSqlMd5": "...", "builderVersion": <int>, "source": "recompile-components"}` |

### 1.7 错误响应

错误沿用取数配置器端点族的**裸体**格式（`BuilderApiException` → `GlobalExceptionMapper`，不套 `ApiResponse` 信封）：`{"code": "<错误码>", "message": "...", ...extra}`。
鉴权失败沿用 `RoleFilter` 的既有响应（`ApiResponse` 信封）。

| HTTP | `code` | 触发 | extra |
|---|---|---|---|
| 400 | `COMPONENT_IDS_REQUIRED` | `componentIds` 缺失 / `null` / 空数组 | — |
| 400 | `INVALID_COMPONENT_ID` | 某元素不是合法 UUID | `invalidIds: string[]` |
| 404 | `COMPONENT_NOT_FOUND` | 有 id 在 `component` 表不存在 | `missingIds: string[]`（升序） |
| 500 | `RECOMPILE_CONFIG_CORRUPT` | 某视图 `builder_config` 无法反序列化 | `sqlViewName` / `componentId` |
| 500 | `RECOMPILE_FAILED` | 某视图编译失败（非结构化异常） | `sqlViewName` / `componentId` |
| 4xx | 编译器原码 | 结构化编译错误（如列/边找不到）原样上抛 | 原样 |
| 401 | —（`ApiResponse.error(401,"未登录")`） | 未登录 | — |
| 403 | —（`ApiResponse.error(403,"无权限访问")`） | 非 SYSTEM_ADMIN | — |

所有错误路径**零写入**（含 `operation_log`）。

### 1.8 性能口径（`backend.md §1`）

- 组件、视图、（执行路径的）组件编码各**一次批量查询**，均在循环外；
- 循环体内每个视图有编译器内部的 1 条元数据查询，执行路径另有 1 条保存期 dry-run —— 与既有全量重编译相同，属「一个视图 = 一个工作单元」，条数随**请求里的视图数**线性增长。
- ⇒ 按 `backend.md §1` **走例外申请**：代码处写 `// N+1 例外：…`、`BACKLOG` 登记、**用户批准**（主线在闸门 A 一并呈报）。审计行必须**批量写入**，不许逐行 `persist` 后逐行 flush 查询。

### 1.9 调用示例（主线落地用，AC-13）

```bash
# 预览（零写入）
curl -s --noproxy '*' -X POST http://localhost:8081/api/cpq/config-center/recompile-components \
  -H 'Content-Type: application/json' -H "Cookie: <admin 会话>" \
  -d '{"componentIds":["4db28822-85c6-4522-ac62-61ef2393a99c","57554055-0896-4cc8-be98-65e45b0a5985","39fa3d9d-54b9-4605-93ca-dd38cbfa2831"],"confirm":false}'
# 用户批准后执行：同一请求 confirm=true
```

> 登录方式（Cookie / Header）以 `AuthResource` 现行实现为准，实现方在回报里写出可直接复用的命令。

---

## 2. 既有端点：零改动（回归清单）

| 端点 | 为什么不改 | 回归 AC |
|---|---|---|
| `POST /api/cpq/config-center/refresh-all-snapshots` | 它恒处理**全部** builder 视图并恒推模板两份快照，语义与本次需求相反；本次新开端点，不往它身上加参数，避免同一端点出现两种互相矛盾的语义 | AC-9 |
| `GET/PUT /api/cpq/components/{id}/builder`、`POST …/builder/compile`、`…/preview`、`…/inspect` | 行为随连表配置变化自动生效（材料名多一段材质表查询），契约不变 | AC-2 / AC-3 / AC-4 |

---

## 3. `main-api.md` 回写（合并前必做，`task-docs.md §2.5`）

- 在 §8.1 `ConfigCenterResource` 下**追加**「按组件重编译取数视图」端点小节（取本文 §1 定义），末尾加来源标记：
  `> 来源任务：\`task-260908-取数配置器优化/repair-260916-来料类材料名补查材质表\`｜回写日期：<合并当日>`
- 顺带：总账里 `refresh-all-snapshots` 小节**描述已过期**（仍写着 K4 旧实现「逐个调用 refreshSnapshotsByComponent，请求体无」，实际已有 `templateIds` / `confirm` / `recompile` 三个参数）。**本次不改它**（非本任务契约），在合并汇报里点名，交用户裁决是否另行更正。
- 更新 `main-api.md` 文件头的「最近契约更新」说明。
