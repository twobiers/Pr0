package com.pr0gramm.app.services.config;


import org.immutables.gson.Gson;
import org.immutables.value.Value;

import java.util.Arrays;
import java.util.List;

import javax.annotation.Nullable;

/**
 */
@Value.Immutable
@Gson.TypeAdapters
public abstract class Config {
    @Value.Default
    public boolean getExtraCategories() {
        return true;
    }

    @Value.Default
    public long getMaxUploadSizeNormal() {
        return 6 * 1024 * 1024;
    }

    @Value.Default
    public long getMaxUploadSizePremium() {
        return 12 * 1024 * 1024;
    }

    @Value.Default
    public boolean getSearchUsingTagService() {
        return false;
    }

    @Value.Default
    public boolean getSecretSanta() {
        return false;
    }

    @Value.Default
    public AdType getAdType() {
        return AdType.NONE;
    }

    @Value.Default
    public boolean getTrackItemView() {
        return false;
    }

    @Value.Default
    public boolean getTrackVotes() {
        return false;
    }

    @Value.Default
    public boolean getForceSSL() {
        return false;
    }

    @Value.Default
    public List<String> getQuestionableTags() {
        return Arrays.asList(
                "0815", "kann weg", "heil hitler", "ban pls", "deshalb",
                "ab ins gas", "und weiter", "alles ist", "hure", "da drückste",
                "pr0paganda", "pr0gida", "für mehr", "dein scheiß", "kann ich auch");
    }

    @Value.Default
    public List<String> getReportReasons() {
        return Arrays.asList(
                "Regel #1 - Bild unzureichend getagged (nsfw/nsfl)",
                "Regel #2 - Gore/Porn/Suggestive Bilder mit Minderjährigen",
                "Regel #3 - Tierporn",
                "Regel #4 - Stumpfer Rassismus/Nazi-Nostalgie",
                "Regel #5 - Werbung/Spam",
                "Regel #6 - Infos zu Privatpersonen",
                "Regel #7 - Bildqualität",
                "Regel #12 - Warez/Logins zu Pay Sites",
                "Regel #14 - Screamer/Sound-getrolle",
                "Regel #15 - Reiner Musikupload",
                "Verstoß in den Tags",
                "Ich habe diesen Beitrag selbst erstellt und möchte ihn gelöscht haben");
    }

    @Nullable
    @Value.Default
    public MenuItem getSpecialMenuItem() {
        return null;
    }

    @Value.Immutable
    public interface MenuItem {
        String getName();

        String getIcon();

        String getLink();
    }

    public enum AdType {
        NONE,
        FEED,
        MAIN /* deprecated - dont use */;
    }
}
