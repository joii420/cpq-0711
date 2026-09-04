import { chromium } from '/home/joii/project/cpq/.claude/worktrees/task-260819-sql-view-builder/cpq-frontend/node_modules/playwright/index.mjs';
const BASE='http://localhost:5191', OUT='/tmp/f30shots';
function grp(ds, table, gname){
  return { groupKey:table.toUpperCase(), groupName:gname+' ('+table+')', groupKind:'MAIN', dims:[], conflict:false, conflictReason:null, dialect:ds,
    fields:[{sourceNodeKey:table,sourceColumn:'c',displayName:'列A',dataType:'TEXT',roles:[],viewColumn:'c',lookupLib:null,isCore:false,elemKey:false}]};
}
const VARIANTS={QUOTE:8,COST_BASIC:7,COST_DETAIL:15};
const b=await chromium.launch({channel:'chrome'});
const p=await (await b.newContext({viewport:{width:1700,height:1050}})).newPage();
p.on('console',m=>{if(m.type()==='error'&&!/deprecated|Static function/.test(m.text()))console.log('[err]',m.text().slice(0,160));});
await p.route('**/api/cpq/components/*/builder', r=>r.fulfill({status:200,contentType:'application/json',
  body:JSON.stringify({viewState:'NEW',builderConfig:null,isLegacyHandwritten:false,isStale:false,sqlTemplate:null})}));
await p.route('**/api/cpq/components/*/builder/compile', r=>r.fulfill({status:200,contentType:'application/json',
  body:JSON.stringify({sql:'SELECT 1',declaredColumns:[],requiredVariables:[],grain:[],rewriterCompatible:true,warnings:[]})}));
await p.route('**/api/cpq/components/*/builder/inspect', r=>r.fulfill({status:200,contentType:'application/json',body:JSON.stringify({blocked:false,items:[]})}));
await p.route('**/api/cpq/config/semantic-graph/field-tree*', r=>{
  const u=new URL(r.request().url()); const ds=u.searchParams.get('dialect'); const tt=u.searchParams.get('tabType');
  // COST_DETAIL 的「主件」被故意做成"本数据集没有"→ 触发 AC-115③ 的空态文案分支
  const missing = (ds==='COST_DETAIL' && tt==='主件');
  const avail = ds==='COST_DETAIL' ? ['材质元素','零件','费用类','BOM 树'] : ['主件','材质元素','零件','外购件','费用类','BOM 树'];
  r.fulfill({status:200,contentType:'application/json',body:JSON.stringify({
    tabType:tt, variantKey:'', anchorDesc:'stub', availableTabTypes:avail, switches:[],
    variants: tt==='费用类' ? Array.from({length:VARIANTS[ds]||0},(_,i)=>({key:'v'+i,label:'来源'+(i+1),hint:''})) : [],
    groups: missing ? [] : [grp(ds,'ds_'+ds.toLowerCase()+'_material','物料')] })});
});
await p.goto(BASE+'/login');
await p.locator('input[placeholder="用户名或邮箱"]').fill('admin');
await p.locator('input[placeholder="密码"]').fill('Admin@2026');
await p.locator('button[type="submit"]').click();
await p.waitForURL(/\/(dashboard|customers|quotations|system|products|change-password)/,{timeout:20000});
await p.goto(BASE+'/components-raw'); await p.waitForLoadState('networkidle');
await p.locator('.cmm-master').getByText('页签组件',{exact:false}).first().click({force:true});
await p.waitForTimeout(1200);
await p.locator('.cmm-card').first().click(); await p.waitForTimeout(2000);
await p.locator('.ant-tabs-tab',{hasText:'取数配置'}).first().click(); await p.waitForTimeout(2200);

// ---- 费用类「数据来源」选项数随数据集变（🚫 不写死）----
for(const [seg,ds] of [['报价','QUOTE'],['基础核价','COST_BASIC']]){
  await p.locator('.ant-segmented-item-label',{hasText:seg}).click(); await p.waitForTimeout(900);
  await p.locator('.svb-recipe-bar .ant-select').first().click(); await p.waitForTimeout(700);
  await p.locator('.ant-select-dropdown:visible .ant-select-item-option',{hasText:'费用类'}).first().click();
  await p.waitForTimeout(1400);
  const sels=p.locator('.svb-recipe-bar .ant-select');
  const n=await sels.count();
  let cnt='(无数据来源下拉)';
  if(n>1){ await sels.nth(1).click(); await p.waitForTimeout(700);
    cnt=await p.locator('.ant-select-dropdown:visible .ant-select-item-option').count(); await p.keyboard.press('Escape'); }
  console.log(`数据来源选项数 [${ds} · 费用类] = ${cnt}（期望 ${VARIANTS[ds]}，来自 stub 的 variants）`);
  await p.waitForTimeout(400);
}
// ---- AC-115③ 空态文案（COST_DETAIL 无「主件」）----
await p.locator('.ant-segmented-item-label',{hasText:'明细核价'}).click(); await p.waitForTimeout(2000);
const left=await p.locator('.svb-pane.left').innerText();
console.log('AC-115③ 空态左栏 =', left.replace(/\n+/g,' | ').slice(0,300));
const opts=await (async()=>{ await p.locator('.svb-recipe-bar .ant-select').first().click(); await p.waitForTimeout(700);
  const o=await p.locator('.ant-select-dropdown:visible .ant-select-item-option').evaluateAll(els=>els.map(e=>({t:e.innerText.trim(),d:e.className.includes('disabled')})));
  await p.keyboard.press('Escape'); return o;})();
console.log('AC-115③ 下拉 =', JSON.stringify(opts));
await p.screenshot({path:OUT+'/25-empty-state.png'});
await b.close();
