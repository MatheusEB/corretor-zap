import { useEffect, useState } from 'react'
import { AnimatePresence, motion } from 'motion/react'
import { XIcon } from 'lucide-react'

import { Cabecalho } from '@/components/cabecalho'
import { Tecla } from '@/components/tecla'
import { Button } from '@/components/ui/button'
import { Switch } from '@/components/ui/switch'
import { api, type Atualizar, type Configuracao, type Sistema } from '@/lib/api'
import { atalhoDoEvento, teclasDoAtalho } from '@/lib/atalho'
import { cn } from '@/lib/utils'

type Props = { config: Configuracao; atualizar: Atualizar }

const NOME_DA_TECLA: Record<string, string> = { Control: 'Ctrl', Shift: 'Shift', Alt: 'Alt', ' ': 'Espaço' }

// regras de pontuação do próprio corretor (o LanguageTool quase não sugere vírgula e ponto em português)
const REGRAS_DE_VIRGULA = ['CZ_VIRGULA_VOCATIVO', 'CZ_VIRGULA_ADVERSATIVA', 'CZ_VIRGULA_ORACAO', 'CZ_VIRGULA_ENUMERACAO']
const REGRAS_DE_FIM = ['CZ_PONTO_FINAL', 'CZ_INTERROGACAO', 'CZ_MAIUSCULA']

