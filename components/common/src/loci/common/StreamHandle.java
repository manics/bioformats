/*
 * #%L
 * LOCI Common package: utilities for I/O, reflection and miscellaneous tasks.
 * %%
 * Copyright (C) 2008 - 2012 Open Microscopy Environment:
 *   - Board of Regents of the University of Wisconsin-Madison
 *   - Glencoe Software, Inc.
 *   - University of Dundee
 * %%
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * 
 * The views and conclusions contained in the software and documentation are
 * those of the authors and should not be interpreted as representing official
 * policies, either expressed or implied, of any organization.
 * #L%
 */

package loci.common;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Abstract IRandomAccess implementation for reading from InputStreams and
 * writing to OutputStreams.
 *
 * <dl><dt><b>Source code:</b></dt>
 * <dd><a href="http://trac.openmicroscopy.org.uk/ome/browser/bioformats.git/components/common/src/loci/common/StreamHandle.java">Trac</a>,
 * <a href="http://git.openmicroscopy.org/?p=bioformats.git;a=blob;f=components/common/src/loci/common/StreamHandle.java;hb=HEAD">Gitweb</a></dd></dl>
 *
 * @see IRandomAccess
 *
 * @author Melissa Linkert melissa at glencoesoftware.com
 */
public abstract class StreamHandle implements IRandomAccess {

  // -- Fields --

  /** Name of the open stream. */
  protected String file;

  /** InputStream to be used for reading. */
  protected DataInputStream stream;

  /** OutputStream to be used for writing. */
  protected DataOutputStream outStream;

  /** Length of the stream. */
  protected long length;

  /** Current position within the stream. */
  protected long fp;

  /** Marked position within the stream. */
  protected long mark;

  /** Byte ordering of this stream. */
  protected ByteOrder order;

  // -- Constructor --

  /**
   * Construct a new StreamHandle.
   * The file pointer will be set to 0, and the byte ordering
   * will be big-endian.
   */
  public StreamHandle() {
    fp = 0;
    order = ByteOrder.BIG_ENDIAN;
  }

  // -- IRandomAccess API methods --

  /* @see IRandomAccess#close() */
  public void close() throws IOException {
    length = fp = mark = 0;
    if (stream != null) stream.close();
    if (outStream != null) outStream.close();
    stream = null;
    outStream = null;
    file = null;
  }

  /* @see IRandomAccess#getFilePointer() */
  public long getFilePointer() throws IOException {
    return fp;
  }

  /* @see IRandomAccess#length() */
  public long length() throws IOException {
    return length;
  }

  /* @see IRandomAccess#read(byte[]) */
  public int read(byte[] b) throws IOException {
    return read(b, 0, b.length);
  }

  /* @see IRandomAccess#read(byte[], int, int) */
  public int read(byte[] b, int off, int len) throws IOException {
    int n = stream.read(b, off, len);
    if (n >= 0) fp += n;
    else n = 0;
    markManager();
    while (n < len && fp < length()) {
      int s = stream.read(b, off + n, len - n);
      fp += s;
      n += s;
    }
    return n == -1 ? 0 : n;
  }

  /* @see IRandomAccess#read(ByteBuffer) */
  public int read(ByteBuffer buffer) throws IOException {
    return read(buffer, 0, buffer.capacity());
  }

  /* @see IRandomAccess#read(ByteBuffer, int, int) */
  public int read(ByteBuffer buffer, int off, int len) throws IOException {
    if (buffer.hasArray()) {
      return read(buffer.array(), off, len);
    }

    byte[] b = new byte[len];
    int n = read(b);
    buffer.put(b, off, len);
    return n;
  }

  /* @see IRandomAccess#seek(long) */
  public void seek(long pos) throws IOException {
    long diff = pos - fp;
    fp = pos;

    if (diff < 0) {
      resetStream();
      diff = fp;
    }
    int skipped = stream.skipBytes((int) diff);
    while (skipped < diff) {
      int n = stream.skipBytes((int) (diff - skipped));
      if (n == 0) break;
      skipped += n;
    }
  }

  /* @see IRandomAccess.write(ByteBuffer) */
  public void write(ByteBuffer buf) throws IOException {
    write(buf, 0, buf.capacity());
  }

