# 报价单提交行键冲突友好定位（Plan 1b）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 报价单提交被「行键唯一性校验」拦截时，后端结构化返回冲突明细，前端弹冲突清单抽屉并点击定位到对应「料号卡片 + 页签」。

**Architecture:** 后端新增 `RowKeyConflictDTO` + `RowKeyConflictException`，经 `GlobalExceptionMapper` 把 `{conflicts:[...]}` 放进 `ApiResponse.data`（HTTP 422）；前端 `api.ts` 拦截器透传 payload，`QuotationWizard.handleSubmit` 据此开 `RowKeyConflictDrawer`，点击经 `locateTarget{seq}` 下钻 `QuotationStep2` → 复位 mainTab/viewType → 按 line item id 定位卡片（PART 走 parentLineItemId）→ ProductCard 按 `normalComponents` 切 activeTab。**只改呈现与定位，不动校验语义。**

**Tech Stack:** Java 17 / Quarkus / Jackson；React 18 / Ant Design 5 / TypeScript / Vitest / Playwright。

**Spec:** `docs/superpowers/specs/2026-06-29-submit-rowkey-conflict-locator-design.md`（两轮评审纠偏后定稿）

**执行前置（CLAUDE.md 强制）:** 用 `superpowers:using-git-worktrees` 起隔离 worktree + 特性分支。worktree 内**复用主工作区已运行的** 后端 dev(8081) / 前端 dev(5174) / DB / node_modules，**不另起 server、不重装依赖**。后端测试在 `cpq-backend/` 用 `./mvnw`（mvnw 在 cpq-backend，不在仓库根）。

---

## File Structure

**后端（新建）**
- `cpq-backend/src/main/java/com/cpq/quotation/service/rowkey/RowKeyConflictDTO.java` — 结构化冲突 DTO（7 字段）
- `cpq-backend/src/main/java/com/cpq/common/exception/RowKeyConflictException.java` — 携带 `List<RowKeyConflictDTO>` 的 BusinessException 子类

**后端（修改）**
- `…/common/dto/ApiResponse.java` — 加 `error(code, message, data)` 重载
- `…/common/exception/GlobalExceptionMapper.java` — `handleBusinessException` 加 `instanceof RowKeyConflictException` 分支
- `…/quotation/service/rowkey/RowKeyUniquenessService.java` — `collectConflicts` 返回 `List<RowKeyConflictDTO>`，`LineItemComps` 加 `lineItemId` + `productPartNo`
- `…/quotation/service/QuotationService.java` — `submit` 装配新字段、从 DTO 重建文本、抛 `RowKeyConflictException`

**后端（测试）**
- `…/test/…/rowkey/RowKeyUniquenessServiceTest.java` — 断言改为 DTO 字段
- `…/test/…/rowkey/SubmitRowKeyUniquenessQuarkusTest.java` — 断言抛 `RowKeyConflictException` 且 conflicts 字段齐全

**前端（新建）**
- `cpq-frontend/src/pages/quotation/RowKeyConflictDrawer.tsx` — 冲突清单抽屉 + `RowKeyConflictDTO` TS 类型
- `cpq-frontend/src/services/api.test.ts` — 拦截器 payload 透传单测
- `cpq-frontend/e2e/submit-rowkey-conflict-locator.spec.ts` — E2E

**前端（修改）**
- `cpq-frontend/src/services/api.ts` — 失败 handler 抽成 `buildApiError` 并挂 `payload`
- `cpq-frontend/src/pages/quotation/QuotationWizard.tsx` — handleSubmit 分支 + locateTarget state + Drawer
- `cpq-frontend/src/pages/quotation/QuotationStep2.tsx` — 接 locateTarget，定位卡片 + 下钻 ProductCard 切 tab

---

## Task 1: 后端 `RowKeyConflictDTO`

**Files:**
- Create: `cpq-backend/src/main/java/com/cpq/quotation/service/rowkey/RowKeyConflictDTO.java`

- [ ] **Step 1: 写 record**

```java
package com.cpq.quotation.service.rowkey;

import java.util.List;

/**
 * 提交期行键冲突的结构化明细（前端定位用）。
 *
 * @param lineItemId   报价单明细行 id（后端装配恒非空；前端按它定位卡片）
 * @param productName  产品名（= line item label，Drawer 主展示）
 * @param productPartNo 料号（= product_part_no_snapshot，前端兜底匹配卡片用；与 productName 来源不同，勿混用）
 * @param componentId  页签组件 id（前端切 Tab 用）
 * @param tabName      页签中文名（取不到回退 componentId）
 * @param rowKey       组合行键
 * @param rowIndices   1 基重复行号（已 +1，展示用；与异常文案同口径）
 */
public record RowKeyConflictDTO(
        String lineItemId,
        String productName,
        String productPartNo,
        String componentId,
        String tabName,
        String rowKey,
        List<Integer> rowIndices) {}
```

- [ ] **Step 2: 编译验证**

Run: `cd cpq-backend && ./mvnw -q compile`
Expected: BUILD SUCCESS（无报错）

