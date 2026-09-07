import { chromium } from '/home/joii/project/cpq/.claude/worktrees/task-260819-sql-view-builder/cpq-frontend/node_modules/playwright/index.mjs';
const BASE='http://localhost:5191', OUT='/tmp/f30shots';

// ── 契约形状的 stub：故意**三套数据集的分组全部返回**（不按 dataset 过滤），
//    专门用来验前端第二道防线（AC-116：另两套一张都不出现）。
function grp(ds, table, gname){
  return { groupKey: table.toUpperCase(), groupName: gname+' ('+table+')', groupKind:'MAIN', dims:[], conflict:false, conflictReason:null,
    dialect: ds,
    fields:[
      {sourceNodeKey:table, sourceColumn: ds==='QUOTE'?'material_no':'production_no', displayName: ds==='QUOTE'?'销售料号':'生产料号', dataType:'TEXT', roles:['PART_NO','ROW_KEY'], viewColumn:'c1', lookupLib:null, isCore:false, elemKey:false},
      {sourceNodeKey:table, sourceColumn:'material_name', displayName:'品名', dataType:'TEXT', roles:['PART_NAME'], viewColumn:'c2', lookupLib:null, isCore:false, elemKey:false},
    ]};
}
const ALL=[grp('QUOTE','ds_quote_material','物料·报价'), grp('COST_BASIC','ds_cost_basic_material','物料·基础核价'), grp('COST_DETAIL','ds_cost_detail_material','物料·明细核价')];
// COST_DETAIL 故意缺「外购件」，用来触发 AC-115③ 的置灰+空态（生产上三套都齐全，见回报）
const AVAIL={ QUOTE:['主件','材质元素','零件','外购件','费用类','BOM 树'],
              COST_BASIC:['主件','材质元素','零件','外购件','费用类','BOM 树'],
              COST_DETAIL:['主件','材质元素','零件','费用类','BOM 树'] };
const VARIANTS={ QUOTE:8, COST_BASIC:7, COST_DETAIL:15 };

const b=await chromium.launch({channel:'chrome'});
const ctx=await b.newContext({viewport:{width:1700,height:1050}});
const p=await ctx.newPage();
const seen=[];
p.on('console',m=>{if(m.type()==='error'&&!/deprecated/.test(m.text()))console.log('[err]',m.text().slice(0,180));});

await p.route('**/api/cpq/components/*/builder', r=>r.fulfill({status:200,contentType:'application/json',
  body:JSON.stringify({viewState:'NEW',builderConfig:null,isLegacyHandwritten:false,isStale:false,sqlTemplate:null})}));
await p.route('**/api/cpq/config/semantic-graph/field-tree*', r=>{
  const u=new URL(r.request().url()); const ds=u.searchParams.get('dialect')||'(missing)'; const tt=u.searchParams.get('tabType');
  seen.push({dialect:ds,tabType:tt});
  const n=VARIANTS[ds]||0;
  r.fulfill({status:200,contentType:'application/json',body:JSON.stringify({
    tabType:tt, variantKey:'', anchorDesc:'stub 锚点', availableTabTypes:AVAIL[ds]||AVAIL.QUOTE, switches:[],
    variants: tt==='费用类' ? Array.from({length:n},(_,i)=>({key:'v'+i,label:'来源'+(i+1),hint:''})) : [],
    groups: ALL })});
});
// 编译/体检/预览端点不打真实后端 —— 按各自的 Response 形状给全字段（给 {} 会在渲染层
// 读 .length 时崩，那是 stub 缺字段，不是产品缺陷）
const compileBodies=[];
await p.route('**/api/cpq/components/*/builder/compile', r=>{ try{compileBodies.push(JSON.parse(r.request().postData()||'{}').dialect);}catch(e){} return r.fulfill({status:200,contentType:'application/json',
  body:JSON.stringify({sql:'SELECT 1 -- stub', declaredColumns:['c1'], requiredVariables:[], grain:['material_no'], rewriterCompatible:true, warnings:[]})});});
await p.route('**/api/cpq/components/*/builder/inspect', r=>r.fulfill({status:200,contentType:'application/json',
  body:JSON.stringify({blocked:false, items:[]})}));
await p.route('**/api/cpq/components/*/builder/preview', r=>r.fulfill({status:200,contentType:'application/json',
  body:JSON.stringify({rowCount:0, columns:[], rows:[], elapsedMs:1, diagnostics:[]})}));

