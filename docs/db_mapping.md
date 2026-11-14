# GFinPay database map

This describes the PostgreSQL database after migration **V28**. Amounts are rupees (`NUMERIC`), not paise. Timestamps are `TIMESTAMPTZ` (stored in UTC). Identifiers are `UUID` unless a column says otherwise. Status and type columns are plain text, not database enums, so a new value can appear when the application writes it. The lists below are the values the application writes today.

Schema is `public`. Join on the named id columns. There is no separate reporting schema.

## Read this before writing a query

**Two wallets per sales user.** `wallets.wallet_kind` is `MAIN` or `REWARDS`.

- `MAIN` is the operating float. Bill pay, money transfer, account-opening fees, and customer cash-out settlement use this wallet.
- `REWARDS` is commission. A successful service credits retailer, distributor, and hub rewards here. It is not spent on the service itself.

Retailers, distributors (`user_type = 'MASTER_DISTRIBUTOR'`), and hub admins each have a rewards wallet. Retailers and distributors also have a main wallet. Hub admins do not have a main wallet. Platform admins and auditors usually have neither.

Always filter `wallet_kind`. A join on `wallets.user_id` alone returns two rows.

**A transaction is the business event. The ledger is the money.**

`transactions.amount` is the business amount (transfer, bill, cash handed over, lead budget, withdrawal). `transactions.fee` is our charge on the main wallet. That fee is currently zero: commission is a reward, not a deduction from the float.

`transactions.wallet_id` is the wallet the operation is anchored to, usually the retailer's `MAIN` wallet. Reward credits are extra `ledger_entries` rows on `REWARDS` wallets, still pointing at the same `transaction_id`.

To see money move, use `ledger_entries`. `direction = 'DEBIT'` is money out of that wallet. `direction = 'CREDIT'` is money in. `amount` is always positive. Rows that share `posting_group` were written together.

`ledger_entries` and `activity_events` are append-only. The database rejects updates and deletes.

**Network.** A retailer’s `users.parent_id` is their distributor. Both carry `users.hub_id`. The distributor role in the database is `MASTER_DISTRIBUTOR`. The public shop code is `users.code` (for example `GFIN…`).

**External sellers are not users.** A `sales_leads` row with `sale_channel = 'EXTERNAL'` stores a `vendors.id` in `distributor_user_id` and a `vendor_affiliates.id` in `retailer_user_id`. Those columns are real `users.id` values only when `sale_channel = 'INTERNAL'`.

**Sensitive columns.** Do not export these into a shared sheet: `users.password_hash`, `user_sessions.refresh_token_hash`, anything named `pan_enc`, `account_number_enc`, `aadhaar_ref_key`, `otp_hash`, `stateresp`, `refund_stateresp`, `encdata`, `ao_sdk_token`, `paysprint_onboard_token`, `payer_account`, or document JSON that can contain identity images.

**Commission has no seeded rates.** Migration V21 deletes the original DMT, BBPS, UPI cash-out, and account-opening seed rules. A service pays a reward only after an admin saves a row in `commission_rules` for that service. The open rule is the row with `effective_to IS NULL`.

## People and organisation

| `users.user_type` | Who they are |
| --- | --- |
| `SUPER_ADMIN` | The single platform owner. No wallet. |
| `ADMIN` | Platform admin. No wallet. |
| `HUBADMIN` | Runs one hub. Rewards wallet only. |
| `AUDITOR` | Reviews wallet top-ups, activations, and withdrawals. No wallet. |
| `MASTER_DISTRIBUTOR` | Distributor. Main wallet and rewards wallet. Parent of retailers. |
| `RETAILER` | Shop. Main wallet and rewards wallet. `parent_id` is the distributor. |

`users.status` values in use: `PENDING`, `PENDING_KYC`, `PENDING_ACTIVATION`, `SUBMITTED`, `APPROVED`, `ACTIVE`, `INACTIVE`, `SUSPENDED`, `FROZEN`, `TERMINATED`, `REJECTED`. A retailer starts `PENDING`, becomes `APPROVED` when the activation payment is accepted, then `ACTIVE` when the shop is activated. `FROZEN` is also used on `wallets.status` when the till cannot transact.

### `hubs`

A city or region the network is organised under.

| Column | Type | Use |
| --- | --- | --- |
| `id` | UUID | Primary key. |
| `code` | TEXT | Short unique hub code. |
| `name` | TEXT | Hub name shown in admin. |
| `city` | TEXT | City. |
| `state` | TEXT | State. |
| `status` | TEXT | `ACTIVE` unless the hub is turned off. |
| `notes` | TEXT | Free-text note. |
| `created_by` | UUID | `users.id` of the admin who created the hub. |
| `created_at` | TIMESTAMPTZ | When the hub was created. |
| `updated_at` | TIMESTAMPTZ | When the hub row was last changed. |

### `users`

Every person who logs into GFinPay. Distributors and retailers are both rows here. External vendor staff are not.

| Column | Type | Use |
| --- | --- | --- |
| `id` | UUID | Primary key. This is the id used as retailer, distributor, or admin throughout the database. |
| `user_type` | TEXT | Role. See the table above. Only one `SUPER_ADMIN` is allowed. |
| `parent_id` | UUID | For a retailer, the distributor (`users.id`). Empty for roles that have no parent. |
| `hub_id` | UUID | Hub this person belongs to. |
| `created_by` | UUID | `users.id` of the person who created this account. |
| `full_name` | TEXT | Display name. |
| `mobile` | VARCHAR(15) | Login mobile. Unique. |
| `email` | TEXT | Unique when present. |
| `password_hash` | TEXT | **Sensitive.** Password hash. Never select this for analysis. |
| `pin_hash` | TEXT | **Sensitive.** 6-digit login PIN hash for partners (`RETAILER`, `MASTER_DISTRIBUTOR`). Added in V28. |
| `failed_login_attempts` | INT | Consecutive failed password or PIN login attempts. Reset to 0 on success. Added in V28. |
| `locked_until` | TIMESTAMPTZ | Account lockout timestamp after 5 failed attempts. Added in V28. |
| `code` | TEXT | Public party code, unique when present. Retailer and distributor codes used on sales and PaySprint (`merchant_code`). |
| `status` | TEXT | Account lifecycle. See the list above. |
| `created_at` | TIMESTAMPTZ | When the account was created. |
| `updated_at` | TIMESTAMPTZ | When the account was last changed. |

### `hub_admin_assignments`

Which hub admins are assigned to which hub. A hub admin can be assigned to more than one hub. `users.hub_id` is also set for the hub they operate in day to day.

| Column | Type | Use |
| --- | --- | --- |
| `hub_id` | UUID | Hub. Part of the primary key. |
| `user_id` | UUID | Hub admin (`users.id`, `user_type = 'HUBADMIN'`). Part of the primary key. |
| `assigned_at` | TIMESTAMPTZ | When the assignment was made. |

### `user_sessions`

One active login per user. Not useful for business reports.

| Column | Type | Use |
| --- | --- | --- |
| `id` | UUID | Primary key. |
| `user_id` | UUID | Who is logged in. |
| `refresh_token_hash` | TEXT | **Sensitive.** Hash of the refresh token. |
| `last_activity_at` | TIMESTAMPTZ | Last time this session was used. |
| `absolute_expires_at` | TIMESTAMPTZ | Hard end of the session. |
| `revoked_at` | TIMESTAMPTZ | Empty while the session is the active one. Set on logout or replacement. |
| `revoked_reason` | TEXT | Why the session ended. |
| `rotations` | INT | How many times the refresh token was rotated. |
| `ip` | TEXT | Client IP at login. |
| `user_agent` | TEXT | Browser or app identifier. |
| `created_at` | TIMESTAMPTZ | When the session started. |

### `vendors`

An external partner who sells through their own staff, not through a GFinPay distributor login.

| Column | Type | Use |
| --- | --- | --- |
| `id` | UUID | Primary key. Stored in `sales_leads.distributor_user_id` when the sale channel is `EXTERNAL`. |
| `hub_id` | UUID | Hub the vendor is attached to. |
| `full_name` | TEXT | Vendor name. |
| `mobile` | VARCHAR(15) | Unique mobile. |
| `email` | TEXT | Email. |
| `code` | TEXT | Unique vendor code. |
| `status` | TEXT | `ACTIVE` unless turned off. |
| `created_at` | TIMESTAMPTZ | Created time. |
| `updated_at` | TIMESTAMPTZ | Last change. |

### `vendor_affiliates`

One employee of a vendor. Their apply link is how an external sale starts.

