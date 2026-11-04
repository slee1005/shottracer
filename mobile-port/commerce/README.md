# Free distribution configuration

On 2026-11-04 the owner replaced the USD 2.99 monthly plan with free distribution. The apps require no expiry period, payment, login or code. The default watermark remains.

- Android Play Billing and iPhone StoreKit purchase, restoration and expiry gates are removed.
- Android has no billing/network permissions or billing-public-key build requirement.
- `products.json` describes free distribution with no products. `ShotTracer.storekit` is an empty local fixture.
- Legacy expired access records are ignored; projects and album videos remain. The separate Android tester package is also free and accepts no extension codes.
- `tools/issue_access_codes.py`, its public key and tests remain historical developer utilities, outside the app and unrelated to free access. Prior subscription implementation is in Git commit `9ece37c`.
- Continue using the existing local upload signing key. Never commit keys, passwords or credentials.

See the [free Play release guide](PLAY_RELEASE.md). Store publication and physical-device validation remain separate. If subscriptions are already active, an app update does not cancel them in the store; review them separately in the Console. This task does not inspect or change account subscriptions.
