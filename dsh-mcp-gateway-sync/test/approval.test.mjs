import assert from 'node:assert/strict'
import test from 'node:test'
import {
  approvalText,
  installApprovalUnwrap,
  isMetaCallName,
  unwrapMetaCall,
} from '../approval.js'

function fakeCtx() {
  const listeners = []
  return {
    listeners,
    on(event, listener) {
      listeners.push({ event, listener })
      return () => {
        const index = listeners.findIndex((entry) => entry.listener === listener)
        if (index >= 0) listeners.splice(index, 1)
      }
    },
  }
}

function decide(gate, decision) {
  return gate.listener({
    name: 'mcp__team__call_tool',
    arguments: { name: 'pcb__refund_apply', arguments: { orderNo: 'A1' }, reason: '用户申请退款' },
  }, async () => decision)
}

test('recognises prefixed and bare call_tool names', () => {
  assert.equal(isMetaCallName('call_tool'), true)
  assert.equal(isMetaCallName('mcp__team-pcb__call_tool'), true)
  assert.equal(isMetaCallName('mcp__team-pcb__search_tools'), false)
  assert.equal(isMetaCallName(null), false)
})

test('unwraps inner name, arguments and reason', () => {
  const inner = unwrapMetaCall('mcp__team__call_tool', {
    name: 'pcb__refund_apply',
    arguments: { orderNo: 'A1' },
    reason: '用户申请退款',
  })
  assert.deepEqual(inner, {
    toolName: 'pcb__refund_apply',
    arguments: { orderNo: 'A1' },
    reason: '用户申请退款',
  })
})

test('parses stringified arguments and tolerates missing values', () => {
  const inner = unwrapMetaCall('call_tool', '{"name":"order_query","arguments":{"id":1}}')
  assert.equal(inner.toolName, 'order_query')
  assert.equal(inner.arguments.id, 1)
  assert.equal(inner.reason, '')

  assert.equal(unwrapMetaCall('call_tool', '{"arguments":{}}'), null)
  assert.equal(unwrapMetaCall('order_query', { name: 'x' }), null)
  assert.equal(unwrapMetaCall('call_tool', 'not-json'), null)
})

test('formats approval text with reason', () => {
  const text = approvalText({ toolName: 'pcb__refund_apply', reason: '用户申请退款' })
  assert.match(text.reason, /pcb__refund_apply/)
  assert.match(text.reason, /用户申请退款/)
  assert.match(text.displayReason.en, /Proxy call to pcb__refund_apply/)
  assert.match(text.displayReason.zh, /代理调用 pcb__refund_apply/)
})

test('enriches an existing ask without escalating allow by default', async () => {
  const ctx = fakeCtx()
  installApprovalUnwrap(ctx, { logger: { warn() {} } })
  const gate = ctx.listeners.find((entry) => entry.event === 'tools/pre-execute')

  const ask = await decide(gate, { kind: 'ask', reason: 'tool requires approval' })
  assert.equal(ask.kind, 'ask')
  assert.match(ask.reason, /pcb__refund_apply/)
  assert.match(ask.displayReason.en, /pcb__refund_apply/)

  const allow = await decide(gate, { kind: 'allow' })
  assert.deepEqual(allow, { kind: 'allow' })
})

test('escalates allowed proxy calls only when configured', async () => {
  const ctx = fakeCtx()
  installApprovalUnwrap(ctx, { escalate: true, logger: { warn() {} } })
  const gate = ctx.listeners.find((entry) => entry.event === 'tools/pre-execute')

  const escalated = await decide(gate, { kind: 'allow' })
  assert.equal(escalated.kind, 'ask')
  assert.match(escalated.reason, /pcb__refund_apply/)
})

test('never overrides deny or cancel', async () => {
  const ctx = fakeCtx()
  installApprovalUnwrap(ctx, { escalate: true })
  const gate = ctx.listeners.find((entry) => entry.event === 'tools/pre-execute')

  assert.deepEqual(await decide(gate, { kind: 'deny', reason: 'no' }), {
    kind: 'deny',
    reason: 'no',
  })
  assert.deepEqual(await decide(gate, { kind: 'cancel' }), { kind: 'cancel' })
})

test('passes through non-proxy tools untouched', async () => {
  const ctx = fakeCtx()
  installApprovalUnwrap(ctx)
  const gate = ctx.listeners.find((entry) => entry.event === 'tools/pre-execute')
  const decision = { kind: 'ask', reason: 'other' }

  const result = await gate.listener(
    { name: 'mcp__team__search_tools', arguments: { query: 'x' } },
    async () => decision,
  )
  assert.deepEqual(result, decision)
})
