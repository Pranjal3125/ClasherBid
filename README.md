<div align="center">

# ClasherBid

### GUI-Based Single-Item Auction Plugin for Clasher Network

A Paper plugin for live single-item auctions with Vault economy integration, anti-sniping, persistent state, and crash-safe transaction recovery.

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
- [Build](#build)
- [Data and Backups](#data-and-backups)
- [Recovery and Transaction Safety](#recovery-and-transaction-safety)
- [Staging Checklist](#staging-checklist)
- [Contributing](#contributing)

---

## Features

- Live auction GUI opened with `/bid` or `/auction`.
- Single active auction at a time.
- Vault-backed bidding, escrow handling, seller payout, and outbid refunds.
- First bid may match the opening price; subsequent bids must meet the configured increment.
- Bid cooldown and configurable anti-sniping deadline extension.
- Custom bid flow with confirmation and recent-bid display.
- Persistent active-auction state and auction history.
- Automatic item delivery attempts, including when a player joins later.
- Conservative recovery markers for interrupted economy and item-delivery operations.
- Admin diagnostics and manual review commands for uncertain transactions.

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
3. Download the latest successful **ClasherBid-jar** artifact from [GitHub Actions](https://github.com/Pranjal3125/ClasherBid/actions/workflows/build.yml).
4. Extract the ZIP and place the JAR in the server's `plugins/` folder.
5. Confirm Vault and a Vault-compatible economy provider are installed.
6. Start the server and check the console for successful ClasherBid enablement.
7. Run `/clasherbid status` and perform a low-value staff-only test auction.

Do not use `/reload` to install or update the plugin — restart the server cleanly.

## Commands

### Players

| Command | Description |
|---|---|
| `/bid` | Open the auction GUI. |
| `/auction` | Alias for `/bid`. |

### Administrators

All commands below require `clasherbid.admin`.

| Command | Description |
|---|---|
| `/clasherbid` | Show command help. |
| `/clasherbid start <minutes> <starting-price>` | Start an auction using the item in the admin's main hand (duration: 1–120 minutes). |
| `/clasherbid cancel` | Freeze the active auction for manual reconciliation (does not auto-refund or return the item). |
| `/clasherbid status` | Show active auction, escrow, and recovery status. |
| `/clasherbid history` | Show the latest 25 recorded auctions. |
| `/clasherbid recover <player>` | Retry an eligible recorded refund. |
| `/clasherbid retry` | Retry settlement after correcting an invalid payout account. |
| `/clasherbid settlement` | Inspect a frozen settlement. |
| `/clasherbid settlement paid` | Confirm payout already happened; finalizes item delivery without paying again. |
| `/clasherbid settlement unpaid` | Confirm payout did not happen; records required recovery liability and queues the seller's item. |
| `/clasherbid bid-review <paid\|unpaid>` | Resolve an interrupted bid after checking the bidder's balance. |
| `/clasherbid refund-review <player> <paid\|unpaid>` | Resolve an uncertain refund after checking the recipient's balance. |
| `/clasherbid claim-review <player> <delivered\|undelivered>` | Resolve an interrupted item claim after checking the player's inventory. |
| `/clasherbid start-review <item-removed\|item-still-held>` | Resolve an interrupted start after checking the admin's inventory. |

**Command compatibility:** ClasherBid does not register `/claim`. If GriefPrevention is installed, its `/claim` command remains untouched.

### Examples

```text
/clasherbid start 10 500
/clasherbid status
/bid
/clasherbid history
```

## Permissions

| Permission | Default | Description |
|---|---|---|
| `clasherbid.admin` | Operators | Start/cancel auctions, inspect status/history, and perform recovery actions. |

Players do not need a special permission to open the bidding GUI unless another plugin restricts command access.

## Configuration

The generated `plugins/ClasherBid/config.yml` contains:

```yaml
minimum-increment: 100.0
bid-cooldown-seconds: 2
anti-sniping:
  enabled: true
  trigger-seconds: 15
  extend-seconds: 30
payout-player: ""
```

| Setting | Meaning |
|---|---|
| `minimum-increment` | Minimum increase after the first accepted bid. Default: `100`. |
| `bid-cooldown-seconds` | Per-auction accepted-bid cooldown (0–3600). Default: `2`. |
| `anti-sniping.enabled` | Enable/disable deadline extension near auction end. |
| `anti-sniping.trigger-seconds` | Extend when a bid arrives within this many seconds of the deadline (0–3600). Default: `15`. |
| `anti-sniping.extend-seconds` | Seconds added to the deadline (0–3600). Default: `30`. |
| `payout-player` | Optional existing player account to receive seller proceeds. Blank pays the auction seller. |

All money values use the installed Vault economy provider. After changing `payout-player`, restart the server before using `/clasherbid retry`.

## Build

Every push to `main`, pull request, or manual dispatch runs the Maven build in GitHub Actions:

1. Open [Actions → Maven build](https://github.com/Pranjal3125/ClasherBid/actions/workflows/build.yml).
2. Open the latest successful run and download the `ClasherBid-jar` artifact ZIP.
3. Extract the plugin JAR from the ZIP.

For local builds:

```bash
mvn --batch-mode clean package
```

The JAR is generated in `target/`.

## Data and Backups

Back up the entire `plugins/ClasherBid/` directory before updates and before any manual recovery:

- `active-auction.yml` — persisted active auction and transaction state.
- `config.yml` — plugin settings and pending delivery records.
- `auction-history.yml` — recorded auction history.

Never edit recovery markers while the server is running. Stop the server and make a separate copy before any manual file-level repair.

## Recovery and Transaction Safety

Vault economy transactions and YAML persistence cannot participate in a single atomic transaction. If a crash happens after an economy provider processes a payment but before ClasherBid records the result, ClasherBid freezes uncertain operations for manual review instead of blindly repeating a withdrawal, deposit, refund, or item delivery.

**Before using any `paid` / `unpaid` / `delivered` / `undelivered` review command, verify the real player balance and inventory.** The first settlement decision is persisted and locks recovery to that decision.

- `BID_PENDING`: check the bidder's balance before running `/clasherbid bid-review paid|unpaid`. If `BID_PENDING_ACCEPTED`, only `paid` clears the marker.
- Settlement review: inspect `/clasherbid settlement` and verify seller payout, Vault balance, and item delivery before choosing `paid` or `unpaid`.
- Invalid payout account: update `payout-player`, restart, then run `/clasherbid retry`.
- Refund review: verify the recipient's balance before running `/clasherbid refund-review <player> paid|unpaid`.
- Claim review: inspect the player's inventory before running `/clasherbid claim-review <player> delivered|undelivered`.
- Start review: check whether the admin still holds the item before choosing `item-removed` or `item-still-held`.
- `AUCTION_DATA_CORRUPT`: command-based settlement is intentionally blocked. Preserve the original file and recover from a backup after reconciling balances and item ownership.

## Staging Checklist

Before production rollout, verify on a Paper staging server matching your production stack:

- [ ] Confirm Vault economy is detected and ClasherBid enables without command conflicts.
- [ ] Confirm `/bid`, `/auction`, and `/clasherbid` work; confirm GriefPrevention still owns `/claim`.
- [ ] Test normal, renamed, and lore items; invalid durations/prices; persistence after restart.
- [ ] Test opening-price bid, increment, insufficient balance, self-bid, cooldown, and anti-sniping.
- [ ] Test outbid refunds for online and offline players.
- [ ] Test completed auctions with a winner and with no bids.
- [ ] Test seller payout, winner/seller item delivery, offline delivery, and full-inventory behavior.
- [ ] Test recovery paths using disposable test accounts and items.
- [ ] Verify non-ops cannot run admin commands and authorized admins can.
- [ ] Restart during an active auction and controlled transaction boundaries.
- [ ] Back up data, remove the staging JAR, and confirm clean startup.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for the development workflow and pull-request checklist.
