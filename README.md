# ClasherBid

GUI-based single-item auction plugin for Clasher Network.

## Target platform

- Paper API: Minecraft 26.2
- Java 25
- Vault + a Vault-compatible economy provider

## Player commands

- `/bid` — open the live auction GUI.
- `/auction` — alias for `/bid`.

## Admin commands

All admin commands require `clasherbid.admin` (default: op).

- `/clasherbid start <minutes> <starting-price>` — start an auction using the item in the admin's main hand (1–120 minutes; positive starting price). The first bid can match the opening price; later bids must meet the configured increment.
- `/clasherbid cancel` — freeze the auction for manual reconciliation. It does not automatically refund or return the item.
- `/clasherbid status` — show the active auction.
- `/clasherbid history` — show recent auction history.
- `/clasherbid retry` — retry settlement after fixing an invalid configured payout account.
- `/clasherbid settlement` — inspect the frozen auction/recovery state.
- `/clasherbid settlement paid` — confirm the winner payout already happened; queues the winner's item without paying again.
- `/clasherbid settlement unpaid` — confirm no payout happened; queues the seller's item and records any bidder refund as a recovery liability.
- `/clasherbid bid-review <paid|unpaid>` — resolve an interrupted bid withdrawal after checking the bidder's balance.
- `/clasherbid start-review <item-removed|item-still-held>` — resolve a crash during auction start after checking the admin's inventory.
- Outbid refunds are automatically attempted through guarded Vault recovery; if the economy provider fails, the liability remains frozen for admin review.
- `/clasherbid recover <player>` — retry a recorded refund after checking its review state.
- `/clasherbid refund-review <player> <paid|unpaid>` — resolve an uncertain refund after checking the player's balance.
- `/clasherbid claim-review <player> <delivered|undelivered>` — resolve an interrupted item claim after checking the player's inventory.

## Build

```bash
mvn clean package
```

The plugin JAR is created under `target/`.

## Configuration

- `minimum-increment` — minimum increase after the first accepted bid (default 100).
- `bid-cooldown-seconds` — per-auction bid cooldown (0–3600; default 2).
- `anti-sniping.enabled` — enable deadline extension near the end (default true).
- `anti-sniping.trigger-seconds` — extension trigger window (0–3600; default 15).
- `anti-sniping.extend-seconds` — extension duration (0–3600; default 30).
- `payout-player` — optional existing player account to receive seller proceeds. Blank pays the auction seller. Change this in config and restart before using `/clasherbid retry`.

All prices use the installed Vault economy provider. Keep regular backups of the plugin data folder, especially `active-auction.yml`, `config.yml`, and `auction-history.yml`.

## Release and staging checklist

**Build verification:** GitHub Actions must show the Maven build and JAR artifact upload as successful. This proves compilation/package creation only; it does not prove live-server behavior.

**Install on a separate staging Paper server first**, using the same Paper build, Java runtime, Vault version, and economy provider as production. Use test accounts and disposable items.

1. Confirm startup reports a Vault economy provider and ClasherBid enables without command conflicts. Verify `/bid`, `/auction`, and `/clasherbid`; confirm GriefPrevention's `/claim` remains owned by GriefPrevention.
2. Start auctions with a normal stack, a renamed/lore item, and invalid inputs. Confirm the item is removed exactly once and the auction persists after restart.
3. Test first bid at opening price, below-minimum bid, subsequent increment, insufficient balance, self-bid, cooldown, custom bid, cancel from chat, confirmation cancel, and anti-sniping.
4. Outbid a player. Verify the new bidder is charged once, the previous bidder is refunded once, and both online and offline cases recover correctly.
5. Finish with a bidder and with no bids. Verify seller payout, winner/seller item delivery, offline delivery on join, and full-inventory retry.
6. Exercise every recovery state only with deliberately simulated test balances/items: `bid-review`, `refund-review`, `claim-review`, `start-review`, `settlement`, and `retry`. Never choose paid/unpaid without checking the test account balance and inventory.
7. Test admin permission as a non-op without `clasherbid.admin`, then grant the permission and verify command/tab completion access.
8. Restart during an active auction and after each simulated transaction boundary. Confirm uncertain transactions freeze for review instead of paying/refunding/delivering twice.
9. Back up plugin data, remove the staging JAR cleanly, and verify the server starts normally without ClasherBid.

**Live rollout:** take a full server/plugin-data backup, announce a short auction maintenance window, install the tested JAR, restart (do not use /reload), run `/clasherbid status`, and perform one low-value staff-only auction before opening it to players.

## Transaction safety limitation

Vault economy transactions and plugin YAML persistence are not one atomic transaction. A crash during a withdrawal, refund, or payout can leave an uncertain outcome. ClasherBid deliberately freezes the affected operation for admin review rather than blindly retrying a potentially completed payment. It cannot guarantee exactly-once economy transactions across every crash window.

## Recovery guidance

- For `BID_PENDING`, check the bidder's balance before using `/clasherbid bid-review paid` or `unpaid`. If the bid state is already `BID_PENDING_ACCEPTED`, only `paid` can clear the marker.
- For payout/settlement review, inspect `/clasherbid settlement` and verify the Vault balance, seller payout, and item delivery before choosing `paid` or `unpaid`. The first choice is durably recorded and locks recovery to that decision; do not try the opposite option afterward. A failed Vault payout response pauses bidding and retries after 60 seconds; an invalid configured payout account requires fixing `payout-player`, restarting the server to reload the config, and then using `/clasherbid retry`.
- For refund review, verify the recipient's balance before `/clasherbid refund-review <player> paid|unpaid`.
- For claim review, inspect the player's inventory before `/clasherbid claim-review <player> delivered|undelivered`. If a crash caused a partial delivery, first reconcile `pending-delivery.<uuid>` in `config.yml` against the player's inventory, then unlock the claim; do not blindly choose `undelivered`.
- For start review, check whether the admin still holds the item before `/clasherbid start-review item-removed|item-still-held`.

Never issue a second payment or clear a recovery marker based only on an error message. Verify balances and inventories first. If the review state is `AUCTION_DATA_CORRUPT`, command-based settlement is intentionally blocked. The original `active-auction.yml` is preserved on shutdown; take a backup and perform file-level recovery only after checking economy balances and item ownership. Frozen recovery is intentionally manual.
