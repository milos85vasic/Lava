import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import matplotlib.patches as mpatches
from matplotlib.patches import FancyBboxPatch, Rectangle, FancyArrowPatch
import numpy as np

plt.rcParams['font.sans-serif'] = ['Noto Sans SC', 'DejaVu Sans']
plt.rcParams['axes.unicode_minus'] = False

# ─── Color Palette ───
C_BG        = '#f6f6f6'
C_CARD      = '#ffffff'
C_PRIMARY   = '#217490'
C_ACCENT    = '#4c30a0'
C_SUCCESS   = '#43925d'
C_WARNING   = '#ab8c4d'
C_ERROR     = '#9a4c45'
C_TEXT      = '#21211e'
C_MUTED     = '#7e7b74'
C_BORDER    = '#e0ddd8'
C_DIVIDER   = '#ece9e4'
C_NOTCH     = '#1a1a1a'
C_PHONE_FG  = '#2d2d2d'
C_CHIP_BG   = '#eef4f7'

PROV_COLORS = {
    'RuTracker':  '#3b82f6',
    'RuTor':      '#ef4444',
    'NNMClub':    '#f59e0b',
    'Kinozal':    '#8b5cf6',
    'Archive.org':'#10b981',
    'Gutenberg':  '#6366f1',
}

# Phone dimensions in inches (390x844 px at 96 DPI)
PW = 390 / 96
PH = 844 / 96
NOTCH_W = 120 / 96
NOTCH_H = 28 / 96
CORNER_R = 0.22
STATUS_H = 0.22
NAV_H = 0.35


def draw_phone_frame(ax):
    """Draw a phone-shaped frame with notch."""
    phone = FancyBboxPatch(
        (0, 0), PW, PH,
        boxstyle=f"round,pad=0,rounding_size={CORNER_R}",
        facecolor=C_BG, edgecolor=C_PHONE_FG, linewidth=2.5, zorder=10
    )
    ax.add_patch(phone)

    notch_x = (PW - NOTCH_W) / 2
    notch = FancyBboxPatch(
        (notch_x, PH - NOTCH_H - 0.02), NOTCH_W, NOTCH_H + 0.02,
        boxstyle="round,pad=0,rounding_size=0.08",
        facecolor=C_NOTCH, edgecolor=C_NOTCH, linewidth=1, zorder=12
    )
    ax.add_patch(notch)

    # Status bar text
    ax.text(0.3, PH - 0.32, '9:41', fontsize=7, color='white',
            fontweight='bold', va='center', zorder=13)
    # Battery icon
    ax.add_patch(Rectangle((PW - 0.6, PH - 0.36), 0.2, 0.1,
                           facecolor='white', edgecolor='white', linewidth=0.5, zorder=13))
    ax.add_patch(Rectangle((PW - 0.62, PH - 0.33), 0.03, 0.04,
                           facecolor='white', edgecolor='none', linewidth=0, zorder=13))
    # Signal bars
    for i in range(3):
        h = 0.03 + i * 0.03
        ax.add_patch(Rectangle((PW - 0.95 + i * 0.06, PH - 0.36), 0.04, h,
                               facecolor='white', edgecolor='none', linewidth=0, zorder=13))


def draw_top_app_bar(ax, title, y_top, has_back=False, has_plus=False, has_menu=False):
    """Draw a Material 3 top app bar. Returns y position below bar."""
    bar_h = 0.42
    y_bot = y_top - bar_h

    bar = FancyBboxPatch(
        (0.12, y_bot), PW - 0.24, bar_h,
        boxstyle="round,pad=0,rounding_size=0.02",
        facecolor=C_PRIMARY, edgecolor='none', zorder=15
    )
    ax.add_patch(bar)

    x_offset = 0.25
    if has_back:
        ax.annotate('', xy=(x_offset + 0.14, y_top - bar_h/2),
                    xytext=(x_offset, y_top - bar_h/2),
                    arrowprops=dict(arrowstyle='->', color='white', lw=1.8),
                    zorder=16)
        x_offset += 0.25

    ax.text(x_offset, y_top - bar_h/2, title,
            fontsize=11, color='white', fontweight='bold',
            va='center', zorder=16)

    right_x = PW - 0.25
    if has_plus:
        ax.text(right_x, y_top - bar_h/2, '+', fontsize=16, color='white',
                fontweight='bold', va='center', ha='right', zorder=16)
        right_x -= 0.3
    if has_menu:
        for dy in [-0.06, 0, 0.06]:
            ax.plot(right_x, y_top - bar_h/2 + dy, 'o', color='white',
                    markersize=2.5, zorder=16)

    return y_bot


def draw_card(ax, x, y, w, h, radius=0.06, facecolor=C_CARD, edgecolor=C_BORDER, lw=0.8, zorder=20):
    card = FancyBboxPatch(
        (x, y), w, h,
        boxstyle=f"round,pad=0,rounding_size={radius}",
        facecolor=facecolor, edgecolor=edgecolor, linewidth=lw, zorder=zorder
    )
    ax.add_patch(card)
    return card


def draw_chip(ax, x, y, w, h, label, bg_color, text_color='white', fontsize=5.5, zorder=22):
    chip = FancyBboxPatch(
        (x, y), w, h,
        boxstyle="round,pad=0,rounding_size=0.04",
        facecolor=bg_color, edgecolor='none', linewidth=0, zorder=zorder
    )
    ax.add_patch(chip)
    ax.text(x + w/2, y + h/2, label, fontsize=fontsize, color=text_color,
            ha='center', va='center', fontweight='bold', zorder=zorder+1)


def draw_text_field(ax, x, y, w, h, label='', value='', has_focus=False, zorder=20):
    border_c = C_PRIMARY if has_focus else C_BORDER
    lw_val = 1.5 if has_focus else 0.8
    field = FancyBboxPatch(
        (x, y), w, h,
        boxstyle="round,pad=0,rounding_size=0.05",
        facecolor=C_CARD, edgecolor=border_c, linewidth=lw_val, zorder=zorder
    )
    ax.add_patch(field)
    if label:
        ax.text(x + 0.1, y + h - 0.06, label, fontsize=5, color=C_PRIMARY if has_focus else C_MUTED,
                va='top', zorder=zorder+1)
    if value:
        ax.text(x + 0.1, y + h/2 - 0.02, value, fontsize=6.5, color=C_TEXT,
                va='center', zorder=zorder+1)


