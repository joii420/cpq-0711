# fronttask · repair-260911 核价 Excel 视图取数源对不上

## 结论：本次**零前端改动**，前端不派工

### 为什么不改

缺陷完全在后端求值链内：

```
ExcelViewService.buildLineTreeRows
  → parseEffectiveRows(costingCardValues)  → CardEffectiveRows.parse   ← 键只登记了 cid:sortOrder（病灶）
  → buildRowData → CardDataProvider.subtotalOf(裸 tabKey)              ← 精确查 map，miss → null
  → TabJoinPlanEvaluator                                               → 列值 0
```

前端拿到的是后端已算好的 `costing_excel_values`，**它只负责渲染**。后端把值算对，前端不动一行即恢复正常。

- 无接口结构变化（返回体形状不变，只是 `col_*` 的值由 `"0"` 变成真实值）
- 无新增/删除列、无布局改动 ⇒ **不触发 `frontend.md §1.3` 的原型图要求**
- 无字段类型变动 ⇒ 不触发 AP-44

### 回归确认清单（不改，但要确认没被动到）

| 项 | 确认方式 |
|---|---|
| 核价 Excel 视图渲染 | AC-1 / AC-2 真机核对四列数值 |
| 报价侧 Excel 视图 | AC-3 —— 本单 4 行各 3 个非零单元格逐位不变 |
| 核价产品卡片视图 | 不受影响（读 `costing_card_values`，本次不动该数据）；抽验 `S0001` BOM 仍为 `00003`=233922.5 / `00081`=5204745 |

### 二期触发条件

若后续要让 Excel 列**按 BOM 节点取值**（而不是每行都显示同一个页签总计，见 `问题说明.md` ⑥「已知语义」），那是**配置语义变更**，会牵涉列表达式编辑器与 `filterByNodeId` 的语义 —— 届时才需要前端参与。本期不做。
