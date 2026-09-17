# test · repair-260916 测试方案 + AC 可追溯矩阵

> 用例**只从 `问题说明.md` ⑥ 的 AC 原文派生**。测试工程师**禁止读实现代码**：
> 🚫 `cpq-frontend/src/**` 下**非 `*.test.ts(x)`** 的文件（含 `QuotationStep2.tsx`、`ReadonlyProductCard.tsx`、`QuotationWizard.tsx`、`utils/formulaEngine.ts`、`utils/condTree.ts`）、🚫 `cpq-backend/**`。
> ✅ 可读：本目录全部文档（含 `api.md` 的函数签名与契约、`证据/`）· `docs/rules/testing.md` · `cpq-frontend/src/**/*.test.ts` 既有测试（当作调用样板）· `cpq-frontend/src/utils/losslessJson.ts`（只看导出签名）· `cpq-frontend/e2e/` 下既有 harness 与配置 · 数据库只读查询（`SELECT`）。
> 信息不足就停下报主线，🚫 不许翻实现补齐。

---

## 0. 环境前提（先读，否则会写出假绿用例）

### 0.1 运行实例

| 实例 | 地址 | 代码 | 谁起 |
|---|---|---|---|
| 共享后端 | `8081`（默认 profile → `cpq_db_0724`） | master（本任务后端零改动） | 既有，🚫 不重启 |
| master 前端 | `5174` | 主工作区 master | 既有，只用来跑「修复前」对照 |
| **修复分支前端** | **`5197`** | worktree | S-E 片自己起：worktree 的 `cpq-frontend` 下 `VITE_API_TARGET=http://localhost:8081 npx vite --port 5197 --strictPort`（`node_modules` 软链主仓，`git-worktree.md` §1.4）；**跑完即停** |

- 本机 shell 有 `http_proxy`，访问本机一律 `curl --noproxy '*'`。
- 🚫 不占 `5174` / `8081` 以外的声明端口；`5198` 留给前端工程师自检。
- 🚦 **每次开跑 Playwright 前**先采样：`pgrep -f "node.*[p]laywright test"`，**有输出就等**（决策台账 `playwright.config.ts`：E2E 无跨进程互斥，`global-setup` 每次写共享库 `user` 表）。
- 修复分支前端探活要**验明正身**（`testing.md` §4.2）：`curl` `http://localhost:5197/src/pages/quotation/QuotationStep2.tsx` 的响应里能找到前端工程师回报的改动特征串，才算跑的是修复后代码。
- 本任务**不跑** `mvnw test`（后端零改动）。

### 0.2 测试数据（全部取自真实单据，🚫 不手搓）

| 数据 | 用途 | 纪律 |
|---|---|---|
| `QT-20260916-0879`（id `a566efeb-72fe-48f9-b2c8-e1543283beab`） | 复制源 · AC-10 只读样本 | 🚫 不在其上编辑 |
| 副本 Q′（`POST /api/cpq/quotations/a566efeb-72fe-48f9-b2c8-e1543283beab/copy`，body `{}`） | AC-1~AC-8、AC-12 | **每轮一张新副本**；把返回的 `id` / 单号写进 `test-report.md`；`finally` 里 `DELETE /api/cpq/quotations/{id}` 回收，**只删自己记下的 id** |
| `QT-20260916-0874` | AC-9 只读 | 🚫 不进编辑页 |
| `QT-20260914-0866`「BOM」页签、`QT-20260913-0859`「材质元素」页签 | AC-10 只读 | 🚫 不进编辑页 |
| `证据/离线判决/fixture_0879_wuliao.json` | S-U 真实数据夹具 | 复制到 `cpq-frontend/src/pages/quotation/__fixtures__/qt20260916-0879/wuliao.json`，🚫 不改内容 |

⚠️ 副本单号无法自定义前缀（复制接口不收名称）⇒ **按 id 隔离**：只断言、只清理自己记录的 id。

### 0.3 期望值与精度口径（防假红）

