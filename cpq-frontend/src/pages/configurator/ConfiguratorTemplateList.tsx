import React, { useEffect, useState } from 'react';
import { Card, Table, Tag, Button, Input, Select, Space, message, Drawer, Form, Switch } from 'antd';
import { useNavigate } from 'react-router-dom';
import { configuratorTemplateService } from '../../services/configuratorService';
import type { ConfiguratorTemplate, TemplateStatus } from '../../types/configurator';

const ConfiguratorTemplateList: React.FC = () => {
  const navigate = useNavigate();
  const [data, setData] = useState<ConfiguratorTemplate[]>([]);
  const [loading, setLoading] = useState(false);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [status, setStatus] = useState<string | undefined>();
  const [keyword, setKeyword] = useState('');

  const [drawerOpen, setDrawerOpen] = useState(false);
  const [form] = Form.useForm();

  const load = async () => {
    setLoading(true);
    try {
      const res: any = await configuratorTemplateService.list({ page, size, status, keyword });
      setData(res.data?.content || []);
      setTotal(res.data?.totalElements || 0);
    } catch (e: any) {
      message.error('加载失败：' + (e?.message || ''));
    } finally {
      setLoading(false);
    }
  };
  useEffect(() => { load(); /* eslint-disable-next-line */ }, [page, size, status]);

  const openCreate = () => {
    form.resetFields();
    form.setFieldsValue({ status: 'DRAFT', showPrice: true });
    setDrawerOpen(true);
  };
  const save = async () => {
    try {
      const v = await form.validateFields();
      await configuratorTemplateService.create(v);
      message.success('已创建');
      setDrawerOpen(false);
      load();
    } catch (e: any) {
      if (e?.errorFields) return;
      message.error('创建失败：' + (e?.message || ''));
    }
  };

  return (
    <div style={{ padding: 16 }}>
      <Card title="🛒 选配模板管理" extra={
        <Space>
          <Input.Search placeholder="代码 / 名称" allowClear style={{ width: 240 }}
                        value={keyword}
                        onChange={e => setKeyword(e.target.value)}
                        onSearch={() => { setPage(0); load(); }} />
          <Select placeholder="状态" allowClear style={{ width: 140 }}
                  options={[
                    { value: 'DRAFT', label: 'DRAFT' },
                    { value: 'PUBLISHED', label: 'PUBLISHED' },
                    { value: 'ARCHIVED', label: 'ARCHIVED' },
                  ]}
                  value={status} onChange={setStatus} />
          <Button onClick={() => message.info('复制模板 — 后续切片：选源模板 → 改 code → 提交复制')}>📋 复制模板</Button>
          <Button onClick={() => message.info('导入 JSON 模板 — 后续切片')}>📥 导入</Button>
          <Button type="primary" onClick={openCreate}>+ 新建模板</Button>
        </Space>
      }>
        <Table<ConfiguratorTemplate>
          rowKey="id"
          loading={loading}
          dataSource={data}
          pagination={{ current: page + 1, pageSize: size, total,
            onChange: (p, s) => { setPage(p - 1); setSize(s); } }}
          columns={[
            { title: '模板代码', dataIndex: 'code', width: 220, render: (v, r) => (
              <a onClick={() => navigate(`/system/configurator-templates/${r.id}`)}>{v}</a>
            )},
            { title: '名称', dataIndex: 'name' },
            { title: '基础料号', dataIndex: 'basePartNo', width: 180 },
            { title: '品类', dataIndex: 'category', width: 110 },
            { title: '展示价格', dataIndex: 'showPrice', width: 100, align: 'center',
              render: (v: boolean, r: ConfiguratorTemplate) => (
                <Switch checked={v} size="small"
                        onChange={async (checked) => {
                          try {
                            await configuratorTemplateService.update(r.id, { showPrice: checked });
                            message.success(checked ? '已开启展示价格' : '已隐藏价格栏');
                            load();
                          } catch (e: any) { message.error('切换失败：' + (e?.message || '')); }
                        }} />
              ) },
            { title: '版本', dataIndex: 'version', width: 60 },
            { title: '状态', dataIndex: 'status', width: 110, render: (s: TemplateStatus) => {
              const color = s === 'PUBLISHED' ? 'green' : s === 'DRAFT' ? 'default' : 'red';
              return <Tag color={color}>{s}</Tag>;
            }},
            { title: '更新时间', dataIndex: 'updatedAt', width: 170,
              render: (v: string) => v ? new Date(v).toLocaleString() : '-' },
            { title: '操作', width: 100, render: (_, r) => (
              <a onClick={() => navigate(`/system/configurator-templates/${r.id}`)}>✏️ 编辑</a>
            )},
          ]}
        />
      </Card>

      <Drawer
        title="+ 新建选配模板"
        width={560}
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        extra={
          <Space>
            <Button onClick={() => setDrawerOpen(false)}>取消</Button>
            <Button type="primary" onClick={save}>✓ 创建</Button>
          </Space>
        }
      >
        <Form form={form} layout="vertical">
          <Form.Item label="模板代码" name="code" rules={[{ required: true, max: 64 }]}>
            <Input placeholder="如 CFG-TPL-CONTACT-STRIP" />
          </Form.Item>
          <Form.Item label="模板名称" name="name" rules={[{ required: true, max: 128 }]}>
            <Input placeholder="如 接触片产品选配" />
          </Form.Item>
          <Form.Item label="基础料号" name="basePartNo">
            <Input placeholder="可选" />
          </Form.Item>
          <Form.Item label="品类" name="category">
            <Select allowClear placeholder="选择品类"
                    options={[
                      { value: '接触片', label: '接触片' },
                      { value: '接触簧片', label: '接触簧片' },
                      { value: '端子', label: '端子' },
                      { value: '电机', label: '电机' },
                    ]} />
          </Form.Item>
          <Form.Item label="描述" name="description">
            <Input.TextArea rows={3} />
          </Form.Item>
          <Form.Item label="展示价格" name="showPrice" valuePropName="checked"
                     help="false 时选配页底部价格栏整体隐藏（适用客户初期沟通）">
            <Switch defaultChecked />
          </Form.Item>
        </Form>
      </Drawer>
    </div>
  );
};

export default ConfiguratorTemplateList;
