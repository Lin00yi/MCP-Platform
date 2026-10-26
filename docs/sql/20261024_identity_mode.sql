-- ============================================================
-- 2026-10-24：身份下传灰度模式
--
-- 适用：已执行过 migration.sql 6.3（已有 audience / identity_public_key）的存量库。
-- 新装环境用 db/mysql/init.sql 即可，无需本脚本。
--
-- 说明：
--   INHERIT  沿用全局 mcp.identity.mode（默认）
--   OFF      该 Server 不下发内部断言
--   OPTIONAL 能签发就签发，缺客编/密钥时告警并继续
--   REQUIRED 必须签发，否则拒绝请求（-32003）
-- ============================================================

-- 若该列已存在（曾执行过旧版 migration 6.3），跳过本句，直接执行下面的 UPDATE。
ALTER TABLE mcp_server
  ADD COLUMN identity_mode VARCHAR(16) NOT NULL DEFAULT 'INHERIT'
  COMMENT '身份下传覆盖：INHERIT/OFF/OPTIONAL/REQUIRED';

-- 曾经存在过一版未使用的 identity_mode（默认 OPTIONAL），统一收敛为 INHERIT，
-- 避免全局改为 REQUIRED 时被历史默认值意外降级。
UPDATE mcp_server SET identity_mode = 'INHERIT' WHERE identity_mode IS NULL OR identity_mode = '';
