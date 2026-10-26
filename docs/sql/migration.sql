-- 2026-10 应用管理已移除：本文件中的 app_* / enn_app_info 段落仅保留历史参考，勿在新环境执行。

-- ============================================================
-- MCP 网关多租户改造：完整迁移脚本（可对存量库直接执行，幂等）
-- 用途：把旧模型（tenant_api/method_def/tenant_method_rel/mcp_sys_method_rel）
--       迁移到新模型（tenant/mcp_server/mcp_tool/mcp_prompt/mcp_resource/
--       app_server_rel/app_tool_rel），并补齐默认种子数据。
-- 幂等：所有建表带 IF NOT EXISTS，所有写入用 INSERT IGNORE / 条件插入，可重复执行。
-- 注意：不会删除旧表、不会动 enn_app_info / mcp_request_log / tool_call_record / admin_user 等既有数据。
-- ============================================================

SET NAMES utf8mb4;
USE mcp_gateway;

-- ============================================================
-- 1. 新建表（幂等）
-- ============================================================

CREATE TABLE IF NOT EXISTS tenant (
  id INT NOT NULL AUTO_INCREMENT COMMENT '业务方主键',
  name VARCHAR(128) COMMENT '业务方名称',
  status INT DEFAULT 1 COMMENT '1启用 0禁用',
  create_time DATETIME,
  update_time DATETIME,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS mcp_server (
  id INT NOT NULL AUTO_INCREMENT,
  tenant_id INT NOT NULL COMMENT '归属业务方',
  name VARCHAR(128) COMMENT 'server 名称',
  path VARCHAR(255) COMMENT 'MCP 端点路径（动态注册）',
  status INT DEFAULT 1 COMMENT '1启用(注册) 0禁用(下线)',
  capabilities_json LONGTEXT COMMENT 'initialize 能力声明',
  create_time DATETIME,
  update_time DATETIME,
  PRIMARY KEY (id),
  UNIQUE KEY uk_path (path),
  KEY idx_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS mcp_tool (
  id INT NOT NULL AUTO_INCREMENT COMMENT '主键',
  server_id INT NOT NULL COMMENT '归属 server',
  name VARCHAR(255) COMMENT '接口说明',
  code VARCHAR(255) COMMENT '接口标识（MCP 工具名，唯一）',
  type INT COMMENT '执行器：0本地 1HTTP转发',
  system_id INT NULL COMMENT '关联 service_system（SK_EXCHANGE 兜底 auth_url）',
  status INT DEFAULT 1 COMMENT '1启用 0禁用',
  service_url VARCHAR(500) COMMENT '本地=类名.方法名 远程=URL',
  request_type VARCHAR(10) COMMENT 'GET/POST',
  need_token INT DEFAULT 0 COMMENT '0否 1是（兼容字段）',
  body_type INT DEFAULT 0 COMMENT '0:JSON 1:form',
  auth_type VARCHAR(32) COMMENT '鉴权方式 NONE/SK_EXCHANGE/API_KEY/BEARER',
  auth_config LONGTEXT COMMENT '鉴权配置JSON',
  param_mapping LONGTEXT COMMENT '参数映射JSON（可视化字段映射）',
  res_json LONGTEXT COMMENT '工具描述+inputSchema',
  tags VARCHAR(255) COMMENT '标签（逗号分隔，资产目录检索）',
  version VARCHAR(32) COMMENT '版本号',
  create_time DATETIME,
  update_time DATETIME,
  PRIMARY KEY (id),
  KEY idx_code (code),
  KEY idx_server_id (server_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS mcp_prompt (
  id INT NOT NULL AUTO_INCREMENT,
  server_id INT NOT NULL,
  name VARCHAR(255) COMMENT 'prompt 名称',
  status INT DEFAULT 1,
  res_json LONGTEXT COMMENT 'prompt 描述 + arguments schema',
  version VARCHAR(32) COMMENT '版本号',
  create_time DATETIME,
  update_time DATETIME,
  PRIMARY KEY (id),
  KEY idx_server_id (server_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS mcp_resource (
  id INT NOT NULL AUTO_INCREMENT,
  server_id INT NOT NULL,
  uri VARCHAR(500) COMMENT '资源 URI',
  name VARCHAR(255) COMMENT '资源名称',
  status INT DEFAULT 1,
  res_json LONGTEXT COMMENT '资源描述 + 模板定义',
  version VARCHAR(32) COMMENT '版本号',
  create_time DATETIME,
  update_time DATETIME,
  PRIMARY KEY (id),
  KEY idx_server_id (server_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS app_server_rel (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  app_id VARCHAR(128) NOT NULL COMMENT '应用ID',
  server_id INT NOT NULL COMMENT 'server ID',
  status TINYINT DEFAULT 1 COMMENT '0禁用 1启用',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_app_server (app_id, server_id),
  KEY idx_app_id (app_id),
  KEY idx_server_id (server_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS app_tool_rel (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  app_id VARCHAR(128) NOT NULL COMMENT '应用ID',
  tool_id INT NOT NULL COMMENT '工具 ID',
  status TINYINT DEFAULT 1 COMMENT '0禁用 1启用',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_app_tool (app_id, tool_id),
  KEY idx_app_id (app_id),
  KEY idx_tool_id (tool_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS operation_log (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  username VARCHAR(64) COMMENT '操作者用户名',
  action VARCHAR(32) COMMENT '操作类型',
  resource_type VARCHAR(32) COMMENT '资源类型',
  resource_id VARCHAR(128) COMMENT '资源 ID',
  detail LONGTEXT COMMENT '详情 JSON',
  create_time DATETIME,
  PRIMARY KEY (id),
  KEY idx_create_time (create_time),
  KEY idx_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作审计日志';

CREATE TABLE IF NOT EXISTS grant_request (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  app_id VARCHAR(128) NOT NULL COMMENT '申请授权的应用',
  server_id INT NULL COMMENT '申请的 server（可选）',
  tool_id INT NULL COMMENT '申请的工具（可选）',
  reason VARCHAR(500) COMMENT '申请理由',
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/REJECTED',
  requester VARCHAR(64) COMMENT '申请人',
  approver VARCHAR(64) COMMENT '审批人',
  create_time DATETIME,
  update_time DATETIME,
  PRIMARY KEY (id),
  KEY idx_status (status),
  KEY idx_app_id (app_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='授权申请单';

-- 1.x 存量表补列：mcp_request_log 增加 sk_hash（Authorization 脱敏后按哈希联表）
-- 若列已存在会报错，可忽略；新库由 init.sql 建表时已含此列。
ALTER TABLE mcp_request_log ADD COLUMN sk_hash VARCHAR(64) COMMENT 'Authorization 中 sk 的 SHA-256 哈希' AFTER authorization;

-- 1.y 存量表补列：mcp_tool 增加资产目录元数据（tags/version）
ALTER TABLE mcp_tool ADD COLUMN tags VARCHAR(255) COMMENT '标签（逗号分隔）' AFTER res_json;
ALTER TABLE mcp_tool ADD COLUMN version VARCHAR(32) COMMENT '版本号' AFTER tags;

-- 1.y2 存量表清理：mcp_tool 移除 owner（团队归属由 server→tenant 层级决定）
ALTER TABLE mcp_tool DROP COLUMN owner;

-- ============================================================
-- 2. 旧表数据迁移（幂等，旧表为空则自然跳过）
--    仅当旧表存在时执行；用 INSERT IGNORE 避免重复
-- ============================================================

-- 2.1 tenant_api → tenant + mcp_server（1:1 拆分）
INSERT IGNORE INTO tenant (id, name, status, create_time, update_time)
SELECT id, name, status, create_time, update_time FROM tenant_api;

INSERT IGNORE INTO mcp_server (id, tenant_id, name, path, status, capabilities_json, create_time, update_time)
SELECT id, id, CONCAT(name, '-core'), path, status, res_json, create_time, update_time
FROM tenant_api;

-- 2.2 method_def → mcp_tool（tenant_id 即 server_id，因旧模型 1:1）
-- system_id 映射：type=1(HTTP转发) 默认映射到 1（示例），请按 auth_url 人工复核修正
INSERT IGNORE INTO mcp_tool (id, server_id, name, code, type, system_id, status, service_url, request_type,
                      need_token, body_type, auth_type, auth_config, param_mapping, res_json, create_time, update_time)
SELECT id, tenant_id, name, code, type,
       CASE WHEN type = 1 THEN 1 ELSE NULL END,
       status, service_url, request_type, need_token, body_type,
       auth_type, auth_config, param_mapping, res_json, create_time, update_time
FROM method_def;

-- 2.3 mcp_sys_method_rel → 级联授权（tool 授权依赖 server 授权）
-- 2.3a 通配 "*" → server 级授权（需人工确认应用对应哪个业务方的 server）
INSERT IGNORE INTO app_server_rel (app_id, server_id, status)
SELECT r.app_id, s.id, r.status
FROM mcp_sys_method_rel r
JOIN mcp_server s ON s.tenant_id = (SELECT id FROM tenant WHERE name = r.app_id)
WHERE r.method_id = '*'
  AND r.app_id IN (SELECT name FROM tenant);

-- 2.3b 具体 method_id → 先补 server 级授权（级联），再补 tool 级白名单
INSERT IGNORE INTO app_server_rel (app_id, server_id, status)
SELECT r.app_id, t.server_id, r.status
FROM mcp_sys_method_rel r
JOIN mcp_tool t ON t.id = CAST(r.method_id AS UNSIGNED)
WHERE r.method_id <> '*';

INSERT IGNORE INTO app_tool_rel (app_id, tool_id, status)
SELECT app_id, CAST(method_id AS UNSIGNED), status
FROM mcp_sys_method_rel
WHERE method_id <> '*';

-- ============================================================
-- 3. 种子说明：不再预置演示数据（业务方/Server/工具/授权/系统服务请通过控制台配置）
--    本脚本只做「建表 + 旧表数据迁移」，迁移完成后请到控制台补充业务配置。
-- ============================================================

-- ============================================================
-- 4. 清理旧表（确认无误后手工执行，本脚本默认不删除）
-- ============================================================
-- DROP TABLE IF EXISTS tenant_method_rel;
-- DROP TABLE IF EXISTS mcp_sys_method_rel;
-- DROP TABLE IF EXISTS method_def;
-- DROP TABLE IF EXISTS tenant_api;

-- 1.z 存量表补列：mcp_prompt / mcp_resource 增加 version
ALTER TABLE mcp_prompt ADD COLUMN version VARCHAR(32) COMMENT '版本号' AFTER res_json;
ALTER TABLE mcp_resource ADD COLUMN version VARCHAR(32) COMMENT '版本号' AFTER res_json;

-- 1.w 存量表补列：tool_call_record 增加 server 归属
ALTER TABLE tool_call_record ADD COLUMN server_id INT COMMENT 'Server ID' AFTER tenant_name;
ALTER TABLE tool_call_record ADD COLUMN server_name VARCHAR(128) COMMENT 'Server 名称' AFTER server_id;

-- 2.0 清理：日志已切换到 tool_call_record，移除原 CRM 遗留的员工信息关联表
DROP TABLE IF EXISTS Kn_organization;
DROP TABLE IF EXISTS kn_employee;
DROP TABLE IF EXISTS sk_auth;

-- 2.1 架构解耦：mcp_server 增加上游类型（支持 MCP→MCP 代理转发）
ALTER TABLE mcp_server
  ADD COLUMN upstream_type VARCHAR(16) DEFAULT 'NONE' COMMENT '上游类型：NONE=本地/REST工具，MCP=代理到下游真实MCP server',
  ADD COLUMN upstream_url VARCHAR(500) COMMENT '下游 MCP server 的 Streamable HTTP 地址（upstream_type=MCP 时）';

-- 2.2 应用授权：补齐 Prompt / Resource 细粒度授权表
CREATE TABLE IF NOT EXISTS app_prompt_rel (
  id BIGINT NOT NULL AUTO_INCREMENT,
  app_id VARCHAR(128) NOT NULL,
  prompt_id INT NOT NULL,
  status TINYINT DEFAULT 1,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_app_prompt (app_id, prompt_id),
  KEY idx_app_id (app_id),
  KEY idx_prompt_id (prompt_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS app_resource_rel (
  id BIGINT NOT NULL AUTO_INCREMENT,
  app_id VARCHAR(128) NOT NULL,
  resource_id INT NOT NULL,
  status TINYINT DEFAULT 1,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_app_resource (app_id, resource_id),
  KEY idx_app_id (app_id),
  KEY idx_resource_id (resource_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============================================================
-- 5. 安全整改（2026-10）：审计/审批租户隔离 + 查询索引
--    注意：MySQL 8.0 不支持 ADD COLUMN IF NOT EXISTS，
--    以下语句请在存量库执行前先确认列不存在（或分批手工执行）。
-- ============================================================

-- 5.1 操作审计日志归属租户
ALTER TABLE operation_log ADD COLUMN tenant_id INT COMMENT '归属业务方（租户隔离用）' AFTER username;
CREATE INDEX idx_operation_log_tenant ON operation_log (tenant_id);

-- 5.2 授权申请归属租户
ALTER TABLE grant_request ADD COLUMN tenant_id INT COMMENT '归属业务方（租户隔离用）' AFTER id;
CREATE INDEX idx_grant_request_tenant ON grant_request (tenant_id);

-- 5.3 调用记录按租户/Server 查询索引
CREATE INDEX idx_tool_call_tenant_time ON tool_call_record (tenant_id, create_time);
CREATE INDEX idx_tool_call_server_time ON tool_call_record (server_id, create_time);

-- 5.4 应用 ID 唯一化（执行前先确认无重复：SELECT app_id, COUNT(*) FROM enn_app_info GROUP BY app_id HAVING COUNT(*) > 1）
-- ALTER TABLE enn_app_info ADD UNIQUE KEY uk_app_id (app_id);

-- 5.5 清理历史默认账号（确认已创建新的管理员后再执行）
-- DELETE FROM admin_user WHERE username = 'zhangsan';
-- UPDATE admin_user SET password = '<新的 BCrypt hash>' WHERE username = 'admin';

-- ============================================================
-- 6. 身份下传（2026-10，方案 A）：应用归属 + 下游 audience/公钥
-- ============================================================

-- 6.1 应用归属表（网关侧扩展，避免改 share 的 enn_app_info）
CREATE TABLE IF NOT EXISTS mcp_app_owner (
  app_id VARCHAR(128) NOT NULL COMMENT '应用ID（enn_app_info.app_id）',
  tenant_id INT NOT NULL COMMENT '归属业务方',
  created_by BIGINT COMMENT '创建人 admin_user.id',
  create_time DATETIME,
  update_time DATETIME,
  PRIMARY KEY (app_id),
  KEY idx_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='应用归属';

-- 6.2 存量回填：按已有 server 授权推导；冲突的 app 需人工确认
INSERT IGNORE INTO mcp_app_owner (app_id, tenant_id, create_time, update_time)
SELECT r.app_id, MIN(s.tenant_id), NOW(), NOW()
FROM app_server_rel r
JOIN mcp_server s ON s.id = r.server_id
GROUP BY r.app_id;

-- 冲突检查：一个 app 授权到多个租户
-- SELECT r.app_id, GROUP_CONCAT(DISTINCT s.tenant_id)
-- FROM app_server_rel r JOIN mcp_server s ON s.id = r.server_id
-- GROUP BY r.app_id HAVING COUNT(DISTINCT s.tenant_id) > 1;

-- 6.3 下游内部 JWT 配置（JWE 公钥为 PEM；执行前先确认列不存在）
ALTER TABLE mcp_server
  ADD COLUMN audience VARCHAR(128) COMMENT '内部 JWT aud，默认 server-{id}',
  ADD COLUMN identity_public_key LONGTEXT COMMENT '下游 JWE 公钥（PEM/JWK）',
  ADD COLUMN identity_mode VARCHAR(16) NOT NULL DEFAULT 'INHERIT' COMMENT '身份下传覆盖：INHERIT/OFF/OPTIONAL/REQUIRED';


-- ============================================================
-- 7. DSH 同步：server 稳定标识
-- ============================================================

-- 7.1 新增 server_key（可空，创建后由应用回填；唯一索引允许多个 NULL）
ALTER TABLE mcp_server ADD COLUMN server_key VARCHAR(32) NULL COMMENT '稳定机器标识（DSH serverName，创建后不可变）';

-- 7.2 存量回填：name 转 slug（小写、非法字符转 -）
UPDATE mcp_server
SET server_key = TRIM(BOTH '-' FROM LOWER(REGEXP_REPLACE(name, '[^A-Za-z0-9_-]+', '-')))
WHERE server_key IS NULL AND name IS NOT NULL AND name <> '';

UPDATE mcp_server
SET server_key = CONCAT('s', id)
WHERE server_key IS NULL OR server_key = '';

-- 7.3 截断 + 冲突追加 id
UPDATE mcp_server SET server_key = LEFT(server_key, 27) WHERE CHAR_LENGTH(server_key) > 27;

UPDATE mcp_server s
JOIN (
  SELECT id, CONCAT(LEFT(server_key, 27), '-', id) AS new_key
  FROM (
    SELECT id, server_key, ROW_NUMBER() OVER (PARTITION BY server_key ORDER BY id) AS rn
    FROM mcp_server
    WHERE server_key IS NOT NULL
  ) x
  WHERE x.rn > 1
) d ON s.id = d.id
SET s.server_key = d.new_key;

CREATE UNIQUE INDEX uk_mcp_server_key ON mcp_server (server_key);

-- ============================================================
-- 8. 调用日志：记录客户端使用的用户（2026-10）
--    注意：MySQL 8.0 不支持 ADD COLUMN IF NOT EXISTS，
--    以下语句请在存量库执行前先确认列不存在（或分批手工执行）。
-- ============================================================

-- 8.1 工具调用记录增加调用方应用与调用用户
ALTER TABLE tool_call_record ADD COLUMN app_id VARCHAR(128) COMMENT '调用方应用ID（token iss）' AFTER server_name;
ALTER TABLE tool_call_record ADD COLUMN user_id VARCHAR(255) COMMENT '调用用户ID（客户端身份）' AFTER app_id;
ALTER TABLE tool_call_record ADD COLUMN user_name VARCHAR(128) COMMENT '调用用户名称' AFTER user_id;

-- ============================================================
-- 9. 通用审批流（2026-10）：grant_request → approval_request
--    存量库按顺序执行；新库由 init.sql 直接创建 approval_request，跳过 9.1。
-- ============================================================

-- 9.1 重命名旧表（仅存量库；新库已有 approval_request 时自动跳过）
SET @has_grant := (SELECT COUNT(*) FROM information_schema.tables
                   WHERE table_schema = DATABASE() AND table_name = 'grant_request');
SET @has_approval := (SELECT COUNT(*) FROM information_schema.tables
                      WHERE table_schema = DATABASE() AND table_name = 'approval_request');
SET @rename_sql := IF(@has_grant = 1 AND @has_approval = 0,
                      'RENAME TABLE grant_request TO approval_request',
                      'DO 0');
PREPARE rename_stmt FROM @rename_sql;
EXECUTE rename_stmt;
DEALLOCATE PREPARE rename_stmt;

-- 9.2 扩展为通用审批单
ALTER TABLE approval_request
  ADD COLUMN action VARCHAR(48) NULL COMMENT '审批动作（ApprovalAction）' AFTER tenant_id,
  ADD COLUMN prompt_id INT NULL COMMENT '目标 prompt',
  ADD COLUMN resource_id INT NULL COMMENT '目标 resource',
  ADD COLUMN user_id BIGINT NULL COMMENT '目标用户（角色变更）',
  ADD COLUMN payload MEDIUMTEXT COMMENT '执行快照 JSON（落库前加密）',
  ADD COLUMN requester_role VARCHAR(32) NULL COMMENT '申请人角色',
  ADD COLUMN approver_role VARCHAR(32) NULL COMMENT '审批人角色',
  ADD COLUMN comment VARCHAR(500) NULL COMMENT '审批意见';

-- 9.3 回填历史数据的 action，并收紧非空约束
UPDATE approval_request
SET action = CASE WHEN server_id IS NOT NULL THEN 'GRANT_SERVER' ELSE 'GRANT_TOOL' END
WHERE action IS NULL;

ALTER TABLE approval_request MODIFY action VARCHAR(48) NOT NULL COMMENT '审批动作（ApprovalAction）';
-- 新流程允许 Server 创建等无 app 的申请
ALTER TABLE approval_request MODIFY app_id VARCHAR(128) NULL COMMENT '目标应用';

-- 9.4 回填历史审批单的 tenant_id（早期数据可能为空，非超管将无法看到/审批）
UPDATE approval_request r
JOIN mcp_app_owner o ON o.app_id = r.app_id
SET r.tenant_id = o.tenant_id
WHERE r.tenant_id IS NULL AND o.tenant_id IS NOT NULL;

UPDATE approval_request r
JOIN mcp_server s ON s.id = r.server_id
SET r.tenant_id = s.tenant_id
WHERE r.tenant_id IS NULL AND r.server_id IS NOT NULL;

UPDATE approval_request r
JOIN mcp_tool t ON t.id = r.tool_id
JOIN mcp_server s ON s.id = t.server_id
SET r.tenant_id = s.tenant_id
WHERE r.tenant_id IS NULL AND r.tool_id IS NOT NULL;

-- 9.5 索引
CREATE INDEX idx_approval_status ON approval_request (status);
CREATE INDEX idx_approval_requester ON approval_request (requester);
CREATE INDEX idx_approval_app ON approval_request (app_id);
CREATE INDEX idx_approval_server ON approval_request (server_id);
CREATE INDEX idx_approval_tool ON approval_request (tool_id);

-- ============================================================
-- 10. 工具规模治理（2026-10）：访问模式 / 暴露模式 / 热工具 / 阈值
--     访问模式统一为 WHITELIST / ALL（Tool / Prompt / Resource 同一枚举）；
--     默认 WHITELIST，脚本幂等，可重复执行。
-- ============================================================
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

-- 2026-10-20 Prompt / Resource 治理
-- 完整迁移见：db/mysql/migrations/20261020_asset_optimizations.sql
-- 新增：mcp_server.prompt_access_mode / resource_access_mode
--       mcp_prompt / mcp_resource.tags / token_estimate / tokenizer
