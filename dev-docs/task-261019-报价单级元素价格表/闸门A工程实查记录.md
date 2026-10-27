# 闸门 A 工程实查记录

> 实查日期：2026-10-26。本文只记录当前代码/数据库事实以及能由已确认业务规则唯一推出的实现约束；存在两种以上合理做法的事项不在本文预先写死，须经用户裁决后再回填正式契约。

## 1. 数据规模、存储与迁移基线

对开发库 `cpq_db_0724` 使用只读事务实查：

| 指标 | 结果 |
|---|---:|
| 报价单总数 / 非空报价单数 | 226 / 186 |
| 单报价产品数 P50 / P95 / P99 / 最大值 | 1 / 8 / 1845 / 1845 |
| 单报价四类现存 JSON 总体积 P50 / P95 / P99 / 最大值 | 4,359 B / 37,189 B / 1,935,405 B / 5,310,177 B |
| 报价 `_record` 单据内不同元素最大值 | 4 |
| 元素主档总数 / 报价数据出现的不同元素数 | 49 / 5 |
| `quotation_line_item` 四类 JSON 总有效载荷 | 9,100,650 B |
| `quotation_line_component_data` 的 `row_data + snapshot_rows` 总有效载荷 | 14,683,642 B |
| 当前代码与开发库最新成功迁移 | V452 |

P99 被三张明确标为性能测试的大单拉高：1845 行两张、1200 行一张；它们仍是系统当前可打开的数据，不能在没有显式产品裁决时把 1845 行能力静默降级。

现有历史实测可作为对照，不冒充本任务尚未实现的投影性能：1845 行报价保存曾实测 8.1～10.3 秒；完整 DTO 曾达到约 24.6 MB、读取约 22 秒；价格调整优化后的 1200/1845 行单次相关处理约 0.79～1.11 秒。新投影必须分页读取，不能复制现有 24.6 MB 单响应形态。

用户于 2026-10-26 裁决采用“兼容现有大单基线”：正式支持单张报价单 2000 个产品、64 个不同价格元素；异步任务受理 P95 ≤ 1 秒，8 产品普通单投影 P95 ≤ 5 秒，2000 产品大单投影 P95 ≤ 60 秒，分页首屏普通单 P95 ≤ 2 秒、大单 P95 ≤ 5 秒。明细强制分页，不得以巨型响应规避；完整测量方法见 `test.md §3.6`。该项已回写 AC-35，不再是闸门阻塞项。

## 2. 前后端计算与哈希现状

已确认两端共同事实：

- 后端 `PrecisionPolicy` 与前端 `utils/precision.ts` 均为计算 12 位、显示最多 9 位、`HALF_UP`；元素单价单独在写入前舍入 9 位。
- 两端都拒绝用二进制浮点承担精度敏感计算：后端使用 `BigDecimal`，前端使用 `decimal.js`，接口金额/价格使用十进制字符串。
- 除零和普通公式非法/缺失值沿用既有公式兼容语义返回 0；元素价格缺失不适用该降级，必须在进入金额计算前形成阻断错误。
- 当前工程有多套局部 SHA-256/稳定字符串实现，但没有一份可供前端与后端共同执行的“投影规范 JSON + 哈希”契约。前端 `draftLineDiff.stableStringify` 只服务草稿差异，后端回填 token 也只服务各自链路，均不能直接冒充本任务协议。

用户于 2026-10-26 裁决采用方案 A：版本化 `CPQ-JCS-1`，精度敏感数字规范为十进制字符串，各数组先按契约稳定键排序，再执行 RFC 8785 JCS；使用 UTF-8、输入/结果独立域前缀与 SHA-256，协议版本随投影冻结，前后端各自实现并共用语言无关黄金夹具。完整契约已回写 `api.md §1.1`、AC-11 和测试方案，该项不再是闸门阻塞项。

## 3. 复制链路实查

当前 `QuotationService.copy` 的实际行为与本任务目标相反：

- 同模板复制会继承报价头金额、税额、折扣结果、四类行缓存、Excel 快照、组件 `row_data/snapshot_rows/subtotal`，并显式跳过重算。
- 跨模板复制只按字段名迁移目标模板的 `INPUT_TEXT/INPUT_NUMBER`，公式和数据源重新计算。
- 当前接口只有可选 `templateId`，没有目标客户、价格基准日、复制状态和重新取价任务契约。
- `ensureStructure` 在复制事务提交后“尽力而为”，异常会被吞掉，可能返回看似成功但结构未就绪的新单。

按用户已确认的“只复制产品内容、不复制价格表”可以唯一确定以下业务白名单：

