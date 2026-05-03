package gutenberg

import (
	"context"
	"testing"

	"digital.vasic.lava.apigo/internal/provider"
)

func TestProviderAdapter_Metadata(t *testing.T) {
	a := NewProviderAdapter(NewClient(""))
	if a.ID() != "gutenberg" {
		t.Errorf("id=%q want gutenberg", a.ID())
	}
	if a.DisplayName() != "Project Gutenberg" {
		t.Errorf("displayName=%q want Project Gutenberg", a.DisplayName())
	}
	if a.AuthType() != provider.AuthNone {
		t.Errorf("authType=%q want NONE", a.AuthType())
	}
	if a.Encoding() != "UTF-8" {
		t.Errorf("encoding=%q want UTF-8", a.Encoding())
	}
	caps := a.Capabilities()
	if len(caps) != 4 {
		t.Fatalf("expected 4 capabilities, got %d", len(caps))
	}
}

func TestProviderAdapter_GetForumTree(t *testing.T) {
	a := NewProviderAdapter(NewClient(""))
	tree, err := a.GetForumTree(context.Background(), provider.Credentials{})
	if err != nil {
		t.Fatalf("GetForumTree error: %v", err)
	}
	if tree == nil {
		t.Fatal("tree is nil")
	}
	if len(tree.Categories) != 6 {
		t.Errorf("categories=%d want 6", len(tree.Categories))
	}
}

func TestProviderAdapter_UnsupportedMethods(t *testing.T) {
	a := NewProviderAdapter(NewClient(""))
	ctx := context.Background()
	cred := provider.Credentials{}

	if _, err := a.GetTorrent(ctx, "1", cred); err != provider.ErrUnsupported {
		t.Errorf("GetTorrent: expected ErrUnsupported, got %v", err)
	}
	if _, err := a.GetComments(ctx, "1", 0, cred); err != provider.ErrUnsupported {
		t.Errorf("GetComments: expected ErrUnsupported, got %v", err)
	}
	if _, err := a.AddComment(ctx, "1", "hi", cred); err != provider.ErrUnsupported {
		t.Errorf("AddComment: expected ErrUnsupported, got %v", err)
	}
	if _, err := a.GetFavorites(ctx, cred); err != provider.ErrUnsupported {
		t.Errorf("GetFavorites: expected ErrUnsupported, got %v", err)
	}
	if _, err := a.AddFavorite(ctx, "1", cred); err != provider.ErrUnsupported {
		t.Errorf("AddFavorite: expected ErrUnsupported, got %v", err)
	}
	if _, err := a.RemoveFavorite(ctx, "1", cred); err != provider.ErrUnsupported {
		t.Errorf("RemoveFavorite: expected ErrUnsupported, got %v", err)
	}
	if _, err := a.Login(ctx, provider.LoginOpts{}); err != provider.ErrUnsupported {
		t.Errorf("Login: expected ErrUnsupported, got %v", err)
	}
	if _, err := a.FetchCaptcha(ctx, ""); err != provider.ErrUnsupported {
		t.Errorf("FetchCaptcha: expected ErrUnsupported, got %v", err)
	}
}

func TestProviderAdapter_CheckAuth(t *testing.T) {
	a := NewProviderAdapter(NewClient(""))
	ok, err := a.CheckAuth(context.Background(), provider.Credentials{})
	if err != nil {
		t.Fatalf("CheckAuth error: %v", err)
	}
	if !ok {
		t.Error("CheckAuth should return true for no-auth provider")
	}
}