def draw_button(ax, x, y, w, h, label, bg_color=C_PRIMARY, text_color='white', outlined=False, zorder=22):
    if outlined:
        btn = FancyBboxPatch(
            (x, y), w, h,
            boxstyle="round,pad=0,rounding_size=0.06",
            facecolor=C_CARD, edgecolor=bg_color, linewidth=1.2, zorder=zorder
        )
        ax.add_patch(btn)
        ax.text(x + w/2, y + h/2, label, fontsize=7, color=bg_color,
                ha='center', va='center', fontweight='bold', zorder=zorder+1)
    else:
        btn = FancyBboxPatch(
            (x, y), w, h,
            boxstyle="round,pad=0,rounding_size=0.06",
            facecolor=bg_color, edgecolor='none', linewidth=0, zorder=zorder
        )
        ax.add_patch(btn)
        ax.text(x + w/2, y + h/2, label, fontsize=7, color=text_color,
                ha='center', va='center', fontweight='bold', zorder=zorder+1)


def draw_fab(ax, x, y, size=0.32, icon='+', zorder=25):
    shadow = FancyBboxPatch(
        (x + 0.015, y - 0.015), size, size,
        boxstyle="round,pad=0,rounding_size=0.08",
        facecolor='#00000018', edgecolor='none', linewidth=0, zorder=zorder-1
    )
    ax.add_patch(shadow)
    fab = FancyBboxPatch(
        (x, y), size, size,
        boxstyle="round,pad=0,rounding_size=0.08",
        facecolor=C_PRIMARY, edgecolor='none', linewidth=0, zorder=zorder
    )
    ax.add_patch(fab)
    ax.text(x + size/2, y + size/2, icon, fontsize=14, color='white',
            ha='center', va='center', fontweight='bold', zorder=zorder+1)


def draw_provider_dot(ax, x, y, color, radius=0.05, zorder=23):
    circle = plt.Circle((x, y), radius, facecolor=color, edgecolor='none', zorder=zorder)
    ax.add_patch(circle)


def draw_toggle(ax, x, y, is_on=False, w=0.22, h=0.1, zorder=22):
    bg = C_PRIMARY if is_on else '#c4c0b8'
    track = FancyBboxPatch(
        (x, y), w, h,
        boxstyle="round,pad=0,rounding_size=0.05",
        facecolor=bg, edgecolor='none', linewidth=0, zorder=zorder
    )
    ax.add_patch(track)
    thumb_x = x + w - h/2 - 0.02 if is_on else x + h/2 + 0.02
    thumb = plt.Circle((thumb_x, y + h/2), h/2 - 0.01,
                       facecolor='white', edgecolor='none', zorder=zorder+1)
    ax.add_patch(thumb)


def draw_divider(ax, x, y, w, zorder=21):
    ax.plot([x, x + w], [y, y], color=C_DIVIDER, linewidth=0.6, zorder=zorder)


def draw_icon_circle(ax, x, y, color, letter, radius=0.14, zorder=21):
    """Draw a colored circle with a letter inside as provider icon."""
    circle = plt.Circle((x, y), radius, facecolor=color, edgecolor='none', zorder=zorder)
    ax.add_patch(circle)
    ax.text(x, y, letter, fontsize=9, color='white',
            ha='center', va='center', fontweight='bold', zorder=zorder+1)


def draw_small_icon(ax, x, y, icon_type, color=C_MUTED, size=7, zorder=22):
    """Draw simple icons using basic shapes and safe characters."""
    s = size * 0.012  # scale factor
    if icon_type == 'edit':
        # Pencil shape: small diagonal line with tip
        ax.plot([x - 2*s, x + 2*s], [y + 2*s, y - 2*s], color=color, linewidth=1.2, zorder=zorder)
        ax.plot([x + s, x + 2*s], [y - s, y - 2*s], color=color, linewidth=1.8, zorder=zorder)
    elif icon_type == 'delete':
        # X shape
        ax.plot([x - 1.5*s, x + 1.5*s], [y - 1.5*s, y + 1.5*s], color=color, linewidth=1.3, zorder=zorder)
        ax.plot([x - 1.5*s, x + 1.5*s], [y + 1.5*s, y - 1.5*s], color=color, linewidth=1.3, zorder=zorder)
    elif icon_type == 'search':
        # Magnifying glass: circle + handle
        circle = plt.Circle((x - s, y), 1.5*s, fill=False, edgecolor=color, linewidth=1.2, zorder=zorder)
        ax.add_patch(circle)
        ax.plot([x + 0.5*s, x + 2*s], [y - 1.5*s, y - 3*s], color=color, linewidth=1.2, zorder=zorder)
    elif icon_type == 'arrow':
        ax.text(x, y, '\u203A', fontsize=size, color=color,
                ha='center', va='center', zorder=zorder)
    elif icon_type == 'link':
        ax.text(x, y, '\u2197', fontsize=size, color=color,
                ha='center', va='center', zorder=zorder)
    elif icon_type == 'eye':
        # Simple eye: ellipse shape
        ax.plot([x - 2*s, x + 2*s], [y, y], color=color, linewidth=1, zorder=zorder)
        circle = plt.Circle((x, y), 1*s, fill=False, edgecolor=color, linewidth=1, zorder=zorder)
        ax.add_patch(circle)
    elif icon_type == 'key':
        # Key shape: circle head + line
        circle = plt.Circle((x - 1.5*s, y), 1.5*s, fill=False, edgecolor=color, linewidth=1.2, zorder=zorder)
        ax.add_patch(circle)
        ax.plot([x, x + 3*s], [y, y], color=color, linewidth=1.2, zorder=zorder)
        ax.plot([x + 2*s, x + 2*s], [y, y - 1.2*s], color=color, linewidth=1.2, zorder=zorder)
        ax.plot([x + 3*s, x + 3*s], [y, y - 1.2*s], color=color, linewidth=1.2, zorder=zorder)
    elif icon_type == 'plus':
        ax.text(x, y, '+', fontsize=size, color=color,
                ha='center', va='center', zorder=zorder)


def setup_wireframe():
    fig_w = PW + 0.4
    fig_h = PH + 0.4
    fig, ax = plt.subplots(1, 1, figsize=(fig_w, fig_h))
    ax.set_xlim(-0.2, PW + 0.2)
    ax.set_ylim(-0.2, PH + 0.2)
    ax.set_aspect('equal')
    ax.axis('off')
    fig.patch.set_facecolor('#e8e6e1')
    return fig, ax


