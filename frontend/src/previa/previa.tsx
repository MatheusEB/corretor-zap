import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { AnimatePresence, MotionConfig, motion } from 'motion/react'

import { Logo } from '@/components/logo'
import { Tecla } from '@/components/tecla'
import { Button } from '@/components/ui/button'
import { Spinner } from '@/components/ui/spinner'
import type { Correcao, ResultadoCorrecao } from '@/lib/api'
import { aplicarTodas, diferenca, segmentar } from '@/lib/correcoes'
import { cn } from '@/lib/utils'

/** Ponte com o Java: a janela flutuante injeta `window.ponte` e chama `window.mostrarPrevia`. */
type Ponte = {
  tamanho: (largura: number, altura: number) => void
  /** JSON de {@link Decisao} */
  aplicar: (decisao: string) => void
  cancelar: () => void
  tom: (nome: string) => void
}

/** "revisando": a prévia abriu com o corretor local e a IA ainda vai responder. */
type EstadoIa = 'desligada' | 'revisando' | 'pronta' | 'falhou'

type Dados = { resultado: ResultadoCorrecao; escuro: boolean; ia: EstadoIa; tons: boolean }
type Atualizacao = { resultado: ResultadoCorrecao; ia: EstadoIa }
type RespostaTom = { tom: string; resultado?: ResultadoCorrecao; erro?: string }
type Decisao = { texto: string; aceitas: Correcao[]; recusadas: Correcao[] }

declare global {
  interface Window {
    ponte?: Ponte
    mostrarPrevia?: (dados: Dados) => void
    atualizarPrevia?: (dados: Atualizacao) => void
    mostrarTom?: (dados: RespostaTom) => void
    decisaoDaPrevia?: () => string
  }
}

const TONS = [
  { nome: 'FORMAL', rotulo: 'Formal', tecla: '1' },
  { nome: 'CURTO', rotulo: 'Curto', tecla: '2' },
  { nome: 'GENTIL', rotulo: 'Gentil', tecla: '3' },
] as const

// a prévia tem de ser lida na hora: as marcas entram quase juntas, e a última nunca depois de ~0,3 s
const ATRASO_INICIAL = 0.04
const ATRASO_POR_MARCA = 0.025
const ATRASO_MAXIMO = 0.3
/** Quanto o "Tudo certo" fica na tela antes de a prévia fechar sozinha. */
const FECHAR_SEM_CORRECAO_MS = 1100

/** As duvidosas chegam desmarcadas: um clique aplica. */
const duvidosas = (r: ResultadoCorrecao) => new Set(r.correcoes.flatMap((c, i) => (c.certeza ? [] : [i])))

