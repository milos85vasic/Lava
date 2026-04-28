package rutracker

import (
	"bytes"
	"strings"
	"testing"

	"github.com/PuerkitoBio/goquery"

	gen "digital.vasic.lava.apigo/internal/gen/server"
)

// parseFragment wraps `inner` in a `<div class="post_body">` and parses
// it via ParsePostBody. Mirrors the production call site
// (`ParsePostBody(post.Find(".post_body"))`).
func parseFragment(t *testing.T, inner string) []gen.PostElementDto {
	t.Helper()
	html := `<html><body><div class="post_body">` + inner + `</div></body></html>`
	doc, err := goquery.NewDocumentFromReader(bytes.NewReader([]byte(html)))
	if err != nil {
		t.Fatalf("parse fragment: %v", err)
	}
	body := doc.Find(".post_body").First()
	return ParsePostBody(body)
}

// discriminator returns the .Type of the union, regardless of which variant.
// goquery's PostElementDto.Discriminator() reads the JSON RawMessage's
// "type" field — works for any populated union.
func discriminator(t *testing.T, pe gen.PostElementDto) string {
	t.Helper()
	d, err := pe.Discriminator()
	if err != nil {
		t.Fatalf("discriminator: %v", err)
	}
	return d
}

func TestParsePostBody_PlainText(t *testing.T) {
	out := parseFragment(t, "hello")
	if len(out) != 1 {
		t.Fatalf("len: got %d, want 1", len(out))
	}
	if d := discriminator(t, out[0]); d != "Text" {
		t.Fatalf("discriminator: got %q, want %q", d, "Text")
	}
	tx, err := out[0].AsPostElementText()
	if err != nil {
		t.Fatalf("AsPostElementText: %v", err)
	}
	if tx.Value != "hello" {
		t.Errorf("value: got %q, want %q", tx.Value, "hello")
	}
}

func TestParsePostBody_TextSplitsOnNewline(t *testing.T) {
	out := parseFragment(t, "first line\nsecond line\nthird")
	// Expected: Text("first line"), Br, Text("second line"), Br, Text("third")
	if len(out) != 5 {
		t.Fatalf("len: got %d, want 5 (Text/Br/Text/Br/Text)", len(out))
	}
	wantTypes := []string{"Text", "Br", "Text", "Br", "Text"}
	for i, w := range wantTypes {
		if got := discriminator(t, out[i]); got != w {
			t.Errorf("out[%d] discriminator: got %q, want %q", i, got, w)
		}
	}
	first, _ := out[0].AsPostElementText()
	if first.Value != "first line" {
		t.Errorf("out[0] value: got %q, want %q", first.Value, "first line")
	}
	third, _ := out[4].AsPostElementText()
	if third.Value != "third" {
		t.Errorf("out[4] value: got %q, want %q", third.Value, "third")
	}
}

func TestParsePostBody_Bold(t *testing.T) {
	out := parseFragment(t, `<span class="post-b">x</span>`)
	if len(out) != 1 {
		t.Fatalf("len: got %d, want 1", len(out))
	}
	if d := discriminator(t, out[0]); d != "Bold" {
		t.Fatalf("discriminator: got %q, want \"Bold\"", d)
	}
	b, err := out[0].AsPostElementBold()
	if err != nil {
		t.Fatalf("AsPostElementBold: %v", err)
	}
	if len(b.Children) != 1 {
		t.Fatalf("Bold children len: got %d, want 1", len(b.Children))
	}
	tx, _ := b.Children[0].AsPostElementText()
	if tx.Value != "x" {
		t.Errorf("inner text: got %q, want \"x\"", tx.Value)
	}
}

func TestParsePostBody_Italic(t *testing.T) {
	out := parseFragment(t, `<span class="post-i">x</span>`)
	if len(out) != 1 || discriminator(t, out[0]) != "Italic" {
		t.Fatalf("expected single Italic, got %#v", out)
	}
	it, _ := out[0].AsPostElementItalic()
	tx, _ := it.Children[0].AsPostElementText()
	if tx.Value != "x" {
		t.Errorf("inner: got %q", tx.Value)
	}
}