# ════════════════════════════════════════════════════════════════
#  WIREFRAME 1: Credentials Management Screen
# ════════════════════════════════════════════════════════════════
def wireframe_credentials():
    fig, ax = setup_wireframe()
    draw_phone_frame(ax)

    content_top = PH - NOTCH_H - STATUS_H - 0.18

    # Top app bar
    y_below = draw_top_app_bar(ax, 'Credentials', content_top, has_plus=True)

    margin_x = 0.18
    card_w = PW - 2 * margin_x

    credentials = [
        {
            'name': 'Main RuTracker',
            'type': 'Password',
            'type_color': C_PRIMARY,
            'providers': ['RuTracker'],
        },
        {
            'name': 'API Token',
            'type': 'API Key',
            'type_color': C_ACCENT,
            'providers': ['Archive.org', 'Gutenberg'],
        },
        {
            'name': 'NNMClub Login',
            'type': 'Password',
            'type_color': C_PRIMARY,
            'providers': ['NNMClub'],
        },
        {
            'name': 'Kinozal Token',
            'type': 'Token',
            'type_color': C_WARNING,
            'providers': ['Kinozal'],
        },
    ]

    item_h = 0.58
    y = y_below - 0.12

    for cred in credentials:
        draw_card(ax, margin_x, y - item_h, card_w, item_h, zorder=20)

        # Credential name
        ax.text(margin_x + 0.15, y - 0.15, cred['name'],
                fontsize=7.5, color=C_TEXT, fontweight='bold', va='top', zorder=21)

        # Type badge
        draw_chip(ax, margin_x + 0.15, y - 0.4, 0.55, 0.14,
                  cred['type'], bg_color=cred['type_color'], fontsize=5)

        # Provider dots
        px = margin_x + 0.8
        for prov in cred['providers']:
            draw_provider_dot(ax, px, y - 0.33, PROV_COLORS[prov], radius=0.04, zorder=22)
            ax.text(px + 0.07, y - 0.33, prov, fontsize=4.5, color=C_MUTED,
                    va='center', zorder=22)
            px += 0.07 + len(prov) * 0.04 + 0.38

        # Edit icon
        draw_small_icon(ax, margin_x + card_w - 0.48, y - 0.15, 'edit', color=C_MUTED, size=9)

        # Delete icon
        draw_small_icon(ax, margin_x + card_w - 0.22, y - 0.15, 'delete', color=C_ERROR, size=7)

        y -= item_h + 0.08

    # Divider
    draw_divider(ax, margin_x, y + 0.04, card_w)

    # Empty state section
    y -= 0.15
    # Illustration circle
    circle = plt.Circle((PW/2, y - 0.35), 0.25, facecolor='#e8e4df',
                        edgecolor=C_BORDER, linewidth=0.8, zorder=20)
    ax.add_patch(circle)
    draw_small_icon(ax, PW/2, y - 0.35, 'key', color=C_MUTED, size=14)

    ax.text(PW/2, y - 0.75, 'No more credentials', fontsize=8, color=C_TEXT,
            ha='center', fontweight='bold', zorder=21)
    ax.text(PW/2, y - 0.9, 'Create your first credential to\nget started with providers',
            fontsize=6, color=C_MUTED, ha='center', va='top', zorder=21, linespacing=1.4)

    draw_button(ax, PW/2 - 0.55, y - 1.3, 1.1, 0.28,
                '+ Create Credential', bg_color=C_PRIMARY, zorder=22)

    # FAB
    draw_fab(ax, PW - 0.18 - 0.32, 0.18 + NAV_H + 0.08, size=0.35)

    # Bottom Nav
    nav_y = 0.12
    draw_card(ax, 0.12, nav_y, PW - 0.24, NAV_H, radius=0.08, zorder=25)
    nav_items = [('Search', False), ('Forums', False), ('Settings', True)]
    for i, (label, active) in enumerate(nav_items):
        nx = PW * (i + 0.5) / 3
        color = C_PRIMARY if active else C_MUTED
        # Simple icon shapes
        icon_shapes = [
            lambda x, y: ax.add_patch(plt.Circle((x, y), 0.04, fill=False, edgecolor=color, linewidth=1.2, zorder=26)),
            lambda x, y: ax.add_patch(Rectangle((x-0.05, y-0.05), 0.1, 0.1, fill=False, edgecolor=color, linewidth=1.2, zorder=26)),
            lambda x, y: ax.add_patch(plt.Circle((x, y+0.02), 0.03, fill=False, edgecolor=color, linewidth=1.2, zorder=26)),
        ]
        icon_shapes[i](nx, nav_y + NAV_H * 0.68)
        ax.text(nx, nav_y + NAV_H * 0.22, label, fontsize=5, color=color,
                ha='center', va='center', zorder=26)

    fig.savefig('/home/z/my-project/download/diagrams/wireframe_credentials.png',
                dpi=300, bbox_inches='tight', pad_inches=0.05, facecolor=fig.get_facecolor())
    plt.close(fig)
    print("  wireframe_credentials.png")


