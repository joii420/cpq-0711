# repair-0805 · 后端 rowKey 权威口径 + 同构白名单复核

> 编写：后端工程师（本轮**零生产代码改动**）
> 日期：2026-08-05
> 分支：`fix/repair-0805-formula-id-lost-in-enrich`
> 上游：同目录 `需求文档.md` / `问题分析报告.md`
> 用途：**前端阶段二 F5 逐字镜像本文 §1**；§2 是「白名单漏搬」同构复核结论

---

## 0. 结论先行：后端本次要不要改？

### **不要改。** 三条依据：

1. **实测正确**。夹具 `.fixture-raw/tab_wuliao.json`（`QT-20260804-0068` 物料页签持久化原文）里
   `formulaResults` 6 行 × 11 列全是正确数值，`rowKey` 6 个全是内容键。
2. **口径可复算**。本文 §1.6 按后端源码手工推演这 6 个 rowKey，**6/6 逐字命中**夹具真值。
   后端 `computeRowKey → buildRawRowKeys → uniquifyRowKeys` 这条链没有歧义、没有漂移。
3. **白名单复核通过**。§2 穷举了后端所有 `fields`/`formulas` 搬运层：
   **只有一处**是白名单式逐键搬运（`CardSnapshotService.buildCardStructure`），
   `formulaId` 已由 `1062c41c` 补齐，公式侧 `id` 走整块透传天然不丢。**没有第二个 `1062c41c`。**

### 但复核发现两条应登记的后端问题（**均非本次故障成因，均建议另立条目，不在本轮动手**）：

| # | 问题 | 严重度 | 是否影响本次修复 |
|---|---|---|---|
| **BE-1** | `buildCardStructure` 字段白名单**漏搬 `decimals`** —— 与 `formulaId` 同一方法、同一类错误 | P2（当前 0 数据触发，休眠） | 否 |
| **BE-2** | `injectDraftFormula`（公式抽屉试算）整体替换 `formulas` 数组，宿主页签其余 FORMULA 字段的 `formula_id` 全部解析不到 → 试算行里这些列为 null | P2（试算预览语义，非持久化） | 否 |

详见 §2.4 / §2.5。

> ⚠️ **一条与需求文档不符的事实**：`需求文档.md` §3.1 把 F2/F3（enrich 两条路径补 `id`）列为「待做」，
> 但本分支 **`e7eb2c42`（`fix(repair-0805): 渲染侧 enrich 补搬公式稳定 id`）已经落地**——
> `enrichComponentData.ts:169` 与 `:303` 现为 `id: fm.id ?? fm.formulaId`。
> 即**阶段一已由前端工程师完成**（该提交在本文编写期间落入分支）。本文按代码现状描述，不按文档描述。

---

## 1. 后端 rowKey 权威口径

**唯一真相三件套**（全在 `cpq-backend/src/main/java/com/cpq/quotation/service/FormulaCalculator.java`）：

| 阶段 | 方法 | 行号 |
|---|---|---|
| ① 单行内容键 | `computeRowKey(rowKeyFields, fields, driverRow, basicDataValues)` | `1412-1447` |
| ② 整列 raw 键（含 `nodeId::` 前缀 + 行号兜底） | `buildRawRowKeys(rowKeyFields, fields, baseRows, deleted)` | `1474-1493` |
| ③ 撞键消歧 | `uniquifyRowKeys(keys)` | `1503-1514` |

调用者共 4 处，**全部必须走这三件套**（`FormulaCalculator.java:1054`、
`CardSnapshotService.java:2119/2125`、`RowDataMaterializer.java:179/184`、
`QuotationTreeService.java:597`）。

---

### 1.1 字段解析优先级链（`computeRowKey` 4-arg）

对 `rowKeyFields` 里的**每一个字段名** `fieldName`，按序：

#### 前置短路（两条，先于任何解析）

| 条件 | 行号 | 返回 |
|---|---|---|
| `rowKeyFields == null` / 非数组 / `size()==0` | `1414` | **`null`** |
| `rowKeyFields.size()==1 && rowKeyFields[0]=="__seq_no__"`（哨兵） | `1415` | **`null`** |

#### 一级：直读 `driverRow[fieldName]`

`FormulaCalculator.java:1427` → `pickNonEmpty(driverRow, fieldName)`（`1594-1600`）

```java
private static String pickNonEmpty(JsonNode node, String field) {
    if (node == null) return null;
    JsonNode v = node.path(field);
    if (v == null || v.isMissingNode() || v.isNull()) return null;
    String s = v.asText("");
    return s.isEmpty() ? null : s;          // ← 空串 == 未命中
}
```

**命中判据**：`node[field]` 存在（非 missing）、非 JSON null、且 `asText("")` **非空串**。
命中 → `part = 该文本`，`any = true`（`1428-1430`）。

> ⚠️ 判据是「**非空串**」，不是 `!= null`。JSON 里的 `""` 视为未命中，继续往下走。
> 数字/布尔用 `JsonNode.asText()` 转文本（`2 → "2"`、`true → "true"`）。

#### 二级：按字段定义 `resolveRowByFieldName` 解析

