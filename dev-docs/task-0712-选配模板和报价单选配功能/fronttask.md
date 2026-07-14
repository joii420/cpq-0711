# 前端任务文档 — 选配模板和报价单选配功能（task-0712）

> 权威依据：`需求说明.md`（决策 D1–D16 / §4.5 / §4.6）+ `UI设计说明.md`（逐页规格）+ `api.md`（接口契约）+ `prototypes/*.html`（4 个定稿原型，视觉/交互标准）。
> 本文档只做前端任务拆解，不写实现代码；不改后端、不改 PRD。

---

## 0. 强制规范（本次所有任务通用，不重复列在每节）

1. **抽屉不用弹窗**：新建/编辑/详情/多步选择一律 `Drawer`，`placement="right"`。`AddProductModal.tsx` 现状是 `qt-modal-overlay` 自定义居中弹层（**不是** AntD Modal，也不是 Drawer）——本次必须migrate 为 AntD `Drawer`。
2. **列表统一 `SelectableTable` + 工具栏动作模式**（`docs/列表操作规范.md`）：行内不放动作按钮，只留主入口链接；动作 `enabledWhen` 选择驱动，禁用态 hover 提示原因；危险动作（删除）`needsConfirm` 二次确认列出所选项；批量操作用 `runBatch` 聚合失败明细。参照现成范例 `cpq-frontend/src/pages/config/SelTemplateManagement.tsx`。
3. **小数显示口径不改**：计算列/列小计/页签合计 4 位、最终产品小计+导出 2 位（`cpq-decimal-display-policy`）；本次新增内容（数量、元素含量%、单重 g）**不新造精度规则**，沿用各自现有输入组件的默认精度（`InputNumber` 已有 `step`/精度约定，照抄现状，不新增）。
4. **中文文案**：按钮/提示/错误/空态一律中文。
5. **过滤通则（D14）**：选配相关的多选/单选候选列表——材质、工序、组合工艺、3D 绑定对象——统一配过滤/搜索框（前端本地过滤即可，候选量级不大）。

### 边界（禁止越界）

- 不改报价卡片渲染管线（`ProductCard` / `ReadonlyProductCard` / `ExcelView` / `LinkedExcelView` 等）、不新增/改动 `field_type`（不触发 AP-44 协议）。
- 报价单其余功能（Step1/Step3/导出/对比视图/核价视图等）禁止改动。
- 3D 仅出现在「从已有产品添加」「选配添加」两个抽屉内，**不进**报价卡片各视图 / Excel / PDF（D9）。
- v0.4 独立 3D 选配器（`configurator` / `product_config_*` / `mat_part_model`）不动、不复用为主存储（D4）。
- 选配落库（§4.6 / D16）是后端任务，前端只按 `api.md §3.2` 请求结构提交，不感知落库细节。

---

## 1. 现状盘点（codegraph + 实读源码核对，非臆测）

| 项 | 路径 | 现状 |
|---|---|---|
| 选配模板管理页 | `cpq-frontend/src/pages/config/SelTemplateManagement.tsx` | **已存在**，`SelectableTable` 骨架 + 新建/编辑 Drawer(720) 已完成约 90%；工具栏动作只有 编辑/删除，**缺“停用/启用”** |
| 选配模板 Service | `cpq-frontend/src/services/selTemplateService.ts` | **已存在** `listParamTypes/candidates/list/getById/upsert/delete`；**缺 `effective(customerNo)`**（api.md §1.4 要求，`EffectiveTemplateService` 后端已现成，前端未接） |
| 从已有产品添加 | `cpq-frontend/src/pages/quotation/AddProductModal.tsx` | **已存在**，但是三步向导（选 v3 `product` 表产品 → 选工序 → 选模板），自定义 `.qt-modal-overlay` 居中层（非 Drawer）——与 D8/api.md §2 的新语义（`material_customer_map` 客户产品列表 + 4 过滤 + 3D + 多选）**完全不同，需整体重写** |
| 选配添加抽屉 | `cpq-frontend/src/pages/quotation/ConfigureProductDrawer.tsx` | **已存在**，AntD `Drawer(960)` 承载，但内部是 5 段 `globalStep` × 3 段 `subStep` 的**逐配件**向导（产品类型→料号匹配→材质→工序→[配件数量]→[组合工艺]→汇总）；与 D11 的“空白明细表 + 新增子框回填一行 + 数量合计≥2 出现组合工艺”模型**结构性不同，需整体重构** |
| 选配添加子组件 | `cpq-frontend/src/pages/quotation/configure/*.tsx` | `Step0ProductType` `Step1SearchPart` `Step2Material` `Step3Process` `Step4CompositeProcess` `Step5Summary` `StepAccessoryQuantity` `AccessoryProgressBar` 均存在；**部分逻辑可复用**（见 F5 拆解），**部分整体废弃** |
| 选配运行 Service/类型 | `cpq-frontend/src/services/configureProductService.ts` + `cpq-frontend/src/types/configure.ts` | **已存在** `lookupFingerprint` / `configureProduct`；`ConfigureProductRequest.parts[]` 字段（`recipeCode/elements/processIds/unitWeightGrams/quantity/partMode/existingHfPartNo`）与 api.md §3.2 示例**基本对齐**，`partMode='existing'/'custom'` 概念在新模型下可能不再需要（开放点，见 §8） |
| 材质候选/详情 | `cpq-frontend/src/services/materialRecipeService.ts` | **已存在** `list()`（全量字典）/`detail(id)`/`loadForExisting(hfPartNo)`；F5 材质候选**要换成模板 `effectiveValues` 限定**，但元素详情仍需 `detail(id)`（id vs candidate.key 语义需核对，见 §8） |
| 组合工艺候选 | `cpq-frontend/src/services/compositeProcessService.ts` | **已存在** `list()` 返回 `CompositeProcessDef[]`；D13/R4 是**后端**把数据源换成 `process_master(process_category='ASSEMBLY')`、DTO `code=process_no`（去 paramSchema），前端契约形状基本不变，仅需回归验证 + 组合工艺多选加过滤(D14) |
| 3D 模型配置 | 无 | **全新**，新表 `model_config`/`model_config_file`，前端无任何现成代码；v0.4 `PartModelList.tsx`/`partModelService.ts` 结构可作**交互参考**（4 步上传向导），但不复用其 service/类型（D4 弃用 `mat_part_model` 为主存储） |
| `SelectableTable` | `cpq-frontend/src/components/SelectableTable.tsx` | **已存在**，`ToolbarAction<T>` + `runBatch` 齐全，直接复用 |
| 报价单添加产品入口 | `cpq-frontend/src/pages/quotation/QuotationStep2.tsx:3397-3431` | `Dropdown` 已有「从已有产品添加」「选配添加」两项，分别触发 `onAddProduct?.()` / `onAddConfigured?.()`；本次**不改**这段（入口正确），只改两个抽屉内部 |
| 抽屉挂载点 | `cpq-frontend/src/pages/quotation/QuotationWizard.tsx:1556-1570` | `<AddProductModal open onConfirm={(lineItem)=>...单个}/>` + `<ConfigureProductDrawer .../>`；F4 把 `onConfirm` 签名从单条改多条后，此处 wiring 要同步改 |
| 批量加入复用点 | `cpq-frontend/src/pages/quotation/BulkImportPartsDrawer.tsx:176` | `export function buildLineItemFromTemplate(tmpl, part: CustomerPartCandidate): LineItem` —— **F4 直接复用**此函数把 `ExistingProductDTO` 映射成 `LineItem`，不新造构建逻辑 |
| 路由/菜单 | `cpq-frontend/src/router/index.tsx` / `cpq-frontend/src/layouts/MainLayout.tsx:85-95` | 「配置中心」菜单已有 `组件管理/模板管理/选配模板管理`；F3 需新增一条 `3D 模型配置` |
| E2E 现状 | `cpq-frontend/e2e/composite-product-flow.spec.ts` | **深度绑定旧向导** DOM 结构（逐 `drawerNext()` 走 `globalStep`/`subStep`，断言"配件 1/2""料号匹配"等文案）。F5 重构后**该 spec 必然全部失效**，需要 cpq-tester 同步重写（前端任务边界内只做手工冒烟，不写正式测试代码，但必须把这个依赖显式交接） |