export default function Previa() {
  const [dados, setDados] = useState<Dados | null>(null)
  const [rejeitadas, setRejeitadas] = useState<Set<number>>(() => new Set())
  const [rodada, setRodada] = useState(0)
  // muda só quando uma prévia nova abre: a resposta da IA troca o conteúdo sem refazer a entrada do cartão
  const [abertura, setAbertura] = useState(0)
  // a IA respondeu depois que a pessoa já mexeu nas marcas: não troca nada sem ela pedir
  const [pendente, setPendente] = useState<ResultadoCorrecao | null>(null)
  const [tom, setTom] = useState<{ atual: string | null; carregando: string | null; erro: string | null }>({
    atual: null,
    carregando: null,
    erro: null,
  })
  const mexeuRef = useRef(false)
  const tomRef = useRef<string | null>(null)
  const cartaoRef = useRef<HTMLDivElement>(null)

  const trocarResultado = (resultado: ResultadoCorrecao) => {
    setDados((atual) => (atual ? { ...atual, resultado } : atual))
    setRejeitadas(duvidosas(resultado))
    setPendente(null)
    mexeuRef.current = false
    setRodada((r) => r + 1)
  }

  useEffect(() => {
    window.mostrarPrevia = (novos) => {
      document.documentElement.classList.toggle('dark', novos.escuro)
      setDados(novos)
      setRejeitadas(duvidosas(novos.resultado))
      setPendente(null)
      setTom({ atual: null, carregando: null, erro: null })
      mexeuRef.current = false
      tomRef.current = null
      setRodada((r) => r + 1)
      setAbertura((a) => a + 1)
    }
    window.atualizarPrevia = ({ resultado, ia }) => {
      setDados((atual) => (atual ? { ...atual, ia } : atual))
      // falhou: fica o que já está na tela (o corretor local); com um tom escolhido, a revisão chegou tarde demais
      if (ia === 'falhou' || tomRef.current) return
      if (mexeuRef.current) setPendente(resultado)
      else trocarResultado(resultado)
    }
    window.mostrarTom = ({ tom: nome, resultado, erro }) => {
      if (resultado) {
        tomRef.current = nome
        setTom({ atual: nome, carregando: null, erro: null })
        trocarResultado(resultado)
      } else {
        setTom((atual) => ({ ...atual, carregando: null, erro: erro ?? 'Não foi possível ajustar o tom agora' }))
      }
    }
    return () => {
      delete window.mostrarPrevia
      delete window.atualizarPrevia
      delete window.mostrarTom
    }
  }, [])

  // a janela nativa acompanha o tamanho do cartão (inclui a margem da sombra)
  useLayoutEffect(() => {
    const cartao = cartaoRef.current
    if (!cartao) return
    const avisar = () => {
      const r = cartao.getBoundingClientRect()
      window.ponte?.tamanho(Math.ceil(r.width) + 32, Math.ceil(r.height) + 32)
    }
    avisar()
    const observador = new ResizeObserver(avisar)
    observador.observe(cartao)
    return () => observador.disconnect()
  }, [rodada])

  const correcoes = dados?.resultado.correcoes ?? []
  const aceitas = correcoes.filter((_, i) => !rejeitadas.has(i))
  const recusadas = correcoes.filter((_, i) => rejeitadas.has(i))
  const textoFinal = dados ? aplicarTodas(dados.resultado.original, aceitas) : ''
  const revisando = dados?.ia === 'revisando' && !tom.atual
  const tudoCerto = dados !== null && correcoes.length === 0 && !revisando && tom.carregando === null

  // nada a corrigir depois da IA: mostra o "Tudo certo" um instante e devolve o foco ao WhatsApp
  useEffect(() => {
    if (!tudoCerto) return
    const fechar = setTimeout(() => window.ponte?.cancelar(), FECHAR_SEM_CORRECAO_MS)
    return () => clearTimeout(fechar)
  }, [tudoCerto, rodada])

  // Enter aplica e Esc cancela, de qualquer lugar do cartão (a janela nativa também chama decisaoDaPrevia)
  const decisaoRef = useRef<Decisao>({ texto: '', aceitas: [], recusadas: [] })
  decisaoRef.current = { texto: textoFinal, aceitas, recusadas }
  const pedirTomRef = useRef((_nome: string) => {})
  pedirTomRef.current = (nome: string) => {
    if (!dados?.tons || tom.carregando) return
    setTom((atual) => ({ ...atual, carregando: nome, erro: null }))
    window.ponte?.tom(nome)
  }
  const teclasRef = useRef({ aplicar: () => {}, cancelar: () => {} })
  teclasRef.current = {
    aplicar: () => window.ponte?.aplicar(JSON.stringify(decisaoRef.current)),
    cancelar: () => window.ponte?.cancelar(),
  }
  useEffect(() => {
    window.decisaoDaPrevia = () => JSON.stringify(decisaoRef.current)
    return () => {
      delete window.decisaoDaPrevia
    }
  }, [])
  useEffect(() => {
    const aoTeclar = (e: KeyboardEvent) => {
      if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault()
        teclasRef.current.aplicar()
      } else if (e.key === 'Escape') {
        e.preventDefault()
        teclasRef.current.cancelar()
      } else if (e.altKey) {
        const escolhido = TONS.find((t) => t.tecla === e.key)
        if (escolhido) {
          e.preventDefault()
          pedirTomRef.current(escolhido.nome)
        }
      }
    }
    window.addEventListener('keydown', aoTeclar)
    return () => window.removeEventListener('keydown', aoTeclar)
  }, [])

  const alternar = (indice: number) => {
    mexeuRef.current = true
    setRejeitadas((atual) => {
      const proximo = new Set(atual)
      if (proximo.has(indice)) proximo.delete(indice)
      else proximo.add(indice)
      return proximo
    })
  }

  if (!dados) return null

  return (
    <MotionConfig reducedMotion="user">
      <div className="p-4">
        <motion.div
          key={abertura}
          ref={cartaoRef}
          role="dialog"
          aria-label="Prévia da correção"
          initial={{ opacity: 0, y: 6, scale: 0.98 }}
          animate={{ opacity: 1, y: 0, scale: 1 }}
          transition={{ type: 'spring', stiffness: 620, damping: 38 }}
          className="w-[440px] overflow-hidden rounded-2xl border border-linha-forte bg-folha text-tinta shadow-[0_1px_0_var(--linha),0_28px_56px_-24px_color-mix(in_oklab,var(--tinta)_60%,transparent)]"
        >
          <header className="flex items-center gap-2.5 border-b border-linha px-4 pt-3.5 pb-3">
            <Logo className="size-6" />
            <p className="font-serif text-[1.35rem] leading-none">Prévia</p>
            <SeloIa ia={dados.ia} tom={tom.atual} />
            <p className="ml-auto font-mono text-[10px] tracking-[0.16em] text-apagado uppercase tabular-nums">
              {aceitas.length} de {correcoes.length} {correcoes.length === 1 ? 'correção' : 'correções'}
            </p>
          </header>

          <div className="relative">
            {tudoCerto ? (
              <p className="px-4 py-5 text-[15px] text-teal">Tudo certo, nada para corrigir.</p>
            ) : (
              <p
                data-testid="texto-previa"
                className="max-h-64 overflow-y-auto px-4 py-4 text-[15px] leading-[1.9] break-words whitespace-pre-wrap"
              >
                {segmentar(dados.resultado.original, correcoes).map((s, i) =>
                  s.correcao ? (
                    <Marca
                      key={`${rodada}-${i}`}
                      correcao={s.correcao}
                      aceita={!rejeitadas.has(s.indice!)}
                      atraso={Math.min(ATRASO_INICIAL + (s.indice ?? 0) * ATRASO_POR_MARCA, ATRASO_MAXIMO)}
                      alternar={() => alternar(s.indice!)}
                    />
                  ) : (
                    <span key={`${rodada}-${i}`}>{s.texto}</span>
                  ),
                )}
              </p>
            )}
            <span
              aria-hidden
              className="pointer-events-none absolute inset-x-0 bottom-0 h-4 bg-linear-to-t from-folha to-transparent"
            />
          </div>

          {pendente ? (
            <button
              type="button"
              onClick={() => trocarResultado(pendente)}
              className="flex w-full items-center gap-2 border-t border-linha bg-teal-suave/50 px-4 py-2 text-left text-xs text-teal hover:bg-teal-suave"
            >
              A IA revisou a mensagem. Clique para ver a versão dela.
            </button>
          ) : null}

          {dados.tons ? (
            <div className="flex items-center gap-1.5 border-t border-linha px-4 py-2">
              <span className="mr-1 text-xs text-apagado">Tom</span>
              {TONS.map((t) => (
                <Button
                  key={t.nome}
                  size="xs"
                  variant={tom.atual === t.nome ? 'secondary' : 'ghost'}
                  disabled={tom.carregando !== null}
                  onClick={() => pedirTomRef.current(t.nome)}
                  title={`Alt+${t.tecla}`}
                  aria-pressed={tom.atual === t.nome}
                >
                  {tom.carregando === t.nome ? <Spinner className="size-3" /> : null}
                  {t.rotulo}
                </Button>
              ))}
              {tom.erro ? <span className="ml-auto truncate text-xs text-vermelhao">{tom.erro}</span> : null}
            </div>
          ) : null}

          <footer className="flex items-center gap-2 border-t border-linha bg-papel/60 px-4 py-3">
            <Button onClick={teclasRef.current.aplicar} disabled={aceitas.length === 0}>
              Aplicar
              <Tecla className="ml-1 border-primary-foreground/25 bg-primary-foreground/15 text-primary-foreground shadow-none">↵</Tecla>
            </Button>
            <Button variant="ghost" onClick={teclasRef.current.cancelar}>
              Cancelar
              <Tecla className="ml-1">Esc</Tecla>
            </Button>
            <p className="ml-auto text-xs text-apagado">Clique para desfazer</p>
          </footer>
        </motion.div>
      </div>
    </MotionConfig>
  )
}

