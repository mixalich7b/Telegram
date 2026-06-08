package org.telegram.ui;

import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NetworkRouteSettings;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.WireGuardConfigParser;
import org.telegram.messenger.WireGuardManager;
import org.telegram.messenger.WireGuardProfile;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

import static org.telegram.messenger.LocaleController.getString;

public class WireGuardSettingsActivity extends BaseFragment {

    private static final int FIELD_NAME = 0;
    private static final int FIELD_PRIVATE_KEY = 1;
    private static final int FIELD_ADDRESSES = 2;
    private static final int FIELD_DNS = 3;
    private static final int FIELD_MTU = 4;
    private static final int FIELD_PEER_PUBLIC_KEY = 5;
    private static final int FIELD_PRESHARED_KEY = 6;
    private static final int FIELD_ENDPOINT = 7;
    private static final int FIELD_ALLOWED_IPS = 8;
    private static final int FIELD_KEEPALIVE = 9;

    private static final int DONE_BUTTON = 1;

    private final WireGuardProfile currentProfile;
    private final boolean addingNewProfile;

    private EditTextBoldCursor[] inputFields;
    private ActionBarMenuItem doneItem;

    public WireGuardSettingsActivity() {
        currentProfile = new WireGuardProfile();
        addingNewProfile = true;
    }

    public WireGuardSettingsActivity(WireGuardProfile profile) {
        currentProfile = profile == null ? new WireGuardProfile() : profile.copy();
        addingNewProfile = currentProfile.id == null || currentProfile.id.isEmpty();
    }

