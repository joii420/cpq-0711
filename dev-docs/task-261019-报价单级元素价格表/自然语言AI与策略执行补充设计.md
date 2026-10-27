# 自然语言、AI 与策略执行补充设计

> 状态：2026-10-26 闸门 A 已通过，进入 worktree 开发。本文只规定策略配置、AI 和确定性执行；价格修订、投影、复制、核价换版的权威定义在 `方案设计.md`。

## 1. 确认结论

1. AI 只在配置阶段把自然语言编译为白名单 DSL；报价创建、加产品、复制和核价换版运行期不调用 AI。
2. 第一期不引入独立 Skill。使用版本化 Prompt、结构化输出 Schema 和后端确定性校验器。
3. 策略和交易日历采用不可变发布版本；运行期只读已发布 DSL，不执行自然语言原文。
4. 新元素按操作时最新发布的客户策略、报价单原 `priceBaseDate` 解析；已有元素不随策略变化。
5. 同一价格修订可包含来自不同策略版本的元素，因此策略证据记录在每个修订明细，不能只记一个单头版本。
6. 歧义、冲突、不支持或校验失败均阻止发布，不允许 AI 静默近似。
7. `CARRY_FORWARD/LATEST_IN_WINDOW` 若多个目标日映射同一来源日，每个目标日仍是一个聚合样本，同时记录唯一来源日数量。
8. AI 密钥与通用 `system_config` 分离，不能通过普通配置读取接口明文下发。
9. 报价与依附报价的核价通过各自显式绑定读取价格修订；核价侧不另选策略。
10. 人工价可被有权限的显式调价覆盖，但必须创建新修订并保留完整审计。

## 2. 能力边界

### 2.1 第一期支持

- 客户默认规则、元素例外、优先级与无条件兜底。
- 日期条件：星期、月内日号、月份、年内月日、指定日期/区间及 `ALL/ANY/NOT`。
- 日期生成：`LATEST/RANGE/RELATIVE_ANCHORED_RANGE/PERIOD_POINT/FIXED`。
- 星期/日号排除、交易日历、四种统一缺价政策。
- `LATEST/AVG/MAX/MIN` 聚合、系数和 CNY 加价。
- 自然语言新建规则与修改规则，但最终都必须落为同一 DSL。

### 2.2 必须确认

- “最近”“上一周期”“月底”等可能有多种解释的相对表达。
- `LATEST_IN_WINDOW` 可能使用目标日之后的价格。
- 未说明短月、当前周期、边界是否包含或缺价处理。
- AI 修改已有规则时产生的结构化 diff。

### 2.3 明确不支持

- 任意 SQL/JavaScript/正则或外部代码执行。
- AI 直接发布策略、抓取行情、修改日历或写报价价格。
- 任意自然语言在无确认时直接运行。
- 逐目标日配置不同缺价方式；缺价政策是规则级统一配置。
- 依赖运行时互联网、模型知识或模型当前日期的计算。

## 3. AI 解析契约

### 3.1 调用边界

```text
用户输入自然语言
→ 后端组装受限上下文（允许的元素/价格源/枚举/现有规则）
→ AI 输出符合 JSON Schema 的候选 DSL
→ 后端白名单、类型、区间、冲突和日历覆盖校验
→ 页面展示摘要、风险、未知片段和结构化 diff
→ 用户确认
→ 保存草稿 DSL
→ 独立发布动作生成不可变策略版本
```

AI 无权取得数据库凭据、系统 Prompt 以外的敏感配置或执行工具。自然语言和响应须按业务日志规则脱敏。

### 3.2 解析状态

| 状态 | 含义 | 可保存草稿 | 可发布 |
|---|---|---:|---:|
| `READY` | 完整且后端验证通过 | 是 | 用户确认后是 |
| `NEEDS_CONFIRMATION` | 存在可枚举歧义/高风险选择 | 是 | 否 |
| `UNSUPPORTED` | 含 DSL 不支持语义 | 否 | 否 |
| `INVALID` | Schema/业务规则冲突 | 否 | 否 |

