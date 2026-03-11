'use client'

import { cn } from '@/lib/utils'
import {
  Activity,
  BarChart3,
  Download,
  History,
  LayoutDashboard,
  Map,
  Radio,
  Settings,
  Smartphone,
  Signal,
  Wifi,
} from 'lucide-react'

interface AppSidebarProps {
  currentView: string
  onViewChange: (view: string) => void
}

const navigation = [
  { id: 'dashboard', label: 'Dashboard', icon: LayoutDashboard },
  { id: 'statistics', label: 'Statistics', icon: BarChart3 },
  { id: 'heatmap', label: 'Heatmap', icon: Map },
  { id: 'history', label: 'History', icon: History },
  { id: 'devices', label: 'Devices', icon: Smartphone },
  { id: 'settings', label: 'Settings', icon: Settings },
]

export function AppSidebar({ currentView, onViewChange }: AppSidebarProps) {
  return (
    <aside className="fixed left-0 top-0 z-40 flex h-screen w-64 flex-col border-r border-border bg-sidebar">
      {/* Logo */}
      <div className="flex h-16 items-center gap-3 border-b border-border px-6">
        <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-primary/10">
          <Radio className="h-5 w-5 text-primary" />
        </div>
        <div className="flex flex-col">
          <span className="text-sm font-semibold text-foreground">Network Cell</span>
          <span className="text-xs text-muted-foreground">Analyzer</span>
        </div>
      </div>

      {/* Status Indicator */}
      <div className="mx-4 mt-4 rounded-lg bg-secondary/50 p-3">
        <div className="flex items-center gap-2">
          <div className="relative">
            <Signal className="h-4 w-4 text-primary" />
            <span className="absolute -right-0.5 -top-0.5 h-2 w-2 rounded-full bg-primary animate-pulse" />
          </div>
          <div className="flex flex-col">
            <span className="text-xs font-medium text-foreground">Live Monitoring</span>
            <span className="text-[10px] text-muted-foreground">Connected to 1 device</span>
          </div>
        </div>
      </div>

      {/* Navigation */}
      <nav className="flex-1 space-y-1 px-3 py-4">
        <div className="mb-2 px-3 text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
          Overview
        </div>
        {navigation.slice(0, 4).map((item) => (
          <button
            key={item.id}
            onClick={() => onViewChange(item.id)}
            className={cn(
              'flex w-full items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium transition-all duration-200',
              currentView === item.id
                ? 'bg-primary/10 text-primary'
                : 'text-muted-foreground hover:bg-secondary hover:text-foreground'
            )}
          >
            <item.icon className="h-4 w-4" />
            {item.label}
            {currentView === item.id && (
              <div className="ml-auto h-1.5 w-1.5 rounded-full bg-primary" />
            )}
          </button>
        ))}

        <div className="mb-2 mt-6 px-3 text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
          Management
        </div>
        {navigation.slice(4).map((item) => (
          <button
            key={item.id}
            onClick={() => onViewChange(item.id)}
            className={cn(
              'flex w-full items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium transition-all duration-200',
              currentView === item.id
                ? 'bg-primary/10 text-primary'
                : 'text-muted-foreground hover:bg-secondary hover:text-foreground'
            )}
          >
            <item.icon className="h-4 w-4" />
            {item.label}
            {currentView === item.id && (
              <div className="ml-auto h-1.5 w-1.5 rounded-full bg-primary" />
            )}
          </button>
        ))}
      </nav>

      {/* Quick Actions */}
      <div className="border-t border-border p-4">
        <button className="flex w-full items-center justify-center gap-2 rounded-lg bg-primary/10 px-4 py-2.5 text-sm font-medium text-primary transition-colors hover:bg-primary/20">
          <Activity className="h-4 w-4" />
          Run Speed Test
        </button>
      </div>

      {/* Connection Status */}
      <div className="border-t border-border p-4">
        <div className="flex items-center justify-between text-xs">
          <div className="flex items-center gap-2">
            <Wifi className="h-3.5 w-3.5 text-primary" />
            <span className="text-muted-foreground">Server</span>
          </div>
          <span className="font-mono text-primary">Connected</span>
        </div>
      </div>
    </aside>
  )
}
