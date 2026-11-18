# ClasherBid

GUI-based single-item auction plugin for Clasher Network.

## Target platform

- Paper API (target: Minecraft 26.2; verify matching API artifact before release)
- Java 21+
- Vault + a Vault-compatible economy provider

## Player command

- `/bid` — open the auction GUI

## Admin commands

- `/clasherbid start <minutes> <starting-price>` — start an auction using the item in the admin's main hand
- `/clasherbid cancel` — cancel and refund
- `/clasherbid status` — show current auction state

Requires `clasherbid.admin` (default: op).

## Build

```bash
mvn clean package
```

The output JAR is created under `target/`.

## Important status

This is an initial development implementation, not a verified production release. Test on a staging Paper server with a test economy before using on a live network. In particular, review restart recovery, payment/refund failure handling, offline item delivery, and GUI behavior against your exact Paper 26.2 build.
