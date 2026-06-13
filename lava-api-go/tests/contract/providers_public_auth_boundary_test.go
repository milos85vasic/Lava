// providers_public_auth_boundary_test.go is the STRONGEST real-stack proof of
// the real-device onboarding fix (commit 9ae9ab90, Crashlytics 47b000d5,
// v1.3.4): GET /providers MUST be reachable WITHOUT a Lava-Auth header so a
// freshly-DISCOVERED API (mDNS, no pre-shared key) can populate the onboarding
// provider catalogue — WHILE the per-provider operations (/v1/{id}/...) stay
// auth-gated.
//
// Difference from providers_contract_test.go (the sibling capability-honesty
// gate) and from router_config_wiring_test.go's TestBuild_ProvidersOpenWith-
// FullAuthChain (the in-package unit test):
//
//   - providers_contract_test.go builds with Cfg=nil, so NO auth middleware is
//     mounted. It proves catalogue shape + §6.E honesty, NOT the auth boundary.
//   - router_config_wiring_test.go's unit test mounts the full chain but with an
//     EMPTY default registry (no providers) and only asserts /providers != 401.
//     It does NOT prove that the per-provider operations STAYED gated, which is
//     the security half of the fix.
//
// THIS test boots the SAME production router.Build that cmd/lava-api-go/main.go
// and internal/mobile use, with:
//   - the FULL production auth chain (non-nil *config.Config + a real
//     *ladder.Ladder + a REAL configured allowlist: AuthFieldName, AuthHMACSecret,
//     one active client UUID), exactly like the composition root, and
//   - a REAL native provider registry (rutracker/archiveorg/gutenberg adapters),
//
// then drives real httptest requests through engine.ServeHTTP (no direct-handler
// shortcut — Sixth Law clause 1) and asserts the THREE-WAY user-visible boundary
// the fix establishes:
//
//	(A) UNAUTH GET /providers              → 200 + {"providers":[...]} catalogue
//	    (the onboarding fetch path the real device exercised; the WHOLE point of
//	    the fix).
//	(B) UNAUTH GET /v1/rutracker/search    → 401 (the catalogue being public did
//	    NOT open the per-provider operations — the security invariant).
//	(C) AUTH'd GET /v1/rutracker/search    → NOT 401 (proves (B)'s 401 came from
//	    the auth gate being real, not from the route being unconditionally
//	    rejected — guards against a vacuous (B)).
//
// No Postgres is required: /providers builds its catalogue from registry.All()
// and the auth gate's verdict (401 vs pass) is decided before any handler /
// cache code runs. The gin engine returned by router.Build IS the production
// wire surface — this is the strongest real-stack form available without
// standing up the HTTP/3 socket, which adds nothing to an auth-boundary proof.
package contract

import (
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/prometheus/client_golang/prometheus"

	"digital.vasic.lava.apigo/internal/auth"
	"digital.vasic.lava.apigo/internal/config"
	"digital.vasic.lava.apigo/internal/router"
	"digital.vasic.ratelimiter/pkg/ladder"
)

// authBoundaryFixture bundles the production engine built with the full auth
// chain plus the header value of a valid (active) client, so the test can probe
// the gated route both WITHOUT and WITH a real key.
type authBoundaryFixture struct {
	engine http.Handler
	// validHeaderName + validHeaderValue together form the Lava-Auth header an
	// allowlisted Android client sends. validHeaderValue is the base64 of the
	// raw 16 bytes of the active UUID — the exact wire shape middleware.go
	// base64-decodes then HMACs.
	validHeaderName  string
	validHeaderValue string
}

