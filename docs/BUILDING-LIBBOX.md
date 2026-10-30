# libbox.aar

`app/libs/libbox.aar` is sing-box v1.14.4, built from the upstream source. It is
not the AAR published by the sing-box-for-android project.

- Source: https://github.com/SagerNet/sing-box
- Tag: `v1.14.4`
- Commit: `c005f35300ce76427dc285481ec5695f5b179cd2`
- The build uses upstream code as-is; no sing-box source changes.

Toolchain used on Windows:

- Go 1.25.5
- github.com/sagernet/gomobile v0.1.12
- Android NDK r27c (clang 18.0.3), API 24
- JDK 21

From the sing-box checkout:

```sh
gomobile bind -v -o libbox.aar \
  -target android/arm64 \
  -androidapi 24 \
  -javapkg=io.nekohasekai \
  -libname=box \
  -trimpath \
  -buildvcs=false \
  -ldflags "-X github.com/sagernet/sing-box/constant.Version=1.14.4 -X runtime.godebugDefault=multipathtcp=0,tlssha1=1 -checklinkname=0 -s -w -buildid=" \
  -tags with_gvisor,with_quic,with_wireguard,with_utls,with_naive_outbound,with_clash_api,with_usbip,with_openvpn,with_openconnect,badlinkname,tfogo_checklinkname0,with_tailscale,ts_omit_logtail,ts_omit_ssh,ts_omit_drive,ts_omit_taildrop,ts_omit_webclient,ts_omit_doctor,ts_omit_capture,ts_omit_kube,ts_omit_aws,ts_omit_synology,ts_omit_bird \
  ./experimental/libbox
```

Copy the resulting `libbox.aar` to `app/libs/`. The AAR is arm64-v8a only and
declares minSdk 24.

The bundled file has SHA-256
`faad510cb3e8270859f6a1e1d2741e723a476980ba5dff0415e9095ef29e9053`. It contains
gVisor, QUIC, WireGuard, uTLS, naive, Clash API, USB/IP, OpenVPN, OpenConnect and
Tailscale, per the tag list above.

The upstream commit is the corresponding source for GPL-3.0. The app source and
third-party notices are in this repository.
