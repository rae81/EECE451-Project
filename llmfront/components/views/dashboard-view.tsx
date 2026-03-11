'use client'

import { useEffect, useState } from 'react'
import { AppHeader } from '@/components/app-header'
import { MetricCard } from '@/components/dashboard/metric-card'
import { SignalGauge } from '@/components/dashboard/signal-gauge'
import { CellInfoCard } from '@/components/dashboard/cell-info-card'
import { SpeedTestCard } from '@/components/dashboard/speed-test-card'
import { LiveChart } from '@/components/dashboard/live-chart'
import {
  generateCurrentCell,
  generateNeighboringCells,
  generateNetworkStats,
} from '@/lib/mock-data'
import { CellData, NetworkStats } from '@/lib/types'
import {
  Activity,
  Download,
  Radio,
  Signal,
  Timer,
  Upload,
  Waves,
  Zap,
} from 'lucide-react'

export function DashboardView() {
  const [currentCell, setCurrentCell] = useState<CellData | null>(null)
  const [neighbors, setNeighbors] = useState<CellData[]>([])
  const [stats, setStats] = useState<NetworkStats | null>(null)
  const [isRefreshing, setIsRefreshing] = useState(false)

  const loadData = () => {
    setCurrentCell(generateCurrentCell())
    setNeighbors(generateNeighboringCells(4))
    setStats(generateNetworkStats())
  }

  const handleRefresh = async () => {
    setIsRefreshing(true)
    await new Promise((resolve) => setTimeout(resolve, 500))
    loadData()
    setIsRefreshing(false)
  }

  useEffect(() => {
    loadData()
    
    // Simulate live updates
    const interval = setInterval(() => {
      setCurrentCell((prev) => {
        if (!prev) return prev
        return {
          ...prev,
          signalStrength: prev.signalStrength + (Math.random() - 0.5) * 4,
          rsrp: prev.rsrp + (Math.random() - 0.5) * 2,
          rsrq: prev.rsrq + (Math.random() - 0.5) * 1,
          sinr: prev.sinr + (Math.random() - 0.5) * 2,
        }
      })
    }, 2000)

    return () => clearInterval(interval)
  }, [])

  if (!currentCell || !stats) {
    return (
      <div className="flex h-full items-center justify-center">
        <div className="flex flex-col items-center gap-3">
          <div className="h-10 w-10 animate-spin rounded-full border-2 border-primary border-t-transparent" />
          <span className="text-sm text-muted-foreground">Loading dashboard...</span>
        </div>
      </div>
    )
  }

  return (
    <div className="flex flex-col">
      <AppHeader
        title="Dashboard"
        subtitle="Real-time network monitoring"
        onRefresh={handleRefresh}
        isRefreshing={isRefreshing}
      />

      <div className="flex-1 space-y-6 p-6">
        {/* Top KPI Cards */}
        <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-4">
          <MetricCard
            title="Signal Strength"
            value={Math.round(currentCell.signalStrength)}
            unit="dBm"
            icon={Signal}
            change={2.4}
            changeLabel="vs last hour"
            trend="up"
            accentColor="var(--signal-good)"
          />
          <MetricCard
            title="Download Speed"
            value={currentCell.downloadSpeed?.toFixed(1) || 0}
            unit="Mbps"
            icon={Download}
            change={-1.2}
            changeLabel="vs last hour"
            trend="down"
            accentColor="var(--chart-1)"
          />
          <MetricCard
            title="Upload Speed"
            value={currentCell.uploadSpeed?.toFixed(1) || 0}
            unit="Mbps"
            icon={Upload}
            change={5.1}
            changeLabel="vs last hour"
            trend="up"
            accentColor="var(--chart-2)"
          />
          <MetricCard
            title="Latency"
            value={Math.round(currentCell.latency || 0)}
            unit="ms"
            icon={Timer}
            change={-8.3}
            changeLabel="vs last hour"
            trend="up"
            accentColor="var(--chart-3)"
          />
        </div>

        {/* Main Content Grid */}
        <div className="grid gap-6 lg:grid-cols-3">
          {/* Signal Gauge + Current Cell */}
          <div className="space-y-6 lg:col-span-1">
            {/* Signal Gauge Card */}
            <div className="rounded-xl border border-border bg-card p-6">
              <div className="mb-4 flex items-center justify-between">
                <h3 className="text-sm font-semibold text-foreground">Signal Quality</h3>
                <span className="rounded-full bg-primary/10 px-2.5 py-1 text-xs font-medium text-primary">
                  {currentCell.networkType}
                </span>
              </div>
              <div className="flex justify-center py-4">
                <SignalGauge value={Math.round(currentCell.signalStrength)} size={180} />
              </div>
              <div className="mt-4 grid grid-cols-2 gap-4 border-t border-border pt-4">
                <div className="text-center">
                  <span className="text-xs text-muted-foreground">RSRP</span>
                  <p className="font-mono text-lg font-semibold text-foreground">
                    {currentCell.rsrp.toFixed(0)} dBm
                  </p>
                </div>
                <div className="text-center">
                  <span className="text-xs text-muted-foreground">SINR</span>
                  <p className="font-mono text-lg font-semibold text-foreground">
                    {currentCell.sinr.toFixed(1)} dB
                  </p>
                </div>
              </div>
            </div>

            {/* Speed Test */}
            <SpeedTestCard
              downloadSpeed={currentCell.downloadSpeed || 0}
              uploadSpeed={currentCell.uploadSpeed || 0}
              latency={currentCell.latency || 0}
              onRunTest={() => {
                // Simulate speed test
                setTimeout(() => {
                  setCurrentCell((prev) => {
                    if (!prev) return prev
                    return {
                      ...prev,
                      downloadSpeed: 100 + Math.random() * 400,
                      uploadSpeed: 20 + Math.random() * 80,
                      latency: 10 + Math.random() * 30,
                    }
                  })
                }, 3000)
              }}
            />
          </div>

          {/* Charts */}
          <div className="space-y-6 lg:col-span-2">
            {/* Primary Cell Info */}
            <CellInfoCard cell={currentCell} isMain />

            {/* Signal Trend Chart */}
            <div className="rounded-xl border border-border bg-card p-5">
              <div className="mb-4 flex items-center justify-between">
                <h3 className="text-sm font-semibold text-foreground">Signal Trend</h3>
                <div className="flex items-center gap-4 text-xs">
                  <div className="flex items-center gap-1.5">
                    <div className="h-2 w-2 rounded-full bg-primary" />
                    <span className="text-muted-foreground">Signal Strength</span>
                  </div>
                </div>
              </div>
              <div className="h-[200px]">
                <LiveChart
                  title="Signal"
                  data={stats.signalTrend}
                  color="var(--primary)"
                  unit=" dBm"
                />
              </div>
            </div>

            {/* Neighboring Cells */}
            <div>
              <div className="mb-4 flex items-center justify-between">
                <h3 className="text-sm font-semibold text-foreground">Neighboring Cells</h3>
                <span className="text-xs text-muted-foreground">
                  {neighbors.length} cells detected
                </span>
              </div>
              <div className="grid gap-4 md:grid-cols-2">
                {neighbors.map((cell) => (
                  <CellInfoCard key={cell.id} cell={cell} />
                ))}
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}
