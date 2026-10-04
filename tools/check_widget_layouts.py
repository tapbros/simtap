#!/usr/bin/env python3
"""위젯 레이아웃의 뷰 태그를 RemoteViews 허용 목록과 대조한다.

허용 목록 근거: developer.android.com/reference/android/widget/RemoteViews 클래스 설명
("RemoteViews is limited to support for the following layouts ... Descendants of these classes are not supported")
와 developer.android.com/develop/ui/views/appwidgets 의 ViewStub 지원 문장.
API 31 부터 쓸 수 있는 CheckBox, RadioButton, RadioGroup, Switch 는 minSdk 26 인 layout/ 에 두면
Android 8~11 에서 inflate 가 실패하므로 넣지 않았다.

사용: check_widget_layouts.py [레이아웃.xml ...]
인자가 없으면 이 저장소의 res/layout/widget*.xml 과 res/xml/ 의 appwidget-provider 가 가리키는 레이아웃을 검사한다.
허용 밖 태그가 하나라도 있거나 검사할 파일이 없으면 0 이 아닌 값으로 끝난다.
"""
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

ALLOWED = {
    # layouts
    "AdapterViewFlipper", "FrameLayout", "GridLayout", "GridView", "LinearLayout",
    "ListView", "RelativeLayout", "StackView", "ViewFlipper",
    # widgets
    "AnalogClock", "Button", "Chronometer", "ImageButton", "ImageView", "ProgressBar",
    "TextClock", "TextView",
    # appwidgets 가이드: RemoteViews also supports ViewStub
    "ViewStub",
}
# 뷰가 아닌 레이아웃 XML 요소. include 는 대상 레이아웃이 따로 검사되지 않으므로 허용하지 않는다.
NON_VIEW = {"requestFocus", "tag"}
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"


def default_files(root):
    files = set(glob.glob(os.path.join(root, "res", "layout", "widget*.xml")))
    for info in glob.glob(os.path.join(root, "res", "xml", "*.xml")):
        tree = ET.parse(info).getroot()
        if tree.tag != "appwidget-provider":
            continue
        for attr in ("initialLayout", "previewLayout", "initialKeyguardLayout"):
            ref = tree.get(ANDROID_NS + attr)
            m = re.fullmatch(r"@layout/(\w+)", ref or "")
            if m:
                files.add(os.path.join(root, "res", "layout", m.group(1) + ".xml"))
    return sorted(files)


def check(path):
    bad = []
    for el in ET.parse(path).getroot().iter():
        tag = el.tag
        if tag in NON_VIEW:
            continue
        if tag == "view":
            tag = el.get("class", "view")
        name = tag[len("android.widget."):] if tag.startswith("android.widget.") else tag
        if tag == "android.view.ViewStub":
            name = "ViewStub"
        if name not in ALLOWED:
            bad.append(tag)
    return bad


def main(argv):
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    files = argv[1:] or default_files(root)
    if not files:
        print("check_widget_layouts: no widget layouts found", file=sys.stderr)
        return 2
    failed = False
    for f in files:
        if not os.path.isfile(f):
            print(f"check_widget_layouts: missing {f}", file=sys.stderr)
            failed = True
            continue
        bad = check(f)
        if bad:
            failed = True
            print(f"check_widget_layouts: {f}: not allowed in RemoteViews: {', '.join(bad)}", file=sys.stderr)
    if not failed:
        print(f"check_widget_layouts: {len(files)} layouts OK")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
