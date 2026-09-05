package com.stardust.app;

/**
 * Callback for menu items created by {@link OperationDialogBuilder} and
 * {@code OptionListView}. Replaces the old ButterKnife reflection-based
 * {@code bindItemClick} mechanism.
 */
public interface OperationItemClickListener {
    void onOperationItemClick(int id);
}
