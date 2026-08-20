# Payments: the switch-on procedure

Everything described here is **built and shipped switched off**. Nothing below is
a code change — it is the order in which four environment variables are turned on,
and what to verify between each one.

Read this the day the Razorpay keys arrive.

---

## What exists today

| Piece | State |
|---|---|
| Razorpay collection — orders, capture, signature verify, webhooks, refunds | Complete |
| Idempotent order creation, duplicate-charge auto-refund, webhook dedup | Complete |
| React Native checkout sheet, persisted idempotency keys, cancel detection | Complete |
| Earnings ledger — earning, commission, direct-collection | Complete, **on** |
| RazorpayX payouts — contact, fund account, payout, reconciliation, webhooks | Complete, **off** |
| Partner withdraw screen | Complete, hidden |

The four switches:

```
PAYMENTS_LEDGER_ENABLED=true    # already on — records only, no money moves
PAYMENTS_ONLINE_ENABLED=false   # citizen pays in-app
PAYOUTS_ENABLED=false           # partner withdraws to a bank
PLATFORM_COMMISSION_BPS=1500    # 15%
```

---

## Why the ledger is already on

It records what happened; it moves nothing. Turning it on only on the day payouts
go live would mean every partner balance starts at zero, with no history behind it
and nothing to reconcile a dispute against.

It is worth understanding what it books, because the cash case is not obvious:

```
Online prepaid, ₹450 job:          Cash or UPI, ₹450 job:
  EARNING            +45000          EARNING            +45000
  COMMISSION          -6750          COMMISSION          -6750
                     ------          DIRECT_COLLECTION  -45000
  balance            +38250                             ------
  → we owe the partner ₹382.50       balance             -6750
                                     → the partner owes us ₹67.50
```

A cash job leaves the partner **owing** us, because they already collected the
full amount. If it booked only the earning, the balance would show a payable that
does not exist — and on the day payouts went live, it would be paid.

The app shows that negative balance as "Commission you owe", not as a negative
available balance.

---

## Step 1 — Collection (`PAYMENTS_ONLINE_ENABLED`)

**Before:**

1. Razorpay dashboard → Settings → API Keys → generate live keys.
2. Webhooks → add `https://api.mysuperhero.xyz/api/v1/payments/webhooks/razorpay`,
   subscribed to `payment.captured`, `payment.failed`, `order.paid`,
   `refund.processed`, `refund.failed`. Copy the webhook secret.
3. Set on the server:
   ```
   RAZORPAY_KEY_ID=rzp_live_...
   RAZORPAY_KEY_SECRET=...
   RAZORPAY_WEBHOOK_SECRET=...
   ```
   Restart. Leave `PAYMENTS_ONLINE_ENABLED=false` for now — with keys present and
   the flag off, nothing changes for users but the gateway is reachable.

**Then flip it, and verify in this order:**

- A ₹1 test booking end to end, from a real device.
- Cancel that booking → the refund appears in the Razorpay dashboard within a minute.
- Replay the `payment.captured` webhook from the dashboard → the payment does not
  change and the log says the event was already handled.
- Kill the app mid-checkout, reopen, retry → one order, one charge.

**Rolling back** is setting the flag to false and restarting. In-flight prepaid
tasks keep working; new bookings go back to pay-after-service.

---

## Step 2 — Payouts (`PAYOUTS_ENABLED`)

This is the one that can lose money, so it comes second and only after a
reconciled settlement by hand.

**Before:**

1. RazorpayX account, activated, KYC complete, with a funded virtual account.
2. Generate **separate** RazorpayX credentials. Do not reuse the collection key —
   an account that can only collect cannot be used to drain a balance.
3. Webhook → `https://api.mysuperhero.xyz/api/v1/payouts/webhooks/razorpayx`,
   subscribed to `payout.processed`, `payout.failed`, `payout.reversed` and
   `fund_account.validation.completed`.
4. Set on the server:
   ```
   RAZORPAYX_KEY_ID=...
   RAZORPAYX_KEY_SECRET=...
   RAZORPAYX_ACCOUNT_NUMBER=...        # the virtual account payouts are funded from
   RAZORPAYX_WEBHOOK_SECRET=...
   ```
   Restart with `PAYOUTS_ENABLED` still false and confirm the boot log shows no
   RazorpayX errors.

**Then verify bank accounts, before anyone can withdraw.**

A payout is refused unless the destination account reached `VERIFIED`, and the
only thing that gets it there is a penny drop — a ₹1 credit that comes back with
the name the bank holds the account under. Until this was built, nothing ever
moved an account off `NOT_STARTED` and `PayoutService` did not read the field at
all, so turning payouts on would have sent money to destinations nobody had ever
confirmed existed.

