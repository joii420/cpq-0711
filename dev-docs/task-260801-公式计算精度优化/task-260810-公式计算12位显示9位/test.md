# test.md · 公式计算保留 12 位、最终显示 9 位

> 验收依据：同目录 `需求文档.md`（AC-1~AC-20）；接口以 `api.md` 为准；实现检查点以 `fronttask.md` / `backtask.md` 为准。
> 当前为测试设计阶段，尚未执行测试，不创建 `test-report.md`。“实际结果”列全部留空，待主线审核后填写。
> 核心口径：工作值 12 位；最终最多显示 9 位、HALF_UP、去尾零；后端 BigDecimal、前端 Decimal；精度值禁止经过 Double/JS number。

## 1. 环境、数据与证据

| 项 | 约定 |
|---|---|
| worktree / 分支 | `/home/joii/project/codex-cpq-task-0810` / `feat/task-0810-formula-precision-12-display-9` |
| 后端 | worktree `cpq-backend/`；定向测试、`./mvnw test`、compile；记录 Tests run/Failures/Errors/Skipped |
| 前端 | worktree `cpq-frontend/`；Vitest、TypeScript、Vite build、Playwright |
| 联调 | 前端 5174、后端 8081；复用共享服务，先探活并确认 8081 实际连接 `cpq_db_0724` |
| 权限 | `admin`（SYSTEM_ADMIN）、`alice`（SALES_REP）、无登录 |
| FX-01 | 双端共用 `formula-golden/*.json`，含 1/3、10/3*3、amt-002/003、null/除零/全角运算符 |
| FX-02 | 测试专用 DRAFT：含 `98765431.123456789012`、正负第 10 位边界、零边界、跨页签、折扣、总额 |
| FX-03 | 专用 DRAFT 历史快照：row_data/卡片/Excel JSON 内含 numeric token 大金额、12 位小数、null |
| FX-04 | SUBMITTED、APPROVED、冻结报价/核价各一；执行前保存金额、JSON md5、xmin、updated_at |
| FX-05 | 可导出样本；HTML/PDF/邮件/Excel 均包含大金额和显示舍入边界 |
| FX-06 | 基础取数 8 位/12 位小数；费率保留现有业务 scale |
| FX-07 | 固定产品、页签、行数的 DRAFT 性能报价；master/分支用同一数据快照 |

证据要求：

- 自动化保留命令、HEAD、工作区 diff hash/文件清单、通过/失败/跳过数；若已 commit 再记录 commit，不得只写 BUILD SUCCESS。
- API 保留请求、状态和原始 JSON，证明 decimal 在引号内；SQL 保留 Flyway、21 列和冻结前后结果。
- UI 保留 URL、DOM textContent、三视图截图，并证明 state/API 仍为 12 位。
- 导出保留 HTML 原文、PDF 提取文本、邮件正文/附件、Excel 值及 `CellType.STRING`/xlsx XML。
- Playwright 每个视图记录“加载中”最终计数，期望为 0。

## 2. AC 覆盖矩阵

| AC | TC |
|---|---|
| AC-1 | TC-001~003 |
| AC-2 | TC-004 |
| AC-3 | TC-005 |
| AC-4 | TC-006 |
| AC-5 | TC-007~009 |
| AC-6 | TC-019~022、TC-082~083 |
| AC-7 | TC-030~033、TC-080~081、TC-085 |
| AC-8 | TC-010~016、TC-040 |
| AC-9 | TC-023~027 |
| AC-10 | TC-034~036 |
| AC-11 | TC-037~041 |
| AC-12 | TC-042~044 |
| AC-13 | TC-045~046 |
| AC-14 | TC-047~049 |
| AC-15 | TC-050~052 |
| AC-16 | TC-053~055 |
| AC-17 | TC-056~059 |
| AC-18 | TC-060~065 |
| AC-19 | TC-068~072、TC-086 |
| AC-20 | TC-073~076 |

### 2.1 FR → TC 追踪矩阵

| 需求 | 交付要点 | 对应 TC | 覆盖判定 |
|---|---|---|---|
| FR-1 | 12 位计算、9 位显示、HALF_UP 单一来源 | TC-001~003 | 双端常量、rounding、调用点均覆盖 |
| FR-2 | 加减乘精确、除法/节点 12 位 | TC-004~006、TC-015 | 黄金值、节点边界、聚合覆盖 |
| FR-3 | 字段/上一行/跨页签/小计/总额/差异零浮点 | TC-008、TC-010~015、TC-034~036、TC-042~052 | 静态禁用 + 动态对拍 + 业务写点 |
| FR-4 | 六个 JEXL 点全 BigDecimal | TC-014 及 §3.1 六子项 | 每个求值点独立留证 |
| FR-5 | 21 列 numeric(26,12)，JSON string | TC-019、TC-023、TC-030~033、TC-080~081 | schema/JPA/迁移/容量/往返 |
| FR-6 | 历史 numeric 无损；null/除零/非法公式不变 | TC-013、TC-023~027、TC-050~051、TC-060~065 | 前后端 parser 与语义回归 |
| FR-7 | P1~P4 string-only；number=400 | TC-017~018、TC-028~029、TC-082~083 | 逐端点响应/写请求/权限矩阵 |
| FR-8 | 前端 state/payload/localStorage 禁 number | TC-011~013、TC-019~027 | 类型、解析、state 与往返覆盖 |
| FR-9 | 报价/核价/详情/列表/快照/比较最多 9 位 | TC-007~009、TC-034~036、TC-073~075 | helper、全部视图、E2E |
| FR-10 | HTML/PDF/邮件/Excel 与 UI 一致 | TC-016、TC-037~041 | 文件文本、MockMailbox、Excel STRING |
| FR-11 | 非 DRAFT 零写；DRAFT 显式升级 | TC-026、TC-053~059 | md5/xmin/SQL 日志、幂等并发 |
| FR-12 | 按 12 位对账，10~12 位差异阻断 | TC-066~067、TC-076 | 正反对账与 UI route 注入 |

### 2.2 前端任务 → TC 追踪矩阵

