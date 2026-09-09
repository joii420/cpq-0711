# B-8（AC-18）· editRows 行键失配的响亮失败 · 2026-09-08

## 一、现行行为的实测更正 —— 不是「新建一条零效果记录」，是**直接丢弃**

`backtask.md` B-8 描述为「匹配不上就新建一条 `editRows`、HTTP 200、零效果、不报错」。
**代码实测不是这样**（`CardSnapshotService#filterEditRowsToNewBaseRows`，改动前原文）：

```java
ArrayNode kept = MAPPER.createArrayNode();
for (JsonNode er : oldEdits) {
    if (newKeys.contains(er.path("rowKey").asText(""))) kept.add(er);
}
if (kept.size() > 0) filtered.put(cid, kept);
```

对不上的那一条**既不新建、也不保留 —— 直接从结果里消失**：不记日志、不计数、不报错、HTTP 200。
⇒ 后果比「新建零效果记录」**更重**：那种至少还留着一条可查的记录，这种是值直接没了。
**AC-18 要求的做法不变**（收集 + 报出），但闸门 B 汇报里这句描述建议按实测口径更正。

## 二、AC-18 的基线（只读，未执行任何刷新）

```
QT-20260908-0624：editRows 总数 196，其中带 #N 后缀 196 条（100%）
后缀分布： #0 → 96    #1 → 96    #2 → 2    #3 → 2
```

🚨 **`#2`/`#3` 各 2 条 = 存在撞键度 4 的行**，「恰好两个客户」解释不了它们
（可能是 2 客户 × 2 条重复行，或本来就有重复行）。

⚠️ **因此不要预设「失配数 = 196」**：纯跨客户造成的撞键消失后后缀才会消失；
撞键度 4 的那 4 条修好客户维度后**可能仍是撞键度 2**、仍带 `#0/#1`，于是**仍能匹配上**。
⇒ `AC-18` 的「条数与实测失配数一致」应当**以刷新时实际报出的数字为准**，再回头解释差额。

## 三、为什么本批次没有跑刷新验证

`POST /quotations/{id}/refresh-snapshot` 会**真的重建该单快照**，而当前行为正是「失配即丢弃」
⇒ 在 `QT-20260908-0624` 上跑一次，就会**把这 196 条用户编辑消耗掉**，
而它们正是 AC-18 唯一的实测样本。⇒ 本批次只交代码 + 基线，刷新由主线在批准后执行。

## 四、改完之后，同一动作会多出什么

- HTTP 响应（`ConfigureProductResponse`，加法式字段）：
  `editRowMismatchCount` / `editRowMismatches[]`（componentId + rowKey + 原值摘要）/ `editRowMismatchTruncated`
- 服务端日志两条：`[edit-rows 失配] comp=… 有 N 条 …被丢弃` + `[refresh-snapshot] quotationId=… 本次刷新有 N 条 …`
- 🚫 **一行去留都没改** —— 只观察不干预，保证「不带后缀的 editRows 照常匹配、值不变」这条反向要求。
