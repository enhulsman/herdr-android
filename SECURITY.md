# Security

kudde holds an SSH key that opens your machines, so a security
problem in it matters. Thanks for taking the time to report one.

## Reporting a vulnerability

Please don't open a public issue. Report it privately instead:
[report a vulnerability](https://github.com/enhulsman/kudde/security/advisories/new)
(the repository's Security tab, then "Report a vulnerability"). Only the
maintainer sees it.

Say what you found, which version of the app (Android settings, Apps, kudde)
and of herdr-core it affects, and how to reproduce it. Leave out your own host
names, IP addresses, ports, paths and keys; a made-up example shows the problem
just as well.

You'll get a first reply within a week. Once a fix is out, the advisory is
published, with credit to you if you'd like.

## Supported versions

Only the latest release gets security fixes. The app is pre-1.0 and has a
single maintainer, so update to the latest release before reporting.

## In scope

- The app: key handling, host key checks, the SSH connection, the encrypted
  alerts, and anything that leaks what is on your machines.
- The release builds and how they are signed.

Problems in herdr-core itself, in herdr, or in your ntfy server belong to
those projects.
