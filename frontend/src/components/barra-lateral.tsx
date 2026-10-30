import { motion } from 'motion/react'
import { MonitorIcon, MoonIcon, SunIcon } from 'lucide-react'

import { AvisoAtualizacao } from '@/components/aviso-atualizacao'
import { Logo } from '@/components/logo'
import { Tecla } from '@/components/tecla'
import { Switch } from '@/components/ui/switch'
import type { Configuracao, Status } from '@/lib/api'
import { teclasDoAtalho } from '@/lib/atalho'
import { definirTema, usarTema, type PreferenciaTema } from '@/lib/tema'
import { usarAtualizacao } from '@/lib/usar-atualizacao'
import { cn } from '@/lib/utils'

export type Pagina = 'inicio' | 'atalho' | 'dicionario' | 'historico' | 'ia'

export const PAGINAS: { id: Pagina; nome: string }[] = [
  { id: 'inicio', nome: 'Início' },
  { id: 'atalho', nome: 'Atalho' },
  { id: 'dicionario', nome: 'Dicionário' },
  { id: 'historico', nome: 'Histórico' },
  { id: 'ia', nome: 'IA' },
]

const TEMAS: { id: PreferenciaTema; nome: string; Icone: typeof SunIcon }[] = [
  { id: 'sistema', nome: 'Seguir o Windows', Icone: MonitorIcon },
  { id: 'claro', nome: 'Claro', Icone: SunIcon },
  { id: 'escuro', nome: 'Escuro', Icone: MoonIcon },
]

type Props = {
  atual: Pagina
  irPara: (pagina: Pagina) => void
  config: Configuracao
  status: Status | null
  alternarAtivo: (ativo: boolean) => void
}

export function BarraLateral({ atual, irPara, config, status, alternarAtivo }: Props) {
  const tema = usarTema()
  const { estado: atualizacao, setEstado: setAtualizacao } = usarAtualizacao()
  const ouvindo = config.ativo && status?.atalhoRegistrado === true

  return (
    <aside className="flex h-full w-[232px] shrink-0 flex-col border-r border-linha bg-papel">
      <div className="flex items-center gap-2.5 px-5 pt-6 pb-8">
        <Logo />
        <p className="font-serif text-[1.45rem] leading-none tracking-tight">
          Corretor <em className="text-vermelhao">Zap</em>
        </p>
      </div>

      <nav aria-label="Seções" className="flex flex-col gap-0.5 px-3">
        {PAGINAS.map((p, i) => {
          const ativa = p.id === atual
          return (
            <button
              key={p.id}
              type="button"
              onClick={() => irPara(p.id)}
              aria-current={ativa ? 'page' : undefined}
              className={cn(
                'group relative flex items-center gap-3 rounded-md px-3 py-2 text-left text-[15px] transition-colors',
                'focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none',
                ativa ? 'text-tinta' : 'text-grafite hover:text-tinta',
              )}
            >
              {ativa ? (
                <motion.span
                  layoutId="indicador-pagina"
                  className="absolute inset-0 rounded-md bg-secondary"
                  transition={{ type: 'spring', stiffness: 520, damping: 40 }}
                />
              ) : null}
              <span className="relative font-mono text-[11px] text-apagado">0{i + 1}</span>
              <span className="relative flex-1">{p.nome}</span>
              <span className="relative font-mono text-[10px] text-apagado opacity-0 transition-opacity group-hover:opacity-100">
                Ctrl {i + 1}
              </span>
            </button>
          )
        })}
      </nav>

      <div className="mt-auto flex flex-col gap-4 px-5 pb-5">
        <AvisoAtualizacao estado={atualizacao} setEstado={setAtualizacao} />
        <div className="rounded-lg border border-linha bg-folha p-3.5">
          <div className="flex items-center justify-between gap-2">
            <p className="flex items-center gap-2 text-sm font-medium">
              <span className="relative flex size-2" aria-hidden>
                {ouvindo ? (
                  <span className="absolute inline-flex size-full animate-ping rounded-full bg-teal opacity-50 motion-reduce:animate-none" />
                ) : null}
                <span className={cn('relative inline-flex size-2 rounded-full', ouvindo ? 'bg-teal' : 'bg-apagado')} />
              </span>
              {status && !status.atalhoRegistrado ? 'Atalho indisponível' : ouvindo ? 'Ouvindo' : 'Pausado'}
            </p>
            <Switch
              checked={config.ativo}
              onCheckedChange={alternarAtivo}
              aria-label={config.ativo ? 'Pausar a correção' : 'Retomar a correção'}
            />
          </div>
          <div className="mt-3 flex items-center gap-1" aria-label={`Atalho: ${teclasDoAtalho(config.atalho).join(' + ')}`}>
            {teclasDoAtalho(config.atalho).map((tecla) => (
              <Tecla key={tecla}>{tecla}</Tecla>
            ))}
          </div>
        </div>

        <div className="flex items-center justify-between">
          <div role="radiogroup" aria-label="Tema" className="flex rounded-md border border-linha p-0.5">
            {TEMAS.map(({ id, nome, Icone }) => (
              <button
                key={id}
                type="button"
                role="radio"
                aria-checked={tema === id}
                aria-label={nome}
                title={nome}
                onClick={() => definirTema(id)}
                className={cn(
                  'grid size-7 place-items-center rounded-[5px] transition-colors focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none',
                  tema === id ? 'bg-secondary text-tinta' : 'text-apagado hover:text-tinta',
                )}
              >
                <Icone className="size-3.5" />
              </button>
            ))}
          </div>
          {atualizacao?.atual ? (
            <span className="font-mono text-[10px] text-apagado" data-testid="versao-app">
              v{atualizacao.atual}
            </span>
          ) : null}
        </div>
      </div>
    </aside>
  )
}