- 期望值来自证据 §3.3（前端真实引擎离线算出，E0 与后端现值逐位一致）。
- 页面显示 9 位；断言按 9 位字符串比较。
- **列合计 = 每格先舍到 9 位再相加**（后端列小计、前端 Excel 快照都是这个口径）。🚫 不要用 12 位原值累加算期望（E3 会差 1：`1.978006121` vs 正确的 `1.978006120`）。
- 字面期望只在 T0 那组输入下成立（Ni `105`、Cu `101.1392`、Zn `24.1695`、Ag `28892.5`、税率 `1.13`、来料固定加工费小计 `127.5`）。AC-3 / AC-6 查库时顺带复核；**任何一项变了即停下报主线**，🚫 不许自行改期望值。

### 0.4 夹具读取（S-U 必读，已踩过的坑）

- 夹具 JSON **必须**用 `tryParseSnapshotJsonLossless`（`src/utils/losslessJson.ts`）读，🚫 不用 `JSON.parse`。
  立项期实测：用 `JSON.parse` 读时对照组失败（「材料成本」整列 0）——数字被读成 JS number 后会被引擎丢弃，净重缺失 ⇒ 乘积 0。**这是夹具读法错误，不是缺陷。**
- 夹具结构：`{ comp, rows: [{row, basicDataValues, nodeId, parentId, lvl}], crossTabRows, backendFormulaResults }`；另需传 `allComponentSubtotals = { 'COMP-0001#税率': '1.13', 'COMP-0004#加工费': '127.5' }`、`partNo = 'S3120011203'`。
- 行下标：0 根（S3120011203）· 1 S3110520422 · 2 00144 · 3 00255 · 4 00256 · 5 00257。
- 改上游值 = 改 `crossTabRows` 中 `57554055-0896-4cc8-be98-65e45b0a5985` **与** `COMP-0005` 两个键下的同一行（`料号`+`要素` 定位；`费用` / `比例` 字段），两个键都要改。
- 模拟「行数据是旧的」= 改 `rows[i].row` 里对应公式列的值（如 `rows[5].row['来料加工费'] = '150.8'`）。
- 调用样板见既有 `src/pages/quotation/treeFormula.test.ts`、`formulaParityQt0068.repair0805.test.ts`。离线判决脚本 `证据/离线判决/*.test.ts` 可作参考（它们在 master 上的行为已知，见证据 §3）。

---

## 1. 分片计划

| 片 | 写入面 | 共库 | 解锁执行条件 | 认领 AC |
|---|---|---|---|---|
| **S-U**（单元） | 只写 worktree 内新增测试文件与夹具；不连库、不起服务 | 不涉及 | 用例写完即可在 **master 代码**上跑一次（证伪：应红）；前端工程师报完成后在修复分支上跑（应绿） | AC-11、AC-13 |
| **S-E**（E2E，按 `S-全局` 纪律串行） | 共享库 `cpq_db_0724`：自建 Q′ 副本（私有写）；Playwright `global-setup` 写 `user` 表（全局） | 与本任务其他片不冲突（S-U 不连库）；与**其他会话**的 Playwright 互斥（开跑前采样） | master 对照部分：用例写完即可跑；修复分支部分：前端工程师报完成 + 5197 验明正身后 | AC-1~AC-10、AC-12、AC-14 |

- 两片可并行（S-U 不连库、不起服务）。S-E 内部所有 Playwright 运行**串行**。
- 每条 AC 恰好属于一片（见 §3 矩阵）。
- 🚫 S-U 不许读写任何库；🚫 S-E 不许改 worktree 里 S-U 的文件，反之亦然。

---

## 2. 用例

### 2.1 S-U（`cpq-frontend/src/pages/quotation/bfieldStaleRowData.repair260916.test.ts`）

