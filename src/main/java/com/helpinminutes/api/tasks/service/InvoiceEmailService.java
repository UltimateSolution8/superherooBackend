package com.helpinminutes.api.tasks.service;

import com.helpinminutes.api.tasks.model.TaskEntity;
import com.helpinminutes.api.users.model.UserEntity;
import com.helpinminutes.api.users.repo.UserRepository;
import jakarta.mail.internet.MimeMessage;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class InvoiceEmailService {
  private static final Logger log = LoggerFactory.getLogger(InvoiceEmailService.class);
  private static final DateTimeFormatter DATE_FORMATTER = 
      DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a", Locale.ENGLISH);
  private static final ZoneId IST_ZONE = ZoneId.of("Asia/Kolkata");

  private final JavaMailSender mailSender;
  private final UserRepository userRepository;

  public InvoiceEmailService(JavaMailSender mailSender, UserRepository userRepository) {
    this.mailSender = mailSender;
    this.userRepository = userRepository;
  }

  @Async
  public void sendInvoiceEmailAsync(TaskEntity task) {
    if (task == null) {
      log.warn("Null task provided for invoice email, skipping.");
      return;
    }

    try {
      UserEntity buyer = userRepository.findById(task.getBuyerId()).orElse(null);
      if (buyer == null) {
        log.warn("Buyer not found for task ID: {}, skipping invoice email.", task.getId());
        return;
      }

      String recipientEmail = buyer.getEmail();
      if (recipientEmail == null || recipientEmail.isBlank()) {
        log.info("Buyer {} has no email address registered. Skipping task invoice email.", buyer.getId());
        return;
      }

      UserEntity helper = task.getAssignedHelperId() != null 
          ? userRepository.findById(task.getAssignedHelperId()).orElse(null) 
          : null;

      String buyerName = getDisplayName(buyer);
      String helperName = getDisplayName(helper);
      String taskTitle = task.getTitle() != null ? task.getTitle() : "Superhero Task";
      String completionTimeStr = task.getUpdatedAt() != null 
          ? task.getUpdatedAt().atZone(IST_ZONE).format(DATE_FORMATTER) 
          : "-";

      double amountInRupees = task.getBudgetPaise() != null 
          ? task.getBudgetPaise() / 100.0 
          : 0.0;

      String htmlContent = buildHtmlInvoice(
          task.getId().toString(),
          taskTitle,
          task.getDescription(),
          buyerName,
          buyer.getPhone(),
          helperName,
          completionTimeStr,
          amountInRupees
      );

      MimeMessage mimeMessage = mailSender.createMimeMessage();
      MimeMessageHelper helperMessage = new MimeMessageHelper(mimeMessage, true, "UTF-8");
      
      helperMessage.setTo(recipientEmail.trim());
      helperMessage.setSubject("Invoice for Task: " + taskTitle);
      helperMessage.setText(htmlContent, true);

      try {
        ClassPathResource logoRes = new ClassPathResource("static/finallogo.png");
        if (logoRes.exists()) {
          helperMessage.addInline("logoImage", logoRes, "image/png");
        }
      } catch (Exception e) {
        log.warn("Failed to attach inline logo: {}", e.getMessage());
      }

      log.info("Sending asynchronously completed task invoice email to: {}", recipientEmail);
      mailSender.send(mimeMessage);
      log.info("Invoice email successfully sent to {}", recipientEmail);

    } catch (Exception e) {
      log.error("Failed to send invoice email for task {}: {}", task.getId(), e.getMessage());
    }
  }

  private String getDisplayName(UserEntity user) {
    if (user == null) return "N/A";
    return user.getDisplayName() != null && !user.getDisplayName().isBlank() 
        ? user.getDisplayName() 
        : user.getPhone();
  }

  private String buildHtmlInvoice(
      String invoiceId,
      String title,
      String description,
      String buyerName,
      String buyerPhone,
      String helperName,
      String dateStr,
      double amountRupees
  ) {
    String truncatedId = invoiceId.length() > 8 ? invoiceId.substring(0, 8).toUpperCase() : invoiceId;
    return "<!DOCTYPE html>\n"
        + "<html>\n"
        + "<head>\n"
        + "  <meta charset=\"utf-8\">\n"
        + "  <title>Invoice</title>\n"
        + "  <style>\n"
        + "    body {\n"
        + "      font-family: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;\n"
        + "      background-color: #F8FAFC;\n"
        + "      margin: 0;\n"
        + "      padding: 0;\n"
        + "      color: #334155;\n"
        + "      -webkit-font-smoothing: antialiased;\n"
        + "    }\n"
        + "    .container {\n"
        + "      max-width: 600px;\n"
        + "      margin: 40px auto;\n"
        + "      background: #FFFFFF;\n"
        + "      border-radius: 16px;\n"
        + "      box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.1), 0 2px 4px -2px rgba(0, 0, 0, 0.1);\n"
        + "      overflow: hidden;\n"
        + "      border: 1px solid #E2E8F0;\n"
        + "    }\n"
        + "    .header {\n"
        + "      background-color: #0F1932;\n"
        + "      padding: 32px;\n"
        + "      text-align: center;\n"
        + "      color: #FFFFFF;\n"
        + "    }\n"
        + "    .logo {\n"
        + "      font-size: 24px;\n"
        + "      font-weight: 800;\n"
        + "      letter-spacing: 1px;\n"
        + "      color: #FFFFFF;\n"
        + "      margin-bottom: 4px;\n"
        + "    }\n"
        + "    .logo span {\n"
        + "      color: #FF6500;\n"
        + "    }\n"
        + "    .subtitle {\n"
        + "      font-size: 13px;\n"
        + "      color: #94A3B8;\n"
        + "      text-transform: uppercase;\n"
        + "      letter-spacing: 2px;\n"
        + "      margin-top: 4px;\n"
        + "    }\n"
        + "    .content {\n"
        + "      padding: 32px;\n"
        + "    }\n"
        + "    .invoice-info {\n"
        + "      display: table;\n"
        + "      width: 100%;\n"
        + "      margin-bottom: 24px;\n"
        + "      border-bottom: 1px solid #F1F5F9;\n"
        + "      padding-bottom: 20px;\n"
        + "    }\n"
        + "    .info-col {\n"
        + "      display: table-cell;\n"
        + "      width: 50%;\n"
        + "    }\n"
        + "    .info-col.right {\n"
        + "      text-align: right;\n"
        + "    }\n"
        + "    .label {\n"
        + "      font-size: 12px;\n"
        + "      color: #64748B;\n"
        + "      text-transform: uppercase;\n"
        + "      letter-spacing: 0.5px;\n"
        + "      margin-bottom: 4px;\n"
        + "    }\n"
        + "    .value {\n"
        + "      font-size: 15px;\n"
        + "      font-weight: 600;\n"
        + "      color: #0F1932;\n"
        + "    }\n"
        + "    .badge {\n"
        + "      display: inline-block;\n"
        + "      background-color: #DCFCE7;\n"
        + "      color: #15803D;\n"
        + "      padding: 4px 12px;\n"
        + "      border-radius: 9999px;\n"
        + "      font-size: 12px;\n"
        + "      font-weight: 700;\n"
        + "      text-transform: uppercase;\n"
        + "      margin-top: 4px;\n"
        + "    }\n"
        + "    .section-title {\n"
        + "      font-size: 14px;\n"
        + "      font-weight: 700;\n"
        + "      color: #0F1932;\n"
        + "      text-transform: uppercase;\n"
        + "      letter-spacing: 0.5px;\n"
        + "      margin-top: 24px;\n"
        + "      margin-bottom: 12px;\n"
        + "    }\n"
        + "    .details-box {\n"
        + "      background-color: #F8FAFC;\n"
        + "      border-radius: 12px;\n"
        + "      padding: 16px;\n"
        + "      border: 1px solid #F1F5F9;\n"
        + "      margin-bottom: 24px;\n"
        + "    }\n"
        + "    .detail-row {\n"
        + "      display: table;\n"
        + "      width: 100%;\n"
        + "      margin-bottom: 8px;\n"
        + "    }\n"
        + "    .detail-row:last-child {\n"
        + "      margin-bottom: 0;\n"
        + "    }\n"
        + "    .detail-label {\n"
        + "      display: table-cell;\n"
        + "      font-size: 14px;\n"
        + "      color: #64748B;\n"
        + "      width: 35%;\n"
        + "    }\n"
        + "    .detail-value {\n"
        + "      display: table-cell;\n"
        + "      font-size: 14px;\n"
        + "      font-weight: 600;\n"
        + "      color: #0F1932;\n"
        + "      width: 65%;\n"
        + "    }\n"
        + "    .summary-table {\n"
        + "      width: 100%;\n"
        + "      border-collapse: collapse;\n"
        + "      margin-top: 16px;\n"
        + "      margin-bottom: 24px;\n"
        + "    }\n"
        + "    .summary-table th {\n"
        + "      text-align: left;\n"
        + "      font-size: 12px;\n"
        + "      color: #64748B;\n"
        + "      text-transform: uppercase;\n"
        + "      padding: 12px;\n"
        + "      border-bottom: 2px solid #E2E8F0;\n"
        + "    }\n"
        + "    .summary-table td {\n"
        + "      padding: 16px 12px;\n"
        + "      border-bottom: 1px solid #F1F5F9;\n"
        + "      font-size: 14px;\n"
        + "    }\n"
        + "    .summary-table .amount-col {\n"
        + "      text-align: right;\n"
        + "      font-weight: 600;\n"
        + "    }\n"
        + "    .total-section {\n"
        + "      border-top: 2px solid #E2E8F0;\n"
        + "      margin-top: 16px;\n"
        + "      padding-top: 16px;\n"
        + "      text-align: right;\n"
        + "    }\n"
        + "    .total-title {\n"
        + "      font-size: 14px;\n"
        + "      color: #64748B;\n"
        + "      display: inline-block;\n"
        + "      margin-right: 12px;\n"
        + "    }\n"
        + "    .total-amount {\n"
        + "      font-size: 24px;\n"
        + "      font-weight: 800;\n"
        + "      color: #FF6500;\n"
        + "      display: inline-block;\n"
        + "      vertical-align: middle;\n"
        + "    }\n"
        + "    .footer {\n"
        + "      background-color: #F8FAFC;\n"
        + "      padding: 24px 32px;\n"
        + "      text-align: center;\n"
        + "      font-size: 12px;\n"
        + "      color: #94A3B8;\n"
        + "      border-top: 1px solid #E2E8F0;\n"
        + "    }\n"
        + "    .footer p {\n"
        + "      margin: 4px 0;\n"
        + "    }\n"
        + "    .footer a {\n"
        + "      color: #FF6500;\n"
        + "      text-decoration: none;\n"
        + "    }\n"
        + "  </style>\n"
        + "</head>\n"
        + "<body>\n"
        + "  <div class=\"container\">\n"
        + "    <div class=\"header\">\n"
        + "      <img src=\"cid:logoImage\" alt=\"Superherooo Logo\" style=\"max-height: 48px; margin-bottom: 8px;\" />\n"
        + "      <div class=\"subtitle\">Your Everyday Hero</div>\n"
        + "    </div>\n"
        + "    <div class=\"content\">\n"
        + "      <div class=\"invoice-info\">\n"
        + "        <div class=\"info-col\">\n"
        + "          <div class=\"label\">Invoice ID</div>\n"
        + "          <div class=\"value\">#INV-" + truncatedId + "</div>\n"
        + "          <div class=\"label\" style=\"margin-top: 12px;\">Date Completed</div>\n"
        + "          <div class=\"value\">" + dateStr + "</div>\n"
        + "        </div>\n"
        + "        <div class=\"info-col right\">\n"
        + "          <div class=\"label\">Payment Status</div>\n"
        + "          <div><span class=\"badge\">Paid</span></div>\n"
        + "        </div>\n"
        + "      </div>\n"
        + "\n"
        + "      <div class=\"section-title\">Booking Details</div>\n"
        + "      <div class=\"details-box\">\n"
        + "        <div class=\"detail-row\">\n"
        + "          <div class=\"detail-label\">Customer</div>\n"
        + "          <div class=\"detail-value\">" + buyerName + " (" + buyerPhone + ")</div>\n"
        + "        </div>\n"
        + "        <div class=\"detail-row\">\n"
        + "          <div class=\"detail-label\">Assigned Helper</div>\n"
        + "          <div class=\"detail-value\">" + helperName + "</div>\n"
        + "        </div>\n"
        + "      </div>\n"
        + "\n"
        + "      <div class=\"section-title\">Task Summary</div>\n"
        + "      <table class=\"summary-table\">\n"
        + "        <thead>\n"
        + "          <tr>\n"
        + "            <th style=\"width: 70%;\">Description</th>\n"
        + "            <th style=\"width: 30%; text-align: right;\">Amount</th>\n"
        + "          </tr>\n"
        + "        </thead>\n"
        + "        <tbody>\n"
        + "          <tr>\n"
        + "            <td>\n"
        + "              <div style=\"font-weight: 600; color: #0F1932; margin-bottom: 4px;\">" + title + "</div>\n"
        + "              <div style=\"font-size: 13px; color: #64748B;\">" + (description != null ? description : "On-demand helper support task.") + "</div>\n"
        + "            </td>\n"
        + "            <td class=\"amount-col\">₹" + String.format(Locale.US, "%.2f", amountRupees) + "</td>\n"
        + "          </tr>\n"
        + "        </tbody>\n"
        + "      </table>\n"
        + "\n"
        + "      <div class=\"total-section\">\n"
        + "        <div class=\"total-title\">Total Paid via Cash/UPI:</div>\n"
        + "        <div class=\"total-amount\">₹" + String.format(Locale.US, "%.2f", amountRupees) + "</div>\n"
        + "      </div>\n"
        + "    </div>\n"
        + "    <div class=\"footer\">\n"
        + "      <p>Thank you for choosing Superherooo!</p>\n"
        + "      <p>For support or queries, contact us at <a href=\"mailto:info@superherooo.com\">info@superherooo.com</a></p>\n"
        + "      <p style=\"margin-top: 12px; font-size: 10px;\">&copy; 2026 Superherooo Pvt Ltd. All rights reserved.</p>\n"
        + "    </div>\n"
        + "  </div>\n"
        + "</body>\n"
        + "</html>";
  }
}
