# 测试报告：核价卡片小计展示调整

| 项 | 值 |
|---|---|
| 执行日期 | 2026-10-30 |
| 分支 / worktree | `feat/task-261030-costing-card-subtotal` · `/home/joii/project/cpq/.claude/worktrees/task-261030-costing-card-subtotal` |
| 代码基线 | worktree `HEAD 1ac02a1d`；被测组件 `cpq-frontend/src/pages/quotation/CostingSubtotalSummary.tsx`（未跟踪，执行时 sha256 `dd4f1c6f218c11f95b38b0817208f33da7029573c5dd12ea5c1853a5279ba78e`） |
| 数据库 / 服务 | 全程不连后端、不连任何库；页面级用例的 `/api/**` 全部由 `page.route` 用夹具应答（`e2e/task261030-costing-subtotal.fixtures.ts`） |
| 浏览器 | 第三轮起使用 Playwright 自带 Chromium；中文字体复用 Windows 字体 |
| 当前结论（第六轮，2026-10-30 16:05~16:09） | 候选侧 S-FLOW 11/11 通过（另 2 条为基线专属）；T-10 命令串全部 exit 0；T-03 6/6 通过。AC-1~AC-10 在夹具口径下全部达成。**真实后端数据下的验收仍待主线亲验**（见 test.md §7） |

> 本表之后的各节是从第一轮到第六轮按时间追加的过程记录，**以本表和「第六轮」一节为准**。证据路径都相对本任务目录。

## AC-1~AC-10 逐条汇总（第六轮）

| AC | 覆盖用例 | 结果 | 证据路径 | 未验证项 |
|---|---|---|---|---|
| AC-1 默认布局与精确值 | T-05（编辑页）、T-05b（A/B：头部 / 页签 / 表格不变）、T-08、T-09②（核价管理页）；组件级 T-01（9 位） | ✅ 通过 | `证据/RUN6-20261030-160502/S-FLOW-candidate.txt`；元素截图 `证据/RUN6-20261030-160502/shots2/edit-costing-USD-2cols-1280-footer.png`、`detail-costing-USD-2cols-1280-footer.png`；A/B `证据/RUN6-20261030-160502/ab/` | 真实后端数据下的表现（主线亲验） |
| AC-2 角色显示位数 | T-01（4 位 / 9 位，以及超出 JS 安全整数的精度用例） | ✅ 通过（组件级） | `证据/S-UNIT-vitest-20261030-143242/s-unit-vitest.txt`；第六轮 `证据/RUN6-20261030-160502/T-10/vitest-task261030.txt` | 页面级的销售角色 4 位（按裁决由主线亲验覆盖） |
| AC-3 报价币种 CNY / 空 | T-06（CNY 编辑页 + 详情页、空币种详情页）；组件级 T-06c | ✅ 通过 | `证据/RUN6-20261030-160502/S-FLOW-candidate.txt`；`shots2/edit-costing-CNY-1col-1280-footer.png` | 「币种为空」线上不可达，只验了前端兜底 |
| AC-4 没有核价数据 | T-07（无核价结构 / 生成失败，并回看报价侧）；组件级 T-04；夹具自证 CAL-2（第五轮，master） | ✅ 通过 | `证据/RUN6-20261030-160502/S-FLOW-candidate.txt`；`shots2/detail-costing-NO_COSTING_STRUCTURE-dash-1280-card.png`、`detail-costing-COSTING_FAILED-dash-1280-card.png`；CAL-2 `证据/RUN5-20261030-151059/S-FLOW-baseline.txt` | 「加载组件结构...」只由代码审查覆盖（test.md §4） |
| AC-5 税率来源与异常 | T-02（11 组） | ✅ 通过（组件级） | `证据/S-UNIT-vitest-20261030-143242/s-unit-vitest.txt`；`证据/RUN6-20261030-160502/T-10/vitest-task261030.txt` | 异常值线上不可达，只验了前端防御 |
| AC-6 编辑 / 详情连续一致 | T-08（4 次记录 + 中途报价单） | ✅ 通过 | `证据/RUN6-20261030-160502/S-FLOW-candidate.txt`；`证据/RUN6-20261030-160502/S-FLOW-candidate-*/T-08-records.json` | — |
| AC-7 其他入口与保存无回归 | T-09①（报价侧）、②（核价管理页 + 核价单据总价 A/B）、③a（Excel A/B）、③b（比对 A/B）、④（保存请求体键集合 A/B）、⑤（261028 spec 改写，装载通过） | ✅ 通过 | `证据/RUN6-20261030-160502/S-FLOW-candidate.txt`；`证据/RUN6-20261030-160502/ab/baseline.json` 与 `ab/candidate.json`（6 个 A/B 键逐字相等） | ⑤ 只做了装载，没有执行（261028 flow spec 依赖真实后端与私有账号） |
| AC-8 长值与窄屏 | T-03（2 种外壳 × 1280 / 390，另含 2 条阳性对照），加证伪实验 | ✅ 通过（按 test.md §1「AC-8 口径」） | `证据/RUN4-20261030-145623/`；第六轮 `证据/RUN6-20261030-160502/T-10/playwright-unit.txt` | 静态页的字体不是真实应用的字体；**页面级 390 宽见第六轮 R6-4**（应用侧栏不收起，卡片只有 52px 宽，不属本 AC 范围，仅提示） |
| AC-9 回归与可构建 | T-10：tsc -b、本任务 Vitest 24、回归 Vitest 30、build、Playwright unit 6、flow 11；E2E 控制台 error/warn 计数 | ✅ 通过 | `证据/RUN6-20261030-160502/T-10/T-10-summary.txt`；flow 各用例的请求账与控制台记录 `证据/RUN6-20261030-160502/S-FLOW-candidate-*/` | `quotation-flow.spec.ts` 因 DEC-0008 不跑 |
| AC-10 保存后不丢币种与汇率 | T-11（候选）；基线证伪（第五轮，master 复现 `888.123456789 CNY`） | ✅ 通过 | `证据/RUN6-20261030-160502/S-FLOW-candidate.txt`；`证据/RUN6-20261030-160502/S-FLOW-candidate-*/T-11-candidate.json`；证伪 `证据/RUN5-20261030-151059/S-FLOW-baseline-*/T-11-baseline.json` | 真实后端的保存响应（主线亲验） |

