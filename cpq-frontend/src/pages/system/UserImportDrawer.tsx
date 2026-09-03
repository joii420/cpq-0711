/**
 * 用户批量导入抽屉（task-260902 · F-4 / F-5）
 * ——对照 `原型图/4-用户导入抽屉.html` 状态 A / B / C / D
 *   与 `原型图/5-用户导入结果.html` 状态 A / B / C / D。
 *
 * 形态照 `pages/master-data/ProcessMasterImportDrawer.tsx`：说明区 → 上传区 → 下载模板 → 底部动作，
 * **导入完成后在同一个抽屉里换内容**，不新开弹层。
 *
 * 🚨 这是全任务唯一的敏感界面：**初始密码只在本次响应里出现一次**，
 * 关掉抽屉后再也查不到（只能到用户列表点「重置密码」重新生成）。所以：
 *   ① 密码区必须带「只显示这一次」的警示条；
 *   ② `createdCount === 0` 时**整个密码区（含警示条）不渲染** —— 空的密码表格会让人以为密码丢了；
 *   ③ 「跳过」与「提示」分开渲染：跳过 = 这一行**没有**创建用户（独立表格）；
 *      提示 = 用户**创建成功了**、只是某个非必需字段没落上（挂在成功行最后一列）。
 *
 * → 服务 AC-14 / AC-15 / AC-16 / AC-17 / AC-18 / AC-24 / AC-25
 */
import React, { useEffect, useState } from 'react';
import {
  Drawer, Button, Space, Typography, Alert, Table, Tooltip, Tag, Spin, message,
} from 'antd';
import {
  DownloadOutlined, CloseOutlined, FileExcelOutlined, CopyOutlined,
} from '@ant-design/icons';
import type { UploadFile } from 'antd';
import CompactUploadDragger from '../../components/CompactUploadDragger';
import {
  userService,
  type UserImportReport,
  type UserImportCreatedRow,
  type UserImportSkippedRow,
} from '../../services/userService';
import { apiErrorCode, apiErrorMessage } from '../../utils/apiError';

const { Text, Paragraph } = Typography;

interface Props {
  open: boolean;
  onClose: () => void;
  /** 有新增时回调，用于刷新用户列表 */
  onImported?: () => void;
}

/** 角色标签配色（与 `原型图/5-用户导入结果.html` 状态 A 一致） */
const ROLE_COLOR: Record<string, string> = {
  SYSTEM_ADMIN: 'red',
  SALES_MANAGER: 'blue',
  SALES_REP: 'green',
  PRICING_MANAGER: 'gold',
};

/** 结果页顶部的 4 个计数块（原型 `.stat .box`） */
const StatBox: React.FC<{
  label: string;
  value: React.ReactNode;
  tone?: 'ok' | 'warn';
  small?: boolean;
}> = ({ label, value, tone, small }) => (
  <div
    style={{
      flex: 1, minWidth: 130, border: '1px solid #f0f0f0', borderRadius: 8,
      padding: '12px 16px', background: '#fff',
    }}
  >
    <div
      style={{
        fontSize: small ? 16 : 26, fontWeight: 600, lineHeight: 1.2,
        color: tone === 'ok' ? '#389e0d' : tone === 'warn' ? '#d48806' : undefined,
      }}
    >
      {value}
    </div>
    <div style={{ fontSize: 12, color: 'rgba(0,0,0,.45)', marginTop: 2 }}>{label}</div>
  </div>
);