`FormulaCalculator.java:1431-1441`；懒计算，整行只解析一次（`1433-1435`）：

```java
resolved = resolveRowByFieldName(fields, driverRow, basicDataValues, null, null);
Object v = resolved.get(fieldName);
if (v != null) {
    part = v.toString();
    if (!part.isEmpty()) any = true;       // ← 空串不置 any，但仍占位
}
```

`resolveRowByFieldName`（`1714-1824`）**按 `field_type` 分支**，这是前端最容易漏的一层：

| `field_type` | 取值链 | 行号 |
|---|---|---|
| `FORMULA` / `LIST_FORMULA` | `formulaValues[name]`（本处传 `null` → 恒空） | `1725-1729` |
| `INPUT_NUMBER` / `INPUT_TEXT` / `INPUT` | `editValues[name]`（本处 `null`）→ `driverRow[name]` → **`default_source`**（`GLOBAL_VARIABLE`→`bdv["@gvar:"+code]`；`BNF_PATH`/`BASIC_DATA`→`bdv[bnfDriverLookupKey(path)]`）→ **`content`/`defaultValue`** | `1738-1772` |
| `BASIC_DATA` | **`bdv[bnfDriverLookupKey(basic_data_path)]`** → `driverRow[name]` → `content`/`defaultValue` | `1775-1789` |
| `DATA_SOURCE` | `driverRow[name]` → `datasource_binding`（`GLOBAL_VARIABLE`/`BNF_PATH`）→ `content`/`defaultValue` | `1792-1814` |
| `FIXED_VALUE` / 其它 | `content`/`defaultValue` → `driverRow[name]` | `1817-1821` |

配套访问器（**snake / camel 双读**，`2600-2634`）：

| 访问器 | 读键顺序 | 行号 |
|---|---|---|
| `fieldType(f)` | `fieldType` → `field_type` | `2602-2605` |
| `fieldName(f)` | `name` → `key` | `2607-2611` |
| `basicDataPath(f)` | `basicDataPath` → `basic_data_path` | `2613-2616` |
| `datasourceBinding(f)` | `datasourceBinding` → `datasource_binding` | `2618-2622` |
| **`defaultSource(f)`** | **`defaultSource` → `default_source`** | `2624-2628` |
| `content(f)` | `defaultValue` → `content` | `2630-2634` |

> 🔑 **后端两种命名都认，前端只认驼峰 —— 这就是根因 B。**

其它两个关键工具：

```java
// 2719-2723：把 path 规范化成 basicDataValues 的键（剥花括号后再包一层）
private String bnfDriverLookupKey(String path) {
    String p = path == null ? "" : path.trim();
    if (p.startsWith("{") && p.endsWith("}")) p = p.substring(1, p.length()-1).trim();
    return "{" + p + "}";
}

// 2594-2598：null 节点会被原样返回（NullNode），由 nonEmpty 拦掉
private Object lookupBdv(JsonNode bdv, String key) {
    if (bdv == null || !bdv.isObject()) return null;
    JsonNode v = bdv.get(key);
    return (v == null || v.isMissingNode()) ? null : v;
}

// 2705-2716：JSON null / 空串 / 空数组 → false
private boolean nonEmpty(Object o) { ... }
```

---

### 1.2 多字段拼接分隔符 + 部分解析不到时填什么

| 项 | 值 | 行号 |
|---|---|---|
| **分隔符** | **`\|\|`**（两个竖线，`String.join("\|\|", parts)`） | `1446` |
| **部分字段解析不到** | 该段填 **空串 `""`**（占位保留，`parts` 与 `rowKeyFields` **等长**） | `1424` + `1442` |
| **`any` 标志** | 只要**任意一段**非空即 `true` | `1430` / `1439` |

即 `rowKeyFields = ["销售料号","工序编号"]`、只有第一段解析到 `3120011203` 时 →
**`"3120011203||"`**（尾部空段不省略、不 trim）。

---

### 1.3 全部解析不到时的兜底

| 层 | 行为 | 行号 |
|---|---|---|
| `computeRowKey` | `if (!any) return null;` —— **返 `null`，不返 `"\|\|"` 假键** | `1445` |
| `buildRawRowKeys`（调用方） | `String base = (rk != null && !rk.isEmpty()) ? rk : String.valueOf(pre);` | `1481` |
| 下标基数 | `int pre = 0;` 循环末 `pre++` → **0 基**，且**按 `baseRows` 原数组序**（不跳过任何行） | `1478` / `1490` |

> ⚠️ 兜底判据是 `rk != null && !rk.isEmpty()`，**空串也走行号兜底**。
> 前端 `computeRowKey` 直接 `return String(rowIndex)`（`useCardSnapshots.ts:133`），
> 语义等价（前端把兜底内联了，后端把兜底外置给调用方）。

---

### 1.4 `nodeId::` 前缀的施加条件（`buildRawRowKeys`，`1474-1493`）