## 1. S-UNIT（AC-2 / AC-5 / AC-8）

### 1.1 已执行：Vitest（T-01、T-02、T-03 文本部分）

| 命令 | 用例数 | 退出码 | 原始输出 |
|---|---|---|---|
| `npx vitest run src/pages/quotation/task261030-costing-subtotal.test.tsx` | 17 passed / 17 | 0 | `证据/S-UNIT-vitest-20261030-143242/s-unit-vitest.txt` |

执行前采样（14:32:42）：无其他 vitest / playwright / vite 进程。

逐条实际输出（摘自原始输出的 `[T261030_COST_]` 行）：

| 测试 | AC | 实际 4 段文本 | 结果 |
|---|---|---|---|
| T-01 4 位 | AC-2 | `1717.1355 CNY` / `(VAT) 1940.3632 CNY` · `241.0556 USD` / `(VAT) 272.3928 USD` | 通过 |
| T-01 9 位 | AC-2 / AC-1 | `1717.135540717 CNY` / `(VAT) 1940.36316101 CNY` · `241.055611185 USD` / `(VAT) 272.392840639 USD` | 通过 |
| T-01 文本节点 | api.md §3.1 | 前缀原文 `"(VAT) "`；第一行原文即「金额 币种」半角单空格 | 通过 |
| T-01 4 位无 9 位残留 | AC-2 | 4 位标记不含任一 9 位文本 | 通过 |
| T-01 不经 number（9 位） | AC-2 | `9007199254740993.000000001 CNY` / `(VAT) 10178135157857322.090000001 CNY` · `4503599627370496.500000001 USD` / `(VAT) 5089067578928661.045000001 USD` | 通过 |
| T-01 不经 number（4 位） | AC-2 | `9007199254740993 CNY` / `(VAT) 10178135157857322.09 CNY` · `4503599627370496.5 USD` / `(VAT) 5089067578928661.045 USD` | 通过 |
| T-02 ① ratio=0 | AC-5 | 第二行 `(VAT) 1717.135540717 CNY` / `(VAT) 241.055611185 USD` | 通过 |
| T-02 ② 仅 vatRate=13（undefined / null 两种） | AC-5 | 同 AC-1 | 通过 |
| T-02 ③ ratio 0.13 + vatRate 5 | AC-5 | 同 AC-1 | 通过 |
| T-02 边界 ratio=1（由「>1 → -」推出） | AC-5 | 第二行 `(VAT) 3434.271081434 CNY` / `(VAT) 482.11122237 USD` | 通过 |
| T-02 ④ 缺失（undefined / null）、非数字（含 / 不含 vatRate）、<0、>1 | AC-5 | 两列第二行均为 `(VAT) -`，第一行不变 | 通过（7 组均不抛异常） |
| T-03 长值文本 | AC-8 | `99999999999999.999999999 CNY` / `(VAT) 199999999998999.999999998 CNY` · `199999999999999.999999998 USDT` / `(VAT) 399999999997999.999999996 USDT` | 通过 |

每条均先断言 `.qt-subtotal-costing` 恰好 1 个、列数恰好 2、每列各 1 个第一行 / 第二行 / 前缀 / 金额节点，DOM 顺序 CNY 在前。期望值由 Python Decimal（prec 80，12 位 HALF_UP 工作值 → 角色上限 → 去尾零）独立实算，两种派生顺序结果一致。

**量具证伪**（testing.md §4.4）：临时用例 `task261030-costing-subtotal.falsify.test.tsx` 用手写坏标记喂读取工具 —— 前缀无尾空格、金额币种粘连、3 列、缺 VAT 节点、无根节点、非良构 HTML —— 全部被检出（2 passed，exit 0，`证据/…/falsify.txt`）。该文件跑完已删除（`falsify-removed.txt`）。

### 1.2 未执行：T-03 几何（AC-8 的布局部分）

`npx playwright test -c e2e/task261030-costing-subtotal-unit.config.ts` 待浏览器就绪后执行。用例已按 api.md §3.1 的两种真实外壳（编辑页 `div.qt-subtotal-bar-multi`、只读页 `div.qt-subtotal-bar.qt-subtotal-bar-multi`）各包一次：

- 1 条阳性对照 × 2 种外壳；
- 长值 × {1280×720, 390×844} × 2 种外壳；
- 共 6 条，`--list` 装载通过。

**AC-8 的几何断言目前为「未验证」。**

## 2. S-FLOW（AC-1 / AC-3 / AC-4 / AC-6 / AC-7 / AC-9 / AC-10）

### 2.1 已执行：组件级 Vitest（T-04、T-06c）

| 命令 | 用例数 | 退出码 | 原始输出 |
|---|---|---|---|
| `npx vitest run src/pages/quotation/task261030-costing-subtotal-flow.test.tsx` | 7 passed / 7 | 0 | `证据/S-UNIT-vitest-20261030-143242/s-flow-component-vitest.txt` |

| 测试 | AC | 实际 | 结果 |
|---|---|---|---|
| T-04 `subtotal:null`（USD 全字段 / CNY / 4 位） | AC-4（组件级） | 1 列，`data-currency=""`、`data-empty="true"`，`-` / `(VAT) -`；标记不含 CNY / USD / ¥ / ￥ / 1717.135540717 / 888.123456789 / 产品小计 / 外币小计 | 通过 |
| T-06c 币种 `CNY` / `''` / `'   '` / 未传 | AC-3（组件级补充） | 1 列 `data-currency=CNY`，`1717.135540717 CNY` / `(VAT) 1940.36316101 CNY`，无 USD | 通过 |

### 2.2 未执行：页面级 Playwright（T-05~T-09、T-11、CAL-1/2）

