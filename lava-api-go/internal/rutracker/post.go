// Package rutracker — post.go ports ParsePostUseCase.kt 1:1 — the
// recursive post-body parser that turns rutracker's rich-text HTML into
// the OpenAPI PostElementDto[] discriminated-union tree.
//
// Element classification order (matches Kotlin lines 56-110, first match
// wins):
//
//  1. node has a `style` attribute    → Align/Size/Color (or recurse if
//     the style key is none of the three)
//  2. node has class `ost-box`        → Box(children)        (NOTE: literal
//     `ost-box`, not `post-box` — this
//     is what the Kotlin parser checks;
//     it is faithful to upstream HTML.)
//  3. `post-b`                        → Bold(children)
//  4. `post-i`                        → Italic(children)
//  5. `post-u`                        → Underscore(children)
//  6. `post-s`                        → Crossed(children)
//  7. `postLink`                      → Link(href, children)
//  8. `postImg`
//     a. with `postImgAligned`       → ImageAligned(title, alignment)
//     b. without                     → Image(title)
//  9. `post-ul`                       → List(children)        (Kotlin name
//     is UList; OpenAPI variant name is
//     PostElementList)
//  10. `c-wrap`                        → Code(.c-head text, recurse over .c-body)
//  11. `sp-wrap`                       → Spoiler(.sp-head text, recurse over .sp-body)
//  12. `q-wrap`                        → Quote(.q-head, .q-post, recurse
//     over .q with .q-post REMOVED)
//  13. `post-hr` OR `<hr>`             → Hr
//  14. `post-br`                       → PostBr
//  15. `<br>`                          → Br
//  16. else                            → recurse without wrapping
//
// Text handling (Kotlin lines 117-125): split on `\n`, drop blank lines,
// emit each non-blank chunk as Text, with Br between chunks.
package rutracker

import (
	"strconv"
	"strings"

	"github.com/PuerkitoBio/goquery"
	"golang.org/x/net/html"

	gen "digital.vasic.lava.apigo/internal/gen/server"
)

// ParsePostBody parses the contents of a `.post_body` (or any) selection
// into the recursive PostElementDto tree. The selection may be empty —
// the result is a non-nil empty slice in that case.
func ParsePostBody(s *goquery.Selection) []gen.PostElementDto {
	out := make([]gen.PostElementDto, 0)
	if s == nil || s.Length() == 0 {
		return out
	}
	appendElements(&out, s)
	return out
}

// appendElements ports `ElementsList.appendElements(elements: Elements)`.
func appendElements(out *[]gen.PostElementDto, elements *goquery.Selection) {
	elements.Each(func(_ int, el *goquery.Selection) {
		appendElement(out, el)
	})
}

// appendElement ports `ElementsList.appendElement(element: Element?)`.
// The Kotlin distinguishes "has child nodes" from "is leaf with text" so
// that text-only leaves (e.g. a bare `<span>hello</span>`) still emit a
// Text element rather than being skipped.
func appendElement(out *[]gen.PostElementDto, el *goquery.Selection) {
	if el == nil || el.Length() == 0 {
		return
	}
	node := el.Get(0)
	if node == nil {
		return
	}
	if node.FirstChild != nil {
		// Walk children including text nodes (goquery's Children() drops
		// non-Element nodes; we use the html.Node tree directly).
		for c := node.FirstChild; c != nil; c = c.NextSibling {
			appendNode(out, c)
		}
		return
	}
	if t := strings.TrimSpace(el.Text()); t != "" {
		text(out, el.Text())
	}
}

// appendNode ports `ElementsList.appendNode(node: Node)` — switches on
// element vs text-node and dispatches to the per-class branches.
func appendNode(out *[]gen.PostElementDto, n *html.Node) {
	switch n.Type {
	case html.ElementNode:
		dispatchElement(out, wrapNode(n))
	case html.TextNode:
		text(out, n.Data)
	}
}

// wrapNode returns a *goquery.Selection containing exactly the single
// html.Node `n`. goquery.NewDocumentFromNode treats `n` as the root of a
// new Document; its `.Selection` field then points at exactly that node,
// while preserving access to the live child subtree (no copy is made).
// This is the cheapest way to bridge the raw-html.Node walk we need for
// text-node interleaving with the goquery API used by the per-class
// dispatch branches.
func wrapNode(n *html.Node) *goquery.Selection {
	return goquery.NewDocumentFromNode(n).Selection
}