模型置信度不改变状态门禁。后端校验失败时，即使 AI 声称高置信度也必须 `INVALID`。

### 3.3 请求与响应要点

请求只提供：用户文本、时区、可用枚举、字段元数据、目标 scope、已有规则结构和 Prompt/Schema 版本。响应必须有：候选 DSL、状态、逐段解释、待确认项、不支持片段、风险和修改 diff；禁止返回可执行代码。

### 3.4 故障与降级

- AI 超时/限流：允许用户转结构化编辑器，不产生半规则。
- AI 服务停用：已发布规则运行、试算和人工结构化配置不受影响。
- Prompt/模型升级：通过金标语料回归后发布新解析版本；旧策略不重解析。

## 4. 配置与安全

新增“系统管理 → AI 解析配置”，维护 Provider/Profile、模型、超时、重试、Prompt/Schema 不可变版本、不可变金标集和 Parser Release。Release 显式绑定金标集版本；运行时只读取唯一启用的发布 Release，并将 release/provider/model/prompt/schema 版本写入每次解析记录。详细字段、接口与权限见 `配置管理模块补充设计.md`。

密钥存 Secret/Vault，数据库只保存 `secretRef`；管理页只能显示“已配置/未配置”、引用名、连通性和最近测试结果，不能回显密钥。通用 `system_config`、接口错误、日志与导出均不得包含密钥明文。

变更模型/Prompt/Schema 不得自动修改草稿或已发布规则。Prompt/Schema/Release 发布后不可原位编辑；发布新 Release 前必须运行版本化金标集且全部通过。禁用 AI 只关闭自然语言入口，不影响结构化编辑器或正式取价。

## 5. 策略与日历存储

### 5.1 策略版本

```text
customer_element_price_policy_version
  id, customer_id, version_no
  status DRAFT|PUBLISHED|ARCHIVED
  valid_from, valid_to
  prompt_version, schema_version
  content_digest
  created_by, created_at, published_by, published_at
```

发布版本不可原位更新；新修改从已发布版复制为新草稿。每个客户已发布有效期不可产生未裁决的重叠。

### 5.2 规则

```text
customer_element_price_rule
  policy_version_id
  scope DEFAULT|ELEMENT
  element_id nullable
  priority
  condition_json
  date_selector_json
  exclusions_json
  calendar_binding_json
  missing_price_policy_json
  source_id, aggregation, factor, premium_cny
  content_digest
```

元素例外是完整规则，不做字段级继承；未命中回到客户默认。每组默认规则必须有唯一无条件兜底。

### 5.3 交易日历

日历头与版本分离。版本保存覆盖起止、具体日期分类和摘要，发布后不可修改。规则可：

1. 保存日历选择器/`calendarId`：执行时选择唯一已发布且覆盖“目标日期 + 最大回溯区间”的具体版本并冻结证据；
2. 显式固定 `calendarVersionId`：该版本覆盖不足时直接失败，不自动漂移。

## 6. 确定性执行器

### 6.1 规则选择

1. 选择操作时有效的已发布策略版本。
2. 对新元素按 `priority ASC, rule_id ASC` 检查元素例外。
3. 无例外命中时检查客户默认规则。
4. 同优先级规则重叠阻止发布；不同优先级重叠由小值先命中。
5. 无限期年度重复条件以公历 400 年循环验证，不让 AI 判断冲突。

### 6.2 执行顺序

```text
识别新元素
→ 选择策略版本与规则
→ 用 quotation.priceBaseDate 生成目标日期
→ 截断未来日期
→ 应用日期排除
→ 解析具体日历版本
→ 批量读取候选价格与回溯区间
→ 应用规则级 missingPricePolicy
→ 生成目标日→实际来源日映射
→ 校验 CNY 和规范计价单位
→ 聚合
→ 聚合值 × factor + premiumCny
→ 按 DEC-0020 统一舍入到 9 位
→ 写入 BUILDING 修订明细
→ 全部成功后封存修订
```

