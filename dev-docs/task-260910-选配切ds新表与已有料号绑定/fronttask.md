# task-260910 · 前端任务分解

> 🚫 **只标 AC 编号，不复制 AC 原文** —— 原文在 `需求文档.md §③`，**开工前必读那一节**。
> 🚫 接口契约看 `api.md`，**不要凭本文件猜签名**；契约有出入**停下来报主线**，不许自行改契约。
> 🎨 **视觉基准 = `原型图/`**（`frontend.md §1.3`）：F-3 的第三张类型卡与 F-2 的多材质展示**以原型图为准**，实现后要逐屏比对。

---

## 任务分解

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **F-1** | AC-6, AC-7 | **两个候选端点调用透传 `customerNo`**（契约见 `api.md §2.1 / §2.2`）：<br>① `configureProductService.searchParts(q, size)` → 加 `customerNo` 参数；调用点 `ExistingPartPanel.tsx:78`；<br>② `configureProductService.listOutsourcedParts({keyword,page,size})` → 加 `customerNo`；调用点 `OutsourcedPartPanel.tsx:66`。<br>📌 **客户号已在手**：`ConfigureProductDrawer` 有 `customerNo` prop（`:54` 注释「客户编码（`customer.code`）」），需往下透传到 `AddPartSubDrawer` → 两个 Panel。<br>⚠️ `customerNo` 为 `undefined` 时**不要发请求**（后端会 400），渲染成「请先选择客户」的空态 |
| **F-2** | AC-8 | **多材质展示** —— `SearchPartResult` 的材质字段由单值改 `materials[]`（`api.md §2.1`）。`ExistingPartPanel` 表格的材质列改为展示 N 个材质。<br>🚦 **展示形态以原型图为准**（`api.md` 的 R-2）：数组渲染成多个 tag，还是 `A / B` 单串 —— 原型定稿后实现，🚫 不要自己先定。<br>⚠️ **空数组是正常状态**：外购件与组合父料号没有材质行（AC-9），渲染成 `—` 而不是「加载中…」（AP-31 族） |
| **F-3** | AC-18, AC-20 | 🆕 **第三张类型卡「直接绑定已有销售料号」** —— `AddPartSubDrawer` 的类型选择步骤现有「零件 / 外购件」两张卡，加第三张。<br>选中后进入一个**料号搜索面板**（可复用 `ExistingPartPanel` 的搜索交互，但**选中即完成**，不采集材质/工序）。<br>**放行判据改造**（`ConfigureProductDrawer.tsx:204`）：现状 `parts.length === 0 ? '请至少添加一个配件' : null` 会把这条路拦住 ⇒ 改为「**已选绑定料号 或 已加配件**」二者之一即放行（AC-20）。<br>提交时发 `bindExistingMaterialNo`（`api.md §2.3`），且 `parts` 传空数组。<br>🚫 **两者互斥**：选了绑定就不允许再加配件（UI 层禁用「+ 添加配件」并给 tooltip 说明原因，`frontend.md §1.2`「禁用但可见 + 写明原因」） |
| **F-4** | AC-19 | **绑定路径的错误提示** —— 409 `CUSTOMER_PRODUCT_NO_TAKEN` 复用第 1 步既有的「该编号已存在，请从产品库添加」+ 跳转入口；新增 400 `BIND_MATERIAL_NOT_FOUND` / `BIND_AND_PARTS_EXCLUSIVE` 的提示文案。<br>🚫 **沿用既有的错误码解析规则**（`ConfigureProductDrawer.tsx:259`）：只把「非纯数字的字符串」当业务码 —— 现网信封的 `code` 有时装 HTTP 状态码数字，退回去会显示「错误码 400」这种无意义文案 |
| **F-5** | AC-11 | **卡片渲染回归确认（零代码改动的确认清单）** —— 后端 S-4 改的是**冻结期取数**，前端渲染期走「快照模式」（`QuotationStep2.tsx:237/2051/2094`「渲染期不再调 `/batch-expand`」）⇒ **前端不需要改代码**。<br>但必须**确认**：① 「物料BOM」「物料与元素BOM」两页签渲染非空；② 「材料占比（%）」列显示 `100`（或 AC-14 的 `70`/`30`）；③ 「自制加工费」页签**从空变为有数据**（这是 S-1 的前端可观测出口）。<br>📌 **零改动的判定依据**：`snapshot_rows` 有值时前端不查库，patch 合并发生在后端冻结期 ⇒ 前端看到的仍是一份普通快照 |