| Column | Type | Use |
| --- | --- | --- |
| `id` | UUID | Primary key. Stored in `sales_leads.retailer_user_id` when the sale channel is `EXTERNAL`. |
| `vendor_id` | UUID | Parent vendor. |
| `employee_name` | TEXT | Employee name. |
| `employee_code` | TEXT | Code unique inside that vendor. |
| `affiliate_ref` | UUID | Stable affiliate reference. |
| `apply_token` | TEXT | Unique token embedded in the public apply link. |
| `gfin_code` | TEXT | Unique GFin code printed for that employee. |
| `created_at` | TIMESTAMPTZ | Created time. |

### `partner_inquiries`

Someone who asked to become a partner from the public site, before they are a user.

| Column | Type | Use |
| --- | --- | --- |
| `id` | UUID | Primary key. |
| `full_name` | TEXT | Applicant name. |
| `mobile` | VARCHAR(15) | Applicant mobile. |
| `email` | TEXT | Applicant email. |
| `city` | TEXT | City they entered. |
| `message` | TEXT | Free-text message. |
| `status` | TEXT | `OPEN`, `WITH_HUB`, `WITH_DISTRIBUTOR`, `CONVERTED`, `NOT_INTERESTED`, or `RETIRED`. |
| `hub_id` | UUID | Hub it was routed to. |
| `assigned_hub_admin` | UUID | Hub admin working it. |
| `assigned_distributor` | UUID | Distributor it was passed to. |
| `converted_user_id` | UUID | User created from this inquiry. |
| `converted_role` | TEXT | Role that user was created as. |
| `created_at` | TIMESTAMPTZ | When the inquiry arrived. |
| `updated_at` | TIMESTAMPTZ | Last status change. |

## Wallets, transactions, and the ledger

### `wallets`

One balance bucket for one user. A user can have two rows: `MAIN` and `REWARDS`.

| Column | Type | Use |
| --- | --- | --- |
| `id` | UUID | Primary key. This is `transactions.wallet_id` and `ledger_entries.wallet_id`. |
| `user_id` | UUID | Owner. Unique together with `wallet_kind`. |
| `wallet_kind` | TEXT | `MAIN` (operating float) or `REWARDS` (commission). |
| `available_balance` | NUMERIC(18,4) | Rupees that can be spent or withdrawn. Never negative. |
| `hold_balance` | NUMERIC(18,4) | Rupees reserved for a money transfer that has not finished. Never negative. |
| `version` | BIGINT | Internal lock counter. Ignore for reporting. |
| `status` | TEXT | `ACTIVE` or `FROZEN`. Frozen means the till cannot transact. |
| `is_active` | BOOLEAN | Row is in use. |
| `created_at` | TIMESTAMPTZ | When the wallet was created. |
| `updated_at` | TIMESTAMPTZ | When the balance or status last changed. |

Rewards wallets are created for existing retailers, distributors, and hub admins by V21, and for new users of those roles when they are onboarded.

### `transactions`

One business operation. Filter `txn_type` and `state` for volumes. Do not treat `amount` as a wallet movement; use `ledger_entries` for that.

| Column | Type | Use |
| --- | --- | --- |
| `id` | UUID | Primary key. |
| `txn_type` | TEXT | What happened. Values written today: `DMT` (money transfer), `DMT_AO` (PaySprint account-opening fee), `BBPS` (bill pay), `AEPS` (Aadhaar cash-out), `CASHOUT_UPI` (UPI cash-out), `FD_CARD` (FD card converted), `CREDIT_CARD`, `INSTANT_LOAN`, `PERSONAL_LOAN`, `BUSINESS_LOAN`, `SAVINGS` (lead converted), `REWARDS_TRANSFER` (rewards moved into the main wallet), `EARNINGS_WITHDRAW` (rewards paid out to a bank), `ADJUSTMENT` (admin or auditor credit or debit, including an approved wallet top-up). |
| `state` | TEXT | `INITIATED`, `HOLD`, `PENDING`, `SUCCESS`, `FAILED`, `REVERSED`. Count completed business as `SUCCESS`. `HOLD` and `PENDING` are still in flight. `REVERSED` means a success was undone. |
| `agent_user_id` | UUID | User the operation belongs to. For a shop transaction this is the retailer. |
| `wallet_id` | UUID | Anchor wallet, usually that user’s `MAIN` wallet. Reward credits still use this transaction id but post on `REWARDS` wallets. |
| `amount` | NUMERIC(18,4) | Business amount in rupees. Always greater than zero. |
| `fee` | NUMERIC(18,4) | Our fee taken from the main wallet. Currently zero. PaySprint’s own charges are stored on `dmt_transfers` or `cashout_sessions`, not here. |
| `dmt_sender_id` | UUID | Remitter, when this is a money transfer. |
| `beneficiary_id` | UUID | Beneficiary, when this is a money transfer. |
| `customer_ref` | JSONB | Extra customer reference captured with the operation. Shape varies by service. |
| `partner_code` | TEXT | Downstream partner. PaySprint operations use `PAYSPRINT`. |
| `partner_ref` | TEXT | Partner reference. Unique per `partner_code` when present. For UPI cash-out and DMT this is our numeric reference. |
| `idempotency_key_id` | UUID | Link to `idempotency_keys` when the API call was protected against a double submit. |
| `failure_reason` | TEXT | Why a failed or reversed transaction failed. |
| `state_history` | JSONB | Array of `{state, at}` objects, oldest first. |
| `created_at` | TIMESTAMPTZ | When the transaction was created. |
| `updated_at` | TIMESTAMPTZ | When the state last changed. |

Lead and FD rows (`FD_CARD`, `CREDIT_CARD`, and the other lead types) exist so a reward can be attached. They do not debit the main wallet. The amount on a lead conversion is the lead budget, and the new sell buttons store a budget of 1, so a percentage reward on those leads is a percentage of ₹1 unless the budget was entered as a real amount.

### `ledger_entries`

Append-only money movements. This is the source for a statement.

| Column | Type | Use |
| --- | --- | --- |
| `id` | BIGSERIAL | Primary key. Statement order. |
| `transaction_id` | UUID | The business event this posting belongs to. |
| `posting_group` | UUID | Groups the legs written in one posting. A rewards-to-wallet move has a debit and a credit with the same group. |
| `wallet_id` | UUID | Wallet that gained or lost the money. Join `wallets` to see `user_id` and `wallet_kind`. |
| `direction` | TEXT | `DEBIT` (out) or `CREDIT` (in). |
| `amount` | NUMERIC(18,4) | Rupees moved. Always greater than zero. |
| `narration` | TEXT | Human description, for example `DMT hold`, `Commission: retailer`, `Wallet top-up UTR …`. |
| `created_at` | TIMESTAMPTZ | When the posting was written. |

### `idempotency_keys`

Stops a repeated API call from creating a second transaction. Skip this table for business reporting.

| Column | Type | Use |
| --- | --- | --- |
| `id` | UUID | Primary key. |
| `idem_key` | TEXT | Client-supplied key. Unique per `agent_user_id`. |
| `agent_user_id` | UUID | User who sent the request. |
| `request_hash` | TEXT | Hash of the request body, so the same key cannot be reused for a different request. |
| `response_code` | SMALLINT | HTTP status saved for the replay. |
| `response_body` | JSONB | Response saved for the replay. |
| `status` | TEXT | `IN_FLIGHT` while the first call is running, then the completed status. |
| `created_at` | TIMESTAMPTZ | When the key was claimed. |
| `expires_at` | TIMESTAMPTZ | When a replay is no longer honoured. |

### `commission_rules`

The reward an admin set for one service. There is no slab in the product. Old slab columns are still on the row so existing code keeps validating, and new rows are stored with `slab_min = 0` and `slab_max = 999999999`. Ignore slabs, `total_rate_bp`, `flat_fee`, and `platform_share_pct` when explaining what someone was paid. Use `commission_earnings` for what was actually paid, and this table for the setting that applied.

The current setting for a service is:

```sql
SELECT *
  FROM commission_rules
 WHERE txn_type = 'DMT'
   AND effective_to IS NULL
 ORDER BY effective_from DESC
 LIMIT 1;
```