---

## 2. 任务总览与依赖顺序

```
F1 基础设施(类型+Service)  ← 唯一前置，最先做
   │
   ├─→ F2 选配模板管理页面打磨      (可与 F3/F4/F5 并行)
   ├─→ F3 配置中心·3D模型配置新页   (可与 F2/F4/F5 并行)
   ├─→ F4 报价单·从已有产品添加     (依赖 F1 的 existingProduct 类型/service)
   └─→ F5 报价单·选配添加重构      (依赖 F1 的 configure 类型扩展 + selTemplateService.effective + modelConfigService.current)
                │
                ▼
        F6 集成收尾 & 冒烟自测（依赖 F4 + F5 都完成，因为两者都要改 QuotationWizard.tsx 同一段 wiring）
```

建议执行顺序：**F1 → {F2, F3, F4, F5 并行} → F6**。F2/F3 风险低可优先验收，F5 工作量与风险最大，建议单独排期、留足回归时间。

---

## F1 — 基础设施：类型与 Service 层改造

**依赖**：无。**产出**：后续 4 个任务都要用到的类型定义与 API 封装。

### 1.1 新建 `cpq-frontend/src/types/modelConfig.ts`

对齐 api.md §4：

```ts
export type ModelSubjectType = 'SALES_PART' | 'MATERIAL';

export interface ModelConfigDTO {
  id: string;
  subjectType: ModelSubjectType;
  subjectKey: string;        // 销售料号 material_no / 材质配方码
  subjectLabel?: string;     // 展示用中文名（后端 JOIN 出）
  version: number;
  isCurrent: boolean;
  label?: string;
  glbUrl?: string | null;
  thumbnailUrl?: string | null;
  sizeKb?: number;
  meshCount?: number;
  vertices?: number;
  uploadedAt?: string;
  uploadedBy?: string;
}
```

### 1.2 新建 `cpq-frontend/src/services/modelConfigService.ts`

```
list(params: { subjectType; keyword?; page?; size? }) → GET /model-configs
upload(formData: FormData) → POST /model-configs  (multipart: subjectType/subjectKey/label/glbFile/thumbnailFile/setCurrent)
versions(params: { subjectType; subjectKey }) → GET /model-configs/versions
setCurrent(id) → PUT /model-configs/{id}/set-current
remove(id) → DELETE /model-configs/{id}
current(params: { subjectType; subjectKey }) → GET /model-configs/current   // data 可能为 null
```

`current()` 是 F4/F5 两个抽屉共用的“实时带出”端点，**必须做防抖/取消**（选材质/切行时会连续触发，避免竞态导致预览图闪回旧值——用请求序号或 `AbortController` 丢弃过期响应，不引入额外全局缓存层，保持简单）。

### 1.3 新建/改造：已有产品列表

**位置决策**：加进 `cpq-frontend/src/services/quotationService.ts`（与既有 `listCustomerPartCandidates` 同域，避免 service 碎片化），新增方法：

```
listExistingProducts(quotationId: string, params: {
  customerProductNo?; salesPartNo?; productName?; spec?; page?; size?;
}) → GET /quotations/{quotationId}/existing-products
```

对应类型加进 `cpq-frontend/src/types/quotation.ts`（若无同名文件则新建 `cpq-frontend/src/types/existingProduct.ts`，先用 `codegraph_files` 确认 `types/` 目录现状再定文件名）：

```ts
export interface ExistingProductDTO {
  materialNo: string;
  customerProductNo?: string;
  productName?: string;
  spec?: string;
  customerMaterialName?: string;
  has3d: boolean;
  thumbnailUrl?: string | null;
}
```

### 1.4 改造 `cpq-frontend/src/services/selTemplateService.ts`

新增一行：

