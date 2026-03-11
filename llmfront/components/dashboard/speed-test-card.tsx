'use client'

import { useState } from 'react'
import { cn } from '@/lib/utils'
import { ArrowDown, ArrowUp, Clock, Play, Loader2 } from 'lucide-react'
import { Button } from '@/components/ui/button'

interface SpeedTestCardProps {
  downloadSpeed?: number
  uploadSpeed?: number
  latency?: number
  onRunTest?: () => void
}

export function SpeedTestCard({
  downloadSpeed = 0,
  uploadSpeed = 0,
  latency = 0,
  onRunTest,
}: SpeedTestCardProps) {
  const [isRunning, setIsRunning] = useState(false)
  const [progress, setProgress] = useState(0)

  const handleRunTest = async () => {
    setIsRunning(true)
    setProgress(0)
    
    // Simulate test progress
    const interval = setInterval(() => {
      setProgress((prev) => {
        if (prev >= 100) {
          clearInterval(interval)
          setIsRunning(false)
          return 0
        }
        return prev + 5
      })
    }, 150)
    
    onRunTest?.()
  }

  return (
    <div className="relative overflow-hidden rounded-xl border border-border bg-card">
      {/* Header */}
      <div className="flex items-center justify-between border-b border-border/50 px-5 py-4">
        <div>
          <h3 className="text-sm font-semibold text-foreground">Speed Test</h3>
          <p className="text-xs text-muted-foreground">Last run: 2 minutes ago</p>
        </div>
        <Button
          onClick={handleRunTest}
          disabled={isRunning}
          size="sm"
          className="gap-2"
        >
          {isRunning ? (
            <>
              <Loader2 className="h-4 w-4 animate-spin" />
              Testing...
            </>
          ) : (
            <>
              <Play className="h-4 w-4" />
              Run Test
            </>
          )}
        </Button>
      </div>

      {/* Progress Bar */}
      {isRunning && (
        <div className="h-1 bg-secondary">
          <div
            className="h-full bg-primary transition-all duration-150"
            style={{ width: `${progress}%` }}
          />
        </div>
      )}

      {/* Speed Metrics */}
      <div className="grid grid-cols-3 divide-x divide-border">
        <SpeedMetric
          icon={ArrowDown}
          label="Download"
          value={downloadSpeed}
          unit="Mbps"
          color="var(--signal-excellent)"
        />
        <SpeedMetric
          icon={ArrowUp}
          label="Upload"
          value={uploadSpeed}
          unit="Mbps"
          color="var(--chart-2)"
        />
        <SpeedMetric
          icon={Clock}
          label="Latency"
          value={latency}
          unit="ms"
          color="var(--chart-3)"
        />
      </div>

      {/* Speed Bars */}
      <div className="space-y-3 px-5 py-4">
        <SpeedBar
          label="Download"
          value={downloadSpeed}
          max={500}
          color="var(--signal-excellent)"
        />
        <SpeedBar
          label="Upload"
          value={uploadSpeed}
          max={100}
          color="var(--chart-2)"
        />
      </div>
    </div>
  )
}

function SpeedMetric({
  icon: Icon,
  label,
  value,
  unit,
  color,
}: {
  icon: typeof ArrowDown
  label: string
  value: number
  unit: string
  color: string
}) {
  return (
    <div className="flex flex-col items-center px-4 py-5">
      <div
        className="mb-2 flex h-10 w-10 items-center justify-center rounded-full"
        style={{ backgroundColor: `${color}20` }}
      >
        <Icon className="h-5 w-5" style={{ color }} />
      </div>
      <span className="text-xs text-muted-foreground">{label}</span>
      <div className="flex items-baseline gap-1">
        <span className="font-mono text-xl font-bold text-foreground">
          {value.toFixed(1)}
        </span>
        <span className="text-xs text-muted-foreground">{unit}</span>
      </div>
    </div>
  )
}

function SpeedBar({
  label,
  value,
  max,
  color,
}: {
  label: string
  value: number
  max: number
  color: string
}) {
  const percentage = Math.min(100, (value / max) * 100)
  
  return (
    <div>
      <div className="mb-1.5 flex items-center justify-between text-xs">
        <span className="text-muted-foreground">{label}</span>
        <span className="font-mono text-foreground">{value.toFixed(1)} Mbps</span>
      </div>
      <div className="h-2 overflow-hidden rounded-full bg-secondary">
        <div
          className="h-full rounded-full transition-all duration-500"
          style={{ 
            width: `${percentage}%`,
            backgroundColor: color,
            boxShadow: `0 0 8px ${color}`,
          }}
        />
      </div>
    </div>
  )
}
