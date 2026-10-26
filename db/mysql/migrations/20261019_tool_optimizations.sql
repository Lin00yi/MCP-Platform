-- ============================================================
-- 工具链路优化（2026-10-19）
--   1) mcp_tool_embedding：语义检索向量
--   2) app_server_rel.tool_access / profile_tags：应用级覆盖与工具画像
--   3) mcp_tool.token_estimate / tokenizer：token 预算预计算
-- 幂等：可重复执行。
-- ============================================================

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS mcp_tool_embedding (
  tool_id INT NOT NULL COMMENT 'mcp_tool.id',
  server_id INT NOT NULL COMMENT '冗余 server，便于按 server 加载',
  model VARCHAR(64) COMMENT 'embedding 模型',
  dim INT COMMENT '向量维度',
  vector LONGTEXT COMMENT 'JSON 数组',
  update_time DATETIME,
  PRIMARY KEY (tool_id),
  KEY idx_server_id (server_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

DROP PROCEDURE IF EXISTS mcp_add_column_if_missing;

DELIMITER $$
CREATE PROCEDURE mcp_add_column_if_missing(
  IN p_table VARCHAR(64),
  IN p_column VARCHAR(64),
  IN p_ddl TEXT
)
BEGIN
  -- 表不存在（如 app_server_rel 已被后续迁移删除）时直接跳过，保证脚本可在任意存量库重复执行
  IF EXISTS (
    SELECT 1 FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = p_table
  ) AND NOT EXISTS (
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

CALL mcp_add_column_if_missing('app_server_rel', 'tool_access',
  "VARCHAR(16) NOT NULL DEFAULT 'INHERIT' COMMENT '应用级覆盖：INHERIT/ALL/WHITELIST'");
CALL mcp_add_column_if_missing('app_server_rel', 'profile_tags',
  "VARCHAR(512) COMMENT '应用级工具画像标签，逗号分隔'");
CALL mcp_add_column_if_missing('mcp_tool', 'token_estimate',
  "INT DEFAULT NULL COMMENT '预计算 token 估算'");
CALL mcp_add_column_if_missing('mcp_tool', 'tokenizer',
  "VARCHAR(32) DEFAULT NULL COMMENT 'tokenizer 标识'");

DROP PROCEDURE IF EXISTS mcp_add_column_if_missing;
