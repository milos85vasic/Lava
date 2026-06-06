package server

import (
	"testing"
)

// FuzzForumTopicDtoUnionDecode fuzzes the discriminated-union decode path that a
// handler takes when it deserialises a ForumTopicDto from (potentially hostile
// or malformed) JSON bytes and then tries every As* accessor. The safety
// property pinned here is robustness: neither UnmarshalJSON nor any As*
// accessor may PANIC on arbitrary input — they must return an error or a
// (possibly empty / mismapped per the documented FINDING) struct. A panic in
// this path would crash a request goroutine on malformed upstream data.
//
// This is deliberately a robustness fuzz (no-panic + error-discipline), NOT a
// correctness fuzz: the FINDING (As* ignores the discriminator) is pinned by
// the table-driven tests in forumtopicdto_union_test.go, not here.
func FuzzForumTopicDtoUnionDecode(f *testing.F) {
	f.Add([]byte(`{"type":"Torrent","id":"1","title":"t"}`))
	f.Add([]byte(`{"type":"Topic","id":"2","title":"x"}`))
	f.Add([]byte(`{"type":"CommentsPage","id":"3","title":"c","page":1,"pages":2}`))
	f.Add([]byte(`{}`))
	f.Add([]byte(`null`))
	f.Add([]byte(`[1,2,3]`))
	f.Add([]byte(`{"type":42}`))
	f.Add([]byte(`{"seeds":"not-an-int"}`))
	f.Add([]byte(``))

	f.Fuzz(func(t *testing.T, raw []byte) {
		var dto ForumTopicDto
		if err := dto.UnmarshalJSON(raw); err != nil {
			// Malformed JSON for the union is allowed to error; the contract is
			// only that it does not panic. Nothing further to assert.
			return
		}

		// UnmarshalJSON accepted the bytes; every accessor must now be callable
		// without panicking. We intentionally ignore the (value, error) results
		// — correctness of the mapping is the FINDING's table tests' job; here we
		// only require no panic.
		_, _ = dto.AsForumTopicDtoTorrent()
		_, _ = dto.AsForumTopicDtoTopic()
		_, _ = dto.AsForumTopicDtoCommentsPage()

		// Re-marshalling an accepted union must also not panic.
		if _, err := dto.MarshalJSON(); err != nil {
			// An error is acceptable; a panic is not (would have aborted above).
			return
		}
	})
}
