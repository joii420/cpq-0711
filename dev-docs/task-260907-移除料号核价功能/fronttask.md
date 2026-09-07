# fronttask · 移除主数据维护「料号核价」

> 前端只按本文件做。AC 原文在 `需求文档.md §③`，**本文件只标编号不复制原文**（复制会双写漂移）。
> 🚨 本任务**只做减法**：不新增任何组件、控件、状态、样式。若发现自己在做设计决策 → 已超范围 → 停下来报主线。

---

## 任务分解

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **F-1** | AC-1, AC-2, AC-9 | **壳页摘页签 + 改默认落点**：`pages/master-data/MasterDataHubPage.tsx` —— ① 删 `import PartCostingTab`；② 删 `items` 里 `{ key: 'part-costing', label: '料号核价', ... }` 一项；③ `useState<string>('part-costing')` → `useState<string>('material')`；④ 改写文件头 javadoc（7 项 → 6 项、默认落点、去掉「导入核价数据已移入料号核价页签」那句）。**其余 6 项的 key / label / children / 顺序一个字节不动。** 视觉基准：`原型图/1-主数据维护-页签栏-默认态.html` |
| **F-2** | AC-3, AC-6, AC-7 | **删前端专属组件**：`pages/master-data/part-costing/PartCostingTab.tsx`、`pages/master-data/part-costing/PartCostingDrawer.tsx` 两个文件物理删除 |
| **F-3** | AC-3, AC-5, AC-6, AC-7 | **删导入抽屉与其服务方法**：① 删 `pages/master-data/PricingBasicDataImportDrawer.tsx`；② 从 `services/basicDataImportV6Service.ts` 删 `importPricing`、`downloadPricingTemplate` 两个方法及其专用类型（若有）。🚫 **`importQuote` / `pollImportResult` / `parseProgress` / `BASE` 常量必须保留** —— 本任务的删除边界是「pricing 两个方法」，其余一律不碰。<br>🚨 **并发撞车（2026-09-07）**：`task-260907-报价导入建单切ds新表` 要摘掉 `QuoteBasicDataImportV6Drawer` 的入口并把 `/quote` 端点改 410。⇒ ① 报价侧这三个方法的去留**归对方裁决，不归你**；② 即便你发现 `importQuote` 在你的分支上已无消费方，**也不许顺手删** —— 那会和对方的改动撞车，且不在本任务任何一条 AC 里；③ 若拉到的版本里 `QuoteBasicDataImportV6Drawer.tsx` 已被对方删除，照原样接受，不要"修复"引用 |
| **F-4** | AC-6, AC-16 | **公共件迁移（A0 岜路 1 · 方案乙）**：`git mv` 四个文件到新建目录 `pages/master-data/shared/` —— `EditableSheetTable.tsx`、`SheetPartListTab.tsx`、`types.ts`、`api.ts`→**改名 `sheetApiFactory.ts`**。迁完删除空目录 `part-costing/`。<br>`sheetApiFactory.ts` 内**只保留 `createSheetApi` 工厂**，删除：`BASE` 常量、`legacy` 实例、`listParts`/`getSheets`/`getOverview`/`getRows`/`getVersions`/`saveRows`/`lookup` 七个具名导出、`PartSortBy` 类型、`LookupFn` 类型（若无消费方则删，有则保留）。<br>🚨 **AC-16 硬约束**：四个文件除 ① import 路径 ② 文件头注释 ③ 上述 legacy 段删除 外，**函数体 / JSX / props 定义 / 类型字段名 / 样式一个字节都不许改**。有优化冲动 → 记 `BACKLOG.md`，不在本任务动手 |
| **F-5** | AC-8a, AC-8b, AC-8c, AC-14, AC-16 | **改 6 处 import 路径**（全在 `pages/master-data/dataset/` 下，实查行号）：`types.ts:11`、`DatasetSheetDrawer.tsx:22,23`、`api.ts:9,10`、`DatasetPartListTab.tsx:16,17`、`PlatingSchemeTab.tsx:20`。**只改路径字符串，不改导入的符号名**（`createSheetApi` 从 `../part-costing/api` → `../shared/sheetApiFactory`，其余从 `../part-costing/X` → `../shared/X`）。改完 `npx tsc --noEmit` 必须 0 错误 —— 漏一处必红，这是本项的验证手段 |
| **F-6** | AC-7 | **清理残留文本引用**：① `pages/product/` 下 5 处注释（`ProductSalesPartTab.tsx:18`、`productHubApi.ts:12,52`、`productHubTypes.ts:5`、`ReadonlySheetTable.tsx:12`）—— 保留「本任务不得 import 该目录」这类历史约束的**语义**，把 `part-costing/` 改写为「主数据维护核价侧旧公共件目录（已于 2026-09-07 迁至 `shared/`）」；② `pages/master-data/listConventions.ts:5,29,50` 的页签清单与「料号核价用裸 `<Table>`」说明；③ `layouts/MainLayout.tsx:45` 的页签清单注释；④ `pages/master-data/dataset/types.ts:5`、`DatasetImportDrawer.tsx:10`、`dataset/api.ts:5,6,35`、`DatasetSheetDrawer.tsx:9`、`DatasetPartListTab.tsx:8`、`PlatingSchemeTab.tsx`、`shared/EditableSheetTable.tsx:111`、`shared/types.ts:34,56` 里对 `/pricing-basic-data` 与「料号核价」的引用。<br>**判据 = AC-7 的 grep 命中数 0**（含 `part-costing` 与 `料号核价` 两个词） |
| **F-7** | AC-14 | **前端自检**：`cd cpq-frontend && npx tsc --noEmit`（0 错误）+ `npm run build`（exit 0）。🚫 不跑这两条不许报完成 |