| fronttask | 交付物 | 对应 TC | 审核重点 |
|---|---|---|---|
| F1 | DecimalString/DecimalValue 与规范化 helper | TC-002~003、TC-011~013、TC-021~022 | 精度类型中无 `number`，`-0`/尾零规范 |
| F2 | 12 位计算、9 位显示函数 | TC-002、TC-004~009 | 过程值与显示值分层 |
| F3 | formulaEngine/LIST_FORMULA Decimal 化、BL-0160 | TC-004~015、TC-047~049、TC-060~065 | golden 同源、amt-002/003 |
| F4 | 行公式/跨页签/列/页签/产品小计 | TC-008、TC-015、TC-034~036、TC-073~075 | footer 不另算，三视图一致 |
| F5 | 折扣/总额/对账消除 number | TC-015、TC-017~022、TC-036、TC-066~067、TC-076 | 第 10~12 位不可丢 |
| F6 | 无损 parser；历史 numeric、新写 string | TC-013、TC-023~027、TC-053~059 | 必建 `row_data`、quote/costing CardValues、quote/costing ExcelValues 及报价编辑/详情/核价消费入口 inventory；这些入口禁止普通 JSON.parse |
| F7 | quotation/costing/formula service 类型 | TC-012、TC-017~018、TC-028~029、TC-082~083 | P1~P4 和数量/费率专项 |
| F8 | 报价/核价/详情/列表/快照/Excel 显示 | TC-034~041、TC-073~075 | UI 与导出文本一致 |
| F9 | 共享 golden、单测、确定性 fixture | FX-01~07、TC-004~015、TC-047~049、TC-060~065 | 禁止两端维护预期副本 |
| F10 | TS/Vitest/Vite/Playwright 自检 | TC-071~076、TC-086 | 0 新失败、0 未解释 skip、加载中=0 |

### 2.3 后端任务 → TC 追踪矩阵

| backtask | 交付物 | 对应 TC | 审核重点 |
|---|---|---|---|
| B1 | 失败测试锁定旧 6 位/Double/BL 行为 | TC-004~016、TC-042~049 | 反向门禁 TC-044 必须真能识别 4 位 |
| B2 | PrecisionPolicy 12/9 分层 | TC-001、TC-003~009 | 旧 round 不得混用计算/显示 |
| B3 | 历史 numeric→BigDecimal；string-only adapter | TC-013、TC-017~029、TC-082~083 | DoubleNode/Double 0 命中 |
| B4 | FormulaCalculator 全 BigDecimal | TC-004~015、TC-047~049、TC-060~065 | 上下文、节点、聚合均无 Double |
| B5 | 六个 JEXL 点加固 | TC-014 及 §3.1 | 六处不能合并成一个笼统断言 |
| B6 | 报价/折扣/卡片/跨页签/核价写点 12 位 | TC-019~022、TC-034~036、TC-042~046、TC-053~059 | 保存、刷新、重开逐值一致 |
| B7 | 价格调整/版本/审计值与 BL-0159 | TC-042~046、TC-052、TC-082~083 | 不扩大升版重算范围 |
| B8 | 21 列 Flyway/JPA | TC-030~033、TC-080~081、TC-085 | 只 ALTER、不重算、容量与事务回滚 |
| B9 | API decimal string；number=400 | TC-017~018、TC-028~029、TC-082~083 | 所有可写精度字段逐项拒绝 number |
| B10 | HTML/PDF/邮件/Excel 9 位 | TC-016、TC-037~041 | MockMailbox 与 Excel STRING |
| B11 | 定向/全量/SQL/端点/性能自检 | TC-068~079、TC-084~086 | 原始统计、main-api、迁移唯一、skip |

### 2.4 API 交付物 → TC 追踪

| api.md | 对应 TC | 放行条件 |
|---|---|---|
| 通用 decimal/string 与嵌套快照 | TC-017~029 | string/null、普通十进制、历史 numeric 无损 |
| API-P1 报价主链 | TC-019~029、TC-082~083 | P1-01~18 逐端点；P1-18 组内实际路由全部展开 |
| API-P2 公式求值 | TC-004~006、TC-014~018、TC-060~065、TC-082~083 | 单算/批算、bindings/context、错误隔离 |
| API-P3 Excel/导出 | TC-016、TC-037~041、TC-082~083 | API string + 文件 9 位 + Excel STRING |
| API-P4 核价/比较/价格调整 | TC-034~036、TC-045~052、TC-082~083 | 17 个命名端点和配置写端点逐项留证 |
| main-api 回写 | TC-084 | 方法+路径覆盖、来源标记、文件头日期正确 |

### 2.5 P0/P1 分级

| 级别 | 用例 | 放行口径 |
|---|---|---|
| P0（74 条） | 除下列 P1 外的全部 TC | 任一失败/阻塞即不得交付；不得用人工目测替代自动化/SQL/API 证据 |
| P1（12 条） | TC-003、022、027、029、041、043、049、059、062、065、077、079 | 默认同样阻断交付；仅用户书面接受风险且 test-report 记录原因、影响、后续条目时可豁免 |

P0 核心红线：任何 number/Double 中转、12 位丢失、API number 未返回 400、DB 容量/迁移不符、冻结单 UPDATE、三视图/导出不一致、Excel 非文本、对账漏第 10~12 位、加载中非 0。

## 3. 精度与零浮点

