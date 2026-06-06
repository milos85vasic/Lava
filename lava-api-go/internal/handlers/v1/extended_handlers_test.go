package v1

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"

	"digital.vasic.lava.apigo/internal/provider"
)

// richProvider is a fully-configurable real provider.Provider used to drive
// the extended v1 handlers (comments, favorites, login, torrent, captcha)
// end-to-end through the real Gin engine + the production handler code.
type richProvider struct {
	comments  *provider.CommentsResult
	addOK     bool
	favorites *provider.FavoritesResult
	login     *provider.LoginResult
	torrent   *provider.TorrentResult
	download  *provider.FileDownload
	captcha   *provider.CaptchaImage
	err       error
}

func (p *richProvider) ID() string                                  { return "rich" }
func (p *richProvider) DisplayName() string                         { return "Rich" }
func (p *richProvider) Capabilities() []provider.ProviderCapability { return nil }
func (p *richProvider) AuthType() provider.AuthType                 { return provider.AuthFormLogin }
func (p *richProvider) Encoding() string                            { return "UTF-8" }
func (p *richProvider) Search(ctx context.Context, opts provider.SearchOpts, cred provider.Credentials) (*provider.SearchResult, error) {
	return &provider.SearchResult{}, nil
}
func (p *richProvider) Browse(ctx context.Context, categoryID string, page int, cred provider.Credentials) (*provider.BrowseResult, error) {
	return &provider.BrowseResult{}, nil
}
func (p *richProvider) GetForumTree(ctx context.Context, cred provider.Credentials) (*provider.ForumTree, error) {
	return &provider.ForumTree{}, nil
}
func (p *richProvider) GetTopic(ctx context.Context, id string, page int, cred provider.Credentials) (*provider.TopicResult, error) {
	return &provider.TopicResult{}, nil
}
func (p *richProvider) GetTorrent(ctx context.Context, id string, cred provider.Credentials) (*provider.TorrentResult, error) {
	if p.err != nil {
		return nil, p.err
	}
	return p.torrent, nil
}
func (p *richProvider) DownloadFile(ctx context.Context, id string, cred provider.Credentials) (*provider.FileDownload, error) {
	if p.err != nil {
		return nil, p.err
	}
	return p.download, nil
}
func (p *richProvider) GetComments(ctx context.Context, id string, page int, cred provider.Credentials) (*provider.CommentsResult, error) {
	if p.err != nil {
		return nil, p.err
	}
	return p.comments, nil
}
func (p *richProvider) AddComment(ctx context.Context, id, message string, cred provider.Credentials) (bool, error) {
	if p.err != nil {
		return false, p.err
	}
	return p.addOK, nil
}
func (p *richProvider) GetFavorites(ctx context.Context, cred provider.Credentials) (*provider.FavoritesResult, error) {
	if p.err != nil {
		return nil, p.err
	}
	return p.favorites, nil
}
func (p *richProvider) AddFavorite(ctx context.Context, id string, cred provider.Credentials) (bool, error) {
	if p.err != nil {
		return false, p.err
	}
	return p.addOK, nil
}
func (p *richProvider) RemoveFavorite(ctx context.Context, id string, cred provider.Credentials) (bool, error) {
	if p.err != nil {
		return false, p.err
	}
	return p.addOK, nil
}
func (p *richProvider) CheckAuth(ctx context.Context, cred provider.Credentials) (bool, error) {
	return true, nil
}
func (p *richProvider) Login(ctx context.Context, opts provider.LoginOpts) (*provider.LoginResult, error) {
	if p.err != nil {
		return nil, p.err
	}
	return p.login, nil
}
func (p *richProvider) FetchCaptcha(ctx context.Context, path string) (*provider.CaptchaImage, error) {
	if p.err != nil {
		return nil, p.err
	}
	return p.captcha, nil
}
func (p *richProvider) HealthCheck(ctx context.Context) (*provider.HealthStatus, error) {
	return &provider.HealthStatus{Healthy: true}, nil
}

