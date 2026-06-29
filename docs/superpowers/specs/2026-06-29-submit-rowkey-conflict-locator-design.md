# 报价单提交冲突的友好定位（Plan 1b）— 设计

> 状态：已定稿（2026-06-29 brainstorming 收敛 → 同日独立评审纠偏后修订）
> 关联：`docs/superpowers/plans/2026-06-09-plan1-composite-rowkey-uniqueness.md`（Plan 1 后端硬拦截已落地，本 spec 即其中明确拆出、一直未做的 **Plan 1b 前端友好定位**）

## 1. 背景与问题

报价单提交审批时，后端「行键唯一性校验（设计 E / Plan 1）」会对每个组件计算 driver 展开行 + 手动行的组合行键（`rowKeyFields`），发现重复即拒绝提交。这是**按设计的业务硬拦截**，不是 bug。

但当前用户体验差：后端把全部冲突拼成一长串中文文本抛出，前端只用一个 Ant `message.error` 整串弹出，例如：

```
行键重复，无法提交：
· 组件「5121114615 · e31bbdd1-1c27-4fd4-a1a5-581b3461ae8b」行键 [焊接组件] 在第 2,3 行重复
· 组件「5121111511 · 1b2d1bdb-facc-4fe9-8d10-3e68e05b85a6」行键 [Cu||H65带] 在第 2,3 行重复
... （实测一次可达 12 条，跨多个料号与页签）
```

用户无法据此快速定位到底是哪个料号、哪个页签出了问题，只能逐个 Tab 翻找。

**本 spec 目标**：提交被拦截时，前端把冲突结构化呈现为一个清单抽屉，点击某条即把向导带到对应的**料号卡片 + 页签**。

## 2. 现状事实（已核对源码，2026-06-29 评审复核）

| 事实 | 位置 |
|---|---|
| submit 内行键校验块 807-839，冲突 `throw new BusinessException(422, 文本)` 在 838；文案 `"行键重复，无法提交："` 拼装在 834-838 | `QuotationService.java:807-839` |
| **校验对象是全部 line item（含组合产品 PART 子行）** | `QuotationService.java:817`（遍历 `lineItems`，无 PART 过滤） |
| `lineItems` 来自 `QuotationLineItem.list(...)` DB 加载，每行 `li.id` 均为已持久化 UUID（非空） | `QuotationService.java:805` |
| 单明细 label 取数：**优先 `productNameSnapshot`（产品名），仅缺名时才回退 `productPartNoSnapshot`（料号）** | `QuotationService.java:818-819` |
| `RowKeyConflict` record `(componentName, rowKey, rowIndices)`；`rowIndices` 是 **0 基**，`describe()` 内 `i+1` 转 1 基 | `RowKeyConflict.java:9-16` |
| `RowKeyConflictDetector` 纯函数判重 | `RowKeyConflictDetector.java:16-30` |
| `collectConflicts` 解析结构快照 + 两路取数；调 `detect()` 前已把 `lineItemLabel + " · " + componentName` **塌缩成单个 label 串**（79-80），返回的 `RowKeyConflict` 不带 componentId/lineItemId | `RowKeyUniquenessService.java:41-84` |
| `TabKeyCfg` **已有 `componentName` 字段**（由 `tab.path("componentName")` 解析），即页签名现成、无需额外解析 | `RowKeyUniquenessService.java:39, 93` |
| rowIndices 的行号 = `(driver 展开行 ++ 手动行)` 合并序列的下标 | `RowKeyUniquenessService.java:62-77` |
| `BusinessException` 仅 `code + message`，**不带结构化 payload** | `BusinessException.java:1-17` |
| mapper 把 `BusinessException` 转 `ApiResponse.error(code, message)`，**无 data** | `GlobalExceptionMapper.java:21-26` |
| `ApiResponse` 有 `data` 字段（:10），但 `error(code, message)` 工厂不 set data（:34-39） | `ApiResponse.java` |
| 前端拦截器：成功返回整个信封 `response.data`（`{code,message,data}`）；失败分支只取 `error.response.data.message` → `new Error(message)`，**data 被丢** | `api.ts:32-33` |
| `handleSubmit` catch 仅 `message.error(e.message)` | `QuotationWizard.tsx:1083-1095`（catch 1092-1094） |
| 向导 steps = [选客户, **添加产品(Step2)=index 1**, 优惠, 交易, 提交=index 4]；`steps[currentStep].content()` **只渲染当前步** → 跳回 Step2 是全新挂载 | `QuotationWizard.tsx:1599-1604, 753, 1649` |
| `LineItem.id` 是**可选**字段；`ComponentDataItem` 有 `componentId`(:129) **和 `tabName`(:132)** | `QuotationStep2.tsx:173-174, 129, 132` |
| **`activeTab` 是每个 `ProductCard` 的内部 state**（`useState(0)` 在 ProductCard 内），卡片 list 渲染各自持有；`QuotationStep2` 本身没有 activeTab | `QuotationStep2.tsx:1486`；卡片渲染 3356/3404 |
| 切 tab 索引的是 `normalComponents = componentData.filter(c => c.componentType==='NORMAL')`（再叠模板成员过滤） | `QuotationStep2.tsx:1952, 2831` |
| 前端两处 `lineItems.filter(li => li.compositeType !== 'PART')` **隐藏组合产品子卡片** | `QuotationStep2.tsx:2828, 2879` |
| 存在 `mainTab`（'quote'|'costing'|'comparison'）/ `viewType`（'card'|'excel'）视图开关 | `QuotationStep2.tsx:2740-2741` |
| 全工程无任何现存 `err.payload` 用法 → 新增该字段零冲突 | grep 核验 |

