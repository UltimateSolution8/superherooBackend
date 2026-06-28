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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helpinminutes.api.batches.repo.BookingBatchItemRepository;
import com.helpinminutes.api.notifications.model.PushTokenEntity;
import com.helpinminutes.api.tasks.model.TaskEntity;
import com.helpinminutes.api.users.model.UserRole;
import com.helpinminutes.api.users.repo.UserRepository;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
  private final ObjectMapper mapper;
  private final FirebaseMessaging messaging;
  private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  public PushNotificationService(
      PushTokenService tokens,
      UserRepository users,
      BookingBatchItemRepository batchItems,
      StringRedisTemplate redis,
      ObjectMapper mapper,
      @Value("${FIREBASE_SERVICE_ACCOUNT_JSON:}") String serviceAccountJson,
      @Value("${FIREBASE_SERVICE_ACCOUNT_BASE64:}") String serviceAccountBase64,
      @Value("${FIREBASE_SERVICE_ACCOUNT_PATH:}") String serviceAccountPath) {
    this.tokens = tokens;
    this.users = users;
    this.batchItems = batchItems;
    this.redis = redis;
    this.mapper = mapper;
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
        log.warn("Push notifications disabled: missing FIREBASE_SERVICE_ACCOUNT_JSON or BASE64. Will use Expo push API as fallback.");
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
        Map<String, String> data = new HashMap<>();
        data.put("type", "TASK_OFFERED");
        data.put("taskId", task.getId().toString());
        data.put("title", task.getTitle() == null ? "Task" : task.getTitle());
        data.put("urgency", task.getUrgency().name());
        data.put("budgetPaise", String.valueOf(budgetPaise));
        data.put("amountText", amountText == null ? "" : amountText);
        data.put("lat", String.valueOf(task.getLat()));
        data.put("lng", String.valueOf(task.getLng()));
        if (bulkMeta != null) {
          data.put("bulkRequest", "true");
          data.put("batchId", bulkMeta.batchId().toString());
          data.put("helpersNeeded", String.valueOf(bulkMeta.totalCount()));
        }

        if (distMeters != null) {
          data.put("distanceMeters", String.valueOf(Math.round(distMeters)));
        }

        sendToTokens(task.getId(), helperId, tokenList, title, body, data);
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

  public void notifyTaskCreatedMonitor(TaskEntity task) {
    if (task == null) return;
    if (!isTaskCreateMonitorEnabled()) return;
    String phone = resolveTaskCreateMonitorPhone();
    if (phone == null) return;

    Optional<UUID> monitorUserId = users.findByPhone(phone).map(u -> u.getId());
    if (monitorUserId.isEmpty()) {
      log.info("Task create monitor skipped: no user found for phone {}", phone);
      return;
    }
    UUID userId = monitorUserId.get();
    List<PushTokenEntity> tokenEntities = tokens.getTokensForUsers(List.of(userId));
    if (tokenEntities.isEmpty()) {
      log.info("Task create monitor skipped: no push token for user {}", userId);
      return;
    }
    List<String> tokenList = new ArrayList<>();
    for (PushTokenEntity token : tokenEntities) {
      if (token.getToken() != null && !token.getToken().isBlank()) {
        tokenList.add(token.getToken());
      }
    }
    if (tokenList.isEmpty()) return;

    long budgetPaise = task.getBudgetPaise() == null ? 0L : Math.max(0L, task.getBudgetPaise());
    String amountText = formatAmountInr(budgetPaise);
    String bodyTitle = task.getTitle() == null || task.getTitle().isBlank() ? "Task" : task.getTitle();
    String body = amountText == null ? bodyTitle : bodyTitle + " • " + amountText;

    try {
      Map<String, String> data = new HashMap<>();
      data.put("type", "TASK_CREATED_MONITOR");
      data.put("taskId", task.getId().toString());
      data.put("title", bodyTitle);
      data.put("budgetPaise", String.valueOf(budgetPaise));
      data.put("lat", String.valueOf(task.getLat()));
      data.put("lng", String.valueOf(task.getLng()));
      sendToTokens(task.getId(), userId, tokenList, "New task created", body, data);
    } catch (Exception e) {
      log.warn("Failed task create monitor push for task {}", task.getId(), e);
    }
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

  private boolean isTaskCreateMonitorEnabled() {
    String value = System.getenv("TASK_CREATE_MONITOR_ENABLED");
    return value == null || value.isBlank() || !"false".equalsIgnoreCase(value.trim());
  }

  private String resolveTaskCreateMonitorPhone() {
    String configured = System.getenv("TASK_CREATE_MONITOR_PHONE");
    String fallback = "7842541414";
    String source = (configured == null || configured.isBlank()) ? fallback : configured.trim();
    String digits = source.replaceAll("\\D", "");
    if (digits.length() > 10) {
      digits = digits.substring(digits.length() - 10);
    }
    return digits.length() == 10 ? digits : null;
  }

  public void notifyBuyerTaskAccepted(UUID buyerId, TaskEntity task) {
    if (buyerId == null) return;
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
      sendToTokens(task.getId(), buyerId, tokenList, "Task accepted", "A Superheroo is on the way.",
          Map.of("type", "TASK_ACCEPTED", "taskId", task.getId().toString()));
    } catch (Exception e) {
      log.warn("Failed to send task accepted notification for task {}", task.getId(), e);
    }
  }

  public void notifyBuyerTaskCompleted(UUID buyerId, TaskEntity task) {
    if (buyerId == null) return;
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
      sendToTokens(task.getId(), buyerId, tokenList, "Task completed", "Please rate your Superheroo.",
          Map.of("type", "TASK_COMPLETED", "taskId", task.getId().toString()));
    } catch (Exception e) {
      log.warn("Failed to send task completed notification for task {}", task.getId(), e);
    }
  }

  /**
   * Send a push notification when a chat message is received.
   */
  public void notifyChatMessage(UUID targetUserId, UUID taskId, String senderName, String messagePreview) {
    if (targetUserId == null) return;
    List<PushTokenEntity> tokenEntities = tokens.getTokensForUsers(List.of(targetUserId));
    if (tokenEntities.isEmpty()) {
      log.info("Chat push skipped for user {} task {}: no push tokens", targetUserId, taskId);
      return;
    }
    List<String> tokenList = new ArrayList<>();
    for (PushTokenEntity t : tokenEntities) {
      if (t.getToken() != null && !t.getToken().isBlank()) {
        tokenList.add(t.getToken());
      }
    }
    if (tokenList.isEmpty()) return;
    String preview = messagePreview != null && messagePreview.length() > 100
        ? messagePreview.substring(0, 100) + "…" : messagePreview;
    String title = "New message from " + (senderName != null ? senderName : "Someone");
    try {
      Map<String, String> data = new HashMap<>();
      data.put("type", "CHAT_MESSAGE");
      if (taskId != null) data.put("taskId", taskId.toString());
      data.put("senderName", senderName != null ? senderName : "Someone");
      sendToTokens(taskId, targetUserId, tokenList, title, preview != null ? preview : "You have a new message", data);
    } catch (Exception e) {
      log.warn("Failed to send chat push notification to user {} task {}", targetUserId, taskId, e);
    }
  }

  /**
   * Health check for push notification system readiness.
   */
  public boolean isReady() {
    return messaging != null;
  }

  public void notifyHelperKycApproved(UUID helperId) {
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
      sendToTokens(null, helperId, tokenList, "KYC approved", "You are approved and can now go online.",
          Map.of("type", "KYC_APPROVED"));
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

  private void sendToTokens(UUID taskId, UUID userId, List<String> tokenList, String title, String body, Map<String, String> data) {
    if (tokenList == null || tokenList.isEmpty()) return;
    List<String> expoTokens = new ArrayList<>();
    List<String> fcmTokens = new ArrayList<>();
    for (String token : tokenList) {
      if (isExpoToken(token)) {
        expoTokens.add(token);
      } else {
        fcmTokens.add(token);
      }
    }
    if (!fcmTokens.isEmpty()) {
      if (messaging == null) {
        // Firebase is not initialized — route FCM tokens through Expo push API as fallback.
        // Expo can relay push notifications to FCM devices if the app uses expo-notifications.
        log.info("Firebase not initialized; routing {} FCM token(s) through Expo push API fallback for user {} task {}", fcmTokens.size(), userId, taskId);
        expoTokens.addAll(fcmTokens);
      } else {
        MulticastMessage.Builder builder = MulticastMessage.builder()
            .addAllTokens(fcmTokens)
            .setNotification(Notification.builder().setTitle(title).setBody(body).build());
        data.forEach(builder::putData);
        try {
          BatchResponse response = messaging.sendEachForMulticast(builder.build());
          log.info("FCM push sent user={} task={}: success={}, failure={}",
              userId, taskId, response.getSuccessCount(), response.getFailureCount());
          pruneInvalidTokens(taskId, userId, fcmTokens, response);
        } catch (Exception e) {
          log.warn("Failed FCM push user={} task={}; falling back to Expo push API", userId, taskId, e);
          // Fallback: try sending FCM tokens via Expo push API
          expoTokens.addAll(fcmTokens);
        }
      }
    }
    if (!expoTokens.isEmpty()) {
      sendExpoPush(userId, taskId, expoTokens, title, body, data);
    }
  }

  private boolean isExpoToken(String token) {
    return token != null && (token.startsWith("ExponentPushToken[") || token.startsWith("ExpoPushToken["));
  }

  private void sendExpoPush(UUID userId, UUID taskId, List<String> tokenList, String title, String body, Map<String, String> data) {
    try {
      List<Map<String, Object>> payload = tokenList.stream()
          .map(token -> {
            Map<String, Object> item = new HashMap<>();
            item.put("to", token);
            item.put("sound", "default");
            item.put("title", title);
            item.put("body", body);
            item.put("data", data);
            return item;
          })
          .toList();
      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create("https://exp.host/--/api/v2/push/send"))
          .timeout(Duration.ofSeconds(10))
          .header("Content-Type", "application/json")
          .header("Accept", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
          .build();
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        log.warn("Expo push failed user={} task={} status={} body={}", userId, taskId, response.statusCode(), response.body());
        return;
      }
      log.info("Expo push sent user={} task={} count={}", userId, taskId, tokenList.size());
    } catch (Exception e) {
      log.warn("Failed Expo push user={} task={}", userId, taskId, e);
    }
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

  /**
   * Admin notifications are low-priority. They run asynchronously on a
   * background thread with a small delay between sends so they never
   * block or starve higher-priority app notifications (task offers,
   * chat messages, task status changes).
   */
  public void sendAdminNotification(String role, List<UUID> userIds, String title, String body) {
    List<UUID> targetUserIds = new ArrayList<>();
    if (userIds != null && !userIds.isEmpty()) {
      targetUserIds.addAll(userIds);
    } else {
      if ("ALL".equalsIgnoreCase(role)) {
        List<com.helpinminutes.api.users.model.UserEntity> allHelpers = users.findTop200ByRoleOrderByCreatedAtDesc(UserRole.HELPER);
        List<com.helpinminutes.api.users.model.UserEntity> allBuyers = users.findTop200ByRoleOrderByCreatedAtDesc(UserRole.BUYER);
        for (com.helpinminutes.api.users.model.UserEntity u : allHelpers) targetUserIds.add(u.getId());
        for (com.helpinminutes.api.users.model.UserEntity u : allBuyers) targetUserIds.add(u.getId());
      } else if ("CITIZEN".equalsIgnoreCase(role) || "BUYER".equalsIgnoreCase(role)) {
        List<com.helpinminutes.api.users.model.UserEntity> allBuyers = users.findTop200ByRoleOrderByCreatedAtDesc(UserRole.BUYER);
        for (com.helpinminutes.api.users.model.UserEntity u : allBuyers) targetUserIds.add(u.getId());
      } else if ("PARTNER".equalsIgnoreCase(role) || "HELPER".equalsIgnoreCase(role)) {
        List<com.helpinminutes.api.users.model.UserEntity> allHelpers = users.findTop200ByRoleOrderByCreatedAtDesc(UserRole.HELPER);
        for (com.helpinminutes.api.users.model.UserEntity u : allHelpers) targetUserIds.add(u.getId());
      }
    }

    if (targetUserIds.isEmpty()) {
      log.warn("No target users found for admin notification role={} userIds={}", role, userIds);
      return;
    }

    List<PushTokenEntity> tokenEntities = tokens.getTokensForUsers(targetUserIds);
    if (tokenEntities.isEmpty()) {
      log.info("No active push tokens found for target users");
      return;
    }

    Map<UUID, List<String>> tokensByUser = new HashMap<>();
    for (PushTokenEntity t : tokenEntities) {
      if (t.getToken() == null || t.getToken().isBlank()) continue;
      tokensByUser.computeIfAbsent(t.getUserId(), k -> new ArrayList<>()).add(t.getToken());
    }

    int totalUsers = tokensByUser.size();
    log.info("Admin notification queued for {} users (async, low-priority)", totalUsers);

    // Run the actual sends on a background thread with rate-limiting.
    java.util.concurrent.CompletableFuture.runAsync(() -> {
      int sent = 0;
      for (Map.Entry<UUID, List<String>> entry : tokensByUser.entrySet()) {
        try {
          sendToTokens(null, entry.getKey(), entry.getValue(), title, body, Map.of("type", "ADMIN_BROADCAST"));
          sent++;
          // Throttle: 200ms between each user to avoid flooding push servers
          // and to yield CPU/network to higher-priority app notifications.
          if (sent < totalUsers) {
            Thread.sleep(200);
          }
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          log.warn("Admin notification sending interrupted after {}/{} users", sent, totalUsers);
          break;
        } catch (Exception e) {
          log.warn("Failed to send admin push notification to user {}", entry.getKey(), e);
        }
      }
      log.info("Admin notification completed: {}/{} users sent", sent, totalUsers);
    });
  }

  private record BulkMeta(UUID batchId, int totalCount) {
  }
}

