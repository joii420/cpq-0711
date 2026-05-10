import React, { useRef, useState } from 'react';
import { Input, Select, Button, Dropdown, Modal, Form, Checkbox, Tooltip } from 'antd';
import { DeleteOutlined, PlusOutlined, HolderOutlined, EditOutlined } from '@ant-design/icons';
import type { ProductAttribute, ProductAttrSource } from './types';
import './styles.css';

const BUILT_IN_ATTRS: { source: ProductAttrSource; name: string; field_type: ProductAttribute['field_type'] }[] = [
  { source: 'PRODUCT_SPEC', name: '产品规格', field_type: 'FIXED_VALUE' },
  { source: 'PRODUCT_CATEGORY', name: '产品分类', field_type: 'FIXED_VALUE' },
  { source: 'PRODUCT_TAGS', name: '产品标签', field_type: 'FIXED_VALUE' },
  { source: 'PRODUCT_DRAWING_NO', name: '图号', field_type: 'FIXED_VALUE' },
  { source: 'PRODUCT_DIMENSION', name: '尺寸', field_type: 'FIXED_VALUE' },
  { source: 'PRODUCT_MATERIAL', name: '材质', field_type: 'FIXED_VALUE' },
];

const TYPE_OPTIONS = [
  { value: 'TEXT', label: '文本' },
  { value: 'NUMBER', label: '数值' },
  { value: 'TEXTAREA', label: '多行文本' },
  { value: 'FIXED_VALUE', label: '固定值' },
];

const typeLabel = (t: string) => TYPE_OPTIONS.find(o => o.value === t)?.label || t;

interface ProductAttributesGridProps {
  attributes: ProductAttribute[];
  onChange: (attrs: ProductAttribute[]) => void;
  disabled?: boolean;
}

