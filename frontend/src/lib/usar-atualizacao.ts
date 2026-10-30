import { useEffect, useSyncExternalStore } from 'react'

import { api, type EstadoAtualizacao } from '@/lib/api'

// o app procura sozinho no servidor; a tela só pergunta o resultado de vez em quando
const INTERVALO_MS = 5 * 60 * 1000
// enquanto baixa, acompanha a barra de progresso
const INTERVALO_BAIXANDO_MS = 400

// um estado só para a tela inteira: "Procurar agora" na página Atalho precisa acender o aviso da barra lateral
let atual: EstadoAtualizacao | null = null
const ouvintes = new Set<() => void>()

function definir(estado: EstadoAtualizacao) {
  atual = estado
  ouvintes.forEach((avisar) => avisar())
}

function recarregar() {
  api
    .atualizacao()
    .then(definir)
    .catch(() => {})
}

function assinar(avisar: () => void) {
  ouvintes.add(avisar)
  return () => {
    ouvintes.delete(avisar)
  }
}

/** Estado da atualização automática, mantido em dia enquanto a tela está aberta. */
export function usarAtualizacao() {
  const estado = useSyncExternalStore(assinar, () => atual)
  const ocupado = estado?.fase === 'BAIXANDO' || estado?.fase === 'INSTALANDO'

  useEffect(() => {
    recarregar()
    const id = window.setInterval(recarregar, ocupado ? INTERVALO_BAIXANDO_MS : INTERVALO_MS)
    return () => window.clearInterval(id)
  }, [ocupado])

  return { estado, setEstado: definir, recarregar }
}