- 用例：`e2e/task261030-costing-subtotal-flow.spec.ts`，共 13 条（含 CAL-1 / CAL-2 夹具自证、新增 T-11 / AC-10），`--list` 装载通过。
- 夹具：`e2e/task261030-costing-subtotal.fixtures.ts`，按 `backend-audit.md` 造。
- T-09⑤ 已按主线要求改写 `e2e/task261028-vat-subtotal-flow.spec.ts` 的 `assertCostingFooterUnchanged`（只改该函数）。该文件 4 条用例 `--list` 装载通过。
  - ⚠️ 引用它的用例标题仍是旧文案「keep the legacy two labels」，按指示未改。
- **AC-1 / AC-3（页面）/ AC-4（页面）/ AC-6 / AC-7 / AC-10 均为「未验证」。**

### 2.3 AC-9 / T-10

本轮未执行，脚本为 `e2e/task261030-costing-subtotal-t10.sh`。本轮只做了装载与类型检查（§3）。

## 3. 静态检查（原始输出 `证据/S-UNIT-vitest-20261030-143242/static-checks.txt`）

| 命令 | 退出码 |
|---|---|
| `npx tsc -p tsconfig.test.json --noEmit` | 0 |
| `npx tsc -p e2e/tsconfig.e2e.json --noEmit` | 0 |
| `npx eslint <本任务 10 个测试文件 + task261028-vat-subtotal-flow.spec.ts>` | 0 |
| `playwright --list` · unit config | 0（6 tests） |
| `playwright --list` · flow config（candidate） | 0（13 tests） |
| `playwright --list` · flow config（`T261030_TARGET=baseline T261030_VITE_ROOT=/home/joii/project/cpq/cpq-frontend`） | 0（13 tests） |
| `playwright --list` · flow config（baseline 未设 VITE_ROOT，守卫证伪） | 1，`Error: T261030_COST_ baseline must serve a master checkout…`（守卫按预期拦截） |
| `playwright --list` · `task261028-vat-subtotal.config.ts`（假 env，仅装载，无服务） | 0（4 tests） |
| 装载后端口采样 `ss -ltn \| grep 5292/5293` | 无监听 |

## 4. 环境与 A/B 基线备注

- A/B 基线 = 主工作区 `/home/joii/project/cpq/cpq-frontend`，端口 5293；候选端口 5292。两边必须串行跑。
- 14:3x 采样：主工作区 `master@9219c063`，比 worktree 基点 `1ac02a1d` 多 4 个提交，均未触及 `cpq-frontend`（`git log 1ac02a1d..HEAD -- cpq-frontend` 为空）；`cpq-frontend` 无未提交改动；基线中不存在 `CostingSubtotalSummary.tsx`。这是**采样时刻**的状态，执行 A/B 前需复查。
- 本轮未建库、未造数、未起服务。**一次性库 / 业务夹具 / 进程待回收清单：无。**
- 本次无 HTTP 契约变更，无需回写 `main-api.md`。

---

# 第三轮（浏览器就绪后，2026-10-30 14:37~14:42）

证据根目录：`证据/RUN3-20261030-143719/`

本轮环境：
- 浏览器：Playwright 自带 Chromium（`~/.cache/ms-playwright/chromium-1217`），两份 config 已去掉 `channel:'chrome'` 默认值。
- 实现状态：F-1~F-6 已完成。
- 被测组件 sha256 记录在 `env.txt`。

本轮改动：
- 夹具按 `backend-audit.md` §6+ 更新：
  - SaveDraftResponse 改为白名单构造：40 个单头字段 + `userDataVersion` + `lineItems: []`；
  - 加入核价 Excel 列和值；
  - 加入比对视图三个接口。
- `task261028-vat-subtotal-flow.spec.ts`：
  - `assertCostingFooterUnchanged` 已改写（T-09⑤）；
  - 对应用例标题已改为与新断言一致。

## R3-1. S-UNIT · T-03 几何（AC-8 布局部分）

- 命令：`npx playwright test -c e2e/task261030-costing-subtotal-unit.config.ts`
- 结果：6 条，4 通过、2 失败，exit 1
- 原始输出：`T-03-geometry-2.txt`
- 截图与量测 JSON：`S-UNIT-T03-2026-10-30T06-39-1*/`

第一次运行（`T-03-geometry.txt`）因 serial 模式在首个失败处停止了后续用例。已改为非 serial 后重跑，下表是重跑结果。

| 用例 | 结果 | 实测 |
|---|---|---|
| 阳性对照（edit 外壳 / readonly 外壳） | 2 通过 | 样式生效；默认夹具 1280 宽并排、CNY 在左；4 段文本同 AC-1 |
| 长值 edit 外壳 1280×720 | 通过 | 并排；4 段文本同 AC-8；单行；无覆盖；`scrollWidth 1280/1280` |
| 长值 readonly 外壳 1280×720 | 通过 | 同上 |
| 长值 edit 外壳 390×844 | **失败** | 详见下方 |
| 长值 readonly 外壳 390×844 | **失败** | 与 edit 外壳完全相同 |

390×844 的实测情况：
- **通过的部分**：
  - 布局为上下排列，USDT 整列在 CNY 下方；
  - 文本逐字正确；
  - 所有数值都是单行；
  - `(VAT)` 在金额之前且同行；
  - 各段两两不覆盖；
  - `scrollWidth 390 === clientWidth 390`。
- **失败的断言**：`colN.value 右缘在卡片内`。
  - 失败原文：`Expected: <= 374.5  Received: 445`（col0）、`Received: 459`（col1）。
- **事实（`clips` 诊断）**：
  - 第一行 `.qt-subtotal-costing-main` 与第二行 `.qt-subtotal-costing-vat` 都是 `overflow-x:auto` 的滚动容器，宽 270px，右缘 330 在卡片内。
  - 金额文本的 scrollWidth 为 383~412px，在本行内可横向滚动。
  - `.qt-subtotal-vat-value` 元素盒伸出卡片右缘 71~85px，被所在行裁剪。
  - 截图显示两行都从左侧起显示，币种缩写（CNY / USDT）在初始视图里被裁掉，需要横向滚动才能看到。
