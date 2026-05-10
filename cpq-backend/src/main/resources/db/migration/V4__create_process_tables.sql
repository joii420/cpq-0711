-- Process (工序)
CREATE TABLE process (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    category VARCHAR(30) NOT NULL,
    is_required BOOLEAN NOT NULL DEFAULT false,
    sort_order INTEGER DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT chk_process_category CHECK (category IN ('SURFACE_TREATMENT','MACHINING','HEAT_TREATMENT','ASSEMBLY','INSPECTION','PACKAGING')),
    CONSTRAINT chk_process_status CHECK (status IN ('ACTIVE','DISABLED'))
);

-- ProductProcess (product ↔ process binding)
CREATE TABLE product_process (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id UUID NOT NULL REFERENCES product(id),
    process_id UUID NOT NULL REFERENCES process(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_product_process UNIQUE(product_id, process_id)
);

CREATE INDEX idx_pp_product ON product_process(product_id);

-- Seed: 6 categories of processes
INSERT INTO process (code, name, description, category, is_required, sort_order) VALUES
-- 表面处理工序
('MRO-LP-0001', '电镀', '金属表面电镀处理', 'SURFACE_TREATMENT', false, 1),
('MRO-LP-0002', '喷涂', '表面喷涂处理', 'SURFACE_TREATMENT', false, 2),
('MRO-LP-0003', '抛光', '表面抛光处理', 'SURFACE_TREATMENT', false, 3),
('MRO-LP-0004', '阳极氧化', '铝合金阳极氧化', 'SURFACE_TREATMENT', false, 4),
('MRO-LP-0005', '磷化', '钢铁磷化处理', 'SURFACE_TREATMENT', false, 5),
-- 机加工序
('MRO-MC-0001', 'CNC加工', '数控加工中心加工', 'MACHINING', false, 1),
('MRO-MC-0002', '车削', '车床车削加工', 'MACHINING', false, 2),
('MRO-MC-0003', '铣削', '铣床铣削加工', 'MACHINING', false, 3),
('MRO-MC-0004', '钻孔攻丝', '钻孔及攻丝加工', 'MACHINING', false, 4),
('MRO-MC-0005', '磨削', '磨床精密磨削', 'MACHINING', false, 5),
-- 热处理工序
('MRO-HT-0001', '淬火', '金属淬火处理', 'HEAT_TREATMENT', false, 1),
('MRO-HT-0002', '回火', '金属回火处理', 'HEAT_TREATMENT', false, 2),
('MRO-HT-0003', '正火', '金属正火处理', 'HEAT_TREATMENT', false, 3),
('MRO-HT-0004', '退火', '金属退火处理', 'HEAT_TREATMENT', false, 4),
-- 装配工序
('MRO-AS-0001', '总装配', '产品总装配', 'ASSEMBLY', false, 1),
('MRO-AS-0002', '部件装配', '部件组装', 'ASSEMBLY', false, 2),
('MRO-AS-0003', '螺栓连接', '螺栓紧固连接', 'ASSEMBLY', false, 3),
('MRO-AS-0004', '焊接装配', '焊接组装', 'ASSEMBLY', false, 4),
-- 检测工序
('MRO-IN-0001', '尺寸检测', '尺寸精度检测', 'INSPECTION', true, 1),
('MRO-IN-0002', '外观检测', '外观质量检测', 'INSPECTION', false, 2),
('MRO-IN-0003', '功能测试', '功能性能测试', 'INSPECTION', false, 3),
('MRO-IN-0004', '强度测试', '材料强度测试', 'INSPECTION', false, 4),
('MRO-IN-0005', '气密性检测', '密封气密性检测', 'INSPECTION', false, 5),
-- 包装工序
('MRO-PK-0001', '包装入库', '标准包装入库', 'PACKAGING', true, 1),
('MRO-PK-0002', '防护包装', '特殊防护包装', 'PACKAGING', false, 2),
('MRO-PK-0003', '标识标签', '产品标识标签粘贴', 'PACKAGING', false, 3);
