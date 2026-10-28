import React from 'react';
import { Button } from 'antd';
import { SearchOutlined } from '@ant-design/icons';

export interface ListQueryButtonProps {
  onClick: () => void;
  loading?: boolean;
  disabled?: boolean;
}

/** 统一列表筛选提交按钮：与筛选条件并排放置，回车提交由各输入控件负责。 */
const ListQueryButton: React.FC<ListQueryButtonProps> = ({ onClick, loading, disabled }) => (
  <Button
    type="primary"
    icon={<SearchOutlined />}
    onClick={onClick}
    loading={loading}
    disabled={disabled}
  >
    查询
  </Button>
);

export default ListQueryButton;
