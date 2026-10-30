import React, { useCallback, useEffect, useState } from 'react';
import { Drawer, Button, Space, Alert, message } from 'antd';
import { templateService } from '../../services/templateService';
import RemoteInfiniteSelect from '../../components/RemoteInfiniteSelect';

interface Props {
  open: boolean;
  defaultTemplateId?: string;
  onClose: () => void;
  onConfirm: (templateId: string) => Promise<void> | void;
}

const CopyQuotationDrawer: React.FC<Props> = ({ open, defaultTemplateId, onClose, onConfirm }) => {
  const [selected, setSelected] = useState<string | undefined>(defaultTemplateId);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (!open) return;
    setSelected(defaultTemplateId);
  }, [open, defaultTemplateId]);

  const fetchTemplates = useCallback(({ page, size, keyword }: { page: number; size: number; keyword: string }) =>
    templateService.list({
      page,
      size,
      status: 'PUBLISHED',
      templateKind: 'QUOTATION',
      keyword: keyword || undefined,
    }), []);

  const mapTemplate = useCallback((template: any) => ({
    value: template.id,
    label: template.version ? `${template.name} ${template.version}` : template.name,
    item: template,
  }), []);

  const handleOk = async () => {
    if (!selected) {
      message.warning('请选择模板');
      return;
    }
    setSubmitting(true);
    try {
      await onConfirm(selected);
    } finally {
      setSubmitting(false);
    }
  };

  const changed = selected && defaultTemplateId && selected !== defaultTemplateId;

  return (
    <Drawer
      title="复制报价单 — 选择模板"
      placement="right"
      size={480}
      open={open}
      onClose={onClose}
      destroyOnClose
      footer={
        <Space style={{ float: 'right' }}>
          <Button onClick={onClose}>取消</Button>
          <Button type="primary" loading={submitting} onClick={handleOk}>
            确认复制
          </Button>
        </Space>
      }
    >
      <Alert
        style={{ marginBottom: 12 }}
        type="warning"
        showIcon
        message="换模板会清空当前报价单已填写的产品数据（总价归零），且当前无法恢复，请务必先导出留档后再继续操作。"
      />
      <RemoteInfiniteSelect<any>
        style={{ width: '100%' }}
        placeholder="选择报价模板（仅已发布）"
        value={selected}
        onChange={setSelected}
        fetchPage={fetchTemplates}
        mapOption={mapTemplate}
      />
      {changed ? (
        <Alert
          style={{ marginTop: 12 }}
          type="warning"
          showIcon
          message="已切换模板：确认后将清空当前已填写的产品数据，且无法恢复，请确保已导出留档。"
        />
      ) : null}
    </Drawer>
  );
};

export default CopyQuotationDrawer;
