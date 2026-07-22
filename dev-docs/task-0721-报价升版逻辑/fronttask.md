# 报价数据版本升级 · 前端任务（fronttask.md）

> 依据 `需求说明.md` + `api.md`。技术栈 React + Ant Design + TypeScript。
> 立项初稿写「前端无需修改」，澄清后**需两处改动**（回填影响预览 + 刷新二次确认）。其余延迟生效/回填全在后端，前端渲染仍纯读 `snapshot_rows`，**卡片渲染逻辑零改动**。
> **UI 规范**：弹出式交互一律用 `Drawer`（右侧滑出），危险/知情确认列出明细，遵循 `docs/列表操作规范.md`。

---

## F1 · 回填影响预览抽屉（核心）

**场景**：财务在核价单/财务工作台点「核价通过」时，先展示本次通过将对基础数据造成的增/删/改，确认后才真正提交。

**交互流程**：
1. 财务点「核价通过」→ 不直接提交，先调 `GET /quotations/{id}/costing-approve/preview`。
2. 打开 `Drawer`（`placement="right"`，宽度按内容 720/960）：
   - **顶部汇总**：将升版 N 组 / 新增 X 行 / 删除 Y 行 / 改值 Z 行。
   - **分组列表**（按 `groups`）：每组显示 目标表(展示名用 `tabName`) / 轴摘要(`groupKey`) / 版本 `versionFrom → versionTo`。
     - **全局共享表**（`isGlobalShared=true`，如电镀方案 `plating_scheme`）**重点标注**（红/橙 Tag「全局共享，影响所有客户」），因其改动面最大（见需求 R-4）。
   - **逐行明细**（可展开）：
     - `op=CHANGE`：列出变更列 `列名：旧值 → 新值`。
     - `op=ADD`：绿色标「新增」+ 行值。
     - `op=DELETE`：红色删除线标「删除」+ 行值。
3. 底部两按钮：
   - **「确认通过」**：调 `POST /quotations/{id}/costing-approve`，Body 带回 `previewToken`（预览响应里拿的）。成功后关抽屉、刷新核价单状态。
   - **「取消」**：关抽屉，不提交。
4. **空影响**（`summary` 全 0）：抽屉提示「本次通过无基础数据变更，仅完成审核状态流转」，仍可点「确认通过」（后端要 flip 闸门/状态）。

**错误处理**：
- 提交返回 **409**（`previewToken` 漂移）：message.error「报价数据在预览后发生变化，请重新预览」→ 自动重新拉 preview 刷新抽屉。
- 400/403/500：按 `ApiResponse.message` 提示，不关抽屉（除 500 整体失败可关并提示重试）。

**技术点**：
- 新建组件 `CostingApprovePreviewDrawer.tsx`（遵循 Drawer 规范）。
- `costingOrderService.approve` 签名从 `(quotationId, comment?)` 改为 `(quotationId, previewToken, comment?)`（api.md §1.2）。同步改 `quotationService` 若也有核价通过入口。
- 新增 `costingOrderService.previewApprove(quotationId)` → `GET .../costing-approve/preview`。

---

## F2 · 「刷新基础数据」二次确认

**场景**：Step2 现有「刷新基础数据」按钮（触发 `POST /quotations/{id}/refresh-card-snapshot` → 重 expand）会吃到回填后的最新版本数据。保留功能，但点击前弹二次确认，避免用户以为「打开就变了」。

**交互**：
- 点按钮 → `Modal.confirm`（AntD 轻量确认，属规范例外白名单）或 `Popconfirm`：
  - 文案：「刷新后本单基础数据将更新为最新已审核版本，未提交的本单编辑保留。是否继续？」
  - 确认 → 原刷新逻辑；取消 → 无操作。
- **仅 DRAFT 态**显示/可用（沿用现状；非草稿本就 no-op）。

**技术点**：包一层确认，不改刷新本身逻辑。定位现有触发处（Step2 工具栏「刷新基础数据」按钮的 onClick）。

---

## F3 · 撤回提示文案（轻量）

**场景**：已「核价通过」的报价单撤回时，需让用户知道**基础数据不回退**（规则七：撤回不回滚）。

**交互**：
- 撤回入口（若当前状态为 APPROVED）→ 确认弹窗补一句：「撤回仅使报价单回到可编辑，**已回填生效的基础数据不会回退**。」
- 非 APPROVED 撤回（SUBMITTED/COSTING_REJECTED）文案不变。

**技术点**：现有撤回确认加条件文案，无新端点。

---

## 不做 / 非目标

- 报价卡片树渲染、加叶子、剪枝、页签类型 —— 属**树任务**（`task-0721-报价侧树状结构与页签类型属性`），本任务不实现。
- 「从已有产品添加」列表 —— 后端加闸门谓词，**前端零改动**（响应结构不变）。
- pending / `__v6_id` / 版本号 —— 前端无感知，不展示。
- 无「部分勾选回填」UI（预览只读，规则六）。

---

## 修改后强制自检（本任务必跑）

1. `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json` → 0 错误。
2. 对每个改动 `.tsx` 跑 `curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:5174/src/<相对路径>` → 200。
3. 新建 `CostingApprovePreviewDrawer.tsx` → 主入口 `curl .../` 200。
4. **联调**：核价通过全流程（预览抽屉出明细 → 确认 → 状态流转；改数据后提交 409 → 自动重预览）真机点通。
5. 完成宣告含「已自检」声明行（TS 0 错误 / 各 tsx Vite 200 / 联调通过 ✅）。

> 前端不含协议级 driver 改动，无强制 E2E；但 F1 联调须真机走一遍核价通过。
