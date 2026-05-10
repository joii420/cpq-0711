import React, { useEffect, useState } from 'react';
import { Tabs, Card, Table, Spin, Alert, Tag, Statistic, Row, Col } from 'antd';
import { costingSheetService } from '../../services/costingSheetService';
import type { ComparisonData } from '../../services/costingSheetService';

interface Props {
  quotationId: string;
}

const ComparisonView: React.FC<Props> = ({ quotationId }) => {
  const [data, setData] = useState<ComparisonData | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setLoading(true);
    costingSheetService.getComparison(quotationId)
      .then((r) => setData(r.data))
      .catch((e) => setError(e.message))
      .finally(() => setLoading(false));
  }, [quotationId]);

  if (loading) return <Spin />;
  if (error) return <Alert type="error" message={error} />;
  if (!data) return null;

  const fmt = (v: any) => v === null || v === undefined ? '-' : (typeof v === 'number' ? v.toLocaleString() : String(v));

  return (
    <div>
      <Card style={{ marginBottom: 16 }} title="核价 vs 客户报价 摘要">
        <Row gutter={16}>
          <Col span={6}><Statistic title="核价总成本" value={fmt(data.summary.costingTotal)} /></Col>
          <Col span={6}><Statistic title="客户报价总价" value={fmt(data.summary.quotationTotal)} /></Col>
          <Col span={6}><Statistic title="毛利" value={fmt(data.summary.profit)} /></Col>
          <Col span={6}><Statistic title="毛利率" value={data.summary.profitRate || '-'} /></Col>
        </Row>
        <p style={{ marginTop: 12, color: '#999' }}>
          用户修改字段: <Tag>{data.summary.modifiedFieldsCount}</Tag>
        </p>
      </Card>

      <Tabs
        items={[
          {
            key: 'basic',
            label: `基础数据字段 (${data.basicFieldDiffs.length})`,
            children: (
              <Table
                rowKey="variableCode"
                size="small"
                pagination={false}
                dataSource={data.basicFieldDiffs}
                columns={[
                  { title: '变量编码', dataIndex: 'variableCode', width: 220 },
                  { title: '变量标签', dataIndex: 'variableLabel' },
                  { title: '核价值', dataIndex: 'costingValue', render: fmt },
                  { title: '报价值', dataIndex: 'quotationValue', render: fmt },
                  {
                    title: '状态', dataIndex: 'diffStatus', width: 100,
                    render: (s: string) => {
                      const map: Record<string, string> = { SAME: 'default', MODIFIED: 'orange', MISSING: 'red', NEW: 'blue' };
                      return <Tag color={map[s] || 'default'}>{s}</Tag>;
                    },
                  },
                ]}
              />
            ),
          },
          {
            key: 'tag',
            label: `公式 / 业务标签 (${data.tagGroups.reduce((s, g) => s + g.tags.length, 0)})`,
            children: (
              <div>
                {data.tagGroups.map((g) => (
                  <Card key={g.groupName} size="small" title={g.groupName} style={{ marginBottom: 12 }}>
                    <Table
                      rowKey="tag"
                      size="small"
                      pagination={false}
                      dataSource={g.tags}
                      columns={[
                        { title: '业务标签', dataIndex: 'tagLabel' },
                        { title: '编码', dataIndex: 'tag', width: 220 },
                        { title: '核价值', dataIndex: 'costingValue', render: fmt },
                        { title: '报价值', dataIndex: 'quotationValue', render: fmt },
                        { title: '差异', dataIndex: 'delta', render: fmt },
                        { title: '差异率', dataIndex: 'deltaPct', render: (v) => v || '-' },
                      ]}
                    />
                  </Card>
                ))}
              </div>
            ),
          },
        ]}
      />
    </div>
  );
};

export default ComparisonView;