- **待主线裁决**：AC-8 写「4 段都在卡片内右对齐」，原型写「数值放不下时在本行内横向滚动」。两种读法得出相反结论：
  - 按「元素盒在卡片内」：判红；
  - 按「本行内可滚动即可」：判绿，但 390 宽下初始视图既看不到币种，也谈不上右对齐。
  - 我没有改断言，仍按原口径判红。

## R3-2. S-FLOW · 基线（master，主工作区 `/home/joii/project/cpq/cpq-frontend`，端口 5293）

执行前复查（`baseline-precheck.txt`，14:39:28）：
- 主工作区为 `master@6896d30d`；
- `cpq-frontend` 无未提交改动；
- 自 `1ac02a1d` 起没有触及 `cpq-frontend` 的提交；
- 不存在 `CostingSubtotalSummary.tsx`；
- 5292 / 5293 端口无监听。

命令：`T261030_TARGET=baseline T261030_VITE_ROOT=/home/joii/project/cpq/cpq-frontend T261030_AB_DIR=<RUN3>/ab npx playwright test -c e2e/task261030-costing-subtotal-flow.config.ts`

结果：13 条，4 通过、5 失败、4 跳过（候选专属），exit 1，耗时 2.0 分钟。原始输出：`S-FLOW-baseline.txt`。

探活：基线端口上 `CostingSubtotalSummary.tsx` 不是 module（`status=200 module=false`），确认服务的是 master。

### 🚨 夹具自证结果（CAL）—— CAL-1 失败，依赖夹具的页面级结论全部作废，S-FLOW 已停

| 自证 | 结果 | 实际 |
|---|---|---|
| CAL-1 编辑页报价侧应为 `888.123456789 USD` / `(VAT) 1003.579506172 USD`，旧核价金额区应含 `1717.135540717` | **失败** | 报价侧两行实际为 `["0 USD","(VAT) 0 USD"]` |
| CAL-2① 无核价结构（形态 A）：master 详情页应复现「显示报价侧金额」 | 复现成功 | `暂无组件数据 产品小计 ¥ 888.123456789 外币小计 124.676903837 USD` |
| CAL-2② 生成失败哨兵：master 应复现「显示 888.123456789」 | **失败** | `核价数据生成失败：核价渲染失败: T261030_COST_mock 产品小计 ¥ 0 外币小计 0 USD`<br>master 显示的是 `¥ 0`，不是报价侧金额 |

CAL-1 失败的诊断（仅依据页面文本与请求账，未读实现）：
- 按 `backend-audit` §2.4 / §2.5 造的「BASIC_DATA 列 + `isSubtotal` + 快照 `basicDataValues`」没有被前端用来出数：
  - 编辑页 `核价金额` 单元格**永久显示「加载中…」**，页签合计为 `0`；
  - 详情页该单元格显示 `—`，合计 `0`（见 `ab/baseline.json` 的 `T-05b.costingCardChrome`）；
  - 核价管理页同样是 `—` / `0`（截图 `T-09-2-baseline-review-failed.png`）。
- 请求账里没有 `batch-expand` 请求。详情页和核价管理页各有 1~5 次 `formulas/batch-evaluate`，夹具返回空结果。
- 所有请求都有夹具应答，`unhandled` 为空。
- 审核 §2.4 末尾原本就注明「这份结构 + 卡片值能否让前端算出 1717.135540717 未验证」。CAL-1 现在证实算不出来。

因此 T-05、T-06、T-07、T-08、T-09①②、T-11 的页面级结论**不可得**，候选侧（5292）**未执行**。

### 基线侧其余用例的原始结果（仅作记录，不作结论）

- **T-05b**：A/B 记录已写入，内容是基于无效夹具的卡片文本。
- **T-09③a、T-09③b**：A/B 记录已写入，Excel 视图 400 字符，比对视图 471 字符。这两条只读 Excel 列 / 比对接口的样本，不依赖卡片计算；但候选侧未执行，A/B 比较**未完成**。
- **T-09④**：保存请求的键路径共 9 个：`$.added`、`$.baseVersion`、`$.costingCardTemplateId`、`$.customerTemplateId`、`$.expectedCloseDate`、`$.expiryDate`、`$.modified`、`$.projectName`、`$.removed`。触发方式是第 1 步的「保存草稿」按钮。
- **T-09①**：编辑页报价侧「加载中」最终不为 0，失败。根因同 CAL-1。
- **T-09②**：「核价单据总价」在核价管理页 DOM 中**不存在**（`element(s) not found`）。原因可能是它的出现条件或文案与审核 §2.3 不同，**未定位**。
- **T-11 基线证伪**：编辑页保存后停留在「加载中」，未走到证伪判断，失败。根因同 CAL-1。

### 本轮发现的其他环境问题

1. **控制台 error/warn 在 master 上本就不为 0**。基线各页面都有既有输出，例如：
   - `Warning: [antd: Drawer] width is deprecated`
   - `[antd: Alert] message is deprecated`
   - `[antd: Space] direction is deprecated`
   - `[antd: Spin] tip is deprecated`
   - 编辑页一条 `In HTML, <form> cannot be a descendant of <form>` 嵌套报错

   AC-9 字面要求「控制台 error/warn 为 0」，候选侧几乎必然判红，而且与本任务无关。建议主线裁决是否改为「相对 master 无新增」。
2. **WSL 的 Chromium 没有中文字体**（`fc-list :lang=zh` 结果为 0）。截图里中文全部是方框，截图作为证据可读性很差；文本断言不受影响。

## R3-3. 未执行 / 未验证清单

| 项 | 状态 | 原因 |
|---|---|---|
| S-FLOW 候选侧全部 13 条（含 T-05~T-09、T-11） | 未执行 | CAL-1 自证失败，按指令停下 |
| AC-1 / AC-3 / AC-4 / AC-6 / AC-7 / AC-10 的页面级验证 | 未验证 | 同上 |
| AC-8 390 宽「在卡片内右对齐」 | 判红，待裁决 | 见 R3-1 |
| T-10 / AC-9 | 未执行 | 依赖 S-FLOW；控制台口径待裁决 |

