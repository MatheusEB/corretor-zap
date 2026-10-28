import { useCallback, useEffect, useState } from 'react'
import { AnimatePresence, MotionConfig, motion } from 'motion/react'
import { toast } from 'sonner'

import { BarraLateral, PAGINAS, type Pagina } from '@/components/barra-lateral'
import { Logo } from '@/components/logo'
import { Button } from '@/components/ui/button'
import { Toaster } from '@/components/ui/sonner'
import { Spinner } from '@/components/ui/spinner'
import { TooltipProvider } from '@/components/ui/tooltip'
import { api, type Atualizar, type Configuracao, type Status } from '@/lib/api'
import { PaginaAtalho } from '@/paginas/atalho'
import { PaginaDicionario } from '@/paginas/dicionario'
import { PaginaHistorico } from '@/paginas/historico'
import { PaginaInicio } from '@/paginas/inicio'

export default function App() {
  const [pagina, setPagina] = useState<Pagina>('inicio')
  const [config, setConfig] = useState<Configuracao | null>(null)
  const [status, setStatus] = useState<Status | null>(null)
  const [semConexao, setSemConexao] = useState(false)

  const carregar = useCallback(() => {
    setSemConexao(false)
    Promise.all([api.config(), api.status()])
      .then(([c, s]) => {
        setConfig(c)
        setStatus(s)
      })
      .catch(() => setSemConexao(true))
  }, [])

  useEffect(carregar, [carregar])

  // Ctrl+1..4 troca de seção, como num app de desktop
  useEffect(() => {
    const aoTeclar = (e: KeyboardEvent) => {
      if (!e.ctrlKey || e.shiftKey || e.altKey) return
      const destino = PAGINAS[Number(e.key) - 1]
      if (destino) {
        e.preventDefault()
        setPagina(destino.id)
      }
    }
    window.addEventListener('keydown', aoTeclar)
    return () => window.removeEventListener('keydown', aoTeclar)
  }, [])

  // toda alteração passa por aqui para a tela refletir o que o servidor salvou
  const atualizar = useCallback<Atualizar>(async (operacao) => {
    try {
      setConfig(await operacao())
      return true
    } catch (e) {
      toast.error((e as Error).message)
      return false
    }
  }, [])

  if (semConexao) return <TelaSemConexao tentarDeNovo={carregar} />
  if (!config) return <TelaCarregando />

  return (
    <MotionConfig reducedMotion="user">
      <TooltipProvider>
        <div className="flex h-full overflow-hidden">
          <BarraLateral
            atual={pagina}
            irPara={setPagina}
            config={config}
            status={status}
            alternarAtivo={(ativo) => atualizar(() => api.salvarConfig({ ...config, ativo }))}
          />
          <main className="min-w-0 flex-1 overflow-y-auto">
            <AnimatePresence mode="wait" initial={false}>
              <motion.div
                key={pagina}
                initial={{ opacity: 0, y: 8 }}
                animate={{ opacity: 1, y: 0 }}
                exit={{ opacity: 0, y: -4 }}
                transition={{ duration: 0.18, ease: [0.2, 0.7, 0.2, 1] }}
                className="mx-auto max-w-5xl px-10 pt-10 pb-16"
              >
                {pagina === 'inicio' ? (
                  <PaginaInicio config={config} atualizar={atualizar} />
                ) : pagina === 'atalho' ? (
                  <PaginaAtalho config={config} atualizar={atualizar} />
                ) : pagina === 'dicionario' ? (
                  <PaginaDicionario config={config} atualizar={atualizar} />
                ) : (
                  <PaginaHistorico config={config} />
                )}
              </motion.div>
            </AnimatePresence>
          </main>
        </div>
        <Toaster position="bottom-right" />
      </TooltipProvider>
    </MotionConfig>
  )
}

function TelaCarregando() {
  return (
    <div className="grid h-full place-items-center">
      <div className="flex flex-col items-center gap-4">
        <Logo className="size-12" />
        <Spinner className="text-apagado" />
      </div>
    </div>
  )
}

function TelaSemConexao({ tentarDeNovo }: { tentarDeNovo: () => void }) {
  return (
    <div className="grid h-full place-items-center px-6">
      <div className="flex max-w-sm flex-col items-center gap-4 text-center">
        <Logo className="size-12" />
        <p className="font-serif text-3xl">O corretor não respondeu</p>
        <p className="text-sm text-grafite">Ele roda nesta máquina, na porta 8765. Se acabou de abrir, espere uns segundos.</p>
        <Button onClick={tentarDeNovo}>Tentar de novo</Button>
      </div>
    </div>
  )
}