| TC | FR/AC | 级 | 前置数据 | 步骤 | 明确预期 | 实际结果 |
|---|---|---:|---|---|---|---|
| TC-001 | FR-1/AC-1 | P0 | 后端策略测试 | 断言 PrecisionPolicy | 计算 12、显示 9、HALF_UP、DECIMAL128 | |
| TC-002 | FR-1/AC-1 | P0 | 前端策略测试 | 断言 precision 常量/rounding | 12、9、Decimal.ROUND_HALF_UP，DIVISION_SCALE 仅指向 12 | |
| TC-003 | FR-1/AC-1 | P1 | 双端调用点 | 查局部 6/9/12 常量和私有舍入 | 只用集中策略，无第二套规则 | |
| TC-004 | FR-2/AC-2 | P0 | FX-01 | 双端执行 `1/3` | 工作值 `0.333333333333`，显示 `0.333333333` | |
| TC-005 | FR-2/AC-3 | P0 | FX-01 | 双端执行 `10/3*3` | 工作值 `9.999999999999`，显示 `10`，不得提前 round | |
| TC-006 | FR-2/AC-4 | P0 | `1.2345678912345` | 经公式节点出口 | `1.234567891235` | |
| TC-007 | FR-9/AC-5 | P0 | `1.2345678914/15` | 双端显示 | `1.234567891/1.234567892` | |
| TC-008 | FR-9/AC-5 | P0 | `-1.2345678914/15` | 双端显示 | `-1.234567891/-1.234567892` | |
| TC-009 | FR-9/AC-5 | P0 | 0、-0、`0.0000000004/5`、`5.000000000000` | 双端显示 | `0`、`0`、`0/0.000000001`、`5`；无科学计数法 | |
| TC-010 | FR-3/AC-8 | P0 | 后端精度链 | 搜 doubleValue、Double.parseDouble、new BigDecimal(double)、Map<String,Double>、DoubleNode/double | 精度链 0 命中；结构用途逐条分类且不接收精度值 | |
| TC-011 | FR-3/8/AC-8 | P0 | 前端精度链 | 搜 toNumber、Number、parseFloat、toFixed、Math.abs、精度类型 `| number` | 精度链 0 命中；结构整数不流入公式 | |
| TC-012 | FR-7/8/AC-8 | P0 | DTO/service 类型 | 静态检查和编译 | 仅 DecimalString/Decimal 或 BigDecimal/string，无 number/Double 联合旁路 | |
| TC-013 | FR-6/AC-8 | P0 | §3.2 精度快照解析 inventory | 对每个存储载体和报价编辑/详情/核价消费入口查 JSON.parse/无损 parser，并记录文件、函数、输入来源 | 所有精度快照在产生 JS number 前被无损 parser 接管；inventory 无空槽；普通 JSON.parse 仅存在于经证明不含精度值的配置/结构 JSON | |
| TC-014 | FR-4/AC-8 | P0 | 六个 JEXL 点 | 各测字面量、变量、函数、1/3、大金额 | 六处全程 BigDecimal，结果 12 位，无 Double | |
| TC-015 | FR-2/3/AC-8 | P0 | 双端算术集 | 测 0.1+0.2、连续乘法、括号、SUM/AVG/MAX/MIN/K 系列 | 加减乘不逐步 round；聚合全程 Decimal/BigDecimal；双端逐值一致 | |
| TC-016 | FR-10/AC-8 | P0 | Excel 实现 | 搜 double NUMERIC/setCellValue(double) | 精度计算值全部字符串写入 | |

### 3.1 TC-014 六个 JEXL 必测子项

| 子项 | 求值点 | 输入矩阵 | 明确预期 |
|---|---|---|---|
| J1 | `engine/formula/FormulaCalculationService` | BigDecimal 字面量、变量、函数、1/3、大金额、负数 | 参数/返回均 BigDecimal；节点 12 位；无 Double |
| J2 | `formula/FormulaEngine` | 同 J1 | 同 J1 |
| J3 | `TemplateFormulaService.rowJexl` | 同 J1，另含行字段/null | null 语义不变；其它同 J1 |
| J4 | `ExcelViewService` 内联公式 | 同 J1，另含 Excel 动态值 | 结果 decimal string/BigDecimal；导出前不压 9 位 |
| J5 | `TabJoinPlanEvaluator/SafeArithmetic` | 同 J1，另含跨页签值 | 跨页签上下文 BigDecimal；除零沿用 0 |
| J6 | `CostingSheetService` 内联公式 | 同 J1，另含核价汇总 | 核价公式/汇总 BigDecimal；与报价同值逐位一致 |

TC-014 只有六个子项全部有独立测试方法和结果统计才算通过；任一求值点仅靠代码阅读不得放行。

### 3.2 精度快照解析入口 inventory

实现交接时必须逐行填写“实际 parser/helper + 前端消费函数/文件”，测试报告逐行引用静态与动态证据。缺任一入口即 TC-013/025 失败。

| 精度载体 | 存储/API 入口 | 必测前端消费入口 | 强制断言 |
|---|---|---|---|
| `row_data` | componentData.rowData | 报价编辑、报价详情、核价的组件行读取 | 历史 numeric 原字面量直达 Decimal；新值 string |
| `quote_card_values` | quoteCardValues | 报价编辑、报价详情/ReadonlyProductCard、快照视图 | 同上，formulaResults/subtotalByColumn/resolvedRows 均覆盖 |
| `costing_card_values` | costingCardValues | 核价、报价详情中的核价视图、比较视图 | 同上 |
| `quote_excel_values` | quoteExcelValues | 报价 Excel 编辑/详情/快照/导出调用 | 同上，计算列与原始列按字段语义区分 |
| `costing_excel_values` | costingExcelValues | 核价 Excel/详情/快照/导出调用 | 同上 |

普通 `JSON.parse` 只允许用于已经通过字段 schema、类型定义和调用链证明“不含金额、数量、费率、公式值、小计、合计或差异值”的配置/结构 JSON。证明材料必须列出 JSON 名称、schema/类型和调用方；无法证明即按精度 JSON 处理，必须走无损 parser。不得使用“解析后再 new Decimal(number)”补救。

## 4. API、往返与历史 JSON