// newAuthBoundaryFixture builds the production router with the SAME conditional
// branches the composition root turns on: a non-nil *config.Config carrying a
// real auth allowlist (field name + HMAC secret + one active client) AND a real
// *ladder.Ladder, plus a real native provider registry. This is what makes the
// auth middleware actually gate /v1/{id}/... while /providers (registered before
// the auth Use) stays public.
func newAuthBoundaryFixture(t *testing.T) authBoundaryFixture {
	t.Helper()

	// One active client. The secret + UUID are synthetic test fixtures (§6.R:
	// test files may use synthetic literals; they are NOT real production
	// values and never touch the network). The active map is keyed by
	// hex(HMAC-SHA256(uuidBytes, secret)) — exactly the shape config's
	// parseClientsList produces at boot — seeded via the auth package's
	// TestOnly hasher so this test does not have to reimplement the HMAC.
	secret := []byte("contract-test-secret-0123456789AB")
	activeUUIDHex := "0123456789abcdef0123456789abcdef" // 32 hex chars = 16 bytes
	activeUUIDBytes, err := hex.DecodeString(activeUUIDHex)
	if err != nil {
		t.Fatalf("decode active uuid hex: %v", err)
	}
	activeHash := auth.TestOnlyHashUUID(secret, activeUUIDBytes)

	cfg := &config.Config{
		// Auth allowlist — turns the auth middleware into a real gate.
		AuthFieldName:     "Lava-Auth",
		AuthHMACSecret:    secret,
		AuthActiveClients: map[string]string{activeHash: "android-contract-test"},
		// The remaining fields mirror router_config_wiring_test.go's fullCfg()
		// so every conditional middleware branch in Build is mounted (the same
		// chain production runs). Synthetic, never dialled at Build time.
		ProtocolMetricEnabled: true,
		BrotliResponseEnabled: true,
		BrotliQuality:         4,
		HTTP3Enabled:          true,
		Listen:                ":8443",
		AuthTrustedProxies:    nil,
	}

	engine := router.Build(router.Deps{
		Cfg:        cfg,
		Cache:      newNativeRegistryStubCache(),
		Registry:   newNativeRegistry(), // real rutracker/archiveorg/gutenberg adapters
		PromReg:    prometheus.NewRegistry(),
		AuthLadder: ladder.New([]time.Duration{time.Second}),
	})

	return authBoundaryFixture{
		engine:           engine,
		validHeaderName:  cfg.AuthFieldName,
		validHeaderValue: base64.StdEncoding.EncodeToString(activeUUIDBytes),
	}
}

// newNativeRegistryStubCache reuses the contract package's contractStubCache so
// the search handler (if ever reached) does not need a Postgres backend. The
// auth-boundary assertions decide before the handler runs, so the cache is only
// here to satisfy the Deps.Cache interface.
func newNativeRegistryStubCache() contractStubCache { return contractStubCache{} }

