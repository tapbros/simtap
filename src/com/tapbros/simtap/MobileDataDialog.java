package com.tapbros.simtap;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import java.util.IllegalFormatException;

/** Match the localized resources used by SystemUI InternetDialog.showTurnOffMobileDialog. */
final class MobileDataDialog {
    private final String title, message, fallbackMessage, positive, negative;

    private MobileDataDialog(String title, String message, String fallbackMessage,
                             String positive, String negative) {
        this.title = normalize(title);
        this.message = normalize(message);
        this.fallbackMessage = normalize(fallbackMessage);
        this.positive = normalize(positive);
        this.negative = normalize(negative);
    }

    static MobileDataDialog load(Context context, String carrier) {
        try {
            Resources ui = context.getPackageManager().getResourcesForApplication(SimTapService.NET_PKG);
            Resources system = Resources.getSystem();
            String fallback = ui.getString(ui.getIdentifier(
                    "mobile_data_disable_message_default_carrier", "string", SimTapService.NET_PKG));
            int messageId = ui.getIdentifier("mobile_data_disable_message", "string", SimTapService.NET_PKG);
            return new MobileDataDialog(
                    ui.getString(ui.getIdentifier("mobile_data_disable_title", "string", SimTapService.NET_PKG)),
                    ui.getString(messageId, carrier == null || carrier.isEmpty() ? fallback : carrier),
                    ui.getString(messageId, fallback),
                    system.getString(system.getIdentifier(
                            "alert_windows_notification_turn_off_action", "string", "android")),
                    system.getString(android.R.string.cancel));
        } catch (PackageManager.NameNotFoundException | Resources.NotFoundException | IllegalFormatException e) {
            // Unknown OEM resources are not a reason to guess which dialog to confirm.
            return null;
        }
    }

    boolean matches(CharSequence title, CharSequence message, CharSequence positive, CharSequence negative) {
        return !this.title.isEmpty() && !this.message.isEmpty() && !this.fallbackMessage.isEmpty()
                && !this.positive.isEmpty() && !this.negative.isEmpty()
                && this.title.equals(normalize(title))
                && (this.message.equals(normalize(message)) || this.fallbackMessage.equals(normalize(message)))
                && this.positive.equals(normalize(positive)) && this.negative.equals(normalize(negative));
    }

    private static String normalize(CharSequence text) {
        return text == null ? "" : text.toString()
                .replaceAll("[\\u200E\\u200F\\u061C\\u202A-\\u202E\\u2066-\\u2069]", "").trim();
    }
}
