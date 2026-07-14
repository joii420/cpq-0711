# 接口文档 — 选配模板和报价单选配功能（task-0712）

> 前后端共同契约。依据 `需求说明.md`（决策 D1–D16 / §4.5 / §4.6）与《报价系统Excel导入落库方案 V3.4》。
> 图例：🟢 现有可直接复用 · 🟡 现有需改造 · 🔴 本次新增。

## 0. 通用约定
- **Base**：`/api/cpq`；前端经 Vite proxy(5174)→后端(8081)。
- **鉴权**：现有 JWT；未登录返 401（非 404）。
- **响应包络**：多数返 `ApiResponse<T>`：`{ "success": boolean, "data": T, "message": string|null }`。`configure-product` 系列直接返 DTO（沿用现状，见各节标注）。
- **金额精度**：计算列/列小计/页签合计 4 位；最终产品小计 + 对外导出 2 位（沿用 `cpq-decimal-display-policy`，本次不改）。
- **N+1 禁令**：列表/候选/3D 预览/指纹一律批量或 JOIN（AC 硬指标）。

---

## 1. 选配模板管理 🟢（现有 `/api/cpq/sel-templates`）

### 1.1 列表 `GET /sel-templates`
返回 `ApiResponse<SelTemplateDTO[]>`。`SelTemplateDTO`：
```jsonc
{ "id":"uuid","industryCode":"AUTO","name":"汽车选配","status":"ACTIVE","version":3,
  "items":[ { "paramTypeCode":"MATERIAL","enabled":true,"sortOrder":1,"allowedValues":["SS304","H62"] } ] }
```

### 1.2 参数池 `GET /sel-param-types` + 候选 `GET /sel-param-types/{code}/candidates`
- `sel-param-types` → `SelParamTypeDTO[]`：`{ code, name, valueMode(single|multi|adjust), dataSourceKey, persistHandlerKey, sortOrder }`（种子 MATERIAL/ELEMENT/PROCESS）。
- `{code}/candidates` → `ParamCandidateDTO[]`：`{ key, label }`（材质取材质库、工序取工序库；adjust 型无候选）。
- **前端**：选配模板编辑抽屉、选配添加子框的材质/工序候选均取此；候选列表带前端过滤（D14）。

### 1.3 详情 `GET /sel-templates/{id}` / 保存 `POST /sel-templates` / 删除 `DELETE /sel-templates/{id}`
- 保存请求 `SelTemplateUpsertRequest`：`{ industryCode, name, status, items:[{paramTypeCode,enabled,allowedValues[]}] }`；`industry_code` UNIQUE（一行业一套，D7）。
- 停用 = `status='INACTIVE'` 的 upsert（或列表工具栏动作）。

### 1.4 有效模板解析 `GET /sel-templates/effective?customerNo=xxx` 🟢
返回 `ApiResponse<EffectiveTemplateDTO>`（选配添加抽屉打开即调，D6）：
```jsonc
{ "customerNo":"C001","resolvedIndustryCode":"AUTO","usedDefault":false,"templateId":"uuid",
  "hasTemplate":true,
  "params":[ { "paramTypeCode":"MATERIAL","name":"材质","valueMode":"single",
               "effectiveValues":[{"key":"SS304","label":"不锈钢304"}] } ] }
```
- 兜底链：客户行业模板 → `__DEFAULT__` → `hasTemplate=false`（前端据此显示"缺少选配模板"空态）。

---

## 2. 报价单 · 从已有产品添加 🔴（新增）

### 2.1 已有产品列表 `GET /api/cpq/quotations/{quotationId}/existing-products`
> 数据源 `material_customer_map`，**按本报价单客户过滤**（后端从 quotation 取 customer_no，前端不传客户）。

Query（全部可选，服务端 AND 组合、模糊）：`customerProductNo` / `salesPartNo` / `productName` / `spec` / `page` / `size`。