```ts
effective: (customerNo: string) => api.get('/sel-templates/effective', { params: { customerNo } }) as Promise<any>,
```

对应类型 `EffectiveTemplateDTO`（放进 `cpq-frontend/src/types/configure.ts`，见 1.5）：

```ts
export interface EffectiveTemplateParam {
  paramTypeCode: 'MATERIAL' | 'ELEMENT' | 'PROCESS';
  name: string;
  valueMode: 'single' | 'multi' | 'adjust';
  effectiveValues: { key: string; label: string }[];
}
export interface EffectiveTemplateDTO {
  customerNo: string;
  resolvedIndustryCode?: string;
  usedDefault: boolean;
  templateId?: string;
  hasTemplate: boolean;
  params: EffectiveTemplateParam[];
}
```

### 1.5 改造 `cpq-frontend/src/types/configure.ts`

**不改**现有 `ProductType`/`PartMode`/`PartRequest`/`CompositeProcessRequest`/`ConfigureProductRequest`/`LookupFingerprintRequest`/`SearchPartResult` 的后端契约字段（提交时仍要产出这些形状，除非 backtask 明确调整——见 §8 开放点）。

**新增**前端专用 UI 状态类型（明细表每行的抽屉内部状态，不是请求 DTO，提交前再 map 成 `PartRequest`）：

```ts
export interface SelDetailRow {
  rowId: string;                       // genUUID，前端本地 key
  recipeCode: string | null;
  recipeLabel: string;                 // 中文名，列表显示用
  elementOverrides: Record<string, number>;
  processIds: string[];
  processLabels: string[];             // 中文名，列表显示用
  quantity: number;                    // 默认 1
  unitWeightGrams: number | null;
}

export interface CompositeSelectionState {
  defCode: string;
  name: string;                        // 展示用
}

export interface FingerprintSummaryState {
  checked: boolean;
  matched: boolean;
  hfPartNo?: string;
  snapshot?: LookupFingerprintSnapshot;
}
```

新增 `EffectiveTemplateDTO`/`EffectiveTemplateParam`（见 1.4）一并放在此文件。

### F1 验收标准

- `npx tsc --noEmit` 通过（新文件类型自洽，无循环引用）。
- `modelConfigService`/`selTemplateService.effective`/`quotationService.listExistingProducts` 三个方法能在浏览器 console 手动 `import` 调用并拿到 401（未登录）或 200（登录态），确认 URL 拼接正确（`/api/cpq/model-configs`、`/api/cpq/sel-templates/effective?customerNo=`、`/api/cpq/quotations/{id}/existing-products`）。
- 不涉及页面渲染，无需截图。

---

## F2 — 选配模板管理页面打磨

**依赖**：无（可最先做，风险最低）。
**原型**：`prototypes/原型-选配模板管理.html`。
**改造文件**：`cpq-frontend/src/pages/config/SelTemplateManagement.tsx`（仅打磨，不重写）。

### 差异点（对照 UI设计说明 §1，现状 vs 应有）

| 项 | 现状 | UI设计说明要求 | 动作 |
|---|---|---|---|
| 工具栏动作 | `编辑`（选1行）、`删除`（危险+二次确认） | 还应有 **`停用/启用`**（切状态，选择驱动） | **新增** 一个 `ToolbarAction`：`enabledWhen: sel.length===1`，`onClick` 调 `selTemplateService.upsert({...current, status: toggled})`；不需要 `needsConfirm`（非危险，可逆操作） |
| 空态 | `SelectableTable` 未传 `locale` | “暂无选配模板，点击右上「+ 新建模板」创建” | 传 `locale={{ emptyText: <自定义空态文案+图标> }}` |
| 列表列 | 归属行业(主入口)/模板名/启用参数数/状态 | 同左，已对齐 | 无需改 |
| 新建/编辑抽屉 | 720 宽，行业(编辑禁用)/模板名/状态 + 参数卡片(材质单选池+多选限定 / 元素含量说明文案 / 工序多选限定) | 同左，已对齐 | 无需改，逐条核对无差异 |

### 关键交互（保持不变，仅核对）

- 归属行业下拉含保留项 `__DEFAULT__`(默认模板)/`__GLOBAL__`(通用组合工艺)，编辑态禁用（一行业一套，D7）。
- 参数卡片：勾选启用 → 下拉解禁；`adjust`(元素含量) 类型不显示多选框，只显示说明文案。
- 候选值加载：`ensureCandidatesFor` 懒加载 + 编辑抽屉打开时预加载非 adjust 参数候选（保证已选值不是只显示 key）——现状已实现，不动。

### 前端验收标准

1. 列表：无数据时显示新空态文案；有数据时四列渲染正确，`状态` 标签绿/灰正确。
2. 工具栏：不选行时 `编辑`/`停用启用` 置灰 + hover 提示“请先选择行”；选 2 行时 `编辑`/`停用启用` 置灰（原因文案：“编辑一次只能选一行”/同款）；`删除` 选 ≥1 行即可用，点击后 Modal 列出所选行业名二次确认。
3. 停用/启用点击后列表状态标签实时刷新，无需手动刷新页面。
4. 新建：行业选保留项 `__DEFAULT__`/`__GLOBAL__` 可选中并保存成功；勾选“材质”参数后下拉可选中材质库候选（中文 label，非裸 key）。
5. 编辑：行业列禁用不可改；已保存的 `allowedValues` 回显为中文 label（非 key 裸值）。

---

## F3 — 配置中心 · 3D 模型配置（新页）

**依赖**：F1（`modelConfigService`/`ModelConfigDTO`）。
**原型**：`prototypes/原型-配置中心-3D模型配置.html`。
**新建文件**：
- `cpq-frontend/src/pages/config/ModelConfigManagement.tsx`
- 路由：`cpq-frontend/src/router/index.tsx` 加 `{ path: 'config/model-configs', element: <ModelConfigManagement /> }`（紧邻 `config/sel-templates` 一行）
- 菜单：`cpq-frontend/src/layouts/MainLayout.tsx` “配置中心” `children` 数组追加 `{ key: '/config/model-configs', label: '3D 模型配置', roles: ['PRICING_MANAGER', 'SALES_MANAGER', 'SYSTEM_ADMIN'] }`（沿用选配模板管理同一套角色）