// dispatchElement applies the 16-case classification table from the
// package-level doc comment.
func dispatchElement(out *[]gen.PostElementDto, sel *goquery.Selection) {
	if sel == nil || sel.Length() == 0 {
		return
	}

	// 1. style attribute → Align/Size/Color (or recurse if unknown style).
	if styleAttr, ok := sel.Attr("style"); ok && styleAttr != "" {
		switch s := parseStyle(styleAttr).(type) {
		case styleAlignment:
			align(out, s.alignment, func(inner *[]gen.PostElementDto) {
				appendElement(inner, sel)
			})
			return
		case styleSize:
			size(out, s.size, func(inner *[]gen.PostElementDto) {
				appendElement(inner, sel)
			})
			return
		case styleColor:
			color(out, s.color, func(inner *[]gen.PostElementDto) {
				appendElement(inner, sel)
			})
			return
		case nil:
			// Unknown / unstyled — fall through to recurse.
			appendElement(out, sel)
			return
		}
	}

	// 2-15. class-driven branches.
	switch {
	case sel.HasClass("ost-box"):
		// Note: literal "ost-box" — Kotlin checks this exact class string.
		boxWrap(out, func(inner *[]gen.PostElementDto) {
			appendElement(inner, sel)
		})
	case sel.HasClass("post-b"):
		bold(out, func(inner *[]gen.PostElementDto) {
			appendElement(inner, sel)
		})
	case sel.HasClass("post-i"):
		italic(out, func(inner *[]gen.PostElementDto) {
			appendElement(inner, sel)
		})
	case sel.HasClass("post-u"):
		underscore(out, func(inner *[]gen.PostElementDto) {
			appendElement(inner, sel)
		})
	case sel.HasClass("post-s"):
		crossed(out, func(inner *[]gen.PostElementDto) {
			appendElement(inner, sel)
		})
	case sel.HasClass("postLink"):
		href, _ := sel.Attr("href")
		link(out, href, func(inner *[]gen.PostElementDto) {
			appendElement(inner, sel)
		})
	case sel.HasClass("postImg"):
		title, _ := sel.Attr("title")
		if sel.HasClass("postImgAligned") {
			align := imageAlignmentFromClasses(sel)
			imageAligned(out, title, align)
		} else {
			image(out, title)
		}
	case sel.HasClass("post-ul"):
		uList(out, func(inner *[]gen.PostElementDto) {
			appendElement(inner, sel)
		})
	case sel.HasClass("c-wrap"):
		head := strings.TrimSpace(sel.Find(".c-head").First().Text())
		body := sel.Find(".c-body").First()
		code(out, head, func(inner *[]gen.PostElementDto) {
			appendElement(inner, body)
		})
	case sel.HasClass("sp-wrap"):
		head := strings.TrimSpace(sel.Find(".sp-head").First().Text())
		body := sel.Find(".sp-body").First()
		spoiler(out, head, func(inner *[]gen.PostElementDto) {
			appendElement(inner, body)
		})
	case sel.HasClass("q-wrap"):
		title := strings.TrimSpace(sel.Find(".q-head").First().Text())
		// .q-post is removed BEFORE the `.q` recursion (Kotlin: .q-post
		// is captured for the discriminator id, then `.q-post` is removed
		// from the subtree, then `.q` is walked).
		id := strings.TrimSpace(sel.Find(".q-post").First().Text())
		// Clone subtree by removing .q-post; goquery's Remove mutates the
		// underlying node tree, but at this point the node is part of the
		// walked document and the parent walk has already advanced past
		// it (q-wrap is the current node; .q-post is a descendant). It is
		// safe to mutate it: subsequent dispatches start from sibling
		// nodes that no longer contain .q-post.
		sel.Find(".q-post").Remove()
		body := sel.Find(".q").First()
		quote(out, title, id, func(inner *[]gen.PostElementDto) {
			appendElement(inner, body)
		})
	case sel.HasClass("post-hr"):
		hr(out)
	case sel.HasClass("post-br"):
		postBr(out)
	default:
		// Tag-level checks (hr / br) — Kotlin matches `node.tag().name`.
		tag := goquery.NodeName(sel)
		switch tag {
		case "hr":
			hr(out)
		case "br":
			br(out)
		default:
			appendElement(out, sel)
		}
	}
}

