package v1

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"

	"digital.vasic.lava.apigo/internal/provider"
)

// streamProvider is a real provider.Provider whose Search returns a
// configurable result or error, used to drive the SSE MultiSearchHandler
// against the real registry + real Gin engine.
type streamProvider struct {
	provider.BaseProvider
	id     string
	name   string
	result *provider.SearchResult
	err    error
}

func (p *streamProvider) ID() string { return p.id }
func (p *streamProvider) DisplayName() string {
	if p.name != "" {
		return p.name
	}
	return p.id
}
func (p *streamProvider) Capabilities() []provider.ProviderCapability {
	return []provider.ProviderCapability{provider.CapSearch}
}
func (p *streamProvider) AuthType() provider.AuthType { return provider.AuthNone }
func (p *streamProvider) Encoding() string            { return "UTF-8" }
func (p *streamProvider) Search(ctx context.Context, opts provider.SearchOpts, cred provider.Credentials) (*provider.SearchResult, error) {
	if p.err != nil {
		return nil, p.err
	}
	return p.result, nil
}
func (p *streamProvider) Browse(ctx context.Context, categoryID string, page int, cred provider.Credentials) (*provider.BrowseResult, error) {
	return nil, provider.ErrUnsupported
}
func (p *streamProvider) GetForumTree(ctx context.Context, cred provider.Credentials) (*provider.ForumTree, error) {
	return nil, provider.ErrUnsupported
}
func (p *streamProvider) GetTopic(ctx context.Context, id string, page int, cred provider.Credentials) (*provider.TopicResult, error) {
	return nil, provider.ErrUnsupported
}
func (p *streamProvider) GetTorrent(ctx context.Context, id string, cred provider.Credentials) (*provider.TorrentResult, error) {
	return nil, provider.ErrUnsupported
}
func (p *streamProvider) DownloadFile(ctx context.Context, id string, cred provider.Credentials) (*provider.FileDownload, error) {
	return nil, provider.ErrUnsupported
}
func (p *streamProvider) GetComments(ctx context.Context, id string, page int, cred provider.Credentials) (*provider.CommentsResult, error) {
	return nil, provider.ErrUnsupported
}
func (p *streamProvider) AddComment(ctx context.Context, id, message string, cred provider.Credentials) (bool, error) {
	return false, provider.ErrUnsupported
}
func (p *streamProvider) GetFavorites(ctx context.Context, cred provider.Credentials) (*provider.FavoritesResult, error) {
	return nil, provider.ErrUnsupported
}
func (p *streamProvider) AddFavorite(ctx context.Context, id string, cred provider.Credentials) (bool, error) {
	return false, provider.ErrUnsupported
}
func (p *streamProvider) RemoveFavorite(ctx context.Context, id string, cred provider.Credentials) (bool, error) {
	return false, provider.ErrUnsupported
}
func (p *streamProvider) CheckAuth(ctx context.Context, cred provider.Credentials) (bool, error) {
	return false, nil
}
func (p *streamProvider) Login(ctx context.Context, opts provider.LoginOpts) (*provider.LoginResult, error) {
	return nil, provider.ErrUnsupported
}
func (p *streamProvider) FetchCaptcha(ctx context.Context, path string) (*provider.CaptchaImage, error) {
	return nil, provider.ErrUnsupported
}
func (p *streamProvider) HealthCheck(ctx context.Context) (*provider.HealthStatus, error) {
	return &provider.HealthStatus{Healthy: true}, nil
}

func multiSearchRouter(reg *provider.ProviderRegistry) *gin.Engine {
	gin.SetMode(gin.TestMode)
	r := gin.New()
	h := NewMultiSearchHandler(reg, 0)
	r.GET("/v1/search", h.GetMultiSearch)
	return r
}

// getSSE drives a request through a REAL httptest.Server (required because
// Gin's c.Stream depends on http.CloseNotifier / Flusher, which the bare
// httptest.ResponseRecorder does not provide) and returns the full body.
func getSSE(t *testing.T, reg *provider.ProviderRegistry, query string) (int, string, http.Header) {
	t.Helper()
	srv := httptest.NewServer(multiSearchRouter(reg))
	defer srv.Close()

	resp, err := http.Get(srv.URL + query)
	if err != nil {
		t.Fatalf("GET: %v", err)
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatalf("read body: %v", err)
	}
	return resp.StatusCode, string(body), resp.Header
}

