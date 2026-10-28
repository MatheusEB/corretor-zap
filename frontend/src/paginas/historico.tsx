import { useEffect, useState } from 'react'
import { motion } from 'motion/react'

import { Cabecalho } from '@/components/cabecalho'
import { Tecla } from '@/components/tecla'
import { Button } from '@/components/ui/button'
import { api, type Configuracao, type RegistroHistorico } from '@/lib/api'
import { teclasDoAtalho } from '@/lib/atalho'
import { diferenca, segmentar } from '@/lib/correcoes'

const HORA = new Intl.DateTimeFormat('pt-BR', { hour: '2-digit', minute: '2-digit', second: '2-digit' })
const DIA = new Intl.DateTimeFormat('pt-BR', { weekday: 'long', day: 'numeric', month: 'long' })
const INTERVALO_MS = 3000

export function PaginaHistorico({ config }: { config: Configuracao }) {
  const [registros, setRegistros] = useState<RegistroHistorico[] | null>(null)

  useEffect(() => {
    let montado = true
    const carregar = () => {
      if (document.hidden) return
      api
        .historico()
        .then((r) => montado && setRegistros(r))
        .catch(() => {})
    }
    carregar()
    const intervalo = setInterval(carregar, INTERVALO_MS)
    return () => {
      montado = false
      clearInterval(intervalo)
    }
  }, [])

  async function limpar() {
    await api.limparHistorico()
    setRegistros([])
  }

  const grupos = agruparPorDia(registros ?? [])

  return (
    <div className="flex flex-col gap-8">
      <Cabecalho
        indice="04 · Histórico"
        titulo="Histórico"
        descricao="As últimas 50 mensagens corrigidas pelo atalho. Fica só na memória e some quando o app fecha."
        acao={
          registros?.length ? (
            <Button variant="outline" onClick={limpar}>
              Limpar
            </Button>
          ) : null
        }
      />

      {registros === null ? null : registros.length === 0 ? (
        <Vazio atalho={config.atalho} />
      ) : (
        grupos.map(([dia, itens]) => (
          <section key={dia} aria-label={dia} className="flex flex-col gap-1">
            <h2 className="mb-3 font-serif text-xl first-letter:uppercase">{dia}</h2>
            <ol className="relative flex flex-col">
              <motion.span
                aria-hidden
                className="absolute top-2 bottom-2 left-[71px] w-px origin-top bg-linha-forte"
                initial={{ scaleY: 0 }}
                animate={{ scaleY: 1 }}
                transition={{ duration: 0.6, ease: [0.2, 0.7, 0.2, 1] }}
              />
              {itens.map((r, i) => (
                <motion.li
                  key={r.quando}
                  initial={{ opacity: 0, y: 6 }}
                  animate={{ opacity: 1, y: 0, transition: { delay: Math.min(i, 8) * 0.04 } }}
                  className="grid grid-cols-[60px_24px_minmax(0,1fr)] items-start gap-x-0 py-3"
                >
                  <time dateTime={r.quando} className="pt-0.5 font-mono text-[11px] text-apagado tabular-nums">
                    {HORA.format(new Date(r.quando))}
                  </time>
                  <span aria-hidden className="relative mt-1.5 ml-[7px] size-2 rounded-full border border-tinta bg-papel" />
                  <div className="min-w-0">
                    <p className="text-[15px] leading-relaxed break-words">
                      {segmentar(r.original, r.correcoes).map((s, j) =>
                        s.correcao ? <TrocaInline key={j} trecho={s.texto} sugestao={s.correcao.sugestao} /> : <span key={j}>{s.texto}</span>,
                      )}
                    </p>
                    <p className="mt-1 font-mono text-[10px] tracking-wider text-apagado uppercase">
                      {r.correcoes.length} {r.correcoes.length === 1 ? 'correção' : 'correções'}
                    </p>
                  </div>
                </motion.li>
              ))}
            </ol>
          </section>
        ))
      )}
    </div>
  )
}

function TrocaInline({ trecho, sugestao }: { trecho: string; sugestao: string }) {
  const { prefixo, removido, inserido, sufixo } = diferenca(trecho, sugestao)
  return (
    <span>
      {prefixo}
      {removido ? (
        <>
          <del className="text-apagado decoration-vermelhao">{removido}</del>{' '}
        </>
      ) : null}
      <ins className="font-medium text-teal no-underline">{inserido}</ins>
      {sufixo}
    </span>
  )
}

function Vazio({ atalho }: { atalho: string }) {
  return (
    <div className="flex flex-col items-center gap-5 py-16 text-center">
      <svg viewBox="0 0 120 48" aria-hidden className="w-40 text-linha-forte">
        <path d="M6 18h70" stroke="currentColor" strokeWidth="3" strokeLinecap="round" />
        <path d="M6 32h48" stroke="currentColor" strokeWidth="3" strokeLinecap="round" />
        <motion.path
          d="M62 34c4-5 8-5 12 0s8 5 12 0 8-5 12 0 8 5 12 0"
          fill="none"
          className="stroke-vermelhao"
          strokeWidth="2.5"
          strokeLinecap="round"
          initial={{ pathLength: 0 }}
          animate={{ pathLength: 1 }}
          transition={{ duration: 1.1, ease: 'easeInOut', delay: 0.2 }}
        />
      </svg>
      <div>
        <p className="font-serif text-3xl">Nada por aqui ainda</p>
        <p className="mt-2 flex flex-wrap items-center justify-center gap-1.5 text-sm text-grafite">
          Escreva no WhatsApp, aperte
          {teclasDoAtalho(atalho).map((t) => (
            <Tecla key={t}>{t}</Tecla>
          ))}
          e a correção aparece aqui.
        </p>
      </div>
    </div>
  )
}

function agruparPorDia(registros: RegistroHistorico[]): [string, RegistroHistorico[]][] {
  const grupos = new Map<string, RegistroHistorico[]>()
  for (const r of registros) {
    const dia = DIA.format(new Date(r.quando))
    const lista = grupos.get(dia)
    if (lista) lista.push(r)
    else grupos.set(dia, [r])
  }
  return [...grupos]
}
