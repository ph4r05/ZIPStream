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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.bouncycastle.util.encoders.Base64;

/**
 *
 * @author ph4r05
 */
public class Utils {
    /**
     * Reflection magic - making some fields & methods accessible.
     * 
     * @deprecated 
     * @throws NoSuchFieldException
     * @throws ClassNotFoundException
     * @throws NoSuchMethodException 
     */
    public static void exposeZip() throws NoSuchFieldException, ClassNotFoundException, NoSuchMethodException{
        ZipArchiveInputStream.class.getDeclaredField("inf").setAccessible(true);
        ZipArchiveInputStream.class.getDeclaredField("buf").setAccessible(true);
        ZipArchiveInputStream.class.getDeclaredField("in").setAccessible(true);
        
        ZipArchiveInputStream.class.getDeclaredMethod("readFully", new Class[]{ Class.forName("[B") }).setAccessible(true);
        ZipArchiveInputStream.class.getDeclaredMethod("closeEntry", new Class[]{ }).setAccessible(true);
    }
    
    /**
     * Reads whole section between current LocalFileHeader and the next Header 
     * from the archive. If the ArchiveEntry being read is deflated, stream
     * automatically inflates the data. Output is always uncompressed.
     * 
     * @param zip
     * @return
     * @throws IOException 
     */
    public static byte[] readAll(ZipArchiveInputStream zip) throws IOException{
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        
        long skipped = 0;
        long value = Long.MAX_VALUE;
        byte[] b = new byte[1024];
        while (skipped != value) {
            long rem = value - skipped;
            int x = zip.read(b, 0, (int) (b.length > rem ? rem : b.length));
            if (x == -1) {
                return bos.toByteArray();
            }
            
            bos.write(b, 0, x);
            skipped += x;
        }
        
        return bos.toByteArray();
    }
    
    /**
     * Computes SHA256 hash of a given file.
     * @param b
     * @return 
     */
    public static String sha256(byte[] b){
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA256");
            InputStream fis = new ByteArrayInputStream(b);
            DigestInputStream dis = new DigestInputStream(fis, sha);
            
            byte[] buffer = new byte[65536]; // 64kB buffer
            while (dis.read(buffer) != -1){}
            
            byte[] hash = sha.digest();
            return new String(Base64.encode(hash));
            
        } catch(Exception e){
            throw new IllegalArgumentException("Cannot compute SHA256 digest of the file", e);
        }
    }
}
