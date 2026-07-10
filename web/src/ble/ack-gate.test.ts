import { test } from 'node:test'
import assert from 'node:assert/strict'
import { AckGate } from './ack-gate.ts'

test('sends the first frame immediately', (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const sent: Uint8Array[] = []
  const gate = new AckGate((f) => sent.push(f), 1000)
  gate.send(Uint8Array.from([1]))
  assert.equal(sent.length, 1)
  assert.deepEqual(Array.from(sent[0]), [1])
})

test('a second frame sent before the ack is held, not sent immediately', (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const sent: Uint8Array[] = []
  const gate = new AckGate((f) => sent.push(f), 1000)
  gate.send(Uint8Array.from([1]))
  gate.send(Uint8Array.from([2]))
  assert.equal(sent.length, 1, 'the second frame must wait for an ack')
})

test('only the latest pending frame is sent once the ack arrives — intermediate ones are dropped', (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const sent: Uint8Array[] = []
  const gate = new AckGate((f) => sent.push(f), 1000)
  gate.send(Uint8Array.from([1]))
  gate.send(Uint8Array.from([2]))
  gate.send(Uint8Array.from([3]))
  gate.onAck()
  assert.equal(sent.length, 2)
  assert.deepEqual(Array.from(sent[1]), [3])
})

test('after the ack for the second frame, a further send goes out immediately', (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const sent: Uint8Array[] = []
  const gate = new AckGate((f) => sent.push(f), 1000)
  gate.send(Uint8Array.from([1]))
  gate.onAck()
  gate.send(Uint8Array.from([2]))
  assert.equal(sent.length, 2)
})

test('an ack with nothing pending does not send anything extra', (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const sent: Uint8Array[] = []
  const gate = new AckGate((f) => sent.push(f), 1000)
  gate.send(Uint8Array.from([1]))
  gate.onAck()
  assert.equal(sent.length, 1)
})

test('a timeout flushes the pending frame if no ack ever arrives', (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const sent: Uint8Array[] = []
  const gate = new AckGate((f) => sent.push(f), 500)
  gate.send(Uint8Array.from([1]))
  gate.send(Uint8Array.from([2]))
  assert.equal(sent.length, 1)
  t.mock.timers.tick(500)
  assert.equal(sent.length, 2)
  assert.deepEqual(Array.from(sent[1]), [2])
})

test('reset clears in-flight and pending state without firing anything', (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const sent: Uint8Array[] = []
  const gate = new AckGate((f) => sent.push(f), 500)
  gate.send(Uint8Array.from([1]))
  gate.send(Uint8Array.from([2]))
  gate.reset()
  t.mock.timers.tick(1000)
  assert.equal(sent.length, 1, 'no further sends after reset')
  gate.send(Uint8Array.from([3]))
  assert.equal(sent.length, 2, 'a fresh send after reset goes out immediately')
})
