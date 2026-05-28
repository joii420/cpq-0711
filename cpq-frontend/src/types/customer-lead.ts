// v0.4 §17.5 客户线索

export type LeadStatus = 'PENDING_REVIEW' | 'CONVERTED' | 'REJECTED';
export type LeadReviewAction = 'BIND_EXISTING' | 'CREATE_NEW' | 'REJECT';
export type LeadSourceType = 'CUSTOMER_SELF' | 'SHARED_LINK' | 'IMPORT_BATCH';

export interface CustomerLead {
  id: string;
  leadCode: string;
  sourceType: LeadSourceType;
  shareToken?: string;
  contactName: string;
  contactPhone: string;
  contactEmail?: string;
  companyName?: string;
  note?: string;
  status: LeadStatus;
  reviewedBy?: string;
  reviewedAt?: string;
  reviewAction?: LeadReviewAction;
  boundCustomerId?: string;
  reviewNote?: string;
  createdAt: string;
  updatedAt: string;
}
