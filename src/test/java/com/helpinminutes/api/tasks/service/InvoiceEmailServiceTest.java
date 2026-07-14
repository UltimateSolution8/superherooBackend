package com.helpinminutes.api.tasks.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.helpinminutes.api.tasks.model.TaskEntity;
import com.helpinminutes.api.users.model.UserEntity;
import com.helpinminutes.api.users.repo.UserRepository;
import jakarta.mail.internet.MimeMessage;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

public class InvoiceEmailServiceTest {

  private JavaMailSender mailSender;
  private UserRepository userRepository;
  private InvoiceEmailService invoiceEmailService;
  private MimeMessage mimeMessage;

  @BeforeEach
  public void setUp() {
    mailSender = mock(JavaMailSender.class);
    userRepository = mock(UserRepository.class);
    mimeMessage = mock(MimeMessage.class);

    when(mailSender.createMimeMessage()).thenReturn(mimeMessage);

    invoiceEmailService = new InvoiceEmailService(mailSender, userRepository);
  }

  @Test
  public void testSendInvoiceEmailAsync_Success() {
    UUID taskId = UUID.randomUUID();
    UUID buyerId = UUID.randomUUID();
    UUID helperId = UUID.randomUUID();

    TaskEntity task = new TaskEntity();
    ReflectionTestUtils.setField(task, "id", taskId);
    task.setBuyerId(buyerId);
    task.setAssignedHelperId(helperId);
    task.setTitle("Deliver parcel");
    task.setDescription("Deliver small box");
    task.setBudgetPaise(15000L); // Rs 150
    ReflectionTestUtils.setField(task, "updatedAt", Instant.now());

    UserEntity buyer = new UserEntity();
    buyer.setId(buyerId);
    buyer.setEmail("buyer@example.com");
    buyer.setDisplayName("John Doe");
    buyer.setPhone("9876543210");

    UserEntity helper = new UserEntity();
    helper.setId(helperId);
    helper.setDisplayName("Helper Sam");

    when(userRepository.findById(buyerId)).thenReturn(Optional.of(buyer));
    when(userRepository.findById(helperId)).thenReturn(Optional.of(helper));

    assertDoesNotThrow(() -> invoiceEmailService.sendInvoiceEmailAsync(task));

    verify(mailSender, times(1)).createMimeMessage();
    verify(mailSender, times(1)).send(any(MimeMessage.class));
  }

  @Test
  public void testSendInvoiceEmailAsync_NoBuyerEmail() {
    UUID taskId = UUID.randomUUID();
    UUID buyerId = UUID.randomUUID();

    TaskEntity task = new TaskEntity();
    ReflectionTestUtils.setField(task, "id", taskId);
    task.setBuyerId(buyerId);

    UserEntity buyer = new UserEntity();
    buyer.setId(buyerId);
    buyer.setEmail(""); // empty email

    when(userRepository.findById(buyerId)).thenReturn(Optional.of(buyer));

    assertDoesNotThrow(() -> invoiceEmailService.sendInvoiceEmailAsync(task));

    verify(mailSender, never()).createMimeMessage();
    verify(mailSender, never()).send(any(MimeMessage.class));
  }

  @Test
  public void testSendInvoiceEmailAsync_NoBuyer() {
    UUID taskId = UUID.randomUUID();
    UUID buyerId = UUID.randomUUID();

    TaskEntity task = new TaskEntity();
    ReflectionTestUtils.setField(task, "id", taskId);
    task.setBuyerId(buyerId);

    when(userRepository.findById(buyerId)).thenReturn(Optional.empty());

    assertDoesNotThrow(() -> invoiceEmailService.sendInvoiceEmailAsync(task));

    verify(mailSender, never()).createMimeMessage();
    verify(mailSender, never()).send(any(MimeMessage.class));
  }

  @Test
  public void testSendInvoiceEmailAsync_MailExceptionHandledGracefully() {
    UUID taskId = UUID.randomUUID();
    UUID buyerId = UUID.randomUUID();

    TaskEntity task = new TaskEntity();
    ReflectionTestUtils.setField(task, "id", taskId);
    task.setBuyerId(buyerId);
    task.setBudgetPaise(1000L);

    UserEntity buyer = new UserEntity();
    buyer.setId(buyerId);
    buyer.setEmail("buyer@example.com");

    when(userRepository.findById(buyerId)).thenReturn(Optional.of(buyer));
    doThrow(new RuntimeException("Mail server down")).when(mailSender).send(any(MimeMessage.class));

    // Should handle exception internally and not throw
    assertDoesNotThrow(() -> invoiceEmailService.sendInvoiceEmailAsync(task));

    verify(mailSender, times(1)).send(any(MimeMessage.class));
  }
}
