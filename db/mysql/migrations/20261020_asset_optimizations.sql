-- ============================================================
-- Prompt / Resource 治理（2026-10-20）
--   1) mcp_server.prompt_access_mode / resource_access_mode
--   2) mcp_prompt / mcp_resource tags / token_estimate / tokenizer
--
-- 存量 Server 的 prompt/resource 模式迁移为 LEGACY（空白名单=全放行），
-- 新 Server 由应用层写 WHITELIST，避免升级后应用突然看不到资产。
-- 幂等：可重复执行。
-- ============================================================

SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS mcp_add_column_if_missing;

DELIMITER $$
CREATE PROCEDURE mcp_add_column_if_missing(
  IN p_table VARCHAR(64),
  IN p_column VARCHAR(64),
  IN p_ddl TEXT
)
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = p_table
      AND COLUMN_NAME = p_column
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN `', p_column, '` ', p_ddl);
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END$$
DELIMITER ;

CALL mcp_add_column_if_missing('mcp_server', 'prompt_access_mode',
  "VARCHAR(16) NOT NULL DEFAULT 'LEGACY' COMMENT 'Prompt 访问模式：LEGACY/WHITELIST'");
CALL mcp_add_column_if_missing('mcp_server', 'resource_access_mode',
  "VARCHAR(16) NOT NULL DEFAULT 'LEGACY' COMMENT 'Resource 访问模式：LEGACY/WHITELIST'");
CALL mcp_add_column_if_missing('mcp_prompt', 'tags',
  "VARCHAR(255) COMMENT '标签（逗号分隔）'");
CALL mcp_add_column_if_missing('mcp_prompt', 'token_estimate',
  "INT DEFAULT NULL COMMENT '预计算 token 估算'");
CALL mcp_add_column_if_missing('mcp_prompt', 'tokenizer',
  "VARCHAR(32) DEFAULT NULL COMMENT 'tokenizer 标识'");
CALL mcp_add_column_if_missing('mcp_resource', 'tags',
  "VARCHAR(255) COMMENT '标签（逗号分隔）'");
CALL mcp_add_column_if_missing('mcp_resource', 'token_estimate',
  "INT DEFAULT NULL COMMENT '预计算 token 估算'");
CALL mcp_add_column_if_missing('mcp_resource', 'tokenizer',
  "VARCHAR(32) DEFAULT NULL COMMENT 'tokenizer 标识'");

DROP PROCEDURE IF EXISTS mcp_add_column_if_missing;
