# 接口文档 · repair-2(报价单)材质料号不入料号表

> 结论:**接口契约不变。** 本修复只动后端 handler / 版本化写入器 / 渲染视图,导入端点的请求/响应结构不变,对前端透明。

## 契约变更结论

| 维度 | 是否变化 | 说明 |
|------|:---:|------|
| 请求路径/方法/参数 | ❌ 不变 | 沿用 `POST /api/cpq/basic-data-import/v6/quote`(multipart `customerId+file`) |
| 响应结构 | ❌ 不变 | 仍是 `ImportResultDTO` / 轮询 Map |
| 落库结果(DB) | ✅ 变化 | 材质料号不再进 material_master;material_bom_item 材质料号行 characteristic=RECIPE、component_no=原始材质料号 |
| 渲染视图输出 | ✅ 变化 | 组合产品视图对材质料号行改读 material_recipe 兜底品名/规格(字段名不变) |

> 现有端点定义见上级 `../api.md`;本修复不改任何端点契约,不重复。QA 复验走原端点上传报价文件。
