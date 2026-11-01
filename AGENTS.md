# Working in this repository

This repository is private now and meant to go public later without rewriting
history. So tracked files, commit messages, screenshots and test fixtures never
contain homelab hostnames, IP addresses, ports, private domains, home-directory
paths, real project or client names, or Mac screenshots.

- Real values live in `local/setup.md` (ignored by git). Read it when you need
  them; write neutral wording in tracked files ("WSL over OpenSSH", "your ntfy
  server", `https://ntfy.example.org`).
- Example content uses invented names (website, api, docs; workstation, server).
- A local pre-commit hook checks staged changes against a local denylist. If it
  blocks a commit, fix the wording; never bypass it with `--no-verify`.

Start with `decisions.md`, then `openspec/changes/android-client-v1/`.
The visual contract is the signed-off renders in `design/`; `design-tokens.json`
and `design-brief.md` record them.
