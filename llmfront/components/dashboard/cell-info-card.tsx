'use client'

import { cn } from '@/lib/utils'
import { CellData, getSignalQuality } from '@/lib/types'
import { Radio, Signal, Wifi } from 'lucide-react'

interface CellInfoCardProps {
  cell: CellData
  isMain?: boolean
}

export function CellInfoCard({ cell, isMain = false }: CellInfoCardProps) {
  const quality = getSignalQuality(cell.signalStrength)
  
  const qualityColors = {
    excellent: 'bg-[var(--signal-excellent)]',
    good: 'bg-[var(--signal-good)]',
    fair: 'bg-[var(--signal-fair)]',
    poor: 'bg-[var(--signal-poor)]',
    weak: 'bg-[var(--signal-weak)]',
  }
  
  const networkBadgeColors: Record<string, string> = {
    '5G': 'bg-primary/20 text-primary border-primary/30',
    '4G LTE': 'bg-blue-500/20 text-blue-400 border-blue-500/30',
    '3G': 'bg-amber-500/20 text-amber-400 border-amber-500/30',
    '2G': 'bg-red-500/20 text-red-400 border-red-500/30',
  }

  return (
    <div
      className={cn(
        'group relative overflow-hidden rounded-xl border border-border bg-card transition-all duration-300 hover:border-primary/30',
        isMain && 'ring-1 ring-primary/20'
      )}
    >
      {/* Header */}
      <div className="flex items-center justify-between border-b border-border/50 px-4 py-3">
        <div className="flex items-center gap-3">
          <div className={cn(
            'flex h-8 w-8 items-center justify-center rounded-lg',
            isMain ? 'bg-primary/20' : 'bg-secondary'
          )}>
            {isMain ? (
              <Radio className="h-4 w-4 text-primary" />
            ) : (
              <Signal className="h-4 w-4 text-muted-foreground" />
            )}
          </div>
          <div>
            <div className="flex items-center gap-2">
              <span className="font-mono text-sm font-medium text-foreground">
                {cell.cellId}
              </span>
              {isMain && (
                <span className="rounded bg-primary/20 px-1.5 py-0.5 text-[10px] font-semibold text-primary">
                  PRIMARY
                </span>
              )}
            </div>
            <span className="text-xs text-muted-foreground">{cell.operator}</span>
          </div>
        </div>
        
        <span className={cn(
          'rounded-full border px-2.5 py-1 text-xs font-semibold',
          networkBadgeColors[cell.networkType] || 'bg-secondary text-foreground'
        )}>
          {cell.networkType}
        </span>
      </div>

      {/* Metrics Grid */}
      <div className="grid grid-cols-2 gap-px bg-border/30">
        <MetricItem 
          label="Signal" 
          value={`${cell.signalStrength} dBm`}
          quality={quality}
        />
        <MetricItem label="RSRP" value={`${cell.rsrp} dBm`} />
        <MetricItem label="RSRQ" value={`${cell.rsrq} dB`} />
        <MetricItem label="SINR" value={`${cell.sinr} dB`} />
        <MetricItem label="Frequency" value={`${cell.frequency} MHz`} />
        <MetricItem label="Bandwidth" value={`${cell.bandwidth} MHz`} />
      </div>

      {/* Footer with LAC/MCC/MNC */}
      <div className="flex items-center justify-between border-t border-border/50 px-4 py-2.5">
        <div className="flex gap-4 text-xs">
          <span className="text-muted-foreground">
            LAC: <span className="font-mono text-foreground">{cell.lac}</span>
          </span>
          <span className="text-muted-foreground">
            MCC: <span className="font-mono text-foreground">{cell.mcc}</span>
          </span>
          <span className="text-muted-foreground">
            MNC: <span className="font-mono text-foreground">{cell.mnc}</span>
          </span>
        </div>
      </div>
    </div>
  )
}

function MetricItem({ 
  label, 
  value, 
  quality 
}: { 
  label: string
  value: string
  quality?: string 
}) {
  const qualityColors: Record<string, string> = {
    excellent: 'text-[var(--signal-excellent)]',
    good: 'text-[var(--signal-good)]',
    fair: 'text-[var(--signal-fair)]',
    poor: 'text-[var(--signal-poor)]',
    weak: 'text-[var(--signal-weak)]',
  }
  
  return (
    <div className="bg-card px-4 py-3">
      <span className="text-[10px] font-medium uppercase tracking-wider text-muted-foreground">
        {label}
      </span>
      <p className={cn(
        'mt-0.5 font-mono text-sm font-semibold',
        quality ? qualityColors[quality] : 'text-foreground'
      )}>
        {value}
      </p>
    </div>
  )
}
