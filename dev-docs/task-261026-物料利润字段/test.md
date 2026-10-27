# 测试计划：物料利润字段

## 1. 分片与环境

| 分片 | 唯一认领的 AC | 范围 | 写入纪律 |
|---|---|---|---|
| S-BE | AC-1~AC-6、AC-11、AC-12 | Registry、导入校验/写入、列表读契约、语义字段树/编译、存量零改写 | 优先使用隔离测试库或事务回滚；测试数据统一 `T261026_BE_` 前缀，`finally` 只清理本前缀。 |
| S-UI | AC-7~AC-10 | 销售产品利润列与编辑单元格、PUT payload/客户隔离、状态切换、逐屏视觉 | 组件测试使用 Mock API；真实接口夹具统一 `T261026_UI_` 前缀并定点清理。 |
| S-E2E | AC-13、AC-20 | 冷启动、全链真实串联、双视图浏览器验收与范围回归 | 迁移合入主线后由主代理串行执行；使用 `T261026_E2E_` 前缀，先取基线，结束逐表核对并清理。 |
| S-QBE | AC-14、AC-15、AC-17~AC-19 | 报价行快照迁移、所有新行入口、历史空值、精度契约、复制与 N+1 | 隔离测试库；数据统一 `T261026_QBE_` 前缀，历史行夹具在迁移前建立或用明确 NULL 夹具模拟，结束定点清理。 |
| S-QUI | AC-16 | 编辑态/只读态提示框位置、文案、极值布局与双视图视觉 | 使用隔离 E2E 单据 `T261026_QUI_`；完整页面优先连隔离后端，只有纯格式分支可 Mock。截图写测试输出目录，不污染仓库。 |

新增 DDL 不在开发子代理阶段直接应用到共享开发库。迁移先在隔离环境验证；合并后按主线规则冷启动测试实例，再做真实 E2E。

## 2. AC 覆盖矩阵

