# Security Policy

## Reporting a vulnerability

Please do not report security vulnerabilities in public issues or discussions. Use
[GitHub's private vulnerability reporting form](https://github.com/wstein/flixw/security/advisories/new)
for this repository instead.

Include the affected wrapper version, operating system and Java version, a minimal
reproduction, the impact you believe is possible, and any mitigation you have identified.
Do not include credentials, private repository URLs, or downloaded compiler binaries.

## Scope

Security-sensitive areas include compiler and companion-asset acquisition, SHA-256
verification, lock parsing, cache-path containment, Java selection, environment handling,
and subprocess dispatch. Third-party plugins execute with the invoking user's privileges;
a digest identifies bytes but does not make plugin code safe.

The current release line is supported. Reports against older releases are still useful when
the issue reproduces on the current release.
