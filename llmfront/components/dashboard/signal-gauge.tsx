'use client'

import { useEffect, useRef } from 'react'
import { getSignalQuality } from '@/lib/types'

interface SignalGaugeProps {
  value: number // dBm value
  min?: number
  max?: number
  size?: number
}

export function SignalGauge({ value, min = -120, max = -50, size = 200 }: SignalGaugeProps) {
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const quality = getSignalQuality(value)
  
  const qualityColors = {
    excellent: '#22c55e',
    good: '#4ade80',
    fair: '#fbbf24',
    poor: '#f97316',
    weak: '#ef4444',
  }
  
  const color = qualityColors[quality]
  
  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return
    
    const ctx = canvas.getContext('2d')
    if (!ctx) return
    
    const dpr = window.devicePixelRatio || 1
    canvas.width = size * dpr
    canvas.height = size * dpr
    ctx.scale(dpr, dpr)
    
    const centerX = size / 2
    const centerY = size / 2
    const radius = (size / 2) - 20
    
    // Clear canvas
    ctx.clearRect(0, 0, size, size)
    
    // Draw background arc
    ctx.beginPath()
    ctx.arc(centerX, centerY, radius, Math.PI * 0.75, Math.PI * 2.25, false)
    ctx.strokeStyle = 'rgba(255, 255, 255, 0.1)'
    ctx.lineWidth = 12
    ctx.lineCap = 'round'
    ctx.stroke()
    
    // Calculate percentage
    const percentage = Math.max(0, Math.min(1, (value - min) / (max - min)))
    const endAngle = Math.PI * 0.75 + (Math.PI * 1.5 * percentage)
    
    // Draw value arc with gradient
    const gradient = ctx.createLinearGradient(0, 0, size, size)
    gradient.addColorStop(0, color)
    gradient.addColorStop(1, `${color}88`)
    
    ctx.beginPath()
    ctx.arc(centerX, centerY, radius, Math.PI * 0.75, endAngle, false)
    ctx.strokeStyle = gradient
    ctx.lineWidth = 12
    ctx.lineCap = 'round'
    ctx.stroke()
    
    // Draw glow effect
    ctx.shadowColor = color
    ctx.shadowBlur = 20
    ctx.beginPath()
    ctx.arc(centerX, centerY, radius, Math.PI * 0.75, endAngle, false)
    ctx.strokeStyle = `${color}44`
    ctx.lineWidth = 20
    ctx.lineCap = 'round'
    ctx.stroke()
    ctx.shadowBlur = 0
    
  }, [value, min, max, size, color])
  
  return (
    <div className="relative flex flex-col items-center">
      <canvas
        ref={canvasRef}
        style={{ width: size, height: size }}
        className="drop-shadow-lg"
      />
      <div className="absolute inset-0 flex flex-col items-center justify-center">
        <span className="font-mono text-4xl font-bold text-foreground">{value}</span>
        <span className="text-sm text-muted-foreground">dBm</span>
        <span 
          className="mt-2 rounded-full px-3 py-1 text-xs font-semibold uppercase"
          style={{ 
            backgroundColor: `${color}20`,
            color: color,
          }}
        >
          {quality}
        </span>
      </div>
    </div>
  )
}