| Column | Type | Use |
| --- | --- | --- |
| `id` | UUID | Primary key. Referenced by `commission_earnings.rule_id`. |
| `txn_type` | TEXT | Service the rule pays. `DMT`, `BBPS`, `AEPS`, `CASHOUT_UPI`, `FD_CARD`, `CREDIT_CARD`, `INSTANT_LOAN`, `PERSONAL_LOAN`, `BUSINESS_LOAN`, `SAVINGS`. The UPI cash-out service switch is `UPI_CASHOUT`; the rule code is `CASHOUT_UPI`. |
| `slab_min` | NUMERIC(18,4) | Legacy. Not used to choose a rule. |
| `slab_max` | NUMERIC(18,4) | Legacy. Not used to choose a rule. |
| `total_rate_bp` | INT | Legacy basis points. The main-wallet fee is not calculated from this. |
| `flat_fee` | NUMERIC(18,4) | Legacy. Not the reward. |
| `retailer_share_pct` | NUMERIC(5,2) | When `commission_type = 'PERCENT'`, retailer reward is `amount * this / 100`. Not required to sum to 100 with the other percents. |
| `distributor_share_pct` | NUMERIC(5,2) | Distributor percent, same meaning. |
| `platform_share_pct` | NUMERIC(5,2) | Legacy platform percent. Current rewards do not pay a platform share. |
| `commission_type` | TEXT | `FLAT` (a fixed rupee amount per success) or `PERCENT`. Older rows may say `FIXED`. |
| `retailer_amount` | NUMERIC(18,4) | When mode is `FLAT`, rupees credited to the retailer’s rewards wallet. |
| `distributor_amount` | NUMERIC(18,4) | Flat rupees for the distributor. |
| `hub_amount` | NUMERIC(18,4) | Flat rupees for the hub admin. Added in V21. |
| `hub_share_pct` | NUMERIC(5,2) | Hub percent. Added in V21. Default 0. |
| `effective_from` | TIMESTAMPTZ | When this version started. |
| `effective_to` | TIMESTAMPTZ | Empty on the live version. Set when a newer version replaces it. |

If there is no open rule, or every share is zero, a success pays nothing. Rewards are not clawed back when a later failure happens, because they are credited only after success.

### `commission_earnings`

One row per person who received a reward on one transaction. This is the table to sum for “what did we pay”.

| Column | Type | Use |
| --- | --- | --- |
| `id` | UUID | Primary key. |
| `transaction_id` | UUID | The success that earned it. Unique together with `beneficiary_user`. |
| `rule_id` | UUID | `commission_rules.id` that was applied. |
| `beneficiary_user` | UUID | User who was credited. |
| `role_in_split` | TEXT | `RETAILER`, `DISTRIBUTOR`, or `HUB`. |
| `amount` | NUMERIC(18,4) | Rupees credited to that user’s rewards wallet. |
| `posting_group` | UUID | Ledger group of that credit. |
| `retailer_user_id` | UUID | Retailer on the deal, copied for reporting so a distributor or hub report does not need to walk `users`. |
| `distributor_user_id` | UUID | Distributor on the deal. |
| `hub_id` | UUID | Hub on the deal. |
| `created_at` | TIMESTAMPTZ | When the reward was recorded. |

The hub share is skipped when no active hub admin exists for that hub, and when the hub admin is the same user as the retailer or distributor.

### `finance_requests`

A request for an auditor to accept money in, or to pay rewards out.

| Column | Type | Use |
| --- | --- | --- |
| `id` | UUID | Primary key. |
| `kind` | TEXT | `ACTIVATION` (retailer joining fee), `WALLET_TOPUP` (add float), or `EARNINGS_WITHDRAW` (pay rewards out to a bank). |
| `retailer_user_id` | UUID | The user the request is for. Despite the name, a distributor can raise `EARNINGS_WITHDRAW` and their user id is stored here. |
| `requested_by` | UUID | User who submitted it. |
| `amount` | NUMERIC(18,4) | Rupees requested. |
| `utr` | TEXT | Bank UTR or UPI reference supplied with the proof. |
| `proof_key` | TEXT | Storage key of the payment screenshot. |
| `proof_filename` | TEXT | Original file name. |
| `proof_content_type` | TEXT | File type. |
| `remarks` | TEXT | Note from the person who submitted it. |
| `status` | TEXT | `PENDING`, `RECEIVED`, or `REJECTED`. A withdrawal marked transferred is `RECEIVED`. |
| `auditor_user_id` | UUID | Auditor who decided it. |
| `auditor_notes` | TEXT | Auditor’s note. |
| `reviewed_at` | TIMESTAMPTZ | When it was decided. |
| `wallet_txn_id` | UUID | `transactions.id` created when the decision moved money. Top-up and activation credits are `ADJUSTMENT` on `MAIN`. Withdrawal is `EARNINGS_WITHDRAW` on `REWARDS`. Activation itself does not credit a wallet; the later top-up does. |
| `collection_account_id` | UUID | `platform_bank_accounts.id`. The GFinPay account the payer sent money to, or the account a withdrawal was paid from. |
| `transfer_mode` | TEXT | `IMPS`, `NEFT`, `RTGS`, `UPI`, or `QR`. |
| `payout_account_id` | UUID | `retailer_bank_accounts.id` the withdrawal should be paid to. |
| `created_at` | TIMESTAMPTZ | Submitted at. |
| `updated_at` | TIMESTAMPTZ | Last change. |

Only one pending activation is allowed per user. Top-ups and withdrawals can have more than one pending row.

### `platform_bank_accounts`

GFinPay’s own collection accounts. Retailers pay top-ups and activation fees into one of these.

| Column | Type | Use |
| --- | --- | --- |
| `id` | UUID | Primary key. |
| `account_name` | TEXT | Account title. |
| `account_number` | TEXT | Unique account number. Treat as confidential. |
| `ifsc` | TEXT | IFSC. |
| `bank_name` | TEXT | Bank name. |
| `upi_id` | TEXT | UPI id, unique when present. |
| `qr_key` | TEXT | Storage key of the QR image. |
| `qr_filename` | TEXT | QR file name. |
| `qr_content_type` | TEXT | QR file type. |
| `active` | BOOLEAN | Shown to payers only when true. |
| `created_by` | UUID | Admin who added it. |
| `created_at` | TIMESTAMPTZ | Created time. |
| `updated_at` | TIMESTAMPTZ | Last change. |

### `retailer_bank_accounts`

Bank account a retailer or distributor registered so rewards can be paid out. Keyed by `user_id`, so distributors use the same table.

| Column | Type | Use |
| --- | --- | --- |
| `id` | UUID | Primary key. |
| `user_id` | UUID | Owner. |
| `account_name` | TEXT | Account title. |
| `account_number` | TEXT | Account number. Unique per user. Treat as confidential. |
| `ifsc` | TEXT | IFSC. |
| `bank_name` | TEXT | Bank name. |
| `active` | BOOLEAN | Can be selected for a payout when true. |
| `verification_status` | TEXT | Penny-drop verification status (`NOT_VERIFIED`, `VERIFIED`, `FAILED`). Added in V28. |
| `verified_name` | TEXT | Account holder name returned by Surepass penny-drop. Added in V28. |
| `name_match_score` | NUMERIC(5,2) | `NameMatcher` similarity score (0-100) against partner identity/business name. Added in V28. |
| `provider_ref` | TEXT | Surepass bank verification reference. Added in V28. |
| `verified_at` | TIMESTAMPTZ | When penny-drop verification succeeded. Added in V28. |
| `created_at` | TIMESTAMPTZ | Created time. |
| `updated_at` | TIMESTAMPTZ | Last change. |

## Retailer onboarding and bank eKYC

Onboarding (shop profile) and bank fingerprint checks are separate. Completing one does not reset the other.

### `agent_kyc_profiles`

One row per retailer. This is the shop profile, not the per-bank fingerprint result.

