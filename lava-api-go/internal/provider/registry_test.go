package provider

import (
	"context"
	"testing"
)

// fakeProvider is a minimal Provider implementation for testing.
type fakeProvider struct {
	BaseProvider // catalogue-metadata defaults for the test fake
	id           string
	caps         []ProviderCapability
	auth         AuthType
	searchCalled bool
}

func (f *fakeProvider) ID() string                         { return f.id }
func (f *fakeProvider) DisplayName() string                { return f.id }
func (f *fakeProvider) Capabilities() []ProviderCapability { return f.caps }
func (f *fakeProvider) AuthType() AuthType                 { return f.auth }
func (f *fakeProvider) Encoding() string                   { return "UTF-8" }
func (f *fakeProvider) Search(ctx context.Context, opts SearchOpts, cred Credentials) (*SearchResult, error) {
	f.searchCalled = true
	return &SearchResult{Provider: f.id}, nil
}
func (f *fakeProvider) Browse(ctx context.Context, categoryID string, page int, cred Credentials) (*BrowseResult, error) {
	return nil, ErrUnsupported
}
func (f *fakeProvider) GetForumTree(ctx context.Context, cred Credentials) (*ForumTree, error) {
	return nil, ErrUnsupported
}
func (f *fakeProvider) GetTopic(ctx context.Context, id string, page int, cred Credentials) (*TopicResult, error) {
	return nil, ErrUnsupported
}
func (f *fakeProvider) GetTorrent(ctx context.Context, id string, cred Credentials) (*TorrentResult, error) {
	return nil, ErrUnsupported
}
func (f *fakeProvider) DownloadFile(ctx context.Context, id string, cred Credentials) (*FileDownload, error) {
	return nil, ErrUnsupported
}
func (f *fakeProvider) GetComments(ctx context.Context, id string, page int, cred Credentials) (*CommentsResult, error) {
	return nil, ErrUnsupported
}
func (f *fakeProvider) AddComment(ctx context.Context, id, message string, cred Credentials) (bool, error) {
	return false, ErrUnsupported
}
func (f *fakeProvider) GetFavorites(ctx context.Context, cred Credentials) (*FavoritesResult, error) {
	return nil, ErrUnsupported
}
func (f *fakeProvider) AddFavorite(ctx context.Context, id string, cred Credentials) (bool, error) {
	return false, ErrUnsupported
}
func (f *fakeProvider) RemoveFavorite(ctx context.Context, id string, cred Credentials) (bool, error) {
	return false, ErrUnsupported
}
func (f *fakeProvider) CheckAuth(ctx context.Context, cred Credentials) (bool, error) {
	return false, nil
}
func (f *fakeProvider) Login(ctx context.Context, opts LoginOpts) (*LoginResult, error) {
	return nil, ErrUnsupported
}
func (f *fakeProvider) FetchCaptcha(ctx context.Context, path string) (*CaptchaImage, error) {
	return nil, ErrUnsupported
}
func (f *fakeProvider) HealthCheck(ctx context.Context) (*HealthStatus, error) {
	return &HealthStatus{Healthy: true}, nil
}

func TestRegistry_RegisterAndGet(t *testing.T) {
	r := NewRegistry()
	p := &fakeProvider{id: "test", caps: []ProviderCapability{CapSearch}}

	r.Register(p)

	got, err := r.Get("test")
	if err != nil {
		t.Fatalf("Get failed: %v", err)
	}
	if got.ID() != "test" {
		t.Errorf("ID: got %q, want test", got.ID())
	}
}

func TestRegistry_GetUnknown(t *testing.T) {
	r := NewRegistry()
	_, err := r.Get("unknown")
	if err == nil {
		t.Fatal("expected error for unknown provider")
	}
}

func TestRegistry_DuplicatePanics(t *testing.T) {
	r := NewRegistry()
	r.Register(&fakeProvider{id: "dup"})
	defer func() {
		if recover() == nil {
			t.Fatal("expected panic for duplicate registration")
		}
	}()
	r.Register(&fakeProvider{id: "dup"})
}

func TestRegistry_Supports(t *testing.T) {
	r := NewRegistry()
	r.Register(&fakeProvider{id: "a", caps: []ProviderCapability{CapSearch}})
	r.Register(&fakeProvider{id: "b", caps: []ProviderCapability{CapBrowse}})

	if !r.Supports("a", CapSearch) {
		t.Error("expected a to support SEARCH")
	}
	if r.Supports("a", CapBrowse) {
		t.Error("expected a to NOT support BROWSE")
	}
	if r.Supports("unknown", CapSearch) {
		t.Error("expected unknown provider to NOT support anything")
	}
}

func TestRegistry_IDs(t *testing.T) {
	r := NewRegistry()
	r.Register(&fakeProvider{id: "z"})
	r.Register(&fakeProvider{id: "a"})

	ids := r.IDs()
	if len(ids) != 2 {
		t.Fatalf("expected 2 ids, got %d", len(ids))
	}
}

// TestRegistry_IDs_LexicographicAndDeterministic is the LVA-059 regression
// guard. IDs() documents "in lexicographic order", and the multi-search SSE
// auto-discovery path (handlers/v1 GetMultiSearch) relies on that order for a
// deterministic, testable SSE event sequence the Android client consumes.
// Before the fix, IDs() iterated the backing map, so the order was Go's
// randomized map-iteration order — non-deterministic across calls AND across
// process restarts. This test registers ids in NON-sorted insertion order and
// asserts the returned slice is sorted lexicographically AND identical across
// repeated calls on the same registry.
//
// FALSIFIABILITY: removing the sort.Strings(out) call in IDs() makes this test
// FAIL — the returned slice falls back to map order, which is not guaranteed
// sorted (and on most runs is not).
func TestRegistry_IDs_LexicographicAndDeterministic(t *testing.T) {
	r := NewRegistry()
	// Insert in deliberately non-sorted order.
	for _, id := range []string{"rutor", "kinozal", "archiveorg", "rutracker", "nnmclub"} {
		r.Register(&fakeProvider{id: id})
	}

	want := []string{"archiveorg", "kinozal", "nnmclub", "rutor", "rutracker"}

	// Call several times; every call MUST yield the same sorted order.
	for call := 0; call < 20; call++ {
		got := r.IDs()
		if len(got) != len(want) {
			t.Fatalf("call %d: len = %d, want %d", call, len(got), len(want))
		}
		for i := range want {
			if got[i] != want[i] {
				t.Fatalf("call %d: IDs()[%d] = %q, want %q (full=%v, want lexicographic+deterministic)",
					call, i, got[i], want[i], got)
			}
		}
	}
}

func TestRegistry_All(t *testing.T) {
	r := NewRegistry()
	r.Register(&fakeProvider{id: "x"})

	all := r.All()
	if len(all) != 1 {
		t.Fatalf("expected 1 provider, got %d", len(all))
	}
}
