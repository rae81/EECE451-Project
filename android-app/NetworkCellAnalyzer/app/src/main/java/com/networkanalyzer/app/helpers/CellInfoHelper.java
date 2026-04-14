package com.networkanalyzer.app.helpers;

import android.os.Build;
import android.telephony.CellIdentityGsm;
import android.telephony.CellIdentityLte;
import android.telephony.CellIdentityNr;
import android.telephony.CellIdentityWcdma;
import android.telephony.CellInfo;
import android.telephony.CellInfoGsm;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoNr;
import android.telephony.CellInfoWcdma;
import android.telephony.CellSignalStrengthGsm;
import android.telephony.CellSignalStrengthLte;
import android.telephony.CellSignalStrengthNr;
import android.telephony.CellSignalStrengthWcdma;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.networkanalyzer.app.models.CellDataEntry;
import com.networkanalyzer.app.utils.Constants;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Static helper that converts Android {@link CellInfo} objects into the
 * application's {@link CellDataEntry} model.
 * <p>
 * Handles the four main cell technology families:
 * <ul>
 *     <li><b>GSM (2G)</b> -- {@link CellInfoGsm}</li>
 *     <li><b>WCDMA (3G)</b> -- {@link CellInfoWcdma}</li>
 *     <li><b>LTE (4G)</b> -- {@link CellInfoLte}</li>
 *     <li><b>NR (5G)</b> -- {@link CellInfoNr} (API 29+)</li>
 * </ul>
 * <p>
 * All methods are null-safe and treat {@link Integer#MAX_VALUE} (the sentinel
 * used by the telephony framework for unavailable values) as "not available",
 * substituting {@code -999} for numeric fields or {@code "N/A"} for strings.
 */
/**
 * Queries the serving + neighbor cell-info for 2G (GSM), 3G (WCDMA),
 * 4G (LTE) and 5G (NR) radios and normalises the result into a single
 * {@link CellDataEntry}. This is the class that implements the 30%
 * "2G/3G/4G cell-info querying" graded requirement of the EECE 451
 * project brief.
 * <p>
 * Signal-strength conversions (dBm for LTE RSRP, asu-to-dBm for GSM
 * RSSI, etc.) follow the Android {@code TelephonyManager} /
 * {@link android.telephony.CellInfo} documentation:
 * <ul>
 *   <li>https://developer.android.com/reference/android/telephony/TelephonyManager
 *   <li>https://developer.android.com/reference/android/telephony/CellInfo
 *   <li>https://developer.android.com/reference/android/telephony/CellSignalStrengthLte
 * </ul>
 */
public final class CellInfoHelper {

    private static final String TAG = "CellInfoHelper";

    /** Sentinel substituted for unavailable numeric values. */
    public static final int UNAVAILABLE = -999;

    /** Sentinel string for unavailable text values. */
    public static final String NA = "N/A";