| TC | FR/AC | 级 | 前置数据 | 步骤 | 明确预期 | 实际结果 |
|---|---|---:|---|---|---|---|
| TC-017 | FR-7 | P0 | FX-02、登录 | P2 发 1/3；P1 保存大金额 string | 2xx，响应为 `"0.333333333333"`/大金额 string | |
| TC-018 | FR-7 | P0 | 同上 | P2 binding、P1 draft、quote-card-edit、P4 字段分别发 JSON number | 全部 400，message 指明字段/原值，不静默按 0 | |
| TC-019 | FR-5~8/AC-6 | P0 | FX-02 | 保存后查 API、DB/JSONB并重开 | 请求、DB、JSONB、响应/state 逐字等于大金额；DOM `98765431.123456789` | |
| TC-020 | FR-5~8/AC-6 | P0 | FX-02 | 保存/刷新/关闭重开 3 次 | 12 位工作值不漂移，无二进制噪声/科学计数法 | |
| TC-021 | FR-8/AC-6 | P0 | FX-02 | 查 Network、state/localStorage、DOM | 前三者 12 位 string，仅 DOM 9 位；渲染不反写 state | |
| TC-022 | FR-5/8/AC-6 | P1 | 同值不同 scale | `1.0`/`1.000000000000` 规范化并重复保存 | 新写统一 `"1"`，指纹稳定 | |
| TC-023 | FR-5/6/AC-9 | P0 | FX-02 | 保存 row_data 和四份值快照 | 新计算节点为 JSON string；文本/布尔/null 不变 | |
| TC-024 | FR-6/AC-9 | P0 | FX-03 | 后端读取历史 numeric | Jackson 直接 BigDecimal/DecimalNode，逐字一致，无 Double | |
| TC-025 | FR-6/8/AC-9 | P0 | FX-03 + §3.2 全 inventory | 分别从 row_data、quote/costing CardValues、quote/costing ExcelValues，经报价编辑/详情/核价入口读取历史 numeric | 每个入口均由无损 parser 直达 DecimalString/Decimal，进入 state 前无 JS number；五类载体逐项有动态断言 | |
| TC-026 | FR-6/11/AC-9 | P0 | FX-03 | 仅打开不保存后查 DB | 可显示，原 JSON md5 不变，无兼容回写 | |
| TC-027 | FR-6/AC-9 | P1 | 混合 JSON | 往返 null/文本数字/布尔/文本 | 非精度语义不变，禁止整棵 JSON 递归 round | |
| TC-028 | FR-7 | P0 | 同上 | 发 0、负数、12 位、null、科学计数法、abc、空串、超容量 | 合法值 string 返回；null 保持 null；非法 400；无 500/截断落库 | |
| TC-029 | FR-7 | P1 | 无登录/SALES_REP/admin | 请求 P1~P4代表端点；batch 含合法/非法项 | 401/403/成功沿用 RBAC；批量仅单项失败且顺序不变 | |

### 4.1 API-P1~P4 逐端点覆盖矩阵

统一执行方式：

- TC-082：逐行保存原始响应，断言该行列出的金额、数量、费率、公式值、小计、合计、差异值及嵌套快照数值全部为 JSON string/null；结构整数仍为 number。
- TC-083：矩阵“number=400”为“是”的端点，将每一类精度字段分别改为 JSON number，逐字段断言 400、message 含字段和原值、事务零部分写；“无精度入参”不是免测，而是证明请求 DTO 确实没有精度字段。

#### API-P1 报价链路

| 编号 | 方法与路径 | string 响应核查 | number=400 |
|---|---|---|---|
| P1-01 | GET `/api/cpq/quotations` | totalAmount、originalAmount 等金额 | 无精度入参 |
| P1-02 | GET `/api/cpq/quotations/{id}` | 单头、lineItems、componentData、四份快照全部精度节点 | 无精度入参 |
| P1-03 | POST `/api/cpq/quotations` | 响应金额 string | 无精度入参（创建 DTO 仅含客户/联系人/模板/分类等标识与文本） |
| P1-04 | PUT `/api/cpq/quotations/{id}/draft` | 单头/行/组件/快照全部精度节点 | 是：金额、数量、费率、嵌套快照 |
| P1-05 | POST `/api/cpq/quotations/{id}/refresh-card-snapshot` | 新卡片快照 12 位 string | 无精度入参 |
| P1-06 | POST `/api/cpq/quotations/{id}/ensure-card-values` | 卡片公式值/小计 string | 无精度入参 |
| P1-07 | POST `/api/cpq/quotations/{id}/ensure-excel-values` | Excel 公式值/小计 string | 无精度入参 |
| P1-08 | PUT `/api/cpq/quotations/line-items/{lineItemId}/quote-card-edit` | value 和返回快照精度节点 string | 是：value/rowData 数值 |
| P1-09 | POST `/api/cpq/quotations/{id}/calculate-discount` | originalAmount、折扣额、折后额 string | 是：originalAmount、折扣率/费率 |
| P1-10 | POST `/api/cpq/quotations/{id}/recalculate` | 单头/行/公式结果 string | 若 DTO 含精度字段则逐字段是；纯触发请求则留存“无精度入参”证据 |
| P1-11 | GET `/api/cpq/quotations/{id}/snapshot` | 四份快照和 componentData 精度节点 string | 无精度入参 |
| P1-12 | GET `/api/cpq/quotations/{id}/field-trace` | trace input/output/差异 string | 无精度入参 |
| P1-13 | POST `/api/cpq/quotations/{id}/submit` | 响应金额/对账值 string | 是：请求快照内所有精度节点 |
| P1-14 | POST `/api/cpq/quotations/{id}/copy` | 复制/跨模板重算金额和快照 string | 若 DTO 含精度字段则逐字段是；否则无精度入参 |
| P1-15 | GET `/api/cpq/quotations/{id}/costing-approve/preview` | 报价/核价/差异 string | 无精度入参 |
| P1-16 | POST `/api/cpq/quotations/{id}/costing-approve` | 落库金额、差异、响应 string | 若 DTO 含精度字段则逐字段是；否则无精度入参 |
| P1-17 | POST `/api/cpq/quotations/line-items/{lineItemId}/reconcile-report` | frontend/backend/inputs/差异 string | 是：frontend、backend、inputs |
| P1-18a | POST `/api/cpq/quotations/{quotationId}/line-items/{lineItemId}/tree/add-leaf` | `quoteCardValues` 精度节点 string | 无精度入参（UUID/节点/料号） |
| P1-18b | POST `/api/cpq/quotations/{quotationId}/line-items/{lineItemId}/tree/delete-preview` | 结构响应，无精度字段 | 无精度入参（UUID/模式/节点/行键） |
| P1-18c | POST `/api/cpq/quotations/{quotationId}/line-items/{lineItemId}/tree/delete` | 返回投影快照精度节点 string | 无精度入参（UUID/模式/节点/行键/令牌） |
| P1-18d | POST `/api/cpq/quotations/{qid}/line-items/{lid}/delete-driver-row` | 返回投影快照精度节点 string | 无精度入参（UUID/行键/指纹） |
| P1-18e | POST `/api/cpq/quotations/{qid}/line-items/{lid}/restore-driver-rows` | 返回投影快照精度节点 string | 无精度入参（组件 UUID） |

