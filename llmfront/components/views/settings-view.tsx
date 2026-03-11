'use client'

import { useState } from 'react'
import { AppHeader } from '@/components/app-header'
import {
  Bell,
  Database,
  Globe,
  Key,
  Moon,
  Server,
  Settings,
  Shield,
  Smartphone,
  Sun,
  User,
  Wifi,
} from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { cn } from '@/lib/utils'

type SettingsTab = 'general' | 'server' | 'notifications' | 'account'

export function SettingsView() {
  const [activeTab, setActiveTab] = useState<SettingsTab>('general')
  const [settings, setSettings] = useState({
    serverUrl: 'https://api.networkanalyzer.app',
    apiKey: 'nca_xxxxxxxxxxxxxxxxxxxx',
    refreshInterval: 5,
    autoSync: true,
    notifications: {
      signalDrop: true,
      speedAlerts: true,
      deviceOffline: true,
      weeklyReport: false,
    },
    dataRetention: 30,
    darkMode: true,
  })

  const tabs = [
    { id: 'general', label: 'General', icon: Settings },
    { id: 'server', label: 'Server', icon: Server },
    { id: 'notifications', label: 'Notifications', icon: Bell },
    { id: 'account', label: 'Account', icon: User },
  ] as const

  return (
    <div className="flex flex-col">
      <AppHeader
        title="Settings"
        subtitle="Application configuration"
      />

      <div className="flex flex-1">
        {/* Sidebar Tabs */}
        <div className="w-56 border-r border-border bg-card/50 p-4">
          <nav className="space-y-1">
            {tabs.map((tab) => (
              <button
                key={tab.id}
                onClick={() => setActiveTab(tab.id)}
                className={cn(
                  'flex w-full items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium transition-colors',
                  activeTab === tab.id
                    ? 'bg-primary/10 text-primary'
                    : 'text-muted-foreground hover:bg-secondary hover:text-foreground'
                )}
              >
                <tab.icon className="h-4 w-4" />
                {tab.label}
              </button>
            ))}
          </nav>
        </div>

        {/* Content */}
        <div className="flex-1 p-6">
          {activeTab === 'general' && (
            <div className="max-w-2xl space-y-8">
              <Section title="Appearance">
                <SettingRow
                  title="Dark Mode"
                  description="Enable dark theme for the application"
                  icon={settings.darkMode ? Moon : Sun}
                >
                  <Toggle
                    checked={settings.darkMode}
                    onChange={(checked) =>
                      setSettings((s) => ({ ...s, darkMode: checked }))
                    }
                  />
                </SettingRow>
              </Section>

              <Section title="Data Collection">
                <SettingRow
                  title="Refresh Interval"
                  description="How often to update measurements (in seconds)"
                  icon={Wifi}
                >
                  <Input
                    type="number"
                    min={1}
                    max={60}
                    value={settings.refreshInterval}
                    onChange={(e) =>
                      setSettings((s) => ({
                        ...s,
                        refreshInterval: parseInt(e.target.value),
                      }))
                    }
                    className="w-24"
                  />
                </SettingRow>

                <SettingRow
                  title="Auto Sync"
                  description="Automatically sync data when devices connect"
                  icon={Database}
                >
                  <Toggle
                    checked={settings.autoSync}
                    onChange={(checked) =>
                      setSettings((s) => ({ ...s, autoSync: checked }))
                    }
                  />
                </SettingRow>

                <SettingRow
                  title="Data Retention"
                  description="Number of days to keep measurement history"
                  icon={Shield}
                >
                  <select
                    value={settings.dataRetention}
                    onChange={(e) =>
                      setSettings((s) => ({
                        ...s,
                        dataRetention: parseInt(e.target.value),
                      }))
                    }
                    className="rounded-lg border border-border bg-secondary px-3 py-2 text-sm text-foreground"
                  >
                    <option value={7}>7 days</option>
                    <option value={30}>30 days</option>
                    <option value={90}>90 days</option>
                    <option value={365}>1 year</option>
                  </select>
                </SettingRow>
              </Section>
            </div>
          )}

          {activeTab === 'server' && (
            <div className="max-w-2xl space-y-8">
              <Section title="Server Configuration">
                <SettingRow
                  title="Server URL"
                  description="Backend API endpoint for data synchronization"
                  icon={Globe}
                  vertical
                >
                  <Input
                    value={settings.serverUrl}
                    onChange={(e) =>
                      setSettings((s) => ({ ...s, serverUrl: e.target.value }))
                    }
                    className="font-mono"
                  />
                </SettingRow>

                <SettingRow
                  title="API Key"
                  description="Authentication key for server communication"
                  icon={Key}
                  vertical
                >
                  <div className="flex gap-2">
                    <Input
                      type="password"
                      value={settings.apiKey}
                      onChange={(e) =>
                        setSettings((s) => ({ ...s, apiKey: e.target.value }))
                      }
                      className="flex-1 font-mono"
                    />
                    <Button variant="outline">Regenerate</Button>
                  </div>
                </SettingRow>
              </Section>

              <Section title="Connection Status">
                <div className="rounded-lg border border-border bg-secondary/30 p-4">
                  <div className="flex items-center gap-3">
                    <div className="flex h-10 w-10 items-center justify-center rounded-full bg-[var(--signal-excellent)]/20">
                      <Server className="h-5 w-5 text-[var(--signal-excellent)]" />
                    </div>
                    <div>
                      <p className="font-medium text-foreground">Connected</p>
                      <p className="text-xs text-muted-foreground">
                        Last sync: 2 minutes ago
                      </p>
                    </div>
                    <Button variant="outline" size="sm" className="ml-auto">
                      Test Connection
                    </Button>
                  </div>
                </div>
              </Section>
            </div>
          )}

          {activeTab === 'notifications' && (
            <div className="max-w-2xl space-y-8">
              <Section title="Alert Preferences">
                <SettingRow
                  title="Signal Drop Alerts"
                  description="Notify when signal strength drops below threshold"
                  icon={Wifi}
                >
                  <Toggle
                    checked={settings.notifications.signalDrop}
                    onChange={(checked) =>
                      setSettings((s) => ({
                        ...s,
                        notifications: { ...s.notifications, signalDrop: checked },
                      }))
                    }
                  />
                </SettingRow>

                <SettingRow
                  title="Speed Alerts"
                  description="Notify when download/upload speed is unusually low"
                  icon={Bell}
                >
                  <Toggle
                    checked={settings.notifications.speedAlerts}
                    onChange={(checked) =>
                      setSettings((s) => ({
                        ...s,
                        notifications: { ...s.notifications, speedAlerts: checked },
                      }))
                    }
                  />
                </SettingRow>

                <SettingRow
                  title="Device Offline"
                  description="Notify when a device goes offline"
                  icon={Smartphone}
                >
                  <Toggle
                    checked={settings.notifications.deviceOffline}
                    onChange={(checked) =>
                      setSettings((s) => ({
                        ...s,
                        notifications: { ...s.notifications, deviceOffline: checked },
                      }))
                    }
                  />
                </SettingRow>
              </Section>

              <Section title="Reports">
                <SettingRow
                  title="Weekly Summary"
                  description="Receive a weekly email report of network statistics"
                  icon={Bell}
                >
                  <Toggle
                    checked={settings.notifications.weeklyReport}
                    onChange={(checked) =>
                      setSettings((s) => ({
                        ...s,
                        notifications: { ...s.notifications, weeklyReport: checked },
                      }))
                    }
                  />
                </SettingRow>
              </Section>
            </div>
          )}

          {activeTab === 'account' && (
            <div className="max-w-2xl space-y-8">
              <Section title="Profile">
                <div className="flex items-center gap-4 rounded-lg border border-border bg-secondary/30 p-4">
                  <div className="flex h-16 w-16 items-center justify-center rounded-full bg-primary/20 text-xl font-semibold text-primary">
                    RB
                  </div>
                  <div className="flex-1">
                    <p className="font-semibold text-foreground">Research Bot</p>
                    <p className="text-sm text-muted-foreground">
                      research@networkanalyzer.app
                    </p>
                  </div>
                  <Button variant="outline">Edit Profile</Button>
                </div>
              </Section>

              <Section title="Security">
                <SettingRow
                  title="Change Password"
                  description="Update your account password"
                  icon={Key}
                >
                  <Button variant="outline">Change</Button>
                </SettingRow>

                <SettingRow
                  title="Two-Factor Authentication"
                  description="Add an extra layer of security"
                  icon={Shield}
                >
                  <Button variant="outline">Enable</Button>
                </SettingRow>
              </Section>

              <Section title="Danger Zone">
                <div className="rounded-lg border border-destructive/30 bg-destructive/5 p-4">
                  <div className="flex items-center justify-between">
                    <div>
                      <p className="font-medium text-foreground">Delete Account</p>
                      <p className="text-sm text-muted-foreground">
                        Permanently delete your account and all data
                      </p>
                    </div>
                    <Button variant="destructive">Delete Account</Button>
                  </div>
                </div>
              </Section>
            </div>
          )}

          {/* Save Button */}
          <div className="mt-8 flex justify-end border-t border-border pt-6">
            <Button className="px-8">Save Changes</Button>
          </div>
        </div>
      </div>
    </div>
  )
}

