import React, { useEffect, useState } from 'react';
import { Input, List, Tag, Card, Empty, Spin } from 'antd';
import { SearchOutlined, PlusCircleOutlined } from '@ant-design/icons';
import { configureProductService } from '../../../services/configureProductService';
import type { SearchPartResult } from '../../../types/configure';
import type { PartState } from '../ConfigureProductDrawer';

interface Props {
  part: PartState;
  onUpdate: (patch: Partial<PartState>) => void;
}

const Step1SearchPart: React.FC<Props> = ({ part, onUpdate }) => {
  const [q, setQ] = useState('');
  const [results, setResults] = useState<SearchPartResult[]>([]);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!q.trim()) { setResults([]); return; }
    setLoading(true);
    const t = setTimeout(() => {
      configureProductService.searchParts(q, 50)
        .then(setResults)
        .catch(() => setResults([]))
        .finally(() => setLoading(false));
    }, 300);
    return () => clearTimeout(t);
  }, [q]);

  const selectExisting = (r: SearchPartResult) => {
    onUpdate({
      partMode: 'existing',
      selectedHfPartNo: r.hfPartNo,
      selectedRecipeCode: r.recipeCode ?? null,
      selectedRecipeSymbol: r.recipeSymbol ?? null,
      matLocked: true,
      // 清空自定义路径残留
      elementOverrides: {},
      processIds: [],
      unitWeightGrams: null,
      reusedFromExisting: null,
    });
  };

  const selectNone = () => {
    onUpdate({
      partMode: 'custom',
      selectedHfPartNo: null,
      selectedRecipeCode: null,
      selectedRecipeSymbol: null,
      matLocked: false,
      elementOverrides: {},
      processIds: [],
      unitWeightGrams: null,
      reusedFromExisting: null,
    });
  };

  return (
    <div>
      <Input
        size="large"
        prefix={<SearchOutlined />}
        placeholder="输入料号、材质、规格或尺寸…"
        value={q}
        onChange={(e) => setQ(e.target.value)}
        allowClear
      />
      <div style={{ marginTop: 8, color: '#888', fontSize: 12 }}>
        匹配 <b style={{ color: '#5c6bc0' }}>{results.length}</b> 条结果
      </div>

      <Spin spinning={loading}>
        <List
          style={{ marginTop: 12, maxHeight: 360, overflow: 'auto' }}
          dataSource={results}
          locale={{
            emptyText: q.trim()
              ? <Empty description="未找到匹配料号" />
              : <div style={{ padding: 24, color: '#bbb', textAlign: 'center' }}>输入关键词开始搜索</div>,
          }}
          renderItem={(r) => (
            <List.Item
              onClick={() => selectExisting(r)}
              style={{
                cursor: 'pointer',
                padding: 12,
                background: part.selectedHfPartNo === r.hfPartNo ? '#f0effe' : undefined,
                border: '0.5px solid ' + (part.selectedHfPartNo === r.hfPartNo ? '#5c6bc0' : '#eee'),
                borderRadius: 8,
                marginBottom: 4,
              }}
            >
              <List.Item.Meta
                title={<b>{r.hfPartNo}</b>}
                description={
                  <div>
                    {r.recipeSymbol ?? '—'} {r.recipeName ?? ''}{' '}
                    {r.specification && `· ${r.specification}`} {r.sizeInfo && `· ${r.sizeInfo}`}{' '}
                    <Tag color={r.statusCode === 'Y' ? 'green' : 'red'}>
                      {r.statusCode === 'Y' ? '在产' : '停产'}
                    </Tag>
                  </div>
                }
              />
            </List.Item>
          )}
        />
      </Spin>

      <Card
        size="small"
        onClick={selectNone}
        style={{
          marginTop: 12,
          cursor: 'pointer',
          border: '1.5px dashed ' + (part.partMode === 'custom' ? '#5c6bc0' : '#c5cae9'),
          background: part.partMode === 'custom' ? '#f0effe' : undefined,
          color: '#5c6bc0',
        }}
      >
        <PlusCircleOutlined /> &nbsp; 无匹配料号,进入自定义材质选配
      </Card>
    </div>
  );
};

export default Step1SearchPart;
