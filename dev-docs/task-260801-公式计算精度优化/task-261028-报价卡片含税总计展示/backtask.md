# 后端任务：报价卡片含税总计展示

## 结论

本任务后端业务实现、数据库和 API 契约均为零改动。含税金额是前端在卡片最终呈现边界使用已返回的产品小计和冻结 VAT 快照派生的文本，不是新的报价事实字段。

## 任务分解

| 编号 | 服务的 AC | 内容 |
|---|---|---|
| B-1 | AC-5、AC-6、AC-8 | 只读审核后端契约与变更清单：确认 `cpq-backend/src/main/**`、Flyway 迁移、Dockerfile 和后端测试代码均无本任务改动；对前端截取的保存前后 `GET`/`PUT` 样本做字段级 diff，确认不新增含税金额字段，原产品小计、VAT 快照和核价响应形状不变。 |

## 为什么不改后端

- 新文本不被报价总额、折扣、税额、导出或提交消费，不应成为 DTO/实体/快照字段。
- 现有报价行已返回 `vatRateRatio` / `vatRate` 和产品小计来源，前端具备无损 Decimal 计算能力。
- 历史报价的冻结税率不随主数据更新；后端重算/回填会破坏已确认的冻结语义。
- 税率缺失/损坏是呈现降级，不授权前端或后端回查当前产品主数据替换历史事实。

## 回归确认清单

- `git diff -- cpq-backend/src/main cpq-backend/src/test cpq-backend/src/main/resources/db/migration Dockerfile` 对本任务为空。
- `GET /api/cpq/quotations/{id}` 改造前后字段集合相同，`vatRate` / `vatRateRatio` / 小计 decimal string 逐字相同。
- 保存无关文本时的请求体不包含 `vatInclusiveTotal`、`taxInclusiveSubtotal` 或任何新增同义字段。
- 后端无新增 SQL/Flyway，因此部署 SQL 基线不变；未收到“打包/部署”指令时不更新 `deploy/`。

## 二期触发条件

仅当用户后续要求下列任一事项时，才重新评估后端与 API：

- 税后金额参与报价单级总计、折扣、导出、邮件或审批。
- 需要保存税前/税后双金额，或对历史单执行回填/重算。
- 要求后端提供已格式化的含税文本或新增税额字段。

## 后端自检

- 运行定向 `git diff` 与 API 样本字段 diff，将原始输出写入 `test-report.md`。
- 不启动不必要的迁移或后端全量测试；如前端 E2E 需要后端，使用本 worktree 的临时端口与隔离库，不把共享服务当作本分支证据。
