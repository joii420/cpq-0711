import React from 'react';
import { Card, Tag, Typography, Tooltip } from 'antd';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import 'dayjs/locale/zh-cn';
import type { TableSummaryDTO } from '../../services/masterDataService';

dayjs.extend(relativeTime);
dayjs.locale('zh-cn');

const { Text, Title } = Typography;

const GROUP_TAG_MAP: Record<TableSummaryDTO['group'], { color: string; label: string }> = {
  GLOBAL: { color: 'blue', label: '全局' },
  CUSTOMER: { color: 'green', label: '客户' },
  ELEMENT: { color: 'default', label: '元素' },
};

interface Props {
  summary: TableSummaryDTO;
  onClick: () => void;
}

const TableOverviewCard: React.FC<Props> = ({ summary, onClick }) => {
  const { displayName, tableName, rowCount, lastUpdatedAt, group, v1Disabled } = summary;
  const tagInfo = GROUP_TAG_MAP[group];
  const isDisabled = v1Disabled;

  const cardContent = (
    <Card
      hoverable={!isDisabled}
      onClick={isDisabled ? undefined : onClick}
      style={{
        cursor: isDisabled ? 'not-allowed' : 'pointer',
        opacity: isDisabled ? 0.5 : 1,
        transition: 'opacity 0.2s',
        height: '100%',
      }}
      styles={{ body: { padding: '16px' } }}
    >
      {/* 顶部：中文名 + 表名 */}
      <div style={{ marginBottom: 12 }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 4 }}>
          <Text strong style={{ fontSize: 15 }}>{displayName}</Text>
          <Tag color={tagInfo.color} style={{ marginRight: 0 }}>{tagInfo.label}</Tag>
        </div>
        <Text
          type="secondary"
          style={{ fontFamily: 'monospace', fontSize: 12 }}
        >
          {tableName}
        </Text>
        {group === 'ELEMENT' && v1Disabled && (
          <Tag color="default" style={{ marginLeft: 6, fontSize: 11 }}>v2 启用</Tag>
        )}
      </div>

      {/* 中间：行数大字 */}
      <div style={{ textAlign: 'center', margin: '16px 0' }}>
        <Title level={2} style={{ margin: 0, lineHeight: 1 }}>
          {rowCount.toLocaleString()}
        </Title>
        <Text type="secondary" style={{ fontSize: 12 }}>条记录</Text>
      </div>

      {/* 底部：最近更新 */}
      <div style={{ marginTop: 8 }}>
        <Text type="secondary" style={{ fontSize: 12 }}>
          {lastUpdatedAt
            ? `更新于 ${dayjs(lastUpdatedAt).fromNow()}`
            : '暂无更新记录'}
        </Text>
      </div>
    </Card>
  );

  if (isDisabled) {
    return (
      <Tooltip title="v1 暂未启用" placement="top">
        {cardContent}
      </Tooltip>
    );
  }

  return cardContent;
};

export default TableOverviewCard;
