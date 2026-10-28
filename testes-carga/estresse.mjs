// Teste de estresse do Corretor Zap, no estilo do k6 (cenários + thresholds), em Node puro.
// Uso: node testes-carga/estresse.mjs [http://127.0.0.1:8765]
// Precisa do programa rodando. Não toca no atalho global: só exercita a API HTTP.
import { execFileSync } from 'node:child_process'

const BASE = process.argv[2] ?? 'http://127.0.0.1:8765'

// mensagens típicas de chat; o "esperado" é calculado no aquecimento e conferido sob carga
const TEXTOS = [
  'voce nao vai acreditar oque aconteceu hoje',
  'blz, to chegando ai em 10 min',
  'Amanha a gente se ve na casa da Ana?',
  'nao esquece de levar o carregador pfv',
  'Tudo certo por aqui, e com voce?',
  'eu acho que ele nao vem mais nao',
]
const TEXTO_GRANDE = 'voce nao vai acreditar oque aconteceu. '.repeat(500).slice(0, 19_990)

const esperado = new Map()

async function corrigir(texto) {
  const resposta = await fetch(`${BASE}/api/corrigir`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ texto }),
  })
  if (!resposta.ok) throw new Error(`HTTP ${resposta.status}`)
  return resposta.json()
}

function percentil(ordenados, p) {
  if (ordenados.length === 0) return 0
  return ordenados[Math.min(ordenados.length - 1, Math.floor((p / 100) * ordenados.length))]
}

function memoriaMb(pid) {
  try {
    const csv = execFileSync('tasklist', ['/FI', `PID eq ${pid}`, '/FO', 'CSV', '/NH'], { encoding: 'latin1' })
    const kb = csv.split('","')[4]?.replace(/[^\d]/g, '')
    return kb ? Math.round(Number(kb) / 1024) : null
  } catch {
    return null
  }
}

function pidDoServidor() {
  try {
    const porta = new URL(BASE).port
    const linhas = execFileSync('netstat', ['-ano'], { encoding: 'latin1' }).split('\n')
    const linha = linhas.find((l) => l.includes(`:${porta} `) && l.includes('LISTENING'))
    return linha?.trim().split(/\s+/).pop()
  } catch {
    return null
  }
}

/**
 * Roda `vus` usuários virtuais em paralelo. `estagios` funciona como no k6:
 * [{ duracao: ms, vus: n }], subindo/descendo a quantidade de usuários ativos.
 */
async function cenario({ nome, estagios, acao, limites }) {
  const latencias = []
  let erros = 0
  let incorretas = 0
  const amostra = []
  const inicio = performance.now()
  const duracaoTotal = estagios.reduce((s, e) => s + e.duracao, 0)
  const vusMax = Math.max(...estagios.map((e) => e.vus))

  const vusAlvo = () => {
    let decorrido = performance.now() - inicio
    for (const e of estagios) {
      if (decorrido < e.duracao) return e.vus
      decorrido -= e.duracao
    }
    return 0
  }

  async function vu(id) {
    let i = id
    while (performance.now() - inicio < duracaoTotal) {
      if (id >= vusAlvo()) {
        await new Promise((r) => setTimeout(r, 50))
        continue
      }
      const t0 = performance.now()
      try {
        const ok = await acao(i++)
        if (!ok) incorretas++
        latencias.push(performance.now() - t0)
      } catch (e) {
        erros++
        if (amostra.length < 3) amostra.push(e.message)
      }
    }
  }

  const pid = pidDoServidor()
  let picoMemoria = pid ? memoriaMb(pid) : null
  const monitor = pid
    ? setInterval(() => {
        const mb = memoriaMb(pid)
        if (mb) picoMemoria = Math.max(picoMemoria ?? 0, mb)
      }, 1000)
    : null

  await Promise.all(Array.from({ length: vusMax }, (_, id) => vu(id)))
  if (monitor) clearInterval(monitor)

  const segundos = (performance.now() - inicio) / 1000
  const ordenados = latencias.sort((a, b) => a - b)
  const total = ordenados.length + erros
  const r = {
    cenario: nome,
    vusMax,
    requisicoes: total,
    'req/s': +(ordenados.length / segundos).toFixed(1),
    'erro%': +((100 * erros) / Math.max(1, total)).toFixed(2),
    incorretas,
    p50: Math.round(percentil(ordenados, 50)),
    p95: Math.round(percentil(ordenados, 95)),
    p99: Math.round(percentil(ordenados, 99)),
    max: Math.round(ordenados.at(-1) ?? 0),
    memoriaMb: picoMemoria,
  }
  const falhas = []
  if (limites.p95 && r.p95 > limites.p95) falhas.push(`p95 ${r.p95}ms > ${limites.p95}ms`)
  if (r['erro%'] > (limites.erro ?? 1)) falhas.push(`erro ${r['erro%']}% > ${limites.erro ?? 1}%`)
  if (incorretas > 0) falhas.push(`${incorretas} respostas incorretas`)
  r.resultado = falhas.length ? `FALHOU (${falhas.join('; ')})` : 'ok'
  if (amostra.length) r.amostraErros = amostra.join(' | ')
  return r
}

