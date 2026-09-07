task-260907 · B-16 `errors[].value` 实测（2026-09-07，临时端口 8098，🚦 无任何 flyway 绕过参数）

── 约束① 保留原 4 参构造器 ───────────────────────────────────────────
DsValidationError 现有两个构造器：
  4 参（原签名，一字未动）→ 内部委托 5 参、value 传 null
  5 参（新增，带 value）
全仓 11 个 new DsValidationError(...) 调用点：
  DatasetImportValidator  6 处（其中 3 处升到 5 参：行级 err() / 主键重复 / 轴值未登记）
  DatasetMaintenanceService 5 处（**一行未改**，继续走 4 参 ⇒ value=null ⇒ NON_NULL 下不出现
                                  ⇒ 保存端 400 响应逐字节不变）
  QuotationImportService  1 处（B-15 跨客户，升到 5 参）

── 约束② 维护端 A/B 对照（证明「没多拦、没改形状」）──────────────────
POST /api/cpq/dataset/quote/import，同一份文件、同一条错误：
  顶层键   ['code','data','message'] → ['code','data','message']   相同
  code     400 → 400                                              相同
  message  逐字相同
  errors   1 条 → 1 条
  字段名   ['column','reason','row','sheet']
        → ['column','reason','row','sheet','value']
        新增 ['value']   🚨 丢失/改名：无
  既有字段逐个比对 {sheet:True,row:True,column:True,reason:True} → 全部未变
  改动后 {"sheet":"客户料号","row":2,"column":"客户编号","value":"8000142",
          "reason":"客户编号未在客户档案中登记"}

── 新端点 api.md §2 形状 ─────────────────────────────────────────────
字段名集合 ['columnLabel','reason','rowNum','sheetName','value']
域外客户编号：2/2 条带 value="8000142"
组合负例    ：3/3 条带 value（'8000142' / 'CUST-0001' / '8000142'）

── null 分支：拿不到就留 null，🚫 不编 ────────────────────────────────
三类「没有对应单元格」的错误，全部【没有 value 键】（0/3 带 value）：
  第0行 sheet 级   sheet「我不属于这个数据集」不属于报价数据数据集
  第1行 表头级     表头列名与规范不一致：缺少「降价次数」
  第2行 必填为空   必填项为空
⇒ 前端渲染该列必须容忍缺键。

── 专项：value 取的是【用户填的原值】，不是被改写后的值 ─────────────
产品分类列（categoryRef）在校验通过时会被改写成 code。报错路径实测：
  第2行 [产品分类] value='这个分类不存在XYZ'   ← 用户原文，不是 code
  第3行 [类型]     value='零部件'
📌 之所以专门验这条：它是我写在 err() javadoc 里的一句断言。
   「注释里写了」不等于「代码是这样的」——取值时机若挪到改写之后，
   用户会看到一个自己没填过的编码，而且不报错。

── 回归 ──────────────────────────────────────────────────────────────
AC-15 结构层 sheets 与基线逐字段相同 : True
三个新端点未登录 401（非 500）
B-15 不误伤：合法文件仍 SUCCESS
D-26 + D-31 仍成立：lineItemsCount=3、quotationNumber=QT-20260907-0491
