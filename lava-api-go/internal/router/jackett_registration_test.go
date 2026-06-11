// jackett_registration_test.go is the deferred startup-registration gate for
// dynamic Jackett-indexer discovery (2026-06-11 spec §4.1 "each indexer = a
// provider", §5 native-wins collision guard).
//
// It exercises the REAL production registration loop inside router.Build: when
// Cfg.JackettEnabled is true and a Jackett base URL + api key are configured,
// Build constructs a real jackett.Client, calls jc.ListIndexers, and registers
// one jackettprovider.New per configured indexer — UNLESS the indexer id
// collides with an already-registered native provider, in which case the native
// provider wins and the Jackett indexer is skipped.
//
// No production refactor is needed: the registration loop is reachable end to
// end by pointing Cfg.JackettBaseURL at an httptest server that serves the real
// Jackett /api/v2.0/indexers JSON shape. The seam being tested is the ACTUAL
// router.Build code path, not an extracted helper — so a regression in the real
// loop (e.g. dropping the native-wins guard) fails this test.
//
// Anti-bluff posture (§6.J): the primary assertions are on the REGISTRY STATE a
// real /providers request would surface — provider id present, Kind()=="jackett"
// for the new indexer, and the native rutracker provider STILL the rutracker id
// after registration (not overwritten). "ListIndexers was called" is never the
// assertion.
//
// Falsifiability (Sixth Law clause 2): see the Bluff-Audit block on
// TestBuild_RegistersJackettIndexers_NativeWinsCollision.
package router

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"

	"digital.vasic.lava.apigo/internal/config"
	"digital.vasic.lava.apigo/internal/provider"
)

// nativeRutrackerStub is a minimal real provider standing in for the native
// rutracker provider. It carries the "rutracker" id and Kind()=="native"
// (from BaseProvider) so the collision-guard assertion can prove the native
// provider was NOT overwritten by a Jackett indexer of the same id.
type nativeRutrackerStub struct{ provider.BaseProvider }

func (nativeRutrackerStub) ID() string          { return "rutracker" }
func (nativeRutrackerStub) DisplayName() string { return "RuTracker (native)" }
func (nativeRutrackerStub) Capabilities() []provider.ProviderCapability {
	return []provider.ProviderCapability{provider.CapSearch}
}
func (nativeRutrackerStub) AuthType() provider.AuthType { return provider.AuthFormLogin }
func (nativeRutrackerStub) Encoding() string           { return "windows-1251" }
func (nativeRutrackerStub) Search(context.Context, provider.SearchOpts, provider.Credentials) (*provider.SearchResult, error) {
	return &provider.SearchResult{Provider: "rutracker"}, nil
}
func (nativeRutrackerStub) Browse(context.Context, string, int, provider.Credentials) (*provider.BrowseResult, error) {
	return nil, provider.ErrUnsupported
}
func (nativeRutrackerStub) GetForumTree(context.Context, provider.Credentials) (*provider.ForumTree, error) {
	return nil, provider.ErrUnsupported
}
func (nativeRutrackerStub) GetTopic(context.Context, string, int, provider.Credentials) (*provider.TopicResult, error) {
	return nil, provider.ErrUnsupported
}
func (nativeRutrackerStub) GetTorrent(context.Context, string, provider.Credentials) (*provider.TorrentResult, error) {
	return nil, provider.ErrUnsupported
}
func (nativeRutrackerStub) DownloadFile(context.Context, string, provider.Credentials) (*provider.FileDownload, error) {
	return nil, provider.ErrUnsupported
}
func (nativeRutrackerStub) GetComments(context.Context, string, int, provider.Credentials) (*provider.CommentsResult, error) {
	return nil, provider.ErrUnsupported
}
func (nativeRutrackerStub) AddComment(context.Context, string, string, provider.Credentials) (bool, error) {
	return false, provider.ErrUnsupported
}
func (nativeRutrackerStub) GetFavorites(context.Context, provider.Credentials) (*provider.FavoritesResult, error) {
	return nil, provider.ErrUnsupported
}
func (nativeRutrackerStub) AddFavorite(context.Context, string, provider.Credentials) (bool, error) {
	return false, provider.ErrUnsupported
}
func (nativeRutrackerStub) RemoveFavorite(context.Context, string, provider.Credentials) (bool, error) {
	return false, provider.ErrUnsupported
}
func (nativeRutrackerStub) CheckAuth(context.Context, provider.Credentials) (bool, error) {
	return false, nil
}
func (nativeRutrackerStub) Login(context.Context, provider.LoginOpts) (*provider.LoginResult, error) {
	return nil, provider.ErrUnsupported
}
func (nativeRutrackerStub) FetchCaptcha(context.Context, string) (*provider.CaptchaImage, error) {
	return nil, provider.ErrUnsupported
}
func (nativeRutrackerStub) HealthCheck(context.Context) (*provider.HealthStatus, error) {
	return &provider.HealthStatus{Healthy: true}, nil
}

