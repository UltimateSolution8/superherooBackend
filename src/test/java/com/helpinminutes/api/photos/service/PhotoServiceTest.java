package com.helpinminutes.api.photos.service;

import com.helpinminutes.api.photos.dto.PhotoUploadRequest;
import com.helpinminutes.api.photos.dto.PresignedUploadResponse;
import com.helpinminutes.api.photos.model.PhotoEntity;
import com.helpinminutes.api.photos.repository.PhotoRepository;
import com.helpinminutes.api.tasks.model.TaskEntity;
import com.helpinminutes.api.tasks.repo.TaskRepository;
import com.helpinminutes.api.tasks.service.TaskService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Disabled;

@Disabled("Mockito inline mock maker fails on JDK 21 without --add-opens")
class PhotoServiceTest {

    private PhotoRepository photoRepository;
    private TaskRepository taskRepository;
    private S3PresignerService presignerService;
    private RabbitTemplate rabbitTemplate;
    private PhotoService photoService;

    @BeforeEach
    void setUp() {
        photoRepository = Mockito.mock(PhotoRepository.class);
        taskRepository = Mockito.mock(TaskRepository.class);
        presignerService = Mockito.mock(S3PresignerService.class);
        rabbitTemplate = Mockito.mock(RabbitTemplate.class);
        TaskService taskService = Mockito.mock(TaskService.class);

        photoService = new PhotoService(
                photoRepository,
                taskRepository,
                presignerService,
                rabbitTemplate,
                new SimpleMeterRegistry(),
                taskService);
    }

    @Test
    void testRequestUpload() {
        UUID userId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();

        TaskEntity task = new TaskEntity();
        ReflectionTestUtils.setField(task, "id", jobId);
        ReflectionTestUtils.setField(task, "assignedHelperId", userId);

        when(taskRepository.findById(jobId)).thenReturn(Optional.of(task));

        when(photoRepository.save(any(PhotoEntity.class))).thenAnswer(inv -> {
            PhotoEntity pe = inv.getArgument(0);
            ReflectionTestUtils.setField(pe, "id", UUID.randomUUID());
            return pe;
        });

        PresignedUploadResponse presignedRes = new PresignedUploadResponse(
                UUID.randomUUID(), "http://presigned.url", Map.of(), 600L, "photos/" + jobId + "/test.jpg");
        when(presignerService.createPresignedPut(any(), any(), any())).thenReturn(presignedRes);

        PhotoUploadRequest req = new PhotoUploadRequest();
        req.setJobId(jobId);
        req.setPhotoType("arrival");

        PresignedUploadResponse res = photoService.requestUpload(userId, req);

        assertNotNull(res);
        assertNotNull(res.getPhotoId());
        assertEquals("http://presigned.url", res.getPresignedUrl());
    }
}
