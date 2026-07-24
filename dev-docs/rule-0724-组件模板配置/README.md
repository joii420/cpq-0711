# 组件模板配置规则（rule-0724）

> **权威配置规则总入口**（2026-07-24 梳理）。报价 / 核价两侧组件模板配置的唯一权威文档集。
> 取代散落的 `docs/配置方法论-合并版.md` / `docs/rule/报价模板生成规则.md` / `docs/核价树页签组件配置指南.md` / `docs/核价SQL配置手册.md`（迁移后删除，见文末）。

## 阅读路径

1. **先读共性**（`1`~`5`，两侧都适用）
2. **再读对应侧 delta**：配报价模板读 `报价侧.md`；配核价模板读 `核价侧.md`
3. 卡壳查 `附录-速查.md`（6 维度对照 + 常见坑 + V6 表映射）

## 文件结构

| 文件 | 内容 | 谁读 |
|---|---|---|
| `1-总则与工作流.md` | 4 问工作流 + 料号列绑定值铁律 + 交付方式 | 两侧 |
| `2-组件与字段.md` | 组件模型 / field_type / default_source / rowKeyFields / `_` 前缀别名 | 两侧 |
| `3-SQL视图.md` | `$view` 机制 / hf_part_no / 禁表 / 中文别名 / 缓存 key / pending 改写坑 | 两侧 |
| `4-页签属性与树.md` | tabType / partNoField / partNameField / sort_field / 树契约 | 两侧 |
| `5-公式与Excel列.md` | 公式引擎 / 字段类型联动(AP-44) / Excel 列 | 两侧 |
| `报价侧.md` | `:customerCode` / material_no 料号键 / 树可选 / V6 映射 / 组件配方大全 / ConfigureSnapshotService | 报价 |
| `核价侧.md` | `:versionFilter` 版本 / `_GLOBAL_` / 树主轴 spine / 生产料号桥接 / CardSnapshotService | 核价 |
| `附录-速查.md` | 6 维度对照表 + 常见坑速查 + V6 现役表映射 | 两侧 |

## 「我要配 X」决策树

```
配一套客户模板
├─ 先按客户 Excel 推出「组件 + 字段」 …………… 2-组件与字段
├─ 逐组件 4 问用户勾选(类型/料号列/料号名称列/行键) … 1-总则 §工作流
├─ 写每组件 $view ……………………………………… 3-SQL视图 + (报价侧/核价侧 契约)
│   ├─ 报价：hf_part_no + :customerCode 平铺，或 BOM 树契约
│   └─ 核价：hf_part_no + :versionFilter，_GLOBAL_ 定价，树主轴 spine
├─ 页签属性(tabType/partNoField/sort_field) …… 4-页签属性与树
└─ 公式列 / Excel 列 ………………………………… 5-公式与Excel列
```

## 报价 vs 核价 6 维度对照（速查，详见 附录-速查.md）

| 维度 | 报价侧 | 核价侧 |
|---|---|---|
| 客户口径 | `:customerCode` 按客户收窄 | 无 customerCode，`_GLOBAL_` 全局成本 |
| 版本 | 无（提交即冻结） | `:versionFilter` 宏，每 sheet 独立版本可切 |
| 树 | 可选（tabType=BOM） | 核心主轴（物料BOM，spine 全节点） |
| 料号键 | `material_no`（销售料号） | 销售料号 JOIN `material_master`→生产料号 |
| 渲染服务 | `ConfigureSnapshotService` | `CardSnapshotService` + `BomTreeRenderService` |
| 页签属性使用 | 已用（罗克韦尔 5 tabType + 5 sort_field） | 目前空（机制在，未用） |

## 关联文档（支撑机制，留在 `docs/`，不搬迁）

- 全局变量：`docs/全局变量使用指南.md`
- 数据源类型扩展（Resolver SPI）：`docs/数据源类型扩展指南.md`
- HTTP_API 安全：`docs/HTTP_API_安全配置.md`
- 配置中心架构（三层模型 / snapshot 同步 / datasource_field token）：`docs/配置中心架构.md`
- 三大核心模块基线🔒 / 统一智能视图路径方案 / 报价单核价单功能总结（架构/设计/功能，非配置规则）

## 迁移待办

- [ ] 填充 `1`~`5` + `报价侧` + `核价侧` + `附录`（内容来源见各文件头「来源」标注）
- [ ] 删除旧文档：`docs/配置方法论-合并版.md`、`docs/rule/报价模板生成规则.md`、`docs/核价树页签组件配置指南.md`、`docs/核价SQL配置手册.md`（+ 空的 `docs/rule/`）
- [ ] **更新 `CLAUDE.md`「Key Documents」**：把指向上述 4 份的引用改到本目录（否则新会话按图索骥 404）
