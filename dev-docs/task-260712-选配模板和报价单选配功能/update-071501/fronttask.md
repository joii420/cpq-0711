# fronttask.md — 前端任务（update-071501：三模板统一到「客户产品分类」轴）

> 依据：`优化需求说明.md §9`（澄清备案）+ `api.md`。本次前端 = **换字段 + 客户表单加分类 + 报价单创建分类改只读带出**，无新页面、无渲染管线改动。
> 开发前必读：`CLAUDE.md`「UI 交互规范 / 修改后强制自检」；本目录 `api.md`。
> 全程在 worktree 分支内开发；前端自检复用主工作区 5174（worktree 自检坑见记忆 `cpq-worktree-frontend-selfcheck`）。

---

## 0. 现状锚点（改动前先读）

| 对象 | 文件 | 关键点 |
|---|---|---|
| 客户管理 | `cpq-frontend/src/pages/customer/CustomerManagement.tsx` | 用 `industryService.listActive()`；`openDrawer`/回填 effect @L85-102；`handleSave` @L104 |
| 选配模板管理 | `src/pages/config/SelTemplateManagement.tsx` | `industryService`；`RESERVED_INDUSTRIES` @L74；`industryCode` 贯穿；列/下拉/upsert |
| 报价单创建表单 | `src/pages/quotation/QuotationCreateForm.tsx` | 产品分类手选（默认"默认分类"）@L94-110；match/costing effect @L134-211；只读 readOnly 已有 |
| 导入向导 | `src/pages/quotation/BasicDataImportV5Wizard.tsx` | `customers:{id,name}[]`；Step3 渲染 `QuotationCreateForm` @L468 |
| 导入壳 | `src/pages/quotation/BasicDataImportV5ToQuotation.tsx` | `customerService.list` map 仅取 `{id,name}` @L37-42 |
| 选配 service | `src/services/selTemplateService.ts` | `upsert({industryCode})`；`effective(customerNo)` |
| 产品分类 service | `src/services/productCategoryService.ts` | `list('ACTIVE')`（**复用**，已存在） |
| 客户 service | `src/services/customerService.ts` | list/getById/create/update |
| 选配抽屉（不改，回归） | `src/pages/quotation/ConfigureProductDrawer.tsx` | 调 `selTemplateService.effective(customerNo)`；不读 resolvedIndustryCode（grep 确认） |

---

## F1. 客户管理：新建/编辑加「产品分类」（必填 + 默认默认分类）

文件：`CustomerManagement.tsx`

1. 引入 `import { productCategoryService, type ProductCategory } from '../../services/productCategoryService';`
2. 加状态：`const [categories, setCategories] = useState<ProductCategory[]>([]);` 并在挂载 effect 拉取：
   ```tsx
   useEffect(() => {
     productCategoryService.list('ACTIVE').then(res => setCategories(res?.data ?? [])).catch(() => {});
   }, []);
   ```
3. **表单加"产品分类"Select**（放在行业选择附近）：
   ```tsx
   <Form.Item name="productCategoryId" label="产品分类"
     rules={[{ required: true, message: '请选择产品分类' }]}>
     <Select showSearch optionFilterProp="label" placeholder="请选择产品分类"
       options={categories.map(c => ({ value: c.id, label: c.name }))} />
   </Form.Item>
   ```
4. **新建默认"默认分类"**：`openDrawer()` 新建分支（`!customer`）里，`form.resetFields()` 后预填：
   ```tsx
   const def = categories.find(c => c.name === '默认分类');
   if (def) form.setFieldsValue({ productCategoryId: def.id });
   ```
   （注意时序：categories 已加载；若未加载完，可在 categories 到位的 effect 里对"新建且空"补默认。）
5. **编辑回填**：@L88-97 `form.setFieldsValue({...})` 增 `productCategoryId: editingCustomer.productCategoryId`。
6. `handleSave` 无需改（`values` 已含 `productCategoryId`，透传 create/update）。
7. **列表不加列、不加筛选**（D5）。`industryService` 及行业字段**保留不动**（D8）。

`customerService.ts` 类型：`CreateCustomerRequest`/`Customer` 增 `productCategoryId?: string`。

---

## F2. 选配模板管理：归属「行业」→「产品分类」

文件：`SelTemplateManagement.tsx`（换轴，逐点改）

1. `import { industryService }` → `import { productCategoryService, type ProductCategory }`。
2. 状态：`industries` → `categories: ProductCategory[]`；`fetchIndustries` → `fetchCategories`（`productCategoryService.list('ACTIVE')`）。
3. **移除 `RESERVED_INDUSTRIES`**（`__DEFAULT__`/`__GLOBAL__` 哨兵，@L74-78）——换轴后归属改真实产品分类；"默认分类"本身就是一条 `product_category`，会出现在下拉里正常可选（承接原 `__DEFAULT__` 兜底角色，D10）。
4. `industryNameMap` → `categoryNameMap`（由 categories 建 id→name）。
5. `industryOptions`/`createIndustryOptions` → `categoryOptions`/`createCategoryOptions`：
   ```tsx
   const categoryOptions = categories.map(c => ({ value: c.id, label: c.name }));
   const usedCategoryIds = new Set(templates.map(t => t.productCategoryId));   // 一分类一套，新建排除已配
   const createCategoryOptions = categoryOptions.filter(o => !usedCategoryIds.has(o.value));
   ```