```java
String rk = computeRowKey(rowKeyFields, fields, baseRow.path("driverRow"), baseRow.path("basicDataValues"));
String base = (rk != null && !rk.isEmpty()) ? rk : String.valueOf(pre);
JsonNode nodeIdNode = baseRow.get("__nodeId");                     // ← 1482
if (deleted != null && nodeIdNode != null && !nodeIdNode.isNull()) { // ← 1483
    String nodeId = nodeIdNode.asText("");
    if (!nodeId.isEmpty()) {                                        // ← 1485
        base = nodeId + "::" + base;                                // ← 1486
    }
}
```

| 问题 | 答案 |
|---|---|
| **「报价侧信号」判据是什么** | **`deleted != null`**（墓碑列表参数，`List<DeletedRowKeys.Tombstone>`）。**空列表 `List.of()` 也算报价侧**；核价侧固定传 `null`（见 `CardSnapshotService.java:2125` / `RowDataMaterializer.java:184` 的 legacy 变体显式传 `null`） |
| **`__nodeId` 从哪取** | **`baseRow` 顶层**的 `__nodeId`（`baseRow.get("__nodeId")`，**不是** `driverRow` 里的） |
| **为空 / 缺失怎么办** | 三重放行：`nodeIdNode == null`（键缺失）/ `isNull()`（JSON null）/ `asText("")` 为空串 → **一律不加前缀**，`base` 保持内容键或行号 |
| **前缀分隔符** | **`::`**（两个冒号） |
| **前缀与内容键的组合形态** | `nodeId::内容键` 或 `nodeId::行号`（内容键兜底成行号时，前缀照加 —— 本单第 0 行 `3120011203::0` 就是这个形态） |

> 前缀的目的（javadoc `1449-1473`）：树页签 DAG 里同一子件挂在不同父下会产生重复内容键，
> 靠 `uniquifyRowKeys` 的 `#序号` 消歧会受数组顺序影响错位；`nodeId` 是天然唯一的结构维度。

---

### 1.5 `#N` 消歧规则 + 与前缀的先后顺序

`uniquifyRowKeys`（`1503-1514`）：

```java
Map<String,Integer> counts = ...;  for (String k : keys) counts.merge(k, 1, Integer::sum);
Map<String,Integer> running = ...;
for (String k : keys) {
    if (counts.getOrDefault(k,0) <= 1) { out.add(k); continue; }   // 出现 1 次 → 原样
    int n = running.merge(k, 1, Integer::sum) - 1;                  // 0 基
    out.add(k + "#" + n);                                          // 第 1 个 → k#0
}
```

| 项 | 规则 |
|---|---|
| 触发条件 | 该键在**同一组件的 `keys` 列表内**出现 **≥2 次** |
| 后缀 | **`#<0基出现序号>`**：第 1 次 `#0`、第 2 次 `#1`… |
| 出现 1 次 | **保持原样、不加 `#0`**（向后兼容存量 `editRows`） |
| 序号依据 | **入参 `keys` 的数组顺序**（= `baseRows` 数组序，前后端同序） |

**顺序：先加 `nodeId::` 前缀，再唯一化。**
`buildRawRowKeys` 产出的就是**已带前缀**的 raw 列表（`1486`），调用方随后才喂 `uniquifyRowKeys`
（`CardSnapshotService.java:2119→2125`、`RowDataMaterializer.java:179→184`）。
即消歧是对 **`nodeId::内容键` 整串**做的，不是对裸内容键做完再拼前缀。
前端 `buildUniqueRowKeys`（`useCardSnapshots.ts:167-179`）已按同一顺序实现 ✅。

---

### 1.6 本单实证：手工推演 6 行 rowKey vs 夹具真值

**输入**

- `baseRows`：`.fixture-raw/tab_wuliao.json` → `baseRows`（6 行，含顶层 `__nodeId` 与 `driverRow._料件`）
- `rowKeyFields`：`.fixture-raw/comp_wuliao.json` → **`["料件"]`**（单字段）
- 字段定义：`{name:"料件", field_type:"INPUT_TEXT", default_source:{type:"BASIC_DATA", path:"$bom_view._料件"}}`
- `deleted`：非 null（报价侧，`quote_card_values`）

**逐级推演**（每行都走同样 5 步）

1. 前置短路：`rowKeyFields` 非空、非 `__seq_no__` → 继续
2. **一级** `pickNonEmpty(driverRow, "料件")` → `driverRow` 的键是 `_料件`（带下划线前缀），
   **没有 `料件` 这个键** → `path()` 返 MissingNode → **`null`，六行全部一级不命中**
3. **二级** `resolveRowByFieldName` → 字段类型 `INPUT_TEXT` 走 `1738-1772` 分支：
   `editValues=null` → `driverRow.path("料件")` MissingNode → `defaultSource(f)` 读到 `default_source`
   （访问器 `2626` 的 snake 兜底）→ `type=="BASIC_DATA"` → `lookupBdv(bdv, bnfDriverLookupKey("$bom_view._料件"))`
   = **`bdv["{$bom_view._料件}"]`**
4. `!any → null`，否则 `String.join("||", [part])`
5. `buildRawRowKeys`：`base = rk 非空 ? rk : String(idx)`；`deleted != null` 且 `__nodeId` 非空 → `nodeId + "::" + base`
6. `uniquifyRowKeys`：6 个键互不相同 → **全部原样，无 `#N`**

