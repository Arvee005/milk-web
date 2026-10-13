import type { TimeTheme } from './TimeTheme'

export type MilkCanMascotProps = {
  theme: TimeTheme
  label?: string
}

// A cute milk-can mascot rendered as inline SVG. It is decorative by default;
// pass an accessible label (or set label to null) when it carries meaning.
export function MilkCanMascot({ theme, label = 'Milk Web mascot' }: MilkCanMascotProps) {
  const eyeY = theme === 'night' ? 16 : 14
  const eyeColor = theme === 'night' ? '#fff' : '#174f3d'
  const lidColor = theme === 'night' ? '#f3efe6' : '#fff'
  const highlight = theme === 'night' ? '#cfe2f0' : '#fff'
  const lidShift = theme === 'morning' ? 2 : 0
  const blushY = eyeY + 8

  return (
    <svg
      className="milk-can-mascot"
      viewBox="0 0 120 140"
      role={label ? 'img' : 'presentation'}
      aria-label={label ?? undefined}
      aria-hidden={label ? undefined : true}
      focusable="false"
    >
      <defs>
        <linearGradient id={`can-${theme}`} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor="#fff" />
          <stop offset="100%" stopColor="#efe7d6" />
        </linearGradient>
        <linearGradient id={`lid-${theme}`} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor={lidColor} />
          <stop offset="100%" stopColor="#d8cdba" />
        </linearGradient>
        <linearGradient id={`spout-${theme}`} x1="0" y1="0" x2="1" y2="0">
          <stop offset="0%" stopColor="#fff" />
          <stop offset="100%" stopColor="#e3d8c4" />
        </linearGradient>
      </defs>

      {/* Spout */}
      <path d="M78 52 L100 40 L106 50 L84 62 Z" fill={`url(#spout-${theme})`} stroke="#c9bda3" strokeWidth="2" />

      {/* Lid */}
      <rect x="28" y={48 + lidShift} width="64" height="14" rx="7" fill={`url(#lid-${theme})`} stroke="#b8ad93" strokeWidth="2" />
      <rect x="44" y={38 + lidShift} width="32" height="10" rx="5" fill="#f7f1e2" stroke="#b8ad93" strokeWidth="2" />

      {/* Handle */}
      <path d="M100 56 Q116 70 100 86" fill="none" stroke="#c9bda3" strokeWidth="5" strokeLinecap="round" />

      {/* Can body */}
      <path d="M28 62 Q22 120 44 128 H76 Q98 120 92 62 Z" fill={`url(#can-${theme})`} stroke="#c9bda3" strokeWidth="2" />

      {/* Highlight */}
      <ellipse cx="50" cy="86" rx="14" ry="22" fill={highlight} opacity="0.55" />

      {/* Face */}
      <circle cx="44" cy={eyeY} r="3.4" fill={eyeColor} />
      <circle cx="76" cy={eyeY} r="3.4" fill={eyeColor} />
      <path d="M54 96 Q60 102 66 96" fill="none" stroke={eyeColor} strokeWidth="2.4" strokeLinecap="round" />

      {/* Cheek blush */}
      <circle cx="38" cy={blushY} r="3" fill="#f3c6c0" opacity={theme === 'night' ? 0.35 : 0.6} />
      <circle cx="82" cy={blushY} r="3" fill="#f3c6c0" opacity={theme === 'night' ? 0.35 : 0.6} />

      {/* Sparkle / stars for night */}
      {theme === 'night' && (
        <g fill="#fff" opacity="0.9">
          <path d="M24 24 L26 30 L32 30 L27 34 L29 40 L24 36 L19 40 L21 34 L16 30 L22 30 Z" />
          <path d="M96 96 L98 100 L102 100 L99 103 L101 107 L98 104 L95 107 L97 103 L94 100 L98 100 Z" />
        </g>
      )}
    </svg>
  )
}

export default MilkCanMascot