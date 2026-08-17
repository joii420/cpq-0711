# 接口文档 · repair-1 生产料号落库补全

> 结论:**接口契约不变**。本修复只动 DB(material_master 加列)+ 后端 handler 落库逻辑,导入端点的请求/响应结构与 task-0708 一致,对前端透明。

## 契约变更结论

| 维度 | 是否变化 | 说明 |
|------|:---:|------|
| 请求路径/方法 | ❌ 不变 | 沿用 `POST /api/cpq/basic-data-import/v6/pricing`(核价)、`/quote`(报价)、`GET /v6/{recordId}` |
| 请求参数/请求体 | ❌ 不变 | 仍是 multipart `file`(核价)/ `customerId+file`(报价) |
| 响应结构 | ❌ 不变 | 仍是 `ImportResultDTO` / 轮询 Map,无新增字段 |
| 落库结果(DB) | ✅ 变化 | production_no 现落到 material_master + 各表主行 + material_customer_map(详见 backtask) |

> 现有端点定义见上级 `../api.md`,本修复不改其中任何一条,故不重复。QA 复验仍走原端点上传新文件。
