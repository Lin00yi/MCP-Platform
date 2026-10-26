-- ============================================================
-- Namespace / Endpoint 多租户归属（2026-10-25）
--
--   tenant_id IS NULL   -> 平台级资源：仅平台超管可管理，可聚合多个业务方的 Server
--   tenant_id = N       -> 业务方级资源：该业务方管理员只能管理/绑定本租户的 Server
--
-- 存量数据保持 tenant_id = NULL（平台级），行为不变。
-- 幂等：可重复执行。
-- ============================================================

SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
                    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mcp_namespace' AND COLUMN_NAME = 'tenant_id');
SET @ddl := IF(@col_exists = 0,
  'ALTER TABLE mcp_namespace ADD COLUMN tenant_id INT NULL COMMENT ''归属业务方；NULL=平台级（可跨租户聚合）'' AFTER id',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
                    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mcp_namespace' AND INDEX_NAME = 'idx_namespace_tenant');
SET @ddl := IF(@idx_exists = 0, 'ALTER TABLE mcp_namespace ADD KEY idx_namespace_tenant (tenant_id)', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
                    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mcp_endpoint' AND COLUMN_NAME = 'tenant_id');
SET @ddl := IF(@col_exists = 0,
  'ALTER TABLE mcp_endpoint ADD COLUMN tenant_id INT NULL COMMENT ''归属业务方；NULL=平台级（可跨租户聚合）'' AFTER id',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
                    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mcp_endpoint' AND INDEX_NAME = 'idx_endpoint_tenant');
SET @ddl := IF(@idx_exists = 0, 'ALTER TABLE mcp_endpoint ADD KEY idx_endpoint_tenant (tenant_id)', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
