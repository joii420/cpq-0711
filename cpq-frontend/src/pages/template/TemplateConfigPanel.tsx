import React, { useEffect, useState } from 'react';
import {
  Typography,
  Button,
  Select,
  Input,
  Form,
  Tag,
  Divider,
  List,
  Space,
  Popconfirm,
  Modal,
  InputNumber,
  Alert,
} from 'antd';
import {
  SendOutlined,
  InboxOutlined,
  CopyOutlined,
  HistoryOutlined,
} from '@ant-design/icons';
import type { TemplateData, VersionHistoryItem } from './types';
import { STATUS_COLORS, STATUS_LABELS } from './types';
import { canCreateNewDraftFromTemplate } from './softDeleteRules';
import { productCategoryService, type ProductCategory } from '../../services/productCategoryService';
import { customerService } from '../../services/customerService';
import RemoteInfiniteSelect from '../../components/RemoteInfiniteSelect';
import GvBindingPanel from './GvBindingPanel';
import './styles.css';

const { Text } = Typography;
const { TextArea } = Input;

interface TemplateConfigPanelProps {
  template: TemplateData;
  form: any;
  onFormValuesChange: () => void;
  onPublish: (majorVersion?: number) => void;
  onArchive: () => void;
  onNewDraft: () => void;
  onBack: () => void;
  versionHistory: VersionHistoryItem[];
  onVersionClick: (id: string) => void;
  currentId: string;
  isSystemAdmin?: boolean;
}