- 复制：目标客户/模板选择、允许的业务头字段、产品及父子关系、产品属性、数量/年用量、工艺选择、锁定组成版本、树增删差异、用户输入值。
- 重新解析：客户快照、价格基准日、币种/汇率、税率/VAT 版本、价格修订、策略/日历版本及所有金额。
- 禁止复制：四类卡片/Excel 结果、单价/金额/小计/折扣计算结果、价格修订、投影、提交/审批、核价绑定和调价结果。

用户于 2026-10-26 裁决采用方案 A：新增不可变、版本化计算输入快照头和按产品载荷；输入事实必须携带来源、敏感级别和 `COPY_WITH_PRODUCT/RE_RESOLVE` 策略，价格、金额、公式结果与四类缓存禁止进入。投影及报价当前/提交状态显式绑定快照；同模板复制生成目标单独立快照，跨模板再经目标 schema 映射，绝不复制整份 `snapshot_rows`。完整模型和失败分流已回写 `方案设计.md §3.2/§6.5`、AC-23～AC-25、接口和测试，该项不再是闸门阻塞项。

## 4. 核价换版与审批事务边界

### 4.1 当前事实

- `CostingVersionService.switchVersion` 直接以 `SELECT ... FOR UPDATE` 锁定 `costing_order`，随后原位 upsert `costing_order_version_override`、重算并覆盖 `costing_render/costing_total_amount`。
- 当前没有换版预览接口、一次性 token、`review_version`、价格修订绑定、目标/活动核价投影绑定，也没有 override 历史版本。
- 核价通过虽然有回填 `previewToken` 并对 `quotation` 加悲观锁，但该 token 不绑定核价 override、核价价格修订或核价投影；核价驳回甚至没有对应预览 token。

### 4.2 由既有 AC 唯一推出的目标事务

1. 新增不可变 `costing_composition_revision` 及明细，取代 SNAPSHOT 模式下原位覆盖 override；现有 `costing_order_version_override` 仅供 LEGACY 兼容读取。
2. 预览只读当前 `reviewVersion`、报价提交绑定、核价当前绑定和目标组成，生成数据库保存的一次性 token；保存 token 摘要、候选组成摘要、新增元素、基准绑定、过期时间和操作者，不修改任何正式绑定。
3. 确认请求先在短事务中锁定 `costing_order`，验证 token 未消费/未过期以及全部预期绑定；成功后只创建候选组成修订和目标任务，返回 202，不提前切换当前组成/价格/投影。
4. 后台完整构建候选价格修订和核价投影。最终激活事务再次锁定 `costing_order` 并做 CAS；只有基准 `reviewVersion` 和全部绑定仍一致时，才一次写入组成修订、应用价格修订、目标/活动核价投影并令 `reviewVersion + 1`。失败或过期只留下不可见失败证据，原绑定不变。
5. 批准/驳回同样锁定 `costing_order` 和关联 `quotation`，逐项校验请求看到的报价修订/投影、核价修订/投影、组成摘要和 `reviewVersion`；成功后冻结这些标识和摘要。任何不匹配返回 409。

数据库保存 token 是唯一满足“一次性、可过期、可审计且可跨实例消费”的现有需求形态，不采用只靠客户端携带的无状态 token。

## 5. 现有价格调整任务的接入边界

### 5.1 当前事实

- 当前任务以客户 + 料号选取“建单时间最新的活单”为判断依据；活单状态固定为 `DRAFT/SUBMITTED/APPROVED/REJECTED/COSTING_REJECTED`，同单多行按 `sort_order, id` 取第一行。
- 预算进池、审核、正式更新和启动恢复已经具备幂等状态及批量恢复，但 `VersionSupersedeGate`、`BasisQuotationLocks`、并发信号量和启动恢复都明确依赖单 JVM/单实例。
- 当前正式更新会直接改报价行及组件 JSON，并写现有 `quotation_price_revision`；这张表是“价格调整后的整单缓存版本”，不是本任务新增的元素价格修订，二者不能混用。
- 当前部署为单个 Docker Compose 应用实例；DEC-0022 已明确在替换三处进程内并发控制前禁止扩成多实例。

### 5.2 由冻结规则唯一推出的接入方式

- LEGACY 报价继续走现有五状态兼容路径，本任务不借机改写其历史语义。
- SNAPSHOT 仅对仍为 `DRAFT`、当前价格修订包含命中元素的报价创建 `PRICE_ADJUSTMENT` 新修订；`SUBMITTED/APPROVED/...` 的正式绑定绝不改写，并记录“已冻结跳过”结果。
- 在调价任务与报价修订之间增加数据库命令/发件箱，唯一键至少包含 `sourceJobId + quotationId + basePriceRevisionId`。重试命中同一命令，不重复生成修订号。
- worker 复制母修订全部明细，只替换任务命中的元素并保留人工原值、调价版本、原因和操作者；随后构建新目标投影。最终用 `DRAFT + currentRevision + userDataVersion` CAS 一次激活，过期任务标记 `SUPERSEDED`。
- 新增命令和投影 worker 使用数据库状态/CAS，不依赖现有 JVM 锁；现有价格调整模块仍维持 DEC-0022 的单实例前提，是否整体改造成多实例安全不扩入本任务。

