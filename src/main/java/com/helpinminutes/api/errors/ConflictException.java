package com.helpinminutes.api.errors;

public class ConflictException extends RuntimeException {
  public ConflictException(String message) {
    super(message);
  }
}
