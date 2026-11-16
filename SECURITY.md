# Security Policy

## Supported versions

| Version | Supported          |
| ------- | ------------------ |
| 1.1.0   | Yes                |

## Reporting a vulnerability

If you find a security problem in Clipboard Bridge, please report it privately through GitHub's **"Report a vulnerability"** button on the Security tab of this repository.

Please do not open a public Issue for a vulnerability. Include as much detail as you can: what the problem is, how to reproduce it, and which version is affected.

## Notes

- Clipboard Bridge only talks to your own PC on your local network. It has no servers, no analytics and no accounts.
- Pairing uses a 6-digit PIN with PBKDF2-SHA256, and all traffic is encrypted with AES-256-GCM.
- Keep the app on trusted WiFi only, and never share your signing key or PIN.
