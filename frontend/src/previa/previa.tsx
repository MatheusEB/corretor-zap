import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { AnimatePresence, MotionConfig, motion } from 'motion/react'

import { Logo } from '@/components/logo'
import { Tecla } from '@/components/tecla'
import { Button } from '@/components/ui/button'
import type { Correcao, ResultadoCorrecao } from '@/lib/api'
import { aplicarTodas, diferenca, segmentar } from '@/lib/correcoes'
import { cn } from '@/lib/utils'

/** Ponte com o Java: a janela flutuante injeta `window.ponte` e chama `window.mostrarPrevia`. */
type Ponte = {
  tamanho: (largura: number, altura: number) => void
  aplicar: (texto: string) => void
  cancelar: () => void
}

type Dados = { resultado: ResultadoCorrecao; escuro: boolean }

declare global {
  interface Window {
    ponte?: Ponte
    mostrarPrevia?: (dados: Dados) => void
    textoDaPrevia?: () => string
  }
}

const ATRASO_POR_MARCA = 0.07

export default function Previa() {
  const [dados, setDados] = useState<Dados | null>(null)
  const [rejeitadas, setRejeitadas] = useState<Set<number>>(() => new Set())
  const [rodada, setRodada] = useState(0)
  const cartaoRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    window.mostrarPrevia = (novos) => {
      document.documentElement.classList.toggle('dark', novos.escuro)
      setDados(novos)
      // as duvidosas chegam desmarcadas: um clique aplica
      setRejeitadas(new Set(novos.resultado.correcoes.flatMap((c, i) => (c.certeza ? [] : [i]))))
      setRodada((r) => r + 1)
    }
    return () => {
      delete window.mostrarPrevia
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
  const textoFinal = dados ? aplicarTodas(dados.resultado.original, aceitas) : ''

  // Enter aplica e Esc cancela, de qualquer lugar do cartão (a janela nativa também chama textoDaPrevia)
  const teclasRef = useRef({ aplicar: () => {}, cancelar: () => {} })
  teclasRef.current = {
    aplicar: () => window.ponte?.aplicar(textoFinal),
    cancelar: () => window.ponte?.cancelar(),
  }
  const textoFinalRef = useRef(textoFinal)
  textoFinalRef.current = textoFinal
  useEffect(() => {
    window.textoDaPrevia = () => textoFinalRef.current
    return () => {
      delete window.textoDaPrevia
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
      }
    }
    window.addEventListener('keydown', aoTeclar)
    return () => window.removeEventListener('keydown', aoTeclar)
  }, [])

  const alternar = (indice: number) =>
    setRejeitadas((atual) => {
      const proximo = new Set(atual)
      if (proximo.has(indice)) proximo.delete(indice)
      else proximo.add(indice)
      return proximo
    })

  if (!dados) return null

  return (
    <MotionConfig reducedMotion="user">
      <div className="p-4">
        <motion.div
          key={rodada}
          ref={cartaoRef}
          role="dialog"
          aria-label="Prévia da correção"
          initial={{ opacity: 0, y: 10, scale: 0.97 }}
          animate={{ opacity: 1, y: 0, scale: 1 }}
          transition={{ type: 'spring', stiffness: 460, damping: 32 }}
          className="w-[440px] overflow-hidden rounded-2xl border border-linha-forte bg-folha text-tinta shadow-[0_1px_0_var(--linha),0_28px_56px_-24px_color-mix(in_oklab,var(--tinta)_60%,transparent)]"
        >
          <header className="flex items-center gap-2.5 border-b border-linha px-4 pt-3.5 pb-3">
            <Logo className="size-6" />
            <p className="font-serif text-[1.35rem] leading-none">Prévia</p>
            {correcoes.some((c) => c.regra === 'CZ_IA') ? (
              <span className="rounded-[4px] bg-teal-suave px-1.5 py-0.5 font-mono text-[10px] tracking-[0.12em] text-teal uppercase">
                IA
              </span>
            ) : null}
            <p className="ml-auto font-mono text-[10px] tracking-[0.16em] text-apagado uppercase tabular-nums">
              {aceitas.length} de {correcoes.length} {correcoes.length === 1 ? 'correção' : 'correções'}
            </p>
          </header>

          <div className="relative">
            <p className="max-h-64 overflow-y-auto px-4 py-4 text-[15px] leading-[1.9] break-words whitespace-pre-wrap">
              {segmentar(dados.resultado.original, correcoes).map((s, i) =>
                s.correcao ? (
                  <Marca
                    key={`${rodada}-${i}`}
                    correcao={s.correcao}
                    aceita={!rejeitadas.has(s.indice!)}
                    atraso={0.18 + (s.indice ?? 0) * ATRASO_POR_MARCA}
                    alternar={() => alternar(s.indice!)}
                  />
                ) : (
                  <span key={`${rodada}-${i}`}>{s.texto}</span>
                ),
              )}
            </p>
            <span
              aria-hidden
              className="pointer-events-none absolute inset-x-0 bottom-0 h-4 bg-linear-to-t from-folha to-transparent"
            />
          </div>

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
