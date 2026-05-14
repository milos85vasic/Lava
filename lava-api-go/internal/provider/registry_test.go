package provider

import (
	"context"
	"testing"
)

// fakeProvider is a minimal Provider implementation for testing.
type fakeProvider struct {
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

func TestRegistry_All(t *testing.T) {
	r := NewRegistry()
	r.Register(&fakeProvider{id: "x"})

	all := r.All()
	if len(all) != 1 {
		t.Fatalf("expected 1 provider, got %d", len(all))
	}
}
