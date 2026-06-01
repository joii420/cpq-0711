import React from 'react';
import { Card, Radio, InputNumber, Space, Typography } from 'antd';
import type { ProductType } from '../../../types/configure';

interface Props {
  productType: ProductType;
  onChangeType: (t: ProductType) => void;
  initPartCount: number;
  onChangePartCount: (n: number) => void;
}

const Step0ProductType: React.FC<Props> = ({
  productType,
  onChangeType,
  initPartCount,
  onChangePartCount,
}) => (
  <div>
    <Typography.Title level={5}>请选择产品类型</Typography.Title>
    <Typography.Paragraph type="secondary" style={{ marginBottom: 16 }}>
      选择后决定本次选配的配置方式
    </Typography.Paragraph>

    <Radio.Group
      value={productType}
      onChange={(e) => onChangeType(e.target.value)}
      style={{ width: '100%' }}
    >
      <Space direction="vertical" style={{ width: '100%' }} size={12}>
        <Radio.Button value="SIMPLE" style={{ width: '100%', height: 'auto', padding: 16 }}>
          <div style={{ whiteSpace: 'normal' }}>
            <b>独立产品</b> — 单一零件，完成料号 → 材质 → 工序选配
          </div>
        </Radio.Button>
        <Radio.Button value="COMPOSITE" style={{ width: '100%', height: 'auto', padding: 16 }}>
          <div style={{ whiteSpace: 'normal' }}>
            <b>组合产品</b> — 多配件各自选配，再进行组合工艺（铆接/焊接等）
          </div>
        </Radio.Button>
      </Space>
    </Radio.Group>

    {productType === 'COMPOSITE' && (
      <Card size="small" style={{ marginTop: 16, background: '#fafafe' }}>
        <Space>
          <span>配件个数:</span>
          <InputNumber
            min={2}
            max={8}
            value={initPartCount}
            onChange={(v) => onChangePartCount(v ?? 2)}
          />
          <Typography.Text type="secondary">个配件（最少 2，最多 8）</Typography.Text>
        </Space>
      </Card>
    )}
  </div>
);

export default Step0ProductType;
