package com.jdkshen.aijspro.ui.edit.toolbar;

import android.os.Bundle;
import androidx.annotation.NonNull;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.jdkshen.aijspro.R;

import java.util.Arrays;
import java.util.List;

public class NormalToolbarFragment extends ToolbarFragment {

    @NonNull
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_normal_toolbar, container, false);
    }

    @Override
    public List<Integer> getMenuItemIds() {
        return Arrays.asList(R.id.run, R.id.undo, R.id.redo, R.id.save);
    }
}