// TestGetComments_Success verifies the comments handler returns the real
// comment items in the JSON body — the user's review feed.
func TestGetComments_Success(t *testing.T) {
	fp := &richProvider{comments: &provider.CommentsResult{
		Provider: "rich",
		Page:     1,
		Total:    3,
		Items:    []provider.Comment{{ID: "c1", Author: "alice", Body: "great"}},
	}}
	router := setupTestRouter(fp)

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/v1/rich/comments/99?page=1", nil)
	router.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body=%s", w.Code, w.Body.String())
	}
	var got provider.CommentsResult
	if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if got.Total != 3 || len(got.Items) != 1 || got.Items[0].Author != "alice" {
		t.Errorf("got %+v, want total=3 one item by alice", got)
	}
}

// TestAddComment_Success verifies POST add-comment returns success=true.
func TestAddComment_Success(t *testing.T) {
	fp := &richProvider{addOK: true}
	router := setupTestRouter(fp)

	body := bytes.NewBufferString(`{"message":"nice"}`)
	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodPost, "/v1/rich/comments/99/add", body)
	req.Header.Set("Content-Type", "application/json")
	router.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body=%s", w.Code, w.Body.String())
	}
	var got map[string]bool
	if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if !got["success"] {
		t.Errorf("success = %v, want true", got["success"])
	}
}

// TestAddComment_InvalidBody verifies a malformed JSON body yields 400.
func TestAddComment_InvalidBody(t *testing.T) {
	router := setupTestRouter(&richProvider{})

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodPost, "/v1/rich/comments/99/add", bytes.NewBufferString("not json"))
	req.Header.Set("Content-Type", "application/json")
	router.ServeHTTP(w, req)

	if w.Code != http.StatusBadRequest {
		t.Errorf("status = %d, want 400", w.Code)
	}
}

// TestGetFavorites_Success verifies the favorites handler returns the saved
// items in the body.
func TestGetFavorites_Success(t *testing.T) {
	fp := &richProvider{favorites: &provider.FavoritesResult{
		Provider: "rich",
		Items:    []provider.SearchItem{{ID: "f1", Title: "Saved One"}},
	}}
	router := setupTestRouter(fp)

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/v1/rich/favorites", nil)
	router.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body=%s", w.Code, w.Body.String())
	}
	var got provider.FavoritesResult
	if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if len(got.Items) != 1 || got.Items[0].Title != "Saved One" {
		t.Errorf("got %+v, want one item 'Saved One'", got)
	}
}

// TestAddFavorite_Success / TestRemoveFavorite_Success verify the POST
// add/remove favorite handlers report success.
func TestAddFavorite_Success(t *testing.T) {
	router := setupTestRouter(&richProvider{addOK: true})
	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodPost, "/v1/rich/favorites/add/55", nil)
	router.ServeHTTP(w, req)
	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body=%s", w.Code, w.Body.String())
	}
	if !strings.Contains(w.Body.String(), `"success":true`) {
		t.Errorf("body = %q, want success:true", w.Body.String())
	}
}

func TestRemoveFavorite_Success(t *testing.T) {
	router := setupTestRouter(&richProvider{addOK: true})
	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodPost, "/v1/rich/favorites/remove/55", nil)
	router.ServeHTTP(w, req)
	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body=%s", w.Code, w.Body.String())
	}
	if !strings.Contains(w.Body.String(), `"success":true`) {
		t.Errorf("body = %q, want success:true", w.Body.String())
	}
}

// TestLogin_Success verifies the login handler returns the LoginResult with
// the auth token in the body — the credential the client stores.
func TestLogin_Success(t *testing.T) {
	fp := &richProvider{login: &provider.LoginResult{Success: true, AuthToken: "user-77"}}
	router := setupTestRouter(fp)

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodPost, "/v1/rich/login", bytes.NewBufferString(`{"username":"u","password":"p"}`))
	req.Header.Set("Content-Type", "application/json")
	router.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body=%s", w.Code, w.Body.String())
	}
	var got provider.LoginResult
	if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if !got.Success || got.AuthToken != "user-77" {
		t.Errorf("got %+v, want success=true token=user-77", got)
	}
}

