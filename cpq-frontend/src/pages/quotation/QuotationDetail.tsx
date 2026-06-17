import React, { useEffect, useState, useMemo } from 'react';
import {
  Button, Card, Col, Collapse, Descriptions, Drawer, Form, Input,
  Popconfirm, Row, Space, Spin, Table, Tabs, Tag, DatePicker,
  Checkbox, Typography, message,
} from 'antd';
import {
  CopyOutlined, DeleteOutlined, EditOutlined, FilePdfOutlined, FileExcelOutlined,
  MailOutlined, PrinterOutlined, CheckCircleOutlined, CloseCircleOutlined,
  CalendarOutlined, SendOutlined, UploadOutlined, DatabaseOutlined,
} from '@ant-design/icons';
import { useNavigate, useParams } from 'react-router-dom';
import { quotationService } from '../../services/quotationService';
import { quotationSnapshotService } from '../../services/quotationSnapshotService';
import { boundGlobalVariableService } from '../../services/boundGlobalVariableService';
import { globalVariableService } from '../../services/globalVariableService';
import type { GlobalVariableDefinition } from '../../services/globalVariableService';
import { useAuthStore } from '../../stores/authStore';
import ReadonlyProductCard from './ReadonlyProductCard';
import CopyQuotationDrawer from './CopyQuotationDrawer';
import { usePathFormulaCache } from './usePathFormulaCache';
import { enrichComponentData } from './enrichComponentData';
import type { LineItem } from './QuotationStep2';
import WithdrawSection from './WithdrawSection';
import SnapshotTab from './components/SnapshotTab';
import BoundGlobalVariablesTab from './components/BoundGlobalVariablesTab';
import type { SubmissionSnapshot } from '../../types/quotation-snapshot';
import dayjs from 'dayjs';

const { Title, Text } = Typography;

const statusConfig: Record<string, { label: string; color: string }> = {
  DRAFT: { label: '草稿', color: 'default' },
  SUBMITTED: { label: '审批中', color: 'processing' },
  APPROVED: { label: '已批准', color: 'success' },
  SENT: { label: '已发送', color: 'cyan' },
  ACCEPTED: { label: '已接受', color: 'green' },
  REJECTED: { label: '已退回', color: 'error' },
  EXPIRED: { label: '已过期', color: 'warning' },
};

const approvalActionMap: Record<string, { label: string; color: string }> = {
  APPROVED: { label: '批准', color: 'success' },
  REJECTED: { label: '退回', color: 'error' },
  WITHDRAWN: { label: '撤回', color: 'default' },
  SENT: { label: '发送', color: 'cyan' },
};

