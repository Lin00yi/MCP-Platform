-- ============================================================
-- 访问模式迁移（P1 执行，勿提前跑）
--
-- 前置：
--   1. 已执行并评审 20261021_access_mode_inventory.sql；
--   2. 已按盘点结果把"不该对所有登录用户暴露"的资产置 status = 0；
--   3. 已确认不再需要 app_*_rel 授权（P1 起数据面不再读取）。
--
-- 说明：
--   - P1 只做"访问模式统一为 ALL + 默认值改 ALL"；
--   - app_* 表在 P3 才 DROP；
--   - 本脚本不可逆，按实施步骤不设计回滚。
-- ============================================================

-- 1) 将现有 Server 的访问模式统一为 ALL
UPDATE mcp_server
SET tool_access_mode     = 'ALL',
    prompt_access_mode   = 'ALL',
    resource_access_mode = 'ALL';

-- 2) 修改默认值：新增 Server 不再默认 WHITELIST
ALTER TABLE mcp_server
    ALTER COLUMN tool_access_mode SET DEFAULT 'ALL',
    ALTER COLUMN prompt_access_mode SET DEFAULT 'ALL',
    ALTER COLUMN resource_access_mode SET DEFAULT 'ALL';

-- 3) 迁移后核对：应全部为 ALL
SELECT id, name, tool_access_mode, prompt_access_mode, resource_access_mode
FROM mcp_server
WHERE status = 1
ORDER BY id;
