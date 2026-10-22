/**
 * DSH Cordis plugin: sync MCP Gateway discovery into live DSH MCP clients.
 *
 * Unlike an external YAML writer, this plugin runs inside the DSH process:
 * it fetches `/mcp/discovery/servers` and mounts one
 * `@deepseek-ai/dsh-mcp-client` child plugin per visible gateway server.
 * Removing/updating a server disposes/recreates only that child plugin.
 */
import { createHash } from 'node:crypto'
import { readFile } from 'node:fs/promises'
import { homedir } from 'node:os'
import { isAbsolute, join } from 'node:path'
import z from '@deepseek-ai/schemastery'
import * as McpClient from '@deepseek-ai/dsh-mcp-client'
import { installApprovalUnwrap } from './approval.js'

export const name = 'mcp-gateway-sync'

/** The parent plugin does not need tools directly; each child mcp-client injects it. */
export const inject = []

const SERVER_KEY_PATTERN = /^[A-Za-z0-9_-]{1,32}$/
const PLUGIN_VERSION = '0.1.0'

export const Config = z.object({
  /** Gateway base URL, e.g. http://127.0.0.1:8081 */
  gatewayBaseUrl: z.string().required(),
  /** File containing the DSH user token (legacy name; prefer userTokenFile). */
  tokenFile: z.string().default(''),
  /** File containing the DSH user token, sent as x-dsh-auth-token (preferred). */
  userTokenFile: z.string().default(''),
  /** Discovery polling interval. */
  intervalMs: z.number().min(5_000).default(30_000),
  /** Prefix for DSH serverName, useful when multiple gateways share one profile. */
  serverNamePrefix: z.string().default(''),
  /** Per-tool-call timeout passed to each mcp-client child. */
  toolCallTimeoutMs: z.number().min(1_000).default(60_000),
  /** HTTP timeout for the discovery request. */
  requestTimeoutMs: z.number().min(1_000).default(15_000),
  /** Fail plugin activation if the first discovery request fails. */
  failOnStartupError: z.boolean().default(false),
  /** 把 call_tool 的内层工具名写进审批展示（R6）。 */
  approvalUnwrap: z.boolean().default(true),
  /** 是否把「允许的 call_tool」升级为审批，让卡片展示真实工具名。 */
  approvalEscalate: z.boolean().default(false),
})

function expandPath(value) {
  let path = String(value || '')
  if (path === '~') path = homedir()
  else if (path.startsWith('~/')) path = join(homedir(), path.slice(2))
  return isAbsolute(path) ? path : join(process.cwd(), path)
}

function sha256(value) {
  return createHash('sha256').update(value).digest('hex')
}

function errorMessage(error) {
  return error instanceof Error ? error.message : String(error)
}

function getLogger(ctx) {
  try {
    return typeof ctx.logger === 'function' ? ctx.logger('mcp-gateway-sync') : ctx.logger
  } catch {
    return console
  }
}

/**
 * Cordis plugin entrypoint.
 *
 * @param {import('@deepseek-ai/cordis').Context} ctx
 * @param {import('@deepseek-ai/schemastery').Infer<typeof Config>} config
 */