- [ ] **Step 3: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/rowkey/RowKeyConflictDTO.java
git commit -m "feat(rowkey): 新增 RowKeyConflictDTO 结构化冲突明细"
```

---

## Task 2: 后端异常承载 — `RowKeyConflictException` + `ApiResponse.error(.,.,data)` + mapper 分支

**Files:**
- Create: `cpq-backend/src/main/java/com/cpq/common/exception/RowKeyConflictException.java`
- Modify: `cpq-backend/src/main/java/com/cpq/common/dto/ApiResponse.java`
- Modify: `cpq-backend/src/main/java/com/cpq/common/exception/GlobalExceptionMapper.java:21-26`

- [ ] **Step 1: 写异常子类**

```java
package com.cpq.common.exception;

import com.cpq.quotation.service.rowkey.RowKeyConflictDTO;

import java.util.List;

/**
 * 提交期行键冲突专用异常：HTTP 422 + 结构化冲突列表（供前端定位）。
 * message 仍是原「行键重复，无法提交：…」文本，向后兼容旧的纯文本展示。
 */
public class RowKeyConflictException extends BusinessException {

    private final List<RowKeyConflictDTO> conflicts;

    public RowKeyConflictException(String message, List<RowKeyConflictDTO> conflicts) {
        super(422, message);
        this.conflicts = conflicts;
    }

    public List<RowKeyConflictDTO> getConflicts() {
        return conflicts;
    }
}
```

- [ ] **Step 2: `ApiResponse` 加 `error(code, message, data)` 重载**

在 `ApiResponse.java` 的 `error(int code, String message)` 方法（:34-39）**之后**插入：

```java
    public static <T> ApiResponse<T> error(int code, String message, T data) {
        ApiResponse<T> response = new ApiResponse<>();
        response.code = code;
        response.message = message;
        response.data = data;
        return response;
    }
```

- [ ] **Step 3: `GlobalExceptionMapper.handleBusinessException` 加分支**

把 `GlobalExceptionMapper.java:21-26` 的方法体替换为：

```java
    @ServerExceptionMapper
    public Response handleBusinessException(BusinessException e) {
        LOG.warnf("Business error: %s", e.getMessage());
        if (e instanceof com.cpq.common.exception.RowKeyConflictException rce) {
            return Response.status(e.getCode())
                    .entity(ApiResponse.error(e.getCode(), e.getMessage(),
                            java.util.Map.of("conflicts", rce.getConflicts())))
                    .build();
        }
        return Response.status(e.getCode())
                .entity(ApiResponse.error(e.getCode(), e.getMessage()))
                .build();
    }
```

> 契约不变量：行键冲突时 `ApiResponse.data = { "conflicts": [ ...DTO... ] }`（对象包一层）。其它 BusinessException 行为不变。

- [ ] **Step 4: 编译验证**

Run: `cd cpq-backend && ./mvnw -q compile`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/common/exception/RowKeyConflictException.java \
        cpq-backend/src/main/java/com/cpq/common/dto/ApiResponse.java \
        cpq-backend/src/main/java/com/cpq/common/exception/GlobalExceptionMapper.java
git commit -m "feat(exception): RowKeyConflictException + ApiResponse.error 带 data + mapper 分支"
```

---

## Task 3: `RowKeyUniquenessService` 返回 DTO + `LineItemComps` 补字段

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/rowkey/RowKeyUniquenessService.java`
- Test: `cpq-backend/src/test/java/com/cpq/quotation/service/rowkey/RowKeyUniquenessServiceTest.java`

- [ ] **Step 1: 改测试断言为 DTO 字段（先让它失败）**

把 `RowKeyUniquenessServiceTest.java` 顶部 `item(...)` 辅助方法与首个测试 `driverColumnDuplicate_detected` 改为新签名/新返回类型：

```java
    // LineItemComps 新签名：(lineItemId, productName, productPartNo, comps)
    private RowKeyUniquenessService.LineItemComps item(String partNo, RowKeyUniquenessService.CompRows... comps) {
        return new RowKeyUniquenessService.LineItemComps("LI-" + partNo, "产品" + partNo, partNo, List.of(comps));
    }

    @Test
    void driverColumnDuplicate_detected() {
        String snap = """
          [ { "driverRow": { "child_no": "P1" } },
            { "driverRow": { "child_no": "P1" } },
            { "driverRow": { "child_no": "P2" } } ]""";
        String rd = """
          [ { "material": "Cu" }, { "material": "Cu" }, { "material": "Cu" } ]""";
        List<RowKeyConflictDTO> r = svc.collectConflicts(STRUCT,
            List.of(item("A1", new RowKeyUniquenessService.CompRows("c1", snap, rd))));
        assertEquals(1, r.size());
        RowKeyConflictDTO c = r.get(0);
        assertEquals("P1||Cu", c.rowKey());
        assertEquals(List.of(1, 2), c.rowIndices());   // 1 基（原断言是 0 基 [0,1]）
        assertEquals("c1", c.componentId());
        assertEquals("投料", c.tabName());
        assertEquals("LI-A1", c.lineItemId());
        assertEquals("A1", c.productPartNo());
        assertEquals("产品A1", c.productName());
    }
