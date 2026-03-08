package com.helpinminutes.api.photos.controller;

import com.helpinminutes.api.photos.dto.ConfirmUploadRequest;
import com.helpinminutes.api.photos.dto.PhotoUploadRequest;
import com.helpinminutes.api.photos.dto.PresignedUploadResponse;
import com.helpinminutes.api.photos.service.PhotoService;
import com.helpinminutes.api.security.UserPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/photos")
public class PhotoController {

    private final PhotoService photoService;

    public PhotoController(PhotoService photoService) {
        this.photoService = photoService;
    }

    @PostMapping("/request-upload")
    public ResponseEntity<PresignedUploadResponse> requestUpload(
            @AuthenticationPrincipal UserPrincipal auth,
            @Valid @RequestBody PhotoUploadRequest request) {

        UUID userId = auth.userId();
        PresignedUploadResponse response = photoService.requestUpload(userId, request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/confirm-upload")
    public ResponseEntity<Void> confirmUpload(
            @AuthenticationPrincipal UserPrincipal auth,
            @Valid @RequestBody ConfirmUploadRequest request) {

        UUID userId = auth.userId();
        photoService.confirmUpload(userId, request);
        return ResponseEntity.ok().build();
    }
}
