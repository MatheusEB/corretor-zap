import { cn } from '@/lib/utils'

type Props = {
  children: React.ReactNode
  apertada?: boolean
  tamanho?: 'sm' | 'lg'
  className?: string
}

/** Tecla "física": afunda quando a tecla real está apertada. */
export function Tecla({ children, apertada = false, tamanho = 'sm', className }: Props) {
  return (
    <kbd
      data-apertada={apertada}
      className={cn(
        'tecla inline-flex items-center justify-center rounded-md border border-linha-forte bg-folha font-mono font-medium text-tinta select-none',
        tamanho === 'lg' ? 'h-16 min-w-16 px-5 text-lg' : 'h-6 min-w-6 px-1.5 text-[11px]',
        apertada && 'bg-teal-suave text-teal',
        className,
      )}
    >
      {children}
    </kbd>
  )
}
