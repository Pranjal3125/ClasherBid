# Contributing to ClasherBid

Thanks for helping improve ClasherBid.

## Before making changes

- Check existing issues and discussions before starting large changes.
- Keep changes focused and explain the player/admin impact.
- Do not add a player-facing command that conflicts with common server plugins. In particular, do not register `/claim`; GriefPrevention owns that command on the target network.
- Preserve conservative recovery behavior. Never automatically repeat an economy transaction when its outcome is uncertain.

## Development

Requirements:

- Java 25
- Maven 3.9+
- Paper API 26.2-compatible development environment
- Vault API and a Vault-compatible economy provider for runtime testing

Build locally:

```bash
mvn --batch-mode clean package
```

GitHub Actions runs the same Maven package build on pushes to `main`, pull requests and manual dispatches. A successful build verifies compilation and packaging, not live server behavior.

## Pull request checklist

- [ ] Describe the issue and the behavior change.
- [ ] Keep command names and permissions documented in README.
- [ ] Update configuration documentation when adding/changing settings.
- [ ] Consider restart/crash behavior at every economy or item-delivery boundary.
- [ ] Do not retry uncertain withdrawals, deposits, refunds or item grants automatically.
- [ ] Test normal flow and failure/recovery flow on a staging Paper server.
- [ ] Include relevant console output or reproduction steps for bug fixes.

## Security and economy safety

Never include server credentials, database credentials, player personal data or production economy data in issues or pull requests. Use disposable test accounts and items when testing transaction recovery.
