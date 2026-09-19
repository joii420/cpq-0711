# AC-5 / AC-23 亲验（开发库只读 SQL + 8081 日志），采样 2026-09-18 23:09:12
- AC-5：V26091802 | Ag | 0.000035000000（合并前快照同值 → 未回算）✅
- AC-23：合并后 8081 重载 23:01:45，启动收尾日志：
    2026-09-18 23:01:49.665 INFO  [com.cpq.pri.ser.PriceAdjustStartupRecovery] (Quarkus Main Thread) [price-adjust-recovery] 启动收尾：中断批次 0 个 []
    2026-09-18 23:01:49.727 INFO  [com.cpq.pri.ser.PriceAdjustStartupRecovery] (Quarkus Main Thread) [price-adjust-recovery] 预算续跑：待续跑版本 2 个 [a4147c07-4e2d-412b-b896-c4dd87fe6613, 06656cfc-606c-40ba-9129-68f9d21ce
    2026-09-18 23:01:49.760 INFO  [com.cpq.pri.ser.PriceAdjustStartupRecovery] (Quarkus Main Thread) [price-adjust-recovery] versionId=a4147c07-4e2d-412b-b896-c4dd87fe6613 异步续跑预算
    2026-09-18 23:01:49.763 INFO  [com.cpq.pri.ser.PriceAdjustStartupRecovery] (Quarkus Main Thread) [price-adjust-recovery] versionId=06656cfc-606c-40ba-9129-68f9d21ce1b4 异步续跑预算
    2026-09-18 23:01:49.923 INFO  [com.cpq.pri.ser.PriceAdjustBudgetService] (executor-thread-2) [price-adjust-budget] onVersionGenerated versionId=06656cfc-606c-40ba-9129-68f9d21ce1b4 customer=CUST-0002 materials=1 alreadyProcessed=0
    2026-09-18 23:01:49.940 INFO  [com.cpq.pri.ser.PriceAdjustBudgetService] (executor-thread-1) [price-adjust-budget] onVersionGenerated versionId=a4147c07-4e2d-412b-b896-c4dd87fe6613 customer=CUST-0004 materials=4558 alreadyProcesse
  23:09:12 采样：V26091802 有审核行料号 595、指针已推进 4、预算失败 0；自 23:01:49（已处理 13）起约 7.4 分钟增加 ≥582（≈0.76 s/个），门槛「10 分钟内 ≥120」✅；23:01 以来日志「预算试算超时 / ARJUNA012108」0 次 ✅
