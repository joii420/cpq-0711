# CPQ 报价系统 - 产品需求文档 (PRD v3.0)

**版本**:v3.0
**日期**:2026-05-13
**状态**:活跃(覆盖 v1.0~v2.8 + 实际未回写部分)
**适用代码基线**:V150 (template 与 costing_template 合并)、import_session V6 staging、PartVersion S2

---

## 0. 文档导读

### 0.1 本文档定位

本文档是 CPQ 报价系统的**当前实际形态**说明,以"已落地代码 + 数据库 V150 schema"为底稿重写,目的:

- 给业务方、PM、新加入的开发/测试/Agent 一份**与代码一致**的需求文档
- 取代过去 `docs/PRD.md` (v2.8) 中已偏离实际或未回写的内容
- 作为后续需求评审、UAT、功能验收的唯一对照标准

### 0.2 与旧 PRD 的关系

旧 PRD (`docs/PRD.md`,2584 行,1.0~2.8)**保留为历史档案**,**不再维护**:

- 旧 PRD 中 Drools 规则引擎、外部系统同步、V5 六步导入向导、品类专属折扣、AI 转化率预测、季度返利等**未落地或已废止**的设计**不再出现**在 v3.0
- 旧 PRD 缺失的"核价单系统、全局变量层、料号版本、导入会话、变量标签、比对标签、Excel 模板独立配置"等模块**首次正式回写**到 v3.0
- 旧 PRD 中**实装一致**的部分(客户/组件/审批/通知/数据源/系统配置/并发锁等)在 v3.0 中保留并精简
- 旧 PRD 80+ 条变更日志在第 9 章"项目演进史"中以决策点形式精炼保留

### 0.3 与其他文档的关系

| 文档 | 关系 | 用途 |
|---|---|---|
| `docs/PRD.md` (v2.8) | 历史档案 | 变更决策回溯 |
| `docs/RECORD.md` | 开发记忆 | 历史 bug、修复决策、跨 session 上下文 |
| `docs/报价单核价单功能总结.md` | 业务速览 | 入门 / 新人 onboarding |
| `docs/配置方法论.md` | 配置手册 | 组件/模板/公式三层配置决策树 |
| `docs/Excel模板配置指南.md` | 操作手册 | Excel 视图列字段配置 |
| `docs/反模式.md` | 避坑速查 | AP-01~AP-22 反模式列表 |
| `docs/archive/操作说明.md` | 用户手册 | 销售/经理/管理员操作步骤 |
| `docs/列表操作规范.md` | UI 规范 | SelectableTable + 工具栏动作模式 |
| `docs/archive/数据一致性方法论.md` | 工程约束 | 数据流约束、迁移策略 |

### 0.4 阅读建议

- **业务 / PM**:1 → 2 → 3 → 4 → 9
- **新开发**:1 → 3 → 4 → 5 → 8 → 附录 + RECORD.md
- **测试**:全章 + 附录 10.5 已知限制
- **AI Agent**:0 → 8 → 附录 + 配置方法论.md

### 0.5 术语速查

完整术语见附录 10.1。最关键的:

| 术语 | 含义 |
|---|---|
| **报价单 (Quotation)** | 销售对外的价格清单,5 步向导完成 |
| **核价单 (Costing Summary)** | 内部成本计算单,7 项 metric,可独立可关联报价 |
| **组件 (Component)** | 可复用的字段+公式单元(投料/回料/加工/电镀等) |
| **模板 (Template)** | 组件组合 + 产品属性 + Excel 列结构,三类:QUOTATION / COSTING / Excel |
| **lineItem** | 报价/核价单中的一条产品行 |
| **BNF 路径** | 数据取值语法 `<sheet>[谓词].<field>` |
| **隐式 JOIN** | 后端自动按 hf_part_no / customer_id 注入谓词 |
| **快照** | 报价提交后冻结的字段防腐机制 |
| **override** | 核价单层面的用户级数据覆盖,不写回基础数据 |
| **driver expansion** | 组件按 `data_driver_path` 自动展开成 N 行 |

---

## 1. 项目背景与目标

### 1.1 业务背景

公司面向制造业客户提供精密金属/电气元件,长期痛点:

- **报价周期长**:销售从询价到出价 3~7 个工作日,客户流失
- **成本计算分散**:材料/工艺/模具/设计成本散落于 Excel + 多本台账,新员工无法独立完成核价
- **报价数据不可追溯**:历史报价 Excel 文件四散,客户事后回询"当时怎么算的"难以复现
- **价格策略落地难**:VIP/钻石客户的阶梯折扣靠人工 lookup,易出错
- **模板版本混乱**:不同业务员用不同 Excel 模板,字段口径不一致

### 1.2 项目目标

**V1 (当前版本)** — 上线已完成:

- 销售在系统内 5 步完成报价,平均出价时间 < 2 小时
- 核价单与报价单联动,**底价**(成本)与**售价**(报价)双视图并存
- 模板 + 组件 + 公式 三层配置,业务管理员可自助加新指标(无需开发)
- 全局变量(汇率/元素价/材料价)统一版本管理,变更可追溯
- 完整的快照机制,半年后看回报价依然可复现当时算法
- 客户料号 vs HF 内部料号双向映射,导入 Excel 自动匹配
- 料号版本管理,客户同一料号的多版本规格可独立报价

**V2 (规划)**:

- 报价单 PDF/Excel 导出
- 核价单批量比较(同料号在不同基础数据版本下的成本差异)
- 公式可配置化(7 项 metric 当前为后端 service 硬编码)
- 同 `linked_template_id` 多份 PUBLISHED 默认 Excel 模板
- 产品外部系统同步(ERP 对接)
- LinkedExcelView 同料号多 summary 多行展开

### 1.3 关键设计原则

| 原则 | 含义 | 体现 |
|---|---|---|
| **快照防腐** | 历史数据冻结,后续基础数据变更不影响已提交报价 | `*_snapshot` 列、`components_snapshot`、`excel_view_snapshot` |
| **配置优于代码** | 业务变化通过配置实现,代码变更频率最低 | 模板 columns JSON、组件 fields/formulas JSON、全局变量 |
| **NULL 传递** | 区分"无数据"和"零值" | V111 后视图不再 COALESCE(0),前端整行清空 |
| **隐式 JOIN** | 业务路径无需写谓词,系统按上下文自动注入 | `ImplicitJoinRewriter` 注入 `hf_part_no` / `customer_id` |
| **抽屉优于弹窗** | 所有表单/详情/向导走 Drawer,与上下文并存 | 见 `CLAUDE.md` 强制规范 |
| **列表工具栏统一** | 行内只放主入口链接,所有动作上提到工具栏 | `SelectableTable` 组件,见 `docs/列表操作规范.md` |
| **单实例部署** | V1 不支持水平扩展,引擎缓存为进程内 | 视图列发现 cache、`CachedSqlCompiler`、`CachedPathParser` |
| **变更可追溯** | 关键表写入走 `VersionedWriter`,字段级日志 | `versioning` 模块 + `change-log` 前端 |

---

## 2. 角色与权限矩阵

### 2.1 角色清单

| 角色代码 | 中文名 | 主要职责 |
|---|---|---|
| `SALES_REP` | 销售代表 | 创建报价单、编辑草稿、提交审批、撤回 |
| `SALES_MANAGER` | 销售经理 | 审批报价单、查看团队报价、维护审批规则 |
| `PRICING_MANAGER` | 定价经理 | 维护核价单、调整 override、维护核价基础数据 |
| `SYSTEM_ADMIN` | 系统管理员 | 用户/角色/部门/区域管理、配置中心、可审批任意报价单(兜底) |

### 2.2 权限矩阵

| 功能区 | SALES_REP | SALES_MANAGER | PRICING_MANAGER | SYSTEM_ADMIN |
|---|---|---|---|---|
| 报价单 创建/编辑草稿/提交 | ✅(本人) | ✅(本人) | — | ✅(全部) |
| 报价单 审批 | — | ✅(分配给我的 + 团队) | — | ✅(兜底,可审批任意) |
| 报价单 撤回(SUBMITTED→DRAFT) | ✅(本人) | — | — | ✅ |
| 报价单 列表查看 | ✅(本人) | ✅(团队) | ✅(只读) | ✅(全部) |
| 客户管理 | ✅(只读) | ✅ | ✅(只读) | ✅ |
| 产品/料号管理 | ✅(只读) | ✅(只读) | ✅ | ✅ |
| 定价策略 | ✅(只读) | ✅ | — | ✅ |
| 核价单 创建/编辑 | — | — | ✅ | ✅ |
| 核价单 发布 | — | — | ✅ | ✅ |
| 核价基础数据(元素/材料/汇率) | — | — | ✅ | ✅ |
| 料号级核价数据(8 张表) | — | — | ✅ | ✅ |
| 组件管理 | — | — | — | ✅ |
| 模板配置(报价/核价/Excel) | — | — | ✅(协作) | ✅ |
| 全局变量 | — | — | ✅(只读) | ✅ |
| 数据源管理 | — | — | — | ✅ |
| 基础数据导入(V6) | — | — | ✅ | ✅ |
| 系统管理(用户/部门/区域/审批规则) | — | — | — | ✅ |
| 系统配置中心 | — | — | — | ✅(REST API,V1 无 UI) |
| 操作日志 / 变更日志 / 锁监控 | — | ✅(本人) | ✅(本人) | ✅(全部) |

### 2.3 账号安全约束

- **首次登录强制改密**:`User.is_first_login=true` + `initial_password_expires_at`,7 天内未改 → 自动锁定
- **密码重置**:通过邮件链接(`password_reset_token`,24 小时有效期)
- **密码强度**:至少 8 位,含大小写字母 + 数字
- **登录失败保护**:连续 5 次失败锁定 30 分钟(进程内计数,单实例)
- **Session**:JVM 内存存储,服务重启后失效

---

## 3. 业务主线一:报价单 (Quotation)

### 3.1 定位

- **业务目的**:销售对客户出价的"价格清单",记录客户/产品/数量/单价/折扣/税费/币种/有效期
- **角色主线**:`SALES_REP` 创建 → `SALES_MANAGER` 审批
- **结果对外**:审批通过后给客户的价格依据(V2 才提供 PDF/Excel 导出)
- **强调**:商务条款(折扣、付款条件、有效期、币种)

### 3.2 五步向导 (QuotationWizard)

```
┌─────────┐  ┌──────────┐  ┌─────────┐  ┌─────────┐  ┌─────────┐
│ 第一步  │→ │ 第二步   │→ │ 第三步  │→ │ 第四步  │→ │ 第五步  │
│ 选择客户│  │ 添加产品 │  │ 优惠策略│  │ 交易条款│  │ 提交审批│
└─────────┘  └──────────┘  └─────────┘  └─────────┘  └─────────┘
```

第二步是核心,占报价制作 80% 时间。

#### 3.2.1 第一步:选择客户

**功能**:

- 客户搜索下拉(模糊匹配 `customer.name` / `customer.code`)
- 新客户创建入口(Drawer)
- 客户画像卡片:等级 / 行业 / 信用额度 / 历史报价数 / 累计成交金额 / 付款方式
- 报价元数据表单:
  - 报价名称(必填,建议:客户名-报价-日期)
  - 联系人(从客户联系人列表下拉,默认选中 `is_primary=true` 的)
  - 电话/邮箱(从联系人自动填充,可改)
  - 项目名称、商机编号、销售代表、报价类型、优先级、销售阶段、预计成交日期
- **模板预选**:
  - `customer_template_id` — 报价单 Excel 视图模板(从客户绑定的报价模板下拉)
  - `costing_card_template_id` — 核价单卡片视图模板(从核价模板下拉)
  - 客户级默认模板由 `customer.default_template_id` 决定
  - **按客户过滤可选模板**(2026-05-14 回填规则):
    - 选定客户 + 产品分类后,前端调 `GET /templates/match-customer-quote?customerId=X&categoryId=Y`
    - 匹配优先级:
      1. **客户专属**(CUSTOMER_SPECIFIC):`template.customer_id = X AND category_id = Y AND status = 'PUBLISHED'`
      2. **通用兜底**(GENERAL_FALLBACK):`template.customer_id IS NULL AND category_id = Y AND status = 'PUBLISHED'`
      3. **无匹配**(NONE):Alert 提示去「模板配置」配置一个
    - **只列 PUBLISHED 模板**;DRAFT / ARCHIVED 不出现在 Step1 下拉(防止未发布模板被业务使用)
    - 命中 CUSTOMER_SPECIFIC 时不再退回通用模板(显式优先 + 不混淆来源)
    - 核价模板(`templateKind='COSTING'`)同规则:`customer_id` 留空 = 全客户通用,非空 = 客户专属
- 产品分类筛选(`product_category_id`)— 决定第二步可选料号范围

**草稿保存**:

- 每 10 秒自动保存到后端(`quotation.status='DRAFT'`)
- 同时写 localStorage 作为离线降级
- 页面加载优先从后端恢复
- **最后写入胜出**,不做乐观锁(单人编辑场景为主)

#### 3.2.2 第二步:添加产品 (核心)

第二步页面 `QuotationStep2.tsx` 顶部有 **mainTab × viewType** 双轴切换:

|  | 产品卡片视图 | Excel 视图 | 比对视图 |
|---|---|---|---|
| **报价单 Tab** | 按报价模板组件渲染卡片,每行表单填值 | 按 `customer_template_id` 反查 `costing_template` 渲染表格,每 lineItem 一行 | 同模板/同产品的多份报价历史并排比对 |
| **核价单 Tab** | 按核价模板组件渲染卡片 (V72) | 按 `costing_card_template_id` 反查 `costing_template` 渲染 (V73/V74) | — |

**核心交互**:

- **添加产品**:点击"添加产品"按钮 → `BulkImportPartsDrawer` 抽屉
  - 从客户料号库勾选(`customer_material_mapping`,显示客户料号 + 已映射的 HF 料号)
  - 从全局料号库勾选(`internal_material`,按 HF 料号)
  - 也支持手填 HF 料号 + 数量
  - 批量加入 lineItems
- **lineItem 列表**:每行展示 HF 料号、客户料号、产品名称、规格、数量、当前小计
- **删除行 / 重排序**:行内只保留主入口(点击进卡片视图),其他动作走工具栏
- ~~**料号版本切换**(V2.8 新):每 lineItem 可点开 `PartVersionDrawer`,选择该料号的历史版本 → 同步重算 `excel_view_snapshot`~~ **(2026-06-23 移除报价单内版本切换 UI 入口 + 产品卡片"版本: vXXXX"标签，详见演进史)**：报价单产品卡片不再显示版本标签、不再提供卡内 `PartVersionDrawer` 切换入口。**后端 `part_version_locked` 机制完全保留**（导入/建单按 `current_version` 锁版本、BNF 路径求值仍按版本注入谓词）；料号版本本身的维护仍走独立 `/part-versions` 页与产品详情「料号版本」Tab（§5.2.5，不受影响）

**双视图共享内存模型**:

- 报价单 Tab 与核价单 Tab **共用同一份 `lineItems`** 内存对象
- 渲染时按各自模板的 `componentId` 集合做**白名单过滤**(报价模板含的组件才渲染)
- 编辑回写走"updater 跑在视图态 → union-merge by componentId 回底层完整数据" sandwich 模式
- 此设计避免 AP-19(双视图共享时的索引错位)

**产品卡片视图**:

- 每个 lineItem 一个卡片
- 卡片头部:HF 料号 / 客户料号 / 产品名称 / 规格 / 单位 / 数量 / 交货天数 (`product_attributes`)
- 卡片体:按模板 `components_snapshot` 渲染 N 个组件 Tab
  - 投料金额(原材料 BOM)
  - 回料金额(回收料)
  - 加工费用(8 种 cost_type:车铣磨等)
  - 电镀方案 / 电镀费用
  - 模具费 / 设计费 / 质检费 / 重量
  - 商务加价(管理费 / 财务费 / 利润 / 税)
- 每组件根据 `data_driver_path` 自动 driver expansion 成 N 行
- 每组件有"小计"(`is_subtotal=true` 字段)
- 卡片底部:产品小计 = `subtotal_formula` 汇总各组件

**手动新增行(Phase 1,2026-06-08)**:

- 每个组件 Tab 表格底部有"+ 添加行"入口,点击在该 Tab 末尾追加一行**手动行**(标记 `_origin='manual'`),用于补录 driver 未覆盖的临时/特殊条目。
- **行内行为**:除公式列(`FORMULA`/`LIST_FORMULA`,由渲染层按用户手填值即时计算)外,所有列初始**全空白**——`FIXED_VALUE` 列渲染为可填文本框(不自动带模板默认值)、`DATA_SOURCE` 列渲染为空下拉/文本框(不自动解析 driver 值)、`INPUT_*` 列为空。
- **仅手动行享受空白待填**:`_origin='manual'` 全程 gated,driver 展开行的既有渲染/取值/小计行为不受影响。
- **计入小计**:手动行公式列结果计入该 Tab 小计 → 产品小计。
- **持久化**:手动行经 `row_data` JSONB 往返保存,草稿保存 / 刷新重开 / 提交后均保留;driver 行数不受手动行影响(渲染时"driver 行 + 手动行"拼接,prune 只裁 driver 行)。
- **详情只读态一致**:提交后详情页(`ReadonlyProductCard`)同样拼接显示手动行(只读展示用户填的值)。
- **核价侧**:核价 Tab 只读,不提供"+ 添加行"入口。
- **Phase 2(待立项)**:driver 行删除(`deleted_driver_keys` + driverRow 内容指纹匹配 + 重开不复活)。

**Excel 视图**:

- 按选定 Excel 模板(`costing_template`)的 `columns` JSON 渲染表格
- 每 lineItem 一行,每列由 `source_type=VARIABLE` 取数 或 `source_type=FORMULA` 公式计算
- 隐藏列模式(V96+):中间数据列 `hidden=true` 不显示,只露公式合成列
- 比对标签(`comparison_tag`):列设置 tag 后参与比对视图分组(V114 注册 13 个 tag)

**比对视图**(仅报价单 Tab):

- 同模板同产品的多份历史报价并排
- 按 `comparison_tag` 分组("成本明细" / "商务加价" / "终价" 等)
- 单一料号在不同基础数据版本下的成本差异可视化

**数据源字段交互**(`source_type=DATA_SOURCE` 的字段):

- 初始状态:占位符 "—" 等待触发
- 触发条件:同行任意被绑定为参数来源的 INPUT 字段失焦且值变化后,300ms 防抖
- 必填参数全部有值 → 调用后端执行 DataSource SQL/API → loading → 填入只读结果
- 必填参数缺失 → 不执行,保持 "—"
- 同参数缓存:同会话内相同 datasource_id + 参数 hash 缓存 5 分钟
- 查询失败:红色 "查询失败",tooltip 展示错误,不阻断其他字段

#### 3.2.3 第三步:优惠策略(v3.1 改为行级表格 + 年用量阶梯)

> v3.1(2026-05-13)起,Step3 从"整单单一折扣率"改为"按行配置 + 年用量阶梯折扣"模型。详细设计见 `docs/superpowers/specs/2026-05-13-step3-annual-volume-discount.md`。旧整单字段 `quotation.system_discount_rate / final_discount_rate` 保留但 V1 不写入(置 100 兜底)。

**UI 形态**:11 列编辑表格(每行一个产品)+ 底部"金额汇总"卡片

| # | 列名 | 类型 | 来源 / 公式 |
|---|---|---|---|
| 1 | 产品 | 只读 | 主行 **生产料号**(productPartNo / hf_part_no,monospace 字体);副行品名(productName 快照,小字灰显;无值则不显示);**不展示客户料号 / 客户品名**(避免与基础数据导入时的客户视角混淆) |
| 2 | 年用量 | **可编辑** 整数 | 用户输入(空 → 0) |
| 3 | 优惠金额来源 | **可编辑** 下拉 | 8 项 metric_code(MATERIAL_COST/PROCESS_FEE/TOOLING_FEE/DESIGN_COST/MANAGEMENT_COST/FINANCE_COST/PROFIT/SUBTOTAL),默认 PROCESS_FEE;在 `v_costing_summary_full` 该列为 NULL 时灰显 |
| 4 | 可优惠金额基数 | 只读派生 | `v_costing_summary_full.<对应列>`,SUBTOTAL 直接读 lineItem.subtotal |
| 5 | 折扣 | 只读派生 | `DiscountStrategy.compute(annual_volume)`,V1 走年用量阶梯 |
| 6 | 优惠金额 | 只读派生 | 基数 × 折扣 / 100 (单件) |
| 7 | 计价单位 | 只读 | productAttributeValues.计量单位 / mat_part.unit / PCS |
| 8 | 币种 | 只读 | quotation.base_currency |
| 9 | 单价 | 只读 | line_item.subtotal(Step2 出参,进 Step3 强刷) |
| 10 | 优惠后单价 | 只读派生 | 单价 - 优惠金额(单件) |
| 11 | 总金额 | 只读派生 | 年用量 × 优惠后单价 |

**年用量阶梯折扣(V1 硬编码)**:

| 年用量区间 | 折扣率 |
|---|---|
| < 200 | 0 (不打折) |
| 200 ~ 499 | 10 |
| 500 ~ 999 | 20 |
| ≥ 1000 | 30 |

**整单总价**:`quotation.total_amount = Σ line_total_amount`(底部"金额汇总" Statistic 与该值同源)

**折扣引擎扩展点**:`DiscountStrategy` 接口 + `@LookupIfProperty(name="cpq.discount.strategy")` 选择实现;V1 = `AnnualVolumeStepDiscount`;V2 切 `PricingStrategyDiscount` 读 `pricing_strategy / pricing_rule` 表(前端 0 改动)

**进入 Step3 的初始化**(对齐 v1.8 步骤间刷新原则):
- 每次进 Step3 强刷 `lineUnitPrice ← line_item.subtotal`(防 Step2 改产品后单价漂移)
- 异步并行拉每行基数 → 引擎实时算下游 5 个派生值
- 用户已存的 annual_volume / discount_source 保留(无则默认 0 / PROCESS_FEE)

**校验**:
- V1 折扣率由引擎硬算,**不允许手动覆盖**(V2 PricingStrategy 上线再开)
- Step3 → Step4 前每行 `line_final_price >= 0`、`annual_volume >= 0`
- Step5 提交后端复算 + ±0.01 容差校验,超容差抛 400

**快照(AP-11 一致性)**:9 字段全部提交时 round-trip 入 `quotation_line_item`(annual_volume / discount_source / discount_base_amount / discount_rate_applied / line_discount_amount / line_unit_price / line_final_price / line_total_amount / discount_rule_code)。半年后审计可复现当时计算依据,不依赖运行时反查。

**实现说明**:
- 后端 `com.cpq.discount.AnnualVolumeStepDiscount`(`@ApplicationScoped + @LookupIfProperty`)
- 前端 `cpq-frontend/src/utils/discountStrategy.ts` 同份阶梯(实时反馈),后端 commit 时复算校验
- V162 迁移加 9 列 + 1 部分索引;旧 `system_discount_rate / final_discount_rate` 字段保留兼容

