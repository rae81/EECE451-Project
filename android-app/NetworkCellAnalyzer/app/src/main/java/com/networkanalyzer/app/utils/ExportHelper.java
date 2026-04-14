package com.networkanalyzer.app.utils;

import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import com.networkanalyzer.app.database.CellDataEntity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Utility class for exporting cell data to CSV and PDF files and sharing them.
 * <p>
 * On Android 10+ (API 29+), files are written via {@link MediaStore} for
 * scoped-storage compliance per the Android docs
 * (https://developer.android.com/training/data-storage). On earlier
 * versions, files are written directly to the Downloads directory.
 * <p>
 * References:
 * <ul>
 *   <li>CSV formatting: RFC 4180 "Common Format and MIME Type for CSV Files"
 *       — https://datatracker.ietf.org/doc/html/rfc4180
 *   <li>PDF generation: iText 7 Core (AGPL / commercial dual-license) —
 *       https://kb.itextpdf.com/itext/examples-itext7
 *   <li>MediaStore API: https://developer.android.com/reference/android/provider/MediaStore
 * </ul>
 */
public final class ExportHelper {

    private static final String TAG = "ExportHelper";
    private static final String EXPORT_DIR = Constants.EXPORT_DIR;
    private static final SimpleDateFormat DATE_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    private ExportHelper() {
        // Utility class -- prevent instantiation.
    }

    public static Uri saveDownloadedFile(Context context, InputStream inputStream,
                                         String filename, String mimeType) {
        if (inputStream == null) {
            return null;
        }

        try {
            OutputStream outputStream;
            Uri fileUri;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentResolver resolver = context.getContentResolver();
                ContentValues contentValues = new ContentValues();
                contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
                contentValues.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
                contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + File.separator + EXPORT_DIR);

                fileUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues);
                if (fileUri == null) {
                    Log.e(TAG, "Failed to create MediaStore entry for " + filename);
                    return null;
                }
                outputStream = resolver.openOutputStream(fileUri);
            } else {
                File exportDir = new File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        EXPORT_DIR);
                if (!exportDir.exists() && !exportDir.mkdirs()) {
                    Log.e(TAG, "Failed to create export directory.");
                    return null;
                }
                File outputFile = new File(exportDir, filename);
                outputStream = new FileOutputStream(outputFile);
                fileUri = Uri.fromFile(outputFile);
            }

            if (outputStream == null) {
                return null;
            }

            byte[] buffer = new byte[8192];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
            outputStream.flush();
            outputStream.close();
            inputStream.close();
            return fileUri;
        } catch (IOException e) {
            Log.e(TAG, "Failed to save downloaded file " + filename, e);
            return null;
        }
    }

    // =========================================================================
    //  CSV Export
    // =========================================================================

    /**
     * Exports the given cell data list to a CSV file.
     *
     * @param context  application context
     * @param data     list of cell data entities to export
     * @param filename desired file name (without extension)
     * @return the {@link Uri} of the created file, or {@code null} on failure
     */
    public static Uri exportToCsv(Context context, List<CellDataEntity> data, String filename) {
        if (data == null || data.isEmpty()) {
            Log.w(TAG, "No data to export to CSV.");
            return null;
        }

        String csvFilename = filename.endsWith(".csv") ? filename : filename + ".csv";

        try {
            OutputStream outputStream;
            Uri fileUri;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Scoped storage: use MediaStore
                ContentResolver resolver = context.getContentResolver();
                ContentValues contentValues = new ContentValues();
                contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, csvFilename);
                contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "text/csv");
                contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + File.separator + EXPORT_DIR);

                fileUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues);
                if (fileUri == null) {
                    Log.e(TAG, "Failed to create MediaStore entry for CSV.");
                    return null;
                }
                outputStream = resolver.openOutputStream(fileUri);
            } else {
                // Legacy: write directly to Downloads/NetworkAnalyzer
                File exportDir = new File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        EXPORT_DIR);
                if (!exportDir.exists() && !exportDir.mkdirs()) {
                    Log.e(TAG, "Failed to create export directory.");
                    return null;
                }
                File csvFile = new File(exportDir, csvFilename);
                outputStream = new FileOutputStream(csvFile);
                fileUri = Uri.fromFile(csvFile);
            }

            if (outputStream == null) {
                Log.e(TAG, "Output stream is null for CSV.");
                return null;
            }

            // Write CSV header
            String header = "Timestamp,Operator,Network Type,Signal Power (dBm),SNR (dB)," +
                    "Cell ID,Frequency Band,LAC,Latitude,Longitude,SIM Slot\n";
            outputStream.write(header.getBytes());

            // Write data rows
            for (CellDataEntity entity : data) {
                String row = String.format(Locale.US,
                        "%s,%s,%s,%d,%.1f,%s,%s,%s,%.6f,%.6f,%d\n",
                        DATE_FORMAT.format(new Date(entity.getTimestamp())),
                        escapeCsv(entity.getOperator()),
                        escapeCsv(entity.getNetworkType()),
                        entity.getSignalPower(),
                        entity.getSnr(),
                        escapeCsv(entity.getCellId()),
                        escapeCsv(entity.getFrequencyBand()),
                        escapeCsv(entity.getLac()),
                        entity.getLatitude(),
                        entity.getLongitude(),
                        entity.getSimSlot()
                );
                outputStream.write(row.getBytes());
            }

            outputStream.flush();
            outputStream.close();

            Log.i(TAG, "CSV exported successfully: " + fileUri);
            return fileUri;

        } catch (IOException e) {
            Log.e(TAG, "Error exporting CSV", e);
            return null;
        }
    }

    // =========================================================================
    //  PDF Export
    // =========================================================================

    /**
     * Exports the given cell data list to a professional PDF report.
     *
     * @param context  application context
     * @param data     list of cell data entities to export
     * @param filename desired file name (without extension)
     * @return the {@link Uri} of the created file, or {@code null} on failure
     */
    public static Uri exportToPdf(Context context, List<CellDataEntity> data, String filename) {
        if (data == null || data.isEmpty()) {
            Log.w(TAG, "No data to export to PDF.");
            return null;
        }

        String pdfFilename = filename.endsWith(".pdf") ? filename : filename + ".pdf";

        try {
            OutputStream outputStream;
            Uri fileUri;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentResolver resolver = context.getContentResolver();
                ContentValues contentValues = new ContentValues();
                contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, pdfFilename);
                contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf");
                contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + File.separator + EXPORT_DIR);

                fileUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues);
                if (fileUri == null) {
                    Log.e(TAG, "Failed to create MediaStore entry for PDF.");
                    return null;
                }
                outputStream = resolver.openOutputStream(fileUri);
            } else {
                File exportDir = new File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        EXPORT_DIR);
                if (!exportDir.exists() && !exportDir.mkdirs()) {
                    Log.e(TAG, "Failed to create export directory.");
                    return null;
                }
                File pdfFile = new File(exportDir, pdfFilename);
                outputStream = new FileOutputStream(pdfFile);
                fileUri = Uri.fromFile(pdfFile);
            }

            if (outputStream == null) {
                Log.e(TAG, "Output stream is null for PDF.");
                return null;
            }

            // Build the PDF document
            PdfWriter writer = new PdfWriter(outputStream);
            PdfDocument pdfDoc = new PdfDocument(writer);
            Document document = new Document(pdfDoc, PageSize.A4.rotate());
            document.setMargins(30, 30, 30, 30);

            PdfFont boldFont = PdfFontFactory.createFont("Helvetica-Bold");
            PdfFont normalFont = PdfFontFactory.createFont("Helvetica");

            // ---- Title Page ------------------------------------------------

            document.add(new Paragraph("NetworkCellAnalyzer")
                    .setFont(boldFont)
                    .setFontSize(28)
                    .setTextAlignment(TextAlignment.CENTER)
                    .setMarginTop(80));

            document.add(new Paragraph("Cell Data Report")
                    .setFont(boldFont)
                    .setFontSize(20)
                    .setTextAlignment(TextAlignment.CENTER)
                    .setMarginTop(20));

            // Date range
            long minTimestamp = Long.MAX_VALUE;
            long maxTimestamp = Long.MIN_VALUE;
            for (CellDataEntity entity : data) {
                if (entity.getTimestamp() < minTimestamp) minTimestamp = entity.getTimestamp();
                if (entity.getTimestamp() > maxTimestamp) maxTimestamp = entity.getTimestamp();
            }
            String dateRange = String.format(Locale.US, "From: %s\nTo: %s",
                    DATE_FORMAT.format(new Date(minTimestamp)),
                    DATE_FORMAT.format(new Date(maxTimestamp)));

            document.add(new Paragraph(dateRange)
                    .setFont(normalFont)
                    .setFontSize(12)
                    .setTextAlignment(TextAlignment.CENTER)
                    .setMarginTop(20));

            // Device info
            String deviceId = data.get(0).getDeviceId();
            document.add(new Paragraph("Device ID: " + (deviceId != null ? deviceId : "N/A"))
                    .setFont(normalFont)
                    .setFontSize(11)
                    .setTextAlignment(TextAlignment.CENTER)
                    .setMarginTop(10));

            document.add(new Paragraph("Generated: " + DATE_FORMAT.format(new Date()))
                    .setFont(normalFont)
                    .setFontSize(10)
                    .setTextAlignment(TextAlignment.CENTER)
                    .setMarginTop(5));

            document.add(new Paragraph("Total Records: " + data.size())
                    .setFont(normalFont)
                    .setFontSize(10)
                    .setTextAlignment(TextAlignment.CENTER)
                    .setMarginTop(5));

            // ---- Summary Table ---------------------------------------------

            document.add(new Paragraph("\n")); // spacer

            document.add(new Paragraph("Summary")
                    .setFont(boldFont)
                    .setFontSize(16)
                    .setMarginTop(30));

            Table summaryTable = new Table(UnitValue.createPercentArray(new float[]{40, 60}))
                    .useAllAvailableWidth();

            // Calculate summary statistics
            int totalRecords = data.size();
            double avgSignal = 0;
            double avgSnr = 0;
            int count2G = 0, count3G = 0, count4G = 0, count5G = 0;
            for (CellDataEntity e : data) {
                avgSignal += e.getSignalPower();
                avgSnr += e.getSnr();
                if (e.getNetworkType() != null) {
                    switch (e.getNetworkType()) {
                        case "2G": count2G++; break;
                        case "3G": count3G++; break;
                        case "4G": count4G++; break;
                        case "5G": count5G++; break;
                    }
                }
            }
            avgSignal /= totalRecords;
            avgSnr /= totalRecords;

            addSummaryRow(summaryTable, "Total Records", String.valueOf(totalRecords), boldFont, normalFont);
            addSummaryRow(summaryTable, "Avg Signal Power",
                    String.format(Locale.US, "%.1f dBm", avgSignal), boldFont, normalFont);
            addSummaryRow(summaryTable, "Avg SNR",
                    String.format(Locale.US, "%.1f dB", avgSnr), boldFont, normalFont);
            addSummaryRow(summaryTable, "2G Readings", String.valueOf(count2G), boldFont, normalFont);
            addSummaryRow(summaryTable, "3G Readings", String.valueOf(count3G), boldFont, normalFont);
            addSummaryRow(summaryTable, "4G Readings", String.valueOf(count4G), boldFont, normalFont);
            addSummaryRow(summaryTable, "5G Readings", String.valueOf(count5G), boldFont, normalFont);

            document.add(summaryTable);

            // ---- Data Table ------------------------------------------------

            document.add(new Paragraph("Detailed Data")
                    .setFont(boldFont)
                    .setFontSize(16)
                    .setMarginTop(30));

            float[] columnWidths = {12, 10, 8, 8, 7, 9, 8, 7, 10, 10, 5};
            Table dataTable = new Table(UnitValue.createPercentArray(columnWidths))
                    .useAllAvailableWidth()
                    .setFontSize(7);

            // Header row
            String[] headers = {
                    "Timestamp", "Operator", "Type", "Signal (dBm)",
                    "SNR (dB)", "Cell ID", "Freq Band", "LAC",
                    "Latitude", "Longitude", "SIM"
            };
            for (String h : headers) {
                dataTable.addHeaderCell(
                        new Cell().add(new Paragraph(h).setFont(boldFont))
                                .setBackgroundColor(ColorConstants.LIGHT_GRAY)
                                .setTextAlignment(TextAlignment.CENTER)
                );
            }

            // Data rows
            for (CellDataEntity entity : data) {
                dataTable.addCell(createCell(DATE_FORMAT.format(new Date(entity.getTimestamp())), normalFont));
                dataTable.addCell(createCell(safeString(entity.getOperator()), normalFont));
                dataTable.addCell(createCell(safeString(entity.getNetworkType()), normalFont));
                dataTable.addCell(createCell(String.valueOf(entity.getSignalPower()), normalFont));
                dataTable.addCell(createCell(String.format(Locale.US, "%.1f", entity.getSnr()), normalFont));
                dataTable.addCell(createCell(safeString(entity.getCellId()), normalFont));
                dataTable.addCell(createCell(safeString(entity.getFrequencyBand()), normalFont));
                dataTable.addCell(createCell(safeString(entity.getLac()), normalFont));
                dataTable.addCell(createCell(
                        String.format(Locale.US, "%.6f", entity.getLatitude()), normalFont));
                dataTable.addCell(createCell(
                        String.format(Locale.US, "%.6f", entity.getLongitude()), normalFont));
                dataTable.addCell(createCell(String.valueOf(entity.getSimSlot()), normalFont));
            }

            document.add(dataTable);

            // ---- Charts Placeholder ----------------------------------------

            document.add(new Paragraph("Charts & Visualizations")
                    .setFont(boldFont)
                    .setFontSize(16)
                    .setMarginTop(30));

            document.add(new Paragraph(
                    "Signal strength charts and coverage visualizations can be generated " +
                    "in the application and are available in the Charts and Map sections of " +
                    "the NetworkCellAnalyzer app.")
                    .setFont(normalFont)
                    .setFontSize(10)
                    .setMarginTop(10));

            // Close document
            document.close();

            Log.i(TAG, "PDF exported successfully: " + fileUri);
            return fileUri;

        } catch (IOException e) {
            Log.e(TAG, "Error exporting PDF", e);
            return null;
        }
    }

    // =========================================================================
    //  Share
    // =========================================================================

    /**
     * Opens the system share sheet for the given file URI and MIME type.
     *
     * @param context  application context
     * @param fileUri  URI of the file to share
     * @param mimeType MIME type of the file (e.g. "text/csv" or "application/pdf")
     */
    public static void shareFile(Context context, Uri fileUri, String mimeType) {
        if (fileUri == null) {
            Log.w(TAG, "Cannot share: file URI is null.");
            return;
        }

        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType(mimeType);
        shareIntent.putExtra(Intent.EXTRA_STREAM, fileUri);
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        Intent chooser = Intent.createChooser(shareIntent, "Share Report");
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(chooser);
    }

    public static boolean openFile(Context context, Uri fileUri, String mimeType) {
        if (fileUri == null) {
            Log.w(TAG, "Cannot open: file URI is null.");
            return false;
        }

        Intent viewIntent = new Intent(Intent.ACTION_VIEW);
        viewIntent.setDataAndType(fileUri, mimeType);
        viewIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        viewIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        try {
            context.startActivity(viewIntent);
            return true;
        } catch (ActivityNotFoundException viewError) {
            Log.w(TAG, "No activity available to open exported file, falling back to share sheet.", viewError);
            try {
                shareFile(context, fileUri, mimeType);
                return true;
            } catch (Exception shareError) {
                Log.e(TAG, "Unable to surface exported file.", shareError);
                return false;
            }
        }
    }

    // =========================================================================
    //  Private helpers
    // =========================================================================

    /**
     * Escapes a CSV field value. Wraps the value in double quotes if it contains
     * commas, quotes, or newlines.
     */
    private static String escapeCsv(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    /**
     * Returns the string value or an empty string if null.
     */
    private static String safeString(String value) {
        return value != null ? value : "";
    }

    /**
     * Creates a PDF table cell with the given text and font.
     */
    private static Cell createCell(String text, PdfFont font) {
        return new Cell()
                .add(new Paragraph(text).setFont(font))
                .setTextAlignment(TextAlignment.CENTER)
                .setPadding(2);
    }

    /**
     * Adds a key-value row to the summary table.
     */
    private static void addSummaryRow(Table table, String label, String value,
                                       PdfFont labelFont, PdfFont valueFont) {
        table.addCell(new Cell()
                .add(new Paragraph(label).setFont(labelFont).setFontSize(10))
                .setBackgroundColor(ColorConstants.LIGHT_GRAY)
                .setPadding(4));
        table.addCell(new Cell()
                .add(new Paragraph(value).setFont(valueFont).setFontSize(10))
                .setPadding(4));
    }
}
