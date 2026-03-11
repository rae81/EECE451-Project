'use client'

import { cn } from '@/lib/utils'
import { ArrowDown, ArrowUp, Minus } from 'lucide-react'
import type { LucideIcon } from 'lucide-react'

interface MetricCardProps {
  title: string
  value: string | number
  unit?: string
  change?: number
  changeLabel?: string
  icon?: LucideIcon
  trend?: 'up' | 'down' | 'neutral'
  className?: string
  accentColor?: string
}

export function MetricCard({
  title,
  value,
  unit,
  change,
  changeLabel,
  icon: Icon,
  trend,
  className,
  accentColor = 'var(--primary)',
}: MetricCardProps) {
  const TrendIcon = trend === 'up' ? ArrowUp : trend === 'down' ? ArrowDown : Minus
  
  const trendColors = {
    up: 'text-[var(--signal-excellent)]',
    down: 'text-[var(--signal-weak)]',
    neutral: 'text-muted-foreground',
  }
  
  return (
    <div
      className={cn(
        'group relative overflow-hidden rounded-xl border border-border bg-card p-5 transition-all duration-300 hover:border-primary/30 hover:shadow-lg hover:shadow-primary/5',
        className
      )}
    >
      {/* Subtle accent glow */}
      <div
        className="pointer-events-none absolute -right-8 -top-8 h-24 w-24 rounded-full opacity-10 blur-2xl transition-opacity group-hover:opacity-20"
        style={{ backgroundColor: accentColor }}
      />
      
      <div className="flex items-start justify-between">
        <div className="flex flex-col gap-1">
          <span className="text-xs font-medium uppercase tracking-wider text-muted-foreground">
            {title}
          </span>
          <div className="flex items-baseline gap-1.5">
            <span className="font-mono text-2xl font-bold text-foreground">
              {typeof value === 'number' ? value.toLocaleString() : value}
            </span>
            {unit && (
              <span className="text-sm text-muted-foreground">{unit}</span>
            )}
          </div>
        </div>
        
        {Icon && (
          <div
            className="flex h-10 w-10 items-center justify-center rounded-lg"
            style={{ backgroundColor: `${accentColor}15` }}
          >
            <Icon className="h-5 w-5" style={{ color: accentColor }} />
          </div>
        )}
      </div>
      
      {(change !== undefined || changeLabel) && (
        <div className="mt-3 flex items-center gap-1.5 border-t border-border/50 pt-3">
          {change !== undefined && (
            <>
              <TrendIcon className={cn('h-3.5 w-3.5', trendColors[trend || 'neutral'])} />
              <span className={cn('text-xs font-medium', trendColors[trend || 'neutral'])}>
                {change > 0 ? '+' : ''}{change}%
              </span>
            </>
          )}
          {changeLabel && (
            <span className="text-xs text-muted-foreground">{changeLabel}</span>
          )}
        </div>
      )}
    </div>
  )
}
