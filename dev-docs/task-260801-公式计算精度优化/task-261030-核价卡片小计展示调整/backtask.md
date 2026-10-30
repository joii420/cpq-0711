# 后端任务：核价卡片小计展示调整

## 结论

本任务后端业务实现、数据库和 API 契约均为**零改动**。核价侧的含税金额、外币金额与「无数据」判定都是前端在卡片最终呈现边界、用已返回的字段派生的文本，不是新的业务事实字段。

## 任务分解

| 编号 | 服务的 AC | 内容 |
|---|---|---|
| B-1 | AC-4、AC-7、AC-9 | **只读**审核后端契约并产出 S-FLOW 夹具的字段依据：① 从后端 DTO / 序列化代码确认报价查询响应中「报价币种、汇率、核价卡片结构、报价行 `subtotal`、`vatRate`、`vatRateRatio`、`costingCardValues`（含生成失败标记 `__cardValueFailed` / `__errorMsg`）」的**真实字段名、类型与空值形态**，以及「没有核价结构」时 `costingCardStructure` 的真实取值（`null` / 缺省 / 空 tabs），写成 `backend-audit.md` 的字段样本表，供测试员造 mock 夹具时对齐；② 确认 `quotation_line_item` 只有一个 `subtotal` 列且为报价侧小计（立项时本机库已实查列清单，复核即可）；③ 确认 `cpq-backend/src/main/**`、Flyway 迁移、Dockerfile、后端测试代码对本任务的 `git diff` 为空。 |

## 为什么不改后端

- 用户要的是「核价侧没有数据就显示 `-`」，不是让后端补一个核价小计字段；前端已能从卡片主体状态判断「没有核价数据」。
- 含税 / 外币文本不参与报价总额、折扣、税额、导出或提交，不应成为 DTO / 实体 / 快照字段。
- 报价行已返回冻结的 `vatRateRatio` / `vatRate`、报价单已返回币种与汇率，前端具备无损 Decimal 计算能力。

## 回归确认清单

- `git diff -- cpq-backend/src/main cpq-backend/src/test cpq-backend/src/main/resources/db/migration Dockerfile` 对本任务为空。
- 报价查询响应字段集合改造前后相同；保存请求体不含新派生字段。
- 无新增 SQL / Flyway，部署 SQL 基线不变；未收到「打包 / 部署」指令时不更新 `deploy/`。

## 二期触发条件

仅当用户后续要求下列任一事项时，才重新评估后端与 API：

- 核价侧需要持久化自己的产品小计（独立于报价行 `subtotal`），或导出 / 汇总 / 审批要用核价含税金额。
- 核价管理页「核价单据总价」改口径或改币种写法需要后端格式化。

## 后端自检

- 定向 `git diff` 与字段样本表原始依据（文件:行）写入 `backend-audit.md`。
- 🚫 不启动迁移、不连共享库、不改任何后端文件；遇 `CLAUDE.md §3.2` 红线停下报告。