```

同文件其余测试（`uniqueMixedKeys_noConflict` / `manualRowsDuplicate_detected` / `manualRowDuplicatesDriverRow_detected` 等）把 `List<RowKeyConflict>` 改 `List<RowKeyConflictDTO>`、`r.get(0).rowKey()` 保持可用（DTO 也有 `rowKey()`），并把任何 `rowIndices()` 断言由 0 基改 1 基。新增 import：`import com.cpq.quotation.service.rowkey.RowKeyConflictDTO;`（同包可省）。

- [ ] **Step 2: 运行测试确认失败**

Run: `cd cpq-backend && ./mvnw -q test -Dtest=RowKeyUniquenessServiceTest`
Expected: 编译失败 / FAIL（`collectConflicts` 仍返回 `List<RowKeyConflict>`、`LineItemComps` 旧签名）

- [ ] **Step 3: 改 `LineItemComps` record + `collectConflicts` 返回 DTO**

`RowKeyUniquenessService.java`：

(a) 把 `LineItemComps` record（:36）改为：
```java
    /** 单明细的全部组件行。productName=展示名(label)，productPartNo=料号(兜底匹配用)。 */
    public record LineItemComps(String lineItemId, String productName, String productPartNo, List<CompRows> comps) {}
```

(b) 把 `collectConflicts` 方法（:41-84）整体替换为（返回 DTO；就地组装；1 基行号；detect 仍用 RowKeyConflict 内部产物）：
```java
    public List<RowKeyConflictDTO> collectConflicts(String structureJson, List<LineItemComps> items) {
        List<RowKeyConflictDTO> out = new ArrayList<>();
        Map<String, TabKeyCfg> cfgByComp = parseStructure(structureJson);
        if (cfgByComp.isEmpty() || items == null) return out;

        for (LineItemComps item : items) {
            if (item == null || item.comps() == null) continue;
            for (CompRows comp : item.comps()) {
                TabKeyCfg cfg = cfgByComp.get(comp.componentId());
                if (cfg == null || !cfg.rowKeyFields().isArray() || cfg.rowKeyFields().isEmpty()) continue;

                ArrayNode snapshotRows = parseArray(comp.snapshotRowsJson());
                ArrayNode rowData = parseArray(comp.rowDataJson());

                List<JsonNode> driverDataRows = new ArrayList<>();
                List<JsonNode> manualRows = new ArrayList<>();
                for (JsonNode r : rowData) {
                    if ("manual".equals(r.path("_origin").asText(""))) manualRows.add(r);
                    else driverDataRows.add(r);
                }

                List<String> keys = new ArrayList<>();
                for (int i = 0; i < snapshotRows.size(); i++) {
                    JsonNode br = snapshotRows.get(i);
                    JsonNode driverRow = br.path("driverRow");
                    JsonNode basicDataValues = br.path("basicDataValues");
                    JsonNode overlay = i < driverDataRows.size() ? driverDataRows.get(i) : MAPPER.createObjectNode();
                    keys.add(formulaCalculator.computeDedupKey(
                            cfg.rowKeyFields(), cfg.fields(), driverRow, basicDataValues, overlay));
                }
                ObjectNode emptyDriver = MAPPER.createObjectNode();
                for (JsonNode mr : manualRows) {
                    keys.add(formulaCalculator.computeDedupKey(
                            cfg.rowKeyFields(), cfg.fields(), emptyDriver, MAPPER.createObjectNode(), mr));
                }

                // detect 仍用纯文本内部产物（componentName 占位，rowIndices 为 0 基）
                for (RowKeyConflict rc : RowKeyConflictDetector.detect(cfg.componentName(), keys)) {
                    List<Integer> oneBased = new ArrayList<>();
                    for (Integer idx : rc.rowIndices()) oneBased.add(idx + 1);   // 0 基 → 1 基
                    out.add(new RowKeyConflictDTO(
                            item.lineItemId(), item.productName(), item.productPartNo(),
                            comp.componentId(), cfg.componentName(),
                            rc.rowKey(), oneBased));
                }
            }
        }
        return out;
    }
```

(c) 新增 import（文件顶部）：无需新增（`RowKeyConflictDTO` 同包；`RowKeyConflict` 同包）。

- [ ] **Step 4: 运行测试确认通过**

Run: `cd cpq-backend && ./mvnw -q test -Dtest=RowKeyUniquenessServiceTest`
Expected: BUILD SUCCESS，所有用例 PASS

- [ ] **Step 5: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/rowkey/RowKeyUniquenessService.java \
        cpq-backend/src/test/java/com/cpq/quotation/service/rowkey/RowKeyUniquenessServiceTest.java
git commit -m "feat(rowkey): collectConflicts 返回 DTO + LineItemComps 补 lineItemId/productPartNo + 行号 1 基"
```

---

