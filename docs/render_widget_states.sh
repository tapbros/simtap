#!/usr/bin/env bash
# Renders README preview PNGs of the SimTap home screen widget (English and Korean)
# by reproducing res/layout/widget.xml as HTML and taking headless Chrome screenshots.
# Colors, radius, paddings, text sizes, strings and the launcher icon (adaptive icon
# background color + res/drawable/ic_launcher_fg.xml) are parsed from the resource XML
# at run time, so the images follow resource changes. Cell visibility, background and
# text per example mirror SimTapWidget.build().
#
# Output: docs/widget_states.png (res/values) and docs/widget_states.ko.png (res/values-ko)
#
# Assumptions (not taken from resources):
#   - widget size 320x80 dp (a 4x1 cell; real size depends on the launcher grid)
#   - 1 dp = 1 sp = 1 CSS px, rendered at device scale factor 3
#   - example SIM names "SKT", "KT eSIM" and data SIM "SKT" (real names come from the SIM manager screen)
#   - "SIM 1" is sim_default with %1$d = 1, used for the single SIM and first install rows
#   - launcher icon drawn as the 72 dp visible part of the 108 dp adaptive canvas with a rounded square mask
#   - system font stack (Apple SD Gothic Neo for Korean) instead of the device font; no web fonts
#   - captions and footnote text are written here, not in the app resources
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/docs"
CHROME="${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
SCALE=3

