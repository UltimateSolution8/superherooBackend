package com.helpinminutes.api.geo;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.helpinminutes.api.security.UserPrincipal;
import com.helpinminutes.api.users.model.UserRole;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GeoControllerTest {
  private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final String TOKEN = "address-session-12345";

  private GeoProviderChain geo;
  private GeoSpendGuard guard;
  private GeoController controller;

  @BeforeEach
  void setUp() {
    geo = mock(GeoProviderChain.class);
    guard = mock(GeoSpendGuard.class);
    controller = new GeoController(geo, new GeoProperties(), guard);
  }

  @Test
  void googlePredictionsAuthorizeOneDetailsCallForTheBuyerSession() {
    UserPrincipal buyer = new UserPrincipal(USER, UserRole.BUYER);
    GeoDtos.SuggestionsResponse response = new GeoDtos.SuggestionsResponse(List.of(), "google", false);
    when(guard.validSessionToken(TOKEN)).thenReturn(true);
    when(guard.allowNewPremiumSession()).thenReturn(true);
    when(guard.allowPremiumForUser(USER)).thenReturn(true);
    when(guard.authorizePremiumDetails(USER, TOKEN)).thenReturn(true);
    when(geo.autocomplete("Madhapur", 17.4, 78.4, true, TOKEN)).thenReturn(response);

    assertSame(response, controller.autocomplete(
        buyer, "Madhapur", 17.4, 78.4, "task_create", TOKEN));

    verify(guard).authorizePremiumDetails(USER, TOKEN);
  }

  @Test
  void contextStringCannotGiveAHelperAccessToThePremiumBudget() {
    UserPrincipal helper = new UserPrincipal(USER, UserRole.HELPER);
    GeoDtos.SuggestionsResponse response = new GeoDtos.SuggestionsResponse(List.of(), "ola", false);
    when(geo.autocomplete("Madhapur", null, null, false, TOKEN)).thenReturn(response);

    assertSame(response, controller.autocomplete(
        helper, "Madhapur", null, null, "task_create", TOKEN));

    verify(guard, never()).allowNewPremiumSession();
    verify(guard, never()).allowPremiumForUser(any());
  }

  @Test
  void authorizationStoreFailureReplacesUnresolvableGooglePredictionsWithOla() {
    UserPrincipal buyer = new UserPrincipal(USER, UserRole.BUYER);
    GeoDtos.SuggestionsResponse google = new GeoDtos.SuggestionsResponse(List.of(), "google", false);
    GeoDtos.SuggestionsResponse ola = new GeoDtos.SuggestionsResponse(List.of(), "ola", false);
    when(guard.validSessionToken(TOKEN)).thenReturn(true);
    when(guard.allowNewPremiumSession()).thenReturn(true);
    when(guard.allowPremiumForUser(USER)).thenReturn(true);
    when(guard.authorizePremiumDetails(USER, TOKEN)).thenReturn(false);
    when(geo.autocomplete("Madhapur", null, null, true, TOKEN)).thenReturn(google);
    when(geo.autocomplete("Madhapur", null, null, false, TOKEN)).thenReturn(ola);

    assertSame(ola, controller.autocomplete(
        buyer, "Madhapur", null, null, "task_create", TOKEN));
  }

  @Test
  void inventedGooglePlaceIdCannotConsumeTheReservedDetailsBudget() {
    UserPrincipal buyer = new UserPrincipal(USER, UserRole.BUYER);
    when(guard.consumePremiumDetailsAuthorization(USER, TOKEN)).thenReturn(false);

    GeoDtos.GeoEnvelope<GeoDtos.PlaceDetail> response =
        controller.placeDetails(buyer, "google:invented", TOKEN);

    assertTrue(response.degraded());
    verify(geo, never()).placeDetails(anyString(), any());
  }

  @Test
  void authorizedGoogleDetailsUsesTheProviderScopedLookup() {
    UserPrincipal buyer = new UserPrincipal(USER, UserRole.BUYER);
    GeoDtos.GeoEnvelope<GeoDtos.PlaceDetail> expected = GeoDtos.GeoEnvelope.served(
        new GeoDtos.PlaceDetail("google:abc", null, null, 17.4, 78.4), "google");
    when(guard.consumePremiumDetailsAuthorization(USER, TOKEN)).thenReturn(true);
    when(geo.placeDetails("google:abc", TOKEN)).thenReturn(expected);

    assertSame(expected, controller.placeDetails(buyer, "google:abc", TOKEN));
  }
}