// TestMultiSearch_StreamsResultsAndEnd verifies the SSE stream the Android
// client consumes: a real query against a registered provider must emit
// provider_start, results (carrying the actual items), provider_done, and a
// stream_end carrying the aggregate counts. Assertions are on the SSE wire
// body — exactly what the client parses.
func TestMultiSearch_StreamsResultsAndEnd(t *testing.T) {
	reg := provider.NewRegistry()
	reg.Register(&streamProvider{
		id:   "kinozal",
		name: "Kinozal.tv",
		result: &provider.SearchResult{
			Provider:   "kinozal",
			Page:       1,
			TotalPages: 2,
			Results: []provider.SearchItem{
				{ID: "1", Title: "Result Alpha"},
				{ID: "2", Title: "Result Beta"},
			},
		},
	})
	code, body, hdr := getSSE(t, reg, "/v1/search?q=movie&providers=kinozal")

	if code != http.StatusOK {
		t.Fatalf("status = %d, want 200", code)
	}
	if ct := hdr.Get("Content-Type"); !strings.HasPrefix(ct, "text/event-stream") {
		t.Errorf("Content-Type = %q, want text/event-stream", ct)
	}

	for _, want := range []string{
		"event: provider_start",
		"event: results",
		"event: provider_done",
		"event: stream_end",
	} {
		if !strings.Contains(body, want) {
			t.Errorf("SSE body missing %q\n--- body ---\n%s", want, body)
		}
	}
	// The actual search items must be on the wire (user-visible payload).
	if !strings.Contains(body, "Result Alpha") || !strings.Contains(body, "Result Beta") {
		t.Errorf("SSE body missing search item titles\n--- body ---\n%s", body)
	}
	// The aggregate must report 1 provider searched, 0 failed, 2 results.
	if !strings.Contains(body, `"providers_searched":1`) {
		t.Errorf("stream_end missing providers_searched:1\n--- body ---\n%s", body)
	}
	if !strings.Contains(body, `"total_results":2`) {
		t.Errorf("stream_end missing total_results:2\n--- body ---\n%s", body)
	}
}

// TestMultiSearch_ProviderErrorEmitsErrorEvent verifies that a provider
// whose Search fails surfaces as a provider_error SSE event AND is counted
// as failed in stream_end — the user sees the per-provider failure inline,
// not a silently dropped provider.
func TestMultiSearch_ProviderErrorEmitsErrorEvent(t *testing.T) {
	reg := provider.NewRegistry()
	reg.Register(&streamProvider{id: "broken", name: "Broken", err: provider.ErrCircuitOpen})

	_, body, _ := getSSE(t, reg, "/v1/search?q=x&providers=broken")
	if !strings.Contains(body, "event: provider_error") {
		t.Errorf("SSE body missing provider_error event\n--- body ---\n%s", body)
	}
	if !strings.Contains(body, provider.ErrCircuitOpen.Error()) {
		t.Errorf("provider_error event missing the error text\n--- body ---\n%s", body)
	}
	if !strings.Contains(body, `"providers_failed":1`) {
		t.Errorf("stream_end missing providers_failed:1\n--- body ---\n%s", body)
	}
}

// orderOfProviderStartEvents parses an SSE body and returns the provider_id
// values of every provider_start event in the order they appear on the wire —
// i.e. the order the user/client observes providers being searched.
func orderOfProviderStartEvents(t *testing.T, body string) []string {
	t.Helper()
	var order []string
	lines := strings.Split(body, "\n")
	for i := 0; i < len(lines); i++ {
		if strings.TrimSpace(lines[i]) != "event: provider_start" {
			continue
		}
		// The data line follows the event line.
		for j := i + 1; j < len(lines); j++ {
			l := lines[j]
			if !strings.HasPrefix(l, "data: ") {
				continue
			}
			var status providerStreamStatus
			if err := json.Unmarshal([]byte(strings.TrimPrefix(l, "data: ")), &status); err != nil {
				t.Fatalf("unmarshal provider_start data %q: %v", l, err)
			}
			order = append(order, status.ProviderID)
			break
		}
	}
	return order
}

// TestMultiSearch_AutoDiscoveryStreamsProvidersInDeterministicOrder is the
// LVA-059 regression guard. When the client omits ?providers= the handler
// auto-discovers search-capable providers from the registry. Before the fix it
// iterated registry.IDs() in Go's randomized map-iteration order, so the SSE
// provider_start / results / provider_done sequence the Android client renders
// arrived in a different order on (almost) every request — un-testable and a
// jarring UX (provider list reshuffles each search). After the fix the order is
// the registry's lexicographic order, stable across repeated calls.
//
// The assertion is on the SSE wire body the client parses (the user-visible
// surface): the provider_start events MUST appear sorted by provider id, and
// MUST be identical across repeated requests.
//
// FALSIFIABILITY: reverting IDs() to unsorted map iteration (or replacing the
// handler's discovered-id slice with a shuffled copy) makes this test FAIL —
// the observed order stops matching the sorted expectation and stops being
// stable across calls.
func TestMultiSearch_AutoDiscoveryStreamsProvidersInDeterministicOrder(t *testing.T) {
	reg := provider.NewRegistry()
	// Register in deliberately NON-sorted insertion order so map order != sorted.
	for _, id := range []string{"rutor", "kinozal", "archiveorg", "rutracker", "nnmclub"} {
		reg.Register(&streamProvider{
			id: id,
			result: &provider.SearchResult{
				Provider: id, Page: 1, TotalPages: 1,
				Results: []provider.SearchItem{{ID: "1", Title: id + "-result"}},
			},
		})
	}
	want := []string{"archiveorg", "kinozal", "nnmclub", "rutor", "rutracker"}

	var first []string
	for call := 0; call < 8; call++ {
		// No ?providers= → auto-discovery path.
		code, body, _ := getSSE(t, reg, "/v1/search?q=movie")
		if code != http.StatusOK {
			t.Fatalf("call %d: status = %d, want 200\n%s", call, code, body)
		}
		got := orderOfProviderStartEvents(t, body)
		if len(got) != len(want) {
			t.Fatalf("call %d: got %d provider_start events, want %d (order=%v)", call, len(got), len(want), got)
		}
		for i := range want {
			if got[i] != want[i] {
				t.Fatalf("call %d: provider_start order[%d] = %q, want %q (full=%v) — auto-discovery is not deterministically sorted",
					call, i, got[i], want[i], got)
			}
		}
		if first == nil {
			first = got
		} else {
			for i := range first {
				if got[i] != first[i] {
					t.Fatalf("call %d order %v differs from first call order %v — non-deterministic", call, got, first)
				}
			}
		}
	}
}

