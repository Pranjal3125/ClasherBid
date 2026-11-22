<div align="center">

# ClasherBid

### GUI-Based Single-Item Auction Plugin for Clasher Network

A modern Paper 26.2 plugin for live single-item auctions with Vault economy escrow, customizable MiniMessage branding, anti-sniping, countdown alerts, dedicated ledger persistence, and crash-safe transaction recovery.

[![Maven build](https://github.com/Pranjal3125/ClasherBid/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/Pranjal3125/ClasherBid/actions/workflows/build.yml)
![Java](https://img.shields.io/badge/Java-25-orange?logo=openjdk)
![Platform](https://img.shields.io/badge/Platform-Paper%2026.2-313131?logo=minecraft)
![Build](https://img.shields.io/badge/CI-GitHub%20Actions-blue?logo=githubactions)

[Source](https://github.com/Pranjal3125/ClasherBid) · [Issues](https://github.com/Pranjal3125/ClasherBid/issues) · [Actions](https://github.com/Pranjal3125/ClasherBid/actions/workflows/build.yml)

</div>

> **Note:** Always test new builds on a staging server with your production Vault economy plugin before deploying live.

## Contents

- [Features](#features)
- [Requirements](#requirements)
- [Installation](#installation)
- [Commands](#commands)
- [Permissions](#permissions)
- [Configuration](#configuration)
- [Build & Tests](#build--tests)
- [Data and Backups](#data-and-backups)
- [Recovery and Transaction Safety](#recovery-and-transaction-safety)
- [Staging Checklist](#staging-checklist)
- [Contributing](#contributing)

---

## Features

- **Modern 45-Slot Live Auction GUI** (`/bid` or `/auction`) secured by custom `InventoryHolder` verification (immune to renamed-chest spoofing) with live 1-second updates.
- **Direct & Custom Bidding**:
  - Configurable quick-bid increment buttons, minimum bid button, `/bid <amount>` shortcut, and chat-based custom bids via Paper's `AsyncChatEvent` (with automatic timeout and compact number suffixes like `1,500`, `2.5k`, `1m`).
  - Dedicated confirmation screen displaying bid amount, current balance, and post-escrow balance before committing funds.
- **Vault Economy Escrow**:
  - Immediate escrow on accepted bids, automatic outbid refunds with clear player notifications, and configurable seller/fixed-account payouts.
  - First bid may match the opening price; subsequent bids enforce `minimum-increment`, `bid-cooldown-seconds`, and `allow-self-outbid` protection.
- **Anti-Sniping & Countdown Alerts**:
  - Configurable deadline extensions when bids arrive near auction end, optional extension broadcasts, and configurable countdown reminders (`60s`, `30s`, `10s`, `5s`).
- **In-GUI Claims & History**:
  - Automatic item delivery on win and on player join, plus `/bid claim` and a one-click **Claim Pending Items** button inside `/bid` (no relogging required, and never registers `/claim`).
  - Built-in **Auction History** GUI (`/bid history`) and chat history (`/clasherbid history`).
- **Full MiniMessage Localization & Live Reload**:
  - Customize every prefix, broadcast, and player message in `messages.yml` using Adventure MiniMessage tags, and reload live with `/clasherbid reload`.
- **Crash-Safe Architecture**:
  - Atomic temp-file-and-move YAML persistence for `active-auction.yml`, `ledger.yml`, and `auction-history.yml`.
  - Automatic one-time migration of legacy `recovery.*` and `pending-delivery.*` entries from `config.yml` into `ledger.yml`.
  - Conservative recovery markers and admin review commands for interrupted economy or inventory operations.

## Requirements

| Component | Requirement |
|---|---|
| Server | Paper 26.2 |
| Java | Java 25 |
| Economy bridge | Vault |
| Economy provider | Any Vault-compatible economy plugin |

## Installation

1. Stop the server.
2. Back up the server and any existing `plugins/ClasherBid/` data folder.
3. Download the latest successful **ClasherBid-jar** artifact from [GitHub Actions](https://github.com/Pranjal3125/ClasherBid/actions/workflows/build.yml) (or build locally with Maven).
4. Place the JAR in the server's `plugins/` folder.
5. Confirm Vault and a Vault-compatible economy provider are installed.
6. Start the server and verify console startup (`ClasherBid v1.0.0 enabled with Vault provider: ...`).
7. Run `/clasherbid status` and perform a low-value staff-only test auction.

Do not use `/reload` to install or update the plugin — restart the server cleanly. (Use `/clasherbid reload` for configuration/message edits.)

## Commands

### Players

All player commands require `clasherbid.bid` (granted to everyone by default). `/auction` is an alias for `/bid`.

| Command | Description |
|---|---|
| `/bid` | Open the live auction GUI. |
| `/bid <amount>` | Open the bid confirmation GUI directly for `<amount>` (supports `1500`, `1,500`, `2.5k`, `1m`). |
| `/bid claim` | Claim any pending won or returned auction items immediately. |
| `/bid history` | Open the recent auction history GUI (or print in console). |
| `/bid status` | Show a quick summary of the active auction in chat. |
| `/bid help` | Show player command usage. |

### Administrators

All commands below require `clasherbid.admin` (default: operators). `/cbid` is an alias for `/clasherbid`.

| Command | Description |
|---|---|
| `/clasherbid` or `/clasherbid help` | Show admin command help. |
| `/clasherbid start <minutes> <starting-price>` | Start an auction using the item in the admin's main hand (duration: 1–120 minutes; supports `500`, `2.5k`, etc.). |
| `/clasherbid cancel` | Freeze the active auction for manual reconciliation (does not auto-refund or return the item). |
| `/clasherbid status` | Show active auction, seller, escrow, review state, and pending ledger alert counts. |
| `/clasherbid history` | Show the latest 25 recorded auctions. |
| `/clasherbid reload` | Live-reload `config.yml` and `messages.yml` without disturbing active auctions or `ledger.yml`. |
| `/clasherbid recover <player>` | Retry an eligible recorded refund. |
| `/clasherbid retry` | Retry settlement after updating `payout-player` and running `/clasherbid reload`. |
| `/clasherbid settlement` | Inspect a frozen settlement. |
| `/clasherbid settlement paid` | Confirm payout already happened; finalizes item delivery without paying again. |
| `/clasherbid settlement unpaid` | Confirm payout did not happen; records required recovery liability and queues the seller's item. |
| `/clasherbid bid-review <paid\|unpaid>` | Resolve an interrupted bid after checking the bidder's balance. |
| `/clasherbid refund-review <player> <paid\|unpaid>` | Resolve an uncertain refund after checking the recipient's balance. |
| `/clasherbid claim-review <player> <delivered\|undelivered>` | Resolve an interrupted item claim after checking the player's inventory. |
| `/clasherbid start-review <item-removed\|item-still-held>` | Resolve an interrupted start after checking the admin's inventory. |

**Command compatibility:** ClasherBid intentionally does **not** register `/claim`. GriefPrevention's `/claim` command remains completely untouched.

### Examples

```text
/clasherbid start 10 500
/clasherbid status
/bid
/bid 1.5k
/bid claim
/clasherbid reload
```

## Permissions

| Permission | Default | Description |
|---|---|---|
| `clasherbid.bid` | All players (`true`) | Open the auction GUI, place bids, view history, and claim won items. |
| `clasherbid.admin` | Operators (`op`) | Start/cancel auctions, reload configs, inspect status/history, and perform recovery actions. |

## Configuration

`plugins/ClasherBid/config.yml`:

```yaml
minimum-increment: 100.0
bid-cooldown-seconds: 2
allow-self-outbid: false
custom-bid-timeout-seconds: 30

quick-bid-increments:
  - 500
  - 1000
  - 5000

anti-sniping:
  enabled: true
  trigger-seconds: 15
  extend-seconds: 30
  broadcast: true

countdown-alerts:
  enabled: true
  seconds:
    - 60
    - 30
    - 10
    - 5

sounds:
  enabled: true

payout-player: ""
```

| Setting | Meaning |
|---|---|
| `minimum-increment` | Minimum increase after the first accepted bid. Default: `100.0`. |
| `bid-cooldown-seconds` | Per-auction accepted-bid cooldown (0–3600). Default: `2`. |
| `allow-self-outbid` | Allow the current highest bidder to bid again. Default: `false`. |
| `custom-bid-timeout-seconds` | Chat input timeout for custom bids (5–120). Default: `30`. |
| `quick-bid-increments` | Up to 3 positive quick-bid increments shown in `/bid`. |
| `anti-sniping.enabled` | Enable/disable deadline extension near auction end. |
| `anti-sniping.trigger-seconds` | Extend when a bid arrives within this many seconds of the deadline (0–3600). Default: `15`. |
| `anti-sniping.extend-seconds` | Seconds added to the deadline (0–3600). Default: `30`. |
| `anti-sniping.broadcast` | Broadcast a notice when anti-sniping extends the deadline. |
| `countdown-alerts.enabled` | Broadcast remaining-time reminders near auction end. |
| `countdown-alerts.seconds` | Thresholds (in seconds) at which countdown reminders are broadcast. |
| `sounds.enabled` | Play UI, bid, outbid, claim, and win sound effects. |
| `payout-player` | Optional existing player account to receive seller proceeds. Blank pays the auction seller. |

All player-facing messages and broadcasts are customizable in `plugins/ClasherBid/messages.yml` using Adventure MiniMessage syntax and can be reloaded live with `/clasherbid reload`.

## Build & Tests

Every push to `main`, pull request, or manual dispatch runs the Maven build and JUnit 5 test suite in GitHub Actions:

```bash
mvn --batch-mode clean package
```

The shaded plugin JAR is generated in `target/ClasherBid-1.0.0.jar`.

## Data and Backups

Back up the entire `plugins/ClasherBid/` directory before updates and before any manual recovery:

- `config.yml` — plugin settings (kept clean of runtime ledgers).
- `messages.yml` — customizable MiniMessage localization.
- `active-auction.yml` — persisted active auction and transaction intent journal.
- `ledger.yml` — idempotent pending item deliveries, refund liabilities, and claim/refund review markers.
- `auction-history.yml` — recorded auction history (latest 25 auctions).

Never edit recovery markers while the server is running. Stop the server and make a separate copy before any manual file-level repair.

## Recovery and Transaction Safety

Vault economy transactions and YAML persistence cannot participate in a single atomic transaction. If a crash happens after an economy provider processes a payment but before ClasherBid records the result, ClasherBid freezes uncertain operations for manual review instead of blindly repeating a withdrawal, deposit, refund, or item delivery.

**Before using any `paid` / `unpaid` / `delivered` / `undelivered` review command, verify the real player balance and inventory.** The first settlement decision is persisted and locks recovery to that decision.

- `BID_PENDING`: check the bidder's balance before running `/clasherbid bid-review paid|unpaid`. If `BID_PENDING_ACCEPTED`, only `paid` clears the marker.
- Settlement review: inspect `/clasherbid settlement` and verify seller payout, Vault balance, and item delivery before choosing `paid` or `unpaid`.
- Invalid payout account: update `payout-player` in `config.yml`, run `/clasherbid reload`, then run `/clasherbid retry`.
- Refund review: verify the recipient's balance before running `/clasherbid refund-review <player> paid|unpaid`.
- Claim review: inspect the player's inventory before running `/clasherbid claim-review <player> delivered|undelivered`.
- Start review: check whether the admin still holds the item before choosing `item-removed` or `item-still-held`.
- `AUCTION_DATA_CORRUPT`: command-based settlement is intentionally blocked. Preserve the original file and recover from a backup after reconciling balances and item ownership.

## Staging Checklist

Before production rollout, verify on a Paper staging server matching your production stack:

- [ ] Confirm Vault economy is detected and ClasherBid enables without command conflicts.
- [ ] Confirm `/bid`, `/auction`, `/bid claim`, `/bid history`, and `/clasherbid` work; confirm GriefPrevention still owns `/claim`.
- [ ] Test normal, renamed, and lore items; invalid durations/prices; persistence after restart.
- [ ] Test opening-price bid, increment, insufficient balance, self-bid, self-outbid block, cooldown, anti-sniping, and countdown alerts.
- [ ] Test outbid refunds for online and offline players.
- [ ] Test completed auctions with a winner and with no bids.
- [ ] Test seller payout, winner/seller item delivery, `/bid claim`, offline delivery, and full-inventory behavior.
- [ ] Test `/clasherbid reload` during an active auction.
- [ ] Test recovery paths using disposable test accounts and items.
- [ ] Verify non-ops cannot run admin commands and authorized admins can.
- [ ] Back up data, remove the staging JAR, and confirm clean startup.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for the development workflow and pull-request checklist.
