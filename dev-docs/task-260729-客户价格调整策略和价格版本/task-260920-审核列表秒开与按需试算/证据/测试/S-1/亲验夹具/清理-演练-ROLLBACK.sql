-- T260920 亲验夹具清理（按私有 id 精确限定；只写不跑，主线验完后执行）
BEGIN;
DELETE FROM material_price_update_job_item WHERE quotation_id IN ('4d63b2e5-113d-4cf5-82a0-2c80692f0327', 'a9476455-4fc5-424e-acf1-e7b672b28725', 'e20425f0-f5dd-40fb-b2bf-0f840263c8b7', '306a25b3-20bd-40cb-beee-4a63e74ed29c', '0233a328-6b6b-43b9-9f3e-7a0bad291a92') OR job_id IN (SELECT id FROM material_price_update_job WHERE customer_no = 'T260920-43482dfe');
DELETE FROM material_price_update_job WHERE customer_no = 'T260920-43482dfe';
DELETE FROM material_price_review_column WHERE review_id IN (SELECT id FROM material_price_review WHERE customer_no = 'T260920-43482dfe');
DELETE FROM material_price_review WHERE customer_no = 'T260920-43482dfe';
DELETE FROM material_price_version_ref WHERE customer_no = 'T260920-43482dfe';
DELETE FROM quotation_price_revision WHERE quotation_id IN ('4d63b2e5-113d-4cf5-82a0-2c80692f0327', 'a9476455-4fc5-424e-acf1-e7b672b28725', 'e20425f0-f5dd-40fb-b2bf-0f840263c8b7', '306a25b3-20bd-40cb-beee-4a63e74ed29c', '0233a328-6b6b-43b9-9f3e-7a0bad291a92');
DELETE FROM costing_order_version_override WHERE costing_order_id IN (SELECT id FROM costing_order WHERE quotation_id IN ('4d63b2e5-113d-4cf5-82a0-2c80692f0327', 'a9476455-4fc5-424e-acf1-e7b672b28725', 'e20425f0-f5dd-40fb-b2bf-0f840263c8b7', '306a25b3-20bd-40cb-beee-4a63e74ed29c', '0233a328-6b6b-43b9-9f3e-7a0bad291a92'));
DELETE FROM costing_order WHERE quotation_id IN ('4d63b2e5-113d-4cf5-82a0-2c80692f0327', 'a9476455-4fc5-424e-acf1-e7b672b28725', 'e20425f0-f5dd-40fb-b2bf-0f840263c8b7', '306a25b3-20bd-40cb-beee-4a63e74ed29c', '0233a328-6b6b-43b9-9f3e-7a0bad291a92');
DELETE FROM quotation WHERE id IN ('4d63b2e5-113d-4cf5-82a0-2c80692f0327', 'a9476455-4fc5-424e-acf1-e7b672b28725', 'e20425f0-f5dd-40fb-b2bf-0f840263c8b7', '306a25b3-20bd-40cb-beee-4a63e74ed29c', '0233a328-6b6b-43b9-9f3e-7a0bad291a92');
DELETE FROM element_price_version WHERE customer_no = 'T260920-43482dfe';
DELETE FROM comparison_column_config WHERE customer_no = 'T260920-43482dfe';
DELETE FROM customer_price_adjust_strategy WHERE id = '1c00e165-e190-4eb4-8505-7b1ead53ad94';
DELETE FROM element_price_strategy WHERE customer_no = 'T260920-43482dfe';
DELETE FROM template WHERE id = '14f34f3d-21d2-4563-bf0c-e709d44d11eb';
DELETE FROM component WHERE code IN ('T260920-ELEM-43482dfe', 'T260920-SUB-43482dfe');
DELETE FROM customer WHERE id = 'e7005462-bf8c-4bbc-944b-19a7894a2437';
DELETE FROM "user" WHERE id = 'd6fda83c-1b25-4400-a9fb-781c7b29c198' AND username = 't260920-accept-43482dfe';
-- 残留自检（应全为 0）：
SELECT (SELECT count(*) FROM material_price_review WHERE customer_no = 'T260920-43482dfe') + (SELECT count(*) FROM quotation WHERE quotation_number LIKE 'T260920-43482dfe-%') + (SELECT count(*) FROM customer WHERE code = 'T260920-43482dfe') + (SELECT count(*) FROM "user" WHERE username = 't260920-accept-43482dfe') AS residue;
ROLLBACK;
