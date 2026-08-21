/* 语义图管理页原型 · 共享数据（两个形态的页面都读这一份，避免漂移）
   来源：原型-取数配置器.html 的 deriveModel() 真实输出（13 节点 / 16 边），
   2026-08-20 按用户裁决「差异按最新文档为准」补齐到 **17 节点 / 19 边 / 孤儿 4 张**：
     · 17 = 16 个 Q*Handler + MaterialBomMergeHandler，逐个核对 sheetName() 得出，非估算
     · 补入 4 张：来料回收折扣(Q09) + 年降三张(Q08/Q15/Q19，按 N-7 挂空 = 孤儿)
     · 「来料其他费用」由孤儿改挂费用类页签 —— 依据 AC-43「孤儿 4 张 = 年降三张 + 电镀方案」
     · 边按 **多源 COALESCE 合并计数 = 19 条**（与文档一致），展开为 **22 条连接**：
       cg 字段标出 3 组多源查名（ebi_name / ll_name / lqt_name），每组 2 条连接 = 1 条逻辑边 */
/* ===== 真实数据：取自 原型-取数配置器.html 的 deriveModel() 输出 ===== */
const NODES=[
 {n:'物料主档 / 单重',tbl:'material_master',dims:[],cols:6,usedBy:['主件 · 主源'],kind:'SHEET'},
 {n:'客户料号与宏丰料号的关系',tbl:'material_customer_map',dims:[],cols:8,usedBy:['主件'],kind:'SHEET'},
 {n:'物料与元素BOM',tbl:'element_bom_item',dims:['材质料号','元素'],cols:6,usedBy:['材质元素 · 主源'],kind:'SHEET'},
 {n:'元素回收折扣',tbl:'element_bom_item',dims:['材质料号','元素'],cols:3,usedBy:['材质元素'],kind:'SHEET'},
 {n:'物料BOM',tbl:'material_bom_item',dims:['组成件料号'],cols:9,usedBy:['材质元素','零件','外购件 · 主源','BOM 树 · 主源'],kind:'SHEET'},
 {n:'自制加工费',tbl:'unit_price',dims:['零件料号'],cols:5,usedBy:['零件 · 主源'],kind:'SHEET'},
 {n:'成品其他费用',tbl:'unit_price',dims:['要素'],cols:5,usedBy:['主件'],kind:'SHEET'},
 {n:'组成件其他费用',tbl:'unit_price',dims:['组成件料号'],cols:5,usedBy:['外购件'],kind:'SHEET'},
 {n:'电镀费用',tbl:'unit_price',dims:['零件料号','费用类型'],cols:5,usedBy:['零件'],kind:'SHEET'},
 {n:'电镀方案',tbl:'plating_scheme',dims:[],cols:8,usedBy:[],kind:'SHEET',orphan:'现网数据完全孤立（hf_part_no 与 plating_scheme_no 双向全空），属导入侧问题'},
 {n:'组装加工费',tbl:'capacity',dims:['工序号'],cols:7,usedBy:['主件'],kind:'SHEET'},
 {n:'来料固定加工费',tbl:'unit_price',dims:['投入料号'],cols:9,usedBy:['费用类 · 主源'],kind:'SHEET',
   pt:"price_type='INCOMING_MATERIAL_PROCESS'",src:'ll_view（13 个组件）'},
 {n:'来料其他费用',tbl:'unit_price',dims:['投入料号','要素'],cols:6,usedBy:['费用类 · 主源'],kind:'SHEET',
   pt:"price_type='INCOMING_MATERIAL_OTHER'",src:'lqt_view（6 个组件）',
   note:'⚠️ 实测：按 (code, finished_material_no) 6 行/4 键，有 2 组重复 → 必须再带一维（要素/项次）才唯一'},
 /* ↓ 以下 4 张为 2026-08-20 按「17 个导入 handler」口径补齐（用户裁决：差异按最新文档为准）。
    定稿原型 原型-取数配置器.html 的 SHEETS 只登记配置器用得到的 13 张，故此前缺这 4 张。 */
 {n:'来料回收折扣',tbl:'unit_price',dims:['投入料号','直接父件'],cols:7,usedBy:['BOM 树'],kind:'SHEET',
   pt:"price_type='INCOMING_MATERIAL_RECYCLE'",src:'Q09IncomingRecoveryHandler / bom_view 标量子查询',
   thin:'全库仅 1 行数据 —— 基数断言在这种样本量下必然通过，属假阴性盲区'},
 {n:'来料年降',tbl:'annual_discount',dims:['投入料号','年降顺序'],cols:7,usedBy:[],kind:'SHEET',
   pt:"discount_type='INCOMING_MATERIAL'",src:'Q08IncomingAnnualDiscountHandler',
   orphan:'年降三张已按 N-7 移出本期范围（用户第四轮确认），登记在图里但不挂任何页签'},
 {n:'组装加工费年降',tbl:'annual_discount',dims:['工序号','年降顺序'],cols:7,usedBy:[],kind:'SHEET',
   pt:"discount_type='ASSEMBLY_PROCESS'",src:'Q15AssemblyAnnualDiscountHandler',
   orphan:'年降三张已按 N-7 移出本期范围（用户第四轮确认），登记在图里但不挂任何页签'},
 {n:'年降系数',tbl:'annual_discount',dims:['年降顺序'],cols:7,usedBy:[],kind:'SHEET',
   pt:"discount_type='FINISHED'",src:'Q19AnnualDiscountHandler',
   orphan:'年降三张已按 N-7 移出本期范围（用户第四轮确认），登记在图里但不挂任何页签'}];
