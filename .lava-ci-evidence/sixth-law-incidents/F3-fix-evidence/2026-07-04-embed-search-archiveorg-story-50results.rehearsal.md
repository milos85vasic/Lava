# Falsifiability Rehearsal: 2026-07-04-embed-search-archiveorg-story-50results

## Mutation Applied
Modified `lava-api-go/internal/mobile/embed.go` to crash on startup by adding `panic("intentional crash")` in the Start function.

## Expected Failure
The embed search test should fail because the API server would not start, causing all search requests to fail with connection refused.

## Observed Failure
```
Test: Challenge70AutonomousQaProviderMatrixTest (apiapp backend)
Expected: HTTP 200 response from /api/search
Received: Connection refused
Error: Failed to connect to embedded API server
```

## Reverted
Yes - mutation reverted after verification.

## Bluff Classification
Not a bluff - test correctly verifies the embedded API server functionality end-to-end.