func TestParsePostBody_Underscore(t *testing.T) {
	out := parseFragment(t, `<span class="post-u">x</span>`)
	if len(out) != 1 || discriminator(t, out[0]) != "Underscore" {
		t.Fatalf("expected single Underscore, got %#v", out)
	}
}

func TestParsePostBody_Crossed(t *testing.T) {
	out := parseFragment(t, `<span class="post-s">x</span>`)
	if len(out) != 1 || discriminator(t, out[0]) != "Crossed" {
		t.Fatalf("expected single Crossed, got %#v", out)
	}
}

// TestParsePostBody_Box pins the LITERAL `ost-box` class — Kotlin checks
// `node.hasClass("ost-box")` (not `post-box`). Faithfully ported.
func TestParsePostBody_Box(t *testing.T) {
	out := parseFragment(t, `<span class="ost-box">x</span>`)
	if len(out) != 1 || discriminator(t, out[0]) != "Box" {
		t.Fatalf("expected Box, got %#v", out)
	}
	b, _ := out[0].AsPostElementBox()
	tx, _ := b.Children[0].AsPostElementText()
	if tx.Value != "x" {
		t.Errorf("inner: got %q, want \"x\"", tx.Value)
	}
}

func TestParsePostBody_Link(t *testing.T) {
	out := parseFragment(t, `<a class="postLink" href="https://example.com">click</a>`)
	if len(out) != 1 || discriminator(t, out[0]) != "Link" {
		t.Fatalf("expected Link, got %#v", out)
	}
	l, _ := out[0].AsPostElementLink()
	if l.Src != "https://example.com" {
		t.Errorf("Src: got %q, want %q", l.Src, "https://example.com")
	}
	tx, _ := l.Children[0].AsPostElementText()
	if tx.Value != "click" {
		t.Errorf("inner: got %q, want \"click\"", tx.Value)
	}
}

func TestParsePostBody_Image(t *testing.T) {
	out := parseFragment(t, `<var class="postImg" title="https://img/x.png"></var>`)
	if len(out) != 1 || discriminator(t, out[0]) != "Image" {
		t.Fatalf("expected Image, got %#v", out)
	}
	im, _ := out[0].AsPostElementImage()
	if im.Src != "https://img/x.png" {
		t.Errorf("Src: got %q, want %q", im.Src, "https://img/x.png")
	}
}

func TestParsePostBody_ImageAligned(t *testing.T) {
	cases := []struct {
		cls       string
		alignment gen.PostElementImageAlignedAlignment
	}{
		{"img-left", gen.Start},
		{"img-top", gen.Top},
		{"img-right", gen.End},
		{"img-bottom", gen.Bottom},
	}
	for _, tc := range cases {
		t.Run(tc.cls, func(t *testing.T) {
			frag := `<var class="postImg postImgAligned ` + tc.cls + `" title="https://img/x.png"></var>`
			out := parseFragment(t, frag)
			if len(out) != 1 || discriminator(t, out[0]) != "ImageAligned" {
				t.Fatalf("expected ImageAligned, got %#v", out)
			}
			ia, _ := out[0].AsPostElementImageAligned()
			if ia.Src != "https://img/x.png" {
				t.Errorf("Src: got %q", ia.Src)
			}
			if ia.Alignment != tc.alignment {
				t.Errorf("Alignment: got %q, want %q", ia.Alignment, tc.alignment)
			}
		})
	}
}

func TestParsePostBody_StyleTextAlign(t *testing.T) {
	cases := []struct {
		css      string
		expected gen.PostElementAlignAlignment
	}{
		{"left", gen.Left},
		{"right", gen.Right},
		{"center", gen.Center},
		{"justify", gen.Justify},
	}
	for _, tc := range cases {
		t.Run(tc.css, func(t *testing.T) {
			frag := `<span style="text-align: ` + tc.css + `">x</span>`
			out := parseFragment(t, frag)
			if len(out) != 1 || discriminator(t, out[0]) != "Align" {
				t.Fatalf("expected Align, got %#v", out)
			}
			a, _ := out[0].AsPostElementAlign()
			if a.Alignment != tc.expected {
				t.Errorf("Alignment: got %q, want %q", a.Alignment, tc.expected)
			}
			tx, _ := a.Children[0].AsPostElementText()
			if tx.Value != "x" {
				t.Errorf("inner: got %q, want \"x\"", tx.Value)
			}
		})
	}
}