#### 3.2.4 第四步:交易条款

**功能**:

- 付款条件(自由文本 + 常用条款下拉)
- 整单交货天数(`delivery_cycle`,与产品行 `product_attribute_values.交货天数` 不同 —— 此为整单承诺)
- 报价有效期(`expiry_date`,日期选择)
- 币种(`base_currency`,默认 CNY,可切换 USD/EUR/JPY)
- 备注

#### 3.2.5 第五步:提交审批

**功能**:

- 全报价单 preview(只读卡片视图 + 商务条款卡片)
- 提交前校验:
  - 必填字段全部填写
  - 所有数据源字段已查询完毕(非占位符状态)
  - 所有公式字段无计算异常
  - 后端 JEXL 二次校验,与前端结果误差 ≤ ±0.01 元 → 静默以后端结果覆盖;超出 → 阻止提交
- 提交动作:
  - 写入 `quotation_line_item.*_snapshot` 列(见 3.4 快照机制)
  - 状态 `DRAFT` → `SUBMITTED`
  - 执行 `ApprovalRuleService.route()`,写入 `quotation.assigned_approver_id`
  - 发送站内通知 + 邮件给审批人

### 3.3 报价单状态机

```
DRAFT  ──提交──►  SUBMITTED  ──销售经理审批──►  APPROVED
  ▲                  │                          │
  │  ◄──撤回(销售代表) │                          │
  │                  │ 驳回                     │
  └──────────────────┘                          │
                                                ▼
                          EXPIRED (定时任务每日 00:30 标记 expiry_date < 当天)
                          CLOSED (手动归档)
```

| 状态 | 显示标签 | 可操作 |
|---|---|---|
| DRAFT | 草稿 | 编辑、提交、删除 |
| SUBMITTED | 审批中 | 审批(经理/管理员)、撤回(销售) |
| APPROVED | 已通过 | 查看、复制、标记 ACCEPTED/REJECTED、延期 |
| REJECTED | 已退回 | 编辑(回 DRAFT)、删除 |
| EXPIRED | 已过期 | 查看、复制、延期(恢复) |
| CLOSED | 已关闭 | 仅查看 |

### 3.4 快照机制

**目的**:基础数据(料号、价格、汇率、模板、组件)后续可能变,快照保证半年后看回报价依然能复现当时算法。

**提交时冻结的字段**:

| 表 | 快照字段 |
|---|---|
| `quotation_line_item` | `product_part_no_snapshot` / `product_name_snapshot` / `unit_price_snapshot` / `discount_rate_snapshot` / `formula_used_snapshot` / `data_source_snapshot` |
| `quotation_line_item` | `excel_view_snapshot`(V149 加,整行 Excel 视图列结果) |
| `template_components_snapshot` | 模板发布时的组件结构快照 |
| `quotation` | `customer_name_snapshot` / `customer_contact_snapshot` |

**快照写入触发**:

- 报价单 `DRAFT → SUBMITTED` 时(`SnapshotCollectorService.collect()`)
- 草稿态切换料号版本时也会重算 `excel_view_snapshot`(V2.8 PUT 端点)

### 3.5 报价单与核价单的关联

```
quotation_line_item.costing_summary_id  ──软关联──►  costing_summary
              (ON DELETE SET NULL)
```

- **可独立**:核价单可脱离报价单单独管理
- **可关联**:lineItem 关联 costing_summary 后,核价单 Tab 直接展示对应核价数据
- **可嵌入**:报价单页面内核价 Tab 即编辑该 summary
- 删核价单不影响报价单(置 NULL)

### 3.6 报价单审批流

#### 3.6.1 审批规则配置

`ApprovalRule` 表存储审批路由规则,由 `SYSTEM_ADMIN` / `SALES_MANAGER` 配置:

```
ApprovalRule {
  id, name, rule_type=[FIXED|DYNAMIC], priority,
  match_conditions JSONB, approver_id (FIXED 类型用),
  approver_resolver (DYNAMIC 类型用,如 "salesManager-of-region"),
  status, created_at
}
```

- `priority` 数字越小优先级越高
- FIXED 类型(固定审批人)在同 priority 数值下始终优先于 DYNAMIC
- 兜底规则:无匹配 → 路由到 `created_at` 最早的 ACTIVE 系统管理员

#### 3.6.2 审批路由(纯 Java 实现)

提交时由 `ApprovalRuleService.route(quotation)`:

1. 按 priority 升序 + FIXED 优先排序加载 ACTIVE 规则
2. 遍历匹配 `match_conditions`(金额阈值 / 客户等级 / 区域 / 部门等)
3. 第一条命中即返回 `approver_id`
4. 全部不匹配 → 走兜底 SYSTEM_ADMIN

#### 3.6.3 审批动作

审批人在"待我审批"列表点击进详情:

- **通过**:`SUBMITTED → APPROVED`,记录 `quotation_approval` 行
- **驳回**:`SUBMITTED → REJECTED`(必填驳回原因),回到销售可继续编辑
- 经理可"快捷审批"(从列表页直接通过/驳回,无需进详情)
- `SYSTEM_ADMIN` 可审批**任意** SUBMITTED 报价(兜底)

#### 3.6.4 撤回

- 销售代表对自己的 SUBMITTED 报价可"撤回"(`quotation_withdraw_request`)
- 撤回后状态 `SUBMITTED → DRAFT`,清空 `assigned_approver_id`
- 已 APPROVED 的不可撤回

#### 3.6.5 催办

每日 09:00 (cron `0 0 9 * * ?`) 扫描:

- `status=SUBMITTED AND created_at < NOW()-48h AND 未发过催办`
- 发送站内消息 + 邮件给 `assigned_approver_id`

### 3.7 模板绑定全局变量 + 报价单引用数据 Tab

#### 3.7.1 业务目标

报价单依赖若干**全系统共享的基础数据**（元素单价、材料单价、汇率等），这些数据已注册于 `global_variable_definition` 表（§5.6）。本功能打通"模板配置端"与"报价单详情端"的直通通道：

- **配置端**：模板管理员在编辑模板时，选择与该产品相关的全局变量并排序，记录绑定关系。
- **消费端**：销售/审批人在报价单详情页，通过「引用数据」Tab 实时或快照地查看绑定全局变量的完整数据行，无需切回配置中心。
- **审计端**：报价单提交后，引用数据以快照形式保存，确保事后可追溯当时使用的基础数据版本。

#### 3.7.2 全局变量绑定（模板编辑端）

##### 3.7.2.1 UI 入口

在模板配置页 (`/templates/:id`) 的编辑抽屉内，现有「组件区」和「基础信息」区之间，新增**「关联全局变量」**区块（Ant Design `Card`）。

**入口约束**：

- 仅 DRAFT 状态的模板显示可编辑的绑定区块；PUBLISHED / ARCHIVED 模板显示只读列表。
- `template_kind ∈ {QUOTATION, COSTING}` 时显示该区块；`template_kind = EXCEL` 时隐藏（Excel 模板不展示报价详情 Tab）。

##### 3.7.2.2 绑定交互

| 步骤 | 操作 | 说明 |
|---|---|---|
| 1 | 点击「+ 添加全局变量」 | 打开候选列表 |
| 2 | 候选列表 | 多选 Checkbox 列表，来自 `global_variable_definition` 表 `is_active = true` 的记录；已绑定的条目显示已绑定标记，不可重复添加 |
| 3 | 确认 | 选中条目追加到绑定列表尾部 |
| 4 | 排序 | 绑定列表支持拖拽排序（`display_order` 字段），决定「引用数据」Tab 中卡片的展示顺序 |
| 5 | 移除 | 行尾「删除」图标；DRAFT 状态下可删除，PUBLISHED 后只读 |

候选列表展示字段：`code`、`name`（中文名）、`var_type`（LOOKUP_TABLE / SCALAR）、`source_view`（物理视图/表名）、`unit`（单位）、`is_active`。

**历史绑定可见规则**：若某已绑定 GV 在 `global_variable_definition` 中被设为 `is_active = false`，该条目在模板编辑时**仍显示在绑定列表中**（带灰色「已停用」徽章），允许管理员手动决定是否移除；候选列表中过滤掉 `is_active = false` 条目，不允许新增绑定到已停用 GV。

##### 3.7.2.3 DB 数据模型

新增关联表：

```
template_global_variable_binding {
  id                       UUID         PK
  template_id              UUID         NOT NULL  FK → template.id
  global_variable_code     VARCHAR(64)  NOT NULL  FK → global_variable_definition.code
  display_order            INT          NOT NULL DEFAULT 0
  created_at               TIMESTAMPTZ  NOT NULL DEFAULT now()

  UNIQUE (template_id, global_variable_code)
}
```

> **字段类型说明**：`global_variable_definition` 表的主键是 `code VARCHAR(64)`（业务编码，非 UUID），见 V104 真实 schema。本表 FK 直接引用 `code`，方便 JSONB 快照内可读、便于 admin 跨环境对账。

**约束**：

- 删除模板时级联删除绑定行（`ON DELETE CASCADE`）。
- 删除 `global_variable_definition` 行时拒绝（`ON DELETE RESTRICT`），需先解除绑定。
- 单模板绑定上限：软上限 20 个（超出 UI 警告，不硬拒）。

##### 3.7.2.4 API 端点摘要

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/cpq/templates/{tid}/global-variable-bindings` | 返回该模板已绑定 GV 列表，按 `display_order` 升序 |
| `PUT` | `/api/cpq/templates/{tid}/global-variable-bindings` | 全量替换绑定列表（body: `{ bindings: [{globalVariableCode, displayOrder}] }`），DRAFT-only，非 DRAFT 返 403 |
| `GET` | `/api/cpq/global-variable-definitions?activeOnly=true` | 候选 GV 列表（复用现有 `GlobalVariableService.listAll()`，已自带 `is_active = true` 过滤） |

**createNewDraft 拷贝规则**：`TemplateService.createNewDraft()` 在拷贝模板时，将原模板的 `template_global_variable_binding` 行原样复制（`display_order` 保持不变），新 `template_id` 指向新草稿。

#### 3.7.3 引用数据 Tab（报价单详情端）

##### 3.7.3.1 Tab 位置

报价单详情页 (`/quotations/:id`) 的 Tab 条顺序调整为：

```
报价单信息 (info)  →  引用数据 (ref-data)  →  数据来源 (snapshot)  →  核价单  →  审批记录
```

**「引用数据」Tab 仅在报价单关联模板且该模板存在 ≥1 条 GV 绑定时显示**，无绑定时 Tab 自动隐藏。

##### 3.7.3.2 懒加载策略

- 用户**切换到「引用数据」Tab** 时触发数据加载（懒加载），不在页面初始化时预拉取。
- **DRAFT 状态**：每次切到该 Tab 时实时调用 API，从 `source_view` 抓取最新全量行（保证草稿阶段始终看到最新数据）。
- **非 DRAFT 状态**（SUBMITTED / APPROVED / REJECTED 等）：从 `quotation_submission_snapshot.bound_global_variables_snapshot` 读取提交时快照，不再实时抓取。

##### 3.7.3.3 展示格式

每个绑定 GV 渲染为一个独立**卡片（Card）**，卡片标题 = `${gv.name}（${gv.code}）`。

| GV 类型 | 渲染方式 |
|---|---|
| **SCALAR**（`var_type = 'SCALAR'`，单值） | Ant Design `Descriptions column={3} bordered size="small"`（v3.5.1 column 从 2 调整为 3），参考报价单信息 Tab 内「基本信息」卡片的同款样式；**value 拼接 unit**（如 `8500 CNY`）|
| **LOOKUP_TABLE**（`var_type = 'LOOKUP_TABLE'`，多行查找表） | **Ant Design `Descriptions column={3} bordered size="small"`（v3.5.1 修订）**：每行一个 `Descriptions.Item`，label = key 列值（如 `Ag` / `Cu` / `Sn`，灰底）, value = `value_column` 列值 + 空格 + `unit`（如 `400 CNY` / `100 CNY` / `255 CNY`，白底）。**列头名称**（如 `key` / `value_number`）**不显示**。容器 `max-height: 600px + overflow: auto` 保护大表（如 MAT_PRICE 上千行场景）。**列头/分页规则已废除**（v3.5 之前为 Table 渲染 + > 10 行分页，现统一为 form 形式与「基本信息」对齐）|

> **v3.5.1 微调依据**（用户反馈）：① `column={2}` 一行 2 个 K:V 过宽，调整为 `column={3}` 一行 3 个 K:V 更紧凑；② 单位（如 `CNY`）从 Card 右上角 `extra` 标识改为在每个 `value` 后空格拼接，方便每行直接对照看出"X 是多少单位"，无需返回卡片头核对单位。

> **分支判定依据**：以 `global_variable_definition.var_type` 字段为唯一判别标识（V104 实际 schema），不依赖 `source_kind`（PRD 早期草稿用语，已废）。`source_view` 是物理视图/表名（如 `v_costing_element_price`），由后端 `GlobalVariableDataLoader` 直接 `SELECT ... FROM source_view` 全量读取。

加载中：展示 `Skeleton` 占位（与现有卡片一致）。  
无数据：展示「暂无数据」占位文字。  
加载失败：展示 `Alert type="error"` 提示"数据加载失败，请刷新重试"。

##### 3.7.3.4 API 端点摘要

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/cpq/quotations/{qid}/ref-data` | 返回该报价单所有绑定 GV 的实时数据（DRAFT 专用），按 `display_order` 升序，每条含 `code / name / varType / unit / displayOrder / rows[]` |
| `GET` | `/api/cpq/quotations/{qid}/ref-data/snapshot` | 返回提交时快照（非 DRAFT 专用），数据来自 `quotation.bound_global_variables_snapshot` |

后端实现路径（隔离边界约束下）：

- 新增 `GlobalVariableDataLoader` 服务，按 GV 的 `var_type` / `source_view` / `value_column` / `key_columns` 直接执行原生 SQL `SELECT * FROM source_view` 全量行（视图名/列名走 V104 已校验过的标识符白名单 `isIdent()`，防注入），**不复用** `useDriverExpansions` / `enrichComponentData` / `ComponentDriverService.expand`。
- `SnapshotCollectorService.collect()` 末尾追加段：查询 `template_global_variable_binding`（按 `display_order` 升序），逐 GV 调用 `GlobalVariableDataLoader`，序列化为 JSONB 写入 `quotation.bound_global_variables_snapshot` 列。

#### 3.7.4 数据快照机制

##### 3.7.4.1 快照存储

扩展现有 `quotation` 表（`submission_snapshot JSONB` 已存在于 V54；本功能新增独立 JSONB 列以保持列粒度可索引、不污染既有快照结构），新增列：

```
ALTER TABLE quotation
  ADD COLUMN bound_global_variables_snapshot JSONB NOT NULL DEFAULT '[]'::jsonb;
```

> **表名说明**：PRD 早期草稿提及的 `quotation_submission_snapshot` 表实际不存在。当前 V54 已把"快照"作为 `quotation.submission_snapshot` JSONB 列实现；本功能采用相同模式，在 `quotation` 表上加一列独立 JSONB（避免改 V54 既有快照内容、便于回滚）。

JSONB 结构（`bound_global_variables_snapshot`）：

```json
[
  {
    "code": "ELEM_PRICE",
    "name": "元素核价价格",
    "varType": "LOOKUP_TABLE",
    "unit": "CNY/KG",
    "displayOrder": 0,
    "snapshotAt": "2026-05-21T10:30:00Z",
    "rows": [
      { "element_code": "Cu", "costing_price": 75000 },
      { "element_code": "Ag", "costing_price": 8500 }
    ]
  }
]
```

> **JSONB 数组元素以 `code` 作为业务主键**（与 V104 一致），不再使用 PRD 早期草稿中的 `gvId`。`code` 全局唯一且人类可读，便于审计回溯。

##### 3.7.4.2 快照触发时机

- **触发点**：报价单从 DRAFT → SUBMITTED（`SnapshotCollectorService.collect()` 末尾）。
- **快照内容**：逐 GV 调用 `GlobalVariableDataLoader.loadAll(code)` 拉取 `source_view` 全量行，写入 `quotation.bound_global_variables_snapshot` JSONB 列。
- **历史报价兼容**：提交前创建的报价单 `bound_global_variables_snapshot` 列默认为 `[]`（V212 迁移时所有存量行回填空数组），前端展示时若数组为空且状态非 DRAFT，提示「提交时未生成引用数据快照」。

##### 3.7.4.3 快照不可变

快照一旦写入（SUBMITTED 时刻），后续审批流程中不再更新；APPROVED / REJECTED 阶段只读。

#### 3.7.5 状态机与编辑约束

| 模板状态 | 绑定 GV 操作 | 说明 |
|---|---|---|
| DRAFT | 可增 / 删 / 排序 | 完全可编辑 |
| PUBLISHED | 只读 | 返回 403，前端工具栏禁用 |
| ARCHIVED | 只读 | 同 PUBLISHED |

| 报价单状态 | 「引用数据」Tab 数据来源 | 说明 |
|---|---|---|
| DRAFT | 实时抓取（切 Tab 懒加载） | 每次切 Tab 重新拉取 |
| SUBMITTED | 快照（提交时固化） | 审批中只读 |
| APPROVED | 快照 | 只读 |
| REJECTED | 快照 | 退回草稿修改后下次提交时重新生成快照 |

**注意**：报价单 REJECTED 后回到 DRAFT 可再次编辑，再次提交时 `SnapshotCollectorService` 会覆盖写入新快照（覆盖旧 snapshot 行）。

#### 3.7.6 验收标准摘要

**AC1：DRAFT 模板新增绑定 GV**

- Given：模板状态 = DRAFT，访问模板编辑抽屉
- When：在「关联全局变量」区块添加 GV（如 ELEM_PRICE、MAT_PRICE），保存
- Then：`GET /templates/{tid}/global-variable-bindings` 返回 2 条按 `display_order` 升序排列的记录；模板编辑抽屉「关联全局变量」列表显示 2 行

**AC2：PUBLISHED 模板禁止修改绑定**

- Given：模板状态 = PUBLISHED
- When：调用 `PUT /api/cpq/templates/{tid}/global-variable-bindings`
- Then：后端返回 HTTP 403；前端「关联全局变量」区块所有编辑操作（添加 / 删除 / 排序）置灰不可交互

**AC3：createNewDraft 绑定关系原样复制**

- Given：PUBLISHED 模板已绑定 3 个 GV（display_order 0/1/2）
- When：触发「派生新草稿」操作（`POST /templates/{tid}/create-new-draft`）
- Then：新草稿的 `template_global_variable_binding` 表中存在 3 条对应记录，`global_variable_code` 与原模板一致，`display_order` 保持 0/1/2

**AC4：报价单 DRAFT 打开「引用数据」Tab 实时数据**

- Given：报价单状态 = DRAFT，关联模板绑定了 ELEM_PRICE（source_view = `v_costing_element_price`，含 4 行）
- When：用户切换到「引用数据」Tab
- Then：Tab 内出现 ELEM_PRICE 卡片，Table 展示 4 行数据；行数 ≤ 10，不分页；网络请求命中 `GET /quotations/{qid}/ref-data`

**AC5：报价单 SUBMITTED 后显示快照**

- Given：报价单从 DRAFT 提交为 SUBMITTED，提交时 ELEM_PRICE 快照已写入
- When：用户（或审批人）查看「引用数据」Tab
- Then：数据来自 `quotation.bound_global_variables_snapshot`；网络请求命中 `GET /quotations/{qid}/ref-data/snapshot`；页面不再发起实时 `ref-data` 请求

**AC6（v3.5.1 修订）：SCALAR 与 LOOKUP_TABLE 统一用 Descriptions form 渲染（column=3 + value 拼接 unit）**

- Given：绑定了 EXCHANGE_RATE（SCALAR，单行单值）和 ELEM_PRICE（LOOKUP_TABLE，多行，含 Ag/Cu/Sn 等 key，unit=CNY）
- When：打开「引用数据」Tab
- Then：两个卡片均展示 Ant Design `Descriptions column={3} bordered size="small"`；ELEM_PRICE 卡片内**每行一个 `Descriptions.Item`**：label = `Ag` / `Cu` / `Sn`（灰底），value = `400 CNY` / `100 CNY` / `255 CNY`（白底，数字 + 空格 + unit）；**列头名称**（`key` / `value_number`）**不显示**；**每行 3 个 K:V 并排**（column=3），与「报价单信息→基本信息」卡片视觉对齐。

**AC7（v3.5 修订）：LOOKUP_TABLE 大表展示边界**

- Given：绑定某 GV，其 `source_view` 返回 300 行
- When：打开「引用数据」Tab 对应卡片
- Then：卡片内 Descriptions 在 `max-height: 600px` 容器内展示，超出高度时容器内滚动；**不再启用分页**（v3.5 前为 Table > 10 行分页规则，已废除）

**AC8：已停用 GV 历史可见，候选列表过滤**

- Given：模板已绑定 GV_X，随后 GV_X 在 `global_variable_definition` 中被设为 `is_active = false`
- When：管理员打开该 DRAFT 模板编辑抽屉
- Then：「关联全局变量」列表仍显示 GV_X（带「已停用」徽章）；点击「+ 添加全局变量」弹出的候选列表中 GV_X 不出现

---

### 3.8 报价单输出

**V1 实装**:

- web 端报价单详情页(`/quotations/:id`)— 完整商务条款 + 全部 lineItems 卡片
- 报价单列表导出 CSV(只导主信息,不含 lineItem 详情)

**V2 规划**(占位,本版本不实现):

- PDF 导出(Quarkus Qute 模板)
- Excel 导出(Apache POI,可自定义模板)
- 邮件直发客户(SMTP 通过 `application.properties` 配置,V1 无 UI)
- 延期管理(到期前 N 天提醒)
- 客户回执标记(ACCEPTED / REJECTED)

### 3.9 报价单管理(列表页 `/quotations`)

**列表呈现**:

- 多角色视图:销售看自己的、经理看团队的、管理员看全部
- 状态筛选 Tab:全部 / 草稿 / 审批中 / 已通过 / 已驳回 / 已过期
- "待我审批"专属 Tab(经理 / 管理员可见)
- 列:报价单号 / 客户 / 项目 / 创建人 / 提交时间 / 状态 / 总金额 / 当前审批人
- 默认按 `updated_at` 倒序

**工具栏动作**(对应 `SelectableTable` 规范):

- 新建报价单(无需勾选)
- 复制(勾选 1 行,生成新草稿)
- 删除(勾选 1+ 草稿,二次确认)
- 撤回(勾选 1+ 自己的 SUBMITTED)
- 批量通过 / 驳回(勾选 1+ 审批中,审批人可见)
- 导出 CSV(勾选 1+,主信息)

**复制规则**:

- 复制后新报价单状态 = DRAFT
- 折扣信息保留为参考(旧 `system_discount_rate` / 手动折扣率),进入步骤三时按当前策略重算
- 数据源字段值清空(下次进入步骤二时重新触发查询)
- 模板若为 ARCHIVED 状态,新草稿步骤二标记 "⚠ 模板已归档,请重新选择",允许保留数据但禁止提交

