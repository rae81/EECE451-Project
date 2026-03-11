'use client'

import { useEffect, useState } from 'react'
import { AppHeader } from '@/components/app-header'
import { generateDevices } from '@/lib/mock-data'
import { DeviceInfo } from '@/lib/types'
import { cn } from '@/lib/utils'
import {
  Battery,
  BatteryLow,
  BatteryMedium,
  MoreVertical,
  Plus,
  RefreshCw,
  Smartphone,
  Trash2,
  Wifi,
  WifiOff,
} from 'lucide-react'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'

export function DevicesView() {
  const [devices, setDevices] = useState<DeviceInfo[]>([])
  const [isRefreshing, setIsRefreshing] = useState(false)

  useEffect(() => {
    setDevices(generateDevices())
  }, [])

  const handleRefresh = async () => {
    setIsRefreshing(true)
    await new Promise((resolve) => setTimeout(resolve, 500))
    setDevices(generateDevices())
    setIsRefreshing(false)
  }

  const formatLastSeen = (timestamp: number) => {
    const diff = Date.now() - timestamp
    const minutes = Math.floor(diff / 60000)
    const hours = Math.floor(diff / 3600000)
    const days = Math.floor(diff / 86400000)

    if (minutes < 1) return 'Just now'
    if (minutes < 60) return `${minutes} min ago`
    if (hours < 24) return `${hours} hours ago`
    return `${days} days ago`
  }

  const getStatusColor = (status: DeviceInfo['status']) => {
    switch (status) {
      case 'online':
        return 'bg-[var(--signal-excellent)]'
      case 'idle':
        return 'bg-[var(--signal-fair)]'
      case 'offline':
        return 'bg-[var(--signal-weak)]'
    }
  }

  const getStatusBadge = (status: DeviceInfo['status']) => {
    switch (status) {
      case 'online':
        return 'bg-[var(--signal-excellent)]/20 text-[var(--signal-excellent)] border-[var(--signal-excellent)]/30'
      case 'idle':
        return 'bg-[var(--signal-fair)]/20 text-[var(--signal-fair)] border-[var(--signal-fair)]/30'
      case 'offline':
        return 'bg-[var(--signal-weak)]/20 text-[var(--signal-weak)] border-[var(--signal-weak)]/30'
    }
  }

  const getBatteryIcon = (level?: number) => {
    if (!level) return Battery
    if (level < 20) return BatteryLow
    if (level < 50) return BatteryMedium
    return Battery
  }

  const getBatteryColor = (level?: number) => {
    if (!level) return 'text-muted-foreground'
    if (level < 20) return 'text-[var(--signal-weak)]'
    if (level < 50) return 'text-[var(--signal-fair)]'
    return 'text-[var(--signal-excellent)]'
  }

  return (
    <div className="flex flex-col">
      <AppHeader
        title="Devices"
        subtitle="Connected device management"
        onRefresh={handleRefresh}
        isRefreshing={isRefreshing}
      />

      <div className="flex-1 space-y-6 p-6">
        {/* Summary Stats */}
        <div className="grid gap-4 md:grid-cols-3">
          <StatCard
            label="Total Devices"
            value={devices.length.toString()}
            icon={Smartphone}
          />
          <StatCard
            label="Online"
            value={devices.filter((d) => d.status === 'online').length.toString()}
            icon={Wifi}
            color="var(--signal-excellent)"
          />
          <StatCard
            label="Total Measurements"
            value={devices
              .reduce((sum, d) => sum + d.totalMeasurements, 0)
              .toLocaleString()}
            icon={RefreshCw}
          />
        </div>

        {/* Add Device Button */}
        <div className="flex justify-end">
          <Button className="gap-2">
            <Plus className="h-4 w-4" />
            Add Device
          </Button>
        </div>

        {/* Device Cards */}
        <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-3">
          {devices.map((device) => {
            const BatteryIcon = getBatteryIcon(device.batteryLevel)

            return (
              <div
                key={device.id}
                className="group relative overflow-hidden rounded-xl border border-border bg-card transition-all duration-300 hover:border-primary/30 hover:shadow-lg hover:shadow-primary/5"
              >
                {/* Status indicator bar */}
                <div
                  className={cn(
                    'absolute left-0 top-0 h-1 w-full',
                    getStatusColor(device.status)
                  )}
                />

                {/* Header */}
                <div className="flex items-start justify-between p-5 pb-3">
                  <div className="flex items-center gap-3">
                    <div className="flex h-12 w-12 items-center justify-center rounded-xl bg-secondary">
                      <Smartphone className="h-6 w-6 text-foreground" />
                    </div>
                    <div>
                      <h3 className="font-semibold text-foreground">{device.name}</h3>
                      <p className="text-xs text-muted-foreground">{device.model}</p>
                    </div>
                  </div>

                  <DropdownMenu>
                    <DropdownMenuTrigger asChild>
                      <Button
                        variant="ghost"
                        size="icon"
                        className="opacity-0 transition-opacity group-hover:opacity-100"
                      >
                        <MoreVertical className="h-4 w-4" />
                      </Button>
                    </DropdownMenuTrigger>
                    <DropdownMenuContent align="end">
                      <DropdownMenuItem>View Details</DropdownMenuItem>
                      <DropdownMenuItem>Sync Data</DropdownMenuItem>
                      <DropdownMenuSeparator />
                      <DropdownMenuItem className="text-destructive">
                        <Trash2 className="mr-2 h-4 w-4" />
                        Remove Device
                      </DropdownMenuItem>
                    </DropdownMenuContent>
                  </DropdownMenu>
                </div>

                {/* Status & Battery */}
                <div className="flex items-center justify-between border-t border-border/50 px-5 py-3">
                  <div className="flex items-center gap-2">
                    {device.status === 'online' ? (
                      <Wifi className="h-4 w-4 text-[var(--signal-excellent)]" />
                    ) : (
                      <WifiOff className="h-4 w-4 text-muted-foreground" />
                    )}
                    <span
                      className={cn(
                        'rounded-full border px-2 py-0.5 text-xs font-medium capitalize',
                        getStatusBadge(device.status)
                      )}
                    >
                      {device.status}
                    </span>
                  </div>

                  {device.batteryLevel !== undefined && (
                    <div className="flex items-center gap-1.5">
                      <BatteryIcon
                        className={cn('h-4 w-4', getBatteryColor(device.batteryLevel))}
                      />
                      <span className="text-xs text-muted-foreground">
                        {device.batteryLevel}%
                      </span>
                    </div>
                  )}
                </div>

                {/* Stats */}
                <div className="grid grid-cols-2 gap-px border-t border-border/50 bg-border/30">
                  <div className="bg-card px-5 py-3">
                    <span className="text-[10px] font-medium uppercase tracking-wider text-muted-foreground">
                      OS
                    </span>
                    <p className="mt-0.5 text-sm font-medium text-foreground">
                      {device.os}
                    </p>
                  </div>
                  <div className="bg-card px-5 py-3">
                    <span className="text-[10px] font-medium uppercase tracking-wider text-muted-foreground">
                      Measurements
                    </span>
                    <p className="mt-0.5 font-mono text-sm font-medium text-foreground">
                      {device.totalMeasurements.toLocaleString()}
                    </p>
                  </div>
                </div>

                {/* Footer */}
                <div className="border-t border-border/50 px-5 py-3">
                  <span className="text-xs text-muted-foreground">
                    Last seen: {formatLastSeen(device.lastSeen)}
                  </span>
                </div>
              </div>
            )
          })}
        </div>
      </div>
    </div>
  )
}

function StatCard({
  label,
  value,
  icon: Icon,
  color = 'var(--primary)',
}: {
  label: string
  value: string
  icon: typeof Smartphone
  color?: string
}) {
  return (
    <div className="rounded-xl border border-border bg-card p-5">
      <div className="flex items-center justify-between">
        <span className="text-xs font-medium uppercase tracking-wider text-muted-foreground">
          {label}
        </span>
        <div
          className="flex h-10 w-10 items-center justify-center rounded-lg"
          style={{ backgroundColor: `${color}15` }}
        >
          <Icon className="h-5 w-5" style={{ color }} />
        </div>
      </div>
      <p className="mt-2 font-mono text-2xl font-bold text-foreground">{value}</p>
    </div>
  )
}
