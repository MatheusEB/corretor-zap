import { useSyncExternalStore } from 'react'

export type PreferenciaTema = 'sistema' | 'claro' | 'escuro'

const CHAVE = 'corretor-zap:tema'
const ouvintes = new Set<() => void>()

// a janela do app passa ?tema=escuro|claro conforme o Windows (o WebView não sabe do modo escuro do sistema)
const parametros = new URLSearchParams(window.location.search)
const temaDoWindows = parametros.get('tema')
const dentroDoApp = parametros.has('janela')
const midiaEscura = window.matchMedia('(prefers-color-scheme: dark)')

function lerPreferencia(): PreferenciaTema {
  try {
    const salvo = localStorage.getItem(CHAVE)
    if (salvo === 'claro' || salvo === 'escuro' || salvo === 'sistema') return salvo
  } catch {
    // armazenamento bloqueado: segue o sistema
  }
  return 'sistema'
}

let preferencia = lerPreferencia()

function sistemaEscuro() {
  return temaDoWindows ? temaDoWindows === 'escuro' : midiaEscura.matches
}

function aplicar() {
  const escuro = preferencia === 'escuro' || (preferencia === 'sistema' && sistemaEscuro())
  document.documentElement.classList.toggle('dark', escuro)
  // dentro do app desktop, a barra de título nativa acompanha o tema escolhido aqui
  if (dentroDoApp) fetch(`/api/janela/tema?escuro=${escuro}`, { method: 'POST' }).catch(() => {})
}

export function iniciarTema() {
  aplicar()
  midiaEscura.addEventListener('change', aplicar)
}

export function definirTema(nova: PreferenciaTema) {
  preferencia = nova
  try {
    localStorage.setItem(CHAVE, nova)
  } catch {
    // vale só para esta sessão
  }
  aplicar()
  ouvintes.forEach((avisar) => avisar())
}

function assinar(avisar: () => void) {
  ouvintes.add(avisar)
  return () => ouvintes.delete(avisar)
}

export function usarTema(): PreferenciaTema {
  return useSyncExternalStore(assinar, () => preferencia)
}