#### API-P2 公式求值

| 编号 | 方法与路径 | string 响应核查 | number=400 |
|---|---|---|---|
| P2-01 | POST `/api/cpq/formulas/evaluate` | data.value 及精度 trace string | 是：bindings/context 每一种精度值 |
| P2-02 | POST `/api/cpq/formulas/batch-evaluate` | 每个成功项 value string，失败项结构不变 | 是：每项 bindings/context；单项 400/错误隔离沿既有契约 |

#### API-P3 Excel 视图与导出

| 编号 | 方法与路径 | string/文件核查 | number=400 |
|---|---|---|---|
| P3-01 | GET `/api/cpq/quotations/{id}/excel-view` | 计算列 string，原始列保持原类型契约 | 无精度入参 |
| P3-02 | POST `/api/cpq/quotations/{id}/excel-view/dry-run` | 试算值 string | 是：请求中的计算输入/公式变量 |
| P3-03 | PUT `/api/cpq/quotations/{id}/excel-view` | 保存/响应精度值 string | 是：全部可写精度单元格 |
| P3-04 | POST `/api/cpq/quotations/{id}/export/html` | 文件显示最多 9 位 | 无精度入参 |
| P3-05 | POST `/api/cpq/quotations/{id}/export/pdf` | 文件显示最多 9 位 | 无精度入参 |
| P3-06 | POST `/api/cpq/quotations/{id}/export/excel` | 计算值为 9 位文本单元格 | 无精度入参 |
| P3-07 | GET `/api/cpq/quotations/{id}/export-excel-view` | 计算值为 9 位文本单元格 | 无精度入参 |
| P3-08 | POST `/api/cpq/quotations/{id}/send` | mock 邮件正文/附件最多 9 位 | 请求中若有自定义精度内容逐字段是；仅收件人/主题/正文参数则无精度入参 |

#### API-P4 核价、比较、价格调整

| 类别 | 方法与路径 | string 响应核查 | number=400 |
|---|---|---|---|
| 核价 | GET `/api/cpq/costing-orders` | 总额/核价总额 string | 无精度入参 |
| 核价 | GET `/api/cpq/costing-orders/{coid}` | 单头/明细/版本金额、数量、费率 string | 无精度入参 |
| 核价 | GET `/api/cpq/costing-orders/{coid}/version-options` | 金额选项 string | 无精度入参 |
| 核价 | POST `/api/cpq/costing-orders/{coid}/version-switch` | 响应金额 string | 若请求仅版本 ID 则无精度入参；否则精度字段逐一是 |
| 比较 | GET `/api/cpq/quotations/{id}/comparison` | 报价/核价/差异 string | 无精度入参 |
| 比较 | POST `/api/cpq/quotations/{id}/comparison/export` | 文件显示最多 9 位 | 无精度入参 |
| 比较 | GET `/api/cpq/quotations/{id}/comparison-view/data` | current/costing/diff string | 无精度入参 |
| 版本 | GET `/api/cpq/quotations/{quotationId}/price-revisions` | quoteTotalAmount string | 无精度入参 |
| 版本 | GET `/api/cpq/quotations/{quotationId}/price-revisions/{revisionId}/preview` | 总额/行金额 string | 无精度入参 |
| 审核 | GET `/api/cpq/price-adjust/reviews` | current/adjusted/diff/warn string | 无精度入参 |
| 审核 | GET `/api/cpq/price-adjust/reviews/{reviewId}` | current/adjusted/diff/warn string | 无精度入参 |
| 审核 | POST `/api/cpq/price-adjust/reviews/impact` | 影响金额/差异 string | 是：请求中的价格、数量、费率、阈值 |
| 审核 | POST `/api/cpq/price-adjust/reviews/approve` | 派生金额 string | 若请求含精度值则逐字段是；纯 ID/状态动作则无精度入参 |
| 审核 | POST `/api/cpq/price-adjust/reviews/{reviewId}/recompute-budget` | 重算金额 string | 若请求含精度值则逐字段是；纯触发则无精度入参 |
| 任务 | GET `/api/cpq/price-adjust/jobs` | diffValue/金额 string | 无精度入参 |
| 任务 | GET `/api/cpq/price-adjust/jobs/{jobId}` | diffValue/金额 string | 无精度入参 |
| 任务 | GET `/api/cpq/price-adjust/jobs/{jobId}/items` | diffValue/金额 string | 无精度入参 |
| 配置 | `main-api.md` 中阈值、元素价格的实际写端点 | 业务 scale 不变，精度响应 string | 是：阈值、元素价格、费率逐字段 |

#### 数量与费率专项核查清单

| 分类 | 必查位置 | 断言 |
|---|---|---|
| 报价数量 | P1-02/04/08/10/12/13/17 及 P1-18 返回快照 | rowData/快照/公式 quantity input 为 string；含精度入参的写端点发 number 为 400；`annualVolume` 是结构性整数，保持 number |
| 报价费率 | P1-04/09/10/12/13/17 | finalDiscountRate、discountRateApplied、taxRate 及公式 rate input 为 string；业务 scale 不变 |
| 公式上下文 | P2-01/02 bindings/context | 每个金额/数量/费率变量均 string；任一 number 被拒绝或按批量既有单项错误隔离 |
| Excel 输入 | P3-01/02/03 的动态计算输入 | 计算列 quantity/rate 为 string；基础原始列只保持自身契约，不被全局 9 位化 |
| 核价 | P4 核价详情/版本切换/比较 | 明细数量、费率、金额、差异均 string；结构性行数仍 number |
| 价格调整 | impact、approve、recompute、阈值/元素价格写端点 | 价格、数量、费率、阈值为 string；现有业务 scale 不变；number=400 |

