export type TimeTheme = 'morning' | 'afternoon' | 'evening' | 'night'

export function getTimeTheme(date: Date = new Date()): TimeTheme {
  const hour = date.getHours()
  if (hour >= 5 && hour < 11) return 'morning'
  if (hour >= 11 && hour < 17) return 'afternoon'
  if (hour >= 17 && hour < 20) return 'evening'
  return 'night'
}

export function themeAriaLabel(theme: TimeTheme): string {
  switch (theme) {
    case 'morning':
      return 'Morning theme: sunrise'
    case 'afternoon':
      return 'Afternoon theme: sunny sky'
    case 'evening':
      return 'Evening theme: sunset'
    case 'night':
      return 'Night theme: moon and stars'
  }
}