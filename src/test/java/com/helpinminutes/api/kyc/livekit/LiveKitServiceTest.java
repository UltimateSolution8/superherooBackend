package com.helpinminutes.api.kyc.livekit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.helpinminutes.api.config.LiveKitProperties;
import com.helpinminutes.api.errors.LiveKycUnavailableException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.livekit.server.RoomServiceClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import livekit.LivekitModels.Room;
import org.junit.jupiter.api.Test;
import retrofit2.Call;
import retrofit2.Response;

class LiveKitServiceTest {
  private static final String SECRET = "livekit-test-secret-with-at-least-32-bytes";
  private static final LiveKitProperties PROPERTIES = new LiveKitProperties(
      "wss://livekit.example.test", "test-api-key", SECRET, 900);

  @Test
  void createsRoomWithExactlyTwoParticipantCapacity() throws Exception {
    RoomServiceClient client = mock(RoomServiceClient.class);
    @SuppressWarnings("unchecked") Call<List<Room>> listCall = mock(Call.class);
    @SuppressWarnings("unchecked") Call<Room> createCall = mock(Call.class);
    when(client.listRooms(List.of("kyc_room_1"))).thenReturn(listCall);
    when(listCall.execute()).thenReturn(Response.success(List.of()));
    when(client.createRoom("kyc_room_1", 300, 2)).thenReturn(createCall);
    when(createCall.execute()).thenReturn(Response.success(mock(Room.class)));

    new LiveKitService(PROPERTIES, client).ensureTwoParticipantRoom("kyc_room_1");

    verify(client).createRoom("kyc_room_1", 300, 2);
  }

  @Test
  void doesNotRecreateAnExistingRoom() throws Exception {
    RoomServiceClient client = mock(RoomServiceClient.class);
    @SuppressWarnings("unchecked") Call<List<Room>> listCall = mock(Call.class);
    when(client.listRooms(List.of("kyc_room_1"))).thenReturn(listCall);
    when(listCall.execute()).thenReturn(Response.success(List.of(mock(Room.class))));

    new LiveKitService(PROPERTIES, client).ensureTwoParticipantRoom("kyc_room_1");

    verify(client).listRooms(List.of("kyc_room_1"));
  }

  @Test
  void tokenIsRoomScopedAndAllowsOnlyNormalParticipantMediaActions() {
    Instant issuedAround = Instant.now();
    String token = new LiveKitService(PROPERTIES, mock(RoomServiceClient.class))
        .createParticipantToken("helper_1", "Helper", "kyc_room_1");

    var claims = Jwts.parser()
        .verifyWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
        .build()
        .parseSignedClaims(token)
        .getPayload();

    assertThat(claims.getSubject()).isEqualTo("helper_1");
    assertThat(claims.get("name", String.class)).isEqualTo("Helper");
    @SuppressWarnings("unchecked")
    Map<String, Object> video = claims.get("video", Map.class);
    assertThat(video).containsEntry("room", "kyc_room_1")
        .containsEntry("roomJoin", true)
        .containsEntry("canPublish", true)
        .containsEntry("canSubscribe", true)
        .containsEntry("canPublishData", false);
    assertThat(claims.getExpiration().toInstant())
        .isBetween(issuedAround.plusSeconds(899), issuedAround.plusSeconds(901));
  }

  @Test
  void providerFailureBecomesTheSpecificLiveKycAvailabilityError() throws Exception {
    RoomServiceClient client = mock(RoomServiceClient.class);
    @SuppressWarnings("unchecked") Call<List<Room>> listCall = mock(Call.class);
    when(client.listRooms(List.of("kyc_room_1"))).thenReturn(listCall);
    when(listCall.execute()).thenThrow(new java.io.IOException("unreachable"));

    assertThatThrownBy(() -> new LiveKitService(PROPERTIES, client).ensureTwoParticipantRoom("kyc_room_1"))
        .isInstanceOf(LiveKycUnavailableException.class)
        .hasMessageContaining("LiveKit room");
  }
}
