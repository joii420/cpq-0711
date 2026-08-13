# 测试用例

## 1. 测试口径

1. 普通 `INPUT_NUMBER` 的逐字保真载体必须是 decimal string。AC-2 使用字符串 `"1.2300"`；JSON numeric token `1.2300` 经普通 JSON mapper 后不能证明尾零原文仍在，不得拿它代替 AC-2。
2. 只有字段元数据和协议共同确认的结构整数（本次至少 `项次`、`_项次`、`序号`）允许 JSON number，且必须是安全整数。不得把普通输入小数或全部动态 number 一并放行。
3. 字段语义以报价绑定模板的冻结字段定义为准，查找键是 `templateId + componentId`。同一 `componentId` 在不同模板中可以有不同字段语义。
4. 公式过程值按 12 位验证；公式单元格结果、产品卡片小计、报价总金额分别验证自己的结果边界。两个 scale 名字不同但实际共用入口，判定失败。
5. 保存失败必须同时检查 HTTP 400、完整字段路径/原值和数据库写指纹不变。只测 helper 抛错不能替代真实 HTTP 证据。
6. P0 不得 skip。正式交付必须冻结 Git HEAD、前后端 diff hash、数据库 migration/schema 状态和执行命令原始摘要。

## 2. AC 覆盖矩阵

| AC | 主用例 | 补充用例 | 必须交付的证据 |
|---|---|---|---|
| AC-1 | TC-01 | TC-17 | HTTP 2xx；请求、DB JSON、GET 中 `项次`均为 number `1` |
| AC-2 | TC-02 | TC-15 | UI 输入、请求原文、DB JSON 文本、GET/重开均逐字 `"1.2300"` |
| AC-3 | TC-03 | TC-18 | 12 位基础数据 decimal string 在保存/重开后逐字不变 |
| AC-4 | TC-04 | TC-19、TC-27 | 前后端黄金值；链式公式依赖 12 位工作值，最终最多 9 位且 HALF_UP 一致 |
| AC-5 | TC-05 | TC-13、TC-20 | 公式 numeric token 400；路径/原值；DB 零写 |
| AC-6 | TC-06 | TC-21、TC-28 | 只有产品卡片最终小计使用小计专用 helper；列级/组件中间汇总不提前套该边界 |
| AC-7 | TC-07 | TC-22 | 报价总额边界值及所有生产写点使用总额专用 helper |
| AC-8 | TC-08 | TC-21、TC-22 | 测试注入不同 scale 后两个真实生产入口独立变化 |
| AC-9 | TC-09 | TC-03、TC-04 | 静态扫描与大数动态结果均无 JS number/Double 中转 |
| AC-10 | TC-10 | TC-17 | 三个中文结构键解析为安全整数；源文件无乱码字面量 |
| AC-11 | TC-11 | TC-16、TC-23 | 报价、核价、比较三视图输入原文/公式结果一致且冻结读零写 |
| AC-12 | TC-12 | TC-24 | migration 清单、V385 history、21 列 26/12、无 schema diff |

## 3. 功能与契约用例

