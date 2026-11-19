# HopTrace for Android

A native Android network diagnostic app that performs ICMP traceroute (IPv4 and IPv6), looks up public hop addresses with IPinfo City-level geolocation, and plots returned city coordinates on an OpenFreeMap basemap using MapLibre Native.

## Project configuration

- `compileSdk`: 37
- `targetSdk`: 37 (Android 17)
- `minSdk`: 26 (Android 8.0; runs on Android 15/16/17)
- Java 17 + Android NDK C++17
- MapLibre Native Android OpenGL SDK 13.6.1
- Material Components for Android 1.13.0

## Features

- ICMP Echo-based traceroute with TTL / IPv6 Hop Limit stepping.
- IPv4, IPv6, and DNS-based Auto selection.
- Linux ping sockets (`SOCK_DGRAM` + `IP_RECVERR` / `IPV6_RECVERR`) and the kernel error queue; no root permission is requested.
- Configurable hop limit, per-probe timeout and 1 or 3 probes per hop.
- City / region / country and ASN organization display for public router IPs.
- Seven-day local cache; duplicate IPs are only looked up once per cache period.
- Private, loopback, link-local, multicast and documentation IP ranges are not sent to the geolocation provider.
- MapLibre Native + OpenFreeMap `liberty` style; no map API key.

## IP geolocation setup

1. Create an IPinfo account and generate an access token for the **City-level / Legacy Free API** (not IPinfo Lite).
2. Install HopTrace, tap **IPinfo API**, and paste the token.
3. Run a trace. Public hop IPs are looked up and cached on the device for seven days.

IPinfo Lite has unlimited calls but only country-level data is included in that free product, so it cannot position individual hops on a city-level map. The City-level free endpoint is documented as 50,000 requests/month for token users. The app deliberately does not ship with a developer-owned token; each user supplies their own token.

## Build the APK

Open this folder in Android Studio (recent version that supports AGP 9.5). Install these SDK components from SDK Manager:

- Android SDK Platform 37
- Android SDK Build-Tools 36.0.0 or newer
- Android NDK 28.2.13676358
- CMake 3.22.1

Then choose **Build > Build Bundle(s) / APK(s) > Build APK(s)**. The debug APK is generated at:

`app/build/outputs/apk/debug/app-debug.apk`

Or run:

```bash
./gradlew assembleDebug
```

The first build requires internet access to resolve Gradle, Android Gradle Plugin, AndroidX, Material Components, and MapLibre dependencies.

## Network / accuracy caveats

- A traceroute hop is a router that chose to send an ICMP Time Exceeded response. Some routers suppress or rate-limit these packets; `*` timeouts are normal.
- Mobile carriers, CGNAT, firewalls, VPNs and asymmetric routing can hide hops or change the path.
- IP geolocation is an estimate based on an IP registry / commercial database. A pin is generally a city or network registration centroid, **not** a verified router location. Some IPs have no meaningful city coordinate.
- The app does not request Android device location permission.
- Hop IP addresses are sent to IPinfo when geolocation is enabled. The access token is stored in the app's private on-device preferences; it is not embedded in the APK. For a public, multi-user production release, put the token behind a backend proxy rather than shipping a shared API key.
- OpenFreeMap's public tile server currently publishes no usage quotas, but it is a community service rather than an SLA-backed commercial map platform. Attribute OpenStreetMap / OpenMapTiles as required by the rendered style.

## Native implementation note

`app/src/main/cpp/traceroute.cpp` implements the probe loop. It uses the OS ping-socket interface rather than privileged raw sockets. The kernel's extended error queue (`IP_RECVERR` / `IPV6_RECVERR`, read with `recvmsg(MSG_ERRQUEUE)`) supplies ICMP Time Exceeded / Destination Unreachable messages and the router address. Device kernels that disable unprivileged ping sockets or networks that filter ICMP can still prevent the operation; no-root support is not universally guaranteed across every vendor ROM.
