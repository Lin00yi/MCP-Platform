/**
 * DSH 审批信息还原（R6 方法 A：L3 拦截层解包内层工具名）。
 *
 * Gateway 的 META 模式把长尾工具收敛到 `call_tool`，DSH 原生审批只拿到外层工具名。
 * 本模块在 `tools/pre-execute` 瀑布里读取内层 `name` / `reason`，并在
 * DSH 决定需要审批时把真实目标工具写进 reason / displayReason。
 *
 * 约束：
 * - 只改写审批展示，不修改 exec.name / exec.arguments，不影响实际执行；
 * - 已有 deny/cancel 一律原样返回，不放大权限；
 * - 是否把 allow 升级为 ask 由 `escalate` 控制（默认关闭）。
 */

const CALL_TOOL = 'call_tool'

function parseArguments(value) {
  if (value === null || value === undefined) return {}
  if (typeof value === 'string') {
    try {
      const parsed = JSON.parse(value)
      return parsed && typeof parsed === 'object' ? parsed : {}
    } catch {
      return {}
    }
  }
  return typeof value === 'object' ? value : {}
}

/** 判断是否为网关代理调用（兼容 DSH 的 mcp__server__call_tool 前缀）。 */
export function isMetaCallName(name) {
  if (typeof name !== 'string' || name.length === 0) return false
  const index = name.lastIndexOf('__')
  const bare = index >= 0 ? name.slice(index + 2) : name
  return bare === CALL_TOOL
}

/**
 * 解包 call_tool 的内层目标；非代理调用返回 null。
 *
 * @returns {{toolName: string, arguments: object, reason: string} | null}
 */
export function unwrapMetaCall(name, args) {
  if (!isMetaCallName(name)) return null
  const value = parseArguments(args)
  const target = value.name ?? value.tool_code
  if (typeof target !== 'string' || target.trim().length === 0) return null
  const reason = typeof value.reason === 'string' ? value.reason.trim() : ''
  return {
    toolName: target.trim(),
    arguments: value.arguments ?? {},
    reason,
  }
}

/** 生成审批展示文案（reason 用于日志，displayReason 用于多语言 UI）。 */
export function approvalText(inner) {
  const baseZh = `代理调用 ${inner.toolName}`
  const baseEn = `Proxy call to ${inner.toolName}`
  const zh = inner.reason ? `${baseZh}（原因：${inner.reason}）` : baseZh
  const en = inner.reason ? `${baseEn} (reason: ${inner.reason})` : baseEn
  return { reason: zh, displayReason: { en, zh, 'zh-CN': zh } }
}

/**
 * 注册 tools/pre-execute 监听。
 *
 * @param {object} ctx Cordis Context
 * @param {{escalate?: boolean, logger?: object}} [options]
 * @returns {() => void} disposer
 */
export function installApprovalUnwrap(ctx, options = {}) {
  const logger = options.logger ?? console
  if (!ctx || typeof ctx.on !== 'function') {
    logger?.warn?.('approval-unwrap: ctx.on 不可用，跳过审批信息还原')
    return () => {}
  }
  const dispose = ctx.on('tools/pre-execute', async (exec, next) => {
    const decision = await next()
    if (!decision || decision.kind === 'deny' || decision.kind === 'cancel') {
      return decision
    }
    const inner = unwrapMetaCall(exec?.name, exec?.arguments)
    if (inner === null) return decision

    const text = approvalText(inner)
    if (decision.kind === 'ask') {
      return { ...decision, reason: text.reason, displayReason: text.displayReason }
    }
    if (decision.kind === 'allow' && options.escalate === true) {
      return { kind: 'ask', reason: text.reason, displayReason: text.displayReason }
    }
    return decision
  })
  return typeof dispose === 'function' ? dispose : () => {}
}
