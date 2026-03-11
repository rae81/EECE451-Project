import { CellData, DeviceInfo, NetworkStats, HeatmapPoint, HistoryEntry } from './types'

// Generate realistic mock data for the dashboard
export function generateCurrentCell(): CellData {
  const networkTypes: ('5G' | '4G LTE' | '3G' | '2G')[] = ['5G', '4G LTE', '3G', '2G']
  const operators = ['Verizon', 'AT&T', 'T-Mobile', 'Sprint']
  
  return {
    id: `cell-${Date.now()}`,
    timestamp: Date.now(),
    networkType: networkTypes[Math.floor(Math.random() * 2)], // Mostly 5G/LTE
    signalStrength: -65 - Math.floor(Math.random() * 45),
    rsrp: -80 - Math.floor(Math.random() * 40),
    rsrq: -8 - Math.floor(Math.random() * 12),
    sinr: 5 + Math.floor(Math.random() * 25),
    cellId: Math.floor(Math.random() * 999999).toString(16).toUpperCase(),
    lac: Math.floor(Math.random() * 65535).toString(),
    mcc: '310',
    mnc: '410',
    operator: operators[Math.floor(Math.random() * operators.length)],
    bandwidth: [5, 10, 15, 20][Math.floor(Math.random() * 4)],
    frequency: [700, 850, 1900, 2100, 2600][Math.floor(Math.random() * 5)],
    latitude: 40.7128 + (Math.random() - 0.5) * 0.1,
    longitude: -74.006 + (Math.random() - 0.5) * 0.1,
    downloadSpeed: 50 + Math.random() * 450,
    uploadSpeed: 10 + Math.random() * 90,
    latency: 10 + Math.random() * 40,
  }
}

export function generateNeighboringCells(count: number = 6): CellData[] {
  return Array.from({ length: count }, () => ({
    ...generateCurrentCell(),
    id: `neighbor-${Math.random().toString(36).substr(2, 9)}`,
    signalStrength: -75 - Math.floor(Math.random() * 35),
  }))
}

export function generateDevices(): DeviceInfo[] {
  return [
    {
      id: 'device-1',
      name: 'Primary Phone',
      model: 'Samsung Galaxy S24 Ultra',
      os: 'Android 14',
      lastSeen: Date.now() - 1000,
      status: 'online',
      batteryLevel: 85,
      totalMeasurements: 12453,
    },
    {
      id: 'device-2',
      name: 'Test Device',
      model: 'Google Pixel 8 Pro',
      os: 'Android 14',
      lastSeen: Date.now() - 60000 * 5,
      status: 'idle',
      batteryLevel: 42,
      totalMeasurements: 3421,
    },
    {
      id: 'device-3',
      name: 'Field Unit',
      model: 'OnePlus 12',
      os: 'Android 14',
      lastSeen: Date.now() - 60000 * 60,
      status: 'offline',
      batteryLevel: 15,
      totalMeasurements: 8732,
    },
  ]
}

export function generateNetworkStats(): NetworkStats {
  const now = Date.now()
  const hourMs = 60 * 60 * 1000
  
  return {
    avgSignalStrength: -78,
    avgLatency: 24,
    avgDownloadSpeed: 187.5,
    avgUploadSpeed: 45.2,
    totalMeasurements: 24606,
    networkTypeDistribution: {
      '5G': 45,
      '4G LTE': 42,
      '3G': 10,
      '2G': 3,
    },
    signalTrend: Array.from({ length: 24 }, (_, i) => ({
      time: now - (23 - i) * hourMs,
      value: -75 - Math.sin(i / 4) * 15 + Math.random() * 10,
    })),
    speedTrend: Array.from({ length: 24 }, (_, i) => ({
      time: now - (23 - i) * hourMs,
      download: 150 + Math.sin(i / 3) * 100 + Math.random() * 50,
      upload: 35 + Math.sin(i / 3) * 20 + Math.random() * 15,
    })),
  }
}

export function generateHeatmapData(): HeatmapPoint[] {
  const centerLat = 40.7128
  const centerLng = -74.006
  const points: HeatmapPoint[] = []
  
  for (let i = 0; i < 200; i++) {
    points.push({
      lat: centerLat + (Math.random() - 0.5) * 0.05,
      lng: centerLng + (Math.random() - 0.5) * 0.08,
      intensity: Math.random(),
      networkType: ['5G', '4G LTE', '3G'][Math.floor(Math.random() * 3)],
      signalStrength: -65 - Math.floor(Math.random() * 50),
    })
  }
  
  return points
}

export function generateHistory(count: number = 50): HistoryEntry[] {
  const locations = ['Manhattan, NY', 'Brooklyn, NY', 'Queens, NY', 'Bronx, NY', 'Staten Island, NY']
  const networkTypes = ['5G', '4G LTE', '3G', '2G']
  
  return Array.from({ length: count }, (_, i) => ({
    id: `history-${i}`,
    timestamp: Date.now() - i * 60000 * 15, // Every 15 minutes
    networkType: networkTypes[Math.floor(Math.random() * 2)],
    signalStrength: -65 - Math.floor(Math.random() * 45),
    downloadSpeed: 50 + Math.random() * 450,
    uploadSpeed: 10 + Math.random() * 90,
    latency: 10 + Math.random() * 40,
    location: locations[Math.floor(Math.random() * locations.length)],
  }))
}