| 测试编号 | 分片 | 覆盖 AC | 场景与硬断言 |
|---|---|---|---|
| T-01 | S-BE | AC-1 | 解析正式 XLSX：物料 10 表头、利润末列且为选填色；`gen_matrix.py` 生成矩阵含 `profit numeric(26,12)`；Registry 同名同类型。 |
| T-02 | S-BE | AC-1 | 旧 9 列物料 Sheet 导入返回 400，明确缺少「利润」；不得静默兼容或 500。 |
| T-03 | S-BE | AC-2 | 利润 `-12.34`、`0`、`123.456789012345` 分别导入；DB 按 `numeric(26,12)` 精确落值，列表 GET 返回十进制字符串。 |
| T-04 | S-BE | AC-2 | 利润 `abc`：400 且错误含物料/行号/利润/不是合法数值；导入涉及表的行数和摘要均与导入前一致。 |
| T-05 | S-BE | AC-3、AC-4 | 新行利润空白得到 NULL；同复合轴再次导入非空值后覆盖，其他客户同料号不变。 |
| T-06 | S-BE | AC-5 | 导入 `1.25` → PUT 改 `-2.5` → 导入利润空白 → DB/GET 仍为 `-2.500000000000`。 |
| T-07 | S-BE | AC-6 | quote item 恒含 profit 键（含 null）；两个 cost 数据集不含该键；SQL 统计为 2，不随行数增加。 |
| T-08 | S-UI | AC-8、AC-9 | PUT 正数/负数/零/小数成功，`updated_at` 变、`source` 不变；PUT null 后 DB 为 NULL；PUT 非数字 400 且原值不变。 |
| T-09 | S-UI | AC-10 | 两客户共享同料号，只更新携带 customerNo 的一行；省略 customerNo 命中两行时 409 且两行都不变；四角色权限回归。 |
| T-10 | S-BE | AC-11 | field-tree 精确包含 `MATERIAL.profit/利润/NUMBER/_物料_利润` 且排在现有物料字段末尾；拖入规则断言默认 `INPUT_NUMBER`。 |
| T-11 | S-BE | AC-11 | compile SQL 等价投影 `ds_quote_material.profit` 为 `"_物料_利润"`；preview 返回私有样本利润，空值返回 null。 |
| T-12 | S-BE | AC-12 | 迁移前后 builder_config、组件字段、已发布模板/快照抽样摘要不变；未选利润的编译 SQL 逐字相同。 |
| T-13 | S-UI | AC-7~AC-9 | 组件测试覆盖 view/edit/saving/failure、Esc、Enter、blur、空值、非法值和客户号透传；保存失败恢复 props 原值。 |
| T-14 | S-UI | AC-7 | 浏览器逐屏比对 3 份原型中由 AC-7 接管的默认、空值、极值与横向滚动；同行其他列仍开抽屉，利润格不误开。 |
| T-15 | S-E2E | AC-13 | 其余 Sheet 导入、生产料号编辑、税务分类、分页/搜索、销售产品空态/抽屉及核价两套字段集合回归。 |
| T-16 | S-E2E | AC-13 | 冷启动：Flyway success=true，`DatasetSchemaSelfCheck` 与语义图加载无错，端点不返 500。 |
| T-17 | S-QBE | AC-14 | 迁移前历史报价行基线 → 执行迁移 → `profit_snapshot IS NULL`；GET 返回 `profit:null`，编辑/详情都显示 `利润: -`，SQL 证据表明未按主数据补值。 |
| T-18 | S-QBE | AC-15、AC-19 | 利润正数/零/负数/NULL 经 customer-part-candidates、existing-products、导入物化和 saveDraft 新增行落库；GET 均为保留数据库 scale 的字符串/null，保存刷新逐值一致且无 double/JSON number；`1.234567891500` 的卡片显示按现行边界为 `1.234567892`。 |
| T-19 | S-QUI | AC-16 | 编辑态与只读态 DOM 顺序均为单位 → VAT → 利润；有值 `利润: 12.5`、空值 `利润: -`；最长值按 9 位显示边界渲染且不遮挡右侧产品名/销售料号，编辑态删除按钮仍可用、只读态仍无删除按钮。 |
| T-20 | S-QBE | AC-17 | 新行冻结 `1.25` → 基础利润改 `9.5` → saveDraft 修改、重算、刷新基础数据、GET，旧行仍 `1.25`；再新增同料号，新行 `9.5`。 |
| T-21 | S-QBE | AC-18 | 选配新铸产品 null、绑定已有产品 `3.75`、复制非空与历史 null 行逐值断言；SQL 计数证明利润查询不随行数增长，公式/小计/VAT/导出前后摘要不变。 |
| T-22 | S-QBE | AC-19 | 旧 payload 不带 profit、modified 历史 NULL 行、滚动兼容场景均成功；历史 NULL 不被补值；后端定向与相关回归通过。 |
| T-23 | S-E2E | AC-20 | Playwright 打开真实报价编辑页与只读详情页：URL/title 正确、非空、无错误遮罩、console error=0；截图中利润框紧跟 VAT，历史样例 `S3120011203` 显示 `利润: -`；前后端规定测试与冷启动均通过。 |

## 3. 正向覆盖自检

- 用户的三个入口均有测试：Excel 导入（T-01~T-06）、产品列表展示维护（T-07~T-09/T-13/T-14）、组件取数配置（T-10~T-12）。
- 两个已选决策均有跨层序列验证：D-1 由 T-03/T-08；D-2 由 T-06。
- 数据库、API、UI 与编译预览均读取同一 `profit`，避免只验某一层假绿。
- 报价卡片新增链覆盖“新行即时值 → 首次保存冻结 → 刷新回读 → 主数据后改不漂移 → 只读详情”完整序列，而非只测最终 DOM。

## 4. 反向与失败覆盖自检

- 缺表头、非数字、精度边界、空值、网络/服务端失败、多客户误更新、未选择利润的存量组件均有反向断言。
- 导入非法值必须整份不落库；页面失败必须回滚显示；迁移不得改写已有配置/快照。
- 核价两套明确断言不出现利润，防范围渗透。
- 历史报价、基础利润为空、旧前端 payload、复制 NULL、极值精度和所有新增行入口均有边界/反向断言。

## 5. 验证命令与证据

- 后端按新增测试类执行定向 Maven 测试，再执行规则要求的相关回归集；原始输出落 `test-report.md`。
- 前端执行 `npx tsc -b`、定向测试与构建/transform 检查。
- 冷启动记录 Flyway 最新成功版本、Schema 自检日志、字段树 JSON、列表 GET/PUT 原始响应、导入错误原始响应。
- UI 记录三份原型逐屏对照截图和浏览器控制台 0 error 证据。
- N+1 记录列表与更新 SQL 条数，不以代码目测代替。

## 6. 独立测试实现边界与收集入口

