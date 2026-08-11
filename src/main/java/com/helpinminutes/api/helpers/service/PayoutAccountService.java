package com.helpinminutes.api.helpers.service;

import com.helpinminutes.api.errors.ForbiddenException;
import com.helpinminutes.api.helpers.dto.HelperBankDetailsResponse;
import com.helpinminutes.api.helpers.dto.HelperPayoutAccountRequest;
import com.helpinminutes.api.helpers.dto.IfscLookupResponse;
import com.helpinminutes.api.helpers.dto.PayoutAccountHistoryResponse;
import com.helpinminutes.api.helpers.dto.PayoutAccountUpdateRequest;
import com.helpinminutes.api.helpers.model.HelperPayoutAccountEntity;
import com.helpinminutes.api.helpers.model.PayoutAccountChangeEventEntity;
import com.helpinminutes.api.helpers.model.PayoutBeneficiaryLinkEntity;
import com.helpinminutes.api.helpers.repo.HelperPayoutAccountRepository;
import com.helpinminutes.api.helpers.repo.PayoutAccountChangeEventRepository;
import com.helpinminutes.api.helpers.repo.PayoutBeneficiaryLinkRepository;
import com.helpinminutes.api.helpers.security.BankAccountCipher;
import com.helpinminutes.api.reports.service.AuditLogService;
import com.helpinminutes.api.users.model.UserRole;
import com.helpinminutes.api.users.repo.UserRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PayoutAccountService {
  public record PreparedAccount(String holderName, String accountNumber, IfscLookupResponse ifsc) {}

  private final HelperPayoutAccountRepository accounts;
  private final PayoutBeneficiaryLinkRepository providerLinks;
  private final UserRepository users;
  private final IfscLookupService ifscLookup;
  private final BankAccountCipher cipher;
  private final AuditLogService audit;
  private final PayoutAccountChangeEventRepository changeEvents;
  private final BankChangeChallengeService challenges;
  private final ApplicationEventPublisher events;

  public PayoutAccountService(
      HelperPayoutAccountRepository accounts,
      PayoutBeneficiaryLinkRepository providerLinks,
      UserRepository users,
      IfscLookupService ifscLookup,
      BankAccountCipher cipher,
      AuditLogService audit,
      PayoutAccountChangeEventRepository changeEvents,
      BankChangeChallengeService challenges,
      ApplicationEventPublisher events) {
    this.accounts = accounts;
    this.providerLinks = providerLinks;
    this.users = users;
    this.ifscLookup = ifscLookup;
    this.cipher = cipher;
    this.audit = audit;
    this.changeEvents = changeEvents;
    this.challenges = challenges;
    this.events = events;
  }

  public PreparedAccount prepare(HelperPayoutAccountRequest req) {
    String holder = req.accountHolderName() == null
        ? ""
        : req.accountHolderName().trim().replaceAll("\\s+", " ");
    if (holder.length() < 3 || holder.length() > 160
        || !holder.matches(".*\\p{L}.*")
        || !holder.matches("[\\p{L}\\p{M}\\p{N} .,'&()/-]+")) {
      throw new com.helpinminutes.api.errors.BadRequestException("Enter the account-holder name shown by the bank");
    }
    String number = req.bankAccountNumber() == null ? "" : req.bankAccountNumber().replaceAll("\\s", "");
    if (!number.matches("^[0-9]{6,20}$")) {
      throw new com.helpinminutes.api.errors.BadRequestException("Enter a valid bank account number");
    }
    return new PreparedAccount(holder, number, ifscLookup.lookup(req.ifscCode()));
  }

  @Transactional(readOnly = true)
  public HelperBankDetailsResponse getCurrent(UUID userId) {
    return current(userId).map(PayoutAccountService::toResponse).orElse(null);
  }

  @Transactional(readOnly = true)
  public boolean hasSecureCurrent(UUID userId) {
    return current(userId).filter(account -> account.getAccountNumberCiphertext() != null
        && !"DETAILS_INCOMPLETE".equals(account.getVerificationStatus())).isPresent();
  }

  @Transactional
  public HelperBankDetailsResponse replace(UUID userId, UserRole role, PayoutAccountUpdateRequest req, String ipAddress) {
    PreparedAccount prepared = prepare(req.details());
    challenges.consume(userId, role, req.changeToken());
    return replacePreparedInternal(userId, role, prepared, "PROFILE", ipAddress);
  }

  @Transactional
  public HelperBankDetailsResponse replacePrepared(UUID userId, UserRole role, PreparedAccount prepared) {
    return replacePreparedInternal(userId, role, prepared, "INITIAL_KYC", null);
  }

  private HelperBankDetailsResponse replacePreparedInternal(
      UUID userId, UserRole role, PreparedAccount prepared, String source, String ipAddress) {
    // Serialize replacements across application instances so two taps cannot
    // create competing current destinations for the same beneficiary.
    var beneficiary = users.findByIdForUpdate(userId)
        .orElseThrow(() -> new ForbiddenException("User not found"));
    if (beneficiary.getRole() != role) {
      throw new ForbiddenException("Payout account role does not match the beneficiary");
    }
    Optional<HelperPayoutAccountEntity> previous = current(userId);
    Instant now = Instant.now();
    previous.ifPresent(old -> {
      old.setCurrent(false);
      old.setStatus("SUPERSEDED");
      old.setSupersededAt(now);
      for (PayoutBeneficiaryLinkEntity link : providerLinks.findByPayoutAccountId(old.getId())) {
        link.setStatus("DISABLED");
        providerLinks.save(link);
      }
      accounts.save(old);
      accounts.flush();
    });

    HelperPayoutAccountEntity account = new HelperPayoutAccountEntity();
    UUID accountId = UUID.randomUUID();
    account.setId(accountId);
    account.setHelperId(userId);
    account.setProvider(HelperPayoutAccountEntity.DEFAULT_PROVIDER);
    account.setStatus("PENDING_ACCOUNT_VERIFICATION");
    account.setVerificationStatus("NOT_STARTED");
    account.setCurrent(true);
    account.setChangeSource(source);
    account.setSupersedesAccountId(previous.map(HelperPayoutAccountEntity::getId).orElse(null));
    account.setAccountHolderName(prepared.holderName());
    account.setBankName(prepared.ifsc().bankName());
    account.setBankAccountLast4(prepared.accountNumber().substring(prepared.accountNumber().length() - 4));
    account.setIfscCode(prepared.ifsc().ifsc());
    account.setIfscVerifiedAt(now);
    BankAccountCipher.EncryptedValue encrypted = cipher.encrypt(accountId, prepared.accountNumber());
    account.setAccountNumberKeyId(encrypted.keyId());
    account.setAccountNumberCiphertext(encrypted.ciphertext());
    HelperPayoutAccountEntity saved = accounts.save(account);
    String action = previous.isPresent() ? "BANK_ACCOUNT_REPLACED" : "BANK_ACCOUNT_CREATED";
    PayoutAccountChangeEventEntity change = new PayoutAccountChangeEventEntity();
    change.setBeneficiaryUserId(userId);
    change.setActorUserId(userId);
    change.setActorRole(role.name());
    change.setActionType(action);
    change.setChangeSource(source);
    change.setPreviousAccountId(previous.map(HelperPayoutAccountEntity::getId).orElse(null));
    change.setNewAccountId(saved.getId());
    change.setPreviousLast4(previous.map(HelperPayoutAccountEntity::getBankAccountLast4).orElse(null));
    change.setNewLast4(saved.getBankAccountLast4());
    change.setIpAddress(ipAddress);
    changeEvents.save(change); // Strict, transactional audit: never swallow this failure.
    audit.logAction(userId, null, role.name(), action,
        "PAYOUT_ACCOUNT", saved.getId().toString(),
        "Account ending " + saved.getBankAccountLast4() + "; previousVersion="
            + previous.map(value -> value.getId().toString()).orElse("none"), ipAddress);
    events.publishEvent(new BankAccountChangedEvent(userId, saved.getBankName(), saved.getBankAccountLast4()));
    return toResponse(saved);
  }

  @Transactional(readOnly = true)
  public PayoutAccountHistoryResponse history(UUID userId, int limit) {
    var history = changeEvents.findByBeneficiaryUserIdOrderByCreatedAtDesc(
        userId, PageRequest.of(0, Math.min(100, Math.max(1, limit))));
    var accountIds = history.stream()
        .flatMap(event -> java.util.stream.Stream.of(event.getPreviousAccountId(), event.getNewAccountId()))
        .filter(java.util.Objects::nonNull)
        .distinct()
        .toList();
    var versions = accounts.findAllById(accountIds).stream()
        .collect(java.util.stream.Collectors.toMap(HelperPayoutAccountEntity::getId, value -> value));
    var entries = history.stream()
        .map(event -> new PayoutAccountHistoryResponse.Entry(
            event.getId(), event.getActionType(), event.getChangeSource(), event.getPreviousAccountId(),
            event.getNewAccountId(), mask(event.getPreviousLast4()), mask(event.getNewLast4()),
            bankName(versions, event.getPreviousAccountId()), bankName(versions, event.getNewAccountId()),
            ifsc(versions, event.getPreviousAccountId()), ifsc(versions, event.getNewAccountId()),
            event.getActorUserId(), event.getActorRole(), event.getIpAddress(), event.getCreatedAt()))
        .toList();
    return new PayoutAccountHistoryResponse(userId, entries);
  }

  private static String mask(String last4) { return last4 == null ? null : "••••" + last4; }
  private static String bankName(java.util.Map<UUID, HelperPayoutAccountEntity> versions, UUID id) {
    return id == null || versions.get(id) == null ? null : versions.get(id).getBankName();
  }
  private static String ifsc(java.util.Map<UUID, HelperPayoutAccountEntity> versions, UUID id) {
    return id == null || versions.get(id) == null ? null : versions.get(id).getIfscCode();
  }

  private Optional<HelperPayoutAccountEntity> current(UUID userId) {
    return accounts.findByHelperIdAndProviderAndCurrentTrue(userId, HelperPayoutAccountEntity.DEFAULT_PROVIDER);
  }

  public static HelperBankDetailsResponse toResponse(HelperPayoutAccountEntity account) {
    if (account == null) return null;
    String last4 = account.getBankAccountLast4();
    boolean eligible = "VERIFIED".equals(account.getVerificationStatus()) && "ACTIVE".equals(account.getStatus());
    return new HelperBankDetailsResponse(
        account.getId(), account.getAccountHolderName(), account.getBankName(), last4,
        last4 == null ? null : "••••" + last4, account.getIfscCode(), account.getIfscVerifiedAt(),
        account.getVerificationStatus(), account.getStatus(), eligible, account.getUpdatedAt());
  }
}
