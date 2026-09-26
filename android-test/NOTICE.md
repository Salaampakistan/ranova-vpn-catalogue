# RANOVA VPN Connection Test

This is a temporary engineering test application.

It does **not** embed an OpenVPN engine. It talks to the separately installed
"OpenVPN for Android" application through that project's documented external
AIDL API.

The external API / remoteExample materials are published by the upstream
OpenVPN for Android project under the Apache License 2.0. The AIDL interface
names and method ordering are kept compatible with the upstream service.

Upstream:
https://github.com/schwabe/ics-openvpn

This test exists only to prove:
RANOVA GitHub catalogue -> selected OpenVPN profile -> Android VPN tunnel ->
public IP change.
