# 接口契约 · task-260902 主数据与用户导入导出

> 本次**新增 5 个端点，不修改任何既有端点**。合并前须按 `task-docs.md §2.5` 回写 `dev-docs/main-api.md`。

## 全局约定（三个导出端点共用）

| 项 | 约定 | 依据 |
|---|---|---|
| 返回形态 | `Response.ok(byte[])` + `@Produces("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")` + `Content-Disposition: attachment; filename="<ascii>.xlsx"`。**🚫 不包 `ApiResponse<T>`** | 照抄现有 `downloadTemplate()`（`MaterialRecipeResource:74-82`） |
| 文件名 | **后端 Content-Disposition 用 ASCII**（如 `material_library.xlsx`）；**中文文件名由前端 `a.download` 决定** | 实测 `MaterialRecipeManagement.tsx:153` 前端本就自定文件名、不读响应头 ⇒ 不必碰 RFC 5987 编码 |
| 权限 | `@RoleAllowed({"SYSTEM_ADMIN"})` **标在方法上**（方法级覆盖类级，`RoleFilter:60-64` 实证） | AC-3 / AC-9 |
| 分页 | **无 page/size 参数**。导出永远是"筛选结果全量" | 用户裁决 |
| 空结果 | 仍返回 200 + 只有表头的 xlsx（前端已在 0 条时禁用按钮，此为后端兜底） | AC-23 |
| 错误 | 未登录 401 / 角色不符 403，均由 `RoleFilter` 统一产出 `{"code":401\|403,"message":"..."}` | — |

---

## B-1 · `GET /api/cpq/material-recipes/export`

材质库导出（与导入模板同构，可回导）。

```
@GET @Path("/export")
@Produces("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
@RoleAllowed({"SYSTEM_ADMIN"})
public Response export(@QueryParam("keyword") String keyword,
                       @QueryParam("recipeType") String recipeType,
                       @QueryParam("status") String status)
```

| 参数 | 类型 | 说明 |
|---|---|---|
| `keyword` | string, 可空 | 与 `GET /material-recipes` 的 keyword **同一套匹配规则**（材质编号/材质名称/元素符号/元素中文名），**必须复用同一个查询方法**，不许另写一套 SQL |
| `recipeType` | string, 可空 | `locked` / `editable` / `partial`，对应 `material_recipe.recipe_type` **精确相等** |
| `status` | string, 可空 | `ACTIVE` / `INACTIVE`。⚠️ 口径必须与前端 `isActive()` 一致：**仅 `'ACTIVE'` 算启用，其余（含 NULL）都算停用** |

**为什么要新增后两个参数**：`recipeType`/`status` 过滤当前**只在前端做**（`MaterialRecipeManagement.tsx:196-200` 的 `filteredList`），后端 `list()` 只认 `keyword`。导出走后端 ⇒ 后端必须能复刻这两个条件，否则「导出跟随筛选」做不到。
🚫 **不要顺手把列表接口也改成后端过滤** —— 那是改既有行为，属超范围。

**响应体（xlsx 结构）**

| 列 | 表头 | 取值 | 备注 |
|---|---|---|---|
| 1 | `材质` | `material_recipe.symbol` | 🚨 前 4 列**位置与文字都不能变**（导入端 `validateHeader` 按位置逐列比对） |
| 2 | `组号` | `material_recipe_config.seq` | 整数 |
| 3 | `元素符号` | `material_recipe_element.element_code` | |
| 4 | `含量` | `element.default_pct` **÷ 100** | 🚨 写 0–1 小数（0.84），**不是库值 84**；`BigDecimal.divide(100, 12, HALF_UP)`，去尾随零 |
| 5 | `材质编号` | `material_recipe.code` | 只读参考列，回导时被忽略 |
| 6 | `含量配置编号` | `material_recipe_config.config_no` | 只读 |
| 7 | `状态` | `启用` / `停用` | 只读 |
| 8 | `含量类型` | `标准锁定`/`含量可调`/`部分可调` | 只读，由 `recipe_type` 映射 |

**行集合**：`material_recipe_element` JOIN `material_recipe_config`（`status='ACTIVE'`）JOIN `material_recipe`（按上述筛选），按 `symbol, seq, sort_order` 排序。
🚫 **N+1 红线**：整个导出的 SQL 条数必须是**常数**（一条 JOIN 查询取全部行），不许「先查材质列表再逐材质查配置」。

---

## B-2 · `GET /api/cpq/v6/process-master/export`

```
@GET @Path("/export")
@Produces(<xlsx>)
@RoleAllowed({"SYSTEM_ADMIN"})
public Response export(@QueryParam("keyword") String keyword,
                       @QueryParam("isOutsource") Boolean isOutsource,
                       @QueryParam("processCategory") String processCategory)
```

三个参数与 `GET /v6/process-master` 的**同名参数语义完全一致**（复用同一查询方法，只是不传 page/size）。

**响应体（7 列）**：列名**必须取自 `ProcessMasterImportService` 的 `COL_*` 常量**，不是页面表头。

