#!/usr/bin/env python3
"""
Lava Multi-Provider Extension: Comprehensive Research & Implementation Guide
Generated via ReportLab PDF pipeline
"""
import os
import hashlib
from reportlab.lib.pagesizes import A4
from reportlab.lib.units import inch, cm
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.enums import TA_LEFT, TA_CENTER, TA_JUSTIFY, TA_RIGHT
from reportlab.lib import colors
from reportlab.platypus import (
    Paragraph, Spacer, Table, TableStyle, Image, PageBreak,
    KeepTogether, CondPageBreak, HRFlowable
)
from reportlab.platypus.tableofcontents import TableOfContents
from reportlab.platypus import SimpleDocTemplate
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.pdfbase.pdfmetrics import registerFontFamily

# ━━ Font Registration ━━
pdfmetrics.registerFont(TTFont('LiberationSerif', '/usr/share/fonts/truetype/liberation/LiberationSerif-Regular.ttf'))
pdfmetrics.registerFont(TTFont('TinosBold', '/usr/share/fonts/truetype/liberation/LiberationSerif-Bold.ttf'))
pdfmetrics.registerFont(TTFont('LiberationSans', '/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf'))
pdfmetrics.registerFont(TTFont('CarlitoBold', '/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf'))
pdfmetrics.registerFont(TTFont('DejaVuSans', '/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf'))
pdfmetrics.registerFont(TTFont('NotoSerifSC', '/usr/share/fonts/truetype/noto-serif-sc/NotoSerifSC-Regular.ttf'))
registerFontFamily('LiberationSerif', normal='LiberationSerif', bold='TinosBold')
registerFontFamily('LiberationSans', normal='LiberationSans', bold='CarlitoBold')
registerFontFamily('DejaVuSans', normal='DejaVuSans', bold='DejaVuSans')
registerFontFamily('NotoSerifSC', normal='NotoSerifSC', bold='NotoSerifSC')

# ━━ Color Palette ━━
ACCENT       = colors.HexColor('#217490')
TEXT_PRIMARY  = colors.HexColor('#18191b')
TEXT_MUTED    = colors.HexColor('#858b91')
BG_SURFACE   = colors.HexColor('#e1e5e9')
BG_PAGE      = colors.HexColor('#f0f2f4')
ACCENT_2     = colors.HexColor('#4ece8e')
HEADER_FILL  = colors.HexColor('#716950')
CARD_BG      = colors.HexColor('#f0efee')
TABLE_STRIPE = colors.HexColor('#f1f0ef')
BORDER       = colors.HexColor('#c6c2b5')
SEM_SUCCESS  = colors.HexColor('#43925d')
SEM_WARNING  = colors.HexColor('#ab8c4d')
SEM_ERROR    = colors.HexColor('#9a4c45')
SEM_INFO     = colors.HexColor('#527496')

# ━━ Styles ━━
PAGE_W, PAGE_H = A4
LEFT_M = 1.0 * inch
RIGHT_M = 1.0 * inch
TOP_M = 0.8 * inch
BOT_M = 0.8 * inch
AVAILABLE_W = PAGE_W - LEFT_M - RIGHT_M

body_style = ParagraphStyle(
    name='Body', fontName='LiberationSerif', fontSize=10.5, leading=16,
    alignment=TA_JUSTIFY, spaceAfter=6, spaceBefore=2,
)
body_left = ParagraphStyle(
    name='BodyLeft', fontName='LiberationSerif', fontSize=10.5, leading=16,
    alignment=TA_LEFT, spaceAfter=6, spaceBefore=2,
)
h1_style = ParagraphStyle(
    name='H1', fontName='LiberationSerif', fontSize=20, leading=26,
    textColor=ACCENT, spaceBefore=18, spaceAfter=10,
)
h2_style = ParagraphStyle(
    name='H2', fontName='LiberationSerif', fontSize=16, leading=22,
    textColor=HEADER_FILL, spaceBefore=14, spaceAfter=8,
)
h3_style = ParagraphStyle(
    name='H3', fontName='LiberationSerif', fontSize=13, leading=18,
    textColor=TEXT_PRIMARY, spaceBefore=10, spaceAfter=6,
)
h4_style = ParagraphStyle(
    name='H4', fontName='LiberationSerif', fontSize=11, leading=16,
    textColor=TEXT_MUTED, spaceBefore=8, spaceAfter=4,
)
code_style = ParagraphStyle(
    name='Code', fontName='DejaVuSans', fontSize=8.5, leading=12,
    alignment=TA_LEFT, spaceAfter=4, spaceBefore=2,
    leftIndent=12, backColor=colors.HexColor('#f5f5f5'),
)
caption_style = ParagraphStyle(
    name='Caption', fontName='LiberationSerif', fontSize=9, leading=13,
    alignment=TA_CENTER, textColor=TEXT_MUTED, spaceBefore=3, spaceAfter=6,
)
th_style = ParagraphStyle(
    name='TH', fontName='LiberationSerif', fontSize=10, leading=14,
    textColor=colors.white, alignment=TA_CENTER,
)
td_style = ParagraphStyle(
    name='TD', fontName='LiberationSerif', fontSize=9.5, leading=14,
    textColor=TEXT_PRIMARY, alignment=TA_LEFT,
)
td_center = ParagraphStyle(
    name='TDCenter', fontName='LiberationSerif', fontSize=9.5, leading=14,
    textColor=TEXT_PRIMARY, alignment=TA_CENTER,
)
bullet_style = ParagraphStyle(
    name='Bullet', fontName='LiberationSerif', fontSize=10.5, leading=16,
    alignment=TA_LEFT, spaceAfter=3, leftIndent=18, bulletIndent=6,
)
toc_h1 = ParagraphStyle(name='TOCH1', fontSize=10.5,
    bulletIndent=6,
)

# ━━ TocDocTemplate ━━
class TocDocTemplate(SimpleDocTemplate):
    def afterFlowable(self, flowable):
        if hasattr(flowable, 'bookmark_name'):
            level = getattr(flowable, 'bookmark_level', 0)
            text = getattr(flowable, 'bookmark_text', '')
            key = getattr(flowable, 'bookmark_key', '')
            self.notify('TOCEntry', (level, text, self.page, key))

def add_heading(text, style, level=0):
    key = 'h_%s' % hashlib.md5(text.encode()).hexdigest()[:8]
    p = Paragraph('<a name="%s"/>%s' % (key, text), style)
    p.bookmark_name = text
    p.bookmark_level = level
    p.bookmark_text = text
    p.bookmark_key = key
    return p

def make_table(data, col_widths=None, header_rows=1):
    if col_widths is None:
        col_widths = [AVAILABLE_W / len(data[0])] * len(data[0])
    t = Table(data, colWidths=col_widths, hAlign='CENTER')
    style_cmds = [
        ('BACKGROUND', (0, 0), (-1, header_rows-1), HEADER_FILL),
        ('TEXTCOLOR', (0, 0), (-1, header_rows-1), colors.white),
        ('VALIGN', (0, 0), (-1, -1), 'MIDDLE'),
        ('LEFTPADDING', (0, 0), (-1, -1), 6),
        ('RIGHTPADDING', (0, 0), (-1, -1), 6),
        ('TOPPADDING', (0, 0), (-1, -1), 4),
        ('BOTTOMPADDING', (0, 0), (-1, -1), 4),
        ('GRID', (0, 0), (-1, -1), 0.5, BORDER),
    ]
    for i in range(header_rows, len(data)):
        bg = colors.white if (i - header_rows) % 2 == 0 else TABLE_STRIPE
        style_cmds.append(('BACKGROUND', (0, i), (-1, i), bg))
    t.setStyle(TableStyle(style_cmds))
    return t

def add_image(story, path, caption_text, max_w=AVAILABLE_W * 0.92):
    if os.path.exists(path):
        img = Image(path, width=max_w, height=max_w * 0.65)
        img.hAlign = 'CENTER'
        story.append(Spacer(1, 12))
        story.append(img)
        story.append(Paragraph(caption_text, caption_style))
        story.append(Spacer(1, 12))

# ━━ Build Document ━━
OUTPUT = '/home/z/my-project/download/Lava_Multi_Provider_Extension_Research.pdf'
DIAGRAMS = '/home/z/my-project/download/diagrams/'

doc = TocDocTemplate(
    OUTPUT, pagesize=A4,
    leftMargin=LEFT_M, rightMargin=RIGHT_M,
    topMargin=TOP_M, bottomMargin=BOT_M,
    title='Lava Multi-Provider Extension: Comprehensive Research & Implementation Guide',
    author='Z.ai', creator='Z.ai',
)

story = []

# ━━ TABLE OF CONTENTS ━━
toc = TableOfContents()
toc.levelStyles = [
    ParagraphStyle(name='TOC1', fontName='LiberationSerif', fontSize=13, leading=20, leftIndent=20, spaceBefore=6),
    ParagraphStyle(name='TOC2', fontName='LiberationSerif', fontSize=11, leading=18, leftIndent=40, spaceBefore=3),
    ParagraphStyle(name='TOC3', fontName='LiberationSerif', fontSize=10, leading=16, leftIndent=60, spaceBefore=2),
]
story.append(Paragraph('<b>Table of Contents</b>', h1_style))
story.append(toc)
story.append(PageBreak())

# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# SECTION 1: EXECUTIVE SUMMARY
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
story.append(add_heading('1. Executive Summary', h1_style, 0))

story.append(Paragraph(
    'This document presents a comprehensive research and implementation guide for extending the Lava project to support multiple content providers beyond the original RuTracker integration. The Lava project, currently at version 1.2.0 for the Android client and 2.0.8 for the Go API service, has already undergone significant refactoring through the SP-3a Multi-Tracker SDK Foundation initiative, which established a pluggable, capability-based tracker architecture with RuTracker and RuTor as the first two implementations. This research builds upon that foundation to introduce four additional providers: two torrent-based trackers (NNMClub and Kinozal) and two HTTP-based digital libraries (Internet Archive and Project Gutenberg).',
    body_style))

story.append(Paragraph(
    'Beyond simply adding new providers, this document addresses critical user experience enhancements that must accompany the multi-provider expansion. The most significant of these is the introduction of a comprehensive credentials management system that allows users to create, store, and associate authentication credentials with multiple providers. Users must be able to manage usernames, passwords, tokens, and API keys through a centralized settings interface, and then associate those credentials with specific providers during the login and configuration process. The system must support the notion that a single set of credentials can be shared across multiple providers where applicable, and that anonymous access must remain a first-class option for providers that support it.',
    body_style))

story.append(Paragraph(
    'The search and browsing experience must be fundamentally transformed from a single-tracker paradigm to a unified, multi-provider experience. Search queries must be dispatched to all selected providers in parallel, with results streaming in real-time as each provider returns its findings. The forums and categories sections must similarly present a unified view across all providers that offer browsable content hierarchies. All user selections, filters, and preferences must be persisted so that the experience is consistent across sessions. The Android client user interface must be redesigned to meet modern Material Design 3 standards, providing enterprise-grade quality with proper error handling, loading states, and accessibility features throughout.',
    body_style))

story.append(Paragraph(
    'This guide provides the engineering team with exact codebase references, step-by-step implementation instructions, system architecture diagrams, database schema designs, wireframe specifications, testing plans, and user documentation. Every aspect of the implementation is broken down into fine-grained phases with detailed tasks and sub-tasks, ensuring nothing is missed and the team can proceed with confidence from planning through delivery.',
    body_style))

# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# SECTION 2: CURRENT SYSTEM ANALYSIS
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
story.append(add_heading('2. Current System Architecture Analysis', h1_style, 0))

story.append(add_heading('2.1 Go API Service Architecture', h2_style, 1))

story.append(Paragraph(
    'The Lava Go API service (lava-api-go) is a JSON-REST API built with Go 1.24+, Gin Gonic, quic-go, pgx/v5, and golang-migrate. It serves as a proxy that structures content from upstream tracker websites, applying response caching via PostgreSQL, per-IP rate limiting, and comprehensive audit logging. The service listens on port 8443 with HTTP/3 (QUIC/UDP) as the primary transport and HTTP/2-over-TLS (TCP) as a fallback on the same port. Metrics are exposed on a separate plain-HTTP listener at 127.0.0.1:9091. The service announces itself via mDNS as _lava-api._tcp for local network discovery by the Android client.',
    body_style))

story.append(Paragraph(
    'The current architecture has a single abstraction seam: the ScraperClient interface defined in internal/handlers/handlers.go. This interface declares 15 methods that the handler layer depends on: GetForum, GetCategoryPage, GetSearchPage, GetTopic, GetTopicPage, GetCommentsPage, AddComment, GetTorrent, GetTorrentFile, GetFavorites, AddFavorite, RemoveFavorite, CheckAuthorised, Login, and FetchCaptcha. The concrete *rutracker.Client type satisfies this interface at compile time via a static assertion (var _ ScraperClient = (*rutracker.Client)(nil)). The Deps struct bundles a Cache interface and ScraperClient, and the Register function wires 13 routes onto the Gin router, all routing to a single upstream tracker (RuTracker).',
    body_style))

story.append(Paragraph(
    'The auth layer (internal/auth/passthrough.go) translates the Auth-Token header from the Android client into an upstream cookie. The realm hash (SHA-256 of the Auth-Token) is used for cache keying and audit scoping. The UpstreamCookie function handles the translation: if the token contains an equals sign, it is forwarded verbatim; otherwise, bb_session= is prepended. This design is RuTracker-specific and will need generalization for providers with different cookie schemas.',
    body_style))

story.append(Paragraph(
    'The RuTracker client implementation (internal/rutracker/) follows a consistent pattern: an HTTP client with circuit breaker (5 consecutive failures trips to OPEN for 10s), IPv4-only transport (required due to Cloudflare IPv6 edge silently dropping login POSTs), charset transcoding from Windows-1251 to UTF-8, and http.ErrUseLastResponse to capture the auth token from the 302 login redirect. Each functional area (search, forum, topic, torrent, favorites, comments, captcha, login) is implemented as a separate file with a Parse function that extracts structured data from HTML using goquery selectors.',
    body_style))

