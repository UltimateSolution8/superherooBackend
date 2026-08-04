package com.helpinminutes.api.kyc.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helpinminutes.api.errors.BadRequestException;
import com.helpinminutes.api.errors.LiveKycUnavailableException;
import com.helpinminutes.api.helpers.repo.HelperProfileRepository;
import com.helpinminutes.api.kyc.dto.KycActionRequest;
import com.helpinminutes.api.kyc.livekit.LiveKitService;
import com.helpinminutes.api.kyc.model.KycRequestEntity;
import com.helpinminutes.api.kyc.model.KycRequestStatus;
import com.helpinminutes.api.kyc.repository.KycAuditLogRepository;
import com.helpinminutes.api.kyc.repository.KycRequestRepository;
import com.helpinminutes.api.notifications.service.NotificationQueueService;
import com.helpinminutes.api.realtime.RealtimePublisher;
import com.helpinminutes.api.storage.SupabaseStorageService;
import com.helpinminutes.api.users.model.UserEntity;
import com.helpinminutes.api.users.repo.UserRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

@ExtendWith(MockitoExtension.class)
class KycServiceLiveSessionTest {
  @Mock KycRequestRepository requests;
  @Mock KycAuditLogRepository auditLogs;
  @Mock UserRepository users;
  @Mock HelperProfileRepository helperProfiles;
  @Mock NotificationQueueService notifications;
  @Mock SupabaseStorageService storage;
  @Mock RabbitTemplate rabbit;
  @Mock LiveKitService liveKit;
  @Mock RealtimePublisher realtime;

  private KycService service;

  @BeforeEach
  void setUp() {
    service = new KycService(
        requests,
        auditLogs,
        users,
        helperProfiles,
        notifications,
        storage,
        rabbit,
        new ObjectMapper(),
        liveKit,
        realtime);
  }

  @Test
  void startsARestrictedLiveKitSessionAndIssuesOnlyTheAdminToken() {
    UUID helperId = UUID.randomUUID();
    UUID adminId = UUID.randomUUID();
    UserEntity helper = user(helperId, "KYC Helper");
    UserEntity admin = user(adminId, "KYC Admin");

    when(users.findById(helperId)).thenReturn(Optional.of(helper));
    when(users.findById(adminId)).thenReturn(Optional.of(admin));
    when(requests.findActiveLiveSessions(eq(helperId), anyList())).thenReturn(List.of());
    when(requests.save(any(KycRequestEntity.class))).thenAnswer(invocation -> {
      KycRequestEntity entity = invocation.getArgument(0);
      entity.prePersist();
      return entity;
    });
    when(liveKit.tokenTtlSeconds()).thenReturn(900L);
    when(liveKit.serverUrl()).thenReturn("wss://livekit.mysuperhero.xyz");
    when(liveKit.createParticipantToken(any(), any(), any())).thenReturn("admin-token");

    var result = service.startLiveKyc(helperId, adminId);

    assertEquals("LIVEKIT", result.provider());
    assertEquals("wss://livekit.mysuperhero.xyz", result.serverUrl());
    assertEquals(helperId, result.helperId());
    assertEquals("admin-token", result.token());
    assertNotNull(result.id());
    assertNotNull(result.expiresAt());
    verify(liveKit).ensureTwoParticipantRoom(result.roomId());
    verify(liveKit).createParticipantToken(result.userId(), "KYC Admin", result.roomId());
  }

  @Test
  void propagatesTheDedicatedUnavailableErrorWithoutPersistingASession() {
    UUID helperId = UUID.randomUUID();
    UUID adminId = UUID.randomUUID();
    when(users.findById(helperId)).thenReturn(Optional.of(user(helperId, "Helper")));
    when(users.findById(adminId)).thenReturn(Optional.of(user(adminId, "Admin")));
    when(requests.findActiveLiveSessions(eq(helperId), anyList())).thenReturn(List.of());
    doThrow(new LiveKycUnavailableException("offline", new IllegalStateException()))
        .when(liveKit).ensureTwoParticipantRoom(any());

    assertThrows(LiveKycUnavailableException.class, () -> service.startLiveKyc(helperId, adminId));
    verify(requests, never()).save(any());
  }

  @Test
  void endingASessionDeletesTheRoomAndPersistsTheEndTimestamp() {
    UUID kycId = UUID.randomUUID();
    UUID adminId = UUID.randomUUID();
    KycRequestEntity entity = new KycRequestEntity();
    entity.setId(kycId);
    entity.setLiveRoomId("kyc_room");
    entity.setStatus(KycRequestStatus.SUBMITTED);
    when(requests.findById(kycId)).thenReturn(Optional.of(entity));
    when(users.findById(adminId)).thenReturn(Optional.of(user(adminId, "Admin")));

    service.endLiveSession(kycId, adminId);

    verify(liveKit).deleteRoomBestEffort("kyc_room");
    assertNotNull(entity.getLiveEndedAt());
  }

  @Test
  void approvalRejectsAnIncompleteLiveSnapshotSet() {
    UUID kycId = UUID.randomUUID();
    UUID adminId = UUID.randomUUID();
    KycRequestEntity entity = new KycRequestEntity();
    entity.setId(kycId);
    entity.setStatus(KycRequestStatus.SUBMITTED);
    entity.setLiveRoomId("kyc_room");
    entity.setSelfiePath("kyc/selfie.jpg");
    when(requests.findById(kycId)).thenReturn(Optional.of(entity));
    when(users.findById(adminId)).thenReturn(Optional.of(user(adminId, "Admin")));

    assertThrows(
        BadRequestException.class,
        () -> service.adminAction(kycId, adminId, new KycActionRequest("APPROVE", "reviewed")));
    verify(liveKit, never()).deleteRoomBestEffort(any());
  }

  private static UserEntity user(UUID id, String name) {
    UserEntity user = new UserEntity();
    user.setId(id);
    user.setDisplayName(name);
    return user;
  }
}
