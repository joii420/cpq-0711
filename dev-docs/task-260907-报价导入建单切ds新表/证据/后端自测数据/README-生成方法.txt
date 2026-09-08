task-260907 后端自测用的 16 sheet 报价 Excel（openpyxl 生成，非手工）。

为什么不用 dev-docs/task-260902/「报价 - 数据导入与表格建表.xlsx」：
  它是【设计沟通用模板】，第 2 行是填写说明文字（如「零件/外购件」当值填在类型列），
  导入会被 Phase 1 判 59 处问题整份拒收 —— 那验的是拒收路径，验不到 SUCCESS 路径。

各文件用途：
  01  合法文件。物料 2 行（T260907-M1/M2）+ 客户料号 3 行（M1 有两条客户料号 → AC-21）
      其余 14 sheet 只有表头 → 顺带验 AC-17「空 sheet 一行不动」
  02  客户编号 8000142（不在 customer.code）→ AC-4 整份拒收 + 16 表 count 逐表相等
  03  同一份文件里 CUST-0004 与 CUST-0001 并存 → AC-3 素材。
      ⚠️ 当前【会被放行】：AC-3 的拒收逻辑属 B-14，阻塞于上游 customer_no DDL，本期未实现。
  04/05  物料BOM 带 1 行数据，05 只改了「组成数量」2→3
      → 连导 04、04、05 三次可验 CREATED / UNCHANGED / UPGRADED 三态（AC-12）
  06  GET /api/cpq/dataset/quote/sheets 的响应快照，生成脚本用它取 13 张带版本表的中文列名

⚠️ 01/04/05 会往共享库 cpq_db_0724 写入前缀为 T260907- 的行。
   跑完若要清理（前缀化、命中面明确）：
     DELETE FROM ds_quote_customer_part WHERE customer_product_no LIKE 'T260907-CP%';
     DELETE FROM ds_quote_material_bom  WHERE material_no LIKE 'T260907-%';
     DELETE FROM ds_quote_material      WHERE material_no LIKE 'T260907-%';
   🚫 不要连带删 quotation —— 那属 §3.2 红线，须单独呈报。

────────────────────────────────────────────────────────────────
2026-09-07 返工后追加（D-26 批次口径 / D-31 quotationNumber）

  07  「客户料号」sheet 只有表头 → batchParts=[]（键在、数组空）
      → 建单返 200 + lineItemsCount=0（AC-18），🚫 不是 400/500

⚠️ D-26 之后，建单只认 import_record.metadata.batchParts 里的料号。
   ⇒ 本改动【之前】建的 import_record（metadata 里没有 batchParts 键）
     调 create-quotation 会返 400「请重新导入一次」，这是刻意的：
     当成空清单会静默建出 0 行单，用户看到「导入明明成功、建出来却是空的」且不报错。
   ⇒ 复现任何建单场景，都要用返工后的代码重新导一次，不要复用老的 importRecordId。