export function PaginaAtalho({ config, atualizar }: Props) {
  const salvar = (mudancas: Partial<Configuracao>) => atualizar(() => api.salvarConfig({ ...config, ...mudancas }))
  const [gravando, setGravando] = useState(false)
  const apertadas = usarTeclasApertadas()
  const [sistema, setSistema] = useState<Sistema | null>(null)

  useEffect(() => {
    api.sistema().then(setSistema).catch(() => {})
  }, [])

  useEffect(() => {
    if (!gravando) return
    const aoTeclar = (e: KeyboardEvent) => {
      e.preventDefault()
      if (e.key === 'Escape') {
        setGravando(false)
        return
      }
      const atalho = atalhoDoEvento(e)
      if (!atalho) return
      setGravando(false)
      salvar({ atalho })
    }
    window.addEventListener('keydown', aoTeclar)
    return () => window.removeEventListener('keydown', aoTeclar)
  }, [gravando, config, atualizar])

  const teclas = teclasDoAtalho(config.atalho)
  const desativadas = new Set(config.regrasDesativadas)
  const grupoLigado = (regras: string[]) => regras.every((r) => !desativadas.has(r))
  const ligarGrupo = (regras: string[], ligar: boolean) => {
    const proximas = new Set(desativadas)
    for (const r of regras) {
      if (ligar) proximas.delete(r)
      else proximas.add(r)
    }
    salvar({ regrasDesativadas: [...proximas] })
  }
  const regrasDoLanguageTool = config.regrasDesativadas.filter((r) => !r.startsWith('CZ_'))

  return (
    <div className="flex flex-col gap-10">
      <Cabecalho
        indice="02 · Atalho"
        titulo="Atalho e comportamento"
        descricao="Como e quando o corretor entra em ação. Tudo é salvo na hora."
      />

      <section aria-labelledby="titulo-combinacao" className="flex flex-col items-center gap-5 py-2">
        <h2 id="titulo-combinacao" className="sr-only">
          Combinação de teclas
        </h2>
        <div
          className={cn(
            'flex min-h-28 items-center justify-center gap-3 rounded-2xl border border-dashed px-10 py-6 transition-colors',
            gravando ? 'border-teal bg-teal-suave/40' : 'border-transparent',
          )}
        >
          <AnimatePresence mode="popLayout" initial={false}>
            {gravando ? (
              <motion.p
                key="gravando"
                initial={{ opacity: 0 }}
                animate={{ opacity: 1 }}
                exit={{ opacity: 0 }}
                className="font-serif text-3xl text-teal italic"
              >
                Aperte a nova combinação…
              </motion.p>
            ) : (
              teclas.map((tecla, i) => (
                <motion.span
                  key={tecla}
                  initial={{ opacity: 0, y: 8 }}
                  animate={{ opacity: 1, y: 0, transition: { delay: i * 0.06 } }}
                  className="flex items-center gap-3"
                >
                  {i > 0 ? <span className="font-serif text-2xl text-apagado">+</span> : null}
                  <Tecla tamanho="lg" apertada={apertadas.has(tecla)}>
                    {tecla}
                  </Tecla>
                </motion.span>
              ))
            )}
          </AnimatePresence>
        </div>
        <div className="flex items-center gap-3">
          <Button variant={gravando ? 'outline' : 'default'} onClick={() => setGravando((g) => !g)}>
            {gravando ? 'Cancelar (Esc)' : 'Gravar nova combinação'}
          </Button>
          <p className="text-sm text-apagado">Aperte as teclas aqui para ver elas afundarem.</p>
        </div>
      </section>

      <div className="grid gap-x-12 gap-y-8 min-[1080px]:grid-cols-2">
        <Linha titulo="Correção ligada" descricao="Pause sem fechar o app. Também dá para pausar pela bandeja.">
          <Switch checked={config.ativo} onCheckedChange={(ativo) => salvar({ ativo })} aria-label="Correção ligada" />
        </Linha>

        <Linha
          titulo="Mostrar prévia antes de corrigir"
          descricao="Um cartão perto do mouse mostra as mudanças; Enter aplica, Esc cancela."
        >
          <Switch
            checked={config.previa}
            onCheckedChange={(previa) => salvar({ previa })}
            aria-label="Mostrar prévia antes de corrigir"
          />
        </Linha>

        <Linha
          titulo="Iniciar com o Windows"
          descricao={
            sistema?.instalado === false ? 'Disponível na versão instalada do app.' : 'Abre minimizado na bandeja ao ligar o PC.'
          }
        >
          <Switch
            checked={sistema?.iniciarComWindows ?? false}
            disabled={!sistema?.instalado}
            onCheckedChange={(ligar) =>
              api
                .salvarSistema(ligar)
                .then(setSistema)
                .catch(() => {})
            }
            aria-label="Iniciar com o Windows"
          />
        </Linha>
      </div>

      <section aria-labelledby="titulo-modo" className="flex flex-col gap-4">
        <h2 id="titulo-modo" className="font-serif text-2xl">
          O que corrigir
        </h2>
        <div role="radiogroup" aria-labelledby="titulo-modo" className="grid gap-4 min-[900px]:grid-cols-2">
          <Modo
            selecionado={config.selecionarTudo}
            aoEscolher={() => salvar({ selecionarTudo: true })}
            titulo="A mensagem inteira"
            descricao="Seleciona tudo na caixa de texto e corrige de uma vez."
            destaque="tudo"
          />
          <Modo
            selecionado={!config.selecionarTudo}
            aoEscolher={() => salvar({ selecionarTudo: false })}
            titulo="Só o que eu selecionar"
            descricao="Corrige apenas o trecho que você marcou antes de apertar o atalho."
            destaque="trecho"
          />
        </div>
      </section>

      <section aria-labelledby="titulo-pontuacao" className="flex flex-col gap-4">
        <h2 id="titulo-pontuacao" className="font-serif text-2xl">
          Pontuação
        </h2>
        <div className="grid gap-x-12 gap-y-8 min-[1080px]:grid-cols-2">
          <Linha titulo="Vírgulas" descricao="Depois de “oi”, antes de “mas”, em listas e após “se/quando…”.">
            <Switch
              checked={grupoLigado(REGRAS_DE_VIRGULA)}
              onCheckedChange={(ligar) => ligarGrupo(REGRAS_DE_VIRGULA, ligar)}
              aria-label="Vírgulas"
            />
          </Linha>
          <Linha titulo="Ponto final e interrogação" descricao="Não mexe em respostas curtas, risadas nem links.">
            <Switch
              checked={grupoLigado(REGRAS_DE_FIM)}
              onCheckedChange={(ligar) => ligarGrupo(REGRAS_DE_FIM, ligar)}
              aria-label="Ponto final e interrogação"
            />
          </Linha>
        </div>
      </section>

      <section aria-labelledby="titulo-regras" className="flex flex-col gap-3">
        <h2 id="titulo-regras" className="font-serif text-2xl">
          Regras desativadas
        </h2>
        {regrasDoLanguageTool.length === 0 ? (
          <p className="text-sm text-apagado">Nenhuma. Desative uma regra direto no cartão de sugestão do Início.</p>
        ) : (
          <ul className="flex flex-wrap gap-2">
            {regrasDoLanguageTool.map((regra) => (
              <li
                key={regra}
                className="flex items-center gap-1 rounded-md border border-linha bg-folha py-1 pr-1 pl-2.5 font-mono text-xs"
              >
                {regra}
                <button
                  type="button"
                  aria-label={`Reativar ${regra}`}
                  onClick={() => salvar({ regrasDesativadas: config.regrasDesativadas.filter((r) => r !== regra) })}
                  className="grid size-5 place-items-center rounded text-apagado hover:text-tinta focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
                >
                  <XIcon className="size-3" />
                </button>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  )
}

function Linha({ titulo, descricao, children }: { titulo: string; descricao: string; children: React.ReactNode }) {
  return (
    <div className="flex items-center justify-between gap-6 border-b border-linha pb-5">
      <div>
        <p className="font-medium">{titulo}</p>
        <p className="mt-0.5 text-sm text-apagado">{descricao}</p>
      </div>
      {children}
    </div>
  )
}

type PropsModo = {
  selecionado: boolean
  aoEscolher: () => void
  titulo: string
  descricao: string
  destaque: 'tudo' | 'trecho'
}

function Modo({ selecionado, aoEscolher, titulo, descricao, destaque }: PropsModo) {
  return (
    <button
      type="button"
      role="radio"
      aria-checked={selecionado}
      onClick={aoEscolher}
      className={cn(
        'flex flex-col gap-4 rounded-xl border p-5 text-left transition-[border-color,box-shadow,background-color] focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none',
        selecionado ? 'border-tinta bg-folha shadow-[0_0_0_1px_var(--tinta)]' : 'border-linha hover:border-linha-forte',
      )}
    >
      <div aria-hidden className="flex w-full flex-col gap-2 rounded-lg bg-papel p-3.5">
        {[0.92, 0.7, 0.84].map((largura, i) => (
          <span key={i} className="relative block h-2 rounded-full bg-linha-forte" style={{ width: `${largura * 100}%` }}>
            {destaque === 'tudo' || i === 1 ? (
              <motion.span
                className="absolute inset-y-[-3px] left-0 block rounded-sm bg-teal/25"
                initial={false}
                animate={{ width: selecionado ? (destaque === 'tudo' ? '100%' : '55%') : '0%' }}
                transition={{ duration: 0.35, delay: i * 0.05 }}
              />
            ) : null}
          </span>
        ))}
      </div>
      <div>
        <p className="font-medium">{titulo}</p>
        <p className="mt-0.5 text-sm text-apagado">{descricao}</p>
      </div>
    </button>
  )
}

/** Teclas que a pessoa está segurando agora, já com os nomes usados no atalho (Ctrl, Shift, Espaço…). */
function usarTeclasApertadas() {
  const [apertadas, setApertadas] = useState<Set<string>>(() => new Set())
  useEffect(() => {
    const nome = (e: KeyboardEvent) => NOME_DA_TECLA[e.key] ?? (e.key.length === 1 ? e.key.toUpperCase() : e.key.toUpperCase())
    const descer = (e: KeyboardEvent) => setApertadas((atual) => new Set(atual).add(nome(e)))
    const subir = (e: KeyboardEvent) =>
      setApertadas((atual) => {
        const proximo = new Set(atual)
        proximo.delete(nome(e))
        return proximo
      })
    const limpar = () => setApertadas(new Set())
    window.addEventListener('keydown', descer)
    window.addEventListener('keyup', subir)
    window.addEventListener('blur', limpar)
    return () => {
      window.removeEventListener('keydown', descer)
      window.removeEventListener('keyup', subir)
      window.removeEventListener('blur', limpar)
    }
  }, [])
  return apertadas
}
