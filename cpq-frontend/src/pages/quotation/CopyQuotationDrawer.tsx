import React, { useEffect, useState } from 'react';
import { Drawer, Select, Button, Space, Alert, message } from 'antd';
import { templateService } from '../../services/templateService';

interface TemplateOption {
  id: string;
  name: string;
  version?: string;
}

interface Props {
  open: boolean;
  defaultTemplateId?: string;
  onClose: () => void;
  onConfirm: (templateId: string) => Promise<void> | void;
}

const CopyQuotationDrawer: React.FC<Props> = ({ open, defaultTemplateId, onClose, onConfirm }) => {
  const [templates, setTemplates] = useState<TemplateOption[]>([]);
  const [selected, setSelected] = useState<string | undefined>(defaultTemplateId);
  const [loading, setLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (!open) return;
    setSelected(defaultTemplateId);
    setLoading(true);
    templateService
      .list({ status: 'PUBLISHED', templateKind: 'QUOTATION', size: 200 })
      .then((res: any) => {
        const list = (res.data ?? []) as any[];
        setTemplates(list.map((t: any) => ({ id: t.id, name: t.name, version: t.version })));
      })
      .catch((e: any) => message.error(e.message || '加载模板失败'))
      .finally(() => setLoading(false));
  }, [open, defaultTemplateId]);

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
      width={480}
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
      <p>默认使用源报价单的模板。换模板后：页签相同的迁移用户输入值，不同的留空，公式/数据由新模板重算。</p>
      <Select
        style={{ width: '100%' }}
        loading={loading}
        showSearch
        optionFilterProp="label"
        placeholder="选择报价模板（仅已发布）"
        value={selected}
        onChange={setSelected}
        options={templates.map((t) => ({
          value: t.id,
          label: t.version ? `${t.name} ${t.version}` : t.name,
        }))}
      />
      {changed ? (
        <Alert
          style={{ marginTop: 12 }}
          type="warning"
          showIcon
          message="已更换模板：仅页签字段相同的输入值会被迁移，其余留空。"
        />
      ) : null}
    </Drawer>
  );
};

export default CopyQuotationDrawer;