// imageAlignmentFromClasses ports the `when { node.hasClass("img-left") …}`
// chain from Kotlin lines 78-83.
func imageAlignmentFromClasses(sel *goquery.Selection) gen.PostElementImageAlignedAlignment {
	switch {
	case sel.HasClass("img-left"):
		return gen.Start
	case sel.HasClass("img-top"):
		return gen.Top
	case sel.HasClass("img-right"):
		return gen.End
	case sel.HasClass("img-bottom"):
		return gen.Bottom
	default:
		return gen.Start
	}
}

// text ports `ElementsList.text(value: String)` — split on \n, drop blank
// chunks, emit each as a Text element with Br separators.
func text(out *[]gen.PostElementDto, value string) {
	parts := strings.Split(value, "\n")
	chunks := make([]string, 0, len(parts))
	for _, p := range parts {
		if strings.TrimSpace(p) != "" {
			chunks = append(chunks, p)
		}
	}
	for i, t := range chunks {
		var pe gen.PostElementDto
		if err := pe.FromPostElementText(gen.PostElementText{
			Type:  gen.Text,
			Value: t,
		}); err == nil {
			*out = append(*out, pe)
		}
		if i != len(chunks)-1 {
			emitBr(out)
		}
	}
}

// emitBr appends a Br element. Pulled out so both the text() splitter
// and the `<br>` tag-level branch reuse the same construction.
func emitBr(out *[]gen.PostElementDto) {
	var pe gen.PostElementDto
	if err := pe.FromPostElementBr(gen.PostElementBr{Type: gen.Br}); err == nil {
		*out = append(*out, pe)
	}
}

func br(out *[]gen.PostElementDto) { emitBr(out) }

func hr(out *[]gen.PostElementDto) {
	var pe gen.PostElementDto
	if err := pe.FromPostElementHr(gen.PostElementHr{Type: gen.Hr}); err == nil {
		*out = append(*out, pe)
	}
}

func postBr(out *[]gen.PostElementDto) {
	var pe gen.PostElementDto
	if err := pe.FromPostElementPostBr(gen.PostElementPostBr{Type: gen.PostBr}); err == nil {
		*out = append(*out, pe)
	}
}

// align / size / color / bold / italic / underscore / crossed / boxWrap
// / uList / code / spoiler / quote / link / image / imageAligned all
// mirror the Kotlin extension functions of the same names. Each takes a
// `recurse` closure that fills the inner slice; the closure pattern is the
// Go equivalent of Kotlin's `block: ElementsList.() -> Unit`.

func align(out *[]gen.PostElementDto, alignment gen.PostElementAlignAlignment, recurse func(*[]gen.PostElementDto)) {
	inner := make([]gen.PostElementDto, 0)
	recurse(&inner)
	var pe gen.PostElementDto
	if err := pe.FromPostElementAlign(gen.PostElementAlign{
		Alignment: alignment,
		Children:  inner,
		Type:      gen.Align,
	}); err == nil {
		*out = append(*out, pe)
	}
}

func size(out *[]gen.PostElementDto, sz int32, recurse func(*[]gen.PostElementDto)) {
	inner := make([]gen.PostElementDto, 0)
	recurse(&inner)
	var pe gen.PostElementDto
	if err := pe.FromPostElementSize(gen.PostElementSize{
		Size:     sz,
		Children: inner,
		Type:     gen.PostElementSizeTypeSize,
	}); err == nil {
		*out = append(*out, pe)
	}
}

func color(out *[]gen.PostElementDto, c gen.ColorValue, recurse func(*[]gen.PostElementDto)) {
	inner := make([]gen.PostElementDto, 0)
	recurse(&inner)
	var pe gen.PostElementDto
	if err := pe.FromPostElementColor(gen.PostElementColor{
		Color:    c,
		Children: inner,
		Type:     gen.Color,
	}); err == nil {
		*out = append(*out, pe)
	}
}

func bold(out *[]gen.PostElementDto, recurse func(*[]gen.PostElementDto)) {
	inner := make([]gen.PostElementDto, 0)
	recurse(&inner)
	var pe gen.PostElementDto
	if err := pe.FromPostElementBold(gen.PostElementBold{
		Children: inner,
		Type:     gen.Bold,
	}); err == nil {
		*out = append(*out, pe)
	}
}

func italic(out *[]gen.PostElementDto, recurse func(*[]gen.PostElementDto)) {
	inner := make([]gen.PostElementDto, 0)
	recurse(&inner)
	var pe gen.PostElementDto
	if err := pe.FromPostElementItalic(gen.PostElementItalic{
		Children: inner,
		Type:     gen.Italic,
	}); err == nil {
		*out = append(*out, pe)
	}
}

