# fronttask — 基础资料数值列扩至 12 位小数

> 对应 `需求文档.md`。
> **结论：前端不是零改动。** 有 1 个页面会因本任务出现 UI 回归，必须处理。

---

## 1. 判定过程（为什么先以为是零改动，实际不是）

立项初判是"前端零改动"，理由看起来充分：

| 初判理由 | 核实结果 |
|---|---|
| 显示位数不变（用户裁决维持 9 位） | ✅ 成立，`DISPLAY_SCALE` 不动 |
| 精度字段走 decimal string 传输，非 JS number | ✅ 成立（task-0810 FR-8） |
| `decimal.js` 精度设 80 位，承载 12 位无压力 | ✅ 成立（`precision.ts:22-27`） |
| 前端无硬编码小数位数 | ✅ 成立——全工程 grep `toFixed(6)` / `toFixed(4)` / `toFixed(8)` / `decimalPlaces(6)` / `decimalPlaces(4)` **零命中** |
| 前端不感知 DB 列 scale | ❌ **不成立** —— 见 §2 |

**最后一条是错的**，所以本文档不是"为什么不改"，而是"必须改哪里"。

---

## 2. 受影响页面：核价料号维护页（唯一一处）

**路径**：`cpq-frontend/src/pages/master-data/part-costing/`
**文件**：`EditableSheetTable.tsx`
**端点**：`GET /api/cpq/pricing-basic-data/parts/{materialNo}/sheets/{sheetKey}/rows`

### 2.1 传导链路

后端 `PricingMaintenanceService:322`（**读取路径**）：

```java
if (scale != null && val != null) val = scaledString(val, scale);
```

而 `scaledString`（`:841-844`）的注释写得很明白：

> DECIMAL 列序列化为定标字符串：按 DB 列 scale `setScale` + `toPlainString`（如 `"1.230000"`）；禁 double、禁科学计数（3E-6）。

它读的 `scale` 来自 `PricingSheetDef.decimalScales`，也就是 **`backtask.md` T4 要改的那 16 处 `.scale(col, N)`**。

**T4 一改，这个读取路径立刻跟着变**：响应从 `"1.230000"` 变成 `"1.230000000000"`。

前端拿到后**原样显示**，因为 `EditableSheetTable.tsx` 完全不做格式化：

```tsx
function displayText(v: unknown): string {
  if (v === null || v === undefined || v === '') return '—';
  if (typeof v === 'boolean') return v ? '是' : '否';
  return String(v);          // ← 直接 String()，不走 formatDisplayDecimal
}
```

DECIMAL / NUMBER 列的**编辑控件**同样直取（`:217-227`）：

```tsx
if (col.type === 'DECIMAL' || col.type === 'NUMBER') {
  return (
    <InputNumber size="small" controls={false} stringMode
      value={value === null || value === undefined ? null : (String(value) as any)}
      onChange={(v) => updateCell(rid, { [col.name]: v ?? undefined })} />
  );
}
```

> `stringMode` 用得对（精度安全，不经 JS number），但 value 是后端那串补零字符串。

### 2.2 回归后果

| 位置 | 现在 | T4 之后（不修前端） |
|---|---|---|
| 只读单元格 | `1.230000` | `1.230000000000` |
| 编辑输入框 | `1.230000` | `1.230000000000` |

两个问题：

1. **违反用户裁决**。用户明确选了"显示最多 9 位"，这里会变成 12 位。
2. **编辑体验劣化**。输入框里一串 12 位尾随零，用户要先删一堆零才能改数——这是维护页的**主要工作界面**。

---

## 3. 三个修法（请在闸门 A 裁决）

| # | 方案 | 改动面 | 优点 | 代价 |
|---|---|---|---|---|
| **F-A**（主线推荐） | 前端 `displayText` + DECIMAL 分支接 `formatDisplayDecimal`（去尾零、最多 9 位） | 前端 1 文件约 2 处 | 与全局显示口径统一；后端定标字符串语义不变（仍防科学计数） | 需确认去尾零后 `InputNumber stringMode` 回填正常 |
| **F-B** | 后端 `scaledString` 改为 `stripTrailingZeros().toPlainString()` | 后端 1 处 | 一处修好所有消费方 | ⚠️ **风险**：`stripTrailingZeros()` 对 `2200.000000` 会产出 `2.2E+3` 科学计数——这正是 [[BL-0126]] 记录过的坑（"只加第一项会从丢精度变成 2.2E+3"）。要改必须配 `toPlainString()` 并加回归测试 |
| **F-C** | 不修，接受 12 位尾随零 | 0 | 无 | 违反用户裁决 D-2，且编辑体验劣化。**不推荐** |

**主线建议 F-A**：改动最小、与 `precision.ts` 既有口径一致，且不碰后端那个有历史坑的序列化函数。

---

### 3.1 ⚠️ 实施期修正：F-A 改为 **F-A′（纯去尾零，不截位）**

