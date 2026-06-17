package gutenberg

import "testing"

// TestFormatDescription_Branches exercises the pure formatDescription transform
// that produces the user-visible topic Description text. The existing
// TestGetBook_BuildsURLAndParses only reaches the single-author/with-subjects
// path, leaving the multi-author comma-separator branch (i > 0) and the
// empty-subjects branch uncovered. Both are user-visible: the Description string
// is rendered to the end user on the topic screen.
func TestFormatDescription_Branches(t *testing.T) {
	tests := []struct {
		name string
		in   book
		want string
	}{
		{
			name: "multiple authors join with comma and subjects present",
			in: book{
				Authors:       []author{{Name: "Alice"}, {Name: "Bob"}, {Name: "Carol"}},
				Subjects:      []string{"Fiction", "Adventure"},
				DownloadCount: 42,
			},
			want: "Authors: Alice, Bob, Carol\nSubjects: Fiction, Adventure\nDownloads: 42",
		},
		{
			name: "single author no subjects omits subjects line",
			in: book{
				Authors:       []author{{Name: "Solo"}},
				Subjects:      nil,
				DownloadCount: 7,
			},
			want: "Authors: Solo\nDownloads: 7",
		},
		{
			name: "no authors no subjects zero downloads",
			in: book{
				Authors:       nil,
				Subjects:      nil,
				DownloadCount: 0,
			},
			want: "Authors: \nDownloads: 0",
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			got := formatDescription(tc.in)
			if got != tc.want {
				t.Errorf("formatDescription() =\n%q\nwant\n%q", got, tc.want)
			}
		})
	}
}