| Column | Type | Use |
| --- | --- | --- |
| `user_id` | UUID | Primary key and `users.id` of the retailer. |
| `aadhaar_ref_key` | TEXT | **Sensitive.** Reference from Aadhaar verification, not the full number. |
| `aadhaar_last4` | CHAR(4) | Last four digits only. |
| `aadhaar_verified_at` | TIMESTAMPTZ | When Aadhaar was verified. |
| `pan_enc` | TEXT | **Sensitive.** Stored PAN. Do not export. |
| `pan_verified_at` | TIMESTAMPTZ | When PAN was verified. |
| `liveness_score` | NUMERIC(5,2) | Face-match score, when a liveness check was used. |
| `liveness_passed_at` | TIMESTAMPTZ | When liveness passed. |
| `shop_name` | TEXT | Shop name. |
| `shop_address` | JSONB | Shop address fields. |
| `gstin` | VARCHAR(15) | GSTIN, when provided. |
| `gstin_verified_at` | TIMESTAMPTZ | When GSTIN was verified. |
| `ovd_type` | TEXT | Officially valid document type captured for the shop. |
| `kyc_status` | TEXT | Onboarding status. `PENDING` or `COMPLETED`. Older `VERIFIED` rows were rewritten to `COMPLETED`, and `NOT_STARTED` to `PENDING`. Redoing a bank check does not change this. |
| `bank_status` | TEXT | Summary of bank fingerprint checks. `VERIFIED` only when every required bank row is `SUCCESS`, otherwise `PENDING`. Added in V15. |
| `rejection_reason` | TEXT | Why onboarding was rejected, when it was. |
| `documents` | JSONB | **Sensitive.** Uploaded document metadata. Default `[]`. |
| `paysprint_onboard_issued_at` | TIMESTAMPTZ | When a PaySprint onboard link was issued. |
| `paysprint_onboard_token` | TEXT | **Sensitive.** Token on that link. Unique when present. |
| `paysprint_redirect_url` | TEXT | Where PaySprint sent the retailer after onboard. |
| `identity_status` | TEXT | Self-service / reverify identity status (`NOT_VERIFIED`, `VERIFIED`, `NEEDS_REVIEW`, `REJECTED`). Added in V28. |
| `identity_verified_at` | TIMESTAMPTZ | When self-service / reverify identity completed. Added in V28. |
| `identity_details` | JSONB | Verified DigiLocker Aadhaar, PAN, and selfie summary (never stores full Aadhaar or PAN). Added in V28. |
| `pan_last4` | CHAR(4) | Last four characters of verified PAN. Added in V28. |
| `selfie_key` | TEXT | Spaces object key of the live selfie snapshot. Added in V28. |
| `bank_verification_status` | TEXT | `VERIFIED` when the partner has at least one active penny-drop verified bank account, else `NOT_VERIFIED`. Added in V28. |
| `business_status` | TEXT | Business registration lifecycle (`NOT_VERIFIED`, `DETAILS_CAPTURED`, `UNDER_REVIEW`, `BUSINESS_VERIFIED`, `REJECTED`). |
| `business_details` | JSONB | Entity type, legal/trade name, GSTIN/Udyam/CIN registry verification metadata. |
| `business_reviewed_by` | UUID | Admin who approved or rejected the business registration. Added in V28. |
| `business_reviewed_at` | TIMESTAMPTZ | When business registration was reviewed. Added in V28. |
| `business_review_note` | TEXT | Admin review note or rejection reason. Added in V28. |
| `agreement_status` | TEXT | `NOT_SIGNED` or `SIGNED`. |
| `agreement_details` | JSONB | E-sign metadata (`version`, `signatoryName`, `providerRef`). |
| `created_at` | TIMESTAMPTZ | Profile created. |
| `updated_at` | TIMESTAMPTZ | Profile last changed. |

### `onboarding_invites`

Single-use, mobile-bound onboarding invitations generated by Admins, Hub Admins, or Distributors (added in V28).

| Column | Type | Use |
| --- | --- | --- |
| `id` | UUID | Primary key. |
| `purpose` | TEXT | `ONBOARD` or `REVERIFY`. |
| `role` | TEXT | `RETAILER` or `MASTER_DISTRIBUTOR`. |
| `full_name` | TEXT | Partner name entered by inviter. |
| `mobile` | VARCHAR(15) | Bound 10-digit mobile number. |
| `email` | TEXT | Optional email. |
| `parent_id` | UUID | Distributor (`users.id`) when `role = 'RETAILER'`. |
| `hub_id` | UUID | Hub (`hubs.id`). |
| `user_id` | UUID | Created `users.id` once `CREDENTIALS_SET` completes. |
| `token_hash` | TEXT | **Sensitive.** SHA-256 hash of the invite link token. |
| `session_hash` | TEXT | **Sensitive.** SHA-256 hash of the post-OTP `X-Onboarding-Session` token. |
| `session_expires_at` | TIMESTAMPTZ | Expiry of the post-OTP session token. |
| `status` | TEXT | `SENT`, `IN_PROGRESS`, `COMPLETED`, `EXPIRED`, `REVOKED`. |
| `expires_at` | TIMESTAMPTZ | Link expiry (default 7 days). |
| `created_by` | UUID | Staff/distributor `users.id` who generated the link. |
| `created_at` | TIMESTAMPTZ | Created time. |
| `updated_at` | TIMESTAMPTZ | Last change. |

### `onboarding_journeys` and `onboarding_journey_events`

`onboarding_journeys` tracks the current step (`LINK_SENT`, `LINK_OPENED`, `PHONE_VERIFIED`, `LOCATION_CAPTURED`, `AADHAAR_VERIFIED`, `PAN_VERIFIED`, `SELFIE_VERIFIED`, `CREDENTIALS_SET`, `FIRST_LOGIN`, `ACTIVATION_SUBMITTED`, `ACTIVATION_APPROVED`, `ACTIVATED`, `BANK_VERIFIED`, `BUSINESS_SUBMITTED`, `BUSINESS_VERIFIED`), status (`IN_PROGRESS`, `NEEDS_REVIEW`, `ABANDONED`, `COMPLETED`, `EXPIRED`, `REVOKED`), failure counters, and intermediate identity state. `onboarding_journey_events` is an append-only audit trail protected by a `BEFORE UPDATE OR DELETE` trigger (added in V28).

### `otp_challenges`

HMAC-SHA256 hashed 6-digit OTP challenges for `ONBOARD`, `REVERIFY`, and `RESET_CREDENTIALS` with 5-minute TTL, 60-second resend cooldown, and 5 max attempts (added in V28).

### `paysprint_bank_ekyc`

One row per retailer per PaySprint bank pipe. Primary key is `(user_id, bank_name)`.

`bank_name` is `bank2` for the money-transfer check and `bank6` for the UPI cash-out check. Cash-out requires `bank6` with `status = 'SUCCESS'`. Bank2 is always required. Bank6 is required when the UPI cash-out service is enabled.

| Column | Type | Use |
| --- | --- | --- |
| `user_id` | UUID | Retailer. |
| `bank_name` | TEXT | `bank2` or `bank6`. |
| `status` | TEXT | `PENDING` (show the scan form) or `SUCCESS` (show the done card). An admin redo sets a success row back to `PENDING` without clearing `is_dmt_active`. |
| `completed_at` | TIMESTAMPTZ | When this bank check succeeded. Cleared on redo. |
| `last_response_code` | TEXT | Last PaySprint response code. |
| `last_message` | TEXT | Last PaySprint or operator message. A redo sets this to a scan-again note. |
| `attempts` | INT | How many scans were submitted. |
| `last_attempt_at` | TIMESTAMPTZ | When the last scan was submitted. |
| `is_dmt_active` | BOOLEAN | True when a successful scan asked PaySprint to activate DMT (`is_casa = 1`). Default false. Old scans were sent as 0, so they stay false until a later scan with DMT activation succeeds. A later scan with the box unchecked does not turn a true flag off. The retailer can transfer only when any of their rows is true. Added in V18. |
| `created_at` | TIMESTAMPTZ | Row created. |
| `updated_at` | TIMESTAMPTZ | Row last changed. |

## Domestic money transfer

A transfer needs an active bank2/bank6 flag (`paysprint_bank_ekyc.is_dmt_active`), an active remitter, an active beneficiary bank from `dmt_bank_list`, and a hold on the retailer’s main wallet.

### `dmt_bank_list`

Banks a retailer can send money to. Loaded by an admin from the DMT bank spreadsheet or added one at a time. Not seeded.

| Column | Type | Use |
| --- | --- | --- |
| `id` | UUID | Primary key. |
| `bank_id` | TEXT | PaySprint bank id. Unique. Numeric text, not always contiguous. |
| `bank_name` | TEXT | Bank name shown in the picker. |
| `active` | BOOLEAN | Retailers only see active banks. A bulk upload updates the name and does not turn an inactive bank back on. New rows start active. |
| `created_at` | TIMESTAMPTZ | Created time. |
| `updated_at` | TIMESTAMPTZ | Last change. |

### `dmt_senders`

The remitter: the customer whose mobile is sending the money. The same mobile can exist once per retailer, not once for the whole platform.

| Column | Type | Use |
| --- | --- | --- |
| `id` | UUID | Primary key. |
| `mobile` | VARCHAR(15) | Remitter mobile. Unique together with `registered_by`. |
| `full_name` | TEXT | Name, when PaySprint has returned one. May be empty after a lookup that only returned a link. |
| `kyc_level` | TEXT | Older local KYC label. Default `MIN_KYC`. PaySprint is the source of truth for whether the remitter can send; see `provider_status`. |
| `partner_sender_ref` | TEXT | Older partner reference. |
| `registered_by` | UUID | Retailer who looked this remitter up. |
| `ovd_type` | TEXT | Older local document type. The current DMT flow does not collect this. |
| `ovd_last4` | CHAR(4) | Last four of that document. |
| `address_line` | TEXT | Older local address. |
| `pan_last4` | CHAR(4) | Last four of PAN, when captured locally. |
| `mobile_verified_at` | TIMESTAMPTZ | When a local mobile OTP was verified. |
| `ovd_captured_at` | TIMESTAMPTZ | When a local document was captured. |
| `provider_status` | TEXT | PaySprint remitter state. Transfers require `ACTIVE`. |
| `provider_limit` | NUMERIC(18,4) | Remaining send limit PaySprint returned, in rupees. |
| `provider_checked_at` | TIMESTAMPTZ | When we last asked PaySprint about this remitter. |
| `ao_url` | TEXT | Account-opening URL, when PaySprint says the remitter must open an account first. |
| `ao_sdk_token` | TEXT | **Sensitive.** Token that goes with that URL. |
| `ao_account_no` | TEXT | Account number PaySprint reported after account opening. Treat as confidential. |
| `ao_account_status` | TEXT | Account status from the account-opening callback. |
| `ao_access_type` | TEXT | Access type from that callback. |
| `ao_verification_type` | TEXT | Verification type from that callback. |
| `ao_refid` | TEXT | Our reference for the account-opening fee debit. |
| `created_at` | TIMESTAMPTZ | When this retailer first looked the mobile up. |