const LOOKUPS=[
 {n:'物料主档',key:'material_no',kind:'LOOKUP'},
 {n:'材质库',key:'code',kind:'LOOKUP'},
 {n:'工序库',key:'process_no',kind:'LOOKUP'},
 {n:'元素库',key:'element_code',kind:'LOOKUP',note:'= 元素符号 Ag/Cu，不是 element_no'},
 {n:'客户料号关系',key:'material_no',kind:'LOOKUP',note:'固定谓词 customer_no = :customerCode'},
 {n:'价格策略 f_material_element_price',key:'f_material_element_price(:customerCode, :priceBaseDate) cep',kind:'FUNCTION'}];
const EDGES=[
 {id:'E01',from:'物料主档 / 单重',to:'客户料号与宏丰料号的关系',kind:'JOIN',on:'mm.material_no = mcm.material_no',tabs:['主件'],card:'MANY_TO_ONE',fb:null,assert:'ok'},
 {id:'E02',from:'物料主档 / 单重',to:'成品其他费用',kind:'GRAIN',on:'fo.code = mm.material_no',tabs:['主件'],card:'ONE_TO_MANY',fb:null,assert:'na'},
 {id:'E03',from:'物料主档 / 单重',to:'组装加工费',kind:'GRAIN',on:'ca.material_no = mm.material_no',tabs:['主件'],card:'ONE_TO_MANY',fb:null,assert:'na'},
 {id:'E04',from:'物料与元素BOM',to:'元素回收折扣',kind:'SAME',on:'（同表同粒度，无连接键）',tabs:['材质元素'],card:'MANY_TO_ONE',fb:null,assert:'ok'},
 {id:'E05',from:'物料与元素BOM',to:'物料BOM',kind:'SUB',on:'rb.material_no = ebi.material_no AND rb.component_no = ebi.material_part_no',tabs:['材质元素'],card:'MANY_TO_ONE',fb:null,assert:'ok'},
 {id:'E06',cg:'ebi_name',from:'物料与元素BOM',to:'材质库',kind:'LOOKUP',on:'ebi.material_part_no = mr.code',tabs:['材质元素'],card:'MANY_TO_ONE',fb:0,assert:'ok'},
 {id:'E07',cg:'ebi_name',from:'物料与元素BOM',to:'物料主档',kind:'LOOKUP',on:'ebi.material_part_no = mm2.material_no',tabs:['材质元素'],card:'MANY_TO_ONE',fb:1,assert:'ok'},
 {id:'E08',from:'物料与元素BOM',to:'元素库',kind:'LOOKUP',on:'ebi.component_no = el.element_code',tabs:['材质元素'],card:'MANY_TO_ONE',fb:0,assert:'ok'},
 {id:'E09',from:'物料与元素BOM',to:'价格策略 f_material_element_price',kind:'PRICE',on:"cep.element_code = ebi.component_no AND cep.material_no = <hf_part_no 表达式>",tabs:['材质元素'],card:'MANY_TO_ONE',fb:null,assert:'ok'},
 {id:'E10',from:'自制加工费',to:'电镀费用',kind:'SUB',on:"pl.code = up.code AND pl.price_type = 'PLATING'",tabs:['零件'],card:'MANY_TO_ONE',fb:null,assert:'ok'},
 {id:'E11',from:'自制加工费',to:'物料BOM',kind:'SUB',on:'ab.material_no = up.finished_material_no AND ab.component_no = up.code',tabs:['零件'],card:'MANY_TO_ONE',fb:null,assert:'ok'},
 {id:'E12',from:'自制加工费',to:'物料主档',kind:'LOOKUP',on:'up.code = mm.material_no',tabs:['零件'],card:'MANY_TO_ONE',fb:0,assert:'ok'},
 {id:'E13',from:'自制加工费',to:'工序库',kind:'LOOKUP',on:'up.operation_no = pm.process_no',tabs:['零件'],card:'MANY_TO_ONE',fb:0,assert:'ok'},
 {id:'E14',from:'物料BOM',to:'组成件其他费用',kind:'SUB',on:'co.finished_material_no = mbi.material_no AND co.code = mbi.component_no',tabs:['外购件'],card:'MANY_TO_ONE',fb:null,assert:'ok'},
 {id:'E15',from:'物料BOM',to:'物料主档',kind:'LOOKUP',on:'mbi.component_no = mm.material_no',tabs:['外购件','BOM 树'],card:'MANY_TO_ONE',fb:0,assert:'ok'},
 {id:'E16',from:'物料BOM',to:'工序库',kind:'LOOKUP',on:'mbi.operation_no = pm.process_no',tabs:['外购件'],card:'MANY_TO_ONE',fb:0,assert:'ok'},
 {id:'E17',cg:'ll_name',from:'来料固定加工费',to:'物料主档',kind:'LOOKUP',on:'up.code = mm.material_no',tabs:['费用类'],card:'MANY_TO_ONE',fb:0,assert:'待跑'},
 /* ↓ 2026-08-20 实测修正（D-32）：原推定的两条边 E18/E19 已被现网 SQL 推翻，见 README「实测核实」。
    以下 6 条逐条有现网视图为证：ll_view / lqt_view / bom_view 的 JOIN 与标量子查询原文。 */
 {id:'E18',cg:'ll_name',from:'来料固定加工费',to:'材质库',kind:'LOOKUP',on:'mr.code = up.code',tabs:['费用类'],card:'MANY_TO_ONE',fb:1,assert:'ok',src:'ll_view'},
 {id:'E19',from:'来料固定加工费',to:'工序库',kind:'LOOKUP',on:'pm.process_no = up.operation_no',tabs:['费用类'],card:'MANY_TO_ONE',fb:0,assert:'ok',src:'ll_view'},
 /* ⚠️ E20/E21 的存在与否取决于「费用类建模」这个未决问题（见 需求文档 §6 遗留 7）：
    · 分立建模（现网事实：ll_view 13 组件 / lqt_view 6 组件，两个独立视图各一套查名）→ 有 E20/E21 → 19 条边
    · 合并建模（定稿原型现状：price_type IN + main2，一套查名）→ 无 E20/E21 → 18 条边
    本文件按**分立**登记（= 现网事实 + 文档的 19 条口径）；定稿原型按**合并**。裁决前两侧不强行统一。 */
 {id:'E20',cg:'lqt_name',from:'来料其他费用',to:'物料主档',kind:'LOOKUP',on:'mm.material_no = up.code',tabs:['费用类'],card:'MANY_TO_ONE',fb:0,assert:'ok',src:'lqt_view'},
 {id:'E21',cg:'lqt_name',from:'来料其他费用',to:'材质库',kind:'LOOKUP',on:'mr.code = up.code',tabs:['费用类'],card:'MANY_TO_ONE',fb:1,assert:'ok',src:'lqt_view'},
 {id:'E22',from:'物料BOM',to:'来料回收折扣',kind:'SUB',
   on:'r.code = mbi.component_no AND r.finished_material_no = mbi.material_no',
   tabs:['BOM 树'],card:'MANY_TO_ONE',fb:null,assert:'thin',src:'bom_view',
   note:'匹配维度 = 投入料号 + **直接父件**（code=本行子件, finished=本行父件）。unit_price 是版本化表，禁写进带 is_current 的 LEFT JOIN → 必须编译成相关标量子查询。已知取舍：三层以上 BOM 中孙件那行取不到值（它的父不是成品）。'}];
