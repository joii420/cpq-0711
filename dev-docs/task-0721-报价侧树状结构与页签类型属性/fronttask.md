# 前端任务文档 — 报价侧树状结构与页签类型属性

> 需求：`需求说明.md`｜接口：`api.md`｜技术设计：`docs/superpowers/specs/2026-07-21-报价单BOM树状渲染-design.md`
> 日期：2026-07-21

---

## 0. 开工前必读

1. `docs/反模式.md` **AP-31 / AP-38**（「加载中…」族）、**AP-50**（详情页 single-source）、**AP-51**（行数纪律）、**AP-54**（过滤下标错配）
2. `docs/E2E测试方法.md` —— 本任务属协议级改动，E2E 不是可选项
3. `docs/列表操作规范.md` —— 禁用按钮**不得隐藏**，须 hover 给出原因

### 架构红线（违反即打回）

| # | 红线 |
|---|---|
| 1 | **前端不得自行实现类型判定与级联判定逻辑**。前端只做 UI 反馈，写操作以后端校验为准 |
| 2 | **AP-50**：`ProductCard`（编辑页）与 `ReadonlyProductCard`（详情页）**必须同步改**。漏一侧 = 详情页显示僵尸数据 |
| 3 | **AP-51**：行迭代一律按 `expansion.rowCount`，**禁止** `Math.max(expansion.rowCount, baseRows.length)` |
| 4 | **AP-54**：渲染用过滤子集、写回用原集合时，写路径下标必须按对象引用 / 稳定 ID 映射回原下标 |
| 5 | 禁用状态用 `enabledWhen` 返回 reason 字符串，**禁止** `if (...) return null` 隐藏按钮 |

---

## F1. 拆掉 COSTING 侧闸门 ★入口

**依赖**：后端 B3 完成（`snapshot_rows` 已带系统列）

**涉及**：

| 文件 | 位置 | 现状 |
|---|---|---|
| `pages/quotation/QuotationStep2.tsx` | `:2110` | `activeComponentBomTree = cardSide === 'COSTING' && ...` |
| `pages/quotation/ReadonlyProductCard.tsx` | `:664` | 同款 COSTING 条件 |

**要点**：

- 去掉 `cardSide === 'COSTING'` 判断，改为**纯数据驱动** —— 只要该组件 baseRows 含 `__sys.nodeId` 就走树渲染
- 这与原注释表达的设计意图一致：*"未勾选组件后端不发系统列 → 此处 false → 普通表渲染。数据驱动，无需额外 flag"*
- `buildSnapshotExpansions`（`QuotationStep2.tsx:1508`）的 `__sys` 提取**本就无侧别判断**，无需改动；但需**新增透传 `__nodeType`**

**注意**：`QuotationStep2.tsx:1468-1476` 的墓碑过滤 `side === 'QUOTE'` 条件**必须保留** —— 核价侧仍然不过滤（AP-41 隔离）。

**验收**：报价单树页签正确渲染树结构；核价侧渲染逐位不变

---

## F2. 页签类型属性配置

**依赖**：后端 B4

**涉及**：`pages/component/` 组件编辑表单

**要点**：

- 新增「页签类型」选择项，值域 5 类：`BOM` / `材质元素` / `零件` / `外购件` / `主件`
- 可空（存量组件无此属性）
- 与既有「核价 BOM 递归展开」开关**分列展示**，说明区分二者：
  - 页签类型 = 业务语义（这个页签的料号代表什么）
  - BOM 递归展开 = 渲染行为（是否按树展开）

**验收**：五个值域可选可存；组件详情正确回显

---

## F3. 树渲染增强

**依赖**：F1

**涉及**：`QuotationStep2.tsx`（ProductCard 树渲染段，约 `:2576` 起）、`ReadonlyProductCard.tsx`（约 `:664` 起）

### F3.1 系统列只读

| 系统列 | UI 表现 |
|---|---|
| `__nodeId` / `__parentId` / `__lvl` / `__nodeType` | **完全不可见**（内部用） |
| `__hfPartNo`（料号）/ `__parentNo`（父料号）/ `__bomVersion`（版本） | 显示为**只读固定列** |

### F3.2 行可编辑性

**所有行**的业务列凡可填即可编辑，**不因该节点是否拥有子节点而变化**。

> ⚠️ 曾有「非叶子行整行只读」的设计，**已于 2026-07-21 推翻**。不要实现它。

### F3.3 行内 `+`（加叶子入口）

- 每行前显示，与既有 `×` 删除按钮对应
- **置灰规则**：`__nodeType` 为 `材质` / `外购件` 时禁用，hover 显示原因（如"材质节点不可再添加下级"）
- **禁用不隐藏**（列表操作规范）

### F3.4 剪枝入口

- **区别于 `×`**，仅在节点主行显示，图标用剪枝语义，避免与行删除混淆
- 树布局复用现有 `treeTable.ts` 的 `layoutTreeRows` / `isTreeRowHidden` / `useTreeCollapse`，**不需要改这些文件**

> **已验证的复用点**：`treeTable.ts:25` 规则「同 id 多行 → 第一条声明者胜」使同节点多行天然工作 ——
> 后续行 parent 仍指向父节点，成为同 depth 兄弟行，折叠箭头与子树只挂第一行。无需改造。

**验收**：树层级缩进正确；折叠展开可用；`+` 置灰规则正确且 hover 有原因

