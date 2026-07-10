const NS = 'http://www.w3.org/2000/svg'

function svg(children: SVGElement[], viewBox = '0 0 24 24'): SVGSVGElement {
  const root = document.createElementNS(NS, 'svg') as SVGSVGElement
  root.setAttribute('viewBox', viewBox)
  root.setAttribute('class', 'icon')
  for (const child of children) root.append(child)
  return root
}

function line(x1: number, y1: number, x2: number, y2: number): SVGLineElement {
  const node = document.createElementNS(NS, 'line') as SVGLineElement
  node.setAttribute('x1', String(x1))
  node.setAttribute('y1', String(y1))
  node.setAttribute('x2', String(x2))
  node.setAttribute('y2', String(y2))
  return node
}

function circle(cx: number, cy: number, r: number): SVGCircleElement {
  const node = document.createElementNS(NS, 'circle') as SVGCircleElement
  node.setAttribute('cx', String(cx))
  node.setAttribute('cy', String(cy))
  node.setAttribute('r', String(r))
  return node
}

function path(d: string): SVGPathElement {
  const node = document.createElementNS(NS, 'path') as SVGPathElement
  node.setAttribute('d', d)
  return node
}

function label(content: string): SVGTextElement {
  const node = document.createElementNS(NS, 'text') as SVGTextElement
  node.setAttribute('x', '12')
  node.setAttribute('y', '16.5')
  node.setAttribute('text-anchor', 'middle')
  node.setAttribute('font-size', '12.5')
  node.setAttribute('font-weight', '700')
  node.setAttribute('stroke', 'none')
  node.setAttribute('fill', 'currentColor')
  node.textContent = content
  return node
}

function radial(count: number, r1: number, r2: number): SVGLineElement[] {
  const lines: SVGLineElement[] = []
  for (let i = 0; i < count; i++) {
    const a = (i / count) * Math.PI * 2
    lines.push(line(12 + r1 * Math.cos(a), 12 + r1 * Math.sin(a), 12 + r2 * Math.cos(a), 12 + r2 * Math.sin(a)))
  }
  return lines
}

/** A filled rectangle, rotated about the icon's center — used for gear teeth. */
function tooth(x: number, y: number, w: number, h: number, angleDeg: number): SVGRectElement {
  const node = document.createElementNS(NS, 'rect') as SVGRectElement
  node.setAttribute('x', String(x))
  node.setAttribute('y', String(y))
  node.setAttribute('width', String(w))
  node.setAttribute('height', String(h))
  node.setAttribute('rx', '0.6')
  node.setAttribute('fill', 'currentColor')
  node.setAttribute('stroke', 'none')
  node.setAttribute('transform', `rotate(${angleDeg} 12 12)`)
  return node
}

function gearTeeth(count: number): SVGRectElement[] {
  const teeth: SVGRectElement[] = []
  for (let i = 0; i < count; i++) teeth.push(tooth(10.9, 4.5, 2.2, 3.5, (360 / count) * i))
  return teeth
}

const ICONS: Record<string, () => SVGSVGElement> = {
  power: () => svg([path('M12 3v8'), path('M7 6a7 7 0 1 0 10 0')]),
  gear: () => svg([circle(12, 12, 5.5), ...gearTeeth(8)]),
  minus: () => svg([line(6, 12, 18, 12)]),
  plus: () => svg([line(6, 12, 18, 12), line(12, 6, 12, 18)]),
  pause: () => svg([line(9, 5, 9, 19), line(15, 5, 15, 19)]),
  play: () => svg([path('M7 4l13 8-13 8z')]),
  back: () => svg([path('M15 4l-8 8 8 8')]),
  cooling: () => svg([line(12, 3, 12, 21), line(4.4, 7.5, 19.6, 16.5), line(19.6, 7.5, 4.4, 16.5)]),
  heating: () => svg([circle(12, 12, 4), ...radial(8, 7, 10)]),
  wind: () => svg([path('M3 8h13a3 3 0 1 0-3-3'), path('M3 13h16a3 3 0 1 1-3 3'), path('M3 18h9')]),
  ai: () => svg([label('PID')]),
  silent: () => svg([path('M4 9v6h4l5 5V4L8 9z'), line(16, 8, 22, 16), line(22, 8, 16, 16)]),
  hotpack: () =>
    svg([
      path('M5 18c1.5-2 1.5-3.5 0-5.5S3.5 8 5 6'),
      path('M11 18c1.5-2 1.5-3.5 0-5.5S9.5 8 11 6'),
      path('M17 18c1.5-2 1.5-3.5 0-5.5S15.5 8 17 6'),
    ]),
  coolingfirst: () => svg([path('M11 3h2v10.5a4 4 0 1 1-2 0z'), line(8, 20, 12, 16), line(12, 16, 16, 20)]),
  lowpower: () => svg([path('M13 2 4 14h6l-1 8 9-12h-6z')]),
}

export function icon(name: string): SVGSVGElement {
  const factory = ICONS[name]
  if (factory === undefined) throw new Error(`unknown icon: ${name}`)
  return factory()
}

/** A small filled charge-status bolt, overlaid on the battery indicator. Not part of the stroke-based `.icon` set. */
export function chargeBolt(): SVGSVGElement {
  const root = document.createElementNS(NS, 'svg') as SVGSVGElement
  root.setAttribute('viewBox', '0 0 10 16')
  root.setAttribute('class', 'charge-bolt')
  const bolt = path('M6 0L1 9H4.2L3.4 16L9 6.4H5.6L6 0Z')
  bolt.setAttribute('fill', '#FFD24A')
  bolt.setAttribute('stroke', 'none')
  root.append(bolt)
  return root
}