| TC | FR/AC | 级 | 前置数据 | 步骤 | 明确预期 | 实际结果 |
|---|---|---:|---|---|---|---|
| TC-082 | FR-7/AC-6 | P0 | 上述 P1~P4 矩阵、有效夹具 | 逐端点执行 string 响应类型断言并保存原始响应 | 所有精度字段 string/null，结构整数 number；任一端点缺证据即失败 | |
| TC-083 | FR-7/AC-6 | P0 | 所有含精度请求字段的 POST/PUT | 每端点逐类把金额/数量/费率/公式值改为 JSON number | 全部 400，字段/原值可定位，事务零部分写；无精度入参端点留存 DTO 证据 | |

## 5. DB、视图、导出与遗留缺陷

| TC | FR/AC | 级 | 前置数据 | 步骤 | 明确预期 | 实际结果 |
|---|---|---:|---|---|---|---|
| TC-030 | FR-5/AC-7 | P0 | Flyway 自动执行 | 查 flyway_schema_history | 实际迁移唯一且 success=t，未手工 psql -f | |
| TC-031 | FR-5/AC-7 | P0 | information_schema | 查需求列出的 21 列 | 恰 21 行，全部 precision=26/scale=12 | |
| TC-032 | FR-5/AC-7 | P0 | 7 个实体 | 查 21 个 Column mapping | 全部 precision=26/scale=12，与 DB 一致 | |
| TC-033 | FR-5/11/AC-7 | P0 | 旧值样本 | 审迁移并比前后值 | 只 ALTER TYPE，无 UPDATE/重算/补零；旧值相等 | |
| TC-080 | FR-5/AC-7 | P0 | 21 列参数化持久化测试、可写 API | 每列写入 14 位整数+12 位小数 `99999999999999.999999999999` 并回读 | 21 列均成功；DB、实体、API 逐字等于输入；响应为 string | |
| TC-081 | FR-5/AC-7 | P0 | 与 TC-080 同一事务夹具 | 每列尝试写 15 位整数 `100000000000000.000000000000`；同时带一个合法字段，保存前记录行数/md5/xmin | 每次受控失败（API 为 400/既有校验错误，不得 500）；整个事务回滚，合法字段也不落库，行数/md5/xmin 全不变 | |
| TC-034 | FR-9/AC-10 | P0 | FX-02 | 报价编辑/详情/核价读同一字段 | 三处 DOM 完全一致、最多 9 位；state 12 位 | |
| TC-035 | FR-9/AC-10 | P0 | FX-02 | 列表/快照/比较读同一值 | 文本一致；空值 `—`，业务零 `0` | |
| TC-036 | FR-3/9/AC-10 | P0 | FX-02 | 手算行和，对比 footer/页签/产品小计 | footer 用权威 columnSums，无第二算法 | |
| TC-037 | FR-10/AC-11 | P0 | FX-05 | 导出 HTML | 数值与 UI 一致，最多 9 位，不暴露 10~12 位 | |
| TC-038 | FR-10/AC-11 | P0 | FX-05 | 导出 PDF并提取文本 | 与 UI/HTML 一致，无科学计数法 | |
| TC-039 | FR-10/AC-11 | P0 | FX-05；test profile 已配置 `quarkus.mailer.mock=true` | 新增/执行 Quarkus `@QuarkusTest`，注入 `io.quarkus.mailer.MockMailbox`；测试前 clear，调用 send 后用 `getMailsSentTo` 取 Mail，检查 HTML body 和 HTML/Excel 附件内容 | 恰捕获 1 封；正文数值与 UI 一致；HTML 附件最多 9 位；Excel 附件精度单元格为 STRING 且文本一致；不依赖真实 SMTP/邮箱 | |
| TC-040 | FR-10/AC-8/11 | P0 | FX-05 | POI读报价Excel/Excel View/比较导出 | 精度计算单元格全为 STRING，值与 UI 一致 | |
| TC-041 | FR-10/AC-11 | P1 | FX-05/06 | 对比 Excel 原始列/计算列 | 计算列9位文本；基础原始列保持自身契约 | |

邮件可执行机制核查：仓库已有 `quarkus-mailer` 依赖，`application.properties`、`application-test.properties`、`application-test2.properties` 均启用 `quarkus.mailer.mock=true`；现有测试未使用 `MockMailbox` 捕获邮件。因此 TC-039 的自动化交付物必须补充 MockMailbox/captor 测试，禁止退化为“send 返回 2xx”或依赖人工真实邮箱。
| TC-042 | FR-3/AC-12 | P0 | 两金额列和含12位尾数 | 触发三个 `__amount_total__` 登记点 | 三处逐值一致、12位、不截4位 | |
| TC-043 | FR-3/AC-12 | P1 | 空集/零/负数/恰4位 | 重复三登记点 | 三处一致，空集合按既有语义0，无异常 | |
| TC-044 | FR-3/AC-12 | P0 | `0.083825536789` | 与手工4位截断结果比较 | 必须不等，证明用例可识别BL-0159 | |
| TC-045 | FR-3/5/AC-13 | P0 | 同组line totals | 普通写点与升版写点对拍 | 两者12位逐值一致，无setScale(4) | |
| TC-046 | FR-5/AC-13 | P0 | 含未升版/PART行 | 执行升版并查审计列 | 仅重算既有指定行，派生值12位，不扩大范围 | |
| TC-047 | FR-2/3/AC-14 | P0 | amt-002 | 双端读tab_name总计 | 命中 `投料#__amount_total__`，不回退0 | |
| TC-048 | FR-2/3/AC-14 | P0 | amt-003 | 两种总计相加 | 双端逐字符串等于golden | |
| TC-049 | FR-3/AC-14 | P1 | 多种键并存 | 测键优先级 | 双端对称，沿用既有优先级 | |
| TC-050 | FR-6/9/AC-15 | P0 | FX-06 | 原始列展示/payload往返 | 8/12位原值不被9位规则压缩 | |
| TC-051 | FR-3/6/AC-15 | P0 | FX-06 | 原始值进公式+0 | 从原始文本构造Decimal/BigDecimal，节点12位 | |
| TC-052 | FR-7/AC-15 | P0 | 费率/阈值 | 保存读取比较 | 业务scale/schema不变，传输string，比较用Decimal/BigDecimal | |

21 列：quotation 3；quotation_line_item 6；quotation_line_component_data 1；costing_order 2；material_price_review 1；material_price_review_column 6；quotation_price_revision 1；material_price_update_job_item 1。