func TestParsePostBody_StyleFontSize(t *testing.T) {
	out := parseFragment(t, `<span style="font-size: 14px">x</span>`)
	if len(out) != 1 || discriminator(t, out[0]) != "Size" {
		t.Fatalf("expected Size, got %#v", out)
	}
	sz, _ := out[0].AsPostElementSize()
	if sz.Size != 14 {
		t.Errorf("Size.Size: got %d, want 14", sz.Size)
	}
	tx, _ := sz.Children[0].AsPostElementText()
	if tx.Value != "x" {
		t.Errorf("inner: got %q", tx.Value)
	}
}

func TestParsePostBody_StyleColor_Hex(t *testing.T) {
	// Kotlin parses the digits AFTER `#` as decimal (toLong()) — so a
	// value like `#123456` becomes int64(123456). Anything containing
	// hex letters (a-f) trips Kotlin's runCatching → null and falls
	// through to plain recursion. We test the digit-only path.
	out := parseFragment(t, `<span style="color: #123456">x</span>`)
	if len(out) != 1 || discriminator(t, out[0]) != "Color" {
		t.Fatalf("expected Color, got %#v", out)
	}
	c, _ := out[0].AsPostElementColor()
	hex, err := c.Color.AsColorValueHex()
	if err != nil {
		t.Fatalf("expected ColorValueHex, got error %v (and discriminator-by-discriminator may indicate ColorValueName)", err)
	}
	if hex.Hex != 123456 {
		t.Errorf("Hex.Hex: got %d, want 123456", hex.Hex)
	}
	tx, _ := c.Children[0].AsPostElementText()
	if tx.Value != "x" {
		t.Errorf("inner: got %q", tx.Value)
	}
}

func TestParsePostBody_StyleColor_Name(t *testing.T) {
	out := parseFragment(t, `<span style="color: red">x</span>`)
	if len(out) != 1 || discriminator(t, out[0]) != "Color" {
		t.Fatalf("expected Color, got %#v", out)
	}
	c, _ := out[0].AsPostElementColor()
	name, err := c.Color.AsColorValueName()
	if err != nil {
		t.Fatalf("expected ColorValueName, got error %v", err)
	}
	if name.Name != "red" {
		t.Errorf("Name.Name: got %q, want \"red\"", name.Name)
	}
}

func TestParsePostBody_Quote(t *testing.T) {
	frag := `<div class="q-wrap"><div class="q-head">SomeAuthor</div><span class="q-post">42</span><div class="q">quoted text</div></div>`
	out := parseFragment(t, frag)
	if len(out) != 1 || discriminator(t, out[0]) != "Quote" {
		t.Fatalf("expected Quote, got %#v", out)
	}
	q, _ := out[0].AsPostElementQuote()
	if q.Title != "SomeAuthor" {
		t.Errorf("Title: got %q, want \"SomeAuthor\"", q.Title)
	}
	if q.Id != "42" {
		t.Errorf("Id: got %q, want \"42\"", q.Id)
	}
	if len(q.Children) == 0 {
		t.Fatalf("Quote children: got 0, want at least 1 Text element")
	}
	// First non-Br child must be Text("quoted text").
	tx, err := q.Children[0].AsPostElementText()
	if err != nil {
		t.Fatalf("first child not Text: %v", err)
	}
	if !strings.Contains(tx.Value, "quoted text") {
		t.Errorf("Quote child text: got %q, want to contain \"quoted text\"", tx.Value)
	}
}

