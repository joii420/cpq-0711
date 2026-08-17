# api — task-0725 修复报价单页签无法显示数据

> 本期**只有一处协议扩展**：`batch-expand` 的 task 增加侧别字段。
> 无新增端点、无路径变更、无响应结构变更、无 Flyway 迁移。
> 采纳方案 D' 后 service 层也无签名改动（详见 `需求说明.md §4.3`）。

---

## 1. 变更总览

| # | 端点 | 变更类型 | 兼容性 |
|---|------|---------|--------|
| 1 | `POST /api/cpq/components/batch-expand` | 请求体 `tasks[].usage` **新增可选字段** | ✅ 向后兼容（缺省 = `COSTING` = 保持现状行为） |

其余涉及的端点**均无协议变更**，仅内部行为修复（页签从空变有数据）：

| 端点 | 说明 |
|------|------|
| `POST /api/cpq/quotations/{id}/draft` | saveDraft，触发重新物化（验收用） |
| `POST /api/cpq/quotations/{id}/refresh-card-snapshot` | 刷新基础数据（P2 覆盖入口） |
| `GET /api/cpq/quotations/{id}` | 详情读取，返回已持久化卡片值 |
| `POST /api/cpq/components/{id}/refresh-template-snapshots` | snapshot 核对（排查辅助） |
| `GET /api/cpq/costing-bom-tree-config?usage=QUOTE\|COSTING` | 树配置核对。⚠️ **路径是单数** `costing-bom-tree-config`，task-0721 的 `api.md` 写复数是笔误 |

---

## 2. `POST /api/cpq/components/batch-expand`

### 2.1 为什么需要这个字段

后端 `ComponentResource` 在三处（`:261` phase1 / `:367` bucket-merge / `:407` runSingleTask）需要决定**是否为该 task 打开报价侧 pending 可见域**（`QuotePendingScope.open`）。但：

- 前端报价侧与核价侧**共用同一个 `useDriverExpansions` hook**
- 两侧传的是**同一个 `quotationId`**（`QuotationStep2.tsx:3362` 报价 / `:3364` 核价）

→ 后端拿不到任何可区分侧别的信号。若按 quotationId 判断，核价侧也会被开启 pending 改写，**100% 破 AC-17 核价零回归**（详见 `需求说明.md §9` 风险表第 1 条）。

### 2.2 请求体（变更部分）

`BatchExpandDriverRequest.Task`（`BatchExpandDriverRequest.java:18-78`）新增：

| 字段 | 类型 | 必填 | 缺省 | 取值 | 含义 |
|------|------|------|------|------|------|
| `usage` | string | **否** | `"COSTING"` | `"QUOTE"` / `"COSTING"` | 该 task 所属业务侧。`QUOTE` = 报价侧渲染，允许看见本单 pending 数据；`COSTING` = 核价侧渲染，**不**开启 pending 可见域 |

**缺省值刻意选 `COSTING`（保守兜底）**：老前端不传该字段时行为与修复前逐字相同，不会意外让核价侧或未知调用方获得 pending 可见性。

**非法值处理**：非 `QUOTE` / `COSTING` 的值一律**按 `COSTING` 兜底**（不抛错），与列默认值语义一致，避免因前端传错值导致整批 expand 失败。

### 2.3 请求示例

```json
{
  "customerId": "32aea5b1-d003-4232-a90a-cdc5fab0520d",
  "tasks": [
    {
      "usage": "QUOTE",
      "componentId": "edfa54ff-b71d-497a-b262-e3ffc5a92742",
      "partNo": "S-3120014539",
      "lineItemId": "6ad49abc-7b9f-4de2-a993-5c7d22e30aba",
      "compositeType": "SIMPLE"
    },
    {
      "usage": "COSTING",
      "componentId": "edfa54ff-b71d-497a-b262-e3ffc5a92742",
      "partNo": "S-3120014539",
      "lineItemId": "6ad49abc-7b9f-4de2-a993-5c7d22e30aba",
      "compositeType": "SIMPLE"
    }
  ]
}
```

> ⚠️ `customerId` 是 **per-task 字段**，不在顶层（顶层只有 `tasks` + `debugSql`）。上例为突出 `usage` 差异而省略了其余字段，实际请求以 `BatchExpandDriverRequest.Task`（`:18-78`）的字段定义为准。

> 注意这两个 task 的其余字段**完全相同** —— 这正是必须有 `usage` 的原因，也是三处必须补 pending 维度的原因（否则 key 逐字相同互相污染）：
> ① `ComponentDriverService.cacheKey`（`:365-366`）
> ② `DataLoader.resultCache` 三个重载（`:90` / `:104` / `:189`）—— 第二层缓存，评审 BLOCKER
> ③ `ComponentResource` 的 **bucketKey**（`:318-331`）—— 见 §3 不变式 #4
> 详见 `backtask.md` T2 与 T3-P3。

### 2.4 响应

**结构不变**。同一批次内不同 `usage` 的 task 各自独立求值，结果按 **task index** 配对返回（沿用现有约定 —— AP-37 明确要求配对用 task index 而非 backend `r.key`）。