返回 `ApiResponse<PagedResult<ExistingProductDTO>>`：
```jsonc
{ "success":true, "data":{ "total":128, "items":[
  { "materialNo":"SP-10110001",           // 销售料号(=material_customer_map.material_no)
    "customerProductNo":"CPN-8899",        // 客户产品编号
    "productName":"阀体总成",               // 品名(customer_material_name)
    "spec":"DN50",                          // 规格(映射见 §2.3)
    "customerMaterialName":"阀体",
    "has3d":true,                           // 该料号是否配了 3D(is_current)，供列表小图标
    "thumbnailUrl":"/files/model/....png"   // 缩略图(有则给，无则 null)
  } ] } }
```
- **N+1 防治**：产品列表与 3D(`model_config`) 一次 LEFT JOIN（`subject_type='SALES_PART' AND subject_key=material_no AND is_current`），禁逐行查 3D。
- ⚠️ **必须 `WHERE customer_product_no IS NOT NULL`**（F005）：选配发号会往 `material_customer_map` 插 `customer_product_no=NULL` 占位行，不过滤会污染本列表。

### 2.2 加入报价单
- 复用现有报价单行追加机制：前端拿选中料号，套报价单已绑客户报价模板生成 line item（沿用 `BulkImportPartsDrawer.buildLineItemFromTemplate` / 现有导入落行路径）。**本抽屉不新建落库端点**，直接走现有"从基础数据加入行"链路（D8：直接加成品料号）。后端确认现有批量加入端点可复用（backtask B4 核对）。

### 2.3 规格(`spec`)字段映射 ✅（架构决策3-A 定稿）
`material_customer_map` 无规格列 → LEFT JOIN `material_master`(`material_no`)：`spec = COALESCE(NULLIF(specification,''), dimension)`（specification 语义优先，测试期 dimension 兜显示）。`spec` 过滤对同一表达式模糊匹配。JOIN 命中唯一索引，无 N+1。

---

## 3. 报价单 · 选配添加（运行时）🟡（现有 `/api/cpq/configure-product`，落库需改造）

### 3.1 指纹预查 `POST /configure-product/lookup-fingerprint` 🟢
用于汇总步的"指纹命中/新建"演示与真实带出。
- 请求 `LookupFingerprintRequest`（客户 + 选配投影）；返回 `LookupFingerprintResponse`：命中则含既有销售料号 + 快照（`reusedFromExisting`），前端据此切"料号 3D"。

### 3.2 提交选配 `POST /configure-product/quotations/{quotationId}` 🟡
- 请求 `ConfigureProductRequest`（现有结构，适配明细表 D11）：
```jsonc
{ "productType":"SIMPLE|COMPOSITE",           // 见 §3.3 判定
  "parts":[                                   // 明细表每行 = 一个材质料号
    { "recipeCode":"SS304", "elements":[{"elementCode":"Ni","pct":8.1}],
      "processIds":["uuid-工序"], "unitWeightGrams":12.5, "quantity":1 } ],
  "compositeProcesses":[ { "defCode":"ASSY_WELD", "participatingPartIndexes":[0,1], "params":{} } ] }
```
- 返回 `ConfigureProductResponse`：生成/复用的报价料号 + line items（前端追加到 Step2）。
- **落库**：见 §5 + backtask B2（改造为等价导入完整落库）。

### 3.3 SIMPLE / COMPOSITE 判定（D11+D12，✅ 架构决策1-A 定稿）
- 明细表 `Σqty = Σ 各行 quantity`。**后端按 Σqty 兜底裁决，前后端同口径**：`Σqty==1 → SIMPLE`；`Σqty≥2 → COMPOSITE`。
- 单行 qty≥2 = **父 COMPOSITE + 1 个去重子件 `composition_qty=qty`**（不展开多子件）；`compositeProcesses[].participatingPartIndexes` 允许单去重子件（`[0]`）。
- **前端**：Σqty 计算与后端一致（单行 qty2 也发 COMPOSITE），组合工艺区在 Σqty≥2 出现。

### 3.4 组合工艺候选 🟡（改造，✅ 架构决策2-2A 定稿）
`GET /api/cpq/composite-processes` → 改读工序库 `process_master WHERE process_category='ASSEMBLY'`（现网实值，非"组合工艺"）。DTO：`{ code(=process_no), name(=process_name), currency, unit, defectRate }`（去 icon/paramSchema，放弃参数化）。
- **标识锚点 `process_master.process_no`**，五处一致（候选/前端选值/指纹CPROC/`capacity.process_no`/`quotation_line_composite_process.def_code`）。`composite_process_def` 保留给 v0.4、选配不再引用。
- 前端：组合工艺多选带过滤（D14）。

---

## 4. 配置中心 · 3D 模型配置 🔴（新增 `model_config`）

> 新表 `model_config` / `model_config_file`（D4，弃旧 `mat_part_model`）。Base：`/api/cpq/model-configs`。

