/* 语义图管理页原型 · 共享数据（两个形态的页面都读这一份，避免漂移）
   来源：原型-取数配置器.html 的 deriveModel() 真实输出 —— 13 节点 / 16 边 / 6 页签，非编造。*/
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
 {n:'来料固定加工费',tbl:'unit_price',dims:['投入料号'],cols:9,usedBy:['费用类 · 主源'],kind:'SHEET'},
 {n:'来料其他费用',tbl:'unit_price',dims:['投入料号'],cols:6,usedBy:[],kind:'SHEET',orphan:'尚未挂到任何页签视图 —— 新增 Sheet 后忘记挂页签，此处立刻可见'}];
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
 {id:'E06',from:'物料与元素BOM',to:'材质库',kind:'LOOKUP',on:'ebi.material_part_no = mr.code',tabs:['材质元素'],card:'MANY_TO_ONE',fb:0,assert:'ok'},
 {id:'E07',from:'物料与元素BOM',to:'物料主档',kind:'LOOKUP',on:'ebi.material_part_no = mm2.material_no',tabs:['材质元素'],card:'MANY_TO_ONE',fb:1,assert:'ok'},
 {id:'E08',from:'物料与元素BOM',to:'元素库',kind:'LOOKUP',on:'ebi.component_no = el.element_code',tabs:['材质元素'],card:'MANY_TO_ONE',fb:0,assert:'ok'},
 {id:'E09',from:'物料与元素BOM',to:'价格策略 f_material_element_price',kind:'PRICE',on:"cep.element_code = ebi.component_no AND cep.material_no = <hf_part_no 表达式>",tabs:['材质元素'],card:'MANY_TO_ONE',fb:null,assert:'ok'},
 {id:'E10',from:'自制加工费',to:'电镀费用',kind:'SUB',on:"pl.code = up.code AND pl.price_type = 'PLATING'",tabs:['零件'],card:'MANY_TO_ONE',fb:null,assert:'ok'},
 {id:'E11',from:'自制加工费',to:'物料BOM',kind:'SUB',on:'ab.material_no = up.finished_material_no AND ab.component_no = up.code',tabs:['零件'],card:'MANY_TO_ONE',fb:null,assert:'ok'},
 {id:'E12',from:'自制加工费',to:'物料主档',kind:'LOOKUP',on:'up.code = mm.material_no',tabs:['零件'],card:'MANY_TO_ONE',fb:0,assert:'ok'},
 {id:'E13',from:'自制加工费',to:'工序库',kind:'LOOKUP',on:'up.operation_no = pm.process_no',tabs:['零件'],card:'MANY_TO_ONE',fb:0,assert:'ok'},
 {id:'E14',from:'物料BOM',to:'组成件其他费用',kind:'SUB',on:'co.finished_material_no = mbi.material_no AND co.code = mbi.component_no',tabs:['外购件'],card:'MANY_TO_ONE',fb:null,assert:'ok'},
 {id:'E15',from:'物料BOM',to:'物料主档',kind:'LOOKUP',on:'mbi.component_no = mm.material_no',tabs:['外购件','BOM 树'],card:'MANY_TO_ONE',fb:0,assert:'ok'},
 {id:'E16',from:'物料BOM',to:'工序库',kind:'LOOKUP',on:'mbi.operation_no = pm.process_no',tabs:['外购件'],card:'MANY_TO_ONE',fb:0,assert:'ok'},
 {id:'E17',from:'来料固定加工费',to:'物料主档',kind:'LOOKUP',on:'up.code = mm.material_no',tabs:['费用类'],card:'MANY_TO_ONE',fb:0,assert:'待跑'}];
const TABS=['主件','材质元素','零件','外购件','费用类','BOM 树'];
const KINDLABEL={GRAIN:['一对多','p-info','JOIN，页签按目标 grain 展开行'],SUB:['多对一','p-grey','粒度更粗 → 相关标量子查询'],
  SAME:['同粒度','p-grey','同表同粒度，直接并列'],JOIN:['多对一','p-grey','收窄用 JOIN（客户料号关系）'],
  LOOKUP:['多对一','p-lk','查名 LEFT JOIN，多源按 fallbackOrder 生成 COALESCE'],PRICE:['多对一','p-fn','表函数，原子组']};