const TemplateConfigPanel: React.FC<TemplateConfigPanelProps> = ({
  template,
  form,
  onFormValuesChange,
  onPublish,
  onArchive,
  onNewDraft,
  onBack,
  versionHistory,
  onVersionClick,
  currentId,
  isSystemAdmin = false,
}) => {
  const isDraft = template.status === 'DRAFT';
  const isPublished = template.status === 'PUBLISHED';
  const isDeleted = template.status === 'DELETED';
  const [publishModalOpen, setPublishModalOpen] = useState(false);
  const [majorVersion, setMajorVersion] = useState<number | undefined>(undefined);
  const [categories, setCategories] = useState<ProductCategory[]>([]);

  const fetchCustomers = React.useCallback(
    ({ page, size, keyword }: { page: number; size: number; keyword: string }) =>
      customerService.list({ page, size, keyword: keyword || undefined }),
    [],
  );
  const mapCustomerOption = React.useCallback(
    (customer: { id: string; name: string }) => ({ value: customer.id, label: customer.name }),
    [],
  );

  useEffect(() => {
    productCategoryService.list('ACTIVE')
      .then(res => setCategories(res.data || []))
      .catch(() => setCategories([]));
  }, []);

  const categoryOptions = categories.map(c => ({ value: c.id, label: c.name }));
  const customerInitialOptions = (template.customers?.length
    ? template.customers
    : (template.customerIds || (template.customerId ? [template.customerId] : [])).map((id, index) => ({
      id,
      name: template.customerNames?.[index] || (template.customerId === id ? template.customerName : undefined) || id,
    })))
    .map(customer => ({ value: String(customer.id), label: customer.name }));

  return (
    <>
      {/* Title */}
      <div className="tm-config-title">模板配置</div>

      {isDeleted && (
        <Alert
          type="error"
          showIcon
          style={{ margin: '0 12px 12px' }}
          message="该模板已删除"
          description="不能恢复、编辑、发布或作为新报价模板引用；历史报价仍保留原模板引用和快照。"
        />
      )}

      {/* Basic info form */}
      <div className="tm-config-section">
        <div className="tm-config-section-title">基本信息</div>
        <Form
          form={form}
          layout="vertical"
          size="small"
          onValuesChange={onFormValuesChange}
        >
          <Form.Item name="categoryId" label="产品分类">
            <Select
              options={categoryOptions}
              placeholder="请选择产品分类"
              allowClear
              showSearch
              optionFilterProp="label"
              disabled={!isDraft}
            />
          </Form.Item>
          <Form.Item name="name" label="模板名称">
            <Input disabled={!isDraft} />
          </Form.Item>
          <Form.Item name="customerIds" label="适用客户">
            <RemoteInfiniteSelect
              mode="multiple"
              placeholder="留空表示对所有客户通用"
              allowClear
              fetchPage={fetchCustomers}
              mapOption={mapCustomerOption}
              disabled={!isDraft}
              initialOptions={customerInitialOptions}
            />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <TextArea rows={2} disabled={!isDraft} />
          </Form.Item>
          <Form.Item name="usageNote" label="使用说明">
            <TextArea rows={2} disabled={!isDraft} />
          </Form.Item>
        </Form>
      </div>

      <Divider style={{ margin: '12px 0' }} />

      {/* B4：EXCEL 类型模板不显示「关联全局变量」区块（PRD §3.7.2.1） */}
      {template.templateKind !== 'EXCEL' && (
        <>
          <div className="tm-config-section">
            <GvBindingPanel templateId={currentId} isDraft={isDraft} />
          </div>

          <Divider style={{ margin: '12px 0' }} />
        </>
      )}

      {/* Action buttons */}
      <div className="tm-config-section">
        <div className="tm-config-section-title">操作</div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          {isDraft && (
            <>
              <Button
                type="primary"
                icon={<SendOutlined />}
                block
                style={{
                  background: 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)',
                  border: 'none',
                  height: 40,
                  fontWeight: 600,
                  fontSize: 14,
                  boxShadow: '0 2px 8px rgba(102,126,234,0.3)',
                }}
                onClick={() => setPublishModalOpen(true)}
              >
                发布模板
              </Button>
              <Modal
                title="发布模板"
                open={publishModalOpen}
                onOk={() => { onPublish(majorVersion); setPublishModalOpen(false); setMajorVersion(undefined); }}
                onCancel={() => setPublishModalOpen(false)}
              >
                <p>确认将当前草稿发布为新版本？</p>
                <Form.Item label="大版本号（可选，留空则自动递增小版本）">
                  <InputNumber min={1} value={majorVersion} onChange={v => setMajorVersion(v ?? undefined)} placeholder="如: 2" />
                </Form.Item>
              </Modal>
            </>
          )}
          {isPublished && (
            <Popconfirm
              title="确认归档"
              description="归档后不可用于新报价，确认归档？"
              onConfirm={onArchive}
            >
              <Button danger icon={<InboxOutlined />} block>
                归档模板
              </Button>
            </Popconfirm>
          )}
          {canCreateNewDraftFromTemplate(template.status, isSystemAdmin) && (
            <Button icon={<CopyOutlined />} block onClick={onNewDraft}>
              {isDeleted ? '创建新模板' : '创建新草稿'}
            </Button>
          )}
          {isDeleted && (
            <Button block disabled title="DELETED 不支持恢复或编辑">
              恢复 / 编辑（不可用）
            </Button>
          )}
          <Button block onClick={onBack}>
            返回列表
          </Button>
        </div>
      </div>

      <Divider style={{ margin: '12px 0' }} />

      {/* Version history */}
      <div className="tm-config-section">
        <div className="tm-config-section-title">
          <Space>
            <HistoryOutlined />
            <span>版本历史</span>
          </Space>
        </div>
        <List
          size="small"
          dataSource={versionHistory}
          style={{ maxHeight: 300, overflow: 'auto' }}
          renderItem={(v: VersionHistoryItem) => (
            <List.Item
              style={{ padding: '4px 0', border: 'none' }}
              onClick={() => v.id !== currentId && onVersionClick(v.id)}
            >
              <div
                className={`tm-version-item${v.id === currentId ? ' current' : ''}`}
                style={{ width: '100%', cursor: v.id !== currentId ? 'pointer' : 'default' }}
              >
                <Space>
                  <Text style={{ fontSize: 12 }}>{v.version || '(草稿)'}</Text>
                  <Tag
                    color={STATUS_COLORS[v.status]}
                    style={{ fontSize: 10, padding: '0 4px' }}
                  >
                    {STATUS_LABELS[v.status] || v.status}
                  </Tag>
                  {v.id === currentId && (
                    <Tag color="gold" style={{ fontSize: 10, padding: '0 4px' }}>
                      当前
                    </Tag>
                  )}
                </Space>
                {v.publishedAt && (
                  <div>
                    <Text type="secondary" style={{ fontSize: 11 }}>
                      {new Date(v.publishedAt).toLocaleDateString('zh-CN')}
                    </Text>
                  </div>
                )}
              </div>
            </List.Item>
          )}
          locale={{ emptyText: '暂无历史版本' }}
        />
      </div>
    </>
  );
};

export default TemplateConfigPanel;
