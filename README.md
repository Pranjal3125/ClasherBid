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
- `/clasherbid history` — recent auction history\n- `/clasherbid recover <player>` — recover a recorded failed refund (admin only)

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
