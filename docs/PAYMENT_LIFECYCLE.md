# Payment Lifecycle

## Booking modes

### Online prepayment

1. Create the task or crew request in `PAYMENT_PENDING`.
2. Create a Razorpay order using the server-calculated amount.
3. Verify the checkout signature and confirm the provider payment is `captured`.
4. Only then move the task to `SEARCHING`/`SCHEDULED_PENDING`, or move a crew request to `PENDING_AUDIT`.
5. Keep the payment fulfillment state as `HELD` while work is incomplete.
6. On successful end OTP, set fulfillment to `EARNED` and expose it in partner earnings.
7. If the request is cancelled before completion, queue a full refund and reconcile it from Razorpay webhooks.
8. If a captured callback arrives after an unpaid booking expired, keep the booking cancelled and refund the late capture.

`HELD` is an application fulfillment state. It is not a bank escrow or a Razorpay settlement hold.

### Pay after service

1. Dispatch the booking without online checkout.
2. After completion, the citizen can pay online or directly using cash/UPI.
3. The assigned partner confirms direct cash/UPI receipt. Confirmation is idempotent and creates a real payment ledger row.

## Crew and mediator rules

- The citizen pays once before a prepaid crew request is released for audit.
- `PER_HELPER` allocates earned amounts to each present helper after completion.
- `CONSOLIDATED_MEDIATOR` allocates the completed crew amount to the mediator.
- The citizen and mediator use the batch start/end OTPs.
- Helpers do not enter individual OTPs for mediator-managed jobs.
- Every present helper must provide an arrival photo before batch start and a completion photo before batch completion.
- Absent helpers are skipped and their worker task is cancelled.
- A prepaid crew booking refunds the unfulfilled helper amount after completion.
- Recurring bookings remain pay-after-service until a compliant recurring mandate/autopay product is introduced; the system never stores card or UPI credentials.

## Provider invariants

- Amounts come only from persisted task/batch data.
- Checkout uses idempotency keys.
- Client success is never sufficient; signatures and captured state are verified server-side.
- Webhooks are deduplicated and out-of-order events cannot regress captured/refunded payments.
- Refund submission uses `X-Refund-Idempotency`, then waits for webhook reconciliation.
- Helper earnings include only `EARNED` fulfillment rows/allocations.

## Required for real bank credit

The current integration captures real payment-gateway funds and releases application earnings after completion. Actual automated bank settlement to helpers requires Razorpay Route activation, one Linked Account per beneficiary, helper bank/KYC onboarding, transfer/reversal webhooks, and reconciliation. Never describe the internal `EARNED` state as a completed bank payout until Route reports the transfer as processed/settled.

### Route production switch

`V48__razorpay_route_settlement_readiness.sql` reserves audited linked-account and transfer records with unique idempotency keys, retry state, and provider references. This is intentionally data-plane readiness only: no transfer is initiated until Razorpay enables Route on the merchant account and the payout account is `ACTIVE`.

Before enabling automatic settlement:

1. Complete Razorpay Route merchant activation and webhook approval.
2. Create and verify one Razorpay Linked Account for each helper; store only the provider ID and masked bank details.
3. Add transfer, settlement, reversal, and failure webhook handlers with signature verification and event idempotency.
4. Queue a `READY` transfer only after a prepaid payment is `CAPTURED`, the task is `COMPLETED`, and its fulfillment state is `EARNED`.
5. Reconcile provider transfers daily; never infer bank settlement from the internal ledger.
6. Keep `RAZORPAY_ROUTE_ENABLED=false` until sandbox, reversal, duplicate-webhook, timeout, and partial-refund tests pass.
