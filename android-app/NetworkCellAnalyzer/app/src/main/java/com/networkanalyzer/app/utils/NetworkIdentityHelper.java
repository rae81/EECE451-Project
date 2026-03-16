package com.networkanalyzer.app.utils;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;

public final class NetworkIdentityHelper {

    private static final String[] PREFERRED_INTERFACES = {
            "wlan0",
            "eth0",
            "rmnet_data0",
            "rmnet_data1"
    };

    private NetworkIdentityHelper() {
    }

    @Nullable
    public static String getBestEffortIpAddress(@NonNull Context context) {
        String address = findIpv4Address(PREFERRED_INTERFACES);
        if (address != null) {
            return address;
        }
        address = findIpv4Address(null);
        if (address != null) {
            return address;
        }
        return findWifiIpv4Address(context);
    }

    @Nullable
    public static String getBestEffortMacAddress(@NonNull Context context) {
        String mac = findMacAddress(PREFERRED_INTERFACES);
        if (mac != null) {
            return mac;
        }
        mac = findMacAddress(null);
        if (mac != null) {
            return mac;
        }
        return findWifiMacAddress(context);
    }

    @Nullable
    private static String findIpv4Address(@Nullable String[] preferredNames) {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) {
                return null;
            }
            for (NetworkInterface networkInterface : Collections.list(interfaces)) {
                if (!shouldInspectInterface(networkInterface, preferredNames)) {
                    continue;
                }
                for (InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (SocketException ignored) {
        }
        return null;
    }

    @Nullable
    private static String findMacAddress(@Nullable String[] preferredNames) {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) {
                return null;
            }
            for (NetworkInterface networkInterface : Collections.list(interfaces)) {
                if (!shouldInspectInterface(networkInterface, preferredNames)) {
                    continue;
                }
                byte[] hardwareAddress = networkInterface.getHardwareAddress();
                String formatted = formatMac(hardwareAddress);
                if (isUsableMac(formatted)) {
                    return formatted;
                }
            }
        } catch (SocketException ignored) {
        }
        return null;
    }

    private static boolean shouldInspectInterface(@NonNull NetworkInterface networkInterface,
                                                  @Nullable String[] preferredNames) throws SocketException {
        if (!networkInterface.isUp() || networkInterface.isLoopback() || networkInterface.isVirtual()) {
            return false;
        }
        if (preferredNames == null) {
            return true;
        }
        String name = networkInterface.getName();
        for (String preferredName : preferredNames) {
            if (preferredName.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static String findWifiIpv4Address(@NonNull Context context) {
        WifiManager wifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifiManager == null) {
            return null;
        }
        @SuppressWarnings("deprecation")
        WifiInfo wifiInfo = wifiManager.getConnectionInfo();
        if (wifiInfo == null) {
            return null;
        }
        int ipAddress = wifiInfo.getIpAddress();
        if (ipAddress == 0) {
            return null;
        }
        return String.format(
                Locale.US,
                "%d.%d.%d.%d",
                ipAddress & 0xff,
                (ipAddress >> 8) & 0xff,
                (ipAddress >> 16) & 0xff,
                (ipAddress >> 24) & 0xff
        );
    }

    @Nullable
    private static String findWifiMacAddress(@NonNull Context context) {
        WifiManager wifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifiManager == null) {
            return null;
        }
        @SuppressWarnings("deprecation")
        WifiInfo wifiInfo = wifiManager.getConnectionInfo();
        if (wifiInfo == null) {
            return null;
        }
        String macAddress = wifiInfo.getMacAddress();
        return isUsableMac(macAddress) ? macAddress : null;
    }

    @Nullable
    private static String formatMac(@Nullable byte[] hardwareAddress) {
        if (hardwareAddress == null || hardwareAddress.length == 0) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        for (byte octet : hardwareAddress) {
            if (builder.length() > 0) {
                builder.append(':');
            }
            builder.append(String.format(Locale.US, "%02X", octet));
        }
        return builder.toString();
    }

    private static boolean isUsableMac(@Nullable String macAddress) {
        return macAddress != null
                && !macAddress.trim().isEmpty()
                && !"02:00:00:00:00:00".equalsIgnoreCase(macAddress)
                && !"00:00:00:00:00:00".equalsIgnoreCase(macAddress);
    }
}
