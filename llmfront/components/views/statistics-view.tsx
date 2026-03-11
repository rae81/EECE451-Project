'use client'

import { useEffect, useState } from 'react'
import { AppHeader } from '@/components/app-header'
import { generateNetworkStats } from '@/lib/mock-data'
import { NetworkStats } from '@/lib/types'
import {
  Area,
  AreaChart,
  Bar,
  BarChart,
  Cell,
  Legend,
  Line,
  LineChart,
  Pie,
  PieChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import { Activity, BarChart3, PieChart as PieChartIcon, TrendingUp } from 'lucide-react'

const COLORS = {
  '5G': 'var(--primary)',
  '4G LTE': 'var(--chart-2)',
  '3G': 'var(--chart-3)',
  '2G': 'var(--chart-4)',
}

export function StatisticsView() {
  const [stats, setStats] = useState<NetworkStats | null>(null)
  const [isRefreshing, setIsRefreshing] = useState(false)
  const [activeTab, setActiveTab] = useState<'signal' | 'speed' | 'distribution'>('signal')

  useEffect(() => {
    setStats(generateNetworkStats())
  }, [])

  const handleRefresh = async () => {
    setIsRefreshing(true)
    await new Promise((resolve) => setTimeout(resolve, 500))
    setStats(generateNetworkStats())
    setIsRefreshing(false)
  }

  if (!stats) {
    return (
      <div className="flex h-full items-center justify-center">
        <div className="h-10 w-10 animate-spin rounded-full border-2 border-primary border-t-transparent" />
      </div>
    )
  }

  const pieData = Object.entries(stats.networkTypeDistribution).map(([name, value]) => ({
    name,
    value,
  }))

  const formatTime = (timestamp: number) => {
    return new Date(timestamp).toLocaleTimeString('en-US', {
      hour: '2-digit',
      minute: '2-digit',
    })
  }

  return (
    <div className="flex flex-col">
      <AppHeader
        title="Statistics"
        subtitle="Network performance analytics"
        onRefresh={handleRefresh}
        isRefreshing={isRefreshing}
      />

      <div className="flex-1 space-y-6 p-6">
        {/* Summary Cards */}
        <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-4">
          <SummaryCard
            title="Avg Signal Strength"
            value={`${stats.avgSignalStrength.toFixed(0)} dBm`}
            icon={Activity}
            trend="+2.3%"
            trendUp
          />
          <SummaryCard
            title="Avg Download"
            value={`${stats.avgDownloadSpeed.toFixed(1)} Mbps`}
            icon={TrendingUp}
            trend="+5.7%"
            trendUp
          />
          <SummaryCard
            title="Avg Upload"
            value={`${stats.avgUploadSpeed.toFixed(1)} Mbps`}
            icon={BarChart3}
            trend="-1.2%"
            trendUp={false}
          />
          <SummaryCard
            title="Total Measurements"
            value={stats.totalMeasurements.toLocaleString()}
            icon={PieChartIcon}
            trend="+124"
            trendUp
          />
        </div>

        {/* Chart Tabs */}
        <div className="flex gap-2 border-b border-border pb-4">
          {[
            { id: 'signal', label: 'Signal Trend' },
            { id: 'speed', label: 'Speed Analysis' },
            { id: 'distribution', label: 'Network Distribution' },
          ].map((tab) => (
            <button
              key={tab.id}
              onClick={() => setActiveTab(tab.id as typeof activeTab)}
              className={`rounded-lg px-4 py-2 text-sm font-medium transition-colors ${
                activeTab === tab.id
                  ? 'bg-primary text-primary-foreground'
                  : 'text-muted-foreground hover:bg-secondary hover:text-foreground'
              }`}
            >
              {tab.label}
            </button>
          ))}
        </div>

        {/* Charts */}
        <div className="grid gap-6 lg:grid-cols-2">
          {activeTab === 'signal' && (
            <>
              {/* Signal Trend Chart */}
              <div className="rounded-xl border border-border bg-card p-6 lg:col-span-2">
                <h3 className="mb-4 text-sm font-semibold text-foreground">
                  24-Hour Signal Strength Trend
                </h3>
                <div className="h-[300px]">
                  <ResponsiveContainer width="100%" height="100%">
                    <AreaChart data={stats.signalTrend}>
                      <defs>
                        <linearGradient id="signalGradient" x1="0" y1="0" x2="0" y2="1">
                          <stop offset="0%" stopColor="var(--primary)" stopOpacity={0.3} />
                          <stop offset="100%" stopColor="var(--primary)" stopOpacity={0} />
                        </linearGradient>
                      </defs>
                      <XAxis
                        dataKey="time"
                        tickFormatter={formatTime}
                        tick={{ fontSize: 11, fill: 'var(--muted-foreground)' }}
                        axisLine={{ stroke: 'var(--border)' }}
                        tickLine={false}
                      />
                      <YAxis
                        domain={[-110, -60]}
                        tick={{ fontSize: 11, fill: 'var(--muted-foreground)' }}
                        axisLine={false}
                        tickLine={false}
                        tickFormatter={(value) => `${value}`}
                      />
                      <Tooltip
                        contentStyle={{
                          backgroundColor: 'var(--card)',
                          border: '1px solid var(--border)',
                          borderRadius: '8px',
                        }}
                        labelFormatter={(value) => formatTime(value as number)}
                        formatter={(value: number) => [`${value.toFixed(1)} dBm`, 'Signal']}
                      />
                      <Area
                        type="monotone"
                        dataKey="value"
                        stroke="var(--primary)"
                        strokeWidth={2}
                        fill="url(#signalGradient)"
                      />
                    </AreaChart>
                  </ResponsiveContainer>
                </div>
              </div>
            </>
          )}

          {activeTab === 'speed' && (
            <>
              {/* Download/Upload Comparison */}
              <div className="rounded-xl border border-border bg-card p-6 lg:col-span-2">
                <h3 className="mb-4 text-sm font-semibold text-foreground">
                  Download vs Upload Speed
                </h3>
                <div className="h-[300px]">
                  <ResponsiveContainer width="100%" height="100%">
                    <LineChart data={stats.speedTrend}>
                      <XAxis
                        dataKey="time"
                        tickFormatter={formatTime}
                        tick={{ fontSize: 11, fill: 'var(--muted-foreground)' }}
                        axisLine={{ stroke: 'var(--border)' }}
                        tickLine={false}
                      />
                      <YAxis
                        tick={{ fontSize: 11, fill: 'var(--muted-foreground)' }}
                        axisLine={false}
                        tickLine={false}
                      />
                      <Tooltip
                        contentStyle={{
                          backgroundColor: 'var(--card)',
                          border: '1px solid var(--border)',
                          borderRadius: '8px',
                        }}
                        labelFormatter={(value) => formatTime(value as number)}
                      />
                      <Legend />
                      <Line
                        type="monotone"
                        dataKey="download"
                        name="Download"
                        stroke="var(--primary)"
                        strokeWidth={2}
                        dot={false}
                      />
                      <Line
                        type="monotone"
                        dataKey="upload"
                        name="Upload"
                        stroke="var(--chart-2)"
                        strokeWidth={2}
                        dot={false}
                      />
                    </LineChart>
                  </ResponsiveContainer>
                </div>
              </div>

              {/* Speed Distribution */}
              <div className="rounded-xl border border-border bg-card p-6">
                <h3 className="mb-4 text-sm font-semibold text-foreground">
                  Speed Distribution by Network Type
                </h3>
                <div className="h-[250px]">
                  <ResponsiveContainer width="100%" height="100%">
                    <BarChart
                      data={[
                        { type: '5G', download: 350, upload: 75 },
                        { type: '4G LTE', download: 150, upload: 45 },
                        { type: '3G', download: 25, upload: 8 },
                        { type: '2G', download: 2, upload: 0.5 },
                      ]}
                      layout="vertical"
                    >
                      <XAxis type="number" tick={{ fontSize: 11, fill: 'var(--muted-foreground)' }} />
                      <YAxis
                        type="category"
                        dataKey="type"
                        tick={{ fontSize: 11, fill: 'var(--muted-foreground)' }}
                        axisLine={false}
                      />
                      <Tooltip
                        contentStyle={{
                          backgroundColor: 'var(--card)',
                          border: '1px solid var(--border)',
                          borderRadius: '8px',
                        }}
                      />
                      <Bar dataKey="download" name="Download (Mbps)" fill="var(--primary)" radius={4} />
                      <Bar dataKey="upload" name="Upload (Mbps)" fill="var(--chart-2)" radius={4} />
                    </BarChart>
                  </ResponsiveContainer>
                </div>
              </div>

              {/* Latency Chart */}
              <div className="rounded-xl border border-border bg-card p-6">
                <h3 className="mb-4 text-sm font-semibold text-foreground">
                  Latency by Network Type
                </h3>
                <div className="h-[250px]">
                  <ResponsiveContainer width="100%" height="100%">
                    <BarChart
                      data={[
                        { type: '5G', latency: 12 },
                        { type: '4G LTE', latency: 35 },
                        { type: '3G', latency: 120 },
                        { type: '2G', latency: 400 },
                      ]}
                    >
                      <XAxis
                        dataKey="type"
                        tick={{ fontSize: 11, fill: 'var(--muted-foreground)' }}
                        axisLine={{ stroke: 'var(--border)' }}
                        tickLine={false}
                      />
                      <YAxis
                        tick={{ fontSize: 11, fill: 'var(--muted-foreground)' }}
                        axisLine={false}
                        tickLine={false}
                      />
                      <Tooltip
                        contentStyle={{
                          backgroundColor: 'var(--card)',
                          border: '1px solid var(--border)',
                          borderRadius: '8px',
                        }}
                        formatter={(value: number) => [`${value} ms`, 'Latency']}
                      />
                      <Bar dataKey="latency" fill="var(--chart-3)" radius={[4, 4, 0, 0]} />
                    </BarChart>
                  </ResponsiveContainer>
                </div>
              </div>
            </>
          )}

          {activeTab === 'distribution' && (
            <>
              {/* Network Type Distribution Pie */}
              <div className="rounded-xl border border-border bg-card p-6">
                <h3 className="mb-4 text-sm font-semibold text-foreground">
                  Network Type Distribution
                </h3>
                <div className="h-[300px]">
                  <ResponsiveContainer width="100%" height="100%">
                    <PieChart>
                      <Pie
                        data={pieData}
                        cx="50%"
                        cy="50%"
                        innerRadius={60}
                        outerRadius={100}
                        paddingAngle={2}
                        dataKey="value"
                      >
                        {pieData.map((entry) => (
                          <Cell
                            key={entry.name}
                            fill={COLORS[entry.name as keyof typeof COLORS]}
                          />
                        ))}
                      </Pie>
                      <Tooltip
                        contentStyle={{
                          backgroundColor: 'var(--card)',
                          border: '1px solid var(--border)',
                          borderRadius: '8px',
                        }}
                        formatter={(value: number) => [`${value}%`, 'Usage']}
                      />
                      <Legend />
                    </PieChart>
                  </ResponsiveContainer>
                </div>
              </div>

              {/* Network Usage Over Time */}
              <div className="rounded-xl border border-border bg-card p-6">
                <h3 className="mb-4 text-sm font-semibold text-foreground">
                  Measurements by Network Type
                </h3>
                <div className="space-y-4">
                  {Object.entries(stats.networkTypeDistribution).map(([type, percentage]) => (
                    <div key={type}>
                      <div className="mb-1.5 flex items-center justify-between text-sm">
                        <span className="font-medium text-foreground">{type}</span>
                        <span className="font-mono text-muted-foreground">{percentage}%</span>
                      </div>
                      <div className="h-3 overflow-hidden rounded-full bg-secondary">
                        <div
                          className="h-full rounded-full transition-all duration-500"
                          style={{
                            width: `${percentage}%`,
                            backgroundColor: COLORS[type as keyof typeof COLORS],
                          }}
                        />
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            </>
          )}
        </div>
      </div>
    </div>
  )
}

function SummaryCard({
  title,
  value,
  icon: Icon,
  trend,
  trendUp,
}: {
  title: string
  value: string
  icon: typeof Activity
  trend: string
  trendUp: boolean
}) {
  return (
    <div className="rounded-xl border border-border bg-card p-5">
      <div className="flex items-center justify-between">
        <span className="text-xs font-medium uppercase tracking-wider text-muted-foreground">
          {title}
        </span>
        <Icon className="h-4 w-4 text-muted-foreground" />
      </div>
      <div className="mt-2 flex items-baseline gap-2">
        <span className="font-mono text-2xl font-bold text-foreground">{value}</span>
        <span
          className={`text-xs font-medium ${
            trendUp ? 'text-[var(--signal-excellent)]' : 'text-[var(--signal-weak)]'
          }`}
        >
          {trend}
        </span>
      </div>
    </div>
  )
}