### 涉及 api.md 端点

`GET /model-configs`（分 Tab 列表）/ `POST /model-configs`（multipart 上传）/ `GET /model-configs/versions` / `PUT /model-configs/{id}/set-current` / `DELETE /model-configs/{id}`。

### 组件与状态

- 顶部 `Tabs`：`销售料号模型` | `材质模型`，对应 `subjectType` state（`SALES_PART`/`MATERIAL`），切 Tab 重新拉列表 + 清选中。
- 每个 Tab 一个 `SelectableTable<ModelConfigDTO>`：
  - 列（销售料号 Tab）：销售料号(`subjectKey`) / 模型名(`label`) / 当前版本(`version` + `isCurrent` 标签) / 缩略图(`thumbnailUrl` 小图，无则占位图标) / 大小(`sizeKb`) / 上传时间(`uploadedAt`) / 状态(当前/历史)。
  - 列（材质 Tab）：材质配方码(`subjectKey`) / 材质名(`subjectLabel`) / 其余同上。
  - 工具栏动作：`上传模型`（常驻按钮，不受选择约束，打开上传 Drawer）、`设为当前版本`（`enabledWhen: sel.length===1 && !sel[0].isCurrent`，否则提示“该版本已是当前版本”/“请选择 1 条历史版本”）、`查看历史版本`（`enabledWhen: sel.length===1`，打开只读 Drawer/子表按 `subjectKey` 拉 `versions()`）、`删除`（危险，二次确认，`enabledWhen: sel.length>0`）。
- 上传 Drawer（宽 720）：
  - 字段：`绑定对象`选择框（按当前 Tab 的 `subjectType` 预设；D14 要求**可输入过滤**——用 AntD `Select showSearch optionFilterProp="label"` 而非纯下拉，候选源：销售料号 Tab 从已有 `material_customer_map`/料号候选接口取（沿用 F4 复用的候选源或简单 `Input` 手填——**开放点，见 §8**），材质 Tab 从 `materialRecipeService.list()` 取）。
  - `模型文件 .glb`：`Upload` 拖拽区，选中后本地展示文件名+大小（原型里的 mesh/顶点数是**后端解析后**回填的展示字段，前端上传阶段不预解析，提交后用响应 `meshCount`/`vertices` 回显）。
  - `预览图`：可选 `Upload` png/jpg；“从模型自动截图”按钮在前端阶段可先不实现（后端未提供该能力时置灰+tooltip“该功能暂未开放”，不是必做项，若 backtask 后端明确支持再补）。
  - `模型名 label`：`Input`。
  - 底部两个按钮：`上传并设为当前`(`setCurrent=true`) / `仅上传为历史版本`(`setCurrent=false`)，都调 `modelConfigService.upload(formData)`。
  - 提交用 `multipart/form-data`：`FormData` 塞 `subjectType/subjectKey/label/glbFile/thumbnailFile?/setCurrent`。

### 空态/占位态

- 列表无数据：“暂无{销售料号/材质}模型，点击左上「上传模型」新增”。
- 缩略图缺失：占位色块 + 🧊 图标（复用 UI设计说明 §0.3 的 `.preview3d` 视觉，不强求逐像素还原原型 CSS，语义一致即可）。

### 过滤（D14）

上传 Drawer 内“绑定对象”必须可输入文本过滤，不用纯 `<Select>` 静态下拉。

### 前端验收标准

1. 两个 Tab 各自独立分页/列表，切换不残留上一 Tab 选中态。
2. 上传成功后新记录出现在列表顶部，若 `setCurrent=true` 则同 `subjectKey` 旧记录的“当前”标签自动降级（依赖后端响应/重新拉取列表，前端不用本地强算）。
3. “设为当前版本”仅历史版本可点，点击后该行标签变“当前”、原当前版本变“历史”。
4. “删除”二次确认列出所选对象 `subjectKey` + 版本号。
5. 绑定对象选择框输入关键字能过滤候选（中文名/编码任一命中）。

---

## F4 — 报价单 · 从已有产品添加（改造）

**依赖**：F1（`quotationService.listExistingProducts`/`ExistingProductDTO`/`modelConfigService.current`）。
**原型**：`prototypes/原型-报价单-从已有产品添加.html`。
**改造文件**：
- `cpq-frontend/src/pages/quotation/AddProductModal.tsx`（**整体重写内部实现**，文件名保留不改，因为改名要牵动 `QuotationWizard.tsx` import 且无实质收益；仅内容从“三步向导”换成“列表+3D 预览”）
- `cpq-frontend/src/pages/quotation/QuotationWizard.tsx`（第 1556-1563 行 wiring：`onConfirm` 签名从 `(lineItem: LineItem) => void` 改为 `(lineItems: LineItem[]) => void`，与 `onAddBatch` 走同一条追加+去重逻辑）

### 涉及 api.md 端点

`GET /quotations/{quotationId}/existing-products`（列表+过滤）+ `GET /model-configs/current?subjectType=SALES_PART&subjectKey=...`（3D 预览，F1 已封装）。

### 组件与状态

```
AddProductModalProps {
  open: boolean;
  quotationId: string;      // 新增：查询候选与 3D 都要
  onCancel: () => void;
  onConfirm: (lineItems: LineItem[]) => void;   // 由单条改批量
}
```

- 内部 state：`filters`(4 字段) / `list: ExistingProductDTO[]` / `loading` / `selectedRowKeys: string[]`(rowKey=`materialNo`) / `activeRow: ExistingProductDTO | null`(用于右侧 3D 预览) / `preview: ModelConfigDTO | null` / `previewLoading`。
- 结构：`Drawer(width=960, placement="right")` → 左右两栏（左 60% 表格 + 顶部过滤条，右 40% 3D 预览面板）。

### 关键交互