**逐行比对表**

| i | `__nodeId` | `bdv["{$bom_view._料件}"]` | 一级 | 二级 | `any` | 内容键 `rk` | `base` | **手工推演结果** | **夹具 `formulaResults[i].rowKey`** | 命中 |
|---|---|---|---|---|---|---|---|---|---|---|
| 0 | `3120011203` | `null` | miss | `null`（`nonEmpty(NullNode)=false`） | `false` | **`null`** | `"0"`（行号兜底，0 基） | `3120011203::0` | `3120011203::0` | ✅ |
| 1 | `3120011203/3110520422` | `"AgNi10/Cu触点"` | miss | `"AgNi10/Cu触点"` | `true` | `AgNi10/Cu触点` | 同左 | `3120011203/3110520422::AgNi10/Cu触点` | `3120011203/3110520422::AgNi10/Cu触点` | ✅ |
| 2 | `3120011203/00144` | `"H85"` | miss | `"H85"` | `true` | `H85` | 同左 | `3120011203/00144::H85` | `3120011203/00144::H85` | ✅ |
| 3 | `3120011203/3110520422/00255` | `"Ag粉"` | miss | `"Ag粉"` | `true` | `Ag粉` | 同左 | `3120011203/3110520422/00255::Ag粉` | `3120011203/3110520422/00255::Ag粉` | ✅ |
| 4 | `3120011203/3110520422/00256` | `"TU2丝"` | miss | `"TU2丝"` | `true` | `TU2丝` | 同左 | `3120011203/3110520422/00256::TU2丝` | `3120011203/3110520422/00256::TU2丝` | ✅ |
| 5 | `3120011203/3110520422/00257` | `"羰基镍粉"` | miss | `"羰基镍粉"` | `true` | `羰基镍粉` | 同左 | `3120011203/3110520422/00257::羰基镍粉` | `3120011203/3110520422/00257::羰基镍粉` | ✅ |

### **6/6 逐字命中。§1 的口径描述与后端实际行为一致。**

> 附带证实了 `问题分析报告.md` §2.2 的观察：第 0 行前后端"偶然撞上"，
> 是因为**两边都走了行号兜底**（后端 `rk==null → "0"`、前端 `!any → String(0)`），
> 而不是因为前端解析对了。

---

### 1.7 前端镜像检查清单（F5 逐条对照）

前端待对齐文件：`cpq-frontend/src/pages/quotation/useCardSnapshots.ts`
（`resolveRowKeyPart:63-99` / `computeRowKey:110-135` / `uniquifyRowKeys:141-151` / `buildUniqueRowKeys:167-179`）

#### ✅ 已对齐（勿动）

| # | 项 | 后端 | 前端 | 证据 |
|---|---|---|---|---|
| M1 | 分隔符 `\|\|` | `FormulaCalculator:1446` | `useCardSnapshots.ts:134` | 一致 |
| M2 | `__seq_no__` 哨兵短路 | `:1415` | `:118` | 一致 |
| M3 | `rowKeyFields` 空 → 行号 | `:1414` + `:1481` | `:117` | 一致 |
| M4 | 全空 → 行号（0 基、按数组序） | `:1445` + `:1478/1481/1490` | `:133` + `:174` | 一致 |
| M5 | 部分解析不到 → 该段填 `""` 占位 | `:1424/1442` | `:129` | 一致 |
| M6 | 一级直读 `driverRow[fieldName]`，空串视为未命中 | `pickNonEmpty:1594-1600` | `:70-73`（`String(direct).length > 0`） | 一致 |
| M7 | `GLOBAL_VARIABLE` → `bdv["@gvar:"+code]` | `:1754-1756` | `:78-81` | 一致 |
| M8 | `BNF_PATH`/`BASIC_DATA` → `bdv[bnfDriverLookupKey(path)]` | `:1757-1762` | `:82-86` | 一致 |
| M9 | `bnfDriverLookupKey` 剥花括号再包一层 | `:2719-2723` | `useDriverExpansions.ts:467-480` | 逐字等价 |
| M10 | `#N` 消歧：≥2 次才加、0 基、按数组序、1 次原样 | `:1503-1514` | `:141-151` | 逐字等价 |
| M11 | **先拼 `nodeId::` 前缀、后唯一化** | `:1486` → 调用方 `uniquify` | `:173-178` | 一致 |
| M12 | `nodeId` 为空/缺失 → 不加前缀 | `:1483-1487` | `:175-176`（`nodeId ? ... : base`） | 一致 |
| M13 | 前缀分隔符 `::` | `:1486` | `:176` | 一致 |

#### ❌ 未对齐（F5 必须处理）

