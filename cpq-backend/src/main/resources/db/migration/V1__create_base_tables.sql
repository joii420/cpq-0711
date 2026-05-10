-- ===========================================
-- V1: Base tables for CPQ system
-- User, Region, Department, OperationLog,
-- PasswordResetToken, Notification
-- ===========================================

-- --- Region dictionary ---
CREATE TABLE region (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    sort_order INTEGER DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT chk_region_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

-- --- Department dictionary ---
CREATE TABLE department (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    sort_order INTEGER DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT chk_department_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

-- --- User ---
CREATE TABLE "user" (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username VARCHAR(100) NOT NULL UNIQUE,
    full_name VARCHAR(200) NOT NULL,
    email VARCHAR(200) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(30) NOT NULL,
    region_id UUID REFERENCES region(id),
    department_id UUID REFERENCES department(id),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    is_first_login BOOLEAN NOT NULL DEFAULT true,
    initial_password_expires_at TIMESTAMP WITH TIME ZONE,
    failed_login_attempts INTEGER NOT NULL DEFAULT 0,
    locked_until TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT chk_user_role CHECK (role IN ('SALES_REP', 'SALES_MANAGER', 'PRICING_MANAGER', 'SYSTEM_ADMIN')),
    CONSTRAINT chk_user_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE INDEX idx_user_role ON "user"(role);
CREATE INDEX idx_user_status ON "user"(status);
CREATE INDEX idx_user_region ON "user"(region_id);
CREATE INDEX idx_user_department ON "user"(department_id);

-- --- Password Reset Token ---
CREATE TABLE password_reset_token (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES "user"(id),
    token_hash VARCHAR(255) NOT NULL UNIQUE,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    used_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_prt_user_expires ON password_reset_token(user_id, expires_at);

-- --- Operation Log ---
CREATE TABLE operation_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    operator_id UUID NOT NULL REFERENCES "user"(id),
    operation_type VARCHAR(50) NOT NULL,
    target_type VARCHAR(50) NOT NULL,
    target_id UUID,
    summary TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_oplog_operator ON operation_log(operator_id);
CREATE INDEX idx_oplog_type ON operation_log(operation_type);
CREATE INDEX idx_oplog_created ON operation_log(created_at);

-- --- Notification ---
CREATE TABLE notification (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    recipient_id UUID NOT NULL REFERENCES "user"(id),
    type VARCHAR(50) NOT NULL,
    title VARCHAR(500) NOT NULL,
    content TEXT,
    link VARCHAR(500),
    related_type VARCHAR(50),
    related_id UUID,
    is_read BOOLEAN NOT NULL DEFAULT false,
    read_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT chk_notification_type CHECK (type IN (
        'APPROVAL_SUBMITTED', 'APPROVAL_APPROVED', 'APPROVAL_REJECTED',
        'APPROVAL_REMINDER', 'PASSWORD_RESET', 'ROLE_CHANGED', 'SYSTEM'
    ))
);

CREATE INDEX idx_notification_recipient_read ON notification(recipient_id, is_read);
CREATE INDEX idx_notification_created ON notification(created_at);

-- --- Seed data: Regions ---
INSERT INTO region (code, name, sort_order) VALUES
    ('SOUTH_CHINA', '华南', 1),
    ('EAST_CHINA', '华东', 2),
    ('NORTH_CHINA', '华北', 3),
    ('CENTRAL_CHINA', '华中', 4);

-- --- Seed data: Departments ---
INSERT INTO department (code, name, sort_order) VALUES
    ('SALES_DEPT_1', '销售一部', 1),
    ('SALES_DEPT_2', '销售二部', 2),
    ('SALES_DEPT_3', '销售三部', 3);

-- --- Seed data: Default system admin ---
-- Password: Admin@2026 (bcrypt hash with 12 rounds)
INSERT INTO "user" (username, full_name, email, password_hash, role, status, is_first_login)
VALUES (
    'admin',
    '系统管理员',
    'admin@cpq-system.com',
    '$2a$12$LJ3m4ys3Gp2v8J5rN3k5aOQZI6x5RmCqGn7B.Y4e2V6dX1wK8S2qC',
    'SYSTEM_ADMIN',
    'ACTIVE',
    true
);
