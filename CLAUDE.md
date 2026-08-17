# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> 📦 **本文件是常驻层**：每个会话、每个子代理都会完整加载它，所以它只装**决策与路由**，不装操作细节。
> 操作细节在 `docs/rules/` 的 8 份分册里，**由 §2.2 触发矩阵按条件调取**。

---

# 会话开始时的动作顺序（按此顺序，不要跳，也不要重排）

本文件里有多处写着「必读」「先做」。**它们的唯一执行顺序是这里定义的**：

| # | 动作 | 何时做 |
|---|---|---|
| 1 | §1 仍是「待探测」，或 `docs/` / `dev-docs/` 缺 §2.1 表中的文件 → 执行 **§0 初始化** | 仅首次；只探测和建骨架，不动代码 |
| 2 | 掌握在途态势：`RECORD.md` 历史与已知问题 · `INDEX.md`「当前项目态势」· `BACKLOG.md` 待开发条目 | 每个新会话一次。**会话开局的 hook 已注入三者摘要 —— 别再整篇重读**，要细节时定向读 |
| 3 | 收到代码改动请求 → 走 **§4.3 路径确认**，等用户拍板 | **每个新请求都要走** |

> 🚨 **三条不进这张表的规则**，因为它们不是「先做什么」而是「什么时候都成立」：
> - **§3.2 不可逆操作红线** —— 任何时刻、任何路径、任何授权下都生效
> - **§2.2 触发矩阵** —— 不是会话开始时读一次，而是**每次动手前对照一次**
> - **下面这条压缩重锚** —— 压缩可能发生在任何时刻

## 🔄 上下文压缩之后：重锚（本条写在这里，是因为它必须活过压缩）

**压缩清掉的是 transcript，不是系统提示。** 所以压缩后你会进入一个最危险的状态：
**本文件还在**（你还记得有规则、还记得任务在做什么），但下面这些**已经没了**——

| 蒸发 | 还在 |
|---|---|
| 规则分册全文 | 本文件 `CLAUDE.md` |
| **AC 原文**、任务文档正文 | 任务目录里的**文件**（重读即可） |
| 你发出的派工 prompt、子代理回报正文 | 已落盘的 `test-report.md` |
| 原型图内容 | `原型图/*.html` 文件 |

🚫 **压缩摘要里的转述不能替代原文。** 摘要足以让你「觉得自己知道」，不足以让你验对 ——
AC 的可观测断言（**具体数值、具体文案、具体行数**）恰恰是最先被摘要抹平的东西。

✅ **判据一句话**：**说不出当前任务的 AC 编号和它们的可观测断言 = 已经蒸发了。**
先把 `需求文档.md`（返修任务是 `问题说明.md`）的验收标准一节读回来，再按 §2.2 重新调取本阶段的分册 —— 然后才动手。

🚫 **不许凭摘要继续写代码、写测试或做亲验。**

---

# 0. 首次进入项目：初始化（一次性）

**触发条件**：§1 仍带「待探测」，或 `docs/` / `dev-docs/` 缺 §2.1 表中的文件。
**必须先做完初始化，再执行用户的任务。**

📖 **全部步骤 → `docs/rules/bootstrap.md`**（绿地判定 / 依赖自检 / 建仓库 / 探测清单 / 凭据脱敏 / 裁剪边界 / 骨架脚本 / 汇报要求）

只有一条底线必须常驻，因为它保护的是**这套规则本身**：

🚫 **被约束方不能删自己的约束** —— §1/§2 的探测结果可直接回写；**§3~§7 与分册的规则条款只能等效替换，删除必须用户点头。**
> 初始化发生在会话最开头，用户还没读过内容。此刻允许删条款，等于规则在用户视线外消失且不留痕。

---

# 1. 项目速览

### 项目定位

CPQ (Configure, Price, Quote) system for manufacturing/industrial components. The system manages customer quoting workflows: component creation -> product card template assembly -> quote generation -> quote output (PDF/Excel/email).

**Status**: Pre-implementation (requirements & design phase). No application code exists yet. The `docs/` directory contains the PRD and HTML prototypes.

## Technology Stack

- **Backend**: Java 17 + Quarkus 3.23.3 (RESTEasy Reactive, Hibernate ORM with Panache, Flyway)
- **Frontend**: Node.js 24 (SPA framework TBD)
- **Database**: PostgreSQL 16 (JSONB for flexible template/component config)
- **Export**: Apache POI (Excel), Quarkus Qute (PDF)

## 本地开发服务启动（前后端 + DB）

> dev server 是**全会话/多 worktree 共享**的（见「开发流程规范」的 worktree 共享约束）：先探端口，**已在跑就直接复用，不要重复起**。

**后端（Quarkus dev，端口 8081）**
```bash
cd cpq-backend && ./mvnw quarkus:dev
```
- 端口 `8081`，绑 `0.0.0.0`；dev 模式带 Live Coding（改 java 自动热重载）。
- ✅ **当前本地开发环境不加 profile 参数**，走默认 profile → `10.177.152.12:5432/cpq_db_0724`。
- ⚠️ 三个 profile 指向**不同的库**。库选错的症状是「改动/数据看不到效果」，且可能污染他人环境：

  | profile | 主机 / 库 | 用途 |
  |---------|----------|------|
  | **默认（不带 `-Dquarkus.profile`）** | **`10.177.152.12:5432/cpq_db_0724`** | ✅ **当前开发环境，日常开发用这个** |
  | `jh` | `localhost:5432/cpq_db`（本机 docker PG16） | 备用本地库，当前不使用 |
  | `test` | `10.177.152.12:5432/cpq_db` | 后端自动化测试（`mvnw test` 走这个，与 dev 库不同——写集成测试时注意） |