## 3. 已定决策（brainstorming 收敛）

1. **触发方式 = 被动定位**：点提交 → 后端校验失败返回 422 + 冲突明细 → 前端解析后呈现并跳转。不做编辑期实时预检（潜在"第二期"，本 spec 不含）。
2. **报错传输 = 后端结构化返回**：后端额外携带结构化冲突数据，前端直接消费，不靠正则解析中文文本（行键值含 `·` / `||` / 逗号会撞分隔符）。
3. **呈现 = 冲突清单抽屉**：右侧 Drawer 列出全部冲突点，点某条跳转。
4. **定位粒度 = 料号 + 页签级**：跳到对应料号卡片并切到对应页签即可；**不做卡片内"具体行高亮"**（行号仅在 Drawer 文案里展示给用户参考）。

## 4. 非目标（YAGNI）

- ❌ 编辑期 / 提交前的实时预检与红点标记（前端镜像后端行键算法）——不在本期。
- ❌ 卡片内冲突行高亮 / 滚动定位到具体行——已明确降级，不做。
- ❌ 修改行键唯一性的判定口径或"提交侧也做 `#序号` 消歧"——本 spec 只改**呈现与定位**，不动**校验语义**（避免与 Plan 1「组合行键不可重复」的硬约束冲突）。
- ❌ 核价单提交的同类定位——本期只覆盖报价单提交。

## 5. 架构与数据流

```
点「提交审批」(QuotationWizard.handleSubmit)
  → handleSaveDraft()                         （先存草稿，不变）
  → POST /quotations/{id}/submit
        RowKeyUniquenessService.collectConflicts() 发现冲突
        → 抛 RowKeyConflictException（携带 List<RowKeyConflictDTO>）
        → GlobalExceptionMapper 把 conflicts 包成 {conflicts:[...]} 放进 ApiResponse.data，HTTP 422
  → api.ts 拦截器：把响应体信封的 .data 挂到 rejected Error（err.payload），message 行为不变
  → handleSubmit catch：
        若 err.payload?.conflicts 非空数组 → 打开 RowKeyConflictDrawer
        否则                              → 维持现有 message.error(e.message)
  → Drawer 列出每条 {料号, 页签, 行键, 第X,Y行}
  → 点某条「定位」→ setLocateTarget({lineItemId, productPartNo, componentId})
        → setCurrentStep(1)（切到 Step2）
        → QuotationStep2 复位 mainTab='quote' + viewType='card'
        → 按 lineItemId(兜底 productPartNo) 找到料号卡片
        → 下钻进该 ProductCard → ProductCard 自身 setActiveTab(按 normalComponents 里的 componentId 序号)
```

