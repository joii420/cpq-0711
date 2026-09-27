# S-3 · 小单搜索框回归

- 日期：2026-09-26
- 样本：`QT-20260926-0941`，只读详情页，数据库实查 1 个可见产品
- 代码：`fix/task-260926-search-small-quote`，临时 Vite `http://localhost:5390`
- 命令：`PW_BASE_URL=http://localhost:5390 npx playwright test e2e/task260926-small-quote-search.spec.ts --reporter=list`
- 结果：**1 passed（4.8s，整轮 11.2s）**

## 断言

1. 1 产品详情页的 `paging-search-input` 可见。
2. `.ant-pagination` 数量为 0，未出现分页器。
3. 输入该产品销售料号并回车，显示 `匹配 1 条 / 共 1 条`。
4. 输入不存在的料号并回车，显示空态，搜索框仍可见。
5. 只读守卫未拦截任何写请求。

该 spec 为一次性现场验证脚本，未提交到前端测试目录；本报告保留验证结果。