| 用例 | 覆盖 | 内容 | master 上 | 修复分支上 |
|---|---|---|---|---|
| U-0 对照 | 夹具可信 | 夹具原样喂 `computeTabFormulasTree` ⇒ 6 行 × 8 公式列与 `backendFormulaResults` 9 位逐格相等；断言前先断言 6 行、8 列、非空 | 绿 | 绿 |
| U-1 | AC-13（AC-1 离线等价） | `rows[5].row['来料加工费']='150.8'`（旧），`crossTabRows` 中 00257 来料加工费仍 `170.404` ⇒ 00257 材料成本 `0.002418226`、S3110520422 `0.059189199` | **红**（实得 `0.002246091` / `0.059185757`） | 绿 |
| U-2 | AC-13 | 同 U-1 但用 `computeAllFormulas` 逐行算 00257（传该行 `row` / `basicDataValues` / `crossTabRows` / subtotals / partNo） ⇒ 材料成本 `0.002418226` | **红**（`0.002246091`） | 绿 |
| U-3 | AC-13（期望值表） | 行数据保持 T0 原样（不改），只改 `crossTabRows`：E1（费用 200）/ E2（费用 200、比例 10）/ E3（费用 0、比例 10）⇒ 各行材料成本与 §0.3 / 证据 §3.3 一致；另断言 E3 六格 9 位值之和 = `1.978006120` | E1 起**红** | 绿 |
| U-4 | AC-11a | 构造组件（见 §2.3）两个入口各一遍：原始行 `A='8'`、`X='1'`（旧）⇒ `Y='1'` | **红**（`2`） | 绿 |
| U-5 | AC-11b | 构造组件两个入口各一遍：`N='3'` ⇒ `Z='15'`；`N=''` ⇒ `Z='0'` | 绿 | 绿 |
| U-6 | AC-11c | 两个入口各一遍：调用前深拷贝入参行，调用后逐键比较（含公式列键仍在、值不变） | 绿 | 绿 |

**证伪（AC-13 第 3 项）**：在 master 代码上跑本文件，**U-1、U-2、U-4 必须失败**，把失败输出原样贴进 `test-report.md`；任何一条在 master 上是绿的 ⇒ 用例没打中缺陷，停下报主线。
方法：worktree 里 `git stash` 实现改动**之前**先跑一次（或在前端工程师开工前跑）；🚫 不许手工改实现文件做对照（`testing.md` §5.7）。

### 2.2 S-U 其余（AC-13 前两项）

- `cd <worktree>/cpq-frontend && npx tsc -b` → 退出码 0，贴原始输出。
- `npx vitest run src`：master 代码一次、修复分支一次，各记失败用例清单；判据 = 修复分支失败集合 ⊆ master 失败集合 ∪ {无}（本任务新增用例除外——它们在修复分支应全绿）。

### 2.3 AC-11 构造组件（JSON 形态，直接照抄）

```ts
// 宿主组件 H（非树）
fields: [
  { name: 'A', field_type: 'INPUT_NUMBER' },
  { name: 'N', field_type: 'INPUT_NUMBER' },
  { name: 'X', field_type: 'FORMULA', formula_id: 'fx' },
  { name: 'Y', field_type: 'FORMULA', formula_id: 'fq',
    conditional_formula: {
      rules: [{ when: { kind: 'group', logic: 'and', children: [
                  { kind: 'leaf', left: 'X', op: 'gt', rhs: { type: 'literal', value: '10' } } ] },
                formula_id: 'fp', formula: 'P' }],
      default_formula_id: 'fq', default: 'Q' } },
  { name: 'Z', field_type: 'FORMULA', formula_id: 'fz' },
]
formulas: [
  { id: 'fx', name: 'X', expression: [ {type:'field',value:'A'}, {type:'operator',value:'*'}, {type:'number',value:'2'} ] },
  { id: 'fp', name: 'P', expression: [ {type:'number',value:'1'} ] },
  { id: 'fq', name: 'Q', expression: [ {type:'number',value:'2'} ] },
  { id: 'fz', name: 'Z', expression: [ { type:'cross_tab_ref', agg:'SUM', match:[], source:'SRC', target:'',
      targetExpr: [ {type:'field',value:'v',source:'SRC'}, {type:'operator',value:'*'}, {type:'b_field',value:'N'} ] } ] },
]
crossTabRows: { SRC: [ { v: '5' } ] }
```

