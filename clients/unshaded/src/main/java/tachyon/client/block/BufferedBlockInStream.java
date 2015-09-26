/*
 * Licensed to the University of California, Berkeley under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package tachyon.client.block;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

import com.google.common.base.Preconditions;
import tachyon.Constants;
import tachyon.client.BoundedStream;
import tachyon.client.ClientContext;
import tachyon.client.Seekable;
import tachyon.conf.TachyonConf;
import tachyon.thrift.NetAddress;
import tachyon.util.io.BufferUtils;
import tachyon.util.network.NetworkAddressUtils;

/**
 * Provides a stream API to read a block from Tachyon. An instance extending this class can be
 * obtained by calling {@link TachyonBlockStore#getInStream}. Multiple BlockInStreams can be opened
 * for a block. This class is not thread safe and should only be used by one thread.
 *
 * This class provides the same methods as a Java {@link InputStream} with additional methods from
 * Tachyon Stream interfaces.
 */
public abstract class BufferedBlockInStream extends InputStream implements BoundedStream, Seekable {
  private final long mBlockId;
  private final long mBlockSize;
  private final BlockStoreContext mContext;

  private ByteBuffer mBuffer;
  private boolean mClosed;
  private long mBufferPos;
  private long mPos;

  public BufferedBlockInStream(long blockId, long blockSize) {
    mBlockId = blockId;
    mBlockSize = blockSize;
    mBuffer = allocateBuffer();
    mClosed = false;
    mContext = BlockStoreContext.INSTANCE;
  }

  private ByteBuffer allocateBuffer() {
    TachyonConf conf = ClientContext.getConf();
    return ByteBuffer.allocate((int) conf.getBytes(Constants.USER_REMOTE_READ_BUFFER_SIZE_BYTE));
  }

  protected void checkIfClosed() {
    Preconditions.checkState(!mClosed, "Cannot do operations on a closed BlockInStream");
  }

  @Override
  public int read() throws IOException {
    checkIfClosed();
    if (mPos == mBlockSize) {
      close();
      return -1;
    }
    if (mBuffer.remaining() == 0) {
      updateBuffer();
    }
    mPos ++;
    incrementBytesReadMetric(1);
    return BufferUtils.byteToInt(mBuffer.get());
  }

  @Override
  public int read(byte[] b) throws IOException {
    return read(b, 0, b.length);
  }

  @Override
  public int read(byte[] b, int off, int len) throws IOException {
    checkIfClosed();
    Preconditions.checkArgument(b != null, "Read buffer cannot be null");
    Preconditions.checkArgument(off >= 0 && len >= 0 && len + off <= b.length, String.format
        ("Buffer length (%d), offset(%d), len(%d)", b.length, off, len));
    if (len == 0) {
      return 0;
    }

    int toRead = (int) Math.min(len, remaining());
    if (len > mBuffer.remaining()) {
      return directRead(b, off, toRead);
    } else {
      mBuffer.get(b, off, toRead);
      mPos += toRead;
      incrementBytesReadMetric(toRead);
      return toRead;
    }
  }

  @Override
  public long remaining() {
    return mBlockSize - mPos;
  }

  @Override
  public void seek(long pos) throws IOException {
    checkIfClosed();
    Preconditions.checkArgument(pos >= 0, "Seek position is negative: " + pos);
    Preconditions.checkArgument(pos <= mBlockSize, "Seek position is past end of block: "
        + mBlockSize);
    mPos = pos;
  }

  @Override
  public long skip(long n) throws IOException {
    checkIfClosed();
    if (n <= 0) {
      return 0;
    }

    long toSkip = Math.min(remaining(), n);
    mPos += toSkip;
    return toSkip;
  }

  protected long remainingInBuf() {
    return mPos - mBufferPos + mBuffer.remaining();
  }

  protected abstract void updateBuffer();

  protected abstract void incrementBytesReadMetric(int bytes);

  protected abstract int directRead(byte[] b, int off, int len);
}