## R3-4. 进程与端口

14:42:28 采样（`ports-after.txt`）：5292、5293 均无监听；无 vite、playwright、chromium 进程。本轮未建库、未造数。**待回收清单：无。**

---

# 第四轮（主线裁决后，2026-10-30 14:56）

证据根目录：`证据/RUN4-20261030-145623/`

`env.txt` 记录：
- 被测组件 sha256 仍为 `dd4f1c6f…`，`quotation.css` 为 `c3974567…`；
- 中文字体 28 个；
- 起跑前无 vite / playwright 进程，5292 / 5293 无监听。

## R4-1. T-03 按「AC-8 口径」改断言后重跑

改动（test.md §1「AC-8 口径」）：
- 删掉原来那条 `expect.soft`（元素盒在卡片内），换成以下硬断言：
  - 每一行的容器（`.qt-subtotal-costing-main`、`.qt-subtotal-costing-vat`）左右缘都在卡片内；
  - 某行内容超出时，该行必须 `scrollWidth > clientWidth`；
  - 并且该行 `overflow-x` ∈ {auto, scroll}。这一条是我在「确实可滚」之外补的，用来排除被 `hidden` 截断的情形。
- `(VAT)` 前缀的盒子仍须在卡片内。
- 页面 `scrollWidth === clientWidth` 保持不变。
- 其余断言（单行、前缀在前且同行、不覆盖、整列换行、右对齐）都没改。

| 命令 | 结果 | 原始输出 |
|---|---|---|
| `npx playwright test -c e2e/task261030-costing-subtotal-unit.config.ts` | **6 通过 / 6，exit 0** | `T-03-geometry.txt` |

实测（每行的 scrollWidth / clientWidth）：
- 1280 宽：编辑页和只读页两种外壳下，4 行都不溢出（340/340、364/364），两列并排。
- 390 宽：两种外壳下 4 行都溢出，可滚动（283/270、289/270、303/270、297/270，`overflow-x:auto`）。
  - 列布局上下排列，USDT 整列在 CNY 下方；
  - 页面 390/390，无横向溢出。
- 截图和量测 JSON 在 `S-UNIT-T03-2026-10-30T06-56-29-455Z/`。

**证伪实验**（testing.md §4.4）：设 `T261030_FALSIFY=row-hidden`，向页面注入 `overflow-x:hidden !important`。
- 两条 390 宽用例**硬失败**，其余 4 条通过，exit 1。
- 失败原文：`col0.main 行：内容超出时该行 overflow-x 必须允许滚动（不是被 hidden 截断）`，收到的值是 `hidden`。
- 输出在 `T-03-falsify-row-hidden.txt`。
- 结论：新守卫确实接上了。

⚠️ 与第三轮数值不同的原因：第三轮 390 宽的行内容宽是 383~412px，本轮是 283~303px。
- 被测组件和样式表的 sha 都没变。差异来自字体环境：fontconfig 改为指向 Windows 字体后，静态页（只内联了 `quotation.css`，没有应用的全局字体栈）的数字改用衬线体渲染，字宽变了。
- 两轮都是「行溢出、可滚动」，结论方向一致。
- 静态页的字体不等于真实应用页面的字体，所以 390 宽下真实页面的几何仍应以 S-FLOW 或主线亲验为准。

## R4-2. 中文字体确认

`font-check-prototype-02.png`：用自带 Chromium 截了定稿原型 `02-核价只读与边界态.html`。截图中「当前基准 · 核价卡片金额区 · 只读与边界态」「物料BOM」「来料固定加工费」等中文**正常显示，可读**。

## R4-3. 用例调整（只改了用例，未执行）

- **CAL-2②**（主线裁决 2）：生成失败变体的证伪预期改为「master 上金额区出现 ¥ 或带币种的金额（即显示了非 `-` 的金额）」。CAL-2① 仍要求出现 `888.123456789`；T-07② 不变。
- S-FLOW 继续暂停，等 `fixture-recipe.md` 和主线的消息。T-09②「核价单据总价」用例暂不改。

## R4-4. 进程与端口

14:57 采样：无 playwright / chromium 进程。本轮没有起 Vite，5292 / 5293 无监听。**待回收清单：无。**

---

# 第五轮（按 fixture-recipe 配方 A 重造夹具，2026-10-30 15:10~15:16）

证据根目录：`证据/RUN5-20261030-151059/`。

起跑采样（`env.txt`）：
- worktree `HEAD 1ac02a1d`；被测组件 sha256 仍为 `dd4f1c6f…`；worktree `src/pages/quotation` 下另有 F-7 改动的 19 个文件。
- 基线：主工作区 `master@d25ac690`。`cpq-frontend` 无未提交改动；自 `1ac02a1d` 起没有触及 `cpq-frontend` 的提交；不存在 `CostingSubtotalSummary.tsx`。
- 5292 / 5293 无监听，无 vite / playwright 进程。

**夹具改动**（`e2e/task261030-costing-subtotal.fixtures.ts`）：
- 按配方 A-1，把两侧 `basicDataValues` 的键从字段名改为 `"{"+basicDataPath+"}"`，即 `{$T261030_COST_view.amount}`、`{$T261030_COST_quote_view.amount}`。
- 新增路由 `GET /templates/{id}/excel-view-config` → `null`。
- A-2~A-8 逐条核对，全部满足：
  - componentId 三处一致；
  - `productPartNo` 非空；
  - 两侧卡片值非空（无数据变体除外）；
  - `quoteCardStructure.templateId` 等于行 `templateId`；
  - 模板快照用 snake_case，`basic_data_path` 与结构一致；
  - 没有 SUBTOTAL 页签；
  - 金额全是字符串。

**用例改动**：
- T-09② 先切到「核价单」分段，再定位「核价单据总价」（正则匹配）。
- 编辑页分段带 emoji，现有的 `hasText` 子串匹配可以直接命中，不用改。

静态检查：`tsc -p e2e/tsconfig.e2e.json` exit 0；eslint exit 0。

## R5-1. 夹具自证（master 基线，端口 5293）—— 通过

