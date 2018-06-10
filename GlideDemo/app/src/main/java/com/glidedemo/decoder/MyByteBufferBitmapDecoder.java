package com.glidedemo.decoder;

import android.graphics.Bitmap;
import android.support.annotation.NonNull;
import android.util.Log;

import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.ResourceDecoder;
import com.bumptech.glide.load.engine.Resource;
import com.bumptech.glide.load.resource.bitmap.Downsampler;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * Decodes {@link Bitmap Bitmaps} from {@link ByteBuffer ByteBuffers}.
 */
public class MyByteBufferBitmapDecoder implements ResourceDecoder<ByteBuffer, Bitmap> {

  private static final String TAG = "MyByteBufferDecoder";
  private final Downsampler downsampler;

  public MyByteBufferBitmapDecoder(Downsampler downsampler) {
    this.downsampler = downsampler;
  }

  @Override
  public boolean handles(@NonNull ByteBuffer source, @NonNull Options options) {
    return downsampler.handles(source);
  }

  @Override
  public Resource<Bitmap> decode(@NonNull ByteBuffer source, int width, int height,
      @NonNull Options options)
      throws IOException {
    InputStream is = new MyByteBufferStream(source);
    return downsampler.decode(is, width, height, options);
  }


  private static class MyByteBufferStream extends InputStream {
    private static final int UNSET = -1;
    @NonNull private final ByteBuffer byteBuffer;
    private int markPos = UNSET;
    private long size;

    MyByteBufferStream(@NonNull ByteBuffer byteBuffer) {
      this.byteBuffer = byteBuffer;
      size = available();
      Log.d(TAG, "size -- " + size);
    }

    @Override
    public int available() {
      return byteBuffer.remaining();
    }

    @Override
    public int read() {
      if (!byteBuffer.hasRemaining()) {
        return -1;
      }
      return byteBuffer.get();
    }

    @Override
    public synchronized void mark(int readLimit) {
      markPos = byteBuffer.position();
    }

    @Override
    public boolean markSupported() {
      return true;
    }


    @Override
    public int read(@NonNull byte[] buffer, int byteOffset, int byteCount) throws IOException {
      Log.d(TAG, "read--progress  " + ((size - available()) * 100 / size) + ", THR " + Thread.currentThread());
      if (!byteBuffer.hasRemaining()) {
        return -1;
      }
      int toRead = Math.min(byteCount, available());
      byteBuffer.get(buffer, byteOffset, toRead);
      return toRead;
    }

    @Override
    public synchronized void reset() throws IOException {
      if (markPos == UNSET) {
        throw new IOException("Cannot reset to unset mark position");
      }
      // reset() was not implemented correctly in 4.0.4, so we track the mark position ourselves.
      byteBuffer.position(markPos);
    }

    @Override
    public long skip(long byteCount) throws IOException {
      if (!byteBuffer.hasRemaining()) {
        return -1;
      }

      long toSkip = Math.min(byteCount, available());
      byteBuffer.position((int) (byteBuffer.position() + toSkip));
      return toSkip;
    }
  }
}