# ════════════════════════════════════════════════════════════════
#  WIREFRAME 2: Create/Edit Credential Dialog
# ════════════════════════════════════════════════════════════════
def wireframe_credential_edit():
    fig, ax = setup_wireframe()
    draw_phone_frame(ax)

    content_top = PH - NOTCH_H - STATUS_H - 0.18

    # Top app bar (back arrow)
    y_below = draw_top_app_bar(ax, 'New Credential', content_top, has_back=True)

    margin_x = 0.18
    card_w = PW - 2 * margin_x

    # Dimmed background overlay
    overlay = Rectangle((0.12, 0.12), PW - 0.24, y_below - 0.12,
                        facecolor='#00000020', edgecolor='none', zorder=18)
    ax.add_patch(overlay)

    # Bottom Sheet
    sheet_h = 5.8
    sheet_y = 0.12 + 0.15
    draw_card(ax, 0.12, sheet_y, PW - 0.24, sheet_h, radius=0.12,
              facecolor=C_CARD, edgecolor=C_BORDER, lw=1, zorder=20)

    # Drag handle
    draw_card(ax, PW/2 - 0.2, sheet_y + sheet_h - 0.14, 0.4, 0.05,
              radius=0.025, facecolor='#c4c0b8', edgecolor='none', zorder=21)

    # Title
    ax.text(PW/2, sheet_y + sheet_h - 0.32, 'Create Credential',
            fontsize=10, color=C_TEXT, fontweight='bold',
            ha='center', va='top', zorder=21)

    sx = 0.35
    sw = PW - 0.7

    # Label/Name field
    cy = sheet_y + sheet_h - 0.62
    draw_text_field(ax, sx, cy - 0.35, sw, 0.35, label='Label / Name', value='My RuTracker Login', has_focus=True)

    # Credential Type selector
    cy -= 0.6
    ax.text(sx, cy, 'Credential Type', fontsize=6.5, color=C_TEXT,
            fontweight='bold', va='top', zorder=21)

    cy -= 0.25
    chip_w = sw / 3 - 0.03
    types = [
        ('Password', C_PRIMARY, True),
        ('Token', '#c4c0b8', False),
        ('API Key', '#c4c0b8', False),
    ]
    for i, (label, bg, active) in enumerate(types):
        cx = sx + i * (chip_w + 0.03)
        if active:
            draw_chip(ax, cx, cy, chip_w, 0.2, label, bg_color=C_PRIMARY, text_color='white', fontsize=6, zorder=22)
        else:
            chip = FancyBboxPatch(
                (cx, cy), chip_w, 0.2,
                boxstyle="round,pad=0,rounding_size=0.04",
                facecolor=C_CARD, edgecolor=C_BORDER, linewidth=0.8, zorder=22
            )
            ax.add_patch(chip)
            ax.text(cx + chip_w/2, cy + 0.1, label, fontsize=6, color=C_MUTED,
                    ha='center', va='center', zorder=23)

    # Conditional fields for "Password" type
    cy -= 0.38
    ax.text(sx, cy + 0.06, 'Password fields (active)', fontsize=5.5, color=C_ACCENT,
            fontweight='bold', va='bottom', zorder=21)

    cy -= 0.35
    draw_text_field(ax, sx, cy - 0.35, sw, 0.35, label='Username', value='user123', has_focus=False)

    cy -= 0.52
    draw_text_field(ax, sx, cy - 0.35, sw, 0.35, label='Password', value='\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022', has_focus=False)
    # Eye icon
    draw_small_icon(ax, sx + sw - 0.15, cy - 0.17, 'eye', color=C_MUTED, size=8)

    # Divider
    cy -= 0.48
    draw_divider(ax, sx, cy, sw)

    # Token fields (dimmed alternative)
    cy -= 0.15
    ax.text(sx, cy, 'Token fields (if Token selected)', fontsize=5, color=C_MUTED,
            fontstyle='italic', va='top', zorder=21)
    cy -= 0.3
    draw_text_field(ax, sx, cy - 0.3, sw, 0.3, label='Token Value', value='', has_focus=False)
    dim_rect = Rectangle((sx, cy - 0.32), sw, 0.38,
                         facecolor='#ffffffb0', edgecolor='none', zorder=22)
    ax.add_patch(dim_rect)

    # API Key fields (dimmed alternative)
    cy -= 0.5
    ax.text(sx, cy, 'API Key fields (if API Key selected)', fontsize=5, color=C_MUTED,
            fontstyle='italic', va='top', zorder=21)
    cy -= 0.3
    draw_text_field(ax, sx, cy - 0.3, sw, 0.3, label='API Key', value='', has_focus=False)
    cy -= 0.42
    draw_text_field(ax, sx, cy - 0.3, sw, 0.3, label='Secret (optional)', value='', has_focus=False)
    dim_rect2 = Rectangle((sx, cy - 0.32), sw, 0.7,
                          facecolor='#ffffffb0', edgecolor='none', zorder=22)
    ax.add_patch(dim_rect2)

    # Buttons
    cy -= 0.5
    btn_w = (sw - 0.12) / 2
    draw_button(ax, sx, cy - 0.28, btn_w, 0.28, 'Cancel',
                bg_color=C_MUTED, outlined=True, zorder=22)
    draw_button(ax, sx + btn_w + 0.12, cy - 0.28, btn_w, 0.28, 'Save',
                bg_color=C_PRIMARY, zorder=22)

    fig.savefig('/home/z/my-project/download/diagrams/wireframe_credential_edit.png',
                dpi=300, bbox_inches='tight', pad_inches=0.05, facecolor=fig.get_facecolor())
    plt.close(fig)
    print("  wireframe_credential_edit.png")


# ════════════════════════════════════════════════════════════════
#  WIREFRAME 3: Provider Selection on Login
# ════════════════════════════════════════════════════════════════
def wireframe_provider_login():
    fig, ax = setup_wireframe()
    draw_phone_frame(ax)

    content_top = PH - NOTCH_H - STATUS_H - 0.18

    y_below = draw_top_app_bar(ax, 'Select Provider', content_top, has_back=True)

    margin_x = 0.18
    card_w = PW - 2 * margin_x

    # Anonymous Access toggle
    cy = y_below - 0.18
    draw_card(ax, margin_x, cy - 0.3, card_w, 0.3, zorder=20)
    ax.text(margin_x + 0.15, cy - 0.15, 'Anonymous Access', fontsize=7, color=C_TEXT,
            va='center', fontweight='bold', zorder=21)
    draw_toggle(ax, margin_x + card_w - 0.4, cy - 0.2, is_on=False, zorder=22)

    ax.text(margin_x + 0.15, cy - 0.28, 'Some providers support browsing without login',
            fontsize=5, color=C_MUTED, va='top', zorder=21)

    providers = [
        {'name': 'RuTracker',  'letter': 'RT', 'type': 'Tracker',     'color': PROV_COLORS['RuTracker']},
        {'name': 'RuTor',      'letter': 'Rr', 'type': 'Tracker',     'color': PROV_COLORS['RuTor']},
        {'name': 'NNMClub',    'letter': 'NM', 'type': 'Tracker',     'color': PROV_COLORS['NNMClub']},
        {'name': 'Kinozal',    'letter': 'Kz', 'type': 'Tracker',     'color': PROV_COLORS['Kinozal']},
        {'name': 'Archive.org','letter': 'Ar', 'type': 'HTTP Library','color': PROV_COLORS['Archive.org']},
        {'name': 'Gutenberg',  'letter': 'Gt', 'type': 'HTTP Library','color': PROV_COLORS['Gutenberg']},
    ]

    item_h = 0.85
    cy = cy - 0.42

    for prov in providers:
        draw_card(ax, margin_x, cy - item_h, card_w, item_h, zorder=20)

        # Provider icon circle
        draw_icon_circle(ax, margin_x + 0.22, cy - 0.22, prov['color'], prov['letter'], radius=0.14)

        # Provider name
        ax.text(margin_x + 0.48, cy - 0.12, prov['name'],
                fontsize=7.5, color=C_TEXT, fontweight='bold', va='top', zorder=21)

        # Type badge
        badge_bg = '#3b82f620' if prov['type'] == 'Tracker' else '#10b98120'
        badge_tc = '#3b82f6' if prov['type'] == 'Tracker' else '#10b981'
        draw_chip(ax, margin_x + 0.48, cy - 0.38, 0.65, 0.16,
                  prov['type'], bg_color=badge_bg, text_color=badge_tc, fontsize=5, zorder=22)

        # Credential selector dropdown
        dd_x = margin_x + 1.25
        dd_y = cy - 0.58
        dd_w = card_w - 1.45
        dd_h = 0.22
        dd = FancyBboxPatch(
            (dd_x, dd_y), dd_w, dd_h,
            boxstyle="round,pad=0,rounding_size=0.04",
            facecolor=C_CHIP_BG, edgecolor=C_BORDER, linewidth=0.6, zorder=21
        )
        ax.add_patch(dd)
        ax.text(dd_x + 0.08, dd_y + dd_h/2, 'Select credentials...', fontsize=5.5,
                color=C_MUTED, va='center', zorder=22)
        ax.text(dd_x + dd_w - 0.1, dd_y + dd_h/2, '\u25BE', fontsize=7, color=C_MUTED,
                ha='center', va='center', zorder=22)

        # "Create New" link
        ax.text(dd_x + dd_w - 0.55, dd_y - 0.1, '+ Create New', fontsize=5,
                color=C_PRIMARY, fontweight='bold', va='top', zorder=22)

        cy -= item_h + 0.06

    # Login button
    cy -= 0.08
    draw_button(ax, PW/2 - 0.8, cy - 0.3, 1.6, 0.3,
                'Login to Selected', bg_color=C_PRIMARY, zorder=22)

    fig.savefig('/home/z/my-project/download/diagrams/wireframe_provider_login.png',
                dpi=300, bbox_inches='tight', pad_inches=0.05, facecolor=fig.get_facecolor())
    plt.close(fig)
    print("  wireframe_provider_login.png")