6. 类型 `TemplateRow.industryCode → productCategoryId`；`IndustryOption` 删除。
7. **归属下拉**（@L366-380）：`name="industryCode"` → `name="productCategoryId"`；label "归属行业" → "产品分类"；`options={editing ? categoryOptions : createCategoryOptions}`；`extra` 文案改"一个产品分类仅可配置一套选配模板；产品分类确定后不可修改"；`notFoundContent="所有产品分类均已配置模板"`。
8. `openEdit` 回填（@L211）：`form.setFieldsValue({ productCategoryId: detail.productCategoryId, ... })`。
9. `handleSave`（@L241）：`selTemplateService.upsert({ productCategoryId: values.productCategoryId, ... })`。
10. 列定义（@L260-268）"归属行业" → "产品分类"，`dataIndex: 'productCategoryId'`，render 用 `categoryNameMap`。
11. 工具栏 `toggle-status`/`delete` 的 `upsert`/`rowLabel`（@L299-322）里 `industryCode` → `productCategoryId`，`industryNameMap` → `categoryNameMap`；删除确认文案"该行业" → "该产品分类"。
12. `handleSaveFailed` 文案"归属行业" → "产品分类"。

`selTemplateService.ts`：`upsert` 请求体类型 `industryCode` → `productCategoryId`；`SelTemplateDTO`/`TemplateRow` 类型同步。

---

## F3. 报价单创建：产品分类改「客户带出 + 只读锁定」

### F3.1 `BasicDataImportV5ToQuotation.tsx`
`customerService.list` 的 map 带上产品分类（@L37-42）：
```tsx
setCustomers(list.map((c: any) => ({ id: c.id, name: c.name, productCategoryId: c.productCategoryId })));
```

### F3.2 `BasicDataImportV5Wizard.tsx`
1. `customers` prop 类型：`{ id: string; name: string; productCategoryId?: string }[]`。
2. Step3 渲染（@L468-479）：找到当前客户，传 `lockedCategoryId`：
   ```tsx
   const customer = customers.find(c => c.id === customerId);
   return (
     <QuotationCreateForm
       customerId={customerId}
       customerName={customer?.name ?? ''}
       lockedCategoryId={customer?.productCategoryId}   // 🆕 客户带出，只读锁定
       value={createForm} onChange={setCreateForm} onValidityChange={setFormValid}
     />
   );
   ```

### F3.3 `QuotationCreateForm.tsx`
1. Props 增 `lockedCategoryId?: string;`。
2. **移除"默认预选默认分类"逻辑**（@L94-110 那段 `find(c => c.name === '默认分类')` 自动填分类的分支删除）；`productCategoryService.list` 仍可保留（用于把 id 显示成分类名）。
3. **初始化 categoryId 为 lockedCategoryId**：
   ```tsx
   useEffect(() => {
     if (lockedCategoryId && value.categoryId !== lockedCategoryId) {
       onChange({ ...value, categoryId: lockedCategoryId });
     }
   }, [lockedCategoryId]);   // eslint-disable-line
   ```
4. **产品分类下拉改只读展示**（@L302-319）：`disabled` 恒为真（`disabled={true}` 或 `disabled={readOnly || !!lockedCategoryId}`）；仍显示分类名。文案 tooltip 改"产品分类由客户绑定决定，如需变更请到客户管理修改客户所属产品分类"。移除 `allowClear`、`onChange`（只读）。
5. **match 报价模板 / 拉核价模板 effect 不变**（@L134-211）——它们依赖 `value.categoryId`，现在 categoryId=lockedCategoryId，自动触发匹配，行为一致。
6. 合法性校验（@L214-219）不变（仍要求 categoryId + customerTemplateId）。

> **其它 callsite 检查**：`grep -rn "QuotationCreateForm" src/` 确认只有 `BasicDataImportV5Wizard` 一处引用；若有其它入口（复制报价单/独立创建），同样传 `lockedCategoryId`。

---

## F4. 前端类型：EffectiveTemplateDTO / 选配抽屉回归

1. 前端 `EffectiveTemplateDTO` 类型（selTemplateService.ts 或 types）：`resolvedIndustryCode` → `resolvedCategoryId`。
2. `grep -rn "resolvedIndustryCode" src/` → 全部改 `resolvedCategoryId`（`ConfigureProductDrawer.tsx` 若未引用则无需动）。
3. `ConfigureProductDrawer.tsx` **不改逻辑**（仍 `selTemplateService.effective(customerNo)`）；仅回归验证：选配抽屉按客户产品分类带出模板、无模板时空态提示正常。

---

## F5. 前端自检清单（宣告完成必附）

- [ ] `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json` → 0 错误。
- [ ] 对每个改动 `.tsx` 跑 `curl -s --noproxy '*' -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/.../X.tsx` → 200（含 CustomerManagement / SelTemplateManagement / QuotationCreateForm / BasicDataImportV5Wizard / BasicDataImportV5ToQuotation）。
- [ ] `grep -rn "industryCode\|resolvedIndustryCode\|RESERVED_INDUSTRIES" src/pages/config/SelTemplateManagement.tsx` → 无残留（用 `/usr/bin/grep -a`）。
- [ ] 手动回归三处（联动后端换轴后）：
  - 客户新建默认"默认分类"、必填拦截、编辑回填与改绑；
  - 报价单创建：产品分类只读带出客户值，报价/核价模板据此自动匹配；
  - 选配抽屉：按客户产品分类带出选配模板，无模板报错提示。
- [ ] 完成宣告含一行「已自检」声明。

> **E2E 说明**：本次**不触及**渲染管线协议文件（`useDriverExpansions`/`QuotationStep2`/`ReadonlyProductCard`/字段类型等），非 AP-44/协议级改动，**E2E 非强制**；但建议跑一遍 `quotation-flow.spec.ts` 确认报价单渲染零回归（可选证据）。
