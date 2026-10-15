CREATE TABLE IF NOT EXISTS template_customer (
    template_id UUID NOT NULL REFERENCES template(id),
    customer_id UUID NOT NULL REFERENCES customer(id),
    PRIMARY KEY (template_id, customer_id)
);
CREATE INDEX IF NOT EXISTS idx_template_customer_customer ON template_customer(customer_id, template_id);
CREATE INDEX IF NOT EXISTS idx_template_customer_template ON template_customer(template_id, customer_id);
INSERT INTO template_customer(template_id, customer_id)
SELECT id, customer_id FROM template WHERE customer_id IS NOT NULL
ON CONFLICT (template_id, customer_id) DO NOTHING;
DO $$ BEGIN
 IF (SELECT count(*) FROM template_customer) < (SELECT count(*) FROM template WHERE customer_id IS NOT NULL)
 THEN RAISE EXCEPTION 'template_customer backfill incomplete'; END IF;
 IF EXISTS (SELECT 1 FROM template t WHERE t.customer_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM template_customer tc WHERE tc.template_id=t.id AND tc.customer_id=t.customer_id))
 THEN RAISE EXCEPTION 'template_customer legacy rows missing'; END IF;
 IF EXISTS (SELECT 1 FROM template_customer tc LEFT JOIN template t ON t.id=tc.template_id LEFT JOIN customer c ON c.id=tc.customer_id WHERE t.id IS NULL OR c.id IS NULL)
 THEN RAISE EXCEPTION 'template_customer orphan rows detected'; END IF;
END $$;
