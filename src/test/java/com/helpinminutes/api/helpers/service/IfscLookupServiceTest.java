package com.helpinminutes.api.helpers.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helpinminutes.api.errors.BadRequestException;
import com.helpinminutes.api.errors.ServiceUnavailableException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IfscLookupServiceTest {
  private HttpServer server;
  private String baseUrl;
  private final AtomicInteger requests = new AtomicInteger();

  @BeforeEach
  void startServer() throws Exception {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterEach
  void stopServer() { server.stop(0); }

  @Test
  void mapsValidResponseAndCachesSuccess() {
    server.createContext("/HDFC0000001", exchange -> {
      requests.incrementAndGet();
      byte[] body = "{\"IFSC\":\"HDFC0000001\",\"BANK\":\"HDFC Bank\",\"BRANCH\":\"TEST\",\"CITY\":\"HYDERABAD\",\"DISTRICT\":\"HYDERABAD\",\"STATE\":\"TELANGANA\"}".getBytes();
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    IfscLookupService service = new IfscLookupService(new ObjectMapper(), baseUrl, 500, 1000);
    assertEquals("HDFC Bank", service.lookup("hdfc0000001").bankName());
    assertEquals("HDFC Bank", service.lookup("HDFC0000001").bankName());
    assertEquals(1, requests.get());
  }

  @Test
  void distinguishesInvalidAndUnavailableResponses() {
    server.createContext("/ABCD0000000", exchange -> { exchange.sendResponseHeaders(404, -1); exchange.close(); });
    server.createContext("/HDFC0000005", exchange -> { exchange.sendResponseHeaders(401, -1); exchange.close(); });
    server.createContext("/HDFC0000002", exchange -> { exchange.sendResponseHeaders(429, -1); exchange.close(); });
    server.createContext("/HDFC0000003", exchange -> { exchange.sendResponseHeaders(500, -1); exchange.close(); });
    IfscLookupService service = new IfscLookupService(new ObjectMapper(), baseUrl, 500, 1000);
    assertThrows(BadRequestException.class, () -> service.lookup("bad"));
    assertThrows(BadRequestException.class, () -> service.lookup("ABCD0000000"));
    assertThrows(ServiceUnavailableException.class, () -> service.lookup("HDFC0000002"));
    assertThrows(ServiceUnavailableException.class, () -> service.lookup("HDFC0000003"));
    assertThrows(ServiceUnavailableException.class, () -> service.lookup("HDFC0000005"));
  }

  @Test
  void timesOutAsUnavailable() {
    server.createContext("/HDFC0000004", exchange -> {
      try { Thread.sleep(250); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
      exchange.sendResponseHeaders(200, -1);
      exchange.close();
    });
    IfscLookupService service = new IfscLookupService(new ObjectMapper(), baseUrl, 100, 50);
    assertThrows(ServiceUnavailableException.class, () -> service.lookup("HDFC0000004"));
  }
}
