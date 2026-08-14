# api · repair-0814 发布冻结后 tabType 护栏误拦

> **契约变更性质**：**零新增端点、零签名变更、零请求/响应结构变更**。
> 变的只有**校验行为**：两个端点的 400 触发条件（一个收窄、一个新增），以及一处渲染期错误从静默升级为显式。
> 前端**不需要改任何调用代码**（理由见 `fronttask.md`）。

---

## A-1 `PUT /api/cpq/components/{id}` —— 组件更新（含 `tabType`）

- **鉴权**：与现状一致（管理端 admin）
- **请求 / 响应结构**：**逐字段不变**

### 变化：`tabType='BOM'` 的 400 触发条件**收窄**

| | 改动前 | 改动后 |
|---|---|---|
| 触发条件 | 组件被**任意一张** `template_kind='COSTING'` 的模板引用（不问 status、不问是否冻结） | 组件被**尚未冻结**的 COSTING 模板引用，即 `status ∉ {PUBLISHED, ARCHIVED}`，**或** status 是这两者但 `template_component_snapshot` 零行 |
| HTTP | 400 | 400（条件成立时）/ **200（原先误拦的场景）** |

### 错误响应

**文案必须是多行（含 `\n`）**，每张冲突模板占一行：

```json
{
  "code": 400,
  "message": "该组件被以下尚未冻结的核价(COSTING)模板引用，不能设为 BOM 树页签：\n  · 核价模板X v1.0（DRAFT）\n这些模板渲染时直接读取组件活配置，改为树页签会立即改变它们的渲染方式。\n（已发布并已冻结的核价模板不受影响，故不在此列。）"
}
```

> 📌 **为什么必须多行（实测依据，非风格偏好）**：`ComponentManagement.tsx:49-62` 的 `showSaveError`
> 按 `msg.includes('\n')` 分流 —— **单行**走 `message.error`（3s 自动消失、换行被折叠），
> **多行**走 `notification.error`（`duration: 0` 常驻 + `white-space: pre-wrap`）。
> 本文案要求用户「去看是哪张模板、去处理它」，属于典型的「来不及照着改」场景，正是该 helper 为之而写。
> 采用多行即可**零前端改动**获得常驻可读提示。组件保存路径确认走此 helper：`ComponentManagement.tsx:1477`
> （该处注释原文即点名 task-0721 这条护栏：「后端 400（如"组件已被核价模板引用，无法设为 BOM 类型"）须完整展示，不吞成通用「保存失败」」）。

> ⚠️ **旧文案作废**：`该组件已被 N 处核价(COSTING)模板引用，不能设为 BOM 树页签——会把这些核价模板一并改成树渲染，破坏核价侧零回归。报价侧树页签请新建专用组件。`
> 该句在 task-0806 冻结改造后已是**错误陈述**（已冻结模板不会跟着变），故整句替换。
> **前端未对该文案做任何字符串匹配**（已 grep 实测，证据见 `fronttask.md` §2 第 3 项），替换无兼容风险。

**对应**：`问题说明.md` E-1 / E-2 / E-3，AC-1 / AC-2 / AC-3 / AC-6

---

## A-2 `POST /api/cpq/templates/{id}/publish` —— 模板发布

- **鉴权 / 请求 / 响应结构**：**全部不变**

### 变化：新增一条 400（树页签不变量）

| | 内容 |
|---|---|
| 触发 | `template_kind='COSTING'` 且**本次要冻的快照行**中 `bom_recursive_expand=true` 的数量 **> 1** |
| HTTP | 400 |
| 事务 | 断言在 `persistSnapshotRows` 之后抛出 → **同事务回滚**，快照与 `components_snapshot` 均不落库，模板停留 `DRAFT` |

```json
{
  "code": 400,
  "message": "核价模板最多只能有一个 BOM 树页签，当前有 2 个：物料BOM、物料与元素BOM。请先把其中一个改为非树页签再发布。"
}
```

**这不是新业务规则** —— 同一约束早已存在于 `TemplateComponentService.addComponent`
（`validateAtMostOneTreeTab:59`，文案「一个核价模板最多一个核价树页签」），本次只是把它补到 publish 这个漏掉的入口。

> 🚫 **判据必须是状态而非 delta**：断言对象是「本次要冻的这批行」的树页签**数量**，
> **不得**与上一版快照比对 `tab_type` 变化。delta 判据会两头出错（假阳性 / 假阴性），见 `问题说明.md` §③。

**不受影响的路径**（刻意）：`archive()` 补冻、`freeze()` 首次冻结 —— 这两条是**救援路径**，
违规时只记 WARN 不拦，否则会把存量模板卡成「既不能冻结也不能编辑」。理由见 `backtask.md` §3 D-2 表。

**对应**：`问题说明.md` E-7，AC-8 / AC-9 / AC-10

---

## A-3 核价树渲染路径 —— 树页签 `$view` 缺 `parent_no`

涉及一切走 `BomTreeRenderService.render()` 的调用方（`POST /api/cpq/components/batch-expand`、核价卡片渲染、价格更新 job 等）。**端点签名与正常响应不变。**

| | 改动前 | 改动后 |
|---|---|---|
| 树页签组件 `$view` 全部行都没有 `parent_no` | 一行 `LOG.warnf`，渲染**照常返回 200**，该页签全是空行，用户无任何提示 | 抛 `BusinessException` → 按既有失败通道返回（与同文件 `failedComponents` 块口径一致） |

触发条件（**不放宽**）：`recursive && kept > 0 && missingParent == kept` —— 有行且**全部**行缺父件列。
部分行缺 `parent_no` **不触发**（那是数据问题，不是配置问题）。

**对应**：`问题说明.md` E-8，AC-11（原 `BL-0172`）

---

## 与 FR / AC 对应关系总表

| 端点 | 期望 | AC |
|---|---|---|
| A-1 | E-1 放行已冻结、E-2 仍拦未冻结、E-3 文案 | AC-1 / AC-2 / AC-3 / AC-6 |
| A-2 | E-7 树页签不变量 | AC-8 / AC-9 / AC-10 |
| A-3 | E-8 `parent_no` 检出 | AC-11 |

---

## 回写 `main-api.md`

按任务平台规则 §2.4，本文件的**最终形态**（测试完成后的实际契约）须回写 `dev-docs/main-api.md`。
本次按端点分节，回写时按 `PUT /api/cpq/components/{id}` 与 `POST /api/cpq/templates/{id}/publish`
两个端点定位覆盖其「错误码」小节即可，**不涉及请求/响应结构段**。
