import React from 'react';
import { Drawer, Descriptions, Tag } from 'antd';
import type { ProcessMasterDTO } from '../../services/v6MasterDataService';

interface Props {
  open: boolean;
  record: ProcessMasterDTO | null;
  onClose: () => void;
}

const V6ProcessDetailDrawer: React.FC<Props> = ({ open, record, onClose }) => {
  return (
    <Drawer
      title={record ? `工序详情 — ${record.processNo}` : '工序详情'}
      placement="right"
      width={480}
      open={open}
      onClose={onClose}
      destroyOnClose
      footer={null}
    >
      {record && (
        <Descriptions column={1} bordered size="small" labelStyle={{ width: 120, fontWeight: 500 }}>
          <Descriptions.Item label="记录 ID">{record.id || '—'}</Descriptions.Item>
          <Descriptions.Item label="工序编号">{record.processNo}</Descriptions.Item>
          <Descriptions.Item label="工序名称">{record.processName || '—'}</Descriptions.Item>
          <Descriptions.Item label="工序分类">{record.processCategory || '—'}</Descriptions.Item>
          <Descriptions.Item label="是否外协">
            {record.isOutsource === true ? (
              <Tag color="orange">外协</Tag>
            ) : record.isOutsource === false ? (
              <Tag color="default">自制</Tag>
            ) : '—'}
          </Descriptions.Item>
          <Descriptions.Item label="标准货币">{record.standardCurrency || '—'}</Descriptions.Item>
          <Descriptions.Item label="标准单位">{record.standardUnit || '—'}</Descriptions.Item>
          <Descriptions.Item label="默认不良率">
            {record.defaultDefectRate !== undefined && record.defaultDefectRate !== null
              ? record.defaultDefectRate
              : '—'}
          </Descriptions.Item>
          <Descriptions.Item label="创建时间">{record.createdAt || '—'}</Descriptions.Item>
          <Descriptions.Item label="更新时间">{record.updatedAt || '—'}</Descriptions.Item>
          <Descriptions.Item label="创建人">{record.createdBy || '—'}</Descriptions.Item>
          <Descriptions.Item label="更新人">{record.updatedBy || '—'}</Descriptions.Item>
        </Descriptions>
      )}
    </Drawer>
  );
};

export default V6ProcessDetailDrawer;
