export type AckGateDispatch = (frame: Uint8Array) => void

/**
 * Ensures at most one command is in flight at a time. A frame sent while the
 * previous one is unacknowledged replaces any already-pending frame — every
 * frame is a full-state resend, so only the newest one is worth sending — and
 * goes out once the prior command's ack arrives. A timeout guards against a
 * dropped or missing echo permanently stalling the gate.
 */
export class AckGate {
  private awaitingAck = false
  private pendingFrame: Uint8Array | null = null
  private timeoutTimer: ReturnType<typeof setTimeout> | null = null
  private readonly dispatch: AckGateDispatch
  private readonly timeoutMs: number

  constructor(dispatch: AckGateDispatch, timeoutMs: number) {
    this.dispatch = dispatch
    this.timeoutMs = timeoutMs
  }

  /** Send now if nothing is in flight; otherwise hold as the pending frame. */
  send(frame: Uint8Array): void {
    if (this.awaitingAck) {
      this.pendingFrame = frame
      return
    }
    this.fire(frame)
  }

  /** Call when the device's echo arrives. Flushes the pending frame, if any. */
  onAck(): void {
    this.settle()
    const next = this.pendingFrame
    if (next !== null) {
      this.pendingFrame = null
      this.fire(next)
    }
  }

  /** Drop in-flight/pending state without sending anything, e.g. on disconnect. */
  reset(): void {
    this.settle()
    this.pendingFrame = null
  }

  private fire(frame: Uint8Array): void {
    this.awaitingAck = true
    this.dispatch(frame)
    this.timeoutTimer = setTimeout(() => this.onAck(), this.timeoutMs)
  }

  private settle(): void {
    if (this.timeoutTimer !== null) {
      clearTimeout(this.timeoutTimer)
      this.timeoutTimer = null
    }
    this.awaitingAck = false
  }
}