| # | 项 | 后端 | 前端现状 | 影响面（当前库实测） | 优先级 |
|---|---|---|---|---|---|
| **X1** | **`default_source` 蛇形键读不到** | `defaultSource(f)` **双读** `defaultSource` → `default_source`（`:2624-2628`） | `resolveRowKeyPart` 只读 `f.defaultSource`（`:65/:127`）；而 `enrichComponentData` 两条路径**统一输出蛇形** `default_source`（`:153` / `:289`）→ 恒 `undefined` | **151 处绑定 / 78 个组件**（`INPUT_TEXT` + `default_source`）+ **28 处 / 28 个组件**（`INPUT_NUMBER` + `default_source`） | **P0（需求文档已列 F5）** |
| **X2** | **`BASIC_DATA` 字段的 `basic_data_path` 分支整条缺失** | `resolveRowByFieldName` 的 `BASIC_DATA` 分支读 `basicDataPath(f)` = `basicDataPath` → `basic_data_path`（`:1775-1780` + `:2613-2616`） | `resolveRowKeyPart` **完全不读 `basic_data_path`**，只看 `defaultSource` | **42 处绑定 / 17 个组件**：`BASIC_DATA` 类型 + 有 `basic_data_path` + **无 `default_source`**（COMP-0048~0063 等核价通用模板全族：销售料号/工序编号/元素代码/来料料号/要素名称/项次/电镀方案编号…） | **P0 —— 需求文档 F5「兼容 `defaultSource ?? default_source`」不覆盖这条，只改大小写这批组件仍会退化成行号** |
| **X3** | **`content`/`defaultValue` 兜底缺失** | 各类型分支末尾都有 `content(f)`（`defaultValue` → `content`）兜底（`:1766-1769` / `:1783-1786` / `:1808-1811` / `:1817-1819`） | 前端无此级 | 当前库 0 处（上述 221 处 rowKey 绑定的 `content` 均为空） | P2（休眠，但建议一并对齐，否则口径仍不完整） |
| **X4** | `FIXED_VALUE` / `DATA_SOURCE` 类型的 rowKey 字段无解析分支 | 有专门分支（`:1792-1814` / `:1817-1821`） | 前端 `resolveRowKeyPart` **完全不看 `fieldType`**，只有一条 `defaultSource` 通路 | 当前库 0 处 | P2（休眠） |
| **X5** | **前端多出一级后端没有的降级**：`driverRow[path 末段]`（`"$bom_view._料件"` → `driverRow["_料件"]`） | **无此级** | `:89-96` | 本单恰好与后端同值（巧合）；但**当 `bdv` 缺该键而 `driverRow` 有别名列时，前端出内容键、后端出行号 → 新的不一致** | P1（F5 落地后才会暴露，建议同批评估：要么删、要么后端补对称级——**后端补属扩围，须技术总监裁决**） |
| **X6** | 数值段字符串化口径 | `unwrapNode` 返 Java `Number` → `v.toString()`（`:1438` + `:1830-1839`）。Jackson `2.0` → `DoubleNode` → `"2.0"` | `String(2.0)` → `"2"` | 当前库 0 处（`INPUT_NUMBER` 型 rowKey 字段全是「项次」整数；持久化 `rowKey` 里正则扫不到 `x.x0` 形态小数段） | P3（理论风险，登记备查即可） |
| **X7** | **类型洞：`buildUniqueRowKeys(fields: any[])`** | — | `:168` 形参声明 `any[]`，**吞掉所有调用点的类型检查**；`computeRowKey:111` 那份精确签名因此形同虚设（`QuotationStep2.tsx:2089/2092` 的 `as any` 只是叠加，去掉也不会报错） | 全部 6 个调用点 | **P1 —— F5 若只改运行时不收窄这个签名，下次仍然静默漏** |

#### 调用点上下文（F5 改完需逐个复验）

| 调用点 | `fields` 来源 | 键风格 | `applyNodePrefix` | 与后端 `deleted != null` 对齐？ |
|---|---|---|---|---|
| `useCardSnapshots.ts:278/279`（hook 内部） | `CardStructureTab.fields`（`buildCardStructure` 产出） | **驼峰 `defaultSource`** ✅ | `side === 'QUOTE'`（`:273`） | ✅ |
| `QuotationStep2.tsx:2089/2092` | `comp.fields`（enrich 模型） | **蛇形 `default_source`** ❌ | `true` / 省略 | ✅（外层已判 `side === 'QUOTE'`） |
| `QuotationStep2.tsx:3138/3141` | `activeComponent.fields`（enrich 模型） | **蛇形** ❌ | `true` / 省略 | ✅ |
| `ReadonlyProductCard.tsx:679/682` | `activeComp.fields`（enrich 模型） | **蛇形** ❌ | `!isCosting` / 省略 | ✅ |

> 📌 **hook 内部那条路径（`:278`）是驼峰、已经对齐**，坏的只有三个 enrich 模型调用点。
> 所以 F5 写成 `f.defaultSource ?? f.default_source` **两边都不会回归**。

> 📌 `:3138` / `:679` 两处的 `__nodeId` 取自 `activeDriverExpansion.rows[i].__sys.nodeId`
> （`QuotationStep2.tsx:3133` / `ReadonlyProductCard.tsx:674`），不是后端那种 `baseRow` 顶层 `__nodeId`。
> 语义等价，但 `expIndex < 0`（手动行）时为 `undefined` → 不加前缀，与后端「`__nodeId` 缺失不加前缀」一致 ✅。

#### F5 建议实现（供参考，非强制）

