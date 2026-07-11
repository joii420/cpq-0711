# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

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
- 连远程 PostgreSQL（`jdbc:postgresql://10.177.152.12:5432/cpq_db`）；连接串/凭据默认值见 `cpq-backend/src/main/resources/application.properties`（`${DB_USERNAME:postgres}` / `${DB_PASSWORD:joii5231}`，可用环境变量覆盖）。
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

## Architecture

Nine business modules with a core data pipeline:

```
Component Management (页签组件) → Product Card Template (拖拽组装) → Product Binding → Quote Generator → Quote Output
```

- **Component Management**: Creates reusable tab components (投料/回料/加工 etc.) with field definitions and formulas
- **Product Card Template**: Drag-and-drop assembly of components into product cards
- **Quote Generator**: 5-step wizard where sales reps select product cards and fill in data
- **Pricing Strategy**: Customer-level discount/rebate rules applied during quoting

Templates and components use JSONB storage for flexible field/formula configuration. Components define column structure, field types (fixed value, data source, input, formula), and calculation formulas. Templates reference components via association table, not by duplicating structure.

## Key Documents

- 🚨 **`docs/方案制定前必读.md` — 历史教训速查 + 决策清单（任何编码/架构/迁移方案制定前必读，不读 = 高概率撞已知坑；含症状→反模式速查表 + 7 类改动决策树 + 连环 bug 案例 + 7 步自检清单）**
- `docs/PRD-v3.md` - Full product requirements with data models, user scenarios, and project plan（功能交付的唯一标准,2026-05-13 起活跃版本）
- `docs/archive/PRD-v2.8-历史档案.md` - 已废弃的历史 PRD (v1.0~v2.8),仅作变更决策回溯用途,**不再维护**（2026-06-18 由 `docs/PRD.md` 移入 `archive/`）
- `docs/RECORD.md` - Development record for multi-agent shared memory（开始工作前必须先阅读此文件了解历史上下文）
- 📋 `BACKLOG.md`（项目根目录） - **spec 评审推迟功能的持久化清单**（新会话第一个开发指令前必读；spec 评审后写入、开发完成后更新状态；规则与格式见本文末「Backlog 自动管理规则」）
- 🔒 **`docs/三大核心模块基线.md` - 组件管理 / 模板管理 / 报价单渲染 三大核心架构基线（2026-05-21 终态锁定，后续不轻易修改；任何破坏性改动前必读 + 评估 + 走 architect）**
- `docs/统一智能视图路径方案.md` - 配置驱动方案。**当前版本采用 §2 核心方案（已随 V202 智能视图落地）**；§13（RuntimeContext 上下文字典 + 显式谓词 path + Tab visibleWhen 表达式）为**未来演进方向的备选设计，尚未实施**，不要当成现行终态
- `docs/反模式.md` - 反模式速查（PR 自检用，新增功能前必读）
- 🆕 `docs/配置方法论-合并版.md` - **组件字段 / Excel 模板 / 公式 三层配置的统一权威指南**（第一部·操作手册 + 第二部·原理参考；由 `配置方法论.md` + `Excel模板配置指南.md` + `组件管理字段配置指南.md` 三份合并去重、以当前代码为准重写，与代码同源；**任何字段 / 指标 / 公式 / Excel 列配置改动前必读**；含 §3.5 字段类型切换清理矩阵 / §11 字段类型联动协议(AP-44) / 附录 A 常见坑速查 / 附录 B V6 现役 vs V44 废弃表对照）
- `docs/配置中心架构.md` - 三层模型 + snapshot 同步 + 管理端点 + datasource_field token（架构基线）
- `docs/全局变量使用指南.md` - 单表 schema + 新建/维护/引用 SOP
- `docs/数据源类型扩展指南.md` - 加 Resolver SPI（DATABASE_QUERY / GLOBAL_VARIABLE / BNF_PATH / HTTP_API）
- `docs/HTTP_API_安全配置.md` - HTTP_API 启用步骤 + 安全不变量
- `docs/列表操作规范.md` - 列表页面的工具栏动作规范（**所有列表页面必须按此规范实现**，详见下方"UI 交互规范"）
- `docs/报价单核价单功能总结.md` - 报价单与核价单两条主线的功能、流程、视图、状态机、模板体系、数据库主表的整合视图（PRD 没回写核价系统，以本文 + RECORD.md 为准）
- ~~`docs/组件管理字段配置指南.md`~~ / ~~`docs/Excel模板配置指南.md`~~ / ~~`docs/配置方法论.md`~~ - ⚠️ **已合并 + 已归档**（2026-06-12 三份合并去重为上方 `docs/配置方法论-合并版.md`，原件移入 `docs/archive/`，仅作历史追溯；新引用一律指向合并版对应章节）
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
- `docs/反模式.md` AP-44 - **字段类型变动 / 新增 = 组件管理 + 报价渲染 强联动协议（核心规范）**：任何 `field_type` 改动跨 **17 个检查点 (约 13 个独立文件，部分文件含多子项, 2026-05-20 双轨方案后从 15 处扩到 17 处)** 协议变换 — 写代码前 grep 全工程 / 写代码中按矩阵勾掉 / 写完跑 E2E `quotation-flow.spec.ts` + `composite-product-flow.spec.ts` 双 spec 三步走；详见 `docs/配置方法论-合并版.md §11 字段类型联动协议（AP-44 核心规范）`
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

