export type Configuracao = {
  ativo: boolean
  atalho: string
  selecionarTudo: boolean
  dicionario: string[]
  regrasDesativadas: string[]
  previa: boolean
  /** procura versão nova no GitHub de tempos em tempos */
  atualizacoes: boolean
}

export type Correcao = {
  inicio: number
  fim: number
  trecho: string
  sugestao: string
  mensagem: string
  regra: string
  descricaoRegra: string
  /** false = sugestão duvidosa: não entra no texto corrigido e a prévia mostra desmarcada */
  certeza: boolean
}

export type ResultadoCorrecao = {
  original: string
  corrigido: string
  correcoes: Correcao[]
}

export type RegistroHistorico = ResultadoCorrecao & { quando: string }

export type Status = { atalhoRegistrado: boolean }

export type Sistema = { instalado: boolean; iniciarComWindows: boolean }

export type FaseAtualizacao = 'INDISPONIVEL' | 'ATUALIZADO' | 'DISPONIVEL' | 'BAIXANDO' | 'INSTALANDO' | 'ERRO'

export type EstadoAtualizacao = {
  /** null rodando do código-fonte */
  atual: string | null
  fase: FaseAtualizacao
  nova: string | null
  novidades: string | null
  pagina: string | null
  progresso: number
  erro: string | null
  acabouDeAtualizar: boolean
}

export type ConfiguracaoIa = { ativo: boolean; provedor: string; url: string; modelo: string; chaveDefinida: boolean }

/** `chave` ausente mantém a salva; string vazia apaga. */
export type PedidoIa = Omit<ConfiguracaoIa, 'chaveDefinida'> & { chave?: string }

export type ResultadoTesteIa = { resultado: ResultadoCorrecao; milissegundos: number }

async function chamar<T>(metodo: string, caminho: string, corpo?: unknown, signal?: AbortSignal): Promise<T> {
  const resposta = await fetch(`/api${caminho}`, {
    method: metodo,
    headers: corpo === undefined ? undefined : { 'Content-Type': 'application/json' },
    body: corpo === undefined ? undefined : JSON.stringify(corpo),
    signal,
  })
  if (!resposta.ok) {
    const erro = await resposta.json().catch(() => ({}))
    throw new Error(erro.detail || `Erro ${resposta.status}`)
  }
  const texto = await resposta.text()
  return (texto ? JSON.parse(texto) : null) as T
}

export const api = {
  status: () => chamar<Status>('GET', '/status'),
  corrigir: (texto: string, signal?: AbortSignal) =>
    chamar<ResultadoCorrecao>('POST', '/corrigir', { texto }, signal),
  config: () => chamar<Configuracao>('GET', '/config'),
  salvarConfig: (config: Configuracao) => chamar<Configuracao>('PUT', '/config', config),
  adicionarPalavra: (palavra: string) => chamar<Configuracao>('POST', '/dicionario', { palavra }),
  removerPalavra: (palavra: string) =>
    chamar<Configuracao>('DELETE', `/dicionario/${encodeURIComponent(palavra)}`),
  historico: () => chamar<RegistroHistorico[]>('GET', '/historico'),
  limparHistorico: () => chamar<null>('DELETE', '/historico'),
  sistema: () => chamar<Sistema>('GET', '/sistema'),
  salvarSistema: (iniciarComWindows: boolean) => chamar<Sistema>('PUT', '/sistema', { iniciarComWindows }),
  ia: () => chamar<ConfiguracaoIa>('GET', '/ia'),
  salvarIa: (pedido: PedidoIa) => chamar<ConfiguracaoIa>('PUT', '/ia', pedido),
  testarIa: (texto: string) => chamar<ResultadoTesteIa>('POST', '/ia/testar', { texto }),
  atualizacao: () => chamar<EstadoAtualizacao>('GET', '/atualizacao'),
  procurarAtualizacao: () => chamar<EstadoAtualizacao>('POST', '/atualizacao/verificar'),
  instalarAtualizacao: () => chamar<EstadoAtualizacao>('POST', '/atualizacao/instalar'),
  avisoDeAtualizacaoVisto: () => chamar<null>('POST', '/atualizacao/aviso-visto'),
}

/** Aplica uma alteração na config; devolve false (e avisa o usuário) se o servidor recusar. */
export type Atualizar = (operacao: () => Promise<Configuracao>) => Promise<boolean>
