import { useEffect, useState } from 'react'
import { toast } from 'sonner'

import { Cabecalho } from '@/components/cabecalho'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Spinner } from '@/components/ui/spinner'
import { Switch } from '@/components/ui/switch'
import { api, type ConfiguracaoIa, type PedidoIa, type ResultadoTesteIa } from '@/lib/api'
import { cn } from '@/lib/utils'

type Provedor = { id: string; nome: string; descricao: string; url: string; modelo: string; dicaModelo: string; chave: boolean }

// qualquer API no formato de chat da OpenAI funciona; estes só preenchem os campos
const PROVEDORES: Provedor[] = [
  {
    id: 'gemini',
    nome: 'Google Gemini',
    descricao: 'Plano gratuito com limite de uso. Precisa de uma chave do Google AI Studio.',
    url: 'https://generativelanguage.googleapis.com/v1beta/openai/',
    modelo: 'gemini-3.1-flash-lite',
    dicaModelo: 'gemini-3.1-flash-lite responde em ~1 s; outros: gemini-3.5-flash-lite, gemini-3.8-flash',
    chave: true,
  },
  {
    id: 'ollama',
    nome: 'Ollama',
    descricao: 'Roda no seu computador: grátis e nada sai da máquina. Precisa de um PC com boa memória.',
    url: 'http://localhost:11434/v1/',
    modelo: '',
    dicaModelo: 'o nome que aparece em “ollama list”, ex.: qwen3:8b',
    chave: false,
  },
  {
    id: 'outro',
    nome: 'Outro',
    descricao: 'Qualquer serviço compatível com a API da OpenAI (OpenRouter, Groq, LM Studio…).',
    url: '',
    modelo: '',
    dicaModelo: 'o nome do modelo no serviço',
    chave: true,
  },
]

const FRASE_DE_TESTE = 'voce nao vai acreditar oque aconteceu hoje to rindo ate agora'

export function PaginaIa() {
  const [salva, setSalva] = useState<ConfiguracaoIa | null>(null)
  const [rascunho, setRascunho] = useState<Omit<ConfiguracaoIa, 'chaveDefinida' | 'ativo'> | null>(null)
  const [chave, setChave] = useState('')
  const [salvando, setSalvando] = useState(false)

  useEffect(() => {
    api
      .ia()
      .then((c) => {
        setSalva(c)
        setRascunho({ provedor: c.provedor, url: c.url, modelo: c.modelo })
      })
      .catch((e: Error) => toast.error(e.message))
  }, [])

  if (!salva || !rascunho) return null

  const provedor = PROVEDORES.find((p) => p.id === rascunho.provedor) ?? PROVEDORES[2]
  const alterado = rascunho.provedor !== salva.provedor || rascunho.url !== salva.url || rascunho.modelo !== salva.modelo || chave !== ''

  const enviar = async (pedido: PedidoIa) => {
    setSalvando(true)
    try {
      const nova = await api.salvarIa(pedido)
      setSalva(nova)
      setChave('')
      return true
    } catch (e) {
      toast.error((e as Error).message)
      return false
    } finally {
      setSalvando(false)
    }
  }

  const base: PedidoIa = { ativo: salva.ativo, provedor: salva.provedor, url: salva.url, modelo: salva.modelo }
  const salvar = () => enviar({ ...rascunho, ativo: salva.ativo, ...(chave ? { chave } : {}) })
  const escolher = (p: Provedor) => setRascunho({ provedor: p.id, url: p.url, modelo: p.modelo })

  return (
    <div className="flex flex-col gap-10">
      <Cabecalho
        indice="05 · IA"
        titulo="Revisão por IA"
        descricao="Opcional: uma IA revisa a mensagem do atalho e pega o que o corretor local deixa passar, como concordância e palavras trocadas."
      />

      <div className="grid gap-10 min-[1080px]:grid-cols-[minmax(0,1fr)_260px]">
        <div className="flex flex-col gap-10">
          <div className="flex items-center justify-between gap-6 border-b border-linha pb-5">
            <div>
              <p className="font-medium">Usar IA no atalho</p>
              <p className="mt-0.5 text-sm text-apagado">
                Se ela falhar ou passar do limite, o corretor local assume na hora.
              </p>
            </div>
            <Switch
              checked={salva.ativo}
              disabled={salvando}
              onCheckedChange={(ativo) => enviar({ ...base, ativo })}
              aria-label="Usar IA no atalho"
            />
          </div>

          <section aria-labelledby="titulo-provedor" className="flex flex-col gap-4">
            <h2 id="titulo-provedor" className="font-serif text-2xl">
              Qual IA
            </h2>
            <div role="radiogroup" aria-labelledby="titulo-provedor" className="grid gap-3 min-[900px]:grid-cols-3">
              {PROVEDORES.map((p) => (
                <button
                  key={p.id}
                  type="button"
                  role="radio"
                  aria-checked={p.id === provedor.id}
                  onClick={() => escolher(p)}
                  className={cn(
                    'flex flex-col gap-1 rounded-xl border p-4 text-left transition-[border-color,box-shadow] focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none',
                    p.id === provedor.id ? 'border-tinta bg-folha shadow-[0_0_0_1px_var(--tinta)]' : 'border-linha hover:border-linha-forte',
                  )}
                >
                  <span className="font-medium">{p.nome}</span>
                  <span className="text-sm text-apagado">{p.descricao}</span>
                </button>
              ))}
            </div>

            <div className="grid gap-5 pt-2">
              <Campo id="ia-url" rotulo="Endereço da API">
                <Input
                  id="ia-url"
                  value={rascunho.url}
                  onChange={(e) => setRascunho({ ...rascunho, url: e.target.value })}
                  placeholder="https://…/v1/"
                  spellCheck={false}
                />
              </Campo>
              <Campo id="ia-modelo" rotulo="Modelo" dica={provedor.dicaModelo}>
                <Input
                  id="ia-modelo"
                  value={rascunho.modelo}
                  onChange={(e) => setRascunho({ ...rascunho, modelo: e.target.value })}
                  spellCheck={false}
                />
              </Campo>
              {provedor.chave || salva.chaveDefinida ? (
                <Campo
                  id="ia-chave"
                  rotulo="Chave da API"
                  dica={
                    provedor.id === 'gemini'
                      ? 'Crie de graça em aistudio.google.com/apikey. Fica salva só neste computador.'
                      : 'Fica salva só neste computador e nunca volta para esta tela.'
                  }
                >
                  <div className="flex gap-2">
                    <Input
                      id="ia-chave"
                      type="password"
                      value={chave}
                      onChange={(e) => setChave(e.target.value)}
                      placeholder={salva.chaveDefinida ? '•••••••• (salva; digite para trocar)' : 'cole a chave aqui'}
                      autoComplete="off"
                      spellCheck={false}
                    />
                    {salva.chaveDefinida ? (
                      <Button variant="ghost" disabled={salvando} onClick={() => enviar({ ...base, chave: '' })}>
                        Remover
                      </Button>
                    ) : null}
                  </div>
                </Campo>
              ) : null}
              <div>
                <Button onClick={salvar} disabled={!alterado || salvando}>
                  {salvando ? <Spinner data-icon="inline-start" /> : null}
                  Salvar
                </Button>
              </div>
            </div>
          </section>

          <Teste pronta={!!salva.url && !!salva.modelo && !alterado} />
        </div>

        <aside className="border-l border-linha pl-6 text-sm leading-relaxed text-grafite">
          <p className="font-serif text-xl text-tinta italic">Antes de ligar</p>
          <p className="mt-2">
            Com a IA ligada, o texto de cada mensagem corrigida pelo atalho é enviado ao serviço escolhido.
          </p>
          <p className="mt-3">
            No <span className="font-medium text-tinta">plano gratuito do Gemini</span> não há cobrança, mas o Google pode usar
            o conteúdo para melhorar os produtos dele. Para conversas pessoais, prefira o Ollama, que roda aqui mesmo.
          </p>
          <p className="mt-3">
            O editor do Início continua com o corretor local, para não gastar o limite a cada tecla.
          </p>
        </aside>
      </div>
    </div>
  )
}