/** Mostra de onde vem a correção: a IA ainda revisando, já revisou, falhou, ou um tom escolhido. */
function SeloIa({ ia, tom }: { ia: EstadoIa; tom: string | null }) {
  const base = 'inline-flex items-center gap-1 rounded-[4px] px-1.5 py-0.5 font-mono text-[10px] tracking-[0.12em] uppercase'
  if (tom) {
    return <span className={cn(base, 'bg-teal-suave text-teal')}>Tom {TONS.find((t) => t.nome === tom)?.rotulo}</span>
  }
  switch (ia) {
    case 'revisando':
      return (
        <span data-testid="selo-ia" className={cn(base, 'bg-secondary text-apagado')}>
          <Spinner className="size-2.5" />
          IA revisando
        </span>
      )
    case 'pronta':
      return (
        <span data-testid="selo-ia" className={cn(base, 'bg-teal-suave text-teal')}>
          IA
        </span>
      )
    case 'falhou':
      return (
        <span data-testid="selo-ia" title="A IA não respondeu; a correção é a do corretor local" className={cn(base, 'bg-secondary text-apagado')}>
          Sem IA
        </span>
      )
    default:
      return null
  }
}

type PropsMarca = { correcao: Correcao; aceita: boolean; atraso: number; alternar: () => void }