- 凭据 `${DB_USERNAME:postgres}` / `${DB_PASSWORD:joii5231}`，可用环境变量覆盖；各 profile 配置见 `cpq-backend/src/main/resources/application-<profile>.properties`。
- 连库自检：`PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_0724 -c '\conninfo'`
- **确认 8081 实际连的哪个库**（不确定时用这招，比读配置可靠）：比对 `GET /api/cpq/quotations?page=1&size=1` 返回的 `totalElements` 与各库 `SELECT count(*) FROM quotation` —— 数字对上就是那个库。
- 启动时 Flyway 自动 `migrate-at-start`；**不要**手工 `psql -f V_xx.sql`（详见「修改后强制自检」）。
- 首次启动约 6-7s；热重载遇大范围文件变化（如切分支）会重编译，期间 8081 短暂无响应属正常。

**前端（Vite dev，端口 5174）**
```bash
cd cpq-frontend && npm run dev
```
- 端口 `5174`，绑 `0.0.0.0`；`/api` 经 Vite proxy 转发到后端 `localhost:8081`（浏览器只需开 `http://localhost:5174`）。

**启动/存活自检（两个坑，务必按此判断）**
```bash
# ⚠️ 坑1: 本机 shell 常设了 http_proxy=127.0.0.1:7890，curl 访问 localhost 会走代理返 502。
#        探本机服务一律加 --noproxy '*'。
# ⚠️ 坑2: 后端未装 smallrye-health，/q/health 返 404 —— 它不是健康探针！
#        判后端健康看业务端点返 401（应用在跑、鉴权正常）。
curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:5174/                 # 前端: 期望 200
curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:8081/api/cpq/components # 后端: 期望 401
```
- 后端连库确认（可选）：`SELECT state,count(*) FROM pg_stat_activity WHERE datname='cpq_db' GROUP BY state;` 有一批 idle 连接 = 连接池已建。


### 架构

Nine business modules with a core data pipeline:

```
Component Management (页签组件) → Product Card Template (拖拽组装) → Product Binding → Quote Generator → Quote Output
```

- **Component Management**: Creates reusable tab components (投料/回料/加工 etc.) with field definitions and formulas
- **Product Card Template**: Drag-and-drop assembly of components into product cards
- **Quote Generator**: 5-step wizard where sales reps select product cards and fill in data
- **Pricing Strategy**: Customer-level discount/rebate rules applied during quoting

Templates and components use JSONB storage for flexible field/formula configuration. Components define column structure, field types (fixed value, data source, input, formula), and calculation formulas. Templates reference components via association table, not by duplicating structure.


### Language

文档、UI、原型使用中文；代码产物（变量、函数、API、注释）使用英文。

---

# 2. 文档体系与规则分册

## 2.1 每类知识的唯一出处

**缺失的按 `docs/rules/bootstrap.md` §2 创建，不要另起炉灶新建同类文件。**

| 角色 | 文件 | 约束 |
|---|---|---|
| **产品级唯一标准** | `docs/PRD.md` | 功能验收最终依据，跨任务长期有效。需求变更必须回写，含演进史 |
| **多 Agent 共享记忆** | `docs/RECORD.md` | **开始工作前必读**；完成后必须追加。格式 `[日期] 模块 - 描述 \| 涉及文件 \| 关键决策` |
| **历史任务索引** | `dev-docs/INDEX.md` | 含「当前项目态势」「按症状反查」「按代码文件反查」三张表。维护触发点见 §2.3 |
| **反模式速查** | `docs/反模式.md` | **按症状组织**的跨任务知识库（AP-NNN）。排查 bug 第二入口 |
| **本项目改动决策清单** | `docs/方案制定前必读.md` | 🚨 **任何编码/架构/迁移方案制定前必读**：7 类改动决策树 + AP-44 的 17 个协议检查点 + 连环 bug 案例。与 `change-protocol.md`（通用 SOP）配套，**两份都要过** |
| **三大核心模块基线** | `docs/三大核心模块基线.md` | 组件管理 / 模板管理 / 报价单渲染。破坏前必须评估 + 用户裁决 |
| **架构基线** | `docs/架构基线.md` | 不轻易改动的核心契约。**破坏前必须评估 + 用户裁决** |
| **待办清单** | `docs/BACKLOG.md` | 见 §7 |
| **任务文档目录** | `dev-docs/` | 每任务一子目录，规范见 `docs/rules/task-docs.md` |
| **规则分册** | `docs/rules/` | 8 份，见 §2.2 |
| **角色代理** | `.claude/agents/` | 3 个执行角色：`frontend-engineer` / `backend-engineer` / `test-engineer`。**实现与初步测试一律由它们做，主线只统筹**（§4.0 分工铁律）。架构决策、需求取舍、AC 编写、亲验、合并留在主线，不设代理 —— 那些是需要跟用户来回的活 |


### 2.1.1 本项目的文档地图（cpq 专有，上表是通用骨架，这里是实际清单）

