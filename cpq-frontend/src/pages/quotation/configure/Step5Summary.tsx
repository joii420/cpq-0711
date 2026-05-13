import React from 'react';
import { Card, Tag, InputNumber, Descriptions } from 'antd';
import { CheckCircleFilled } from '@ant-design/icons';
import type { ProductType } from '../../../types/configure';
import type { PartState, CompositeProcessAdded } from '../ConfigureProductDrawer';

interface Props {
  productType: ProductType;
  parts: PartState[];
  addedCProcs: CompositeProcessAdded[];
  onUpdatePart: (idx: number, patch: Partial<PartState>) => void;
}

const Step5Summary: React.FC<Props> = ({ productType, parts, addedCProcs, onUpdatePart }) => (
  <div>
    <div style={{ textAlign: 'center', marginBottom: 20 }}>
      <CheckCircleFilled style={{ fontSize: 36, color: '#52c41a' }} />
      <h3 style={{ marginTop: 8, marginBottom: 4 }}>选配完成</h3>
      <p style={{ color: '#888' }}>请核对以下信息,确认后点击「确认添加」</p>
    </div>

    <Card title="产品类型" size="small" style={{ marginBottom: 12 }}>
      <span style={{ fontSize: 18 }}>{productType === 'COMPOSITE' ? '🔧' : '🔩'}</span>
      <b style={{ marginLeft: 8 }}>{productType === 'COMPOSITE' ? '组合产品' : '独立产品'}</b>
      <span style={{ color: '#888', marginLeft: 12 }}>
        {productType === 'COMPOSITE' ? `共 ${parts.length} 个配件` : '单一零件'}
      </span>
    </Card>

    <Card title="配件明细" size="small" style={{ marginBottom: 12 }}>
      {parts.map((p, i) => {
        const reused = p.reusedFromExisting;
        return (
          <Card
            key={i}
            size="small"
            style={{ marginBottom: 8 }}
            title={<><Tag color="purple">{i + 1}</Tag> {p.name}</>}
          >
            <Descriptions size="small" column={1} bordered={false}>
              <Descriptions.Item label="料号">
                {p.partMode === 'existing' ? (
                  <b style={{ color: '#5c6bc0' }}>{p.selectedHfPartNo}</b>
                ) : reused ? (
                  <>
                    <b style={{ color: '#5c6bc0' }}>{reused.hfPartNo}</b>{' '}
                    <Tag color="green">复用现有</Tag>
                  </>
                ) : (
                  <Tag color="orange">自定义(将新建)</Tag>
                )}
              </Descriptions.Item>

              <Descriptions.Item label="材质">{p.selectedRecipeCode ?? '—'}</Descriptions.Item>

              <Descriptions.Item label="工序">
                {reused
                  ? (reused.snapshot?.processes?.length
                      ? reused.snapshot.processes.map(x => x.processCode).join(' → ')
                      : '无')
                  : (p.processIds.length ? `${p.processIds.length} 项工序` : '无')}
              </Descriptions.Item>

              <Descriptions.Item label="单重">
                {(p.partMode === 'existing' || reused) ? (
                  <span>
                    {reused?.snapshot?.unitWeightGrams ?? '—'} g/件{' '}
                    <Tag>只读</Tag>
                  </span>
                ) : (
                  <InputNumber
                    placeholder="g/件(可选)"
                    value={p.unitWeightGrams ?? undefined}
                    onChange={(v) => onUpdatePart(i, { unitWeightGrams: v === null ? null : Number(v) })}
                    min={0}
                    step={0.1}
                  />
                )}
              </Descriptions.Item>
            </Descriptions>
          </Card>
        );
      })}
    </Card>

    {productType === 'COMPOSITE' && addedCProcs.length > 0 && (
      <Card title="组合工艺" size="small">
        {addedCProcs.map((a, i) => (
          <div key={i} style={{ borderBottom: '0.5px solid #f0f0f0', padding: '8px 0' }}>
            <b>{a.defCode}</b>
            <span style={{ marginLeft: 8, color: '#888' }}>
              参与配件: {a.participatingPartIndexes.map(idx => parts[idx]?.name).join(' + ')}
            </span>
            <div style={{ fontSize: 11, color: '#aaa', marginTop: 2 }}>
              {Object.entries(a.params).length === 0
                ? '(未填参数)'
                : Object.entries(a.params).map(([k, v]) => `${k}: ${v}`).join('; ')}
            </div>
          </div>
        ))}
      </Card>
    )}
  </div>
);

export default Step5Summary;