### 3.10 非功能需求

| 指标 | 要求 |
|---|---|
| 报价单草稿保存延迟 | < 500ms (P95) |
| 第二步公式重算延迟 | < 100ms (前端 JS,单 lineItem) |
| 第三步折扣计算延迟 | < 200ms (PricingRuleService) |
| 报价单详情打开延迟 | < 1s (含全部 lineItems) |
| 单报价单 lineItem 上限 | 软上限 20 (UI 提示),无硬限制 |
| 并发同时编辑同一草稿 | 不支持,最后写入胜出 |
| 草稿自动保存频率 | 10 秒 / 次 |
| 报价单审批后修改 | 禁止,需复制为新草稿 |

---

## 4. 业务主线二:核价单 (Costing Summary)

### 4.1 定位

- **业务目的**:算出某个料号的真实成本(材料 + 加工 + 模具 + 设计 + 商务加价),给报价提供"底价"
- **角色主线**:`PRICING_MANAGER` 独立操作,或在报价单第二步核价单 Tab 内作为子流程
- **结果对内**:成本数据 + 7 项 metric 计算结果,可追溯到具体的元素价、材料价、汇率版本
- **强调**:精度、版本、可追溯("这个 0.123 是怎么算出来的")

**与报价单的关系**(重申 3.5 节):

```
核价单                                  报价单
(成本视角)                              (商务视角)
   │                                     │
   │   计算/锁定                          │   关联引用
   ▼                                     ▼
costing_summary  ◄── costing_summary_id ── quotation_line_item
   ↑                                     │
   │                                     │
   └─ 引用全局基础数据                    └─ 加上折扣/加价/税
      (元素/材料/汇率版本)                   → total_amount
```

| 维度 | 报价单 | 核价单 |
|---|---|---|
| 受众 | 客户(对外) | 内部(定价/销售/财务) |
| 关注点 | 售价、商务条款 | 成本、版本、可追溯 |
| 维护方 | SALES_REP | PRICING_MANAGER |
| 状态机 | DRAFT/SUBMITTED/APPROVED/EXPIRED... | DRAFT/COMPUTED/PUBLISHED/ARCHIVED |
| 是否依赖另一方 | 否(可独立) | 否(可独立) |

### 4.2 三层数据架构

```
┌─────────────────────────────────────────────────────────────┐
│  L0  全局基础数据 (3 类,每类独立版本)                       │
│  costing_price_version (kind=ELEMENT/MATERIAL/EXCHANGE)     │
│   ├─ costing_element_price       元素单价(铜/银/锡 等)       │
│   ├─ costing_material_price      材料单价(规格件)             │
│   └─ costing_exchange_rate       汇率(CNY/USD/EUR/JPY)        │
└─────────────────────────────────────────────────────────────┘
                            ▲
                            │ versionId 引用
                            │
┌─────────────────────────────────────────────────────────────┐
│  L1  料号级数据 (8 张表,按 hf_part_no 绑定)                 │
│  costing_part_*                                              │
│   ├─ process_cost       (8 种 cost_type discriminator)       │
│   ├─ tooling_cost       (模具/工装)                          │
│   ├─ material_bom       (原材料 BOM)                         │
│   ├─ element_bom        (元素 BOM,composition_pct)          │
│   ├─ quality_check      (INCOMING / SEMI_FINISHED stage)     │
│   ├─ plating            (电镀方案)                            │
│   ├─ design_cost        (设计成本)                            │
│   └─ weight             (1 row per part,unique)              │
└─────────────────────────────────────────────────────────────┘
                            ▲
                            │ hf_part_no 关联
                            │
┌─────────────────────────────────────────────────────────────┐
│  L2  核价单实例 (3 张表,1 个料号 1 份)                     │
│  costing_summary                                            │
│   ├─ costing_summary_override  (用户差量 - what-if)         │
│   └─ costing_summary_result    (7 metric 计算结果)          │
└─────────────────────────────────────────────────────────────┘
```

每层语义边界:**改 L0 数据 → L1~L2 实时反映;改 L1 数据 → 已发布核价单不动(快照机制);override 仅本核价单生效,不写回基础数据。**

#### 4.2.1 L0 全局基础数据

**版本表 `costing_price_version`**:

```
costing_price_version {
  id, kind ∈ {ELEMENT, MATERIAL, EXCHANGE},
  version_no, name, status ∈ {DRAFT, PUBLISHED, ARCHIVED},
  is_default,  -- 同 kind 内仅 1 份 (is_default=true AND status='PUBLISHED') 唯一
  effective_date, published_at, created_by, ...
}
```

**约束**:

- 每个 `kind` 同时刻最多 1 份 `is_default=true + status='PUBLISHED'` 版本(数据库 partial unique index)
- 历史版本以 `ARCHIVED` 保留,不可改但可被引用
- 视图 `v_costing_element_price` / `v_costing_material_price` / `v_costing_exchange_rate` (V78) 永远只查默认版本数据,简化业务路径

**三张价格表**:

| 表 | 关键列 | 用途 |
|---|---|---|
| `costing_element_price` | version_id / element_name / costing_price / unit | 元素单价(如银 4500 元/kg) |
| `costing_material_price` | version_id / material_no / unit_price / unit | 规格材料单价(如 4N5-AgCu 棒料) |
| `costing_exchange_rate` | version_id / from_currency / to_currency / costing_rate / market_rate | 双币种汇率(核价用 costing_rate,商务用 market_rate) |

**维护入口**:`PRICING_MANAGER` 在「配置中心 → 核价基础数据」菜单管理三类版本及其数据。

#### 4.2.2 L1 料号级数据(8 张表)

| 表 | 一行 = | 关键列 |
|---|---|---|
| `costing_part_material_bom` | 料号下的一条 BOM 项 | hf_part_no / material_no / quantity / loss_rate |
| `costing_part_element_bom` | BOM 项对应的元素含量 | bom_id / element_name / composition_pct |
| `costing_part_process_cost` | 一道工序的成本 | hf_part_no / cost_type ∈ {LATHE, MILL, GRIND, ENERGY_DEDICATED, ENERGY_SHARED, LABOR, OTHER_OUTSOURCE, ...} / unit_price / process_count |
| `costing_part_tooling_cost` | 模具/工装 | hf_part_no / tooling_unit_cost / process_count / cycle_count |
| `costing_part_quality_check` | 品质检验项 | hf_part_no / stage ∈ {INCOMING, SEMI_FINISHED} / fee |
| `costing_part_plating` | 电镀方案行 | hf_part_no / scheme_no / element_name / area_cm2 / thickness |
| `costing_part_plating_fee` | 电镀费用汇总 | hf_part_no / scheme_no / unit_price |
| `costing_part_design_cost` | 设计成本 | hf_part_no / design_proc_fee / design_material_fee |
| `costing_part_weight` | 料号重量(1 row per part,UNIQUE) | hf_part_no / weight_g_per_pcs |

**维护入口**:`PRICING_MANAGER` 在「配置中心 → 料号级核价数据」一页式 Tab 管理(每张表一个 Tab,按 `hf_part_no` 筛选)。

**`cost_type` 8 种 discriminator**(在 `costing_part_process_cost`):

```
LATHE              车
MILL               铣
GRIND              磨
ENERGY_DEDICATED   专用能耗
ENERGY_SHARED      共用能耗
LABOR              人工
OTHER_OUTSOURCE    其他外加工
OTHER_INHOUSE      其他内加工
```

#### 4.2.3 L2 核价单实例(3 张表 + 1 视图)

```
costing_summary {
  id, hf_part_no, name, status ∈ {DRAFT, COMPUTED, PUBLISHED, ARCHIVED},
  element_version_id, material_version_id, exchange_version_id,  -- 3 个 L0 版本引用
  quote_currency,                                                 -- 报价币种 (CNY/USD/EUR/JPY)
  computed_at, published_at, created_by, ...
}

costing_summary_override {
  id, summary_id,
  target_kind,   -- 'GLOBAL_ELEMENT' / 'GLOBAL_MATERIAL' / 'PART_PROCESS' / ...
  target_key,    -- 'element_name=Ag' / 'cost_type=LATHE' / ...
  field_name,    -- 'costing_price' / 'unit_price' / ...
  override_value
}

costing_summary_result {
  id, summary_id, metric_code, value, calculated_at
}

v_costing_summary_full  -- PIVOT 视图,把 N 行 metric 转成 1 行 9 列(material_cost / processing_cost / ... / unit_per_pcs)
```

**PIVOT 视图 `v_costing_summary_full`**(V80 引入):

- 把 `costing_summary_result` 的 N 行 metric × 单 summary PIVOT 成 1 行 9 列宽表
- 列:`material_cost / processing_cost / tooling_fee / design_cost / management_cost / financial_cost / profit / tax / unit_per_pcs / unit_total_quote` 等
- Excel 模板可用单条 BNF 路径 `v_costing_summary_full.material_cost` 取每个成本项
- 未实现的 6 项商务加价以 NULL 占位

### 4.3 核价单状态机

```
DRAFT  ──compute()──►  COMPUTED  ──publish()──►  PUBLISHED  ──archive──►  ARCHIVED
   ▲                       │                         │
   │ 用户改 override        │                         │
   └───────────────────────┘                         │
                                                     │
                                          (Excel 视图按 PUBLISHED 取数)
```

| 状态 | 含义 | 可操作 |
|---|---|---|
| DRAFT | 草稿,数据可改 / 待计算 | 编辑、计算、删除 |
| COMPUTED | 已计算,可查看结果 | 编辑(回 DRAFT)、改 override(回 DRAFT)、发布 |
| PUBLISHED | 已发布,被报价单引用 | 查看、归档 |
| ARCHIVED | 已归档 | 查看 |

**关键约束**:

- override 写入后,状态自动 `COMPUTED → DRAFT`,强制重新 compute(避免"override 改了但结果没刷新")
- 切换任一 L0 版本(element / material / exchange version_id),状态自动 `COMPUTED → DRAFT`
- `PUBLISHED` 状态的 summary 才会被 `quotation_line_item.costing_summary_id` 关联引用(可关联 DRAFT/COMPUTED 但 UI 会提示)
- 删除 PUBLISHED summary:若被报价单引用,要求先解绑或转 ARCHIVED;否则置 `costing_summary_id` 为 NULL

### 4.4 7 项 metric 计算 (`CostingSummaryService.compute()`)

V1 已实现的 7 项 metric:

| metric_code | 中文 | 计算依赖 |
|---|---|---|
| `MATERIAL_COST` | 材料成本 | `Σ(material_bom.quantity × element_bom.composition_pct × element_price × (1+loss_rate))` |
| `PROCESS_FEE` | 加工费(工序合计) | `Σ unit_price`(8 种 cost_type 全部) |
| `TOOLING_FEE` | 模具工装费 | `Σ tooling_unit_cost / process_count / cycle_count` |
| `DESIGN_COST` | 设计成本 | `Σ (design_proc_fee + design_material_fee)` |
| `UNIT_TOTAL_COST` | 单位总成本 | `MATERIAL_COST + PROCESS_FEE + TOOLING_FEE + DESIGN_COST` |
| `UNIT_TOTAL_QUOTE` | 单位总成本(报价币种) | `UNIT_TOTAL_COST × 汇率(CNY → quote_currency)` |
| `UNIT_PER_PCS` | 单件成本 | `UNIT_TOTAL_QUOTE × weight_g_per_pcs / 1000` |

**V2 待实现的 6 项商务加价**(在 `v_costing_summary_full` 视图以 NULL 占位):

- `MANAGEMENT_COST` 管理费
- `FINANCIAL_COST` 财务费
- `PROFIT` 利润
- `TAX` 税费
- `PLATING_OUTSOURCE` 电镀外加工
- `OTHER_OUTSOURCE_FEE` 其他外加工

> 当前 compute() 为后端 Java 硬编码;**V2 规划**:升级为基于公式表达式的可配置 metric,业务管理员通过 UI 加新 metric 无需改代码。

**触发计算**:

- 手动:核价单详情页"计算"按钮
- 自动:override 写入后 / 切换 L0 版本后 / 关联的 L1 数据变更后(由 ChangeFeed 通知)

**计算过程**:

1. 加载 `costing_summary_override` 进 Map(key=`{target_kind}:{target_key}:{field_name}`)
2. 加载 L0 三类基础数据(按 summary 的 3 个 version_id)
3. 加载 L1 八张表(按 hf_part_no)
4. 求值时优先 override Map,其次 L0/L1 原始数据
5. 写入 `costing_summary_result`(7 行,每行一项 metric)
6. 状态 → `COMPUTED`

### 4.5 用户差量 (override) — what-if 试算

#### 4.5.1 设计动机

`PRICING_MANAGER` 在做定价决策时常需要"如果某元素涨价 10% 会怎样"的试算。直接改 L0 基础数据会污染团队共享数据,引发其他核价单串扰。

**override 机制**:

- 用户在核价单详情页的"差量"页填写覆盖值
- 字段层次:`target_kind` × `target_key` × `field_name`
- 覆盖值仅本核价单 `compute()` 时生效,**不写回 L0/L1**
- 删 override 行 → 该字段回归原始数据

#### 4.5.2 override 字段层次

| target_kind | 含义 | target_key 示例 |
|---|---|---|
| `GLOBAL_ELEMENT` | 覆盖某元素单价 | `element_name=Ag` |
| `GLOBAL_MATERIAL` | 覆盖某材料单价 | `material_no=4N5-AgCu` |
| `GLOBAL_EXCHANGE` | 覆盖某币种汇率 | `from=CNY,to=USD` |
| `PART_PROCESS` | 覆盖该料号下某工序单价 | `cost_type=LATHE` |
| `PART_TOOLING` | 覆盖模具费某项 | `tooling_id=...` |
| `PART_DESIGN` | 覆盖设计成本字段 | `field=proc_fee` |
| `PART_WEIGHT` | 覆盖料号重量 | (无 key,1 row per part) |

#### 4.5.3 决策原则 (Q3=B 决策)

历史决策:override 仅本核价单生效,**不**提供"应用到全局"按钮。理由:

- 试算 / what-if 是高频场景,污染全局数据后清理成本高
- 全局数据维护有独立入口(L0 配置),走正式审批流(V2 规划)
- override 在 PUBLISHED 后随 summary 一起冻结,后续 L0 数据变更不影响

### 4.6 与报价单的关联

#### 4.6.1 关联方式

- `quotation_line_item.costing_summary_id` 软关联(ON DELETE SET NULL)
- 一对一(一个 lineItem 关联一个 summary;同一个 summary 可被多 lineItem 引用)

#### 4.6.2 三种使用场景

| 场景 | 操作 |
|---|---|
| **独立核价** | PRICING_MANAGER 在「核价单」菜单建 summary,不绑定任何报价单 |
| **报价时新建** | 报价单第二步切到核价单 Tab,点击"建立核价"自动创建一份 summary 并绑定到当前 lineItem |
| **报价时关联已有** | 报价单第二步选已存在的 PUBLISHED summary 直接关联 |

#### 4.6.3 报价单内核价 Tab 行为

- 报价单 Tab 与核价单 Tab 共用同一份 lineItems 内存模型(见 3.2.2)
- 核价单 Tab 渲染时按 `costing_card_template_id` 的组件白名单过滤
- 在核价单 Tab 编辑组件数据 → 同步写入 `costing_summary_override`(`target_kind=PART_*`)
- 切换 lineItem 的料号版本 → 重新 compute 关联的 summary

#### 4.6.4 删除保护

- 删除 PUBLISHED summary:若被任意 lineItem 引用 → 先提示"被 N 份报价单引用",用户确认后置 `costing_summary_id` 为 NULL,summary 转 ARCHIVED
- 删除 lineItem:summary 不受影响(可能被其他 lineItem 引用)

### 4.7 核价单列表与详情页

#### 4.7.1 核价单列表 (`/costing-summary`)

- 列:summary 编号 / HF 料号 / 客户料号 / 状态 / 7 项 metric 摘要(`v_costing_summary_full` 取值)/ 引用版本号 / 创建人 / 更新时间
- 筛选:状态 / 料号 / 客户(通过 lineItem 关联反查)/ 版本
- 工具栏:新建 / 复制 / 计算(批量)/ 发布(批量)/ 归档 / 删除
- 行内主入口:点击进详情

#### 4.7.2 核价单详情 (`/costing-summary/:id`)

- **基础信息卡**:summary 编号 / 料号信息 / 状态 / 引用的 3 个 L0 版本 / quote_currency
- **L1 数据 Tab 区**(8 个 Tab,每个对应一张料号级表的视图):
  - 投料 BOM(material_bom)
  - 元素 BOM(element_bom)
  - 加工费(process_cost,按 cost_type 分组)
  - 模具(tooling_cost)
  - 品质(quality_check,按 stage 分组)
  - 电镀方案(plating)
  - 设计(design_cost)
  - 重量(weight)
  - 这些 Tab 数据来自 L1,只读展示;编辑入口在「料号级核价数据」菜单
- **override Tab**:用户差量列表 + 新建 override 入口
- **计算结果区**:7 项 metric 数值(已实现的非 NULL,V2 商务加价显示 "—")
- **操作按钮**:计算 / 发布 / 归档(根据状态)

### 4.8 与基础数据导入的关系

L1 八张表的数据可通过两种方式录入:

1. **手工录入**:在「配置中心 → 料号级核价数据」每张表的 Tab 内 CRUD
2. **基础数据导入 V6**:`SYSTEM_ADMIN` 用 Excel 模板批量导入(见第 7 章)
   - 导入时自动按 `hf_part_no` 关联,无该料号则报错
   - 走 `import_session` staging,导入前可预览 / diff / 决策

### 4.9 非功能需求

| 指标 | 要求 |
|---|---|
| compute() 单 summary 耗时 | < 500ms (V1) |
| 列表页打开延迟 | < 1s (含 v_costing_summary_full 取值) |
| 详情页打开延迟 | < 1.5s (含 8 张 L1 表查询) |
| 单料号 BOM 行数上限 | 软上限 100 (UI 提示) |
| 单料号 process_cost 行数上限 | 软上限 50 |
| L0 版本切换 | 自动触发 compute,无需手动重算 |
| override 立即生效 | 改 override 后下次 compute 直接命中 |
| 视图列发现缓存 | 进程内 cache,DDL 后必须重启 Quarkus(V112 后空集不缓存,但已残留进程仍需重启) |

### 4.10 已知限制(V1 → V2)

| # | 限制 | 影响 | 计划版本 |
|---|---|---|---|
| 1 | compute() 暂未实现 6 项商务加价 | `v_costing_summary_full` 中这 6 列恒 NULL,Excel 视图显示 "—" | V2 |
| 2 | LinkedExcelView 每个 lineItem 一行 | 同料号多 summary 不能在同一视图展开成多行 | V2 |
| 3 | 同一 `linked_template_id` 只能 1 份 PUBLISHED 默认 Excel 模板 | 多默认场景需切换 / 归档 | 永久(设计取舍) |
| 4 | 核价单批量比较跨 summary 未实现 | 同料号在不同基础数据版本的成本差异需手工对照 | V2 |
| 5 | 公式可配置化(compute 7 项 metric 当前是 service 硬编码) | 业务调整需改代码 + 重启 | V2 |
| 6 | override 单 summary 生效,无"提升到全局"按钮 | 全局调整需手工改 L0 数据 | 永久(设计取舍) |

---

## 5. 配置中心

配置中心面向 `SYSTEM_ADMIN` 与 `PRICING_MANAGER`,统一管理报价/核价系统的所有"主数据 + 配置数据"。子菜单按职责分组,前端入口在侧边栏「配置中心」一级菜单下。

### 5.1 客户管理

#### 5.1.1 功能

- **客户列表**(`/customers`):分页 + 模糊搜索 + 等级筛选(普通/VIP/钻石)
- **客户详情**(Drawer):
  - 基本信息:客户编号、名称、行业、地址、税号、信用额度
  - 联系人(`customer_contact`):多联系人,1 个 `is_primary=true` 主联系人
  - 商务字段:付款方式、默认币种、累计成交金额(只读,由系统自动统计 APPROVED 报价单 total_amount 累加)
  - 模板预选:默认报价模板 / 默认核价模板
  - 业务隔离:所属区域 / 部门(决定哪些 SALES_REP 可见)
- **新建 / 编辑**(Drawer):表单两栏布局
- **删除保护**:存在 APPROVED 报价单时禁止删除,提示"有 N 份历史报价,如确需归档请联系管理员"

#### 5.1.2 数据模型

```
Customer {
  id, code (业务编号,唯一), name, level ∈ {NORMAL, VIP, DIAMOND},
  industry, tax_no, address, credit_limit, default_currency,
  default_template_id, default_costing_template_id,
  region_id, department_id, status ∈ {ACTIVE, INACTIVE},
  accumulated_amount,  -- 由定时任务每日 02:00 同步
  created_at, updated_at
  
}

CustomerContact {
  id, customer_id, name, phone, email, position,
  is_primary ∈ {true, false},  -- 同 customer_id 内仅 1 个 true
  created_at
}
```

#### 5.1.3 非功能

- 客户列表 < 1000 条时全量加载;> 1000 走分页(默认 20 条/页)
- 模糊搜索匹配 `code` / `name` / 拼音首字母,延迟 < 200ms

---

### 5.2 产品管理

#### 5.2.1 概念分层

```
ProductCategory  ───►  Product (HF 内部料号)  ───►  PartVersion (料号版本)
                              ▲
                              │
                  CustomerMaterialMapping (客户料号 ↔ HF 料号 映射)
```

- **`Product` (HF 内部料号)**:HF 公司维护的标准料号(`hf_part_no`),代表一个具体产品
- **`PartVersion`**:同一 HF 料号的多个规格版本(如同型号铜端子的厚度变更),独立报价
- **`CustomerMaterialMapping`**:客户料号 → HF 料号的多对一映射,Excel 导入时按客户料号自动匹配

#### 5.2.2 产品列表 (`/products`)

- 列:HF 料号、品类、产品名称、规格、单位、状态、最新版本号、创建时间
- 筛选:品类、状态、料号关键字
- 工具栏:新建、Excel 批量导入(走 V6 staging 流程,见第 7 章)、删除、归档
- 行内主入口:点击进产品详情 Drawer
- **删除保护**:存在已被报价单引用的料号禁止删除

#### 5.2.3 产品详情 (Drawer)

- **基础信息**:HF 料号、产品名称、规格、单位、品类、状态、备注
- **预留外部字段**:`external_id` / `last_synced_at`(V1 默认 NULL,V2 外部系统对接预留,不影响 V1 流程)
- **料号版本 Tab**:见 5.2.5
- **客户料号映射 Tab**:见 5.2.6

#### 5.2.4 产品分类 (`/product-categories`)

- 单表 `product_category`:`id / code / name / parent_id / sort_order / status`
- 支持父子层级(2 层为主,UI 树形)
- 作用:客户管理"产品分类筛选"、报价单第一步筛选可选料号

