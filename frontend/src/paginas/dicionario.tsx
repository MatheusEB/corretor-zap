import { Cabecalho } from '@/components/cabecalho'
import { Contador } from '@/components/contador'
import { TagInput } from '@/components/ui/tag-input'
import { api, type Atualizar, type Configuracao } from '@/lib/api'

const MAXIMO = 5000

type Props = { config: Configuracao; atualizar: Atualizar }

export function PaginaDicionario({ config, atualizar }: Props) {
  // o TagInput devolve a lista nova inteira; aqui viram chamadas de adicionar/remover no servidor
  function sincronizar(novas: string[]) {
    const atuais = new Set(config.dicionario)
    const proximas = new Set(novas.map((p) => p.toLowerCase()))
    const operacoes = [
      ...[...proximas].filter((p) => !atuais.has(p)).map((p) => () => api.adicionarPalavra(p)),
      ...[...atuais].filter((p) => !proximas.has(p)).map((p) => () => api.removerPalavra(p)),
    ]
    if (operacoes.length === 0) return
    atualizar(async () => {
      let resultado = config
      for (const operacao of operacoes) resultado = await operacao()
      return resultado
    })
  }

  return (
    <div className="flex flex-col gap-8">
      <Cabecalho
        indice="03 · Dicionário"
        titulo="Dicionário pessoal"
        descricao="Gírias, nomes e apelidos que o corretor nunca deve trocar."
        acao={
          <p className="shrink-0 text-right">
            <span className="block font-serif text-4xl leading-none tabular-nums">
              <Contador valor={config.dicionario.length} />
            </span>
            <span className="font-mono text-[10px] tracking-[0.16em] text-apagado uppercase">palavras</span>
          </p>
        }
      />

      <div className="grid gap-10 min-[1080px]:grid-cols-[minmax(0,1fr)_260px]">
        <TagInput
          label="Palavras"
          value={config.dicionario}
          onChange={sincronizar}
          placeholder="ex.: blz, vlw, Zezinho"
          hint="Enter ou vírgula adiciona · Backspace remove"
          validate={(palavra) => !/\s/.test(palavra)}
          maxLength={100}
          max={MAXIMO}
        />
        <aside className="border-l border-linha pl-6 text-sm leading-relaxed text-grafite">
          <p className="font-serif text-xl text-tinta italic">Dica de revisor</p>
          <p className="mt-2">
            Cole uma lista separada por vírgulas para adicionar várias de uma vez. As palavras valem com ou sem
            maiúscula.
          </p>
          <p className="mt-3">
            No Início, o botão <span className="font-medium text-tinta">Ignorar palavra</span> do cartão de sugestão
            também traz a palavra para cá.
          </p>
        </aside>
      </div>
    </div>
  )
}
