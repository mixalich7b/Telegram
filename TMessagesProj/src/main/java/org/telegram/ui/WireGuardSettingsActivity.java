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
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NetworkRouteSettings;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.TunnelConfigParser;
import org.telegram.messenger.TunnelManager;
import org.telegram.messenger.TunnelProtocol;
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
    private static final int FIELD_AMNEZIA_JC = 10;
    private static final int FIELD_AMNEZIA_JMIN = 11;
    private static final int FIELD_AMNEZIA_JMAX = 12;
    private static final int FIELD_AMNEZIA_S1 = 13;
    private static final int FIELD_AMNEZIA_S2 = 14;
    private static final int FIELD_AMNEZIA_S3 = 15;
    private static final int FIELD_AMNEZIA_S4 = 16;
    private static final int FIELD_AMNEZIA_H1 = 17;
    private static final int FIELD_AMNEZIA_H2 = 18;
    private static final int FIELD_AMNEZIA_H3 = 19;
    private static final int FIELD_AMNEZIA_H4 = 20;
    private static final int FIELD_AMNEZIA_I1 = 21;
    private static final int FIELD_AMNEZIA_I2 = 22;
    private static final int FIELD_AMNEZIA_I3 = 23;
    private static final int FIELD_AMNEZIA_I4 = 24;
    private static final int FIELD_AMNEZIA_I5 = 25;
    private static final int FIELD_COUNT_COMMON = 10;
    private static final int FIELD_COUNT_AMNEZIA = 26;

    private static final int DONE_BUTTON = 1;

    private final WireGuardProfile currentProfile;
    private final boolean addingNewProfile;

    private EditTextBoldCursor[] inputFields;
    private ActionBarMenuItem doneItem;

    public WireGuardSettingsActivity() {
        currentProfile = new WireGuardProfile();
        addingNewProfile = true;
    }

    public WireGuardSettingsActivity(TunnelProtocol protocol) {
        currentProfile = new WireGuardProfile();
        currentProfile.protocol = protocol == null ? TunnelProtocol.WIREGUARD : protocol;
        addingNewProfile = true;
    }

    public WireGuardSettingsActivity(WireGuardProfile profile) {
        currentProfile = profile == null ? new WireGuardProfile() : profile.copy();
        addingNewProfile = currentProfile.id == null || currentProfile.id.isEmpty();
    }

    @Override
    public View createView(Context context) {
        actionBar.setTitle(currentProfile.protocol == TunnelProtocol.AMNEZIA_WG ? getString(R.string.AmneziaWGDetails) : getString(R.string.WireGuardDetails));
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

        inputFields = new EditTextBoldCursor[currentProfile.protocol == TunnelProtocol.AMNEZIA_WG ? FIELD_COUNT_AMNEZIA : FIELD_COUNT_COMMON];
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
        if (currentProfile.protocol == TunnelProtocol.AMNEZIA_WG) {
            addField(context, fieldsContainer, FIELD_AMNEZIA_JC, getString(R.string.AmneziaWGJc), intValue(currentProfile.amneziaJc), InputType.TYPE_CLASS_NUMBER, false);
            addField(context, fieldsContainer, FIELD_AMNEZIA_JMIN, getString(R.string.AmneziaWGJmin), intValue(currentProfile.amneziaJmin), InputType.TYPE_CLASS_NUMBER, false);
            addField(context, fieldsContainer, FIELD_AMNEZIA_JMAX, getString(R.string.AmneziaWGJmax), intValue(currentProfile.amneziaJmax), InputType.TYPE_CLASS_NUMBER, false);
            addField(context, fieldsContainer, FIELD_AMNEZIA_S1, getString(R.string.AmneziaWGS1), intValue(currentProfile.amneziaS1), InputType.TYPE_CLASS_NUMBER, false);
            addField(context, fieldsContainer, FIELD_AMNEZIA_S2, getString(R.string.AmneziaWGS2), intValue(currentProfile.amneziaS2), InputType.TYPE_CLASS_NUMBER, false);
            addField(context, fieldsContainer, FIELD_AMNEZIA_S3, getString(R.string.AmneziaWGS3), intValue(currentProfile.amneziaS3), InputType.TYPE_CLASS_NUMBER, false);
            addField(context, fieldsContainer, FIELD_AMNEZIA_S4, getString(R.string.AmneziaWGS4), intValue(currentProfile.amneziaS4), InputType.TYPE_CLASS_NUMBER, false);
            addField(context, fieldsContainer, FIELD_AMNEZIA_H1, getString(R.string.AmneziaWGH1), currentProfile.amneziaH1, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, false);
            addField(context, fieldsContainer, FIELD_AMNEZIA_H2, getString(R.string.AmneziaWGH2), currentProfile.amneziaH2, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, false);
            addField(context, fieldsContainer, FIELD_AMNEZIA_H3, getString(R.string.AmneziaWGH3), currentProfile.amneziaH3, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, false);
            addField(context, fieldsContainer, FIELD_AMNEZIA_H4, getString(R.string.AmneziaWGH4), currentProfile.amneziaH4, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, false);
            addField(context, fieldsContainer, FIELD_AMNEZIA_I1, getString(R.string.AmneziaWGI1), currentProfile.amneziaI1, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, false, true);
            addField(context, fieldsContainer, FIELD_AMNEZIA_I2, getString(R.string.AmneziaWGI2), currentProfile.amneziaI2, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, false, true);
            addField(context, fieldsContainer, FIELD_AMNEZIA_I3, getString(R.string.AmneziaWGI3), currentProfile.amneziaI3, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, false, true);
            addField(context, fieldsContainer, FIELD_AMNEZIA_I4, getString(R.string.AmneziaWGI4), currentProfile.amneziaI4, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, false, true);
            addField(context, fieldsContainer, FIELD_AMNEZIA_I5, getString(R.string.AmneziaWGI5), currentProfile.amneziaI5, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, false, true);
        }

        checkDoneEnabled();
        return fragmentView;
    }

    private void addField(Context context, LinearLayout fieldsContainer, int index, String hint, String value, int inputType, boolean password) {
        addField(context, fieldsContainer, index, hint, value, inputType, password, false);
    }

    private void addField(Context context, LinearLayout fieldsContainer, int index, String hint, String value, int inputType, boolean password, boolean dialogEditor) {
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
        field.setInputType(inputType);
        field.setSingleLine(true);
        field.setMaxLines(1);
        field.setHorizontallyScrolling(!dialogEditor);
        field.setEllipsize(dialogEditor ? TextUtils.TruncateAt.END : null);
        field.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        field.setHeaderHintColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader));
        field.setTransformHintToHeader(true);
        field.setLineColors(Theme.getColor(Theme.key_windowBackgroundWhiteInputField), Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated), Theme.getColor(Theme.key_text_RedRegular));
        if (password) {
            field.setTransformationMethod(PasswordTransformationMethod.getInstance());
        }
        field.setImeOptions(index + 1 < inputFields.length ? EditorInfo.IME_ACTION_NEXT : EditorInfo.IME_ACTION_DONE);
        field.setHintText(hint);
        field.setText(value == null ? "" : value);
        field.setSelection(field.length());
        field.setPadding(0, 0, 0, 0);
        if (dialogEditor) {
            field.setFocusable(false);
            field.setCursorVisible(false);
            field.setClickable(true);
            field.setOnClickListener(v -> showLongTextEditor(index, hint));
        }
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

    private void showLongTextEditor(int index, String title) {
        if (getParentActivity() == null || inputFields == null || index < 0 || index >= inputFields.length || inputFields[index] == null) {
            return;
        }

        EditTextBoldCursor editor = new EditTextBoldCursor(getParentActivity());
        editor.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        editor.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        editor.setHintColor(Theme.getColor(Theme.key_dialogTextGray2));
        editor.setBackground(null);
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editor.setSingleLine(false);
        editor.setMinLines(5);
        editor.setMaxLines(10);
        editor.setHorizontallyScrolling(false);
        editor.setGravity(Gravity.LEFT | Gravity.TOP);
        editor.setImeOptions(EditorInfo.IME_ACTION_DONE);
        editor.setPadding(0, AndroidUtilities.dp(6), 0, AndroidUtilities.dp(6));
        editor.setText(inputFields[index].getText());
        editor.setSelection(editor.length());

        LinearLayout container = new LinearLayout(getParentActivity());
        container.setOrientation(LinearLayout.VERTICAL);
        container.addView(editor, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 0, 24, 10));

        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity())
                .setTitle(title)
                .setView(container)
                .setNegativeButton(getString(R.string.Cancel), null)
                .setPositiveButton(getString(R.string.OK), (dialog, which) -> {
                    inputFields[index].setText(editor.getText());
                    inputFields[index].setSelection(inputFields[index].length());
                    checkDoneEnabled();
                });
        builder.makeCustomMaxHeight();
        builder.setWidth(AndroidUtilities.dp(292));

        AlertDialog dialog = builder.create();
        showDialog(dialog);
        AndroidUtilities.runOnUIThread(() -> {
            editor.requestFocus();
            AndroidUtilities.showKeyboard(editor);
        }, 100);
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
        if (addingNewProfile && !TunnelManager.isSupported()) {
            showWireGuardUnsupported();
            return;
        }

        WireGuardProfile profile = currentProfile.copy();
        profile.protocol = currentProfile.protocol;
        profile.name = text(FIELD_NAME);
        profile.privateKey = text(FIELD_PRIVATE_KEY);
        profile.localAddresses = TunnelConfigParser.parseList(text(FIELD_ADDRESSES));
        profile.dnsServers = TunnelConfigParser.parseList(text(FIELD_DNS));
        profile.mtu = TextUtils.isEmpty(text(FIELD_MTU)) ? WireGuardProfile.DEFAULT_MTU : Utilities.parseInt(text(FIELD_MTU));
        profile.peerPublicKey = text(FIELD_PEER_PUBLIC_KEY);
        profile.presharedKey = text(FIELD_PRESHARED_KEY);
        profile.peerEndpoint = text(FIELD_ENDPOINT);
        profile.allowedIps = TunnelConfigParser.parseList(text(FIELD_ALLOWED_IPS));
        profile.persistentKeepaliveSeconds = TextUtils.isEmpty(text(FIELD_KEEPALIVE)) ? WireGuardProfile.DEFAULT_PERSISTENT_KEEPALIVE_SECONDS : Utilities.parseInt(text(FIELD_KEEPALIVE));
        if (profile.protocol == TunnelProtocol.AMNEZIA_WG) {
            profile.amneziaJc = intText(FIELD_AMNEZIA_JC);
            profile.amneziaJmin = intText(FIELD_AMNEZIA_JMIN);
            profile.amneziaJmax = intText(FIELD_AMNEZIA_JMAX);
            profile.amneziaS1 = intText(FIELD_AMNEZIA_S1);
            profile.amneziaS2 = intText(FIELD_AMNEZIA_S2);
            profile.amneziaS3 = intText(FIELD_AMNEZIA_S3);
            profile.amneziaS4 = intText(FIELD_AMNEZIA_S4);
            profile.amneziaH1 = text(FIELD_AMNEZIA_H1);
            profile.amneziaH2 = text(FIELD_AMNEZIA_H2);
            profile.amneziaH3 = text(FIELD_AMNEZIA_H3);
            profile.amneziaH4 = text(FIELD_AMNEZIA_H4);
            profile.amneziaI1 = text(FIELD_AMNEZIA_I1);
            profile.amneziaI2 = text(FIELD_AMNEZIA_I2);
            profile.amneziaI3 = text(FIELD_AMNEZIA_I3);
            profile.amneziaI4 = text(FIELD_AMNEZIA_I4);
            profile.amneziaI5 = text(FIELD_AMNEZIA_I5);
        }
        profile.normalize();

        String validationError = profile.validate();
        if (validationError != null) {
            showDialog(new AlertDialog.Builder(getParentActivity())
                    .setTitle(getString(R.string.TunnelInvalidConfig))
                    .setMessage(validationError)
                    .setPositiveButton(getString(R.string.OK), null)
                    .create());
            return;
        }

        WireGuardProfile saved;
        try {
            saved = TunnelManager.saveProfile(profile);
        } catch (Throwable e) {
            FileLog.e(e);
            showWireGuardUnsupported();
            return;
        }
        if (addingNewProfile) {
            if (!NetworkRouteSettings.enableTunnel(saved.id)) {
                showWireGuardUnsupported();
                return;
            }
        } else {
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
        }
        finishFragment();
    }

    private void showWireGuardUnsupported() {
        showDialog(new AlertDialog.Builder(getParentActivity())
                .setTitle(getString(R.string.UseTunnelSettings))
                .setMessage(getString(R.string.TunnelUnsupportedOnThisDevice))
                .setPositiveButton(getString(R.string.OK), null)
                .create());
    }

    private String text(int index) {
        if (index < 0 || inputFields == null || index >= inputFields.length || inputFields[index] == null) {
            return "";
        }
        return inputFields[index].getText().toString().trim();
    }

    private int intText(int index) {
        String value = text(index);
        return TextUtils.isEmpty(value) ? 0 : Utilities.parseInt(value);
    }

    private static String intValue(int value) {
        return value == 0 ? "" : String.valueOf(value);
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
