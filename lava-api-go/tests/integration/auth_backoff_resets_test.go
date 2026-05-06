package integration_test

import (
	"net/http"
	"testing"
	"time"

	"digital.vasic.lava.apigo/tests/integration/testenv"
)

// TestIntegration_AuthBackoffResets_AfterValidUuid asserts the §6.J
// behavioral contract: a successful auth resets the per-IP failure
// counter so subsequent failures restart from step 0, NOT from where
// the previous failure left off.
//
// Sequence:
//   - 1 bad UUID  → 401, counter advances to 1, blockedUntil=now+50ms.
//   - sleep 100ms  → block window expires (counter still at 1).
//   - 1 good UUID → 200, counter RESETS (entry deleted from Ladder).
//   - 1 bad UUID  → 401, counter advances to 1 (NOT 2), blockedUntil=now+50ms.
//   - sleep 100ms → block window expires, counter still at 1.
//   - 1 bad UUID  → 401, counter advances to 2, blockedUntil=now+1h.
//   - 1 bad UUID  → 429 with Retry-After ≈ 3600.
//
// If the counter were NOT reset after the good UUID, then on the
// second-after-reset failure the counter would advance from 2 to "3"
// (clamped at last step = 1h), and Retry-After would be 3600 instead
// of 1. The Retry-After value is the load-bearing assertion.
func TestIntegration_AuthBackoffResets_AfterValidUuid(t *testing.T) {
	activeHex := "00000000000000000000000000000001"
	env := testenv.NewWithAuth(t, testenv.AuthFixture{
		ActiveUUIDHex: []string{activeHex},
		BackoffSteps:  []time.Duration{50 * time.Millisecond, 1 * time.Hour},
	})
	defer env.Close()

	bad := uuidBlobBase64(t, "ffffffffffffffffffffffffffffffff")
	good := uuidBlobBase64(t, activeHex)

	// 1. Bad → 401, counter=1, blocked for 50ms
	resp1 := mustReq(t, env, bad)
	if resp1.StatusCode != http.StatusUnauthorized {
		t.Fatalf("req1 status = %d, want 401", resp1.StatusCode)
	}
	resp1.Body.Close()

	// Wait for the 50ms block window to expire.
	time.Sleep(100 * time.Millisecond)

	// 2. Good → 200, counter RESETS to 0 (entry deleted from Ladder).
	resp2 := mustReq(t, env, good)
	if resp2.StatusCode != http.StatusOK {
		t.Fatalf("req2 status = %d, want 200", resp2.StatusCode)
	}
	resp2.Body.Close()

	// 3. Bad → 401 (NOT 429 — entry was deleted, counter restarts at 0
	//    then advances to 1, blocking for 50ms.)
	resp3 := mustReq(t, env, bad)
	if resp3.StatusCode != http.StatusUnauthorized {
		t.Fatalf("req3 status = %d, want 401 (block should have been reset by req2)", resp3.StatusCode)
	}
	resp3.Body.Close()

	// Wait for the 50ms block window to expire.
	time.Sleep(100 * time.Millisecond)

	// 4. Bad → 401, counter=2, blocked for 1h.
	resp4 := mustReq(t, env, bad)
	if resp4.StatusCode != http.StatusUnauthorized {
		t.Fatalf("req4 status = %d, want 401", resp4.StatusCode)
	}
	resp4.Body.Close()

	// 5. Bad immediately → 429 with Retry-After ≈ 3600 (1h step).
	//    If the reset DID NOT happen, after the original good UUID
	//    request 2 the counter would still have been at 1, then req3
	//    would have advanced to 2 (1h block), and req4 would have been
	//    a 429 not a 401 — the test would have already failed at req3.
	resp5 := mustReq(t, env, bad)
	defer resp5.Body.Close()
	if resp5.StatusCode != http.StatusTooManyRequests {
		t.Fatalf("req5 status = %d, want 429", resp5.StatusCode)
	}
	ra := resp5.Header.Get("Retry-After")
	if ra != "3600" {
		t.Fatalf("req5 Retry-After = %q, want \"3600\" (counter at step 1 = 1h)", ra)
	}
}
