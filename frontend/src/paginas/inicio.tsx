import { useEffect, useRef, useState } from 'react'

import { Cabecalho } from '@/components/cabecalho'
import { Contador } from '@/components/contador'
import { EditorRevisao } from '@/components/editor-revisao'
import { NotasMargem } from '@/components/notas-margem'
import { Tecla } from '@/components/tecla'
import { api, type Atualizar, type Configuracao, type Correcao } from '@/lib/api'
import { teclasDoAtalho } from '@/lib/atalho'
import { aplicarCorrecao, aplicarTodas, correcoesValidas } from '@/lib/correcoes'
import { usarCorrecaoAoVivo } from '@/lib/usar-correcao-ao-vivo'

const EXEMPLO = 'voce nao vai acreditar oque aconteceu hoje, to rindo ate agora'

type Props = { config: Configuracao; atualizar: Atualizar }

export function PaginaInicio({ config, atualizar }: Props) {
  const [texto, setTexto] = useState(EXEMPLO)
  const [ativa, setAtiva] = useState<number | null>(null)
  const versaoConfig = `${config.dicionario.join(',')}|${config.regrasDesativadas.join(',')}`
  const aoVivo = usarCorrecaoAoVivo(texto, versaoConfig)
  const correcoes = correcoesValidas(texto, aoVivo.correcoes)
  const sessao = usarSessao()

  const aceitar = (c: Correcao) => {
    const r = aplicarCorrecao(texto, correcoes, c)
    setTexto(r.texto)
    aoVivo.definir(r)
    setAtiva(null)
  }

  const removerLocalmente = (manter: (c: Correcao) => boolean) => {
    aoVivo.definir({ texto, correcoes: correcoes.filter(manter) })
    setAtiva(null)
  }

  const ignorarPalavra = (c: Correcao) => {
    const palavra = c.trecho.toLowerCase()
    removerLocalmente((outra) => outra.trecho.toLowerCase() !== palavra)
    atualizar(() => api.adicionarPalavra(c.trecho))
  }

  const desativarRegra = (c: Correcao) => {
    removerLocalmente((outra) => outra.regra !== c.regra)
    atualizar(() => api.salvarConfig({ ...config, regrasDesativadas: [...config.regrasDesativadas, c.regra] }))
  }

  const aplicarTudo = () => {
    if (correcoes.length === 0) return
    const novo = aplicarTodas(texto, correcoes)
    setTexto(novo)
    aoVivo.definir({ texto: novo, correcoes: [] })
    setAtiva(null)
  }

  // o atalho Ctrl+Enter é registrado uma vez só e sempre chama a versão mais recente
  const aplicarTudoRef = useRef(aplicarTudo)
  aplicarTudoRef.current = aplicarTudo
  useEffect(() => {
    const aoTeclar = (e: KeyboardEvent) => {
      if (e.ctrlKey && e.key === 'Enter') {
        e.preventDefault()
        aplicarTudoRef.current()
      }
    }
    window.addEventListener('keydown', aoTeclar)
    return () => window.removeEventListener('keydown', aoTeclar)
  }, [])

  return (
    <div className="flex flex-col gap-8">
      <Cabecalho
        indice="01 · Início"
        titulo="Revisão"
        descricao="Escreva como no WhatsApp e veja o que o corretor marcaria. Lá, é só apertar o atalho."
        acao={
          <dl className="flex shrink-0 gap-8 text-right">
            <div>
              <dt className="font-mono text-[10px] tracking-[0.16em] text-apagado uppercase">Correções hoje</dt>
              <dd className="font-serif text-4xl leading-none tabular-nums">
                <Contador valor={sessao.correcoes} />
              </dd>
            </div>
            <div>
              <dt className="font-mono text-[10px] tracking-[0.16em] text-apagado uppercase">Mensagens</dt>
              <dd className="font-serif text-4xl leading-none tabular-nums">
                <Contador valor={sessao.mensagens} />
              </dd>
            </div>
          </dl>
        }
      />

      <div className="grid grid-cols-1 gap-10 min-[1080px]:grid-cols-[minmax(0,1fr)_280px]">
        <div className="flex min-w-0 flex-col gap-4">
          <EditorRevisao
            texto={texto}
            mudarTexto={setTexto}
            correcoes={correcoes}
            ativa={ativa}
            definirAtiva={setAtiva}
            aceitar={aceitar}
            ignorarPalavra={ignorarPalavra}
            desativarRegra={desativarRegra}
          />
          <p className="flex flex-wrap items-center gap-1.5 text-sm text-grafite">
            No WhatsApp: escreva, aperte
            {teclasDoAtalho(config.atalho).map((t) => (
              <Tecla key={t}>{t}</Tecla>
            ))}
            e a mensagem é corrigida direto na caixa de texto.
          </p>
          {aoVivo.erro ? <p className="text-sm text-vermelhao">{aoVivo.erro}</p> : null}
        </div>

        <NotasMargem
          correcoes={correcoes}
          vazio={!texto.trim()}
          verificando={aoVivo.verificando}
          ativa={ativa}
          definirAtiva={setAtiva}
          aceitar={aceitar}
          ignorarPalavra={ignorarPalavra}
          aplicarTodas={aplicarTudo}
        />
      </div>
    </div>
  )
}

/** Números do dia a partir do histórico do atalho (atualiza sozinho enquanto a janela está aberta). */
function usarSessao() {
  const [sessao, setSessao] = useState({ correcoes: 0, mensagens: 0 })
  useEffect(() => {
    let montado = true
    const carregar = () => {
      if (document.hidden) return
      api
        .historico()
        .then((registros) => {
          if (!montado) return
          const hoje = new Date().toDateString()
          const deHoje = registros.filter((r) => new Date(r.quando).toDateString() === hoje)
          setSessao({
            mensagens: deHoje.length,
            correcoes: deHoje.reduce((soma, r) => soma + r.correcoes.length, 0),
          })
        })
        .catch(() => {})
    }
    carregar()
    const intervalo = setInterval(carregar, 5000)
    return () => {
      montado = false
      clearInterval(intervalo)
    }
  }, [])
  return sessao
}
