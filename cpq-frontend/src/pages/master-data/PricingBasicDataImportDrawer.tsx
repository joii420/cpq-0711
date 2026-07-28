import { DownloadOutlined } from '@ant-design/icons';
import {
  Alert,
  Button,
  Drawer,
  Empty,
  message,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd';
import type { UploadFile, UploadProps } from 'antd/es/upload/interface';
import { useEffect, useMemo, useState } from 'react';

import CompactUploadDragger from '../../components/CompactUploadDragger';
import {
  basicDataImportV6Service,
  type ImportResultDTO,
  type SheetResultDTO,
} from '../../services/basicDataImportV6Service';

const { Text } = Typography;

interface Props {
  open: boolean;
  onClose: () => void;
}

export default function PricingBasicDataImportDrawer({ open, onClose }: Props) {
  const [fileList, setFileList] = useState<UploadFile[]>([]);
  const [submitting, setSubmitting] = useState(false);
  const [downloading, setDownloading] = useState(false);
  const [result, setResult] = useState<ImportResultDTO | null>(null);

  useEffect(() => {
    if (!open) { setResult(null); setFileList([]); }
  }, [open]);

  const draggerProps: UploadProps = {
    name: 'file',
    multiple: false,
    accept: '.xlsx',
    fileList,
    beforeUpload: (file) => {
      setFileList([file as unknown as UploadFile]);
      return false;
    },
    onRemove: () => setFileList([]),
  };

  /** 下载 24 Sheet 空模板（task-0728 · A4）。 */
  const handleDownloadTemplate = async () => {
    setDownloading(true);
    try {
      const blob = await basicDataImportV6Service.downloadPricingTemplate();
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = 'pricing_basic_data_template.xlsx';
      document.body.appendChild(a);
      a.click();
      a.remove();
      URL.revokeObjectURL(url);
    } catch {
      message.error('模板下载失败，请稍后重试');
    } finally {
      setDownloading(false);
    }
  };

  const handleSubmit = async () => {
    if (fileList.length === 0) return message.warning('请先上传 Excel 文件');
    setSubmitting(true);
    try {
      const file = (fileList[0] as unknown as { originFileObj?: File }).originFileObj
        ?? (fileList[0] as unknown as File);
      const r = await basicDataImportV6Service.importPricing(file as File);
      setResult(r);
      if (r.status === 'SUCCESS') message.success(`导入成功 ${r.totalSuccessRows} 行`);
      else if (r.status === 'PARTIAL')
        message.warning(`部分成功：${r.totalSuccessRows} 行 / 失败 ${r.totalFailedRows} 行`);
      else message.error(`导入失败 ${r.totalFailedRows} 行`);
    } catch (e: any) {
      message.error(e?.message ?? '导入异常');
    } finally {
      setSubmitting(false);
    }
  };

  const statusTag = useMemo(() => {
    if (!result) return null;
    const color = result.status === 'SUCCESS' ? 'green' : result.status === 'PARTIAL' ? 'orange' : 'red';
    return <Tag color={color}>{result.status}</Tag>;
  }, [result]);

  const columns = [
    { title: 'Sheet', dataIndex: 'sheetName', width: 220 },
    {
      title: '行数',
      width: 140,
      render: (_: unknown, r: SheetResultDTO) => `${r.successRows} / ${r.totalRows}`,
    },
    { title: '失败', dataIndex: 'failedRows', width: 80 },
    {
      title: '写入',
      render: (_: unknown, r: SheetResultDTO) =>
        Object.entries(r.writtenCounts ?? {}).map(([t, n]) => (
          <Tag key={t}>{t}:{n}</Tag>
        )),
    },
  ];

  return (
    <Drawer
      title="核价基础数据导入 (V6 · 24 Sheet)"
      width={840}
      placement="right"
      open={open}
      onClose={onClose}
      destroyOnClose
      footer={
        <div style={{ textAlign: 'right' }}>
          <Space>
            <Button onClick={() => { setResult(null); setFileList([]); }}>重置</Button>
            <Button type="primary" onClick={handleSubmit} loading={submitting}
                    disabled={fileList.length === 0}>
              开始导入
            </Button>
          </Space>
        </div>
      }
    >
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <Alert
          type="info"
          showIcon
          message="导入说明"
          description={
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 12 }}>
              <div>
                核价基础数据为全局数据，无客户上下文。`宏丰-客户料号对应关系` Sheet 的 customer_no 从 Excel 行读取。
              </div>
              <Button
                icon={<DownloadOutlined />}
                loading={downloading}
                onClick={handleDownloadTemplate}
                style={{ flex: 'none' }}
              >
                下载模板
              </Button>
            </div>
          }
        />

        <CompactUploadDragger
          {...draggerProps}
          text="点击或拖拽 .xlsx 文件到此区域"
          hint="24 Sheet 核价基础数据 / 单文件"
        />

        {result && (
          <>
            <Alert
              type={result.status === 'SUCCESS' ? 'success' : result.status === 'PARTIAL' ? 'warning' : 'error'}
              showIcon
              message={
                <Space>
                  导入完成 {statusTag}
                  <Text>成功 {result.totalSuccessRows} 行</Text>
                  {result.totalFailedRows > 0 && <Text type="danger">失败 {result.totalFailedRows} 行</Text>}
                </Space>
              }
            />
            <Table
              size="small"
              rowKey="sheetName"
              pagination={false}
              columns={columns as any}
              dataSource={result.sheetResults}
              expandable={{
                expandedRowRender: (r) =>
                  r.errors?.length ? (
                    <Table
                      size="small"
                      rowKey={(e: any) => `${e.rowNo}-${e.column}-${e.message}`}
                      pagination={false}
                      columns={[
                        { title: '行号', dataIndex: 'rowNo', width: 80 },
                        { title: '列', dataIndex: 'column', width: 200 },
                        { title: '错误', dataIndex: 'message' },
                      ]}
                      dataSource={r.errors}
                    />
                  ) : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="无错误" />,
                rowExpandable: (r) => (r.failedRows ?? 0) > 0,
              }}
            />
          </>
        )}
      </Space>
    </Drawer>
  );
}
