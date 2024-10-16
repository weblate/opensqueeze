/*
 * Copyright (c) 2020-2022 The OpenSqueeze Authors. All Rights Reserved.
 * Use of this source code is governed by the license that can be found in the LICENSE file.
 */

package com.orangebikelabs.orangesqueeze.common;

import android.text.format.DateUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.google.common.base.Objects;
import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.google.common.io.ByteSource;
import com.google.common.primitives.Longs;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.orangebikelabs.orangesqueeze.cache.CacheEntry;
import com.orangebikelabs.orangesqueeze.cache.CacheFuture;
import com.orangebikelabs.orangesqueeze.cache.CacheRequestCallback;
import com.orangebikelabs.orangesqueeze.cache.CacheService;
import com.orangebikelabs.orangesqueeze.cache.CacheServiceProvider;
import com.orangebikelabs.orangesqueeze.cache.SBCacheException;
import com.orangebikelabs.orangesqueeze.common.SBRequest.Type;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

import java.util.Optional;

/**
 * Class that provides access to local track information by ID.
 *
 * @author tsandee
 */
public class TrackInfo {
    final static private int TRACKINFO_VERSION_TAG = 3;

    @Nonnull
    public static Optional<TrackInfo> peek(long serverId, String trackId) {
        try {
            Callback callback = new Callback(trackId, serverId);
            return CacheServiceProvider.get().peek(callback);
        } catch (SBCacheException e) {
            return Optional.empty();
        }
    }

    @Nonnull
    public static CacheFuture<TrackInfo> load(long serverId, String trackId, ListeningExecutorService executorService) {
        Callback callback = new Callback(trackId, serverId);
        return CacheServiceProvider.get().load(callback, executorService);
    }

    @Nonnull
    static private TrackInfo newTrackInfo(ByteSource byteSource) throws IOException {
        AtomicInteger size = new AtomicInteger();
        return new TrackInfo(JsonHelper.deserializeNode(byteSource, size), byteSource.read());
    }

    @Nonnull
    static private TrackInfo newTrackInfo(JsonNode node) throws IOException {

        AtomicInteger size = new AtomicInteger();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        JsonHelper.compactSerializeNode(node, baos, size);
        byte[] byteData = baos.toByteArray();
        return new TrackInfo(node, byteData);
    }

    static class Callback implements CacheRequestCallback<TrackInfo, byte[]> {
        @Nonnull
        final CacheEntry mCacheEntry;

        @Nonnull
        final String mTrackId;

        Callback(String trackId, long serverId) {
            mTrackId = trackId;
            mCacheEntry = new CacheEntry(CacheEntry.Type.SERVERSCAN, serverId, "TI" + TRACKINFO_VERSION_TAG + ":" + trackId);
        }

        @Override
        @Nonnull
        public TrackInfo onDeserializeCacheData(CacheService service, ByteSource byteSource, long expectedLength) throws IOException {
            return newTrackInfo(byteSource);
        }

        @Override
        @Nonnull
        public TrackInfo onLoadData(CacheService service) throws InterruptedException, IOException, SBCacheException {
            try {
                SBRequest request = SBContextProvider.get().newRequest(Type.COMET, "songinfo", "0", "500", "track_id:" + mTrackId, "tags:abcdefghijklmnopqrstuvwxyzABCDEFHIJKLMNOQRTUVWXY");
                request.setCacheable(false);

                FutureResult result = request.submit(MoreExecutors.newDirectExecutorService());

                return newTrackInfo(result.get().getJsonResult());
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if(cause == null) {
                    throw SBCacheException.wrap(e);
                }
                Throwables.throwIfInstanceOf(cause, InterruptedException.class);
                Throwables.throwIfInstanceOf(cause, IOException.class);
                Throwables.throwIfUnchecked(cause);

                throw SBCacheException.wrap(e);
            }
        }

        @Override
        @Nullable
        public ByteSource onSerializeForDatabaseCache(CacheService service, TrackInfo data, AtomicLong outEstimatedSize) throws IOException {
            if (!JsonHelper.isResponseCacheable(data.mRootNode)) {
                return null;
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();

            AtomicInteger size = new AtomicInteger();
            JsonHelper.compactSerializeNode(data.mRootNode, baos, size);

            byte[] bytes = baos.toByteArray();
            outEstimatedSize.set(bytes.length);

            return ByteSource.wrap(bytes);
        }

        @Override
        public int onEstimateMemorySize(CacheService service, byte[] toEstimate) {
            return toEstimate.length;
        }

        @Nullable
        @Override
        public byte[] onAdaptForMemoryCache(CacheService service, TrackInfo dataToAdapt) {
            return dataToAdapt.mByteData;
        }

        @Nonnull
        @Override
        public TrackInfo onAdaptFromMemoryCache(CacheService service, byte[] dataToAdapt) throws IOException {
            return newTrackInfo(ByteSource.wrap(dataToAdapt));
        }

        @Override
        @Nonnull
        public CacheEntry getEntry() {
            return mCacheEntry;
        }

        @Override
        public boolean shouldMarkFailedRequests() {
            return false;
        }
    }

