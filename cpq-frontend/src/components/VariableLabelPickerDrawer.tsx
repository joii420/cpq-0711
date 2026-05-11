import React, { useEffect, useState, useMemo } from 'react';
import { Drawer, Input, Tabs, List, Tag, Typography, Empty, Space, Button, Spin, Modal, Form, Select, message } from 'antd';
import { ReloadOutlined, PlusOutlined } from '@ant-design/icons';
import { variableLabelService, type VariableLabel, type GroupedLabels } from '../services/variableLabelService';

const KNOWN_CATEGORIES = ['成本汇总', '费用比率', '物料属性', '单位标签', '汇率'];
const KNOWN_DATATYPES = ['DECIMAL', 'INTEGER', 'PERCENT', 'STRING', 'DATE'];

const { Text } = Typography;

interface Props {
  open: boolean;
  onClose: () => void;
  /** 选中一行后回调, 传 raw variable_path 字符串 (例 v_c_summary_agg.packaging_fee) */
  onPick: (variablePath: string, label: VariableLabel) => void;
  title?: string;
  /** 已填的 path, 用于高亮当前选中 */
  initialPath?: string;
}

/**
 * V149: 已命名字段选择抽屉. 按业务分类(成本汇总/费用比率/...)展示, 支持搜索.
 *
 * 与 GlobalVariablePickerDrawer 区别:
 *   - 全局变量 picker = 选"整张查表"(ELEM_PRICE/MAT_PRICE/EXCHANGE_RATE)+ key, 编译 BNF 路径
 *   - 本 picker     = 直接选"已命名的视图列", 返回 raw variable_path
 *
 * 未注册字段的"渐进式起名"入口暂留 TODO (Phase 1.5).
 */
