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

fig, ax = plt.subplots(1, 1, figsize=(30, 24))
fig.patch.set_facecolor(BG_COLOR)
ax.set_facecolor(BG_COLOR)
ax.set_xlim(0, 30)
ax.set_ylim(0, 24)
ax.axis('off')

def draw_rounded_box(ax, x, y, w, h, label, facecolor, edgecolor=BORDER_COLOR, fontsize=12, fontcolor=TEXT_COLOR, fontweight='normal', alpha=1.0, linewidth=1.5):
    box = FancyBboxPatch((x, y), w, h, boxstyle="round,pad=0.15", facecolor=facecolor, edgecolor=edgecolor, linewidth=linewidth, alpha=alpha)
    ax.add_patch(box)
    ax.text(x + w/2, y + h/2, label, ha='center', va='center', fontsize=fontsize, fontweight=fontweight, color=fontcolor)
    return box

def draw_arrow(ax, x1, y1, x2, y2, color=MUTED, style='->', linewidth=1.5):
    ax.annotate('', xy=(x2, y2), xytext=(x1, y1),
                arrowprops=dict(arrowstyle=style, color=color, lw=linewidth, connectionstyle='arc3,rad=0'))

# ========== TITLE ==========
ax.text(15, 23.3, 'Provider Interface Hierarchy', ha='center', va='center', fontsize=22, fontweight='bold', color=PRIMARY)
ax.plot([2, 28], [22.9, 22.9], color=BORDER_COLOR, linewidth=1.5)

# ========== TRACKERCLIENT ROOT ==========
root_x, root_y, root_w, root_h = 9.5, 21.2, 11.0, 1.5
draw_rounded_box(ax, root_x, root_y, root_w, root_h, 'TrackerClient\n(Root Interface)', PRIMARY, PRIMARY, fontsize=14, fontweight='bold', fontcolor=WHITE, linewidth=2.5)

# Methods on TrackerClient
methods = ['TrackerDescriptor', 'healthCheck()', 'getFeature()']
meth_y = 20.2
for i, m in enumerate(methods):
    mx = 8.0 + i * 4.5
    draw_rounded_box(ax, mx, meth_y, 4.0, 0.7, m, WHITE, PRIMARY, fontsize=10, fontcolor=PRIMARY, linewidth=1.0)
    draw_arrow(ax, root_x + root_w/2, root_y, mx + 2.0, meth_y + 0.7, color=PRIMARY, linewidth=1.0)

# ========== FEATURE INTERFACES ==========
ax.text(15, 19.2, 'Feature Interfaces', ha='center', va='center', fontsize=16, fontweight='bold', color=SECONDARY)

features = [
    ('SearchableTracker', 'search(query)'),
    ('BrowsableTracker', 'browse(category)'),
    ('AuthenticatableTracker', 'authenticate(creds)'),
    ('TopicTracker', 'getTopic(id)'),
    ('CommentsTracker', 'getComments(id)'),
    ('FavoritesTracker', 'getFavorites()'),
    ('DownloadableTracker', 'download(id)')
]

feat_w = 3.5
feat_h = 1.4
feat_y = 17.2
feat_x_start = 1.5

for i, (name, method) in enumerate(features):
    x = feat_x_start + i * (feat_w + 0.35)
    # Interface box
    draw_rounded_box(ax, x, feat_y, feat_w, feat_h, '', WHITE, SECONDARY, linewidth=1.5)
    # Name (top half)
    ax.text(x + feat_w/2, feat_y + feat_h*0.7, name, ha='center', va='center', fontsize=9, fontweight='bold', color=SECONDARY)
    # Method (bottom half)
    ax.text(x + feat_w/2, feat_y + feat_h*0.3, method, ha='center', va='center', fontsize=8, color=MUTED, fontstyle='italic')
    # Dashed line between name and method
    ax.plot([x+0.2, x+feat_w-0.2], [feat_y + feat_h*0.5, feat_y + feat_h*0.5], color=BORDER_COLOR, linewidth=0.8, linestyle='--')
    # Arrow from root
    draw_arrow(ax, root_x + root_w/2, root_y, x + feat_w/2, feat_y + feat_h, color=SECONDARY, linewidth=1.0)

# ========== TRACKER CAPABILITY ENUM ==========
ax.text(4.5, 15.5, 'TrackerCapability Enum', ha='center', va='center', fontsize=14, fontweight='bold', color=HEADER_COLOR)

cap_values = ['SEARCH', 'BROWSE', 'AUTH', 'TOPIC', 'COMMENTS', 'FAVORITES', 'DOWNLOAD']
cap_w = 3.0
cap_h = 0.6
cap_y_start = 14.6
cap_x = 2.0