add_image(story, os.path.join(DIAGRAMS, 'architecture_overview.png'),
          'Figure 1: Multi-Provider Architecture Overview')

story.append(add_heading('2.2 Android Client Architecture', h2_style, 1))

story.append(Paragraph(
    'The Android client follows a clean multi-module architecture with Hilt for dependency injection, Orbit MVI for state management (ContainerHost<State, SideEffect>), Jetpack Compose for UI, Kotlin Serialization for models, and a custom NavigationController for navigation. The module hierarchy follows core/ to feature/ to app/ layering, with a clear separation of concerns between the tracker SDK, domain logic, and presentation layers.',
    body_style))

story.append(Paragraph(
    'The tracker SDK core resides in core/tracker/ with seven submodules: api (interfaces and models), registry (tracker discovery), mirror (mirror management), client (LavaTrackerSdk facade), rutracker (RuTracker implementation), rutor (RuTor implementation), and testing (FakeTrackerClient and builders). The root interface is TrackerClient, which declares a descriptor property, a healthCheck method, and a getFeature method that returns nullable feature implementations based on the tracker declared capabilities. Seven feature interfaces extend the TrackerFeature marker: SearchableTracker, BrowsableTracker, AuthenticatableTracker, TopicTracker, CommentsTracker, FavoritesTracker, and DownloadableTracker.',
    body_style))

story.append(Paragraph(
    'The TrackerDescriptor interface provides metadata about each tracker: trackerId (unique string identifier), displayName, baseUrls (primary plus mirrors), capabilities (set of TrackerCapability enum values), authType (AuthType enum), encoding (character set), and expectedHealthMarker (substring for health probes). The TrackerCapability enum defines 13 values: SEARCH, BROWSE, FORUM, TOPIC, COMMENTS, FAVORITES, TORRENT_DOWNLOAD, MAGNET_LINK, AUTH_REQUIRED, CAPTCHA_LOGIN, RSS, UPLOAD, and USER_PROFILE. The AuthType enum defines five values: NONE, FORM_LOGIN, CAPTCHA_LOGIN, OAUTH, and API_KEY.',
    body_style))

story.append(Paragraph(
    'The LavaTrackerSdk is the @Singleton facade that feature ViewModels interact with. It provides methods for listing available trackers, switching the active tracker, and performing all tracker operations (search, browse, get topic, download, get comments, manage favorites, login, etc.). The SDK implements cross-tracker fallback: when all mirrors of the active tracker are UNHEALTHY, it emits a CrossTrackerFallbackProposed outcome with an alternative tracker suggestion. The UI presents a modal allowing the user to accept or dismiss the fallback proposal. No silent fallback occurs, per design decision 7a from the SP-3a specification.',
    body_style))

add_image(story, os.path.join(DIAGRAMS, 'provider_interface_hierarchy.png'),
          'Figure 2: Provider Interface Hierarchy and Feature Implementation Matrix')

story.append(add_heading('2.3 Existing Provider Implementations', h2_style, 1))

story.append(add_heading('2.3.1 RuTracker', h3_style, 2))
story.append(Paragraph(
    'RuTracker (rutracker.org) is the original and most feature-complete provider. The RuTrackerDescriptor declares trackerId "rutracker", 10 capabilities (SEARCH, BROWSE, FORUM, TOPIC, COMMENTS, FAVORITES, TORRENT_DOWNLOAD, MAGNET_LINK, AUTH_REQUIRED, CAPTCHA_LOGIN), authType CAPTCHA_LOGIN, and Windows-1251 encoding. The RuTrackerClient implements all seven feature interfaces, delegating to 14+ use cases (LoginUseCase, GetSearchPageUseCase, GetForumUseCase, GetCategoryPageUseCase, GetTopicUseCase, GetTopicPageUseCase, GetCommentsPageUseCase, AddCommentUseCase, GetFavoritesUseCase, AddFavoriteUseCase, RemoveFavoriteUseCase, GetTorrentFileUseCase, GetMagnetLinkUseCase, CheckAuthorisedUseCase, LogoutUseCase, VerifyTokenUseCase). The implementation wraps a legacy NetworkApi interface with mappers that translate between the old DTO models and the new tracker SDK models. Authentication uses a PHPBB-style cookie session (bb_session cookie) obtained via POST to /forum/login.php, with captcha support via cap_sid, cap_code, and cap_val parameters.',
    body_style))

story.append(add_heading('2.3.2 RuTor', h3_style, 2))
story.append(Paragraph(
    'RuTor (rutor.info) is the second provider, added during the SP-3a initiative. The RuTorDescriptor declares trackerId "rutor", 8 capabilities (SEARCH, BROWSE, TOPIC, COMMENTS, TORRENT_DOWNLOAD, MAGNET_LINK, RSS, AUTH_REQUIRED), authType FORM_LOGIN, and UTF-8 encoding. Notably, RuTor lacks FORUM and FAVORITES capabilities, and the CommentsTracker.addComment method throws an exception because anonymous posting is unsupported. The RuTorHttpClient uses an InMemoryCookieJar, a Semaphore limiting concurrent requests to 4, and a CircuitBreaker (3 failures in 30s trips to OPEN for 30s then HALF_OPEN). Search uses the URL pattern {baseUrl}/search/{page}/0/000/0/{query}, browse uses {baseUrl}/browse/{page}/{category}/000/0, and authentication posts to /login.php with nick and password fields, storing the userid cookie for session management. Topic detail requires a two-fetch dance: {baseUrl}/torrent/{id} for the main content and {baseUrl}/descriptions/{id}.files for the AJAX-loaded file list.',
    body_style))

# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# SECTION 3: NEW PROVIDER RESEARCH
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
story.append(add_heading('3. New Provider Research', h1_style, 0))

story.append(add_heading('3.1 NNMClub (nnmclub.to)', h2_style, 1))

story.append(Paragraph(
    'NNMClub (NoNaMe Club) is a public Russian torrent tracker operating on a phpBB-based forum platform with integrated tracker functionality. The site serves as a comprehensive resource for Russian-language content across movies, television series, anime, books, music, software, and games, with over 500 subcategories organized hierarchically. The platform uses Windows-1251 character encoding, which is a critical implementation detail requiring charset transcoding identical to the RuTracker integration pattern already established in the codebase.',
    body_style))

story.append(Paragraph(
    'Authentication on NNMClub follows the standard phpBB cookie-based session model. Login is performed via POST to the forum login endpoint (ucp.php?mode=login), with username and password fields. The session is maintained through standard phpBB session cookies (phpbb3_* or sid). No captcha is present on the login form, simplifying the authentication flow compared to RuTracker. Authentication is not required for search, browsing, or accessing magnet links, but is required for downloading .torrent files via the download.php endpoint. This means the provider can operate in both authenticated and anonymous modes, with anonymous users still able to search and browse but unable to download torrent files directly.',
    body_style))

story.append(Paragraph(
    'Search functionality uses POST to https://nnmclub.to/forum/tracker.php with form-encoded parameters: nm (search keywords), f[] (category ID, -1 for all), o (sort field: 1=created, 10=seeders, 7=size, 2=title), s (sort order: 2=desc, 1=asc), tm (time filter), sds (seed filter: 4=only freeleech), and pagination via the start parameter (0, 50, 100, ...). Results are parsed from table.forumline.tablesorter rows, extracting title from a[href^="viewtopic.php?t="] elements, seeders from td.seedmed, leechers from td.leechmed, and size and date from specific td elements. The detail page (viewtopic.php?t={ID}) provides magnet links from a[href^="magnet:"], torrent download links from a[href^="download.php?id="], and metadata such as production details, director, cast, and description from span elements. File listings are available via a separate request to filelst.php?attach_id={ID}.',
    body_style))

# Capability mapping table for NNMClub
nnm_data = [
    [Paragraph('<b>Capability</b>', th_style), Paragraph('<b>Supported</b>', th_style), Paragraph('<b>Implementation Notes</b>', th_style)],
    [Paragraph('SEARCH', td_style), Paragraph('Yes', td_center), Paragraph('POST to /forum/tracker.php with nm parameter', td_style)],
    [Paragraph('BROWSE', td_style), Paragraph('Yes', td_center), Paragraph('Category-based browsing via f[] parameter', td_style)],
    [Paragraph('FORUM', td_style), Paragraph('Yes', td_center), Paragraph('500+ subcategories in hierarchical structure', td_style)],
    [Paragraph('TOPIC', td_style), Paragraph('Yes', td_center), Paragraph('viewtopic.php?t={ID} with magnet and metadata', td_style)],
    [Paragraph('COMMENTS', td_style), Paragraph('Partial', td_center), Paragraph('phpBB comments available; add comment requires auth', td_style)],
    [Paragraph('FAVORITES', td_style), Paragraph('Partial', td_center), Paragraph('phpBB bookmarks available with auth', td_style)],
    [Paragraph('TORRENT_DOWNLOAD', td_style), Paragraph('Yes', td_center), Paragraph('download.php?id={ATTACH_ID} requires auth', td_style)],
    [Paragraph('MAGNET_LINK', td_style), Paragraph('Yes', td_center), Paragraph('Available on detail page, no auth needed', td_style)],
    [Paragraph('AUTH_REQUIRED', td_style), Paragraph('Partial', td_center), Paragraph('Required for .torrent download only', td_style)],
    [Paragraph('CAPTCHA_LOGIN', td_style), Paragraph('No', td_center), Paragraph('No captcha on login form', td_style)],
    [Paragraph('RSS', td_style), Paragraph('Yes', td_center), Paragraph('/forum/rss.php?f={CATEGORY_ID}', td_style)],
]
story.append(Spacer(1, 12))
story.append(make_table(nnm_data, [AVAILABLE_W*0.22, AVAILABLE_W*0.15, AVAILABLE_W*0.63]))
story.append(Paragraph('Table 1: NNMClub Capability Mapping', caption_style))

story.append(add_heading('3.2 Kinozal (kinozal.tv)', h2_style, 1))

story.append(Paragraph(
    'Kinozal is a semi-private Russian torrent tracker focused primarily on video content (movies, TV series, anime, and music videos). Registration is required for downloading torrent files, but browsing and searching are available to unauthenticated users. The platform uses Windows-1251 encoding and imposes a daily download limit for free accounts, with freeleech indicators (gold for 0x download, silver for 0.5x download) marked by specific CSS classes (a.r1 for gold, a.r2 for silver). Mirror domains include kinozal.me and kinozal.guru, which must be configured as fallback URLs in the mirror management system.',
    body_style))

story.append(Paragraph(
    'Authentication is straightforward: POST to https://kinozal.tv/takelogin.php with username and password fields. No CSRF token has been observed in reference implementations. On success, the response sets uid and pass cookies (e.g., uid=20631917; pass=KOJ4DJf1VS) that must be forwarded with subsequent requests. Authentication status can be verified by fetching my.php and checking for a[href*="logout.php?hash4u="]. On login failure, the error message is contained within div.bx1:has(div.red). No captcha is present on the standard login form, though the site may employ rate-limiting or temporary blocks on repeated failed attempts.',
    body_style))

story.append(Paragraph(
    'Search uses GET to https://kinozal.tv/browse.php with parameters: s (query), c (category, 0=all), g (search type: 0=title, 1=person, 2=genres, 3=regexp), w (time filter), t (sort: 0=created, 1=seeders, 3=size), f (order: 0=desc, 1=asc), and page (0-based, 50 results per page). Results are parsed from table.t_peer rows, extracting title from td.nam a[href^="/details.php?"], seeders from .sl_s, leechers from .sl_p, and size from td:nth-child(4) in Russian unit format. The detail page (details.php?id={ID}) provides rich metadata including original title, year, poster image, IMDb and Kinopoisk links, and extended information available via a separate AJAX request to get_srv_details.php?id={ID}&pagesd=2. Torrent files are downloaded from https://dl.kinozal.tv/download.php?id={TORRENT_ID} using the uid and pass authentication cookies.',
    body_style))

# Capability mapping for Kinozal
kinozal_data = [
    [Paragraph('<b>Capability</b>', th_style), Paragraph('<b>Supported</b>', th_style), Paragraph('<b>Implementation Notes</b>', th_style)],
    [Paragraph('SEARCH', td_style), Paragraph('Yes', td_center), Paragraph('GET /browse.php with s, c, g, t, f parameters', td_style)],
    [Paragraph('BROWSE', td_style), Paragraph('Yes', td_center), Paragraph('Category browsing via c parameter', td_style)],
    [Paragraph('FORUM', td_style), Paragraph('Partial', td_center), Paragraph('Categories exist but no tree structure; flat list', td_style)],
    [Paragraph('TOPIC', td_style), Paragraph('Yes', td_center), Paragraph('details.php?id={ID} with metadata and poster', td_style)],
    [Paragraph('COMMENTS', td_style), Paragraph('Yes', td_center), Paragraph('Available on detail page', td_style)],
    [Paragraph('FAVORITES', td_style), Paragraph('No', td_center), Paragraph('Not documented in public interfaces', td_style)],
    [Paragraph('TORRENT_DOWNLOAD', td_style), Paragraph('Yes', td_center), Paragraph('dl.kinozal.tv/download.php?id={ID} requires auth', td_style)],
    [Paragraph('MAGNET_LINK', td_style), Paragraph('Partial', td_center), Paragraph('Construct from info hash + tracker announce URLs', td_style)],
    [Paragraph('AUTH_REQUIRED', td_style), Paragraph('Yes', td_center), Paragraph('Required for torrent download', td_style)],
    [Paragraph('CAPTCHA_LOGIN', td_style), Paragraph('No', td_center), Paragraph('No captcha on standard login', td_style)],
    [Paragraph('RSS', td_style), Paragraph('No', td_center), Paragraph('No RSS feed available', td_style)],
]
story.append(Spacer(1, 12))
story.append(make_table(kinozal_data, [AVAILABLE_W*0.22, AVAILABLE_W*0.15, AVAILABLE_W*0.63]))
story.append(Paragraph('Table 2: Kinozal Capability Mapping', caption_style))