// TestProviders_PublicCatalogue_PerProviderStillGated is the load-bearing
// real-stack regression for the onboarding fix. See the file header for the
// three-way boundary it proves.
//
// Bluff-Audit:
//
//	Test:     TestProviders_PublicCatalogue_PerProviderStillGated
//	Mutation: in internal/router/router.go, move the
//	          `engine.GET("/providers", v1handlers.NewProvidersHandler(...))`
//	          registration from BEFORE the auth middleware back to AFTER the
//	          `engine.Use(auth.GinMiddleware())` block (the original v1.3.4 bug:
//	          /providers registered inside the auth-gated chain).
//	Observed: sub-test "A_unauth_providers_public" FAILED:
//	          "UNAUTH GET /providers returned 401 (body=\"{\\\"error\\\":\\\"unauthorized\\\"}\")
//	           — the catalogue is auth-gated; this IS the real-device onboarding
//	           bug. It must be registered BEFORE the auth middleware."
//	          Sub-tests B (unauth /v1/search blocked at the Lava-Auth gate) and C
//	          (valid key crosses the gate) continued to PASS — the per-provider
//	          gate was unaffected by the mutation — which is exactly the
//	          security-boundary discrimination this test adds over the existing
//	          empty-registry unit test.
//	Reverted: yes (router.go restored; full re-run green — see task report).
func TestProviders_PublicCatalogue_PerProviderStillGated(t *testing.T) {
	fx := newAuthBoundaryFixture(t)

	// (A) The onboarding path: UNAUTHENTICATED GET /providers MUST return 200
	// with the catalogue JSON. This is the exact request the freshly-discovered
	// API received from the real device — it MUST NOT be 401.
	t.Run("A_unauth_providers_public", func(t *testing.T) {
		w := httptest.NewRecorder()
		fx.engine.ServeHTTP(w, httptest.NewRequest(http.MethodGet, "/providers", nil))

		if w.Code == http.StatusUnauthorized {
			t.Fatalf("UNAUTH GET /providers returned 401 (body=%q) — the catalogue is "+
				"auth-gated; this IS the real-device onboarding bug. It must be registered "+
				"BEFORE the auth middleware.", w.Body.String())
		}
		if w.Code != http.StatusOK {
			t.Fatalf("UNAUTH GET /providers returned %d (body=%q); want 200 — the provider "+
				"catalogue must be publicly reachable for onboarding.", w.Code, w.Body.String())
		}

		// Primary assertion is on the USER-VISIBLE body the Android wizard parses:
		// a providers[] catalogue carrying the native providers. A 200 with an
		// auth-error body would still be a bug.
		var resp providersResponse
		if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil {
			t.Fatalf("UNAUTH GET /providers body is not a providers catalogue JSON: %v; body=%q",
				err, w.Body.String())
		}
		ids := map[string]bool{}
		for _, p := range resp.Providers {
			ids[p.ID] = true
		}
		for _, want := range []string{"rutracker", "archiveorg", "gutenberg"} {
			if !ids[want] {
				t.Fatalf("UNAUTH GET /providers catalogue missing native provider %q; got ids=%v",
					want, idsOf(resp.Providers))
			}
		}
	})

	// (B) The security invariant: making the catalogue public MUST NOT have
	// opened the per-provider operations. An UNAUTHENTICATED GET on an auth-gated
	// route (/v1/rutracker/search) MUST still be rejected by the Lava-Auth gate
	// with 401 + the gate's own {"error":"unauthorized"} body.
	//
	// The body shape is load-bearing here: the Lava-Auth gate
	// (auth.NewMiddleware) emits {"error":"unauthorized"}, whereas a DEEPER
	// per-provider credential rejection (provider.ErrUnauthorized →
	// writeProviderError) emits a bare {}. Asserting the gate's body proves the
	// request was stopped AT THE LAVA-AUTH GATE — i.e. the auth chain the fix
	// left in front of /v1/* is intact — not merely that some layer returned 401.
	t.Run("B_unauth_search_blocked_at_lava_auth_gate", func(t *testing.T) {
		w := httptest.NewRecorder()
		fx.engine.ServeHTTP(w, httptest.NewRequest(http.MethodGet, "/v1/rutracker/search?query=x", nil))

		if w.Code != http.StatusUnauthorized {
			t.Fatalf("UNAUTH GET /v1/rutracker/search returned %d (body=%q); want 401 — "+
				"the fix made the CATALOGUE public but per-provider operations MUST stay "+
				"auth-gated. A non-401 here means the auth boundary was widened too far.",
				w.Code, w.Body.String())
		}
		// Stopped at the Lava-Auth gate specifically (not the deeper per-provider
		// credential check), proving the gate the fix preserved in front of /v1/*
		// is what rejected the header-less request.
		var gateBody struct {
			Error string `json:"error"`
		}
		if err := json.Unmarshal(w.Body.Bytes(), &gateBody); err != nil || gateBody.Error != "unauthorized" {
			t.Fatalf("UNAUTH GET /v1/rutracker/search 401 body=%q; want the Lava-Auth gate's "+
				`{"error":"unauthorized"} — the request must be stopped AT the auth gate, `+
				"not by a deeper layer (which would mean the Lava-Auth gate was bypassed).",
				w.Body.String())
		}
	})

	// (C) Discrimination guard: prove (B)'s 401 came from the Lava-Auth gate
	// being REAL (rejecting the missing key), not from the route 401-ing
	// unconditionally. With a VALID active-client Lava-Auth header, the SAME
	// request MUST PASS the Lava-Auth gate — it then reaches the real
	// per-provider handler, which itself 401s with a DIFFERENT, bare {} body
	// because no rutracker session credential (Auth-Token cookie) was supplied
	// and the upstream rutracker.example.invalid is never dialled.
	//
	// The assertion is therefore: the response MUST NOT be the Lava-Auth gate's
	// {"error":"unauthorized"} rejection. A {} 401 (the per-provider credential
	// check) PROVES the valid key crossed the gate. Equality with the gate body
	// would mean the gate rejected a valid key — making (B)'s 401 vacuous.
	t.Run("C_valid_key_crosses_lava_auth_gate", func(t *testing.T) {
		req := httptest.NewRequest(http.MethodGet, "/v1/rutracker/search?query=x", nil)
		req.Header.Set(fx.validHeaderName, fx.validHeaderValue)
		w := httptest.NewRecorder()
		fx.engine.ServeHTTP(w, req)

		var gateBody struct {
			Error string `json:"error"`
		}
		_ = json.Unmarshal(w.Body.Bytes(), &gateBody)
		if w.Code == http.StatusUnauthorized && gateBody.Error == "unauthorized" {
			t.Fatalf("AUTH'd GET /v1/rutracker/search (valid Lava-Auth header) was rejected by "+
				`the Lava-Auth gate (401 {"error":"unauthorized"}) — the active-client key was `+
				"NOT accepted, so sub-test B's 401 is NOT evidence of a working gate (it would "+
				"401 regardless of the key). The Lava-Auth gate must accept a valid key. body=%q",
				w.Body.String())
		}
	})
}