Verification does **not** require `PAYOUTS_ENABLED`, and should be done first —
otherwise the day you switch payouts on begins with every partner unable to
withdraw. Partners start it themselves from the withdraw screen; the polling job
and the webhook close the loop.

```sql
-- Where every partner's bank account stands.
SELECT verification_status, count(*)
FROM helper_payout_accounts
WHERE current IS TRUE
GROUP BY verification_status;
```

`MANUAL_REVIEW` means the drop reached a real account held under a **different**
name. That is the fraud case this exists to catch, and it is deliberately never
auto-verified — someone has to look. There is no Admin queue for this yet
(known-risks #26), so check the table directly:

```sql
SELECT v.id, v.helper_id, v.registered_name, v.name_match_score, a.account_holder_name
FROM payout_account_validations v
JOIN helper_payout_accounts a ON a.id = v.payout_account_id
WHERE v.status = 'MANUAL_REVIEW'
ORDER BY v.created_at DESC;
```

Each drop is a real ₹1 transfer plus a fee. Attempts are capped at 3 per account
per day and one may be in flight at a time, but there is no global spend ceiling.

**Before flipping**, reconcile once by hand:

```sql
-- What the ledger says each partner is owed.
SELECT user_id, sum(amount_paise) AS balance_paise
FROM ledger_entries
GROUP BY user_id
HAVING sum(amount_paise) <> 0
ORDER BY balance_paise DESC;
```

Compare against what you believe you owe. If they disagree, find out why before
turning payouts on — the ledger is what the payout amount is computed from.

**Then flip it, and verify:**

- One partner, one ₹100 withdrawal, watched from the app to the bank statement.
- `payout_items` shows `PROCESSED` with a UTR.
- `ledger_entries` shows one `PAYOUT` row and the balance dropped by exactly that.
- Force a failure: a deliberately wrong IFSC → status `FAILED`, and one
  `PAYOUT_REVERSAL` restoring the exact amount. Not two.

---

## The things that stop a double payment

Worth knowing, because this is the failure that cannot be undone:

1. A partial unique index allows **one** `PENDING`/`PROCESSING` payout per
   partner. A double tap loses at the database, not in a check that raced.
2. The idempotency key is generated **before** RazorpayX is called and stored on
   the row, so a retry sends the same one.
3. RazorpayX deduplicates on that key for 24 hours, so a socket timeout — which is
   indistinguishable from a failure — resolves to the payout it already made.
4. The `PAYOUT` ledger entry is written in the same transaction as the request, so
   a balance cannot be spent twice even if the provider call is repeated.

And the rule that follows from it: **a provider error is never treated as a
failure.** The item stays `PROCESSING` and `PayoutReconciliationJob` asks
RazorpayX what actually happened. Marking it failed would restore a balance while
the money was still moving.

---

## Commission

Basis points and integer arithmetic throughout, rounding in the partner's favour —
a floating-point rate gives a different answer on a different machine, and money
that depends on the machine is a reconciliation problem you find six months later.

**Changing the rate no longer needs a deploy.** Rates live in
`commission_settings` and are set through the admin API:

```bash
curl -X PUT https://api.mysuperhero.xyz/api/v1/admin/commission \
  -H 'Authorization: Bearer <super-admin-token>' \
  -H 'Content-Type: application/json' \
  -d '{"scope":"GLOBAL","commissionBps":1200,"note":"launch promo"}'
```

`PLATFORM_COMMISSION_BPS=1500` is now only the fallback, used until a GLOBAL row
exists. Scopes resolve most-specific-first: `HELPER` → `CATEGORY` → `GLOBAL` →
the env default. (`CATEGORY` is inert today — tasks carry no category field. See
known-risks #24.)

Rows are append-only and effective-dated: a change closes the current row and
opens a new one, so entries already written keep reconciling against the rate
they were booked at. The rate is also stored on each `COMMISSION` ledger row, so
a historical entry is self-describing.

Changing it affects **future** bookings only. Entries already written are history
and are never recomputed.

Revenue reports read the ledger (`commissionBetween`) rather than multiplying GMV
by a constant. They previously hardcoded `0.15` in four places, so any rate change
silently made the admin dashboard disagree with what partners were actually
charged.

---

## Monitoring, once live

```bash
# Penny drops that never resolved.
psql -c "SELECT id, helper_id, status, created_at FROM payout_account_validations
         WHERE status = 'PENDING' AND created_at < now() - interval '1 hour';"
```

```bash
# Payouts that have been in flight too long.
psql -c "SELECT id, user_id, amount_paise, status, requested_at FROM payout_items
         WHERE status IN ('PENDING','PROCESSING') AND requested_at < now() - interval '30 minutes';"
```

```bash
journalctl -u superheroo-api | grep -i "payout"
```

A partner whose balance disagrees with their expectation is answered from
`ledger_entries` — every line has a description and a task id, which is the point
of an append-only table.
