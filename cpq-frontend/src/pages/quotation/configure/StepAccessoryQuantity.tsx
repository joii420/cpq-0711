import React from 'react';
import { Card, InputNumber, Tag, Descriptions, Typography } from 'antd';
import type { PartState } from '../ConfigureProductDrawer';

interface Props {
  parts: PartState[];
  onUpdatePart: (idx: number, patch: Partial<PartState>) => void;
}

/**
 * 配件清单 + 数量编辑（仅 COMPOSITE）。
 * 在进入「组合工艺」前，让用户为每个已选配件设置组成用量（正整数，默认 1）。
 * 数量最终写入 material_bom_item.composition_qty —— 选配只负责入库，不做系统自动计算。
 */
const StepAccessoryQuantity: React.FC<Props> = ({ parts, onUpdatePart }) => (
  <div>
    <Typography.Title level={5} style={{ marginTop: 0 }}>配件清单与数量</Typography.Title>
    <Typography.Paragraph type="secondary" style={{ marginBottom: 16 }}>
      请确认每个配件的组成用量（默认 1，正整数）。该数量用于后续公式计算。
    </Typography.Paragraph>

    {parts.map((p, i) => {
      const partNo = p.selectedHfPartNo
        ?? p.reusedFromExisting?.hfPartNo
        ?? (p.partMode === 'custom' ? '自定义(将新建)' : '—');
      return (
        <Card
          key={i}
          size="small"
          style={{ marginBottom: 12 }}
          title={<><Tag color="purple">{i + 1}</Tag> {p.name}</>}
          extra={
            <span>
              数量:{' '}
              <InputNumber
                min={1}
                step={1}
                precision={0}
                value={p.quantity ?? 1}
                onChange={(v) => onUpdatePart(i, { quantity: v == null || v < 1 ? 1 : Math.floor(v) })}
                style={{ width: 120 }}
              />
            </span>
          }
        >
          <Descriptions size="small" column={1} bordered={false}>
            <Descriptions.Item label="料号">{partNo}</Descriptions.Item>
            <Descriptions.Item label="材质">{p.selectedRecipeSymbol ?? p.selectedRecipeCode ?? '—'}</Descriptions.Item>
          </Descriptions>
        </Card>
      );
    })}
  </div>
);

export default StepAccessoryQuantity;
