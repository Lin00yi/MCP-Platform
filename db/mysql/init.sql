-- ============================================================
-- MCP Gateway 数据库初始化脚本（MySQL 8，手工执行：mysql -uroot -p < db/mysql/init.sql）
-- 多租户模型：业务方 tenant → MCP Server(s) → tool/prompt/resource
-- 新装环境执行本脚本；存量环境执行 docs/sql/migration.sql + db/mysql/migrations/*.sql
--
-- 说明：本脚本只建表 + 初始化后台管理员账号。
-- 业务方 / MCP Server / 工具 / 应用授权等运行时数据，请通过控制台配置。
-- ============================================================

-- 关键：强制客户端连接使用 utf8mb4，避免中文导入时被按 latin1 解释而乱码
SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS mcp_gateway DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE mcp_gateway;

-- ---------- 业务方（租户） ----------
CREATE TABLE IF NOT EXISTS tenant (
  id INT NOT NULL AUTO_INCREMENT COMMENT '业务方主键',
  name VARCHAR(128) COMMENT '业务方名称',
  status INT DEFAULT 1 COMMENT '1启用 0禁用',
  create_time DATETIME,
  update_time DATETIME,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------- MCP Server（归属业务方，N:1） ----------
CREATE TABLE IF NOT EXISTS mcp_server (
  id INT NOT NULL AUTO_INCREMENT,
  tenant_id INT NOT NULL COMMENT '归属业务方',
  name VARCHAR(128) COMMENT 'server 名称',
  server_key VARCHAR(32) COMMENT '稳定机器标识（DSH serverName，创建后不可变）',
  path VARCHAR(255) COMMENT 'MCP 端点路径（动态注册）',
  status INT DEFAULT 1 COMMENT '1启用(注册) 0禁用(下线)',
  capabilities_json LONGTEXT COMMENT 'initialize 能力声明',
  upstream_type VARCHAR(16) DEFAULT 'NONE' COMMENT '上游类型：NONE=本地/REST工具，MCP=代理到下游真实MCP server',
  upstream_url VARCHAR(500) COMMENT '下游 MCP server 的 Streamable HTTP 地址（upstream_type=MCP 时）',
  audience VARCHAR(128) COMMENT '内部 JWT aud，默认 server-{id}',
  identity_public_key LONGTEXT COMMENT '下游 JWE 公钥（PEM/JWK）',
  identity_mode VARCHAR(16) NOT NULL DEFAULT 'INHERIT' COMMENT '身份下传覆盖：INHERIT/OFF/OPTIONAL/REQUIRED',
  tool_access_mode VARCHAR(16) NOT NULL DEFAULT 'ALL' COMMENT 'Tool 访问模式：ALL=全部启用 Tool（WHITELIST 已废弃）',
  prompt_access_mode VARCHAR(16) NOT NULL DEFAULT 'ALL' COMMENT 'Prompt 访问模式：ALL（WHITELIST 已废弃）',
  resource_access_mode VARCHAR(16) NOT NULL DEFAULT 'ALL' COMMENT 'Resource 访问模式：ALL（WHITELIST 已废弃）',
  tool_exposure_mode VARCHAR(16) NOT NULL DEFAULT 'DIRECT' COMMENT '工具暴露模式：DIRECT=全量 tools/list META=元工具(search/get_tool_schema/call_tool)+热工具',
  hot_tool_codes LONGTEXT COMMENT 'META 模式常驻 tools/list 的工具 code（JSON 数组）',
  tool_warn_threshold INT DEFAULT NULL COMMENT '工具数告警阈值，空则用全局默认',
  tool_max_threshold INT DEFAULT NULL COMMENT '工具数硬阈值，空则用全局默认',
  create_time DATETIME,
  update_time DATETIME,
  PRIMARY KEY (id),
  UNIQUE KEY uk_path (path),
  UNIQUE KEY uk_server_key (server_key),
  KEY idx_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------- 工具（归属 server） ----------
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
  token_estimate INT DEFAULT NULL COMMENT '预计算 token 估算',
  tokenizer VARCHAR(32) DEFAULT NULL COMMENT 'tokenizer 标识',
  create_time DATETIME,
  update_time DATETIME,
  PRIMARY KEY (id),
  KEY idx_code (code),
  KEY idx_server_id (server_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------- 工具描述向量（语义检索，可选） ----------
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

-- ---------- Prompt（归属 server） ----------
CREATE TABLE IF NOT EXISTS mcp_prompt (
  id INT NOT NULL AUTO_INCREMENT,
  server_id INT NOT NULL,
  name VARCHAR(255) COMMENT 'prompt 名称',
  status INT DEFAULT 1,
  res_json LONGTEXT COMMENT 'prompt 描述 + arguments schema',
  tags VARCHAR(255) COMMENT '标签（逗号分隔）',
  version VARCHAR(32) COMMENT '版本号',
  token_estimate INT DEFAULT NULL COMMENT '预计算 token 估算',
  tokenizer VARCHAR(32) DEFAULT NULL COMMENT 'tokenizer 标识',
  create_time DATETIME,
  update_time DATETIME,
  PRIMARY KEY (id),
  KEY idx_server_id (server_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------- Resource（归属 server） ----------
CREATE TABLE IF NOT EXISTS mcp_resource (
  id INT NOT NULL AUTO_INCREMENT,
  server_id INT NOT NULL,
  uri VARCHAR(500) COMMENT '资源 URI',
  name VARCHAR(255) COMMENT '资源名称',
  status INT DEFAULT 1,
  res_json LONGTEXT COMMENT '资源描述 + 模板定义',
  tags VARCHAR(255) COMMENT '标签（逗号分隔）',
  version VARCHAR(32) COMMENT '版本号',
  token_estimate INT DEFAULT NULL COMMENT '预计算 token 估算',
  tokenizer VARCHAR(32) DEFAULT NULL COMMENT 'tokenizer 标识',
  create_time DATETIME,
  update_time DATETIME,
  PRIMARY KEY (id),
  KEY idx_server_id (server_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------- 系统服务 ----------
CREATE TABLE IF NOT EXISTS service_system (
  id INT NOT NULL AUTO_INCREMENT COMMENT '系统ID（mcp_tool.system_id 关联）',
  name VARCHAR(128) COMMENT '系统名称',
  auth_url VARCHAR(500) COMMENT 'sk 换 token 的授权地址',
  status INT DEFAULT 1 COMMENT '1启用 0禁用',
  create_time DATETIME,
  update_time DATETIME,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------- 工具调用记录 ----------
CREATE TABLE IF NOT EXISTS tool_call_record (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id INT,
  tenant_name VARCHAR(128),
  server_id INT COMMENT 'Server ID（mcp_server.id）',
  server_name VARCHAR(128) COMMENT 'Server 名称',
  app_id VARCHAR(128) COMMENT '调用方应用ID（token iss）',
  user_id VARCHAR(255) COMMENT '调用用户ID（客户端身份）',
  user_name VARCHAR(128) COMMENT '调用用户名称',
  method_def_id INT COMMENT '工具ID（mcp_tool.id）',
  method_code VARCHAR(255),
  method_name VARCHAR(255),
  request_args LONGTEXT,
  response_result LONGTEXT,
  call_type VARCHAR(20) COMMENT 'LOCAL/EXTERNAL',
  http_method VARCHAR(10),
  target_url VARCHAR(500),
  success INT DEFAULT 1 COMMENT '1成功 0失败',
  error_msg LONGTEXT,
  duration BIGINT COMMENT '耗时(ms)',
  create_time DATETIME,
  PRIMARY KEY (id),
  KEY idx_create_time (create_time),
  KEY idx_tenant_time (tenant_id, create_time),
  KEY idx_server_time (server_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------- 请求日志 ----------
CREATE TABLE IF NOT EXISTS mcp_request_log (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  mcp_method VARCHAR(255) COMMENT 'MCP 方法名',
  method_name VARCHAR(255) COMMENT '实际调用的方法（tools/call 时有值）',
  authorization LONGTEXT COMMENT 'Authorization 请求头（脱敏后）',
  sk_hash VARCHAR(64) COMMENT 'Authorization 中 sk 的 SHA-256 哈希（联表用，避免明文落库）',
  user_id VARCHAR(255),
  tenant_id INT COMMENT '业务方(租户)ID',
  request_params LONGTEXT,
  response_result LONGTEXT,
  create_time DATETIME,
  PRIMARY KEY (id),
  KEY idx_create_time (create_time),
  KEY idx_tenant_id (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------- 操作审计日志 ----------
CREATE TABLE IF NOT EXISTS operation_log (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  username VARCHAR(64) COMMENT '操作者用户名',
  tenant_id INT COMMENT '归属业务方（租户隔离用）',
  action VARCHAR(32) COMMENT '操作类型 CREATE/UPDATE/DELETE/GRANT/REVOKE/APPROVE/REJECT/REFRESH',
  resource_type VARCHAR(32) COMMENT '资源类型 TENANT/SERVER/TOOL/PROMPT/RESOURCE/APP/SERVER_REL/TOOL_REL/GRANT_REQUEST',
  resource_id VARCHAR(128) COMMENT '资源 ID',
  detail LONGTEXT COMMENT '详情 JSON',
  create_time DATETIME,
  PRIMARY KEY (id),
  KEY idx_create_time (create_time),
  KEY idx_username (username),
  KEY idx_tenant_id (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作审计日志';

-- ---------- 通用审批申请单（审批流） ----------
CREATE TABLE IF NOT EXISTS approval_request (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id INT COMMENT '归属业务方（租户隔离用）',
  action VARCHAR(48) NOT NULL COMMENT '审批动作（ApprovalAction）',
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/REJECTED/CANCELLED',
  app_id VARCHAR(128) NULL COMMENT '历史字段（应用授权已移除，仅存量数据）',
  server_id INT NULL COMMENT '目标 server',
  tool_id INT NULL COMMENT '目标工具',
  prompt_id INT NULL COMMENT '目标 prompt',
  resource_id INT NULL COMMENT '目标 resource',
  user_id BIGINT NULL COMMENT '目标用户（角色变更）',
  payload MEDIUMTEXT COMMENT '执行快照 JSON（落库前加密）',
  reason VARCHAR(500) COMMENT '申请理由',
  requester VARCHAR(64) COMMENT '申请人',
  requester_role VARCHAR(32) COMMENT '申请人角色',
  approver VARCHAR(64) COMMENT '审批人',
  approver_role VARCHAR(32) COMMENT '审批人角色',
  comment VARCHAR(500) COMMENT '审批意见',
  create_time DATETIME,
  update_time DATETIME,
  PRIMARY KEY (id),
  KEY idx_approval_status (status),
  KEY idx_approval_tenant (tenant_id),
  KEY idx_approval_requester (requester),
  KEY idx_approval_app (app_id),
  KEY idx_approval_server (server_id),
  KEY idx_approval_tool (tool_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通用审批申请单';

-- ============================================================
-- 后台管理用户与角色（多租户 RBAC）
-- ============================================================

CREATE TABLE IF NOT EXISTS admin_user (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  username VARCHAR(64) NOT NULL COMMENT '登录名',
  password VARCHAR(128) NOT NULL COMMENT 'BCrypt 密码',
  name VARCHAR(64) COMMENT '显示名',
  role VARCHAR(32) NOT NULL COMMENT 'SUPER_ADMIN/ADMIN/DEVELOPER/OPERATOR/USER',
  status TINYINT DEFAULT 1 COMMENT '1启用 0禁用',
  create_time DATETIME,
  update_time DATETIME,
  PRIMARY KEY (id),
  UNIQUE KEY uk_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='后台管理用户';

CREATE TABLE IF NOT EXISTS admin_user_tenant (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  user_id BIGINT NOT NULL COMMENT '用户ID',
  tenant_id INT NOT NULL COMMENT '业务方(租户)ID',
  create_time DATETIME,
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_tenant (user_id, tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户-业务方关联';

-- ---------- Namespace / Endpoint 访问拓扑 ----------
CREATE TABLE IF NOT EXISTS mcp_namespace (
  id INT NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id INT NULL COMMENT '归属业务方；NULL=平台级（可跨租户聚合）',
  name VARCHAR(64) NOT NULL COMMENT '展示名',
  prefix VARCHAR(32) NOT NULL COMMENT '工具名前缀（创建后不可变）',
  description VARCHAR(255) DEFAULT NULL,
  status TINYINT NOT NULL DEFAULT 1,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_ns_prefix (prefix),
  KEY idx_namespace_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MCP 命名空间';

CREATE TABLE IF NOT EXISTS mcp_namespace_server (
  id INT NOT NULL AUTO_INCREMENT,
  namespace_id INT NOT NULL,
  server_id INT NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_ns_server (namespace_id, server_id),
  KEY idx_ns_server_ns (namespace_id),
  KEY idx_ns_server_server (server_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='命名空间-成员Server';

CREATE TABLE IF NOT EXISTS mcp_endpoint (
  id INT NOT NULL AUTO_INCREMENT,
  tenant_id INT NULL COMMENT '归属业务方；NULL=平台级（可跨租户聚合）',
  endpoint_key VARCHAR(64) NOT NULL COMMENT '对外路径 /mcp/e/{key}',
  name VARCHAR(64) NOT NULL,
  description VARCHAR(255) DEFAULT NULL,
  status TINYINT NOT NULL DEFAULT 1,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_ep_key (endpoint_key),
  KEY idx_endpoint_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MCP 对外接入点';

CREATE TABLE IF NOT EXISTS mcp_endpoint_namespace (
  id INT NOT NULL AUTO_INCREMENT,
  endpoint_id INT NOT NULL,
  namespace_id INT NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_ep_ns (endpoint_id, namespace_id),
  KEY idx_ep_ns_ep (endpoint_id),
  KEY idx_ep_ns_ns (namespace_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='接入点-命名空间绑定';
-- ============================================================
-- 说明：不再预置默认管理员账号。
-- 首次启动由 AdminBootstrap 根据环境变量 MCP_ADMIN_INIT_PASSWORD 创建 admin，
-- 未配置时（仅限非生产）随机生成并打印到启动日志。
-- ============================================================
