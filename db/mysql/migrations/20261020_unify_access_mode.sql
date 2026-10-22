-- ============================================================
-- 统一访问模式：移除 LEGACY
--   Tool / Prompt / Resource 共用同一套访问模式枚举：
--     WHITELIST = 仅 app_*_rel 显式授权的资产可见
--     ALL       = Server 下全部启用资产可见
--   存量 LEGACY（以及空值/非法值）一律归一为 WHITELIST（fail-closed）。
-- ============================================================

UPDATE mcp_server
   SET tool_access_mode = 'WHITELIST'
 WHERE tool_access_mode IS NULL OR tool_access_mode NOT IN ('WHITELIST', 'ALL');

UPDATE mcp_server
   SET prompt_access_mode = 'WHITELIST'
 WHERE prompt_access_mode IS NULL OR prompt_access_mode NOT IN ('WHITELIST', 'ALL');

UPDATE mcp_server
   SET resource_access_mode = 'WHITELIST'
 WHERE resource_access_mode IS NULL OR resource_access_mode NOT IN ('WHITELIST', 'ALL');

ALTER TABLE mcp_server
  MODIFY COLUMN tool_access_mode VARCHAR(16) NOT NULL DEFAULT 'WHITELIST'
    COMMENT 'Tool 访问模式：WHITELIST=仅 app_tool_rel 授权 ALL=全部启用 Tool',
  MODIFY COLUMN prompt_access_mode VARCHAR(16) NOT NULL DEFAULT 'WHITELIST'
    COMMENT 'Prompt 访问模式：WHITELIST=仅 app_prompt_rel 授权 ALL=全部启用 Prompt',
  MODIFY COLUMN resource_access_mode VARCHAR(16) NOT NULL DEFAULT 'WHITELIST'
    COMMENT 'Resource 访问模式：WHITELIST=仅 app_resource_rel 授权 ALL=全部启用 Resource';