story.append(add_heading('3.3 Internet Archive (archive.org)', h2_style, 1))

story.append(Paragraph(
    'The Internet Archive is a public digital library providing free access to millions of books, movies, music, software, and archived web pages. Unlike the torrent trackers in the Lava ecosystem, Archive.org provides content primarily through HTTP downloads rather than BitTorrent. The platform exposes a comprehensive set of REST APIs that make programmatic access straightforward and well-documented. No authentication is required for read and search operations, though registration and IA-S3 API keys are required for write and upload operations (which are out of scope for the Lava integration).',
    body_style))

story.append(Paragraph(
    'The primary search interface is the Advanced Search API at https://archive.org/advancedsearch.php, which accepts Lucene-like query syntax. Parameters include q (query), fl[] (fields to return such as identifier, title, description, mediatype), sort[] (sort fields), rows (results per page), page (page number), and output (format: json, xml, csv). The API supports field-specific queries like mediatype:movies, collection:nasa, title:"war and peace", language:english, and date ranges like date:[2020-01-01 TO 2024-12-31]. The Scrape API at https://archive.org/services/search/v1/scrape provides cursor-based pagination without the 10,000 result limit of the Advanced Search API, making it suitable for large-scale data retrieval. Item metadata is available at https://archive.org/metadata/{IDENTIFIER} as a complete JSON document, with partial reads supported via path suffixes like /metadata/title for specific fields.',
    body_style))

story.append(Paragraph(
    'Downloads are served via HTTP at https://archive.org/download/{IDENTIFIER}/{FILENAME}, with many items also offering .torrent files for BitTorrent downloads. The download mechanism is purely HTTP-based with no authentication required for public items. Rate limiting policies require honoring 429 Too Many Requests responses and Retry-After headers, with a recommended concurrency limit of approximately 4 simultaneous requests. A descriptive User-Agent header is mandatory and should include the application name and version. The item structure includes an identifier (unique ID used in URLs), metadata (title, description, creator, date, mediatype, collection, language, subject), and files (array of file objects with name, size, format, and checksums). Collections group items into browsable hierarchies, with common collections including opensource, nasa, 78rpm, audio_books, and movies.',
    body_style))

# Capability mapping for Archive.org
archive_data = [
    [Paragraph('<b>Capability</b>', th_style), Paragraph('<b>Supported</b>', th_style), Paragraph('<b>Implementation Notes</b>', th_style)],
    [Paragraph('SEARCH', td_style), Paragraph('Yes', td_center), Paragraph('Advanced Search API + Scrape API with Lucene queries', td_style)],
    [Paragraph('BROWSE', td_style), Paragraph('Yes', td_center), Paragraph('Collection-based browsing hierarchy', td_style)],
    [Paragraph('FORUM', td_style), Paragraph('Yes (Collections)', td_center), Paragraph('Collections serve as browsable categories', td_style)],
    [Paragraph('TOPIC', td_style), Paragraph('Yes (Item)', td_center), Paragraph('Item metadata API provides full details', td_style)],
    [Paragraph('COMMENTS', td_style), Paragraph('Partial', td_center), Paragraph('Reviews available on some items', td_style)],
    [Paragraph('FAVORITES', td_style), Paragraph('No', td_center), Paragraph('No native favorites; implement client-side', td_style)],
    [Paragraph('TORRENT_DOWNLOAD', td_style), Paragraph('Partial', td_center), Paragraph('HTTP download primary; some items have .torrent', td_style)],
    [Paragraph('MAGNET_LINK', td_style), Paragraph('No', td_center), Paragraph('Not applicable for HTTP-based provider', td_style)],
    [Paragraph('AUTH_REQUIRED', td_style), Paragraph('No', td_center), Paragraph('Read/search operations are public', td_style)],
    [Paragraph('CAPTCHA_LOGIN', td_style), Paragraph('No', td_center), Paragraph('No authentication required for read access', td_style)],
    [Paragraph('RSS', td_style), Paragraph('No', td_center), Paragraph('No RSS; use Changes API for updates', td_style)],
]
story.append(Spacer(1, 12))
story.append(make_table(archive_data, [AVAILABLE_W*0.22, AVAILABLE_W*0.15, AVAILABLE_W*0.63]))
story.append(Paragraph('Table 3: Internet Archive Capability Mapping', caption_style))

story.append(add_heading('3.4 Project Gutenberg (gutenberg.org)', h2_style, 1))

story.append(Paragraph(
    'Project Gutenberg is a public domain eBook library hosting over 75,000 books available for free download in multiple formats. Like the Internet Archive, Gutenberg provides content through HTTP downloads rather than BitTorrent. The platform has strict anti-bot policies: automated scraping of the website will result in IP bans. Therefore, the integration must exclusively use the official programmatic access methods: the OPDS feed, the machine-readable RDF/XML catalog, and the third-party Gutenberg API via RapidAPI. Direct web scraping of gutenberg.org search or browse pages is strictly forbidden.',
    body_style))

story.append(Paragraph(
    'The OPDS (Open Publication Distribution System) feed at https://www.gutenberg.org/ebooks/search.opds/ provides a machine-readable Atom-based XML catalog optimized for mobile and reader app integration. The complete metadata catalog is available as a daily-updated RDF/XML file at https://www.gutenberg.org/cache/epub/feeds/pg_catalog.rdf.zip, which should be downloaded and processed locally to build a searchable database. The Harvest/Robot endpoint at https://www.gutenberg.org/robot/harvest supports filtered bulk downloads with filetypes[] and langs[] parameters, but requires a mandatory 2-second minimum delay between requests (enforced via wget -w 2). For live search queries, the third-party Gutenberg API at project-gutenberg-books-api.p.rapidapi.com provides REST endpoints for searching books, retrieving book text, searching authors, and listing bookshelves, though it requires a RapidAPI key.',
    body_style))

story.append(Paragraph(
    'Direct download URLs follow predictable patterns: plain text at /files/{ID}/{ID}-0.txt, EPUB with images at /ebooks/{ID}.epub.images, EPUB without images at /ebooks/{ID}.epub.noimages, Kindle at /ebooks/{ID}.kindle.images, HTML at /files/{ID}/{ID}-h/{ID}-h.htm, and cover images at /cache/epub/{ID}/pg{ID}.cover.medium.jpg. The bookshelf system provides categorization similar to forum categories on tracker sites, with examples including "Best Books Ever Listings", "Children\'s Fiction", and "Science Fiction". The recommended integration approach is to download the RDF catalog locally, build a SQLite or Room database, and serve search and browse operations from the local database, using the Harvest endpoint for on-demand content downloads with appropriate rate limiting.',
    body_style))

# Capability mapping for Gutenberg
guten_data = [
    [Paragraph('<b>Capability</b>', th_style), Paragraph('<b>Supported</b>', th_style), Paragraph('<b>Implementation Notes</b>', th_style)],
    [Paragraph('SEARCH', td_style), Paragraph('Yes', td_center), Paragraph('Local RDF catalog search or RapidAPI', td_style)],
    [Paragraph('BROWSE', td_style), Paragraph('Yes', td_center), Paragraph('Bookshelf-based category hierarchy', td_style)],
    [Paragraph('FORUM', td_style), Paragraph('Yes (Bookshelves)', td_center), Paragraph('Bookshelves serve as browsable categories', td_style)],
    [Paragraph('TOPIC', td_style), Paragraph('Yes (Book)', td_center), Paragraph('Full book metadata + multiple download formats', td_style)],
    [Paragraph('COMMENTS', td_style), Paragraph('No', td_center), Paragraph('No comment system', td_style)],
    [Paragraph('FAVORITES', td_style), Paragraph('No', td_center), Paragraph('No native favorites; implement client-side', td_style)],
    [Paragraph('TORRENT_DOWNLOAD', td_style), Paragraph('No', td_center), Paragraph('HTTP download only (EPUB, Kindle, TXT, HTML)', td_style)],
    [Paragraph('MAGNET_LINK', td_style), Paragraph('No', td_center), Paragraph('Not applicable', td_style)],
    [Paragraph('AUTH_REQUIRED', td_style), Paragraph('No', td_center), Paragraph('All content is public domain, no auth needed', td_style)],
    [Paragraph('CAPTCHA_LOGIN', td_style), Paragraph('No', td_center), Paragraph('No authentication required', td_style)],
    [Paragraph('RSS', td_style), Paragraph('Partial', td_center), Paragraph('OPDS feed available for catalog discovery', td_style)],
]
story.append(Spacer(1, 12))
story.append(make_table(guten_data, [AVAILABLE_W*0.22, AVAILABLE_W*0.15, AVAILABLE_W*0.63]))
story.append(Paragraph('Table 4: Project Gutenberg Capability Mapping', caption_style))

# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# SECTION 4: SYSTEM ARCHITECTURE DESIGN
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
story.append(add_heading('4. System Architecture Design', h1_style, 0))

story.append(add_heading('4.1 Go API Service Redesign', h2_style, 1))

story.append(Paragraph(
    'The current Go API service uses a single ScraperClient interface bound to RuTracker. The redesign must introduce a provider-aware routing layer that supports multiple concurrent upstream providers. The fundamental architectural change is the introduction of a Provider interface that generalizes beyond the RuTracker-specific ScraperClient. Each provider implements this interface, and the routing layer dispatches requests to the appropriate provider based on a URL parameter or request header.',
    body_style))

story.append(add_heading('4.1.1 Provider Interface Definition', h3_style, 2))

story.append(Paragraph(
    'The new Provider interface must be defined in a new package internal/provider/provider.go. It generalizes the ScraperClient interface by removing RuTracker-specific types and replacing them with provider-agnostic equivalents. The key design principle is that the Provider interface must support both torrent-based trackers (which require cookie-based authentication and HTML scraping) and HTTP-based digital libraries (which use REST APIs and token-based or no authentication). The interface must declare methods for all capabilities that any provider might support, with methods that a specific provider does not support returning an ErrUnsupported error.',
    body_style))

story.append(Paragraph(
    'The proposed Provider interface includes the following methods: ID() string, DisplayName() string, Capabilities() Set[ProviderCapability], AuthType() AuthType, Encoding() string, Search(ctx, opts SearchOpts, cred Credentials) (*SearchResult, error), Browse(ctx, categoryID string, page int, cred Credentials) (*BrowseResult, error), GetForumTree(ctx, cred Credentials) (*ForumTree, error), GetTopic(ctx, id string, page int, cred Credentials) (*TopicResult, error), GetComments(ctx, id string, page int, cred Credentials) (*CommentsResult, error), AddComment(ctx, id, message string, cred Credentials) (bool, error), GetTorrent(ctx, id string, cred Credentials) (*TorrentResult, error), DownloadFile(ctx, id string, cred Credentials) (*FileDownload, error), GetFavorites(ctx, cred Credentials) (*FavoritesResult, error), AddFavorite(ctx, id string, cred Credentials) (bool, error), RemoveFavorite(ctx, id string, cred Credentials) (bool, error), CheckAuth(ctx, cred Credentials) (bool, error), Login(ctx, opts LoginOpts) (*LoginResult, error), FetchCaptcha(ctx, path string) (*CaptchaImage, error), HealthCheck(ctx) (bool, error). The Credentials struct carries the authentication material: Type (None, Cookie, Token, APIKey), CookieValue (for tracker sessions), Token (for API-based providers), Username/Password (for form-login providers).',
    body_style))

story.append(add_heading('4.1.2 Route Structure Redesign', h3_style, 2))

story.append(Paragraph(
    'The current route structure uses flat paths like /forum, /search, /topic/{id}, etc., all implicitly bound to RuTracker. The redesigned route structure introduces a {provider} path segment that enables multi-provider dispatch. Two approaches are viable: (A) Prefix-based routing where each provider gets its own route prefix, e.g., /v1/{provider}/forum, /v1/{provider}/search, /v1/{provider}/topic/{id}; or (B) Header-based provider selection where the client sends an X-Provider header and the same /forum, /search routes dispatch based on the header value. Approach A is recommended because it is RESTful, cacheable, and explicit. The /v1 prefix provides a clean versioning boundary, and the {provider} segment enables per-provider cache namespacing without additional key computation.',
    body_style))

add_image(story, os.path.join(DIAGRAMS, 'api_routes_structure.png'),
          'Figure 3: Go API Route Structure - Current vs. Proposed')

story.append(add_heading('4.1.3 Provider Registry and Dispatch', h3_style, 2))

story.append(Paragraph(
    'A new ProviderRegistry must be implemented in internal/provider/registry.go. This registry maps provider IDs (strings like "rutracker", "rutor", "nnmclub", "kinozal", "archiveorg", "gutenberg") to their corresponding Provider implementations. The registry is populated during application startup in cmd/lava-api-go/main.go, where each provider client is instantiated with its configuration (base URL, timeout, breaker settings) and registered. The dispatch middleware in internal/middleware/provider.go extracts the {provider} path parameter, looks up the provider in the registry, and injects it into the Gin context. If the provider is not found, a 404 response is returned with a list of available provider IDs. The handler layer then retrieves the provider from the context and calls the appropriate method, passing the credentials extracted from the Auth-Token header via the generalized auth middleware.',
    body_style))

story.append(add_heading('4.1.4 Auth Middleware Generalization', h3_style, 2))

