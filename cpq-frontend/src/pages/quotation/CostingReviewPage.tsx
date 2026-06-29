/**
 * CostingReviewPage —— 只读核价工作台（Phase 1）
 *
 * 路由：/quotations/:id/costing-review
 * 进入方式：核价管理列表"进入核价"动作跳转
 *
 * 功能：
 *   - 顶部 Card：报价单号 + 状态 Tag + 操作栏（核价通过 / 驳回）
 *   - 正文：<ProductDetailViews quotation={q} />（复用，反 AP-50）
 *   - 驳回操作走 Drawer（填驳回原因必填），调 costingOrderService.reject
 *   - canReview = ['PRICING_MANAGER','SYSTEM_ADMIN'].includes(role) && status==='SUBMITTED'
 */
import React, { useEffect, useState } from 'react';
import {
  Button,
  Card,
  Col,
  Drawer,
  Form,
  Input,
  Row,
  Space,
  Spin,
  Tag,
  Typography,
  message,
} from 'antd';
import {
  ArrowLeftOutlined,
  CheckCircleOutlined,
  CloseCircleOutlined,
} from '@ant-design/icons';
import { useNavigate, useParams } from 'react-router-dom';
import { quotationService } from '../../services/quotationService';
import { costingOrderService } from '../../services/costingOrderService';
import { useAuthStore } from '../../stores/authStore';
import ProductDetailViews from './ProductDetailViews';

const { Title } = Typography;

const statusConfig: Record<string, { label: string; color: string }> = {
  DRAFT: { label: '草稿', color: 'default' },
  SUBMITTED: { label: '审批中', color: 'processing' },
  APPROVED: { label: '已批准', color: 'success' },
  SENT: { label: '已发送', color: 'cyan' },
  ACCEPTED: { label: '已接受', color: 'green' },
  REJECTED: { label: '已退回', color: 'error' },
  EXPIRED: { label: '已过期', color: 'warning' },
  COSTING_REJECTED: { label: '核价驳回', color: 'error' },
};

const CostingReviewPage: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const user = useAuthStore((s) => s.user);

  const [quotation, setQuotation] = useState<any>(null);
  const [loading, setLoading] = useState(true);
  const [actionLoading, setActionLoading] = useState(false);
  const [rejectDrawerOpen, setRejectDrawerOpen] = useState(false);
  const [rejectForm] = Form.useForm();

  const loadQuotation = async () => {
    if (!id) return;
    setLoading(true);
    try {
      const res = await quotationService.getById(id);
      setQuotation(res.data);
    } catch (e: any) {
      message.error(e?.message || '加载失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadQuotation();
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id]);

  const canReview =
    ['PRICING_MANAGER', 'SYSTEM_ADMIN'].includes(user?.role ?? '') &&
    quotation?.status === 'SUBMITTED';

  const handleApprove = async () => {
    setActionLoading(true);
    try {
      await costingOrderService.approve(id!);
      message.success('核价通过');
      navigate('/costing-summary');
    } catch (e: any) {
      message.error(e?.message || '操作失败');
    } finally {
      setActionLoading(false);
    }
  };

  const handleReject = async (values: { comment: string }) => {
    setActionLoading(true);
    try {
      await costingOrderService.reject(id!, values.comment);
      message.success('已驳回');
      setRejectDrawerOpen(false);
      rejectForm.resetFields();
      navigate('/costing-summary');
    } catch (e: any) {
      message.error(e?.message || '操作失败');
    } finally {
      setActionLoading(false);
    }
  };

  if (loading) {
    return (
      <div style={{ textAlign: 'center', padding: 80 }}>
        <Spin size="large" />
      </div>
    );
  }

  if (!quotation) {
    return <div style={{ textAlign: 'center', padding: 80 }}>报价单不存在</div>;
  }

  const statusInfo = statusConfig[quotation.status] || {
    label: quotation.status,
    color: 'default',
  };

  return (
    <div style={{ padding: 24 }}>
      {/* 操作栏 */}
      <Card style={{ marginBottom: 16 }}>
        <Row justify="space-between" align="middle">
          <Col>
            <Space align="center">
              <Button
                type="text"
                icon={<ArrowLeftOutlined />}
                onClick={() => navigate('/costing-summary')}
              >
                返回核价列表
              </Button>
              <Title level={4} style={{ margin: 0 }}>
                {quotation.quotationNumber}
              </Title>
              <Tag color={statusInfo.color} style={{ fontSize: 13 }}>
                {statusInfo.label}
              </Tag>
            </Space>
          </Col>
          {canReview && (
            <Col>
              <Space>
                <Button
                  type="primary"
                  style={{ backgroundColor: '#52c41a', borderColor: '#52c41a' }}
                  icon={<CheckCircleOutlined />}
                  loading={actionLoading}
                  onClick={handleApprove}
                >
                  核价通过
                </Button>
                <Button
                  danger
                  icon={<CloseCircleOutlined />}
                  onClick={() => {
                    rejectForm.resetFields();
                    setRejectDrawerOpen(true);
                  }}
                >
                  驳回
                </Button>
              </Space>
            </Col>
          )}
        </Row>
      </Card>

      {/* 产品明细两级视图（复用 ProductDetailViews，反 AP-50 单源） */}
      <ProductDetailViews quotation={quotation} />

      {/* 驳回 Drawer */}
      <Drawer
        title="驳回报价单"
        placement="right"
        width={480}
        open={rejectDrawerOpen}
        onClose={() => setRejectDrawerOpen(false)}
        destroyOnClose
        extra={
          <Space>
            <Button onClick={() => setRejectDrawerOpen(false)}>取消</Button>
            <Button
              danger
              loading={actionLoading}
              onClick={() => rejectForm.submit()}
            >
              确认驳回
            </Button>
          </Space>
        }
      >
        <Form form={rejectForm} layout="vertical" onFinish={handleReject}>
          <Form.Item
            name="comment"
            label="驳回原因"
            rules={[{ required: true, message: '请填写驳回原因' }]}
          >
            <Input.TextArea
              rows={4}
              placeholder="请填写驳回原因（必填）"
              maxLength={500}
              showCount
            />
          </Form.Item>
        </Form>
      </Drawer>
    </div>
  );
};

export default CostingReviewPage;
