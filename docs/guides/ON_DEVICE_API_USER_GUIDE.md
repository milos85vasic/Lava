# Lava API on Your Device — User Guide

> **Status (2026-06-02):** This guide describes the *Lava API on this device*
> feature. The engine that powers it (the Lava API server, cross-compiled to run
> inside an Android app, with a local database) is built and tested. The **Lava
> API app itself now exists** (the `:api-app` module): it installs, runs the
> server as a background service with a system notification, discovers/announces
> itself on the network, and remembers its access key across restarts. What is
> still being built is the **on-screen control panel** — the screen you tap to
> see status, start/stop, and read the access key. Sections that describe
> on-screen buttons, the displayed key, and the notification copy are marked
> **PENDING Phase D-ui (screenshots to follow)**. This guide is written so it is
> ready the moment that screen lands. Nothing here promises a screen that does
> not yet exist.

## What is the Lava API app?

Normally the Lava API runs on a computer, a home server, or a NAS, and your
phone connects to it over your home Wi-Fi. The Lava API app turns that around:
**it lets your phone or tablet itself run the Lava API**, so other devices on
the same Wi-Fi can connect to your device instead of a separate server.

In plain terms:

- You install one extra app (the Lava API app) alongside the normal Lava client.
- You tap **Start**, and your device becomes a Lava API that lives on your home
  network.
- Other Lava apps on the same Wi-Fi — another phone, a tablet, a TV — can find
  it automatically and use it.
- You can **Stop** it any time, or **Restart** it.

It stores its data in a small local database file on the device (no separate
database server needed), and it talks to other devices securely over HTTPS.

## Is it the same as the normal Lava client?

No. There are two separate apps:

- **The Lava client** — the app you use to search and browse trackers. This is
  unchanged.
- **The Lava API app** — the new app whose only job is to *run the API* on your
  device so the network can use it.

They have different names on your device so you can tell them apart. The API app
ships in two variants with distinct identifiers, so a tester can install both
side-by-side: a dev/debug variant (`digital.vasic.lava.api.dev`) and a release
variant (`digital.vasic.lava.api`). The two variants announce themselves under
distinct network identities (the dev variant under a `-dev` service type), so
they never collide when both are running on the same Wi-Fi.

## How to start, stop, and restart it

> **PENDING Phase D-ui (screenshots to follow).** The control screen and its
> buttons are still being built (the underlying start/stop/restart logic and the
> notification controls already work — Phase D-infra). When the screen lands,
> this section will show the exact buttons and a screenshot of each state. The
> intended behavior is:

1. Open the Lava API app.
2. You see a clear status indicator: **Stopped**, **Running**, or an error
   message.
3. Tap **Start** to bring the API up. The status changes to **Running** and the
   screen shows the address other devices can use (something like
   `https://<your-lan-ip>:8443`) and a live counter of how many requests it has
   served.
4. Tap **Stop** to bring it down. The status returns to **Stopped**.
5. Tap **Restart** to stop and start again in one step (useful if you changed
   your network).

## What does "Running" mean?

When the app says **Running**, it means:

- The Lava API server is live inside your device.
- It is listening on your local network (not just on the device itself), so
  other devices on the same Wi-Fi can reach it.
- It is reachable at `https://<your-lan-ip>:<port>` (the screen shows the exact
  address and all the local IP addresses other devices can use).
- It requires a key to answer requests (see *Pairing* below) — being on the same
  Wi-Fi is not enough; the connecting device must present the correct key.

When it says **Stopped**, the server is fully shut down and nothing on the
network can reach it.

## How other devices on the network find and connect to it

Other Lava clients on the same Wi-Fi discover your device **automatically** using
mDNS (the same "find devices on the local network" mechanism printers use). Your
running API announces itself on the network (this announcing is built and
working — Phase D-infra), and other Lava apps list it as an available API
instance. The announcement carries tags that mark it as the Go engine running on
an Android device with a local SQLite database, plus the running version.

Because your device is running the API itself, it appears in the list with an
identity that marks it as running on an Android device — so users can tell it
apart from a server-hosted API. (The label that distinguishes "an Android
device on this network" is **PENDING sub-project 2** in the Lava client; until
then it appears as a normal Lava API instance.)

Requirements for discovery to work:

- Both devices must be on the **same local network** (same Wi-Fi or LAN).
- The network must allow multicast traffic (most home routers do by default).
- The API app must be **Running** on your device.

## The access key (pairing)

The on-device API is protected. Being on the same Wi-Fi does **not**
automatically grant access — a connecting device must present the correct
**access key**. This is the same security mechanism the server-hosted Lava API
uses.

- When you start the API, it uses an access key. If you have not set one, the
  app **generates one for you** automatically (a random key created on the device)
  so the API is never left open.
- The key is **remembered across restarts** — it is stored encrypted on the
  device, so devices you have paired keep working after you stop and start the
  API or reboot your phone (this storage is built — Phase D-infra).
- The key will be shown in the app so you can share it with the other devices you
  want to allow (this is "pairing").

> **PENDING Phase D-ui (screenshots to follow).** Where the key is shown on
> screen and how you copy or share it are part of the control screen still being
> built. When it lands, this section will show exactly where to find and share
> the key. The key is stored encrypted and is never written to logs.

## The system notification

While the API is running, the app shows a **persistent system notification** so
you always know it is on and can control it without reopening the app (this is
built — Phase D-infra). The notification shows the reachable address and a live
request count, carries **Stop** and **Restart** buttons, and tapping it opens
the app.

> **PENDING Phase D-ui (screenshots to follow).** The notification's exact
> wording is intentionally minimal for now and will be refined alongside the
> control screen. A screenshot will be added when the screen lands.

## Privacy and safety

- Your device's API is reachable by other devices on your **local network only**
  — it is not exposed to the public internet by this feature.
- Access requires the access key; repeated wrong-key attempts from a device are
  slowed down automatically (a backoff that escalates if someone keeps guessing).
- The connection is encrypted (HTTPS) using a certificate the app generates on
  your device the first time it runs.
- The access key is shown only in the app and is never written to logs.

## Troubleshooting

| Issue | What to check |
|---|---|
| Other devices can't find my API | Make sure the API app shows **Running**, both devices are on the same Wi-Fi, and your router allows multicast (port 5353/UDP). |
| A device finds it but gets rejected | The connecting device needs the correct access key (see *Pairing*). |
| It won't start | Make sure you granted the app notification permission and that no other copy of the API is already running on the device. |

## See also

- Architecture and internals: [`docs/ON_DEVICE_API.md`](../ON_DEVICE_API.md)
- Local network discovery: [`docs/LOCAL_NETWORK_DISCOVERY.md`](../LOCAL_NETWORK_DISCOVERY.md)

`Classification:` project-specific.