```ts
function resolveRowKeyPart(
  fieldName: string,
  field: { fieldType?: string; field_type?: string;
           defaultSource?: DS | null; default_source?: DS | null;
           basicDataPath?: string | null; basic_data_path?: string | null;
           defaultValue?: any; content?: any } | undefined,
  driverRow, basicDataValues,
): string | undefined {
  // 1. 直读 driverRow[fieldName]（对齐 pickNonEmpty:1594）
  // 2. basic_data_path（X2，对齐 :1775-1780；双读 basicDataPath / basic_data_path）
  // 3. default_source（X1，双读 defaultSource / default_source）
  // 4. content / defaultValue 兜底（X3，对齐 content():2630）
  // 5. （X5 待裁决）path 末段降级：保留 or 删除
}
```

并把 `buildUniqueRowKeys` 的 `fields: any[]` 收窄成显式 union 类型（X7）。

#### F6 存量兼容（提醒，非后端职责）

需求文档 §3.2 的判断成立：F5 会把这 78+28+17 个组件的前端键**从行号变成内容键**，
存量 `editRows` 是按行号键写的 → `getByKeyWithLegacyFallback`（`useCardSnapshots.ts:191-201`）
现在只处理「有/无 `nodeId` 前缀」一档，**不覆盖「行号 vs 内容」这一档**，必须新增一档。

---

## 2. 后端同构「白名单漏搬」复核

**复核方法**：不靠印象，靠三重穷举 —— ① codegraph 定位符号；
② `/usr/bin/grep -a`（规避本环境 ugrep 对中文大文件静默返空的坑）全量扫写侧键；
③ 对每个命中点读源码判定「白名单逐键」还是「整块透传」。

**扫描口径**（`cpq-backend/src/main/java` 全量）：

```bash
# a) 所有触及公式 JSON 的文件
/usr/bin/grep -ral '"expression"' .            # 17 个文件
/usr/bin/grep -ral '"formulas"' .              #  6 个文件
/usr/bin/grep -ral '"formulaId"\|"formula_id"\|"default_formula_id"\|"defaultFormulaId"' .   # 4 个文件

# b) 所有「写侧」字段配置键发射点（判定白名单的决定性证据）
/usr/bin/grep -arn 'put("basic_data_path"\|set("basic_data_path"\|put("basicDataPath"\
\|put("field_type"\|set("field_type"\|put("formula_name"\|put("formulaName"\
\|put("is_amount"\|put("isAmount"\|set("default_source"\|put("default_source"\
\|set("defaultSource"\|set("conditional_formula"\|set("conditionalFormula"\
\|set("datasource_binding")' .
# → 命中仅 7 行，全部落在 CardSnapshotService（6 行）+ TemplateService:650（1 行，双轨 path 覆写，非搬运）

# c) 结构侧唯一标识：谁在产出 camelCase fieldType
/usr/bin/grep -arn 'put("fieldType"\|set("fieldType"' .
# → 唯一命中 CardSnapshotService.java:296
```

**c) 的意义**：camelCase `fieldType` 是「冻结结构」的指纹，全工程**只有一处**产出
→ **后端只有一个结构白名单，不存在同构副本**。这直接回答了元教训的追问。

### 2.1 搬运层清单