- 数字一律用**字符串**（精度契约：计算链禁 JS number）。
- `computeTabFormulasTree` 调用时 `rows = [{ row }]`（不带 `nodeId`）即可。

### 2.4 S-E（`cpq-frontend/e2e/repair260916-bfield-stale.spec.ts`，配置 `e2e/repair260916.config.ts`）

- 配置：复制 `e2e/playwright.config.ts` 的写法，`baseURL` 取 `PW_BASE_URL`；`workers: 1`；报告与截图输出到 `test-results/repair260916/`，**跑完把证据图复制到本目录 `证据/e2e/<master|fix>/`**（下一轮会清空输出目录，`testing.md` §2）。
- 断言统一用 `expect.soft`，一轮跑完再汇总——master 对照轮要把**全部**偏差记下来，不能在第一处失败就停。
- 页面取值：读单元格**显示文本**；⚠ 个数 = 公式单元格里 ⚠ 标记的数量（悬停取提示文字）。选择器坑先读记忆/既有 spec（antd v6：`.ant-drawer-section`、tooltip 内容类名等），**取到空值先怀疑选择器**。
- 编辑单元格：点击「来料其他费用」对应行「费用」/「比例」输入框 → 清空 → 输入 → 点空白处；等待 `…/quote-card-edit` 响应 200。

| 步 | 覆盖 | 操作要点 |
|---|---|---|
| E-0 | T0 | 建副本、记 id；打开编辑页 → 第 2 步 → 产品卡片 →「物料」；核 T0 前置断言（页面 7 个值 + 查库 6 个输入），不满足即中止本轮 |
| E-1 | AC-1 | 改 00257 费用 → `200`，**不等响应**立即点「物料」，读 6 个值 |
| E-2 | AC-2 | 等 `quote-card-edit` 200 + 3 秒 → 切「来料其他费用」→ 切回「物料」→ 6 行 × 8 列 ⚠ 计数 + AC-1 三值；有 ⚠ 时记录悬停文字 |
| E-3 | AC-3 | 查库 Q′ `quote_card_values` 物料 `formulaResults`（00257、S3110520422）+ 复核输入 |
| E-4 | AC-4 | 三小步：改 00256 比例 → `10` 并读值；切「产品」再回；刷新再读；每步 ⚠ 计数 |
| E-5 | AC-12 | **E-4 第 1 小步之后、刷新之前**：点「保存草稿」，拦截 `PUT /api/cpq/quotations/{id}/draft` 请求体，解析「物料」页签 `rowData`（JSON 字符串）取 00256、00257 两行 |
| E-6 | AC-5 | 改 00257 费用 → `0`，点「物料」读值 + ⚠ 计数 |
| E-7 | AC-6 | 点「保存草稿」等 200 → 查库 `quote_excel_values`、`subtotal`、`subtotalByColumn.材料成本`（+ 复核输入）→ 用页面会话下载 `GET /api/cpq/quotations/{id}/export-excel-view`，解析 xlsx 取 Q′ 行「材料成本」「产品单价」 |
| E-8 | AC-7 | 打开 Q′ 详情页 →「物料」读 3 值 + ⚠ 计数 |
| E-9 | AC-8 | Q′ 编辑页 →「核价单」→ 逐页签抓全部公式列显示值，存 JSON |
| E-10 | AC-9 | 0874 详情页 →「产品」→「管理费」⚠ 与悬停文字 |
| E-11 | AC-10 | 0879 / 0866 / 0859 详情页指定页签抓全部公式单元格，存 JSON；0879 与库 `formulaResults` 比 |
| E-12 | 清理 | `finally`：`DELETE` 本轮 Q′ |

**执行顺序**：

