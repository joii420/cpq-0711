# task-0806 模板发布全量冻结 —— 前端任务文档

> 执行依据：`需求文档.md`（FR-10 / AC-13）｜接口契约：`api.md`｜分支：`feat/task-0806-template-freeze`

---

## 0. 一句话

**本期前端只做一件事**：把 `CopyQuotationDrawer.tsx` 里两处**与实际行为不符的文案**改成实话。不新增页面、不新增接口调用、不碰任何渲染契约。

---

## 1. 为什么前端改动这么小（先说清楚，别以为漏了）

| 事项 | 前端是否要动 | 原因 |
|---|---|---|
| 新表 `template_component_snapshot` | ❌ | 纯后端存储层 |
| `components_snapshot` 改为派生 | ❌ | **契约不变**（AC-2 要求逐字段一致）。`BulkImportPartsDrawer.tsx:123`、`QuotationStep2.tsx:3844`、`ExcelViewConfigTab`、`enrichComponentData` 全部无感 |
| 10 处渲染读取点收口 | ❌ | 后端内部重构，接口与返回值不变 |
| `frozen-drift` 差异视图 | ❌ | **D10：本期只做 API，无 UI** → `BL-0139` |
| 3 个 admin 后门加 confirm | ❌ | `SYSTEM_ADMIN` 运维端点，前端从未调用（已 grep 确认） |
| 删除的 A8 / A9 两个端点 | ❌ | 前端从未调用（已 grep 确认） |
| 换模板护栏（FR-10） | ✅ | **唯一改动** |

> 🎯 **前端「几乎零改动」本身是本期的验收资产**：现有 vitest 全绿 = 后端派生逻辑没改变 jsonb 形状的直接证据（AC-2）。任何渲染相关的前端测试挂掉，都说明后端改坏了契约，**不要去改前端迁就它**。

---

## 2. 唯一改动点：`CopyQuotationDrawer.tsx`

**文件**：`cpq-frontend/src/pages/quotation/CopyQuotationDrawer.tsx`（97 行，Drawer，width 480）

### 2.1 现状与问题

| 位置 | 现状 | 实际发生的事 |
|---|---|---|
| `:70` | 普通 `<p>`，**零视觉警示**，Drawer 打开即显示<br>「默认使用源报价单的模板。换模板后：页签相同的**迁移用户输入值**，不同的留空，**公式/数据由新模板重算**。」 | ❌ 两个承诺都没发生 |
| `:84-91` | `Alert type="warning" showIcon`（**样式已经是对的**），仅 `changed === true` 时显示<br>「已更换模板：仅页签字段相同的**输入值会被迁移**，其余留空。」 | ❌ 同上 |

**真相**（`BL-0129`，已实测非读代码推断）：`QuotationService:1670` 换模板复制时**显式清空 `snapshot_rows`**（既有设计语义「留空待重建」）→ 产品行无数据、总价恒 0，且**三条恢复路径全部失败**：

```
ensure-card-values     → 0   （qcv 非 NULL，IS NULL 谓词跳过）
refresh-card-snapshot  → 0   （force 重算，但没数据可算）
saveDraft + 懒算       → 0   （置 NULL 后重算，仍是 0）
```

用户只能回向导重新配置产品行、重新展开 driver。

**为什么现在必须改**：`BL-0133` 落地严格版本化后，「想用上新组件配置」的唯一路径就是「发新版 → 已有报价单换模板」。**这条已坏的路径会从边缘操作变成主干道**，继续挂着骗人的文案不可接受。

### 2.2 要改成什么

1. **`:70` 的裸 `<p>` → 恒显示的 `Alert type="warning" showIcon`**，文案必须覆盖三层信息：
   - 换模板**会清空已填数据**
   - **当前无法恢复**（关联 `BL-0129`）
   - **请先导出留档**
2. **`:84-91` 的换模板 Alert** 保留 `type="warning"`，**文案改成实话** —— 删掉「输入值会被迁移」的承诺
3. 全组件**不得**再出现「迁移用户输入值」「公式/数据由新模板重算」这类表述（AC-13 用 grep 验收）

> 文案由前端工程师拟具体措辞，但**三层信息一个都不能少**，且不许再出现任何"数据会保留/会迁移/会重算"的暗示。

### 2.3 明确不做

- 🚫 **不加二次确认弹窗** —— 本期只做知情告警。加确认流程属于改交互，超出 D8「加一道护栏」的范围
- 🚫 **不改 `handleOk` 逻辑 / 不改接口调用 / 不改 `onConfirm` 契约**
- 🚫 **不修 `BL-0129` 本身** —— 根因在后端另一条链路（`QuotationService:1670`），本期不碰

