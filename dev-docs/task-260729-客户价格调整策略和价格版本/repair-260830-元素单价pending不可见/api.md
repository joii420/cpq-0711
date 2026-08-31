# api · repair-260830 元素单价 pending 不可见

## 结论：接口契约零变更

**本次不新增、不修改、不删除任何 HTTP 端点。**

## 判定依据

| 维度 | 结论 |
|---|---|
| **端点** | 无增删改。渲染仍走既有的报价单渲染 / driver expand 链路 |
| **请求参数** | 无变化。`:pq`（pending 报价单 id）是**服务端内部**从 `SqlViewRuntimeContext` 取的运行时上下文（`SqlViewExecutor.injectPendingParam`），**从不经由 HTTP 传入**，前端无感知 |
| **响应结构** | 无变化。`snapshot_rows[].driverRow.元素单价` 与 `basicDataValues["{$mc_view.元素单价}"]` 两个 key **形态不变**，仅值从 `null` 变为有值 |
| **错误码** | 无新增。改写失败沿用既有**安全降级**（返回原模板 + `LOG.warnf`），不产生新的 HTTP 错误 |
| **数据库函数签名** | `f_material_element_price` 新增三参重载；**两参版签名保持不变**（`CREATE OR REPLACE` 委托），老调用方不受影响。这是**库内契约**，不属 HTTP 契约 |

## `main-api.md` 回写

**不需要回写。**

依据 `task-docs.md §2.5`：「只改了实现、没改契约（方法/路径/参数/响应/错误码全未变）的任务可跳过回写」。
本次符合该条件，将在 `test-report.md` 中写明「本次无契约变更，无需回写 `main-api.md`」。

## 内部契约变更登记（非 HTTP，但需记录）

| 项 | 变更 |
|---|---|
| `f_material_element_price` | 新增重载 `(text, date, uuid)`；两参版改为委托调用，**签名与返回列逐字不变** |
| `QuotePendingRewriter` | 改写范围从「白名单表 token」**扩展到「数据库函数调用补参」**。这是 pending 可见性协议的覆盖范围扩大，属**协议级改动**，结案时提议晋升为 `change-protocol.md` 规则 |
