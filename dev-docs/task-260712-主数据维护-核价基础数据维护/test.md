# 测试用例文档 · 主数据维护-核价基础数据维护（task-0712）

> 编写人：测试员 | 日期：2026-07-11 | 送审对象：技术经理
> 依据：`需求说明.md`（C1–C13 + §8 验收标准）、`api.md`（7 接口契约）、`backtask.md`（B1–B7）、`fronttask.md`（F1–F8）。
> 说明：本文档为**交付前评审版**，待开发组合并后逐项执行并回填结果到 `test-report.md`。
> 覆盖口径：8 条验收标准全覆盖 + 7 接口契约 + 16 版本组异构口径 + 护栏/并发/精度/权限/回归。

---

## 一、测试环境与前置条件

| 项 | 内容 |
|----|------|
| 后端 | Quarkus dev `http://localhost:8081`（探活：`/api/cpq/pricing-basic-data/parts` 未带 token 返 `401`） |
| 前端 | Vite dev `http://localhost:5174`（`/api` proxy 到 8081） |
| 数据库 | 远程 PostgreSQL `cpq_db`；核价版本化表已由 tesk-0709 落库（V6 表 + is_current） |
| curl 探活纪律 | 一律加 `--noproxy '*'`（本机 http_proxy 会致 502） |

**测试账号（已授权：测试期由测试员按需创建，测试员具全部权限）**

| 角色 | 账号 | 用途 |
|------|------|------|
| SYSTEM_ADMIN | `admin` / `Admin@2026` | 全权限，跑接口契约与升版 |
| PRICING_MANAGER | 测试期创建 | **核心角色**：验证可见入口 + 编辑/升版权 |
| SALES_MANAGER | 测试期创建 | 验证可见入口但**只读**（无编辑权） |
| 无关角色（如普通 SALES_REP） | 测试期创建 | 验证入口不可见 / 403 |

> 已获授权：测试员在测试期直接创建上述角色账号（写库/建用户），并拥有全部业务权限；无需额外申请。

**测试数据前置**
- 至少 1 个"有多张 sheet 核价数据"的销售料号（configuredCount 接近 16，用于列表 N/16 与抽屉多 tab 校验）。
- 至少 1 个"仅个别 sheet 有数据"的料号（验证 N/16 部分配置 + 空 tab 从零新建）。
- 至少 1 个同料号在 `production_energy` 同时有 DEPRECIATION 与 ENERGY 数据的样本（验证两 tab 互不干扰）。
- 至少 1 个有历史多版本（version ≥ 2 版）的版本组（验证版本切换只读）。
- 建议 ≥100 料号规模用于零 N+1 验证。

> 若上述样本不足，测试前需请开发组或通过导入（tesk-0709 流程）补齐；缺样本的用例标记为 **BLOCKED** 并在报告中说明。

---

## 二、用例编号规则与优先级

- 编号：`TC-<组>-<序号>`，组见下方分节（A~N）。
- 优先级：**P0**=阻断/验收必过；**P1**=重要；**P2**=一般/边界。
- 结果列在 `test-report.md` 回填：Pass / Fail / Blocked / N/A。

---

## A. 入口与权限（验收 §8.1、C10、F1、F6）

| 编号 | 优先级 | 用例 | 步骤 | 预期结果 |
|------|--------|------|------|----------|
| TC-A-01 | P0 | 新增第 5 tab | PRICING_MANAGER 登录 → 进「主数据维护」`/master-data-hub` | 页面出现第 5 个 tab「料号核价」，位于工序/料号/材质/数据模板之后 |
| TC-A-02 | P0 | 菜单角色可见 | PRICING_MANAGER 登录 → 看左侧菜单 | `/master-data-hub` 菜单项可见（角色已追加 PRICING_MANAGER） |
| TC-A-03 | P0 | SALES_MANAGER 可见入口 | SALES_MANAGER 登录 | 菜单可见 + 「料号核价」tab 可进入（仅查看） |
| TC-A-04 | P0 | SYSTEM_ADMIN 可见入口 | admin 登录 | 菜单可见 + tab 可进入，具编辑权 |
| TC-A-05 | P1 | 无关角色不可见 | 无关角色（非上述三角色）登录 | 菜单不出现 `/master-data-hub`；直接访问接口返 403 |
| TC-A-06 | P0 | 编辑权门控-有权 | PRICING_MANAGER / admin 进抽屉当前版 tab | 显示「保存」「新增行」「删除行」，编码列为下拉/输入可编辑 |
| TC-A-07 | P0 | 编辑权门控-只读 | SALES_MANAGER 进抽屉当前版 tab | 全 tab 只读：无保存/新增/删除按钮，编码列纯文本展示（C7/F6） |