## 6. 后端契约改造

### 6.1 新增 `RowKeyConflictDTO`
```
record RowKeyConflictDTO(
    String lineItemId,      // 报价单明细行 id（精确定位用；后端装配恒非空，见 §9）
    String productName,     // 产品名（= label，Drawer 主展示）
    String productPartNo,   // 料号（= productPartNoSnapshot，前端兜底匹配卡片用）
    String componentId,     // 页签组件 id（前端切 Tab 用）
    String tabName,         // 页签中文名（= TabKeyCfg.componentName；取不到回退 componentId）
    String rowKey,          // 组合行键
    List<Integer> rowIndices // 1 基重复行号（展示用，转换时 i+1，与 describe() 同口径）
)
```
> 注（#1 纠偏）：`productPartNo` 必须显式取 `li.productPartNoSnapshot`，**不可复用 label**——label 优先是产品名（`QuotationService.java:818-819`），用它匹配卡片会错。
> 注（rowIndices 语义）：行号是「driver 展开行 ++ 手动行」合并序列的 1 基序（`RowKeyUniquenessService.java:62-77`），与卡片内可见行序（含墓碑删除行 / 树形布局）不必然一一对应。既已降级不做行高亮，Drawer 文案对它仅作"参考行"提示，**不要让用户误当可见行号**。

### 6.2 `RowKeyUniquenessService` 携带 id 出参（改造比初版描述更深）
现状 `collectConflicts` 在调 `detect()` 前已把信息塌缩成 `label` 单串（`:79-80`）、返回的 `RowKeyConflict` 只带 `(label, rowKey, rowIndices)`；`LineItemComps` 当前仅 `(lineItemLabel, comps)`（`:36`），不含料号。改造要点：
- `LineItemComps` 增加 `lineItemId` **和 `productPartNo`** 两个字段；`QuotationService.submit` 装配时把 `li.id` 与 `li.productPartNoSnapshot` 一并传入（当前只传了 label=`productNameSnapshot`，见 `:815-830, 818-819`）。**注**：DTO.`productName` 复用 label，DTO.`productPartNo` 取新增字段——二者来源不同（产品名 vs 料号），**不可混用**。
  - 备选实现：把 DTO 组装直接**上移到 `QuotationService.submit`**（`li.id` / `li.productPartNoSnapshot` / `li.productNameSnapshot` 全在作用域），`collectConflicts` 只返回带 componentId 的中间结构。实现时二选一，效果等价。
- `CompRows` 已有 `componentId`，沿用；`tabName` 直接取 `cfg.componentName`（**已现成，无需额外解析**）。
- 在嵌套循环内（`comp.componentId()` / `lineItemId` / `productPartNo` / `cfg.componentName` 均在作用域时）**就地组装 `RowKeyConflictDTO`**，每条带齐 7 字段。
- **返回类型迁移（钉死）**：`collectConflicts` 改为返回 `List<RowKeyConflictDTO>`；submit 侧（`:834-838`）原用 `c.describe()` 拼 message 的路径，改为**从 DTO 列表重建同样文本**（文案逐字不变、日志可读、向后兼容）。
- **1 基 / 0 基纪律（防双增）**：`describe()` 收 **0 基** rowIndices 并做 `i+1`（`RowKeyConflict.java:14`），而 DTO.`rowIndices` 已存 **1 基**；DTO→文本重建时**直接 join，不得再 +1**，否则变成 +2、文案不再逐字一致。`RowKeyConflict` + `describe()` 作为 detect 内部产物**保留**，仅 service 出参类型升级为 DTO。`SubmitRowKeyUniquenessQuarkusTest`（文案逐字断言）兜底。

### 6.3 承载方式 = 新异常子类
```
class RowKeyConflictException extends BusinessException {
    final List<RowKeyConflictDTO> conflicts;   // submit 冲突明细
    // super(422, 从 DTO 重建的既有拼装文案)
}
```
- 选新子类而非给 `BusinessException` 加通用 `data` 字段：更内聚、影响面最小（mapper 现有 11 个分支无一受影响），不波及其它 BusinessException 调用方。

