import { AnimatePresence, motion } from 'motion/react'
import { BookPlusIcon, CheckIcon } from 'lucide-react'

import { Tecla } from '@/components/tecla'
import { Button } from '@/components/ui/button'
import { Spinner } from '@/components/ui/spinner'
import type { Correcao } from '@/lib/api'
import { diferenca } from '@/lib/correcoes'
import { cn } from '@/lib/utils'

type Props = {
  correcoes: Correcao[]
  vazio: boolean
  verificando: boolean
  ativa: number | null
  definirAtiva: (indice: number | null) => void
  aceitar: (c: Correcao) => void
  ignorarPalavra: (c: Correcao) => void
  aplicarTodas: () => void
}

/** As marcações numeradas na margem, como um revisor anota uma prova. */
export function NotasMargem({
  correcoes,
  vazio,
  verificando,
  ativa,
  definirAtiva,
  aceitar,
  ignorarPalavra,
  aplicarTodas,
}: Props) {
  return (
    <section aria-label="Marcações" className="flex min-h-0 flex-col">
      <header className="flex items-baseline justify-between gap-2 border-b border-linha pb-3">
        <h2 className="font-serif text-2xl leading-none">
          {correcoes.length === 0 ? 'Margem' : `${correcoes.length} ${correcoes.length === 1 ? 'marcação' : 'marcações'}`}
        </h2>
        <span
          className={cn(
            'flex items-center gap-1.5 font-mono text-[10px] tracking-wider text-apagado uppercase transition-opacity',
            verificando ? 'opacity-100' : 'opacity-0',
          )}
          aria-live="polite"
        >
          <Spinner className="size-3" /> revisando
        </span>
      </header>

      {correcoes.length === 0 ? (
        <SemMarcacoes vazio={vazio} verificando={verificando} />
      ) : (
        <>
          <ol className="-mx-2 mt-2 flex flex-col">
            <AnimatePresence initial={false}>
              {correcoes.map((c, i) => (
                <motion.li
                  key={`${c.inicio}-${c.trecho}`}
                  layout="position"
                  initial={{ opacity: 0, x: 10 }}
                  animate={{ opacity: 1, x: 0, transition: { delay: i * 0.05 } }}
                  exit={{ opacity: 0, x: -10, transition: { duration: 0.15 } }}
                  onMouseEnter={() => definirAtiva(i)}
                  onMouseLeave={() => definirAtiva(null)}
                  className={cn(
                    'group flex items-start gap-3 rounded-md px-2 py-2.5 transition-colors',
                    ativa === i && 'bg-vermelhao-suave/60',
                  )}
                >
                  <span className="mt-0.5 grid size-5 shrink-0 place-items-center rounded-full border border-vermelhao font-mono text-[10px] text-vermelhao">
                    {i + 1}
                  </span>
                  <div className="min-w-0 flex-1">
                    <p className="truncate text-[15px]">
                      <Troca correcao={c} />
                    </p>
                    <p className="mt-0.5 truncate text-xs text-apagado">
                      {c.descricaoRegra}
                      {c.certeza ? null : ' · confira'}
                    </p>
                  </div>
                  <div className="flex gap-0.5 opacity-60 transition-opacity group-focus-within:opacity-100 group-hover:opacity-100">
                    <Button size="icon-xs" variant="ghost" aria-label={`Aceitar ${c.sugestao}`} onClick={() => aceitar(c)}>
                      <CheckIcon />
                    </Button>
                    <Button
                      size="icon-xs"
                      variant="ghost"
                      aria-label={`Ignorar ${c.trecho}`}
                      onClick={() => ignorarPalavra(c)}
                    >
                      <BookPlusIcon />
                    </Button>
                  </div>
                </motion.li>
              ))}
            </AnimatePresence>
          </ol>
          <div className="mt-3 flex items-center gap-2 border-t border-linha pt-3">
            <Button onClick={aplicarTodas}>Aplicar todas</Button>
            <span className="flex items-center gap-1 text-xs text-apagado">
              <Tecla>Ctrl</Tecla>
              <Tecla>Enter</Tecla>
            </span>
          </div>
        </>
      )}
    </section>
  )
}

/** "voce → você" ou, quando só entra pontuação, "festa, mas" com o sinal destacado. */
function Troca({ correcao }: { correcao: Correcao }) {
  const { prefixo, removido, inserido, sufixo } = diferenca(correcao.trecho, correcao.sugestao)
  if (!removido) {
    return (
      <>
        {prefixo}
        <ins className="rounded-[3px] bg-teal-suave px-[2px] font-semibold text-teal no-underline">{inserido}</ins>
        {sufixo}
      </>
    )
  }
  return (
    <>
      <del className="text-apagado decoration-vermelhao">{correcao.trecho}</del>
      <span className="mx-1.5 text-apagado" aria-hidden>
        →
      </span>
      <span className="font-medium text-tinta">{correcao.sugestao}</span>
    </>
  )
}

function SemMarcacoes({ vazio, verificando }: { vazio: boolean; verificando: boolean }) {
  if (vazio) {
    return <p className="mt-4 text-sm text-apagado">As marcações aparecem aqui enquanto você escreve.</p>
  }
  if (verificando) {
    return null
  }
  return (
    <div className="mt-8 flex flex-col items-center gap-3 text-center">
      <motion.span
        initial={{ scale: 1.6, rotate: -14, opacity: 0 }}
        animate={{ scale: 1, rotate: -6, opacity: 1 }}
        transition={{ type: 'spring', stiffness: 380, damping: 18 }}
        className="rounded-md border-2 border-teal px-3 py-1 font-mono text-xs font-medium tracking-[0.2em] text-teal uppercase"
      >
        Revisado
      </motion.span>
      <p className="text-sm text-apagado">Nada para corrigir.</p>
    </div>
  )
}