## 6. 冻结、DRAFT、语义、重复与回归

| TC | FR/AC | 级 | 前置数据 | 步骤 | 明确预期 | 实际结果 |
|---|---|---:|---|---|---|---|
| TC-053 | FR-11/AC-16 | P0 | FX-04报价 | 记md5/xmin/updated_at；详情/snapshot/ensure/export/刷新3次后重查 | 全部不变，SQL日志无该单UPDATE | |
| TC-054 | FR-11/AC-16 | P0 | FX-04冻结核价 | 打开核价/详情/比较并导出 | 报价/核价冻结事实不变，无重算/回写 | |
| TC-055 | FR-6/11/AC-16 | P0 | 非DRAFT历史numeric | 只读解析显示 | DB numeric JSON字节/md5不变 | |
| TC-056 | FR-11/AC-17 | P0 | DRAFT | 只打开不保存 | 不升级格式、不UPDATE | |
| TC-057 | FR-11/AC-17 | P0 | DRAFT | 显式重算 | 关系列12位，新JSON节点decimal string | |
| TC-058 | FR-11/AC-17 | P0 | DRAFT | 显式保存后GET/刷新/重开 | 新格式稳定，结果不漂移 | |
| TC-059 | FR-11/AC-17 | P1 | 已规范DRAFT | 同值重复保存/重算3次，并发提交同值 | 无丢精度/重复快照/死锁/部分写，最终值确定 | |
| TC-060 | FR-6/AC-18 | P0 | 双端golden | 执行5/0、0/0 | 返回`"0"`，无未捕获异常 | |
| TC-061 | FR-6/AC-18 | P0 | 缺失field/path、null/空值 | 参与+1 | 缺值按0得`"1"`，存储null仍null | |
| TC-062 | FR-6/AC-18 | P1 | 空token | 求值 | 沿用父任务返回`"0"` | |
| TC-063 | FR-6/AC-18 | P0 | 非法表达式 | 单算/批算 | 沿用原错误；批量仅单项失败；无500/静默错误值 | |
| TC-064 | FR-6/AC-18 | P0 | `2×3÷4` | 双端求值 | 工作值/显示均`1.5` | |
| TC-065 | FR-6/AC-18 | P1 | 一元负号/优先级/括号 | 双端求值 | 与父任务golden一致 | |
| TC-066 | FR-12 | P0 | 前9位同、第10~12位异 | 提交前对账 | 判不一致并阻止提交 | |
| TC-067 | FR-12 | P0 | 12位数值相等、scale字符串异 | 规范化对账 | 判一致，不因尾零假报警 | |
| TC-068 | AC-19 | P0 | 后端定向 | 跑精度/公式/JSON/写点/迁移/导出测试 | 0 failure/error/unexpected skip，记录统计 | |
| TC-069 | AC-19 | P0 | 同轮master基线 | worktree跑`./mvnw test` | 无新增失败，Skipped增长有解释 | |
| TC-070 | AC-19 | P0 | 后端 | compile | 编译成功 | |
| TC-071 | AC-19 | P0 | 前端 | `npm test` | 精度/格式/golden/快照/折扣/对账无新增失败 | |
| TC-072 | AC-19 | P0 | 前端 | tsc、Vite build、模块200 | 0 TS错误、build通过、非SPA fallback、console无新错 | |

## 7. Playwright、性能与放行

| TC | FR/AC | 级 | 前置数据 | 步骤 | 明确预期 | 实际结果 |
|---|---|---:|---|---|---|---|
| TC-073 | AC-20 | P0 | FX-02/SIMPLE | 创建→填写→保存→刷新→重开→对账 | 12位往返不变，显示正确，各阶段加载中=0 | |
| TC-074 | FR-9/AC-20 | P0 | TC-073单据 | 打开报价编辑/核价/详情抓DOM | 三视图一致，每视图加载中=0 | |
| TC-075 | AC-20 | P0 | SIMPLE/COMPOSITE | 跑quotation-flow和composite-product-flow | 两spec passed；全部Tab加载中=0；无空白/清零/行数累加 | |
| TC-076 | FR-12/AC-20 | P0 | page.route篡改第10~12位 | 保持9位DOM同后提交 | 对账明确阻断；移除route后恢复通过 | |
| TC-077 | 性能 | P1 | FX-07；同一机器、同一 Java/Node/DB 配置和同一只读数据快照 | 先确认无其它 mvn/Playwright/批任务、`pg_stat_activity` 无其它 CPQ 活跃写事务且共享 8081/5174 请求量空闲；按 M1→F1→M2→F2→M3→F3→M4→F4→M5→F5 串行交替执行，采用一致的冷/暖缓存处理并取各自中位数 | 分支相对 master 增幅≤20%；超出判失败并定位瓶颈；任一轮发生并发写、服务热重载、CPU/DB争用或无法证明同一快照，则整组数据作废并标 BLOCKED，不得用于放行 | |
| TC-078 | 性能/N+1 | P0 | N与2N数据 | 统计公式链SQL | SQL常数，不随N增长，无新增N+1 | |
| TC-079 | 快照体积 | P1 | 同一快照 | 比number/string字节和响应 | 记录增长；不得触发分页/裁剪/超时 | |
| TC-084 | FR-7/交付治理 | P0 | 最终 `api.md`、`dev-docs/main-api.md` | 按方法+路径逐端点核对 P1~P4；检查文件头日期和每个覆盖端点来源标记 | 最终实际 decimal-string 契约已覆盖回 main-api；来源为 task-0810、日期正确；P1-18a~e 无遗漏 | |
| TC-085 | FR-5/AC-7 | P0 | migration 目录、flyway_schema_history | 按数字解析全部 V*.sql，检查本任务版本文件和 history | 本任务迁移号在文件系统与 history 中均唯一、文件名/description 对应；无同号第二文件、无 checksum mismatch，success=t | |
| TC-086 | AC-19/交付治理 | P0 | 后端、Vitest、Playwright 原始报告 | 汇总所有 skipped/disabled/assumption/`test.skip` | 本任务 AC/FR 用例 0 skip；存量 skip 每条有名称、原因和同轮基线证据；任一未解释 skip 阻止交付 | |

