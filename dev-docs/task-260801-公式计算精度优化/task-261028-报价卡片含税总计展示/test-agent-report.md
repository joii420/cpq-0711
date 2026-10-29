# 独立测试代理报告

> 执行日期：2026-10-28  
> 分支：`feat/task-261028-quote-card-vat-total`  
> worktree：`/home/joii/project/cpq/.claude/worktrees/task-261028-quote-card-vat-total`  
> Browser 分类：**Browser plugin not available**；已按规范改用仓库 Playwright + 系统 Chrome 通道。本轮只做 Playwright 用例发现/装载，未连接服务。

## 1. 独立性与边界

- 用例仅从本任务 AC、`test.md`、`fronttask.md`、`api.md`和定稿原型派生。
- 未读取前端代理本轮生产实现正文或 diff；仅与实现代理对齐了公开黑盒契约：命名导出 `QuotationVatSubtotal`，props 为 `subtotal`、`vatRateRatio?`、`vatRate?`、`currency?`、`maxDisplayDecimals`。
- 未修改生产源码、样式、后端、数据库、现有测试、package/lock 或部署产物。
- 新增三个任务专用测试文件：
  - `cpq-frontend/src/pages/quotation/task261028-vat-subtotal.test.tsx`
  - `cpq-frontend/e2e/task261028-vat-subtotal.config.ts`
  - `cpq-frontend/e2e/task261028-vat-subtotal-flow.spec.ts`

## 2. 结果摘要

| 项 | 结果 | 可复核证据 |
|---|---|---|
| S-UNIT 专项 | ✅ 12/12 | 精确两行文案、4/9 位、ratio 优先级、0/1 边界、百分比回退、缺失/非法/越界降级、超出 JS safe integer 的 Decimal 鉴别值 |
| 专项 + 存量精度回归 | ✅ 30/30 | 3 个 test files：本任务 12 + `task261027RoleDisplayPrecision` 4 + `ComponentCell.readonlyPrecision` 14 |
| TypeScript | ✅ | `npx tsc -b`，exit 0；包含 src test 和 e2e 独立 TS project |
| 新增测试 ESLint | ✅ | 3 个新文件，exit 0 |
| 生产构建 | ✅ | Vite 8.0.8，3445 modules transformed，exit 0；仅既有大 chunk warning |
| Playwright 用例装载 | ✅ 4/4 被发现 | T-04~T-07 各 1 例，exit 0；使用虚拟的安全环境变量执行 `--list`，未连服务/库 |
| Playwright 隔离守卫证伪 | ✅ | 故意传入共享 `5174/8081 + cpq_db_0724`，配置在装载期硬失败，exit 1 |
| S-FLOW 真实执行 | ⚠️ 未执行 | 当前未提供独立 DB、独立前/后端端口、`T261028_VAT_` 私有账号和两张 `T261028_VAT_FLOW_` 报价夹具；为避免污染共享数据，未连 `5174/8081`、`5173/8201` 或固有测试库 |

## 3. AC 覆盖与本轮状态

| AC | 测试 | 本轮结果 | 状态边界 |
|---|---|---|---|
| AC-1 | T-01 SSR + T-04 E2E | SSR 精确文本通过 | 编辑页真实卡片待 S-FLOW |
| AC-2 | T-02 SSR | ✅ 4/9 位精确值通过 | 不以页面权限路由代替主线亲验 |
| AC-3 | T-04 E2E | 用例已装载 | 编辑→详情→编辑刷新待隔离栈 |
| AC-4 | T-03 SSR | ✅ 9 类来源/边界夹具通过 | ratio=0/1、fallback、priority、missing、NaN、negative、>1 |
| AC-5 | T-05 API + E2E | 用例已装载 | 保存与 `finally` 精确还原待隔离私有报价 |
| AC-6 | T-06 E2E | 用例已装载 | 核价编辑/只读反向待隔离栈 |
| AC-7 | T-07 E2E | 用例已装载 | 1280×720 / 390×844 几何断言与截图待长值夹具 |
| AC-8 | 命令组 | 部分通过 | typecheck/unit/regression/build 已绿；专项 Playwright 真实流程未执行 |

## 4. 执行命令与结果

工作目录均为 `cpq-frontend/`。

1. `npx vitest run src/pages/quotation/task261028-vat-subtotal.test.tsx`
   - 首轮：11/12，exit 1。失败来自测试预期把 9 位小数 `10178135157857322.090000001` 误写成 `.09`；实现输出符合“最多 9 位小数 + 去尾零”。已修正测试预期，未改实现。
   - 复跑：12/12，exit 0。