---

## B. 料号列表（验收 §8.2、C3/C4、F2、接口 §1）

| 编号 | 优先级 | 用例 | 步骤 | 预期结果 |
|------|--------|------|------|----------|
| TC-B-01 | P0 | 只列有核价数据料号 | 打开「料号核价」tab | 列表仅含"至少一张 PRICING 版本化表有 `is_current=true`"的料号；无核价数据料号不出现 |
| TC-B-02 | P0 | 列展示完整 | 观察列表列 | 展示 品名/料号/规格/尺寸 + 「已配置 N/16」+ 最近更新时间，均有值不为空白错列 |
| TC-B-03 | P0 | N/16 汇总正确 | 取一个已知配置数料号，比对 configuredCount | N = 该料号 distinct 有 is_current 数据的版本组数；与抽屉内"有数据 tab"个数一致 |
| TC-B-04 | P1 | 最近更新时间正确 | 比对某料号 lastUpdatedAt | = 该料号所有版本组 `max(updated_at)` |
| TC-B-05 | P0 | 按料号搜索 | 搜索框输入完整/部分料号 | 结果按 material_no 模糊过滤，命中正确 |
| TC-B-06 | P0 | 按品名搜索 | 搜索框输入品名关键字 | 结果按 material_name 模糊过滤，命中正确 |
| TC-B-07 | P1 | 搜索防抖 | 连续快速输入 | 不每字触发请求，停顿后一次请求（F2 防抖） |
| TC-B-08 | P1 | 分页 | 数据 >1 页时翻页 | 分页正确，total/page/size 与接口一致 |
| TC-B-09 | P2 | 空态 | 搜索无结果 / 无任何核价料号 | 显示友好空态"暂无有核价数据的料号"，不报错 |
| TC-B-10 | P1 | 主入口点行进抽屉 | 点击某行 | 打开抽屉（传对应 materialNo）；**行内无动作按钮**（列表规范） |

---

## C. 抽屉与 16 tab 结构（验收 §8.3、C2/C9、F3、接口 §2/§3）

| 编号 | 优先级 | 用例 | 步骤 | 预期结果 |
|------|--------|------|------|----------|
| TC-C-01 | P0 | 抽屉从右滑出 | 点行开抽屉 | Ant Drawer `placement=right` 宽 1200；标题显示料号+品名/规格/尺寸（非 Modal，符合 UI 规范） |
| TC-C-02 | P0 | 固定 16 tab | 观察抽屉 tab 栏 | 恒 16 个 tab，顺序按 order；名称与需求 §4.1 一致（生产耗材BOM/包装材料BOM/来料加工费/来料其他费用/加工费&组装费/成品其他费用/电镀成本/其他外加工成本/物料BOM/物料与元素BOM/产能/工时单价/折旧/能耗/辅助能耗/模具工装成本） |
| TC-C-03 | P0 | tab 徽标-有数据 | 看有数据的 tab | 徽标显示当前版本号（overview.currentVersion） |
| TC-C-04 | P0 | tab 徽标-无数据 | 看无数据的 tab | 徽标显示"未配置" |
| TC-C-05 | P1 | 懒加载 | 切到某 tab | 切到才请求该组 rows（Network 验证），未切 tab 不预取 |
| TC-C-06 | P1 | 元数据缓存 | 多次开抽屉 | `GET /sheets` 元数据全局缓存一次，不每次重取 |
| TC-C-07 | P0 | overview 16 项齐 | 抽屉打开 | overview 返 16 项，无数据组 hasData=false（不漏 tab） |

---

## D. 数据读取与名称列（验收 §8.3、C8、F4、接口 §4）