  /* @see IRandomAccess.write(ByteBuffer, int, int) */
  public void write(ByteBuffer buf, int off, int len) throws IOException {
    buf.position(off);
    if (buf.hasArray()) {
      write(buf.array(), off, len);
    }
    else {
      byte[] b = new byte[len];
      buf.get(b);
      write(b);
    }
  }

  /* @see IRandomAccess.getOrder() */
  public ByteOrder getOrder() {
    return order;
  }

  /* @see IRandomAccess.setOrder(ByteOrder) */
  public void setOrder(ByteOrder order) {
    this.order = order;
  }

  // -- DataInput API methods --

  /* @see java.io.DataInput#readBoolean() */
  public boolean readBoolean() throws IOException {
    fp++;
    return stream.readBoolean();
  }

  /* @see java.io.DataInput#readByte() */
  public byte readByte() throws IOException {
    fp++;
    return stream.readByte();
  }

  /* @see java.io.DataInput#readChar() */
  public char readChar() throws IOException {
    fp++;
    return stream.readChar();
  }

  /* @see java.io.DataInput#readDouble() */
  public double readDouble() throws IOException {
    fp += 8;
    double v = stream.readDouble();
    return order.equals(ByteOrder.LITTLE_ENDIAN) ? DataTools.swap(v) : v;
  }

  /* @see java.io.DataInput#readFloat() */
  public float readFloat() throws IOException {
    fp += 4;
    float v = stream.readFloat();
    return order.equals(ByteOrder.LITTLE_ENDIAN) ? DataTools.swap(v) : v;
  }

  /* @see java.io.DataInput#readFully(byte[]) */
  public void readFully(byte[] b) throws IOException {
    stream.readFully(b);
    fp += b.length;
  }

  /* @see java.io.DataInput#readFully(byte[], int, int) */
  public void readFully(byte[] b, int off, int len) throws IOException {
    stream.readFully(b, off, len);
    fp += len;
  }

  /* @see java.io.DataInput#readInt() */
  public int readInt() throws IOException {
    fp += 4;
    int v = stream.readInt();
    return order.equals(ByteOrder.LITTLE_ENDIAN) ? DataTools.swap(v) : v;
  }

  /* @see java.io.DataInput#readLine() */
  public String readLine() throws IOException {
    throw new IOException("Unimplemented");
  }

  /* @see java.io.DataInput#readLong() */
  public long readLong() throws IOException {
    fp += 8;
    long v = stream.readLong();
    return order.equals(ByteOrder.LITTLE_ENDIAN) ? DataTools.swap(v) : v;
  }

  /* @see java.io.DataInput#readShort() */
  public short readShort() throws IOException {
    fp += 2;
    short v = stream.readShort();
    return order.equals(ByteOrder.LITTLE_ENDIAN) ? DataTools.swap(v) : v;
  }

  /* @see java.io.DataInput#readUnsignedByte() */
  public int readUnsignedByte() throws IOException {
    fp++;
    return stream.readUnsignedByte();
  }

  /* @see java.io.DataInput#readUnsignedShort() */
  public int readUnsignedShort() throws IOException {
    return readShort() & 0xffff;
  }

  /* @see java.io.DataInput#readUTF() */
  public String readUTF() throws IOException {
    String s = stream.readUTF();
    fp += s.length();
    return s;
  }

  /* @see java.io.DataInput#skipBytes(int) */
  public int skipBytes(int n) throws IOException {
    int skipped = 0;
    try {
      for (int i=0; i<n; i++) {
        if (readUnsignedByte() != -1) skipped++;
        markManager();
      }
    }
    catch (EOFException e) { }
    return skipped;
  }

  // -- DataOutput API methods --

  /* @see java.io.DataOutput#write(byte[]) */
  public void write(byte[] b) throws IOException {
    if (outStream == null) {
      throw new HandleException("This stream is read-only.");
    }
    outStream.write(b);
  }

  /* @see java.io.DataOutput#write(byte[], int, int) */
  public void write(byte[] b, int off, int len) throws IOException {
    if (outStream == null) {
      throw new HandleException("This stream is read-only.");
    }
    outStream.write(b, off, len);
  }

