import React, { useEffect, useState } from 'react';
import { Card, Row, Col, Tag, Button, message, Empty, Input, Select, Space } from 'antd';
import { useNavigate } from 'react-router-dom';
import { configuratorTemplateService } from '../../services/configuratorService';

/**
 * v0.4 销售路径起点：模板挑选页（骨架）
 * 后续切片：客户选择器 + 卡片网格 + 筛选工具栏
 */
const MOCK_CUSTOMERS = [
  { id: 'cus-rockwell-001', name: '罗克韦尔自动化', tier: 'VIP' },
  { id: 'cus-siemens-002',  name: '西门子', tier: 'STD' },
  { id: 'cus-mb-003',       name: '奔驰中国', tier: 'VIP' },
  { id: 'cus-trial-099',    name: '某试用客户', tier: 'TRIAL' },
];

const ConfiguratorStartPage: React.FC = () => {
  const navigate = useNavigate();
  const [templates, setTemplates] = useState<any[]>([]);
  const [loading, setLoading] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [category, setCategory] = useState<string | undefined>();
  const [customerId, setCustomerId] = useState('cus-rockwell-001');
  const [favorites, setFavorites] = useState<Set<string>>(() => {
    try { return new Set(JSON.parse(localStorage.getItem('cpq.tpl.favorites') || '[]')); }
    catch { return new Set(); }
  });
  const [onlyFav, setOnlyFav] = useState(false);
  const toggleFav = (id: string) => {
    const next = new Set(favorites);
    if (next.has(id)) next.delete(id); else next.add(id);
    localStorage.setItem('cpq.tpl.favorites', JSON.stringify([...next]));
    setFavorites(next);
  };

  const load = async () => {
    setLoading(true);
    try {
      const res: any = await configuratorTemplateService.list({ page: 0, size: 50, status: 'PUBLISHED', keyword, category });
      setTemplates(res.data?.content || []);
    } catch (e: any) {
      message.error('加载模板失败：' + (e?.message || ''));
    } finally { setLoading(false); }
  };
  useEffect(() => { load(); /* eslint-disable-next-line */ }, [category]);

  const enterTemplate = (tplId: string) => {
    navigate(`/configurator/run/${tplId}?customer=${customerId}`);
  };

  const cust = MOCK_CUSTOMERS.find(c => c.id === customerId);

  return (
    <div style={{ padding: 16 }}>
      <Card title="🎯 开始选配 — 挑选模板">
        {/* 客户选择器 */}
        <div style={{ background: 'linear-gradient(90deg,#fffbe6,#fff7e6)',
                      border: '1px solid #ffd591', padding: '10px 14px',
                      borderRadius: 6, marginBottom: 14, display: 'flex', alignItems: 'center', gap: 12 }}>
          <span style={{ color: '#876800', fontWeight: 500 }}>📋 当前客户：</span>
          <Select value={customerId} onChange={setCustomerId} style={{ width: 280 }}
                  options={MOCK_CUSTOMERS.map(c => ({
                    value: c.id, label: `${c.name} · ${c.tier} · ${c.id}`,
                  }))} />
          {cust?.tier === 'VIP' && <Tag color="orange">VIP 客户</Tag>}
          <div style={{ flex: 1 }} />
          <span style={{ fontSize: 11.5, color: '#876800' }}>
            ⚠ 不同客户可能看到不同覆盖配置（§14 多租户，后续切片）
          </span>
        </div>

        {/* 筛选工具栏 */}
        <Space style={{ marginBottom: 14 }} wrap>
          <Input.Search placeholder="模板名 / 代码" allowClear style={{ width: 240 }}
                        value={keyword} onChange={e => setKeyword(e.target.value)} onSearch={load} />
          <Select placeholder="品类" allowClear style={{ width: 140 }} value={category} onChange={setCategory}
                  options={[
                    { value: '阀门', label: '阀门' },
                    { value: '接触片', label: '接触片' },
                    { value: '接触簧片', label: '接触簧片' },
                    { value: '端子', label: '端子' },
                    { value: '电机', label: '电机' },
                  ]} />
          <Button type={onlyFav ? 'primary' : 'default'} onClick={() => setOnlyFav(!onlyFav)}>
            ⭐ 我的收藏 ({favorites.size})
          </Button>
        </Space>

        {loading ? <div style={{ padding: 40, textAlign: 'center' }}>加载中...</div>
         : templates.length === 0 ? (
          <Empty description="暂无 PUBLISHED 状态的选配模板。请联系 PM 在「⚙️ 系统管理 / 🛒 选配模板管理」中创建" />
        ) : (
          <Row gutter={[14, 14]}>
            {templates.filter(t => !onlyFav || favorites.has(t.id)).map(t => {
              const isFav = favorites.has(t.id);
              const isNew = t.updatedAt && (new Date().getTime() - new Date(t.updatedAt).getTime()) < 30 * 86400000;
              const isHot = favorites.size > 0 && isFav;  // 简化：被收藏过视为 HOT
              return (
              <Col key={t.id} xs={24} sm={12} md={8} lg={6}>
                <Card hoverable
                      cover={
                        <div style={{ height: 130, background: 'linear-gradient(135deg,#e8eef5,#f5f7fa)',
                                      display:'flex', alignItems:'center', justifyContent:'center',
                                      fontSize: 56, color: 'rgba(0,80,179,.3)', position: 'relative' }}>
                          🎲
                          {isHot && (
                            <div style={{ position: 'absolute', top: 8, left: 8, padding: '2px 8px',
                                          background: 'linear-gradient(90deg,#f5222d,#ff7a45)', color: '#fff',
                                          borderRadius: 3, fontSize: 10.5, fontWeight: 600 }}>🔥 HOT</div>
                          )}
                          {!isHot && isNew && (
                            <div style={{ position: 'absolute', top: 8, left: 8, padding: '2px 8px',
                                          background: 'linear-gradient(90deg,#13c2c2,#1890ff)', color: '#fff',
                                          borderRadius: 3, fontSize: 10.5, fontWeight: 600 }}>✨ NEW</div>
                          )}
                          <div style={{ position: 'absolute', top: 8, right: 8, display: 'flex', gap: 4 }}>
                            <a onClick={(e) => { e.stopPropagation(); toggleFav(t.id); }}
                               style={{ padding: '2px 6px', background: 'rgba(0,21,41,.85)', color: isFav ? '#faad14' : '#fff',
                                        borderRadius: 3, fontSize: 12 }}>{isFav ? '★' : '☆'}</a>
                            <span style={{ padding: '2px 8px', background: 'rgba(0,21,41,.85)', color: '#fff',
                                          borderRadius: 3, fontSize: 10.5 }}>{t.status}</span>
                          </div>
                        </div>
                      }>
                  <div style={{ fontSize: 14, fontWeight: 600, marginBottom: 4 }}>{t.name}</div>
                  <div style={{ fontSize: 11, color: '#999', marginBottom: 8 }}><code>{t.code}</code></div>
                  <div style={{ minHeight: 36, fontSize: 12, color: '#606266', lineHeight: 1.5 }}>{t.description}</div>
                  <div style={{ marginTop: 10, display: 'flex', gap: 4, flexWrap: 'wrap' }}>
                    {t.category && <Tag color="green">{t.category}</Tag>}
                    {t.showPrice && t.metadata?.base_price > 0 &&
                      <Tag color="orange">基础价 ¥{t.metadata.base_price.toLocaleString()}</Tag>}
                    {!t.showPrice && (
                      <Tag style={{ background: '#f5f5f5', color: '#909399', border: '1px solid #e0e0e0' }}>
                        💰 价格隐藏
                      </Tag>
                    )}
                  </div>
                  <Button type="primary" block style={{ marginTop: 12, background: '#52c41a', borderColor: '#52c41a' }}
                          onClick={() => enterTemplate(t.id)}>
                    🎯 进入选配 →
                  </Button>
                </Card>
              </Col>
              );
            })}
          </Row>
        )}
      </Card>
    </div>
  );
};

export default ConfiguratorStartPage;