- 🚨 **`docs/方案制定前必读.md` — 历史教训速查 + 决策清单（任何编码/架构/迁移方案制定前必读，不读 = 高概率撞已知坑；含症状→反模式速查表 + 7 类改动决策树 + 连环 bug 案例 + 7 步自检清单）**
- `docs/PRD-v3.md` - Full product requirements with data models, user scenarios, and project plan（功能交付的唯一标准,2026-05-13 起活跃版本）
- `docs/archive/PRD-v2.8-历史档案.md` - 已废弃的历史 PRD (v1.0~v2.8),仅作变更决策回溯用途,**不再维护**（2026-06-18 由 `docs/PRD.md` 移入 `archive/`）
- `docs/RECORD.md` - Development record for multi-agent shared memory（开始工作前必须先阅读此文件了解历史上下文）
- 📋 `BACKLOG.md`（项目根目录） - **spec 评审推迟功能的持久化清单**（新会话第一个开发指令前必读；spec 评审后写入、开发完成后更新状态；规则与格式见本文末「Backlog 自动管理规则」）
- 🗂️ **`dev-docs/INDEX.md` - 历史任务索引（36 目录 / 216 篇的导航表）**。**排查任何 bug、接任何新需求前必读**：§0「按症状反查」一眼定位「这事属于哪个历史任务」，再去读那个目录的主文档，避免重踩已定位过的根因。dev-docs 约 5 MB，**禁止全量扫**，一律先过 INDEX 再定向深读。新建任务目录 / 任务状态变化时必须回写本索引（规则见 INDEX §9）
- 🔒 **`docs/三大核心模块基线.md` - 组件管理 / 模板管理 / 报价单渲染 三大核心架构基线（2026-05-21 终态锁定，后续不轻易修改；任何破坏性改动前必读 + 评估 + 走 architect）**
- `docs/统一智能视图路径方案.md` - 配置驱动方案。**当前版本采用 §2 核心方案（已随 V202 智能视图落地）**；§13（RuntimeContext 上下文字典 + 显式谓词 path + Tab visibleWhen 表达式）为**未来演进方向的备选设计，尚未实施**，不要当成现行终态
- `docs/反模式.md` - 反模式速查（PR 自检用，新增功能前必读）
- 🆕 **`dev-docs/rule-260724-组件模板配置/` — 组件模板配置规则的唯一权威文档集**（2026-07-24 由 `docs/配置方法论-合并版.md` + 报价/核价配置文档合并迁移，原件已归档 `docs/archive/`）。**配置任意客户报价/核价组件模板前必读**：起点 `AGENT-配置入口.md`（Agent Runbook：模板→配置全流程 + 数据来源映射 + 料号列绑定铁律 + 元素单价接价格策略 + SQL 契约 + 自检）+ 自描述模板 `组件模板.xlsx`（用户只填字段名 + 页签类型，其余按规则推导）；深度规则 `1-总则与工作流` / `2-组件与字段` / `3-SQL视图` / `4-页签属性与树` / `5-公式与Excel列`（含 §5.2 字段类型联动协议 AP-44）/ `报价侧` / `核价侧` / `附录-速查`（6 维度对照 + 常见坑 + V6 表映射）
- `docs/配置中心架构.md` - 三层模型 + snapshot 同步 + 管理端点 + datasource_field token（架构基线）
- `docs/全局变量使用指南.md` - 单表 schema + 新建/维护/引用 SOP
- `docs/数据源类型扩展指南.md` - 加 Resolver SPI（DATABASE_QUERY / GLOBAL_VARIABLE / BNF_PATH / HTTP_API）
- `docs/HTTP_API_安全配置.md` - HTTP_API 启用步骤 + 安全不变量
- `docs/列表操作规范.md` - 列表页面的工具栏动作规范（**所有列表页面必须按此规范实现**，详见下方"UI 交互规范"）
- `docs/报价单核价单功能总结.md` - 报价单与核价单两条主线的功能、流程、视图、状态机、模板体系、数据库主表的整合视图（PRD 没回写核价系统，以本文 + RECORD.md 为准）
- ~~`docs/组件管理字段配置指南.md`~~ / ~~`docs/Excel模板配置指南.md`~~ / ~~`docs/配置方法论.md`~~ / ~~`docs/配置方法论-合并版.md`~~ / ~~`docs/rule/报价模板生成规则.md`~~ / ~~`docs/核价树页签组件配置指南.md`~~ / ~~`docs/核价SQL配置手册.md`~~ - ⚠️ **已合并 + 已归档**（2026-06-12 前三份合并为 `配置方法论-合并版.md`；2026-07-24 该合并版连同报价/核价配置文档进一步迁移至 `dev-docs/rule-260724-组件模板配置/`，原件全部移入 `docs/archive/`，仅作历史追溯；新引用一律指向 rule-0724 对应文件）
- `docs/反模式.md` AP-22 - **多行数据 "X (共N项)" 显示族**（4 类共因：SQL 隐式 JOIN 失效 / 渲染层漏读 row / 视图 COALESCE 遮蔽 NULL / comparison_tag 未注册）
- `docs/反模式.md` AP-31 - **"加载中…" 永久占位族**（修 `useDriverExpansions.ts` / `QuotationStep2.tsx` / `QuotationWizard.tsx` / `ConfigureProductService` 等写后端 `mat_*` 流程**必读**；4 类共因：fingerprint 漏维度 / pre-enrich 缓存 EMPTY_EXPANSION / invalidate 漏调 / DATA_SOURCE 渲染缺 fallback；含 PR 专项自检清单 + F12 Network 验证步骤）
- `docs/反模式.md` AP-37 - **新字段类型 / 同 componentId 多实例 cache 冲突**（AP-31 续集；加新 `field_type` (如 LIST_FORMULA) 必须同步改 **9 处协议传播点** — enrich mapper / normalize / cache key / 渲染 case / 后端白名单 / computeAllFormulas 字段值循环 / parseBasicDataPaths+usePathFormulaCache 路径采集 / **driverExpansionKey 含 fields hash 维度**；模板里同 componentId 多次出现时 cache key 必须含 `dataDriverPath + fieldsHash`；batchExpand 结果配对必须用 task index 而非 backend r.key；含 6 个独立根因诊断 + PR 协议清单 + DATA_SOURCE 4 子类型解析协议对照表）
- `docs/反模式.md` AP-38 - **"0 行 driver 鬼魂行加载中"**（2026-05-19 E2E 暴露；driver=mat_xx 等返 0 行 + autoSave 留空 row + BASIC_DATA cell 走 globalPathCache miss → "加载中…"；BASIC_DATA 渲染分支必须 `activeDriverExpansion?.rowCount === 0 → "—"` 兜底, 不能盲目降级 globalPathCache）
- `docs/反模式.md` AP-39 - **PUBLISHED 模板 snapshot 残留 V109 老散字段**（follow-up；V190~V193 数据迁移只动 component.fields 没动所有引用方 jsonb 列；含 3 个候选修法对比）
- `docs/反模式.md` AP-40~43 - **B3 4 个连锁 bug 沉淀**（2026-05-19 LIST_FORMULA 渲染调试串联事故）：
  - AP-40 H1 `refreshSnapshotsByComponent` 同 cid 多 tc 实例 `firstResult()` 反向污染（V206 后端修；必须按 sortOrder 精确匹配）
  - AP-41 prop drilling 漏传（报价单 vs 核价单 ProductCard 不对齐 → 一个视图功能正常另一个失效）
  - AP-42 `{...lfItem, ...rawRow}` 用 null 字段反向覆盖 lfItem 自动映射（V207 前端修；用 `rawRowNonEmpty` filter）
  - AP-43 Vite ESM 项目残留 `require()` 抛 ReferenceError → catch 吞错误 → 渲染 "—"
