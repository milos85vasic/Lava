package server

import (
	"encoding/json"
	"testing"
)

// These tests pin the ACTUAL behavior of the oapi-codegen-generated
// ForumTopicDto discriminated-union accessors (As*/From*/Merge*). They are
// white-box (package server) because the union is held in an unexported
// json.RawMessage field; the From* builders are the only way to populate it.
//
// FINDING (documented, NOT fixed — see the FINDING block in
// TestAsForumTopicDtoTorrent_IgnoresDiscriminator): the As* accessors do a
// blind json.Unmarshal of the raw union bytes into the target struct and do
// NOT consult the "type" discriminator. Because the three variant structs
// share the Author/Category/Id/Title fields and every variant-specific field
// is a nilable pointer, a DTO built FromForumTopicDtoTopic round-trips through
// AsForumTopicDtoTorrent with NO error — silently mapping a Topic onto a
// Torrent shape. Fixing this carries cross-backend parity-contract risk (the
// Ktor proxy wire shape is the contract these gen types mirror), so it is
// documented here rather than changed.

// TestForumTopicDtoTorrentRoundTrip is the happy-path: a DTO built from a
// Torrent variant reads back as a Torrent with every field preserved. Primary
// assertion is on the round-tripped field values (real observable data).
func TestForumTopicDtoTorrentRoundTrip(t *testing.T) {
	seeds := int32(42)
	leeches := int32(7)
	magnet := "magnet:?xt=urn:btih:deadbeef"
	size := "1.4 GiB"
	in := ForumTopicDtoTorrent{
		Id:         "topic-9001",
		Title:      "Real Torrent Title",
		Seeds:      &seeds,
		Leeches:    &leeches,
		MagnetLink: &magnet,
		Size:       &size,
	}

	var dto ForumTopicDto
	if err := dto.FromForumTopicDtoTorrent(in); err != nil {
		t.Fatalf("FromForumTopicDtoTorrent: %v", err)
	}

	out, err := dto.AsForumTopicDtoTorrent()
	if err != nil {
		t.Fatalf("AsForumTopicDtoTorrent: %v", err)
	}
	if out.Id != in.Id {
		t.Errorf("Id = %q, want %q", out.Id, in.Id)
	}
	if out.Title != in.Title {
		t.Errorf("Title = %q, want %q", out.Title, in.Title)
	}
	if out.Seeds == nil || *out.Seeds != seeds {
		t.Errorf("Seeds = %v, want %d", out.Seeds, seeds)
	}
	if out.Leeches == nil || *out.Leeches != leeches {
		t.Errorf("Leeches = %v, want %d", out.Leeches, leeches)
	}
	if out.MagnetLink == nil || *out.MagnetLink != magnet {
		t.Errorf("MagnetLink = %v, want %q", out.MagnetLink, magnet)
	}
	if out.Size == nil || *out.Size != size {
		t.Errorf("Size = %v, want %q", out.Size, size)
	}
	// From* stamps the discriminator: it MUST be "Torrent".
	if string(out.Type) != "Torrent" {
		t.Errorf("Type = %q, want %q (From* must stamp the discriminator)", out.Type, "Torrent")
	}
}

// TestFromForumTopicDtoTopicStampsDiscriminator confirms the From* builder
// stamps the "type" field on the wire bytes (the discriminator the client
// uses to decide which As* accessor is correct). Assertion is on the raw JSON.
func TestFromForumTopicDtoTopicStampsDiscriminator(t *testing.T) {
	var dto ForumTopicDto
	if err := dto.FromForumTopicDtoTopic(ForumTopicDtoTopic{Id: "t1", Title: "A Topic"}); err != nil {
		t.Fatalf("FromForumTopicDtoTopic: %v", err)
	}
	raw, err := dto.MarshalJSON()
	if err != nil {
		t.Fatalf("MarshalJSON: %v", err)
	}
	var probe struct {
		Type string `json:"type"`
	}
	if err := json.Unmarshal(raw, &probe); err != nil {
		t.Fatalf("unmarshal probe: %v", err)
	}
	if probe.Type != "Topic" {
		t.Errorf("wire type = %q, want %q", probe.Type, "Topic")
	}
}