await p.goto(BASE+'/login');
await p.locator('input[placeholder="用户名或邮箱"]').fill('admin');
await p.locator('input[placeholder="密码"]').fill('Admin@2026');
await p.locator('button[type="submit"]').click();
await p.waitForURL(/\/(dashboard|customers|quotations|system|products|change-password)/,{timeout:20000});
await p.goto(BASE+'/components-raw'); await p.waitForLoadState('networkidle');
await p.locator('.cmm-master').getByText('页签组件',{exact:false}).first().click({force:true});
await p.waitForTimeout(1500);
const card=p.locator('.cmm-card').first();
console.log('card text =', (await card.innerText()).replace(/\n+/g,' / '));
await card.click(); await p.waitForTimeout(2500);
const tabs=await p.locator('.ant-tabs-tab').allInnerTexts();
console.log('TABS:', JSON.stringify(tabs));
await p.locator('.ant-tabs-tab', {hasText:'取数配置'}).first().click();
await p.waitForTimeout(2500);
await p.screenshot({path:OUT+'/20-builder-quote.png'});

// ---- 断言 1：三选一控件存在，且在「页签类型」之前 ----
const segTexts=await p.locator('.ant-segmented-item-label').allInnerTexts();
console.log('AC-115① Segmented 选项 =', JSON.stringify(segTexts));
const order=await p.evaluate(()=>{
  const lbls=[...document.querySelectorAll('.svb-recipe-bar .svb-lbl')].map(e=>e.textContent.trim());
  return lbls;
});
console.log('AC-115① 顶部标签顺序 =', JSON.stringify(order));

// ---- 断言 2：字段面板只出当前数据集的表 ----
async function panelTables(){
  return await p.evaluate(()=>{
    const pane=document.querySelector('.svb-pane.left');
    const t=pane?pane.innerText:'';
    return {
      quote: t.includes('ds_quote_material'),
      cost_basic: t.includes('ds_cost_basic_material'),
      cost_detail: t.includes('ds_cost_detail_material'),
      groups: [...(pane?pane.querySelectorAll('.svb-grp-h'):[])].map(e=>e.textContent.trim()),
      count: (pane?pane.querySelector('.svb-pane-h span').textContent:''),
    };
  });
}
console.log('AC-116 [QUOTE 选中] 面板 =', JSON.stringify(await panelTables(),null,0));

// ---- 断言 4：先选一列，再切数据集 → 必须弹确认 ----
await p.locator('.svb-pane.left .svb-grp-h').first().click();   // 分组默认折叠，先展开
await p.waitForTimeout(600);
await p.locator('.svb-pane.left .svb-fld').first().dblclick();
await p.waitForTimeout(1200);
const selCount=await p.locator('.svb-pane.right').innerText();
console.log('已选输出列区文本(截断) =', selCount.slice(0,160).replace(/\n+/g,' | '));
await p.screenshot({path:OUT+'/21-picked-one-col.png'});

await p.locator('.ant-segmented-item-label', {hasText:'基础核价'}).click();
await p.waitForTimeout(1200);
const dlg=await p.locator('.ant-modal-confirm').count();
const dlgTitle=dlg? await p.locator('.ant-modal-confirm-title').innerText():'(无弹层)';
const dlgBody=dlg? await p.locator('.ant-modal-confirm-content').innerText():'';
console.log('AC-115④ 确认弹层数 =',dlg,'| 标题 =',dlgTitle);
console.log('AC-115④ 正文 =',dlgBody.replace(/\n+/g,' | '));
await p.screenshot({path:OUT+'/22-switch-confirm.png'});
await p.locator('.ant-modal-confirm .ant-btn-primary').click();
await p.waitForTimeout(2000);
await p.screenshot({path:OUT+'/23-cost-basic.png'});
console.log('AC-116 [COST_BASIC 选中] 面板 =', JSON.stringify(await panelTables(),null,0));

// ---- 断言 3：本数据集没有的页签类型 → 置灰 ----
await p.locator('.ant-segmented-item-label', {hasText:'明细核价'}).click();
await p.waitForTimeout(1800);
await p.locator('.svb-recipe-bar .ant-select').first().click();
await p.waitForTimeout(900);
const opts=await p.locator('.ant-select-dropdown:visible .ant-select-item-option').evaluateAll(
  els=>els.map(e=>({text:e.innerText.trim(), disabled:e.className.includes('disabled')})));
console.log('AC-115③ 页签类型下拉 =', JSON.stringify(opts));
await p.screenshot({path:OUT+'/24-tabtype-dropdown.png'});
await p.keyboard.press('Escape');

console.log('field-tree 请求记录 =', JSON.stringify(seen));
console.log('compile 请求体里的 dialect 值 =', JSON.stringify(compileBodies));
await b.close();
