# backtask · repair-0814 详情页报价树多出「版本」列

## 结论：**后端零改动**

按 `dev-docs/任务平台规则.md` §3.3「宁可写细，不可留空槽」，此处写清**为什么不改**的判定依据。

## B0 判定依据

| # | 判据 | 证据 |
|---|---|---|
| B0-1 | 版本列是否由后端下发「要不要显示」的标志？ | **否**。后端只在 driver 展开行上给数据：树页签给 `__sys`（`nodeId` / `parentId` / `hfPartNo` / `bomVersion` / `isCycle` / `nodeType`），非树页签给 `$view` 输出列 `view_version`。**「出不出这一列」纯由前端按 side 判定** —— 编辑页 `cardSide === 'COSTING'`、只读页 `isCosting`。故本 BUG 的修复面完全在前端。 |
| B0-2 | 能否改成「报价侧后端不下发 `bomVersion`」来治？ | **不可以，且会造成更大破坏**。`__sys.bomVersion` 与 `nodeId`/`parentId` 同属一组 spine 系统列，由同一条树展开链路产出；报价侧同样依赖 `__sys.nodeId`/`parentId` 建树、依赖 `hfPartNo` 显示料号列、依赖 `nodeId` 做剪枝/删除（repair-0727 的行身份）。为了藏一列而改后端载荷 = 动 task-0721/repair-0727 的核心契约，收益为负。 |
| B0-3 | 是否触及取值 / 落库 / 快照？ | **否**。改动只影响 `<th>` / `<td>` 是否渲染，以及 `colSpan` 与 tfoot 占位格数。小计 / 合计 / 产品小计的入参（`columnSumsByComp`、`compSubtotals`、`sumTabColumns`、`computeProductSubtotal`）一行未动。 |
| B0-4 | 是否需要 Flyway 迁移？ | **否**。无 schema、无数据、无配置表变更。 |
| B0-5 | N+1 自检 | **不适用**（本次零后端改动，无新增循环、无 repository 调用）。 |

## B1 回归确认清单（后端侧，只看不改）

- [ ] `ComponentDriverService` 树展开返回的 `__sys` 字段集不变（`bomVersion` 仍下发，核价侧照常消费）。
- [ ] 核价侧版本切换端点（task-0713：`GET /part-version/...` / switch）无调用方变化 —— 报价侧本就不调它（`canSwitchTreeVersion` 含 `isCosting` 且需 `coid`，报价详情页不传 `coid`）。
- [ ] 报价单详情接口返回体不变（`quoteCardValues` / `quoteCardStructure` 结构未动）。

## B2 二期触发条件（什么情况下本文件才需要变成真的后端任务）

1. 若将来裁决改为「报价侧也要展示 BOM 版本」——那时才需要确认后端在报价侧 `__sys.bomVersion` 的取值口径（当前报价侧该值来源未经业务确认，只是随 spine 一起下发）。
2. 若做 `BL-0174`（抽共享系统列 helper）时决定把「系统列清单」下沉为后端契约（由后端声明每个页签出哪些系统列）—— 那是接口层改造，需另立 `api.md`。
