package server

import (
	"testing"
)

// Server-side mirror of internal/gen/client/forumtopicdto_checked_test.go.
// The generated As* accessors ignore the "type" discriminator and silently
// type-confuse overlapping variants; the *Checked accessors in
// forumtopicdto_checked.go consult Discriminator() first.

func TestAsForumTopicDtoTorrentChecked_RejectsTopic(t *testing.T) {
	var dto ForumTopicDto
	if err := dto.FromForumTopicDtoTopic(ForumTopicDtoTopic{Id: "topic-id", Title: "Not A Torrent"}); err != nil {
		t.Fatalf("FromForumTopicDtoTopic: %v", err)
	}
	got, err := dto.AsForumTopicDtoTorrentChecked()
	if err == nil {
		t.Fatalf("AsForumTopicDtoTorrentChecked returned nil error for a Topic union (type confusion); got=%+v", got)
	}
	if got.Id != "" || got.Title != "" || string(got.Type) != "" {
		t.Errorf("on mismatch the returned struct must be zero, got Id=%q Title=%q Type=%q",
			got.Id, got.Title, got.Type)
	}
}

func TestAsForumTopicDtoTorrentChecked_AllowsTorrent(t *testing.T) {
	seeds := int32(42)
	in := ForumTopicDtoTorrent{Id: "topic-9001", Title: "Real Torrent Title", Seeds: &seeds}
	var dto ForumTopicDto
	if err := dto.FromForumTopicDtoTorrent(in); err != nil {
		t.Fatalf("FromForumTopicDtoTorrent: %v", err)
	}
	out, err := dto.AsForumTopicDtoTorrentChecked()
	if err != nil {
		t.Fatalf("AsForumTopicDtoTorrentChecked on a real Torrent errored: %v", err)
	}
	if out.Id != in.Id || out.Title != in.Title {
		t.Errorf("shared fields lost: Id=%q Title=%q", out.Id, out.Title)
	}
	if out.Seeds == nil || *out.Seeds != seeds {
		t.Errorf("Seeds = %v, want %d", out.Seeds, seeds)
	}
	if string(out.Type) != "Torrent" {
		t.Errorf("Type = %q, want %q", out.Type, "Torrent")
	}
}

func TestAsForumTopicDtoTopicChecked_RejectsTorrent(t *testing.T) {
	var dto ForumTopicDto
	if err := dto.FromForumTopicDtoTorrent(ForumTopicDtoTorrent{Id: "tor-id", Title: "A Torrent"}); err != nil {
		t.Fatalf("FromForumTopicDtoTorrent: %v", err)
	}
	got, err := dto.AsForumTopicDtoTopicChecked()
	if err == nil {
		t.Fatalf("AsForumTopicDtoTopicChecked returned nil error for a Torrent union; got=%+v", got)
	}
}

func TestAsForumTopicDtoTorrentChecked_ErrorsOnGarbageUnion(t *testing.T) {
	var dto ForumTopicDto
	if err := dto.UnmarshalJSON([]byte(`[1,2,3]`)); err != nil {
		t.Fatalf("UnmarshalJSON(array): %v", err)
	}
	if _, err := dto.AsForumTopicDtoTorrentChecked(); err == nil {
		t.Error("AsForumTopicDtoTorrentChecked on a JSON-array union returned nil error; " +
			"structural decode failures MUST surface")
	}
}