本节是对独立测试首轮阻塞的处置，不放宽“测试代理不得阅读业务实现”的独立性要求。

### 6.1 允许读取与禁止读取

独立测试代理可读取并复用以下测试基建：

- `cpq-backend/src/test/**`、`cpq-backend/pom.xml`、`cpq-backend/src/test/resources/application.properties`；
- `cpq-frontend/e2e/**`、`cpq-frontend/package.json`、`cpq-frontend/playwright.config.ts`；
- 本任务的 `需求文档.md`、`api.md`、`test.md`。

仍禁止读取 `cpq-backend/src/main/**`、`cpq-frontend/src/**` 以及本次前后端代理的实现 diff。测试必须从文档契约和
可观察行为推出断言，不得翻实现“对答案”。数据库只允许查看隔离库的 schema/metadata，不允许借共享库当前数据洗绿。

### 6.2 测试文件与命名

- S-QBE 新增测试放 `cpq-backend/src/test/java/com/cpq/task261026/`，类/方法名前缀
  `T261026_QBE_`；可复用同目录既有 `Task261026ProfitApiTest` 的登录、前缀清理、RestAssured 和
  Hibernate Statistics 模式，但不得修改 S-BE 已认领断言。
- S-QUI 新增 `cpq-frontend/e2e/task261026-quotation-profit-ui.spec.ts`，测试标题前缀
  `T261026_QUI_`。同步把 `e2e/task261026.config.ts` 的 `testMatch` 扩到两个 task261026 spec，
  不能让既有销售产品 spec 掉出收集范围。
- 后端测试编写完成后的定向命令：
  `DB_NAME=<临时库> ./mvnw -Dtest='com.cpq.task261026.*' test`（从 `cpq-backend/` 执行）。
- 前端测试编写完成后的收集命令：
  `PW_BASE_URL=<临时前端> PW_BACKEND_URL=<临时后端> npx playwright test -c e2e/task261026.config.ts --list`。
  真正运行仍必须等主代理启动独立栈并给出 URL。

### 6.3 隔离栈与夹具所有权

- 主代理负责在执行前创建新的临时数据库和独立前后端端口，并把名称/URL交给测试分片；测试代理不得自行
  DROP 数据库，也不得连接共享 5174/8081 或默认开发库。
- S-QBE 可在隔离库选择既有 ACTIVE 客户作为父记录，但所有新数据必须使用
  `T261026_QBE_` 前缀并在 `finally/@AfterAll` 精确清理；禁止 `DELETE FROM <table>` 无谓词或按全局数量断言。
- S-QUI 使用 S-QBE REST/SQL 夹具口径创建自己的 `T261026_QUI_` 报价；不得依赖某个固定现网报价 UUID。
  若为了纯前端 null/精度分支做 route mock，必须先用真实隔离单据拿到完整响应形状，只覆写
  `lineItems[].profit`，不得手写一份不完整整单 JSON。
- 鉴权、接口、请求体、SQL 观测和稳定 DOM 的完整契约见 `api.md` 第 7 节。

### 6.4 S-QUI 浏览器硬断言

每个编辑态/只读态场景除 T-19 业务断言外，还必须同时断言：

1. URL 和页面标题正确、页面非空、`.qt-product-card` 至少 1 张；
2. 无错误遮罩，浏览器 `console.error` 为 0；
3. 单位/VAT/利润在同一 `.qt-card-header-left` 内且 DOM 顺序严格递增；
4. 1440×900 下利润框不与右侧产品名/销售料号重叠，卡片和页面无意外横向溢出；
5. 编辑态删除按钮存在且仍可触发既有确认/删除交互；只读态删除按钮数量为 0；
6. 截图写 Playwright `outputPath()`/测试输出目录，不写入仓库受跟踪目录。

## 7. 独立测试首轮阻塞处置

- 草稿、创建、复制、刷新、重算、导入物化的 URL 与请求形状：已由 `api.md` §7.1~§7.3 补齐。
- 隔离库、鉴权、夹具和 SQL 观测口径：已由 `api.md` §7.4 与本文件 §6.3 补齐。
- 页面入口和稳定 DOM：已由 `api.md` §7.5 与本文件 §6.4 补齐。
- 现有测试 fixture/helper/构建配置读取权：已由本文件 §6.1 明确开放；业务实现目录仍禁止读取。
- 完整页面不要求猜测最小 Mock 响应：默认使用隔离真实栈；需要 Mock 时只能在真实完整响应上覆写利润字段。