- `docs/反模式.md` AP-44 - **字段类型变动 / 新增 = 组件管理 + 报价渲染 强联动协议（核心规范）**：任何 `field_type` 改动跨 **17 个检查点 (约 13 个独立文件，部分文件含多子项, 2026-05-20 双轨方案后从 15 处扩到 17 处)** 协议变换 — 写代码前 grep 全工程 / 写代码中按矩阵勾掉 / 写完跑 E2E `quotation-flow.spec.ts` + `composite-product-flow.spec.ts` 双 spec 三步走；详见 `dev-docs/rule-260724-组件模板配置/5-公式与Excel列.md §5.2 字段类型联动协议（AP-44 核心规范）`
- `docs/反模式.md` AP-50 - **详情页/编辑页渲染层 single-source 反模式**（2026-05-22；ReadonlyProductCard 缺 DATA_SOURCE/LIST_FORMULA 分支致僵尸数据掩盖；抽 ComponentCell 共享解决；AP-44 矩阵 #14/#15 合一）
- `docs/反模式.md` AP-51 - **`snapshotRows` Math.max 持久化累加死锁**（2026-05-22；driver 行数权威纪律；写 snapshotRows / computeTabSubtotal 必读）
- `docs/反模式.md` AP-52 - **全局变量绑定的"语义错配 + 契约不对齐"双重隐患**（2026-05-22；QT-1590~1604 连环 bug 综合教训；含 4 类独立根因 + 4 条强制规范）
- 🚨 `docs/反模式.md` AP-53 - **V44 老表禁用 + SQL 视图模板查老表导致的渲染数据断链**（2026-05-26 立项）：V218~V222 落 23 张 V6 表后 V44 `mat_part / mat_bom / mat_process / mat_fee / plating_plan / mat_customer_part_mapping / element_price*` 等**已废弃**。强制规则：组件 `data_driver_path` + 字段 `basic_data_path` 禁用直接 PG 视图名/表名，必须 `$<sql_view_name>` 引用；component_sql_view.sql_template 必须 FROM V6 表；V6 无 `quotation_line_item_id` 维度（customer × material 共享）；详见 `docs/方案制定前必读.md` §V6 基础资料表使用规则
- `docs/反模式.md` AP-54 - **过滤后下标当原数组下标 → 编辑写错位 Tab，受控 input 假死**（2026-05-27；QT-1656 复现）：`QuotationStep2.tsx` ProductCard 渲染用 `normalComponents`（过滤 SUBTOTAL 后子集）下标，写路径 `handleRowChange/handleInputBlur/handleDeleteRow/handleAddRow + dsStateKey` 却用同一 `activeTab` 索引未过滤的 `item.componentData`；SUBTOTAL 排第 0 位 → 偏移 +1 → 文本/数字输入框 value 回退假死。修法：`activeComponentDataIndex = item.componentData.indexOf(activeComponent)` 映射回真实下标。通用规范：**渲染用过滤子集、写回用原集合时，写路径下标必须按对象引用/稳定 ID 映射回原下标**（改 QuotationStep2.tsx 必读）
- `docs/反模式.md` AP-45 - **组合产品模板用单子件 driver 渲染错** (2026-05-20 提出 → **2026-05-21 终态修复**)：~~双轨字段方案~~ **已被统一智能视图方案替代**；新解法 = V202 `v_composite_child_*` 视图自适应 SIMPLE/COMPOSITE + ComponentDriverService 按 compositeType 三分支注入；详见 `docs/三大核心模块基线.md` §5.1 + §7.B 场景
- ~~`docs/archive/同模板双轨支持组合产品.md`~~ - ⚠️ **已废弃 + 已归档** (2026-05-21 由 `docs/统一智能视图路径方案.md` + `docs/三大核心模块基线.md` 取代；2026-06-03 移入 `docs/archive/`)；保留作历史追溯
- `docs/E2E测试方法.md` - **Playwright E2E 测试标杆 SOP**（2026-05-19 立项；前端协议级改动 / 模板 schema 变更 / driver expand 链路改动 / **字段类型变动**强制 E2E；含选择器约定 / 中文 UTF-8 编码踩坑 / 复测协议 / 复杂多 Tab 矩阵 / Bug 分类清单 / 自检 checklist / **§4.6 console.warn 三段式调试 (LF-FIND/DEBUG/EVAL)**；UI 改动 PR 必读）
- `docs/html/*.html` - 10 interactive HTML prototypes (Chinese language UI)
- 🧪 **`docs/3D-集成总览-索引.md`** — **3D 集成入口导航**（2026-05-26 v0.4 收敛后）：单一主线决策树 + 5 个 HTML 原型导航 + v0.4 数据模型清单 + 已废弃表清单。**任何 3D 相关改动前先读这个**
- 🧪 `docs/3D产品选配方案.md` — **实验性 v0.4 · 选配模式（唯一主线）**（19 章）：全屏配置器；UG NX 双文件工作流；**选项值即特征（feature_type / attributes / tags 字段下沉）**；**option_value 可绑独立子模型 (sub_model_part_no)**；多租户/版本/审批/分享 5 章节
- 🧪 `docs/CAD转换POC-技术验证.md` — **CAD POC**（2026-05-26 立）：Dockerfile + 4 个 Python 脚本草案 + 性能基准 + 5 步落地路径


**为什么「按症状」这一类不能省**：`RECORD.md` 按时间组织、`INDEX.md` 按任务/文件组织，**都要求你先知道这事归谁管**。而排查现场你手上只有症状。

