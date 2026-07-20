package com.helpinminutes.api.reports.service;

import com.helpinminutes.api.reports.dto.ReportDtos.BookingReportItem;
import com.helpinminutes.api.reports.dto.ReportDtos.BookingReportResponse;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@Service
public class ReportExportService {

  private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Kolkata"));

  public StreamingResponseBody streamBookingReportCsv(ReportService reportService, Instant start, Instant end, String status, String service, String location) {
    return (OutputStream outputStream) -> {
      try (PrintWriter writer = new PrintWriter(outputStream, true, StandardCharsets.UTF_8)) {
        // Write CSV Header
        writer.println("Booking ID,Title,Customer Name,Customer Phone,Helper Name,Helper Phone,Status,Budget (₹),Haversine Distance (km),Lead Time (min),Address,Arrival Selfie URL,Completion Selfie URL,Created At");

        BookingReportResponse response = reportService.getBookingReport(start, end, status, service, location);
        for (BookingReportItem item : response.items()) {
          writer.printf("\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",%.2f,%s,%s,\"%s\",\"%s\",\"%s\",\"%s\"%n",
              item.id(),
              escapeCsv(item.title()),
              escapeCsv(item.buyerName()),
              escapeCsv(item.buyerPhone()),
              escapeCsv(item.helperName()),
              escapeCsv(item.helperPhone()),
              item.status(),
              item.budgetPaise() / 100.0,
              item.haversineDistanceKm() != null ? String.format(java.util.Locale.US, "%.2f", item.haversineDistanceKm()) : "N/A",
              item.leadTimeMinutes() != null ? item.leadTimeMinutes() : "N/A",
              escapeCsv(item.addressText()),
              escapeCsv(item.arrivalSelfieUrl() != null ? item.arrivalSelfieUrl() : "N/A"),
              escapeCsv(item.completionSelfieUrl() != null ? item.completionSelfieUrl() : "N/A"),
              item.createdAt() != null ? FMT.format(item.createdAt()) : "N/A"
          );
          writer.flush();
        }
      }
    };
  }

  private String escapeCsv(String input) {
    if (input == null) return "";
    return input.replace("\"", "\"\"");
  }
}