story.append(Paragraph(
    'The current auth middleware (internal/auth/passthrough.go) is RuTracker-specific, prepending bb_session= to bare tokens. The generalized auth middleware must support multiple credential types. The Auth-Token header format is extended to: "provider_id:credential_type:credential_value". For example, "rutracker:cookie:bb_session=abc123", "archiveorg:apikey:IA-S3-Access-Key=xyz", or "gutenberg:none:". The middleware parses this header, extracts the credential type and value, and constructs a Credentials struct that is passed to the provider methods. For backwards compatibility, bare tokens (without colons) are treated as RuTracker cookie credentials, preserving the existing behavior for clients that have not yet migrated to the new format.',
    body_style))

story.append(add_heading('4.1.5 Database Schema Extensions', h3_style, 2))

story.append(Paragraph(
    'Four new database tables are required to support credentials management and provider configuration. The provider_credentials table stores encrypted credential records with columns: id (UUID PK), user_realm_hash (TEXT, references the auth realm), provider_id (TEXT, the tracker ID), cred_type (TEXT: "cookie", "token", "apikey", "password"), cred_label (TEXT, user-assigned name), cred_value_encrypted (BYTEA, AES-256-GCM encrypted), created_at (TIMESTAMPTZ), updated_at (TIMESTAMPTZ), and last_used_at (TIMESTAMPTZ). The provider_configs table stores per-user provider settings: id (UUID PK), user_realm_hash (TEXT), provider_id (TEXT), enabled (BOOLEAN DEFAULT true), anonymous_mode (BOOLEAN DEFAULT false), credentials_id (UUID FK to provider_credentials), display_order (INTEGER), custom_mirror_url (TEXT), and request_delay_ms (INTEGER). The search_provider_selections table persists which providers are selected for unified search: id (UUID PK), user_realm_hash (TEXT), provider_id (TEXT), selected (BOOLEAN DEFAULT true). The forum_provider_selections table persists which providers appear in the unified forums view: id (UUID PK), user_realm_hash (TEXT), provider_id (TEXT), enabled (BOOLEAN DEFAULT true).',
    body_style))

add_image(story, os.path.join(DIAGRAMS, 'database_schema.png'),
          'Figure 4: Database Schema Extensions for Multi-Provider Support')

story.append(add_heading('4.2 Android Client Redesign', h2_style, 1))

story.append(add_heading('4.2.1 New Core Modules', h3_style, 2))

story.append(Paragraph(
    'Four new core modules must be added to the Android client. The first is core/tracker/nnmclub/, implementing the NNMClub provider with NNMClubDescriptor (trackerId "nnmclub", capabilities SEARCH/BROWSE/FORUM/TOPIC/COMMENTS/FAVORITES/TORRENT_DOWNLOAD/MAGNET_LINK/AUTH_REQUIRED/RSS, authType FORM_LOGIN, encoding Windows-1251) and NNMClubClient with feature implementations for SearchableTracker, BrowsableTracker, AuthenticatableTracker, TopicTracker, CommentsTracker, FavoritesTracker, and DownloadableTracker. The second new module is core/tracker/kinozal/, implementing the Kinozal provider with KinozalDescriptor (trackerId "kinozal", capabilities SEARCH/BROWSE/TOPIC/COMMENTS/TORRENT_DOWNLOAD/AUTH_REQUIRED, authType FORM_LOGIN, encoding Windows-1251). The third is core/tracker/archiveorg/, implementing the Internet Archive provider with ArchiveOrgDescriptor (trackerId "archiveorg", capabilities SEARCH/BROWSE/FORUM/TOPIC/COMMENTS/TORRENT_DOWNLOAD, authType NONE, encoding UTF-8). The fourth is core/tracker/gutenberg/, implementing the Project Gutenberg provider with GutenbergDescriptor (trackerId "gutenberg", capabilities SEARCH/BROWSE/FORUM/TOPIC/TORRENT_DOWNLOAD/RSS, authType NONE, encoding UTF-8).',
    body_style))

story.append(add_heading('4.2.2 Credentials Management Module', h3_style, 2))

story.append(Paragraph(
    'A new core/credentials/ module must be created to handle credential storage and management. This module includes: (1) Credential model data class with fields id (UUID), label (String), type (CredentialType enum: USERNAME_PASSWORD, TOKEN, API_KEY), providerIds (Set<String> of associated providers), username (String?), password (String?), token (String?), apiKey (String?), apiSecret (String?), createdAt (Long), updatedAt (Long). (2) CredentialRepository interface with methods: getAll(), getById(id), getByProvider(providerId), save(credential), delete(id), associateWithProvider(credentialId, providerId), dissociateFromProvider(credentialId, providerId). (3) CredentialRepositoryImpl using Room database with EncryptedSharedPreferences for sensitive values (passwords, tokens, API keys). (4) CredentialDao with Room annotations for CRUD operations. (5) Hilt module (CredentialModule) providing repository bindings. The encryption strategy uses Android Keystore with AES-256-GCM, where each credential value is encrypted with a key stored in the Android Keystore, and the encrypted bytes are stored in Room. This ensures that credential values are never stored in plaintext on the device filesystem.',
    body_style))

story.append(add_heading('4.2.3 LavaTrackerSdk Extensions', h3_style, 2))

story.append(Paragraph(
    'The LavaTrackerSdk facade must be extended with the following new methods: (1) searchAll(request, providerIds): Flow<SearchResultBatch> - dispatches search to multiple providers concurrently and emits results as each provider returns, enabling real-time streaming of search results. (2) browseAll(categoryId, providerIds): Flow<BrowseResultBatch> - similar concurrent dispatch for browsing. (3) getForumTreeAll(providerIds): Map<TrackerDescriptor, ForumTree?> - retrieves forum trees from all selected providers. (4) setCredentialsForProvider(providerId, credentialId) - associates a credential with a provider. (5) getCredentialForProvider(providerId): Credential? - retrieves the credential associated with a provider. (6) setAnonymousMode(providerId, anonymous: Boolean) - configures anonymous access for a provider. (7) getProviderConfig(providerId): ProviderConfig - retrieves the full configuration for a provider. (8) observeProviderConfigs(): Flow<List<ProviderConfig>> - observes all provider configurations reactively. The SDK must also maintain a provider credential mapping that persists across sessions, stored in the CredentialRepository.',
    body_style))

story.append(add_heading('4.2.4 Unified Search Architecture', h3_style, 2))

story.append(Paragraph(
    'The unified search architecture must transform the current single-tracker search flow into a multi-provider concurrent search system. When the user initiates a search, the search query is dispatched to all selected providers simultaneously using Kotlin coroutines (async/awaitAll pattern). Each provider search runs in its own coroutine with an independent timeout (configurable per provider, defaulting to 10 seconds). Results are emitted as they arrive using a SharedFlow, enabling the UI to update in real-time as each provider returns its findings. The SearchResultBatch data class contains: providerId, results (List<TorrentItem>), error (Throwable?), timestamp (Long), and isComplete (Boolean). The UI layer observes the SharedFlow and updates the results list incrementally, inserting new results into the appropriate position based on the current sort order. Each result item displays a provider badge (colored dot or icon) indicating its source, enabling users to visually identify which provider returned each result. If a provider fails or times out, a partial error indicator is shown for that provider without blocking results from other providers.',
    body_style))

add_image(story, os.path.join(DIAGRAMS, 'unified_search_flow.png'),
          'Figure 5: Unified Search Flow Architecture')

# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# SECTION 5: PROVIDER IMPLEMENTATION GUIDES
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
story.append(add_heading('5. Provider Implementation Guides', h1_style, 0))

story.append(add_heading('5.1 NNMClub Implementation', h2_style, 1))

story.append(add_heading('5.1.1 Go API: internal/nnmclub/', h3_style, 2))

story.append(Paragraph(
    'The NNMClub Go implementation follows the same structural pattern as the RuTracker implementation. Create a new package internal/nnmclub/ with the following files: client.go (HTTP client with circuit breaker, Windows-1251 decoding, and phpBB session management), search.go (search page parser for POST /forum/tracker.php), forum.go (forum tree and category page parsers), topic.go (topic detail page parser), torrent.go (torrent metadata and download handlers), login.go (phpBB authentication flow), favorites.go (bookmark management), comments.go (comment page parser), and captcha.go (not needed for NNMClub but stub for interface compliance). The client must use the same charset transcoding approach as RuTracker (readBodyDecoded with golang.org/x/net/html/charset), and the circuit breaker should be configured with name "nnmclub", MaxFailures 5, and ResetTimeout 10s. The NNMClub base URL must be configurable via the LAVA_API_NNMCLUB_URL environment variable, defaulting to https://nnmclub.to/forum.',
    body_style))

story.append(add_heading('5.1.2 Android: core/tracker/nnmclub/', h3_style, 2))

story.append(Paragraph(
    'The NNMClub Android module must be created at core/tracker/nnmclub/ with the KotlinTrackerModuleConventionPlugin pre-wiring dependencies (Jsoup, OkHttp, kotlinx-serialization, etc.). The module contains: NNMClubDescriptor object declaring trackerId "nnmclub", displayName "NNMClub.to", baseUrls [https://nnmclub.to (primary), nnm-club.name, nnm-club.me], capabilities SEARCH/BROWSE/FORUM/TOPIC/COMMENTS/FAVORITES/TORRENT_DOWNLOAD/MAGNET_LINK/AUTH_REQUIRED/RSS, authType FORM_LOGIN, encoding "Windows-1251", and expectedHealthMarker "NNM-Club". NNMClubClient class implementing TrackerClient with all seven feature interfaces. NNMClubClientFactory for Hilt registration. Feature implementations: NNMClubSearch (POST to /forum/tracker.php), NNMClubBrowse (category-based), NNMClubAuth (phpBB login to ucp.php?mode=login), NNMClubTopic (viewtopic.php?t={ID}), NNMClubComments (phpBB comments), NNMClubFavorites (phpBB bookmarks), NNMClubDownload (download.php?id={ID}). Parsers: NNMClubSearchParser, NNMClubForumParser, NNMClubTopicParser, NNMClubCommentsParser, NNMClubSizeParser, NNMClubDateParser - all Jsoup-based. Test fixtures in src/test/resources/fixtures/nnmclub/ with HTML snapshots of search, forum, topic, and login pages.',
    body_style))

story.append(add_heading('5.2 Kinozal Implementation', h2_style, 1))

story.append(add_heading('5.2.1 Go API: internal/kinozal/', h3_style, 2))

story.append(Paragraph(
    'The Kinozal Go implementation follows the same pattern. Create internal/kinozal/ with client.go (HTTP client with circuit breaker named "kinozal", Windows-1251 decoding, uid/pass cookie management), search.go (GET /browse.php parser), browse.go (category browsing parser), topic.go (details.php parser with AJAX file list fetch), login.go (POST /takelogin.php with uid/pass cookie extraction), and download.go (dl.kinozal.tv/download.php binary download). Key differences from RuTracker: the login response sets uid and pass cookies directly (no 302 redirect), the download server is on a different subdomain (dl.kinozal.tv), and the topic detail page requires a secondary AJAX request to get_srv_details.php for extended information. The base URL is configurable via LAVA_API_KINOZAL_URL, defaulting to https://kinozal.tv. Mirror domains (kinozal.me, kinozal.guru) must be configured in the bundled mirrors.json.',
    body_style))

story.append(add_heading('5.2.2 Android: core/tracker/kinozal/', h3_style, 2))

story.append(Paragraph(
    'The Kinozal Android module at core/tracker/kinozal/ contains: KinozalDescriptor (trackerId "kinozal", displayName "Kinozal.tv", baseUrls [https://kinozal.tv (primary), kinozal.me, kinozal.guru], capabilities SEARCH/BROWSE/TOPIC/COMMENTS/TORRENT_DOWNLOAD/AUTH_REQUIRED, authType FORM_LOGIN, encoding "Windows-1251", expectedHealthMarker "Kinozal"). KinozalClient implementing SearchableTracker, BrowsableTracker, AuthenticatableTracker, TopicTracker, CommentsTracker, and DownloadableTracker (no FavoritesTracker since Kinozal lacks bookmarks). KinozalHttpClient with OkHttp, InMemoryCookieJar, Semaphore (4 concurrent), and CircuitBreaker (3 failures/30s). Feature implementations: KinozalSearch (GET /browse.php), KinozalBrowse (category browsing), KinozalAuth (POST /takelogin.php), KinozalTopic (GET /details.php + AJAX file list), KinozalComments (from detail page), KinozalDownload (GET dl.kinozal.tv/download.php). Parsers: KinozalSearchParser, KinozalTopicParser, KinozalCommentsParser, KinozalSizeParser (Russian unit format: ТБ/ГБ/МБ/КБ), KinozalDateParser. Category mapping with 12 top-level categories.',
    body_style))

story.append(add_heading('5.3 Internet Archive Implementation', h2_style, 1))

story.append(add_heading('5.3.1 Go API: internal/archiveorg/', h3_style, 2))

story.append(Paragraph(
    'The Internet Archive implementation is fundamentally different from the tracker implementations because it uses JSON REST APIs rather than HTML scraping. Create internal/archiveorg/ with client.go (HTTP client with rate limiter, User-Agent header, and JSON response parsing), search.go (Advanced Search API and Scrape API integration), browse.go (collection-based browsing using the Search API with collection: prefix queries), item.go (item metadata from /metadata/{IDENTIFIER}), download.go (HTTP file download from /download/{IDENTIFIER}/{FILENAME}), and collections.go (collection hierarchy using Search API). The client must set a proper User-Agent header (e.g., "Lava/2.0 (digital.vasic.lava)"), honor 429 responses with exponential backoff, and limit concurrent requests to 4. The base URL is configurable via LAVA_API_ARCHIVEORG_URL, defaulting to https://archive.org. Authentication is not required for read operations, but the Credentials struct must support optional IA-S3 keys for write operations in future extensions.',
    body_style))

story.append(add_heading('5.3.2 Android: core/tracker/archiveorg/', h3_style, 2))