1. **master 轮**（`PW_BASE_URL=http://localhost:5174`，前端工程师未完成时即可跑）：E-0~E-12 全跑。期望：E-1/E-2/E-4/E-5/E-7 出现 `问题说明.md` 各 AC「修复前」所写的偏差（阳性对照，贴实际值）；E-9、E-11 的抓取结果作为 AC-8 / AC-10 的基线；E-10 必须看到 ⚠ + 「细项引用命中多行」（否则停下报主线换样本）。
2. **修复轮**（`PW_BASE_URL=http://localhost:5197`）：E-0~E-12 全跑，全部断言通过；E-9 / E-11 与 master 轮基线逐格相等。
   ⚠️ AC-8 / AC-10 的两份抓取要**尽量相邻时间**（先 master 轮抓基线、紧接着修复轮）；若中间库里数据被别人改了导致不等，先查 `updated_at` 再下结论（`testing.md` §4.1.5）。
3. **AC-14**：修复轮结束后，`e2e/quotation-flow.spec.ts` 分别对 `5174` 与 `5197` 各跑一次，记失败用例名清单；判据 = 两份清单相同。另 `curl` 5197 上 `QuotationStep2.tsx` 模块地址 → 200。

---

## 3. AC 可追溯矩阵

| AC | 覆盖它的测试 | 层级 | 分片 | 验收证据形式（归档位置） |
|---|---|---|---|---|
| AC-1 | E-1（master 轮 + 修复轮） | E2E | S-E | 截图 ×2 · `证据/e2e/` |
| AC-2 | E-2 | E2E | S-E | 截图 ×2（含 master 轮 ⚠ 悬停文字）· `证据/e2e/` |
| AC-3 | E-3 | E2E + SQL | S-E | SQL 原始输出 · `test-report.md` |
| AC-4 | E-4 | E2E | S-E | 截图 ×3 步 · `证据/e2e/` |
| AC-5 | E-6 | E2E | S-E | 截图 · `证据/e2e/` |
| AC-6 | E-7 | E2E + SQL + 导出文件 | S-E | SQL 输出 + 导出 xlsx 副本 · `证据/e2e/` |
| AC-7 | E-8 | E2E | S-E | 截图 · `证据/e2e/` |
| AC-8 | E-9 | E2E | S-E | 两份 JSON · `证据/e2e/` |
| AC-9 | E-10 | E2E | S-E | 截图（含悬停）×2 · `证据/e2e/` |
| AC-10 | E-11 | E2E + SQL | S-E | 两份 JSON · `证据/e2e/` |
| AC-11 | U-4、U-5、U-6 | 单元 | S-U | 测试输出 · `test-report.md` —— ⚠️ **仅单元测试覆盖**（全库无样本，`问题说明.md` AC-11 已声明），闸门 B 显式列出 |
| AC-12 | E-5 | E2E（请求体） | S-E | 请求体原文 · `证据/e2e/` |
| AC-13 | U-0~U-3、§2.2 | 单元 + 命令 | S-U | 命令原始输出 + master/修复两份失败清单 · `test-report.md` |
| AC-14 | §2.4 第 3 步 | E2E + curl | S-E | 两份失败清单 + curl 输出 · `test-report.md` |

每条 AC 恰好一片：S-U = {AC-11, AC-13}；S-E = {AC-1~AC-10, AC-12, AC-14}。

---

## 4. 本任务会动的全局状态（`testing.md` §4.3 登记）

| 对象 | 谁动 | 还原 |
|---|---|---|
| 共享库 `user` 表（Playwright `global-setup` 解锁账号 / 登录） | S-E 每次运行 | 既有 harness 行为，无需还原；开跑前采样互斥 |
| 共享库新增报价单 Q′（每轮 1 张，共 2 张） | S-E | `finally` 按 id `DELETE`；若删失败，id 列入 `test-report.md`「待回收清单」 |
| 其他任何数据 | — | 🚫 不许写 |

一次性库：**无**。