`usage=QUOTE` 的 task 结果中，driverRow 会**额外带 `__v6_id` 锚点列**（由 `QuotePendingRewriter` 注入，供 B5 回填定位行）；`usage=COSTING` 的**不带**。这是两侧结果的可观测差异，也是 T4 断言核价零回归的观测量。

### 2.5 权限

不变，沿用 `ComponentResource` 现有 `@RoleAllowed`。

---

## 3. 不变式（实现须遵守）

| 不变式 | 说明 |
|--------|------|
| `usage` 缺省 / 非法 ⟹ 不开作用域 | 保证老客户端与未知调用方零行为变化 |
| `usage=QUOTE` 且报价单**冻结**（`SUBMITTED`/`APPROVED`/`PUBLISHED`）⟹ 仍不开作用域 | 冻结判定内建在 `QuotePendingScope.open()` 里，不由调用方判断（AC-10 由构造保证） |
| `usage=COSTING` ⟹ 结果与修复前**逐位相同** | 含缓存 key 逐字不变（`cacheTag()` 关闭态返 `""`） |
| 同批次混合 `usage` ⟹ 各 task 独立，互不影响 | 作用域按 task 粒度 open/restore，严格 try/finally。🔴 **仅靠这一条不够** —— 见下方 #4 |
| **#4 `usage` 必须纳入 bucketKey，报价/核价永不合并** | `ComponentResource:318-331` 现有 bucketKey = `componentId \| customerId \| partVersion \| dp \| fieldsTag \| q=quotationId [\| li=lineItemId]`，**无侧别维度**。`canMerge`（`:342-343`）成立时整桶只跑一次 `expandMulti`、作用域只能按 `pivot`（`:336`）开一次 → 上面 §2.3 那个「两 task 只差 usage」的示例会被**合并成一次**，其中一侧必然拿到错误的可见性。**需求方决策（2026-07-25）：把侧别纳入 bucketKey**（改动约一行），混合批次因此可正确工作，§2.3 示例保持有效 |

---

## 4. 联调自检

```bash
# 后端存活（/q/health 返 404 不是探针，看业务端点 401）
curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:8081/api/cpq/components   # 期望 401

# 登录取会话
curl -s --noproxy '*' -c jar.txt -X POST http://localhost:8081/api/cpq/auth/login \
  -H 'Content-Type: application/json' -d '{"username":"admin","password":"Admin@2026"}'

# 树配置在位（BOM 页签与「材料成本 2 行」验收的前置）
curl -s --noproxy '*' -b jar.txt 'http://localhost:8081/api/cpq/costing-bom-tree-config?usage=QUOTE'
curl -s --noproxy '*' -b jar.txt 'http://localhost:8081/api/cpq/costing-bom-tree-config?usage=COSTING'
# 期望各返 1 条 isActive=true

# 触发重新物化（验收主入口）—— ⚠️ 必须走 refresh-snapshot，不是 saveDraft
curl -s --noproxy '*' -b jar.txt -X POST \
  http://localhost:8081/api/cpq/configure-product/quotations/c670e9e7-5f7c-4b72-9a27-965447fcf75b/refresh-snapshot
# 期望 200；随后查 quote_card_values 应有数据
```

> ⚠️ **端点路径易猜错（2026-07-25 实测踩过）**：重算端点的类级 `@Path` 是 **`/api/cpq/configure-product`**（`ConfigureProductResource:31`），不是 `/api/cpq/configure` —— `:26` 有注释说明 2026-05-18 hotfix 把类级路径从 `/api/cpq/quotations` 改成了 `/api/cpq/configure-product`。写成 `/api/cpq/configure/...` 会返 **404**。完整正确路径：
> `POST /api/cpq/configure-product/quotations/{quotationId}/refresh-snapshot`（实测返 200）

> 🔴 **不要用 `POST /api/cpq/quotations/{id}/draft`（saveDraft）验收**：它调 `snapshotQuotation(id, **true**)` 走**增量**，而 `ConfigureSnapshotService.lineNeedsExpand:148-156` **只判 `sr == null`** —— 上次失败物化写下的 `snapshot_rows = []` 是非 null → 整行被判「已完整」跳过 → `anyNeedsExpand=false` → 连树都不渲染。**页签仍是空的，会被误判为「修复无效」。**
> 正确入口 `POST /api/cpq/configure-product/quotations/{id}/refresh-snapshot`（`ConfigureProductResource:95` → 1-arg `snapshotQuotation(qid)`，`:125-127` 确认 `skip=false` 全量重 expand），对应前端 DRAFT 态常驻的「刷新基础数据」按钮（`QuotationStep2.tsx:3401`），**一次点击即可，无需重新编辑字段**。

> ⚠️ 环境重建后 `costing_bom_tree_config` 会空（该表**全库无 INSERT 迁移**，配置天生只活在 DB）。两条配置的 SQL 原文见 `docs/RECORD.md` 2026-07-25 条目所指的备份路径。
