# RustDesk Managed - Internal Deployment Fork

> **Internal-use project. This is not a public product, public service, or general-purpose RustDesk distribution.**

This repository contains a customized RustDesk client maintained for a single privately operated deployment. It remains publicly visible because it is a fork of the public upstream RustDesk repository.

Public access to this source code does **not** provide or imply:

- access to the operator's RustDesk infrastructure or management platform;
- permission to register devices with, connect to, or otherwise use that infrastructure;
- access to unattended-access credentials, enrollment credentials, signing keys, or production configuration;
- access to supported production binaries, installation services, maintenance, or technical support.

## Project scope

The maintained branch contains changes required for the operator's managed Android deployment, including self-hosted RustDesk configuration, managed unattended access, service lifecycle behavior, and internal device enrollment.

Production-specific configuration and credentials are supplied separately during controlled builds. They are not intended to be committed to this repository. A third-party clone is not configured or supported for use with the operator's systems.

## Status

The current internal production baseline is derived from RustDesk 1.4.9. Development is maintained on the `managed-android` branch and may diverge from upstream or change without notice.

No public release, compatibility promise, installation assistance, or support service is provided from this repository.

## Authorized use

Remote-access software must only be installed or used on devices and systems that you own or are explicitly authorized to administer.

Do not attempt to access, enroll with, test against, or interfere with infrastructure that you do not own or have explicit permission to use.

## Upstream project

This project is derived from [RustDesk](https://github.com/rustdesk/rustdesk) and is not an official RustDesk release.

- [RustDesk source](https://github.com/rustdesk/rustdesk)
- [RustDesk documentation](https://rustdesk.com/docs/)
- [RustDesk Server](https://github.com/rustdesk/rustdesk-server)

## License

RustDesk is licensed under the GNU Affero General Public License v3.0 (AGPL-3.0). This fork retains the applicable upstream license and attribution requirements.

See [LICENCE](LICENCE) for the full license text.
