-- V317__element_master.sql（注：并发会话已占 V316=material_master_production_no，本迁移避让至 V317）
-- 元素/组成项字典主表 element（task-0708 材质库规范化 · B1）。
-- element_code 存"符号"(Ag/Cu/SnO2/H70…)，是定价 join 键，必须与 costing_element_price.element_code 对齐，
-- 不是 Excel 里的数字"元素编号"(那是内部字典号，落 element_no 留存备用)。

CREATE TABLE IF NOT EXISTS element (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    element_code  VARCHAR(32)  NOT NULL UNIQUE,   -- 元素符号 Ag/Cu/SnO2/H70…(定价 join 键)
    element_name  VARCHAR(64)  NOT NULL,          -- 中文名(字典已知则中文,未知回退=符号)
    element_no    VARCHAR(32),                    -- Excel 元素编号 10001(内部字典号,当前无消费方,留存备用)
    status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_element_status CHECK (status IN ('ACTIVE','INACTIVE'))
);
COMMENT ON TABLE element IS '元素/组成项字典(符号=定价join键, 中文名展示, 元素编号留存)';

-- 中文字典 seed：覆盖 材质库.xlsx 中可命名的元素符号；纯数字"元素"(191/206/…721)是脏数据不 seed。
-- element_no 留空(NULL)，由导入按符号 upsert 时回填。ON CONFLICT DO NOTHING 保证幂等 + 不覆盖已有。
INSERT INTO element (element_code, element_name) VALUES
  ('Ag','银'),   ('Cu','铜'),   ('Ni','镍'),
  ('Al','铝'),   ('Fe','铁'),   ('Sn','锡'),
  ('Zn','锌'),   ('Cr','铬'),   ('Mn','锰'),
  ('Si','硅'),   ('P','磷'),    ('C','碳'),
  ('Be','铍'),   ('Cd','镉'),   ('Ce','铈'),
  ('In','铟'),   ('Ir','铱'),   ('Pt','铂'),
  ('Pd','钯'),   ('W','钨'),    ('Au','金'),
  ('SnO2','二氧化锡'), ('ZnO','氧化锌'), ('CdO','氧化镉'),
  ('WC','碳化钨'),     ('H70','黄铜H70'), ('DC04','冷轧钢DC04'),
  ('Ni36','铁镍合金Ni36'), ('Ni42','铁镍合金Ni42'), ('不锈钢','不锈钢')
ON CONFLICT (element_code) DO NOTHING;
