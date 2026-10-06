package com.tapbros.simtap;

/** 5x1 패널: 회선 1, 회선 2, 와이파이, 모바일 데이터, 데이터 SIM. 그리기와 수신 처리는 SimTapWidget 과 같고 레이아웃만 다르다. */
public class SimTapWideWidget extends SimTapWidget {
    @Override
    int layout() { return R.layout.widget_wide; }
}