function Section({
  title,
  children,
}: {
  title: string
  children: React.ReactNode
}) {
  return (
    <div>
      <h3 className="mb-4 text-sm font-semibold uppercase tracking-wider text-muted-foreground">
        {title}
      </h3>
      <div className="space-y-4">{children}</div>
    </div>
  )
}

function SettingRow({
  title,
  description,
  icon: Icon,
  children,
  vertical = false,
}: {
  title: string
  description: string
  icon: typeof Settings
  children: React.ReactNode
  vertical?: boolean
}) {
  return (
    <div
      className={cn(
        'rounded-lg border border-border p-4',
        vertical ? 'space-y-3' : 'flex items-center justify-between'
      )}
    >
      <div className="flex items-center gap-3">
        <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-secondary">
          <Icon className="h-5 w-5 text-muted-foreground" />
        </div>
        <div>
          <p className="font-medium text-foreground">{title}</p>
          <p className="text-xs text-muted-foreground">{description}</p>
        </div>
      </div>
      <div className={vertical ? 'pl-13' : ''}>{children}</div>
    </div>
  )
}

function Toggle({
  checked,
  onChange,
}: {
  checked: boolean
  onChange: (checked: boolean) => void
}) {
  return (
    <button
      role="switch"
      aria-checked={checked}
      onClick={() => onChange(!checked)}
      className={cn(
        'relative h-6 w-11 rounded-full transition-colors',
        checked ? 'bg-primary' : 'bg-secondary'
      )}
    >
      <span
        className={cn(
          'absolute left-0.5 top-0.5 h-5 w-5 rounded-full bg-white transition-transform',
          checked && 'translate-x-5'
        )}
      />
    </button>
  )
}
