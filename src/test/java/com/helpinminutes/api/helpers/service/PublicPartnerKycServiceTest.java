package com.helpinminutes.api.helpers.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.helpinminutes.api.errors.BadRequestException;
import com.helpinminutes.api.helpers.model.HelperKycStatus;
import com.helpinminutes.api.helpers.model.PublicPartnerKycSubmissionEntity;
import com.helpinminutes.api.helpers.repo.PublicPartnerKycSubmissionRepository;
import com.helpinminutes.api.storage.SupabaseStorageService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

public class PublicPartnerKycServiceTest {
  private PublicPartnerKycSubmissionRepository submissions;
  private SupabaseStorageService storage;
  private PublicPartnerKycService service;

  @BeforeEach
  public void setUp() {
    submissions = mock(PublicPartnerKycSubmissionRepository.class);
    storage = mock(SupabaseStorageService.class);
    service = new PublicPartnerKycService(submissions, storage);
    when(submissions.countByPhoneAndCreatedAtAfter(eq("9876543210"), any(Instant.class))).thenReturn(0L);
    when(submissions.countByEmailAndCreatedAtAfter(eq("partner@example.com"), any(Instant.class))).thenReturn(0L);
    when(storage.uploadHelperKycDoc(any(UUID.class), any(String.class), any())).thenAnswer(invocation -> "https://cdn/" + invocation.getArgument(1));
    when(submissions.save(any(PublicPartnerKycSubmissionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test
  public void submitStoresPublicKycWithMaskedPayoutSummary() {
    var response = service.submit(
        "Public Partner",
        "9876543210",
        "partner@example.com",
        "PAN",
        "ABCDE1234F",
        image("front"),
        null,
        image("selfie"),
        "Public Partner",
        "HDFC Bank",
        "1234",
        "HDFC0000001",
        "pub***@upi");

    assertEquals(HelperKycStatus.PENDING, response.status());
    assertEquals("PKYC-", response.referenceId().substring(0, 5));
    ArgumentCaptor<PublicPartnerKycSubmissionEntity> captor = ArgumentCaptor.forClass(PublicPartnerKycSubmissionEntity.class);
    verify(submissions).save(captor.capture());
    PublicPartnerKycSubmissionEntity saved = captor.getValue();
    assertEquals("WEB_PUBLIC_KYC", saved.getSource());
    assertEquals("9876543210", saved.getPhone());
    assertEquals("partner@example.com", saved.getEmail());
    assertEquals("1234", saved.getBankAccountLast4());
  }

  @Test
  public void aadhaarRequiresBackImage() {
    assertThrows(BadRequestException.class, () -> service.submit(
        "Public Partner",
        "9876543210",
        "partner@example.com",
        "AADHAAR",
        "123456789012",
        image("front"),
        null,
        image("selfie"),
        "Public Partner",
        "HDFC Bank",
        "1234",
        "HDFC0000001",
        null));
  }

  @Test
  public void rejectsNonImageUploads() {
    MockMultipartFile textFile = new MockMultipartFile("idFront", "id.txt", "text/plain", "not image".getBytes());
    assertThrows(BadRequestException.class, () -> service.submit(
        "Public Partner",
        "9876543210",
        "partner@example.com",
        "PAN",
        "ABCDE1234F",
        textFile,
        null,
        image("selfie"),
        "Public Partner",
        "HDFC Bank",
        "1234",
        "HDFC0000001",
        null));
  }

  private static MockMultipartFile image(String name) {
    return new MockMultipartFile(name, name + ".jpg", "image/jpeg", new byte[] { 1, 2, 3 });
  }
}