### 6.4 mapper 与 ApiResponse（钉死 JSON 形状，#3）
- `ApiResponse` 增加 `error(int code, String message, Object data)` 重载（或给 `data` 加 setter）。
- `GlobalExceptionMapper.handleBusinessException`：`instanceof RowKeyConflictException` 时返回
  `ApiResponse.error(422, msg, Map.of("conflicts", e.conflicts))` —— 即响应体 `data = { "conflicts": [ ...DTO... ] }`（**对象包一层**）。其余 BusinessException 行为完全不变。
- **契约不变量**：`ApiResponse.data` 必须是 `{conflicts:[...]}` 对象，不能直接塞裸 `List`。否则前端 `payload.conflicts` 恒 undefined → Drawer 静默不弹（这是初版的致命歧义）。

### 6.5 `QuotationService.submit`
- 冲突分支由 `throw new BusinessException(422, sb.toString())` 改为 `throw new RowKeyConflictException(文本, conflictDTOs)`。文本逐字不变。

## 7. 前端改造

### 7.1 `api.ts` 拦截器（全局，保守改）
失败分支在 `new Error(message)` 基础上挂载结构化数据：
```
const err = new Error(message);
(err as any).payload = error.response?.data?.data ?? null;   // 信封.data，与成功侧 response.data 同层级
(err as any).httpStatus = error.response?.status;
return Promise.reject(err);
```
- `message` 取值逻辑（`error.response?.data?.message`）与 reject 形态完全不变 → 所有现有 `catch (e) { message.error(e.message) }` 向后兼容（全工程无 `.payload` 依赖，已核验）。
- 说明（#5）：成功分支返回 `response.data`=信封 `{code,message,data}`，失败侧 `error.response.data` 也是同一信封，故两侧都用 `.data.data` 取业务 payload，层级对称。

### 7.2 新组件 `RowKeyConflictDrawer.tsx`
- Ant `Drawer`，`placement="right"`，宽度 720。
- 一个表格：列「料号(productName + productPartNo) / 页签(tabName) / 行键(rowKey) / 参考行号(rowIndices) / 操作」；操作列一个「定位」链接。
- props：`open`、`conflicts: RowKeyConflictDTO[]`、`onLocate(c)`、`onClose`。
- 顶部一句汇总：`共 N 处行键重复，请逐个修正后重新提交`。
- **列表规范豁免备注**（防 PR 被打回）：Drawer 内部子表 + 行内「定位」纯导航链接（无副作用、非状态变更/危险动作），按 `docs/列表操作规范.md` §12「例外白名单」免用 `SelectableTable`，合规。

### 7.3 `handleSubmit`（QuotationWizard.tsx）
```
catch (e: any) {
  const conflicts = e?.payload?.conflicts;
  if (Array.isArray(conflicts) && conflicts.length) {
    setConflicts(conflicts);
    setConflictDrawerOpen(true);
  } else {
    message.error(e.message);   // 维持现状
  }
}
```

### 7.4 定位联动（料号 + 页签级）— 关键，按两轮评审重写
`activeTab` 在 **ProductCard 内部**（`QuotationStep2.tsx:1485-1486`），不在 Step2 顶层；卡片渲染自 **`quoteLineItems = lineItems.filter(li => li.compositeType !== 'PART')`**（`:2828`，渲染 `:3404`），其下标与全量 `lineItems` 不一致（**AP-54 同类坑搬到卡片维度**）。联动必须层层下钻，且全程**按稳定 id、不按下标**定位：