命令：`T261030_TARGET=baseline T261030_VITE_ROOT=/home/joii/project/cpq/cpq-frontend T261030_AB_DIR=<RUN5>/ab npx playwright test -c e2e/task261030-costing-subtotal-flow.config.ts`

结果：9 通过、4 跳过（候选专属），exit 0，耗时 37 秒。原始输出 `S-FLOW-baseline.txt`。

| 自证 | 结果 | master 实际文本 |
|---|---|---|
| CAL-1 | **通过** | 报价侧 `["888.123456789 USD","(VAT) 1003.579506172 USD"]`；核价卡片 `核价金额 1717.135540717 … 产品小计 CNY 1717.135540717 外币小计 241.055611185 USD` |
| CAL-2① 无核价结构 | **通过**（复现原缺陷） | `暂无组件数据 产品小计 ¥ 888.123456789 外币小计 124.676903837 USD` |
| CAL-2② 生成失败 | **通过**（按裁决后的口径：显示了非 `-` 的金额） | `核价数据生成失败：核价渲染失败: T261030_COST_mock 产品小计 ¥ 0 外币小计 0 USD` |
| T-11 基线证伪（AC-10） | **通过**（master 复现保存后丢币种） | 保存后不刷新：报价侧 `888.123456789 CNY` / `(VAT) 1003.579506172 CNY`，核价侧 `外币小计 1717.135540717 CNY`；刷新后报价侧回到 `888.123456789 USD` |

基线同轮还写入了 A/B 记录：T-05b、T-09②（`核价单据总价：¥1717.135540717`）、T-09③a、T-09③b、T-09④。T-09① 在 master 上也通过。

## R5-2. 候选全量 S-FLOW（worktree，端口 5292）

命令：`T261030_AB_DIR=<RUN5>/ab npx playwright test -c e2e/task261030-costing-subtotal-flow.config.ts`

结果：9 通过、2 失败、2 跳过（基线专属 CAL），exit 1，耗时 55.9 秒。原始输出 `S-FLOW-candidate.txt`。

探活：候选端口上 `CostingSubtotalSummary.tsx` 是 module（`status=200 module=true`），确认服务的是本 worktree。

| 测试 | AC | 结果 | 实际（摘自输出） |
|---|---|---|---|
| T-05 | AC-1 | ✅ | 2 列，币种 `["CNY","USD"]`；`1717.135540717 CNY` / `(VAT) 1940.36316101 CNY` · `241.055611185 USD` / `(VAT) 272.392840639 USD`；同一水平带、CNY 在左；所有字色采样均为 `rgb(255, 255, 255)`（前缀 opacity 0.82、金额 0.94，仅记录）；金额区不含 产品小计 / 外币小计 / ¥ / ￥ / 888.123456789 |
| T-05b | AC-1 | ✅（A/B 相等） | 编辑页、详情页卡片的头部 + 页签 + 表格文本与 master 逐字相同 |
| T-06 | AC-3 | ✅ | CNY 编辑页、CNY 详情页、空币种详情页：均为 1 列 CNY，`1717.135540717 CNY` / `(VAT) 1940.36316101 CNY`，金额区里 USD 出现 0 次 |
| T-07 | AC-4 | ✅ | 无核价结构 / 生成失败：主体分别为「暂无组件数据」「核价数据生成失败」；1 列，`data-currency=""`、`data-empty="true"`，`-` / `(VAT) -`；切到报价单后为 `888.123456789 USD` / `(VAT) 1003.579506172 USD` |
| T-08 | AC-6 | ✅ | 4 次记录都等于 AC-1：编辑首次、切回核价单、详情页、编辑页刷新后（`T-08-records.json`）；中途报价单为 `888.123456789 USD` / `(VAT) 1003.579506172 USD`；每步「加载中」、红色错误、错误遮罩均为 0 |
| T-09① | AC-7 | ✅ | 报价侧编辑页、详情页两行同上；`.qt-subtotal-costing` 与 `.qt-subtotal-costing-col` 计数均为 0 |
| T-09② | AC-7 | ✅（A/B 相等） | 核价管理页金额区同 AC-1；`核价单据总价：¥1717.135540717` 与 master 逐字相同 |
| T-09③a | AC-7 | ✅（A/B 相等） | 核价 Excel 视图：`… T261030_COST_P1 T261030_COST_P1 1717.135540717 查询 共 1 条`，与 master 逐字相同 |
| T-09③b | AC-7 | ✅（A/B 相等） | 比对视图：`T261030_COST_P1 报价 888.123456789 核价 1717.135540717 差异 -829.012083928`，与 master 逐字相同；meta、data、config 三个接口均被命中 |
| T-09④ | AC-7 | ❌（只因控制台判红） | 用例本体的断言全部通过：请求体键路径共 9 个，与 master 相同（`$.added, $.baseVersion, $.costingCardTemplateId, $.customerTemplateId, $.expectedCloseDate, $.expiryDate, $.modified, $.projectName, $.removed`）；不含派生值和 vatInclusive / taxInclusive 键。失败来自 afterEach 的 AC-9 控制台断言，见下 |
| T-11 | AC-10 | ❌（只因控制台判红） | 用例本体的断言全部通过：保存响应 data 里没有 currency 等 5 个键（白名单响应见 `T-11-candidate.json`）；不刷新时核价 2 列同 AC-1、报价首行 `888.123456789 USD`；刷新后不变。失败来自 afterEach 的 AC-9 控制台断言，见下 |

**单列：两条失败的唯一原因**（主线说明中的第 6 类，正等用户裁决；断言没改）：

```
Error: AC-9：目标页面控制台 error/warn 必须为 0
+   "error: Warning: [antd: message] Static function can not consume context like dynamic theme. Please use 'App' component instead.",
```

- 只在走保存流程的两条用例（T-09④、T-11）里出现，其余 9 条控制台为 0。
- T-10 里再跑一次，复现结果完全相同（2/2 轮）。

## R5-3. T-10 / AC-9 构建与回归

