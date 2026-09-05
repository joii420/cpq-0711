# 接口契约 · task-260904-页签类型收缩

> 服务的 AC 见 `需求文档.md §③`。本文件是**前后端唯一协调物**，前端只按 `fronttask.md`、后端只按 `backtask.md`，两者之间的约定以本文为准。

---

## 0. 设计前提：内部坐标不动，只换用户面

`BuilderConfig`（存在 `component.builder_config` JSONB 里）用 `(tabType, variantKey)` 作为**定位 `semantic_tab_view` 的内部坐标**。

🚫 **本次不改这对坐标的字段名，也不改 `field-tree` / `compile` 的请求入参。**

理由：
1. 用户诉求发生在**界面层**（不再选抽象的"页签类型"），内部坐标叫什么用户看不见
2. 改坐标名 = 存量组件的 `builder_config` JSONB 全部要迁移，收益为零、风险不小
3. `(tabType, variantKey)` 本来就唯一定位一行 `semantic_tab_view`，语义正确

**变的是**：前端不再让用户直接选 `tabType`，改为选「数据源」；每个数据源在响应里自带它对应的 `(tabType, variantKey)` 坐标，前端原样回传。

---

## 1. `GET /config/semantic-graph/field-tree`

### 1.1 请求 —— **不变**

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `tabType` | string | 是 | 内部坐标。前端从 `availableSources[].tabType` 取，不再由用户直接选 |
| `variantKey` | string | 否 | 内部坐标，缺省 `""` |
| `selectedConfig` | string(JSON) | 否 | 当前已选列，用于算 `conflict`。解析失败按未传处理（不 500），**此行为不变** |

### 1.2 响应 —— 一增一改

```jsonc
{
  "tabType": "材质元素",          // 保留：只读回显用
  "variantKey": "",
  "anchorDesc": "物料与元素BOM",

  // 🆕 新增：数据源清单，替代 availableTabTypes 成为前端下拉的数据源
  "availableSources": [
    { "sourceKey": "MATERIAL_BOM",  "label": "物料BOM",     "tabType": "BOM 树",   "variantKey": "", "semantic": "TREE" },
    { "sourceKey": "ELEMENT_BOM",   "label": "物料与元素BOM", "tabType": "材质元素", "variantKey": "", "semantic": "MATERIAL_ELEMENT" },
    { "sourceKey": "CUSTOMER_PART", "label": "物料",         "tabType": "主件",     "variantKey": "", "semantic": null },  // ← S-11：锚点是客户料号表，物料表以 JOIN 组接入
    { "sourceKey": "SELF_PROCESS_FEE", "label": "自制加工费", "tabType": "费用类",   "variantKey": "SELF_PROCESS_FEE", "semantic": null }
    // …共 11 项（QUOTE 方言）
  ],

  // ⚠️ 变更：availableTabTypes 移除
  "variants": [...],              // 保留，形态不变
  "switches": [...],
  "groups": [...]
}
```

**`semantic` 字段的取值与含义**（前端唯一需要分支的地方）：

| 值 | 含义 | 前端行为 |
|---|---|---|
| `"TREE"` | 该数据源是 BOM 树 | 提示"本页签为树形，只读、不参与回填"；不显示价格策略组 |
| `"MATERIAL_ELEMENT"` | 该数据源是材质元素 | 显示价格策略原子组（`groupKind='PRICE'`）；元素列由后端自动带出 |
| `null` | 普通平铺数据源 | 不显示价格策略组，无树提示 |

🚫 **前端不得自己按 `label` 或 `sourceKey` 硬编码判断语义**，一律读 `semantic`。

### 1.3 数据源清单的来源（后端）

`availableSources` = `semantic_tab_view` 中 `status='ACTIVE'` 且 `dialect` 匹配的行，每行取其锚点节点的 `node_key` 与 `display_name`。

停用 6 行（3 方言 × {零件, 外购件}）后，`QUOTE` 方言返回 **11** 项、`COST_BASIC` **10** 项、`COST_DETAIL` **18** 项。

⚠️ **「物料」是唯一的双表数据源**（S-11）：其 `sourceKey` 为 `CUSTOMER_PART`，字段面板返回**两个组** —— `客户料号(MAIN)` 5 列 + `物料(JOIN)` 9 列。其余数据源一律一组。

### 1.4 错误

| HTTP | code | 触发 |
|---|---|---|
| 404 | `COMPILE_TABVIEW_NOT_FOUND` | `(tabType, variantKey)` 查不到 ACTIVE 行。**本次新增触发场景**：传入已停用的「零件」/「外购件」——文案需指出该页签类型已停用，请改选数据源 |

---

## 2. `POST /config/semantic-graph/compile` —— 请求响应均不变

编译期判别式的推导规则有一处**行为变更**（不改契约）：

- **改动前**：`SemanticCompiler.resolveDiscriminator()` 中 `"外购件".equals(tabType) → characteristic = 'OUTSOURCED'`
- **改动后**：该分支移除。`MATERIAL_BOM` 作锚点时恒不加 `characteristic` 过滤（即原 BOM 树行为）

