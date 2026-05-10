import React, { useEffect, useState } from 'react';
import {
  Modal, Steps, Button, Select, Upload, Table, Tag, Space, message,
  Spin, Typography, Alert, Statistic, Row, Col,
} from 'antd';
import { UploadOutlined, CheckCircleOutlined, CloseCircleOutlined, FileExcelOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { customerService } from '../../services/customerService';
import { templateService } from '../../services/templateService';
import { quotationService } from '../../services/quotationService';

const { Text } = Typography;

interface ImportExcelModalProps {
  open: boolean;
  onClose: () => void;
}

const ImportExcelModal: React.FC<ImportExcelModalProps> = ({ open, onClose }) => {
  const navigate = useNavigate();
  const [currentStep, setCurrentStep] = useState(0);

  // Step 0: Select customer
  const [customers, setCustomers] = useState<any[]>([]);
  const [customersLoading, setCustomersLoading] = useState(false);
  const [selectedCustomerId, setSelectedCustomerId] = useState<string>('');

  // Step 1: Select CPQ template
  const [templates, setTemplates] = useState<any[]>([]);
  const [templatesLoading, setTemplatesLoading] = useState(false);
  const [selectedTemplateId, setSelectedTemplateId] = useState<string>('');

  // Step 2: Upload Excel
  const [uploadFile, setUploadFile] = useState<File | null>(null);
  const [previewing, setPreviewing] = useState(false);

  // Step 3: Preview results
  const [previewData, setPreviewData] = useState<any>(null);

  // Step 4: Confirm import
  const [confirming, setConfirming] = useState(false);
  const [importResult, setImportResult] = useState<any>(null);

  const reset = () => {
    setCurrentStep(0);
    setSelectedCustomerId('');
    setSelectedTemplateId('');
    setUploadFile(null);
    setPreviewData(null);
    setImportResult(null);
    setTemplates([]);
  };

  const handleClose = () => {
    reset();
    onClose();
  };

  // Load customers when modal opens
  useEffect(() => {
    if (open) {
      setCustomersLoading(true);
      customerService.list({ size: 200 })
        .then(res => setCustomers(res.data?.content || []))
        .catch(() => { /* silently fail */ })
        .finally(() => setCustomersLoading(false));
    }
  }, [open]);

  // Step 0 → Step 1: load published templates with excel_view_config for this customer
  const handleStep0Next = async () => {
    if (!selectedCustomerId) { message.warning('请选择客户'); return; }
    setTemplatesLoading(true);
    try {
      const res = await templateService.list({ status: 'PUBLISHED', size: 100 });
      const allTemplates: any[] = res.data?.content || res.data || [];
      // Filter templates that have excel_view_config.customer_id matching selected customer
      const filtered = allTemplates.filter((t: any) => {
        const cfg = t.excelViewConfig || t.excel_view_config;
        if (!cfg) return false;
        const parsed = typeof cfg === 'string' ? JSON.parse(cfg) : cfg;
        return parsed?.customer_id === selectedCustomerId;
      });
      setTemplates(filtered);
      setCurrentStep(1);
    } catch (e: any) {
      message.error(e.message || '加载模板失败');
    } finally {
      setTemplatesLoading(false);
    }
  };

  const getSelectedTemplate = () => templates.find(t => t.id === selectedTemplateId);

  const getTemplateSampleFile = (template: any) => {
    if (!template) return '';
    const cfg = template.excelViewConfig || template.excel_view_config;
    if (!cfg) return '';
    const parsed = typeof cfg === 'string' ? JSON.parse(cfg) : cfg;
    return parsed?.import_settings?.sample_file_name || '';
  };

  // Step 1 → Step 2
  const handleStep1Next = () => {
    if (!selectedTemplateId) { message.warning('请选择CPQ模板'); return; }
    setCurrentStep(2);
  };

  // Step 2 → Step 3: call import-excel preview
  const handleStep2Next = async () => {
    if (!uploadFile) { message.warning('请上传Excel文件'); return; }
    setPreviewing(true);
    try {
      const res = await quotationService.importPreview(uploadFile, selectedTemplateId, selectedCustomerId);
      setPreviewData(res.data || res);
      setCurrentStep(3);
    } catch (e: any) {
      message.error('解析失败: ' + (e.message || '未知错误'));
    } finally {
      setPreviewing(false);
    }
  };

  // Step 3 → Step 4: confirm import
  const handleConfirmImport = async () => {
    if (!previewData) return;
    setConfirming(true);
    try {
      const res = await quotationService.confirmImport(previewData);
      setImportResult(res.data || res);
      setCurrentStep(4);
    } catch (e: any) {
      message.error('导入失败: ' + (e.message || '未知错误'));
    } finally {
      setConfirming(false);
    }
  };

  const handleGoToQuotation = () => {
    if (importResult?.quotationId) {
      navigate(`/quotations/${importResult.quotationId}`);
    }
    handleClose();
  };

  const previewRows: any[] = previewData?.rows || [];
  const previewStats = previewData?.stats || {};
  const previewErrors: string[] = previewData?.errors || [];

  const previewColumns = [
    { title: '行号', dataIndex: 'rowNo', key: 'rowNo', width: 60 },
    { title: '客户料号', dataIndex: 'customerPartNo', key: 'customerPartNo' },
    {
      title: '匹配状态', dataIndex: 'matchStatus', key: 'matchStatus', width: 120,
      render: (v: string) => {
        if (v === 'MATCHED') return <Tag color="green" icon={<CheckCircleOutlined />}>已匹配</Tag>;
        if (v === 'NO_MATCH') return <Tag color="red" icon={<CloseCircleOutlined />}>未匹配</Tag>;
        return <Tag>{v}</Tag>;
      },
    },
    { title: '物料名称', dataIndex: 'materialName', key: 'materialName' },
    { title: '备注', dataIndex: 'remark', key: 'remark', ellipsis: true },
  ];

  const steps = [
    { title: '选择客户' },
    { title: '选择模板' },
    { title: '上传文件' },
    { title: '预览结果' },
    { title: '导入完成' },
  ];

  const renderStepContent = () => {
    switch (currentStep) {
      case 0:
        return (
          <div>
            <div style={{ marginBottom: 8 }}>选择要为哪个客户导入报价数据：</div>
            <Select
              style={{ width: '100%' }}
              placeholder="请选择客户"
              loading={customersLoading}
              value={selectedCustomerId || undefined}
              onChange={v => { setSelectedCustomerId(v); setSelectedTemplateId(''); }}
              showSearch
              filterOption={(input, opt) =>
                String(opt?.label || '').toLowerCase().includes(input.toLowerCase())
              }
              options={customers.map((c: any) => ({ label: c.name, value: c.id }))}
            />
          </div>
        );

      case 1:
        return (
          <div>
            <div style={{ marginBottom: 8 }}>
              选择已配置Excel导入的CPQ模板（仅显示已发布且关联当前客户的模板）：
            </div>
            {templatesLoading ? <Spin /> : templates.length === 0 ? (
              <Alert
                type="warning"
                message="未找到可用模板"
                description="该客户暂无已发布且配置了Excel导入的CPQ模板，请先在模板配置中设置Excel视图并关联客户。"
                showIcon
              />
            ) : (
              <Select
                style={{ width: '100%' }}
                placeholder="请选择CPQ模板"
                value={selectedTemplateId || undefined}
                onChange={v => setSelectedTemplateId(v)}
                options={templates.map((t: any) => ({
                  label: `${t.name}${t.version ? ` v${t.version}` : ''}`,
                  value: t.id,
                }))}
              />
            )}
            {selectedTemplateId && (() => {
              const tpl = getSelectedTemplate();
              const sampleFile = getTemplateSampleFile(tpl);
              return sampleFile ? (
                <div style={{ marginTop: 8, fontSize: 12, color: '#1677ff' }}>
                  <FileExcelOutlined style={{ marginRight: 4 }} />
                  样例文件: {sampleFile}（可参考此格式上传）
                </div>
              ) : null;
            })()}
          </div>
        );

      case 2:
        return (
          <div>
            <div style={{ marginBottom: 12 }}>上传客户Excel报价文件：</div>
            <Upload
              accept=".xlsx,.xls"
              maxCount={1}
              beforeUpload={file => { setUploadFile(file); return false; }}
              onRemove={() => setUploadFile(null)}
            >
              <Button icon={<UploadOutlined />}>选择Excel文件</Button>
            </Upload>
            {uploadFile && (
              <div style={{ marginTop: 8, fontSize: 12, color: '#52c41a' }}>
                已选择: {uploadFile.name}
              </div>
            )}
          </div>
        );

      case 3:
        return (
          <div>
            <div style={{ marginBottom: 12 }}>
              预览解析结果（共 {previewRows.length} 行）：
            </div>
            {previewErrors.length > 0 && (
              <Alert
                type="warning"
                style={{ marginBottom: 12 }}
                message={`发现 ${previewErrors.length} 个警告`}
                description={previewErrors.slice(0, 3).join('；') + (previewErrors.length > 3 ? '...' : '')}
                showIcon
              />
            )}
            <Table
              rowKey="rowNo"
              columns={previewColumns}
              dataSource={previewRows}
              size="small"
              pagination={{ pageSize: 10, size: 'small' }}
              scroll={{ y: 280 }}
            />
            {(previewStats.total || previewStats.matched !== undefined) && (
              <Row gutter={16} style={{ marginTop: 12 }}>
                <Col span={8}>
                  <Statistic title="总行数" value={previewStats.total ?? previewRows.length} valueStyle={{ fontSize: 16 }} />
                </Col>
                <Col span={8}>
                  <Statistic title="已匹配" value={previewStats.matched ?? 0} valueStyle={{ fontSize: 16, color: '#52c41a' }} />
                </Col>
                <Col span={8}>
                  <Statistic title="未匹配" value={previewStats.unmatched ?? 0} valueStyle={{ fontSize: 16, color: previewStats.unmatched > 0 ? '#ff4d4f' : undefined }} />
                </Col>
              </Row>
            )}
            <div style={{ marginTop: 8, color: '#888', fontSize: 12 }}>
              未匹配料号将标记为警告，仍可继续导入
            </div>
          </div>
        );

      case 4:
        return (
          <div style={{ textAlign: 'center', padding: '24px 0' }}>
            {importResult ? (
              <>
                <CheckCircleOutlined style={{ fontSize: 48, color: '#52c41a' }} />
                <div style={{ marginTop: 16, fontSize: 16, fontWeight: 600 }}>导入成功</div>
                <div style={{ marginTop: 8, color: '#666' }}>
                  共导入 {importResult.totalRows ?? previewRows.length} 行数据
                </div>
                {importResult.quotationId && (
                  <Button
                    type="primary"
                    style={{ marginTop: 16 }}
                    onClick={handleGoToQuotation}
                  >
                    查看生成的报价单
                  </Button>
                )}
              </>
            ) : (
              <Spin size="large" />
            )}
          </div>
        );

      default:
        return null;
    }
  };

  const renderFooter = () => {
    if (currentStep === 4) {
      return [<Button key="close" onClick={handleClose}>关闭</Button>];
    }
    const buttons: React.ReactNode[] = [
      <Button key="cancel" onClick={handleClose}>取消</Button>,
    ];
    if (currentStep > 0) {
      buttons.push(
        <Button key="prev" onClick={() => setCurrentStep(s => s - 1)}>上一步</Button>
      );
    }
    if (currentStep < 3) {
      const nextHandlers = [handleStep0Next, handleStep1Next, handleStep2Next];
      const isLoading = currentStep === 0 ? templatesLoading : currentStep === 2 ? previewing : false;
      buttons.push(
        <Button key="next" type="primary" loading={isLoading} onClick={nextHandlers[currentStep]}>
          下一步
        </Button>
      );
    }
    if (currentStep === 3) {
      buttons.push(
        <Button key="import" type="primary" loading={confirming} onClick={handleConfirmImport}>
          确认导入
        </Button>
      );
    }
    return buttons;
  };

  return (
    <Modal
      title="从Excel新建报价单"
      open={open}
      onCancel={handleClose}
      width={720}
      footer={renderFooter()}
      destroyOnClose
    >
      <Steps
        current={currentStep}
        items={steps}
        size="small"
        style={{ marginBottom: 24 }}
      />
      {renderStepContent()}
    </Modal>
  );
};

export default ImportExcelModal;
