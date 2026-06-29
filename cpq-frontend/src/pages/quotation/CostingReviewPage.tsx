/**
 * CostingReviewPage —— 核价工作台（读冻结副本 frozen DTO）
 *
 * 路由：/costing-orders/:coid/review
 * 进入方式：核价管理列表"进入核价"/"核价单号"跳转
 *
 * 数据流：
 *   - costingOrderService.getById(coid) → detail（CostingOrderDetail）
 *   - detail.frozenDto (JSON 字符串) → frozen（报价单完整快照对象）
 *   - ProductDetailViews 以 frozen 模式渲染（无 live /templates、/global-variables 请求）
 *
 * 审批回联：
 *   - approve/reject 用 detail.quotationId（非 coid）
 *   - canReview = ['PRICING_MANAGER','SYSTEM_ADMIN'].includes(role) && detail.status === 'PENDING'
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
import { costingOrderService } from '../../services/costingOrderService';
import type { CostingOrderDetail } from '../../services/costingOrderService';
import { useAuthStore } from '../../stores/authStore';
import ProductDetailViews from './ProductDetailViews';

const { Title, Text } = Typography;

/** 核价单状态配置（与报价单 status 语义完全分离） */
const costingStatusConfig: Record<string, { label: string; color: string }> = {
  PENDING:   { label: '待核价',  color: 'processing' },
  APPROVED:  { label: '已审核',  color: 'success'    },
  REJECTED:  { label: '已驳回',  color: 'error'      },
  WITHDRAWN: { label: '已撤回',  color: 'default'    },
};

const CostingReviewPage: React.FC = () => {
  const { coid } = useParams<{ coid: string }>();
  const navigate = useNavigate();
  const user = useAuthStore((s) => s.user);

  const [detail, setDetail] = useState<CostingOrderDetail | null>(null);
  const [frozen, setFrozen] = useState<any>(null);
  const [loading, setLoading] = useState(true);
  const [actionLoading, setActionLoading] = useState(false);
  const [rejectDrawerOpen, setRejectDrawerOpen] = useState(false);
  const [rejectForm] = Form.useForm();

  const loadDetail = async () => {
    if (!coid) return;
    setLoading(true);
    try {
      const res = await costingOrderService.getById(coid);
      const d = res.data;
      setDetail(d);
      if (d.frozenDto) {
        try {
          setFrozen(JSON.parse(d.frozenDto));
        } catch {
          setFrozen(null);
        }
      } else {
        setFrozen(null);
      }
    } catch (e: any) {
      message.error(e?.message || '加载失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadDetail();
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [coid]);

  /** 核价操作权限：角色 + 核价单处于待核价状态 */
  const canReview =
    ['PRICING_MANAGER', 'SYSTEM_ADMIN'].includes(user?.role ?? '') &&
    detail?.status === 'PENDING';

  /** 审批回联：用 quotationId，不是 costingOrderId */
  const handleApprove = async () => {
    if (!detail?.quotationId) return;
    setActionLoading(true);
    try {
      await costingOrderService.approve(detail.quotationId);
      message.success('核价通过');
      navigate('/costing-summary');
    } catch (e: any) {
      message.error(e?.message || '操作失败');
    } finally {
      setActionLoading(false);
    }
  };

  const handleReject = async (values: { comment: string }) => {
    if (!detail?.quotationId) return;
    setActionLoading(true);
    try {
      await costingOrderService.reject(detail.quotationId, values.comment);
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

  if (!detail) {
    return <div style={{ textAlign: 'center', padding: 80 }}>核价单不存在</div>;
  }

  const statusInfo = costingStatusConfig[detail.status] ?? {
    label: detail.status,
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
                {detail.costingOrderNumber}
              </Title>
              {frozen?.quotationNumber && (
                <Text type="secondary" style={{ fontSize: 13 }}>
                  报价单：{frozen.quotationNumber}
                </Text>
              )}
              <Tag color={statusInfo.color} style={{ fontSize: 13 }}>
                {statusInfo.label}
              </Tag>
              {detail.rejectReason && (
                <Text type="danger" style={{ fontSize: 12 }}>
                  驳回原因：{detail.rejectReason}
                </Text>
              )}
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

      {/* 产品明细：frozen DTO 存在则走 frozen 模式，否则降级提示 */}
      {frozen ? (
        <ProductDetailViews quotation={frozen} frozen />
      ) : (
        <Card>
          <div style={{ textAlign: 'center', padding: 32, color: '#999' }}>
            此单为旧版本，无冻结明细
          </div>
        </Card>
      )}

      {/* 驳回 Drawer */}
      <Drawer
        title="驳回核价单"
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