1. **QuotationWizard** 持有 `locateTarget: { lineItemId, productPartNo, componentId, seq } | null`。`seq` 单调递增（每次 `onLocate` +1），保证连点同一条冲突也能重新触发。
2. Drawer `onLocate(c)` → `setLocateTarget({ ...c, seq: prevSeq + 1 })`、关 Drawer、`setCurrentStep(1)`（Step2 全新挂载）。
3. **QuotationStep2** 接收 `locateTarget` prop，`useEffect`（**依赖含 `locateTarget.seq`**）监听其变化：
   - **先复位视图**：`setMainTab('quote')` + `setViewType('card')`。后端只校验 `QUOTE_CARD` 结构（`QuotationService.java:808-813`），冲突恒来自报价卡 → 复位到 quote/card **永远正确**。
   - **解析目标卡片（按 id，不按下标）**：
     - 在**全量 `lineItems`** 按 `li.id === locateTarget.lineItemId` 找到冲突行 `hit`。
     - 若 `hit.compositeType === 'PART'` → 真正要定位的是父卡：目标卡 id = `hit.parentLineItemId`（`:209`，仅 PART 非空）。
     - 否则目标卡 id = `hit.id`。目标卡 id 须存在于**渲染用的 `quoteLineItems`**（父卡/普通卡都在其中）。
   - **滚动**：卡片 ref 用 **以 line item id 为 key 的 Map**（`cardRefs.current[item.id]`，按 id 取、杜绝下标偏移），滚动到目标料号卡片顶部（现工程无 cardRef，需新增）——属料号级定位，不滚动到具体行。
4. **下钻进目标 ProductCard 切页签**：把 `componentId`（+ `seq`）作为 prop 传给该卡片；ProductCard 自身 `useEffect`（**依赖含 `seq`**）在 **`normalComponents`（过滤 SUBTOTAL + 模板成员后的子集，`:1951-1964/:2831`）** 里 `findIndex(c => c.componentId === 目标)` 求 tab 序号、`setActiveTab(序号)`。
   - **AP-54 纪律**：必须用 `normalComponents` 下标，不可用 raw `componentData` 下标（`docs/反模式.md AP-54`「过滤后下标当原数组下标」）。
5. **降级与兜底（按优先级）**：
   - 普通行 `lineItemId` 在前端尚未回灌持久化 id → 回退按 `productPartNo` 在 `quoteLineItems` 匹配卡片。
   - **PART 冲突只能走 id→`parentLineItemId`**：PART 的 `productPartNo` 在 `quoteLineItems`（父卡列表）里**永不命中，不得退化到 productPartNo 兜底**；若 PART 行 id 缺失无法解析父卡 → 该条降级为"只在 Drawer 展示、点定位给一句 toast，不跳转"。
   - 目标组件是 SUBTOTAL / 被模板过滤 / 在 `normalComponents` 找不到 → 只定位到卡片，不切 tab。

## 8. 测试计划

### 8.1 后端
- `RowKeyUniquenessServiceTest`：断言 `collectConflicts` 返回 DTO 带齐 `lineItemId / productName / productPartNo / componentId / tabName / rowKey / rowIndices`；rowIndices 为 1 基。
- 新增 mapper/集成测试：`RowKeyConflictException` → HTTP 422 且响应体 `data.conflicts` 为结构化数组（形状 `{conflicts:[...]}`，非裸 List、非纯文本）。
- 既有 `SubmitRowKeyUniquenessQuarkusTest`：保持绿（异常 message 文本逐字不变）。

### 8.2 前端
- 新增 E2E `submit-rowkey-conflict-locator.spec.ts`：构造含行键重复的报价单 → 点提交 → 断言 Drawer 出现、条目数与后端一致 → 点首条「定位」→ 断言切回 Step2、mainTab=quote、目标卡片的 activeTab = 对应页签。
- **协议级回归（强制）**：改动 `QuotationWizard.tsx` / `QuotationStep2.tsx`，必须跑 `quotation-flow.spec.ts`，要求 `1 passed` + `加载中 final count = 0` + 8 Tab 加载中=0，附 qf 截图（见 `docs/E2E测试方法.md`、CLAUDE.md「修改后强制自检」§5）。
- 组合产品场景：跑 `composite-product-flow.spec.ts` 确认 PART 冲突的降级/父卡映射不崩。
- `api.ts` 改动属全局：跑前端测试套件确认无回归（拦截器 message 行为未变）。

