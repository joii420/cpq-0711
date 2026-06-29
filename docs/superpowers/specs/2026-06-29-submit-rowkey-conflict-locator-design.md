# 报价单提交冲突的友好定位（Plan 1b）— 设计

> 状态：已定稿（2026-06-29 brainstorming 收敛）
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

## 2. 现状事实（已核对源码）

| 事实 | 位置 |
|---|---|
| submit 内行键校验，冲突抛 `BusinessException(422, 文本)` | `QuotationService.submit()` `QuotationService.java:807-839` |
| 冲突记录 `record (componentName, rowKey, rowIndices)`，`describe()` 拼文案 | `RowKeyConflict.java` |
| 纯函数判重（同组件内 rowKey 出现 ≥2 次） | `RowKeyConflictDetector.java` |
| 装配：解析结构快照 + componentData 两路取数，算 key | `RowKeyUniquenessService.collectConflicts()` |
| `BusinessException` 仅 `code + message`，**不带结构化 payload** | `BusinessException.java` |
| mapper 把 `BusinessException` 转 `ApiResponse.error(code, message)`，**无 data** | `GlobalExceptionMapper.java:21-26` |
| `ApiResponse` 已有 `data` 字段，但 `error(code, message)` 工厂不填 data | `ApiResponse.java` |
| 前端拦截器失败分支只取 `error.response.data.message` → `new Error(message)`，**丢弃 data** | `api.ts:22-33` |
| `handleSubmit` catch 仅 `message.error(e.message)` | `QuotationWizard.tsx:1083-1095` |
| `LineItem` 有 `id`（line_item id），`ComponentDataItem` 有 `componentId` | `QuotationStep2.tsx`（`LineItem` / `ComponentDataItem` 接口） |

关键缺口：当前文本里只有「料号 + componentId」，**没有 lineItemId**；而同一 componentId（如 `e31bbdd1…`）出现在很多料号下，前端靠料号字符串模糊匹配不可靠。要精确跳转，必须把 `lineItemId` 一路带出来。

## 3. 已定决策（brainstorming 收敛）

1. **触发方式 = 被动定位**：点提交 → 后端校验失败返回 422 + 冲突明细 → 前端解析后呈现并跳转。不做编辑期实时预检（那是潜在的"第二期"，本 spec 不含）。
2. **报错传输 = 后端结构化返回**：后端额外携带结构化冲突数据，前端直接消费，不靠正则解析中文文本（行键值含 `·` / `||` / 逗号会撞分隔符）。
3. **呈现 = 冲突清单抽屉**：右侧 Drawer 列出全部冲突点，点某条跳转。
4. **定位粒度 = 料号 + 页签级**：跳到对应料号卡片并切到对应页签即可；**不做卡片内"具体行高亮"**（行号仅在 Drawer 文案里展示给用户参考）。

## 4. 非目标（YAGNI）

- ❌ 编辑期 / 提交前的实时预检与红点标记（前端镜像后端行键算法）——不在本期。
- ❌ 卡片内冲突行高亮 / 滚动定位到行——已明确降级，不做。
- ❌ 修改行键唯一性的判定口径或"提交侧也做 `#序号` 消歧"——本 spec 只改**呈现与定位**，不动**校验语义**（避免与 Plan 1「组合行键不可重复」的硬约束冲突）。
- ❌ 核价单提交的同类定位——本期只覆盖报价单提交。

## 5. 架构与数据流

```
点「提交审批」(QuotationWizard.handleSubmit)
  → handleSaveDraft()                         （先存草稿，不变）
  → POST /quotations/{id}/submit
        RowKeyUniquenessService.collectConflicts() 发现冲突
        → 抛 RowKeyConflictException（携带 List<RowKeyConflictDTO>）
        → GlobalExceptionMapper 把 conflicts 放进 ApiResponse.data，HTTP 422
  → api.ts 拦截器：把响应体 data 挂到 rejected Error（err.payload），message 行为不变
  → handleSubmit catch：
        若 err.payload?.conflicts 非空 → 打开 RowKeyConflictDrawer
        否则                          → 维持现有 message.error(e.message)
  → Drawer 列出每条 {料号, 页签, 行键, 第X,Y行}
  → 点某条「定位」→ setLocateTarget({lineItemId, componentId})
        → setCurrentStep(1)（切到 Step2）
        → QuotationStep2 按 lineItemId 找到料号卡片、切到 componentId 对应页签
```

