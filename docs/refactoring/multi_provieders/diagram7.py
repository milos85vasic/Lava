import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import matplotlib.patches as mpatches
from matplotlib.patches import FancyBboxPatch, FancyArrowPatch
import numpy as np

plt.rcParams['font.sans-serif'] = ['Noto Sans SC', 'DejaVu Sans']
plt.rcParams['axes.unicode_minus'] = False

# Color scheme
PRIMARY = '#217490'
SECONDARY = '#4c30a0'
ACCENT = '#4ece8e'
BG_COLOR = '#f6f6f6'
HEADER_COLOR = '#716950'
BORDER_COLOR = '#c6c2b5'
TEXT_COLOR = '#21211e'
MUTED = '#7e7b74'
LIGHT_PRIMARY = '#d0e8ef'
LIGHT_SECONDARY = '#ddd0f0'
LIGHT_ACCENT = '#d0f0de'
LIGHT_HEADER = '#e8e4d8'
WHITE = '#ffffff'

fig, ax = plt.subplots(1, 1, figsize=(28, 22))
fig.patch.set_facecolor(BG_COLOR)
ax.set_facecolor(BG_COLOR)
ax.set_xlim(0, 28)
ax.set_ylim(0, 22)
ax.axis('off')

def draw_rounded_box(ax, x, y, w, h, label, facecolor, edgecolor=BORDER_COLOR, fontsize=12, fontcolor=TEXT_COLOR, fontweight='normal', alpha=1.0, linewidth=1.5, sublabel=None):
    box = FancyBboxPatch((x, y), w, h, boxstyle="round,pad=0.15", facecolor=facecolor, edgecolor=edgecolor, linewidth=linewidth, alpha=alpha)
    ax.add_patch(box)
    if sublabel:
        ax.text(x + w/2, y + h*0.65, label, ha='center', va='center', fontsize=fontsize, fontweight=fontweight, color=fontcolor)
        ax.text(x + w/2, y + h*0.3, sublabel, ha='center', va='center', fontsize=fontsize-3, color=MUTED, fontstyle='italic')
    else:
        ax.text(x + w/2, y + h/2, label, ha='center', va='center', fontsize=fontsize, fontweight=fontweight, color=fontcolor)
    return box

def draw_arrow(ax, x1, y1, x2, y2, color=MUTED, style='->', linewidth=1.5, label=None, label_offset=(0, 0)):
    ax.annotate('', xy=(x2, y2), xytext=(x1, y1),
                arrowprops=dict(arrowstyle=style, color=color, lw=linewidth, connectionstyle='arc3,rad=0'))
    if label:
        mx = (x1 + x2)/2 + label_offset[0]
        my = (y1 + y2)/2 + label_offset[1]
        ax.text(mx, my, label, ha='center', va='center', fontsize=9, color=color, fontweight='bold',
                bbox=dict(boxstyle='round,pad=0.2', facecolor=BG_COLOR, edgecolor='none', alpha=0.8))

# ========== TITLE ==========
ax.text(14, 21.3, 'Android Navigation Flow (Updated)', ha='center', va='center', fontsize=22, fontweight='bold', color=PRIMARY)
ax.plot([2, 26], [20.9, 20.9], color=BORDER_COLOR, linewidth=1.5)

# ========== BOTTOM TAB BAR ==========
tab_y = 1.0
tab_h = 1.2
tab_w = 5.5
tabs = [
    ('Search', PRIMARY, 'Magnifier icon'),
    ('Forums', SECONDARY, 'Grid icon'),
    ('Downloads', ACCENT, 'Download icon'),
    ('Settings', HEADER_COLOR, 'Gear icon')
]

tab_x_start = 2.5
for i, (tab, col, icon) in enumerate(tabs):
    x = tab_x_start + i * (tab_w + 0.5)
    draw_rounded_box(ax, x, tab_y, tab_w, tab_h, tab, col, col, fontsize=13, fontweight='bold', fontcolor=WHITE, linewidth=2)

# Tab bar background
draw_rounded_box(ax, tab_x_start - 0.5, tab_y - 0.3, tab_w * 4 + 0.5 * 3 + 1.0, tab_h + 0.6, '', LIGHT_HEADER, BORDER_COLOR, linewidth=1.0, alpha=0.3)
ax.text(14, tab_y - 0.1, 'Bottom Navigation Bar', ha='center', va='center', fontsize=10, color=MUTED)

# ========== SEARCH TAB FLOW ==========
# Search tab → Search Screen
search_screen_y = 17.0
search_screen_x = 1.5
search_screen_w = 7.5
search_screen_h = 3.5