---

## 🚫 明确不改（判定依据 + 回归确认清单）

| 不改的 | 为什么不改 | 回归怎么确认 |
|---|---|---|
| `QuotationStep2.tsx` | 渲染期走快照模式、不查库（见 F-5）。S-4 的 patch 合并全在后端冻结期 | F-5 的三条确认 |
| `useDriverExpansions.ts` | `quotationId` **已经在传**（`:164` 注释「透传到 batchExpand task，后端绑成 `:quotationId`」），本任务不需要新增透传 | 结构确认：该文件 0 改动 |
| `ConfirmStep.tsx` 的换序提示 | AC-22 的数据源从 `unit_price` 切到 `ds_quote_self_process_fee` 是**后端内部**变化，响应契约里 `snapshot.processes` 形状不变 | AC-22 回归：换序仍显示 `（Z100 → Z101）` |
| `MaterialPicker.tsx` / `NewPartPanel.tsx` / `ProcessSection.tsx` | 材质与工序候选**数据源不变**（`material_recipe` / `process_master` 是主数据，无 `ds_` 对应物 —— 若改读 `ds_quote_*` 结果表，材质候选会从 263 掉到 18） | 结构确认：这三个文件 0 改动 |
| `CompositeProcessStep.tsx` | 组合工序候选仍读 `process_master`（`process_category IN ('ASSEMBLY','组装')`）；只有**落库表**从 `capacity` 换成 `ds_quote_assembly_fee`，前端无感 | AC-2 后端断言 |
| 外购件候选的分页 | 本期明确不做（`需求文档.md §2.2`）。后端有分页能力但前端 `page` 恒 1 / `pagination={false}`，现被数据量掩盖（实测 5 条） | 不回归；已登记 BACKLOG |
| `AddProductModal.tsx`（「从已有产品添加」） | 它读的是**已有绑定**（`ExistingProductService` 只读 `ds_quote_customer_part`，全类无 INSERT），与 S-7 的「创建新绑定」是不同场景 | 结构确认：该文件 0 改动 |

---

## 强制自检（`frontend.md`）

开工后每一项完成即跑，**回报里必须带这三行的实际输出**：

```
1) TS：cd cpq-frontend && npx tsc --noEmit          → 期望 0 错误
2) 内容确证：/usr/bin/grep -an "<本次新增的关键标识>" <改过的文件>   → 能命中
3) 页面可达：临时端口起 vite（🚫 不要占 5174，那是主线亲验用的）
   curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:<临时端口>/   → 期望 200
```

⚠️ **共享 5174 服务的是主工作区代码，看不到 worktree 里的改动** —— 拿它验证自己的改动 = 假绿（`subagents.md §2f`）。
⚠️ 本环境 `grep` 是 `ugrep`，中文多的大文件会被静默判为二进制返空 ⇒ **一律 `/usr/bin/grep -a`**。

---

## 双向覆盖自检

**正向**：AC-6 → F-1 · AC-7 → F-1 · AC-8 → F-2 · AC-11 → F-5 · AC-18 → F-3 · AC-19 → F-4 · AC-20 → F-3。

⚠️ 其余 AC（AC-1~AC-5、AC-9、AC-10、AC-12~AC-17、AC-21~AC-23）**由后端 `B-x` 或测试侧覆盖，前端无任务** —— 见 `backtask.md` 的覆盖表。这不是缺口。

**反向**：F-1~F-5 全部已标 AC，无孤立项。