const ProductAttributesGrid: React.FC<ProductAttributesGridProps> = ({
  attributes,
  onChange,
  disabled = false,
}) => {
  const dragIndex = useRef<number | null>(null);
  const dragOverIndex = useRef<number | null>(null);
  const [modalOpen, setModalOpen] = useState(false);
  const [editingKey, setEditingKey] = useState<string | null>(null); // null = new, string = editing
  const [form] = Form.useForm();

  const isBuiltIn = (attr: ProductAttribute) => attr.source && attr.source !== 'CUSTOM';

  // Open modal for new custom attribute
  const openNew = () => {
    setEditingKey(null);
    form.resetFields();
    form.setFieldsValue({ field_type: 'TEXT', required: false });
    setModalOpen(true);
  };

  // Open modal to edit existing custom attribute
  const openEdit = (attr: ProductAttribute) => {
    setEditingKey(attr.key);
    form.setFieldsValue({
      name: attr.name,
      field_type: attr.field_type,
      required: attr.required,
      default_value: attr.default_value || '',
    });
    setModalOpen(true);
  };

  const handleModalOk = async () => {
    try {
      const values = await form.validateFields();
      if (editingKey) {
        // Update existing
        onChange(attributes.map(a => a.key === editingKey ? {
          ...a,
          name: values.name,
          field_type: values.field_type,
          required: values.required || false,
          default_value: values.default_value || '',
        } : a));
      } else {
        // Add new
        onChange([...attributes, {
          key: Date.now().toString(),
          name: values.name,
          field_type: values.field_type,
          required: values.required || false,
          default_value: values.default_value || '',
          sort_order: attributes.length,
          source: 'CUSTOM',
        }]);
      }
      setModalOpen(false);
    } catch { /* validation */ }
  };

  const addBuiltIn = (builtIn: typeof BUILT_IN_ATTRS[number]) => {
    if (attributes.some(a => a.source === builtIn.source)) return;
    onChange([...attributes, {
      key: Date.now().toString(),
      name: builtIn.name,
      field_type: builtIn.field_type,
      required: false,
      default_value: '',
      sort_order: attributes.length,
      source: builtIn.source,
    }]);
  };

  const removeAttr = (key: string) => onChange(attributes.filter(a => a.key !== key));

  const handleDragStart = (index: number) => { dragIndex.current = index; };
  const handleDragOver = (e: React.DragEvent, index: number) => { e.preventDefault(); dragOverIndex.current = index; };
  const handleDrop = () => {
    if (dragIndex.current === null || dragOverIndex.current === null || dragIndex.current === dragOverIndex.current) return;
    const next = [...attributes];
    const [removed] = next.splice(dragIndex.current, 1);
    next.splice(dragOverIndex.current, 0, removed);
    onChange(next.map((a, i) => ({ ...a, sort_order: i })));
    dragIndex.current = null;
    dragOverIndex.current = null;
  };

  const addMenuItems = [
    ...BUILT_IN_ATTRS.map(b => ({
      key: b.source,
      label: b.name,
      disabled: attributes.some(a => a.source === b.source),
    })),
    { type: 'divider' as const, key: 'divider' },
    { key: 'CUSTOM', label: '自定义属性' },
  ];

  const handleMenuClick = ({ key }: { key: string }) => {
    if (key === 'CUSTOM') openNew();
    else {
      const b = BUILT_IN_ATTRS.find(x => x.source === key);
      if (b) addBuiltIn(b);
    }
  };

  return (
    <div className="tm-product-attributes">
      <div className="tm-attributes-header">
        <span className="tm-attributes-title">📦 产品属性字段</span>
        {!disabled && (
          <Dropdown menu={{ items: addMenuItems, onClick: handleMenuClick }} trigger={['click']}>
            <Button size="small" type="primary" icon={<PlusOutlined />} style={{ background: '#52c41a', borderColor: '#52c41a' }}>
              添加属性
            </Button>
          </Dropdown>
        )}
      </div>

      {attributes.length === 0 ? (
        <div style={{ color: '#999', fontSize: 13, textAlign: 'center', padding: '8px 0' }}>暂无产品属性</div>
      ) : (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 6 }}>
          {attributes.map((attr, index) => {
            const builtIn = isBuiltIn(attr);
            const borderColor = builtIn ? '#1677ff' : '#52c41a';
            const hasFixedVal = !builtIn && attr.field_type === 'FIXED_VALUE' && attr.default_value;
            const displayParts: string[] = [attr.name];
            if (!builtIn) displayParts.push(typeLabel(attr.field_type));
            if (hasFixedVal) displayParts.push(`=${attr.default_value}`);

            return (
              <div
                key={attr.key}
                draggable={!disabled}
                onDragStart={() => handleDragStart(index)}
                onDragOver={(e) => handleDragOver(e, index)}
                onDrop={handleDrop}
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 5,
                  padding: '4px 8px',
                  borderRadius: 4,
                  border: '1px solid #e8e8e8',
                  borderLeftWidth: 3,
                  borderLeftColor: borderColor,
                  background: '#fff',
                  cursor: disabled ? 'default' : 'grab',
                  fontSize: 12,
                  minHeight: 30,
                }}
              >
                {!disabled && <HolderOutlined style={{ color: '#ccc', cursor: 'grab', flexShrink: 0, fontSize: 10 }} />}
                <Tooltip title={displayParts.join(' | ')}>
                  <span style={{ flex: 1, fontWeight: 500, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                    {attr.name}
                  </span>
                </Tooltip>
                {!builtIn && (
                  <span style={{ color: '#999', fontSize: 10, flexShrink: 0 }}>{typeLabel(attr.field_type)}</span>
                )}
                {hasFixedVal && (
                  <Tooltip title={attr.default_value}>
                    <span style={{ color: '#d48806', fontSize: 10, flexShrink: 0, maxWidth: 50, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                      ={attr.default_value}
                    </span>
                  </Tooltip>
                )}
                {!builtIn && attr.required && (
                  <span style={{ color: '#e53e3e', fontSize: 10, flexShrink: 0 }}>*</span>
                )}
                {!disabled && !builtIn && (
                  <EditOutlined
                    style={{ color: '#1677ff', fontSize: 11, cursor: 'pointer', flexShrink: 0 }}
                    onClick={(e) => { e.stopPropagation(); openEdit(attr); }}
                  />
                )}
                {!disabled && (
                  <DeleteOutlined
                    style={{ color: '#ccc', fontSize: 11, cursor: 'pointer', flexShrink: 0 }}
                    onClick={() => removeAttr(attr.key)}
                  />
                )}
              </div>
            );
          })}
        </div>
      )}

      {/* Custom attribute modal (add / edit) */}
      <Modal
        title={editingKey ? '编辑自定义属性' : '添加自定义属性'}
        open={modalOpen}
        onOk={handleModalOk}
        onCancel={() => setModalOpen(false)}
        okText="确定"
        cancelText="取消"
        width={400}
        destroyOnClose
      >
        <Form form={form} layout="vertical" size="small">
          <Form.Item name="name" label="属性名称" rules={[{ required: true, message: '请输入属性名称' }]}>
            <Input placeholder="请输入属性名称" />
          </Form.Item>
          <Form.Item name="field_type" label="属性类型" initialValue="TEXT">
            <Select options={TYPE_OPTIONS} />
          </Form.Item>
          <Form.Item noStyle shouldUpdate={(prev, cur) => prev.field_type !== cur.field_type}>
            {({ getFieldValue }) =>
              getFieldValue('field_type') === 'FIXED_VALUE' ? (
                <Form.Item name="default_value" label="默认值">
                  <Input placeholder="请输入默认值" />
                </Form.Item>
              ) : null
            }
          </Form.Item>
          <Form.Item name="required" valuePropName="checked">
            <Checkbox>必填</Checkbox>
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default ProductAttributesGrid;