> ⚠️ 该分支引用的 `characteristic` 列在新表 `ds_quote_material_bom` 中**已不存在**（只有 `output_material_type`），移除前它已是不可达/会出错的死代码。

---

## 3. `POST /quotations/{qid}/line-items/{lid}/tree/add-leaf` —— 请求不变，新增 2 个拒绝态

### 3.1 请求 —— 不变

```jsonc
{ "componentId": "<uuid>", "hostNodeId": "<string>", "partNo": "<string>" }
```

### 3.2 类型判定来源变更（不改契约，改实现）

| | 改动前 | 改动后 |
|---|---|---|
| 判据 | `BomNodeTypeResolver` 6 条链，按"料号出现在当前报价单哪类页签的已渲染行" | 读主数据 |
| 材质 | 命中「材质元素」页签 | 存在于 `material_recipe.code` |
| 零件 | 命中「零件」页签 | 存在于 `ds_quote_material` 且 `material_type='零件'` |
| 外购件 | 命中「外购件」页签 | 存在于 `ds_quote_material` 且 `material_type='外购件'` |
| `material_type` 为空 | — | **判「零件」**（A0-1 裁决） |

响应中 `__nodeType` 的取值域**不变**：`材质` / `零件` / `外购件`。

### 3.3 新增错误码

| HTTP | code | 触发 | 文案要求 |
|---|---|---|---|
| 400 | `LEAF_PART_NOT_IN_MASTER` | 料号在 `ds_quote_material` 与 `material_recipe` 中**都不存在** | 必须点名该料号，并说明需先在物料表或材质库建档 |
| 400 | `LEAF_CYCLE_DETECTED` | 挂上后会形成环（含自环：宿主 = 料号） | 必须给出**环路径**（如 `A → B → A`），不能只说"会成环" |

### 3.4 既有错误码 —— 全部保留，行为不变

| HTTP | 触发 | 备注 |
|---|---|---|
| 404 | 报价单 / 报价行不存在 | |
| 400 | `componentId` 不是该报价行的树页签组件 | **判据由 `tab_type='BOM'` 改为"该组件绑定的数据源 `semantic='TREE'`"**，文案不变 |
| 400 | 宿主节点不存在于该树 | |
| 400 | 材质 / 外购件节点不可再添加下级 | 🚨 **反向 AC-8**：本次不得失效 |
| 409 | 类型冲突 | 改读主数据后**结构上不再可能触发**（一个料号在主数据里只有一个类型）。⚠️ **保留该分支与错误码**，不删——降级为防御性代码 |

### 3.5 校验顺序（**强制**，影响错误文案的可预期性）

```
① 报价单 / 报价行存在
② componentId 是树页签组件
③ 宿主节点存在
④ 宿主不是材质 / 外购件（既有）
⑤ 🆕 料号存在于主数据（否则 LEAF_PART_NOT_IN_MASTER）
⑥ 🆕 不成环（否则 LEAF_CYCLE_DETECTED）
⑦ 判定类型（含空值默认零件）
```

> ⑤ 必须早于 ⑥：不存在的料号谈不上成不成环，先报"不存在"更贴近用户认知。

---

## 4. 组件 CRUD —— 第二阶段（随 S-8 删列）

> 🚨 S-8 触 `CLAUDE.md` §3.2 红线，**未获用户单独批准前，本节一律不实施**。

| 端点 | 变更 |
|---|---|
| `POST /api/cpq/components` | 请求体移除 `tabType` |
| `PUT /api/cpq/components/{id}` | 同上 |
| `GET /api/cpq/components/{id}` | 响应移除 `tabType`；如需展示，改由 `builder_config.tabType` 推导 |
| `GET /api/cpq/components` | 列表响应移除 `tabType` |
| 组件导入 / 导出 | `ComponentExportBundle` 移除 `tabType` 字段；**导入时遇到旧包中的该字段静默忽略，不报错**（向后兼容） |

**第一阶段（本期主体）**：上述端点**保持原样返回 `tabType`**，但后端不再消费它做任何判定 —— 让系统在「列还在但没人读」的状态下先跑通验证。

---

## 5. 明确不变更的契约

| 接口 / 结构 | 为什么列在这里 |
|---|---|
| `BuilderConfig` 的 `tabType` / `variantKey` 字段名 | §0 已述，内部坐标 |
| `component.builder_config` JSONB 形态 | 存量零迁移 |
| `snapshot_rows` 中 `__nodeType` 的键名与取值域 | 只改判定来源，不改产物 |
| `ds_quote_material_bom.output_material_type` | 用户业务字段，本次一列不动、一值不改 |
| `template_component_snapshot.tab_type` | 冻结快照，114 行历史值保留（A0-3） |
| 核价侧全部端点 | 核价侧无加叶子；收缩只影响其配置器下拉与 6 行中的 4 行停用 |

---

## 6. 回写 `dev-docs/main-api.md`

按 `task-docs.md §2.5`：本次改动涉及 `field-tree` 响应结构与 `add-leaf` 错误码，**属契约变更**，测试完成后、合并 master 之前必须回写总账，来源标记：

```
> 来源任务：`task-260904-页签类型收缩`｜回写日期：<合并当日>
```