function Campo({ id, rotulo, dica, children }: { id: string; rotulo: string; dica?: string; children: React.ReactNode }) {
  return (
    <div className="flex flex-col gap-1.5">
      <Label htmlFor={id}>{rotulo}</Label>
      {children}
      {dica ? <p className="text-xs text-apagado">{dica}</p> : null}
    </div>
  )
}

/** Manda uma frase para a IA salva e mostra o que voltou (ou o erro), com o tempo de resposta. */
function Teste({ pronta }: { pronta: boolean }) {
  const [frase, setFrase] = useState(FRASE_DE_TESTE)
  const [testando, setTestando] = useState(false)
  const [resultado, setResultado] = useState<ResultadoTesteIa | null>(null)
  const [erro, setErro] = useState<string | null>(null)

  const testar = async () => {
    setTestando(true)
    setErro(null)
    setResultado(null)
    try {
      setResultado(await api.testarIa(frase))
    } catch (e) {
      setErro((e as Error).message)
    } finally {
      setTestando(false)
    }
  }

  return (
    <section aria-labelledby="titulo-teste" className="flex flex-col gap-4">
      <h2 id="titulo-teste" className="font-serif text-2xl">
        Testar
      </h2>
      <div className="flex gap-2">
        <Input value={frase} onChange={(e) => setFrase(e.target.value)} aria-label="Frase de teste" maxLength={2000} />
        <Button onClick={testar} disabled={!pronta || testando || !frase.trim()} variant="outline">
          {testando ? <Spinner data-icon="inline-start" /> : null}
          Testar
        </Button>
      </div>
      {!pronta ? <p className="text-sm text-apagado">Salve o endereço e o modelo para testar.</p> : null}
      {erro ? <p className="text-sm text-vermelhao">{erro}</p> : null}
      {resultado ? (
        <div className="rounded-xl border border-linha bg-folha p-4">
          <p className="text-[15px] leading-relaxed whitespace-pre-wrap">{resultado.resultado.corrigido}</p>
          <p className="mt-2 font-mono text-[10px] tracking-[0.16em] text-apagado uppercase tabular-nums">
            {resultado.resultado.correcoes.length}{' '}
            {resultado.resultado.correcoes.length === 1 ? 'correção' : 'correções'} ·{' '}
            {(resultado.milissegundos / 1000).toLocaleString('pt-BR', { maximumFractionDigits: 1 })} s
          </p>
        </div>
      ) : null}
    </section>
  )
}