draw_rounded_box(ax, search_screen_x, search_screen_y, search_screen_w, search_screen_h, '', LIGHT_PRIMARY, PRIMARY, linewidth=2, alpha=0.3)
ax.text(search_screen_x + 0.3, search_screen_y + search_screen_h - 0.4, 'Search Screen', fontsize=13, fontweight='bold', color=PRIMARY)

# Search bar
draw_rounded_box(ax, search_screen_x + 0.5, search_screen_y + 1.8, 6.5, 0.8, 'Search Query Input', WHITE, PRIMARY, fontsize=10, fontcolor=PRIMARY, linewidth=1.0)
# Provider selector
draw_rounded_box(ax, search_screen_x + 0.5, search_screen_y + 0.8, 6.5, 0.8, 'Provider Selector (multi-select)', LIGHT_SECONDARY, SECONDARY, fontsize=10, fontcolor=SECONDARY, linewidth=1.0)
# Search button
draw_rounded_box(ax, search_screen_x + 0.5, search_screen_y + 0.1, 6.5, 0.6, 'Search Button', PRIMARY, PRIMARY, fontsize=10, fontweight='bold', fontcolor=WHITE, linewidth=1.0)

# Arrow from Search tab to Search screen
draw_arrow(ax, tab_x_start + tab_w/2, tab_y + tab_h, search_screen_x + search_screen_w/2, search_screen_y, color=PRIMARY, linewidth=2.0)

# Search Results screen
results_y = 11.5
results_x = 1.5
results_w = 7.5
results_h = 4.8

draw_rounded_box(ax, results_x, results_y, results_w, results_h, '', LIGHT_PRIMARY, PRIMARY, linewidth=2, alpha=0.3)
ax.text(results_x + 0.3, results_y + results_h - 0.4, 'Search Results', fontsize=13, fontweight='bold', color=PRIMARY)

# Result items with provider badges
result_items = [
    ('Interstellar 4K', 'RuTracker', '#217490'),
    ('Interstellar BluRay', 'NNMClub', '#4ece8e'),
    ('Interstellar 1080p', 'RuTor', '#4c30a0'),
    ('Interstellar REMUX', 'Kinozal', '#716950'),
    ('Interstellar Script', 'Gutenberg', '#8b5e3c'),
]

for i, (title, prov, col) in enumerate(result_items):
    ry = results_y + results_h - 1.2 - i * 0.8
    draw_rounded_box(ax, results_x + 0.3, ry, 5.5, 0.6, title, WHITE, BORDER_COLOR, fontsize=9, fontcolor=TEXT_COLOR, linewidth=0.8)
    draw_rounded_box(ax, results_x + 5.9, ry, 1.3, 0.6, prov, col, col, fontsize=7, fontcolor=WHITE, fontweight='bold', linewidth=0.5)

draw_arrow(ax, search_screen_x + search_screen_w/2, search_screen_y, results_x + results_w/2, results_y + results_h, color=PRIMARY, linewidth=1.5, label='results')

# Detail screen
detail_y = 7.5
detail_x = 1.5
detail_w = 7.5
detail_h = 3.2

draw_rounded_box(ax, detail_x, detail_y, detail_w, detail_h, '', LIGHT_SECONDARY, SECONDARY, linewidth=2, alpha=0.3)
ax.text(detail_x + 0.3, detail_y + detail_h - 0.4, 'Detail Screen (Provider-Specific)', fontsize=12, fontweight='bold', color=SECONDARY)

# Detail contents
details = ['Topic Title & Description', 'Provider Badge', 'Download Actions', 'Comments Section']
for i, d in enumerate(details):
    dy = detail_y + detail_h - 1.2 - i * 0.6
    draw_rounded_box(ax, detail_x + 0.5, dy, 6.5, 0.5, d, WHITE, SECONDARY, fontsize=9, fontcolor=SECONDARY, linewidth=0.8)

draw_arrow(ax, results_x + results_w/2, results_y, detail_x + detail_w/2, detail_y + detail_h, color=SECONDARY, linewidth=1.5, label='tap')

# ========== FORUMS TAB FLOW ==========
forums_y = 16.0
forums_x = 10.5
forums_w = 7.5
forums_h = 4.0

draw_rounded_box(ax, forums_x, forums_y, forums_w, forums_h, '', LIGHT_SECONDARY, SECONDARY, linewidth=2, alpha=0.3)
ax.text(forums_x + 0.3, forums_y + forums_h - 0.4, 'Forums Screen', fontsize=13, fontweight='bold', color=SECONDARY)

# Provider tabs
forum_provs = ['RuTracker', 'NNMClub', 'Kinozal']
for i, prov in enumerate(forum_provs):
    px = forums_x + 0.5 + i * 2.3
    draw_rounded_box(ax, px, forums_y + 2.5, 2.1, 0.6, prov, SECONDARY if i == 0 else WHITE, SECONDARY, fontsize=9, fontweight='bold', fontcolor=WHITE if i == 0 else SECONDARY, linewidth=1.0)