## Language

All UI, prototypes, and PRD are in Chinese. Code artifacts (variables, APIs, comments) should use English.

## 开发规范
- 当需求发生变更时，必须同步更新 PRD-v3.md 的对应章节
- 在 PRD-v3.md 末尾或第 9 章演进史中记录所有调整
- 不要再修改 `docs/archive/PRD-v2.8-历史档案.md`（原 PRD.md，已废弃归档）

## 开发流程规范（新功能必须用隔离 worktree 分支）🔒
**强制**：开发任何新功能 / 较大改动，**必须**先用 `superpowers:using-git-worktrees` 技能创建**隔离 worktree 分支**，在该隔离工作区里开发，**不直接在主工作区或 master 上改**。

**计划执行方式（默认，不必再询问用户）**：写好实现计划（`superpowers:writing-plans`）后，**默认走 `superpowers:subagent-driven-development`**——每个 Task 派全新子代理实现，Task 间做两阶段评审（先 spec 合规、再代码质量），连续执行不中途请示。除非用户当次明确要求改用 `superpowers:executing-plans` 或手动执行。

**生命周期**：
1. **起步**：调 `superpowers:using-git-worktrees` 建独立 worktree + 特性分支，后续所有编码/提交都在该 worktree 内进行。
2. **开发**：按本文「质量保证规范」「修改后强制自检」完成功能 + 测试 + 自检；协议级改动跑 E2E。默认按上面「计划执行方式」用 subagent-driven 推进。
3. **确认**：完成后**由用户确认**功能达标（不得自行宣布"完成即合并"）。
4. **收尾（用户确认后自动执行）**：走 `superpowers:finishing-a-development-branch` 的"合并并清理"路径 —— 切回 `master` → `git merge <特性分支>` → 跑一遍测试确认合并结果 → **`git worktree remove` 删除新建的 worktree 目录** + 删除该特性分支。

**worktree 共享约束（勿踩）**：worktree 只隔离 **git 工作区 + 分支**；后端 dev server(8081) / 前端 dev server(5174) / 远程 DB / `node_modules` / `.codegraph/` 仍是**共享**的。**不要**在 worktree 里另起 dev server 或重装依赖，直接复用主工作区已运行的实例做 `curl` / E2E 自检（详见历史记忆 `cpq-concurrent-sessions-and-worktree`）。

**并发纪律**：多会话各用自己的 worktree 分支并行，避免污染主工作区；提交只 `git add` 本次明确改动的文件，严禁 `git add -A`（同分支并发提交会交错，参见 RECORD 教训）。