### `dmt_beneficiaries`

A person the remitter can send money to. A delete sets `deleted_at`; the row stays.

| Column | Type | Use |
| --- | --- | --- |
| `id` | UUID | Primary key. |
| `sender_id` | UUID | Remitter. |
| `name` | TEXT | Name entered or returned. |
| `account_number_enc` | TEXT | **Sensitive.** Full account number, encoded, only when we collected it at add time. Empty when PaySprint’s list returned a masked number only. |
| `account_last4` | CHAR(4) | Last four. `XXXX` style fallbacks are not used; unknown accounts still store four characters. |
| `ifsc` | VARCHAR(11) | IFSC. If PaySprint did not return one, the stored fallback is `XXXXXXXXXXX`. |
| `partner_bene_ref` | TEXT | Older partner reference. |
| `verified_at` | TIMESTAMPTZ | When a name-verification call succeeded. |
| `provider_bank_id` | TEXT | `dmt_bank_list.bank_id` chosen for this beneficiary. |
| `provider_bene_id` | TEXT | PaySprint beneficiary id. Unique per sender among rows that are not deleted. |
| `account_masked` | TEXT | Masked account as PaySprint shows it. |
| `verified_name` | TEXT | Name returned by verification. |
| `deleted_at` | TIMESTAMPTZ | Set when the beneficiary was removed. |
| `created_at` | TIMESTAMPTZ | When the beneficiary was added. |

### `dmt_bene_otp_sessions`

Short-lived OTP session while a beneficiary is being added. Expires in minutes. Not a transfer.

| Column | Type | Use |
| --- | --- | --- |
| `id` | UUID | Primary key. Supplied by the application. |
| `sender_id` | UUID | Remitter. |
| `agent_user_id` | UUID | Retailer doing the add. |
| `name` | TEXT | Beneficiary name being added. |
| `bank_id` | TEXT | PaySprint bank id. |
| `account_number_enc` | TEXT | **Sensitive.** Account number being added. |
| `ifsc` | VARCHAR(11) | IFSC being added. |
| `stateresp` | TEXT | **Sensitive.** PaySprint state token required to confirm the OTP. |
| `created_at` | TIMESTAMPTZ | When the OTP was sent. |
| `expires_at` | TIMESTAMPTZ | When the OTP can no longer be used. |
| `consumed_at` | TIMESTAMPTZ | When the beneficiary was created from this OTP. |

### `dmt_txn_otp_sessions`

Short-lived OTP session for one transfer. Created when the transfer OTP is sent. Nothing is debited until the retailer submits the OTP and a `dmt_transfers` row is created.

| Column | Type | Use |
| --- | --- | --- |
| `id` | UUID | Primary key. Supplied by the application. |
| `sender_id` | UUID | Remitter. |
| `agent_user_id` | UUID | Retailer. |
| `beneficiary_id` | UUID | Beneficiary being paid. |
| `provider_bene_id` | TEXT | PaySprint beneficiary id copied onto the session. |
| `amount` | NUMERIC(18,4) | Rupees to send. |
| `fee` | NUMERIC(18,4) | Our fee quoted with the OTP. Currently zero. |
| `txn_type` | TEXT | Rail inside DMT: `IMPS` or `NEFT`. This is not `transactions.txn_type` (that one is `DMT`). |
| `reference_id` | TEXT | Our reference, taken from `dmt_reference_seq`. Unique. |
| `stateresp` | TEXT | **Sensitive.** PaySprint token required to process the OTP. |
| `created_at` | TIMESTAMPTZ | When the OTP was sent. |
| `expires_at` | TIMESTAMPTZ | About 10 minutes after `created_at`. |
| `consumed_at` | TIMESTAMPTZ | When the transfer was submitted. |

### `dmt_transfers`

The DMT attempt after the OTP was accepted. One row per `transactions` row of type `DMT`.

| Column | Type | Use |
| --- | --- | --- |
| `id` | UUID | Primary key. Supplied by the application. |
| `transaction_id` | UUID | Matching `transactions` row. Unique. |
| `agent_user_id` | UUID | Retailer. |
| `sender_id` | UUID | Remitter. |
| `beneficiary_id` | UUID | Beneficiary. |
| `reference_id` | TEXT | Our reference sent to PaySprint. Unique. Same value as `transactions.partner_ref`. |
| `amount` | NUMERIC(18,4) | Rupees sent. |
| `fee` | NUMERIC(18,4) | Our fee. Currently zero. The main wallet hold is `amount + fee`. |
| `txn_type` | TEXT | `IMPS` or `NEFT`. |
| `provider_ackno` | TEXT | PaySprint acknowledgement number. Required to claim a refund. |
| `provider_utr` | TEXT | Bank UTR when PaySprint returns one. |
| `provider_txn_status` | TEXT | Raw PaySprint `txn_status`. `1` success, `0` failed and refunded, `2` in process, `3` sent to bank, `4` on hold, `5` failed and a refund must be claimed. |
| `provider_charge` | NUMERIC(18,4) | PaySprint customer charge, stored for reconciliation. Not debited again by us. |
| `provider_gst` | NUMERIC(18,4) | PaySprint GST, for reconciliation. |
| `provider_tds` | NUMERIC(18,4) | PaySprint TDS, for reconciliation. |
| `provider_commission` | NUMERIC(18,4) | PaySprint’s commission figure, for reconciliation. Our reward is `commission_earnings`, not this column. |
| `provider_bc_share` | NUMERIC(18,4) | PaySprint business-correspondent share, for reconciliation. |
| `bene_name` | TEXT | Beneficiary name copied onto the receipt. |
| `account_masked` | TEXT | Masked account copied onto the receipt. |
| `npci_code` | TEXT | NPCI response code when present. |
| `status` | TEXT | Our status: `HOLD`, `PENDING`, `IN_PROCESS`, `SENT`, `ON_HOLD`, `SUCCESS`, `FAILED`, `REFUNDED`. Open rows are the first five. |
| `refund_state` | TEXT | `NONE`, `AUTO_REFUNDED` (PaySprint status 0: we credited the main wallet back), `CLAIM_REQUIRED` (status 5: hold stays until the retailer claims), `REFUNDED` (claim succeeded). |
| `refund_stateresp` | TEXT | **Sensitive.** Token from the refund OTP. |
| `err_msg` | TEXT | Failure text. |
| `created_at` | TIMESTAMPTZ | When the hold was placed. |
| `updated_at` | TIMESTAMPTZ | Last status change. |

Account-opening fee debits are not in this table. They are `transactions` with `txn_type = 'DMT_AO'`, amount equal to the callback amount (PaySprint’s sample is ₹25), fee zero, and no reward.

### `customer_otp_challenges`

Older local OTP for a remitter, from before PaySprint owned the DMT journey. The current transfer flow does not write this table.

| Column | Type | Use |
| --- | --- | --- |
| `id` | UUID | Primary key. |
| `sender_id` | UUID | Remitter. |
| `purpose` | TEXT | Why the OTP was sent. |
| `otp_hash` | TEXT | **Sensitive.** Hash of the OTP. |
| `expires_at` | TIMESTAMPTZ | Expiry. |
| `verified_at` | TIMESTAMPTZ | When it was verified. |
| `created_at` | TIMESTAMPTZ | When it was sent. |

### Sequences used by transfers

These are not tables. `dmt_reference_seq` starts at `100000001` and supplies `dmt_transfers.reference_id` and account-opening ref ids. `upi_cashout_refid_seq` starts at `100000001` and supplies `cashout_sessions.provider_refid`.

## Cash-out and bills

### `cashout_sessions`

One UPI cash-out at a shop. The customer pays a PaySprint QR. On payment success the retailer’s main wallet is credited, then a reward is credited if a `CASHOUT_UPI` rule exists. The retailer marks cash handed over separately.