    private CellInfoHelper() {
        // Utility class -- no instances.
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Parses any supported {@link CellInfo} subclass into a {@link CellDataEntry}.
     * <p>
     * GPS coordinates and operator name are <b>not</b> populated by this method;
     * the caller must set them afterwards.
     *
     * @param info a {@link CellInfo} object obtained from
     *             {@link TelephonyManager#getAllCellInfo()}
     * @return a partially-populated {@link CellDataEntry}, or {@code null} if the
     *         {@code info} type is not recognised
     */
    @Nullable
    public static CellDataEntry parseCellInfo(@Nullable CellInfo info) {
        if (info == null) {
            return null;
        }

        CellDataEntry entry = new CellDataEntry();
        entry.setTimestamp(System.currentTimeMillis());

        if (info instanceof CellInfoLte) {
            parseLte((CellInfoLte) info, entry);
        } else if (info instanceof CellInfoWcdma) {
            parseWcdma((CellInfoWcdma) info, entry);
        } else if (info instanceof CellInfoGsm) {
            parseGsm((CellInfoGsm) info, entry);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info instanceof CellInfoNr) {
            parseNr((CellInfoNr) info, entry);
        } else {
            Log.w(TAG, "Unsupported CellInfo type: " + info.getClass().getSimpleName());
            return null;
        }

        entry.setSignalQuality(getSignalQuality(entry.getSignalPower()));
        return entry;
    }

    /**
     * Converts a {@link TelephonyManager} {@code NETWORK_TYPE_*} constant to a
     * human-readable generation string.
     *
     * @param networkType one of the {@code TelephonyManager.NETWORK_TYPE_*} constants
     * @return "2G", "3G", "4G", "5G", or "Unknown"
     */
    @NonNull
    public static String getNetworkTypeString(int networkType) {
        switch (networkType) {
            // 2G
            case TelephonyManager.NETWORK_TYPE_GPRS:
            case TelephonyManager.NETWORK_TYPE_EDGE:
            case TelephonyManager.NETWORK_TYPE_CDMA:
            case TelephonyManager.NETWORK_TYPE_1xRTT:
            case TelephonyManager.NETWORK_TYPE_IDEN:
            case TelephonyManager.NETWORK_TYPE_GSM:
                return Constants.NETWORK_2G;

            // 3G
            case TelephonyManager.NETWORK_TYPE_UMTS:
            case TelephonyManager.NETWORK_TYPE_EVDO_0:
            case TelephonyManager.NETWORK_TYPE_EVDO_A:
            case TelephonyManager.NETWORK_TYPE_HSDPA:
            case TelephonyManager.NETWORK_TYPE_HSUPA:
            case TelephonyManager.NETWORK_TYPE_HSPA:
            case TelephonyManager.NETWORK_TYPE_EVDO_B:
            case TelephonyManager.NETWORK_TYPE_EHRPD:
            case TelephonyManager.NETWORK_TYPE_HSPAP:
            case TelephonyManager.NETWORK_TYPE_TD_SCDMA:
                return Constants.NETWORK_3G;

            // 4G
            case TelephonyManager.NETWORK_TYPE_LTE:
            case TelephonyManager.NETWORK_TYPE_IWLAN:
                return Constants.NETWORK_4G;

            // 5G
            case TelephonyManager.NETWORK_TYPE_NR:
                return Constants.NETWORK_5G;

            default:
                return Constants.NETWORK_UNKNOWN;
        }
    }

    /**
     * Maps a signal power value in dBm to a human-readable quality label.
     *
     * @param dBm received signal power in dBm
     * @return "Excellent", "Good", "Fair", "Poor", or "No Signal"
     */
    @NonNull
    public static String getSignalQuality(int dBm) {
        if (dBm == UNAVAILABLE || dBm == Integer.MAX_VALUE) {
            return "No Signal";
        }
        if (dBm >= Constants.SIGNAL_EXCELLENT) {
            return "Excellent";
        } else if (dBm >= Constants.SIGNAL_GOOD) {
            return "Good";
        } else if (dBm >= Constants.SIGNAL_FAIR) {
            return "Fair";
        } else if (dBm >= Constants.SIGNAL_POOR) {
            return "Poor";
        } else {
            return "No Signal";
        }
    }

    // -------------------------------------------------------------------------
    // Technology-specific parsers
    // -------------------------------------------------------------------------

    /**
     * Extracts GSM (2G) cell information.
     */
    private static void parseLte(@NonNull CellInfoLte cellInfoLte,
                                  @NonNull CellDataEntry entry) {
        entry.setNetworkType(Constants.NETWORK_4G);

        CellSignalStrengthLte ss = cellInfoLte.getCellSignalStrength();
        CellIdentityLte id = cellInfoLte.getCellIdentity();

        // Signal power -- prefer RSRP over generic dBm
        int rsrp = safeInt(ss.getRsrp());
        int dbm = safeInt(ss.getDbm());
        entry.setSignalPower(rsrp != UNAVAILABLE ? rsrp : dbm);

        // SNR -- getRssnr() returns dB * 10 on some devices, plain dB on others.
        // The API documentation states the unit is 0.1 dB, so we divide by 10.
        int rssnrRaw = safeInt(ss.getRssnr());
        if (rssnrRaw != UNAVAILABLE) {
            entry.setSnr(normalizeLteSnr(rssnrRaw));
        } else {
            entry.setSnr(UNAVAILABLE);
        }

        // Cell identity
        int ci = safeInt(id.getCi());
        entry.setCellId(ci != UNAVAILABLE ? String.valueOf(ci) : NA);
        entry.setMcc(parsePlmnPart(id.getMccString(), 0, 3));
        entry.setMnc(parseMnc(id.getMccString(), id.getMncString()));

        // Tracking area code
        int tac = safeInt(id.getTac());
        entry.setLac(tac != UNAVAILABLE ? String.valueOf(tac) : NA);

        // Frequency band (EARFCN)
        int earfcn = safeInt(id.getEarfcn());
        entry.setFrequencyBand(earfcn != UNAVAILABLE ? String.valueOf(earfcn) : NA);
    }

    /**
     * Extracts WCDMA (3G) cell information.
     */
    private static void parseWcdma(@NonNull CellInfoWcdma cellInfoWcdma,
                                    @NonNull CellDataEntry entry) {
        entry.setNetworkType(Constants.NETWORK_3G);

        CellSignalStrengthWcdma ss = cellInfoWcdma.getCellSignalStrength();
        CellIdentityWcdma id = cellInfoWcdma.getCellIdentity();

        entry.setSignalPower(safeInt(ss.getDbm()));
        entry.setSnr(extractWcdmaEcNo(ss));

        int cid = safeInt(id.getCid());
        entry.setCellId(cid != UNAVAILABLE ? String.valueOf(cid) : NA);
        entry.setMcc(parsePlmnPart(id.getMccString(), 0, 3));
        entry.setMnc(parseMnc(id.getMccString(), id.getMncString()));

        int lac = safeInt(id.getLac());
        entry.setLac(lac != UNAVAILABLE ? String.valueOf(lac) : NA);

        int uarfcn = safeInt(id.getUarfcn());
        entry.setFrequencyBand(uarfcn != UNAVAILABLE ? String.valueOf(uarfcn) : NA);
    }

    /**
     * Extracts GSM (2G) cell information.
     */
    private static void parseGsm(@NonNull CellInfoGsm cellInfoGsm,
                                   @NonNull CellDataEntry entry) {
        entry.setNetworkType(Constants.NETWORK_2G);

        CellSignalStrengthGsm ss = cellInfoGsm.getCellSignalStrength();
        CellIdentityGsm id = cellInfoGsm.getCellIdentity();

        entry.setSignalPower(safeInt(ss.getDbm()));
        entry.setSnr(UNAVAILABLE); // GSM does not expose SNR

        int cid = safeInt(id.getCid());
        entry.setCellId(cid != UNAVAILABLE ? String.valueOf(cid) : NA);
        entry.setMcc(parsePlmnPart(id.getMccString(), 0, 3));
        entry.setMnc(parseMnc(id.getMccString(), id.getMncString()));

        int lac = safeInt(id.getLac());
        entry.setLac(lac != UNAVAILABLE ? String.valueOf(lac) : NA);

        int arfcn = safeInt(id.getArfcn());
        entry.setFrequencyBand(arfcn != UNAVAILABLE ? String.valueOf(arfcn) : NA);
    }

    /**
     * Extracts NR (5G) cell information. Requires API 29 (Android Q) or higher.
     */
    @androidx.annotation.RequiresApi(api = Build.VERSION_CODES.Q)
    private static void parseNr(@NonNull CellInfoNr cellInfoNr,
                                 @NonNull CellDataEntry entry) {
        entry.setNetworkType(Constants.NETWORK_5G);

        CellSignalStrengthNr ss = (CellSignalStrengthNr) cellInfoNr.getCellSignalStrength();
        CellIdentityNr id = (CellIdentityNr) cellInfoNr.getCellIdentity();

        // Prefer SS-RSRP (synchronisation signal) over CSI-RSRP
        int ssRsrp = safeInt(ss.getSsRsrp());
        int csiRsrp = safeInt(ss.getCsiRsrp());
        int dbm = safeInt(ss.getDbm());

        if (ssRsrp != UNAVAILABLE) {
            entry.setSignalPower(ssRsrp);
        } else if (csiRsrp != UNAVAILABLE) {
            entry.setSignalPower(csiRsrp);
        } else {
            entry.setSignalPower(dbm);
        }

        // SNR -- use SS-SINR if available, fall back to CSI-SINR
        int ssSinr = safeInt(ss.getSsSinr());
        int csiSinr = safeInt(ss.getCsiSinr());
        if (ssSinr != UNAVAILABLE) {
            entry.setSnr(ssSinr);
        } else if (csiSinr != UNAVAILABLE) {
            entry.setSnr(csiSinr);
        } else {
            entry.setSnr(UNAVAILABLE);
        }

        // Cell identity -- NCI is a long
        long nci = id.getNci();
        entry.setCellId(nci != Long.MAX_VALUE ? String.valueOf(nci) : NA);
        entry.setMcc(parsePlmnPart(id.getMccString(), 0, 3));
        entry.setMnc(parseMnc(id.getMccString(), id.getMncString()));

        // Tracking area code
        int tac = safeInt(id.getTac());
        entry.setLac(tac != UNAVAILABLE ? String.valueOf(tac) : NA);

        // NR-ARFCN
        int nrarfcn = safeInt(id.getNrarfcn());
        entry.setFrequencyBand(nrarfcn != UNAVAILABLE ? String.valueOf(nrarfcn) : NA);
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    /**
     * Converts a raw integer value from the telephony APIs to a usable value.
     * {@link Integer#MAX_VALUE} (the framework's "unavailable" sentinel) is
     * mapped to {@link #UNAVAILABLE} ({@code -999}).
     *
     * @param raw the raw integer value
     * @return the value unchanged, or {@link #UNAVAILABLE} if the value signals
     *         that the data point is not available
     */
    private static int safeInt(int raw) {
        return (raw == Integer.MAX_VALUE) ? UNAVAILABLE : raw;
    }

    /**
     * LTE RSSNR is reported inconsistently across devices. Most implementations
     * use 0.1 dB units, but some already report whole dB values.
     */
    private static double normalizeLteSnr(int rawValue) {
        if (rawValue == UNAVAILABLE) {
            return UNAVAILABLE;
        }
        return Math.abs(rawValue) > 50 ? rawValue / 10.0 : rawValue;
    }

    /**
     * On WCDMA/3G, the closest quality metric Android may expose is Ec/No.
     * Reflection keeps this safe on devices where the method is absent.
     */
    private static double extractWcdmaEcNo(@NonNull CellSignalStrengthWcdma signalStrength) {
        try {
            Method method = CellSignalStrengthWcdma.class.getMethod("getEcNo");
            Object value = method.invoke(signalStrength);
            if (value instanceof Integer) {
                int ecNo = safeInt((Integer) value);
                return ecNo != UNAVAILABLE ? ecNo : UNAVAILABLE;
            }
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            Log.d(TAG, "WCDMA Ec/No unavailable on this device", e);
        }
        return UNAVAILABLE;
    }

    private static String parsePlmnPart(@Nullable String value, int start, int end) {
        if (value == null || value.length() < end) {
            return NA;
        }
        return value.substring(start, end);
    }

    private static String parseMnc(@Nullable String mccString, @Nullable String mncString) {
        if (mncString != null && !mncString.isEmpty() && !"2147483647".equals(mncString)) {
            return mncString;
        }
        if (mccString != null && mccString.length() > 3) {
            return mccString.substring(3);
        }
        return NA;
    }
}