## 代码探索规范（codegraph 优先）
本仓库已建好 `.codegraph/` 代码知识图谱（约 1000+ 文件 / 2 万+ 符号 / 4 万+ 调用边，含 Java + TSX + TS），**涉及代码结构、调用链、符号定位、改动影响面时优先用 codegraph，而非 grep/rg/find 或盲目派子代理探索**：
- **找符号**（"X 在哪 / 叫什么"）→ `codegraph_search`
- **理解某块**（"这个任务/功能/区域怎么回事"）→ `codegraph_context`（起手首选，一发返回入口+相关符号+代码）
- **追调用链**（"X 怎么走到 Y / 这个流程"）→ `codegraph_trace`（一发返回整条路径，含 grep 跟不到的动态分发/回调/JSX/前端 fetch→后端 endpoint 跳转）
- **谁调它 / 它调谁** → `codegraph_callers` / `codegraph_callees`
- **改它会波及谁**（重构/字段类型联动评估前必跑）→ `codegraph_impact`
- **看若干相关符号源码** → `codegraph_explore`（一发取多文件，胜过连环 Read）

**边界**：codegraph 只索引**代码符号**，不索引 Markdown。业务为什么这么设计、进度、踩坑教训、决策仍须读 `docs/`（RECORD.md / PRD-v3.md / 反模式.md / 基线文档）。理想分工 = **codegraph 管代码骨架，docs 管业务血肉**。
**特别地**：AP-44「字段类型改动跨 17 个协议检查点」这类影响面排查，`codegraph_impact` / `codegraph_callers` 比 grep 更准（按真实调用边遍历，不漏别名/间接引用），应作为 grep 全工程的补充而非替代。
> 已配 PreToolUse hook（`.claude/settings.local.json`）：执行 grep/rg/find 或派 Explore/general-purpose 子代理前会自动提醒此规则。

## UI 交互规范
- **统一使用抽屉（Drawer）替代弹窗（Modal）**：所有需要弹出式交互的场景（新建/编辑表单、详情查看、多步骤向导、批量导入、确认配置等）一律使用抽屉组件从屏幕右侧滑出，不再使用居中 Modal。
  - **例外**：仅保留轻量即时反馈类组件（Ant Design `message` / `notification` / `Popconfirm` 简单二次确认），这些不属于"弹窗功能"范畴
  - **技术实现**：前端统一使用 Ant Design `Drawer` 组件，默认 `placement="right"`，宽度按内容复杂度选用 480/720/960/1200
  - **理由**：抽屉与主页面上下文并存便于对照；更适合表单/多步流程；视觉层级统一、移动端体验更好
  - **适用范围**：新增需求、旧页面重构、代码审查均须遵循；如发现代码中仍存在 `Modal` 实现的表单/详情/向导，应在相关改动中顺手迁移为 `Drawer`

- **列表操作统一走 SelectableTable + 工具栏动作模式**（强制规范，详见 `docs/列表操作规范.md`）：
  - **行内不放动作按钮**：所有变更/状态切换/危险动作（编辑/发布/归档/删除/停用…）一律上提到顶部工具栏，行内只保留"主入口"链接（点击进详情/编辑）
  - **选择驱动启用**：每个动作声明 `enabledWhen(selectedRows): true | false | reason 字符串`，禁用时 hover tooltip 显示原因，**禁止用 `if (...) return null` 隐藏不可用按钮**
  - **统一多选 + 跨页保留选中**：单选场景用 `enabledWhen` 限定 `length === 1`，不要单独搞 radio
  - **危险动作走 Modal 列出所选项二次确认**，不再用零散 `Popconfirm`
  - **批量操作的"部分失败"用 `runBatch`** 聚合，message.error 列出失败明细
  - **技术实现**：使用 `cpq-frontend/src/components/SelectableTable.tsx`
  - **何时不用**：纯查看页（详情/下载无副作用）、Master-Detail 双栏、树+编辑器、Drawer 内部子表 — 详见规范文档「12. 例外白名单」
  - **PR 评审强制项**：新增列表页或改动列表页必须按规范实现并对照规范文档第 10 节「PR 自检清单」

