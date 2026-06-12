# docs/archive — 历史文档归档区

> 2026-06-03 文档整理时归档。这些文档**不再维护**,仅作历史追溯。
> 当前活跃文档见 `docs/` 根目录 + `CLAUDE.md`「Key Documents 必读清单」。
> 所有文件都在 git 历史中,如需恢复可 `git mv` 回原位。

## 归档原因分类

### A. 已作废 / 版本链中间版 / 一次性排查(`specs/`、根目录、`table/`)
- `specs/2026-06-03-核价单Excel-CARD_FORMULA-方案C修订.md` — 文件头 🚫 已作废,被取数统一方案取代
- `specs/2026-04-21-excel-import-design.md` (v1) / `-v2.md` / `2026-04-22-...-v3.md` — Excel 导入 v1→v4 链中间版,被 v5/v6 取代
- `specs/2026-04-23-excel-import-design-v4.md` — v4 里程碑,同样被 v5/v6 取代
- `specs/2026-04-24-basic-data-tables-independent.md` — 未采用的「合并前」对比版(采用了 -merged)
- `选配V6迁移诊断方案.md` / `选配V6入库-is_effective引用面排查.md` — 一次性诊断/排查,结论已落地(见 RECORD)
- `table/报价系统Excel导入落库核查报告-2026-05-30.md` — 一次性核查审计,已完成

### B. 已完成的实现计划 / 里程碑 / 落地方案(`plans/`、`table/`、`templates/`、根目录)
- `plans/2026-04-13-m0~m7 系列` + `2026-04-16-approval-workflow.md` — 早期里程碑,功能早已上线
- `plans/2026-06-01~06-03` 多个 — 已验收交付(RECORD 有「全 PASS/已交付」记录)
- `table/报价系统版本号统一升版规则-实现计划.md` / `视图is_current过滤-实现计划.md` / `视图is_current-worklist.md` — 已完成过程文档
- `templates/核价Excel模板-完整公式版-mapping.md` — 被 `docs/Excel模板配置指南.md` + 端到端方案取代
- `同模板双轨支持组合产品.md` — CLAUDE.md 已标废弃(统一智能视图方案取代)
- `方案-加产品整份快照.md` / `方案-Excel模板BNF迁移至组件SQL视图.md` / `组件级数据源SQL方案.md` — 已落地方案
- `配置方法论.md` / `Excel模板配置指南.md` / `组件管理字段配置指南.md` — 2026-06-12 三份合并去重重写为 `docs/配置方法论-合并版.md`,以当前代码为准校对;新引用一律指向合并版对应章节

### C. 旧开发文档 + 用户/运维手册(根目录)
- 早期开发文档(已被 PRD-v3.md 覆盖):`API.md`、`UI-FLOW.md`、`TDD.md`、`TEST-CHECKLIST.md`、`FIX-PLAN.md`
- 用户/运维手册:`操作说明.md`、`项目说明书.md`、`CAD导出GLB操作手册.md`
- 边缘/已被覆盖:`原型vs代码功能详细对比.md`、`报价模板配置参考-Excel基础结构v1.5.md`、`数据一致性方法论.md`、`示例-阀门选配全流程.md`、`数据库表字段说明-报价渲染链路.md`、`选配V6入库规范-设计方案.md`、`选配与基础数据料号材质关系.md`、`选配落库字段清单.md`