story.append(Paragraph(
    'The Archive.org Android module at core/tracker/archiveorg/ contains: ArchiveOrgDescriptor (trackerId "archiveorg", displayName "Internet Archive", baseUrls [https://archive.org], capabilities SEARCH/BROWSE/FORUM/TOPIC/COMMENTS/TORRENT_DOWNLOAD, authType NONE, encoding "UTF-8", expectedHealthMarker "Internet Archive"). ArchiveOrgClient implementing SearchableTracker, BrowsableTracker (collections as categories), TopicTracker (item metadata as topic), CommentsTracker (reviews), and DownloadableTracker (HTTP file download). ArchiveOrgHttpClient with OkHttp, rate limiter (4 concurrent), and proper User-Agent. ArchiveOrgSearchApi wrapping the Advanced Search API with Lucene query builder. ArchiveOrgScrapeApi for cursor-based pagination. ArchiveOrgMetadataApi for item details. ArchiveOrgCollectionsApi for browse hierarchy. Mappers: ArchiveOrgSearchMapper (JSON results to TorrentItem), ArchiveOrgItemMapper (metadata to TopicDetail), ArchiveOrgCollectionMapper (collections to ForumCategory). The module uses kotlinx-serialization for JSON parsing rather than Jsoup, since all responses are JSON rather than HTML.',
    body_style))

story.append(add_heading('5.4 Project Gutenberg Implementation', h2_style, 1))

story.append(add_heading('5.4.1 Go API: internal/gutenberg/', h3_style, 2))

story.append(Paragraph(
    'The Gutenberg implementation must avoid direct web scraping and instead use the RDF catalog and OPDS feed. Create internal/gutenberg/ with client.go (HTTP client with 2-second minimum request delay for harvest endpoint), catalog.go (RDF/XML catalog parser for local database), search.go (local SQLite search using the parsed catalog), browse.go (bookshelf-based browsing), book.go (book detail with download URLs), download.go (HTTP file download with rate limiting), and opds.go (OPDS feed parser for real-time catalog updates). The startup sequence must check for a cached catalog database; if not present or older than 24 hours, download the RDF catalog from https://www.gutenberg.org/cache/epub/feeds/pg_catalog.rdf.zip, parse it into a SQLite database, and use that for all search and browse operations. The base URL is configurable via LAVA_API_GUTENBERG_URL, defaulting to https://www.gutenberg.org. No authentication is required.',
    body_style))

story.append(add_heading('5.4.2 Android: core/tracker/gutenberg/', h3_style, 2))

story.append(Paragraph(
    'The Gutenberg Android module at core/tracker/gutenberg/ contains: GutenbergDescriptor (trackerId "gutenberg", displayName "Project Gutenberg", baseUrls [https://www.gutenberg.org], capabilities SEARCH/BROWSE/FORUM/TOPIC/TORRENT_DOWNLOAD/RSS, authType NONE, encoding "UTF-8", expectedHealthMarker "Project Gutenberg"). GutenbergClient implementing SearchableTracker, BrowsableTracker, TopicTracker, and DownloadableTracker. GutenbergCatalogSyncWorker (WorkManager worker that periodically downloads and parses the RDF catalog into a local Room database). GutenbergLocalSearch (Room full-text search on the catalog database). GutenbergBookshelfParser (OPDS feed parser for bookshelf hierarchy). GutenbergBookMapper (catalog entry to TorrentItem/TopicDetail). The download feature must support multiple format options: the user can select their preferred format (EPUB with images, EPUB without images, Kindle, HTML, plain text) and the download URL is constructed accordingly. The catalog sync worker should run on WiFi + charging constraints by default, with a configurable interval (default 24 hours).',
    body_style))

# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# SECTION 6: CREDENTIALS MANAGEMENT
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
story.append(add_heading('6. Credentials Management System', h1_style, 0))

story.append(add_heading('6.1 Design Requirements', h2_style, 1))

story.append(Paragraph(
    'The credentials management system must satisfy the following requirements derived from the user specification: (1) Users can create, edit, and delete credentials (usernames/passwords or tokens) in the Settings screen. (2) On the Login screen, users pick a provider and associate it with credentials or tokens created in Settings. (3) Multiple providers can use the same credentials or token. (4) Users can check an Anonymous option to access providers without credentials. (5) On the provider configuration screen, if no credentials have been created, the user is redirected to the credentials Settings screen. (6) On the provider login/setup screen, there must be a button for creating new credentials or opening the Settings screen for management. (7) All credential values must be encrypted at rest using the Android Keystore. (8) Credentials must be scoped per-provider but shareable across providers.',
    body_style))

story.append(add_heading('6.2 Data Model', h2_style, 1))

cred_data = [
    [Paragraph('<b>Field</b>', th_style), Paragraph('<b>Type</b>', th_style), Paragraph('<b>Description</b>', th_style)],
    [Paragraph('id', td_style), Paragraph('UUID', td_center), Paragraph('Unique identifier', td_style)],
    [Paragraph('label', td_style), Paragraph('String', td_center), Paragraph('User-assigned name (e.g., "Main RuTracker Account")', td_style)],
    [Paragraph('type', td_style), Paragraph('Enum', td_center), Paragraph('USERNAME_PASSWORD, TOKEN, API_KEY', td_style)],
    [Paragraph('providerIds', td_style), Paragraph('Set<String>', td_center), Paragraph('Associated provider IDs (can be multiple)', td_style)],
    [Paragraph('username', td_style), Paragraph('String?', td_center), Paragraph('Username for USERNAME_PASSWORD type', td_style)],
    [Paragraph('password', td_style), Paragraph('String?', td_center), Paragraph('Encrypted password for USERNAME_PASSWORD type', td_style)],
    [Paragraph('token', td_style), Paragraph('String?', td_center), Paragraph('Encrypted token for TOKEN type', td_style)],
    [Paragraph('apiKey', td_style), Paragraph('String?', td_center), Paragraph('Encrypted API key for API_KEY type', td_style)],
    [Paragraph('apiSecret', td_style), Paragraph('String?', td_center), Paragraph('Encrypted API secret (optional)', td_style)],
    [Paragraph('createdAt', td_style), Paragraph('Long', td_center), Paragraph('Creation timestamp (epoch millis)', td_style)],
    [Paragraph('updatedAt', td_style), Paragraph('Long', td_center), Paragraph('Last modification timestamp', td_style)],
    [Paragraph('lastUsedAt', td_style), Paragraph('Long?', td_center), Paragraph('Last usage timestamp for stale detection', td_style)],
]
story.append(Spacer(1, 12))
story.append(make_table(cred_data, [AVAILABLE_W*0.20, AVAILABLE_W*0.15, AVAILABLE_W*0.65]))
story.append(Paragraph('Table 5: Credential Data Model', caption_style))

story.append(add_heading('6.3 User Flow', h2_style, 1))

add_image(story, os.path.join(DIAGRAMS, 'credentials_flow.png'),
          'Figure 6: Credentials Management User Flow')

story.append(Paragraph(
    'The credentials management flow operates through three primary user paths. The first path is through Settings: the user navigates to Settings, taps "Credentials Management", sees a list of all created credentials, and can create a new credential by tapping the "+" button, edit an existing one, or delete one. The second path is through Provider Login: the user opens the Login screen, selects a provider, sees a dropdown of available credentials (or "Anonymous"), and can either select an existing credential or tap "Create New Credentials" which navigates to the credentials creation dialog. After creating the credential, the user returns to the login screen with the new credential pre-selected. The third path is through Provider Configuration: the user opens a provider configuration screen, sees the "Authentication" section with a credential selector, and if no credentials exist, is redirected to the credentials management screen with a contextual hint explaining that credentials are needed for this provider.',
    body_style))

story.append(Paragraph(
    'The sharing mechanism works through the providerIds field on the Credential model. When a user creates or edits a credential, they can select which providers it should be associated with. For example, a single USERNAME_PASSWORD credential could be associated with both "rutracker" and "nnmclub" if the user uses the same credentials on both trackers. When the user logs into a provider, the credential selector shows all credentials associated with that provider plus any unassociated credentials (which the user can then associate). The anonymous mode is a per-provider setting stored in ProviderConfig.anonymousMode; when enabled, no credential is required for that provider and all operations proceed without authentication headers.',
    body_style))

# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# SECTION 7: UI/UX DESIGN
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
story.append(add_heading('7. UI/UX Design Specifications', h1_style, 0))

story.append(add_heading('7.1 Design Principles', h2_style, 1))

story.append(Paragraph(
    'The UI/UX redesign must follow Material Design 3 guidelines, providing a modern, enterprise-grade, cutting-edge quality product. The core design principles are: (1) Unified Experience - all providers should feel like one integrated service, not a collection of separate trackers. (2) Progressive Disclosure - advanced options (provider-specific filters, credential management) should be accessible but not overwhelming. (3) Real-Time Feedback - search results should appear as they arrive, with clear indicators of which providers are still loading. (4) Error Resilience - every error state must have a clear, actionable UI with retry options and helpful messages. (5) Persistent Preferences - all user selections (active providers, filters, sort orders) must be remembered across sessions. (6) Accessibility - all interactive elements must have content descriptions, minimum touch targets of 48dp, and proper color contrast ratios.',
    body_style))

story.append(add_heading('7.2 Wireframe Specifications', h2_style, 1))

add_image(story, os.path.join(DIAGRAMS, 'wireframe_credentials.png'),
          'Figure 7: Credentials Management Screen Wireframe')

add_image(story, os.path.join(DIAGRAMS, 'wireframe_credential_edit.png'),
          'Figure 8: Create/Edit Credential Dialog Wireframe')

add_image(story, os.path.join(DIAGRAMS, 'wireframe_provider_login.png'),
          'Figure 9: Provider Selection on Login Screen Wireframe')

add_image(story, os.path.join(DIAGRAMS, 'wireframe_unified_search.png'),
          'Figure 10: Unified Search Screen Wireframe')

add_image(story, os.path.join(DIAGRAMS, 'wireframe_unified_forums.png'),
          'Figure 11: Unified Forums Screen Wireframe')

add_image(story, os.path.join(DIAGRAMS, 'wireframe_provider_config.png'),
          'Figure 12: Provider Configuration Screen Wireframe')

story.append(add_heading('7.3 Navigation Redesign', h2_style, 1))

add_image(story, os.path.join(DIAGRAMS, 'android_navigation.png'),
          'Figure 13: Android Navigation Flow (Updated)')

story.append(Paragraph(
    'The navigation structure is updated to support the multi-provider paradigm. The bottom navigation retains four tabs but with updated semantics: Search (unified search across all providers), Forums (unified browsing across all providers with content hierarchies), Downloads (download history and active downloads), and Settings (credential management, provider configuration, and app preferences). The Search tab now includes provider filter chips above the results list, allowing users to quickly select which providers to include. The Forums tab presents a unified view with provider-grouped sections, each expandable to show that provider content hierarchy. The Settings tab gains two new top-level sections: Credentials Management and Provider Configuration, both accessible from the main settings screen.',
    body_style))

story.append(add_heading('7.4 Error Handling UI/UX', h2_style, 1))

story.append(Paragraph(
    'Every user-facing operation must have proper error handling with clear UI feedback. The error handling design follows these patterns: (1) Network Errors - show a Snackbar with the error message and a "Retry" action. If the error persists, show a full-screen error state with an illustration, the error message, and a "Try Again" button. (2) Authentication Errors - show a dialog explaining that the credentials may be invalid, with options to "Update Credentials" (navigate to credential edit), "Switch Account" (navigate to credential selector), or "Use Anonymously" (enable anonymous mode for this provider). (3) Provider Unavailable - show the provider badge with a "Unavailable" indicator, dim the provider results, and suggest alternative providers via the cross-tracker fallback modal. (4) Rate Limited - show a temporary message indicating that the provider is rate-limiting requests, with an estimated wait time. (5) Partial Results - when some providers succeed and others fail, show successful results normally with a subtle notification banner indicating that N providers could not be reached. (6) Empty Results - show a contextual empty state with provider-specific messaging, such as "No torrents found for this query on any provider" with suggestions to adjust filters or try different search terms.',
    body_style))

# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# SECTION 8: TESTING PLAN
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
story.append(add_heading('8. Comprehensive Testing Plan', h1_style, 0))

story.append(add_heading('8.1 Constitutional Compliance', h2_style, 1))

story.append(Paragraph(
    'All testing must comply with the Lava project Constitutional Framework (7 Laws). The First Law requires tests to guarantee real user-visible behavior. The Second Law prohibits mocking internal business logic; only outermost boundaries (HTTP clients, databases) may be substituted with fakes. The Third Law requires fakes to be behaviorally equivalent to real implementations. The Fourth Law mandates an Integration Challenge Test for every feature. The Fifth Law requires bug fixes to include regression tests. The Sixth Law (Real User Verification) has six extensions: 6.A (real-binary contract tests), 6.B (container health verification), 6.C (mirror-state mismatch checks), 6.D (behavioral coverage contract), 6.E (capability honesty - declared capability implies getFeature() non-null), and 6.F (anti-bluff submodule inheritance). The Seventh Law (Anti-Bluff Enforcement) requires bluff-audit stamps, real-stack verification gates, pre-tag real-device attestation, forbidden test patterns, recurring bluff hunts, and bluff discovery protocols.',
    body_style))

story.append(add_heading('8.2 Test Categories', h2_style, 1))

