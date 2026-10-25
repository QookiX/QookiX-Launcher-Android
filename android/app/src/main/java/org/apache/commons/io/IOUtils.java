package org.apache.commons.io;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * {@code org.apache.commons.io.IOUtils} 的最小替身。
 *
 * <p><b>来源说明</b>：PojavLauncher 通过本地 jar
 * （{@code app_pojavlauncher/libs/ExagearApacheCommons.jar}）提供 {@code commons-io}。
 * 被移植的代码里只有 {@code ImportControlActivity} 用到 {@link #copy(InputStream, OutputStream)}
 * 一处，因此这里只实现该方法，避免为了一个拷贝循环引入 474KB 的整包 jar。
 * API 语义与 commons-io 一致，返回拷贝的字节数。
 */
public final class IOUtils {

    private static final int DEFAULT_BUFFER_SIZE = 8192;

    private IOUtils() {
    }

    public static int copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
        long count = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
            count += read;
        }
        return (int) count;
    }

    public static long copyLarge(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
        long count = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
            count += read;
        }
        return count;
    }

    public static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (IOException ignored) {
            // 故意忽略：与原库 closeQuietly 语义一致
        }
    }
}