**归档纪律**：文档废弃时**移入 `docs/archive/` 并在索引里划掉（`~~删除线~~`）+ 注明取代者**，不要直接删。Agent 会引用旧路径，必须让它撞到「已废弃，请看 X」而不是撞到空。

**禁止全量扫大目录**：`dev-docs/` 体量变大后，一律先过 `INDEX.md` 再定向深读。

## 2.2 🎯 规则分册触发矩阵（每次动手前对照）

**本文件不含操作细节。下表决定你现在该打开哪一份。**

| 你正要做的事 | **必读** | 装了什么 |
|---|---|---|
| **首次进入项目**（§1 仍是「待探测」） | `docs/rules/bootstrap.md` | 探测清单 + 凭据脱敏 + 裁剪边界 + 骨架创建脚本 |
| 改前端源码（`.tsx`/`.ts`/`.vue`/`.css`）、动 UI 交互 | `docs/rules/frontend.md` | UI 规范（弹层/列表模式）+ 🎨 **页面设计类改动必先出 HTML 原型图、定稿后 1:1 还原**（§1.3）+ 前端强制自检 + 红色遮罩处理 |
| 改后端源码 / SQL / 迁移 / schema DDL | `docs/rules/backend.md` | 🚫 **N+1 硬指标：单个业务操作的 SQL 条数必须是常数，与 N 无关；循环体里出现查询 = 违规**（任何语言任何 ORM）。四种批量化手法 + 例外申请三条件 + 后端强制自检 + DDL 后必须重启 + 迁移纪律 |
| 写测试、跑测试、判断"这个绿可不可信" | `docs/rules/testing.md` | 测试可信前提 + 不稳定失败归因 + 证伪实验 + 冷启动验证 |
| 走路径 A 立项、写任务文档、写 AC、BUG 返修立项 | `docs/rules/task-docs.md` | 任务目录规范 + 六件套 + AC 编写规范 + 返修六节 |
| **闸门 A 呈报前 + 收到「开工」时**（不论你打不打算派子代理） | `docs/rules/subagents.md` | 🤖 **「开工」二字 = 全套执行编排的触发词**（默认并行派三类子代理）+ 派工六段 prompt + 四类假绿 + 失联处理 |
| 建分支 / worktree / 提交 / 多会话并发 | `docs/rules/git-worktree.md` | worktree 共享约束 + 并发纪律 + 提交纪律 + 工作区卫生 |
| 改字段类型 / 枚举 / 跨端契约 / 任何"改一处多处静默失效"的改动 | `docs/rules/change-protocol.md` | 强联动 SOP + 检查点清单法 + 教训→规则晋升 |

🚫 **不读就动手 = 违规**，等同于跳过自检。分册里的规则与本文件同等效力，**不是参考资料**。

⚠️ **拿不准归哪一类时，宁可多读一份。** 判错的代价（漏一条协议规则 → 静默失败）远大于多读一份的代价。

## 2.3 `INDEX.md`「当前项目态势」

**过期的态势表比没有更危险**（它让人以为自己看过了）。四行各绑死一个写/删动作，触发点写在动作发生的地方：
建分支时 → `git-worktree.md §5`；建任务目录 / 结案 / 缺陷闭合 → `task-docs.md §10`。

---

# 3. 开发规范

> 编号从 §3.2 起 —— §3.1（需求变更同步）已并入 §6.3，§3.3（N+1）已并入 §2.2 触发矩阵。
> **编号不重排**：`§3.2` 被分册和 agent 定义多处引用，重排会让它们指向错处而且不报错。

## 3.2 🚨 不可逆操作红线（不受任何路径 / 豁免 / "开工"授权覆盖）

本文件其余部分都建立在一个假设上：**做错了可以改回来**。本节管的是这个假设不成立的那些操作。

| 类别 | 具体操作 |
|---|---|
| **数据销毁** | `DROP TABLE/VIEW/SCHEMA`（尤其带 `CASCADE`）、`TRUNCATE`、无 `WHERE` 或命中面不明的 `DELETE`/`UPDATE` |
| **历史销毁** | `git reset --hard`、`git push -f` / `--force-with-lease`、rebase 已推送分支、删分支、`git clean -fdx` |
| **文件销毁** | `rm -rf`、覆盖写未读过的文件、批量重命名/移动 |
| **契约销毁** | 删除或改名**已应用到共享库**的迁移文件、删除他人正在引用的文档/接口 |
| **环境销毁** | 清库、重建库、重置共享 dev server 状态、改动共享环境的全局配置 |

**三步前置，缺一不可**：

1. **先量化影响面** —— 用只读手段说清「将影响多少行 / 哪些对象」：同样 `WHERE` 先跑 `SELECT count(*)`、`--dry-run`、`git diff --stat`、`ls` 一遍要删的路径。**说不出数字就不许执行。**
2. **说清可恢复路径** —— 有备份？可由迁移重建？可从远端恢复？**三个都没有，就直说「此操作不可恢复」**，不要模糊带过。
3. 🚦 **用户明确批准本次** —— 报「操作 + 影响面数字 + 可恢复性」，等回话。**批准不跨操作、不跨会话**：批了删 A 表不等于批了删 B 表。

**三条补充**：

- ⚠️ **§4.3 的路径 B 和「开工」授权都不豁免本节。** 它们豁免的是流程文档和分支隔离，不是数据安全。
- ⚠️ **子代理同样受约束，且没有批准权** —— 派工 prompt 必须写明「遇红线停下报告」（见 `docs/rules/subagents.md`）。
- ⚠️ **测试也算**：测试里的清库 / 重置全局状态属于「环境销毁」。**共享库上不许跑会清库的测试**，哪怕它写在 `beforeAll` 里。

---

# 4. 开发流程规范（路径确认 → 闸门 → worktree 隔离）

## 4.0 🤖 分工铁律（本项目的工作方式，不是建议）

**主线只做统筹，实现交给子代理。所有走路径 A 的任务一律如此。**