1. 打开抽屉即拉 `listExistingProducts(quotationId, {})`（后端已按 quotation 的 customer_no 过滤，前端不传客户）。
2. 过滤条 4 个 `Input`：客户产品编号/销售料号/品名/规格，输入后本地防抖 300ms 或直接调接口重查（**推荐调接口**，因为字段是服务端模糊匹配 AND 组合，前端本地过滤语义会和后端不一致——调接口更准确，用 `useEffect` + `debounce` 触发）。
3. 表格：`Table`（可用 AntD 原生 `rowSelection` 多选，因为这里是“选中即加入”场景不涉及变更类动作，不强制套 `SelectableTable` 的工具栏范式——**例外**，理由见 `docs/列表操作规范.md` §12 例外白名单“纯选择、无副作用变更”场景；若评审认为仍应走 `SelectableTable` 也可，用其 `getCheckboxProps` 但底部“加入报价单”按钮不算工具栏动作，是抽屉 `footer`）。
4. 单击行（非勾选框）→ `activeRow` 置为该行 → 调 `modelConfigService.current({subjectType:'SALES_PART', subjectKey: row.materialNo})` → 右侧预览区更新；若无数据（`data=null`）显示“该料号未配置 3D 模型”占位（**不阻断**，勾选/加入照常可用）。
5. 底部：“已选 N 项” + `取消` + `加入报价单`（`disabled: selectedRowKeys.length===0`）。
6. 点击“加入报价单”：
   - 取当前报价单 `customerTemplateId` → `templateService.getById(customerTemplateId)` 拿完整模板；
   - 把选中的 `ExistingProductDTO[]` 逐条映射成 `CustomerPartCandidate` 形状（`partNo: materialNo`, `partName: productName`, `customerProductNo`, `customerPartName: customerMaterialName`；`customerDrawingNo`/`unitWeight`/`hfPartInfo`/`currentVersion` 该 DTO 未提供，留空/undefined，`buildLineItemFromTemplate` 对可选字段有兜底）；
   - 对每条调用 `buildLineItemFromTemplate(tmpl, candidate)`（**直接 import 复用**，不重写这段逻辑）；
   - `onConfirm(lineItems)` 一次性回传数组；
   - `message.success('已加入 N 个产品')` 并关闭抽屉。
7. 若 `customerTemplateId` 为空（理论上 Dropdown 层已挡，见现状盘点，QuotationStep2.tsx 的「从已有产品添加」目前**没有**像「选配添加」那样做 `disabled` 判断——需确认是否也要加同款保护；**建议**在 `handleConfirm` 里兜底 `if (!customerTemplateId) { message.error(...); return; }`，不依赖 Dropdown 层拦截，双保险）。

### 空态/占位态

- 列表加载中：`loading` 骨架（`Table loading` 属性）。
- 无匹配产品：`Table` 空态“暂无匹配产品”。
- 未选中行时右侧预览区：“请选择左侧产品查看 3D 预览”占位（不是错误态）。
- 选中行但无 3D：“该料号未配置 3D 模型”。

### 过滤（D14）

4 个过滤输入框本身即满足 D14（服务端模糊匹配）；无需下拉候选类过滤（不是 select，是 text input）。

### 3D 带出逻辑（D3/D15）

选中/单击某行 → 查 `SALES_PART` + 该 `materialNo` 的 `is_current` 记录 → 展示预览图（缩略图，点“⤢ 交互查看”为增强项，可先做静态提示层“可旋转 3D，增强项”，不强求接入真实 3D 渲染库）。

### 前端验收标准

1. 打开抽屉即看到当前客户产品列表（非全量产品），4 个过滤字段任一输入均生效且可组合。
2. 单击不同行，右侧预览区标题/图片随之切换，无 3D 时占位文案正确、不报错。
3. 多选 3 行后点“加入报价单”，Step2 卡片视图出现 3 张新卡片，字段渲染与「从已有产品」原有加入路径（`BulkImportPartsDrawer`）效果一致（同一模板同一料号渲染结果应相同，因为共用 `buildLineItemFromTemplate`）。
4. 未选客户报价模板时点“加入报价单”给出中文错误提示，不发请求、不崩溃。
5. 关闭抽屉重新打开，过滤条与选中态重置为初始。

---

## F5 — 报价单 · 选配添加（重构为明细表，D11）

**依赖**：F1（`SelDetailRow`/`EffectiveTemplateDTO`/`modelConfigService.current`）。
**原型**：`prototypes/原型-报价单-选配添加.html`。
**风险**：本任务范围内**最大**，涉及整个抽屉的交互模型重写。**必须**在开工前通知 cpq-tester：`composite-product-flow.spec.ts` 现有全部选择器（`Step0ProductType`/逐配件 `drawerNext()`/“配件 1/2”文案）在本任务完成后**全部失效**，需要同步重写（超出前端任务范围，前端只做手工冒烟）。

### 5.1 整体模型变化

| 维度 | 旧模型（现状） | 新模型（D11/D12/D13） |
|---|---|---|
| 顶层结构 | `globalStep`(0-4) × `subStep`(0-2)，逐配件推进 | 单屏：明细表（主体）+ 新增子框（内层弹层）+ 组合工艺条件区 + 汇总区，**不再有跨步骤线性推进** |
| 产品类型 | 用户开局显式选 SIMPLE/COMPOSITE + 配件数 | **不再预选**，由明细表“数量合计”实时判定（=1→SIMPLE，≥2→COMPOSITE，api.md §3.3） |
| 料号来源 | 每配件先“料号匹配”（`Step1SearchPart`，existing/custom 二选一，命中指纹时"复用此料号"提示） | 每行都是新选材质构建（不再有配件级 existing 复用 UI），**指纹匹配挪到整份提交前统一做一次**（汇总区） |
| 材质候选 | `materialRecipeService.list()` 全量字典 | **模板限定**：`effective.params[MATERIAL].effectiveValues`（为空=不限，仍取全量） |
| 工序候选 | 原始 `/processes` 全量字典（`Step3Process`） | **模板限定**：`effective.params[PROCESS].effectiveValues` |
| 数量 | 独立步骤 `StepAccessoryQuantity`（仅 COMPOSITE 才有） | 明细表行内 `InputNumber`，任何产品类型都有，默认 1 |
| 组合工艺 | 独立 `globalStep`（仅 COMPOSITE 且用户已确认类型后才出现） | 明细表下方**条件区块**，数量合计 ≥2 时自动出现/可用，否则灰置提示语 |
| 汇总/指纹 | `Step5Summary` 展示配件卡片列表（每配件单独展示"料号"状态） | 展示"明细表 + 组合工艺摘要 + 一次性指纹匹配结果"，命中则 3D 从“材质 3D”切到“料号 3D” |