2. `npx eslint src/pages/quotation/task261028-vat-subtotal.test.tsx e2e/task261028-vat-subtotal.config.ts e2e/task261028-vat-subtotal-flow.spec.ts`
   - exit 0。
   - 收尾复跑时曾有一次在仓库根目录误用了前端相对路径，ESLint 报 `No files matching` 并 exit 2；这是执行目录错误，未进入检查。切回 `cpq-frontend/` 用同一命令复跑后 exit 0。
3. `npx tsc -b`
   - 首轮：exit 2，新 E2E 将页面宽度误写为 `document.scrollWidth/clientWidth`，TS2339 硬失败。
   - 修正为 `document.documentElement.scrollWidth/clientWidth` 后复跑：exit 0。
4. `npx vitest run src/pages/quotation/task261028-vat-subtotal.test.tsx src/utils/task261027RoleDisplayPrecision.test.ts src/pages/quotation/components/ComponentCell.readonlyPrecision.test.tsx`
   - 3 files / 30 tests 全绿，exit 0。
5. `npm run build`
   - `tsc -b && vite build`，exit 0；3445 modules transformed；仅既有 chunk-size warning。
6. `pgrep -af 'node.*[p]laywright test' || true`
   - 每次 Playwright 命令前采样均无匹配进程；未 kill/干预他任务。该结论仅代表各次当时采样。
7. 安全虚拟环境变量 + `npx playwright test -c e2e/task261028-vat-subtotal.config.ts --list`
   - 4 tests in 1 file，exit 0；未发起 HTTP/数据库请求。
8. 故意传入 `PW_BASE_URL=:5174`、`PW_BACKEND_URL=:8081`、`PW_DB=cpq_db_0724` 再执行 `--list`
   - 如期在 config 加载期拒绝共享环境，exit 1，证明新隔离守卫非恒绿。
9. `git diff --check -- <三个新测试文件>`
   - exit 0。

## 5. 执行 S-FLOW 所需前置

专项 config 要求：

- `PW_BASE_URL` / `PW_BACKEND_URL`：本 worktree 独立端口，严禁 5173/5174/8081/8201。
- `PW_DB`：匹配 `cpq_t261028_vat_*`，严禁 `cpq_db`、`cpq_db_0724`、`cpq_db_test`。
- `PW_VAT_ADMIN_USER` / `PW_VAT_SALES_MANAGER_USER`：独立账号且各以 `T261028_VAT_` 开头，以及对应密码。
- 默认夹具：`PW_VAT_QUOTATION_ID`、`PW_VAT_QUOTATION_NO`、`PW_VAT_PART_NO`；报价号/料号以 `T261028_VAT_FLOW_` 开头，含税前 `1717.135540717`、ratio `0.13`、USD，且具有同料号核价快照。
- 长值夹具：`PW_VAT_LONG_QUOTATION_ID`、`PW_VAT_LONG_PART_NO`；税前 `99999999999999.999999999`、ratio `0.99999999999`、USDT。
- 证据：`PW_VAT_EVIDENCE_DIR` 与 `PW_VAT_OUTPUT_DIR` 应指向独立临时目录，不覆盖他任务证据。
- 开跑前仍须重新执行 Playwright 进程采样，并先预热一次目标页。

## 6. 原型算术笔误

定稿原型 06 的长值演示写为 `199999999998999.999999999`，但已定稿公式的 Decimal 精确结果是：

`99999999999999.999999999 × (1 + 0.99999999999) = 199999999998999.99999999800000000001`

按 12 位工作边界/最多 9 位展示后均为 `199999999998999.999999998`。主线已裁定这是原型演示算术笔误，不改文案/布局/计算规则；测试已按公式使用 `...998`，本代理未修改原型或任务文档，留给主线统一更正。

## 7. 剩余风险与证据

- **未验证**：真实报价编辑/详情往返、保存 JSON 逐字比对、核价反向、390 px 几何布局、真实控制台健康。
- **未生成截图**：S-FLOW 未运行，因此本报告没有伪造桌面/移动端截图证据。T-07 执行时将写入 `PW_VAT_EVIDENCE_DIR`。
- **无数据回收项**：本轮未建库、未造数、未写业务数据，待回收清单为无。
- **API 契约**：本任务无 HTTP/JSON 契约变更，无需回写 `dev-docs/main-api.md`。

## 8. 已自检

> 已自检：本任务 Vitest 12/12 ✅；本任务 + 相关精度回归 30/30 ✅；TypeScript 0 错误 ✅；新测试 ESLint 0 错误 ✅；生产构建成功 ✅；Playwright 4/4 用例装载 ✅；隔离守卫证伪 ✅；真实 S-FLOW 因缺少隔离栈/私有夹具未执行 ⚠️。
