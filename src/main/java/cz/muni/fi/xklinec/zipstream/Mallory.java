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
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipExtraField;
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
    
    // receives other command line parameters than options
    @Argument
    private final List<String> arguments = new ArrayList<String>(8);
    
    @Option(name = "--cmd", aliases = {"-c"}, usage = "Command for APK modification. APK to modify will be passed as the first argument. "
            + "After command is finished, inputfile_tampered is axpected in the same folder as a result of modification. If not provided, "
            + "modification will be simulated. ")
    private String cmd=null;
    
    @Option(name = "--format", aliases = {"-f"}, usage = "Format of the cmd call. \n0=input APK file name is appended to the cmd"
            + "\n1=input apk is substituted for placeholder " + INPUT_APK_PLACEHOLDER
            + "\n2=as 1 + output apk is substituted for placeholder " + OUTPUT_APK_PLACEHOLDER)
    private int cmdFormat=0;
    
    @Option(name = "--out", aliases = {"-o"}, usage = "New APK file after tampering is finished.")
    private String outFile;
    
    @Option(name = "--quiet", aliases = {"-q"}, usage = "No output on stderr.")
    private boolean quiet = false;
    
    @Option(name = "--zip-align", aliases = {"-z"}, usage = "Apply ZIP align on resulting APK (stream output).")
    private boolean zipAlign = false;
    
    @Option(name = "--output-size", aliases = {"-s"}, usage = "Desired size of the resulting APK in bytes.")
    private long outBytes = 0;
    
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
    public void doMain( String[] args ) throws FileNotFoundException, IOException, NoSuchFieldException, ClassNotFoundException, NoSuchMethodException, InterruptedException
    {   
        OutputStream fos = null;
        InputStream  fis = null;
        
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
        
        if (outBytes!=0){
            System.err.println("WARNING: Padding to output length not implemented yet...");
            return;
        }
        
        // Deflater to re-compress uncompressed data read from ZIP stream.
        final Deflater def = new Deflater(9, true);
        
        // Generate temporary APK filename
        File tempApk = File.createTempFile(TEMP_DIR + "_temp_apk", ".apk");
        FileOutputStream tos = new FileOutputStream(tempApk);
        
        // What we want here is to read input stream from the socket/pipe 
        // whatever, process it in ZIP logic and simultaneously to copy 
        // all read data to the temporary file - this reminds tee command
        // logic. This functionality can be found in TeeInputStream.
        TeeInputStream tis = new TeeInputStream(fis, tos);
        
        // Providing tis to ZipArchiveInputStream will copy all read data
        // to temporary tos file.
        ZipArchiveInputStream zip = new ZipArchiveInputStream(tis);
        
        // List of all sent files, with data and hashes
        Map<String, PostponedEntry> alMap = new HashMap<String, PostponedEntry>();
        // List of postponed entries for further "processing".
        List<PostponedEntry> peList = new ArrayList<PostponedEntry>(6);
        // Priority postponed entries - at the end of the archive.
        List<PostponedEntry> prList = new ArrayList<PostponedEntry>(6);
        
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
            
            // Store zip entry to the map for later check after the APK is recompiled.
            // Hashes will be compared with the modified APK files after the process.
            PostponedEntry al = new PostponedEntry(ze, byteData, deflData);
            alMap.put(curName, al);
            
            // META-INF files should be always on the end of the archive, 
            // thus add postponed files right before them
            if (curName.startsWith("META-INF")){
                // Add to priority postponed data (meta inf files @ the end of the file).
                PostponedEntry pr = new PostponedEntry(ze, byteData, deflData);
                prList.add(pr);
            }
            
            // Capturing interesting files for us and store for later.
            // If the file is not interesting, send directly to the stream.
            if ("classes.dex".equalsIgnoreCase(curName)
                 || "AndroidManifest.xml".equalsIgnoreCase(curName)){
                
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
        
        // APK is finished here, all non-interesting files were sent to the 
        // zop strem (socket to the victim). Now APK transformation will
        // be performed, diff, sending rest of the files to zop.
        // 
        if (!quiet)
            System.err.println("APK reading finished, going to tamper downloaded "
                + " APK file ["+tempApk.toString()+"]; filezise=["+tempApk.length()+"]");
        
        // New APK was generated, new filename = "tempApk_tampered"
        File newApk = new File(outFile==null ? tempApk.getAbsolutePath() + "_tampered" : outFile);
        
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
        // Now read new APK file with ZipInputStream and push new files to the ZOP
        //
        fis = new FileInputStream(newApk);
        zip = new ZipArchiveInputStream(fis);
        
        // Read the archive
        ze = zip.getNextZipEntry();
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
                    System.err.println("Detected newly added file ["+curName+"]");
                
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
                    System.err.println("Detected modified file ["+curName+"]");
                
                al.dump(zop);
                
            } else if (curName.startsWith("META-INF")
                    || "classes.dex".equalsIgnoreCase(curName)
                    || "AndroidManifest.xml".equalsIgnoreCase(curName)){
                // File was not modified but is one of the postponed files, thus has to 
                // be flushed also.
                if (!quiet)
                    System.err.println("Postponed file not modified ["+curName+"]");
                
                al.dump(zop);
                
            }
            
            ze = zip.getNextZipEntry();
        }
        
        if (!quiet)
            System.err.println("Reading tampered APK finished, ZipOutputStream is now complete. Closing...");
        
        zop.finish();
        zop.close();
        fos.close();
        
        if (!quiet)
            System.err.println( "THE END!" );
    }
}