#### 5.2.5 料号版本管理(`/part-versions` + 产品详情 Tab)

**业务场景**:客户对同一料号要求"上次的厚度版本"vs"新方案的厚度版本"两份报价并存,系统需保留历史规格快照供独立报价。

**数据模型**:

```
PartVersion {
  id, hf_part_no, version_no (1, 2, 3, ...),
  is_current ∈ {true, false},  -- 同 hf_part_no 内仅 1 个 true
  spec_fingerprint,             -- 规格指纹(SHA256 of normalized spec JSON)
  created_at, created_by, change_reason
}
```

**关键能力**(由 `PartVersionService` 提供):

- **指纹比对**:新版规格与最新版指纹一致 → 拒绝建版(DiffDetector 防误判 BUMP)
- **版本谓词构建**:报价单引用某料号时可指定 `part_version_locked`,后续 BNF 路径求值自动按版本注入
- **草稿态版本切换**(V2.8):报价单草稿态可切换版本,后端 PUT 端点同步重算 `excel_view_snapshot`

**前端入口**:

- 独立菜单 `/part-versions`:全料号版本管理(按 hf_part_no 分组)
- 产品详情 → "料号版本"Tab:单料号版本管理
- 报价单 lineItem → `PartVersionDrawer`:选择版本

#### 5.2.6 客户料号映射(`CustomerMaterialMapping`)

```
CustomerMaterialMapping {
  id, customer_id, customer_part_no, hf_part_no,
  customer_product_name, customer_drawing_no, ...
  status, created_at
}
```

- 多对一:同一 HF 料号可对应不同客户的不同客户料号
- 维护入口:客户详情 → "客户料号"Tab,或 Excel 批量导入(`MaterialMappingService`)
- 报价单第二步从客户料号库勾选时显示客户料号 + HF 料号双列

#### 5.2.7 非功能

- 单料号料号版本数:软上限 20(UI 提示)
- Excel 批量导入:单次上限 5000 条(走 V6 staging,可预览决策)
- 删除保护:HF 料号被任意报价单 lineItem 引用 → 禁止物理删除,只可改 `status='INACTIVE'`

---

### 5.3 客户定价策略

#### 5.3.1 策略管理页面 (`/pricing`)

- 左侧:客户侧边栏(按等级筛选 + 搜索)
- 右侧:选中客户的策略列表 + 规则编辑

#### 5.3.2 策略配置

```
PricingStrategy {
  id, customer_id, name,
  base_discount,           -- 基础折扣率(0-100,语义"应付比例")
  min_order_amount,        -- 触发门槛
  priority,                -- 1 最高,默认 1
  effective_date, expiration_date (NULL=长期有效),
  status ∈ {ACTIVE, EXPIRED, DISABLED},
  created_at, updated_at
}

PricingRule {
  id, strategy_id, rule_type='BULK_DISCOUNT',
  threshold_amount,        -- 触发阈值
  discount_rate,           -- 应付比例,0-100
  sort_order               -- 仅控制 UI 顺序,不影响匹配逻辑
}
```

#### 5.3.3 匹配逻辑

(同 3.2.3,在此不重复;实现由 `PricingRuleService` 纯 Java 完成)

#### 5.3.4 工具栏动作

- 新建策略 / 复制策略 / 删除策略 / 启用-停用 / 导出 Excel / 导入 Excel

#### 5.3.5 非功能

- 策略过期由定时任务每日 01:00 标记
- Excel 导入单次上限 1000 条规则
- 计算延迟 < 200ms (P95)
- 多策略并发匹配结果与前端展示一致,误差为 0

---

### 5.4 组件管理(`/components`)

#### 5.4.1 组件定位

组件 (`component`) 是模板的可复用单元,代表"一组业务字段 + 字段间公式"(如"投料金额"组件含 BOM 列 + 单价 + 损耗率 + 行小计公式)。

#### 5.4.2 组件分类

| 模式 | 典型组件 | 特点 |
|---|---|---|
| **A 多行展示型** | COMP-V4-RAW-BOM / PLATING-SCHEME / ELEMENT-BOM | 有 `data_driver_path`,按 driver 行展开 N 行 |
| **B 单行汇总型** | COMP-V4-PLATING-COST / EXCHANGE-RATE | `data_driver_path=NULL` 或单行视图,一行显示 |
| **C 跨组件 SUBTOTAL 型** | COMP-V4-TOTAL-CNY | `component_type='SUBTOTAL'`,聚合其他组件的小计字段 |

详细决策树见 `docs/配置方法论.md` 第 3 节。

#### 5.4.3 组件数据模型

```
Component {
  id, code, name,
  component_type ∈ {BUSINESS, SUBTOTAL},
  category,
  data_driver_path,          -- 主表/视图,留空则单行
  fields JSONB,              -- 字段定义数组(见下)
  formulas JSONB,            -- 公式定义(可选,字段级公式)
  status ∈ {DRAFT, PUBLISHED, ARCHIVED},
  version,
  created_at, updated_at
}
```

**`fields` JSON 结构**:

```
[
  {
    name: '材料编号',
    field_key: 'material_no',
    type ∈ {INPUT, BASIC_DATA, FORMULA, DATA_SOURCE},
    path: 'costing_part_material_bom.material_no',  -- BASIC_DATA 用
    formula: [...token 数组],                         -- FORMULA 用
    datasource_id: '...',                            -- DATA_SOURCE 用
    is_subtotal ∈ {true, false},                     -- 单组件最多 1 个 true
    is_required, default_value, ...
  }
]
```

**字段类型**:

- `INPUT`:用户输入(数字 / 文本 / 日期)
- `BASIC_DATA`:从 BNF 路径取数(单值 / Map / List)
- `FORMULA`:基于同行其他字段的公式表达式(token 数组)
- `DATA_SOURCE`:调用 `datasource` 模块的 SQL/API 查询

#### 5.4.4 组件管理 UI

- **树形目录**(`component_directory`):按品类分文件夹组织组件
- **字段配置表**(`FieldConfigTable`):新增/编辑字段
- **公式编辑器**(`FormulaBuilder`):
  - 拖拽 token 拼公式
  - token 类型:`field / operator / number / bracket / component_subtotal / product_attribute / quotation_field / path / global_variable`(完整清单见附录 10.3)
  - 公式不支持引用其他 FORMULA 字段(前端限制,需手工内联展开)

#### 5.4.5 组件生命周期

- DRAFT → PUBLISHED:发布后字段定义冻结,模板可引用
- PUBLISHED 不可改;改需"派生新草稿"(version 自增)
- ARCHIVED:可读不可被新模板引用
- 模板引用关系存储于 `template_component` 表

#### 5.4.6 组件目录 导入 / 导出（v1.0 设计,2026-05-31 立项）

**目标**:在组件管理目录树上,把某目录**直属**的全部组件及其完整配置导出为 JSON bundle;用户可在**任意目录**导入,组件**平铺**落到目标目录。**硬约束:不与其他业务冲突、不影响任何现有业务功能**。

**导出/导入的数据边界(组件自包含配置)**:
- `component`:code / name / component_type / column_count / status / `data_driver_path` / `fields`(JSONB) / `formulas`(JSONB)
- `component_sql_view`:sql_view_name / sql_template / declared_columns / required_variables / scope / description(**组件内唯一**,随组件走)
- **不进 bundle(刻意排除,杜绝耦合)**:`template_component` 绑定、模板 `components_snapshot`、报价单数据 → 导入的是"未绑定的干净组件",由用户后续在模板管理自行引用。
- **本期不递归子目录**:只导当前目录直属组件;导入全部平铺到目标目录(bundle 不含目录子树)。

**🔒 隔离 / 不冲突保证(核心,逐条对应硬约束)**:
1. 导出纯只读(全 SELECT),零副作用。
2. 导入**只 INSERT 新行 + 全新 UUID**,绝不 UPDATE/DELETE 任何现有 `component / component_sql_view / template / 快照`。
3. `component.code` **全局唯一**:导入**永不覆盖**同 code 现有组件,靠冲突策略规避。
4. 导入组件**不绑定任何模板**(`template_component` 按 component_id 关联,新 id 自然未被引用)→ `refreshSnapshotsByComponent` 命中 0 模板 = no-op → **现有模板/报价单完全不受影响**。
5. `component_sql_view` 唯一键 = **(component_id, sql_view_name)**,sql_view_name **组件内唯一非全局** → `$cz_view` 等按组件上下文解析,导入无全局撞名、字段路径 `$view.col` **无需改写**。
6. 整个 import 单 `@Transactional`,全有或全无。
7. 依赖前置校验(见下),不静默产生悬空引用。
8. **无 Flyway / 无 schema 变更**,复用 `ComponentService.create` / `ComponentDirectoryService` 既有校验。

**导出**:`GET /api/cpq/components/directories/{dirId}/export` → 下载 bundle JSON(attachment)。扫描各字段的 `datasource_binding`(数据源 code)与 `global_variable_code` 汇总进 bundle.`dependencies`,供导入端校验。

**Bundle 格式(带版本;tempId 仅 bundle 内引用,导入重映射为新 UUID;含 checksum 防损坏)**:
```
{ "bundleVersion":"1.0", "exportedAt":"...", "source":{"directoryId","directoryName"},
  "components":[ { "code","name","componentType","columnCount","status","dataDriverPath",
                   "fields":[...], "formulas":[...],
                   "sqlViews":[{"sqlViewName","sqlTemplate","declaredColumns","requiredVariables","scope","description"}] } ],
  "dependencies":{ "datasources":[{"code"}], "globalVariables":[{"code"}] }, "checksum":"sha256:..." }
```

**导入(两步:预览 → 提交)**:
- 预览(dry-run):`POST /api/cpq/components/directories/{targetDirId}/import?dryRun=true` → 校验格式/checksum + 依赖校验 + 冲突计划,**不写库**,返回计划。
- 提交:`POST .../import`(带 conflictPolicy)→ 单事务执行,返回结果(新建 id / 重命名映射 / 跳过项 / 依赖告警)。
- **code 冲突策略(默认=重命名)**:重命名(冲突 code 加后缀 `__impN`,全量不丢,**推荐默认**)/ 跳过(幂等再导入)/ 中止(任一冲突即回滚)。**任何策略都不覆盖现有组件**。
- **依赖(数据源/全局变量)校验**:预览阶段比对目标环境 `datasource` / `global_variable_definition`;缺失则**红色列出并默认阻止提交**,提供"仍然导入(相关字段运行时取数会失败)"显式确认。

**UI(遵循规范)**:目录树节点增加「导出目录」(触发下载)、「导入到此目录」;导入走 **Drawer 向导**(上传 → 依赖/冲突预览 → 选策略 → 确认 → 结果报告),不用 Modal。

**RBAC**:写操作限 SALES_MANAGER / SYSTEM_ADMIN。

**分期**:P1 导出(只读)→ P2 导入预览+依赖校验 → P3 导入提交+冲突策略+结果报告。

**边界/反模式**:① AP-53 sql_template 引用 V6 表/`$<view>`,跨环境时 bundle 记录引用表名作环境假设提示;② 重命名策略再导入会产生 `__imp1/__imp2`(要幂等用"跳过");③ 不导出模板/快照/报价数据,从根杜绝耦合。

---

### 5.5 模板体系

#### 5.5.1 三类模板共表设计

```
template (kind=QUOTATION/COSTING)    ◄── 报价/核价模板:组件 + 公式 + 产品属性
   │
   │ linked_template_id  反查
   ▼
costing_template (kind=EXCEL)         ◄── Excel 模板:列结构(columns JSON)
                                          V150 起逻辑合并入 template 表
```

| 模板类型 | template_kind | 用途 | 报价单引用列 |
|---|---|---|---|
| **报价模板** | QUOTATION | 报价单产品卡片视图 + Excel 视图源 | `quotation.customer_template_id` |
| **核价模板** | COSTING | 核价单产品卡片视图 + Excel 视图源 | `quotation.costing_card_template_id` |
| **Excel 模板** | EXCEL | Excel 视图列定义 | 按 `linked_template_id` 反查 |

V150 后,三类模板物理上合并到 `template` 表,通过 `template_kind` discriminator 区分。

#### 5.5.2 模板列表 (`/templates`)

- Tab 切换:报价模板 / 核价模板 / Excel 模板
- 列:模板名称、版本、状态、引用产品分类、组件数量、创建人、更新时间
- 工具栏:新建 / 派生新草稿 / 发布 / 归档 / 删除 / 复制
- "派生新草稿":对 PUBLISHED/ARCHIVED 模板复制一份 version 自增的 DRAFT,继续编辑

#### 5.5.3 模板配置 (`/templates/:id`)

- **基础信息**(2026-05-14 明确编辑约束):

  | 字段 | 含义 | DRAFT 状态 | PUBLISHED / ARCHIVED 状态 |
  |---|---|---|---|
  | `name` | 模板名称 | ✅ 可改 | ❌ 冻结 |
  | `template_kind` | QUOTATION / COSTING / EXCEL | ✅ 可改 | ❌ 冻结 |
  | `category_id` | 产品分类 | ✅ 可改 | ❌ 冻结 |
  | **`customer_id`** | **适用客户**(NULL = 通用 / 非空 = 客户专属) | **✅ 可改** | **❌ 冻结(要改先「派生新草稿」)** |
  | `description` / `usage_note` | 描述与使用说明 | ✅ 可改 | ❌ 冻结 |
  | `status` | DRAFT → PUBLISHED 由"发布"动作切换;PUBLISHED → ARCHIVED 由"归档"动作切换;`status` 字段本身不开放直接修改 | — | — |

  **核心规则**:**只有 DRAFT 状态可以修改 `customer_id`**(即"适用客户"绑定);PUBLISHED 后冻结,要改必须先「派生新草稿」获得一份新 DRAFT,改完后再发布替代。  
  实现位点:前端 `cpq-frontend/src/pages/template/TemplateConfigPanel.tsx` 「适用客户」及所有元数据字段 `disabled={!isDraft}`;后端 `TemplateService.update()` 对非 DRAFT 模板整体拒绝任何字段变更(`"Only DRAFT templates can be edited"`),`customer_id` 自然受此约束保护。

- **产品属性区**(`product_attributes` JSONB):定义模板使用时需收集的产品级属性(规格/单位/数量/交货天数等)
- **组件区**(QUOTATION/COSTING 模板):拖拽添加组件,组件按显示顺序排列;每组件可设"Tab 名称"覆盖默认名
- **Excel 视图 Tab**(V149 合并入模板):
  - 列定义编辑器(添加 / 删除 / 重排序列)
  - 每列 5 字段:`col_key / title / source_type / variable_path | formula / comparison_tag / hidden`
  - 公式列编辑器 `[X]` 引用同行其他列
  - "🌐 全局变量"按钮:选择全局变量自动编译 BNF 路径
- **subtotal 公式**:产品小计 = `subtotal_formula`(token 数组,可引用各组件小计 + 产品属性 NUMBER 字段)

#### 5.5.4 模板状态机

```
DRAFT  ──发布──►  PUBLISHED  ──归档──►  ARCHIVED
   ▲                  │
   │                  │
   └──派生新草稿──────►(新一份 DRAFT)
```

- 同 `template_series_id` 内多版本(version v1.0 / v1.1 / v1.2...)
- v2.7 后撤销"同 (customer_id, category_id) 单 PUBLISHED"约束,允许多 PUBLISHED 并存
- UI 默认显示 series 内最新 PUBLISHED;Excel 模板默认 `is_default=true` 一份(同 linked_template_id 内 partial unique)
- **DRAFT 字段可改性**(2026-05-14 明确):DRAFT 状态下 `name / template_kind / category_id / customer_id / description / usage_note` 全部可改;PUBLISHED 后所有标识字段冻结,要改必须先「派生新草稿」(详细映射见 §5.5.3 字段表)
- **客户绑定与 Step1 联动**:模板的 `customer_id` 决定它在「报价单 Step1 选择模板」环节是否对该客户可见 — 客户专属模板(`customer_id = 当前客户`)优先匹配,无客户专属时退回通用模板(`customer_id IS NULL`)。详见 §3.2.1「按客户过滤可选模板」

#### 5.5.5 产品模板绑定 (`/template-bindings`)

`ProductTemplateBinding` 表存储产品(+ 可选工序组合)→ 模板的关联,作为客户没有专属模板时的兜底:

```
ProductTemplateBinding {
  id, product_id, process_ids_hash (可空,工序组合的稳定哈希),
  template_id, is_default ∈ {true, false},
  created_at
}
```

- 报价单第一步选模板优先级:`customer.default_template_id` > `ProductTemplateBinding.is_default=true` > 列表选择
- 同 `(product_id, process_ids_hash)` 内仅 1 个 `is_default=true`(PostgreSQL partial unique index)

#### 5.5.6 模板版本对比 (`/template-comparison`)

- 选两个同 series 的版本 → 并排展示组件 / 字段 / 公式 / Excel 列结构差异
- 数据从 `template.components_snapshot` 读取(不实时查组件表,确保历史一致性)
- **注**:V3 起此独立菜单使用度低,主流比对在报价单第二步内嵌"比对视图"完成。本菜单保留作版本审计用途。

#### 5.5.7 非功能

- 模板列表打开延迟 < 1s
- 模板详情打开延迟 < 1.5s(含组件展开)
- Excel 列定义上限:软上限 50 列(超过 UI 警告)
- 模板派生新草稿原子操作:同 series 内 version 自增由数据库 sequence 保证唯一

---

### 5.6 全局变量层 (L1 注册层)

#### 5.6.1 设计动机

V104 前,公式只能用 BNF 直查物理表(如 `costing_element_price.costing_price`),业务理解成本高 + 路径写错难调试。V104 引入"变量注册层":

- 给 L0 物理数据起一个**业务可读名称**(如 `ELEM_PRICE` → 元素单价)
- 公式编辑器从下拉选择,自动编译为 BNF 路径
- 变更全局变量定义时,所有引用它的模板自动跟随

#### 5.6.2 数据模型

```
GlobalVariableDefinition {
  id, code (业务编码,唯一,如 'ELEM_PRICE'),
  name (中文名,如 '元素单价'),
  description,
  source_kind ∈ {VIEW, TABLE, EXPRESSION},
  source_path,              -- 如 'v_costing_element_price'
  value_field,              -- 如 'costing_price'
  key_fields JSONB,         -- 静态/动态 key 配置(见下)
  unit, category, status,
  created_at, updated_at
}
```

#### 5.6.3 静态 key vs 动态 key

| 模式 | 场景 | key_fields 配置 |
|---|---|---|
| **静态 key** | 整张报价单一个值(如 CNY→USD 汇率) | 固定值 `{from_currency:'CNY', to_currency:'USD'}` |
| **动态 key** | 多行 driver 按当前行取值(如电镀方案行的元素 → 元素单价) | 字段引用 `{element_name: '$row.element_name'}`,运行时由 ImplicitJoinRewriter 注入 |

#### 5.6.4 全局变量列表 (`/global-variables`)

- 列:code、name、source、value_field、key 类型、引用模板数、状态、更新时间
- 工具栏:新建 / 编辑 / 启用-停用 / 删除 / 变更日志
- 删除保护:被任意模板 / 组件公式引用时禁止删除

#### 5.6.5 公式编辑器集成

- 公式 token 类型 `global_variable` 调用 picker UI
- picker 显示全局变量列表(按 category 分组),用户选定后自动填充 `code` + 编译 `path`
- "⚡ 动态查表"按钮:把 key 字段从静态切换为字段引用模式

#### 5.6.6 变更日志

- 全局变量定义任何变更走 `VersionedWriter`,字段级记录到 `change_log`
- 前端 `/change-log` 提供查询(按 entity_type='GLOBAL_VARIABLE' 筛选)

---

### 5.7 变量标签 与 比对标签

#### 5.7.1 变量标签 (`variable_label`,V149)

**用途**:为模板 Excel 列 / 公式 token 中引用的"变量"提供业务可读标签,便于权限管理与跨模板搜索。

```
VariableLabel {
  id, code (业务编码), name (展示名),
  category, description, status,
  created_at, updated_at
}
```

**前端集成**:模板配置 → Excel 视图 Tab,新增列时可选"绑定变量标签",运行时按标签批量替换。

#### 5.7.2 比对标签 (`comparison_tag`,V114)

**用途**:Excel 模板列设置 `comparison_tag` 后,报价单第二步"比对视图"按 tag 分组渲染,实现"成本明细 / 商务加价 / 终价"的语义化对比。

```
ComparisonTag {
  code (业务编码,如 'MATERIAL_COST'),
  label (中文名,如 '材料成本'),
  group_name (分组,如 '成本明细'),
  sort_order,
  status
}
```

**V114 已注册 13 个 tag**(完整列表见 V114 migration):

| group_name | tag 示例 |
|---|---|
| 成本明细 | MATERIAL_COST / PROCESSING_COST / TOOLING_FEE / DESIGN_COST / ENERGY_COST |
| 商务加价 | MANAGEMENT_COST / FINANCIAL_COST / PROFIT / TAX |
| 终价 | UNIT_TOTAL_COST / UNIT_TOTAL_QUOTE / UNIT_PER_PCS |
| 其他 | (Excel 列未设 tag 的默认归"其他"组) |

**配置入口**:`/comparison-tags`(`SYSTEM_ADMIN` 维护);Excel 模板配置时下拉选择。

**反模式预警**:Excel 模板列设了 `comparison_tag` code 但 `comparison_tag` 表未注册 → 比对视图 tagLabel 显示 raw code,全归"其他"组。详见 `docs/反模式.md` AP-22 类别 D。

---

### 5.8 数据源管理 (`/datasources`)

#### 5.8.1 用途

组件字段 `type=DATA_SOURCE` 时,运行时调用 `DataSource` 配置执行查询(SQL 或外部 API),返回值填入单元格。典型场景:输入客户料号后自动查"上次报价单价"。

#### 5.8.2 数据源类型

| type | 实现 | 适用 |
|---|---|---|
| **SQL** | 直连 PostgreSQL,执行预编译 SQL,参数化绑定 | 内部数据查询 |
| **API** | HTTP REST 调用,credentials 加密存储 | 对接 ERP / CRM / 外部价格服务 |

#### 5.8.3 数据模型

```
DataSource {
  id, name, type ∈ {SQL, API},
  description,
  -- SQL 类型字段
  sql_template,
  -- API 类型字段
  http_method, http_url, http_headers JSONB, request_body_template,
  credentials_encrypted,   -- AES 加密
  -- 通用
  result_field_mapping JSONB,
  cache_ttl_seconds,       -- 单次报价会话内缓存时间,默认 300
  status, created_at, updated_at
}

DataSourceParameter {
  id, datasource_id, name, type ∈ {STRING, NUMBER, DATE, BOOLEAN},
  source ∈ {USER_FIELD, SYSTEM_PARAM, CONSTANT},
  source_key,            -- USER_FIELD 时指字段路径,SYSTEM_PARAM 时指系统参数 key
  is_required, default_value,
  sort_order
}
```

