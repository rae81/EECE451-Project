export interface CellData {
  id: string
  timestamp: number
  networkType: '5G' | '4G LTE' | '3G' | '2G'
  signalStrength: number // dBm
  rsrp: number // Reference Signal Received Power
  rsrq: number // Reference Signal Received Quality
  sinr: number // Signal to Interference plus Noise Ratio
  cellId: string
  lac: string // Location Area Code
  mcc: string // Mobile Country Code
  mnc: string // Mobile Network Code
  operator: string
  bandwidth: number
  frequency: number
  latitude: number
  longitude: number
  downloadSpeed?: number
  uploadSpeed?: number
  latency?: number
}

export interface DeviceInfo {
  id: string
  name: string
  model: string
  os: string
  lastSeen: number
  status: 'online' | 'offline' | 'idle'
  batteryLevel?: number
  totalMeasurements: number
}

export interface NetworkStats {
  avgSignalStrength: number
  avgLatency: number
  avgDownloadSpeed: number
  avgUploadSpeed: number
  totalMeasurements: number
  networkTypeDistribution: Record<string, number>
  signalTrend: { time: number; value: number }[]
  speedTrend: { time: number; download: number; upload: number }[]
}

export interface HeatmapPoint {
  lat: number
  lng: number
  intensity: number
  networkType: string
  signalStrength: number
}

export interface HistoryEntry {
  id: string
  timestamp: number
  networkType: string
  signalStrength: number
  downloadSpeed: number
  uploadSpeed: number
  latency: number
  location: string
}

export type SignalQuality = 'excellent' | 'good' | 'fair' | 'poor' | 'weak'

export function getSignalQuality(dbm: number): SignalQuality {
  if (dbm >= -70) return 'excellent'
  if (dbm >= -85) return 'good'
  if (dbm >= -100) return 'fair'
  if (dbm >= -110) return 'poor'
  return 'weak'
}

export function getSignalColor(quality: SignalQuality): string {
  switch (quality) {
    case 'excellent': return 'var(--signal-excellent)'
    case 'good': return 'var(--signal-good)'
    case 'fair': return 'var(--signal-fair)'
    case 'poor': return 'var(--signal-poor)'
    case 'weak': return 'var(--signal-weak)'
  }
}
