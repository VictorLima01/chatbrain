import { useEffect, useState } from 'react'

const PHASES = [
  'Consultando o material...',
  'Lendo as transcricoes...',
  'Cruzando as fontes...',
  'Montando a resposta...',
]

/** Bolinha colorida que muda de forma enquanto o modelo pensa. */
export function ThinkingBlob() {
  const [phase, setPhase] = useState(0)

  useEffect(() => {
    const timer = setInterval(() => setPhase((p) => (p + 1) % PHASES.length), 2600)
    return () => clearInterval(timer)
  }, [])

  return (
    <div className="thinking" role="status" aria-live="polite">
      <div className="blob" />
      <span className="thinking-text">{PHASES[phase]}</span>
    </div>
  )
}
