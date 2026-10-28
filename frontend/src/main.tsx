import { StrictMode, Suspense, lazy } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App'
import { iniciarTema } from './lib/tema'
import './index.css'

// a prévia flutuante (janela por cima do WhatsApp) usa a mesma página com ?previa
const Previa = lazy(() => import('./previa/previa'))
const ehPrevia = new URLSearchParams(window.location.search).has('previa')

if (ehPrevia) {
  document.documentElement.classList.add('previa')
} else {
  iniciarTema()
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    {ehPrevia ? (
      <Suspense fallback={null}>
        <Previa />
      </Suspense>
    ) : (
      <App />
    )}
  </StrictMode>,
)
