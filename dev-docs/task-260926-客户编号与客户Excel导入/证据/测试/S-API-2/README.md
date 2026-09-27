# S-API-2 · Excel 导入测试夹具

本目录只保存可复核的离线夹具与黑盒测试脚本，不依赖实现代码，也不清理数据库。

## 生成夹具

```bash
python3 generate_fixtures.py /tmp/imp-260926-sapi2-fixtures
```

生成文件：

| 文件 | 目标 AC |
| --- | --- |
| `valid_two_rows.xlsx` | AC-8：一行手填编号、一行空编号的合法导入 |
| `existing_code_mix.xlsx` | AC-9：既有编号冲突时整份拒绝 |
| `duplicate_in_file.xlsx` | AC-11：编号/名称大小写规范化后文件内重复 |
| `multiple_errors.xlsx` | AC-12：必填、电话、等级、行业、分类、长度等多错误聚合 |
| `wrong_header.xlsx` | AC-13：表头不匹配 |
| `damaged.xlsx` | AC-13：损坏文件 |
| `not_xlsx.xls` | AC-13：非 `.xlsx` |
| `header_only.xlsx` | AC-14：仅表头，0/0/0 |

所有业务数据使用 `IMP-260926-` 私有前缀。夹具脚本不发请求、不写数据库。

## 执行口径

实现完成后，在隔离的 test profile 服务上执行 API 黑盒验证。服务地址和认证令牌通过环境变量提供：

```bash
export CPQ_BASE_URL=http://localhost:8081/api/cpq
export CPQ_AUTH_TOKEN='…'
python3 contract_runner.py --fixtures /tmp/imp-260926-sapi2-fixtures
```

若服务尚未启动、令牌不可用或服务连接的不是 test profile，测试应记录为环境阻塞，不得记为业务通过。脚本不执行 DELETE、不清理共享数据；所有创建数据均使用本分片前缀。
