package com.helpinminutes.api.batches.controller;

import com.helpinminutes.api.batches.dto.BatchDtos;
import com.helpinminutes.api.batches.service.BookingBatchService;
import com.helpinminutes.api.security.UserPrincipal;
import com.helpinminutes.api.users.model.UserRole;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/batches")
public class BookingBatchController {
  private final BookingBatchService service;

  public BookingBatchController(BookingBatchService service) {
    this.service = service;
  }

  @PostMapping("/preview")
  public BatchDtos.PreviewResponse preview(
      @AuthenticationPrincipal UserPrincipal principal,
      @Valid @RequestBody BatchDtos.PreviewRequest req) {
    if (principal.role() != UserRole.BUYER && principal.role() != UserRole.ADMIN) {
      throw new com.helpinminutes.api.errors.ForbiddenException("Not allowed");
    }
    return service.preview(req);
  }

  @PostMapping
  public BatchDtos.CreateResponse create(
      @AuthenticationPrincipal UserPrincipal principal,
      @Valid @RequestBody BatchDtos.CreateRequest req) {
    return service.create(principal.userId(), principal.role(), req);
  }

  @GetMapping("/{batchId}")
  public BatchDtos.BatchSummaryResponse summary(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID batchId) {
    return service.getSummary(principal.userId(), principal.role(), batchId);
  }

  @GetMapping("/{batchId}/items")
  public List<BatchDtos.BatchItemResponse> items(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID batchId) {
    return service.getItems(principal.userId(), principal.role(), batchId);
  }

  @GetMapping("/{batchId}/live")
  public BatchDtos.BatchLiveResponse live(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID batchId) {
    return service.getLive(principal.userId(), principal.role(), batchId);
  }

  @PostMapping("/{batchId}/items/{itemId}/retry")
  public BatchDtos.BatchItemResponse retryItem(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID batchId,
      @PathVariable UUID itemId) {
    return service.retryItem(principal.userId(), principal.role(), batchId, itemId);
  }

  @PostMapping("/{batchId}/items/{itemId}/cancel")
  public BatchDtos.BatchItemResponse cancelItem(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID batchId,
      @PathVariable UUID itemId,
      @Valid @RequestBody(required = false) BatchDtos.ItemActionRequest req) {
    return service.cancelItem(
        principal.userId(),
        principal.role(),
        batchId,
        itemId,
        req == null ? null : req.reason());
  }
}
