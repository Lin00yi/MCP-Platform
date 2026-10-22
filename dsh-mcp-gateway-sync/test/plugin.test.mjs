import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { mkdtemp, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'
import { createReconciler } from '../index.js'

function fakeContext() {
  const mounted = []
  const cleanups = []
  const ctx = {
    mounted,
    cleanups,
    logger: () => ({ info() {}, warn() {}, error() {} }),
    plugin(plugin, config) {
      const fiber = {
        then(resolve) {
          // Resolve with a non-thenable; resolving with the fiber itself would
          // re-enter this thenable forever.
          return Promise.resolve().then(() => resolve?.(undefined))
        },
        async dispose() {
          fiber.disposed = (fiber.disposed || 0) + 1
        },
      }
      mounted.push({ plugin, config, fiber })
      return fiber
    },
    effect(callback) {
      const cleanup = callback()
      cleanups.push(cleanup)
      return cleanup
    },
  }
  return ctx
}

test('mounts, reloads and removes mcp-client children from discovery', { timeout: 15_000 }, async () => {
  const dir = await mkdtemp(join(tmpdir(), 'dsh-mcp-sync-'))
  const userTokenFile = join(dir, 'user-token')
  await writeFile(userTokenFile, 'dsh-user-token', { mode: 0o600 })

  let servers = [
    {
      serverKey: 'pcb-server',
      url: 'http://127.0.0.1:19081/mcp/v1/pcb-server',
      revision: 'r1',
    },
  ]
  const http = createServer((req, res) => {
    if (req.url !== '/mcp/discovery/servers') {
      res.writeHead(404).end()
      return
    }
    assert.equal(req.headers['x-dsh-auth-token'], 'dsh-user-token')
    res.writeHead(200, { 'Content-Type': 'application/json', ETag: '"r1"', Connection: 'close' })
    res.end(JSON.stringify({ code: 0, data: servers }))
  })
  await new Promise((resolve) => http.listen(0, '127.0.0.1', resolve))
  const port = http.address().port

  const ctx = fakeContext()
  const reconciler = createReconciler(ctx, {
    gatewayBaseUrl: `http://127.0.0.1:${port}`,
    userTokenFile,
    serverNamePrefix: 't_',
    intervalMs: 5_000,
    toolCallTimeoutMs: 60_000,
    requestTimeoutMs: 5_000,
    failOnStartupError: false,
  })

  try {
    await reconciler.reconcile()
    assert.equal(ctx.mounted.length, 1)
    assert.equal(ctx.mounted[0].config.serverName, 't_pcb-server')
    assert.equal(ctx.mounted[0].config.transport, 'streamable-http')
    assert.equal(ctx.mounted[0].config.headers['x-dsh-auth-token'], 'dsh-user-token')

    servers = [
      {
        serverKey: 'pcb-server',
        url: 'http://127.0.0.1:19082/mcp/v1/pcb-server',
        revision: 'r2',
      },
    ]
    await reconciler.reconcile()
    assert.equal(ctx.mounted.length, 2)
    assert.equal(ctx.mounted[0].fiber.disposed, 1)
    assert.equal(ctx.mounted[1].config.url, 'http://127.0.0.1:19082/mcp/v1/pcb-server')

    servers = []
    await reconciler.reconcile()
    assert.equal(ctx.mounted[1].fiber.disposed, 1)
  } finally {
    await reconciler.dispose()
    http.closeAllConnections()
    await new Promise((resolve) => http.close(resolve))
    await rm(dir, { recursive: true, force: true })
  }
})