const TABS=['主件','材质元素','零件','外购件','费用类','BOM 树'];
/* D-34 分立建模：费用类一个页签类型对应 **2 个视图声明**（来料固定加工费 / 来料其他费用），
   各自单一 price_type、各自一套查名 —— 与现网 ll_view / lqt_view 逐一对应。
   故「页签类型」6 类，而 semantic_tab_view 表里是 7 行。 */
const TABVIEWS=[{tab:'主件'},{tab:'材质元素'},{tab:'零件'},{tab:'外购件'},
  {tab:'费用类',variant:'来料固定加工费',view:'ll_view · 13 个组件'},
  {tab:'费用类',variant:'来料其他费用',view:'lqt_view · 6 个组件'},
  {tab:'BOM 树'}];
const KINDLABEL={GRAIN:['一对多','p-info','JOIN，页签按目标 grain 展开行'],SUB:['多对一','p-grey','粒度更粗 → 相关标量子查询'],
  SAME:['同粒度','p-grey','同表同粒度，直接并列'],JOIN:['多对一','p-grey','收窄用 JOIN（客户料号关系）'],
  LOOKUP:['多对一','p-lk','查名 LEFT JOIN，多源按 fallbackOrder 生成 COALESCE'],PRICE:['多对一','p-fn','表函数，原子组']};

