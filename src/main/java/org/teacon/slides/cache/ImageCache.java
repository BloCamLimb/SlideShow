package org.teacon.slides.cache;

import org.apache.commons.io.IOUtils;
import org.apache.http.Header;
import org.apache.http.HttpHeaders;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.cache.HttpCacheContext;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.cache.CacheConfig;
import org.apache.http.impl.client.cache.CachingHttpClients;
import org.apache.logging.log4j.*;
import org.teacon.slides.SlideShow;

import javax.annotation.Nonnull;
import javax.imageio.ImageIO;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class ImageCache {

    private static final Logger LOGGER = LogManager.getLogger(SlideShow.class);
    private static final Marker MARKER = MarkerManager.getMarker("Cache");

    private static final Path LOCAL_CACHE_PATH = Paths.get("slideshow");

    private static volatile ImageCache sInstance;

    private static final int MAX_CACHE_OBJECT_SIZE = 1 << 29; // 512 MiB
    private static final CacheConfig CONFIG =
            CacheConfig.custom().setMaxObjectSize(MAX_CACHE_OBJECT_SIZE).setSharedCache(false).build();

    private static final String DEFAULT_REFERER = "https://github.com/teaconmc/SlideShow";
    // user agent copied from forge gradle 2.3 (class: net.minecraftforge.gradle.common.Constants)
    private static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.11 (KHTML, " +
            "like Gecko) Chrome/23.0.1271.95 Safari/537.11";

    private final CloseableHttpClient mHttpClient;
    private final CacheStorage mCacheStorage;

    public static ImageCache getInstance() {
        if (sInstance != null) {
            return sInstance;
        }
        synchronized (ImageCache.class) {
            if (sInstance == null) {
                sInstance = new ImageCache(LOCAL_CACHE_PATH);
            }
        }
        return sInstance;
    }

    private ImageCache(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create cache directory for slide images.", e);
        }
        mCacheStorage = new CacheStorage(dir);
        mHttpClient = CachingHttpClients.custom().setCacheConfig(CONFIG).setHttpCacheStorage(mCacheStorage).build();
    }

    @Nonnull
    public CompletableFuture<byte[]> getResource(@Nonnull URI location, boolean online) {
        return CompletableFuture.supplyAsync(() -> {
            final HttpCacheContext context = HttpCacheContext.create();
            try (CloseableHttpResponse response = createResponse(location, context, online)) {
                try {
                    return IOUtils.toByteArray(response.getEntity().getContent());
                } catch (IOException e) {
                    if (online) {
                        LOGGER.warn(MARKER, "Failed to read bytes from remote source.", e);
                    }
                    throw new CompletionException(e);
                }
            } catch (ClientProtocolException protocolError) {
                LOGGER.warn(MARKER, "Detected invalid client protocol.", protocolError);
                throw new CompletionException(protocolError);
            } catch (IOException connError) {
                LOGGER.warn(MARKER, "Failed to establish connection.", connError);
                throw new CompletionException(connError);
            }
        });
    }

    private CloseableHttpResponse createResponse(URI location, HttpCacheContext context, boolean online) throws IOException {
        HttpGet request = new HttpGet(location);

        request.addHeader(HttpHeaders.REFERER, DEFAULT_REFERER);
        request.addHeader(HttpHeaders.USER_AGENT, DEFAULT_USER_AGENT);
        request.addHeader(HttpHeaders.ACCEPT, String.join(", ", ImageIO.getReaderMIMETypes()));

        if (!online) {
            request.addHeader(HttpHeaders.CACHE_CONTROL, "max-stale=2147483647");
            request.addHeader(HttpHeaders.CACHE_CONTROL, "only-if-cached");
        } else {
            request.addHeader(HttpHeaders.CACHE_CONTROL, "must-revalidate");
        }

        return mHttpClient.execute(request, context);
    }

    private void logRequestHeader(@Nonnull HttpCacheContext context) {
        LOGGER.debug(MARKER, " >> {}", context.getRequest().getRequestLine());
        for (Header header : context.getRequest().getAllHeaders()) {
            LOGGER.debug(MARKER, " >> {}", header);
        }
        LOGGER.debug(MARKER, " << {}", context.getResponse().getStatusLine());
        for (Header header : context.getResponse().getAllHeaders()) {
            LOGGER.debug(MARKER, " << {}", header);
        }
        LOGGER.debug(MARKER, "Remote server status: {}", context.getCacheResponseStatus());
    }

    public int cleanResources() {
        return mCacheStorage.cleanResources();
    }
}