#### 5.8.4 数据源编辑 (`/datasources/:id/edit`)

- **基础信息**:名称、类型、描述、状态
- **SQL / API 配置**:对应字段表单,API 类型 credentials 字段加密存储不回显
- **参数列表**(`DataSourceParameter`):用户字段引用 / 系统参数 / 常量,标记是否必填
- **结果字段映射**:把 SQL 结果列名 / API 响应 JSON path 映射到组件字段

#### 5.8.5 系统参数

预置系统参数(`source=SYSTEM_PARAM`):

| key | 含义 | 来源 |
|---|---|---|
| `CURRENT_USER_ID` | 当前操作用户 | session |
| `CURRENT_USER_ROLE` | 当前用户角色 | session |
| `CURRENT_QUOTATION_ID` | 当前报价单 ID | 上下文 |
| `CURRENT_CUSTOMER_ID` | 当前客户 ID | 上下文 |
| `CURRENT_HF_PART_NO` | 当前 lineItem 料号 | 上下文 |

#### 5.8.6 权限隔离

- 数据源**创建/编辑**:仅 `SYSTEM_ADMIN`
- 数据源**调用**:任何角色(由报价单上下文触发,不暴露原始 SQL/API URL)
- API 凭证加密存储,日志中脱敏

#### 5.8.7 非功能

- SQL 执行超时:30 秒
- API 调用超时:10 秒
- 查询结果缓存:进程内 5 分钟(同 datasource_id + 参数 hash)
- 失败降级:错误信息脱敏后回前端,不阻断其他字段

---

### 5.9 基础数据配置 (`/basic-data-config`)

#### 5.9.1 用途

注册可被 BNF 路径引用的"sheet"(物理表 / 视图),管理其字段元数据,作为 PathPickerDrawer 下拉数据源。

#### 5.9.2 数据模型

```
BasicDataConfig {
  id, code (sheet 编码,如 'mat_part'),
  name (中文名,如 '料号主档'),
  physical_table,         -- 物理表/视图名,如 'mat_part' 或 'v_costing_summary_full'
  template_kind ∈ {QUOTATION, COSTING, BOTH},  -- V79 加,决定哪类模板可引用
  category, description, status,
  created_at, updated_at
}

BasicDataAttribute {
  id, config_id,
  field_name,             -- 物理列名
  display_name,           -- 中文展示
  type ∈ {STRING, NUMBER, DATE, BOOLEAN},
  unit,
  is_primary_key,
  is_searchable,
  sort_order
}

DerivedAttribute {
  id, config_id,
  field_name,
  expression,             -- 派生公式(聚合/查表/表达式)
  description
}
```

#### 5.9.3 配置入口

- 「基础数据配置」菜单一页式表格:左侧 sheet 列表,右侧字段列表
- 新建 sheet → 选物理表 → 自动 introspect 字段元数据(`information_schema.columns`)→ 用户补 display_name / unit / is_searchable
- 派生字段:写表达式 + 描述

#### 5.9.4 PathPickerDrawer

公式编辑器 / Excel 模板列编辑时,点击 "路径选择" 弹出 `PathPickerDrawer`:

- 左侧:`BasicDataConfig` sheet 列表(按 template_kind 过滤)
- 右侧:选中 sheet 的字段列表(BasicDataAttribute + DerivedAttribute)
- 用户选定字段 → 自动生成 BNF 路径 `<sheet_code>.<field_name>` 或带谓词路径

#### 5.9.5 非功能

- sheet 注册数量上限:200 个(经验值,超过 UI 警告)
- 单 sheet 字段数:无限制,但 UI 列表分页
- 字段 introspect 失败 → 报错"物理表不存在",拒绝创建

---

## 6. 平台基础

平台基础模块面向 `SYSTEM_ADMIN`,提供用户体系、审批规则、通知、日志、配置中心、并发锁、主数据维护等支撑能力。

### 6.1 用户与组织

#### 6.1.1 用户管理 (`/system/users`)

**功能**:

- 用户列表(分页 + 角色 / 状态 / 区域 / 部门筛选)
- 新建用户:用户名、姓名、邮箱、初始密码、角色、区域、部门
- 编辑:姓名、邮箱、角色、区域、部门、状态(ACTIVE / INACTIVE / LOCKED)
- 重置密码:生成临时密码 + 邮件通知 + `is_first_login=true`
- 解锁:管理员手动解锁因失败次数过多 LOCKED 的账号

**数据模型**:

```
User {
  id, username (登录名,唯一), display_name, email,
  password_hash, role ∈ {SALES_REP, SALES_MANAGER, PRICING_MANAGER, SYSTEM_ADMIN},
  region_id, department_id,
  status ∈ {ACTIVE, INACTIVE, LOCKED},
  is_first_login,                 -- true: 强制改密
  initial_password_expires_at,    -- 7 天后失效
  failed_login_count,
  last_login_at,
  created_at, updated_at
}

PasswordResetToken {
  id, user_id, token (随机字符串),
  expires_at,                    -- 24 小时
  used_at
}
```

**首次登录强制改密流程**:

1. 管理员创建用户 → 生成初始密码 + 邮件发送
2. 用户首次登录 → `is_first_login=true` → 强制跳转 `/change-password`
3. 改密成功 → `is_first_login=false`,正常使用
4. 超过 7 天未改密 → 自动 `status='LOCKED'`,需管理员重置

#### 6.1.2 区域管理 (`/system/regions`)

- 单表 `region`:`id / code / name / sort_order / status`
- 用于审批规则 DYNAMIC 路由(如"按区域路由到经理")
- 用户 / 客户均关联 region_id

#### 6.1.3 部门管理 (`/system/departments`)

- 单表 `department`:`id / code / name / parent_id / sort_order / status`
- 支持父子层级
- 用户关联 department_id

---

### 6.2 审批规则 (`/system/approval-rules`)

#### 6.2.1 规则配置

```
ApprovalRule {
  id, name, priority,                       -- 数字越小优先级越高
  rule_type ∈ {FIXED, DYNAMIC},
  match_conditions JSONB,                   -- 匹配条件
  approver_id,                              -- FIXED 类型:固定审批人 user_id
  approver_resolver,                        -- DYNAMIC 类型:如 'salesManager-of-region'
  status ∈ {ACTIVE, INACTIVE},
  created_by, created_at, updated_at
}
```

**match_conditions 示例**:

```json
{
  "amount_min": 100000,
  "amount_max": null,
  "customer_level": ["VIP", "DIAMOND"],
  "region_id": null,
  "department_id": null,
  "product_category_id": null
}
```

#### 6.2.2 路由算法(纯 Java,无 Drools)

由 `ApprovalRuleService.route(quotation)` 实现:

1. 加载所有 ACTIVE 规则,按 (priority ASC, rule_type=FIXED 优先) 排序
2. 遍历规则,检查 `match_conditions` 是否全部匹配
3. 第一条命中:
   - FIXED → 直接返回 `approver_id`
   - DYNAMIC → 调用对应 resolver(如查询客户所在区域的销售经理)
4. 全部不匹配 → 兜底:`created_at` 最早的 ACTIVE 系统管理员

#### 6.2.3 工具栏动作

- 新建规则 / 编辑 / 启用-停用 / 删除 / 调整优先级(拖拽排序)

#### 6.2.4 非功能

- 规则匹配延迟 < 100ms
- 规则数量上限:软上限 50 条(超过 UI 警告)

---

### 6.3 站内通知 (`/system/notifications`)

#### 6.3.1 通知类型

| type | 触发场景 | 通道 |
|---|---|---|
| `APPROVAL_PENDING` | 报价单提交时通知审批人 | 站内 + 邮件 |
| `APPROVAL_REMINDER` | 48 小时未审批催办 | 站内 + 邮件 |
| `APPROVAL_APPROVED` | 报价单审批通过 | 站内 + 邮件 |
| `APPROVAL_REJECTED` | 报价单被驳回 | 站内 + 邮件 |
| `PASSWORD_RESET` | 密码重置链接 | 邮件(无站内) |
| `ROLE_CHANGED` | 用户角色变更 | 站内 |
| `WITHDRAW_REQUEST` | 销售撤回 SUBMITTED 报价 | 站内通知原审批人 |

#### 6.3.2 数据模型

```
Notification {
  id, user_id, type, title, content,
  link,                              -- 点击跳转的 URL
  related_id, related_type,          -- 关联实体
  is_read, read_at,
  created_at
}
```

#### 6.3.3 前端入口

- 顶部导航栏 🔔 角标显示未读数
- `/system/notifications` 列表页:全部 / 未读 Tab + 类型筛选
- 点击通知 → 标记已读 + 跳转 link

#### 6.3.4 邮件发送

- SMTP 通过 `application.properties` `quarkus.mailer.*` 配置(V1 无 UI)
- 失败仅记录日志,**不阻断**站内消息写入(双通道独立)
- 模板使用 Quarkus Qute

#### 6.3.5 清理

定时任务每周一 04:00 清理 `created_at < NOW()-6个月` 的通知。

---

### 6.4 操作日志 与 变更日志

#### 6.4.1 操作日志 (`/system/operation-logs`)

`OperationLog` 表记录用户**关键业务动作**(谁、何时、做了什么):

```
OperationLog {
  id, user_id, username_snapshot,
  action,                          -- 'CREATE_QUOTATION' / 'SUBMIT_QUOTATION' / 'APPROVE' / ...
  entity_type, entity_id,
  details JSONB,                   -- 详情(如折扣手动调整原因)
  ip_address, user_agent,
  created_at
}
```

记录覆盖:

- 登录 / 登出 / 改密
- 报价单 提交 / 撤回 / 审批 / 驳回 / 复制
- 折扣手动调整(必填原因)
- 定价策略 / 审批规则 创建-编辑-删除
- 数据源 创建-编辑-删除

#### 6.4.2 变更日志 (`/change-log`,`versioning` 模块)

`change_log` 表记录关键实体的**字段级**变更(由 `VersionedWriter` 拦截写入):

```
ChangeLog {
  id, entity_type, entity_id,
  field_name, old_value, new_value,
  changed_by, change_reason,
  created_at
}
```

覆盖实体:

- `Template` 字段级变更
- `Component` 字段级变更
- `GlobalVariableDefinition` 变更
- `BasicDataConfig` 变更
- `CostingSummaryOverride` 写入(便于追溯试算历史)
- 关键基础数据(L0 价格表)修改

**前端入口**:

- `/change-log` 一页式时间轴(按 entity_type 筛选)
- 模板 / 组件 / 全局变量详情页 → "变更日志"Tab

#### 6.4.3 与操作日志的区别

| 维度 | 操作日志 | 变更日志 |
|---|---|---|
| 粒度 | 业务动作 | 字段级数据 |
| 主体 | 用户行为 | 实体变迁 |
| 用途 | 审计、合规 | 数据回溯、调试 |
| 写入入口 | 业务 Service 主动调用 | 拦截 `VersionedWriter` 自动写 |

---

### 6.5 系统配置中心 (`/system-config`)

#### 6.5.1 定位

集中管理 23+ 条运行时配置项(导入阈值、校验参数、性能调优等)。V1 提供 REST API,**不提供 UI**(SYSTEM_ADMIN 通过 API 工具改值)。

#### 6.5.2 数据模型

```
SystemConfiguration {
  key (业务编码,如 'IMPORT_MAX_ROWS'),
  value,
  category,                       -- 分组,如 'IMPORT' / 'PRICING' / 'PERFORMANCE'
  description,
  default_value,
  data_type ∈ {STRING, NUMBER, BOOLEAN, JSON},
  updated_at, updated_by
}
```

#### 6.5.3 配置项示例

| key | 用途 | 默认值 |
|---|---|---|
| `IMPORT_MAX_ROWS` | 单次导入最大行数 | 5000 |
| `IMPORT_SESSION_TTL_HOURS` | 导入会话保留时长 | 72 |
| `PRICING_CALC_TIMEOUT_MS` | 折扣计算超时 | 200 |
| `FORMULA_PRECISION_TOLERANCE` | 公式前后端误差容差 | 0.01 |
| `PART_VERSION_MAX_PER_PART` | 单料号版本数上限 | 20 |
| `DATA_SOURCE_CACHE_TTL_SECONDS` | 数据源缓存 | 300 |

完整列表见 `application.properties` 或 V_NN migration。

#### 6.5.4 缓存策略

- 配置加载到 `ConcurrentHashMap` 进程内缓存(单实例)
- 修改通过 API 后立即更新缓存,无需重启
- 重启后从数据库重新加载

---

### 6.6 并发锁机制

#### 6.6.1 业务背景

报价单 / 核价单同一时刻可能多用户同时操作同一料号 / 同一客户;基础数据导入可能与日常报价并发;DDL 扩列操作可能与任何运行时业务冲突。需要锁机制保证一致性。

#### 6.6.2 产品级悲观锁

`product_import_lock` 表实现"料号级 / 客户级"自适应粒度锁:

```
ProductImportLock {
  id, lock_type ∈ {PART, CUSTOMER, GLOBAL},
  lock_key,                       -- PART:hf_part_no / CUSTOMER:customer_id / GLOBAL:'*'
  holder_id,                      -- 持锁人 user_id
  acquired_at, expires_at,        -- 默认 5 分钟,可心跳续期
  heartbeat_at,
  metadata JSONB                  -- 如导入会话 ID
}
```

**协议**:

- 获取锁:`INSERT ... ON CONFLICT DO NOTHING`,失败返回 HTTP 423 Locked + 当前持锁人信息
- 续期:每 30 秒心跳更新 `heartbeat_at` + `expires_at`
- 释放:用户主动释放 / 心跳超时被回收

#### 6.6.3 DDL 全局锁

`ddl_operation_lock` 表实现 DDL 操作的全局互斥锁:

```
DDLOperationLock {
  id (固定值 'GLOBAL_DDL_LOCK',单行 UPSERT),
  holder_id, operation_type,      -- 'ADD_COLUMN' / 'CREATE_VIEW' / ...
  acquired_at, expires_at,
  metadata JSONB
}
```

- 用于「DDL 扩列管理」(`/system-monitor/ddl-extension`)模块
- DDL 操作前先获取此锁,确保同时只有一个 DDL 进行
- 与产品级锁互斥(DDL 进行中拒绝所有产品锁请求)

#### 6.6.4 锁互斥协议

| 操作 | 需要锁 |
|---|---|
| 报价单编辑同一草稿 | 不加锁(最后写入胜出) |
| 基础数据导入(V6) | 获取 PART/CUSTOMER 级锁 |
| 核价单 compute() | 不加锁(读多写少,无并发风险) |
| DDL 扩列 | 获取 DDL 全局锁 |
| 模板发布 | 不加锁(模板版本自增隔离) |

#### 6.6.5 监控页面 (`/system-monitor/locks`)

- 当前持锁列表:lock_type、lock_key、holder、acquired_at、expires_at
- 强制释放(SYSTEM_ADMIN 应急)
- 锁竞争统计:近 24 小时 423 响应次数

#### 6.6.6 DDL 扩列管理 (`/system-monitor/ddl-extension`)

- 列出所有可被业务用户扩列的"基础数据 sheet"
- 选 sheet → 加列(name/type/nullable/default)→ 后端获取 DDL 锁 → 执行 ALTER TABLE → 自动同步 `BasicDataAttribute`
- 历史扩列记录 `ddl_operation_history` 表

---

### 6.7 主数据维护 (`/master-data`)

#### 6.7.1 定位

「主数据维护」是 `PRICING_MANAGER` 的核心工作台,以统一界面查看 / 编辑跨多张物理表的"主数据"(料号 / 元素 / 材料等)。

#### 6.7.2 数据总览 (`/master-data`)

- 卡片式呈现各类主数据的统计:料号总数、客户料号映射数、元素价版本数、材料价版本数、汇率版本数等
- 快捷入口跳转到具体管理页

#### 6.7.3 版本历史 (`/master-data/history`)

- 按 entity_type 列出所有有版本管理的数据的版本历史
- 数据来源:`costing_price_version` 表 + `part_version` 表
- 列:entity_type、版本号、状态、创建人、发布时间

#### 6.7.4 字段重要性 (`/master-data/field-importance`)

- 标注每张主数据表各字段的"重要性等级"(KEY / IMPORTANT / NORMAL)
- 字段标记 KEY 后,变更走严格审批流;IMPORTANT 写入操作日志;NORMAL 仅记录变更日志
- 目前为 V1 数据辅助,V2 接入审批工作流

---

## 7. 数据导入

数据导入子系统负责将业务用户的 Excel 数据安全、可预览、可回退地写入系统主数据。当前活跃版本为 **V6 staging 三步流程**(2026-05-12 上线,替代旧 V5 六步向导)。

### 7.1 V6 staging 三步流程

#### 7.1.1 流程总览

```
┌──────────┐   ┌────────────┐   ┌──────────────┐
│ 1. 上传   │→ │ 2. 预览决策 │→ │ 3. 确认入库   │
│  + 映射   │   │  (staging)  │   │              │
└──────────┘   └────────────┘   └──────────────┘
```

#### 7.1.2 第一步:上传 + 映射

- 用户上传 Excel 文件
- 选择「导入映射模板」(`import_mapping_template`)— 决定 Excel 列 ↔ 物理字段的对应关系
- 后端创建 `ImportSession`(`status='STAGING'`),把数据写入 staging 表(隔离生产数据)

```
ImportSession {
  id, user_id, target_table,            -- 目标表(如 'product' / 'costing_part_material_bom')
  template_id,                          -- 引用的 mapping template
  uploaded_filename, total_rows,
  status ∈ {STAGING, PREVIEWED, CONFIRMED, CANCELLED, FAILED},
  metadata JSONB,                       -- 含 hfPairs / 用户决策 / 文件指纹
  created_at, completed_at
}
```

#### 7.1.3 第二步:预览决策

- 用户在 staging 表上查看 diff:**新增行 / 更新行 / 无变化行**
- **DiffDetector**(V6 引入):用指纹(规格 SHA256)比对而非字段级对比,避免空格 / 大小写 / 小数位差异误判
- 决策(每行可独立选):
  - `INSERT` — 新增到生产表
  - `UPDATE` — 覆盖生产表已有行
  - `BUMP` — 创建新版本(料号版本场景)
  - `SKIP` — 跳过此行
- 决策结果存入 `ImportSessionDecision`:

```
ImportSessionDecision {
  id, session_id, staging_row_id,
  decision ∈ {INSERT, UPDATE, BUMP, SKIP},
  decided_by, decided_at,
  notes
}
```

#### 7.1.4 第三步:确认入库

- 用户点击"确认入库"按钮
- 后端:加产品锁(基础数据导入对应 `lock_type=PART/CUSTOMER`)→ 批量 UPSERT 到生产表 → 释放锁
- session 状态 `STAGING → PREVIEWED → CONFIRMED`
- 失败回滚:整个 session 写入失败 → 自动 `FAILED` 状态,生产表不动

#### 7.1.5 关键设计原则

| 原则 | 体现 |
|---|---|
| **指纹比对** | DiffDetector 用 SHA256 指纹防误判 BUMP |
| **mergeMapping 不动 current_version** | ON CONFLICT 时仅更新映射,不重置版本号(修复了 V5 双重升版问题) |
| **字段名兼容** | DTO 同时接受 `customerProductNo` / `customer_product_no` 等多种命名 |
| **V6 优先路径** | `listCandidates` 等查询优先走 `metadata.hfPairs`,无则降级走传统映射查询 |

### 7.2 客户料号映射

#### 7.2.1 客户料号 → HF 料号映射

`CustomerMaterialMapping`(见 5.2.6)是客户料号到 HF 内部料号的多对一映射。Excel 导入(产品 / BOM / 工序)时自动按 `customer_product_no + customer_id` 查映射,缺失则报错。

#### 7.2.2 Excel 模板配置 (`customer_excel_template`)

每个客户可绑定一份自定义 Excel 模板:

```
CustomerExcelTemplate {
  id, customer_id, name,
  columns JSONB,                        -- 客户 Excel 列结构
  excel_view_config JSONB,              -- Handsontable 渲染配置 (V2.2 引入)
  status, created_at
}
```

报价单第二步「Excel 视图」可选用客户绑定的模板渲染,默认列结构覆盖客户的偏好。

### 7.3 导入历史 (`/import-history`)

- 列:session_id、目标表、上传文件名、上传时间、行数、状态、决策摘要(INSERT N / UPDATE M / SKIP K)、操作人
- 点击进 session 详情:staging 表内容回看 + 决策回看
- **不可重放**:CONFIRMED 的 session 不可二次入库(避免重复写入);若需类似数据 → 重新上传新 session

### 7.4 非功能

| 指标 | 要求 |
|---|---|
| 单次 Excel 上传上限 | 5000 行(`IMPORT_MAX_ROWS` 配置,可调) |
| Excel 解析延迟 | < 5 秒(5000 行) |
| Diff 计算延迟 | < 3 秒(对比生产表) |
| Session 保留期 | 72 小时(`IMPORT_SESSION_TTL_HOURS`),超时自动清理 |
| 失败处理 | 整 session 原子失败,生产表无脏数据 |
| 并发导入 | 同一目标表同时仅 1 个 STAGING session(产品锁约束) |

---

## 8. 技术架构

### 8.1 技术栈

| 层 | 技术 | 版本 | 说明 |
|---|---|---|---|
| **后端框架** | Quarkus | 3.23.3 | RESTEasy Reactive |
| **JDK** | OpenJDK | 17 | JVM 模式(非 native) |
| **持久化** | Hibernate ORM + Panache | 自动 | 实体 + Repository 模式 |
| **数据库** | PostgreSQL | 16 | JSONB 灵活配置 |
| **数据迁移** | Flyway | 自动 | `db/migration/V_NN__*.sql` |
| **公式引擎** | Apache Commons JEXL + 前端 JS 等价层 | 3.x | 后端校验 + 前端实时 |
| **邮件** | Quarkus Mailer | 自动 | SMTP via `application.properties` |
| **Excel 导出** | Apache POI | 5.x | V2 PDF/Excel 导出用 |
| **PDF 模板** | Quarkus Qute | 自动 | V2 PDF 输出 |
| **前端** | React | 18.x | Vite 构建,TypeScript |
| **UI 组件** | Ant Design | 5.x | Drawer 优先策略 |
| **路由** | React Router | 6.x | |
| **状态管理** | Zustand / Context | — | |
| **Excel 编辑器** | Handsontable | — | 第二步 Excel 视图 |
| **HTTP** | axios | — | services 层封装 |
| **公式渲染** | 自研 JS 表达式引擎 | — | 与后端 JEXL 等价 |

**明确不使用**:Drools、Kogito、GraalVM Native、Spring Boot、Vue、Vuex。

### 8.2 计算引擎

系统内有三个计算引擎,分层职责清晰:

