import React, { useState, useEffect } from 'react';
import {
  Row,
  Col,
  Select,
  Button,
  Typography,
  Card,
  Tag,
  Space,
  Statistic,
  Table,
  Spin,
  Empty,
  message,
  Collapse,
  Badge,
} from 'antd';
import {
  SwapOutlined,
  PlusOutlined,
  MinusOutlined,
  EditOutlined,
} from '@ant-design/icons';
import { templateService } from '../../services/templateService';
import { bindingService } from '../../services/bindingService';

const { Title, Text } = Typography;

interface TemplateOption {
  id: string;
  name: string;
  version: string;
  status: string;
  templateSeriesId: string;
}

const TemplateComparison: React.FC = () => {
  const [allTemplates, setAllTemplates] = useState<TemplateOption[]>([]);
  const [templateAId, setTemplateAId] = useState<string | undefined>();
  const [templateBId, setTemplateBId] = useState<string | undefined>();
  const [comparing, setComparing] = useState(false);
  const [result, setResult] = useState<any>(null);
  const [loadingTemplates, setLoadingTemplates] = useState(false);

  // Load PUBLISHED + ARCHIVED templates
  useEffect(() => {
    setLoadingTemplates(true);
    Promise.all([
      templateService.list({ status: 'PUBLISHED', size: 200 }),
      templateService.list({ status: 'ARCHIVED', size: 200 }),
    ])
      .then(([pubRes, arcRes]) => {
        const all = [...(pubRes.data || []), ...(arcRes.data || [])];
        setAllTemplates(all);
      })
      .catch(() => message.error('加载模板列表失败'))
      .finally(() => setLoadingTemplates(false));
  }, []);

  const handleCompare = async () => {
    if (!templateAId || !templateBId) {
      message.warning('请选择两个模板');
      return;
    }
    if (templateAId === templateBId) {
      message.warning('请选择不同的模板');
      return;
    }
    setComparing(true);
    setResult(null);
    try {
      const res = await bindingService.compareTemplates(templateAId, templateBId);
      setResult(res.data);
    } catch (e: any) {
      message.error(e.message || '比较失败');
    } finally {
      setComparing(false);
    }
  };

  const templateOptions = allTemplates.map((t) => ({
    value: t.id,
    label: `${t.name}  (${t.version || '-'})  [${t.status === 'PUBLISHED' ? '已发布' : '已归档'}]`,
  }));

  const renderStatistics = (stats: any) => (
    <Row gutter={16} style={{ marginBottom: 24 }}>
      <Col span={5}>
        <Card size="small">
          <Statistic title="总差异数" value={stats.totalDiffs} valueStyle={{ color: stats.totalDiffs === 0 ? '#52c41a' : '#1677ff' }} />
        </Card>
      </Col>
      <Col span={5}>
        <Card size="small">
          <Statistic title="新增" value={stats.added} prefix={<PlusOutlined />} valueStyle={{ color: '#52c41a' }} />
        </Card>
      </Col>
      <Col span={5}>
        <Card size="small">
          <Statistic title="删除" value={stats.removed} prefix={<MinusOutlined />} valueStyle={{ color: '#ff4d4f' }} />
        </Card>
      </Col>
      <Col span={5}>
        <Card size="small">
          <Statistic title="修改" value={stats.modified} prefix={<EditOutlined />} valueStyle={{ color: '#fa8c16' }} />
        </Card>
      </Col>
      <Col span={4}>
        <Card size="small">
          <Statistic
            title="相似度"
            value={stats.similarityPercent}
            suffix="%"
            valueStyle={{ color: stats.similarityPercent >= 80 ? '#52c41a' : stats.similarityPercent >= 50 ? '#fa8c16' : '#ff4d4f' }}
          />
        </Card>
      </Col>
    </Row>
  );

  const renderMetadataDiff = (metadata: any) => {
    const rows = [
      { field: '名称', a: metadata.name?.valueA, b: metadata.name?.valueB, changed: metadata.name?.changed },
      { field: '版本', a: metadata.version?.valueA, b: metadata.version?.valueB, changed: metadata.version?.changed },
      { field: '分类', a: metadata.category?.valueA, b: metadata.category?.valueB, changed: metadata.category?.changed },
      { field: '描述', a: metadata.description?.valueA, b: metadata.description?.valueB, changed: metadata.description?.changed },
    ];
    return (
      <Card size="small" title="基本信息对比" style={{ marginBottom: 16 }}>
        <Table
          size="small"
          dataSource={rows}
          rowKey="field"
          pagination={false}
          columns={[
            { title: '字段', dataIndex: 'field', width: 80 },
            {
              title: `模板 A (${result.templateAVersion || '-'})`,
              dataIndex: 'a',
              render: (v: any) => <Text>{v ?? '-'}</Text>,
            },
            {
              title: `模板 B (${result.templateBVersion || '-'})`,
              dataIndex: 'b',
              render: (v: any, row: any) => (
                <Text style={{ color: row.changed ? '#fa8c16' : undefined }}>{v ?? '-'}</Text>
              ),
            },
            {
              title: '状态',
              dataIndex: 'changed',
              width: 80,
              render: (v: boolean) => v ? <Tag color="orange">已修改</Tag> : <Tag color="green">相同</Tag>,
            },
          ]}
          rowClassName={(row: any) => row.changed ? 'diff-row-modified' : ''}
        />
      </Card>
    );
  };

  const renderAttributesDiff = (attrs: any) => {
    if (!attrs) return null;
    const { added, removed, modified } = attrs;
    const totalChanges = (added?.length || 0) + (removed?.length || 0) + (modified?.length || 0);

    return (
      <Card
        size="small"
        title={
          <Space>
            产品属性差异
            <Badge count={totalChanges} style={{ backgroundColor: totalChanges > 0 ? '#fa8c16' : '#52c41a' }} showZero />
          </Space>
        }
        style={{ marginBottom: 16 }}
      >
        {totalChanges === 0 ? (
          <Text type="secondary">产品属性无差异</Text>
        ) : (
          <>
            {added?.length > 0 && (
              <div style={{ marginBottom: 8 }}>
                <Text strong style={{ color: '#52c41a' }}>新增 ({added.length})</Text>
                <div style={{ marginTop: 4, display: 'flex', flexWrap: 'wrap', gap: 4 }}>
                  {added.map((a: any, i: number) => (
                    <Tag key={i} color="green">{a.name || JSON.stringify(a)}</Tag>
                  ))}
                </div>
              </div>
            )}
            {removed?.length > 0 && (
              <div style={{ marginBottom: 8 }}>
                <Text strong style={{ color: '#ff4d4f' }}>删除 ({removed.length})</Text>
                <div style={{ marginTop: 4, display: 'flex', flexWrap: 'wrap', gap: 4 }}>
                  {removed.map((r: any, i: number) => (
                    <Tag key={i} color="red">{r.name || JSON.stringify(r)}</Tag>
                  ))}
                </div>
              </div>
            )}
            {modified?.length > 0 && (
              <div>
                <Text strong style={{ color: '#fa8c16' }}>修改 ({modified.length})</Text>
                <div style={{ marginTop: 4, display: 'flex', flexWrap: 'wrap', gap: 4 }}>
                  {modified.map((m: any, i: number) => (
                    <Tag key={i} color="orange">{m.fieldName}</Tag>
                  ))}
                </div>
              </div>
            )}
          </>
        )}
      </Card>
    );
  };

  const renderComponentsDiff = (components: any) => {
    if (!components) return null;
    const { addedTabs, removedTabs, modifiedTabs } = components;
    const totalChanges = (addedTabs?.length || 0) + (removedTabs?.length || 0) + (modifiedTabs?.length || 0);

    return (
      <Card
        size="small"
        title={
          <Space>
            组件/页签差异
            <Badge count={totalChanges} style={{ backgroundColor: totalChanges > 0 ? '#fa8c16' : '#52c41a' }} showZero />
          </Space>
        }
      >
        {totalChanges === 0 ? (
          <Text type="secondary">组件/页签无差异</Text>
        ) : (
          <>
            {addedTabs?.length > 0 && (
              <div style={{ marginBottom: 12 }}>
                <Text strong style={{ color: '#52c41a' }}>新增页签 ({addedTabs.length})</Text>
                <div style={{ marginTop: 4, display: 'flex', flexWrap: 'wrap', gap: 4 }}>
                  {addedTabs.map((tab: any, i: number) => (
                    <Tag key={i} color="green">{tab.tabName || '未命名'}</Tag>
                  ))}
                </div>
              </div>
            )}
            {removedTabs?.length > 0 && (
              <div style={{ marginBottom: 12 }}>
                <Text strong style={{ color: '#ff4d4f' }}>删除页签 ({removedTabs.length})</Text>
                <div style={{ marginTop: 4, display: 'flex', flexWrap: 'wrap', gap: 4 }}>
                  {removedTabs.map((tab: any, i: number) => (
                    <Tag key={i} color="red">{tab.tabName || '未命名'}</Tag>
                  ))}
                </div>
              </div>
            )}
            {modifiedTabs?.length > 0 && (
              <Collapse
                size="small"
                items={modifiedTabs.map((tab: any, i: number) => ({
                  key: i,
                  label: (
                    <Space>
                      <Tag color="orange">{tab.tabName}</Tag>
                      {tab.addedFields?.length > 0 && <Tag color="green">+{tab.addedFields.length} 字段</Tag>}
                      {tab.removedFields?.length > 0 && <Tag color="red">-{tab.removedFields.length} 字段</Tag>}
                      {tab.fieldChanges?.length > 0 && <Tag color="orange">~{tab.fieldChanges.length} 变化</Tag>}
                    </Space>
                  ),
                  children: (
                    <div>
                      {tab.addedFields?.length > 0 && (
                        <div style={{ marginBottom: 6 }}>
                          <Text type="secondary" style={{ fontSize: 12 }}>新增字段：</Text>
                          {tab.addedFields.map((f: string, j: number) => <Tag key={j} color="green">{f}</Tag>)}
                        </div>
                      )}
                      {tab.removedFields?.length > 0 && (
                        <div style={{ marginBottom: 6 }}>
                          <Text type="secondary" style={{ fontSize: 12 }}>删除字段：</Text>
                          {tab.removedFields.map((f: string, j: number) => <Tag key={j} color="red">{f}</Tag>)}
                        </div>
                      )}
                      {tab.fieldChanges?.length > 0 && (
                        <div>
                          <Text type="secondary" style={{ fontSize: 12 }}>字段变化：{tab.fieldChanges.length} 项</Text>
                        </div>
                      )}
                    </div>
                  ),
                }))}
              />
            )}
          </>
        )}
      </Card>
    );
  };

  return (
    <div>
      <Title level={4}>模板版本对比</Title>

      {/* Selection panel */}
      <Card size="small" style={{ marginBottom: 24 }}>
        <Row gutter={16} align="middle">
          <Col span={10}>
            <div style={{ marginBottom: 4 }}>
              <Text strong>模板 A</Text>
            </div>
            <Select
              placeholder="选择模板 A"
              showSearch
              loading={loadingTemplates}
              style={{ width: '100%' }}
              value={templateAId}
              onChange={setTemplateAId}
              filterOption={(input, option) =>
                (option?.label as string ?? '').toLowerCase().includes(input.toLowerCase())
              }
              options={templateOptions}
            />
          </Col>
          <Col span={4} style={{ textAlign: 'center' }}>
            <Button
              icon={<SwapOutlined />}
              onClick={() => {
                const tmp = templateAId;
                setTemplateAId(templateBId);
                setTemplateBId(tmp);
                setResult(null);
              }}
              disabled={!templateAId || !templateBId}
            >
              互换
            </Button>
          </Col>
          <Col span={10}>
            <div style={{ marginBottom: 4 }}>
              <Text strong>模板 B</Text>
            </div>
            <Select
              placeholder="选择模板 B"
              showSearch
              loading={loadingTemplates}
              style={{ width: '100%' }}
              value={templateBId}
              onChange={(v) => { setTemplateBId(v); setResult(null); }}
              filterOption={(input, option) =>
                (option?.label as string ?? '').toLowerCase().includes(input.toLowerCase())
              }
              options={templateOptions}
            />
          </Col>
        </Row>
        <Row style={{ marginTop: 16 }} justify="center">
          <Button
            type="primary"
            size="large"
            loading={comparing}
            onClick={handleCompare}
            disabled={!templateAId || !templateBId || templateAId === templateBId}
          >
            开始对比
          </Button>
        </Row>
      </Card>

      {/* Results */}
      <Spin spinning={comparing}>
        {result ? (
          <>
            <Row style={{ marginBottom: 16 }}>
              <Col span={12}>
                <Card size="small" style={{ background: '#f6ffed', border: '1px solid #b7eb8f' }}>
                  <Text strong>模板 A：</Text>
                  <Text>{result.templateAName}</Text>
                  <Tag color="blue" style={{ marginLeft: 8 }}>{result.templateAVersion || '-'}</Tag>
                </Card>
              </Col>
              <Col span={12}>
                <Card size="small" style={{ background: '#fff7e6', border: '1px solid #ffd591' }}>
                  <Text strong>模板 B：</Text>
                  <Text>{result.templateBName}</Text>
                  <Tag color="orange" style={{ marginLeft: 8 }}>{result.templateBVersion || '-'}</Tag>
                </Card>
              </Col>
            </Row>

            {result.stats && renderStatistics(result.stats)}
            {result.metadata && renderMetadataDiff(result.metadata)}
            {result.productAttributes && renderAttributesDiff(result.productAttributes)}
            {result.components && renderComponentsDiff(result.components)}

            {result.stats?.totalDiffs === 0 && (
              <Card style={{ textAlign: 'center', marginTop: 16 }}>
                <Text type="success" style={{ fontSize: 16 }}>两个模板内容完全相同</Text>
              </Card>
            )}
          </>
        ) : (
          !comparing && (
            <Empty
              description="选择两个模板后点击「开始对比」"
              style={{ marginTop: 60 }}
            />
          )
        )}
      </Spin>
    </div>
  );
};

export default TemplateComparison;