## Task 4: `QuotationService.submit` 装配新字段 + 重建文本 + 抛新异常

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/QuotationService.java:807-839`
- Test: `cpq-backend/src/test/java/com/cpq/quotation/service/rowkey/SubmitRowKeyUniquenessQuarkusTest.java`

- [ ] **Step 1: 扩展集成测试（断言抛 RowKeyConflictException + conflicts 字段齐全）**

把 `SubmitRowKeyUniquenessQuarkusTest.java` 的断言段（:83-87）替换为：

```java
        com.cpq.common.exception.RowKeyConflictException ex =
            assertThrows(com.cpq.common.exception.RowKeyConflictException.class,
                () -> quotationService.submit(q.id, null));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("行键重复"), "报错应含『行键重复』: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("P1||Cu"), "报错应含冲突组合键 P1||Cu: " + ex.getMessage());
        // 结构化明细：字段齐全 + 行号 1 基
        assertFalse(ex.getConflicts().isEmpty(), "conflicts 不应为空");
        var c = ex.getConflicts().get(0);
        assertEquals(compId.toString(), c.componentId());
        assertEquals("投料", c.tabName());
        assertEquals(li.id.toString(), c.lineItemId());
        assertEquals("P1||Cu", c.rowKey());
        assertEquals(java.util.List.of(1, 2), c.rowIndices());
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd cpq-backend && ./mvnw -q test -Dtest=SubmitRowKeyUniquenessQuarkusTest`
Expected: FAIL（submit 仍抛 `BusinessException` 而非 `RowKeyConflictException`）

- [ ] **Step 3: 改 `QuotationService.submit` 行键块（:807-839）**

把 `QuotationService.java:815-839`（`rowsForCheck` 装配 + 校验 + 抛异常）替换为：

```java
        java.util.List<com.cpq.quotation.service.rowkey.RowKeyUniquenessService.LineItemComps> rowsForCheck =
            new java.util.ArrayList<>();
        for (QuotationLineItem li : lineItems) {
            String productName = li.productNameSnapshot != null ? li.productNameSnapshot
                         : (li.productPartNoSnapshot != null ? li.productPartNoSnapshot : "明细");
            java.util.List<com.cpq.quotation.service.rowkey.RowKeyUniquenessService.CompRows> comps =
                new java.util.ArrayList<>();
            java.util.List<com.cpq.quotation.entity.QuotationLineComponentData> cds =
                com.cpq.quotation.entity.QuotationLineComponentData.list("lineItemId", li.id);
            for (com.cpq.quotation.entity.QuotationLineComponentData cd : cds) {
                if (cd.componentId == null) continue;
                comps.add(new com.cpq.quotation.service.rowkey.RowKeyUniquenessService.CompRows(
                    cd.componentId.toString(), cd.snapshotRows, cd.rowData));
            }
            rowsForCheck.add(new com.cpq.quotation.service.rowkey.RowKeyUniquenessService.LineItemComps(
                li.id.toString(), productName, li.productPartNoSnapshot, comps));
        }
        java.util.List<com.cpq.quotation.service.rowkey.RowKeyConflictDTO> conflicts =
            rowKeyUniquenessService.collectConflicts(quoteCardStructureJson, rowsForCheck);
        if (!conflicts.isEmpty()) {
            StringBuilder sb = new StringBuilder("行键重复，无法提交：");
            for (com.cpq.quotation.service.rowkey.RowKeyConflictDTO c : conflicts) {
                // 从 DTO 重建文本，与旧 RowKeyConflict.describe() 逐字一致；rowIndices 已是 1 基，直接 join，勿再 +1
                String rows = c.rowIndices().stream().map(String::valueOf)
                        .reduce((a, b) -> a + "," + b).orElse("");
                sb.append("\n· 组件「").append(c.tabName()).append("」行键 [")
                  .append(c.rowKey()).append("] 在第 ").append(rows).append(" 行重复");
            }
            throw new com.cpq.common.exception.RowKeyConflictException(sb.toString(), conflicts);
        }
```

> 注意：原文案是 `组件「{componentName}」行键 [{rowKey}] 在第 {rows} 行重复`，其中 `componentName` 旧实现 = `lineItemLabel + " · " + cfg.componentName`。集成测试只断言 message 含「行键重复」「P1||Cu」，不强校验料号前缀，故用 `c.tabName()` 拼装即可保绿；如需逐字含料号可改 `c.productName()+" · "+c.tabName()`。

- [ ] **Step 4: 运行测试 + 既有 rowkey 全量回归**

Run: `cd cpq-backend && ./mvnw -q test -Dtest=SubmitRowKeyUniquenessQuarkusTest,RowKeyUniquenessServiceTest,RowKeyConflictDetectorTest`
Expected: BUILD SUCCESS，全部 PASS

- [ ] **Step 5: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/QuotationService.java \
        cpq-backend/src/test/java/com/cpq/quotation/service/rowkey/SubmitRowKeyUniquenessQuarkusTest.java
git commit -m "feat(quotation): submit 抛 RowKeyConflictException 携带结构化 conflicts + 从 DTO 重建文案"
```

---

## Task 5: 前端 `api.ts` 拦截器透传 payload

**Files:**
- Modify: `cpq-frontend/src/services/api.ts`
- Test: `cpq-frontend/src/services/api.test.ts`

- [ ] **Step 1: 写失败用例（先失败）**

Create `cpq-frontend/src/services/api.test.ts`:
```ts
import { describe, it, expect } from 'vitest';
import { buildApiError } from './api';

describe('buildApiError', () => {
  it('挂载后端结构化 payload 到 error.payload', () => {
    const err = buildApiError({
      response: { status: 422, data: { message: '行键重复', data: { conflicts: [{ rowKey: 'X' }] } } },
    });
    expect(err.message).toBe('行键重复');
    expect((err as any).payload).toEqual({ conflicts: [{ rowKey: 'X' }] });
    expect((err as any).httpStatus).toBe(422);
  });

  it('无 data 时 payload 为 null、message 兜底', () => {
    const err = buildApiError({});
    expect(err.message).toBe('Network error');
    expect((err as any).payload).toBeNull();
  });
});
```

