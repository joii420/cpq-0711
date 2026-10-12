-- task-261004: managed units and directed conversion rates.
-- Never derive a reverse rate: every direction is an explicit row.
CREATE TABLE unit_definition (
    id          bigserial PRIMARY KEY,
    code        varchar(32) NOT NULL,
    category    varchar(16) NOT NULL CHECK (category IN ('QUANTITY', 'WEIGHT')),
    enabled     boolean NOT NULL DEFAULT true,
    sort_order  integer NOT NULL DEFAULT 0,
    version     integer NOT NULL DEFAULT 1,
    created_at  timestamptz NOT NULL DEFAULT now(),
    created_by  varchar(64),
    updated_at  timestamptz,
    updated_by  varchar(64)
);
CREATE UNIQUE INDEX uq_unit_definition_code_ci ON unit_definition (lower(btrim(code)));
CREATE INDEX idx_unit_definition_category_enabled ON unit_definition (category, enabled, sort_order, id);

CREATE TABLE unit_conversion_rate (
    id              bigserial PRIMARY KEY,
    source_unit_id  bigint NOT NULL REFERENCES unit_definition(id),
    target_unit_id  bigint NOT NULL REFERENCES unit_definition(id),
    factor          numeric(38,18) NOT NULL CHECK (factor > 0),
    version         integer NOT NULL DEFAULT 1,
    created_at      timestamptz NOT NULL DEFAULT now(),
    created_by      varchar(64),
    updated_at      timestamptz,
    updated_by      varchar(64),
    CONSTRAINT ck_unit_conversion_rate_distinct CHECK (source_unit_id <> target_unit_id),
    CONSTRAINT uq_unit_conversion_rate_direction UNIQUE (source_unit_id, target_unit_id)
);
CREATE INDEX idx_unit_conversion_rate_source ON unit_conversion_rate (source_unit_id, target_unit_id);

ALTER TABLE ds_quote_customer_part ADD COLUMN settlement_unit varchar(32);
ALTER TABLE ds_quote_customer_part ADD COLUMN version integer NOT NULL DEFAULT 1;
ALTER TABLE ds_cost_basic_material ADD COLUMN pricing_unit varchar(32);
ALTER TABLE ds_cost_detail_material ADD COLUMN pricing_unit varchar(32);
COMMENT ON COLUMN ds_quote_customer_part.settlement_unit IS '客户产品结算单位（引用 unit_definition.code，大小写不敏感）';
COMMENT ON COLUMN ds_quote_customer_part.version IS '结算单位维护的乐观锁版本';
COMMENT ON COLUMN ds_cost_basic_material.pricing_unit IS '基础核价根生产料号计价单位';
COMMENT ON COLUMN ds_cost_detail_material.pricing_unit IS '详细核价根生产料号计价单位';

-- Seven prescribed units.  The stored spelling is the UI spelling; lookups use lower(trim(code)).
INSERT INTO unit_definition (code, category, enabled, sort_order, created_by)
VALUES
 ('EA',   'QUANTITY', true, 10, 'task-261004'),
 ('PCS',  'QUANTITY', true, 20, 'task-261004'),
 ('KPCS', 'QUANTITY', true, 30, 'task-261004'),
 ('g',    'WEIGHT',   true, 10, 'task-261004'),
 ('kg',   'WEIGHT',   true, 20, 'task-261004'),
 ('t',    'WEIGHT',   true, 30, 'task-261004'),
 ('盎司', 'WEIGHT',   true, 40, 'task-261004')
ON CONFLICT DO NOTHING;

INSERT INTO unit_conversion_rate (source_unit_id, target_unit_id, factor, created_by)
SELECT s.id, t.id, v.factor::numeric, 'task-261004'
FROM (VALUES
 ('EA','PCS',  '1'), ('EA','KPCS','0.001'),
 ('PCS','EA', '1'), ('PCS','KPCS','0.001'),
 ('KPCS','EA','1000'), ('KPCS','PCS','1000'),
 ('g','kg','0.001'), ('g','t','0.000001'), ('g','盎司','0.03527396194958'),
 ('kg','g','1000'), ('kg','t','0.001'), ('kg','盎司','35.27396194958'),
 ('t','g','1000000'), ('t','kg','1000'), ('t','盎司','35273.96194958'),
 ('盎司','g','28.349523125'), ('盎司','kg','0.028349523125'), ('盎司','t','0.000028349523125')
) AS v(source_code, target_code, factor)
JOIN unit_definition s ON lower(btrim(s.code)) = lower(btrim(v.source_code))
JOIN unit_definition t ON lower(btrim(t.code)) = lower(btrim(v.target_code))
ON CONFLICT (source_unit_id, target_unit_id) DO NOTHING;
