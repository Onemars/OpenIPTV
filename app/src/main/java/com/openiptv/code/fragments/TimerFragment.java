package com.openiptv.code.fragments;

import android.app.Dialog;
import android.app.TimePickerDialog;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.widget.TimePicker;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.openiptv.code.PreferenceUtils;

import java.util.Calendar;

public class TimerFragment extends DialogFragment implements TimePickerDialog.OnTimeSetListener {

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        //use current time as default
        final Calendar current = Calendar.getInstance();
        int hour = current.get(Calendar.HOUR_OF_DAY);
        int minute = current.get(Calendar.MINUTE);

        return new TimePickerDialog(getActivity(), this, hour, minute,
                DateFormat.is24HourFormat(getActivity()));
    }

    @Override
    public void onTimeSet(TimePicker timePicker, int i, int i1) {
        int hour = timePicker.getHour();
        int minute = timePicker.getMinute();


        Toast.makeText(getContext(), "Time: " + hour + " : " + minute, Toast.LENGTH_SHORT).show();
        /*
        PreferenceUtils preferenceUtils = new PreferenceUtils(getContext());

        preferenceUtils.setInteger("Hour", hour);
        preferenceUtils.setInteger("Minute", minute);*/
    }
}
