package com.helpinminutes.api.learn.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helpinminutes.api.learn.dto.HelperAssessmentAttemptResponse;
import com.helpinminutes.api.learn.dto.HelperTrainingProgressResponse;
import com.helpinminutes.api.learn.dto.LearningAssessmentResponse;
import com.helpinminutes.api.learn.dto.TrainingMaterialResponse;
import com.helpinminutes.api.learn.model.HelperAssessmentAttemptEntity;
import com.helpinminutes.api.learn.model.HelperTrainingProgressEntity;
import com.helpinminutes.api.learn.model.LearningAssessmentEntity;
import com.helpinminutes.api.learn.model.TrainingMaterialEntity;
import org.springframework.stereotype.Component;

@Component
public class LearningMapper {
  private final ObjectMapper objectMapper;

  public LearningMapper(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  JsonNode parseJson(String raw) {
    if (raw == null || raw.isBlank()) return objectMapper.createArrayNode();
    try {
      return objectMapper.readTree(raw);
    } catch (Exception e) {
      return objectMapper.createArrayNode();
    }
  }

  String writeJson(JsonNode node) {
    try {
      return objectMapper.writeValueAsString(node == null ? objectMapper.createArrayNode() : node);
    } catch (Exception e) {
      return "[]";
    }
  }

  TrainingMaterialResponse toMaterialResponse(
      TrainingMaterialEntity material,
      HelperTrainingProgressEntity helperProgress,
      Integer totalLearners,
      Integer completedLearners) {
    return new TrainingMaterialResponse(
        material.getId(),
        material.getTitle(),
        material.getDescription(),
        material.getContentType().name(),
        material.getResourceUrl(),
        material.getThumbnailUrl(),
        material.getDurationSeconds(),
        material.isActive(),
        material.getCreatedAt(),
        material.getUpdatedAt(),
        helperProgress == null ? null : helperProgress.getProgressPercent(),
        helperProgress == null ? null : helperProgress.getStatus().name(),
        totalLearners,
        completedLearners);
  }

  HelperTrainingProgressResponse toProgressResponse(
      HelperTrainingProgressEntity progress,
      String materialTitle,
      String helperName) {
    return new HelperTrainingProgressResponse(
        progress.getId(),
        progress.getMaterialId(),
        materialTitle,
        progress.getHelperId(),
        helperName,
        progress.getStatus().name(),
        progress.getProgressPercent(),
        progress.getViewedSeconds(),
        progress.getLastAccessedAt(),
        progress.getCompletedAt(),
        progress.getUpdatedAt());
  }

  LearningAssessmentResponse toAssessmentResponse(LearningAssessmentEntity assessment) {
    return new LearningAssessmentResponse(
        assessment.getId(),
        assessment.getTitle(),
        assessment.getDescription(),
        assessment.getInstructions(),
        assessment.getMaxAttempts(),
        assessment.getTimeLimitMinutes(),
        assessment.getPassPercentage(),
        parseJson(assessment.getQuestionSchema()),
        assessment.isActive(),
        assessment.getCreatedAt(),
        assessment.getUpdatedAt());
  }

  HelperAssessmentAttemptResponse toAttemptResponse(
      HelperAssessmentAttemptEntity attempt,
      String assessmentTitle,
      String helperName) {
    return new HelperAssessmentAttemptResponse(
        attempt.getId(),
        attempt.getAssessmentId(),
        assessmentTitle,
        attempt.getHelperId(),
        helperName,
        attempt.getAttemptNo(),
        attempt.getStatus().name(),
        attempt.getScorePercentage(),
        attempt.getCorrectCount(),
        attempt.getTotalCount(),
        attempt.getStartedAt(),
        attempt.getSubmittedAt(),
        attempt.getDurationSeconds(),
        parseJson(attempt.getAnswersJson()));
  }
}