| 引擎 | 实现 | 触发时机 | 数据来源 |
|---|---|---|---|
| **折扣计算** | `PricingRuleService` (纯 Java) | 报价单第三步进入 | `pricing_strategy` + `pricing_rule` |
| **审批路由** | `ApprovalRuleService` (纯 Java) | 报价单提交时 | `approval_rule` |
| **公式引擎** | `FormulaEngine` + JEXL + 前端 JS 等价层 | 单元格失焦 / 草稿保存 / 提交校验 | 组件 token + Excel 模板列 + BNF 路径 |

#### 8.2.1 公式引擎前后端分工

- **前端 JS 表达式引擎**:报价时每行数据录入后实时计算(零延迟,无网络)
- **后端 JEXL**:草稿保存 / 提交审批时最终校验
- **一致性约束**:误差容差 ±0.01 元
  - 容差内:静默以 JEXL 结果覆盖
  - 超出容差:返回错误,前端高亮标红 + 阻止提交
- 不允许静默修改用户录入值,金额差异必须告知

#### 8.2.2 公式 token 设计

公式表达式存储为 token 数组(JSONB),完整 token 类型见附录 10.3。运行时 evaluator 按 token 类型分派求值器:

- `field` → 取同行其他字段值
- `path` → 走 BNF 路径解析(见 8.3)
- `component_subtotal` → 跨组件 SUM 后传入
- `global_variable` → 编译产物 `path` 字段 → 走 path resolver
- `product_attribute` → 读 lineItem.productAttributes
- `quotation_field` → 读 quotation.* 字段

#### 8.2.3 @Blocking 线程模型

JEXL 表达式求值和数据库阻塞调用须在 Quarkus Worker 线程池运行,严禁 Vert.x I/O 事件循环。实现要求:

- 调用 `FormulaEngine.evaluate()` / JDBC 的 Service 方法须标注 `@io.smallrye.common.annotation.Blocking`
- RESTEasy Reactive 下未标注将抛运行时异常

### 8.3 BNF 路径与隐式 JOIN 协议

#### 8.3.1 BNF 路径文法

```
path        := sheet ['[' predicate ']'] '.' field
predicate   := condition (',' condition)*
condition   := column '=' literal
sheet       := <BasicDataConfig.code 或 物理表/视图名>
field       := <列名>
literal     := <字符串/数字>
```

**示例**:

```
v_costing_summary_full.material_cost
mat_part[hf_part_no='3100080003'].unit_weight
v_costing_exchange_rate[from_currency='CNY' AND to_currency='USD'].costing_rate
```

#### 8.3.2 三种路径写法

| 写法 | 示例 | 何时用 |
|---|---|---|
| **legacy 占位符** | `{HF_PART_NO}` / `{customer_drawing_no}` | 报价单元数据,前端从 lineItem 直接映射 |
| **BNF 路径** | `v_costing_summary_full.material_cost` | 90% 场景,后端 FormulaEngine 求值 |
| **全局变量** | (通过 picker 自动编译为 BNF) | 业务可读,公式编辑器选 |

#### 8.3.3 隐式 JOIN Rewriter

后端 `ImplicitJoinRewriter` 渲染时自动注入谓词,业务路径无需写:

```
注入规则(按以下顺序):
1. hf_part_no = '<lineItem.productPartNo>'  -- 目标表/视图含 hf_part_no 列时
2. customer_id = '<报价单.customerId>'      -- 目标表/视图含 customer_id 列时
3. driver_row 同名列                         -- driver 多行展开时,按当前行业务键
4. part_no = '<...>'                         -- hf_part_no 的同义
```

**关键工程约束**:

- 视图 / 表的列发现走 `information_schema.columns`,**进程内缓存**
- V112 前:视图 DDL 期间被 CASCADE 临时删除瞬间若有请求 → 缓存空集 → 永久残留
- V112 后:空集不缓存 + 重启时清缓存
- **运维规则**:任何 schema DDL (`DROP VIEW CASCADE` / `DROP TABLE CASCADE` / 视图重建) 后**必须重启 Quarkus**

#### 8.3.4 求值结果格式化

| 结果 | 前端显示 |
|---|---|
| 单值 | 直接显示 |
| 单行多列 | 取 path 指定字段 |
| 多行 List | "首值(共N项)" — **此即反模式预警**,见附录 10.4 |
| null | 显示 "—" |

### 8.3.5 模板 SQL 视图（独立配置）

**背景**：Excel 模板（`template_kind=EXCEL`）的列取数路径（`variable_path`）原依赖物理 PG 视图或组件 SQL 视图（`component_sql_view`）。V249/V250 为 `template` 层引入独立的 `template_sql_view` 表，使模板可以维护专属 SQL 视图，与组件层完全隔离。

#### 引用语法

模板 Excel 视图列的 `variable_path` 支持三种写法（按优先级推荐顺序）：

| 写法 | 示例 | 解析目标 |
|---|---|---|
| `$<sql_view_name>.<col>` | `$summary_full.material_cost` | 本模板 `template_sql_view` 表中 `name=summary_full` 的视图 |
| `{code}` 简写 | `{hf_part_no}` | lineItem 内存字段（不查 DB） |
| `<pg_view>.<col>` | `v_costing_summary_full.material_cost` | 物理 PG 视图直引（遗留，不推荐新配） |

> **禁止**在 Excel 视图列路径里使用 `$$<componentCode>.<sql_view_name>` 跨组件引用语法。该语法仅在**组件上下文**（ownerType=COMPONENT）下有效；模板上下文（ownerType=TEMPLATE）的 `SqlViewExecutor` 会强抛 `BusinessException` 拒绝请求。

#### 与组件 SQL 视图的隔离边界

| 维度 | 组件 SQL 视图（component_sql_view） | 模板 SQL 视图（template_sql_view） |
|---|---|---|
| **物理表** | `component_sql_view`，FK → `component.id` | `template_sql_view`，FK → `template.id` |
| **引用路径前缀** | `$<name>` 在组件上下文 | `$<name>` 在模板上下文 |
| **跨引用** | `$$code.name` 可引用 GLOBAL scope 的组件视图 | 禁止 `$$` 前缀（强阻断） |
| **scope** | COMPONENT / GLOBAL | LOCAL（仅本模板） |
| **执行器路由** | `SqlViewExecutor` ownerType=COMPONENT | `SqlViewExecutor` ownerType=TEMPLATE |
| **发布快照** | 冻结到 `template.components_snapshot` JSONB | 冻结到 `template.template_sql_views_snapshot` JSONB |

#### 模板发布时 snapshot 冻结语义

`TemplateService.publish` 在状态机切换 DRAFT→PUBLISHED 时同事务执行两件事：

1. **跨引用校验**：扫 `excel_view_config` 所有列的 `variable_path`，若发现含 `$$` 前缀的跨引用路径，立即抛 `BusinessException` 阻断发布。
2. **snapshot 冻结**：拉取本模板所有 `status=ACTIVE` 的 `template_sql_view` 行，序列化写入 `template.template_sql_views_snapshot` JSONB（与 `components_snapshot` 同事务）。

报价单提交（DRAFT→SUBMITTED）后，`SqlViewExecutor` 查询优先级：**报价单 snapshot > 模板 template_sql_views_snapshot > 实时 template_sql_view**，确保历史报价可复现。

`TemplateService.createNewDraft` 派生新草稿时，`TemplateSqlViewService.deepCopySqlViews` 把所有 `template_sql_view` 行 deep-copy 到新草稿，新草稿独立维护，修改不影响原版本。

#### 路径校验规则（强制）

1. **`$$` 禁用**：模板 Excel 视图列 `variable_path` 不得包含 `$$` 前缀（会在 `PathPickerDrawer ownerContext=TEMPLATE` 层拦截 + 后端 publish 时再次校验）。
2. **V44 老表禁用**（AP-53 延伸）：`template_sql_view.sql_template` 内部禁止 `FROM mat_part / mat_bom / mat_process / mat_fee / plating_plan / mat_customer_part_mapping / element_price*` 等 V44 已废弃表；过渡期可包一层 PG 视图，Phase 4 完成后必须改查 V6 表。
3. **dry-run 保存前验证**：新建 / 编辑 `template_sql_view` 保存时后端自动执行 EXPLAIN dry-run，拒绝 DDL/DML 语句 + 拒绝 `:hfPartNo` 标量占位符。

#### 配置入口（UI）

1. 进入「模板配置」→「TemplateConfiguration」编辑页。
2. 顶部 Tabs 切换到第三个 Tab「SQL 视图」（`TemplateSqlViewsTab`）。
3. 在 Tab 内新建 / 编辑 / 删除 / dry-run 视图（PUBLISHED 模板只读，需先派生草稿）。
4. 切换到第一个 Tab「列配置」→「ExcelViewConfigTab」，编辑某列的 `variable_path` 时点击「SQL 视图」按钮，弹出 `PathPickerDrawer`（`ownerContext=TEMPLATE`），可在"SQL 视图" Tab 选择本模板的视图字段，自动写回 `$<name>.<col>` 格式。

**验收标准**

- Given 管理员在 TemplateSqlViewsTab 新建名为 `summary_full` 的 SQL 视图并保存；When 在列配置里通过 PathPickerDrawer 选择该视图字段；Then `variable_path` 写回为 `$summary_full.<col>` 格式。
- Given 模板包含 `$$comp.view.col` 路径的列；When 管理员执行「发布」；Then 系统抛出业务异常，阻断发布，提示需先修正路径。
- Given 模板 PUBLISHED 后用户以该模板创建报价单并提交；When 后续 `template_sql_view` 被修改；Then 已提交报价单的 Excel 视图仍使用发布时的 snapshot 数据，不受影响。

### 8.4 视图层与缓存策略

#### 8.4.1 关键视图

| 视图 | 用途 | 引入版本 |
|---|---|---|
| `v_costing_element_price` | 默认版本元素单价 | V78 |
| `v_costing_material_price` | 默认版本材料单价 | V78 |
| `v_costing_exchange_rate` | 默认版本汇率 | V78 |
| `v_costing_summary_full` | 7 项 metric PIVOT 宽表 | V80 |
| `v_part_plating_scheme` | 复合电镀方案 | V103 |
| `v_costing_merged_*` | 料号级数据 + 版本谓词 | V160/V161 |

#### 8.4.2 缓存层

| 缓存 | 范围 | 失效 |
|---|---|---|
| `ImplicitJoinRewriter.tableColumnsCache` | 进程内,ApplicationScoped | V112 后空集不缓存;DDL 后需重启 |
| `CachedSqlCompiler` | 进程内 | 重启失效 |
| `CachedPathParser` | 进程内 | 重启失效 |
| 数据源调用结果 | 进程内 + 同会话 5 分钟 | 参数变化 / TTL 失效 |
| `SystemConfiguration` ConcurrentHashMap | 进程内 | 配置修改 API 主动更新 |

### 8.5 非功能约束

| 指标 | 要求 |
|---|---|
| **API 响应时间** | P95 < 500ms |
| **并发用户数** | 100 |
| **数据库容量** | 10 万+ 报价单 |
| **可用性** | 99.5% SLA |
| **传输安全** | HTTPS |
| **密码存储** | bcrypt / argon2 加密 |
| **浏览器兼容** | Chrome 90+ / Edge 90+ / Firefox 90+ |
| **公式引擎单次求值** | < 50ms |
| **部署模式** | 单实例 JVM;水平扩展需先把进程内缓存改造为分布式 |
| **Session 持久化** | JVM 内存,重启失效 |

---

## 9. 项目演进史

精炼自旧 PRD 80+ 条变更日志,以**决策点**形式保留(便于追溯"为什么这么做")。完整日志见 `docs/PRD.md` 变更记录章节。

### 9.22 v4.4(2026-06-23)— 报价单产品卡片移除「料号版本」标签 + 卡内版本切换入口

报价单产品卡片上的「版本: vXXXX」标签及编辑页点击该标签打开 `PartVersionDrawer` 切换版本的入口一并移除（用户需求；仅去 UI，后端机制保留）。

| 决策 | 内容 |
|---|---|
| **移除范围** | 编辑页（`QuotationStep2`）+ 只读详情页（`ReadonlyProductCard`，报价单详情 & 核价单详情共用）的产品卡片均不再显示「版本: vXXXX」标签 |
| **编辑页切换入口一并去掉** | 编辑页该标签原本是版本切换入口（onClick 开 `PartVersionDrawer`），按需求连同 `PartVersionDrawer` 渲染/state/`onApplied` 处理一并清理（死代码移除）；报价单内不再能手动切换料号版本 |
| **后端机制完全保留** | `part_version_locked` 不动：导入/建单仍按 `mat_customer_part_mapping.current_version` 锁版本、BNF 路径求值仍按版本注入谓词；料号版本维护仍走独立 `/part-versions` 页 + 产品详情「料号版本」Tab（§5.2.5 不受影响） |
| **影响的旧描述** | §234「料号版本切换(V2.8 新)」标记废弃（仅指报价单卡内切换 UI；版本机制与独立维护页不变） |
| **背景** | 用户 2026-06-23 反馈：报价单产品卡片不需展示版本号、也不在卡内切版本 |

### 9.21 v4.3(2026-06-18)— 报价单草稿默认冻结(显式刷新仅值)

报价单草稿态卡片值的"打开自动重刷"改为默认冻结 + 显式刷新,详见 `docs/superpowers/specs/2026-06-18-草稿默认冻结-design.md`。

| 决策 | 内容 |
|---|---|
| **草稿默认冻结** | DRAFT 草稿首次 bake(加产品)后即冻结值+结构;**打开编辑向导不再自动按最新基础数据重刷/重建**,只读已冻快照 |
| **显式刷新仅值(R1)** | 保留 Step2 工具栏「刷新基础数据」按钮:用户显式点击时**只重算报价侧两份的值并保留编辑(按 `rowKeyFields` 行键对齐),不重建结构**(R1);要新结构须删行重加 / 重建报价单 |
| **冻结判据复用** | 复用 `QuotationLineItem.cardSnapshotAt`(非空 = 已首次冻结),**不新增数据库列**;`refreshQuoteCardValues` 加 `force` 短路,首次 bake 不被挡 |
| **提交后永久冻结** | `SUBMITTED` 起报价侧也永久冻死;核价从加产品起永久冻死(沿用既有边界) |
| **存量草稿迁移** | 一次性 `migrate-freeze-drafts` 端点(dryRun)对含 `#ERROR` 脏快照的存量草稿重烤清理;配合 Bug1 路径↔视图列名审计/软校验防回归 |
| **背景** | 落实 2026-06-08「严格冻结结论」的待立项项;原 2026-06-02"草稿打开重刷"为有意设计,本期收敛为"默认冻结 + 显式刷新" |

### 9.12 v3.5(2026-06-13)— 多 source 链式 SUM + KSUM 嵌套预聚合

连表公式（`cross_tab_ref`）求值能力扩展，详见 `docs/配置方法论-合并版.md §3.4.1`。

| 决策 | 内容 |
|---|---|
| **多 source 链式 SUM（v1）** | 一个 `SUM(...)` 内可引用多个非宿主 source 页签,各 source 与宿主 row_key_fields 两两可比(⊆/⊇)即合法成链;驱动=最细 source,更粗 source 按公共行键广播(0命中→项0 / 1命中→取值 / >1命中→报错改 KSUM);互不包含→报错 |
| **KSUM/KAVG/KMAX/KMIN/KCOUNT（v2 降维投影）** | 内层算子,对单个被聚合页签按宿主行键塌缩成标量,绕开"互不包含维度→笛卡尔积"限制;只能写在外层 SUM 等内 |
| **inner 白名单** | KSUM 内只允许 field(限被聚合页签列)/operator/number/bracket/global_variable(token type=path);拒宿主列/跨页签/上下文标量/嵌套 K*;前端序列化 + 后端 TokenMappabilityValidator 双端镜像拒绝 |
| **决策 K 空集分流** | KSUM/KCOUNT 空集→0(静默);KAVG/KMAX/KMIN 空集→null→整外层塌 0+⚠(无定义,不静默给 0) |
| **决策 J/M/I2/C3** | J=K 套 K 双端拒;M=顶层裸 KSUM 拒;I2=同页签既 KSUM 又裸引拒;C3=`K SUM` 不可拆写专门文案 |
| **C1 前后端求值对称** | 后端 sub 透传 componentSubtotals/quotationFields/productAttributes/previousRowSubtotal + sub.currentRowRaw 合并驱动行(对齐前端 mergedRow);共享夹具 cross-tab-cases.json 锁前后端一致 |
| **token 模型零迁移** | FormulaToken 纯新增 sources + projectToHostKey,无 DB 列、无 snapshot 迁移,缺省即旧 token |
| **Excel 模型 B 降级** | TabJoinPlanEvaluator 遇 KSUM/多 source token 显式抛错(非静默少算),改用页签连表渲染(模型 A) |
| **核价单不受影响** | 核价单走独立 8 指标引擎,不评估 cross_tab_ref token,本特性无核价侧改动 |

### 9.1 v1.0 → v1.5(2026-04-09 ~ 2026-04-10)

| 决策 | 内容 |
|---|---|
| 删除 AI 转化率预测 | 业务复杂度与 ROI 不匹配,聚焦核心报价流程 |
| 删除季度返利 | 删除 REBATE 类型与相关场景 |
| 新增模块二产品管理 | 支持手动 CRUD 和 Excel 导入(V1 不做外部系统同步) |
| 简化折扣策略 | 删除 CATEGORY_SPECIFIC 品类专属折扣,折扣体系简化为"批量折扣 + 基础折扣率"两级 |

### 9.2 v1.6 → v1.8(2026-04-10)

| 决策 | 内容 |
|---|---|
| 模板每产品唯一默认 | `is_default=true` 部分唯一索引 |
| Drools 设计提案 | (后续未实现,仅作设计参考 — 见 9.5) |
| 步骤三刷新规则 | 进入步骤三强制重算折扣;数据变更后手动折扣清空 |

### 9.3 v1.9 → v2.0(2026-04-11)

| 决策 | 内容 |
|---|---|
| 产品 + 工序组合维度 | `ProductTemplateBinding` 加 `process_ids_hash`,绑定到组合 |
| SMTP 配置走 properties | V1 不做 UI 配置 |
| 草稿并发策略 | 最后写入胜出,不做乐观锁 |
| 联系人快照时机 | DRAFT 实时更新,提交时固化 |
| 兜底审批人 | 多管理员时取 `created_at` 最早 |
| 产品同步推迟 V2 | V1 仅手动 CRUD + Excel 导入 |
| 定时任务清单 | 5 个定时任务 (报价过期 / 策略过期 / 催办 / token 清理 / 通知清理) |

### 9.4 v2.1 → v2.3(2026-04-16 ~ 2026-04-22)

| 决策 | 内容 |
|---|---|
| 主题切换功能 | 深色 / 浅色,localStorage 持久化 |
| 撤回功能 | 销售可撤回自己的 SUBMITTED 报价 |
| 审批规则类型修正 | FIXED / DYNAMIC,审批人和匹配值改下拉选择 |
| SYSTEM_ADMIN 可审批任意 | 兜底机制 |
| Excel 导入 v2 | 引入 `excel_view_config` JSONB + Handsontable |
| Excel 导入 v3 | 统一配置入口,从 6 步简化到 5 步(V5 流程) |

### 9.5 v2.4 → v2.7(2026-04-28)

| 决策 | 内容 |
|---|---|
| 撤销模板单 PUBLISHED 约束 | DROP V28 的 partial unique index,允许多 PUBLISHED 并存 |
| 产品分类接通字典 | 改为 `product_category` 表引用,旧 category 字段保留兼容 |
| **新增模块十六:系统配置中心** | 23 条配置项,REST API,V1 无 UI |
| **新增模块十七:并发锁机制** | 产品级悲观锁 + DDL 全局锁 |

### 9.6 v2.8(2026-05-12)

| 决策 | 内容 |
|---|---|
| **基础数据导入 V6 staging** | V5 六步向导废弃,引入 `import_session` + staging 暂存表 |
| 草稿态版本切换补齐快照 | PUT 端点扩展,切换 part_version 后同步调 `SnapshotCollectorService` 重算 `excel_view_snapshot` |

### 9.8 v3.1(2026-05-13)— Step3 优惠策略改造

| 决策 | 内容 |
|---|---|
| **Step3 改为行级表格** | 11 列(产品/年用量/优惠金额来源/可优惠金额基数/折扣/优惠金额/计价单位/币种/单价/优惠后单价/总金额)+ 底部金额汇总 |
| **年用量驱动阶梯折扣** | V1 硬编码 4 阶梯(<200=0 / 200-499=10 / 500-999=20 / ≥1000=30);引擎接口 `DiscountStrategy` 预留,V2 切 `PricingStrategyDiscount` 表驱动 |
| **整单总价改按行求和** | `quotation.total_amount = SUM(line_total_amount)`,`line_total_amount = annual_volume × line_final_price` |
| **行级折扣模型与旧整单字段并存** | `system_discount_rate / final_discount_rate` 保留兜底,V1 不写入(置 100);V2 PricingStrategy 上线再合并/废弃 |
| **V162 迁移** | `quotation_line_item` 加 9 列(annual_volume/discount_source/discount_base_amount/discount_rate_applied/line_discount_amount/line_unit_price/line_final_price/line_total_amount/discount_rule_code) + 部分索引 |
| **WYSIWYG 快照** | 6 个屏幕显示派生值全部 commit 入库,半年后审计可复现(AP-11) |

### 9.7 v3.0(2026-05-13)

| 决策 | 内容 |
|---|---|
| **重大对齐:新建 PRD-v3.md** | 以"实际跑起来的系统"为底稿重写 |
| **删除未落地设计** | Drools / 外部系统同步 / 品类专属折扣 / AI 预测 / V5 六步导入向导(从 PRD 正文移除) |
| **首次回写实装模块** | 核价单系统(三层架构)/ 全局变量层 / 料号版本管理 / 导入会话 V6 / 变量标签 / 比对标签 / Excel 模板独立配置 |
| **降级模块** | 产品工序选型配置 / 模板版本比对 不再作主线模块,合入产品管理 / 模板体系 子节 |
| **明确单实例约束** | 进程内缓存 + KieBase / Drools 字样移除,改为公式引擎缓存 |
| **旧 PRD 归档** | `docs/PRD.md` 保留为历史档案,不再维护 |

### 9.10 v3.3(2026-05-19)— 模板级覆盖 UI 化 + 选配 Tab LIST_FORMULA 支持