const VariableLabelPickerDrawer: React.FC<Props> = ({ open, onClose, onPick, title = '选择已命名字段', initialPath }) => {
  const [grouped, setGrouped] = useState<GroupedLabels>({});
  const [loading, setLoading] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [activeCategory, setActiveCategory] = useState<string>('');
  // P1.5: 渐进式起名 modal
  const [addModalOpen, setAddModalOpen] = useState(false);
  const [addForm] = Form.useForm();
  const [adding, setAdding] = useState(false);

  const load = async () => {
    setLoading(true);
    try {
      const data = await variableLabelService.grouped();
      setGrouped(data);
      const cats = Object.keys(data);
      if (cats.length && !activeCategory) setActiveCategory(cats[0]);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (open) load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  const filtered = useMemo<GroupedLabels>(() => {
    const kw = keyword.trim().toLowerCase();
    if (!kw) return grouped;
    const out: GroupedLabels = {};
    for (const [cat, list] of Object.entries(grouped)) {
      const hits = list.filter(v =>
        v.displayName.toLowerCase().includes(kw)
        || v.variablePath.toLowerCase().includes(kw)
        || (v.description || '').toLowerCase().includes(kw)
      );
      if (hits.length) out[cat] = hits;
    }
    return out;
  }, [grouped, keyword]);

  const categories = Object.keys(filtered);

  const renderItem = (v: VariableLabel) => {
    const selected = initialPath === v.variablePath;
    return (
      <List.Item
        key={v.variablePath}
        onClick={() => { onPick(v.variablePath, v); onClose(); }}
        style={{
          cursor: 'pointer',
          padding: '10px 12px',
          background: selected ? '#e6f4ff' : undefined,
          borderRadius: 4,
        }}
      >
        <Space direction="vertical" size={2} style={{ width: '100%' }}>
          <Space>
            <Text strong style={{ fontSize: 14 }}>{v.displayName}</Text>
            {v.dataType && <Tag color="blue">{v.dataType}</Tag>}
            {v.unit && <Tag>{v.unit}</Tag>}
          </Space>
          <Text type="secondary" style={{ fontSize: 11, fontFamily: 'Consolas, Monaco, monospace' }}>
            {v.variablePath}
          </Text>
          {v.description && <Text type="secondary" style={{ fontSize: 12 }}>{v.description}</Text>}
        </Space>
      </List.Item>
    );
  };

  return (
    <Drawer
      title={title}
      open={open}
      onClose={onClose}
      width={520}
      placement="right"
      extra={
        <Space>
          <Button
            icon={<PlusOutlined />}
            size="small"
            type="primary"
            onClick={() => { addForm.resetFields(); setAddModalOpen(true); }}
            title="为未注册的视图列起一个中文名 (P1.5)"
          >
            添加
          </Button>
          <Button
            icon={<ReloadOutlined />}
            size="small"
            onClick={() => { variableLabelService.clearCache(); load(); }}
            title="刷新字段库"
          >
            刷新
          </Button>
        </Space>
      }
    >
      <Input.Search
        placeholder="按中文名 / 视图列 / 说明搜索"
        value={keyword}
        onChange={e => setKeyword(e.target.value)}
        style={{ marginBottom: 12 }}
        allowClear
      />
      <Spin spinning={loading}>
        {categories.length === 0 ? (
          <Empty description={keyword ? '没有匹配的字段' : '注册表为空'} />
        ) : (
          <Tabs
            activeKey={activeCategory && filtered[activeCategory] ? activeCategory : categories[0]}
            onChange={setActiveCategory}
            items={categories.map(cat => ({
              key: cat,
              label: `${cat} (${filtered[cat].length})`,
              children: (
                <List
                  dataSource={filtered[cat]}
                  renderItem={renderItem}
                  size="small"
                />
              ),
            }))}
          />
        )}
      </Spin>
      <div style={{ marginTop: 16, padding: 12, background: '#fafafa', borderRadius: 4, fontSize: 12, color: '#888' }}>
        <Text type="secondary">
          💡 没找到要的字段? 点击右上「+ 添加」为视图列起中文名 (会同步到全公司).
        </Text>
      </div>

      {/* P1.5: 渐进式起名 Modal */}
      <Modal
        title="为视图列起中文名"
        open={addModalOpen}
        onCancel={() => setAddModalOpen(false)}
        confirmLoading={adding}
        onOk={async () => {
          try {
            const values = await addForm.validateFields();
            setAdding(true);
            const ok = await variableLabelService.upsert({
              variablePath: values.variablePath.trim(),
              displayName: values.displayName.trim(),
              category: values.category,
              dataType: values.dataType || null,
              unit: values.unit?.trim() || null,
              description: values.description?.trim() || null,
            });
            if (ok) {
              message.success('已入库');
              setAddModalOpen(false);
              await load();
            } else {
              message.error('入库失败 (权限或重复?)');
            }
          } catch (e) {
            // form validate error
          } finally {
            setAdding(false);
          }
        }}
      >
        <Form form={addForm} layout="vertical">
          <Form.Item
            label="视图列路径"
            name="variablePath"
            rules={[
              { required: true, message: '必填' },
              { pattern: /^v_[a-zA-Z0-9_]+\.[a-zA-Z0-9_]+$/, message: '格式应为 v_xxx.col_name' },
            ]}
            extra="例: v_c_summary_agg.packaging_fee"
          >
            <Input placeholder="v_xxx.col_name" />
          </Form.Item>
          <Form.Item label="中文名" name="displayName" rules={[{ required: true, message: '必填' }]}>
            <Input placeholder="例: 包装材料费源" />
          </Form.Item>
          <Form.Item label="业务分类" name="category" rules={[{ required: true, message: '必填' }]}>
            <Select
              placeholder="选择或输入新分类"
              options={KNOWN_CATEGORIES.map(c => ({ label: c, value: c }))}
              showSearch
              allowClear
            />
          </Form.Item>
          <Form.Item label="数据类型" name="dataType">
            <Select
              placeholder="可选"
              options={KNOWN_DATATYPES.map(d => ({ label: d, value: d }))}
              allowClear
            />
          </Form.Item>
          <Form.Item label="单位" name="unit">
            <Input placeholder="例: ¥ / % / g" />
          </Form.Item>
          <Form.Item label="说明" name="description">
            <Input.TextArea rows={2} placeholder="可选" />
          </Form.Item>
        </Form>
      </Modal>
    </Drawer>
  );
};

export default VariableLabelPickerDrawer;
