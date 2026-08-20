package com.helpinminutes.api.users.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.helpinminutes.api.errors.BadRequestException;
import com.helpinminutes.api.errors.NotFoundException;
import com.helpinminutes.api.users.dto.SavedAddressDtos.ImportAddressesRequest;
import com.helpinminutes.api.users.dto.SavedAddressDtos.SaveAddressRequest;
import com.helpinminutes.api.users.dto.SavedAddressDtos.SavedAddressResponse;
import com.helpinminutes.api.users.dto.SavedAddressDtos.UpdateAddressRequest;
import com.helpinminutes.api.users.model.SavedAddressEntity;
import com.helpinminutes.api.users.repo.SavedAddressRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Saved addresses moved from the device to the server, so the rules that used to be
 * "whatever the app happened to write" are now the API's to keep.
 *
 * Three of them matter enough to pin down:
 *
 * <ol>
 *   <li><b>Ownership.</b> Every lookup is scoped to the caller. Somebody else's
 *       address id must read as absent, not as forbidden — a 403 confirms the row
 *       exists.
 *   <li><b>Exactly one default.</b> The create-task prefill reads it, so two
 *       defaults is undefined behaviour and none is a feature that stops working.
 *   <li><b>The import is idempotent.</b> It runs unattended on app start; a retry
 *       after a dropped connection must converge, not duplicate.
 * </ol>
 */
class SavedAddressServiceTest {

  /** Hitech City — inside the Hyderabad service radius, so it passes the area check. */
  private static final double LAT = 17.4435d;
  private static final double LNG = 78.3772d;

  private final UUID user = UUID.randomUUID();
  private final UUID stranger = UUID.randomUUID();
  private final Map<UUID, SavedAddressEntity> store = new LinkedHashMap<>();
  private SavedAddressService service;

  @BeforeEach
  void setUp() {
    store.clear();
    service = new SavedAddressService(inMemoryRepository());
  }

  private static SaveAddressRequest request(String label, boolean isDefault) {
    return new SaveAddressRequest(label, label + " address, Hyderabad", LAT, LNG, null, isDefault);
  }

  @Test
  void theFirstAddressBecomesTheDefaultWhetherOrNotItWasAsked() {
    SavedAddressResponse home = service.create(user, request("Home", false));

    assertTrue(home.isDefault(), "the prefill has nothing to read if the only address is not default");
  }

  @Test
  void settingANewDefaultClearsTheOldOne() {
    service.create(user, request("Home", true));
    SavedAddressResponse work = service.create(user, request("Work", true));

    List<SavedAddressResponse> all = service.list(user);
    assertEquals(1, all.stream().filter(SavedAddressResponse::isDefault).count());
    assertTrue(all.stream().filter(SavedAddressResponse::isDefault)
        .allMatch(a -> a.id().equals(work.id())));
  }

  @Test
  void makeDefaultMovesTheFlagRatherThanAddingASecond() {
    SavedAddressResponse home = service.create(user, request("Home", true));
    service.create(user, request("Work", false));

    service.makeDefault(user, home.id());

    assertEquals(1, service.list(user).stream().filter(SavedAddressResponse::isDefault).count());
  }

  @Test
  void deletingTheDefaultPromotesAnother() {
    SavedAddressResponse home = service.create(user, request("Home", true));
    service.create(user, request("Work", false));

    service.delete(user, home.id());

    List<SavedAddressResponse> remaining = service.list(user);
    assertEquals(1, remaining.size());
    assertTrue(remaining.get(0).isDefault(), "a user with addresses must always have a default");
  }

  @Test
  void deletingTheLastAddressLeavesNothingBehind() {
    SavedAddressResponse only = service.create(user, request("Home", true));

    service.delete(user, only.id());

    assertTrue(service.list(user).isEmpty());
  }

  @Test
  void aStrangerCannotReadUpdateOrDeleteSomebodyElsesAddress() {
    SavedAddressResponse home = service.create(user, request("Home", true));

    assertTrue(service.list(stranger).isEmpty());
    assertThrows(NotFoundException.class,
        () -> service.update(stranger, home.id(), new UpdateAddressRequest(
            "Stolen", null, null, null, null, null)));
    assertThrows(NotFoundException.class, () -> service.makeDefault(stranger, home.id()));
    assertThrows(NotFoundException.class, () -> service.delete(stranger, home.id()));
    assertEquals("Home", service.list(user).get(0).label());
  }

  @Test
  void duplicateLabelsAreRejectedRegardlessOfCase() {
    service.create(user, request("Home", true));

    assertThrows(BadRequestException.class, () -> service.create(user, request("home", false)));
  }

  @Test
  void anAddressOutsideTheServiceAreaIsRefusedByTheServerNotJustTheApp() {
    // Mumbai. Well outside the Hyderabad radius.
    SaveAddressRequest faraway =
        new SaveAddressRequest("Beach house", "Marine Drive, Mumbai", 19.0760d, 72.8777d, null, false);

    assertThrows(BadRequestException.class, () -> service.create(user, faraway));
  }

  @Test
  void movingCoordinatesRequiresBothOrNeither() {
    SavedAddressResponse home = service.create(user, request("Home", true));

    assertThrows(BadRequestException.class,
        () -> service.update(user, home.id(), new UpdateAddressRequest(null, null, 17.5d, null, null, null)));
  }

