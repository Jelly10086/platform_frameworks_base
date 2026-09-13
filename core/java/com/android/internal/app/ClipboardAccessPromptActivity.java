/*
 * Copyright (C) 2026 The uwuAOSP Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.android.internal.app;

import static android.view.WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS;

import android.app.Activity;
import android.content.Context;
import android.content.IClipboard;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.android.internal.R;

import java.util.ArrayList;

/** Dialog shown when an app with ask-policy accesses the clipboard. */
public class ClipboardAccessPromptActivity extends Activity implements View.OnClickListener {
    private static final String TAG = "ClipboardAccessPrompt";
    private static final String PACKAGE_NAME = "com.android.internal.app";

    private static final String EXTRA_PACKAGE_NAME = PACKAGE_NAME + ".extra.CLIPBOARD_PACKAGE";
    private static final String EXTRA_OPERATION = PACKAGE_NAME + ".extra.CLIPBOARD_OPERATION";

    public static final int OPERATION_READ = 0;
    public static final int OPERATION_WRITE = 1;

    private int mUserId;
    private int mOperation;
    private String mPackageName;
    private CheckBox mPermanentRuleView;
    private boolean mPromptResolved;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addSystemFlags(SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS);
        setFinishOnTouchOutside(true);

        final Intent intent = getIntent();
        mUserId = intent.getIntExtra(Intent.EXTRA_USER_ID, -1);
        mPackageName = intent.getStringExtra(EXTRA_PACKAGE_NAME);
        mOperation = intent.getIntExtra(EXTRA_OPERATION, -1);
        if (mUserId < 0
                || mPackageName == null
                || (mOperation != OPERATION_READ && mOperation != OPERATION_WRITE)) {
            Log.wtf(TAG, "Invalid clipboard access prompt intent: " + intent);
            mPromptResolved = true;
            finish();
            return;
        }