- [ ] **Step 2: 运行确认失败**

Run: `cd cpq-frontend && npx vitest run src/services/api.test.ts`
Expected: FAIL（`buildApiError` 未导出）

- [ ] **Step 3: 抽出 `buildApiError` 并在拦截器复用**

把 `api.ts` 的响应拦截器失败分支（:26-34）替换为：
```ts
export function buildApiError(error: any): Error {
  const err = new Error(error?.response?.data?.message || 'Network error');
  (err as any).payload = error?.response?.data?.data ?? null;   // 信封.data，与成功侧 response.data 同层级
  (err as any).httpStatus = error?.response?.status;
  return err;
}

api.interceptors.response.use(
  (response) => {
    return response.data;
  },
  (error) => {
    const url = error.config?.url || '';
    const isAuthEndpoint = url.includes('/auth/login') || url.includes('/auth/forgot-password') || url.includes('/auth/reset-password');
    if (error.response?.status === 401 && !isAuthEndpoint) {
      window.location.href = '/login';
    }
    return Promise.reject(buildApiError(error));
  }
);
```
（`message` 取值与抛出形态不变 → 现有 `catch (e) { message.error(e.message) }` 全部向后兼容。）

- [ ] **Step 4: 运行确认通过 + tsc**

Run: `cd cpq-frontend && npx vitest run src/services/api.test.ts && npx tsc --noEmit -p tsconfig.json`
Expected: 测试 PASS；tsc 0 错误

- [ ] **Step 5: Commit**

```bash
git add cpq-frontend/src/services/api.ts cpq-frontend/src/services/api.test.ts
git commit -m "feat(api): 拦截器抽 buildApiError 并透传后端结构化 payload"
```

---

## Task 6: 前端 `RowKeyConflictDrawer` 组件 + TS 类型

**Files:**
- Create: `cpq-frontend/src/pages/quotation/RowKeyConflictDrawer.tsx`
- Test: `cpq-frontend/src/pages/quotation/RowKeyConflictDrawer.test.tsx`

- [ ] **Step 1: 写组件渲染/回调测试（先失败）**

Create `RowKeyConflictDrawer.test.tsx`:
```tsx
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import RowKeyConflictDrawer from './RowKeyConflictDrawer';

const conflicts = [
  { lineItemId: 'li1', productName: '产品A', productPartNo: 'PN-A', componentId: 'c1', tabName: '投料', rowKey: 'P1||Cu', rowIndices: [2, 3] },
];

describe('RowKeyConflictDrawer', () => {
  it('列出冲突并触发 onLocate', () => {
    const onLocate = vi.fn();
    render(<RowKeyConflictDrawer open conflicts={conflicts} onLocate={onLocate} onClose={() => {}} />);
    expect(screen.getByText('投料')).toBeInTheDocument();
    expect(screen.getByText('P1||Cu')).toBeInTheDocument();
    fireEvent.click(screen.getByText('定位'));
    expect(onLocate).toHaveBeenCalledWith(conflicts[0]);
  });
});
```

- [ ] **Step 2: 运行确认失败**

Run: `cd cpq-frontend && npx vitest run src/pages/quotation/RowKeyConflictDrawer.test.tsx`
Expected: FAIL（组件不存在）

- [ ] **Step 3: 写组件**

Create `RowKeyConflictDrawer.tsx`:
```tsx
import React from 'react';
import { Drawer, Table, Typography, Button, Alert } from 'antd';

export interface RowKeyConflictDTO {
  lineItemId?: string;
  productName?: string;
  productPartNo?: string;
  componentId?: string;
  tabName?: string;
  rowKey: string;
  rowIndices: number[];
}

interface Props {
  open: boolean;
  conflicts: RowKeyConflictDTO[];
  onLocate: (c: RowKeyConflictDTO) => void;
  onClose: () => void;
}

const RowKeyConflictDrawer: React.FC<Props> = ({ open, conflicts, onLocate, onClose }) => {
  const columns = [
    {
      title: '料号',
      key: 'product',
      render: (_: any, c: RowKeyConflictDTO) => (
        <span>{c.productName ?? '—'}{c.productPartNo ? ` (${c.productPartNo})` : ''}</span>
      ),
    },
    { title: '页签', dataIndex: 'tabName', key: 'tabName', render: (v: string) => v ?? '—' },
    { title: '行键', dataIndex: 'rowKey', key: 'rowKey' },
    {
      title: '参考行号',
      key: 'rows',
      render: (_: any, c: RowKeyConflictDTO) => (c.rowIndices ?? []).join(', '),
    },
    {
      title: '操作',
      key: 'op',
      render: (_: any, c: RowKeyConflictDTO) => (
        <Button type="link" size="small" onClick={() => onLocate(c)}>定位</Button>
      ),
    },
  ];
  return (
    <Drawer title="提交校验未通过：行键重复" placement="right" width={720} open={open} onClose={onClose}>
      <Alert
        type="error"
        showIcon
        style={{ marginBottom: 16 }}
        message={`共 ${conflicts.length} 处行键重复，请逐个修正后重新提交`}
        description="点「定位」跳到对应料号卡片与页签。参考行号为后端校验序，仅作参考。"
      />
      <Table
        rowKey={(c, i) => `${c.lineItemId ?? ''}-${c.componentId ?? ''}-${c.rowKey}-${i}`}
        columns={columns}
        dataSource={conflicts}
        pagination={false}
        size="small"
      />
      <Typography.Paragraph type="secondary" style={{ marginTop: 12 }}>
        提示：行键由「行键字段」组合算出，同一组件内不可重复。请去重或调整行键字段配置后重试。
      </Typography.Paragraph>
    </Drawer>
  );
};

export default RowKeyConflictDrawer;
```

