# 测试方案与 AC 追溯矩阵

## 测试分片

| 分片 | 写入面 | 覆盖 |
|---|---|---|
| S-API | 仅测试库内新增语义图夹具/组件，使用唯一前缀并在测试结束清理自身数据 | AC-1、AC-2、AC-3、AC-4 |
| S-UI | 只读字段树/编译响应与前端类型检查 | AC-1、AC-2、AC-4、AC-5 |

## 用例

| 用例 | AC | 断言 |
|---|---|---|
| T-1 | AC-1 | `SELF_PROCESS_FEE` 主组字段中 `operation_no` 后一个字段为 `工序名称`，source column 为 process lookup 的 `process_name`。 |
| T-2 | AC-2 | 编译 SQL 含 `LEFT JOIN process_master`、`process_no = operation_no` 和 `process_name` 投影。 |
| T-3 | AC-3 | 未命中工序主数据时仍返回左表行，工序名称为空。 |
| T-4 | AC-4 | 未选工序名称的存量配置前后字段/SQL 既有列一致。 |
| T-5 | AC-5 | `mvnw` 后端目标测试、`npx tsc --noEmit` 通过；Flyway history 迁移 success=true。 |

## 证伪重点

- 反向验证新字段不是直接读 `ds_quote_self_process_fee` 的不存在列，而是由 process_master LOOKUP 边投影。
- 反向验证使用 LEFT JOIN，不因缺失工序主数据把自制加工费行过滤掉。
- 反向验证字段顺序不是按数组末尾追加。
