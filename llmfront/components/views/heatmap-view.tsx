'use client'

import { useEffect, useRef, useState } from 'react'
import { AppHeader } from '@/components/app-header'
import { generateHeatmapData } from '@/lib/mock-data'
import { HeatmapPoint } from '@/lib/types'
import { Filter, Layers, MapPin, ZoomIn, ZoomOut } from 'lucide-react'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'

export function HeatmapView() {
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const [points, setPoints] = useState<HeatmapPoint[]>([])
  const [isRefreshing, setIsRefreshing] = useState(false)
  const [zoom, setZoom] = useState(1)
  const [filters, setFilters] = useState({
    '5G': true,
    '4G LTE': true,
    '3G': true,
  })
  const [selectedPoint, setSelectedPoint] = useState<HeatmapPoint | null>(null)

  useEffect(() => {
    setPoints(generateHeatmapData())
  }, [])

  useEffect(() => {
    renderHeatmap()
  }, [points, zoom, filters])

  const renderHeatmap = () => {
    const canvas = canvasRef.current
    if (!canvas) return

    const ctx = canvas.getContext('2d')
    if (!ctx) return

    const dpr = window.devicePixelRatio || 1
    const rect = canvas.getBoundingClientRect()
    canvas.width = rect.width * dpr
    canvas.height = rect.height * dpr
    ctx.scale(dpr, dpr)

    // Dark map background
    ctx.fillStyle = '#0a0c10'
    ctx.fillRect(0, 0, rect.width, rect.height)

    // Draw grid
    ctx.strokeStyle = 'rgba(255, 255, 255, 0.03)'
    ctx.lineWidth = 1
    const gridSize = 40 * zoom

    for (let x = 0; x < rect.width; x += gridSize) {
      ctx.beginPath()
      ctx.moveTo(x, 0)
      ctx.lineTo(x, rect.height)
      ctx.stroke()
    }
    for (let y = 0; y < rect.height; y += gridSize) {
      ctx.beginPath()
      ctx.moveTo(0, y)
      ctx.lineTo(rect.width, y)
      ctx.stroke()
    }

    // Filter and render points
    const filteredPoints = points.filter(
      (p) => filters[p.networkType as keyof typeof filters]
    )

    // Draw heatmap circles
    filteredPoints.forEach((point) => {
      const x = ((point.lng + 74.05) / 0.1) * rect.width * zoom
      const y = ((40.75 - point.lat) / 0.06) * rect.height * zoom
      const radius = 15 + point.intensity * 25

      const gradient = ctx.createRadialGradient(x, y, 0, x, y, radius)
      const color = getNetworkColor(point.networkType)

      gradient.addColorStop(0, `${color}80`)
      gradient.addColorStop(0.4, `${color}40`)
      gradient.addColorStop(1, `${color}00`)

      ctx.beginPath()
      ctx.arc(x, y, radius, 0, Math.PI * 2)
      ctx.fillStyle = gradient
      ctx.fill()
    })

    // Draw point markers
    filteredPoints.forEach((point) => {
      const x = ((point.lng + 74.05) / 0.1) * rect.width * zoom
      const y = ((40.75 - point.lat) / 0.06) * rect.height * zoom
      const color = getNetworkColor(point.networkType)

      ctx.beginPath()
      ctx.arc(x, y, 3, 0, Math.PI * 2)
      ctx.fillStyle = color
      ctx.fill()
    })
  }

  const getNetworkColor = (type: string): string => {
    switch (type) {
      case '5G':
        return '#22c55e'
      case '4G LTE':
        return '#3b82f6'
      case '3G':
        return '#f59e0b'
      default:
        return '#ef4444'
    }
  }

  const handleRefresh = async () => {
    setIsRefreshing(true)
    await new Promise((resolve) => setTimeout(resolve, 500))
    setPoints(generateHeatmapData())
    setIsRefreshing(false)
  }

  const handleCanvasClick = (e: React.MouseEvent<HTMLCanvasElement>) => {
    const canvas = canvasRef.current
    if (!canvas) return

    const rect = canvas.getBoundingClientRect()
    const x = e.clientX - rect.left
    const y = e.clientY - rect.top

    // Find nearest point
    const threshold = 20
    let nearest: HeatmapPoint | null = null
    let minDist = Infinity

    points.forEach((point) => {
      const px = ((point.lng + 74.05) / 0.1) * rect.width * zoom
      const py = ((40.75 - point.lat) / 0.06) * rect.height * zoom
      const dist = Math.sqrt((x - px) ** 2 + (y - py) ** 2)

      if (dist < threshold && dist < minDist) {
        minDist = dist
        nearest = point
      }
    })

    setSelectedPoint(nearest)
  }

  const stats = {
    total: points.length,
    '5G': points.filter((p) => p.networkType === '5G').length,
    '4G LTE': points.filter((p) => p.networkType === '4G LTE').length,
    '3G': points.filter((p) => p.networkType === '3G').length,
    avgSignal: Math.round(
      points.reduce((sum, p) => sum + p.signalStrength, 0) / points.length
    ),
  }

  return (
    <div className="flex h-full flex-col">
      <AppHeader
        title="Coverage Heatmap"
        subtitle="Signal strength visualization"
        onRefresh={handleRefresh}
        isRefreshing={isRefreshing}
      />

      <div className="flex flex-1 overflow-hidden">
        {/* Map Area */}
        <div className="relative flex-1">
          <canvas
            ref={canvasRef}
            className="h-full w-full cursor-crosshair"
            onClick={handleCanvasClick}
          />

          {/* Zoom Controls */}
          <div className="absolute right-4 top-4 flex flex-col gap-2">
            <Button
              variant="secondary"
              size="icon"
              onClick={() => setZoom((z) => Math.min(z + 0.25, 3))}
              className="bg-card/90 backdrop-blur-sm"
            >
              <ZoomIn className="h-4 w-4" />
            </Button>
            <Button
              variant="secondary"
              size="icon"
              onClick={() => setZoom((z) => Math.max(z - 0.25, 0.5))}
              className="bg-card/90 backdrop-blur-sm"
            >
              <ZoomOut className="h-4 w-4" />
            </Button>
          </div>

          {/* Filter Controls */}
          <div className="absolute left-4 top-4">
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button variant="secondary" className="gap-2 bg-card/90 backdrop-blur-sm">
                  <Filter className="h-4 w-4" />
                  Filters
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent>
                <DropdownMenuLabel>Network Types</DropdownMenuLabel>
                <DropdownMenuSeparator />
                {Object.entries(filters).map(([type, enabled]) => (
                  <DropdownMenuCheckboxItem
                    key={type}
                    checked={enabled}
                    onCheckedChange={(checked) =>
                      setFilters((f) => ({ ...f, [type]: checked }))
                    }
                  >
                    <div className="flex items-center gap-2">
                      <div
                        className="h-2 w-2 rounded-full"
                        style={{ backgroundColor: getNetworkColor(type) }}
                      />
                      {type}
                    </div>
                  </DropdownMenuCheckboxItem>
                ))}
              </DropdownMenuContent>
            </DropdownMenu>
          </div>

          {/* Legend */}
          <div className="absolute bottom-4 left-4 rounded-lg border border-border bg-card/90 p-4 backdrop-blur-sm">
            <h4 className="mb-3 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
              Signal Strength
            </h4>
            <div className="flex items-center gap-2">
              <div className="h-3 w-32 rounded-full bg-gradient-to-r from-[#ef4444] via-[#f59e0b] to-[#22c55e]" />
              <div className="flex justify-between text-xs text-muted-foreground">
                <span>-120</span>
                <span>-50 dBm</span>
              </div>
            </div>
            <div className="mt-3 space-y-1.5">
              {[
                { type: '5G', color: '#22c55e' },
                { type: '4G LTE', color: '#3b82f6' },
                { type: '3G', color: '#f59e0b' },
              ].map(({ type, color }) => (
                <div key={type} className="flex items-center gap-2 text-xs">
                  <div
                    className="h-2 w-2 rounded-full"
                    style={{ backgroundColor: color }}
                  />
                  <span className="text-foreground">{type}</span>
                </div>
              ))}
            </div>
          </div>

          {/* Selected Point Info */}
          {selectedPoint && (
            <div className="absolute bottom-4 right-4 w-64 rounded-lg border border-border bg-card/90 p-4 backdrop-blur-sm">
              <div className="mb-2 flex items-center justify-between">
                <h4 className="text-sm font-semibold text-foreground">
                  Measurement Point
                </h4>
                <button
                  onClick={() => setSelectedPoint(null)}
                  className="text-muted-foreground hover:text-foreground"
                >
                  x
                </button>
              </div>
              <div className="space-y-2 text-xs">
                <div className="flex justify-between">
                  <span className="text-muted-foreground">Network</span>
                  <span className="font-medium text-foreground">
                    {selectedPoint.networkType}
                  </span>
                </div>
                <div className="flex justify-between">
                  <span className="text-muted-foreground">Signal</span>
                  <span className="font-mono font-medium text-foreground">
                    {selectedPoint.signalStrength} dBm
                  </span>
                </div>
                <div className="flex justify-between">
                  <span className="text-muted-foreground">Location</span>
                  <span className="font-mono text-foreground">
                    {selectedPoint.lat.toFixed(4)}, {selectedPoint.lng.toFixed(4)}
                  </span>
                </div>
              </div>
            </div>
          )}
        </div>

        {/* Sidebar Stats */}
        <div className="w-72 border-l border-border bg-card p-6">
          <h3 className="mb-4 text-sm font-semibold text-foreground">Coverage Statistics</h3>

          <div className="space-y-4">
            <StatItem label="Total Points" value={stats.total.toString()} />
            <StatItem
              label="5G Coverage"
              value={`${((stats['5G'] / stats.total) * 100).toFixed(1)}%`}
              color="#22c55e"
            />
            <StatItem
              label="4G LTE Coverage"
              value={`${((stats['4G LTE'] / stats.total) * 100).toFixed(1)}%`}
              color="#3b82f6"
            />
            <StatItem
              label="3G Coverage"
              value={`${((stats['3G'] / stats.total) * 100).toFixed(1)}%`}
              color="#f59e0b"
            />
            <StatItem
              label="Avg Signal"
              value={`${stats.avgSignal} dBm`}
            />
          </div>

          <div className="mt-6 border-t border-border pt-6">
            <h4 className="mb-3 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
              Coverage Quality
            </h4>
            <div className="space-y-3">
              <QualityBar label="Excellent" percentage={32} color="#22c55e" />
              <QualityBar label="Good" percentage={45} color="#4ade80" />
              <QualityBar label="Fair" percentage={15} color="#fbbf24" />
              <QualityBar label="Poor" percentage={8} color="#ef4444" />
            </div>
          </div>

          <div className="mt-6 border-t border-border pt-6">
            <Button className="w-full gap-2">
              <Layers className="h-4 w-4" />
              Export Coverage Data
            </Button>
          </div>
        </div>
      </div>
    </div>
  )
}

function StatItem({
  label,
  value,
  color,
}: {
  label: string
  value: string
  color?: string
}) {
  return (
    <div className="flex items-center justify-between">
      <div className="flex items-center gap-2">
        {color && (
          <div
            className="h-2 w-2 rounded-full"
            style={{ backgroundColor: color }}
          />
        )}
        <span className="text-sm text-muted-foreground">{label}</span>
      </div>
      <span className="font-mono text-sm font-semibold text-foreground">{value}</span>
    </div>
  )
}

function QualityBar({
  label,
  percentage,
  color,
}: {
  label: string
  percentage: number
  color: string
}) {
  return (
    <div>
      <div className="mb-1 flex items-center justify-between text-xs">
        <span className="text-muted-foreground">{label}</span>
        <span className="font-mono text-foreground">{percentage}%</span>
      </div>
      <div className="h-1.5 overflow-hidden rounded-full bg-secondary">
        <div
          className="h-full rounded-full transition-all duration-500"
          style={{ width: `${percentage}%`, backgroundColor: color }}
        />
      </div>
    </div>
  )
}