  /* @see java.io.DataOutput#write(int) */
  public void write(int b) throws IOException {
    if (outStream == null) {
      throw new HandleException("This stream is read-only.");
    }
    if (order.equals(ByteOrder.LITTLE_ENDIAN)) b = DataTools.swap(b);
    outStream.write(b);
  }

  /* @see java.io.DataOutput#writeBoolean(boolean) */
  public void writeBoolean(boolean v) throws IOException {
    if (outStream == null) {
      throw new HandleException("This stream is read-only.");
    }
    outStream.writeBoolean(v);
  }

  /* @see java.io.DataOutput#writeByte(int) */
  public void writeByte(int v) throws IOException {
    if (outStream == null) {
      throw new HandleException("This stream is read-only.");
    }
    if (order.equals(ByteOrder.LITTLE_ENDIAN)) v = DataTools.swap(v);
    outStream.writeByte(v);
  }

  /* @see java.io.DataOutput#writeBytes(String) */
  public void writeBytes(String s) throws IOException {
    if (outStream == null) {
      throw new HandleException("This stream is read-only.");
    }
    outStream.writeBytes(s);
  }

  /* @see java.io.DataOutput#writeChar(int) */
  public void writeChar(int v) throws IOException {
    if (outStream == null) {
      throw new HandleException("This stream is read-only.");
    }
    if (order.equals(ByteOrder.LITTLE_ENDIAN)) v = DataTools.swap(v);
    outStream.writeChar(v);
  }

  /* @see java.io.DataOutput#writeChars(String) */
  public void writeChars(String s) throws IOException {
    if (outStream == null) {
      throw new HandleException("This stream is read-only.");
    }
    outStream.writeChars(s);
  }

  /* @see java.io.DataOutput#writeDouble(double) */
  public void writeDouble(double v) throws IOException {
    if (outStream == null) {
      throw new HandleException("This stream is read-only.");
    }
    if (order.equals(ByteOrder.LITTLE_ENDIAN)) v = DataTools.swap(v);
    outStream.writeDouble(v);
  }

  /* @see java.io.DataOutput#writeFloat(float) */
  public void writeFloat(float v) throws IOException {
    if (outStream == null) {
      throw new HandleException("This stream is read-only.");
    }
    if (order.equals(ByteOrder.LITTLE_ENDIAN)) v = DataTools.swap(v);
    outStream.writeFloat(v);
  }

  /* @see java.io.DataOutput#writeInt(int) */
  public void writeInt(int v) throws IOException {
    if (outStream == null) {
      throw new HandleException("This stream is read-only.");
    }
    if (order.equals(ByteOrder.LITTLE_ENDIAN)) v = DataTools.swap(v);
    outStream.writeInt(v);
  }

  /* @see java.io.DataOutput#writeLong(long) */
  public void writeLong(long v) throws IOException {
    if (outStream == null) {
      throw new HandleException("This stream is read-only.");
    }
    if (order.equals(ByteOrder.LITTLE_ENDIAN)) v = DataTools.swap(v);
    outStream.writeLong(v);
  }

  /* @see java.io.DataOutput#writeShort(int) */
  public void writeShort(int v) throws IOException {
    if (outStream == null) {
      throw new HandleException("This stream is read-only.");
    }
    if (order.equals(ByteOrder.LITTLE_ENDIAN)) v = DataTools.swap(v);
    outStream.writeShort(v);
  }

  /* @see java.io.DataOutput#writeUTF(String) */
  public void writeUTF(String str) throws IOException {
    if (outStream == null) {
      throw new HandleException("This stream is read-only.");
    }
    outStream.writeUTF(str);
  }

  // -- Helper methods --

  /**
   * Close and reopen the stream; the stream pointer and mark should be
   * reset to 0.  This method is called if we need to seek backwards within
   * the stream.
   */
  protected abstract void resetStream() throws IOException;

  /** Reset the marked position, if necessary. */
  private void markManager() {
    if (fp >= mark + RandomAccessInputStream.MAX_OVERHEAD - 1) {
      mark = fp;
      stream.mark(RandomAccessInputStream.MAX_OVERHEAD);
    }
  }

}