| 决策 | 内容 |
|---|---|
| **§5.x 模板级覆盖 (V200/V204)** | `template_component.fields_override` / `data_driver_path_override` 从 V200 起在 DB 存在但**前端无 UI** → 用户无法管理"同 component 在不同 Tab 字段集不同"的场景, 唯一可用路径是新建组件或写 SQL. V204 起加 PATCH endpoint + `OverridesDrawer` UI(模板编辑 Tab 内顶部「⚙ 编辑字段/Driver 覆盖」), 字段定义复用组件管理同款 `FieldConfigTable`(支持 LIST_FORMULA / FORMULA / BNF_PATH / GLOBAL_VARIABLE / FIXED_VALUE / INPUT). DATABASE_QUERY 类数据源仍需去组件管理配 |
| **TemplateDTO 暴露 override 字段** | `GET /templates/{id}` 和 `GET /templates/{tid}/components` 返回的 components[i] 现在含 `fieldsOverride` / `dataDriverPathOverride`, 前端能区分"使用默认 vs 已覆盖" |
| **Tab 头视觉反馈** | 有 override 的 Tab 自动显示橙色「覆盖」徽章; Tab 内顶部显示当前 effective driver path + 「已覆盖」标签 |
| **DRAFT-only** | 覆盖仅 DRAFT 模板可改, PUBLISHED 返 400. clone 升版本时 override **原样复制**(`TemplateService:387`), 这意味着改 component.fields 不会影响已 publish 模板的"已 override Tab", 必须先编辑 v2.0 草稿的 override 才能让组件变更生效 |
| **配置中心 seed: 3 个 config_template** | 为 "选配-材质 / 选配-元素含量 / 选配-组合工艺" 落地 LIST_FORMULA 字段提供 items 库: 材质(4 items) / 元素(5 items) / 组合工艺(4 items). 之前仅"工序"(5 items) 一个 config_template, 这一批补齐 |
| **AP-37 根因 6 续 — 详情/编辑 Tab 一致性** | ReadonlyProductCard / QuotationStep2 撤销 componentHasData 自动隐藏空 Tab 的逻辑, 改为按模板 snapshot 1:1 渲染 Tab + 空数据内部显示"暂无数据"占位. 解决"详情页 5 Tab vs 编辑页 6 Tab vs 模板 8 Tab" 不一致问题 |

### 9.9 v3.2(2026-05-14)— 模板客户绑定规则回填 + 选配产品呈现链路修复

| 决策 | 内容 |
|---|---|
| **§5.5.3 / §5.5.4 字段编辑约束明确化** | DRAFT 状态可改 `name / template_kind / category_id / customer_id / description / usage_note`;PUBLISHED 后全部冻结。**核心规则**:`customer_id`(适用客户)仅 DRAFT 状态可修改,PUBLISHED 后要改必须先「派生新草稿」 |
| **§3.2.1 模板预选规则回填** | 选客户 + 产品分类后按 `GET /templates/match-customer-quote` 过滤:① 客户专属(`customer_id = X AND status='PUBLISHED'`)② 通用兜底(`customer_id IS NULL`)③ 无匹配。DRAFT / ARCHIVED 不出现在 Step1 下拉 |
| **代码 vs PRD 同步** | 上述规则在 V73 起代码层已实现(`TemplateService.matchCustomerQuoteTemplate` + `TemplateConfigPanel.tsx` `disabled={!isDraft}`),本次仅回填 PRD 文档,代码无改动 |
| **选配产品 line_item 绑模板** | `ConfigureProductService.insertLineItem` 写入 `template_id` + 模板派生的默认 `product_attribute_values`,选配产品在报价单按 line_item.template_id 走标准卡片渲染链路(不再依赖 quotation.customer_template_id 全局兜底) |
| **`ConfigureProductResponse` DTO 扩展** | 返回完整 LineItem 形态(templateId + productAttributeValues + componentData=[] + customer 三字段 null),前端直接拼入 state 不需要再 getById |
| **报价单 Step1 视觉分卡** | 「选择模板」独立 Card 标题(从「产品分类 + 模板选择」改名);基础数据导入流程入口加 `<Alert success>` 提示"模板已在导入时选定" |
| **Step1 受控组件 Bug 修复** | `QuotationCreateForm` 的 value 从 `form.getFieldValue` 改为 React state `step1FormValue`,修复"用户输入报价单名称无反应"问题(预先存在的受控组件未触发重渲染) |

### 9.11 v3.4(2026-05-21)— 模板绑定全局变量 + 报价单引用数据 Tab

| 决策 | 内容 |
|---|---|
| **新增 §3.7** | 模板编辑端「关联全局变量」区块（多选 + 拖拽排序）；报价单详情端「引用数据」Tab（位于 info 之后、snapshot 之前） |
| **新增 `template_global_variable_binding` 表** | 存储模板 → GV 的 N:M 绑定关系，含 `display_order`；UNIQUE(template_id, gv_id)；`ON DELETE RESTRICT` 保护 GV 定义不被随意删除 |
| **扩展 `quotation_submission_snapshot`** | 新增 `bound_global_variables_snapshot JSONB` 列，在报价单提交时（`SnapshotCollectorService.collect()` 末尾）写入所有绑定 GV 全量行快照 |
| **DRAFT 实时 / 非 DRAFT 快照双路由** | DRAFT 报价单切 Tab 时调用 `/ref-data`（实时）；SUBMITTED+ 读 `/ref-data/snapshot`（不可变快照），防止审批中看到基础数据变更 |
| **隔离边界确认** | 不引入新 field_type；不复用 useDriverExpansions / ProductCard / enrichComponentData；不修改 component 表 / template.componentsSnapshot；GV 数据读取由独立 `GlobalVariableDataLoader` 服务承载 |
| **createNewDraft 绑定复制** | `TemplateService.createNewDraft()` 拷贝时原样复制 `template_global_variable_binding` 行，新草稿继承绑定关系，管理员可在 DRAFT 态调整 |
| **INACTIVE GV 策略** | 历史绑定保留（带「已停用」徽章），候选列表过滤 INACTIVE；防止管理员"意外失察"删除历史绑定数据 |
| **大表分页** | LOOKUP_TABLE 类 GV 行数 > 10 自动分页 pageSize=10；SCALAR 类 GV 用 Descriptions 渲染（v3.5 修订废除，统一 form 形式） |
| **PDF/Excel 导出本阶段不做** | 列入未来增量（§3.8 V2 规划），本次范围仅 web 端 Tab 展示 |

### 9.18 v4.0(2026-05-27)— 主数据维护 Hub 工序 Tab V6 化 + BOM Tab 新建 + V44 ProcessManagement 退出

| 改动 | 内容 |
|---|---|
| **工序 Tab** | 改为只读查看 V6 `process_master`；CRUD 完全退出 UI 入口（删除 ProcessManagement/RegularProcessTab/CompositeProcessTab + EditDrawer 共 5 个旧 .tsx + router `/config/processes` 路由）；后端 V44 ProcessResource/Service 暂保留观察一轮，避免连锁影响 |
| **BOM Tab** | 新建只读查询页（原 Tab 空 placeholder）：客户编号下拉（必选）+ 料号下拉（可搜索 + 客户切换清空 + ≤500 截断提示）+ 系统类型 Radio（全部/报价/核价/共用）+ 查询按钮；列表 11 主列 + 详情 Drawer 5 组（维度键/项次工序/用量损耗/选项追溯/审计）展示完整 49 字段 |
| **systemType 宽松语义** | `QUOTE → system_type IN ('QUOTE','BOTH')`、`PRICING → IN ('PRICING','BOTH')`、`BOTH` 精确匹配；BOTH 行同时归属报价和核价视图 |
| **材质 / 料号 / 数据模板 Tab** | 不动；材质沿用 V44 `material_recipe`（用户明确「使用之前的」），通过 `material_recipe.part_no` 与 V6 `material_master` 关联 |
| **新增 API** | `GET /api/cpq/v6/process-master?page&size&keyword` + `GET /api/cpq/v6/material-bom-items` + `/customer-nos` + `/material-nos`；权限 SALES_REP/SALES_MANAGER/PRICING_MANAGER/SYSTEM_ADMIN |
| **错误码** | `MISSING_CUSTOMER_NO`（400 BOM 查询/material-nos 漏传 customerNo）/ `INVALID_SYSTEM_TYPE`（400）/ `INVALID_PAGE_SIZE`（400 size>200）|
| **缓存** | `findDistinctCustomerNos` 用 ConcurrentHashMap + volatile lastFetchedAt 5min TTL（项目未引入 quarkus-cache 扩展） |
| **AP-53 纪律** | 全部 FROM V6 表（material_bom_item / process_master / material_master）；零 V44 老表引用 |
| **测试** | Playwright E2E `cpq-frontend/e2e/master-data-v6-tabs.spec.ts` 7 passed 覆盖工序 Tab 5 AC + BOM Tab 10 AC + 老路由 404 + 材质/料号 Tab 回归 |
| **路径迁移过程踩 3 个 bug** | B3（前端 ApiResponse 未解包导致整页崩溃；查项目其他 service 引入 `unwrap` 函数标准化）、B1（前端 systemType 枚举 `QUOTATION/COSTING/COMMON` ≠ 后端 `QUOTE/PRICING/BOTH`）、B2（同 B1 字典键不匹配致 Tag 不显色）。教训：前端 service 必须 grep 项目惯用 unwrap 模式；枚举值必须从后端实际 entity/DTO 拷贝，禁脑补 |
| **关联文档** | `docs/列表操作规范.md` / `docs/方案制定前必读.md` §V6 / `docs/RECORD.md` 当日 4 条记录 |

### 9.17 v3.9(2026-05-27)— buildEvalKey 4 段协议对齐 + v1.30 process_info 视图聚合修复

修复 v3.7 (V249/V250) 引入 template_sql_view 后遗留的两个 bug：

| 修复 | 内容 |
|---|---|
| **buildEvalKey 4 段协议对齐**（前端） | 后端 `FormulaEvaluateResource.batchEvaluate` 在 V249 时把 `r.key` 升级为 4 段 `expr:customerId:partNo:templateId`，但前端 `formulaService.ts:buildEvalKey` 仍是 3 段；导致 `LinkedExcelView.tsx` 用 3 段 reqKey 反查 4 段 itemByKey 永远 miss，pathCache 强制写 null，V111 noCostingData=true → 整行 13 列全 `—`。修复：buildEvalKey 加可选第 4 参数 templateId（默认 "_"），LinkedExcelView 透传 templateId（向后兼容老调用方）|
| **v1.30 process_info 视图聚合修复**（V260+V261） | "选配产品标准报价模板-组合产品 v1.30" Excel 视图 [H]工序数 列显示 "1（共372项）"。原 SQL `UNION ALL ... FROM mat_process` 返多行；改为 `COUNT(DISTINCT seq_no) GROUP BY hf_part_no` 单值聚合。V260 改源表 `template_sql_view.sql_template`，V261 用 `jsonb_set` 同步更新 `template.template_sql_views_snapshot.process_info`（PUBLISHED 模板渲染走 snapshot fallback 源表）|
| **PUBLISHED 模板 SQL 视图改动纪律** | 已发布模板的 SQL 视图改动必须双更新：`template_sql_view` 源表 + `template.template_sql_views_snapshot` JSONB。只改源表渲染不生效。立项加入「方案制定前必读」改动决策树 |
| **AP-53 负债保留** | process_info 视图仍 FROM V44 `mat_process`（V6 `material_bom_item.operation_no` 数据未到位）。BasicDataImportServiceV5 V6 backfill PR 落地后此视图应改 `FROM material_bom_item GROUP BY material_no` |
| **E2E 验证** | `cpq-frontend/e2e/quot-excel-view-key-protocol.spec.ts` 1 passed in 13.8s；partNo 3120012574/5 报价单 Excel 视图 28 单元中 22 个有数据 / 6 个 `—`（仅 product_type / specification / config_fingerprint 等字段语义 NULL）；[H]工序数 = 5 / 3（int 标量，断言拒 "共N项" + 要求纯整数） |

### 9.16 v3.8(2026-05-27)— 核价标准模板 v5.0 v1.2 全量 V6 迁移（V253~V259，未发布）

- 2026-05-27 V253~V259（v1.2 DRAFT 创建，未发布）— 核价标准模板 v5.0 v1.2 全量 V6 迁移 schema-only PR：v1.2 复制 20 -V12 组件 + 20 component_sql_view + 7 template_sql_view 替代 v_costing_summary_full / v_c_summary_agg；excel_view_config 36 列 path 全部 $<view>.<col> 化；V76 costing_part_* 关键词进 BnfPathLinter / SqlViewValidator 黑名单。fee_config / plating_scheme / unit_price 加 6 列待 import PR backfill；数值标杆暂不验收。

### 9.16 v3.8(2026-05-31)— 组件目录 导入/导出 立项（设计见 §5.4.6）

| 决策 | 内容 |
|---|---|
| 导出范围 | 本期**只导当前目录直属组件**,不递归子目录;导入平铺到目标目录 |
| code 冲突 | 默认**重命名(加后缀 `__impN`)**;另提供 跳过 / 中止;**任何策略都不覆盖现有组件** |
| 依赖缺失 | 数据源/全局变量缺失 → 预览**红色报出 + 默认阻止提交**,可显式"仍然导入" |
| 隔离保证 | 导出只读;导入只 INSERT 新 UUID;不绑模板(refreshSnapshots no-op);sql_view_name 组件内唯一无需改写;单事务;**无 Flyway/schema 变更** |
| 不进 bundle | 模板绑定 / 快照 / 报价数据(杜绝跨业务耦合) |
| 分期 | P1 导出 → P2 导入预览+依赖校验 → P3 导入提交+冲突策略+结果报告 |

### 9.15 v3.7(2026-05-26)— 模板独立 SQL 视图（template_sql_view + template.template_sql_views_snapshot）

2026-05-26 V249 / V250 — 模板独立 SQL 视图。模板的 Excel 视图列可引用本模板 SQL 视图 `$view.col`，与组件视图完全隔离；发布时 snapshot 冻结。

| 决策 | 内容 |
|---|---|
| **新表 template_sql_view** | FK → `template.id`，与 `component_sql_view` 同构（name / description / sql_template / status / declared_columns JSONB），scope 只允许 LOCAL |
| **新字段 template.template_sql_views_snapshot** | JSONB，模板发布时冻结本模板所有 ACTIVE 视图定义；保证历史报价可复现 |
| **OwnerType.TEMPLATE** | `SqlViewRuntimeContext.OwnerType` 加 TEMPLATE 枚举，与 COMPONENT 互斥；Snapshot 统一复用 `templateId` 字段，不再引入 `costingTemplateId` 平行字段 |
| **SqlViewExecutor owner-aware 路由** | `$view.col` 按 ownerType 路由：COMPONENT → component_sql_view，TEMPLATE → template_sql_view；`$$code.view.col` 仅 COMPONENT 上下文允许，TEMPLATE 上下文强抛 BusinessException |
| **发布时双校验** | `TemplateService.publish` 先扫 excel_view_config 中的 `$$` 跨引用（强阻断），再拉 ACTIVE template_sql_view 构造 snapshot 写入 |
| **createNewDraft deep-copy** | 派生新草稿时 `TemplateSqlViewService.deepCopySqlViews` 把所有 template_sql_view 行复制到新草稿，版本间独立 |
| **前端 TemplateConfiguration 第三 Tab** | TemplateSqlViewsTab 提供 CRUD + dry-run；ExcelViewConfigTab VARIABLE 列编辑加「SQL 视图」按钮弹 PathPickerDrawer（ownerContext=TEMPLATE） |
| **PathPickerDrawer ownerContext=TEMPLATE** | 只显示本模板 SQL 视图（不显示 GLOBAL 区域）；`$$` 路径强校验阻断；旧调用方无 ownerContext 参数时行为不变 |
| **EvaluateRequest 携带 templateId** | LinkedExcelView batchEvaluate 透传 templateId；FormulaEvaluateResource 注入 SqlViewRuntimeContext.setNestedTemplate；缓存 key 含 templateId 维度 |
| **路径形态隔离规则** | 组件上下文：`$view.col` 查 component_sql_view；模板上下文：`$view.col` 查 template_sql_view；跨 owner 引用一律拒绝 |
| **关联文档** | `docs/archive/方案-Excel模板BNF迁移至组件SQL视图.md` v2（权威设计文档）；`docs/反模式.md` AP-53（V44 老表禁用延伸）；`docs/Excel模板配置指南.md` §四 C |

### 9.14 v3.6(2026-05-25)— 组件级数据源 SQL 方案立项

| 决策 | 内容 |
|---|---|
| **基础数据配置职责拆分** | 「基础数据配置」回归最初职责（仅 Excel sheet → 物理表导入路由）；BNF 元数据改由 `bnf_table_meta` 启动时自动同步 `information_schema.tables/views`；PathPicker 新增"information_schema 视觉模式" Tab，DBA Flyway 加视图后自动可见，不再要业务侧补登记 |
| **新增 component_sql_view 表** | 组件管理新增"SQL 视图"配置 Tab —— 用户自写 SELECT（含 UNION / JOIN / 命名占位符），给视图取名 `<sql_view_name>`，scope=COMPONENT(本组件) / GLOBAL(可跨组件 BNF 引用)；保存时 EXPLAIN dry-run 拒绝 DDL/DML + 拒绝 `:hfPartNo` 标量占位符 + 自动提取 declared_columns |
| **BNF path 新增 `$` / `$$` 前缀引用语法** | BNF 解析层（BnfPathResolver.resolveSheetExpression）识别两种前缀：① `$<sql_view_name>` → 本组件 SQL 视图；② `$$<componentCode>.<sql_view_name>` → 跨组件 GLOBAL 引用。**纯解析层扩展，渲染层 / cache key / ComponentDriverService 三分支 / ComponentCell / useDriverExpansions 全部不动** |
| **双层冻结策略** | 模板 DRAFT→PUBLISHED 时冻结至 `template.sql_views_snapshot` JSONB（与 components_snapshot 同事务）；报价单 DRAFT→SUBMITTED 时冻结至 `quotation_component_sql_snapshot` 表（与 *_snapshot 列同事务）。lookupSqlView 优先级：报价单 snapshot > 模板 snapshot > 实时 component_sql_view |
| **N+1 与 BNF 现有 batch 机制自动融合** | inline subquery 包装后仍受外层 `WHERE inner_q.hf_part_no = ANY(:hfPartNos)` batch filter，一次 batch query 拿全部 partNo 数据；用户 SQL 内自己写 UNION 处理 SIMPLE/COMPOSITE 双场景，与 §3.6 红线"SIMPLE/COMPOSITE 配置层统一"一致 |
| **§10.1.2 禁双轨红线不撞** | 组件 SQL 模式定位为"BNF path 数据源的层级扩展"（不是新渲染通路）；字段渲染单通路仍是 BNF path；三个核心选配组件（e42185ec/dae85db8/0a436b6c）继续走 v_composite_child_* 物理视图，**不回溯改造** |
| **AP-44 矩阵 17 → 18 处** | 新增 #16 BnfPathResolver 解析层检查点，**不影响 #1~#15 字段类型/缓存/渲染矩阵**（纯解析层扩展） |
| **关联文档** | `docs/archive/组件级数据源SQL方案.md`（完整方案）/ `docs/三大核心模块基线.md §2.3 / §3.2`（增量段落）/ `docs/组件管理字段配置指南.md §2.3 / §11`（$ 引用语法）/ `docs/反模式.md AP-44`（矩阵 18 处）|
| **阶段迁移** | 阶段 1 功能加法 0 破坏；阶段 2 基础数据配置职责回归；阶段 3 已发布模板 BNF path snapshot 永久稳定 |

### 9.13 v3.5.1(2026-05-21)— 引用数据 Tab Descriptions 微调（column 3 + value 拼 unit）

| 决策 | 内容 |
|---|---|
| **column 从 2 调整为 3** | 用户反馈 column=2 一行 2 个 K:V 过宽，改为 column=3 一行 3 个 K:V 更紧凑 |
| **value 拼接 unit** | 单位（如 `CNY`）原显示在 Card 右上角 `extra`，现改为在每个 `Descriptions.Item` 的 value 后空格拼接（如 `400 CNY`），方便每行直接对照看出单位，无需返回卡片头核对 |
| **Card extra 移除** | 同步删除 Card 的 `extra={unit}` props（避免重复显示） |

### 9.12 v3.5(2026-05-21)— 引用数据 Tab 渲染统一为 Descriptions form 形式

| 决策 | 内容 |
|---|---|
| **LOOKUP_TABLE 渲染从 Table 切换为 Descriptions** | 用户反馈 v3.4 Table+表头形式与"报价单信息→基本信息"视觉风格不一致，改为 `Descriptions column={2} bordered size="small"`（后 v3.5.1 改为 column=3），每行一个 `Descriptions.Item`：label = key 列值（如 Ag/Cu/Sn，灰底），value = value_column 列值（400/100/255，白底）。**列头名称（key/value_number）不再显示**，整体视觉与「基本信息」卡片对齐 |
| **分页规则废除** | v3.4 的"LOOKUP_TABLE 行数 > 10 自动 pageSize=10 分页"规则**完全删除**；统一用 `max-height: 600px + overflow: auto` 滚动容器保护大表（如 MAT_PRICE 上千行场景） |
| **SCALAR 渲染不变** | 仍是 `Descriptions column={2} bordered size="small"`，与 LOOKUP_TABLE 用同一套组件路径，代码上 SCALAR/LOOKUP_TABLE 分支保留（语义不同：SCALAR 每个 Item 的 label 来自 columns[i]，LOOKUP_TABLE 每个 Item 的 label 来自 rows[i][keyCol]） |
| **AC6/AC7 同步更新** | AC6 改为"两类 GV 统一 Descriptions 渲染"+ 详细字段对应；AC7 改为"大表 max-height 滚动而非分页"|
| **核心基线 §5.5 / 反模式 AP-49 不受影响** | 本次仅前端渲染层修订，不动 `GlobalVariableDataLoader` / 公式引擎 / 透传链路；引用数据 Tab 仍走 `_globalPathCache` 之外的独立请求路径（`/ref-data` `/ref-data/snapshot`），与产品卡片求值无耦合 |

### 9.19 v4.1(2026-06-08)— 报价单页签手动新增行 Phase 1

| 决策 | 内容 |
|---|---|
| **§3.2.2 新增"手动新增行"** | 报价单产品卡片每个组件 Tab 底部"+ 添加行"追加手动行(`_origin='manual'`),除公式列外全空白由用户自填;计入小计、经 `row_data` 持久化、详情只读态一致显示;核价 Tab 不提供入口 |
| **纯前端实现(后端零改动)** | 手动行存 `comp.rows` 末尾,经 `snapshotRows` 原样序列化进 `row_data` JSONB 往返;后端 `refreshQuoteCardValues` 只写 `quoteCardValues` 不碰 `row_data`,故手动行安全 |
| **`splitRows`/`rowAt` helper 统一行迭代** | 新建 `manualRows.ts`,把 5 处"按 `exp.rowCount` 截断"的行迭代(buildCrossTabRows / computeTabSubtotal / 编辑态渲染 / snapshotRows / ReadonlyProductCard)统一改为"driver 行 + 手动行拼接";`fillFixedDefaults` 短路手动行不自动填 FIXED_VALUE 默认 |
| **AP-54 写回下标映射** | 渲染用拼接序、写回用 `comp.rows` 原集合,写路径下标按 `comp.rows.indexOf(ra.row)` 真实下标映射,避免写错位 |
| **AP-31 Phase1 — prune 保留手动行** | driver 页签 prune useEffect 原 `comp.rows.length>exp.rowCount` 整段 slice 会截掉手动行;改为只在 `driverRows.length>exp.rowCount` 时裁、且只裁非手动 driver 行 |
| **AP-37 续 — 同 cid 多实例合并保手动行** | "材质"+"选配-材质"共享同一 componentId 时,合并原按 cid 单值索引致后者(无手动行)覆盖前者(有手动行);改 `(cid,tabName)` 精确匹配 + 同 cid 队列 FIFO |
| **验证** | `manualRows.test.ts` 4/4;E2E `quote-manual-row.spec.ts` 5/5(添加 N+1 / 小计 / 持久化 / 详情 / 加载中=0);回归 `quotation-flow.spec.ts` passed。`composite-product-flow.spec.ts` 唯一失败为 RECORD:296 预存已知缺陷(元素模板 unit_weight 列不存在,用户确认停用不修),与本次无关 |
| **Phase 2(待立项)** | driver 行删除 + `deleted_driver_keys` + driverRow 内容指纹匹配 + 重开不复活 |

