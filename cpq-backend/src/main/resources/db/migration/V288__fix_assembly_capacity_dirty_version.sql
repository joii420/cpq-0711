-- V288: 修正组装加工费 capacity 历史脏数据（料号 3120012004）
-- 背景: 解析器重名表头 bug 导致旧导入把工序名称(焊接/铆接)当编码存入;
--       表头修正后重导生成 Z350/Z029 新组,但旧组(per-process 分组)未被下线 → 4 行全 is_current=true。
-- 目标终态: Z350/Z029 升版到 2001(current); 焊接/铆接 降为 2000 历史(is_current=false)。
-- 幂等: 按主键 id 精确定位,重复执行无副作用。

-- 1) 旧版 焊接/铆接 整组下线(保留为 2000 版历史)
UPDATE capacity SET is_current = false
WHERE id IN (
  '7accbaff-39fa-4833-8a98-a196b402f746',  -- 焊接
  '10983bb5-6fee-47f0-969d-663d0673bc90'   -- 铆接
);

-- 2) 新导入 Z350/Z029 升版到 2001
UPDATE capacity SET calc_version = '2001'
WHERE id IN (
  '51ecedb1-02de-4557-80ae-f331b43b3a56',  -- Z350
  '55d8b2e2-33cc-4fed-a50a-83182cab1475'   -- Z029
);
