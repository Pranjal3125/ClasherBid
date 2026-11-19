# ClasherBid

[![Maven build](https://github.com/Pranjal3125/ClasherBid/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/Pranjal3125/ClasherBid/actions/workflows/build.yml)
![Minecraft](https://img.shields.io/badge/Minecraft-Paper%2026.2-2ea44f)
![Java](https://img.shields.io/badge/Java-25-orange)
![Build](https://img.shields.io/badge/build-GitHub%20Actions-blue)

**ClasherBid** is a GUI-based, single-item auction plugin built for Clasher Network. It uses Vault for economy transactions and stores auction/recovery state on disk.

> CI passing confirms that the project compiles and packages. It does not replace testing on a staging server.

## Contents

- [Features](#features)
- [Requirements](#requirements)
- [Installation](#installation)
- [Commands](#commands)
- [Permissions](#permissions)
- [Configuration](#configuration)
- [Build](#build)
- [Data and backups](#data-and-backups)
- [Recovery and transaction safety](#recovery-and-transaction-safety)
- [Staging checklist](#staging-checklist)
- [Contributing](#contributing)

## Features

- Live auction GUI opened with `/bid` or `/auction`.
- Single active auction at a time.
- Vault-backed bidding, escrow handling, seller payout and outbid refunds.
- First bid may match the opening price; later bids must meet the configured increment.
- Bid cooldown and configurable anti-sniping extension.
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

Use a server build and dependency versions compatible with the target platform. Test the exact production stack on staging first.

## Installation

1. Stop the server.
2. Back up the server and the existing ClasherBid data folder, if present.
3. Download the latest successful **ClasherBid-jar** artifact from [GitHub Actions](https://github.com/Pranjal3125/ClasherBid/actions/workflows/build.yml).
4. Extract the ZIP and place the JAR in the server's `plugins/` folder.
5. Confirm Vault and a Vault-compatible economy provider are installed.
6. Start the server and check the console for successful ClasherBid enablement.
7. Run `/clasherbid status` and perform a low-value staff-only test auction.

Do not use `/reload` to install or update the plugin. Restart the server cleanly.

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
| `/clasherbid start <minutes> <starting-price>` | Start an auction using the item in the admin's main hand. Duration: 1–120 minutes. |
| `/clasherbid cancel` | Freeze the active auction for manual reconciliation. Does not automatically refund or return the item. |
| `/clasherbid status` | Show active auction, escrow and recovery status. |
| `/clasherbid history` | Show the latest 25 recorded auctions. |
| `/clasherbid recover <player>` | Retry an eligible recorded refund. |
| `/clasherbid retry` | Retry settlement after correcting an invalid payout account. |
| `/clasherbid settlement` | Inspect a frozen settlement. |
| `/clasherbid settlement paid` | Confirm payout already happened; finalizes item delivery without paying again. |
| `/clasherbid settlement unpaid` | Confirm payout did not happen; records the required recovery liability and queues the seller's item. |
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
| `clasherbid.admin` | Operators | Start/cancel auctions, inspect status/history and perform recovery actions. |

Players do not need a special permission to open the bidding GUI unless another server plugin restricts command access.

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
| `minimum-increment` | Minimum increase after the first accepted bid. Default: 100. |
| `bid-cooldown-seconds` | Per-auction accepted-bid cooldown. Range: 0–3600. Default: 2. |
| `anti-sniping.enabled` | Enable/disable deadline extension near auction end. |
| `anti-sniping.trigger-seconds` | Extend when a bid arrives within this many seconds of the deadline. Range: 0–3600. Default: 15. |
| `anti-sniping.extend-seconds` | Number of seconds added to the deadline. Range: 0–3600. Default: 30. |
| `payout-player` | Optional existing player account to receive seller proceeds. Blank pays the auction seller. |

All money values use the installed Vault economy provider. After changing `payout-player`, restart the server so the plugin reloads the setting before using `/clasherbid retry`.

## Build

You do not need to build locally to use the plugin. Every push to `main`, pull request, or manual workflow dispatch runs the Maven build through GitHub Actions.

1. Open [Actions → Maven build](https://github.com/Pranjal3125/ClasherBid/actions/workflows/build.yml).
2. Open the latest successful run.
3. Download the `ClasherBid-jar` artifact ZIP.
4. Extract the plugin JAR from the ZIP.

For local development, use:

```bash
mvn --batch-mode clean package
```

The JAR is generated in `target/`.

## Data and backups

Back up the entire `plugins/ClasherBid/` directory before updates and before any manual recovery. Important files include:

- `active-auction.yml` — persisted active auction and transaction state.
- `config.yml` — plugin settings and pending delivery records.
- `auction-history.yml` — recorded auction history.

Never edit recovery markers while the server is running. Stop the server and make a separate copy before any manual file-level repair.

## Recovery and transaction safety

Vault economy transactions and YAML persistence cannot participate in one atomic transaction. A crash can happen after an economy provider processes a payment but before ClasherBid records the result. ClasherBid therefore freezes uncertain operations for review instead of blindly repeating a withdrawal, deposit, refund or item delivery.

**Before using any `paid` / `unpaid` / `delivered` / `undelivered` review command, verify the real player balance and inventory.** Choosing the wrong outcome can cause a duplicate payment, lost item or unresolved liability. The first settlement decision is persisted and locks recovery to that decision.

- For `BID_PENDING`, check the bidder's balance before using `/clasherbid bid-review paid|unpaid`. If the bid is already `BID_PENDING_ACCEPTED`, only `paid` can clear the marker.
- For payout/settlement review, inspect `/clasherbid settlement`; verify the seller payout, Vault balance and item delivery before choosing `paid` or `unpaid`.
- If a payout account is invalid, correct `payout-player`, restart, then use `/clasherbid retry`.
- For refund review, verify the recipient's balance before using `/clasherbid refund-review <player> paid|unpaid`.
- For claim review, inspect the player's inventory before using `/clasherbid claim-review <player> delivered|undelivered`. Reconcile any partial delivery record before unlocking a retry.
- For start review, check whether the admin still holds the auction item before choosing `item-removed` or `item-still-held`.
- If recovery reports `AUCTION_DATA_CORRUPT`, command-based settlement is intentionally blocked. Preserve the original file and perform file-level recovery only from a backup with balances and item ownership reconciled.

ClasherBid cannot promise exactly-once economy transactions across every possible crash window. Frozen recovery is intentionally manual.

## Staging checklist

Before production rollout, use a separate Paper staging server with the same Java, Paper, Vault and economy-provider versions as production.

- [ ] Confirm Vault economy is detected and ClasherBid enables without command conflicts.
- [ ] Confirm `/bid`, `/auction` and `/clasherbid` work; confirm GriefPrevention still owns `/claim`.
- [ ] Test normal, renamed and lore items; invalid auction durations/prices; persistence after restart.
- [ ] Test opening-price bid, increment, insufficient balance, self-bid, cooldown and anti-sniping.
- [ ] Test outbid refunds for online and offline players.
- [ ] Test completed auctions with a winner and with no bids.
- [ ] Test seller payout, winner/seller item delivery, offline delivery and full-inventory behavior.
- [ ] Test recovery paths using disposable test accounts and items only.
- [ ] Verify a non-op cannot use admin commands and an authorized admin can.
- [ ] Restart during an active auction and during controlled test transaction boundaries.
- [ ] Back up data, remove the staging JAR and confirm the server starts normally.

For production, take a full backup, announce a maintenance window, install the tested JAR, restart (never `/reload`), inspect `/clasherbid status`, and run one low-value staff-only auction before opening auctions to players.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for the development workflow and pull-request checklist.
