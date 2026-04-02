package com.helpinminutes.api.learn.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helpinminutes.api.errors.BadRequestException;
import com.helpinminutes.api.errors.NotFoundException;
import com.helpinminutes.api.learn.dto.AdminUpsertAssessmentRequest;
import com.helpinminutes.api.learn.dto.AdminUpsertTrainingMaterialRequest;
import com.helpinminutes.api.learn.dto.HelperAssessmentAttemptResponse;
import com.helpinminutes.api.learn.dto.HelperAssessmentStartResponse;
import com.helpinminutes.api.learn.dto.HelperAssessmentSubmitRequest;
import com.helpinminutes.api.learn.dto.HelperTrainingProgressRequest;
import com.helpinminutes.api.learn.dto.HelperTrainingProgressResponse;
import com.helpinminutes.api.learn.dto.LearningAssessmentResponse;
import com.helpinminutes.api.learn.dto.TrainingMaterialResponse;
import com.helpinminutes.api.learn.model.HelperAssessmentAttemptEntity;
import com.helpinminutes.api.learn.model.HelperAssessmentAttemptStatus;
import com.helpinminutes.api.learn.model.HelperTrainingProgressEntity;
import com.helpinminutes.api.learn.model.HelperTrainingProgressStatus;
import com.helpinminutes.api.learn.model.LearningAssessmentEntity;
import com.helpinminutes.api.learn.model.TrainingMaterialEntity;
import com.helpinminutes.api.learn.model.TrainingMaterialType;
import com.helpinminutes.api.learn.repo.HelperAssessmentAttemptRepository;
import com.helpinminutes.api.learn.repo.HelperTrainingProgressRepository;
import com.helpinminutes.api.learn.repo.LearningAssessmentRepository;
import com.helpinminutes.api.learn.repo.TrainingMaterialRepository;
import com.helpinminutes.api.users.model.UserEntity;
import com.helpinminutes.api.users.repo.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LearningService {
  private final TrainingMaterialRepository materials;
  private final HelperTrainingProgressRepository progress;
  private final LearningAssessmentRepository assessments;
  private final HelperAssessmentAttemptRepository attempts;
  private final UserRepository users;
  private final LearningMapper mapper;
  private final ObjectMapper objectMapper;

  public LearningService(
      TrainingMaterialRepository materials,
      HelperTrainingProgressRepository progress,
      LearningAssessmentRepository assessments,
      HelperAssessmentAttemptRepository attempts,
      UserRepository users,
      LearningMapper mapper,
      ObjectMapper objectMapper) {
    this.materials = materials;
    this.progress = progress;
    this.assessments = assessments;
    this.attempts = attempts;
    this.users = users;
    this.mapper = mapper;
    this.objectMapper = objectMapper;
  }

  @Transactional(readOnly = true)
  public List<TrainingMaterialResponse> listAdminMaterials() {
    List<TrainingMaterialEntity> rows = materials.findAllByOrderByCreatedAtDesc();
    List<HelperTrainingProgressEntity> allProgress = progress.findAllByOrderByUpdatedAtDesc();
    Map<UUID, Integer> totalByMaterial = new HashMap<>();
    Map<UUID, Integer> completedByMaterial = new HashMap<>();
    for (HelperTrainingProgressEntity p : allProgress) {
      totalByMaterial.merge(p.getMaterialId(), 1, Integer::sum);
      if (p.getStatus() == HelperTrainingProgressStatus.COMPLETED) {
        completedByMaterial.merge(p.getMaterialId(), 1, Integer::sum);
      }
    }
    return rows.stream()
        .map(m -> mapper.toMaterialResponse(
            m,
            null,
            totalByMaterial.getOrDefault(m.getId(), 0),
            completedByMaterial.getOrDefault(m.getId(), 0)))
        .toList();
  }

  @Transactional(readOnly = true)
  public List<TrainingMaterialResponse> listHelperMaterials(UUID helperId) {
    List<TrainingMaterialEntity> rows = materials.findByActiveTrueOrderByCreatedAtDesc();
    Map<UUID, HelperTrainingProgressEntity> progressByMaterial = progress.findByHelperIdOrderByUpdatedAtDesc(helperId)
        .stream()
        .collect(Collectors.toMap(
            HelperTrainingProgressEntity::getMaterialId,
            p -> p,
            (a, b) -> a));
    return rows.stream()
        .map(m -> mapper.toMaterialResponse(m, progressByMaterial.get(m.getId()), null, null))
        .toList();
  }

  @Transactional
  public TrainingMaterialResponse createMaterial(UUID adminUserId, AdminUpsertTrainingMaterialRequest req) {
    TrainingMaterialEntity material = new TrainingMaterialEntity();
    material.setTitle(req.title().trim());
    material.setDescription(trimToNull(req.description()));
    material.setContentType(parseMaterialType(req.contentType()));
    material.setResourceUrl(req.resourceUrl().trim());
    material.setThumbnailUrl(trimToNull(req.thumbnailUrl()));
    material.setDurationSeconds(req.durationSeconds());
    material.setActive(req.active() == null || req.active());
    material.setCreatedBy(adminUserId);
    TrainingMaterialEntity saved = materials.save(material);
    return mapper.toMaterialResponse(saved, null, 0, 0);
  }

  @Transactional
  public TrainingMaterialResponse updateMaterial(UUID materialId, AdminUpsertTrainingMaterialRequest req) {
    TrainingMaterialEntity material = materials.findById(materialId)
        .orElseThrow(() -> new NotFoundException("Training material not found"));
    material.setTitle(req.title().trim());
    material.setDescription(trimToNull(req.description()));
    material.setContentType(parseMaterialType(req.contentType()));
    material.setResourceUrl(req.resourceUrl().trim());
    material.setThumbnailUrl(trimToNull(req.thumbnailUrl()));
    material.setDurationSeconds(req.durationSeconds());
    if (req.active() != null) material.setActive(req.active());
    TrainingMaterialEntity saved = materials.save(material);
    int total = progress.findByMaterialIdOrderByUpdatedAtDesc(materialId).size();
    int completed = (int) progress.findByMaterialIdOrderByUpdatedAtDesc(materialId).stream()
        .filter(p -> p.getStatus() == HelperTrainingProgressStatus.COMPLETED)
        .count();
    return mapper.toMaterialResponse(saved, null, total, completed);
  }

  @Transactional(readOnly = true)
  public List<HelperTrainingProgressResponse> listAdminProgress(UUID materialId, UUID helperId) {
    List<HelperTrainingProgressEntity> rows;
    if (materialId != null) {
      rows = progress.findByMaterialIdOrderByUpdatedAtDesc(materialId);
    } else if (helperId != null) {
      rows = progress.findByHelperIdOrderByUpdatedAtDesc(helperId);
    } else {
      rows = progress.findAllByOrderByUpdatedAtDesc();
    }
    if (materialId != null && helperId != null) {
      rows = rows.stream()
          .filter(p -> Objects.equals(p.getMaterialId(), materialId) && Objects.equals(p.getHelperId(), helperId))
          .toList();
    }
    Map<UUID, String> materialTitles = materials.findAllById(rows.stream().map(HelperTrainingProgressEntity::getMaterialId).toList())
        .stream()
        .collect(Collectors.toMap(TrainingMaterialEntity::getId, TrainingMaterialEntity::getTitle, (a, b) -> a));
    Map<UUID, String> helperNames = userDisplayNames(rows.stream().map(HelperTrainingProgressEntity::getHelperId).toList());
    return rows.stream()
        .map(p -> mapper.toProgressResponse(
            p,
            materialTitles.getOrDefault(p.getMaterialId(), "-"),
            helperNames.getOrDefault(p.getHelperId(), p.getHelperId().toString())))
        .toList();
  }

  @Transactional(readOnly = true)
  public List<HelperTrainingProgressResponse> listHelperProgress(UUID helperId) {
    List<HelperTrainingProgressEntity> rows = progress.findByHelperIdOrderByUpdatedAtDesc(helperId);
    Map<UUID, String> materialTitles = materials.findAllById(rows.stream().map(HelperTrainingProgressEntity::getMaterialId).toList())
        .stream()
        .collect(Collectors.toMap(TrainingMaterialEntity::getId, TrainingMaterialEntity::getTitle, (a, b) -> a));
    String helperName = users.findById(helperId)
        .map(this::displayNameOrPhone)
        .orElse(helperId.toString());
    return rows.stream()
        .map(p -> mapper.toProgressResponse(
            p,
            materialTitles.getOrDefault(p.getMaterialId(), "-"),
            helperName))
        .toList();
  }

  @Transactional
  public HelperTrainingProgressResponse upsertHelperProgress(UUID helperId, UUID materialId, HelperTrainingProgressRequest req) {
    TrainingMaterialEntity material = materials.findById(materialId)
        .orElseThrow(() -> new NotFoundException("Training material not found"));
    if (!material.isActive()) {
      throw new BadRequestException("Training material is not active");
    }
    HelperTrainingProgressEntity row = progress.findByMaterialIdAndHelperId(materialId, helperId)
        .orElseGet(() -> {
          HelperTrainingProgressEntity n = new HelperTrainingProgressEntity();
          n.setMaterialId(materialId);
          n.setHelperId(helperId);
          return n;
        });

    int nextProgress = req.progressPercent() == null ? row.getProgressPercent() : clamp(req.progressPercent(), 0, 100);
    int nextViewedSeconds = req.viewedSeconds() == null
        ? row.getViewedSeconds()
        : Math.max(row.getViewedSeconds(), Math.max(0, req.viewedSeconds()));
    boolean completed = Boolean.TRUE.equals(req.completed()) || nextProgress >= 95;

    row.setProgressPercent(completed ? 100 : nextProgress);
    row.setViewedSeconds(nextViewedSeconds);
    row.setLastAccessedAt(Instant.now());
    if (completed) {
      row.setStatus(HelperTrainingProgressStatus.COMPLETED);
      if (row.getCompletedAt() == null) {
        row.setCompletedAt(Instant.now());
      }
    } else if (row.getProgressPercent() > 0 || row.getViewedSeconds() > 0) {
      row.setStatus(HelperTrainingProgressStatus.IN_PROGRESS);
    } else {
      row.setStatus(HelperTrainingProgressStatus.NOT_STARTED);
    }

    HelperTrainingProgressEntity saved = progress.save(row);
    String helperName = users.findById(helperId).map(this::displayNameOrPhone).orElse(helperId.toString());
    return mapper.toProgressResponse(saved, material.getTitle(), helperName);
  }

  @Transactional(readOnly = true)
  public List<LearningAssessmentResponse> listAdminAssessments() {
    return assessments.findAllByOrderByCreatedAtDesc().stream()
        .map(mapper::toAssessmentResponse)
        .toList();
  }

  @Transactional(readOnly = true)
  public List<LearningAssessmentResponse> listHelperAssessments() {
    return assessments.findByActiveTrueOrderByCreatedAtDesc().stream()
        .map(mapper::toAssessmentResponse)
        .toList();
  }

  @Transactional
  public LearningAssessmentResponse createAssessment(UUID adminUserId, AdminUpsertAssessmentRequest req) {
    validateQuestionSchema(req.questionSchema());
    LearningAssessmentEntity entity = new LearningAssessmentEntity();
    entity.setTitle(req.title().trim());
    entity.setDescription(trimToNull(req.description()));
    entity.setInstructions(trimToNull(req.instructions()));
    entity.setMaxAttempts(req.maxAttempts());
    entity.setTimeLimitMinutes(req.timeLimitMinutes());
    entity.setPassPercentage(req.passPercentage());
    entity.setQuestionSchema(mapper.writeJson(req.questionSchema()));
    entity.setActive(req.active() == null || req.active());
    entity.setCreatedBy(adminUserId);
    return mapper.toAssessmentResponse(assessments.save(entity));
  }

  @Transactional
  public LearningAssessmentResponse updateAssessment(UUID assessmentId, AdminUpsertAssessmentRequest req) {
    validateQuestionSchema(req.questionSchema());
    LearningAssessmentEntity entity = assessments.findById(assessmentId)
        .orElseThrow(() -> new NotFoundException("Assessment not found"));
    entity.setTitle(req.title().trim());
    entity.setDescription(trimToNull(req.description()));
    entity.setInstructions(trimToNull(req.instructions()));
    entity.setMaxAttempts(req.maxAttempts());
    entity.setTimeLimitMinutes(req.timeLimitMinutes());
    entity.setPassPercentage(req.passPercentage());
    entity.setQuestionSchema(mapper.writeJson(req.questionSchema()));
    if (req.active() != null) entity.setActive(req.active());
    return mapper.toAssessmentResponse(assessments.save(entity));
  }

  @Transactional(readOnly = true)
  public List<HelperAssessmentAttemptResponse> listAdminAssessmentAttempts(UUID assessmentId) {
    LearningAssessmentEntity assessment = assessments.findById(assessmentId)
        .orElseThrow(() -> new NotFoundException("Assessment not found"));
    List<HelperAssessmentAttemptEntity> rows = attempts.findByAssessmentIdOrderByCreatedAtDesc(assessmentId);
    Map<UUID, String> helperNames = userDisplayNames(rows.stream().map(HelperAssessmentAttemptEntity::getHelperId).toList());
    return rows.stream()
        .map(a -> mapper.toAttemptResponse(
            a,
            assessment.getTitle(),
            helperNames.getOrDefault(a.getHelperId(), a.getHelperId().toString())))
        .toList();
  }

  @Transactional(readOnly = true)
  public List<HelperAssessmentAttemptResponse> listHelperAssessmentAttempts(UUID helperId, UUID assessmentId) {
    LearningAssessmentEntity assessment = assessments.findById(assessmentId)
        .orElseThrow(() -> new NotFoundException("Assessment not found"));
    List<HelperAssessmentAttemptEntity> rows = attempts.findByHelperIdAndAssessmentIdOrderByAttemptNoDesc(helperId, assessmentId);
    String helperName = users.findById(helperId).map(this::displayNameOrPhone).orElse(helperId.toString());
    return rows.stream()
        .map(a -> mapper.toAttemptResponse(a, assessment.getTitle(), helperName))
        .toList();
  }

  @Transactional
  public HelperAssessmentStartResponse startAssessment(UUID helperId, UUID assessmentId) {
    LearningAssessmentEntity assessment = assessments.findById(assessmentId)
        .orElseThrow(() -> new NotFoundException("Assessment not found"));
    if (!assessment.isActive()) {
      throw new BadRequestException("Assessment is not active");
    }

    List<HelperAssessmentAttemptEntity> prev = attempts.findByHelperIdAndAssessmentIdOrderByAttemptNoDesc(helperId, assessmentId);
    HelperAssessmentAttemptEntity current = prev.stream()
        .filter(a -> a.getStatus() == HelperAssessmentAttemptStatus.IN_PROGRESS)
        .findFirst()
        .orElse(null);

    if (current != null) {
      if (isTimeLimitExceeded(assessment, current.getStartedAt())) {
        timeoutAttempt(current, assessment.getTimeLimitMinutes());
      } else {
        return new HelperAssessmentStartResponse(
            current.getId(),
            assessmentId,
            current.getAttemptNo(),
            assessment.getMaxAttempts(),
            assessment.getTimeLimitMinutes(),
            current.getStartedAt());
      }
    }

    int maxNo = prev.stream().mapToInt(HelperAssessmentAttemptEntity::getAttemptNo).max().orElse(0);
    if (maxNo >= assessment.getMaxAttempts()) {
      throw new BadRequestException("Maximum attempts reached for this assessment");
    }

    HelperAssessmentAttemptEntity next = new HelperAssessmentAttemptEntity();
    next.setAssessmentId(assessmentId);
    next.setHelperId(helperId);
    next.setAttemptNo(maxNo + 1);
    next.setStatus(HelperAssessmentAttemptStatus.IN_PROGRESS);
    next.setStartedAt(Instant.now());
    HelperAssessmentAttemptEntity saved = attempts.save(next);
    return new HelperAssessmentStartResponse(
        saved.getId(),
        assessmentId,
        saved.getAttemptNo(),
        assessment.getMaxAttempts(),
        assessment.getTimeLimitMinutes(),
        saved.getStartedAt());
  }

  @Transactional
  public HelperAssessmentAttemptResponse submitAssessment(
      UUID helperId,
      UUID assessmentId,
      HelperAssessmentSubmitRequest req) {
    LearningAssessmentEntity assessment = assessments.findById(assessmentId)
        .orElseThrow(() -> new NotFoundException("Assessment not found"));
    HelperAssessmentAttemptEntity attempt = attempts.findByIdAndHelperId(req.attemptId(), helperId)
        .orElseThrow(() -> new NotFoundException("Attempt not found"));

    if (!Objects.equals(attempt.getAssessmentId(), assessmentId)) {
      throw new BadRequestException("Attempt does not belong to this assessment");
    }
    if (attempt.getStatus() != HelperAssessmentAttemptStatus.IN_PROGRESS) {
      throw new BadRequestException("Attempt is already submitted");
    }
    if (isTimeLimitExceeded(assessment, attempt.getStartedAt())) {
      timeoutAttempt(attempt, assessment.getTimeLimitMinutes());
      throw new BadRequestException("Assessment time limit exceeded. Start a new attempt.");
    }
    if (req.answers() == null || !req.answers().isObject()) {
      throw new BadRequestException("Answers payload must be an object");
    }

    EvaluationResult eval = evaluate(assessment.getQuestionSchema(), req.answers());
    attempt.setAnswersJson(writeJsonSafe(req.answers()));
    attempt.setScorePercentage(eval.scorePercentage());
    attempt.setCorrectCount(eval.correctCount());
    attempt.setTotalCount(eval.totalCount());
    attempt.setSubmittedAt(Instant.now());
    attempt.setDurationSeconds((int) Duration.between(attempt.getStartedAt(), attempt.getSubmittedAt()).toSeconds());
    attempt.setStatus(eval.scorePercentage() >= assessment.getPassPercentage()
        ? HelperAssessmentAttemptStatus.PASSED
        : HelperAssessmentAttemptStatus.FAILED);

    HelperAssessmentAttemptEntity saved = attempts.save(attempt);
    String helperName = users.findById(helperId).map(this::displayNameOrPhone).orElse(helperId.toString());
    return mapper.toAttemptResponse(saved, assessment.getTitle(), helperName);
  }

  private void timeoutAttempt(HelperAssessmentAttemptEntity attempt, Integer timeLimitMinutes) {
    attempt.setStatus(HelperAssessmentAttemptStatus.TIMED_OUT);
    Instant now = Instant.now();
    attempt.setSubmittedAt(now);
    if (attempt.getStartedAt() != null) {
      attempt.setDurationSeconds((int) Duration.between(attempt.getStartedAt(), now).toSeconds());
    } else if (timeLimitMinutes != null) {
      attempt.setDurationSeconds(timeLimitMinutes * 60);
    }
    attempts.save(attempt);
  }

  private boolean isTimeLimitExceeded(LearningAssessmentEntity assessment, Instant startedAt) {
    if (assessment.getTimeLimitMinutes() == null || startedAt == null) return false;
    Instant deadline = startedAt.plus(Duration.ofMinutes(assessment.getTimeLimitMinutes()));
    return Instant.now().isAfter(deadline);
  }

  private EvaluationResult evaluate(String schemaRaw, JsonNode answers) {
    JsonNode schema = mapper.parseJson(schemaRaw);
    if (!schema.isArray()) {
      return new EvaluationResult(0, 0, 0);
    }
    int scorePoints = 0;
    int totalPoints = 0;
    int correctQuestions = 0;
    int totalQuestions = 0;

    for (JsonNode q : schema) {
      if (q == null || q.isNull() || !q.isObject()) continue;
      JsonNode correct = q.get("correctAnswer");
      if (correct == null || correct.isNull()) continue; // informational question

      totalQuestions += 1;
      int points = q.has("points") && q.get("points").canConvertToInt()
          ? Math.max(0, q.get("points").asInt())
          : 1;
      totalPoints += points;

      String id = safeText(q.get("id"));
      if (id == null) continue;
      JsonNode answer = answers.get(id);
      if (answer == null || answer.isNull()) continue;

      String type = safeText(q.get("type"));
      if (isAnswerCorrect(type, answer, correct)) {
        correctQuestions += 1;
        scorePoints += points;
      }
    }

    int scorePct = totalPoints <= 0 ? 0 : (int) Math.round((scorePoints * 100.0) / totalPoints);
    return new EvaluationResult(scorePct, correctQuestions, totalQuestions);
  }

  private boolean isAnswerCorrect(String typeRaw, JsonNode answer, JsonNode correct) {
    String type = typeRaw == null ? "" : typeRaw.trim().toLowerCase(Locale.ROOT);
    return switch (type) {
      case "multiple_choice", "multiselect", "multi", "checkbox" -> toSet(answer).equals(toSet(correct));
      case "number", "numeric" -> {
        Double a = toDouble(answer);
        Double c = toDouble(correct);
        yield a != null && c != null && Math.abs(a - c) < 0.00001;
      }
      case "boolean", "bool", "yes_no" -> {
        Boolean a = toBoolean(answer);
        Boolean c = toBoolean(correct);
        yield a != null && c != null && a.equals(c);
      }
      case "text", "short_text", "long_text" -> normalizeText(answer).equals(normalizeText(correct));
      default -> normalizeText(answer).equals(normalizeText(correct));
    };
  }

  private Set<String> toSet(JsonNode node) {
    Set<String> out = new HashSet<>();
    if (node == null || node.isNull()) return out;
    if (node.isArray()) {
      node.forEach(n -> out.add(normalizeText(n)));
      out.remove("");
      return out;
    }
    String one = normalizeText(node);
    if (!one.isEmpty()) out.add(one);
    return out;
  }

  private Double toDouble(JsonNode node) {
    if (node == null || node.isNull()) return null;
    if (node.isNumber()) return node.asDouble();
    try {
      return Double.parseDouble(node.asText());
    } catch (Exception e) {
      return null;
    }
  }

  private Boolean toBoolean(JsonNode node) {
    if (node == null || node.isNull()) return null;
    if (node.isBoolean()) return node.asBoolean();
    String t = normalizeText(node);
    if ("true".equals(t) || "yes".equals(t) || "1".equals(t)) return true;
    if ("false".equals(t) || "no".equals(t) || "0".equals(t)) return false;
    return null;
  }

  private String normalizeText(JsonNode node) {
    if (node == null || node.isNull()) return "";
    return node.asText("").trim().toLowerCase(Locale.ROOT);
  }

  private String safeText(JsonNode node) {
    String t = node == null ? null : node.asText(null);
    if (t == null || t.isBlank()) return null;
    return t.trim();
  }

  private String writeJsonSafe(JsonNode value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception e) {
      return "{}";
    }
  }

  private void validateQuestionSchema(JsonNode schema) {
    if (schema == null || !schema.isArray()) {
      throw new BadRequestException("questionSchema must be an array");
    }
    if (schema.size() == 0) {
      throw new BadRequestException("questionSchema must contain at least one question");
    }
    if (schema.size() > 200) {
      throw new BadRequestException("questionSchema cannot exceed 200 questions");
    }

    Set<String> ids = new HashSet<>();
    for (JsonNode q : schema) {
      if (q == null || !q.isObject()) {
        throw new BadRequestException("Each question must be an object");
      }
      String id = safeText(q.get("id"));
      String label = safeText(q.get("label"));
      if (id == null) {
        throw new BadRequestException("Each question must have id");
      }
      if (!ids.add(id)) {
        throw new BadRequestException("Duplicate question id: " + id);
      }
      if (label == null) {
        throw new BadRequestException("Each question must have label");
      }
    }
  }

  private TrainingMaterialType parseMaterialType(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new BadRequestException("contentType is required");
    }
    try {
      return TrainingMaterialType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (Exception e) {
      throw new BadRequestException("Invalid contentType. Use VIDEO, PDF, AUDIO, or LINK.");
    }
  }

  private Map<UUID, String> userDisplayNames(List<UUID> ids) {
    if (ids == null || ids.isEmpty()) return Map.of();
    List<UserEntity> rows = users.findAllById(ids.stream().distinct().toList());
    Map<UUID, String> out = new HashMap<>();
    for (UserEntity row : rows) {
      out.put(row.getId(), displayNameOrPhone(row));
    }
    return out;
  }

  private String displayNameOrPhone(UserEntity user) {
    if (user == null) return "-";
    if (user.getDisplayName() != null && !user.getDisplayName().isBlank()) {
      return user.getDisplayName().trim();
    }
    if (user.getPhone() != null && !user.getPhone().isBlank()) {
      return user.getPhone();
    }
    if (user.getEmail() != null && !user.getEmail().isBlank()) {
      return user.getEmail();
    }
    return user.getId().toString();
  }

  private static int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }

  private String trimToNull(String value) {
    if (value == null) return null;
    String t = value.trim();
    return t.isEmpty() ? null : t;
  }

  private record EvaluationResult(int scorePercentage, int correctCount, int totalCount) {
  }
}
