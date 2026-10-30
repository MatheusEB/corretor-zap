import { useEffect, useState } from 'react'
import { AnimatePresence, motion } from 'motion/react'
import { ArrowDownToLineIcon } from 'lucide-react'
import { toast } from 'sonner'

import { Button } from '@/components/ui/button'
import { Spinner } from '@/components/ui/spinner'
import { api, type EstadoAtualizacao } from '@/lib/api'

type Props = {
  estado: EstadoAtualizacao | null
  setEstado: (estado: EstadoAtualizacao) => void
}

/** Cartão na barra lateral quando há versão nova: um clique baixa, troca e abre o app de novo. */
export function AvisoAtualizacao({ estado, setEstado }: Props) {
  const [vendoNovidades, setVendoNovidades] = useState(false)

  // abriu já na versão nova: avisa uma vez
  useEffect(() => {
    if (!estado?.acabouDeAtualizar || !estado.atual) return
    toast.success(`Atualizado para a versão ${estado.atual}`)
    api.avisoDeAtualizacaoVisto().catch(() => {})
  }, [estado?.acabouDeAtualizar, estado?.atual])

  const instalar = () =>
    api
      .instalarAtualizacao()
      .then(setEstado)
      .catch((e: Error) => toast.error(e.message))

  const fase = estado?.fase
  // erro sem versão nova (sem internet ao procurar) não merece cartão; a página Atalho mostra
  const visivel =
    fase === 'DISPONIVEL' || fase === 'BAIXANDO' || fase === 'INSTALANDO' || (fase === 'ERRO' && estado?.nova)

  return (
    <AnimatePresence initial={false}>
      {visivel && estado ? (
        <motion.div
          key="aviso-atualizacao"
          initial={{ opacity: 0, y: 6 }}
          animate={{ opacity: 1, y: 0 }}
          exit={{ opacity: 0, y: 6 }}
          transition={{ duration: 0.18 }}
          className="rounded-lg border border-teal/40 bg-folha p-3.5"
          data-testid="aviso-atualizacao"
          role="status"
        >
          <p className="flex items-center gap-2 text-sm font-medium">
            <ArrowDownToLineIcon className="size-3.5 text-teal" aria-hidden />
            Versão {estado.nova} disponível
          </p>

          {fase === 'BAIXANDO' ? (
            <div className="mt-3">
              <div
                className="h-1.5 overflow-hidden rounded-full bg-secondary"
                role="progressbar"
                aria-valuenow={estado.progresso}
                aria-valuemin={0}
                aria-valuemax={100}
                aria-label="Baixando a versão nova"
              >
                <div className="h-full bg-teal transition-[width] duration-300" style={{ width: `${estado.progresso}%` }} />
              </div>
              <p className="mt-1.5 text-xs text-apagado">Baixando… {estado.progresso}%</p>
            </div>
          ) : fase === 'INSTALANDO' ? (
            <p className="mt-2 flex items-center gap-2 text-xs text-apagado">
              <Spinner className="size-3" /> Instalando. O app fecha e abre de novo sozinho.
            </p>
          ) : (
            <>
              {fase === 'ERRO' && estado.erro ? <p className="mt-1.5 text-xs text-vermelhao">{estado.erro}</p> : null}
              <div className="mt-3 flex flex-wrap items-center gap-2">
                <Button size="sm" onClick={instalar}>
                  {fase === 'ERRO' ? 'Tentar de novo' : 'Atualizar'}
                </Button>
                {estado.novidades ? (
                  <Button size="sm" variant="ghost" onClick={() => setVendoNovidades((v) => !v)} aria-expanded={vendoNovidades}>
                    Novidades
                  </Button>
                ) : null}
              </div>
              {vendoNovidades && estado.novidades ? (
                <div className="mt-3 max-h-56 overflow-y-auto border-t border-linha pt-2 text-xs leading-relaxed whitespace-pre-wrap text-grafite">
                  {textoSimples(estado.novidades)}
                </div>
              ) : null}
            </>
          )}
        </motion.div>
      ) : null}
    </AnimatePresence>
  )
}

/** As notas da release vêm em Markdown do GitHub; aqui só interessa o texto. */
function textoSimples(markdown: string) {
  return markdown
    .replace(/<[^>]+>/g, '')
    // o cartão já diz que é novidade
    .replace(/^#{1,6}\s*Novidades\s*$/im, '')
    .replace(/^>\s*\[!\w+\]\s*$/gm, '')
    .replace(/^#{1,6}\s*/gm, '')
    .replace(/^>\s?/gm, '')
    .replace(/\*\*(.+?)\*\*/g, '$1')
    .replace(/`([^`]+)`/g, '$1')
    .replace(/\[([^\]]+)\]\([^)]+\)/g, '$1')
    .replace(/\n{3,}/g, '\n\n')
    .trim()
}