  @Test
  void theCapIsEnforced() {
    for (int i = 0; i < SavedAddressService.MAX_ADDRESSES_PER_USER; i++) {
      service.create(user, request("Place " + i, false));
    }

    assertThrows(BadRequestException.class, () -> service.create(user, request("One too many", false)));
  }

  @Test
  void importingTheSameDeviceListTwiceDoesNotDuplicateIt() {
    ImportAddressesRequest payload = new ImportAddressesRequest(
        List.of(request("Home", true), request("Work", false)));

    service.importLocal(user, payload);
    List<SavedAddressResponse> second = service.importLocal(user, payload);

    assertEquals(2, second.size());
    assertEquals(1, second.stream().filter(SavedAddressResponse::isDefault).count());
  }

  @Test
  void importSkipsAnUnusableRowInsteadOfLosingTheRest() {
    ImportAddressesRequest payload = new ImportAddressesRequest(List.of(
        request("Home", true),
        // Outside the service area: individually invalid, and not worth failing a
        // background import the citizen never asked for.
        new SaveAddressRequest("Beach house", "Marine Drive, Mumbai", 19.0760d, 72.8777d, null, false),
        request("Work", false)));

    List<SavedAddressResponse> imported = service.importLocal(user, payload);

    assertEquals(2, imported.size());
    assertFalse(imported.stream().anyMatch(a -> a.label().equals("Beach house")));
  }

  /**
   * A map standing in for Postgres.
   *
   * Only the queries the service uses are answered; everything else on
   * JpaRepository stays a Mockito default. Real semantics matter here — ordering,
   * case-insensitive label matching, and the fact that clearing the default is one
   * statement — because those are what the assertions are about.
   */
  private SavedAddressRepository inMemoryRepository() {
    SavedAddressRepository repo = mock(SavedAddressRepository.class);

    when(repo.save(any(SavedAddressEntity.class))).thenAnswer(invocation -> {
      SavedAddressEntity entity = invocation.getArgument(0);
      if (entity.getId() == null) {
        entity.setId(UUID.randomUUID());
        setCreatedAt(entity);
      }
      store.put(entity.getId(), entity);
      return entity;
    });

    when(repo.findByUserIdOrderByDefaultAddressDescCreatedAtDesc(any())).thenAnswer(invocation -> {
      UUID owner = invocation.getArgument(0);
      List<SavedAddressEntity> rows = new ArrayList<>(store.values().stream()
          .filter(a -> a.getUserId().equals(owner))
          .toList());
      rows.sort(Comparator
          .comparing(SavedAddressEntity::isDefaultAddress).reversed()
          .thenComparing(SavedAddressEntity::getCreatedAt, Comparator.reverseOrder()));
      return rows;
    });

    when(repo.findByIdAndUserId(any(), any())).thenAnswer(invocation -> {
      UUID id = invocation.getArgument(0);
      UUID owner = invocation.getArgument(1);
      return Optional.ofNullable(store.get(id)).filter(a -> a.getUserId().equals(owner));
    });

    when(repo.countByUserId(any())).thenAnswer(invocation -> {
      UUID owner = invocation.getArgument(0);
      return store.values().stream().filter(a -> a.getUserId().equals(owner)).count();
    });

    when(repo.existsByUserIdAndLabelIgnoreCase(any(), anyString())).thenAnswer(invocation ->
        findByLabel(invocation.getArgument(0), invocation.getArgument(1)).isPresent());

    when(repo.findByUserIdAndLabelIgnoreCase(any(), anyString())).thenAnswer(invocation ->
        findByLabel(invocation.getArgument(0), invocation.getArgument(1)));

    when(repo.clearDefaultExcept(any(), any())).thenAnswer(invocation -> {
      UUID owner = invocation.getArgument(0);
      UUID keep = invocation.getArgument(1);
      int cleared = 0;
      for (SavedAddressEntity entity : store.values()) {
        if (entity.getUserId().equals(owner) && entity.isDefaultAddress()
            && !entity.getId().equals(keep)) {
          entity.setDefaultAddress(false);
          cleared++;
        }
      }
      return cleared;
    });

    org.mockito.Mockito.doAnswer(invocation -> {
      SavedAddressEntity entity = invocation.getArgument(0);
      store.remove(entity.getId());
      return null;
    }).when(repo).delete(any(SavedAddressEntity.class));

    return repo;
  }

  private Optional<SavedAddressEntity> findByLabel(UUID owner, String label) {
    return store.values().stream()
        .filter(a -> a.getUserId().equals(owner))
        .filter(a -> a.getLabel().equalsIgnoreCase(label))
        .findFirst();
  }

  /** JPA sets this via @PrePersist; the fake has to supply it for the ordering to work. */
  private void setCreatedAt(SavedAddressEntity entity) {
    try {
      var field = SavedAddressEntity.class.getDeclaredField("createdAt");
      field.setAccessible(true);
      // Distinct and increasing, so "newest first" is deterministic in the test.
      field.set(entity, Instant.now().plusNanos(store.size() * 1_000_000L));
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }
}
