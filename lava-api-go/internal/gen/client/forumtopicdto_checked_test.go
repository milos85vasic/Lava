package client

import (
	"testing"
)

// These tests cover the discriminator-honoring accessors added in
// forumtopicdto_checked.go. The generated As* accessors (in the DO-NOT-EDIT
// api.gen.go) do a blind json.Unmarshal of the union bytes and ignore the
// "type" discriminator, so a Topic/CommentsPage variant whose shape overlaps a
// Torrent silently maps onto ForumTopicDtoTorrent with no error (type
// confusion). The *Checked accessors consult Discriminator() first and refuse
// to map a variant whose discriminator does not match.

// TestAsForumTopicDtoTorrentChecked_RejectsTopic is the FIX test. A DTO built
// from a Topic variant MUST NOT be returned as a Torrent: the checked accessor
// returns ErrDiscriminatorMismatch and a zero value. Primary assertion is on
// the observable (err + returned struct), per §6.J.
func TestAsForumTopicDtoTorrentChecked_RejectsTopic(t *testing.T) {
	var dto ForumTopicDto
	if err := dto.FromForumTopicDtoTopic(ForumTopicDtoTopic{Id: "c-topic", Title: "Not Torrent"}); err != nil {
		t.Fatalf("FromForumTopicDtoTopic: %v", err)
	}

	got, err := dto.AsForumTopicDtoTorrentChecked()
	if err == nil {
		t.Fatalf("AsForumTopicDtoTorrentChecked returned nil error for a Topic union; "+
			"the discriminator says %q but the accessor mapped it as a Torrent (type confusion). got=%+v",
			"Topic", got)
	}
	if got.Id != "" || got.Title != "" || string(got.Type) != "" {
		t.Errorf("on mismatch the returned struct must be the zero value, got Id=%q Title=%q Type=%q",
			got.Id, got.Title, got.Type)
	}
}

// TestAsForumTopicDtoTorrentChecked_AllowsTorrent is the happy path: a
// Torrent-built DTO is returned with fields preserved and no error.
func TestAsForumTopicDtoTorrentChecked_AllowsTorrent(t *testing.T) {
	seeds := int32(11)
	in := ForumTopicDtoTorrent{Id: "c-1", Title: "Client Torrent", Seeds: &seeds}
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

// TestAsForumTopicDtoTopicChecked_RejectsTorrent proves the guard is symmetric:
// a Torrent-built DTO must not be returned as a Topic.
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

// TestAsForumTopicDtoTorrentChecked_ErrorsOnGarbageUnion bounds the fix: a
// structurally invalid union (JSON array where an object is required) still
// surfaces an error rather than a silent zero value. The discriminator probe
// itself fails to decode the "type" field, which is reported as an error.
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
