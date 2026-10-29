# 测试报告：报价卡片含税总计展示

| 项 | 值 |
|---|---|
| 执行日期 | 2026-10-28 |
| 分支 / 合并 | `feat/task-261028-quote-card-vat-total` → `master@96cc0241` |
| 代码基线 | `cb7f449b34942356203b33d38a62ad4f25105a21` |
| Browser | Browser plugin 不可用；依规则改用仓库 Playwright + 系统 Chrome |
| 数据库 / 服务 | 本轮未启动服务、未连接或写入共享库 |
| 当前结论 | 纯函数/组件契约、类型检查、生产构建通过；固定测试环境真实 UI 亲验尚未执行，不进入闸门 B |

## 1. 实现与契约结论

- 新增共享 `QuotationVatSubtotal`，编辑态与只读态报价卡片共用同一纯组件/纯函数；没有 hook、effect 或业务 state。
- 首行仅显示“金额 币种”，保留 `.qt-subtotal-value`；次行仅显示 `(VAT) 含税金额 币种`，缺失/非法 VAT 显示 `(VAT) -`。
- `vatRateRatio` 存在时为权威快照，非法不回退；仅当它缺失时才将 `vatRate / 100` 作为回退。
- 含税值用 Decimal 计算并进入既有 12 位工作边界，最终按调用方角色上限显示 4/9 位，不经 JavaScript `number`。
- 核价 COSTING 分支的“产品小计 / 外币小计” JSX 未替换。后端、API、Flyway、Dockerfile 和 `deploy/` 差异为空，详见 `backend-audit.md`。

## 2. AC 进度

| AC | 当前结果 | 证据 / 待验项 |
|---|---|---|
| AC-1 | 自动契约通过，真页待验 | SSR 精确两行：`1717.135540717 USD` / `(VAT) 1940.36316101 USD`；三个禁用文案均为 0 命中。 |
| AC-2 | 通过 | 销售 4 位：`1717.1355` / `1940.3632`；其他角色 9 位：`1717.135540717` / `1940.36316101`。 |
| AC-3 | 待固定环境亲验 | T-04 已装载；尚未走编辑 → 详情 → 编辑刷新真实导航。 |
| AC-4 | 通过 | ratio=0/1、百分比回退、ratio 优先、缺失、非数字、负数、>1 均有精确断言；异常不抛到卡片。 |
| AC-5 | 静态审核通过，真保存待验 | 后端现有 DTO/快照已提供输入，无新保存字段；T-05 已装载，待真实 PUT/GET 逐字比对。 |
| AC-6 | 代码反向审核通过，真页待验 | 核价 JSX 未替换；T-06 已装载，待编辑/只读页检查 `(VAT)` 为 0。 |
| AC-7 | 用例已就绪，待真页 | T-07 已覆盖 1280×720 / 390×844、局部 nowrap/溢出/几何/截图，需长值私有夹具执行。 |
| AC-8 | 部分通过 | typecheck、定向单测、相关回归、生产构建和 Playwright 装载通过；真实 Playwright 流程待固定环境。 |

## 3. 主线复核命令

| 命令 | 结果 |
|---|---|
| `npx vitest run src/pages/quotation/task261028-vat-subtotal.test.tsx src/utils/task261027RoleDisplayPrecision.test.ts src/pages/quotation/components/ComponentCell.readonlyPrecision.test.tsx` | 3 files / 30 tests passed，exit 0 |
| `npx eslint src/pages/quotation/QuotationVatSubtotal.tsx src/pages/quotation/task261028-vat-subtotal.test.tsx e2e/task261028-vat-subtotal.config.ts e2e/task261028-vat-subtotal-flow.spec.ts` | exit 0 |
| `npx tsc -b` | exit 0 |
| `npm run build` | 3445 modules transformed，exit 0；仅既有 chunk-size warning |
| Playwright 运行前 `pgrep -af 'node.*[p]laywright test'` | 无其他 Playwright 进程 |
| 安全虚拟环境 `npx playwright test -c e2e/task261028-vat-subtotal.config.ts --list` | T-04~T-07，4/4 用例装载，exit 0；未连服务/库 |
| `git diff --check` | exit 0 |

## 4. 主线审查发现

1. 定稿原型 06 将长值样例末位写成 `...999`；按既定公式精确结果为 `199999999998999.99999999800000000001`，12 位工作值/9 位显示均为 `...998`。已仅更正当前基准原型数值，未改布局、文案或公式。
2. 测试初稿用两个不同字号节点的 `boundingBox.y` 差值硬判“同行”，会对 baseline 对齐产生假红；已改为垂直区间相交 + 共用 nowrap 父容器 + x 顺序。
3. 审查中又删除了测试对父容器必须是 flex 的实现细节约束；需求只要求同行不换行，block + inline 子节点同样合法。

## 5. 真实 S-FLOW 未执行原因

当前没有本 worktree 独立后端/前端端口、`cpq_t261028_vat_*` 一次性库、`T261028_VAT_` 私有账号和 `T261028_VAT_FLOW_` 报价/核价夹具。项目规范又明确验收与主线亲验使用 `http://10.177.152.12:5173`，而用户本轮未要求部署。因此本轮没有连接共享服务/共享库，也没有自行生成 `deploy/` 产物。

## 6. 环境、冷启动与待回收

- 本任务未新增依赖，未改 package/lock、端口、连库、迁移或启动文档；worktree 仅链接主工作区已有 `node_modules`，没有执行安装。
- 本轮不触发额外冷启动；上次 `[\u51b7\u542f\u52a8✓]` 为 2026-10-28 `task-261027-报价结果按角色显示精度` 的测试环境部署。
- 本轮未建库、未造数、未启服务，一次性库/业务夹具/进程待回收清单：**无**。
- 未收到“打包”或“部署”指令，`deploy/` 保持不变。

## 7. 已自检

> 已自检：主线复跑定向 Vitest 30/30 ✅；新实现/测试 ESLint ✅；TypeScript ✅；生产构建 ✅；Playwright 4/4 装载 ✅；后端/API/DB 零改动审核 ✅；真实 S-FLOW 与固定测试环境主线亲验未执行 ⚠️，未假称达成。

## 8. 合并与 master 复验

- 特性分支 tip `483c5537` 以无冲突 merge commit `96cc0241` 合入 master；没有业务冲突或手工取舍。
- 合并后先执行 `npx tsc -b --clean`，再在主工作区复跑：Vitest 3 files / 30 tests passed，专项 ESLint exit 0，`npm run build` exit 0（3445 modules，仅既有 chunk-size warning）。
- Playwright 前检查无其他测试进程；隔离虚拟配置下 T-04~T-07 共 4 个用例成功装载，未连接服务或数据库。
- 本次用户仅要求合并，未生成/更新 `deploy/`，未部署。固定测试环境真实 UI 亲验仍是闸门 B 前置。

> 已自检（合并后）：master 定向 Vitest 30/30 ✅；专项 ESLint ✅；TypeScript clean/build ✅；Vite 生产构建 ✅；Playwright 4/4 装载 ✅；未部署、未将用例装载冒充真实页面验证 ✅。
