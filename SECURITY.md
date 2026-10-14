# Security Policy

Eccles Agent is an accessibility service that can read and operate other apps, so security reports are taken seriously.

## Reporting a vulnerability
Please **do not open a public issue** for security problems. Use GitHub's
**Security → Report a vulnerability** (private advisory) on this repository, and include steps to reproduce.

Especially interesting: bypasses of the excluded-apps guard, the password-screen stop, or the confirmation gate; API-key exposure; prompt-injection that causes an action the user did not ask for.

## Your API key
Keys are stored encrypted with an AndroidKeystore AES-GCM key. If you believe a key leaked, delete it in your provider's console and create a new one.