// TestLogin_Unauthorized verifies an ErrUnauthorized from the provider maps
// to HTTP 401 (the user sees "wrong credentials", not a 200).
func TestLogin_Unauthorized(t *testing.T) {
	router := setupTestRouter(&richProvider{err: provider.ErrUnauthorized})

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodPost, "/v1/rich/login", bytes.NewBufferString(`{"username":"u","password":"bad"}`))
	req.Header.Set("Content-Type", "application/json")
	router.ServeHTTP(w, req)

	if w.Code != http.StatusUnauthorized {
		t.Errorf("status = %d, want 401", w.Code)
	}
}

// TestLogin_InvalidBody verifies malformed JSON yields 400.
func TestLogin_InvalidBody(t *testing.T) {
	router := setupTestRouter(&richProvider{})
	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodPost, "/v1/rich/login", bytes.NewBufferString("{"))
	req.Header.Set("Content-Type", "application/json")
	router.ServeHTTP(w, req)
	if w.Code != http.StatusBadRequest {
		t.Errorf("status = %d, want 400", w.Code)
	}
}

// TestGetTorrent_Success verifies the .torrent metadata is returned as JSON.
func TestGetTorrent_Success(t *testing.T) {
	fp := &richProvider{torrent: &provider.TorrentResult{
		Provider: "rich", ID: "12", Filename: "movie.torrent", ContentType: "application/x-bittorrent",
	}}
	router := setupTestRouter(fp)

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/v1/rich/torrent/12", nil)
	router.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body=%s", w.Code, w.Body.String())
	}
	var got provider.TorrentResult
	if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if got.Filename != "movie.torrent" {
		t.Errorf("Filename = %q, want movie.torrent", got.Filename)
	}
}

// TestGetDownload_Success verifies the binary download handler streams the
// real bytes with the provider-declared content type — the actual .torrent
// file the user saves.
func TestGetDownload_Success(t *testing.T) {
	want := []byte("d8:announce...e")
	fp := &richProvider{download: &provider.FileDownload{
		Provider: "rich", ID: "12", ContentType: "application/x-bittorrent", Body: want,
	}}
	router := setupTestRouter(fp)

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/v1/rich/download/12", nil)
	router.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body=%s", w.Code, w.Body.String())
	}
	if ct := w.Header().Get("Content-Type"); ct != "application/x-bittorrent" {
		t.Errorf("Content-Type = %q, want application/x-bittorrent", ct)
	}
	if !bytes.Equal(w.Body.Bytes(), want) {
		t.Errorf("body bytes = %q, want %q", w.Body.Bytes(), want)
	}
}

// TestGetDownload_ProviderError verifies a download error maps to a non-200.
func TestGetDownload_ProviderError(t *testing.T) {
	router := setupTestRouter(&richProvider{err: provider.ErrForbidden})
	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/v1/rich/download/12", nil)
	router.ServeHTTP(w, req)
	if w.Code != http.StatusForbidden {
		t.Errorf("status = %d, want 403", w.Code)
	}
}

// TestGetCaptcha_Success verifies the captcha handler streams the PNG bytes.
func TestGetCaptcha_Success(t *testing.T) {
	pngBytes := []byte{0x89, 0x50, 0x4E, 0x47}
	fp := &richProvider{captcha: &provider.CaptchaImage{Path: "cap.png", Data: pngBytes}}
	router := setupTestRouter(fp)

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/v1/rich/captcha/cap.png", nil)
	router.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body=%s", w.Code, w.Body.String())
	}
	if ct := w.Header().Get("Content-Type"); ct != "image/png" {
		t.Errorf("Content-Type = %q, want image/png", ct)
	}
	if !bytes.Equal(w.Body.Bytes(), pngBytes) {
		t.Errorf("body = %v, want %v", w.Body.Bytes(), pngBytes)
	}
}

// ensure gin import is used even if helper refactors change call sites.
var _ = gin.Version