test_cat_data = [
    [Paragraph('<b>Category</b>', th_style), Paragraph('<b>Scope</b>', th_style), Paragraph('<b>Tools</b>', th_style), Paragraph('<b>Coverage Target</b>', th_style)],
    [Paragraph('Unit Tests', td_style), Paragraph('Individual parsers, mappers, use cases', td_style), Paragraph('JUnit 5, MockK (boundaries only)', td_style), Paragraph('90%+', td_center)],
    [Paragraph('Integration Tests', td_style), Paragraph('Provider + HTTP client + parser chains', td_style), Paragraph('JUnit 5, OkHttp MockWebServer', td_style), Paragraph('80%+', td_center)],
    [Paragraph('Contract Tests', td_style), Paragraph('API response shape validation', td_style), Paragraph('Go testing, parity test fixtures', td_style), Paragraph('100% routes', td_center)],
    [Paragraph('Challenge Tests', td_style), Paragraph('End-to-end user scenarios on real device', td_style), Paragraph('Android Instrumentation, Hilt', td_style), Paragraph('12 scenarios', td_center)],
    [Paragraph('Fuzz Tests', td_style), Paragraph('Parser robustness against malformed HTML', td_style), Paragraph('Go fuzzing, JUnit Property-based', td_style), Paragraph('All parsers', td_center)],
    [Paragraph('Performance Tests', td_style), Paragraph('Concurrent search, cache hit rates', td_style), Paragraph('k6, Android Profiler', td_style), Paragraph('P95 < 2s', td_center)],
    [Paragraph('Security Tests', td_style), Paragraph('Credential encryption, auth token handling', td_style), Paragraph('OWASP checklist, manual pen test', td_style), Paragraph('All auth paths', td_center)],
]
story.append(Spacer(1, 12))
story.append(make_table(test_cat_data, [AVAILABLE_W*0.16, AVAILABLE_W*0.30, AVAILABLE_W*0.30, AVAILABLE_W*0.24]))
story.append(Paragraph('Table 6: Testing Categories and Coverage Targets', caption_style))

story.append(add_heading('8.3 Provider-Specific Test Plans', h2_style, 1))

story.append(add_heading('8.3.1 NNMClub Tests', h3_style, 2))
story.append(Paragraph(
    'NNMClub tests must cover: (1) Search parsing with real HTML fixtures from /forum/tracker.php, including normal results, empty results, and Cyrillic query results. (2) Forum tree parsing from the category hierarchy page. (3) Topic detail parsing including magnet link extraction, metadata extraction (production, director, cast), and file list parsing from filelst.php. (4) Authentication flow with phpBB login, cookie extraction, and session validation. (5) Download flow with auth-required verification (anonymous download should return ErrUnauthorized). (6) Windows-1251 charset decoding verification using the same approach as the RuTracker charset tests. (7) Pagination parsing for search results spanning multiple pages. (8) Fuzz tests against malformed tracker.php HTML. Test fixtures must be captured from the real NNMClub website and stored in src/test/resources/fixtures/nnmclub/ with date stamps in the filename (following the project convention of including capture dates in fixture filenames).',
    body_style))

story.append(add_heading('8.3.2 Kinozal Tests', h3_style, 2))
story.append(Paragraph(
    'Kinozal tests must cover: (1) Search parsing from /browse.php, including normal results, category-filtered results, and sort-ordered results. (2) Topic detail parsing including title extraction, poster image, IMDb/Kinopoisk links, and AJAX-loaded file details. (3) Authentication flow with uid/pass cookie extraction and login failure detection. (4) Download flow with dl.kinozal.tv subdomain handling and auth cookie forwarding. (5) Size parsing for Russian unit format (ТБ/ГБ/МБ/КБ). (6) Date parsing for Kinozal-specific date formats. (7) Category mapping verification for the 12 top-level categories. (8) Mirror fallback tests verifying kinozal.tv to kinozal.me to kinozal.guru rotation. (9) Fuzz tests against malformed browse.php and details.php HTML. Test fixtures must be captured from the real Kinozal website with date-stamped filenames.',
    body_style))

story.append(add_heading('8.3.3 Archive.org Tests', h3_style, 2))
story.append(Paragraph(
    'Archive.org tests must cover: (1) Search API response parsing with various mediatypes (movies, audio, texts, software). (2) Collection browsing with nested collection hierarchies. (3) Item metadata parsing from /metadata/{IDENTIFIER} endpoint. (4) Download URL construction for various file formats. (5) Rate limiting behavior with 429 response handling and Retry-After header parsing. (6) Cursor-based pagination via the Scrape API. (7) Lucene query builder for field-specific searches (mediatype, collection, language, date ranges). (8) Error handling for invalid identifiers, unavailable items, and network timeouts. Unlike the HTML-based tracker tests, Archive.org tests use JSON fixtures rather than HTML, making them more stable and less prone to breakage from UI changes on the website.',
    body_style))

story.append(add_heading('8.3.4 Gutenberg Tests', h3_style, 2))
story.append(Paragraph(
    'Gutenberg tests must cover: (1) RDF/XML catalog parsing from the daily catalog feed. (2) Local SQLite database search functionality with FTS5. (3) OPDS feed parsing for bookshelf hierarchies. (4) Book detail construction with multiple download format URLs. (5) Catalog sync worker scheduling and incremental updates. (6) Rate limiting enforcement (2-second minimum delay between harvest requests). (7) Format selection logic (EPUB, Kindle, HTML, TXT). (8) Bookshelf-to-ForumCategory mapping. The RDF catalog parser is the most critical test target, as it must handle the full 75,000+ book catalog efficiently. Test fixtures should include a representative subset of the RDF catalog (100-200 entries) covering various book types, languages, and formats.',
    body_style))

story.append(add_heading('8.4 Challenge Tests (Integration Scenarios)', h2_style, 1))

challenge_data = [
    [Paragraph('<b>ID</b>', th_style), Paragraph('<b>Scenario</b>', th_style), Paragraph('<b>Providers</b>', th_style), Paragraph('<b>Verifies</b>', th_style)],
    [Paragraph('C1', td_center), Paragraph('App launch and provider list display', td_style), Paragraph('All', td_center), Paragraph('All providers registered and discoverable', td_style)],
    [Paragraph('C2', td_center), Paragraph('Unified search across all providers', td_style), Paragraph('All', td_center), Paragraph('Concurrent dispatch, result streaming, provider badges', td_style)],
    [Paragraph('C3', td_center), Paragraph('Provider-specific search filter', td_style), Paragraph('RuTracker, NNMClub', td_center), Paragraph('Filter selection persistence, single-provider search', td_style)],
    [Paragraph('C4', td_center), Paragraph('Anonymous search on RuTor', td_style), Paragraph('RuTor', td_center), Paragraph('Anonymous mode works without credentials', td_style)],
    [Paragraph('C5', td_center), Paragraph('Authenticated search on NNMClub', td_style), Paragraph('NNMClub', td_center), Paragraph('phpBB auth flow, cookie-based session', td_style)],
    [Paragraph('C6', td_center), Paragraph('Kinozal login and torrent download', td_style), Paragraph('Kinozal', td_center), Paragraph('uid/pass auth, dl.kinozal.tv download', td_style)],
    [Paragraph('C7', td_center), Paragraph('Archive.org collection browse', td_style), Paragraph('Archive.org', td_center), Paragraph('Collection hierarchy, item metadata', td_style)],
    [Paragraph('C8', td_center), Paragraph('Gutenberg book search and download', td_style), Paragraph('Gutenberg', td_center), Paragraph('Local catalog search, multi-format download', td_style)],
    [Paragraph('C9', td_center), Paragraph('Create credential and associate with provider', td_style), Paragraph('All', td_center), Paragraph('Credential CRUD, provider association', td_style)],
    [Paragraph('C10', td_center), Paragraph('Share credential across multiple providers', td_style), Paragraph('RuTracker, NNMClub', td_center), Paragraph('Credential sharing, multi-provider auth', td_style)],
    [Paragraph('C11', td_center), Paragraph('Cross-tracker fallback on provider failure', td_style), Paragraph('RuTor, NNMClub', td_center), Paragraph('Fallback modal, alternative result display', td_style)],
    [Paragraph('C12', td_center), Paragraph('Unified forums with mixed provider content', td_style), Paragraph('RuTracker, Archive.org', td_center), Paragraph('Mixed content hierarchy, source badges', td_style)],
]
story.append(Spacer(1, 12))
story.append(make_table(challenge_data, [AVAILABLE_W*0.06, AVAILABLE_W*0.32, AVAILABLE_W*0.22, AVAILABLE_W*0.40]))
story.append(Paragraph('Table 7: Integration Challenge Tests', caption_style))

# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# SECTION 9: IMPLEMENTATION PHASES
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
story.append(add_heading('9. Implementation Phases', h1_style, 0))

story.append(add_heading('9.1 Phase Overview', h2_style, 1))

phase_data = [
    [Paragraph('<b>Phase</b>', th_style), Paragraph('<b>Name</b>', th_style), Paragraph('<b>Duration</b>', th_style), Paragraph('<b>Key Deliverables</b>', th_style)],
    [Paragraph('0', td_center), Paragraph('Pre-Flight Audit', td_style), Paragraph('1 week', td_center), Paragraph('Bluff audit, baseline coverage, test infrastructure', td_style)],
    [Paragraph('1', td_center), Paragraph('Go API Provider Abstraction', td_style), Paragraph('2 weeks', td_center), Paragraph('Provider interface, registry, route redesign, auth generalization', td_style)],
    [Paragraph('2', td_center), Paragraph('NNMClub Full Implementation', td_style), Paragraph('2 weeks', td_center), Paragraph('Go + Android NNMClub with all features and tests', td_style)],
    [Paragraph('3', td_center), Paragraph('Kinozal Full Implementation', td_style), Paragraph('2 weeks', td_center), Paragraph('Go + Android Kinozal with all features and tests', td_style)],
    [Paragraph('4', td_center), Paragraph('Archive.org Full Implementation', td_style), Paragraph('2 weeks', td_center), Paragraph('Go + Android Archive.org with JSON API integration', td_style)],
    [Paragraph('5', td_center), Paragraph('Gutenberg Full Implementation', td_style), Paragraph('2 weeks', td_center), Paragraph('Go + Android Gutenberg with RDF catalog', td_style)],
    [Paragraph('6', td_center), Paragraph('Credentials Management System', td_style), Paragraph('2 weeks', td_center), Paragraph('Android credentials CRUD, encryption, provider association', td_style)],
    [Paragraph('7', td_center), Paragraph('Unified Search and Browse', td_style), Paragraph('2 weeks', td_center), Paragraph('Concurrent search, real-time results, unified forums', td_style)],
    [Paragraph('8', td_center), Paragraph('UI/UX Redesign', td_style), Paragraph('2 weeks', td_center), Paragraph('Material Design 3, wireframes to code, error states', td_style)],
    [Paragraph('9', td_center), Paragraph('Persistence and Preferences', td_style), Paragraph('1 week', td_center), Paragraph('Filter persistence, provider selections, preference migration', td_style)],
    [Paragraph('10', td_center), Paragraph('Testing and Documentation', td_style), Paragraph('2 weeks', td_center), Paragraph('12 Challenge Tests, user manuals, API docs, real-device attestation', td_style)],
]
story.append(Spacer(1, 12))
story.append(make_table(phase_data, [AVAILABLE_W*0.08, AVAILABLE_W*0.25, AVAILABLE_W*0.12, AVAILABLE_W*0.55]))
story.append(Paragraph('Table 8: Implementation Phases Overview', caption_style))

story.append(add_heading('9.2 Phase 0: Pre-Flight Audit', h2_style, 1))
story.append(Paragraph(
    'Phase 0 establishes the baseline before any multi-provider changes. Tasks: (0.1) Run existing test suite and verify 100% pass rate on current RuTracker and RuTor implementations. (0.2) Audit existing fakes (FakeTrackerClient, TestEndpointsRepository, TestBookmarksRepository) for behavioral equivalence - verify LF-1 (TestBookmarksRepository stub) and LF-2 (TestHealthcheckContract vacuous pass) are still open issues or have been resolved. (0.3) Capture HTML fixtures from all four new providers (NNMClub search/forum/topic/login, Kinozal search/browse/details/login, Archive.org search/metadata/collections, Gutenberg OPDS/catalog). (0.4) Set up test infrastructure for new providers: create test fixture directories, configure MockWebServer for Go API tests, configure OkHttp MockWebServer for Android tests. (0.5) Run bluff hunt on 5 random existing test files, mutate production code, confirm test failure. (0.6) Document baseline coverage metrics for Go API and Android client.',
    body_style))

story.append(add_heading('9.3 Phase 1: Go API Provider Abstraction', h2_style, 1))
story.append(Paragraph(
    'Phase 1 redesigns the Go API to support multiple providers. Tasks: (1.1) Create internal/provider/provider.go with the Provider interface, Credentials struct, SearchOpts, BrowseOpts, LoginOpts, and all result types. (1.2) Create internal/provider/registry.go with ProviderRegistry that maps provider IDs to Provider implementations. (1.3) Refactor internal/rutracker/ to implement the Provider interface - create an adapter that wraps the existing Client and translates between ScraperClient-specific types and Provider-agnostic types. (1.4) Create internal/middleware/provider.go with provider dispatch middleware that extracts the {provider} path parameter and injects the Provider into the Gin context. (1.5) Generalize internal/auth/ to support multiple credential types in the Auth-Token header format. (1.6) Refactor internal/handlers/ to use Provider instead of ScraperClient - each handler retrieves the Provider from the context and calls Provider methods. (1.7) Update the route structure from flat paths to /v1/{provider}/... paths. (1.8) Update cmd/lava-api-go/main.go to register all providers and use the new routing. (1.9) Add database migrations 0006-0009 for the four new tables (provider_credentials, provider_configs, search_provider_selections, forum_provider_selections). (1.10) Verify all existing tests pass with the refactored architecture (byte-equivalent responses for RuTracker routes). (1.11) Update api/openapi.yaml with the new route structure and provider parameter. (1.12) Regenerate internal/gen/ via oapi-codegen.',
    body_style))

