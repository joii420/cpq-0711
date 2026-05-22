-- V204: mat_part 加 length / width / height 三列基础物理属性 (2026-05-20)
--
-- 背景:
--   用户要求"料号增加基础属性字段 长/宽/高". 经调研, 长/宽/高 是料号的物理特征
--   (一料号一组值), 应挂在主表 mat_part — 不挂 mat_bom (后者是按 hf_part_no + bom_type
--   多行的 BOM/元素表, 每料号多行, 放尺寸会冗余 + 一致性风险).
--
-- 字段语义:
--   - length / width / height NUMERIC(18,4) (nullable): 长 / 宽 / 高, 单位 mm
--   - 与已有 size_info VARCHAR (历史文本尺寸字段) 互补, 不冲突
--     (size_info 仍可保留以兼容历史数据, 新数据填三个数值列以支持公式计算)
--
-- 协议传播 (零代码改动, 全部走自适应):
--   - mat_part 主数据 API (/api/cpq/master-data/table/mat_part):
--       走 information_schema.columns 动态发现, 新列自动曝露
--   - 前端 MasterDataTableViewerPage.tsx:
--       按后端 metadata 渲染表头, 自动显示新 3 列
--   - BNF 路径 {mat_part.length} / {mat_part.width} / {mat_part.height}:
--       ImplicitJoinRewriter 查 information_schema 自动识别, 公式可直接引用
--   - 无 Hibernate entity (mat_part 走原生 SQL, TableRegistry 注明)
--   - 不改 mat_bom / 不改任何视图 (v_q_part_info_merged 当前未投 size_info,
--     新字段若要进报价单视图按业务需求后续单独 ALTER VIEW)

ALTER TABLE mat_part
    ADD COLUMN IF NOT EXISTS length NUMERIC(18,4),
    ADD COLUMN IF NOT EXISTS width  NUMERIC(18,4),
    ADD COLUMN IF NOT EXISTS height NUMERIC(18,4);

COMMENT ON COLUMN mat_part.length IS 'V204: 长 (mm). 料号物理属性, 用于公式计算 (体积/面积等).';
COMMENT ON COLUMN mat_part.width  IS 'V204: 宽 (mm). 同 length.';
COMMENT ON COLUMN mat_part.height IS 'V204: 高 (mm). 同 length.';
