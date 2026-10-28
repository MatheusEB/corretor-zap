import { useEffect } from 'react'
import { motion, useReducedMotion, useSpring, useTransform } from 'motion/react'

/** Número que "rola" até o valor novo. */
export function Contador({ valor, className }: { valor: number; className?: string }) {
  const reduzido = useReducedMotion()
  const mola = useSpring(valor, { stiffness: 120, damping: 22 })
  const arredondado = useTransform(mola, (v) => Math.round(v).toLocaleString('pt-BR'))

  useEffect(() => {
    if (reduzido) mola.jump(valor)
    else mola.set(valor)
  }, [valor, reduzido, mola])

  return <motion.span className={className}>{arredondado}</motion.span>
}
