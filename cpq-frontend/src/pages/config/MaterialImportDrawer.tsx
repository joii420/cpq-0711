/**
 * 材质库导入抽屉（task-0708 · F3；task-260901 · F-6 / F-7 改版）
 * ——对照 `原型图/4-导入抽屉与报告.html` 状态 A / B / C / D。
 *
 * 模板由「两 sheet」改为**单表 4 列**：材质 / 组号 / 元素符号 / 含量。
 *   • 不填材质编号 —— 按材质名匹配；不存在则自动创建并按 5 位补零自增发号
 *   • 不填元素编号 —— 按元素符号匹配元素字典；未建档则自动建档并自增元素编号
 *   • **组号仅用于在文件内把行分成若干组含量配置，不落库**；改组号不影响归属
 *   • 语义是**只增不改**：与该材质已有的 ACTIVE 配置逐值比对，内容已存在的组直接跳过
 *
 * 上传旧两 sheet 文件 → 后端返 400 `IMPORT_TEMPLATE_OUTDATED`，
 * 前端渲染 error alert + 「下载新模板」按钮，**不显示成功态**（AC-11）。
 *
 * → 服务 AC-6 / AC-9 / AC-10 / AC-11 / AC-12 / AC-23
 */
import React, { useEffect, useState } from 'react';
import {
  Drawer, Button, Space, Typography, Alert, Table, Divider, Tag, message,
} from 'antd';
import { DownloadOutlined } from '@ant-design/icons';
import type { UploadFile } from 'antd';
import CompactUploadDragger from '../../components/CompactUploadDragger';
import {
  materialRecipeService,
  type MaterialImportReport,
} from '../../services/materialRecipeService';
import { apiErrorCode, apiErrorMessage } from '../../utils/apiError';

const { Text, Paragraph } = Typography;

interface Props {
  open: boolean;
  onClose: () => void;
  onImported: () => void;
}

const StatTile: React.FC<{ label: string; value: React.ReactNode; tone?: 'warn' | 'err' }> = ({
  label, value, tone,
}) => (
  <div style={{ flex: 1, padding: '14px 16px', borderRight: '1px solid #f0f0f0' }}>
    <div style={{ fontSize: 12, color: 'rgba(0,0,0,.45)', marginBottom: 4 }}>{label}</div>
    <div
      style={{
        fontSize: 22, fontWeight: 600, lineHeight: 1.2,
        color: tone === 'warn' ? '#d48806' : tone === 'err' ? '#cf1322' : undefined,
      }}
    >
      {value}
    </div>
  </div>
);