**决策人**：技术总监（主线），2026-08-13 实施期
**触发**：前端工程师交付后指出——`formatDisplayDecimal` 截 9 位后，该值就是**受控输入框里用户下次编辑的起点**；用户一旦在格内做局部修改，`onChange` 拿到的是基于 9 位的文本，**task-0813 刚扩到 12 位的精度会被静默改回 9 位**。

**判断**：这是**用观感牺牲本任务的核心目标**，本末倒置。且进一步分析发现原方案定错了靶子：

| 值的形态 | `formatDisplayDecimal`（截 9 位） | `normalizeDecimalString`（纯去尾零） |
|---|---|---|
| 补零噪声 `1.230000000000` | `1.23` ✅ | `1.23` ✅ |
| 真实 12 位有效值 `91.768628123457` | `91.768628123` ❌ **丢了 3 位真数据** | `91.768628123457` ✅ |

**用户真正要消除的是「补零到 12 位」的噪声，不是有效位数。** 去尾零已完全解决噪声；截位则误伤真实数据。

**最终实现**：`displayText()` 与 DECIMAL/NUMBER 的 `InputNumber` value 均改用 `normalizeDecimalString`。

**实测验证**（用 worktree 内 `decimal.js` 实跑，非推理）：

```
"1.230000000000"        -> "1.23"
"91.768628000000"       -> "91.768628"
"91.768628123457"       -> "91.768628123457"   ← 真实有效数字完整保留
"0.000000000001"        -> "0.000000000001"    ← 极小值不转科学计数
"2200.000000"           -> "2200"              ← 不是 "2.2E+3"
"0.050000000000"        -> "0.05"
"-3.140000000000"       -> "-3.14"
"0.000000"              -> "0"
```

后两条顺带避开了 [[BL-0126]] 记录的 `stripTrailingZeros()` 经典坑（`2200.000000` → `2.2E+3`）——`normalizeDecimalString` 走 `Decimal.toFixed()` + `toExpPos: 1e6`，天然不产科学计数。

**边界说明**：本处是**编辑型维护页**的局部例外。报价单 / 核价单 / 详情 / 列表 / HTML / PDF / Excel 仍走全局 `DISPLAY_SCALE = 9` 口径，task-0810 的 D-2 / FR-9 契约**未被触碰**。

**另一处保留的工程师改进**：`displayText()` 是**全列通用**函数（编码 / 文本 / 枚举列同样走它），故加了 `isDecimalString(v)` 白名单——否则非数字字符串会被 `toDecimal` 兜底成 `"0"`，产生新回归。这是文档原方案没预料到的，予以采纳。

---

## 4. 明确不受影响的部分（已逐一核实，非推断）

| 模块 | 判定依据 |
|---|---|
| **报价单编辑 / 详情 / 卡片渲染** | 走 `quoteCardValues` 快照 + `formatDisplayDecimal`，本就去尾零截 9 位。列 scale 变化对其透明 |
| **核价单渲染** | 同上 |
| **`bomTreeLeaf.ts`** | grep 命中的 `pricing-basic-data` 是**注释里的架构红线**（原文："候选料号列表**不调用任何远程端点**——不查 pricing-basic-data/lookup"），非真实调用。报价侧 BOM 树不受影响 |
| **列表页 / 导出** | 走 `formatNumber` / `NumberFormatUtil`，兜底 `DISPLAY_SCALE`，不感知列 scale |
| **`precision.ts` 全族** | `Decimal.set({ precision: 80 })`，12 位远在容量内；`DISPLAY_SCALE` 本任务不改 |

---

## 5. 回归确认清单（交付必跑）

- [ ] `npx tsc --noEmit -p tsconfig.json` → 0 错误
- [ ] 改动的 `.tsx` → `curl http://localhost:5174/src/pages/master-data/part-costing/EditableSheetTable.tsx` 返 200
- [ ] **核价料号维护页**：打开任一料号的任一页签，DECIMAL 列显示**最多 9 位、去尾零**，不出现 `1.230000000000`
- [ ] **编辑往返**：在维护页输入 12 位小数 → 保存 → 重新打开，值**逐位相等**（显示可截 9 位，但**回存的值必须是 12 位**——这是 AC-6 的前端侧）
  > ⚠️ 这一条是本任务前端最容易做错的地方：**显示截断不能污染回存值**。若 `formatDisplayDecimal` 的结果被写回 state 再提交，12 位就在前端丢了——与后端扩列的目的直接冲突。务必确认「显示格式化只发生在渲染边界」（task-0810 FR-8 原则）
- [ ] 报价单编辑页 / 详情页 / 核价单三视图截图，确认显示位数无变化（AC-9）

---

## 6. 二期触发条件

以下情况需要重新评估前端改动面：

1. **若后续裁决把 `DISPLAY_SCALE` 提到 12**（本期已否决）→ 需重跑 task-0810 的全套显示回归
2. **若 §3.6 的 `global_variable_value.value_number` 纳入范围** → 全局变量维护页需同样检查是否直取原始字符串渲染
3. **若新增页面消费 `pricing-basic-data` 端点** → 须在该页接 `formatDisplayDecimal`，不要重蹈 `EditableSheetTable` 的覆辙