| 编号 | 优先级 | 用例 | 步骤 | 预期结果 |
|------|--------|------|------|----------|
| TC-D-01 | P0 | 当前版数据正确 | 进有数据 tab | 表体显示该料号该组 `is_current=true` 行，值与库一致 |
| TC-D-02 | P0 | 编码列旁名称列只读 | 看含工序号/元素/来料料号的组 | 编码列旁有只读名称列（工序名/元素名/来料品名），值由主表 join 带出 |
| TC-D-03 | P0 | 名称列 join 正确 | 比对 operation_no→operation_name | 名称与 process_master 对应记录一致，无错配/无"（共N项）"多值错乱 |
| TC-D-04 | P1 | AXIS 列不可编辑 | 看料号/price_type 轴列 | 轴列不渲染为可编辑（或只读展示），编辑态也不可改 |
| TC-D-05 | P0 | 主从 BOM 展示 | 进物料BOM / 物料与元素BOM | 返回子表明细行；主表信息（bom_type/production_no 等）在 masterInfo 展示 |
| TC-D-06 | P1 | 数值精度保留 | 看 DECIMAL 列（单价/不良率） | 保留后端精度（字符串传值），无浮点截断/尾零丢失（参照核价精度既往教训） |

---

## E. 版本切换（验收 §8.4、C7/C11、F3、接口 §5）

| 编号 | 优先级 | 用例 | 步骤 | 预期结果 |
|------|--------|------|------|----------|
| TC-E-01 | P0 | 列出历史版本 | 有多版本组顶部版本下拉 | 列出全部版本号，含 is_current 标记 |
| TC-E-02 | P0 | 版本元信息 | 看下拉选项内容 | 每项显示 版本号·来源(IMPORT/MANUAL)·操作人·时间（C11） |
| TC-E-03 | P0 | 默认选当前版 | 打开 tab | 默认选中 is_current 版，且可编辑（有权时） |
| TC-E-04 | P0 | 切历史版只读 | 切到历史版本号 | editable=false：保存/新增/删除隐藏，全列纯文本（C7 历史版恒只读） |
| TC-E-05 | P0 | 历史版数据正确 | 切到某历史版 | 显示该版本号对应组行（is_current 不限），与库一致 |
| TC-E-06 | P0 | 切回当前版恢复可编辑 | 从历史版切回当前版 | 有编辑权用户恢复可编辑态 |
| TC-E-07 | P1 | 操作人显示为用户名 | 看 MANUAL 版本操作人 | 显示用户名（由 updated_by UUID join 用户表），非 UUID |

---

## F. 编辑保存-升版（验收 §8.5、C5/C6、F5、接口 §6）

| 编号 | 优先级 | 用例 | 步骤 | 预期结果 |
|------|--------|------|------|----------|
| TC-F-01 | P0 | 改值升版 | 当前版改一个 VALUE 单元格 → 保存 | result=UPGRADED；新版本号=旧+1；旧组 is_current=false、新组 true；source=MANUAL |
| TC-F-02 | P0 | 增行升版 | 新增一行填值 → 保存 | UPGRADED，版本+1，新组含新增行 |
| TC-F-03 | P0 | 删行升版 | 删除一行（保留≥1行）→ 保存 | UPGRADED，版本+1，新组少该行 |
| TC-F-04 | P0 | 内容未变不升版 | 打开即保存（未改任何值）| result=UNCHANGED；`message.info("内容未变化，未产生新版本")`；不写库、版本号不变、行数不变 |
| TC-F-05 | P0 | 升版后 UI 刷新 | 保存成功后 | `message.success("已保存，版本 X")`；徽标/版本下拉/rows 刷新到新当前版 |
| TC-F-06 | P1 | 保存中禁用 | 点保存瞬间 | 按钮 loading + 禁用，防重复提交 |
| TC-F-07 | P0 | source 写 MANUAL | 改值保存后查版本列表 | 新版本 source=MANUAL、operator=当前用户 |
| TC-F-08 | P1 | 只读值列不参与但保存不丢 | 改值保存后重开 | NAME 列不回传但重开正确带出；未改列值不丢失 |
| TC-F-09 | P0 | 指纹比对基于内容真变 | 把值改成 A 再改回原值 → 保存 | 视为未变→UNCHANGED（与导入口径一致 C6） |
| TC-F-10 | P1 | 主从 BOM 升版 | 物料BOM 子表整批改 → 保存 | 主表 bom_version+1，子表多版本保留（不物理删旧版子表） |