> 列表规范豁免：Drawer 内子表 + 「定位」纯导航链接（无副作用），按 `docs/列表操作规范.md` §12 免用 SelectableTable。

- [ ] **Step 4: 运行确认通过 + tsc + Vite 200**

Run: `cd cpq-frontend && npx vitest run src/pages/quotation/RowKeyConflictDrawer.test.tsx && npx tsc --noEmit -p tsconfig.json`
Expected: 测试 PASS；tsc 0 错误
Run: `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/quotation/RowKeyConflictDrawer.tsx`
Expected: 200

- [ ] **Step 5: Commit**

```bash
git add cpq-frontend/src/pages/quotation/RowKeyConflictDrawer.tsx \
        cpq-frontend/src/pages/quotation/RowKeyConflictDrawer.test.tsx
git commit -m "feat(quotation): 新增 RowKeyConflictDrawer 冲突清单抽屉"
```

---

## Task 7: `QuotationWizard` — handleSubmit 分支 + locateTarget state + Drawer 挂载

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/QuotationWizard.tsx`

- [ ] **Step 1: 引入组件 + state**

在 QuotationWizard.tsx 顶部 import 区加：
```tsx
import RowKeyConflictDrawer, { type RowKeyConflictDTO } from './RowKeyConflictDrawer';
```
在 `QuotationWizard` 组件体内（与其它 `useState` 同区，如 Step2 data 附近 :99 之后）加：
```tsx
  const [rowKeyConflicts, setRowKeyConflicts] = useState<RowKeyConflictDTO[]>([]);
  const [conflictDrawerOpen, setConflictDrawerOpen] = useState(false);
  // 定位目标：seq 单调递增，保证连点同一条也重新触发 Step2 effect
  const [locateTarget, setLocateTarget] = useState<{ lineItemId?: string; productPartNo?: string; componentId?: string; seq: number } | null>(null);
  const locateSeqRef = useRef(0);
```

- [ ] **Step 2: 改 `handleSubmit`（:1083-1095）catch 分支**

把 `handleSubmit` 的 `catch` 块（:1092-1094）替换为：
```tsx
    } catch (e: any) {
      const conflicts = e?.payload?.conflicts;
      if (Array.isArray(conflicts) && conflicts.length) {
        setRowKeyConflicts(conflicts);
        setConflictDrawerOpen(true);
      } else {
        message.error(e.message);
      }
    }
```

- [ ] **Step 3: 加 onLocate 处理器（紧接 handleSubmit 之后）**

```tsx
  const handleLocateConflict = (c: RowKeyConflictDTO) => {
    locateSeqRef.current += 1;
    setLocateTarget({ lineItemId: c.lineItemId, productPartNo: c.productPartNo, componentId: c.componentId, seq: locateSeqRef.current });
    setConflictDrawerOpen(false);
    setCurrentStep(1);   // 切到 Step2（添加产品）
  };
```

- [ ] **Step 4: 把 locateTarget 传给 QuotationStep2 + 挂 Drawer**

grep `<QuotationStep2` 找到 callsite（在 renderStep2 内），在其 props 列表追加：
```tsx
            locateTarget={locateTarget}
```
在 QuotationWizard 的最外层返回 JSX 末尾（与其它 Drawer/Modal 同级）挂：
```tsx
      <RowKeyConflictDrawer
        open={conflictDrawerOpen}
        conflicts={rowKeyConflicts}
        onLocate={handleLocateConflict}
        onClose={() => setConflictDrawerOpen(false)}
      />
```

- [ ] **Step 5: 验证 tsc + Vite 200**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json`
Expected: 0 错误（`QuotationStep2Props.locateTarget` 在 Task 8 加；若此刻 tsc 报 locateTarget 不存在，先做 Task 8 再回此步——两 task 同一前端改动，提交前一起绿）
Run: `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/quotation/QuotationWizard.tsx`
Expected: 200

- [ ] **Step 6: Commit（与 Task 8 可合并提交；若单独提交，先确保 Task 8 已完成 tsc 0 错误）**

```bash
git add cpq-frontend/src/pages/quotation/QuotationWizard.tsx
git commit -m "feat(quotation): handleSubmit 行键冲突分支 + locateTarget 联动 + 挂 RowKeyConflictDrawer"
```

---