### 5.2 组件级拆解

**新建**：
- `cpq-frontend/src/pages/quotation/configure/SelDetailTable.tsx` — 明细表主体（列：# / 材质(含 3D 小缩略图) / 元素含量摘要 / 工序摘要 / 数量(可编辑) / 操作(编辑/删除)；顶部【+ 新增材质料号】；底部“数量合计: N”）。
- `cpq-frontend/src/pages/quotation/configure/AddPartSubDrawer.tsx` — 内层弹层（可用嵌套 `Drawer` 或同容器内的分步 `Card`；**内层用 Drawer 时宽度建议 720，`push={false}` 或走同一 Drawer 内的 3 段式局部状态切换**，避免双层 Drawer 遮罩层叠视觉问题——原型 UI设计说明 §3.2(b) 原话"可用内层抽屉/弹层呈现"，两种实现都可，**建议选内层局部切换（非独立 Drawer 组件）**，实现更简单且避免 AntD 嵌套 Drawer 的层级/ESC 冲突）：
  - 子步骤 1「材质」：候选 = `effective.params[MATERIAL].effectiveValues`，过滤框（复用 `Step2Material.tsx` 的 `q` 过滤 `List` 交互，去掉 `matLocked`/`existingMat` 分支——新模型没有“料号锁定”概念）；选中后调 `modelConfigService.current({subjectType:'MATERIAL', subjectKey: recipeCode})` 刷新右侧 3D。
  - 子步骤 2「元素含量」：按选定材质派生元素列表（复用 `Step2Material.tsx` 的 `materialRecipeService.detail(id)` 加载 + `InputNumber` 微调 + 含量和校验逻辑，**去掉** `isLocked`/`matLocked` 只读分支——新模型元素默认可调）。**开放点**：`detail(id)` 要 `id` 不是 `code`，而候选来自 `effectiveValues` 给的是 `{key,label}`，`key` 语义待核实（见 §8）。
  - 子步骤 3「工序」：候选 = `effective.params[PROCESS].effectiveValues`，过滤框（复用 `Step3Process.tsx` 交互，候选源从 `/processes` 换成 `effectiveValues`）。
  - 底部【确认】→ 把 3 个子步骤状态组装成一条 `SelDetailRow`（`rowId=genUUID()`），`quantity` 默认 1，回调给父组件 `onAddRow(row)`，关闭子框回到明细表。
- `cpq-frontend/src/pages/quotation/configure/CompositeProcessSection.tsx` — 条件区块：`sum(rows.quantity) >= 2` 时可用，否则整块灰置 + 提示“数量合计≥2 时需选择组合工艺”；候选沿用 `compositeProcessService.list()`（复用 `Step4CompositeProcess.tsx` 的过滤 `Card` 交互，D13 由后端换数据源，前端契约不变）。
- `cpq-frontend/src/pages/quotation/configure/SummaryFingerprintPanel.tsx` — 汇总区：明细表摘要 + 组合工艺摘要（如有）+ 指纹匹配结果（`✅ 匹配到已有销售料号 SP-xxxx` / `🆕 将新建选配产品`）+ 右侧 3D 预览常驻（默认跟随最近操作材质，命中料号后切换）。

**改造（提取可复用逻辑，宿主组件废弃）**：
- `Step2Material.tsx` → 逻辑并入 `AddPartSubDrawer` 子步骤 2（不再作为独立 Step 文件挂在 `ConfigureProductDrawer` 顶层）。
- `Step3Process.tsx` → 逻辑并入 `AddPartSubDrawer` 子步骤 3，数据源改为 `effectiveValues`。
- `Step4CompositeProcess.tsx` → 逻辑并入 `CompositeProcessSection.tsx`。
- `Step5Summary.tsx` → 逻辑并入 `SummaryFingerprintPanel.tsx`（去掉“配件卡片逐条 existing/reused”展示，因为新模型没有这个概念）。

**整体废弃（删除，不再被任何组件引用）**：
- `Step0ProductType.tsx`（不再预选产品类型）
- `Step1SearchPart.tsx`（不再有配件级“料号匹配/复用此料号”交互）
- `StepAccessoryQuantity.tsx`（数量已内嵌明细表行）
- `AccessoryProgressBar.tsx`（不再有逐配件进度导航）

> 删除前用 `codegraph_callers` 确认这 4 个文件除 `ConfigureProductDrawer.tsx` 外无其他引用者，再删——避免误删被其他页面复用的公共组件（初步 grep 未见其他引用，但删除前仍需二次确认）。

**主容器重写**：`ConfigureProductDrawer.tsx` 整体重写为：
```
Drawer(width=960)
 ├─ 前置：打开即调 selTemplateService.effective(customerNo)
 │    ├─ hasTemplate=false → 空态"缺少选配模板" + "去配置"链接（导向 /config/sel-templates）
 │    └─ hasTemplate=true  → 往下渲染
 ├─ SelDetailTable（+ AddPartSubDrawer 内层交互）
 ├─ CompositeProcessSection（条件区块）
 └─ SummaryFingerprintPanel + 底部【取消】【确认加入】
```
`goNext`/`goPrev`/`globalStep`/`subStep`/`ci`/`furthestCi`/`checkFingerprintAndAdvance`（逐配件指纹提示）等状态与函数**整体废弃**，替换成明细表行数组 `rows: SelDetailRow[]` + `compositeSelections: CompositeSelectionState[]` 的扁平状态。