| 编号 | 对应 | 优先级 | 前置数据 | 步骤 | 期望 | 实际 |
|---|---|---|---|---|---|---|
| TC-01 | AC-1 | P0 | DRAFT；冻结字段 `项次=INPUT_NUMBER` | 真实 HTTP 保存 `rowData=[{"项次":1}]`，再查 DB JSON 和 GET | 2xx；三处均为安全整数 number `1` | |
| TC-02 | AC-2 | P0 | DRAFT；自定义字段 `输入单价=INPUT_NUMBER` | UI 输入字符串 `"1.2300"`，保存、查请求/DB、刷新、重开 | 四处文本逐字 `"1.2300"`；不得变成 `"1.23"`、补 9 位或 number | |
| TC-03 | AC-3 | P0 | 基础数据带入字段为 `INPUT_NUMBER`，值 `"98765431.123456789012"` | 带入、保存、重开并参与后续计算 | 输入载体逐字不变；计算入口无损解析；不压到 9 位 | |
| TC-04 | AC-4 | P0 | 同一前后端黄金数据 | 覆盖 `1/3`、`0.1+0.2`、乘加、负数、零、大整数和 `1.2345678905` | 每个公式过程节点 12 位 HALF_UP；最终公式值最多 9 位且前后端逐字一致 | |
| TC-05 | AC-5 | P0 | DRAFT；冻结字段 `公式金额=FORMULA` | 记录 quotation/line/component 指纹；将公式值改为 JSON number `0.410000001` 后真实 HTTP 保存 | 400；消息含 `lineItems[i].componentData[j].rowData[k].公式金额` 和原值；所有指纹不变 | |
| TC-06 | AC-6 | P0 | 产品卡片小计边界数据 | 通过真实卡片小计生产入口计算 `1.2345678905` | decimal string `"1.234567891"`；调用 `PRODUCT_CARD_SUBTOTAL_SCALE` 对应 helper | |
| TC-07 | AC-7 | P0 | 报价汇总边界数据 | 通过报价创建/保存/价格调整/提交相关总额生产入口汇总 `1.2345678905` | `originalAmount/totalAmount` 等总额结果为 `"1.234567891"`；调用报价总额专用 helper | |
| TC-08 | AC-8 | P0 | 可注入/参数化结果 scale 的测试入口 | 同一输入下设置小计 scale=7、总额 scale=8；再反向设置小计=8、总额=7 | 首轮小计 `"1.2345679"`、总额 `"1.23456789"`；反向后结果随各自参数互换，互不影响 | |
| TC-09 | AC-9 | P0 | 前后端改动清单 | 扫描新增/改动精度链中的 `.toNumber()`、`Number(...)`、`parseFloat`、`Double`、`.doubleValue()`、`.asDouble()`；跑 14 位整数+12 位小数用例 | 无新增浮点中转；大数结果与 BigDecimal/Decimal 黄金值一致 | |
| TC-10 | AC-10 | P0 | lossless JSON | 解析 `{"项次":1,"_项次":2,"序号":3,"输入数量":1.2300}` 并扫描源码 | 前三者是 number；普通小数不经 JS number；`losslessJson.ts` 与后端源码不存在 `椤规/搴忓彿` | |
| TC-11 | AC-11 | P1 | 同一 DRAFT 同时可在报价、核价、比较视图读取 | 保存输入 `"1.2300"`、公式/小计/总额边界值；依次打开并重开三视图 | 三视图输入文本逐字一致；公式/小计/总额均为对应最多 9 位 decimal string | |
| TC-12 | AC-12 | P1 | V385 测试库 | 查 migration 文件、Flyway history、`information_schema.columns` | 无 V386/新 migration；V385 成功；21 个目标列全部 `numeric(26,12)` | |
| TC-13 | FR-9 | P0 | componentId、templateId、字段元数据分别缺失/损坏 | 每类构造含 numeric token 的未知字段后保存 | 400；消息含 templateId、componentId、字段名、JSON 路径；不得全局放行 | |
| TC-14 | N+1 | P0 | 见 §4 复合键数据集 | 分别保存 N 与 2N 行，统计字段元数据 SQL | 查询数为常数且结果分类正确；循环/stream 内无 repo 或懒加载 | |
| TC-15 | 回归 | P1 | `INPUT_TEXT`、`INPUT_NUMBER` 的 null/空串/负数/零/尾零 | 保存、GET、重开 | 输入类型、空值语义和原始文本不变；`""` 不改为 `"0"` | |
| TC-16 | AC-11 | P1 | APPROVED/冻结单，三视图结构齐全 | 记录 quotation/line/component/structure 的 count+xmin+md5；GET 三视图并复查 | 零写入、零重算、历史输入/结果不变；不读取活组件改变语义 | |
| TC-17 | AC-1/10 | P0 | 除“项次”外另建自定义 `INPUT_NUMBER` 和结构整数 | 分别保存自定义输入字符串、三个结构整数；再给普通小数传 number | 自定义输入可保存证明不是“只加项次白名单”；普通小数 numeric token 不承担保真且按契约拒绝/兼容策略处理 | |
| TC-18 | AC-2/3 | P0 | `field_type` 与 `fieldType` 两种冻结字段格式 | 相同输入原文分别走两种元数据格式保存 | 分类与逐字保真结果相同 | |
| TC-19 | AC-4 | P0 | `FORMULA`、`LIST_FORMULA`及另一种 `*_FORMULA` | 计算并尝试 numeric token 保存 | 三类均是公式语义；过程 12 位、结果 9 位、numeric token 400 | |
| TC-20 | AC-5 | P0 | 一个请求含合法输入与后置非法公式 | 保存后检查合法字段是否被局部写入 | 整个事务失败；合法输入也未产生部分写 | |
| TC-21 | AC-6/8 | P0 | 产品卡片有多个组件和小计公式 | 参数化小计专用入口为 7，保持公式/总额为 9/8 | 仅产品小计按 7 位变化；公式单元格和报价总额不受影响 | |
| TC-22 | AC-7/8 | P0 | 多 lineItems 报价汇总 | 参数化报价总额专用入口为 8，保持公式/小计为 9/7 | 仅 quotation `originalAmount/totalAmount` 边界按 8 位变化；行内公式/卡片小计不受影响 | |
| TC-23 | AC-11 | P1 | 三视图及 Excel/快照数据组装 | 对相同数据比对 UI、快照 JSON、Excel 数据源 | 输入原文不得被显示格式反向覆盖；结果值按各自边界一致 | |
| TC-24 | AC-12 | P1 | 迁移前后 schema dump 或目标对象清单 | 对比本任务业务代码前后 DDL/Flyway/实体映射 | 本任务新增表/列/索引/约束/migration 均为 0；V385 21 列定义未变 | |
| TC-25 | 输入边界 | P1 | `INPUT_NUMBER` decimal string | 保存 `"+1.2300"`、`"-0.0000"`、14 位整数+12 位小数、非法科学计数法/非数值 | 合法输入按明确载体策略逐字保留；非法格式 400 可定位；不得经浮点 | |
| TC-26 | fallback | P0 | componentData 中小计分别为 `null`、`""`，公式引用组件小计 | 构建 draft payload 并截获发送前 JSON；分别执行 null/空串两组 | fallback 只能是 decimal string `"0"` 或保持 null/空串的约定值；请求中不得出现 numeric token `0`；后端不因 fallback 误报 | |
| TC-27 | AC-4 | P0 | 链式公式 A=`1/3`，B=`A*3`；可观测工作上下文与对外结果 | 前后端分别计算 A、B，并检查 A 的工作缓存、A 的对外值、B 的输入和结果 | A 工作值 `0.333333333333`，A 对外值 `0.333333333`；B 必须引用 12 位 A 而非 9 位对外值，前后端结果一致 | |
| TC-28 | AC-6/8 | P0 | 两列金额先形成列级/组件汇总，再形成产品卡片最终小计 | 将产品小计 scale 设为 7；构造只有第 8~12 位累加后才影响最终舍入的两列值 | 列级和组件中间值仍保留 12 位；只在产品卡片最终小计边界舍入 7 位；不得每列/每组件提前 7 位后再相加 | |