---

## G. 空 tab 从零新建（验收 §8.6、C9、F3、接口 §6 CREATED）

| 编号 | 优先级 | 用例 | 步骤 | 预期结果 |
|------|--------|------|------|----------|
| TC-G-01 | P0 | 空 tab 渲染空表 | 进 hasData=false 的 tab | 渲染空 EditableSheetTable + 可「新增行」，无红错 |
| TC-G-02 | P0 | 从零新建保存 | 空 tab 新增行填值 → 保存 | result=CREATED；version=`2000`；is_current=true；徽标从"未配置"变 2000 |
| TC-G-03 | P0 | 新建后可再升版 | 对刚建 2000 版再改值保存 | UPGRADED，version=2001 |
| TC-G-04 | P1 | 空表直接保存拦截 | 空 tab 不加行直接保存 | 422（至少留一行），提示"至少保留一行" |
| TC-G-05 | P1 | 列表 N/16 联动 | 空 tab 新建后回列表 | 该料号 configuredCount +1 |

---

## H. 编码/枚举列录入（验收 §8.7、C12/C13、§4.4.0、F4、接口 §7）

| 编号 | 优先级 | 用例 | 步骤 | 预期结果 |
|------|--------|------|------|----------|
| TC-H-01 | P0 | MASTER 下拉-工序 | P13/18/23 等工序号列点下拉 | 远程搜索 `GET /lookup/process`，返 process_no+process_name；选中带出工序名到 NAME 列 |
| TC-H-02 | P0 | MASTER 下拉-元素 | 物料与元素BOM 元素列 | `GET /lookup/element`（status=ACTIVE），选中带出 element_name |
| TC-H-03 | P0 | MASTER 下拉-来料料号 | 来料加工费/来料其他费用 code 列 | `GET /lookup/material`，选中带出 material_name（来料品名） |
| TC-H-04 | P1 | lookup 关键字/limit | 输入关键字 | 按 keyword 模糊 + limit（默认 20）返回，前端 Select 远程搜索正常 |
| TC-H-05 | P0 | ENUM 下拉 | 币种/单位/计算类型/是否有效列 | 固定枚举 options（含 CHECK 约束值如 capacity.production_type∈UNIT/BATCH/BATCH_FIXED），可选 |
| TC-H-06 | P1 | ENUM 未知可输入回退 | ENUM 列输入非预设值 | 允许自定义输入（showSearch 回退），不硬拦（§4.4.0 B） |
| TC-H-07 | P0 | P22 电镀费类型二选一 | 电镀成本 cost_type | 固定二选一（电镀加工费/电镀材料费），ENUM 渲染 |
| TC-H-08 | P0 | FREE 自由文本 | 要素名称/模具编号/物料BOM组成件 | 普通 Input，可自由录入（C13） |
| TC-H-09 | P0 | MASTER 非法值校验 | 保存含主表不存在的编码 | 后端 400（或宽松模式告警），提示具体列错误 |
| TC-H-10 | P1 | 名称列联动刷新 | 改编码列后 | NAME 列随编码联动重取/刷新，不进指纹比对（改名称本身不触发升版） |

---

## I. 护栏与并发（验收 §8.5/§8.8、C5/C6、B4、接口 §6）

| 编号 | 优先级 | 用例 | 步骤 | 预期结果 |
|------|--------|------|------|----------|
| TC-I-01 | P0 | 乐观锁 409 | 用户A/B 同载入 v2003；A 先保存升到 2004；B 再用 expectedCurrentVersion=2003 保存 | B 收 409；前端 Modal"该数据已被他人升级，请刷新后重试" |
| TC-I-02 | P0 | 从零新建冲突 409 | expectedCurrentVersion=null 但库内已有当前版 | 409（从零新建冲突） |
| TC-I-03 | P0 | 至少留一行 422 | rows 传空数组保存 | 422，提示"至少保留一行" |
| TC-I-04 | P0 | 轴锁定 | body 篡改 materialNo/price_type/system_type | 服务端忽略/以 path+registry 常量为准，不按 body 写入 |
| TC-I-05 | P0 | 并发不产生双 current | 高并发同轴保存（脚本模拟） | 同轴 pg_advisory_xact_lock 串行；结束后该组 is_current=true 恰 1 组，无重复版本号 |
| TC-I-06 | P1 | 事务一致性 | 保存中途构造异常 | 整组回滚，不留半升版脏态 |
| TC-I-07 | P0 | production_energy 独立版本 | 同料号改折旧组保存升版 | 折旧升版**不影响**能耗版本号，反之亦然（price_type 隔离，V325 唯一索引含 price_type） |