## 质量保证规范
1. **严格单元测试**：每次编码完成后进行代码审核和单元自测，如果发现问题进行修复，然后重复进行代码审核和单元自测，直到完全符合要求为止
2. **PRD 驱动交付**：严格按照 `docs/PRD-v3.md` 文档内容进行功能交付，PRD-v3.md 作为测试预期结果的唯一标准（旧 `docs/PRD.md` 已废弃归档，仅作变更决策回溯）
3. **需求变更沟通**：如果用户要求的修改内容与 PRD-v3.md 中的预期不符，必须先与用户沟通确认修改方案，确认后将最终修改方案同步更新到 `docs/PRD-v3.md` 中（含演进史章节）
4. **开发记录（多Agent共享记忆）**：
   - **开始工作前**：必须先阅读 `docs/RECORD.md` 了解历史开发上下文和已知问题
   - **完成工作后**：必须将本次开发的核心内容或修复的核心问题追加到 `docs/RECORD.md`
   - 记录格式：`[日期] 模块 - 简要描述 | 涉及文件 | 关键决策或注意事项`

## 修改后强制自检（每次代码改动结束前必须跑一遍）

**背景**：单纯的 TypeScript 类型检查（`tsc --noEmit`）并不覆盖 Vite/Rollup 的解析阶段错误（如字符串嵌套引号、JSX 解析、syntax 报错），因此每次修改完代码必须**亲自跑一遍编译/transform 验证**，再向用户报告"完成"。

**前端改动**（含 `.tsx` `.ts` `.css` 修改）：
1. `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json` → 必须 0 错误
2. **对每个改动的 `.tsx` 文件**跑 `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/<相对路径>` → 必须 HTTP 200
3. 如果是新加的列表页/Drawer/抽屉，再 `curl http://localhost:5174/` 看主入口 200
4. 如果用户描述的故障路径含具体页面，必须**真的把那个 url 透过 curl 拿到 200**才能宣布修复
5. **协议级改动必须跑 Playwright E2E**（不是可选）—— 改动以下任一文件时:
   - `useDriverExpansions.ts` / `usePathFormulaCache.ts` / `QuotationStep2.tsx` / `QuotationWizard.tsx` / `ReadonlyProductCard.tsx` / `BulkImportPartsDrawer.tsx` / `component/types.ts` / `component/FieldConfigTable.tsx`
   - 后端 `ComponentDriverService.java` / `FormulaCalculationService.java` / `ComponentService.java` / **`TemplateService.java#refreshSnapshotsByComponent`**
   - 模板 snapshot 数据迁移（Flyway V*）
   - 详细规范见 `docs/E2E测试方法.md`
   - 执行命令:
     ```powershell
     cd cpq-frontend
     Remove-Item e2e\screenshots\qf-*.png -ErrorAction SilentlyContinue
     npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list
     ```
   - **必须看到** 所有 test `passed` (当前 `quotation-flow.spec.ts` 含 1 个 test, 后续可能加), `'加载中' final count = 0`, 全部 8 Tab `'加载中'=0`
   - PR 必须附 qf-19 (确认添加后) + qf-21~28 (8 Tab) 9 张截图作为渲染证据
   - 跳过 E2E 等于跳过自检 — AP-37 / AP-38 / AP-40~43 类协议 bug 只在 E2E 暴露, API/TS check 看不到

5.5 **driver expansion 行数纪律**（AP-51，2026-05-22）：
   - 触发场景：改动 `snapshotRows` / `computeTabSubtotal` / 任何涉及 `expansion.rowCount` 的行迭代逻辑
   - **禁止**：`Math.max(expansion.rowCount, baseRows.length)` — driver 权威优先，baseRows 仅在 rowCount=0 时使用
   - **正确**：`const rowCount = expansion?.rowCount > 0 ? expansion.rowCount : baseRows.length`
   - 原因：baseRows.length 是历史持久化值，可能含脏数据（前一次错误写入的 28 行）；driver expansion 才是当前正确行数的唯一来源
   - 自检：E2E 刷新 3 次后行数稳定 = 未累加 ✅

