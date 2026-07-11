# 前端任务 · repair-2(报价单)材质料号不入料号表

> 结论:**本次前端零代码改动。**

## 为什么不改前端

本修复全部在后端与 DB/视图层:handler(材质料号不登记 material_master、characteristic=RECIPE)+ 版本化写入器(characteristic per-component)+ 渲染视图兜底(材质料号行改读 material_recipe)。
1. 导入文件驱动,前端只上传 Excel;
2. 接口契约不变(见 api.md);
3. 渲染改的是后端 SQL 视图,前端读同样的字段(品名/规格),视图兜底 material_recipe 后前端无感;
4. 验收在 DB + 渲染视图输出层。

## 交付物
- ✅ 无代码改动。
- ✅ 本文档作为"前端已评估、无需改动"的留痕。
