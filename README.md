# ClasherBid

GUI-based single-item auction plugin for Clasher Network.

## Target platform

- Paper API (target: Minecraft 26.2; verify matching API artifact before release)
- Java 25
- Vault + a Vault-compatible economy provider

## Player command

- `/bid` — open the auction GUI

## Admin commands

- `/clasherbid start <minutes> <starting-price>` — start an auction using the item in the admin's main hand (1–120 minutes; positive starting price)
- `/clasherbid cancel` — cancel and refund
- `/clasherbid status` — show current auction state
- `/clasherbid history` — recent auction history
- `/clasherbid recover <player>` — start guarded recovery of a recorded failed refund (admin only)
- `/clasherbid refund-review <player> <paid|unpaid>` — resolve an interrupted or uncertain refund recovery; verify economy balances before deciding
- `/clasherbid bid-review <paid|unpaid>` — resolve an interrupted bid withdrawal; verify the bidder balance before deciding
- `/clasherbid settlement` — inspect a frozen payout for manual review (admin only)
- `/clasherbid settlement paid` — admin confirms payout was completed; finalizes winner item delivery
- `/clasherbid settlement unpaid` — admin confirms no payout occurred; returns item and records the bidder refund as a recovery liability (recover separately after review)

Requires `clasherbid.admin` (default: op).

## Build

```bash
mvn clean package
```

The output JAR is created under `target/`.

## Important status

This is an initial development implementation, not a verified production release. Test on a staging Paper server with a test economy before using on a live network. In particular, review restart recovery, payment/refund failure handling, offline item delivery, and GUI behavior against your exact Paper 26.2 build.

## Settlement safety / live-server warning

Vault economy transactions and plugin YAML persistence are not a single atomic transaction. A server crash during a bid refund or auction settlement can leave an uncertain transaction outcome. The plugin records failed refund attempts for admin recovery, but it cannot guarantee exactly-once payouts across every crash window.

**Before live use:** test the following on a staging server with a test economy:
- Restart during an active auction with and without a top bidder.
- Outbid a player, then simulate a restart immediately afterward.
- Finish an auction and restart around the payout/delivery moment.
- Cancel an auction while a bid is escrowed.
- Test offline winner delivery and inventory-full handling.

If a crash occurs during settlement, check the economy balance, active-auction state, pending deliveries, and refund-recovery config before manually retrying a payment. Avoid blindly issuing a second payout.

## Bid withdrawal review

A new bid is persisted as `BID_PENDING` before Vault withdrawal. If startup finds an interrupted bid marker, the auction remains frozen for admin review. Check the bidder's balance before using `/clasherbid bid-review paid` (accepts the bid and queues the previous bidder's refund liability) or `unpaid` (discards the pending bid). If the bid state was already accepted but the marker remained, only `paid` can clear the marker; do not select unpaid.

## Settlement review

When an auction payout enters the Vault transaction window, ClasherBid writes a persistent `settlementReview` marker before attempting the deposit. If the server crashes in this uncertain window, the auction is frozen on startup rather than automatically paying again. Admins can inspect the frozen auction with `/clasherbid settlement`. If balances have been verified, `/clasherbid settlement paid` finalizes delivery without another payout, while `/clasherbid settlement unpaid` returns the item and attempts a bidder refund. These commands require careful manual verification.

This is a conservative safety hold—not a complete automated reconciliation system. Verify the Vault economy balance, seller payout, and item delivery before taking manual action. Do not blindly retry a payout. Frozen settlement recovery is intentionally manual at this stage.
