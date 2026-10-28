import { useDeferredValue, useEffect, useState } from 'react'

import { api, type Correcao } from '@/lib/api'

const ESPERA_MS = 320

type Estado = { texto: string; correcoes: Correcao[] }

/**
 * Verifica o texto enquanto a pessoa digita: espera uma pausa curta, cancela a verificação anterior
 * e devolve as correções junto com o texto a que elas se referem.
 */
export function usarCorrecaoAoVivo(texto: string, configVersao: unknown) {
  const adiado = useDeferredValue(texto)
  const [estado, setEstado] = useState<Estado>({ texto: '', correcoes: [] })
  const [verificando, setVerificando] = useState(false)
  const [erro, setErro] = useState<string | null>(null)

  useEffect(() => {
    if (!adiado.trim()) {
      setEstado({ texto: adiado, correcoes: [] })
      setVerificando(false)
      return
    }
    const controle = new AbortController()
    setVerificando(true)
    const espera = setTimeout(() => {
      api
        .corrigir(adiado, controle.signal)
        .then((r) => {
          setEstado({ texto: adiado, correcoes: r.correcoes })
          setErro(null)
        })
        .catch((e: Error) => {
          if (e.name !== 'AbortError') setErro(e.message)
        })
        .finally(() => {
          if (!controle.signal.aborted) setVerificando(false)
        })
    }, ESPERA_MS)
    return () => {
      clearTimeout(espera)
      controle.abort()
    }
    // configVersao: dicionário/regras mudaram → verifica de novo o mesmo texto
  }, [adiado, configVersao])

  return { ...estado, verificando, erro, definir: setEstado }
}
