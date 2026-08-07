# fronttask · 价格调整更新任务性能优化

> 结论先行：**本期前端零代码改动。** 本文件不是空槽占位，而是"为什么不改"的判定依据 + 回归确认清单 + 二期条件。

---

## 1. 判定：本期不改前端

| 判定项 | 结论 | 依据 |
|---|---|---|
| 新增/改动页面 | 无 | 本任务是后端 job 执行链路提速，不改任何用户可见流程 |
| 新增/改动组件 | 无 | — |
| 接口契约变更是否需要前端跟进 | **否** | `api.md` 唯一变更是 `GET/PUT /price-adjust/settings` 各多一个布尔字段，而**全前端对该路径命中 0 处**（实测 `grep -rn "price-adjust/settings" cpq-frontend/src` 无结果）—— 该端点目前只有后端，尚无任何配置页消费它 |
| 状态管理 / 缓存 key | 无变化 | 不涉及 `useDriverExpansions` / `usePathFormulaCache` / driver expansion key 等前端缓存维度 |
| 是否触发 `CLAUDE.md`「修改后强制自检」第 5 条 E2E 强制清单 | **否** | 该清单触发条件是改动 `QuotationStep2.tsx` / `useDriverExpansions.ts` / `ReadonlyProductCard.tsx` 等**前端协议级文件**或 `CardSnapshotService` / `ComponentDriverService` 等后端文件。本任务确实动 `CardSnapshotService.java`（新增重载），**故后端侧 E2E 仍须跑**，但那属 `backtask.md`/`test.md` 范围，不因此产生前端改动 |
| 是否触发 AP-44（字段类型联动 17 处） | **否** | 未新增或改动任何 `field_type` |
| UI 交互规范（Drawer / SelectableTable） | 不适用 | 无新增界面 |

---

## 2. 前端侧回归确认清单（不改代码，但必须确认没被改坏）

后端改的是**核价卡片值的生成路径**，前端是这些值的消费方。以下三处必须人工确认渲染无变化：

| # | 页面 / 入口 | 确认点 | 判定 |
|---|---|---|---|
| RG-1 | 报价单详情 → 产品明细 → **核价单**子视图 | 升版后的核价卡片各页签数值、行数与改造前一致 | 与 `test.md` 的 MD5 比对互为印证：MD5 全等则渲染必然一致；本项是**人眼兜底**，防"MD5 比的不是渲染真正读的那份" |
| RG-2 | 核价工作台（`CostingReviewPage`）→ 冻结模式 | 已提交单打开不空白、页签不清零 | 历史事故基线：2026-08-03 曾出现"核价卡片 17 个 tab 清零却全报 SUCCESS" |
| RG-3 | 报价单详情 → **比对视图** | 报价/核价/差异三行块数值不变 | 比对视图读的是同一份冻结快照 |

> 🔒 RG-2 的历史事故值得复述：那次 `ContextNotActiveException` 被逐组件 `catch` 吞掉，render 照常返回、job 照常报 SUCCESS，但**核价卡片已经悄悄清零**。本任务的 FR-5（失败回退逐项）正是防止批量化把这个已修好的失败可见性又弄丢。**前端看不出"该报错却没报错"，只能靠后端守卫 + 本清单人眼复核。**

---

## 3. 自检项

本期无前端代码改动，故 `tsc --noEmit` / Vite transform 200 / 前端 E2E **均不因本任务触发**。

⚠️ 但有一条例外：若实施期间为验证 RG-1~RG-3 而临时改了任何 `.tsx`/`.ts`（例如加临时日志），则必须按 `CLAUDE.md`「修改后强制自检」前端四步走完，并在交付前**还原**。

---

## 4. 二期才会产生前端工作的条件

| 触发条件 | 届时的前端工作 |
|---|---|
| 业务方要求 S0 守卫开关**在界面上可配**（而非只能走 API/DB） | 新建或扩展「调价系统参数」配置页：`GET/PUT /price-adjust/settings`（契约见 `api.md`），按 UI 规范用 Drawer；写操作限 `SYSTEM_ADMIN`，读可放宽到 `PRICING_MANAGER` |
| 更新任务进度抽屉需要展示"本批分了几组渲染"等诊断信息 | 扩展 `JobProgressDrawer.tsx`；**本期明确不做** —— 那是实现细节，不该泄漏到业务界面 |

> 以上均**不在本期范围**，届时按任务平台规则另行立项或登记 BACKLOG。
