const NOMES: Record<string, string> = { CTRL: 'Ctrl', SHIFT: 'Shift', ALT: 'Alt', SPACE: 'Espaço', ENTER: 'Enter' }
const MODIFICADORES = ['Control', 'Shift', 'Alt', 'Meta', 'AltGraph']

/** "CTRL+SHIFT+SPACE" → ["Ctrl", "Shift", "Espaço"] */
export function teclasDoAtalho(atalho: string): string[] {
  return atalho.split('+').map((parte) => NOMES[parte] ?? parte)
}

/** Converte um keydown do navegador no formato do backend ("CTRL+SHIFT+SPACE"), ou null se só modificadores. */
export function atalhoDoEvento(e: Pick<KeyboardEvent, 'key' | 'code' | 'ctrlKey' | 'shiftKey' | 'altKey'>): string | null {
  if (MODIFICADORES.includes(e.key)) return null
  const partes: string[] = []
  if (e.ctrlKey) partes.push('CTRL')
  if (e.shiftKey) partes.push('SHIFT')
  if (e.altKey) partes.push('ALT')
  partes.push(teclaDoCodigo(e.code))
  return partes.join('+')
}

function teclaDoCodigo(code: string): string {
  if (code === 'Space') return 'SPACE'
  if (code.startsWith('Key')) return code.slice(3)
  if (code.startsWith('Digit')) return code.slice(5)
  return code.toUpperCase()
}
