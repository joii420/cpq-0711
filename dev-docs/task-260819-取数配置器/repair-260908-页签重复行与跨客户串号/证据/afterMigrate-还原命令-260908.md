# afterMigrate 副作用还原命令

> 用户 2026-09-08 按 `CLAUDE.md §3.2` 批准「先快照再跑」。快照见同目录两个 csv。

**批准前量化的净影响 = 1 行**（其余全是空操作）：

| 语句 | 命中 |
|---|---|
| 4 个 `system_config` 重置 | **全空操作**（当前值 == 默认值，逐条实测） |
| `admin` 置 ACTIVE | **空操作**（已是 `ACTIVE, failed=0`） |
| 非 admin 的 SYSTEM_ADMIN 置 INACTIVE | 匹配 4 行，3 行已 INACTIVE ⇒ **真正会变的只有 1 行** |

**唯一会被改的账号**：`test-pm-longname-d0b2269b-2f69-490a-b581-55723e071936`（快照时 `ACTIVE`，测试夹具账号）

## 还原（跑完测试后执行）

```sql
UPDATE "user" SET status='ACTIVE'
WHERE username='test-pm-longname-d0b2269b-2f69-490a-b581-55723e071936' AND role='SYSTEM_ADMIN';
```

⚠️ 还原前先比对 csv 快照，确认没有别的行被动过：

```sql
SELECT username, status FROM "user" WHERE role='SYSTEM_ADMIN' ORDER BY username;
```

🚫 **批准不跨操作**：本批准仅覆盖 `afterMigrate.sql` 这三条语句，不含任何其他红线。
