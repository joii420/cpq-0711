# 核价 Excel 导出树化（POI xlsx）— 设计方案

- 日期：2026-06-05
- 状态：已与用户确认全部口径，待写实施计划（plan）
- 适用范围：报价单/核价单 Excel 视图的 xlsx 导出；核价侧导出 BOM 树，报价侧导出扁平表
- 前置：P2-B「核价 Excel 视图树状」已落地（`costingExcelValues` 快照含 `{rows:[N 树行], treeMode:true}`，每行带 `__hfPartNo/__parentNo/__bomVersion/__nodeId/__lvl`）
- 关联文档：
  - `docs/superpowers/specs/2026-06-05-核价Excel树状-P2B-design.md`（快照结构来源）
  - `docs/Excel模板配置指南.md`（excel_view_config 列定义）
  - `docs/反模式.md` AP-41（报价/核价隔离）

---

## 0. 定位（一句话）

> Step2 Excel 视图工具栏加「导出 Excel」按钮，把**当前侧的 Excel 快照所见即所得**导出为 xlsx：核价单导出缩进的 BOM 树（料号/父料号/版本 + 业务列，每节点一行），报价单导出扁平表（料号 + 业务列，1 行/产品）。

现状：`GET /{id}/export-excel-view` 端点 + `quotationService.exportExcelView` 存在，但**前端无任何按钮调用**（孤儿），且其后端走 `getExcelView` live 重算（默认/报价模板、无料号列、非树）。本期改为**读快照**并接上按钮。

不做：累乘列（已停，业务公式决定）；版本切换（P2-A）；PDF/HTML 导出；导出时的折叠。

---

## 1. 需求与已确认口径

| # | 决策点 | 结论 |
|---|---|---|
| 1 | 导出入口 | Step2 Excel 视图加「导出 Excel」按钮，**导出当前侧**（核价→树，报价→扁平） |
| 2 | 数据源 | **快照**（`costingExcelValues`/`quoteExcelValues`），所见即所得；不走 live 重算 |
| 3 | 树缩进表现 | **料号列前导空格缩进**（每层 `(__lvl-1)` 个全角空格 + 料号），与 UI 观感一致、复制不丢层级 |
| 4 | 核价导出列 | `料号(缩进) | 父料号 | 版本 | <excel_view_config 业务列…>` |
| 5 | 报价导出列 | `料号 | <excel_view_config 业务列…>`（无父料号/版本，隔离） |
| 6 | 多产品 | 各 line item 的行**顺序拼接**于同一 sheet（无额外分隔行；YAGNI） |
| 7 | 空快照 | 输出只含表头的空 sheet，不 500 |

---

## 2. 架构

读快照导出，单一新方法承载：`ExcelViewService.exportExcelViewSnapshot(quotationId, side)`。
- 复用 P2-B 已落的快照结构（核价 N 树行 / 报价 1 行），不重算、不依赖 live `getExcelView`。
- 端点 `export-excel-view` 加 `side` 参数路由；旧无参行为 = `side=QUOTE`（兼容）。
- 前端在 Excel 视图加按钮，按当前 `side` 调用。

---

## 3. 数据流

```
GET /quotations/{id}/export-excel-view?side=COSTING
  → ExcelViewService.exportExcelViewSnapshot(id, COSTING)
      1. 取 quotation + lineItems(按 sortOrder)
      2. templateId = side==COSTING ? q.costingCardTemplateId : q.customerTemplateId  // 与快照生成所用模板一致(excel_view_config 对齐)
      3. columns = parseJsonArray(template.excelViewConfig)
      4. XSSFWorkbook, sheet "Excel View"
      5. 表头行：核价 = [料号,父料号,版本] + columns.title；报价 = [料号] + columns.title
      6. for li in lineItems:
           snapJson = side==COSTING ? li.costingExcelValues : li.quoteExcelValues
           rows = parse(snapJson).rows            // 核价: N 树行; 报价: ≤1 行
           for r in rows:
              核价: 料号单元格 = "　"×max(0,(r.__lvl-1)) + r.__hfPartNo; 再写 父料号/版本; 再写各 col 值
              报价: 料号单元格 = li.productPartNo (或 r 无则空); 再写各 col 值
      7. 返回 workbook bytes
```

报价侧 `quoteExcelValues` 行无 `__hfPartNo` → 料号取 `li.productPartNoSnapshot`。

---

## 4. 落点（文件触点）

### 后端
| 改动 | 文件 / 位置 | 说明 |
|---|---|---|
| 新增 `exportExcelViewSnapshot(quotationId, side)` | `ExcelViewService` | 读快照 → XSSFWorkbook；核价前置 3 列 + 料号缩进；报价前置料号 |
| 端点加 `side` 参数 | `QuotationResource#exportExcelView` | `@QueryParam("side")`，默认 QUOTE；文件名带 核价/报价 |
| 旧 `exportExcelView(id)` | `ExcelViewService` | 保留，委托 `exportExcelViewSnapshot(id, "QUOTE")`（兼容；live getExcelView 路径弃用但不删，避免影响其它潜在调用） |

### 前端
| 改动 | 文件 | 说明 |
|---|---|---|
| `exportExcelView` 加 `side` 参数 | `services/quotationService.ts` | `(id, side) => GET .../export-excel-view?side=${side}` blob |
| 「导出 Excel」按钮 | `LinkedExcelView.tsx`（Card extra 区） | `DownloadOutlined`；按当前 `side` 调用 → blob → 触发浏览器下载（`URL.createObjectURL` + a.download，文件名取响应头或 `{partNo}-{核价/报价}-view.xlsx`） |

> 注：`LinkedExcelView` 已有 `side` prop；按钮放其 `<Card extra>`，与「共 N 行」并列。

---

## 5. 隔离（AP-41）
- `side` 显式区分；报价导出不写 父料号/版本 列。
- 报价 `quoteExcelValues` 仍单行 → 报价导出 1 行/产品，行为同改动前（仅新增料号列 + 可用按钮）。

## 6. 边界与风险
1. 空/缺快照 → 该 li 无行，仅表头 sheet，不抛 500。
2. 核价快照若非 treeMode（旧快照/未 refresh）→ 行无 `__lvl`/`__hfPartNo` → 缩进退化为 0、料号取 `li.productPartNoSnapshot` 兜底，仍可导出（不报错）。
3. 列值格式：导出写快照原值（数字/字符串）；`display_format` 千分位等精细格式本期不在 xlsx 复刻（follow-up），保证值正确即可。
4. 前导空格用全角空格 `　`（半角在 Excel 易被 trim 观感不明显）。

## 7. 自检 / 验收（DoD）
1. 核价单 Excel 视图点「导出 Excel」→ 下载 xlsx：表头 `料号|父料号|版本|A|B|C…`，数据行 = spine 节点数（如 3120018220 = 17），料号按层级缩进，根行版本=2000。
2. 报价单 Excel 视图点「导出 Excel」→ 下载 xlsx：表头 `料号|<业务列>`，1 行/产品，无 父料号/版本。
3. 后端集成测试 POI 读回断言（行数/表头/缩进/版本/隔离）。
4. E2E：点按钮触发下载，响应 200 + content-type = `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`。
5. 全量自检：后端重启 + 401；前端 tsc 0 + Vite 200。

## 8. 后置（不在本期）
- `display_format`（千分位/小数位）在 xlsx 复刻；列宽/样式美化；多产品分隔/产品标题行；PDF 导出树。