| Column | Type | Use |
| --- | --- | --- |
| `id` | UUID | Primary key. |
| `transaction_id` | UUID | Matching `transactions` row (`txn_type = 'CASHOUT_UPI'`). Unique. |
| `agent_user_id` | UUID | Retailer. |
| `channel` | TEXT | `UPI_QR` for the current PaySprint flow. Older rows may be an Aadhaar channel. |
| `amount` | NUMERIC(18,4) | Rupees the customer is paying. |
| `upi_qr_payload` | TEXT | QR string, when we generated one locally. The current flow opens a PaySprint page instead. |
| `upi_collect_ref` | TEXT | UPI collect reference, when used. |
| `aadhaar_last4` | CHAR(4) | Last four, for an Aadhaar cash-out. |
| `aeps_bank_iin` | VARCHAR(6) | Bank IIN for Aadhaar cash-out. |
| `aeps_rrn` | TEXT | RRN for Aadhaar cash-out. |
| `status` | TEXT | `INITIATED` (PaySprint has started, no QR yet), `AWAITING_PAYMENT` (QR is up), `PAID` (customer paid, main wallet credited), `CASH_DISPENSED` (retailer confirmed cash was handed over), `FAILED`, `EXPIRED`. |
| `expires_at` | TIMESTAMPTZ | When an unpaid session expires. |
| `paid_at` | TIMESTAMPTZ | When PaySprint reported payment. |
| `dispensed_at` | TIMESTAMPTZ | When the retailer confirmed cash was handed over. |
| `customer_name` | TEXT | Customer name. |
| `customer_mobile` | VARCHAR(15) | Customer mobile. A repeat callback with the same retailer, amount, and mobile while `INITIATED` is the same cash-out, not a new one. |
| `provider_refid` | BIGINT | Our numeric reference. Unique when present. |
| `ackno` | TEXT | PaySprint acknowledgement. |
| `bank_rrn` | TEXT | Bank RRN of the customer’s payment. |
| `payer_va` | TEXT | Payer UPI id. |
| `payer_name` | TEXT | Payer name. |
| `payer_account` | TEXT | **Sensitive.** Payer account. |
| `payer_ifsc` | TEXT | Payer IFSC. |
| `provider_comm` | NUMERIC(18,4) | PaySprint commission figure, for reconciliation. |
| `provider_tds` | NUMERIC(18,4) | PaySprint TDS, for reconciliation. |
| `latitude` | TEXT | Device latitude from the callback. |
| `longitude` | TEXT | Device longitude from the callback. |
| `device_id` | TEXT | Device id from the callback. |
| `err_code` | TEXT | PaySprint error code. |
| `err_msg` | TEXT | PaySprint error text. |
| `provider_initiated_at` | TIMESTAMPTZ | When PaySprint sent the initiate callback. |
| `created_at` | TIMESTAMPTZ | When we created the session. |

### `bill_operators`

Biller catalog for BBPS. Seeded with electricity, water, gas, broadband, LPG, credit-card, loan, insurance, education, and municipal billers. `partner_id` is the biller code sent to the partner, not our user id.

| Column | Type | Use |
| --- | --- | --- |
| `id` | SERIAL | Primary key. `bill_payments.operator_id` matches this. There is no foreign key. |
| `partner_id` | TEXT | Partner biller code. Unique. |
| `name` | TEXT | Biller name. |
| `category` | TEXT | `ELECTRICITY`, `WATER`, `GAS`, `BROADBAND`, `LPG`, `CREDIT_CARD`, `LOAN`, `INSURANCE`, `EDUCATION`, `MUNICIPAL`, and any later category. |
| `txn_type` | TEXT | Transaction type to raise. Seeded rows use `BBPS`. |
| `view_bill` | BOOLEAN | Whether the UI fetches the bill before payment. |
| `consumer_label` | TEXT | Label for the consumer-number field. |
| `regex` | TEXT | Pattern the consumer number must match. |
| `enabled` | BOOLEAN | Shown to retailers when true. |

### `bill_payments`

One bill payment, linked to a `transactions` row of type `BBPS`.

| Column | Type | Use |
| --- | --- | --- |
| `id` | UUID | Primary key. |
| `transaction_id` | UUID | The wallet transaction. |
| `operator_id` | INT | `bill_operators.id`. |
| `category_id` | INT | Partner category id, when the partner sent one. |
| `consumer_number` | TEXT | Consumer, account, or policy number paid. |
| `customer_mobile` | VARCHAR(15) | Customer mobile, when collected. |
| `customer_name` | TEXT | Customer name, when collected. |
| `bill_amount` | NUMERIC(18,4) | Bill amount returned by the biller. |
| `due_date` | DATE | Due date returned by the biller. |
| `bill_fetch` | JSONB | Raw bill-fetch payload. |
| `partner_txn_id` | TEXT | Partner transaction id. |
| `created_at` | TIMESTAMPTZ | When the payment row was stored. |

## Customers and sales

### `catalog_categories`

Product families a retailer can sell.

| Column | Type | Use |
| --- | --- | --- |
| `code` | TEXT | Primary key. `CREDIT_CARD`, `FD_CARD`, `INSTANT_LOAN`, `PERSONAL_LOAN`, `BUSINESS_LOAN`, `SAVINGS`. |
| `name` | TEXT | Display name. |
| `sort_order` | INT | Display order. |
| `eligibility_mode` | TEXT | How eligibility is decided. Seeded as `MANUAL`. |
| `payout_hint` | TEXT | Marketing text about the payout. Not the live commission rule. |
| `created_at` | TIMESTAMPTZ | Created time. |

Whether a lead category is on sale is `platform_services.enabled` for the same code, not this table. `FD_CARD` is controlled by `fd_providers`, not by `platform_services`.

### `catalog_items`

A specific product inside a category.

| Column | Type | Use |
| --- | --- | --- |
| `id` | UUID | Primary key. `sales_leads.catalog_item_id`. |
| `code` | TEXT | Unique item code. |
| `category_code` | TEXT | Parent category. |
| `name` | TEXT | Display name. |
| `provider` | TEXT | `PAYSPRINT`, `ZET`, or `GROWMORE`. |
| `rail` | TEXT | How the application starts the journey. `PAYSPRINT_LEAD` (credit card, loans, savings), `PAYSPRINT_FD`, `ZET_LINK`, `GROWMORE_LINK`. |
| `external_product` | TEXT | Code sent to PaySprint: `CC`, `IL`, `PL`, `BL`, `SA`, or `FD`. |
| `product_key` | TEXT | Internal product key. FD providers use `SBM`, `IOB`, `DCB`. |
| `min_budget` | NUMERIC(18,2) | Minimum budget for eligibility. PaySprint lead rails do not enforce this. FD still can. |
| `apply_url` | TEXT | Fixed partner URL for link-based FD products. `{ref}` is replaced with the lead reference. |
| `active` | BOOLEAN | Can be sold when true. |
| `payout_hint` | TEXT | Marketing text. Not the live reward. |
| `sort_order` | INT | Display order inside the category. |
| `created_at` | TIMESTAMPTZ | Created time. |

### `customers`

A person a retailer, distributor, or vendor is selling to. One row per mobile for the whole platform.

| Column | Type | Use |
| --- | --- | --- |
| `id` | UUID | Primary key. |
| `retailer_user_id` | UUID | Internal retailer who owns the customer. Empty for a vendor-only customer. |
| `vendor_id` | UUID | Vendor, when the customer came from an external seller. |
| `affiliate_id` | UUID | Vendor employee who captured them. |
| `full_name` | TEXT | Customer name. |
| `mobile` | VARCHAR(15) | Unique mobile. |
| `email` | TEXT | Required before a PaySprint lead link is created. |
| `city` | TEXT | City. |
| `state` | TEXT | State. |
| `pincode` | VARCHAR(10) | PIN code. |
| `employment_type` | TEXT | Employment, used for eligibility. |
| `monthly_income` | NUMERIC(18,4) | Monthly income in rupees. |
| `ekyc_status` | TEXT | Customer eKYC. Starts `NOT_STARTED`. |
| `ekyc_verified_at` | TIMESTAMPTZ | When customer eKYC completed. |
| `ovd_type` | TEXT | Document type. |
| `ovd_last4` | VARCHAR(4) | Last four of that document. |
| `created_by_role` | TEXT | Role of the person who created the customer. |
| `created_by_code` | TEXT | Their public code at the time. |
| `created_at` | TIMESTAMPTZ | Created time. |
| `updated_at` | TIMESTAMPTZ | Last change. |

### `customer_category_eligibility`

Latest eligibility result for one customer and one product family.