for i, cap in enumerate(cap_values):
    y = cap_y_start - i * 0.75
    draw_rounded_box(ax, cap_x, y, cap_w, cap_h, cap, LIGHT_HEADER, HEADER_COLOR, fontsize=9, fontcolor=HEADER_COLOR, linewidth=1.0)

# ========== AUTH TYPE ENUM ==========
ax.text(13.5, 15.5, 'AuthType Enum', ha='center', va='center', fontsize=14, fontweight='bold', color=HEADER_COLOR)

auth_values = ['NONE', 'USERNAME_PASSWORD', 'TOKEN', 'API_KEY']
auth_w = 3.5
auth_h = 0.6
auth_y_start = 14.6
auth_x = 11.5

for i, auth in enumerate(auth_values):
    y = auth_y_start - i * 0.75
    draw_rounded_box(ax, auth_x, y, auth_w, auth_h, auth, LIGHT_HEADER, HEADER_COLOR, fontsize=9, fontcolor=HEADER_COLOR, linewidth=1.0)

# ========== PROVIDER-FEATURE MATRIX ==========
ax.text(22, 15.5, 'Provider Implementation Matrix', ha='center', va='center', fontsize=14, fontweight='bold', color=PRIMARY)

providers = ['RuTracker', 'RuTor', 'NNMClub', 'Kinozal', 'ArchiveOrg', 'Gutenberg']
feat_short = ['Search', 'Browse', 'Auth', 'Topic', 'Comments', 'Favs', 'Download']

# Matrix: which provider supports which feature
# Rows: providers, Cols: features
matrix = [
    [1, 1, 1, 1, 1, 1, 1],  # RuTracker
    [1, 1, 0, 1, 0, 0, 1],  # RuTor
    [1, 1, 1, 1, 1, 0, 1],  # NNMClub
    [1, 1, 1, 1, 0, 0, 1],  # Kinozal
    [1, 1, 0, 0, 0, 0, 1],  # ArchiveOrg
    [1, 1, 0, 0, 0, 0, 1],  # Gutenberg
]

cell_w = 1.2
cell_h = 0.9
matrix_x = 15.5
matrix_y = 14.2

# Column headers (features)
for j, feat in enumerate(feat_short):
    x = matrix_x + j * cell_w + cell_w/2
    ax.text(x, matrix_y + 0.3, feat, ha='center', va='center', fontsize=7, fontweight='bold', color=PRIMARY, rotation=45)

# Rows
for i, prov in enumerate(providers):
    y = matrix_y - i * cell_h - cell_h/2
    # Provider name
    ax.text(matrix_x - 0.3, y, prov, ha='right', va='center', fontsize=8, fontweight='bold', color=SECONDARY)
    for j in range(len(feat_short)):
        x = matrix_x + j * cell_w
        if matrix[i][j]:
            color = ACCENT
            label = '✓'
            tcolor = WHITE
        else:
            color = '#e0ddd5'
            label = '—'
            tcolor = MUTED
        draw_rounded_box(ax, x, y - cell_h/2 + 0.1, cell_w - 0.1, cell_h - 0.2, label, color, BORDER_COLOR, fontsize=9, fontcolor=tcolor, fontweight='bold', linewidth=0.8)

# ========== LEGEND ==========
legend_y = 8.2
ax.text(15, legend_y, 'Legend', ha='center', va='center', fontsize=13, fontweight='bold', color=TEXT_COLOR)
draw_rounded_box(ax, 10, legend_y - 1.0, 2.5, 0.6, '✓ Supported', ACCENT, ACCENT, fontsize=9, fontcolor=WHITE, linewidth=1.0)
draw_rounded_box(ax, 13, legend_y - 1.0, 2.5, 0.6, '— Not Supported', '#e0ddd5', BORDER_COLOR, fontsize=9, fontcolor=MUTED, linewidth=1.0)

# ========== INTERFACE DETAILS ==========
detail_y = 6.2
ax.text(15, detail_y, 'Key Design Principles', ha='center', va='center', fontsize=14, fontweight='bold', color=PRIMARY)

principles = [
    'Each feature is an independent interface — providers implement only what they support',
    'getFeature(Type) returns Optional — callers check availability before use',
    'TrackerCapability enum used for compile-time safe feature advertisement',
    'AuthType determines credential requirements per provider',
    'Graceful degradation: unsupported features hidden in UI automatically'
]

for i, p in enumerate(principles):
    y = detail_y - 0.8 - i * 0.7
    ax.text(3, y, f'• {p}', ha='left', va='center', fontsize=10, color=TEXT_COLOR)

plt.tight_layout()
plt.savefig('/home/z/my-project/download/diagrams/provider_interface_hierarchy.png', dpi=300, bbox_inches='tight', facecolor=BG_COLOR)
plt.close()
print("Diagram 2 saved successfully")
