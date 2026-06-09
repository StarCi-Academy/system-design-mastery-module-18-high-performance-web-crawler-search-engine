package indexer

import (
	"net/url"
	"strings"

	"github.com/PuerkitoBio/goquery"
)

// ExtractOutlinks parses HTML and returns the page title plus the unique,
// absolute outlinks. Relative hrefs are resolved against the page URL and
// self-links are filtered so a page cannot boost its own rank.
func ExtractOutlinks(pageURL, html string) (string, []string) {
	doc, err := goquery.NewDocumentFromReader(strings.NewReader(html))
	if err != nil {
		return pageURL, nil
	}
	title := strings.TrimSpace(doc.Find("title").First().Text())
	if title == "" {
		title = pageURL
	}
	base, _ := url.Parse(pageURL)
	seen := make(map[string]struct{}) // dedups within the page
	outlinks := make([]string, 0)
	doc.Find("a[href]").Each(func(_ int, s *goquery.Selection) {
		href, ok := s.Attr("href")
		if !ok || href == "" {
			return
		}
		rel, err := url.Parse(href)
		if err != nil {
			return // skip malformed hrefs
		}
		// ResolveReference resolves a relative href against the page URL.
		abs := base.ResolveReference(rel).String()
		if abs == pageURL { // drop self-links
			return
		}
		if _, dup := seen[abs]; dup {
			return
		}
		seen[abs] = struct{}{}
		outlinks = append(outlinks, abs)
	})
	return title, outlinks
}
