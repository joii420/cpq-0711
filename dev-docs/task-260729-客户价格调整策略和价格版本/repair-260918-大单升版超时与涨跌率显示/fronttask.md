# fronttask · repair-260918 大单升版超时与涨跌率显示（v2 · 2026-09-18，前端任务未变）

> 立项文档：`问题说明.md`（⑤ 方案 / ⑥ AC）。**只标 AC 编号，AC 原文以 `问题说明.md` ⑥ 为准。**
> 契约：`api.md`。原型：`原型图/index.html`（进度抽屉按 `原型图/进度抽屉.html` 1:1 还原）。

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| F-1 | AC-1, AC-2, AC-4 | **公共涨跌率格式化**：在 `cpq-frontend/src/utils/` 下新增一个共享函数（建议并入 `precision.ts` 或同目录新文件），入参是后端下发的涨跌率（`DecimalString \| null \| undefined`，小数形式，`0.000035` = 0.0035%），返回 `{ text, color? }`：<br>① 空 ⇒ `—`，无色；<br>② `rate × 100` 按 `ROUND_HALF_UP` 保留**最多 4 位**小数并去尾零；<br>③ 非零但②结果为 0 ⇒ `+<0.0001%` / `-<0.0001%`；<br>④ 正 ⇒ 前缀 `+`、红 `#cf1322`；负 ⇒ 自带 `-`、绿 `#389e0d`；恰为 0 ⇒ `0%` 无色无符号。<br>替换三处各自的私有实现：`pages/pricing/price-adjust/VersionTrailPanel.tsx`（`formatRate`）、`pages/pricing/price-adjust/ElementMatrix.tsx`（`formatRate`）、`pages/pricing/price-adjust-review/ReviewDetailDrawer.tsx`（`fmtRate`），删掉三份私有函数。🔒 十进制运算一律走既有 `toDecimal`（decimal.js），🚫 不用 JS `number` 乘除。 |
| F-2 | AC-9, AC-16 | **进度抽屉显示执行中**（`pages/pricing/price-adjust-jobs/JobProgressDrawer.tsx`）：<br>① 类型 `UpdateJobDTO` 增加 `running: number`（后端新增字段，见 `api.md`；缺省按 0 处理，兼容旧后端）；<br>② 计数行在「冲突」与「等待」之间插入「执行中 N」（颜色用 AntD processing 蓝 `#1677ff`，N=0 时仍显示）；<br>③ 「等待」= `max(0, total − success − failed − conflict − stale − skipped − running)`；进度百分比口径不变（只算已终态）；<br>④ 轮询启停逻辑不变（`status === 'RUNNING'` 时 2 秒轮询，离开即停）；<br>⑤ 按原型 `原型图/进度抽屉.html` 的三个状态 1:1 还原。 |

## 为什么只有这两项

- **审核抽屉超时（AC-6）**：原因在后端试算，前端请求超时（60 秒）**不改** —— 修好后应在 5 秒内返回（AC-6）；若把超时调大，等于把问题藏起来。
- **失败原因可读（AC-15）**：明细表「错误信息」列本来就原样显示 `errorMessage`，后端换成可读文案即可，**前端不改**。
- **预算失败可见（AC-17）**：审核列表已有「预算失败」标签与「重算」入口（`PriceAdjustReviewPage.tsx:143-147`、`:193-200`），**前端不改**。
- **新错误码 `EXECUTION_TIMEOUT` / `REVISION_WRITE_FAILED` / `EXECUTION_INTERRUPTED`**：前端对 `errorCode` 只特判 `SUBTOTAL_MISMATCH`；新取值走「失败」通用展示与「重试」按钮（`status` 为 `FAILED` 即可重试），**前端不改**。
- **重启后停止轮询（AC-21）/ 批量重试含失败明细（AC-24）**：批次离开 `RUNNING` 后前端轮询本来就会停；批量重试按钮行为由后端修正，**前端不改**。

## 自检要求（交付回报必须逐条给出）

1. `npx tsc -b` 0 错误（在 **worktree 的 `cpq-frontend/`** 里跑；🚫 不要用 `tsc --noEmit -p tsconfig.json`，它编译 0 个文件、恒返回 0，见 `docs/rules/frontend.md §2.1`）。
2. `grep` 证据：三处私有格式化函数已删除、均改为调用 F-1 的共享函数。
3. 如需起页面自查：worktree 内另起临时端口的 vite（🚫 不占 5174，那是主线亲验用的），用完即停。