func underscore(out *[]gen.PostElementDto, recurse func(*[]gen.PostElementDto)) {
	inner := make([]gen.PostElementDto, 0)
	recurse(&inner)
	var pe gen.PostElementDto
	if err := pe.FromPostElementUnderscore(gen.PostElementUnderscore{
		Children: inner,
		Type:     gen.Underscore,
	}); err == nil {
		*out = append(*out, pe)
	}
}

func crossed(out *[]gen.PostElementDto, recurse func(*[]gen.PostElementDto)) {
	inner := make([]gen.PostElementDto, 0)
	recurse(&inner)
	var pe gen.PostElementDto
	if err := pe.FromPostElementCrossed(gen.PostElementCrossed{
		Children: inner,
		Type:     gen.Crossed,
	}); err == nil {
		*out = append(*out, pe)
	}
}

func boxWrap(out *[]gen.PostElementDto, recurse func(*[]gen.PostElementDto)) {
	inner := make([]gen.PostElementDto, 0)
	recurse(&inner)
	var pe gen.PostElementDto
	if err := pe.FromPostElementBox(gen.PostElementBox{
		Children: inner,
		Type:     gen.Box,
	}); err == nil {
		*out = append(*out, pe)
	}
}

func uList(out *[]gen.PostElementDto, recurse func(*[]gen.PostElementDto)) {
	inner := make([]gen.PostElementDto, 0)
	recurse(&inner)
	var pe gen.PostElementDto
	if err := pe.FromPostElementList(gen.PostElementList{
		Children: inner,
		Type:     gen.List,
	}); err == nil {
		*out = append(*out, pe)
	}
}

func code(out *[]gen.PostElementDto, title string, recurse func(*[]gen.PostElementDto)) {
	inner := make([]gen.PostElementDto, 0)
	recurse(&inner)
	var pe gen.PostElementDto
	if err := pe.FromPostElementCode(gen.PostElementCode{
		Title:    title,
		Children: inner,
		Type:     gen.Code,
	}); err == nil {
		*out = append(*out, pe)
	}
}

func spoiler(out *[]gen.PostElementDto, title string, recurse func(*[]gen.PostElementDto)) {
	inner := make([]gen.PostElementDto, 0)
	recurse(&inner)
	var pe gen.PostElementDto
	if err := pe.FromPostElementSpoiler(gen.PostElementSpoiler{
		Title:    title,
		Children: inner,
		Type:     gen.Spoiler,
	}); err == nil {
		*out = append(*out, pe)
	}
}

func quote(out *[]gen.PostElementDto, title, id string, recurse func(*[]gen.PostElementDto)) {
	inner := make([]gen.PostElementDto, 0)
	recurse(&inner)
	var pe gen.PostElementDto
	if err := pe.FromPostElementQuote(gen.PostElementQuote{
		Title:    title,
		Id:       id,
		Children: inner,
		Type:     gen.Quote,
	}); err == nil {
		*out = append(*out, pe)
	}
}

func link(out *[]gen.PostElementDto, src string, recurse func(*[]gen.PostElementDto)) {
	inner := make([]gen.PostElementDto, 0)
	recurse(&inner)
	var pe gen.PostElementDto
	if err := pe.FromPostElementLink(gen.PostElementLink{
		Src:      src,
		Children: inner,
		Type:     gen.Link,
	}); err == nil {
		*out = append(*out, pe)
	}
}

func image(out *[]gen.PostElementDto, src string) {
	var pe gen.PostElementDto
	if err := pe.FromPostElementImage(gen.PostElementImage{
		Src:  src,
		Type: gen.Image,
	}); err == nil {
		*out = append(*out, pe)
	}
}

func imageAligned(out *[]gen.PostElementDto, src string, alignment gen.PostElementImageAlignedAlignment) {
	var pe gen.PostElementDto
	if err := pe.FromPostElementImageAligned(gen.PostElementImageAligned{
		Src:       src,
		Alignment: alignment,
		Type:      gen.ImageAligned,
	}); err == nil {
		*out = append(*out, pe)
	}
}

// styleSpec is a tagged-union for the three known CSS styles. nil means
// "no recognised style key" and the caller falls through to plain recursion.
type styleSpec interface{ styleSpec() }

