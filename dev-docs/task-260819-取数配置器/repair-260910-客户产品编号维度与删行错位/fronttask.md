# fronttask · repair-260910

> 前端只按本文做。与后端唯一的协调物是 `api.md`。
> AC 原文在 `问题说明.md ⑥`，本文**只标编号不复制**。
> 🚨 本任务是**协议级改动**，且要改的是**全仓最危险的文件** —— `QuotationStep2.tsx` 身上叠了 38 个任务的协议，
> 同时受 `AP-31 / AP-37 / AP-38 / AP-44 / AP-50 / AP-51 / AP-54` 约束。改它之前请连读：
> `task-0721树` · `task-删除行删错架构重构` · `repair-260727-报价单报价侧删除行BUG`（行身份四套口径）。

---

## 本次前端的全部工作 = 让行身份脱离「排序位置」

后端会在展开结果里新增顶层系统列 **`__row_uid`**（契约见 `api.md`），前端要做的是**让它成为消歧依据**，
取代现在按数组下标生成的 `#N`。

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **F-1** | AC-8, AC-9, AC-10, AC-11 | `useCardSnapshots.ts`：`buildUniqueRowKeys` 在 `baseRows[i].__row_uid` 非空时**用它消歧**，不再落到 `uniquifyRowKeys` 的按位置 `#N`。<br>⚠️ 加前缀/消歧的**顺序**必须与后端逐字节一致（现有注释 `:327-328` 写明「先拼 `nodeId::base`、再对拼接后的完整字符串消歧」）—— 顺序错了 `#N` 两侧算不出同一结果。<br>⚠️ `buildLegacyRowKeySets` 现有**三档**回退**原样保留**，新键作为**第四档、优先匹配**（AC-11 存量兼容） |
| **F-2** | AC-8, AC-9, AC-10 | `deletedRows.ts`：`rowFingerprint` 增加 `__row_uid` 维度，**与既有 `nodeId` 维度同款叠加**（后端 `DeletedRowKeys` 同步改，见 `backtask.md B-8`）。两侧口径必须逐字节等价 |
| **F-3** | AC-8, AC-9, AC-10, AC-13 | `QuotationStep2.tsx`：<br>① `buildSnapshotExpansions`（`:2109`）把 `__row_uid` 随 `__sys` 一起注入（与 `:2196-2199` 现有 `__nodeId/__parentId/__lvl` 同槽位）；<br>② `handleDeleteDriverRow` 传 `__row_uid`；<br>③ 手工行（`_origin:'manual'`）无 `__row_uid` ⇒ **保持现有 `row_index` 口径不变**（AC-13）。<br>⚠️ `AP-54` 仍然适用：渲染用过滤子集、写回用原集合时，写路径下标必须按对象引用映射回原下标（`activeComponentDataIndex` / `realRowIndex` 那套**不要动**） |
| **F-4** | AC-12 + 详情页回归 | `ReadonlyProductCard.tsx`（`:225`）**同步改造**。<br>🚨 **这一项不是可选的** —— `AP-50`（详情页/编辑页渲染层 single-source 反模式）就是「编辑页正常、详情页显示僵尸数据」，是本项目最常见的漏法；`change-protocol.md` 步骤 3 明确要求**列表 / 编辑 / 详情三处分别验证** |
| **F-5** | AC-9, AC-11 | 其余三个传播点（影响面分析实测命中，不许漏）：`enrichComponentData.ts` · `draftLineDiff.ts` · `services/quotationService.ts` |

---

## 🚫 前端明确不做

| 项 | 理由 |
|---|---|
| 改 `AP-54` 的下标映射机制（`activeComponentDataIndex` / `realRowIndex`） | 那是已修好的另一套问题，本次不碰。碰了就是超范围 |
| 改树页签（`__nodeId`）的删除交互 | AC-12 要求**行为不变** |
| 给页签加「显示重复行提示」之类的新 UI | 不在任何 AC 里 = 没人要的功能 |
| 页面布局 / 样式调整 | 本次无页面设计类改动 ⇒ **不需要 HTML 原型图**（`frontend.md §1.3` 的触发条件：新页面 / 新弹层 / 布局或交互改动，本次三者皆无，改的是行身份的内部口径，用户可见变化只有「行数对了」「删对行了」） |

---

## 自检要求（`CLAUDE.md §6.1`）

- `tsc` 0 错误（**worktree 里跑**；共享 5174 服务的是主仓不是 worktree）
- worktree 自检要点：软链 `node_modules` + 另起临时端口的 vite + **grep 内容确证**（不能只看端口 200）
- 单测：`rowKeyUniquify.test.ts` / `rowKeyParityQt0068.repair0805.test.ts` / `legacyRowKeyFallbackQt0068.repair0805.test.ts` / `deletedRows.test.ts` / `buildSnapshotExpansions.deletedRows.test.ts` / `readonlySnapshotRows.precision.test.tsx` 全绿，**贴计数与用例名**
- 探本机服务一律加 `--noproxy '*'`（shell 常设 `http_proxy=127.0.0.1:7890`，不加会走代理返 502）