// TestAsForumTopicDtoTorrent_IgnoresDiscriminator is the FINDING test. It builds
// a DTO from a Topic variant, then reads it back AS A TORRENT. The generated
// accessor returns NO error and produces a ForumTopicDtoTorrent carrying the
// Topic's Type="Topic" discriminator and the Topic's Id/Title — torrent-only
// fields (Seeds/Leeches/MagnetLink) are nil because they were absent from the
// Topic JSON.
//
// FINDING (latent, documented, NOT fixed):
//
//	AsForumTopicDtoTorrent() does NOT validate that the union actually holds a
//	Torrent. It blindly json.Unmarshals the bytes into ForumTopicDtoTorrent.
//	Consequence: a server handler (or client) that calls AsForumTopicDtoTorrent
//	on a Topic-shaped DTO gets a zero-error Torrent with bogus/empty torrent
//	fields and a Type of "Topic" — a type-confusion that the compiler cannot
//	catch and that no error surfaces. Callers MUST inspect the discriminator
//	(Type) themselves before choosing an As* accessor; the accessor will not.
//
// This test PINS the current behavior so a future regression (e.g. someone
// adding discriminator validation) is a visible, deliberate change rather than
// a silent surprise. It asserts on the observable returned struct, per §6.J.
func TestAsForumTopicDtoTorrent_IgnoresDiscriminator(t *testing.T) {
	var dto ForumTopicDto
	if err := dto.FromForumTopicDtoTopic(ForumTopicDtoTopic{Id: "topic-id", Title: "Not A Torrent"}); err != nil {
		t.Fatalf("FromForumTopicDtoTopic: %v", err)
	}

	// The accessor does NOT error despite the discriminator saying "Topic".
	torrent, err := dto.AsForumTopicDtoTorrent()
	if err != nil {
		t.Fatalf("FINDING regression: AsForumTopicDtoTorrent now errors on a Topic union (%v); "+
			"the documented latent behavior was a silent success — investigate before changing the parity contract", err)
	}

	// The mapped struct carries the Topic's shared fields verbatim...
	if torrent.Id != "topic-id" {
		t.Errorf("Id = %q, want %q (shared field maps across variants)", torrent.Id, "topic-id")
	}
	if torrent.Title != "Not A Torrent" {
		t.Errorf("Title = %q, want %q", torrent.Title, "Not A Torrent")
	}
	// ...and the discriminator that PROVES this is the type confusion: a real
	// Torrent would carry Type=="Torrent", but this came from a Topic.
	if string(torrent.Type) != "Topic" {
		t.Errorf("FINDING regression: Type = %q, expected the Topic discriminator %q to leak through "+
			"unchecked (proving As* ignores the discriminator)", torrent.Type, "Topic")
	}
	// Torrent-only fields are nil because they were never in the Topic JSON —
	// the silent mismap leaves them empty, which is exactly the hazard.
	if torrent.Seeds != nil || torrent.Leeches != nil || torrent.MagnetLink != nil {
		t.Errorf("torrent-only fields populated (Seeds=%v Leeches=%v Magnet=%v); "+
			"a Topic source must leave them nil", torrent.Seeds, torrent.Leeches, torrent.MagnetLink)
	}
}

// TestAsForumTopicDtoTopic_OnTorrentAlsoMismaps confirms the discriminator
// blindness is symmetric: a Torrent-built DTO reads back AS A TOPIC without
// error, carrying the Torrent's Type="Torrent". Documents the same FINDING
// from the opposite direction.
func TestAsForumTopicDtoTopic_OnTorrentAlsoMismaps(t *testing.T) {
	var dto ForumTopicDto
	if err := dto.FromForumTopicDtoTorrent(ForumTopicDtoTorrent{Id: "tor-id", Title: "A Torrent"}); err != nil {
		t.Fatalf("FromForumTopicDtoTorrent: %v", err)
	}
	topic, err := dto.AsForumTopicDtoTopic()
	if err != nil {
		t.Fatalf("FINDING regression: AsForumTopicDtoTopic now errors on a Torrent union (%v)", err)
	}
	if topic.Id != "tor-id" || topic.Title != "A Torrent" {
		t.Errorf("shared fields not preserved: Id=%q Title=%q", topic.Id, topic.Title)
	}
	if string(topic.Type) != "Torrent" {
		t.Errorf("Type = %q, want the leaked Torrent discriminator %q", topic.Type, "Torrent")
	}
}

// TestAsForumTopicDtoTorrent_ErrorsOnGarbageUnion proves the accessor is NOT a
// total no-op: when the union bytes are not valid JSON for the target shape
// (e.g. a JSON array where an object is required), AsForumTopicDtoTorrent DOES
// return the json.Unmarshal error. This bounds the FINDING: the accessor
// surfaces structural decode errors; it only fails to surface DISCRIMINATOR
// mismatches between structurally-compatible variants.
func TestAsForumTopicDtoTorrent_ErrorsOnGarbageUnion(t *testing.T) {
	var dto ForumTopicDto
	if err := dto.UnmarshalJSON([]byte(`[1,2,3]`)); err != nil {
		t.Fatalf("UnmarshalJSON(array): %v", err)
	}
	if _, err := dto.AsForumTopicDtoTorrent(); err == nil {
		t.Error("AsForumTopicDtoTorrent on a JSON-array union returned nil error; " +
			"structural decode failures MUST surface")
	}
}
