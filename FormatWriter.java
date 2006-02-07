//
// FormatWriter.java
//

/*
LOCI Bio-Formats package for reading and converting biological file formats.
Copyright (C) 2005-2006 Melissa Linkert, Curtis Rueden and Eric Kjellman.

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 2 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/

package loci.formats;

import java.awt.Image;
import java.io.IOException;

/** Abstract superclass of all supported biological file format writers. */
public abstract class FormatWriter {

  /** Name of this file format. */
  protected String format;

  /** List of valid suffixes for this file format. */
  protected String[] suffixes;

  /** Name of current file. */
  protected String currentId;

  /** Percent complete with current operation. */
  protected double percent;

  /** Frame rate to use when writing in frames per second, if applicable. */
  protected int fps;


  // -- Constructors --

  /** Constructs a format writer with the given name and default suffix. */
  public FormatWriter(String format, String suffix) {
    this(format, suffix == null ? null : new String[] {suffix});
  }

  /** Constructs a format writer with the given name and default suffixes. */
  public FormatWriter(String format, String[] suffixes) {
    this.format = format;
    this.suffixes = suffixes == null ? new String[0] : suffixes;
  }


  // -- Abstract FormatWriter API methods --

  /**
   * Saves the given image to the specified (possibly already open) file.
   * If this image is the last one in the file, the last flag must be set.
   */
  public abstract void save(String id, Image image, boolean last)
    throws FormatException, IOException;


  // -- FormatWriter API methods --

  /** Saves the given images to the specified file. */
  public void save(String id, Image[] images)
    throws FormatException, IOException
  {
    percent = 0;
    for (int i=0; i<images.length; i++) {
      save(id, images[i], i < images.length - 1);
      percent = (double) (i+1) / images.length;
    }
    percent = Double.NaN;
  }

  /** Gets the name of this file format. */
  public String getFormat() { return format; }

  /** Gets the default file suffixes for this file format. */
  public String[] getSuffixes() { return suffixes; }

  /** Gets the percentage complete of the writer's current operation. */
  public double getPercentComplete() { return percent; }

  /** Sets the frames per second to use when writing. */
  public void setFramesPerSecond(int fps) { this.fps = fps; }

  /** Gets the frames per second to use when writing. */
  public int getFramesPerSecond() { return fps; }

}