## 6. 索引、迁移与清理边界

### 6.1 迁移基线

- 代码与开发库当前均到 V452；实现 worktree 建立后仍须按四方规则重新检查所有工作区、主仓、迁移目录和数据库历史，不能在立项文档预占 V453。
- 本任务只新增表/列/索引/约束和兼容分支，不删除现有四类 JSON、`quotation_price_revision` 或 `costing_order_version_override`。
- `quotation.user_data_version` 现为 `integer` 且已满足可预见寿命，本任务沿用，不为文档中的 `bigint` 发生无收益表重写。

### 6.2 必需索引与唯一约束

- 价格修订头：`UNIQUE(quotation_id, revision_no)`、`UNIQUE(quotation_id, idempotency_key)`；按 `quotation_id, created_at DESC` 和 `source_job_id` 查询。
- 价格修订明细：`PRIMARY KEY(revision_id, element_id)`；按 `element_id, revision_id` 反查。
- 输入快照头：`UNIQUE(quotation_id, user_data_version)`；按 `copied_from_snapshot_id` 追溯；对从未绑定的终态候选按 `status, sealed_at/completed_at` 建清理部分索引。快照行 `PRIMARY KEY(snapshot_id, quotation_line_item_id)`。
- 投影头：按 `quotation_id, scope, created_at DESC` 查历史；对待处理状态建立 `status + next_attempt_at` 部分索引；同一规范输入/幂等键唯一；对 `activated_at IS NULL` 的终态候选按 `completed_at` 建清理部分索引。
- 投影行：`PRIMARY KEY(projection_id, quotation_line_item_id)`；所有正式读取必须先解析活动投影，再按该主键批量加载。
- 投影差异：`PRIMARY KEY(projection_id, diff_no)`；按 `projection_id, quotation_line_item_id` 分页定位。
- 组成修订：`UNIQUE(costing_order_id, revision_no)`；明细 `PRIMARY KEY(composition_revision_id, component_id, part_no)`；未激活终态候选按 `completed_at` 建清理部分索引。
- 预览 token：token 摘要唯一，并按 `costing_order_id, expires_at` 与未消费状态查询。
- 审批证据：按 `costing_order_id, decided_at DESC` 查询，`evidence_digest` 唯一防止同一裁决重放。
- 调价命令/发件箱：`UNIQUE(source_job_id, quotation_id, base_price_revision_id)`，并按待处理状态/下次尝试时间索引。
- 日历：主档编码唯一；版本 `(calendar_id, version_no)` 唯一；日期 `(calendar_version_id, calendar_date)` 主键；覆盖查询按发布状态和起止日期建立组合索引。
- 策略/AI 发布对象：业务编码 + 版本号唯一；同一用途只允许一个启用发布版，以数据库部分唯一索引守住。

### 6.3 不可变与外键

- SEALED/PUBLISHED 内容由应用校验和数据库触发器共同拒绝 UPDATE/DELETE；外键引用使用 `RESTRICT`，不能级联删除历史价格、日历、策略、组成或正式投影。
- 报价/核价的当前、目标和提交绑定通过显式外键引用修订/投影；循环外键在建表后用 `ALTER TABLE` 增加，迁移必须可在空库和现有库重复验证。
- 输入快照、投影和组成候选的清理索引只是候选扫描入口；删除事务仍须重新检查 `first_business_bound_at/activated_at`、复制溯源及全部业务外键，不能仅凭部分索引结果删除。

用户于 2026-10-26 裁决采用方案 A：SEALED 修订，曾激活、被复制溯源或被业务绑定的输入快照/投影/组成修订随单保留；仅从未生效且无引用的终态候选可清理。重载荷保留 90 天、技术头 365 天、已消费/过期 token 30 天、终态异步命令/发件箱 180 天。部署配置只允许延长，清理须 dry-run、小批量执行、删除前二次校验并留审计；完整契约见 `方案设计.md §11`。

## 7. 裁决完成状态

六项工程实查已完成；其中性能、跨端哈希、复制输入模型和保留周期四项存在多种合理路径，均已逐项取得用户裁决并回写 `需求文档.md`、`方案设计.md`、`api.md`、任务分解和 `test.md`。当前无剩余工程裁决项。

用户于 2026-10-26 明确通过闸门 A，并要求使用 worktree 特性分支开发；开工后不得在主工作区直接修改业务实现。
