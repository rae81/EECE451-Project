'use client'

import { useEffect, useState } from 'react'
import { AppHeader } from '@/components/app-header'
import { generateHistory } from '@/lib/mock-data'
import { HistoryEntry, getSignalQuality } from '@/lib/types'
import { cn } from '@/lib/utils'
import {
  Calendar,
  ChevronLeft,
  ChevronRight,
  Download,
  FileSpreadsheet,
  FileText,
  Filter,
  Search,
} from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'

export function HistoryView() {
  const [history, setHistory] = useState<HistoryEntry[]>([])
  const [isRefreshing, setIsRefreshing] = useState(false)
  const [searchQuery, setSearchQuery] = useState('')
  const [currentPage, setCurrentPage] = useState(1)
  const [networkFilters, setNetworkFilters] = useState({
    '5G': true,
    '4G LTE': true,
    '3G': true,
    '2G': true,
  })
  const itemsPerPage = 10

  useEffect(() => {
    setHistory(generateHistory(100))
  }, [])

  const handleRefresh = async () => {
    setIsRefreshing(true)
    await new Promise((resolve) => setTimeout(resolve, 500))
    setHistory(generateHistory(100))
    setIsRefreshing(false)
  }

  const filteredHistory = history.filter((entry) => {
    const matchesSearch =
      entry.location.toLowerCase().includes(searchQuery.toLowerCase()) ||
      entry.networkType.toLowerCase().includes(searchQuery.toLowerCase())
    const matchesFilter = networkFilters[entry.networkType as keyof typeof networkFilters]
    return matchesSearch && matchesFilter
  })

  const totalPages = Math.ceil(filteredHistory.length / itemsPerPage)
  const paginatedHistory = filteredHistory.slice(
    (currentPage - 1) * itemsPerPage,
    currentPage * itemsPerPage
  )

  const formatDate = (timestamp: number) => {
    return new Date(timestamp).toLocaleDateString('en-US', {
      month: 'short',
      day: 'numeric',
      year: 'numeric',
    })
  }

  const formatTime = (timestamp: number) => {
    return new Date(timestamp).toLocaleTimeString('en-US', {
      hour: '2-digit',
      minute: '2-digit',
    })
  }

  const handleExport = (format: 'csv' | 'pdf') => {
    // Simulate export
    const filename = `network-history-${Date.now()}.${format}`
    alert(`Exporting ${filename}...`)
  }

  return (
    <div className="flex flex-col">
      <AppHeader
        title="History"
        subtitle="Measurement records and exports"
        onRefresh={handleRefresh}
        isRefreshing={isRefreshing}
      />

      <div className="flex-1 space-y-6 p-6">
        {/* Toolbar */}
        <div className="flex flex-wrap items-center justify-between gap-4">
          <div className="flex items-center gap-3">
            {/* Search */}
            <div className="relative">
              <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
              <Input
                placeholder="Search by location or network..."
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                className="w-64 pl-9"
              />
            </div>

            {/* Filters */}
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button variant="outline" className="gap-2">
                  <Filter className="h-4 w-4" />
                  Filter
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent>
                <DropdownMenuLabel>Network Types</DropdownMenuLabel>
                <DropdownMenuSeparator />
                {Object.entries(networkFilters).map(([type, enabled]) => (
                  <DropdownMenuCheckboxItem
                    key={type}
                    checked={enabled}
                    onCheckedChange={(checked) =>
                      setNetworkFilters((f) => ({ ...f, [type]: checked }))
                    }
                  >
                    {type}
                  </DropdownMenuCheckboxItem>
                ))}
              </DropdownMenuContent>
            </DropdownMenu>

            {/* Date Range */}
            <Button variant="outline" className="gap-2">
              <Calendar className="h-4 w-4" />
              Last 7 days
            </Button>
          </div>

          {/* Export Buttons */}
          <div className="flex items-center gap-2">
            <Button
              variant="outline"
              className="gap-2"
              onClick={() => handleExport('csv')}
            >
              <FileSpreadsheet className="h-4 w-4" />
              Export CSV
            </Button>
            <Button
              variant="outline"
              className="gap-2"
              onClick={() => handleExport('pdf')}
            >
              <FileText className="h-4 w-4" />
              Export PDF
            </Button>
          </div>
        </div>

        {/* Stats Summary */}
        <div className="grid gap-4 md:grid-cols-4">
          <SummaryCard
            label="Total Records"
            value={filteredHistory.length.toString()}
          />
          <SummaryCard
            label="Avg Download"
            value={`${(
              filteredHistory.reduce((sum, e) => sum + e.downloadSpeed, 0) /
              filteredHistory.length
            ).toFixed(1)} Mbps`}
          />
          <SummaryCard
            label="Avg Upload"
            value={`${(
              filteredHistory.reduce((sum, e) => sum + e.uploadSpeed, 0) /
              filteredHistory.length
            ).toFixed(1)} Mbps`}
          />
          <SummaryCard
            label="Avg Signal"
            value={`${Math.round(
              filteredHistory.reduce((sum, e) => sum + e.signalStrength, 0) /
                filteredHistory.length
            )} dBm`}
          />
        </div>

        {/* Table */}
        <div className="rounded-xl border border-border bg-card">
          <div className="overflow-x-auto">
            <table className="w-full">
              <thead>
                <tr className="border-b border-border">
                  <th className="px-4 py-3 text-left text-xs font-semibold uppercase tracking-wider text-muted-foreground">
                    Date / Time
                  </th>
                  <th className="px-4 py-3 text-left text-xs font-semibold uppercase tracking-wider text-muted-foreground">
                    Location
                  </th>
                  <th className="px-4 py-3 text-left text-xs font-semibold uppercase tracking-wider text-muted-foreground">
                    Network
                  </th>
                  <th className="px-4 py-3 text-left text-xs font-semibold uppercase tracking-wider text-muted-foreground">
                    Signal
                  </th>
                  <th className="px-4 py-3 text-left text-xs font-semibold uppercase tracking-wider text-muted-foreground">
                    Download
                  </th>
                  <th className="px-4 py-3 text-left text-xs font-semibold uppercase tracking-wider text-muted-foreground">
                    Upload
                  </th>
                  <th className="px-4 py-3 text-left text-xs font-semibold uppercase tracking-wider text-muted-foreground">
                    Latency
                  </th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {paginatedHistory.map((entry) => {
                  const quality = getSignalQuality(entry.signalStrength)
                  const qualityColors = {
                    excellent: 'text-[var(--signal-excellent)]',
                    good: 'text-[var(--signal-good)]',
                    fair: 'text-[var(--signal-fair)]',
                    poor: 'text-[var(--signal-poor)]',
                    weak: 'text-[var(--signal-weak)]',
                  }

                  const networkColors: Record<string, string> = {
                    '5G': 'bg-primary/20 text-primary border-primary/30',
                    '4G LTE': 'bg-blue-500/20 text-blue-400 border-blue-500/30',
                    '3G': 'bg-amber-500/20 text-amber-400 border-amber-500/30',
                    '2G': 'bg-red-500/20 text-red-400 border-red-500/30',
                  }

                  return (
                    <tr
                      key={entry.id}
                      className="transition-colors hover:bg-secondary/30"
                    >
                      <td className="px-4 py-3">
                        <div className="flex flex-col">
                          <span className="text-sm text-foreground">
                            {formatDate(entry.timestamp)}
                          </span>
                          <span className="text-xs text-muted-foreground">
                            {formatTime(entry.timestamp)}
                          </span>
                        </div>
                      </td>
                      <td className="px-4 py-3 text-sm text-foreground">
                        {entry.location}
                      </td>
                      <td className="px-4 py-3">
                        <span
                          className={cn(
                            'rounded-full border px-2 py-0.5 text-xs font-medium',
                            networkColors[entry.networkType]
                          )}
                        >
                          {entry.networkType}
                        </span>
                      </td>
                      <td className="px-4 py-3">
                        <span
                          className={cn(
                            'font-mono text-sm font-medium',
                            qualityColors[quality]
                          )}
                        >
                          {entry.signalStrength} dBm
                        </span>
                      </td>
                      <td className="px-4 py-3 font-mono text-sm text-foreground">
                        {entry.downloadSpeed.toFixed(1)} Mbps
                      </td>
                      <td className="px-4 py-3 font-mono text-sm text-foreground">
                        {entry.uploadSpeed.toFixed(1)} Mbps
                      </td>
                      <td className="px-4 py-3 font-mono text-sm text-foreground">
                        {entry.latency.toFixed(0)} ms
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>

          {/* Pagination */}
          <div className="flex items-center justify-between border-t border-border px-4 py-3">
            <span className="text-sm text-muted-foreground">
              Showing {(currentPage - 1) * itemsPerPage + 1} to{' '}
              {Math.min(currentPage * itemsPerPage, filteredHistory.length)} of{' '}
              {filteredHistory.length} records
            </span>
            <div className="flex items-center gap-2">
              <Button
                variant="outline"
                size="icon"
                onClick={() => setCurrentPage((p) => Math.max(1, p - 1))}
                disabled={currentPage === 1}
              >
                <ChevronLeft className="h-4 w-4" />
              </Button>
              {Array.from({ length: Math.min(5, totalPages) }, (_, i) => {
                const page = i + 1
                return (
                  <Button
                    key={page}
                    variant={currentPage === page ? 'default' : 'outline'}
                    size="icon"
                    onClick={() => setCurrentPage(page)}
                  >
                    {page}
                  </Button>
                )
              })}
              <Button
                variant="outline"
                size="icon"
                onClick={() => setCurrentPage((p) => Math.min(totalPages, p + 1))}
                disabled={currentPage === totalPages}
              >
                <ChevronRight className="h-4 w-4" />
              </Button>
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}

function SummaryCard({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-lg border border-border bg-card p-4">
      <span className="text-xs font-medium uppercase tracking-wider text-muted-foreground">
        {label}
      </span>
      <p className="mt-1 font-mono text-xl font-bold text-foreground">{value}</p>
    </div>
  )
}
