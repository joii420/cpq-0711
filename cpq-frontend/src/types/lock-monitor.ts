export interface ProductImportLockDTO {
  id: string;
  customerId: string;
  partNo: string | null;
  granularity: string;
  lockedBy: string;
  importRecordId: string | null;
  lockedAt: string;
  lastHeartbeatAt: string;
  expiresAt: string;
  status: string;
  releasedAt: string | null;
  releaseReason: string | null;
}

export interface DdlLockStatusDTO {
  locked: boolean;
  lockedBy: string | null;
  lockedAt: string | null;
  expiresAt: string | null;
  operationDesc: string | null;
}