---

## F4. 加叶子交互

**依赖**：F3、后端 B6

**要点**：

1. 点击行内 `+` → 弹出候选料号选择
2. **候选料号来自前端本地数据** —— 遍历 `item.componentData` 各页签已渲染行的料号，本地去重
   - ⚠️ **不要**调用 `pricing-basic-data/lookup` 或任何远程搜索端点（`api.md` §7）
3. 用户选中 → 调 `POST .../tree/add-leaf`
4. 后端返回整单 `quoteCardValues` → **直接回灌**，不要二次拉取

**错误处理**（后端返回的四类错误都要有可读提示）：

| HTTP | 提示 |
|---|---|
| 400 宿主为材质/外购件 | 理论上前端已置灰，若仍触发说明数据漂移，提示并刷新 |
| 400 料号命中主件页签 | "该料号是成品，不能作为子件挂入" |
| 400 零命中 | "该料号不在任何页签中，不是有效的报价产品" |
| 409 多页签冲突 | 展示 `data.conflictTabs`，提示先修正基础数据 |

**验收**：AC-3 / AC-4

---

## F5. 删除确认弹窗 ★核心交互

**依赖**：后端 B7

**要点**：

**所有删除操作（剪枝 `+` 行删除 `×`）都必须先调预览接口**，用返回结果渲染确认弹窗。**不允许静默级联**。

```
点击 × 或剪枝
  → POST .../tree/delete-preview
  → 弹窗展示三块内容：
       ① 将从树上移除的节点（含子孙料号、层级）
       ② 各页签将被级联删除的行（按页签分组）
       ③ 因仍有其他引用而【不删】的料号 + 理由      ← 必须展示
  → 用户确认 → POST .../tree/delete（带 previewToken）
  → 返回整单 quoteCardValues → 直接回灌
```

**第 ③ 块不能省**。DAG 重复子件场景下，用户剪掉一支后会疑惑"为什么材质页签的数据还在"，
`retainedParts` 就是回答这个问题的 —— 明确告诉他"该料号在树上还有 N 处引用，故保留"。

**组件选型**：按 `docs/列表操作规范.md`，危险动作二次确认走 **Modal** 并列出所选项。
若明细行数过多导致 Modal 内滚动体验差，可改用 `Drawer`（宽度 720），但**必须保留三块内容的完整展示**。

**409 处理**：`previewToken` 失效（树在预览后变化）→ 提示"数据已变化，请重新确认"并自动重新预览。

**验收**：AC-7d；DAG 场景弹窗中 `retainedParts` 非空且理由可读

---

## F6. 报价侧递归 SQL 配置管理

**依赖**：后端 B2

**涉及**：现有 `CostingBomTreeConfigTab`（核价树配置页）

**要点**：

- 增加 `usage` 维度切换（`QUOTE` / `COSTING`），两侧配置分列管理
- 创建 / 编辑时必须指定 `usage`
- **激活提示**：明确告知用户"激活仅影响当前 usage，不影响另一侧"

**验收**：QUOTE 侧可独立创建 / 激活配置；操作后核价侧 active 配置未变

---

## F7. E2E（强制，不是可选）

**依赖**：F1~F6

本任务改动 `QuotationStep2.tsx` / `ReadonlyProductCard.tsx`，属 `CLAUDE.md` 明列的协议级改动。

```powershell
cd cpq-frontend
Remove-Item e2e\screenshots\qf-*.png -ErrorAction SilentlyContinue
npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list
npx playwright test --config=e2e/playwright.config.ts e2e/composite-product-flow.spec.ts --reporter=list
```

**必须看到**：所有 test `passed`、`'加载中' final count = 0`、全 Tab `'加载中'=0`

> ⚠️ **已知环境缺口**：干净 master 上 `quotation-flow` 恒有 3 个失败（夹具单缺产品分类致 Step1 下一步禁用）。
> 判断是否为本次回归**必须做 A/B 同型对比**（在改动前的 master 上跑一遍对照），勿误归因。
> 见记忆 `task0712-update071501-category-axis`。

**新增 E2E 用例建议**：树渲染 + 加叶子 + 剪枝级联（含 DAG 场景 `3110520789`）

---

## F8. 强制自检（CLAUDE.md §修改后强制自检）

```bash
# 1. TS 类型检查
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json          # 必须 0 错误

# 2. 每个改动的 .tsx 都要过 Vite transform（tsc 覆盖不到解析阶段错误）
#    注意：本机 shell 常设 http_proxy，探本机服务必须加 --noproxy
curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' \
  http://localhost:5174/src/pages/quotation/QuotationStep2.tsx        # 期望 200
curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' \
  http://localhost:5174/src/pages/quotation/ReadonlyProductCard.tsx   # 期望 200

# 3. 主入口
curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:5174/   # 期望 200
```

**「完成」宣告必须附带一行「已自检」声明**，含 TS 结果 + 各改动文件的 Vite 状态码 + E2E 结果。没有这行 = 未完成。

---

## 任务依赖图

```
后端 B3 ──→ F1 ──→ F3 ──┬── F4（依赖后端 B6）
                        └── F5（依赖后端 B7）
后端 B4 ──→ F2
后端 B2 ──→ F6
F1~F6 ──→ F7 ──→ F8
```

**建议顺序**：F1 → F3 → F2 → F6 → F4 → F5 → F7 → F8
