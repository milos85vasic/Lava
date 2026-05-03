# Authentication Contract: Multi-Provider Extension

**Version**: v1  
**Date**: 2026-05-02

## Auth-Token Header Format

The `Auth-Token` HTTP header carries authentication material from the Android client to the Go API.

### Format Specification

```
Auth-Token: <provider_id>:<credential_type>:<credential_value>
```

### Components

| Component | Description | Example |
|-----------|-------------|---------|
| `provider_id` | The provider identifier matching `TrackerDescriptor.id` | `rutracker`, `nnmclub`, `archiveorg` |
| `credential_type` | The authentication mechanism | `cookie`, `token`, `apikey`, `password`, `none` |
| `credential_value` | The actual credential (may be empty for `none`) | `bb_session=abc123`, `Bearer xyz`, `IA-S3-Key=abc` |

### Valid Combinations

| Provider Type | credential_type | credential_value format |
|---------------|-----------------|------------------------|
| Torrent trackers (RuTracker, NNMClub, Kinozal) | `cookie` | `phpbb3_sid=abc123` or `bb_session=abc123` or `uid=123;pass=abc` |
| Token-based APIs | `token` | `Bearer <token>` or `<raw_token>` |
| API Key services | `apikey` | `key=<key>` or `key=<key>;secret=<secret>` |
| Form login (legacy) | `password` | `username=<user>;password=<pass>` (discouraged; use session cookie instead) |
| Anonymous / public providers | `none` | Empty string |

### Backward Compatibility

Tokens without any colon (`:`) character are treated as RuTracker `cookie` credentials:

```
Auth-Token: abc123
// Interpreted as: rutracker:cookie:bb_session=abc123
```

### Credential Lifecycle

1. **Creation**: User creates credential in Android Settings → encrypted with Android Keystore → stored in Room
2. **Association**: User selects credential for a provider in Login screen → `ProviderConfig.activeCredentialId` updated
3. **Transmission**: Android client reads credential → constructs `Auth-Token` header → sends over TLS
4. **Translation**: Go API auth middleware parses header → constructs `Credentials` struct → passes to Provider
5. **Validation**: Provider implementation validates credential against upstream service → returns auth result
6. **Persistence**: Valid session tokens/cookies are stored in Android credential store for reuse
7. **Backup**: Credentials included in Android Backup Service for device restoration
8. **Deletion**: User deletes credential → associated providers fall back to anonymous mode or show "credentials required"

### Security Requirements

- Credential values MUST NEVER be logged at DEBUG or higher levels
- Credential values MUST be transmitted only over TLS (HTTPS)
- Credential values in transit MUST be treated as sensitive headers (not cached by proxies)
- Go API MUST NOT persist credential values in database or logs
- Android client MUST encrypt credential values at rest using Android Keystore or EncryptedSharedPreferences
- Cloud backup MUST use Android's encrypted backup mechanism (Android Backup Service encrypts backup data with device key)

### Error Contract

| Scenario | HTTP Status | Response Body |
|----------|-------------|---------------|
| Missing Auth-Token on auth-required provider | 401 | `{"error": "Authentication required", "provider": "nnmclub"}` |
| Invalid credential format | 401 | `{"error": "Invalid credential format", "expectedFormat": "provider_id:type:value"}` |
| Expired session | 401 | `{"error": "Session expired", "action": "relogin"}` |
| Invalid credentials (upstream rejected) | 403 | `{"error": "Invalid credentials", "provider": "kinozal"}` |
| Anonymous access attempted on auth-only endpoint | 403 | `{"error": "Anonymous access not supported for this action"}` |