    @Nonnull
    public static TrackInfo absent() {
        return new TrackInfo(MissingNode.getInstance(), null);
    }

    @GuardedBy("this")
    final private Map<String, JsonNode> mMappings = new HashMap<>();

    @Nonnull
    final protected JsonNode mRootNode;

    @Nullable
    final protected byte[] mByteData;

    protected TrackInfo(JsonNode node, @Nullable byte[] byteData) {
        mRootNode = node;
        mByteData = byteData;
        JsonNode loop = mRootNode.path("songinfo_loop");
        if (loop.isArray()) {
            for (int i = 0; i < loop.size(); i++) {
                JsonNode item = loop.get(i);
                Iterator<Map.Entry<String, JsonNode>> it = item.fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> entry = it.next();
                    mMappings.put(entry.getKey(), entry.getValue());
                }
            }
        }
    }

    @Nonnull
    public JsonNode getRootNode() {
        return mRootNode;
    }

    @Nonnull
    public String getTrackName() {
        return getString("title", "");
    }

    @Nullable
    public Long getFilesize() {
        Long retval = null;
        String value = getString("filesize");
        if (value != null) {
            retval = Longs.tryParse(value);
        }
        return retval;
    }

    @Nonnull
    public String getTrackUrl() {
        return getString("url", "");
    }

    @Nonnull
    public String getAlbumArtist() {
        return getString("albumartist", "");
    }

    @Nonnull
    public String getTrackArtist() {
        return getString("artist", "");
    }

    @Nonnull
    public Optional<String> getGenre() {
        return Optional.ofNullable(getString("genre"));
    }

    @Nonnull
    public Optional<String> getComments() {
        return Optional.ofNullable(getString("comment"));
    }

    @Nonnull
    public String getTrackAlbum() {
        return getString("album", "");
    }

    @Nonnull
    public Optional<String> getDiscNumber() {
        return Optional.ofNullable(getString("disc"));
    }

    @Nonnull
    public Optional<String> getDiscCount() {
        return Optional.ofNullable(getString("disccount"));
    }

    @Nonnull
    public String getContentType() {
        return getString("type", "");
    }

    @Nonnull
    public Optional<String> getArtistId() {
        return Optional.ofNullable(getString("artist_id"));
    }

    @Nonnull
    public Optional<String> getAlbumId() {
        return Optional.ofNullable(getString("album_id"));
    }

    @Nonnull
    public Optional<String> getYear() {
        Optional<String> retval = Optional.empty();
        String year = getString("year", "0");
        if (!year.equals("0") && !year.isEmpty()) {
            retval = Optional.of(year);
        }
        return retval;
    }

    @Nonnull
    public Optional<String> getTrackNumber() {
        Optional<String> retval = Optional.empty();
        String tracknum = getString("tracknum", "0");
        if (!tracknum.equals("0") && !tracknum.isEmpty()) {
            retval = Optional.of(tracknum);
        }
        return retval;
    }

    @Nonnull
    public Optional<String> getCoverId() {
        return Optional.ofNullable(getString("coverid"));
    }

    @Nonnull
    public String getText1() {
        StringBuilder builder = new StringBuilder();

        String artist = Strings.emptyToNull(getTrackArtist());
        String title = Strings.emptyToNull(getTrackName());

        String trackNum = getTrackNumber().orElse(null);
        if (trackNum != null) {
            builder.append(trackNum);
            builder.append(". ");
        }

        if (title != null) {
            builder.append(title);
        }

        if (artist != null) {
            builder.append(" (");
            builder.append(artist);
            builder.append(")");
        }
        return builder.toString();
    }

    @Nonnull
    public Optional<Float> getDuration() {
        Optional<Float> retval = Optional.empty();
        String duration = getString("duration", "0");
        if (!duration.equals("0")) {
            try {
                retval = Optional.of(Float.parseFloat(duration));
            } catch (NumberFormatException e) {
                // ignore
            }
        }
        return retval;
    }

    @Nonnull
    public Optional<String> getText2() {
        StringBuilder builder = new StringBuilder();
        String year = getYear().orElse(null);
        String album = Strings.emptyToNull(getTrackAlbum());

        if (Objects.equal(year, "0")) {
            year = null;
        }

        if (album != null) {
            builder.append(album);
        }

        if (year != null) {
            builder.append(" (");
            builder.append(year);
            builder.append(")");
        }
        if (builder.length() > 0) {
            return Optional.of(builder.toString());
        } else {
            return Optional.empty();
        }
    }

    @Nonnull
    public Optional<String> getText3() {
        Float duration = getDuration().orElse(null);
        if (duration == null) {
            return Optional.empty();
        } else {
            return Optional.of(DateUtils.formatElapsedTime(duration.longValue()));
        }
    }

    @Nullable
    synchronized private String getString(String key) {
        JsonNode node = mMappings.get(key);
        return node == null ? null : node.asText();
    }

    @Nonnull
    synchronized private String getString(String key, String defaultValue) {
        JsonNode node = mMappings.get(key);
        if (node == null) {
            return defaultValue;
        } else {
            return node.asText();
        }
    }
}