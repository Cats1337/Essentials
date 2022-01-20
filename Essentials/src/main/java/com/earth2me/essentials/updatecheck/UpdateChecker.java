package com.earth2me.essentials.updatecheck;

import com.earth2me.essentials.CommandSource;
import com.earth2me.essentials.Essentials;
import com.google.common.base.Charsets;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public final class UpdateChecker {
    private static final String REPO = "EssentialsX/Essentials";
    private static final String BRANCH = "2.x";

    private final Essentials ess;
    private final String versionIdentifier;
    private final String versionBranch;
    private final boolean devBuild;

    private long lastFetchTime = 0;
    private CompletableFuture<RemoteVersion> pendingDevFuture;
    private CompletableFuture<RemoteVersion> pendingReleaseFuture;
    private String latestRelease = null;
    private RemoteVersion cachedDev = null;
    private RemoteVersion cachedRelease = null;

    public UpdateChecker(Essentials ess) {
        String identifier = "INVALID";
        String branch = "INVALID";
        boolean dev = false;

        final InputStream inputStream = UpdateChecker.class.getClassLoader().getResourceAsStream("release");
        if (inputStream != null) {
            final List<String> versionStr = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8)).lines().collect(Collectors.toList());
            if (versionStr.size() == 2) {
                if (versionStr.get(0).matches("\\d+\\.\\d+\\.\\d+-(?:dev|rc|beta|alpha)\\+\\d+-[0-9a-f]{7,40}")) {
                    identifier = versionStr.get(0).split("-")[2];
                    dev = true;
                } else {
                    identifier = versionStr.get(0);
                }
                branch = versionStr.get(1);
            }
        }

        this.ess = ess;
        this.versionIdentifier = identifier;
        this.versionBranch = branch;
        this.devBuild = dev;
    }

    public boolean isDevBuild() {
        return devBuild;
    }

    public CompletableFuture<RemoteVersion> fetchLatestDev() {
        if (cachedDev == null || ((System.currentTimeMillis() - lastFetchTime) > 1800000L)) {
            if (pendingDevFuture != null) {
                return pendingDevFuture;
            }
            pendingDevFuture = new CompletableFuture<>();
            new Thread(() -> {
                pendingDevFuture.complete(cachedDev = fetchDistance(BRANCH, getVersionIdentifier()));
                pendingDevFuture = null;
                lastFetchTime = System.currentTimeMillis();
            }).start();
            return pendingDevFuture;
        }
        return CompletableFuture.completedFuture(cachedDev);
    }

    public CompletableFuture<RemoteVersion> fetchLatestRelease() {
        if (cachedRelease == null || ((System.currentTimeMillis() - lastFetchTime) > 1800000L)) {
            if (pendingReleaseFuture != null) {
                return pendingReleaseFuture;
            }
            pendingReleaseFuture = new CompletableFuture<>();
            new Thread(() -> {
                catchBlock:
                try {
                    final HttpURLConnection connection = (HttpURLConnection) new URL("https://api.github.com/repos/" + REPO + "/releases/latest").openConnection();
                    connection.connect();

                    if (connection.getResponseCode() == HttpURLConnection.HTTP_NOT_FOUND) {
                        // Locally built?
                        pendingReleaseFuture.complete(cachedRelease = new RemoteVersion(BranchStatus.UNKNOWN));
                        break catchBlock;
                    } else if (connection.getResponseCode() == HttpURLConnection.HTTP_INTERNAL_ERROR || connection.getResponseCode() == HttpURLConnection.HTTP_FORBIDDEN) {
                        // GitHub is down or rate limit exceeded
                        pendingReleaseFuture.complete(new RemoteVersion(BranchStatus.ERROR));
                        break catchBlock;
                    }

                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), Charsets.UTF_8))) {
                        latestRelease = new Gson().fromJson(reader, JsonObject.class).get("tag_name").getAsString();
                        pendingReleaseFuture.complete(cachedRelease = fetchDistance(latestRelease, getVersionIdentifier()));
                    } catch (JsonSyntaxException | NumberFormatException e) {
                        e.printStackTrace();
                        pendingReleaseFuture.complete(new RemoteVersion(BranchStatus.ERROR));
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    pendingReleaseFuture.complete(new RemoteVersion(BranchStatus.ERROR));
                }
                pendingReleaseFuture = null;
                lastFetchTime = System.currentTimeMillis();
            }).start();
            return pendingReleaseFuture;
        }
        return CompletableFuture.completedFuture(cachedRelease);
    }

    public String getVersionIdentifier() {
        return versionIdentifier;
    }

    public String getVersionBranch() {
        return versionBranch;
    }

    public String getBuildInfo() {
        return "id:'" + getVersionIdentifier() + "' branch:'" + getVersionBranch() + "' isDev:" + isDevBuild();
    }

    public String getLatestRelease() {
        return latestRelease;
    }

    private RemoteVersion fetchDistance(final String head, final String hash) {
        try {
            final HttpURLConnection connection = (HttpURLConnection) new URL("https://api.github.com/repos/" + REPO + "/compare/" + head + "..." + hash).openConnection();
            connection.connect();

            if (connection.getResponseCode() == HttpURLConnection.HTTP_NOT_FOUND) {
                // Locally built?
                return new RemoteVersion(BranchStatus.UNKNOWN);
            } else if (connection.getResponseCode() == HttpURLConnection.HTTP_INTERNAL_ERROR || connection.getResponseCode() == HttpURLConnection.HTTP_FORBIDDEN) {
                // GitHub is down or we hit a local rate limit
                return new RemoteVersion(BranchStatus.ERROR);
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), Charsets.UTF_8))) {
                final JsonObject obj = new Gson().fromJson(reader, JsonObject.class);
                switch (obj.get("status").getAsString()) {
                    case "identical": {
                        return new RemoteVersion(BranchStatus.IDENTICAL, 0);
                    }
                    case "ahead": {
                        return new RemoteVersion(BranchStatus.AHEAD, 0);
                    }
                    case "behind": {
                        return new RemoteVersion(BranchStatus.BEHIND, obj.get("behind_by").getAsInt());
                    }
                    case "diverged": {
                        return new RemoteVersion(BranchStatus.DIVERGED, obj.get("behind_by").getAsInt());
                    }
                    default: {
                        return new RemoteVersion(BranchStatus.UNKNOWN);
                    }
                }
            } catch (JsonSyntaxException | NumberFormatException e) {
                e.printStackTrace();
                return new RemoteVersion(BranchStatus.ERROR);
            }
        } catch (IOException e) {
            e.printStackTrace();
            return new RemoteVersion(BranchStatus.ERROR);
        }
    }

    public String[] getVersionMessages(final boolean sendLatestMessage, final boolean verboseErrors, final CommandSource source) {
        if (!ess.getSettings().isUpdateCheckEnabled()) {
            return new String[] {source.tl("versionCheckDisabled")};
        }

        if (this.isDevBuild()) {
            final RemoteVersion latestDev = this.fetchLatestDev().join();
            switch (latestDev.getBranchStatus()) {
                case IDENTICAL: {
                    return sendLatestMessage ? new String[] {source.tl("versionDevLatest")} : new String[] {};
                }
                case BEHIND: {
                    return new String[] {source.tl("versionDevBehind", latestDev.getDistance()),
                            source.tl("versionReleaseNewLink", "https://essentialsx.net/downloads.html")};
                }
                case AHEAD:
                case DIVERGED: {
                    return new String[] {source.tl(latestDev.getDistance() == 0 ? "versionDevDivergedLatest" : "versionDevDiverged", latestDev.getDistance()),
                            source.tl("versionDevDivergedBranch", this.getVersionBranch()) };
                }
                case UNKNOWN: {
                    return verboseErrors ? new String[] {source.tl("versionCustom", this.getBuildInfo())} : new String[] {};
                }
                case ERROR: {
                    return new String[] {source.tl(verboseErrors ? "versionError" : "versionErrorPlayer", this.getBuildInfo())};
                }
                default: {
                    return new String[] {};
                }
            }
        } else {
            final RemoteVersion latestRelease = this.fetchLatestRelease().join();
            switch (latestRelease.getBranchStatus()) {
                case IDENTICAL: {
                    return sendLatestMessage ? new String[] {source.tl("versionReleaseLatest")} : new String[] {};
                }
                case BEHIND: {
                    return new String[] {source.tl("versionReleaseNew", this.getLatestRelease()),
                            source.tl("versionReleaseNewLink", "https://essentialsx.net/downloads.html?branch=stable")};
                }
                case DIVERGED: //WhatChamp
                case AHEAD: //monkaW?
                case UNKNOWN: {
                    return verboseErrors ? new String[] {source.tl("versionCustom", this.getBuildInfo())} : new String[] {};
                }
                case ERROR: {
                    return new String[] {source.tl(verboseErrors ? "versionError" : "versionErrorPlayer", this.getBuildInfo())};
                }
                default: {
                    return new String[] {};
                }
            }
        }
    }

    private static class RemoteVersion {
        private final BranchStatus branchStatus;
        private final int distance;

        RemoteVersion(BranchStatus branchStatus) {
            this(branchStatus, 0);
        }

        RemoteVersion(BranchStatus branchStatus, int distance) {
            this.branchStatus = branchStatus;
            this.distance = distance;
        }

        public BranchStatus getBranchStatus() {
            return branchStatus;
        }

        public int getDistance() {
            return distance;
        }
    }

    private enum BranchStatus {
        IDENTICAL,
        AHEAD,
        BEHIND,
        DIVERGED,
        ERROR,
        UNKNOWN
    }
}