story.append(add_heading('9.4 Phase 2: NNMClub Full Implementation', h2_style, 1))
story.append(Paragraph(
    'Phase 2 implements NNMClub as a full provider. Go API tasks: (2.1) Create internal/nnmclub/client.go with HTTP client, circuit breaker, Windows-1251 decoding. (2.2) Implement internal/nnmclub/search.go with POST /forum/tracker.php parser. (2.3) Implement internal/nnmclub/forum.go with category hierarchy parser. (2.4) Implement internal/nnmclub/topic.go with viewtopic.php parser including magnet link extraction. (2.5) Implement internal/nnmclub/login.go with phpBB authentication flow. (2.6) Implement internal/nnmclub/favorites.go with phpBB bookmark management. (2.7) Implement internal/nnmclub/comments.go with phpBB comment parser. (2.8) Implement internal/nnmclub/download.go with download.php handler. (2.9) Register NNMClub provider in the registry and add LAVA_API_NNMCLUB_URL config. (2.10) Write unit tests for all parsers with HTML fixtures. (2.11) Write integration tests with MockWebServer. (2.12) Write contract tests against the OpenAPI spec. Android tasks: (2.13) Create core/tracker/nnmclub/ module with NNMClubDescriptor. (2.14) Implement NNMClubClient with all feature interfaces. (2.15) Implement NNMClubHttpClient with OkHttp and cookie management. (2.16) Implement all parsers (search, forum, topic, comments, size, date). (2.17) Implement all feature classes (search, browse, auth, topic, comments, favorites, download). (2.18) Register NNMClubClientFactory in TrackerClientModule. (2.19) Add NNMClub mirrors to mirrors.json. (2.20) Write unit tests for all parsers with HTML fixtures. (2.21) Write integration tests with MockWebServer.',
    body_style))

story.append(add_heading('9.5 Phases 3-5: Kinozal, Archive.org, Gutenberg', h2_style, 1))
story.append(Paragraph(
    'Phases 3, 4, and 5 follow the same pattern as Phase 2, with provider-specific adjustments. Phase 3 (Kinozal) includes the additional complexity of the dl.kinozal.tv download subdomain, the uid/pass cookie authentication model, the Russian size format parser, and the AJAX-loaded file details. Phase 4 (Archive.org) is fundamentally different because it uses JSON REST APIs rather than HTML scraping; the parser implementations use kotlinx-serialization for JSON parsing rather than Jsoup, and the test fixtures are JSON files rather than HTML snapshots. Phase 5 (Gutenberg) introduces the RDF catalog synchronization via a WorkManager worker, the local Room database with FTS5 search, and the multi-format download URL construction. Each phase produces a complete, fully-tested provider implementation that is registered in both the Go API provider registry and the Android tracker registry.',
    body_style))

story.append(add_heading('9.6 Phase 6: Credentials Management System', h2_style, 1))
story.append(Paragraph(
    'Phase 6 implements the credentials management system on the Android client. Tasks: (6.1) Create core/credentials/ module with Credential data model, CredentialType enum, and CredentialRepository interface. (6.2) Implement CredentialRepositoryImpl using Room database with EncryptedSharedPreferences for sensitive values. (6.3) Implement AES-256-GCM encryption using Android Keystore for credential value storage. (6.4) Create CredentialDao with Room annotations for CRUD operations and provider association queries. (6.5) Create CredentialModule Hilt module providing repository bindings. (6.6) Create feature/credentials/ feature module with CredentialsScreen, CredentialsViewModel, CredentialsState, CredentialsAction, CredentialsSideEffect. (6.7) Implement credential list UI with type badges, provider association indicators, and edit/delete actions. (6.8) Implement credential creation/edit bottom sheet dialog with conditional fields based on credential type. (6.9) Implement credential deletion with confirmation dialog. (6.10) Implement provider association UI with multi-select provider chips. (6.11) Extend LavaTrackerSdk with credential management methods. (6.12) Extend LoginScreen with credential selector dropdown and "Create New Credentials" button. (6.13) Implement anonymous mode toggle per provider. (6.14) Implement redirect from provider config to credentials screen when no credentials exist. (6.15) Write unit tests for CredentialRepository with encrypted storage verification. (6.16) Write UI tests for CredentialsScreen with Compose testing.',
    body_style))

story.append(add_heading('9.7 Phase 7: Unified Search and Browse', h2_style, 1))
story.append(Paragraph(
    'Phase 7 transforms the search and browse experience from single-tracker to multi-provider concurrent operations. Tasks: (7.1) Implement searchAll() in LavaTrackerSdk using Kotlin coroutines async/awaitAll with SharedFlow result emission. (7.2) Create SearchResultBatch data class with provider ID, results list, error state, and completion flag. (7.3) Implement real-time result merging and sorting in SearchResultViewModel. (7.4) Update SearchScreen UI with provider filter chips and real-time result updates. (7.5) Implement provider badge display on each search result item. (7.6) Implement partial error indicators for providers that fail or timeout during search. (7.7) Implement search button disable when no providers are selected. (7.8) Implement browseAll() in LavaTrackerSdk for concurrent forum/category browsing. (7.9) Update ForumScreen with unified content hierarchy showing provider-grouped sections. (7.10) Implement provider-specific detail screen routing (click on result opens the correct detail screen variant). (7.11) Write tests for concurrent search with mock providers returning results at different speeds. (7.12) Write tests for error handling when some providers fail during concurrent search.',
    body_style))

story.append(add_heading('9.8 Phase 8: UI/UX Redesign', h2_style, 1))
story.append(Paragraph(
    'Phase 8 implements the Material Design 3 visual redesign. Tasks: (8.1) Define Material 3 theme with dynamic color support for Android 12+ and fallback static theme for older versions. (8.2) Implement provider-specific color tokens for badges and icons (RuTracker blue, RuTor red, NNMClub amber, Kinozal purple, Archive.org green, Gutenberg indigo). (8.3) Redesign bottom navigation with updated icons and labels. (8.4) Redesign SearchScreen with search bar, provider chips, and result cards with provider badges. (8.5) Redesign ForumScreen with unified sections and source indicators. (8.6) Redesign LoginScreen with provider selection cards and credential integration. (8.7) Redesign SettingsScreen with credentials management and provider configuration sections. (8.8) Implement all error state UIs (network error, auth error, provider unavailable, rate limited, partial results, empty results). (8.9) Implement loading skeletons for all data-fetching screens. (8.10) Implement smooth transitions between screens with shared element transitions. (8.11) Accessibility audit: content descriptions, touch targets, color contrast. (8.12) Dark theme support with proper color mapping for all provider badges.',
    body_style))

story.append(add_heading('9.9 Phase 9: Persistence and Preferences', h2_style, 1))
story.append(Paragraph(
    'Phase 9 ensures all user actions are persisted across sessions. Tasks: (9.1) Implement SearchProviderSelectionsRepository with Room database persistence. (9.2) Implement ForumProviderSelectionsRepository with Room database persistence. (9.3) Migrate existing Settings repository to support per-provider preferences. (9.4) Implement filter state persistence for search (sort order, period, categories per provider). (9.5) Implement provider display order persistence. (9.6) Implement anonymous mode persistence per provider. (9.7) Implement credential-provider association persistence. (9.8) Add data migration from AppDatabase v7 to v8 with new tables. (9.9) Write tests verifying persistence across app restarts. (9.10) Write tests verifying migration from v7 to v8 preserves existing data.',
    body_style))

story.append(add_heading('9.10 Phase 10: Testing and Documentation', h2_style, 1))
story.append(Paragraph(
    'Phase 10 completes the testing and documentation effort. Tasks: (10.1) Execute all 12 Challenge Tests on a real Android device. (10.2) Write user manual for credentials management (creating, editing, deleting, sharing credentials). (10.3) Write user manual for provider selection and configuration. (10.4) Write user manual for unified search and forums. (10.5) Write API documentation for the new /v1/{provider}/... routes. (10.6) Write developer guide for adding new providers (step-by-step recipe). (10.7) Update README.md with multi-provider architecture overview. (10.8) Update CHANGELOG.md with all changes. (10.9) Create real-device attestation evidence pack for tagging. (10.10) Run scripts/tag.sh to verify all evidence gates pass. (10.11) Final bluff hunt on 5 random new test files. (10.12) Performance baseline measurement for concurrent search across all 6 providers.',
    body_style))

# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# SECTION 10: USER MANUALS & GUIDES
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
story.append(add_heading('10. User Manuals and Guides', h1_style, 0))

story.append(add_heading('10.1 Credentials Management Guide', h2_style, 1))

story.append(Paragraph(
    'The Credentials Management feature allows you to securely store and manage your login information for all supported content providers. Credentials are encrypted on your device using hardware-backed encryption (Android Keystore) and never leave your device in plaintext. This guide covers the complete lifecycle of credential management: creating, editing, deleting, and associating credentials with providers.',
    body_style))

story.append(add_heading('10.1.1 Creating a New Credential', h3_style, 2))
story.append(Paragraph(
    'To create a new credential, navigate to Settings and tap "Credentials Management". Tap the "+" button (or the "Create your first credential" call-to-action if no credentials exist yet). Enter a descriptive label for the credential (e.g., "My RuTracker Account" or "Archive.org API Key"). Select the credential type: Username + Password (for tracker login credentials), Token (for session tokens or OAuth tokens), or API Key (for API access keys like IA-S3 keys). Fill in the required fields based on the selected type. For Username + Password, enter your username and password. For Token, enter the token value. For API Key, enter the key and optionally the secret. Tap "Save" to create the credential. The credential is immediately available for association with any provider.',
    body_style))

story.append(add_heading('10.1.2 Associating Credentials with Providers', h3_style, 2))
story.append(Paragraph(
    'Credentials can be associated with one or more providers. To associate a credential during provider login, select the provider on the Login screen, then use the credential selector dropdown to choose an existing credential or tap "Create New Credentials" to create one on the spot. To associate a credential from Settings, navigate to Settings, tap "Provider Configuration", select the provider, and use the "Authentication" section to select or change the associated credential. To share a credential across multiple providers, edit the credential and use the "Associated Providers" section to select which providers should use this credential. This is useful when you use the same username and password on multiple trackers (e.g., the same account on both RuTracker and NNMClub).',
    body_style))

story.append(add_heading('10.1.3 Using Anonymous Access', h3_style, 2))
story.append(Paragraph(
    'Some providers support anonymous access, meaning you can search and browse without providing any credentials. To use anonymous access, toggle the "Anonymous" switch on the Login screen or in the Provider Configuration screen. When anonymous mode is enabled, all operations will proceed without authentication. Note that some features (such as downloading .torrent files from NNMClub or Kinozal, or adding favorites on RuTracker) require authentication and will not be available in anonymous mode. The app will display a clear message when an action requires authentication and offer to switch to authenticated mode.',
    body_style))

story.append(add_heading('10.2 Unified Search Guide', h2_style, 1))

story.append(Paragraph(
    'The Unified Search feature allows you to search across all your configured content providers simultaneously, presenting results from multiple sources in a single, real-time updated list. This guide explains how to use unified search effectively.',
    body_style))

story.append(add_heading('10.2.1 Searching Across All Providers', h3_style, 2))
story.append(Paragraph(
    'By default, search queries are sent to all enabled providers simultaneously. As each provider returns results, they are immediately added to the results list and sorted according to your selected sort order. Each result displays a small colored badge indicating which provider returned it (blue for RuTracker, red for RuTor, amber for NNMClub, purple for Kinozal, green for Archive.org, indigo for Gutenberg). While search is in progress, a loading indicator appears next to each provider that has not yet responded. If a provider fails or times out, a subtle notification appears indicating that results from that provider are unavailable.',
    body_style))

story.append(add_heading('10.2.2 Filtering by Provider', h3_style, 2))
story.append(Paragraph(
    'You can limit your search to specific providers by using the provider filter chips above the results list. Tap the "All" chip to search all providers, or tap individual provider chips to include only those providers in the search. You can select multiple providers simultaneously. If no providers are selected, the search button is disabled and a message appears prompting you to select at least one provider. Your filter selections are remembered between sessions, so you do not need to reconfigure them each time you open the app. To reset all filters, tap the "All" chip again.',
    body_style))

story.append(add_heading('10.3 Unified Forums Guide', h2_style, 1))

story.append(Paragraph(
    'The Unified Forums feature presents browsable content from all providers that offer hierarchical content structures. For torrent trackers (RuTracker, NNMClub, Kinozal), this shows their forum category trees. For Archive.org, this shows collection hierarchies. For Project Gutenberg, this shows bookshelf categories. All content is presented in a unified view where each section is labeled with its source provider.',
    body_style))

story.append(Paragraph(
    'To configure which providers appear in the Unified Forums view, navigate to the Forums screen and use the provider filter tabs at the top. You can enable or disable individual providers. At least one provider must be enabled at all times. Your selections are persisted across sessions. When browsing a category, the content is displayed with source badges, allowing you to identify which provider each item comes from. Tapping an item opens the appropriate detail screen for that provider, whether it is a torrent detail page (for trackers) or an item metadata page (for HTTP-based providers).',
    body_style))

# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# SECTION 11: CODEBASE REFERENCE INDEX
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
story.append(add_heading('11. Codebase Reference Index', h1_style, 0))

story.append(Paragraph(
    'This section provides a comprehensive index of all existing codebase files that must be modified, extended, or referenced during the multi-provider implementation. Each entry includes the file path, a brief description, and the nature of the required change.',
    body_style))

