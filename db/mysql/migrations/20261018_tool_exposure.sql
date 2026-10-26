-- ============================================================
-- 工具规模治理（A/C 方案）存量库迁移脚本
--
-- 新增字段：
--   tool_access_mode    LEGACY / ALL / WHITELIST
--   tool_exposure_mode  DIRECT / META
--   hot_tool_codes      META 模式常驻工具 code（JSON 数组）
--   tool_warn_threshold 工具数告警阈值（NULL=全局默认）
--   tool_max_threshold  工具数硬阈值（NULL=全局默认）
--
-- 兼容策略：
--   存量 server 的 tool_access_mode 迁移为 LEGACY（保持“空白名单=全放行”的旧行为），
--   新建 server 由应用层显式写入 WHITELIST，因此不会出现“上线后应用突然看不到工具”。
--
-- 幂等：通过 information_schema 判断列是否存在，可重复执行。
-- 用法：mysql -u<user> -p mcp_gateway < 20261018_tool_exposure.sql
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
  -- 表不存在（历史表已被后续迁移删除）时直接跳过，保证脚本可在任意存量库重复执行
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

CALL mcp_add_column_if_missing('mcp_server', 'tool_access_mode',
  "VARCHAR(16) NOT NULL DEFAULT 'LEGACY' COMMENT '工具访问模式：LEGACY=存量兼容 ALL=全部启用工具 WHITELIST=仅 app_tool_rel 授权'");
CALL mcp_add_column_if_missing('mcp_server', 'tool_exposure_mode',
  "VARCHAR(16) NOT NULL DEFAULT 'DIRECT' COMMENT '工具暴露模式：DIRECT=全量 tools/list META=元工具+热工具'");
CALL mcp_add_column_if_missing('mcp_server', 'hot_tool_codes',
  "LONGTEXT COMMENT 'META 模式常驻 tools/list 的工具 code（JSON 数组）'");
CALL mcp_add_column_if_missing('mcp_server', 'tool_warn_threshold',
  "INT DEFAULT NULL COMMENT '工具数告警阈值，空则用全局默认'");
CALL mcp_add_column_if_missing('mcp_server', 'tool_max_threshold',
  "INT DEFAULT NULL COMMENT '工具数硬阈值，空则用全局默认'");

DROP PROCEDURE IF EXISTS mcp_add_column_if_missing;
