package client

import (
	"testing"
)

// Mirror of internal/gen/server/forumtopicdto_union_test.go for the client-side
// generated types (the client DTO is what the parity/cross-backend client path
// decodes). Same FINDING applies: the As* accessors ignore the "type"
// discriminator. See the server-side test for the full FINDING narrative.

// TestClientForumTopicDtoTorrentRoundTrip — happy path: a Torrent-built DTO
// reads back as a Torrent with fields preserved. Assertion on observed values.
func TestClientForumTopicDtoTorrentRoundTrip(t *testing.T) {
	seeds := int32(11)
	in := ForumTopicDtoTorrent{Id: "c-1", Title: "Client Torrent", Seeds: &seeds}
	var dto ForumTopicDto
	if err := dto.FromForumTopicDtoTorrent(in); err != nil {
		t.Fatalf("FromForumTopicDtoTorrent: %v", err)
	}
	out, err := dto.AsForumTopicDtoTorrent()
	if err != nil {
		t.Fatalf("AsForumTopicDtoTorrent: %v", err)
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

// TestClientAsForumTopicDtoTorrent_IgnoresDiscriminator — the FINDING, client
// side. A Topic-built DTO reads back as a Torrent with NO error, leaking the
// "Topic" discriminator. PINS the latent behavior; falsifiable by any future
// discriminator-validation change.
func TestClientAsForumTopicDtoTorrent_IgnoresDiscriminator(t *testing.T) {
	var dto ForumTopicDto
	if err := dto.FromForumTopicDtoTopic(ForumTopicDtoTopic{Id: "c-topic", Title: "Not Torrent"}); err != nil {
		t.Fatalf("FromForumTopicDtoTopic: %v", err)
	}
	torrent, err := dto.AsForumTopicDtoTorrent()
	if err != nil {
		t.Fatalf("FINDING regression: AsForumTopicDtoTorrent now errors on a Topic union (%v)", err)
	}
	if torrent.Id != "c-topic" || torrent.Title != "Not Torrent" {
		t.Errorf("shared fields not mapped: Id=%q Title=%q", torrent.Id, torrent.Title)
	}
	if string(torrent.Type) != "Topic" {
		t.Errorf("FINDING regression: Type = %q, expected leaked %q proving As* ignores the discriminator",
			torrent.Type, "Topic")
	}
	if torrent.Seeds != nil || torrent.MagnetLink != nil {
		t.Errorf("torrent-only fields populated from a Topic source: Seeds=%v Magnet=%v",
			torrent.Seeds, torrent.MagnetLink)
	}
}