ref_data = [
    [Paragraph('<b>File Path</b>', th_style), Paragraph('<b>Description</b>', th_style), Paragraph('<b>Change Type</b>', th_style)],
    [Paragraph('lava-api-go/internal/handlers/handlers.go', td_style), Paragraph('ScraperClient interface and route registration', td_style), Paragraph('Major refactor', td_center)],
    [Paragraph('lava-api-go/internal/handlers/*.go', td_style), Paragraph('All handler implementations (15 files)', td_style), Paragraph('Update to use Provider', td_center)],
    [Paragraph('lava-api-go/internal/rutracker/client.go', td_style), Paragraph('RuTracker HTTP client with circuit breaker', td_style), Paragraph('Add Provider adapter', td_center)],
    [Paragraph('lava-api-go/internal/rutracker/*.go', td_style), Paragraph('All RuTracker implementation files (16 files)', td_style), Paragraph('Add Provider adapter', td_center)],
    [Paragraph('lava-api-go/internal/auth/passthrough.go', td_style), Paragraph('Auth middleware (RuTracker-specific)', td_style), Paragraph('Generalize for multi-provider', td_center)],
    [Paragraph('lava-api-go/internal/config/config.go', td_style), Paragraph('Environment variable configuration', td_style), Paragraph('Add provider URLs', td_center)],
    [Paragraph('lava-api-go/internal/server/server.go', td_style), Paragraph('HTTP server setup', td_style), Paragraph('Update routing', td_center)],
    [Paragraph('lava-api-go/cmd/lava-api-go/main.go', td_style), Paragraph('Application entry point', td_style), Paragraph('Register all providers', td_center)],
    [Paragraph('lava-api-go/api/openapi.yaml', td_style), Paragraph('OpenAPI 3.0.3 specification', td_style), Paragraph('Add provider parameter', td_center)],
    [Paragraph('lava-api-go/migrations/*', td_style), Paragraph('Database migration files', td_style), Paragraph('Add 0006-0009', td_center)],
    [Paragraph('core/tracker/api/TrackerClient.kt', td_style), Paragraph('Root tracker interface', td_style), Paragraph('No change (sufficient)', td_center)],
    [Paragraph('core/tracker/api/TrackerDescriptor.kt', td_style), Paragraph('Tracker metadata interface', td_style), Paragraph('Add ProviderType field', td_center)],
    [Paragraph('core/tracker/api/TrackerCapability.kt', td_style), Paragraph('Capability enum', td_style), Paragraph('Add HTTP_DOWNLOAD', td_center)],
    [Paragraph('core/tracker/client/LavaTrackerSdk.kt', td_style), Paragraph('SDK facade', td_style), Paragraph('Add multi-provider methods', td_center)],
    [Paragraph('core/tracker/registry/DefaultTrackerRegistry.kt', td_style), Paragraph('Tracker registry', td_style), Paragraph('Register 4 new providers', td_center)],
    [Paragraph('core/tracker/mirror/MirrorConfigStore.kt', td_style), Paragraph('Mirror configuration', td_style), Paragraph('Add mirrors for 4 providers', td_center)],
    [Paragraph('core/models/src/.../Settings.kt', td_style), Paragraph('App settings model', td_style), Paragraph('Add provider prefs', td_center)],
    [Paragraph('core/models/src/.../Endpoint.kt', td_style), Paragraph('Endpoint sealed type', td_style), Paragraph('Extend for multi-provider', td_center)],
    [Paragraph('feature/login/LoginScreen.kt', td_style), Paragraph('Login screen composable', td_style), Paragraph('Major redesign', td_center)],
    [Paragraph('feature/login/LoginViewModel.kt', td_style), Paragraph('Login view model', td_style), Paragraph('Add credential logic', td_center)],
    [Paragraph('feature/search/SearchScreen.kt', td_style), Paragraph('Search screen composable', td_style), Paragraph('Add provider chips', td_center)],
    [Paragraph('feature/search_result/SearchResultViewModel.kt', td_style), Paragraph('Search result view model', td_style), Paragraph('Add concurrent search', td_center)],
    [Paragraph('feature/forum/ForumScreen.kt', td_style), Paragraph('Forum screen composable', td_style), Paragraph('Add unified forums', td_center)],
    [Paragraph('feature/tracker_settings/TrackerSettingsScreen.kt', td_style), Paragraph('Tracker settings screen', td_style), Paragraph('Add credential section', td_center)],
    [Paragraph('app/src/.../navigation/MobileNavigation.kt', td_style), Paragraph('Navigation graph', td_style), Paragraph('Add new destinations', td_center)],
    [Paragraph('app/src/main/assets/mirrors.json', td_style), Paragraph('Bundled mirror configuration', td_style), Paragraph('Add 4 provider mirrors', td_center)],
]
story.append(Spacer(1, 12))
story.append(make_table(ref_data, [AVAILABLE_W*0.38, AVAILABLE_W*0.40, AVAILABLE_W*0.22]))
story.append(Paragraph('Table 9: Codebase Reference Index', caption_style))

# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# SECTION 12: RISK ANALYSIS
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
story.append(add_heading('12. Risk Analysis and Mitigation', h1_style, 0))

risk_data = [
    [Paragraph('<b>Risk</b>', th_style), Paragraph('<b>Impact</b>', th_style), Paragraph('<b>Likelihood</b>', th_style), Paragraph('<b>Mitigation</b>', th_style)],
    [Paragraph('Provider website changes break HTML parsers', td_style), Paragraph('High', td_center), Paragraph('Medium', td_center), Paragraph('Fixture-based testing, CI freshness checks, parser abstraction layer', td_style)],
    [Paragraph('Windows-1251 decoding issues on new trackers', td_style), Paragraph('Medium', td_center), Paragraph('Low', td_center), Paragraph('Reuse RuTracker charset transcoding pattern, test with Cyrillic content', td_style)],
    [Paragraph('Archive.org API changes or deprecation', td_style), Paragraph('Medium', td_center), Paragraph('Low', td_center), Paragraph('Pin API versions, use Scrape API as fallback, cache responses', td_style)],
    [Paragraph('Gutenberg IP ban from aggressive requests', td_style), Paragraph('High', td_center), Paragraph('Medium', td_center), Paragraph('Strict 2-second rate limiting, local RDF catalog, no direct web scraping', td_style)],
    [Paragraph('Android Keystore credential encryption fails on some devices', td_style), Paragraph('High', td_center), Paragraph('Low', td_center), Paragraph('Fallback to EncryptedSharedPreferences, hardware keystore detection', td_style)],
    [Paragraph('Concurrent search causes UI jank or ANR', td_style), Paragraph('High', td_center), Paragraph('Medium', td_center), Paragraph('Result batching, debounce UI updates, SharedFlow with buffer', td_style)],
    [Paragraph('OpenAPI spec changes break code generation', td_style), Paragraph('Medium', td_center), Paragraph('Medium', td_center), Paragraph('Pin oapi-codegen version, contract tests against golden files', td_style)],
    [Paragraph('Database migration failures on existing users', td_style), Paragraph('High', td_center), Paragraph('Low', td_center), Paragraph('Migration testing on real databases, rollback scripts, phased migration', td_style)],
]
story.append(Spacer(1, 12))
story.append(make_table(risk_data, [AVAILABLE_W*0.25, AVAILABLE_W*0.10, AVAILABLE_W*0.12, AVAILABLE_W*0.53]))
story.append(Paragraph('Table 10: Risk Analysis Matrix', caption_style))

# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# SECTION 13: APPENDICES
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
story.append(add_heading('13. Appendices', h1_style, 0))

story.append(add_heading('13.1 Provider Feature Comparison Matrix', h2_style, 1))

matrix_data = [
    [Paragraph('<b>Feature</b>', th_style), Paragraph('<b>RuTracker</b>', th_style), Paragraph('<b>RuTor</b>', th_style), Paragraph('<b>NNMClub</b>', th_style), Paragraph('<b>Kinozal</b>', th_style), Paragraph('<b>Archive.org</b>', th_style), Paragraph('<b>Gutenberg</b>', th_style)],
    [Paragraph('Search', td_style), Paragraph('Yes', td_center), Paragraph('Yes', td_center), Paragraph('Yes', td_center), Paragraph('Yes', td_center), Paragraph('Yes', td_center), Paragraph('Yes', td_center)],
    [Paragraph('Browse', td_style), Paragraph('Yes', td_center), Paragraph('Yes', td_center), Paragraph('Yes', td_center), Paragraph('Yes', td_center), Paragraph('Yes', td_center), Paragraph('Yes', td_center)],
    [Paragraph('Forum Tree', td_style), Paragraph('Yes', td_center), Paragraph('No', td_center), Paragraph('Yes', td_center), Paragraph('Partial', td_center), Paragraph('Collections', td_center), Paragraph('Bookshelves', td_center)],
    [Paragraph('Topic Detail', td_style), Paragraph('Yes', td_center), Paragraph('Yes', td_center), Paragraph('Yes', td_center), Paragraph('Yes', td_center), Paragraph('Item', td_center), Paragraph('Book', td_center)],
    [Paragraph('Comments', td_style), Paragraph('Yes', td_center), Paragraph('Yes', td_center), Paragraph('Partial', td_center), Paragraph('Yes', td_center), Paragraph('Reviews', td_center), Paragraph('No', td_center)],
    [Paragraph('Favorites', td_style), Paragraph('Yes', td_center), Paragraph('No', td_center), Paragraph('Partial', td_center), Paragraph('No', td_center), Paragraph('No', td_center), Paragraph('No', td_center)],
    [Paragraph('Torrent DL', td_style), Paragraph('Yes', td_center), Paragraph('Yes', td_center), Paragraph('Yes', td_center), Paragraph('Yes', td_center), Paragraph('Partial', td_center), Paragraph('No', td_center)],
    [Paragraph('Magnet Link', td_style), Paragraph('Yes', td_center), Paragraph('Yes', td_center), Paragraph('Yes', td_center), Paragraph('Partial', td_center), Paragraph('No', td_center), Paragraph('No', td_center)],
    [Paragraph('HTTP DL', td_style), Paragraph('No', td_center), Paragraph('No', td_center), Paragraph('No', td_center), Paragraph('No', td_center), Paragraph('Yes', td_center), Paragraph('Yes', td_center)],
    [Paragraph('Auth Type', td_style), Paragraph('Captcha', td_center), Paragraph('Form', td_center), Paragraph('Form', td_center), Paragraph('Form', td_center), Paragraph('None', td_center), Paragraph('None', td_center)],
    [Paragraph('Encoding', td_style), Paragraph('Win-1251', td_center), Paragraph('UTF-8', td_center), Paragraph('Win-1251', td_center), Paragraph('Win-1251', td_center), Paragraph('UTF-8', td_center), Paragraph('UTF-8', td_center)],
    [Paragraph('RSS', td_style), Paragraph('No', td_center), Paragraph('Yes', td_center), Paragraph('Yes', td_center), Paragraph('No', td_center), Paragraph('No', td_center), Paragraph('OPDS', td_center)],
]
story.append(Spacer(1, 12))
story.append(make_table(matrix_data, [AVAILABLE_W*0.14, AVAILABLE_W*0.12, AVAILABLE_W*0.10, AVAILABLE_W*0.12, AVAILABLE_W*0.12, AVAILABLE_W*0.14, AVAILABLE_W*0.14]))
story.append(Paragraph('Table 11: Complete Provider Feature Comparison Matrix', caption_style))

story.append(add_heading('13.2 New Environment Variables', h2_style, 1))

env_data = [
    [Paragraph('<b>Variable</b>', th_style), Paragraph('<b>Default</b>', th_style), Paragraph('<b>Required</b>', th_style)],
    [Paragraph('LAVA_API_NNMCLUB_URL', td_style), Paragraph('https://nnmclub.to/forum', td_style), Paragraph('No', td_center)],
    [Paragraph('LAVA_API_KINOZAL_URL', td_style), Paragraph('https://kinozal.tv', td_style), Paragraph('No', td_center)],
    [Paragraph('LAVA_API_ARCHIVEORG_URL', td_style), Paragraph('https://archive.org', td_style), Paragraph('No', td_center)],
    [Paragraph('LAVA_API_GUTENBERG_URL', td_style), Paragraph('https://www.gutenberg.org', td_style), Paragraph('No', td_center)],
    [Paragraph('LAVA_API_CREDENTIAL_ENCRYPTION_KEY', td_style), Paragraph('-', td_style), Paragraph('Yes', td_center)],
    [Paragraph('LAVA_API_PROVIDER_TIMEOUT_SECONDS', td_style), Paragraph('30', td_style), Paragraph('No', td_center)],
    [Paragraph('LAVA_API_CONCURRENT_SEARCH_LIMIT', td_style), Paragraph('6', td_style), Paragraph('No', td_center)],
]
story.append(Spacer(1, 12))
story.append(make_table(env_data, [AVAILABLE_W*0.45, AVAILABLE_W*0.35, AVAILABLE_W*0.20]))
story.append(Paragraph('Table 12: New Environment Variables', caption_style))

story.append(add_heading('13.3 New Android Module Dependencies', h2_style, 1))

dep_data = [
    [Paragraph('<b>Module</b>', th_style), Paragraph('<b>New Dependencies</b>', th_style)],
    [Paragraph('core/tracker/nnmclub', td_style), Paragraph('Jsoup, OkHttp, kotlinx-serialization (existing via convention plugin)', td_style)],
    [Paragraph('core/tracker/kinozal', td_style), Paragraph('Jsoup, OkHttp, kotlinx-serialization (existing via convention plugin)', td_style)],
    [Paragraph('core/tracker/archiveorg', td_style), Paragraph('OkHttp, kotlinx-serialization, Moshi/Kotlinx-serialization-json (for JSON APIs)', td_style)],
    [Paragraph('core/tracker/gutenberg', td_style), Paragraph('OkHttp, kotlinx-serialization, Room (FTS5), WorkManager', td_style)],
    [Paragraph('core/credentials', td_style), Paragraph('Room, EncryptedSharedPreferences, Android Keystore, Hilt', td_style)],
    [Paragraph('feature/credentials', td_style), Paragraph('Compose UI, Orbit MVI, Hilt, core/credentials', td_style)],
]
story.append(Spacer(1, 12))
story.append(make_table(dep_data, [AVAILABLE_W*0.30, AVAILABLE_W*0.70]))
story.append(Paragraph('Table 13: New Module Dependencies', caption_style))

# ━━━ Build ━━
doc.multiBuild(story)
print(f"PDF generated: {OUTPUT}")
