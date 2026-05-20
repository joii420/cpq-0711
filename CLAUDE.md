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

- `docs/PRD-v3.md` - Full product requirements with data models, user scenarios, and project plan（功能交付的唯一标准,2026-05-13 起活跃版本）
- `docs/PRD.md` - 已废弃的历史 PRD (v1.0~v2.8),仅作变更决策回溯用途,**不再维护**
- `docs/RECORD.md` - Development record for multi-agent shared memory（开始工作前必须先阅读此文件了解历史上下文）
- `docs/反模式.md` - 反模式速查（PR 自检用，新增功能前必读）
- `docs/组件管理字段配置指南.md` - 字段类型矩阵 / default_source / DATA_SOURCE 4 类型 / 公式 token / 自检 checklist / 避坑速查（**组件字段配置改动前必读**, 2026-05-18 Phase K 后版本）
- `docs/配置中心架构.md` - 三层模型 + snapshot 同步 + 管理端点 + datasource_field token（架构基线）
- `docs/全局变量使用指南.md` - 单表 schema + 新建/维护/引用 SOP
- `docs/数据源类型扩展指南.md` - 加 Resolver SPI（DATABASE_QUERY / GLOBAL_VARIABLE / BNF_PATH / HTTP_API）
- `docs/HTTP_API_安全配置.md` - HTTP_API 启用步骤 + 安全不变量
- `docs/列表操作规范.md` - 列表页面的工具栏动作规范（**所有列表页面必须按此规范实现**，详见下方"UI 交互规范"）
- `docs/报价单核价单功能总结.md` - 报价单与核价单两条主线的功能、流程、视图、状态机、模板体系、数据库主表的整合视图（PRD 没回写核价系统，以本文 + RECORD.md 为准）
- `docs/Excel模板配置指南.md` - Excel 模板（核价单/报价单 Excel 视图）列配置 + VARIABLE/FORMULA 来源 + 公式语法 + 23 列实操对照
- `docs/配置方法论.md` - **组件 / Excel 模板 / 公式 三层配置决策树 + 模式模板 + 16 个常见坑速查**（V96~V118 实战沉淀，新增任何"指标/公式/字段"前必读；含**多行数据展示问题专题**）
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
- `docs/反模式.md` AP-44 - **字段类型变动 / 新增 = 组件管理 + 报价渲染 强联动协议（核心规范）**：任何 `field_type` 改动跨 **15 个检查点 (约 12 个独立文件，部分文件含多子项)** 协议变换 — 写代码前 grep 全工程 / 写代码中按矩阵勾掉 / 写完跑 E2E `quotation-flow.spec.ts` 三步走；详见 `docs/组件管理字段配置指南.md §十一 字段类型联动性矩阵`
- `docs/E2E测试方法.md` - **Playwright E2E 测试标杆 SOP**（2026-05-19 立项；前端协议级改动 / 模板 schema 变更 / driver expand 链路改动 / **字段类型变动**强制 E2E；含选择器约定 / 中文 UTF-8 编码踩坑 / 复测协议 / 复杂多 Tab 矩阵 / Bug 分类清单 / 自检 checklist / **§4.6 console.warn 三段式调试 (LF-FIND/DEBUG/EVAL)**；UI 改动 PR 必读）
- `docs/html/*.html` - 10 interactive HTML prototypes (Chinese language UI)

## Language

All UI, prototypes, and PRD are in Chinese. Code artifacts (variables, APIs, comments) should use English.

## 开发规范
- 当需求发生变更时，必须同步更新 PRD-v3.md 的对应章节
- 在 PRD-v3.md 末尾或第 9 章演进史中记录所有调整
- 不要再修改 PRD.md（已废弃归档）

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

6. **字段类型变动 / 新增** 是**特殊场景**（AP-44 核心规范，2026-05-19 立项）：
   - 触发: 在「组件管理」改 `field_type`（如 INPUT_NUMBER → LIST_FORMULA）/ 加新枚举 / 给现有类型加 sub-type / 加新 config JSON 键 / 改 `VALID_FIELD_TYPES`
   - 影响: **15 个协议检查点跨约 12 个独立文件** — 前端 enrich / normalizeFieldType / cache key / 渲染分支 / computeAllFormulas 字段值循环 / 所有 ProductCard callsite prop / 详情页 ReadonlyProductCard 同步 + 后端 校验 / 路径采集 / 公式 token / refreshSnapshotsByComponent
   - 漏一处必有静默失败（不报编译错也没运行时错，只是 UI 渲染不对）
   - **强制 SOP**:
     - 写代码前 grep 全工程列清单（详见 `docs/组件管理字段配置指南.md §十一 字段类型联动性矩阵`）
     - 写代码中对照 15 项 checklist 勾掉每格
     - 写完**跑 E2E + 复测报价单 + 核价单 + 详情页三个视图** + admin 端点验证 snapshot 各 Tab fields_override 独立保留
   - **PR 必含**:
     - 矩阵 15 处 grep 命中输出
     - E2E `1 passed` + `'加载中' final count = 0`
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