- 命令：`T261030_AB_DIR=<RUN5>/ab bash e2e/task261030-costing-subtotal-t10.sh <RUN5>/T-10`
- 汇总：`T-10/T-10-summary.txt`
- 执行前采样：没有其他 tsc / vite / vitest / playwright 进程。

| 命令 | 退出码 | 用例数 |
|---|---|---|
| `npx tsc -b` | 0 | — |
| 本任务 Vitest（两个文件） | 0 | 24 / 24 |
| 回归 Vitest：`task261028-vat-subtotal.test.tsx` / `task261027RoleDisplayPrecision.test.ts` / `ComponentCell.readonlyPrecision.test.tsx` | 0 | 30 / 30 |
| `npm run build` | 0 | 只有既有的 chunk-size 告警 |
| Playwright unit（T-03） | 0 | 6 / 6 |
| Playwright flow（候选） | 1 | 9 通过 / 2 失败（同 R5-2，只因第 6 类控制台告警） |

- 脚本把 `tsc.txt` 标为 `EMPTY-OUTPUT` 是误报：`tsc -b` 成功时本来就没有输出，exit 0 已记录。
- 控制台计数：flow 中除 T-09④、T-11 外均为 0；那两条各有 1 条（上面的 antd message 告警）。框架错误遮罩计数全部为 0。

## R5-4. AC 达成汇总（S-FLOW 夹具口径；真实后端数据仍以主线亲验为准）

| AC | 结论 |
|---|---|
| AC-1 | 通过（T-05、T-05b） |
| AC-2 / AC-5 | 通过（组件级，第二轮） |
| AC-3 | 通过（T-06 + 组件级 T-06c） |
| AC-4 | 通过（T-07 + 组件级 T-04）；「加载组件结构...」由代码审查覆盖 |
| AC-6 | 通过（T-08） |
| AC-7 | ①②③ 通过；④ 本体通过，但控制台第 6 类告警判红，**待用户裁决** |
| AC-8 | 通过（T-03，第四轮按裁定口径） |
| AC-9 | 构建与回归全部 exit 0；E2E 控制台为 0 这一项**未达成**（保存流程第 6 类告警，待裁决） |
| AC-10 | 本体通过；基线证伪成立；控制台第 6 类告警判红，**待裁决** |

## R5-5. 其他

- 截图中文可读，已确认：`S-FLOW-candidate-*/T-05-edit-costing-1280.png` 中「编辑报价单」「核价单」「产品卡片」「T261030_COST_核价明细」等都正常显示。
- ⚠️ 页面的 `fullPage` 截图只截到视口：内容在内层滚动容器里，卡片底部金额区不在截图范围内。金额区的证据以输出中逐字记录的文本为准；如需金额区截图，要改成截元素。
- 进程与端口：15:16 采样，5292 / 5293 均无监听，无 vite / chromium 进程。`npm run build` 生成的 `dist/` 在 worktree 内（已被 gitignore）。本轮未建库、未造数。**待回收清单：无。**
- 时间盒：S-FLOW 两轮实际运行约 1.5 分钟，加上 T-10 共约 6 分钟，在 45 分钟以内。

---

# 第六轮（F-8 完成后，候选侧，2026-10-30 16:05~16:09）

证据根目录：`证据/RUN6-20261030-160502/`

起跑采样（`env.txt`）：
- worktree `HEAD 1ac02a1d`；被测组件 sha256 `dd4f1c6f…`；`App.tsx` 有改动（F-8），sha256 `aacb2497…`。
- 5292 / 5293 无监听，没有 vite / playwright / vitest / tsc 进程。

本轮断言**一条未改**。新增的只有截图用的 spec 和 config（`e2e/task261030-costing-subtotal-shots.*`），它们不参与 AC 判定。

**为什么不重跑基线**：
- A/B 用例需要的 master 记录直接复用第五轮的 `RUN5/ab/baseline.json`，复制到 `RUN6/ab/`，sha256 前缀 `d79c0b14`；第五轮的证据没有改动。
- 依据：主工作区 master 现在是 `2384efe2`。自第五轮基线 `d25ac690` 起，`git log -- cpq-frontend` 为空，`cpq-frontend` 也没有未提交改动。master 前端没变，基线记录就仍然有效。

## R6-1. 候选全量 S-FLOW（端口 5292）

- 命令：`T261030_AB_DIR=<RUN6>/ab npx playwright test -c e2e/task261030-costing-subtotal-flow.config.ts`
- 结果：**11 通过 / 0 失败**，2 条 CAL 为基线专属、跳过；**exit 0**；耗时 52.8 秒。
- 原始输出：`S-FLOW-candidate.txt`。
- **T-09④ 和 T-11 的控制台告警均为 0**：第五轮唯一的判红原因（antd 静态 message 告警）已被 F-8 消除。其余用例的控制台同样为 0。
- 6 个 A/B 键与 master 记录逐字相等。

## R6-2. T-10 命令串（AC-9）

- 命令：`T261030_AB_DIR=<RUN6>/ab bash e2e/task261030-costing-subtotal-t10.sh <RUN6>/T-10`
- 汇总：`T-10/T-10-summary.txt`

| 命令 | 退出码 | 用例数 |
|---|---|---|
| `npx tsc -b` | 0 | — |
| 本任务 Vitest | 0 | 24 / 24 |
| 回归 Vitest（261028 / 261027 / ComponentCell） | 0 | 30 / 30 |
| `npm run build` | 0 | 只有既有的 chunk-size 告警 |
| Playwright unit（T-03） | 0 | 6 / 6 |
| Playwright flow（候选） | 0 | 11 通过 / 2 跳过 |

- `tsc.txt` 的 `EMPTY-OUTPUT` 是误报：`tsc -b` 成功时本来就不输出。
- flow 在本轮共跑了两遍（R6-1 和 T-10 内），结果相同。

## R6-3. 亲验用元素级截图