## 4. 冻结元数据复合键与 N+1 专项

测试数据至少包含 4 个 line item：

| lineItem | templateId | componentId | 字段 `共享字段` 的冻结类型 | 目的 |
|---|---|---|---|---|
| L1 | T1 | C1 | `INPUT_NUMBER` | 基准输入分类 |
| L2 | T1 | C2 | `FORMULA` | 同模板不同组件 |
| L3 | T2 | C1 | `FORMULA` | 同 componentId 跨模板语义不同，阻断只按 componentId 建索引 |
| L4 | T2 | C3 | `INPUT_TEXT` | 第二模板不同组件 |

执行要求：

- L1 的 `共享字段="1.2300"`成功，L2/L3 的公式 numeric token 分别失败，L4 文本不做十进制规整。
- 元数据加载必须一次批量完成，并以 `(templateId, componentId)` 分发；不得只以 componentId 覆盖。
- N 组与 2N 组复制 rowData/lineItems 后，统计 SQL 数量必须相同。至少记录模板快照/组件字段相关 SQL 的语句数和参数集合。
- 静态复核新增 `for`、`forEach`、`stream()` 循环体中无 repository 调用、查询执行器或触发懒加载的 getter。

## 5. 失败基线与正式执行顺序

1. 冻结当前 HEAD、前后端 diff hash、测试文件清单。
2. 先跑新增 P0 定向测试，确认开发前至少由真实缺口失败；禁止用无法编译以外的无关环境失败冒充业务红灯。
3. 后端完成后先执行：字段分类单测、真实 HTTP 生命周期、零部分写、复合键/N+1、scale helper 及生产入口、schema 回归。
4. 前端完成后执行：lossless JSON、draft payload、公式黄金、产品小计、报价总额、三视图、TypeScript、Vite transform。
5. 联调执行真实 DRAFT 保存、刷新、重开和三视图；浏览器 Network 保存原始 request/response artifact。
6. 产生 `test-report.md`，逐条填写 AC-1~AC-12、TC-01~26、缺陷、命令、Tests run/failure/error/skip 和数据库证据。

## 6. 主线审核判据

- AC-1~AC-12 每条均有动态或数据库证据，P0 不得 skip。
- 必须同时证明输入值“逐字保留”和公式结果“数值规整”，不能只做 `BigDecimal.compareTo==0` 或数值相等断言。
- 必须有真实 HTTP 保存草稿证据；private/helper 单测仅作补充。
- `项次`白名单成功但任意自定义 `INPUT_NUMBER` 仍失败，整体验收失败。
- 两个独立常量存在，但改变 scale 后不能分别影响真实产品小计和报价总额生产入口，整体验收失败；helper 的可选参数测试不能单独证明生产调用路径解耦。
- 链式公式 B 使用 A 的 9 位对外值而不是 12 位工作值，或列级/组件中间汇总提前使用产品小计 scale，整体验收失败。
- 多 template/component 数据若只按 componentId 分类、出现语义覆盖或 SQL 随 N 增长，整体验收失败。
- component subtotal `null`/`""` fallback 发送 numeric token `0`，整体验收失败。
- 出现新 DDL/Flyway、冻结读写入、公式 numeric token 被放行或新增浮点精度链，整体验收失败。