/**
 * Uma correção no texto: o trecho antigo é riscado (o traço "se desenha") e o novo entra logo depois.
 * Clicar alterna entre corrigir e manter o original.
 */
function Marca({ correcao, aceita, atraso, alternar }: PropsMarca) {
  const { prefixo, removido, inserido, sufixo } = diferenca(correcao.trecho, correcao.sugestao)

  return (
    <button
      type="button"
      onClick={alternar}
      aria-pressed={aceita}
      title={
        aceita
          ? `${correcao.descricaoRegra}: clique para manter o original`
          : correcao.certeza
            ? 'Clique para corrigir de novo'
            : `Talvez “${correcao.sugestao}”? Clique para corrigir`
      }
      className={cn(
        '-mx-0.5 rounded-[4px] px-0.5 align-baseline transition-colors focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none',
        aceita ? 'hover:bg-teal-suave/70' : 'underline decoration-linha-forte decoration-dashed underline-offset-4 hover:bg-secondary',
      )}
    >
      {prefixo}
      {removido ? (
        <span className={cn('relative', aceita && 'text-apagado')}>
          {removido}
          {aceita ? (
            <motion.span
              aria-hidden
              className="absolute inset-x-0 top-1/2 h-[1.5px] origin-left bg-vermelhao"
              initial={{ scaleX: 0 }}
              animate={{ scaleX: 1, transition: { delay: atraso, duration: 0.28, ease: [0.2, 0.7, 0.2, 1] } }}
            />
          ) : null}
        </span>
      ) : null}
      <AnimatePresence initial>
        {aceita && inserido ? (
          <motion.ins
            initial={{ opacity: 0, y: -4 }}
            animate={{ opacity: 1, y: 0, transition: { delay: atraso + (removido ? 0.2 : 0), duration: 0.24 } }}
            exit={{ opacity: 0, transition: { duration: 0.14 } }}
            className={cn(
              'inline-block font-semibold whitespace-pre text-teal no-underline',
              removido ? 'ml-1' : 'rounded-[3px] bg-teal-suave px-[2px]',
            )}
          >
            {inserido}
          </motion.ins>
        ) : null}
      </AnimatePresence>
      {sufixo}
    </button>
  )
}