D="$(mktemp -d)"
cleanup() { rm -f "$D"/*; rmdir "$D"; }
trap cleanup EXIT

python3 - "$ROOT" "$D" <<'PY'
import html, re, sys, xml.etree.ElementTree as ET
root, out = sys.argv[1], sys.argv[2]
A = '{http://schemas.android.com/apk/res/android}'

def color(c):
    # Android #AARRGGBB -> CSS rgba(); alpha is the FIRST byte
    c = c.lstrip('#')
    if len(c) == 6:
        c = 'FF' + c
    a, r, g, b = (int(c[i:i + 2], 16) for i in (0, 2, 4, 6))
    return f'rgba({r},{g},{b},{a / 255:.3f})'

def dp(v):
    return float(re.match(r'([\d.]+)(dp|sp)$', v).group(1))

def res(path):
    return {e.get('name'): e.text for e in ET.parse(f'{root}/res/{path}').getroot()}

dimens, colors = res('values/dimens.xml'), res('values/colors.xml')

def bg(name):
    shape = ET.parse(f'{root}/res/drawable/{name}.xml').getroot()
    solid = shape.find('solid').get(A + 'color')
    rad = shape.find('corners').get(A + 'radius')
    if rad.startswith('@dimen/'):
        rad = dimens[rad[len('@dimen/'):]]
    return color(solid), dp(rad)

# Launcher icon: adaptive-icon background color + foreground vector, cropped to the 72 dp visible area
ai = ET.parse(f'{root}/res/mipmap-anydpi-v26/ic_launcher.xml').getroot()
icon_bg = color(colors[ai.find('background').get(A + 'drawable').split('/')[-1]])
fg = ET.parse(f'{root}/res/drawable/{ai.find("foreground").get(A + "drawable").split("/")[-1]}.xml').getroot()
paths = ''.join(
    f'<path fill="{color(p.get(A + "fillColor"))}" fill-rule="{"evenodd" if p.get(A + "fillType") == "evenOdd" else "nonzero"}" '
    f'd="{p.get(A + "pathData")}"/>' for p in fg.findall('path'))
vw = float(fg.get(A + 'viewportWidth'))
inset = vw * 18 / 108
ICON = (f'<svg viewBox="{inset:g} {inset:g} {vw - 2 * inset:g} {vw - 2 * inset:g}" width="100%" height="100%">'
        f'<rect x="{inset:g}" y="{inset:g}" width="{vw - 2 * inset:g}" height="{vw - 2 * inset:g}" fill="{icon_bg}"/>{paths}</svg>')

# Layout values from res/layout/widget.xml (all three cells share these attributes)
lay = ET.parse(f'{root}/res/layout/widget.xml').getroot()
ids = {e.get(A + 'id').split('/')[-1]: e for e in lay.iter() if e.get(A + 'id')}
c0, c1, n0, s0 = ids['cell0'], ids['cell1'], ids['name0'], ids['state0']
L = dict(
    ps=dp(c0.get(A + 'paddingStart')), pe=dp(c0.get(A + 'paddingEnd')),
    gap=dp(c1.get(A + 'layout_marginStart')),
    n_sz=dp(n0.get(A + 'textSize')), n_col=color(n0.get(A + 'textColor')),
    s_sz=dp(s0.get(A + 'textSize')), s_col=color(s0.get(A + 'textColor')),
    s_w='700' if s0.get(A + 'textStyle') == 'bold' else '400',
)

W, H = 320, 80          # 4x1 widget (assumption, see header)
HEAD, HEAD_MB = 24, 12  # header: app icon + app name
CAP_MT, CAP_H, ROW_GAP = 6, 16, 14
FOOT_MT, FOOT_H = 10, 14
PAD = 4                 # transparent margin around the sheet
SW = 360                # sheet width: the 320 dp widget plus room for the one-line footnote

TEXT = {
    '': dict(dual='Dual SIM', dual_off='Dual SIM, one line off', single='Single SIM', first='Before the first read',
             foot='Example · drawn from the app layout, not a device screenshot'),
    'ko': dict(dual='듀얼 SIM', dual_off='듀얼 SIM, 회선 하나 꺼짐', single='단일 SIM', first='처음 설치(상태 읽기 전)',
               foot='예시 화면 · 앱 레이아웃으로 그린 미리보기이며 실제 기기 화면이 아닙니다'),
}

CSS = f'''
html,body{{margin:0;padding:0;background:transparent;}}
*{{box-sizing:border-box;}}
body{{font-family:-apple-system,"Apple SD Gothic Neo","Helvetica Neue","Noto Sans KR",sans-serif;-webkit-font-smoothing:antialiased;}}
/* fixed width: headless Chrome keeps a minimum window width wider than the shot, so do not center on the viewport */
.sheet{{width:{SW}px;display:flex;flex-direction:column;align-items:center;padding:{PAD}px;}}
.head{{display:flex;flex-direction:row;align-items:center;height:{HEAD}px;margin-bottom:{HEAD_MB}px;}}
.head .ai{{width:{HEAD}px;height:{HEAD}px;border-radius:{HEAD // 4}px;overflow:hidden;}}
.head .ai svg{{display:block;}}
.head span{{margin-left:8px;font-size:16px;line-height:{HEAD}px;font-weight:700;color:#374151;}}
.item+.item{{margin-top:{ROW_GAP}px;}}
.item{{display:flex;flex-direction:column;align-items:center;}}
.w{{width:{W}px;height:{H}px;display:flex;flex-direction:row;}}
.c{{flex:1 1 0;min-width:0;display:flex;flex-direction:column;justify-content:center;
  padding:0 {L["pe"]}px 0 {L["ps"]}px;}}
.c+.c{{margin-left:{L["gap"]}px;}}
.c div{{white-space:nowrap;overflow:hidden;text-overflow:ellipsis;}}
.n{{font-size:{L["n_sz"]}px;color:{L["n_col"]};}}
.s{{font-size:{L["s_sz"]}px;color:{L["s_col"]};font-weight:{L["s_w"]};}}
.cap{{margin-top:{CAP_MT}px;font-size:12px;line-height:{CAP_H}px;color:#6B7280;}}
.foot{{white-space:nowrap;margin-top:{FOOT_MT}px;font-size:10px;line-height:{FOOT_H}px;color:#9CA3AF;}}
'''

def cell(name, state, bgname):
    c, r = bg(bgname)
    return (f'<div class="c" style="background:{c};border-radius:{r}px">'
            f'<div class="n">{html.escape(name)}</div><div class="s">{html.escape(state)}</div></div>')

for lang, suffix in (('', ''), ('ko', '.ko')):
    s = res(f'values{"-" + lang if lang else ""}/strings.xml')
    t = TEXT[lang]
    sim1 = s['sim_default'].replace('%1$d', '1')
    # Rows mirror SimTapWidget.build(): GONE cells are omitted, so a single visible cell takes the full width
    rows = [
        (t['dual'], [('SKT', s['state_on'], 'bg_on'), ('KT eSIM', s['state_on'], 'bg_on'),
                     (s['data_label'], 'SKT', 'bg_data')]),
        # with fewer than two lines on, the data cell stays but is dimmed (SimTapWidget.build())
        (t['dual_off'], [('SKT', s['state_on'], 'bg_on'), ('KT eSIM', s['state_off'], 'bg_off'),
                         (s['data_label'], s['state_need_two'], 'bg_unknown')]),
        (t['single'], [(sim1, s['state_on'], 'bg_on')]),
        (t['first'], [(sim1, s['state_tap_to_read'], 'bg_unknown')]),
    ]
    items = ''.join(f'<div class="item"><div class="w">{"".join(cell(*c) for c in cells)}</div>'
                    f'<div class="cap">{html.escape(cap)}</div></div>' for cap, cells in rows)
    head = f'<div class="head"><div class="ai">{ICON}</div><span>{html.escape(s["app_name"])}</span></div>'
    body = f'<div class="sheet">{head}{items}<div class="foot">{html.escape(t["foot"])}</div></div>'
    open(f'{out}/states{suffix}.html', 'w').write(
        f'<!doctype html><html><head><meta charset="utf-8"><style>{CSS}</style></head><body>{body}</body></html>')

h = 2 * PAD + HEAD + HEAD_MB + len(rows) * (H + CAP_MT + CAP_H) + (len(rows) - 1) * ROW_GAP + FOOT_MT + FOOT_H
open(f'{out}/sizes', 'w').write(f'{SW},{h}\n')
PY

read -r SIZE < "$D/sizes"

shot() { # html png w,h  (perl alarm = 30 s hard timeout; headless Chrome can hang)
  rm -f "$2"
  perl -e 'alarm 30; exec @ARGV' "$CHROME" --headless=new --disable-gpu --hide-scrollbars \
    --force-device-scale-factor="$SCALE" --default-background-color=00000000 \
    --window-size="$3" --screenshot="$2" "file://$1" >/dev/null 2>&1 \
    && [ -s "$2" ] || { echo "screenshot failed: $2" >&2; return 1; }
}

shot "$D/states.html" "$OUT/widget_states.png" "$SIZE"
shot "$D/states.ko.html" "$OUT/widget_states.ko.png" "$SIZE"
echo "wrote: widget_states.png widget_states.ko.png in $OUT"
