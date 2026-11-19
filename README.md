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

- `/clasherbid start <minutes> <starting-price>` — start an auction using the item in the admin's main hand (1–120 minutes; positive starting price).
- `/clasherbid cancel` — freeze the auction for manual reconciliation. It does not automatically refund or return the item.
- `/clasherbid status` — show the active auction.
- `/clasherbid history` — show recent auction history.
- `/clasherbid retry` — retry settlement after fixing an invalid configured payout account.
- `/clasherbid settlement` — inspect the frozen auction/recovery state.
- `/clasherbid settlement paid` — confirm the winner payout already happened; queues the winner's item without paying again.
- `/clasherbid settlement unpaid` — confirm no payout happened; queues the seller's item and records any bidder refund as a recovery liability.
- `/clasherbid bid-review <paid|unpaid>` — resolve an interrupted bid withdrawal after checking the bidder's balance.
- `/clasherbid start-review <item-removed|item-still-held>` — resolve a crash during auction start after checking the admin's inventory.
- `/clasherbid recover <player>` — attempt a guarded refund recovery.
- `/clasherbid refund-review <player> <paid|unpaid>` — resolve an uncertain refund after checking the player's balance.
- `/clasherbid claim-review <player> <delivered|undelivered>` — resolve an interrupted item claim after checking the player's inventory.

## Build

```bash
mvn clean package
```

The plugin JAR is created under `target/`.

## Important: staging before live use

This is an initial development implementation, not a verified production release. Test on a staging Paper server with a test economy before using it on a live network. In particular, verify restart recovery, Vault payment/refund behavior, offline item delivery, and GUI behavior against your exact Paper build and economy provider.

Vault economy transactions and plugin YAML persistence are not one atomic transaction. A crash during a withdrawal, refund, or payout can leave an uncertain outcome. ClasherBid deliberately freezes the affected operation for admin review rather than blindly retrying a potentially completed payment. It cannot guarantee exactly-once economy transactions across every crash window.

Before live use, test at least:

- Restart during an active auction with and without a top bidder.
- Outbid a player, then restart around the refund-queue write.
- Finish an auction and restart around the payout/delivery moment.
- Cancel an auction while a bid is escrowed.
- Test offline winner delivery and inventory-full handling.
- Close the confirmation GUI without submitting a bid.
- Verify all admin recovery commands and tab completions.

## Recovery guidance

- For `BID_PENDING`, check the bidder's balance before using `/clasherbid bid-review paid` or `unpaid`. If the bid state is already `BID_PENDING_ACCEPTED`, only `paid` can clear the marker.
- For payout/settlement review, inspect `/clasherbid settlement` and verify the Vault balance, seller payout, and item delivery before choosing `paid` or `unpaid`. The first choice is durably recorded and locks recovery to that decision; do not try the opposite option afterward. A failed Vault payout response pauses bidding and retries after 60 seconds; an invalid configured payout account requires fixing `payout-player` and using `/clasherbid retry`.
- For refund review, verify the recipient's balance before `/clasherbid refund-review <player> paid|unpaid`.
- For claim review, inspect the player's inventory before `/clasherbid claim-review <player> delivered|undelivered`.
- For start review, check whether the admin still holds the item before `/clasherbid start-review item-removed|item-still-held`.

Never issue a second payment or clear a recovery marker based only on an error message. Verify balances and inventories first. Frozen recovery is intentionally manual.