// fakeJackettIndexers starts an httptest server serving the real Jackett
// configured-indexers JSON shape (the same shape internal/jackett/testdata/
// indexers_configured.json carries) for the given indexer ids. Returns the
// server and a cleanup. Each id is given a distinct human Name so the
// registered provider's DisplayName can be asserted.
func fakeJackettIndexers(t *testing.T, ids ...string) *httptest.Server {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Faithful to ListIndexers' request shape: it GETs /api/v2.0/indexers
		// with ?configured=true&apikey=... — serve only that path.
		if r.URL.Path != "/api/v2.0/indexers" {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(jackettIndexersJSON(ids...)))
	}))
	t.Cleanup(srv.Close)
	return srv
}

// jackettIndexersJSON renders the configured-indexers JSON for the given ids.
// Name is the title-cased id so DisplayName is distinguishable from the id.
func jackettIndexersJSON(ids ...string) string {
	out := "["
	for i, id := range ids {
		if i > 0 {
			out += ","
		}
		out += `{"id":"` + id + `","name":"` + titleName(id) + `","caps":[]}`
	}
	return out + "]"
}

func titleName(id string) string {
	switch id {
	case "1337x":
		return "1337x"
	case "rutracker":
		return "RuTracker (jackett)"
	default:
		return id
	}
}

// buildWithJackett builds the production engine with Jackett enabled, pointed at
// the fake indexers upstream, and the given pre-seeded registry. It returns the
// same *gin.Engine production uses; the side effect under test is the registry
// mutation Build performs during construction.
func buildWithJackett(reg *provider.ProviderRegistry, jackettBaseURL string) {
	Build(Deps{
		Cache:    stubCache{},
		Scraper:  stubScraper{},
		Registry: reg,
		Cfg: &config.Config{
			JackettEnabled:        true,
			JackettBaseURL:        jackettBaseURL,
			JackettAPIKey:         "test-apikey-not-a-secret-in-tracked-source",
			JackettDefaultIndexer: "all",
		},
	})
}

// TestBuild_RegistersJackettIndexers_NewIndexerBecomesProvider proves the
// happy path: an indexer with an id that does NOT collide with any native
// provider is registered as a first-class jackett-kind provider.
func TestBuild_RegistersJackettIndexers_NewIndexerBecomesProvider(t *testing.T) {
	srv := fakeJackettIndexers(t, "1337x")

	reg := provider.NewRegistry()
	reg.Register(nativeRutrackerStub{}) // a native provider already present

	buildWithJackett(reg, srv.URL)

	// USER-VISIBLE STATE: the catalogue (registry.All / Get) now contains 1337x.
	got, err := reg.Get("1337x")
	if err != nil || got == nil {
		t.Fatalf("after Build with Jackett enabled, registry.Get(\"1337x\") = (%v, %v); "+
			"want the indexer registered as a provider (registration loop did not run)", got, err)
	}
	if got.Kind() != "jackett" {
		t.Errorf("provider \"1337x\" Kind()=%q want \"jackett\" — registered as the wrong kind", got.Kind())
	}
	if got.DisplayName() != "1337x" {
		t.Errorf("provider \"1337x\" DisplayName()=%q want \"1337x\"", got.DisplayName())
	}
	if !got.SupportsAnonymous() {
		t.Errorf("jackett provider \"1337x\" SupportsAnonymous()=false want true (no per-indexer device auth)")
	}
	// It MUST declare SEARCH so /v1/1337x/search resolves (the e2e suite drives
	// that route; here we assert the capability is present in the catalogue).
	if !declares(got, provider.CapSearch) {
		t.Errorf("jackett provider \"1337x\" does not declare SEARCH: %v", got.Capabilities())
	}
}

