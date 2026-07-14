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
import type { CostingOrderDetail, VersionSwitchResult } from '../../services/costingOrderService';
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

/**
 * task-0713（D1/F1）：报价侧读 frozenDto 原样不变；核价侧改用响应里的 costingRender/costingTotalAmount
 * 叠加到 frozen 快照上——只覆写每个 lineItem 的 costingCardValues/costingExcelValues 和顶层
 * costingTotalAmount，报价侧字段（quoteCardValues 等）逐字节不动（守 T2 报价侧隔离）。
 * costingRender 缺失（后端未就绪/历史单未落缓存）时优雅降级为 frozenDto 里原有的核价字段值，
 * 不因此报错或清空。
 */
function buildFrozenView(d: CostingOrderDetail): any {
  let parsed: any = null;
  if (d.frozenDto) {
    try {
      parsed = JSON.parse(d.frozenDto);
    } catch {
      parsed = null;
    }
  }
  if (!parsed) return null;
  const renderMap = d.costingRender ?? {};
  const lineItems = Array.isArray(parsed.lineItems)
    ? parsed.lineItems.map((li: any) => {
        const entry = li?.id != null ? renderMap[li.id] : undefined;
        if (!entry) return li;
        return {
          ...li,
          costingCardValues: entry.costingCardValues ?? li.costingCardValues,
          costingExcelValues: entry.costingExcelValues ?? li.costingExcelValues,
        };
      })
    : parsed.lineItems;
  return {
    ...parsed,
    lineItems,
    costingTotalAmount: d.costingTotalAmount ?? parsed.costingTotalAmount,
  };
}

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
      setFrozen(buildFrozenView(d));
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

  /**
   * task-0713（F4）：版本切换控件可交互 = PENDING + 财务/管理员。
   * 优先信后端下发的 detail.editable（api.md §1 契约字段）；后端未就绪时前端按同一规则自算兜底，
   * 与 canReview 同源（两者语义一致：都是"待核价 + 财务/管理员"）。
   */
  const editable = detail?.editable ?? canReview;

  /**
   * task-0713（F3）：版本切换只增量刷新当前卡片 + 单据总价，绝不整单重新 getById（守 AP-31）。
   * VersionSelectDropdown 切换成功后把 POST version-switch 的响应原样上抛到这里，
   * 本回调只 REPLACE 命中的那一个 lineItem 的核价字段（AP-51：整体替换，不做 Math.max 累加）。
   */
  const handleVersionSwitched = (result: VersionSwitchResult) => {
    setFrozen((prev: any) => {
      if (!prev || !Array.isArray(prev.lineItems)) return prev;
      const lineItems = prev.lineItems.map((li: any) =>
        li?.id === result.lineItemId
          ? {
              ...li,
              costingCardValues: result.costingCardValues,
              costingExcelValues: result.costingExcelColumns ?? li.costingExcelValues,
            }
          : li,
      );
      return { ...prev, lineItems, costingTotalAmount: result.costingTotalAmount ?? prev.costingTotalAmount };
    });
  };

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
        <ProductDetailViews
          quotation={frozen}
          frozen
          coid={coid}
          editable={editable}
          onVersionSwitched={handleVersionSwitched}
        />
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