# ════════════════════════════════════════════════════════════════
#  WIREFRAME 4: Unified Search Screen
# ════════════════════════════════════════════════════════════════
def wireframe_unified_search():
    fig, ax = setup_wireframe()
    draw_phone_frame(ax)

    content_top = PH - NOTCH_H - STATUS_H - 0.18

    y_below = draw_top_app_bar(ax, 'Search', content_top, has_menu=True)

    margin_x = 0.18
    card_w = PW - 2 * margin_x

    # Search bar
    cy = y_below - 0.18
    draw_card(ax, margin_x, cy - 0.32, card_w, 0.32, radius=0.08, zorder=20)
    draw_small_icon(ax, margin_x + 0.18, cy - 0.16, 'search', color=C_MUTED, size=10)
    ax.text(margin_x + 0.38, cy - 0.16, 'Search all providers...', fontsize=7,
            color=C_MUTED, va='center', zorder=21)

    # Provider filter chips
    cy -= 0.48
    chip_data = [
        ('All', C_CHIP_BG, C_TEXT, True),
        ('RuTracker', PROV_COLORS['RuTracker'], 'white', True),
        ('RuTor', PROV_COLORS['RuTor'], 'white', True),
        ('NNMClub', '#e8e4df', C_MUTED, False),
        ('Kinozal', PROV_COLORS['Kinozal'], 'white', True),
        ('Archive.org', PROV_COLORS['Archive.org'], 'white', True),
        ('Gutenberg', '#e8e4df', C_MUTED, False),
    ]

    chip_x = margin_x + 0.05
    for label, bg, tc, active in chip_data:
        cw = 0.15 + len(label) * 0.055
        draw_chip(ax, chip_x, cy - 0.17, cw, 0.17, label, bg_color=bg, text_color=tc, fontsize=5, zorder=22)
        if active and label != 'All':
            ax.text(chip_x + cw - 0.06, cy - 0.05, '\u2713', fontsize=5, color=tc,
                    ha='center', va='center', fontweight='bold', zorder=23)
        chip_x += cw + 0.04
        if chip_x > PW - margin_x - 0.3:
            break

    # Loading indicator
    cy -= 0.28
    ax.text(margin_x + 0.15, cy, 'Results', fontsize=7, color=C_TEXT,
            fontweight='bold', va='top', zorder=21)

    # Loading bar for RuTor
    cy -= 0.22
    ax.text(margin_x + 0.15, cy, 'RuTor', fontsize=5, color=PROV_COLORS['RuTor'],
            fontweight='bold', va='top', zorder=21)
    bar_y = cy - 0.12
    bar_bg = FancyBboxPatch(
        (margin_x + 0.15, bar_y), card_w - 0.3, 0.04,
        boxstyle="round,pad=0,rounding_size=0.02",
        facecolor='#e0ddd8', edgecolor='none', zorder=22
    )
    ax.add_patch(bar_bg)
    bar_fill = FancyBboxPatch(
        (margin_x + 0.15, bar_y), (card_w - 0.3) * 0.6, 0.04,
        boxstyle="round,pad=0,rounding_size=0.02",
        facecolor=PROV_COLORS['RuTor'], edgecolor='none', zorder=23
    )
    ax.add_patch(bar_fill)

    # Search results
    results = [
        {
            'title': 'The Great Gatsby - Fitzgerald',
            'provider': 'Archive.org',
            'meta': 'EPUB \u00B7 1.2 MB',
            'format': 'EPUB',
            'type': 'book',
        },
        {
            'title': '1984 - George Orwell (RTF, FB2, EPUB)',
            'provider': 'RuTracker',
            'meta_size': '4.7 GB',
            'seeds': '234',
            'leeches': '12',
            'type': 'tracker',
        },
        {
            'title': 'Brave New World - Huxley',
            'provider': 'Gutenberg',
            'meta': 'PDF \u00B7 3.8 MB',
            'format': 'PDF',
            'type': 'book',
        },
        {
            'title': 'Sci-Fi Collection 2024',
            'provider': 'NNMClub',
            'meta_size': '12.3 GB',
            'seeds': '89',
            'leeches': '5',
            'type': 'tracker',
        },
        {
            'title': 'Dune - Frank Herbert (MOBI)',
            'provider': 'Archive.org',
            'meta': 'MOBI \u00B7 2.1 MB',
            'format': 'MOBI',
            'type': 'book',
        },
        {
            'title': 'Fantasy Books Pack vol.3',
            'provider': 'Kinozal',
            'meta_size': '8.9 GB',
            'seeds': '156',
            'leeches': '23',
            'type': 'tracker',
        },
    ]

    cy -= 0.22
    item_h = 0.52
    for res in results:
        if cy - item_h < 0.5:
            break
        draw_card(ax, margin_x, cy - item_h, card_w, item_h, zorder=20)

        # Provider dot
        draw_provider_dot(ax, margin_x + 0.15, cy - 0.17, PROV_COLORS[res['provider']],
                         radius=0.05, zorder=22)

        # Title
        ax.text(margin_x + 0.32, cy - 0.1, res['title'],
                fontsize=6.5, color=C_TEXT, fontweight='bold', va='top', zorder=21)

        if res['type'] == 'tracker':
            ax.text(margin_x + 0.32, cy - 0.32, res['meta_size'], fontsize=5,
                    color=C_MUTED, va='top', zorder=21)
            ax.text(margin_x + 1.0, cy - 0.32, '\u2191', fontsize=7, color=C_SUCCESS,
                    va='top', zorder=21)
            ax.text(margin_x + 1.12, cy - 0.32, res['seeds'], fontsize=5,
                    color=C_SUCCESS, va='top', zorder=21)
            ax.text(margin_x + 1.5, cy - 0.32, '\u2193', fontsize=7, color=C_ERROR,
                    va='top', zorder=21)
            ax.text(margin_x + 1.62, cy - 0.32, res['leeches'], fontsize=5,
                    color=C_ERROR, va='top', zorder=21)
        else:
            fmt = res.get('format', '')
            if fmt:
                draw_chip(ax, margin_x + 0.32, cy - 0.42, 0.32, 0.14,
                          fmt, bg_color='#eef0f8', text_color=C_ACCENT, fontsize=4.5, zorder=22)
            meta = res.get('meta', '')
            if '\u00B7' in meta:
                size_part = meta.split('\u00B7')[1].strip()
                ax.text(margin_x + 0.72, cy - 0.35, size_part, fontsize=5,
                        color=C_MUTED, va='top', zorder=21)

        # Link icon
        draw_small_icon(ax, margin_x + card_w - 0.18, cy - 0.26, 'link', color=C_MUTED, size=9)

        cy -= item_h + 0.06

    # Bottom nav
    nav_y = 0.12
    draw_card(ax, 0.12, nav_y, PW - 0.24, NAV_H, radius=0.08, zorder=25)
    nav_items = [('Search', True), ('Forums', False), ('Settings', False)]
    for i, (label, active) in enumerate(nav_items):
        nx = PW * (i + 0.5) / 3
        color = C_PRIMARY if active else C_MUTED
        icon_shapes = [
            lambda x, y: ax.add_patch(plt.Circle((x, y), 0.04, fill=False, edgecolor=color, linewidth=1.2, zorder=26)),
            lambda x, y: ax.add_patch(Rectangle((x-0.05, y-0.05), 0.1, 0.1, fill=False, edgecolor=color, linewidth=1.2, zorder=26)),
            lambda x, y: ax.add_patch(plt.Circle((x, y+0.02), 0.03, fill=False, edgecolor=color, linewidth=1.2, zorder=26)),
        ]
        icon_shapes[i](nx, nav_y + NAV_H * 0.68)
        ax.text(nx, nav_y + NAV_H * 0.22, label, fontsize=5, color=color,
                ha='center', va='center', zorder=26)

    fig.savefig('/home/z/my-project/download/diagrams/wireframe_unified_search.png',
                dpi=300, bbox_inches='tight', pad_inches=0.05, facecolor=fig.get_facecolor())
    plt.close(fig)
    print("  wireframe_unified_search.png")