const QuotationDetail: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const user = useAuthStore((s) => s.user);
  const navigate = useNavigate();
  const [quotation, setQuotation] = useState<any>(null);
  const [loading, setLoading] = useState(true);

  // ----------------------------------------------------------------
  // 迁移：Drawer 替代 Modal（导出PDF / 导出Excel / 发送邮件 / 延期 / 拒绝）
  // ----------------------------------------------------------------
  const [copyDrawerOpen, setCopyDrawerOpen] = useState(false);
  const [pdfDrawerOpen, setPdfDrawerOpen] = useState(false);
  const [excelDrawerOpen, setExcelDrawerOpen] = useState(false);
  const [emailDrawerOpen, setEmailDrawerOpen] = useState(false);
  const [extendDrawerOpen, setExtendDrawerOpen] = useState(false);
  const [rejectDrawerOpen, setRejectDrawerOpen] = useState(false);

  // 审批操作 Drawer（原 Modal）
  const [approveDrawerOpen, setApproveDrawerOpen] = useState(false);
  const [approvalRejectDrawerOpen, setApprovalRejectDrawerOpen] = useState(false);
  const [approveComment, setApproveComment] = useState('');
  const [approvalRejectComment, setApprovalRejectComment] = useState('');

  // Form instances
  const [emailForm] = Form.useForm();
  const [extendForm] = Form.useForm();
  const [rejectForm] = Form.useForm();
  const [pdfForm] = Form.useForm();
  const [excelForm] = Form.useForm();

  const [actionLoading, setActionLoading] = useState(false);

  // ----------------------------------------------------------------
  // UI-8 数据来源 Tab
  // ----------------------------------------------------------------
  const [activeTab, setActiveTab] = useState<string>('info');
  const [snapshot, setSnapshot] = useState<SubmissionSnapshot | null>(null);
  const [snapshotLoading, setSnapshotLoading] = useState(false);

  // ----------------------------------------------------------------
  // B3：引用数据 Tab 可见性（无 GV 绑定时自动隐藏）
  // ----------------------------------------------------------------
  const [hasGvBindings, setHasGvBindings] = useState(false);

  // ----------------------------------------------------------------
  // B-GV-2 修复: 动态 key 全局变量定义字典，传给 ReadonlyProductCard 供 FORMULA 字段求值
  // ----------------------------------------------------------------
  const [gvDefs, setGvDefs] = useState<Record<string, GlobalVariableDefinition>>({});
  useEffect(() => {
    globalVariableService.list()
      .then((res: any) => {
        const arr: GlobalVariableDefinition[] = Array.isArray(res) ? res
          : Array.isArray(res?.data) ? res.data
          : [];
        const map: Record<string, GlobalVariableDefinition> = {};
        for (const d of arr) { if (d?.code) map[d.code] = d; }
        setGvDefs(map);
      })
      .catch(() => setGvDefs({}));
  }, []);

  // ----------------------------------------------------------------
  // 任务2: enriched lineItems —— 供 usePathFormulaCache 预热 _globalPathCache。
  // 详情页打开时 _globalPathCache 是空的（编辑页的 cache 不跨路由），
  // 需要先把 lineItems enrich（补 fields/formulas），hook 才能扫到 path token 并预热。
  // ----------------------------------------------------------------
  const [enrichedLineItems, setEnrichedLineItems] = useState<LineItem[]>([]);
  useEffect(() => {
    if (!quotation?.lineItems?.length) {
      setEnrichedLineItems([]);
      return;
    }
    let cancelled = false;
    Promise.all(
      (quotation.lineItems as any[]).map(async (li: any) => {
        if (!li.templateId) return li as LineItem;
        const enrichedComps = await enrichComponentData(li.templateId, li.componentData || []);
        return { ...li, componentData: enrichedComps } as LineItem;
      }),
    ).then((result) => {
      if (!cancelled) setEnrichedLineItems(result);
    }).catch(() => {
      if (!cancelled) setEnrichedLineItems([]);
    });
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [quotation?.id, quotation?.lineItems?.length]);

  // 触发 path 公式缓存预热（写 _globalPathCache 模块级，ReadonlyProductCard 的
  // evaluateExpression 调用会在 path/global_variable case 里直接命中缓存）
  usePathFormulaCache(enrichedLineItems, quotation?.customerId, gvDefs);

  // ----------------------------------------------------------------
  // Phase 4 #21：提交按钮
  // ----------------------------------------------------------------
  const [submitLoading, setSubmitLoading] = useState(false);

  // ----------------------------------------------------------------
  // Handlers
  // ----------------------------------------------------------------
  const handleApprove = async () => {
    setActionLoading(true);
    try {
      await quotationService.approve(id!, approveComment || undefined);
      message.success('审批通过');
      setApproveDrawerOpen(false);
      setApproveComment('');
      loadQuotation();
    } catch (e: any) {
      message.error(e.message);
    } finally {
      setActionLoading(false);
    }
  };

  const handleApprovalReject = async () => {
    if (!approvalRejectComment.trim()) {
      message.warning('请填写退回原因');
      return;
    }
    setActionLoading(true);
    try {
      await quotationService.reject(id!, approvalRejectComment);
      message.success('已退回');
      setApprovalRejectDrawerOpen(false);
      setApprovalRejectComment('');
      loadQuotation();
    } catch (e: any) {
      message.error(e.message);
    } finally {
      setActionLoading(false);
    }
  };

  const handleWithdraw = async () => {
    setActionLoading(true);
    try {
      await quotationService.withdraw(id!);
      message.success('已撤回');
      loadQuotation();
    } catch (e: any) {
      message.error(e.message);
    } finally {
      setActionLoading(false);
    }
  };

  const loadQuotation = async () => {
    if (!id) return;
    setLoading(true);
    try {
      const res = await quotationService.getById(id);
      setQuotation(res.data);
    } catch (e: any) {
      message.error(e.message || '加载失败');
    } finally {
      setLoading(false);
    }
  };

  const loadSnapshot = async () => {
    if (!id) return;
    setSnapshotLoading(true);
    try {
      const res = await quotationSnapshotService.getSnapshot(id);
      setSnapshot(res.data);
    } catch {
      message.error('快照数据加载失败');
    } finally {
      setSnapshotLoading(false);
    }
  };

  useEffect(() => {
    loadQuotation();
  }, [id]);

  // B3：quotation 加载完毕后探测模板是否有 GV 绑定，不阻塞主页加载
  useEffect(() => {
    if (!quotation?.customerTemplateId) {
      setHasGvBindings(false);
      return;
    }
    boundGlobalVariableService
      .getTemplateBindings(quotation.customerTemplateId)
      .then((arr) => setHasGvBindings((arr || []).length > 0))
      .catch(() => setHasGvBindings(false));
  }, [quotation?.customerTemplateId]);

  // 切换到"数据来源" Tab 时懒加载快照
  const handleTabChange = (key: string) => {
    setActiveTab(key);
    if (key === 'snapshot' && !snapshot && !snapshotLoading) {
      loadSnapshot();
    }
  };

  const handleDelete = async () => {
    try {
      await quotationService.delete(id!);
      message.success('删除成功');
      navigate('/quotations');
    } catch (e: any) {
      message.error(e.message);
    }
  };

  const handleCopy = () => setCopyDrawerOpen(true);

  const handleAccept = async () => {
    setActionLoading(true);
    try {
      await quotationService.accept(id!);
      message.success('已确认接受报价');
      loadQuotation();
    } catch (e: any) {
      message.error(e.message);
    } finally {
      setActionLoading(false);
    }
  };

  const handleRejectByCustomer = async (values: any) => {
    setActionLoading(true);
    try {
      await quotationService.rejectByCustomer(id!, values.comment);
      message.success('已拒绝报价');
      setRejectDrawerOpen(false);
      rejectForm.resetFields();
      loadQuotation();
    } catch (e: any) {
      message.error(e.message);
    } finally {
      setActionLoading(false);
    }
  };

  const handleExtend = async (values: any) => {
    setActionLoading(true);
    try {
      await quotationService.extend(id!, values.newExpiryDate.format('YYYY-MM-DD'));
      message.success('有效期已更新');
      setExtendDrawerOpen(false);
      extendForm.resetFields();
      loadQuotation();
    } catch (e: any) {
      message.error(e.message);
    } finally {
      setActionLoading(false);
    }
  };

  const handleSendEmail = async (values: any) => {
    setActionLoading(true);
    try {
      await quotationService.send(id!, {
        to: values.to,
        cc: values.cc,
        subject: values.subject,
        body: values.body,
        attachExcel: values.attachExcel,
      });
      message.success('报价单已发送');
      setEmailDrawerOpen(false);
      emailForm.resetFields();
      loadQuotation();
    } catch (e: any) {
      message.error(e.message);
    } finally {
      setActionLoading(false);
    }
  };

  const handleExportHtml = async (values: any) => {
    const fullUrl = `${window.location.origin}/api/cpq/quotations/${id}/export/html?showDiscount=${!!values.showDiscount}&showProcesses=${!!values.showProcesses}&showTabDetails=${!!values.showTabDetails}`;
    window.open(fullUrl, '_blank');
    setPdfDrawerOpen(false);
  };

  const handleExportExcel = async (values: any) => {
    try {
      const res = await quotationService.exportExcel(id!, {
        showDiscount: values.showDiscount,
        includeRawData: values.includeRawData,
      });
      const blob = new Blob([res.data], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `${quotation?.quotationNumber || 'quotation'}.xlsx`;
      a.click();
      URL.revokeObjectURL(url);
      setExcelDrawerOpen(false);
    } catch (e: any) {
      message.error(e.message || '导出失败');
    }
  };

  const handlePrint = () => {
    const printUrl = `${window.location.origin}/api/cpq/quotations/${id}/export/html?showDiscount=true&showProcesses=true&showTabDetails=false`;
    const win = window.open(printUrl, '_blank');
    if (win) {
      win.onload = () => win.print();
    }
  };

  // ----------------------------------------------------------------
  // Phase 4 #21：提交报价单（含快照写入）
  // ----------------------------------------------------------------
  const handleSubmit = async () => {
    setSubmitLoading(true);
    try {
      await quotationSnapshotService.submit(id!);
      message.success('提交成功，报价单进入审批流程');
      // 刷新：status → SUBMITTED，"数据来源"Tab 出现，ⓘ 变黄色
      await loadQuotation();
      // 清掉旧快照缓存，触发重新加载
      setSnapshot(null);
    } catch (e: any) {
      message.error(e.message || '提交失败，请稍后重试');
    } finally {
      setSubmitLoading(false);
    }
  };

  if (loading) {
    return <div style={{ textAlign: 'center', padding: 80 }}><Spin size="large" /></div>;
  }

  if (!quotation) {
    return <div style={{ textAlign: 'center', padding: 80 }}>报价单不存在</div>;
  }

  const status = quotation.status;
  const statusInfo = statusConfig[status] || { label: status, color: 'default' };
  const isSubmittedOrLater = !['DRAFT'].includes(status);

  const approvalColumns = [
    {
      title: '操作', dataIndex: 'action', key: 'action',
      render: (v: string) => {
        const c = approvalActionMap[v];
        return c ? <Tag color={c.color}>{c.label}</Tag> : <Tag>{v}</Tag>;
      },
    },
    {
      title: '操作人', dataIndex: 'approverName', key: 'approver',
      render: (v: string, record: any) => v || (record.approverId ? record.approverId.substring(0, 8) + '...' : '-'),
    },
    { title: '备注', dataIndex: 'comment', key: 'comment', render: (v: string) => v || '-' },
    {
      title: '操作时间', dataIndex: 'actedAt', key: 'actedAt',
      render: (v: string) => v ? new Date(v).toLocaleString('zh-CN') : '-',
    },
  ];

  // ----------------------------------------------------------------
  // Tabs 配置
  // ----------------------------------------------------------------
  const tabItems = [
    {
      key: 'info',
      label: '报价单信息',
      children: (
        <>
          {/* Header Info */}
          <Card title="基本信息" style={{ marginBottom: 16 }}>
            <Descriptions column={3} bordered size="small">
              <Descriptions.Item label="报价单名称">{quotation.name}</Descriptions.Item>
              <Descriptions.Item label="报价单号">{quotation.quotationNumber}</Descriptions.Item>
              <Descriptions.Item label="状态"><Tag color={statusInfo.color}>{statusInfo.label}</Tag></Descriptions.Item>
              <Descriptions.Item label="客户名称">{quotation.snapshotCustomerName}</Descriptions.Item>
              <Descriptions.Item label="客户等级">{quotation.snapshotCustomerLevel}</Descriptions.Item>
              <Descriptions.Item label="客户地区">{quotation.snapshotCustomerRegion}</Descriptions.Item>
              <Descriptions.Item label="联系人">{quotation.contactName}</Descriptions.Item>
              <Descriptions.Item label="联系电话">{quotation.contactPhone}</Descriptions.Item>
              <Descriptions.Item label="联系邮箱">{quotation.contactEmail}</Descriptions.Item>
              <Descriptions.Item label="项目名称">{quotation.projectName}</Descriptions.Item>
              <Descriptions.Item label="报价类型">{quotation.quoteType}</Descriptions.Item>
              <Descriptions.Item label="优先级">{quotation.priority}</Descriptions.Item>
              <Descriptions.Item label="有效期至">{quotation.expiryDate}</Descriptions.Item>
              <Descriptions.Item label="预计成交日">{quotation.expectedCloseDate}</Descriptions.Item>
              <Descriptions.Item label="付款条款">{quotation.paymentTerms}</Descriptions.Item>
              <Descriptions.Item label="交货周期">{quotation.deliveryCycle ? `${quotation.deliveryCycle} 天` : '-'}</Descriptions.Item>
              <Descriptions.Item label="创建时间">{quotation.createdAt ? new Date(quotation.createdAt).toLocaleString('zh-CN') : '-'}</Descriptions.Item>
              <Descriptions.Item label="更新时间">{quotation.updatedAt ? new Date(quotation.updatedAt).toLocaleString('zh-CN') : '-'}</Descriptions.Item>
            </Descriptions>
          </Card>

          {/* Line Items — 隐藏组合产品 PART 子件 (与 QuotationStep2 一致, 父卡片内 Tab 通过聚合视图展示子件数据) */}
          <Card title="产品明细" style={{ marginBottom: 16 }}>
            {(() => {
              const visible = (quotation.lineItems || []).filter((li: any) => li.compositeType !== 'PART');
              return visible.length === 0 ? (
                <div style={{ textAlign: 'center', padding: 32, color: '#999' }}>暂无产品</div>
              ) : (
                <div className="qt-products-list">
                  {visible.map((li: any, idx: number) => (
                    <ReadonlyProductCard
                      key={li.id || idx}
                      lineItem={li}
                      index={idx}
                      quotationId={quotation.id}
                      quotationStatus={quotation.status}
                      customerId={quotation.customerId}
                      globalVariableDefs={gvDefs}
                      quoteCardStructure={quotation.quoteCardStructure ?? null}
                    />
                  ))}
                </div>
              );
            })()}
            <Row justify="end" style={{ marginTop: 16 }}>
              <Col>
                <Space direction="vertical" align="end">
                  <Text>原价合计：<Text strong>¥{Number(quotation.originalAmount || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2 })}</Text></Text>
                  <Text>折扣率：<Text strong>{quotation.finalDiscountRate}%</Text></Text>
                  <Text style={{ fontSize: 16 }}>
                    报价总金额：<Text strong style={{ fontSize: 18, color: '#c00' }}>¥{Number(quotation.totalAmount || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2 })}</Text>
                  </Text>
                </Space>
              </Col>
            </Row>
          </Card>

          {/* Approval History */}
          {quotation.approvalHistory && quotation.approvalHistory.length > 0 && (
            <Collapse style={{ marginBottom: 16 }}>
              <Collapse.Panel header={`审批历史 (${quotation.approvalHistory.length} 条)`} key="approvals">
                <Table
                  dataSource={quotation.approvalHistory}
                  columns={approvalColumns}
                  rowKey="id"
                  pagination={false}
                  size="small"
                />
              </Collapse.Panel>
            </Collapse>
          )}
        </>
      ),
    },
    // ADR-002：引用数据 Tab（DRAFT 实时 / 非 DRAFT 读快照）
    // B3：仅模板有 GV 绑定时才显示（探测结果来自 hasGvBindings）
    ...(hasGvBindings
      ? [
          {
            key: 'refData',
            label: '引用数据',
            children: (
              <BoundGlobalVariablesTab
                quotationId={quotation.id}
                status={quotation.status}
              />
            ),
          },
        ]
      : []),
    // UI-8：仅 SUBMITTED+ 显示"数据来源"Tab
    ...(isSubmittedOrLater
      ? [
          {
            key: 'snapshot',
            label: (
              <Space size={4}>
                <DatabaseOutlined style={{ color: '#faad14' }} />
                数据来源
              </Space>
            ),
            children: (
              <SnapshotTab snapshot={snapshot} loading={snapshotLoading} />
            ),
          },
        ]
      : []),
  ];

  return (
    <div style={{ padding: 24 }}>
      {/* Action Bar */}
      <Card style={{ marginBottom: 16 }}>
        <Row justify="space-between" align="middle">
          <Col>
            <Space align="center">
              <Title level={4} style={{ margin: 0 }}>
                {quotation.quotationNumber}
              </Title>
              <Tag color={statusInfo.color} style={{ fontSize: 13 }}>{statusInfo.label}</Tag>
            </Space>
          </Col>
          <Col>
            <Space wrap>
              {/* ---------------------------------------------------------------- */}
              {/* DRAFT：提交按钮（Phase 4 #21 增强）                               */}
              {/* ---------------------------------------------------------------- */}
              {status === 'DRAFT' && (
                <>
                  <Button icon={<EditOutlined />} onClick={() => navigate(`/quotations/${id}/edit`)}>编辑</Button>
                  <Popconfirm
                    title="提交后报价单进入审批流程，数据将被冻结快照。是否继续？"
                    onConfirm={handleSubmit}
                    okText="确认提交"
                    cancelText="取消"
                  >
                    <Button
                      type="primary"
                      icon={<UploadOutlined />}
                      loading={submitLoading}
                    >
                      提交审批
                    </Button>
                  </Popconfirm>
                  <Popconfirm title="确认删除此报价单？" onConfirm={handleDelete} okText="删除" cancelText="取消" okButtonProps={{ danger: true }}>
                    <Button danger icon={<DeleteOutlined />}>删除</Button>
                  </Popconfirm>
                </>
              )}

              {/* SUBMITTED：审批操作 */}
              {status === 'SUBMITTED' && (() => {
                const isAssigned = user?.id === quotation.assignedApproverId;
                const isAdmin = user?.role === 'SYSTEM_ADMIN';
                const isCreator = user?.id === quotation.salesRepId;
                return (
                  <>
                    {(isAssigned || isAdmin) && (
                      <>
                        <Button
                          type="primary"
                          style={{ backgroundColor: '#52c41a', borderColor: '#52c41a' }}
                          icon={<CheckCircleOutlined />}
                          onClick={() => setApproveDrawerOpen(true)}
                        >
                          通过
                        </Button>
                        <Button
                          danger
                          icon={<CloseCircleOutlined />}
                          onClick={() => setApprovalRejectDrawerOpen(true)}
                        >
                          退回
                        </Button>
                      </>
                    )}
                    {user?.role === 'SALES_MANAGER' && !isAssigned && !isAdmin && (
                      <>
                        <Button disabled icon={<CheckCircleOutlined />} title="该报价单不在您的审批范围内">通过</Button>
                        <Button disabled icon={<CloseCircleOutlined />} title="该报价单不在您的审批范围内">退回</Button>
                      </>
                    )}
                    {isCreator && (
                      <Popconfirm title="撤回后报价单将回到草稿状态，需重新提交审批。是否继续？" onConfirm={handleWithdraw}>
                        <Button loading={actionLoading}>撤回</Button>
                      </Popconfirm>
                    )}
                  </>
                );
              })()}

              {status === 'APPROVED' && (
                <Button type="primary" icon={<SendOutlined />} onClick={() => {
                  emailForm.setFieldValue('subject', `报价单 ${quotation.quotationNumber}`);
                  emailForm.setFieldValue('to', quotation.contactEmail || '');
                  setEmailDrawerOpen(true);
                }}>
                  发送报价
                </Button>
              )}
              {status === 'SENT' && (
                <>
                  <Popconfirm title="确认接受此报价单？" onConfirm={handleAccept} okText="确认接受">
                    <Button type="primary" icon={<CheckCircleOutlined />} loading={actionLoading}>接受报价</Button>
                  </Popconfirm>
                  <Button danger icon={<CloseCircleOutlined />} onClick={() => setRejectDrawerOpen(true)}>拒绝报价</Button>
                  <Button icon={<CalendarOutlined />} onClick={() => {
                    if (quotation.expiryDate) extendForm.setFieldValue('newExpiryDate', dayjs(quotation.expiryDate));
                    setExtendDrawerOpen(true);
                  }}>延期</Button>
                </>
              )}
              {status === 'APPROVED' && (
                <Button icon={<CalendarOutlined />} onClick={() => {
                  if (quotation.expiryDate) extendForm.setFieldValue('newExpiryDate', dayjs(quotation.expiryDate));
                  setExtendDrawerOpen(true);
                }}>延期</Button>
              )}

              {/* 通用操作 */}
              <Button icon={<CopyOutlined />} onClick={handleCopy}>复制</Button>
              <Button icon={<FilePdfOutlined />} onClick={() => setPdfDrawerOpen(true)}>导出PDF</Button>
              <Button icon={<FileExcelOutlined />} onClick={() => setExcelDrawerOpen(true)}>导出Excel</Button>
              <Button icon={<PrinterOutlined />} onClick={handlePrint}>打印</Button>
            </Space>
          </Col>
        </Row>
      </Card>

      {/* Withdraw Request Section */}
      <WithdrawSection
        quotationId={quotation.id}
        quotationStatus={status}
        assignedApproverId={quotation.assignedApproverId}
        onChanged={() => loadQuotation()}
      />

      {/* Approval Progress */}
      {(status === 'SUBMITTED' || status === 'APPROVED' || (status === 'DRAFT' && quotation.approvalHistory?.length > 0)) && (
        <Card size="small" style={{ marginBottom: 16 }}>
          {status === 'SUBMITTED' && (
            <Space>
              <Tag color="processing">审批中</Tag>
              <span>审批人：<strong>{quotation.assignedApproverName || '未指定'}</strong></span>
              <span style={{ color: '#999' }}>
                已等待 {(() => {
                  const updatedAt = quotation.updatedAt ? new Date(quotation.updatedAt) : null;
                  if (!updatedAt) return '-';
                  const hours = Math.floor((Date.now() - updatedAt.getTime()) / 3600000);
                  return hours < 24 ? `${hours} 小时` : `${Math.floor(hours / 24)} 天`;
                })()}
              </span>
            </Space>
          )}
          {status === 'APPROVED' && (() => {
            const approveRecord = [...(quotation.approvalHistory || [])].reverse().find((a: any) => a.action === 'APPROVED');
            return (
              <Space>
                <Tag color="success">已通过</Tag>
                <span>审批人：<strong>{quotation.assignedApproverName || '未指定'}</strong></span>
                {approveRecord?.actedAt && <span>{new Date(approveRecord.actedAt).toLocaleString('zh-CN')}</span>}
                {approveRecord?.comment && <span style={{ color: '#666' }}>意见：{approveRecord.comment}</span>}
              </Space>
            );
          })()}
          {status === 'DRAFT' && quotation.approvalHistory?.length > 0 && (() => {
            const lastReject = [...(quotation.approvalHistory || [])].reverse().find((a: any) => a.action === 'REJECTED');
            if (!lastReject) return null;
            return (
              <Space>
                <Tag color="error">上次审批被退回</Tag>
                <span style={{ color: '#c00' }}>原因：{lastReject.comment || '未填写'}</span>
              </Space>
            );
          })()}
        </Card>
      )}

      {/* ----------------------------------------------------------------
          主内容 Tabs（UI-8：SUBMITTED+ 才有"数据来源"Tab）
      ---------------------------------------------------------------- */}
      <Tabs
        activeKey={activeTab}
        onChange={handleTabChange}
        items={tabItems}
        style={{ background: '#fff', padding: '0 24px 24px', borderRadius: 8 }}
      />

      {/* ================================================================
          以下均为 Drawer（已从 Modal 迁移）
      ================================================================ */}

      {/* 导出PDF Drawer */}
      <Drawer
        title="导出PDF / 打印选项"
        placement="right"
        width={480}
        open={pdfDrawerOpen}
        onClose={() => setPdfDrawerOpen(false)}
        destroyOnClose
      >
        <Form
          form={pdfForm}
          layout="vertical"
          onFinish={handleExportHtml}
          initialValues={{ showDiscount: true, showProcesses: true, showTabDetails: false }}
        >
          <Form.Item name="showDiscount" valuePropName="checked">
            <Checkbox>显示折扣信息</Checkbox>
          </Form.Item>
          <Form.Item name="showProcesses" valuePropName="checked">
            <Checkbox>显示加工工序</Checkbox>
          </Form.Item>
          <Form.Item name="showTabDetails" valuePropName="checked">
            <Checkbox>显示组件明细</Checkbox>
          </Form.Item>
          <Form.Item>
            <Space>
              <Button type="primary" htmlType="submit" icon={<FilePdfOutlined />}>在新窗口预览/打印</Button>
              <Button onClick={() => setPdfDrawerOpen(false)}>取消</Button>
            </Space>
          </Form.Item>
        </Form>
      </Drawer>

      {/* 导出Excel Drawer */}
      <Drawer
        title="导出Excel选项"
        placement="right"
        width={480}
        open={excelDrawerOpen}
        onClose={() => setExcelDrawerOpen(false)}
        destroyOnClose
      >
        <Form
          form={excelForm}
          layout="vertical"
          onFinish={handleExportExcel}
          initialValues={{ showDiscount: true, includeRawData: false }}
        >
          <Form.Item name="showDiscount" valuePropName="checked">
            <Checkbox>显示折扣信息</Checkbox>
          </Form.Item>
          <Form.Item name="includeRawData" valuePropName="checked">
            <Checkbox>包含原始数据（Sheet2）</Checkbox>
          </Form.Item>
          <Form.Item>
            <Space>
              <Button type="primary" htmlType="submit" icon={<FileExcelOutlined />}>导出</Button>
              <Button onClick={() => setExcelDrawerOpen(false)}>取消</Button>
            </Space>
          </Form.Item>
        </Form>
      </Drawer>

      {/* 发送邮件 Drawer */}
      <Drawer
        title="发送报价单邮件"
        placement="right"
        width={600}
        open={emailDrawerOpen}
        onClose={() => setEmailDrawerOpen(false)}
        destroyOnClose
      >
        <Form form={emailForm} layout="vertical" onFinish={handleSendEmail}>
          <Form.Item name="to" label="收件人" rules={[{ required: true, type: 'email', message: '请输入有效的收件人邮箱' }]}>
            <Input placeholder="收件人邮箱" />
          </Form.Item>
          <Form.Item name="cc" label="抄送">
            <Input placeholder="抄送邮箱（可选）" />
          </Form.Item>
          <Form.Item name="subject" label="主题" rules={[{ required: true, message: '请输入邮件主题' }]}>
            <Input placeholder="邮件主题" />
          </Form.Item>
          <Form.Item name="body" label="邮件正文">
            <Input.TextArea rows={4} placeholder="邮件正文内容" />
          </Form.Item>
          <Form.Item name="attachExcel" valuePropName="checked" initialValue={false}>
            <Checkbox>附加Excel附件</Checkbox>
          </Form.Item>
          <Form.Item>
            <Space>
              <Button type="primary" htmlType="submit" loading={actionLoading} icon={<MailOutlined />}>发送</Button>
              <Button onClick={() => setEmailDrawerOpen(false)}>取消</Button>
            </Space>
          </Form.Item>
        </Form>
      </Drawer>

      {/* 延期 Drawer */}
      <Drawer
        title="延长有效期"
        placement="right"
        width={480}
        open={extendDrawerOpen}
        onClose={() => setExtendDrawerOpen(false)}
        destroyOnClose
      >
        <Form form={extendForm} layout="vertical" onFinish={handleExtend}>
          <Form.Item name="newExpiryDate" label="新有效期" rules={[{ required: true, message: '请选择新有效期' }]}>
            <DatePicker style={{ width: '100%' }} disabledDate={(d) => d && d.isBefore(dayjs())} />
          </Form.Item>
          <Form.Item>
            <Space>
              <Button type="primary" htmlType="submit" loading={actionLoading}>确认延期</Button>
              <Button onClick={() => setExtendDrawerOpen(false)}>取消</Button>
            </Space>
          </Form.Item>
        </Form>
      </Drawer>

      {/* 拒绝报价 Drawer（客户拒绝） */}
      <Drawer
        title="拒绝报价"
        placement="right"
        width={480}
        open={rejectDrawerOpen}
        onClose={() => setRejectDrawerOpen(false)}
        destroyOnClose
      >
        <Form form={rejectForm} layout="vertical" onFinish={handleRejectByCustomer}>
          <Form.Item name="comment" label="拒绝原因">
            <Input.TextArea rows={3} placeholder="请输入拒绝原因（可选）" />
          </Form.Item>
          <Form.Item>
            <Space>
              <Button type="primary" danger htmlType="submit" loading={actionLoading} icon={<CloseCircleOutlined />}>确认拒绝</Button>
              <Button onClick={() => setRejectDrawerOpen(false)}>取消</Button>
            </Space>
          </Form.Item>
        </Form>
      </Drawer>

      {/* 审批通过 Drawer */}
      <Drawer
        title="审批通过"
        placement="right"
        width={480}
        open={approveDrawerOpen}
        onClose={() => { setApproveDrawerOpen(false); setApproveComment(''); }}
        destroyOnClose
        extra={
          <Space>
            <Button onClick={() => { setApproveDrawerOpen(false); setApproveComment(''); }}>取消</Button>
            <Button
              type="primary"
              style={{ backgroundColor: '#52c41a', borderColor: '#52c41a' }}
              loading={actionLoading}
              onClick={handleApprove}
            >
              确认通过
            </Button>
          </Space>
        }
      >
        <Form layout="vertical">
          <Form.Item label="审批意见（可选）">
            <Input.TextArea
              rows={4}
              placeholder="审批意见（可选）"
              value={approveComment}
              onChange={(e) => setApproveComment(e.target.value)}
            />
          </Form.Item>
        </Form>
      </Drawer>

      {/* 退回报价单 Drawer */}
      <Drawer
        title="退回报价单"
        placement="right"
        width={480}
        open={approvalRejectDrawerOpen}
        onClose={() => { setApprovalRejectDrawerOpen(false); setApprovalRejectComment(''); }}
        destroyOnClose
        extra={
          <Space>
            <Button onClick={() => { setApprovalRejectDrawerOpen(false); setApprovalRejectComment(''); }}>取消</Button>
            <Button
              danger
              loading={actionLoading}
              onClick={handleApprovalReject}
            >
              确认退回
            </Button>
          </Space>
        }
      >
        <Form layout="vertical">
          <Form.Item label="退回原因" required>
            <Input.TextArea
              rows={4}
              placeholder="请填写退回原因（必填）"
              value={approvalRejectComment}
              onChange={(e) => setApprovalRejectComment(e.target.value)}
            />
          </Form.Item>
        </Form>
      </Drawer>

      <CopyQuotationDrawer
        open={copyDrawerOpen}
        defaultTemplateId={quotation?.customerTemplateId}
        onClose={() => setCopyDrawerOpen(false)}
        onConfirm={async (templateId) => {
          try {
            const res = await quotationService.copy(id!, templateId);
            message.success('复制成功');
            setCopyDrawerOpen(false);
            navigate(`/quotations/${res.data.id}/edit`);
          } catch (e: any) { message.error(e.message); }
        }}
      />
    </div>
  );
};

export default QuotationDetail;