执行器只接受明确的 `LocalDate + ZoneId`；不得读取浏览器日期或服务器默认时区。

### 6.3 批量读取

先汇总元素、价格源、最早回溯日和最晚日期，以常数批次 SQL 读取行情并构建 `(elementId, sourceId, priceDate)` 索引。禁止在元素、目标日或回溯循环内查数据库。

### 6.4 聚合规则

- 目标日期去重、升序；排除日不产生样本，也不能作为替代来源。
- 节假日若存在有效价格仍可 DIRECT 使用。
- `CARRY_FORWARD` 只能向前且受 `maxLookbackDays` 限制。
- `LATEST_IN_WINDOW` 只能用本次窗口内有效来源，可能晚于目标日，必须配置确认。
- 多个目标日映射同一来源日时分别参与 AVG；映射证据逐条保留。
- 无样本返回 MISSING，不返回 0。
- 聚合前样本必须统一 CNY 与规范计价单位。

## 7. 写入价格修订的证据

每个修订明细至少冻结：

```json
{
  "elementId": "...",
  "elementCodeSnapshot": "Cu",
  "canonicalUnitCode": "kg",
  "cnyUnitPrice": "5820.000000000",
  "automaticCnyPrice": "5820.000000000",
  "sourceType": "STRATEGY",
  "policyVersionId": "...",
  "ruleId": "...",
  "ruleDigest": "sha256:...",
  "calendarVersionId": "...",
  "calendarDigest": "sha256:...",
  "targetDates": ["2026-10-01"],
  "excludedDates": [],
  "targetDateMappings": [{
    "targetDate": "2026-10-01",
    "result": "SUBSTITUTED",
    "actualSourceDate": "2026-09-30",
    "sourcePriceId": "...",
    "rawPriceCny": "5810.000000000",
    "sourceUnitText": "元/kg"
  }],
  "aggregation": "AVG",
  "factor": "1.000000000",
  "premiumCny": "0.000000000"
}
```

正式存储是关系表中的不可变修订明细；以上 JSON 仅表达证据结构，不再以 `quotation.element_price_snapshot` 可变 JSON 作为权威。

## 8. 与报价/核价生命周期的连接

- 新报价：执行全量首次取价，生成 R1。
- 加产品：只为首次出现元素执行策略，以母修订复制旧元素并生成 Rn+1。
- 移除/重加：使用状态由用户数据/投影派生为 DORMANT/ACTIVE，价格修订号和值不变；不重新执行策略。
- 人工改价/调价：生成新修订；调价可覆盖人工值但保留来源链。
- 复制：不复制修订，新单重新执行策略。
- 核价换版：只有新增元素时执行策略并生成核价使用的 Rn+1；报价提交修订不变。
- 策略发布：本身不触发任何已有报价重算。

## 9. 验证门槛

1. 自然语言金标：同义、歧义、不支持、矛盾和修改已有规则。
2. Schema：未知字段/枚举、越界、超窗口全部拒绝。
3. 日期：跨周/月/年、闰年、短月、ISO 周和 400 年冲突。
4. 日历：法定节假日、补班、临时休市、覆盖不足和版本选择。
5. 缺价：四种模式、回溯耗尽、重复来源日和全量排除。
6. 批量：元素增加时 SQL 次数保持常数阶。
7. 精度：先聚合后乘加、最终 9 位和 DEC-0020 零值规则。
8. 可复现：源行情后续变化不改变历史修订解释。
9. AI 故障：AI 停用不影响已发布 DSL 执行。
10. 生命周期：R1→人工改价→加元素→移除/重加→调价→复制→核价换版全序列。

产品承诺不是“任意自然语言都能正确理解”，而是：

> 受支持、通过后端校验且由用户确认的 DSL，在相同策略/日历版本、报价基准日和源样本下得到相同结果；任何歧义或不支持内容不得进入执行阶段。