# ════════════════════════════════════════════════════════════════
#  WIREFRAME 5: Unified Forums Screen
# ════════════════════════════════════════════════════════════════
def wireframe_unified_forums():
    fig, ax = setup_wireframe()
    draw_phone_frame(ax)

    content_top = PH - NOTCH_H - STATUS_H - 0.18

    y_below = draw_top_app_bar(ax, 'Forums', content_top, has_menu=True)

    margin_x = 0.18
    card_w = PW - 2 * margin_x

    # Provider tabs/filter chips
    cy = y_below - 0.15
    tabs = [
        ('All Sources', C_PRIMARY, 'white', True),
        ('RuTracker', PROV_COLORS['RuTracker'], 'white', False),
        ('RuTor', PROV_COLORS['RuTor'], 'white', False),
        ('NNMClub', PROV_COLORS['NNMClub'], 'white', False),
        ('Kinozal', PROV_COLORS['Kinozal'], 'white', False),
        ('Archive.org', PROV_COLORS['Archive.org'], 'white', False),
        ('Gutenberg', PROV_COLORS['Gutenberg'], 'white', False),
    ]

    tab_x = margin_x
    for label, bg, tc, active in tabs:
        if not active:
            bg = C_CHIP_BG
            tc = C_TEXT
        tw = 0.12 + len(label) * 0.055
        draw_chip(ax, tab_x, cy - 0.19, tw, 0.19, label, bg_color=bg, text_color=tc, fontsize=5, zorder=22)
        tab_x += tw + 0.04

    # Active indicator line under "All Sources"
    ax.plot([margin_x, margin_x + 0.6], [cy - 0.21, cy - 0.21],
            color=C_PRIMARY, linewidth=2, zorder=23)

    cy -= 0.35

    # Tracker Forums Section
    ax.text(margin_x + 0.1, cy, 'Tracker Forums', fontsize=7.5, color=C_TEXT,
            fontweight='bold', va='top', zorder=21)

    tracker_categories = [
        ('Foreign Literature', 'RuTracker', '2.4K topics'),
        ('Russian Literature', 'RuTracker', '1.8K topics'),
        ('Audiobooks', 'RuTracker', '3.1K topics'),
        ('Foreign Movies', 'NNMClub', '5.6K topics'),
        ('Literature', 'NNMClub', '1.2K topics'),
        ('Cinema & Video', 'Kinozal', '8.9K topics'),
        ('Literature & Audio', 'Kinozal', '2.3K topics'),
    ]

    cy -= 0.2
    item_h = 0.38
    for cat, prov, count in tracker_categories:
        draw_card(ax, margin_x, cy - item_h, card_w, item_h, zorder=20)
        draw_provider_dot(ax, margin_x + 0.15, cy - 0.19, PROV_COLORS[prov],
                         radius=0.04, zorder=22)
        ax.text(margin_x + 0.3, cy - 0.12, cat, fontsize=6.5, color=C_TEXT,
                fontweight='bold', va='top', zorder=21)
        ax.text(margin_x + 0.3, cy - 0.28, prov, fontsize=5, color=PROV_COLORS[prov],
                va='top', zorder=21)
        ax.text(margin_x + card_w - 0.2, cy - 0.19, count, fontsize=5,
                color=C_MUTED, ha='right', va='center', zorder=21)
        draw_small_icon(ax, margin_x + card_w - 0.08, cy - 0.19, 'arrow', color=C_MUTED, size=10)

        cy -= item_h + 0.05

    # Archive.org Collections Section
    cy -= 0.12
    ax.text(margin_x + 0.1, cy, 'Archive.org Collections', fontsize=7.5,
            color=PROV_COLORS['Archive.org'], fontweight='bold', va='top', zorder=21)

    archive_cats = [
        ('Open Library', 'Archive.org', '4.2M items'),
        ('American Libraries', 'Archive.org', '2.8M items'),
        ('Books to Borrow', 'Archive.org', '1.5M items'),
    ]

    cy -= 0.2
    for cat, prov, count in archive_cats:
        draw_card(ax, margin_x, cy - item_h, card_w, item_h, zorder=20)
        draw_provider_dot(ax, margin_x + 0.15, cy - 0.19, PROV_COLORS[prov],
                         radius=0.04, zorder=22)
        ax.text(margin_x + 0.3, cy - 0.12, cat, fontsize=6.5, color=C_TEXT,
                fontweight='bold', va='top', zorder=21)
        ax.text(margin_x + 0.3, cy - 0.28, prov, fontsize=5, color=PROV_COLORS[prov],
                va='top', zorder=21)
        ax.text(margin_x + card_w - 0.2, cy - 0.19, count, fontsize=5,
                color=C_MUTED, ha='right', va='center', zorder=21)
        draw_small_icon(ax, margin_x + card_w - 0.08, cy - 0.19, 'arrow', color=C_MUTED, size=10)
        cy -= item_h + 0.05

    # Gutenberg Bookshelves Section
    cy -= 0.12
    ax.text(margin_x + 0.1, cy, 'Gutenberg Bookshelves', fontsize=7.5,
            color=PROV_COLORS['Gutenberg'], fontweight='bold', va='top', zorder=21)

    guten_cats = [
        ('Best Books Ever', 'Gutenberg', '5.2K books'),
        ('Science Fiction', 'Gutenberg', '890 books'),
    ]

    cy -= 0.2
    for cat, prov, count in guten_cats:
        draw_card(ax, margin_x, cy - item_h, card_w, item_h, zorder=20)
        draw_provider_dot(ax, margin_x + 0.15, cy - 0.19, PROV_COLORS[prov],
                         radius=0.04, zorder=22)
        ax.text(margin_x + 0.3, cy - 0.12, cat, fontsize=6.5, color=C_TEXT,
                fontweight='bold', va='top', zorder=21)
        ax.text(margin_x + 0.3, cy - 0.28, prov, fontsize=5, color=PROV_COLORS[prov],
                va='top', zorder=21)
        ax.text(margin_x + card_w - 0.2, cy - 0.19, count, fontsize=5,
                color=C_MUTED, ha='right', va='center', zorder=21)
        draw_small_icon(ax, margin_x + card_w - 0.08, cy - 0.19, 'arrow', color=C_MUTED, size=10)
        cy -= item_h + 0.05

    # Bottom nav
    nav_y = 0.12
    draw_card(ax, 0.12, nav_y, PW - 0.24, NAV_H, radius=0.08, zorder=25)
    nav_items = [('Search', False), ('Forums', True), ('Settings', False)]
    for i, (label, active) in enumerate(nav_items):
        nx = PW * (i + 0.5) / 3
        color = C_PRIMARY if active else C_MUTED
        icon_shapes = [
            lambda x, y: ax.add_patch(plt.Circle((x, y), 0.04, fill=False, edgecolor=color, linewidth=1.2, zorder=26)),
            lambda x, y: ax.add_patch(Rectangle((x-0.05, y-0.05), 0.1, 0.1, fill=False, edgecolor=color, linewidth=1.2, zorder=26)),
            lambda x, y: ax.add_patch(plt.Circle((x, y+0.02), 0.03, fill=False, edgecolor=color, linewidth=1.2, zorder=26)),
        ]
        icon_shapes[i](nx, nav_y + NAV_H * 0.68)
        ax.text(nx, nav_y + NAV_H * 0.22, label, fontsize=5, color=color,
                ha='center', va='center', zorder=26)

    fig.savefig('/home/z/my-project/download/diagrams/wireframe_unified_forums.png',
                dpi=300, bbox_inches='tight', pad_inches=0.05, facecolor=fig.get_facecolor())
    plt.close(fig)
    print("  wireframe_unified_forums.png")


