package com.helpinminutes.api.errors;

public class LiveKycUnavailableException extends RuntimeException {
  public LiveKycUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