// TestBuild_RegistersJackettIndexers_NativeWinsCollision is the load-bearing
// §5 native-wins assertion: when a Jackett indexer's id collides with an
// already-registered native provider, the native provider is NOT overwritten.
//
// Bluff-Audit:
//
//	Test:     TestBuild_RegistersJackettIndexers_NativeWinsCollision
//	Mutation: in internal/router/router.go, delete the collision guard inside
//	          the registration loop, i.e. the block:
//	              if existing, _ := deps.Registry.Get(idx.ID); existing != nil {
//	                  ... RecordWarning ...; continue
//	              }
//	          so every indexer (including the colliding "rutracker") falls
//	          through to deps.Registry.Register(jackettprovider.New(...)).
//	Observed: the test fails — registry.Register panics on the duplicate id
//	          ("provider \"rutracker\" already registered") which gin.Recovery
//	          does NOT cover at Build time, surfacing as:
//	              panic: provider "rutracker" already registered
//	          recovered by the test's deferred recover() into:
//	              "router.Build panicked registering a colliding indexer: provider
//	               \"rutracker\" already registered — native-wins guard removed"
//	          (and, were Register made last-wins instead of panic, the
//	          Kind()=="native" assertion below fires: "rutracker overwritten by
//	          a jackett provider").
//	Reverted: yes (see the verbatim run in the task report).
func TestBuild_RegistersJackettIndexers_NativeWinsCollision(t *testing.T) {
	// The fake upstream returns BOTH a fresh indexer (1337x) AND one that
	// collides with the native rutracker id.
	srv := fakeJackettIndexers(t, "1337x", "rutracker")

	reg := provider.NewRegistry()
	native := nativeRutrackerStub{}
	reg.Register(native)

	// Build performs the registration loop; the collision guard must keep it
	// from panicking on the duplicate rutracker id.
	func() {
		defer func() {
			if r := recover(); r != nil {
				t.Fatalf("router.Build panicked registering a colliding indexer: %v — "+
					"native-wins guard removed (the loop tried to Register a 2nd \"rutracker\")", r)
			}
		}()
		buildWithJackett(reg, srv.URL)
	}()

	// 1337x (no collision) MUST be registered as a jackett provider.
	if got, err := reg.Get("1337x"); err != nil || got == nil || got.Kind() != "jackett" {
		t.Errorf("non-colliding indexer \"1337x\" not registered as jackett provider: got=%v err=%v", got, err)
	}

	// rutracker MUST still be the NATIVE provider — the Jackett indexer of the
	// same id was skipped, native wins (§5).
	got, err := reg.Get("rutracker")
	if err != nil || got == nil {
		t.Fatalf("registry.Get(\"rutracker\") = (%v, %v); the native provider vanished", got, err)
	}
	if got.Kind() != "native" {
		t.Errorf("rutracker Kind()=%q want \"native\" — the native provider was OVERWRITTEN by a jackett indexer "+
			"(native-wins collision guard broken)", got.Kind())
	}
	if got.DisplayName() != "RuTracker (native)" {
		t.Errorf("rutracker DisplayName()=%q want \"RuTracker (native)\" — native provider replaced", got.DisplayName())
	}
}

func declares(p provider.Provider, want provider.ProviderCapability) bool {
	for _, c := range p.Capabilities() {
		if c == want {
			return true
		}
	}
	return false
}
