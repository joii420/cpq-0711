# 接口契约 · 报价单大单量前端分页与料号查询

## 结论：**本次零接口变更**

**没有新增端点、没有修改端点、没有删除端点、没有改动任何请求/响应结构。**
按 `task-docs.md §2.5`，**本任务无需回写 `dev-docs/main-api.md`**（该事实亦须复述于 `test-report.md`）。

---

## 判定依据（逐个端点核对，不是"我觉得没改"）

本任务触及的页面共依赖 5 个端点，逐一说明为什么不动：

| 端点 | 谁在用 | 现状 | 本次是否改动 | 依据 |
|---|---|---|---|---|
| `GET /api/cpq/quotations/{id}` | 编辑页、详情页 | 全量返回 1845 行，**23.04 MB / 9.98 s**（实测） | ❌ **不改** | 前端持有全量数据是本方案成立的前提：总额求和（AC-21）、payload 全量（AC-22）、COMPOSITE 父子映射（AC-25）三条都依赖它 |
| `PUT /api/cpq/quotations/{id}/draft` | 编辑页保存 | 整单全量语义（`SaveDraftRequest.lineItems` 全量回传） | ❌ **不改** | 无 `partial`、无 `scope`、无白名单。payload 仍是全量 1845 行 |
| `GET /api/cpq/costing-orders/{coid}` | 核价工作台 | 全量返回 | ❌ **不改** | 同上，前端切渲染 |
| `GET /api/cpq/quotations/{id}/excel-view` | Excel 视图（v2 形态） | 全量返回 1845 行（`ExcelViewService.java:133`） | ❌ **不改** | 🚨 **由前端切片**（`fronttask.md` F-6 / AC-7）。这是"接口不改"付出的代价，必须在前端补上 |
| `POST /api/cpq/quotations/{id}/ensure-card-values` | 卡片值预热 | 返回全量 `QuotationDTO` | ❌ **不改** | 前端本来就是全量，长度匹配，不冲突（评审 P0 `Q2-b` 在本方案下不触发） |

---

## 为什么"接口不改"反而更安全（三条实证）

独立评审在 v1（服务端分页）方案下查出 6 项 P0，**其中 5 项的根因都是「前端只有当前页」**。本方案保留全量，故：

| 评审 P0 | 本方案下 | 依据 |
|---|---|---|
| **B-1** `saveDraft` 用 payload 求和覆盖整单原价 | ✅ 不触发 | `QuotationService.java:2379/2442/2599` 的 `total` 求和范围是 `request.lineItems`；payload 仍全量 → 求和范围仍是全单 |
| **B-2** `PriceReconciler` 按整单重写 `snapshot_rows`/`row_data` | ✅ 不触发 | `QuotationResource.java:167-169` 照常整单跑；无 `partial` 就不存在"名单外行被改"的问题 |
| **A-2** COMPOSITE 父子跨页断链 | ✅ 不触发 | `useDriverExpansions.ts:258-267` 遍历全量数组建 `parentLineItemId → [childId]` 映射，全量在手则映射完整 |
| **D-3** `/excel-view` 全量 vs 卡片当前页 | ⚠️ **需前端处理** | 后端仍全量返回 → 前端必须自己切片，否则两侧行数不一致 |
| **Q2-a** `syncLineItemsFromResponse` 长度守卫致重复行 | ✅ 不触发 | `QuotationWizard.tsx:776` `if (respLines.length !== prev.length) return prev;` —— 响应 1845、`prev` 1845，长度匹配，回填正常 |
| **Q2-b** `warmCardValues` 把分页数组打回全量 | ✅ 不触发 | 返回全量、前端也全量，不冲突 |

---

## 回归确认清单（合并前逐条打勾，不是"应该没问题"）

| # | 确认项 | 判据 |
|---|---|---|
| R-1 | `saveDraft` 请求体未变 | F12 抓包，改动前后各存一次，两次 payload `lineItems.length` 均为 **1845**，且结构逐字段一致 |
| R-2 | 翻页零网络请求 | 连翻 5 页，`/api` 请求数 **== 0** |
| R-3 | `GET /quotations/{id}` 响应未变 | 改动前后各拉一次，响应体 **md5 相同** |
| R-4 | 导出 Excel / PDF 未变 | 同一张单改动前后各导一次，文件 **md5 相同** |
| R-5 | 提交 / 审批 / 核价审批链路未变 | 走完整流程，状态机流转与改动前一致 |
| R-6 | 复制报价单未变 | 复制出的单行数 == 1845 |
| R-7 | 价格调整策略归位未受影响 | `PriceReconciler` 日志无新增异常 |

---

## 二期触发条件（什么时候才需要改接口）

以下任一成立时，才需要回到服务端分页并重启接口契约设计：

1. **AC-19 达标但用户仍反馈慢** —— 说明瓶颈在 Step1 的 22.2 s（取数 + 解析 23 MB），前端分页够不着，需读侧分页
2. **需要解决首存超时 / 建单超时** —— 必须有 `partial` saveDraft 语义（行 id 白名单）才能分批提交
3. **单据规模再上一个量级**（如 5000+ 行）—— 101 MB 的数据常驻会随规模线性增长，逼近地板上限

> 二期一旦启动，**必须重新处理评审查出的 6 项 P0**，特别是 B-1 / B-2 这两条**既有缺陷**（它们现在被"全量提交"这个巧合掩盖着）。
> v1 方案的完整设计与评审结论存档于 `需求文档-v1-服务端分页-已撤回.md`，不要重新推导一遍。
