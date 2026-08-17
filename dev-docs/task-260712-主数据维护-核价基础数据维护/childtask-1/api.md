# childtask-1 接口文档（api.md）

> 前后端联调契约。以本文为准；字段变更须双方同步改本文。
> 通用：除模板下载外均 `Content-Type: application/json`；鉴权走本仓 `RoleFilter`（无注解=放行，写端点须 `@RoleAllowed`）。
> 响应包装：沿用本仓 `ApiResponse<T>`（`{ code, message, data }`）；前端 `api` 拦截器已解包，`materialRecipeService.ts` 为准范式。

---

## 1. 工序主数据导入（新增）

### 1.1 POST `/api/cpq/v6/process-master/import`
上传 xlsx 批量导入工序主数据（upsert 覆盖）。

- **权限**：`SYSTEM_ADMIN`（与材质库导入口径一致）。
- **Content-Type**：`multipart/form-data`
- **表单字段**：`file` = xlsx 文件（`@RestForm("file") FileUpload`）。
- **xlsx 表头约定**（首个 sheet；按中文列名读）：

  | 列名 | 必填 | 目标字段 | 类型 |
  |---|---|---|---|
  | `工序编号` | ✅ | process_no | 文本(≤20)，唯一键 |
  | `工序名称` | ✅ | process_name | 文本(≤50) |
  | `工序类别` | ✖ | process_category | 文本(≤30) |
  | `是否外协` | ✖ | is_outsource | 是/否 → bool |
  | `标准币种` | ✖ | standard_currency | 文本(≤10) |
  | `标准单位` | ✖ | standard_unit | 文本(≤20) |
  | `默认不良率` | ✖ | default_defect_rate | DECIMAL(10,4) |

- **写入语义**：`ON CONFLICT (process_no) DO UPDATE`——`process_name` 覆盖；选填列 `COALESCE(新值, 原值)`（留空不清原值）；`created_*` 不动。
- **去重**：同 xlsx 内同 `process_no` 多行取首行，余行入 skipped。
- **跳过规则**：`工序编号` 空 / `工序名称` 空 → 该行 skipped（不阻断整批）。

- **响应 200**：`ApiResponse<ProcessMasterImportReportDTO>`
  ```json
  {
    "code": 0, "message": "ok",
    "data": {
      "totalRows": 42,
      "insertedCount": 5,
      "updatedCount": 37,
      "skippedRowCount": 0,
      "skipped": [
        { "row": 12, "reason": "工序名称为空", "raw": "Z999,," }
      ],
      "durationMs": 137
    }
  }
  ```
- **错误**：非 xlsx / 解析失败 → 400 `ApiResponse`(message)；无权限 → 403。

### 1.2 GET `/api/cpq/v6/process-master/import/template`
下载导入模板（干净表头 xlsx）。
- **权限**：登录即可（与材质库模板一致）。
- **响应 200**：`application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`
  - Header：`Content-Disposition: attachment; filename="process_master_template.xlsx"`
  - Body：单 sheet，首行 = 上表 7 列表头（前 2 列必填）。
- 前端 `responseType: 'blob'`。

---

## 2. 工序主数据 CRUD（已存在，不改，仅登记）

`@Path("/api/cpq/v6/process-master")`（`ProcessMasterResource`）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/` | 分页列表 `ApiResponse<PageResult<ProcessMasterDTO>>`（`keyword/page/size`） |
| POST | `/` | 新建 `ProcessMasterUpsertRequest` → `ApiResponse<ProcessMasterDTO>` |
| PUT | `/{id}` | 编辑（processNo 锁定不可改） |
| DELETE | `/{id}` | 删除 |

`ProcessMasterDTO` / `ProcessMasterUpsertRequest` 业务字段：`processNo, processName, processCategory, isOutsource, standardCurrency, standardUnit, defaultDefectRate`。

---

## 3. 核价维护页「材质名」（改动）

### 3.1 GET `/api/cpq/v6/pricing-basic-data/parts/{materialNo}/sheets/{sheetKey}/rows`
（`PricingBasicDataMaintenanceResource`，已存在；本期在 `ELEMENT_BOM` sheet 的返回里**新增材质名**。）

- **改动点**：`sheetKey = ELEMENT_BOM`（物料与元素BOM）时，每行新增字段 **`material_recipe_name`**（材质名）。
  - 来源：后端 readRows 两跳 join `material_part_no → material_master.material_recipe_id → material_recipe.name`。
  - 未绑定 `material_recipe_id` → `material_recipe_name = null`（前端渲染灰字「未绑定」）。
- **列元数据**：材质名列进 sheet 的 `columns` 定义（`nameCol("material_recipe_name","材质名")`），前端动态列渲染即自动出列。
- **示例行（ELEMENT_BOM，节选）**：
  ```json
  {
    "material_no": "10110002",
    "material_part_no": "P-STL-304",
    "material_recipe_name": "不锈钢304",     // ← 新增；未绑定时 null
    "component_no": "Fe",
    "component_name": "铁",                  // 元素名（已有）
    "content": 0.71, "scrap_rate": 0.02
  }
  ```
- **不影响**：其它 sheet、写回校验（`validateMasters`）、`lookup`——材质料号仍是只读子维度，不校验、不下拉。

### 3.2 其余名称列（工序/元素/料号，机制不变）
- 工序名 `operation_name`/`process_name`、元素名 `component_name`、料号名 `code_name` 沿用现有单跳 MASTER join。**本期不改机制**，仅靠 B1 导入把工序码补进 `process_master` 后自然带出。

---

## 4. 联调顺序建议

1. 后端先落 B1（导入端点 + 模板）→ 前端 F1 联调导入。
2. 后端落 B2（readRows 材质名）→ 前端 F2 渲染。
3. B1 导入工序样例后，验维护页工序名非 null（AC-2）。

## 5. 契约变更登记
| 日期 | 变更 | 影响 |
|---|---|---|
| 2026-07-12 | 新增工序导入 `/import` + `/import/template`；ELEMENT_BOM rows 增 `material_recipe_name` | 前端 F1/F2 |
