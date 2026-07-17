package com.helpinminutes.api.payments.gateway;

public class RazorpayGatewayException extends RuntimeException {
  public RazorpayGatewayException(String message, Throwable cause) {
    super(message, cause);
  }

  public RazorpayGatewayException(String message) {
    super(message);
  }
}