export function createReconciler(ctx, config) {
  const logger = getLogger(ctx)
  const baseUrl = String(config.gatewayBaseUrl).replace(/\/+$/, '')
  const tokenFile = config.tokenFile ? expandPath(config.tokenFile) : ''
  const userTokenFile = config.userTokenFile ? expandPath(config.userTokenFile) : ''
  const prefix = String(config.serverNamePrefix || '')
  const intervalMs = Math.max(5_000, Number(config.intervalMs) || 30_000)
  const requestTimeoutMs = Math.max(1_000, Number(config.requestTimeoutMs) || 15_000)
  const toolCallTimeoutMs = Math.max(1_000, Number(config.toolCallTimeoutMs) || 60_000)

  /** serverName -> { fiber, fingerprint } */
  const children = new Map()
  let lastEtag
  let lastTokenHash
  let stopped = false
  let running = false
  let pending = false
  let firstRun = true

  async function readTokenFile(path, label) {
    const token = (await readFile(path, 'utf8')).trim()
    if (!token) throw new Error(`${label}为空: ${path}`)
    return token
  }

  async function readCredential() {
    if (userTokenFile) return readTokenFile(userTokenFile, 'user token 文件')
    if (tokenFile) return readTokenFile(tokenFile, 'token 文件')
    throw new Error('tokenFile / userTokenFile 至少配置一个')
  }

  async function fetchServers(token) {
    const tokenHash = sha256(token)
    const headers = {
      Accept: 'application/json',
      'x-dsh-auth-token': token,
    }
    if (lastEtag && lastTokenHash === tokenHash) {
      headers['If-None-Match'] = lastEtag
    }
    const response = await fetch(`${baseUrl}/mcp/discovery/servers`, {
      headers,
      signal: AbortSignal.timeout(requestTimeoutMs),
    })
    if (response.status === 304) {
      return { notModified: true, etag: lastEtag, tokenHash }
    }
    if (!response.ok) {
      const body = await response.text().catch(() => '')
      const challenge = response.headers.get('www-authenticate') || ''
      throw new Error(`discovery HTTP ${response.status} ${challenge} ${body}`.trim())
    }
    const body = await response.json()
    if (!body || body.code !== 0) {
      throw new Error(`discovery 返回异常: ${JSON.stringify(body)}`)
    }
    return {
      notModified: false,
      servers: Array.isArray(body.data) ? body.data : [],
      etag: response.headers.get('etag') || undefined,
      tokenHash,
    }
  }

  async function disposeChild(serverName) {
    const entry = children.get(serverName)
    if (!entry) return
    children.delete(serverName)
    try {
      await entry.fiber.dispose()
    } catch (error) {
      logger.warn(`mcp-gateway-sync: dispose ${serverName} failed: ${errorMessage(error)}`)
    }
  }

  async function reconcile() {
    if (stopped) return
    if (running) {
      pending = true
      return
    }
    running = true
    try {
      const credential = await readCredential()
      const result = await fetchServers(credential)
      if (result.notModified) return

      lastEtag = result.etag
      lastTokenHash = result.tokenHash

      const childHeaders = { 'x-dsh-auth-token': credential }

      const desired = new Map()
      for (const server of result.servers || []) {
        const key = String(server.serverKey || '').trim()
        const url = String(server.url || '').trim()
        const rawRevision = server.revision ?? server.updatedAt ?? JSON.stringify(server)
        if (!SERVER_KEY_PATTERN.test(key)) {
          logger.warn(`mcp-gateway-sync: 跳过非法 serverKey ${JSON.stringify(key)}`)
          continue
        }
        const serverName = prefix + key
        if (!SERVER_KEY_PATTERN.test(serverName)) {
          logger.warn(`mcp-gateway-sync: 跳过非法 serverName ${JSON.stringify(serverName)}（检查 serverNamePrefix）`)
          continue
        }
        if (!url.startsWith('http://') && !url.startsWith('https://')) {
          logger.warn(`mcp-gateway-sync: 跳过非法 URL ${JSON.stringify(url)}（${serverName}）`)
          continue
        }
        if (desired.has(serverName)) {
          logger.warn(`mcp-gateway-sync: 跳过重复 serverName ${serverName}`)
          continue
        }
        desired.set(serverName, { url, revision: String(rawRevision) })
      }

      // Remove servers that disappeared or lost authorization.
      for (const serverName of [...children.keys()]) {
        if (!desired.has(serverName)) {
          logger.info(`mcp-gateway-sync: remove ${serverName}`)
          await disposeChild(serverName)
        }
      }

      for (const [serverName, server] of desired) {
        const fingerprint = sha256(
          [serverName, server.url, server.revision, result.tokenHash].join('\0'),
        )
        const existing = children.get(serverName)
        if (existing && existing.fingerprint === fingerprint) continue

        if (existing) {
          logger.info(`mcp-gateway-sync: reload ${serverName}`)
          await disposeChild(serverName)
        } else {
          logger.info(`mcp-gateway-sync: add ${serverName}`)
        }

        let fiber
        try {
          fiber = ctx.plugin(McpClient, {
            transport: 'streamable-http',
            serverName,
            url: server.url,
            headers: childHeaders,
            toolCallTimeoutMs,
            failOnStartupError: false,
            reconnect: { enabled: true },
          })
        } catch (error) {
          logger.error(`mcp-gateway-sync: mount ${serverName} failed: ${errorMessage(error)}`)
          continue
        }

        const entry = { fiber, fingerprint }
        children.set(serverName, entry)
        try {
          // mcp-client blocks activation until tools/list settles; await it so
          // the parent's startup reflects the first generation.
          await fiber
        } catch (error) {
          logger.error(`mcp-gateway-sync: ${serverName} initial connection failed: ${errorMessage(error)}`)
          if (children.get(serverName) === entry) {
            children.delete(serverName)
          }
          try {
            await fiber.dispose()
          } catch (disposeError) {
            logger.warn(`mcp-gateway-sync: dispose failed ${serverName}: ${errorMessage(disposeError)}`)
          }
        }
      }
    } catch (error) {
      logger.error(`mcp-gateway-sync: reconcile failed: ${errorMessage(error)}`)
      if (firstRun && config.failOnStartupError) throw error
    } finally {
      firstRun = false
      running = false
      if (pending && !stopped) {
        pending = false
        queueMicrotask(() => void reconcile())
      }
    }
  }

  async function dispose() {
    stopped = true
    for (const serverName of [...children.keys()]) {
      await disposeChild(serverName)
    }
  }

  return { reconcile, dispose, intervalMs }
}

/**
 * Cordis plugin entrypoint: start the poller and await the first discovery.
 */
export async function apply(ctx, config) {
  if (config.approvalUnwrap !== false) {
    try {
      ctx.effect(
        () => installApprovalUnwrap(ctx, {
          escalate: config.approvalEscalate === true,
          logger: getLogger(ctx),
        }),
        'mcp-gateway-sync.approval-unwrap',
      )
    } catch (error) {
      getLogger(ctx).warn?.(`mcp-gateway-sync: 审批信息还原注册失败: ${errorMessage(error)}`)
    }
  }
  const reconciler = createReconciler(ctx, config)
  ctx.effect(() => {
    const timer = setInterval(() => void reconciler.reconcile(), reconciler.intervalMs)
    return async () => {
      clearInterval(timer)
      await reconciler.dispose()
    }
  }, 'mcp-gateway-sync.cleanup')

  await reconciler.reconcile()
}

export const version = PLUGIN_VERSION