type styleAlignment struct{ alignment gen.PostElementAlignAlignment }
type styleSize struct{ size int32 }
type styleColor struct{ color gen.ColorValue }

func (styleAlignment) styleSpec() {}
func (styleSize) styleSpec()      {}
func (styleColor) styleSpec()     {}

// parseStyle ports `Element.getStyle()` — splits the `style="…"` string
// into key/value pairs and recognises text-align / font-size / color.
//
// First-match-wins ordering matches Kotlin's `when { styles.contains("text-align") …}`.
func parseStyle(style string) styleSpec {
	pairs := strings.Split(style, ";")
	kv := make(map[string]string, len(pairs))
	for _, p := range pairs {
		if strings.TrimSpace(p) == "" {
			continue
		}
		parts := strings.SplitN(p, ":", 2)
		if len(parts) < 2 {
			continue
		}
		kv[strings.TrimSpace(parts[0])] = strings.TrimSpace(parts[1])
	}
	if v, ok := kv["text-align"]; ok {
		// Kotlin: TextAlignment.valueOf(value.replaceFirstChar { titlecase })
		// → ASCII upper-case the first char. Unknown values fall through
		// to nil (TextAlignment.valueOf throws → runCatching → null).
		title := titleASCII(v)
		switch gen.PostElementAlignAlignment(title) {
		case gen.Left, gen.Right, gen.Center, gen.Justify:
			return styleAlignment{alignment: gen.PostElementAlignAlignment(title)}
		}
		return nil
	}
	if v, ok := kv["font-size"]; ok {
		// Kotlin: filter(Char::isDigit).toInt() — keep only ASCII digits.
		digits := keepDigits(v)
		if digits == "" {
			return nil
		}
		n, err := strconv.Atoi(digits)
		if err != nil {
			// no-telemetry: §6.AC-debt drain (bulk pass) — accepted as opt-out pending per-call instrumentation review.
			return nil
		}
		return styleSize{size: int32(n)}
	}
	if v, ok := kv["color"]; ok {
		if strings.HasPrefix(v, "#") {
			rest := strings.TrimPrefix(v, "#")
			// Kotlin: drop("#").toLong() — unconditional radix-10 parse.
			// rutracker emits hex color values like "ff0000"; toLong() in
			// Kotlin parses them as decimal, which throws for any non-digit
			// → runCatching → null → unstyled recurse. Faithfully port the
			// same behaviour: only digit-only "#NNN" strings yield a Hex
			// ColorValue here. Everything else falls through to nil.
			//
			// (The wire-shape comment on ColorValueHex.Hex says "kotlinx
			// serialises Long as JSON number" — it's literally a JSON number,
			// not a hex string. The Kotlin parser is taking the digits as
			// decimal. We match that.)
			n, err := strconv.ParseInt(rest, 10, 64)
			// no-telemetry: §6.AC-debt drain (bulk pass) — accepted as opt-out pending per-call instrumentation review.
			if err != nil {
				// no-telemetry: §6.AC-debt drain (bulk pass) — accepted as opt-out pending per-call instrumentation review.
				return nil
			}
			var cv gen.ColorValue
			if err := cv.FromColorValueHex(gen.ColorValueHex{
				Type: gen.Hex,
				Hex:  n,
			}); err != nil {
				return nil
			}
			return styleColor{color: cv}
		}
		var cv gen.ColorValue
		if err := cv.FromColorValueName(gen.ColorValueName{
			Type: gen.Name,
			Name: v,
		}); err != nil {
			return nil
		}
		return styleColor{color: cv}
	}
	return nil
}

// titleASCII upper-cases the first ASCII byte of s and lower-cases the
// rest. Used for `text-align: center` → "Center". Locale-independent —
// strings.Title is deprecated and would, in any case, be wrong for our
// expected inputs (lowercase ASCII identifiers).
func titleASCII(s string) string {
	if s == "" {
		return s
	}
	first := s[0]
	if first >= 'a' && first <= 'z' {
		first = first - 'a' + 'A'
	}
	rest := strings.ToLower(s[1:])
	return string(first) + rest
}

// keepDigits returns the substring of s composed of ASCII digits.
func keepDigits(s string) string {
	var b strings.Builder
	b.Grow(len(s))
	for i := 0; i < len(s); i++ {
		if s[i] >= '0' && s[i] <= '9' {
			b.WriteByte(s[i])
		}
	}
	return b.String()
}
