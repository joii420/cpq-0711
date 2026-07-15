# 前端任务文档 — 核价管理·核价单版本选择（task-0713）

> 权威依据：`需求说明.md` §「需求澄清纪要与设计定稿」+ `api.md`。
> 🔶 = 依赖 cpq-architect 对 D1/D2/D3 结论，评审后按结论施工。
> 技术栈：React + Ant Design + Vite(5174)。UI 规范：抽屉替代弹窗；列表用 SelectableTable（本任务基本不涉列表改动）。

---

## 0. 必读约束（开工前）

- `docs/E2E测试方法.md`、`docs/反模式.md` AP-31/37/50/54、`docs/三大核心模块基线.md`。
- **协议级文件**（改动必跑 E2E）：`QuotationStep2.tsx` / `ReadonlyProductCard.tsx` / `ProductDetailViews.tsx` / `usePathFormulaCache.ts` / `useDriverExpansions.ts`。
- 前端只认约定列 **`view_version`**：核价侧任一卡片行含 `view_version` → 该行显示版本下拉；无则不显示。
- 报价侧维持现状（读 frozen），**不要动报价侧渲染**。

---

## F1 · 核价管理打开核价单：修复空白 + 核价侧改数据源

- `CostingReviewPage.tsx`：`getById(coid)` 后，报价侧继续走 `frozenDto`（不变）；🔶 **核价侧改用响应里的 `costingRender` / `costingTotalAmount`**（D1）。
- `ProductDetailViews.tsx`：核价单视图（`mainTab==='costing'`）的卡片/Excel 数据源切到核价侧重算数据；报价单视图不变。空白 bug 通过"核价侧有正确数据源"消除。
- 传入 `editable`（= PENDING + 财务/管理员）向下透给卡片，决定是否显示切换控件。

## F2 · 核价树 / 非树页签：版本下拉控件

- 新组件 `VersionSelectDropdown.tsx`（复用于树页签与非树页签）：
  - 入参：`coid, lineItemId, componentId, partNo, currentVersion, disabled`。
  - 展开时按需 `GET /version-options` 拉可选版本（**倒序**已由后端保证），高亮 `currentVersion`。
  - 选择即触发 F3 切换。
- 挂载点：`ReadonlyProductCard.tsx`（核价侧 COSTING 分支）渲染每行时，**若该行数据含 `view_version`** 且 `editable`，在该行"料号/版本"位置渲染下拉；否则纯文本显示 `view_version`（只读）或不显示。
  - 树页签：挂在每个带版本的料号节点上。
  - 非树页签：挂在每个销售料号分组上（一个料号一组，组内多行共享一个下拉）。
- 反 AP-50：核价侧渲染仍走只读 `ReadonlyProductCard` 的既有分支，仅**新增**版本下拉这一叠加层，不复制渲染逻辑。

## F3 · 切换动作 + 定向刷新

- 选定版本 → `POST /version-switch`（`api.md §3`）→ 用返回的 `costingCardValues`/`costingExcelColumns`/`costingTotalAmount` **只刷新当前卡片 + 单据总价**，不整页重载、不动其他卡片。
- 切换中 loading 态；失败按 `message.error` 显示后端错误原文（BL-0030 语义，不静默）。
- **严禁**在切换回调里触发全量 batch-expand / 重新 `getById` 整单（守 AP-31，避免"加载中…"风暴）；只用返回值增量更新受影响卡片。

## F4 · 权限 / 状态门

- 仅当 `editable === true`（`status==='PENDING'` 且角色 ∈ 财务/管理员）显示版本下拉可交互；否则版本号只读展示。
- 核价管理菜单本身已限财务/管理员（系统权限），前端沿用。

## F5 · 单据总价联动（3a）

- 核价侧单据总价展示位读 `costingTotalAmount`；切换后用 `version-switch` 返回值即时更新。
- 精度守 `cpq-decimal-display-policy`（对外总额 2 位，计算列 4 位）。

## F6 · 服务层

- `costingOrderService.ts` 增：`getVersionOptions(coid, {lineItemId, componentId, partNo})`、`switchVersion(coid, {lineItemId, componentId, partNo, viewVersion})`；`getById` 类型扩 `costingRender/costingTotalAmount/versionOverrides/editable`。

---

## 自检（完成前必跑，写入"已自检"声明）

1. `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json` → 0 错误。
2. 每个改动 `.tsx`：`curl -s -o /dev/null -w '%{http_code}' --noproxy '*' http://localhost:5174/src/<相对路径>` → 200。
3. **协议级 E2E**：`quotation-flow.spec.ts` 全绿、`'加载中' final count = 0`、8 Tab `'加载中'=0`；核价树切版本前后不出"加载中…"、行数稳定（AP-51/54）。
4. 手测三条主路径截图：① 核价管理打开核价单不再空白（报价侧+核价侧都出数据）；② 主树切版本→整卡刷新+总价变；③ 非树页签切某料号版本→仅该料号该页签整组变、其他不动。
5. worktree 前端自检坑：共享 5174 服务是主仓非 worktree，须软链 node_modules + 另端口临时 vite 验证真实改动文件（守 `cpq-worktree-frontend-selfcheck`）。
