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

import java.io.IOException;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;

/**
 *
 * @author ph4r05
 */
/**
 * Class for storing ZipEntries + corresponding data bytes.
 */
public class PostponedEntry{
    public ZipArchiveEntry ze;
    public byte[] byteData;
    public byte[] deflData;
    public String hashByte;
    public String hashDefl;
    int infl = 0;
    int defl = 0;
    
    public PostponedEntry(ZipArchiveEntry ze, byte[] byteData, byte[] deflData) {
       this.ze = (ZipArchiveEntry) ze.clone();
       this.defl = deflData.length;
       this.infl = byteData.length;
       this.byteData = new byte[byteData.length];
       this.deflData = new byte[deflData.length];
       System.arraycopy(byteData, 0, this.byteData, 0, byteData.length);
       System.arraycopy(deflData, 0, this.deflData, 0, deflData.length);
       this.hashByte = Utils.sha256(this.byteData);
       this.hashDefl = Utils.sha256(this.deflData);
   }
    
   public void dump(ZipArchiveOutputStream zop) throws IOException{
        zop.putArchiveEntry(ze);
        zop.write(byteData, 0, byteData.length);
        zop.closeArchiveEntry();
   }
}
