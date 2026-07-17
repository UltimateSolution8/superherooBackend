# Razorpay Payment Integration

## Scope

This phase replaces the demo wallet and pseudo-escrow with real Razorpay Standard Checkout for completed tasks.

- A citizen pays the task amount after the task reaches `COMPLETED`.
- The backend, not the app, reads the payable amount from the task.
- Razorpay collects the payment into the platform merchant account.
- A payment is considered successful only after server-side signature verification and Razorpay reports it as `captured`.
- Partners can see verified collection status. This is not a withdrawable partner bank balance.
- Partner settlement through Razorpay Route, RazorpayX, or a bank payout API is a separate phase.

Scheduled and recurring tasks use the same payment flow for each generated task after completion. Mediator-managed crew bookings default to one payment per completed helper task. Before checkout starts, the citizen can instead choose one consolidated collection for the exact sum of all completed, present helper tasks. The consolidated record is associated with the mediator and batch, but money is still collected into the platform merchant account; it is not a mediator bank payout.

## Architecture

1. The citizen taps **Pay** on a completed task.
2. The app calls `POST /api/v1/payments/tasks/{taskId}/orders` with an `Idempotency-Key`.
3. The backend locks the task, verifies ownership/completion, reads its amount, persists a payment intent, and creates a Razorpay order.
4. The app opens the native `react-native-razorpay` checkout using the returned public key and order ID.
5. Checkout returns the payment ID, order ID, and signature.
6. The app sends those fields to `POST /api/v1/payments/verify`.
7. The backend verifies the signature against its stored order ID, fetches the payment from Razorpay, captures an authorised payment if needed, and records only verified provider state.
8. Signed, idempotent webhooks reconcile success, failure, and refund changes if the app callback is interrupted.

## API

| Method | Endpoint | Access | Purpose |
|---|---|---|---|
| `POST` | `/api/v1/payments/tasks/{taskId}/orders` | Task buyer | Create/reuse a server-owned Razorpay order |
| `POST` | `/api/v1/payments/batches/{batchId}/mode` | Batch buyer | Select per-helper or consolidated mode before checkout starts |
| `POST` | `/api/v1/payments/batches/{batchId}/orders` | Batch buyer | Create/reuse a consolidated mediator-batch order |
| `POST` | `/api/v1/payments/verify` | Task buyer | Verify checkout signature and captured status |
| `GET` | `/api/v1/payments/tasks/{taskId}` | Task buyer/helper, admin, support | Latest task payment status |
| `GET` | `/api/v1/payments/batches/{batchId}/summary` | Batch buyer/mediator, admin, support | Payment mode, helper lines, and collection status |
| `GET` | `/api/v1/payments/me` | Buyer/helper/mediator | Latest 100 relevant payment records |
| `POST` | `/api/v1/payments/webhooks/razorpay` | Razorpay signature | Reconcile provider events |

## Configuration

Use secrets from the runtime environment. Never add real keys to Git or the mobile app.

```bash
RAZORPAY_KEY_ID=rzp_test_or_live_key
RAZORPAY_KEY_SECRET=server_only_secret
RAZORPAY_WEBHOOK_SECRET=independent_dashboard_webhook_secret
RATE_LIMIT_PAYMENT_ORDER_PER_MIN=20
RATE_LIMIT_PAYMENT_VERIFY_PER_MIN=40
```

The test key ID may be returned by the order endpoint because checkout requires it. The key secret and webhook secret must remain server-only.

## Razorpay Dashboard

Configure this separately in **Test Mode** and later in **Live Mode**:

1. Enable automatic payment capture.
2. Add webhook URL: `https://api.mysuperhero.xyz/api/v1/payments/webhooks/razorpay`.
3. Generate a strong, independent webhook secret and set the same value as `RAZORPAY_WEBHOOK_SECRET` on the backend server.
4. Subscribe to `payment.captured`, `payment.failed`, `order.paid`, `refund.processed`, and `refund.failed`.
5. Keep webhook delivery retries enabled and monitor failures.

## Local Verification

Backend:

```bash
cd Backend
JAVA_TOOL_OPTIONS='-Dnet.bytebuddy.experimental=true' mvn test
mvn clean package
```

Mobile:

```bash
cd 'React native new theme/ReactNative'
npm ci
npm run typecheck
npm test -- --runInBand
```

For a checkout test, run the backend with test keys, install a native Android build, complete a task, and tap **Pay**. Expo Go cannot load the Razorpay native module.

Razorpay test payment data changes over time; use the current Test Mode values shown in the Razorpay documentation/dashboard. Test success, user cancellation, provider failure, loss of network after checkout, duplicate taps, invalid signature, refund reconciliation, per-helper checkout, consolidated checkout, and attempts to mix both batch modes.

The local automated suite also validates payment-before-completion rejection, exact server-calculated batch totals, idempotent order retry, late/out-of-order callbacks, full refund state, and invalid webhook signatures. Flyway migrations `V1` through `V45` have been verified from an empty PostgreSQL 16 database.

## Security and Reliability Controls

- The client cannot choose the charge amount or currency.
- Orders are available only for completed tasks owned by the authenticated buyer.
- Idempotency keys and open-order reuse prevent duplicate order creation from repeated taps.
- The secret is never returned by any endpoint or bundled in the app.
- Signature verification uses the order ID stored by the backend.
- Provider amount, currency, and order ID are checked before state changes.
- Only `CAPTURED` and partially refunded captured payments count as paid.
- Webhook signatures are calculated from the unmodified raw request body.
- Razorpay event IDs are deduplicated; payload hashes are stored instead of raw payment payloads.
- Webhooks are the reconciliation source, while immediate provider fetch supports user-facing confirmation.
- Failed signatures never mark a payment as paid.
- Payment records are append-only attempts; retries do not overwrite historical attempts.
- A bulk payment mode becomes immutable as soon as any order attempt exists, preventing accidental mixed collection.
- A consolidated amount is calculated from completed server-side task records; the app cannot submit helper amounts.

## Go-Live Checklist

1. Complete Razorpay merchant KYC and settlement-bank verification.
2. Confirm pricing, GST treatment, invoices, who bears gateway fees, cancellation/refund policy, and support SOP.
3. Generate Live Mode API keys; do not reuse test keys.
4. Configure a separate Live Mode webhook and secret.
5. Enable auto-capture and run a low-value real transaction through payment, settlement, refund, and reconciliation.
6. Add alerts for webhook failure, authorised payments not captured, payment mismatch, duplicate captured attempts, and elevated provider latency/errors.
7. Restrict production secret access to deployment/runtime administrators and rotate exposed or shared keys.
8. Define partner payout architecture before showing a withdrawable balance. For marketplace split settlements, evaluate Razorpay Route linked accounts; otherwise use an approved bank payout API with partner KYC and beneficiary validation.

## Deferred Decisions

The following require business approval before implementation:

- Platform commission and GST calculation.
- Gateway fee absorption or pass-through.
- Refund/cancellation windows and approval roles.
- Partner payout provider, payout schedule, minimum balance, holds, disputes, and reconciliation.
- Cash fallback policy and how offline cash is reconciled without falsely marking it as an online payment.