# ════════════════════════════════════════════════════════════════
#  WIREFRAME 6: Provider Configuration Screen
# ════════════════════════════════════════════════════════════════
def wireframe_provider_config():
    fig, ax = setup_wireframe()
    draw_phone_frame(ax)

    content_top = PH - NOTCH_H - STATUS_H - 0.18

    y_below = draw_top_app_bar(ax, 'Provider Settings', content_top, has_back=True)

    margin_x = 0.18
    card_w = PW - 2 * margin_x

    # Provider Card at Top
    cy = y_below - 0.15
    draw_card(ax, margin_x, cy - 0.55, card_w, 0.55, zorder=20)

    draw_icon_circle(ax, margin_x + 0.3, cy - 0.275, PROV_COLORS['RuTracker'], 'RT', radius=0.18)

    ax.text(margin_x + 0.6, cy - 0.15, 'RuTracker', fontsize=10, color=C_TEXT,
            fontweight='bold', va='top', zorder=21)
    draw_chip(ax, margin_x + 0.6, cy - 0.38, 0.55, 0.16,
              'Tracker', bg_color='#3b82f625', text_color='#3b82f6', fontsize=5, zorder=22)

    # Status indicator
    ax.text(margin_x + card_w - 0.18, cy - 0.12, '\u25CF', fontsize=7,
            color=C_SUCCESS, ha='right', va='top', zorder=21)
    ax.text(margin_x + card_w - 0.3, cy - 0.12, 'Connected', fontsize=5.5,
            color=C_SUCCESS, ha='right', va='top', zorder=21)

    # Authentication Section
    cy -= 0.72
    ax.text(margin_x + 0.05, cy, 'Authentication', fontsize=7.5, color=C_PRIMARY,
            fontweight='bold', va='top', zorder=21)

    cy -= 0.2
    draw_card(ax, margin_x, cy - 0.85, card_w, 0.85, zorder=20)

    ax.text(margin_x + 0.15, cy - 0.1, 'Credential', fontsize=6, color=C_MUTED,
            va='top', zorder=21)
    dd_y = cy - 0.35
    dd = FancyBboxPatch(
        (margin_x + 0.15, dd_y), card_w - 0.3, 0.24,
        boxstyle="round,pad=0,rounding_size=0.05",
        facecolor=C_CHIP_BG, edgecolor=C_BORDER, linewidth=0.6, zorder=21
    )
    ax.add_patch(dd)
    ax.text(margin_x + 0.25, dd_y + 0.12, 'Main RuTracker', fontsize=6,
            color=C_TEXT, va='center', zorder=22)
    ax.text(margin_x + card_w - 0.2, dd_y + 0.12, '\u25BE', fontsize=7,
            color=C_MUTED, ha='center', va='center', zorder=22)

    ax.text(margin_x + 0.15, cy - 0.65, '+ Create New Credential', fontsize=5.5,
            color=C_PRIMARY, fontweight='bold', va='top', zorder=22)

    ax.text(margin_x + 0.15, cy - 0.82, 'Anonymous', fontsize=6, color=C_TEXT,
            va='center', zorder=21)
    draw_toggle(ax, margin_x + card_w - 0.45, cy - 0.88, is_on=False, zorder=22)

    # Search Section
    cy -= 1.02
    ax.text(margin_x + 0.05, cy, 'Search', fontsize=7.5, color=C_PRIMARY,
            fontweight='bold', va='top', zorder=21)

    cy -= 0.2
    draw_card(ax, margin_x, cy - 0.7, card_w, 0.7, zorder=20)

    ax.text(margin_x + 0.15, cy - 0.15, 'Include in unified search', fontsize=6.5,
            color=C_TEXT, va='center', zorder=21)
    draw_toggle(ax, margin_x + card_w - 0.45, cy - 0.21, is_on=True, zorder=22)

    draw_divider(ax, margin_x + 0.1, cy - 0.35, card_w - 0.2)

    ax.text(margin_x + 0.15, cy - 0.45, 'Default search categories', fontsize=6,
            color=C_MUTED, va='top', zorder=21)
    cat_chips = ['All', 'Literature', 'Audiobooks', 'Movies']
    cx = margin_x + 0.15
    for cat in cat_chips:
        cw = 0.15 + len(cat) * 0.04
        is_active = cat in ['All', 'Literature']
        draw_chip(ax, cx, cy - 0.65, cw, 0.15, cat,
                  bg_color=C_PRIMARY if is_active else '#e8e4df',
                  text_color='white' if is_active else C_MUTED,
                  fontsize=4.5, zorder=22)
        cx += cw + 0.04

    # Forums Section
    cy -= 0.88
    ax.text(margin_x + 0.05, cy, 'Forums', fontsize=7.5, color=C_PRIMARY,
            fontweight='bold', va='top', zorder=21)

    cy -= 0.2
    draw_card(ax, margin_x, cy - 0.65, card_w, 0.65, zorder=20)

    ax.text(margin_x + 0.15, cy - 0.15, 'Show in unified forums', fontsize=6.5,
            color=C_TEXT, va='center', zorder=21)
    draw_toggle(ax, margin_x + card_w - 0.45, cy - 0.21, is_on=True, zorder=22)

    draw_divider(ax, margin_x + 0.1, cy - 0.35, card_w - 0.2)

    ax.text(margin_x + 0.15, cy - 0.45, 'Default category', fontsize=6,
            color=C_MUTED, va='top', zorder=21)
    dd2_y = cy - 0.6
    dd2 = FancyBboxPatch(
        (margin_x + 0.15, dd2_y), card_w - 0.3, 0.2,
        boxstyle="round,pad=0,rounding_size=0.04",
        facecolor=C_CHIP_BG, edgecolor=C_BORDER, linewidth=0.6, zorder=21
    )
    ax.add_patch(dd2)
    ax.text(margin_x + 0.25, dd2_y + 0.1, 'Foreign Literature', fontsize=5.5,
            color=C_TEXT, va='center', zorder=22)
    ax.text(margin_x + card_w - 0.2, dd2_y + 0.1, '\u25BE', fontsize=6,
            color=C_MUTED, ha='center', va='center', zorder=22)

    # Advanced Section
    cy -= 0.82
    ax.text(margin_x + 0.05, cy, 'Advanced', fontsize=7.5, color=C_PRIMARY,
            fontweight='bold', va='top', zorder=21)

    cy -= 0.2
    draw_card(ax, margin_x, cy - 1.4, card_w, 1.4, zorder=20)

    ax.text(margin_x + 0.15, cy - 0.1, 'Custom mirror URL', fontsize=6,
            color=C_MUTED, va='top', zorder=21)
    draw_text_field(ax, margin_x + 0.15, cy - 0.5, card_w - 0.3, 0.3,
                    label='', value='https://rutracker.org', has_focus=False)

    ax.text(margin_x + 0.15, cy - 0.62, 'Request delay', fontsize=6,
            color=C_MUTED, va='top', zorder=21)
    draw_text_field(ax, margin_x + 0.15, cy - 0.95, card_w/2 - 0.2, 0.3,
                    label='', value='2s', has_focus=False)

    ax.text(margin_x + card_w/2 + 0.05, cy - 0.62, 'Health check', fontsize=6,
            color=C_MUTED, va='top', zorder=21)
    draw_text_field(ax, margin_x + card_w/2 + 0.05, cy - 0.95, card_w/2 - 0.2, 0.3,
                    label='', value='5 min', has_focus=False)

    draw_button(ax, margin_x + 0.15, cy - 1.33, card_w - 0.3, 0.25,
                'Test Connection', bg_color=C_SUCCESS, zorder=22)

    fig.savefig('/home/z/my-project/download/diagrams/wireframe_provider_config.png',
                dpi=300, bbox_inches='tight', pad_inches=0.05, facecolor=fig.get_facecolor())
    plt.close(fig)
    print("  wireframe_provider_config.png")


# ─── Generate all wireframes ───
if __name__ == '__main__':
    print("Generating Lava Multi-Provider Android wireframes...")
    wireframe_credentials()
    wireframe_credential_edit()
    wireframe_provider_login()
    wireframe_unified_search()
    wireframe_unified_forums()
    wireframe_provider_config()
    print("\nAll 6 wireframes generated successfully!")
