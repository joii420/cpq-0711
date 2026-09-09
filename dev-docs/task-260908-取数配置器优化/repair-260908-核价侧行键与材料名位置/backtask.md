# 后端任务分解 · repair-260908

> 本次**全部改动都在后端**：1 处 Java + 1 个 Flyway 迁移。
> AC 原文在 `问题说明.md §⑥`，**任务分解是分解结果，AC 原文才是验收标准**，两者有出入以 AC 原文为准并报主线。

---

## B-1 · 材料名插到料号列之后（服务 AC-R1 / AC-R2 / AC-R3）

**文件**：`cpq-backend/src/main/java/com/cpq/builder/compiler/FieldTreeBuilder.java:387-389`

**现状**：
```java
if (isMain) {
    fields.addAll(syntheticLookupFields(snap, dialect, anchor, nodesWithOwnGroup));
}
```
`addAll` 追加到 `fields` 末尾，而此时 `fields` 已装完锚点全部物理列 ⇒ 材料名必然排最后。

**改成**：插到**最后一个带 `PART_NO` 角色的列之后**。
- 锚点组内**有** `PART_NO` 列 → 插在它后面（`fields.addAll(idx + 1, lookups)`）
- **没有** → 维持追加到末尾（**行为不变**，`AC-R3` 钉这条）

### 🚫 三条不许动

1. **不许改 `syntheticLookupFields` 自身**（返回内容与顺序）—— 它的 `coalesceGroup` 去重（同组只出现一次、用 `fallback_order` 最小的节点代表）与 `AC-7`「不另起分组」都依赖现状。**本次只改插入位置**。
2. **不许改 `nodesWithOwnGroup` 的判定** —— 那是 `B-49` 修过的「桥既被内联又自成一组」缺陷的防线。
3. **不许改 PRICE 组的处理**（`:394` 起那段）—— 价格策略组单独成组返回，与本次无关；`task-260904 B-23` 刚修过「返回两个价格策略组」，别碰。

### ⚠️ 判 `PART_NO` 用角色，不要用列名

用 `roles.contains("PART_NO")` 判定，🚫 **不要**按 `display_name` 里有没有「料号」二字判 —— 实测存在「方案编号」这类带 `PART_NO` 角色但名字不含「料号」的列，也存在名字含「料号」但无该角色的列。

---

## B-2 · 核价侧行键修正（服务 AC-R4 ~ AC-R8）

一个 Flyway 迁移，**只 `UPDATE semantic_node_column.roles`**，七类改动：

| dialect | node_key | 动作 | 目标行键（改后） |
|---|---|---|---|
| COST_BASIC + COST_DETAIL | `MATERIAL_BOM` | **去掉**「工序编号」「使用特性」的 `ROW_KEY` | 生产料号｜组成料号 |
| COST_BASIC + COST_DETAIL | `FINISHED_RATIO_FEE` | **加**「要素名称」 | 生产料号｜要素名称 |
| **COST_DETAIL only** | `FINISHED_FIXED_FEE` | **加**「要素名称」（`COST_BASIC` 已对，别重复加） | 生产料号｜要素名称 |
| COST_BASIC + COST_DETAIL | `INCOMING_OTHER_FEE` | **加**「要素名称」 | 生产料号｜来料料号｜要素名称 |
| COST_BASIC + COST_DETAIL | `INCOMING_OTHER_FIXED_FEE` | **加**「要素名称」 | 生产料号｜来料料号｜要素名称 |
| COST_DETAIL | `PLATING_COST` | **去掉**「电镀方案编号」「版本编号」 | 生产料号 |
| COST_DETAIL | `TOOLING` | **去掉**「模具台账/工装编号」 | 生产料号｜工序编号 |

### 🚫 四条硬约束（每条都有具体后果）

1. **`WHERE` 必须同时限定 `dialect IN ('COST_BASIC','COST_DETAIL')` 和 `node_key`**
   ⇒ 漏了 dialect 会连报价侧一起改，`AC-R5` 会红。
2. **只增删 `ROW_KEY` 一个角色，用数组增删而非整体覆盖**
   推荐 `array_remove(roles,'ROW_KEY')` / `roles || '{ROW_KEY}'`，🚫 不要 `roles = '{PART_NO,ROW_KEY}'` 这种整体赋值 ——
   ⇒ 会把同一行的 `PART_NO` / `SORT` 一起冲掉，`AC-R6` 会红。
3. **🚫 不动 `component.row_key_fields`**（裁决 `A0-3`）⇒ `AC-R7` 钉这条。
4. **幂等**：加角色前先判 `NOT ('ROW_KEY' = ANY(roles))`，删之前先判存在。本迁移可能在多个 worktree 的 `mvnw test` 中被重放。

### 🚨 迁移号：四方核对，且落库后当场报主线

2026-09-08 实测：共享库 **432** / master **432** / `target/classes` **432**（本轮已补齐）⇒ 拟用 **V433**。

**写文件前必须重新四方核对**（共享库 `flyway_schema_history` × master 的 `db/migration` × 当前分支的 `db/migration` × `target/classes/db/migration`，第四方要**双向**看）。
🚨 **迁移一落共享库，立刻在回报里单独说一句「已落共享库 V4xx」** —— 这条规则最近三天落空过三次，最近一次就是本任务族的 `V430`，打挂了并发会话的实例。

---

## B-3 · 后端自检（交付前必跑，输出贴进回报）

1. **迁移生效**：`select version, success from flyway_schema_history where version='433'` → `success = t`
2. **AC-R4 正表**：跑 `问题说明.md §②` 的 SQL，**七行逐项与目标一致**
3. **AC-R5 反向**（报价侧零变化）：迁移**前后**各采一次报价方言行键快照，`diff` 完全一致
   🚨 **先证明量具会动**：故意改一个核价侧角色 → 采样 → 确认 diff 有输出 → 再回滚 → 才拿它当证据。
   🚫 不许比较两个空文件就说"相同"（本任务族出过这个假绿）。
4. **AC-R6 反向**（其他角色未误伤）：迁移前后 `PART_NO` / `SORT` 分布 md5 相同
5. **AC-R7 反向**（存量不动）：迁移前后 `component.row_key_fields` md5 相同
6. **B-1 的编译产物验证**：用 `GET /api/cpq/config/semantic-graph/field-tree` 取任一有 `PART_NO` 列的数据源，
   确认返回的 `groups[].fields[]` 里**材料名紧跟在那个 `PART_NO` 列之后**（看数组下标，不是看有没有）
7. **N+1**：本次不新增查询路径，不适用

## 🚫 越界纪律

- 只做 B-1 / B-2。🚫 不许改前端任何文件
- 发现别人的代码有 bug → **只报根因 + 修法，不动那个文件**
- 🚨 遇 `CLAUDE.md §3.2` 红线（`DROP`/`TRUNCATE`/无 `WHERE` 的 `DELETE`/`UPDATE`/`rm -rf`/`git reset --hard`）→ **停下来报主线，你没有批准权**。本次是纯 `UPDATE` 且 `WHERE` 精确，正常不该遇到
- 🚫 **不许改 `V430`**（已冻结，落过共享库）—— 需要调整语义图一律新开迁移
