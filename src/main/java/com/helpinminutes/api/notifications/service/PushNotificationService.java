package com.helpinminutes.api.notifications.service;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.Notification;
import com.helpinminutes.api.notifications.model.PushTokenEntity;
import com.helpinminutes.api.tasks.model.TaskEntity;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class PushNotificationService {
  private static final Logger log = LoggerFactory.getLogger(PushNotificationService.class);

  private final PushTokenService tokens;
  private final FirebaseMessaging messaging;

  public PushNotificationService(
      PushTokenService tokens,
      @Value("${FIREBASE_SERVICE_ACCOUNT_JSON:}") String serviceAccountJson,
      @Value("${FIREBASE_SERVICE_ACCOUNT_BASE64:}") String serviceAccountBase64,
      @Value("${FIREBASE_SERVICE_ACCOUNT_PATH:}") String serviceAccountPath) {
    this.tokens = tokens;
    this.messaging = initFirebase(serviceAccountJson, serviceAccountBase64, serviceAccountPath);
  }

  private FirebaseMessaging initFirebase(String json, String base64, String path) {
    try {
      String payload = json;
      if ((payload == null || payload.isBlank()) && path != null && !path.isBlank()) {
        payload = Files.readString(Path.of(path));
      }
      if ((payload == null || payload.isBlank()) && base64 != null && !base64.isBlank()) {
        payload = new String(java.util.Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
      }
      if (payload == null || payload.isBlank()) {
        log.warn("Push notifications disabled: missing FIREBASE_SERVICE_ACCOUNT_JSON or BASE64.");
        return null;
      }
      if (FirebaseApp.getApps().isEmpty()) {
        FirebaseOptions options = FirebaseOptions.builder()
            .setCredentials(GoogleCredentials.fromStream(
                new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8))))
            .build();
        FirebaseApp.initializeApp(options);
      }
      return FirebaseMessaging.getInstance();
    } catch (Exception e) {
      log.error("Failed to initialize Firebase push notifications", e);
      return null;
    }
  }

  public void notifyTaskOffered(List<UUID> helperIds, TaskEntity task) {
    if (messaging == null) return;
    List<PushTokenEntity> tokenEntities = tokens.getTokensForUsers(helperIds);
    if (tokenEntities.isEmpty()) return;

    List<String> tokenList = new ArrayList<>();
    for (PushTokenEntity t : tokenEntities) {
      if (t.getToken() != null && !t.getToken().isBlank()) {
        tokenList.add(t.getToken());
      }
    }
    if (tokenList.isEmpty()) return;

    try {
      MulticastMessage msg = MulticastMessage.builder()
          .addAllTokens(tokenList)
          .setNotification(Notification.builder()
              .setTitle("New task nearby")
              .setBody(task.getTitle() == null ? "Tap to view details" : task.getTitle())
              .build())
          .putData("type", "TASK_OFFERED")
          .putData("taskId", task.getId().toString())
          .putData("title", task.getTitle() == null ? "Task" : task.getTitle())
          .putData("urgency", task.getUrgency().name())
          .putData("lat", String.valueOf(task.getLat()))
          .putData("lng", String.valueOf(task.getLng()))
          .build();
      messaging.sendEachForMulticast(msg);
    } catch (Exception e) {
      log.warn("Failed to send push notifications for task {}", task.getId(), e);
      throw new RuntimeException("Failed to send task offered notification", e);
    }
  }

  public void notifyBuyerTaskAccepted(UUID buyerId, TaskEntity task) {
    if (messaging == null || buyerId == null) return;
    List<PushTokenEntity> tokenEntities = tokens.getTokensForUsers(List.of(buyerId));
    if (tokenEntities.isEmpty()) return;
    List<String> tokenList = new ArrayList<>();
    for (PushTokenEntity t : tokenEntities) {
      if (t.getToken() != null && !t.getToken().isBlank()) {
        tokenList.add(t.getToken());
      }
    }
    if (tokenList.isEmpty()) return;
    try {
      MulticastMessage msg = MulticastMessage.builder()
          .addAllTokens(tokenList)
          .setNotification(Notification.builder()
              .setTitle("Task accepted")
              .setBody("A Superheroo is on the way.")
              .build())
          .putData("type", "TASK_ACCEPTED")
          .putData("taskId", task.getId().toString())
          .build();
      messaging.sendEachForMulticast(msg);
    } catch (Exception e) {
      log.warn("Failed to send task accepted notification for task {}", task.getId(), e);
      throw new RuntimeException("Failed to send task accepted notification", e);
    }
  }

  public void notifyBuyerTaskCompleted(UUID buyerId, TaskEntity task) {
    if (messaging == null || buyerId == null) return;
    List<PushTokenEntity> tokenEntities = tokens.getTokensForUsers(List.of(buyerId));
    if (tokenEntities.isEmpty()) return;
    List<String> tokenList = new ArrayList<>();
    for (PushTokenEntity t : tokenEntities) {
      if (t.getToken() != null && !t.getToken().isBlank()) {
        tokenList.add(t.getToken());
      }
    }
    if (tokenList.isEmpty()) return;
    try {
      MulticastMessage msg = MulticastMessage.builder()
          .addAllTokens(tokenList)
          .setNotification(Notification.builder()
              .setTitle("Task completed")
              .setBody("Please rate your Superheroo.")
              .build())
          .putData("type", "TASK_COMPLETED")
          .putData("taskId", task.getId().toString())
          .build();
      messaging.sendEachForMulticast(msg);
    } catch (Exception e) {
      log.warn("Failed to send task completed notification for task {}", task.getId(), e);
      throw new RuntimeException("Failed to send task completed notification", e);
    }
  }

  public void notifyHelperKycApproved(UUID helperId) {
    if (messaging == null) return;
    List<PushTokenEntity> tokenEntities = tokens.getTokensForUsers(List.of(helperId));
    if (tokenEntities.isEmpty()) return;

    List<String> tokenList = new ArrayList<>();
    for (PushTokenEntity t : tokenEntities) {
      if (t.getToken() != null && !t.getToken().isBlank()) {
        tokenList.add(t.getToken());
      }
    }
    if (tokenList.isEmpty()) return;

    try {
      MulticastMessage msg = MulticastMessage.builder()
          .addAllTokens(tokenList)
          .setNotification(Notification.builder()
              .setTitle("KYC approved")
              .setBody("You are approved and can now go online.")
              .build())
          .putData("type", "KYC_APPROVED")
          .build();
      messaging.sendEachForMulticast(msg);
    } catch (Exception e) {
      log.warn("Failed to send KYC approved push notification for helper {}", helperId, e);
      throw new RuntimeException("Failed to send KYC approved notification", e);
    }
  }
}