| # | 搬运层 | 文件:行 | 逐键白名单 / 整块透传 | 公式 `id` | 字段 `formula_id` | 条件公式内 `formula_id` / `default_formula_id` | 漏搬？ |
|---|---|---|---|---|---|---|---|
| **B1** | **`CardSnapshotService.buildCardStructure`**（报价单/核价单**冻结结构**，写 `quotation_view_structure.structure`） | `CardSnapshotService.java:209-366` | **fields = 逐键白名单**（`:293-347`，19 个键）<br>**formulas = 整块透传**（`:350-356` `tabNode.set("formulas", formulas)`） | ✅ 整块透传天然带走 | ✅ `:319-321` 显式搬 `formula_id → formulaId`（`1062c41c` 补） | ✅ `:323-325` `conditionalFormula` **整块** `set`，内部 id 随行 | **否**（但漏 `decimals`，见 §2.4） |
| **B2** | `TemplateService.publish` 建模板 snapshot | `TemplateService.java:234` | 整块：`entry.put("formulas", parseJsonArray(comp.formulas))`；`fields` 同样整块 `parseJsonArray(effectiveFields)`（`:233`） | ✅ | ✅ | ✅ | 否 |
| **B3** | `TemplateService.refreshSnapshotsByComponent` 平台级刷新 | `TemplateService.java:390` | 整块：`entry.put("formulas", compFormulas)`；`fields = effectiveFields`（`:389`） | ✅ | ✅ | ✅ | 否 |
| **B4** | `TemplateService` snapshot 重建 | `TemplateService.java:729` | 整块（同 B2 写法） | ✅ | ✅ | ✅ | 否 |
| **B5** | `ComponentImportService` 组件**导入** | `ComponentImportService.java:264-265`（`c.fields/c.formulas = nodeToJson(it.*)`） | 整块（bundle DTO 声明就是 `JsonNode fields; JsonNode formulas;` —— `ComponentExportBundle.java:55-57`） | ✅ **原样保留**（`FormulaIdBinder.ensureFormulaIds:36-45` 只补缺失的，已有 id 不重生成） | ✅ `:341-343` 三遍流程重新固化 | ✅ `bindConditionalRefs:175-189` | 否 |
| **B6** | `ComponentImportService` 跨组件引用重写 | `FormulaRefRemapper.remap`（`:39-101`） | 就地改 `expression` 内 token 的 `source`/`component_code`，**其余键原样**（`:98` 整数组回写） | ✅ | n/a | n/a | 否 |
| **B7** | `ComponentService.create` 保存 | `ComponentService.java:515-567` | 整块：`request.fields/formulas` 是 `List<Map<String,Object>>`（`CreateComponentRequest.java:16-17`）；**`:547` `fieldsJson = toJson(fieldList)` 已在 binder 之后重序列化** | ✅ `:528` | ✅ `:539` | ✅ `:539` | 否 |
| **B8** | `ComponentService.update` 保存 | `ComponentService.java:661-680` | 整块，`:679-680` 用固化后的 list 回写（注释显式点名「不是原 fieldsJson」） | ✅ `:661` | ✅ `:674` | ✅ `:674` | 否 |
| **B9** | `ComponentDTO.from`（组件配置页读路径） | `ComponentDTO.java:65-66` | 整块 `parseJsonArray`，字段类型 `List<Map<String,Object>>`（`:20-21`） | ✅ | ✅ | ✅ | 否（印证报告「配置页 id 完好」） |
| **B10** | `QuotationService` 读回冻结结构 → DTO | `QuotationService.java:213/215` | 整块 `JsonNode` 直挂 `dto.quoteCardStructure` / `costingCardStructure` | ✅ | ✅ | ✅ | 否 |
| **B11** | `ConfigureSnapshotService`（选配侧装配） | `ConfigureSnapshotService.java:1283` | **只读**（`tab.path("formulas")` 传给 `CrossTabComponentOrder` 建依赖图）；无 `put("formulas"/"fields")` 写点（grep 空） | n/a | n/a | n/a | 否 |
| **B12** | `SnapshotCollectorService` 全局变量采集 | `SnapshotCollectorService.java:485-489` | **只读**遍历 `formulas[].expression[]` 找 `global_variable` token | n/a | n/a | n/a | 否 |
| **B13** | `RowDataMaterializer` 物化 | `RowDataMaterializer.java:146` | **只读** `tab.path("formulas")` | n/a | n/a | n/a | 否 |
| **B14** | `CrossTabComponentOrder` 拓扑序 | `:117` / `:239` | **只读** `f.path("expression")` | n/a | n/a | n/a | 否 |
| **B15** | `ComponentDataEffectiveRows` | `:32`（`EXPR_KEY`） | **只读** | n/a | n/a | n/a | 否 |
| **B16** | `ExcelViewService` | `:1239` | **只读**，且 `col.get("expression")` 是 **`excel_columns` 的列表达式**，与组件公式无关 | n/a | n/a | n/a | 否（属不同概念，列此备查） |
| **B17** | `CardSnapshotService.injectDraftFormula`（公式抽屉试算） | `CardSnapshotService.java:3181-3218` | **既非搬运也非透传：整体替换** `tab.set("formulas", 只含 __dryrun__ 的新数组)`（`:3191-3197`）+ `tab.putArray("formula_assignments")`（`:3199`） | 合成公式**无 `id`**（`:3192-3195` 只 put `name`/`fieldName`/`expression`） | 宿主页签既有字段的 `formula_id` 仍在（deepCopy） | 同左 | **不算漏搬**，但有副作用，见 §2.5 |

### 2.2 结论

1. **后端只有 B1 一处是白名单式逐键搬运**（判据：全工程只有 `CardSnapshotService.java:296` 产出 camelCase `fieldType`）。
2. **B1 的 `formulaId` 已补齐**（`1062c41c`，`:319-321`），且**公式侧 `id` 走整块 `set`，天然不丢**（`:350-356`）。
   `问题分析报告.md` §3.1「结构快照 `formula[0] keys = ['expression','id','name','result_type']`」的实测与此吻合。
3. **没有第二个 `1062c41c`**。前端那份漏搬（`enrichComponentData` 两条 `.map()`）之所以是**前端独有**，
   正是因为后端**根本不做逐键搬运公式**——B2/B3/B4/B5/B7/B8/B9 全是整块 JSON 透传。
4. **条件公式的 `formula_id` / `default_formula_id` 无独立风险**：所有搬运层都把
   `conditional_formula` 当**整块对象**处理（B1 的 `:323-325` 也是 `set` 整块），内部 id 自动随行。

### 2.3 反向确认：后端确实"算对了"

- 求值端 `resolveFormula`（`FormulaCalculator.java:2029-2082`）：`formula_id`（蛇形）→ `formulaId`（驼峰）
  最高优先，**查不到返 `null` 不回落**（`:2037-2045`）——与前端设计同款语义。