| Column | Type | Use |
| --- | --- | --- |
| `customer_id` | UUID | Customer. Part of the primary key. |
| `category_code` | TEXT | `catalog_categories.code`. Part of the primary key. |
| `status` | TEXT | `PENDING`, `ELIGIBLE`, or `INELIGIBLE`. |
| `reason` | TEXT | Why they were eligible or not. |
| `checked_at` | TIMESTAMPTZ | When the check was saved. |

### `sales_leads`

One share link and its outcome. This is the sales fact table.

| Column | Type | Use |
| --- | --- | --- |
| `id` | UUID | Primary key. |
| `customer_id` | UUID | Customer. |
| `catalog_item_id` | UUID | Product. |
| `retailer_user_id` | UUID | Internal retailer, or, when `sale_channel = 'EXTERNAL'`, a `vendor_affiliates.id`. |
| `distributor_user_id` | UUID | Internal distributor, or, when `EXTERNAL`, a `vendors.id`. |
| `state` | TEXT | `LINK_CREATED`, `OPENED`, `IN_PROGRESS`, `CONVERTED`, `ACTIVATED`, `REJECTED`, `EXPIRED`, `FAILED`. A reward is paid when an internal lead becomes `CONVERTED`. Open pipeline is the first three. |
| `budget` | NUMERIC(18,2) | Amount used as the base for a percentage reward. The current sell buttons store `1`. |
| `sale_type` | TEXT | Category code, copied so reports do not need the catalog. Same values as `catalog_categories.code`. |
| `sale_provider` | TEXT | Provider code, copied from the catalog item. |
| `sale_channel` | TEXT | `INTERNAL` (our retailer) or `EXTERNAL` (vendor employee). |
| `hub_id` | UUID | Hub copied onto the lead. |
| `provider_refid` | TEXT | Reference we sent to PaySprint. Use this to match a callback or a status check. |
| `link_token` | TEXT | Unique token in the public `/apply/{token}` URL. |
| `payment_link_url` | TEXT | URL returned by PaySprint or built for an FD link. |
| `encdata` | TEXT | **Sensitive.** Encrypted payload for the partner form. |
| `link_opened_at` | TIMESTAMPTZ | When the customer opened the link. |
| `partner_status` | TEXT | Last status string from PaySprint. |
| `partner_status_at` | TIMESTAMPTZ | When that status was saved. |
| `partner_user_id` | TEXT | Customer id on the partner side, when they send one. |
| `partner_snapshot` | JSONB | Last raw status payload. |
| `distributor_code` | TEXT | Distributor or vendor code at creation. |
| `distributor_name` | TEXT | Distributor or vendor name at creation. |
| `retailer_code` | TEXT | Retailer or employee code at creation. |
| `retailer_name` | TEXT | Retailer or employee name at creation. |
| `hub_name` | TEXT | Hub name at creation. |
| `product_code` | TEXT | Catalog item code at creation. |
| `state_history` | JSONB | Array of `{state, at}` objects. |
| `created_at` | TIMESTAMPTZ | When the link was created. |
| `updated_at` | TIMESTAMPTZ | Last change. |

An internal customer can have only one open lead (`LINK_CREATED`, `OPENED`, `IN_PROGRESS`) per catalog item. Names on this row are a snapshot. For the current name, join `users` or `customers`.

### `platform_services`

On/off switches for shop services, and the list an admin sets commission against. FD card is not a row here. Its reward rule uses `txn_type = 'FD_CARD'` in `commission_rules`, and its on/off switch is `fd_providers.enabled`.

| Column | Type | Use |
| --- | --- | --- |
| `code` | TEXT | Primary key. `DMT`, `AEPS`, `UPI_CASHOUT`, `BBPS`, `LEAD_GEN`, `CREDIT_CARD`, `INSTANT_LOAN`, `PERSONAL_LOAN`, `BUSINESS_LOAN`, `SAVINGS`. |
| `name` | TEXT | Label in admin. |
| `description` | TEXT | What the service does. Lead rows say the reward is credited when the lead converts. |
| `enabled` | BOOLEAN | Retailers see the service only when true. `LEAD_GEN` was turned off in V22. The five lead codes replaced it and can be switched one by one. They started in whatever state `LEAD_GEN` was in. |
| `sort_order` | INT | Display order. Lead categories are 6 through 10. |
| `updated_at` | TIMESTAMPTZ | Last switch. |

## FD card routing

FD commission in the current product is the single `commission_rules` row with `txn_type = 'FD_CARD'`. These three tables decide which partner receives the application, and keep the older per-provider tier table.

### `fd_providers`

| Column | Type | Use |
| --- | --- | --- |
| `code` | TEXT | Primary key. Seeded `ZET`, `PAYSPRINT`, `GROWMORE`. |
| `name` | TEXT | Display name. |
| `enabled` | BOOLEAN | This provider can receive FD applications when true. |
| `novu` | BOOLEAN | Whether the journey is a Novu-style handoff. |
| `fallback_rank` | INT | Order used when more than one provider can take a budget. Lower is earlier. |
| `commission_txn_type` | TEXT | Older per-provider commission code (`FD_ZET`, `FD_PAYSPRINT`, `FD_GROWMORE`). The live reward path does not read this. It uses `FD_CARD`. |
| `updated_at` | TIMESTAMPTZ | Last change. |

### `fd_budget_bands`

Which provider is preferred for a budget range. This is routing, not commission.

| Column | Type | Use |
| --- | --- | --- |
| `id` | UUID | Primary key. |
| `slab_min` | NUMERIC(18,2) | Lowest budget in rupees for this band. |
| `slab_max` | NUMERIC(18,2) | Highest budget. Must be at least `slab_min`. |
| `preferred_provider` | TEXT | `fd_providers.code`. One band per provider. |
| `preference_rank` | INT | Unique rank. Lower is tried first. |

### `fd_commission_tiers`

Older per-provider FD payout tiers. The conversion reward no longer reads this table. Leave it out of payout reports. Use `commission_rules` and `commission_earnings`.

| Column | Type | Use |
| --- | --- | --- |
| `id` | UUID | Primary key. |
| `provider_code` | TEXT | `fd_providers.code`. |
| `cards_min` | INT | Lowest converted-card count this tier covered. |
| `cards_max` | INT | Highest count. |
| `fixed_amount_per_card` | NUMERIC(18,2) | Rupees per card in that older model. |
| `retailer_share_pct` | NUMERIC(5,2) | Retailer percent. The three percents had to sum to 100. |
| `distributor_share_pct` | NUMERIC(5,2) | Distributor percent. |
| `platform_share_pct` | NUMERIC(5,2) | Platform percent. |
| `created_at` | TIMESTAMPTZ | When the tier was saved. |

## Reconciliation, callbacks, and audit

### `recon_batches`

One uploaded partner MIS file for a business date, used to match FD and lead sales.

| Column | Type | Use |
| --- | --- | --- |
| `id` | UUID | Primary key. |
| `provider` | TEXT | Which partner file this is. |
| `business_date` | DATE | The date the file is for. |
| `mis_file_uri` | TEXT | Where the file was stored. |
| `total_rows` | INT | Rows in the file. |
| `matched_rows` | INT | Rows matched to a lead. |
| `unidentified_rows` | INT | Rows that could not be matched. |
| `status` | TEXT | `RUNNING` until `finished_at` is set. |
| `status_counts` | JSONB | Counts by partner status. |
| `started_at` | TIMESTAMPTZ | When the import started. |
| `finished_at` | TIMESTAMPTZ | When the import finished. |

### `recon_rows`

One line from that file.

| Column | Type | Use |
| --- | --- | --- |
| `id` | UUID | Primary key. |
| `batch_id` | UUID | Parent batch. |
| `customer_mobile` | TEXT | Mobile from the file. |
| `customer_name` | TEXT | Name from the file. |
| `lead_id` | UUID | `sales_leads.id` when matched. |
| `retailer_user_id` | UUID | Retailer copied from the match. Not a declared foreign key. |
| `distributor_user_id` | UUID | Distributor copied from the match. Not a declared foreign key. |
| `hub_id` | UUID | Hub. |
| `retailer_label` | TEXT | Retailer text from the file or the match. |
| `distributor_label` | TEXT | Distributor text from the file or the match. |
| `hub_label` | TEXT | Hub text. |
| `identified` | BOOLEAN | True when the row was matched to our network. |
| `current_status` | TEXT | Status on that MIS line. |
| `payload` | JSONB | The raw line. |
| `created_at` | TIMESTAMPTZ | When the line was imported. |

### `recon_mismatches`

A difference found while reconciling. Not only FD sales; `transaction_id` is there for payment mismatches.

