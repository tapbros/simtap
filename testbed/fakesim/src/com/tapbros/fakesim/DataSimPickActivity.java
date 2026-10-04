package com.tapbros.fakesim;

import android.app.Activity;
import android.os.Bundle;
import android.widget.RadioButton;
import android.widget.RadioGroup;

/** 「데이터 SIM 선택」 화면. 켜진 회선 목록에서 고르고 적용을 누르면 데이터 SIM 이 바뀐다. */
public class DataSimPickActivity extends Activity {
    private SimState state;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_data_pick);
        state = SimState.load(this);
        final RadioGroup group = findViewById(R.id.data_choices);
        for (int i = 0; i < state.lines.size(); i++) {
            SimState.Line l = state.lines.get(i);
            if (!l.present || !l.on) continue;
            RadioButton r = new RadioButton(this);
            r.setId(i + 1);
            r.setText(l.name);
            group.addView(r);
            if (i == state.dataSim) r.setChecked(true);
        }
        findViewById(R.id.apply).setOnClickListener(v -> {
            int id = group.getCheckedRadioButtonId();
            if (id > 0) {
                state.setData(id - 1);
                state.save(this);
            }
            finish();
        });
    }
}
