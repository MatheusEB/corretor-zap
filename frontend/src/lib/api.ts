export type Configuracao = {
  ativo: boolean
  atalho: string
  selecionarTudo: boolean
  dicionario: string[]
  regrasDesativadas: string[]
  previa: boolean
}

export type Correcao = {
  inicio: number
  fim: number
  trecho: string
  sugestao: string
  mensagem: string
  regra: string
  descricaoRegra: string
}

export type ResultadoCorrecao = {
  original: string
  corrigido: string
  correcoes: Correcao[]
}

export type RegistroHistorico = ResultadoCorrecao & { quando: string }

export type Status = { atalhoRegistrado: boolean }

export type Sistema = { instalado: boolean; iniciarComWindows: boolean }

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
}

/** Aplica uma alteração na config; devolve false (e avisa o usuário) se o servidor recusar. */
export type Atualizar = (operacao: () => Promise<Configuracao>) => Promise<boolean>
