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

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Deflater;
import org.apache.commons.compress.archivers.zip.UnparseableExtraFieldData;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipExtraField;

/**
 * Magic application for modifying ZIP files on the fly.
 * 
 * @author ph4r05 (Dusan Klinec)
 */
public class App 
{
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
     * Class for storing ZipEntries + corresponding data bytes.
     */
    public static class PostponedEntry{
        public PostponedEntry(ZipArchiveEntry ze, byte[] byteData, byte[] deflData) {
            this.ze = (ZipArchiveEntry) ze.clone();
            this.defl = deflData.length;
            this.infl = byteData.length;
            this.byteData = new byte[byteData.length];
            this.deflData = new byte[deflData.length];
            System.arraycopy(byteData, 0, this.byteData, 0, byteData.length);
            System.arraycopy(deflData, 0, this.deflData, 0, deflData.length);
        }
        
        public void dump(ZipArchiveOutputStream zop) throws IOException{
             zop.putArchiveEntry(ze);
             zop.write(byteData, 0, infl);  // will get deflated automatically if needed
             zop.closeArchiveEntry();
        }
        
        public ZipArchiveEntry ze;
        public byte[] byteData;
        public byte[] deflData;
        int infl = 0;
        int defl = 0;
    }
    
    /**
     * Entry point. 
     * 
     * @param args
     * @throws FileNotFoundException
     * @throws IOException
     * @throws NoSuchFieldException
     * @throws ClassNotFoundException
     * @throws NoSuchMethodException 
     */
    public static void main( String[] args ) throws FileNotFoundException, IOException, NoSuchFieldException, ClassNotFoundException, NoSuchMethodException, InterruptedException
    {   
        OutputStream fos = null;
        InputStream  fis = null;
                
        if ((args.length!=0 && args.length!=2)){
            System.err.println(String.format("Usage: app.jar source.apk dest.apk"));
            return;
        } else if (args.length==2){
            System.err.println(String.format("Will use file [%s] as input file and [%s] as output file", args[0], args[1]));
            fis = new FileInputStream(args[0]);
            fos = new FileOutputStream(args[1]);
        } else if (args.length==0){
            System.err.println(String.format("Will use file [STDIN] as input file and [STDOUT] as output file"));
            fis = System.in;
            fos = System.out;
        }
        
        final Deflater def = new Deflater(9, true);
        ZipArchiveInputStream zip = new ZipArchiveInputStream(fis);
        
        // List of postponed entries for further "processing".
        List<PostponedEntry> peList = new ArrayList<PostponedEntry>(6);
        
        // Output stream
        ZipArchiveOutputStream zop = new ZipArchiveOutputStream(fos);
        zop.setLevel(9);
        
        // Read the archive
        ZipArchiveEntry ze = zip.getNextZipEntry();
        while(ze!=null){
            
            ZipExtraField[] extra = ze.getExtraFields(true);
            byte[] lextra = ze.getLocalFileDataExtra();
            UnparseableExtraFieldData uextra = ze.getUnparseableExtraFieldData();
            byte[] uextrab = uextra != null ? uextra.getLocalFileDataData() : null;
            
            // ZipArchiveOutputStream.DEFLATED
            // 
            
            // Data for entry
            byte[] byteData = readAll(zip);
            byte[] deflData = new byte[0];
            int infl = byteData.length;
            int defl = 0;
            
            // If method is deflated, get the raw data (compress again).
            if (ze.getMethod() == ZipArchiveOutputStream.DEFLATED){
                def.reset();
                def.setInput(byteData);
                def.finish();
                
                byte[] deflDataTmp = new byte[byteData.length*2];
                defl = def.deflate(deflDataTmp);
                
                deflData = new byte[defl];
                System.arraycopy(deflDataTmp, 0, deflData, 0, defl);
            }
            
            System.err.println(String.format("ZipEntry: meth=%d "
                    + "size=%010d isDir=%5s "
                    + "compressed=%07d extra=%d lextra=%d uextra=%d "
                    + "comment=[%s] "
                    + "dataDesc=%s "
                    + "UTF8=%s "
                    + "infl=%07d defl=%07d "
                    + "name [%s]", 
                    ze.getMethod(),
                    ze.getSize(), ze.isDirectory(),
                    ze.getCompressedSize(),
                    extra!=null  ?  extra.length : -1,
                    lextra!=null ? lextra.length : -1,
                    uextrab!=null ? uextrab.length : -1,
                    ze.getComment(),
                    ze.getGeneralPurposeBit().usesDataDescriptor(),
                    ze.getGeneralPurposeBit().usesUTF8ForNames(),
                    infl, defl,
                    ze.getName()));
            
            final String curName = ze.getName();
            
            // META-INF files should be always on the end of the archive, 
            // thus add postponed files right before them
            if (curName.startsWith("META-INF") && peList.size()>0){
                System.err.println("Now is the time to put things back, but at first, I'll perform some \"facelifting\"...");
                
                // Simulate som evil being done
                Thread.sleep(5000);
                
                System.err.println("OK its done, let's do this.");
                for(PostponedEntry pe : peList){
                    System.err.println("Adding postponed entry at the end of the archive! deflSize=" 
                    + pe.deflData.length + "; inflSize=" + pe.byteData.length
                    + "; meth: " + pe.ze.getMethod());
                    
                    zop.putArchiveEntry(pe.ze);

                    zop.write(pe.byteData, 0, pe.byteData.length);
                    zop.closeArchiveEntry();
                }
                
                peList.clear();
            }
            
            // Capturing interesting files for us and store for later.
            // If the file is not interesting, send directly to the stream.
            if ("classes.dex".equalsIgnoreCase(curName)
                 || "AndroidManifest.xml".equalsIgnoreCase(curName)){
                 System.err.println("### Interesting file, postpone sending!!!");
                 
                 PostponedEntry pe = new PostponedEntry(ze, byteData, deflData);
                 peList.add(pe);
            } else {
                // Write ZIP entry to the archive
                zop.putArchiveEntry(ze);
                // Add file data to the stream
                zop.write(byteData, 0, infl);
                zop.closeArchiveEntry();
            }
            
            ze = zip.getNextZipEntry();
        }
 
        // Cleaning up stuff
        zip.close();
        fis.close();
        
        zop.finish();
        zop.close();
        fos.close();
        
        System.err.println( "THE END!" );
    }
}
