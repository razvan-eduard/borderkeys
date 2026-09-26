<!--
SPDX-License-Identifier: GPL-3.0-or-later
SPDX-FileCopyrightText: 2026 BorderKeys contributors
-->

# Security

## Reporting

Report a vulnerability through GitHub's private reporting for this repository:
**Security → Report a vulnerability** on
[github.com/razvan-eduard/borderkeys](https://github.com/razvan-eduard/borderkeys/security/advisories/new).
The report is visible to the maintainers only until a fix is published. Please do not open a
public issue for something that could put people's text at risk.

Say what you found, how to reproduce it, which build (core or plus, version from the About
screen) and which Android version. A report is acknowledged within seven days; a fix for a
confirmed report ships in the next release, and the advisory is published with it.

## What is in scope

A keyboard sees everything typed into it. Anything that breaks one of these promises is a
vulnerability, whatever the mechanism:

- Text from a password field, or from any field while private mode is on, being stored,
  suggested, learned from, or shown on the strip without the person asking for it.
- Text leaving the device. The app declares no network permission and carries no code that
  can open a connection; a way around that is the most serious report there is.
- The encrypted database, its passphrase, a backup file or a transfer between the two builds
  exposing the personal dictionary or the clipboard history to another app or user.
- The text assistant, in the plus build, reading a field it was not given.
- A crash or a hang triggered from an editor, a pasted clip, an imported pack, a backup file
  or a layout file: the keyboard is what stands between the person and their phone.

Findings in the dictionaries' word lists, the quality of suggestions, or the accuracy of the
swipe decoder are not security reports; they are welcome as ordinary issues.

## Supported versions

The latest release on the releases page. Older versions get no separate fix; the fix is the
next release, which updates over any earlier one.

## Verifying a build

Every release APK carries a GitHub build attestation:

```
gh attestation verify BorderKeys-<version>-core.apk --repo razvan-eduard/borderkeys
```

The release workflow also checks that the published core APK asks for no permission and
carries no model or assistant code, and fails the release when it does.