const MaterialImportDrawer: React.FC<Props> = ({ open, onClose, onImported }) => {
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [importing, setImporting] = useState(false);
  const [downloading, setDownloading] = useState(false);
  const [report, setReport] = useState<MaterialImportReport | null>(null);
  /** 整体失败（400）——与「脏数据跳过」（200 报告）是两回事，必须分开渲染 */
  const [fatalError, setFatalError] = useState<string | null>(null);
  /** 整体失败的**字符串错误码**（`err.payload.code`），决定要不要给「下载新模板」按钮 */
  const [fatalCode, setFatalCode] = useState<string | null>(null);

  // 每次打开重置内部状态，避免复用上次导入的报告/文件
  useEffect(() => {
    if (open) {
      setSelectedFile(null);
      setImporting(false);
      setReport(null);
      setFatalError(null);
      setFatalCode(null);
    }
  }, [open]);

  const fileList: UploadFile[] = selectedFile
    ? [{ uid: '-1', name: selectedFile.name, status: 'done' }]
    : [];

  const handleDownloadTemplate = async () => {
    setDownloading(true);
    try {
      const blob = await materialRecipeService.downloadTemplate();
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = 'material_library_template.xlsx';
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

  const handleImport = async () => {
    if (!selectedFile) return;
    setImporting(true);
    setReport(null);
    setFatalError(null);
    setFatalCode(null);
    try {
      const res = await materialRecipeService.importLibrary(selectedFile);
      setReport(res);
      message.success(
        `导入完成：新增材质 ${res.recipesCreated} · 新增含量配置 ${res.configsCreated}`,
      );
    } catch (e: unknown) {
      // 400 = 文件整体不可用（旧模板 / 表头不符 / 空文件），后端已给出明确文案。
      // 🚨 按**字符串错误码**区分：空文件给「下载新模板」是答非所问。
      setFatalError(apiErrorMessage(e, '导入失败，请检查文件'));
      setFatalCode(apiErrorCode(e));
      setReport(null);
    } finally {
      setImporting(false);
    }
  };

  const handleDone = () => {
    onClose();
    onImported();
  };

  /** 重置：清空已选文件与导入报告（task-0728 · F6 footer 统一） */
  const handleReset = () => {
    setSelectedFile(null);
    setReport(null);
    setFatalError(null);
    setFatalCode(null);
  };

  /** 只有「模板/表头不对」才该指路到新模板；空文件不是模板问题 */
  const showTemplateHelp = fatalCode !== 'IMPORT_FILE_EMPTY';

  const isEmptyReport = !!report && report.totalRows === 0;

  return (
    <Drawer
      title={report ? '导入完成' : '导入材质库'}
      placement="right"
      width={960}
      open={open}
      onClose={onClose}
      destroyOnClose
      footer={
        <div style={{ textAlign: 'right' }}>
          <Space>
            <Button onClick={handleReset}>重置</Button>
            <Button
              type={report ? 'default' : 'primary'}
              loading={importing}
              disabled={!selectedFile || !!fatalError}
              onClick={handleImport}
            >
              {importing ? '导入中…' : '开始导入'}
            </Button>
            {report && <Button type="primary" onClick={handleDone}>完成</Button>}
          </Space>
        </div>
      }
    >
      {/* ── 状态 C：整体失败（旧两 sheet 模板 / 表头不符 / 空文件），不显示成功态 ── */}
      {fatalError && (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 16 }}
          message={fatalError}
          description={showTemplateHelp ? (
            <div>
              新模板为<b>单个工作表、4 列</b>：材质 / 组号 / 元素符号 / 含量（不再需要材质编号与元素编号）。
              <div style={{ marginTop: 10 }}>
                <Button
                  type="primary"
                  icon={<DownloadOutlined />}
                  loading={downloading}
                  onClick={handleDownloadTemplate}
                >
                  下载新模板
                </Button>
              </div>
              {fatalCode === 'IMPORT_TEMPLATE_OUTDATED' && (
                <div style={{ marginTop: 10, fontSize: 12, color: 'rgba(0,0,0,.45)' }}>
                  为什么直接报错而不是按旧语义跑：旧格式是「整体重灌覆盖」，新格式是「只增不改」。
                  两套相反的语义共用一个入口，用户无从判断这次导入到底会覆盖还是会新增。
                </div>
              )}
            </div>
          ) : (
            <span>请换一个有内容的 .xlsx 文件后重试。</span>
          )}
        />
      )}

      {/* ── 状态 A：上传前的说明 ── */}
      {!report && (
        <>
          <Alert
            style={{ marginBottom: 16 }}
            type="info"
            showIcon
            message="模板格式（4 列，单个工作表）：材质 | 组号 | 元素符号 | 含量"
            description={
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 12 }}>
                <Paragraph style={{ marginBottom: 0 }}>
                  • <Text strong>不填材质编号</Text> —— 按材质名匹配；材质不存在则自动创建并按 5 位补零自增发号<br />
                  • <Text strong>不填元素编号</Text> —— 按元素符号匹配元素字典；符号未建档则自动建档并自增元素编号<br />
                  • <Text strong>组号</Text>仅用于在文件内把行分成若干组含量配置，<Text strong>不落库</Text>；改组号不影响归属<br />
                  • 含量填 <Text strong>0~1 小数</Text>，同一组之和 = 1，支持 12 位小数
                </Paragraph>
                <Button
                  icon={<DownloadOutlined />}
                  loading={downloading}
                  onClick={handleDownloadTemplate}
                  style={{ flex: 'none' }}
                >
                  下载导入模板
                </Button>
              </div>
            }
          />

          <CompactUploadDragger
            accept=".xlsx"
            maxCount={1}
            multiple={false}
            fileList={fileList}
            beforeUpload={(file) => {
              setSelectedFile(file as unknown as File);
              setReport(null);
              setFatalError(null);
              return false; // 阻止自动上传，改由「开始导入」手动触发
            }}
            onRemove={() => { setSelectedFile(null); return true; }}
            disabled={importing}
            text="点击或拖拽 .xlsx 材质库文件到此处"
            hint="仅支持单个 .xlsx 文件，最大 20 MB"
          />

          <div
            style={{
              marginTop: 16, borderLeft: '3px solid #1677ff', background: '#f0f5ff',
              padding: '10px 14px', borderRadius: '0 6px 6px 0', fontSize: 12,
              color: 'rgba(0,0,0,.65)', lineHeight: 1.8,
            }}
          >
            <b>导入语义是「只增不改」：</b>与该材质<b>已有的启用中配置</b>逐值比对，内容已存在的组直接跳过，
            不存在的才新增一条配置。重复导入同一份文件<b>零新增</b>。
            <span style={{ color: '#cf1322' }}>改了含量再导入会<b>新增一条</b>配置，原来那条仍在</span>
            —— 要清除错误配置请到材质编辑抽屉里删。
          </div>
        </>
      )}

      {/* ── 状态 B / D：导入报告 ── */}
      {report && (
        <>
          <div
            style={{
              display: 'flex', border: '1px solid #f0f0f0', borderRadius: 8,
              overflow: 'hidden', marginBottom: 16,
            }}
          >
            <StatTile label="读取行数" value={report.totalRows} />
            <StatTile label="新增材质" value={report.recipesCreated} />
            <StatTile label="新增含量配置" value={report.configsCreated} />
            <StatTile
              label="新建元素"
              value={report.createdElements?.length ?? 0}
              tone={(report.createdElements?.length ?? 0) > 0 ? 'warn' : undefined}
            />
            <StatTile
              label="跳过"
              value={report.skippedRowCount}
              tone={report.skippedRowCount > 0 ? 'err' : undefined}
            />
            <StatTile label="耗时" value={<span style={{ fontSize: 16 }}>{report.durationMs} ms</span>} />
          </div>

          {isEmptyReport ? (
            // 状态 D：表头识别正常但没有数据行 —— 不报 500、不算失败
            <div style={{ padding: '40px 0', textAlign: 'center', color: 'rgba(0,0,0,.45)' }}>
              <div style={{ fontSize: 15, marginBottom: 6 }}>文件无有效数据行</div>
              <div style={{ fontSize: 12 }}>表头识别正常，但表头下方没有任何数据行。库内数据未发生变化。</div>
            </div>
          ) : (
            <>
              {/* X-2 / AC-6：本次自动新建的元素必须单独列出来给业务复核 */}
              {report.createdElements && report.createdElements.length > 0 && (
                <Alert
                  type="warning"
                  showIcon
                  style={{ marginBottom: 16 }}
                  message={`本次自动新建了 ${report.createdElements.length} 个元素，请业务复核`}
                  description={
                    <div>
                      <Table
                        size="small"
                        rowKey={(r) => r.elementNo}
                        style={{ marginTop: 8, background: '#fff' }}
                        pagination={false}
                        dataSource={report.createdElements}
                        columns={[
                          { title: '元素编号', dataIndex: 'elementNo', key: 'elementNo', width: 110 },
                          { title: '符号', dataIndex: 'elementCode', key: 'elementCode', width: 100 },
                          { title: '中文名', dataIndex: 'elementName', key: 'elementName' },
                          {
                            title: '来源',
                            key: 'source',
                            render: (_: unknown, r) => (
                              <span style={{ color: 'rgba(0,0,0,.45)' }}>
                                材质 {r.sourceRecipe}，第 {r.sourceRow} 行
                              </span>
                            ),
                          },
                        ]}
                      />
                      <div style={{ marginTop: 8, fontSize: 12 }}>
                        元素主表是取价链路的根。若这个符号其实是<b>填错的列</b>
                        （例如把元素编号填进了符号列），请到「主数据维护 → 元素」停用它，并修正 Excel 后重导。
                      </div>
                    </div>
                  }
                />
              )}

              <div style={{ fontSize: 13, fontWeight: 600, margin: '20px 0 8px' }}>
                新增明细
                {report.configsSkippedAsDuplicate > 0 && (
                  <span style={{ fontWeight: 400, color: 'rgba(0,0,0,.45)', marginLeft: 8 }}>
                    另有 {report.configsSkippedAsDuplicate} 组配置内容与已有配置完全相同，已跳过（只增不改）
                  </span>
                )}
              </div>
              <Table
                size="small"
                rowKey={(r) => r.configNo}
                pagination={false}
                scroll={{ y: 280 }}
                dataSource={report.createdConfigs ?? []}
                locale={{ emptyText: '本次没有新增任何含量配置' }}
                columns={[
                  { title: '材质编号', dataIndex: 'recipeCode', key: 'recipeCode', width: 110 },
                  { title: '材质名', dataIndex: 'recipeSymbol', key: 'recipeSymbol', width: 150 },
                  { title: '配置编号', dataIndex: 'configNo', key: 'configNo', width: 110 },
                  { title: '元素含量', dataIndex: 'summary', key: 'summary' },
                  {
                    title: '动作',
                    key: 'action',
                    width: 130,
                    render: (_: unknown, r) => (
                      r.recipeIsNew
                        ? <Tag color="blue">新增材质+配置</Tag>
                        : <Tag color="green">新增配置</Tag>
                    ),
                  },
                ]}
              />

              {report.skipped && report.skipped.length > 0 && (
                <>
                  <div style={{ fontSize: 13, fontWeight: 600, margin: '20px 0 8px' }}>
                    跳过明细
                    <span style={{ fontWeight: 400, color: 'rgba(0,0,0,.45)', marginLeft: 6 }}>
                      （{report.skipped.length} 条）
                    </span>
                  </div>
                  <Table
                    size="small"
                    rowKey={(_, idx) => String(idx)}
                    pagination={false}
                    scroll={{ y: 280 }}
                    dataSource={report.skipped}
                    columns={[
                      {
                        title: 'Excel 行',
                        dataIndex: 'row',
                        key: 'row',
                        width: 80,
                        render: (v?: number | null) => (v == null ? '整个材质' : v),
                      },
                      { title: '工作表 / 材质', dataIndex: 'sheet', key: 'sheet', width: 170 },
                      {
                        // reason **原文展示，不做前端映射**（api.md §2.3）
                        title: '原因',
                        dataIndex: 'reason',
                        key: 'reason',
                        width: 300,
                        render: (v: string) => <Tag color="red">{v}</Tag>,
                      },
                      {
                        title: '原始内容',
                        dataIndex: 'raw',
                        key: 'raw',
                        render: (v?: string) => (
                          <span style={{ color: 'rgba(0,0,0,.45)' }}>{v ?? '—'}</span>
                        ),
                      },
                    ]}
                  />
                </>
              )}

              <Divider />
              <div style={{ fontSize: 12, color: 'rgba(0,0,0,.45)', lineHeight: 1.8 }}>
                <b>被整体跳过的材质既不落库，也不消耗编号</b> —— 编号只发给校验通过的材质，所以新发的号是连续的、没有空号。
                <br />
                「同一材质内各组元素组成不一致」是<b>整个材质跳过</b>而不是只跳后面那组：新材质的元素组成还没定，
                若「第一组胜」就成了看谁排在前面谁说了算，同样的数据换个行序入库结果就不同。
                改成整材质跳过后，<b>结果与行序无关</b>。
              </div>
            </>
          )}
        </>
      )}
    </Drawer>
  );
};

export default MaterialImportDrawer;