- 命令：`npx playwright test -c e2e/task261030-costing-subtotal-shots.config.ts`，7/7 通过，exit 0。
- 截图存放：第二次运行的结果在 `shots2/`（新增了视口截图与宽度记录）；第一次运行的结果在 `shots/`，内容相同但少了这两项。
- 每个场景产出 4 个文件：
  - `-footer.png`：包住 `.qt-subtotal-costing` 的外壳元素截图，即卡片底部金额区；
  - `-card.png`：整张目标卡片的元素截图；
  - `-viewport.png`：当时的视口；
  - `-footer.txt`：金额区文本，加上页面和卡片的宽度。

| 场景 | 宽 | 金额区文本 | 文件前缀（`shots2/`） |
|---|---|---|---|
| 编辑页核价 USD 两列 | 1280 | `1717.135540717 CNY (VAT) 1940.36316101 CNY 241.055611185 USD (VAT) 272.392840639 USD` | `edit-costing-USD-2cols-1280` |
| 编辑页核价 CNY 一列 | 1280 | `1717.135540717 CNY (VAT) 1940.36316101 CNY` | `edit-costing-CNY-1col-1280` |
| 详情页核价 USD 两列 | 1280 | 同编辑页 USD | `detail-costing-USD-2cols-1280` |
| 详情页 无核价结构 | 1280 | `- (VAT) -` | `detail-costing-NO_COSTING_STRUCTURE-dash-1280` |
| 详情页 生成失败 | 1280 | `- (VAT) -`；卡片主体为「核价数据生成失败：核价渲染失败: T261030_COST_mock」 | `detail-costing-COSTING_FAILED-dash-1280` |
| 编辑页核价 USD 两列 | 390 | 文本同上 | `edit-costing-USD-2cols-390` |
| 详情页核价 USD 两列 | 390 | 文本同上 | `detail-costing-USD-2cols-390` |

**目视核对**：
- `edit-costing-USD-2cols-1280-footer.png`：左列 CNY、右列 USD；第一行税前、第二行 `(VAT)`；白字，紫色渐变底。
- `detail-costing-COSTING_FAILED-dash-1280-card.png`：红字失败提示，金额区只显示 `-` / `(VAT) -`。
- 这两张与原型 01 / 02 的结构一致，截图中的中文可读。

## R6-4. ⚠️ 提示：页面级 390 宽截图不可用于比对（不属 AC-8 范围，不判缺陷）

- 两张 390 宽截图里，卡片宽度只有 **52px**，金额区外壳只有 **36px**（`-footer.txt` 中的 `cardWidth: 52, shellWidth: 36`）。页面本身没有横向溢出（390/390）。
- 原因见 `detail-costing-USD-2cols-390-viewport.png`：应用左侧 220px 的侧栏在 390 宽下不收起，主内容区被挤到约 140px。金额区因此是一根窄条，文字只能在行内横向滚动查看。
- 这是应用整体布局的既有行为，不是本任务改动引入的。AC-8 写的是组件夹具，按 test.md 口径已由 T-03 在真实外壳中验证。
- 因此这两张图**不能**用于和原型 02「窄屏 390」那一节比对；那一节应对照 T-03 的静态页截图 `证据/RUN4-20261030-145623/S-UNIT-T03-*/T-03-long-*-390x844.png`。
- 是否需要另立项处理页面在窄屏下的布局，由主线决定。

## R6-5. 进程与端口

- 采样时刻：16:07:45 与 16:08:43（`ports-after.txt`）。
- 5292、5293 均无监听；没有 vite、playwright、chromium 进程。
- `npm run build` 在 worktree 内生成了 `dist/`（已被 gitignore）。
- 本轮未建库、未造数。**待回收清单：无。**
- 时间盒：从 16:05 到 16:09，约 4 分钟。

# 主线复验（2026-10-30 16:10~16:16，不采信子代理结论，worktree 内亲跑）

| 项 | 命令 / 做法 | 结果 |
|---|---|---|
| 环境验明正身 | 开跑前 `ss -ltn` 无 5174/5291/5292/5293/8081 监听、无 vite/playwright/chromium 进程；`npx tsc -b --clean` 并删除 worktree `dist/` 后再构建 | 干净起跑 |
| 类型检查 | `npx tsc -b` | exit 0 |
| Vitest | 本任务 2 文件 + 回归 3 文件（`task261028-vat-subtotal` / `task261027RoleDisplayPrecision` / `ComponentCell.readonlyPrecision`） | 5 files / 54 tests passed，exit 0 |
| 构建 | `npm run build` | exit 0（仅既有 chunk 体积告警） |
| T-03 几何 | `playwright -c e2e/task261030-costing-subtotal-unit.config.ts` | 6 passed，exit 0 |
| S-FLOW A/B（主线自建基线，未复用测试员的 baseline.json） | `T261030_AB_DIR=/tmp/t261030-mainline-ab`：先 `T261030_TARGET=baseline T261030_VITE_ROOT=<主工作区 cpq-frontend>`（master `2384efe2`，`cpq-frontend` 0 改动、无 `CostingSubtotalSummary.tsx`），再候选 | 基线 9 passed（含 CAL-1 / CAL-2 自证）；候选 11 passed / 2 skipped（CAL 仅基线），exit 0；跑完无端口残留 |
| 首次候选误红的归因 | 第一次未设 `T261030_AB_DIR` 直接跑候选，5 条失败，原文均为「A/B 基线缺「…」」——A/B 用例缺基线时按设计硬失败（非产品问题）；补跑基线后同用例全过 | 已排除 |
| 原型逐屏比对 | 读 `证据/RUN6-*/shots2/` 元素截图：编辑页 USD 两列、编辑页 CNY 一列、详情页无核价结构 `-` | 与 `原型图/01`、`02` 一致；偏差仅首行字重 700（原型 750，沿用报价侧 `.qt-subtotal-value`，前端 F-1 回报已列） |
| 契约 | 本次无 HTTP 契约变更 | 无需回写 `main-api.md` |

**主线结论**：AC-1~AC-10 在「前端 + 夹具应答」口径下全部亲跑通过。**未覆盖**：真实后端数据与固定测试环境 `http://10.177.152.12:5173` 的 UI 亲验（本机不可达），页面级销售角色 4 位显示，`task261028-vat-subtotal-flow.spec.ts` 改写后的执行（依赖真实后端）。