| | 谁做 | 具体 |
|---|---|---|
| **统筹侧** | **主线（你自己）** | 需求分析与澄清 / 立项与 AC 编写 / 方案呈报与裁决传达 / **派工与监控** / 审核测试用例 / 亲验 / 合并 / 结案 / **与用户的全部交互** |
| **执行侧** | **子代理** | 前端实现（`frontend-engineer`）、后端实现（`backend-engineer`）、测试用例编写与执行（`test-engineer`） |

🚫 **主线不写业务实现代码。** 发现要改实现 → 派工或回流给对应子代理，不要自己动手。
🚫 **子代理不做需求取舍与架构决策，不直接与用户交互** —— 有意见报主线。

**唯一豁免是「客观做不到」**（例：不是 git 仓库 → 没有 worktree → 派工六段的第一段填不出来）。
🚫 **没有「我判断这次不必派」这种豁免** —— 工作量小、契约没定型、怕假绿、串行更稳，**都不是理由**。
判断权在用户，不在你（§4.3 决策权归属）。

> 📌 **路径 B（直接修复）不适用本节** —— 它按定义就是「单文件单点、根因明确、不建目录不开分支」，
> 由主线直接改并在 `RECORD.md` 记一行。为两行改动派三个代理，编排开销远超收益。

📖 派工六段 prompt / 四类假绿复验 / 失联处理 → `docs/rules/subagents.md`

## 4.1 术语：先统一三个词

| 词 | 含义 | 不要混淆 |
|---|---|---|
| **主线** | **当前这个主会话（你自己）**，相对于你派出去的子代理 | ≠ master 分支 |
| **亲验** | 主线自己动手复验，不采信子代理的汇报 | ≠ 用户验收 |
| **验收** | **用户**在已合并环境真机确认 | ≠ 亲验 |

## 4.2 什么是「闸门」

**闸门 = 必须停下来等用户回话的地方。不是自检清单，不是走形式 —— 是「用户不点头，下一步就不许做」。**

全流程共 4 个停止点，各问一件不同的事：

| 停止点 | 在问什么 | 用户未回答时该做什么 |
|---|---|---|
| **路径确认**（§4.3） | 这事走完整流程，还是直接改？ | 停。可继续读代码/查根因，**不许建任务目录、不许改代码** |
| 🚦 **A0 方案裁决** | 原因查清了，有几种改法，选哪个？ | 停。**不许把候选方案写进文档**，更不许按自己偏好开写 |
| 🚦 **A 开工确认** | 文档写完了，就照这个干？ | 停。**不许建分支、不许写代码** |
| 🚦 **B 验收** | 做完并已合并，你去点一遍，对吗？ | 停。状态停在「待验收」，**不许自行标「已交付」** |

**三条判定规则**：

1. 🚫 **「通过」只能由用户给出。自己检查一遍 ≠ 闸门通过。**
2. **用户没明确表态时一律视为未通过。** 含糊回应（「嗯」「看看」「你先弄」）要追问确认，不要当默许。
3. 闸门可被用户**显式豁免**（「不用问了，直接做」），但**豁免只对当次有效，不跨会话**。

## 4.3 🚦 开工前必须与用户确认路径（先于本章所有规则）

**收到任何代码改动请求时（新功能 / BUG / 优化 / 重构），禁止自行决定走哪条路径，也禁止直接动手。**

> **不适用本节**（直接做，不用问）：纯文档改动、流程本身要求的回写动作（`RECORD.md` / `INDEX.md` / `BACKLOG.md`）、只读探查。
> 判据：**没改到会进构建产物的代码，就不用问。**

| 路径 | 含义 | 产出 |
|---|---|---|
| **A. 任务立项** | 走完整流程：建任务目录 → 写文档 → 闸门 A0 → 闸门 A → worktree → 开发 → 亲验 → 合并 → 闸门 B → 结案 | 完整任务文档 + 特性分支 |
| **B. 直接修复** | 不建任务目录、不开分支，直接在主工作区改 | 代码改动 + 自检 + 一行 `RECORD.md` + **一条路径限定的提交** |

**提问格式**：一句话说清改动性质与影响面 → 给出**推荐路径**和理由 → 等用户拍板。不要罗列长篇选项。

🚫 **禁止把「走哪条路径」和「要不要豁免闸门」捆成同一个选项。** 那是两个独立决策，捆在一起用户会连带批准掉他没打算批的那个。
要问豁免就单独问一次，并写明代价（「豁免闸门 A = 我不把需求文档拿给你过目就直接开写，AC 是对是错你要到闸门 B 才看得到」）。
⚠️ 同理：**不要拿用户随口说的话当豁免依据**（「你说跑完关掉会话，所以我就不停了」）。豁免必须是用户针对闸门本身的明确表态。

| 倾向 A（立项） | 倾向 B（直接修） |
|---|---|
| 新功能 / 新页面 / 新接口 | 纯文案、样式微调、注释与文档 |
| 跨多个文件，或影响面不清 | 单文件单点，根因明确 |
| 涉及数据契约、DB 迁移、公式、权限 | 依赖版本号、配置项 |
| 需要回归验证既有功能 | 改动 ≤ 2 个文件且不碰上述任一项 |

⚠️ **两列会同时命中，此时不是"权衡"，是 A 侧优先**：「只改 2 个文件」和「涉及 DB 迁移」完全可能同时成立。判定规则——**A 侧任一条命中即推荐 A**。**文件数是最弱的判据，不要用它抵消契约类判据。**

### 🐛 BUG 单独一张判据表（上表针对改动，本表针对缺陷）

上表按「改动长什么样」分流，但 BUG 的改动往往很小、**风险却在别处**。命中任一即 A：

| BUG 走 A 的判据 | 为什么 |
|---|---|
| **根因未定位** | 不知道原因就估不出影响面，也写不出可执行的验收标准。返修流程要求「写到根因才能进 A0」 |
| **修法有 2 种以上合理选择** | 需要闸门 A0 裁决，那是用户的决定不是你的 |
| 跨多文件，或涉及数据契约 / 迁移 / 权限 / 金额与公式 | 与上表 A 侧同源 |
| 需要回归验证既有功能 | 同上 |
| 🔁 **同一症状第 2 次出现** | 上次没修到根上。第二次还按 B 快修，就是在同一个坑上叠补丁 |