### 8.3 自检（完成宣告前必跑）
- `npx tsc --noEmit` 0 错误。
- 改动的 `.tsx` 经 `curl http://localhost:5174/src/...` HTTP 200。
- 后端 `touch` 重启 → submit endpoint 返回 422（非 500）且响应体含 `data.conflicts`（形状 `{conflicts:[...]}`）。

## 9. 风险与缓解

| 风险 | 缓解 |
|---|---|
| **`ApiResponse.data` 形状不一致 → Drawer 静默不弹** | §6.4 钉死 `data={conflicts:[...]}` 对象包一层；后端测试断言形状；前端只认非空数组。 |
| `api.ts` 全局拦截器改动波及所有请求 | 只**新增**挂载字段（`payload`/`httpStatus`），`message` 取值与抛出形态不变；全工程无 `.payload` 依赖；跑回归确认。 |
| **前端 lineItem 未带持久化 id**（真实风险在前端非后端） | 后端装配侧 `li.id` 恒非空（`:805`）；前端首存后若**普通行** `LineItem.id` 尚未同步回，则按 `productPartNo` 兜底匹配卡片；实现时验证 handleSubmit 前 `handleSaveDraft` 已回灌持久化 id。 |
| **组合产品 PART 冲突无可见卡片** | §7.4 step5：PART **只能**走 id→`parentLineItemId` 定位父卡（PART 的 productPartNo 在 `quoteLineItems` 永不命中，**不可退 productPartNo 兜底**）；PART 行 id 缺失即降级"只展示+toast 不跳转"。 |
| 切 tab 用错下标（撞 AP-54） | 强制 `normalComponents.findIndex`；E2E 断言 activeTab 命中。 |
| 误把其它 422/业务错当行键冲突 | 前端严格判 `err.payload?.conflicts` 是非空数组才开 Drawer，否则走原 message。 |
| 改协议级文件引发渲染回归 | 强制 `quotation-flow.spec.ts` + `composite-product-flow.spec.ts` 回归。 |

## 10. 文件清单

**后端**
- 新增 `…/quotation/service/rowkey/RowKeyConflictDTO.java`
- 新增 `…/common/exception/RowKeyConflictException.java`
- 改 `…/quotation/service/rowkey/RowKeyUniquenessService.java`（出参改 DTO 列表 + 带 lineItemId/componentId/tabName/productPartNo）
- 改 `…/quotation/service/QuotationService.java`（submit 装配 `li.id` + `productPartNoSnapshot`，抛新异常，从 DTO 重建文本）
- 改 `…/common/exception/GlobalExceptionMapper.java`（instanceof 分支放 `{conflicts:[...]}` 进 data）
- 改 `…/common/dto/ApiResponse.java`（error 带 data 重载 / setter）
- 改/加测试：`RowKeyUniquenessServiceTest`、mapper 集成测试

**前端**
- 新增 `…/quotation/RowKeyConflictDrawer.tsx`
- 改 `…/services/api.ts`（拦截器挂 payload）
- 改 `…/quotation/QuotationWizard.tsx`（handleSubmit 分支 + locateTarget state + Drawer 挂载 + 传 locateTarget 给 Step2）
- 改 `…/quotation/QuotationStep2.tsx`（接收 locateTarget：复位 mainTab/viewType + 按 lineItemId/productPartNo 选卡片 + cardRef；下钻 componentId 给 ProductCard，ProductCard 按 normalComponents setActiveTab）
- 新增 E2E `e2e/submit-rowkey-conflict-locator.spec.ts`

## 11. 实现纪律

- 按 CLAUDE.md 规范，实现阶段先用 `superpowers:using-git-worktrees` 起隔离 worktree 特性分支，不在 master 直接改。
- 默认走 `superpowers:subagent-driven-development` 推进计划。
- 协议级改动跑 E2E（`quotation-flow` + `composite-product-flow`）；完成宣告附「已自检」声明。
- **完成后回写 `docs/RECORD.md`**（CLAUDE.md「开发记录」强制）：格式 `[日期] 模块 - 描述 | 涉及文件 | 关键决策`。