/** 已选文件行（原型 `.file`）——导入中把上传区换成它，避免用户以为还能改文件 */
const FileRow: React.FC<{ file: File }> = ({ file }) => (
  <div
    style={{
      border: '1px solid #f0f0f0', borderRadius: 6, padding: '8px 12px',
      display: 'flex', alignItems: 'center', gap: 8, fontSize: 13, background: '#fff',
    }}
  >
    <FileExcelOutlined style={{ color: '#389e0d' }} />
    <span style={{ flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
      {file.name}
    </span>
    <span style={{ color: 'rgba(0,0,0,.45)' }}>{(file.size / 1024).toFixed(1)} KB</span>
  </div>
);

const UserImportDrawer: React.FC<Props> = ({ open, onClose, onImported }) => {
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [importing, setImporting] = useState(false);
  const [downloading, setDownloading] = useState(false);
  const [report, setReport] = useState<UserImportReport | null>(null);
  /** 「文件本身不可用」(400)——与「脏数据行跳过」(200 报告) 是两回事，必须分开渲染（AC-24） */
  const [fatalError, setFatalError] = useState<string | null>(null);
  const [fatalCode, setFatalCode] = useState<string | null>(null);

  // 每次打开重置内部状态，避免复用上次导入的报告/文件（密码更不能留到下一次）
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
      await userService.downloadImportTemplate();
    } catch (e: unknown) {
      message.error(apiErrorMessage(e, '模板下载失败，请稍后重试'));
    } finally {
      setDownloading(false);
    }
  };

  /**
   * 🚫 **刻意不做客户端的 .xlsx 前置拦截**：AC-24 断言的是「上传 .txt → HTTP 400 → 红字提示
   * 「请上传 .xlsx 文件」」。在前端提前拦掉的话请求根本不会发出，那条 AC 的可观测断言就落空了。
   * 文案一律用后端返回的 message，不在前端另写一套。
   */
  const handleImport = async () => {
    if (!selectedFile) return;
    setImporting(true);
    setReport(null);
    setFatalError(null);
    setFatalCode(null);
    try {
      const res = await userService.importUsers(selectedFile);
      setReport(res);
    } catch (e: unknown) {
      setFatalError(apiErrorMessage(e, '导入失败，请检查文件'));
      setFatalCode(apiErrorCode(e));
      setReport(null);
    } finally {
      setImporting(false);
    }
  };

  /** 导入中锁死：关闭按钮 / 取消 / 遮罩 / ESC 全部无效，避免用户中途关掉后不知道到底导没导进去 */
  const handleRequestClose = () => {
    if (importing) return;
    onClose();
  };

  /**
   * 底部按钮：空文件（三个 0）→「完成」只关闭；其余 →「完成并刷新列表」。
   * ⚠️ 刷不刷新必须与按钮文案一致 —— 写着「并刷新列表」却不刷新，是在骗用户。
   * （原型图 5 状态 C「0 新增 3 跳过」画的就是「完成并刷新列表」，只有状态 D 是「完成」。）
   */
  const handleDone = () => {
    const shouldRefresh = !!report && report.totalRows > 0;
    onClose();
    if (shouldRefresh) onImported?.();
  };

  const handleCopyPasswords = async () => {
    const rows = report?.created ?? [];
    if (rows.length === 0) return;
    const text = rows.map((r) => `${r.username}\t${r.initialPassword}`).join('\n');
    try {
      await navigator.clipboard.writeText(text);
      message.success(`已复制 ${rows.length} 条初始密码`);
    } catch {
      // 非 HTTPS / 无剪贴板权限时的兜底：临时 textarea + execCommand
      const ta = document.createElement('textarea');
      ta.value = text;
      ta.style.position = 'fixed';
      ta.style.opacity = '0';
      document.body.appendChild(ta);
      ta.select();
      const ok = document.execCommand('copy');
      ta.remove();
      if (ok) message.success(`已复制 ${rows.length} 条初始密码`);
      else message.error('复制失败，请手动选中密码列复制');
    }
  };

  // ── 结果分支（F-5 的三个渲染分支，🚫 不许混）──
  const createdRows = report?.created ?? [];
  const skippedRows = report?.skipped ?? [];
  /** 空文件：表头正确但一行数据都没有 —— 不报错，正常进结果页显示三个 0（AC-24） */
  const isEmptyReport = !!report && report.totalRows === 0;
  const hasCreated = (report?.createdCount ?? 0) > 0;
  const hasSkipped = (report?.skippedCount ?? 0) > 0;

  /** 「开始导入」的禁用原因（禁用但可见 + hover 给原因，`frontend.md §1.2`） */
  const importDisabledReason = importing
    ? '正在处理，请稍候'
    : fatalError
      ? '请先修正文件后重新选择'
      : !selectedFile
        ? '请先选择要导入的文件'
        : '';

  const footer = report ? (
    <div style={{ textAlign: 'right' }}>
      {/* 状态 D（空文件）什么都没变，按钮文案就不该是「并刷新列表」 */}
      <Button type="primary" onClick={handleDone}>
        {isEmptyReport ? '完成' : '完成并刷新列表'}
      </Button>
    </div>
  ) : (
    <div style={{ textAlign: 'right' }}>
      <Space>
        {/* antd v6 已自带 disabled 子元素的 hover 兼容层（2026-09-02 实测：裸包 Button 也能弹出
            tooltip），故沿用 SelectableTable 的既有写法，不额外套 span */}
        <Tooltip title={importing ? '导入进行中，无法取消' : ''}>
          <Button disabled={importing} onClick={handleRequestClose}>取消</Button>
        </Tooltip>
        <Tooltip title={importDisabledReason}>
          <Button
            type="primary"
            loading={importing}
            disabled={!!importDisabledReason}
            onClick={handleImport}
          >
            {importing ? '导入中…' : '开始导入'}
          </Button>
        </Tooltip>
      </Space>
    </div>
  );

  return (
    <Drawer
      title={report ? '导入完成' : '导入用户'}
      placement="right"
      // 上传态 640（原型图 4）；结果页要放下 5 列密码表 + 跳过表，按原型图 5 的 wide 加宽
      width={report ? 960 : 640}
      open={open}
      onClose={handleRequestClose}
      maskClosable={false}
      keyboard={!importing}
      closeIcon={
        <CloseOutlined style={importing ? { opacity: 0.35, cursor: 'not-allowed' } : undefined} />
      }
      destroyOnClose
      footer={footer}
    >
      {/* ══ 结果页（F-5）══ */}
      {report ? (
        <>
          <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', marginBottom: 16 }}>
            <StatBox label="读取行数" value={report.totalRows} />
            <StatBox label="新增用户" value={report.createdCount} tone={hasCreated ? 'ok' : undefined} />
            <StatBox label="跳过" value={report.skippedCount} tone={hasSkipped ? 'warn' : undefined} />
            <StatBox label="耗时" value={`${report.elapsedMs} ms`} small />
          </div>

          {/* 状态 D：空文件 —— 表头识别正常但没有数据行 */}
          {isEmptyReport && (
            <div style={{ padding: '28px 0', textAlign: 'center', color: 'rgba(0,0,0,.45)' }}>
              <FileExcelOutlined style={{ fontSize: 28 }} />
              <div style={{ fontSize: 15, margin: '8px 0 6px', color: 'rgba(0,0,0,.65)' }}>
                文件里没有数据行
              </div>
              <div style={{ fontSize: 12 }}>表头正确，但表头以下一行数据都没有</div>
            </div>
          )}

          {/* 状态 C：一条都没新增 —— 🚨 整个密码区（含警示条）不渲染，空表格会让人以为密码丢了 */}
          {!isEmptyReport && !hasCreated && (
            <Alert
              type="info"
              showIcon
              style={{ marginBottom: 16 }}
              message={
                <span>
                  <Text strong>没有新增任何用户</Text>，因此没有初始密码需要显示。
                  现有用户的姓名、角色、区域、部门<Text strong>均未被修改</Text>。
                </span>
              }
            />
          )}

          {/* 状态 A / B：密码区 —— 仅 createdCount > 0 时存在 */}
          {hasCreated && (
            <>
              <Alert
                type="warning"
                showIcon
                style={{ marginBottom: 14 }}
                message={<Text strong>以下初始密码只显示这一次，请立即复制并转交给对应同事。</Text>}
                description={
                  <div style={{ lineHeight: 1.8 }}>
                    关闭本窗口后无法再查看；忘了可以在用户列表对该用户点「重置密码」重新生成。
                    <br />
                    新用户首次登录时会被<Text strong>强制修改密码</Text>。
                  </div>
                }
              />

              <Table<UserImportCreatedRow>
                size="small"
                rowKey={(r) => `${r.rowNum}-${r.username}`}
                pagination={false}
                style={{ marginBottom: 8 }}
                dataSource={createdRows}
                columns={[
                  {
                    title: '用户名',
                    dataIndex: 'username',
                    key: 'username',
                    width: 150,
                    render: (v: string) => <span style={{ fontFamily: 'Consolas, monospace' }}>{v}</span>,
                  },
                  { title: '姓名', dataIndex: 'fullName', key: 'fullName', width: 120 },
                  {
                    title: '角色',
                    dataIndex: 'role',
                    key: 'role',
                    width: 110,
                    render: (role: string, r) => (
                      <Tag color={ROLE_COLOR[role]}>{r.roleLabel || role}</Tag>
                    ),
                  },
                  {
                    title: '初始密码',
                    dataIndex: 'initialPassword',
                    key: 'initialPassword',
                    width: 180,
                    render: (v: string) => (
                      <span
                        style={{
                          fontFamily: 'Consolas, monospace', background: '#fffbe6',
                          border: '1px solid #ffe58f', borderRadius: 4, padding: '1px 8px',
                          fontSize: 13, letterSpacing: '.5px',
                        }}
                      >
                        {v}
                      </span>
                    ),
                  },
                  {
                    // 🚫 「提示」≠「跳过」：这一行的用户**已经建好了**，只是某个非必需字段没落上
                    title: '提示',
                    dataIndex: 'hint',
                    key: 'hint',
                    render: (v?: string | null) =>
                      (v ? <Tag color="gold">{v}</Tag> : <span style={{ color: 'rgba(0,0,0,.45)' }}>-</span>),
                  },
                ]}
              />

              <div style={{ display: 'flex', gap: 8, marginBottom: 20, alignItems: 'center', flexWrap: 'wrap' }}>
                <Button icon={<CopyOutlined />} onClick={handleCopyPasswords}>复制全部密码</Button>
                <span style={{ fontSize: 12, color: 'rgba(0,0,0,.45)' }}>
                  复制为「用户名 制表符 密码」两列，可直接粘进 Excel 或聊天窗口
                </span>
              </div>
            </>
          )}

          {/* 跳过区 —— 🚨 skippedCount === 0 时整块不渲染，也不留空表格占位 */}
          {hasSkipped && (
            <>
              <div style={{ fontSize: 13, fontWeight: 600, margin: '0 0 10px' }}>
                跳过的 {report.skippedCount} 行
              </div>
              <Table<UserImportSkippedRow>
                size="small"
                rowKey={(r, idx) => `${r.rowNum}-${idx}`}
                pagination={false}
                scroll={{ y: 320 }}
                dataSource={skippedRows}
                columns={[
                  {
                    title: 'Excel 行',
                    dataIndex: 'rowNum',
                    key: 'rowNum',
                    width: 90,
                    render: (v: number) => <span style={{ fontFamily: 'Consolas, monospace' }}>{v}</span>,
                  },
                  {
                    title: '用户名',
                    dataIndex: 'username',
                    key: 'username',
                    width: 160,
                    render: (v?: string | null) => (
                      <span style={{ fontFamily: 'Consolas, monospace' }}>{v || '—'}</span>
                    ),
                  },
                  // reason 是后端原文，前端不做映射（与材质导入报告同口径）
                  { title: '原因', dataIndex: 'reason', key: 'reason' },
                ]}
              />
            </>
          )}
        </>
      ) : importing ? (
        /* ══ 状态 C：导入中 ══ */
        <>
          {selectedFile && <FileRow file={selectedFile} />}
          <div style={{ textAlign: 'center', padding: '28px 0', color: 'rgba(0,0,0,.45)' }}>
            <Spin />
            <div style={{ marginTop: 8 }}>正在导入，请勿关闭此窗口…</div>
          </div>
        </>
      ) : (
        /* ══ 状态 A / B / D：上传前 ══ */
        <>
          {/* 状态 D：文件本身不可用（非 xlsx / 表头不符 / 解析失败）——错误留在抽屉里，不跳结果页 */}
          {fatalError && (
            <Alert
              type="error"
              showIcon
              style={{ marginBottom: 16 }}
              message={<Text strong>{fatalError}</Text>}
              description={
                fatalCode === 'IMPORT_HEADER_INVALID' ? (
                  <span style={{ color: 'rgba(0,0,0,.45)' }}>
                    期望的前 6 列：用户名 | 姓名 | 邮箱 | 角色 | 区域 | 部门
                  </span>
                ) : undefined
              }
            />
          )}

          {!fatalError && (
            <Alert
              style={{ marginBottom: 16 }}
              type="info"
              showIcon
              message={
                <span>
                  <Text strong>模板列（6 列，单个工作表）：</Text>
                  用户名 | 姓名 | 邮箱 | 角色 | 区域 | 部门
                </span>
              }
              description={
                <Paragraph style={{ marginBottom: 0 }}>
                  • <Text strong>只新增，不修改</Text> —— 用户名已存在的行整行跳过，不会覆盖现有用户的姓名/角色/部门<br />
                  • <Text strong>不填密码</Text> —— 系统为每个新用户生成一个随机初始密码，导入完成后在结果页统一显示<br />
                  • <Text strong>角色</Text>填：系统管理员 / 销售经理 / 销售代表 / 财务（也接受 <Text code>SYSTEM_ADMIN</Text> 等英文枚举）<br />
                  • <Text strong>区域 / 部门</Text>可留空；填了但系统里没有该名称时，该行<Text strong>仍会创建</Text>，
                  只是这两个字段留空并在报告里提示
                </Paragraph>
              }
            />
          )}

          <CompactUploadDragger
            accept=".xlsx"
            maxCount={1}
            multiple={false}
            fileList={fileList}
            beforeUpload={(file) => {
              setSelectedFile(file as unknown as File);
              setReport(null);
              setFatalError(null);
              setFatalCode(null);
              return false; // 阻止自动上传，改由「开始导入」手动触发
            }}
            onRemove={() => {
              setSelectedFile(null);
              setFatalError(null);
              setFatalCode(null);
              return true;
            }}
            text="点击或拖拽 .xlsx 文件到此处"
            hint="仅支持 .xlsx，单文件最大 20 MB"
          />

          <div style={{ marginTop: 12 }}>
            <Button icon={<DownloadOutlined />} loading={downloading} onClick={handleDownloadTemplate}>
              下载导入模板
            </Button>
          </div>

          {!fatalError && (
            <div
              style={{
                marginTop: 14, background: '#fafafa', borderLeft: '3px solid #d9d9d9',
                padding: '10px 14px', borderRadius: '0 4px 4px 0', fontSize: 12,
                color: 'rgba(0,0,0,.65)', lineHeight: 1.8,
              }}
            >
              <b>为什么不做「导入前预览再确认」：</b>本次语义是「只新增、重复跳过」——
              最坏情况是多出几个可以停用的账号，<b>不会破坏任何现有数据</b>，多一轮确认换不来对应的安全收益。
              与材质/工序导入的交互也保持一致（都是直接执行 + 报告）。
            </div>
          )}
        </>
      )}
    </Drawer>
  );
};

export default UserImportDrawer;
