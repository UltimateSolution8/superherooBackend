package com.helpinminutes.api.notifications.service;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.Notification;
import com.google.firebase.messaging.SendResponse;
import com.helpinminutes.api.batches.repo.BookingBatchItemRepository;
import com.helpinminutes.api.notifications.model.PushTokenEntity;
import com.helpinminutes.api.tasks.model.TaskEntity;
import com.helpinminutes.api.users.model.UserRole;
import com.helpinminutes.api.users.repo.UserRepository;
import java.io.ByteArrayInputStream;
import java.time.Duration;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class PushNotificationService {
  private static final Logger log = LoggerFactory.getLogger(PushNotificationService.class);

  private final PushTokenService tokens;
  private final UserRepository users;
  private final BookingBatchItemRepository batchItems;
  private final StringRedisTemplate redis;
  private final FirebaseMessaging messaging;

  public PushNotificationService(
      PushTokenService tokens,
      UserRepository users,
      BookingBatchItemRepository batchItems,
      StringRedisTemplate redis,
      @Value("${FIREBASE_SERVICE_ACCOUNT_JSON:}") String serviceAccountJson,
      @Value("${FIREBASE_SERVICE_ACCOUNT_BASE64:}") String serviceAccountBase64,
      @Value("${FIREBASE_SERVICE_ACCOUNT_PATH:}") String serviceAccountPath) {
    this.tokens = tokens;
    this.users = users;
    this.batchItems = batchItems;
    this.redis = redis;
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
    if (messaging == null) {
      log.warn("Push skipped for task {} because Firebase messaging is not initialized", task != null ? task.getId() : null);
      return;
    }
    if (task == null || helperIds == null || helperIds.isEmpty()) return;

    List<PushTokenEntity> tokenEntities = tokens.getTokensForUsers(helperIds);
    if (tokenEntities.isEmpty()) {
      log.info("Push skipped for task {} because no push tokens were found for {} helper(s)", task.getId(), helperIds.size());
      return;
    }

    Map<UUID, List<String>> tokensByUser = new HashMap<>();
    for (PushTokenEntity t : tokenEntities) {
      if (t.getToken() == null || t.getToken().isBlank()) continue;
      tokensByUser.computeIfAbsent(t.getUserId(), k -> new ArrayList<>()).add(t.getToken());
    }

    BulkMeta bulkMeta = resolveBulkMeta(task.getId());
    for (UUID helperId : helperIds) {
      // Hard guard: task-created/offered notifications are for helpers only.
      if (users.findById(helperId).map(u -> u.getRole() != UserRole.HELPER).orElse(true)) {
        log.info("Skipping task {} push for non-helper user {}", task.getId(), helperId);
        continue;
      }
      List<String> tokenList = tokensByUser.get(helperId);
      if (tokenList == null || tokenList.isEmpty()) {
        log.info("Skipping task {} push for helper {} because no active token exists", task.getId(), helperId);
        continue;
      }
      if (bulkMeta != null && !shouldSendBulkNotification(helperId, bulkMeta.batchId())) {
        log.info("Skipping duplicate bulk push for helper {} batch {}", helperId, bulkMeta.batchId());
        continue;
      }

      Double distMeters = distanceByHelper == null ? null : distanceByHelper.get(helperId);
      String distanceText = formatDistanceMeters(distMeters);
      String title;
      if (bulkMeta != null) {
        title = distanceText == null ? "New bulk request nearby" : "Bulk request " + distanceText + " away";
      } else {
        title = distanceText == null ? "New task nearby" : "New task " + distanceText + " away";
      }
      long budgetPaise = task.getBudgetPaise() == null ? 0L : Math.max(0L, task.getBudgetPaise());
      String amountText = formatAmountInr(budgetPaise);
      String bodyTitle = task.getTitle() == null || task.getTitle().isBlank() ? "Task" : task.getTitle();
      String body = amountText == null ? bodyTitle : bodyTitle + " • " + amountText;
      if (bulkMeta != null && bulkMeta.totalCount() > 1) {
        body += " • " + bulkMeta.totalCount() + " helpers needed";
      }

      try {
        MulticastMessage.Builder builder = MulticastMessage.builder()
            .addAllTokens(tokenList)
            .setNotification(Notification.builder()
                .setTitle(title)
                .setBody(body)
                .build())
            .putData("type", "TASK_OFFERED")
            .putData("taskId", task.getId().toString())
            .putData("title", task.getTitle() == null ? "Task" : task.getTitle())
            .putData("urgency", task.getUrgency().name())
            .putData("budgetPaise", String.valueOf(budgetPaise))
            .putData("amountText", amountText == null ? "" : amountText)
            .putData("lat", String.valueOf(task.getLat()))
            .putData("lng", String.valueOf(task.getLng()));
        if (bulkMeta != null) {
          builder.putData("bulkRequest", "true");
          builder.putData("batchId", bulkMeta.batchId().toString());
          builder.putData("helpersNeeded", String.valueOf(bulkMeta.totalCount()));
        }

        if (distMeters != null) {
          builder.putData("distanceMeters", String.valueOf(Math.round(distMeters)));
        }

        BatchResponse response = messaging.sendEachForMulticast(builder.build());
        log.info("Push sent for task {} helper {}: success={}, failure={}",
            task.getId(), helperId, response.getSuccessCount(), response.getFailureCount());
        pruneInvalidTokens(task.getId(), helperId, tokenList, response);
      } catch (Exception e) {
        log.warn("Failed to send push notifications for task {} to helper {}", task.getId(), helperId, e);
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
    notifyTaskCreated(helperIds, task, null);
  }

  public void notifyTaskCreated(List<UUID> helperIds, TaskEntity task, Map<UUID, Double> distanceByHelper) {
    // reuse the same implementation as offered, helpers will see a notification
    // that looks identical to an offer (title/body) and the app treats it the
    // same way (refresh available tasks).
    notifyTaskOffered(helperIds, task, distanceByHelper);
  }

  private String formatDistanceMeters(Double meters) {
    if (meters == null || !Double.isFinite(meters) || meters <= 0) return null;
    if (meters < 1000) {
      return Math.round(meters) + " meters";
    }
    return String.format("%.1f km", meters / 1000.0);
  }

  private String formatAmountInr(long paise) {
    if (paise <= 0) return null;
    long rupees = Math.round(paise / 100.0);
    return "₹" + rupees;
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
      BatchResponse response = messaging.sendEachForMulticast(msg);
      pruneInvalidTokens(task.getId(), buyerId, tokenList, response);
    } catch (Exception e) {
      log.warn("Failed to send task accepted notification for task {}", task.getId(), e);
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
      BatchResponse response = messaging.sendEachForMulticast(msg);
      pruneInvalidTokens(task.getId(), buyerId, tokenList, response);
    } catch (Exception e) {
      log.warn("Failed to send task completed notification for task {}", task.getId(), e);
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
      BatchResponse response = messaging.sendEachForMulticast(msg);
      pruneInvalidTokens(null, helperId, tokenList, response);
    } catch (Exception e) {
      log.warn("Failed to send KYC approved push notification for helper {}", helperId, e);
    }
  }

  private void pruneInvalidTokens(UUID taskId, UUID userId, List<String> tokenList, BatchResponse response) {
    if (response == null || tokenList == null || tokenList.isEmpty()) return;
    List<String> invalidTokens = new ArrayList<>();
    List<SendResponse> sendResponses = response.getResponses();
    for (int i = 0; i < sendResponses.size() && i < tokenList.size(); i++) {
      SendResponse sendResponse = sendResponses.get(i);
      if (sendResponse.isSuccessful()) continue;
      FirebaseMessagingException ex = sendResponse.getException();
      if (isPermanentTokenError(ex)) {
        invalidTokens.add(tokenList.get(i));
      }
    }
    if (invalidTokens.isEmpty()) return;
    long deleted = tokens.removeTokens(invalidTokens);
    log.info("Pruned {} invalid push token(s) for user {} task {}", deleted, userId, taskId);
  }

  private boolean isPermanentTokenError(FirebaseMessagingException ex) {
    if (ex == null) return false;
    MessagingErrorCode code = ex.getMessagingErrorCode();
    if (code == null) return false;
    return code == MessagingErrorCode.UNREGISTERED
        || code == MessagingErrorCode.INVALID_ARGUMENT
        || code == MessagingErrorCode.SENDER_ID_MISMATCH;
  }

  private BulkMeta resolveBulkMeta(UUID taskId) {
    if (taskId == null) return null;
    try {
      var itemOpt = batchItems.findByTaskId(taskId);
      if (itemOpt.isEmpty()) return null;
      UUID batchId = itemOpt.get().getBatchId();
      long total = batchItems.countByBatchId(batchId);
      if (total <= 1) return null;
      return new BulkMeta(batchId, (int) Math.min(total, Integer.MAX_VALUE));
    } catch (Exception e) {
      log.debug("Unable to resolve batch metadata for task {}", taskId, e);
      return null;
    }
  }

  private boolean shouldSendBulkNotification(UUID helperId, UUID batchId) {
    if (helperId == null || batchId == null) return true;
    try {
      String key = "push:bulk-task:" + batchId + ":helper:" + helperId;
      Boolean ok = redis.opsForValue().setIfAbsent(key, "1", Duration.ofMinutes(3));
      return Boolean.TRUE.equals(ok);
    } catch (Exception e) {
      // Never fail notification flow because dedupe cache failed.
      return true;
    }
  }

  private record BulkMeta(UUID batchId, int totalCount) {
  }
}
