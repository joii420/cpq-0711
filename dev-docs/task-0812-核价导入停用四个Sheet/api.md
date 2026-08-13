# api · 核价导入停用四个 Sheet

> 上游依据：本目录 `需求文档.md`。

---

## 0. 结论：本任务无接口契约变更

**方法、路径、鉴权、请求参数、响应结构、错误码 —— 六项全部不变。** 仅两个既有端点的**响应数据量**随后端口径收敛而变化（数组元素数 24 → 20 / xlsx 内 Sheet 数 24 → 20）。

按 `dev-docs/任务平台规则.md` §2.4，无契约变更的任务**不回写 `main-api.md`**，改为在 `test-report.md` 中写明"本次无契约变更，无需回写 main-api.md"。

本文件仍逐条列出这两个端点，用途有二：① 让前后端在开工前对齐"到底哪些变、哪些不变"，避免前端预防性地改适配代码；② 作为测试用例的契约依据。

---

## API-1 核价基础数据导入

`POST /api/cpq/basic-data-import/v6/pricing`

- **实现**：`BasicDataImportV6Resource#importPricing`（`resource/BasicDataImportV6Resource.java:97-113`）
- **鉴权**：`@RoleAllowed({"SALES_MANAGER","SYSTEM_ADMIN"})` —— **不变**
- **Content-Type**：`multipart/form-data` —— **不变**

### 请求参数（不变）

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `file` | form-data | `.xlsx` 文件 | 是 | 核价基础数据 Excel。**停用后仍接受含 24 Sheet 的旧文件**，多出的 4 页被静默忽略（需求文档 D-4） |

### 响应结构（结构不变，数据量变）

`ApiResponse<ImportResultDTO>`：

| 字段 | 类型 | 变化 |
|---|---|---|
| `importRecordId` | UUID | 不变 |
| `systemType` | String | 恒为 `"PRICING"`，不变 |
| `status` | String | `SUCCESS` / `PARTIAL` / `FAILED`，判定规则不变 |
| `totalSuccessRows` | int | **数值变小**（不再累计 4 个已停用 Sheet 的成功行） |
| `totalFailedRows` | int | **数值变小**（同上） |
| `sheetResults` | `SheetResultDTO[]` | **长度 22 → 18**；不再包含 `元素核价价格表` / `材料核价价格表` / `核价版本` / `宏丰-客户料号对应关系` 四个元素（FR-5） |

> ⚠️ **条目数 ≠ Sheet 数（2026-08-12 实测更正，本文件原写「24 → 20」有误）**：P16+P17、P19+P20 各走一个合并 bean，**每对只产出 1 条**结果条目（`PricingImportService.java:107` / `:124`），条目名为 `来料其他费用（比例）+来料其他固定费用(合并)` 与 `成品其他比例费用+成品其他固定费用(合并)`。因此：
>
> | | 循环项 | 合并项 | `sheetResults` 条目数 | 覆盖 Excel Sheet 数 |
> |---|---|---|---|---|
> | 停用前 | 20 | 2 | **22** | 24 |
> | 停用后 | 16 | 2 | **18** | 20 |
>
> 22 是 task-0812 之前就有的既有行为，非本次引入。断言时用 **Δ = −4 + 4 个停用 Sheet 名不出现**，不要硬编码"20"。

`SheetResultDTO` 元素结构**完全不变**：`sheetName` / `totalRows` / `successRows` / `failedRows` / `errors[]` / `writtenCounts{}`。

### 响应示例（停用后）

```json
{
  "success": true,
  "data": {
    "importRecordId": "…",
    "systemType": "PRICING",
    "status": "SUCCESS",
    "totalSuccessRows": 128,
    "totalFailedRows": 0,
    "sheetResults": [
      { "sheetName": "单重",     "totalRows": 12, "successRows": 12, "failedRows": 0,
        "errors": [], "writtenCounts": { "material_master": 12 } },
      { "sheetName": "汇率管理表", "totalRows": 3,  "successRows": 3,  "failedRows": 0,
        "errors": [], "writtenCounts": { "exchange_rate_v6": 3 } },
      { "sheetName": "来料其他费用（比例）+来料其他固定费用(合并)", "totalRows": 2, "successRows": 2,
        "failedRows": 0, "errors": [], "writtenCounts": { "unit_price": 2 } }
    ]
  }
}
```

> 注意示例中**没有**「元素核价价格表」等四项 —— 这正是 FR-5 / AC-3 的断言点。

### 错误码（不变）

| 码 | 触发条件 |
|---|---|
| 400 | `file` 为空 |
| 401 | 未登录（`sessionHelper.getCurrentUserId` 返 null） |
| 403 | 角色不在 `SALES_MANAGER` / `SYSTEM_ADMIN` |
| 500 | 解析/写库异常，消息前缀 `核价基础数据导入失败: ` |

**停用的 4 个 Sheet 不产生任何新错误码** —— 存量文件带这几页不报错、不进 `errors[]`、不影响 `status`。

### 与 FR 的对应

FR-1 / FR-2 / FR-3 / FR-4 / FR-5 / FR-9

---

## API-2 核价基础数据空模板下载

`GET /api/cpq/basic-data-import/v6/pricing/template`

- **实现**：`BasicDataImportV6Resource#pricingTemplate`（`resource/BasicDataImportV6Resource.java:124-135`）
- **鉴权**：`@RoleAllowed({"SALES_REP","SALES_MANAGER","PRICING_MANAGER","SYSTEM_ADMIN"})` —— **不变**（下载空模板无副作用，故比导入端点宽）
- **请求参数**：无 —— 不变

### 响应（结构不变，内容变）

- `Content-Type`: `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` —— 不变
- `Content-Disposition`: `attachment; filename="pricing_basic_data_template.xlsx"` —— 不变
- **响应体是裸 xlsx 字节流，不包 `ApiResponse`** —— 不变（与 `process-master/import/template`、`material-recipes/import/template` 同约定）
- **变化**：xlsx 内 Sheet 数 **24 → 20**，Sheet 顺序与表头对其余 20 个逐字不变（FR-6 / AC-1 / AC-2）

### 停用后的 Sheet 顺序（= `PricingHandlerCatalog.all()` 顺序）

```
汇率管理表 / 物料BOM / 物料与元素BOM / 产能 / 设备折旧成本 / 生产设备能耗 /
辅助设备能耗 / 模具工装成本 / 生产耗材BOM / 包装材料BOM / 来料加工费 /
来料其他费用（比例）/ 来料其他固定费用 / 加工费&组装费 / 成品其他比例费用 /
成品其他固定费用 / 电镀方案 / 电镀成本 / 其他外加工成本 / 单重
```

### 错误码（不变）

| 码 | 触发条件 |
|---|---|
| 401 | 未登录 |
| 403 | 角色不在白名单 |
| 500 | 模板生成异常 |

### 与 FR 的对应

FR-6

---

## 3. 前后端约定

1. **停用清单只存在于后端**（`PricingHandlerCatalog` / `PricingImportService`）。前端**不得**硬编码停用 Sheet 名去过滤 `sheetResults` —— 后端给什么就渲染什么，否则日后恢复某个 Sheet 时前端会成为第二个需要改的地方（口径分叉）。
2. 前端**不需要**任何接口适配代码改动，只改文案（见 `fronttask.md`）。
3. 若后续需要恢复某个已停用 Sheet：**只改后端两个 List**，接口契约与前端均无需变更。
