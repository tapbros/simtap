# testbed: 가짜 SIM 관리자로 듀얼 SIM 시험

듀얼 SIM 기기 없이 SimTap 의 듀얼 SIM 동작을 실기기에서 시험한다. 진짜 SIM 은 건드리지 않는다.

- `testbed/fakesim/` 은 삼성 SIM 관리자 첫 화면(One UI 9.0 KR 디컴파일 기준)을 흉내 낸 앱이다. 회선 행 스위치 id `on_off_switch`, contentDescription = SIM 이름, 끄기·켜기 확인 창, 진행 스피너, 최대 개수 창(버튼 3개), 데이터 SIM 선택 화면을 갖는다.
- `SIMTAP_TARGET_PKG=com.tapbros.fakesim ./build.sh` 는 대상 패키지만 바꾼 SimTap(`-testbed.apk`)을 만든다. 패키지명이 같으므로 릴리스 키로 서명해 덮어 설치하고 시험 뒤 릴리스판으로 되돌린다.

## 순서

1. `testbed/fakesim/build.sh` 로 가짜 앱을 만들어 설치한다.
2. 릴리스 키 환경 변수와 `SIMTAP_TARGET_PKG=com.tapbros.fakesim` 로 SimTap 을 빌드해 덮어 설치한다.
3. `adb shell am start -n com.tapbros.fakesim/.SimCardMgrActivity --es scenario <이름>` 으로 시나리오를 고르고 홈 위젯 칸을 누른다.
4. 판정은 logcat 태그 `SimTap` 과 `FakeSim`(`toggle <이름> 0->1`)으로 한다. 흐름 도중 `uiautomator dump` 는 접근성 서비스를 다시 묶으므로 쓰지 않는다.
5. 끝나면 릴리스판 SimTap 을 다시 설치하고 `adb uninstall com.tapbros.fakesim`, 진짜 SIM 관리자를 한 번 열어 위젯 캐시를 실제 회선으로 되돌린다.

## 시나리오와 기대 결과 (2026-10-04 Fold8 실기기, v0.01.00.21 과 v0.01.00.25)

| 시나리오 | 구성 | 시험 | 결과 |
|---|---|---|---|
| psim_esim | 유심 SKT + eSIM KT eSIM | KT eSIM 끄기(사용자 확인), 켜기(자동) | KT eSIM 만 바뀜, 6초 진행 뒤 홈 |
| psim_esim (0.25) | 같음 | KT eSIM 끄기, SKT(데이터 SIM) 끄기 | 끄기 창 자동 확인, 데이터 SIM 이동 본문도 통과, 홈 |
| danger_on (0.25) | SKT 꺼짐 + KT eSIM 켜짐 | KT eSIM 끄기(마지막 SIM 본문), SKT 켜기(위험 본문) | 끄기는 자동 확인, 위험 켜기는 거부 |
| psim_esim2 | + 꺼진 eSIM Travel 이 KT eSIM 위 | Travel 켜기 | 버튼 3개 최대 개수 창을 누르지 않음 |
| esim_only | 유심 없음 + LG U+ + KT eSIM | KT eSIM 끄기 | KT eSIM 만 꺼짐 |
| same_name | 두 회선 모두 「SIM」 | 둘째 칸 끄기 | 둘째 스위치만 꺼짐 |
| prefix_name | SKT, SKT eSIM 모두 꺼짐 | SKT 켜기 | SKT 만 켜짐 |
| danger_on | 켜기 창에 「다른 SIM 이 꺼집니다」 본문 | SKT 켜기 | 자동 확인 거부(body=true) |
| psim2 | 유심 두 장 SKT + KT | KT 끄기·켜기 | KT 만 바뀜 |
| psim_esim | 두 회선 켜짐 | 데이터 SIM 칸 | 데이터 SIM 선택 화면 열림 |

가짜 앱의 최대 개수 창 본문, 위험 창 인자 채우기 규칙, 지연 시간(eSIM 6초, 그 외 2초)은 시험용으로 정한 값이다.

회선을 토글한 뒤 `dataSettleMs`(기본 3000ms) 동안은 두 회선이 모두 켜져 있어도 「모바일 데이터」 행이 disabled 로 남는다(S25 One UI 8.5 제보의 삼성 동작 모사). `--ei settle <ms>` 로 바꾸고 logcat `FakeSim` 의 `dataRow enabled=<bool>` 로 행 상태가 바뀐 시점을 본다.