---

## 双向覆盖自检

**正向 —— 每条前端相关 AC 都有人认领：**

| AC | 认领 |
|---|---|
| AC-1 页签集合 | F-1 |
| AC-2 默认落点 | F-1 |
| AC-3 导入按钮消失 | F-2, F-3 |
| AC-6 代码物理删除 | F-2, F-3, F-4 |
| AC-7 零残留引用 | F-2, F-3, F-6 |
| AC-8a/8b/8c 公共件回归 | F-5（迁移不弄坏消费方）+ F-4（内容零变化） |
| AC-9 切换与刷新 | F-1 |
| AC-14 构建与类型 | F-5, F-7 |
| AC-16 公共件内容零变化 | F-4, F-5 |

| AC-11 角色边界 | F-1（页签集合对所有角色一致）+ F-4（抽屉保存按钮所在的公共件迁移后仍可用） |

> AC-4 / AC-5 / AC-10 / AC-17 由后端认领（见 `backtask.md`）。
> AC-5 前端侧只负责删调用方（F-3），端点下线由 B-2 负责，故 F-3 也标了 AC-5。

### 四条「验证类 AC」的认领归属（补充说明，防止被判交付缺口）

`AC-12` / `AC-13` / `AC-15` 断言的是「**某某没有变化**」，它们没有、也不应该有对应的实现动作 —— 若有人为它们写代码，反而说明改坏了东西再去补。它们的认领关系是**倒过来的**：

| AC | 谁保证 | 谁验证 |
|---|---|---|
| AC-12 V6 数据零变化 | **全体 F-x 与 B-x 共同保证** —— 具体保证手段是 `backtask.md` B-0 的四条红线（无 DDL / 无写库 / 无迁移 / 不删 pricing 包） | 🔒 主线亲验（T-12） |
| AC-13 核价渲染零回归 | 同上（V6 表没被碰 ⇒ 渲染不可能变） | 🔒 主线亲验（T-13） |
| AC-15 E2E 套件不新增失败 | **F-1~F-7 与 B-1~B-7 全体** —— 任一项做坏都会在这里现形 | 测试代理（T-15） |

⇒ 这三条是**兜底网**，不是待办项。写进 AC 是为了让"没弄坏"这件事有可复核证据，而不是靠"应该没问题"。

**反向 —— 每个 F-x 都指回 AC：** F-1→AC-1/2/9 · F-2→AC-3/6/7 · F-3→AC-3/5/6/7 · F-4→AC-6/16 · F-5→AC-8a/8b/8c/14/16 · F-6→AC-7 · F-7→AC-14。**无孤儿项。**

---

## 🚫 明确不做（防止顺手扩范围）

1. 🚫 **不改剩余 6 个页签的任何内容**（key / label / children / 顺序 / 内部组件）
2. 🚫 **不改 `MaterialRecipeManagement`** —— 它只是从第 2 位变成第 1 位并成为默认落点，组件本身零改动
3. 🚫 **不优化公共件**（AC-16 明令）—— 发现问题记 `BACKLOG.md`
4. 🚫 **不动 `pages/product/` 下的任何代码逻辑** —— 那 5 处只是注释，`productHubApi.ts` 自带 `createSheetApi` 副本，与本次迁移无代码耦合
5. 🚫 **不动 `pages/quotation/QuoteBasicDataImportV6Drawer.tsx`** 与它用的三个服务方法
6. 🚫 **不给 `activeTab` 加持久化**（localStorage / URL query）—— AC-9 明确要求保持「刷新回默认」的现状
7. 🚫 **不新建路由、不改 `router/index.tsx`、不改左侧菜单项**

---

## 已知坑（实测，别再踩一遍）

| 坑 | 说明 |
|---|---|
| **`grep` 是 `ugrep -I` 别名** | 会把中文注释多的源文件**静默判为二进制返空**。AC-7 的扫描必须用 `/usr/bin/grep -a`，据别名 grep 的空结果下「已清理干净」结论是假绿 |
| **`tsc` 才查真实文件** | worktree 里改前端，共享 5174 服务的是主仓不是 worktree。F-5 的验证靠 `npx tsc --noEmit`，不要靠「打开 5174 看着正常」 |
| **`git mv` 而非删+建** | 用 `git mv` 保住 blame 链；AC-16 的 `git diff` 判据也依赖它才能显示为「重命名 + 少量改动」而不是「全删 + 全新增」 |