**全不命中**（根因明确 + 单点 + 修法唯一 + 不碰上述）→ 走 B。
⚠️ 走 B 的 BUG **仍要在 `RECORD.md` 写根因**，不能只写「改了什么」——
否则 `INDEX.md` 的「按症状反查」表长不出来，下一个人还得从头查一遍。

📖 走 A 的 BUG 按 `docs/rules/task-docs.md §5` 建 `repair-` 目录、写 `问题说明.md` 六节。

三条硬约束：

1. 🚫 **用户答复前，不许建任务目录，也不许改代码。** 探测/读代码/定位根因可以先做。
2. ⚠️ **走 B 豁免的只是流程文档和分支隔离，不豁免验证。** 自检照跑；改完必须**主线亲自确认改动真的生效**（不是「应该没问题」）；派了子代理则 `subagents.md` 的假绿清单同样适用。
3. ✅ **B 的收尾必须提交，不是留在工作区。** 自检通过即提交，**一条路径限定的提交**（`git commit -- <本次改的文件> docs/RECORD.md`），不需要用户批准——B 本来就没有闸门。
   > 🚫 不提交的后果不是「少一步」，是**下一个会话无法用 `git status` 判断有没有在途工作** ——
   > 而 `git-worktree.md` 整套并发纪律都建立在 `git status` 可读这个前提上。攒两三次之后就再也分不清哪些改动是谁的了。
   > ⚠️ 工作区若已有**上一次遗留的未提交改动**，先报给用户问怎么拆，不要一把梭全提交进去。
3. 用户已明确表示「直接改，不用问」时按 B 执行，并注明「按你上条指示走直接修复」——**该授权不跨会话**。

### 决策权归属（贯穿全流程）

**路径选择（A/B）、技术方案选型、需求取舍、需求范围增减 —— 一律由用户裁决。**
Claude 的职责是给出**分析、候选方案、明确推荐和推荐理由**，不是替用户做选择。

⚠️ **「范围变更」和「方案变更」同样要回来问。** 开发中发现需求有缺口、要加一条 AC，那是**扩范围**——用户在闸门 A 批的是 N 条 AC，不是 N+3 条。追溯链维护得再好也替代不了重新点头。

🚫 **禁止把尚未裁决的选择写成既成事实**（写进文档、代码、提交）。凡「有两种以上合理做法」的岔路，**先在对话里问，拿到答复再落文档**。文档记录的是**裁决结果**，不是提案。

## 4.4 与技能包（superpowers 等）的优先级裁决

技能**硬编码了自己的产物形态和默认落盘路径**。不显式 override，Agent 会照搬技能默认值，产出游离在任务目录体系之外的文档，且**不报错**。

**通则：任何技能与本 `CLAUDE.md` 冲突时，一律以 `CLAUDE.md` 为准。**（多数技能自身也写明「User preferences override this default」。）

- 🚫 **禁用**产出计划类文档的技能（如 `writing-plans`）—— 文档形态以 `docs/rules/task-docs.md` 为准
- ✅ **保留且必用**需求澄清类技能（如 `brainstorming`）—— 它是步骤 -1 的承担者，但**覆盖其落盘路径**，一律落 `dev-docs/`
- ✅ 保留 worktree / 收尾分支类技能
- **废弃路径要留碑**：技能默认路径若曾被用过，标注「已停用，仅保留历史存档」而不是直接删目录

📖 **三个确认阶段（brainstorming / A0 / A）如何分工才不会让用户点三次头 → `docs/rules/task-docs.md` §6.5**

## 4.5 生命周期（闸门不可跳）

```
-1. 澄清需求 → 调 brainstorming，一次一问把模糊点问清（做什么/给谁/什么场景/边界）
              ⚠️ 需求没问清就动笔 = 写出来的 AC 全是猜的
0.  立项    → 目录名日期先跑 date +%y%m%d 实取，再建 dev-docs/task-YYMMDD-xxx/
              写立项文档（规范见 docs/rules/task-docs.md）——只写需求侧，方案留给 A0
              同步在 docs/BACKLOG.md 登记条目
              ⚠️ 这一步在建 worktree 之前，不要反过来
0.3 出原型  → 🎨 页面设计类改动（新页面/新弹层/布局或交互改动）必出 HTML 原型图
              放 dev-docs/<任务目录>/原型图/，双击能开、真实数据、空态与禁用态画全
              触发条件与还原纪律见 docs/rules/frontend.md §1.3
              ⚠️ 不新增闸门 —— 随整套文档在闸门 A 一并定稿；两种以上布局方案才走 A0
0.5 🚦闸门A0 → 方案裁决：有两种以上合理做法时，先在对话里呈报候选
              （改动面/代价/风险）+ 明确推荐，等用户拍板；裁决结果才写进文档
              方案唯一时可直接进闸门 A，但要说明「无岔路」
1.  🚦闸门A  → 用户确认完整文档（**含原型图定稿 + 派工计划**）。未过严禁建分支写代码
              进闸门前自检三项（详见 task-docs.md），任一不过就打回重写：
              ① AC 质量 ② 正向覆盖（每条 AC 有人认领）③ 反向覆盖（每项能指回 AC）
              呈报时一并说明**派工计划**：派哪几类子代理 + 每类认领哪些 F-x/B-x
              —— 按 §4.0 分工铁律，派是默认，不派只有「客观做不到」一种理由
              ⚠️ 自检通过只是取得「呈报资格」，放行仍由用户裁定
2.  起步    → 用户说「开工」「开始吧」「可以了」= 闸门 A 放行信号，**同时是全套编排的触发词**
              ⚠️ 先提交任务文档，再建 worktree（顺序不能反）
              🤖 **动作 = 并行派三类子代理**（前端 / 后端 / 测试）—— 见 §4.0 分工铁律
                 用户说了「开工」就等于下达了这套指令，**不需要他每次重复交代**
              🚫 **主线不写业务实现代码。** 不派的唯一理由是「客观做不到」（如无 git 仓库），
                 且必须在汇报里点名说。**「我判断这次不必派」不是理由**
3.  开发    → 前后端并行 + 测试写用例 + 主线审用例 + 测试执行
              自检照跑（frontend.md / backend.md）；协议级改动见 change-protocol.md
4.  主线亲验 → 逐条对照 AC，四条同时满足才算打勾：
              a. 在用户实际使用的环境跑（dev server + dev 库 + 真实数据）
              b. 走用户视角的完整路径（从 UI 进，不是从 API 或单测进）
              c. 每条 AC 附可复核证据 —— 禁止只写「✅ 通过」
              d. 证据必须非空正向结果。空列表 / 0 行 / "—" 一律不算通过
              e. 页面设计类改动追加：**逐屏比对实现与原型图**，附对照截图，
                 偏差逐条列出（只允许"组件库能力所限的等价实现"这一类）
              🚫 测试全绿不构成 AC 达成的证据：
                 测试证明「代码按实现者的理解工作」，AC 证明「功能符合需求文档」
              ⚠️ 不采信子代理的「已完成」。这一步不可省
5.  收尾合并 → 亲验通过即自动执行，不等用户许可。**六步按序，见 §4.6**
6.  🚦闸门B  → 汇报「AC 逐条达成 + 亲验证据 + 已合并」，用户在已合并环境真机验收
7.  结案    → 见 §4.6。验收未通过 → 状态改「返修中」，开返修任务
```