# Forum categories
forum_cats = ['Movies', 'Music', 'Games', 'Software']
for i, cat in enumerate(forum_cats):
    cy = forums_y + 1.8 - i * 0.55
    draw_rounded_box(ax, forums_x + 0.5, cy, 6.5, 0.45, cat, WHITE if i % 2 == 0 else LIGHT_HEADER, BORDER_COLOR, fontsize=9, fontcolor=TEXT_COLOR, linewidth=0.5)

draw_arrow(ax, tab_x_start + tab_w + 0.25 + tab_w/2, tab_y + tab_h, forums_x + forums_w/2, forums_y, color=SECONDARY, linewidth=2.0)

# ========== SETTINGS TAB FLOW ==========
settings_y = 16.0
settings_x = 19.5
settings_w = 7.5
settings_h = 4.0

draw_rounded_box(ax, settings_x, settings_y, settings_w, settings_h, '', LIGHT_HEADER, HEADER_COLOR, linewidth=2, alpha=0.3)
ax.text(settings_x + 0.3, settings_y + settings_h - 0.4, 'Settings Screen', fontsize=13, fontweight='bold', color=HEADER_COLOR)

# Settings items
settings_items = [
    ('Credentials Management', PRIMARY),
    ('Provider Configuration', SECONDARY),
    ('Download Settings', ACCENT),
    ('About', MUTED),
]
for i, (item, col) in enumerate(settings_items):
    sy = settings_y + settings_h - 1.2 - i * 0.7
    draw_rounded_box(ax, settings_x + 0.5, sy, 6.5, 0.55, item, WHITE, col, fontsize=10, fontcolor=col, linewidth=1.0)

draw_arrow(ax, tab_x_start + 3*(tab_w + 0.5) + tab_w/2, tab_y + tab_h, settings_x + settings_w/2, settings_y, color=HEADER_COLOR, linewidth=2.0)

# ========== CREDENTIALS MANAGEMENT SCREEN ==========
cred_y = 10.0
cred_x = 19.5
cred_w = 7.5
cred_h = 5.2

draw_rounded_box(ax, cred_x, cred_y, cred_w, cred_h, '', LIGHT_PRIMARY, PRIMARY, linewidth=2, alpha=0.3)
ax.text(cred_x + 0.3, cred_y + cred_h - 0.4, 'Credentials Management', fontsize=12, fontweight='bold', color=PRIMARY)

# Credential list
cred_items = [
    ('RuTracker: user1', '#217490'),
    ('NNMClub: user2', '#4ece8e'),
    ('Kinozal: user1 (shared)', '#716950'),
]
for i, (item, col) in enumerate(cred_items):
    cy = cred_y + cred_h - 1.3 - i * 0.7
    draw_rounded_box(ax, cred_x + 0.5, cy, 4.5, 0.55, item, WHITE, col, fontsize=9, fontcolor=col, linewidth=0.8)
    # Edit/Delete buttons
    draw_rounded_box(ax, cred_x + 5.2, cy, 1.0, 0.55, 'Edit', LIGHT_PRIMARY, PRIMARY, fontsize=7, fontcolor=PRIMARY, linewidth=0.5)
    draw_rounded_box(ax, cred_x + 6.3, cy, 1.0, 0.55, 'Del', '#f5e8e8', '#c05050', fontsize=7, fontcolor='#c05050', linewidth=0.5)

# Create new button
draw_rounded_box(ax, cred_x + 1.5, cred_y + 0.3, 4.5, 0.7, '+ Create New Credential', ACCENT, ACCENT, fontsize=10, fontweight='bold', fontcolor=WHITE, linewidth=1.5)

draw_arrow(ax, settings_x + settings_w/2 - 1, settings_y, cred_x + cred_w/2, cred_y + cred_h, color=PRIMARY, linewidth=1.5, label='manage')

# ========== PROVIDER CONFIGURATION SCREEN ==========
prov_config_y = 3.5
prov_config_x = 19.5
prov_config_w = 7.5
prov_config_h = 5.7

draw_rounded_box(ax, prov_config_x, prov_config_y, prov_config_w, prov_config_h, '', LIGHT_SECONDARY, SECONDARY, linewidth=2, alpha=0.3)
ax.text(prov_config_x + 0.3, prov_config_y + prov_config_h - 0.4, 'Provider Configuration', fontsize=12, fontweight='bold', color=SECONDARY)

