package com.helpinminutes.api.kyc.livekit;

import com.helpinminutes.api.config.LiveKitProperties;
import com.helpinminutes.api.errors.LiveKycUnavailableException;
import io.livekit.server.AccessToken;
import io.livekit.server.CanPublish;
import io.livekit.server.CanPublishData;
import io.livekit.server.CanSubscribe;
import io.livekit.server.RoomJoin;
import io.livekit.server.RoomName;
import io.livekit.server.RoomServiceClient;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class LiveKitService {
  private static final Logger log = LoggerFactory.getLogger(LiveKitService.class);

  private final LiveKitProperties properties;
  private final RoomServiceClient rooms;

  @Autowired
  public LiveKitService(LiveKitProperties properties) {
    this(properties, RoomServiceClient.createClient(
        properties.url(), properties.apiKey(), properties.apiSecret()));
  }

  LiveKitService(LiveKitProperties properties, RoomServiceClient rooms) {
    this.properties = properties;
    this.rooms = rooms;
  }

  public void ensureTwoParticipantRoom(String roomId) {
    try {
      var existing = rooms.listRooms(List.of(roomId)).execute();
      if (!existing.isSuccessful()) {
        throw new IllegalStateException("LiveKit list rooms returned HTTP " + existing.code());
      }
      if (existing.body() == null || existing.body().isEmpty()) {
        var created = rooms.createRoom(roomId, 300, 2).execute();
        if (!created.isSuccessful()) {
          throw new IllegalStateException("LiveKit create room returned HTTP " + created.code());
        }
      }
    } catch (Exception ex) {
      throw new LiveKycUnavailableException("Unable to prepare a LiveKit room", ex);
    }
  }

  public String createParticipantToken(String identity, String name, String roomId) {
    try {
      AccessToken token = new AccessToken(properties.apiKey(), properties.apiSecret());
      token.setIdentity(identity);
      token.setName(name);
      // The Kotlin SDK's ttl property is expressed in milliseconds.
      token.setTtl(TimeUnit.SECONDS.toMillis(properties.tokenTtlSeconds()));
      token.addGrants(
          new RoomJoin(true),
          new RoomName(roomId),
          new CanPublish(true),
          new CanSubscribe(true),
          new CanPublishData(false));
      return token.toJwt();
    } catch (Exception ex) {
      throw new LiveKycUnavailableException("Unable to authorize the LiveKit participant", ex);
    }
  }

  public void deleteRoomBestEffort(String roomId) {
    if (roomId == null || roomId.isBlank()) {
      return;
    }
    try {
      var deleted = rooms.deleteRoom(roomId).execute();
      if (!deleted.isSuccessful() && deleted.code() != 404) {
        log.warn("LiveKit room cleanup failed roomId={} status={}", roomId, deleted.code());
      }
    } catch (Exception ex) {
      log.warn("LiveKit room cleanup failed roomId={}", roomId, ex);
    }
  }

  public long tokenTtlSeconds() {
    return properties.tokenTtlSeconds();
  }

  public String serverUrl() {
    return properties.url();
  }
}