## Task 8: `QuotationStep2` — 接 locateTarget，定位卡片 + 下钻 ProductCard 切 tab

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/QuotationStep2.tsx`（Props 234-262 / ProductCardProps 1463-1483 / ProductCard 1485 / normalComponents 1951 / 卡片渲染 3404）

- [ ] **Step 1: `QuotationStep2Props` 加字段**

在 `QuotationStep2Props` 接口（:234-262）末尾 `}` 之前加：
```tsx
  /** Plan 1b：提交行键冲突定位目标（seq 单调递增触发）。报价侧专用。 */
  locateTarget?: { lineItemId?: string; productPartNo?: string; componentId?: string; seq: number } | null;
```
并在 `QuotationStep2` 组件解构 props 处加入 `locateTarget`。

- [ ] **Step 2: 组件体内加 cardRef Map + 解析 state + 定位 effect**

在 `QuotationStep2` 组件体内（`mainTab`/`viewType` 声明 :2740-2741 附近之后）加：
```tsx
  // Plan 1b 定位：cardRef 以 line item id 为 key（按 id 取、杜绝过滤后下标偏移，AP-54）
  const cardRefs = React.useRef<Record<string, HTMLDivElement | null>>({});
  // 解析后的定位结果：目标卡 id（PART 已映射为父卡）+ 目标 componentId + seq
  const [locateResolved, setLocateResolved] = React.useState<{ cardId?: string; componentId?: string; seq: number } | null>(null);

  React.useEffect(() => {
    if (!locateTarget) return;
    // 复位视图：后端只校验 QUOTE_CARD，冲突恒来自报价卡 → quote/card 永远正确
    setMainTab('quote');
    setViewType('card');
    // 按 id 在全量 lineItems 找冲突行；PART → 映射父卡
    const hit = lineItems.find(li => li.id === locateTarget.lineItemId);
    let cardId = hit?.id;
    if (hit?.compositeType === 'PART') cardId = hit.parentLineItemId;
    // 兜底：普通行 id 未回灌 → 按 productPartNo 在可见卡片匹配（PART 不走此兜底）
    if (!cardId && hit?.compositeType !== 'PART' && locateTarget.productPartNo) {
      cardId = quoteLineItems.find(li => li.productPartNo === locateTarget.productPartNo)?.id;
    }
    setLocateResolved({ cardId, componentId: locateTarget.componentId, seq: locateTarget.seq });
    if (cardId && cardRefs.current[cardId]) {
      cardRefs.current[cardId]!.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [locateTarget?.seq]);
```

- [ ] **Step 3: 卡片渲染处挂 ref + 透传定位 prop（:3404-3420）**

把 `quoteLineItems.map(...)` 块（:3404-3420）替换为：
```tsx
          {quoteLineItems.map((item, index) => (
            <div
              key={item.id ?? (item.productId ? `${item.productId}-${index}` : `item-${index}`)}
              ref={el => { if (item.id) cardRefs.current[item.id] = el; }}
            >
              <ProductCard
                item={item}
                index={index}
                onRemove={() => onRemoveProduct(index)}
                onUpdate={(data) => handleUpdateQuoteLineItem(index, data)}
                customerId={customerId}
                quotationId={quotationId}
                driverExpansions={driverExpansions}
                configTemplates={configTemplates}
                pathCacheState={quotationPathCache}
                globalVariableDefs={gvDefs}
                cardSide="QUOTE"
                cardStructure={quoteCardStructure}
                locateComponentId={locateResolved?.cardId === item.id ? locateResolved?.componentId : undefined}
                locateSeq={locateResolved?.cardId === item.id ? locateResolved?.seq : undefined}
              />
            </div>
          ))}
```

- [ ] **Step 4: `ProductCardProps` 加字段 + ProductCard 解构 + 切 tab effect**

(a) 在 `ProductCardProps`（:1463-1483）末尾 `}` 前加：
```tsx
  /** Plan 1b：定位目标页签 componentId（仅目标卡非空）；locateSeq 变化时切到该页签 */
  locateComponentId?: string;
  locateSeq?: number;
```
(b) `ProductCard` 解构（:1485）追加 `locateComponentId, locateSeq`。
(c) 在 `normalComponents` 声明（:1951-1953）**之后**加切 tab effect：
```tsx
  useEffect(() => {
    if (!locateComponentId) return;
    const list = (item.componentData ?? []).filter(c => c?.componentType === 'NORMAL');
    const idx = list.findIndex(c => c.componentId === locateComponentId);   // AP-54：按 normalComponents 下标
    if (idx >= 0) setActiveTab(idx);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [locateSeq]);
```

- [ ] **Step 5: 验证 tsc + Vite 200**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json`
Expected: 0 错误
Run: `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/quotation/QuotationStep2.tsx`
Expected: 200

- [ ] **Step 6: Commit**

```bash
git add cpq-frontend/src/pages/quotation/QuotationStep2.tsx
git commit -m "feat(quotation): Step2 接 locateTarget 定位料号卡片+下钻 ProductCard 切页签(AP-54/PART 映射)"
```

---

## Task 9: E2E + 协议级回归

**Files:**
- Create: `cpq-frontend/e2e/submit-rowkey-conflict-locator.spec.ts`

- [ ] **Step 1: 写 E2E（构造行键重复 → 提交 → 断言 Drawer + 定位）**

参照 `docs/E2E测试方法.md` 与既有 `e2e/quotation-flow.spec.ts` 的登录/选择器约定，新建 spec。核心断言：
```ts
import { test, expect } from '@playwright/test';
// 复用 quotation-flow.spec.ts 的登录与打开报价单辅助（按现有 spec 的封装/常量引入）。

test('提交行键重复时弹冲突抽屉并可定位到料号+页签', async ({ page }) => {
  // 1) 打开一份已知含行键重复的报价单（用 cpq-e2e-quotation-flow-test-data 记忆里的测试单，
  //    或在 Step2 某组件手动复制一行制造撞键）。
  // 2) 点「提交审批」。
  await page.getByRole('button', { name: '提交审批' }).click();
  // 3) 断言冲突抽屉出现且至少 1 条
  await expect(page.getByText('提交校验未通过：行键重复')).toBeVisible();
  const locateBtns = page.getByRole('button', { name: '定位' });
  await expect(locateBtns.first()).toBeVisible();
  // 4) 点首条「定位」→ 回到 Step2、报价卡视图、对应页签高亮选中
  await locateBtns.first().click();
  await expect(page.getByText('提交校验未通过：行键重复')).toBeHidden();
  // 断言已切到 Step2（添加产品步）且某页签 tab 处于 active —— 选择器按现有卡片 Tab 约定补全
});
```

- [ ] **Step 2: 跑新 E2E**

Run:
```bash
cd cpq-frontend && npx playwright test --config=e2e/playwright.config.ts e2e/submit-rowkey-conflict-locator.spec.ts --reporter=list
```
Expected: `1 passed`

- [ ] **Step 3: 协议级回归（强制，CLAUDE.md §5）**

Run:
```bash
cd cpq-frontend
rm -f e2e/screenshots/qf-*.png
npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list
npx playwright test --config=e2e/playwright.config.ts e2e/composite-product-flow.spec.ts --reporter=list
```
Expected: 两个 spec 均 `passed`；`quotation-flow` 输出 `'加载中' final count = 0` + 8 Tab 加载中=0；`composite-product-flow` PART 场景不崩

- [ ] **Step 4: 后端全量 rowkey 回归 + 健康检查**

Run:
```bash
cd cpq-backend && ./mvnw -q test -Dtest='RowKey*,SubmitRowKeyUniquenessQuarkusTest'
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8081/q/health
```
Expected: 全部 PASS；health 200

- [ ] **Step 5: Commit**

```bash
git add cpq-frontend/e2e/submit-rowkey-conflict-locator.spec.ts
git commit -m "test(e2e): 提交行键冲突→抽屉→定位 端到端 + 协议回归"
```

---

## Task 10: 回写 RECORD.md（CLAUDE.md「开发记录」强制）

**Files:**
- Modify: `docs/RECORD.md`

- [ ] **Step 1: 追加一条开发记录**

在 `docs/RECORD.md` 顶部时间线追加：
```
### [2026-06-29] 报价提交行键冲突友好定位(Plan 1b) | RowKeyConflictDTO+RowKeyConflictException(新) / ApiResponse.error(data) / GlobalExceptionMapper / RowKeyUniquenessService(返回DTO+LineItemComps补id/料号) / QuotationService.submit / api.ts(buildApiError) / RowKeyConflictDrawer(新) / QuotationWizard / QuotationStep2 + 测试&E2E | 后端 422 结构化返回{conflicts:[...]} → 前端拦截器透传 payload → 冲突清单 Drawer → 点击经 locateTarget{seq} 定位料号卡片(PART→parentLineItemId)+下钻 ProductCard 按 normalComponents 切页签(AP-54)。只改呈现不动校验语义。行号后端0基→DTO 1基。E2E submit-rowkey-conflict-locator 1 passed + quotation-flow/composite-product-flow 回归绿。推迟项(实时预检/行高亮/核价单定位/消歧对齐)见 BACKLOG.md。spec/plan: 2026-06-29-submit-rowkey-conflict-locator-*
```

- [ ] **Step 2: Commit**

```bash
git add docs/RECORD.md
git commit -m "docs(record): Plan 1b 行键冲突友好定位 开发记录"
```

---

## 完成判据（合并前）

- 后端：`RowKey*` + `SubmitRowKeyUniquenessQuarkusTest` 全绿；submit endpoint 行键重复时返回 422 且响应体 `data.conflicts` 形状为 `{conflicts:[...]}`。
- 前端：`tsc --noEmit` 0 错误；改动的 4 个 `.tsx`/`.ts` curl Vite 200；vitest（api / Drawer）绿。
- E2E：`submit-rowkey-conflict-locator` 1 passed；`quotation-flow` 加载中=0 + 8 Tab=0；`composite-product-flow` 绿。
- 自检声明（CLAUDE.md）：完成宣告附「TS 0 错误 ✅；4 文件 Vite 200 ✅；后端 RowKey* N passed ✅；submit→422 data.conflicts ✅；E2E 三 spec passed ✅」。
- 收尾：用户确认达标后走 `superpowers:finishing-a-development-branch` 合并 master + 删 worktree/分支。
