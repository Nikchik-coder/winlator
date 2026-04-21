# RetroNexus: Universal Financial & Operational Strategy

This document defines the **provider-agnostic financial strategy** for the RetroNexus ecosystem: business logic, user tiering, and technical automation for monetizing a high-performance emulation marketplace while keeping operational costs and legal exposure under control.

---

## 1. Core value proposition: “Convenience as a service”

RetroNexus does not sell games. It sells **optimization and automation** so the product stays viable long term.

| | |
|---:|---|
| **Problem** | Standard Winlator setups need hours of manual tuning, driver hunting, and troubleshooting (for example, the FlatOut 2 audio crash). |
| **Solution** | A **one-click**, cloud-synced environment. Users pay for time saved and for the **guaranteed compatibility** of the Gold Build pipeline. |

---

## 2. Monetization models (tiers)

Application logic should support three user states, regardless of payment provider.

### A. Discovery tier — free

- **Purpose:** Marketing and proof of concept.
- **Access:** Two to three benchmark titles (for example Portal, Half-Life 2) to validate performance on the user’s hardware.
- **Limitations:** Capped download speeds (for example 1 MB/s) and **manual** container configuration only.

### B. Nexus Premium — one-time / lifetime

- **Purpose:** Primary revenue for casual users.
- **Access:** Full legacy library (GTA, NFS, Mafia, Iron Man).
- **Features:** Automated Gold Build configurations, high-speed S3 downloads, XInput controller auto-mapping.

### C. Cloud Pass — subscription

- **Purpose:** Recurring revenue for power users.

**Exclusive features:**

- **Cloud saves:** Progress synced across devices (Supabase).
- **Early access:** New Gold Builds before the standard store.
- **VIP support:** Direct help for custom game configurations.

---

## 3. Technical transaction logic (webhook model)

The stack should be **plug-and-play** with any processor (YuKassa, Stripe, crypto, PayPal) via a standard **webhook → database** flow.

1. **Metadata:** Every checkout includes `user_id` and `tier_id` in transaction metadata.
2. **Universal webhook:** Provider sends a success signal to a Supabase Edge Function; the function verifies the source and applies metadata.
3. **Database (example):**

   ```sql
   UPDATE profiles SET is_premium = true WHERE id = <user_id>;
   INSERT INTO transaction_logs (user_id, amount, provider) VALUES (...);
   ```

4. **Activation:** The Android client subscribes to database changes (Realtime) and unlocks Premium UI immediately.

---

## 4. S3 egress and cost management

Large files (2 GB+) on S3 or R2 create **egress** (outbound traffic) cost. Mitigations:

| Layer | What it does |
|--------|----------------|
| **Signed URLs** | Download links are minted on demand and expire (for example after one hour), reducing link leeching outside the app. |
| **Regional routing** | Cloudflare R2 or AWS S3 with CDN so objects are served from edge nodes close to the user. |
| **Download tokens (optional)** | Monthly download credits; Premium unlimited, Free limited (for example one download). |

---

## 5. Strategic risk mitigation (compliance)

To stay workable across gateways, RetroNexus uses a **login-first** visibility policy:

- **Hidden catalog:** Public site and checkout pages avoid listing specific game titles (reduces DMCA/IP surface). Copy should describe **“RetroNexus Premium access”** rather than catalog listings.
- **Vault logic:** Game lists and downloads exist only **inside the authenticated**, encrypted app.
- **Billing descriptors:** Use generic labels (for example `RETRONEXUS_TECH_SUBSCRIPTION`).

---

## 6. Database schema standard (`profiles`)

Supabase `profiles` should support the provider-agnostic model:

| Column | Type | Description |
|--------|------|-------------|
| `id` | `uuid` | References `auth.users` |
| `is_premium` | `boolean` | Unlocks one-click installs |
| `subscription_status` | `text` | `active`, `trialing`, `past_due`, `none` |
| `current_tier` | `int` | `0` = Free, `1` = Premium, `2` = Cloud Pass |
| `egress_used` | `bigint` | Total GB downloaded by user (tracking / caps) |

---

## Executive conclusion

RetroNexus wins by removing friction from emulation: **Gold Build preparation** on Ubuntu and **delivery via S3** add a paid layer on top of the open-source Winlator core. Revenue comes from **infrastructure and expertise** that make Windows games viable on ARM—not from selling the emulator itself.
