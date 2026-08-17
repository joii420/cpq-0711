# API 契约 —— repair-0803 公式 SUM 内引用宿主页签字段

- 立项日期：2026-08-03
- 关联：`需求文档.md` FR-8 ~ FR-12
- **本次无新增 / 删除端点**，仅**既有端点的错误响应新增可选 `data` 载荷**

---

## 0. 总则

| 项 | 规定 |
|---|---|
| 响应包封 | 沿用既有 `ApiResponse{ code, message, data }`，**不新增包封层、不改字段名** |
| 载荷位置 | 结构化环信息放 `data`，与 `RowKeyConflictException`（`data.conflicts`）/ `TreeConflictException`（`data.conflictTabs`）同型 |
| 兼容性 | `data` 是既有可选字段；不消费它的前端路径行为不变（仍可只读 `message` 展示） |
| 铁律 | `data` 内所有面向用户的标识**一律为名称**（组件名称 / 公式名称 / 字段名称），**不得出现 UUID 或字段 id** |

---

## 1. 错误载荷结构（`data`）—— 三个端点共用

```jsonc
{
  "errorType": "FORMULA_CYCLE",      // 固定值，前端据此判定走环链路抽屉
  "cycles": [                         // 每个元素 = 一个独立的环
    {
      "scope": "FIELD",               // FIELD=同组件内字段环 | TAB=跨页签组件环
      "componentName": "物料",         // scope=FIELD 时存在：环所在组件名
      "nodes": [                      // 环上的节点，按链路顺序，首尾不重复
        { "componentName": "物料", "fieldName": "原材料成本", "formulaName": "v2-原材料成本公式(银点类)" },
        { "componentName": "物料", "fieldName": "来料加工费", "formulaName": "来料加工费取值公式" }
      ],
      "edges": [                      // 边数 = 节点数（环闭合，最后一条回到首节点）
        {
          "fromField": "原材料成本",
          "toField": "来料加工费",
          "viaFormulaName": "v2-原材料成本公式(银点类)",
          "viaDesc": "公式"            // 人话来源描述，见下表
        },
        {
          "fromField": "来料加工费",
          "toField": "原材料成本",
          "viaFormulaName": "来料加工费取值公式",
          "viaDesc": "公式"
        }
      ]
    },
    {
      "scope": "TAB",
      "nodes": [
        { "componentName": "产品", "formulaName": "管理费" },
        { "componentName": "物料", "formulaName": "v2-原材料成本公式(银点类)" }
      ],
      "edges": [
        { "fromComponentName": "产品", "toComponentName": "物料",
          "viaFormulaName": "管理费", "viaDesc": "跨页签引用 [材料成本]" },
        { "fromComponentName": "物料", "toComponentName": "产品",
          "viaFormulaName": "v2-原材料成本公式(银点类)", "viaDesc": "跨页签引用 [税率]" }
      ]
    }
  ]
}
```

### 字段说明

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `errorType` | string | ✅ | 固定 `"FORMULA_CYCLE"` |
| `cycles[].scope` | string | ✅ | `FIELD` / `TAB` |
| `cycles[].componentName` | string | `scope=FIELD` 时 ✅ | 环所在组件名称 |
| `cycles[].nodes[]` | array | ✅ | 按链路顺序；**首尾不重复**（前端渲染时自行闭合回首节点） |
| `nodes[].componentName` | string | ✅ | 组件（页签）名称 |
| `nodes[].fieldName` | string | `scope=FIELD` 时 ✅ | 字段名称 |
| `nodes[].formulaName` | string | ⭕ | 该节点当前绑定的公式名；解析不到时省略 |
| `cycles[].edges[]` | array | ✅ | 边数 = 节点数（含闭合边） |
| `edges[].viaDesc` | string | ✅ | 来源描述，沿用后端 `DepEdge.via` 既有措辞 |

### `viaDesc` 取值来源（沿用现有 `buildFormulaDepEdges` 措辞，不新造）

| 场景 | 文案 |
|---|---|
| 普通公式引用 | `公式「<公式名>」` |
| 条件公式某规则命中的公式 | `条件规则<N>命中的公式「<公式名>」` |
| 条件公式的判断条件里引用 | `条件规则<N>的判断条件` |
| 条件公式默认分支 | `条件默认公式「<公式名>」` |
| 跨页签引用（`scope=TAB`） | `跨页签引用 [<被引用列名>]` |

---

## 2. 受影响端点

### 2.1 `POST /api/cpq/components` —— 创建组件

- **鉴权**：登录态（Session Cookie）
- **请求**：不变（`CreateComponentRequest`）
- **成功响应**：不变
- **变更点**：当 `fields` / `formulas` 存在字段级循环引用时，`400` 响应的 `data` 携带 §1 载荷（`scope=FIELD`）
- 对应：FR-8 / FR-9 / FR-10

### 2.2 `PUT /api/cpq/components/{id}` —— 更新组件

- 同 2.1
- 对应：FR-8 / FR-9 / FR-10

### 2.3 `POST /api/cpq/templates/{id}/publish` —— 发布模板

- **鉴权**：登录态
- **请求**：不变
- **成功响应**：不变
- **变更点**：当卡片内组件间存在页签级循环引用时，`400` 响应的 `data` 携带 §1 载荷（`scope=TAB`）
- 对应：FR-11

### 2.4 渲染期错误（**非端点契约变更**）

`CrossTabComponentOrder.topoOrder` 抛出的文案由

```
页签公式存在循环引用: [56c8a517-e770-4429-82c7-72f216daab45, 74c0cede-094e-478c-a8fe-8f0028d538cd]
```

改为

```
页签公式存在循环引用: 产品 → 物料 → 产品
```

该消息经 `CardSnapshotService:1280` 包装成 `卡片渲染失败: …` 落进卡片值，**不弹抽屉**（渲染期错误就地显示在卡片上）。对应 FR-12。

---

## 3. 错误码

| code | 场景 | `data.errorType` |
|---|---|---|
| 400 | 公式循环引用（字段级 / 页签级） | `FORMULA_CYCLE` |
| 400 | 其它既有校验失败 | 无 `data` 或既有 `data`（`conflicts` / `conflictTabs` 等），**不受本次影响** |

---

## 4. 前端消费约定

```ts
// 判定：只认 errorType，不靠 message 文本匹配
const data = err?.response?.data?.data;
if (data?.errorType === 'FORMULA_CYCLE') {
  openFormulaCycleDrawer(data.cycles);   // 走抽屉
} else {
  showSaveError(err?.response?.data?.message);  // 走既有路径（message / notification）
}
```

⚠️ **禁止**用 `message.includes('循环引用')` 这类文本匹配判定 —— 文案会变，`errorType` 才是契约。

---

## 5. `main-api.md` 回写

本次**改动了 §2.1 / 2.2 / 2.3 三个端点的错误响应结构**（新增 `data` 载荷），属契约变更，**需要回写**。

- 时机：`test-report.md` 产出、缺陷闭环后，合并 master 之前
- 粒度：上述三个端点各自的小节
- 标记：`> 来源任务：\`repair-260803-公式SUM内引用宿主页签字段\`｜回写日期：<实际日期>`
