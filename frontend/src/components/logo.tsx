import { cn } from '@/lib/utils'

/** Balão de conversa com a "onda" de revisor por baixo da linha de texto. */
export function Logo({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 32 32" aria-hidden className={cn('size-8', className)}>
      <rect width="32" height="32" rx="8" className="fill-tinta" />
      <path
        d="M8 9.5h16a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2h-9.2L10 25.2v-3.7H8a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2Z"
        className="fill-papel"
      />
      <path d="M10 13.6h9.5" className="stroke-tinta" strokeWidth="1.6" strokeLinecap="round" />
      <path
        d="M10 18c.9-1.2 1.8-1.2 2.7 0s1.8 1.2 2.7 0 1.8-1.2 2.7 0 1.8 1.2 2.7 0"
        className="stroke-vermelhao"
        strokeWidth="1.6"
        fill="none"
        strokeLinecap="round"
      />
    </svg>
  )
}
