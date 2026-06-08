package server

import "fmt"

// Discriminator-honoring accessors for the ForumTopicDto discriminated union.
//
// The oapi-codegen-generated As* accessors in api.gen.go (DO NOT EDIT) do a
// blind json.Unmarshal of the raw union bytes into the target struct and never
// consult the "type" discriminator. Because the three variants
// (Topic / Torrent / CommentsPage) share the Author/Category/Id/Title fields
// and every variant-specific field is a nilable pointer, a DTO whose
// discriminator says "Topic" round-trips through AsForumTopicDtoTorrent() with
// NO error — a silent type confusion. The generated code cannot be edited
// (CI enforces "regenerate produces empty diff"), so the fix lives here: each
// *Checked accessor reads Discriminator() first and refuses to map a union
// whose discriminator does not name the requested variant.
//
// Callers that decode untrusted/cross-backend ForumTopicDto values (the Ktor
// parity path) MUST prefer these *Checked accessors, or use ValueByDiscriminator
// which already dispatches on the discriminator.

// ErrDiscriminatorMismatch is returned by a *Checked accessor when the union's
// "type" discriminator does not match the requested variant.
type ErrDiscriminatorMismatch struct {
	Want string // the variant the accessor was asked for
	Got  string // the discriminator actually present on the wire
}

func (e *ErrDiscriminatorMismatch) Error() string {
	return fmt.Sprintf("ForumTopicDto discriminator mismatch: requested %q but union type is %q", e.Want, e.Got)
}

// AsForumTopicDtoTorrentChecked returns the union as a ForumTopicDtoTorrent only
// when the discriminator is "Torrent". On a discriminator mismatch it returns a
// zero value and *ErrDiscriminatorMismatch. On a structurally invalid union it
// returns the underlying decode error (the discriminator probe cannot read a
// "type" field). This is the discriminator-honoring counterpart to the
// generated AsForumTopicDtoTorrent.
func (t ForumTopicDto) AsForumTopicDtoTorrentChecked() (ForumTopicDtoTorrent, error) {
	disc, err := t.Discriminator()
	if err != nil {
		return ForumTopicDtoTorrent{}, err
	}
	if disc != string(Torrent) {
		return ForumTopicDtoTorrent{}, &ErrDiscriminatorMismatch{Want: string(Torrent), Got: disc}
	}
	return t.AsForumTopicDtoTorrent()
}

// AsForumTopicDtoTopicChecked returns the union as a ForumTopicDtoTopic only
// when the discriminator is "Topic".
func (t ForumTopicDto) AsForumTopicDtoTopicChecked() (ForumTopicDtoTopic, error) {
	disc, err := t.Discriminator()
	if err != nil {
		return ForumTopicDtoTopic{}, err
	}
	if disc != string(Topic) {
		return ForumTopicDtoTopic{}, &ErrDiscriminatorMismatch{Want: string(Topic), Got: disc}
	}
	return t.AsForumTopicDtoTopic()
}

// AsForumTopicDtoCommentsPageChecked returns the union as a
// ForumTopicDtoCommentsPage only when the discriminator is "CommentsPage".
func (t ForumTopicDto) AsForumTopicDtoCommentsPageChecked() (ForumTopicDtoCommentsPage, error) {
	disc, err := t.Discriminator()
	if err != nil {
		return ForumTopicDtoCommentsPage{}, err
	}
	if disc != string(CommentsPage) {
		return ForumTopicDtoCommentsPage{}, &ErrDiscriminatorMismatch{Want: string(CommentsPage), Got: disc}
	}
	return t.AsForumTopicDtoCommentsPage()
}