---

## J. 后端接口契约（api.md 7 接口，curl + admin token 直测）

> 均以 admin token 直接打接口验证状态码与响应结构；未带 token 验 401；错误角色验 403。

| 编号 | 优先级 | 接口 | 用例 | 预期结果 |
|------|--------|------|------|----------|
| TC-J-01 | P0 | 通用 | 未带 token 访任一 GET | 401 |
| TC-J-02 | P0 | 通用 | SALES_MANAGER 访 PUT 保存 | 403（写权仅 PRICING_MANAGER/SYSTEM_ADMIN） |
| TC-J-03 | P0 | GET /parts | 带 token + keyword/page/size | 200；返 total/page/size/items，items 字段齐（materialNo/Name/spec/dimension/configuredCount/totalSheets=16/lastUpdatedAt） |
| TC-J-04 | P0 | GET /sheets | 取元数据 | 200；返 16 sheets，每项含 sheetKey/tabName/group/order/masterDetail/salesPartAnchor/columns[]；columns 含 name/label/type/role/editable/dropdown |
| TC-J-05 | P0 | GET /parts/{no}/overview | 取概览 | 200；返 16 项 sheets，字段 hasData/currentVersion/versionCount/lastUpdatedAt |
| TC-J-06 | P0 | GET .../rows 当前版 | 不传 version | 200；isCurrent=true、editable 计算正确、rows 含 NAME 列 |
| TC-J-07 | P0 | GET .../rows 历史版 | 传 version=历史号 | 200；isCurrent=false、editable=false、返该版组行 |
| TC-J-08 | P0 | GET .../versions | 取版本列表 | 200；versions 含 version/isCurrent/source/operator/operatedAt |
| TC-J-09 | P0 | PUT .../rows | 三态返回 | UNCHANGED/UPGRADED/CREATED 各场景 result+version+isCurrent 正确 |
| TC-J-10 | P0 | GET /lookup/{type} | process/element/material | 200；items 含 code+name；单查询+limit |
| TC-J-11 | P1 | 错误码 | 不存在料号/sheetKey | 404 |
| TC-J-12 | P1 | 错误码 | 非法 masterType | 400/404（按实现） |

---

## K. 性能 · 零 N+1（验收 §8.8、B3/B6）

| 编号 | 优先级 | 用例 | 步骤 | 预期结果 |
|------|--------|------|------|----------|
| TC-K-01 | P0 | 列表零 N+1 | ≥100 料号下调 `GET /parts` | SQL 次数恒定（不随料号数增长）；后端日志/计数验证无逐料号循环查库 |
| TC-K-02 | P0 | overview 零 N+1 | 调 `GET .../overview` | 一条聚合查询装配 16 项，非逐 sheet 查 16 次 |
| TC-K-03 | P1 | rows NAME 列一次 join | 多行组读取 | NAME 列 IN 集合一次 join，非逐行查名 |
| TC-K-04 | P1 | 列表响应耗时 | ≥100 料号列表 | 响应时间在合理范围（无明显 N+1 卡顿） |

---

## L. 16 版本组异构口径专项（api.md §8、需求 §4.1 三错位点）