// TestMultiSearch_MissingQuery verifies the 400 contract: no 'q' parameter
// is a client error and the user gets a clear message.
func TestMultiSearch_MissingQuery(t *testing.T) {
	code, body, _ := getSSE(t, provider.NewRegistry(), "/v1/search")

	if code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", code)
	}
	if !strings.Contains(body, "required") {
		t.Errorf("body = %q, want it to mention the query is required", body)
	}
}

// TestMultiSearch_NoCapableProviders verifies 400 when a query arrives but
// no search-capable provider is registered.
func TestMultiSearch_NoCapableProviders(t *testing.T) {
	code, body, _ := getSSE(t, provider.NewRegistry(), "/v1/search?q=movie")

	if code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", code)
	}
	if !strings.Contains(body, "no search-capable providers") {
		t.Errorf("body = %q, want no-capable-providers message", body)
	}
}

// TestParseProviderList verifies CSV parsing incl. whitespace trimming and
// empty-token elision — a malformed providers= param must not inject blank
// provider IDs that would 404 downstream.
func TestParseProviderList(t *testing.T) {
	tests := []struct {
		in   string
		want []string
	}{
		{"", nil},
		{"a,b,c", []string{"a", "b", "c"}},
		{" a , b ,c ", []string{"a", "b", "c"}},
		{"a,,b,", []string{"a", "b"}},
		{"   ", nil},
	}
	for _, tc := range tests {
		got := parseProviderList(tc.in)
		if len(got) != len(tc.want) {
			t.Errorf("parseProviderList(%q) = %v, want %v", tc.in, got, tc.want)
			continue
		}
		for i := range got {
			if got[i] != tc.want[i] {
				t.Errorf("parseProviderList(%q)[%d] = %q, want %q", tc.in, i, got[i], tc.want[i])
			}
		}
	}
}

// TestMultiSearch_UnknownProviderEmitsErrorEvent is the LVA-057 regression
// guard. When the user EXPLICITLY requests a provider id via ?providers= that
// is not registered, the handler MUST surface a provider_error SSE event for
// that id AND count it as failed in stream_end — exactly as a registered-but-
// failing provider does. Before the fix, registry.Get(pid) failed and the
// handler silently `continue`d: no provider_error, no failed-count increment,
// yet total_providers still counted the bogus id, so the client's requested
// provider vanished with zero signal (a §6.AB silent-omission bluff: the user
// asked for "ghost", got no error and no results).
//
// Assertions are on the SSE wire body the Android client parses — the
// user-visible surface.
func TestMultiSearch_UnknownProviderEmitsErrorEvent(t *testing.T) {
	reg := provider.NewRegistry()
	reg.Register(&streamProvider{
		id:   "kinozal",
		name: "Kinozal.tv",
		result: &provider.SearchResult{
			Provider:   "kinozal",
			Page:       1,
			TotalPages: 1,
			Results:    []provider.SearchItem{{ID: "1", Title: "Real Result"}},
		},
	})

	// User asks for one real provider and one that does not exist.
	_, body, _ := getSSE(t, reg, "/v1/search?q=x&providers=kinozal,ghost")

	// The real provider must still stream its result.
	if !strings.Contains(body, "Real Result") {
		t.Errorf("SSE body missing the real provider's result\n--- body ---\n%s", body)
	}
	// The unknown provider MUST surface as a provider_error naming its id —
	// not be silently dropped.
	if !strings.Contains(body, "event: provider_error") {
		t.Errorf("SSE body missing provider_error event for the unknown provider\n--- body ---\n%s", body)
	}
	if !strings.Contains(body, `"provider_id":"ghost"`) {
		t.Errorf("provider_error event must name the unknown provider id \"ghost\"\n--- body ---\n%s", body)
	}
	// And it MUST count as failed in the aggregate (1 searched ok, 1 failed).
	if !strings.Contains(body, `"providers_failed":1`) {
		t.Errorf("stream_end must count the unknown provider as failed\n--- body ---\n%s", body)
	}
	if !strings.Contains(body, `"providers_searched":2`) {
		t.Errorf("stream_end providers_searched must account for both requested ids\n--- body ---\n%s", body)
	}
}