## 6. 后端契约改造

### 6.1 新增 `RowKeyConflictDTO`
```
record RowKeyConflictDTO(
    String lineItemId,      // 报价单明细行 id（精确定位用；可能为 null，见 6.2）
    String productPartNo,   // 料号（展示用，来自 lineItemLabel / product_part_no_snapshot）
    String componentId,     // 页签组件 id（前端切 Tab 用）
    String tabName,         // 页签中文名；解析不到回退 componentId
    String rowKey,          // 组合行键
    List<Integer> rowIndices // 1 基重复行号（展示用）
)
```

### 6.2 `RowKeyUniquenessService` 携带 id 出参
- `LineItemComps` 增加 `lineItemId` 字段（`QuotationService.submit` 装配时把 `li.id` 传入；当前只传了 label）。
- `CompRows` 已有 `componentId`，沿用。
- `parseStructure` / `TabKeyCfg` 额外解析**页签中文名**（结构快照 tab 节点的名称字段；取不到回退 componentId，与现状文案一致）。
- `collectConflicts` 产出 `List<RowKeyConflictDTO>`（每条带齐 lineItemId / productPartNo / componentId / tabName / rowKey / rowIndices）。
- 既有的 `RowKeyConflict.describe()` 文本拼装**保留**，用于异常 message（向后兼容、日志可读）。

### 6.3 承载方式 = 新异常子类
```
class RowKeyConflictException extends BusinessException {
    final List<RowKeyConflictDTO> conflicts;   // submit 冲突明细
    // super(422, 既有拼装文案)
}
```
- 选新子类而非给 `BusinessException` 加通用 `data` 字段：更内聚、影响面最小，不波及其它 BusinessException 调用方。

### 6.4 mapper 与 ApiResponse
- `ApiResponse` 增加 `error(int code, String message, Object data)` 重载（或给 `data` 加 setter）。
- `GlobalExceptionMapper.handleBusinessException`：`instanceof RowKeyConflictException` 时把 `conflicts` 放进 `ApiResponse.data`；其余 BusinessException 行为完全不变。

### 6.5 `QuotationService.submit`
- 冲突分支由 `throw new BusinessException(422, sb.toString())` 改为 `throw new RowKeyConflictException(sb.toString(), conflictDTOs)`。文案不变。

## 7. 前端改造

### 7.1 `api.ts` 拦截器（全局，保守改）
失败分支在 `new Error(message)` 基础上挂载结构化数据：
```
const err = new Error(message);
(err as any).payload = error.response?.data?.data ?? null;
(err as any).httpStatus = error.response?.status;
return Promise.reject(err);
```
`message` 取值逻辑不变 → 所有现有 `catch (e) { message.error(e.message) }` 向后兼容。

### 7.2 新组件 `RowKeyConflictDrawer.tsx`
- Ant `Drawer`，`placement="right"`，宽度 720。
- 一个表格：列「料号 / 页签 / 行键 / 重复行号 / 操作」；操作列一个「定位」链接。
- props：`open`、`conflicts: RowKeyConflictDTO[]`、`onLocate(c)`、`onClose`。
- 顶部一句汇总：`共 N 处行键重复，请逐个修正后重新提交`。

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

### 7.4 定位联动（料号 + 页签级）
- QuotationWizard 持有 `locateTarget: { lineItemId, componentId } | null`。
- Drawer `onLocate(c)` → `setLocateTarget({lineItemId: c.lineItemId, componentId: c.componentId})`、关 Drawer、`setCurrentStep(1)`。
- `QuotationStep2` 接收 `locateTarget` prop：用 `useEffect` 在其变化时——
  - 按 `lineItems.findIndex(li => li.id === lineItemId)` 定位料号卡片（可滚动到该**料号卡片顶部**——属料号级定位，不滚动到具体行）；
  - 把该卡片的 activeTab 切到 `componentData` 中 `componentId` 匹配的页签。
