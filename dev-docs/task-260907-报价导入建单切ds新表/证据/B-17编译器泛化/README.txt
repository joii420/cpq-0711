task-260907 · B-17 编译器泛化（价格函数节点解析）—— 独立成立性证据
2026-09-07　🚦 全程未起服务落库；语义图零变化；V423 未应用

── 结论 ────────────────────────────────────────────────────────────────
在【没有 V423、语义图零变化】的前提下：
  · 21 个存量组件全部编译成功（21/21，0 失败）
  · 改动前 / 改动后产物【逐文件相同】，有差异 0 / 21
⇒ 编译器泛化是纯改进，不依赖任何图数据变更，可独立保留。

── 回归对象怎么选的 ────────────────────────────────────────────────────
SQL: component_sql_view WHERE builder_version IS NOT NULL
     AND builder_config::text LIKE '%FUNC_ELEMENT_PRICE%'
= 21 个（其中 priceStrategy 非空 10 个；含 1 个真实组件 COMP-2249「材质元素」，
  其余 20 个是 SQLVB-TEST-price-* 测试件）

── 方法（为什么这样验才算数）────────────────────────────────────────────
不是"读代码觉得没影响"，也不是"跑一下没报错"：
把每个组件【库里存的 builder_config】原样 POST 进
    POST /api/cpq/components/{componentId}/builder/compile
比对返回体的 sql / declaredColumns / grain / requiredVariables / warnings 全字段。
⇒ 这是编译器的真实产物，不是中间状态。

01-改动前编译产物-21组件/  改代码【之前】的 21 份
02-改动后编译产物-21组件/  改代码【之后】的 21 份（语义图未动）
capture.py                 采集脚本（可复跑）
diff 命令：逐文件 diff 两目录，输出为空即通过

⚠️ capture.py 里有一行是踩坑后补的：
    urllib.request.ProxyHandler({})
本机 shell 设了 http_proxy=127.0.0.1:7890，urllib 会照用 → 502 Bad Gateway。
curl 侧一直用 --noproxy '*' 规避，换成 Python 客户端时同一个坑会重踩一次。

── 改了什么 ────────────────────────────────────────────────────────────
SemanticCompiler.java：
 ① 删除常量 PRICE_FUNC_NODE_KEY = "FUNC_ELEMENT_PRICE"
    —— 它把「价格函数」这个**角色**钉死成**某一个具体节点**。
    字节码级复核：javap -c 常量池里 "FUNC_ELEMENT_PRICE" 出现 0 次
    （源码里还剩 3 处，全是解释性注释，不进字节码）
 ② resolvePricePlan：edgesFrom(anchor).filter(PRICE).findFirst()
    → 按 builder_config 里列引用的函数节点【精确匹配】PRICE 边。
    findFirst 在同一锚点挂多条 PRICE 边时按遍历顺序碰运气，取错也不报错
    —— 与 refreshSnapshotsByComponent 的 firstResult()、semantic_tab_view
       三段坐标只用两段，是同一族反模式。
    新增三个明确错误码，取代静默行为：
      COMPILE_PRICE_EDGE_DUPLICATED  同一锚点→同一函数节点多条边
      COMPILE_PRICE_EDGE_NOT_FOUND   选了 FUNCTION 列却没有对应 PRICE 边
                                     （原先会静默落进普通列分支，报一个不相干的错）
      COMPILE_PRICE_MULTI_FUNC       一个组件同时选两个价格函数的列
 ③ isPriceColumn(plan, col)：原实现【收了 plan 参数却没用】，只比常量。
    现改为 plan.funcNode.nodeKey.equals(col.sourceNodeKey)。
    「参数在签名里但没被使用」是静默失效的温床。

── 未做（随 AC-8 改判撤回）─────────────────────────────────────────────
V423 新建 FUNC_CUSTOMER_ELEMENT_PRICE 节点 —— 已作废，改走并发会话的 V425
（给 f_material_element_price 的 candidate_materials 补 ds_quote_* 支）。
⇒ 不会出现第二条 PRICE 边 ⇒ 「两条边并存」的阶段 2 回归不再需要。
