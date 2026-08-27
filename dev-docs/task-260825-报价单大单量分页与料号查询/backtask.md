# 后端任务分解 · 报价单大单量前端分页

## 结论：**本次后端零改动，不派后端子代理**

按 `task-docs.md §2`「前端或接口零改动的任务也要写 `fronttask.md` / `api.md`，内容是『为什么不改』的判定依据 + 回归确认清单 + 二期触发条件」，
本文对**后端侧**做同样交代。

---

## 为什么不改（不是"这次用不上"，是"改了反而会坏"）

用户 2026-08-26 裁决「服务端不变」，而独立评审的结论恰好支持这个决定：
**v1（服务端分页）方案的 6 项 P0 中，有 5 项的根因都是「前端只剩当前页」。** 保住全量，它们自动消失。

| 评审 P0（v1 方案下） | 后端不改的效果 | 依据 |
|---|---|---|
| **B-1** `saveDraft` 用 payload 求和覆盖整单原价 | ✅ 不触发 | `QuotationService.java:2379 / :2442 / :2599`：`total` 的求和范围是 `request.lineItems`。payload 仍全量 → 求和范围仍是全单 |
| **B-2** `PriceReconciler` 整单重写 `snapshot_rows`/`row_data` | ✅ 不触发 | `QuotationResource.java:167-169` 照常整单跑；无 `partial` 就没有"名单外行被误改"这回事 |
| **Q2-a** `syncLineItemsFromResponse` 长度守卫致重复行 | ✅ 不触发 | `saveDraft` 继续返回全单 1845 行，前端 `prev` 也是 1845 → 长度匹配，回填正常 |
| **Q2-b** `ensure-card-values` 返回全量 DTO 打回分页数组 | ✅ 不触发 | 前端本来就是全量，不冲突 |
| **A-2** COMPOSITE 父子跨页断链 | ✅ 不触发 | 前端持有全量数组，`useDriverExpansions` 的父子映射完整 |
| **D-3** `/excel-view` 全量 vs 卡片当前页 | ⚠️ 由**前端**切片解决 | `ExcelViewService.java:133` 保持全量返回；前端自己切（`fronttask.md` F-6 / AC-7） |

---

## 后端侧需要**知情但不动手**的三项

这三项都已实证，**不在本次范围**，登记在此避免遗失：

| # | 事实 | 证据 | 去向 |
|---|---|---|---|
| **E-1** | **打开编辑页会自发一次整单 `PUT /draft`** —— 无任何用户编辑动作，1 行单与 1845 行单**都会触发**（详情页对照组 0 次） | 网络层拦截实测，见 `证据/开工前基线-读侧.md` | 独立缺陷，**另立 `repair-`**。它是「首次 draft 超时」的真实触发点：用户根本没点保存 |
| **E-2** | `QuotationDTO.java:226` 每行一次 `Product.findById` = **活的 N+1**（1845 行 = 1845 次查询），违反 `backend.md` N+1 硬指标 | 独立评审 | 登记 `BACKLOG`（P1） |
| **E-3** | `li.subtotal` 由 `quoteCardValues` JSON 抽取（`CardSnapshotService.java:723`），总价 = 全单 `Σ li.subtotal`（`:820-822`）→ **建单必须全算，懒物化对建单不成立** | 主线查证 | 这是「需求 3 转二期」的技术依据，写进 `需求文档.md` D-3 裁决 |

---

## 回归确认清单（前端合并前，后端侧逐条确认无副作用）

| # | 确认项 | 判据 |
|---|---|---|
| B-R1 | 后端代码零改动 | `git diff master --stat -- cpq-backend/` **输出为空** |
| B-R2 | 后端测试仍全绿 | 在**主仓** `cpq-backend/` 跑 `./mvnw test`（🚫 不在 worktree 跑，会测错树报假绿） |
| B-R3 | `saveDraft` 收到的 payload 未变 | 后端 `[saveDraft-diag]` 日志中 `received lineItems=1845`，与改动前一致 |
| B-R4 | 无新增 Flyway 迁移 | `git status` 中 `src/main/resources/db/migration/` 无新增文件 |

---

## 二期触发条件（什么时候后端才需要动）

| 触发 | 需要的后端改动 | 必须先处理的 P0 |
|---|---|---|
| 要解决**首存超时** | `saveDraft` 增加 `scope: {partial, lineItemIds}` 行 id 白名单语义 | **B-1**（原价求和范围）、**B-2**（`PriceReconciler` 整单重写）—— 这两条是**既有缺陷**，现在被"全量提交"这个巧合掩盖着 |
| 要解决**建单超时** | ① N+1 修复（`fix/task-260825-materialize-n1` 在途）② `ensureCardValues` 单事务拆成按批小事务 ③ 建单端点的 30s 前端墙处置（调高 timeout 或改早返回+轮询） | 与分页无关，可独立推进 |
| 要解决 **GET 23 MB / 9.98 s** | 读侧服务端分页 | **A-2**（COMPOSITE 卡片族分页，方案级扩范围）、**D-3**、**Q2-a**、**Q2-b** |

> ⚠️ 二期启动时**不要重新推导**：v1 的完整设计、15 条裁决、独立评审的 6 项 P0 与 9 项未验证事项，
> 全部存档于 `需求文档-v1-服务端分页-已撤回.md`。
