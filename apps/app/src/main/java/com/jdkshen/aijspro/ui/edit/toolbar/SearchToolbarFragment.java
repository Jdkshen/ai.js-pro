package com.jdkshen.aijspro.ui.edit.toolbar;

import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.jdkshen.aijspro.R;

import java.util.Arrays;
import java.util.List;

public class SearchToolbarFragment extends ToolbarFragment {

    public static final String ARGUMENT_SHOW_REPLACE_ITEM = "show_replace_item";

    @NonNull
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_search_toolbar, container, false);
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        boolean showReplaceItem = getArguments().getBoolean(ARGUMENT_SHOW_REPLACE_ITEM, false);
        view.findViewById(R.id.replace).setVisibility(showReplaceItem ? View.VISIBLE : View.GONE);
    }

    @Override
    public List<Integer> getMenuItemIds() {
        return Arrays.asList(R.id.replace, R.id.find_next, R.id.find_prev, R.id.cancel_search);
    }

}