---

## 3. 交互流程（改动后）

```
用户在报价单详情点「复制」
      ↓
CopyQuotationDrawer 打开
      ↓
【新】恒显示 warning Alert：会清空已填数据 / 无法恢复 / 请先导出
      ↓
模板下拉（仅 PUBLISHED + QUOTATION，size 200）
      ↓
若 selected !== defaultTemplateId  →  额外显示第二个 warning Alert（文案已改为实话）
      ↓
点「确认复制」→ onConfirm(templateId)   ← 逻辑完全不变
```

---

## 4. 状态管理与缓存

**无变化。** 组件现有 4 个 `useState`（`templates` / `selected` / `loading` / `submitting`）与 `changed` 派生量全部保持原样。无新增缓存 key，无新增 `useEffect`。

---

## 5. 调用的接口

**无新增。** 仍只调 `templateService.list({ status: 'PUBLISHED', templateKind: 'QUOTATION', size: 200 })`（`:28-29`）。

`api.md` 中 A2~A9 全部是 `SYSTEM_ADMIN` 运维端点，**本期前端一个都不调**。

---

## 6. 边界与空态

| 场景 | 行为（均保持现状） |
|---|---|
| 模板列表加载中 | `Select` 显示 loading |
| 模板列表加载失败 | `message.error`（`:34`） |
| 未选模板点确认 | `message.warning('请选择模板')`（`:40`） |
| 未换模板（`changed === false`） | 只显示【新】那个恒显示 Alert，不显示第二个 |
| 提交中 | 按钮 loading（`:64`） |

---

## 7. 自检项

- [ ] `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json` → **0 错误**
- [ ] `curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:5174/src/pages/quotation/CopyQuotationDrawer.tsx` → **200**
  > ⚠️ 本机 shell 常设 `http_proxy=127.0.0.1:7890`，探 localhost **必须** 加 `--noproxy '*'`，否则走代理返 502
- [ ] `curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:5174/` → **200**
- [ ] 现有 vitest **全绿**（本期未改任何渲染逻辑，挂了就是后端改坏了 jsonb 契约，**不要改前端迁就**）
- [ ] AC-13 grep 验收：`/usr/bin/grep -an "迁移用户输入值\|输入值会被迁移\|由新模板重算" cpq-frontend/src/pages/quotation/CopyQuotationDrawer.tsx` → **零命中**
  > ⚠️ 必须 `/usr/bin/grep -a`：本环境 `grep` 是 ugrep，中文多的文件会被静默判为二进制返空，据空结果下结论会假绿

### 是否触发 E2E？

**本文件改动不触发** —— 它不在 CLAUDE.md 的 E2E 强制清单里（`QuotationStep2.tsx` / `useDriverExpansions.ts` / `ReadonlyProductCard.tsx` 等）。

但**本任务整体必须跑双 spec E2E**（AC-18），因为后端动了 `CardSnapshotService` / `ConfigureSnapshotService` / `ExcelViewService` 三个协议级文件。E2E 由主线亲跑，见 `backtask.md` §7。

---

## 8. UI 规范符合性

| 规范 | 本次情况 |
|---|---|
| Drawer 替代 Modal | ✅ 已是 Drawer，未引入 Modal |
| 列表走 SelectableTable + 工具栏动作 | 不适用（非列表页） |
| 危险动作二次确认 | 不适用 —— D8 明确本期只做**知情告警**，不加确认流程 |

---

## 9. Task 列表

- [ ] **F1** `:70` 裸 `<p>` 改为恒显示的 `Alert type="warning" showIcon`，文案覆盖「会清空已填数据 / 当前无法恢复 / 请先导出留档」三层
- [ ] **F2** `:84-91` 换模板 Alert 文案改为实话，删除「输入值会被迁移」承诺
- [ ] **F3** 跑 §7 全部自检项，附证据

---

## 10. 红线

1. 🚫 不改 `handleOk` / `onConfirm` / 接口调用 —— 本期只动文案与告警呈现
2. 🚫 不加二次确认弹窗（超出 D8 范围）
3. 🚫 渲染相关 vitest 挂了**不许改前端迁就** —— 那是后端 AC-2 没达标的信号，回头找后端
4. 🚫 `git add -A`（并发会话互相夹带）；只 add 本次明确改动的文件，提交后 `git show --stat` 自查
5. 🚫 据 ugrep 空结果下「零命中」结论（必须 `/usr/bin/grep -a`）
