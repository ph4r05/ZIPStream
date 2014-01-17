package cz.muni.fi.xklinec.zipstream;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
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
    public static void exposeZip() throws NoSuchFieldException, ClassNotFoundException, NoSuchMethodException{
        ZipArchiveInputStream.class.getDeclaredField("inf").setAccessible(true);
        ZipArchiveInputStream.class.getDeclaredField("buf").setAccessible(true);
        ZipArchiveInputStream.class.getDeclaredField("in").setAccessible(true);
        
        ZipArchiveInputStream.class.getDeclaredMethod("readFully", new Class[]{ Class.forName("[B") }).setAccessible(true);
        ZipArchiveInputStream.class.getDeclaredMethod("closeEntry", new Class[]{ }).setAccessible(true);
    }
    /**
     * Reads whole section between current LocalFileHeader and the next Header 
     * from the archive.
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
    
    public static void main( String[] args ) throws FileNotFoundException, IOException, NoSuchFieldException, ClassNotFoundException, NoSuchMethodException
    {
        /*if (args==null || args.length!=2){
            System.out.println(String.format("Usage: %s file.apk", args[0]));
            return;
        }*/
        final String FNAME = "/tmp/a.apk";
        final String FNAMEO = "/tmp/b.apk";
        final Inflater inf = new Inflater(true);
        final Deflater def = new Deflater();
        exposeZip();
        
        FileInputStream fis = new FileInputStream(FNAME);
        ZipArchiveInputStream zip = new ZipArchiveInputStream(fis);
        
        // Simple ArchiveEntry
        ArchiveEntry entry = zip.getNextEntry();
        while(entry!=null){
            System.out.println(String.format("ZipEntry: size=%010d isDir=%5s name [%s]", entry.getSize(), entry.isDirectory(), entry.getName()));
            entry = zip.getNextEntry();
        }
        
        // Output stream
        FileOutputStream fos = new FileOutputStream(FNAMEO);
        ZipArchiveOutputStream zop = new ZipArchiveOutputStream(fos);
        
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
            byte[] eData = readAll(zip);
            int infl = eData.length;
            int defl = 0;
            
            if (ze.getMethod() == ZipArchiveOutputStream.DEFLATED){
                def.reset();
                def.setInput(eData);
                def.finish();
                
                defl = def.deflate(eData);
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
            
            
            if ("classes.dex".equalsIgnoreCase(ze.getName())){
                 System.out.println("### DEXED CLASS, postpone sending");
            } else {
                
                ArchiveEntry ne = new ZipArchiveEntry(null, FNAME);
                
            }
            
            ze = zip.getNextZipEntry();
        }
        
        // Try clasical java impl
        /*fis = new FileInputStream(FNAME);
        ZipInputStream zis = new ZipInputStream(zip);
        ZipEntry zz = zis.getNextEntry();
        */
        
        System.out.println( "THE END!" );
    }
}