const corrigirConferindo = async (i) => {
  const texto = TEXTOS[i % TEXTOS.length]
  return (await corrigir(texto)).corrigido === esperado.get(texto)
}

async function main() {
  console.log(`Alvo: ${BASE}`)
  for (const t of TEXTOS) esperado.set(t, (await corrigir(t)).corrigido)
  await corrigir(TEXTO_GRANDE)

  const resultados = []
  const rodar = async (c) => {
    process.stdout.write(`→ ${c.nome}… `)
    const r = await cenario(c)
    console.log(r.resultado)
    resultados.push(r)
  }

  await rodar({ nome: 'smoke (1 VU)', estagios: [{ duracao: 5_000, vus: 1 }], acao: corrigirConferindo, limites: { p95: 300, erro: 0 } })
  await rodar({ nome: 'carga (10 VUs)', estagios: [{ duracao: 20_000, vus: 10 }], acao: corrigirConferindo, limites: { p95: 1_000 } })
  await rodar({
    nome: 'estresse (rampa 10→50→100)',
    estagios: [
      { duracao: 10_000, vus: 10 },
      { duracao: 10_000, vus: 50 },
      { duracao: 10_000, vus: 100 },
    ],
    acao: corrigirConferindo,
    limites: { erro: 1 },
  })
  await rodar({ nome: 'pico (200 VUs)', estagios: [{ duracao: 10_000, vus: 200 }], acao: corrigirConferindo, limites: { erro: 1 } })
  await rodar({
    nome: 'textos de 20 mil caracteres (5 VUs)',
    estagios: [{ duracao: 15_000, vus: 5 }],
    acao: async () => (await corrigir(TEXTO_GRANDE)).corrigido.length > 0,
    limites: { erro: 0 },
  })

  // escrita concorrente no dicionário: no fim, tem que sobrar exatamente o que foi adicionado e não removido
  const palavras = Array.from({ length: 100 }, (_, i) => `estresse${i}`)
  await rodar({
    nome: 'dicionário concorrente (20 VUs)',
    estagios: [{ duracao: 10_000, vus: 20 }],
    acao: async (i) => {
      const palavra = palavras[i % palavras.length]
      const metodo = i % 2 === 0 ? 'POST' : 'DELETE'
      const resposta = await fetch(
        metodo === 'POST' ? `${BASE}/api/dicionario` : `${BASE}/api/dicionario/${palavra}`,
        metodo === 'POST'
          ? { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ palavra }) }
          : { method: 'DELETE' },
      )
      if (!resposta.ok) throw new Error(`HTTP ${resposta.status}`)
      return true
    },
    limites: { erro: 0 },
  })
  for (const p of palavras) await fetch(`${BASE}/api/dicionario/${p}`, { method: 'DELETE' })
  const configFinal = await (await fetch(`${BASE}/api/config`)).json()
  const sobras = configFinal.dicionario.filter((p) => p.startsWith('estresse'))
  console.log(sobras.length === 0 ? '✓ dicionário consistente após escrita concorrente' : `✗ sobraram ${sobras.length} palavras`)

  console.table(resultados.map(({ amostraErros, ...r }) => r))
  for (const r of resultados) if (r.amostraErros) console.log(`${r.cenario}: ${r.amostraErros}`)
  const falhou = resultados.some((r) => r.resultado !== 'ok') || sobras.length > 0
  process.exit(falhou ? 1 : 0)
}

main().catch((e) => {
  console.error(e)
  process.exit(1)
})