## 8. 前后端测试准入条件

### 8.1 后端完成后准入

以下条件全部满足，测试工程师才开始后端执行批次：

1. B1~B11 有逐项完成清单；记录待测 HEAD、工作区 diff hash 和精确文件列表；若已 commit 再附 commit。开发者自检包含 Tests run/Failures/Errors/Skipped。
2. 后端 compile 通过；本任务新增/修改的定向测试 0 failure、0 error、0 unexpected skip。
3. FormulaCalculator、六个 JEXL 点、JSON adapter、业务写点、导出均有自动化测试入口，不能只交代码。
4. API-P1~P4 实际请求/响应 DTO 清单已提供；P1-18a~e、数量/费率字段、所有可写精度字段已展开。
5. Flyway 实际版本已重新确认唯一；迁移 SQL 仅 ALTER TYPE；21 列 JPA mapping 清单齐全。
6. 禁浮点静态自检已按改动文件逐命中分类；精度链 `.doubleValue()`、Double、DoubleNode 为 0。
7. 提供可独占的测试数据/事务 fixture；禁止拿并发会话正在修改的报价作为冻结零写或性能样本。

若后端完成、前端尚未完成，可先执行 §9 阶段 A~F；API string-only 和 DB 正确不等于整任务交付，阶段 G~J 必须等待前端准入。

### 8.2 前端完成后准入

1. F1~F10 有逐项完成清单；记录待测 HEAD、工作区 diff hash 和精确文件列表；若已 commit 再附 commit。
2. DecimalString/DecimalValue、无损 parser、共享 golden、三视图 formatter、确定性 Playwright fixture 已提交。
3. TypeScript、Vitest 定向自检 0 新失败/未解释 skip；受影响精度类型无 `| number`。
4. API-P1~P4 service 类型与最终 api.md 一致；mock 数据同样使用 decimal string，不得让 mock 掩盖 number 响应。
5. quotation-flow、composite-product-flow 与 task0810 精度断言可执行，测试数据不会污染其它会话。

### 8.3 联调与最终准入

1. 测试工程师记录同一特性分支的精确 HEAD、工作区 diff hash 和文件清单；若前后端改动已 commit 再记录 commit。测试期间任一工作区变化都必须重新计算 diff hash，不存在未说明的实现漂移。
2. 8081/5174 服务的代码内容与“待测 HEAD + diff hash”一致；若共享 dev server 指向主工作区旧代码，不得用其结果验特性分支。
3. 数据库/迁移版本明确；自动化 test 库与联调 dev 库不得混写证据。
4. 邮件 MockMailbox、PDF 文本提取、POI Excel 单元格检查工具可用。
5. 性能环境满足同机、同快照、串行交替和共享服务空闲；不满足则 TC-077 明确 BLOCKED。

## 9. 后端完成后的测试执行顺序

| 阶段 | 执行内容 | 用例 | 进入下一阶段条件 |
|---|---|---|---|
| A 文档/版本冻结 | 记录 HEAD、工作区 diff hash/文件清单；已 commit 时追加 commit；记录 API DTO/路径、迁移号、fixture | TC-084~086 的静态部分 | 路径/字段/版本无空槽，待测内容可唯一复现 |
| B 零浮点静态门禁 | 后端 BigDecimal/Double 搜索，DTO/Jackson/Excel 写入审查 | TC-010、012~016 | 精度链 0 浮点；否则立即退回后端 |
| C 后端精度定向 | PrecisionPolicy、FormulaCalculator、黄金集、六 JEXL、语义 | TC-001、003~006、010、014~015、042~052、060~065 | P0 全通过、无 skip |
| D DB/Flyway | 自动迁移、21 列、JPA、旧值不重算、容量/回滚、迁移唯一 | TC-030~033、080~081、085 | success=t、26/12、零部分写 |
| E API/JSON | P1~P4 逐端点 string、number=400、历史 numeric 往返、权限 | TC-017~029、082~083 | 全端点/字段有证据；无 500 |
| F 后端业务边界 | BL-0159/0160、升版、冻结零 UPDATE、DRAFT 显式升级、后端导出/邮件 | TC-037~059 | 冻结 md5/xmin 不变；文件文本正确 |
| G 前端静态/单测 | 前端禁 number、precision/format/golden/parser/state、TS/Vitest/build | TC-002、007~013、019~027、071~072 | 前端准入已满足且 0 新失败 |
| H 联调/三视图/E2E | 保存重开、报价/核价/详情、SIMPLE/COMPOSITE、对账注入 | TC-034~041、066~067、073~076 | 三视图一致、加载中=0 |
| I 性能与全量 | 后端/前端全量、N+1、快照体积、串行交替 A/B | TC-068~070、077~079、086 | 无新增失败/未解释 skip；性能≤20% 或明确阻塞 |
| J 治理与报告 | 回写 main-api、复查迁移唯一、生成 test-report AC 对照 | TC-084~086 + AC 矩阵 | 所有 P0/P1 闭环后交主线亲验 |

失败处理：任一阶段 P0 失败立即停止其下游依赖阶段，登记缺陷并回流对应工程师；修复后从失败阶段开始重测，并复跑所有已通过但受影响的阶段。不得跨过 DB/API 失败直接跑 UI 来制造假绿。

放行条件：

1. AC-1~AC-20均有实际通过证据；P0/P1缺陷全部修复并复测。
2. 存量失败必须有同轮master A/B证据，不得口头排除。
3. 后端、前端、SQL、API、性能、Playwright均保留原始输出。
4. 冻结零写入有md5/xmin/SQL日志；Excel文本单元格有POI/xlsx证据。
5. main-api 已逐端点回写、迁移号唯一、无未解释 skip。
6. 产出`test-report.md`并完成逐用例、缺陷、AC对照后才进入主线亲验。

主线裁决项：

1. 数量、费率按“传输 string、业务 scale 不变”执行，TC-082/083 的专项清单必须覆盖实际 DTO/嵌套字段，不能只测金额。
2. 性能 A/B 无法证明同机、同快照、串行交替且共享服务空闲时，TC-077 标 BLOCKED，不能以噪声数据放行。