> ⚠️ **步骤 5 的合并不是闸门。** 全流程只有路径确认、A0、A、B 四处等用户点头；合并由「亲验通过」这个客观条件触发。
> **为什么合并在验收之前**：dev server 服务的是主工作区已合并代码，不先合并用户根本没法真机验收。
> 配套：①自动合并跳过的是"等用户点头"，不是"亲验"——没有亲验证据就合并 = 拿 master 当测试环境；②验收不达标走 **fix-forward**（开返修任务），不默认 revert，除非缺陷会污染数据或破坏在途单据。

## 4.6 结案输出契约

📖 **七个任务状态、结案三件事、闸门 B 归因 → `docs/rules/task-docs.md` §9.5**（触发点唯一：结案时）

结案必须输出这三行，**写「无」是合法的，不写才是漏项** —— 强制输出的作用是逼你想一遍：

```
任务索引：新增 N 条 / 更新 M 条状态 / 无变化
Backlog 状态：共 N 条，已完成 X 条，待开发 Y 条，其中 P0 级 Z 条。
规则升级：本次提议 N 条 / 无
```

---

# 5. 代码探索规范（代码索引优先）

项目若建有代码索引（如 codegraph MCP），**改动影响面分析一律用它，不用 grep 估**（工具自身的说明里有选型细节，不在此重复）。索引只管代码符号，不索引 Markdown —— **索引管代码骨架，文档管业务血肉**。

⚠️ **grep 结果为空 ≠ 不存在**：某些环境的 `grep` 是别名（如 `ugrep -I`），会把含大量非 ASCII 的源文件**静默判为二进制并返空**。据 grep 空结果下「无引用 / 影响面为零」结论前，必须用 `/usr/bin/grep -a` 复核。

---

# 6. 质量保证规范

1. **严格单元测试**：每次编码完成后审核 + 自测，发现问题即修复，重复直到全绿。
   ⚠️ **全绿是必要条件不是充分条件**，之后仍必须走 §4.5 步骤 4 的 AC 亲验，二者不可互相替代
2. **需求文档驱动交付**：严格按 `docs/PRD.md`；单个任务以该任务的立项文档为验收切片，**该切片须与 PRD 一致**
3. **需求变更沟通**：与 PRD 预期不符时，**必须先与用户沟通确认方案**，确认后同步更新 `docs/PRD.md` 的对应章节**并在演进史章节记录本次调整**。🚫 不要修改 `docs/archive/` 下已归档的历史版本
4. **开发记录（多 Agent 共享记忆）**：开始工作前先读 `docs/RECORD.md`；新会话进场读 `INDEX.md`「当前项目态势」；完成后追加 `RECORD.md` 并回写 `INDEX.md`
   - 🚫 **排查 bug 时**：先过 `INDEX.md`「按症状反查」→ 查不到再过 `docs/反模式.md` → 才动手复现。**禁止跳过本步直接读代码**（重查已定位过的根因 = 纯浪费 + 大概率得出与既有结论不一致的判断）
   - ⚠️ **要改热点文件时**：先查「按代码文件反查」表看它压着什么契约，再跑影响面分析

## 6.1 "完成"宣告的格式要求

**任何"完成"宣告必须包含一行"已自检"声明**，例如：

> TS 0 错误 ✅；`XxxPage.tsx` → dev server 200 ✅；后端 `/api/xxx` → 401（auth 正常）✅；迁移 V77 success=t ✅

**没有这行声明的"完成" = 未完成。**（各端具体自检项见 `frontend.md` / `backend.md`）

⚠️ **「已自检」声明 ≠ 「亲验证据」，闸门 B 汇报里两者都要有**：自检回答「**代码能跑吗**」（形式是命令与其输出），亲验回答「**功能对吗**」（形式是实际业务输出值/截图/接口原始响应）。
只给自检 = 只证明了「它没坏」；只给亲验而没跑自检 = 可能在半坏的环境里得出了正确结论。

---

# 7. Backlog

**所有在评审中被建议推迟的功能，必须持久化到 `docs/BACKLOG.md`。**
🚫 **禁止只在对话中口头提及二期任务** —— 上下文丢失不能成为功能遗漏的理由。

⚠️ **写入前必须问用户是否采纳，不要自动写。**

📖 三条维护规则（评审时 / 新会话开局 / 完成任务时）与条目格式 → `docs/rules/task-docs.md` §9
> 「新会话开局列出待开发条目」这条已由会话开局 hook 注入摘要承担，不必整篇重读。
