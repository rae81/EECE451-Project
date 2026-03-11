'use client'

import { useState, useEffect } from 'react'
import { AppSidebar } from '@/components/app-sidebar'
import { DashboardView } from '@/components/views/dashboard-view'
import { StatisticsView } from '@/components/views/statistics-view'
import { HeatmapView } from '@/components/views/heatmap-view'
import { HistoryView } from '@/components/views/history-view'
import { DevicesView } from '@/components/views/devices-view'
import { SettingsView } from '@/components/views/settings-view'

export default function NetworkCellAnalyzer() {
  const [currentView, setCurrentView] = useState('dashboard')
  
  useEffect(() => {
    console.log("[v0] NetworkCellAnalyzer mounted, currentView:", currentView)
  }, [currentView])

  const renderView = () => {
    switch (currentView) {
      case 'dashboard':
        return <DashboardView />
      case 'statistics':
        return <StatisticsView />
      case 'heatmap':
        return <HeatmapView />
      case 'history':
        return <HistoryView />
      case 'devices':
        return <DevicesView />
      case 'settings':
        return <SettingsView />
      default:
        return <DashboardView />
    }
  }

  return (
    <div className="flex min-h-screen bg-background">
      <AppSidebar currentView={currentView} onViewChange={setCurrentView} />
      <main className="ml-64 flex-1">
        {renderView()}
      </main>
    </div>
  )
}
