# S-2 执行报告 · run-05（AC-13 详情页口径）

## 结论

AC-13 按用户 2026-09-26 裁决改在现存 1845 产品草稿单 `QT-20260908-0628` 的详情页执行。详情页稳定出现分页栏，不经过编辑页 Step1，因此避开 BL-0322 的报价模板回填竞态。4/4 通过：

| 用例 | 结果 | 观测 |
|---|---|---|
| T-09 第 1200 位销售料号按回车搜索 | ✅ | `T260907T-B01200`，命中 1 条，显示在第 1 页 |
| T-11 无结果空态 | ✅ | `XYZ999`，卡片 0 张，副文案含 1845，`pageerror = 0` |
| T-12 查询结果跨页 | ✅ | 前缀 `T260907T-B00` 命中 999 条；第 2 页为命中集合第 11~20 条 |
| T-13 清空查询 | ✅ | 清空后恢复「共 1845 条」，分页恢复，卡片数 ≤10 且非空 |

## 环境与纪律

- 分支临时 Vite：`http://localhost:5390`，后端：共享开发服务 `8081`。
- 执行命令：

  `PW_BASE_URL=http://localhost:5390 T260923_S2_EVIDENCE_DIR=/tmp/task260923-ac13 npx playwright test e2e/task260825-paging-search.spec.ts --grep 'T-09|T-11|T-12|T-13' --reporter=list`

- 4 个用例总耗时约 1.2 分钟。
- 只读守卫短路写请求；单据写入指纹前后均为：

  `2026-09-09 06:50:49.238318+00#0#659e50b52e6ea60c29e3f7273fef5268`

- 页面脚本错误：`pageerror = 0`。记录到的 2 条 `console.error` 为既有 antd 弃用警告，不计入 AC 判据。
- 截图证据：本目录 `search-T-09-01-deep-hit.png`、`search-T-11-02-empty-state.png`、`search-T-12-03-query-page2.png`、`search-T-13-04-cleared.png`。

## AC-13 范围说明

本轮不验证 Excel 子项。现存 1845 行样本的报价模板 `excel_view_config` 为空，无法产生 Excel 行；Excel 搜索与当前页行数关联由 AC-9 在配置了 Excel 视图的自造样本单 A 覆盖。