- 条件公式 `condRefFormula`（`:1930-1943`）同款，且同样「绑了 id 查不到 → `null` 不回落」。
- 两者都双读 snake/camel，所以后端读组件正本（snake）和读冻结结构（camel）都能命中。
- 夹具 `formulaResults` 6 行 × 11 列全非空 → **实测印证**。

### 2.4 【BE-1】`buildCardStructure` 漏搬 `decimals`（新发现，P2 休眠）

**与 `formulaId` 同一方法、同一类错误。**

| 项 | 事实 |
|---|---|
| 前端**消费点** | `ComponentCell.tsx:146/159/171/185/299` 共 5 处 `field.decimals`（`:299` 注释「未配 decimals → 兜底 2 位」） |
| 前端**类型声明** | `pages/component/types.ts:182` `decimals?: number \| null` |
| 前端**两条 enrich 路径都读** | `enrichComponentData.ts:161`（模板快照路径，源是 `component.fields`，键名同为 `decimals` → **能拿到**）<br>`enrichComponentData.ts:295`（**冻结结构路径**，源是 `buildCardStructure` 输出 → **恒 `null`**） |
| 后端**发射点** | `CardSnapshotService.java:293-347` 白名单 19 键：`name/fieldType/label/sortOrder/isAmount/isRequired/isSubtotal/width/editable/defaultValue/basicDataPath/formulaName/formulaId/conditionalFormula/globalVariableCode/defaultSource/listFormulaConfig/datasourceBinding/unitSourceField` —— **无 `decimals`** |
| **后果** | 走冻结结构渲染的三处（报价单编辑页 structure 分支 / 详情页 / 核价侧）小数位配置丢失 → 回落默认位数。**只影响显示，不影响计算。** |
| **当前是否触发** | **否**。`SELECT count(*) FROM component c WHERE EXISTS (SELECT 1 FROM jsonb_array_elements(c.fields) f WHERE f ? 'decimals' AND f->>'decimals' IS NOT NULL)` → **0**。功能已建但无人配置，故休眠。 |
| **建议** | 建 BACKLOG 条目（P2）：`buildCardStructure` 补 `if (!f.path("decimals").isMissingNode() && !f.path("decimals").isNull()) fieldNode.put("decimals", f.path("decimals").asInt());`。**本轮不做**（红线：不改 `.java`；且与本次故障无关）。 |

### 2.5 【BE-2】`injectDraftFormula` 试算预览会打空宿主其余公式列（P2）

`CardSnapshotService.java:3181-3218`：为「公式抽屉试算」把宿主页签的 `formulas` **整体替换**成
只含一条 `__dryrun__` 的数组（`:3191-3197`）并清空 `formula_assignments`（`:3199`）。

宿主页签既有的 FORMULA 字段（deepCopy 保留了各自的 `formula_id`）此时在新数组里**一条也查不到**
→ `resolveFormula:2039-2045` 返 `null` → 这些列在试算行里全空。
若用户写的草稿公式引用了同页签其它公式列（如 `[材料成本] * 2`），**试算预览值可能与真实计算不符**。

判定：**不是白名单漏搬**（是刻意的隔离式替换），且 `extractHostDryRunRows:3221-3235` 只取
`values["__dryrun__"]` 一列，其余列本就不外露。

BL-0098 之前，这里会走 `resolveFormula` 的位置回退（`:2075-2080`）把 `__dryrun__` 表达式
**错绑到第 0 个 FORMULA 字段**上——所以 BL-0098 实际上让这条路径**更确定**了，只是没让它更正确。

**建议**：登记 BACKLOG（P2），修法可选「保留原 formulas 数组 + 追加 `__dryrun__`」。
**本轮不做**（超出 repair-0805 范围，需技术总监裁决）。

---

## 3. 自检声明

本轮**未修改任何 `.java` / `.sql` / `.ts` / `.tsx` 生产文件**，仅新增本 md。
故无编译/热重载自检项。所有结论的验证方式：

| 验证项 | 方式 | 结果 |
|---|---|---|
| §1 口径描述 | 逐条读源码 + 记录 `文件:行号` | 已列 |
| §1.6 手工推演 | 按 §1 口径对 6 行 `baseRows` 人工推演，与夹具 `formulaResults[].rowKey` 逐字比对 | **6/6 ✅** |
| §1.7 X1/X2 影响面 | `cpq_db_0724` 聚合查询（按 `field_type` × `default_source` × `basic_data_path` 分组统计 `row_key_fields` 绑定） | 151(78 组件) / 28(28) / **42(17)** |
| §1.7 X6 影响面 | 正则扫 `quote_card_values` 全量持久化 `rowKey` 找小数段 | 0 命中（休眠） |
| §2 搬运层穷举 | `/usr/bin/grep -a` 三重扫（公式键 / 写侧字段键 / camelCase `fieldType`）+ 逐点读源码 | 17 个搬运/消费点，白名单仅 1 处 |
| §2.4 `decimals` 影响面 | `cpq_db_0724` `jsonb_array_elements` 计数 | 0（休眠） |

> ⚠️ 本环境 `grep` 是 ugrep（`-I` 会把中文注释多的大源文件静默返空），
> 上述所有文本检索**一律用 `/usr/bin/grep -a`**，避免据空结果下"无写点"的错误结论。
