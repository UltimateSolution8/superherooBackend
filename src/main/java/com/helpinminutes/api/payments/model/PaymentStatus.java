package com.helpinminutes.api.payments.model;

public enum PaymentStatus {
  CREATING,
  CREATED,
  AUTHORIZED,
  CAPTURED,
  FAILED,
  CANCELLED,
  PARTIALLY_REFUNDED,
  REFUNDED
}
