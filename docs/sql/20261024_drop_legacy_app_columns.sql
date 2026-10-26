-- ============================================================
-- 2026-10-24：清理应用维度遗留列
--
-- 背景：
--   - 审批通过后不再生成「一次性应用密钥」，result_secret 已无代码读写；
--   - app_id 列仅为存量历史数据保留，新数据恒为 NULL，不在此删除（避免历史审计丢失）。
--
-- 执行前请备份；本脚本可重复执行时需先确认列是否存在。
-- ============================================================

-- 仅当 approval_request.result_secret 仍存在时执行
ALTER TABLE approval_request DROP COLUMN result_secret;

-- 可选：确认没有代码再依赖应用维度列（保留数据，仅改注释）
ALTER TABLE approval_request
  MODIFY COLUMN app_id VARCHAR(128) NULL COMMENT '历史字段（应用授权已移除）';
ALTER TABLE tool_call_record
  MODIFY COLUMN app_id VARCHAR(128) NULL COMMENT '历史字段（应用授权已移除）';
