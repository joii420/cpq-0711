# repair-0814（根因修复）· 验收报告

- **执行时间**：2026-08-14（本地 PDT）
- **分支**：`fix/repair-0814-rowkey-fieldname`（worktree `/tmp/claude-1000/wt-rowkey-fieldname`）→ 已合 `master` `33deb4a7`，worktree 与分支已删除
- **环境**：worktree 验证用临时 vite `:5199`（主工作区 `:5174` 服务的是已合并代码，用于合并后复验）；后端 `:8081`；库 `cpq_db_0724`
- **执行人**：主线亲执行 + 亲验，未派子代理
- **对应**：`问题说明.md` §⑥ AC-1~AC-11，`BL-0170`

---

## 1. 改动清单

| 文件 | 改动 |
|---|---|
| `FieldConfigTable.tsx` | 行键列 `checked` / `onToggleRowKey` 统一按 `record.name`（字段名）；`eligible` 判据不动；`resolvedColumn` 降级为 tooltip 展示；prop 注释与签名同步（`resolvedColumn` → `fieldName`） |
| `ComponentManagement.tsx` | `computeFinalRowKeyFields`：剔除「不是字段名、但正是某字段 `resolvedColumn`」的历史污染项；真锚定项（无字段代表且非任何 driver 列）仍保留；`onToggleRowKey` 回调参数改名 |
| `ComponentService.java` | 更正 `:907-909` 过期注释（V279 时代的「driverRow 底层列」说法）；新增「混用两套命名空间」软告警（`LOG.warn`，不阻断） |
| `e2e/rowkey-fieldname-contract.spec.ts` | 新增四段契约回归 |

**未触碰**（`git diff` 为证）：`computeRowKey` / `comparable` / `buildMatch` / 任何求值逻辑（AC-7）。

---

## 2. AC 逐条结果

| AC | 判定 | 证据 |
|---|---|---|
| **AC-1 写入即字段名** | ✅ | S3：勾选「生产料号」→ 落库 `["销售料号","料号","生产料号"]`，写的是**字段名**而非 `production_no`；非字段名项 = `[]` |
| **AC-2 勾选态正确** | ✅ | S2：`勾选态 = ["销售料号","料号"]`，与 `rowKeyFields` 完全一致（**旧实现为 `[]`**）；反向断言「不在行键里的字段必须未勾选」同时通过 |
| **AC-3 取消可生效** | ✅ | S3：取消「生产料号」→ 落库回到 `["销售料号","料号"]`（旧实现中文项会被当锚定列永久保留） |
| **AC-4 eligible 不放宽** | ✅ | 代码未动 `eligible = !!cand?.eligible`；spec 的 `toggleRowKey` 遇 `disabled` 直接判失败，S3 选取的候选也限定 `!r.disabled` |
| **AC-5 阴性不回归** | ✅ | S1：打开不改任何东西点保存 → PUT 请求体 `rowKeyFields = ["销售料号","料号"]`，与加载值逐字一致，落库不变 |
| **AC-6 存量列名可清除** | ✅ | S4：用 API 写入 `["销售料号","料号","parent_no","material_no"]` → UI 打开保存 → 自动清成 `["销售料号","料号"]`，字段名项一个不少 |
| **AC-7 求值零变化** | ✅ | `git diff --stat` 仅 3 个源文件 + 1 个新 spec；求值链路文件零改动 |
| **AC-8 全库回归** | ✅ | 见 §4 说明（唯一一条命中是用户删字段所致，非本次污染） |
| **AC-9 前端自检** | ✅ | `npx tsc --noEmit` **0 错误**；`FieldConfigTable.tsx` / `ComponentManagement.tsx` 经临时 vite(5199) 与合并后 5174 均 **200** |
| **AC-10 后端自检** | ✅ | worktree `mvnw compile` 通过；合并后 `touch` 触发 Quarkus 重启 → `GET /api/cpq/components` **401**（应用在跑、鉴权正常）；S4 证明软告警**不阻断**写入 |
| **AC-11 BACKLOG/索引** | ✅ | `BL-0170` → 已完成；`INDEX.md` 挂本 repair 并标状态 |

---

## 3. 还原实验（证明回归 spec 有鉴别力）

```
git stash push FieldConfigTable.tsx ComponentManagement.tsx   # 仅还原本次前端修复
→ 重跑 spec：[RK][S2] 勾选态 = []
→ 失败信息：Error: 字段「销售料号」在行键里却显示未勾选
git stash pop → 重跑 → 四段全绿
```

故障态**精确复现**（勾选态从 `["销售料号","料号"]` 退回 `[]`），证明 spec 不是空验证。

