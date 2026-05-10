import React, { useEffect, useState } from 'react';
import { Table, Card, Spin, Alert, Tag } from 'antd';
import { costingSheetService } from '../../services/costingSheetService';
import type { CostingSheetData } from '../../services/costingSheetService';

interface Props {
  quotationId: string;
}

const CostingSheetView: React.FC<Props> = ({ quotationId }) => {
  const [data, setData] = useState<CostingSheetData | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setLoading(true);
    costingSheetService.get(quotationId)
      .then((r) => setData(r.data))
      .catch((e) => setError(e.message))
      .finally(() => setLoading(false));
  }, [quotationId]);

  if (loading) return <Spin />;
  if (error) return <Alert type="error" message={error} />;
  if (!data) return <Alert message="无核价表" />;

  const tableColumns = [
    { title: '宏丰料号', dataIndex: 'hf_part_no', key: 'hf_part_no', fixed: 'left' as const, width: 150 },
    ...data.columns.map((col) => ({
      title: col.title,
      dataIndex: ['cells', col.col_key],
      key: col.col_key,
      width: 130,
      render: (val: any) => {
        if (val === null || val === undefined) return '-';
        return typeof val === 'number' ? val.toLocaleString() : String(val);
      },
    })),
  ];

  return (
    <div>
      <Card
        size="small"
        title={
          <span>
            核价表 · <Tag color="blue">{data.costingTemplateName}</Tag>
            <Tag color={data.status === 'LIVE' ? 'green' : 'default'}>{data.status === 'LIVE' ? '实时' : '快照'}</Tag>
          </span>
        }
        extra={data.totalCost && <span>总成本: <strong>{Number(data.totalCost).toLocaleString()}</strong></span>}
        style={{ marginBottom: 12 }}
      />
      <Table
        rowKey="hf_part_no"
        size="small"
        columns={tableColumns}
        dataSource={data.rows}
        pagination={false}
        scroll={{ x: 'max-content' }}
      />
    </div>
  );
};

export default CostingSheetView;