### 4.1 列表 `GET /model-configs?subjectType=SALES_PART|MATERIAL&keyword=&page=&size=`
返回 `ApiResponse<PagedResult<ModelConfigDTO>>`：
```jsonc
{ "id":"uuid","subjectType":"MATERIAL","subjectKey":"SS304","subjectLabel":"不锈钢304",
  "version":3,"isCurrent":true,"label":"阀体v3","glbUrl":"/files/..glb","thumbnailUrl":"/files/..png",
  "sizeKb":2400,"meshCount":128,"vertices":34000,"uploadedAt":"2026-07-13T..","uploadedBy":"uuid" }
```
- 按 `subjectType` 分 Tab；同 `subject_key` 多版本，仅一条 `is_current`。

### 4.2 上传 `POST /model-configs`（multipart）
字段：`subjectType`、`subjectKey`（销售料号/材质配方码，可输入过滤 D14）、`label`、`glbFile`(.glb)、`thumbnailFile`(可选 png/jpg)、`setCurrent`(bool，"上传并设为当前" vs "仅上传为历史版本")。
- 版本号 = 该 subject 现有 max(version)+1；`setCurrent=true` 时把同 subject 旧 `is_current` 降级。
- 返回新建 `ModelConfigDTO`。

### 4.3 版本操作
- 历史版本 `GET /model-configs/versions?subjectType=&subjectKey=` → `ModelConfigDTO[]`（含历史）。
- 设为当前 `PUT /model-configs/{id}/set-current` → 同 subject 其余降级、本条置 `is_current`。
- 删除 `DELETE /model-configs/{id}`（危险动作，前端二次确认）。

### 4.4 选配运行端查 3D `GET /model-configs/current?subjectType=&subjectKey=` 🔴
> 供两个添加抽屉带出 3D（D15）。返回该对象 `is_current` 记录（无则 `data=null` → 前端占位"未配置 3D 模型"）。
- 材质 3D：`subjectType=MATERIAL&subjectKey=配方码`（选材质实时调）。
- 料号 3D：`subjectType=SALES_PART&subjectKey=销售料号`（从已有产品选中 / 指纹命中）。
- 缓存策略：维护端"设为当前/更新"后需失效，保证下次选配即取新版本（D15，不得陈旧）。

---

## 5. 选配落库契约（后端权威，前端不感知）

选配提交（§3.2）后端按《报价系统Excel导入落库方案 V3.4》做**等价导入的完整落库**（D16 / 需求 §4.6）。逐表逐列见 `backtask.md B2`。摘要：

| 选配内容 | 表 | 关键列 |
|---|---|---|
| 材质/物料BOM(§3) | `material_bom`(头)+`material_bom_item`+`material_master` | `system_type=QUOTE,bom_type=MATERIAL`；子表 seq_no/component_no/component_usage_type/rough_weight/net_weight/scrap_rate/defect_rate |
| 元素(§4) | `element_bom`(头)+`element_bom_item` | `characteristic=2000`（同主件不同组成则+1）；子表 component_no=元素/content/scrap_rate/composition_qty/base_qty/issue_unit |
| 工序(§10) | `unit_price` | `price_type=PROCESS,cost_type=自制加工费`；finished_material_no/seq_no/code/operation_no/pricing_price/cost_ratio/currency/unit |
| 组合工艺(§14) | `capacity` | material_no/seq_no/process_no(=process_master.process_no)/fixed_cost/currency/**capacity_unit**(非 unit)/default_defect_rate |

**+ 选配专属**：指纹匹配（`sel_part_signature` 客户维度 UNIQUE，命中复用料号幂等）+ 发号（`quoteAllocator` mint）。**同一事务**（签名可见 ⇔ 数据可见）。

---

## 6. 错误码约定
| 场景 | HTTP | message |
|---|---|---|
| 未登录 | 401 | 未授权 |
| 缺少选配模板（后端硬阻断时） | 200 + `hasTemplate=false` | 前端渲染空态，不当错误 |
| 选配无客户不能发号 | 400 | 选配需客户（报价料号内嵌客户码） |
| 指纹分隔符碰撞（码值含 `|=,:∅`） | 400 | 码值非法（列出字段） |
| 自制加工费"两空行"重复（§10 规则3） | 400 | 数据非法（列出成品） |
| 3D 对象无当前版本 | 200 + `data=null` | 前端占位"未配置 3D 模型" |