| 列 | 表头（＝导入模板列名） | 页面上叫什么 | 取值 |
|---|---|---|---|
| 1 | `工序编号` | 工序编号 | `process_no` |
| 2 | `工序名称` | 工序名称 | `process_name` |
| 3 | `工序类别` | ⚠️ 工序**分类** | `process_category` |
| 4 | `是否外协` | 是否外协 | `是` / `否`（**不写 true/false**） |
| 5 | `标准币种` | ⚠️ 标准**货币** | `standard_currency` |
| 6 | `标准单位` | 标准单位 | `standard_unit` |
| 7 | `默认不良率` | 默认不良率 | `default_defect_rate` 原始小数（`0.01`，**不写 `1.00%`**） |

第 3、5 列的列名与页面不同名是**刻意的**：导出要能回导，就必须用导入端认识的列名。

---

## B-3 · `GET /api/cpq/users/export`

```
@GET @Path("/export") @Produces(<xlsx>) @RoleAllowed({"SYSTEM_ADMIN"})
public Response export(@QueryParam("keyword") String keyword,
                       @QueryParam("role") String role,
                       @QueryParam("status") String status)
```

参数与 `GET /users` 同名同义。

**响应体（8 列）**：前 6 列＝导入模板列（可回导），后 2 列只读。

| 列 | 表头 | 取值 |
|---|---|---|
| 1 | `用户名` | `username` |
| 2 | `姓名` | `full_name` |
| 3 | `邮箱` | `email` |
| 4 | `角色` | **中文标签**：系统管理员 / 销售经理 / 销售代表 / 财务 |
| 5 | `区域` | 区域名称（`region.name`），无则空 |
| 6 | `部门` | 部门名称（`department.name`），无则空 |
| 7 | `状态` | `启用` / `停用`（只读） |
| 8 | `创建时间` | `yyyy-MM-dd HH:mm:ss`（只读） |

🚫 **不含 `id`、不含任何密码字段**。
⚠️ 区域/部门名称要**批量预取**成 Map 再回填，🚫 不许逐行查（N+1）。

---

## B-4 · `GET /api/cpq/users/import/template`

```
@GET @Path("/import/template") @Produces(<xlsx>) @RoleAllowed({"SYSTEM_ADMIN"})
```

单 sheet（名 `用户`），6 列：`用户名 | 姓名 | 邮箱 | 角色 | 区域 | 部门`，含 **1 行示例**，`角色`列表头挂单元格批注列出 4 个合法值。

---

## B-5 · `POST /api/cpq/users/import`

```
@POST @Path("/import")
@Consumes(MediaType.MULTIPART_FORM_DATA)
@RoleAllowed({"SYSTEM_ADMIN"})
public ApiResponse<UserImportReportDTO> importUsers(@RestForm("file") FileUpload file)
```

（multipart 参数名与现有 `MaterialRecipeResource#import` 保持一致。）

**语义：只新增，不修改，不删除。** 部分成功，不整单回滚。

**400 的三种情形**（"文件本身不可用"，一行都没处理）：

| 情形 | code | message |
|---|---|---|
| 非 xlsx / 解析失败 | `IMPORT_FILE_INVALID` | 请上传 .xlsx 文件 |
| 前 6 列表头不符 | `IMPORT_HEADER_INVALID` | 表头不符合模板要求，请下载新模板 |

⚠️ **只有表头、0 行数据 ≠ 400**，正常返回 200 + 三个计数为 0 的报告。

**逐行校验（不通过 → 跳过该行，写进 `skipped`，其余行照常处理）**

| 判据 | 跳过原因文案 |
|---|---|
| 用户名为空 | `用户名为空` |
| 用户名在**库中**已存在 | `用户名已存在` |
| 用户名在**本文件内**重复 | `文件内用户名重复，已取首行` |
| 用户名长度 > DB 列长 | `用户名超长（最多 N 字符）` |
| 姓名为空 | `姓名为空` |
| 邮箱格式非法 | `邮箱格式不合法：<原值>` |
| 角色不在 4 个合法值内 | `角色不合法：<原值>` |

**软提示（行**照常创建**，写进该行的 `hint`）**

| 判据 | 提示文案 |
|---|---|
| 区域名填了但匹配不到 | `区域未匹配：<原值>` |
| 部门名填了但匹配不到 | `部门未匹配：<原值>` |

🚫 区域/部门匹配不上**不许拒绝整行** —— 两表当前均 0 条，一拒就一个人都导不进来。

**响应 `UserImportReportDTO`**

```jsonc
{
  "totalRows": 8,
  "createdCount": 3,
  "skippedCount": 4,
  "elapsedMs": 142,
  "created": [                       // 仅本次新建的用户，含初始密码
    { "rowNum": 2, "username": "t260902a", "fullName": "张明",
      "role": "SALES_REP", "roleLabel": "销售代表",
      "initialPassword": "Kv7#mQ2xLp9d", "hint": null }
  ],
  "skipped": [
    { "rowNum": 4, "username": "admin", "reason": "用户名已存在" }
  ]
}
```

🚨 **`initialPassword` 只在本响应出现一次**：不落库明文、**不写任何日志**、不进导出文件。
密码生成**必须复用现有创建用户路径**的同一套生成器与哈希逻辑（`UserService` 里 `initialPassword` 的产出点），🚫 不许新写一套。
新用户 `status='ACTIVE'`、`is_first_login=true`（沿用既有语义，首登强制改密）。

**性能**：N 行导入的 SQL 条数必须与 N **无关**（一次批量查重、批量 INSERT）。🚫 循环体内查库。
