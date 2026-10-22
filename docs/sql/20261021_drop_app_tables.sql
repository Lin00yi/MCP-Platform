-- ============================================================
-- P5：删除应用管理表（不可逆）
--
-- 前置：
--   1. P3 已下线应用管理 API/UI，P5 已删除审批枚举中的 GRANT_*/APP_* 动作；
--   2. 确认不再需要历史应用审批单/应用授权数据；
--   3. 已备份数据库（按运维要求）。
--
-- 说明：新装环境 init.sql 已不再创建这些表；本脚本用于存量环境。
-- 顺序：先删关联表，再删主表。
-- ============================================================

-- 历史应用维度审批单：动作已从枚举移除，统一置为 CANCELLED，避免 UI/接口无法处理
UPDATE approval_request
SET status = 'CANCELLED'
WHERE action IN (
    'GRANT_SERVER', 'GRANT_SERVER_BATCH', 'GRANT_TOOL', 'GRANT_PROMPT', 'GRANT_RESOURCE',
    'REVOKE_SERVER', 'REVOKE_TOOL', 'REVOKE_PROMPT', 'REVOKE_RESOURCE',
    'APP_CREATE', 'APP_RESET_SECRET', 'APP_DELETE'
);

DROP TABLE IF EXISTS app_endpoint_rel;
DROP TABLE IF EXISTS app_resource_rel;
DROP TABLE IF EXISTS app_prompt_rel;
DROP TABLE IF EXISTS app_tool_rel;
DROP TABLE IF EXISTS app_server_rel;
DROP TABLE IF EXISTS mcp_app_owner;
DROP TABLE IF EXISTS enn_app_info;
