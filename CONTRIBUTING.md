# Contributing to vaelii-foreign

`vaelii-foreign` is a sibling of the
[Vaelii](https://github.com/vaelii/vaelii) engine, licensed under the
**Apache License 2.0** (see [`LICENSE`](LICENSE)). The core engine is
SSPL-licensed; this repo is part of the permissive Apache-2.0 layer, so its
contributor terms are correspondingly lighter.

## Inbound = outbound

By submitting a contribution you agree it is licensed under Apache-2.0 — the
same terms as this repository (Apache-2.0 §5, which already licenses inbound
contributions to the project). **No separate CLA is required** for this repo.

## Sign off your commits (DCO)

Every commit in a pull request must be signed off under the
[Developer Certificate of Origin](DCO) (DCO 1.1, by the Linux Foundation),
which certifies you have the right to submit it:

```bash
git commit -s                 # appends Signed-off-by: Your Name <email>
git rebase HEAD~N --signoff   # sign off the last N commits, if you forgot
```

The [DCO GitHub App](https://github.com/apps/dco) checks every commit in a
pull request and blocks the merge if any is missing a sign-off.

## Where a format's specification may come from

This repo reads formats it does not own, so a patch to a reader carries a
provenance question that a patch elsewhere does not.
[`licenses/THIRD-PARTY.md`](licenses/THIRD-PARTY.md) is the full account; the
short form for a contributor:

- **Work from the Apache-2.0 sources.** Cycorp published CFASL's reference
  implementation, and the LarKC release carries it per-file. It is complete for
  what this repo reads.
- **Not from `clyc`.** It is AGPL-3.0, and an AGPL derivation cannot ship under
  this repo's license — the fact that its Cyc-derived files sit on an Apache-2.0
  upstream does not travel to you through them.
- **Not from the OpenCyc Knowledge Server binary.** Its license forbids reverse
  assembly, reverse compilation and translation.

A pull request touching `cfasl.clj` or `units.clj` should say in its description
which upstream file the change came from. Where the answer is subtle, a comment
in the source saying it is worth more than one in the PR.

## Conventions

Shared coding, commit, and review conventions follow the core engine's
[`CONTRIBUTING.md`](https://github.com/vaelii/vaelii/blob/main/CONTRIBUTING.md):

- **Pull requests target `develop`.** `main` carries releases and is pushed by
  the maintainer, so it is never a pull-request target.
- Conventional Commits subjects: `type(scope): subject`, the scope optional.
- Define functions before use; reorder rather than `declare`.
- Comments describe what the code does now, never what it used to do.
- `Co-Authored-By:` / `Co-developed-by:` trailers are **human-only** — welcome
  for human collaborators, never for a tool, bot, or other non-human author.

Run [`lein gate`](README.md#development) before opening a pull request: it is
`lein lint` and then the suite.

## Code of conduct

Participation is governed by the project's
[Code of Conduct](https://github.com/vaelii/.github/blob/main/CODE_OF_CONDUCT.md)
(Contributor Covenant 2.1). Report concerns to support@vaelii.com.