| 编号 | 优先级 | 用例 | 预期结果 |
|------|--------|------|----------|
| TC-L-01 | P0 | P16+P17 合并组 | 「来料其他费用」= INCOMING_OTHER 一个版本组，两 sheet 数据合并展示与升版口径一致 |
| TC-L-02 | P0 | P19+P20 合并组 | 「成品其他费用」= FINISHED_OTHER 一个版本组，合并口径正确 |
| TC-L-03 | P0 | P08 拆两线 | 「产能」(capacity/calc_version) 与「工时单价」(labor_rate/version_no) 为**两个独立**版本组，各自升版互不影响 |
| TC-L-04 | P0 | 折旧/能耗同表分 price_type | 折旧(DEPRECIATION)、能耗(ENERGY) 落 production_energy，两 tab 按 price_type 正确过滤与写回，版本独立（见 TC-I-07） |
| TC-L-05 | P0 | 版本列异构 | 各组版本列正确：unit_price/labor_rate=version_no、capacity/production_energy/auxiliary_energy/tooling_cost=calc_version、material_bom=bom_version、element_bom=characteristic |
| TC-L-06 | P0 | registry 同源 | 各组 axis/content 列与对应 P*Handler 的 groupKeyColumns/contentColumns 逐列一致（保存升版口径与导入一致，不产生虚假升版/漏升版） |
| TC-L-07 | P1 | 销售料号锚异构 | code / finished_material_no / material_no 三种锚列各组读写正确 |

---

## M. 回归（B6.3，不带坏既有导入）

| 编号 | 优先级 | 用例 | 步骤 | 预期结果 |
|------|--------|------|------|----------|
| TC-M-01 | P0 | 核价重导不受影响 | 本任务合并后跑一遍核价导入（tesk-0709 §7 six-check）| failedRows=0；导入升版口径与本任务上线前一致，无虚假升版 |
| TC-M-02 | P0 | source 加列无副作用 | 报价侧（unit_price 共用表）功能 | 报价 handler 未受 source 加列影响，报价流程正常 |
| TC-M-03 | P1 | is_current 数据一致 | 手工升版 + 导入升版交叉 | 同轴始终恰 1 组 is_current=true，无双 current |

---

## N. 自检与交付证据核对（backtask §B7 / fronttask §F7）

| 编号 | 优先级 | 检查项 | 预期 |
|------|--------|--------|------|
| TC-N-01 | P0 | 后端迁移 | V3xx source 迁移 `flyway_schema_history` success=t |
| TC-N-02 | P0 | 后端探活 | 6 GET + 1 PUT 端点 admin token 全通，PUT 三态正确 |
| TC-N-03 | P0 | 前端编译 | `npx tsc --noEmit` 0 错误 |
| TC-N-04 | P0 | 前端 Vite | 改动 .tsx 各 `http://localhost:5174/src/...` 返 200；主入口 200 |
| TC-N-05 | P0 | 单测 | `PricingMaintenanceServiceTest` 全绿（含 UNCHANGED/UPGRADED/CREATED/409/422/轴锁/主从/production_energy 隔离）|
| TC-N-06 | P1 | 前端冒烟 | 轻量 Playwright（列表→抽屉→改值保存→版本+1）通过（fronttask 建议项） |
| TC-N-07 | P0 | 完成宣告 | 交付说明含"已自检"声明行（前后端各一） |

---

## 三、测试执行顺序建议

1. **接口契约层（J/K/L/I）**：先用 curl + admin token 打通后端，确认三态、护栏、乐观锁、零 N+1、异构口径——后端不稳前端无从验。
2. **权限层（A）**：三角色 + 无关角色分别登录验入口与编辑门控。
3. **功能主线（B→C→D→E→F→G→H）**：列表→抽屉→读→版本→编辑升版→新建→编码列，端到端走查。
4. **回归（M）+ 自检核对（N）**。

## 四、缺陷分级与处理

- **Blocker**：验收 P0 用例失败（如升版口径错、双 current、权限越权、零 N+1 破防）→ 立即反馈开发组返修，报告标红。
- **Major**：P1 失败影响主流程但有绕过。
- **Minor**：P2/UI 细节。
- 每个缺陷在 `test-report.md` 记录：现象 / 复现步骤 / 期望 vs 实际 / 归属（前端/后端/需确认）/ 反馈话术 / 返修后复测结果。

---

> **执行约定（需求方已确认，2026-07-11）**
> 1. **测试账号**：授权测试员在测试期自行创建 PRICING_MANAGER / SALES_MANAGER / 无关角色 账号，测试员具全部权限。
> 2. **零 N+1（TC-K-01）验证手段**：开启后端 SQL 日志/计数进行验证（辅以代码审查旁证）。
> 3. **并发双 current（TC-I-05）**：已授权脚本并发压测。
> 4. **测试样本**：已授权测试员通过导入（tesk-0709 流程）补齐所需样本料号（多 sheet / 折旧+能耗同料号 / 多历史版本）。