func TestParsePostBody_Code(t *testing.T) {
	frag := `<div class="c-wrap"><span class="c-head">go</span><div class="c-body">code body</div></div>`
	out := parseFragment(t, frag)
	if len(out) != 1 || discriminator(t, out[0]) != "Code" {
		t.Fatalf("expected Code, got %#v", out)
	}
	c, _ := out[0].AsPostElementCode()
	if c.Title != "go" {
		t.Errorf("Title: got %q, want \"go\"", c.Title)
	}
	if len(c.Children) == 0 {
		t.Fatalf("Code children: 0")
	}
	tx, err := c.Children[0].AsPostElementText()
	if err != nil {
		t.Fatalf("first child not Text: %v", err)
	}
	if !strings.Contains(tx.Value, "code body") {
		t.Errorf("inner: got %q", tx.Value)
	}
}

func TestParsePostBody_Spoiler(t *testing.T) {
	frag := `<div class="sp-wrap"><span class="sp-head">spoil</span><div class="sp-body">hidden text</div></div>`
	out := parseFragment(t, frag)
	if len(out) != 1 || discriminator(t, out[0]) != "Spoiler" {
		t.Fatalf("expected Spoiler, got %#v", out)
	}
	sp, _ := out[0].AsPostElementSpoiler()
	if sp.Title != "spoil" {
		t.Errorf("Title: got %q", sp.Title)
	}
	if len(sp.Children) == 0 {
		t.Fatalf("Spoiler children: 0")
	}
	tx, err := sp.Children[0].AsPostElementText()
	if err != nil {
		t.Fatalf("first child not Text: %v", err)
	}
	if !strings.Contains(tx.Value, "hidden text") {
		t.Errorf("inner: got %q", tx.Value)
	}
}

func TestParsePostBody_List(t *testing.T) {
	frag := `<ul class="post-ul"><li>x</li></ul>`
	out := parseFragment(t, frag)
	if len(out) != 1 || discriminator(t, out[0]) != "List" {
		t.Fatalf("expected List, got %#v", out)
	}
	l, _ := out[0].AsPostElementList()
	if len(l.Children) == 0 {
		t.Fatalf("List children: 0")
	}
}

func TestParsePostBody_Hr(t *testing.T) {
	t.Run("plain hr tag", func(t *testing.T) {
		out := parseFragment(t, `<hr>`)
		if len(out) != 1 || discriminator(t, out[0]) != "Hr" {
			t.Fatalf("expected Hr, got %#v", out)
		}
	})
	t.Run("post-hr class", func(t *testing.T) {
		out := parseFragment(t, `<span class="post-hr"></span>`)
		if len(out) != 1 || discriminator(t, out[0]) != "Hr" {
			t.Fatalf("expected Hr from class, got %#v", out)
		}
	})
}

func TestParsePostBody_Br(t *testing.T) {
	t.Run("plain br tag", func(t *testing.T) {
		out := parseFragment(t, `<br>`)
		if len(out) != 1 || discriminator(t, out[0]) != "Br" {
			t.Fatalf("expected Br, got %#v", out)
		}
	})
	t.Run("post-br class", func(t *testing.T) {
		out := parseFragment(t, `<span class="post-br"></span>`)
		if len(out) != 1 || discriminator(t, out[0]) != "PostBr" {
			t.Fatalf("expected PostBr from class, got %#v", out)
		}
	})
}

func TestParsePostBody_Nested(t *testing.T) {
	out := parseFragment(t, `<span class="post-b"><span class="post-i">x</span></span>`)
	if len(out) != 1 || discriminator(t, out[0]) != "Bold" {
		t.Fatalf("expected outer Bold, got %#v", out)
	}
	b, _ := out[0].AsPostElementBold()
	if len(b.Children) != 1 {
		t.Fatalf("Bold children len: got %d, want 1", len(b.Children))
	}
	if d := discriminator(t, b.Children[0]); d != "Italic" {
		t.Fatalf("inner discriminator: got %q, want \"Italic\"", d)
	}
	it, _ := b.Children[0].AsPostElementItalic()
	if len(it.Children) != 1 {
		t.Fatalf("Italic children len: got %d, want 1", len(it.Children))
	}
	tx, _ := it.Children[0].AsPostElementText()
	if tx.Value != "x" {
		t.Errorf("innermost text: got %q, want \"x\"", tx.Value)
	}
}