## 3.1 两次运行的完整输出

**worktree 环境（:5199，修复代码）**
```
[RK] 临时组件 COMP-0289 物料BOM；导入后 rowKeyFields=["销售料号","料号"]
[RK][S1] PUT payload rowKeyFields = ["销售料号","料号"]
[RK][S2] 勾选态 = ["销售料号","料号"]
[RK][S3] 勾选「生产料号」后 = ["销售料号","料号","生产料号"]
[RK][S3] 取消「生产料号」后 = ["销售料号","料号"]
[RK][S4] 污染 ["销售料号","料号","parent_no","material_no"] → 保存后 ["销售料号","料号"]
1 passed
```

**合并后主工作区（:5174，用户真机环境）**
```
[RK] 临时组件 COMP-0297 物料BOM；导入后 rowKeyFields=["销售料号","料号"]
[RK][S1] PUT payload rowKeyFields = ["销售料号","料号"]
[RK][S2] 勾选态 = ["销售料号","料号"]
[RK][S3] 勾选「生产料号」后 = ["销售料号","料号","生产料号"]
[RK][S3] 取消「生产料号」后 = ["销售料号","料号"]
[RK][S4] 污染 [...] → 保存后 ["销售料号","料号"]
1 passed
```

两次结果逐行一致；spec 全程使用独立临时目录 + 临时组件，跑完即清理（`afterAll` 删组件 + 删目录）。

---

## 4. 全库口径复核（AC-8）与一处需你确认的遗留

修复后扫描：**139 个有行键的组件中，1 个命中「含非字段名 key」**——但它**不是**本次污染，也不是修复引入：

```
COMP-0241 物料BOM（目录：核价模板2）  row_key_fields = ["销售料号","料号"]
updated_at = 2026-08-14 09:03:27   ← 晚于姊妹任务的数据清理（08:39）
当前字段列表 = 项次 | 料号 | 品名 | 规格 | 尺寸 | 工序编号 | … （共 17 个）
```

原因：**该组件的「销售料号」「生产料号」两个字段在 09:03 被删除了**（从 19 个字段变为 17 个），而行键里仍留着「销售料号」。按新逻辑它属于「无字段代表的真锚定项」，会被**保留**（这是防误删的设计意图，AC-6 后半）。

后果：`销售料号` 这一段在 `computeRowKey` 里恒解析为空 ⇒ 该组件的实际行键退化成只有「料号」。BOM 页签下同一子件挂不同父件时会**撞键**（`uniquifyRowKeys` 会加 `#序号` 兜底，但那是位置依赖的弱消歧）。

**待你确认**：这两个字段是有意删除的吗？
- 若是有意删除 → 行键应改为 `["料号"]`（或补别的区分维度，如加「项次」）；
- 若是误删 → 把字段加回来即可，行键无需动。

未擅自处理。

---

## 5. 强制自检声明

> 前端：`npx tsc --noEmit -p tsconfig.json` **0 错误** ✅；`FieldConfigTable.tsx` / `ComponentManagement.tsx` → 临时 vite(5199) 200 ✅、合并后 5174 200 ✅
> 后端：worktree `./mvnw -o compile` 通过 ✅；合并后 `touch` 重启 → `/api/cpq/components` **401** ✅；无 Flyway 迁移
> E2E：`rowkey-fieldname-contract.spec.ts` **1 passed**（worktree + 合并后各一次），含**还原实验**反证 ✅
> N+1 自检：后端改动仅在既有校验方法内做一次内存遍历（`keys` 数组），**无查库、无循环 SQL** ✅
> 数据：全库「含非字段名 key」= 1（用户删字段所致，已在 §4 说明并待确认），非本次引入 ✅
> 清理：worktree 已 `git worktree remove`，分支已 `git branch -d`，临时探针 spec 已删除，E2E 临时目录/组件已由 `afterAll` 清理 ✅

---

## 6. 与姊妹任务的关系

| 任务 | 职责 | 状态 |
|---|---|---|
| `repair-0814-行键中英口径混存致连表可比判定失真`（`BL-0169`） | **数据清理** —— 洗干净已被污染的 7 个组件（`COMP-0232~0234` / `COMP-0241~0244`） | ✅ 已交付 |
| `repair-0814-行键复选框写列名致口径污染`（`BL-0170`，本任务） | **根因修复** —— 让污染不再产生，并让存量残留在下次保存时自愈 | ✅ 已交付 |

两条合起来闭环：既有的脏数据已清，新的不会再生，且历史遗留在编辑时会被自然清除。
