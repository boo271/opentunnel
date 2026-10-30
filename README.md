# OpenTunnel

A small Android VPN app built on sing-box. I made it mainly for phones on data plans that only let social apps through, and it turned out useful for plain old privacy too.

It does three things:

- **Free Cloudflare WARP** over MASQUE. No login, no server of your own. This is the default.
- **Your own server** with VLESS + REALITY, if you have a VPS.
- **WireGuard**, including a normal WARP config.

There's also a "Share to PC" option, which gives a PC on your hotspot a proxy that goes through the tunnel.

No root needed. No analytics, no app accounts, no tracking. All apps on the phone go through the tunnel (there's no split tunneling yet).

Version 0.3.3, arm64 only, Android 7.0 or newer.

## What works

- WARP over MASQUE (TCP 443): works. The first connection needs a normal network, or an imported config, because sign-up gets blocked on social-only plans.
- VLESS + REALITY (TCP 443): works.
- WireGuard / classic WARP over UDP: implemented, but some carriers drop the UDP handshake. If that happens, use the MASQUE mode.
- Share to PC: works, with a username and password.
- IPv4 only. IPv6-only networks aren't supported yet.

## Install

Get `OpenTunnel-0.3.3-arm64.apk` from the [Releases page](https://github.com/boo271/opentunnel/releases) and install it. Check the hash first if you like:

```
SHA-256: b912c4c93e4f4a2d62a88f9d8a59684c4995d52eb2b3ef94a57dfd349219d809
Signing cert SHA-256: BD:DB:37:CB:90:D9:BE:E7:BD:D3:CC:EF:A9:C8:1A:6F:80:BD:2D:D3:D2:35:70:C8:50:EB:0C:9E:56:C0:C1:2A
```

## Using it

1. Open the app and go to **Edit profile**.
2. The default profile is **WARP (free)**. Leave it, tap **Save**, then **Connect**, and allow the VPN permission.
3. The first connection creates an anonymous Cloudflare device (no email, no payment). This needs a normal network for that one step.

To use your own server instead, pick **VLESS + REALITY**. You can paste a `vless://` link and tap **Import link**, or fill in the fields by hand: host, port, UUID, SNI, REALITY public key, short id, fingerprint (`chrome` is fine), and flow (leave it empty unless your server uses `xtls-rprx-vision`).

For WireGuard, pick the **WireGuard / WARP** chip and paste the whole `[Interface]` / `[Peer]` config.

### Social-only data plans

By default, WARP mode sends `m.facebook.com` as the TLS name, which gets through on many of these plans. The catch is the one-time sign-up at `api.cloudflareclient.com`, which those plans usually block. Two ways around it:

- Connect once over Wi-Fi. The registration is saved, and after that mobile data is fine.
- Or make a `warp.json` on a PC with usque and paste it in under **Settings > Import config…**.

If it still fails after that, your carrier is probably filtering by IP address, and no SNI trick will fix it. Use a VPS the plan allows, or regular data.

### Share to PC

Android doesn't send hotspot traffic through a VPN app, so a PC on your hotspot just goes out the normal way. To fix that, the app can run a proxy inside the tunnel:

1. Turn on **Settings > Share to PC (LAN)**. Default port is 1080. A username and random password are generated for you and shown on that screen.
2. On the PC, set an HTTP or SOCKS5 proxy to your phone's hotspot address (usually `192.168.43.1`) with that port and login.
3. Reconnect the tunnel if you change the port or password.

Two warnings. The proxy listens on every interface, so anyone on that network with the login can use your tunnel. And the HTTP login isn't encrypted on the LAN, so use a WPA2 hotspot, not an open one. Also, only apps that respect a proxy setting will use it. Games and other raw-socket apps won't.

## Setting up your own server

Any cheap VPS works. Oracle Cloud's free tier and GCP's free e2-micro are fine for testing. On Ubuntu or Debian, as root:

```bash
SNI=m.facebook.com bash server/setup.sh
```

It installs sing-box, generates a UUID and REALITY keys, writes the config, starts the service, and prints the fields plus a `vless://` link you can paste into the app. You still have to open TCP 443 in your cloud provider's firewall yourself.

About the SNI: REALITY needs a real TLS 1.3 site as its cover. I use `m.facebook.com` because it matches the social-only plans. If your VPS can't reach it, pick another TLS 1.3 site and use the same value on both the server and the app:

```bash
SNI=www.microsoft.com bash server/setup.sh
```

## Building

You need JDK 21 (newer ones break the Gradle version here), the Android SDK with platform 35, and the prebuilt `app/libs/libbox.aar`. That's sing-box 1.14.4, and `docs/BUILDING-LIBBOX.md` explains how I built it.

Linux / macOS:

```bash
cd opentunnel
./gradlew assembleDebug
```

Windows (PowerShell):

```powershell
$env:JAVA_HOME='C:\path\to\jdk21'
cd opentunnel
.\gradlew.bat assembleDebug
```

The APK ends up in `app/build/outputs/apk/debug/`.

For a signed release, put a `keystore.properties` in the project root:

```properties
storeFile=path/to/your-release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Then run `assembleRelease`. Keep that keystore safe. If you lose it, nobody can update an installed release.

The app is arm64 only, because the bundled sing-box core and the usque helper aren't built for 32-bit.

## How it works, briefly

- `TunnelService` is the `VpnService`. It creates the TUN device and hands it to sing-box.
- Sockets the core opens are marked with `protect()` so they don't loop back into the tunnel.
- The app looks up your server's address itself over DoH before connecting, so carrier DNS filtering doesn't get in the way. The core only gets IPv4 addresses.
- DNS inside the tunnel is DoH to `1.1.1.1`.
- Free WARP mode runs [usque](https://github.com/Diniboy1123/usque) v4.2.1 (MIT) as a local SOCKS5 proxy. Its connection goes through a small relay on the phone that opens the real socket on the physical network, so it doesn't loop into our own tunnel.

Where things live:

```
app/src/main/java/dev/opentunnel/app/
  MainActivity.kt
  data/        profiles, LAN proxy settings, vless:// parser
  service/     VpnService, sing-box config builder, WARP code
  ui/          Compose screens
app/libs/libbox.aar                          sing-box core
app/src/main/jniLibs/arm64-v8a/libusque.so   usque helper
server/setup.sh                              VPS setup script
docs/BUILDING-LIBBOX.md                      how libbox.aar was built
THIRD-PARTY-NOTICES.md                       bundled components
```

## Privacy

I don't collect anything. The only things that leave your phone are your tunnel traffic, the one-time anonymous Cloudflare registration for WARP, and DNS lookups to `1.1.1.1`. Your profiles and WARP registration are stored privately inside the app, and backups are off. Clearing the app's data wipes them.

## Disclaimer

This is an independent project. It is not affiliated with or endorsed by Cloudflare, Meta, or the sing-box project. WARP mode talks to Cloudflare's service with an unofficial client, and using a different SNI to get through a carrier's restrictions may go against your carrier's or Cloudflare's terms. That's your call and your responsibility. The software comes with no warranty.

Found a security problem? Please use the private report option on this repository's **Security** tab instead of posting it publicly.

## Credits

Made by [boo271](https://github.com/boo271), with development assistance from DeepSeek. Built on [sing-box](https://sing-box.sagernet.org/) and [usque](https://github.com/Diniboy1123/usque).

## License

Copyright (C) 2026 boo271. GPL-3.0, same as sing-box; see `LICENSE`. usque is MIT, and its license is in `LICENSES/`. Everything bundled is listed in `THIRD-PARTY-NOTICES.md`.
