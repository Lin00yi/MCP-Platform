-- ============================================================
-- 访问模式盘点（P0）
--
-- 目的：在删除应用授权之前，记录"原目标应用可见的 Tool/Prompt/Resource 集合"，
--       作为 P1 迁移到 ALL 之后的对照基线。
--
-- 执行时机：P1 上线前，在生产库执行并导出结果（只读，不改数据）。
-- ============================================================

-- 1) 哪些 Server 还是 WHITELIST
SELECT id,
       tenant_id,
       name,
       server_key,
       tool_access_mode,
       prompt_access_mode,
       resource_access_mode
FROM mcp_server
WHERE status = 1
  AND (tool_access_mode = 'WHITELIST'
    OR prompt_access_mode = 'WHITELIST'
    OR resource_access_mode = 'WHITELIST')
ORDER BY id;

-- 2) 每个 Server × 应用：有效访问模式 + 可见 Tool ID 集合
--    有效模式：app_server_rel.tool_access = ALL/WHITELIST 覆盖；INHERIT/NULL 继承 server
SELECT s.id                         AS server_id,
       s.name                       AS server_name,
       r.app_id                     AS app_id,
       r.tool_access                AS app_override,
       s.tool_access_mode           AS server_mode,
       GROUP_CONCAT(DISTINCT CASE
           WHEN r.tool_access = 'ALL' THEN t.id
           WHEN (r.tool_access IS NULL OR r.tool_access = 'INHERIT')
                AND s.tool_access_mode = 'ALL' THEN t.id
           WHEN tr.id IS NOT NULL THEN t.id
       END ORDER BY t.id)           AS visible_tool_ids,
       COUNT(DISTINCT CASE
           WHEN r.tool_access = 'ALL' THEN t.id
           WHEN (r.tool_access IS NULL OR r.tool_access = 'INHERIT')
                AND s.tool_access_mode = 'ALL' THEN t.id
           WHEN tr.id IS NOT NULL THEN t.id
       END)                         AS visible_tool_count,
       COUNT(DISTINCT t.id)         AS enabled_tool_count
FROM mcp_server s
JOIN app_server_rel r ON r.server_id = s.id AND r.status = 1
JOIN mcp_tool t ON t.server_id = s.id AND t.status = 1
LEFT JOIN app_tool_rel tr
       ON tr.app_id = r.app_id AND tr.tool_id = t.id AND tr.status = 1
WHERE s.status = 1
GROUP BY s.id, s.name, r.app_id, r.tool_access, s.tool_access_mode
ORDER BY s.id, r.app_id;

-- 3) 每个 Server × 应用：可见 Prompt ID 集合
SELECT s.id                         AS server_id,
       s.name                       AS server_name,
       r.app_id                     AS app_id,
       s.prompt_access_mode         AS server_mode,
       GROUP_CONCAT(DISTINCT CASE
           WHEN s.prompt_access_mode = 'ALL' THEN p.id
           WHEN pr.id IS NOT NULL THEN p.id
       END ORDER BY p.id)           AS visible_prompt_ids,
       COUNT(DISTINCT CASE
           WHEN s.prompt_access_mode = 'ALL' THEN p.id
           WHEN pr.id IS NOT NULL THEN p.id
       END)                         AS visible_prompt_count
FROM mcp_server s
JOIN app_server_rel r ON r.server_id = s.id AND r.status = 1
JOIN mcp_prompt p ON p.server_id = s.id AND p.status = 1
LEFT JOIN app_prompt_rel pr
       ON pr.app_id = r.app_id AND pr.prompt_id = p.id AND pr.status = 1
WHERE s.status = 1
GROUP BY s.id, s.name, r.app_id, s.prompt_access_mode
ORDER BY s.id, r.app_id;

-- 4) 每个 Server × 应用：可见 Resource ID 集合
SELECT s.id                         AS server_id,
       s.name                       AS server_name,
       r.app_id                     AS app_id,
       s.resource_access_mode       AS server_mode,
       GROUP_CONCAT(DISTINCT CASE
           WHEN s.resource_access_mode = 'ALL' THEN res.id
           WHEN rr.id IS NOT NULL THEN res.id
       END ORDER BY res.id)         AS visible_resource_ids,
       COUNT(DISTINCT CASE
           WHEN s.resource_access_mode = 'ALL' THEN res.id
           WHEN rr.id IS NOT NULL THEN res.id
       END)                         AS visible_resource_count
FROM mcp_server s
JOIN app_server_rel r ON r.server_id = s.id AND r.status = 1
JOIN mcp_resource res ON res.server_id = s.id AND res.status = 1
LEFT JOIN app_resource_rel rr
       ON rr.app_id = r.app_id AND rr.resource_id = res.id AND rr.status = 1
WHERE s.status = 1
GROUP BY s.id, s.name, r.app_id, s.resource_access_mode
ORDER BY s.id, r.app_id;

-- 5) 是否存在"只给某个应用看"的资产（迁移后会对所有登录用户可见）
--    这些行需要在 P1 前决定：保留（status=1）还是停用（status=0）
SELECT s.id AS server_id, s.name AS server_name, t.id AS tool_id, t.code, t.name AS tool_name,
       COUNT(DISTINCT tr.app_id) AS granted_app_count
FROM mcp_tool t
JOIN mcp_server s ON s.id = t.server_id
LEFT JOIN app_tool_rel tr ON tr.tool_id = t.id AND tr.status = 1
WHERE t.status = 1
GROUP BY s.id, s.name, t.id, t.code, t.name
HAVING granted_app_count > 0
ORDER BY s.id, t.id;