| Column | Type | Use |
| --- | --- | --- |
| `id` | BIGSERIAL | Primary key. |
| `batch_id` | UUID | Parent batch. |
| `mismatch_type` | TEXT | Kind of difference. |
| `transaction_id` | UUID | Our transaction, when the mismatch is about a payment. |
| `partner_ref` | TEXT | Partner reference from the file. |
| `details` | JSONB | What differed. |
| `resolution` | TEXT | How it was closed, when it was. |
| `resolved_at` | TIMESTAMPTZ | When it was closed. |
| `created_at` | TIMESTAMPTZ | When it was recorded. |

### `provider_callbacks`

Every inbound partner callback, including repeats and ones we refused. This is the history. `webhook_inbox` is only the processing queue.

| Column | Type | Use |
| --- | --- | --- |
| `id` | UUID | Primary key. |
| `provider` | TEXT | Who sent it. PaySprint sales callbacks are stored as `paysprint-fd`. Onboard and cash-out callbacks use their own provider label. |
| `source` | TEXT | Which URL received it, such as the FD, lead, UTM, or onboard path. |
| `event` | TEXT | Event name, when the body had one. Includes `MERCHANT_ONBOARDING`, `UPI-CASHOUT`, `UPI-CASHOUT-2FA`, `DMT_CASA_AO_DEBIT_NOTIFY`, `MERCHANT_STATUS_ONBOARD`. |
| `status` | TEXT | Status field from the payload, when present. |
| `merchant_code` | TEXT | Retailer code the callback was about. |
| `payload` | JSONB | Raw body. Can contain account and token fields. Do not export whole payloads. |
| `outcome` | TEXT | What we did: applied, or `REJECTED`. |
| `rejection_reason` | TEXT | Why we rejected it, when we did. |
| `received_at` | TIMESTAMPTZ | When it arrived. |

### `webhook_inbox`

Queue of callbacks waiting to be applied. Unique on `(provider, external_event_id)`, so a duplicate event is not processed twice. For a full arrival history, use `provider_callbacks`.

| Column | Type | Use |
| --- | --- | --- |
| `id` | BIGSERIAL | Primary key. |
| `provider` | TEXT | Sender. |
| `external_event_id` | TEXT | Partner event id. |
| `payload` | JSONB | Raw body. |
| `signature_valid` | BOOLEAN | Whether the signature checked out. |
| `status` | TEXT | `RECEIVED` until it is processed. |
| `transaction_id` | UUID | Transaction it was applied to, when it had one. |
| `received_at` | TIMESTAMPTZ | Arrival time. |
| `processed_at` | TIMESTAMPTZ | When processing finished. |

### `outbox_events`

Internal queue of events we still need to publish. Not a business report.

| Column | Type | Use |
| --- | --- | --- |
| `id` | BIGSERIAL | Primary key. |
| `aggregate_type` | TEXT | Kind of record, such as a transaction. |
| `aggregate_id` | UUID | That record’s id. |
| `event_type` | TEXT | What happened. |
| `payload` | JSONB | Event body. |
| `status` | TEXT | `NEW`, then `FAILED` if a try failed, then dispatched. |
| `attempts` | SMALLINT | How many tries. |
| `next_retry_at` | TIMESTAMPTZ | When to try again. |
| `dispatched_at` | TIMESTAMPTZ | When it was sent. |
| `created_at` | TIMESTAMPTZ | When it was queued. |

### `activity_events`

Append-only audit of what a signed-in user did. One row per mutating API call that matches an audit rule. Reads are not logged.

| Column | Type | Use |
| --- | --- | --- |
| `id` | UUID | Primary key. Supplied by the application. |
| `occurred_at` | TIMESTAMPTZ | When it happened. |
| `actor_user_id` | UUID | Who did it. Empty for an anonymous call. |
| `actor_user_type` | TEXT | Their role at the time. |
| `actor_code` | TEXT | Their public code at the time. |
| `action` | TEXT | Short label, for example `Service Commission Updated`, `Rewards Moved to Wallet`, `Money Transfer Sent`, `Beneficiary Added`. |
| `subject_type` | TEXT | Kind of thing acted on. |
| `subject_id` | TEXT | Id of that thing. |
| `outcome` | TEXT | Success or failure of the call. |
| `http_status` | INT | HTTP status returned. |
| `http_method` | TEXT | `POST`, `PUT`, `PATCH`, or `DELETE`. |
| `http_path` | TEXT | API path. |
| `detail` | JSONB | Extra fields. Should not contain secrets, but treat it as sensitive until reviewed. |
| `ip` | TEXT | Client IP. |
| `user_agent` | TEXT | Client identifier. |

### `shedlock`

Lock rows for scheduled jobs so two servers do not run the same job. Ignore for analysis.

| Column | Type | Use |
| --- | --- | --- |
| `name` | VARCHAR(64) | Job name. Primary key. |
| `lock_until` | TIMESTAMPTZ | When the lock expires. |
| `locked_at` | TIMESTAMPTZ | When it was taken. |
| `locked_by` | VARCHAR(255) | Which server holds it. |

### `flyway_schema_history`

Flyway’s own record of which migrations have run. The current application expects version `22` with `success = true`.

| Column | Type | Use |
| --- | --- | --- |
| `installed_rank` | INT | Order applied. |
| `version` | VARCHAR | Migration version, `1` through `22`. |
| `description` | VARCHAR | Short name, such as `rewards wallet`. |
| `type` | VARCHAR | `SQL` for these files. |
| `script` | VARCHAR | File name. |
| `checksum` | INT | Checksum. A changed applied file will not run again; it will fail startup. |
| `installed_by` | VARCHAR | Database user that applied it. |
| `installed_on` | TIMESTAMP | When it was applied. |
| `execution_time` | INT | Milliseconds it took. |
| `success` | BOOLEAN | True when that migration committed. |

## Queries analysts usually need

Retailers with their distributor, hub, and both balances:

```sql
SELECT r.code AS retailer_code,
       r.full_name AS retailer_name,
       r.status AS retailer_status,
       d.code AS distributor_code,
       d.full_name AS distributor_name,
       h.name AS hub_name,
       mw.available_balance AS main_balance,
       mw.hold_balance AS main_on_hold,
       rw.available_balance AS rewards_balance
  FROM users r
  LEFT JOIN users d ON d.id = r.parent_id
  LEFT JOIN hubs h ON h.id = r.hub_id
  LEFT JOIN wallets mw ON mw.user_id = r.id AND mw.wallet_kind = 'MAIN'
  LEFT JOIN wallets rw ON rw.user_id = r.id AND rw.wallet_kind = 'REWARDS'
 WHERE r.user_type = 'RETAILER';
```

Completed service volume, without double-counting rewards:

```sql
SELECT txn_type,
       count(*) AS txns,
       sum(amount) AS business_amount
  FROM transactions
 WHERE state = 'SUCCESS'
   AND txn_type IN ('DMT', 'BBPS', 'AEPS', 'CASHOUT_UPI', 'FD_CARD',
                    'CREDIT_CARD', 'INSTANT_LOAN', 'PERSONAL_LOAN',
                    'BUSINESS_LOAN', 'SAVINGS')
 GROUP BY txn_type
 ORDER BY txn_type;
```

Rewards actually credited:

```sql
SELECT t.txn_type,
       e.role_in_split,
       count(*) AS credits,
       sum(e.amount) AS reward_rupees
  FROM commission_earnings e
  JOIN transactions t ON t.id = e.transaction_id
 GROUP BY t.txn_type, e.role_in_split
 ORDER BY t.txn_type, e.role_in_split;
```

A user’s statement, both wallets:

```sql
SELECT le.created_at,
       w.wallet_kind,
       le.direction,
       le.amount,
       le.narration,
       t.txn_type,
       t.state
  FROM ledger_entries le
  JOIN wallets w ON w.id = le.wallet_id
  JOIN transactions t ON t.id = le.transaction_id
 WHERE w.user_id = :user_id
 ORDER BY le.id;
```

Sales funnel for internal leads:

```sql
SELECT sale_type,
       state,
       count(*) AS leads
  FROM sales_leads
 WHERE sale_channel = 'INTERNAL'
 GROUP BY sale_type, state
 ORDER BY sale_type, state;
```

DMT still in flight:

```sql
SELECT t.reference_id,
       t.amount,
       t.txn_type AS rail,
       t.status,
       t.refund_state,
       t.provider_utr,
       t.created_at,
       u.code AS retailer_code
  FROM dmt_transfers t
  JOIN users u ON u.id = t.agent_user_id
 WHERE t.status IN ('HOLD', 'PENDING', 'IN_PROCESS', 'SENT', 'ON_HOLD')
 ORDER BY t.created_at;
```

## Tables to skip for a business report

`user_sessions`, `idempotency_keys`, `customer_otp_challenges`, `dmt_bene_otp_sessions`, `dmt_txn_otp_sessions`, `outbox_events`, `webhook_inbox`, `shedlock`, `flyway_schema_history`, and `fd_commission_tiers`.