# Provider config items
prov_configs = [
    ('RuTracker', 'Enabled', 'user1 creds', '#217490'),
    ('RuTor', 'Enabled', 'Anonymous', '#4c30a0'),
    ('NNMClub', 'Enabled', 'user2 creds', '#4ece8e'),
    ('Kinozal', 'Disabled', 'user1 creds', '#716950'),
    ('ArchiveOrg', 'Enabled', 'API Key', '#c09050'),
    ('Gutenberg', 'Enabled', 'Anonymous', '#8b5e3c'),
]

for i, (prov, status, creds, col) in enumerate(prov_configs):
    py = prov_config_y + prov_config_h - 1.2 - i * 0.7
    draw_rounded_box(ax, prov_config_x + 0.3, py, 2.2, 0.55, prov, col, col, fontsize=8, fontcolor=WHITE, fontweight='bold', linewidth=0.8)
    draw_rounded_box(ax, prov_config_x + 2.7, py, 1.5, 0.55, status, ACCENT if status == 'Enabled' else '#e0ddd5', ACCENT if status == 'Enabled' else BORDER_COLOR, fontsize=7, fontcolor=WHITE if status == 'Enabled' else MUTED, linewidth=0.5)
    draw_rounded_box(ax, prov_config_x + 4.4, py, 2.8, 0.55, creds, WHITE, BORDER_COLOR, fontsize=7, fontcolor=TEXT_COLOR, linewidth=0.5)

draw_arrow(ax, settings_x + settings_w/2 + 1, settings_y, prov_config_x + prov_config_w/2, prov_config_y + prov_config_h, color=SECONDARY, linewidth=1.5, label='configure')

# ========== LOGIN FLOW ==========
login_y = 11.5
login_x = 10.5
login_w = 7.5
login_h = 3.5

draw_rounded_box(ax, login_x, login_y, login_w, login_h, '', LIGHT_ACCENT, ACCENT, linewidth=2, alpha=0.3)
ax.text(login_x + 0.3, login_y + login_h - 0.4, 'Login Flow', fontsize=13, fontweight='bold', color='#2a8a5a')

# Login steps
login_steps = [
    '1. Select Provider',
    '2. Choose/Add Credentials',
    '3. Authenticate',
]
for i, step in enumerate(login_steps):
    sy = login_y + login_h - 1.3 - i * 0.8
    draw_rounded_box(ax, login_x + 0.5, sy, 6.5, 0.6, step, WHITE, '#2a8a5a', fontsize=10, fontcolor='#2a8a5a', linewidth=1.0)

# Arrow from login to credential association
draw_arrow(ax, login_x + login_w/2, login_y, cred_x + cred_w/2, cred_y + cred_h + 0.5, color=ACCENT, linewidth=1.0, label='add creds', label_offset=(0, 0.3))

# ========== DOWNLOADS TAB ==========
downloads_y = 10.0
downloads_x = 10.5
downloads_w = 7.5
downloads_h = 1.0

draw_rounded_box(ax, downloads_x, downloads_y, downloads_w, downloads_h, 'Downloads Screen\n(Active/Completed with Provider Badges)', WHITE, ACCENT, fontsize=10, fontweight='bold', fontcolor='#2a8a5a', linewidth=1.5)
draw_arrow(ax, tab_x_start + 2*(tab_w + 0.5) + tab_w/2, tab_y + tab_h, downloads_x + downloads_w/2, downloads_y + downloads_h, color=ACCENT, linewidth=2.0)

# ========== FLOW LABELS ==========
# Add descriptive notes
ax.text(5.25, 6.8, 'Provider badges\ndistinguish results\nfrom different sources', ha='center', va='center', fontsize=9, color=MUTED, fontstyle='italic',
        bbox=dict(boxstyle='round,pad=0.3', facecolor=LIGHT_PRIMARY, edgecolor=BORDER_COLOR, alpha=0.5))

ax.text(14.25, 9.0, 'Each provider tab\nshows its own forum\nstructure', ha='center', va='center', fontsize=9, color=MUTED, fontstyle='italic',
        bbox=dict(boxstyle='round,pad=0.3', facecolor=LIGHT_SECONDARY, edgecolor=BORDER_COLOR, alpha=0.5))

ax.text(23.25, 2.8, 'Credentials can be\nshared across providers', ha='center', va='center', fontsize=9, color=MUTED, fontstyle='italic',
        bbox=dict(boxstyle='round,pad=0.3', facecolor=LIGHT_ACCENT, edgecolor=BORDER_COLOR, alpha=0.5))

plt.tight_layout()
plt.savefig('/home/z/my-project/download/diagrams/android_navigation.png', dpi=300, bbox_inches='tight', facecolor=BG_COLOR)
plt.close()
print("Diagram 7 saved successfully")