6. **字段类型变动 / 新增** 是**特殊场景**（AP-44 核心规范，2026-05-19 立项, 2026-05-20 扩到 17 处）：
   - 触发: 在「组件管理」改 `field_type`（如 INPUT_NUMBER → LIST_FORMULA）/ 加新枚举 / 给现有类型加 sub-type / 加新 config JSON 键 / 改 `VALID_FIELD_TYPES`
   - **双轨场景** (2026-05-20 新增): 同模板双轨字段 (`basic_data_path` + `basic_data_path_composite` / `data_driver_path` + `data_driver_path_composite`) — 详见 `docs/archive/同模板双轨支持组合产品.md`（已废弃归档，仅历史追溯）
   - 影响: **17 个协议检查点跨约 13 个独立文件** — 前端 enrich / normalizeFieldType / cache key / 渲染分支 / computeAllFormulas 字段值循环 / 所有 ProductCard callsite prop / 详情页 ReadonlyProductCard 同步 + 后端 校验 / 路径采集 / 公式 token / refreshSnapshotsByComponent + (新增) useDriverExpansions tasks 切换 + QuotationStep2 渲染层 isCompositeItem 参数
   - 漏一处必有静默失败（不报编译错也没运行时错，只是 UI 渲染不对）
   - **强制 SOP**:
     - 写代码前 grep 全工程列清单（详见 `docs/配置方法论-合并版.md §11 字段类型联动协议（AP-44 核心规范）`）
     - 写代码中对照 15 项 checklist 勾掉每格
     - 写完**跑 E2E + 复测报价单 + 核价单 + 详情页三个视图** + admin 端点验证 snapshot 各 Tab fields_override 独立保留
   - **PR 必含**:
     - 矩阵 17 处 grep 命中输出
     - E2E `1 passed` + `'加载中' final count = 0` — **双轨场景必须跑两个 spec** (`quotation-flow.spec.ts` SIMPLE + `composite-product-flow.spec.ts` COMPOSITE)
     - 报价单视图 + 核价单视图 + 详情页三处的关键 Tab 截图（修复前 vs 后）
     - `POST /api/cpq/components/{id}/refresh-template-snapshots` 跑过后所有 Tab snapshot.fields 列表打印

**后端改动**（含 `.java` `.sql` 修改）：
1. `touch` 一个 java 文件强制 Quarkus 重启 → 等 5-7 秒
2. `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8081/q/health` 或具体 endpoint → 期望 200/401（不要 500）
3. 如果是 Flyway 迁移：`PGPASSWORD=... psql ... -c "SELECT version, success FROM flyway_schema_history WHERE version = 'NN'"` 必须 success=t
4. 不要手动 `psql -f V_xx.sql`！让 Quarkus dev mode 自己跑 Flyway，否则会在重启时碰到"已存在"导致启动失败

**视图 DROP CASCADE / 重建（schema DDL）后必须重启 Quarkus**：
1. 触发场景：任何 `DROP VIEW ... CASCADE`、`DROP TABLE ... CASCADE`、视图列结构变更、视图重建（V109/V110/V111 是典型案例）
2. **必须** `touch` 一个 java 文件强制 Quarkus 重启（不是只跑 Flyway 就够）
3. 原因：`ImplicitJoinRewriter.tableColumnsCache` / `CachedSqlCompiler` / `CachedPathParser` 都是 ApplicationScoped 进程级缓存。视图被 CASCADE 临时删除的瞬间若有请求触发 `getColumns` → 缓存了**空集** → V112 之前永久残留 → 后续所有 BNF 路径求值不再注入 `hf_part_no` 谓词 → 视图返全表 N 行 → UI 出现「首值（共N项）」错乱
4. 自检：用一个含 BNF 路径的 endpoint 验证，期望返单值（不是数组）
5. 失败征兆：`v_costing_summary_full.xxx` 在 Excel 模板列里显示成 "—（共4项）" / "0.138（共3项）"
6. V112 后已修：空集不再缓存，下次自愈；但已残留旧 JVM 进程缓存仍需重启清空。**任何 schema DDL 操作完都重启一次以防万一**