### 5.3 提交映射（明细表 → 现有 `ConfigureProductRequest`）

```
productType = rows.reduce(sum, quantity) >= 2 ? 'COMPOSITE' : 'SIMPLE'   // api.md §3.3
parts = rows.map(r => ({
  name: r.recipeLabel,                 // 展示名兜底（后端字段仍留，见 §8 是否精简）
  partMode: 'custom',                  // 新模型固定 custom（无 existing 分支），见 §8
  recipeCode: r.recipeCode!,
  elements: Object.entries(r.elementOverrides).map(([elementCode, pct]) => ({elementCode, pct})),
  processIds: r.processIds,
  unitWeightGrams: r.unitWeightGrams ?? undefined,
  quotationLineItemId: ...,            // SIMPLE 与顶层 tempId 同值；COMPOSITE 每行独立 genUUID()（沿用现状规则）
  quantity: r.quantity,
}))
compositeProcesses = productType==='COMPOSITE'
  ? compositeSelections.map(c => ({ defCode: c.defCode, participatingPartIndexes: rows.map((_,i)=>i), params: {} }))  // 全部行参与，沿用现状规则
  : undefined
```

指纹预查：汇总区展示前调一次 `configureProductService.lookupFingerprint({...})`（**请求投影需要 backtask 确认**——现有 `LookupFingerprintRequest` 是单件 `recipeCode+elements+childHfPartNos`，明细表是多行组合，需要与 §4.6 的 `SIMPLE|COMPOSITE` 客户维度指纹算法对齐，属于**后端契约开放点**，前端先按“汇总区展示指纹检查结果”的 UI 行为实现，请求体形状等 backtask 定稿后再核对补全）。

### 5.4 空态/占位态

- 无有效模板：抽屉内空态“缺少选配模板 —— 请先在「配置中心 → 选配模板管理」为该客户所属行业或默认模板配置选配参数。” + 「去配置」链接（新开标签页或路由跳转均可，建议 `window.open('/config/sel-templates', '_blank')` 避免用户丢失当前报价单编辑上下文）。
- 明细表为空：“暂无选配材质，点击「+ 新增材质料号」开始”。
- 组合工艺区数量合计 <2：整块灰置 + 提示文案（不隐藏，D14 通则“不用 `if return null` 隐藏”同样适用于此非工具栏场景的语义一致性）。
- 3D 预览缺失：“未配置 3D 模型”占位，不阻断流程。

### 5.5 过滤（D14）

材质/工序/组合工艺三处候选列表**均需**过滤框（现状 `Step2Material`/`Step3Process`/`Step4CompositeProcess` 已各自实现，迁移时保留这部分交互，不倒退）。

### 5.6 3D 带出逻辑（D3/D15）

- 新增子框选材质 → 实时查 `MATERIAL`+配方码 → 材质 3D。
- 汇总区指纹命中 → 查 `SALES_PART`+命中料号 → **切换**为料号 3D（覆盖材质 3D，不是并列展示）。
- 右侧 3D 面板在整个 Drawer 生命周期内常驻（不是只在某个子步骤才显示），跟随“最近一次操作”更新。

### 前端验收标准

1. 有效模板解析：客户行业有模板 → 直接进明细表；客户行业无模板但 `__DEFAULT__` 有 → 用默认模板（材质/工序候选按默认模板 `effectiveValues` 限定）；两者都无 → 空态“缺少选配模板”。
2. 新增材质料号：材质候选按模板限定（若模板对材质 `allowedValues` 留空则不限，全量可选）；元素含量微调正确回填并可编辑；工序候选按模板限定；确认后明细表新增一行，字段摘要正确。
3. 明细表行内改数量：数量合计实时刷新；从 1 改到 2 时组合工艺区从灰置变为可用（无需重开抽屉）。
4. 组合工艺过滤框可用；选中后计入提交。
5. 汇总区：未命中显示"🆕 将新建"；命中（用测试数据构造一组已存在指纹的组合）显示"✅ 匹配到 SP-xxxx"且右侧 3D 切换为料号 3D。
6. 确认加入后 Step2 出现新卡片，字段按当前客户报价模板正常渲染（复用现有渲染管线，不应有"加载中"卡死）。
7. 删除已废弃的 4 个组件文件后，`ConfigureProductDrawer.tsx` 及全工程无遗留 import 报错。

---

## F6 — 集成收尾 & 冒烟自测

**依赖**：F4 + F5 均完成。
**改造文件**：`cpq-frontend/src/pages/quotation/QuotationWizard.tsx`（第 1529-1572 行区域，两个抽屉的最终 wiring 一起复核，因为 F4/F5 可能并行分支开发，此处大概率有合并冲突）。

### 收尾检查项

1. `AddProductModal` 新 props（`quotationId`/`onConfirm(lineItems[])`）与 `ConfigureProductDrawer` 是否仍需要的 props（`open`/`quotationId`/`onCancel`/`onConfirm`，其内部签名不变，只是内容重写）在 `QuotationWizard.tsx` 里正确传入。
2. 两个入口都走完整链路各跑一遍（从已有产品添加 3 件 + 选配添加 2 行触发组合工艺各一次），确认互不干扰、`lineItems` 状态正确累加、Step2 卡片视图/Excel 视图都能正常渲染新加入的行。
3. 确认 `mainTab==='quote'` 时 Dropdown 两项的既有 `disabled`（`customerTemplateId` 判断）逻辑对新实现仍然生效（F4/F5 都不应绕过这个判断）。
4. 全量回归报价单其余功能（Step1/Step3/导出/对比视图）**未被本次改动影响**——只做点击冒烟，不深入测试（深入测试属 cpq-tester 职责）。

### 前端验收标准

- 从空报价单开始，依次用两个入口各加 1~2 个产品，卡片视图/Excel 视图均正常渲染，无 JS 报错、无“加载中”卡死。
- 刷新页面重新进入草稿报价单，两个入口新加的行数据仍在（草稿已保存/自动保存路径未被破坏）。

