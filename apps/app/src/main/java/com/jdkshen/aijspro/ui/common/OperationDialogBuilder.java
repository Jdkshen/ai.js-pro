package com.stardust.app;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.afollestad.materialdialogs.MaterialDialog;
import com.jdkshen.aijspro.R;

import java.util.ArrayList;

/**
 * Created by Stardust on 2017/6/26.
 */

public class OperationDialogBuilder extends MaterialDialog.Builder {

    private RecyclerView mOperations;
    private ArrayList<Integer> mIds = new ArrayList<>();
    private ArrayList<Integer> mIcons = new ArrayList<>();
    private ArrayList<String> mTexts = new ArrayList<>();
    private OperationItemClickListener mOnItemClickTarget;

    public OperationDialogBuilder(@NonNull Context context) {
        super(context);
        mOperations = new RecyclerView(context);
        mOperations.setLayoutManager(new LinearLayoutManager(context));
        mOperations.setAdapter(new RecyclerView.Adapter<ViewHolder>() {
            @Override
            public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
                return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.operation_dialog_item, parent, false));
            }

            @Override
            public void onBindViewHolder(ViewHolder holder, int position) {
                holder.itemView.setId(mIds.get(position));
                holder.text.setText(mTexts.get(position));
                holder.icon.setImageResource(mIcons.get(position));
                if (mOnItemClickTarget != null) {
                    holder.itemView.setOnClickListener(v ->
                            mOnItemClickTarget.onOperationItemClick(holder.itemView.getId()));
                }
            }

            @Override
            public int getItemCount() {
                return mIds.size();
            }
        });
        customView(mOperations, false);
    }

    public OperationDialogBuilder item(int id, int iconRes, int textRes) {
        return item(id, iconRes, getContext().getString(textRes));
    }

    public OperationDialogBuilder item(int id, int iconRes, String text) {
        mIds.add(id);
        mIcons.add(iconRes);
        mTexts.add(text);
        return this;
    }

    public OperationDialogBuilder bindItemClick(OperationItemClickListener listener) {
        mOnItemClickTarget = listener;
        return this;
    }

    class ViewHolder extends RecyclerView.ViewHolder {

        ImageView icon;
        TextView text;

        public ViewHolder(View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.icon);
            text = itemView.findViewById(R.id.text);
        }

    }
}