    @Override
    public View createView(Context context) {
        actionBar.setTitle(getString(R.string.WireGuardDetails));
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(false);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == DONE_BUTTON) {
                    saveProfile();
                }
            }
        });

        doneItem = actionBar.createMenu().addItemWithWidth(DONE_BUTTON, R.drawable.ic_ab_done, AndroidUtilities.dp(56));
        doneItem.setContentDescription(getString(R.string.Done));

        fragmentView = new FrameLayout(context);
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        AndroidUtilities.setScrollViewEdgeEffectColor(scrollView, Theme.getColor(Theme.key_actionBarDefault));
        ((FrameLayout) fragmentView).addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        LinearLayout fieldsContainer = new LinearLayout(context);
        fieldsContainer.setOrientation(LinearLayout.VERTICAL);
        fieldsContainer.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        scrollView.addView(fieldsContainer, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        inputFields = new EditTextBoldCursor[10];
        addField(context, fieldsContainer, FIELD_NAME, getString(R.string.WireGuardName), currentProfile.name, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, false);
        addField(context, fieldsContainer, FIELD_PRIVATE_KEY, getString(R.string.WireGuardPrivateKey), currentProfile.privateKey, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, true);
        addField(context, fieldsContainer, FIELD_ADDRESSES, getString(R.string.WireGuardInterfaceAddresses), join(currentProfile.localAddresses), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, false);
        addField(context, fieldsContainer, FIELD_DNS, getString(R.string.WireGuardDnsServers), join(currentProfile.dnsServers), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, false);
        addField(context, fieldsContainer, FIELD_MTU, getString(R.string.WireGuardMtu), currentProfile.mtu == 0 ? "" : String.valueOf(currentProfile.mtu), InputType.TYPE_CLASS_NUMBER, false);
        addField(context, fieldsContainer, FIELD_PEER_PUBLIC_KEY, getString(R.string.WireGuardPeerPublicKey), currentProfile.peerPublicKey, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, false);
        addField(context, fieldsContainer, FIELD_PRESHARED_KEY, getString(R.string.WireGuardPresharedKey), currentProfile.presharedKey, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, true);
        addField(context, fieldsContainer, FIELD_ENDPOINT, getString(R.string.WireGuardEndpoint), currentProfile.peerEndpoint, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_URI, false);
        addField(context, fieldsContainer, FIELD_ALLOWED_IPS, getString(R.string.WireGuardAllowedIps), join(currentProfile.allowedIps), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, false);
        addField(context, fieldsContainer, FIELD_KEEPALIVE, getString(R.string.WireGuardPersistentKeepalive), String.valueOf(currentProfile.persistentKeepaliveSeconds), InputType.TYPE_CLASS_NUMBER, false);

        checkDoneEnabled();
        return fragmentView;
    }

    private void addField(Context context, LinearLayout fieldsContainer, int index, String hint, String value, int inputType, boolean password) {
        FrameLayout container = new FrameLayout(context);
        fieldsContainer.addView(container, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 64));

        EditTextBoldCursor field = new EditTextBoldCursor(context);
        inputFields[index] = field;
        field.setTag(index);
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        field.setHintColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        field.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        field.setBackground(null);
        field.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        field.setCursorSize(AndroidUtilities.dp(20));
        field.setCursorWidth(1.5f);
        field.setSingleLine(true);
        field.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        field.setHeaderHintColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader));
        field.setTransformHintToHeader(true);
        field.setLineColors(Theme.getColor(Theme.key_windowBackgroundWhiteInputField), Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated), Theme.getColor(Theme.key_text_RedRegular));
        field.setInputType(inputType);
        if (password) {
            field.setTransformationMethod(PasswordTransformationMethod.getInstance());
        }
        field.setImeOptions(index + 1 < inputFields.length ? EditorInfo.IME_ACTION_NEXT : EditorInfo.IME_ACTION_DONE);
        field.setHintText(hint);
        field.setText(value == null ? "" : value);
        field.setSelection(field.length());
        field.setPadding(0, 0, 0, 0);
        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                checkDoneEnabled();
            }
        });
        field.setOnEditorActionListener((textView, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_NEXT) {
                int nextIndex = (Integer) textView.getTag() + 1;
                if (nextIndex < inputFields.length) {
                    inputFields[nextIndex].requestFocus();
                }
                return true;
            } else if (actionId == EditorInfo.IME_ACTION_DONE) {
                saveProfile();
                return true;
            }
            return false;
        });

        container.addView(field, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.LEFT | Gravity.TOP, 17, index == FIELD_NAME ? 12 : 0, 17, 0));
    }

    private void checkDoneEnabled() {
        if (doneItem == null || inputFields == null || inputFields[FIELD_PRIVATE_KEY] == null) {
            return;
        }
        boolean enabled = inputFields[FIELD_PRIVATE_KEY].length() != 0
                && inputFields[FIELD_ADDRESSES].length() != 0
                && inputFields[FIELD_PEER_PUBLIC_KEY].length() != 0
                && inputFields[FIELD_ENDPOINT].length() != 0
                && inputFields[FIELD_ALLOWED_IPS].length() != 0;
        doneItem.setEnabled(enabled);
        doneItem.setAlpha(enabled ? 1.0f : 0.5f);
    }

    private void saveProfile() {
        if (addingNewProfile && !WireGuardManager.isBuildSupported()) {
            showWireGuardUnavailable();
            return;
        }

        WireGuardProfile profile = currentProfile.copy();
        profile.name = text(FIELD_NAME);
        profile.privateKey = text(FIELD_PRIVATE_KEY);
        profile.localAddresses = WireGuardConfigParser.parseList(text(FIELD_ADDRESSES));
        profile.dnsServers = WireGuardConfigParser.parseList(text(FIELD_DNS));
        profile.mtu = TextUtils.isEmpty(text(FIELD_MTU)) ? WireGuardProfile.DEFAULT_MTU : Utilities.parseInt(text(FIELD_MTU));
        profile.peerPublicKey = text(FIELD_PEER_PUBLIC_KEY);
        profile.presharedKey = text(FIELD_PRESHARED_KEY);
        profile.peerEndpoint = text(FIELD_ENDPOINT);
        profile.allowedIps = WireGuardConfigParser.parseList(text(FIELD_ALLOWED_IPS));
        profile.persistentKeepaliveSeconds = TextUtils.isEmpty(text(FIELD_KEEPALIVE)) ? WireGuardProfile.DEFAULT_PERSISTENT_KEEPALIVE_SECONDS : Utilities.parseInt(text(FIELD_KEEPALIVE));
        profile.normalize();

        String validationError = profile.validate();
        if (validationError != null) {
            showDialog(new AlertDialog.Builder(getParentActivity())
                    .setTitle(getString(R.string.WireGuardInvalidConfig))
                    .setMessage(validationError)
                    .setPositiveButton(getString(R.string.OK), null)
                    .create());
            return;
        }

        WireGuardProfile saved = WireGuardManager.saveProfile(profile);
        if (addingNewProfile) {
            if (!NetworkRouteSettings.enableWireGuard(saved.id)) {
                showWireGuardUnavailable();
                return;
            }
        } else {
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
        }
        finishFragment();
    }

    private void showWireGuardUnavailable() {
        showDialog(new AlertDialog.Builder(getParentActivity())
                .setTitle(getString(R.string.UseWireGuardSettings))
                .setMessage(getString(R.string.WireGuardUnavailableInThisBuild))
                .setPositiveButton(getString(R.string.OK), null)
                .create());
    }

    private String text(int index) {
        return inputFields[index].getText().toString().trim();
    }

    private static String join(String[] values) {
        if (values == null || values.length == 0) {
            return "";
        }
        ArrayList<String> nonEmpty = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                nonEmpty.add(value.trim());
            }
        }
        return TextUtils.join(", ", nonEmpty);
    }
}
