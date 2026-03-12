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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    notifyTaskOffered(helperIds, task, null);
  }

  public void notifyTaskOffered(List<UUID> helperIds, TaskEntity task, Map<UUID, Double> distanceByHelper) {
    if (messaging == null) return;
    if (helperIds == null || helperIds.isEmpty()) return;
    List<PushTokenEntity> tokenEntities = tokens.getTokensForUsers(helperIds);
    if (tokenEntities.isEmpty()) return;

    Map<UUID, List<String>> tokensByUser = new HashMap<>();
    for (PushTokenEntity t : tokenEntities) {
      if (t.getToken() == null || t.getToken().isBlank()) continue;
      tokensByUser.computeIfAbsent(t.getUserId(), k -> new ArrayList<>()).add(t.getToken());
    }

    for (UUID helperId : helperIds) {
      List<String> tokenList = tokensByUser.get(helperId);
      if (tokenList == null || tokenList.isEmpty()) continue;

      Double distMeters = distanceByHelper == null ? null : distanceByHelper.get(helperId);
      String distanceText = formatDistanceMeters(distMeters);
      String title = distanceText == null ? "New task nearby" : "New task " + distanceText + " away";

      try {
        MulticastMessage.Builder builder = MulticastMessage.builder()
            .addAllTokens(tokenList)
            .setNotification(Notification.builder()
                .setTitle(title)
                .setBody(task.getTitle() == null ? "Tap to view details" : task.getTitle())
                .build())
            .putData("type", "TASK_OFFERED")
            .putData("taskId", task.getId().toString())
            .putData("title", task.getTitle() == null ? "Task" : task.getTitle())
            .putData("urgency", task.getUrgency().name())
            .putData("lat", String.valueOf(task.getLat()))
            .putData("lng", String.valueOf(task.getLng()));

        if (distMeters != null) {
          builder.putData("distanceMeters", String.valueOf(Math.round(distMeters)));
        }

        messaging.sendEachForMulticast(builder.build());
      } catch (Exception e) {
        log.warn("Failed to send push notifications for task {} to helper {}", task.getId(), helperId, e);
        throw new RuntimeException("Failed to send task offered notification", e);
      }
    }
  }

  /**
   * When a task is created we also want to nudge helpers such that they refresh
   * even if we haven't explicitly offered to them yet.  This method simply
   * re‑uses the task offered logic because it already handles token filtering and
   * formatting.
   */
  public void notifyTaskCreated(List<UUID> helperIds, TaskEntity task) {
    // reuse the same implementation as offered, helpers will see a notification
    // that looks identical to an offer (title/body) and the app treats it the
    // same way (refresh available tasks).
    notifyTaskOffered(helperIds, task);
  }

  private String formatDistanceMeters(Double meters) {
    if (meters == null || !Double.isFinite(meters) || meters <= 0) return null;
    if (meters < 1000) {
      return Math.round(meters) + " meters";
    }
    return String.format("%.1f km", meters / 1000.0);
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
