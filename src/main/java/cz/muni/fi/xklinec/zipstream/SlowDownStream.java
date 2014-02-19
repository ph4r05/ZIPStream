/*
 * Copyright (C) 2014 ph4r05
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package cz.muni.fi.xklinec.zipstream;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Slows down flushing the buffer to the output stream. 
 * 
 * @author ph4r05
 */
public class SlowDownStream extends OutputStream implements Runnable {
    private static final int BUFFER_SIZE = 5242880;
    private static final long SLEEP_TIME = 1;
    
    OutputStream out;
    
    /**
     * Flush thread
     */
    protected Thread thread;
    
    /**
     * Size of the buffer to flush on trigger event.
     */
    protected int flushBufferSize;
    
    /**
     * Buffer flush timeout in milliseconds.
     */
    protected long flushBufferTimeout;
    
    protected PipedOutputStream pos;
    protected PipedInputStream  pis;
    
    protected volatile int state;
    
    /**
     * Creates a new buffered output stream to write data to the specified
     * underlying output stream.
     *
     * @param out the underlying output stream.
     * @throws java.io.IOException
     */
    public SlowDownStream(OutputStream out) throws IOException {
        this(out, BUFFER_SIZE); // 5 MB
    }

    /**
     * Creates a new buffered output stream to write data to the specified
     * underlying output stream with the specified buffer size.
     *
     * @param out the underlying output stream.
     * @param size the buffer size.
     * @exception IllegalArgumentException if size &lt;= 0.
     * @throws java.io.IOException
     */
    public SlowDownStream(OutputStream out, int size) throws IOException {
        if (size <= 0) {
            throw new IllegalArgumentException("Buffer size <= 0");
        }
        
        this.out = out;
        pis = new PipedInputStream(size);
        pos = new PipedOutputStream(pis);
        
        thread = new Thread(this);
        
        flushBufferSize =    8192;
        flushBufferTimeout = 100;
        state = 0;
    }

    /**
     * Writes the specified byte to this buffered output stream.
     *
     * @param b the byte to be written.
     * @exception IOException if an I/O error occurs.
     */
    @Override
    public synchronized void write(int b) throws IOException {
        pos.write(b);
    }

    /**
     * Writes <code>len</code> bytes from the specified byte array starting at
     * offset <code>off</code> to this buffered output stream.
     *
     * @param b the data.
     * @param off the start offset in the data.
     * @param len the number of bytes to write.
     * @exception IOException if an I/O error occurs.
     */
    @Override
    public synchronized void write(byte b[], int off, int len) throws IOException {
        pos.write(b, off, len);
    }

    /**
     * Flushes this buffered output stream. This forces any buffered output
     * bytes to be written out to the underlying output stream.
     *
     * @exception IOException if an I/O error occurs.
     * @see java.io.FilterOutputStream#out
     */
    @Override
    public synchronized void flush() throws IOException {
        pos.flush();
    }

    /**
     * Closes output stream to signalize stream end.
     * @throws IOException
     */
    @Override
    public synchronized void close() throws IOException {
        pos.close();
    }
    
    public int getFlushBufferSize() {
        return flushBufferSize;
    }

    public void setFlushBufferSize(int flushBufferSize) {
        this.flushBufferSize = flushBufferSize;
    }

    public long getFlushBufferTimeout() {
        return flushBufferTimeout;
    }

    public void setFlushBufferTimeout(long flushBufferTimeout) {
        this.flushBufferTimeout = flushBufferTimeout;
    }
    
    public void start(){
        if (state!=0)
            throw new IllegalStateException("Cannot start, was already started once.");
        
        thread.start();
    }
    
    public boolean isRunning(){
        return thread!=null && thread.isAlive() && state!=3;
    }
    
    public void flushPipes(){
        if (state!=0)
            throw new IllegalStateException("Cannot flush pipes");
        
        state=1;
    }
    
    public void terminate(){
        state=2;
    }

    @Override
    public void run() {
        long lastInvocation = 0;
        byte[] readBuffer = new byte[flushBufferSize];
        
        while(state==0){
            long curTime = System.currentTimeMillis();
            try {
                if ((lastInvocation + flushBufferTimeout) <= curTime) {
                    lastInvocation = curTime;
                    
                    // Flush given buffer size to output buffer.
                    int read = pis.read(readBuffer, 0, readBuffer.length);
                    if (read>0){
                        out.write(readBuffer, 0, read);
                        out.flush();
                    } else {
                        break;
                    }
                }

                // Sleep for a small unit of time.
                Thread.sleep(SLEEP_TIME);
            } catch (Exception ex) {
                ex.printStackTrace(System.err);
                break;
            }
        }
        
System.err.println("SDS: finished, state=" + state + "; TID="+Thread.currentThread().getId());        
        // In this state keep flushing rapidly.
        if (state==1){
            int count;
            byte[] buffer = new byte[8192];
            try {
                while ((count = pis.read(buffer, 0, buffer.length)) > 0){
                    out.write(buffer, 0, count);
                    out.flush();
                }
            } catch(Exception ex){
                ex.printStackTrace(System.err);
            }
        }
        
        state=3;
        try {
            pis.close();
            pos.close();
        } catch (IOException ex) {
            ex.printStackTrace(System.err);
        }
        
System.err.println("SDS: finished, state=" + state + "; TID="+Thread.currentThread().getId());      
    }
}
