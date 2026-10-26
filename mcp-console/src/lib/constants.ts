/** 密码规则常量：与后端 AdminAuthServiceImpl.MIN_PASSWORD_LENGTH 保持一致 */

/** 密码最小长度（后端同样要求 >= 8） */
export const MIN_PASSWORD_LENGTH = 8;

/** 表单/提示统一使用的规则文案，避免「提示 8 位、校验 6 位」这类不一致 */
export const PASSWORD_RULE_HINT = `至少 ${MIN_PASSWORD_LENGTH} 位`;
