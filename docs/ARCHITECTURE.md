# Local Network Discovery Architecture

## Sequence Diagram

```
┌─────────┐     ┌──────────────┐     ┌─────────────────┐     ┌─────────────┐
│  User   │     │  MenuScreen  │     │ MenuViewModel   │     │ DiscoverUse │
└────┬────┘     └──────┬───────┘     └────────┬────────┘     └──────┬──────┘
     │                 │                      │                     │
     │  Open Settings  │                      │                     │
     │────────────────>│                      │                     │
     │                 │  LaunchedEffect      │                     │
     │                 │─────────────────────>│                     │
     │                 │                      │  invoke()           │
     │                 │                      │────────────────────>│
     │                 │                      │                     │
     │                 │                      │<────────────────────│
     │                 │                      │  Discovered /       │
     │                 │                      │  NotFound /         │
     │                 │                      │  AlreadyConfigured  │
     │                 │                      │                     │
     │                 │  OpenConnectionSettings (if discovered)    │
     │                 │<─────────────────────│                     │
     │                 │                      │                     │
     │  Show endpoints │                      │                     │
     │<────────────────│                      │                     │
```

## Data Flow

```
┌─────────────────────────────┐
│  LocalNetworkDiscoveryService │  (core:data)
│  - NsdManager on Android     │
│  - callbackFlow              │
└─────────────┬───────────────┘
              │ Flow<DiscoveredEndpoint>
              ▼
┌─────────────────────────────┐
│ DiscoverLocalEndpointsUseCase│  (core:domain)
│  - firstOrNull() with timeout│
│  - adds mirror to repo       │
│  - sets active endpoint      │
└─────────────┬───────────────┘
              │ DiscoverLocalEndpointsResult
              ▼
┌─────────────────────────────┐
│       MenuViewModel          │  (feature:menu)
│  - onCreate: discovers       │
│  - posts side effect         │
└─────────────┬───────────────┘
              │ MenuSideEffect.OpenConnectionSettings
              ▼
┌─────────────────────────────┐
│       MenuScreen             │  (feature:menu)
│  - collects side effects     │
│  - shows bottom sheet        │
└─────────────────────────────┘
```

## Proxy Advertisement

```
┌─────────────────────────────┐
│  ServiceAdvertisement        │  (proxy)
│  - JmDNS.create()            │
│  - registerService()         │
│  - type: _lava._tcp.local.   │
└─────────────────────────────┘
```

## Module Dependencies

```
feature:menu
    └── core:domain
            ├── core:data
            │       └── LocalNetworkDiscoveryService (NsdManager)
            └── core:models

proxy
    └── JmDNS advertisement
```
