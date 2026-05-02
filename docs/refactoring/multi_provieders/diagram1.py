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

def draw_rounded_box(ax, x, y, w, h, label, facecolor, edgecolor=BORDER_COLOR, fontsize=12, fontcolor=TEXT_COLOR, fontweight='normal', alpha=1.0, linewidth=1.5):
    box = FancyBboxPatch((x, y), w, h, boxstyle="round,pad=0.15", facecolor=facecolor, edgecolor=edgecolor, linewidth=linewidth, alpha=alpha)
    ax.add_patch(box)
    ax.text(x + w/2, y + h/2, label, ha='center', va='center', fontsize=fontsize, fontweight=fontweight, color=fontcolor, wrap=True)
    return box

def draw_arrow(ax, x1, y1, x2, y2, color=MUTED, style='->', linewidth=1.5):
    ax.annotate('', xy=(x2, y2), xytext=(x1, y1),
                arrowprops=dict(arrowstyle=style, color=color, lw=linewidth, connectionstyle='arc3,rad=0'))

# ========== TITLE ==========
ax.text(14, 21.3, 'Multi-Provider Architecture Overview', ha='center', va='center', fontsize=22, fontweight='bold', color=PRIMARY)
ax.plot([2, 26], [20.9, 20.9], color=BORDER_COLOR, linewidth=1.5)

# ========== ANDROID CLIENT APP LAYER ==========
layer_y_top = 20.5
layer_y_bot = 12.8
draw_rounded_box(ax, 0.5, layer_y_bot, 27, layer_y_top - layer_y_bot, '', LIGHT_PRIMARY, PRIMARY, linewidth=2.5, alpha=0.3)
ax.text(1.2, layer_y_top - 0.3, 'Android Client App', ha='left', va='center', fontsize=16, fontweight='bold', color=PRIMARY)

# Feature modules row
features = ['Search', 'Forum', 'Topic', 'Login', 'Tracker\nSettings', 'Account', 'Credentials']
feat_w = 2.8
feat_h = 1.2
feat_y = 18.3
feat_x_start = 2.0
for i, feat in enumerate(features):
    x = feat_x_start + i * (feat_w + 0.3)
    draw_rounded_box(ax, x, feat_y, feat_w, feat_h, feat, WHITE, PRIMARY, fontsize=11, fontweight='bold', fontcolor=PRIMARY, linewidth=1.2)

ax.text(14, feat_y + feat_h + 0.4, 'Feature Modules', ha='center', va='center', fontsize=13, fontweight='bold', color=HEADER_COLOR)

# Arrows from features to LavaTrackerSdk
sdk_y = 16.2
sdk_x = 9.0
sdk_w = 10.0
sdk_h = 1.3
for i in range(len(features)):
    x = feat_x_start + i * (feat_w + 0.3) + feat_w/2
    draw_arrow(ax, x, feat_y, x, sdk_y + sdk_h, color=PRIMARY, linewidth=1.0)

draw_rounded_box(ax, sdk_x, sdk_y, sdk_w, sdk_h, 'LavaTrackerSdk (Facade)', PRIMARY, PRIMARY, fontsize=14, fontweight='bold', fontcolor=WHITE, linewidth=2)

# Tracker Registry
reg_x = sdk_x + sdk_w + 1.0
reg_w = 4.5
draw_rounded_box(ax, reg_x, sdk_y, reg_w, sdk_h, 'Tracker Registry', LIGHT_SECONDARY, SECONDARY, fontsize=12, fontweight='bold', fontcolor=SECONDARY, linewidth=1.5)

draw_arrow(ax, sdk_x + sdk_w, sdk_y + sdk_h/2, reg_x, sdk_y + sdk_h/2, color=SECONDARY, linewidth=1.5, style='<->')

# Per-tracker modules
trackers = ['RuTracker', 'RuTor', 'NNMClub', 'Kinozal', 'ArchiveOrg', 'Gutenberg']
trk_w = 3.3
trk_h = 1.1
trk_y = 13.8
trk_x_start = 2.5

ax.text(14, trk_y + trk_h + 0.5, 'Per-Tracker Modules', ha='center', va='center', fontsize=13, fontweight='bold', color=HEADER_COLOR)

for i, trk in enumerate(trackers):
    x = trk_x_start + i * (trk_w + 0.4)
    draw_rounded_box(ax, x, trk_y, trk_w, trk_h, trk, WHITE, SECONDARY, fontsize=11, fontcolor=SECONDARY, linewidth=1.2)

# Arrows from sdk down to tracker modules
for i in range(len(trackers)):
    x = trk_x_start + i * (trk_w + 0.4) + trk_w/2
    draw_arrow(ax, min(max(x, sdk_x + 1), sdk_x + sdk_w - 1), sdk_y, x, trk_y + trk_h, color=SECONDARY, linewidth=1.0)

# ========== GO API SERVICE LAYER ==========
layer2_y_top = 12.2
layer2_y_bot = 5.5
draw_rounded_box(ax, 0.5, layer2_y_bot, 27, layer2_y_top - layer2_y_bot, '', LIGHT_ACCENT, ACCENT, linewidth=2.5, alpha=0.25)
ax.text(1.2, layer2_y_top - 0.3, 'Go API Service', ha='left', va='center', fontsize=16, fontweight='bold', color='#2a8a5a')