---

## 7. 修改后强制自检（全局，每个 F 任务收尾都要过一遍，F6 做最终汇总）

### 7.1 类型检查

```
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json
```
必须 0 错误。

### 7.2 逐文件 Vite 200 校验

对**每一个**新建/改动的 `.tsx`/`.ts` 文件跑：
```
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/<相对路径>
```
预计涉及文件清单（F1~F6 完成后逐条过）：

```
src/types/modelConfig.ts
src/types/configure.ts
src/types/existingProduct.ts (或并入 types/quotation.ts，视 F1 实读现状而定)
src/services/modelConfigService.ts
src/services/selTemplateService.ts
src/services/quotationService.ts
src/pages/config/SelTemplateManagement.tsx
src/pages/config/ModelConfigManagement.tsx
src/pages/quotation/AddProductModal.tsx
src/pages/quotation/ConfigureProductDrawer.tsx
src/pages/quotation/configure/SelDetailTable.tsx
src/pages/quotation/configure/AddPartSubDrawer.tsx
src/pages/quotation/configure/CompositeProcessSection.tsx
src/pages/quotation/configure/SummaryFingerprintPanel.tsx
src/pages/quotation/QuotationWizard.tsx
src/router/index.tsx
src/layouts/MainLayout.tsx
```
（`.ts` 类型/纯逻辑文件 Vite 不一定单独可 `curl` 到 200——若返回非 200，改用其被引用页面的入口路径验证，如 `src/pages/config/ModelConfigManagement.tsx` 200 即可间接证明其依赖的 `modelConfigService.ts` 语法可解析。）

主入口兜底：
```
curl http://localhost:5174/
```

### 7.3 协议级改动 → 强制 E2E

`QuotationWizard.tsx` 在 CLAUDE.md 的显式触发清单内 → **F6 完成后必须跑**：
```
cd cpq-frontend
Remove-Item e2e\screenshots\qf-*.png -ErrorAction SilentlyContinue   # 或 Linux: rm -f e2e/screenshots/qf-*.png
npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list
```
必须看到全部 test `passed`、`'加载中' final count = 0`、8 Tab 全 0，并附 9 张截图证据（qf-19 + qf-21~28）。

`composite-product-flow.spec.ts` **现状必然因 F5 结构性重写而失败**（旧选择器绑定旧 DOM）——这不是 F5 的回归 bug，是预期的“测试需同步重写”。前端任务边界内的处理方式：
1. F5 完成后**不**尝试自己改写该 spec（属 cpq-tester 职责，写正式测试代码不在前端职责内）。
2. 但必须**手工走一遍**该 spec 描述的黄金路径（组合产品 2 配件 + 铆接组合工艺，见 spec 注释第 4 行）在新 UI 下的等价操作，确认功能未退化，并截图留证（放 `e2e/screenshots/` 或本任务目录均可，命名如 `sel-composite-smoke-01.png`…）。
3. 交接一句话必须写进 `docs/RECORD.md`（收尾时）：“`composite-product-flow.spec.ts` 因 D11 明细表重构失效，需 cpq-tester 重写”，避免后续会话把这当成新增回归 bug 排查。

### 7.4 “完成”宣告格式

必须包含一行如：
> "TS 0 错误 ✅；ModelConfigManagement.tsx → Vite 200 ✅；AddProductModal.tsx → Vite 200 ✅；ConfigureProductDrawer.tsx → Vite 200 ✅；quotation-flow.spec.ts 1 passed / 加载中=0 ✅；composite-product-flow.spec.ts 已知失效待 cpq-tester 重写（手工冒烟通过，截图 sel-composite-smoke-01~04）"

没有这行声明的“完成”=未完成。

---

## 8. 待 backtask.md / 架构对齐的开放点（前端不擅自决定，列出供后端/架构确认）

1. **`ConfigureProductRequest.parts[].partMode`/`existingHfPartNo`/`name` 是否精简**：新模型下每行都是新选材质，理论上 `partMode` 恒为 `'custom'`、`existingHfPartNo` 恒为空。前端本次先按“固定传 `partMode:'custom'`，`name` 传材质中文名兜底”与现有后端契约兼容；若 backtask 决定精简这些字段，前端需同步改提交映射（F5 §5.3）。
2. **`sel-param-types/{code}/candidates` 与 `sel-templates/effective` 的 `key` 语义**：是 `recipeCode`（材质配方码）还是 `recipeId`（`material_recipe.id`）？F5 材质详情/元素派生要靠 `materialRecipeService.detail(id)`（**吃 id**），若 `effectiveValues[].key` 给的是 `code`，前端需要一次 `materialRecipeService.list()` 建 `code→id` 索引再查详情；若给的直接是 `id`，可省这一步。**需要开发自测阶段用真实接口探查一次，写实现前先用 `curl` 确认，不能假设。**
3. **`lookupFingerprint` 请求投影是否已适配明细表多行组合**：现有 `LookupFingerprintRequest`（`productType/recipeCode/elements/childHfPartNos`）是单件粒度；§4.6 的客户维度指纹算法（`COMPOSITE: v1|CUST|COMBO=子件:数量|CPROC=组合工艺`）需要传整份组合的结构。前端 F5 §5.3 先按“汇总区调一次指纹检查”的**行为**实现，请求体字段等 backtask 定稿后再核对补全，不臆造字段名。
4. **配置中心「3D 模型配置」上传抽屉“绑定对象”候选源**：销售料号 Tab 的候选取哪个接口？本文档 F3 建议“沿用已有客户产品候选/料号字典”，但具体接口（是否复用 `existing-products`、需不需要跨客户的全量料号字典）未在 api.md 明确，需要 backtask/architect 补一条“料号字典候选”端点或明确复用现有哪个。
5. **“从模型自动截图”生成预览图**：F3 原型有此按钮，api.md 未给对应端点，前端先禁用+提示“暂未开放”，若后端后续补充再联调。
