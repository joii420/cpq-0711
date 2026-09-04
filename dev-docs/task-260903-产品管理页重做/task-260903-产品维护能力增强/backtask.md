# 后端任务分解 · 产品维护能力增强（子任务）

> 阅读者：`cpq-backend` 子代理。**只按本文件做**，不要读 `fronttask.md`。
> AC 原文在 `需求文档.md §④`，**本文只标编号不复制原文**（复制必漂移）。

---

## 0. 范围边界（先看完再动手）

**本任务后端只动两处，都在父任务自建的 `com.cpq.product.dataset` 包内。**

| 归谁 | 内容 |
|---|---|
| **本任务** | `customer-parts` 加过滤参数（B-1）· 新增候选来源端点（B-2） |
| 🚫 **`task-260902`** | `PUT parts/{axisValue}`（其 B-20）· 产品分类字段与 `GET parts` 新字段（其 B-16）· `production_no` 的 `COALESCE` 导入语义（其 B-19） |

🚫 **不要实现 `PUT parts/{axisValue}`** —— 那是对方的 B-20，用户 A0 已裁决走「由对方加通用端点」。你实现了就是第二条写入路径，正是本任务费力避免的事。
🚫 **不要动 `com.cpq.dataset` 包**（对方的），也不要改 `ds_quote_*` 表结构 / 加索引 / 写迁移。有需要报主线转对方。

---

## 1. 任务清单

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **B-1** | AC-1, AC-2, AC-3, AC-4, AC-14 | `GET /dataset/{dataset}/customer-parts` **新增可选 Query `customerNo`**。与既有 `keyword` **AND 叠加**；省略 = 不过滤；传不存在的值返回 `total:0` + 空 items（**不是 404**） |
| **B-2** | AC-5 | **新增** `GET /dataset/{dataset}/customer-parts/customers` —— 客户过滤器的候选来源 |

---

## 2. 逐项要点

### B-1 · `customer-parts` 加 `customerNo`

- 文件：`com.cpq.product.dataset.DatasetCustomerPartService` / `DatasetCustomerPartResource`
- 🚫 **不得改变** `columns` 下发内容与既有响应形状 —— **父任务的 AC-2（total=17、6 列顺序）必须继续成立**，这是回归底线
- `customerNo` 与 `keyword` 是 **AND**：先按客户过滤，再按关键字搜（AC-3 断言「选了 `CUST-0004` 再搜 `0028-2609000001` 得 1 行」）
- 分页仍是服务端，`page` **0-based**（与父任务一致）

### B-2 · 候选来源端点

```
GET /api/cpq/dataset/{dataset}/customer-parts/customers
200: { code:200, data: { items:[ { customerNo, customerName, count } ] } }
```

🚨 **本项的全部意义在这一条：候选必须 `SELECT DISTINCT customer_no FROM ds_quote_customer_part`，不是查 `customer` 表。**

> 父任务实证：`Q13CUST0617` 与 `C1` **未在 `customer` 表建档**（其客户名称在列表里显示 `—`）。
> 若候选从 `customer` 表取，**这两个客户的 3 行产品在页面上看得见、却永远筛不出来**。

- `customerName` 走 **LEFT JOIN `customer` ON `c.code = t.customer_no`**
  🚨 **两个已实证的坑**：`customer` 表**没有 `customer_no` 列**，键是 **`code`**；**必须 LEFT**，INNER 会把未建档的 3 行静默丢掉
- JOIN 不到时 `customerName` 回 `null`（前端渲染可辨识文案）
- `count` = 该客户的产品行数。**可省**，省了要在回报里说明，前端会相应不显示数量

---

## 3. 硬约束

1. ✅ **N+1 硬指标**（`backend.md`）：两个端点的 SQL 条数都必须与返回行数**无关**。`customerName` **JOIN 一次取回**，🚫 禁止逐行查 `customer`；B-2 是一条 `GROUP BY` 查询，不要先查客户再逐个 count
2. ✅ **分页服务端做**，禁止全量捞进内存
3. ✅ **注入面**：`customerNo` 走参数绑定，`keyword` 的 `%`/`_`/`\` 已在父任务做过转义 + `ESCAPE`，**沿用同一套，不要另写一份**
4. 🚫 **只读端点**：B-1/B-2 都不含任何 INSERT/UPDATE/DELETE
5. 🚨 遇 `CLAUDE.md §3.2` 红线（DROP / TRUNCATE / 清库 / 改已应用迁移 / `rm -rf` / `git reset --hard`）→ **停下报告，你没有批准权**

---

## 4. 回归确认（本任务改了父任务已交付的端点，必须验没打坏）

| # | 项 | 期望 |
|---|---|---|
| G-1 | 不传 `customerNo` 时 | `total` 仍为 **17**，6 列顺序不变（父任务 AC-2） |
| G-2 | `keyword=CUST-0004` 单独用 | 仍为 **11**（父任务 AC-14） |
| G-3 | `GET parts`（销售产品） | **零改动**，`total` 仍为 42 |
| G-4 | 抽屉相关 4 个端点 | **零改动** |
| G-5 | 后端存活 | 业务端点返 **401**（`/q/health` 返 404 是正常的，**它不是健康探针**） |

---

## 5. 自检口径

🚨 **共享 8081 跑的是 master 代码，看不到你 worktree 里的改动 —— 拿它验证 = 假绿。**

- 起服务用**临时端口**，用完即停；**8081 / 5174 保留给主线亲验**
- 构建与测试在 worktree 内执行（`mvnw` 在 `cpq-backend/` 不在仓库根）
- 🚨 `mvnw test` 的 `test` profile **实连共享开发库** ⇒ **禁止任何清库型测试**
- 探本机服务一律 `curl --noproxy '*'`（本机常设 http_proxy，不加会走代理返 502）
- 完成宣告必须带自检声明行（`CLAUDE.md §6.1`）
- 🚫 **「已自检」≠「亲验」**：亲验由主线做，不要代劳，也不要声称功能已验收
