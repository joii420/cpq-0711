# api · repair-260910 核价树版本切换

## 结论：**接口形状零变更，语义变更 1 处**

### `GET /api/cpq/costing-orders/{coid}/version-options`

| 项 | 改动前 | 改动后 |
|---|---|---|
| 请求参数 | `lineItemId` / `componentId` / `partNo` | **不变**（🚫 不新增 `parentNo` —— 见 ⑤「已否决备选」） |
| 响应体 | `{componentId, partNo, currentVersion, options[]}` | **形状不变** |
| **语义** | 树组件：该料号**作为子件**所在边的 BOM 版本（实际查 V6 老表，恒空） | 树组件：该料号**自己那张 BOM** 的全部版本（主表+历史） |

### `POST /api/cpq/costing-orders/{coid}/version-switch`

| 项 | 改动 |
|---|---|
| 请求/响应 | **不变** |
| 新增错误 | `400` —— 传入料号没有自己的 BOM（叶子）时拒绝。既有 `403 仅待核价(PENDING)` **不变** |

## 为什么不改契约

业务模型改为「查该料号**自己那张清单**的版本」后，后端**不再需要知道它挂在谁下面** ⇒ `parentNo` 这个参数从需求上消失。这既避免了跨端契约改动（`change-protocol.md` 那套清单不触发），也顺带消灭了「一个件挂多个父件」的候选歧义。