# Handler Layer
handler_y = 10.5
handler_x = 8.5
handler_w = 11.0
handler_h = 1.2
draw_rounded_box(ax, handler_x, handler_y, handler_w, handler_h, 'Handler Layer (HTTP Routes)', WHITE, '#2a8a5a', fontsize=12, fontweight='bold', fontcolor='#2a8a5a', linewidth=1.5)

# Provider Interface
prov_y = 8.8
prov_x = 8.5
prov_w = 11.0
prov_h = 1.2
draw_rounded_box(ax, prov_x, prov_y, prov_w, prov_h, 'Provider Interface', '#2a8a5a', '#2a8a5a', fontsize=12, fontweight='bold', fontcolor=WHITE, linewidth=2)

draw_arrow(ax, handler_x + handler_w/2, handler_y, prov_x + prov_w/2, prov_y + prov_h, color='#2a8a5a', linewidth=1.5)

# Per-provider packages
providers = ['rutracker', 'rutor', 'nnmclub', 'kinozal', 'archiveorg', 'gutenberg']
prv_w = 3.3
prv_h = 1.0
prv_y = 7.0
prv_x_start = 2.5

for i, prv in enumerate(providers):
    x = prv_x_start + i * (prv_w + 0.4)
    draw_rounded_box(ax, x, prv_y, prv_w, prv_h, prv, WHITE, '#2a8a5a', fontsize=10, fontcolor='#2a8a5a', linewidth=1.2)

# Arrows from provider interface to per-provider
for i in range(len(providers)):
    x = prv_x_start + i * (prv_w + 0.4) + prv_w/2
    draw_arrow(ax, min(max(x, prov_x + 1), prov_x + prov_w - 1), prov_y, x, prv_y + prv_h, color='#2a8a5a', linewidth=1.0)

# Cross-cutting concerns
cc_items = ['Cache', 'Auth', 'Rate\nLimiting', 'Observability']
cc_w = 3.5
cc_h = 1.3
cc_y = 5.8
cc_x_start = 4.5
for i, cc in enumerate(cc_items):
    x = cc_x_start + i * (cc_w + 0.8)
    draw_rounded_box(ax, x, cc_y, cc_w, cc_h, cc, LIGHT_HEADER, HEADER_COLOR, fontsize=11, fontweight='bold', fontcolor=HEADER_COLOR, linewidth=1.2)

ax.text(14, cc_y + cc_h + 0.15, 'Cross-Cutting Concerns', ha='center', va='center', fontsize=11, fontweight='bold', color=MUTED)

# ========== DATA SOURCES LAYER ==========
layer3_y_top = 4.8
layer3_y_bot = 1.0
draw_rounded_box(ax, 0.5, layer3_y_bot, 27, layer3_y_top - layer3_y_bot, '', LIGHT_HEADER, HEADER_COLOR, linewidth=2.5, alpha=0.3)
ax.text(1.2, layer3_y_top - 0.3, 'Data Sources', ha='left', va='center', fontsize=16, fontweight='bold', color=HEADER_COLOR)

sources = ['RuTracker.org', 'RuTor.info', 'NNMClub.to', 'Kinozal.tv', 'Archive.org', 'Gutenberg.org']
src_w = 3.3
src_h = 1.2
src_y = 1.8
src_x_start = 2.5

for i, src in enumerate(sources):
    x = src_x_start + i * (src_w + 0.4)
    draw_rounded_box(ax, x, src_y, src_w, src_h, src, HEADER_COLOR, HEADER_COLOR, fontsize=10, fontweight='bold', fontcolor=WHITE, linewidth=1.5)

# Arrows from per-provider to data sources
for i in range(len(sources)):
    x_src = src_x_start + i * (src_w + 0.4) + src_w/2
    x_prv = prv_x_start + i * (prv_w + 0.4) + prv_w/2
    draw_arrow(ax, x_prv, prv_y, x_src, src_y + src_h, color=HEADER_COLOR, linewidth=1.2)

# ========== LAYER FLOW ARROWS (between layers) ==========
# Android -> Go API
draw_arrow(ax, 14, layer_y_bot, 14, layer2_y_top, color=PRIMARY, linewidth=2.5, style='<->')
ax.text(15.5, (layer_y_bot + layer2_y_top)/2, 'HTTP/REST', ha='left', va='center', fontsize=11, color=PRIMARY, fontweight='bold', rotation=90)

# Go API -> Data Sources
draw_arrow(ax, 14, layer2_y_bot, 14, layer3_y_top, color=HEADER_COLOR, linewidth=2.5, style='<->')
ax.text(15.5, (layer2_y_bot + layer3_y_top)/2, 'Scrape/API', ha='left', va='center', fontsize=11, color=HEADER_COLOR, fontweight='bold', rotation=90)

plt.tight_layout()
plt.savefig('/home/z/my-project/download/diagrams/architecture_overview.png', dpi=300, bbox_inches='tight', facecolor=BG_COLOR)
plt.close()
print("Diagram 1 saved successfully")
