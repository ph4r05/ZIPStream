package cz.muni.fi.xklinec.zipstream;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.zip.UnparseableExtraFieldData;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipExtraField;

/**
 * Hello world!
 *
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
    
    public static void main( String[] args ) throws FileNotFoundException, IOException, NoSuchFieldException, ClassNotFoundException, NoSuchMethodException
    {
        /*if (args==null || args.length!=2){
            System.out.println(String.format("Usage: %s file.apk", args[0]));
            return;
        }*/
        final String FNAME = "/tmp/a.apk";
        final String FNAMEO = "/tmp/b.apk";
        final Inflater inf = new Inflater(true);
        final Deflater def = new Deflater(9, true);
        exposeZip();
        
        FileInputStream fis = new FileInputStream(FNAME);
        ZipArchiveInputStream zip = new ZipArchiveInputStream(fis);
        
        // Simple ArchiveEntry
        ArchiveEntry entry = zip.getNextEntry();
        while(entry!=null){
            System.out.println(String.format("ZipEntry: size=%010d isDir=%5s name [%s]", entry.getSize(), entry.isDirectory(), entry.getName()));
            entry = zip.getNextEntry();
        }
        
        // Proof of concept - only one postponed entry
        List<PostponedEntry> peList = new ArrayList<PostponedEntry>(6);
        
        // Output stream
        FileOutputStream fos = new FileOutputStream(FNAMEO);
        ZipArchiveOutputStream zop = new ZipArchiveOutputStream(fos);
        zop.setLevel(9);
        
        // Reset stream and use ZipArchiveEntry
        fis = new FileInputStream(FNAME);
        zip = new ZipArchiveInputStream(fis);
        
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
            
            System.out.println(String.format("ZipEntry: meth=%d "
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
                for(PostponedEntry pe : peList){
                    System.out.println("Adding postponed entry at the end of the archive! deflSize=" 
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
                 System.out.println("### Interesting file, postpone sending!!!");
                 
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
        
        System.out.println( "THE END!" );
    }
}
