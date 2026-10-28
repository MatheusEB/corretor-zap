type Props = {
  indice: string
  titulo: string
  descricao: string
  acao?: React.ReactNode
}

/** Cabeçalho de página no estilo de revista: número da seção, título serifado e fio fino. */
export function Cabecalho({ indice, titulo, descricao, acao }: Props) {
  return (
    <header className="flex items-end justify-between gap-6 border-b border-linha pb-5">
      <div className="min-w-0">
        <p className="font-mono text-[11px] tracking-[0.18em] text-apagado uppercase">{indice}</p>
        <h1 className="mt-1 font-serif text-[2.75rem] leading-none tracking-tight text-tinta">{titulo}</h1>
        <p className="mt-2 max-w-xl text-sm text-grafite">{descricao}</p>
      </div>
      {acao}
    </header>
  )
}