**禁止手工 `psql -f V_xx.sql` 后不重启 Quarkus**：
- 即使 SQL 是幂等的，Quarkus dev 重启时 Flyway 仍按 file → history checksum 对账，可能因为本地 git 改动跟 history 记录不符而报 `Migration checksum mismatch`
- 正确做法：让 Quarkus 启动时 `migrate-at-start=true` 自动跑（已配置）。文件放进 `db/migration/` 后 touch 一个 java 文件即可触发

**当出现"看到了 500/红色 overlay"反馈时**：
- 不要立刻假设"是用户的浏览器缓存"——先 `curl` 拉一次原始响应，看错误信息是不是已经修了；
- 如果文件确实是好的而 overlay 仍存在，告诉用户 **强刷（Ctrl+Shift+R）** 清 HMR overlay；
- 如果文件本身有问题，立即修，再次跑完整自检后通报。

**任何"完成"宣告必须包含一行"已自检"声明**，例如：
> "TS 0 错误 ✅；CostingPartDataPage.tsx → Vite 200 ✅；后端 /api/cpq/.. → 401（auth 正常）✅；V77 success=t ✅"

没有这行声明的"完成"=未完成。

# Backlog 自动管理规则

## 核心原则
所有在 spec 评审中被建议推迟的功能，必须持久化到 `BACKLOG.md` 文件中。
禁止只在对话中口头提及二期任务，上下文丢失不能成为功能遗漏的理由。

---

## 规则一：评审 Spec 时的强制行为

当我要求你评审任何 spec、PRD、需求文档时，你必须：

1. 完成评审分析后，明确列出**建议推迟的功能点**及推迟理由
2. 询问我是否采纳这些推迟建议（等待我确认，不要自动写入）
3. 收到我的确认后，立即创建或更新项目根目录的 `BACKLOG.md`
4. 每个推迟条目必须包含以下字段：
   - 功能描述（来源：spec 哪一节/哪个模块）
   - 推迟原因（复杂度过高 / 依赖未就绪 / 超出本期范围 / 其他）
   - 优先级（P0 必做 / P1 重要 / P2 可选）
   - 前置条件（依赖一期的哪个模块或接口完成后才能开始）
   - 预估规模（S=1-2天 / M=3-5天 / L=1周以上）
5. 写入完成后，输出 BACKLOG.md 的摘要确认，并告知总计推迟条目数

---

## 规则二：每次新会话启动时的强制行为

每当我在新会话中发出第一个开发任务指令时，你必须先执行以下步骤，再开始任务：

1. 检查项目根目录是否存在 `BACKLOG.md`
2. 如果存在，读取并列出所有状态为 `[ ]`（待开发）的条目及其优先级
3. 判断本次任务是否与任何待开发条目相关，并告知我
4. 如果相关，询问是否一并处理，等待我决策
5. 如果不相关，简短提示当前 Backlog 中有 N 个待开发条目，然后继续执行我的任务

---

## 规则三：完成开发任务时的强制行为

每次完成一个开发任务后，你必须：

1. 检查本次实现是否覆盖了 BACKLOG.md 中的任何条目
2. 如果覆盖，将对应条目状态更新为 `[x]`，并在变更记录中追加一行
3. 检查本次实现是否满足了某些条目的「前置条件」，如果是，更新该条目的前置条件备注为「✅ 已就绪」
4. 输出一句话收尾摘要，格式为：
   「Backlog 状态：共 N 条，已完成 X 条，待开发 Y 条，其中 P0 级 Z 条。」

---

## BACKLOG.md 标准格式

每次写入或更新 BACKLOG.md 时，严格遵守以下格式：

```markdown
# Project Backlog

> 自动维护文件，由 Claude Code 在 spec 评审后写入，开发完成后更新。
> 开始新任务前请先阅读本文件。

## 状态说明
- [ ] 待开发
- [~] 进行中
- [x] 已完成
- [-] 已废弃（需注明原因）

---

## 待开发条目

### [模块名称]

- [ ] **功能描述**
  - 来源：spec §X.X / [章节名]
  - 推迟原因：
  - 优先级：P0 / P1 / P2
  - 前置条件：
  - 预估规模：S / M / L

---

## 变更记录

| 日期 | 操作 | 条目 | 备注 |
|------|------|------|------|
```