### 9.20 v4.2(2026-06-11)— 页签连表公式 行键宿主分组(包含关系)重设计 + 试算=渲染（批3/需求1）

| 决策 | 内容 |
|---|---|
| **结果粒度=宿主组件行键** | NORMAL/SUBTOTAL 页签连表公式逐行按宿主组件行键分组;被引用 source 页签按"行键集合包含(⊆/⊇,顺序无关)"对齐宿主——**不笛卡尔**(多细 source 各自独立聚合) |
| **三态引用(spec §58 模型)** | 粗/同级 source(键⊆宿主)→广播 agg=NONE 裸明细可用;**细 source(键⊋宿主)→强制 `FN()` 单列聚合**(SUM/AVG/MAX/MIN/COUNT,默认SUM);不可比/空行键 source→置灰,仅整页签小计 `[页签(总计)]` 可用 |
| **FN 函数语法(序列化)** | `buildMatch` 改公共行键字段名交集配对(非位置zip);lexer+状态机支持 `FN([alias.field])`→cross_tab_ref agg=FN,**单列收口**(FN内运算符/多引用报错,复合留二期);回显归一 `FN([a.f])`(SUM 不再 `(总计)`,解析仍兼容旧串) |
| **mappability 命门** | 前端 `checkMappable`+后端 `TokenMappabilityValidator` 同改"**任何 cross_tab_ref 且 match 为空即拒**"(作废旧"≥2 NONE 拒");`evalCrossTab` 求值逻辑不改,加防御"空 match→ERR" |
| **置灰基准换宿主可比** | `TabFieldMatrix` 废 `parseActiveRowKeySig`("首明细令牌锁签名"旧机制),改 `tabComparable(宿主行键, source行键)` 集合包含;prop 链转发 `selfRowKeyFields`;细 source 明细 chip 弹 FN 下拉录入 |
| **🔴 试算=渲染(同引擎)** | NORMAL/SUBTOTAL 试算改走 token 渲染引擎(新端点 `/components/{id}/dry-run-token` + 复用 `assembleTabsWithFormulaResults`,草稿公式+草稿行键双注入),逐行小表展示,与真实卡片渲染逐行一致(命门0 对拍);EXCEL 仍走 `TabJoinPlanEvaluator` 单值不变 |
| **存量不管** | 旧 token 落库 match[] 引擎只读不重跑 `buildMatch`,已发布 snapshot 求值不变;同序同集等价、乱序同集存量按"不回溯"处理;无迁移脚本 |
| **验证** | 前端 173 测试(序列化/矩阵/引擎)+ tsc 0;后端 78 测试(FormulaCalculator*/CardSnapshot*/validator);cross-tab-cases.json 前后端同夹具锁引擎一致;命门0 `CardSnapshotDryRunParityTest` 螺丝行 80 逐行对拍绿 |
| **遗留 follow-up** | ① v6-N 草稿改行键差异化未单独断言(机制走通,受 @TestTransaction/readonly 约束;driver-expand 实路由 RefreshCardSnapshotTest 覆盖);② 同 cid 多实例 injectDraftFormula 取首个,sortOrder 精确定位留 v6-O |

### 9.21 v4.3(2026-06-29)— 核价管理改为「财务核价工作台」(第一期)

> 设计 `docs/superpowers/specs/2026-06-29-核价管理财务核价工作台-design.md`(v3 分期);计划 `docs/superpowers/plans/2026-06-29-核价管理财务核价工作台-第一期.md`。

| 决策 | 内容 |
|---|---|
| **定位** | 「核价管理」菜单(原手动料号级核价单 CostingSummary,**废弃**)改为**财务核价审批节点**:报价单提交审批→进财务角色队列→财务在列表/只读核价工作台复核后**整单**核价通过/驳回 |
| **审批流** | 提交审批(SUBMITTED=待核价)→核价审批(财务)→已审核(APPROVED);驳回→**COSTING_REJECTED**(新增状态);〔流程审批·后期接流程引擎〕;**财务=当前唯一审批节点** |
| **角色队列** | 任一 财务/系统管理员 可操作待核价单,**不用 assignedApproverId 指派**;`PRICING_MANAGER` 显示名→"财务";核价管理菜单去 SALES_MANAGER(仅财务+管理员) |
| **撤回** | 统一为**销售一步直接撤回**(废弃两步申请-审批流程):SUBMITTED/COSTING_REJECTED/APPROVED→DRAFT + 解冻(清提交快照/SQL 闭包);排除 SENT/ACCEPTED(涉客户累计金额,放后续) |
| **工作台** | 独立路由 `/quotations/:id/costing-review`,**复用详情页只读两级视图**(报价/核价/比对×卡片/Excel,抽共享 ProductDetailViews 反 AP-50);财务+待核价 时叠加顶部通过/驳回;列表亦可通过/批量驳回 |
| **货币** | 列默认 **CNY**(系统暂无报价单级币种;币种切换功能=后续) |
| **第二期(未做)** | 财务**切料号版本重算子料号调价**:依赖"版本感知 BOM 闭包"前置工程(当前 BomClosureService 硬编码 is_current 不吃版本、核价卡片 expand 传 partVersion=null);核价单独立快照 + revision + 覆盖读取层下沉(导出/列表/比对/total 多路绕 DTO) |
| **验证** | 后端 test-compile 0 + 核价 3 测试全绿(submit建单/角色队列通过驳回/撤回解冻);前端 tsc 0;合并 master 后 E2E quotation-detail-readonly-views 2 passed(AP-50 抽取无回归)+ e2e-withdraw-02 一步撤回 2 passed |

### 9.22 v4.4(2026-06-29)— 核价单表与报价单/核价单状态机重构(第一期·续)

> 设计 `docs/superpowers/specs/2026-06-29-核价单表与报价单核价单状态机重构-design.md`(v3,两轮 cpq-architect 评审定稿);计划 `docs/superpowers/plans/2026-06-29-核价单表与状态机重构-第一期.md`。承接 §9.21,把精简 costing_order 升级为完整核价单实体。

| 决策 | 内容 |
|---|---|
| **核价单实体** | costing_order 升级(V305):核价单号 `HJ-yyyyMMdd-NNNN`(序列) + 显式 status + 驳回原因 + frozen_dto + 审核人 + 修改时间;**按提交累积**(每次提交新建一条,旧"已驳回/已撤回"连原因+冻结数据永久留存);**部分唯一索引 uq_co_active 保证至多一条进行中**(防并发双 PENDING,撞→409) |
| **状态机(两侧可分叉)** | 报价单 SUBMITTED=待核价 / APPROVED=已审核 / COSTING_REJECTED=已驳回 / REJECTED=客户已拒绝(消歧);核价单 PENDING/APPROVED/REJECTED/WITHDRAWN。提交→核价单待核价;通过/驳回→两侧同步;**销售点编辑被驳回单→报价单转草稿、核价单仍已驳回**(beginEdit);**撤回→报价单草稿、核价单已撤回**(三态可撤回) |
| **真冻结(务实版)** | 提交时把整份报价单 DTO(含 enrich 后结构 + gvDefs)冻进核价单,工作台只读冻结副本渲染(getById coid);核价卡片/Excel 本就零计算快照冻结,故只需冻结构 + gvDefs,**不做独立 path-cache 冻结**(残留 live 侧信道仅模板/主数据 republish 时漂,入 BL-0015) |
| **列表增强** | 核价管理列表:全状态过滤 + 报价单号搜索 + 退回原因/修改时间列;身份键 costingOrderId(累积多条不撞);英文码状态前端单点映射中文 |
| **退役老审批** | 移除详情页 + 列表页销售经理「审批通过/退回」+「待我审批」tab,财务成为唯一审批节点 |
| **验证** | 后端 ~30 单测全绿(状态机/累积/冻结/列表/409,真实库);前端 tsc 0;V305 success=t;合并 master 后 quotation-flow 协议回归 render 检查「加载中=0」全过(其 3 个失败经 git diff 证在未改文件 QuotationWizard/QuotationStep2,系并发会话既存,与本改动无关) |

### 9.8 关键设计决策追溯

#### Q1:为何不引入 Drools?

- v1.8 系列详细设计过 Drools 7.74.x 动态 DRL 加载方案
- 实际进入开发后评估:折扣计算规则数少(每客户 1~5 条)+ 审批路由规则数少(全系统 < 50 条),纯 Java service 复杂度足以承担
- Drools 引入会增加部署复杂度(`--add-opens` 参数)、单实例约束(KieBase 分布式难)、调试难度
- 最终决策:**降级为纯 Java**,接口契约不变,业务逻辑等价

#### Q2:为何核价单与报价单是软关联,而非父子?

- 核价单(成本)是定价经理的工作产物,报价单(售价)是销售的工作产物,**角色边界清晰**
- 同一料号在不同客户报价中可能引用同一核价(共享底价)
- 删核价单不影响报价单(置 NULL),反之亦然
- 软关联避免了"删除报价单连带删除核价"的复杂级联

#### Q3:为何 override 不写回基础数据?

- `PRICING_MANAGER` 做 what-if 试算高频,直接改 L0 数据会污染团队共享数据
- 全局调整有独立入口(L0 配置),走正式版本管理流
- override 在 PUBLISHED 后随 summary 一起冻结,长期可追溯

#### Q4:为何视图 DDL 后必须重启 Quarkus?

- `ImplicitJoinRewriter.tableColumnsCache` / `CachedSqlCompiler` / `CachedPathParser` 都是 ApplicationScoped 进程级缓存
- 视图被 CASCADE 临时删除瞬间若有请求触发 `getColumns` → 缓存空集 → 永久残留 → 后续路径求值不再注入 hf_part_no 谓词 → 视图返全表 N 行 → UI 出现「首值(共 N 项)」错乱
- V112 已修:空集不缓存,下次自愈;但已残留旧 JVM 进程缓存仍需重启清空

---

## 10. 附录

### 10.1 术语表

| 术语 | 英文 / 字段名 | 含义 |
|---|---|---|
| 报价单 | Quotation | 销售对客户出价的"价格清单",5 步向导完成 |
| 核价单 | Costing Summary | 内部成本计算单,7 项 metric |
| 组件 | Component | 可复用的字段 + 公式单元 |
| 模板 | Template | 组件组合 + 产品属性 + Excel 列结构 |
| Excel 模板 | costing_template / template_kind=EXCEL | Excel 视图列定义 |
| 全局变量 | GlobalVariable | L1 注册层,给 L0 数据起业务名 |
| 比对标签 | comparison_tag | Excel 列 tag,比对视图分组用 |
| 变量标签 | variable_label | 模板列引用变量的可读标签 |
| lineItem | quotation_line_item | 报价 / 核价单中的一条产品行 |
| component data | quotation_line_component_data | lineItem 上挂载的组件实例数据 |
| driver expansion | — | 组件按 `data_driver_path` 自动展开 N 行 |
| BNF 路径 | `<sheet>[谓词].<field>` | 数据取值语法 |
| 隐式 JOIN | ImplicitJoinRewriter | 后端自动按 hf_part_no / customer_id 注入谓词 |
| 快照 | `*_snapshot` | 报价提交后冻结的字段,确保历史可复现 |
| override | costing_summary_override | 核价单的用户级覆盖,不写回基础数据 |
| metric | costing_summary_result 一行 | 一项成本指标(如 MATERIAL_COST) |
| PIVOT 视图 | v_costing_summary_full | N 行 metric → 1 行 9 列 |
| template_kind | template 表 discriminator | QUOTATION / COSTING / EXCEL |
| import session | import_session | V6 导入会话,staging 阶段持久化 |
| part version | part_version | 同 hf_part_no 的多规格版本 |
| HF 料号 | hf_part_no | HF 公司内部料号 |
| 客户料号 | customer_part_no | 客户侧料号,需映射到 HF 料号 |

### 10.2 BNF 路径文法(完整)

```
path        := sheet predicate_opt '.' field
sheet       := identifier
predicate_opt := '' | '[' predicate ']'
predicate   := condition (logical_op condition)*
condition   := column comparison_op literal
comparison_op := '=' | '!=' | '>' | '<' | '>=' | '<='
logical_op  := 'AND' | 'OR'
field       := identifier
column      := identifier
identifier  := [a-zA-Z_][a-zA-Z0-9_]*
literal     := string_literal | number_literal
string_literal := "'" [^']* "'"
number_literal := -?\d+(\.\d+)?
```

**注意事项**:

- 谓词中字符串字面量必须用单引号:`[hf_part_no='3100080003']`
- 数值字面量不加引号:`[seq_no=1]`
- 复合条件:`[from_currency='CNY' AND to_currency='USD']`
- 无谓词时由 `ImplicitJoinRewriter` 注入

### 10.3 公式 token 完整清单

| token type | 用途 | 必填字段 | 示例 |
|---|---|---|---|
| `field` | 引用同行其他字段 | `value` | `{type:'field', value:'电镀面积(cm²)'}` |
| `operator` | 运算符 | `value` | `{type:'operator', value:'*', label:'×'}` |
| `bracket_open` / `bracket_close` | 括号 | — | `{type:'bracket_open', value:'('}` |
| `number` | 数字常量 | `value` | `{type:'number', value:'100000'}` |
| `component_subtotal` | 跨组件小计 | `component_code` | `{type:'component_subtotal', component_code:'COMP-V4-PLATING-SCHEME'}` |
| `product_attribute` | 产品属性 | `attribute_name` | `{type:'product_attribute', attribute_name:'材料'}` |
| `quotation_field` | 报价单级字段(税率等) | `value` | `{type:'quotation_field', value:'tax_rate'}` |
| `path` | BNF 路径直接取 | `path` | `{type:'path', path:'mat_part.unit_weight'}` |
| `global_variable` | 全局变量 | `code`, `key_values` 或 `key_field_refs` | `{type:'global_variable', code:'EXCHANGE_RATE', key_values:{from_currency:'CNY', to_currency:'USD'}, path:'...'}` |

**重要**:`global_variable` token 创建时由前端编译产物 `path` 字段(BNF),运行时 evaluator 走 path resolver。不要手写 path。

### 10.4 反模式速查 AP-22(多行数据展示族)

完整反模式见 `docs/反模式.md`。**AP-22** 是配置者最易踩的"X (共 N 项)"问题族,4 类共因:

| 类别 | 名字 | 修复 V_NN | 一句话 |
|---|---|---|---|
| **A** | ImplicitJoinRewriter 缓存空集 | V112 | 视图 DDL 后 cache 残留 → 重启清缓存 + 空集不缓存 |
| **B** | ProductCard fallback 漏读 row | V118 | BASIC_DATA cell 跳过 row[fieldKey] 直接走 globalPathCache → 加 row 优先级 |
| **C** | 视图 COALESCE(0) 遮蔽 NULL | V111 | "未配置" 跟 "真 0" 混淆 → 视图改 NULL 传递,前端识别整行 NULL 行清空 |
| **D** | comparison_tag 未注册 | V114 | Excel 列 tag code 不在表 → tagLabel 显示 raw code,全归"其他"组 → 注册 |

#### 症状 → 根因 5 秒决策树

```
看到 "(共N项)" 后缀
   ↓
是 Excel 视图列? ─── 是 ──→ 类别 A: SQL 隐式 JOIN 失效
                          (1) SQL 验证 SELECT col FROM view WHERE hf_part_no='X' 是否单行
                          (2) 单行 → 重启 Quarkus 清进程缓存
                          (3) 多行 → 视图本身缺收窄键,加 PIVOT 包宽表
   ↓
是产品卡片视图 cell? ── 是 ──→ 类别 B: 渲染层 fallback 漏读 row
                              F12 看 comp.rows.length > 1 + row[fieldKey] 是否标量
                              是 → BASIC_DATA cell 缺优先级 2 → 立刻补
                              否 → 进入类别 A 排查

显示数值都是 0 但语义"无数据"? ──→ 类别 C: 视图 COALESCE(0) 遮蔽
                                  去掉 COALESCE,让 NULL 传递

比对视图 tag 显示 raw code 全归"其他"组? ──→ 类别 D: comparison_tag 表 INSERT 缺失
                                            V_NN 注册 (code, label, group_name, sort_order)
```

### 10.5 已知限制 V1 → V2 Roadmap

| # | V1 限制 | V2 计划 | 优先级 |
|---|---|---|---|
| 1 | 报价单无 PDF/Excel 导出 | Quarkus Qute + Apache POI 出 PDF/Excel | 高 |
| 2 | 核价单 compute() 商务加价 6 项未实现 | 升级为可配置公式 | 高 |
| 3 | 公式不支持字符串字面量 / Excel 函数 | 评估扩 JEXL | 中 |
| 4 | LinkedExcelView 每个 lineItem 一行 | 同料号多 summary 多行展开 | 中 |
| 5 | 同 linked_template_id 只能 1 份 PUBLISHED 默认 Excel 模板 | 永久(设计取舍) | — |
| 6 | 核价单批量比较跨 summary 未实现 | 同料号不同版本成本差异 | 中 |
| 7 | Excel 视图无导出 / 复制为 Excel 文件功能 | 视图渲染结果可另存 .xlsx | 中 |
| 8 | compute 7 项 metric 当前是 service 硬编码 | 公式可配置化 | 高 |
| 9 | 产品外部系统同步 | ERP 对接 | 高 |
| 10 | 主数据维护 / 字段重要性 无审批工作流 | 接入审批工作流 | 低 |
| 11 | 单实例部署 | 进程内缓存分布式化 → 多实例部署 | 低(评估) |
| 12 | SMTP 通过 properties 配置 | UI 化配置 | 低 |
| 13 | 报价单延期 / 客户回执标记 | UI + 定时提醒 | 中 |

### 10.6 关键代码索引

| 模块 | 路径 | 关键类 |
|---|---|---|
| 报价单 | `cpq-backend/src/main/java/com/cpq/quotation/` | `QuotationService` / `SnapshotCollectorService` |
| 核价单 | `cpq-backend/src/main/java/com/cpq/costingsummary/` | `CostingSummaryService` |
| 核价基础数据 | `cpq-backend/src/main/java/com/cpq/costingbasic/` | `CostingBasicDataService` |
| 料号级核价 | `cpq-backend/src/main/java/com/cpq/costingpart/` | `CostingPartDataService` |
| 组件 | `cpq-backend/src/main/java/com/cpq/component/` | `ComponentService` |
| 模板 | `cpq-backend/src/main/java/com/cpq/template/` | `Template` 实体 |
| 公式引擎 | `cpq-backend/src/main/java/com/cpq/formula/` | `FormulaEngine` / `FormulaEvaluateResource` |
| BNF 路径解析 | `cpq-backend/src/main/java/com/cpq/datapath/` | `CpqPathParser` |
| 隐式 JOIN | `cpq-backend/src/main/java/com/cpq/formula/` | `ImplicitJoinRewriter` |
| 全局变量 | `cpq-backend/src/main/java/com/cpq/globalvariable/` | `GlobalVariableService` |
| 料号版本 | `cpq-backend/src/main/java/com/cpq/partversion/` | `PartVersionService` |
| 导入会话 | `cpq-backend/src/main/java/com/cpq/importsession/` | `ImportSession` 实体 |
| Excel 导入 | `cpq-backend/src/main/java/com/cpq/importexcel/` | `BasicDataImportService` |
| 定价 | `cpq-backend/src/main/java/com/cpq/pricing/` | `PricingRuleService` |
| 审批 | `cpq-backend/src/main/java/com/cpq/approval/` | `ApprovalRuleService` |
| 数据源 | `cpq-backend/src/main/java/com/cpq/datasource/` | `DataSourceService` |
| 基础数据配置 | `cpq-backend/src/main/java/com/cpq/basicdata/` | `BasicDataConfigService` |
| 变更追踪 | `cpq-backend/src/main/java/com/cpq/versioning/` | `VersionedWriter` |
| 系统配置 | `cpq-backend/src/main/java/com/cpq/system/` | `SystemConfiguration` |

### 10.7 关键数据库迁移参考

| V_NN | 内容 |
|---|---|
| V28 | 模板单 PUBLISHED 约束(v2.7 撤销) |
| V62 | DROP V28 的 partial unique index |
| V72 | 模板派生新草稿;核价模板组件 |
| V73/V74 | 核价单 Excel 视图模板 |
| V78 | 全局基础数据默认版本视图(`v_costing_*_price`) |
| V79 | `basic_data_config.template_kind`(QUOTATION/COSTING/BOTH) |
| V80 | `v_costing_summary_full` PIVOT 视图 |
| V90 | useDriverExpansions hook 按行求值 |
| V96 | 隐藏列模式标准化 |
| V101 | ratio 单位统一(小数 vs 百分比) |
| V103 | 复合视图 `v_part_plating_scheme` |
| V104 | 全局变量定义层 (`global_variable_definition`) |
| V106 | 全局变量配置 CRUD + 变更日志 |
| V109 | 全局变量动态 key 模式 |
| V110 | picker UI;ratio LIKE 关键字 + SUM |
| V111 | 视图 NULL 传递,前端整行清空 |
| V112 | ImplicitJoinRewriter 空集不缓存 |
| V114 | comparison_tag 注册 13 个标签 |
| V115 | costing_sheet 表废弃,buildComparison 实时求值 |
| V118 | ProductCard BASIC_DATA cell row 优先级 |
| V128~V144 | template / component / Excel 演进 |
| V145~V148 | template formula layer Stage 1-4 |
| V149 | variable_label;excel_view_snapshot |
| V150 | template 与 costing_template 合并 |
| V160/V161 | merged 视图加 part_version |
| V249 | DROP costing_template_sql_view CASCADE；新建 template_sql_view（FK → template.id） |
| V250 | DROP costing_template.sql_views_snapshot；template 加 template_sql_views_snapshot JSONB |

完整迁移记录见 `cpq-backend/src/main/resources/db/migration/`。

---

**文档维护**:模块新增 / 重大重构时同步更新本文。与代码 / V_NN.sql 不一致时,以代码 + SQL 为准,本文滞后由维护者负责回写。

**反馈渠道**:本文为多 Agent 共享文档,任何不一致请记录到 `docs/RECORD.md` 当日条目。

— 完 —
