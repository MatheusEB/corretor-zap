import { useLayoutEffect, useRef, useState } from 'react'
import { AnimatePresence, motion } from 'motion/react'
import { BanIcon, BookPlusIcon, CheckIcon } from 'lucide-react'

import { Button } from '@/components/ui/button'
import type { Correcao } from '@/lib/api'
import { segmentar } from '@/lib/correcoes'

const ENTRELINHA = 30
const RESPIRO = 28
const LARGURA_CARTAO = 300

type Props = {
  texto: string
  mudarTexto: (texto: string) => void
  correcoes: Correcao[]
  ativa: number | null
  definirAtiva: (indice: number | null) => void
  aceitar: (c: Correcao) => void
  ignorarPalavra: (c: Correcao) => void
  desativarRegra: (c: Correcao) => void
}

/**
 * Textarea com um "espelho" atrás, na mesma fonte e medidas: o espelho tem o texto transparente e desenha
 * os sublinhados de revisor, então eles ficam exatamente embaixo das palavras digitadas.
 */
export function EditorRevisao({
  texto,
  mudarTexto,
  correcoes,
  ativa,
  definirAtiva,
  aceitar,
  ignorarPalavra,
  desativarRegra,
}: Props) {
  const folhaRef = useRef<HTMLDivElement>(null)
  const espelhoRef = useRef<HTMLDivElement>(null)
  const areaRef = useRef<HTMLTextAreaElement>(null)
  const fecharRef = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)
  const [posicao, setPosicao] = useState<{ top: number; left: number } | null>(null)

  // a folha cresce com o texto, então o espelho nunca precisa rolar
  useLayoutEffect(() => {
    const area = areaRef.current
    if (!area) return
    area.style.height = '0px'
    area.style.height = `${Math.max(area.scrollHeight, ENTRELINHA * 8 + RESPIRO * 2)}px`
  }, [texto])

  useLayoutEffect(() => {
    if (ativa === null) {
      setPosicao(null)
      return
    }
    const marca = espelhoRef.current?.querySelector<HTMLElement>(`[data-indice="${ativa}"]`)
    const folha = folhaRef.current
    if (!marca || !folha) return
    const maximo = folha.clientWidth - LARGURA_CARTAO - 12
    setPosicao({
      top: marca.offsetTop + marca.offsetHeight + 10,
      left: Math.max(12, Math.min(marca.offsetLeft - 16, maximo)),
    })
  }, [ativa, correcoes, texto])

  function marcaSobOPonteiro(x: number, y: number) {
    const marcas = espelhoRef.current?.querySelectorAll<HTMLElement>('[data-indice]') ?? []
    for (const marca of marcas) {
      for (const r of marca.getClientRects()) {
        if (x >= r.left && x <= r.right && y >= r.top - 2 && y <= r.bottom + 4) return Number(marca.dataset.indice)
      }
    }
    return null
  }

  function abrir(indice: number | null) {
    clearTimeout(fecharRef.current)
    if (indice !== null) definirAtiva(indice)
  }

  function fecharDepois() {
    clearTimeout(fecharRef.current)
    fecharRef.current = setTimeout(() => definirAtiva(null), 220)
  }

  function cursorNaMarca() {
    const pos = areaRef.current?.selectionStart ?? -1
    const indice = correcoes.findIndex((c) => pos >= c.inicio && pos <= c.fim)
    if (indice >= 0) abrir(indice)
    else definirAtiva(null)
  }

  const aberta = ativa !== null ? correcoes[ativa] : undefined

  return (
    <div
      ref={folhaRef}
      className="folha-pautada relative rounded-xl border border-linha transition-shadow focus-within:border-linha-forte focus-within:ring-3 focus-within:ring-teal/25 shadow-[0_1px_0_var(--linha),0_18px_40px_-28px_color-mix(in_oklab,var(--tinta)_45%,transparent)]"
      style={{ '--entrelinha': `${ENTRELINHA}px`, '--respiro': `${RESPIRO}px` } as React.CSSProperties}
    >
      <div
        ref={espelhoRef}
        aria-hidden
        className="pointer-events-none absolute inset-0 px-9 text-[17px] break-words whitespace-pre-wrap text-transparent"
        style={{ lineHeight: `${ENTRELINHA}px`, paddingTop: RESPIRO, paddingBottom: RESPIRO }}
      >
        {segmentar(texto, correcoes).map((s, i) =>
          s.correcao ? (
            <span
              key={`${s.correcao.inicio}-${s.correcao.trecho}`}
              data-indice={s.indice}
              data-n={(s.indice ?? 0) + 1}
              data-ativa={s.indice === ativa}
              className="marca-erro"
              style={{ '--atraso': `${(s.indice ?? 0) * 70}ms` } as React.CSSProperties}
            >
              {s.texto}
            </span>
          ) : (
            <span key={i}>{s.texto}</span>
          ),
        )}
        {'​'}
      </div>

      {/* o foco aparece na folha inteira (focus-within:ring), não só no textarea */}
      <textarea
        ref={areaRef}
        value={texto}
        onChange={(e) => {
          mudarTexto(e.target.value)
          definirAtiva(null)
        }}
        onMouseUp={cursorNaMarca}
        onKeyUp={(e) => {
          if (e.key.startsWith('Arrow') || e.key === 'Home' || e.key === 'End') cursorNaMarca()
        }}
        onMouseMove={(e) => {
          const indice = marcaSobOPonteiro(e.clientX, e.clientY)
          if (indice !== null) abrir(indice)
          else if (ativa !== null) fecharDepois()
        }}
        onMouseLeave={fecharDepois}
        spellCheck={false}
        maxLength={20_000}
        placeholder="Escreva aqui como escreveria no WhatsApp…"
        aria-label="Texto para revisar"
        className="relative z-10 block w-full resize-none bg-transparent px-9 text-[17px] text-tinta caret-teal outline-none placeholder:text-apagado/80"
        style={{ lineHeight: `${ENTRELINHA}px`, paddingTop: RESPIRO, paddingBottom: RESPIRO }}
      />

      <AnimatePresence>
        {aberta && posicao ? (
          <motion.div
            key={`${aberta.inicio}-${aberta.regra}`}
            role="dialog"
            aria-label={`Sugestão para ${aberta.trecho}`}
            initial={{ opacity: 0, y: -6, scale: 0.98 }}
            animate={{ opacity: 1, y: 0, scale: 1 }}
            exit={{ opacity: 0, y: -4, transition: { duration: 0.12 } }}
            transition={{ type: 'spring', stiffness: 520, damping: 34 }}
            onMouseEnter={() => abrir(null)}
            onMouseLeave={fecharDepois}
            className="absolute z-20 rounded-lg border border-linha-forte bg-folha p-4 shadow-[0_24px_48px_-24px_color-mix(in_oklab,var(--tinta)_55%,transparent)]"
            style={{ top: posicao.top, left: posicao.left, width: LARGURA_CARTAO }}
          >
            <p className="font-mono text-[10px] tracking-[0.16em] text-apagado uppercase">{aberta.descricaoRegra}</p>
            <p className="mt-2 flex items-baseline gap-2.5">
              <del className="text-base text-apagado decoration-vermelhao">{aberta.trecho}</del>
              <span className="text-apagado" aria-hidden>
                →
              </span>
              <span className="font-serif text-[1.75rem] leading-none text-tinta">{aberta.sugestao}</span>
            </p>
            <p className="mt-2 text-[13px] leading-snug text-grafite">{aberta.mensagem}</p>
            <div className="mt-3.5 flex flex-wrap gap-1.5">
              <Button size="sm" onClick={() => aceitar(aberta)}>
                <CheckIcon data-icon="inline-start" /> Aceitar
              </Button>
              <Button size="sm" variant="outline" onClick={() => ignorarPalavra(aberta)}>
                <BookPlusIcon data-icon="inline-start" /> Ignorar palavra
              </Button>
              <Button size="sm" variant="ghost" onClick={() => desativarRegra(aberta)} title={aberta.regra}>
                <BanIcon data-icon="inline-start" /> Desativar regra
              </Button>
            </div>
          </motion.div>
        ) : null}
      </AnimatePresence>
    </div>
  )
}
