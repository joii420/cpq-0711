# api · 接口契约

> 本次**不新增端点、不删端点、不改路径与方法**。唯一变更：`builder` 保存请求的列对象**开始携带已存在的可选字段 `fieldType`**，并对其加白名单校验。
> 🚨 前后端两路子代理**只以本文件为准**。开工后本文件若变更，主线会显式通知两侧。

---

## §1 `fieldType`（列对象的可选字段）

### 1.1 它已经存在，只是没人发

| 端 | 现状 |
|---|---|
| 后端 DTO | `BuilderConfig.java:53` `public String fieldType;` —— **已存在** |
| 后端消费 | `BuilderService.java:881` `col.fieldType != null ? col.fieldType : <按数据类型推>` —— **已消费** |
| 前端模型 | `SqlViewBuilderTab.tsx:164` `fieldType: string` —— **已存在**（从后端回填，供刷新回填） |
| 前端发送 | 🚫 **不发**（`sqlViewBuilderService.ts:17` 注释：「不传 viewColumn/fieldType」） |

⇒ 本次改的是「前端开始发」+「后端开始校验」，**DTO 结构零变更**。

### 1.2 值域（跨端必须一致）

| 值 | 中文标签 | 落库后的绑定键 |
|---|---|---|
| `BASIC_DATA` | 基础数据 | **顶层 `basic_data_path`**（平铺字符串） |
| `INPUT_TEXT` | 文本输入 | `default_source.path`（嵌套对象，`type: "BASIC_DATA"`） |
| `INPUT_NUMBER` | 数字输入 | 同上 |

🚫 **不接受**：`FORMULA` / `DATA_SOURCE` / `FIXED_VALUE` / 任何其它值。
理由：它们分别需要 `formula_id` / `binding` / `content`，**配置器不收集这些**，允许它们只会产出必然坏的字段。

🚫 **绑定键的分派规则本次不动**（`D-73/B-30`：绑定键跟 `field_type` 走，不跟报价/核价侧走）。

### 1.3 不传时的默认值（按数据集方言）

| `dialect` | 默认 `field_type` |
|---|---|
| `QUOTE` | 按列的 `resolvedDataType` 推：`TEXT` → `INPUT_TEXT`，其余 → `INPUT_NUMBER`（**现状逐位不变**） |
| `COST_BASIC` | **`BASIC_DATA`** |
| `COST_DETAIL` | **`BASIC_DATA`** |

- **显式传值恒优先于默认**
- **不传 / null → 走默认，不报错**（向后兼容，旧客户端不碎）

### 1.4 校验与错误

| 情形 | 状态码 | message |
|---|---|---|
| 合法三值之一 | 200 | — |
| `FORMULA` / `DATA_SOURCE` / `FIXED_VALUE` / 任意非法值 | **400** | `非法 fieldType: <值>，合法值：BASIC_DATA / INPUT_TEXT / INPUT_NUMBER` |
| 不传 / null | 200 | 走 §1.3 默认 |

🚫 **校验失败时不得有任何落库** —— 该组件的字段必须与请求前逐字相同。

### 1.5 请求体片段（其余字段一律不变）

```jsonc
{
  "columns": [
    {
      "sourceNodeKey": "…", "sourceColumn": "component_qty",
      "fieldName": "组成用量",
      "fieldType": "BASIC_DATA",        // ← 本次新发；可选；不传走方言默认
      "dataType": "NUMBER",
      "isAmount": false, "inSubtotal": false,
      "roles": []
      // 🚫 viewColumn 仍然不传（后端纯函数生成，只读展示）
    }
  ]
}
```

---

## §1.6 🔑 回填一致性（2026-09-09 开工后并入，用户裁决）

**问题**：`builder_config.columns[].fieldType` 存的是**客户端发来的原值**，而 `component.fields[].field_type`
是**推导后的有效值**。存量实测 **42 个组件 / 344 字段**的 `builder_config.fieldType` **全为 `null`**
（前端此前从不发送），而库里真实是 `INPUT_TEXT` / `INPUT_NUMBER`。

⇒ 前端一旦开始发送（§1.1），「打开存量组件 → 什么都不改 → 保存」会把**兜底值**写进 `field_type`，
**静默损坏 42 个存量组件**（NUMBER 列降级为 TEXT，或核价侧被改成 `BASIC_DATA`）。

### 契约

| 路径 | 规定 |
|---|---|
| **`GET /builder`（回读）** | `builder_config.columns[].fieldType` 为空/null 时，**回填 `component.fields[]` 里同名字段的真实 `field_type`** 再返回。匹配键是**字段名**，🚫 不是下标。读回的值**可能不在 3 值白名单内**（实测有 `FORMULA`）⇒ **原样返回，不过滤不报错** |
| **`POST/PUT` 保存** | 落 `builder_config` 之前把 **effective 值**回填进 `columns[].fieldType`，使其之后不再为空 |

⇒ **不变量：界面回填显示的 `fieldType` == 实际生效的 `component.fields[].field_type`。**

📌 「产物并进 `builder_config`」是既有做法（`viewColumn` / `axisScope` 同款），不是新模式。

⚠️ **副作用（知情）**：本项会让 `QUOTE` 侧 `builder_config` 的字节变化（`null` → 有值）。
`AC-5` 的「逐位一致」断言对象是 **`component.fields[]`**，不是 `builder_config` ⇒ 不冲突。

---

## §2 无契约变更但受影响的读端点（供测试取证）

| 端点 | 说明 |
|---|---|
| `GET /builder`（已保存配置回读） | 返回体里的 `fieldType` 是 **AC-11 刷新回填**的取证口。**本次不改其形状** |
| 组件详情 / 模板快照 | `component.fields[].field_type` 与绑定键位置是 AC-3/4/5 的落库取证口 |

🚫 以上端点**本次不改任何契约**，列出只为让测试知道去哪里取证。
