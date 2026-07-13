package com.helpinminutes.api.batches.model;

public enum BookingBatchStatus {
  CREATED,
  PARTIAL,
  COMPLETED,
  CANCELLED,
  PENDING_MEDIATOR,
  MEDIATOR_ACCEPTED,
  MEDIATOR_DISPATCHING,
  MEDIATOR_IN_PROGRESS,
  MEDIATOR_COMPLETED
}