- 兜底：`lineItemId` 为 null 或找不到（旧数据 / 装配缺失）时，退化为按 `productPartNo` 匹配第一张卡片；仍找不到则只关 Drawer 不跳转，保留 message 提示。

## 8. 测试计划

### 8.1 后端
- `RowKeyUniquenessServiceTest`：扩展断言——冲突 DTO 带齐 `lineItemId / componentId / tabName / rowKey / rowIndices`。
- 新增 mapper/集成测试：`RowKeyConflictException` → HTTP 422 且响应体 `data.conflicts` 为结构化数组（非纯文本）。
- 既有 `SubmitRowKeyUniquenessQuarkusTest`：保持绿（文案不变）。

### 8.2 前端
- 新增 E2E `submit-rowkey-conflict-locator.spec.ts`：构造一个含行键重复的报价单 → 点提交 → 断言 Drawer 出现、条目数与后端一致 → 点首条「定位」→ 断言切到 Step2 且 activeTab = 对应页签。
- **协议级回归（强制）**：因改动 `QuotationWizard.tsx` / `QuotationStep2.tsx`，必须跑 `quotation-flow.spec.ts`，要求 `1 passed` + `加载中 final count = 0` + 8 Tab 加载中=0（见 `docs/E2E测试方法.md`、CLAUDE.md「修改后强制自检」§5）。
- `api.ts` 改动属全局：跑现有前端测试套件确认无回归（拦截器 message 行为未变）。

### 8.3 自检（完成宣告前必跑）
- `npx tsc --noEmit` 0 错误。
- 改动的 `.tsx` 经 `curl http://localhost:5174/src/...` HTTP 200。
- 后端 `touch` 重启 → submit endpoint 返回 422（非 500）且响应体含 `data.conflicts`。

## 9. 风险与缓解

| 风险 | 缓解 |
|---|---|
| `api.ts` 全局拦截器改动波及所有请求 | 只**新增**挂载字段（`payload` / `httpStatus`），`message` 取值与抛出形态不变；现有 catch 全部向后兼容；跑回归确认。 |
| 装配阶段拿不到 `lineItemId` / 中文页签名 | DTO 字段允许为 null；前端按 `productPartNo` 兜底匹配，再不行只展示不跳转——不阻断"看清问题"这一核心价值。 |
| 误把其它 422/业务错也当行键冲突 | 前端严格判 `err.payload?.conflicts` 是非空数组才开 Drawer，否则走原 message。 |
| 改了协议级文件引发渲染回归 | 强制 `quotation-flow.spec.ts` 回归 + 8 Tab 加载中=0。 |

## 10. 文件清单

**后端**
- 新增 `…/quotation/service/rowkey/RowKeyConflictDTO.java`
- 新增 `…/common/exception/RowKeyConflictException.java`
- 改 `…/quotation/service/rowkey/RowKeyUniquenessService.java`（出参带 id + 页签名）
- 改 `…/quotation/service/QuotationService.java`（submit 装配 lineItemId + 抛新异常）
- 改 `…/common/exception/GlobalExceptionMapper.java`（放 conflicts 进 data）
- 改 `…/common/dto/ApiResponse.java`（error 带 data 重载 / setter）
- 改/加测试：`RowKeyUniquenessServiceTest`、mapper 集成测试

**前端**
- 新增 `…/quotation/RowKeyConflictDrawer.tsx`
- 改 `…/services/api.ts`（拦截器挂 payload）
- 改 `…/quotation/QuotationWizard.tsx`（handleSubmit 分支 + locateTarget state + Drawer 挂载）
- 改 `…/quotation/QuotationStep2.tsx`（接收 locateTarget，按 lineItemId + componentId 切卡片/页签）
- 新增 E2E `e2e/submit-rowkey-conflict-locator.spec.ts`

## 11. 实现纪律

- 按 CLAUDE.md 规范，实现阶段先用 `superpowers:using-git-worktrees` 起隔离 worktree 特性分支，不在 master 直接改。
- 默认走 `superpowers:subagent-driven-development` 推进计划。
- 协议级改动跑 E2E；完成宣告附「已自检」声明。
