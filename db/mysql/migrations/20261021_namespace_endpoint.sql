-- ============================================================
-- Namespace / Endpoint 访问拓扑
--   Namespace：一组 MCP Server 的逻辑分组，带稳定前缀（工具名 {prefix}__{tool}）
--   Endpoint ：对外接入点 /mcp/e/{endpointKey}，绑定若干 Namespace
--   App 授权：app_endpoint_rel（准入），资产可见性仍按 Server/Tool 授权收窄
-- 幂等：可重复执行。
-- ============================================================

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS mcp_namespace (
  id INT NOT NULL AUTO_INCREMENT COMMENT '主键',
  name VARCHAR(64) NOT NULL COMMENT '展示名',
  prefix VARCHAR(32) NOT NULL COMMENT '工具名前缀（创建后不可变）',
  description VARCHAR(255) DEFAULT NULL COMMENT '说明',
  status TINYINT NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_ns_prefix (prefix)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MCP 命名空间';

CREATE TABLE IF NOT EXISTS mcp_namespace_server (
  id INT NOT NULL AUTO_INCREMENT COMMENT '主键',
  namespace_id INT NOT NULL COMMENT '命名空间ID',
  server_id INT NOT NULL COMMENT 'MCP Server ID',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_ns_server (namespace_id, server_id),
  KEY idx_ns_server_ns (namespace_id),
  KEY idx_ns_server_server (server_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='命名空间-成员Server';

CREATE TABLE IF NOT EXISTS mcp_endpoint (
  id INT NOT NULL AUTO_INCREMENT COMMENT '主键',
  endpoint_key VARCHAR(64) NOT NULL COMMENT '对外路径标识 /mcp/e/{key}',
  name VARCHAR(64) NOT NULL COMMENT '展示名',
  description VARCHAR(255) DEFAULT NULL COMMENT '说明',
  status TINYINT NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_ep_key (endpoint_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MCP 对外接入点';

CREATE TABLE IF NOT EXISTS mcp_endpoint_namespace (
  id INT NOT NULL AUTO_INCREMENT COMMENT '主键',
  endpoint_id INT NOT NULL COMMENT '接入点ID',
  namespace_id INT NOT NULL COMMENT '命名空间ID',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_ep_ns (endpoint_id, namespace_id),
  KEY idx_ep_ns_ep (endpoint_id),
  KEY idx_ep_ns_ns (namespace_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='接入点-命名空间绑定';

CREATE TABLE IF NOT EXISTS app_endpoint_rel (
  id INT NOT NULL AUTO_INCREMENT COMMENT '主键',
  app_id VARCHAR(128) NOT NULL COMMENT '应用ID',
  endpoint_id INT NOT NULL COMMENT '接入点ID',
  status TINYINT NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_app_endpoint (app_id, endpoint_id),
  KEY idx_app_endpoint_app (app_id),
  KEY idx_app_endpoint_ep (endpoint_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='应用-接入点授权';