        setContentView(R.layout.app_jump_prompt_dialog);
        bindViews();
    }

    private void bindViews() {
        final TextView titleView = requireViewById(R.id.app_jump_title);
        final TextView messageView = requireViewById(R.id.app_jump_message);
        final ImageView sourceIconView = requireViewById(R.id.app_jump_source_icon);
        final TextView sourceLabelView = requireViewById(R.id.app_jump_source_label);
        final Button allowButton = requireViewById(R.id.app_jump_allow_button);
        final Button denyButton = requireViewById(R.id.app_jump_deny_button);
        final Button allowOnceButton = requireViewById(R.id.app_jump_allow_once_button);
        final Button okButton = requireViewById(R.id.app_jump_ok_button);
        mPermanentRuleView = requireViewById(R.id.app_jump_remember_choice);
        final TextView footerView = requireViewById(R.id.app_jump_footer);
        final ViewGroup buttonGroup = requireViewById(R.id.app_jump_button_group);

        final CharSequence appLabel = loadAppLabel();
        sourceIconView.setImageDrawable(loadAppIcon());
        sourceLabelView.setText(appLabel);
        final ViewGroup sourceContainer = (ViewGroup) sourceIconView.getParent();
        final ViewGroup routeContainer = (ViewGroup) sourceContainer.getParent();
        for (int index = 0; index < routeContainer.getChildCount(); index++) {
            final View child = routeContainer.getChildAt(index);
            child.setVisibility(child == sourceContainer ? View.VISIBLE : View.GONE);
        }

        final boolean isRead = mOperation == OPERATION_READ;
        titleView.setText(
                isRead
                        ? R.string.clipboard_access_prompt_read_title
                        : R.string.clipboard_access_prompt_write_title);
        messageView.setText(
                getString(
                        isRead
                                ? R.string.clipboard_access_prompt_read_message
                                : R.string.clipboard_access_prompt_write_message,
                        appLabel));

        allowButton.setText(R.string.app_jump_allow);
        denyButton.setText(R.string.app_jump_deny);
        allowButton.setVisibility(View.VISIBLE);
        denyButton.setVisibility(View.VISIBLE);
        allowOnceButton.setVisibility(View.GONE);
        okButton.setVisibility(View.GONE);
        mPermanentRuleView.setText(R.string.clipboard_access_write_permanent_rule);
        mPermanentRuleView.setChecked(false);
        mPermanentRuleView.setVisibility(View.VISIBLE);
        footerView.setVisibility(View.GONE);
        updateActionButtonBackgrounds(buttonGroup, allowButton, denyButton);

        allowButton.setOnClickListener(this);
        denyButton.setOnClickListener(this);
    }

    @Override
    public void onClick(View view) {
        final int decision =
                view.getId() == R.id.app_jump_allow_button
                        ? Settings.Secure.UWU_APP_CLIPBOARD_POLICY_ALLOW
                        : Settings.Secure.UWU_APP_CLIPBOARD_POLICY_DENY;
        if (!resolvePrompt(decision, mPermanentRuleView.isChecked())) {
            Log.e(TAG, "Unable to apply clipboard choice for " + mPackageName);
            Toast.makeText(this, R.string.clipboard_access_policy_save_failed, Toast.LENGTH_LONG)
                    .show();
            return;
        }
        mPromptResolved = true;
        finish();
    }

    private boolean resolvePrompt(int decision, boolean persist) {
        final IClipboard clipboard =
                IClipboard.Stub.asInterface(ServiceManager.getService(Context.CLIPBOARD_SERVICE));
        if (clipboard == null) {
            return false;
        }
        try {
            return clipboard.resolveClipboardAccessPrompt(
                    mPackageName, mUserId, mOperation, decision, persist);
        } catch (RemoteException e) {
            Log.e(TAG, "Clipboard service rejected the prompt result", e);
            return false;
        }
    }

    @Override
    protected void onDestroy() {
        if (isFinishing() && !mPromptResolved && mPackageName != null) {
            resolvePrompt(Settings.Secure.UWU_APP_CLIPBOARD_POLICY_ASK, false);
            mPromptResolved = true;
        }
        super.onDestroy();
    }

    private void updateActionButtonBackgrounds(ViewGroup buttonGroup, Button... buttons) {
        final ArrayList<Button> visibleButtons = new ArrayList<>();
        for (Button button : buttons) {
            if (button.getVisibility() == View.VISIBLE) {
                visibleButtons.add(button);
            }
        }
        buttonGroup.setVisibility(visibleButtons.isEmpty() ? View.GONE : View.VISIBLE);
        final int topMargin =
                getResources().getDimensionPixelOffset(R.dimen.app_jump_action_button_margin_top);
        final int bottomMargin =
                getResources()
                        .getDimensionPixelOffset(R.dimen.app_jump_action_button_margin_bottom);
        for (int index = 0; index < visibleButtons.size(); index++) {
            final Button button = visibleButtons.get(index);
            button.setBackgroundResource(
                    index == 0
                            ? R.drawable.app_jump_action_background_top
                            : R.drawable.app_jump_action_background_bottom);
            final ViewGroup.LayoutParams layoutParams = button.getLayoutParams();
            if (layoutParams instanceof ViewGroup.MarginLayoutParams marginLayoutParams) {
                marginLayoutParams.topMargin = index == 0 ? 0 : topMargin;
                marginLayoutParams.bottomMargin =
                        index == visibleButtons.size() - 1 ? bottomMargin : 0;
                button.setLayoutParams(marginLayoutParams);
            }
        }
    }

    private CharSequence loadAppLabel() {
        try {
            final ApplicationInfo appInfo =
                    getPackageManager().getApplicationInfoAsUser(mPackageName, 0, mUserId);
            return appInfo.loadSafeLabel(
                    getPackageManager(),
                    PackageItemInfo.DEFAULT_MAX_LABEL_SIZE_PX,
                    PackageItemInfo.SAFE_LABEL_FLAG_FIRST_LINE
                            | PackageItemInfo.SAFE_LABEL_FLAG_TRIM);
        } catch (PackageManager.NameNotFoundException e) {
            return mPackageName;
        }
    }

    private Drawable loadAppIcon() {
        try {
            return getPackageManager()
                    .getApplicationInfoAsUser(mPackageName, 0, mUserId)
                    .loadIcon(getPackageManager());
        } catch (PackageManager.NameNotFoundException e) {
            return getPackageManager().getDefaultActivityIcon();
        }
    }

    public static Intent createIntent(
            Context context, int userId, String packageName, int operation) {
        return new Intent()
                .setClass(context, ClipboardAccessPromptActivity.class)
                .putExtra(Intent.EXTRA_USER_ID, userId)
                .putExtra(EXTRA_PACKAGE_NAME, packageName)
                .putExtra(EXTRA_OPERATION, operation)
                .setFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
    }
}
