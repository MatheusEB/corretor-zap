import type { Correcao } from '@/lib/api'

/** Uma correção ainda vale se o trecho continua no mesmo lugar do texto atual. */
export function correcoesValidas(texto: string, correcoes: Correcao[]): Correcao[] {
  return correcoes.filter((c) => texto.slice(c.inicio, c.fim) === c.trecho)
}

/** Aplica uma correção e desloca as posições das que vêm depois, sem precisar perguntar ao servidor de novo. */
export function aplicarCorrecao(texto: string, correcoes: Correcao[], alvo: Correcao) {
  const delta = alvo.sugestao.length - (alvo.fim - alvo.inicio)
  const novoTexto = texto.slice(0, alvo.inicio) + alvo.sugestao + texto.slice(alvo.fim)
  const restantes = correcoes.flatMap((c) => {
    if (c === alvo) return []
    if (c.inicio >= alvo.fim) return [{ ...c, inicio: c.inicio + delta, fim: c.fim + delta }]
    return [c]
  })
  return { texto: novoTexto, correcoes: restantes }
}

export function aplicarTodas(texto: string, correcoes: Correcao[]) {
  // de trás pra frente, as posições das anteriores continuam valendo
  let resultado = texto
  for (const c of correcoes.toSorted((a, b) => b.inicio - a.inicio)) {
    resultado = resultado.slice(0, c.inicio) + c.sugestao + resultado.slice(c.fim)
  }
  return resultado
}

export type Segmento = { texto: string; correcao?: Correcao; indice?: number }

/** Quebra o texto em pedaços normais e pedaços marcados, na ordem. */
export function segmentar(texto: string, correcoes: Correcao[]): Segmento[] {
  const segmentos: Segmento[] = []
  let cursor = 0
  correcoes.forEach((c, indice) => {
    if (c.inicio < cursor) return
    if (c.inicio > cursor) segmentos.push({ texto: texto.slice(cursor, c.inicio) })
    segmentos.push({ texto: texto.slice(c.inicio, c.fim), correcao: c, indice })
    cursor = c.fim
  })
  segmentos.push({ texto: texto.slice(cursor) })
  return segmentos
}

const LETRA = /[\p{L}\p{N}]/u

/**
 * Separa o que muda de verdade: "festa mas" → "festa, mas" vira prefixo "festa", acréscimo "," e sufixo " mas".
 * Se a diferença cai no meio de uma palavra ("voce" → "você"), a palavra inteira é trocada.
 */
export function diferenca(antes: string, depois: string) {
  let prefixo = 0
  while (prefixo < antes.length && prefixo < depois.length && antes[prefixo] === depois[prefixo]) prefixo++
  while (prefixo > 0 && (LETRA.test(antes[prefixo] ?? '') || LETRA.test(depois[prefixo] ?? '')) && LETRA.test(antes[prefixo - 1])) {
    prefixo--
  }
  let sufixo = 0
  while (
    sufixo < antes.length - prefixo &&
    sufixo < depois.length - prefixo &&
    antes[antes.length - 1 - sufixo] === depois[depois.length - 1 - sufixo]
  ) {
    sufixo++
  }
  while (
    sufixo > 0 &&
    LETRA.test(antes[antes.length - sufixo]) &&
    (LETRA.test(antes[antes.length - sufixo - 1] ?? '') || LETRA.test(depois[depois.length - sufixo - 1] ?? ''))
  ) {
    sufixo--
  }
  return {
    prefixo: antes.slice(0, prefixo),
    removido: antes.slice(prefixo, antes.length - sufixo),
    inserido: depois.slice(prefixo, depois.length - sufixo),
    sufixo: antes.slice(antes.length - sufixo),
  }
}
